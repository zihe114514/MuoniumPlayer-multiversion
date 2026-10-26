package yun.shan.mpsync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.util.SourceGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code config/mpsync.json} — written with defaults on first run. */
public final class MpConfig {

    /** Members allowed in one room (0 = unlimited). */
    public int maxRoomMembers = 32;
    /** Pending 点歌 entries allowed per room. */
    public int maxQueueSize = 200;
    /** Pending entries a single non-admin member may hold at once (0 = unlimited). */
    public int maxQueuePerMember = 5;
    /** Rooms one server may host at a time (0 = unlimited). */
    public int maxRooms = 32;
    /** Interval of the authoritative clock broadcast, in ticks. */
    public int timeBroadcastTicks = 40;
    /**
     * 命令帧之后的「时钟静默窗」：换歌／暂停／恢复／拖进度之后这么久内不发时钟心跳。
     *
     * <p>为什么需要：命令帧刚下发的那几秒，全房客户端都在开流（建连、缓冲、解码器起步），
     * 各家网络快慢不一，位置天然是散的。这时候再按 {@link #timeBroadcastTicks} 打一发心跳，
     * 每个还没缓冲完的客户端都会算出一个大偏差，于是集体 seek —— 而 seek 会重新开流，
     * 下一发心跳又是同样的局面，进度就在那儿来回跳。静默窗把这段「本来就该乱」的时间让出去，
     * 等大家真的开始播了再校准。</p>
     *
     * <p>命令帧本身不受影响，仍然立刻下发：静默的只是心跳。</p>
     */
    public long clockGraceMs = 4000L;
    /**
     * 房间内无缝曲目交接的提前量（毫秒）；0 = 关闭。
     *
     * <p>开启后服务端在「本曲结束前这么多毫秒」就把下一首置 0 并广播 {@code play}，
     * 客户端在同一刻让下一首起播，于是本曲的尾巴与下一首的开头重叠。</p>
     *
     * <p>为什么提前的是<b>服务端</b>而不是客户端自己：房间只有一套权威时钟，
     * {@code startEpoch} 决定下一首的 0 点落在哪个墙钟时刻。客户端若自行提前起播，它本机
     * 下一首的位置就会比服务端多出这个提前量，随后每一发心跳都判它脱节、把它硬跳回去。
     * 提前发生在服务端，两边对「下一首 0 点」的理解才仍然一致，时钟模型一行都不用改。</p>
     *
     * <p>为什么默认 4000（开启）：提前换歌意味着本曲最后这么多毫秒不再单独发声。会交叠的
     * 客户端把它叠在下一首底下，听不出接缝；不会交叠的客户端（旧版本、modernMP）则直接切到
     * 下一首 —— 对它来说就是尾巴少了一截。默认打开是因为房间无缝要两端配套才成立：服务端关着
     * 的话，客户端那半连下一首的链接都拿不到，「提前下载」也就无从谈起，功能等于不存在。
     * 想保住整条尾巴的服主把它设成 {@code 0} 即可，行为立刻退回旧版（原来的 250ms 判据）。</p>
     */
    public long automixLeadMs = 4000L;
    /**
     * 被指派去算交接计划的那台客户端有多久时间交卷（毫秒）。
     *
     * <p>超时就改派下一台。选得比一次分析的实际耗时长不少：真要算的机器要在几秒内完成
     * 「解析下一首直链 → 下载 → 解码 → 读两首歌的 PCM → 出计划」，慢一点的机器十来秒是正常的，
     * 而这首曲子有整首歌的时间可以等（计划只要赶在 {@link #automixLeadMs} 之前到就行）。</p>
     *
     * <p>被指派人主动拒绝（{@code analyze_decline}）时不走这个超时，立刻改派 —— 那是明确信号。</p>
     *
     * <p><b>设成 {@code 0} 就是取消超时</b>（负数同样按 0 处理）：被指派人只要还在线，就一直等它把计划
     * 交上来，不再因为「等太久」改派。Folia 那套模型推理动辄几十秒，固定超时会在它还没跑完时就把活儿
     * 抢走 —— 不但白烧两台的算力，还会把「算得慢但在算」的那台拉进黑名单。关掉之后改派只剩两条路：
     * 被指派人<b>主动拒绝</b>，或它<b>掉线</b>了（掉线这条由 {@code Room#tickAnalyst} 单独判，与超时无关）。
     * 老客户端不发这个字段，取到的就是默认的 15 秒。</p>
     */
    public long automixAssignTimeoutMs = 15_000L;
    /**
     * 房间里没人能分析时，是否让服务端向全房说明「这一首不会有交叠」。
     *
     * <p>关掉只是少一帧提示；正确性不受影响（客户端本来就会因为拿不到计划而走降级路由）。</p>
     */
    public boolean automixAnnounceAnalyst = true;
    /**
     * 软偏差阈值：客户端与服务端位置差超过这个值才值得处理，但只做静默微调（追/缓播放速度或
     * 小步纠偏），不许硬 seek。播放器缓冲抖动、解码延迟常年在 1~2 秒这个量级，硬跳只会更难听。
     */
    public long softDriftMs = 2500L;
    /** 硬偏差阈值：只有差到这个程度（真的脱节了）才允许客户端硬 seek 回服务端位置。 */
    public long hardDriftMs = 6000L;
    /** 两次硬 seek 之间的最小间隔。网络差的时候，这一条是「来回跳」的最后一道闸。 */
    public long resyncCooldownMs = 10_000L;
    /**
     * 连续多少发心跳都判定超阈值，才真的动手。
     *
     * <p>单发心跳不可信：一次 RTT 抖动就能造出几秒的假偏差。要求连续命中，能把瞬时抖动
     * 和真正的脱节区分开。</p>
     */
    public int driftConfirmHits = 2;
    /** Assumed stream-URL lifetime when a client does not report one. */
    public long assumedUrlLifetimeMs = 20 * 60 * 1000L;
    /**
     * 房间等一条重新解析的链接多久才放弃并跳过这首歌。
     *
     * <p>这个窗口要装得下「服务端发问 → 客户端向平台发一次 HTTP → 客户端回帧」一整个往返，而且
     * 是别人机器上的网络。非会员点会员曲时全靠这条路把链接凑齐，10 秒经常不够，所以默认 20 秒，
     * 并在 {@link #load} 里兜一个 12 秒的下限。</p>
     */
    public long urlWaitTimeoutMs = 20_000L;
    /**
     * 分层代解析里<b>每一层</b>的等待窗。
     *
     * <p>{@link #urlWaitTimeoutMs} 是总闸（到点就跳过这首歌），这一项是闸内的推进节奏：
     * 「真会员 → 贡献 LX → 只登录/不卖会员的平台 → 点歌人 → 装了该平台的成员 → 全房」每层等这么
     * 久，没人答或者全部弃权就换下一层。默认 3 秒 —— 装得下「发问 → 客户端向平台发一次 HTTP →
     * 回帧」一个别人机器上的往返，而六层全走完（18 秒）仍留在 {@link #urlWaitTimeoutMs} 之内，
     * 剩下那几秒才是留给跨平台救援的。取 5 秒的话整条链走不完，救援永远轮不上。</p>
     *
     * <p>设得比总闸还长没有意义，{@link #load} 里把它夹到不超过总闸的一半，
     * 免得配错之后只剩第一层被问过一次。</p>
     */
    public long urlTierTimeoutMs = 3_000L;
    /**
     * <b>估算</b>出来的「链接实际服务多长」用的试听阈值，比 {@link #minStreamDurationRatio} 宽。
     *
     * <p>网易云会在同一次响应里报出真实长度，那个数可以直接卡 80%。酷狗（filesize ÷ 档位码率）
     * 与 LX 脚本（HEAD 拿 Content-Length ÷ 脚本声明的 format）两条路都是<b>算出来的</b>：
     * CDN 报的尺寸可能是容器大小、码率可能标称高于实际，按 80% 卡会把完整版误判成试听片段，
     * 而误判的代价是整房白等一轮再跳过一首本来能放的歌。所以估算只卡明显不对的（默认 50%）。</p>
     */
    public double estimatedStreamDurationRatio = 0.5;

