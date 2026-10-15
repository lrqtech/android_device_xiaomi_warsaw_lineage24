package org.lineageos.warsaw.cameramind;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import com.miui.cameraopt.intentaware.FrameCodec;
import com.miui.cameraopt.intentaware.PeerIdentity;
import com.miui.cameraopt.intentaware.Protocol;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

public final class MindSocketServer implements Closeable {
    public interface Publisher { void publish(String payload) throws IOException; }

    public static final class Bootstrap {
        public final Map<String, ?> configuration;
        public final List<String> stickyMessages;
        public Bootstrap(Map<String, ?> configuration, List<String> stickyMessages) {
            this.configuration = configuration == null ? null
                    : Collections.unmodifiableMap(new HashMap<>(configuration));
            this.stickyMessages = Collections.unmodifiableList(new ArrayList<>(stickyMessages));
            if (this.stickyMessages.size() > 64) throw new IllegalArgumentException("Sticky message limit");
        }
    }

    public interface Backend {
        Bootstrap connected(PeerIdentity peer, Publisher publisher) throws Exception;
        void message(PeerIdentity peer, int wireSessionId, JSONObject event, Publisher publisher) throws Exception;
        void disconnected(PeerIdentity peer);
    }

    private static final String TAG = "WarsawMindSocket";
    private final Object guard = new Object();
    private final PeerIdentity.Authorizer authorizer;
    private final Backend backend;
    private final Set<Session> sessions = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread t = new Thread(runnable, "MindSocketSession");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private LocalServerSocket server;
    private boolean started;
    private volatile boolean closed;

    public MindSocketServer(PeerIdentity.Authorizer authorizer, Backend backend) {
        this.authorizer = Objects.requireNonNull(authorizer);
        this.backend = Objects.requireNonNull(backend);
    }

    public void start() throws IOException {
        synchronized (guard) {
            if (closed || started) throw new IllegalStateException("CameraMind socket lifecycle");
            server = new LocalServerSocket(FrameCodec.SOCKET_NAME);
            started = true;
            Thread acceptor = new Thread(this::acceptLoop, "MindSocketAccept");
            acceptor.setDaemon(true);
            acceptor.start();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            LocalSocket socket = null;
            Session session = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(1000);
                session = new Session(socket);
                synchronized (guard) {
                    if (closed || sessions.size() >= 8) throw new IOException("CameraMind connection capacity/lifecycle");
                    sessions.add(session);
                }
                workers.execute(session);
            } catch (RejectedExecutionException e) {
                if (session != null) session.close();
                else closeSocket(socket);
            } catch (IOException | RuntimeException e) {
                if (session != null) session.close();
                else closeSocket(socket);
                if (socket == null) {
                    if (!closed) Log.e(TAG, "CameraMind listener stopped", e);
                    close();
                    return;
                }
            }
        }
    }

    public int publishTo(String processName, String payload) throws IOException {
        List<Session> targets = new ArrayList<>();
        synchronized (guard) {
            for (Session session : sessions) {
                PeerIdentity peer = session.peer;
                if (peer != null && peer.name.equals(processName)) targets.add(session);
            }
        }
        int written = 0;
        for (Session session : targets) { session.publish(payload); written++; }
        return written;
    }

    public void close() {
        List<Session> closing;
        LocalServerSocket listener;
        synchronized (guard) {
            if (closed) return;
            closed = true;
            listener = server;
            closing = new ArrayList<>(sessions);
        }
        if (listener != null) {
            try { Os.shutdown(listener.getFileDescriptor(), OsConstants.SHUT_RDWR); }
            catch (ErrnoException ignored) { }
            try { listener.close(); } catch (IOException ignored) { }
        }
        for (Session session : closing) session.close();
        workers.shutdownNow();
    }

    private final class Session implements Runnable, Publisher, Closeable {
        final LocalSocket socket;
        final Object outputLock = new Object();
        volatile PeerIdentity peer;
        volatile boolean ended;

        Session(LocalSocket socket) { this.socket = socket; }

        public void run() {
            try {
                if (!FrameCodec.awaitReadable(socket, 1000)) throw new IOException("Identification timeout");
                FrameCodec.Frame first = FrameCodec.read(socket);
                JSONObject identity = Protocol.parse(first.payload);
                if (identity.getInt("what") != Protocol.IDENTIFY) throw new IOException("Identification must be first");
                String name = identity.getJSONObject("data").getString("my_name");
                peer = authorizer.verify(socket, name);
                Bootstrap initial = Objects.requireNonNull(backend.connected(peer, this));
                if (initial.configuration != null) {
                    publish(new Protocol().setWhat(Protocol.CONFIGURATION).setValue(initial.configuration).buildMessage());
                }
                for (String sticky : initial.stickyMessages) publish(sticky);
                while (!closed && !ended) {
                    if (!FrameCodec.awaitReadable(socket, 1000)) continue;
                    FrameCodec.Frame frame = FrameCodec.read(socket);
                    JSONObject event = Protocol.parse(frame.payload);
                    if (event.getInt("what") == Protocol.IDENTIFY) throw new IOException("Peer identity cannot change");
                    backend.message(peer, frame.sessionId, event, this);
                }
            } catch (Exception e) {
                if (!closed && !ended) Log.w(TAG, "CameraMind peer disconnected", e);
            } finally {
                close();
                PeerIdentity identity = peer;
                if (identity != null) {
                    try { backend.disconnected(identity); }
                    catch (RuntimeException e) { Log.e(TAG, "CameraMind backend disconnect failed", e); }
                }
            }
        }

        public void publish(String payload) throws IOException {
            if (peer == null || closed || ended) throw new IOException("No authenticated CameraMind session");
            try { Protocol.parse(payload); }
            catch (JSONException e) { throw new IOException("Invalid CameraMind publication", e); }
            synchronized (outputLock) {
                if (closed || ended) throw new IOException("Closed CameraMind session");
                try { FrameCodec.write(socket.getOutputStream(), Protocol.nextSessionId(), payload); }
                catch (IOException e) { close(); throw e; }
            }
        }

        public void close() {
            ended = true;
            closeSocket(socket);
            synchronized (guard) { sessions.remove(this); }
        }
    }

    private static void closeSocket(LocalSocket socket) {
        if (socket == null) return;
        try { socket.shutdownInput(); } catch (IOException ignored) { }
        try { socket.shutdownOutput(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
