package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.mc.Gui;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.mc.GuiTextField;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.gl.GL11;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

/**
 * HUD 设置的 Folia 样式界面。
 *
 * <p>视觉与交互规格移植自 本项目（AGPL-3.0 的 React 应用）的设置弹窗 —— 注意移植的是
 * 呈现参数（时长、圆角、色层、布局尺寸、命中规则），没有搬运它的源码。对应关系：
 * 遮罩淡入 + 面板 {@code y+20 / scale 0.98} 上浮入场（240ms easeOut）、居中大窗
 * （900 × 85vh、24px 圆角、不透明 {@code #18181B} 底、shadow-2xl 多层软阴影）、
 * 两枚按主题强调色染的径向光斑（裁进面板圆角）、左侧「分组标签 → 分区 → 锚点 TOC」双级目录
 * （选中分区下方展开 TOC，2px 强调色指示条按透明度切换）、内容列的锚点分组卡片
 * （12px 圆角、white/5 表面层）、切分区时整页从 ±60px 横向滑入、平滑滚动 + scrollspy
 * （24% 判定线、落点上方 12px、点击后 800ms 锁）、CHOICE 的下拉菜单（∓8px / 0.96 / 150ms）、
 * 以及「按下与松开都落在遮罩上才关闭」的关闭判定与 200ms 退场动画。</p>
 *
 * <p>与经典（{@link GuiHudEditor}）、现代（{@link GuiHudEditorPro}）共用
 * {@link HudSettingsModel} 的条目声明，三套界面永不少同一项。配色按用户选定的策略接模组主题：
 * 面板表面层保持 folia 的 zinc 深灰 + 白色半透明层，强调色（指示条 / 开关 / 选中描边 / 光斑 /
 * 滑块填充）取 {@link NCMTheme} 当前色，文字色取主题的主要/次要文字。</p>
 *
 * <p>沿用 Pro 版定下的三条工程约束：渲染与命中测试共用同一份 {@link Row} 行表；面板矩形只能从
 * {@link #panelBounds()} 拿；动画只改颜色 / 透明度 / 偏移，不改命中矩形（滚动与切页位移被折进
 * {@link #displayScroll} 与绘制矩阵，绘制与命中读同一个值）。</p>
 */
public class GuiHudEditorFolia extends GuiScreen {

    // ==================== 面板几何（folia: max-w-4xl / h-[85vh] / rounded-3xl / p-8） ====================

    private static final int PANEL_MAX_W = 900;
    private static final int PANEL_MIN_W = 420;
    private static final int PANEL_MAX_H_RATIO_PERCENT = 85;
    private static final int PANEL_MIN_H = 260;
    private static final int SCREEN_MARGIN = 20;
    private static final int PANEL_RADIUS = 24;
    private static final int PAD = 28;
    private static final int HEADER_BLOCK_H = 44;
    private static final int SIDEBAR_W = 236;
    private static final int SIDEBAR_GAP = 18;
    private static final int NAV_CAT_H = 30;
    private static final int NAV_LABEL_H = 20;
    private static final int NAV_TOC_H = 18;

    private static final int CONTENT_HEAD_H = 44;
    private static final int GROUP_HEADER_H = 24;
    private static final int ROW_H = 46;
    private static final int CARD_GAP = 12;
    private static final int CARD_RADIUS = 12;

    private static final int TRACK_W = 110;
    private static final int VALUE_W = 50;
    private static final int SWITCH_W = 48;
    private static final int SWITCH_H = 24;
    private static final int SWITCH_TRAVEL = 24;
    private static final int SWATCH_W = 40;
    private static final int SWATCH_H = 24;
    private static final int PILL_H = 24;

    private static final int THEME_CHIP_SIZE = 18;
    private static final int THEME_CHIP_GAP = 6;

    private static final int PICKER_W = 210;
    private static final int PICKER_H = 190;
    private static final int SV_W = 186;
    private static final int SV_H = 88;
    private static final int HUE_H = 10;
    private static final int HEX_INPUT_W = 86;
    private static final int HEX_INPUT_H = 18;

    private static final int MENU_ITEM_H = 26;
    private static final int MENU_MAX_VISIBLE = 7;

    /** folia 的统一外壳动效时长：进 240ms / 出 200ms / 横向滑入 60px。 */
    private static final int INTRO_MS = 240;
    private static final int OUTRO_MS = 200;
    private static final int CONTENT_SLIDE_PX = 60;
    private static final long CLICK_LOCK_MS = 800L;
    private static final double ACTIVE_LINE_RATIO = 0.24;
    private static final int ANCHOR_TOP_PADDING = 12;

    // ==================== 表面色（folia 暗色档；强调与文字接模组主题） ====================

    private static final int PANEL_BG = 0xFF18181B;
    private static final int PANEL_EDGE = 0x1AFFFFFF;
    private static final int CARD_BG = 0x0DFFFFFF;
    private static final int CARD_EDGE = 0x0DFFFFFF;
    private static final int ROW_HOVER = 0x14FFFFFF;
    private static final int DIVIDER = 0x0AFFFFFF;
    private static final int SIDE_SELECTED_BG = 0x1AFFFFFF;
    private static final int SIDE_SELECTED_EDGE = 0x33FFFFFF;
    private static final int SIDE_HOVER = 0x0DFFFFFF;
    private static final int TOC_BG = 0x0FFFFFFF;
    private static final int TOC_HOVER = 0x09FFFFFF;
    private static final int GHOST_BG = 0x0DFFFFFF;
    private static final int GHOST_HOVER = 0x1AFFFFFF;
    private static final int GHOST_EDGE = 0x1AFFFFFF;
    private static final int TRACK_BG = 0x1AFFFFFF;
    private static final int TOGGLE_OFF = 0x1AFFFFFF;
    private static final int MENU_BG = 0xF418181B;
    private static final int SUCCESS = 0xFF6EDAA0;
    private static final int ERROR = 0xFFF1767D;

