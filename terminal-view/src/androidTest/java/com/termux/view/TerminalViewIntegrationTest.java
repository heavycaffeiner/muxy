package com.termux.view;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TerminalViewIntegrationTest {
    private ActivityScenario<TerminalTestActivity> scenario;
    private InputConnection input;

    private interface Condition { boolean met(); }

    private void await(Condition condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.met()) {
            if (System.nanoTime() >= deadline) {
                AtomicReference<String> diagnostic = new AtomicReference<>();
                scenario.onActivity(activity -> diagnostic.set("running=" + activity.session.isRunning()
                        + ", title=" + activity.session.getTitle() + ", transcript="
                        + activity.session.getEmulator().getScreen().getTranscriptText()));
                fail("Timed out waiting for the terminal: " + diagnostic.get());
            }
            Thread.sleep(50);
        }
    }

    private boolean ready() {
        AtomicBoolean value = new AtomicBoolean();
        scenario.onActivity(activity -> value.set(activity.session.getEmulator() != null
                && activity.view.hasFocus() && activity.view.getWidth() > 0));
        return value.get();
    }

    private boolean titleIs(String title) {
        AtomicBoolean value = new AtomicBoolean();
        scenario.onActivity(activity -> value.set(title.equals(activity.session.getTitle())));
        return value.get();
    }

    private void write(String text) {
        scenario.onActivity(activity -> activity.session.write(text));
    }

    private void prepareCapture() throws InterruptedException {
        write("print -n '\\033]2;capture-ready\\007'; IFS= read -r value; "
                + "print -rn -- \"$value\" > \"$CAPTURE\"; print -n '\\033]2;capture-done\\007'\r");
        await(() -> titleIs("capture-ready"));
    }

    private void assertCaptured(String expected) throws Exception {
        await(() -> titleIs("capture-done"));
        AtomicReference<byte[]> captured = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try (FileInputStream stream = new FileInputStream(activity.capture);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                captured.set(bytes.toByteArray());
            } catch (Exception error) {
                throw new IllegalStateException("Cannot read PTY capture", error);
            }
        });
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), captured.get());
    }

    private void enter() {
        input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    @Before public void setUp() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), TerminalTestActivity.class);
        scenario = ActivityScenario.launch(intent);
        await(this::ready);
        scenario.onActivity(activity -> input = activity.view.onCreateInputConnection(new EditorInfo()));
    }

    @After public void tearDown() {
        if (scenario == null) return;
        try {
            scenario.onActivity(activity -> assertEquals(Arrays.asList(), activity.errors));
        } finally {
            scenario.close();
        }
    }

    @Test public void liveGboardTypingReachesThePty() throws Exception {
        String expected = InstrumentationRegistry.getArguments().getString("gboardExpected");
        org.junit.Assume.assumeNotNull(expected);
        prepareCapture();
        scenario.onActivity(activity -> {
            android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager)
                    activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            manager.restartInput(activity.view);
            manager.showSoftInput(activity.view, 0);
        });
        android.util.Log.i("GboardLiveTest", "READY muxy");
        assertCaptured(expected);
    }

    @Test public void alternateScreenMouseReportingStillDeliversTheTouchscreenTapCallback() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            byte[] modes = "\033[?1049h\033[?1000h\033[?1006h".getBytes(StandardCharsets.UTF_8);
            TerminalEmulator emulator = activity.session.getEmulator();
            emulator.append(modes, modes.length);
            assertTrue(emulator.isAlternateBufferActive());
            assertTrue(emulator.isMouseTrackingActive());
            long time = android.os.SystemClock.uptimeMillis();
            for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                android.view.MotionEvent tap = android.view.MotionEvent.obtain(time, time + action * 30,
                        action, 10, 10, 0);
                tap.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try { assertTrue(activity.view.onTouchEvent(tap)); } finally { tap.recycle(); }
            }
        });
        await(() -> {
            AtomicBoolean called = new AtomicBoolean();
            scenario.onActivity(activity -> called.set(activity.singleTapRequests == 1));
            return called.get();
        });
        scenario.onActivity(activity -> enter());
        assertCaptured("\033[<0;1;1M\033[<0;1;1m");
    }

    @Test public void physicalShiftBetweenTheVowelAndDoubleFinalPreservesBetaReplacement() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34);
        prepareCapture();
        scenario.onActivity(activity -> {
            for (int shift : new int[]{KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT}) {
                TerminalInputConnection connection = (TerminalInputConnection) input;
                int start = connection.documentOffset() + connection.editorComposition().text.length();
                input.commitText("ㅇ", 1);
                assertTrue(input.replaceText(start, start + 1, "이", 1, null));
                long time = android.os.SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, shift, 0,
                        KeyEvent.META_SHIFT_ON, 1, 42, 0, android.view.InputDevice.SOURCE_KEYBOARD);
                assertTrue(activity.view.dispatchKeyEvent(down));
                assertTrue(input.replaceText(start, start + 1, "있", 1, null));
                assertTrue(activity.view.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)));
            }
            enter();
        });
        assertCaptured("있있");
    }

    @Test public void wheelOnTheAlternateScreenStillSendsArrowKeys() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            byte[] mode = "\033[?1049h".getBytes(StandardCharsets.UTF_8);
            activity.session.getEmulator().append(mode, mode.length);
            android.view.MotionEvent.PointerProperties pointer = new android.view.MotionEvent.PointerProperties();
            pointer.id = 0;
            pointer.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE;
            android.view.MotionEvent.PointerCoords point = new android.view.MotionEvent.PointerCoords();
            point.x = 20; point.y = 40;
            point.setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, 1);
            long time = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent scroll = android.view.MotionEvent.obtain(time, time,
                    android.view.MotionEvent.ACTION_SCROLL, 1,
                    new android.view.MotionEvent.PointerProperties[]{pointer},
                    new android.view.MotionEvent.PointerCoords[]{point}, 0, 0, 1f, 1f, 1, 0,
                    android.view.InputDevice.SOURCE_TOUCHPAD, 0);
            try { assertTrue(activity.view.onGenericMotionEvent(scroll)); } finally { scroll.recycle(); }
            enter();
        });
        assertCaptured("\033[A\033[A\033[A");
    }

    @Test public void physicalShiftDuringLegacyPreeditKeepsTheWholeSyllable() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            input.setComposingText("이", 1);
            assertTrue(activity.view.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT,
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT)));
            input.setComposingText("있", 1);
            assertTrue(activity.view.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT,
                    new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT)));
            input.commitText("있", 1);
            enter();
        });
        assertCaptured("있");
    }

    @Test public void hangulEnterAndLateEchoReachTheRealPtyOnce() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            for (String text : Arrays.asList("ㅇ", "아", "안", "안ㄴ", "안녀", "안녕")) input.setComposingText(text, 1);
            enter();
            input.commitText("안녕", 1);
        });
        assertCaptured("안녕");
    }

    @Test public void hangulSpaceAndRepeatedWordPreserveByteOrdering() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            input.setComposingText("한글", 1);
            input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE));
            input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE));
            input.commitText("한글", 1);
            input.setComposingText("한글", 1);
            input.commitText("한글", 1);
            enter();
        });
        assertCaptured("한글 한글");
    }

    @Test public void batchedImeQueriesKeepReleasedSyllablesAndTheNextWord() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            assertTrue(input.beginBatchEdit());
            input.setComposingText("안녕", 1);
            assertEquals("안녕", input.getTextBeforeCursor(100, 0));
            input.commitText("안녕 ", 1);
            assertFalse(input.endBatchEdit());
            assertTrue(input.beginBatchEdit());
            input.setComposingText("한ㄱ", 1);
            assertEquals("안녕 한ㄱ", input.getTextBeforeCursor(100, 0));
            input.setComposingText("한글", 1);
            assertEquals("안녕 한글", input.getTextBeforeCursor(100, 0));
            input.commitText("한글", 1);
            assertFalse(input.endBatchEdit());
            enter();
        });
        assertCaptured("안녕 한글");
    }

    @Test public void betaReplacementsReachTheRealPtyWithoutLosingJamo() throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 34) return;
        prepareCapture();
        scenario.onActivity(activity -> {
            InputConnection discarded = activity.view.onCreateInputConnection(new EditorInfo());
            input.commitText("ㅇ", 1);
            assertTrue(input.replaceText(0, 1, "아", 1, null));
            assertTrue(input.replaceText(0, 1, "안", 1, null));
            input.commitText("ㄴ", 1);
            assertTrue(input.replaceText(1, 2, "녀", 1, null));
            assertTrue(input.replaceText(1, 2, "녕", 1, null));
            input.commitText(" ", 1);
            input.commitText("ㅎ", 1);
            input.replaceText(3, 4, "하", 1, null);
            input.replaceText(3, 4, "한", 1, null);
            input.commitText("ㄱ", 1);
            input.replaceText(4, 5, "그", 1, null);
            input.replaceText(4, 5, "글", 1, null);
            enter();
            discarded.closeConnection();
        });
        assertCaptured("안녕 한글");
    }

    @Test public void japaneseChineseAndSupplementaryInputReachThePtyUnchanged() throws Exception {
        prepareCapture();
        String text = "日本語简体中文繁體中文𠀀e\u0301👩‍💻";
        scenario.onActivity(activity -> {
            input.setComposingText("にほん", 1);
            input.setComposingText("日本語", 1);
            input.commitText(text, 1);
            enter();
        });
        assertCaptured(text);
    }

    @Test public void localBackspaceDoesNotErasePreviouslyCommittedShellText() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            input.commitText("KEEP", 1);
            input.setComposingText("한", 1);
            activity.view.onKeyDown(KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            activity.view.onKeyUp(KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
            enter();
        });
        assertCaptured("KEEP");
    }

    @Test public void initialPrintableInputIsNotDroppedWhenTheViewIsReady() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            activity.view.onKeyDown(KeyEvent.KEYCODE_P, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P));
            activity.view.onKeyUp(KeyEvent.KEYCODE_P, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P));
            input.commitText("rintf", 1);
            enter();
        });
        assertCaptured("printf");
    }

    @Test public void startupDoesNotOfferAnInputConnectionWithoutALiveEmulator() {
        scenario.onActivity(activity -> {
            TerminalView pending = new TerminalView(activity, null);
            pending.setTerminalViewClient(activity);
            assertNull(pending.onCreateInputConnection(new EditorInfo()));
        });
    }

    @Test public void staleConnectionCannotSendIntoTheNextSession() throws Exception {
        prepareCapture();
        scenario.onActivity(activity -> {
            TerminalSession first = activity.session;
            TerminalSession next = new TerminalSession("/system/bin/sh", activity.getFilesDir().getAbsolutePath(),
                    new String[]{"sh"}, new String[]{"PATH=/system/bin", "PS1=NEXT> "}, 2000, activity);
            activity.session = next;
            activity.view.attachSession(next);
            assertFalse(input.commitText("STALE", 1));
            activity.view.attachSession(first);
            activity.session = first;
            next.dispose();
            input = activity.view.onCreateInputConnection(new EditorInfo());
            input.commitText("FRESH", 1);
            enter();
        });
        assertCaptured("FRESH");
    }

    @Test public void viewResizeAndPreeditDrawingKeepNativeTextAndSelection() {
        scenario.onActivity(activity -> {
            TerminalEmulator emulator = activity.session.getEmulator();
            byte[] bytes = "\033[2J\033[Ha枝\u0302b\r\n한日本𠀀".getBytes(StandardCharsets.UTF_8);
            emulator.append(bytes, bytes.length);
            assertEquals("枝\u0302", emulator.getSelectedText(2, 0, 2, 0));
            input.setComposingText("한글👩‍💻", 1);
            Bitmap frame = Bitmap.createBitmap(activity.view.getWidth(), activity.view.getHeight(), Bitmap.Config.ARGB_8888);
            activity.view.draw(new Canvas(frame));
            activity.view.setTextSize(30);
            activity.view.updateSize();
            assertTrue(emulator.getScreen().getTranscriptText().contains("한日本𠀀"));
            activity.view.draw(new Canvas(frame));
            frame.recycle();
        });
    }

    private void assertRedImage(String sequence) {
        scenario.onActivity(activity -> {
            TerminalEmulator emulator = activity.session.getEmulator();
            byte[] bytes = ("\033[2J\033[H" + sequence).getBytes(StandardCharsets.UTF_8);
            emulator.append(bytes, bytes.length);
            TerminalBuffer buffer = emulator.getScreen();
            long style = buffer.getStyleAt(0, 0);
            assertTrue("Image placement was not attached to native cells", TextStyle.isTerminalBitmap(style));
            Bitmap image = buffer.getSixelBitmap(style);
            assertNotNull(image);
            assertEquals(Color.RED, image.getPixel(0, 0));
            Bitmap frame = Bitmap.createBitmap(activity.view.getWidth(), activity.view.getHeight(), Bitmap.Config.ARGB_8888);
            activity.view.draw(new Canvas(frame));
            boolean found = false;
            for (int y = 0; y < Math.min(100, frame.getHeight()); y++) {
                for (int x = 0; x < Math.min(100, frame.getWidth()); x++) found |= frame.getPixel(x, y) == Color.RED;
            }
            frame.recycle();
            assertTrue("The existing renderer did not draw the image", found);
        });
    }

    @Test public void sixelDecodingPlacementAndCanvasRenderingWorkTogether() {
        assertRedImage("\033Pq\"1;1;12;6#1;2;100;0;0!12~\033\\");
    }

    @Test public void itermInlineImageDecodingPlacementAndRenderingWorkTogether() {
        Bitmap source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, encoded));
        source.recycle();
        String image = Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP);
        assertRedImage("\033]1337;File=inline=1;width=16px;height=16px;preserveAspectRatio=0:" + image + "\007");
    }
}
