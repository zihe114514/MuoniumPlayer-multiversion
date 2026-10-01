package com.muoniumplayer.core.screens.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引导页那副骨架（面板 / 侧栏 / 底栏）与里面三条带的几何，在任何窗口档位 × 任何播放器缩放下都不许叠。
 *
 * <h2>为什么必须有</h2>
 *
 * <p>界面按「逻辑像素」画：物理分辨率 ÷ GUI Scale，再乘玩家自己设的播放器缩放。真实组合是一张乘出来的
 * 表，不是几个点：854×480 全屏开 Scale 3 只剩 284×160，再把播放器缩到最小的 70%，面板就是 176×89。
 * 上一轮的缺陷正是这么来的——按钮行写死 {@code 底-72}、进度轨写死 {@code 底-50}、内容高写死
 * {@code height-180}，三处各算各的，于是 44 高的按钮永远压着进度轨，而 160 逻辑高的窗口上内容高
 * 直接算成 0，标题、副标题、胶囊叠成一团。</p>
 *
 * <p>这一轮的骨架是从播放器抄来的（见 {@link OnboardingChrome} 的出处表），所以又多了一根轴：
 * 播放器缩放。测试把两根轴一起乘起来过一遍。几何是纯算术，不碰 Skija，因此能在<b>游戏外</b>跑；
 * 真机截图只能证明「截到的那一档没问题」，这里证明的是「没有哪一档会出问题」。</p>
 */
class OnboardingLayoutStaticTest {

    // layoutForTest() 那条数组的下标。测试与被测代码不同包就看不见私有成员，所以名字在这里再钉一遍。
    private static final int CONTENT_X = 0;
    private static final int CONTENT_Y = 1;
    private static final int CONTENT_W = 2;
    private static final int CONTENT_H = 3;
    private static final int BUTTON_Y = 4;
    private static final int BUTTON_H = 5;
    private static final int PRIMARY_X = 6;
    private static final int PRIMARY_W = 7;
    private static final int BACK_X = 8;
    private static final int BACK_W = 9;
    private static final int SKIP_X = 10;
    private static final int SKIP_W = 11;
    private static final int COMPACT = 12;
    private static final int SKIP_COMPACT = 13;
    private static final int PANEL_X = 14;
    private static final int PANEL_Y = 15;
    private static final int PANEL_W = 16;
    private static final int PANEL_H = 17;
    private static final int BAR_Y = 18;
    private static final int BAR_H = 19;
    private static final int AREA_X = 20;
    private static final int AREA_Y = 21;
    private static final int AREA_W = 22;
    private static final int AREA_H = 23;
    private static final int RAIL_X = 24;
    private static final int RAIL_Y = 25;
    private static final int RAIL_W = 26;
    private static final int RAIL_ROW_H = 27;
    private static final int SHOW_BACK = 29;
    private static final int SHOW_SKIP = 30;
    private static final int RAIL_COLLAPSED = 31;

    private static final int STEP_COUNT = 6;

    /**
     * 玩家真会用的档位：物理尺寸 ÷ GUI Scale。
     *
     * <p>含 854×480 / Scale 3（284×160，真机量出来的那一档）、1366×768 笔记本的 Scale 2、
     * 4K 的 Scale 1 与 2，以及窗口缩到最小、Scale 拉满的 256×144。</p>
     */
    private static final List<double[]> WINDOW_SIZES = List.of(
            new double[]{284, 160},
            new double[]{256, 144},
            new double[]{320, 180},
            new double[]{384, 216},
            new double[]{427, 240},
            new double[]{512, 288},
            new double[]{519, 299},
            new double[]{520, 300},
            new double[]{640, 360},
            new double[]{683, 384},
            new double[]{800, 450},
            new double[]{960, 540},
            new double[]{1120, 700},
            new double[]{1280, 720},
            new double[]{1366, 768},
            new double[]{1920, 1080},
            new double[]{2560, 1440},
            new double[]{3840, 2160}
    );

    /** {@code NCMPlayerConfig} 允许的每一档（70%..100%，5% 一档）。 */
    private static final double[] PLAYER_SCALES = {0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00};

