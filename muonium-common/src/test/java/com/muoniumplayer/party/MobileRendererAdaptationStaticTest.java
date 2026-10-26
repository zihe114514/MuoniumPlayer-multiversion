package com.muoniumplayer.party;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「移动端渲染器适配」在五棵树上的接线。
 *
 * <p>共享层（识别器 / 三档玻璃 / 提示路由 / Door B）只写一份，但五棵树的
 * HudConfig / HudSettingsModel / RenderLayerNotice 是各留一份的留守文件——
 * 哪棵树忘了挂实验开关、忘了委托文案，这里红。
 */
class MobileRendererAdaptationStaticTest {

    private static final List<String> TREES = List.of(
            "1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3");

    private static String read(String first, String... more) throws Exception {
        Path p = ProjectPaths.repoRoot();
        for (String segment : Paths.get(first, more).toString().replace('\\', '/').split("/")) {
            p = p.resolve(segment);
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void fiveTreesCarryExperimentalGlassKey() throws Exception {
        for (String tree : TREES) {
            String cfg = read(tree, "src", "main", "java", "com", "muoniumplayer",
                    "core", "settings", "HudConfig.java");
            assertTrue(cfg.contains("public static boolean glassMobileExperimental"),
                    tree + " HudConfig 缺 glassMobileExperimental 键");
            assertTrue(cfg.contains("glassMobileExperimental = getBoolean(o, \"glassMobileExperimental\""),
                    tree + " HudConfig load 缺 glassMobileExperimental");
            assertTrue(cfg.contains("o.addProperty(\"glassMobileExperimental\", glassMobileExperimental);"),
                    tree + " HudConfig save 缺 glassMobileExperimental");
            assertTrue(cfg.contains("GlassMobileTier.setUserOptIn(glassMobileExperimental);"),
                    tree + " HudConfig load 后必须把 opt-in 推给 GlassMobileTier");
        }
    }

    @Test
    void fiveTreesExposeExperimentalToggleAndLockByHardDisabled() throws Exception {
        for (String tree : TREES) {
            String model = read(tree, "src", "main", "java", "com", "muoniumplayer",
                    "core", "screens", "hud", "HudSettingsModel.java");
            assertTrue(model.contains("HudConfig.glassMobileExperimental"),
                    tree + " HudSettingsModel 缺「真玻璃（实验）」开关条目");
            assertTrue(model.contains("GlassMobileTier.hardDisabled()"),
                    tree + " 材质组锁死必须用 hardDisabled()——"
                            + "用 active() 会把实验开关一起锁死，用户永远点不到");
        }
    }

    @Test
    void fiveTreesDelegateRendererAdviceToSharedLayer() throws Exception {
        for (String tree : TREES) {
            String notice = read(tree, "src", "main", "java", "com", "muoniumplayer",
                    "core", "screens", "RenderLayerNotice.java");
            assertTrue(notice.contains("RendererAdviceLines.of("),
                    tree + " RenderLayerNotice 必须委托共享文案构建器（防五份文案漂移）");
            assertTrue(notice.contains("MobileRendererEnv.current().kind().id"),
                    tree + " RenderLayerNotice 必须把识别到的渲染器传给共享层");
        }
        // 旧的一刀切文案不应再硬编码在树里。
        for (String tree : TREES) {
            String notice = read(tree, "src", "main", "java", "com", "muoniumplayer",
                    "core", "screens", "RenderLayerNotice.java");
            assertTrue(!notice.contains("渲染器请选 MobileGlues"),
                    tree + " 不应再硬编码 MobileGlues 文案（交给 RendererAdviceLines 路由）");
        }
    }

    @Test
    void sharedLayerPins() throws Exception {
        String tier = read("muonium-common", "src", "main", "java", "com", "muoniumplayer",
                "core", "rendering", "GlassMobileTier.java");
        assertTrue(tier.contains("hardDisabled()") && tier.contains("setUserOptIn("),
                "GlassMobileTier 必须提供 hardDisabled 与 setUserOptIn");
        assertTrue(tier.contains("active()"),
                "active() 语义必须保留（GlassLook/GlassBackdrop 等调用点零改动的前提）");
        String env = read("muonium-common", "src", "main", "java", "com", "muoniumplayer",
                "core", "rendering", "MobileRendererEnv.java");
        for (String lib : new String[]{"libmobileglues.so", "libMobileGL.so", "libng_gl4es.so",
                "libgl4es_114.so", "libglxshim.so", "libOSMesa"}) {
            assertTrue(env.contains(lib), "识别器缺判据 " + lib);
        }
        assertTrue(env.contains("muonium.renderer.override"),
                "识别器必须留 JVM 属性逃生门");
    }
}
