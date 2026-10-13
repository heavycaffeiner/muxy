package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PointerScrollAccumulatorTest {
    @Test public void fractionalRowsAccumulateInBothDirections() {
        PointerScrollAccumulator scroll = new PointerScrollAccumulator();
        assertEquals(0, scroll.advance(-0.4f, 1, 0));
        assertEquals(0, scroll.advance(-0.4f, 1, 0));
        assertEquals(-1, scroll.advance(-0.4f, 1, 0));
        assertEquals(0, scroll.advance(0.5f, 1, 0));
        assertEquals(1, scroll.advance(0.5f, 1, 0));
    }

    @Test public void deviceAndDestinationChangesDiscardPartialRows() {
        PointerScrollAccumulator scroll = new PointerScrollAccumulator();
        assertEquals(0, scroll.advance(0.7f, 1, 0));
        assertEquals(0, scroll.advance(0.7f, 2, 0));
        assertEquals(0, scroll.advance(0.7f, 2, 1));
        assertEquals(1, scroll.advance(0.4f, 2, 1));
        scroll.reset();
        assertEquals(0, scroll.advance(0.4f, 2, 1));
    }

    @Test public void invalidAndExtremeTravelCannotFloodScroll() {
        PointerScrollAccumulator scroll = new PointerScrollAccumulator();
        assertEquals(0, scroll.advance(Float.NaN, 1, 0));
        assertEquals(0, scroll.advance(Float.POSITIVE_INFINITY, 1, 0));
        assertEquals(0, scroll.advance(0, 1, 0));
        assertEquals(120, scroll.advance(Float.MAX_VALUE, 1, 0));
        assertEquals(-120, scroll.advance(-Float.MAX_VALUE, 1, 0));
    }
}
