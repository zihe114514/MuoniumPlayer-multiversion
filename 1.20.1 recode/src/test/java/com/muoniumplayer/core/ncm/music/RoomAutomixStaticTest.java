package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一起听房间内 automix 的回归测试。
 *
 * <p>分两半。一半是<b>数值</b>：交接计划的编解码必须无损（含 Folia 那几个会左右听感的字段）、
 * 明显不合理的数据必须被挡掉、未知过渡族要退回普通等功率曲线。另一半是<b>接线</b>：静态扫源码，
 * 钉住「房主按自己选的引擎真分析并上报」「全房执行服务端广播的同一份计划」「非房主等计划而不是
 * 自己编一条」「换歌帧走交叠而不是重新起播」这几条容易在后续改动中被悄悄拆掉的数据流。</p>
 *
 * <p>不启动 Minecraft、音频设备或网络。</p>
 */
class RoomAutomixStaticTest {

    private static final Path CORE = ProjectPaths.sharedJava()
            .resolve(Paths_("com/muoniumplayer/core/ncm/music"));
    private static final Path PARTY = ProjectPaths.versionRoot().getParent()
            .resolve("mpparty-common").resolve("src").resolve("main").resolve("java")
            .resolve(Paths_("com/muoniumplayer/party"));

    private static Path Paths_(String relative) {
        return Path.of(relative.replace('/', java.io.File.separatorChar));
    }

    @AfterEach
    void resetRoomAutomix() {
        RoomAutomix.setOwner(false);
        RoomAutomix.setAssigned(false, 0L);
        RoomAutomix.setNobodyCanAnalyze(false);
        RoomAutomix.setCapability(false, "");
        RoomAutomix.configure(0L);
        RoomAutomix.resetAssignmentSeen();
    }

    /** 内置信号处理算出来的计划：交接点不在曲末，入口点不为零，带着曲线参数。 */
    private static AutomixPlan classicPlan() {
        return new AutomixPlan(178_400L, 9_700L, 2_150L, 1.12f, true, true,
                .965f, false, 43L, .62f, "outro vocal tail 9700ms / cue 2150ms");
    }

    /** Folia 算出来的计划：除曲线参数外还带过渡族、频段倾斜、stem 交接分数与动画周期。 */
    private static AutomixPlan foliaPlan() {
        return new AutomixPlan(171_200L, 12_400L, 3_600L, .88f, true, true,
                .97f, false, 61L, .74f, "bassSwap 12.40s overlap, locked keys, locked, fire 171200ms cue 3600ms, bass swap, vocal hold, stem timing",
                FoliaTransitionStyle.BASS_SWAP, -1.7f, .55f, false,
                .31, .44, .52, true, 633.4);
    }

    /** 按若干个渐变位置采样一条计划的全部曲线，用于比对「执行端算出来的是不是同一条」。 */
    private static String curveOf(AutomixPlan plan) {
        StringBuilder out = new StringBuilder();
        for (double p : new double[]{0.0, .1, .25, .5, .75, .9, 1.0}) {
            out.append(p).append(':')
                    .append(plan.outgoingGain(p)).append(',')
                    .append(plan.incomingGain(p)).append(',')
                    .append(plan.outgoingBassDb(p)).append(',')
                    .append(plan.incomingBassDb(p)).append(',')
                    .append(plan.incomingVocalDepth(p)).append(',')
                    .append(plan.incomingMidDb(p)).append(',')
                    .append(plan.incomingHighDb(p)).append(',')
                    .append(plan.outgoingRate(p)).append(',')
                    .append(plan.outgoingTempo(p, plan.overlapMillis)).append(',')
                    .append(plan.outgoingPitch(p, plan.overlapMillis)).append(';');
        }
        return out.toString();
    }

    // ── 数值：共享计划的编解码 ──────────────────────────────────────────────

