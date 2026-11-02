package com.muoniumplayer.core.music.source;

import java.util.List;

/**
 * 歌词聚合。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class Lyric {
    private final String lrc;
    private final String translated;
    private final String yrc;
    private final List<LyricLine> lines;

    public Lyric(String lrc, String translated, String yrc, List<LyricLine> lines) {
        this.lrc = lrc;
        this.translated = translated;
        this.yrc = yrc;
        this.lines = lines;
    }

    public String getLrc() { return lrc; }

    public String getTranslated() { return translated; }

    public String getYrc() { return yrc; }

    public List<LyricLine> getLines() { return lines; }

    public boolean getHasWordByWord() {
        if (lines == null) return false;
        for (LyricLine line : lines) {
            if (line.getWords() != null && !line.getWords().isEmpty()) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }
}
