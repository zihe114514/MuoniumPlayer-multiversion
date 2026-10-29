package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 概念版 auth 取流链路的回归测试：只跑纯逻辑与源码协议不变量，不启动游戏也不访问网络。
 *
 * <p>与 {@link KuGouStaticTest} 同一立场：auth 链路的参数名、clientver、x-router、签名开关
 * 全部来自参考实现抓包，写错时上游只会静默不给数据。这里钉住两类事实——</p>
 * <ol>
 *   <li><b>行为</b>：{@code relate_goods} 的解析、ogg 交付的拦截；</li>
 *   <li><b>源码协议不变量</b>：{@code /tracker/v5/url} 不得带 Android 签名或 x-router、
 *       auth 端点的 clientver 是 11561、模块号是 51。</li>
 * </ol>
 */
class KuGouAuthStreamTest {

    private static final Path ROOT = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/kugou");

    // ── relate_goods 解析 ───────────────────────────────────────────────────

    @Test
    @DisplayName("relate_goods：privilege=0 归免费，非 0 归需会员，未知档位跳过")
    void parseRelateGoodsClassifiesPrivilege() {
        KuGouMusicApi.Privilege privilege = new KuGouMusicApi.Privilege();
        KuGouMusicApi.parseRelateGoods(privilegeNode("["
                + "{\"quality\":\"128\",\"privilege\":0},"
                + "{\"quality\":\"flac\",\"privilege\":10,\"pay_type\":3},"
                + "{\"quality\":\"viper_tape\",\"privilege\":1},"
                + "{\"quality\":\"brand_new_quality\",\"privilege\":0}"
                + "]"), privilege);

        assertEquals(Boolean.FALSE, privilege.vipLockedByQuality.get("128"), "privilege=0 是免费档");
        assertEquals(Boolean.TRUE, privilege.vipLockedByQuality.get("flac"), "非 0 一律按需会员");
        assertEquals(Boolean.TRUE, privilege.vipLockedByQuality.get("viper_tape"));
        assertFalse(privilege.vipLockedByQuality.containsKey("brand_new_quality"),
                "上游新扩的档位没有对应枚举，跳过而不是抛异常");
    }

    @Test
    @DisplayName("relate_goods 缺失或形态不对时静默留空，不抛异常")
    void parseRelateGoodsToleratesMissingOrOdd() {
        KuGouMusicApi.Privilege privilege = new KuGouMusicApi.Privilege();
        // 没有该字段的响应（老上游形态）
        KuGouMusicApi.parseRelateGoods(privilegeNode("{\"audio_info\":{}}"), privilege);
        assertTrue(privilege.vipLockedByQuality.isEmpty());

        // relate_goods 存在但条目缺 quality/privilege 字段
        KuGouMusicApi.parseRelateGoods(privilegeNode("[{\"hash\":\"abc\"},{\"quality\":\"128\"}]"), privilege);
        assertEquals(Boolean.FALSE, privilege.vipLockedByQuality.get("128"),
                "缺 privilege 字段按「不锁定」处理：跳档的代价（漏掉能播的档）高于多打一次被拒的请求");
        assertFalse(privilege.vipLockedByQuality.containsKey("hash"));
    }

    private static JsonObject privilegeNode(String relateGoodsJson) {
        JsonObject node = new JsonObject();
        if (relateGoodsJson.trim().startsWith("[")) {
            JsonArray array = JsonParser.parseString(relateGoodsJson).getAsJsonArray();
            node.add("relate_goods", array);
        } else {
            JsonObject inner = JsonParser.parseString(relateGoodsJson).getAsJsonObject();
            for (String key : inner.keySet()) node.add(key, inner.get(key));
        }
        return node;
    }

    // ── ogg 拦截 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ogg 交付按 extName 与地址后缀双重识别")
    void oggDetection() {
        assertTrue(KuGouAuth.isOggFormat("ogg", "https://example.com/a.mp3"), "extName 说了算");
        assertTrue(KuGouAuth.isOggFormat("OGG", "https://example.com/a.mp3"), "大小写无关");
        assertTrue(KuGouAuth.isOggFormat("", "https://example.com/a.ogg"), "地址后缀兜底");
        assertTrue(KuGouAuth.isOggFormat("", "https://example.com/a.ogg?sign=x"), "带查询串也算");
        assertFalse(KuGouAuth.isOggFormat("mp3", "https://example.com/a.mp3"));
        assertFalse(KuGouAuth.isOggFormat("flac", "https://example.com/a.flac?sign=x"));
        assertFalse(KuGouAuth.isOggFormat("", ""), "全空不误判");
    }

