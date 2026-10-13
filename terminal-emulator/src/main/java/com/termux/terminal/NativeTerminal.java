package com.termux.terminal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Thread-confined libghostty-vt terminal state. */
public final class NativeTerminal implements AutoCloseable {

    private static final int ROW_MAGIC = 0x4d585231;
    private static final int MAX_ROW_BYTES = 16 * 1024 * 1024;

    static {
        System.loadLibrary("muxy_terminal");
    }

    public interface Listener {
        void onWrite(byte[] data);
        void onBell();
        void onTitle(byte[] title);
        void onClipboard(byte[] text);
        void onEvent(int kind, byte[] data);
    }

    public static final class Row {
        public final char[] text;
        /** Wide trailing cells share the leading cell's UTF-16 offset. */
        public final int[] offsets;
        public final long[] styles;
        /** Native narrow, wide, trailing-spacer, and wrap-spacer values: 0, 1, 2, 3. */
        public final byte[] occupancy;
        public final boolean wrapped;

        private Row(char[] text, int[] offsets, long[] styles, byte[] occupancy, boolean wrapped) {
            this.text = text;
            this.offsets = offsets;
            this.styles = styles;
            this.occupancy = occupancy;
            this.wrapped = wrapped;
        }
    }

    private final Thread owner = Thread.currentThread();
    private long handle;
    private boolean processing;
    private ByteBuffer rowBuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);

    public NativeTerminal(int columns, int rows, int historyRows, Listener listener) {
        if (historyRows < 0) throw new IllegalArgumentException("Negative history limit");
        handle = nativeCreate(columns, rows, historyRows, Objects.requireNonNull(listener));
        if (handle == 0) throw new IllegalStateException("Cannot create native terminal");
        try {
            nativeSetColors(handle, TerminalColors.COLOR_SCHEME.mDefaultColors);
        } catch (RuntimeException | Error error) {
            long old = handle;
            handle = 0;
            nativeDestroy(old);
            throw error;
        }
    }

    private long requireHandle() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Terminal accessed from another thread");
        if (handle == 0) throw new IllegalStateException("Terminal is closed");
        return handle;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Terminal closed from another thread");
        if (handle == 0) return;
        if (processing) throw new IllegalStateException("Cannot close terminal during a callback");
        long old = handle;
        handle = 0;
        nativeDestroy(old);
    }

    public void write(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes);
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("Invalid terminal input range");
        }
        long value = requireHandle();
        if (processing) throw new IllegalStateException("Recursive terminal output");
        processing = true;
        try {
            nativeWrite(value, bytes, offset, length);
        } finally {
            processing = false;
        }
    }

    public void resize(int columns, int rows, int cellWidth, int cellHeight) {
        long value = requireHandle();
        if (processing) throw new IllegalStateException("Cannot resize terminal during a callback");
        processing = true;
        try {
            nativeResize(value, columns, rows, cellWidth, cellHeight);
        } finally {
            processing = false;
        }
    }

    public void reset() {
        long value = requireHandle();
        if (processing) throw new IllegalStateException("Cannot reset terminal during a callback");
        processing = true;
        try {
            nativeReset(value);
        } finally {
            processing = false;
        }
    }

    public void clearHistory() {
        if (processing) throw new IllegalStateException("Cannot clear terminal history during a callback");
        nativeClearHistory(requireHandle());
    }

    public void setHistoryRows(int rows) {
        if (processing) throw new IllegalStateException("Cannot change terminal history during a callback");
        if (rows < 0) throw new IllegalArgumentException("Negative history limit");
        nativeSetHistoryRows(requireHandle(), rows);
    }

    public void setCell(int x, int y, int cp, long style) {
        nativeSetCell(requireHandle(), x, y, cp, style);
    }

    public void setCursor(int x, int y) { nativeSetCursor(requireHandle(), x, y); }
    public void advance(int count, int x) { nativeAdvance(requireHandle(), count, x); }

    public void scroll() {
        nativeScroll(requireHandle());
    }

    public long cursorStyle() { return nativeCursorStyle(requireHandle()); }

    /** Kitty keyboard flags in the low byte, modifyOtherKeys level 2 in bit 8. */
    public int keyboard() { return nativeKeyboard(requireHandle()); }

    public int[] state() {
        return nativeState(requireHandle());
    }

    public boolean mode(int value, boolean ansi) {
        if (value < 0 || value > 32767) throw new IllegalArgumentException("Invalid terminal mode");
        return nativeMode(requireHandle(), value, ansi);
    }

    public void setMode(int value, boolean ansi, boolean enabled) {
        if (processing) throw new IllegalStateException("Cannot change terminal mode during a callback");
        if (value < 0 || value > 32767) throw new IllegalArgumentException("Invalid terminal mode");
        nativeSetMode(requireHandle(), value, ansi, enabled);
    }

    public void setCursorStyle(int style) {
        if (processing) throw new IllegalStateException("Cannot change cursor style during a callback");
        if (style < 0 || style > 2) throw new IllegalArgumentException("Invalid cursor style");
        nativeSetCursorStyle(requireHandle(), style);
    }

    public void setColors(int[] colors) {
        if (processing) throw new IllegalStateException("Cannot change terminal palette during a callback");
        if (colors == null || colors.length != TextStyle.NUM_INDEXED_COLORS) {
            throw new IllegalArgumentException("Invalid terminal palette");
        }
        nativeSetColors(requireHandle(), colors);
    }

    public void readColors(int[] colors) {
        if (colors == null || colors.length != TextStyle.NUM_INDEXED_COLORS) {
            throw new IllegalArgumentException("Invalid terminal palette");
        }
        nativeReadColors(requireHandle(), colors);
    }

    public String title() {
        byte[] value = nativeTitle(requireHandle());
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    public void setTopRow(int row) {
        if (processing) throw new IllegalStateException("Cannot scroll terminal during a callback");
        if (row > 0) throw new IllegalArgumentException("Invalid viewport row");
        nativeSetTopRow(requireHandle(), row);
    }

    public long compressionActivity() {
        return nativeCompressionActivity(requireHandle());
    }

    /** Perform one bounded idle step; true means more work remains. */
    public boolean compressHistory() {
        if (processing) throw new IllegalStateException("Cannot compress terminal history during a callback");
        return nativeCompressHistory(requireHandle());
    }

    public byte[] encodeKey(int key, int modifiers, String text, int action, boolean composing, int unshifted) {
        if (key < 0 || action < 0 || action > 2 || modifiers < 0 || modifiers > 1023
                || unshifted < 0 || unshifted > Character.MAX_CODE_POINT
                || unshifted >= 0xd800 && unshifted <= 0xdfff) {
            throw new IllegalArgumentException("Invalid terminal key event");
        }
        return nativeEncodeKey(requireHandle(), key, modifiers,
                text == null ? null : sanitizeText(text).getBytes(StandardCharsets.UTF_8), action, composing, unshifted);
    }

    public byte[] encodePaste(String text) {
        return nativeEncodePaste(requireHandle(), sanitizeText(text).getBytes(StandardCharsets.UTF_8));
    }

    public Row readRow(int externalRow) {
        long nativeHandle = requireHandle();
        int length = nativeReadRow(nativeHandle, externalRow, rowBuffer);
        if (length < 0) {
            if (length == Integer.MIN_VALUE || -length > MAX_ROW_BYTES) {
                throw new IllegalStateException("Native terminal row is too large");
            }
            rowBuffer = ByteBuffer.allocateDirect(-length).order(ByteOrder.LITTLE_ENDIAN);
            length = nativeReadRow(nativeHandle, externalRow, rowBuffer);
        }
        if (length < 12 || length > rowBuffer.capacity()) throw new IllegalStateException("Invalid native terminal row");
        ByteBuffer data = rowBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        data.position(0);
        data.limit(length);
        if (data.getInt() != ROW_MAGIC) throw new IllegalStateException("Invalid native row version");
        int columns = data.getInt();
        int wrap = data.getInt();
        if (columns <= 0 || columns > 65535 || (wrap != 0 && wrap != 1)
                || columns > data.remaining() / 16) throw new IllegalStateException("Invalid native row dimensions");
        long[] styles = new long[columns];
        byte[] occupancy = new byte[columns];
        int[] offsets = new int[columns + 1];
        StringBuilder text = new StringBuilder(columns);
        for (int x = 0; x < columns; x++) {
            if (data.remaining() < 16) throw new IllegalStateException("Truncated native row cell");
            styles[x] = data.getLong();
            int wide = data.getInt();
            int codepoints = data.getInt();
            if (wide < 0 || wide > 3 || codepoints < 0 || codepoints > data.remaining() / 4) {
                throw new IllegalStateException("Invalid native row cell");
            }
            if (wide == 2 && (x == 0 || occupancy[x - 1] != 1)
                    || x > 0 && occupancy[x - 1] == 1 && wide != 2
                    || wide == 1 && x == columns - 1
                    || wide == 3 && (x != columns - 1 || wrap == 0)) {
                throw new IllegalStateException("Invalid native wide-cell boundary");
            }
            occupancy[x] = (byte) wide;
            offsets[x] = wide == 2 ? offsets[x - 1] : text.length();
            for (int i = 0; i < codepoints; i++) {
                int cp = data.getInt();
                if (!Character.isValidCodePoint(cp) || cp >= 0xd800 && cp <= 0xdfff) {
                    throw new IllegalStateException("Invalid native row codepoint");
                }
                if (wide != 2) text.appendCodePoint(cp == 0 ? ' ' : cp);
            }
            if (codepoints == 0 && wide != 2) text.append(' ');
        }
        offsets[columns] = text.length();
        if (data.hasRemaining()) throw new IllegalStateException("Unexpected native row data");
        char[] chars = new char[text.length()];
        text.getChars(0, text.length(), chars, 0);
        return new Row(chars, offsets, styles, occupancy, wrap == 1);
    }

    public static int[] clusterLayout(String text, boolean graphemeMode) {
        String valid = sanitizeText(text);
        int[] cps = new int[valid.codePointCount(0, valid.length())];
        for (int i = 0, offset = 0; i < cps.length; i++) {
            cps[i] = valid.codePointAt(offset);
            offset += Character.charCount(cps[i]);
        }
        return nativeClusterLayout(cps, graphemeMode);
    }

    public static String sanitizeText(CharSequence input) {
        return TerminalText.sanitizeUtf16(input);
    }

    private static native long nativeCreate(int columns, int rows, int history, Listener listener);
    private static native void nativeDestroy(long handle);
    private static native void nativeSetCell(long handle, int x, int y, int cp, long style);
    private static native void nativeScroll(long handle);
    private static native void nativeSetCursor(long handle, int x, int y);
    private static native void nativeAdvance(long handle, int count, int x);
    private static native void nativeWrite(long handle, byte[] bytes, int offset, int length);
    private static native void nativeResize(long handle, int columns, int rows, int width, int height);
    private static native void nativeReset(long handle);
    private static native void nativeClearHistory(long handle);
    private static native void nativeSetHistoryRows(long handle, int rows);
    private static native int[] nativeState(long handle);
    private static native long nativeCursorStyle(long handle);
    private static native int nativeKeyboard(long handle);
    private static native boolean nativeMode(long handle, int mode, boolean ansi);
    private static native void nativeSetMode(long handle, int mode, boolean ansi, boolean enabled);
    private static native void nativeSetCursorStyle(long handle, int style);
    private static native void nativeSetColors(long handle, int[] colors);
    private static native void nativeReadColors(long handle, int[] colors);
    private static native byte[] nativeTitle(long handle);
    private static native void nativeSetTopRow(long handle, int row);
    private static native long nativeCompressionActivity(long handle);
    private static native boolean nativeCompressHistory(long handle);
    private static native int nativeReadRow(long handle, int row, ByteBuffer output);
    private static native int[] nativeClusterLayout(int[] codepoints, boolean graphemeMode);
    private static native byte[] nativeEncodeKey(long handle, int key, int modifiers, byte[] text,
                                                int action, boolean composing, int unshifted);
    private static native byte[] nativeEncodePaste(long handle, byte[] text);
}