    @Test
    @DisplayName("内置信号处理的计划经协议往返后逐字段不变")
    void classicPlanSurvivesTheRoundTrip() {
        AutomixPlan original = classicPlan();
        AutomixPlan restored = RoomAutomix.decode(RoomAutomix.encode(original));

        assertNotNull(restored, "自己编出来的计划必须能被自己解开");
        assertEquals(original.fireMillis, restored.fireMillis, "淡出点必须原样送达");
        assertEquals(original.overlapMillis, restored.overlapMillis, "交叠长度必须原样送达");
        assertEquals(original.incomingCueMillis, restored.incomingCueMillis,
                "新曲入口点必须原样送达：它决定全房从哪一秒开始唱");
        assertEquals(original.matchGain, restored.matchGain, 1e-6, "响度匹配必须原样送达");
        assertEquals(original.tempoRatio, restored.tempoRatio, 1e-6, "速度锁定必须原样送达");
        assertEquals(original.phaseErrorMillis, restored.phaseErrorMillis, "相位缓冲必须原样送达");
        assertEquals(original.vocalDuckDepth, restored.vocalDuckDepth, 1e-6, "人声闪避深度必须原样送达");
        assertEquals(original.bassSwap, restored.bassSwap, "低频交换开关必须原样送达");
        assertEquals(original.barAligned, restored.barAligned, "小节对齐开关必须原样送达");
        assertEquals(original.pitchShift, restored.pitchShift, "移调开关必须原样送达");
        assertEquals(original.summary, restored.summary, "摘要要留着，日志与排障都靠它");
        assertNull(restored.style, "内置信号处理没有过渡族：它不该凭空多出一个");
        assertEquals(curveOf(original), curveOf(restored), "解出来必须是同一条曲线");

        // 这就是「房间里的 automix 真的看了音频内容」的判据：交接点与入口点都由内容决定，
        // 而不是由「距曲末固定多少毫秒」推出来。
        assertTrue(restored.incomingCueMillis > 0L, "跳前奏必须真的落到计划里");
        assertTrue(restored.vocalDuckDepth > 0f, "人声闪避必须真的落到计划里");
    }

    @Test
    @DisplayName("Folia 计划带上过渡族与 stem 交接分数后仍然无损，且执行端算出同一条曲线")
    void foliaPlanSurvivesTheRoundTrip() {
        AutomixPlan original = foliaPlan();
        AutomixPlan restored = RoomAutomix.decode(RoomAutomix.encode(original));

        assertNotNull(restored, "Folia 计划也要能原样送达");
        assertEquals(FoliaTransitionStyle.BASS_SWAP, restored.style,
                "过渡族决定平台期曲线与速率上下限，丢了就不是同一条母带");
        assertEquals(original.midTiltDb, restored.midTiltDb, 1e-6, "中频补偿要送达");
        assertEquals(original.highTiltDb, restored.highTiltDb, 1e-6, "高频补偿要送达");
        assertEquals(original.echoThrow, restored.echoThrow, "echo 开关要送达");
        assertEquals(original.drumHandoverAt, restored.drumHandoverAt, 1e-9,
                "由 stem 得出的鼓交接位置必须送达：它把低频交换挪到真正的鼓点上");
        assertEquals(original.bassHandoverAt, restored.bassHandoverAt, 1e-9, "低音交接位置要送达");
        assertEquals(original.vocalEntryAt, restored.vocalEntryAt, 1e-9, "人声入口位置要送达");
        assertTrue(restored.stemDriven, "stem 驱动标记丢了，曲线就会退回「按渐变比例」那一套");
        assertEquals(original.animationPeriodMillis, restored.animationPeriodMillis, 1e-9,
                "过渡动画的拍周期要送达，否则各机闪得节奏都不一样");

        assertEquals(curveOf(original), curveOf(restored),
                "全房执行的必须是房主算出来的那一条曲线，而不是各机按同一个数各自发挥的");
    }

