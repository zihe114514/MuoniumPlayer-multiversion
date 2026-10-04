package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import java.util.Arrays;
import java.util.List;

/** Immutable qplayer geometry, independent of playhead, opacity and spring state. */
final class LyricWordPlacement {
    final double[] x, widths;
    final int[] row, first, last;
    final String[][] glyphs;
    final double[][] charAdvances;
    final long[][] charStarts;
    final double columnLeft, columnWidth;
    final boolean rightAligned;
    final CFontRenderer font;
    final long fontRevision, lineRevision;

    private LyricWordPlacement(LyricLine line, CFontRenderer font, double columnLeft,
                               double columnWidth, boolean rightAligned, double[] x,
                               double[] widths, int[] row, int rowCount) {
        this.font = font;
        this.fontRevision = font.metricsRevision();
        this.lineRevision = line.layoutRevision();
        this.columnLeft = columnLeft;
        this.columnWidth = columnWidth;
        this.rightAligned = rightAligned;
        this.x = x;
        this.widths = widths;
        this.row = row;
        first = new int[rowCount];
        last = new int[rowCount];
        Arrays.fill(first, -1);
        Arrays.fill(last, -1);
        glyphs = new String[x.length][];
        charAdvances = new double[x.length][];
        charStarts = new long[x.length][];
        for (int i = 0; i < x.length; i++) {
            if (first[row[i]] < 0) first[row[i]] = i;
            last[row[i]] = i;
            String text = line.words.get(i).word;
            glyphs[i] = new String[text.length()];
            charAdvances[i] = new double[text.length()];
            charStarts[i] = new long[text.length()];
            for (int c = 0; c < text.length(); c++) {
                // Intentionally keep the existing UTF-16/kerning semantics of the qplayer renderer.
                char glyph = text.charAt(c);
                glyphs[i][c] = String.valueOf(glyph);
                charAdvances[i][c] = font.getCharWidth(glyph, c + 1 < text.length() ? text.charAt(c + 1) : '\0');
                font.prepareText(glyphs[i][c]);
            }
        }
        // Index the line's cached grapheme timeline once. The old renderer scanned the full
        // timeline once per word per frame and allocated a capturing lambda for every word.
        int[] written = new int[x.length];
        for (LyricGraphemeTimeline.Glyph glyph : LyricGraphemeTimeline.of(line)) {
            int word = glyph.wordIndex;
            if (word < 0 || word >= x.length) continue;
            for (int c = 0; c < glyph.text.length() && written[word] < charStarts[word].length; c++) {
                charStarts[word][written[word]++] = glyph.timestamp;
            }
        }
        for (int i = 0; i < x.length; i++) {
            long tail = written[i] == 0 ? line.words.get(i).timestamp : charStarts[i][written[i] - 1];
            Arrays.fill(charStarts[i], written[i], charStarts[i].length, tail);
        }
    }

    boolean matches(LyricLine line, CFontRenderer font, double left, double width, boolean right) {
        return this.font == font && fontRevision == font.metricsRevision()
                && lineRevision == line.layoutRevision() && x.length == line.words.size()
                && rightAligned == right && Math.abs(columnLeft - left) <= .5
                && Math.abs(columnWidth - width) <= .5;
    }

    static LyricWordPlacement build(LyricLine line, CFontRenderer font, double left,
                                    double width, boolean rightAligned) {
        List<LyricLine.Word> words = line.words;
        int count = words.size();
        double[] widths = new double[count];
        int[] rows = new int[count];
        double[] rowWidths = new double[count + 1];
        int row = 0;
        double cursor = 0;
        for (int i = 0; i < count; i++) {
            widths[i] = font.getStringWidthD(words.get(i).word);
            if (cursor > 0 && cursor + widths[i] > width) {
                rowWidths[row++] = cursor;
                cursor = 0;
            }
            rows[i] = row;
            cursor += widths[i];
        }
        rowWidths[row] = cursor;
        double[] xs = new double[count];
        double[] rowCursor = new double[row + 1];
        for (int i = 0; i <= row; i++) {
            rowCursor[i] = rightAligned ? left + width - Math.min(rowWidths[i], width) : left;
        }
        for (int i = 0; i < count; i++) {
            xs[i] = rowCursor[rows[i]];
            rowCursor[rows[i]] += widths[i];
        }
        return new LyricWordPlacement(line, font, left, width, rightAligned, xs, widths, rows, row + 1);
    }
}
