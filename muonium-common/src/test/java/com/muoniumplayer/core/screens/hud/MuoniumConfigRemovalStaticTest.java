package com.muoniumplayer.core.screens.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「旧版本必须退场」这条纪律的源码级守卫。
 *
 * <p>为什么这条值得单独钉：两个同 mod id 的 jar 一起躺在 mods/ 里，下一次启动会被加载器
 * 当成重复 mod <b>直接拒绝启动</b>。也就是说，"新包已下好、旧包还在"是比"没有设置界面"
 * 严重得多的状态 —— 所以更新流程里必须有"删不掉就回滚"的写法，且不能有人手滑删掉它。</p>
 *
 * <p>本类做的是读源码字符串的静态断言：编排（下载 → 让位 → 回滚）跨在 {@code run()} 的
 * 流程里，单元测试造不出"文件被独占锁住"这种真实平台条件；能钉住的是"这些分支都还在"。
 * 平台层的真实行为已单独实测过（2026-11-09，Windows 11 + JDK 21）：
 * <ul>
 *   <li>{@code ZipFileSystem} 打开的 jar：运行期 {@code Files.delete} 成功，删除后仍能继续
 *       读类；{@code Files.move} 也成功 —— 这是 Fabric / NeoForge 的路径，也是"能自动删"的常态；</li>
 *   <li>{@code java.util.jar.JarFile} 打开的 jar：删除与改名都失败 —— 这是需要回滚的那条路。</li>
 * </ul>
 * </p>
 */
class MuoniumConfigRemovalStaticTest {