    @Test
    @DisplayName("明显不可用的计划必须被挡掉，而不是喂给淡化器")
    void decodeRejectsUnusablePlans() {
        assertNull(RoomAutomix.decode(null), "没有计划就是没有计划");

        assertNull(RoomAutomix.decode(new JsonObject()), "空对象不是计划");

        AutomixPlan headless = foliaPlan();
        JsonObject noFire = RoomAutomix.encode(headless);
        noFire.addProperty("fireMillis", 0L);
        assertNull(RoomAutomix.decode(noFire), "淡出点为 0 会让淡出根本等不到");

        JsonObject beyondTrack = RoomAutomix.encode(headless);
        beyondTrack.addProperty("fireMillis", 40L * 60_000L);
        assertNull(RoomAutomix.decode(beyondTrack), "淡出点超出任何一首歌的长度，只可能是算错了");

        JsonObject tinyOverlap = RoomAutomix.encode(headless);
        tinyOverlap.addProperty("overlapMillis", 5L);
        assertNull(RoomAutomix.decode(tinyOverlap), "几毫秒的「交叠」不算交叠，不交叠更安全");

        JsonObject absurdOverlap = RoomAutomix.encode(headless);
        absurdOverlap.addProperty("overlapMillis", 900_000L);
        assertNull(RoomAutomix.decode(absurdOverlap), "交叠长到能吃掉整首歌时必须拒绝");

        // 单个标量越界就地夹取，不必整份丢掉：其余字段仍然可用。
        JsonObject negativeCue = RoomAutomix.encode(headless);
        negativeCue.addProperty("cueMillis", -5_000L);
        AutomixPlan clamped = RoomAutomix.decode(negativeCue);
        assertNotNull(clamped);
        assertEquals(0L, clamped.incomingCueMillis, "负的入口点要夹回 0");

        JsonObject wildGain = RoomAutomix.encode(headless);
        wildGain.addProperty("matchGain", 99f);
        AutomixPlan fixed = RoomAutomix.decode(wildGain);
        assertNotNull(fixed);
        assertEquals(1f, fixed.matchGain, 1e-6, "离谱的响度匹配要退回单位增益");

        JsonObject wildDuck = RoomAutomix.encode(headless);
        wildDuck.addProperty("vocalDuckDepth", 8f);
        AutomixPlan quiet = RoomAutomix.decode(wildDuck);
        assertNotNull(quiet);
        assertEquals(0f, quiet.vocalDuckDepth, 1e-6, "越界的人声闪避深度会把新曲压成静音，要退回不闪避");
    }

    @Test
    @DisplayName("认不出的过渡族退回普通等功率曲线，而不是把整份计划丢掉")
    void unknownTransitionStyleFallsBackToThePlainCurve() {
        // 一条「只有位置、没有曲线处理」的计划：响度匹配归 1，所以两条增益必须严格等功率。
        AutomixPlan plain = new AutomixPlan(178_400L, 9_700L, 2_150L, 1f, true, false,
                1f, false, 0L, 0f, "plain");
        JsonObject wire = RoomAutomix.encode(plain);
        wire.addProperty("style", "someFutureStyle");

        AutomixPlan restored = RoomAutomix.decode(wire);
        assertNotNull(restored, "过渡族认不出来不该让整份计划作废：交接点仍然可用");
        assertNull(restored.style, "认不出的过渡族按「没有过渡族」处理");
        assertEqualPower(restored, "认不出过渡族时必须退回等功率曲线");

        // 没有 style 字段（旧版房主）走同一条路。
        JsonObject missing = RoomAutomix.encode(plain);
        AutomixPlan legacy = RoomAutomix.decode(missing);
        assertNotNull(legacy);
        assertNull(legacy.style, "旧版房主不发 style 时同样按没有处理");
        assertEqualPower(legacy, "没有过渡族时必须是等功率曲线");

        // 反过来：认得出的过渡族必须真的生效，否则「过渡族」这个字段就是白带的。
        AutomixPlan folia = RoomAutomix.decode(RoomAutomix.encode(foliaPlan()));
        assertNotNull(folia);
        assertEquals(FoliaTransitionStyle.BASS_SWAP, folia.style);
        // 取渐变靠前处比对：那里普通曲线还在缓起（cos .1π/2 = .988），而带平台期的过渡族
        // 已经按 together 份额压下来了 —— 再加上 1.5dB 的混音余量，两者差得很远。
        assertTrue(Math.abs(folia.outgoingGain(.1) - Math.cos(.1 * Math.PI * .5)) > 1e-3,
                "过渡族带平台期时，渐变靠前处的增益不该还是普通等功率那一条："
                        + "全房共用一份计划的前提是这条曲线连同过渡族一起送达到位");
    }

