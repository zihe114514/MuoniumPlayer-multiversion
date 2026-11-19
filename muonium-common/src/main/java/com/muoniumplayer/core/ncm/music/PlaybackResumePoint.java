package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;

/** 位置与曲目身份一起转交；索引相同不代表是同一首歌。 */
final class PlaybackResumePoint {
    final String trackKey;
    final long positionMillis;

    PlaybackResumePoint(Music track, long positionMillis) {
        this.trackKey = track.getStableKey();
        this.positionMillis = Math.max(0L, positionMillis);
    }

    boolean matches(Music track) {
        return track != null && trackKey.equals(track.getStableKey());
    }

    long positionFor(Music track) {
        if (!matches(track)) return 0L;
        long duration = track.getDuration();
        return duration > 0L ? Math.min(positionMillis, duration) : positionMillis;
    }
}
