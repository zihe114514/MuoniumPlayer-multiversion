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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「一起听」房间里<b>身份上报</b>与<b>代解析接上自定义源</b>的离线回归。
 *
 * <p>这一轮的修法全是「少问一句就多等一轮超时」那一类：退化之后不会抛异常、不会报错，只是
 * 房间里那个人又开始沉默。所以只能靠断言把接线钉住。本类只读源码，不起 Minecraft、不联网、
 * 也绝不真去调 {@code LxSourceService}（那会加载用户的脚本）。</p>
 */
class PartyIdentityReportingStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path PARTY = ProjectPaths.versionRoot()
            .getParent().resolve(Paths.get("mpparty-common", "src", "main", "java", "com", "muoniumplayer", "party"));

    // ════════════════════════ 身份上报：三档分开、不盲猜 ════════════════════════

    @Test
    @DisplayName("C_PROFILE 上报真会员 / 能出完整流 / 只靠脚本 三档，并带 LX 贡献开关")
    void profileReportsThreeDistinctIdentityTiers() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String body = region(client, "private static void fillProfile(JsonObject m) {",
                "/** 本机此刻在听什么");

        assertTrue(body.contains("m.add(\"member\", member)"), "真会员一档没报出去，服务端还是分不清登录与会员");
        assertTrue(body.contains("m.add(\"resolvable\", resolvable)"), "「能不能出完整流」没报出去");
        assertTrue(body.contains("m.add(\"viaLx\", viaLx)"), "只靠脚本才解得出的平台没报出去");
        assertTrue(body.contains("m.addProperty(\"contributeLx\", contributeLx())"),
                "LX 贡献开关没上报，服务端就没法只问愿意贡献的人");
        assertTrue(body.contains("m.add(\"vip\", vip)"), "旧的登录一档必须保留，老服务端靠它兜底");
        assertTrue(body.contains("CrossSourceSwitcher.hasVipAccess(platform)"),
                "resolvable 必须复用换源闸门那份判据，别再写第二套「谁能解」");
        assertTrue(body.contains("CrossSourceSwitcher.lxCovers(platform)"), "viaLx 必须走脚本元数据那套正反映射");
    }

    @Test
    @DisplayName("上报令牌与曲目上报同源：都必须走 MpTrack.token")
    void profileTokensComeFromTheSameMappingAsTrackSource() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String body = region(client, "private static void fillProfile(JsonObject m) {",
                "/** 本机此刻在听什么");
        assertTrue(body.contains("String token = MpTrack.token(platform);"),
                "档案里的平台名必须与 track.source 一个来源，否则服务端按令牌匹配「谁能答」永远对不上");
        assertTrue(!body.contains("sourceOf(platform)"),
                "不能再走那条只认 QQ/酷狗/GD 的老映射");

        String sourceOf = region(client, "private static String sourceOf(MusicPlatform platform) {",
                "private static String safe(String value)");
        assertTrue(sourceOf.contains("MpTrack.token(platform)"),
                "这条映射以前把 B 站 / 汽水 / YouTube 一律写成 netease，求助问题会问错人");
        assertTrue(!sourceOf.contains("SOURCE_NETEASE;"),
                "不许再留「默认网易云」的兜底分支");
    }

    @Test
    @DisplayName("查不到会员档位的平台报「说不准」，不许猜成是或不是")
    void unknownMembershipStaysUnknown() throws IOException {
        String access = read(JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "PlatformAccess.java")));

        // QQ 与 YouTube Music 的上游压根没有可只读查询的会员字段：必须报 null（说不准）。
        String tier = region(access, "public static Boolean memberTier(MusicPlatform platform) {",
                "/**\n     * 这个平台是否「不开会员也拿得到完整版」");
        assertTrue(tier.contains("case QQ:") && tier.contains("return NeteaseMusicService.isLoggedIn(MusicPlatform.QQ) ? null : Boolean.FALSE;"),
                "QQ 登录了不等于绿钻，这里必须给 null 而不是猜一个");
        assertTrue(tier.contains("YOUTUBE) ? null : Boolean.FALSE"), "YouTube Music 同理");
        assertTrue(access.contains("public static Boolean memberTier"),
                "memberTier 返回 Boolean（可为 null）才能表达「不知道」，返回 boolean 就是逼着人猜");
        assertEquals(-1, access.indexOf("HttpUtils"), "这一层必须零网络：它在每 5 秒的心跳里逐个平台被问一遍");
        assertEquals(-1, access.indexOf("queryVipAccount("), "不许为了填上报字段去发会员查询请求");
    }

    @Test
    @DisplayName("LX 取流能力只在脚本 READY 时报，且没启用时不顺手把脚本拉起来")
    void capableKeysAreHonestAboutRuntimeState() throws IOException {
        String service = read(JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "lxsource",
                "LxSourceService.java")));
        String body = region(service, "public static List<String> musicUrlCapableKeys() {",
                "/** 面板上的一行状态。 */");

        assertTrue(body.contains("current.getState() != LxRuntime.State.READY"),
                "还在加载 / 已失败的脚本一律报空表，别许一个答不上的诺");
        assertTrue(body.contains("decl.supports(\"musicUrl\")"),
                "只声明了歌词或封面的脚本不能算「能替谁解歌」");
        assertEquals(-1, body.indexOf("ensureStarted()"),
                "上报路径上不许顺手拉起运行时：那是心跳线程，拉起脚本会把整条上报挂住");
    }

    // ════════════════════════ 代解析：真的问到自定义源 ════════════════════════

    @Test
    @DisplayName("替别人答 url_needed 时，本家给不出完整版必须补问自定义源")
    void supplyingAnswersConsultTheCustomSource() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String supply = region(client, "private static void supplyUrl(final String source, final String songId) {",
                "/** 房间下发的 songId 不一定能当数字用");

        assertTrue(supply.contains("customSourceUrl(target)"),
                "网易云这一路以前只走本家解析，等于把「没会员但脚本能解」的人重新按回沉默");
        assertTrue(supply.contains("!isTrialClip(resolved.servedMs, expected)"),
                "试听片段仍然不能替别人答 —— 那条链接会成为整房共用的那一条");
        assertTrue(supply.contains("MpNetease.songById(id)"),
                "本家失败后要能给脚本凑出 musicInfo，光有 songId 解不了");

        String helper = region(client, "private static String customSourceUrl(Music song) {",
                "private static void sendUrlSupply(String source, String songId, String url, long servedMs)");
        assertTrue(helper.contains("!contributeLx()"),
                "用户没同意贡献脚本，就不能拿他的脚本调用量去答别人的歌");
        assertTrue(helper.contains("song.resolveLxPlayUrl()"),
                "问脚本必须走与实播同一跳的公开包装，否则「脚本明明能播」的答案会被漏掉");
    }

    @Test
    @DisplayName("点歌人自己：本家失败先试脚本，实在不行才委托全房，且委托后并行补答")
    void requesterTriesItsOwnScriptBeforeDelegating() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String upload = region(client, "private static void upload(final Music song, final String op, final boolean playNext) {",
                "private static void delegateResolve(String op, Music song, boolean playNext, String why) {");
        int official = upload.indexOf("MpNetease.resolve(song.getId())");
        int custom = upload.indexOf("customSourceUrl(song)");
        int delegated = upload.indexOf("delegateResolve(op, song,");
        assertTrue(official >= 0 && custom > official,
                "本家那一跳仍排在前面：它报得出实际服务长度，会员解出的完整版好过脚本的「长度未知」");
        assertTrue(delegated > custom, "问完脚本还没结果才轮得到委托全房");

        String delegate = region(client, "private static void delegateResolve(String op, Music song, boolean playNext, String why) {",
                "private static void sendTrack(String op, Music song, String url, long servedMs, boolean playNext)");
        int sendFirst = delegate.indexOf("sendTrack(op, song, \"\", 0L, playNext)");
        int supplyAgain = delegate.indexOf("sendUrlSupply(src, id, url, 0L)");
        assertTrue(supplyAgain > sendFirst,
                "补答必须排在广播之后：广播先到，服务端才有这首歌可以接收这条应答（并行口径）");
        assertTrue(delegate.contains("customSourceUrl(song)"),
                "并行补答跑的就是脚本那一路，否则这条并行是空的");
    }

    // ════════════════════════════════ 设置与闸门 ════════════════════════════════

    @Test
    @DisplayName("贡献自定义源是独立一格开关，默认开，且与贡献会员互不牵连")
    void contributeLxIsItsOwnDefaultOnSwitch() throws IOException {
        String settings = read(JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "ListenTogetherSettings.java")));
        assertTrue(settings.contains("private static boolean partyContributeLx = true;"),
                "默认关的话，没人贡献会员时这条路整个不成立，而这是它唯一的用处");
        assertTrue(settings.contains("flagOf(root, KEY_PARTY_CONTRIBUTE_LX, true)"),
                "老配置文件里没这一格时必须仍是开");
        assertTrue(settings.contains("root.addProperty(KEY_PARTY_CONTRIBUTE_LX, partyContributeLx)"),
                "用户关掉之后必须落盘，否则下次启动又偷偷替他贡献");
        assertTrue(settings.contains("public static boolean isPartyContributeLx()"));
        // 与贡献会员是两件事：两处 getter 各自读各自的字段，不许互相回退。
        assertTrue(!settings.contains("return partyContributeVip;\n        }\n    }\n\n    public static boolean isPartyContributeLx"),
                "LX 那一格不许回退到 VIP 那一格的值");
    }

    @Test
    @DisplayName("party 层能拿到换源闸门与脚本覆盖判据（上报与实播必须同一份代码）")
    void coreJudgementsAreVisibleToThePartyLayer() throws IOException {
        String switcher = read(JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "CrossSourceSwitcher.java")));
        assertTrue(switcher.contains("public static boolean hasVipAccess(MusicPlatform platform)"),
                "party 层在另一个包，包级可见就拿不到同一份闸门判据");
        assertTrue(switcher.contains("public static boolean lxCovers(MusicPlatform platform)"),
                "同上：viaLx 上报必须复用这条，不要另写一套映射");
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
