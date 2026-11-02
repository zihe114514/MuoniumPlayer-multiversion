package com.muoniumplayer.core.ncm.music.ambient;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ExternalPlayback;
import com.muoniumplayer.core.ncm.music.LiveStreamSession;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseListenTogetherService;
import com.muoniumplayer.core.ncm.music.KuGouListenTogetherService;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.spotify.SpotifyConnectMirror;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 环境选曲自动 DJ：每 tick 从树侧探针收一份 {@link EnvSample}，重新判定
 * {@link MoodDecider 心情}；心情<b>变了</b>并且<b>稳住</b>了一段防抖窗口之后，
 * 按那个风格的关键词搜当前音源并整表起播。
 *
 * <p>行为设计继承 modernMP 已调好的手感：</p>
 * <ul>
 *   <li><b>防抖</b>：环境心情逐 tick 抖（怪路过、一阵雨），新标签要连续保持
 *       {@link AmbientSettings#stableMs} 才动作，音乐不跟着环境颤。</li>
 *   <li><b>立即切换</b>：一旦触发，{@code play} 整表替换起播 —— 交叉淡化的听感
 *       交给无缝切换（automix），它经 {@code queueRevision} 自动重算，无需照会。</li>
 *   <li><b>复合标签</b>（"探索/史诗"）：随机取一边，再从那个心情的关键词里随机取一个
 *       —— 同一心情重复触发会搜出不同的词。</li>
 *   <li><b>冷却</b>：心情再怎么变，{@link #COOLDOWN_MS} 内不重搜，限定平台搜索流量。</li>
 * </ul>
 *
 * <p><b>本仓库特有的护栏</b>（modernMP 没有）：一起听房间（网易/酷狗）与 mp-party
 * 房间内不抢歌 —— 房间按曲目本体同步，本地换歌等于整房脱队，mp-party 里起播还会
 * 被改写成「替全房点歌」；直播流活跃不杀；外部播放器镜像（SMTC/Spotify Connect）
 * 接管中不起播（起了也会被拒）。私人 FM 不拦：{@code play} 会正确收尾 FM 会话。</p>
 *
 * <p>线程契约：{@link #onClientTick} 必须在主线程（树的 client tick 就是）；搜索在
 * 工作线程跑（{@link CloudMusic#search} 同步阻塞），结果经
 * {@link MultiThreadingUtil#runOnMainThread} 落回主线程，用 revision 防旧结果串台。
 * 全方法 try/Throwable 包住 —— tick 上的异常不能带崩游戏。</p>
 */
public final class AmbientMusicController {

    /** 两次自动触发的最小间隔，与心情是否抖动无关。 */
    private static final long COOLDOWN_MS = 20_000L;

    // 防抖状态（仅主线程读写）。
    private static String lastSeenLabel;    // 上一 tick 判出的标签
    private static long   stableSinceMs;    // lastSeenLabel 首次出现的时刻
    private static String playingLabel;     // 最近一次触发搜索的心情
    private static long   lastSwitchMs;     // 那次搜索发出的时刻
    private static boolean searching;       // 一次搜索在途（防重叠）

    /** 搜索结果防串号：心情又变过之后，旧搜索回来直接丢弃。 */
    private static long requestRevision;

    /** 只从搜索结果前 TOP_N（平台大致按热度排序）里挑，偏热但留变化空间。 */
    private static final int  TOP_N       = 12;
    /** 记住最近播过的曲目，同一心情不重播同一首。 */
    private static final int  HISTORY_MAX = 24;
    private static final Deque<String> recent = new ArrayDeque<>();

    /** 一次性诊断：首次触发打一份环境快照，实机一眼看出探针采到了什么。 */
    private static boolean triggerLogged;
    private static boolean tickFailureLogged;

    private AmbientMusicController() {
    }

    /** 清空防抖状态，让下一个稳定心情重新触发（总开关打开时调）。 */
    public static void reset() {
        lastSeenLabel = null;
        stableSinceMs = 0L;
        playingLabel  = null;
        requestRevision++;
    }

    /**
     * 每 tick 入口，由各树的共享 client tick 桥调（主线程）。
     *
     * @param sample 树侧探针采的环境快照；{@code null} 表示当前采不到（主菜单等），
     *               直接回到「无世界」状态。
     */
    public static void onClientTick(EnvSample sample) {
        try {
            if (!AmbientSettings.isEnabled() || sample == null) {
                // 关掉/没世界：作废在途搜索，回到干净状态。
                if (searching || lastSeenLabel != null) reset();
                return;
            }

            String label = MoodDecider.decide(sample);
            long now = System.currentTimeMillis();

            // 记录当前标签连续保持了多久。
            if (!label.equals(lastSeenLabel)) {
                lastSeenLabel = label;
                // 房间/镜像接管中不给心情计时：出房间后第一个稳定期就能接上，
                // 而不是把 15s 稳定期耗在根本不能起播的状态里。
                stableSinceMs = playbackOccupied() ? 0L : now;
                return;                                   // 刚变 —— 等它稳下来
            }
            if (now - stableSinceMs < AmbientSettings.stableMs()) return; // 还没稳够
            if (label.equals(playingLabel)) return;       // 这个心情已经在播了
            if (now - lastSwitchMs < COOLDOWN_MS) return; // 冷却中
            if (searching || playbackOccupied()) return;  // 搜索在途 / 房间占用（不撞护栏）

            trigger(label, now);
        } catch (Throwable failure) {
            // 自动选曲是锦上添花：任何异常宁可让这 tick 空过，也不往上抛。
            // 静默吞掉会让「没效果」无从排查 —— 同一种异常只报一次。
            if (!tickFailureLogged) {
                tickFailureLogged = true;
                System.out.println("[MuoniumPlayer/Ambient] tick 异常(此后同类静默):" + failure);
            }
        }
    }

    /**
     * 立即按当前环境触发一次切换（热键/手动入口；主线程调）。
     *
     * <p>进世界后 {@code lastSeenLabel} 要等第一 tick 才有值；在此之前手动触发给一句
     * 「采不到环境」的提示，而不是无声无息像按键失灵。手动绕过稳定期与冷却 ——
     * 就是要立刻听当前环境的曲子。</p>
     */
    public static void triggerNow() {
        try {
            if (searching) return;
            if (lastSeenLabel == null) {
                DownloadDynamicIsland.showAmbientScene("还没采到环境", "进入世界后即可按环境切歌");
                return;
            }
            trigger(lastSeenLabel, System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** 按心情标签取关键词搜索，结果整表起播。 */
    private static void trigger(String label, long now) {
        String keyword = pickKeyword(label);
        if (keyword == null) return;

        searching     = true;
        playingLabel  = label;
        lastSwitchMs  = now;
        final long ticket = ++requestRevision;

        if (!triggerLogged) {
            triggerLogged = true;
            System.out.println("[MuoniumPlayer/Ambient] 首次触发: 心情=" + label
                    + " 关键词=" + keyword);
        }

        // 搜索同步阻塞（全程 HTTP），必须丢工作线程；结果回主线程落地。
        MultiThreadingUtil.runAsync(() -> {
            List<Music> results = null;
            Throwable error = null;
            try {
                results = CloudMusic.search(keyword);
            } catch (Throwable t) {
                error = t;
            }
            final List<Music> finalResults = results;
            final Throwable finalError = error;
            MultiThreadingUtil.runOnMainThread(() -> deliver(ticket, label, keyword, finalResults, finalError));
        });
    }

    /** 搜索结果落地（主线程）：校验 ticket、过护栏、挑曲起播。 */
    private static void deliver(long ticket, String label, String keyword,
                                List<Music> results, Throwable error) {
        searching = false;
        if (ticket != requestRevision) return;            // 心情已翻篇，旧结果丢弃
        if (error != null) {
            System.out.println("[MuoniumPlayer/Ambient] 搜索失败(" + label + "): " + error);
            playingLabel = null;                          // 让下个稳定心情重试
            return;
        }
        if (results == null || results.isEmpty()) {
            playingLabel = null;                          // 空结果同理
            return;
        }
        if (blockedByPlaybackGuards()) {
            // 护栏命中：这轮放弃。playingLabel 清掉 —— 否则退出房间后「心情没变」
            // 会让同心情永远不再触发，卡到环境翻篇为止；清掉后下一轮稳定期重试，
            // 冷却窗口仍限制重试频率，不会反复撞护栏。
            playingLabel = null;
            return;
        }
        int idx = pickResultIndex(results);
        if (idx < 0) {
            playingLabel = null;
            return;
        }
        remember(results.get(idx));
        System.out.println("[MuoniumPlayer/Ambient] 切歌: 心情=" + label
                + " 关键词=" + keyword + " → " + results.get(idx).getName());
        DownloadDynamicIsland.showAmbientScene(label, results.get(idx).getName());
        // 整表起播：后续曲目（与 automix 的衔接）都在同一心情内顺延。
        CloudMusic.play(new ArrayList<>(results), idx);
    }

    /**
     * 起播护栏（主线程判断）：一起听/派对房间不抢歌、直播流不杀、外部镜像接管中不起播。
     * 任一命中即放弃本轮。私人 FM 不拦 —— {@code play} 自己会正确收尾 FM 会话。
     */
    private static boolean blockedByPlaybackGuards() {
        try {
            if (NeteaseListenTogetherService.isActive() || NeteaseListenTogetherService.isBusy()) return true;
            if (KuGouListenTogetherService.isActive()) return true;
            if (MpPartyClient.inRoom()) return true;
            if (LiveStreamSession.isActive()) return true;
            // 镜像接管中（SMTC / Spotify Connect）：起了也会被 refuseInternalPlayback 拦下，
            // 提前判掉省一次「注定失败」的整表替换。
            if (ExternalPlayback.isMirroring() || SpotifyConnectMirror.isMirroring()) return true;
        } catch (Throwable ignored) {
            // 护栏本身出问题按「放行」处理：起播链上还有 refuseInternalPlayback 兜底。
        }
        return false;
    }

    /**
     * 护栏的「只读」版：不弹任何提示。tick 路径的稳定期计时开始前先问一次，
     * 一起听/房间里的心情翻篇不浪费稳定期等待（出房间后第一个稳定期就能接上）。
     */
    private static boolean playbackOccupied() {
        try {
            return NeteaseListenTogetherService.isActive() || NeteaseListenTogetherService.isBusy()
                    || KuGouListenTogetherService.isActive() || MpPartyClient.inRoom()
                    || ExternalPlayback.isMirroring() || SpotifyConnectMirror.isMirroring();
        } catch (Throwable ignored) {
            return false;   // 与护栏同一兜底口径
        }
    }

    /**
     * 从标签取一个搜索关键词：复合 "A/B" 随机取一边 → 查 {@link Mood} →
     * 按当前音源平台选中文/英文关键词，再随机取一个词。解析不到返回 {@code null}。
     */
    private static String pickKeyword(String label) {
        if (label == null || label.isBlank()) return null;
        String[] parts = label.split("/");
        String side = parts[ThreadLocalRandom.current().nextInt(parts.length)].trim();
        Mood mood = Mood.byDisplay(side);
        if (mood == null) return null;
        String pool = isInternationalPlatform() ? mood.keywordsEn : mood.keywordsZh;
        String[] words = pool.split("\\s+");
        return words[ThreadLocalRandom.current().nextInt(words.length)];
    }

    /** 国际源（YouTube/Spotify/Apple）的中文元数据召回差，搜索词换英文。 */
    private static boolean isInternationalPlatform() {
        try {
            MusicPlatform p = NeteaseMusicService.getCurrentPlatform();
            return p == MusicPlatform.YOUTUBE || p == MusicPlatform.SPOTIFY || p == MusicPlatform.APPLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 挑播哪首：扫前 {@link #TOP_N}（平台大致按热度/相关度排序），收下不在最近
     * 历史里的，随机挑一个。前 N 全都最近播过时也回退到随机前 N，保证轮换而不是
     * 永远重播第 0 条。
     */
    private static int pickResultIndex(List<Music> results) {
        int cap = Math.min(TOP_N, results.size());
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            if (!recent.contains(key(results.get(i)))) fresh.add(i);
        }
        if (!fresh.isEmpty()) return fresh.get(ThreadLocalRandom.current().nextInt(fresh.size()));
        return cap == 0 ? -1 : ThreadLocalRandom.current().nextInt(cap);   // 全播过 → 仍轮换
    }

    /** 曲目去重键：跨音源的稳定键（下载缓存/播放历史同款）。 */
    private static String key(Music m) {
        try {
            return m.getStableKey();
        } catch (Throwable ignored) {
            return String.valueOf(System.identityHashCode(m));
        }
    }

    /** 把曲目推进有界最近历史环。 */
    private static void remember(Music m) {
        String k = key(m);
        recent.remove(k);          // 已在则挪到队尾（LRU）
        recent.addLast(k);
        while (recent.size() > HISTORY_MAX) recent.pollFirst();
    }
}
