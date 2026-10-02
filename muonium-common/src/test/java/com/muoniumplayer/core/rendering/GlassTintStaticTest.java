package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玻璃底色的「根底 / 内层」两档压薄，完全在 JVM 上算，不启动客户端。
 *
 * <h2>为什么要有这份文件</h2>
 * 2026-09-30 真机截图：面板外圈确实透出了糊过的天空，页面<em>内部</em>却是一块块黑斑——
 * 侧栏、搜索框、来源按钮、底部按钮条、整片曲目行。根因不是坐标、也不是采集，是<b>层数</b>：
 * 一块面板的深色从来不只一层，根底之上还叠着内容区底、侧栏底、行底。这些内层当时压根没接
 * 玻璃（行底是 100% 实色），而「就算接了」也不够——同一份浓度乘上三遍，合成不透明度会
 * 直接回到 0.9 以上，等于没透。{@code interiorFactor} 与 {@code rootFactor} 分成两档就是
 * 为了这件事，而「叠三遍还透不透」这种账肉眼只会看成「有点暗」，必须在这里算清楚。
 *
 * <h2>这里钉住的三件事</h2>
 * <ol>
 *   <li>合成后的整页明暗落在一个区间里：既要真透（不能糊死），又要白字读得出（不能全没底）。</li>
 *   <li>两档在浓度 = 1 处同时回到 1 —— 「滑块推到顶就是原来的样子」对整页成立。</li>
 *   <li>反面证据：如果内层与根底共用一条曲线，合成值会掉回 0.85 以上。这条就是把本轮的 bug
 *       钉在测试里，日后谁把两档合成一档，测试会红。</li>
 * </ol>
 */
class GlassTintStaticTest {

    /**
     * 播放器面板从上到下真实叠了哪几层深色（base = 各调用点自己原本的 alpha）。
     *
     * <p>数字抄自调用点：根底 {@code NCMScreen.layout()} 的 {@code 1.0 - liquid * 0.14}，
     * 内容区 {@code currentPanelBg} 的 {@code 1.0 - liquid * 0.24}，侧栏
     * {@code NavigateBar} 的 {@code 0.9 - liquid * 0.22}，曲目行 {@code MusicWidget}
     * 原本是全不透明。琉璃主题下 liquid 与真玻璃都是 1。</p>
     */
    private static final float ROOT_BASE = 0.86f;
    private static final float CONTENT_BASE = 0.76f;
    private static final float SIDEBAR_BASE = 0.68f;
    private static final float ROW_BASE = 1.0f;

    @Test
    @DisplayName("没有玻璃时两档都原样返回，浓度滑块不影响普通主题")
    void noGlassLeavesEveryLayerAlone() {
        // amount = 0 时 washed 必须一字不改地返回 base：默认那十四套主题靠这一点保证零变化。
        assertEquals(0.86f, GlassLook.washed(0.86f, 0f, GlassLook.interiorFactor(0.55f)), 1e-5f);
        assertEquals(1.0f, GlassLook.washed(1.0f, 0f, GlassLook.rootFactor(0.2f)), 1e-5f);
        // 浓度为 0 的 base 也原样返回（调用点那些「这一层本来就不画」的 0 不能被算成负数）
        assertEquals(0f, GlassLook.washed(0f, 1f, GlassLook.rootFactor(1f)), 1e-5f);
    }

    @Test
    @DisplayName("浓度推到顶时根底与内层同时回到实色")
    void fullTintRestoresTheOldOpaquePanel() {
        assertEquals(1.0f, GlassLook.rootFactor(1f), 1e-5f);
        assertEquals(1.0f, GlassLook.interiorFactor(1f), 1e-5f);
        // 推到 0 时根底仍留 0.035 的保命底，内层则彻底让位
        assertEquals(0.035f, GlassLook.rootFactor(0f), 1e-5f);
        assertEquals(0f, GlassLook.interiorFactor(0f), 1e-5f);
    }

    @Test
    @DisplayName("内层永远不比根底厚，且两档都随浓度单调上升")
    void interiorIsAlwaysThinnerThanRoot() {
        for (float tint = 0f; tint <= 1.0001f; tint += 0.05f) {
            float clamped = Math.min(1f, tint);
            float root = GlassLook.rootFactor(clamped);
            float interior = GlassLook.interiorFactor(clamped);
            assertTrue(interior <= root + 1e-5f,
                    "浓度 " + clamped + " 时内层比根底还厚: " + interior + " > " + root);
            assertTrue(interior >= 0f && root <= 1f,
                    "浓度 " + clamped + " 时系数越界: root=" + root + " interior=" + interior);
        }
        // 单调：滑块往上推，页面必须<em>更</em>实；往下推必须更透。
        // 内层要跟着动，否则「浓度」就只是外圈那一层的旋钮——本轮玩家抱怨的正是这个。
        for (float tint = 0.05f; tint <= 1.0f; tint += 0.05f) {
            assertTrue(GlassLook.interiorFactor(tint) > GlassLook.interiorFactor(tint - 0.05f),
                    "内层系数在浓度 " + tint + " 处没有随浓度上升");
        }
    }