    /** 两条增益的平方和恒为 1（仅在响度匹配归 1、无过渡族时成立）。 */
    private static void assertEqualPower(AutomixPlan plan, String message) {
        for (double progress : new double[]{0.0, .25, .5, .75, 1.0}) {
            double power = Math.pow(plan.outgoingGain(progress), 2)
                    + Math.pow(plan.incomingGain(progress), 2);
            assertEquals(1.0, power, 1e-6, message + "（progress=" + progress + "）");
        }
    }

    @Test
    @DisplayName("没人登记过计划时，任何一份计划都不能被当成「已收到的同一份」")
    void anUnregisteredRoomKnowsNoPlan() {
        JsonObject plan = RoomAutomix.encode(foliaPlan());
        assertFalse(RoomAutomix.sameSharedPlan(plan),
                "还没登记过任何计划时不能自称认识它：否则队列补装会短路，本机永远等不到计划");
        assertFalse(RoomAutomix.armed(), "没有登记过就没有已预备的交接点");
    }

    @Test
    @DisplayName("提前量为 0 时房间模式整体关闭")
    void zeroLeadTurnsTheRoomOff() {
        assertTrue(AutomixSettings.isEnabled(), "该用例假定本机没关掉无缝切换");

        RoomAutomix.configure(0L);
        assertFalse(RoomAutomix.active(), "老服务端 / 服主没开时必须是不激活");

        RoomAutomix.configure(4_000L);
        assertTrue(RoomAutomix.active(), "服务端下发提前量之后才生效");

        assertFalse(RoomAutomix.awaitingPlan(null), "没有下一首时不在等计划");
    }

    // ── 接线：分析由被指派的那一台跑，结果全房共用 ───────────────────────────

