package com.muoniumplayer.core.screens.ncm;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.utils.timing.Timer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/18 10:55
 */
public class LyricLine {
    @Getter
    public long timestamp;  // 时间戳(ms)

    @Getter
    @Setter
    @NonNull
    public String lyric;  // 歌词文本

    public LyricLine(long timestamp, @NonNull String lyric) {
        this.timestamp = timestamp;
        this.lyric = lyric;
    }

    @Getter
    public String translationText;  // 翻译文本
    @Getter
    public String romanizationText; // 罗马音文本

    public long duration;
    public double posY = 0;
    public double height = 0;
    public float alpha = .4f;
    public float hoveringAlpha = 0f;
    public float blurAlpha = 0f;
    public boolean shouldUpdatePosition = false;
    public double reboundAnimation = 0;
    public Timer delayTimer = new Timer();
    public boolean renderEmphasizes = true;
    public boolean isBreakLine = false;

    /**
     * 对唱组编号，{@code -1} 表示独唱行。由 {@link LyricDuetGroups#mark(java.util.List)} 在歌词
     * 提交时写入一次：同组的行时间轴真正重叠，渲染层要把它们当作同一个当前行整块点亮。
     */
    public int duetGroup = -1;

    /**
     * 行在对唱组内的序号，{@code -1} 表示独唱行。渲染层据此决定这一行排在左列还是右列
     * （偶数序号靠左、奇数序号靠右），从而让同时演唱的两位歌手左右分开显示。
     */
    public int duetSlot = -1;

    /**
     * 这一行属于副歌（高潮段）。
     *
     * <p>两个来源：网易云 {@code /api/song/lyric/v1?cp=true} 返回的 {@code chorus} 区间，
     * 或者没有区间时按「重复次数最多的歌词文本」做的文本频次兜底（见
     * {@code LyricChorusMarker}）。渲染层据此对副歌特化配色与发光强度。</p>
     */
    public boolean isChorus = false;

    /**
     * 副歌段编号，从 0 开始；{@code -1} 表示不是副歌。
     *
     * <p>同一段副歌里的所有行共用一个编号，所以渲染层可以让一整段用同一种强调色，
     * 而不是逐行随机。这个编号同时决定 {@link #chorusEffect}。</p>
     */
    public int chorusIndex = -1;

    /**
     * 副歌特效样式（{@code 0/1/2} 对应 bars / circles / beams）。
     *
     * <p>只有词云一类有独立特效层的样式会读它；平铺渲染的样式只看 {@link #isChorus}。</p>
     */
    public int chorusEffect = -1;

    /**
     * 逐字形时间轴的惰性缓存，由 {@link LyricGraphemeTimeline#of(LyricLine)} 填充。
     *
     * <p>故意不在解析阶段构建：绝大多数行不会走到「按字形」这条路径，提前算等于白花时间。
     * {@link #markDirty()} 会把它一起清掉，所以词表被改写之后不会读到过期数据。</p>
     */
    List<LyricGraphemeTimeline.Glyph> glyphTimeline;

    public final SpringAnimation spring = new SpringAnimation(new SpringParams(.9, 15, 90, false));

    // MusicLyricsWidgets 字段
    public double scrollWidth = 0;
    public double offsetX = 0;
    public double targetOffsetX = 0;
    public float lineAlpha = .4f;

    public double offsetY = Double.MIN_VALUE;

    public final List<Word> words = new CopyOnWriteArrayList<>();

    public boolean hasTimedWords() {
        for (Word word : words) {
            if (word != null && word.duration > 0L) return true;
        }
        return false;
    }

    public static class Word {
        public final String word;
        public final long timestamp, duration;
        public final double[] emphasizes;

        // MusicLyricsWidget 用的字段
        public float alpha = 0.0f;
        public double progress = 0.0;

        /**
         * 这个音节属于背景和声（TTML 的 {@code ttm:role="x-bg"}）。
         *
         * <p>{@link LyricParser#parseTtml} 把和声追加到主行词表末尾，从渲染模型上看它和主歌词
         * 的音节没有区别。Apple Music 的歌词模型里和声是<b>独立于主人声的一路数据</b>
         * （逆向笔记里 {@code line.backgroundVocals} 与 {@code filteredWords} 是两个字段，
         * 连字典都各有一套 copy/resize），所以要复刻它的排版就必须能把这两路分开。</p>
         *
         * <p>默认 {@code false}，只有 TTML 的和声 span 会被标上。现有各套样式都不读这个标记，
         * 行为一字不变；只有「苹果」样式据此把和声单独排成一条小字行。</p>
         */
        public boolean background = false;

        public Word(String word, long timestamp, long duration) {
            this.word = word;
            this.timestamp = timestamp;
            this.duration = duration;
            this.emphasizes = new double[word.length()];
        }

        /** 与播放器毫秒时钟对齐的线性逐字进度。 */
        public double getProgress(double positionMs) {
            if (duration <= 0L) return positionMs >= timestamp ? 1.0 : 0.0;
            double value = (positionMs - timestamp) / (double) duration;
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    private boolean heightComputed = false;

    public void markDirty() {
        heightComputed = false;
        // 词表可能被解析层改写（normalizeWordDurations / addWords），字形时间轴必须一起失效。
        glyphTimeline = null;
    }

    public void computeHeight(double width) {

        if (heightComputed) return;

        CFontRenderer fr = FontManager.pf65bold;

        boolean canSetComputed = true;

        if (!this.words.isEmpty()) {
            double height = fr.getHeight();

            double w = 0;
            for (Word word : words) {

                if (!fr.areGlyphsLoaded(word.word)) {
                    canSetComputed = false;
                }

                double wordWidth = fr.getStringWidthD(word.word);

                if (w + wordWidth > width) {
                    w = wordWidth;
                    height += fr.getHeight() * .85 + 4;
                } else {
                    w += wordWidth;
                }

            }

            this.height = height;
        } else {

            if (!fr.areGlyphsLoaded(lyric)) {
                canSetComputed = false;
            }

            int length = fr.fitWidth(lyric, width).length;
            this.height = length * fr.getHeight() * .85 + length * 4;
        }

        if (translationText != null) {
            if (!fr.areGlyphsLoaded(translationText)) {
                canSetComputed = false;
            }

            CFontRenderer frTranslation = FontManager.pf34bold;
            String[] strings = frTranslation.fitWidth(translationText, width);
            height += frTranslation.getHeight() * strings.length + 4 * (strings.length - 1) + 8;
        }

        heightComputed = canSetComputed;
    }

}
