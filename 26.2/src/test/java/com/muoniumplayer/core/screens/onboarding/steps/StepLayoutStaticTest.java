package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六页正文的几何：同一根起点线、卡片之间不重叠、命中测试与绘制读同一份数字。
 *
 * <h2>为什么必须有</h2>
 *
 * <p>这一轮之前每页各写各的：账号页正文在 {@code y + 92}、音源页在 {@code y + 94}、外观页在
 * {@code y + 95}、Automix 页在 {@code y + 103}，而账号页与外观页根本没有 eyebrow 那一段。
 * 于是「翻页」这个动作本身就在上下跳，四页的标题看着像四种东西。更要命的是过去
 * {@code render} 用一份字面量、{@code mouseClicked} 用另一份（{@code y + 95} 与 {@code y + 95}
 * 各写一遍），改一处忘一处就出现「看得见却点不到」——上一轮在小窗口上正是这个症状。</p>
 *
 * <p>现在几何只有几个包内静态方法这一份算法，绘制与命中测试都调它，所以这里能钉两件事：
 * <b>六页站在同一根线上</b>，以及<b>同一页里的卡片不许互相压着</b>。两者都不碰 Skija。</p>
 */
class StepLayoutStaticTest {

    private static final double Y = 40.0;
    private static final double X = 20.0;
    private static final double WIDTH = 620.0;

    @Test
    @DisplayName("六页的第一块内容落在同一根线上（翻页时不上下跳）")
    void everyPageStartsItsBodyOnTheSameLine() {
        double body = Y + OnboardingUi.BODY_OFFSET;
        assertEquals(body, SourceStep.cardY(Y, 0), 1e-9,
                "音源页第一排卡不在表头下面那条公共线上");
        assertEquals(body, AppearanceStep.cardY(Y), 1e-9,
                "外观页那三张主题卡不在公共线上");
        assertEquals(body, AutomixModelStep.rowY(Y, 0), 1e-9,
                "Automix 页第一行不在公共线上");
        // 账号页的第一块内容是那条登录状态，平台卡排在它下面。
        assertEquals(body, AccountStep.statusY(Y), 1e-9,
                "账号页的状态条不在公共线上");
        assertTrue(AccountStep.rowY(Y, 0) > body + AccountStep.statusHeight(),
                "第一行平台卡压住了状态条");
    }

    @Test
    @DisplayName("同一页里的卡片不许互相压着，也不许留下会误点的重叠区")
    void cardsNeverOverlapEachOther() {
        // 两列网格：同一排的两张要比左右，同一列的两张要比上下。逐对判，
        // 因为「第 i 张比第 i−1 张靠下」这种写法在第 2 张（同一排右边那张）上必然假。
        int count = SourceStep.cardCount();
        double width = SourceStep.cardWidth(WIDTH);
        for (int a = 0; a < count; a++) {
            for (int b = a + 1; b < count; b++) {
                double ax = SourceStep.cardX(X, WIDTH, a);
                double ay = SourceStep.cardY(Y, a);
                double bx = SourceStep.cardX(X, WIDTH, b);
                double by = SourceStep.cardY(Y, b);
                boolean sameRow = Math.abs(ay - by) < 1e-9;
                boolean sameColumn = Math.abs(ax - bx) < 1e-9;
                if (sameRow) {
                    assertTrue(Math.max(ax, bx) >= Math.min(ax, bx) + width - 1e-9,
                            "音源页第 " + a + " 与第 " + b + " 张卡在同一排却压在了一起");
                } else if (sameColumn) {
                    assertTrue(Math.max(ay, by) >= Math.min(ay, by) + SourceStep.cardHeight() - 1e-9,
                            "音源页第 " + a + " 与第 " + b + " 张卡在同一列却压在了一起");
                } else {
                    // 既不同排也不同列：只许对角错开，不许矩形相交。
                    assertTrue(ax + width <= bx || bx + width <= ax
                                    || ay + SourceStep.cardHeight() <= by
                                    || by + SourceStep.cardHeight() <= ay,
                            "音源页第 " + a + " 与第 " + b + " 张卡斜着相交了");
                }
            }
        }
        // 同一排的两列之间要有缝：缝宽为 0 时中间那条线会被算成两张都可点。
        assertTrue(SourceStep.cardX(X, WIDTH, 1) > SourceStep.cardX(X, WIDTH, 0) + width,
                "音源页左右两列贴在了一起");

        for (int i = 1; i < AccountStep.rowCount(); i++) {
            assertTrue(AccountStep.rowY(Y, i) >= AccountStep.rowY(Y, i - 1) + AccountStep.rowHeight(),
                    "账号页第 " + i + " 行平台卡压在了上一行上");
        }
        for (int i = 1; i < AutomixModelStep.rowCount(); i++) {
            assertTrue(AutomixModelStep.rowY(Y, i) >= AutomixModelStep.rowY(Y, i - 1)
                            + AutomixModelStep.rowHeight(),
                    "Automix 页第 " + i + " 行模型卡压在了上一行上");
        }
        assertTrue(AutomixModelStep.rowsBottom(Y) > AutomixModelStep.rowY(Y, AutomixModelStep.rowCount() - 1),
                "rowsBottom 不是最后一行的底边");
    }

    @Test
    @DisplayName("主题卡那块预览必须占得到位置：卡高 = 预览 + 内缩 + 名称与状态两行")
    void themeCardHasRoomForItsPreview() {
        double cardHeight = AppearanceStep.cardHeight();
        double cardWidth = AppearanceStep.cardWidth(WIDTH);
        assertTrue(cardWidth > 90,
                "三张主题卡在 " + WIDTH + " 宽的内容带里只剩 " + cardWidth + "，预览块会挤成一团");
        // 预览 58 + 上下内缩 20 + 名称行 20 + 状态行 14 + 之间留白 ≥ 卡高，
        // 少一段就会看到「名字压在预览上」——这一页上一轮整块是透明的，量不出来。
        assertTrue(cardHeight >= 58 + 20 + 20 + 14,
                "主题卡只有 " + cardHeight + " 高，放不下预览块与两行文字");
        // 三张卡连同两道 12 的间距要正好铺满内容带宽，不留一条没人认领的空隙。
        double span = AppearanceStep.cardX(X, WIDTH, 2) + cardWidth - X;
        assertEquals(WIDTH, span, 1.0,
                "三张主题卡没有铺满内容带（差 " + (WIDTH - span) + "）");
    }

    @Test
    @DisplayName("表头那三行的间距：eyebrow、标题、副标题各占一段，互不重叠")
    void headerBandsDoNotCollide() {
        assertTrue(OnboardingUi.EYEBROW_OFFSET < OnboardingUi.TITLE_OFFSET,
                "eyebrow 压在标题上");
        // pf34bold 在最小的内容带缩放下也有二十几逻辑高，标题与副标题之间至少留 20。
        assertTrue(OnboardingUi.SUBTITLE_OFFSET - OnboardingUi.TITLE_OFFSET >= 30,
                "标题与副标题只差 " + (OnboardingUi.SUBTITLE_OFFSET - OnboardingUi.TITLE_OFFSET)
                        + "，大标题会压到副标题上");
        // 正文必须给副标题留出整行的高度，否则第一排卡片会切掉那行字。
        assertTrue(OnboardingUi.BODY_OFFSET - OnboardingUi.SUBTITLE_OFFSET >= 20,
                "正文起点离副标题太近，第一排卡会压在字上");
    }
}