    @Test
    @DisplayName("被指派者按自己选的引擎真分析并上报，其余机器只执行共享计划")
    void onlyTheAssignedMachineAnalysesAndSharesTheResult() throws IOException {
        String room = read(CORE.resolve("RoomAutomix.java"));
        String planner = read(CORE.resolve("AutomixPlanner.java"));
        String cloud = read(CORE.resolve("CloudMusic.java"));

        // ① 房间这条路走的是「那台机器自己选的引擎」，不是某个钉死的算法。
        assertTrue(room.contains("AutomixPlanner.planForRoom(outgoing, incoming, lastVocalEndMillis)"),
                "必须用真正的分析算交接点，而不是按曲末减一个固定值");
        assertFalse(room.contains("planClassic(outgoing, incoming, lastVocalEndMillis)"),
                "房间不该把引擎钉死在内置信号处理上：那台开着 Folia 就该让 Folia 算");
        assertTrue(planner.contains("static AutomixPlan planForRoom(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis) {"),
                "缺少房间入口");
        assertTrue(planner.contains("AutomixPlan folia = FoliaAutomixPlanner.plan(outgoing, incoming, lastVocalEndMillis);"),
                "选了 Folia 就要真的跑 Folia 推理");
        assertTrue(planner.contains("return planClassic(outgoing, incoming, lastVocalEndMillis);"),
                "Folia 给不出结果时必须降级到内置信号处理，而不是直接不交叠");

        // ② 共享计划优先级最高：服务端广播过来了就用它，本机不再另算一份。
        assertTrue(room.contains("AutomixPlan shared = sharedPlan;"),
                "必须先看服务端广播过来的那份计划");
        assertTrue(room.contains("if (shared != null) return shared;"),
                "共享计划存在时本机绝不能再算一份：两份不同的计划会让交叠窗里位置打架");

        // ③ 谁来算由**指派**决定，不再跟房主身份绑定 —— 这是「谁点的歌就用谁的电脑先算」。
        assertTrue(room.contains("if (analyzingHere()) {"),
                "分析必须由「本机是否被指派」决定，而不是「本机是不是房主」");
        assertFalse(room.contains("if (analyzer) {"),
                "房主独占分析的写法必须消失：算力落在谁手上跟房主身份无关");
        assertTrue(room.contains("public static boolean analyzingHere() {"));
        assertTrue(room.contains("if (assignedHere) return true;"),
                "被指派就是分析者");
        assertTrue(room.contains("return owner && !assignmentSeen;"),
                "老服务端不会指派任何人，那时才退回「房主自己算」的旧行为");
        assertTrue(room.contains("publish(armedEntryId, encoded);"),
                "算完必须上报，否则全房拿不到计划");
        assertTrue(room.contains("void publish(long entryId, JsonObject plan);"),
                "上报要带上条目号，服务端才知道这份计划是给哪一首的");
        assertTrue(room.contains("void decline(long entryId);"),
                "算不出来必须能说一声：否则每问到一台不行的机器都要白等一个超时");
        assertTrue(room.contains("decline(armedEntryId);"),
                "分析失败时要真的把「我算不了」发出去，让服务端当场改派下一台");

        // ④ 没有任何「不跑音频分析的固定淡出」：拿不到计划就是不交叠。
        assertFalse(room.contains("planFor("),
                "按曲末减固定值的假交叠已作废：它不读音频，只会让两台机器叠出两条不同波形");
        assertTrue(room.contains("// ③ 拿不出共享计划：不交叠。服务端到点换歌，本机跟着换。"),
                "拿不到计划时的出路必须写清楚是「不交叠」");

        // ⑤ 没被指派的机器不自己编计划，等那一份。
        assertTrue(room.contains("public static boolean awaitingPlan(Music next) {"),
                "缺少「房间在管但计划没到」的判定");
        assertTrue(room.contains("if (nobodyCanAnalyze) return false;"),
                "服务端已经说过没人能算时不许再挂着等：那是个永远等不到的计划");
        assertTrue(room.contains("return !analyzingHere();"),
                "自己被指派时不等（直接去算）；没被指派时才等");
        assertTrue(cloud.contains("if (RoomAutomix.awaitingPlan(nextSong)) return;"),
                "预备链路必须在计划没到时先不动手，否则白跑一次最重的后台任务");

        // ⑥ 预备链路先问房间，房间给不出计划才回落本机分析链路。
        assertTrue(cloud.contains("boolean roomOwns = RoomAutomix.handles(song);"),
                "必须区分「房间在管这一首」与「这一首不在房间里」");
        assertTrue(cloud.contains("track.plan = RoomAutomix.planWith(ownedPlayer, deck, song, lastVocalEnd);"),
                "预备下一首时必须先问房间要不要交叠");
        assertTrue(cloud.contains("if (track.plan == null && !roomOwns) {"),
                "房间在管时不得回落到本机自己的判据：那正是全房分叉的来源");
        assertTrue(cloud.contains("track.roomMode = roomOwns && track.plan != null;"),
                "房间计划必须被标记出来，否则换歌帧没法触发它");
        assertTrue(cloud.contains("long lastVocalEnd = estimateLastVocalEndMillis(ownedPlayer);"),
                "人声尾只算一次，两条链路共用");
    }

    @Test
    @DisplayName("指派状态：被点名才分析，撤回后回到「不交叠」而不是自己编一条")
    void assignmentDecidesWhoAnalyses() {
        RoomAutomix.configure(4_000L);
        assertTrue(RoomAutomix.active(), "测试前提：房间开着交叠");

        // 没被指派、也不是房主（新服务端已经在指派别人）：本机不该动手。
        RoomAutomix.setCapability(true, "model");
        RoomAutomix.setAssigned(false, 0L);
        assertFalse(RoomAutomix.analyzingHere(), "没被点名就不许算：全房只能有一个生产者");

        // 服务端点名让本机算。
        RoomAutomix.setAssigned(true, 7L);
        assertTrue(RoomAutomix.assignedToUs(), "被点名之后本机就是分析者");
        assertTrue(RoomAutomix.analyzingHere());

        // 服务端说「这一首交给别人了」：立刻交还，不能两份计划打架。
        RoomAutomix.setAssigned(false, 7L);
        assertFalse(RoomAutomix.assignedToUs(), "被撤回后不再是分析者");
        assertFalse(RoomAutomix.analyzingHere());
    }

