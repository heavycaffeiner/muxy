package com.termux.view;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import android.view.inputmethod.TextSnapshot;

import java.util.HashSet;
import java.util.Set;

public final class TerminalInputConnection extends BaseInputConnection {

    public interface Sink {
        void sendText(String text);
        boolean sendKey(KeyEvent event, boolean composing);
        void compositionChanged(CompositionState state);
        void cursorUpdates(boolean immediate, boolean monitor);
    }

    // Android may create a connection and discard it without replacing the served one.
    private static final class Session {
        final Sink sink;
        final TerminalImeController controller;
        int connections;
        int batches;
        boolean forwarding;
        boolean invalidated;

        Session(Sink sink) {
            this.sink = sink;
            controller = new TerminalImeController(new TerminalImeController.Sink() {
                @Override public void sendText(String text) {
                    boolean previous = forwarding;
                    forwarding = true;
                    try {
                        sink.sendText(text);
                    } finally {
                        forwarding = previous;
                    }
                }
                @Override public void sendKey(int code) {
                    forward(new KeyEvent(KeyEvent.ACTION_DOWN, code), false, false);
                }
                @Override public void compositionChanged(CompositionState state) { sink.compositionChanged(state); }
            });
        }

        boolean forward(KeyEvent event, boolean composing, boolean track) {
            controller.beginBatch();
            boolean previous = forwarding;
            forwarding = true;
            try {
                if (track && event.getAction() == KeyEvent.ACTION_DOWN && !composing) {
                    controller.terminalKeySent(event.isCtrlPressed() || event.isAltPressed()
                            ? KeyEvent.KEYCODE_UNKNOWN : event.getKeyCode());
                }
                return sink.sendKey(event, composing);
            } finally {
                forwarding = previous;
                controller.endBatch();
            }
        }
    }

    private final Session session;
    private final TerminalImeController controller;
    private final Set<Integer> localKeys = new HashSet<>();
    private boolean closed;
    private int batchDepth;

    public TerminalInputConnection(View view, Sink sink) {
        super(view, true);
        if (sink == null) throw new IllegalArgumentException("Missing terminal input sink");
        session = new Session(sink);
        controller = session.controller;
        session.connections++;
    }

    public TerminalInputConnection(View view, TerminalInputConnection previous) {
        super(view, true);
        session = previous.session;
        controller = session.controller;
        session.connections++;
    }

    public CompositionState composition() { return controller.composition(); }
    public CompositionState editorComposition() { return controller.editorComposition(); }
    public int documentOffset() { return controller.documentOffset(); }
    public int composingStart() { return controller.composingStart(); }
    public boolean isBatchEditing() { return session.batches > 0 || session.forwarding; }
    public boolean isClosed() { return closed || session.invalidated; }
    public boolean hasLiveSession() { return !session.invalidated && session.connections > 0; }

    @Override public boolean beginBatchEdit() {
        if (isClosed() || batchDepth == Integer.MAX_VALUE) return false;
        batchDepth++;
        session.batches++;
        controller.beginBatch();
        return true;
    }

    @Override public boolean endBatchEdit() {
        if (closed || batchDepth == 0) return false;
        batchDepth--;
        session.batches--;
        controller.endBatch();
        return batchDepth > 0;
    }

    public void abandonComposition() { controller.abandonComposition(); }

    public void closeSession() {
        closeConnection();
        session.invalidated = true;
        controller.close();
    }

    public boolean terminalKey(int code) {
        if (!hasLiveSession() || session.forwarding || KeyEvent.isModifierKey(code)) return false;
        if (!controller.isComposing()) {
            controller.terminalKeySent(code);
            return false;
        }
        switch (controller.keyDown(code)) {
            case COMMIT_THEN_FORWARD:
                controller.beginBatch();
                try {
                    controller.flushComposition();
                    controller.terminalKeySent(code);
                } finally {
                    controller.endBatch();
                }
                return false;
            case BACKSPACE_COMPOSITION: return controller.backspace();
            default:
                controller.abandonComposition();
                controller.terminalKeySent(code);
                return false;
        }
    }

    @Override public boolean setComposingText(CharSequence text, int cursorPosition) {
        if (isClosed()) return false;
        controller.composingText(text, cursorPosition);
        return true;
    }

    @Override public boolean setComposingRegion(int start, int end) {
        return !isClosed() && start >= 0 && end >= 0 && controller.composingRegion(start, end);
    }

    @Override public boolean finishComposingText() {
        if (isClosed()) return false;
        controller.finishComposing();
        return true;
    }

    @Override public boolean commitText(CharSequence text, int cursorPosition) {
        if (isClosed()) return false;
        controller.beginBatch();
        try {
            controller.commitText(text);
        } finally {
            controller.endBatch();
        }
        return true;
    }

    @Override public boolean setSelection(int start, int end) {
        if (isClosed() || start < 0 || end < 0) return false;
        controller.editorSelection(start, end);
        return true;
    }

