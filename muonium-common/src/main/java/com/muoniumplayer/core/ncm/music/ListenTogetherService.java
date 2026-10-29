package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一起听的<b>音源无关</b>门面：把「这一间房间归谁管」收在一处。
 *
 * <h3>为什么要多这一层</h3>
 * <p>网易云与酷狗各有一套房间服务，方法是照着彼此写的（{@code Phase} 与 {@code Role}
 * 的常量集合完全相同），而房间面板只想问「现在能进吗、在哪个阶段、成员有谁」。
 * 没有这一层的话，面板里三十多个调用点每个都要自己判一次音源——那正是最容易在某个分支上
 * 把两个房间的会话混起来的地方（用网易云的房号去发酷狗的心跳，两边都不会报错，只会静默失灵）。</p>
 *
 * <h3>归属怎么定</h3>
 * <p><b>已经在谁的房间里，就还是谁的</b>（{@link #ownedByKuGou()}）。进房之后再换播放源
 * 不会把会话搬走，否则心跳与同步会打到另一个源上；只有「手上还没有房间」时才按当前音源选。
 * 面板上那颗「创建房间」按钮走的也是同一个判据，所以不会出现选了酷狗房间却建成网易云房间。</p>
 *
 * <h3>嵌套类型为什么自己再定义一套</h3>
 * <p>门面不能把 {@code NeteaseListenTogetherService.Phase} 借来当公共词汇——那等于让酷狗
 * 依赖网易云。这里按名字转换（两边常量同名，转换点在 {@link #phaseOf} 与 {@link #roleOf}），
 * 代价是多三个类型；好处是面板那边只改类名前缀，一行渲染逻辑都不用重写。
 * <b>两边枚举若有一方加了常量，这里会以 {@code IllegalArgumentException} 立刻炸出来</b>，
 * 而不是静默落到某个默认值上——这是有意的，见 {@link #byName}。</p>
 *
 * <h3>能力差（别把「不知道」读成「没问题」）</h3>
 * <ul>
 *   <li>酷狗<b>没有</b>查他人会员状态的公开端点，所以 {@link #hasNonVipMember()}、
 *       {@link #getNonVipMemberNames()}、{@link #isMemberNonVip(long)} 在酷狗房间下恒为
 *       false / 空。含义是「无法判断」，因此界面上不会出会员提醒——不是「房间里都是会员」。</li>
 *   <li>网易云的「主机跳过会员曲」（{@code shouldHostSkip}）因此也没有对等实现，
 *       门面上<b>不提供</b>这个方法。别为了「两边都有」拿 false 糊上去：那会让酷狗房间
 *       在界面看起来像做了这件事。</li>
 *   <li>酷狗特有的能力（聊天、房间队列、点歌、发现房间）这里也<b>不暴露</b>：
 *       门面只服务两个源共用的那块界面，酷狗独有的部分直接调
 *       {@link KuGouListenTogetherService}。</li>
 * </ul>
 */
public final class ListenTogetherService {

    private ListenTogetherService() {
    }

    /** 房间里的一名成员。字段名与两侧服务保持一致。 */
    public static final class Member {
        public final long userId;
        public final String nickname;
        public final String avatarUrl;

        Member(long userId, String nickname, String avatarUrl) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        }
    }

    public enum Role {
        /** 没有会话。 */
        NONE,
        /** 自己建的房间：会把本地播放状态上报出去。 */
        HOST,
        /** 加入别人的房间：只跟随，不上报指令。 */
        GUEST
    }

    public enum Phase {
        IDLE,
        /** 正在建房/加入，界面显示转子。 */
        CONNECTING,
        ACTIVE,
        /** 终态失败，界面显示原因并允许重试。 */
        FAILED
    }

    // ──────────────────────────── 归属 ────────────────────────────

    /**
     * 门面手上有一个后台动作在跑（只可能是酷狗那侧，见 {@link #runInBackground}）。
     *
     * <p>它不参与归属判断，也不代表动作成功与否：动作一落地就归零，成败看后端的 phase 与状态行。</p>
     */
    private static volatile boolean pendingAction;

    /**
     * 这一间房间（或下一次要建的房间）是不是酷狗的。
     *
     * <p>先问「谁正开着房间」，再退回当前音源。顺序不能反：房主身份、心跳与同步都绑在
     * 原来那套会话上，换播放源不该把它们搬走。</p>
     */
    public static boolean ownedByKuGou() {
        if (KuGouListenTogetherService.isActive()) return true;
        if (NeteaseListenTogetherService.isActive()) return false;
        try {
            return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.KUGOU;
        } catch (Throwable throwable) {
            // 读音源这件事本身不该抛，抛了就退回网易云：那是一直在跑的那条链路。
            return false;
        }
    }

    /** 界面上该说这是谁的一起听（副标题与提示语用）。 */
    public static String backendLabel() {
        return ownedByKuGou() ? "酷狗" : "网易云";
    }

    // ──────────────────────────── 状态 ────────────────────────────

    public static boolean isAvailable() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.isAvailable()
                : NeteaseListenTogetherService.isAvailable();
    }

    public static Phase getPhase() {
        Phase phase = ownedByKuGou()
                ? phaseOf(KuGouListenTogetherService.getPhase())
                : phaseOf(NeteaseListenTogetherService.getPhase());
        // 后台动作还在跑、后端却仍报「没连接」时，界面要显示连接中而不是未连接。
        // 只在后端自己没说清楚的时候补这一档——它已经 ACTIVE 或 FAILED 就以它为准。
        if (pendingAction && (phase == Phase.IDLE || phase == Phase.FAILED)) return Phase.CONNECTING;
        return phase;
    }

    public static Role getRole() {
        return ownedByKuGou()
                ? roleOf(KuGouListenTogetherService.getRole())
                : roleOf(NeteaseListenTogetherService.getRole());
    }

    public static boolean isActive() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.isActive()
                : NeteaseListenTogetherService.isActive();
    }

    /**
     * 互斥判据用的「会话在进行中」：包含 CONNECTING（连接中）。
     *
     * <p>三处互斥闸（直连 hostRoom/joinRoom、服务器房间 conflictingSession）原来只查
     * {@link #isActive()}——它只认 ACTIVE。用户在官方「正在连接」的几秒窗口里开直连/
     * 服务器房间，两套引擎并行落地：官方 worker 的差分回写跑在检查之前，会把已被房间
     * S_PLAY 改写的本地播放整表推进刚建的官方房。CONNECTING 虽未 ACTIVE，但动作在飞、
     * 随时会变 ACTIVE，互斥必须把这一档算进去。</p>
     */
    public static boolean isEngaged() {
        Phase phase = getPhase();
        return phase == Phase.ACTIVE || phase == Phase.CONNECTING;
    }

    public static boolean isBusy() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.isBusy()
                : NeteaseListenTogetherService.isBusy();
    }

    public static String getStatusText() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.getStatusText()
                : NeteaseListenTogetherService.getStatusText();
    }

    public static boolean isStatusError() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.isStatusError()
                : NeteaseListenTogetherService.isStatusError();
    }

    public static String getRoomId() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.getRoomId()
                : NeteaseListenTogetherService.getRoomId();
    }

    public static String getInviteCode() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.getInviteCode()
                : NeteaseListenTogetherService.getInviteCode();
    }

    /**
     * 邀请用的分享链接。
     *
     * <p>酷狗官方<b>没有</b>一起听的分享链接 scheme（参考实现里找不到），所以那一侧只有
     * 自造的 {@code kugou:} 短码；链接位上显示的就是它。别指望外部 App 能认这个串。</p>
     */
    public static String getShareLink() {
        return ownedByKuGou()
                ? KuGouListenTogetherService.getShareLink()
                : NeteaseListenTogetherService.getShareLink();
    }

    public static List<Member> getMembers() {
        if (!ownedByKuGou()) {
            List<NeteaseListenTogetherService.Member> source = NeteaseListenTogetherService.getMembers();
            if (source.isEmpty()) return Collections.emptyList();
            List<Member> result = new ArrayList<Member>(source.size());
            for (NeteaseListenTogetherService.Member member : source) {
                result.add(new Member(member.userId, member.nickname, member.avatarUrl));
            }
            return result;
        }
        List<KuGouListenTogetherService.Member> source = KuGouListenTogetherService.getMembers();
        if (source.isEmpty()) return Collections.emptyList();
        List<Member> result = new ArrayList<Member>(source.size());
        for (KuGouListenTogetherService.Member member : source) {
            result.add(new Member(member.userId, member.nickname, member.avatarUrl));
        }
        return result;
    }

    // ──────────────────────────── 会员可见性（酷狗侧只能「不知道」） ────────────────────────────

    /** 酷狗房间恒为 false：没有查他人会员状态的公开端点，这是「不知道」而不是「都是会员」。 */
    public static boolean hasNonVipMember() {
        return !ownedByKuGou() && NeteaseListenTogetherService.hasNonVipMember();
    }

    /** 同上：酷狗房间返回空列表。 */
    public static List<String> getNonVipMemberNames() {
        return ownedByKuGou() ? Collections.<String>emptyList() : NeteaseListenTogetherService.getNonVipMemberNames();
    }

    /** 同上：酷狗房间恒为 false。 */
    public static boolean isMemberNonVip(long userId) {
        return !ownedByKuGou() && NeteaseListenTogetherService.isMemberNonVip(userId);
    }

    // ──────────────────────────── 动作 ────────────────────────────

    /**
     * 建一间房。
     *
     * <p><b>返回 true 只表示「这一步被接受了、已经开始」</b>，不代表房间建成了：
     * 网易云那侧本来就是异步的（失败写在状态行里），酷狗那侧被门面挪到了后台线程。
     * 终态要看 {@link #getPhase()} 与 {@link #getStatusText()}。</p>
     *
     * <p>酷狗建房要带一份初始队列，取的是<b>当前播放列表</b>；列表里一首酷狗曲都没有时
     * 酷狗那侧会直接失败并在状态行写明原因（它的房间必须有歌单，那是上游的形状）。</p>
     */
    public static boolean createRoom() {
        // 与 MC 服务器房间/直连房间互斥：两套引擎都想独占播放器（本类差分上报会把
        // 别人的队列整表搬进官方房间，MC 房间的 S_PLAY 又会改写本地播放），同时开
        // 就是互相拉扯。入口挡死并写明原因，比静默串台好。
        if (conflictingSession()) return false;
        if (!ownedByKuGou()) {
            NeteaseListenTogetherService.createRoom();
            return accepted();
        }
        final List<Music> seeds = currentQueue();
        runInBackground("创建房间", () -> KuGouListenTogetherService.createRoom(seeds));
        return true;
    }

    /**
     * 按邀请码/分享链接加入。
     *
     * <p>{@code fallbackInviterId} 是网易云那侧要的（它的房间号要和邀请人配对才解得开），
     * 酷狗不需要，所以在酷狗分支下被忽略——不是漏传。</p>
     */
    public static boolean joinRoom(String invite, long fallbackInviterId) {
        // 与 createRoom 同款互斥：官方房间与 MC 服务器/直连房间二选一。
        if (conflictingSession()) return false;
        if (!ownedByKuGou()) {
            NeteaseListenTogetherService.joinRoom(invite, fallbackInviterId);
            return accepted();
        }
        final String code = invite == null ? "" : invite;
        runInBackground("加入房间", () -> KuGouListenTogetherService.joinRoom(code));
        return true;
    }

    /** 退房。返回值同 {@link #createRoom()}：只代表「已经开始」，不代表已经退干净。 */
    public static boolean leave() {
        if (!ownedByKuGou()) {
            NeteaseListenTogetherService.leave();
            return accepted();
        }
        runInBackground("退出房间", () -> KuGouListenTogetherService.leave());
        return true;
    }

    /** 客机手动对齐一次主机进度。 */
    public static boolean syncOnce() {
        if (!ownedByKuGou()) {
            NeteaseListenTogetherService.syncOnce();
            return true;
        }
        runInBackground("同步主机进度", () -> KuGouListenTogetherService.syncOnce());
        return true;
    }

    /** 门面手上是否还有一个后台动作在跑。 */
    public static boolean isPending() {
        return pendingAction;
    }

    // ──────────────────────────── 后台化与快照 ────────────────────────────

    /**
     * 把酷狗那侧的同步动作挪到后台线程，并在期间维持 {@link Phase#CONNECTING}。
     *
     * <p>为什么需要这一层：<b>酷狗的房间方法是同步的</b>，一次建房要连着打好几个请求，
     * 直接从点击回调里调会把游戏卡住一两秒。挪到后台之后，那半秒里界面必须仍然显示
     * 「连接中」，否则用户以为没点上会再点一次——所以 {@link #pendingAction} 会喂给
     * {@link #getPhase()}。</p>
     *
     * <p>归属在派线程之前就问过一次并传进闭包，<b>不</b>在后台线程里重新问
     * {@link #ownedByKuGou()}：用户在等结果的那半秒里换了音源，也不该把这次动作
     * 改派到另一套会话上去。</p>
     */
    private static void runInBackground(String what, java.util.concurrent.Callable<Boolean> action) {
        // 上一次还没跑完就吃掉这一枪：连点两下「创建房间」不该开出两条会话。
        // 但静默丢弃会让按钮像坏了——至少在状态行说一声。
        if (pendingAction) {
            if (!ownedByKuGou()) {
                NeteaseListenTogetherService.publishStatus("上一个操作还在进行，请稍候再试", true);
            } else {
                KuGouListenTogetherService.publishStatus("上一个操作还在进行，请稍候再试", true);
            }
            return;
        }
        pendingAction = true;
        MultiThreadingUtil.runAsync(() -> {
            try {
                Boolean result = action.call();
                if (result != null && !result) {
                    System.err.println("[ListenTogether] " + what + " 未成功：原因在房间状态行里");
                }
            } catch (Throwable throwable) {
                System.err.println("[ListenTogether] " + what + " 异常: " + throwable.getMessage());
            } finally {
                pendingAction = false;
            }
        });
    }

    /** 当前播放列表的快照（在调用线程上拷，免得后台遍历时列表正在被改）。 */
    private static List<Music> currentQueue() {
        List<Music> source = CloudMusic.playList;
        if (source == null || source.isEmpty()) {
            Music playing = CloudMusic.currentlyPlaying;
            if (playing == null) return Collections.emptyList();
            List<Music> single = new ArrayList<Music>(1);
            single.add(playing);
            return single;
        }
        try {
            return new ArrayList<Music>(source);
        } catch (java.util.ConcurrentModificationException raced) {
            // 换歌线程正在整表替换/增删：这一拍拿不到快照，等下一次同步即可，
            // 不能让 CME 冒上去被当成「上游无响应」刷屏。
            return Collections.emptyList();
        }
    }

    /** 网易云那侧的异步动作有没有「被立刻拒绝」。见 {@link #createRoom()} 的返回值说明。 */
    private static boolean accepted() {
        return phaseOf(NeteaseListenTogetherService.getPhase()) != Phase.FAILED;
    }

    /**
     * 官方一起听与 MC 服务器房间 / 直连房间是否冲突（互斥判据）。
     *
     * <p>冲突时把原因写进状态行（网易云侧）——用户点按钮看到的是明确的拒绝原因，
     * 而不是「点了没反应」或两套引擎静默串台。</p>
     */
    private static boolean conflictingSession() {
        if (com.muoniumplayer.party.direct.MpDirect.isActive()) {
            reject("直连房间正在进行：先在「直连房间」页断开，再使用官方一起听");
            return true;
        }
        if (com.muoniumplayer.party.MpPartyClient.inRoom()) {
            reject("已在服务器房间中：先退出房间，再使用官方一起听");
            return true;
        }
        if (com.muoniumplayer.party.direct.MpDirect.isPendingJoin()) {
            reject("正在连接直连房间：先等它完成或超时，再使用官方一起听");
            return true;
        }
        return false;
    }

    /** 拒绝原因写进当前音源的状态行。 */
    private static void reject(String text) {
        if (ownedByKuGou()) {
            KuGouListenTogetherService.publishStatus(text, true);
        } else {
            NeteaseListenTogetherService.publishStatus(text, true);
        }
    }

    // ──────────────────────────── 转换 ────────────────────────────

    private static Phase phaseOf(Enum<?> value) {
        return byName(Phase.class, Phase.values(), value, Phase.IDLE);
    }

    private static Role roleOf(Enum<?> value) {
        return byName(Role.class, Role.values(), value, Role.NONE);
    }

    /**
     * 按常量名转换两侧的同形枚举。
     *
     * <p>名字对不上时抛 {@code IllegalArgumentException}：<b>这是故意的</b>。门面若在这里
     * 静默退到默认值，表现会是「酷狗房间已经建好了，界面还显示未连接」这一类查起来很贵的错——
     * 而加常量的人只要跑一次房间面板就会撞上这条异常。</p>
     */
    private static <T extends Enum<T>> T byName(Class<T> target, T[] constants, Enum<?> value, T fallback) {
        if (value == null) return fallback;
        for (T candidate : constants) {
            if (candidate.name().equals(value.name())) return candidate;
        }
        throw new IllegalArgumentException("一起听状态对不上：" + target.getSimpleName()
                + " 里没有 " + value.name() + "（两侧服务的枚举常量集合必须同形，见 ListenTogetherService 类注释）");
    }
}