    /**
     * 派给一台机器跑跨平台匹配的窗口，到点没结果就改派下一台。默认 12 秒。
     *
     * <p>比一个层窗长得多是刻意的：这一手要<b>挨个平台搜索 + 打分 + 逐条验证取流</b>，
     * 一台的窗口也是这一首卡住的时间上限，所以台数（{@link #switchRescueAttempts}）比窗口更好调。</p>
     */
    public long switchRescueWindowMs = 12_000L;

    /** 一首歌最多找几台机器替它跑跨平台匹配。默认 3 台。 */
    public int switchRescueAttempts = 3;
    /** Drop a room once every member has been offline this long. */
    public long emptyRoomGraceMs = 5 * 60 * 1000L;
    /** Allow members without control rights to 点歌. */
    public boolean membersCanEnqueue = true;
    /** Let a requester delete their own pending entry. */
    public boolean requesterCanRemoveOwn = true;
    /**
     * 一次「播放歌单」最多排入多少首（0 = 关掉了灌单这个功能）。
     *
     * <p>它和 {@link #maxQueueSize} 是两层不同的闸：后者是房间总队列的长度，一张长单子当然不能
     * 把房间占满；这一档管的是「一次投放能倒进去多少」。默认 100 首 ≈ 一张正常歌单的规模，
     * 又明显少于 200 的总上限，留一半位置给别人点歌。</p>
     *
     * <p>{@code 0} = 关掉这个功能（分派那一层直接回一句「服务器未开启播放歌单功能」，客户端
     * 也据此不显示那颗按钮）。</p>
     */
    public int playlistBatchLimit = 100;
    /**
     * 灌歌单是不是只有房主/管理员能做。
     *
     * <p>默认 false —— 「一起听导入歌单」的定位是<b>成员协作</b>：谁都能把自己的网易云歌单
     * 分享给全房听。放得下是因为下面还有三层闸兜着：单次投放上限 {@link #playlistBatchLimit}、
     * 房间总队列 {@link #maxQueueSize}、以及每次导入之间的冷却 {@link #playlistImportCooldownMs}。
     * 「霸房」的窗口被这三层压到了单次一百首之内，不会出现把整间房间的歌单变成自己播放列表
     * 的空间。服主想收紧就改回 true。</p>
     */
    public boolean playlistAdminOnly = false;
    /**
     * 同一名玩家两次「播放歌单」之间的最短间隔（毫秒，0 = 不限速）。
     *
     * <p>为什么成员开放之后需要这一档：单次上限与总队列是<b>存量</b>闸，管得住一次倒进去多少，
     * 管不住<b>连续倒</b> —— 一百首一首上限的人连灌二十次，照样把总队列刷满。冷却把「连续倒」
     * 拉开到人手一次的节奏，和「连点两下创建房间不该开出两条会话」是同一条工程直觉。
     * 默认 10 秒，够念完一句「我灌了张单子」也够别人有反应的机会；服主可调。</p>
     */
    public long playlistImportCooldownMs = 10_000L;
    /**
     * 前瞻窗：轮到播放之前，服务端提前给几首要播放链接。
     *
     * <p>默认 2，也就是「下一首 + 再下一首」。这直接决定灌单能不能把房间打爆：一次 {@code url_needed}
     * 会向房间里每个可能答得上的成员单播一次，收到的人在本机真跑一次取流。整张歌单一次性问完，
     * 等于拿点歌人的账号和上行按着一百首猛抽一遍，而大部分曲子要几十分钟后才轮到，那时链接早过期了。
     * 设成 1 就退回「只备下一首」（改造前单首点歌时代的实际行为），设得更大只是更激进地预取。</p>
     */
    public int queueResolveLead = 2;
    /**
     * Minimum served-audio length, as a fraction of the track length, for a stream URL to
     * be accepted. Guards against a non-VIP client resolving a 30s trial clip and having
     * the whole room play that. Set to 0 to disable the check.
     */
    public double minStreamDurationRatio = 0.8;
    /**
     * 允许入队的播放源。<b>默认留空 = 不限制平台。</b>
     *
     * <p>服务端不解析链接也不取流，它转发的是点歌人自己解析出来的直链，所以平台对服务端没有
     * 技术意义 —— 客户端支持哪个平台，房间就能放哪个平台。这一项留着只是给服主一个收窄的开关
     * （比如只想让房间放网易云就填 {@code ["netease"]}）。可照抄的已知令牌见
     * {@link Proto#KNOWN_SOURCES}，但不限于它们。</p>
     */
    public List<String> allowedSources = new ArrayList<>();
    /**
     * 取流／封面链接允许的 host 后缀。<b>默认留空 = 只要是公网 http(s) 地址就放行。</b>
     *
     * <p>之所以不再内置平台白名单：每加一个音乐平台都要改服务端，而客户端能支持的平台远多于
     * 服务端能维护的列表。留空不等于毫无把关 —— {@link SourceGuard#isPublicHttpUrl} 仍然会挡掉
     * 环回／私网／链路本地地址，所以房间不能被拿来让全房客户端探内网。想收窄到某几个平台时，
     * 可以照抄 {@link SourceGuard#DEFAULT_HOSTS} 再增删；{@code customapi} 与其它返回
     * 第三方 CDN 的源，需要按实际接口再补自己的域名。</p>
     */
    public List<String> allowedHosts = new ArrayList<>();

