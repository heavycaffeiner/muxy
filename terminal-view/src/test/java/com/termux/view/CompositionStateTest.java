package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.*;

public class CompositionStateTest {
    @Test public void positiveCursorPositionsAreRelativeToEnd() {
        assertEquals(3, CompositionState.of("abc", 1).selectionEnd);
        assertEquals(3, CompositionState.of("abc", Integer.MAX_VALUE).selectionEnd);
    }

    @Test public void nonPositiveCursorPositionsAreClampedToStart() {
        assertEquals(0, CompositionState.of("abc", 0).selectionEnd);
        assertEquals(0, CompositionState.of("abc", Integer.MIN_VALUE).selectionEnd);
    }

    @Test public void selectionsStayOnUtf16Boundaries() {
        CompositionState state = new CompositionState("a😀b", 2, 100);
        assertEquals(1, state.selectionStart);
        assertEquals(4, state.selectionEnd);
        state = new CompositionState("a😀b", -100, 3);
        assertEquals(0, state.selectionStart);
        assertEquals(3, state.selectionEnd);
    }

    @Test public void malformedSurrogatesBecomeReplacementScalars() {
        assertEquals("\ufffda\ufffd", CompositionState.of("\ud800a\udc00", 1).text);
        assertEquals("😀", CompositionState.of("😀", 1).text);
    }

    @Test public void onlyPrecomposedHangulIsReleasedEarly() {
        assertTrue(CompositionState.isCompleteHangul("가한힣"));
        assertFalse(CompositionState.isCompleteHangul("ㅎ"));
        assertFalse(CompositionState.isCompleteHangul("\u1112\u1161\u11ab"));
        assertFalse(CompositionState.isCompleteHangul("한a"));
        assertFalse(CompositionState.isCompleteHangul(""));
    }

    @Test public void emptyAndNullInputUseEmptyState() {
        assertSame(CompositionState.EMPTY, CompositionState.of("", 1));
        assertSame(CompositionState.EMPTY, CompositionState.of(null, 1));
    }
}
