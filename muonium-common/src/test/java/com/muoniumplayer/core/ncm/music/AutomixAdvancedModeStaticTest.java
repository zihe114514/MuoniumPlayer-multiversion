package com.muoniumplayer.core.ncm.music;

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
    @DisplayName("三条新功能都在闸后面，闸外没有一次扫描或解码")
    void everyNewBehaviourIsBehindTheGate() throws Exception {
        String planner = read(music("FoliaAutomixPlanner.java"));
        assertTrue(planner.contains("if (AutomixSettings.isAdvancedModeEnabled()) {"),
                "规划器要先看闸再读包络");
        assertTrue(planner.indexOf("AutomixEnvelope.load(") > planner.indexOf("isAdvancedModeEnabled()"),
                "读包络必须发生在闸之后");
        assertTrue(planner.contains("private static AutomixAutoCue.Result advancedCue("));

        String cloud = read(music("CloudMusic.java"));
        String warm = cloud.substring(cloud.indexOf("static void warmAdvancedEnvelope(AudioPlayer deck)"));
        assertTrue(warm.indexOf("if (!AutomixSettings.isAdvancedModeEnabled()) return;")
                        < warm.indexOf("BackgroundWork.submit"),
                "预热要在入队之前就被闸拦掉：关掉时连一个后台任务都不该排");

        String panel = read(screens("ncm", "panels", "PlayQueuePanel.java"));
        assertTrue(panel.contains("isAdvancedModeEnabled()"),
                "「排列」按钮要跟着总开关收掉，而不是留一个按下去什么都不做的按钮");
    }

    @Test
    @DisplayName("规划路径只读缓存：整曲扫描只发生在预热里")
    void planningNeverScans() throws Exception {
        String planner = read(music("FoliaAutomixPlanner.java"));
        assertTrue(!planner.contains("AutomixEnvelope.ensure("),
                "规划器里绝不能出现 ensure()：那会在预备窗里起若干个解码进程扫完整首歌");

        String envelope = read(music("AutomixEnvelope.java"));
        assertTrue(envelope.contains("static AutomixEnvelope load(File source)"), "只读口要单独存在");
        assertTrue(envelope.contains("static boolean ensure(File source, long durationMillis)"));

        String cloud = read(music("CloudMusic.java"));
        assertTrue(cloud.contains("AutomixEnvelope.ensure(playable, (long) deck.getTotalTimeMillis())"),
                "整曲扫描要挂在已有的 profile 预热上，同一线程同一文件，别另起一条链");
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
        assertTrue(cache.contains("isAdvancedModeEnabled()"),
                "计划缓存的键要带总开关，否则关掉之后还会命中开着时算的那份");
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
    @DisplayName("模型里有处理方案四档单选，进阶是其中最高的一档")
    void modelExposesTheSwitch() throws Exception {
        String model = read(screens("hud", "HudSettingsModel.java"));
        assertTrue(model.contains("choice(\"处理方案\""),
                "模型驱动的那套（内置编辑器与 OneConfig 桥）要有处理方案条目");
        assertTrue(model.contains("new String[] {\"原版\", \"Folia 稳健\", \"Folia 表现\", \"Folia 进阶\"}"),
                "处理方案是引擎×表现×进阶的三合一单选：三个隐含档位不该再让用户自己拼");
        assertTrue(model.contains("HudSettingsModel::setAutomixPlan"),
                "四档要有直写 setter（下拉选择走它，而不是只靠循环）");
        assertTrue(model.contains("AutomixSettings.setAdvancedModeEnabled(advanced)"),
                "进阶的开关必须由档位写入路径驱动");
    }

    @Test
    @DisplayName("退场点只能往未来找：规划时把播放头传给搜索")
    void firePointIsClampedToTheFuture() throws Exception {
        String planner = read(music("FoliaAutomixPlanner.java"));
        assertTrue(planner.contains("MIN_ADVANCED_FIRE_LEAD_MILLIS"),
                "要有一条明确的「播放头之后多少毫秒」下限，而不是就地比较");
        assertTrue(planner.contains("request.minimumFireMillis"),
                "播放头要传进搜索；不传就会选出一个已经播过去的时间点，交接当场开始");
        assertTrue(planner.contains("result.fireMillis < request.minimumFireMillis"),
                "采纳前还要再判一次——计划会被缓存复用，复用时的播放头更靠后");
    }

    @Test
    @DisplayName("进场侧包络必须在规划之前扫完：提交完就走等于没提交")
    void incomingEnvelopeIsScannedBeforePlanning() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        int scan = cloud.indexOf("warmAdvancedEnvelopeNow(file, decodedMillis)");
        int plan = cloud.indexOf("RoomAutomix.planWith(ownedPlayer, deck, song, lastVocalEnd)");
        assertTrue(scan >= 0, "预备路径上要有一条同步扫完整曲包络的调用");
        assertTrue(plan > scan, "扫描要排在规划之前——规划就在同一根线程上紧随其后，异步提交等于没提交");

        String helper = between(cloud, "static boolean warmAdvancedEnvelopeNow", "static void warmAdvancedEnvelope");
        assertTrue(helper.contains("if (!AutomixSettings.isAdvancedModeEnabled()) return true;"),
                "总开关关闭时它要第一行返回 true：那时候不存在需要等的证据，缓存与复查都不该被它牵动");
        assertTrue(helper.contains("AutomixEnvelope.ensure(source, durationMillis)"),
                "开着就要真扫；只查不扫的那条是异步版，用途不同");
    }

    @Test
    @DisplayName("没证据算出的计划不落盘，否则同一对歌永远轮不到配对选点")
    void planWithoutEvidenceIsNotCached() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        int gate = cloud.indexOf("if (track.advancedEvidence) {");
        int store = cloud.indexOf("AutomixPlanCache.store(ownedPlayer, deck, lastVocalEnd, track.plan)");
        assertTrue(gate >= 0, "写盘要有一道证据闸");
        assertTrue(store > gate && store - gate < 800, "证据闸要包住这条写盘，而不是包着别的什么");
        assertTrue(cloud.contains("track.advancedEvidence = !AutomixSettings.isAdvancedModeEnabled()"),
                "关闭时这条判据恒为真：老行为一位都不许变");
    }

    @Test
    @DisplayName("证据到货后只复查一次；房间的接缝不许本机另算；改入点归播放线程")
    void replanRunsOnceAndLeavesTheRoomAlone() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        String trigger = between(cloud, "private void maybeReplanArmedTrack", "private void replanArmedTrack");
        assertTrue(trigger.contains("if (!AutomixSettings.isAdvancedModeEnabled()) return;"),
                "关闭时这条路第一行就断掉，不读盘、不入队");
        assertTrue(trigger.contains("track.roomMode"), "房间那条计划的来源是房主那台，本机另算会让两台机器叠出两条波形");
        assertTrue(trigger.contains("track.replanned"), "一条预备只许复查一次");
        assertTrue(trigger.contains("REPLAN_MIN_LEAD_MILLIS"), "贴着接缝改计划要做不得，要有跑道下限");
        assertTrue(trigger.contains("AutomixEnvelope.isPresent(track.file)"),
                "两份包络此刻都在才重算——不然只会算出同一条兜底结论");

        String compute = between(cloud, "private void replanArmedTrack(ArmedTrack track, long armGeneration",
                "void recordSeek()");
        assertFalse(compute.contains("prepareStart"), "后台只算不改牌；改入点必须回到播放线程");

        String install = between(cloud, "private void installReplannedPlan", "boolean requestManualSkip() {");
        assertTrue(install.contains("deck.prepareStart(fresh.incomingCueMillis - preRoll)"));
        assertTrue(install.contains("if (position + requiredLead > fresh.fireMillis - preRoll) return;"),
                "跑道是按旧计划判的，新计划的退场点可能更早，装之前要按它自己再验一次");
        assertTrue(install.contains("STALE_REPLAN_MIN_LEAD_MILLIS : REPLAN_MIN_LEAD_MILLIS"),
                "两档跑道：旧接缝还没作废时「不动」是安全项，已经作废时「不动」才是危险项");

        int triggerCall = cloud.indexOf("maybeReplanArmedTrack(activePlayer);");
        int preRollCall = cloud.indexOf("maybePreRollArmedDeck(activePlayer);");
        assertTrue(triggerCall >= 0 && preRollCall > triggerCall,
                "复查要排在预卷之前：同一 tick 里换完入点，预卷才按新的算");
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
                "顶住交接是进阶模式里的改动：关着时普通路径的行为要一字不改");
        assertTrue(start.contains("track.roomMode"),
                "房间的点火点归服务端决定，本机不许为了等一条更好的接缝把换歌帧拖过去");
        assertTrue(start.contains("AutomixEnvelope.isPresent("),
                "两份整曲包络都不在的时候重算只会得出同一条启发式结论，那是白等");
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
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music", name));
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
