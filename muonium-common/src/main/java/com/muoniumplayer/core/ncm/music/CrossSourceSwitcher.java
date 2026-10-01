package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.kugou.KuGouDevice;
import com.muoniumplayer.core.ncm.lxsource.LxRuntime;
import com.muoniumplayer.core.ncm.lxsource.LxSourceService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨平台换源：一首歌在自己的链路上彻底取不到流之后，拿曲名 / 艺人去别的平台重新匹配
 * 一条<b>真正的</b>曲目替换播放。
 *
 * <h3>它按谁的流程做的</h3>
 * 包括它的每一个关键设计：</p>
 * <ol>
 *   <li><b>两段式漏斗</b>：先并行搜索 + 便宜打分，最后才对少量头部候选做昂贵的取流验证；</li>
 *   <li><b>打分</b>：曲名归一化（小写 + 只留字母数字汉字）后按 Levenshtein 相似度，
 *       权重 0.7，艺人 0.3；艺人缺失时只按曲名；</li>
 *   <li><b>时长门槛</b>：差 ≤10 秒或 ≤原时长 15% 才收 —— 这是防「同名翻唱 / 现场版」的主闸门，
 *       比解灰链的 ±5 秒宽松一档，因为这里搜的是正版跨平台，版本差更常见；</li>
 *   <li><b>高置信提前返回</b>：任何源的搜索先完成且头部候选是「相似度 ≥0.98、时长差 ≤3 秒、
 *       验证可播」，立即收工，不等慢源；</li>
 *   <li><b>并行验证、按排名录取</b>：否则等全部源汇合，对 top-12 做 6 路并行的取流验证，
 *       但录取顺序仍是排名顺序，避免「次优的先返回就用了次优」。</li>
 * </ol>
 *
 * <h3>与解灰（{@link NeteaseGreyUnblocker}）的分工</h3>
 * <p>解灰保住的是「这条网易云曲目的身份」，换的是取流地址；换源换的是曲目本身 —— 换过去之后
 * 封面、歌词、收藏、最近播放全部按新平台的真曲目走。所以自动触发点排在解灰<b>之后</b>。</p>
 *
 * <p>徽标说的是<b>落点</b>，两种前缀分开：跨平台换源换来的曲目 → 「已换源 · 网易云」或
 * 「已换源 · 野花·kw」；本平台自己解出来的第三方流（解灰 / 脚本）→ 「解灰 · 野花·wy」。
 * 徽标内容由 {@link #destinationBadge} 定、由 {@link #propagateUnblockLabel} 在录取点写好，
 * 行对象与替身对象各一份 —— 界面上「换到了哪家」必须一眼读得出，否则换源成功与换源失败
 * 在歌曲行上是同一个样子。</p>
 *
 * <h3>触发半径：所有源，四种失败</h3>
 * <p>这里不区分歌来自哪个平台，自动换源在四种「放不出来」之后兜底：
 * ① {@code Music.getPlayUrl()} 整条链路返回 null（B 站 / 汽水 / GD / 酷狗 / QQ / YouTube /
 * LX 自定义源各自失败，或网易云的官方链路 + 解灰全扑空）；② 直链拿到了但下载 / 字节校验 /
 * 解码失败（CDN 链接过期、上游交付非音频载荷），且平台内兜底（网易云标准 MP3、GD 跨源）
 * 也用完；③ 流能解出、能出声，但解码器报出的真实时长远短于曲目声明 —— 非会员拿到的
 * 30/60 秒试听片段就是这个形状，与其放半首不如去别的平台找完整的；④ 同一个试听形状出现在
 * automix 的<b>预备</b>阶段 —— 「下一曲」在 {@code armTrack} 里就被解码并预载，根本走不到
 * playSong，前三条触发谁也看不见它，于是预备线程对解码器真实时长做同一判定，是试听就丢掉
 * 这张牌、就地换掉队列里那一格并 bump {@code queueRevision} 让旧 deck 作废。
 * ①②③ 都在 {@code PlayThread.playSong} 上，④ 在 BackgroundWork 预备线程上，
 * 靠 {@link #AUTO_ATTEMPTED} 保证每首每进程最多试一次。</p>
 *
 * <h3>护栏</h3>
 * <ul>
 *   <li>候选平台固定为 网易云 / QQ / 酷狗 / 酷我 / 咪咕 / GD / B 站 / 汽水，当前平台自动
 *       排除。YouTube 刻意不进候选：依赖代理，换过去大概率还是死的，白费一次搜索额度。
 *       汽水排在最末：上游对完整版有风控门，很多账号即使有会员也取不到完整流。</li>
 *   <li><b>自定义源渠道内互备</b>：一份 LX 脚本往往同时声明 kw/wy/mg/tx/kg 多个渠道，
 *       上游 lx-music-desktop 里这些渠道天然互为备份（一个渠道取流死了就换同脚本的别的
 *       渠道重搜）。这里对齐这个行为：酷我与咪咕作为候选进表，但它们<b>只有脚本声明了对应
 *       渠道才可播</b>（{@link #lxCovers}），宿主只内置了两家照抄上游 musicSdk 的搜索，
 *       没有本家取流链路 —— 脚本没启用或没声明 kw/mg 时它们根本进不了候选表。</li>
 *   <li><b>要会员的平台没会员就不试</b>（{@link #hasVipAccess}）：非会员在那些平台多半只有
 *       试听流，搜回来也验证不出完整曲目。网易云查 profile.vipType、酷狗查凭证 vip_type、
 *       汽水查会员标记（都是登录时落好的状态，不碰网络），QQ 上游没有可查的会员字段、
 *       以登录为门槛；GD 音乐台与 B 站不卖会员，不受这条限制。<b>但只要 LX 自定义源启用
 *       且声明能吃下该平台（{@link #lxCovers}），没有本家会员也放行</b> —— 换过去的候选
 *       取流时脚本优先，等于借脚本的免费通道拿完整版。</li>
 *   <li>搜索响应没有时长的源（GD），或任何时长信息缺失的配对，相似度门槛抬到 0.85 才收 ——
 *       没有时长闸门兜底时宁可错杀。</li>
 *   <li>GD 有额度与健康度门（同解灰）：额度不足或处于冷却就整个跳过，不挤兑共享池。</li>
 *   <li>自动触发每首歌每进程只试一次（{@link #AUTO_ATTEMPTED}），失败结论缓存 10 分钟
 *       （{@link #NEGATIVE_CACHE}），不会每次点击都重烧整张候选表的额度。</li>
 *   <li>一起听（官方房间）与 party 房间内不做<b>自动</b>换源：房间按曲目本体同步，
 *       本地悄悄换一首会让整房脱队。</li>
 *   <li>每个源的搜索有 6 秒硬超时，整条流水线有墙钟上限；全部网络都发生在
 *       Play Thread 或本类自己的守护线程池上，绝不碰渲染线程。</li>
 * </ul>
 */
public final class CrossSourceSwitcher {

    /** 候选平台，按优先级排序；当前平台在收集时被剔除。 */
    private static final MusicPlatform[] CANDIDATE_ORDER = {
            MusicPlatform.NETEASE,
            MusicPlatform.QQ,
            MusicPlatform.KUGOU,
            // 酷我 / 咪咕是「自定义源渠道」：只有脚本声明了 kw / mg 才能进候选
            // （hasVipAccess 对它们没有本家会员可查，唯一放行路是 lxCovers）。
            // 上游 lx 的一个自定义源里各渠道互为备份，kg 死了换 kw/mg 正是它的原生玩法。
            MusicPlatform.KUWO,
            MusicPlatform.MIGU,
            MusicPlatform.GD,
            MusicPlatform.BILIBILI,
            // 垫底：汽水上游对完整版有风控门，很多账号即使有会员也取不到完整流，
            // 前面的源都试过再轮到它。LX 自定义源不是一行候选而是一个通道（见 lxCovers），
            // 它覆盖的网易云 / QQ / 酷狗天然坐在各自平台的位次上，优先级高于 B 站。
            MusicPlatform.QISHUI,
    };

    static final int MAX_CANDIDATES_PER_SOURCE = 8;
    static final long SOURCE_SEARCH_TIMEOUT_MILLIS = 6_000L;
    static final double HIGH_CONFIDENCE_SCORE = 0.98;
    /** 高置信提前返回还要求时长差 ≤3 秒。 */
    static final long HIGH_CONFIDENCE_DURATION_DELTA_MILLIS = 3_000L;
    static final int VALIDATION_LIMIT = 12;
    static final int VALIDATION_PARALLELISM = 6;
    static final long DURATION_CLOSE_ABS_MILLIS = 10_000L;
    /** 时长按比例放行的上限（15%），小于一律按绝对值。 */
    static final double DURATION_CLOSE_RATIO = 0.15;
    /** 任何一侧时长未知时，相似度必须过这条线。 */
    static final double MIN_SCORE_WITHOUT_DURATION = 0.85;
    /** 每源搜索取回的结果条数。 */
    static final int SEARCH_LIMIT = 10;
    /** 一次 GD 尝试 = 一次搜索 + 一到多次取流，额度不够就整个跳过。 */
    static final int GD_MIN_BUDGET = 12;
    /** 「试听片段」判定的最低声明时长：短过这条线的曲目本来就可能就这么长，不能起疑。 */
    static final long TRIAL_CHECK_MIN_DECLARED_MILLIS = 90_000L;
    /** 解码出的真实时长不足声明时长这个比例才可疑（70%）。 */
    static final double TRIAL_CHECK_MAX_RATIO = 0.7;
    /** 且绝对缺口不少于这个值（30 秒）；元数据的小误差绝不能触发换源。 */
    static final long TRIAL_CHECK_MIN_GAP_MILLIS = 30_000L;

    private static final long NEGATIVE_CACHE_MILLIS = 10L * 60L * 1000L;
    private static final int GUARD_SET_LIMIT = 512;
    /** 整条流水线的墙钟上限：整张候选表的搜索 + 头部验证，必须在上游直链过期前收手。 */
    private static final long PIPELINE_DEADLINE_MILLIS = 24_000L;

    private static final Map<String, Long> NEGATIVE_CACHE =
            new ConcurrentHashMap<String, Long>();
    private static final Set<String> AUTO_ATTEMPTED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "CrossSourceSwitcher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private CrossSourceSwitcher() {
    }

    // ── 对外入口 ────────────────────────────────────────────────────────────

    /** 一次换源的结果：新曲目本体 + 已验证可播的直链 + 匹配度。 */
    public static final class Match {
        public final Music music;
        public final Tuple<String, String> url;
        public final double score;
        public final String platformLabel;

        Match(Music music, Tuple<String, String> url, double score, String platformLabel) {
            this.music = music;
            this.url = url;
            this.score = score;
            this.platformLabel = platformLabel;
        }
    }

    /**
     * 换源录取后把<b>落点</b>写进徽标：替身与行对象两个都写。
     *
     * <p>播放队列是面板列表的浅拷贝（{@code new ArrayList<>(songs)}），已建好的行 widget 绑定的
     * 还是老对象；而第三方流的徽标（{@code Music.getUnblockedSourceLabel()}）在验证关
     * {@code resolveLxPlayUrl} 时只写进了替身。两个都写才两头都看得见：行 widget 那一侧靠回写，
     * 面板重建 / 全屏播放页那一侧读的是替身自己。</p>
     *
     * <p>文案由 {@link #destinationBadge} 定：脚本流是「脚本名·渠道键」（例：{@code 野花·kw}），
     * 官方流是平台显示名（例：{@code 网易云}）。之所以必须自己算而不是「照抄替身那一跳留下的值」：
     * 官方链路根本不会写这个字段，换源换到某平台的官方完整流时它是空串，界面上就与
     * 「没换成」长得一模一样。无条件覆写：行对象自己那一跳留下的旧徽标同样要改掉，
     * 现在播的已经不是它了。</p>
     *
     * <p>同时把两个对象都标成「换源换来的」（{@code Music.markCrossSourceSwitched}），
     * 前缀由此从「解灰 · 」变成「已换源 · 」—— 换的是曲目本身，与「只换一条取流地址」不是一回事。</p>
     */
    public static void propagateUnblockLabel(Music replaced, Music replacement) {
        if (replaced == null || replacement == null) return;
        String badge = destinationBadge(replacement);
        replacement.setUnblockedSourceLabel(badge);
        replaced.setUnblockedSourceLabel(badge);
        // 前缀要说的是「换了一首歌」而不是「换了一条取流地址」：这一位只有这里会置位。
        replacement.markCrossSourceSwitched();
        replaced.markCrossSourceSwitched();
    }

    /**
     * 换源落点的徽标文案：脚本流保持「脚本名·渠道键」，官方流写平台显示名。
     *
     * <p>{@code MusicWidget} 与全屏页把这一位拼在前缀之后（换源换来的那一首前缀是「已换源 · 」），
     * 一行读完就知道声音出自哪里。平台名用 {@link MusicPlatform#getDisplayName()} 而不是枚举名，
     * 「网易云」是给玩家看的词。</p>
     */
    static String destinationBadge(Music replacement) {
        if (replacement == null) return "";
        String label = replacement.getUnblockedSourceLabel().trim();
        if (!label.isEmpty()) return label;
        // getSource() 不会返回 null（字段缺失时它自己退回网易云），这一层判空只是给未来改坏它的人兜底。
        MusicPlatform source = replacement.getSource();
        return source == null ? "" : source.getDisplayName();
    }

    /**
     * 自动换源的前置判定：开关开着、不是房间里、这首歌还没试过、也没有近期失败结论。
     *
     * <p>只在 Play Thread 上问；返回 true 之后调用方必须紧接着走
     * {@link #findReplacementForAuto}，否则「每首都只试一次」的账就对不上。</p>
     */
    public static boolean canAutoSwitch(Music current) {
        return canAutoSwitch(current, false);
    }

    /**
     * 同上，但允许调用方说明「这次换的<b>不是本机那一条</b>」。
     *
     * <p>{@code allowInRoom} 只放开最后那一道「在 mp-party 房间里就不换」的闸，因为换源的结果
     * 不是偷偷改本机这一首，而是一条 {@code track_replace} —— 服务端改的是<b>队列里那一格</b>，
     * 全房跟着一起换，不存在脱队。其余闸门一个都不放：</p>
     * <ul>
     *   <li>网易云 / 酷狗的<b>官方</b>一起听照旧拦：那两个房间系统按曲目本体（或 hash）同步，
     *       本机的队列根本不受我们控制，换了就是本机与其他人听不同的歌；</li>
     *   <li>用户那个「自动换源」总开关照旧管着：它管的是「愿不愿意为放不出来去别的平台搜一圈」，
     *       与在不在房间里无关；</li>
     *   <li>候选表为空（要会员的平台都没会员、脚本也没覆盖）照旧不发。</li>
     * </ul>
     *
     * <p>{@link #AUTO_ATTEMPTED} 与 10 分钟负缓存对这条路同样生效：同一首歌本机刚搜过一遍还没找到，
     * 房间里再搜一遍也搜不出什么，那一格的救援直接放弃更省额度。</p>
     */
    public static boolean canAutoSwitch(Music current, boolean allowInRoom) {
        if (current == null) return false;
        if (!SwitchSourceSettings.isEnabled()) return false;
        if (candidatePlatforms(current.getSource()).isEmpty()) return false;
        // 一起听 / party 房间按曲目本体同步，本地悄悄换一首就是整房脱队。
        if (NeteaseListenTogetherService.isActive()) return false;
        // 酷狗的官方一起听同理：房间里换源会让本机的曲目与其它成员不是同一首，
        // 而酷狗房间是按 hash 同步的，换源得到的 hash 也不同。
        if (KuGouListenTogetherService.isActive()) return false;
        if (!allowInRoom && MpPartyClient.inRoom()) return false;
        String key = current.getStableKey();
        if (key == null || key.isEmpty()) return false;
        if (AUTO_ATTEMPTED.contains(key)) return false;
        return !isNegativelyCached(key, System.currentTimeMillis());
    }

    /**
     * 本机到底有没有<b>跨平台换源这条流水线</b>（房间救援 {@code switch_rescue} 的前提）。
     *
     * <p>只看那个总开关：候选平台够不够、这首歌搜过没搜过、有没有失败结论，都是<b>逐曲目</b>
     * 的判据（{@link #canAutoSwitch}），而档案位要回答的是「这台机器值不值得服务端专门问一句」。
     * 服务端为收到救援帧的那一首会多等一个救援窗，问错人就是把整房晾在那儿。</p>
     */
    public static boolean rescueCapable() {
        return SwitchSourceSettings.isEnabled();
    }

    /** 自动路径：记一次「试过」，再跑流水线。只能在 {@link #canAutoSwitch} 为 true 后调用。 */
    public static Match findReplacementForAuto(Music current, Quality quality) {
        String key = current == null ? null : current.getStableKey();
        if (key != null && !key.isEmpty()) {
            if (AUTO_ATTEMPTED.size() > GUARD_SET_LIMIT) AUTO_ATTEMPTED.clear();
            AUTO_ATTEMPTED.add(key);
        }
        return findReplacement(current, quality);
    }

    /**
     * 手动换源（歌曲行右键）：不受自动开关约束，成功后把播放列表里这一条换成新曲目并刷新面板。
     *
     * <p>全部网络在后台线程；列表替换与提示回投主线程。同一首歌在结果回来之前重复右键会被
     * {@link #IN_FLIGHT} 吃掉。</p>
     */
    public static void requestManualSwitch(final Music current, final PlayList playList, final int index) {
        if (current == null || playList == null) return;
        final String key = current.getStableKey();
        if (key == null || key.isEmpty()) return;
        if (candidatePlatforms(current.getSource()).isEmpty()) {
            DownloadDynamicIsland.showCrossSourceFailure(current.getName(),
                    "要会员的平台都没有会员（LX 自定义源也未覆盖），免费候选（GD / B 站）也已用尽");
            return;
        }
        if (IN_FLIGHT.size() > GUARD_SET_LIMIT) IN_FLIGHT.clear();
        if (!IN_FLIGHT.add(key)) return;

        DownloadDynamicIsland.showCrossSourceSearching(current.getName());
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                Match match = null;
                try {
                    match = findReplacement(current, CloudMusic.quality);
                } catch (Throwable failure) {
                    System.err.println("[Music/Switch] 手动换源流水线异常 " + key + ": " + failure);
                }
                final Match result = match;
                MultiThreadingUtil.runOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        IN_FLIGHT.remove(key);
                        if (result == null) {
                            DownloadDynamicIsland.showCrossSourceFailure(current.getName(),
                                    "候选源没有匹配到可播放版本");
                            return;
                        }
                        try {
                            List<Music> musics = playList.getMusics();
                            if (musics != null && index >= 0 && index < musics.size()) {
                                musics.set(index, result.music);
                                // 队列格子换了人：automix 里按旧曲目预备好的那张牌（整条解码链 +
                                // 交接计划）必须作废，否则下一曲照样交接给换源前的旧流。
                                CloudMusic.queueRevision++;
                            }
                        } catch (Throwable ignored) {
                            // 列表正被面板遍历：替换失败也不该弄丢这次的换源结果提示。
                        }
                        // 行 widget 绑的是老对象（列表 set 也换不掉已建好的 widget）：
                        // 替身是脚本解出来的流时，把徽标回写过去，界面这行才看得到。
                        propagateUnblockLabel(current, result.music);
                        DownloadDynamicIsland.showCrossSourceSwitched(result.music.getName(),
                                result.platformLabel);
                        try {
                            NCMScreen.getInstance().reloadCurrentPanel();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        });
    }

    // ── 流水线 ──────────────────────────────────────────────────────────────

    /**
     * 阻塞式地给 {@code current} 找一条别的平台上的可播替身。只能在后台线程调用。
     *
     * @return 找到就返回替身（直链已经过验证）；没有任何合格匹配时返回 {@code null}
     */
    public static Match findReplacement(Music current, Quality quality) {
        if (current == null) return null;
        String title = NeteaseGreyUnblocker.cleanTitle(current.getName());
        if (title.isEmpty()) return null;
        String artist = NeteaseGreyUnblocker.keywordArtists(current);
        if (artist.isEmpty()) artist = safe(current.getArtistsName()).trim();
        String keyword = NeteaseGreyUnblocker.searchQuery(title, artist);
        long durationMillis = current.getDuration();
        final Quality requestedQuality = quality == null ? CloudMusic.quality : quality;

        final List<MusicPlatform> platforms = candidatePlatforms(current.getSource());
        if (platforms.isEmpty()) return null;

        final long deadline = System.currentTimeMillis() + PIPELINE_DEADLINE_MILLIS;
        List<Future<SourceHits>> futures = new ArrayList<Future<SourceHits>>();
        for (final MusicPlatform platform : platforms) {
            final String searchKeyword = keyword;
            final String searchTitle = title;
            final String searchArtist = artist;
            final long originalDuration = durationMillis;
            futures.add(POOL.submit(() -> {
                List<Music> results = searchPlatform(platform, searchKeyword, searchTitle, searchArtist);
                List<Candidate> candidates = scoreCandidates(current, results, platform,
                        originalDuration, searchTitle, searchArtist);
                return new SourceHits(platform, candidates);
            }));
        }

        List<Candidate> pool = new ArrayList<Candidate>();
        // 按完成顺序收割：哪个源先回来就先拿它的头部候选做高置信判定，慢源不拖全场。
        for (int index = 0; index < futures.size(); index++) {
            if (Thread.currentThread().isInterrupted()) break;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) break;
            SourceHits hits = awaitHits(futures.get(index),
                    Math.min(remaining, SOURCE_SEARCH_TIMEOUT_MILLIS * 2));
            if (hits == null || hits.candidates.isEmpty()) continue;
            sortCandidates(hits.candidates);
            pool.addAll(hits.candidates);

            Candidate best = hits.candidates.get(0);
            if (isHighConfidence(best, durationMillis)) {
                Tuple<String, String> stream = tryPlayable(best.music, requestedQuality);
                if (stream != null) {
                    return new Match(best.music, stream, best.score, platformLabel(best.platform));
                }
            }
        }

        if (pool.isEmpty()) {
            markNegative(current);
            return null;
        }
        sortCandidates(pool);
        Match match = validateInRankOrder(pool, requestedQuality, deadline);
        if (match == null) markNegative(current);
        return match;
    }

    /** 汇合后的头部并行验证：验证窗口最多 6 路在途，但录取顺序仍是排名顺序。 */
    private static Match validateInRankOrder(List<Candidate> ranked, Quality quality, long deadline) {
        int limit = Math.min(VALIDATION_LIMIT, ranked.size());
        List<Candidate> head = ranked.subList(0, limit);
        Tuple<String, String>[] streams = new Tuple[limit];

        LinkedList<WindowedCheck> window = new LinkedList<WindowedCheck>();
        for (int index = 0; index < limit; index++) {
            if (System.currentTimeMillis() > deadline) break;
            final Music candidate = head.get(index).music;
            window.add(new WindowedCheck(index, POOL.submit(() -> tryPlayable(candidate, quality))));
            // 领先出队只限流并发，不改变录取：等来的结果按名次回填，最后仍从头名往后收。
            if (window.size() > VALIDATION_PARALLELISM) {
                settle(window.poll(), streams, deadline);
            }
        }
        while (!window.isEmpty()) {
            settle(window.poll(), streams, deadline);
        }

        for (int index = 0; index < limit; index++) {
            if (streams[index] == null) continue;
            Candidate winner = head.get(index);
            return new Match(winner.music, streams[index], winner.score, platformLabel(winner.platform));
        }
        return null;
    }

    private static void settle(WindowedCheck check, Tuple<String, String>[] streams, long deadline) {
        Tuple<String, String> stream = await(check.future,
                Math.max(1L, deadline - System.currentTimeMillis()));
        if (stream != null) streams[check.index] = stream;
    }

    private static final class WindowedCheck {
        final int index;
        final Future<Tuple<String, String>> future;

        WindowedCheck(int index, Future<Tuple<String, String>> future) {
            this.index = index;
            this.future = future;
        }
    }

    /**
     * 验证一条候选「现在真的放得出来」：先问本家链路，本家给不了流再让 LX 自定义源补位。
     *
     * <p>顺序必须与 {@code Music.getPlayUrl()} 里「自定义源排最前」呼应，否则验证和实播会
     * 分叉：非会员在本家只有试听甚至 403 的 VIP 曲，脚本明明能解出完整流，却会因为这里
     * 只看官方结果而被判死。{@link Music#resolveLxPlayUrl()} 自己带开关与平台覆盖判断，
     * 脚本没启用 / 不覆盖该源时返回 null，这里不重复那套逻辑。</p>
     */
    private static Tuple<String, String> tryPlayable(Music candidate, Quality quality) {
        try {
            Tuple<String, String> stream = NeteaseMusicService.getSongUrl(candidate, quality);
            if (stream != null && stream.getA() != null && !stream.getA().trim().isEmpty()) return stream;
            if (!lxCovers(candidate.getSource())) return null;
            return candidate.resolveLxPlayUrl();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SourceHits awaitHits(Future<SourceHits> future, long timeoutMillis) {
        if (future == null) return null;
        try {
            return future.get(Math.max(1L, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable interrupted) {
            future.cancel(true);
            return null;
        }
    }

    private static <T> T await(Future<T> future, long timeoutMillis) {
        if (future == null) return null;
        try {
            return future.get(Math.max(1L, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            future.cancel(true);
            return null;
        }
    }

    // ── 各源搜索 ────────────────────────────────────────────────────────────

    /**
     * 在一个源上搜一条曲目：先带艺人搜，空了就退纯曲名再搜一次。
     *
     * <p>平台之间的艺人写法差异（罗马音 / 团名 / 合唱顺序）比曲名差异大得多，
     * 这条降级与 {@link NeteaseGreyUnblocker} 用的是同一个理由。</p>
     */
    static List<Music> searchPlatform(MusicPlatform platform, String keyword, String title, String artist) {
        List<Music> primary = safeSearch(platform, keyword);
        if (!primary.isEmpty()) return primary;
        if (artist.isEmpty() || keyword.equals(title)) return primary;
        return safeSearch(platform, title);
    }

    private static List<Music> safeSearch(MusicPlatform platform, String keyword) {
        try {
            if (platform == MusicPlatform.GD) return searchGd(keyword);
            List<Music> found = NeteaseMusicService.search(platform, keyword, SEARCH_LIMIT);
            return found == null ? new ArrayList<Music>() : found;
        } catch (Throwable failure) {
            System.err.println("[Music/Switch] " + platform + " 搜索失败: " + failure);
            return new ArrayList<Music>();
        }
    }

    /** GD 走静默链：不弹搜索失败的灵动岛提示，额度与健康度不够就整个跳过。 */
    private static List<Music> searchGd(String keyword) {
        String platform = GdStudioSourceSettings.getPlatform();
        if (platform.isEmpty()) return new ArrayList<Music>();
        try {
            GdStudioMusicService.Health health = GdStudioMusicService.getHealth(platform);
            if (health == null || health.unsupported || health.cooling) return new ArrayList<Music>();
            if (GdStudioMusicService.remainingRequestBudget() < GD_MIN_BUDGET) return new ArrayList<Music>();
            GdStudioMusicService.SearchOutcome outcome = GdStudioMusicService.searchWithFallback(
                    platform, keyword, Math.min(20, SEARCH_LIMIT), 1);
            if (outcome == null || outcome.isEmpty()) return new ArrayList<Music>();
            String servedSource = outcome.source.isEmpty() ? platform : outcome.source;
            List<Music> result = new ArrayList<Music>();
            for (GdStudioMusicService.GdTrack track : outcome.tracks) {
                Music adapted = Music.fromGdTrack(track, servedSource);
                if (adapted != null) result.add(adapted);
            }
            return result;
        } catch (Throwable failure) {
            System.err.println("[Music/Switch] GD 换源搜索失败: " + failure);
            return new ArrayList<Music>();
        }
    }

    // ── 打分与候选过滤 ──────────────────────────────────────────────────────

    private static List<Candidate> scoreCandidates(Music current, List<Music> results,
                                                   MusicPlatform platform, long durationMillis,
                                                   String title, String artist) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        if (results == null || results.isEmpty()) return candidates;
        String currentKey = current.getStableKey();
        int limit = Math.min(results.size(), MAX_CANDIDATES_PER_SOURCE);
        for (int index = 0; index < limit; index++) {
            Music candidate = results.get(index);
            if (candidate == null) continue;
            if (currentKey != null && currentKey.equals(candidate.getStableKey())) continue;
            double score = acceptanceScore(title, artist, durationMillis,
                    candidate.getName(), candidate.getArtistsName(), candidate.getDuration());
            if (score <= 0.0) continue;
            long diff = durationDeltaMillis(durationMillis, candidate.getDuration());
            candidates.add(new Candidate(candidate, platform, score, diff));
        }
        return candidates;
    }

    /**
     * 一条候选能不能收，以及排名分数（0 表示淘汰）。
     *
     * <p>这是整条流水线唯一需要单测的判据，保持纯函数。</p>
     */
    static double acceptanceScore(String name, String artist, long durationMillis,
                                  String candName, String candArtist, long candDurationMillis) {
        double score = songSimilarity(name, artist, candName, candArtist);
        if (score <= 0.0) return 0.0;
        if (durationMillis > 0L && candDurationMillis > 0L) {
            if (!isDurationClose(durationMillis, candDurationMillis)) return 0.0;
        } else if (score < MIN_SCORE_WITHOUT_DURATION) {
            // 任何一侧没有时长信息（GD 搜索、解码失败的曲目）时，时长闸门形同虚设，抬线防错配。
            return 0.0;
        }
        return score;
    }

    static void sortCandidates(List<Candidate> candidates) {
        Collections.sort(candidates, (left, right) -> {
            if (left.score != right.score) return Double.compare(right.score, left.score);
            return Long.compare(left.durationDeltaMillis, right.durationDeltaMillis);
        });
    }

    static boolean isHighConfidence(Candidate candidate, long durationMillis) {
        if (candidate == null || candidate.score < HIGH_CONFIDENCE_SCORE) return false;
        if (durationMillis > 0L && candidate.music.getDuration() > 0L
                && candidate.durationDeltaMillis > HIGH_CONFIDENCE_DURATION_DELTA_MILLIS) {
            return false;
        }
        return true;
    }

    /** 归一化：小写 + 只保留字母数字（汉字、假名都算字母），把 Live/括号/feat. 之类的符号噪音剃掉。 */
    static String normalizeText(String value) {
        if (value == null) return "";
        String lowered = value.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lowered.length());
        for (int index = 0; index < lowered.length(); index++) {
            char character = lowered.charAt(index);
            if (Character.isLetterOrDigit(character)) builder.append(character);
        }
        return builder.toString();
    }

    /** Levenshtein 相似度：1 - 编辑距离 / 较长串长度；距离不小于较长串时记 0。 */
    static double similarityScore(String left, String right) {
        String a = normalizeText(left);
        String b = normalizeText(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        int distance = levenshtein(a, b);
        if (distance >= maxLen) return 0.0;
        return 1.0 - (double) distance / (double) maxLen;
    }

    /** 综合相似度：曲名 0.7 + 艺人 0.3；任一侧艺人缺失时只按曲名。 */
    static double songSimilarity(String name, String artist, String candName, String candArtist) {
        double nameScore = similarityScore(name, candName);
        if (nameScore <= 0.0) return 0.0;
        String normalizedArtist = normalizeText(artist);
        String normalizedCandArtist = normalizeText(candArtist);
        if (normalizedArtist.isEmpty() || normalizedCandArtist.isEmpty()) return nameScore;
        double artistScore = similarityScore(artist, candArtist);
        return nameScore * 0.7 + artistScore * 0.3;
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                int substitution = previous[j - 1] + cost;
                current[j] = Math.min(Math.min(deletion, insertion), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** 时长闸门：差 ≤10 秒，或 ≤原时长的 15%（下限同样是 10 秒）。任何一侧未知都放行给打分线。 */
    static boolean isDurationClose(long originalMillis, long candidateMillis) {
        if (originalMillis <= 0L || candidateMillis <= 0L) return true;
        long diff = Math.abs(originalMillis - candidateMillis);
        if (diff <= DURATION_CLOSE_ABS_MILLIS) return true;
        long maxAllowed = Math.max(DURATION_CLOSE_ABS_MILLIS, (long) (originalMillis * DURATION_CLOSE_RATIO));
        return diff <= maxAllowed;
    }

    /**
     * 起播后完整性判定：解码器报出的真实时长远短于曲目声明时长 —— 非会员在某个平台拿到的
     * 30/60 秒试听流正是这个形状（取流成功、字节合法、解码器没有异议，前两级触发都看不见它）。
     *
     * <p>刻意保守：声明时长本身未知（GD 搜索、元数据缺失）时不判 —— 没有参照系的猜测只会
     * 误伤完整曲目；短歌（低于 90 秒）不判 —— 前奏 / 过场 / 片段类曲目本来就这么长。</p>
     */
    static boolean isIncompleteStream(long declaredMillis, long decodedMillis) {
        if (declaredMillis < TRIAL_CHECK_MIN_DECLARED_MILLIS) return false;
        if (decodedMillis <= 0L) return false;
        if (decodedMillis >= declaredMillis * TRIAL_CHECK_MAX_RATIO) return false;
        return declaredMillis - decodedMillis >= TRIAL_CHECK_MIN_GAP_MILLIS;
    }

    static long durationDeltaMillis(long originalMillis, long candidateMillis) {
        if (originalMillis <= 0L || candidateMillis <= 0L) return 0L;
        return Math.abs(originalMillis - candidateMillis);
    }

    /** 候选平台序列：固定优先级、剔除当前平台，再剔除当前账号够不着会员的「要会员」平台。 */
    static List<MusicPlatform> candidatePlatforms(MusicPlatform current) {
        return candidatePlatforms(current, CrossSourceSwitcher::hasVipAccess);
    }

    /** 纯选表部分：会员闸门以参数传入，选表逻辑才能被单测钉死（不碰任何登录态全局）。 */
    static List<MusicPlatform> candidatePlatforms(MusicPlatform current,
                                                  java.util.function.Predicate<MusicPlatform> vipGate) {
        List<MusicPlatform> platforms = new ArrayList<MusicPlatform>();
        for (MusicPlatform platform : CANDIDATE_ORDER) {
            if (platform == current) continue;
            if (!vipGate.test(platform)) continue;
            platforms.add(platform);
        }
        return platforms;
    }

    /**
     * 该平台现在的账号值不值得去换。
     *
     * <p>非会员在「要会员」的平台多半只能拿到 30/60 秒试听流 —— 搜索与取流验证搜不出
     * 完整曲目，不如整源不发。<b>GD 音乐台与 B 站不受这条限制</b>（GD 是聚合直链、B 站是
     * 音乐区音频池，都不卖会员）。网易云与酷狗查的是真实会员，且都是登录时就落在内存/凭证
     * 里的状态，这里不发任何网络请求：{@code CloudMusic.profile.vipType} 与酷狗凭证的
     * {@code vip_type}。QQ 音乐在登录包与 {@code QQUserInfo} 里都没有会员字段可查，只能以
     * 「已登录」为门槛 —— 没登录连取流密钥都没有；登录了但不是绿钻，换过去拿到试听也会
     * 被我方第三/四级检测拦住。</p>
     *
     * <p><b>LX 自定义源改写这条闸门</b>（{@link #lxCovers}）：脚本本身支持免费解 VIP 曲，
     * 只要它启用并声明能吃下某平台，该平台就没有「本家会员」一说的必要 —— 没会员也进候选，
     * 换过去的候选在 {@code Music.getPlayUrl} / {@link #tryPlayable} 里脚本优先。</p>
     */
    public static boolean hasVipAccess(MusicPlatform platform) {
        if (platform == MusicPlatform.GD || platform == MusicPlatform.BILIBILI) return true;
        // LX 通道优先：自定义源已启用且声明覆盖该平台时，本家会员与否都不再是门槛。
        if (lxCovers(platform)) return true;
        if (platform == MusicPlatform.NETEASE) {
            return CloudMusic.profile != null && CloudMusic.profile.isVipMember();
        }
        if (platform == MusicPlatform.KUGOU) {
            return KuGouDevice.isLoggedIn() && KuGouDevice.vipType() > 0;
        }
        if (platform == MusicPlatform.QQ) {
            return NeteaseMusicService.isLoggedIn(MusicPlatform.QQ);
        }
        if (platform == MusicPlatform.QISHUI) {
            return QishuiMusicService.isLoggedIn() && QishuiMusicService.isVipAccount();
        }
        // 酷我 / 咪咕没有本家会员可查，也没有本家取流：能不能换全看上面 lxCovers 放没放行，
        // 走到这里说明脚本没声明 kw/mg，别烧一次必死的搜索 + 验证。
        if (platform == MusicPlatform.KUWO || platform == MusicPlatform.MIGU) return false;
        // YouTube 等不在候选表里的平台不会被问到；保守放行不如保守拒绝——
        // 走到这里说明候选表被改过而这里没跟上，宁可不换也别烧请求。
        return false;
    }

    /**
     * LX 自定义源是否覆盖了该平台的取流：源已启用、且它的 info.sources 声明能解这个平台
     * （网易云→wy、QQ→tx、酷狗→kg、酷我→kw、咪咕→mg；GD 行按当前选定的下游平台映射，
     * B 站 / 汽水没有 LX 概念恒为 false）。纯查已加载脚本的元数据，不发网络。脚本只声明
     * 它真能解的 key，所以这里跟着 {@code sourceKeyFor} 的正反两次映射走，不自作主张放行。
     *
     * <p>对 kw / mg 这一票就是生死判：它们的曲目没有本家流，脚本解不出的渠道不值得进候选。</p>
     */
    public static boolean lxCovers(MusicPlatform platform) {
        if (!LxSourceService.isEnabled()) return false;
        String key = lxChannelKey(platform);
        if (key == null) return false;
        // 脚本已经跑起来了就再看深一层：只认它真正声明了这个源 + musicUrl 动作。
        // 还没加载（首次换源早于脚本启动）时按映射放行 —— 即便放行早了，验证关的
        // tryPlayable 也只会在官方链路与脚本都拿不出流时才淘汰这一路，不会乱换。
        LxRuntime runtime = LxSourceService.getRuntime();
        if (runtime == null || runtime.getState() != LxRuntime.State.READY) return true;
        LxRuntime.SourceDecl decl = runtime.getSources().get(key);
        return decl != null && decl.supports("musicUrl");
    }

    private static String platformLabel(MusicPlatform platform) {
        return platform == null ? "其他平台" : platform.getDisplayName();
    }

    /**
     * 该平台在本机 LX 脚本里的<b>渠道键</b>（网易云→wy、QQ→tx、酷狗→kg、酷我→kw、咪咕→mg；
     * GD 按当前选定的下游平台映射；B 站 / 汽水 / YouTube 没有 LX 概念，返回 null）。
     *
     * <p>正反两次映射都过得去才算数：{@code sourceKeyFor} 是平台→键，{@code platformForSourceKey}
     * 是键→平台，只查前一半的话，将来给某个平台新加了映射而反查表没跟上，就会拿着一个
     * 房间认不出的键去取流。{@link #lxCovers} 用的就是这一份，两边不许各写一套。</p>
     *
     * <p>纯查映射表，不发网络、不看脚本加载没加载 —— 与 {@code lxCovers} 的区别就在这儿：
     * 这里回答的是「这台机器的脚本用哪个词称呼这个平台」，而弃权原因要的就是这个词。</p>
     */
    public static String lxChannelKey(MusicPlatform platform) {
        if (platform == null) return null;
        String key = LxSourceService.sourceKeyFor(platform.name(), GdStudioSourceSettings.getPlatform());
        if (key == null || LxSourceService.platformForSourceKey(key) == null) return null;
        return key;
    }

    // ── 负缓存 ──────────────────────────────────────────────────────────────

    private static boolean isNegativelyCached(String key, long now) {
        Long expiry = NEGATIVE_CACHE.get(key);
        if (expiry == null) return false;
        if (expiry > now) return true;
        NEGATIVE_CACHE.remove(key, expiry);
        return false;
    }

    private static void markNegative(Music current) {
        String key = current == null ? null : current.getStableKey();
        if (key == null || key.isEmpty()) return;
        if (NEGATIVE_CACHE.size() > GUARD_SET_LIMIT) {
            // 粗粒度防泄漏：整表过期太重，直接清空重新记账最多少挡几次重复尝试。
            NEGATIVE_CACHE.clear();
        }
        NEGATIVE_CACHE.put(key, System.currentTimeMillis() + NEGATIVE_CACHE_MILLIS);
    }

    /** 换源失败结论的测试钩子：让单测能清账。 */
    static void clearCachesForTests() {
        NEGATIVE_CACHE.clear();
        AUTO_ATTEMPTED.clear();
        IN_FLIGHT.clear();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ── 内部类型 ────────────────────────────────────────────────────────────

    static final class Candidate {
        final Music music;
        final MusicPlatform platform;
        final double score;
        final long durationDeltaMillis;

        Candidate(Music music, MusicPlatform platform, double score, long durationDeltaMillis) {
            this.music = music;
            this.platform = platform;
            this.score = score;
            this.durationDeltaMillis = durationDeltaMillis;
        }
    }

    private static final class SourceHits {
        final MusicPlatform platform;
        final List<Candidate> candidates;

        SourceHits(MusicPlatform platform, List<Candidate> candidates) {
            this.platform = platform;
            this.candidates = candidates == null ? new ArrayList<Candidate>() : candidates;
        }
    }
}
