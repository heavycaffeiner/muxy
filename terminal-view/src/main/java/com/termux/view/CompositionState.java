package com.termux.view;

import com.termux.terminal.TerminalText;

public final class CompositionState {

    public static final CompositionState EMPTY = new CompositionState("", 0, 0);

    public final String text;
    public final int selectionStart;
    public final int selectionEnd;

    public CompositionState(String text, int start, int end) {
        this.text = TerminalText.sanitizeUtf16(text);
        selectionStart = clampBoundary(this.text, start);
        selectionEnd = clampBoundary(this.text, end);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    public static CompositionState of(CharSequence text, int cursorPosition) {
        String valid = TerminalText.sanitizeUtf16(text);
        if (valid.isEmpty()) return EMPTY;
        long offset = cursorPosition > 0 ? (long) valid.length() + cursorPosition - 1 : cursorPosition;
        int caret = (int) Math.max(0, Math.min(valid.length(), offset));
        return new CompositionState(valid, caret, caret);
    }

    private static int clampBoundary(String text, int offset) {
        int value = Math.max(0, Math.min(text.length(), offset));
        if (value > 0 && value < text.length() && Character.isHighSurrogate(text.charAt(value - 1))
                && Character.isLowSurrogate(text.charAt(value))) return value - 1;
        return value;
    }

    public static boolean isCompleteHangul(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (cp < 0xac00 || cp > 0xd7a3) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
}
