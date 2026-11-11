package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次"播放会话"，把 当前声音 = 当前 PlaybackSession = 当前歌词 Timeline 绑定为一对一。
 *
 * 每次开始播放一首新歌都生成一个唯一 Session；用 AtomicLong generation 做逻辑取消（logical
 * cancellation）。异步歌词任务捕获 {@link #sessionId} + {@link #songId}，提交前必须通过
 * {@link #isActive()} 双重校验，任何不匹配的旧结果直接丢弃，不污染新歌状态。
 *
 * 本字段绑定本会话实际使用的 AudioPlayer；普通切歌与 automix 都先准备独立 deck，
 * 校验会话有效后再发布，歌词同步始终读取该会话自己的时钟。
 */
public class PlaybackSession {

    public final long sessionId;
    public final String trackKey;
    public final long songId;
    public final AtomicBoolean active = new AtomicBoolean(true);

    /** 真正开始播放后才置 true；歌词先到/后到都只暂存于本 Session。 */
    public volatile boolean audioActive = false;

    /** 两阶段提交：后台只 fetch+parse，结果暂存于此，由主线程 applyLyricTimeline() 提交。 */
    public volatile List<LyricLine> pendingLyrics = null;

    /** 本 Session 绑定的 AudioPlayer。 */
    public volatile AudioPlayer player = null;

    public PlaybackSession(long sessionId, String trackKey, long songId) {
        this.sessionId = sessionId;
        this.trackKey = trackKey == null ? "" : trackKey;
        this.songId = songId;
    }

    /** 会话是否仍为当前 generation 且未被 invalidate。 */
    public boolean isActive() {
        return active.get() && CloudMusic.generation.get() == sessionId;
    }

    public void invalidate() {
        active.set(false);
    }
}
