package com.muoniumplayer.core.screens.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MuoniumConfig 启动时自更新的回归测试。
 *
 * <p>钉的是三件互相独立、但一起决定"更新能不能安全落地"的事：</p>
 *
 * <ol>
 *   <li><b>认包</b>（{@link MuoniumConfigJarProbe}）—— 删除是按文件做的，认错一次就是
 *       替玩家删掉别人的 mod。所以身份判定只能信 jar 内的 mod id，不能信文件名。</li>
 *   <li><b>认清单</b>（{@link MuoniumConfigIndex}）—— 清单是网络上来的，它决定"下哪个
 *       文件、放哪"。所以车道、文件名、字节数、sha256 每一项都必须硬校验，任一不合规
 *       就整份丢弃（退回内置兜底），绝不能"按半懂的字段去下载"。</li>
 *   <li><b>不降级</b> —— 玩家手装了更新的版本时，清单里更旧的版本不能把它覆盖掉。</li>
 * </ol>
 *
 * <p>这些测试不联网、不碰真实 mods/：{@link MuoniumConfigJarProbe} 与
 * {@link MuoniumConfigIndex#parse} 都被刻意做成纯函数/纯文件操作，就是为了能在这里钉住。</p>
 */
class MuoniumConfigUpdateStaticTest {

    // ==================== 一、认包：身份只信 mod 元数据 ====================

    @Test
    @DisplayName("按 mod id 认包：Fabric 元数据")
    void recognisesFabricJar(@TempDir Path dir) throws Exception {
        File jar = writeJar(dir, "whatever-name.jar", "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"muoniumconfigv1\",\"version\":\"1.7.0\","
                        + "\"depends\":{\"minecraft\":\"26.3.x\"}}");
        MuoniumConfigJarProbe.Info info = MuoniumConfigJarProbe.readInfo(jar);
        assertNotNull(info, "mod id 正确却没认出来：删除与更新都依赖这一步");
        assertEquals("1.7.0", info.version);
        assertEquals("26.3.x", info.minecraftRange);
    }

    @Test
    @DisplayName("按 mod id 认包：NeoForge 元数据")
    void recognisesNeoForgeJar(@TempDir Path dir) throws Exception {
        File jar = writeJar(dir, "MuoniumConfig-26.3-neoforge.jar", "META-INF/neoforge.mods.toml",
                "modLoader = \"javafml\"\n"
                        + "[[mods]]\n"
                        + "modId = \"muoniumconfigv1\"\n"
                        + "version = \"1.7.0\"\n"
                        + "displayName = \"MuoniumConfig\"\n");
        MuoniumConfigJarProbe.Info info = MuoniumConfigJarProbe.readInfo(jar);
        assertNotNull(info, "NeoForge 侧认不出包，1.21.1+ 的更新会整个失效");
        assertEquals("1.7.0", info.version);
    }

    @Test
    @DisplayName("按 mod id 认包：Forge 1.20.1 的 mods.toml")
    void recognisesForgeJar(@TempDir Path dir) throws Exception {
        File jar = writeJar(dir, "mc.jar", "META-INF/mods.toml",
                "modLoader = \"javafml\"\n"
                        + "[[mods]]\n"
                        + "modId = \"muoniumconfigv1\"\n"
                        + "version = \"1.6.1\"\n");
        MuoniumConfigJarProbe.Info info = MuoniumConfigJarProbe.readInfo(jar);
        assertNotNull(info);
        assertEquals("1.6.1", info.version);
    }

    @Test
    @DisplayName("别的 mod 就算撞了文件名也绝不认 —— 认错就是替玩家删他的东西")
    void refusesForeignModDespiteMatchingName(@TempDir Path dir) throws Exception {
        File jar = writeJar(dir, "muoniumconfig-9.9.9.jar", "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"9.9.9\"}");
        assertNull(MuoniumConfigJarProbe.readInfo(jar),
                "id 是别的 mod 却认成了 MuoniumConfig：更新时会把它删掉");
    }

    @Test
    @DisplayName("坏包 / 空包不抛异常，只是不认识")
    void survivesBrokenJar(@TempDir Path dir) throws Exception {
        File broken = dir.resolve("muoniumconfig-broken.jar").toFile();
        Files.write(broken.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
        assertNull(MuoniumConfigJarProbe.readInfo(broken));
        assertNull(MuoniumConfigJarProbe.readInfo(new File(dir.toFile(), "missing.jar")));
        assertTrue(MuoniumConfigJarProbe.scan(dir.toFile()).isEmpty(),
                "扫描遇到坏包不该抛，也不该把它算成已装");
    }

    @Test
    @DisplayName("扫 mods/ 只挑出 MuoniumConfig，且大小写无关")
    void scansOnlyOurJars(@TempDir Path dir) throws Exception {
        writeJar(dir, "MuoniumConfig-1.7.0.jar", "fabric.mod.json",
                "{\"id\":\"muoniumconfigv1\",\"version\":\"1.7.0\"}");
        writeJar(dir, "muoniumconfig-1.6.1.jar", "fabric.mod.json",
                "{\"id\":\"muoniumconfigv1\",\"version\":\"1.6.1\"}");
        writeJar(dir, "sodium.jar", "fabric.mod.json", "{\"id\":\"sodium\",\"version\":\"0.5\"}");
        Files.write(dir.resolve("readme.txt"), "x".getBytes(StandardCharsets.UTF_8));

        List<MuoniumConfigJarProbe.Info> found = MuoniumConfigJarProbe.scan(dir.toFile());
        assertEquals(2, found.size(), "应当只认出两个 MuoniumConfig：" + found);
        // 文件名排序稳定（大小写不敏感），便于日志与测试对拍。
        // 注意 'muoniumconfig' 与 'MuoniumConfig' 只差大小写，所以下一个字符决定先后：
        // '-' 与 '-' 相同后比 '1' vs '1'，再看 '1.6' < '1.7' —— 1.6.1 就排在前面。
        assertEquals("muoniumconfig-1.6.1.jar", found.get(0).file.getName());
        assertEquals("MuoniumConfig-1.7.0.jar", found.get(1).file.getName());
    }

    @Test
    @DisplayName("文件名像就够进候选，但真正的身份仍由 readInfo 判定")
    void nameFilterIsOnlyAPrefilter(@TempDir Path dir) {
        assertTrue(MuoniumConfigJarProbe.looksLikeName("muoniumconfig-1.7.0.jar"));
        assertTrue(MuoniumConfigJarProbe.looksLikeName("MuoniumConfig-26.3-fabric.jar"));
        assertTrue(MuoniumConfigJarProbe.looksLikeName("muonium_config.jar"));
        assertFalse(MuoniumConfigJarProbe.looksLikeName("sodium.jar"));
        assertFalse(MuoniumConfigJarProbe.looksLikeName("muoniumconfig-1.7.0.jar.txt"));
    }

    // ==================== 二、认清单：任一字段不合规就整份丢弃 ====================

    private static final String LANE = "26.3/fabric";
    private static final String SHA = "e13bd5458bd8d61d0ecf6fa60f534c299af067f44a7532aba41bb685f87e6dfe";

    private static String manifest(String overrides) {
        return "{\"schema\":1,\"id\":\"muoniumconfigv1\",\"lane\":\"26.3/fabric\","
                + "\"version\":\"1.8.0\",\"file\":\"muoniumconfig-1.8.0.jar\","
                + "\"bytes\":26123481,\"sha256\":\"" + SHA + "\","
                + "\"notes\":\"x\"" + overrides + "}";
    }

    @Test
    @DisplayName("合规清单能被解析出全部关键字段")
    void parsesValidManifest() {
        MuoniumConfigIndex.Entry entry = MuoniumConfigIndex.parse(manifest(""), LANE, "2.5.0");
        assertNotNull(entry, "合规清单被拒了：更新永远不会发生");
        assertEquals("1.8.0", entry.version);
        assertEquals("muoniumconfig-1.8.0.jar", entry.fileName);
        assertEquals(26123481L, entry.bytes);
        assertEquals(SHA, entry.sha256);
        assertTrue(entry.remote, "从远端清单读来的条目应标记 remote");
        assertTrue(entry.isUsable());
    }

    @Test
    @DisplayName("schema 不认就丢弃 —— 以后改结构才有退路")
    void rejectsUnknownSchema() {
        String body = manifest("").replace("\"schema\":1", "\"schema\":2");
        assertNull(MuoniumConfigIndex.parse(body, LANE, "2.5.0"));
    }

    @Test
    @DisplayName("id 不是 MuoniumConfig 就丢弃")
    void rejectsForeignId() {
        String body = manifest("").replace("\"muoniumconfigv1\"", "\"sodium\"");
        assertNull(MuoniumConfigIndex.parse(body, LANE, "2.5.0"));
    }

    @Test
    @DisplayName("车道对不上就丢弃 —— 装错版本会在启动期崩")
    void rejectsMismatchedLane() {
        // 26.3 的清单被放到 1.21.1 的目录下：宁可这次不装，也不能装一个必然崩的包
        assertNull(MuoniumConfigIndex.parse(manifest(""), "1.21.1/fabric", "2.5.0"));
    }

    @Test
    @DisplayName("文件名带路径就丢弃 —— 清单来自网络，不许它决定写到 mods/ 之外")
    void rejectsPathTraversal() {
        for (String evil : new String[]{"../muoniumconfig-1.8.0.jar",
                "sub/muoniumconfig-1.8.0.jar", "..\\evil.jar", "C:/evil.jar"}) {
            String body = manifest("").replace("muoniumconfig-1.8.0.jar", evil);
            assertNull(MuoniumConfigIndex.parse(body, LANE, "2.5.0"),
                    "带路径的文件名必须拒收：" + evil);
        }
    }

    @Test
    @DisplayName("不是 .jar 的文件名丢弃")
    void rejectsNonJarFile() {
        String body = manifest("").replace("muoniumconfig-1.8.0.jar", "muoniumconfig-1.8.0.exe");
        assertNull(MuoniumConfigIndex.parse(body, LANE, "2.5.0"));
    }

    @Test
    @DisplayName("bytes 或 sha256 缺失/非法就丢弃 —— 没有它们就不是可校验的下载")
    void rejectsUnverifiableEntry() {
        assertNull(MuoniumConfigIndex.parse(
                manifest("").replace("\"bytes\":26123481", "\"bytes\":0"), LANE, "2.5.0"));
        assertNull(MuoniumConfigIndex.parse(
                manifest("").replace(SHA, SHA.substring(0, 32)), LANE, "2.5.0"),
                "sha256 长度不足必须拒收");
        assertNull(MuoniumConfigIndex.parse(
                manifest("").replace(SHA, "z".repeat(64)), LANE, "2.5.0"),
                "sha256 非十六进制必须拒收");
    }

    @Test
    @DisplayName("minLoader 高于当前播放器时跳过本次更新（不装可能不兼容的新版）")
    void skipsEntryRequiringNewerLoader() {
        String body = manifest(",\"minLoader\":\"9.0.0\"");
        assertNull(MuoniumConfigIndex.parse(body, LANE, "2.5.0"),
                "要求播放器 9.0.0 而当前 2.5.0：不该自动装");
        // 播放器够新时正常放行
        assertNotNull(MuoniumConfigIndex.parse(body, LANE, "9.1.0"));
    }

    @Test
    @DisplayName("超大响应直接丢弃（防被塞垃圾）")
    void rejectsOversizedBody() {
        String huge = manifest("").replace("\"notes\":\"x\"", "\"notes\":\"" + "x".repeat(70_000) + "\"");
        assertNull(MuoniumConfigIndex.parse(huge, LANE, "2.5.0"));
    }

    @Test
    @DisplayName("内置兜底条目与远端条目走同一条链，版本号从文件名取")
    void builtinEntryParsesVersion() {
        MuoniumConfigIndex.Entry entry = MuoniumConfigIndex.builtin(
                LANE, "muoniumconfig-1.7.0.jar", 26123481L, SHA);
        assertEquals("1.7.0", entry.version);
        assertFalse(entry.remote);
        assertTrue(entry.isUsable());
    }

    // ==================== 三、不降级 ====================

    @Test
    @DisplayName("已装 1.8.0 而清单说 1.7.0：判定为已是最新（不降级玩家的版本）")
    void doesNotDowngrade() {
        // SemVer 是判定依据，这里直接钉住"不比已装新"这条关系，
        // 与 MuoniumConfigDownload.run 里的 !SemVer.isNewer(target, installed) 一致。
        assertFalse(com.muoniumplayer.core.update.SemVer.isNewer("1.7.0", "1.8.0"));
        assertTrue(com.muoniumplayer.core.update.SemVer.isNewer("1.8.0", "1.7.0"));
        assertFalse(com.muoniumplayer.core.update.SemVer.isNewer("1.7.0", "1.7.0"));
    }

    @Test
    @DisplayName("版本号过 9 仍然比较正确（字符串比较会在这里翻车）")
    void versionComparisonSurvivesDoubleDigits() {
        assertTrue(com.muoniumplayer.core.update.SemVer.isNewer("1.10.0", "1.9.0"));
        assertFalse(com.muoniumplayer.core.update.SemVer.isNewer("1.9.0", "1.10.0"));
    }

    // ==================== 辅助 ====================

    /** 造一个只含指定元数据文件的 jar。 */
    private static File writeJar(Path dir, String name, String entryName, String content)
            throws Exception {
        File jar = dir.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
