package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;

/** UI 的一次不可变快照：选中曲目不等于当前出声曲目，绝不替换音频会话或歌词时钟。 */
public final class PlaybackPresentation {
    public final Music audibleTrack;
    public final Music displayTrack;
    public final boolean preparing;
    public final long requestNanos;

    public PlaybackPresentation(Music audibleTrack, Music requestedTrack, long requestNanos) {
        this.audibleTrack = audibleTrack;
        this.displayTrack = requestedTrack == null ? audibleTrack : requestedTrack;
        this.preparing = requestedTrack != null;
        this.requestNanos = preparing ? requestNanos : 0L;
    }

    public String displayKey() {
        return displayTrack == null ? "" : displayTrack.getStableKey();
    }

    public boolean isSelected(Music track) {
        return track != null && displayTrack != null && displayKey().equals(track.getStableKey());
    }
}
