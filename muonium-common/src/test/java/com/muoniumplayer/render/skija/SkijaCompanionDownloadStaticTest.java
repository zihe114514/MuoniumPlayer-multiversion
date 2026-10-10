package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 伴生包自动下载的不变量：表覆盖对不对、镜像指向对不对、钉子与产物对不对。
 *
 * <p>下载器本体不在这里起线程、不发网络请求——那两件事放进单测等于把测试变成
 * 「看网络脸色」。这里只钉三件错了不会在编译期报错、却会在玩家机器上炸的事：
 * 平台表的覆盖与命名、镜像 URL 的仓库路径、钉住的字节数/sha256 与真实产物一致。
 *
 * <p>「触发条件」「提示层接线」这类跨文件形状用源码断言钉（与
 * {@link SkijaNativeCatalogStaticTest} 同一路数）——断了它们就等于
 * 「下载器写好了但没人叫它」或「提示层还在教用户手动解压」。
 */
class SkijaCompanionDownloadStaticTest {

    private static final String PINNED = SkijaNatives.PINNED_SKIJA_VERSION;

    /** 应该托管伴生包的七个平台（与 build.gradle 的 skijaCompanionPlatforms 同一份）。 */
    private static final List<String> PLATFORMS = List.of(
            "windows/x64", "linux/x64", "linux/arm64", "macos/x64", "macos/arm64",
            "android/arm64", "android/x64");

    @Test
    @DisplayName("七个平台一个不少：文件名与打包任务、提示层三处同一拼法")
    void tableCoversEveryShippedPlatform() {
        Set<String> seen = new HashSet<>();
        for (String platform : PLATFORMS) {
            SkijaCompanionDownload.Pack pack = SkijaCompanionDownload.packFor(platform);
            assertNotNull(pack, "缺平台的包：" + platform);
            assertEquals(platform, pack.platform());
            assertTrue(seen.add(pack.platform()), "平台重复：" + platform);
            assertEquals("muonium-skija-" + platform.replace('/', '-') + "-" + PINNED + ".zip",
                    pack.fileName(), "文件名必须与 packageSkijaCompanion 的产物同一拼法");
            assertEquals(pack.fileName(), new SkijaNatives.Status(false, platform, PINNED, null, null,
                            List.of(), List.of(), null, false).expectedPackageName(),
                    "提示层打给用户的包名与实际下载的文件名必须一致");
            assertTrue(pack.bytes() > 1_000_000L, "包体至少 1 MB 量级：" + platform);
            assertEquals(64, pack.sha256().length(), "sha256 必须是 64 位十六进制：" + platform);
            assertTrue(pack.sha256().chars().allMatch(c -> Character.digit(c, 16) >= 0),
                    "sha256 只许十六进制字符：" + platform);
        }
        assertEquals(PLATFORMS.size(), SkijaCompanionDownload.PACKS.size(), "表里不能多出计划外的平台");
        assertNull(SkijaCompanionDownload.packFor("linux/riscv64"), "没有包的平台要老实返回 null");
    }