    @Test
    @DisplayName("面板在窗口内，内容区不被底栏吃掉：从上到下永远是 内容 → 底栏")
    void panelFitsTheWindowAndYieldsToTheBottomBar() {
        forEachLayout((band, width, height, where) -> {
            assertTrue(band[PANEL_X] >= 0 && band[PANEL_Y] >= 0
                            && band[PANEL_X] + band[PANEL_W] <= width + EPS
                            && band[PANEL_Y] + band[PANEL_H] <= height + EPS,
                    where + "：骨架面板出了窗口");
            assertTrue(band[AREA_Y] + band[AREA_H] <= band[BAR_Y] + EPS,
                    where + "：内容区（下沿 " + (band[AREA_Y] + band[AREA_H]) + "）压进了底栏（顶边 "
                            + band[BAR_Y] + "）——正文会盖住播放条那一条");
            assertTrue(band[CONTENT_H] > 0 && band[BUTTON_H] > 0 && band[BAR_H] > 0,
                    where + "：内容带或底栏算成了 0，等于这一档没界面");
        });
    }

    @Test
    @DisplayName("正文带住在内容区里：绘制与命中测试用的是同一个矩形")
    void contentBandStaysInsideTheContentArea() {
        forEachLayout((band, width, height, where) -> {
            assertTrue(band[CONTENT_X] >= band[AREA_X] - EPS
                            && band[CONTENT_X] + band[CONTENT_W] <= band[AREA_X] + band[AREA_W] + EPS,
                    where + "：正文横向出了内容区");
            assertTrue(band[CONTENT_Y] >= band[AREA_Y] - EPS
                            && band[CONTENT_Y] + band[CONTENT_H] <= band[AREA_Y] + band[AREA_H] + EPS,
                    where + "：正文纵向出了内容区");
            // 内容带是「缩放后还要被裁」的那块。最小的那一档（176×89 的面板）只剩三十几逻辑高，
            // 那是窗口与播放器缩放两头都拧到极限的结果：正文会被裁，但骨架、底栏、侧栏仍然各归各位。
            assertTrue(band[CONTENT_W] >= 96,
                    where + "：正文只剩 " + band[CONTENT_W] + " 宽，六页里那些卡片排不开");
            assertTrue(band[CONTENT_H] >= 24,
                    where + "：正文只剩 " + band[CONTENT_H] + " 高，连一行标题都放不下");
        });
    }

    @Test
    @DisplayName("底栏里的按钮：竖在条内、横不互叠；塞不下就整条不画而不是叠")
    void navButtonsFitTheBottomBar() {
        for (boolean confirmingSkip : new boolean[]{false, true}) {
            forEachLayout((band, width, height, where0) -> {
                String where = where0 + (confirmingSkip ? " 跳过确认中" : "");
                assertTrue(band[BUTTON_Y] >= band[BAR_Y] - EPS
                                && band[BUTTON_Y] + band[BUTTON_H] <= band[BAR_Y] + band[BAR_H] + EPS,
                        where + "：按钮没待在底栏那条里（按钮 " + band[BUTTON_Y] + "+" + band[BUTTON_H]
                                + "，条 " + band[BAR_Y] + "+" + band[BAR_H] + "）");
                assertTrue(band[PRIMARY_X] >= band[AREA_X] - EPS
                                && band[PRIMARY_X] + band[PRIMARY_W] <= band[AREA_X] + band[AREA_W] + EPS,
                        where + "：主按钮出了面板");
                if (band[SHOW_BACK] > .5) {
                    assertTrue(band[BACK_X] >= band[AREA_X] - EPS, where + "：上一步出了面板左边");
                    assertTrue(band[BACK_X] + band[BACK_W] <= band[SKIP_X] + EPS,
                            where + "：上一步（" + (band[BACK_X] + band[BACK_W]) + "）压住了跳过（"
                                    + band[SKIP_X] + "）");
                }
                if (band[SHOW_SKIP] > .5) {
                    assertTrue(band[SKIP_X] + band[SKIP_W] <= band[PRIMARY_X] + EPS,
                            where + "：跳过（" + (band[SKIP_X] + band[SKIP_W]) + "）压住了主按钮（"
                                    + band[PRIMARY_X] + "）");
                    if (band[SKIP_COMPACT] < .5) {
                        assertTrue(band[SKIP_W] >= 112,
                                where + "：跳过带字却只有 " + band[SKIP_W]
                                        + " 宽，「再次点击确认跳过」放不下");
                    }
                }
                // 主按钮是这一页唯一的动作，任何一档都必须留着。
                assertTrue(band[PRIMARY_W] >= band[BUTTON_H], where + "：主按钮被挤没了");
            });
        }
    }

