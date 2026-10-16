package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Split lines：把「两行歌词写在一行里」拆成两行独立渲染。
 *
 * <p>网易云歌词常见这种脏数据 —— 一行文本中间塞着 {@code /} 或全角斜杠
 * {@code ／}（偶尔是 {@code //}），上半句和下半句挤在同一时间轴里。参考实现
 * （Placement: In the world 的 Split lines 开关）把它拆开各自渲染。</p>
 *
 * <h2>拆分规则</h2>
 * <ul>
 *   <li>分隔符：{@code /} 与 {@code ／}。{@code //}（有些源用它做装饰）按一个分隔符算；
 *       行首/行尾的孤立分隔符不拆（那是空半句，拆了出空行）。</li>
 *   <li>时间轴按字符数比例分摊：总窗口 [timestamp, timestamp+duration) 均分给两半句，
 *       按各自字符数占整行的比例切。逐词轴同理按词归属切分。</li>
 *   <li>翻译/音译侧车跟随各自半句：同样拆，拆不出就整条跟第一半句。</li>
 *   <li>拆出的两个 {@link LyricLine} 都是全新对象（渲染对象绝不跨播放复用），
 *       第二半句的 {@code timestamp} 推迟到第一半句唱完时。</li>
 * </ul>
 */
public final class SplitLines {

    private SplitLines() {
    }

    /**
     * 按需拆分一行。不需要拆（开关关、无分隔符、拆出空半句）时返回只含原行的列表。
     */
    public static List<LyricLine> split(LyricLine line, boolean enabled) {
        List<LyricLine> out = new ArrayList<>(1);
        if (line == null) return out;
        if (!enabled) {
            out.add(line);
            return out;
        }
        String text = line.lyric == null ? "" : line.lyric;
        int sep = findSeparator(text);
        if (sep < 0) {
            out.add(line);
            return out;
        }
        String first = text.substring(0, sep).trim();
        String second = text.substring(sep + separatorLength(text, sep)).trim();
        // 空半句不拆：行首/行尾分隔符或「////」这种装饰串
        if (first.isEmpty() || second.isEmpty()) {
            out.add(line);
            return out;
        }

        long start = line.timestamp;
        long duration = line.duration > 0 ? line.duration : 0L;
        // 总时长未知时按字符数×300ms 估一个窗口，保持两半句都有合理时间轴
        if (duration <= 0) {
            duration = (long) (text.length() * 300.0);
        }
        double ratio = (double) first.length() / (double) (first.length() + second.length());
        long firstDur = Math.max(200L, Math.round(duration * ratio));
        long secondStart = start + firstDur;
        long secondDur = Math.max(200L, duration - firstDur);

        LyricLine a = copyOf(line, first, start, firstDur, true);
        LyricLine b = copyOf(line, second, secondStart, secondDur, false);
        // 逐词轴按词归属切分；切不出（词表与半句边界对不上）就整体清空走行级
        assignWords(a, line, 0, sep);
        assignWords(b, line, sep + separatorLength(text, sep), text.length());
        a.markDirty();
        b.markDirty();
        out.add(a);
        out.add(b);
        return out;
    }

    /**
     * 找第一个可拆分的分隔符下标。{@code //} 按一个分隔符算；两侧都必须有非空内容。
     */
    static int findSeparator(String text) {
        if (text == null) return -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '/' && c != '／') continue;
            int after = i + separatorLength(text, i);
            // 两侧都有内容才拆
            if (i > 0 && after < text.length() && !text.substring(0, i).trim().isEmpty()
                    && !text.substring(after).trim().isEmpty()) {
                return i;
            }
            i = after - 1;
        }
        return -1;
    }

    /** 分隔符长度：{@code /} 或 {@code ／} 一个 char，连续的按一串算（{@code //} 也算一个）。 */
    private static int separatorLength(String text, int at) {
        char c = text.charAt(at);
        int len = 1;
        while (at + len < text.length() && (text.charAt(at + len) == '/' || text.charAt(at + len) == '／')) {
            len++;
        }
        return len;
    }

    /** 把 [from,to) 区间内的词分给 target；一个都对不上就清空词表走行级渲染。 */
    private static void assignWords(LyricLine target, LyricLine source, int from, int to) {
        if (source.words == null || source.words.isEmpty()) return;
        List<LyricLine.Word> picked = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < source.words.size(); i++) {
            LyricLine.Word w = source.words.get(i);
            if (w == null || w.word == null) continue;
            int wordStart = cursor;
            cursor += w.word.length();
            if (wordStart >= from && wordStart < to) {
                picked.add(w);
            } else if (wordStart < from && cursor > from) {
                picked.add(w); // 跨边界的词归前半句
            }
        }
        if (picked.isEmpty() || picked.size() == source.words.size()) {
            target.words.clear();
            return;
        }
        target.words.addAll(picked);
    }

    /**
     * 复制一行的数据字段（渲染状态全部全新，不带走）。侧车文本按同一分隔符拆分；
     * 拆不出（侧车无分隔符）时整条跟到第一半句、第二半句留空 —— 比把整句翻译
     * 复制两遍更接近原意。{@code isFirstHalf} 决定「跟整条」落在哪一侧。
     */
    private static LyricLine copyOf(LyricLine source, String text, long timestamp, long duration,
                                     boolean isFirstHalf) {
        LyricLine copy = new LyricLine(timestamp, text);
        copy.duration = duration;
        copy.translationText = splitSidecar(source.translationText, isFirstHalf);
        copy.romanizationText = splitSidecar(source.romanizationText, isFirstHalf);
        return copy;
    }

    /**
     * 侧车文本按同一分隔符拆分。{@code keepWithFirst} 为 false（正在造第二半句）且侧车
     * 拆不出时返回 null（整条翻译跟第一半句去了）。
     */
    private static String splitSidecar(String sidecar, boolean keepWithFirst) {
        if (sidecar == null || sidecar.isEmpty()) return null;
        int sep = findSeparator(sidecar);
        if (sep < 0) {
            return keepWithFirst ? sidecar : null;
        }
        String first = sidecar.substring(0, sep).trim();
        String second = sidecar.substring(sep + 1).trim();
        if (first.isEmpty() || second.isEmpty()) {
            return keepWithFirst ? sidecar : null;
        }
        return keepWithFirst ? first : second;
    }
}
