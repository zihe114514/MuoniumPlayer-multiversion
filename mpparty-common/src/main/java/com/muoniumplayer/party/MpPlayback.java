package com.muoniumplayer.party;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.ncm.music.RoomAutomix;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 房间指令与本机播放器之间的那一层：把服务端下发的「播这首、跳到这里、暂停」翻译成
 * 播放器现有的调用，不新开第二套播放通路。
 *
 * <p>刻意复用 {@link CloudMusic#play(java.util.List, int)}：下载、容器识别、AAC 转码、
 * 封面、歌词、灵动岛全都挂在那条链路上，绕过它就等于在房间里丢掉播放器一半的功能。
 * 房间下发的链接通过 {@code Music#setPartyStreamUrl} 注入，取流时优先于本机各源解析 ——
 * 会员成员解析出的完整音频，非会员成员也能听到同一条流。
 *
 * <p>起播位置不能在调用 {@code play} 时就设：那一刻还没解析链接、没下载、没解码。
 * 因此这里只记一个「待应用」标记，由 {@link #tick()} 在播放器真的可用之后再对齐到
 * 房间当前时钟（而不是当初那个已经过期的位置）。
 */
public final class MpPlayback {

    private MpPlayback() {
    }

    // 漂移阈值不再写死在这里：软／硬阈值、确认次数、冷却全在 MpSyncPolicy，由服务端 hello
    // 统一下发，保证同一房间里所有客户端（含 modernMP）的纠偏口径一致。

    /** 一首歌从「开始起播」到「播放器可用」的等待上限（解析 + 下载 + 转码）。 */
    private static final long START_TIMEOUT_MS = 120_000L;

    /** 房间当前曲目对应的 Music 实例；本地误起播的拦截也靠它做身份判断。 */
    private static volatile Music partyMusic;
    /** 正在等「解析+下载+解码」完成，等到了由 {@link #tick()} 对齐一次。 */
    private static volatile boolean startPending;
    private static volatile long pendingSince;

    /** 进房前的播放模式。单曲循环会让本机自己重播，和房间时钟对不上。 */
    private static volatile CloudMusic.PlayMode savedPlayMode;

    /**
     * 房间预告的「下一首」：服务端在换歌之前就把它连直链一起广播过来，好让本机在这首歌还没轮到
     * 之前就把它解析、下载、解码完 —— 这正是房间里能交叠起来的前提。
     */
    private static volatile MpTrack stagedNext;
    /** {@link #stagedNext} 对应的 {@code Music} 实例，同时也就是播放列表里的下一格。 */
    private static volatile Music stagedNextMusic;

    /** 该实例是否就是房间正在放的那一首（供拦截逻辑放行自己的起播）。 */
    public static boolean isPartyMusic(Music music) {
        return music != null && music == partyMusic;
    }

    public static Music currentMusic() {
        return partyMusic;
    }

    /**
     * 起播（或换歌）。
     *
     * <p>曲目<b>完全用房间下发的元数据</b>构造，不再拿 songId 回平台重查一遍。原来那条路是
     * 「{@code MpNetease.songById(numericId)}」，它有两个后果：非数字 id（酷狗 hash）直接被静默
     * 丢掉，什么都不会发生；而 QQ 的数字 id 更糟 —— 会查到一首完全不相干的网易云歌然后照播。
     * 现在不需要本机装有该平台，甚至不需要网易云会话，只要能下载那条直链就行。</p>
     */
    public static void play(final MpTrack track, final String streamUrl, final long posMs, final boolean playing) {
        if (track == null || streamUrl == null || streamUrl.trim().isEmpty()) return;
        if (track.songId == null || track.songId.trim().isEmpty()) return;

        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                Music music;
                try {
                    music = track.toMusic();
                } catch (Throwable throwable) {
                    System.err.println("[MpParty] 构造曲目失败：" + throwable);
                    MpPartyClient.note("无法识别房间下发的歌曲：" + track.display(), true);
                    return;
                }
                music.setPartyStreamUrl(streamUrl);
                music.setPartyStreamType("");
                // 起播链路一旦静默失败（下载好了却没声音）就完全没有痕迹可查，所以这一路
                // 留三条 INFO：拿到元数据、交给播放器、以及 tick() 真正对齐成功。
                System.out.println("[MpParty] 起播 " + track.display() + " key=" + track.key()
                        + " pos=" + posMs + " playing=" + playing + " url=" + brief(streamUrl));

                forceSequentialMode();
                partyMusic = music;
                startPending = true;
                pendingSince = System.currentTimeMillis();

                MpPartyClient.beginRemote();
                try {
                    CloudMusic.play(roomQueue(music), 0);
                } catch (Throwable throwable) {
                    System.err.println("[MpParty] 起播失败：" + throwable);
                    MpPartyClient.note("起播失败：" + track.display(), true);
                    startPending = false;
                } finally {
                    MpPartyClient.endRemote();
                }
            }
        });
    }

    /**
     * 起播时交给播放器的队列：房间当前这一首，外加（服务端已经预告、且本机开着交叠时）
     * 还没轮到的那一首。
     *
     * <p>队列从「一首」变成「两首」是整件事的关键 —— automix 的 {@code peekNextIndex()} 只认
     * 队列，队里没有第二格，它连尝试都不会尝试（这正是房间里从来没有无缝切换的原因）。
     * 播放模式仍是顺序播放，这一点不变。</p>
     */
    private static List<Music> roomQueue(Music music) {
        Music next = stagedNextMusic;
        if (next == null || next == music) return Collections.singletonList(music);
        List<Music> queue = new ArrayList<Music>(2);
        queue.add(music);
        queue.add(next);
        return queue;
    }

    /**
     * 收下房间预告的下一首。
     *
     * <p>由 {@code S_QUEUE} / {@code S_ROOM_STATE} 的 {@code next} 字段驱动。同一首重复预告
     * 直接忽略：服务端每次广播队列都会带上一遍，重复处理会让已经解码好的播放牌反复作废，
     * 结果每次换歌都来不及预置。</p>
     *
     * <p>房间没开交叠（老服务端、或服主把 {@code automixLeadMs} 设成 0）时整个方法短路，
     * 房间行为与今天逐字节一致。</p>
     */
    public static void stageNext(final MpTrack next, final JsonObject plan) {
        if (!RoomAutomix.active()) {
            clearRoomAutomix();
            return;
        }
        MpTrack current = stagedNext;
        String currentUrl = current == null || current.url == null ? "" : current.url;
        String nextUrl = next == null || next.url == null ? "" : next.url;
        // 「同一首、同一条链接、同一份计划」才算重复。计划必须单列：房主分析完会把计划单独
        // 广播一遍，那时曲目和链接都没变 —— 若短路掉，本机就永远等不到房主那份计划
        // （本机不会自己算一条顶上，见 RoomAutomix#awaitingPlan）。
        if (next != null && current != null
                && current.key().equals(next.key()) && currentUrl.equals(nextUrl)
                && RoomAutomix.sameSharedPlan(plan)) {
            return;
        }
        // 没有直链就没有可预下载的东西（服务端只发了元数据，链接会随队列重发再来一次）。
        if (next == null || nextUrl.isEmpty()) {
            clearRoomAutomix();
            return;
        }

        Music music;
        try {
            music = next.toMusic();
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 预置下一首失败：构造曲目异常 " + throwable);
            return;
        }
        music.setPartyStreamUrl(nextUrl);
        music.setPartyStreamType("");

        stagedNext = next;
        stagedNextMusic = music;
        RoomAutomix.arm(music, next.entryId, plan);
        installRoomNext(music);
        System.out.println("[MpParty] 已预置下一首 " + next.display()
                + " key=" + next.key() + " url=" + brief(nextUrl)
                + (plan == null ? "（计划未到：房主算完会单独广播一次，到之前本机不预备）" : " plan=" + plan));
    }

    /**
     * 刷新房间角色与本机的分析能力。
     *
     * <p>房主身份只用于<b>老服务端</b>的兜底：那些服务端不会指派任何人，只能按老规矩让房主
     * 自己算。新服务端会点名（{@code analyze_assign}），届时由指派决定谁算 —— 这也正是
     * 「谁点的歌就用谁的电脑先算」能成立的地方。</p>
     *
     * <p>顺带把本机的分析能力报给 {@code RoomAutomix}，它会随下一次 profile 一起上传，
     * 服务端据此决定要不要把任务派过来。只在身份可能变化时同步（{@code hello}、房间快照、
     * 成员变化），不必每次都问。</p>
     */
    public static void refreshRoomRole() {
        RoomAutomix.setOwner(MpPartyClient.isOwner());
        RoomAutomix.setCapability(AutomixSettings.isEnabled(), capabilityEngine());
    }

    /**
     * 本机自报的分析档位。
     *
     * <p>{@code "model"} = 走 Folia 且模型与运行时都已安装，能算出 stem 交接、过渡族、频段
     * 倾斜这些；{@code "dsp"} = 只有内置信号处理。服务端选人时同优先级里先问档位高的，
     * 所以装了模型的机器会被优先借走。</p>
     *
     * <p>这里刻意<b>不</b>因为「引擎选了 Folia 但模型没装」就报 {@code "model"}：那种情况下
     * Folia 内部会退回纯 DSP，算出来的东西和内置信号处理没区别，谎报只会让服务端把任务
     * 优先派给一台其实没有模型的机器。</p>
     */
    private static String capabilityEngine() {
        try {
            if (!AutomixSettings.isFoliaEngine()) return "dsp";
            // 两套权重缺一不可：Beat This! 出节拍栅格，HTDemucs 出 stem 分离。
            boolean beats = FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS);
            boolean stems = FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS);
            boolean runtime = FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME);
            return beats && stems && runtime ? "model" : "dsp";
        } catch (Throwable ignored) {
            // 探不到就按最低档报：宁可少被借，也不要被派了却给不出东西。
            return "dsp";
        }
    }

    /** 房间预告的下一首；没有则为 null。 */
    public static MpTrack stagedNext() {
        return stagedNext;
    }

    /**
     * 换歌帧到了：本机若已经把这一首交叠进来，就只承认它，不要再起播一次。
     *
     * <p>两种次序都要认。<b>帧先到</b>：请播放线程用已预备好的播放牌立刻交接
     * （{@link CloudMusic#roomAutomixHandover()}）。<b>本机先到</b>：本机的交叠点与服务端的
     * 提前换歌点本来就是同一个数，本地已经淡入了，此时再走一遍 {@link CloudMusic#play} 会整条
     * 替换播放列表，把刚叠上的尾巴整段掐掉 —— 这正是「不做这个保护就会尾巴被掐」的那一处。</p>
     *
     * @return true 表示这一帧已经被交叠链路消化，调用方不要再起播
     */
    public static boolean handoverToRoomFrame(final MpTrack track, final long posMs, final boolean playing) {
        final MpTrack staged = stagedNext;
        final Music music = stagedNextMusic;
        if (staged == null || music == null) return false;
        if (track == null || !staged.key().equals(track.key())) return false;

        boolean blendedLocally = CloudMusic.currentlyPlaying == music;
        if (!blendedLocally && !CloudMusic.roomAutomixHandover()) return false;

        partyMusic = music;
        stagedNext = null;
        stagedNextMusic = null;
        RoomAutomix.disarm();
        // 交叠这一段本机与房间时钟「故意」不同（一条在淡出，另一条刚从入口点淡进来），
        // 关掉漂移判定，别让心跳把刚开始的淡入硬跳一下。窗按这段交叠还剩多长来开 ——
        // 固定 4~5 秒盖不住 Folia 那种二十秒的长尾奏，而淡入没结束就恢复心跳正是「截断」的
        // 来源。帧比本机先到时交叠还没起（交接要等播放线程那一 tick），那里读到 0，
        // 但随后每一发心跳都会用剩下的淡入长度把窗口续上（见 decideSeek）。
        MpSyncPolicy.markCommand(CloudMusic.roomBlendRemainingMillis() + MpSyncPolicy.BLEND_SETTLE_MS);
        System.out.println("[MpParty] 房间内交叠交接 " + track.display()
                + (blendedLocally ? "（本机先到，帧后到）" : "（帧触发）")
                + " pos=" + posMs + " playing=" + playing
                // 这段保护到底盖住了多长的交叠，真机日志里就靠这一行看：开短了就是「淡入还没
                // 结束就被心跳薅断」，也是用户报的那个截断唯一能被事后确认的证据。
                + " 漂移静默=" + MpSyncPolicy.graceRemainingMs() + "ms");
        return true;
    }

    /** 撤下房间的下一首与交叠状态（离房、房间停了、房间关掉交叠）。 */
    public static void clearRoomAutomix() {
        stagedNext = null;
        stagedNextMusic = null;
        RoomAutomix.disarm();
        installRoomNext(null);
    }

    /**
     * 把还没落到队列里的「下一首」补装一次。
     *
     * <p>换歌帧先到时，起播那一刻队列里还只有一首，「下一首」要等随后的队列广播才到 —— 那时
     * 房间这一首多半已经在播了，只有这里补装才能把预解码接上去。接上之后
     * {@link CloudMusic#setRoomNextTrack} 会自行判定「已经是这一首」而不再动队列，所以每秒
     * 来一次没有代价。</p>
     */
    private static void installStagedRoomNext() {
        Music next = stagedNextMusic;
        if (next == null || !RoomAutomix.active()) return;
        installRoomNext(next);
    }

    /**
     * 把下一首接进播放器队列当前曲目之后（传 {@code null} 则撤下来）。
     *
     * <p>只有房间这一首<b>确实在播</b>时才动队列：房间的下一首可能在起播之前就到了，那时队列
     * 还不属于房间，交给起播时的 {@link #roomQueue(Music)} 带上更安全。</p>
     */
    private static void installRoomNext(Music music) {
        if (music != null && !RoomAutomix.active()) return;
        Music playing = partyMusic;
        if (playing == null || CloudMusic.currentlyPlaying != playing) return;
        try {
            CloudMusic.setRoomNextTrack(music);
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 预置下一首失败：" + throwable);
        }
    }

    public static void pause(final long posMs) {
        MpSyncPolicy.markCommand();
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = usablePlayer();
                if (player == null) return;
                player.setPlaybackTime(posMs);
                if (!player.isPausing()) player.pause();
            }
        });
    }

    public static void resume(final long posMs) {
        MpSyncPolicy.markCommand();
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = usablePlayer();
                if (player == null) return;
                player.setPlaybackTime(posMs);
                if (player.isPausing()) player.unpause();
            }
        });
    }

    public static void seek(final long posMs) {
        MpSyncPolicy.markCommand();
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer player = usablePlayer();
                if (player != null) player.setPlaybackTime(posMs);
            }
        });
    }

    /**
     * 房间停了（队列播完 / 离开 / 解散）：停下房间那一路播放，把播放模式还回去。
     *
     * <p>原来这里只是 {@code pause()}，于是房间放完最后一首之后，播放条上还挂着那首歌的名字和
     * 一条停住的进度条，点了也不播 —— 房间是这一路播放的所有者，它停了就没有「下一首」，界面
     * 必须回到「未在播放」。
     *
     * <p>收尾要 join 播放线程（最多 1 秒），不能放在渲染线程上；起播那一路本来也是异步的。</p>
     */
    public static void stop() {
        startPending = false;
        // 队列里多出来的那一格是房间的下一首，房间停了就得收回去 —— 否则它会被当成
        // 本机自己的队列继续往下播。必须在清 partyMusic 之前做（撤销要以「房间这一首
        // 正在播」为判据）。
        clearRoomAutomix();
        final Music music = partyMusic;
        partyMusic = null;
        restorePlayMode();
        if (music == null) return;
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                // 带上身份校验：房间的停止指令可能比用户手动点的下一首晚到，那种情况下什么都
                // 不该动。
                CloudMusic.stopPlaybackAndClear(music);
            }
        });
    }

    /**
     * 服务端时钟帧：只纠偏，不重启。
     *
     * <p>暂停/播放状态也在这里兜底 —— 单个 pause/resume 帧可能在起播还没完成时到达，
     * 那一帧就只能靠随后的 time 帧把状态补齐。
     */
    public static void time(final long posMs, long durationMs, final boolean playing) {
        if (startPending) return;             // 起播还没完成，等 tick() 一次性对齐
        AudioPlayer player = usablePlayer();
        if (player == null) return;

        final boolean pausing = player.isPausing();
        final long local = (long) player.getCurrentTimeMillis();

        // 播放状态本身永远要跟上（暂停/恢复帧可能在起播还没完成时就到了，只能靠心跳补齐），
        // 位置纠偏则走三档判定。
        final boolean stateWrong = pausing == playing;
        final boolean needSeek = decideSeek(playing, pausing, local, posMs);
        if (!needSeek && !stateWrong) return;

        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer active = usablePlayer();
                if (active == null) return;
                if (needSeek) active.setPlaybackTime(posMs);
                if (playing && active.isPausing()) active.unpause();
                if (!playing && !active.isPausing()) active.pause();
            }
        });
    }

    /**
     * 这一帧心跳要不要硬跳播放头。
     *
     * <p>暂停时位置是死的，不存在「越对越乱」，超过软阈值就直接对齐。正在播放时先过两道
     * 「这一刻量不出脱节」的闸 —— 交叠进行中、以及播放头冻住的卡流 —— 过了才交给
     * {@link MpSyncPolicy#shouldHardSeek}：只有连续多次超硬阈值、且过了冷却，才真的跳一次；
     * 软档（软阈值~硬阈值）刻意什么都不做 —— 宁可差几秒，也不要为了对齐而反复扯动播放头。</p>
     */
    private static boolean decideSeek(boolean playing, boolean pausing, long local, long posMs) {
        long drift = local - posMs;
        long abs = drift < 0L ? -drift : drift;
        if (!playing) return abs > MpSyncPolicy.softDriftMs();
        // 房间在放而本机还是暂停态：先把播放按下去，这一帧不动位置（下面会 unpause），
        // 让它跑起来再谈对齐。
        if (pausing) {
            MpSyncPolicy.markCommand();
            return false;
        }
        // 交叠还没淡完：本机与房间时钟在这段里「故意」不同 —— 一条在淡出、另一条刚从入口点
        // 淡进来，而本机开始淡入的时刻天然比房间晚几秒（帧延迟 + 一条落在软档里没纠的漂移 +
        // 新播放牌的缓冲）。这段读数拿去比硬阈值，跳的就是刚叠上的那一条，听到的正是「交叠被
        // 截断」。窗口按剩下的淡入长度开，淡完再多给一段落地时间。
        long blend = CloudMusic.roomBlendRemainingMillis();
        if (blend > 0L) {
            MpSyncPolicy.markCommand(blend + MpSyncPolicy.BLEND_SETTLE_MS);
            return false;
        }
        // 播放头冻着不走：那是没取到数据，不是落后。这一发不计入确认次数，也不跳（跳只会
        // 重开解码器、把缓冲时间再拉长一轮）；连冻约 6 秒后策略自己会放行，那时重开正是
        // 唯一自救手段，见 MpSyncPolicy#isStalled。
        if (MpSyncPolicy.isStalled(local)) {
            MpSyncPolicy.markCommand();
            return false;
        }
        if (MpSyncPolicy.inGrace()) return false;
        return MpSyncPolicy.shouldHardSeek(drift);
    }

    /**
     * 每个心跳周期调一次：把「已经起播完成」的那一首对齐到房间此刻的位置。
     *
     * <p>对齐用的是 {@link MpPartyClient#positionMs()} 而不是当初 {@code play} 帧里的位置：
     * 解析 + 下载 + 转码可能过去了十几秒，用旧值会一起播就落后。
     */
    public static void tick() {
        // 服务端的队列广播与换歌帧谁先到是不定的：帧先到时起播那一刻队列里还只有一首，
        // 「下一首」要等队列广播才到，而那时房间这一首多半已经在播了 —— 在这里补装一次。
        installStagedRoomNext();
        if (!startPending) return;

        if (System.currentTimeMillis() - pendingSince > START_TIMEOUT_MS) {
            startPending = false;
            MpPartyClient.note("起播超时，已放弃与房间对齐（等房主切下一首会自动重试）", true);
            return;
        }

        AudioPlayer player = CloudMusic.player;
        Music music = partyMusic;
        if (player == null || !player.isUsable() || music == null
                || CloudMusic.currentlyPlaying != music || player.getTotalTimeMillis() <= 0.0f) {
            return;
        }

        startPending = false;
        // 这是一次明确的对齐（刚起播完成），随后给一段静默期让缓冲跑起来，别马上又量漂移。
        MpSyncPolicy.markCommand();
        final long target = MpPartyClient.positionMs();
        final boolean playing = MpPartyClient.playing();
        System.out.println("[MpParty] 已就绪，对齐到 " + target + "/"
                + (long) player.getTotalTimeMillis() + " playing=" + playing
                + " pausing=" + player.isPausing() + " vol=" + player.volume
                + " fade=" + player.getFadeGain());
        onMainThread(new Runnable() {
            @Override
            public void run() {
                AudioPlayer active = usablePlayer();
                if (active == null) return;
                if (target > 0L) active.setPlaybackTime(target);
                if (playing && active.isPausing()) active.unpause();
                if (!playing && !active.isPausing()) active.pause();
            }
        });
    }

    public static long localPositionMs() {
        AudioPlayer player = usablePlayer();
        return player == null ? 0L : (long) player.getCurrentTimeMillis();
    }

    public static boolean localPlaying() {
        AudioPlayer player = usablePlayer();
        return player != null && !player.isPausing();
    }

    /** 房间正在放的那一首对应的播放器，其它情况一律 null（绝不动用户自己的播放）。 */
    private static AudioPlayer usablePlayer() {
        Music music = partyMusic;
        AudioPlayer player = CloudMusic.player;
        if (music == null || player == null || !player.isUsable()) return null;
        return CloudMusic.currentlyPlaying == music ? player : null;
    }

    /** 日志里只留链接的路径部分：网易云的签名参数又长又没有诊断价值。 */
    private static String brief(String url) {
        if (url == null) return "-";
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static void onMainThread(Runnable task) {
        try {
            MultiThreadingUtil.runOnMainThread(task);
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 主线程任务提交失败：" + throwable);
        }
    }

    /**
     * 房间里本机不能自己循环：单曲循环会在房间换歌之前把同一首重播一遍。
     * 顺序播放下队列只有一首，播完就停，正好等服务端下发下一首。
     */
    private static void forceSequentialMode() {
        if (savedPlayMode != null) return;
        CloudMusic.PlayMode mode = CloudMusic.playMode;
        if (mode == CloudMusic.PlayMode.Sequential) return;
        savedPlayMode = mode;
        CloudMusic.playMode = CloudMusic.PlayMode.Sequential;
    }

    private static void restorePlayMode() {
        CloudMusic.PlayMode mode = savedPlayMode;
        savedPlayMode = null;
        if (mode != null) CloudMusic.playMode = mode;
    }
}
