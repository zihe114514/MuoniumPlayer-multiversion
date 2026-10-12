package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水后端安装目录「先清理、再落新」的回归测试。
 *
 * <p>这里测的是上一版的病根：安装与热补都只做<b>合并式覆盖</b>，新包里没有的文件
 * （改名前后的运行时 {@code node.exe}、上游删掉的脚本、更早装错层的残留）会永远
 * 留在目录里和新文件混着。现在装包会写下「当前包含哪些文件」的清单，清扫照清单删。</p>
 *
 * <p>三件必须成立的事，各自对应一类真机故障：</p>
 * <ol>
 *   <li><b>旧文件真的会被收掉</b>——否则磁盘被 87 MB 的旧运行时白占，用户也分不清
 *       哪个才是当前版本；</li>
 *   <li><b>用户数据一件都不能少</b>——{@code config.json} 里有设备身份与账号 Cookie，
 *       {@code cache/} 里是已解密的音频；误删等于让用户重新登录、缓存作废；</li>
 *   <li><b>没有清单的老安装不许乱删</b>——1.1.0 的安装目录里 {@code node.exe} 就是
 *       唯一的运行时，那时候还没有清单；清扫必须等到新名运行时真的在位才敢动它。</li>
 * </ol>
 *
 * <p>不联网、不启动 Minecraft：全是文件系统层面的事。</p>
 */
class QishuiInstallSweepTest {

    private Path sandbox;

