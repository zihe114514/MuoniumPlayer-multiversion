package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;

import java.awt.Color;

/**
 * MuoniumConfig 的调色板。
 *
 * <p>色彩体系整体取自 Polyfrost OneConfig 的现代深灰语言（那套 GRAY 梯度、白 alpha 档、
 * 主色双档），但<b>全部数值是在本项目布局轴上重新定标的</b>，不是把 1280×800 画布的像素
 * 抄过来 —— 我们的文字栈（CFontRenderer）比 OneConfig 的 Inter 大一号，几何常数按同一
 * 观感比例放大过。以后调这个界面的颜色只动这一个文件。</p>
 *
 * <p>主色不写死：OneConfig 的灵魂是「主蓝 600/500 双档」，这里用播放器主题 accent
 * 现场映射出同样一对（{@link #primary()} 常态 / {@link #primaryDark()} 按下与选中条）。
 * 映射只做一次缓存；主题切换后下一次打开界面自然跟上，不需要监听。</p>
 *
 * <p>所有常量的高字节都是真实 alpha——「空 alpha 陷阱」（0xRRGGBB 画出来全透明）在这里
 * 从源头杜绝。</p>
 */
public final class MConfigTheme {

    private MConfigTheme() {
    }

    // ==================== 灰阶（OneConfig GRAY 梯度） ====================

    /** 卡片底：比面板再深一号（OneConfig 里 GRAY_900 垫在 GRAY_800 面板上的关系）。 */
    public static final int GRAY_900 = 0xFF0D0E0F;
    public static final int GRAY_850 = 0xFF151719;
    /** 面板底。玻璃层（GlassLook.EDITORS）的 tint 也用这个色相。 */
    public static final int GRAY_800 = 0xFF151617;
    /** 下拉面板 / tooltip 气泡 / 分隔线。 */
    public static final int GRAY_700 = 0xFF222326;
    /** 开关轨道关态 / 双态滑槽。 */
    public static final int GRAY_600 = 0xFF2A2C30;
    /** 次级按钮常态。 */
    public static final int GRAY_500 = 0xFF313338;
    public static final int GRAY_400 = 0xFF373B45;
    /** 滑条轨道 / 描边。 */
    public static final int GRAY_300 = 0xFF494F5C;

    /** MD3 色调阶梯：设置卡片是面板之上的一层面，靠亮度差而不是描边分层。 */
    public static final int SURFACE_1 = 0xFF1B1E23;
    public static final int SURFACE_2 = 0xFF21252B;
    public static final int SURFACE_3 = 0xFF272C33;

    // ==================== 白 alpha 档（OneConfig WHITE_50..95 的用法） ====================

    public static final int WHITE_95 = 0xF2FFFFFF;
    public static final int WHITE_90 = 0xE6FFFFFF;
    public static final int WHITE_80 = 0xCCFFFFFF;
    public static final int WHITE_60 = 0x99FFFFFF;
    public static final int WHITE_50 = 0x80FFFFFF;
    public static final int WHITE_40 = 0x66FFFFFF;
    public static final int WHITE_25 = 0x40FFFFFF;
    public static final int WHITE_15 = 0x26FFFFFF;
    public static final int WHITE_10 = 0x1AFFFFFF;
    public static final int WHITE_6 = 0x0FFFFFFF;

    // ==================== 语义色 ====================

    /** 文字三档：行名 / 说明 / 最弱提示。 */
    public static final int TEXT_PRIMARY = 0xFFF2F5FA;
    public static final int TEXT_SECONDARY = WHITE_60;
    public static final int TEXT_TERTIARY = WHITE_40;
    /**
     * 卡片说明行。比 {@code TEXT_SECONDARY} 亮一档：卡片底下是透明玻璃，
     * 天空这类亮画面透上来时 60% 白会糊成一片，读不清「这项影响什么」。
     */
    public static final int TEXT_DESCRIPTION = 0xCCD6DEE9;

