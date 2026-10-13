package com.termux.view;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TerminalInputConnectionTest {
    private final List<String> events = new ArrayList<>();
    private TerminalInputConnection connection;
    private CompositionState composition;

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Before public void setUp() {
        onMain(() -> connection = new TerminalInputConnection(
                new View(InstrumentationRegistry.getInstrumentation().getTargetContext()),
                new TerminalInputConnection.Sink() {
                    @Override public void sendText(String text) { events.add("text:" + text); }
                    @Override public boolean sendKey(KeyEvent event, boolean composing) {
                        // TerminalView also calls terminalKey from its special-key dispatch.
                        if (event.getAction() == KeyEvent.ACTION_DOWN) connection.terminalKey(event.getKeyCode());
                        events.add("key:" + event.getAction() + ":" + event.getKeyCode());
                        return true;
                    }
                    @Override public void compositionChanged(CompositionState state) { composition = state; }
                    @Override public void cursorUpdates(boolean immediate, boolean monitor) {
                        events.add("cursor:" + immediate + ":" + monitor);
                    }
                }));
    }

    @Test public void enterFlushSurvivesNestedSpecialKeyDispatch() {
        onMain(() -> {
            connection.setComposingText("한", 1);
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            connection.commitText("한", 1);
            assertEquals(Arrays.asList("text:한", "key:0:66", "key:1:66"), events);
            assertTrue(composition.isEmpty());
        });
    }

    @Test public void localBackspaceConsumesItsReleaseAfterPreeditEmpties() {
        onMain(() -> {
            connection.setComposingText("한", 1);
            assertTrue(connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)));
            assertTrue(composition.isEmpty());
            assertTrue(connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)));
            assertTrue(events.isEmpty());
        });
    }

    @Test public void finishBeforeCommitSendsOnlyTheUnreleasedTail() {
        onMain(() -> {
            connection.setComposingText("안녕", 1);
            connection.finishComposingText();
            connection.commitText("안녕", 1);
            assertEquals(Arrays.asList("text:안", "text:녕"), events);
        });
    }

    @Test public void surroundingTextReportsOnlyLocalPreedit() {
        onMain(() -> {
            connection.commitText("shell text", 1);
            connection.setComposingText("a😀b", 1);
            connection.setSelection(1, 3);
            assertEquals("a", connection.getTextBeforeCursor(100, 0));
            assertEquals("b", connection.getTextAfterCursor(100, 0));
            assertEquals("😀", connection.getSelectedText(0));
            assertEquals("a😀b", connection.getExtractedText(null, 0).text);
            assertEquals(Arrays.asList("text:shell text"), events);
        });
    }

    @Test public void utf16AndCodepointDeletionKeepSurrogatesIntact() {
        onMain(() -> {
            connection.setComposingText("a😀b", 1);
            connection.setSelection(3, 3);
            assertTrue(connection.deleteSurroundingText(1, 0));
            assertEquals("ab", composition.text);
            connection.setComposingText("a😀b", 1);
            assertTrue(connection.deleteSurroundingTextInCodePoints(2, 0));
            assertEquals("a", composition.text);
            assertTrue(events.isEmpty());
        });
    }

    @Test public void closedConnectionsRejectStaleCallbacks() {
        onMain(() -> {
            connection.setComposingText("日本", 1);
            connection.closeConnection();
            connection.closeConnection();
            assertFalse(connection.commitText("日本", 1));
            assertFalse(connection.setComposingText("中文", 1));
            assertFalse(connection.deleteSurroundingText(1, 0));
            assertFalse(connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE));
            assertEquals("", connection.getTextBeforeCursor(10, 0));
            assertTrue(events.isEmpty());
            assertTrue(composition.isEmpty());
        });
    }

    @Test public void editorActionCommitsBeforeEnterAndIgnoresOtherActions() {
        onMain(() -> {
            connection.setComposingText("한", 1);
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE));
            connection.commitText("한", 1);
            assertFalse(connection.performEditorAction(EditorInfo.IME_ACTION_NEXT));
            assertEquals(Arrays.asList("text:한", "key:0:66"), events);
        });
    }

    @Test public void releasedHangulStaysInTheImeDocument() {
        onMain(() -> {
            connection.setComposingText("안녕", 1);
            assertEquals("녕", composition.text);
            assertEquals("안녕", connection.getTextBeforeCursor(100, 0));
            assertEquals("안녕", connection.getExtractedText(null, 0).text);
            assertEquals(2, connection.getExtractedText(null, 0).selectionEnd);
            connection.setSelection(1, 2);
            assertEquals("녕", connection.getSelectedText(0));
            assertEquals("안", connection.getTextBeforeCursor(100, 0));
            assertFalse(connection.setSelection(-1, Integer.MAX_VALUE));
            assertEquals(Arrays.asList("text:안"), events);
        });
    }

    @Test public void nestedBatchEditsPublishOnlyTheFinalPreedit() {
        onMain(() -> {
            assertTrue(connection.beginBatchEdit());
            assertTrue(connection.beginBatchEdit());
            connection.setComposingText("ㅇ", 1);
            connection.setComposingText("아", 1);
            connection.setComposingText("안녕", 1);
            assertNull(composition);
            assertEquals("안녕", connection.getTextBeforeCursor(100, 0));
            assertTrue(connection.endBatchEdit());
            assertNull(composition);
            assertFalse(connection.endBatchEdit());
            assertEquals("녕", composition.text);
            assertFalse(connection.endBatchEdit());
            connection.closeConnection();
            assertFalse(connection.beginBatchEdit());
        });
    }

    @Test public void cursorUpdatesRejectUnknownFlags() {
        onMain(() -> {
            assertFalse(connection.requestCursorUpdates(1 << 20));
            assertTrue(connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE
                    | InputConnection.CURSOR_UPDATE_MONITOR));
            assertEquals(Arrays.asList("cursor:true:true"), events);
        });
    }
}
