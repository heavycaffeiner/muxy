package com.termux.view;

import android.view.KeyEvent;

import com.termux.terminal.NativeKeyMap;
import com.termux.terminal.TerminalText;

import java.util.List;

public final class TerminalImeController {

    public interface Sink {
        void sendText(String text);
        void sendKey(int keyCode);
        void compositionChanged(CompositionState state);
    }

    public enum KeyDecision {
        FORWARD, COMMIT_THEN_FORWARD, BACKSPACE_COMPOSITION
    }

    private static final int MAX_DELETE = 64;
    private static final int MAX_CONTEXT = 4096;
    private final Sink sink;
    private CompositionState composition = CompositionState.EMPTY;
    // Only input sent at the current terminal cursor is editable, never terminal output.
    private String committedText = "";
    private int documentOffset;
    private String releasedText = "";
    private String flushedText;
    private String flushedWord;
    private int batchDepth;
    private boolean compositionDirty;

    public TerminalImeController(Sink sink) {
        if (sink == null) throw new IllegalArgumentException("Missing terminal input sink");
        this.sink = sink;
    }

    public CompositionState composition() {
        return composition;
    }

    public boolean isComposing() {
        return !composition.isEmpty();
    }

    public int documentOffset() {
        return documentOffset;
    }

    public int composingStart() {
        return isComposing() || !releasedText.isEmpty() ? documentOffset + committedText.length() : -1;
    }

    public CompositionState editorComposition() {
        int offset = committedText.length() + releasedText.length();
        return new CompositionState(committedText + releasedText + composition.text,
                offset + composition.selectionStart, offset + composition.selectionEnd);
    }

    public void editorSelection(int start, int end) {
        int offset = documentOffset + committedText.length() + releasedText.length();
        selection((int) Math.max(0, (long) start - offset), (int) Math.max(0, (long) end - offset));
    }

    public boolean composingRegion(int start, int end) {
        String text = committedText + releasedText + composition.text;
        int from = Math.min(start, end) - documentOffset;
        int to = Math.max(start, end) - documentOffset;
        if (!validBoundary(text, from) || !validBoundary(text, to) || to != text.length()) return false;
        // A terminal cannot safely move an arbitrary committed selection into preedit.
        if (from > committedText.length()) return false;
        releasedText = committedText.substring(from) + releasedText;
        committedText = committedText.substring(0, from);
        publish(composition);
        return true;
    }

    public void beginBatch() {
        batchDepth++;
    }

    public void endBatch() {
        if (batchDepth == 0) return;
        batchDepth--;
        if (batchDepth == 0 && compositionDirty) {
            compositionDirty = false;
            sink.compositionChanged(composition);
        }
    }

    private void publish(CompositionState state) {
        composition = state;
        if (batchDepth == 0) sink.compositionChanged(state);
        else compositionDirty = true;
    }

    public void composingText(CharSequence text, int cursorPosition) {
        flushedText = null;
        flushedWord = null;
        CompositionState next = CompositionState.of(text, cursorPosition);
        if (!isComposing() && !next.text.startsWith(releasedText)) releasedText = "";
        List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters(next.text, true);
        int settled = Math.max(0, clusters.size() - 1);
        boolean hangul = true;
        for (int i = 0; i < settled; i++) hangul &= CompositionState.isCompleteHangul(clusters.get(i).text);
        int ready = hangul ? settled : Math.min(clusters.size(), PreeditLayout.clusters(releasedText, true).size());
        StringBuilder target = new StringBuilder();
        for (int i = 0; i < ready; i++) target.append(clusters.get(i).text);
        String targetText = target.toString();
        List<PreeditLayout.Cluster> before = PreeditLayout.clusters(releasedText, true);
        int match = 0;
        while (match < before.size() && match < ready && before.get(match).text.equals(clusters.get(match).text)) match++;
        int stale = before.size() - match;
        int matchedChars = match == 0 ? 0 : clusters.get(match - 1).startUtf16 + clusters.get(match - 1).text.length();
        String added = targetText.substring(matchedChars);
        releasedText = targetText;
        String rest = next.text.substring(targetText.length());
        int caret = Math.max(0, next.selectionEnd - targetText.length());
        publish(rest.isEmpty() ? CompositionState.EMPTY : new CompositionState(rest, caret, caret));
        for (int i = 0; i < stale; i++) sink.sendKey(KeyEvent.KEYCODE_DEL);
        if (!added.isEmpty()) sink.sendText(added);
        if (next.text.isEmpty()) releasedText = "";
    }

