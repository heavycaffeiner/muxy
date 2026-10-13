package com.termux.view;

import com.termux.terminal.NativeTerminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class PreeditLayoutTest {
    private static final NativeTerminal.Listener LISTENER = new NativeTerminal.Listener() {
        @Override public void onWrite(byte[] data) {}
        @Override public void onBell() {}
        @Override public void onTitle(byte[] title) {}
        @Override public void onClipboard(byte[] text) {}
        @Override public void onEvent(int kind, byte[] data) {}
    };

    @Test public void cjkWidthsMatchNativeCellsInBothModes() {
        String[] samples = {"한글", "\u1112\u1161\u11ab", "ㄱㅏ", "日本語", "简体中文", "繁體中文", "Ａ，。",
                "e\u0301", "𠀀", "♥\ufe0f", "😀", "👩‍💻", "👍🏽", "🇰🇷"};
        for (boolean grapheme : new boolean[]{false, true}) {
            for (String text : samples) {
                try (NativeTerminal nativeTerminal = new NativeTerminal(80, 3, 100, LISTENER)) {
                    nativeTerminal.setMode(2027, false, grapheme);
                    byte[] bytes = (text + "X").getBytes(StandardCharsets.UTF_8);
                    nativeTerminal.write(bytes, 0, bytes.length);
                    List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters(text, grapheme);
                    int cells = PreeditLayout.cellsBefore(clusters, text.length());
                    assertEquals("Width mismatch for " + text + ", grapheme=" + grapheme, cells + 1, nativeTerminal.state()[2]);
                    NativeTerminal.Row row = nativeTerminal.readRow(0);
                    assertEquals('X', row.text[row.offsets[cells]]);
                }
            }
        }
    }

    @Test public void emojiClustersHaveNativeGraphemeWidth() {
        List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters("a👩‍💻𠀀", true);
        assertEquals(3, clusters.size());
        assertEquals("👩‍💻", clusters.get(1).text);
        assertEquals(1, clusters.get(1).startUtf16);
        assertEquals(2, clusters.get(1).cells);
        assertEquals(6, clusters.get(2).startUtf16);
        assertEquals(5, PreeditLayout.cellsBefore(clusters, 8));
    }

    @Test public void partialUtf16OffsetsDoNotSplitClusters() {
        List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters("ae\u0301😀", true);
        assertEquals(1, PreeditLayout.cellsBefore(clusters, 2));
        assertEquals(2, PreeditLayout.cellsBefore(clusters, 4));
        assertEquals(1, PreeditLayout.boundaryBefore(clusters, 2));
        assertEquals(3, PreeditLayout.boundaryBefore(clusters, 4));
    }

    @Test public void layoutKeepsOriginalNormalizationAndSanitizesSurrogates() {
        String input = "\u1112\u1161\u11ab\ud800";
        StringBuilder text = new StringBuilder();
        for (PreeditLayout.Cluster cluster : PreeditLayout.clusters(input, true)) text.append(cluster.text);
        assertEquals("\u1112\u1161\u11ab\ufffd", text.toString());
    }
}
