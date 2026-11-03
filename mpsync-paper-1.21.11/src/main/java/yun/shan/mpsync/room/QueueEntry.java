package yun.shan.mpsync.room;

import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A queued 点歌 request: the track plus who asked for it. */
public final class QueueEntry {

    /** 房主/管理员用「下一首播放」插进来的 —— 排在最前。 */
    public static final int TIER_JUMP = 0;
    /** 一首一首点进来的排队歌 —— 中间档。 */
    public static final int TIER_REQUEST = 1;
    /** 整张歌单灌进来的待播歌 —— 谁都能插到它前面。 */
    public static final int TIER_PLAYLIST = 2;

    private static final AtomicLong SEQ = new AtomicLong(1L);

    /** Stable handle so clients can remove/move an entry without index races. */
    public final long entryId = SEQ.getAndIncrement();
    /**
     * 这一首。<b>刻意可变</b>：整房没人答得上时，点歌人可以换到别的平台的同一首歌，
     * 服务端就地把它替掉（{@link Room#replaceTrack}），队列位置、档位、点歌人都保持不变。
     *
     * <p>读它的人不少（快照、前瞻、播放、续签），所以 {@code volatile} 不是装饰：
     * 替换发生在网络线程，而 {@link Room#tick} 与快照在别的线程上遍历队列。</p>
     */
    public volatile Track track;
    public final UUID requester;
    public final String requesterName;
    public final long addedAt = System.currentTimeMillis();

    /**
     * 房主算出的交接计划：这一首放完时怎么交给下一首。
     *
     * <p>刻意可变：计划由房主在曲目<b>已经开播之后</b>上报（要先拿到下一首的音频才能分析），
     * 到达时刻不固定。{@code null} 表示还没有计划，换歌退回配置里的默认提前量 —— 这也是
     * 「老客户端 + 新服务端」以及「房主那台算不出」两种情况的共同退路。</p>
     */
    public volatile JsonObject automixPlan;

    /**
     * 这一格在队列里排第几档，见 {@link #TIER_JUMP} / {@link #TIER_REQUEST} / {@link #TIER_PLAYLIST}。
     *
     * <p>它取代了原来那个「插过队没有」的布尔位。布尔位只说得出「压在所有排队歌前面」，而房间里
     * 现在有三种来路：房主/管理员插队、别人一首首点的排队歌、以及整张歌单灌进来的待播歌。
     * 优先级就按档位数值比：{@code Room#insertAt(int)} 把新条目落在「第一个档位比我低的条目」之前，
     * 于是插队段永远最前、点歌排在歌单之前、歌单垫底，而同一档内部保持先到先得。</p>
     *
     * <p>和原来的 {@code jumped} 一样，档位本身<b>不上协议</b>：客户端要看的是
     * {@link #playlist} 有没有名字，那已经足够把第三档标出来；插队与点歌的区别在聊天提示里写着。
     * 多一个 wire 字段就要多动一处客户端和快照，为一个界面用不上的信息不值当。</p>
     */
    public volatile int tier = TIER_REQUEST;

    /**
     * 这一格来自哪张歌单；不是灌单进来的就是空串。
     *
     * <p>这是上协议的（{@link #toJson(boolean)} 里的 {@code playlist}），因为界面上要能回答
     * 「这一首是谁点的还是歌单里的」—— 一张 100 首的单子灌进来之后，如果列表里全是「点歌 张三」，
     *  nobody 看得出后面几十首是自动排的，也就不知道自己点的那首其实会插在那批歌前面。</p>
     */
    public volatile String playlist = "";


    // ── 分层代解析的会话状态（不上协议，只活在这一格的生命周期里）──────────────
    //
    // 为什么挂在条目上而不是 Room 上：前瞻窗同时开着最多 K 首的解析（默认 2 首），
    // 加上正在播那一首，同一时刻可能有三格各自在等不同层。Room 里放一个「当前第几层」
    // 会让它们互相踩，而 QueueEntry 本来就是这条链路的自然主键（entryId 就是为它设计的）。

    /** 没有任何一层在等 —— 初始态，以及采纳/放弃之后回到这里。 */
    public static final int ASK_IDLE = -1;
    /**
     * 还没采纳过任何「分层求助」的答案。
     *
     * <p>点歌人 enqueue 自带直链的格子<b>不再停留在这个哨兵上</b>（Room 会记成
     * {@code ADOPTED_REQUESTER}）：ADOPTED_NONE 与任何层号比较都为真，留着它的格子
     * 会被任何成员的迟到/伪造 url_supply（层号被服务端推导为 ASK_EVERYONE=6，
     * servedDuration 缺省放行）静默顶掉正在用的链接。</p>
     */
    public static final int ADOPTED_NONE = Integer.MAX_VALUE;
    /**
     * 「链接来自点歌人自己上传」的记账层号。
     *
     * <p>比服务端自解（0）还要可信 —— 这条链就是全房共用的源头。此后任何 url_supply
     * （层号最小也是 0）都过不了 {@link #canAdoptTier}，直到这条链真的过期、
     * {@link Room} 为它重新开分层求助并把本字段复位为 {@link #ADOPTED_NONE}。</p>
     */
    public static final int ADOPTED_REQUESTER = -2;

