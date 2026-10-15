package com.miui.cameraopt.intentaware.client;

import android.app.ActivityThread;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;
import com.miui.cameraopt.intentaware.FrameCodec;
import com.miui.cameraopt.intentaware.PeerIdentity;
import com.miui.cameraopt.intentaware.Protocol;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

public final class CamOptClient {
    public interface SocketListener { void onReceivedEvent(String event); }
    public enum State { DISABLED, CONNECTING, CONNECTED, BACKOFF, CLOSED }
    private static final String TAG = "WarsawCamOptClient";
    private static final String SUPPORT = "persist.sys.miui.camera.cameramind.supported";
    private static CamOptClient instance;
    private final Object guard = new Object();
    private final Context context;
    private final ArrayBlockingQueue<Pending> writes = new ArrayBlockingQueue<>(16);
    private final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> daemon("CamOptCallbacks", runnable),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile State state = State.DISABLED;
    private volatile boolean closed;
    private volatile Session active;
    private LocalSocket connecting;
    private SocketListener listener;
    private long listenerEpoch;
    private long generation;
    private Thread reader;
    private Thread writer;

    private static final class Session {
        final long generation;
        final LocalSocket socket;
        Session(long generation, LocalSocket socket) { this.generation = generation; this.socket = socket; }
    }
    private static final class Pending {
        final Session session;
        final String text;
        Pending(Session session, String text) { this.session = session; this.text = text; }
    }

    public static synchronized CamOptClient getInstance() {
        if (instance == null) instance = new CamOptClient(ActivityThread.currentApplication());
        return instance;
    }

    private CamOptClient(Context context) {
        this.context = context;
        if (!SystemProperties.getBoolean(SUPPORT, false)) return;
        if (context == null) {
            Log.e(TAG, "Application context unavailable; CameraMind identity cannot be verified");
            state = State.CLOSED;
            closed = true;
            callbacks.shutdown();
            return;
        }
        reader = daemon("CamOptConnection", this::connectionLoop);
        writer = daemon("CamOptWriter", this::writerLoop);
        state = State.CONNECTING;
        writer.start();
        reader.start();
    }

    private static Thread daemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public State getConnectionState() { return state; }

    public void setListener(SocketListener value) {
        synchronized (guard) {
            listener = value;
            listenerEpoch++;
            callbacks.getQueue().clear();
        }
    }

    public void disConect() {
        final LocalSocket pending;
        final Session session;
        synchronized (guard) {
            if (closed) return;
            closed = true;
            state = State.CLOSED;
            generation++;
            listenerEpoch++;
            listener = null;
            pending = connecting;
            session = active;
            connecting = null;
            active = null;
            writes.clear();
            guard.notifyAll();
        }
        close(pending);
        if (session != null) close(session.socket);
        if (reader != null) reader.interrupt();
        if (writer != null) writer.interrupt();
        callbacks.shutdownNow();
    }

    public void sendEvent(String payload) {
        if (!trySendEvent(payload)) Log.w(TAG, "CameraMind event was not queued: " + state);
    }

    public boolean trySendEvent(String payload) {
        try {
            Protocol.parse(payload);
            FrameCodec.encode(0, payload);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "CameraMind event rejected", e);
            return false;
        }
        synchronized (guard) {
            Session session = active;
            if (closed || session == null) return false;
            return writes.offer(new Pending(session, payload));
        }
    }

    public void sendMessage(int what, String data) { sendEvent(new Protocol().setWhat(what).setStr(data).buildMessage()); }
    public void sendMessage(int what, Map<String, ?> data) { sendEvent(new Protocol().setWhat(what).setValue(data).buildMessage()); }
    public void sendMessage(int what, JSONObject data) { sendEvent(new Protocol().setWhat(what).setJson(data).buildMessage()); }

    public void sendForwardMessage(String from, String to, Map<String, ?> body) {
        Map<String, Object> data = new HashMap<>();
        data.put("from", from);
        data.put("to", to);
        data.put("body", new JSONObject(body));
        sendMessage(Protocol.FORWARD, data);
    }

    public void onReceivedMessage(String payload) {
        Session session = active;
        if (session != null) dispatch(session, payload);
    }

    private void dispatch(Session session, String payload) {
        final SocketListener target;
        final long epoch;
        synchronized (guard) { target = listener; epoch = listenerEpoch; }
        if (target == null) return;
        try {
            callbacks.execute(() -> {
                synchronized (guard) {
                    if (closed || active != session || listenerEpoch != epoch || listener != target) return;
                }
                try { target.onReceivedEvent(payload); }
                catch (RuntimeException e) { Log.e(TAG, "CameraMind listener failed", e); }
            });
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "CameraMind callback capacity exhausted; disconnecting this session");
            close(session.socket);
        }
    }

    private void connectionLoop() {
        int failures = 0;
        while (!closed) {
            LocalSocket socket = new LocalSocket();
            Session session = null;
            synchronized (guard) {
                if (closed) { close(socket); return; }
                state = State.CONNECTING;
                connecting = socket;
            }
            try {
                socket.connect(new LocalSocketAddress(FrameCodec.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(1000);
                PeerIdentity.verifyServer(context, socket);
                String name = PeerIdentity.readProcessName(Process.myPid());
                Map<String, String> identity = new HashMap<>();
                identity.put("my_name", name);
                FrameCodec.write(socket.getOutputStream(), Protocol.nextSessionId(),
                        new Protocol().setWhat(Protocol.IDENTIFY).setValue(identity).buildMessage());
                synchronized (guard) {
                    if (closed || connecting != socket) throw new IOException("CameraMind connection cancelled");
                    session = new Session(++generation, socket);
                    active = session;
                    connecting = null;
                    state = State.CONNECTED;
                }
                failures = 0;
                while (!closed && active == session) {
                    if (!FrameCodec.awaitReadable(socket, 1000)) continue;
                    FrameCodec.Frame frame = FrameCodec.read(socket);
                    Protocol.parse(frame.payload);
                    dispatch(session, frame.payload);
                }
            } catch (IOException | JSONException | RuntimeException e) {
                if (!closed) Log.w(TAG, "CameraMind transport unavailable", e);
            } finally {
                synchronized (guard) {
                    if (active == session) active = null;
                    if (connecting == socket) connecting = null;
                    writes.clear();
                    callbacks.getQueue().clear();
                    if (!closed) state = State.BACKOFF;
                }
                close(socket);
            }
            synchronized (guard) {
                if (closed) return;
                failures = Math.min(failures + 1, 100);
                try { guard.wait(failures * 200L); }
                catch (InterruptedException e) { if (closed) return; }
            }
        }
    }

    private void writerLoop() {
        while (!closed) {
            try {
                Pending item = writes.take();
                if (closed || active != item.session) continue;
                try {
                    FrameCodec.write(item.session.socket.getOutputStream(), Protocol.nextSessionId(), item.text);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "CameraMind write failed; event will not be replayed", e);
                    close(item.session.socket);
                }
            } catch (InterruptedException e) { if (closed) return; }
        }
    }

    private static void close(LocalSocket socket) {
        if (socket == null) return;
        try { socket.shutdownInput(); } catch (IOException ignored) { }
        try { socket.shutdownOutput(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
