package com.muoniumplayer.core.ncm.kugou;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.muoniumplayer.testsupport.ProjectPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酷狗概念版的<b>界面接线</b>与 {@code /v6} 备选取流的静态回归。
 *
 * <p>与 {@code KuGouStaticTest}（取流与解析）、{@code KuGouConceptParityStaticTest}（身份轴与端点）
 * 分工不同：本文件只管<b>入口这条链路</b>——账号面板怎么选身份、门面怎么在两个源之间分派、
 * 音质菜单在什么条件下才允许置灰。这类东西的特点是「写错了界面照样能点，只是点到别处去」，
 * 单元测试抓不住，运行期也不报错，所以钉在源码结构上。</p>
 *
 * <p><b>为什么这些断言不带情绪</b>：概念版从没在真机上测过（只有标准版实测过），
 * 所以这里钉的是<b>「不把没验证的东西写成结论」</b>这一层的约束——比如 {@code /v6} 只许待在兜底里、
 * 酷狗房间查不到别人会员状态时界面不许显示「都是会员」。真机验过之后这些断言该改，
 * 但在改之前它们防的是「看起来像修好了」。</p>
 */
@DisplayName("酷狗概念版界面接线与备选取流")
class KuGouUiParityStaticTest {

    private static final Path KUGOU = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/kugou");
    private static final Path MUSIC = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/music");
    private static final Path SCREENS = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/screens/ncm");
    private static final Path PANELS = SCREENS.resolve("panels");

    /**
     * 读源文件并归一化换行。
     *
     * <p>本仓工作区的 EOL 是按文件混用的，四版本之间同一份文件的行尾也可能不同；
     * 静态回归比的是「这段结构还在不在」，掺进行尾风格就会出现「一个版本绿、另一个版本红」。</p>
     */
    private static String read(Path folder, String fileName) throws Exception {
        Path path = folder.resolve(fileName);
        if (!Files.isRegularFile(path)) throw new IllegalStateException("missing " + path);
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return text.replace("\r\n", "\n");
    }

    /** 取 [start, end) 之间的一段；找不到标记就直接失败，免得断言悄悄作用在整份文件上。 */
    private static String slice(String text, String start, String end, String what) {
        int from = text.indexOf(start);
        assertTrue(from > 0, what + " 的起点没找到：" + start);
        int to = text.indexOf(end, from);
        if (to < 0) to = text.length();
        assertTrue(to > from, what + " 的终点在起点之前");
        return text.substring(from, to);
    }

    // ──────────────────────────── 概念版扫码登录 ────────────────────────────

    @Test
    @DisplayName("账号面板对酷狗给出身份选择器，且只在没有登录时出现")
    void accountPanelOffersBothIdentities() throws Exception {
        String sheet = read(SCREENS, "AccountManagerOverlay.java");
        assertTrue(sheet.contains("kugouVariantPicker = platform == MusicPlatform.KUGOU && !loggedIn"),
                "身份选择器只在未登录时出现：已登录时该显示的是凭证所属身份，不是「下一次选谁」");
        assertTrue(sheet.contains("profileSegment(strip(feed, SEGMENT_BLOCK))"),
                "酷狗要有自己的分段控件，不能只加一个字段没有入口");
        assertTrue(sheet.contains("qqChannelPicker || kugouVariantPicker ? SEGMENT_BLOCK : 0.0"),
                "选择器占的那一段高度要算进版式，否则小窗口下二维码会压在按钮上");
    }

