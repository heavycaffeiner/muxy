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
            assertEquals("한ㄱ", input.getTextBeforeCursor(100, 0));
            input.setComposingText("한글", 1);
            assertEquals("한글", input.getTextBeforeCursor(100, 0));
            input.commitText("한글", 1);
            assertFalse(input.endBatchEdit());
            enter();
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
