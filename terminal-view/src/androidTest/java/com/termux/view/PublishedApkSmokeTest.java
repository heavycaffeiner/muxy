package com.termux.view;

import android.app.UiAutomation;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.MessageDigest;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class PublishedApkSmokeTest {
    private UiAutomation automation;

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream stream =
                     new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            byte[] buffer = new byte[4096];
            while (stream.read(buffer) != -1) {}
        }
    }

    private String text(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder value = new StringBuilder();
        if (node.getText() != null) value.append(node.getText()).append('\n');
        if (node.getContentDescription() != null) value.append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) value.append(text(node.getChild(i)));
        node.recycle();
        return value.toString();
    }

    private void await(String expected) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Pattern line = expected.equals("$") ? Pattern.compile("(?m)^(?:~ )?\\$ *$")
                : Pattern.compile("(?m)^" + Pattern.quote(expected) + "$");
        while (true) {
            String observed = text(automation.getRootInActiveWindow());
            if (line.matcher(observed).find()) return;
            if (System.nanoTime() >= deadline) fail("Missing signed-app output: " + expected + "; observed: " + observed);
            Thread.sleep(100);
        }
    }

    private boolean keyboardVisible() {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        }
        return false;
    }

    private void awaitKeyboard(boolean visible) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (keyboardVisible() != visible) {
            if (System.nanoTime() >= deadline) fail("Keyboard visibility did not become " + visible);
            Thread.sleep(100);
        }
    }

    private void tap(float x, float y, int source) {
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(source);
            try { assertTrue(automation.injectInputEvent(event, true)); } finally { event.recycle(); }
        }
    }

    private void type(String value) {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray());
        assertNotNull("Cannot encode test command", events);
        for (KeyEvent event : events) assertTrue(automation.injectInputEvent(event, true));
    }

    private void key(int code) {
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            assertTrue(automation.injectInputEvent(new KeyEvent(time, SystemClock.uptimeMillis(), action,
                    code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true));
        }
    }

    private void enter() {
        key(KeyEvent.KEYCODE_ENTER);
    }

    private boolean click(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        try {
            if (label.contentEquals(node.getText() == null ? "" : node.getText())) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                assertFalse(bounds.isEmpty());
                long time = SystemClock.uptimeMillis();
                for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0);
                    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    try {
                        assertTrue(automation.injectInputEvent(event, true));
                    } finally {
                        event.recycle();
                    }
                }
                return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) if (click(node.getChild(i), label)) return true;
            return false;
        } finally {
            node.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    @Before public void setUp() throws Exception {
        String expected = InstrumentationRegistry.getArguments().getString("expected_certificate");
        assumeTrue("Pass expected_certificate to test the installed signed release APK", expected != null);
        assertTrue(expected.matches("[a-f0-9]{64}"));
        PackageManager manager = InstrumentationRegistry.getInstrumentation().getContext().getPackageManager();
        PackageInfo info = manager.getPackageInfo("com.termux", PackageManager.GET_SIGNATURES);
        String version = InstrumentationRegistry.getArguments().getString("expected_version", "0.119.5");
        assertEquals(version, info.versionName);
        assertEquals(1, info.signatures.length);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray());
        StringBuilder certificate = new StringBuilder();
        for (byte value : digest) certificate.append(String.format("%02x", value & 255));
        assertEquals(expected, certificate.toString());
        assertEquals(0, info.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE);
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        shell("am force-stop com.termux");
        shell("am start -n com.termux/com.termux.app.TermuxActivity");
        await("$");
    }

    @After public void restoreRotation() {
        if (automation != null) automation.setRotation(UiAutomation.ROTATION_UNFREEZE);
    }

    @Test public void alternateScreenTouchReopensTheKeyboardWhileMouseInputRemainsEnabled() throws Exception {
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int previousFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try {
            for (int mode : new int[]{0, 1000, 1002, 1003}) {
                String enableMouse = mode == 0 ? "" : "\\033[?" + mode + "h\\033[?1006h";
                String ready = "ALT_KEYBOARD_READY_" + mode;
                type("printf '\\033[?1049h" + enableMouse + ready + "\\r\\n'; "
                        + "IFS= read -r value; printf '\\033[?1000l\\033[?1002l\\033[?1003l\\033[?1006l\\033[?1049l'");
                enter();
                await(ready);
                awaitKeyboard(true);
                key(KeyEvent.KEYCODE_BACK);
                awaitKeyboard(false);
                tap(100, 300, InputDevice.SOURCE_MOUSE);
                Thread.sleep(500);
                assertFalse("A physical mouse tap opened the keyboard in mode " + mode, keyboardVisible());
                tap(100, 300, InputDevice.SOURCE_TOUCHSCREEN);
                awaitKeyboard(true);
                enter();
                await("$");
                assertTrue("Exiting alternate screen lost keyboard access in mode " + mode, keyboardVisible());
            }
        } finally {
            info.flags = previousFlags;
            automation.setServiceInfo(info);
        }
    }

    @Test public void liveGboardTypingOnTheSignedApp() throws Exception {
        String expected = InstrumentationRegistry.getArguments().getString("gboardExpected");
        assumeTrue("Pass gboardExpected to drive actual keyboard taps", expected != null);
        type("echo SIGNED_GBOARD_READY; IFS= read -r value; printf 'SIGNED_BYTES:'; "
                + "printf '%s' \"$value\" | od -An -tx1 | tr -d ' \\n'; printf '\\n'");
        enter();
        await("SIGNED_GBOARD_READY");
        android.util.Log.i("GboardLiveTest", "READY muxy-signed");
        StringBuilder hex = new StringBuilder("SIGNED_BYTES:");
        for (byte value : expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", value & 255));
        }
        await(hex.toString());
    }

    @Test public void signedAppExecutesCommandsAndRetainsItsShellAcrossRotation() throws Exception {
        type("MUXY_ROTATION_VALUE=SIGNED_SESSION_RETAINED; echo SIGNED_BOOT_OK");
        enter();
        await("SIGNED_BOOT_OK");
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90));
        automation.waitForIdle(200, 5000);
        type("echo \"$MUXY_ROTATION_VALUE\"");
        enter();
        await("SIGNED_SESSION_RETAINED");
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        automation.waitForIdle(200, 5000);
        type("echo SIGNED_RETURN_OK");
        enter();
        await("SIGNED_RETURN_OK");
    }

    @Test public void extraArrowKeysAndForwardDeleteEditTheShellCommand() throws Exception {
        type("echo EXTRA_KEYS_OX");
        assertTrue(click(automation.getRootInActiveWindow(), "←"));
        type("K");
        key(KeyEvent.KEYCODE_FORWARD_DEL);
        enter();
        await("EXTRA_KEYS_OK");
    }
}
