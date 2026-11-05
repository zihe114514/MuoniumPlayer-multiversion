package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;

/**
 * 引导六页的排版档：<b>给定内容带的宽高，算出这一档该用多大字号、留多少间距、显示哪些信息</b>。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>六页原来全部按「设计稿 620×410」写死：标题 pf34、副标题 pf14、正文 pf12、卡片行高 56、
 * 内缩 x+52 这样的字面量，与容器的实际大小无关。于是两头都坏：
 *
 * <ul>
 *   <li><b>大窗口</b>——1920×1080 全屏的内容带实测 1537×907，是设计稿的两倍半，而字号一点没变。
 *       卡片被拉到 1500 宽、里面还是 14 号字和一根 22 宽的封面块，横向闲掉六成、纵向闲掉五成五。
 *       玩家看到的是「一张摊开的白纸，上面趴着几行小字」。</li>
 *   <li><b>小窗口</b>——284×160 开 GUI Scale 3 再叠 70% 播放器缩放，内容带只有 111×46。
 *       字号被 {@code MIN_READABLE_SCALE} 兜住不再缩，可内容比带子高 327 逻辑像素，
 *       多的那一截被裁掉；而卡片里的文字位置是写死的，窄到一定程度就<b>自己压到自己身上</b>
 *       （步进器标签压数值、模型行描述压下载按钮、长账号名压右侧对勾）。</li>
 * </ul>
 *
 * <p>所以这一层只做一件事：<b>把「内容带多大」翻译成一组排版参数</b>。六页都从
 * {@link #of(double, double)} 或 {@link OnboardingUi#metric()} 拿档位，不再自己写字号与偏移。
 *
 * <h2>为什么是四档而不是连续插值</h2>
 *
 * <p>字号只有那几枚 {@code FontManager.pfNN}，它们是按整数像素栅格化好的；连续插值最后还是
 * 要吸附到某一枚上，中途只会让「同一页在不同窗口下字距跳一格」。四档各自对应一段真实窗口宽度，
 * 玩家拖动窗口时看到的是「够宽了才换档」，而不是一路抖。
 *
 * <h2>为什么字号是<b>方法</b>而不是字段</h2>
 *
 * <p>{@code FontManager.pfNN} 是 {@code FontManager.init()} 之后才有值的静态字段，而在测试 JVM 里
 * 它们全是 null。档位对象是进程级缓存（每档一份），如果把这些值存成 final 字段，那份 null 就跟着
 * 对象一起留在缓存里了 —— 单测里量不出宽度，真机上只要缓存被提前创建过一次，字号就永远是 null。
 * 所以档位只存<b>档位与几何</b>，字号每次现取。</p>
 */
public final class OnboardingMetrics {

    /** 紧凑档上界：内容带不到这么宽时，卡片里的副标题与说明让位（信息降级）。 */
    static final double COMPACT_MAX = 420;
    /** 常规档上界。 */
    static final double REGULAR_MAX = 760;
    /** 宽屏档上界：再宽就走「大屏」那一档，字号整体升一级。 */
    static final double WIDE_MAX = 1180;

    /** 内容列的最大宽度。大屏不再把卡片拉到面板两边，而是收成中间这一栏。 */
    public static final double CONTENT_MAX_WIDTH = WIDE_MAX;

    public enum Tier {
        /** 极小窗口：只有标题 + 卡片 + 按钮，副标题与说明行全部收起。 */
        COMPACT,
        /** 常规：完整六页，字号维持设计稿。 */
        REGULAR,
        /** 宽屏：字号升一级，间距放宽。 */
        WIDE,
        /** 大屏：字号再升一级，卡片更高更松。 */
        ROOMY;

        public boolean showsSubtitle() {
            return this != COMPACT;
        }

        /** 卡片里的第二行（平台状态、模型描述）放不放得下。 */
        public boolean showsCardDetail() {
            return this != COMPACT;
        }

        /** 页脚那句长说明（音源页、Automix 页）显不显示。 */
        public boolean showsFootnote() {
            return this != COMPACT;
        }
    }

    public final Tier tier;

    // ------------------------------------------------ 字号（每次现取，见类注释最后一节）

    public CFontRenderer titleFont() {
        switch (tier) {
            case COMPACT: return FontManager.pf20bold;
            case WIDE: return FontManager.pf40bold;
            case ROOMY: return FontManager.pf50bold;
            default: return FontManager.pf34bold;
        }
    }

    /** 副标题（比正文大一号）。 */
    public CFontRenderer subtitleFont() {
        switch (tier) {
            case COMPACT: return FontManager.pf12;
            case WIDE: return FontManager.pf16;
            case ROOMY: return FontManager.pf18;
            default: return FontManager.pf14;
        }
    }

    /** 卡片主标题 / 段标题（常规体）。 */
    public CFontRenderer labelFont() {
        switch (tier) {
            case COMPACT:
            case REGULAR: return FontManager.pf12;
            case WIDE: return FontManager.pf14;
            default: return FontManager.pf16;
        }
    }

    /** 卡片主标题加粗版。与 {@link #labelFont()} 必须不是同一枚，否则加粗不生效。 */
    public CFontRenderer labelBold() {
        switch (tier) {
            case COMPACT:
            case REGULAR: return FontManager.pf12bold;
            case WIDE: return FontManager.pf14bold;
            default: return FontManager.pf16bold;
        }
    }