    // ── 公开房间 ──────────────────────────────────────────────────────────────

    /**
     * 是否允许房主把房间设为公开（出现在公开房间列表里，任何人可直接加入）。
     *
     * <p>关掉之后房间只能靠邀请码或邀请进 —— 有些服务器不希望陌生人随便加进别人的房间。
     */
    public boolean publicRoomsEnabled = true;

    // ── 服务器频道 ────────────────────────────────────────────────────────────

    /** 是否开启全服公开大房间（服务器频道）。 */
    public boolean channelEnabled = true;
    public String channelName = "服务器频道";
    /** 频道人数上限（0 = 不限）。 */
    public int maxChannelMembers = 0;
    /**
     * 频道里单个玩家同时能挂在歌单上的曲目数。
     *
     * <p>需求写死的 3 首。频道是全服共用的，不设这一档的话一个人塞满队列就把所有人挡在外面。
     */
    public int channelMaxPerMember = 3;

    // ── 投票（频道） ──────────────────────────────────────────────────────────

    /** 一次投票的窗口。需求：倒计时 30 秒后展示结果。 */
    public long voteWindowMs = 30_000L;
    /** 切歌通过门槛：同意票数必须<b>严格超过</b>在线人数的这个比例（50% 以上）。 */
    public double voteSkipRatio = 0.5d;
    /** 踢人通过门槛（80% 以上）。 */
    public double voteKickRatio = 0.8d;
    /**
     * 踢人投票没通过之后的冷却，期间不能再对同一个人发起。
     *
     * <p>需求：「不满足票数则放入冷却，避免乱踢。此方案需超过 1 小时生效」——默认就是 1 小时。
     * 这个数必须远大于投票窗口，否则「乱踢」只是慢一点而已。
     */
    public long voteKickCooldownMs = 60 * 60 * 1000L;
    /** 少于这么多人时不开投票（自己一个人投自己没有意义）。 */
    public int voteMinMembers = 2;

