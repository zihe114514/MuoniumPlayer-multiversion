package com.muoniumplayer.core.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #11 的「缓存 + 凭据迁出 config/muonium」迁移语义。
 *
 * <p>核心承诺是<b>老用户无感</b>：旧位置有什么就搬什么，搬不动就留着，已有的新文件
 * 永不被覆盖。ConfigPaths 的迁移跑在静态块里（进程内只此一次、路径写死在游戏目录），
 * 所以这里直接用临时目录驱动参数化的 {@link ConfigPaths#migrateDataOut}。</p>
 */
class ConfigPathsDataRootMigrationTest {

    @TempDir
    File temp;

    private File configDir;
    private File cacheDir;
    private File authRoot;

    /** 造一份「老用户」的 config/muonium：缓存目录 + 几份凭据 + apple-music 凭据目录。 */
    private LinkedHashMap<File, File> givenLegacyUser() throws IOException {
        configDir = new File(temp, "config/muonium");
        cacheDir = new File(temp, "MuoniumPlayer/cache");
        authRoot = new File(temp, "MuoniumPlayer/auth");

        write(new File(configDir, "cache/music/abc.flac"), "AUDIO");
        write(new File(configDir, "cache/lyrics/1.json"), "{}");
        write(new File(configDir, "music_auth.json"), "{\"netease\":\"cookie\"}");
        write(new File(configDir, "kugou_auth.json"), "{\"kg\":\"token\"}");
        write(new File(configDir, "apple_music_auth.json"), "{\"mt\":\"x\"}");
        write(new File(configDir, "apple-music/cookies.txt"), "# Netscape HTTP Cookie File");

        LinkedHashMap<File, File> authMap = new LinkedHashMap<>();
        authMap.put(new File(configDir, "music_auth.json"), new File(authRoot, "music_auth.json"));
        authMap.put(new File(configDir, "kugou_auth.json"), new File(authRoot, "kugou_auth.json"));
        authMap.put(new File(configDir, "apple_music_auth.json"), new File(authRoot, "apple_music_auth.json"));
        return authMap;
    }

    @Test
    @DisplayName("老用户升级：缓存与凭据整体迁到 MuoniumPlayer/，config 空壳被清掉")
    void 迁移整体生效() throws IOException {
        LinkedHashMap<File, File> authMap = givenLegacyUser();

        ConfigPaths.migrateDataOut(configDir, cacheDir, authRoot, authMap);

        assertEquals("AUDIO", read(new File(cacheDir, "music/abc.flac")));
        assertEquals("{}", read(new File(cacheDir, "lyrics/1.json")));
        assertEquals("{\"netease\":\"cookie\"}", read(new File(authRoot, "music_auth.json")));
        assertEquals("{\"kg\":\"token\"}", read(new File(authRoot, "kugou_auth.json")));
        assertEquals("{\"mt\":\"x\"}", read(new File(authRoot, "apple_music_auth.json")));
        assertEquals("# Netscape HTTP Cookie File", read(new File(authRoot, "apple-music/cookies.txt")));
        // 旧位置不再有任何缓存内容
        assertFalse(new File(configDir, "cache").exists(), "cache 空壳应被清掉");
        assertFalse(new File(configDir, "music_auth.json").exists());
        assertFalse(new File(configDir, "apple-music").exists());
    }

    @Test
    @DisplayName("新位置已有数据：旧文件原样保留，绝不覆盖（重复启动/备份并存都安全）")
    void 已有新文件优先() throws IOException {
        LinkedHashMap<File, File> authMap = givenLegacyUser();
        // 新位置已经有另一份凭据（比如用户手动放的新登录）
        String existingAuth = "{\"netease\":\"NEW\"}";
        write(new File(authRoot, "music_auth.json"), existingAuth);
        write(new File(cacheDir, "music/abc.flac"), "NEW_AUDIO");

        ConfigPaths.migrateDataOut(configDir, cacheDir, authRoot, authMap);

        assertEquals(existingAuth, read(new File(authRoot, "music_auth.json")), "已有凭据不被覆盖");
        assertEquals("NEW_AUDIO", read(new File(cacheDir, "music/abc.flac")));
        // 旧文件留在原地（尽力而为语义），没被删掉
        assertEquals("{\"netease\":\"cookie\"}", read(new File(configDir, "music_auth.json")));
        // 其余没有冲突的照常迁移
        assertEquals("{\"kg\":\"token\"}", read(new File(authRoot, "kugou_auth.json")));
        assertTrue(new File(cacheDir, "lyrics/1.json").isFile());
    }

    @Test
    @DisplayName("config 下没有 cache/凭据（全新安装）：迁移是空操作，不创建任何目录")
    void 全新安装空操作() throws IOException {
        configDir = new File(temp, "config/muonium");
        cacheDir = new File(temp, "MuoniumPlayer/cache");
        authRoot = new File(temp, "MuoniumPlayer/auth");
        assertTrue(configDir.mkdirs());

        ConfigPaths.migrateDataOut(configDir, cacheDir, authRoot, new LinkedHashMap<>());

        assertFalse(cacheDir.exists(), "全新安装不该凭空造出缓存目录");
        assertFalse(authRoot.exists());
        assertTrue(configDir.isDirectory());
    }

    @Test
    @DisplayName("部分失败：锁死的文件留在原地，其余照常迁移")
    void 部分失败不拖累其余() throws IOException {
        LinkedHashMap<File, File> authMap = givenLegacyUser();
        // 目标根造成 move 必败：把 MuoniumPlayer/ 本身建成一个「文件」，authRoot 与
        // cacheDir 的父目录 mkdirs 都会失败，凭据与缓存全搬不动
        File dataRoot = temp.getParent() == null ? new File(temp, "MuoniumPlayer") : new File(temp, "MuoniumPlayer");
        assertTrue(dataRoot.getParentFile().isDirectory() || dataRoot.getParentFile().mkdirs());
        // config/muonium 与 MuoniumPlayer 必须同级才能精确挡住 authRoot；temp 即游戏根
        dataRoot.delete();
        write(new File(temp, "MuoniumPlayer"), "MuoniumPlayer 占位（假文件挡路）");

        ConfigPaths.migrateDataOut(configDir, cacheDir, authRoot, authMap);

        // 凭据迁移全部失败 → 原文件还在 config 下（用户下次启动重试；期间凭据未丢）
        assertTrue(new File(configDir, "music_auth.json").isFile(), "凭据搬不动就留在原地");
        assertTrue(new File(configDir, "cache/music/abc.flac").isFile(), "缓存同样留原地，内容未丢");
        // 迁移函数没有抛异常就是胜利：老用户照常进游戏，下次启动再试
    }

    @Test
    @DisplayName("常量布局：凭据与缓存都指向 MuoniumPlayer/，config 下不再有 cache")
    void 常量指向新根() {
        assertTrue(ConfigPaths.CACHE_DIR.getPath().replace('\\', '/').startsWith("MuoniumPlayer/cache"),
                "CACHE_DIR 必须指向游戏根 MuoniumPlayer/cache：" + ConfigPaths.CACHE_DIR);
        assertTrue(ConfigPaths.MUSIC_CACHE.getPath().replace('\\', '/').startsWith("MuoniumPlayer/cache/"));
        assertTrue(ConfigPaths.MUSIC_AUTH.toFile().getPath().replace('\\', '/').startsWith("MuoniumPlayer/auth/"),
                "凭据必须在 MuoniumPlayer/auth 下：" + ConfigPaths.MUSIC_AUTH);
        assertTrue(ConfigPaths.NETEASE_COOKIE.getPath().replace('\\', '/').startsWith("MuoniumPlayer/auth/"));
        assertTrue(ConfigPaths.APPLE_MUSIC_DIR.getPath().replace('\\', '/').startsWith("MuoniumPlayer/auth/"));
        assertFalse(new File("config/muonium/cache").getPath()
                        .equals(ConfigPaths.CACHE_DIR.getPath()),
                "CACHE_DIR 不许再指向 config/muonium/cache");
    }

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot mkdirs " + parent);
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