    @Test
    @DisplayName("老服务端兜底：房主自己算，且断开重连之后这条兜底不能被永久锁死")
    void ownerFallbackSurvivesReconnect() {
        RoomAutomix.configure(4_000L);
        RoomAutomix.setCapability(true, "model");

        // 老服务端从不指派：本机是房主就该自己算（此刻还没见过任何指派）。
        RoomAutomix.resetAssignmentSeen();
        RoomAutomix.setOwner(true);
        assertTrue(RoomAutomix.analyzingHere(),
                "老服务端不指派任何一台，房主必须自己算，否则整房不交叠");

        // 断开时 onDisconnect 会调 setAssigned(false, 0L)，它顺手把「已见过指派」锁成真。
        RoomAutomix.setAssigned(false, 0L);
        assertFalse(RoomAutomix.analyzingHere(),
                "测试前提：一旦见过指派，房主兜底就该退场");

        // 重连（hello 复位）之后仍可能是老服务端，房主兜底必须重新生效。
        RoomAutomix.resetAssignmentSeen();
        assertTrue(RoomAutomix.analyzingHere(),
                "重连之后房主兜底不能被上一条连接的指派记录永久锁死");

        RoomAutomix.setOwner(false);
    }

    @Test
    @DisplayName("服务端说「没人能算」时不再挂着等计划，直接走不交叠")
    void nobodyCanAnalyzeStopsTheWaiting() {
        RoomAutomix.configure(4_000L);
        RoomAutomix.setCapability(true, "model");
        RoomAutomix.setAssigned(false, 0L);
        assertFalse(RoomAutomix.analyzingHere(), "测试前提：本机不是分析者");

        // 还没结论时本机在等（等别人算完广播过来）。
        assertFalse(RoomAutomix.awaitingPlan(null), "没有下一首时不在等");
        RoomAutomix.setNobodyCanAnalyze(true);
        // 服务端已经明说没人能算，这个计划永远等不到 —— 不许再把预备挂在那里。
        assertFalse(RoomAutomix.awaitingPlan(null));

        // 一旦本机被指派，这个标记必须被清掉（否则会误伤下一首）。
        RoomAutomix.setAssigned(true, 9L);
        assertTrue(RoomAutomix.analyzingHere());
    }

    @Test
    @DisplayName("本机分析能力要自报给服务端，服务端才知道该不该把任务派过来")
    void theMachineReportsItsOwnCapability() throws IOException {
        String playback = read(PARTY.resolve("MpPlayback.java"));
        String client = read(PARTY.resolve("MpPartyClient.java"));

        assertTrue(playback.contains("RoomAutomix.setCapability(AutomixSettings.isEnabled(), capabilityEngine());"),
                "能力位要随角色刷新一起上报");
        assertTrue(playback.contains("boolean beats = FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS);"));
        assertTrue(playback.contains("boolean stems = FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS);"),
                "两套权重缺一不可：只报了 Folia 却没装模型，算出来的和内置信号处理没区别");
        assertTrue(playback.contains("return beats && stems && runtime ? \"model\" : \"dsp\";"),
                "没装模型就要老实报 lowest 档，否则服务端会优先把活派给一台其实给不出东西的机器");
        assertTrue(playback.contains("RoomAutomix.setOwner(MpPartyClient.isOwner());"),
                "房主身份仍要同步：老服务端靠它兜底");

        assertTrue(client.contains("m.addProperty(\"automixCapable\", RoomAutomix.capable());"),
                "能力位必须随 profile 上传");
        assertTrue(client.contains("m.addProperty(\"automixEngine\", RoomAutomix.engine());"),
                "档位要一起报，服务端据此在同优先级里先问装了模型的");
    }

    @Test
    @DisplayName("本机队列要真的为房间长出「下一首」")
    void theLocalQueueGrowsForTheRoomNextTrack() throws IOException {
        String cloud = read(CORE.resolve("CloudMusic.java"));

        assertTrue(cloud.contains("public static boolean setRoomNextTrack(Music next) {"),
                "缺少把房间下一首接进队列的入口");
        assertTrue(cloud.contains("while (queue.size() > curIdx + 1) {"),
                "多出来的格子必须先收回：房间的下一首会随着服务端广播变化");
        assertTrue(cloud.contains("if (next != null && next == queue.get(curIdx)) return false;"),
                "「下一首」就是正在播的这一首时不该动队列");
        assertTrue(cloud.contains("if (queue.size() == curIdx + 2 && queue.get(curIdx + 1) == next) return false;"),
                "队列已经正好是这一首时必须原样返回：补装是每秒一次的，递增修订号会把"
                        + "刚预备好的播放牌一次次作废");
        assertTrue(cloud.contains("if (changed) queueRevision++;"),
                "接上/撤下都必须递增修订号，否则 automix 一直按旧预测预备");
    }

