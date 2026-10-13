package com.termux.view;

import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.HashSet;
import java.util.Set;

public final class TerminalInputConnection extends BaseInputConnection {

    public interface Sink {
        void sendText(String text);
        boolean sendKey(KeyEvent event, boolean composing);
        void compositionChanged(CompositionState state);
        void cursorUpdates(boolean immediate, boolean monitor);
    }

    private final Sink sink;
    private final TerminalImeController controller;
    private final Set<Integer> localKeys = new HashSet<>();
    private boolean closed;
    private int batchDepth;
    private boolean compositionDirty;

    public TerminalInputConnection(View view, Sink sink) {
        super(view, false);
        if (sink == null) throw new IllegalArgumentException("Missing terminal input sink");
        this.sink = sink;
        controller = new TerminalImeController(new TerminalImeController.Sink() {
            @Override
            public void sendText(String text) {
                if (!closed) sink.sendText(text);
            }

            @Override
            public void sendKey(int code) {
                if (!closed) sink.sendKey(new KeyEvent(KeyEvent.ACTION_DOWN, code), false);
            }

            @Override
            public void compositionChanged(CompositionState state) {
                if (batchDepth == 0) sink.compositionChanged(state);
                else compositionDirty = true;
            }
        });
    }

    public CompositionState composition() {
        return controller.composition();
    }

    public CompositionState editorComposition() {
        return controller.editorComposition();
    }

    public boolean isBatchEditing() {
        return batchDepth > 0;
    }

    @Override
    public boolean beginBatchEdit() {
        if (closed || batchDepth == Integer.MAX_VALUE) return false;
        batchDepth++;
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        if (closed || batchDepth == 0) return false;
        batchDepth--;
        if (batchDepth == 0 && compositionDirty) {
            compositionDirty = false;
            sink.compositionChanged(controller.composition());
        }
        return batchDepth > 0;
    }

    public void abandonComposition() {
        controller.abandonComposition();
    }

    public boolean terminalKey(int code) {
        if (closed || !controller.isComposing()) return false;
        switch (controller.keyDown(code)) {
            case COMMIT_THEN_FORWARD: controller.flushComposition(); return false;
            case BACKSPACE_COMPOSITION: return controller.backspace();
            default: controller.abandonComposition(); return false;
        }
    }

    @Override
    public boolean setComposingText(CharSequence text, int cursorPosition) {
        if (closed) return false;
        controller.composingText(text, cursorPosition);
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (closed || start < 0 || end < 0) return false;
        controller.editorSelection(start, end);
        return true;
    }

    @Override
    public boolean finishComposingText() {
        if (closed) return false;
        controller.finishComposing();
        return true;
    }

    @Override
    public boolean commitText(CharSequence text, int cursorPosition) {
        if (closed) return false;
        controller.commitText(text);
        return true;
    }

    @Override
    public boolean setSelection(int start, int end) {
        if (closed || start < 0 || end < 0) return false;
        controller.editorSelection(start, end);
        return true;
    }

    private boolean delete(int before, int after, boolean codepoints) {
        if (closed || before < 0 || after < 0) return false;
        if (controller.deleteSurrounding(before, after, codepoints)) return true;
        for (int i = 0, count = controller.terminalDeletes(before); i < count; i++) {
            sink.sendKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false);
        }
        for (int i = 0, count = controller.terminalDeletes(after); i < count; i++) {
            sink.sendKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL), false);
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int before, int after) {
        return delete(before, after, false);
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int before, int after) {
        return delete(before, after, true);
    }

    @Override
    public CharSequence getTextBeforeCursor(int length, int flags) {
        if (closed) return "";
        CompositionState state = controller.editorComposition();
        int caret = Math.min(state.selectionStart, state.selectionEnd);
        int start = (int) Math.max(0, (long) caret - Math.max(0, length));
        if (start > 0 && start < state.text.length() && Character.isLowSurrogate(state.text.charAt(start))) start++;
        return state.text.substring(start, caret);
    }

    @Override
    public CharSequence getTextAfterCursor(int length, int flags) {
        if (closed) return "";
        CompositionState state = controller.editorComposition();
        int caret = Math.max(state.selectionStart, state.selectionEnd);
        int end = (int) Math.min(state.text.length(), (long) caret + Math.max(0, length));
        if (end > 0 && end < state.text.length() && Character.isLowSurrogate(state.text.charAt(end))) end--;
        return state.text.substring(caret, end);
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        if (closed) return null;
        CompositionState state = controller.editorComposition();
        int start = Math.min(state.selectionStart, state.selectionEnd);
        int end = Math.max(state.selectionStart, state.selectionEnd);
        return start == end ? null : state.text.substring(start, end);
    }

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        CompositionState state = controller.editorComposition();
        ExtractedText result = new ExtractedText();
        result.text = closed ? "" : state.text;
        result.startOffset = 0;
        result.partialStartOffset = -1;
        result.partialEndOffset = -1;
        result.selectionStart = closed ? 0 : state.selectionStart;
        result.selectionEnd = closed ? 0 : state.selectionEnd;
        result.flags = ExtractedText.FLAG_SINGLE_LINE;
        return result;
    }

    @Override
    public boolean requestCursorUpdates(int mode) {
        int accepted = InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR;
        if (closed || (mode & ~accepted) != 0) return false;
        sink.cursorUpdates((mode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0,
                (mode & InputConnection.CURSOR_UPDATE_MONITOR) != 0);
        return true;
    }

    @Override
    public boolean performEditorAction(int action) {
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
                && action != EditorInfo.IME_ACTION_DONE) return false;
        return sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (closed || event == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && localKeys.remove(event.getKeyCode())) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return sink.sendKey(event, controller.isComposing());
        if (event.isCtrlPressed() || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            controller.abandonComposition();
            return sink.sendKey(event, false);
        }
        switch (controller.keyDown(event.getKeyCode())) {
            case COMMIT_THEN_FORWARD:
                controller.flushComposition();
                return sink.sendKey(event, false);
            case BACKSPACE_COMPOSITION:
                localKeys.add(event.getKeyCode());
                return controller.backspace();
            default:
                return sink.sendKey(event, controller.isComposing());
        }
    }

    @Override
    public void closeConnection() {
        if (closed) return;
        closed = true;
        batchDepth = 0;
        compositionDirty = false;
        localKeys.clear();
        controller.close();
        if (Build.VERSION.SDK_INT >= 24) super.closeConnection();
    }
}
