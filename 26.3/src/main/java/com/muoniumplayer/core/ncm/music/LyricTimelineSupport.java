package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.screens.ncm.LyricDuetGroups;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 已解析歌词时间线的纯构造与查找规则。
 *
 * <p>它不访问播放、渲染或 GUI 状态。{@link CloudMusic}
 * 仍是兼容外观，并把准备好的结果发布到其
 * 既有的线程安全歌词列表。</p>
 */
final class LyricTimelineSupport {

    private static final long LONG_BREAK_DURATION_MILLIS = 3000L;
    private static final float JUMP_TO_NEXT_MILLIS = 300.0f;

    /** 间奏行的文本，与 Folia 的 {@code INTERLUDE_FULL_TEXT} 一致。 */
    static final String INTERLUDE_TEXT = "......";
    /** 间奏行的点数。每个点各占整段间奏的 1/6，所以它们是依次出现的。 */
    private static final int INTERLUDE_DOTS = 6;

    private LyricTimelineSupport() {
    }

    static PreparedTimeline prepare(List<LyricLine> parsedLyrics) {
        List<LyricLine> timeline = new ArrayList<>();
        timeline.addAll(parsedLyrics);
        if (timeline.isEmpty()) {
            timeline.add(new LyricLine(0L, "暂无歌词"));
        }

        boolean haveNoWords = hasNoWords(timeline);
        addLongBreaks(timeline, haveNoWords);
        // 重叠行只有在时间线最终确定后才有意义：上面插入的断行
        //参与排序，一个对唱组绝不能横跨一条断行。
        LyricDuetGroups.mark(timeline);
        return new PreparedTimeline(timeline, haveNoWords);
    }

    static LyricLine findCurrentLyric(List<LyricLine> timeline, boolean haveNoWords, double songProgress) {
        for (int index = 0; index < timeline.size(); index++) {
            LyricLine lyric = timeline.get(index);
            LyricLine previous = index > 0 ? timeline.get(index - 1) : null;

            if (!haveNoWords
                    && !lyric.isBreakLine
                    && lyric.getTimestamp() > songProgress
                    && lyric.getTimestamp() - songProgress <= JUMP_TO_NEXT_MILLIS
                    && canJumpToNextEarly(previous)) {
                return lyric;
            }

            if (lyric.getTimestamp() > songProgress) {
                return index > 0 ? timeline.get(index - 1) : timeline.get(0);
            }

            if (index == timeline.size() - 1) {
                return lyric;
            }
        }
        return timeline.isEmpty() ? null : timeline.get(0);
    }

    private static boolean hasNoWords(List<LyricLine> timeline) {
        for (LyricLine lyric : timeline) {
            if (!lyric.words.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void addLongBreaks(List<LyricLine> timeline, boolean haveNoWords) {
        if (haveNoWords) {
            addInitialBreakIfNeeded(timeline, LONG_BREAK_DURATION_MILLIS);
            return;
        }
        addBreaksBetweenLyrics(timeline, LONG_BREAK_DURATION_MILLIS);
    }

    private static void addInitialBreakIfNeeded(List<LyricLine> timeline, long duration) {
        long firstTimestamp = timeline.get(0).getTimestamp();
        if (firstTimestamp >= duration) {
            timeline.add(createBreakLine(0L, firstTimestamp));
            timeline.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        }
    }

    private static void addBreaksBetweenLyrics(List<LyricLine> timeline, long duration) {
        long lastTimestamp = 0L;
        List<LyricLine> breaksToAdd = new ArrayList<>();
        for (LyricLine line : timeline) {
            long lineDuration = line.duration;
            long gap = line.getTimestamp() - lastTimestamp;
            if (gap >= duration) {
                breaksToAdd.add(createBreakLine(lastTimestamp, gap));
            }
            lastTimestamp = line.getTimestamp() + lineDuration;
        }
        timeline.addAll(breaksToAdd);
        timeline.sort(Comparator.comparingLong(LyricLine::getTimestamp));
    }

    /**
     * 间奏行。
     *
     * <p>对应 Folia 的 {@code attachInterludes}。这里刻意把它做成<b>六个各自计时的点</b>而不是一个
     * 覆盖整段间奏的长音节：音节时间是渲染层唯一的插值来源，一个长音节只能让整行一起亮/一起灭，
     * 六个点则能一个接一个出现，这正是「节奏行」想要的效果。</p>
     *
     * <p>注意 qplayer 那套样式并不画这行文本——它用 {@code renderQplayerInterludeDots} 依据行间
     * 空隙自己画三个点的渐显，"● ● ●" 对它是分组边界标记。这里补齐逐点时间是为了让<b>读词表</b>的
     * 渲染路径（HUD 单行模式、苹果等待点之后的行）也能得到同样的逐点节奏，而不是拿到一个
     * 起步就到位的静态字符串。</p>
     */
    private static LyricLine createBreakLine(long timestamp, long duration) {
        LyricLine line = new LyricLine(timestamp, INTERLUDE_TEXT);
        line.isBreakLine = true;
        long span = Math.max(0L, duration);
        for (int index = 0; index < INTERLUDE_DOTS; index++) {
            long dotStart = timestamp + span * index / INTERLUDE_DOTS;
            long dotEnd = index == INTERLUDE_DOTS - 1
                    ? timestamp + span : timestamp + span * (index + 1) / INTERLUDE_DOTS;
            line.words.add(new LyricLine.Word(".", dotStart, Math.max(0L, dotEnd - dotStart)));
        }
        return line;
    }

    private static boolean canJumpToNextEarly(LyricLine lyric) {
        return lyric != null && !lyric.words.isEmpty() && lyric.duration >= JUMP_TO_NEXT_MILLIS;
    }

    static final class PreparedTimeline {
        final List<LyricLine> lines;
        final boolean haveNoWords;

        private PreparedTimeline(List<LyricLine> lines, boolean haveNoWords) {
            this.lines = lines;
            this.haveNoWords = haveNoWords;
        }
    }
}