    /** 正文与说明。 */
    public CFontRenderer bodyFont() {
        switch (tier) {
            case COMPACT: return FontManager.pf11;
            case WIDE: return FontManager.pf14;
            case ROOMY: return FontManager.pf16;
            default: return FontManager.pf12;
        }
    }

    /** 一行字占多高。字体不在位时给一个不会把后续段落推到零的兜底。 */
    public static double lineHeight(CFontRenderer font) {
        return font == null ? 16 : Math.max(12, font.getHeight());
    }

    // ------------------------------------------------ 几何（与字体无关，构造时定下来）

    /** 表头三行的纵向偏移，从内容带顶算起。 */
    public final double eyebrowOffset;
    public final double titleOffset;
    public final double subtitleOffset;
    /** 正文第一行的 y 偏移。 */
    public final double bodyOffset;

    /** 卡片基准行高。各页在这个值上按自己的比例折算（见 {@code StepLayout} 那批方法）。 */
    public final double cardHeight;
    public final double cardGap;
    /** 卡片内文字距左边缘的距离（底盘右侧）。 */
    public final double cardTextInset;
    /** 卡片内的图标底盘边长。 */
    public final double iconBox;

    private OnboardingMetrics(Tier tier) {
        this.tier = tier;
        switch (tier) {
            case COMPACT:
                eyebrowOffset = 2;
                titleOffset = 20;
                subtitleOffset = 48;
                bodyOffset = 62;
                cardHeight = 40;
                cardGap = 8;
                iconBox = 22;
                cardTextInset = 40;
                break;
            case REGULAR:
                eyebrowOffset = EYEBROW_OFFSET;
                titleOffset = TITLE_OFFSET;
                subtitleOffset = SUBTITLE_OFFSET;
                bodyOffset = BODY_OFFSET;
                cardHeight = 56;
                cardGap = 12;
                iconBox = 30;
                cardTextInset = 52;
                break;
            case WIDE:
                eyebrowOffset = 6;
                titleOffset = 38;
                subtitleOffset = 88;
                bodyOffset = 118;
                cardHeight = 68;
                cardGap = 14;
                iconBox = 36;
                cardTextInset = 62;
                break;
            default:
                eyebrowOffset = 8;
                titleOffset = 46;
                subtitleOffset = 106;
                bodyOffset = 130;
                cardHeight = 80;
                cardGap = 16;
                iconBox = 42;
                cardTextInset = 72;
                break;
        }
    }

    // 常规档沿用 OnboardingUi 那四个公共偏移（六页过去就是照它们对齐的）。
    // 包内可见：测试要钉「常规档没被顺手改掉」。
    static final double EYEBROW_OFFSET = 4;
    static final double TITLE_OFFSET = 30;
    static final double SUBTITLE_OFFSET = 72;
    static final double BODY_OFFSET = 96;

    private static final OnboardingMetrics[] CACHE = new OnboardingMetrics[4];

    /**
     * 按内容带的宽高选一档。
     *
     * <p>只读宽度：高度那一头由 {@code stepScale} 与 {@code clipStep} 管（内容高了会被裁，
     * 那是「窗口太矮」而不是「该换字号」）。用宽度判还有一个实际原因——侧栏是固定比例，
     * 窄窗口的宽度最先吃紧，而字号的纵向预算反而没那么敏感。</p>
     */
    public static OnboardingMetrics of(double contentWidth, double contentHeight) {
        double width = Math.max(0, contentWidth);
        Tier tier = width < COMPACT_MAX ? Tier.COMPACT
                : width < REGULAR_MAX ? Tier.REGULAR
                : width < WIDE_MAX ? Tier.WIDE
                : Tier.ROOMY;
        return cached(tier);
    }

    /** 只按档位取（测试与不想重新判档的调用方用）。 */
    public static OnboardingMetrics forTier(Tier tier) {
        return cached(tier == null ? Tier.REGULAR : tier);
    }

    private static OnboardingMetrics cached(Tier tier) {
        synchronized (CACHE) {
            OnboardingMetrics metrics = CACHE[tier.ordinal()];
            if (metrics == null) {
                metrics = new OnboardingMetrics(tier);
                CACHE[tier.ordinal()] = metrics;
            }
            return metrics;
        }
    }

    /**
     * 内容列在内容带里的左端。
     *
     * <p>大屏不再让卡片铺满整条带子，而是收成中间一栏（{@link #CONTENT_MAX_WIDTH}）：
     * 1537 宽的一行卡片里放五个字，读起来是「排版没做完」，而不是「屏幕很大」。</p>
     */
    public double columnX(double bandX, double bandWidth) {
        double width = columnWidth(bandWidth);
        return bandX + (bandWidth - width) * .5;
    }

    public double columnWidth(double bandWidth) {
        return Math.min(Math.max(1, bandWidth), CONTENT_MAX_WIDTH);
    }

    /** 这一档卡片最多排几列（音源页的两列网格在紧凑档退成单列）。 */
    public int cardColumns() {
        return tier == Tier.COMPACT ? 1 : 2;
    }
}
