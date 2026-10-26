package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住渲染层停用提示的「渲染器路由」文案。
 *
 * <p>五棵树的 {@code RenderLayerNotice} 把安卓分支委托给 {@link RendererAdviceLines}；
 * 这组测试防文案路由漂移——历史教训：26.3 已把建议改成 MobileGL，其余四树还是
 * MobileGlues，五份手写文案必然各漂各的。
 */
class RendererAdviceLinesTest {

    @Test
    void desktopGetsNoAdvice() {
        assertTrue(RendererAdviceLines.of(RendererAdviceLines.GameBranch.LEGACY,
                "mobileglues", false).isEmpty(), "桌面不给渲染器建议");
    }

    @Test
    void mobileGlPointsAtRedirectLog() {
        List<String> lines = RendererAdviceLines.of(RendererAdviceLines.GameBranch.RENDER_PEARL,
                "mobilegl", true);
        assertTrue(lines.stream().anyMatch(l -> l.contains("SkijaGlRedirect")),
                "MobileGL 上要让用户查 GL 门重定向日志行");
    }

    @Test
    void mobileGluesOnLegacyKeepsAngleAdvice() {
        List<String> lines = RendererAdviceLines.of(RendererAdviceLines.GameBranch.LEGACY,
                "mobileglues", true);
        assertTrue(lines.stream().anyMatch(l -> l.contains("MobileGlues") && l.contains("ANGLE")),
                "1.20.1~26.2 的 MobileGlues 建议必须保留「关 ANGLE」");
    }

    @Test
    void mobileGluesOnRenderPearlPointsAtMobileGl() {
        List<String> lines = RendererAdviceLines.of(RendererAdviceLines.GameBranch.RENDER_PEARL,
                "mobileglues", true);
        assertTrue(lines.stream().anyMatch(l -> l.contains("MobileGL")),
                "26.3+ 上 MobileGlues 用户要被引向 MobileGL");
        assertFalse(lines.stream().anyMatch(l -> l.contains("关闭 ANGLE")),
                "26.3+ 不再给「关 ANGLE」旧结论");
    }

    @Test
    void gl4esFamilyPointsAtBranchSolution() {
        for (String kind : new String[]{"ng_gl4es", "holy_gl4es"}) {
            assertTrue(RendererAdviceLines.of(RendererAdviceLines.GameBranch.LEGACY, kind, true)
                            .stream().anyMatch(l -> l.contains("MobileGlues")),
                    kind + "（旧树）指向 MobileGlues");
            assertTrue(RendererAdviceLines.of(RendererAdviceLines.GameBranch.RENDER_PEARL, kind, true)
                            .stream().anyMatch(l -> l.contains("MobileGL")),
                    kind + "（26.3）指向 MobileGL");
        }
    }

    @Test
    void mesaRenderersMentionExperimentToggleAndDoor() {
        for (String kind : new String[]{"zink", "virgl", "freedreno", "panfrost"}) {
            List<String> lines = RendererAdviceLines.of(RendererAdviceLines.GameBranch.RENDER_PEARL,
                    kind, true);
            assertTrue(lines.stream().anyMatch(l -> l.contains("过程地址门") || l.contains("门")),
                    kind + " 要解释 GL 门自动对齐");
            assertTrue(lines.stream().anyMatch(l -> l.contains("实验")),
                    kind + " 要指到真玻璃实验开关");
        }
    }

    @Test
    void unknownKindFallsBackToBranchDefault() {
        assertTrue(RendererAdviceLines.of(RendererAdviceLines.GameBranch.LEGACY, "unknown", true)
                .stream().anyMatch(l -> l.contains("MobileGlues")));
        assertTrue(RendererAdviceLines.of(RendererAdviceLines.GameBranch.RENDER_PEARL, null, true)
                .stream().anyMatch(l -> l.contains("MobileGL")));
    }
}
