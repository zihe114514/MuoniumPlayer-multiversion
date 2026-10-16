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
    @DisplayName("五条 GitHub 系镜像 + 两条 https 官网系 + 一条自建分发站，都指向 skija-companion/ 目录")
    void mirrorsPointAtTheManifestRepository() {
        assertEquals(8, SkijaCompanionDownload.MIRRORS.size(), "镜像条数：五条 GitHub 系 + Pages + R2 + 自建分发站");
        for (String mirror : SkijaCompanionDownload.MIRRORS) {
            // 自建分发站是 http（无证书），靠 sha256 终验保证内容不被篡改；其余路必须 https。
            if (!mirror.startsWith("http://muoniummc.cc.cd/")) {
                assertTrue(mirror.startsWith("https://"), "必须 https：" + mirror);
            }
            assertTrue(mirror.endsWith("/skija-companion/"), "目录要与仓库布局一致：" + mirror);
        }
        assertTrue(SkijaCompanionDownload.MIRRORS.get(0).startsWith("https://file.melodifymc.cc.cd/"), "源 1 是 R2 对象存储（2026-10-16 实测最快 2.25MB/s，A 档先开）");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(1).contains("gh-proxy.com"), "源 2 是 gh-proxy（实测次快 1.75MB/s，A 档先开）");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(2).startsWith("http://muoniummc.cc.cd/"), "源 3 是自建分发站（实测 1.11MB/s，A 档先开；独立于 Cloudflare 与 GitHub 的托管面）");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(3).startsWith("https://melodifymc.cc.cd/dl/"), "源 4 是官网 Pages 直链（B 档，15 秒后放量）");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(4).contains("ghfast.top"), "源 5 是 ghfast（B 档，高方差线路）");
        // 两条官网系兜底不走 GitHub；Pages 在 site 仓库 dl/ 下逐字节镜像，R2 是 fenfa 桶直链。
        assertTrue(SkijaCompanionDownload.MIRRORS.get(5).contains("jsdelivr"), "源 6 是 jsDelivr CDN（B 档，近期不稳）");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(6).contains("ghproxy.net"), "源 7 是 ghproxy.net（B 档，稳定但慢）");
        assertTrue(SkijaCompanionDownload.MIRRORS.get(7).contains("raw.githubusercontent.com"), "源 8 是原始地址（B 档，兜底）");
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

    @Test
    @DisplayName("重启接线：装好即置重启闸（不试热启用），跃迁钩子仍兜底重建文字渲染器")
    void restartGateAndRendererRebuildWired() throws IOException {
        String companion = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija",
                        "SkijaCompanionDownload.java")));
        assertTrue(companion.contains("restartRequired = true;"),
                "装好即置重启闸——热启用路线已撤下，生效统一走重启（GameRestart 替用户完成）");
        assertFalse(companion.contains("HOT_ENABLE_WINDOW_NANOS"),
                "不许再有热启用窗口：那是热重载时代的中间方案，已废弃");
        assertTrue(companion.contains("public static boolean companionRestartPending()")
                        || companion.contains("isRestartRequired()"),
                "重启判据要有跨包可问的入口（GameRestart 布防与救援文案都靠它）");

        String natives = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNatives.java")));
        int adopt = natives.indexOf("private static boolean adopt(File directory");
        int transition = natives.indexOf("Boolean.FALSE.equals(loaded)");
        int rebuild = natives.indexOf("FontManager.rebuildAllRenderers()");
        assertTrue(adopt >= 0 && transition > adopt && rebuild > transition,
                "跃迁钩子要保留：热加载不再主动尝试，但手动放包 + 重评偶然热载成功时，"
                        + "仍须重建无库窗口期焊死为空数组的文字渲染器");

        String fontManager = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "management", "FontManager.java")));
        assertTrue(fontManager.contains("public static int rebuildAllRenderers()"),
                "缺全量重建入口");
        assertTrue(fontManager.contains("GlyphInk.clearCache()")
                        && fontManager.contains("GlyphInkMetrics.clearCache()"),
                "重建时必须清 AWT 墨迹缓存——无库窗口期量出的高度可能基于坏行盒");
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