    private boolean delete(int before, int after, boolean codepoints) {
        if (isClosed() || before < 0 || after < 0) return false;
        controller.beginBatch();
        try {
            if (controller.deleteSurrounding(before, after, codepoints)) return true;
            for (int i = 0, count = controller.terminalDeletes(before); i < count; i++) {
                session.forward(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false, true);
            }
            for (int i = 0, count = controller.terminalDeletes(after); i < count; i++) {
                session.forward(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL), false, true);
            }
            return true;
        } finally {
            controller.endBatch();
        }
    }

    @Override public boolean deleteSurroundingText(int before, int after) { return delete(before, after, false); }
    @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) { return delete(before, after, true); }

    @Override public CharSequence getTextBeforeCursor(int length, int flags) {
        if (isClosed()) return "";
        CompositionState state = controller.editorComposition();
        int caret = Math.min(state.selectionStart, state.selectionEnd);
        int start = (int) Math.max(0, (long) caret - Math.max(0, length));
        if (start > 0 && start < state.text.length() && Character.isLowSurrogate(state.text.charAt(start))) start++;
        return state.text.substring(start, caret);
    }

    @Override public CharSequence getTextAfterCursor(int length, int flags) {
        if (isClosed()) return "";
        CompositionState state = controller.editorComposition();
        int caret = Math.max(state.selectionStart, state.selectionEnd);
        int end = (int) Math.min(state.text.length(), (long) caret + Math.max(0, length));
        if (end > 0 && end < state.text.length() && Character.isLowSurrogate(state.text.charAt(end))) end--;
        return state.text.substring(caret, end);
    }

    @Override public CharSequence getSelectedText(int flags) {
        if (isClosed()) return null;
        CompositionState state = controller.editorComposition();
        int start = Math.min(state.selectionStart, state.selectionEnd);
        int end = Math.max(state.selectionStart, state.selectionEnd);
        return start == end ? null : state.text.substring(start, end);
    }

    @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        CompositionState state = controller.editorComposition();
        ExtractedText result = new ExtractedText();
        result.text = isClosed() ? "" : state.text;
        result.startOffset = controller.documentOffset();
        result.partialStartOffset = -1;
        result.partialEndOffset = -1;
        result.selectionStart = isClosed() ? 0 : state.selectionStart;
        result.selectionEnd = isClosed() ? 0 : state.selectionEnd;
        result.flags = ExtractedText.FLAG_SINGLE_LINE;
        return result;
    }

    @TargetApi(31)
    @Override public SurroundingText getSurroundingText(int before, int after, int flags) {
        if (isClosed() || before < 0 || after < 0) return null;
        CompositionState state = controller.editorComposition();
        int left = Math.min(state.selectionStart, state.selectionEnd);
        int right = Math.max(state.selectionStart, state.selectionEnd);
        int from = (int) Math.max(0, (long) left - before);
        int to = (int) Math.min(state.text.length(), (long) right + after);
        if (from > 0 && from < state.text.length() && Character.isLowSurrogate(state.text.charAt(from))) from++;
        if (to < state.text.length() && to > 0 && Character.isLowSurrogate(state.text.charAt(to))) to--;
        return new SurroundingText(state.text.substring(from, to), state.selectionStart - from,
                state.selectionEnd - from, controller.documentOffset() + from);
    }

    @Override public int getCursorCapsMode(int requested) { return 0; }

    @TargetApi(33)
    @Override public TextSnapshot takeSnapshot() {
        if (isClosed()) return null;
        CompositionState state = controller.editorComposition();
        int start = controller.composingStart();
        int end = start < 0 ? -1 : controller.documentOffset() + state.text.length();
        return new TextSnapshot(getSurroundingText(Integer.MAX_VALUE, Integer.MAX_VALUE,
                InputConnection.GET_TEXT_WITH_STYLES), start, end, 0);
    }

    @TargetApi(34)
    @Override public boolean replaceText(int start, int end, CharSequence text, int cursorPosition, TextAttribute attribute) {
        return !isClosed() && controller.replaceText(start, end, text, cursorPosition);
    }

    @TargetApi(33)
    @Override public boolean requestCursorUpdates(int mode, int filter) {
        return filter == 0 && requestCursorUpdates(mode);
    }

    @Override public boolean requestCursorUpdates(int mode) {
        int accepted = InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR;
        if (isClosed() || (mode & ~accepted) != 0) return false;
        session.sink.cursorUpdates((mode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0,
                (mode & InputConnection.CURSOR_UPDATE_MONITOR) != 0);
        return true;
    }

    @Override public boolean performEditorAction(int action) {
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
                && action != EditorInfo.IME_ACTION_DONE) return false;
        return sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    }

    @Override public boolean sendKeyEvent(KeyEvent event) {
        if (isClosed() || event == null) return false;
        if (KeyEvent.isModifierKey(event.getKeyCode())) return true;
        if (event.getAction() == KeyEvent.ACTION_UP && localKeys.remove(event.getKeyCode())) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return session.forward(event, controller.isComposing(), false);
        if (event.isCtrlPressed() || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            controller.abandonComposition();
            return session.forward(event, false, true);
        }
        controller.beginBatch();
        try {
            switch (controller.keyDown(event.getKeyCode())) {
                case COMMIT_THEN_FORWARD:
                    controller.flushComposition();
                    return session.forward(event, false, true);
                case BACKSPACE_COMPOSITION:
                    localKeys.add(event.getKeyCode());
                    return controller.backspace();
                default: return session.forward(event, controller.isComposing(), true);
            }
        } finally {
            controller.endBatch();
        }
    }

    @Override public void closeConnection() {
        if (closed) return;
        while (batchDepth > 0) endBatchEdit();
        closed = true;
        localKeys.clear();
        if (--session.connections == 0) controller.close();
        if (Build.VERSION.SDK_INT >= 24) super.closeConnection();
    }
}
