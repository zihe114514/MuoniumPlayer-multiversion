package yun.shan.mpsync.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.ClientProfile;
import yun.shan.mpsync.presence.PresenceRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One synced listening room: membership + roles, the 点歌 queue, and the authoritative
 * playback clock.
 *
 * <p>Threading: every method here runs on the server thread. Network receivers hop
 * through {@code server.execute(...)} before touching a room, so no locking is needed.
 *
 * <p>Clock model: the server owns position. {@link #startEpoch} is the wall-clock instant
 * at which the current track's position would have been 0, so
 * {@code pos = now - startEpoch} while playing and {@link #pausedPos} while paused.
 * Clients get {@code serverTime} alongside every position so they can compensate for
 * one-way latency instead of trusting arrival time.
 */
public final class Room {

    private static final int HISTORY_MAX = 50;
    /** Treat "within this much of the end" as finished — decoders rarely hit the exact ms. */
    private static final long END_EPSILON_MS = 250L;
    /**
     * 计划里 {@code fireMillis} 允许把交接点从曲末往前挪的最大量（= 交叠长度上限）。
     *
     * <p>与 {@link MpConfig#automixLeadMs} 的上限（{@code load()} 夹到 12000）同构：合法计划的
     * 不变式是 {@code fire + overlap = dur} 且 {@code overlap ≤ 12s}，所以 {@code fire ≥ dur - 12s}。
     * {@link #fireAt} 用它挡住把 {@code fireMillis} 报成「尾奏扫描窗口起点」之类的坏计划。</p>
     */
    private static final long MAX_LEAD_MS = 12_000L;
    /**
     * 计划里 {@code overlapMillis} 的合理上限，用于 {@link #fireAt} 的下限计算。
     *
     * <p>与客户端 {@code RoomAutomix.MAX_OVERLAP_MILLIS} 一致（60 秒）：交接点最早能落在
     * {@code dur - overlap}，而一段交叠再长也不该超过一分钟（再长就不是混音而是串烧了）。
     * 只用来防一个离谱的 {@code overlapMillis} 把 {@link #fireAt} 的下限撑穿，让坏计划得以
     * 提前到曲子中段换歌。</p>
     */
    private static final long MAX_OVERLAP_MS = 60_000L;
    /**
     * 时钟静默窗的上限。
     *
     * <p>交叠可以合法地长达一分钟，但「完全不校准」本身也有代价：真脱节的客户端要靠心跳拉回来。
     * 30 秒比任何一次实际的混音都宽（提前量封顶 12 秒，加上一段长尾奏也就十几二十秒），
     * 又比一首歌的剩余部分短得多 —— 两头都顾得住。</p>
     */
    private static final long CLOCK_QUIET_MAX_MS = 30_000L;
    /**
     * 一条 {@code enqueue_batch} 帧最多带几首。
     *
     * <p>客户端本来就按 {@code MpPartyClient#BATCH_CHUNK} 分批发，这一档只是挡住「一次把一千首
     * 塞进一个 JSON 数组」那种写法：单帧上限 512KB 装得下，但服务端逐条 {@link Track#fromJson}
     * 加上一次全量广播会明显卡顿，而房间里其他人正在等这一帧之后的心跳。</p>
     */
    private static final int MAX_BATCH_FRAME = 200;
    /**
     * 同一首曲子两次被问「谁有这条链接」之间的最短间隔。
     *
     * <p>前瞻窗每次队列变动、每次换歌都会跑一遍，而一张没人答得上的会员曲可以一直在队里挂着。
     * 没有这一档就是每秒向全房问一遍同一首歌。</p>
     */
    private static final long RESOLVE_ASK_COOLDOWN_MS = 30_000L;

    public final String id;
    public final String code;
    public String name;
    /** 频道没有房主，这里是 null —— 所有读它的地方都必须判空。 */
    public UUID owner;
    /** 普通房间 or 服务器频道。决定「谁说得上话」，播放引擎完全一样。 */
    public final RoomKind kind;
    /**
     * 是否公开（出现在公开房间列表里，任何人都能直接加入）。
     *
     * <p>默认 false：房间是私密的，只有拿到邀请码或被邀请的人能进。频道天生公开。
     */
    public boolean open;

    private final MpConfig cfg;

    /** 客户端自报档案（Mod / VIP / 在听），拼进成员条目用。由 {@link RoomManager} 注入。 */
    private PresenceRegistry presence;
    /**
     * 「这个人是不是服务器管理员」。频道里没有房主，管理权就落在服务器管理员身上，
     * 而 core 不认识任何平台的权限系统，所以由外面注入。
     */
    private Predicate<UUID> serverAdmin = u -> false;

    /** 进行中的投票（频道用）。同时最多一个切歌投票和一个针对某人的踢人投票。 */
    private final List<VoteSession> votes = new ArrayList<>();
    /** 踢人投票失败后的冷却：key 是被投的人，value 是冷却到期时刻。 */
    private final Map<UUID, Long> kickCooldown = new LinkedHashMap<>();

    private final Map<UUID, Member> members = new LinkedHashMap<>();
    /** Players explicitly invited — they may join without knowing the code. */
    private final Map<UUID, Long> invites = new LinkedHashMap<>();

    private final List<QueueEntry> queue = new ArrayList<>();
    private final List<QueueEntry> history = new ArrayList<>();

    private QueueEntry current;
    private boolean playing;
    private long startEpoch;
    private long pausedPos;
    private RepeatMode repeat = RepeatMode.OFF;

    /** Monotonic playback-command counter: clients drop out-of-order commands with it. */
    private long seq;

    /** Set while the room is blocked waiting for a client to re-resolve a stream URL. */
    private boolean waitingUrl;
    private long waitingSince;

    // ── 取流求助的层号：越小越可信 ────────────────────────────────────────────
    //
    // 为什么串行而不是一次全发：一次全发确实最快，可「最快」和「最可信」不是一回事 ——
    // 先答先赢意味着一个手快的脚本贡献者会顶掉一个慢一点的会员答案，而本家会员直链更稳、
    // 不烧第三方额度、过期时间也更从容。串行多等的这几秒只在「上一层真的没人」时才付出。
    // 层与层的判据表见 C:\MUSIC\docs\房间分层代解析-弃权-换平台救援-契约.md §3。
    static final int ASK_SERVER_TOKEN = 0, ASK_MEMBER = 1, ASK_CONTRIBUTED_LX = 2,
            ASK_RESOLVABLE = 3, ASK_REQUESTER = 4, ASK_INSTALLED = 5, ASK_EVERYONE = 6;
    /** 最后一个层号 + 1，表示「没有下一层了」。 */
    static final int ASK_NONE = ASK_EVERYONE + 1;
    /** 一次救援派单的窗口与台数上限走配置（{@code switchRescueWindowMs} / {@code switchRescueAttempts}）。 */

    private int tickCounter;
    private long lastRefreshAsk;
    private long allOfflineSince;
    /**
     * 心跳静默到这一刻为止；0 = 不在静默窗内。
     *
     * <p>命令帧（换歌 / 暂停 / 恢复 / 拖进度 / 等到链接后起播）之后 {@link MpConfig#clockGraceMs}
     * 内不发心跳 —— 全房都在开流，那会儿量出来的偏差不是脱节。<b>房间内 automix 的交叠</b>
     * 要把这个窗按实际交叠长度再拉长一截，详见 {@link #markCommand(long)}。</p>
     */
    private long clockQuietUntil;

    /**
     * 每条曲目上一次被前瞻窗问到链接的时刻（键是 {@code source:songId}）。
     *
     * <p>见 {@link #resolveLookahead()}：那里靠 {@code hasFreshUrl()} 判「这条要不要问」，而答不上来
     * 的曲子（房间里没人有会员）永远不 fresh，于是每次队列变动都会被再问一遍。这一格就是那层的节流。
     * 只增不减会跟着房间寿命长，所以条目离队时在 {@link #dropResolvedKeys} 里顺手收掉。</p>
     */
    private final Map<String, Long> resolveAskedAt = new java.util.HashMap<>();
    /** 前瞻窗在 tick 里的低频计时器，见 {@link #tick()}。 */
    private int lookaheadCounter;

    /**
     * 当前被指派去算交接计划的那一台客户端；{@code null} = 这一首没有指派（没人能算，
     * 或者计划已经到手）。见 {@link #assignAnalyzer}。
     */
    private UUID analyst;
    /** 上面那次指派对应的是哪一首「下一首」；队首一变这份指派就作废。 */
    private long analystEntryId;
    /**
     * 当前这份 {@code current.automixPlan} 是为哪一首「下一首」算的（上报时的队首 entryId）。
     *
     * <p>计划描述的是「从 current 怎么进入 <b>那一首</b>」。插队 / 删歌 / 移动 / 清空都可能把队首
     * 换成别的曲子，而计划挂在 current 上不会自动失效 —— 那样旧计划会被继续下发、并按<b>另一首</b>
     * 的交接点换歌。所以队首一旦不再等于这个值，就作废整份计划、重新指派。见
     * {@link #invalidatePlanIfHeadChanged()}。</p>
     */
    private long planTargetEntryId;
    /** 指派的时刻，用于超时改派。 */
    private long analystAssignedAt;
    /** 这一首已经指派过几轮（含改派），只用于日志与协议里的 {@code attempt}。 */
    private int analystAttempt;
    /**
     * 这一首已经明确拒绝过分析的人。
     *
     * <p>改派时据此刻意跳过他们：否则两个都算不出的人会互相接手，在超时窗里来回弹，
     * 而这首曲子的尾巴早过完了。换歌时整个集合清空。</p>
     */
    private final java.util.Set<UUID> refusedAnalysts = new java.util.LinkedHashSet<>();

    public Room(MpConfig cfg, String id, String code, UUID creator, String creatorName, String name) {
        this.cfg = cfg;
        this.id = id;
        this.code = code;
        this.kind = RoomKind.ROOM;
        this.owner = creator;
        this.name = name == null || name.isBlank() ? creatorName + " 的一起听" : name;
        members.put(owner, new Member(owner, creatorName, Role.OWNER));
    }

    /**
     * 服务器频道：没有创建者、没有房主、天生公开、永不因为空房而关闭。
     *
     * <p>刻意不复用上面那个构造器再把 owner 抹掉：房主为 null 会牵动 remove/promote/snapshot
     * 好几条路径，用一个独立入口把这件事说清楚，比事后修补安全。
     */
    public Room(MpConfig cfg, String id, String code, String name) {
        this.cfg = cfg;
        this.id = id;
        this.code = code;
        this.kind = RoomKind.CHANNEL;
        this.owner = null;
        this.open = true;
        this.name = name == null || name.isBlank() ? "服务器频道" : name;
    }

    /** 注入客户端档案表；不注入也能跑，只是成员条目里的 Mod/VIP 全是空。 */
    public void setPresence(PresenceRegistry registry) { this.presence = registry; }

    /**
     * 服务端自己去解析一条直链（服主配置了平台 Token 时）。
     *
     * <p>注入而不是直接调用：core 里的房间引擎不该知道 HTTP 长什么样，也不该在单元测试里
     * 真的发请求。没注入就退化成「只向客户端要链接」，也就是 v1 的行为。
     */
    @FunctionalInterface
    public interface ServerResolve {
        /** 异步解析；解析成功后自行回调 {@link Room#supplyUrl}。 */
        void request(String source, String songId, String name);
    }

    private ServerResolve serverResolve;

    public void setServerResolve(ServerResolve hook) { this.serverResolve = hook; }

    /** 注入「服务器管理员」判据。频道的管理权全靠它。 */
    public void setServerAdmin(Predicate<UUID> check) {
        if (check != null) this.serverAdmin = check;
    }

    public boolean channel() { return kind.channel(); }

    /** 这个人在本房间是否有管理权（频道里就是服务器管理员）。 */
    public boolean serverAdmin(UUID uuid) {
        try {
            return uuid != null && serverAdmin.test(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── membership ───────────────────────────────────────────────────────────

    public Collection<Member> members() { return members.values(); }

    public Member member(UUID uuid) { return members.get(uuid); }

    public boolean has(UUID uuid) { return members.containsKey(uuid); }

    public Role roleOf(UUID uuid) {
        Member m = members.get(uuid);
        return m == null ? Role.MEMBER : m.role;
    }

    /**
     * 有没有「房主/管理员」那一档权限。
     *
     * <p>频道没有房主，所以改判服务器管理员：频道的歌单由管理员管，切歌／踢人则交给投票 ——
     * 普通成员在频道里拿不到直接控制权，这正是频道和普通房间的分界。
     */
    public boolean controls(UUID uuid) {
        if (channel()) return serverAdmin(uuid);
        Member m = members.get(uuid);
        return m != null && m.role.controls();
    }

    public boolean isOwner(UUID uuid) { return owner != null && owner.equals(uuid); }

    public boolean invited(UUID uuid) { return invites.containsKey(uuid); }

    public void invite(UUID uuid) { invites.put(uuid, System.currentTimeMillis()); }

    public boolean full() {
        int cap = channel() ? cfg.maxChannelMembers : cfg.maxRoomMembers;
        return cap > 0 && members.size() >= cap;
    }

    /** Add (or re-attach) a player. Returns the member record. */
    public Member join(UUID uuid, String name) {
        invites.remove(uuid);
        Member m = members.get(uuid);
        if (m == null) {
            m = new Member(uuid, name, Role.MEMBER);
            members.put(uuid, m);
        } else {
            m.name = name;
        }
        m.online = true;
        m.lastSeen = System.currentTimeMillis();
        allOfflineSince = 0L;
        // 新来的这台可能正好是房间里唯一能算的机器（点歌人自己进来、或他带了模型）。
        refreshAnalyst();
        return m;
    }

    /** Full removal (leave / kick). */
    public void remove(UUID uuid) {
        members.remove(uuid);
        // A leaving requester's pending picks stay: the room agreed to play them, and
        // silently dropping the queue on a disconnect is worse than an unowned entry.
        if (owner != null && uuid.equals(owner)) promoteNewOwner();
        // 走掉的人身上挂着的投票要清：他的那一票不该继续算进分母，针对他的踢人投票也没意义了。
        votes.removeIf(v -> v.kind == VoteSession.Kind.KICK && uuid.equals(v.target));
        // 正被他算着的那一份没了下文：当场重新选人，别让这首曲子一直等到换歌。
        if (uuid.equals(analyst)) {
            // 不预先把 analyst 清空：assignAnalyzer 靠「分析者变了才广播」来通知全房。若这里先置
            // null，当走掉的人正是唯一能算的那台时，assignAnalyzer 会以为「本来就没有分析者」
            // 而不发空的 S_ANALYZE_STATE，全房就把他一直显示成「分析中」直到换歌。
            analystAssignedAt = 0L;
            assignAnalyzer(false);
        }
        // 走掉的可能是最后一个在线的人，剩下的全是离线占位。markOffline 在掉线时会记一笔
        // allOfflineSince，但 leave/kick 这条路不经过它 —— 不补的话 dead() 永远判不出这个空房
        // （members 非空且 allOfflineSince==0 恒为 false），房间成僵尸，占着房号与 maxRooms
        // 名额不回收，攒到上限后别人就建不了房。
        if (!members.isEmpty() && members.values().stream().noneMatch(x -> x.online)) {
            allOfflineSince = System.currentTimeMillis();
        }
    }

    /** Connection lost — keep the seat so a reconnect resumes in-place. */
    public void markOffline(UUID uuid) {
        Member m = members.get(uuid);
        if (m == null) return;
        m.online = false;
        m.listening = false;
        if (members.values().stream().noneMatch(x -> x.online)) {
            allOfflineSince = System.currentTimeMillis();
        }
    }

    /** Hand the crown to the longest-standing online member; keeps admins in front. */
    private void promoteNewOwner() {
        if (channel()) return;                 // 频道本来就没有房主，别凭空造一个
        Member next = members.values().stream()
                .filter(m -> m.online && m.role == Role.ADMIN).findFirst()
                .orElseGet(() -> members.values().stream().filter(m -> m.online).findFirst()
                        .orElseGet(() -> members.values().stream().findFirst().orElse(null)));
        if (next == null) return;
        owner = next.uuid;
        next.role = Role.OWNER;
        notice(next.name + " 已成为新房主");
    }

    public boolean dead() {
        // 频道是服务器设施，不随人走人留而生灭 —— 空着也要在那儿等下一个人进来。
        if (channel()) return false;
        if (members.isEmpty()) return true;
        return allOfflineSince > 0
                && System.currentTimeMillis() - allOfflineSince > cfg.emptyRoomGraceMs;
    }

    // ── queue ────────────────────────────────────────────────────────────────

    public List<QueueEntry> queue() { return queue; }

    public List<QueueEntry> history() { return history; }

    public QueueEntry current() { return current; }

    public boolean isPlaying() { return playing; }

    public RepeatMode repeat() { return repeat; }

    public void setRepeat(RepeatMode mode) {
        this.repeat = mode == null ? RepeatMode.OFF : mode;
        broadcastState();
    }

    /**
     * 单人同时能挂在队上的歌数。
     *
     * <p>频道单列一档（默认 3 首，需求写死的规则）：频道是全服共用的，一个人塞十首就把
     * 后面所有人挡在外面了。普通房间沿用 {@code maxQueuePerMember}。
     */
    public int perMemberCap() {
        return channel() ? cfg.channelMaxPerMember : cfg.maxQueuePerMember;
    }

    public int pendingOf(UUID uuid) {
        int n = 0;
        for (QueueEntry e : queue) if (uuid.equals(e.requester)) n++;
        return n;
    }

    /**
     * 点歌: queue a request. When nothing is playing the request starts immediately,
     * which is what makes the first pick in a fresh room audible without host action.
     *
     * @param playNext 插到队首那段插队位的末尾，而不是排队尾。只对房主/管理员生效 ——
     *                 让任何成员都能插队的话，「下一曲播放」这颗按钮就成了插到所有人前面的后门。
     *                 连续插队之间按插入顺序播（见 {@link #insertAt}），不是倒序。
     * @throws IllegalStateException with a user-facing reason when the queue rejects it
     */
    public QueueEntry enqueue(UUID uuid, String name, Track track, boolean playNext) {
        if (cfg.maxQueueSize > 0 && queue.size() >= cfg.maxQueueSize) {
            throw new IllegalStateException("队列已满");
        }
        // 没有时长就定不出交接点：自动进歌判据是 {@code positionMs() >= fireAt(dur)}，dur=0 时
        // 那句永远不成立，这首歌放完也不会前进，整间房间会卡死在它上面，只能靠有人手按「下一曲」。
        // enqueueBatch 早就挡了这一档，单首点歌这条路一直漏着（见那里的注释）—— 现在一并补上。
        if (track.durationMs <= 0L) {
            throw new IllegalStateException("这首歌缺少时长信息，无法点播");
        }
        int cap = perMemberCap();
        if (!controls(uuid) && cap > 0 && pendingOf(uuid) >= cap) {
            throw new IllegalStateException("你的排队歌曲已达上限（" + cap + " 首）");
        }
        // 去重必须含 current：空房点歌会立刻起播并移出 queue（见下面 pollFront），那只扫
        // queue 的话，起播完成前同一首的第二帧（双击/连点）命中不了去重，会再入队一遍——
        // 用户看到的就是同一首连播两次。enqueueBatch 的 seen 一直含 current，两处对齐。
        if (current != null && current.track.key().equals(track.key())) {
            throw new IllegalStateException("这首歌正在播放");
        }
        for (QueueEntry e : queue) {
            // 按 source:songId 比，而不是只比 songId：跨平台之后不同平台会撞到相同的数字 id。
            if (e.track.key().equals(track.key())) throw new IllegalStateException("这首歌已经在队列里了");
        }
        QueueEntry entry = new QueueEntry(track, uuid, name);
        entry.tier = playNext && controls(uuid) ? QueueEntry.TIER_JUMP : QueueEntry.TIER_REQUEST;
        // 点歌自带可用直链时按「点歌人层」记账。不记账（留在 ADOPTED_NONE）的话，这条链
        // 在分层视角里是「没采纳过任何答案」——任何成员的迟到/伪造 url_supply（层号被
        // 服务端推导为最不可信的 ASK_EVERYONE）都能把它静默顶掉。记了账，链接真的过期、
        // 房间重新为它开分层求助时，supplyUrl 会把记账复位回 ADOPTED_NONE，正常路照走。
        if (track.hasFreshUrl()) entry.adoptedTier = QueueEntry.ADOPTED_REQUESTER;
        queue.add(insertAt(entry.tier), entry);
        if (current == null) {
            startEntry(pollFront(entry), 0L);
        } else {
            // 插队会把队首换成刚点的这首 —— 挂在 current 上、为原队首算的计划就作废了。
            invalidatePlanIfHeadChanged();
            broadcastQueue();
            if (!entry.track.hasFreshUrl()) {
                // 点歌人本机解析不出完整音频（多半是会员曲而他不是会员），所以这首歌是「裸」进
                // 队的。现在就静默向全房要一条，而不是等轮到它才要 —— 那时房间会真的卡住
                // urlWaitTimeoutMs 那么久，运气不好直接被跳过。
                //
                // 必须放在 broadcastQueue() 之后：非网易云平台的客户端要靠歌单快照里的元数据
                // 才能重建曲目去解析，快照还没到就只能沉默。
                requestUrl(entry, true);
            }
        }
        return entry;
    }

    /**
     * 整张歌单灌进队列：全部落在<b>第三档</b>，排在所有插队与排队歌之后。
     *
     * <p><b>为什么是一条批量帧而不是 N 条 {@link #enqueue}。</b>逐首走点歌那条路的话，100 首就是
     * 100 次 {@code broadcastQueue()} —— 而每一帧广播都带<b>整份</b>歌单，等于往全房发了上万条曲目；
     * 更要命的是每首裸歌入队都会当场 {@code requestUrl} 一次，于是 100 首要向房间里问 100 轮解析，
     * 客户端那一侧就是 100 次真取流。一首一首点时这些开销无所谓，因为一次只点一首；灌单必须换个形状。</p>
     *
     * <p><b>绝不打断正在播的那一首。</b>这里面没有 {@code playNow}：只有在<b>房间本来就没歌在放</b>
     * 的时候才起播这一批的第一首，那不算打断，那是「终于有歌可放了」。正在放的歌继续按它自己的
     * 交叠计划交接 —— 这一条是需求里「已经播放的不算」的意思：优先级排的只是<b>待播</b>的那三档，
     * 而灌单这个动作本身不碰播放头。</p>
     *
     * <p><b>去重是静默跳过，不是报错。</b>{@link #enqueue} 撞到重复要抛，因为那只对应一次点击；
     * 一张单里有几首已经排上、或者正好是眼下在放的那首是常态，为它把整张单退回等于白灌。</p>
     *
     * <p><b>曲目一律不带直链。</b>客户端在这里只报元数据，谁真轮到才向房间要链接，由
     * {@link #resolveLookahead()} 按前瞻窗分批问（见那里的注释）。所以 {@code Track.fromJson} 的
     * 试听片段那一判在灌单这一路不会拦人 —— 拦了也只是跳过这一首，不影响整张单。</p>
     *
     * <p><b>人均排队上限 {@link #perMemberCap()} 在这里刻意不适用</b>：那一档是为了防止有人一首一首
     * 把房间占满，而灌单一次就是几十首，套上去等于这个功能永远点不动。代替它的是
     * {@link MpConfig#playlistBatchLimit}（单次总量）加 {@code maxQueueSize}（房间总量）两层闸。</p>
     *
     * @param playlistName 歌单名，进 {@link QueueEntry#playlist}，界面据此标出「这批是自动排的」
     * @param tracks       {@code tracks} 数组，逐条走 {@link Track#fromJson}；坏的跳过
     * @throws IllegalStateException 一首都没排上（全重复 / 全非法 / 队列已满），或单帧过大
     */
    public BatchResult enqueueBatch(UUID uuid, String name, String playlistName, JsonArray tracks) {
        if (tracks == null || tracks.isEmpty()) throw new IllegalStateException("这张歌单里没有可以分享的曲目");
        if (tracks.size() > MAX_BATCH_FRAME) {
            throw new IllegalStateException("一次最多灌 " + MAX_BATCH_FRAME + " 首，请把长歌单分批投放");
        }
        String label = playlistName == null || playlistName.isBlank()
                ? "未命名歌单" : playlistName.trim();
        if (label.length() > 80) label = label.substring(0, 80);

        int limit = cfg.playlistBatchLimit > 0
                ? Math.min(cfg.playlistBatchLimit, tracks.size()) : tracks.size();
        // 已经在队上的 / 正在播的那些 key 先收进一个集合：否则每灌一首都要扫一遍整队，
        // 100 首 × 200 条队列就是两万次字符串拼接。
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (QueueEntry e : queue) seen.add(e.track.key());
        if (current != null) seen.add(current.track.key());

        int added = 0, duplicate = 0, invalid = 0, noLength = 0;
        int capLeft = cfg.maxQueueSize > 0 ? cfg.maxQueueSize - queue.size() : tracks.size();
        for (int i = 0; i < limit; i++) {
            try {
                if (!tracks.get(i).isJsonObject()) { invalid++; continue; }
                Track track = Track.fromJson(tracks.get(i).getAsJsonObject(), cfg);
                if (track.durationMs <= 0L) {
                    // 没有时长就不知道什么时候交接：房间的自动进歌判据是
                    // {@code positionMs() >= fireAt(dur)}，dur=0 时那句永远不成立，整间房间会
                    // 卡在这一首上不动，只能靠有人手按「下一曲」救回来。一首一首点的时候这个坑
                    // 已经在（而且点歌人自己那台通常知道时长），但一次灌一百首时它就是「房间停了
                    // 半小时」，所以这里宁可跳过并说清楚 —— 有些平台的歌单列表行只给标题不给时长，
                    // 要点开详情才有，那种平台的整张单会全部落到这个计数里，提示语会写明。
                    noLength++;
                    continue;
                }
                String key = track.key();
                if (!seen.add(key)) { duplicate++; continue; }
                if (capLeft <= 0) { invalid++; continue; }   // 队列已满，剩下的都不排
                QueueEntry entry = new QueueEntry(track, uuid, name);
                entry.tier = QueueEntry.TIER_PLAYLIST;
                entry.playlist = label;
                // 歌单档永远落在<b>队尾</b>：insertAt 在这里退化成 queue.size()，
                // 于是同一张单内部的顺序就是歌单本身的顺序。
                queue.add(insertAt(QueueEntry.TIER_PLAYLIST), entry);
                added++;
                capLeft--;
            } catch (Throwable ignored) {
                invalid++;   // 非法平台 / 没有歌曲 ID / 只有试听片段
            }
        }
        if (added == 0) {
            throw new IllegalStateException(firstReason(duplicate, invalid, noLength));
        }
        boolean truncated = limit < tracks.size();
        BatchResult result = new BatchResult(label, added, duplicate, invalid, noLength, truncated);
        if (current == null) {
            // 起播的是<b>整条队列的头</b>，不是这张单子的第一首：房间里可能已经有人排着队，
            // 按优先级他们那些本来就该先到。这里为空才轮不到他们，所以取 queue.get(0) 就够了。
            startEntry(pollFront(queue.get(0)), 0L);
        } else {
            // 队首没动（灌单不插队），所以这里只是把新快照推给全房，然后按前瞻窗开始要链接。
            broadcastQueue();
            resolveLookahead();
        }
        return result;
    }

    /** 一首都没排上时，按最可能的那个原因说一句话。 */
    private static String firstReason(int duplicate, int invalid, int noLength) {
        if (noLength > 0 && noLength >= duplicate && noLength >= invalid) {
            return "这些歌没有时长信息，房间不知道什么时候换歌（换一张给了时长的歌单再试）";
        }
        if (duplicate > 0 && duplicate >= invalid) return "这些歌已经在队列里或正在播放";
        return "这张歌单里没有能进一起听的曲目（缺曲目 ID、平台未开放或只有试听片段）";
    }

    /** 一次灌单的结果汇总，只为了把「怎么少了 8 首」在聊天栏里说清楚。 */
    public static final class BatchResult {
        public final String playlist;
        public final int added;
        /** 队列里已有 / 正是当前在播的。 */
        public final int duplicate;
        /** 负载非法（缺歌曲 ID、平台未开放、只有试听片段）与被房间总队列上限挡下的。 */
        public final int invalid;
        /** 没有时长信息的：房间不知道该什么时候把它交接出去，所以一条都不排。 */
        public final int noLength;
        /** 被单次上限 {@code playlistBatchLimit} 截断过。 */
        public final boolean truncated;

        BatchResult(String playlist, int added, int duplicate, int invalid, int noLength,
                    boolean truncated) {
            this.playlist = playlist;
            this.added = added;
            this.duplicate = duplicate;
            this.invalid = invalid;
            this.noLength = noLength;
            this.truncated = truncated;
        }

        /** 给聊天栏的一句话；只把真发生了的损失说出来，全顺利时不啰嗦。 */
        public String describe() {
            StringBuilder sb = new StringBuilder("已排入歌单《").append(playlist)
                    .append("》").append(added).append(" 首");
            List<String> notes = new ArrayList<>();
            if (duplicate > 0) notes.add("跳过 " + duplicate + " 首已在队列或正在播");
            if (noLength > 0) notes.add("跳过 " + noLength + " 首没有时长信息");
            if (invalid > 0) notes.add("跳过 " + invalid + " 首不能播或队列已满");
            if (truncated) notes.add("按单次上限截断");
            if (!notes.isEmpty()) sb.append("（").append(String.join("，", notes)).append("）");
            return sb.toString();
        }
    }

    /**
     * 这一档应该落在哪个下标：队列里第一个「档位比我高（排得更后）」的条目之前；一个都没有就落末尾。
     *
     * <p>它取代了原来的 {@code jumpInsertAt()}。那个方法只需要回答「插到队首那段插过队的之后」，
     * 因为当时只有两档。三档之后同一句判断要覆盖三种来路：插队压在排队歌与歌单歌之前；点歌压在
     * 歌单歌之前但排在插队段之后；歌单谁都不压，直接垫到最后。同一档内部保持先到先得，所以房主连点
     * 三首「下一首播放」听到的仍然是 1、2、3 的正序 —— 这一条和改造前完全一致。</p>
     *
     * <p>这里按 {@code <=} 比较，靠的是队列<b>大体上按档位分段</b>这个不变式：所有自动插入都走
     * 这个方法。唯一的例外是 {@link #moveEntry} 那种管理者显式挪位，它会把被挪的那一条按落点重新
     * 定档（队首=插队、其余=排队），所以「把歌单里的某首拎到中间」之后它不会再被后来的点歌跨过。
     * 极端挪法（把一条排队歌精确插进歌单段正中）会让分段不那么整齐，后果只是后来的点歌落在它前面
     * —— 那是管理者自己排的顺序，不是队列排序坏了。</p>
     */
    private int insertAt(int tier) {
        int at = 0;
        while (at < queue.size() && queue.get(at).tier <= tier) at++;
        return at;
    }

    private QueueEntry pollFront(QueueEntry expected) {
        int idx = queue.indexOf(expected);
        return idx >= 0 ? queue.remove(idx) : expected;
    }

    public QueueEntry findEntry(long entryId) {
        for (QueueEntry e : queue) if (e.entryId == entryId) return e;
        return null;
    }

    public boolean removeEntry(long entryId) {
        boolean removed = queue.removeIf(e -> e.entryId == entryId);
        if (removed) {
            // 删掉的可能正是队首（计划瞄准的那一首）—— 作废旧计划再广播。
            invalidatePlanIfHeadChanged();
            broadcastQueue();
        }
        return removed;
    }

    public boolean moveEntry(long entryId, int to) {
        int from = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).entryId == entryId) { from = i; break; }
        if (from < 0) return false;
        int target = Math.max(0, Math.min(queue.size() - 1, to));
        QueueEntry e = queue.remove(from);
        // 手动挪位是管理者的显式排位，所以这一条从此<b>不再受档位规则支配</b>：挪到队首就是
        // 「下一首放它」（升成插队档，否则后面来一首普通点歌会按「点歌排在歌单之前」把它挤下去，
        // 置顶等于没做）；挪到中间则升成排队档，不再被后来的点歌跨过。
        e.tier = target == 0 ? QueueEntry.TIER_JUMP : QueueEntry.TIER_REQUEST;
        queue.add(target, e);
        // 移动可能把别的曲子挪到队首 —— 旧计划瞄准的已不是队首了。
        invalidatePlanIfHeadChanged();
        broadcastQueue();
        return true;
    }

    public void clearQueue() {
        queue.clear();
        // 队列空了就没有「下一首」，挂着的计划失去意义。
        invalidatePlanIfHeadChanged();
        broadcastQueue();
    }

    /**
     * 撤回某张歌单灌进来、<b>还没播</b>的那批。
     *
     * <p>只删 {@link QueueEntry#playlist} 对上号的条目，而且<b>永不碰 {@link #current}</b>：
     * 正在播的那一首哪怕正是这张单子里的，也已经播开了，撤走它等于全房硬切。这一条和灌单那句
     * 「不打断正在播放的」是同一件事的两头 —— 进不来打断，出去也不该打断。</p>
     *
     * <p>非管理者只能撤自己灌的那批（按 {@code requester} 再判一次）。否则任何一个成员都能靠
     * 报一个别人的歌单名把对方的队列拆掉，而界面上的名字是客户端自己填的。</p>
     *
     * @return 实际撤掉的条数，0 表示这个名字下没有任何还没播的条目
     */
    public int removePlaylist(UUID actor, String playlist) {
        if (playlist == null || playlist.isEmpty()) return 0;
        boolean control = controls(actor);
        List<QueueEntry> doomed = new ArrayList<>();
        for (QueueEntry e : queue) {
            if (!playlist.equals(e.playlist)) continue;
            if (!control && (actor == null || !actor.equals(e.requester))) continue;
            doomed.add(e);
        }
        if (doomed.isEmpty()) return 0;
        queue.removeAll(doomed);
        invalidatePlanIfHeadChanged();
        broadcastQueue();
        return doomed.size();
    }

    // ── playback ─────────────────────────────────────────────────────────────

    /**
     * 记一次命令帧下发。之后 {@link MpConfig#clockGraceMs} 毫秒内不发时钟心跳。
     *
     * <p>同时把 {@link #tickCounter} 归零：静默窗一结束就立刻打一发心跳没有意义，
     * 那时候慢的客户端可能刚开始播，让它走满一个完整的广播间隔再校准。</p>
     */
    private void markCommand() {
        markCommand(0L);
    }

    /**
     * 同上，但把静默窗按一次<b>已知的过渡长度</b>来开：取 {@code protectMs} 与配置里那个窗
     * 较远的那个，并夹到 {@link #CLOCK_QUIET_MAX_MS}。
     *
     * <p><b>为什么交叠要单独算。</b>房间里的混音长度由音频内容决定（{@code overlapMillis}，
     * Folia 能挑出一段二十秒以上的长尾奏），而 {@link MpConfig#clockGraceMs} 只跟着配置的
     * 提前量走（{@code load()} 的下限是 {@code automixLeadMs + 1000}）。窗只开五秒、淡入还在
     * 继续时心跳就恢复，各客户端量到的「偏差」其实是本机比房间晚了几秒开始淡入 —— 帧延迟、
     * 一条本来就落在软档里没纠的漂移、加上新播放牌的缓冲。拿它去比硬阈值，客户端就会在淡入
     * 没结束时把刚进来的那一条薅回去，用户听到的正是交叠被截断。</p>
     *
     * <p>心跳在段里不发，客户端靠上一帧的位置自己外推（它本来就在两帧之间外推），界面照常走，
     * 一段混音结束之后照旧恢复校准 —— 少几发心跳不换来的是别把播放头薅断。</p>
     *
     * @param protectMs 这一次过渡有多长；&lt;= 0 表示没有过渡，按配置的窗走
     */
    private void markCommand(long protectMs) {
        long now = System.currentTimeMillis();
        tickCounter = 0;
        long window = cfg.clockGraceMs;
        if (protectMs > window) window = Math.min(protectMs, CLOCK_QUIET_MAX_MS);
        clockQuietUntil = window > 0L ? now + window : 0L;
    }

    /** 是否还在命令帧之后的时钟静默窗内。 */
    private boolean inClockGrace(long now) {
        return clockQuietUntil > 0L && now < clockQuietUntil;
    }

    /**
     * 心跳还要被压多久（毫秒）；不在窗内时为 0。
     *
     * <p>诊断用：服主调 {@code clockGraceMs}／交接计划报了多长的交叠，最终落到这个数上。
     * 出问题时的第一种问法就是「那几秒到底为什么不校准」。</p>
     */
    public long clockQuietRemainingMs() {
        long left = clockQuietUntil - System.currentTimeMillis();
        return left > 0L ? left : 0L;
    }

    /**
     * 这一次交接<b>如果</b>按上报的计划走，会叠多久：离开的那一首所带计划里的
     * {@code overlapMillis}；没开交叠、或这一首根本没有计划时为 0。
     *
     * <p>必须在 {@code current} 被换掉之前读 —— 那份计划描述的就是这一次交接。</p>
     */
    private long outgoingBlendMillis() {
        if (cfg.automixLeadMs <= 0L) return 0L;
        JsonObject plan = current == null ? null : current.automixPlan;
        if (plan == null) return 0L;
        long overlap = Proto.num(plan, "overlapMillis", 0L);
        if (overlap <= 0L) return 0L;
        return Math.min(overlap, MAX_OVERLAP_MS);
    }

    /** 此刻是否允许提前换歌：见 {@link #advanceLead()}。 */
    private boolean mayAdvanceEarly() {
        if (current == null) return false;
        if (repeat == RepeatMode.ONE) return false;
        if (queue.isEmpty() && repeat != RepeatMode.ALL) return false;
        return current.track.durationMs > END_EPSILON_MS + 1000L;
    }

    /**
     * 自动进下一首的提前量，见 {@link MpConfig#automixLeadMs}。
     *
     * <p>这一条只在<b>没有</b>房主上报的计划时兜底，见 {@link #fireAt(long)}。为 0 时就是原来的
     * {@link #END_EPSILON_MS}，整条播放路径与旧版完全一致。</p>
     *
     * <p>开启后也只在「确实还有另一首可播」时才提前。单曲循环重播的是同一首，提前等于把它的
     * 结尾截掉；队列空且不是列表循环时下一刻就是停止，提前会让最后一首也少放一截 ——
     * 两种情况都退回原判据。此外提前量还会被曲目长度夹一次，免得短曲目一进去就判定「已结束」。</p>
     */
    private long advanceLead() {
        if (!mayAdvanceEarly()) return END_EPSILON_MS;
        long lead = cfg.automixLeadMs;
        if (lead <= 0L) return END_EPSILON_MS;
        return Math.min(lead, current.track.durationMs - END_EPSILON_MS - 1000L);
    }

    /**
     * 这一首该在哪个位置触发换歌。
     *
     * <p><b>房主报上来的计划优先。</b>交接点跟着音频内容走：可能是最后一句人声的收尾处，
     * 也可能是一段长尾奏的起点 —— 和「距曲末固定多少毫秒」没有关系，这正是房间里
     * 无缝交接与普通定时淡化的区别。计划里的位置是绝对位置，所以不受元数据时长与实际
     * 解码时长之间那点出入的影响。</p>
     *
     * <p>计划由房主那台用它自己选的引擎算出（内置信号处理，或装了模型就用 Folia），
     * 服务端不解释其中的曲线 —— 它整份存下、整份下发，做的是裁决而不是翻译：
     * 「怎么切、从哪里进、用哪条曲线」全在那一份报文里，各端照着执行同一条母带。</p>
     *
     * <p>没有计划时退回 {@code 曲末 - advanceLead()}，也就是改造前的判据 —— 房主还没算完、
     * 算不出、或者客户端是旧版本时，房间行为与旧版一致。</p>
     */
    private long fireAt(long dur) {
        JsonObject plan = current == null ? null : current.automixPlan;
        if (plan != null && mayAdvanceEarly()) {
            long fire = Proto.num(plan, "fireMillis", 0L);
            // 合法的交接点被夹在一个窗口里，越界的一律不信、退回配置提前量：
            //   上限 `dur - END_EPSILON_MS`：不能落到曲末之后，否则这一首先播完、中间空出静默；
            //   下限跟着计划自己的交叠长度走。交接点从曲末往前挪的极限就是「这份计划打算叠多长」：
            //     内容驱动的交接在 fire 处开始把 A 淡出、B 淡入，A 的尾巴被这段交叠吃掉，所以合法
            //     的 fire 不该早于 `dur - overlap`。
            //   为什么不是固定的 12 秒：`automixLeadMs`（配置提前量）封顶 12 秒，但计划挑的是一段
            //     完整的长尾奏/间奏 —— 客户端 Folia 交叠最长约 25 秒、RoomAutomix 允许到 60 秒。
            //     用固定 12 秒当下限会把这些合法的长交叠计划误杀、退回硬切，把精心算好的无缝长过渡
            //     砍掉。下限取 `dur - max(MAX_LEAD_MS, overlap)` 才两头都对：modernMP 那种「fire
            //     报成曲末前 25 秒、overlap 却只有 5 秒」的坏计划（fire 远早于 dur - overlap）仍被
            //     挡下，而 Folia 的长交叠计划照常放行。
            long overlap = Proto.num(plan, "overlapMillis", 0L);
            if (overlap < 0L) overlap = 0L;
            if (overlap > MAX_OVERLAP_MS) overlap = MAX_OVERLAP_MS;   // 防一个离谱的 overlap 把下限撑穿
            long floor = dur - Math.max(MAX_LEAD_MS, overlap);
            if (fire >= floor && fire <= dur - END_EPSILON_MS) return fire;
        }
        return dur - advanceLead();
    }

    /**
     * 新曲目的入口点：跳过死寂前奏，从主歌 / 首个强拍进入（房主分析前奏长度得出）。
     *
     * <p>它同时改变服务端的时钟基准 —— 新曲目的 0 点就是这一位置，所以全房（含中途进房、
     * 断线重连的人）对「唱到哪了」的理解仍然一致，心跳不会因此触发纠偏。
     * 正因为如此它才能安全地跟计划一起下发。</p>
     *
     * @return 入口位置；没有计划或计划不可信时为 0（从头开始）
     */
    private long plannedCue() {
        JsonObject plan = current == null ? null : current.automixPlan;
        if (plan == null) return 0L;
        long cue = Proto.num(plan, "cueMillis", 0L);
        if (cue <= 0L) return 0L;
        QueueEntry head = queue.isEmpty() ? null : queue.get(0);
        long dur = head == null ? 0L : head.track.durationMs;
        // 入口点不该落到后半首：越界值只可能是对错了曲目，退回从头开始更安全。
        if (dur > 0L && cue > dur / 2L) return 0L;
        return cue;
    }

    /**
     * 接下一份交接计划：这一首放完时具体怎么交给下一首。
     *
     * <p><b>谁能报。</b>服务端在这里充当裁决者，保证全房执行的是同一份计划，但它不再要求
     * 「上报者必须是房主」—— 它只认「是不是我刚刚指派的那一台」（见 {@link #assignAnalyzer}）。
     * 指派顺序是点歌人 → 房主 → 房间里任何自报能算的人，所以上报者可能是任何人；
     * 谁算出来的不重要，全房拿到同一份计划才重要。</p>
     *
     * <p>计划必须指向「当前正在播的那一首的下一首」，否则说明队列已经变过（有人插歌、跳歌），
     * 这份计划对应的是另一首曲子，直接丢弃。</p>
     *
     * @param actor       上报者
     * @param nextEntryId 计划里那一首「下一首」的条目号
     * @param plan        计划本体，字段见客户端 {@code RoomAutomix#encode}
     */
    public void reportPlan(UUID actor, long nextEntryId, JsonObject plan) {
        if (current == null || plan == null) return;
        if (!mayReportPlan(actor, nextEntryId)) return;
        // 同一份计划不必再广播一次：上报会被重试，而每次广播都会让客户端重跑一遍预置。
        if (current.automixPlan != null && current.automixPlan.toString().equals(plan.toString())) {
            return;
        }
        current.automixPlan = plan;
        // 记下这份计划瞄准的是哪一首「下一首」。之后队首一旦变过（插队 / 删歌 / 移动），
        // invalidatePlanIfHeadChanged() 就据此把它作废，免得按另一首的交接点换歌。
        planTargetEntryId = nextEntryId;
        // 这一份已经到手，指派到此结束：清掉待办，免得超时又把它改派给别人、白算第二遍。
        analyst = null;
        analystEntryId = 0L;
        broadcastQueue();
        broadcastAnalyzeState();
    }

    /**
     * 队首变了就作废挂在 current 上的计划并重新指派。
     *
     * <p>计划描述的是「从 current 进入<b>某一首特定的下一首</b>」（{@link #planTargetEntryId}）。
     * 插队（{@code queue.add(0, …)}）、删歌、移动、清空、抢播都可能把队首换成别的曲子，
     * 而计划挂在 current 上不随之失效。不作废的话：{@link #nextJson()} 继续把旧计划下发给
     * 新的下一首、{@link #fireAt}/{@link #plannedCue} 用为<b>另一首</b>算的 fire/cue 换歌、
     * {@link #mayReportPlan} 又因 {@code automixPlan != null} 把新队首该有的计划永远挡死。</p>
     *
     * <p>所有会改动队首的入口在广播歌单之前都要调它一次。current 尚未开播（计划本就为 null）、
     * 或队首仍是原来那一首时，它什么都不做。</p>
     */
    private void invalidatePlanIfHeadChanged() {
        if (current == null || current.automixPlan == null) return;
        long head = queue.isEmpty() ? 0L : queue.get(0).entryId;
        if (head == planTargetEntryId) return;   // 队首没变，计划仍然有效
        current.automixPlan = null;
        planTargetEntryId = 0L;
        // 新队首需要一份新计划：清掉旧指派并当场重新选人（换歌尚早，还来得及算）。
        clearAnalyst();
        assignAnalyzer(false);
    }

    /**
     * 这个人现在有没有资格为「nextEntryId 这一首」上报计划。
     *
     * <p>三种放行：① 本机就是当前被指派者；② 还没有人上报过、且来的人正好是房主
     * （兼容旧客户端 —— 老版本客户端只会在房主那台上报，服务端不能因为引入了指派就把它锁在门外）；
     * ③ 还没有计划、也没有指派记录，但来的人确实是房间里能算的成员（兜底：有人抢先算完了）。
     * 其余一律丢弃。</p>
     */
    private boolean mayReportPlan(UUID actor, long nextEntryId) {
        if (actor == null) return false;
        // 计划必须说的是「正在播的这一首的下一首」——entryId 不符说明队列已经变过。
        if (queue.isEmpty() || queue.get(0).entryId != nextEntryId) return false;
        if (current.automixPlan != null) return false;                 // 已经有一份了，不再接受第二份
        if (analyst != null && analyst.equals(actor)) return true;     // ① 就是我叫的那一台
        // ② 兼容老客户端：老版本只在房主那台上报，服务端从不指派（analyst 恒 null）时放行它。
        //    但**必须加 analyst == null** —— 否则服务端已经把活儿派给房里装了模型的另一台时，
        //    房主会在第一帧 analyze_state 到达前抢先算完上报，把该借用的那份计划挤掉。
        if (analyst == null && isOwner(actor)) return true;
        // ③ 兜底：真的能算的人抢先算完了。同样**必须加 analyst == null** —— 已经派给了某一台时，
        //    另一台能算的成员抢先上报不该顶掉被指派者，否则「模型优先」的指派就形同虚设。
        return analyst == null && analyzable(actor);
    }

    /**
     * 这个人自报能不能跑交接计划的分析。
     *
     * <p>只看客户端自报的能力位，<b>不因为「他是房主」就当他能算</b> —— 房主那台很可能正是
     * 房间里唯一算不出来的那台（modernMP 没有分析这个概念，它必须借别人的机器）。
     * 把房主无条件当成能算，会让「modernMP 当房主」的房间永远指着它等一个交不出的计划。</p>
     *
     * <p>但有一点要让房主兜底：<b>房间里全是老客户端</b>（一个能力位都没报过）时，如果连房主
     * 都不问，房间就再没有任何出路了。老客户端不报能力位，可它的房主身份说明它至少在跑
     * 「能执行房间交接」的那套代码。所以兜底条件是「全房间没有任何一台报了能力位」，
     * 而不是「这台是房主」—— 见 {@link #pickAnalyst}。</p>
     */
    private boolean analyzable(UUID uuid) {
        if (uuid == null) return false;
        PresenceRegistry pr = presence;
        if (pr == null) return false;
        ClientProfile p = pr.get(uuid);
        return p != null && p.automixCapable;
    }

    /**
     * 房间里有没有任何一台<b>报过</b>档案（profile / hello）。决定要不要走老客户端的兜底。
     */
    private boolean anyoneReportedProfile() {
        PresenceRegistry pr = presence;
        if (pr == null) return false;
        for (Member m : members.values()) {
            if (pr.get(m.uuid) != null) return true;
        }
        return false;
    }

    /** 房间里有没有任何一台自报过分析能力。 */
    private boolean anyoneReportedCapability() {
        PresenceRegistry pr = presence;
        if (pr == null) return false;
        for (Member m : members.values()) {
            ClientProfile p = pr.get(m.uuid);
            if (p != null && p.automixCapable) return true;
        }
        return false;
    }

    /**
     * 被指派人拒绝这次分析：立刻改派下一台，不必干等到超时。
     *
     * <p>这是「借用别人的电脑」能实用的关键。若只靠超时，每一首在真正能算的那台被问到之前
     * 都要白等一个 {@link MpConfig#automixAssignTimeoutMs}；而拒绝是明确信号，当场就能换人。</p>
     */
    public void declinePlan(UUID actor, long nextEntryId) {
        if (actor == null || current == null) return;
        if (queue.isEmpty() || queue.get(0).entryId != nextEntryId) return;
        if (analyst == null || !analyst.equals(actor)) return;
        System.out.println("[mpsync] 被指派人放弃本次分析 entry=" + nextEntryId
                + "，改派下一台（房间 " + id + "）");
        // 先把他记进「问过、不行」的名单再改派 —— 否则 pickAnalyst 会按优先级又选中他，
        // 活派回刚拒绝的那台，这首曲子就永远等不到计划了。
        refusedAnalysts.add(actor);
        assignAnalyzer(true);
    }

    /**
     * 选出这一首的分析者，并把任务派下去。
     *
     * <p><b>顺序即「谁最该算」</b>：① <b>点歌人</b> —— 你要的歌你自己最该出这份算力，而且
     * 音频很可能已经在他本机解码缓存里了；② <b>房主</b> —— 没有点歌人（或点歌人算不了）时
     * 的默认人选；③ <b>房间里其他能算的人</b> —— 也就是「借用别人的电脑」，按档位高的优先
     * （装了模型的排在只有内置信号处理的前面），同档位按加入房间的顺序，保证结果稳定可复现。</p>
     *
     * <p>被拒绝过的人不再入选（{@code refusedAnalysts}），否则改派会在两个都不行的人之间来回弹。</p>
     *
     * @param force 被拒绝后改派时为 true：跳过当前那个人重新选
     */
    private void assignAnalyzer(boolean force) {
        if (current == null || queue.isEmpty()) { clearAnalyst(); return; }
        long entryId = queue.get(0).entryId;
        // 已经有计划 / 房间没开交叠 / 不该提前换歌 —— 三种情况都不需要分析。
        if (current.automixPlan != null || cfg.automixLeadMs <= 0L || !mayAdvanceEarly()) {
            clearAnalyst();
            return;
        }

        UUID chosen = pickAnalyst(entryId);
        if (chosen == null) {
            // 房间里没有任何一台能算（全是老客户端 / 全是 modernMP 而没人在线可借）。
            // 这不是错误：全房这一首就走「没有计划 → 服务端到点换歌」的降级路，
            // 与旧版行为一致，只是没有交叠。
            if (analyst != null || analystEntryId != entryId) {
                analyst = null;
                analystEntryId = entryId;
                broadcastAnalyzeState();
            }
            return;
        }

        analyst = chosen;
        analystEntryId = entryId;
        analystAssignedAt = System.currentTimeMillis();
        analystAttempt++;

        JsonObject m = Proto.msg(Proto.S_ANALYZE_ASSIGN);
        m.addProperty("entryId", entryId);
        m.addProperty("attempt", analystAttempt);
        // 0 = 无期限（超时被取消）。这时不能报一个「已经过期」的时刻，客户端会以为下一秒就该换人。
        m.addProperty("deadlineMs", cfg.automixAssignTimeoutMs > 0L
                ? analystAssignedAt + cfg.automixAssignTimeoutMs : 0L);
        // 分析要读两首歌的音频，所以两首都带上直链。当前这一首用 withUrl = true 拿到签名链接；
        // 下一首同理 —— 房间里本来就会把它的直链发给全房（D4），这里不新增暴露面。
        if (current != null) m.add("current", current.toJson(true));
        m.add("next", queue.get(0).toJson(queue.get(0).track.hasFreshUrl()));
        send(chosen, m);
        broadcastAnalyzeState();

        Member m2 = members.get(chosen);
        String name = m2 == null ? "?" : m2.name;
        System.out.println("[mpsync] 指派分析 entry=" + entryId + " → " + name
                + "（第 " + analystAttempt + " 次，房间 " + id + "）");
        if (force) return;
    }

    /**
     * 按优先级挑一台能算的客户端；一个都没有时返回 {@code null}。
     *
     * <p>点歌人优先是这次改动的核心 —— 「谁点的歌就用谁的电脑先尝试计算推理」。</p>
     */
    private UUID pickAnalyst(long entryId) {
        // 老客户端兜底：**一台都没报过档案**时，能力位无从谈起 —— 它们的房主身份就是唯一的
        // 线索。但只要有人报过档案（哪怕报的是「我不能算」），就说明房间里跑的是懂这套协议的
        // 客户端，此时必须以能力位为准，不能把一台明确说了「我不行」的机器硬派上去。
        boolean legacyFallback = !anyoneReportedProfile();
        QueueEntry head = queue.isEmpty() ? null : queue.get(0);
        UUID requester = head == null ? null : head.requester;

        // 老客户端：没有能力位可依，只认房主身份（点歌人恰好是房主时也走这条）。这与
        // 「老版本只在房主那台上报」的历史行为一致。
        if (legacyFallback) {
            if (requester != null && online(requester) && !refusedAnalysts.contains(requester)
                    && isOwner(requester)) {
                return requester;
            }
            if (owner != null && online(owner) && !refusedAnalysts.contains(owner)) {
                return owner;
            }
            return null;
        }

        // 有能力位：**模型优先**，跨「点歌人 / 房主 / 任意成员」统一排序。评分先比引擎档位
        // （model > dsp），再比角色（点歌人 > 房主 > 其他）。于是实际顺序是：
        //   点歌人(model) → 房主(model) → 任意(model) → 点歌人(dsp) → 房主(dsp) → 任意(dsp)。
        // 这才满足需求「即便房主也借用装了模型的机器」—— 从前的写法里点歌人 / 房主只要自报
        // capable 就直接选中，engineRank 只在第三档内部比较，装了模型的机器永远抢不过它们。
        // 同分（同档位同角色）用 members 的插入顺序（加入房间的先后）稳定取第一个，可复现。
        UUID best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Member m : members.values()) {
            if (!m.online || refusedAnalysts.contains(m.uuid)) continue;
            if (!analyzable(m.uuid)) continue;
            int score = analystScore(m.uuid, requester);
            if (score > bestScore) { bestScore = score; best = m.uuid; }
        }
        return best;
    }

    /**
     * 指派评分：引擎档位为主（{@code model} = 2、{@code dsp} = 1），角色为辅
     * （点歌人 +2、房主 +1、其他 0）。档位差（×10）永远压过角色差，保证「装了模型的任意成员」
     * 排在「只有内置信号处理的点歌人 / 房主」之前。
     */
    private int analystScore(UUID uuid, UUID requester) {
        int score = engineRank(uuid) * 10;
        if (uuid.equals(requester)) score += 2;
        else if (isOwner(uuid)) score += 1;
        return score;
    }

    /** 能力档位：装了模型（{@code model}）排最前，内置信号处理（{@code dsp}）次之。 */
    private int engineRank(UUID uuid) {
        PresenceRegistry pr = presence;
        if (pr == null) return 0;
        ClientProfile p = pr.get(uuid);
        if (p == null) return 0;
        if ("model".equals(p.automixEngine)) return 2;
        return p.automixCapable ? 1 : 0;
    }

    private boolean online(UUID uuid) {
        Member m = members.get(uuid);
        return m != null && m.online;
    }

    /** 指派到期的处理：改派下一台，人选用尽就放任「没有计划」。 */
    private void tickAnalyst(long now) {
        if (analyst == null) {
            // 还没指派过（例如刚换的歌、或上次全房都没人能算）：每次 tick 都试着找一台。
            assignAnalyzer(false);
            return;
        }
        if (analystEntryId != (queue.isEmpty() ? 0L : queue.get(0).entryId)) {
            // 队首换了（有人插歌 / 跳歌）：这一份指派作废，重新选人。
            clearAnalyst();
            assignAnalyzer(false);
            return;
        }
        if (current != null && current.automixPlan != null) { clearAnalyst(); return; }
        // 被指派人掉线：等不到它的计划了，当场改派。
        // 这条与超时无关，必须一直做 —— 超时是原来唯一能兜住「那台机器彻底不响应」的路径，
        // 一旦把它关掉（automixAssignTimeoutMs = 0），不单独判掉线就会让一台离线机器
        // 把整首歌卡在「等计划」的状态里。
        // 不拉黑：断线重连是常事，它回来之后照样可以被指派（pickAnalyst 本来就会跳过离线者）。
        if (!online(analyst)) {
            System.out.println("[mpsync] 被指派人已离线（房间 " + id + " entry=" + analystEntryId + "），改派下一台");
            assignAnalyzer(true);
            return;
        }
        // 超时已取消（0）：不再有「算得慢就被抢走」这条路。
        if (cfg.automixAssignTimeoutMs > 0L && analystAssignedAt > 0L
                && now - analystAssignedAt >= cfg.automixAssignTimeoutMs) {
            System.out.println("[mpsync] 指派分析超时（房间 " + id + " entry=" + analystEntryId + "），改派下一台");
            refusedAnalysts.add(analyst);
            assignAnalyzer(true);
        }
    }

    private void clearAnalyst() {
        if (analyst == null && analystEntryId == 0L) return;
        analyst = null;
        analystEntryId = 0L;
        analystAssignedAt = 0L;
        broadcastAnalyzeState();
    }

    /**
     * 外面（成员进房、档案更新）让房间重新考虑一次「这一首该由谁算」。
     *
     * <p>之所以需要显式入口：换歌那一刻房间里可能还没有任何一台报上能力位（客户端刚连上、
     * profile 还在路上），于是会被判成「没人能算」。等档案真到了必须能补救 —— 否则整个房间里
     * 明明有装了模型的机器，却每一首都不交叠。</p>
     */
    public void refreshAnalyst() {
        if (current == null) return;
        if (current.automixPlan != null) return;   // 已经算好了，不用再找人
        if (analyst != null) return;               // 已经在算，别打断
        assignAnalyzer(false);
    }

    /** 告诉全房「这一首由谁在算」。纯信息帧，界面用；解不出来时 analyst 为空串。 */
    private void broadcastAnalyzeState() {
        JsonObject m = Proto.msg(Proto.S_ANALYZE_STATE);
        m.addProperty("entryId", queue.isEmpty() ? 0L : queue.get(0).entryId);
        m.addProperty("analyst", analyst == null ? "" : analyst.toString());
        Member who = analyst == null ? null : members.get(analyst);
        m.addProperty("analystName", who == null ? "" : who.name);
        PresenceRegistry pr = presence;
        ClientProfile p = (analyst == null || pr == null) ? null : pr.get(analyst);
        m.addProperty("engine", p == null ? "" : (p.automixEngine == null ? "" : p.automixEngine));
        broadcast(m);
    }

    public long positionMs() {
        long pos = playing ? System.currentTimeMillis() - startEpoch : pausedPos;
        if (pos < 0) pos = 0;
        long dur = current == null ? 0 : current.track.durationMs;
        if (dur > 0 && pos > dur) pos = dur;
        return pos;
    }

    /** Owner/admin free switch: play this track right now, ahead of the queue. */
    public void playNow(UUID actor, String actorName, Track track) {
        // 和 enqueue 同一道闸：0 时长的歌定不出交接点，startEntry 后房间会永远停在它上面。
        if (track.durationMs <= 0L) {
            throw new IllegalStateException("这首歌缺少时长信息，无法播放");
        }
        QueueEntry entry = new QueueEntry(track, actor, actorName);
        queue.removeIf(e -> e.track.key().equals(track.key()));
        startEntry(entry, 0L);
    }

    /** Jump to an already-queued entry, dropping the ones it skipped past into history. */
    public boolean playEntry(long entryId) {
        int idx = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).entryId == entryId) { idx = i; break; }
        if (idx < 0) return false;
        QueueEntry entry = queue.remove(idx);
        startEntry(entry, 0L);
        return true;
    }

    public void next(boolean auto) {
        if (auto && repeat == RepeatMode.ONE && current != null) {
            startEntry(current, 0L);
            return;
        }
        if (!queue.isEmpty()) {
            // 入口点必须先算：plannedCue 要用「即将播的那一首」的曲长做夹取，
            // 而 queue.remove(0) 一执行，队首就换成再下一首了。
            long cue = plannedCue();
            // 交叠窗只有服务端自己到点提前换歌那一路才算：手动「下一首」、跳过坏曲目都不该
            // 按上一首的计划把心跳多压一段，否则一次点跳过能让房间二十秒不校准。
            // 同理，这一句必须排在 queue.remove(0) 之前 —— 计划挂在<b>离开</b>的那一首上。
            long blend = auto ? outgoingBlendMillis() : 0L;
            startEntry(queue.remove(0), cue, blend);
            return;
        }
        if (repeat == RepeatMode.ALL) {
            // Refill from history (oldest first) so the room loops the same set.
            QueueEntry replay = current;
            if (!history.isEmpty()) {
                queue.addAll(history);
                history.clear();
                if (replay != null) queue.add(replay);
                startEntry(queue.remove(0), 0L);
                return;
            }
            if (replay != null) { startEntry(replay, 0L); return; }
        }
        stop();
    }

    public void prev() {
        if (history.isEmpty()) {
            seek(0L);
            return;
        }
        QueueEntry back = history.remove(history.size() - 1);
        if (current != null) queue.add(0, current);
        startEntryInternal(back, 0L, false);
        broadcastQueue();
    }

    private void startEntry(QueueEntry entry, long startPos) {
        startEntryInternal(entry, startPos, true, 0L);
    }

    /**
     * @param blendMillis 这一次换歌打算叠多久（见 {@link #outgoingBlendMillis()}）；
     *                    0 = 没有交叠，静默窗退回配置的长度
     */
    private void startEntry(QueueEntry entry, long startPos, long blendMillis) {
        startEntryInternal(entry, startPos, true, blendMillis);
    }

    private void startEntryInternal(QueueEntry entry, long startPos, boolean archiveCurrent) {
        startEntryInternal(entry, startPos, archiveCurrent, 0L);
    }

    private void startEntryInternal(QueueEntry entry, long startPos, boolean archiveCurrent,
                                    long blendMillis) {
        if (archiveCurrent && current != null && current != entry) {
            history.add(current);
            while (history.size() > HISTORY_MAX) history.remove(0);
        }
        current = entry;
        pausedPos = Math.max(0L, startPos);
        startEpoch = System.currentTimeMillis() - pausedPos;
        playing = true;
        seq++;
        waitingUrl = false;
        // 换了曲子，上一首的指派与「谁拒绝过」全部作废 —— 新的一首要重新选人。
        analyst = null;
        analystEntryId = 0L;
        analystAssignedAt = 0L;
        analystAttempt = 0;
        planTargetEntryId = 0L;   // 新 current 的 automixPlan 天然为 null，计划瞄准也要归零
        refusedAnalysts.clear();
        // 切歌投票是冲着「这一首」来的：歌一换，进行中的 SKIP 投票就没了对象，留着会在
        // finishVote → next 时把新的这一首误切掉（投票卡显示的还是上一首的名字）。KICK 投票
        // 与放哪首歌无关，保留。移除时顺手给客户端补一帧「这票关了」—— 只删不广播的话，投票卡
        // 会残留在界面上点了没反应，直到下一次全量快照（有人进房 / C_SYNC）才消失。
        for (Iterator<VoteSession> it = votes.iterator(); it.hasNext(); ) {
            VoteSession v = it.next();
            if (v.kind != VoteSession.Kind.SKIP) continue;
            v.close(voteEligible(v));
            broadcastVote(v);
            it.remove();
        }
        // 换歌是最容易引发集体误判的时刻：全房都在开流，位置必然是散的。开着交叠时还要再多让
        // 一段 —— 混音期间本机与房间时钟本来就「故意」差着几秒，那几发心跳只会把淡入薅断。
        markCommand(blendMillis > 0L ? cfg.automixLeadMs + blendMillis + 1000L : 0L);

        if (!entry.track.hasFreshUrl()) {
            // No usable URL: hold the clock and ask a client to re-resolve. Pushing a
            // dead link would leave every member silently stuck instead.
            playing = false;
            waitingUrl = true;
            waitingSince = System.currentTimeMillis();
            requestUrl(entry);
            broadcastState();
            return;
        }
        broadcastPlay();
        broadcastQueue();
        // 立刻指派分析者：整首歌都是它的预算，越早开始越从容（尤其是要借用别人电脑的那种）。
        assignAnalyzer(false);
        // 换歌意味着前瞻窗往前滑了一格：新队首之后的那几首该开始备货了。长歌单放着不动时靠
        // tick 里那个低频兜底，但换歌这一下必须当场走一遍，不然整批要等下一个兜底周期才开始问。
        resolveLookahead();
    }

    private void requestUrl(QueueEntry entry) {
        requestUrl(entry, false);
    }

    /**
     * 让客户端（重新）解析一条取流链接。
     *
     * <p>这是「非会员点歌，房间里的会员代为解析」的实现：服务端自己没有任何平台会话，所有链接
     * 都来自成员。收到请求的客户端只有在能解析出<b>完整</b>音频时才应答 —— 只登录不是会员的人
     * 解析到试听片段会主动沉默，所以最终被采纳的一定出自答得上完整版的人（本家会员、本来
     * 不卖会员的平台，或靠 LX 自定义源的贡献者）。</p>
     *
     * @param quiet 播放未中断时的预解析／续签走 true —— 不发聊天提示，因为并没有卡住
     */
    private void requestUrl(QueueEntry entry, boolean quiet) {
        // 这一格手里已经有可用的直链就什么都别做。两个来源会走到这里：链接是点歌人自己
        // 带上来的，以及**换平台的替换帧带着一条验证过的** —— 后者要是还往下走，全房就会
        // 对着一首立刻能放的歌重新问六层，而没人知道该答什么（链接已经有了）。
        // 抽在服主 Token 上的那一发也省掉：它同样只在「没链」时才该花。
        if (entry.track.hasFreshUrl()) {
            if (entry == current && waitingUrl) resumeWithFreshUrl();
            return;
        }
        String source = entry.track.source;

        // ① 服主配置了这个平台的 Token 时，先让服务端自己去解析。这条路对玩家零成本：
        //    不需要房间里恰好有个会员在线，也不需要谁把自己的账号暴露给别人。
        //    它和「问人」并行，不占任何一层的窗口。
        ServerResolve hook = serverResolve;
        if (hook != null) {
            try {
                hook.request(source, entry.track.songId, entry.track.name);
            } catch (Throwable t) {
                Net.warn("[mpsync] 服务端代解析调用失败", t);
            }
        }

        // ② 已经在等某一层了就别重开：前瞻窗每换一次歌、每动一次队列都会跑一遍，重开会把
        //    刚发出去的那一层抹掉重来，等于一首歌反复问同一批人。
        if (entry.askTier != QueueEntry.ASK_IDLE) return;
        // 走到这说明这条链已经不新鲜了（requestUrl 的入口判过 hasFreshUrl）：点歌人层记账
        // 到此作废——之后的分层答案按真实层号记账，重新变得可比。
        entry.adoptedTier = QueueEntry.ADOPTED_NONE;
        advanceAsk(entry, ASK_MEMBER, quiet);
        if (!quiet && entry.askTier != QueueEntry.ASK_IDLE)
            notice("正在获取《" + entry.track.name + "》的播放地址…");
    }

    /**
     * 房间挂在一首<b>已经有可用直链</b>的曲目上等链接 —— 把时钟接回去并广播起播。
     *
     * <p>起播口径与「采纳到一条应答」那一处完全一致：重开时钟基准、序号 +1、让出命令静默窗，
     * 然后 play + queue 各广播一次。少发一发 play，客户端那边就一直停在「等地址」，
     * 明明能放也不出声。</p>
     */
    private void resumeWithFreshUrl() {
        waitingUrl = false;
        playing = true;
        startEpoch = System.currentTimeMillis() - pausedPos;
        seq++;
        markCommand();
        broadcastPlay();
        broadcastQueue();
    }

    /**
     * 从 {@code from} 层起往下找第一个<b>还有点名人可问</b>的层，把 {@code url_needed} 只发给
     * 那一层新增的人，并起这一层的窗口表。一层人都没有就继续往下，全层走完则求一次跨平台救援。
     */
    private void advanceAsk(QueueEntry entry, int from, boolean quiet) {
        if (entry.track.hasFreshUrl()) { entry.closeAsk(); return; }   // 同上：有了就别问了
        String source = entry.track.source;
        // 曲目压根没有链接 = 点歌人刚刚已经试过并且失败了（非会员只解到试听片段时客户端会沉默）。
        // 这种情况下把他排到「装了该平台」之后，省掉一次注定无用的往返。
        boolean requesterFailed = !entry.track.hasFreshUrl();
        for (int tier = from; tier < ASK_NONE; tier++) {
            List<UUID> targets = tierTargets(entry, source, tier, requesterFailed);
            if (targets.isEmpty()) continue;
            for (UUID uuid : targets) entry.markAsked(uuid);
            entry.openTier(tier, targets);
            JsonObject base = Proto.msg(Proto.S_URL_NEEDED);
            // source：客户端据此决定用哪个平台的会话去解，也用来确认这条应答是给这首歌的。
            base.addProperty("source", source);
            base.addProperty("songId", entry.track.songId);
            base.addProperty("name", entry.track.name);
            // 新四个字段。老客户端一个都不读，行为与改动前一致（只是服务端等满窗口才推进）。
            base.addProperty("entryId", entry.entryId);
            base.addProperty("tier", tier);
            long deadline = System.currentTimeMillis() + tierWindowMs(tier);
            base.addProperty("deadlineMs", deadline);
            for (UUID uuid : targets) {
                JsonObject m = base.deepCopy();
                // 「这一格是不是你点的」。客户端只有知道这一点，才敢在答不上时顺手换平台：
                // {@code track_replace} 的判权是点歌人或管理员，别人换过去只会吃一句拒绝，
                // 而那次跨平台搜索烧的是他自己的额度 —— 所以这一位必须由服务端说清楚，
                // 不能让客户端拿昵称去猜（房间里两个同名的点是恒有的事）。
                m.addProperty("mine", entry.requester != null && entry.requester.equals(uuid));
                send(uuid, m);
            }
            return;
        }
        entry.closeAsk();
        dispatchRescue(entry);
    }

    /** 这一层该问谁：在线 + 档案判据 + 没在前面的层里答过或弃权过。 */
    private List<UUID> tierTargets(QueueEntry entry, String source, int tier, boolean requesterFailed) {
        List<UUID> out = new ArrayList<>();
        PresenceRegistry pr = presence;
        boolean contributors = pr != null && cfg.preferContributors;
        switch (tier) {
            case ASK_MEMBER -> {
                if (!contributors) return out;
                for (Member m : members.values())
                    if (m.online && pr.isMember(m.uuid, source) && pr.contributesVip(m.uuid, source)
                            && !entry.spoken(m.uuid)) out.add(m.uuid);
            }
            case ASK_CONTRIBUTED_LX -> {
                if (!contributors) return out;
                List<Member> cands = new ArrayList<>();
                for (Member m : members.values())
                    if (m.online && pr.contributesLx(m.uuid, source) && !entry.spoken(m.uuid))
                        cands.add(m);
                // 一个脚本常常同时吃下五六个渠道，两个人各挂一个脚本时先问谁不该由进房先后决定：
                // 覆盖渠道多的那台更可能答上，也更可能答上后面的每一首。List.sort 是稳定的，
                // 渠道数相同的两个人仍然按加入顺序，不引入新的不确定。
                cands.sort((a, b) -> Integer.compare(pr.lxChannelCount(b.uuid), pr.lxChannelCount(a.uuid)));
                for (Member m : cands) out.add(m.uuid);
            }
            case ASK_RESOLVABLE -> {
                if (!contributors) return out;
                // 「贡献 VIP」但不在第 1 层的那批：本来不卖会员的平台、以及登录了但档位查不到
                // 的平台（QQ 那类）。它们给得出完整版，只是不像真会员那样稳。
                for (Member m : members.values())
                    if (m.online && pr.contributesVip(m.uuid, source) && !pr.isMember(m.uuid, source)
                            && !entry.spoken(m.uuid)) out.add(m.uuid);
            }
            case ASK_REQUESTER -> {
                if (requesterFailed || entry.requester == null) return out;
                Member m = members.get(entry.requester);
                if (m != null && m.online && !entry.spoken(m.uuid)) out.add(m.uuid);
            }
            case ASK_INSTALLED -> {
                for (Member m : members.values())
                    if (m.online && (pr == null || pr.canResolve(m.uuid, source))
                            && !entry.spoken(m.uuid)) out.add(m.uuid);
            }
            default -> {
                for (Member m : members.values())
                    if (m.online && !entry.spoken(m.uuid)) out.add(m.uuid);
            }
        }
        return out;
    }

    /**
     * 这一层等多久。
     *
     * <p>最后一层<b>也</b>只等一个层窗口，而不是把总闸 {@link MpConfig#urlWaitTimeoutMs} 剩下的
     * 时间全吃掉 —— 曾经就是这么写的，于是「全层落空 → 向点歌人求跨平台救援」那一步永远走不到：
     * 总闸到点时 tick 先判等链超时、直接跳过这首歌并 return，分层再也没机会推进到救援那一手。
     * 现在六层各等一个层窗（默认 3 秒）走完，剩下的预算与另外批给救援的那一窗（见
     * {@link #askRescue}）合起来才够救援跑一条流水线。</p>
     */
    private long tierWindowMs(int tier) {
        return cfg.urlTierTimeoutMs;
    }

    /**
     * 全层落空：找<b>一台有跨平台匹配流水线的机器</b>替这一首换到别的平台的同一首歌。
     *
     * <p>派单而不是只问点歌人，是因为最可能换成功的那台常常不是他：MP 玩家点的歌，MP 自己
     * 既没有 LX 脚本也没有跨平台搜索，能救它的只有同房那台装了脚本的 Mu。反过来 Mu 玩家点歌时
     * 本机已经先跑过一遍（见客户端的点歌路径），落到这里说明本机换不到，再问别人一次正好接上。</p>
     *
     * <p>只派给自报 {@code switchRescue} 的成员：没有这条流水线的机器收到派单只能沉默，而服务端
     * 为等它要把总闸再放宽一个救援窗 —— 白等十几秒换不来任何一次可播的机会，比改造前更坏。</p>
     *
     * <p>一次一台、一台一窗：执行者可能正好断线、可能本机换源开关关着、也可能真的搜不到。
     * 它当场说换不到（{@code switch_decline}）或者窗口走完，就换下一台，最多
     * {@code switchRescueAttempts} 台；全用完就放弃这一首，让总闸到点跳过。</p>
     */
    private void dispatchRescue(QueueEntry entry) {
        int tried = entry.rescueAttempts();
        UUID next = pickRescueExecutor(entry);
        if (next == null) {
            entry.closeRescue();
            if (tried == 0) return;   // 一台都没派出去过，别播多余的提示
            notice("问过了 " + tried + " 台机器，都没能替《" + entry.track.name
                    + "》找到别的平台的可播版本");
            return;
        }
        long window = Math.max(250L, cfg.switchRescueWindowMs);
        entry.openRescue(next);
        // 救援要的时间得另外给：分层把总闸的预算花光之后才轮到它，不另给的话总闸会在这一句
        // 刚派出去时就跳歌，那一手永远来不及答。卡住的上限就是「派出去的台数 × 一个窗口」，
        // 而台数有配置封顶，所以这一句不会把一首歌拖成无限等。
        if (waitingUrl) waitingSince += window;
        JsonObject msg = Proto.msg(Proto.S_SWITCH_RESCUE);
        msg.addProperty("entryId", entry.entryId);
        msg.addProperty("source", entry.track.source);
        msg.addProperty("songId", entry.track.songId);
        msg.addProperty("name", entry.track.name);
        msg.addProperty("artist", entry.track.artist);
        msg.addProperty("duration", entry.track.durationMs);
        msg.addProperty("attempt", entry.rescueAttempts());
        // 收件人不必是点歌人，所以这一位要单独给：客户端拿它决定提示文案（「我点的歌」与
        // 「替别人换一首」是两件事，烧的是自己的额度时更该说清）。
        msg.addProperty("mine", entry.requester != null && entry.requester.equals(next));
        msg.addProperty("deadlineMs", System.currentTimeMillis() + window);
        send(next, msg);
        Member m = members.get(next);
        notice("房间里没人能放《" + entry.track.name + "》，正在问"
                + (m == null ? "别的成员" : m.name) + "换别的平台版本…");
    }

    /** 下一个该派谁：在线 + 有流水线 + 还没派过，按命中率择优。都没有返回 null。 */
    private UUID pickRescueExecutor(QueueEntry entry) {
        PresenceRegistry pr = presence;
        if (pr == null) return null;
        // 台数上限是「一首歌最多卡多久」的那道闸：卡住的时间 = 派出的台数 × 一个窗口，
        // 而窗口和台数都来自配置。没有这一句的话，房间里装了五台 Mu 就会问满五轮，
        // 配置项 switchRescueAttempts 形同不存在。
        if (entry.rescueAttempts() >= Math.max(1, cfg.switchRescueAttempts)) return null;
        java.util.Set<UUID> tried = entry.rescueTried();
        List<Member> cands = new ArrayList<>();
        for (Member m : members.values()) {
            if (!m.online || !pr.canSwitchRescue(m.uuid) || tried.contains(m.uuid)) continue;
            cands.add(m);
        }
        if (cands.isEmpty()) return null;
        UUID requester = entry.requester;
        // List.sort 是稳定的：分数全等的成员仍然按加入顺序，不引入新的不确定。
        cands.sort((a, b) -> Integer.compare(rescueScore(b, requester, pr), rescueScore(a, requester, pr)));
        return cands.get(0).uuid;
    }

    /**
     * 派单权重：<b>点歌人自己的机器先上</b>（他最清楚自己要听哪一首，也最不烧别人额度），
     * 其次比「本机能出完整版的平台数」—— 跨平台匹配要挨个平台搜索，够得着的平台越多越可能命中，
     * 再比 LX 脚本覆盖的渠道数。数量级差得开，就不必再往下堆判据。
     */
    private static int rescueScore(Member m, UUID requester, PresenceRegistry pr) {
        int mine = m.uuid.equals(requester) ? 1 << 20 : 0;
        return mine + pr.resolvableCount(m.uuid) * 16 + pr.lxChannelCount(m.uuid);
    }

    /** 执行者当场说换不到：立刻改派下一台，别干等一个窗口。 */
    public void switchDecline(UUID uuid, long entryId, String reason) {
        if (entryId <= 0L) return;
        QueueEntry entry = entryOf(entryId, "");
        if (entry == null || !uuid.equals(entry.rescueTarget)) return;   // 不是当前那台：过期帧丢掉
        Member m = members.get(uuid);
        String why = reason == null || reason.isBlank() ? "未说明" : reason.trim();
        System.out.println("[mpsync] 换源弃权 entry=" + entryId + " by="
                + (m == null ? "?" : m.name) + " 原因=" + why);
        dispatchRescue(entry);
    }

    /** 派出去的救援超时：换下一台。 */
    private void tickRescues(long now) {
        if (current != null) tickOneRescue(current, now);
        for (QueueEntry e : queue) tickOneRescue(e, now);
    }

    private void tickOneRescue(QueueEntry entry, long now) {
        UUID target = entry.rescueTarget;
        if (target == null) return;
        if (entry.track.hasFreshUrl()) { entry.closeRescue(); return; }
        if (now - entry.rescueSince <= Math.max(250L, cfg.switchRescueWindowMs)) return;
        Member m = members.get(target);
        notice("没等到" + (m == null ? "那台机器" : m.name) + "的换源结果，再问下一台…");
        dispatchRescue(entry);
    }

    /** 收一条弃权。层号对不上就当过期帧丢弃 —— 迟到的弃权会把还没问到的层提前推完。 */
    public void declineUrl(UUID uuid, long entryId, int tier, String source, String songId,
                           String reason) {
        QueueEntry entry = askTarget(entryId, source, songId);
        if (entry == null || entry.askTier != tier) return;
        // reason 必须落日志：排障时「全员 VIP 的房间为什么全层落空」只能靠这里答 ——
        // 每一层的弃权原因（netease-trial / netease-failed / lx-wy-failed / no-platform）
        // 是判定「误判弃权」还是「真没人解得出」的唯一证据，聊天栏的 notice 不带它。
        Member m = members.get(uuid);
        System.out.println("[mpsync] url_decline entry=" + entryId + " tier=" + tier + " by="
                + (m == null ? "?" : m.name) + " 原因=" + (reason == null || reason.isBlank()
                ? "未说明" : reason.trim()));
        if (!entry.decline(uuid)) return;
        advanceAsk(entry, tier + 1, true);
    }

    /** 按 entryId 找那一格；老客户端不带 entryId 时退回按 source+songId 认。 */
    private QueueEntry askTarget(long entryId, String source, String songId) {
        String key = (source == null || source.isBlank() ? Proto.SOURCE_NETEASE : source)
                + ":" + (songId == null ? "" : songId);
        if (current != null && (entryId == current.entryId || current.track.key().equals(key)))
            return current;
        for (QueueEntry e : queue) {
            if (entryId != 0L ? e.entryId == entryId : e.track.key().equals(key)) return e;
        }
        return null;
    }

    /**
     * 点歌人换到了别的平台的同一首歌：就地替掉那一格。
     *
     * <p>位置、档位、点歌人、{@code entryId} 全部保持不变 —— 别人排在他后面的歌不该因为这一格
     * 换了平台就被挪走，而客户端界面正盯着这一行看。换完立刻按新的平台重新走分层。</p>
     */
    public String replaceTrack(UUID uuid, long entryId, JsonObject track) {
        QueueEntry entry = entryId == 0L ? null : entryOf(entryId, "");
        if (entry == null) return "这首歌已经不在队列里了";
        // 点歌人、管理员，以及**服务端此刻派出去换源的那一台**，三种身份才允许替换：
        // 被派者的答卷就是要改掉自己那一份的曲目，判权必须跟着派单走，否则「让别人替 MP 换平台」
        // 这一手在协议上根本走不通。
        if (!uuid.equals(entry.rescueTarget)
                && (entry.requester == null || !entry.requester.equals(uuid)) && !controls(uuid))
            return "只有点歌人、管理员，或被派去换源的那台机器能替换这首歌";
        Track next = Track.fromJson(track, cfg);
        if (next == null) return "换源结果缺少曲目 ID";
        if (next.key().equals(entry.track.key())) return null;   // 同一平台同一首，什么都没换
        entry.track = next;
        // 替换帧自带直链时按点歌人层记账（ADOPTED_REQUESTER），不带链则清记账等分层问。
        // 不能停留在 ADOPTED_NONE：那个哨兵与任何层号比较都为真，等于邀请任何成员的
        // 迟到/伪造 url_supply 把这一格刚换上的链接静默顶掉。
        entry.adoptedTier = next.hasFreshUrl()
                ? QueueEntry.ADOPTED_REQUESTER : QueueEntry.ADOPTED_NONE;
        entry.closeAsk();
        // 换完平台，挂在这一格上的派单就作废了：旧那台的答卷迟到时对不上新的身份，
        // 而它也已经不在 rescueTarget 上，switchDecline / track_replace 都会被丢掉。
        entry.closeRescue();
        entry.clearDeclined();
        if (current == entry) {
            if (next.hasFreshUrl()) {
                // 换到的那一手带着可用直链，这就是全房在等的那条链 —— 直接续上时钟起播。
                notice("《" + entry.track.name + "》换到 " + next.source + " 版本，继续播放…");
                resumeWithFreshUrl();
            } else {
                notice("《" + entry.track.name + "》换到 " + next.source + " 版本，重新获取播放地址…");
                playing = false;
                waitingUrl = true;
                waitingSince = System.currentTimeMillis();
                requestUrl(entry);
                broadcastState();
            }
        } else {
            notice("《" + entry.track.name + "》换到 " + next.source + " 版本，按新平台重新获取播放地址…");
            requestUrl(entry, true);
            broadcastQueue();
        }
        return null;
    }


    /**
     * 按<b>前瞻窗</b>向房间要链接：只看接下来 {@link MpConfig#queueResolveLead} 个位置，
     * 其中没有可用直链的那些才发一次 {@link Proto#S_URL_NEEDED}。
     *
     * <p><b>为什么不是一灌完就把整张单问一遍。</b>一次 {@code requestUrl} 会按「谁最可能答得上」
     * 逐个收件人<b>单播</b>一圈，收到的人在本机真跑一次取流。一张 100 首的单子一次性问完，就是把
     * 点歌人（或者房间里那几个会员）的上行和平台接口按着一整张单猛抽一遍 —— 而其中大部分曲子要等
     * 几十分钟才轮到，那时链接早过期了，等于白抽。取 2 首的含义是「至少提前一首把音频备好，
     * 又不拿别人的账号去赌命中率」；一首歌有 3～5 分钟，两首的提前量足够把下载、解码都跑完。</p>
     *
     * <p><b>窗是相对于队首的，所以它会自己往前滑。</b>每次换歌（{@link #startEntryInternal}）和
     * 每次队列变动都重新算一遍前 K 个位置，不需要额外记「解到第几首了」。tick 里再挂一个低频兜底：
     * 一张长单子放着不动时，链接到期（{@link Track#hasFreshUrl()} 判的是还剩 15 秒以上）也能被重新问到。</p>
     */
    private void resolveLookahead() {
        int lead = Math.max(1, cfg.queueResolveLead);
        long now = System.currentTimeMillis();
        for (int i = 0; i < queue.size() && i < lead; i++) {
            QueueEntry e = queue.get(i);
            if (e.track.hasFreshUrl()) continue;
            String key = e.track.key();
            Long at = resolveAskedAt.get(key);
            if (at != null && now - at < RESOLVE_ASK_COOLDOWN_MS) continue;
            resolveAskedAt.put(key, now);
            // quiet=true：这是在提前备货，播放没有中断，不该往聊天栏刷一句「正在获取播放地址…」。
            requestUrl(e, true);
        }
        // 这张表只记「问过的歌」，条目离队后就是垃圾。队不大时每次全量清一遍成本可接受，
        // 队大到超过 64 条才清（否则每次前瞻都要把整队 key 拼一遍）。
        if (resolveAskedAt.size() > 64) {
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (QueueEntry e : queue) alive.add(e.track.key());
            resolveAskedAt.keySet().retainAll(alive);
        }
    }

    /**
     * A client answered {@link Proto#S_URL_NEEDED} (or refreshed a queued track).
     *
     * <p>{@code servedDurationMs} lets a trial clip be rejected: the room hands one link to
     * everyone, so accepting a non-VIP account's 30s preview would cut the song short for
     * all members. A rejected answer changes nothing and the room keeps waiting, giving a
     * member who can resolve the full track a chance to answer.
     */
    public void supplyUrl(String source, String songId, String url, long expiresAt,
                          long servedDurationMs) {
        // 服务端自解（服主 Token）那一手没有「谁答的」，按最可信的层记账。
        supplyUrl(null, 0L, source, songId, url, expiresAt, servedDurationMs, false);
    }

    /**
     * 收一条代解析回来的直链。
     *
     * <p>判据从「先答先赢」改成「<b>按可信度</b>」：每条答案都记着它出自第几层，只有比现在这条
     * 更可信（层号更小）的答案才允许替换。于是迟到的真会员链接能顶掉先到的脚本链接，反过来不行；
     * 同层内先答先赢 —— 两条同层的链接互相覆盖会打乒乓，而它们本来就该是同一档质量。</p>
     *
     * <p>顺带修掉一处既有的自伤：老写法对<b>正在播</b>那一格无条件调 {@code adoptUrl}，而它在
     * 判据不过时会把 {@code url/urlExpiresAt/urlDuration} 三个字段一起清零 —— 一条迟到的不合格
     * 答案会把好好在播的链接抹掉，晚进房的人就拿不到地址了。现在不合格的答案根本走不到那一步。</p>
     *
     * @param from        谁答的；{@code null} = 服务端自解
     * @param entryId     答的是哪一格；老客户端不发，退回按 {@code source+songId} 认
     * @param estimated   {@code servedDurationMs} 是算出来的还是测出来的（见 {@link
     *                    Track#adoptUrl(String, long, long, MpConfig, boolean)}）
     */
    public void supplyUrl(UUID from, long entryId, String source, String songId, String url,
                          long expiresAt, long servedDurationMs, boolean estimated) {
        if (songId == null) return;
        // 老客户端的 url_supply 不带 source，按网易云处理。
        String key = (source == null || source.isBlank() ? Proto.SOURCE_NETEASE : source) + ":" + songId;
        QueueEntry hit = entryOf(entryId, key);
        if (hit == null) return;
        int tier = from == null ? ASK_SERVER_TOKEN
                : hit.askTier == QueueEntry.ASK_IDLE ? ASK_EVERYONE : hit.askTier;
        if (!hit.canAdoptTier(tier)) return;

        if (hit == current) {
            boolean wasStalled = waitingUrl;
            if (!hit.track.adoptUrl(url, expiresAt, servedDurationMs, cfg, estimated)) return;
            hit.adoptedTier = tier;
            hit.closeAsk();
            hit.closeRescue();      // 链接到手，别再等那台正在搜索的机器
            if (hit.track.hasFreshUrl() && wasStalled) {
                // The room was holding for this URL: start the clock and push play.
                waitingUrl = false;
                playing = true;
                startEpoch = System.currentTimeMillis() - pausedPos;
                seq++;
                // 等到链接之后的起播等同于换歌，同样要让出静默窗。
                // （下面「播放中静默续签」那条路不打标记：它不动时钟，也不会让客户端重开流。）
                markCommand();
                broadcastPlay();
                broadcastQueue();
                return;
            }
            // Pre-emptive refresh while playing: store it silently so late joiners get a
            // live link. Re-broadcasting play here would restart everyone mid-song.
            broadcastQueue();
            return;
        }
        if (hit.track.hasFreshUrl()) return;
        if (!hit.track.adoptUrl(url, expiresAt, servedDurationMs, cfg, estimated)) return;
        hit.adoptedTier = tier;
        hit.closeAsk();
        hit.closeRescue();          // 链接到手，别再等那台正在搜索的机器
        broadcastQueue();
    }

    /** 按 entryId 找那一格；不带 entryId 的老帧按 {@code key} 认（当前曲目优先）。 */
    private QueueEntry entryOf(long entryId, String key) {
        if (entryId != 0L) {
            if (current != null && current.entryId == entryId) return current;
            for (QueueEntry e : queue) if (e.entryId == entryId) return e;
            return null;
        }
        if (current != null && current.track.key().equals(key)) return current;
        for (QueueEntry e : queue) if (e.track.key().equals(key)) return e;
        return null;
    }

    public void pause() {
        if (current == null || !playing) return;
        pausedPos = positionMs();
        playing = false;
        seq++;
        markCommand();
        JsonObject m = Proto.msg(Proto.S_PAUSE);
        stamp(m, pausedPos);
        broadcast(m);
    }

    public void resume() {
        if (current == null || playing) return;
        if (!current.track.hasFreshUrl()) {
            // URL expired while paused — re-resolve before pretending to resume.
            waitingUrl = true;
            waitingSince = System.currentTimeMillis();
            requestUrl(current);
            return;
        }
        startEpoch = System.currentTimeMillis() - pausedPos;
        playing = true;
        seq++;
        markCommand();
        JsonObject m = Proto.msg(Proto.S_RESUME);
        stamp(m, pausedPos);
        broadcast(m);
    }

    public void seek(long pos) {
        if (current == null) return;
        long dur = current.track.durationMs;
        pausedPos = Math.max(0L, dur > 0 ? Math.min(pos, dur) : pos);
        if (playing) startEpoch = System.currentTimeMillis() - pausedPos;
        seq++;
        markCommand();
        JsonObject m = Proto.msg(Proto.S_SEEK);
        stamp(m, pausedPos);
        broadcast(m);
    }

    public void stop() {
        if (current != null) {
            history.add(current);
            while (history.size() > HISTORY_MAX) history.remove(0);
        }
        current = null;
        playing = false;
        pausedPos = 0L;
        waitingUrl = false;
        seq++;
        broadcast(Proto.msg(Proto.S_STOP));
        broadcastQueue();
    }

    // ── roles ────────────────────────────────────────────────────────────────

    /**
     * Grant or revoke admin.
     *
     * @return a user-facing error, or {@code null} on success
     */
    public String setRole(UUID actor, UUID target, Role role) {
        Member t = members.get(target);
        if (t == null) return "对方不在房间里";
        if (!controls(actor)) return "只有房主或管理员可以设置权限";
        if (target.equals(owner)) return "不能修改房主的权限";
        if (role == Role.OWNER) return "请使用转让房主功能";
        if (role == Role.MEMBER && t.role == Role.ADMIN && !isOwner(actor)) {
            return "只有房主可以撤销管理员";
        }
        t.role = role;
        broadcastState();
        notice(t.name + (role == Role.ADMIN ? " 已成为管理员" : " 的管理员权限已被撤销"));
        return null;
    }

    /** Owner-only: hand the room over. */
    public String transfer(UUID actor, UUID target) {
        if (!isOwner(actor)) return "只有房主可以转让房间";
        Member t = members.get(target);
        if (t == null) return "对方不在房间里";
        Member old = members.get(owner);
        if (old != null) old.role = Role.ADMIN;
        owner = target;
        t.role = Role.OWNER;
        broadcastState();
        notice(t.name + " 已成为新房主");
        return null;
    }

    // ── heartbeat ────────────────────────────────────────────────────────────

    public void progress(UUID uuid, long pos, boolean listening) {
        Member m = members.get(uuid);
        if (m == null) return;
        m.lastSeen = System.currentTimeMillis();
        m.reportedPos = pos;
        m.listening = listening;
    }

    public int listenerCount() {
        int n = 0;
        for (Member m : members.values()) if (m.actuallyListening()) n++;
        return n;
    }

    // ── 个人暂停 ─────────────────────────────────────────────────────────────

    /**
     * 个人暂停／恢复：只影响这一个成员，房间时钟一动不动。
     *
     * <p>恢复的那一刻必须由服务端立刻推一帧权威位置过去（{@link #sendResync}），否则他会
     * 从「按下暂停时的位置」接着放 —— 房间已经往前走了几分钟，他一开口就是脱节的。
     * 这也是为什么个人暂停要过服务端，而不是客户端自己静音了算。
     *
     * @return 面向用户的错误，null 表示成功
     */
    public String setSelfPause(UUID uuid, boolean paused) {
        Member m = members.get(uuid);
        if (m == null) return "你不在这个房间里";
        if (m.selfPaused == paused) return null;
        m.selfPaused = paused;
        if (!paused) sendResync(uuid);
        // 收听人数变了，全房都该看到。
        broadcastState();
        return null;
    }

    /**
     * 只给一个人补一帧「现在该放到哪」。
     *
     * <p>用的就是 {@link Proto#S_PLAY}：它带曲目、直链、位置、播放态，客户端收到会重开流并
     * 跳到位置 —— 这正是个人暂停恢复后需要的动作。{@code seq} 不自增，所以对别人毫无影响。
     */
    public void sendResync(UUID uuid) {
        if (current == null) {
            send(uuid, Proto.msg(Proto.S_STOP));
            return;
        }
        JsonObject m = Proto.msg(Proto.S_PLAY);
        m.add("track", current.toJson(true));
        m.addProperty("requesterName", current.requesterName == null ? "" : current.requesterName);
        m.addProperty("playing", playing);
        m.addProperty("resync", true);
        stamp(m, positionMs());
        send(uuid, m);
    }

    // ── 投票（频道） ──────────────────────────────────────────────────────────

    public List<VoteSession> votes() { return votes; }

    /** 分母：在线成员数。离线的人投不了票，把他算进分母等于永远过不了。 */
    public int voteEligible() {
        return voteEligible(null);
    }

    /**
     * 某个投票的分母。
     *
     * <p>踢人投票把<b>被投的人自己</b>排除在外。否则 80% 的门槛在小房间里等于「永远踢不掉」——
     * 5 个人里踢 1 个需要 {@code floor(5×0.8)+1 = 5} 票，也就是连当事人都得投同意。排除他之后，
     * 门槛的含义变成「除他以外的人里超过 80% 同意」，这才是需求那句话可执行的形态。
     */
    public int voteEligible(VoteSession v) {
        int n = 0;
        for (Member m : members.values()) {
            if (!m.online) continue;
            if (v != null && v.kind == VoteSession.Kind.KICK && m.uuid.equals(v.target)) continue;
            n++;
        }
        return Math.max(1, n);
    }

    public VoteSession vote(long voteId) {
        for (VoteSession v : votes) if (v.id == voteId) return v;
        return null;
    }

    /** 针对某人的踢人投票还剩多少冷却（毫秒）；0 表示可以发起。 */
    public long kickCooldownLeft(UUID target) {
        Long until = kickCooldown.get(target);
        if (until == null) return 0L;
        long left = until - System.currentTimeMillis();
        if (left <= 0L) {
            kickCooldown.remove(target);
            return 0L;
        }
        return left;
    }

    /**
     * 发起一次投票。
     *
     * @return 面向用户的错误，null 表示已开始
     */
    public String startVote(VoteSession.Kind kindOf, UUID initiator, UUID target) {
        if (!channel()) return "投票只在服务器频道里使用";
        Member me = members.get(initiator);
        if (me == null) return "你不在频道里";
        int eligible = voteEligible();
        if (eligible < cfg.voteMinMembers) {
            return "频道里人太少（不足 " + cfg.voteMinMembers + " 人），暂不需要投票";
        }
        for (VoteSession v : votes) {
            if (v.kind == kindOf && (kindOf == VoteSession.Kind.SKIP
                    || (target != null && target.equals(v.target)))) {
                return "已经有一个同样的投票在进行中";
            }
        }
        String subject;
        double ratio;
        if (kindOf == VoteSession.Kind.SKIP) {
            if (current == null) return "现在没有在放的歌";
            subject = current.track.display();
            ratio = cfg.voteSkipRatio;
        } else {
            Member t = target == null ? null : members.get(target);
            if (t == null) return "对方不在频道里";
            if (target.equals(initiator)) return "不能对自己发起踢出投票";
            if (serverAdmin(target)) return "不能投票踢出服务器管理员";
            long left = kickCooldownLeft(target);
            if (left > 0L) {
                return "上一次针对 " + t.name + " 的投票没有通过，冷却还剩 "
                        + yun.shan.mpsync.util.Durations.label(left);
            }
            subject = t.name;
            ratio = cfg.voteKickRatio;
        }
        VoteSession v = new VoteSession(kindOf, initiator, me.name, target, subject,
                cfg.voteWindowMs, ratio);
        votes.add(v);
        eligible = voteEligible(v);
        broadcastVote(v);
        // 聊天栏广播：需求要的那句话。装了播放器的成员在 GUI 里会看到一张投票卡（带按钮），
        // 没装的人靠这一行也能参与 —— 命令和按钮走的是同一条 vote_cast。
        Net.chatAll(members.keySet(), "[一起听] " + v.headline() + "，回「/mp agree」表示同意（"
                + (cfg.voteWindowMs / 1000L) + " 秒内，需 " + v.needed(eligible) + "/" + eligible + " 票）");
        // 发起人那一票已经算上了，可能一个人就够（比如两个人的频道里 2 票门槛只需要 2 票时不够，
        // 但 1 人频道被 voteMinMembers 挡掉了），所以立刻检查一次。
        settleIfDecided(v);
        return null;
    }

    /** @return 面向用户的提示；null 表示投票已记录 */
    public String castVote(long voteId, UUID uuid, boolean agree) {
        VoteSession v = voteId > 0 ? vote(voteId) : latestOpenVote();
        if (v == null) return "现在没有进行中的投票";
        if (!members.containsKey(uuid)) return "只有频道里的玩家可以投票";
        // 当事人不参与自己的踢出投票：他不在分母里（否则 80% 永远够不到），
        // 那么他的票也不该进分子，否则「剩下多少人没投」这个账算不平。
        if (v.kind == VoteSession.Kind.KICK && uuid.equals(v.target)) {
            return "你不能给针对自己的投票投票";
        }
        if (!v.cast(uuid, agree)) return "你已经投过票了";
        broadcastVote(v);
        settleIfDecided(v);
        return null;
    }

    /** 最近开始、还没结束的那个投票 —— 聊天栏里回「同意」时不带 id，就投给它。 */
    public VoteSession latestOpenVote() {
        VoteSession best = null;
        for (VoteSession v : votes) {
            if (v.closed()) continue;
            if (best == null || v.startedAt > best.startedAt) best = v;
        }
        return best;
    }

    private void settleIfDecided(VoteSession v) {
        int eligible = voteEligible(v);
        // 两种都要提前结算：票数已经够了，以及剩下的人全投同意也够不到门槛。
        // 后者不判的话，一个注定失败的投票会把频道整整挂住一个窗口。
        if (v.decided(eligible) || v.hopeless(eligible)) finishVote(v);
    }

    /** 结算一个投票并执行结果。 */
    private void finishVote(VoteSession v) {
        int eligible = voteEligible(v);
        boolean passed = v.close(eligible);
        broadcastVote(v);
        votes.remove(v);
        if (v.kind == VoteSession.Kind.SKIP) {
            if (passed) {
                notice("投票通过（" + v.agrees() + "/" + eligible + "），切到下一首");
                next(false);
            } else {
                notice("切歌投票未通过（" + v.agrees() + "/" + eligible + "，需 "
                        + v.needed(eligible) + " 票）");
            }
            return;
        }
        Member t = v.target == null ? null : members.get(v.target);
        String who = t != null ? t.name : v.subject;
        if (passed) {
            notice("投票通过（" + v.agrees() + "/" + eligible + "），" + who + " 被移出频道");
            pendingKick = v.target;       // 真正的移除要回到 RoomManager 做（它管 playerRoom）
        } else {
            // 需求：不满足票数就进冷却，避免乱踢。冷却比投票窗口长得多（默认 1 小时）。
            if (v.target != null) {
                kickCooldown.put(v.target, System.currentTimeMillis() + cfg.voteKickCooldownMs);
            }
            notice("踢出 " + who + " 的投票未通过（" + v.agrees() + "/" + eligible + "，需 "
                    + v.needed(eligible) + " 票），" + yun.shan.mpsync.util.Durations.label(cfg.voteKickCooldownMs)
                    + "内不能再对他发起");
        }
    }

    /**
     * 投票通过要踢的人。
     *
     * <p>为什么不在 {@link #finishVote} 里直接踢：成员表在 Room 里，但「玩家 → 房间」的索引在
     * {@link RoomManager} 里，从 Room 里单方面删人会留下一条指向空房间的索引。所以这里只登记，
     * 由 RoomManager 在 tick 里取走并走它自己的 kick 流程。
     */
    private UUID pendingKick;

    public UUID takePendingKick() {
        UUID out = pendingKick;
        pendingKick = null;
        return out;
    }

    public void broadcastVote(VoteSession v) {
        broadcast(v.frame(voteEligible(v)));
    }

    /** 每个 tick 检查超时的投票。 */
    private void tickVotes(long now) {
        if (votes.isEmpty()) return;
        for (Iterator<VoteSession> it = votes.iterator(); it.hasNext(); ) {
            VoteSession v = it.next();
            if (!v.expired(now)) continue;
            it.remove();
            // finishVote 会再从列表里删一次（已经不在了，remove 返回 false，无害），
            // 这样超时和提前结算走的是同一段结算逻辑。
            finishVote(v);
        }
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    /**
     * 每一层的等待窗到点就往下推一层。
     *
     * <p>收齐弃权时 {@link #declineUrl} 会当场推进，这一遍管的是「没人回话」那种 —— 老客户端
     * 根本不发 {@code url_decline}，只能靠窗口自己走完，所以这一遍不能省。</p>
     */
    private void tickUrlTiers(long now) {
        if (current != null) tickOneTier(current, now);
        for (QueueEntry e : queue) tickOneTier(e, now);
    }

    private void tickOneTier(QueueEntry entry, long now) {
        int tier = entry.askTier;
        if (tier == QueueEntry.ASK_IDLE) return;
        if (entry.track.hasFreshUrl()) { entry.closeAsk(); return; }
        if (now - entry.askTierSince <= tierWindowMs(tier)) return;
        advanceAsk(entry, tier + 1, true);
    }

    public void tick() {
        long now = System.currentTimeMillis();

        // 投票先结算：一个超时的切歌投票可能正好要换歌，让它排在时钟判断之前，
        // 免得同一 tick 里「自动进下一首」和「投票切歌」各切一次。
        tickVotes(now);

        if (waitingUrl && now - waitingSince > cfg.urlWaitTimeoutMs) {
            waitingUrl = false;
            String title = current != null ? current.track.name : "";
            // 走到这里说明房间里没有一个成员能解析出这首歌的完整音频（会员曲而在场都不是会员，
            // 或者这首歌本身已下架）。把原因说明白，否则看起来像是服务端坏了。
            notice("房间里没有成员能解析出《" + title + "》的完整播放地址（可能需要会员），已跳过");
            next(false);
            return;
        }

        // 取流分层的推进：这一层等满了还没人答，就问下一层。
        // 排在总闸（上面那段）之后，所以总闸永远优先 —— 最坏情况是只问了几层就被跳过，
        // 而不是分层把「这首歌到底放不放」这件事拖到无限久。
        tickUrlTiers(now);
        // 派单超时改派排在分层之后：同一 tick 里分层先走完最后一层，救援才有机会接上；
        // 而它两样都不会把已经拿到链接的这一格再动一次（两个 tick 都先判 hasFreshUrl）。
        tickRescues(now);

        // 分析者的指派与超时改派。**必须排在换歌判断之前**：一首曲子临到交接点时下面那一句会
        // 直接 next(true) 返回，改派就永远轮不到 —— 而那恰恰是最需要补救的时刻（真正能算的那台
        // 还没被问到）。它自己会判「已换歌 / 已拿到计划」而空转，放在前面没有副作用。
        tickAnalyst(now);

        if (playing && current != null) {
            long dur = current.track.durationMs;
            // 交接点优先取上报的计划（跟着音频内容走：人声收尾、尾奏起点…），
            // 没有计划时退回「距结束 max(250ms, automixLeadMs) 以内」，也就是改造前的判据。
            if (dur > 0 && positionMs() >= fireAt(dur)) {
                next(true);
                return;
            }
        }

        // Keep the stored URL alive for players who join mid-song: NetEase signs links
        // for minutes, and a joiner handed an expired link would get silence.
        if (current != null && !waitingUrl && playing
                && current.track.urlExpiresAt > 0
                && current.track.urlExpiresAt - now < 60_000L
                && now - lastRefreshAsk > 30_000L) {
            lastRefreshAsk = now;
            requestUrl(current, true);
        }

        // 前瞻窗低频兜底。换歌与队列变动时已经各跑一遍（那覆盖了绝大多数情况），这一遍管的是
        // 「一张长歌单排在后面放着不动」：里面某首的链接到期了、或者当时没人答得上，就得靠它重新问。
        // 十 tick 一次（默认 20 TPS ≈ 半秒）足够从容，而 resolveAskedAt 那层冷却保证不会真的
        // 每半秒把同一首歌再问一遍。
        if (++lookaheadCounter >= 10) {
            lookaheadCounter = 0;
            if (!waitingUrl) resolveLookahead();
        }

        // 心跳只在「房间确实在稳定播放」时发。两个门禁：
        //   waitingUrl —— 正卡在等链接，位置没有意义；
        //   inClockGrace —— 命令帧刚下发，全房都在开流，这时候校准只会引发一轮集体误判 seek。
        if (++tickCounter >= cfg.timeBroadcastTicks) {
            tickCounter = 0;
            if (current != null && !waitingUrl && !inClockGrace(now)) broadcastTime();
        }
    }

    // ── snapshots + sending ──────────────────────────────────────────────────

    /** Full room snapshot. Stream URLs are only included for the track that is playing. */
    public JsonObject snapshot() {
        JsonObject room = new JsonObject();
        room.addProperty("id", id);
        room.addProperty("code", code);
        room.addProperty("name", name);
        room.addProperty("owner", owner == null ? "" : owner.toString());
        room.addProperty("repeat", repeat.id());
        room.addProperty("listeners", listenerCount());
        // v2：房间种类、是否公开、单人在队上限。老客户端读不到，行为不变。
        room.addProperty("kind", kind.id());
        room.addProperty("channel", channel());
        room.addProperty("public", open);
        room.addProperty("perMemberCap", perMemberCap());

        JsonArray mem = new JsonArray();
        for (Member m : members.values()) mem.add(m.toJson(profileOf(m.uuid)));
        room.add("members", mem);

        room.add("queue", queueJson());
        // 中途进房的人靠快照起步；这里不带「下一首」的话，他要等下一次歌单广播才知道。
        JsonObject next = nextJson();
        if (next != null) room.add("next", next);
        room.add("history", historyJson());

        if (!votes.isEmpty()) {
            JsonArray va = new JsonArray();
            for (VoteSession v : votes) va.add(v.toJson(voteEligible(v)));
            room.add("votes", va);
        }

        JsonObject playback = new JsonObject();
        playback.addProperty("state", current == null ? "idle" : (playing ? "playing" : "paused"));
        playback.addProperty("waitingUrl", waitingUrl);
        playback.addProperty("pos", positionMs());
        playback.addProperty("serverTime", System.currentTimeMillis());
        playback.addProperty("seq", seq);
        if (current != null) playback.add("track", current.toJson(true));

        JsonObject msg = Proto.msg(Proto.S_ROOM_STATE);
        msg.add("room", room);
        msg.add("playback", playback);
        return msg;
    }

    /** 这个成员的客户端档案；没注入档案表或没上报过都返回 null。 */
    private ClientProfile profileOf(UUID uuid) {
        PresenceRegistry p = presence;
        return p == null ? null : p.get(uuid);
    }

    /**
     * 公开房间列表里的一条。
     *
     * <p>刻意<b>不</b>带直链，也不带完整歌单 —— 这份数据会发给房间外的人，他们只需要判断
     * 「这个房间在放什么、有多少人、要不要进」。
     */
    public JsonObject publicJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("code", code);
        o.addProperty("name", name);
        o.addProperty("kind", kind.id());
        o.addProperty("channel", channel());
        Member ow = owner == null ? null : members.get(owner);
        o.addProperty("ownerName", ow == null ? (channel() ? "服务器" : "") : ow.name);
        o.addProperty("members", members.size());
        o.addProperty("listeners", listenerCount());
        o.addProperty("queue", queue.size());
        o.addProperty("playing", playing);
        o.addProperty("nowPlaying", current == null ? "" : current.track.display());
        o.addProperty("full", full());
        return o;
    }

    private JsonArray queueJson() {
        JsonArray a = new JsonArray();
        // 交接计划描述的是「从正在播的这一首怎么进入队首那一首」，所以「已经算好了」这个标记
        // 属于队首。带上它，客户端的歌单列表才能把「这一首的交接点已经算出来了」显示给用户 ——
        // 否则分析这件事在整个界面上完全不可见，用户只能从日志里猜。
        boolean headReady = current != null && current.automixPlan != null;
        for (int index = 0; index < queue.size(); index++) {
            QueueEntry e = queue.get(index);
            JsonObject o = e.toJson(false);
            if (index == 0 && headReady) o.addProperty("planReady", true);
            a.add(o);
        }
        return a;
    }

    /**
     * 「下一首」的完整信息 —— 服务端提前把它连同直链发给全房，客户端据此提前下载并解码，
     * 房间换歌才不会出现「播完才发现要下载」的空档。这是房间内无缝交接的原料。
     *
     * <p>只给队首一首，而且只在这一首确实有可用直链时才带 {@code url}；队列里其余条目继续走
     * {@link #queueJson()} 的纯元数据，免得整份歌单的签名直链在每一帧歌单广播里重复传输。</p>
     *
     * <p>单曲循环时下一首就是本曲（交叠到自己是无意义的，还会截掉自己的结尾），队列空时也
     * 没有「下一首」可言 —— 两种都返回 null。</p>
     */
    private JsonObject nextJson() {
        if (repeat == RepeatMode.ONE || queue.isEmpty()) return null;
        QueueEntry head = queue.get(0);
        JsonObject o = head.toJson(head.track.hasFreshUrl());
        // 交接计划一起带上：客户端据此提前预排交叠（用哪一段淡出、新曲从哪一点进）。
        // 计划描述的是「从当前这一首怎么进入下面这一首」，所以它挂在 current 上。
        // 计划比队列广播晚到时服务端会补一次 broadcastQueue，这里自然就带上了。
        JsonObject plan = current == null ? null : current.automixPlan;
        if (plan != null) o.add("plan", plan);
        return o;
    }

    private JsonArray historyJson() {
        JsonArray a = new JsonArray();
        for (QueueEntry e : history) a.add(e.toJson(false));
        return a;
    }

    private void stamp(JsonObject m, long pos) {
        m.addProperty("pos", pos);
        m.addProperty("serverTime", System.currentTimeMillis());
        m.addProperty("seq", seq);
    }

    public void broadcastPlay() {
        if (current == null) return;
        JsonObject m = Proto.msg(Proto.S_PLAY);
        m.add("track", current.toJson(true));
        m.addProperty("requesterName", current.requesterName == null ? "" : current.requesterName);
        m.addProperty("playing", playing);
        stamp(m, positionMs());
        broadcast(m);
    }

    /**
     * 时钟心跳。带 {@code heartbeat: true} 明示「这不是命令」——客户端据此决定用软/硬偏差
     * 那一套阈值去判断要不要纠偏，而不是像收到 play/seek 那样无条件对齐。
     * 命令帧不带这个字段，老客户端读不到也不影响（缺省视为 false）。
     */
    public void broadcastTime() {
        JsonObject m = Proto.msg(Proto.S_TIME);
        m.addProperty("heartbeat", true);
        m.addProperty("playing", playing);
        m.addProperty("duration", current == null ? 0L : current.track.durationMs);
        m.addProperty("listeners", listenerCount());
        stamp(m, positionMs());
        broadcast(m);
    }

    public void broadcastQueue() {
        JsonObject m = Proto.msg(Proto.S_QUEUE);
        m.add("queue", queueJson());
        m.add("history", historyJson());
        // 提前预告下一首（带上它的直链）：客户端据此提前下载并解码，换歌时才交叠得起来。
        // 老客户端不读这个字段，行为不变。
        JsonObject next = nextJson();
        if (next != null) m.add("next", next);
        broadcast(m);
    }

    public void broadcastState() {
        broadcast(snapshot());
    }

    public void notice(String text) {
        JsonObject m = Proto.msg(Proto.S_NOTICE);
        m.addProperty("msg", text);
        broadcast(m);
        // Chat mirror so vanilla clients (no music mod) can still follow the room.
        Net.chatAll(members.keySet(), "[一起听] " + text);
    }

    public void broadcast(JsonObject msg) {
        for (UUID uuid : members.keySet()) send(uuid, msg);
    }

    public void send(UUID uuid, JsonObject msg) {
        Net.send(uuid, msg);
    }

    public void sendSnapshot(UUID uuid) {
        Net.send(uuid, snapshot());
    }
}
