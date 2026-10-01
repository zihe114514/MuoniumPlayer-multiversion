package com.muoniumplayer.core.settings;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置与运行时数据的落盘位置不变量。
 *
 * <p>这里只读源码，不加载 {@link ConfigPaths}：它的静态迁移逻辑会真的移动工作目录里的
 * 旧文件，单元测试不能产生这种副作用。</p>
 */
class ConfigPathLayoutStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("所有 mod 私有文件都挂在 config/muonium 下")
    void modFilesUseUnifiedConfigDirectory() throws IOException {
        String source = read(shared("ConfigPaths.java"));

        assertTrue(source.contains("CONFIG_DIRECTORY = new File(\"config\", \"muonium\")"),
                "必须有一个唯一的 config/muonium 根目录");
        assertTrue(source.contains("HUD = new File(CONFIG_DIRECTORY, \"hud.json\")"),
                "HUD 配置必须落在 config/muonium/hud.json");
        assertTrue(source.contains("PLAYER = new File(CONFIG_DIRECTORY, \"player.json\")"),
                "播放器配置必须落在 config/muonium/player.json");
        assertTrue(source.contains("THEME = new File(CONFIG_DIRECTORY, \"theme.json\")"),
                "主题配置必须落在 config/muonium/theme.json");
        assertTrue(source.contains("NETEASE_COOKIE = new File(CONFIG_DIRECTORY, \"netease_cookie.txt\")"),
                "旧版网易云 Cookie 兜底也必须进入统一配置目录");
        assertTrue(source.contains("MUSIC_CACHE = new File(CACHE_DIR, \"music\")"),
                "音频缓存必须在 mod 私有 cache 目录下，不能留在游戏根目录");
        assertTrue(source.contains("SKIJA_NATIVE_ROOT = new File(CONFIG_DIRECTORY, \"native/skija\")"),
                "Skija 原生库兜底解压目录不能留在游戏根目录");
    }

    @Test
    @DisplayName("旧版根目录文件首次启动时迁移且不覆盖新文件")
    void legacyRootArtifactsAreMigrated() throws IOException {
        String source = read(shared("ConfigPaths.java"));

        assertTrue(source.contains("migrate(new File(\"muoniumplayer_hud.json\"), HUD)"));
        assertTrue(source.contains("migrate(new File(\"muoniumplayer_config.json\"), PLAYER)"));
        assertTrue(source.contains("migrate(new File(\"muoniumplayer_theme.json\"), THEME)"));
        assertTrue(source.contains("migrate(new File(\"NCMCookie.txt\"), NETEASE_COOKIE)"));
        assertTrue(source.contains("migrateDirectory(new File(\"MusicCache\"), MUSIC_CACHE)"));
        assertTrue(source.contains("target.exists()"),
                "已有新文件必须胜出，不能被旧备份覆盖");
    }

    @Test
    @DisplayName("调用方不直接拼游戏根目录路径")
    void callersUseCentralRegistry() throws IOException {
        String cloud = stripComments(read(cloudSource()));
        assertTrue(cloud.contains("ConfigPaths.NETEASE_COOKIE"));
        assertTrue(cloud.contains("MusicAudioCache.directory()"));
        assertFalse(cloud.contains("new File(\"NCMCookie.txt\")"));
        assertFalse(cloud.contains("new File(\"MusicCache\")"));

        String cache = stripComments(read(cacheSource()));
        assertTrue(cache.contains("ConfigPaths.MUSIC_CACHE"));
        assertFalse(cache.contains("new File(\"MusicCache\")"));

        String ffmpeg = stripComments(read(ffmpegSource()));
        assertTrue(ffmpeg.contains("ConfigPaths.BIN_DIR"));

        String skija = stripComments(read(skijaSource()));
        assertTrue(skija.contains("ConfigPaths.SKIJA_NATIVE_ROOT"));

        String jsonStorage = read(shared("JsonConfigStorage.java"));
        assertTrue(jsonStorage.contains("Files.createDirectories(parent.toPath())"),
                "写嵌套配置文件前必须创建父目录");
    }

    private static Path shared(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "settings", fileName));
    }

    private static Path cloudSource() {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "CloudMusic.java"));
    }

    private static Path cacheSource() {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "MusicAudioCache.java"));
    }

    private static Path ffmpegSource() {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "FfmpegSupport.java"));
    }
    private static Path skijaSource() {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "render", "skija", "SkijaNatives.java"));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
