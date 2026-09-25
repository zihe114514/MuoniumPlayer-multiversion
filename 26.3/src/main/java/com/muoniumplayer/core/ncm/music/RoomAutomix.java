package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;

/**
 * 一起听（mpsync）房间里的无缝交接。
 *
 * <p><b>问题。</b>房间的换歌由服务端一首一首下发：{@code MpPlayback} 每次起播都把本机队列
 * 整条换成单曲队列，于是 {@code peekNextIndex()} 恒为 -1，automix 的预备链路完全看不到
 * 下一首 —— 本机那套「读两首歌的 PCM、测出该在哪里交接」的分析在房间里从来没跑过。</p>
 *
 * <p><b>为什么不能各算各的。</b>交接点由音频内容决定（人声尾、尾奏落点、前奏长度、节拍网格），
 * 而房间里「正在播到第几毫秒」是<b>服务端时钟一家说了算</b>。若各机自行分析，交叠窗内各机对
 * 「新曲目处于什么位置」的理解就会分叉，服务端心跳一比对就会把刚开始的淡入 seek 掉 ——
 * 那就不是「一起听」了。所以房间需要的不是「每台都算」，而是<b>一份全房共享的计划</b>。</p>
 *
 * <p><b>怎么做到两全。</b>分析仍然是客户端做的（服务端没有音频，也解析不了各平台取流），
 * 但<b>由哪一台来做不再固定</b>：服务端在每一首开播时指派一台 —— <b>点歌人优先</b>，他没有
 * 分析能力就借房主的，房主也不行就借房间里任何一个自报能算的人；被指派者算完上报，服务端
 * 把它作为权威广播给全房。于是「分析真的看内容」和「全房用同一个交接点」同时成立，
 * 而这个类就是这条链路的登记处。</p>
 *
 * <p><b>为什么不是房主独占。</b>算力落在谁手上跟「谁是房主」没关系，取决于那台机器有没有
 * 装模型、音频读不读得到。钉死房主会让三种情况白瞎：房主是个没模型的客户端、房主压根是
 * modernMP（它没有分析这个概念）、而房间里明明有人装了模型闲着。「谁点的歌就用谁的电脑先算」
 * 既符合直觉，也常常最快 —— 那首歌的音频很可能已经在点歌人本机解码过了。</p>
 *
 * <p><b>用哪个引擎由被指派的那台决定。</b>它开着 Folia 就用 Folia（装了模型就带 stem 交接、
 * 频段倾斜、过渡族这些东西），是原版智能淡化就用内置信号处理。两条路都<b>真的读音频</b>；
 * Folia 连一条计划都给不出时（曲目太短、音频读不到）再降级到内置信号处理，见
 * {@link AutomixPlanner#planForRoom}。「各机模型不同会算出不同交接点」这件事不构成问题 ——
 * 算的只有被指派的那一台，「可复现」由「只有一个生产者」保证。</p>
 *
 * <p><b>两种来源，一个结果。</b>{@link #planWith} 按优先级取：</p>
 * <ol>
 *   <li>服务端已经广播过来的计划 —— 全房共享的那一份，直接用，本机不再分析；</li>
 *   <li>还没有计划、而本机正是<b>被指派的那一台</b> —— 真分析一次，并把结果交出去广播；</li>
 *   <li>其余情况（别人正在算、算不出来、或本机没被指派）—— <b>不交叠</b>，让服务端
 *       到点换歌，本机跟着换。这里刻意<b>没有</b>「按曲末减一个固定值」的假交叠：
 *       不读音频的交叠只会让两台机器在同一刻放出两条不同的波形，比硬切更难听。</li>
 * </ol>
 *
 * <p>第 3 条只会在「全房此刻还拿不出交接点」时停留，而这时本机单独播放也一样不会交叠 ——
 * 房间里不交叠并没有比平时更差。计划一到（服务端广播），下一轮预备就会按它来。</p>
 *
 * <p>老服务端不发 {@code automixLeadMs}，取到的就是 0，{@link #active()} 随之恒为 false，
 * 整条链路与今天逐字节一致。老服务端也不会指派任何人，此时退回「房主那台自己算」的旧行为
 * （见 {@link #analystExpected() }）—— 这样新旧服务端配同一个客户端都能工作。
 * 用户本机关掉无缝切换时也走同一条短路（尊重本机音频偏好）。</p>
 */
