package com.miui.cameraopt.intentaware;

import android.net.LocalSocket;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class FrameCodec {
    public static final String SOCKET_NAME = "intentaware_socket";
    public static final int HEADER_BYTES = 12;
    public static final int STOCK_MAX_BYTES = 1 << 30;
    public static final int MAX_BYTES = 1 << 20;
    private static final long FRAME_NANOS = 2_000_000_000L;

    public static final class Frame {
        public final int sessionId;
        public final String payload;
        private Frame(int sessionId, String payload) {
            this.sessionId = sessionId;
            this.payload = payload;
        }
    }

    public static boolean awaitReadable(LocalSocket socket, int waitMillis) throws IOException {
        StructPollfd poll = new StructPollfd();
        poll.fd = socket.getFileDescriptor();
        poll.events = (short) OsConstants.POLLIN;
        try {
            if (Os.poll(new StructPollfd[] {poll}, waitMillis) == 0) return false;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EINTR) return false;
            throw new IOException("CameraMind poll failed", e);
        }
        if ((poll.revents & OsConstants.POLLIN) != 0) return true;
        throw new EOFException("CameraMind socket poll closed/error");
    }

    public static Frame read(LocalSocket socket) throws IOException {
        InputStream input = socket.getInputStream();
        return read(new InputStream() {
            public int read() throws IOException {
                int value = input.read();
                rejectRights(socket);
                return value;
            }
            public int read(byte[] bytes, int offset, int count) throws IOException {
                int value = input.read(bytes, offset, count);
                rejectRights(socket);
                return value;
            }
        });
    }

    private static void rejectRights(LocalSocket socket) throws IOException {
        FileDescriptor[] descriptors = socket.getAncillaryFileDescriptors();
        if (descriptors == null || descriptors.length == 0) return;
        for (FileDescriptor fd : descriptors) {
            try { Os.close(fd); } catch (ErrnoException ignored) { }
        }
        throw new ProtocolException("CameraMind text frame carried file descriptors");
    }

    public static Frame read(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) throw new EOFException("CameraMind peer closed");
        long started = System.nanoTime();
        byte[] header = new byte[HEADER_BYTES];
        header[0] = (byte) first;
        try {
            readFully(input, header, 1, HEADER_BYTES - 1, started);
            if (header[0] != 64 || header[1] != 0 || header[2] != 0 || header[3] != 0) {
                throw new ProtocolException("Unsupported CameraMind frame header");
            }
            ByteBuffer words = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            int session = words.getInt(4);
            int size = words.getInt(8);
            if (size < 0 || size > MAX_BYTES) throw new ProtocolException("CameraMind frame size");
            byte[] data = new byte[size];
            readFully(input, data, 0, size, started);
            String payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            return new Frame(session, payload);
        } catch (SocketTimeoutException e) {
            throw new IOException("CameraMind partial frame timed out", e);
        } catch (CharacterCodingException e) {
            throw new ProtocolException("CameraMind payload is not UTF-8");
        }
    }

    private static void readFully(InputStream input, byte[] data, int offset, int size,
            long started) throws IOException {
        int done = 0;
        while (done < size) {
            checkFrameTime(started);
            int n = input.read(data, offset + done, size - done);
            checkFrameTime(started);
            if (n < 0) throw new EOFException("Truncated CameraMind frame");
            if (n == 0) throw new IOException("CameraMind stream made no progress");
            done += n;
        }
    }

    private static void checkFrameTime(long started) throws IOException {
        if (System.nanoTime() - started >= FRAME_NANOS) {
            throw new IOException("CameraMind frame deadline");
        }
    }

    public static byte[] encode(int sessionId, String payload) throws IOException {
        if (payload == null) throw new ProtocolException("Null CameraMind payload");
        if (payload.length() > MAX_BYTES) throw new ProtocolException("CameraMind frame size");
        final ByteBuffer data;
        try {
            data = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(payload));
        } catch (CharacterCodingException e) {
            throw new ProtocolException("CameraMind payload has invalid Unicode");
        }
        int size = data.remaining();
        if (size > MAX_BYTES) throw new ProtocolException("CameraMind frame size");
        return ByteBuffer.allocate(HEADER_BYTES + size).order(ByteOrder.BIG_ENDIAN)
                .put(new byte[] {64, 0, 0, 0}).putInt(sessionId).putInt(size).put(data).array();
    }

    public static void write(OutputStream output, int sessionId, String payload) throws IOException {
        output.write(encode(sessionId, payload));
        output.flush();
    }

    private FrameCodec() {}
}