    // ── 源码协议不变量 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("auth 端点的 clientver 是 11561，与 /v5/url 的 11430、身份默认值三分")
    void authClientVerLiteral() {
        assertEquals("11561", KuGouAuth.AUTH_CLIENT_VER);
        assertEquals("11430", KuGouMusicApi.streamClientVer());
        String auth = read("KuGouAuth.java");
        assertTrue(auth.contains("params.put(\"clientver\", AUTH_CLIENT_VER)"),
                "song_auth 与 /tracker/v5/url 都要显式覆盖成 11561（身份默认是 11440）");
        assertTrue(auth.contains("\"/v1/user_verify\""));
        assertTrue(auth.contains("\"/v1/authorization\""));
        assertTrue(auth.contains("\"/tracker/v5/url\""));
    }

    @Test
    @DisplayName("auth 端点走 trackercdngz 网关路由，取流端点不带 x-router 但带 Android 签名")
    void authRouterLiterals() {
        String auth = read("KuGouAuth.java");
        assertTrue(auth.contains("trackercdngz.kugou.com"),
                "user_verify 与 authorization 的 x-router 都是 trackercdngz（对照 user_verify.js）");
        int streamCall = auth.indexOf("\"/tracker/v5/url\"");
        assertTrue(streamCall > 0);
        // 取流调用点必须带 ANDROID 签名且没有 router 参数：上游 song_url_auth.js 的 notSign 是
        // 死参数（request.js 只认 notSignature），签名照算；x-router 则真的没有。
        int signedCall = auth.indexOf("KuGouHttp.get(KuGouHttp.GATEWAY, \"/tracker/v5/url\", params, KuGouHttp.Sign.ANDROID, null)");
        assertTrue(signedCall > 0 && Math.abs(signedCall - streamCall) < 2000,
                "/tracker/v5/url 带 Android 签名、不带 x-router（对照 song_url_auth.js）");
    }

    @Test
    @DisplayName("auth 取流参数表的关键字面量一个都不能少")
    void authStreamParamsPresent() {
        String auth = read("KuGouAuth.java");
        for (String literal : new String[]{
                "\"module_id\", MODULE_ID", "\"need_ogg\", \"1\"", "\"ptype\", \"0\"",
                "\"mtype\", \"0\"", "\"need_m\", \"0\"", "\"open_time\"", "\"auth\"",
                "\"pidversion\", \"3001\"", "\"cdnBackup\", \"1\"", "\"ssa_flag\", \"is_fromtrack\"",
                "\"behavior\", \"play\"", "\"cmd\", \"26\""}) {
            assertTrue(auth.contains(literal), "缺参数 " + literal);
        }
        assertTrue(auth.contains("MODULE_ID = \"51\""), "module_id=51 来自概念版抓包");
        assertTrue(auth.contains("KuGouSign.urlKey(cleanHash, KuGouDevice.mid(), KuGouDevice.userId(), profile)"),
                "key 与 /v5/url 同一把身份盐：必须按当前身份算，不能写死标准版");
    }

    @Test
    @DisplayName("主路径接线：概念版身份 + 开关开着才走 auth，标准版零改动")
    void ladderWiring() {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("KuGouProfile.active() == KuGouProfile.CONCEPT"),
                "auth 主路径只在概念版身份下生效");
        assertTrue(api.contains("NCMPlayerConfig.isKuGouAuthStreamEnabled()"),
                "开关要真的接进阶梯判定");
        String ladder = methodBody(api, "public static Stream songUrl(Track track, KuGouQuality requested)",
                "private static boolean isVipLockedForCurrentAccount");
        int authCall = ladder.indexOf("KuGouAuth.resolveViaAuth");
        int gatewayCall = ladder.indexOf("resolveViaGateway");
        assertTrue(authCall >= 0 && gatewayCall > authCall,
                "同一档内 auth 先行、/v5/url 兜底");
        assertTrue(ladder.contains("isVipLockedForCurrentAccount(track, quality)"),
                "非会员的会员档要在打请求之前跳过");
        assertTrue(api.contains("locked == null || !locked || KuGouDevice.isVip()"),
                "跳档只对非会员生效：会员账号（含概念版 VIP）必须照常尝试会员档，"
                        + "否则付费歌会被跳档逻辑全灭");
    }

    @Test
    @DisplayName("配置开关默认开：只在关掉时写盘")
    void configSwitchDefaultsOn() throws Exception {
        Path config = ProjectPaths.sharedJava()
                .resolve("com/muoniumplayer/core/screens/ncm/NCMPlayerConfig.java");
        String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        assertTrue(text.contains("return !kuGouAuthStreamDisabled"),
                "默认没有这个键就是开着");
        assertTrue(text.contains("if (kuGouAuthStreamDisabled) object.addProperty(\"kuGouAuthStreamDisabled\", true)"),
                "save 镜像 kuGouPrivUrlFallback：默认开的键只在关掉时落盘");
    }

    private static String read(String file) {
        try {
            return new String(Files.readAllBytes(ROOT.resolve(file)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("missing " + file, e);
        }
    }

    /** 截取两个方法签名之间的源码段（不含结束签名），找不到时返回全文。 */
    private static String methodBody(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        if (start < 0) return source;
        int end = source.indexOf(endMarker, start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }
}
