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
 * 动态封面、听歌上报与歌单写操作的静态回归。
 *
 * <p>这些端点的共同点是「参数写错了不会报错，只是静默失败」，所以值得把已经验证过的
 * 协议事实钉在这里，避免以后重构时被"顺手优化"掉。</p>
 */
@DisplayName("酷狗新增能力（动态封面 / 上报 / 歌单写）")
class KuGouFeatureStaticTest {

    private static final Path ROOT = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/kugou");

    private static String read(String fileName) throws Exception {
        Path path = ROOT.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("missing " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 服务层在 {@code ncm/music} 下，不在 {@code ncm/kugou}。 */
    private static String readService(String fileName) throws Exception {
        Path path = ProjectPaths.sharedJava().resolve("com/muoniumplayer/core/ncm/music").resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("missing " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("动态封面：query 必须是 audioPlay，其它取值拿不到 dycover")
    void dynamicCoverQueryIsLiteral() throws Exception {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("\"audioPlay\""), "query 只有取字面量 audioPlay 才返回动态封面");
        assertTrue(api.contains("dycover"), "响应里的动态封面挂在 dycover 字段下");
        assertTrue(api.contains("h264_url"), "h264 是首选，h265 解码器支持面窄");
    }

    @Test
    @DisplayName("动态封面：CDN 只发 dfid/mid/clienttime 三个头，不能带 kg-* 系列")
    void dynamicCoverUsesBareHeaders() throws Exception {
        String http = read("KuGouHttp.java");
        assertTrue(http.contains("kmrCdnHeaders"), "需要有专门的最小头集方法");
        // mid 传字面量 "undefined" 是上游既有行为，实测可用。
        assertTrue(http.contains("\"undefined\""), "CDN 要求 mid 传字面量 undefined");
        assertTrue(http.contains("requestBare"), "必须能完全绕开默认头");
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("kmrCdnHeaders()"), "动态封面请求要走裸头路径");
    }

    @Test
    @DisplayName("CSCC：sty 只能是「手动」，auto 会被拒")
    void csccStyleMustBeManual() throws Exception {
        String reporter = read("KuGouCsccReporter.java");
        // 只看真正拼进事件字段的那一处：类注释里会解释「为什么不能用 auto」，
        // 用整文件 contains 判断会把那段解释本身当成违规。
        assertTrue(reporter.contains("fields.add(\"sty=手动\")"), "实测 sty=auto 返回 errcode=1203");
        assertFalse(reporter.contains("fields.add(\"sty=auto\")"), "auto 会被服务端拒绝");
        // 两个事件构造器都要带上它。
        int occurrences = reporter.split("fields\\.add\\(\"sty=手动\"\\)", -1).length - 1;
        assertEquals(2, occurrences, "start 与 end 事件各自都要写 sty");
    }

    @Test
    @DisplayName("CSCC：start 与 end 的 type_id 不同，时长挂在 end 的 duration 上")
    void csccEventTypesDiffer() throws Exception {
        String reporter = read("KuGouCsccReporter.java");
        assertTrue(reporter.contains("TYPE_ID_START = \"20431\""), "开始事件的 type_id");
        assertTrue(reporter.contains("TYPE_ID_END = \"4\""), "结束事件的 type_id");
        assertTrue(reporter.contains("\"duration=\""), "时长字段");
        assertTrue(reporter.contains("buildStartFields") && reporter.contains("buildEndFields"),
                "两个事件要分开构造");
    }

    @Test
    @DisplayName("CSCC：两把 RSA 公钥不能互换，gen 用的是概念版那把")
    void csccUsesLiteRsaKey() throws Exception {
        String crypto = read("KuGouCrypto.java");
        assertTrue(crypto.contains("LITE_PUBLIC_KEY_BASE64"), "概念版公钥要单独存在");
        assertTrue(crypto.contains("rsaEncryptLiteBase64"), "gen 要求 base64 而不是十六进制");
        assertTrue(crypto.contains("RSA/ECB/PKCS1Padding"), "填充是 PKCS#1 v1.5，不是 OAEP");
        String reporter = read("KuGouCsccReporter.java");
        assertTrue(reporter.contains("rsaEncryptLiteBase64"), "CSCC 握手要用概念版公钥");
    }

    @Test
    @DisplayName("CSCC：报文体密钥派生必须包含 field2，漏了会解不开")
    void csccSessionKeyIncludesField2() throws Exception {
        String reporter = read("KuGouCsccReporter.java");
        assertTrue(reporter.contains("session.field2"),
                "密钥是 MD5(uuid+clienttime+field2+serverstr)，field2 参与派生");
        assertTrue(reporter.contains("final String field2"), "会话要保住 field2");
    }

    @Test
    @DisplayName("CSCC 不上报毫秒级的时间戳，签名按字节拼")
    void csccSignsOverBytes() throws Exception {
        String sign = read("KuGouSign.java");
        assertTrue(sign.contains("md5(byte[] first, byte[] second)"),
                "密文是二进制，签名必须按字节拼而不是转字符串");
        String reporter = read("KuGouCsccReporter.java");
        assertTrue(reporter.contains("concatSignatureBase"), "前缀与密文分开传");
    }

    @Test
    @DisplayName("听歌历史：ot 是秒级，且 op/pc 都要给")
    void playHistoryUsesSeconds() throws Exception {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("/playhistory/v1/upload_songs"), "提交历史走这个端点");
        assertTrue(api.contains("1000L"), "ot 必须换算成秒");
        assertTrue(api.contains("\"mxid\"") && api.contains("\"op\"") && api.contains("\"pc\""),
                "mxid/op/ot/pc 是必填组合");
    }

    @Test
    @DisplayName("歌单写操作：加歌要带 slow_upload 与 scene，删歌按 fileid")
    void playlistWritesKeepRequiredFields() throws Exception {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("slow_upload"), "少了它会「成功」但歌单里看不到歌");
        assertTrue(api.contains("false;null"), "scene 同样是必需字段");
        assertTrue(api.contains("/cloudlist.service/v6/add_song"), "加歌端点");
        assertTrue(api.contains("/v4/delete_songs"), "删歌端点");
        assertTrue(api.contains("fileid"), "删歌按 fileid");
        assertTrue(api.contains("/v4/get_list_all_file_v3"),
                "fileid 只能从这个端点拿，枚举曲目的端点不返回它");
    }