    /**
     * 卡片底。玻璃上要透一点游戏画面，但不能薄到读不清字：
     * 面板本身已经被 {@code tintedArgb} 按浓度压薄过，卡片再压一次就会把天空直接放进来。
     * 所以卡片的 alpha 保持接近实心，透感交给面板那一层。
     *
     * <p><b>色相来自播放器主题</b>：{@link #surface()} 取 {@code ELEMENT_BACKGROUND}，
     * 于是「丝绒玫瑰」的卡片偏紫、「瓷白墨」的卡片偏米白，而不是所有主题共用一套硬编码深灰。</p>
     */
    public static final int CARD_BG = 0xFA1B1E23;
    /** 卡片悬停 / 展开态：比常态亮一档，MD3 里这就是「状态层」而不是描边。 */
    public static final int CARD_BG_HOVER = 0xFF23272E;
    public static final int CARD_BG_ACTIVE = 0xFF272C33;
    /** 卡片描边：极低对比，只在深色底上勾一层轮廓。 */
    public static final int CARD_OUTLINE = 0x14FFFFFF;
    /** 浮层（下拉 / 取色器 / 确认框 / 快捷小窗）：不透明深灰，压得住下面的内容。 */
    public static final int FLOAT_BG = 0xF7151617;
    /** 主内容分隔线（同一张卡内的行与行之间）。 */
    public static final int DIVIDER = 0x0FFFFFFF;
    /** 面板顶部那道 1px 高光边：玻璃厚度感的来处。 */
    public static final int PANEL_EDGE = 0x24FFFFFF;

    /** 吸附参考线：OneConfig 的紫。编辑态里唯一的高饱和装饰色。 */
    public static final int SNAP_PURPLE = 0xFF8A2BE2;

    /** 两块 HUD 的标识色沿用旧编辑器：编辑态换新皮，身份不变。 */
    public static final int INFO_ACCENT = 0xFF7DD3FC;
    public static final int LYRICS_ACCENT = 0xFFA78BFA;

    /** 危险动作（恢复默认确认钮）。 */
    public static final int DANGER = 0xFFD92020;
    public static final int DANGER_HOVER = 0xFFF2756B;

    // ==================== 主题面（跟随播放器主题，不是自定一套灰） ====================
    //
    // 这一页曾经把 OneConfig 的灰阶整套抄进来：深灰面板 + 更深的卡片 + 白 alpha 三档。
    // 结果是切到「瓷白墨」这类浅主题时，播放器别处是米白，只有这一页仍然一片黑 ——
    // 卡片和文字都读不出主题身份。现在卡片面、悬停面、说明文字、分隔线全部问 NCMTheme 要，
    // 本类只保留「主题没就绪时」的兜底值。所有 alpha 仍按面板那套语义补足（见类注释末段）。

    private static int themeCacheKey = Integer.MIN_VALUE;
    private static int surfaceCache;
    private static int surfaceHoverCache;
    private static int surfaceActiveCache;
    private static int descriptionCache;
    private static int dividerCache;
    private static int floatCache;
    private static boolean themeResolved;

    /**
     * 卡片面 = 主题「控件底色」。卡片本身就是这一页的控件容器，
     * 用 {@code ELEMENT_BACKGROUND} 才能和播放器里的行卡片对上。
     */
    public static int surface() {
        resolveThemeColors();
        return surfaceCache;
    }

    /** 卡片悬停面 = 主题「控件悬停」，原样使用（主题自己已经把它提亮过一档）。 */
    public static int surfaceHover() {
        resolveThemeColors();
        return surfaceHoverCache;
    }

    /** 卡片展开面：悬停面再往主色偏一点，表达「这张卡正在被操作」。 */
    public static int surfaceActive() {
        resolveThemeColors();
        return surfaceActiveCache;
    }

    /** 卡片说明文字 = 主题「次要文字」，补足可读 alpha。 */
    public static int descriptionText() {
        resolveThemeColors();
        return descriptionCache;
    }

    /** 卡片内分隔线：主题描边色压到很低，只留一道「这里分层了」的暗示。 */
    public static int divider() {
        resolveThemeColors();
        return dividerCache;
    }

    /** 浮层（tooltip / 下拉 / 取色器）：主题输入框底色再压深一点，保证压得住下面的内容。 */
    public static int floatSurface() {
        resolveThemeColors();
        return floatCache;
    }

    private static void resolveThemeColors() {
        int accent = 0xFF1967FF;
        int element = 0xFF30212C;
        int hover = 0xFF493342;
        int secondary = 0xC2AAB7;
        int border = 0x604252;
        try {
            accent = 0xFF000000 | NCMTheme.getAccentColor();
            element = 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
            hover = 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            secondary = 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
            border = 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.BORDER);
        } catch (Throwable ignored) {
            // 主题系统未就绪（极早打开编辑器）时用丝绒玫瑰的同位色兜底，不至于画成纯黑。
            hover = 0xFF493342;
            secondary = 0xC2AAB7;
            border = 0x604252;
        }
        // 主题切换是 0.95 秒平滑过渡的，颜色每帧都可能变；缓存键带上当前一套色，变了就重算。
        int key = accent ^ (element * 31) ^ (hover * 17) ^ (secondary * 7) ^ border;
        if (themeResolved && key == themeCacheKey) return;
        themeResolved = true;
        themeCacheKey = key;

