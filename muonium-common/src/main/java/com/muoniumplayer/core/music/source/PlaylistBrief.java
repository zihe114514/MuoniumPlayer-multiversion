package com.muoniumplayer.core.music.source;

/**
 * 歌单摘要（列表页用）。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class PlaylistBrief {
    private final String id;
    private final String name;
    private final String coverUrl;
    private final int trackCount;

    public PlaylistBrief(String id, String name, String coverUrl, int trackCount) {
        this.id = id;
        this.name = name;
        this.coverUrl = coverUrl;
        this.trackCount = trackCount;
    }

    public String getId() { return id; }

    public String getName() { return name; }

    public String getCoverUrl() { return coverUrl; }

    public int getTrackCount() { return trackCount; }
}
