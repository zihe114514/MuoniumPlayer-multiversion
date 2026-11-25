package com.muoniumplayer.automix;

import com.muoniumplayer.testsupport.AutomixSources;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进阶模式必须是<b>纯增量</b>：关掉它，这三条新功能一次都不发生，而不是「发生了但被跳过」。
 *
 * <p>数值测试（{@code AutomixAutoCueTest}、{@code AutomixFlowPlannerTest}）证的是打开之后的
 * 行为；这一类证的是<b>没打开时什么都不变</b>——而后者才是敢把它发出去的理由。检查走源码而不是
 * 走运行时，因为「关掉后不扫整曲」这种性质在单测里跑不出真实播放路径。</p>
 */
class AutomixAdvancedModeStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("总开关出厂关闭，且读写都真的落到 automix.json")
    void switchDefaultsOffAndPersists() throws Exception {
        String settings = read(music("AutomixSettings.java"));
        assertTrue(settings.contains("private static boolean advancedMode = false;"),
                "进阶模式必须出厂关闭：默认开着就等于替所有用户改了交接行为");
        assertTrue(settings.contains("isAdvancedModeEnabled") && settings.contains("setAdvancedModeEnabled"));
        assertTrue(settings.contains("root.has(\"advancedMode\")"), "读盘要认这个键");
        assertTrue(settings.contains("root.addProperty(\"advancedMode\", advancedMode)"), "存盘要写这个键");
        // 与表现模式同一对模式：load() 再取锁，setter 相同值直接返回不写盘。
        assertTrue(settings.indexOf("public static boolean isAdvancedModeEnabled()") > 0);
    }

    @Test
    @DisplayName("默认 AutoCue 与额外进阶功能独立，队列排列仍受开关控制")
    void defaultPairSearchIsSeparateFromAdvancedFeatures() throws Exception {
        String settings = read(music("AutomixSettings.java"));
        assertTrue(settings.contains("private static boolean advancedMode = false;"));
        assertTrue(settings.contains("整曲包络配对选点属于普通交接策略"));

        String planner = read(music("FoliaAutomixPlanner.java"));
        assertTrue(planner.contains("AutomixAutoCue.Result selected = pairCue("),
                "普通 Folia 规划必须调用配对搜索");
        assertTrue(planner.contains("AutomixEnvelope outgoingEnvelope = AutomixEnvelope.load("),
                "搜索只读已预热包络");
        assertTrue(planner.contains("if (AutomixSettings.isAdvancedModeEnabled() && outgoingEnvelope != null"),
                "额外出场人声曲线仍受进阶开关保护");

        String cloud = read(music("CloudMusic.java"));
        String warm = between(cloud, "static void warmPairSearchEnvelope(AudioPlayer deck)", "public static boolean warmAutomixProfile");
        assertTrue(warm.contains("isFoliaEngine()") && warm.contains("PAIR_ENVELOPE_IN_FLIGHT"));
        assertFalse(warm.contains("isAdvancedModeEnabled()"));
        assertTrue(cloud.contains("private volatile boolean pairSearchEvidence = true;"));
        assertTrue(cloud.contains("if (track.pairSearchEvidence) {"), "缺证据的回退计划不可缓存");
        assertTrue(cloud.contains("if (track.replanned || track.roomMode) return;"),
                "房间共享计划不由本地补算");

        String panel = read(screens("ncm", "panels", "PlayQueuePanel.java"));
        assertTrue(panel.contains("isAdvancedModeEnabled()"), "队列排列仍受进阶开关控制");
    }

    @Test
    @DisplayName("规划路径只读缓存；扫描只留在后台包络预热")
    void planningNeverScans() throws Exception {
        String planner = read(music("FoliaAutomixPlanner.java"));
        assertFalse(planner.contains("AutomixEnvelope.ensure("), "规划路径不扫描音频");
        assertTrue(planner.contains("AutomixAutoCue.Result selected = pairCue("));

        String envelope = read(music("AutomixEnvelope.java"));
        assertTrue(envelope.contains("static AutomixEnvelope load(File source)"));
        assertTrue(envelope.contains("static boolean ensure(File source, long durationMillis)"));

        String cloud = read(music("CloudMusic.java"));
        assertTrue(cloud.contains("AutomixEnvelope.ensure(playable, (long) deck.getTotalTimeMillis())"));
        assertTrue(cloud.contains("static boolean warmPairSearchEnvelopeNow(File source, long durationMillis)"));
        assertTrue(cloud.contains("static void warmPairSearchEnvelope(AudioPlayer deck)"));
    }

    @Test
    @DisplayName("出场侧人声曲线默认恒为 0，旧构造器给的就是 0")
    void outgoingVocalCurveIsInertByDefault() throws Exception {
        String plan = read(music("AutomixPlan.java"));
        assertTrue(plan.contains("float outgoingVocalDepth(double progress)"));
        assertTrue(plan.contains("if (outgoingVocalDuckDepth <= 0f) return 0f;"),
                "深度为 0 要第一行就返回，让 VocalDuckFilter 走位精确的直通分支");
        // 委托构造器必须给 0f / 0.0：旧调用点（内置规划器、测试、房间旧版本）拿到的就是关闭态。
        String delegating = plan.substring(plan.indexOf("double animationPeriodMillis) {"));
        assertTrue(delegating.contains("stemDriven, animationPeriodMillis, 0f, 0.0)"),
                "20 参构造器要显式把两条新字段填成关闭态");
        // 包络证据不能盖过分轨证据：两条曲线共用同一个释放点字段，优先级要写死。
        assertTrue(plan.contains("stemDriven ? vocalEntryAt")
                && plan.contains("vocalReleaseAt > 0.0 ? vocalReleaseAt : VOCAL_DUCK_RELEASE_START"));
    }

    @Test
    @DisplayName("新字段上协议：全房必须执行同一条人声曲线")
    void sharedPlanCarriesTheNewFields() throws Exception {
        String room = read(music("RoomAutomix.java"));
        for (String field : new String[]{"outgoingVocalDuckDepth", "vocalReleaseAt"}) {
            assertTrue(room.contains("addProperty(\"" + field + "\""), "缺 " + field + " 的 encode：房主压了别人没压");
            assertTrue(room.contains("\"" + field + "\""), field + " 要在解码侧也出现");
        }
        assertTrue(room.contains("decimal(o, \"outgoingVocalDuckDepth\", 0.0)"),
                "旧版本发来的计划要按关闭态解，而不是丢整份");
        assertTrue(room.contains("plan.outgoingVocalDuckDepth + \"/\" + plan.vocalReleaseAt"),
                "指纹要含新字段，否则换了人声安排也看不出计划变了");

        String cache = read(music("AutomixPlanCache.java"));
        assertTrue(cache.contains("FORMAT_VERSION = 4"),
                "默认配对搜索接入后，旧纯启发式缓存必须失效");
    }

    @Test
    @DisplayName("播放侧两条曲线都要写：漏一条就是只压了进场方")
    void bothDecksReceiveTheirVocalDepth() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        assertTrue(cloud.contains("fade.outgoing.applyOutgoingAutomixFrame"),
                "出场方要走带人声深度的那条写入");
        assertTrue(cloud.contains("fade.plan.outgoingVocalDepth(progress)"));
        String player = read(music("AudioPlayer.java"));
        assertTrue(player.contains("void applyOutgoingAutomixFrame(float fade, float bassDb, float vocalDepth,"),
                "出场方的重载要接得住深度；同名五参重载会与进场方那条撞车");
    }

    @Test
    @DisplayName("进阶模式是独立开关，不与处理方案（引擎）绑定")
    void modelExposesTheSwitch() throws Exception {
        String model = read(screens("hud", "HudSettingsModel.java"));
        assertTrue(model.contains("choice(\"处理方案\""),
                "模型驱动的那套（内置编辑器与 OneConfig 桥）要有处理方案条目");
        assertTrue(model.contains("HudSettingsModel::setEngineChoice"),
                "处理方案只管引擎选择（下拉直写 setter）");
        assertTrue(model.contains("toggle(\"进阶模式\""),
                "进阶模式必须是独立 toggle：分析深度与引擎选择是两个正交维度，不该绑死");
        int toggleRow = model.indexOf("toggle(\"进阶模式\"");
        assertTrue(model.indexOf("AutomixSettings.setAdvancedModeEnabled(!AutomixSettings.isAdvancedModeEnabled())", toggleRow) - toggleRow < 600,
                "进阶 toggle 要直接驱动它自己的开关，不经任何档位映射");
    }

    @Test
    @DisplayName("退场点只能往未来找：规划时把播放头传给搜索")
    void firePointIsClampedToTheFuture() throws Exception {
        String planner = read(music("FoliaAutomixPlanner.java"));
        assertTrue(planner.contains("MIN_PAIR_SEARCH_FIRE_LEAD_MILLIS"),
                "配对搜索要留出播放头安全余量");
        assertTrue(planner.contains("request.minimumFireMillis"),
                "播放头要传进搜索；不传就会选出一个已经播过去的时间点，交接当场开始");
        assertTrue(planner.contains("result.fireMillis < request.minimumFireMillis"),
                "采纳前还要再判一次——计划会被缓存复用，复用时的播放头更靠后");
    }

    @Test
    @DisplayName("配对预热在规划前确保进场证据；普通 Folia 可后台补齐出场证据")
    void incomingEnvelopeIsReadyBeforePlanning() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        int asyncWarm = cloud.indexOf("warmPairSearchEnvelope(deck);");
        int plan = cloud.indexOf("RoomAutomix.planWith(ownedPlayer, deck, song, lastVocalEnd, track.pairSearchEvidence)");
        assertTrue(asyncWarm >= 0 && plan > asyncWarm, "普通 Folia 进场证据安排异步预热");
        int roomWarm = cloud.indexOf("incomingEnvelope = warmPairSearchEnvelopeNow(file, decodedMillis)");
        assertTrue(roomWarm >= 0 && plan > roomWarm, "房间权威分析端同步确保证据后规划");
        String tick = between(cloud, "private void maybeReplanArmedTrack", "private void requestReplan");
        assertTrue(tick.contains("AutomixEnvelope.isPresent(track.file)"));
        assertTrue(tick.contains("AutomixEnvelope.isPresent(activePlayer.getSourceFile())"));
        String compute = between(cloud, "private void replanArmedTrack(ArmedTrack track, long armGeneration",
                "void recordSeek()");
        assertTrue(compute.contains("AutomixEnvelope.load(outgoing.getSourceFile()) == null")
                && compute.contains("AutomixEnvelope.load(track.file) == null"),
                "后台重算负责校验两侧包络内容");

        String syncWarm = between(cloud, "static boolean warmPairSearchEnvelopeNow", "static void warmPairSearchEnvelope");
        assertTrue(syncWarm.contains("AutomixEnvelope.load(source) != null")
                && syncWarm.contains("AutomixEnvelope.ensure(source, durationMillis)"),
                "权威同步预备应复用有效边车并重扫损坏边车");
        String async = between(cloud, "static void warmPairSearchEnvelope(AudioPlayer deck)",
                "public static boolean warmAutomixProfile");
        assertTrue(async.contains("PAIR_ENVELOPE_IN_FLIGHT.add(key)"));
        assertTrue(async.contains("PAIR_ENVELOPE_IN_FLIGHT.remove(key)"));
    }

    @Test
    @DisplayName("无完整配对证据的 Folia 基线计划不缓存")
    void planWithoutEvidenceIsNotCached() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        int gate = cloud.indexOf("if (track.pairSearchEvidence) {");
        int store = cloud.indexOf("AutomixPlanCache.store(ownedPlayer, deck, lastVocalEnd, track.plan)");
        assertTrue(gate >= 0, "写盘要由配对证据闸保护");
        assertTrue(store > gate && store - gate < 800, "证据闸必须包住写盘");
        assertTrue(cloud.contains("track.pairSearchEvidence = !pairSearchRequired || (incomingEnvelope && outgoingEnvelope)"));
    }

    @Test
    @DisplayName("普通 Folia 证据到货后后台只补算一次，房间不另算")
    void replanRunsOnceAndLeavesTheRoomAlone() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        String trigger = between(cloud, "private void maybeReplanArmedTrack", "private void replanArmedTrack");
        assertFalse(trigger.contains("if (!AutomixSettings.isAdvancedModeEnabled()) return;"),
                "普通 Folia 包络证据到货后的 AutoCue 补算不能受进阶开关拦截");
        assertTrue(trigger.contains("if (track.replanned || track.roomMode) return;"),
                "常规证据补算只做一次且房间权威计划不本地重算");
        assertTrue(trigger.contains("if (track.pairSearchEvidence || track.replanning || track.preRolled) return;"));
        assertTrue(trigger.contains("AutomixEnvelope.isPresent(track.file)"));
        assertTrue(trigger.contains("AutomixEnvelope.isPresent(activePlayer.getSourceFile())"));
        assertTrue(trigger.contains("REPLAN_MIN_LEAD_MILLIS"));

        String compute = between(cloud, "private void replanArmedTrack(ArmedTrack track, long armGeneration",
                "void recordSeek()");
        assertFalse(compute.contains("prepareStart"));
        assertTrue(compute.contains("AutomixEnvelope.load(outgoing.getSourceFile()) == null")
                && compute.contains("AutomixEnvelope.load(track.file) == null"));

        String install = between(cloud, "private void installReplannedPlan", "boolean requestManualSkip() {");
        assertTrue(install.contains("deck.prepareStart(fresh.incomingCueMillis - preRoll)"));
        assertTrue(install.contains("if (position + requiredLead > fresh.fireMillis - preRoll) return;"));
        assertTrue(install.contains("STALE_REPLAN_MIN_LEAD_MILLIS : REPLAN_MIN_LEAD_MILLIS"));

        int triggerCall = cloud.indexOf("maybeReplanArmedTrack(activePlayer);");
        int preRollCall = cloud.indexOf("maybePreRollArmedDeck(activePlayer);");
        assertTrue(triggerCall >= 0 && preRollCall > triggerCall);
    }

    @Test
    @DisplayName("复用缓存的计划要按当前播放头复查，还要在证据齐了才落盘")
    void reusedPlanIsRevalidatedAndStoreIsEvidenceGated() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        assertTrue(cloud.contains("REUSED_PLAN_FIRE_LEAD_MILLIS"),
                "捡回来的计划不能照单执行：预备晚几秒就会拿到一个已经过去的退场点");
        assertTrue(cloud.contains("track.plan.fireMillis < playhead + REUSED_PLAN_FIRE_LEAD_MILLIS"),
                "复查的是「点火点是否还在播放头之后」，不是别的东西");
        assertTrue(cloud.indexOf("track.plan = null;") > cloud.indexOf("AutomixPlanCache.load(ownedPlayer"),
                "过期之后要清掉它，让下面那条重算路径接上");

        // 落盘必须留在后台那一半：install 跑在 10 ms 监督 tick 上，那里不许写文件。
        String install = between(cloud, "private void installReplannedPlan", "boolean requestManualSkip() {");
        assertFalse(install.contains("AutomixPlanCache.store("),
                "装计划的地方不许写盘");
        String compute = between(cloud, "private void replanArmedTrack(ArmedTrack track, long armGeneration",
                "void recordSeek()");
        assertTrue(compute.contains("AutomixPlanCache.store(outgoing, track.deck, lastVocalEnd, plan)"),
                "重算出来的计划要在预备线程上落盘，否则这次装不上就白算");
    }

    @Test
    @DisplayName("拖进度条要让这次混音收尾，而收尾只能排在监督 tick 上")
    void seekEndsTheLiveBlendFromTheTickThread() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        // 两条入口都要接线：进度条与歌词页走绝对量，键盘快进快退走相对量。漏一条就是
        // 「拖条好了、按方向键旧歌还在响」这种查半天的问题。
        String by = between(cloud, "public static boolean seekByMillis(", "public static boolean seekToMillis(");
        String to = between(cloud, "public static boolean seekToMillis(", "public static void onSeekApplied(");
        assertTrue(by.contains("onSeekApplied()"), "相对跳转也要通知 automix");
        assertTrue(to.contains("onSeekApplied()"), "绝对跳转也要通知 automix");

        // 通知那一侧只做记账：界面线程不许直接放牌。
        String record = between(cloud, "void recordSeek() {", "private boolean holdSeamForFreshCue(");
        assertTrue(record.contains("seekDuringFadeRequested = true"),
                "混音进行中就把「要收尾」记下来");
        assertFalse(record.contains("finishAutomixFade"),
                "收尾不能在调用者的线程上跑：它会和正在推进曲线的同一个 tick 抢 activeFade");

        // tick 消费，且排在推进曲线之前。
        int consume = cloud.indexOf("if (seekDuringFadeRequested)");
        int drive = cloud.indexOf("driveAutomixFade();");
        assertTrue(consume >= 0 && consume < drive,
                "同一 tick 里先推进再收尾，等于明知这次作废还要照旧曲线多写一帧");
        assertTrue(cloud.substring(consume, consume + 160).contains("finishAutomixFade();"));
    }

    @Test
    @DisplayName("播放头越过点火点：顶住交接等新接缝，但绝不无限等")
    void aSeamLeftBehindByASeekIsRecomputedNotFired() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        String hold = between(cloud, "private boolean holdSeamForFreshCue(", "private boolean startSeamHold(");
        String start = between(cloud, "private boolean startSeamHold(", "把后台算好的新计划装上");

        assertTrue(hold.contains("AutomixSettings.isAdvancedModeEnabled()"),
                "seek 后顶住失效 seam 仍由进阶开关控制");
        assertTrue(start.contains("track.roomMode"),
                "房间的点火点归服务端决定，本机不许为了等一条更好的接缝把换歌帧拖过去");
        assertTrue(start.contains("AutomixEnvelope.isPresent("),
                "seam hold 的 tick 分支只检查 sidecar 存在位，不加载/解码包络");
        assertTrue(start.contains("overlapMillis + SEAM_HOLD_MILLIS"),
                "剩余长度还得放得下一次交叠加上这段等待，否则更好的接缝根本不存在");
        assertTrue(start.contains("position + track.preRollMillis < track.plan.fireMillis"),
                "计划还赶得上就别顶：这一条只对越过点火点的拖动生效");
        assertTrue(start.contains("if (!track.replanning && track.replannedPlan == null) return false;"),
                "活儿没交出去就不许顶，否则是干等一个永远不来的结果");
        assertTrue(cloud.contains("SEAM_HOLD_MILLIS = 1_500L"),
                "等待必须有上限：晚一点交接胜过静默空隙，但不能把出场曲拖到播完");

        // 顶住的那几拍里，预卷与点火两步都要跳过——只跳点火的话，预卷照旧按旧点火点出声。
        int gate = cloud.indexOf("if (!holdSeamForFreshCue(activePlayer)) {");
        int preRoll = cloud.indexOf("maybePreRollArmedDeck(activePlayer);", gate);
        int fire = cloud.indexOf("tryAutomixHandover(activePlayer)", gate);
        assertTrue(gate >= 0 && gate < preRoll && preRoll < fire,
                "顶住 = 同时不预卷、不点火，且排在重算之后（结果到货要先装上）");
        assertTrue(cloud.indexOf("maybeReplanArmedTrack(activePlayer);") < gate,
                "复查排在顶住之前，否则结果到货的那一拍还会白顶一次");
    }

    @Test
    @DisplayName("作废的接缝用短跑道，两次触发共用一份预算")
    void staleSeamUsesShorterRunwayAndASharedBudget() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        assertTrue(cloud.contains("STALE_REPLAN_MIN_LEAD_MILLIS"),
                "「不动」在接缝作废时不是安全选项，这一类要允许更短的跑道");
        String install = between(cloud, "private void installReplannedPlan(", "用户按下的");
        assertTrue(install.contains("old.fireMillis - track.preRollMillis <= position"),
                "跑道上用哪一档，要看旧计划是不是已经被播放头甩下了");
        assertTrue(install.contains("seamHoldUntil = 0L;"),
                "顶住交接等的是这一份结果：装完（或决定不装）都要放行，不许留在顶上");

        String request = between(cloud, "private void requestReplan(", "的算的那一半");
        assertTrue(request.contains("track.replanCount >= MAX_REPLAN_PER_ARM"),
                "两个触发源共用一份预算：拖十次进度条不该烧十趟整曲分析");

        String maybe = between(cloud, "private void maybeReplanArmedTrack(AudioPlayer activePlayer) {",
                "private void requestReplan(");
        int installCall = maybe.indexOf("installReplannedPlan(track, activePlayer)");
        int budget = maybe.indexOf("if (track.replanned || track.roomMode) return;");
        assertTrue(installCall >= 0 && installCall < budget,
                "装结果必须排在预算位之前——预算在提交那一刻就花掉了，排在它后面等于算好的那份永远没人装");

        String cancel = between(cloud, "private void cancelArmedTrack()", "closeQuietly(track.deck);");
        assertTrue(cancel.contains("seamHoldUntil = 0L;"), "牌没了，等也没有意义，立刻放行");
    }

    @Test
    @DisplayName("混音中按「下一首」仍走硬切，但正在淡出的那张必须有人放掉")
    void manualSkipDuringABlendIsCleanedByTheThreadsOwnTearDown() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        String skip = between(cloud, "boolean requestManualSkip() {", "一起听房间的换歌帧要求立即交叠交接");
        assertTrue(skip.contains("if (activeFade != null) return false;"),
                "混音里提升第三张牌会让三首同时可闻，只能回退到普通切歌——这一条本轮确认过是有意为之，不是漏的");

        // 回退那一路自己不收牌：close 掉活动那张之后，正在淡出的那张靠 run() 的两处收尾放掉。
        // 少一处就是「旧歌在新歌下面一直播到自然结束」，而它已经在混音里被压到一半音量。
        int afterCompletion = cloud.indexOf("waitForPlaybackCompletion();");
        assertTrue(afterCompletion >= 0
                        && cloud.indexOf("finishAutomixFade();", afterCompletion) - afterCompletion < 300,
                "正常走完那一路要放牌");
        int inFinally = cloud.indexOf("正在混音中途的播放牌不是活动播放器");
        assertTrue(inFinally > 0 && cloud.indexOf("finishAutomixFade();", inFinally) - inFinally < 260,
                "异常与取消那一路也要放牌：这条是最后一道");
        assertTrue(cloud.indexOf("cancelArmedTrack();", inFinally) < cloud.indexOf("finishAutomixFade();", inFinally),
                "预备与混音两处收尾要挨在一起，别只补一半");

        assertTrue(occurrences(cloud, "finishAutomixFade();") >= 4,
                "收尾点少一处，就有一张牌留在空中继续出声（本方法被调的次数）");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static Path music(String name) {
        return AutomixSources.music(name);
    }

    private static Path screens(String... parts) {
        java.util.List<String> path = new java.util.ArrayList<>();
        path.add("com");
        path.add("muoniumplayer");
        path.add("core");
        path.add("screens");
        for (String part : parts) path.add(part);
        return ProjectPaths.sourceFile(Paths.get(String.join(java.io.File.separator, path)));
    }

    /**
     * 取 {@code from} 到 {@code to} 之间的源码（含头不含尾）。两个标记找不到就直接说明是哪一个，
     * 不要让人去猜那句 StringIndexOutOfBounds。
     */
    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到方法开头：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到方法结尾（在 " + from + " 之后）：" + to);
        return source.substring(start, end);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