    private static int textPrimary() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
    }

    private static int textSecondary() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private static int accent() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.ACCENT);
    }

    private static int accentHover() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.ACCENT_HOVER);
    }

    // ==================== 侧栏分组（folia: 分组标签 → 分区按钮） ====================

    /**
     * 侧栏的「分组标签 → 分区」两级结构。按 {@link HudSettingsModel#categories()} 的下标区间
     * 硬编码：模型加新分类时它会落进「其他设置」标签下而不是消失，这里不追求与模型完全同生死。
     */
    private static final String[] NAV_GROUP_NAMES = { "歌词与画面", "播放与界面", "外观与系统", "其他设置" };
    private static final int[][] NAV_GROUP_RANGES = { {0, 4}, {5, 8}, {9, 10}, {-1, -1} };

    private static String navGroupLabel(int categoryIndex) {
        for (int i = 0; i < NAV_GROUP_RANGES.length; i++) {
            int[] r = NAV_GROUP_RANGES[i];
            if (r[0] >= 0 && categoryIndex >= r[0] && categoryIndex <= r[1]) return NAV_GROUP_NAMES[i];
            if (r[0] < 0) return NAV_GROUP_NAMES[i];
        }
        return NAV_GROUP_NAMES[0];
    }

    // ==================== 行表（渲染与命中共用的唯一坐标来源） ====================

    /** 一条可见的行。{@code entry == null} 即分组标题行（锚点），{@code anchor >= 0} 标记可跳转锚点。 */
    private static final class Row {
        final HudSettingsModel.Entry entry;
        final String title;
        final String icon;
        final int accent;
        final int x;
        final int y;
        final int w;
        final int h;
        final boolean firstInCard;
        final boolean lastInCard;
        final int anchor;

        Row(HudSettingsModel.Entry entry, String title, String icon, int accent,
            int x, int y, int w, int h, boolean firstInCard, boolean lastInCard, int anchor) {
            this.entry = entry;
            this.title = title;
            this.icon = icon;
            this.accent = accent;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.firstInCard = firstInCard;
            this.lastInCard = lastInCard;
            this.anchor = anchor;
        }

        boolean isGroupHeader() {
            return entry == null;
        }
    }

    private static final class Card {
        final int x;
        final int y;
        final int w;
        final int h;

        Card(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** 侧栏的一个可点行：kind 0 = 分区，1 = 锚点 TOC。 */
    private static final class NavItem {
        int kind;
        int index;
        int x;
        int y;
        int w;
        int h;
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Card> cards = new ArrayList<Card>();
    private final List<NavItem> navItems = new ArrayList<NavItem>();
    private int contentHeight;
    private final int[] anchorTops = new int[32];
    private int lastMaxScroll;

    // ==================== 状态 ====================

    private int categoryIndex;
    private int[] scrollByCategory;
    private int activeAnchor = -1;
    private long clickLockUntil;

    private HudSetting draggingSlider;
    private int draggingSliderTrackX;
    private HudSettingsModel.ColorTarget editingColor = HudSettingsModel.ColorTarget.NONE;
    private float pickerHue;
    private float pickerSaturation;
    private float pickerBrightness;
    private boolean draggingSaturationBrightness;
    private boolean draggingHue;
    private GuiTextField hexColorInput;
    private boolean settingsDirty;

    /** CHOICE 下拉菜单：打开的条目、它上一帧的行矩形（portal 语义，画在内容裁剪之外）、入场进度。 */
    private HudSettingsModel.Entry openChoice;
    private int[] openChoiceRect;
    private long choiceOpenTime;
    private boolean choiceUp;
    private int choiceHoverIndex = -1;

    /** 按下归属（folia：mousedown 与 click 都落在遮罩上才关闭）。 */
    private boolean pressOutsidePanel;

    private boolean introStarted;
    private long openTime;
    private boolean closing;
    private long closeStart;

    /** 切分区横向滑入的状态：方向由新旧分区下标决定。 */
    private long contentSwitchTime;
    private int contentSlideDir = 1;
    private int contentShownCategory = Integer.MIN_VALUE;

    // ==================== 动画状态（与 Pro 同一套机制） ====================

    /** 绘制时统一乘上的不透明度，见 Pro 版的说明：入场淡入与退场淡出都靠它，漏一处就是一个不跟着淡的破口。 */
    private float layerAlpha = 1f;

    /** 平滑滚动显示位置。只在 drawScreen 推进，mouseClicked 读到的是同一份，绘制与命中不会错帧。 */
    private float displayScroll;
    private int scrollOwner = Integer.MIN_VALUE;

    private final Map<String, Float> namedProgress = new HashMap<String, Float>();
    private final Map<HudSettingsModel.Entry, float[]> rowProgress =
            new IdentityHashMap<HudSettingsModel.Entry, float[]>();
    private static final Map<CFontRenderer, GlyphInkMetrics> GRID_CACHE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    private int savedAdaptiveWidth;
    private int savedAdaptiveHeight;

    // ==================== 生命周期 ====================

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (scrollByCategory == null || scrollByCategory.length != HudSettingsModel.categories().length) {
            scrollByCategory = new int[HudSettingsModel.categories().length];
        }
        ensureHexColorInput();
        if (!introStarted) {
            introStarted = true;
            openTime = System.currentTimeMillis();
        }
    }

    @Override
    public void onGuiClosed() {
        if (settingsDirty) {
            HudConfig.save();
            settingsDirty = false;
        }
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== 主绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        boolean adaptive = false;
        try {
            RenderSystem.refreshResolution();
            adaptive = beginAdaptiveLayout();
            if (adaptive) {
                mouseX = WindowAdaptiveScale.toLayoutX(mouseX);
                mouseY = WindowAdaptiveScale.toLayoutY(mouseY);
                SharedConstants.api.getGLStateManager().pushMatrix();
                SharedConstants.api.getGLStateManager().scale(
                        WindowAdaptiveScale.correctionX(), WindowAdaptiveScale.correctionY(), 1);
            }
            CFontRenderer fr = FontManager.pf12;
            if (fr == null) return;

            if (closing) {
                float q = tween(closeStart, OUTRO_MS);
                if (q >= 1f) {
                    layerAlpha = 1f;
                    mc.setScreen(null);
                    return;
                }
                layerAlpha = 1f - q;
            } else {
                layerAlpha = tween(openTime, INTRO_MS);
            }

            try {
                drawScreenUnguarded(fr, mouseX, mouseY, partialTicks);
            } finally {
                layerAlpha = 1f;
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        } finally {
            layerAlpha = 1f;
            endScissor();
            if (adaptive) {
                SharedConstants.api.getGLStateManager().popMatrix();
            }
            restoreAdaptiveLayout(adaptive);
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void drawScreenUnguarded(CFontRenderer fr, int mouseX, int mouseY, float partialTicks) {
        int[] panel = panelBounds();
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];

        // 遮罩之下的两块 HUD：真实位置照常绘制（folia 的模态观感 —— 世界在玻璃后面，但不能操作）。
        drawHudUnderMask();

        // 遮罩：folia 主弹窗没有 backdrop-blur，就是一层 50% 黑。
        SkijaShapes.rect(0, 0, width, height, fade(0x80000000, layerAlpha));

        int wheel = closing ? 0 : Mouse.getDWheel();
        boolean overPanel = !closing && isInside(mouseX, mouseY, panelX, panelY, panelW, panelH);

        // 面板整体做入场/退场的位移与缩放。矩阵只作用于绘制，命中测试仍走 panelBounds() 的最终矩形 ——
        // 240ms 内偏差不到 2% 缩放，不足以造成可感知的错点。
        float shell = closing
                ? tween(closeStart, OUTRO_MS)
                : tween(openTime, INTRO_MS);
        double rise = closing ? 16.0 * shell : 20.0 * (1.0 - shell);
        double scale = closing ? 1.0 - 0.015 * shell : 0.98 + 0.02 * shell;
        double cx = panelX + panelW / 2.0;
        double cy = panelY + panelH / 2.0;
        SkijaGfx.pushMatrix();
        SkijaGfx.translate(cx, cy + rise);
        SkijaGfx.scale(scale, scale);
        SkijaGfx.translate(-cx, -cy);
        try {
            updatePickerWhileDragging(mouseX, mouseY, panelX, panelY, panelW, panelH);
            updateSliderWhileDragging(mouseX);

            drawPanelShadow(panelX, panelY, panelW, panelH);
            drawRoundedRect(panelX, panelY, panelW, panelH, PANEL_RADIUS, PANEL_BG);
            drawBlobs(panelX, panelY, panelW, panelH);
            drawRoundedOutline(panelX, panelY, panelW, panelH, PANEL_RADIUS, 1, PANEL_EDGE);

            drawPanelHeader(fr, panelX, panelY, panelW, mouseX, mouseY);

            int sideW = sidebarWidth(panelW);
            int bodyTop = panelY + PAD + HEADER_BLOCK_H;
            int bodyH = panelH - PAD * 2 - HEADER_BLOCK_H;
            int sideX = panelX + PAD;
            buildNav(sideX, bodyTop, sideW, bodyH);
            drawNav(fr, sideX, bodyTop, sideW, bodyH, mouseX, mouseY);

            int contentX = sideX + sideW + SIDEBAR_GAP;
            int contentW = panelX + panelW - PAD - SIDEBAR_GAP / 2 - contentX;
            drawContent(fr, contentX, bodyTop, contentW, bodyH, wheel, overPanel && mouseX >= contentX,
                    mouseX, mouseY);

            if (editingColor != HudSettingsModel.ColorTarget.NONE) {
                drawColorPicker(fr, panelX, panelY, panelW, panelH, mouseX, mouseY);
            }
            if (openChoice != null) {
                drawChoiceMenu(fr, mouseX, mouseY);
            }
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    /** 面板之外（遮罩之下）的两块 HUD 实时预览：只画真实内容，不放占位卡，避免遮罩后出现幽灵边框。 */
    private void drawHudUnderMask() {
        MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
        MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
        if (hasLiveInfoPreview()) {
            renderLive(info);
        }
        if (hasLiveLyricsPreview()) {
            int lyricW = (int) (HudConfig.lyricWidth * HudConfig.lyricScale);
            int lyricH = (int) (HudConfig.lyricHeight * HudConfig.lyricScale);
            int lyricX = (int) (HudConfig.lyricX * (width - lyricW));
            int lyricY = (int) (HudConfig.lyricY * (height - lyricH));
            renderLiveLyrics(lyrics, lyricX, lyricY);
        }
    }

    // ==================== 面板骨架 ====================

    private boolean beginAdaptiveLayout() {
        RenderSystem.refreshResolution();
        WindowAdaptiveScale.begin();
        if (!WindowAdaptiveScale.isTransforming()) return false;
        savedAdaptiveWidth = width;
        savedAdaptiveHeight = height;
        width = WindowAdaptiveScale.layoutWidth();
        height = WindowAdaptiveScale.layoutHeight();
        return true;
    }

    private void restoreAdaptiveLayout(boolean active) {
        if (!active) return;
        width = savedAdaptiveWidth;
        height = savedAdaptiveHeight;
        WindowAdaptiveScale.end();
    }

    /** 面板矩形唯一来源（约束 2）。居中，宽 900 / 高 85vh，小窗时钳到屏内。 */
    private int[] panelBounds() {
        int panelW = Math.max(PANEL_MIN_W, Math.min(PANEL_MAX_W, width - SCREEN_MARGIN * 2));
        int panelH = Math.max(PANEL_MIN_H,
                Math.min(height * PANEL_MAX_H_RATIO_PERCENT / 100, height - SCREEN_MARGIN * 2));
        int maxX = Math.max(0, width - panelW);
        int maxY = Math.max(0, height - panelH);
        return new int[] { maxX / 2, maxY / 2, panelW, panelH };
    }

    /** 窄屏先收侧栏，给内容列留最低可用品宽。 */
    private int sidebarWidth(int panelW) {
        return panelW - PAD * 2 - SIDEBAR_W - SIDEBAR_GAP < 300 ? 150 : SIDEBAR_W;
    }

    /** shadow-2xl 的近似：三层外扩软边。Skija 没有 box-shadow，叠层是这个工程一贯的做法（Pro 同款）。 */
    private void drawPanelShadow(int x, int y, int w, int h) {
        drawRoundedRect(x + 1, y + 3, w, h + 8, PANEL_RADIUS + 4, 0x08000000);
        drawRoundedRect(x + 2, y + 6, w, h + 6, PANEL_RADIUS + 2, 0x10000000);
        drawRoundedRect(x + 1, y + 3, w, h + 2, PANEL_RADIUS + 1, 0x18000000);
    }

    /**
     * 两枚装饰光斑（folia：256px 圆、blur-80、10% 不透明度、对角摆放）。
     * 80px 的高斯模糊用一个柔和的径向渐变圆盘近似；裁进面板圆角，越界部分不外泄。
     */
    private void drawBlobs(int panelX, int panelY, int panelW, int panelH) {
        final double radius = 128.0;
        final int acc = accent();
        final int sec = accentHover();
        final int px = panelX;
        final int py = panelY;
        final int pw = panelW;
        final int ph = panelH;
        SkijaClip.beginClip(new Runnable() {
            public void run() {
                SkijaShapes.roundedRect(px, py, pw, ph, PANEL_RADIUS, 0xFFFFFFFF);
            }
        });
        try {
            SkijaShapes.radialDisc(px + pw - 40, py + 40, radius, fade(acc, 0.10f * layerAlpha), fade(acc, 0f));
            SkijaShapes.radialDisc(px + 40, py + ph - 40, radius, fade(sec, 0.10f * layerAlpha), fade(sec, 0f));
        } finally {
            SkijaClip.endClip();
        }
    }

    /** 顶部：标题 + 副标题 + 右侧的样式切换胶囊与关闭按钮（folia 的 X 在右上角、rounded-full）。 */
    private void drawPanelHeader(CFontRenderer fr, int panelX, int panelY, int panelW,
                                 int mouseX, int mouseY) {
        int titleY = panelY + PAD - 4;
        drawText(FontManager.pf14bold, "音乐 HUD 设置", panelX + PAD, titleY, textPrimary());
        drawText(fr, "实时预览 · 自动保存 · 下次打开时记住这套外观",
                panelX + PAD, titleY + 17, fade(textSecondary(), 0.85f));

        int[] close = closeButton(panelX, panelY, panelW);
        float cp = named("header.close", isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])
                && !closing);
        drawRoundedRect(close[0], close[1], close[2], close[3], close[3] * 0.5,
                mix(0x08FFFFFF, GHOST_HOVER, cp));
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CLOSE,
                close[0] + close[2] / 2.0, close[1] + close[3] / 2.0,
                mix(fade(textPrimary(), 0.3f), textPrimary(), cp));

        int[] classic = classicButton(panelX, panelY, panelW);
        drawHeaderPill("header.classic", fr, "经典", classic, mouseX, mouseY);
        int[] modern = modernButton(panelX, panelY, panelW);
        drawHeaderPill("header.modern", fr, "现代", modern, mouseX, mouseY);
        if (OneConfigHudBridge.isAvailable()) {
            int[] oneConfig = oneConfigButton(panelX, panelY, panelW);
            drawHeaderPill("header.oneconfig", fr, "OneConfig", oneConfig, mouseX, mouseY);
        }
    }

    private void drawHeaderPill(String key, CFontRenderer fr, String label, int[] box,
                                int mouseX, int mouseY) {
        float hp = named(key, !closing && isInside(mouseX, mouseY, box[0], box[1], box[2], box[3]));
        drawRoundedRect(box[0], box[1], box[2], box[3], box[3] * 0.5, mix(GHOST_BG, GHOST_HOVER, hp));
        drawRoundedOutline(box[0], box[1], box[2], box[3], box[3] * 0.5, 1, GHOST_EDGE);
        drawCenteredText(fr, label, box[0] + box[2] / 2.0, box[1] + (box[3] - 8) / 2.0,
                mix(fade(textPrimary(), 0.55f), textPrimary(), hp));
    }

    private int headerButtonY(int panelY) {
        return panelY + PAD - 6;
    }

    private int[] closeButton(int panelX, int panelY, int panelW) {
        return new int[] { panelX + panelW - PAD - 26, headerButtonY(panelY), 26, 26 };
    }

    private int[] classicButton(int panelX, int panelY, int panelW) {
        int w = pillLabelWidth("经典");
        int[] close = closeButton(panelX, panelY, panelW);
        return new int[] { close[0] - 8 - w, close[1] + 2, w, 22 };
    }

    private int[] modernButton(int panelX, int panelY, int panelW) {
        int w = pillLabelWidth("现代");
        int[] classic = classicButton(panelX, panelY, panelW);
        return new int[] { classic[0] - 6 - w, classic[1], w, 22 };
    }

    private int[] oneConfigButton(int panelX, int panelY, int panelW) {
        int w = pillLabelWidth("OneConfig");
        int[] modern = modernButton(panelX, panelY, panelW);
        return new int[] { modern[0] - 6 - w, modern[1], w, 22 };
    }

    private static int pillLabelWidth(String label) {
        CFontRenderer fr = FontManager.pf12;
        return 24 + (fr == null ? 0 : fr.getStringWidth(label));
    }

    // ==================== 侧栏：分组标签 → 分区 → 锚点 TOC ====================

    /**
     * 构建侧栏行表（绘制与命中同一份）。选中分区下方展开它的锚点 TOC；
     * 纵向空间不够时先丢 TOC，再把分区行压扁到能放下（Pro 的 navRowHeight 思路）。
     */
    private void buildNav(int sideX, int sideY, int sideW, int sideH) {
        navItems.clear();
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int selected = clampCategory();

        int visibleGroups = visibleGroupCount(categories[selected]);
        boolean withToc = sideH >= estimateNavHeight(categories.length, visibleGroups);
        int needed = estimateNavHeight(categories.length, withToc ? visibleGroups : 0);
        int shrink = needed > sideH ? needed - sideH : 0;

        int y = sideY;
        int lastLabelHash = 0;
        for (int i = 0; i < categories.length; i++) {
            String label = navGroupLabel(i);
            if (label.hashCode() != lastLabelHash) {
                lastLabelHash = label.hashCode();
                y += NAV_LABEL_H;
            }
            int rowH = Math.max(22, NAV_CAT_H - Math.min(8, shrink / Math.max(1, categories.length)));
            NavItem item = new NavItem();
            item.kind = 0;
            item.index = i;
            item.x = sideX;
            item.y = y;
            item.w = sideW;
            item.h = rowH;
            navItems.add(item);
            y += rowH + 2;
            if (!withToc || i != selected) continue;
            HudSettingsModel.Category category = categories[i];
            for (int g = 0; g < category.groups.length; g++) {
                if (!groupHasVisibleEntry(category.groups[g])) continue;
                NavItem toc = new NavItem();
                toc.kind = 1;
                toc.index = g;
                toc.x = sideX + 26;
                toc.y = y;
                toc.w = Math.max(40, sideW - 26);
                toc.h = NAV_TOC_H;
                navItems.add(toc);
                y += NAV_TOC_H;
            }
            if (i == selected) y += 4;
        }
    }

    private int estimateNavHeight(int categories, int tocRows) {
        return categories * (NAV_CAT_H + 2) + NAV_GROUP_NAMES.length * NAV_LABEL_H
                + tocRows * NAV_TOC_H;
    }

    private int visibleGroupCount(HudSettingsModel.Category category) {
        int count = 0;
        for (HudSettingsModel.Group group : category.groups) {
            if (groupHasVisibleEntry(group)) count++;
        }
        return count;
    }

    private static boolean groupHasVisibleEntry(HudSettingsModel.Group group) {
        for (HudSettingsModel.Entry entry : group.entries) {
            if (entry.isVisible()) return true;
        }
        return false;
    }

    private void drawNav(CFontRenderer fr, int sideX, int sideY, int sideW, int sideH,
                         int mouseX, int mouseY) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int selected = clampCategory();
        // 侧栏与内容列之间的 1px 列分隔（folia 侧栏 border-r）。
        drawRoundedRect(sideX + sideW + SIDEBAR_GAP / 2 - 1, sideY - HEADER_BLOCK_H / 2,
                1, sideH + HEADER_BLOCK_H / 2, 1, fade(PANEL_EDGE, 0.6f));

        int labelHash = 0;
        for (int i = 0; i < navItems.size(); i++) {
            NavItem item = navItems.get(i);
            if (item.kind == 0) {
                String label = navGroupLabel(item.index);
                if (label.hashCode() != labelHash) {
                    labelHash = label.hashCode();
                    // 分组标签用粗体小号：folia 的 px-1 text-xs font-bold opacity-50。
                    CFontRenderer labelFr = FontManager.pf12bold != null ? FontManager.pf12bold : fr;
                    drawText(labelFr, label, item.x + 4, item.y - NAV_LABEL_H + 6,
                            fade(textSecondary(), 0.55f));
                }
                drawNavCategory(fr, item, categories[item.index], item.index == selected, mouseX, mouseY);
            } else {
                drawNavToc(fr, item, categories[selected], mouseX, mouseY);
            }
        }
    }

    private void drawNavCategory(CFontRenderer fr, NavItem item, HudSettingsModel.Category category,
                                 boolean current, int mouseX, int mouseY) {
        boolean hovered = !closing && isInside(mouseX, mouseY, item.x, item.y, item.w, item.h);
        float hp = named("nav." + item.index, hovered && !current);
        if (current) {
            drawRoundedRect(item.x, item.y, item.w, item.h, 12, SIDE_SELECTED_BG);
            drawRoundedOutline(item.x, item.y, item.w, item.h, 12, 1, SIDE_SELECTED_EDGE);
        } else if (hp > 0.02f) {
            drawRoundedRect(item.x, item.y, item.w, item.h, 12, fade(SIDE_HOVER, hp));
        }
        // 图标取分类自己的标识色（folia 是单色 + opacity；这里保留 Pro 已验证的彩色图标语义）。
        drawIcon(FontManager.settingsIcon32, category.icon, item.x + 18, item.y + item.h / 2.0,
                current ? category.accent : fade(category.accent, 0.75f + 0.25f * hp));
        drawText(FontManager.pf12bold, category.title, item.x + 32, item.y + item.h / 2.0 - 5,
                current ? textPrimary() : mix(fade(textSecondary(), 0.9f), textPrimary(), hp));
        String count = String.valueOf(category.entryCount());
        drawText(fr, count, item.x + item.w - 8 - fr.getStringWidth(count), item.y + item.h / 2.0 - 4,
                fade(textSecondary(), 0.5f));
    }

    private void drawNavToc(CFontRenderer fr, NavItem item, HudSettingsModel.Category category,
                            int mouseX, int mouseY) {
        HudSettingsModel.Group[] groups = category.groups;
        if (item.index < 0 || item.index >= groups.length) return;
        boolean active = activeAnchor == item.index;
        boolean hovered = !closing && isInside(mouseX, mouseY, item.x, item.y, item.w, item.h);
        // folia：指示条只有 opacity 0↔1 切换（150ms），没有位移动画 —— 这里用 named() 指数插值近似。
        float ap = named("toc.on." + item.index, active);
        float hp = named("toc.hv." + item.index, hovered && !active);
        if (ap > 0.02f || hp > 0.02f) {
            drawRoundedRect(item.x - 4, item.y + 1, item.w + 4, item.h - 2, 6,
                    mix(fade(TOC_BG, ap), TOC_HOVER, hp));
        }
        drawRoundedRect(item.x - 3, item.y + 3, 2, item.h - 6, 1, fade(accent(), ap));
        float textAlpha = 0.55f + 0.4f * ap + 0.2f * hp;
        drawText(fr, groups[item.index].title, item.x + 8, item.y + (item.h - 8) / 2.0,
                mix(fade(textSecondary(), textAlpha), textPrimary(), ap));
    }

    // ==================== 内容列 ====================

    private void drawContent(CFontRenderer fr, int contentX, int listY, int contentW, int listH,
                             int wheel, boolean overContent, int mouseX, int mouseY) {
        HudSettingsModel.Category active = HudSettingsModel.categories()[clampCategory()];

        if (contentShownCategory != clampCategory()) {
            int from = contentShownCategory == Integer.MIN_VALUE ? clampCategory() : contentShownCategory;
            contentSlideDir = clampCategory() >= from ? 1 : -1;
            contentShownCategory = clampCategory();
            contentSwitchTime = System.currentTimeMillis();
            activeAnchor = -1;
        }
        float slideT = tween(contentSwitchTime, INTRO_MS);

        int owner = clampCategory();
        if (owner != scrollOwner) {
            scrollOwner = owner;
            displayScroll = scroll();
        }
        layoutRows(contentX, listY, contentW, active, 0f);
        int maxScroll = Math.max(0, contentHeight - listH);
        lastMaxScroll = maxScroll;
        if (wheel != 0 && overContent && closing == false) {
            setScroll(scroll() + (wheel > 0 ? -30 : 30), maxScroll);
            clickLockUntil = 0L;
        } else {
            setScroll(scroll(), maxScroll);
        }
        displayScroll = Interpolations.interpolate(displayScroll, scroll(), 16f);
        if (Math.abs(displayScroll - scroll()) < 0.4f) displayScroll = scroll();
        layoutRows(contentX, listY, contentW, active, (1f - slideT) * CONTENT_SLIDE_PX * contentSlideDir);

        // scrollspy（folia useSettingsScrollSpy）：24% 判定线 + 落点上沿 -12px + 点击后 800ms 锁。
        if (System.currentTimeMillis() >= clickLockUntil) {
            int line = listY + (int) (listH * ACTIVE_LINE_RATIO) - ANCHOR_TOP_PADDING;
            int found = -1;
            for (Row row : rows) {
                if (row.anchor >= 0 && row.y <= line + 2) found = row.anchor;
            }
            if (displayScroll >= maxScroll - 2 && maxScroll > 0) {
                for (Row row : rows) {
                    if (row.anchor >= 0) found = row.anchor;
                }
            }
            if (found >= 0) activeAnchor = found;
        }

        beginScissor(contentX - 2, listY, contentW + 10, listH);
        float outerAlpha = layerAlpha;
        try {
            layerAlpha = outerAlpha * slideT;
            SkijaGfx.pushMatrix();
            SkijaGfx.translate((1f - slideT) * CONTENT_SLIDE_PX * contentSlideDir, 0f);
            try {
                drawText(FontManager.pf14bold, active.title, contentX, listY + 2, textPrimary());
                drawText(fr, active.subtitle, contentX, listY + 20, fade(textSecondary(), 0.85f));
                for (Card card : cards) {
                    if (card.y + card.h < listY - 4 || card.y > listY + listH + 4) continue;
                    drawRoundedRect(card.x, card.y, card.w, card.h, CARD_RADIUS, CARD_BG);
                    drawRoundedOutline(card.x, card.y, card.w, card.h, CARD_RADIUS, 1, CARD_EDGE);
                }
                for (Row row : rows) {
                    if (row.y + row.h < listY - 4 || row.y > listY + listH + 4) continue;
                    drawRow(fr, row, mouseX, mouseY);
                }
            } finally {
                SkijaGfx.popMatrix();
            }
        } finally {
            layerAlpha = outerAlpha;
            endScissor();
        }

        if (maxScroll > 0) {
            int trackTop = listY + 2;
            int trackH = listH - 4;
            int thumbH = Math.max(24, trackH * listH / Math.max(1, contentHeight));
            float thumbY = trackTop + (trackH - thumbH) * clamp01(displayScroll / maxScroll);
            // folia 全局滚动条：8px 轨道、4px 圆角 thumb、三档灰。这里收细一档以免抢戏。
            drawRoundedRect(contentX + contentW + 3, trackTop, 3, trackH, 2, 0x14FFFFFF);
            drawRoundedRect(contentX + contentW + 2, thumbY, 5, thumbH, 3,
                    named("scrollbar", overContent) > 0.3f ? 0x88FFFFFF : 0x55FFFFFF);
        }
    }

    /** 把当前分类算成行表（约束 1：绘制与命中共用唯一坐标来源）。 */
    private void layoutRows(int contentX, int listY, int contentW,
                            HudSettingsModel.Category active, float slideForDraw) {
        rows.clear();
        cards.clear();
        int rowX = contentX;
        int rowW = contentW - 8;
        int cursor = listY + CONTENT_HEAD_H - Math.round(displayScroll);
        int top = listY + CONTENT_HEAD_H - Math.round(displayScroll);

        int anchorIndex = 0;
        for (int g = 0; g < active.groups.length; g++) {
            HudSettingsModel.Group group = active.groups[g];
            List<HudSettingsModel.Entry> visible = new ArrayList<HudSettingsModel.Entry>();
            for (HudSettingsModel.Entry entry : group.entries) {
                if (entry.isVisible()) visible.add(entry);
            }
            if (visible.isEmpty()) continue;
            if (anchorIndex < anchorTops.length) anchorTops[anchorIndex] = cursor - top;
            rows.add(new Row(null, group.title, group.icon, active.accent,
                    rowX, cursor, rowW, GROUP_HEADER_H, false, false, anchorIndex));
            cursor += GROUP_HEADER_H;
            anchorIndex++;

            int cardTop = cursor;
            for (int index = 0; index < visible.size(); index++) {
                HudSettingsModel.Entry entry = visible.get(index);
                int h = entry.kind == HudSettingsModel.Kind.THEME_GRID
                        ? themeGridHeight(rowW) + 16 : ROW_H;
                rows.add(new Row(entry, entry.getLabel(), null, active.accent,
                        rowX, cursor, rowW, h, index == 0, index == visible.size() - 1, -1));
                cursor += h;
            }
            cards.add(new Card(rowX, cardTop, rowW, cursor - cardTop));
            cursor += CARD_GAP;
        }
        contentHeight = cursor - top + 6;
    }

    private void drawRow(CFontRenderer fr, Row row, int mouseX, int mouseY) {
        if (row.isGroupHeader()) {
            // folia SettingsSectionHeading：text-sm font-bold opacity-50 + 14px 图标。
            drawIcon(FontManager.settingsIcon24, row.icon, row.x + 8, row.y + GROUP_HEADER_H / 2.0 + 1,
                    fade(row.accent, 0.9f));
            drawText(FontManager.pf12bold, row.title, row.x + 19, row.y + 8, fade(textSecondary(), 0.95f));
            return;
        }

        boolean hovered = !closing && isInside(mouseX, mouseY, row.x, row.y, row.w, row.h);
        boolean dragging = row.entry.kind == HudSettingsModel.Kind.SLIDER
                && draggingSlider == row.entry.setting;
        float[] state = progressOf(row.entry, row.entry.kind == HudSettingsModel.Kind.TOGGLE
                && row.entry.isOn());
        state[0] = Interpolations.interpolate(state[0], hovered || dragging ? 1f : 0f, 20f);
        state[2] = Interpolations.interpolate(state[2], dragging ? 1f : (hovered ? 0.6f : 0f), 22f);
        float hp = state[0];

        if (!row.firstInCard) {
            drawRoundedRect(row.x + 12, row.y, row.w - 24, 1, 1, fade(DIVIDER, 1f - hp));
        }
        if (hp > 0.01f) {
            drawRoundedRect(row.x + 2, row.y + 1, row.w - 4, row.h - 2, CARD_RADIUS - 3,
                    fade(ROW_HOVER, hp));
        }

        if (row.entry.kind == HudSettingsModel.Kind.THEME_GRID) {
            drawText(FontManager.pf12bold, row.entry.getLabel(), row.x + 14, row.y + 10, textPrimary());
            drawThemeChips(row.x + 14, row.y + 27, row.w - 28, mouseX, mouseY);
            return;
        }

        int controlLeft = controlLeft(row);
        drawText(FontManager.pf12bold, row.entry.getLabel(), row.x + 14, row.y + 9,
                mix(fade(textPrimary(), 0.92f), textPrimary(), hp));
        String description = row.entry.description;
        if (description != null && !description.isEmpty()) {
            double available = Math.max(20, controlLeft - (row.x + 14) - 10);
            drawText(fr, fr.trim(description, available), row.x + 14, row.y + 26,
                    mix(fade(textSecondary(), 0.5f), fade(textSecondary(), 0.95f), hp));
        }

        int centerY = row.y + row.h / 2;
        int rowRight = row.x + row.w - 14;
        switch (row.entry.kind) {
            case SLIDER:
                drawSliderControl(fr, row.entry.setting, rowRight, centerY, row.accent, state[2]);
                break;
            case TOGGLE: {
                state[1] = Interpolations.interpolate(state[1], row.entry.isOn() ? 1f : 0f, 12f);
                drawSwitch(rowRight - SWITCH_W, centerY - SWITCH_H / 2, state[1], hp);
                break;
            }
            case COLOR:
                drawSwatch(rowRight - SWATCH_W, centerY - SWATCH_H / 2, row.entry.getColor(),
                        editingColor == row.entry.colorTarget, hp);
                break;
            case CHOICE: {
                boolean open = openChoice == row.entry;
                float op = named("choice.open." + System.identityHashCode(row.entry), open);
                drawChoicePill(fr, row, rowRight, centerY, op);
                break;
            }
            case ACTION: {
                int[] box = actionBox(rowRight, centerY);
                float ap = named("action." + System.identityHashCode(row.entry),
                        !closing && isInside(mouseX, mouseY, box[0], box[1], box[2], box[3]));
                drawRoundedRect(box[0], box[1], box[2], box[3], 12, mix(GHOST_BG, GHOST_HOVER, ap));
                drawRoundedOutline(box[0], box[1], box[2], box[3], 12, 1, GHOST_EDGE);
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT,
                        box[0] + box[2] - 11 + ap * 1.5, box[1] + box[3] / 2.0,
                        mix(fade(textSecondary(), 0.45f), textSecondary(), ap));
                drawText(fr, "执行", box[0] + 12, box[1] + (box[3] - 8) / 2.0, textPrimary());
                break;
            }
            default:
                break;
        }
    }

    private void drawChoicePill(CFontRenderer fr, Row row, int rowRight, int centerY, float openProgress) {
        String value = row.entry.getValueText();
        int w = Math.max(64, Math.min(150, (FontManager.pf12 == null ? 0 : FontManager.pf12.getStringWidth(value)) + 36));
        int x = rowRight - w;
        int y = centerY - PILL_H / 2;
        drawRoundedRect(x, y, w, PILL_H, 12, GHOST_BG);
        // 展开态：边框换成强调色并描一圈外环（folia CustomSelect expanded）。
        drawRoundedOutline(x, y, w, PILL_H, 12, 1,
                mix(GHOST_EDGE, fade(accent(), 0.9f), openProgress));
        if (openProgress > 0.02f) {
            drawRoundedOutline(x - 1, y - 1, w + 2, PILL_H + 2, 13, 1, fade(accent(), 0.5f * openProgress));
        }
        drawText(fr, fr.trim(value, w - 28), x + 12, y + (PILL_H - 8) / 2.0, textPrimary());
        // chevron 200ms 翻转：这里用开合进度把右折角旋转近似成上下翻转的纵向偏移与色调。
        double cx = x + w - 13;
        double cy = y + PILL_H / 2.0;
        int tint = mix(fade(textSecondary(), 0.7f), accent(), openProgress);
        if (openProgress < 0.5f) {
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT, cx, cy, tint);
        } else {
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.EXPAND, cx, cy - 1, tint);
        }
    }

    /** 控件区左边界：说明文字在它之前收住（同 Pro）。 */
    private int controlLeft(Row row) {
        int rowRight = row.x + row.w - 14;
        switch (row.entry.kind) {
            case SLIDER:
                return rowRight - VALUE_W - TRACK_W - 12;
            case TOGGLE:
                return rowRight - SWITCH_W - 12;
            case COLOR:
                return rowRight - SWATCH_W - 12;
            case CHOICE:
                return rowRight - choicePillWidth(row.entry) - 12;
            case ACTION:
                return rowRight - 76 - 12;
            default:
                return rowRight;
        }
    }

    private int choicePillWidth(HudSettingsModel.Entry entry) {
        String value = entry.getValueText();
        CFontRenderer fr = FontManager.pf12;
        int text = fr == null || value == null ? 0 : fr.getStringWidth(value);
        return Math.max(64, Math.min(150, text + 36));
    }

    private int[] actionBox(int rowRight, int centerY) {
        return new int[] { rowRight - 76, centerY - PILL_H / 2, 76, PILL_H };
    }

    // ==================== 控件 ====================

    private void drawSliderControl(CFontRenderer fr, HudSetting setting, int rowRight,
                                   int centerY, int accent, float knobProgress) {
        String value = HudSettingsModel.formatValue(setting, setting.getValue());
        drawText(fr, value, rowRight - fr.getStringWidth(value), centerY - 4,
                mix(fade(textSecondary(), 0.6f), textPrimary(), knobProgress));

        int trackX = rowRight - VALUE_W - TRACK_W;
        double trackY = centerY - 3;
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        double fillW = TRACK_W * progress;
        drawRoundedRect(trackX, trackY, TRACK_W, 6, 3, TRACK_BG);
        if (fillW > 1.0) {
            drawRoundedRect(trackX, trackY, fillW, 6, 3, accent);
        }
        // folia 自绘滑块：12px 圆钮，hover ×1.25。
        double knobX = trackX + fillW;
        double radius = 6.0 + 1.5 * knobProgress;
        drawRoundedRect(knobX - radius, centerY - radius, radius * 2, radius * 2, radius, 0x40000000);
        drawRoundedRect(knobX - radius, centerY - radius - 1, radius * 2, radius * 2, radius, 0xFFF6F8FC);
    }

    private static int sliderTrackX(int rowRight) {
        return rowRight - VALUE_W - TRACK_W;
    }

    /** folia 主开关几何：48×24 轨道、16px 旋钮、24px 行程、300ms 位移。 */
    private void drawSwitch(int x, int y, float progress, float hover) {
        drawRoundedRect(x, y, SWITCH_W, SWITCH_H, SWITCH_H * 0.5,
                mix(mix(TOGGLE_OFF, 0x2EFFFFFF, hover), accent(), progress));
        double knobX = x + 4 + SWITCH_TRAVEL * progress;
        drawRoundedRect(knobX, y + 5, 16, 16, 8, 0x40000000);
        drawRoundedRect(knobX, y + 4, 16, 16, 8, 0xFFFFFFFF);
    }

    private void drawSwatch(int x, int y, Color color, boolean editing, float hover) {
        int base = (color == null ? 0xFF9AA6B8 : color.getRGB()) | 0xFF000000;
        drawRoundedRect(x, y, SWATCH_W, SWATCH_H, 10, base);
        drawRoundedOutline(x, y, SWATCH_W, SWATCH_H, 10, 1,
                editing ? fade(accent(), 0.95f) : mix(0x26FFFFFF, 0x55FFFFFF, hover));
        if (hover > 0.05f || editing) {
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE,
                    x + SWATCH_W / 2.0, y + SWATCH_H / 2.0,
                    fade(contrastOn(base), Math.max(hover, editing ? 1f : 0f)));
        }
    }

    // ==================== CHOICE 下拉菜单（folia CustomSelectMenu portal） ====================

    private void drawChoiceMenu(CFontRenderer fr, int mouseX, int mouseY) {
        String[] options = openChoice.getChoices();
        if (openChoiceRect == null || options.length == 0) {
            openChoice = null;
            return;
        }
        int itemW = 0;
        for (String option : options) {
            itemW = Math.max(itemW, fr.getStringWidth(option));
        }
        int w = Math.max(140, Math.min(220, itemW + 44));
        int lines = Math.min(options.length, MENU_MAX_VISIBLE);
        int h = lines * MENU_ITEM_H + 12;
        int rowRight = openChoiceRect[0] + openChoiceRect[2];
        int x = Math.max(8, Math.min(width - w - 8, rowRight - w));
        int y = choiceUp ? openChoiceRect[1] - h - 4 : openChoiceRect[1] + openChoiceRect[3] + 4;

        float t = tween(choiceOpenTime, 150);
        float dy = (1f - t) * (choiceUp ? 8f : -8f);
        float sc = 0.96f + 0.04f * t;

        float outerAlpha = layerAlpha;
        try {
            layerAlpha = outerAlpha * t;
            double cx = x + w / 2.0;
            double cy = y + h / 2.0;
            SkijaGfx.pushMatrix();
            SkijaGfx.translate(cx, cy + dy);
            SkijaGfx.scale(sc, sc);
            SkijaGfx.translate(-cx, -cy);
            try {
                drawPanelShadow(x, y, w, h);
                drawRoundedRect(x, y, w, h, 12, MENU_BG);
                drawRoundedOutline(x, y, w, h, 12, 1, 0x24F4F4F5);
                String current = openChoice.getValueText();
                choiceHoverIndex = -1;
                for (int i = 0; i < options.length && i < MENU_MAX_VISIBLE; i++) {
                    int iy = y + 6 + i * MENU_ITEM_H;
                    boolean selected = options[i].equals(current);
                    boolean hovered = isInside(mouseX, mouseY, x + 6, iy, w - 12, MENU_ITEM_H - 2);
                    if (hovered) choiceHoverIndex = i;
                    if (selected) {
                        drawRoundedRect(x + 6, iy, w - 12, MENU_ITEM_H - 2, 8, fade(accent(), 0.11f));
                    } else if (hovered) {
                        drawRoundedRect(x + 6, iy, w - 12, MENU_ITEM_H - 2, 8, 0x0FFFFFFF);
                    }
                    drawText(fr, fr.trim(options[i], w - 40), x + 14, iy + (MENU_ITEM_H - 10) / 2,
                            selected ? textPrimary() : fade(textPrimary(), 0.85f));
                    if (selected) {
                        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                                x + w - 18, iy + (MENU_ITEM_H - 2) / 2.0, accent());
                    }
                }
                if (options.length > MENU_MAX_VISIBLE) {
                    drawText(fr, "…共 " + options.length + " 项，用循环按钮切换其余",
                            x + 14, y + h - 16, fade(textSecondary(), 0.5f));
                }
            } finally {
                SkijaGfx.popMatrix();
            }
        } finally {
            layerAlpha = outerAlpha;
        }
    }

    private void openChoiceMenu(HudSettingsModel.Entry entry, Row row) {
        openChoice = entry;
        // 记下行矩形（内容坐标里扣掉本次 slide 之后的实际位置即可；slide 只影响绘制不影响命中）。
        openChoiceRect = new int[] { row.x + row.w - choicePillWidth(entry) - 14, row.y,
                choicePillWidth(entry), row.h };
        choiceUp = row.y + row.h + 240 > height / 2;
        choiceOpenTime = System.currentTimeMillis();
    }

    private boolean handleChoiceMenuClick(int mouseX, int mouseY) {
        if (openChoice == null) return false;
        String[] options = openChoice.getChoices();
        CFontRenderer fr = FontManager.pf12;
        if (openChoiceRect == null || options.length == 0 || fr == null) {
            openChoice = null;
            return true;
        }
        int itemW = 0;
        for (String option : options) itemW = Math.max(itemW, fr.getStringWidth(option));
        int w = Math.max(140, Math.min(220, itemW + 44));
        int lines = Math.min(options.length, MENU_MAX_VISIBLE);
        int h = lines * MENU_ITEM_H + 12;
        int x = Math.max(8, Math.min(width - w - 8, openChoiceRect[0] + openChoiceRect[2] - w));
        int y = choiceUp ? openChoiceRect[1] - h - 4 : openChoiceRect[1] + openChoiceRect[3] + 4;
        if (isInside(mouseX, mouseY, x, y, w, h)) {
            for (int i = 0; i < options.length && i < MENU_MAX_VISIBLE; i++) {
                int iy = y + 6 + i * MENU_ITEM_H;
                if (isInside(mouseX, mouseY, x + 6, iy, w - 12, MENU_ITEM_H - 2)) {
                    openChoice.setChoice(options[i]);
                    settingsDirty = true;
                }
            }
            openChoice = null;
            return true;
        }
        openChoice = null;
        return true;
    }

    // ==================== 主题色卡 ====================

    private int themeGridColumns(int rowW) {
        int cols = (Math.max(1, rowW - 4)) / (THEME_CHIP_SIZE + THEME_CHIP_GAP);
        return Math.max(6, Math.min(16, cols));
    }

    private int themeGridHeight(int rowW) {
        int count = NCMTheme.ThemePreset.values().length;
        int lines = Math.max(1, (count + themeGridColumns(rowW) - 1) / themeGridColumns(rowW));
        return lines * (THEME_CHIP_SIZE + THEME_CHIP_GAP) + 2;
    }

    private void drawThemeChips(int x, int y, int rowW, int mouseX, int mouseY) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        int cols = themeGridColumns(rowW);
        for (int i = 0; i < presets.length; i++) {
            int chipX = x + (i % cols) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / cols) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            boolean selected = presets[i] == current;
            // folia 色板 swatch：hover ×1.10 / active ×0.95，用边长插值近似缩放。
            float hp = named("theme." + i, !closing && isInside(mouseX, mouseY,
                    chipX - 2, chipY - 2, THEME_CHIP_SIZE + 4, THEME_CHIP_SIZE + 4));
            float grow = 1f + 0.10f * hp;
            int size = Math.round(THEME_CHIP_SIZE * grow);
            int ox = chipX + (THEME_CHIP_SIZE - size) / 2;
            int oy = chipY + (THEME_CHIP_SIZE - size) / 2;
            if (selected || hp > 0.02f) {
                drawRoundedRect(chipX - 2, chipY - 2, THEME_CHIP_SIZE + 4, THEME_CHIP_SIZE + 4, 9,
                        selected ? fade(accent(), 0.9f) : fade(0x66A9BCD6, hp));
            }
            drawRoundedRect(ox, oy, size, size, 6,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            drawRoundedRect(ox + size * 0.28, oy + size * 0.28, size * 0.44, size * 0.44, size * 0.22,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT));
            if (selected) {
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                        ox + size / 2.0, oy + size / 2.0,
                        contrastOn(0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT)));
            }
        }
    }

    private int themeChipIndexAt(int mouseX, int mouseY, int rowX, int rowY, int rowW) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        int cols = themeGridColumns(rowW);
        int x = rowX + 14;
        int y = rowY + 27;
        for (int i = 0; i < presets.length; i++) {
            int chipX = x + (i % cols) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / cols) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            if (isInside(mouseX, mouseY, chipX - 2, chipY - 2, THEME_CHIP_SIZE + 4, THEME_CHIP_SIZE + 4)) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 取色器（Pro 的 HSV 逻辑，folia 外壳） ====================

    private int pickerX(int panelX, int panelW) {
        return panelX + (panelW - PICKER_W) / 2;
    }

    private int pickerY(int panelY, int panelH) {
        return panelY + (panelH - PICKER_H) / 2;
    }

    private void drawColorPicker(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                                 int mouseX, int mouseY) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        float t = named("picker.in", true);
        float outerAlpha = layerAlpha;
        try {
            layerAlpha = outerAlpha * t;
            drawPanelShadow(x, y, PICKER_W, PICKER_H);
            drawRoundedRect(x, y, PICKER_W, PICKER_H, 16, PANEL_BG);
            drawRoundedOutline(x, y, PICKER_W, PICKER_H, 16, 1, PANEL_EDGE);
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE, x + 20, y + 17, 0xFFB6C4D8);
            drawText(FontManager.pf12bold,
                    editingColor == HudSettingsModel.ColorTarget.CURRENT ? "当前歌词颜色" : "普通歌词颜色",
                    x + 29, y + 13, textPrimary());
            int[] close = pickerCloseButton(x, y);
            float cp = named("picker.close",
                    isInside(mouseX, mouseY, close[0], close[1], close[2], close[3]));
            drawRoundedRect(close[0], close[1], close[2], close[3], close[3] * 0.5,
                    mix(0x0DFFFFFF, GHOST_HOVER, cp));
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CLOSE,
                    close[0] + close[2] / 2.0, close[1] + close[3] / 2.0, textSecondary());

            int svX = x + 12;
            int svY = y + 34;
            drawSaturationBrightnessArea(svX, svY);
            int hueY = svY + SV_H + 12;
            drawHueSlider(svX, hueY);

            Color preview = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
            drawRoundedRect(x + 12, hueY + 24, 28, HEX_INPUT_H, 7, preview.getRGB() | 0xFF000000);

            ensureHexColorInput();
            int inputX = x + 48;
            int inputY = hueY + 24;
            hexColorInput.xPosition = inputX + 6;
            hexColorInput.yPosition = inputY + 4;
            hexColorInput.width = HEX_INPUT_W - 12;
            hexColorInput.height = HEX_INPUT_H - 8;
            drawRoundedRect(inputX, inputY, HEX_INPUT_W, HEX_INPUT_H, 7,
                    hexColorInput.isFocused() ? 0x33FFFFFF : 0x1FFFFFFF);
            drawText(fr, hexColorInput.getText(), inputX + 7, inputY + 5, textPrimary());
            if (hexColorInput.isFocused()) {
                int caretX = inputX + 8 + fr.getStringWidth(hexColorInput.getText());
                drawRoundedRect(caretX, inputY + 4, 1, HEX_INPUT_H - 8, 1, 0xFFEAF3FF);
            }
            drawText(fr, "输入 HEX", inputX + HEX_INPUT_W + 9, inputY + 5, fade(textSecondary(), 0.5f));
        } finally {
            layerAlpha = outerAlpha;
        }
    }

    private int[] pickerCloseButton(int pickerX, int pickerY) {
        return new int[] { pickerX + PICKER_W - 30, pickerY + 6, 20, 20 };
    }

    /** SV 区：folia 的圆角卡用四角渐变一次画成（左上白 → 右上纯色，底下两角透明压黑）。 */
    private void drawSaturationBrightnessArea(int x, int y) {
        int hueRgb = Color.HSBtoRGB(pickerHue, 1.0f, 1.0f) | 0xFF000000;
        SkijaShapes.roundedRectCornerGradient(x, y, SV_W, SV_H, 10,
                new Color(0x000000, true), new Color(0xFFFFFFFF, true),
                new Color(0x000000, true), new Color(hueRgb, false),
                layerAlpha);
        drawPickerMarker(x + Math.round(pickerSaturation * (SV_W - 1)),
                y + Math.round((1.0f - pickerBrightness) * (SV_H - 1)));
    }

    private void drawHueSlider(int x, int y) {
        float[] stops = { 1f, 0f, 0f, 1f };
        for (int seg = 0; seg < 6; seg++) {
            int from = Color.HSBtoRGB(seg / 6f, 1f, 1f) | 0xFF000000;
            int to = Color.HSBtoRGB((seg + 1) / 6f, 1f, 1f) | 0xFF000000;
            SkijaShapes.gradientRect(x + seg * (SV_W / 6f), y,
                    x + (seg + 1) * (SV_W / 6f), y + HUE_H, from, to, true);
        }
        int markerX = x + Math.round(pickerHue * (SV_W - 1));
        Gui.drawRect(markerX - 1, y - 2, markerX + 2, y + HUE_H + 2, 0xFF000000);
        Gui.drawRect(markerX, y - 1, markerX + 1, y + HUE_H + 1, 0xFFFFFFFF);
    }

    private static void drawPickerMarker(int x, int y) {
        Gui.drawRect(x - 3, y - 3, x + 4, y - 2, 0xFF000000);
        Gui.drawRect(x - 3, y + 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 3, y - 3, x - 2, y + 4, 0xFF000000);
        Gui.drawRect(x + 3, y - 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y + 2, x + 3, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y - 2, x - 1, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x + 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
    }

    // ==================== 鼠标 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = WindowAdaptiveScale.toLayoutX(mouseX);
            mouseY = WindowAdaptiveScale.toLayoutY(mouseY);
        }
        try {
            mouseClickedImpl(mouseX, mouseY, button);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void mouseClickedImpl(int mouseX, int mouseY, int button) throws IOException {
        if (closing) return;
        pressOutsidePanel = false;

        int[] panel = panelBounds();
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];
        boolean inside = isInside(mouseX, mouseY, panelX, panelY, panelW, panelH);
        if (!inside) {
            // 遮罩上的按下：是否关闭由 mouseReleased 判定（按下与松开都要落在遮罩上，复刻 folia）。
            pressOutsidePanel = true;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            openChoice = null;
            if (hexColorInput != null) hexColorInput.setFocused(false);
            return;
        }
        if (button != 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        if (handleChoiceMenuClick(mouseX, mouseY)) return;

        if (editingColor != HudSettingsModel.ColorTarget.NONE
                && handlePickerClick(mouseX, mouseY, button, panelX, panelY, panelW, panelH)) {
            return;
        }

        int[] close = closeButton(panelX, panelY, panelW);
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            requestClose();
            return;
        }
        int[] classic = classicButton(panelX, panelY, panelW);
        if (isInside(mouseX, mouseY, classic[0], classic[1], classic[2], classic[3])) {
            switchStyle(HudConfig.HUD_EDITOR_STYLE_CLASSIC, new GuiHudEditor());
            return;
        }
        int[] modern = modernButton(panelX, panelY, panelW);
        if (isInside(mouseX, mouseY, modern[0], modern[1], modern[2], modern[3])) {
            switchStyle(HudConfig.HUD_EDITOR_STYLE_MODERN, new GuiHudEditorPro());
            return;
        }
        if (OneConfigHudBridge.isAvailable()) {
            int[] oneConfig = oneConfigButton(panelX, panelY, panelW);
            if (isInside(mouseX, mouseY, oneConfig[0], oneConfig[1], oneConfig[2], oneConfig[3])) {
                HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_ONECONFIG;
                HudConfig.save();
                if (OneConfigHudBridge.open()) mc.setScreen(null);
                return;
            }
        }

        int sideW = sidebarWidth(panelW);
        int sideX = panelX + PAD;
        int bodyTop = panelY + PAD + HEADER_BLOCK_H;
        int bodyH = panelH - PAD * 2 - HEADER_BLOCK_H;
        buildNav(sideX, bodyTop, sideW, bodyH);
        for (NavItem item : navItems) {
            if (!isInside(mouseX, mouseY, item.x, item.y, item.w, item.h)) continue;
            if (item.kind == 0) {
                categoryIndex = item.index;
                editingColor = HudSettingsModel.ColorTarget.NONE;
                openChoice = null;
            } else {
                scrollToAnchor(item.index, bodyTop, bodyH);
            }
            return;
        }

        int contentX = sideX + sideW + SIDEBAR_GAP;
        int contentW = panelX + panelW - PAD - SIDEBAR_GAP / 2 - contentX;
        if (!isInside(mouseX, mouseY, contentX, bodyTop, contentW, bodyH)) return;
        layoutRows(contentX, bodyTop, contentW, HudSettingsModel.categories()[clampCategory()], 0f);
        for (Row row : rows) {
            if (row.isGroupHeader()) continue;
            if (!isInside(mouseX, mouseY, row.x, row.y, row.w, row.h)) continue;
            activateRow(row, mouseX, mouseY);
            return;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = WindowAdaptiveScale.toLayoutX(mouseX);
            mouseY = WindowAdaptiveScale.toLayoutY(mouseY);
        }
        try {
            if (state == 0) {
                if (pressOutsidePanel && !closing) {
                    int[] panel = panelBounds();
                    if (!isInside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3])) {
                        requestClose();
                    }
                }
                pressOutsidePanel = false;
                if (draggingSaturationBrightness || draggingHue || draggingSlider != null || settingsDirty) {
                    HudConfig.save();
                    settingsDirty = false;
                }
            }
            draggingSaturationBrightness = false;
            draggingHue = false;
            draggingSlider = null;
            super.mouseReleased(mouseX, mouseY, state);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void activateRow(Row row, int mouseX, int mouseY) {
        switch (row.entry.kind) {
            case SLIDER: {
                int rowRight = row.x + row.w - 14;
                int trackX = sliderTrackX(rowRight);
                if (mouseX < trackX - 8 || mouseX > trackX + TRACK_W + 8) break;
                draggingSlider = row.entry.setting;
                draggingSliderTrackX = trackX;
                setSliderFromMouse(row.entry.setting, mouseX, trackX);
                break;
            }
            case TOGGLE:
            case ACTION:
                row.entry.activate();
                break;
            case CHOICE: {
                String[] options = row.entry.getChoices();
                if (options.length > 1) {
                    openChoiceMenu(row.entry, row);
                } else {
                    row.entry.activate();
                }
                break;
            }
            case COLOR:
                openChoice = null;
                beginColorEdit(row.entry);
                break;
            case THEME_GRID: {
                int chip = themeChipIndexAt(mouseX, mouseY, row.x, row.y, row.w);
                if (chip >= 0) {
                    NCMTheme.ThemePreset preset = NCMTheme.ThemePreset.values()[chip];
                    DownloadDynamicIsland.showTheme(NCMTheme.setCurrent(preset) == null
                            ? NCMTheme.getCurrentName() : preset.getDisplayName());
                }
                break;
            }
            default:
                break;
        }
    }

    private void scrollToAnchor(int anchor, int bodyTop, int bodyH) {
        if (anchor < 0 || anchor >= anchorTops.length) return;
        int maxScroll = Math.max(0, contentHeight - bodyH);
        // 锚点坐标是相对「内容头之下」的列表起点排的，滚动目标要把它换算回整列。
        int target = Math.max(0, Math.min(maxScroll,
                anchorTops[anchor] + CONTENT_HEAD_H - ANCHOR_TOP_PADDING));
        setScroll(target, maxScroll);
        activeAnchor = anchor;
        clickLockUntil = System.currentTimeMillis() + CLICK_LOCK_MS;
    }

    private void updateSliderWhileDragging(int mouseX) {
        if (draggingSlider != null && Mouse.isButtonDown(0)) {
            setSliderFromMouse(draggingSlider, mouseX, draggingSliderTrackX);
        }
    }

    private void setSliderFromMouse(HudSetting setting, int mouseX, int trackX) {
        float progress = clamp01((mouseX - trackX) / (float) TRACK_W);
        setting.setValue(setting.getMin() + (setting.getMax() - setting.getMin()) * progress);
        settingsDirty = true;
    }

    private boolean handlePickerClick(int mouseX, int mouseY, int button,
                                      int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int[] close = pickerCloseButton(x, y);
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (hexColorInput != null) hexColorInput.setFocused(false);
            return true;
        }
        int svX = x + 12;
        int svY = y + 34;
        int hueY = svY + SV_H + 12;
        int inputX = x + 48;
        int inputY = hueY + 24;
        ensureHexColorInput();
        if (isInside(mouseX, mouseY, inputX, inputY, HEX_INPUT_W, HEX_INPUT_H)) {
            hexColorInput.setFocused(true);
            hexColorInput.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        hexColorInput.setFocused(false);
        if (isInside(mouseX, mouseY, svX, svY, SV_W, SV_H)) {
            draggingSaturationBrightness = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        if (isInside(mouseX, mouseY, svX, hueY, SV_W, HUE_H)) {
            draggingHue = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        return false;
    }

    private void updatePickerWhileDragging(int mouseX, int mouseY,
                                           int panelX, int panelY, int panelW, int panelH) {
        if (!Mouse.isButtonDown(0)) return;
        if (draggingSaturationBrightness || draggingHue) {
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
        }
    }

    private void applyPickerAt(int mouseX, int mouseY, int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int svX = x + 12;
        int svY = y + 34;
        int hueY = svY + SV_H + 12;
        if (draggingSaturationBrightness) {
            pickerSaturation = clamp01((mouseX - svX) / (float) (SV_W - 1));
            pickerBrightness = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
        }
        if (draggingHue) {
            pickerHue = clamp01((mouseX - svX) / (float) (SV_W - 1));
        }
        Color selected = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        applySelectedColor(selected);
        if (hexColorInput != null && !hexColorInput.isFocused()) {
            hexColorInput.setText(toHex(selected));
        }
    }

    private void beginColorEdit(HudSettingsModel.Entry entry) {
        editingColor = entry.colorTarget;
        Color color = entry.getColor();
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        ensureHexColorInput();
        hexColorInput.setText(toHex(color));
        hexColorInput.setFocused(false);
    }

    private void applySelectedColor(Color selected) {
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.COLOR && entry.colorTarget == editingColor) {
                        entry.setColor(selected);
                        settingsDirty = true;
                        return;
                    }
                }
            }
        }
    }

    // ==================== 键盘 ====================

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (closing) return;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (openChoice != null) {
                openChoice = null;
                return;
            }
            if (editingColor != HudSettingsModel.ColorTarget.NONE) {
                if (hexColorInput != null && hexColorInput.isFocused()) {
                    hexColorInput.setFocused(false);
                    return;
                }
                editingColor = HudSettingsModel.ColorTarget.NONE;
                return;
            }
            requestClose();
            return;
        }
        if (editingColor != HudSettingsModel.ColorTarget.NONE && hexColorInput != null
                && hexColorInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                hexColorInput.setFocused(false);
                if (settingsDirty) {
                    HudConfig.save();
                    settingsDirty = false;
                }
                return;
            }
            if (hexColorInput.textboxKeyTyped(typedChar, keyCode)) {
                applyHexColorInput();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void applyHexColorInput() {
        if (hexColorInput == null) return;
        Color selected = parseHexColor(hexColorInput.getText());
        if (selected == null) return;
        float[] hsb = Color.RGBtoHSB(selected.getRed(), selected.getGreen(), selected.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        applySelectedColor(selected);
    }

    private static Color parseHexColor(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (!normalized.matches("[0-9a-fA-F]{6}")) return null;
        try {
            return new Color(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== 关闭与样式切换 ====================

    private void requestClose() {
        if (closing) return;
        closing = true;
        closeStart = System.currentTimeMillis();
        openChoice = null;
        editingColor = HudSettingsModel.ColorTarget.NONE;
    }

    /** 换样式后立即换界面：记住选择，下次按 H 直接进这一套。 */
    private void switchStyle(String style, GuiScreen next) {
        HudConfig.hudEditorStyle = style;
        HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;
        HudConfig.save();
        mc.setScreen(next);
    }

    // ==================== 杂项 ====================

    private int clampCategory() {
        int count = HudSettingsModel.categories().length;
        return Math.max(0, Math.min(count - 1, categoryIndex));
    }

    private int scroll() {
        if (scrollByCategory == null) return 0;
        return scrollByCategory[clampCategory()];
    }

    private void setScroll(int value, int maxScroll) {
        int clamped = Math.max(0, Math.min(maxScroll, value));
        if (scrollByCategory != null) scrollByCategory[clampCategory()] = clamped;
    }

    private void ensureHexColorInput() {
        if (hexColorInput == null) {
            hexColorInput = new GuiTextField(0, fontRendererObj, 0, 0, HEX_INPUT_W - 12, HEX_INPUT_H - 8);
            hexColorInput.setEnableBackgroundDrawing(false);
            hexColorInput.setMaxStringLength(7);
        }
    }

    private boolean hasLiveInfoPreview() {
        return CloudMusic.currentlyPlaying != null && CloudMusic.player != null
                && !CloudMusic.player.isFinished();
    }

    private boolean hasLiveLyricsPreview() {
        return CloudMusic.player != null && !CloudMusic.player.isFinished()
                && !CloudMusic.lyrics.isEmpty();
    }

    private void renderLive(MusicInfoWidget info) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            info.widget.render();
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void renderLiveLyrics(MusicLyricsWidget lyrics, int x, int y) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            lyrics.renderEditorPreview(x, y, HudConfig.lyricScale);
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    // ==================== 动画工具 ====================

    /** 时间基准的补间（folia 的外壳动画是定长 easeOut，不用帧率相关的指数插值去做它们）。 */
    private static float tween(long startMs, int durationMs) {
        float t = clamp01((System.currentTimeMillis() - startMs) / (float) Math.max(1, durationMs));
        float k = 1f - t;
        return 1f - k * k * k;
    }

    /** 命名部件的悬停进度（150ms 档的观感由指数插值提供，与 Pro 一致）。 */
    private float named(String key, boolean active) {
        Float stored = namedProgress.get(key);
        float now = stored == null ? (active ? 1f : 0f) : stored.floatValue();
        now = Interpolations.interpolate(now, active ? 1f : 0f, 22f);
        namedProgress.put(key, Float.valueOf(now));
        return now;
    }

    private float[] progressOf(HudSettingsModel.Entry entry, boolean on) {
        float[] state = rowProgress.get(entry);
        if (state == null) {
            state = new float[] { 0f, on ? 1f : 0f, 0f };
            rowProgress.put(entry, state);
        }
        return state;
    }

    private static int fade(int color, float alpha) {
        int base = (color >>> 24) & 0xFF;
        int a = Math.round(base * clamp01(alpha));
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    private static int mix(int from, int to, float t) {
        float k = clamp01(t);
        int a = channel(from, 24) + Math.round((channel(to, 24) - channel(from, 24)) * k);
        int r = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * k);
        int g = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * k);
        int b = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int color, int shift) {
        return (color >>> shift) & 0xFF;
    }

    private static int contrastOn(int background) {
        float luma = (channel(background, 16) * 0.299f
                + channel(background, 8) * 0.587f
                + channel(background, 0) * 0.114f) / 255f;
        return luma > 0.58f ? 0xFF101720 : 0xFFF4F7FB;
    }

    // ==================== 绘制原语 ====================

    private void drawIcon(CFontRenderer font, String glyph, double centerX, double centerY, int color) {
        if (font == null || glyph == null || glyph.isEmpty()) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        GlyphInkMetrics square = emSquare(font);
        double x;
        double y;
        if (square != null) {
            x = square.alignCenterX(centerX);
            y = square.alignCenterY(centerY);
        } else {
            x = centerX - font.getStringWidthD(glyph) * .5;
            y = centerY - font.getFontHeight() * .5;
        }
        font.drawString(glyph, x, y, tinted);
    }

    private static GlyphInkMetrics emSquare(CFontRenderer font) {
        synchronized (GRID_CACHE) {
            if (GRID_CACHE.containsKey(font)) {
                return GRID_CACHE.get(font);
            }
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, HudSettingsIcons.LOCATOR.charAt(0));
        synchronized (GRID_CACHE) {
            GRID_CACHE.put(font, measured);
        }
        return measured;
    }

    private void drawRoundedRect(double x, double y, double w, double h, double radius, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;
        if (alpha <= 0.002f) return;
        SkijaShapes.roundedRect(x, y, w, h, Math.min(radius, Math.min(w, h) * 0.5),
                SkijaGfx.argb(((color >>> 16) & 0xFF) / 255.0F, ((color >>> 8) & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F, alpha));
        SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
    }

    /** 1px 描边。folia 的白/5、白/10 边框全靠它，不然不透明面板在世界上会糊成一整块。 */
    private void drawRoundedOutline(double x, double y, double w, double h,
                                    double radius, double thickness, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;
        if (alpha <= 0.002f) return;
        int argb = SkijaGfx.argb(((color >>> 16) & 0xFF) / 255.0F, ((color >>> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, alpha);
        SkijaShapes.roundedOutline(x, y, w, h, Math.min(radius, Math.min(w, h) * 0.5), thickness, argb);
    }

    private void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        font.drawString(text, x, y, tinted);
    }

    private void drawCenteredText(CFontRenderer font, String text, double centerX, double y, int color) {
        if (font == null || text == null) return;
        drawText(font, text, centerX - font.getStringWidthD(text) * .5, y, color);
    }

    private int scissorDepth;

    private void beginScissor(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            SkijaClip.scissorBegin(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight());
        } else {
            SkijaClip.scissorBegin(x, y, w, h);
        }
        scissorDepth++;
    }

    private void endScissor() {
        if (scissorDepth > 0) {
            scissorDepth--;
            SkijaClip.scissorEnd();
        }
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
