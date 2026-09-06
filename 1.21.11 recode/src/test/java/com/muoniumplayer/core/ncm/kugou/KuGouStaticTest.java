package com.muoniumplayer.core.ncm.kugou;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酷狗官方源协议回归测试；只验证纯签名和源码协议不变量，不启动游戏也不访问网络。
 */
class KuGouStaticTest {

    private static final Path ROOT = ProjectPaths.sharedJava()
            .resolve("com/muoniumplayer/core/ncm/kugou");

    @Test
    @DisplayName("现代 tracker key 使用官方盐值和参数顺序")
    void trackerKey() {
        assertEquals("6bcaa8ccd72e4fdec2c9d230b2838d8f",
                KuGouSign.trackerKey("b3a52a7a958bf0aed0ebfba2e9a818b7",
                        "326520237810356861193053821996082887759",
                        "1190620308", "1005"));
    }

    @Test
    @DisplayName("取流主链包含 v6 priv_url 和 tracker_url，并兼容 data item status")
    void modernPlaybackProtocol() throws Exception {
        String source = read("KuGouMusicApi.java");
        assertTrue(source.contains("/v6/priv_url"), "必须使用当前官方现代取流接口");
        assertTrue(source.contains("tracker_url"), "必须读取现代接口返回的 tracker_url");
        assertTrue(source.contains("item.has(\"status\")"),
                "data[0].status=0 不能被误判为播放失败");
        assertTrue(source.contains("is_free_part"), "会员歌曲需要保留试听段回退");
    }

    @Test
    @DisplayName("设备指纹必须注册并持久化")
    void deviceRegistrationProtocol() throws Exception {
        String source = read("KuGouDevice.java");
        assertTrue(source.contains("ensureRegistered"));
        assertTrue(source.contains("r_register_dev"));
        assertTrue(source.contains("dfid_registered"));
        assertTrue(source.contains("root.addProperty(\"dfid\", dfid)"));
    }

    @Test
    @DisplayName("旧取流接口只能作为最后兜底")
    void legacyPlaybackIsFallback() throws Exception {
        String source = read("KuGouMusicApi.java");
        int modern = source.indexOf("resolveViaTracker(track, hash, false)");
        int legacy = source.indexOf("return resolveViaLegacyCdn(track, hash)");
        assertTrue(modern >= 0 && legacy > modern,
                "现代 v6 取流必须先于已经不稳定的 /i/v2/ 兜底");
    }

    private static String read(String file) throws Exception {
        Path path = ROOT.resolve(file);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}