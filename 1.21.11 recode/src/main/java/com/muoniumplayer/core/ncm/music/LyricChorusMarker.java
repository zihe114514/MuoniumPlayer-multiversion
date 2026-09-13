package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 副歌（高潮段）标记。对应 Folia 的 {@code chorusDetector} + {@code chorusEffects}。
 *
 * <p>两条来源，精度从高到低：</p>
 * <ol>
 *   <li><b>网易云精确区间</b>：{@code /api/song/lyric/v1} 带 {@code cp=true} 时会多返回一个
 *       {@code chorus} 数组，每项给出起止毫秒。与行时间轴有重叠即算副歌，这是最可靠的一路。</li>
 *   <li><b>文本频次兜底</b>：没有区间时，认为出现次数最多的那句歌词就是副歌
 *       （与 Folia 的 {@code detectChorusLines} 同一启发式）。纯本地、零网络，任何音源都能用。</li>
 * </ol>
 *
 * <p>标记结果写回 {@link LyricLine#isChorus} / {@link LyricLine#chorusIndex} /
 * {@link LyricLine#chorusEffect}，渲染层据此对副歌特化配色与发光。</p>
 *
 * <p><b>与 Folia 的一处有意差异</b>：Folia 每次加载都用 {@code Math.random()} 给副歌段抽特效，
 * 同一首歌重播时特效会换。这里改成按段号取模，同一首歌每次的观感一致——随机特效在
 * 「重取歌词 / 切回来再听」时会显得像 bug。</p>
 */
final class LyricChorusMarker {

    /** 可用的副歌特效数量，与 Folia 的 {@code bars/circles/beams} 对齐。 */
    static final int EFFECT_COUNT = 3;

    /** 短于这个长度的行不参与频次统计（"Yeah"、"Oh" 这类语气词会污染统计）。 */
    private static final int MIN_CHARS = 2;

    private LyricChorusMarker() {
    }

    /**
     * 用网易云返回的精确区间标记。区间单位是<b>毫秒</b>。
     *
     * @return 实际标记到的行数；0 表示这份区间与时间轴没有重叠，调用方应继续尝试文本兜底
     */
    static int markByRanges(List<LyricLine> lines, List<long[]> ranges) {
        if (lines == null || lines.isEmpty() || ranges == null || ranges.isEmpty()) return 0;

        int marked = 0;
        int segment = -1;
        for (LyricLine line : lines) {
            if (line == null || line.isBreakLine) continue;
            long lineStart = line.timestamp;
            long lineEnd = line.timestamp + Math.max(1L, line.duration);

            int matched = -1;
            for (int index = 0; index < ranges.size(); index++) {
                long[] range = ranges.get(index);
                if (range == null || range.length < 2) continue;
                if (lineStart < range[1] && lineEnd > range[0]) {
                    matched = index;
                    break;
                }
            }
            if (matched < 0) {
                segment = -1;
                continue;
            }
            // 同一个区间可能覆盖多行,它们属于同一段副歌,共用一个段号与特效。
            if (matched != segment) segment = matched;
            line.isChorus = true;
            line.chorusIndex = segment;
            line.chorusEffect = segment % EFFECT_COUNT;
            marked++;
        }
        return marked;
    }

    /**
     * 文本频次兜底：出现次数最多的歌词文本算副歌。
     *
     * @return 实际标记到的行数
     */
    static int markByText(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return 0;

        Map<String, Integer> counts = new HashMap<>();
        for (LyricLine line : lines) {
            if (line == null || line.isBreakLine) continue;
            String text = line.lyric == null ? "" : line.lyric.trim();
            if (text.isEmpty() || text.equals(LyricTimelineSupport.INTERLUDE_TEXT)) continue;
            if (text.codePointCount(0, text.length()) < MIN_CHARS) continue;
            Integer previous = counts.get(text);
            counts.put(text, previous == null ? 1 : previous + 1);
        }

        int max = 0;
        for (Integer count : counts.values()) {
            if (count != null && count > max) max = count;
        }
        // 一句都没重复过就没有"高潮段"可言,宁可不标,也不随便指一行。
        if (max <= 1) return 0;

        int marked = 0;
        int segment = -1;
        // -2 起步：第 0 行也满足 index != previousIndex + 1，于是第一段从 0 号开始。
        int previousIndex = -2;
        for (int index = 0; index < lines.size(); index++) {
            LyricLine line = lines.get(index);
            if (line == null) continue;
            String text = line.lyric == null ? "" : line.lyric.trim();
            Integer count = counts.get(text);
            if (line.isBreakLine || count == null || count != max) continue;
            // 连续的行算同一段；中间夹了别的行（或间奏）就开一段新的，段号与特效随之推进。
            if (index != previousIndex + 1) segment++;
            line.isChorus = true;
            line.chorusIndex = segment;
            line.chorusEffect = segment % EFFECT_COUNT;
            previousIndex = index;
            marked++;
        }
        return marked;
    }

    /**
     * 从 {@code /api/song/lyric/v1} 的响应里读出 {@code chorus} 区间。
     *
     * <p>字段名上游用过两套（{@code startTime/endTime} 与 {@code start/end}），两种都认；
     * 单位恒为毫秒。响应可能把 {@code chorus} 放在根上，也可能裹在 {@code data} 里。</p>
     *
     * @return 永不为 {@code null}；没有区间时返回空列表
     */
    static List<long[]> parseRanges(JsonObject response) {
        List<long[]> ranges = new ArrayList<>();
        if (response == null) return ranges;

        JsonArray chorus = arrayOf(response, "chorus");
        if (chorus == null && response.has("data") && response.get("data").isJsonObject()) {
            chorus = arrayOf(response.getAsJsonObject("data"), "chorus");
        }
        if (chorus == null) return ranges;

        for (int index = 0; index < chorus.size(); index++) {
            JsonElement element = chorus.get(index);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject range = element.getAsJsonObject();
            long start = longField(range, "startTime", "start", "startMs");
            long end = longField(range, "endTime", "end", "endMs");
            if (end <= start) continue;
            ranges.add(new long[]{start, end});
        }
        return ranges;
    }

    /**
     * 副歌强调量 0..1，渲染层读这个值就够，不必自己关心「怎么判定副歌」与「第几段」。
     *
     * <p>{@code chorusEffect} 只改曲线<b>形状</b>，不改<b>有无</b>——换段号只会换观感，
     * 不会出现「某一段副歌突然一点强调都没有」这种看起来像失效的现象：</p>
     * <ul>
     *   <li>{@code 0} 稳定高亮：整段维持较高的底，句内随演唱进度再起落一次。</li>
     *   <li>{@code 1} 呼吸脉冲：约 1.6 秒一个来回，像心跳。</li>
     *   <li>{@code 2} 收束聚焦：进段时冲一下，随后收敛到一个稳定的底。</li>
     * </ul>
     *
     * <p>首尾各留一小段斜坡（前 18% 淡入、后 14% 淡出），避免副歌边缘那一句被硬切。</p>
     *
     * @param positionMs 播放位置（毫秒，调用方传入的应是已含用户偏移的时钟）
     * @return 0..1；非副歌行恒为 {@code 0}
     */
    static double emphasis(LyricLine line, double positionMs) {
        if (line == null || !line.isChorus) return 0.0;

        long span = Math.max(1L, line.duration);
        double progress = (positionMs - line.timestamp) / (double) span;
        double envelope = smoothstep(clamp01(progress / .18))
                * (1.0 - smoothstep(clamp01((progress - .86) / .14)));
        if (envelope <= 0.0) return 0.0;

        double shape;
        switch (Math.floorMod(line.chorusEffect, EFFECT_COUNT)) {
            case 1:
                // 呼吸：周期 1.6s，用绝对时钟而不是行内进度，整段起伏才是连贯的。
                shape = .55 + .45 * Math.sin(positionMs / 1000.0 * (.5 * Math.PI) / .8);
                break;
            case 2:
                shape = .75 + .25 * Math.exp(-Math.max(0.0, progress) * 3.0);
                break;
            default:
                shape = .80 + .20 * Math.sin(Math.PI * clamp01(progress));
                break;
        }
        return envelope * clamp01(shape);
    }

    private static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static double smoothstep(double value) {
        double t = clamp01(value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static JsonArray arrayOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static long longField(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                return element.getAsLong();
            } catch (RuntimeException ignored) {
                // 换个字段名再试。
            }
        }
        return -1L;
    }
}
