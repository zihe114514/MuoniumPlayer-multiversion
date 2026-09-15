package com.muoniumplayer.core.ncm.kugou;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酷狗官方源协议回归测试；只验证纯签名和源码协议不变量，不启动游戏也不访问网络。
 *
 * <p>这些断言的意义在于「钉住协议事实」：签名公式、必需的参数名、端点的先后顺序，全都是
 * 实测出来的、写错时上游只会回一个笼统的 {@code error_code} 而不报错。没有测试的话，
 * 一次「顺手整理」就可能把 {@code plat=2} 这类必需参数删掉。</p>
 */
class KuGouStaticTest {

    private static final Path ROOT = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/kugou");

    @Test
    @DisplayName("Android 签名按 key 排序后拼接并夹盐值")
    void androidSignature() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("b", "2");
        params.put("a", "1");
        // MD5("OIlwieks28dk2k092lksi2UIkp" + "a=1b=2" + "" + "OIlwieks28dk2k092lksi2UIkp")
        assertEquals("b97698f1a4fbf02b349ca851b5dce224", KuGouSign.android(params, ""));
        assertEquals(KuGouSign.android(params, ""), KuGouSign.android(params, null),
                "空请求体与 null 请求体必须给出同一个签名");
        // 顺序无关：参数表本身的插入顺序不参与签名。
        Map<String, String> reversed = new LinkedHashMap<String, String>();
        reversed.put("a", "1");
        reversed.put("b", "2");
        assertEquals(KuGouSign.android(params, ""), KuGouSign.android(reversed, ""));
    }

    @Test
    @DisplayName("Android 签名把请求体算进去，Web 签名排序的是拼好的串")
    void signatureVariants() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("keyword", "晴天");
        assertFalse(KuGouSign.android(params, "").equals(KuGouSign.android(params, "{\"a\":1}")),
                "请求体必须参与 Android 签名");
        assertFalse(KuGouSign.android(params, "").equals(KuGouSign.web(params)),
                "Android 与 Web 是两套盐值和两种排序对象，结果不能相同");
    }

    @Test
    @DisplayName("取流 key 使用标准版盐值与 userid 兜底 0")
    void urlKeyVector() {
        // MD5(hash + "57ae12eb6890223e355ccfcb74edf70d" + appid + mid + userid)
        assertEquals("cb9717cd316163aebd77036424e7e5fa",
                KuGouSign.urlKey("b3a52a7a958bf0aed0ebfba2e9a818b7",
                        "326520237810356861193053821996082887759", "0", "1005"));
        String hash = "b3a52a7a958bf0aed0ebfba2e9a818b7";
        String mid = "326520237810356861193053821996082887759";
        // 未登录时 userid 段是字面量 "0"，空串与 null 都要落到同一结果。
        assertEquals(KuGouSign.urlKey(hash, mid, "0", "1005"), KuGouSign.urlKey(hash, mid, "", "1005"));
        assertEquals(KuGouSign.urlKey(hash, mid, "0", "1005"), KuGouSign.urlKey(hash, mid, null, "1005"));
        // 已登录时 userid 参与签名，结果必须不同。
        assertFalse(KuGouSign.urlKey(hash, mid, "0", "1005")
                .equals(KuGouSign.urlKey(hash, mid, "1372456480", "1005")));
    }

    @Test
    @DisplayName("mid 由 GUID 的 MD5 当作十六进制大整数转十进制")
    void midDerivation() {
        // 直接 Long.parseLong 会溢出，必须走 BigInteger。
        assertEquals("326520237810356861193053821996082887759",
                KuGouSign.mid("36d13101-b07e-48f0-9e1b-74b600d25ae6"));
    }

    @Test
    @DisplayName("搜索必须带 plat=2，缺它不是「少一个可选参数」而是直接失败")
    void searchRequiresPlatform() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("params.put(\"plat\", KuGouHttp.PLAT_ANDROID)"),
                "搜索端点缺 plat 会返回 error_code=152");
        assertTrue(source.contains("/v3/search/song"), "搜索必须走网关的新端点");
        assertTrue(source.contains("complexsearch.kugou.com"), "搜索需要 x-router 才能到后端服务");
    }

    @Test
    @DisplayName("取流走 v5/url，并且外层 clientver 用 tracker 协议版本")
    void modernPlaybackProtocol() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("/v5/url"), "v5/url 是唯一实测可用的取流端点");
        assertTrue(source.contains("STREAM_CLIENT_VER = \"11430\""),
                "取流端点要求 clientver=11430，不能用默认的 20489");
        assertTrue(source.contains("KuGouSign.urlKey("), "取流要带 key 参数");
        assertTrue(source.contains("\"fail_process\""),
                "会员曲会返回 status=2 + fail_process，需要能被识别为「需要会员」");
    }

    @Test
    @DisplayName("已失效的 v6/priv_url 与设备注册链路不再被调用")
    void deadEndpointsAreGone() throws Exception {
        // 断言只看「代码里有没有真的引用它」，不看散文：KuGouDevice 的类注释里会解释
        // 「为什么放弃了设备注册」，用 contains 判断会把那段解释本身当成违规。
        // 所以匹配的是端点路径字面量与调用形式，而不是裸的接口名。
        String api = read("KuGouMusicApi.java");
        assertFalse(api.contains("\"/v6/priv_url\""),
                "v6/priv_url 恒返回 error_code=20010，不能再当主路径");
        assertFalse(api.contains("tdrk.kugou.com") || api.contains("trackercdngz"),
                "授权接口同样不可用，不应保留调用");
        String device = read("KuGouDevice.java");
        assertFalse(device.contains("\"/risk/v2/r_register_dev\""),
                "设备注册端点在本项目里恒失败，不应再调用");
        assertFalse(device.contains("ensureRegistered()"),
                "设备注册已移除，dfid 改为本地生成");
    }

    @Test
    @DisplayName("老 trackercdn 只能作为最后兜底")
    void legacyPlaybackIsFallback() throws Exception {
        String source = read("KuGouMusicApi.java");
        int modern = source.indexOf("Stream stream = resolveViaGateway(track, candidate.hash, candidate.quality)");
        int legacy = source.indexOf("Stream stream = resolveViaLegacyCdn(track, candidate.hash, candidate.legacyBr)");
        assertTrue(modern >= 0 && legacy > modern,
                "老 /i/v2/ 只兜底免费曲，必须排在新 gateway 之后");
    }

    @Test
    @DisplayName("音质阶梯必须 hash 与 quality 成对取用")
    void qualityTiersStayPaired() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("addTier(tiers, track.hashLossless, \"flac\", \"flac\")"),
                "无损 hash 只能配 flac");
        assertTrue(source.contains("addTier(tiers, track.hash320, \"320\", \"hq\")"),
                "320 hash 只能配 320");
        assertTrue(source.contains("addTier(tiers, track.hash, \"128\", \"128\")"),
                "标准 hash 只能配 128");
    }

    @Test
    @DisplayName("榜单曲目要从 audio_info 取 hash，不能按搜索的扁平字段读")
    void rankTracksUseAudioInfo() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("parseAudioInfoTrack"), "榜单需要专门的解析器");
        int method = source.indexOf("private static Track parseAudioInfoTrack");
        int body = source.indexOf("object(item, \"audio_info\")", method);
        assertTrue(method > 0 && body > method, "榜单曲目必须从 audio_info 里读 hash");
    }

    @Test
    @DisplayName("专辑曲目要从 base/audio_info 分层结构读")
    void albumTracksAreLayered() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("object(item, \"base\")"), "专辑歌曲的元数据在 base 里");
        assertTrue(source.contains("/v1/album_audio/lite"), "专辑曲目端点");
    }

    @Test
    @DisplayName("写法不同的端点各自保留对应的必需参数")
    void requiredParametersSurvive() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("body.addProperty(\"slow_upload\", 1)"), "加曲缺 slow_upload 会静默失败");
        assertTrue(source.contains("body.addProperty(\"scene\", \"false;null\")"), "加曲需要 scene");
        assertTrue(source.contains("headers.put(\"kg-tid\", \"369\")"), "榜单曲目需要 kg-tid=369");
        assertTrue(source.contains("headers.put(\"kg-tid\", \"255\")"), "专辑曲目需要 kg-tid=255");
        assertTrue(source.contains("headers.put(\"kg-tid\", \"220\")"), "歌手单曲需要 kg-tid=220");
        // special_recommend 的 special_recommend 必须是对象，传字符串会 500。
        assertTrue(source.contains("body.add(\"special_recommend\", specialRecommend)"),
                "special_recommend 必须作为 JSON 对象放进请求体");
    }

    @Test
    @DisplayName("歌单删除保留 AES + RSA 的密钥信封")
    void playlistDeletionKeepsEnvelope() throws Exception {
        String crypto = read("KuGouCrypto.java");
        assertTrue(crypto.contains("AES/CBC/PKCS5Padding"));
        assertTrue(crypto.contains("RSA/ECB/PKCS1Padding"),
                "上游用的是 PKCS#1 v1.5，换成 OAEP 服务端解不开");
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("/v2/delete_list"));
        assertTrue(api.contains("toUpperCase(Locale.ROOT)"), "p 参数是大写十六进制");
    }

    @Test
    @DisplayName("凭证保留会员状态用于区分「需要会员」与「取流故障」")
    void vipStateIsPersisted() throws Exception {
        String device = read("KuGouDevice.java");
        assertTrue(device.contains("vip_type"), "会员等级要落盘");
        assertTrue(device.contains("isVip()"));
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("/v1/get_union_vip"), "会员状态查询端点");
    }

    private static String read(String file) throws Exception {
        Path path = ROOT.resolve(file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