    @Test
    @DisplayName("三条镜像都是 https、都指向清单仓库的 skija-companion/ 目录")
    void mirrorsPointAtTheManifestRepository() {
        assertEquals(3, SkijaCompanionDownload.MIRRORS.size(), "镜像条数与更新检查同一套策略");
        for (String mirror : SkijaCompanionDownload.MIRRORS) {
            assertTrue(mirror.startsWith("https://"), "必须 https：" + mirror);
            assertTrue(mirror.endsWith("/skija-companion/"), "目录要与仓库布局一致：" + mirror);
            assertTrue(mirror.contains("MuoniumPlayer-updates"), "指向公开清单仓库：" + mirror);
        }
        assertTrue(SkijaCompanionDownload.MIRRORS.get(0).contains("ghfast.top"), "源 1 是国内可达的代理");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(1).contains("raw.githubusercontent.com"), "源 2 是原始地址");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(2).contains("jsdelivr"), "源 3 是 CDN 兜底");
    }

    @Test
    @DisplayName("开关只认显式 false；清掉即恢复默认开")
    void propertyOnlyDisablesOnExplicitFalse() {
        try {
            System.clearProperty(SkijaCompanionDownload.PROP_ENABLED);
            assertTrue(SkijaCompanionDownload.enabled(), "默认必须是开");
            System.setProperty(SkijaCompanionDownload.PROP_ENABLED, "true");
            assertTrue(SkijaCompanionDownload.enabled());
            System.setProperty(SkijaCompanionDownload.PROP_ENABLED, "false");
            assertFalse(SkijaCompanionDownload.enabled(), "只有显式 false 才关");
        } finally {
            System.clearProperty(SkijaCompanionDownload.PROP_ENABLED);
        }
    }

    @Test
    @DisplayName("解压暂存目录不进候选：半成品不许被加载试探撞上")
    void incomingStagingIsInvisibleToScan() throws IOException {
        Path root = Files.createTempDirectory("muonium-skija-incoming");
        try {
            write(root, ".incoming-deadbeef/android/arm64/libskija.so", "ELF");
            write(root, ".incoming-deadbeef/android/arm64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);
            assertTrue(SkijaNativeCatalog.scan(root.toFile(), PINNED, "android/arm64").usable().isEmpty(),
                    "点开头的暂存目录必须被扫描忽略（否则写到一半的 so 会被拿去试加载）");
            // 正式目录照常能认：忽略暂存不等于把正常布局一起吞了
            write(root, "android/arm64/libskija.so", "ELF");
            write(root, "android/arm64/" + SkijaNativeCatalog.VERSION_FILE, PINNED);
            assertEquals(1, SkijaNativeCatalog.scan(root.toFile(), PINNED, "android/arm64").usable().size());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    @DisplayName("dist/skija-companion 的产物与钉子逐字节一致（重打包后忘了同步常量会在这里红）")
    void distArtifactsMatchPinnedHashes() throws IOException {
        Path dist = ProjectPaths.repoRoot().resolve(Paths.get("dist", "skija-companion"));
        assumeTrue(Files.isDirectory(dist),
                "dist/skija-companion 不存在（干净检出或未打过伴生包）——跳过产物对拍");
        for (SkijaCompanionDownload.Pack pack : SkijaCompanionDownload.PACKS) {
            Path file = dist.resolve(pack.fileName());
            assertTrue(Files.isRegularFile(file), "缺产物：" + file
                    + "（重跑 :packageSkijaCompanion）");
            byte[] bytes = Files.readAllBytes(file);
            assertEquals(pack.bytes(), bytes.length,
                    pack.fileName() + " 的字节数与常量不符——重打包后要同步 Java 常量、仓库文件与 README");
            assertEquals(pack.sha256(), sha256(bytes),
                    pack.fileName() + " 与常量不一致——重打包后要同步 Java 常量、仓库文件与 README");
        }
    }

    @Test
    @DisplayName("接线：零候选才触发下载，提示层走共享文案")
    void wiringIsInPlace() throws IOException {
        String natives = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNatives.java")));
        int found = natives.indexOf("boolean foundSomething = !sources.isEmpty();");
        int call = natives.indexOf("SkijaCompanionDownload.onNoCandidates(mapped);");
        assertTrue(found >= 0 && call > found,
                "触发必须挂在「一个候选都没有」的判定之后——候选找到却加载不了时重复下载没有意义");
        assertTrue(natives.contains("public static List<String> nativeMissingLines()"),
                "缺库文案要由共享层按下载状态生成，五个版本不许各写一份");
        assertTrue(natives.contains("expectedPackageName()") && natives.contains("expectedDirectory()"),
                "共享文案里要保留包名与目标目录这两条硬信息");

        String notice = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "screens", "RenderLayerNotice.java")));
        assertTrue(notice.contains("SkijaNatives.nativeMissingLines()"),
                "停用提示的缺库分支必须走共享文案，而不是继续教用户手动解压");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String sha256(byte[] bytes) {
        try {
            byte[] out = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(out.length * 2);
            for (byte value : out) {
                text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException ignored)
                    throws IOException {
                Files.deleteIfExists(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
