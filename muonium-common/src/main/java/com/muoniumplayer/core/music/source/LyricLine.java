package com.muoniumplayer.core.music.source;

import java.util.List;

/**
 * 逐字歌词行。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 *
 * <p>{@code words} 为空表示行级时间轴；{@code metadata=true} 的行是元数据（作曲/作词等），
 * 不参与歌词渲染。</p>
 */
public final class LyricLine {
    private final long startMs;
    private final long durationMs;
    private final String text;
    private final List<LyricWord> words;
    private final String translation;
    private final boolean metadata;

    public LyricLine(long startMs, long durationMs, String text, List<LyricWord> words,
                     String translation, boolean metadata) {
        this.startMs = startMs;
        this.durationMs = durationMs;
        this.text = text;
        this.words = words;
        this.translation = translation;
        this.metadata = metadata;
    }

    public long getStartMs() { return startMs; }

    public long getDurationMs() { return durationMs; }

    public String getText() { return text; }

    public List<LyricWord> getWords() { return words; }

    public String getTranslation() { return translation; }

    public boolean isMetadata() { return metadata; }
}