public final class RoomAutomix {

    private RoomAutomix() {
    }

    /** 与服务端 {@code MpConfig.automixLeadMs} 的上限一致：再长两首歌就打成一片了。 */
    public static final long MAX_LEAD_MILLIS = 12_000L;

    /** 短于此的交叠算不上混音，宁可退回硬切。 */
    private static final long MIN_LEAD_MILLIS = 400L;

    /** 计划里曲内位置的合理上限：超出这个值只可能是算错了或者对错了曲目。 */
    private static final long MAX_PLAUSIBLE_FIRE_MILLIS = 30L * 60_000L;

    /**
     * 交叠长度的上限。
     *
     * <p>刻意比 {@link #MAX_LEAD_MILLIS} 宽松得多：那 12 秒约束的是服务端的换歌提前量
     * （{@code automixLeadMs}，一个配置项），而分析挑的是一段完整的长尾奏或间奏 ——
     * 一分钟已经是两首歌相叠的极限，再长就不是混音而是串烧了。</p>
     */
    private static final long MAX_OVERLAP_MILLIS = 60_000L;

    /** 房间下发的交叠长度；0 = 房间没开无缝过渡（默认）。 */
    private static volatile long leadMillis;

    /**
     * 本机是不是房间里的<b>房主</b>。
     *
     * <p>只用来做一件事：老服务端不会指派任何人（它还不认识 {@code analyze_assign}），
     * 这时退回旧行为 —— 房主那台自己算。新服务端一旦指派过，{@link #assignedToUs}
     * 就是唯一判据，这个字段不再参与「谁来算」。</p>
     */
    private static volatile boolean owner;

    /** 已登记的下一首。用<b>身份</b>而不是 id 比对：同一首歌重新构造出来就是另一个对象。 */
    private static volatile Music armedTrack;

    /** 已登记的那一首在服务端歌单里的条目号；上报计划时用它让服务端认领给正确的那一首。 */
    private static volatile long armedEntryId;

    /** 服务端广播过来的权威计划；还没有时为 null。 */
    private static volatile AutomixPlan sharedPlan;

    /**
     * 上面那份计划的<b>语义</b>指纹，用于幂等判定。
     *
     * <p>按字段而不是按报文串比对：同一份计划会走一遍「本机算出 → 上报 → 服务端回声」，
     * 中间的 JSON 序列化不保证逐字节相同（数字写法、键序都可能变），但解出来的计划一定相同。
     * 拿报文串比对会在房主自己那台上凭空触发一次重新预备。</p>
     */
    private static volatile String sharedPlanKey = "";

    /** 上报通道；由一起听那层注入，房间之外永远是 null。 */
    private static volatile PlanSink sink;

    /**
     * 本机此刻是不是被服务端指派去算这一首的那一台。
     *
     * <p>这一位就是「谁来分析」的唯一答案。它可能落在任何人身上：点歌人、房主、
     * 或者房间里某个装了模型的第三台。分析权因此不再跟房主身份绑定。</p>
     */
    private static volatile boolean assignedHere;

    /** 被指派的那一份说的是哪一首「下一首」；对不上就作废（队列变过）。 */
    private static volatile long assignedEntryId;

    /**
     * 服务端有没有指派过任何一台（含指派给别人）。
     *
     * <p>用来区分「新服务端说了话」与「老服务端压根不懂这件事」：前者必须以指派为准，
     * 后者才退回「房主自己算」的旧行为。见 {@link #analystExpected()}。</p>
     */
    private static volatile boolean assignmentSeen;

    /** 服务端有没有明确说过「这一首没人能算」。说过就不必再等，直接走不交叠。 */
    private static volatile boolean nobodyCanAnalyze;

