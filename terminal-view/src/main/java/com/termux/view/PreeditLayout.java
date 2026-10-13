package com.termux.view;

import com.termux.terminal.NativeTerminal;
import com.termux.terminal.TerminalText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PreeditLayout {

    public static final class Cluster {
        public final String text;
        public final int startUtf16;
        public final int cells;

        private Cluster(String text, int start, int cells) {
            this.text = text;
            startUtf16 = start;
            this.cells = cells;
        }
    }

    private PreeditLayout() {}

    public static List<Cluster> clusters(String input, boolean graphemeMode) {
        String text = TerminalText.sanitizeUtf16(input);
        if (text.isEmpty()) return Collections.emptyList();
        int[] layout = NativeTerminal.clusterLayout(text, graphemeMode);
        if (layout == null || layout.length % 3 != 0) throw new IllegalStateException("Invalid preedit layout");
        List<Cluster> result = new ArrayList<>(layout.length / 3);
        int totalCodepoints = text.codePointCount(0, text.length());
        int codepoints = 0;
        int utf16 = 0;
        for (int i = 0; i < layout.length; i += 3) {
            int start = layout[i];
            int count = layout[i + 1];
            int cells = layout[i + 2];
            if (start != codepoints || count <= 0 || count > totalCodepoints - codepoints
                    || cells < 0 || cells > 2L * count) throw new IllegalStateException("Invalid preedit cluster");
            int end = text.offsetByCodePoints(utf16, count);
            result.add(new Cluster(text.substring(utf16, end), utf16, cells));
            codepoints += count;
            utf16 = end;
        }
        if (utf16 != text.length()) throw new IllegalStateException("Incomplete preedit layout");
        return Collections.unmodifiableList(result);
    }

    public static int cellsBefore(List<Cluster> clusters, int offsetUtf16) {
        int cells = 0;
        for (Cluster cluster : clusters) {
            if (cluster.startUtf16 + cluster.text.length() > offsetUtf16) break;
            if (cluster.cells > Integer.MAX_VALUE - cells) throw new IllegalStateException("Preedit width overflow");
            cells += cluster.cells;
        }
        return cells;
    }

    public static int boundaryBefore(List<Cluster> clusters, int offsetUtf16) {
        int boundary = 0;
        for (Cluster cluster : clusters) {
            if (cluster.startUtf16 >= offsetUtf16) break;
            boundary = cluster.startUtf16;
        }
        return boundary;
    }
}