    public boolean commitText(CharSequence text) {
        String raw = TerminalText.sanitizeUtf16(text);
        String echo = flushedText;
        String whole = flushedWord;
        flushedText = null;
        flushedWord = null;
        if (echo != null) {
            if (whole != null && !whole.isEmpty() && raw.startsWith(whole)) raw = raw.substring(whole.length());
            else if (raw.startsWith(echo)) raw = raw.substring(echo.length());
        }
        List<PreeditLayout.Cluster> before = PreeditLayout.clusters(releasedText, true);
        List<PreeditLayout.Cluster> after = PreeditLayout.clusters(raw, true);
        int match = 0;
        while (match < before.size() && match < after.size() && before.get(match).text.equals(after.get(match).text)) match++;
        int stale = before.size() - match;
        int prefixLength = match == 0 ? 0 : after.get(match - 1).startUtf16 + after.get(match - 1).text.length();
        releasedText = "";
        remember(raw.substring(0, prefixLength));
        publish(CompositionState.EMPTY);
        for (int i = 0; i < stale; i++) sink.sendKey(KeyEvent.KEYCODE_DEL);
        String remaining = raw.substring(prefixLength);
        if (remaining.isEmpty()) return false;
        sendCommitted(remaining);
        return true;
    }

    public boolean replaceText(int start, int end, CharSequence text, int cursorPosition) {
        if (start < 0 || end < 0) return false;
        String editor = editorComposition().text;
        long fromLong = (long) Math.min(start, end) - documentOffset;
        long toLong = (long) Math.max(start, end) - documentOffset;
        if (fromLong < 0 || toLong > editor.length()) return false;
        int from = (int) fromLong, to = (int) toLong;
        if (!validBoundary(editor, from) || !validBoundary(editor, to)) return false;
        String replacement = TerminalText.sanitizeUtf16(text);
        // Rewrites across a submitted line are not reversible terminal edits.
        if (replacement.indexOf('\n') >= 0 || replacement.indexOf('\r') >= 0) return false;
        String target = editor.substring(0, from) + replacement + editor.substring(to);
        long caret = cursorPosition > 0 ? (long) from + replacement.length() + cursorPosition - 1
                : (long) from + cursorPosition;
        if (Math.max(0, Math.min(target.length(), caret)) != target.length()) return false;
        List<PreeditLayout.Cluster> before = PreeditLayout.clusters(committedText + releasedText, true);
        List<PreeditLayout.Cluster> after = PreeditLayout.clusters(target, true);
        int match = 0;
        while (match < before.size() && match < after.size() && before.get(match).text.equals(after.get(match).text)) match++;
        int deletes = before.size() - match;
        if (deletes > MAX_DELETE) return false;
        int prefix = match == 0 ? 0 : after.get(match - 1).startUtf16 + after.get(match - 1).text.length();
        String added = target.substring(prefix);
        committedText = target;
        releasedText = "";
        flushedText = null;
        flushedWord = null;
        trimContext();
        publish(CompositionState.EMPTY);
        for (int i = 0; i < deletes; i++) sink.sendKey(KeyEvent.KEYCODE_DEL);
        if (!added.isEmpty()) sink.sendText(added);
        return true;
    }

    private static boolean validBoundary(String text, int offset) {
        return offset >= 0 && offset <= text.length() && (offset == 0 || offset == text.length()
                || !Character.isHighSurrogate(text.charAt(offset - 1)) || !Character.isLowSurrogate(text.charAt(offset)));
    }

    private void remember(String text) {
        committedText += text;
        trimContext();
    }

    private void trimContext() {
        if (committedText.length() <= MAX_CONTEXT) return;
        int limit = committedText.length() - MAX_CONTEXT;
        int cut = committedText.length();
        for (PreeditLayout.Cluster cluster : PreeditLayout.clusters(committedText, true)) {
            if (cluster.startUtf16 >= limit) { cut = cluster.startUtf16; break; }
        }
        documentOffset += cut;
        committedText = committedText.substring(cut);
    }

    // A submitted line or terminal navigation ends the suffix that an IME may rewrite.
    public void terminalKeySent(int code) {
        if (code == KeyEvent.KEYCODE_DEL) {
            if (!releasedText.isEmpty()) {
                List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters(releasedText, true);
                releasedText = releasedText.substring(0, clusters.get(clusters.size() - 1).startUtf16);
            } else if (!committedText.isEmpty()) {
                List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters(committedText, true);
                committedText = committedText.substring(0, clusters.get(clusters.size() - 1).startUtf16);
            } else if (documentOffset > 0) {
                // Gboard moves its cached caret even when the terminal has nothing left to erase.
                documentOffset--;
            }
            flushedText = null;
            flushedWord = null;
            publish(composition);
        } else if (code == KeyEvent.KEYCODE_SPACE) {
            remember(" ");
            publish(composition);
        } else {
            documentOffset += committedText.length() + releasedText.length() + composition.text.length();
            committedText = "";
            releasedText = "";
            if (code == KeyEvent.KEYCODE_UNKNOWN) {
                flushedText = null;
                flushedWord = null;
            }
            publish(CompositionState.EMPTY);
        }
    }