    /**
     * 本机能不能跑交接计划的分析（自报给服务端的能力位）。
     *
     * <p>由一起听那层按本机实际情况置位：装了模型 → {@code true} + {@code "model"}，
     * 只有内置信号处理 → {@code true} + {@code "dsp"}。{@code false} 的机器永远不会被指派。</p>
     */
    private static volatile boolean capable;

    /** 能力档位：{@code "model"} / {@code "dsp"} / 空串。只影响服务端选人的优先级。 */
    private static volatile String engine = "";

    /**
     * 房主上报计划的通道。
     *
     * <p>由一起听那层注入，核心播放层因此不必反向依赖它 —— 与
     * {@code CloudMusic} 把房间的调用入口开成 public 静态方法是同一个思路。</p>
     */
    public interface PlanSink {
        /**
         * @param entryId  报告给服务端的条目号：这份计划说的是「这一首放完时怎么交接」
         * @param plan     计划本体，字段见 {@link #encode}
         */
        void publish(long entryId, JsonObject plan);

        /**
         * 本机被指派了但算不出这一首的交接点，请服务端改派下一台。
         *
         * <p>有它才谈得上「借用别人的电脑」：否则每问到一台算不出的机器都要白等一个
         * 指派超时，而这类机器（曲目太短、音频拉不到、模型缺失）恰恰是常见的。</p>
         *
         * @param entryId 放弃的是哪一份
         */
        void decline(long entryId);
    }

    /** 注入上报通道（一起听那层初始化时调用一次）。 */
    public static void setPlanSink(PlanSink target) {
        sink = target;
    }

    /** 服务端下发／收回房间交叠长度；{@code < MIN_LEAD_MILLIS} 即关闭，并顺手撤下已登记的下一首。 */
    public static void configure(long millis) {
        long value = millis;
        if (value < 0L) value = 0L;
        if (value > MAX_LEAD_MILLIS) value = MAX_LEAD_MILLIS;
        leadMillis = value;
        if (value < MIN_LEAD_MILLIS) disarm();
    }

    public static long leadMillis() {
        return leadMillis;
    }

    /**
     * 告知本机是不是房间房主。
     *
     * <p>房主身份现在只用于<b>老服务端的兜底</b>：那些服务端不会指派任何人，也就没有人会
     * 通知本机「该你算了」，只能按老规矩让房主自己算。新服务端一旦指派过
     * （{@link #assignmentSeen}），这一位就不再影响谁来算。</p>
     *
     * <p>身份在 {@code hello}、房间快照与成员变化时刷新。</p>
     */
    public static void setOwner(boolean value) {
        owner = value;
    }

    /** 本机是不是房主（仅老服务端兜底用，见 {@link #setOwner}）。 */
    public static boolean isOwner() {
        return owner;
    }

    /**
     * 本机此刻该不该跑分析：被指派了，或者（老服务端）本机是房主且没有任何指派记录。
     */
    public static boolean analyzingHere() {
        if (assignedHere) return true;
        return owner && !assignmentSeen;
    }

    /** 老接口名保留为 {@link #setOwner} 的同义调用，避免调用点一次性全改。 */
    public static void setAnalyzer(boolean value) {
        setOwner(value);
    }

    /**
     * 服务端指派/撤回本机的分析任务。
     *
     * <p>{@code entryId} 是这一份要算的是哪一首「下一首」。撤回（{@code false}）通常在
     * 计划已经到手、或者队首换人时发生。</p>
     */
    public static void setAssigned(boolean value, long entryId) {
        assignmentSeen = true;
        if (value) {
            assignedHere = true;
            assignedEntryId = entryId;
            nobodyCanAnalyze = false;
        } else if (assignedEntryId == 0L || entryId == 0L || assignedEntryId == entryId) {
            // 只在「撤回的正是我手上这一份」时才真撤，免得一条关于别首的撤回把本机清了。
            assignedHere = false;
            assignedEntryId = 0L;
        }
    }