    @Test
    @DisplayName("扫码链路上把身份一路带到取码，QQ 那侧的行为一字不改")
    void identityReachesQrRequest() throws Exception {
        String sheet = read(SCREENS, "AccountManagerOverlay.java");
        assertTrue(sheet.contains("NeteaseMusicService.createQrCode(platform, kuGouConcept)"),
                "取码必须带上这一次选的身份");
        assertTrue(sheet.contains("final boolean kuGouConcept = platform == MusicPlatform.KUGOU && kuGouConceptLogin;"),
                "身份在发请求之前定一次即可，轮询期间不重读选择器");
        // 概念版换身份要立刻重取码；QQ 那侧仍然是「一条会话正在跑就不换链路」。
        assertTrue(sheet.contains("if (concept == kuGouConceptLogin) return true;"),
                "点同一半不该重开会话");
        assertTrue(sheet.contains("if (requestRunning) return false;"),
                "QQ 的守卫要留着：换链路是跨会话误用的源头");
        assertTrue(sheet.contains("restartKuGouQr()"),
                "酷狗换身份要作废当前这张码");
    }

    @Test
    @DisplayName("概念版优先用上游回的二维码图，标准版仍是本地生成")
    void conceptUsesUpstreamQrImage() throws Exception {
        String cadence = read(MUSIC, "NeteaseMusicService.java");
        String branch = slice(cadence, "if (platform == MusicPlatform.KUGOU) {",
                "initialize(OptionsUtil.getCookie());", "酷狗取码分支");
        assertTrue(branch.contains("KuGouMusicService.createQrSession("),
                "要走带身份的会话，不能再用不带身份的 createQrKey()");
        assertTrue(branch.contains("if (kuGouConcept && !isBlank(session.imageDataUrl))"),
                "只有概念版改用上游的图：标准版那条实测能扫，不动它");

        String api = read(KUGOU, "KuGouMusicApi.java");
        String qr = slice(api, "public static String qrContent(String key, KuGouProfile profile)",
                "public static QrStatus checkQr(String key)", "按身份拼的二维码内容");
        assertTrue(qr.contains("profile == KuGouProfile.STANDARD) return qrContent(key)"),
                "标准版必须回到不带 appid 的那一条（实测能扫的形态）");
        assertTrue(qr.contains("qrTextAppId()"), "概念版要嵌自己的 appid");
    }

    @Test
    @DisplayName("二维码渲染：QQ 仍按平台走裸 base64，汽水改本地生成，酷狗按 data: 前缀")
    void qrTextureRoutingStaysCorrect() throws Exception {
        String sheet = read(SCREENS, "AccountManagerOverlay.java");
        // QQ 回的是<b>不带 data: 前缀</b>的 base64。只看前缀会把那段文本当成二维码内容去重画，
        // 画出来的码扫不出登录——这是一次「顺手把平台名单改成通用判断」就会踩到的坑。
        assertTrue(sheet.contains("boolean upstreamImage = platform == MusicPlatform.QQ"),
                "QQ 必须仍按平台写死走图片分支");
        assertTrue(sheet.contains("content.startsWith(\"data:image\")"),
                "其余平台按内容前缀判断，概念版才能复用同一条渲染路径");
        // 汽水现在拿的是外部工具给的 https:// 扫码链接，由界面自己画成码；把它留在图片分支里
        // 会把那条链接当成 base64 去解码，连码都出不来。
        assertTrue(sheet.contains("汽水现在走后者"), "汽水改走本地生成这件事要有注释说明");
    }

    @Test
    @DisplayName("token 粘贴登录入口带身份，且失败不覆盖当前会话")
    void tokenLoginIsWired() throws Exception {
        String sheet = read(SCREENS, "AccountManagerOverlay.java");
        assertTrue(sheet.contains("this::showKuGouTokenLogin"), "酷狗详情页要有 token 入口");
        assertTrue(sheet.contains("KuGouMusicService.loginWithToken(value, userId, concept[0])"),
                "提交时要把这一页选的身份传下去");
        assertTrue(sheet.contains("校验失败：不是 App 侧 token、已失效，或签发客户端选错"),
                "文案要写出「身份选错」这一种可能，否则用户会拿对的东西反复试");
        String service = read(MUSIC, "KuGouMusicService.java");
        assertTrue(service.contains("public static boolean loginWithToken(String token, String userId, boolean concept)"),
                "宿主层的 token 登录入口按身份分流");
    }