    /** 当前那一层的层号（0 = 服主 Token 的服务端自解那一层），{@link #ASK_IDLE} = 没在等。 */
    public volatile int askTier = ASK_IDLE;
    /** 这一层是什么时候发出去的，{@link Room#tick} 据此推进。 */
    public volatile long askTierSince;
    /** 这一层点过名的人（含更早层点过的，避免同一台被问两遍）。 */
    private final Set<UUID> asked = new LinkedHashSet<>();
    /** 这一层里已经当场弃权的人。收齐就立刻推进，不等窗口。 */
    private final Set<UUID> declined = new LinkedHashSet<>();
    /** 已采纳的答案来自第几层。迟到的<b>更小</b>层号可以覆盖它（见 {@link #canAdoptTier}）。 */
    public volatile int adoptedTier = ADOPTED_NONE;
    /**
     * 当前正在替这一首跑跨平台匹配的机器；{@code null} = 没派出去（还没轮到、已经放弃、
     * 或者已经拿到链接）。
     *
     * <p>一次只派<b>一台</b>是刻意的：整条流水线是十几秒量级，同时派三台就是三个人一起为
     * 同一首歌烧搜索额度，而且各自都会交回一条替换 —— 谁的后到谁把这一格再换一遍。</p>
     */
    public volatile UUID rescueTarget;
    /** 派出去的时刻，{@link Room} 据此判超时改派。 */
    public volatile long rescueSince;
    /** 派过哪些机器，一台一次（反复骚扰同一台既烧额度也未必第二次就成）。 */
    private final Set<UUID> rescueTried = new LinkedHashSet<>();

    /** 派过的机器名单（快照，供排序时排除与测试断言）。 */
    public Set<UUID> rescueTried() {
        synchronized (rescueTried) { return new LinkedHashSet<>(rescueTried); }
    }

    /** 已经派过几台。 */
    public int rescueAttempts() {
        synchronized (rescueTried) { return rescueTried.size(); }
    }

    /** 把这一首的跨平台匹配派给 {@code uuid}：记进派过名单并起重置窗口表。 */
    public void openRescue(UUID uuid) {
        if (uuid == null) return;
        synchronized (rescueTried) { rescueTried.add(uuid); }
        rescueTarget = uuid;
        rescueSince = System.currentTimeMillis();
    }

    /** 救援收尾：拿到链接、全用完、或者这一格换成了别的曲目都走这里。 */
    public void closeRescue() {
        rescueTarget = null;
        rescueSince = 0L;
    }

    /** 这一层点名过谁（用于「收齐弃权」的判据）。 */
    public Set<UUID> asked() { synchronized (asked) { return new LinkedHashSet<>(asked); } }

    /** 把 {@code uuid} 记进「这一层问过」，返回是不是新面孔（老面孔不该再收这一发）。 */
    public boolean markAsked(UUID uuid) {
        if (uuid == null) return false;
        synchronized (asked) { return asked.add(uuid); }
    }

    /** 开一层：清空上一轮的弃权、记下层号与起表时刻。 */
    public void openTier(int tier, java.util.Collection<UUID> recipients) {
        synchronized (asked) { if (recipients != null) asked.addAll(recipients); }
        synchronized (declined) { declined.clear(); }
        askTier = tier;
        askTierSince = System.currentTimeMillis();
    }

    /** 收一条弃权，返回「这一层是不是已经全弃权了」（全弃权就立刻推进，不等窗口）。 */
    public boolean decline(UUID uuid) {
        if (uuid == null) return false;
        synchronized (declined) { declined.add(uuid); }
        Set<UUID> who = asked();
        Set<UUID> out;
        synchronized (declined) { out = new LinkedHashSet<>(declined); }
        return !who.isEmpty() && who.containsAll(out);
    }

    public void clearDeclined() { synchronized (declined) { declined.clear(); } }

    /** 这一层里已经答过或弃权过的人不该再被同一层问一次。 */
    public boolean spoken(UUID uuid) {
        Set<UUID> who = asked();
        Set<UUID> out;
        synchronized (declined) { out = new LinkedHashSet<>(declined); }
        return who.contains(uuid) || out.contains(uuid);
    }

    /** 结束这一层的等待（采纳到了、或者决定跳过）。 */
    public void closeAsk() {
        askTier = ASK_IDLE;
        askTierSince = 0L;
        synchronized (declined) { declined.clear(); }
    }

    /**
     * 一个来自第 {@code tier} 层的答案能不能采纳。
     *
     * <p>规则只有一条：<b>比现在这条更可信（层号更小）才行</b>。同层内先答先赢 —— 两条同层的
     * 链接互相覆盖会打乒乓，而它们本来就该是同一档质量。没采纳过（{@link #ADOPTED_NONE}）
     * 时任何合格答案都能进。</p>
     */
    public boolean canAdoptTier(int tier) { return tier < adoptedTier; }

    /** 这一格是不是房主/管理员插进来的。 */
    public boolean jumped() { return tier == TIER_JUMP; }

    public QueueEntry(Track track, UUID requester, String requesterName) {
        this.track = track;
        this.requester = requester;
        this.requesterName = requesterName;
    }

    public JsonObject toJson(boolean withUrl) {
        JsonObject o = track.toJson(withUrl);
        o.addProperty("entryId", entryId);
        o.addProperty("requester", requester == null ? "" : requester.toString());
        o.addProperty("requesterName", requesterName == null ? "" : requesterName);
        o.addProperty("addedAt", addedAt);
        // 只在真属于某张歌单时才带这个键：队列里绝大多数条目是逐首点的，没必要每帧多发一份空串。
        if (playlist != null && !playlist.isEmpty()) o.addProperty("playlist", playlist);
        return o;
    }
}
