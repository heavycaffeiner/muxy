package com.termux.view;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TerminalTestActivity extends Activity implements TerminalViewClient, TerminalSessionClient {
    public TerminalView view;
    public TerminalSession session;
    public File capture;
    public final List<String> errors = new ArrayList<>();
    public int singleTapRequests;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            capture = File.createTempFile("pty-capture-", ".txt", getFilesDir());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create PTY capture", error);
        }
        session = new TerminalSession("/system/bin/sh", getFilesDir().getAbsolutePath(), new String[]{"sh"},
                new String[]{"TERM=xterm-256color", "PATH=/system/bin:/system/xbin", "PS1=MX> ",
                        "HOME=" + getFilesDir(), "CAPTURE=" + capture}, 2000, this);
        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        view.setFocusableInTouchMode(true);
        view.setTextSize(24);
        view.setTypeface(Typeface.MONOSPACE);
        setContentView(view);
        view.attachSession(session);
        view.requestFocus();
    }

    @Override protected void onDestroy() {
        if (session != null) session.dispose();
        if (capture != null && capture.exists() && !capture.delete()) {
            errors.add("Cannot delete PTY capture");
        }
        super.onDestroy();
    }

    @Override public void onTextChanged(TerminalSession changed) { view.onScreenUpdated(); }
    @Override public void onTitleChanged(TerminalSession changed) {}
    @Override public void onSessionFinished(TerminalSession finished) { view.onScreenUpdated(); }
    @Override public void onCopyTextToClipboard(TerminalSession changed, String text) {}
    @Override public void onPasteTextFromClipboard(TerminalSession changed) {}
    @Override public void onBell(TerminalSession changed) {}
    @Override public void onColorsChanged(TerminalSession changed) { if (view != null) view.invalidate(); }
    @Override public void onTerminalCursorStateChange(boolean enabled) {}
    @Override public void setTerminalShellPid(TerminalSession changed, int pid) {}
    @Override public Integer getTerminalCursorStyle() { return 0; }
    @Override public float onScale(float scale) { return scale; }
    @Override public void onSingleTapUp(MotionEvent event) {
        singleTapRequests++;
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean selected) {}
    @Override public boolean onKeyDown(int code, KeyEvent event, TerminalSession current) { return false; }
    @Override public boolean onKeyUp(int code, KeyEvent event) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return false; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int cp, boolean ctrl, TerminalSession current) { return false; }
    @Override public void onEmulatorSet() {}
    @Override public void logError(String tag, String message) { errors.add(tag + ": " + message); }
    @Override public void logWarn(String tag, String message) { errors.add(tag + ": " + message); }
    @Override public void logInfo(String tag, String message) {}
    @Override public void logDebug(String tag, String message) {}
    @Override public void logVerbose(String tag, String message) {}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception error) { errors.add(tag + ": " + error.getClass().getName()); }
    @Override public void logStackTrace(String tag, Exception error) { errors.add(tag + ": " + error.getClass().getName()); }
}