    @Test
    @DisplayName("已登录时提示语跟着凭证身份走，不跟着选择器走")
    void loggedInTextFollowsCredential() throws Exception {
        String sheet = read(SCREENS, "AccountManagerOverlay.java");
        String name = slice(sheet, "private String scanClientName(MusicPlatform platform)",
                "private String identityMeta(MusicPlatform platform)", "扫码提示文案");
        assertTrue(name.contains("KuGouMusicService.isConceptProfile()"),
                "已登录时报的是凭证所属身份");
        assertTrue(name.contains("kuGouConceptLogin"),
                "未登录时报的是这一次要扫的身份");
        String meta = slice(sheet, "private String identityMeta(MusicPlatform platform)",
                "private boolean isAccountVip(MusicPlatform platform)", "身份副标题");
        assertTrue(meta.contains("KuGouMusicService.getProfileLabel()"),
                "详情页副标题要显示真实身份，不能一直是平台名");
    }

    // ──────────────────────────── /v6 备选取流 ────────────────────────────

    @Test
    @DisplayName("/v6 的写死参数照抄参考实现，不许「顺手改成跟随身份」")
    void privUrlKeepsReferenceLiterals() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        String priv = slice(api, "public static Stream privUrl(Track track, KuGouQuality quality)",
                "/** 一次递归找到的候选地址", "privUrl");
        assertTrue(priv.contains("\"priv_vip_type\", \"6\""), "priv_vip_type 是写死的 6");
        assertTrue(priv.contains("\"pid\", \"411\""), "pid 是写死的 411（不是身份的 streamPid）");
        assertTrue(priv.contains("\"collect_list_id\", \"3\""), "collect_list_id 写死 3");
        // key 的盐值是参考实现里写死的那把概念版取流盐；appid 却是当前身份的。
        // 这个「混合」形态正是本仓当初测出 20010 的样子——照抄不修正，改了就说不清测的是什么。
        assertTrue(priv.contains("KuGouProfile.CONCEPT.streamKeySalt()"),
                "key 的盐必须固定用概念版那把（参考实现不做 lite 条件）");
        assertTrue(priv.contains("profile.appId()"), "同一段里的 appid 跟身份走");
        assertFalse(priv.contains("profile.streamKeySalt()"),
                "不许换成当前身份的盐：那等于凭空发明一种参考实现没有的形态");
    }

    @Test
    @DisplayName("/v6 直连 tracker、不带 x-router，qualities 九档按原顺序")
    void privUrlRequestShape() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        String priv = slice(api, "public static Stream privUrl(Track track, KuGouQuality quality)",
                "/** 一次递归找到的候选地址", "privUrl");
        assertTrue(priv.contains("postJson(KuGouHttp.TRACKER, \"/v6/priv_url\""),
                "端点与域名按参考实现：tracker 直连");
        assertFalse(priv.contains("router(\""), "/v6 不带 x-router（那是 gateway 的转发表）");
        assertTrue(priv.contains("KuGouHttp.Sign.ANDROID"), "签名用 Android 那一套（跟随身份）");
        String qualities = slice(api, "private static final String[] PRIV_URL_QUALITIES", "public static Stream privUrl",
                "qualities 数组");
        assertTrue(qualities.contains("\"128\", \"320\", \"flac\", \"high\", \"multitrack\", \"viper_atmos\", "
                        + "\"viper_tape\", \"viper_clear\", \"super\""),
                "九档的内容与顺序都是签名摘要的一部分");
        String http = read(KUGOU, "KuGouHttp.java");
        assertTrue(http.contains("TRACKER = \"http://tracker.kugou.com\""),
                "参考实现用的就是 http，照搬不改");
    }

    @Test
    @DisplayName("备选取流也要核对交付 hash，判据与主路径一致")
    void privUrlStillVerifiesDelivery() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        String pick = slice(api, "private static Stream pickPrivStream(", "/** 节点里最像", "候选挑选");
        assertTrue(pick.contains("deliveredHash("), "交付身份核对不能少：这一条同样可能换文件");
        assertTrue(pick.contains("delivered.equals(hash)"), "比对的是请求的那一个 hash");
        assertFalse(pick.contains("std_hash"), "判据永远不许改用 std_hash");
    }

    @Test
    @DisplayName("开关默认关，且只挂在酷狗账号页")
    void privUrlSwitchDefaultsOff() throws Exception {
        String config = read(SCREENS, "NCMPlayerConfig.java");
        assertTrue(config.contains("private static boolean kuGouPrivUrlFallback;"),
                "默认值就是 false：没写初值等于关着");
        assertTrue(config.contains("if (kuGouPrivUrlFallback) object.addProperty(\"kuGouPrivUrlFallback\", true)"),
                "关着的时候不落盘一个恒为 false 的键");
        String sheet = read(SCREENS, "AccountManagerOverlay.java");
        assertTrue(sheet.contains("kuGouPrivUrlAction(dialog"), "入口在酷狗账号页");
        assertTrue(sheet.contains("setKuGouPrivUrlFallbackEnabled(enabled)"), "入口真的会写开关");
    }

    // ──────────────────────────── 一起听接线 ────────────────────────────

    @Test
    @DisplayName("房间面板不再直接叫网易云那套")
    void overlayGoesThroughTheFacade() throws Exception {
        String overlay = read(SCREENS, "ListenTogetherOverlay.java");
        assertFalse(overlay.contains("NeteaseListenTogetherService."),
                "面板里所有状态与动作都要经门面，否则酷狗房间永远看不见");
        assertTrue(overlay.contains("ListenTogetherService.ownedByKuGou()"),
                "邀请码格式这类文案要按源分开");
        assertTrue(overlay.contains("ListenTogetherService.backendLabel()"),
                "标题与提示要说明这是谁家的房间");
    }

    @Test
    @DisplayName("归属先看「谁正开着房间」，再退回当前音源")
    void roomOwnershipIsSticky() throws Exception {
        String facade = read(MUSIC, "ListenTogetherService.java");
        String owner = slice(facade, "public static boolean ownedByKuGou()", "/** 界面上该说这是谁的一起听",
                "归属判据");
        int kugouActive = owner.indexOf("KuGouListenTogetherService.isActive()");
        int neteaseActive = owner.indexOf("NeteaseListenTogetherService.isActive()");
        int platform = owner.indexOf("getCurrentPlatform() == MusicPlatform.KUGOU");
        assertTrue(kugouActive > 0 && neteaseActive > kugouActive && platform > neteaseActive,
                "顺序必须是「谁开着房间 → 当前音源」：进房之后换源不该把会话搬到另一套上");
    }

    @Test
    @DisplayName("酷狗查不到他人会员状态，界面上就不许显示成「都是会员」")
    void vipUnknownIsNotVipFree() throws Exception {
        String facade = read(MUSIC, "ListenTogetherService.java");
        assertTrue(facade.contains("return !ownedByKuGou() && NeteaseListenTogetherService.hasNonVipMember();"),
                "酷狗房间恒为 false，含义是「不知道」");
        assertTrue(facade.contains("ownedByKuGou() ? Collections.<String>emptyList()"),
                "非会员名单同理返回空");
        // 只看方法声明与调用，不看散文：类注释里正好要解释「为什么不提供 shouldHostSkip」，
        // 用裸名字判断会把那段解释本身当成违规。
        assertFalse(facade.contains("shouldHostSkip("),
                "没有对等能力就不在门面上假造一个：那会让酷狗房间看起来做了这件事");
    }

    @Test
    @DisplayName("酷狗房间方法是同步的，门面必须挪到后台线程")
    void facadeHidesSynchronousNetwork() throws Exception {
        String facade = read(MUSIC, "ListenTogetherService.java");
        assertTrue(facade.contains("runInBackground(\"创建房间\""), "建房不许在渲染回调里同步打网络");
        assertTrue(facade.contains("runInBackground(\"加入房间\""), "加入同理");
        assertTrue(facade.contains("if (pendingAction) return;"), "连点两下不该开出两条会话");
        assertTrue(facade.contains("if (pendingAction && (phase == Phase.IDLE || phase == Phase.FAILED))"),
                "后台动作期间界面要显示「连接中」，否则用户以为没点上");
        assertTrue(facade.contains("throw new IllegalArgumentException"),
                "两侧枚举常量名对不上时必须炸出来，不许静默退到默认值");
    }

    @Test
    @DisplayName("播放条上那颗入口对酷狗开放")
    void controlsBarGateIncludesKuGou() throws Exception {
        String bar = read(PANELS, "ControlsBar.java");
        assertTrue(bar.contains("boolean kugou = currentPlatform == MusicPlatform.KUGOU;"),
                "酷狗要有自己的入口判定");
        assertTrue(bar.contains("boolean usable = (netease || kugou || MpPartyClient.serverPresent()) && fits;"),
                "入口条件是「音源对得上」，不含登录态：没登录也要点得进去看到「去登录」");
        assertTrue(bar.contains("ListenTogetherService.isActive() || MpPartyClient.inRoom()"),
                "点亮判定走门面，两个源的房间都算");
    }

    // ──────────────────────────── 音质菜单 ────────────────────────────

    @Test
    @DisplayName("音质菜单只在探到结果之后才置灰，且探测不在每帧回调里")
    void qualityMenuDoesNotBlockRenderThread() throws Exception {
        String bar = read(PANELS, "ControlsBar.java");
        assertTrue(bar.contains("if (!qualityMenuOpen) refreshKuGouQualityOptions();"),
                "探测挂在「打开菜单」这一次用户动作上");
        assertFalse(bar.contains("refreshKuGouQualityOptions();\n                        double scale"),
                "不许出现在每帧渲染回调里");
        assertTrue(bar.contains("if (options == null) return false;"),
                "还没探到一律放行：置灰是结论，不能拿「不知道」当结论");
        assertTrue(bar.contains("!key.equals(kuGouQualityOptionsKey)) return false;"),
                "上一首的探测结果不许糊在这一首上");
        assertTrue(bar.contains("kuGouQualityOptions = null;\n        kuGouQualityOptionsKey = key;"),
                "换歌之后当场作废，不能等新探测回来才覆盖");
        assertTrue(bar.contains("MultiThreadingUtil.runAsync"), "探测在后台线程");
        assertTrue(bar.contains("CloudMusic.currentlyPlaying != playing) return;"),
                "回来时已经换歌就把这次结果丢掉");
        String service = read(MUSIC, "KuGouMusicService.java");
        assertTrue(service.contains("if (tiers.contains(of(quality))) result.add(quality);"),
                "宿主档 → 酷狗档的正向判定：没有 192k 这一档，higher 与 exhigh 同进同退是对的");
    }

    @Test
    @DisplayName("宿主音质折叠表保持既有映射")
    void hostQualityFoldingUnchanged() throws Exception {
        String service = read(MUSIC, "KuGouMusicService.java");
        String mapping = slice(service, "public static KuGouQuality of(Quality quality) {",
                "private static int tier(Quality quality)", "音质折叠");
        assertTrue(mapping.contains("case LOSSLESS:") && mapping.contains("KuGouQuality.FLAC"),
                "无损 → flac");
        assertTrue(mapping.contains("case HIRES:") && mapping.contains("KuGouQuality.HI_RES"),
                "Hi-Res → high（24bit 高品无损）");
        assertTrue(mapping.contains("case JYMASTER:") && mapping.contains("KuGouQuality.TAPE"),
                "母带 → 蝰蛇母带");
        assertEquals(-1, mapping.indexOf("return null"), "折叠表不许返回空：宁可就近高一档");
    }
}
