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

    /**
     * 这一行的曲目是不是「正在显示的那一首」。
     *
     * <p>先按 stableKey 相等匹配；换源那一格两侧是<b>两条不同的曲目</b>（原曲与替身），
     * 平台与 id 都换了，key 永远不等 —— 再看换源配对键：录取点把原曲与替身标了同一把
     * {@code crossSourcePeerKey}，钥匙相等即视为同一格。没有这一步，自动换源成功的那一行
     * 会当场丢掉「正在播放」的高亮与待播角标（替身在响、行还绑着原曲对象）。</p>
     */
    public boolean isSelected(Music track) {
        if (track == null || displayTrack == null) return false;
        if (displayKey().equals(track.getStableKey())) return true;
        return displayTrack.isCrossSourcePeerOf(track);
    }
}
