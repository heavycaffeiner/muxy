package com.termux.view;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class TerminalPointerInputTest {
    private TerminalView view;
    private TerminalEmulator emulator;
    private final StringBuilder reports = new StringBuilder();
    private long time;

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Before public void setUp() {
        time = SystemClock.uptimeMillis();
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            view = new TerminalView(context, null);
            view.mRenderer = new TerminalRenderer(24, Typeface.MONOSPACE);
            emulator = new TerminalEmulator(new TerminalOutput() {
                @Override public void write(byte[] data, int offset, int count) {
                    reports.append(new String(data, offset, count, StandardCharsets.UTF_8));
                }
                @Override public void titleChanged(String oldTitle, String newTitle) {}
                @Override public void onCopyTextToClipboard(String text) {}
                @Override public void onPasteTextFromClipboard() {}
                @Override public void onBell() {}
                @Override public void onColorsChanged() {}
            }, 40, 8, 10, view.mRenderer.mFontLineSpacing, 2000, null);
            view.mEmulator = emulator;
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 100; i++) output.append(i).append("\r\n");
            feed(output.toString());
            assertTrue(emulator.getScreen().getActiveTranscriptRows() > 50);
        });
    }

    @After public void tearDown() {
        onMain(() -> {
            view.mScroller.abortAnimation();
            view.mEmulator = null;
            emulator.close();
        });
    }

    private void feed(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private MotionEvent event(int action, int source, int classification, float vertical, float horizontal,
                              int buttons, int pointers, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointers];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[pointers];
        for (int i = 0; i < pointers; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = 20 + i * 20;
            coordinates[i].y = y;
            coordinates[i].setAxisValue(action == MotionEvent.ACTION_SCROLL ? MotionEvent.AXIS_VSCROLL : 51, vertical);
            coordinates[i].setAxisValue(action == MotionEvent.ACTION_SCROLL ? MotionEvent.AXIS_HSCROLL : 50, horizontal);
        }
        long at = ++time;
        if (Build.VERSION.SDK_INT >= 34) {
            return MotionEvent.obtain(at, at, action, pointers, properties, coordinates, 0, buttons,
                    1f, 1f, 1, 0, source, 0, 0, classification);
        }
        return MotionEvent.obtain(at, at, action, pointers, properties, coordinates, 0, buttons,
                1f, 1f, 1, 0, source, 0);
    }

    private void generic(MotionEvent event) {
        try { assertTrue(view.onGenericMotionEvent(event)); } finally { event.recycle(); }
    }

    @Test public void wheelAndTouchpadSourcesUseFractionalVerticalTravelOnly() {
        onMain(() -> {
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 0, 1, 0, 0, 1, 40));
            assertEquals(-3, view.getTopRow());
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_TOUCHPAD, 0, 0.125f, 0, 0, 1, 40));
            assertEquals(-3, view.getTopRow());
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_TOUCHPAD, 0, 0.125f, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_TOUCHPAD, 0, 0.125f, 0, 0, 1, 40));
            assertEquals(-4, view.getTopRow());
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 0, 0, 2, 0, 1, 40));
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 0, Float.NaN, 0, 0, 1, 40));
            assertEquals(-4, view.getTopRow());
            generic(event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 0, -1, 0, 0, 1, 40));
            assertEquals(-1, view.getTopRow());
            assertEquals("", reports.toString());
        });
    }

    @Test public void classifiedSwipeBatchesScrollHistoryWithoutMouseClicks() {
        assumeTrue(Build.VERSION.SDK_INT >= 34);
        onMain(() -> {
            int source = InputDevice.SOURCE_MOUSE;
            generic(event(MotionEvent.ACTION_DOWN, source, 3, 0, 0, 0, 1, 40));
            MotionEvent move = event(MotionEvent.ACTION_MOVE, source, 3,
                    -view.mRenderer.mFontLineSpacing * 1.5f, 0, 0, 1, 40);
            MotionEvent.PointerCoords sample = new MotionEvent.PointerCoords();
            sample.x = 20; sample.y = 40;
            sample.setAxisValue(51, -view.mRenderer.mFontLineSpacing * 1.5f);
            move.addBatch(++time, new MotionEvent.PointerCoords[]{sample}, 0);
            generic(move);
            generic(event(MotionEvent.ACTION_UP, source, 3, 0, 0, 0, 1, 40));
            assertEquals(-3, view.getTopRow());
            generic(event(MotionEvent.ACTION_DOWN, source, 3, 0, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_MOVE, source, 3, view.mRenderer.mFontLineSpacing * 2f, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_UP, source, 3, 0, 0, 0, 1, 40));
            assertEquals(-1, view.getTopRow());
            assertFalse(view.isSelectingText());
            assertEquals("", reports.toString());
        });
    }

    @Test public void touchAndGenericRoutesDoNotDuplicateRemoteWheelOrSendButtonDrags() {
        assumeTrue(Build.VERSION.SDK_INT >= 34);
        onMain(() -> {
            feed("\033[?1002h\033[?1006h");
            assertTrue(emulator.isMouseTrackingActive());
            generic(event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            MotionEvent move = event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, 3,
                    -view.mRenderer.mFontLineSpacing * 2f, 0, 0, 1, 40);
            try {
                assertTrue(view.onGenericMotionEvent(move));
                assertTrue(view.onTouchEvent(move));
            } finally { move.recycle(); }
            generic(event(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            int row = 1 + (40 - view.mRenderer.mFontLineSpacingAndAscent) / view.mRenderer.mFontLineSpacing;
            int column = 1 + (int) (20 / view.mRenderer.mFontWidth);
            String report = "\033[<64;" + column + ";" + row + "M";
            assertEquals(report + report, reports.toString());
            assertEquals(0, view.getTopRow());
        });
    }

    @Test public void unclassifiedTwoFingerTouchpadMovesScrollWithoutPinching() {
        onMain(() -> {
            int source = InputDevice.SOURCE_TOUCHPAD;
            generic(event(MotionEvent.ACTION_DOWN, source, 0, 0, 0, 0, 2, 160));
            generic(event(MotionEvent.ACTION_MOVE, source, 0, 0, 0, 0, 2,
                    160 + view.mRenderer.mFontLineSpacing * 3));
            generic(event(MotionEvent.ACTION_UP, source, 0, 0, 0, 0, 2, 80));
            assertEquals(-3, view.getTopRow());
            assertFalse(view.isSelectingText());
            assertEquals("", reports.toString());
        });
    }

    @Test public void buttonlessTouchpadDownBeforeClassificationDoesNotSendAClick() {
        assumeTrue(Build.VERSION.SDK_INT >= 34);
        onMain(() -> {
            feed("\033[?1002h\033[?1006h");
            MotionEvent down = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, 0, 0, 0, 0, 1, 40);
            try { assertTrue(view.onTouchEvent(down)); } finally { down.recycle(); }
            assertEquals("", reports.toString());
            generic(event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, 3,
                    -view.mRenderer.mFontLineSpacing, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            assertTrue(reports.toString().startsWith("\033[<64;"));
            assertFalse(reports.toString().contains("\033[<0;"));
            assertFalse(reports.toString().contains("\033[<32;"));
        });
    }

    @Test public void primaryMousePressStillReportsTheMatchingRelease() {
        onMain(() -> {
            feed("\033[?1002h\033[?1006h");
            MotionEvent down = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, 0, 0, 0,
                    MotionEvent.BUTTON_PRIMARY, 1, 40);
            MotionEvent up = event(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, 0, 0, 0, 0, 1, 40);
            try {
                assertTrue(view.onTouchEvent(down));
                assertTrue(view.onTouchEvent(up));
            } finally { down.recycle(); up.recycle(); }
            assertTrue(reports.toString().startsWith("\033[<0;"));
            assertTrue(reports.toString().endsWith("m"));
            assertEquals(2, reports.toString().split("\\Q\033[<0;\\E", -1).length - 1);
        });
    }

    @Test public void lateGestureClassificationStillStartsScrollingOnMove() {
        assumeTrue(Build.VERSION.SDK_INT >= 34);
        onMain(() -> {
            generic(event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, 3,
                    -view.mRenderer.mFontLineSpacing * 2, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_CANCEL, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            assertEquals(-2, view.getTopRow());
            assertFalse(view.isSelectingText());
            assertEquals("", reports.toString());
        });
    }

    @Test public void scrollDeliveredThroughTouchDuringAGestureIsNotLostOrDuplicated() {
        assumeTrue(Build.VERSION.SDK_INT >= 34);
        onMain(() -> {
            generic(event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            MotionEvent scroll = event(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 0, 1, 0, 0, 1, 40);
            try {
                assertTrue(view.onTouchEvent(scroll));
                assertTrue(view.onGenericMotionEvent(scroll));
            } finally { scroll.recycle(); }
            generic(event(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            assertEquals(-3, view.getTopRow());
            assertEquals("", reports.toString());
        });
    }

    @Test public void pinchAndHorizontalSwipeDoNotScrollVertically() {
        assumeTrue(Build.VERSION.SDK_INT >= 34);
        onMain(() -> {
            generic(event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, 5, 0, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, 5, 200, 0, 0, 1, 10));
            generic(event(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, 5, 0, 0, 0, 1, 10));
            generic(event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            generic(event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE, 3, 0.1f, 100, 0, 1, 40));
            generic(event(MotionEvent.ACTION_UP, InputDevice.SOURCE_MOUSE, 3, 0, 0, 0, 1, 40));
            assertEquals(0, view.getTopRow());
            assertEquals("", reports.toString());
        });
    }
}
