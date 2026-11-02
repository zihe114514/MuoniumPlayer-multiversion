package com.muoniumplayer.core.music.source;

import java.util.List;

/**
 * 跨平台聚合门面。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 *
 * <p>宿主（{@code NeteaseMusicService}）持有本类实例并注入两家的登录态；
 * 调用按 {@link Track#getSource()} 分发。</p>
 */
public final class MuMusicApi {
    private final MuNeteaseApi netease = new MuNeteaseApi();
    private final MuQQApi qq = new MuQQApi();

    public MuNeteaseApi getNetease() { return netease; }

    public MuQQApi getQq() { return qq; }

    public List<Track> search(MusicSource source, String keyword, int limit) {
        return source == MusicSource.QQ
                ? qq.search(keyword, limit, 0)
                : netease.search(keyword, limit, 0);
    }

    public SongUrl getSongUrl(Track track, AudioQuality quality) {
        return track.getSource() == MusicSource.QQ
                ? qq.getSongUrl(track, quality)
                : netease.getSongUrl(track, quality);
    }

    public Lyric getLyric(Track track) {
        return track.getSource() == MusicSource.QQ
                ? qq.getLyric(track)
                : netease.getLyric(track);
    }

    public QrCode createQrCode(MusicSource source) {
        return source == MusicSource.QQ ? qq.createQrCode() : netease.createQrCode();
    }

    public QrLoginState checkQrCode(MusicSource source, QrCode qr) {
        return source == MusicSource.QQ ? qq.checkQrCode(qr) : netease.checkQrCode(qr);
    }
}