        // 卡片面保持接近实心（见 CARD_BG 注释），色相完全来自主题。
        surfaceCache = withAlpha(element, 0xFA);
        surfaceHoverCache = withAlpha(hover, 0xFC);
        surfaceActiveCache = mix(hover, accent, 0.16f) | 0xFC000000;
        descriptionCache = withAlpha(secondary, 0xE6);
        dividerCache = withAlpha(border, 0x40);
        floatCache = withAlpha(mix(element, 0xFF000000, 0.35f) & 0xFFFFFF, 0xFC);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    // ==================== 主色（主题 accent → 600/500 双档） ====================

    private static int primaryCache = 0;
    private static int primaryDarkCache = 0;
    private static boolean primaryResolved;

    /**
     * 主色常态档（顶 OneConfig PRIMARY_500 的位置：滑条已滑过、开关开态、聚焦描边）。
     * 就是播放器主题 accent 原色，只补全 alpha。
     */
    public static int primary() {
        resolvePrimary();
        return primaryCache;
    }

    /** 主色深档（顶 PRIMARY_600：侧栏选中条、下拉选项 hover、按钮按下）。 */
    public static int primaryDark() {
        resolvePrimary();
        return primaryDarkCache;
    }

    private static void resolvePrimary() {
        if (primaryResolved) return;
        primaryResolved = true;
        int accent = 0xFF1967FF;
        try {
            accent = 0xFF000000 | NCMTheme.getAccentColor();
        } catch (Throwable ignored) {
            // 主题系统还没就绪（极早打开编辑器）时用 OneConfig 原味蓝兜底。
        }
        // 主题 accent 近黑（未初始化 / 特殊主题）时深档会更黑，侧栏选中条会隐形 —— 退回原味蓝。
        float luma = ((accent >> 16) & 0xFF) * 0.299f
                + ((accent >> 8) & 0xFF) * 0.587f
                + (accent & 0xFF) * 0.114f;
        if (luma < 26f) {
            accent = 0xFF1967FF;
        }
        primaryCache = accent;
        float[] hsb = Color.RGBtoHSB((accent >> 16) & 0xFF, (accent >> 8) & 0xFF, accent & 0xFF, null);
        // 600 相对 500：亮度 ×0.72、饱和度略抬 —— 与 OneConfig 那对蓝的关系同款。
        Color dark = Color.getHSBColor(hsb[0], Math.min(1f, hsb[1] * 1.08f), hsb[2] * 0.72f);
        primaryDarkCache = dark.getRGB() | 0xFF000000;
    }

    /** 主色淡入版：同一色相、按比例压 alpha（滑条把手内嵌点、聚焦下划线这类轻装饰用）。 */
    public static int primaryFade(float alpha) {
        return fade(primary(), alpha);
    }

    // ==================== 颜色工具（与旧编辑器同语义，自成一份避免跨类私 Useful） ====================

    /** 把颜色的 alpha 按比例压低（保留原有 alpha 作为上限）。 */
    public static int fade(int color, float alpha) {
        int base = (color >>> 24) & 0xFF;
        int a = Math.round(base * clamp01(alpha));
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    /** 在两个 ARGB 之间线性插值，alpha 也一起插。 */
    public static int mix(int from, int to, float t) {
        float k = clamp01(t);
        return (chan(from, 24) + Math.round((chan(to, 24) - chan(from, 24)) * k) << 24)
                | (chan(from, 16) + Math.round((chan(to, 16) - chan(from, 16)) * k) << 16)
                | (chan(from, 8) + Math.round((chan(to, 8) - chan(from, 8)) * k) << 8)
                | (chan(from, 0) + Math.round((chan(to, 0) - chan(from, 0)) * k));
    }

    private static int chan(int color, int shift) {
        return (color >>> shift) & 0xFF;
    }

    /** 在给定底色上可读的墨色：亮底给近黑、暗底给近白（Rec.601 感知亮度）。 */
    public static int contrastOn(int background) {
        float luma = (chan(background, 16) * 0.299f
                + chan(background, 8) * 0.587f
                + chan(background, 0) * 0.114f) / 255f;
        return luma > 0.58f ? 0xFF101720 : 0xFFF4F7FB;
    }

    public static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