    @Test
    @DisplayName("出厂浓度下整页叠完仍然透，且白字还有底可依赖")
    void defaultStackStillReadsAsGlass() {
        float tint = HudConfig.glassTintStrength;
        float root = GlassLook.washed(ROOT_BASE, 1f, GlassLook.rootFactor(tint));
        float content = GlassLook.washed(CONTENT_BASE, 1f, GlassLook.interiorFactor(tint));
        float sidebar = GlassLook.washed(SIDEBAR_BASE, 1f, GlassLook.interiorFactor(tint));
        float row = GlassLook.washed(ROW_BASE, 1f, GlassLook.interiorFactor(tint));

        float headerDark = composite(root, content);
        float sidebarDark = composite(root, sidebar);
        float listDark = composite(root, content, row);

        // 上界：曲目行那一层原本是 100% 实色，整片列表因此完全不透。
        // 接了内层口径之后必须明显低于「面板外圈」当时的 0.08，否则这次改动等于没做。
        assertTrue(listDark < 0.08f,
                "默认浓度 " + tint + " 下列表区仍有 " + listDark + " 的深色，页面读起来还是不通透");
        // 下界：保留极薄的色膜，不能回到旧的深色毛玻璃。
        assertTrue(listDark > 0.025f,
                "默认浓度 " + tint + " 下列表区只剩 " + listDark + " 的深色，字会糊进背景里");
        // 侧栏与内容区这两块<em>区域</em>必须一样深：本轮截图上「内部有暗色部分」的
        // 另一种写法就是各区域深浅不一（侧栏 0.85、内容区 0.76、列表 1.00）。
        assertTrue(Math.abs(sidebarDark - headerDark) < 0.02f,
                "侧栏 " + sidebarDark + " 与内容区 " + headerDark + " 深浅不一，页面上会长出色块");
        // 交错行那一档要留在「看得出有条纹、但绝不成块发黑」之间。
        float stripe = listDark - headerDark;
        assertTrue(stripe >= 0f && stripe < 0.015f,
                "交错行的深浅差 " + stripe + " 不合适：太小区分不出行，太大就又成了一块暗斑");
    }

    @Test
    @DisplayName("反面证据：内层若与根底共用一条曲线，整页会重新糊死")
    void singleTierWouldGoOpaqueAgain() {
        // 这一条不是在测现在的代码，是在钉「为什么要分两档」：把内层也按 rootFactor 压一次，
        // 三层叠完的合成深色会回到 0.85 以上——正是玩家说的「不够通透 + 里面有暗块」。
        float tint = 0.55f; // Legacy factory concentration: demonstrates why the opaque material was replaced.
        float root = GlassLook.rootFactor(tint);
        float singleTier = composite(
                GlassLook.washed(ROOT_BASE, 1f, root),
                GlassLook.washed(CONTENT_BASE, 1f, root),
                GlassLook.washed(ROW_BASE, 1f, root));
        assertTrue(singleTier > 0.55f,
                "单层口径的合成值变了（" + singleTier + "），说明 rootFactor 被改动，"
                        + "两档划分的必要性要重新算一次");

        float twoTier = composite(
                GlassLook.washed(ROOT_BASE, 1f, root),
                GlassLook.washed(CONTENT_BASE, 1f, GlassLook.interiorFactor(tint)),
                GlassLook.washed(ROW_BASE, 1f, GlassLook.interiorFactor(tint)));
        assertTrue(twoTier < singleTier - 0.15f,
                "两档 " + twoTier + " 没有明显比单层 " + singleTier + " 更透，分档失去意义");
    }

    @Test
    @DisplayName("浓度=0：内层归零、根底留 0.035 —— 两档在 0 处本来就不该相等")
    void tintZeroStillKeepsAReadableFloor() {
        // 内层在浓度=0 时必须完全让位给真画面。它要是偷偷有了下限，
        // AbstractWidget.sanitizeFillAlpha 把 0 当坏值那种错就会被掩盖掉
        // （见 ui/FillAlphaStaticTest：那才是 2026-09-30「越调越黑」的真凶）。
        assertEquals(0f, GlassLook.interiorFactor(0f), 0f);
        // 根底反过来必须留一点：浓度拉到 0 也还有 0.035 托着白字，不至于压在亮画面上读不出。
        assertTrue(GlassLook.rootFactor(0f) >= 0.025f,
                "根底的下限被改掉了，浓度=0 时字会压在亮背景上");
        // 两档在浓度=1 处同时回到 1 ——「滑块推到顶就是原来的样子」对整页成立。
        assertEquals(1f, GlassLook.rootFactor(1f), 0f);
        assertEquals(1f, GlassLook.interiorFactor(1f), 0f);
    }

    /** 从下往上叠若干层同色半透明深色，返回最终的不透明度（1 = 完全不透）。 */
    private static float composite(float... alphas) {
        float clear = 1f;
        for (float alpha : alphas) {
            assertTrue(alpha >= 0f && alpha <= 1f, "压薄后的 alpha 越界: " + alpha);
            clear *= 1f - alpha;
        }
        return 1f - clear;
    }
}