    @Test
    @DisplayName("换歌帧能直接触发交叠交接，而不许重新起播")
    void theRoomFrameTriggersTheHandover() throws IOException {
        String cloud = read(CORE.resolve("CloudMusic.java"));

        assertTrue(cloud.contains("public static boolean roomAutomixHandover() {"),
                "缺少房间换歌帧的入口");
        assertTrue(cloud.contains("if (!RoomAutomix.active()) return false;"),
                "房间没开交叠时这条捷径必须关着");
        assertTrue(cloud.contains("return ((PlayThread) active).requestRoomHandover();"),
                "交接只能在播放线程上发生，不能从网络线程直接换播放器");
        assertTrue(cloud.contains("if (track == null || track.plan == null || !track.roomMode) return false;"),
                "只有房间模式下预备出来的那一首才认这个请求");
        assertTrue(cloud.contains("boolean byRoomFrame = track.roomMode && roomHandoverRequested;"),
                "tick 里必须认下这个请求，而不是等本地位置推到交叠点");
        assertTrue(cloud.contains("if (byRoomFrame) roomHandoverRequested = false;"),
                "请求消费一次就要清掉");
        assertTrue(cloud.contains("roomHandoverRequested = false;"),
                "预备被取消时也必须清掉，别落到下一张播放牌上");
    }

