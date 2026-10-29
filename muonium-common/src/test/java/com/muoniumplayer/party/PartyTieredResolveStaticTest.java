package com.muoniumplayer.party;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间<b>分层代解析</b>在客户端这一侧的接线（离线，不起 Minecraft、不联网）。
 *
 * <p>服务端把「谁答上都行」改成「按可信度一层一层问」之后，客户端多出三件必须做到的事：
 * ① 答不上要<b>当场弃权</b>（沉默会让整房白等一个层窗）；② 应答要带上服务端问的是<b>哪一格</b>
 * （否则只能按 {@code source+songId} 认，跨平台同号曲目会错配）；③ 全层落空后服务端单播来的
 * 救援帧要真跑一次跨平台流水线并用 {@code track_replace} 交卷。这三件全是「少写一句就静默退化」
 * 的类型：不抛异常、不报错，只是那首歌又开始白等，所以只能靠断言钉住。</p>
 *
 * <p>本类只读源码 + 调两个纯算法方法。绝不真去调脚本解析、换源流水线或任何网络路径 ——
 * {@link MpStreamEstimate#headBytes} 在这里只喂非 http 与零预算两种输入，两条都在发请求之前就返回。</p>
 */
class PartyTieredResolveStaticTest {

    private static final Path PARTY = ProjectPaths.repoRoot()
            .resolve(Paths.get("mpparty-common", "src", "main", "java", "com", "muoniumplayer", "party"));

    // ════════════════════════ 弃权：沉默不再是答案 ════════════════════════

    @Test
    @DisplayName("url_needed 的三个新字段必须一路传到 supplyUrl")
    void urlNeededCarriesEntryTierAndDeadline() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        int handler = client.indexOf("MpProto.S_URL_NEEDED.equals(op)");
        assertTrue(handler >= 0, "没有 url_needed 的分派");
        String frame = client.substring(handler, client.indexOf("S_PRESENCE", handler));

        assertTrue(frame.contains("MpProto.num(msg, \"entryId\", 0L)"),
                "不读 entryId，应答就只能靠 source+songId 认，跨平台同号曲目会错配");
        assertTrue(frame.contains("(int) MpProto.num(msg, \"tier\", MpProto.TIER_UNKNOWN)"),
                "不读 tier，弃权帧对不上服务端的当前层，会被当过期帧丢掉");
        assertTrue(frame.contains("MpProto.num(msg, \"deadlineMs\", 0L)"),
                "不读 deadlineMs，客户端就没法判断这一层还来不来得及跑慢链路");
        assertTrue(client.contains("} else if (MpProto.S_SWITCH_RESCUE.equals(op)) {"),
                "服务端发来的救援帧没人接，这一手就只是让这首歌多等一个救援窗");
    }

    @Test
    @DisplayName("代解析的每一个沉默出口都改成弃权")
    void everySilencePointDeclines() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String supply = region(client,
                "private static void supplyUrl(final String source, final String songId,",
                "房间下发的 songId 不一定能当数字用");

        assertTrue(supply.contains("declineUrl(entryId, tier, src, songId, \"no-platform\")"),
                "本机压根没这个平台时以前是直接 return，现在必须说一声");
        assertTrue(supply.contains("declineUrl(entryId, tier, src, songId, target == null ? why : lxReason(src))"),
                "脚本答不上是这一层最常见的沉默，必须换成弃权");
        assertTrue(supply.contains("\"netease-trial\""),
                "「只解到试听片段」必须能与「压根没解出来」在日志里分开");
        assertTrue(supply.contains("sendUrlSupply(src, songId, url, est, est > 0L, entryId)"),
                "应答要把 entryId 带回去");
        assertTrue(!supply.contains("if (url == null) return;"),
                "不许留下「解不出就 return」那种裸沉默");
    }

    @Test
    @DisplayName("弃权原因带渠道键：一份脚本五六个渠道，光说 lx 失败分不出三种事")
    void declineReasonCarriesTheChannelKey() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String reason = region(client, "private static String lxReason(String sourceToken) {",
                "private static String channelKeyOf(String sourceToken) {");

        assertTrue(reason.contains("if (!contributeLx()) return \"lx-off\""),
                "用户不让贡献与脚本解不出是两件事，必须分开报");
        assertTrue(reason.contains("\"lx-\" + key + \"-failed\""),
                "渠道键（wy/tx/kg/kw/mg）必须进原因串");
        assertTrue(reason.contains("\"no-lx-channel\""),
                "这台压根没这个渠道也要能单独看出来");

        String keyOf = region(client, "private static String channelKeyOf(String sourceToken) {",
                "private static long estimateServedMs(");
        assertTrue(keyOf.contains("CrossSourceSwitcher.lxChannelKey(MpTrack.platformOf(sourceToken))"),
                "渠道键必须复用换源闸门那份正反两次映射，别在 party 层再写一套");
    }

    @Test
    @DisplayName("老服务端不发新字段时，一帧新的都不追加（退回等满窗口）")
    void oldServerSeesNoNewFrames() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String decline = region(client, "private static void declineUrl(long entryId, int tier,",
                "private static String lxReason(String sourceToken) {");
        assertTrue(decline.contains("if (entryId <= 0L || tier < 0) return;"),
                "老服务端不认识 url_decline，发过去只会换回一句「不支持的操作」打在聊天栏");

        String supply = region(client,
                "private static void sendUrlSupply(String source, String songId, String url, long servedMs,",
                "private static void declineUrl(long entryId, int tier,");
        assertTrue(supply.contains("if (entryId > 0L) m.addProperty(\"entryId\", entryId);"),
                "entryId 是可选追加：没有就不发这一位，别让对端拿 0 去对号");
        assertTrue(supply.contains("if (estimated) m.addProperty(\"urlDurationEstimated\", true);"),
                "估算标记只在真报了长度时才有意义");
    }

    // ════════════════════════ 估算长度：给试听检测补上一只眼睛 ════════════════════════

    @Test
    @DisplayName("酷狗 / QQ / 脚本这几路必须报估算长度，且误差往「估长了」那一边偏")
    void estimatedLengthsAreReportedAndBiasedSafe() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String three = region(client, "private static long estimateServedMs(Music song, String url, long expectedMs) {",
                "同上，但把这一次 HEAD 压进");
        assertTrue(three.contains("return estimateServedMs(song, url, expectedMs, 0L);"),
                "点歌那一跳没有窗，预算用满 —— 窗长是服务端留给代解析那一层的，本机自己上传时没人等");

        String budget = region(client,
                "private static long estimateServedMs(Music song, String url, long expectedMs, long deadlineMs) {",
                "本机认为这条链接交付的是什么码率");
        assertTrue(budget.contains("MpStreamEstimate.headBytes(url, budget)"),
                "长度来自一次带预算的 HEAD");
        assertTrue(budget.contains("if (left < MpStreamEstimate.MIN_BUDGET_MS) return 0L;"),
                "层窗只剩一点点就干脆不发：探测只是多加一只眼睛，不该把整层的窗吃光");
        assertTrue(budget.contains("budget = Math.min(budget, left)"),
                "预算取「单次上限」与「剩余窗」里小的那一个");
        assertTrue(budget.contains("if (song == null || url == null || expectedMs <= 0L) return 0L;"),
                "没有参照时长就别猜：报 0 是「未知」，服务端按放行处理");

        String kbps = region(client, "private static int declaredKbps(Music song) {",
                "private static void supplyRescue(JsonObject msg) {");
        assertTrue(kbps.contains("case LOSSLESS:") && kbps.contains("return 400;"),
                "无损族必须按压低的码率算 —— 码率当小了时长才估得长，才不会被误判成试听片段");
        assertTrue(kbps.contains("case UNKNOWN:"));
        assertTrue(kbps.contains("return 0;"), "说不清码率就返回 0，宁可不报");

        // 点歌那一路（第一次上传，不带 url_needed）同样要报，否则试听片段第一步就过关了。
        String upload = region(client,
                "private static void upload(final Music song, final String op, final boolean playNext) {",
                "private static void delegateResolve(String op, Music song, boolean playNext, String why) {");
        assertTrue(upload.contains("long est = estimateServedMs(song, url, song.getDuration());"),
                "非网易云平台点歌时必须估长度");
        assertTrue(upload.contains("sendTrack(op, song, url, est, playNext, est > 0L);"),
                "估出来的长度必须标成估算值上报");
    }

    @Test
    @DisplayName("track 对象里的估算标记只在报了长度时才写，且紧跟在长度后面")
    void trackJsonMarksEstimatedOnlyWithLength() throws IOException {
        String track = read(PARTY.resolve("MpTrack.java"));
        String body = region(track,
                "public static JsonObject toJson(Music song, String streamUrl, long urlExpiresAt,",
                "    public long numericId()");
        assertTrue(body.contains("if (servedDurationMs > 0) {"),
                "长度与标记必须进同一个判断：只报 estimated 不报长度是没根据的话");
        assertTrue(body.contains("if (estimated) o.addProperty(\"urlDurationEstimated\", true);"));
        assertTrue(body.indexOf("o.addProperty(\"urlDuration\", servedDurationMs);")
                        < body.indexOf("if (estimated) o.addProperty(\"urlDurationEstimated\", true);"),
                "标记必须排在长度之后，读的人才知道它修饰的是哪一位");
    }

    @Test
    @DisplayName("字节数 ÷ 码率：参数不合法一律 0，绝不给一个看着像样的数")
    void estimateMathRefusesToGuess() {
        assertEquals(0L, MpStreamEstimate.durationMs(0L, 128));
        assertEquals(0L, MpStreamEstimate.durationMs(-5L, 128));
        assertEquals(0L, MpStreamEstimate.durationMs(1_000_000L, 0));
        // 128kbps = 每秒 16000 字节 → 1.6MB 是 100 秒。
        assertEquals(100_000L, MpStreamEstimate.durationMs(1_600_000L, 128));
        // 同一份文件按 400kbps 只剩 32 秒 —— 码率估高了就会把完整版判成试听片段。
        assertEquals(32_000L, MpStreamEstimate.durationMs(1_600_000L, 400));
    }

    @Test
    @DisplayName("非 http / 预算见底都不发探测，直接当「长度未知」")
    void probeRefusesNonHttpAndEmptyBudget() {
        assertEquals(-1L, MpStreamEstimate.headBytes(null, 1_200L));
        assertEquals(-1L, MpStreamEstimate.headBytes("", 1_200L));
        assertEquals(-1L, MpStreamEstimate.headBytes("ftp://example.com/a.mp3", 1_200L));
        // 预算不足一个最小超时：当场收手，绝不带着代解析的窗去等一条死 CDN。
        assertEquals(-1L, MpStreamEstimate.headBytes("https://example.com/a.mp3", 100L));
        assertEquals(0L, MpStreamEstimate.durationMs(-1L, 128));
    }

    // ════════════════════════ 跨平台救援：点歌人本机换平台 ════════════════════════

    @Test
    @DisplayName("switch_rescue 必须真跑换源流水线，并用 track_replace 交卷")
    void rescueRunsThePipelineAndReplacesTheEntry() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));

        String rescue = region(client, "private static void supplyRescue(JsonObject msg) {",
                "private static void runRescue(long entryId,");
        assertTrue(rescue.contains("MpProto.num(msg, \"entryId\", 0L)"),
                "不认 entryId 的替换帧服务端对不上是哪一格");
        assertTrue(rescue.contains("RESCUE_IN_FLIGHT.add(Long.valueOf(entryId))"),
                "一次流水线十几秒，同一格必须只跑一次");
        assertTrue(rescue.contains("track.toMusic()"),
                "重建曲目必须复用房间那套映射，别为救援另写一份");

        String run = region(client, "private static void runRescue(long entryId, Music origin,",
                "回一句「这台换不到」，让服务端立刻改派下一台");
        assertTrue(run.contains("CrossSourceSwitcher.canAutoSwitch(origin, true)"),
                "必须走 allowInRoom 那一档判定：这里换的是队列里那一格，不是本机偷偷换");
        assertTrue(run.contains("findReplacementForAuto(origin, CloudMusic.quality)"),
                "救援要复用打分 + 时长闸门 + 取流验证那条流水线，不要在 party 层重写匹配");
        assertTrue(run.contains("MpProto.C_TRACK_REPLACE"), "答卷是替换，不是 url_supply");
                assertTrue(run.indexOf("System.currentTimeMillis() > deadline")
                        < run.indexOf("estimateServedMs(match.music"),
                "先判窗再估：过了点连替换帧都不发，那一次 HEAD 就是纯浪费");
        assertTrue(run.contains("System.currentTimeMillis() > deadline"),
                "过点之后服务端多半已跳过这一格，要把话说清而不是发一条注定被拒的帧");
        assertTrue(run.contains("estimateServedMs(match.music, url, match.music.getDuration(), deadline)"),
                "参照时长必须取替身自己那一个：替换之后这一格的 duration 就是替身的，"
                        + "拿原曲当参照会在边界上把方向判反");
        assertTrue(!run.contains("origin.getDuration()"),
                "原曲的时长在这条路上没有任何用处");
    }

    @Test
    @DisplayName("房间救援只放开「在房间里」那一道闸，官方一起听照旧拦")
    void rescueOpensOnlyTheInRoomGate() throws IOException {
        String switcher = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "CrossSourceSwitcher.java")));
        String gate = region(switcher, "public static boolean canAutoSwitch(Music current, boolean allowInRoom) {",
                "自动路径：记一次「试过」，再跑流水线");

        assertTrue(gate.contains("if (!allowInRoom && MpPartyClient.inRoom()) return false;"),
                "唯一的放行位就是这一条");
        assertTrue(gate.contains("if (NeteaseListenTogetherService.isActive()) return false;"),
                "网易云官方一起听按曲目本体同步，那里换源就是真脱队");
        assertTrue(gate.contains("if (KuGouListenTogetherService.isActive()) return false;"),
                "酷狗官方一起听按 hash 同步，同上");
        assertTrue(gate.contains("if (!SwitchSourceSettings.isEnabled()) return false;"),
                "用户的自动换源总开关必须继续管着这一路：它管的是「愿不愿意去别的平台搜一圈」");
        assertTrue(gate.contains("candidatePlatforms(current.getSource()).isEmpty()"),
                "没有够得着的候选平台就别发，救援也一样");
        assertTrue(gate.contains("AUTO_ATTEMPTED.get(key)"),
                "本机刚为这首歌搜过一遍就别再搜第二遍（账带时间戳：10 分钟后过期给新机会）");
    }

    @Test
    @DisplayName("渠道键的判据只有一份：lxCovers 与 lxChannelKey 共用同一次正反映射")
    void channelKeyHasASingleSourceOfTruth() throws IOException {
        String switcher = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "CrossSourceSwitcher.java")));
        String covers = region(switcher, "public static boolean lxCovers(MusicPlatform platform) {",
                "private static String platformLabel(MusicPlatform platform)");
        assertTrue(covers.contains("String key = lxChannelKey(platform);"),
                "lxCovers 不许再自己拼一次映射，否则报出去的渠道键与实际能不能解会漂");

        String key = region(switcher, "public static String lxChannelKey(MusicPlatform platform) {",
                "// ── 负缓存");
        assertTrue(key.contains("LxSourceService.sourceKeyFor(platform.name(), GdStudioSourceSettings.getPlatform())"),
                "平台→键的映射必须与脚本取流时那一次同源");
        assertTrue(key.contains("LxSourceService.platformForSourceKey(key) == null"),
                "键→平台反查也要过：单向映射会让上报里出现服务端认不出的令牌");
    }

    @Test
    @DisplayName("档案必须自报「有这条流水线」，否则服务端压根不会问出那一帧")
    void profileAdvertisesTheRescuePipeline() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String profile = region(client, "private static void fillProfile(JsonObject m) {",
                "本机此刻在听什么");
        assertTrue(profile.contains("m.addProperty(\"switchRescue\", CrossSourceSwitcher.rescueCapable())"),
                "服务端为收到救援帧的那一首会多等一个窗；这一位不报，Mu 永远收不到那一帧，"
                        + "整个跨平台救援就是死代码");

        String switcher = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "CrossSourceSwitcher.java")));
        String capable = region(switcher, "public static boolean rescueCapable() {",
                "自动路径：记一次「试过」，再跑流水线");
        assertTrue(capable.contains("return SwitchSourceSettings.isEnabled();"),
                "能力位只看总开关：逐曲目的判据留在 canAutoSwitch 里，别混进上报");
    }

    // ════════════════════════ 协议词表：与服务端同名同值 ════════════════════════

    @Test
    @DisplayName("新增的三个 op 名与层号取值必须写死在 MpProto，不许散在调用点")
    void protocolVocabularyIsCentralised() throws IOException {
        String proto = read(PARTY.resolve("MpProto.java"));
        assertTrue(proto.contains("public static final String C_URL_DECLINE = \"url_decline\";"));
        assertTrue(proto.contains("public static final String C_TRACK_REPLACE = \"track_replace\";"));
        assertTrue(proto.contains("public static final String S_SWITCH_RESCUE = \"switch_rescue\";"));
        assertTrue(proto.contains("public static final int TIER_UNKNOWN = -1;"),
                "老服务端的判据要有一个不会与真实层号撞车的哨兵");
        // 层号就是可信度顺序，服务端按它决定谁能顶掉谁；这里漂一位，采纳顺序整个反了。
        assertTrue(proto.contains("public static final int TIER_MEMBER = 1;"));
        assertTrue(proto.contains("public static final int TIER_CONTRIBUTED_LX = 2;"));
        assertTrue(proto.contains("public static final int TIER_RESOLVABLE = 3;"));
        assertTrue(proto.contains("public static final int TIER_REQUESTER = 4;"));
        assertTrue(proto.contains("public static final int TIER_INSTALLED = 5;"));
        assertTrue(proto.contains("public static final int TIER_EVERYONE = 6;"));
    }


    // ═══════════════ 三条路的客户端侧：求助之前先自己试、弃权要回帧、进房要说清 ═══════════════

    @Test
    @DisplayName("点歌路径：本机换平台排在「交给全房」之前")
    void requesterSwitchesPlatformBeforeAskingTheRoom() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String upload = region(client,
                "private static void upload(final Music song, final String op, final boolean playNext) {",
                "private static String resolveByPlayer(Music song) {");
        int custom = upload.indexOf("customSourceUrl(song)");
        int localSwitch = upload.indexOf("switchLocally(op, song, playNext)");
        int delegated = upload.indexOf("delegateResolve(op, song,");
        assertTrue(custom > 0 && localSwitch > custom,
                "本家与脚本都失败之后，先在自己这台机器上换一次平台 —— 一次本机搜索比一轮分层便宜");
        assertTrue(delegated > localSwitch, "本机换不到才轮到向全房求助");
        int nonNeteaseSwitch = upload.indexOf("switchLocally", upload.indexOf("String url = resolveByPlayer(song)"));
        int nonNeteaseAsk = upload.indexOf("delegateResolve(op, song, playNext, \"本机拿不到播放链接\")");
        assertTrue(nonNeteaseSwitch > 0 && nonNeteaseSwitch < nonNeteaseAsk,
                "非网易云那一路也要先换平台再求助：那一路上原来是直接取消点歌，房间根本不知道这首歌存在");

        String helper = region(client, "private static boolean switchLocally(String op, Music song,",
                "private static void sendTrack(String op, Music song, String url, long servedMs, boolean playNext)");
        assertTrue(helper.contains("CrossSourceSwitcher.canAutoSwitch(song, true)"),
                "必须走 allowInRoom 那一档：这里换的是要点进房间的那一首，不是本机偷偷换");
        assertTrue(helper.contains("findReplacementForAuto(song, CloudMusic.quality)"),
                "复用打分 + 时长闸门 + 取流验证那条流水线");
        assertTrue(helper.contains("sendTrack(op, match.music, url, est, playNext, est > 0L)"),
                "入队的是**换到的那一版**，并且把估算长度一起带上去");
        assertTrue(helper.contains("estimateServedMs(match.music, url, match.music.getDuration())"),
                "参照时长取替身自己声明的那一个");
    }

    @Test
    @DisplayName("被派到却做不了时回一句 switch_decline，并分清是不是自己点的歌")
    void rescueGivesUpOutLoudAndKnowsWhoseSongItIs() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String rescue = region(client, "private static void supplyRescue(JsonObject msg) {",
                "private static void runRescue(long entryId,");
        assertTrue(rescue.contains("MpProto.bool(msg, \"mine\", false)"),
                "服务端现在派的是「最可能换成功的那台」，收件人常常不是点歌人，文案要分开");
        assertTrue(rescue.contains("runRescue(entryId, origin, deadline, display, mine)"));

        String run = region(client, "private static void runRescue(long entryId, Music origin,",
                "private static void switchDecline(long entryId, String reason) {");
        assertTrue(run.contains("switchDecline(entryId, \"switch-off\")"),
                "开关关着 / 没有够得着的平台 / 刚搜过一遍：当场说一声，别让服务端等满一个救援窗");
        assertTrue(run.contains("switchDecline(entryId, \"pipeline-failed\")"));
        assertTrue(run.contains("switchDecline(entryId, \"no-candidate\")"),
                "真搜不到也要说：那台换不到的机器不改派，就是把这一首的命交给一次白等");
        assertTrue(run.indexOf("switchDecline(entryId, \"switch-off\")")
                        < run.indexOf("findReplacementForAuto"),
                "弃权要排在跑流水线之前：能做不了就早点回，别先烧十几秒");
        assertTrue(run.contains("mine ? \"没在别的平台找到《\""), "替别人换一首与换自己点的歌是两件事");
        assertTrue(run.contains("\"没替《\""), "被派去替别人换一首时不该说成自己没找到");

        String decline = region(client, "private static void switchDecline(long entryId, String reason) {",
                "在当前曲目和歌单里找这首歌");
        assertTrue(decline.contains("if (entryId <= 0L) return;"),
                "老服务端不认这一帧，发过去只换回一句「不支持的操作」");
        assertTrue(decline.contains("sendQuiet(m)"), "弃权是后台应答，不该打进聊天栏");

        String proto = read(PARTY.resolve("MpProto.java"));
        assertTrue(proto.contains("public static final String C_SWITCH_DECLINE = \"switch_decline\";"),
                "op 名与服务端 Proto.C_SWITCH_DECLINE 必须逐字一致");
    }

    @Test
    @DisplayName("进房那一次说清本机在替房间解析什么，且只说一次")
    void joiningARoomExplainsTheContributionOnce() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        assertTrue(client.contains("private static volatile boolean contributionHintShown;"));
        int apply = client.indexOf("private static void applySnapshot(JsonObject msg) {");
        assertTrue(apply >= 0);
        assertTrue(client.indexOf("hintContributionOnce();", apply)
                        < client.indexOf("private static void startTrack(MpTrack track", apply),
                "提示挂在快照那一条路上：重连、转让房主、重新同步都会再来快照");
        String hint = region(client, "private static void hintContributionOnce() {",
                "private static void startTrack(MpTrack track, long pos, boolean isPlaying, long seq) {");
        assertTrue(hint.contains("if (contributionHintShown) return;"));
        assertTrue(hint.contains("账号不出本机"),
                "贡献会员现在是默认开的，这一句必须把「只上传带签名的临时地址」说清，否则像要交账号");
        assertTrue(hint.contains("contributeVip()") && hint.contains("contributeLx()"),
                "两个开关各自独立，提示要按实际开着的那一个说话");
        assertTrue(hint.contains("面板右上角") && hint.contains("胶囊"), "要给出关得掉的路径，而且是真存在的那两处控件");
        int reset = client.indexOf("private static void reset() {");
        assertTrue(client.indexOf("contributionHintShown = false;", reset) > reset,
                "离房必须复位，否则下次进房不再说");
    }

    @Test
    @DisplayName("贡献会员改成默认开：关着它等于把自己排到最后一档")
    void contributingVipIsOnByDefault() throws IOException {
        String settings = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "ListenTogetherSettings.java")));
        assertTrue(settings.contains("private static boolean partyContributeVip = true;"),
                "服务端第 1、3 层按 contributesVip 筛人，默认关让真会员排到「装了该平台」之后");
        assertTrue(settings.contains("flagOf(root, KEY_PARTY_CONTRIBUTE_VIP, true)"),
                "老配置文件里没这一格时也必须仍是开");

        // saveLocked 是整份写盘的：老玩家的配置里已经躺着一行 partyContributeVip=false，
        // 那是**旧默认值**而不是他关的。只翻代码里的默认值对老玩家完全无效；而无条件把
        // false 顶成 true 又会推翻「他确实亲手关过」这件事。所以「有没有碰过」必须单独一格。
        assertTrue(settings.contains("KEY_PARTY_CONTRIBUTE_VIP_TOUCHED = \"partyContributeVipTouched\""),
                "没有这一格的话，翻默认值只对新装的人有效");
        assertTrue(settings.contains("partyContributeVipTouched = flagOf(root, KEY_PARTY_CONTRIBUTE_VIP_TOUCHED, false)"),
                "老配置里没有这一格 = 从没碰过 → 吃新的默认");
        assertTrue(settings.contains("? flagOf(root, KEY_PARTY_CONTRIBUTE_VIP, true)"),
                "碰过的人必须尊重他留下的那一个值");
        assertTrue(settings.contains("partyContributeVipTouched = true;"),
                "setter 被调过就要记下来 —— 那一下是玩家按的");
        assertTrue(settings.contains("root.addProperty(KEY_PARTY_CONTRIBUTE_VIP_TOUCHED, partyContributeVipTouched)"),
                "不落盘的话每次启动都把他的选择重新当成没碰过");
    }

    // ════════════════════════════════ 取数辅助 ════════════════════════════════

    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点：" + to);
        return source.substring(start, end);
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
