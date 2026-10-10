package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/**
 * 汽水音乐「最近播放」上报器。
 *
 * <p>汽水没有网易云那种逐秒时长的打点接口,唯一的服务端记录入口是
 * {@code POST /luna/pc/me/recently-played-media}(一首歌一条 media)。因此
 * 这里不需要 {@link NeteasePlaybackHistoryReporter} 的 observe/finish 会话
 * 记账:音频真正出声时上报一次即可,切歌/播完不再产生第二条请求。</p>
 *
 * <p>触发点与网易云/酷狗上报器完全对齐({@code CloudMusic} 的两个 start
 * 位置:正常起播与 automix 无缝交接),门禁也同款:非汽水源、未登录、
 * 设置里关掉,三者任一即整轮安静跳过。</p>
 */
public final class QishuiPlaybackReporter {

    /** 同一首歌在极短时间内只报一次(automix 交接与正常起播可能撞车)。 */
    private static volatile String lastReportedTrackId = "";

    private QishuiPlaybackReporter() {
    }

    public static void start(Music song, PlayList source) {
        if (song == null || !song.isQishui()
                || !NCMPlayerConfig.isQishuiRecentlyPlayedReportEnabled()
                || !QishuiSessionStore.isLoggedIn()) {
            return;
        }
        final String trackId = song.getSourceId();
        if (trackId.isEmpty() || trackId.equals(lastReportedTrackId)) {
            return;
        }
        lastReportedTrackId = trackId;
        MultiThreadingUtil.runAsync(() -> {
            // 失败静默(日志在服务层):上报失败不该在游玩过程弹任何东西;
            // 重置去重键,允许同一首被再次播放时重试。
            if (!QishuiMusicService.reportRecentlyPlayed(trackId)) {
                lastReportedTrackId = "";
            }
        });
    }
}
