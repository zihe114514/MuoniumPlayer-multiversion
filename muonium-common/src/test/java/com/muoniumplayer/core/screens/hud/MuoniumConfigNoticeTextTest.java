package com.muoniumplayer.core.screens.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示文案的「真实性」守卫：文案里的**版本号与文件名必须来自真实状态**，
 * 且在任何状态下都不能拼出残缺句子。
 *
 * <p>为什么单独钉这一组：这条提示是用户唯一的行动依据 —— 他看到的是
 * 「请手动删除 mods/ 里的：muoniumconfig-1.5.0.jar」。一旦这个文件名是写死的、
 * 或者版本号是猜的，用户就会去删一个不存在或不匹配的文件，而真正的重复 mod 还在，
 * 下一次启动照样加载失败。这类错误在屏幕上看起来「有提示」，排查时最费时间。</p>
 *
 * <p>数据来源已经由实现保证：文件名取自 {@link MuoniumConfigJarProbe#scan} 扫出的真实
 * 文件（{@code info.file.getName()}），版本号取自清单或 jar 内元数据。本类钉的是
 * **文案这一层不再加工失真**：不写死、不丢、不自造。</p>
 */
class MuoniumConfigNoticeTextTest {

    /** 构造一个状态快照（与 run() 里真正用的一致）。 */
    private static MuoniumConfigDownload.State state(MuoniumConfigDownload.Phase phase,
                                                     String from, String to, List<String> manual) {
        return new MuoniumConfigDownload.State(phase, 0, "detail", from, to, manual);
    }

    /** 走到「旧版删不掉」那条文案，取出拼接后的三行。 */
    private static List<String> removalLines(String from, String to, List<String> manual) throws Exception {
        MuoniumConfigDownload.State built = state(
                MuoniumConfigDownload.Phase.FAILED, from, to, manual);
        return render(built);
    }

    /**
     * 用反射把状态塞进去再看 {@link MuoniumConfigDownload#noticeLines()}。
     *
     * <p>只为了这个测试而加一个生产用的 setter 是不划算的：那等于给「伪造状态」开一个
     * 正式入口。反射在这里只碰私有字段，测试失败会直接抛出，不会静默。</p>
     */
    private static List<String> render(MuoniumConfigDownload.State built) throws Exception {
        java.lang.reflect.Field field = MuoniumConfigDownload.class.getDeclaredField("state");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, built);
            return MuoniumConfigDownload.noticeLines();
        } finally {
            field.set(null, previous);
        }
    }

    @Test
    @DisplayName("删不掉的旧包文件名原样出现在提示里，一个字符都不改写")
    void manualFileNamesAppearVerbatim() throws Exception {
        List<String> lines = removalLines("1.5.0", "1.8.0", List.of("muoniumconfig-1.5.0.jar"));
        String all = String.join("\n", lines);
        assertTrue(all.contains("muoniumconfig-1.5.0.jar"),
                "真实文件名必须原样出现，否则用户会去删别的文件：\n" + all);
    }

    @Test
    @DisplayName("多个旧包全部点名（用户装过 1.5、1.6 都要删）")
    void allStaleFilesAreNamed() throws Exception {
        List<String> stale = List.of("muoniumconfig-1.5.0.jar", "muoniumconfig-1.6.0.jar");
        List<String> lines = removalLines("1.6.0", "1.8.0", stale);
        String all = String.join("\n", lines);
        for (String name : stale) {
            assertTrue(all.contains(name), "漏了 " + name + "，用户会以为只删一个就够：\n" + all);
        }
    }

    @Test
    @DisplayName("玩家装的是 1.5.0 就显示 1.5.0 —— 版本号来自状态，不是写死的常量")
    void versionComesFromRealState() throws Exception {
        // 同一段文案喂两个不同版本，输出必须跟着变。写死任何一处都会让这两条相等。
        String a = String.join("\n", removalLines("1.5.0", "1.8.0", List.of("muoniumconfig-1.5.0.jar")));
        String b = String.join("\n", removalLines("1.3.2", "1.8.0", List.of("muoniumconfig-1.3.2.jar")));
        assertTrue(a.contains("muoniumconfig-1.5.0.jar"),
                "A 组应点名 1.5.0 那个文件：\n" + a);
        assertTrue(b.contains("muoniumconfig-1.3.2.jar"),
                "换了已装版本，文案里的文件名也必须跟着换：\n" + b);
        assertFalse(a.equals(b), "两条不同的状态渲染出了同样的文案 = 某处被写死了");
    }

    @Test
    @DisplayName("目标版本来自清单；拿不到时退化成「新版本」而不是留一个空洞")
    void missingTargetVersionDegradesGracefully() throws Exception {
        // 跨启动重试仍失败时（stuckState）手上没有目标版本号：from/to 都是空串。
        List<String> lines = removalLines("", "", List.of("muoniumconfig-1.5.0.jar"));
        String first = lines.get(0);
        assertTrue(first.contains("新版本"),
                "不知道版本时应说「新版本」，不能拼出空版本号的句子：" + first);
        assertFalse(first.contains("  "),
                "空版本号会在句中留下双空格（「MuoniumConfig  已下载完成」），一眼就是 bug：" + first);
        assertFalse(first.contains("null"), "绝不能把 null 拼进用户可见文案：" + first);
    }

    @Test
    @DisplayName("更新成功的文案带上真实的新旧版本")
    void doneMentionsRealVersions() throws Exception {
        List<String> lines = render(state(
                MuoniumConfigDownload.Phase.DONE, "1.6.0", "1.8.0", List.of()));
        String all = String.join("\n", lines);
        assertTrue(all.contains("1.6.0") && all.contains("1.8.0"),
                "更新成功的提示要同时说清从哪一版到哪一版：\n" + all);
    }

    @Test
    @DisplayName("首次安装没有旧版本时，文案里不出现空版本或 null")
    void firstInstallHasNoDanglingVersion() throws Exception {
        List<String> lines = render(state(
                MuoniumConfigDownload.Phase.DONE, "", "1.8.0", List.of()));
        String all = String.join("\n", lines);
        assertTrue(all.contains("1.8.0"), "首次安装也要说清装的是哪一版：\n" + all);
        assertFalse(all.contains("  "), "空 from 版本会留下双空格：\n" + all);
        assertFalse(all.contains("null"), "绝不能把 null 拼进用户可见文案：\n" + all);
    }

    @Test
    @DisplayName("「旧版删不掉」必须常驻显示（要用户动手，不能一闪而过）")
    void removalNoticeStaysVisible() throws Exception {
        java.lang.reflect.Field field = MuoniumConfigDownload.class.getDeclaredField("state");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, state(MuoniumConfigDownload.Phase.FAILED, "1.5.0", "1.8.0",
                    List.of("muoniumconfig-1.5.0.jar")));
            assertTrue(MuoniumConfigDownload.noticeVisible(),
                    "删不掉旧包是要用户动手的事，提示必须常驻");
        } finally {
            field.set(null, previous);
        }
    }

    @Test
    @DisplayName("下载类失败不点名文件（那是自动重试的事，不该让用户动手）")
    void plainFailureDoesNotDemandManualWork() throws Exception {
        List<String> lines = render(state(
                MuoniumConfigDownload.Phase.FAILED, "", "", List.of()));
        String all = String.join("\n", lines);
        assertTrue(all.contains("自动更新失败"), "普通失败应说明是更新失败：\n" + all);
        assertFalse(all.contains("请手动删除"),
                "下载失败会自动重试，不该让用户去删文件：\n" + all);
    }

    @Test
    @DisplayName("所有文案都不含 null / 双空格空洞")
    void noPlaceholderLeaks() throws Exception {
        List<List<String>> cases = List.of(
                removalLines("1.5.0", "1.8.0", List.of("muoniumconfig-1.5.0.jar")),
                removalLines("", "", List.of("a.jar")),
                removalLines("1.5.0", "", List.of("a.jar")),
                removalLines("", "1.8.0", List.of("a.jar")),
                render(state(MuoniumConfigDownload.Phase.DONE, "", "1.8.0", List.of())),
                render(state(MuoniumConfigDownload.Phase.DONE, "1.5.0", "", List.of())),
                render(state(MuoniumConfigDownload.Phase.DONE, "1.5.0", "1.8.0", List.of())));
        for (List<String> lines : cases) {
            for (String line : lines) {
                assertFalse(line.contains("null"), "null 漏进文案：" + line);
                assertFalse(line.contains("  "), "有空版本号留下的双空格：" + line);
                assertFalse(line.isBlank(), "空行不该出现在文案里");
            }
        }
    }
}
