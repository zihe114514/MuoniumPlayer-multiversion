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
 * 概念版身份与本轮补齐能力的静态回归。
 *
 * <p>与 {@code KuGouStaticTest}（取流与解析）、{@code KuGouFeatureStaticTest}（动态封面 / 上报 /
 * 歌单写）分工：本文件只管<b>身份轴</b>与<b>新增端点</b>。钉的都是同一类东西——
 * 「写错了不会抛异常，只是上游静默不给数据」的协议事实，以及已经踩过一次的混用事故。</p>
 */
@DisplayName("酷狗概念版身份与新增能力")
class KuGouConceptParityStaticTest {

    private static final Path KUGOU = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/kugou");

    private static final Path MUSIC = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/music");

    private static String read(Path folder, String fileName) throws Exception {
        Path path = folder.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("missing " + path);
        }
        // 归一化换行：本仓工作区的 EOL 是按文件混用的（CloudMusic 是 LF、ConfigPaths 是 CRLF），
        // 而四版本之间同一份文件的行尾也可能不同。静态回归要比的是「协议字面量还在不在」，
        // 掺进换行风格就会在一个版本绿、另一个版本红。
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return text.replace("\r\n", "\n");
    }

    @Test
    @DisplayName("概念版四组常量必须成套：appid/签名盐/取流盐/取流页面标识")
    void conceptProfileIsComplete() throws Exception {
        String profile = read(KUGOU, "KuGouProfile.java");
        // 身份参数（对照 util/config.json 的 liteAppid / liteClientver）
        assertTrue(profile.contains("\"3116\""), "概念版 appid 是 3116");
        assertTrue(profile.contains("\"11440\""), "概念版 clientver 是 11440");
        // 两把盐（对照 util/helper.js 的 isLite 分支）
        assertTrue(profile.contains("LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"), "概念版 Android 签名盐");
        assertTrue(profile.contains("185672dd44712f60bb1736df5a377e82"), "概念版取流 key 盐");
        // 取流的三个页面标识
        assertTrue(profile.contains("967177915"), "概念版 page_id");
        assertTrue(profile.contains("356753938,823673182,967485191"), "概念版 ppage_id");
        // 标准版那一份一个都不能少：它是本仓所有既有实测的基础
        assertTrue(profile.contains("\"1005\"") && profile.contains("\"20489\""), "标准版身份要保留");
        assertTrue(profile.contains("OIlwieks28dk2k092lksi2UIkp"), "标准版签名盐");
        assertTrue(profile.contains("57ae12eb6890223e355ccfcb74edf70d"), "标准版取流 key 盐");
        assertTrue(profile.contains("151369488"), "标准版 page_id");
    }

    @Test
    @DisplayName("默认身份是标准版，凭证缺 profile 字段时也按标准版解释")
    void defaultProfileIsStandard() throws Exception {
        String profile = read(KUGOU, "KuGouProfile.java");
        // 旧安装落的凭证文件里没有 profile 字段，那些 token 都是标准版签发的。
        assertTrue(profile.contains("return STANDARD;"), "fromKey 的兜底必须是标准版");
        String device = read(KUGOU, "KuGouDevice.java");
        assertTrue(device.contains("KuGouProfile.fromKey(readString(root, \"profile\"))"),
                "身份要随凭证落盘，否则重启后请求会换一套盐");
        assertTrue(device.contains("root.addProperty(\"profile\", profile.key())"), "身份要写回去");
    }

    @Test
    @DisplayName("请求层的 appid/clientver 与签名都跟随当前身份，不残留写死的标准版值")
    void httpRequestFollowsProfile() throws Exception {
        String http = read(KUGOU, "KuGouHttp.java");
        assertTrue(http.contains("params.put(\"appid\", profile.appId())"), "deviceParams 用当前身份的 appid");
        assertTrue(http.contains("params.put(\"clientver\", profile.clientVer())"),
                "deviceParams 用当前身份的 clientver");
        assertTrue(http.contains("KuGouSign.android(copy, body, KuGouProfile.active())"),
                "Android 签名必须按当前身份选盐");
        String api = read(KUGOU, "KuGouMusicApi.java");
        assertTrue(api.contains("params.put(\"page_id\", profile.streamPageId())"),
                "取流的 page_id 来自身份");
        assertTrue(api.contains("params.put(\"pid\", profile.streamPid())"), "取流的 pid 来自身份");
        assertTrue(api.contains("params.put(\"ppage_id\", profile.streamPageIdChain())"),
                "取流的 ppage_id 来自身份");
        assertTrue(api.contains("KuGouSign.urlKey(hash, KuGouDevice.mid(), KuGouDevice.userId(), profile)"),
                "取流 key 用「同一身份」的盐与 appid");
    }

    @Test
    @DisplayName("动态封面仍是「与平台无关」的固定标准版盐，不能改成跟随身份")
    void dynamicCoverStaysPlatformAgnostic() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        // 实测结论：kmr CDN 的签名固定用标准版盐值。改成 active() 后概念版会话下会随机 sign error。
        assertTrue(api.contains("KuGouSign.android(raw, null)"),
                "动态封面用两参数版签名（固定标准版盐）");
        assertFalse(api.contains("KuGouSign.android(raw, null, KuGouProfile"),
                "动态封面不能改用跟随身份的签名");
    }

    @Test
    @DisplayName("取流的 clientver 恒为 11430，不随身份变")
    void streamClientVerIsNotProfileScoped() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        assertTrue(api.contains("STREAM_CLIENT_VER = \"11430\""), "tracker 协议版本仍是固定值");
        assertTrue(api.contains("params.put(\"clientver\", STREAM_CLIENT_VER)"), "取流外层 clientver 用它");
        assertFalse(api.contains("params.put(\"clientver\", profile.clientVer())"),
                "取流处不许把外层 clientver 换成身份值（会稳定失败）");
    }

    @Test
    @DisplayName("音质档位数组是协议字面量，母带/臻品系列一个都不能少")
    void qualityLadderIsComplete() throws Exception {
        String quality = read(KUGOU, "KuGouQuality.java");
        assertTrue(quality.contains("\"viper_atmos\"") && quality.contains("\"viper_clear\"")
                        && quality.contains("\"viper_tape\""), "臻品系列三档");
        assertTrue(quality.contains("\"super\"") && quality.contains("\"high\""), "母带与高品无损");
        assertTrue(quality.contains("\"multitrack\""), "权限探测的 qualities 数组要带多轨那一项");
        // 魔法音频不是音质档位，但要带 magic_ 前缀（对照 song_url.js 的白名单）
        assertTrue(quality.contains("\"piano\"") && quality.contains("\"acappella\"")
                && quality.contains("\"surnay\""), "魔法音频白名单");
        assertTrue(quality.contains("\"magic_\" + name"), "魔法音频要加 magic_ 前缀才有结果");
    }

    @Test
    @DisplayName("高档音质没有 hash 时不许拿低档 hash 冒充")
    void higherQualityMustNotReuseLowerHash() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        int method = api.indexOf("public static String hashFor(");
        assertTrue(method > 0, "按档取 hash 的入口要在");
        String body = api.substring(method, api.indexOf("\n    }\n", method));
        // (hash, quality) 成对授权：拿 128 的 hash 要 flac 直接 status=2，冒充只会白打请求。
        assertTrue(body.contains("return \"\";"), "探不到这一档就返回空，阶梯自动跳过");
    }

    @Test
    @DisplayName("privilege_lite：路由与三个必带开关，hash 判据是 32 位十六进制")
    void privilegeLiteContract() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        assertTrue(api.contains("/v2/get_res_privilege/lite"), "权限探测端点");
        assertTrue(api.contains("media.store.kugou.com"), "这条端点要 x-router");
        assertTrue(api.contains("need_hash_offset"), "三个开关少一个响应就缺字段");
        assertTrue(api.contains("support_verify"), "support_verify 要带");
        assertTrue(api.contains("text.length() != 32"), "认 hash 的判据是长度 32 的十六进制");
    }

    @Test
    @DisplayName("FM 模式只有 normal/small/peak 三种，负反馈动作与游标都不可少")
    void fmModesAndFeedback() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        assertTrue(api.contains("NORMAL(\"normal\"") && api.contains("SMALL(\"small\"")
                        && api.contains("PEAK(\"peak\""), "参考实现的 FmMode 就这三种");
        assertTrue(api.contains("\"garbage\"") && api.contains("\"cancel_garbage\""), "不感兴趣与撤销");
        assertTrue(api.contains("\"click_red\"") && api.contains("\"cancel_red\""), "红心与撤销");
        assertTrue(api.contains("cur_mark"), "反馈要回传最近一次推荐的游标，否则打到别批上");
        assertTrue(api.contains("persnfm.service.kugou.com"), "FM 的 x-router");
        assertTrue(api.contains("ca981cfc583a4c37f28d2d49000013c16a0a"), "fakem 固定值");
        assertTrue(api.contains("body.addProperty(\"vip_type\""),
                "不报会员等级时上游按非会员推，会员号会一直只拿到免费歌");
    }

    @Test
    @DisplayName("只有单曲走 /v3；专辑搜索的三个开关缺了会少回一半结果")
    void searchTypeContract() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        assertTrue(api.contains("SONG(\"song\", \"/v3/search/song\""), "单曲走 v3");
        assertTrue(api.contains("/v1/search/album") && api.contains("/v1/search/author")
                && api.contains("/v1/search/lyric"), "其余类型走 v1");
        assertTrue(api.contains("\"category\", \"1\"") && api.contains("\"sorttype\"")
                && api.contains("\"searchsong\""), "专辑搜索的三个开关");
        assertFalse(api.contains("\"user\"") && api.contains("/v1/search/user"),
                "酷狗没有按用户搜索这条端点，不许凭网易云的经验造一个");
    }

    @Test
    @DisplayName("收藏冷启动索引：按账号存、放在 cache 目录、稳定键用统一的小写 hash")
    void likedIndexContract() throws Exception {
        String cache = read(MUSIC, "KuGouLikedCache.java");
        assertTrue(cache.contains("root.addProperty(\"account\""), "索引要带账号，换账号不能沿用");
        assertTrue(cache.contains("ConfigPaths.KUGOU_LIKED"), "读写同一个文件");
        assertFalse(cache.contains("LIKED.clear();\n        if (LIKED.size()"), "满了不许整体清空");
        String paths = read(ProjectPaths.sharedJava().resolve("com/muoniumplayer/core/settings"),
                "ConfigPaths.java");
        assertTrue(paths.contains("KUGOU_LIKED = new File(CACHE_DIR"),
                "它是可再生缓存，所以放 cache 而不是配置目录");
        String music = read(MUSIC.resolve("dto"), "Music.java");
        int method = music.indexOf("public static String kuGouStableKey(");
        assertTrue(method > 0, "hash → 稳定键的换算要在");
        String body = music.substring(method, music.indexOf("\n    }\n", method));
        assertTrue(body.contains("toLowerCase(Locale.ROOT)"),
                "收藏端点给大写 hash，不统一大小写会算出两个稳定键");
        assertTrue(body.contains("stableLong(\"kugou:\" + value)"),
                "必须与 fromKuGouTrack 的 id 推导同一套");
    }

    @Test
    @DisplayName("扫码登录：请求 appid 固定 1001，二维码内容里嵌的才是身份 appid")
    void qrLoginSplitsTheTwoAppIds() throws Exception {
        String api = read(KUGOU, "KuGouMusicApi.java");
        assertTrue(api.contains("KuGouProfile.QR_REQUEST_APP_ID"), "请求参数用 1001");
        assertTrue(api.contains("used.qrTextAppId()"), "二维码内容嵌当前身份的 appid");
        assertTrue(api.contains("params.put(\"appid\", (profile == null ? KuGouProfile.STANDARD : profile).appId())"),
                "轮询状态也要带同一个身份的 appid");
        // 粘贴 token：校验失败必须回滚，不能把已登录的账号顶掉
        assertTrue(api.contains("KuGouDevice.saveLogin(previousToken, previousUserId"),
                "token 校验失败要回滚到原登录态");
    }

    @Test
    @DisplayName("宿主八档音质到酷狗档位的折叠方向：宁可高也不低")
    void qualityMappingKeepsParity() throws Exception {
        String service = read(MUSIC, "KuGouMusicService.java");
        assertTrue(service.contains("case SKY:") && service.contains("KuGouQuality.ATMOS"),
                "沉浸环绕声对应全景声");
        assertTrue(service.contains("case JYMASTER:") && service.contains("KuGouQuality.TAPE"),
                "至臻大师对应臻品母带");
        assertTrue(service.contains("case HIRES:") && service.contains("KuGouQuality.HI_RES"),
                "Hi-Res 对应高品无损");
        assertTrue(service.contains("case LOSSLESS:") && service.contains("KuGouQuality.FLAC"),
                "无损对应 flac");
        // 旧的三档折叠要留着当兜底，主链路改成按档
        assertTrue(service.contains("KuGouMusicApi.songUrl(track, target)"), "主链路按档位取流");
        assertTrue(service.contains("KuGouMusicApi.songUrl(track, tier(requestedQuality))"),
                "三档旧链路仍作为兜底，档位探测失败时不至于放不出");
    }

    @Test
    @DisplayName("身份是枚举而不是进程开关，也不给测试留文件依赖")
    void profileApiStaysExplicit() throws Exception {
        String sign = read(KUGOU, "KuGouSign.java");
        // 两参数版固定标准版盐：单元测试里复现确定哈希靠它，不读凭证文件。
        java.util.Map<String, String> params = new java.util.LinkedHashMap<String, String>();
        params.put("b", "2");
        params.put("a", "1");
        assertEquals("b97698f1a4fbf02b349ca851b5dce224", KuGouSign.android(params, ""),
                "两参数版签名的已知向量不能变（它不许改成跟随身份，否则测试要看凭证文件）");
        assertTrue(sign.contains("profile == null ? KuGouProfile.STANDARD : profile"),
                "跟随身份的签名入口对 null 要退回标准版");
    }
}