    // ── 代解析（服主 Token / 贡献 VIP） ───────────────────────────────────────

    /**
     * 服主配置的平台凭据，{@code 平台令牌 -> 凭据}。
     *
     * <p><b>用途</b>：给没有会员的玩家解析会员曲。服务端拿这份凭据自己去平台取一条直链，
     * 再像成员上传的链接一样转发给全房，房间里因此不需要恰好有个会员在线。
     *
     * <p><b>网易云</b>填 {@code MUSIC_U} 的值（登录后的 cookie），也可以整段
     * {@code "MUSIC_U=xxx; __csrf=yyy"} 一起填。其它平台见 {@link #resolverEndpoints}。
     *
     * <p><b>这是敏感数据</b>：等价于服主自己的会员账号。它躺在 {@code mpsync.json} 里，
     * 谁能读服务器文件谁就能拿走，所以别把它填到共享主机上，也别把配置文件提交进仓库。
     * 玩家侧永远拿不到它 —— 下发给客户端的只有解析出来的那条带签名直链。
     */
    public Map<String, String> serverTokens = new LinkedHashMap<>();

    /**
     * 其它平台的解析接口模板，{@code 平台令牌 -> URL 模板}。
     *
     * <p>服务端内置的解析只有网易云一家（它的接口签名方式是公开且稳定的）。其它平台千差万别，
     * 与其在服务端追着各家接口改，不如让服主指一个自己信得过的解析服务：模板里可以用
     * {@code {songId}}、{@code {source}}、{@code {token}} 三个占位符，例如
     * {@code "https://my-api.example.com/qq/url?id={songId}&token={token}"}。
     *
     * <p>响应可以是纯文本的一条 URL，也可以是 JSON —— 会依次找 {@code url}、{@code data.url}、
     * {@code data[0].url} 这几个常见位置。解析出来的地址仍然要过
     * {@link yun.shan.mpsync.util.SourceGuard#isPublicHttpUrl}，所以指不到内网。
     */
    public Map<String, String> resolverEndpoints = new LinkedHashMap<>();

