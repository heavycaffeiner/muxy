package com.termux.view;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TerminalImeControllerTest {
    private final List<String> events = new ArrayList<>();
    private final StringBuilder terminal = new StringBuilder();
    private TerminalImeController ime;

    @Before
    public void setUp() {
        events.clear();
        terminal.setLength(0);
        ime = new TerminalImeController(new TerminalImeController.Sink() {
            @Override public void sendText(String text) {
                events.add("text:" + text);
                terminal.append(text);
            }
            @Override public void sendKey(int code) {
                events.add("key:" + code);
                if (code == KeyEvent.KEYCODE_DEL && terminal.length() > 0) {
                    terminal.delete(terminal.offsetByCodePoints(terminal.length(), -1), terminal.length());
                } else if (code == KeyEvent.KEYCODE_ENTER) {
                    terminal.append('\n');
                }
            }
            @Override public void compositionChanged(CompositionState state) {}
        });
    }

    private void typeHello() {
        for (String text : Arrays.asList("ㅇ", "아", "안", "안ㄴ", "안녀", "안녕", "안녕ㅎ", "안녕하",
                "안녕하ㅅ", "안녕하세", "안녕하세ㅇ", "안녕하세요")) ime.composingText(text, 1);
    }

    private String displayed() {
        return terminal.toString() + ime.composition().text;
    }

    @Test public void finalSyllableRemainsLocal() {
        for (String text : Arrays.asList("ㅎ", "하", "한")) ime.composingText(text, 1);
        assertTrue(events.isEmpty());
        assertEquals("한", ime.composition().text);
        assertTrue(ime.commitText("한"));
        assertEquals(Arrays.asList("text:한"), events);
    }

    @Test public void settledHangulArrivesInOrder() {
        typeHello();
        assertEquals(Arrays.asList("text:안", "text:녕", "text:하", "text:세"), events);
        assertEquals("요", ime.composition().text);
        ime.commitText("안녕하세요");
        assertEquals("안녕하세요", terminal.toString());
        assertFalse(ime.isComposing());
    }

    @Test public void finishThenCommitDoesNotRepeatReleasedPrefix() {
        typeHello();
        ime.finishComposing();
        assertEquals("안녕하세", terminal.toString());
        ime.commitText("안녕하세요");
        assertEquals("안녕하세요", terminal.toString());
    }

    @Test public void repeatedWordsAreNotSuppressed() {
        for (int i = 0; i < 2; i++) {
            typeHello();
            ime.commitText("안녕하세요");
        }
        assertEquals("안녕하세요안녕하세요", terminal.toString());
    }

    @Test public void flushThenWholeWordEchoIsConsumedOnce() {
        typeHello();
        ime.flushComposition();
        assertFalse(ime.commitText("안녕하세요"));
        assertTrue(ime.commitText("안녕하세요"));
        assertEquals("안녕하세요안녕하세요", terminal.toString());
    }

    @Test public void flushThenTailEchoIsConsumed() {
        typeHello();
        ime.flushComposition();
        assertFalse(ime.commitText("요"));
        assertEquals("안녕하세요", terminal.toString());
    }

    @Test public void echoWithNewSuffixPreservesSuffix() {
        ime.composingText("한", 1);
        ime.flushComposition();
        assertTrue(ime.commitText("한글"));
        assertEquals("한글", terminal.toString());
    }

    @Test public void differentCommitAfterFlushIsRealInput() {
        ime.composingText("한", 1);
        ime.flushComposition();
        ime.commitText("글");
        assertEquals("한글", terminal.toString());
    }

    @Test public void newCompositionClearsEchoGuard() {
        ime.composingText("한", 1);
        ime.flushComposition();
        ime.composingText("한", 1);
        ime.commitText("한");
        assertEquals("한한", terminal.toString());
    }

    @Test public void passedEnterCommitsBeforeTheKey() {
        ime.composingText("한", 1);
        assertEquals(TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD, ime.keyDown(KeyEvent.KEYCODE_ENTER));
        ime.flushComposition();
        events.add("key:" + KeyEvent.KEYCODE_ENTER);
        assertEquals(Arrays.asList("text:한", "key:" + KeyEvent.KEYCODE_ENTER), events);
        assertFalse(ime.commitText("한"));
    }

    @Test public void passedSpaceAndNumpadEnterFlushComposition() {
        ime.composingText("ㅎ", 1);
        assertEquals(TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD, ime.keyDown(KeyEvent.KEYCODE_SPACE));
        assertEquals(TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD, ime.keyDown(KeyEvent.KEYCODE_NUMPAD_ENTER));
    }

    @Test public void candidateReplacementRetractsOnlyChangedPrefix() {
        ime.composingText("안뎡하세요", 1);
        events.clear();
        ime.commitText("안녕하세요");
        assertEquals(Arrays.asList("key:67", "key:67", "key:67", "text:녕하세요"), events);
        assertEquals("안녕하세요", terminal.toString());
    }

    @Test public void candidateRevisionDuringCompositionPreservesFinalCluster() {
        ime.composingText("안뎡하세요", 1);
        ime.composingText("안녕하세요", 1);
        assertEquals("안녕하세요", displayed());
        assertEquals("요", ime.composition().text);
    }

    @Test public void hangulBackspacesAndRetypingTrackThePreedit() {
        typeHello();
        for (String text : Arrays.asList("안녕하셍", "안녕하세", "안녕핫")) {
            ime.composingText(text, 1);
            assertEquals(text, displayed());
        }
        ime.composingText("안녕하세요", 1);
        ime.commitText("안녕하세요");
        assertEquals("안녕하세요", terminal.toString());
    }

    @Test public void removingTheWholeHangulWordLeavesNoText() {
        typeHello();
        for (String text : Arrays.asList("안녕하셍", "안녕하세", "안녕핫", "안녕하", "안녕ㅎ", "안녕",
                "안녀", "안ㄴ", "안", "아", "ㅇ", "")) {
            ime.composingText(text, 1);
            assertEquals(text, displayed());
        }
    }

    @Test public void latinJapaneseAndChineseCandidatesRemainLocal() {
        for (String text : Arrays.asList("hello", "にほん", "日本", "中文", "繁體中文")) {
            ime.composingText(text, 1);
            assertTrue(events.isEmpty());
            assertEquals(text, ime.composition().text);
        }
        ime.commitText("繁體中文");
        assertEquals("繁體中文", terminal.toString());
    }

    @Test public void decomposedHangulIsNotNormalized() {
        String text = "\u1112\u1161\u11ab";
        ime.composingText(text, 1);
        assertTrue(events.isEmpty());
        ime.commitText(text);
        assertEquals(text, terminal.toString());
    }

    @Test public void malformedUtf16IsSanitizedBeforeSending() {
        ime.commitText("a\ud800b\udc00");
        assertEquals("a\ufffdb\ufffd", terminal.toString());
    }

    @Test public void committedNewlinesUseEnterInOrder() {
        ime.commitText("한\n글\n");
        assertEquals(Arrays.asList("text:한", "key:66", "text:글", "key:66"), events);
        assertEquals("한\n글\n", terminal.toString());
    }

    @Test public void finishAndCloseNeverSubmitUndecidedText() {
        ime.composingText("日本", 1);
        ime.finishComposing();
        ime.composingText("中文", 1);
        ime.close();
        assertTrue(events.isEmpty());
        assertFalse(ime.isComposing());
    }

    @Test public void backspaceRemovesWholeEmojiClusterLocally() {
        ime.composingText("a👩‍💻", 1);
        assertTrue(ime.backspace());
        assertEquals("a", ime.composition().text);
        assertTrue(events.isEmpty());
    }

    @Test public void backspaceInsideClusterDoesNotSplitIt() {
        ime.composingText("ae\u0301b", 1);
        ime.selection(2, 2);
        assertTrue(ime.backspace());
        assertEquals("ab", ime.composition().text);
        assertEquals(1, ime.composition().selectionEnd);
    }

    @Test public void utf16DeletionDoesNotSplitSurrogates() {
        ime.composingText("a😀b", 1);
        ime.selection(3, 3);
        assertTrue(ime.deleteSurrounding(1, 0, false));
        assertEquals("ab", ime.composition().text);
        assertTrue(events.isEmpty());
    }

    @Test public void codepointDeletionHonorsItsUnits() {
        ime.composingText("a😀b", 1);
        assertTrue(ime.deleteSurrounding(2, 0, true));
        assertEquals("a", ime.composition().text);
    }

    @Test public void selectedPreeditDeletionDoesNotReachTerminal() {
        ime.composingText("日本", 1);
        ime.selection(0, 2);
        assertTrue(ime.deleteSurrounding(0, 0, false));
        assertFalse(ime.isComposing());
        assertTrue(events.isEmpty());
    }

    @Test public void deletionAtStartIsConsumedWithoutTerminalBackspace() {
        ime.composingText("日本", 0);
        assertTrue(ime.backspace());
        assertEquals("日本", ime.composition().text);
        assertTrue(events.isEmpty());
    }

    @Test public void betaReplacesCommittedJamoAndCarriesTheFinalConsonant() {
        ime.commitText("ㅇ");
        assertTrue(ime.replaceText(0, 1, "아", 1));
        assertTrue(ime.replaceText(0, 1, "안", 1));
        assertEquals("안", terminal.toString());
        assertTrue(ime.replaceText(0, 1, "아녀", 1));
        assertTrue(ime.replaceText(1, 2, "녕", 1));
        assertEquals("아녕", terminal.toString());
        assertEquals("아녕", ime.editorComposition().text);
        assertFalse(ime.isComposing());
    }

    @Test public void betaBackspaceAndAutocorrectTrackAbsoluteOffsets() {
        for (char c : "teh quick".toCharArray()) ime.commitText(String.valueOf(c));
        for (int i = 0; i < 2; i++) {
            ime.terminalKeySent(KeyEvent.KEYCODE_DEL);
            terminal.deleteCharAt(terminal.length() - 1);
        }
        ime.commitText("k");
        assertTrue(ime.replaceText(4, 8, "quick ", 1));
        for (char c : "tset".toCharArray()) ime.commitText(String.valueOf(c));
        assertTrue(ime.replaceText(10, 14, "test ", 1));
        assertEquals("teh quick test ", terminal.toString());
        assertEquals(terminal.toString(), ime.editorComposition().text);
    }

    @Test public void betaCompoundFinalConsonantSplitsIntoTwoSyllables() {
        ime.commitText("ㅇ");
        ime.replaceText(0, 1, "아", 1);
        ime.replaceText(0, 1, "안", 1);
        ime.replaceText(0, 1, "앉", 1);
        ime.replaceText(0, 1, "안자", 1);
        assertEquals("안자", terminal.toString());
    }

    @Test public void invalidReplacementDoesNotModifyTerminalOrEditor() {
        ime.commitText("a😀b");
        events.clear();
        for (int[] range : new int[][]{{-1, 2}, {0, 10}, {1, 2}, {2, 3}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertFalse(ime.replaceText(range[0], range[1], "x", 1));
        }
        assertFalse(ime.replaceText(0, 1, "x", 1));
        assertFalse(ime.replaceText(0, 4, "\n", 1));
        assertEquals("a😀b", ime.editorComposition().text);
        assertTrue(events.isEmpty());
    }

    @Test public void submittedInputCannotBeRewrittenByAStaleReplacement() {
        ime.commitText("한");
        ime.terminalKeySent(KeyEvent.KEYCODE_ENTER);
        assertFalse(ime.replaceText(0, 1, "글", 1));
        ime.commitText("ㅇ");
        assertTrue(ime.replaceText(1, 2, "아", 1));
        assertEquals("한아", terminal.toString());
    }

    @Test public void boundedContextRetainsAbsoluteOffsetsWithoutLosingInput() {
        String text = new String(new char[5000]).replace('\0', 'a');
        ime.commitText(text);
        assertEquals(text, terminal.toString());
        assertEquals(904, ime.documentOffset());
        assertFalse(ime.replaceText(0, 1, "b", 1));
        assertTrue(ime.replaceText(4999, 5000, "b", 1));
        assertEquals(text.substring(0, 4999) + "b", terminal.toString());
    }

    @Test public void repeatedBackspaceAfterFinishingHangulClearsReleasedContext() {
        ime.composingText("안녕", 1);
        ime.finishComposing();
        ime.terminalKeySent(KeyEvent.KEYCODE_DEL);
        terminal.setLength(0);
        ime.terminalKeySent(KeyEvent.KEYCODE_DEL);
        assertEquals("", ime.editorComposition().text);
        assertEquals(-1, ime.composingStart());
        ime.commitText("ㅇ");
        assertTrue(ime.replaceText(0, 1, "아", 1));
        assertTrue(ime.replaceText(0, 1, "안", 1));
        assertEquals("안", terminal.toString());
    }

    @Test public void navigationCommitsPreeditBeforeInvalidatingItsEditableRange() {
        for (int code : new int[]{KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_F1}) {
            setUp();
            ime.composingText("한글", 1);
            assertEquals(TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD, ime.keyDown(code));
            ime.flushComposition();
            ime.terminalKeySent(code);
            assertEquals("한글", terminal.toString());
            assertEquals("", ime.editorComposition().text);
            assertFalse(ime.commitText("한글"));
            int start = ime.documentOffset();
            ime.commitText("ㅇ");
            assertTrue(ime.replaceText(start, start + 1, "아", 1));
            assertTrue(ime.replaceText(start, start + 1, "안", 1));
            assertEquals("한글안", terminal.toString());
        }
    }

    @Test public void backspaceAfterFlushDoesNotSuppressTheNextIdenticalCommit() {
        ime.composingText("한", 1);
        ime.flushComposition();
        ime.terminalKeySent(KeyEvent.KEYCODE_DEL);
        terminal.setLength(0);
        ime.commitText("한");
        assertEquals("한", terminal.toString());
    }

    @Test public void terminalDeletionCountIsBounded() {
        assertEquals(64, ime.terminalDeletes(Integer.MAX_VALUE));
        assertEquals(0, ime.terminalDeletes(-1));
        ime.composingText("한", 1);
        assertEquals(0, ime.terminalDeletes(1));
    }
}
