package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricGraphemeTimeline;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.List;

/** 胶囊只跟随本句的演唱时钟向后揭示，停在句尾，不维护循环跑马灯。 */
final class MusicCapsuleLyrics {
    static final double SWITCH_MILLIS = 360.0;
    private LyricLine line;
    private CFontRenderer font;
    private long fontRevision;
    private String text = "";
    private List<LyricGraphemeTimeline.Glyph> timeline;
    private double[] edges = new double[0];
    private double width;
    private boolean timed;

    void prepare(LyricLine nextLine, String nextText, CFontRenderer nextFont) {
        List<LyricGraphemeTimeline.Glyph> nextTimeline = nextLine == null ? null : LyricGraphemeTimeline.of(nextLine);
        long revision = nextFont == null ? 0L : nextFont.metricsRevision();
        if (line == nextLine && text.equals(nextText) && font == nextFont && fontRevision == revision
                && timeline == nextTimeline) return;
        line = nextLine;
        text = nextText;
        font = nextFont;
        fontRevision = revision;
        timeline = nextTimeline;
        width = font == null ? 60.0 : font.getStringWidthD(text);
        timed = line != null && line.hasTimedWords();
        if (!timed || font == null || timeline == null) {
            edges = new double[0];
            return;
        }
        edges = new double[timeline.size()];
        int cursor = 0;
        int leading = 0;
        while (leading < line.lyric.length() && line.lyric.charAt(leading) <= ' ') leading++;
        for (int i = 0; i < timeline.size(); i++) {
            cursor += timeline.get(i).text.length();
            int end = Math.max(0, Math.min(text.length(), cursor - leading));
            edges[i] = font.getStringWidthD(text.substring(0, end));
        }
    }

    double width() {
        return width;
    }

    double offset(double viewport, double positionMillis) {
        if (line == null || width <= viewport || viewport <= 0.0 || !Double.isFinite(positionMillis)) return 0.0;
        if (!timed) {
            // 普通 LRC 只有行时间：单次按行进度揭示，不伪造逐字演唱时间。
            double progress = DynamicIslandMath.clamp01((positionMillis - line.timestamp)
                    / (line.duration > 0L ? line.duration : 2400.0));
            return followOffset(width, viewport, width * progress);
        }
        double revealed = 0.0;
        long end = Long.MIN_VALUE;
        for (int i = 0; i < edges.length; i++) {
            LyricGraphemeTimeline.Glyph glyph = timeline.get(i);
            if (glyph.wordIndex < 0 || glyph.wordIndex >= line.words.size()) continue;
            LyricLine.Word word = line.words.get(glyph.wordIndex);
            if (word == null || word.background) continue;
            end = Math.max(end, glyph.endTimestamp());
            if (positionMillis <= glyph.timestamp) continue;
            double start = i == 0 ? 0.0 : edges[i - 1];
            double progress = ease((positionMillis - glyph.timestamp) / Math.min(160.0, Math.max(1L, glyph.duration)));
            revealed = Math.max(revealed, start + (edges[i] - start) * progress);
        }
        if (end != Long.MIN_VALUE && positionMillis >= end) {
            revealed += (width - revealed) * ease((positionMillis - end) / 160.0);
        }
        return followOffset(width, viewport, revealed);
    }

    static double followOffset(double width, double viewport, double revealed) {
        if (!Double.isFinite(width) || !Double.isFinite(viewport) || !Double.isFinite(revealed)
                || width <= viewport || viewport <= 0.0) return 0.0;
        return DynamicIslandMath.clamp(revealed - viewport, 0.0, width - viewport);
    }

    static double ease(double progress) {
        double t = DynamicIslandMath.clamp01(progress);
        return t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
    }
}