    /** 服务端代解析的单次超时。超过就当没解析出来，退回向客户端要。 */
    public long serverResolveTimeoutMs = 12_000L;

    /**
     * 向客户端要链接时，是否优先问「开了贡献 VIP 的成员」。
     *
     * <p>开着（默认）时，同一首歌只会打扰真的能解析出来的人；关掉就退回 v1 的行为
     * —— 先问点歌人，再问管理者，最后广播全房。
     */
    public boolean preferContributors = true;

    /** 这个平台有没有配服务端凭据。 */
    public boolean hasServerToken(String source) {
        if (source == null || serverTokens == null) return false;
        String key = source.trim().toLowerCase(Locale.ROOT);
        String v = serverTokens.get(key);
        if (v != null && !v.isBlank()) return true;
        String tpl = resolverEndpoints == null ? null : resolverEndpoints.get(key);
        return tpl != null && !tpl.isBlank();
    }

    /** 平台是否开放；{@code allowedSources} 为空视为不限制。 */
    public boolean isSourceAllowed(String source) {
        if (allowedSources == null || allowedSources.isEmpty()) return true;
        for (String s : allowedSources) {
            if (s != null && s.trim().equalsIgnoreCase(source)) return true;
        }
        return false;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MpConfig load(Path file) {
        MpConfig cfg = new MpConfig();
        try {
            if (Files.exists(file)) {
                MpConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MpConfig.class);
                if (read != null) cfg = read;
            }
            if (cfg.allowedHosts == null) cfg.allowedHosts = new ArrayList<>();
            else if (isLegacyNeteaseOnlyHosts(cfg.allowedHosts)) {
                // 旧版本写出来的配置里只有网易云那 5 条后缀。它非空，所以不会被当成「不限制」，
                // 结果是升级之后其他平台的直链一律被拒，看起来像「多平台没生效」。清空成不限制。
                // 只在内容与旧默认值完全一致时才升级 —— 服主手改过的列表不能被覆盖。
                cfg.allowedHosts = new ArrayList<>();
            }
            if (cfg.allowedSources == null) cfg.allowedSources = new ArrayList<>();
            if (cfg.timeBroadcastTicks < 5) cfg.timeBroadcastTicks = 5;
            // 时钟同步这一组：错配的阈值比默认值更糟，所以逐项兜底，并保证 soft < hard。
            if (cfg.clockGraceMs < 0L) cfg.clockGraceMs = 0L;
            if (cfg.clockGraceMs > 30_000L) cfg.clockGraceMs = 30_000L;
            if (cfg.softDriftMs < 1000L) cfg.softDriftMs = 1000L;
            if (cfg.hardDriftMs < cfg.softDriftMs + 1000L) cfg.hardDriftMs = cfg.softDriftMs + 1000L;
            if (cfg.resyncCooldownMs < 2000L) cfg.resyncCooldownMs = 2000L;
            if (cfg.driftConfirmHits < 1) cfg.driftConfirmHits = 1;
            if (cfg.driftConfirmHits > 10) cfg.driftConfirmHits = 10;
            // 房间内交叠：提前换歌发生在命令帧之后，正当全房开流，所以静默窗必须盖得住它，
            // 否则淡到一半就被心跳判成脱节、硬跳回去，听感比不做交叠还差。
            if (cfg.automixLeadMs < 0L) cfg.automixLeadMs = 0L;
            if (cfg.automixLeadMs > 12_000L) cfg.automixLeadMs = 12_000L;
            if (cfg.automixLeadMs > 0L && cfg.clockGraceMs < cfg.automixLeadMs + 1000L) {
                cfg.clockGraceMs = Math.min(30_000L, cfg.automixLeadMs + 1000L);
            }
            // 指派超时：太短会让慢机器每次都被改派（结果是谁都没算完），太长又让「借用别人电脑」
            // 这条路每首白等太久。10~60 秒之间都合理。
            // 0（或负数）表示取消超时，见字段 javadoc —— 0 是个有意义的值，不能被抬成 5 秒。
            if (cfg.automixAssignTimeoutMs < 0L) cfg.automixAssignTimeoutMs = 0L;
            if (cfg.automixAssignTimeoutMs > 0L && cfg.automixAssignTimeoutMs < 5_000L) {
                cfg.automixAssignTimeoutMs = 5_000L;
            }
            if (cfg.automixAssignTimeoutMs > 120_000L) cfg.automixAssignTimeoutMs = 120_000L;
            if (cfg.assumedUrlLifetimeMs < 60_000L) cfg.assumedUrlLifetimeMs = 60_000L;
            // 低于这个值时「向别的成员要链接」在物理上来不及，等于把功能关掉，所以兜底抬上来。
            if (cfg.urlWaitTimeoutMs < 12_000L) cfg.urlWaitTimeoutMs = 12_000L;
            if (cfg.urlTierTimeoutMs < 2_000L) cfg.urlTierTimeoutMs = 2_000L;
            // 分层推进要能在总闸里走完几层：一层比总闸还长就等于只有第一层被问过。
            if (cfg.urlTierTimeoutMs > cfg.urlWaitTimeoutMs / 2L)
                cfg.urlTierTimeoutMs = Math.max(2_000L, cfg.urlWaitTimeoutMs / 2L);
            if (cfg.switchRescueWindowMs < 1_000L) cfg.switchRescueWindowMs = 1_000L;
            if (cfg.switchRescueWindowMs > 60_000L) cfg.switchRescueWindowMs = 60_000L;
            if (cfg.switchRescueAttempts < 1) cfg.switchRescueAttempts = 1;
            if (cfg.switchRescueAttempts > 5) cfg.switchRescueAttempts = 5;
            if (cfg.estimatedStreamDurationRatio < 0d) cfg.estimatedStreamDurationRatio = 0d;
            // 估算的阈值只许比实测更宽，反过来会让估算值比实测还容易挨拒。
            if (cfg.estimatedStreamDurationRatio > cfg.minStreamDurationRatio)
                cfg.estimatedStreamDurationRatio = cfg.minStreamDurationRatio;
            if (cfg.minStreamDurationRatio < 0d) cfg.minStreamDurationRatio = 0d;
            if (cfg.minStreamDurationRatio > 1d) cfg.minStreamDurationRatio = 1d;
            // v2 新增项：老配置文件里没有它们，gson 会留成 null / 0，逐项兜底。
            if (cfg.channelName == null || cfg.channelName.isBlank()) cfg.channelName = "服务器频道";
            if (cfg.channelMaxPerMember < 1) cfg.channelMaxPerMember = 3;
            if (cfg.maxChannelMembers < 0) cfg.maxChannelMembers = 0;
            // 投票窗口太短没人来得及看到，太长又会把频道卡住。
            if (cfg.voteWindowMs < 10_000L) cfg.voteWindowMs = 10_000L;
            if (cfg.voteWindowMs > 300_000L) cfg.voteWindowMs = 300_000L;
            // 比例必须落在开区间里：0 会让任何人一票切歌，1 永远过不了。
            if (cfg.voteSkipRatio <= 0d || cfg.voteSkipRatio >= 1d) cfg.voteSkipRatio = 0.5d;
            if (cfg.voteKickRatio <= 0d || cfg.voteKickRatio >= 1d) cfg.voteKickRatio = 0.8d;
            // 冷却比投票窗口长才有意义，否则「乱踢」只是慢一点。
            if (cfg.voteKickCooldownMs < cfg.voteWindowMs * 2L) {
                cfg.voteKickCooldownMs = Math.max(60 * 60 * 1000L, cfg.voteWindowMs * 2L);
            }
            if (cfg.voteMinMembers < 2) cfg.voteMinMembers = 2;
            // 灌单这一组。单次上限不能超过房间总上限，否则「队列已满」那一句就成了空话；
            // 负数是手滑，按 0（关掉功能）处理，而不是按「不限」处理 —— 不限恰好是最糟的默认值。
            if (cfg.playlistBatchLimit < 0) cfg.playlistBatchLimit = 0;
            if (cfg.maxQueueSize > 0 && cfg.playlistBatchLimit > cfg.maxQueueSize) {
                cfg.playlistBatchLimit = cfg.maxQueueSize;
            }
            // 导入冷却：负数是手滑按 0（不限速）处理；上限 5 分钟，再长「成员灌歌单」
            // 就名存实亡了。
            if (cfg.playlistImportCooldownMs < 0L) cfg.playlistImportCooldownMs = 0L;
            if (cfg.playlistImportCooldownMs > 300_000L) cfg.playlistImportCooldownMs = 300_000L;
            // 前瞻窗至少 1 首，不然轮到那一首才开始问，每次交接都要空等一个 urlWaitTimeoutMs。
            if (cfg.queueResolveLead < 1) cfg.queueResolveLead = 1;
            if (cfg.queueResolveLead > 10) cfg.queueResolveLead = 10;
            if (cfg.serverTokens == null) cfg.serverTokens = new LinkedHashMap<>();
            if (cfg.resolverEndpoints == null) cfg.resolverEndpoints = new LinkedHashMap<>();
            if (cfg.serverResolveTimeoutMs < 3_000L) cfg.serverResolveTimeoutMs = 3_000L;
            if (cfg.serverResolveTimeoutMs > 60_000L) cfg.serverResolveTimeoutMs = 60_000L;
            cfg.save(file);
        } catch (Throwable ignored) {
            // A broken config must not stop the server from booting — defaults win.
        }
        return cfg;
    }

    /** 存量配置是否就是旧版的「仅网易云」默认值（顺序无关，逐项比较）。 */
    private static boolean isLegacyNeteaseOnlyHosts(List<String> hosts) {
        if (hosts.size() != SourceGuard.NETEASE_HOSTS.size()) return false;
        for (String h : hosts) {
            if (h == null) return false;
            boolean found = false;
            for (String known : SourceGuard.NETEASE_HOSTS) {
                if (known.equalsIgnoreCase(h.trim())) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
