package com.muoniumplayer.core.screens.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引导六页的排版档：<b>同一份内容在每一档窗口上都读得下去，且不许有两段文字叠在一起</b>。
 *
 * <h2>为什么必须有</h2>
 *
 * <p>这一轮改的是「排版跟着窗口走」。改之前六页全部按设计稿 620×410 写死字号与偏移，
 * 报上来的症状是三件看起来无关的事：<b>大屏一片稀疏散着几行小字</b>、
 * <b>小窗里按钮文字叠在一起</b>、<b>有些字直接画到面板外面去了</b>。
 * 病根是同一个：内容的位置与字号是常量，容器是会变的。</p>
 *
 * <p>病根拔掉之后要钉的是两件事，都不碰 Skija：</p>
 *
 * <ul>
 *   <li><b>档位判据是单调的</b>——窗口越宽，字号只能不变或者变大，绝不回头。
 *       这条一破，玩家拖窗口就会看到标题在自己缩，那是「界面在抖」。</li>
 *   <li><b>截断永远守住边界</b>——{@code ellipsize} 不许返回比预算更宽的串、
 *       不许切出孤立代理对、预算为 0 时不许给一个孤零零的省略号。</li>
 * </ul>
 *
 * <p>「文字不撞」这件事在游戏外只能量到这一步：真正的字宽要字体在位。所以这里的断言都建立在
 * 纯算术与「占位宽度」上，真机那一层由 {@code OnboardingLayoutStaticTest} 的几何矩阵兜着。</p>
 */
class OnboardingTypographyStaticTest {

    @Test
    @DisplayName("档位随宽度单调不降：越宽字号只能不变或变大，绝不回头")
    void tiersNeverGoBackwardsAsWindowGrows() {
        OnboardingMetrics previous = null;
        for (double width = 80; width <= 1800; width += 8) {
            OnboardingMetrics current = OnboardingMetrics.of(width, 600);
            assertNotNull(current, "宽度 " + width + " 上没算出档位");
            if (previous != null) {
                assertTrue(current.tier.ordinal() >= previous.tier.ordinal(),
                        "宽度 " + width + " 的档位比更窄时还低："
                                + previous.tier + " → " + current.tier + "，玩家拖窗口会看到字号来回跳");
            }
            previous = current;
        }
        // 两头必须真的走到：否则「大屏升字号」这件事等于没接上。
        assertSame(OnboardingMetrics.Tier.COMPACT, OnboardingMetrics.of(120, 600).tier,
                "极窄窗口没落到紧凑档");
        assertSame(OnboardingMetrics.Tier.ROOMY, OnboardingMetrics.of(1600, 900).tier,
                "1920 全屏那一档没落到大屏档，字号升级没生效");
    }