    /**
     * 新连接建立（收到 {@code hello}）时复位「本条连接已见过服务端指派」这一位。
     *
     * <p>{@link #assignmentSeen} 只该反映<b>当前连接</b>的服务端有没有指派过：新服务端一旦指派就
     * 置真、房主兜底随之退场；老服务端从不指派，它就该一直是假，好让房主自己算。但
     * {@link #setAssigned} 会无条件把它置真 —— 连 {@code onDisconnect} 里那次撤销也算 ——
     * 若不在每条连接开头复位，断开重连之后它就永远为真：老服务端房间里房主再也不算了，
     * {@link #analyzingHere()} 恒假，无缝交接静默退化成硬切。复位点选在 {@code hello}，
     * 正是「服务端刚认识我们、这条连接还没派过任何活」的那一刻。</p>
     */
    public static void resetAssignmentSeen() {
        assignmentSeen = false;
    }

    /** 本机此刻是不是被指派者。 */
    public static boolean assignedToUs() {
        return assignedHere;
    }

    /**
     * 服务端说「这一首没有人能算」。
     *
     * <p>收到之后本机不再等待：{@link #awaitingPlan} 直接返回 false，预备链路照常跑，
     * 只是 {@link #planWith} 会给出 {@code null} —— 也就是不交叠。省掉的是「明知没人算还
     * 一直挂着等」的那份悬空状态。</p>
     */
    public static void setNobodyCanAnalyze(boolean value) {
        nobodyCanAnalyze = value;
    }

    /**
     * 告知本机是否具备分析能力，以及档位。
     *
     * <p>由一起听那层按本机实际情况调用（装了模型 → {@code "model"}），随 profile 一起
     * 上报给服务端。服务端据此决定要不要把分析任务派到本机来。</p>
     */
    public static void setCapability(boolean canAnalyze, String engineId) {
        capable = canAnalyze;
        engine = engineId == null ? "" : engineId;
    }

    /** 本机自报的分析能力，见 {@link #setCapability}。 */
    public static boolean capable() {
        return capable;
    }

    /** 本机自报的能力档位：{@code "model"} / {@code "dsp"} / 空串。 */
    public static String engine() {
        return engine;
    }

    /**
     * 房间模式当前是否生效：房间开了交叠、且用户本机没关掉无缝切换。
     *
     * <p>刻意尊重 {@link AutomixSettings#isEnabled()}：把无缝切换关掉是用户的音频偏好，
     * 不该因为「进了房间」就被推翻。</p>
     */
    public static boolean active() {
        return leadMillis >= MIN_LEAD_MILLIS && AutomixSettings.isEnabled();
    }

    /**
     * 登记「下一首」。
     *
     * <p>计划可能比曲目晚到（房主要先分析），所以同一首可以被登记两次：第一次不带计划，
     * 第二次带上服务端广播回来的那一份。两次都只是覆盖登记内容，不会作废已经解码好的播放牌 ——
     * 房主自己那台更是如此，它上报之后会收到自己那份计划的回声，那时指纹相同，什么都不会变。</p>
     *
     * @param next    房间预告的下一首
     * @param entryId 它在服务端歌单里的条目号；未知时为 0
     * @param plan    服务端广播的共享计划；还没有时为 {@code null}
     */
    public static void arm(Music next, long entryId, JsonObject plan) {
        if (next == null || !active()) {
            disarm();
            return;
        }
        armedTrack = next;
        armedEntryId = entryId;
        sharedPlan = decode(plan);
        sharedPlanKey = keyOf(sharedPlan);
    }

    /** 这一份计划与已登记的共享计划是不是同一份（用于跳过重复的队列补装）。 */
    public static boolean sameSharedPlan(JsonObject plan) {
        return keyOf(decode(plan)).equals(sharedPlanKey);
    }

