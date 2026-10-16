package com.muoniumplayer.core.management;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整字体自动下载的不变量：钉子对不对、镜像指向对不对、主包里是不是子集、接线是不是齐的。
 *
 * <p>与 {@code SkijaCompanionDownloadStaticTest} 同一路数：下载器本体在这里不起线程、
 * 不发网络请求。钉四件错了不会在编译期报红、却会在玩家机器上出事的事：</p>
 * <ol>
 *   <li>{@link FontAssetDownload#PACKS} 的字节数与 sha256（与清单仓库 fonts/ 目录对拍）；</li>
 *   <li>镜像 URL 指向公开清单仓库的 fonts/ 目录；</li>
 *   <li>主包资源里是<b>精简子集</b>——体积必须显著小于钉住的全量值，
 *       否则等于把全量字体又塞回了主包（瘦身白做）；</li>
 *   <li>接线：启动要触发下载、加载要缓存优先、装好要在渲染线程热启用、进度要走灵动岛。</li>
 * </ol>
 */
class FontAssetDownloadStaticTest {

    /** 主包资源里的两份字体（精简子集形态）。 */
    private static final Path RESOURCE_FONTS = ProjectPaths.repoRoot().resolve(
            Paths.get("muonium-common", "src", "main", "resources", "muonium", "fonts"));

    @Test
    @DisplayName("两份字体一个不少：文件名与资源目录同名、钉住字节数与 sha256")
    void packsTableMatchesShippedFontNames() {
        assertEquals(2, FontAssetDownload.PACKS.size(), "只下载两份全量苹方（常规 + 中黑）");
        for (FontAssetDownload.Pack pack : FontAssetDownload.PACKS) {
            assertTrue(pack.fileName().startsWith("pf_") && pack.fileName().endsWith(".ttf"),
                    "文件名必须与 FontManager 的资源路径同名：" + pack.fileName());
            assertTrue(pack.bytes() > 5_000_000L, "全量中文字体至少 5 MB 量级：" + pack.fileName());
            assertEquals(64, pack.sha256().length(), "sha256 必须是 64 位十六进制：" + pack.fileName());
            assertTrue(pack.sha256().chars().allMatch(c -> Character.digit(c, 16) >= 0),
                    "sha256 只许十六进制字符：" + pack.fileName());
        }
        assertEquals("pf_normal.ttf", FontAssetDownload.PACKS.get(0).fileName());
        assertEquals("pf_middleblack.ttf", FontAssetDownload.PACKS.get(1).fileName());
        // 不在下载表里的文件名要老实返回 -1（readFontBytes 靠它判断走不走缓存路径）
        assertEquals(-1L, FontAssetDownload.pinnedBytes("arial.ttf"));
        assertEquals(-1L, FontAssetDownload.pinnedBytes("不存在的字体.ttf"));
    }

    @Test
    @DisplayName("五条 GitHub 系镜像 + 两条 https 官网系 + 一条自建分发站，都指向 fonts/ 目录")
    void mirrorsPointAtTheManifestRepository() {
        assertEquals(8, FontAssetDownload.MIRRORS.size(), "镜像条数：五条 GitHub 系 + Pages + R2 + 自建分发站");
        for (String mirror : FontAssetDownload.MIRRORS) {
            // 自建分发站是 http（无证书），靠 sha256 终验保证内容不被篡改；其余路必须 https。
            if (!mirror.startsWith("http://muoniummc.cc.cd/")) {
                assertTrue(mirror.startsWith("https://"), "必须 https：" + mirror);
            }
            assertTrue(mirror.endsWith("/fonts/"), "目录要与仓库布局一致：" + mirror);
        }
        assertTrue(FontAssetDownload.MIRRORS.get(0).startsWith("https://file.melodifymc.cc.cd/"), "源 1 是 R2 对象存储（2026-10-16 实测最快 2.25MB/s，A 档先开）");
        assertTrue(FontAssetDownload.MIRRORS.get(1).contains("gh-proxy.com"), "源 2 是 gh-proxy（实测次快 1.75MB/s，A 档先开）");
        assertTrue(FontAssetDownload.MIRRORS.get(2).startsWith("http://muoniummc.cc.cd/"), "源 3 是自建分发站（实测 1.11MB/s，A 档先开；独立于 Cloudflare 与 GitHub 的托管面）");
        assertTrue(FontAssetDownload.MIRRORS.get(3).startsWith("https://melodifymc.cc.cd/dl/"), "源 4 是官网 Pages 直链（B 档，15 秒后放量）");
        assertTrue(FontAssetDownload.MIRRORS.get(4).contains("ghfast.top"), "源 5 是 ghfast（B 档，高方差线路）");
        // 两条官网系兜底不走 GitHub；Pages 在 site 仓库 dl/ 下逐字节镜像，R2 是 fenfa 桶直链。
        assertTrue(FontAssetDownload.MIRRORS.get(5).contains("jsdelivr"), "源 6 是 jsDelivr CDN（B 档，近期不稳）");
        assertTrue(FontAssetDownload.MIRRORS.get(6).contains("ghproxy.net"), "源 7 是 ghproxy.net（B 档，稳定但慢）");
        assertTrue(FontAssetDownload.MIRRORS.get(7).contains("raw.githubusercontent.com"), "源 8 是原始地址（B 档，兜底）");
    }

    @Test
    @DisplayName("开关只认显式 false；清掉即恢复默认开")
    void propertyOnlyDisablesOnExplicitFalse() {
        try {
            System.clearProperty(FontAssetDownload.PROP_ENABLED);
            assertTrue(FontAssetDownload.enabled(), "默认必须是开");
            System.setProperty(FontAssetDownload.PROP_ENABLED, "true");
            assertTrue(FontAssetDownload.enabled());
            System.setProperty(FontAssetDownload.PROP_ENABLED, "false");
            assertFalse(FontAssetDownload.enabled(), "只有显式 false 才关");
        } finally {
            System.clearProperty(FontAssetDownload.PROP_ENABLED);
        }
    }

    @Test
    @DisplayName("主包里必须是精简子集：合法字体文件、且远小于全量钉值（否则瘦身白做）")
    void bundledFontsAreSubsetsNotFullCopies() throws IOException {
        for (FontAssetDownload.Pack pack : FontAssetDownload.PACKS) {
            Path file = RESOURCE_FONTS.resolve(pack.fileName());
            assertTrue(Files.isRegularFile(file), "主包里必须带着子集兜底：" + file);
            long size = Files.size(file);
            assertTrue(size < pack.bytes() / 2,
                    pack.fileName() + " 的主包副本（" + size + " 字节）超过了全量钉值的一半——"
                            + "是不是把全量字体又塞回了资源目录？瘦身就不成立了");
            assertTrue(size > 500_000L,
                    pack.fileName() + " 的主包副本只剩 " + size + " 字节——子集切过头，"
                            + "首启动连常用字都兜不住");
            byte[] head = Files.readAllBytes(file);
            int version = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16)
                    | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
            boolean sfnt = version == 0x00010000 || version == 0x74727565 // TrueType / 'true'
                    || version == 0x4F54544F; // 'OTTO'
            assertTrue(sfnt, pack.fileName() + " 不是合法的 sfnt 字体文件（魔数不符）");
        }
    }

    @Test
    @DisplayName("接线：init 触发下载、加载缓存优先、渲染线程热启用、进度走灵动岛")
    void wiringIsInPlace() throws IOException {
        String fontManager = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "management", "FontManager.java")));
        int bootstrap = fontManager.indexOf("UiFontLibrary.bootstrap();");
        int ensure = fontManager.indexOf("FontAssetDownload.ensureFonts();");
        assertTrue(bootstrap >= 0 && ensure > bootstrap,
                "init 里要在界面字体装好之后触发完整字体下载（顺序：bootstrap → ensureFonts）");
        assertTrue(fontManager.contains("FontAssetDownload.pinnedBytes(fileName)"),
                "readFontBytes 必须先问下载表有没有缓存副本（缓存目录优先）");
        assertTrue(fontManager.contains("public static boolean applyFullFontAssets("),
                "缺热启用入口 applyFullFontAssets");
        int swapDef = fontManager.indexOf("swapBuiltinFallback(oldNormal, newNormal, oldBold, newBold);");
        assertTrue(swapDef >= 0, "TEXT_SLOTS 的内置回退也要跟着热启用换，否则用户之后换字体又拿回子集");

        String renderer = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "rendering", "font", "CFontRenderer.java")));
        assertTrue(renderer.contains("public boolean swapFallbackFont(Font expected, Font replacement)"),
                "渲染器要有按身份换回退的入口（实例不换，预热控件树才不会拿坏引用）");

        String island = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        int render = island.indexOf("public static void render() {");
        int poll = island.indexOf("FontAssetDownload.pollFromRenderThread();");
        assertTrue(render >= 0 && poll > render,
                "渲染线程钩子必须挂在灵动岛 render() 里（五棵版本树都只调它，接线才零改动）");
        assertTrue(island.contains("public static void showFontAssetProgress(")
                        && island.contains("public static void showFontAssetFailure(")
                        && island.contains("public static void showFontAssetReady("),
                "进度/失败/就绪三张卡都要有公开入口");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
