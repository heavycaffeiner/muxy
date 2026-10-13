package com.termux.terminal;

/** Text validation at the IME and JNI boundary. */
public final class TerminalText {

    private TerminalText() {}

    public static String sanitizeUtf16(CharSequence input) {
        if (input == null) return "";
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < input.length()
                    && Character.isLowSurrogate(input.charAt(i + 1))) {
                result.append(c).append(input.charAt(++i));
            } else {
                result.append(Character.isSurrogate(c) ? '\ufffd' : c);
            }
        }
        return result.toString();
    }
}