    /**
     * 房间在管这一首，但交接计划还没到 —— 此刻预备没有意义。
     *
     * <p>预备链路据此<b>先不启动</b>：分析要几秒，而这份计划本来就该由它决定用哪个入口点、
     * 用哪条曲线，先按本机自己的判据解一遍再作废，等于白跑一次最重的后台任务。这个门对
     * 「借来的那台机器」尤其重要 —— 它要白跑一次 Folia 的 stem 分离（Python 进程）。</p>
     *
     * <p>三种情况不等：本机就是被指派者（{@link #planWith} 会自己算）、服务端已经说过
     * 「没人能算」（等也等不到）、以及老服务端（它不会发计划，房主自己算）。</p>
     *
     * <p>等到计划真的来了，本方法下一轮就会返回 false，预备自然接上 —— 不需要任何唤醒机制。</p>
     */
    public static boolean awaitingPlan(Music next) {
        if (!active() || next == null || next != armedTrack || sharedPlan != null) return false;
        if (nobodyCanAnalyze) return false;      // 已经知道没人能算，别挂着等
        return !analyzingHere();                 // 自己被指派时不等，直接去算
    }

    /** 撤下已登记的交叠点（换歌、离房、房间关掉交叠）。 */
    public static void disarm() {
        armedTrack = null;
        armedEntryId = 0L;
        sharedPlan = null;
        sharedPlanKey = "";
        // 指派跟着这一首走：登记被撤了，本机手上的分析任务也就不作数了。
        assignedHere = false;
        assignedEntryId = 0L;
        nobodyCanAnalyze = false;
    }

    public static boolean armed() {
        return armedTrack != null && active();
    }

    /** 这一首是不是本机登记过、且房间交叠正开着的那一首。 */
    static boolean handles(Music next) {
        return next != null && next == armedTrack && active();
    }

    /**
     * 房间模式下的计划：见类注释里的三条优先级。
     *
     * @param next                本次预备的那首曲子，必须与 {@link #arm} 登记的是同一个实例
     * @param lastVocalEndMillis  退出曲目最后一句歌词的结束位置；无定时歌词时为非正值
     * @return 计划；不在房间模式、预备的不是已登记的那一首、或全房此刻还拿不出计划时返回
     *         {@code null} —— 后两种都由调用方决定「不交叠」，而不是在这里编一条假曲线出来
     */
    static AutomixPlan planWith(AudioPlayer outgoing, AudioPlayer incoming, Music next,
                                long lastVocalEndMillis) {
        if (!handles(next)) return null;
        if (outgoing == null || !outgoing.isUsable()) return null;
        if (incoming == null || !incoming.isUsable()) return null;

        // ① 服务端已经把交接计划广播过来了：全房用同一份，本机不再另算一遍。
        AutomixPlan shared = sharedPlan;
        if (shared != null) return shared;

        // ② 还没有计划，而本机就是被指派的那一台（老服务端则是房主）：按本机选的引擎把真正的
        //    分析跑出来，并交给服务端广播。**分析权不再跟房主身份绑定** —— 服务端可能把这件事
        //    派给点歌人，也可能派给房间里任意一台装了模型的机器。
        if (analyzingHere()) {
            // ⓪ 先查「之前算过的」缓存。同一对曲目 + 同一套设置 + 同一个歌词边界算出来的计划
            //    就是同一份，所以复用不会改变结果，只是省掉一次真分析（Folia 那条路里是一个
            //    Python 的 stem 分离进程）。这仍然是「一个生产者」：只有被指派的那一台会走到
            //    这里，其余机器照旧等广播 —— 换成「谁有缓存谁就用」，几台引擎不同的机器就会
            //    各自执行一条不同的曲线，那正是这次改动要消灭的东西。
            AutomixPlan cached = AutomixPlanCache.load(outgoing, incoming, lastVocalEndMillis);
            if (cached != null) {
                sharedPlan = cached;
                sharedPlanKey = keyOf(cached);
                System.out.println("[Automix] 房间交接计划（复用之前算过的缓存）· " + cached.summary);
                publish(armedEntryId, encode(cached));
                return cached;
            }

            AutomixPlan planned = AutomixPlanner.planForRoom(outgoing, incoming, lastVocalEndMillis);
            if (planned != null) {
                JsonObject encoded = encode(planned);
                sharedPlan = planned;
                sharedPlanKey = keyOf(planned);
                // 落盘：这一对曲目下次再交接（列表循环、别的房间也放这两首）就直接命中。
                AutomixPlanCache.store(outgoing, incoming, lastVocalEndMillis, planned);
                System.out.println("[Automix] 房间交接计划（本机被指派分析）· " + planned.summary);
                publish(armedEntryId, encoded);
                return planned;
            }
            // 本机算不出：说一声让服务端当场改派下一台，而不是干等到超时。
            // 这一条对「借来的电脑」很实在 —— 房里可能还有另一台装了模型的机器能算出来。
            System.out.println("[Automix] 房间交接计划：本机被指派但这次算不出交接点"
                    + "（曲目太短或音频不可读），请服务端改派");
            decline(armedEntryId);
        }

        // ③ 拿不出共享计划：不交叠。服务端到点换歌，本机跟着换。
        return null;
    }

