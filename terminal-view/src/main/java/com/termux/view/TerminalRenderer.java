package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverse = mEmulator.isReverseVideo();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        if (reverse) canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);
        for (int row = topRow; row < topRow + mEmulator.mRows; row++) {
            TerminalRow line = mEmulator.getScreen().getRow(row);
            float y = mFontLineSpacingAndAscent + (row - topRow + 1) * mFontLineSpacing;
            for (int col = 0; col < mEmulator.mColumns;) {
                int width = line.getCellWidth(col);
                if (width == 0) { col++; continue; }
                int start = line.findStartOfColumn(col);
                int end = line.getEndOfColumn(col);
                long style = line.getStyle(col);
                boolean cursor = mEmulator.shouldCursorBeVisible() && row == mEmulator.getCursorRow()
                        && mEmulator.getCursorCol() >= col && mEmulator.getCursorCol() < col + width;
                boolean selected = row >= selectionY1 && row <= selectionY2
                        && (row != selectionY1 || col + width > selectionX1)
                        && (row != selectionY2 || col <= selectionX2);
                if (TextStyle.isTerminalBitmap(style)) {
                    Bitmap bitmap = mEmulator.getScreen().getSixelBitmap(style);
                    Rect src = mEmulator.getScreen().getSixelRect(style);
                    if (bitmap != null && src != null) canvas.drawBitmap(bitmap, src,
                            new RectF(col * mFontWidth, y - mFontLineSpacing,
                                    (col + width) * mFontWidth, y), null);
                } else {
                    float measured = measureCluster(line.mText, start, end, style);
                    drawTextRun(canvas, line.mText, palette, y, col, width, start, end - start,
                            measured, cursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0,
                            mEmulator.getCursorStyle(), style, reverse || selected
                                    || cursor && mEmulator.getCursorStyle() == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);
                }
                col += width;
            }
        }
    }

    private float measureCluster(char[] text, int start, int end, long style) {
        int effect = TextStyle.decodeEffect(style);
        mTextPaint.setTypeface(mTypeface);
        mTextPaint.setFakeBoldText((effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0);
        mTextPaint.setTextSkewX((effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? -0.35f : 0);
        if (Build.VERSION.SDK_INT >= 23 && !mTextPaint.hasGlyph(new String(text, start, end - start))) {
            mTextPaint.setTypeface(Typeface.SANS_SERIF);
        }
        return mTextPaint.measureText(text, start, end - start);
    }

    public void renderPreedit(TerminalEmulator terminal, Canvas canvas, int topRow, CompositionState state) {
        if (state.isEmpty()) return;
        int column = terminal.getInputCursorCol(), row = terminal.getInputCursorRow();
        for (PreeditLayout.Cluster cluster : PreeditLayout.clusters(state.text, terminal.isGraphemeMode())) {
            int width = cluster.cells;
            if (width > 0 && column + width > terminal.mColumns) { column = 0; row++; }
            char[] chars = cluster.text.toCharArray();
            if (row - topRow >= 0 && width > 0) {
                float y = mFontLineSpacingAndAscent + (row - topRow + 1) * mFontLineSpacing;
                long style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
                        TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
                float measured = measureCluster(chars, 0, chars.length, style);
                drawTextRun(canvas, chars, terminal.mColors.mCurrentColors, y, column, width, 0, chars.length,
                        measured, 0, 0, style, terminal.isReverseVideo());
            }
            column += width;
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (mes > 0 && Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
