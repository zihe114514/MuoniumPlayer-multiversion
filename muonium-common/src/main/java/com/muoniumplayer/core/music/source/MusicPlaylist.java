package com.muoniumplayer.core.music.source;

/**
 * 歌单（完整形态，含描述）。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class MusicPlaylist {
    private final MusicSource source;
    private final String id;
    private final String name;
    private final String coverUrl;
    private final int trackCount;
    private final String description;

    public MusicPlaylist(MusicSource source, String id, String name, String coverUrl,
                         int trackCount, String description) {
        this.source = source;
        this.id = id;
        this.name = name;
        this.coverUrl = coverUrl;
        this.trackCount = trackCount;
        this.description = description;
    }

    public MusicSource getSource() { return source; }

    public String getId() { return id; }

    public String getName() { return name; }

    public String getCoverUrl() { return coverUrl; }

    public int getTrackCount() { return trackCount; }

    public String getDescription() { return description; }
}