    /** 告诉服务端「本机放弃这次分析」，请它立刻改派下一台。 */
    private static void decline(long entryId) {
        PlanSink target = sink;
        if (target == null || entryId <= 0L) return;
        try {
            target.decline(entryId);
        } catch (Throwable failure) {
            // 拒绝发不出去只是白等一个超时，绝不能把预备线程带倒。
            System.err.println("[Automix] 放弃分析的通知发送失败：" + failure);
        }
    }

    private static void publish(long entryId, JsonObject plan) {
        PlanSink target = sink;
        if (target == null || entryId <= 0L || plan == null) {
            System.out.println("[Automix] 房间交接计划未能上报（entryId=" + entryId
                    + "，上报通道" + (target == null ? "未注入" : "已注入") + "）");
            return;
        }
        try {
            target.publish(entryId, plan);
        } catch (Throwable failure) {
            // 上报失败只是这一首退回「等计划 / 不交叠」，绝不能让预备线程跟着倒掉。
            System.err.println("[Automix] 房间交接计划上报失败：" + failure);
        }
    }

    /**
     * 把计划压成协议字段。
     *
     * <p>带<b>全字段</b>，不只是内置信号处理会填的那几项：房主可能用 Folia，而 Folia 的过渡族
     * （{@code style}）、频段倾斜、echo、由 stem 得出的鼓/低音/人声交接分数、过渡动画周期都会
     * 左右听感 —— 缺一项，执行端算出来的曲线就和房主选的那条不是同一条。
     * 这些字段在内置信号处理下恒为默认值，多占的那几十字节换的是「全房同一条母带」。</p>
     */
    static JsonObject encode(AutomixPlan plan) {
        JsonObject o = new JsonObject();
        o.addProperty("fireMillis", plan.fireMillis);
        o.addProperty("overlapMillis", plan.overlapMillis);
        o.addProperty("cueMillis", plan.incomingCueMillis);
        o.addProperty("matchGain", plan.matchGain);
        o.addProperty("barAligned", plan.barAligned);
        o.addProperty("bassSwap", plan.bassSwap);
        o.addProperty("tempoRatio", plan.tempoRatio);
        o.addProperty("pitchShift", plan.pitchShift);
        o.addProperty("phaseErrorMillis", plan.phaseErrorMillis);
        o.addProperty("vocalDuckDepth", plan.vocalDuckDepth);
        o.addProperty("summary", plan.summary);
        // 以下为 Folia 策略层；内置信号处理下 style 为 null、其余为默认值，
        // 解出来仍是同一条曲线。
        o.addProperty("style", plan.style == null ? null : plan.style.id);
        o.addProperty("midTiltDb", plan.midTiltDb);
        o.addProperty("highTiltDb", plan.highTiltDb);
        o.addProperty("echoThrow", plan.echoThrow);
        o.addProperty("drumHandoverAt", plan.drumHandoverAt);
        o.addProperty("bassHandoverAt", plan.bassHandoverAt);
        o.addProperty("vocalEntryAt", plan.vocalEntryAt);
        o.addProperty("stemDriven", plan.stemDriven);
        o.addProperty("animationPeriodMillis", plan.animationPeriodMillis);
        return o;
    }

