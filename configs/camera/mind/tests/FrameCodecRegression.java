package org.lineageos.warsaw.cameramind.tests;

import com.miui.cameraopt.intentaware.FrameCodec;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FrameCodecRegression {
    private static final long LATE_READ_MILLIS = 2150L;
    private static int executed;

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void runCase(String name, CheckedRunnable action) throws Exception {
        action.run();
        executed++;
        System.out.println("PASS " + name);
    }

    private static <T extends Exception> T expect(Class<T> type, CheckedRunnable action)
            throws Exception {
        try {
            action.run();
        } catch (Exception error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName() + ", but operation succeeded");
    }

    private static void expectDeadline(InputStream input) throws Exception {
        IOException error = expect(IOException.class, () -> FrameCodec.read(input));
        check(!(error instanceof SocketTimeoutException), "Late frame cannot look idle");
        check("CameraMind frame deadline".equals(error.getMessage()),
                "Complete delayed read must fail specifically at the frame deadline: " + error);
    }

    private static byte[] frame(int session, int declaredSize, byte[] body) {
        return ByteBuffer.allocate(FrameCodec.HEADER_BYTES + body.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(0x40000000).putInt(session).putInt(declaredSize).put(body).array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static String repeated(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void assertFrame(FrameCodec.Frame actual, int session, String payload) {
        check(actual.sessionId == session, "Wrong session identifier");
        check(payload.equals(actual.payload), "Payload mismatch");
    }

    private static final class FragmentedInput extends InputStream {
        private final ByteArrayInputStream delegate;
        private final int chunk;

        FragmentedInput(byte[] data, int chunk) {
            if (chunk <= 0) throw new IllegalArgumentException("chunk");
            delegate = new ByteArrayInputStream(data);
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, Math.min(length, chunk));
        }
    }

    private static final class DelayedFinalRead extends InputStream {
        private final ByteArrayInputStream delegate;
        private final int delayedPosition;
        private int consumed;
        private boolean delayed;

        DelayedFinalRead(byte[] data, int delayedPosition) {
            delegate = new ByteArrayInputStream(data);
            this.delayedPosition = delayedPosition;
        }

        @Override
        public int read() {
            int result = delegate.read();
            if (result >= 0) consumed++;
            return result;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (!delayed && consumed == delayedPosition && length != 0) {
                delayed = true;
                try {
                    Thread.sleep(LATE_READ_MILLIS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException("Harness interrupted");
                    failure.initCause(error);
                    throw failure;
                }
            }
            int result = delegate.read(bytes, offset, length);
            if (result > 0) consumed += result;
            return result;
        }
    }

    private static void finalHeaderReadMustExpire() throws Exception {
        byte[] wire = FrameCodec.encode(7, "");
        DelayedFinalRead input = new DelayedFinalRead(wire, 1);
        expectDeadline(input);
        check(input.delayed && input.consumed == wire.length,
                "Header test did not complete its delayed final read");
    }

    private static void finalPayloadReadMustExpire() throws Exception {
        byte[] wire = FrameCodec.encode(8, "payload");
        DelayedFinalRead input = new DelayedFinalRead(wire, FrameCodec.HEADER_BYTES);
        expectDeadline(input);
        check(input.delayed && input.consumed == wire.length,
                "Payload test did not complete its delayed final read");
    }

    private static void fragmentedFrames() throws Exception {
        String text = "A\u0000\u00e9\u4e2d\ud83d\ude00";
        byte[] wire = FrameCodec.encode(Integer.MIN_VALUE, text);
        for (int chunk : new int[] {1, 2, 7, 11, 64}) {
            FragmentedInput input = new FragmentedInput(wire, chunk);
            assertFrame(FrameCodec.read(input), Integer.MIN_VALUE, text);
            expect(EOFException.class, () -> FrameCodec.read(input));
        }
    }

    private static void coalescedFrames() throws Exception {
        byte[] all = concat(FrameCodec.encode(0, ""),
                concat(FrameCodec.encode(Integer.MAX_VALUE, "first"),
                       FrameCodec.encode(-17, "\u4e8c")));
        ByteArrayInputStream input = new ByteArrayInputStream(all);
        assertFrame(FrameCodec.read(input), 0, "");
        assertFrame(FrameCodec.read(input), Integer.MAX_VALUE, "first");
        assertFrame(FrameCodec.read(input), -17, "\u4e8c");
        check(input.available() == 0, "Frame parser consumed the wrong boundary");
        expect(EOFException.class, () -> FrameCodec.read(input));
    }

    private static void idleTimeoutIsDistinct() throws Exception {
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("test idle");
            }
        };
        expect(SocketTimeoutException.class, () -> FrameCodec.read(input));
    }

    private static void partialTimeoutIsFatal() throws Exception {
        InputStream input = new InputStream() {
            @Override
            public int read() {
                return 64;
            }

            @Override
            public int read(byte[] data, int offset, int length) throws IOException {
                throw new SocketTimeoutException("test partial");
            }
        };
        IOException error = expect(IOException.class, () -> FrameCodec.read(input));
        check(!(error instanceof SocketTimeoutException), "Partial frame cannot look idle");
        check(error.getCause() instanceof SocketTimeoutException, "Timeout cause was lost");
    }

    private static void truncatedFrames() throws Exception {
        byte[] full = FrameCodec.encode(23, "three");
        for (int length : new int[] {0, 1, 11, 12, full.length - 1}) {
            byte[] cut = Arrays.copyOf(full, length);
            expect(EOFException.class, () -> FrameCodec.read(new ByteArrayInputStream(cut)));
        }
    }

    private static void noProgressIsFatal() throws Exception {
        InputStream input = new InputStream() {
            @Override
            public int read() {
                return 64;
            }

            @Override
            public int read(byte[] data, int offset, int length) {
                return 0;
            }
        };
        IOException error = expect(IOException.class, () -> FrameCodec.read(input));
        check("CameraMind stream made no progress".equals(error.getMessage()), "Wrong zero-read error");
    }

    private static void invalidHeaders() throws Exception {
        byte[] valid = FrameCodec.encode(2, "");
        for (int position = 0; position < 4; position++) {
            byte[] changed = valid.clone();
            changed[position] ^= 1;
            expect(ProtocolException.class,
                    () -> FrameCodec.read(new ByteArrayInputStream(changed)));
        }
        for (int size : new int[] {-1, FrameCodec.MAX_BYTES + 1, Integer.MAX_VALUE}) {
            byte[] oversized = frame(0, size, new byte[0]);
            expect(ProtocolException.class,
                    () -> FrameCodec.read(new ByteArrayInputStream(oversized)));
        }
    }

    private static void invalidUtf8() throws Exception {
        byte[][] invalid = {
                {(byte) 0x80},
                {(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xe2, (byte) 0x82},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
        };
        for (byte[] body : invalid) {
            byte[] wire = frame(0, body.length, body);
            expect(ProtocolException.class, () -> FrameCodec.read(new ByteArrayInputStream(wire)));
        }
    }

    private static void unicodeEncoding() throws Exception {
        expect(ProtocolException.class, () -> FrameCodec.encode(0, null));
        for (String invalid : new String[] {"\ud800", "\udfff", "x\ud800y", "\ud800\ud800"}) {
            expect(ProtocolException.class, () -> FrameCodec.encode(0, invalid));
        }
        String text = "\u0000\u00e9\u4e2d\ud83d\ude00";
        byte[] wire = FrameCodec.encode(42, text);
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        check(wire.length == FrameCodec.HEADER_BYTES + bytes, "UTF-8 length mismatch");
        check(ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN).getInt(8) == bytes,
                "Header uses character count instead of UTF-8 byte count");
        assertFrame(FrameCodec.read(new ByteArrayInputStream(wire)), 42, text);
    }

    private static void allocationLimits() throws Exception {
        String asciiLimit = repeated('x', FrameCodec.MAX_BYTES);
        byte[] wire = FrameCodec.encode(1, asciiLimit);
        check(wire.length == FrameCodec.HEADER_BYTES + FrameCodec.MAX_BYTES, "Exact limit rejected");
        assertFrame(FrameCodec.read(new ByteArrayInputStream(wire)), 1, asciiLimit);
        String tooManyUnits = repeated('x', FrameCodec.MAX_BYTES + 1);
        expect(ProtocolException.class, () -> FrameCodec.encode(0, tooManyUnits));
        String tooManyUtf8Bytes = repeated('\u4e2d', FrameCodec.MAX_BYTES / 3 + 1);
        check(tooManyUtf8Bytes.length() < FrameCodec.MAX_BYTES, "Bad multibyte fixture");
        expect(ProtocolException.class, () -> FrameCodec.encode(0, tooManyUtf8Bytes));
        String twoByteLimit = repeated('\u00e9', FrameCodec.MAX_BYTES / 2);
        check(FrameCodec.encode(0, twoByteLimit).length == FrameCodec.HEADER_BYTES + FrameCodec.MAX_BYTES,
                "Exact multibyte byte limit rejected");
    }

    public static void main(String[] args) throws Exception {
        runCase("final header read deadline", FrameCodecRegression::finalHeaderReadMustExpire);
        runCase("final payload read deadline", FrameCodecRegression::finalPayloadReadMustExpire);
        runCase("fragmented frames", FrameCodecRegression::fragmentedFrames);
        runCase("coalesced frames", FrameCodecRegression::coalescedFrames);
        runCase("idle timeout", FrameCodecRegression::idleTimeoutIsDistinct);
        runCase("partial timeout is fatal", FrameCodecRegression::partialTimeoutIsFatal);
        runCase("truncation", FrameCodecRegression::truncatedFrames);
        runCase("zero progress", FrameCodecRegression::noProgressIsFatal);
        runCase("headers and declared sizes", FrameCodecRegression::invalidHeaders);
        runCase("malformed UTF-8", FrameCodecRegression::invalidUtf8);
        runCase("Unicode encoding", FrameCodecRegression::unicodeEncoding);
        runCase("allocation and byte limits", FrameCodecRegression::allocationLimits);
        System.out.println("FrameCodecRegression completed " + executed + " cases");
    }

    private FrameCodecRegression() {}
}
