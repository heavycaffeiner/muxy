package com.termux.terminal;

import android.os.Handler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public final class TerminalEmulator implements AutoCloseable {
    public static final int MOUSE_LEFT_BUTTON = 0;
    public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
    public static final int MOUSE_WHEELUP_BUTTON = 64;
    public static final int MOUSE_WHEELDOWN_BUTTON = 65;
    public static final int UNICODE_REPLACEMENT_CHAR = 0xfffd;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    public static final int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;
    public static final int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    public static final int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    public static final int TERMINAL_CURSOR_STYLE_BAR = 2;
    public static final int DEFAULT_TERMINAL_CURSOR_STYLE = 0;
    public static final Integer[] TERMINAL_CURSOR_STYLES_LIST = {0, 1, 2};

    public int mRows, mColumns;
    int mForeColor, mBackColor, mEffect;
    public final TerminalColors mColors = new TerminalColors();
    final TerminalBuffer mAltBuffer;
    private final TerminalBuffer mMainBuffer;
    private TerminalBuffer mScreen;
    private final TerminalOutput mSession;
    private final NativeTerminal mNative;
    private final TerminalImageDecoder mImages;
    private final int mTranscriptRows;
    private TerminalSessionClient mClient;
    private int mCellWidthPixels, mCellHeightPixels;
    private int[] mState;
    private int mScrollCounter;
    private boolean mAutoScrollDisabled, mCursorBlinkingEnabled, mCursorBlinkState = true, mClosed;
    private String mTitle;
    private final Handler mIdleHandler = new Handler();
    private final Runnable mCompress = new Runnable() {
        @Override public void run() {
            if (!mClosed && mNative.compressHistory()) mIdleHandler.postDelayed(this, 16);
        }
    };

    public TerminalEmulator(TerminalOutput session, int columns, int rows, int cellWidthPixels,
                            int cellHeightPixels, Integer transcriptRows, TerminalSessionClient client) {
        mSession = Objects.requireNonNull(session);
        mClient = client;
        mTranscriptRows = transcriptRows == null || transcriptRows < 100 || transcriptRows > 50000
                ? 2000 : transcriptRows;
        mNative = new NativeTerminal(columns, rows, Math.max(0, mTranscriptRows - rows), new NativeTerminal.Listener() {
            @Override public void onWrite(byte[] data) { mSession.write(data, 0, data.length); }
            @Override public void onBell() { mSession.onBell(); }
            @Override public void onTitle(byte[] title) { updateTitle(new String(title, StandardCharsets.UTF_8)); }
            @Override public void onClipboard(byte[] text) { mSession.onCopyTextToClipboard(new String(text, StandardCharsets.UTF_8)); }
            @Override public void onEvent(int kind, byte[] data) {
                sync();
                mMainBuffer.invalidate(); mAltBuffer.invalidate();
                if (kind == 6) updateTitle(new String(data, StandardCharsets.UTF_8));
                else if (kind == 8) mSession.write(data, 0, data.length);
                else if (kind == 9) {
                    mImages.clear(); mMainBuffer.clearTerminalBitmaps(); mAltBuffer.clearTerminalBitmaps();
                } else if (kind == 10) {
                    // Native reset already restored colors and the cursor style.
                    mImages.clear();
                } else if (kind == 11) {
                    Logger.logWarn(mClient, LOG_TAG, "Ignored a terminal sequence that could not be processed");
                } else mImages.event(kind, data);
            }
        });
        mMainBuffer = new TerminalBuffer(client, mNative, columns, rows, mTranscriptRows, false);
        mAltBuffer = new TerminalBuffer(client, mNative, columns, rows, rows, true);
        mImages = new TerminalImageDecoder(this);
        try {
            mNative.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mCellWidthPixels = cellWidthPixels;
            mCellHeightPixels = cellHeightPixels;
            sync();
            setCursorStyle();
        } catch (RuntimeException | Error error) {
            mNative.close();
            throw error;
        }
    }

    private static final String LOG_TAG = "TerminalEmulator";

    private void requireOpen() {
        if (mClosed) throw new IllegalStateException("Terminal is closed");
    }

    private void sync() {
        int[] state = mNative.state();
        if (state == null || state.length != 14) throw new IllegalStateException("Invalid terminal state");
        mState = state;
        long style = mNative.cursorStyle();
        mForeColor = TextStyle.decodeForeColor(style);
        mBackColor = TextStyle.decodeBackColor(style);
        mEffect = TextStyle.decodeEffect(style);
        mColumns = state[0]; mRows = state[1];
        mMainBuffer.sync(mColumns, mRows, Math.max(mTranscriptRows, mRows));
        mAltBuffer.sync(mColumns, mRows, mRows);
        mScreen = state[6] == 0 ? mMainBuffer : mAltBuffer;
        if (state[6] == 0) mMainBuffer.setHistorySize(state[7]);
        int[] before = mColors.mCurrentColors.clone();
        mNative.readColors(mColors.mCurrentColors);
        if (!Arrays.equals(before, mColors.mCurrentColors)) mSession.onColorsChanged();
    }

    public void append(byte[] buffer, int length) {
        requireOpen();
        mNative.write(buffer, 0, length);
        sync();
        mScrollCounter = (int)Math.min(Integer.MAX_VALUE, (long)mScrollCounter + mState[12]);
        mMainBuffer.invalidate(); mAltBuffer.invalidate();
        mIdleHandler.removeCallbacks(mCompress);
        mIdleHandler.postDelayed(mCompress, 1000);
    }

    public void processCodePoint(int cp) {
        if (mClosed) return;
        if (!Character.isValidCodePoint(cp) || cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
        byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
        append(bytes, bytes.length);
    }

    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mClosed) return;
        if (columns == mColumns && rows == mRows && cellWidthPixels == mCellWidthPixels && cellHeightPixels == mCellHeightPixels) return;
        mNative.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        mNative.setHistoryRows(Math.max(0, mTranscriptRows - rows));
        mCellWidthPixels = cellWidthPixels; mCellHeightPixels = cellHeightPixels;
        sync(); mMainBuffer.invalidate(); mAltBuffer.invalidate();
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client; mMainBuffer.updateClient(client); mAltBuffer.updateClient(client);
        if (mClosed) return;
        setCursorStyle(); setCursorBlinkState(true);
    }
    public TerminalBuffer getScreen() { requireOpen(); return mScreen; }
    public int getRows() { return mRows; }
    public int getColumns() { return mColumns; }
    public int getCellWidthPixels() { return mCellWidthPixels; }
    public int getCellHeightPixels() { return mCellHeightPixels; }
    public int getCursorRow() { return mState[3]; }
    public int getCursorCol() { return mState[2]; }
    public int getInputCursorCol() { return mState[13] != 0 ? 0 : mState[2]; }
    public int getInputCursorRow() { return mState[3] + (mState[13] != 0 ? 1 : 0); }
    public int getCursorStyle() { return mState[4]; }
    long currentTextStyle() { return mNative.cursorStyle(); }
    public boolean isAlternateBufferActive() { return mState[6] != 0; }
    public boolean isReverseVideo() { return mState[10] != 0; }
    public boolean isCursorEnabled() { return mState[5] != 0; }
    public boolean shouldCursorBeVisible() { return isCursorEnabled() && (!mCursorBlinkingEnabled || mCursorBlinkState); }
    public void setCursorBlinkingEnabled(boolean value) { mCursorBlinkingEnabled = value; }
    public void setCursorBlinkState(boolean value) { mCursorBlinkState = value; }
    public boolean isKeypadApplicationMode() { return !mClosed && mNative.mode(66, false); }
    public boolean isCursorKeysApplicationMode() { return !mClosed && mNative.mode(1, false); }
    public boolean isMouseTrackingActive() { return mState[9] != 0; }
    public boolean isGraphemeMode() { return !mClosed && mNative.mode(2027, false); }
    public int getScrollCounter() { return mScrollCounter; }
    public void clearScrollCounter() { mScrollCounter = 0; }
    public boolean isAutoScrollDisabled() { return mAutoScrollDisabled; }
    public void toggleAutoScrollDisabled() { mAutoScrollDisabled = !mAutoScrollDisabled; }
    public int getTopRow() { return mState[8]; }
    public void setTopRow(int row) {
        if (mClosed) return;
        mNative.setTopRow(row); mState = mNative.state();
    }
    public String getTitle() { return mTitle; }
    public String getSelectedText(int x1, int y1, int x2, int y2) { return getScreen().getSelectedText(x1, y1, x2, y2); }
    public void doDecSetOrReset(boolean enabled, int mode) {
        requireOpen();
        mNative.setMode(mode, false, enabled); sync();
    }
    public void clearDcsTypeVariables() { mImages.clear(); }
    public void clearOscTypeVariables() { mImages.clear(); }
    public void clearApcTypeVariables() {}

    private void updateTitle(String title) {
        String old = mTitle; mTitle = title;
        if (!Objects.equals(old, title)) mSession.titleChanged(old, title);
    }

    public void setCursorStyle() {
        if (mClosed) return;
        Integer style = mClient == null ? null : mClient.getTerminalCursorStyle();
        mNative.setCursorStyle(style == null || style < 0 || style > 2 ? 0 : style);
        mState = mNative.state();
    }

    public void resetColors() {
        if (mClosed) return;
        mColors.reset(); mNative.setColors(mColors.mCurrentColors);
        mSession.onColorsChanged();
    }

    /** Reset terminal state so user can interact with it regardless of present state. */
    public void reset() {
        if (mClosed) return;
        mNative.reset();
        sync(); mMainBuffer.invalidate(); mAltBuffer.invalidate();
    }
    public void paste(String text) {
        requireOpen();
        String clean = TerminalText.sanitizeUtf16(text).replaceAll("(\u001b|[\u0080-\u009f])", "").replace("\r\n", "\r").replace('\n', '\r');
        byte[] encoded = mNative.encodePaste(clean);
        mSession.write(encoded, 0, encoded.length);
    }

    /** Whether a program enabled an extended keyboard protocol (Kitty or modifyOtherKeys 2). */
    public boolean isExtendedKeyboard() { return !mClosed && mNative.keyboard() != 0; }

    /**
     * Encode a key with the terminal's active keyboard protocol. Modifiers use Ghostty's bits:
     * shift 1, ctrl 2, alt 4, super 8, caps lock 16, num lock 32.
     */
    public void key(int keyCode, int modifiers, String text, int action, boolean composing, int unshifted) {
        if (mClosed) return;
        if (action < 0 || action > 2 || modifiers < 0 || modifiers > 1023
                || !Character.isValidCodePoint(unshifted) || unshifted >= 0xd800 && unshifted <= 0xdfff) {
            throw new IllegalArgumentException("Invalid terminal key event");
        }
        if (!composing && !isExtendedKeyboard()) {
            if (action == 0) return;
            int legacyMods = ((modifiers & 1) != 0 ? KeyHandler.KEYMOD_SHIFT : 0)
                    | ((modifiers & 2) != 0 ? KeyHandler.KEYMOD_CTRL : 0)
                    | ((modifiers & 4) != 0 ? KeyHandler.KEYMOD_ALT : 0)
                    | ((modifiers & 32) != 0 ? KeyHandler.KEYMOD_NUM_LOCK : 0);
            String code = KeyHandler.getCode(keyCode, legacyMods, isCursorKeysApplicationMode(), isKeypadApplicationMode());
            if (code != null) {
                mSession.write(code);
                return;
            }
            String valid = TerminalText.sanitizeUtf16(text);
            if (valid.isEmpty()) return;
            StringBuilder encoded = new StringBuilder();
            if ((modifiers & 4) != 0) encoded.append('\u001b');
            for (int offset = 0; offset < valid.length();) {
                int cp = valid.codePointAt(offset);
                offset += Character.charCount(cp);
                if ((modifiers & 2) != 0) {
                    if (cp >= 'a' && cp <= 'z') cp = cp - 'a' + 1;
                    else if (cp >= 'A' && cp <= 'Z') cp = cp - 'A' + 1;
                    else switch (cp) {
                        case ' ': case '2': cp = 0; break;
                        case '[': case '3': cp = 27; break;
                        case '\\': case '4': cp = 28; break;
                        case ']': case '5': cp = 29; break;
                        case '^': case '6': cp = 30; break;
                        case '_': case '7': case '/': cp = 31; break;
                        case '8': cp = 127; break;
                        default: break;
                    }
                }
                encoded.appendCodePoint(cp);
            }
            mSession.write(encoded.toString());
            return;
        }
        byte[] data = mNative.encodeKey(NativeKeyMap.fromAndroid(keyCode), modifiers, text, action, composing, unshifted);
        if (data != null && data.length > 0) mSession.write(data, 0, data.length);
    }

    void imageCursor(int x, int y) { mNative.setCursor(x, y); sync(); }
    void imageAdvance(int count, int x) { mNative.advance(count, x); sync(); }
    void reportCellSize() { mSession.write(String.format(Locale.ROOT, "\033]1337;ReportCellSize=%d;%d\007", mCellHeightPixels, mCellWidthPixels)); }

    public void sendMouseEvent(int button, int column, int row, boolean pressed) {
        if (mClosed) return;
        column = Math.max(1, Math.min(mColumns, column)); row = Math.max(1, Math.min(mRows, row));
        if (button == 32 && !mNative.mode(1002, false) && !mNative.mode(1003, false)) return;
        if (mNative.mode(1006, false)) {
            mSession.write(String.format(Locale.ROOT, "\033[<%d;%d;%d%c", button, column, row, pressed ? 'M' : 'm'));
        } else if (column <= 223 && row <= 223) {
            byte[] data = {27, '[', 'M', (byte)(32 + (pressed ? button : 3)), (byte)(32 + column), (byte)(32 + row)};
            mSession.write(data, 0, data.length);
        }
    }

    @Override public void close() {
        if (mClosed) return;
        mIdleHandler.removeCallbacks(mCompress);
        mNative.close(); mClosed = true;
        mImages.clear(); mMainBuffer.clearTerminalBitmaps(); mAltBuffer.clearTerminalBitmaps();
    }
    public boolean isClosed() { return mClosed; }
    @Override public String toString() { return "TerminalEmulator[size=" + mColumns + "x" + mRows + "]"; }
}
