package com.muoniumplayer.core.music.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * LRC / YRC 歌词解析。自 Cadence（原 music-api-0.1.1.jar，原 jar 的 lyric 包 包）手翻，
 * 2026-11 吸收进仓；输出已对拍原 jar 黄金基准（.scratch/cadence-absorb/golden/golden.json）。
 *
 * <p>语义要点（翻错任何一条都会改行为）：</p>
 * <ul>
 *   <li>yrc 非空时走 yrc 主轴，lrc 只当翻译来源；否则 lrc 主轴并按「下一行起点」回填 duration；</li>
 *   <li>翻译按 startMs 精确匹配，失配时取 <b>首个与主行差值 &lt; 500ms</b> 的翻译行；</li>
 *   <li>LRC 时间轴支持多时间戳一行（多行复制），秒支持 {@code [mm:ss.xx]} 与 {@code [mm:ss:xx]} 两种；</li>
 *   <li>{@code [offset:±ms]} 全局位移；yrc 的 {@code {..}} JSON 行是元数据（t=起点，c[].tx=文本）。</li>
 * </ul>
 */
public final class LyricParser {
    public static final LyricParser INSTANCE = new LyricParser();

    private static final Gson GSON = new Gson();
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),(\\d+)\\)([^(]+)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)]");
    private static final Pattern OFFSET_RE = Pattern.compile("\\[offset:\\s*([+-]?\\d+)]",
            Pattern.CASE_INSENSITIVE);

    private LyricParser() {
    }

    public List<LyricLine> parse(String lrc, String translated, String yrc) {
        boolean usingYrc = yrc != null && !yrc.isBlank();
        List<LyricLine> base = usingYrc ? parseYrc(yrc) : parseLrcBlank(lrc);
        base.sort(Comparator.comparingLong(LyricLine::getStartMs));

        List<LyricLine> withDuration;
        if (usingYrc) {
            withDuration = base;
        } else {
            // LRC 行没有行时长：用下一行起点回填，末行保持 0
            List<LyricLine> filled = new ArrayList<>(base.size());
            for (int i = 0; i < base.size(); i++) {
                LyricLine line = base.get(i);
                if (i + 1 < base.size()) {
                    long dur = Math.max(base.get(i + 1).getStartMs() - line.getStartMs(), 0L);
                    filled.add(new LyricLine(line.getStartMs(), dur, line.getText(),
                            line.getWords(), line.getTranslation(), line.isMetadata()));
                } else {
                    filled.add(line);
                }
            }
            withDuration = filled;
        }

        if (translated == null || translated.isBlank()) return withDuration;

        Map<Long, String> transMap = new LinkedHashMap<>();
        for (LyricLine it : parseLrc(translated)) {
            if (!it.getText().isBlank()) transMap.put(it.getStartMs(), it.getText());
        }
        if (transMap.isEmpty()) return withDuration;

        List<LyricLine> out = new ArrayList<>(withDuration.size());
        for (LyricLine line : withDuration) {
            String t = transMap.get(line.getStartMs());
            if (t == null) {
                // 宽容匹配：首个与主行起点差 < 500ms 的翻译行
                for (Map.Entry<Long, String> entry : transMap.entrySet()) {
                    if (Math.abs(entry.getKey() - line.getStartMs()) < 500L) {
                        t = entry.getValue();
                        break;
                    }
                }
            }
            if (t != null) {
                out.add(new LyricLine(line.getStartMs(), line.getDurationMs(), line.getText(),
                        line.getWords(), t, line.isMetadata()));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static List<LyricLine> parseLrcBlank(String lrc) {
        if (lrc == null || lrc.isBlank()) return new ArrayList<>();
        return parseLrc(lrc);
    }

    private static List<LyricLine> parseYrc(String yrc) {
        List<LyricLine> out = new ArrayList<>();
        for (String rawLine : yrc.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("{")) {
                // 元数据行：{"t":..,"c":[{"tx":".."},..]}
                try {
                    JsonObject meta = GSON.fromJson(line, JsonObject.class);
                    JsonArray c = meta.getAsJsonArray("c");
                    if (c != null) {
                        StringBuilder text = new StringBuilder();
                        for (JsonElement element : c) {
                            JsonElement tx = element.getAsJsonObject().get("tx");
                            text.append(tx != null && !tx.isJsonNull() ? tx.getAsString() : "");
                        }
                        long t = 0L;
                        JsonElement tEl = meta.get("t");
                        if (tEl != null && !tEl.isJsonNull()) t = tEl.getAsLong();
                        out.add(new LyricLine(t, 0L, text.toString(), Collections.<LyricWord>emptyList(), null, true));
                    }
                } catch (Exception ignored) {
                    // 坏的元数据行直接跳过
                }
            } else {
                Matcher m = YRC_LINE.matcher(line);
                if (m.matches()) {
                    long start = Long.parseLong(m.group(1));
                    long duration = Long.parseLong(m.group(2));
                    String rest = m.group(3);
                    List<LyricWord> words = new ArrayList<>();
                    StringBuilder sb = new StringBuilder();
                    Matcher w = YRC_WORD.matcher(rest);
                    while (w.find()) {
                        long wStart = Long.parseLong(w.group(1));
                        long wDur = Long.parseLong(w.group(2));
                        String wText = w.group(4);
                        words.add(new LyricWord(wStart, wDur, wText));
                        sb.append(wText);
                    }
                    String text = sb.length() == 0 && !rest.isEmpty() ? rest : sb.toString();
                    out.add(new LyricLine(start, duration, text, words, null, false));
                }
            }
        }
        return out;
    }

    private static List<LyricLine> parseLrc(String lrc) {
        long offset = 0L;
        Matcher om = OFFSET_RE.matcher(lrc);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        List<LyricLine> out = new ArrayList<>();
        for (String rawLine : lrc.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            List<Long> mins = new ArrayList<>();
            List<Double> secs = new ArrayList<>();
            int lastEnd = -1;
            Matcher tm = LRC_TIME.matcher(line);
            while (tm.find()) {
                try {
                    mins.add(Long.parseLong(tm.group(1)));
                } catch (NumberFormatException e) {
                    mins.add(0L);
                }
                try {
                    secs.add(Double.parseDouble(tm.group(2).replace(':', '.')));
                } catch (NumberFormatException e) {
                    secs.add(0.0);
                }
                lastEnd = tm.end();
            }
            if (mins.isEmpty()) continue;
            String text = line.substring(lastEnd).trim();

            for (int t = 0; t < mins.size(); t++) {
                long ms = mins.get(t) * 60000L + (long) (secs.get(t) * 1000) + offset;
                out.add(new LyricLine(Math.max(ms, 0L), 0L, text, Collections.<LyricWord>emptyList(), null, false));
            }
        }
        return out;
    }
}