    @BeforeEach
    void setUp() throws IOException {
        sandbox = Files.createTempDirectory("qishui-install-sweep-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(sandbox);
    }

    /** 造一个「装着当前包」的目录：清单列的就是这些文件。 */
    private File installedRoot(String... manifestEntries) throws IOException {
        File root = sandbox.resolve("qishui-backend").toFile();
        Files.createDirectories(root.toPath());
        for (String entry : manifestEntries) {
            writeFile(root, entry, "pack-content");
        }
        Files.write(new File(root, "pack.files.txt").toPath(),
                String.join("\n", manifestEntries).getBytes(StandardCharsets.UTF_8));
        return root;
    }

    private static void writeFile(File root, String relative, String content) throws IOException {
        File file = new File(root, relative.replace('/', File.separatorChar));
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.exists(root) == false) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言结论。
                }
            });
        }
    }

    @Test
    @DisplayName("清单里的文件一个不动，清单外的旧文件全部收掉——新旧不再混装")
    void sweepRemovesEverythingOutsideTheManifest() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe", "mssdk-bridge.mjs",
                "qishui-audio-decryptor/track-decryptor.js");
        // 上一版（1.1.0）留下的旧运行时，以及更早被删掉、新包里已经没有的脚本。
        writeFile(root, "node.exe", "old-runtime-87mb");
        writeFile(root, "legacy-helper.mjs", "removed-upstream");

        int removed = QishuiCompanionDownload.sweepStaleFiles(root);

        assertEquals(2, removed, "两个旧文件都该被收掉");
        assertFalse(new File(root, "node.exe").exists(), "改名前的旧运行时必须被收掉");
        assertFalse(new File(root, "legacy-helper.mjs").exists(), "新包里没有的旧脚本必须被收掉");
        assertTrue(new File(root, "server.mjs").isFile(), "清单里的文件不能动");
        assertTrue(new File(root, "qishui-backend.exe").isFile(), "清单里的文件不能动");
        assertTrue(new File(root, "qishui-audio-decryptor/track-decryptor.js").isFile(),
                "子目录里清单内的文件也不能动");
    }

    @Test
    @DisplayName("用户数据一件不动：config.json、cache/、backend.log 都在保留名单里")
    void sweepNeverTouchesUserData() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        writeFile(root, "config.json", "{\"device_id\":\"123\"}");
        writeFile(root, "config.json.tmp", "{}");
        writeFile(root, "backend.log", "log");
        writeFile(root, "pack.version", "qishui-backend-windows-x64-1.2.1.zip");
        writeFile(root, "qishui-cookie.txt", "sessionid=abc");
        writeFile(root, "cache/aabbcc__song.m4a", "decrypted-audio");
        writeFile(root, "cache/aabbcc.meta.json", "{}");

        int removed = QishuiCompanionDownload.sweepStaleFiles(root);

        assertEquals(0, removed, "保留名单里的东西一个都不该被删");
        assertTrue(new File(root, "config.json").isFile(), "设备身份与 Cookie 在 config.json 里，绝不能被清");
        assertTrue(new File(root, "cache/aabbcc__song.m4a").isFile(), "已解密的音频缓存不能被清");
        assertTrue(new File(root, "cache/aabbcc.meta.json").isFile(), "缓存的元数据要跟音频一起留下");
        assertTrue(new File(root, "qishui-cookie.txt").isFile(), "约定路径的 Cookie 文件要留下");
        assertTrue(new File(root, "backend.log").isFile(), "排查要用的日志不能被清掉");
    }

    @Test
    @DisplayName("清空后的空目录顺带收掉——上一版整个子目录没了时不留空壳")
    void sweepRemovesEmptiedDirectories() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        writeFile(root, "old-helper/only-file.js", "gone");

        QishuiCompanionDownload.sweepStaleFiles(root);

        assertFalse(new File(root, "old-helper").exists(),
                "子目录里全是旧文件时，目录本身也该收掉，别留一个空壳");
    }

    @Test
    @DisplayName("空的 cache/ 也要留着——它是用户数据目录，不是包内容")
    void sweepKeepsEmptyCacheDirectory() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        Files.createDirectories(new File(root, "cache").toPath());
        Files.write(new File(root, "cache").toPath().resolve(".keep"), new byte[0]);

        QishuiCompanionDownload.sweepStaleFiles(root);
        Files.deleteIfExists(new File(root, "cache/.keep").toPath());
        QishuiCompanionDownload.sweepStaleFiles(root);

        assertTrue(new File(root, "cache").isDirectory(),
                "缓存目录被清空后仍要留着：后端的 CACHE_DIR 就指在这里，"
                        + "空目录被收掉会让「用户数据目录」这个身份在下一次清扫里彻底消失");
    }

    @Test
    @DisplayName("没有清单的老安装只认「已知旧名单」，且新名运行时不在位时绝不删唯一的运行时")
    void legacyInstallWithoutManifestOnlyRemovesKnownStaleNames() throws IOException {
        // 1.1.0 的形态：没有 pack.files.txt，node.exe 就是唯一的运行时。
        File legacy = sandbox.resolve("legacy").toFile();
        Files.createDirectories(legacy.toPath());
        writeFile(legacy, "node.exe", "only-runtime");
        writeFile(legacy, "server.mjs", "pack-content");
        writeFile(legacy, "user-notes.txt", "user kept this");

        int removed = QishuiCompanionDownload.sweepStaleFiles(legacy);

        assertEquals(0, removed, "新名运行时还没到位时，动 node.exe 就是把用户装好的后端弄坏");
        assertTrue(new File(legacy, "node.exe").isFile(),
                "1.1.0 的安装里 node.exe 是唯一运行时，清单还没写下来就不能删");
        assertTrue(new File(legacy, "user-notes.txt").isFile(),
                "没有清单就不该做无依据的删除（只认已知旧名单）");
    }

    @Test
    @DisplayName("老安装升到新包后（新名运行时在位 + 清单已写）旧 node.exe 才会被收掉")
    void legacyRuntimeRemovedOnlyAfterUpgradeLands() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        writeFile(root, "node.exe", "old-runtime");

        int removed = QishuiCompanionDownload.sweepStaleFiles(root);

        assertEquals(1, removed, "新包已落地，旧运行时该收掉了");
        assertFalse(new File(root, "node.exe").exists(), "升级完成后 87 MB 的旧运行时不再留着");
    }

    @Test
    @DisplayName("待启用的 .new 暂存文件保留：它是热补成果，删了等于白升一次")
    void sweepKeepsStagedPromotions() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        writeFile(root, "qishui-backend.exe.new", "new-runtime");
        writeFile(root, "legacy-helper.mjs.new", "stale-leftover");

        QishuiCompanionDownload.sweepStaleFiles(root);

        assertTrue(new File(root, "qishui-backend.exe.new").isFile(),
                "正品在清单里 → 暂存文件是等旧进程退出后要启用的，不能删");
        assertFalse(new File(root, "legacy-helper.mjs.new").exists(),
                "正品不在清单里 → 这个 .new 是旧版残留，该跟着收掉");
    }

    @Test
    @DisplayName("暂存文件换正品：换成功后正品是新内容，换不掉时留到下一局")
    void promoteStagedFilesSwapsInTheNewCopy() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        writeFile(root, "qishui-backend.exe", "old-runtime-still-locked");
        writeFile(root, "qishui-backend.exe.new", "new-runtime");

        int promoted = QishuiCompanionDownload.promoteStagedFiles(root);

        assertEquals(1, promoted, "暂存文件该被启用成正品");
        assertEquals("new-runtime",
                Files.readString(new File(root, "qishui-backend.exe").toPath(), StandardCharsets.UTF_8),
                "启用后正品必须是新内容");
        assertFalse(new File(root, "qishui-backend.exe.new").exists(), "启用后暂存文件不该留着");
    }

    @Test
    @DisplayName("清单里的暂存文件不被启用：清单外的 .new 不冒充正品")
    void promoteIgnoresStagedFilesMissingFromManifest() throws IOException {
        File root = installedRoot("server.mjs", "qishui-backend.exe");
        writeFile(root, "legacy-helper.mjs.new", "stale");

        int promoted = QishuiCompanionDownload.promoteStagedFiles(root);

        assertEquals(0, promoted, "当前包里没有这个文件，它的 .new 不是待启用成果");
        assertTrue(new File(root, "legacy-helper.mjs.new").isFile(),
                "这一份留给清扫收掉，不要在这里顶成 legacy-helper.mjs");
        assertFalse(new File(root, "legacy-helper.mjs").exists(), "绝不能凭空造出清单外的正品文件");
    }

    @Test
    @DisplayName("清扫只碰安装目录本身，不动它的上级——config/muonium/ 根下有用户自己的文件")
    void sweepStaysInsideTheInstallRoot() throws IOException {
        File configRoot = sandbox.resolve("config-muonium").toFile();
        Files.createDirectories(configRoot.toPath());
        File root = new File(configRoot, "qishui-backend");
        Files.createDirectories(root.toPath());
        writeFile(root, "server.mjs", "pack-content");
        Files.write(new File(root, "pack.files.txt").toPath(),
                "server.mjs".getBytes(StandardCharsets.UTF_8));
        // 上级目录里同名的东西：用户自己放的，或者是别的功能（字体、模型、别的音源）的文件。
        writeFile(configRoot, "start.bat", "user's own script");
        writeFile(configRoot, "README.txt", "user's own notes");
        writeFile(configRoot, "kugou_auth.json", "other source's auth");

        QishuiCompanionDownload.sweepStaleFiles(root);

        assertTrue(new File(configRoot, "start.bat").isFile(),
                "清扫只该动安装目录内部；config/muonium/ 根下的同名文件不是我们的残留");
        assertTrue(new File(configRoot, "README.txt").isFile(), "用户自己的说明文件不能碰");
        assertTrue(new File(configRoot, "kugou_auth.json").isFile(), "别的音源的凭据不能碰");
    }
}
