package com.muoniumplayer.core.music.source;

/**
 * 逐字歌词的音节/单词时间片。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class LyricWord {
    private final long startMs;
    private final long durationMs;
    private final String text;

    public LyricWord(long startMs, long durationMs, String text) {
        this.startMs = startMs;
        this.durationMs = durationMs;
        this.text = text;
    }

    public long getStartMs() { return startMs; }

    public long getDurationMs() { return durationMs; }

    public String getText() { return text; }
}