    /**
     * 还原一份共享计划；字段缺失、明显不合理或根本不是计划时返回 {@code null}（调用方回落硬切）。
     *
     * <p>夹取是必需的：这份数据来自网络，而它的两个位置量会被直接喂给淡化器 ——
     * 一个越界的 {@code fireMillis} 会让淡出根本等不到，一个过长的人声闪避深度会把新曲压成静音。
     * 单标的越界就地夹回（其余字段仍可用），只有两个位置量坏掉才整份丢弃。</p>
     */
    static AutomixPlan decode(JsonObject o) {
        if (o == null) return null;
        try {
            long fire = number(o, "fireMillis", -1L);
            long overlap = number(o, "overlapMillis", 0L);
            if (fire <= 0L || fire > MAX_PLAUSIBLE_FIRE_MILLIS) return null;
            if (overlap < MIN_LEAD_MILLIS || overlap > MAX_OVERLAP_MILLIS) return null;

            long cue = number(o, "cueMillis", 0L);
            if (cue < 0L) cue = 0L;

            float gain = (float) decimal(o, "matchGain", 1.0);
            if (gain < .1f || gain > 4f) gain = 1f;

            float ratio = (float) decimal(o, "tempoRatio", 1.0);
            if (ratio < .5f || ratio > 2f) ratio = 1f;

            float duck = (float) decimal(o, "vocalDuckDepth", 0.0);
            if (duck < 0f || duck > 1f) duck = 0f;

            return new AutomixPlan(fire, overlap, cue, gain,
                    bool(o, "barAligned", false), bool(o, "bassSwap", false),
                    ratio, bool(o, "pitchShift", false),
                    number(o, "phaseErrorMillis", 0L), duck,
                    text(o, "summary", "room shared plan"),
                    style(o, "style"),
                    (float) decimal(o, "midTiltDb", 0.0), (float) decimal(o, "highTiltDb", 0.0),
                    bool(o, "echoThrow", false),
                    decimal(o, "drumHandoverAt", .42), decimal(o, "bassHandoverAt", .42),
                    decimal(o, "vocalEntryAt", .45), bool(o, "stemDriven", false),
                    decimal(o, "animationPeriodMillis", 500.0));
        } catch (Throwable malformed) {
            return null;
        }
    }

    /**
     * 把计划折成一个可比对的指纹。
     *
     * <p>只用于「还是不是同一份计划」的判断，所以只要<b>单调</b>且对同一份计划稳定即可；
     * 逐字段拼串比写 {@code equals} 短，也不会漏掉以后新加的字段（改到这份计划就顺手看一眼这里）。</p>
     */
    private static String keyOf(AutomixPlan plan) {
        if (plan == null) return "";
        return plan.fireMillis + "/" + plan.overlapMillis + "/" + plan.incomingCueMillis
                + "/" + plan.matchGain + "/" + plan.barAligned + "/" + plan.bassSwap
                + "/" + plan.tempoRatio + "/" + plan.pitchShift + "/" + plan.phaseErrorMillis
                + "/" + plan.vocalDuckDepth
                + "/" + (plan.style == null ? "-" : plan.style.id)
                + "/" + plan.midTiltDb + "/" + plan.highTiltDb + "/" + plan.echoThrow
                + "/" + plan.drumHandoverAt + "/" + plan.bassHandoverAt + "/" + plan.vocalEntryAt
                + "/" + plan.stemDriven + "/" + plan.animationPeriodMillis;
    }

    /** 按过渡族的 id 还原；未知 id 或没有该字段时返回 {@code null}（回到普通等功率曲线）。 */
    private static FoliaTransitionStyle style(JsonObject o, String key) {
        String id = text(o, key, "");
        if (id.isEmpty()) return null;
        for (FoliaTransitionStyle candidate : FoliaTransitionStyle.values()) {
            if (candidate.id.equals(id)) return candidate;
        }
        return null;
    }

    private static long number(JsonObject o, String key, long fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        return o.get(key).getAsLong();
    }

    private static double decimal(JsonObject o, String key, double fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        return o.get(key).getAsDouble();
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        return o.get(key).getAsBoolean();
    }

    private static String text(JsonObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        String value = o.get(key).getAsString();
        return value == null || value.isEmpty() ? fallback : value;
    }
}