    @Test
    @DisplayName("一起听侧：预告下一首、预解码、按帧交接，且失败一律回落硬切")
    void partySideStagesPreDecodesAndAdopts() throws IOException {
        String playback = read(PARTY.resolve("MpPlayback.java"));
        String client = read(PARTY.resolve("MpPartyClient.java"));

        // 收下预告 -> 构造曲目 -> 接进队列（预解码挂在播放器既有的 arm 链路上）。
        assertTrue(playback.contains("public static void stageNext(final MpTrack next, final JsonObject plan) {"));
        assertTrue(playback.contains("if (!RoomAutomix.active()) {"),
                "房间没开交叠时整个预置链路必须短路，行为与今天一致");
        assertTrue(playback.contains("music.setPartyStreamUrl(nextUrl);"),
                "下一首必须用房间共用的那条直链，而不是各自重新解析");
        assertTrue(playback.contains("&& RoomAutomix.sameSharedPlan(plan)) {"),
                "计划必须纳入重复判定：房主算完会单独广播一次计划，曲目和链接都没变，"
                        + "短路掉的话本机就永远等不到房主那份计划");
        assertTrue(playback.contains("RoomAutomix.arm(music, next.entryId, plan);"),
                "登记下一首时要连条目号和计划一起交出去");
        assertTrue(playback.contains("CloudMusic.setRoomNextTrack(music);"));
        assertTrue(playback.contains("installStagedRoomNext();"),
                "队列广播可能晚于换歌帧，那时房间这一首已经在播了，必须补装一次");

        // 起播时队列要带上预置的下一首，否则 peekNextIndex() 永远是 -1。
        assertTrue(playback.contains("CloudMusic.play(roomQueue(music), 0);"),
                "起播必须交给播放器一条「当前 + 下一首」的队列");
        assertTrue(playback.contains("private static List<Music> roomQueue(Music music) {"));
        assertFalse(playback.contains("先用兜底曲线"),
                "「先用兜底曲线」的说法已作废：计划没到时不预备，也不自己编一条");

        // 分析权由服务端指派决定，身份变化时刷新。
        assertTrue(playback.contains("public static void refreshRoomRole() {"));
        assertTrue(playback.contains("RoomAutomix.setOwner(MpPartyClient.isOwner());"),
                "房主身份仍要同步：老服务端不指派任何人，只能靠它兜底");

        // 换歌帧：本机先到就承认，帧先到就请播放线程交接；两者都失败才硬切。
        assertTrue(playback.contains("public static boolean handoverToRoomFrame(final MpTrack track, final long posMs, final boolean playing) {"));
        assertTrue(playback.contains("boolean blendedLocally = CloudMusic.currentlyPlaying == music;"));
        assertTrue(playback.contains("if (!blendedLocally && !CloudMusic.roomAutomixHandover()) return false;"),
                "本机没交接、播放线程也接不了时必须返回 false，让调用方走普通起播");
        assertTrue(playback.contains("MpSyncPolicy.markCommand();"),
                "交接期间本机与房间时钟故意不同，必须关掉漂移判定");

        // 上报通道：由一起听这层注入，核心播放层不反向依赖它。
        assertTrue(client.contains("RoomAutomix.setPlanSink(new RoomAutomix.PlanSink() {"),
                "计划要经由一起听这层发给服务端");
        assertTrue(client.contains("MpProto.msg(MpProto.C_AUTOMIX_PLAN)"));
        assertTrue(client.contains("m.addProperty(\"entryId\", entryId);"));
        assertTrue(client.contains("if (room == null || !RoomAutomix.assignedToUs()) return;"),
                "上报只看「本机是不是被指派者」，不再要求房主 —— 点歌人也可能是那一台");
        assertFalse(client.contains("if (room == null || !isOwner()) return;"),
                "上报不许再按房主校验：这正是这次改动要拆掉的那道门");
        assertTrue(client.contains("MpProto.msg(MpProto.C_ANALYZE_DECLINE)"),
                "算不出来时要能告诉服务端「改派下一台」");

        // 指派帧：服务端点名让本机算，以及通知全房「这一首由谁在算」。
        assertTrue(client.contains("RoomAutomix.setAssigned(true, entryId);"),
                "收到指派就要把自己登记成分析者");
        assertTrue(client.contains("RoomAutomix.setNobodyCanAnalyze(analyst == null || analyst.isEmpty());"),
                "服务端说没人能算时本机不该继续挂着等计划");
        assertTrue(client.contains("MpProto.S_ANALYZE_ASSIGN.equals(op)"));
        assertTrue(client.contains("MpProto.S_ANALYZE_STATE.equals(op)"));

        // 客户端帧处理：hello 读开关、队列与快照读下一首与计划、换歌走交接、离房清理。
        assertTrue(client.contains("RoomAutomix.configure(MpProto.num(msg, \"automixLeadMs\", 0L));"),
                "hello 的 automixLeadMs 就是开关，缺省必须是 0（老服务端 = 不交叠）");
        assertTrue(client.contains("RoomAutomix.resetAssignmentSeen();"),
                "hello 必须复位「已见过指派」：否则断开重连后老服务端的房主兜底被永久锁死");
        assertTrue(client.contains("MpPlayback.refreshRoomRole();"),
                "hello 与房间快照之后都要刷新分析权");
        assertTrue(client.contains("JsonObject staged = MpProto.obj(msg, \"next\");"),
                "S_QUEUE 要带上「下一首」");
        assertTrue(client.contains("MpPlayback.stageNext(MpTrack.fromJson(staged), MpProto.obj(staged, \"plan\"));"),
                "「下一首」里可能附带算好的计划，必须一起交给播放层");
        assertTrue(client.contains("JsonObject staged = MpProto.obj(MpProto.obj(msg, \"room\"), \"next\");"),
                "房间快照里也带着「下一首」，中途进房与重连的人也要能预置");
        assertTrue(client.contains("if (!selfPaused && MpPlayback.handoverToRoomFrame(track, pos, isPlaying)) {"),
                "换歌帧必须先问交叠链路，问不到才起播");
        assertTrue(client.contains("RoomAutomix.configure(0L);"),
                "换服务器必须清掉交叠开关，等新服务端的 hello 重新下发");
        assertTrue(client.contains("RoomAutomix.setAssigned(false, 0L);"),
                "断开之后本机不再是任何房间的被指派者");
        assertTrue(client.contains("MpPlayback.clearRoomAutomix();"),
                "离房必须把多出来的那一格收回，别让它跟着本机自己的队列播下去");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
