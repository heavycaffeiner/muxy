package com.termux.terminal;

import java.nio.charset.StandardCharsets;

final class TerminalImageDecoder {
    private final TerminalEmulator terminal;
    private TerminalBuffer sixel;
    private int command;
    private int repeat = 1;
    private final StringBuilder numbers = new StringBuilder();
    private ITermImage multipart;

    TerminalImageDecoder(TerminalEmulator terminal) { this.terminal = terminal; }

    void clear() {
        if (sixel != null) sixel.sixelClear();
        sixel = null; command = 0; repeat = 1; numbers.setLength(0); multipart = null;
    }

    void event(int kind, byte[] data) {
        switch (kind) {
            case 1:
                if (sixel != null) sixel.sixelClear();
                sixel = terminal.getScreen(); sixel.sixelStart(100, 100);
                command = 0; repeat = 1; numbers.setLength(0);
                break;
            case 2:
                if (sixel != null) for (byte value : data) consume(value & 255);
                break;
            case 3:
                if (sixel == null) break;
                finishCommand();
                int lines = sixel.sixelEnd(terminal.getCursorCol(), terminal.getCursorRow(),
                        terminal.getCellWidthPixels(), terminal.getCellHeightPixels());
                sixel = null;
                if (lines > 0) terminal.imageAdvance(lines, terminal.getCursorCol());
                break;
            case 4: clear(); break;
            case 5: iterm(new StringBuilder(new String(data, StandardCharsets.UTF_8))); break;
            default: throw new IllegalArgumentException("Invalid image event");
        }
    }

    private void consume(int c) {
        if (command != 0 && (c >= '0' && c <= '9' || c == ';')) {
            if (numbers.length() >= 64) { reject(); return; }
            numbers.append((char)c); return;
        }
        if (command != 0) finishCommand();
        if (sixel == null) return;
        if (c == '#' || c == '!' || c == '"') { command = c; numbers.setLength(0); return; }
        if (c >= '?' && c <= '~' || c == '$' || c == '-') {
            sixel.sixelReadData(c, repeat); repeat = 1;
        } else if (c != ' ' && c != '\t' && c != '\r' && c != '\n' && c != '\f') reject();
    }

    private void reject() {
        if (sixel != null) sixel.sixelIgnore();
        sixel = null; command = 0; numbers.setLength(0);
    }

    private void finishCommand() {
        if (command == 0 || sixel == null) return;
        int code = command; command = 0;
        String[] parts = numbers.toString().split(";", -1);
        numbers.setLength(0);
        int[] args = new int[parts.length];
        try {
            for (int i = 0; i < args.length; i++) args[i] = Integer.parseInt(parts[i]);
            if (code == '!') {
                if (args.length != 1 || args[0] < 0 || args[0] > TerminalSixel.SIXEL__MAX_REPEAT) { reject(); return; }
                repeat = Math.max(1, args[0]);
            } else if (code == '#') {
                if (args[0] < 0 || args[0] > 255) { reject(); return; }
                if (args.length == 1) sixel.sixelSetColor(args[0]);
                else if (args.length == 5 && args[1] == 2 && args[2] >= 0 && args[2] <= 100
                        && args[3] >= 0 && args[3] <= 100 && args[4] >= 0 && args[4] <= 100) {
                    sixel.sixelSetRGBColor(args[0], args[2], args[3], args[4]);
                } else reject();
            } else if (args.length == 4 && args[2] > 0 && args[3] > 0
                    && (long)args[2] * args[3] * 4 <= TerminalBitmap.MAX_BITMAP_SIZE) {
                sixel.sixelResize(args[2], args[3]);
            } else reject();
        } catch (NumberFormatException error) { reject(); }
    }

    private void iterm(StringBuilder args) {
        if (!args.toString().startsWith("1337;")) return;
        ITermImage image = null;
        if (args.indexOf("1337;File=") == 0) {
            multipart = null;
            image = new ITermImage(terminal.getScreen().getClient(), false);
            int start = image.readArguments(terminal, args, 10);
            if (start < 10 || !image.readImage(args, start) || !image.decodeImage()) return;
        } else if (args.indexOf("1337;MultipartFile=") == 0) {
            multipart = new ITermImage(terminal.getScreen().getClient(), true);
            if (multipart.readArguments(terminal, args, 19) < 19) multipart = null;
        } else if (args.indexOf("1337;FilePart=") == 0) {
            if (multipart != null && !multipart.readImage(args, 14)) multipart = null;
        } else if (args.toString().equals("1337;FileEnd")) {
            image = multipart; multipart = null;
            if (image == null || !image.setMultiPartImageRead() || !image.decodeImage()) return;
        } else if (args.indexOf("1337;ReportCellSize") == 0) {
            terminal.reportCellSize(); multipart = null;
        } else multipart = null;
        if (image != null && image.isImageDecoded() && image.isInline()) {
            int[] delta = terminal.getScreen().addTerminalBitmapForImage(image.getDecodedImage(),
                    terminal.getCursorCol(), terminal.getCursorRow(), terminal.getCellWidthPixels(),
                    terminal.getCellHeightPixels(), image.getWidth(), image.getHeight(), image.shouldPreserveAspectRatio());
            if (delta[0] == 0 && delta[1] == 0) return;
            int col = terminal.getCursorCol() + delta[1];
            if (col < terminal.getColumns() - 1) delta[0]--; else col = 0;
            terminal.imageAdvance(Math.max(0, delta[0]), col);
        }
    }
}
