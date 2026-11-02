package com.muoniumplayer.core.music.source;

/**
 * 跨平台曲目行。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 *
 * <p>不可变；QQ 曲目的 {@code id} 与 {@code mid} 通常相同，网易云 {@code mid} 为空。</p>
 */
public final class Track {
    private final MusicSource source;
    private final String id;
    private final String mid;
    private final String name;
    private final String artists;
    private final String album;
    private final long durationMs;
    private final String coverUrl;
    private final boolean vip;

    public Track(MusicSource source, String id, String mid, String name, String artists,
                 String album, long durationMs, String coverUrl, boolean vip) {
        this.source = source;
        this.id = id;
        this.mid = mid;
        this.name = name;
        this.artists = artists;
        this.album = album;
        this.durationMs = durationMs;
        this.coverUrl = coverUrl;
        this.vip = vip;
    }

    public MusicSource getSource() { return source; }

    public String getId() { return id; }

    public String getMid() { return mid; }

    public String getName() { return name; }

    public String getArtists() { return artists; }

    public String getAlbum() { return album; }

    public long getDurationMs() { return durationMs; }

    public String getCoverUrl() { return coverUrl; }

    public boolean getVip() { return vip; }
}