    @Test
    @DisplayName("歌单写操作：新建 type=0、收藏 type=1，两者共用 add_list")
    void createAndCollectShareEndpoint() throws Exception {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("/cloudlist.service/v5/add_list"), "新建与收藏共用这个端点");
        assertTrue(api.contains("list_create_userid") && api.contains("list_create_listid"),
                "收藏时必须带上原歌单的主键三元组");
        assertTrue(api.contains("\"list_create_gid\""), "三元组里的 gid 也不能少");
    }

    @Test
    @DisplayName("add_list 不能带 x-router —— 同族端点里只有它走裸网关")
    void addListMustNotCarryRouter() throws Exception {
        String api = read("KuGouMusicApi.java");
        int submit = api.indexOf("private static boolean submitAddList");
        assertTrue(submit > 0, "新建与收藏应共用一个提交方法");
        String body = api.substring(submit, Math.min(api.length(), submit + 700));
        // 带上 x-router 会被 openresty 直接 404；同族的 get_all_list / get_list_all_file_v3
        // 反而必须带，方向相反，所以这条断言值得钉住。
        assertFalse(body.contains("router("),
                "add_list 传 x-router 会 404，必须留 null");
        assertTrue(body.contains("KuGouHttp.Sign.ANDROID"), "仍走标准 android 签名");
    }

    @Test
    @DisplayName("收藏歌单的主键要从 global_collection_id 里解，搜索结果里没有那三个字段")
    void collectParsesCollectionId() throws Exception {
        String service = readService("KuGouMusicService.java");
        assertTrue(service.contains("collectionCreatorUserId") && service.contains("collectionCreatorListId"),
                "收藏需要 userid 与 listid 两个数字");
        assertTrue(service.contains("split(\"_\")"),
                "它们编码在 collection_<type>_<userid>_<listid>_<n> 里");
    }

    @Test
    @DisplayName("加歌必须补齐 number/sort/size/timelen/bitrate，少一个就是 20010")
    void addSongNeedsFullResourceShape() throws Exception {
        String api = read("KuGouMusicApi.java");
        for (String field : new String[]{"\"number\"", "\"sort\"", "\"size\"", "\"timelen\"", "\"bitrate\""}) {
            assertTrue(api.contains(field), "add_song 的 data 项缺 " + field + " 会返回 20010");
        }
        // mixsongid / album_id 必须是数字，传字符串同样失败。
        assertTrue(api.contains("entry.addProperty(\"mixsongid\", number("),
                "mixsongid 要是数字");
        assertTrue(api.contains("entry.addProperty(\"album_id\", number("),
                "album_id 要是数字");
    }

    @Test
    @DisplayName("deletePlaylist 必须标注为未经证实，避免被当成可用功能")
    void deletePlaylistIsMarkedUnverified() throws Exception {
        String api = read("KuGouMusicApi.java");
        int index = api.indexOf("public static boolean deletePlaylist");
        assertTrue(index > 0);
        // 注释里必须写明该端点恒返回 20006 且已穷举验证过，否则后来者会重新踩坑。
        String doc = api.substring(Math.max(0, index - 2200), index);
        assertTrue(doc.contains("20006"), "要记录实测的错误码");
        assertTrue(doc.contains("未获证实") || doc.contains("不可用"),
                "要明确说明当前不可用");
    }

    @Test
    @DisplayName("取消收藏 / 删除歌单仍走 AES+RSA 信封")
    void deletionKeepsEnvelope() throws Exception {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("/v2/delete_list"), "删除端点");
        assertTrue(api.contains("playlistAesEncrypt") && api.contains("rsaEncrypt"),
                "请求体是 AES 密文，查询串里带 RSA 的 p");
        assertTrue(api.contains("playlistAesDecrypt"), "响应用同一个 aes.key 解");
    }

    @Test
    @DisplayName("「我喜欢」是 listid=2，主键从账号歌单列表里取")
    void likedPlaylistUsesListIdTwo() throws Exception {
        String api = read("KuGouMusicApi.java");
        assertTrue(api.contains("\"2\".equals(playlist.listId)"), "listid=2 就是「我喜欢」");
    }
}
