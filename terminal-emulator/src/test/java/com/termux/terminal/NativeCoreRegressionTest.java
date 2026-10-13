package com.termux.terminal;

import android.view.KeyEvent;

import java.nio.charset.StandardCharsets;

public class NativeCoreRegressionTest extends TerminalTestCase {
    public void testHistoryClearUpdatesTheReadAdapterImmediately() {
        withTerminalSized(5, 3);
        enterString("one\r\ntwo\r\nthree\r\nfour");
        assertTrue(mTerminal.getScreen().getActiveTranscriptRows() > 0);
        mTerminal.getScreen().clearTranscript();
        assertEquals(0, mTerminal.getScreen().getActiveTranscriptRows());
        assertEquals("two\nthree\nfour", mTerminal.getScreen().getTranscriptText());
    }

    public void testBlankCellsKeepForegroundBackgroundAndEffects() {
        withTerminalSized(6, 4).enterString("\033[1;3;4;7;38;5;98;48;5;99m\033[2J");
        int effects = TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_ITALIC
                | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE | TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 6; col++) {
            long style = getStyleAt(row, col);
            assertEquals(98, TextStyle.decodeForeColor(style));
            assertEquals(99, TextStyle.decodeBackColor(style));
            assertEquals(effects, TextStyle.decodeEffect(style));
        }
        enterString("\033[4;1H\n");
        assertEquals(effects, TextStyle.decodeEffect(getStyleAt(3, 5)));
    }

    public void testStyledScrollCrossesPageBoundaries() {
        withTerminalSized(80, 4).enterString("\033[4;38;5;98;48;5;99m");
        for (int i = 0; i < 400; i++) enterString("line\r\n");
        int history = mTerminal.getScreen().getActiveTranscriptRows();
        assertTrue(history > 100);
        for (int row = -history; row < 4; row++) {
            long style = getStyleAt(row, 79);
            // Rows predating the first scroll were created before SGR.
            if (TextStyle.decodeBackColor(style) == 99) {
                assertEquals(98, TextStyle.decodeForeColor(style));
                assertEquals(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.decodeEffect(style));
            }
        }
    }

    public void testTopMarginScrollRetainsRowsAcrossNativePages() {
        withTerminalSized(80, 10);
        for (int i = 0; i < 1000; i++) enterString("line" + i + "\r\n");
        String fixedTop = mTerminal.getSelectedText(0, 0, 79, 1);
        enterString("\033[3;9r\033[9;1H");
        for (int i = 0; i < 200; i++) {
            int history = mTerminal.getScreen().getActiveTranscriptRows();
            String departing = mTerminal.getSelectedText(0, 2, 79, 2);
            enterString("\r\n");
            assertEquals(history + 1, mTerminal.getScreen().getActiveTranscriptRows());
            assertEquals(departing, mTerminal.getSelectedText(0, -1, 79, -1));
            assertEquals(fixedTop, mTerminal.getSelectedText(0, 0, 79, 1));
        }
    }

    public void testUtf8CodepointsCanSpanEveryByteBoundary() {
        String text = "한日本𠀀e\u0301";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= bytes.length; split++) {
            withTerminalSized(20, 3);
            mTerminal.append(bytes, split);
            byte[] suffix = new byte[bytes.length - split];
            System.arraycopy(bytes, split, suffix, 0, suffix.length);
            mTerminal.append(suffix, suffix.length);
            assertEquals(text, mTerminal.getScreen().getTranscriptText());
            assertInvariants();
        }
    }

    public void testWideCellSelectionIncludesTheWholeCluster() {
        withTerminalSized(10, 3).enterString("a枝\u0302b");
        assertEquals("枝\u0302", mTerminal.getSelectedText(2, 0, 2, 0));
        assertEquals(1, mTerminal.getScreen().snapColumn(0, 2));
    }

    public void testLegacyKeyEncodingPreservesTermuxBytes() {
        withTerminalSized(20, 3);
        int[] keys = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MOVE_HOME,
                KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_FORWARD_DEL};
        for (int key : keys) {
            mTerminal.key(key, 0, null, 1, false, 0);
            assertEquals("Key " + key, KeyHandler.getCode(key, 0, false, false), mOutput.getOutputAndClear());
        }
    }

    public void testTextModifiersPreserveControlAndAltInput() {
        withTerminalSized(20, 3);
        mTerminal.key(KeyEvent.KEYCODE_UNKNOWN, 2, "c", 1, false, 'c');
        assertEquals("\003", mOutput.getOutputAndClear());
        mTerminal.key(KeyEvent.KEYCODE_UNKNOWN, 4, "한", 1, false, 0xd55c);
        assertEquals("\033한", mOutput.getOutputAndClear());
        mTerminal.key(KeyEvent.KEYCODE_UNKNOWN, 2, " ", 1, false, ' ');
        assertEquals("\000", mOutput.getOutputAndClear());
    }

    public void testClosedEmulatorRejectsOutputButIgnoresLateInput() {
        withTerminalSized(5, 3);
        mTerminal.close();
        mTerminal.close();
        mTerminal.key(KeyEvent.KEYCODE_ENTER, 0, null, 1, false, 0);
        assertEquals("", mOutput.getOutputAndClear());
        try {
            mTerminal.append(new byte[]{'a'}, 1);
            fail("Closed emulator accepted output");
        } catch (IllegalStateException expected) {
            assertTrue(mTerminal.isClosed());
        }
    }
}