    private static String source() throws IOException {
        Path file = com.muoniumplayer.testsupport.ProjectPaths.sharedJava()
                .resolve("com/muoniumplayer/core/screens/hud/MuoniumConfigDownload.java");
        assertTrue(Files.isRegularFile(file), "找不到 MuoniumConfigDownload.java：" + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("stale 必须排除「新包自己」，而不是「版本最高的那个」")
    void staleExcludesTheNewFileNotTheNewestVersion() throws IOException {
        String text = source();
        // 2026-11-09 端到端验证踩到的真实 bug：只装一版再升级时，那个旧包恰好就是
        // newest，于是「除 newest 之外都删」等于「一个都不删」—— 新包已落盘、旧包还在，
        // 日志却报成功，下一次启动因重复 mod id 直接失败。
        assertTrue(text.contains("String targetPath = targetFile.getAbsolutePath();"),
                "stale 的判据必须是「所有已装包，除了刚下的这个文件」；"
                        + "按 newest 排除会在「只装一版、现在升级」时漏删旧包");
        int start = text.indexOf("String targetPath = targetFile.getAbsolutePath();");
        String tail = text.substring(start, Math.min(text.length(), start + 400));
        assertTrue(tail.contains("equals(targetPath)"), "必须按目标文件路径比较来排除新包");
        assertFalse(tail.contains("info.file != newest.file"),
                "不能再用 newest 做排除判据 —— 那正是漏删旧包的写法");
    }

    @Test
    @DisplayName("删不掉时必须能改名让位（扩展名不再是 .jar，加载器就不会扫它）")
    void hasRenameFallback() throws IOException {
        String text = source();
        assertTrue(text.contains(".jar.disabled"),
                "缺少改名兜底：Windows 上删不掉时唯一的退路就是把扩展名换掉，"
                        + "否则只能回滚整个更新");
        assertTrue(text.contains("Removal.DISABLED"), "三级处理的中间一档不见了");
    }

    @Test
    @DisplayName("删不掉时保留新包并告知用户，不回滚、不自动重启")
    void keepsNewJarAndTellsUserWhenRemovalFails() throws IOException {
        String text = source();
        // 2026-11-09 实机结论：运行期加载器独占锁住 mod jar，删与改名都是 WinError 32。
        // 回滚会让自动更新在被锁环境里永远失败，所以改成"保留新包 + 明确告知"。
        assertTrue(text.contains("if (!manual.isEmpty())"),
                "缺少『旧包删不掉』的分支：这是唯一真正危险的状态");
        int branch = text.indexOf("if (!manual.isEmpty())");
        String tail = text.substring(branch, Math.min(text.length(), branch + 1200));
        assertFalse(tail.contains("discard(targetFile)"),
                "删不掉时不能撤销新包：运行期旧包本来就删不掉（WinError 32），"
                        + "回滚等于让自动更新永远失败");
        assertTrue(tail.contains("Phase.FAILED"), "必须进入 FAILED，提示层才知道要告知用户");
        // 带着两个同 id 的 jar 重启正好会撞上加载失败，所以这条路径绝不能置待重启。
        assertFalse(tail.contains("restartRequired = true"),
                "旧包删不掉时不能自动重启：带着两个同 id 的 jar 重启会加载失败");
    }

    @Test
    @DisplayName("删不掉的旧包要记进待清理清单，下次启动重试")
    void persistsPendingRemoval() throws IOException {
        String text = source();
        assertTrue(text.contains("retryPendingRemoval()"),
                "缺少跨启动重试：JarFile 的锁是进程级的，重启后本来就能删掉");
        assertTrue(text.contains("savePending("), "缺少待清理清单的落盘");
    }

    @Test
    @DisplayName("顺序必须是先下载校验、后让旧包退场 —— 反了就是「界面没了」")
    void downloadsBeforeRemovingOld() throws IOException {
        String text = source();
        int download = text.indexOf("HttpDownloadRace.fetch(");
        int removal = text.indexOf("Removal.FAILED");
        assertTrue(download > 0 && removal > 0, "找不到下载或删除的调用点");
        assertTrue(download < removal,
                "必须先把新包下完并校验、再让旧包退场：反过来的话下载失败就会"
                        + "同时失去旧版 —— 从「少了设置界面」变成「界面彻底没有」");
    }

    @Test
    @DisplayName("下载完必须再验一次身份与版本 —— 清单说是什么不算数")
    void verifiesDownloadedJar() throws IOException {
        String text = source();
        int fetch = text.indexOf("HttpDownloadRace.fetch(");
        assertTrue(fetch > 0);
        String after = text.substring(fetch, Math.min(text.length(), fetch + 2000));
        assertTrue(after.contains("MuoniumConfigJarProbe.readInfo"),
                "下载后必须用 jar 内元数据复核身份：字节数与 sha256 只证明传输没坏，"
                        + "证明不了「这就是 MuoniumConfig 的那一版」");
        assertTrue(after.contains("版本不符"),
                "下载后必须核对清单声明的版本与包内版本一致");
    }

    @Test
    @DisplayName("降级保护：已装版本不比目标旧就不下载")
    void keepsNewerInstalledVersion() throws IOException {
        String text = source();
        assertTrue(text.contains("!SemVer.isNewer(entry.version, newest.version)"),
                "缺少降级保护：玩家手装了更新的版本时，清单里更旧的版本会把它覆盖掉");
    }

    @Test
    @DisplayName("提示层：删不掉时必须点名文件，并说清「不删下次启动会失败」")
    void noticeNamesFilesToDelete() throws IOException {
        String text = source();
        assertTrue(text.contains("请手动删除 mods/ 里的"),
                "用户不知道删哪个文件就等于没有提示");
        // 这是整个功能里唯一必须让用户看懂后果的地方：新包已在 mods/，旧包还在。
        assertTrue(text.contains("下次启动会因重复的 mod 而失败"),
                "必须说清后果：不删旧包，下次启动会因两个同 id 的 jar 加载失败");
        assertTrue(text.contains("public static boolean noticeVisible()"), "提示可见性入口不见了");
        // 删不掉的那条必须常驻，不能是"一闪而过"的失败窗
        String visible = text.substring(text.indexOf("public static boolean noticeVisible()"));
        visible = visible.substring(0, visible.indexOf("public static int noticePercent()"));
        assertTrue(visible.contains("manualRemoval()"),
                "『旧版删不掉』必须常驻提示：它要用户动手，一闪而过等于没说");
    }

    @Test
    @DisplayName("自动下载可以被系统属性关掉（开发期跑 runClient 用）")
    void canBeDisabledForDevelopment() throws IOException {
        String text = source();
        assertNotNull(text.contains("muonium.muoniumconfig.autoDownload") ? text : null,
                "缺少 -Dmuonium.muoniumconfig.autoDownload=false 开关："
                        + "开发期跑客户端会被自动下载打断");
    }
}