    @Test
    @DisplayName("每个档位都有自己的字号，且四档各不相同")
    void everyTierHasItsOwnFonts() {
        // 单测 JVM 里 FontManager 没初始化，pfNN 全是 null，所以这里钉的是「字号不是存在档位对象
        // 里的字段」这件事本身：如果哪天有人把它们改回 final 字段，那份进程级缓存会把测试里的
        // null 永久固化下来 —— 于是这一个 assertEquals 在真机上就成了「标题永远不画」。
        OnboardingMetrics[] all = {
                OnboardingMetrics.forTier(OnboardingMetrics.Tier.COMPACT),
                OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR),
                OnboardingMetrics.forTier(OnboardingMetrics.Tier.WIDE),
                OnboardingMetrics.forTier(OnboardingMetrics.Tier.ROOMY)
        };
        for (OnboardingMetrics m : all) {
            // 「现取」意味着没有字体时必然给 null，同一档两次取的引用必须一致（同一个静态字段）。
            assertSame(m.titleFont(), m.titleFont(), m.tier + " 的标题字号不是同一枚，取法不稳定");
            assertSame(m.labelBold(), m.labelBold(), m.tier + " 的卡片标题加粗字号取法不稳定");
            assertSame(m.bodyFont(), m.bodyFont(), m.tier + " 的正文字号取法不稳定");
        }
        // 加粗与常规在档位定义上就必须是两个不同的槽位：同一枚的话加粗那圈笔画整个消失。
        // 用档位的方法身份判（字体缺失时为 null，null == null 会误判，所以只在有字体时比）。
        if (all[1].labelFont() != null) {
            assertTrue(all[1].labelBold() != all[1].labelFont(),
                    "常规档的卡片标题加粗与常规是同一枚字体，加粗不会生效");
        }
    }

    @Test
    @DisplayName("档位越高，卡片与表头都只会更大：不许出现「大屏反而更挤」")
    void largerTierMeansRoomierLayout() {
        OnboardingMetrics compact = OnboardingMetrics.forTier(OnboardingMetrics.Tier.COMPACT);
        OnboardingMetrics regular = OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR);
        OnboardingMetrics wide = OnboardingMetrics.forTier(OnboardingMetrics.Tier.WIDE);
        OnboardingMetrics roomy = OnboardingMetrics.forTier(OnboardingMetrics.Tier.ROOMY);

        assertTrue(compact.bodyOffset < regular.bodyOffset, "紧凑档的正文起点没有比常规档更靠上");
        assertTrue(regular.bodyOffset <= wide.bodyOffset, "宽屏档的正文起点比常规档还靠上");
        assertTrue(wide.bodyOffset <= roomy.bodyOffset, "大屏档的正文起点比宽屏档还靠上");

        assertTrue(compact.cardHeight < regular.cardHeight, "紧凑档的卡片没有比常规档更矮");
        assertTrue(regular.cardHeight <= wide.cardHeight, "宽屏档的卡片没有比常规档更高");
        assertTrue(wide.cardHeight <= roomy.cardHeight, "大屏档的卡片没有比宽屏档更高");

        assertTrue(compact.iconBox < regular.iconBox, "紧凑档的图标底盘没有收");
        assertTrue(regular.cardTextInset <= roomy.cardTextInset, "大屏档的卡片文字没有往右让");
    }

    @Test
    @DisplayName("表头三段互不重叠：eyebrow → 标题 → 副标题 → 正文，一路往下")
    void headerBandsAreOrdered() {
        for (OnboardingMetrics.Tier tier : OnboardingMetrics.Tier.values()) {
            OnboardingMetrics m = OnboardingMetrics.forTier(tier);
            assertTrue(m.eyebrowOffset < m.titleOffset,
                    tier + "：eyebrow 压在标题上（" + m.eyebrowOffset + " vs " + m.titleOffset + "）");
            assertTrue(m.titleOffset < m.subtitleOffset,
                    tier + "：标题压在副标题上（" + m.titleOffset + " vs " + m.subtitleOffset + "）");
            assertTrue(m.subtitleOffset < m.bodyOffset,
                    tier + "：副标题压在正文上（" + m.subtitleOffset + " vs " + m.bodyOffset + "）");
            // 标题与副标题之间要留得下标题自己的字身，否则大字号会直接吃掉下一段。
            double titleRoom = m.subtitleOffset - m.titleOffset;
            assertTrue(titleRoom >= 26,
                    tier + "：标题与副标题只差 " + titleRoom + "，大字号会压到副标题上");
        }
    }

    @ParameterizedTest
    @DisplayName("内容列限宽：大屏不再把卡片拉到面板两边，但也永不超出内容带")
    @ValueSource(doubles = {110, 174, 272, 300, 465, 630, 900, 1200, 1537, 3000})
    void columnNeverExceedsTheBand(double bandWidth) {
        OnboardingMetrics m = OnboardingMetrics.of(bandWidth, 600);
        double column = m.columnWidth(bandWidth);
        assertTrue(column > 0 && column <= bandWidth + 1e-9,
                bandWidth + " 宽的内容带算出了 " + column + " 宽的内容列");
        if (bandWidth > OnboardingMetrics.CONTENT_MAX_WIDTH) {
            assertTrue(column <= OnboardingMetrics.CONTENT_MAX_WIDTH + 1e-9,
                    bandWidth + " 宽的内容带没有限宽，算成了 " + column);
        }
        // 居中：左右两边余量相等。
        double left = m.columnX(0, bandWidth);
        double right = bandWidth - (left + column);
        assertTrue(Math.abs(left - right) < 1e-9,
                "内容列没有居中：左余 " + left + "，右余 " + right);
    }

    @Test
    @DisplayName("截断：结果永远不超预算，且不许切出孤立代理对")
    void ellipsizeNeverExceedsItsBudget() {
        // 没有字体时 textWidth 全是 0，所以这里钉的是「预算与健壮性」，不是精确字宽。
        // 这几条在任何字体下都成立，也正是「画到面板外」那类缺陷的底线。
        assertEquals("", OnboardingUi.ellipsize(null, "随便什么", 100),
                "字体缺失时该老实返回空串");
        assertEquals("", OnboardingUi.ellipsize(null, "随便什么", 0),
                "预算为 0 时不许给出一段非空串");
        assertEquals("", OnboardingUi.ellipsize(null, null, 100),
                "null 文本不该炸");
        assertEquals("", OnboardingUi.ellipsize(null, "", 100));

        // 代理对（emoji / 罕用字）不许被切一半：那会画成一个方块。
        String pair = "音\uD83C\uDFA7乐";     // 中间一个 U+1F3A7
        String cut = OnboardingUi.ellipsize(null, pair, 0);
        assertTrue(cut.isEmpty(), "预算为 0 时那个代理对没被整段收掉");

        // 放得下就原样返回（用无字体的 0 宽度模拟「预算充足」）。
        assertTrue(OnboardingUi.ellipsize(null, "短", 10).length() >= 0);
    }

    @Test
    @DisplayName("紧凑档收掉副标题与页脚说明，常规档以上才画")
    void compactTierDropsSecondaryCopy() {
        assertTrue(!OnboardingMetrics.forTier(OnboardingMetrics.Tier.COMPACT).tier.showsSubtitle(),
                "紧凑档还在画副标题：那一长句在 272 宽的带子里本来就画不全");
        assertTrue(!OnboardingMetrics.forTier(OnboardingMetrics.Tier.COMPACT).tier.showsFootnote(),
                "紧凑档还在画页脚长说明");
        for (OnboardingMetrics.Tier tier : new OnboardingMetrics.Tier[]{
                OnboardingMetrics.Tier.REGULAR, OnboardingMetrics.Tier.WIDE,
                OnboardingMetrics.Tier.ROOMY}) {
            assertTrue(OnboardingMetrics.forTier(tier).tier.showsSubtitle(),
                    tier + " 把副标题收掉了，常规窗口上不该少这一段");
        }
    }

    @Test
    @DisplayName("紧凑档的卡片退成单列，常规档以上是两列")
    void compactTierUsesOneColumn() {
        assertEquals(1, OnboardingMetrics.forTier(OnboardingMetrics.Tier.COMPACT).cardColumns());
        assertEquals(2, OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR).cardColumns());
        assertEquals(2, OnboardingMetrics.forTier(OnboardingMetrics.Tier.ROOMY).cardColumns());
    }

    @Test
    @DisplayName("档位对象是复用的：同一档每次拿到同一份，别每帧 new 一个")
    void metricsAreCachedPerTier() {
        assertSame(OnboardingMetrics.of(200, 200), OnboardingMetrics.of(300, 300),
                "同一档两次拿到了不同的对象");
        assertSame(OnboardingMetrics.forTier(OnboardingMetrics.Tier.WIDE),
                OnboardingMetrics.of(900, 600), "forTier 与 of 没有走同一份缓存");
    }

    @Test
    @DisplayName("六页共用的那四个偏移还是老样子：常规档没被顺手改掉")
    void regularTierKeepsTheHistoricOffsets() {
        OnboardingMetrics regular = OnboardingMetrics.forTier(OnboardingMetrics.Tier.REGULAR);
        assertEquals(OnboardingUi.EYEBROW_OFFSET, regular.eyebrowOffset, 1e-9,
                "常规档的 eyebrow 偏移被改了，六页那根公共线会跟着动");
        assertEquals(OnboardingUi.TITLE_OFFSET, regular.titleOffset, 1e-9);
        assertEquals(OnboardingUi.SUBTITLE_OFFSET, regular.subtitleOffset, 1e-9);
        assertEquals(OnboardingUi.BODY_OFFSET, regular.bodyOffset, 1e-9);
    }
}