    @Test
    @DisplayName("侧栏那一列：六行留在侧栏里；塞不下就整列收成一行「第几步」")
    void stepRailFitsInsideTheSidebar() {
        forEachLayout((band, width, height, where) -> {
            assertTrue(band[RAIL_X] >= band[PANEL_X] - EPS
                            && band[RAIL_X] + band[RAIL_W] <= band[AREA_X] + EPS,
                    where + "：步骤列越出了侧栏");
            if (band[RAIL_COLLAPSED] > .5) {
                // 收起来的那一档不该再有六行——有就说明「塞不下」判错了。
                assertTrue(band[RAIL_Y] + band[RAIL_ROW_H] > band[BAR_Y] - band[RAIL_ROW_H] * 6,
                        where + "：报了折叠，可六行其实放得下");
                return;
            }
            assertTrue(band[RAIL_Y] + STEP_COUNT * band[RAIL_ROW_H] <= band[BAR_Y] + EPS,
                    where + "：六行步骤（到 " + (band[RAIL_Y] + STEP_COUNT * band[RAIL_ROW_H])
                            + "）垂到了底栏（" + band[BAR_Y] + "）下面");
            assertTrue(band[RAIL_ROW_H] >= 18, where + "：步骤行高只剩 " + band[RAIL_ROW_H] + "，点不动");
        });
    }

    @Test
    @DisplayName("侧栏图标底盘：行太矮就不画；放不下两个时收图标、留标题")
    void railIconYieldsToTheTitleItCarries() {
        // 行高 34（大屏）→ 顶格 22；行高 21 → 只剩 11，比这更小画出来是一团糊。
        assertTrue(OnboardingScreen.railIconBoxFor(34, 200, 60, true) == 22.0,
                "行高够的时候底盘该顶到 22 的上限");
        assertTrue(OnboardingScreen.railIconBoxFor(21, 200, 60, true) == 0,
                "行高 21 时底盘只剩 11 逻辑像素，里面的字形会糊成一团，该整枚收掉");
        assertTrue(OnboardingScreen.railIconBoxFor(22, 200, 60, true) == 12.0,
                "行高 22 正好卡在 12 的下限上，不该收");

        // 标题放得下、但「图标 + 9 + 标题」放不下 → 收图标留标题（侧栏是用来点回去的）。
        assertTrue(OnboardingScreen.railIconBoxFor(34, 80, 60, true) == 0,
                "80 宽的侧栏塞不下 22 + 9 + 60 的「图标 + 标题」，还画图标就会把标题挤出去");
        assertTrue(OnboardingScreen.railIconBoxFor(34, 82, 50, true) == 22.0,
                "同样的侧栏放得下 22 + 9 + 50 = 81，就该画");

        // 标题本来就放不下（那时只剩图标一种表达）→ 只要图标本身放得下就画。
        assertTrue(OnboardingScreen.railIconBoxFor(34, 30, 999, false) == 22.0,
                "标题已经让位了，图标再收就什么都不剩");
        assertTrue(OnboardingScreen.railIconBoxFor(34, 10, 999, false) == 0,
                "连图标都放不下的那一档，整列已经该折叠成「第 N / 6 步」了");
    }

    @Test
    @DisplayName("极小档位：允许内容被裁，但不许叠字、也不许把按钮挤出面板")
    void tinyWindowsCropInsteadOfOverlapping() {
        // 284×160 是真机量到的那一档，再乘最小的播放器缩放 70%。
        double[] band = OnboardingScreen.layoutForTest(284, 160, 0.70, false);
        assertTrue(band[COMPACT] > .5, "这一档的底栏早该退回纯图标");
        assertTrue(band[CONTENT_H] < 410,
                "284×160 @70% 的内容带不该还有设计稿那 410 高：" + band[CONTENT_H]);
        assertTrue(band[CONTENT_Y] + band[CONTENT_H] <= band[BUTTON_Y],
                "小屏上正文越过了按钮行的顶边");
        assertTrue(band[PRIMARY_X] + band[PRIMARY_W] <= band[PANEL_X] + band[PANEL_W],
                "小屏上主按钮出了面板");
    }

    private interface LayoutCheck {
        void check(double[] band, double windowWidth, double windowHeight, String where);
    }

    private void forEachLayout(LayoutCheck check) {
        for (double[] window : WINDOW_SIZES) {
            for (double scale : PLAYER_SCALES) {
                double[] band = OnboardingScreen.layoutForTest(
                        window[0], window[1], scale, false);
                check.check(band, window[0], window[1], window[0] + "×" + window[1]
                        + " @播放器 " + Math.round(scale * 100) + "%");
            }
        }
    }

    private static final double EPS = 1e-6;
}
