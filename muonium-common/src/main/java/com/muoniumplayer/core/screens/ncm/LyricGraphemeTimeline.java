package com.muoniumplayer.core.screens.ncm;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行内逐字形（grapheme）时间轴。上游参考实现的同名能力在本仓库的 Java 移植。
 *
 * <h3>为什么需要它</h3>
 * <p>解析层给出的最小单位是<b>音节</b>（{@link LyricLine.Word}）。一个音节在中文里是一个词、
 * 在日文里是一段假名、在英文里是一个单词，所以「按音节闪」在长音节上会明显分层——渲染层想把
 * 发光/扫光做到单字甚至单字符，就必须自己把音节时间摊到字形上。</p>
 *
 * <p>这件事在「苹果」全屏样式里已经做过一次（{@code FullscreenLyricStage.graphemeProgress}），
 * 但它长在那一个渲染器上，其它样式用不到。这里是同一个算法的<b>共享实现</b>：字形切分、
 * 音节内按字形均摊、以及把音节时间<b>反向映射回整行</b>（补齐词表里没有的空格与标点）。</p>
 *
 * <h3>为什么用 {@link BreakIterator}</h3>
 * <p>上游用 {@code Intl.Segmenter} 按 Unicode 扩展字形簇切分，{@code BreakIterator} 的
 * {@code getCharacterInstance()} 正是 Java 的对应物（都按 UAX #29）。手写「码点 + 组合记号」
 * 会漏掉 emoji ZWJ 序列、区域指示符、韩文音节簇，而这些恰恰是歌词里会出现的字符。</p>
 *
 * <p><b>一个已知的、不必修的版本差异</b>：{@code BreakIterator} 的切分结果取决于<b>运行时 JDK
 * 的 Unicode 数据版本</b>，不是编译目标能定的。实测同样一份输入，JDK 21 / 25 会把
 * emoji ZWJ 序列（如家庭 emoji）合成 1 个字形，而 JDK 17 会切成 5 个（每个 emoji 与每个
 * 零宽连接符各算一个）。本仓库四个版本里只有 {@code 1.20.1 recode} 跑在 Java 17 上，
 * 所以那个版本上的 emoji 家庭会被拆成几个字形——纯观感，不影响中日韩文与拉丁文，
 * 也不影响时间摊分（每个字形仍然拿到正确的时间窗）。不要为此去手写一套切分器。</p>
 *
 * <h3>它与 {@code hasWordByWordTiming} 的关系</h3>
 * <p>字形时间轴<b>不写进</b> {@link LyricLine#words}，所以它不会让一段普通 LRC 被误判成逐字歌词
 * （{@code hasWordByWordTiming} 只看 {@code words}）。没有音节时间的行照样能拿到字形时间轴，
 * 代价是那一行内部按整行时长均匀摊开——这正是 Folia 的行为，调用方按需自行决定要不要用。</p>
 */
public final class LyricGraphemeTimeline {

    /**
     * 一个字形的绝对时间窗。
     *
     * <p>{@code timestamp + duration} 是它的结束时刻；{@code duration == 0} 表示这是一个
     * 「没有自己时间的空隙」（行首缩进、词间的空格、词表里没有的标点），渲染层应把它当作
     * 「跟随前一个字形」处理，而不是当成零长闪烁。</p>
     */
    public static final class Glyph {
        public final String text;
        public final long timestamp;
        public final long duration;
        /** 这个字形所属的音节下标；{@code -1} 表示它落在音节之间、不属于任何一个词。 */
        public final int wordIndex;

        private Glyph(String text, long timestamp, long duration, int wordIndex) {
            this.text = text;
            this.timestamp = timestamp;
            this.duration = duration;
            this.wordIndex = wordIndex;
        }

        public long endTimestamp() {
            return timestamp + duration;
        }
    }

    /**
     * {@link BreakIterator} 不是线程安全的，而解析在后台线程、渲染在渲染线程，所以按线程各持一份。
     * 每次 {@code getCharacterInstance()} 都要新建一份 ICU 级的分词器，逐行建字形表时开销明显。
     */
    private static final ThreadLocal<BreakIterator> SEGMENTER =
            new ThreadLocal<BreakIterator>() {
                @Override
                protected BreakIterator initialValue() {
                    return BreakIterator.getCharacterInstance();
                }
            };

    private LyricGraphemeTimeline() {
    }

    /** 按 Unicode 字形簇切分。切分器不可用时退回「码点 + 组合记号」的手写实现，绝不返回 null。 */
    public static List<String> graphemes(String text) {
        List<String> result = new ArrayList<String>();
        if (text == null || text.isEmpty()) return result;

        try {
            BreakIterator iterator = SEGMENTER.get();
            iterator.setText(text);
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                result.add(text.substring(start, end));
            }
            if (!result.isEmpty()) return result;
        } catch (Throwable ignored) {
            // 落到下面的手写实现。
        }
        return graphemesWithoutSegmenter(text);
    }

    /**
     * 行字形时间轴（带缓存）。
     *
     * <p>结果缓存在 {@link LyricLine} 上，由 {@link LyricLine#markDirty()} 失效——渲染层每帧都会调，
     * 不能每帧重建。</p>
     */
    public static List<Glyph> of(LyricLine line) {
        if (line == null) return Collections.emptyList();
        List<Glyph> cached = line.glyphTimeline;
        if (cached != null) return cached;

        List<Glyph> built = build(line);
        line.glyphTimeline = built;
        return built;
    }

    /**
     * 某个音节在整行字形序列里覆盖的区间，返回 {@code [start, end)}；找不到时返回 {@code null}。
     *
     * <p>用词表顺序去整行字形序列里做子序列查找，所以同一句里重复出现的词不会互相抢位置。</p>
     *
     * <p>越界判定必须发生在记入 {@code end} <b>之前</b>：先记后判会把「第一个落在音节窗口外的
     * 字形」也算进区间，返回一个右端多一格的 {@code [start, end]}。每个音节都多带上后一个字，
     * 逐字扫光的收尾就会整体晚一格。</p>
     */
    public static int[] glyphRangeOf(LyricLine line, LyricLine.Word word) {
        List<Glyph> glyphs = of(line);
        if (glyphs.isEmpty() || word == null) return null;
        if (word.word == null || word.word.isEmpty()) return null;

        // 词首字形在循环外取一次：循环里每次都调 graphemes() 等于每个字形都重新分一次词。
        List<String> wordGlyphs = graphemes(word.word);
        if (wordGlyphs.isEmpty()) return null;
        String head = wordGlyphs.get(0);
        long wordEnd = word.timestamp + Math.max(0L, word.duration);

        int start = -1;
        int end = -1;
        for (int index = 0; index < glyphs.size(); index++) {
            Glyph glyph = glyphs.get(index);
            if (glyph.text == null) continue;
            if (start >= 0 && glyph.timestamp > wordEnd) break;
            if (start < 0 && glyph.text.equals(head)) {
                // 用词首字形定位:字形文本是子串关系,并且必须真的属于这个词的首字形。
                start = index;
            }
            if (start >= 0) end = index;
        }
        if (start < 0) return null;
        return new int[]{start, end + 1};
    }

    /** 整行字形数。与 {@link #graphemes(String)} 的口径一致。 */
    public static int count(String text) {
        return graphemes(text).size();
    }

    /**
     * 逐字起始时刻，按 {@code charIndex} 展开成与 {@link String#toCharArray()} 等长的数组。
     *
     * <p>渲染层是按 UTF-16 单元逐 char 画的（{@code word.word.toCharArray()}），而字形可能由
     * 多个 char 组成（代理对、组合记号）。这里把字形时间摊回每个 char，让调用方能直接用
     * {@code charIndex} 取到时刻，不必自己处理字形到 char 的下标换算。</p>
     *
     * <p>建议在从「按音节」升级到「按字形」的地方使用：把
     * {@code Arrays.fill(word.emphasizes, lift(word.timestamp))} 换成
     * {@code charStarts = charStartMillis(...)} 之后逐位求 lift，纹波就细到一个字了。</p>
     */
    public static long[] charStartMillis(LyricLine line, LyricLine.Word word) {
        if (word == null || word.word == null || word.word.isEmpty()) return new long[0];
        int charCount = word.word.length();
        long[] result = new long[charCount];

        List<String> wordGlyphs = graphemes(word.word);
        if (wordGlyphs.isEmpty()) {
            java.util.Arrays.fill(result, word.timestamp);
            return result;
        }

        // 音节内按字形均摊:Folia 的 buildWordGraphemeTimings 在没有更深一层音节表时就是这个行为。
        long span = Math.max(0L, word.duration);
        int charCursor = 0;
        for (int index = 0; index < wordGlyphs.size(); index++) {
            String glyph = wordGlyphs.get(index);
            long glyphStart = word.timestamp + span * index / wordGlyphs.size();
            for (int offset = 0; offset < glyph.length() && charCursor < charCount; offset++) {
                result[charCursor++] = glyphStart;
            }
        }
        while (charCursor < charCount) result[charCursor++] = word.timestamp + span;
        return result;
    }

    /**
     * 逐字填提升量：把每个 char 的「提升」写进 {@code out}，<b>不分配任何对象</b>。
     *
     * <p>渲染层每帧、每个词都要算一次逐字提升，所以这里刻意不返回数组而是就地填
     * {@link LyricLine.Word#emphasizes}。字形时间取自整行那份被缓存的字形表，每帧只有一次
     * 哈希式的顺序遍历，没有 {@link BreakIterator} 调用。</p>
     *
     * @param line      整行（字形表按行缓存）
     * @param wordIndex 这一行词表里该词的下标
     * @param out       目标数组，长度按 {@code word.word.length()} 的 char 数
     * @param lift      把「起始毫秒」映射成提升量的函数，由渲染层提供——本类不拥有任何动画曲线
     * @param fallback  这一行没有字形数据时整个数组填的值（通常按词首时刻算出的提升量）
     */
    public static void fillCharLifts(LyricLine line, int wordIndex, double[] out,
                                     java.util.function.DoubleUnaryOperator lift, double fallback) {
        if (out == null || out.length == 0) return;
        if (line == null || lift == null) {
            java.util.Arrays.fill(out, fallback);
            return;
        }

        List<Glyph> glyphs = of(line);
        if (glyphs.isEmpty()) {
            java.util.Arrays.fill(out, fallback);
            return;
        }

        int cursor = 0;
        for (int index = 0; index < glyphs.size() && cursor < out.length; index++) {
            Glyph glyph = glyphs.get(index);
            if (glyph.wordIndex != wordIndex) continue;
            double value = lift.applyAsDouble(glyph.timestamp);
            int length = Math.min(glyph.text.length(), out.length - cursor);
            for (int offset = 0; offset < length; offset++) out[cursor++] = value;
        }
        // 尾部（理论上不该出现）沿用最后一个值，而不是留在上一帧的旧值上。
        if (cursor > 0) {
            double tail = out[cursor - 1];
            while (cursor < out.length) out[cursor++] = tail;
        } else {
            java.util.Arrays.fill(out, fallback);
        }
    }

    // ── 构建 ────────────────────────────────────────────────────────────────

    private static List<Glyph> build(LyricLine line) {
        List<String> lineGlyphs = graphemes(line.lyric);
        List<Glyph> timeline = new ArrayList<Glyph>(lineGlyphs.size());
        if (lineGlyphs.isEmpty()) return timeline;

        long lineEnd = line.timestamp + Math.max(0L, line.duration);
        if (line.words.isEmpty()) {
            // 整行没有音节时间:按整行时长均匀摊开。duration 为 0 时所有字形的窗口都是零长,
            // 调用方据此可以判定"这一行没有可用的字形时间"。
            long span = Math.max(0L, line.duration);
            for (int index = 0; index < lineGlyphs.size(); index++) {
                long start = line.timestamp + span * index / lineGlyphs.size();
                long end = index == lineGlyphs.size() - 1
                        ? Math.max(lineEnd, start) : line.timestamp + span * (index + 1) / lineGlyphs.size();
                timeline.add(new Glyph(lineGlyphs.get(index), start, Math.max(0L, end - start), -1));
            }
            return timeline;
        }

        Glyph[] slots = new Glyph[lineGlyphs.size()];
        int cursor = 0;
        long lastResolved = line.timestamp;

        for (int wordIndex = 0; wordIndex < line.words.size(); wordIndex++) {
            LyricLine.Word word = line.words.get(wordIndex);
            if (word == null || word.word == null || word.word.isEmpty()) continue;
            List<String> wordGlyphs = graphemes(word.word);
            if (wordGlyphs.isEmpty()) continue;

            int matched = findSequence(lineGlyphs, wordGlyphs, cursor);
            int start = matched >= 0 ? matched : cursor;
            int end = Math.min(start + wordGlyphs.size(), lineGlyphs.size());
            if (start >= lineGlyphs.size()) break;

            // 词表里没有的字形(空格、引号、行首缩进)夹在两个词之间:它的时间取后一个词的开头,
            // 时长给 0。给 0 而不是给一个假窗口,是为了让渲染层的插值函数自己决定怎么处理,
            // 而不是在这里替它编一个时间。
            for (int gap = cursor; gap < start; gap++) {
                slots[gap] = new Glyph(lineGlyphs.get(gap), word.timestamp, 0L, -1);
            }

            long span = Math.max(0L, word.duration);
            for (int local = 0; local < end - start; local++) {
                long glyphStart = word.timestamp + span * local / wordGlyphs.size();
                long glyphEnd = local == wordGlyphs.size() - 1
                        ? Math.max(word.timestamp + span, glyphStart)
                        : word.timestamp + span * (local + 1) / wordGlyphs.size();
                slots[start + local] = new Glyph(lineGlyphs.get(start + local), glyphStart,
                        Math.max(0L, glyphEnd - glyphStart), wordIndex);
                lastResolved = Math.max(lastResolved, glyphEnd);
            }
            cursor = Math.max(cursor, end);
        }

        // 词表结束后残留的字形(行尾标点、和声行之后的空间)跟到最后一个已知时刻。
        for (int index = 0; index < lineGlyphs.size(); index++) {
            if (slots[index] != null) continue;
            slots[index] = new Glyph(lineGlyphs.get(index), lastResolved, 0L, -1);
        }
        Collections.addAll(timeline, slots);
        return timeline;
    }

    /** 在 {@code source} 里从 {@code from} 起找 {@code target} 子序列，返回起始下标；找不到返回 -1。 */
    private static int findSequence(List<String> source, List<String> target, int from) {
        if (target.isEmpty()) return from;
        for (int index = Math.max(0, from); index <= source.size() - target.size(); index++) {
            boolean matched = true;
            for (int offset = 0; offset < target.size(); offset++) {
                if (!source.get(index + offset).equals(target.get(offset))) {
                    matched = false;
                    break;
                }
            }
            if (matched) return index;
        }
        return -1;
    }

    /** 切分器不可用时的兜底:码点 + 组合记号。 */
    private static List<String> graphemesWithoutSegmenter(String text) {
        List<String> result = new ArrayList<String>();
        int index = 0;
        int length = text.length();
        while (index < length) {
            int codePoint = text.codePointAt(index);
            int end = index + Character.charCount(codePoint);
            while (end < length) {
                int next = text.codePointAt(end);
                int type = Character.getType(next);
                if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK) {
                    end += Character.charCount(next);
                } else {
                    break;
                }
            }
            result.add(text.substring(index, end));
            index = end;
        }
        return result;
    }
}