    private void sendCommitted(String text) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n' && text.charAt(i) != '\r') continue;
            if (i > start) {
                remember(text.substring(start, i));
                sink.sendText(text.substring(start, i));
            }
            // Preserve the flush echo guard until the IME delivers its pending commit.
            documentOffset += committedText.length();
            committedText = "";
            sink.sendKey(KeyEvent.KEYCODE_ENTER);
            start = i + 1;
        }
        if (start < text.length()) {
            remember(text.substring(start));
            sink.sendText(text.substring(start));
        }
    }

    public void selection(int start, int end) {
        if (isComposing()) publish(new CompositionState(composition.text, start, end));
    }

    public boolean deleteSurrounding(int before, int after, boolean codepoints) {
        if (!isComposing()) return false;
        String text = composition.text;
        int start = Math.min(composition.selectionStart, composition.selectionEnd);
        int end = Math.max(composition.selectionStart, composition.selectionEnd);
        int from;
        int to;
        if (codepoints) {
            int left = Math.min(Math.max(0, before), text.codePointCount(0, start));
            int right = Math.min(Math.max(0, after), text.codePointCount(end, text.length()));
            from = text.offsetByCodePoints(start, -left);
            to = text.offsetByCodePoints(end, right);
        } else {
            from = (int) Math.max(0, (long) start - Math.max(0, before));
            to = (int) Math.min(text.length(), (long) end + Math.max(0, after));
        }
        if (from > 0 && from < text.length() && Character.isLowSurrogate(text.charAt(from))) from--;
        if (to > 0 && to < text.length() && Character.isLowSurrogate(text.charAt(to))) to++;
        String remaining = text.substring(0, from) + text.substring(to);
        publish(remaining.isEmpty() ? CompositionState.EMPTY : new CompositionState(remaining, from, from));
        return true;
    }

    public boolean backspace() {
        if (!isComposing()) return false;
        int start = Math.min(composition.selectionStart, composition.selectionEnd);
        int end = Math.max(composition.selectionStart, composition.selectionEnd);
        if (start != end) return deleteSurrounding(0, 0, false);
        if (start == 0) return true;
        List<PreeditLayout.Cluster> clusters = PreeditLayout.clusters(composition.text, true);
        int from = PreeditLayout.boundaryBefore(clusters, start);
        int to = start;
        for (PreeditLayout.Cluster cluster : clusters) {
            int clusterEnd = cluster.startUtf16 + cluster.text.length();
            if (cluster.startUtf16 < start && start < clusterEnd) to = clusterEnd;
        }
        return deleteSurrounding(start - from, to - start, false);
    }

    public KeyDecision keyDown(int code) {
        if (!isComposing()) return KeyDecision.FORWARD;
        if (code == KeyEvent.KEYCODE_SPACE || (NativeKeyMap.isSpecial(code)
                && code != KeyEvent.KEYCODE_DEL && code != KeyEvent.KEYCODE_ESCAPE)) {
            return KeyDecision.COMMIT_THEN_FORWARD;
        }
        return code == KeyEvent.KEYCODE_DEL ? KeyDecision.BACKSPACE_COMPOSITION : KeyDecision.FORWARD;
    }

    public void flushComposition() {
        if (!isComposing()) return;
        String text = composition.text;
        flushedText = text;
        flushedWord = releasedText + text;
        remember(releasedText + text);
        releasedText = "";
        publish(CompositionState.EMPTY);
        sink.sendText(text);
    }

    public void finishComposing() {
        if (isComposing()) publish(CompositionState.EMPTY);
    }

    public void abandonComposition() {
        remember(releasedText);
        releasedText = "";
        flushedText = null;
        flushedWord = null;
        if (isComposing()) publish(CompositionState.EMPTY);
    }

    public int terminalDeletes(int count) {
        return isComposing() ? 0 : Math.min(MAX_DELETE, Math.max(0, count));
    }

    public void close() {
        committedText = "";
        releasedText = "";
        documentOffset = 0;
        batchDepth = 0;
        compositionDirty = false;
        abandonComposition();
    }
}
