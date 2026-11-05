package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.mc.Gui;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.mc.GuiTextField;
import com.muoniumplayer.compat.gl.GL11;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * MuoniumConfig —— HUD 设置界面的现代化呈现（私有品牌）。
 *
 * <p>视觉语言取自 Polyfrost OneConfig：深灰梯度面板 + 胶囊侧栏 + 32px 紧凑行 + 双列开关 +
 * 悬停 tooltip + 开场缩放动画；编辑 HUD 是独立全屏舞台（白实线框、8×8 把手、紫色吸附线），
 * 点侧栏「编辑 HUD」进出。所有绘制走本项目自己的 Skija 栈（{@link SkijaShapes} /
 * {@link GlassLook} / {@link CFontRenderer}），不引任何外部 UI 代码。</p>
 *
 * <p>与旧版 {@link GuiHudEditorPro} 的分工：内容同源（都读 {@link HudSettingsModel}）、
 * 舞台同源（共用 {@link HudOverlayStage}，它只算不画），呈现互不影响。旧版保持不动，
 * 这一版先作为 {@code hudEditorUi = "mconfig"} 档位可选；跑稳之后再谈默认。</p>
 *
 * <p>工程约束与旧版同款三条，动这个类之前先读完：</p>
 * <ol>
 *   <li><b>渲染与命中共用同一份行矩形</b>（{@link #layoutRows} 产物 {@link #rows}）。</li>
 *   <li><b>面板矩形只从 {@link #panelBounds()} 拿</b>——它是固定居中的，但仍要过入场缩放
 *   与窗口自适应两条轴，不许散点重算。</li>
 *   <li><b>动画只改视觉不改命中矩形</b>：入场缩放走 {@code SkijaGfx.scale} 包住整块面板，
 *   缩放未完成时输入整体闸掉（命中矩形是未缩放的，缩放中点击必然错位，不如不接）；
 *   内容滑入走 {@code SkijaGfx.translate} + 鼠标反向补偿。</li>
 * </ol>
 */
public class MConfigHudScreen extends GuiScreen {

    // ==================== 面板尺寸（布局轴） ====================

    private static final int HEADER_H = 54;
    /** 矮窗口下页眉压到这一档：面包屑那行可以省，标题与搜索框必须留着。 */
    private static final int HEADER_H_COMPACT = 40;
    private static final int NAV_W = 168;
    private static final int NAV_ROW_H = 34;
    /** 侧栏行高的压缩下限：再矮就连图标都画不完整，宁可滚动。 */
    private static final int NAV_ROW_MIN_H = 26;
    private static final int NAV_BOTTOM_H = 68;
    private static final int FOOTER_H = 32;

    private static final int CONTENT_PAD = 16;
    private static final int GROUP_HEADER_H = 26;
    /**
     * 卡片折叠态高度：标题 + 说明两行，右侧留给主控件与展开箭头。
     * 说明在双列半宽里几乎必然折行，留一行会变成「水面之下 / 耳朵没进水」这种半句话；
     * 两行说明 + 这块高度是参考图那种「标题+副标题」卡片的实际读法。
     */
    private static final int CARD_H = 68;
    /** 卡片展开后追加的详情区高度（滑条 / 完整说明）。 */
    private static final int DETAIL_H = 46;
    /** 同一列里上下两张卡之间的缝。 */
    private static final int CARD_GAP_V = 10;
    private static final int CARD_GAP = 12;
    private static final int CARD_RADIUS = 16;
    private static final int EMPTY_STATE_H = 78;
    private static final int PANEL_RADIUS = 18;
    /** 双列格子之间的缝。 */
    private static final int CELL_GAP = 12;
    /** 卡片右侧展开箭头的点击区宽度。 */
    private static final int ARROW_W = 26;

    private static final int TRACK_W = 170;
    private static final int VALUE_W = 44;
    private static final int TRACK_H = 3;
    private static final int FILL_H = 5;
    private static final int KNOB_R = 9;
    private static final int SWITCH_W = 34;
    private static final int SWITCH_H = 20;
    private static final int SWATCH_W = 44;
    private static final int SWATCH_H = 24;

    private static final int THEME_CHIP_SIZE = 22;
    private static final int THEME_CHIP_GAP = 5;

    private static final int PICKER_W = 216;
    private static final int PICKER_H = 236;
    private static final int SV_W = 150;
    private static final int SV_H = 110;
    private static final int HUE_W = 16;
    private static final int HEX_INPUT_W = 64;
    private static final int HEX_INPUT_H = 22;

    private static final int DROP_ITEM_H = 32;
    private static final int DROP_ITEM_COMPACT_H = 28;
    private static final int DROP_MAX_VISIBLE = 7;
    private static final int DROP_MIN_W = 168;
    private static final int DROP_MAX_W = 260;

    private static final int SEARCH_W = 150;
    private static final int SEARCH_H = 24;

    private static final int INFO_BASE_W = 230;
    private static final int INFO_BASE_H = 56;
    private static final int WIDGET_POPOVER_W = 200;

    /** 下拉浮层进场时长。 */
    private static final double DROP_MS = 150.0;
    /** 搜索跳转后的高亮脉冲。 */
    private static final long FLASH_MS = 900L;
    /** 描述 tooltip：悬停多久后出现、淡入多久。 */
    private static final long TOOLTIP_DELAY_MS = 350L;
    private static final double TOOLTIP_FADE_MS = 150.0;

    // ==================== 动效时长（一律 durationMs / SheetClock.speed()） ====================

    /** OneConfig 的颜色过渡是 100ms：跟手、不黏。 */
    private static final double HOVER_MS = 100.0;
    /** 开关旋钮一次行程（带 SPRING_LAND 回弹）。 */
    private static final double KNOB_MS = 200.0;
    private static final double FOCUS_MS = 160.0;
    /** 侧栏胶囊指示条换位（OneConfig 是 EaseOutExpo 300ms）。 */
    private static final double INDICATOR_MS = 300.0;
    private static final double POP_MS = 170.0;
    /** 卡片展开 / 收起：详情区高度与箭头旋转共用这一档。 */
    private static final double EXPAND_MS = 240.0;
    /** 配置页 ↔ 编辑舞台的双态切换。 */
    private static final double EDIT_MS = 220.0;

    /** 内容逐块落位窗口（沿用旧版的卡位比例，测试与手感都已验证过）。 */
    private static final double ROW_FIRST = .26;
    private static final double ROW_STEP = .04;
    private static final double ROW_TRAVEL = .54;
    private static final int ROW_STAGGER_LIMIT = 12;
    private static final double NAV_FIRST = .12;
    private static final double NAV_STEP = .035;
    private static final double NAV_TRAVEL = .40;
    private static final int NAV_STAGGER_LIMIT = 8;
    private static final double HEADER_FIRST = .04;
    private static final double HEADER_TRAVEL = .40;
    private static final double FOOTER_FIRST = .52;
    private static final double PLATE_TRAVEL = .34;
    /** 开场缩放窗口：底板淡入的同时 scale 0.9→1（EXPO），播完才放行输入。 */
    private static final double PLATE_SCALE_END = .5;

    /** 切分类时内容滑入幅度。 */
    private static final int CONTENT_SLIDE = 22;
    private static final int CONTENT_DROP = 6;
    private static final long DOUBLE_CLICK_MS = 320L;

    // ==================== 一行 / 一格 ====================

    /** 双列里的一格。 */
    private static final class Cell {
        final HudSettingsModel.Entry entry;
        final int x;
        final int w;

        Cell(HudSettingsModel.Entry entry, int x, int w) {
            this.entry = entry;
            this.x = x;
            this.w = w;
        }
    }

    /**
     * 一条可见行：组头 / 空态 / 单列条目 / 双列条目对。渲染与命中都读它。
     * 双列行 {@code left}/{@code right} 都非 null；单列行只有 {@code left}。
     */
    private static final class MRow {
        final Cell left;
        final Cell right;
        final String title;
        final String icon;
        final int accent;
        final int x;
        final int y;
        final int w;
        final int h;
        final boolean firstInCard;
        final boolean lastInCard;
        final boolean emptyState;
        final int slot;

        MRow(Cell left, Cell right, String title, String icon, int accent,
             int x, int y, int w, int h, boolean firstInCard, boolean lastInCard,
             boolean emptyState, int slot) {
            this.left = left;
            this.right = right;
            this.title = title;
            this.icon = icon;
            this.accent = accent;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.firstInCard = firstInCard;
            this.lastInCard = lastInCard;
            this.emptyState = emptyState;
            this.slot = slot;
        }

        boolean isGroupHeader() {
            return left == null && !emptyState;
        }
    }

    private final List<MRow> rows = new ArrayList<MRow>();
    private int contentHeight;

    // ==================== 状态 ====================

    private int categoryIndex;
    private int[] scrollByCategory;
    private int searchScroll;
    private float navIndicator = -1f;

    private GuiTextField searchInput;
    private String searchQuery = "";

    private HudSettingsModel.Entry openChoice;
    private float choiceGate;
    private HudSettingsModel.Entry flashEntry;
    private long flashDeadline;
    /** MD3 卡片展开态：同一时刻只留一张，点箭头或卡片主体切换。 */
    private HudSettingsModel.Entry expandedEntry;

    private HudSetting draggingSlider;
    private int draggingSliderTrackX;
    private int draggingTrackW;
    private HudSetting editingSliderValue;
    private GuiTextField sliderValueInput;
    private HudSettingsModel.ColorTarget editingColor = HudSettingsModel.ColorTarget.NONE;
    private float pickerHue;
    private float pickerSaturation;
    private float pickerBrightness;
    private boolean draggingSaturationBrightness;
    private boolean draggingHue;
    private GuiTextField hexColorInput;
    private boolean settingsDirty;
    private boolean resetConfirmationVisible;

    private final HudOverlayStage stage = new HudOverlayStage();
    private float popGate;
    private boolean draggingPopoverScale;
    private int popoverScaleTrackX;
    private HudOverlayStage.Kind pulsedSelection = HudOverlayStage.Kind.NONE;
    private final Channel selectionPulseChannel = new Channel();

    /** 配置页 ↔ 编辑舞台的双态。进出都走 {@link #editGate} 补间，过渡中输入整体闸掉。 */
    private boolean editing;
    private float editGate;

    // ==================== 动画状态 ====================

    private float layerAlpha = 1f;
    private final SheetClock page = SheetClock.cascade();
    private final SheetClock list = SheetClock.cascade();
    private float plate = 1f;
    private double pageFade;
    private double listFade;
    private float dialogGate;
    private float pickerGate;
    private boolean introStarted;
    private int listDirection;
    private int contentOwner = Integer.MIN_VALUE;

    private final Map<String, Channel> channels = new HashMap<String, Channel>();
    private final Map<HudSettingsModel.Entry, float[]> rowProgress =
            new IdentityHashMap<HudSettingsModel.Entry, float[]>();
    private final Map<HudSettingsModel.Entry, Channel[]> rowChannels =
            new IdentityHashMap<HudSettingsModel.Entry, Channel[]>();
    /** 卡片展开度通道：按条目身份存，展开/收起都从当前值续着走（首帧也吃动画）。 */
    private final Map<HudSettingsModel.Entry, Channel> expandChannels =
            new IdentityHashMap<HudSettingsModel.Entry, Channel>();

    private float displayScroll;
    private int scrollOwner = Integer.MIN_VALUE;
    private int navScroll;

    /** tooltip 的悬停追踪：键变化即重置计时。 */
    private String tooltipKey;
    private long tooltipSince;
    private float tooltipAlpha;

    private static final Map<CFontRenderer, GlyphInkMetrics> GRID_CACHE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    // ==================== 生命周期 ====================

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (scrollByCategory == null || scrollByCategory.length != HudSettingsModel.categories().length) {
            scrollByCategory = new int[HudSettingsModel.categories().length];
        }
        categoryIndex = Math.max(0, Math.min(HudSettingsModel.categories().length - 1,
                HudConfig.hudEditorLastCategory));
        ensureSearchInput();
        ensureHexColorInput();
        if (!introStarted) {
            introStarted = true;
            page.restart();
            list.restart();
        }
    }

    @Override
    public void onGuiClosed() {
        if (settingsDirty) {
            HudConfig.save();
            settingsDirty = false;
        }
        if (HudConfig.hudEditorLastCategory != clampCategory()) {
            HudConfig.hudEditorLastCategory = clampCategory();
            HudConfig.save();
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
        // 圆角矩形着色器会关 alpha test；整段绘制包一次状态边界，别把状态泄漏给 Forge 覆盖层。
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        boolean adaptive = false;
        try {
            RenderSystem.refreshResolution();
            adaptive = beginAdaptiveLayout();
            if (adaptive) {
                mouseX = WindowAdaptiveScale.toLayoutXForced(mouseX);
                mouseY = WindowAdaptiveScale.toLayoutYForced(mouseY);
                SharedConstants.api.getGLStateManager().pushMatrix();
                SharedConstants.api.getGLStateManager().scale(
                        WindowAdaptiveScale.correctionX(), WindowAdaptiveScale.correctionY(), 1);
            }
            CFontRenderer fr = FontManager.pf12;
            if (fr == null) return;

            pageFade = page.tick();
            listFade = list.tick();
            plate = (float) TransitionCurveCache.EXPO.at(
                    SheetClock.window(pageFade, 0.0, PLATE_TRAVEL));
            float plateScale = (float) TransitionCurveCache.EXPO.at(
                    SheetClock.window(pageFade, 0.0, PLATE_SCALE_END));
            dialogGate = motion("dialog.in", resetConfirmationVisible ? 1f : 0f, POP_MS);
            pickerGate = motion("picker.in",
                    editingColor != HudSettingsModel.ColorTarget.NONE ? 1f : 0f, POP_MS);
            choiceGate = motion("choice.pop", openChoice != null ? 1f : 0f, DROP_MS);
            popGate = motion("wpop.in", stage.hasSelection() && editing ? 1f : 0f, POP_MS);
            editGate = motion("edit.gate", editing ? 1f : 0f, EDIT_MS);
            if (page.isGone()) {
                closeScreen();
                return;
            }
            boolean retiring = page.isDismissing();
            // 双态切换途中（以及入场缩放未完成时）输入整体不接：命中矩形是未变换的。
            boolean modeBusy = retiring || resetConfirmationVisible
                    || plateScale < 0.999f
                    || (editing ? editGate < 0.98f : editGate > 0.02f);

            if (!DownloadDynamicIsland.isDownloadActive()) DownloadDynamicIsland.renderEditorPreview();

            MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
            MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;

            int wheel = Mouse.getDWheel();
            if (modeBusy) wheel = 0;

            // ==== 两块 HUD 的实时预览与舞台（始终在跑，配置态下被面板挡住输入而已） ====
            int hudSpaceWidth = (int) RenderSystem.getLayoutWidth();
            int hudSpaceHeight = (int) RenderSystem.getLayoutHeight();

            int infoW = (int) (INFO_BASE_W * HudConfig.infoScale);
            int infoH = (int) (INFO_BASE_H * HudConfig.infoScale);
            int infoX = (int) (HudConfig.infoX * (hudSpaceWidth - infoW));
            int infoY = (int) (HudConfig.infoY * (hudSpaceHeight - infoH));
            int lyricW = (int) (HudConfig.lyricWidth * HudConfig.lyricScale);
            int lyricH = (int) (HudConfig.lyricHeight * HudConfig.lyricScale);
            int lyricX = (int) (HudConfig.lyricX * (hudSpaceWidth - lyricW));
            int lyricY = (int) (HudConfig.lyricY * (hudSpaceHeight - lyricH));
            int[] infoRect = { infoX, infoY, infoW, infoH };
            int[] lyricRect = { lyricX, lyricY, lyricW, lyricH };

            int[] panel = panelBounds();
            boolean overPanel = isInside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3]);
            boolean overPicker = editingColor != HudSettingsModel.ColorTarget.NONE
                    && isInside(mouseX, mouseY, pickerX(panel), pickerY(panel), PICKER_W, PICKER_H);
            boolean overPopover = stage.hasSelection() && editing
                    && isInside(mouseX, mouseY, widgetPopoverX(), widgetPopoverY(),
                            WIDGET_POPOVER_W, widgetPopoverH());
            boolean uiBusy = modeBusy || overPanel || overPicker || overPopover;
            boolean lmb = !modeBusy && Mouse.isButtonDown(0);

            if (!modeBusy) {
                updateSliderWhileDragging(mouseX);
                updateColorPickerWhileDragging(mouseX, mouseY, panel);
            }

            boolean stageDirty = stage.update(lmb && !uiBusy, uiBusy ? 0 : wheel, mouseX, mouseY,
                    infoRect, lyricRect, new int[] { INFO_BASE_W, INFO_BASE_H }, hudSpaceWidth, hudSpaceHeight);
            if (stageDirty) HudConfig.save();

            if (hasLiveInfoPreview()) {
                renderLiveInfoPreview(info);
            } else {
                drawInfoPlaceholder(fr, infoRect, Math.max(editGate, 1f - editGate * 0.4f));
            }
            if (hasLiveLyricsPreview()) {
                renderLiveLyricsPreview(lyrics, lyricX, lyricY);
            } else {
                drawLyricsPlaceholder(fr, lyricRect, Math.max(editGate, 1f - editGate * 0.4f));
            }

            // ==== 配置页（编辑态淡出，缩放动画包住整块面板） ====
            float panelAlpha = plate * (1f - editGate);
            if (panelAlpha > 0.01f) {
                float outer = pushAlpha(panelAlpha);
                SkijaGfx.pushMatrix();
                try {
                    applyPlateScale(panel, plateScale);
                    drawPanel(fr, panel, mouseX, mouseY, wheel, overPanel);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = outer;
                }

                // 浮层：取色器在上、下拉其次（与旧版同序）。它们跟着各自 gate 淡入 + 下坠。
                if (editingColor != HudSettingsModel.ColorTarget.NONE) {
                    float pickerOuter = pushAlpha(pickerGate);
                    SkijaGfx.pushMatrix();
                    SkijaGfx.translate(0f, slideY(pickerGate));
                    try {
                        drawColorPicker(fr, panel, mouseX, mouseY);
                    } finally {
                        SkijaGfx.popMatrix();
                        layerAlpha = pickerOuter;
                    }
                }
                if (openChoice != null || choiceGate > 0.01f) {
                    float dropOuter = pushAlpha(choiceGate);
                    SkijaGfx.pushMatrix();
                    SkijaGfx.translate(0f, slideY(choiceGate));
                    try {
                        drawChoiceDropdown(fr, panel, mouseX, mouseY);
                    } finally {
                        SkijaGfx.popMatrix();
                        layerAlpha = dropOuter;
                    }
                }
            }

            // ==== 编辑舞台（OneConfig HudGui 那一态） ====
            if (editGate > 0.01f) {
                float outer = pushAlpha(editGate);
                try {
                    drawStageOverlays(fr, infoRect, lyricRect, mouseX, mouseY);
                } finally {
                    layerAlpha = outer;
                }
            }

            // 吸附参考线：紫色的（OneConfig 语言）。编辑态才画。
            if (editGate > 0.01f) {
                for (int[] guide : stage.guides) {
                    Gui.drawRect(guide[0], guide[1], guide[0] + guide[2], guide[1] + guide[3],
                            fade(MConfigTheme.SNAP_PURPLE, editGate));
                }
            }

            super.drawScreen(mouseX, mouseY, partialTicks);

            if (resetConfirmationVisible && !editing) {
                float outer = pushAlpha(dialogGate);
                SkijaGfx.pushMatrix();
                try {
                    applyPlateScale(panel, plateScale);
                    drawResetConfirmation(fr, mouseX, mouseY);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = outer;
                }
            }
        } finally {
            if (adaptive) {
                SharedConstants.api.getGLStateManager().popMatrix();
            }
            restoreAdaptiveLayout(adaptive);
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    /** 开场缩放：围绕面板中心 scale 0.9→1。只在视觉层，命中矩形不动（输入由 plateScale 闸住）。 */
    private void applyPlateScale(int[] panel, float plateScale) {
        if (plateScale >= 0.999f) return;
        float scale = 0.9f + 0.1f * plateScale;
        double cx = panel[0] + panel[2] / 2.0;
        double cy = panel[1] + panel[3] / 2.0;
        SkijaGfx.translate(cx, cy);
        SkijaGfx.scale(scale, scale);
        SkijaGfx.translate(-cx, -cy);
    }

    // ==================== 窗口自适应 ====================

    private boolean beginAdaptiveLayout() {
        RenderSystem.refreshResolution();
        WindowAdaptiveScale.beginForced();
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

    private int savedAdaptiveWidth;
    private int savedAdaptiveHeight;

    // ==================== 面板几何（固定居中） ====================

    /**
     * 面板矩形：优先吃掉可用空间，但**永远不超出窗口**。
     *
     * <p>旧版是 {@code max(520, min(680, width*0.60))} —— 下限写死 520，窗口比它窄时
     * 面板会直接顶出屏幕右边（手机上尤其明显）。这里改成先从可用宽度里减去左右边距再取，
     * 下限只作为「再窄也不缩到不可用」的兜底，并且被 {@code width - 2*margin} 二次夹紧。</p>
     */
    private int[] panelBounds() {
        int margin = Math.max(6, Math.round(Math.min(width, height) * 0.015f));
        int maxW = Math.max(220, width - margin * 2);
        int maxH = Math.max(220, height - margin * 2);
        // 目标宽度按屏幕比例取，但下限只到「侧栏图标档 + 一张卡片」的可用宽度（约 320），
        // 不再出现「下限 440 > 可用宽度」时面板反而比屏幕宽的情况。
        // 大屏给足上限：1280 宽时面板应接近 900，侧栏才摆得下文字而不是被迫收成图标条。
        int targetW = Math.round(width * 0.74f);
        int panelW = Math.max(Math.min(360, maxW), Math.min(920, targetW));
        int targetH = Math.round(height * 0.86f);
        int panelH = Math.max(Math.min(300, maxH), Math.min(760, targetH));
        panelW = Math.min(panelW, maxW);
        panelH = Math.min(panelH, maxH);
        return new int[] { (width - panelW) / 2, (height - panelH) / 2, panelW, panelH };
    }

    /**
     * 内容区够不够摆两列。窄窗口（手机竖屏、小尺寸 GUI 缩放）下强行双列会把
     * 卡内文字压成两三个字一行，此时退回单列，卡片仍然有展开箭头。
     */
    private boolean twoColumns(int contentW) {
        return contentW - CONTENT_PAD * 2 - 4 >= 460;
    }

    /**
     * 侧栏在窄面板里必须让位，否则内容列会被挤成一条缝（560 宽窗口下内容只剩 200px 出头，
     * 卡片连标题都放不下）。三档：完整文字栏 → 窄文字栏 → 只留图标。
     */
    private int navWidth(int panelW) {
        if (panelW >= 620) return NAV_W;
        if (panelW >= 460) return Math.max(96, Math.round(NAV_W * 0.62f));
        return 48;
    }

    /** 侧栏是否只画图标（窄档），此时不写分类标题与项数。 */
    private boolean navCompact(int navW) {
        return navW < 90;
    }

    /**
     * 页眉实际高度：矮窗口下压到 {@link #HEADER_H_COMPACT}。
     * 绘制、命中、滚动补偿全走这一个入口 —— 页眉高度一旦在几处各算一遍，
     * 小窗下「标题看起来在 40」而「鼠标按 54 判定」就会点错行。
     */
    private int headerHeight(int panelH) {
        return panelH < 420 ? HEADER_H_COMPACT : HEADER_H;
    }

    // ==================== 面板绘制 ====================

    private void drawPanel(CFontRenderer fr, int[] panel, int mouseX, int mouseY, int wheel,
                           boolean overPanel) {
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];
        drawSoftShadow(panelX, panelY, panelW, panelH, PANEL_RADIUS);

        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.EDITORS,
                panelX, panelY, panelW, panelH, PANEL_RADIUS, layerAlpha);
        drawRoundedRect(panelX, panelY, panelW, panelH, PANEL_RADIUS,
                frosted ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, MConfigTheme.GRAY_800)
                        : MConfigTheme.GRAY_800);
        if (frosted) {
            GlassLook.paintEdge(GlassLook.Surface.EDITORS, panelX, panelY, panelW, panelH,
                    PANEL_RADIUS, layerAlpha);
        }
        drawRoundedRect(panelX + 8, panelY, panelW - 16, 1, 1, MConfigTheme.PANEL_EDGE);

        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        HudSettingsModel.Category active = categories[clampCategory()];

        float headerOuter = pushAlpha(gate(HEADER_FIRST, HEADER_TRAVEL));
        try {
            drawHeader(fr, panelX, panelY, panelW, panelH, active, mouseX, mouseY);
        } finally {
            layerAlpha = headerOuter;
        }

        int headerH = headerHeight(panelH);
        int bodyY = panelY + headerH;
        int bodyH = Math.max(80, panelH - headerH - FOOTER_H);
        int navW = navWidth(panelW);
        drawNav(fr, panelX, bodyY, navW, bodyH, categories, mouseX, mouseY);

        int contentX = panelX + navW;
        int contentW = panelW - navW;

        int owner = scrollKey();
        if (owner != scrollOwner) {
            scrollOwner = owner;
            displayScroll = scroll();
        }
        if (owner != contentOwner) {
            listDirection = contentOwner == Integer.MIN_VALUE ? 0 : Integer.compare(owner, contentOwner);
            contentOwner = owner;
            list.restart();
        }

        int listY = bodyY + 10;
        int listH = Math.max(24, bodyY + bodyH - listY);
        layoutRows(contentX, listY, contentW, active);
        int maxScroll = Math.max(0, contentHeight - listH);
        if (wheel != 0 && overPanel && mouseX < contentX) {
            navScroll = clampNavScroll(navScroll + (wheel > 0 ? -32 : 32));
        } else if (wheel != 0 && overPanel) {
            setScroll(scroll() + (wheel > 0 ? -32 : 32), maxScroll);
        } else {
            setScroll(scroll(), maxScroll);
        }
        displayScroll = Interpolations.interpolate(displayScroll, scroll(), 19f);
        if (Math.abs(displayScroll - scroll()) < 0.4f) displayScroll = scroll();
        layoutRows(contentX, listY, contentW, active);

        beginScissor(contentX, listY, contentW, listH);
        float outerAlpha = layerAlpha;
        tooltipSeenThisFrame = false;
        try {
            // 先把所有卡片底画完，再画行内容：卡片之间彼此独立，不能出现「下一张卡盖住上一张的说明」。
            for (MRow row : rows) {
                if (row.isGroupHeader() || row.emptyState) continue;
                if (row.y + row.h < listY - 4 || row.y > listY + listH + 4) continue;
                float reveal = revealOf(row.slot);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(slideX(reveal), slideY(reveal));
                try {
                    layerAlpha = outerAlpha * reveal;
                    drawCardSurface(row, row.left, mouseX, mouseY);
                    if (row.right != null) drawCardSurface(row, row.right, mouseX, mouseY);
                } finally {
                    SkijaGfx.popMatrix();
                }
            }
            for (MRow row : rows) {
                if (row.y + row.h < listY - 4 || row.y > listY + listH + 4) continue;
                float reveal = revealOf(row.slot);
                float dx = slideX(reveal);
                float dy = slideY(reveal);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(dx, dy);
                try {
                    layerAlpha = outerAlpha * reveal;
                    drawRow(fr, row, mouseX - Math.round(dx), mouseY - Math.round(dy));
                } finally {
                    SkijaGfx.popMatrix();
                }
            }
        } finally {
            layerAlpha = outerAlpha;
            endScissor();
        }

        // tooltip 在所有行之上：它是悬停信息层，压住内容是本分。
        drawTooltip(fr);

        if (maxScroll > 0) {
            int trackTop = listY + 2;
            int trackH = listH - 4;
            int thumbH = Math.max(24, trackH * listH / Math.max(1, contentHeight));
            float thumbY = trackTop + (trackH - thumbH) * clamp01(displayScroll / maxScroll);
            float sbHover = named("scrollbar", isInside(mouseX, mouseY,
                    contentX + contentW - 12, trackTop, 12, trackH));
            drawRoundedRect(contentX + contentW - 8, trackTop, 4, trackH, 2,
                    fade(MConfigTheme.GRAY_300, 0.4f + 0.3f * sbHover));
            drawRoundedRect(contentX + contentW - 8, thumbY, 4, thumbH, 2,
                    fade(MConfigTheme.GRAY_300, 1f));
        }

        float footerOuter = pushAlpha(gate(FOOTER_FIRST, 1.0 - FOOTER_FIRST));
        try {
            drawFooter(fr, panelX, panelY + panelH - FOOTER_H, panelW, mouseX, mouseY);
        } finally {
            layerAlpha = footerOuter;
        }
    }

    // ==================== 头部（品牌 + 面包屑 + 搜索） ====================

    private void drawHeader(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                            HudSettingsModel.Category active, int mouseX, int mouseY) {
        boolean compact = headerHeight(panelH) < HEADER_H;
        drawText(FontManager.pf16bold, "MUONIUM", panelX + 18, panelY + (compact ? 6 : 9),
                MConfigTheme.WHITE_95);
        double brandW = FontManager.pf16bold.getStringWidthD("MUONIUM");
        drawText(FontManager.pf16bold, "CONFIG", panelX + 18 + brandW + 6, panelY + (compact ? 6 : 9),
                MConfigTheme.WHITE_40);

        // 面包屑：上级 WHITE_40、当前 WHITE_90。矮窗口下省掉这一行（高度不够，留标题更要紧）。
        if (!compact) {
            double crumbY = panelY + 31;
            drawText(fr, "设置", panelX + 18, crumbY, MConfigTheme.WHITE_40);
            double setw = fr.getStringWidthD("设置");
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT,
                    panelX + 18 + setw + 12, crumbY + 6, MConfigTheme.WHITE_25);
            drawText(FontManager.pf12bold, active.title, panelX + 18 + setw + 26, crumbY,
                    MConfigTheme.WHITE_90);
        }

        int searchX = panelX + panelW - CONTENT_PAD - SEARCH_W;
        int searchY = panelY + (headerHeight(panelH) - SEARCH_H) / 2;
        drawSearch(fr, searchX, searchY, mouseX, mouseY);

        drawRoundedRect(panelX + 1, panelY + headerHeight(panelH) - 1, panelW - 2, 1, 1,
                fade(MConfigTheme.GRAY_700, 0.9f));
    }

    private void drawSearch(CFontRenderer fr, int x, int y, int mouseX, int mouseY) {
        ensureSearchInput();
        boolean focused = searchInput.isFocused();
        float focus = motion("search.focus", focused ? 1f : 0f, FOCUS_MS);
        float hp = named("search.box", isInside(mouseX, mouseY, x, y, SEARCH_W, SEARCH_H));

        drawRoundedRect(x, y, SEARCH_W, SEARCH_H, 6, MConfigTheme.GRAY_850);
        int border = mix(MConfigTheme.GRAY_700, MConfigTheme.primary(), focus);
        if (hp > 0.02f && focus < 0.02f) border = mix(border, MConfigTheme.GRAY_400, hp);
        drawOutline(x, y, SEARCH_W, SEARCH_H, 6, 1, border);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.SEARCH, x + 12, y + SEARCH_H / 2.0,
                mix(MConfigTheme.TEXT_TERTIARY, MConfigTheme.TEXT_SECONDARY, Math.max(hp, focus)));

        String text = searchInput.getText();
        boolean hasText = !text.isEmpty();
        int clearSize = 16;
        if (hasText) {
            int[] clear = searchClearButton(x, y);
            float cp = named("search.clear", isInside(mouseX, mouseY, clear[0], clear[1], clear[2], clear[3]));
            drawRoundedRect(clear[0], clear[1], clear[2], clear[3], clear[3] * 0.5,
                    fade(MConfigTheme.GRAY_500, 0.4f + cp * 0.6f));
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CLOSE,
                    clear[0] + clear[2] / 2.0, clear[1] + clear[3] / 2.0,
                    mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY, cp));
        }

        int textLeft = x + 24;
        int textRight = x + SEARCH_W - (hasText ? clearSize + 8 : 10);
        searchInput.xPosition = textLeft;
        searchInput.yPosition = y + 5;
        searchInput.width = Math.max(10, textRight - textLeft);
        searchInput.height = SEARCH_H - 8;
        if (!hasText && !focused) {
            drawText(fr, "搜索设置…", textLeft, y + 6, MConfigTheme.TEXT_TERTIARY);
        } else {
            drawText(fr, fr.trim(text, Math.max(10, textRight - textLeft)), textLeft, y + 6,
                    MConfigTheme.TEXT_PRIMARY);
            if (focused) {
                double caretX = textLeft + 1 + Math.min(fr.getStringWidthD(text), textRight - textLeft);
                drawRoundedRect(caretX, y + 5, 1, SEARCH_H - 10, 1, fade(0xFFEAF3FF, focus));
            }
        }
    }

    private int[] searchClearButton(int x, int y) {
        return new int[] { x + SEARCH_W - 20, y + 4, 16, 16 };
    }

    // ==================== 侧栏（胶囊 + 底部动作） ====================

    /**
     * 侧栏列表区高度：整条侧栏减去标题条（24）、标题与列表之间的空隙（12）与底部两个动作。
     *
     * <p>这 12 必须算进去：绘制里 {@code listTop = navY + 24}，而列表区若按 24 起算，
     * 底部就会多出 12px 被最后一行「借走」，表现出来正是「最后一项被分隔线压住一半」。</p>
     */
    private int navListHeight(int navH) {
        return Math.max(24, navH - 36 - NAV_BOTTOM_H);
    }

    /**
     * 侧栏每行的实际高度：拿得到就按设计值 {@link #NAV_ROW_H}，放不下就均分压缩，
     * 下限 {@link #NAV_ROW_MIN_H}。**这是窗口高度适配的唯一入口** ——
     * 绘制、命中、滚动量三处都必须读它，否则矮窗口下「看到的行」和「点得到的行」会错位。
     */
    private int navRowHeight(int listH, int count) {
        if (count <= 0) return NAV_ROW_H;
        int even = listH / count;
        return Math.max(NAV_ROW_MIN_H, Math.min(NAV_ROW_H, even));
    }

    private void drawNav(CFontRenderer fr, int navX, int navY, int navW, int navH,
                         HudSettingsModel.Category[] categories, int mouseX, int mouseY) {
        int listTop = navY + 24;
        int listH = navListHeight(navH);
        int rowH = navRowHeight(listH, categories.length);
        navScroll = Math.max(0, Math.min(navMaxScroll(listH, categories.length, rowH), navScroll));
        int navTop = listTop - navScroll;
        boolean compactNav = navCompact(navW);

        if (!compactNav) {
            drawText(FontManager.pf12bold, "HUD 设置", navX + 12, navY + 7, MConfigTheme.WHITE_25);
        }

        int selected = clampCategory();
        float targetY = navTop + selected * rowH;
        navIndicator = motion("nav.indicator", targetY, INDICATOR_MS, TransitionCurveCache.EXPO);
        navIndicator = Math.max(listTop, Math.min(listTop + listH - rowH, navIndicator));

        float plateOuter = pushAlpha(navGate(selected));
        try {
            drawRoundedRect(navX + 8, navIndicator, navW - 16, rowH, rowH >= 30 ? 11 : 8,
                    MConfigTheme.primaryDark());
        } finally {
            layerAlpha = plateOuter;
        }

        beginScissor(navX, listTop, navW, listH);
        try {
            for (int index = 0; index < categories.length; index++) {
                HudSettingsModel.Category category = categories[index];
                float reveal = navGate(index);
                if (reveal <= 0.002f) continue;
                float rowOuter = pushAlpha(reveal);
                SkijaGfx.pushMatrix();
                try {
                    drawNavRow(fr, category, index, selected, navX, navTop, navW, rowH, navH,
                            mouseX, mouseY, listTop, listH);
                } finally {
                    SkijaGfx.popMatrix();
                    layerAlpha = rowOuter;
                }
            }
        } finally {
            endScissor();
        }

        // 底部固定动作：编辑 HUD / 关闭（OneConfig 侧栏底部的同位语言）。
        int bottomY = navY + navH - NAV_BOTTOM_H + 6;
        // 分隔线贴着列表区的下沿画，而不是贴着底部动作的起点 —— 两者差 24px 时，
        // 最后一行会压在线上，看起来像「被裁掉了半截」。
        drawRoundedRect(navX + 8, listTop + listH + 6, navW - 16, 1, 1,
                fade(MConfigTheme.GRAY_700, 0.7f));
        int[] edit = editHudButton(navX, navY, navH, navW);
        drawNavAction(fr, "nav.edithud", HudSettingsIcons.MOVE,
                compactNav ? null : "编辑 HUD", edit, mouseX, mouseY, false);
        int[] close = closeButton(navX, navY, navH, navW);
        drawNavAction(fr, "nav.close", HudSettingsIcons.CLOSE,
                compactNav ? null : "关闭", close, mouseX, mouseY, true);
    }

    private void drawNavRow(CFontRenderer fr, HudSettingsModel.Category category, int index,
                            int selected, int navX, int navTop, int navW, int rowH, int navH,
                            int mouseX, int mouseY, int listTop, int listH) {
        int rowY = navTop + index * rowH;
        if (rowY + rowH < listTop - 4 || rowY > listTop + listH + 4) return;
        boolean hovered = isInside(mouseX, mouseY, navX + 8, rowY, navW - 16, rowH);
        boolean current = index == selected;
        float hp = named("nav." + index, hovered && !current);
        if (hp > 0.02f && !current) {
            drawRoundedRect(navX + 8, rowY, navW - 16, rowH, rowH >= 30 ? 11 : 8,
                    fade(MConfigTheme.WHITE_6, hp));
        }
        double iconCy = rowY + rowH / 2.0;
        boolean compactNav = navCompact(navW);
        double iconCx = compactNav ? navX + navW / 2.0 : navX + 24;
        drawIcon(FontManager.settingsIcon24, category.icon, iconCx, iconCy,
                current ? 0xFFFFFFFF : mix(0xFF7E8A9C, MConfigTheme.TEXT_SECONDARY, hp));
        if (compactNav) return;
        int titleColor = current ? 0xFFFFFFFF : mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY, hp);
        if (rowH >= 32) {
            // 只有高度够时才摆两行（标题 + 项数）；压缩档下项数省略，标题垂直居中。
            drawText(FontManager.pf12bold, category.title, navX + 40, rowY + 6, titleColor);
            drawText(fr, category.entryCount() + " 项", navX + 40, rowY + 20, MConfigTheme.WHITE_25);
        } else {
            drawText(FontManager.pf12bold, category.title, navX + 40,
                    iconCy - 6, titleColor);
        }
    }

    /** 侧栏底部动作胶囊。destructive = 关闭，hover 走危险色（OneConfig TERTIARY_DESTRUCTIVE）。 */
    private void drawNavAction(CFontRenderer fr, String key, String glyph, String label,
                               int[] box, int mouseX, int mouseY, boolean destructive) {
        float hp = named(key, isInside(mouseX, mouseY, box[0], box[1], box[2], box[3]));
        if (hp > 0.02f) {
            drawRoundedRect(box[0], box[1], box[2], box[3], 9,
                    fade(destructive ? MConfigTheme.GRAY_600 : MConfigTheme.WHITE_6, hp));
        }
        int tint = destructive
                ? mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.DANGER_HOVER, hp)
                : mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY, hp);
        // label 为 null = 窄侧栏的图标态：只画居中的图标。
        if (label == null) {
            drawIcon(FontManager.settingsIcon24, glyph, box[0] + box[2] / 2.0,
                    box[1] + box[3] / 2.0, tint);
            return;
        }
        drawIcon(FontManager.settingsIcon24, glyph, box[0] + 14, box[1] + box[3] / 2.0, tint);
        drawText(FontManager.pf12bold, label, box[0] + 27, box[1] + box[3] / 2.0 - 4, tint);
    }

    /**
     * 侧栏底部两个动作按钮的矩形。宽度取**当前面板的侧栏宽**而不是常量：
     * 窄窗口下侧栏会缩窄，写死 NAV_W 会让按钮戳出面板右边。
     */
    private int[] editHudButton(int navX, int navY, int navH, int navW) {
        return new int[] { navX + 8, navY + navH - NAV_BOTTOM_H + 6, navW - 16, 28 };
    }

    private int[] closeButton(int navX, int navY, int navH, int navW) {
        return new int[] { navX + 8, navY + navH - NAV_BOTTOM_H + 38, navW - 16, 28 };
    }

    // ==================== 底栏 ====================

    private void drawFooter(CFontRenderer fr, int panelX, int footerY, int panelW,
                            int mouseX, int mouseY) {
        drawRoundedRect(panelX + 1, footerY, panelW - 2, 1, 1, fade(MConfigTheme.GRAY_700, 0.7f));
        int[] reset = resetButton(panelX, footerY, panelW);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.INFO, panelX + 20, footerY + 16,
                MConfigTheme.WHITE_25);
        String placement = "信息栏 " + pct(HudConfig.infoX, HudConfig.infoY, HudConfig.infoScale)
                + "   歌词 " + pct(HudConfig.lyricX, HudConfig.lyricY, HudConfig.lyricScale);
        double room = Math.max(20, reset[0] - (panelX + 28) - 10);
        drawText(fr, fr.trim(placement, room), panelX + 28, footerY + 11, MConfigTheme.WHITE_25);

        float hp = named("footer.reset", isInside(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3]));
        drawRoundedRect(reset[0], reset[1], reset[2], reset[3], 9,
                mix(MConfigTheme.WHITE_6, MConfigTheme.GRAY_600, hp));
        boolean iconOnly = reset[2] < 40;
        double iconCx = iconOnly ? reset[0] + reset[2] / 2.0 : reset[0] + 14;
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.RESTORE, iconCx,
                reset[1] + reset[3] / 2.0, mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.DANGER_HOVER, hp));
        if (!iconOnly) {
            drawText(FontManager.pf12bold, "恢复默认", reset[0] + 27, reset[1] + reset[3] / 2.0 - 4,
                    mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY, hp));
        }
    }

    private int[] resetButton(int panelX, int footerY, int panelW) {
        double textW = FontManager.pf12 == null ? 52 : FontManager.pf12.getStringWidthD("恢复默认");
        // 窄面板下不写「恢复默认」四个字，只留图标 —— 否则按钮会吃掉整条底栏，
        // 左边的位置摘要就没地方摆了。
        int w = panelW < 420 ? 26 : (int) Math.round(27 + textW + 14);
        return new int[] { panelX + panelW - 16 - w, footerY + 5, w, 22 };
    }

    // ==================== 行布局（渲染与命中共用） ====================

    private void layoutRows(int contentX, int listY, int contentW, HudSettingsModel.Category active) {
        rows.clear();
        int rowX = contentX + CONTENT_PAD;
        int rowW = contentW - CONTENT_PAD * 2 - 4;
        int cursor = listY - Math.round(displayScroll);
        int top = cursor;

        String query = searchQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            for (HudSettingsModel.Group group : active.groups) {
                cursor = appendGroup(group, active.title, active.accent, rowX, rowW, cursor, query, false);
            }
        } else {
            boolean any = false;
            for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
                for (HudSettingsModel.Group group : category.groups) {
                    int before = rows.size();
                    cursor = appendGroup(group, category.title, category.accent, rowX, rowW, cursor, query, true);
                    if (rows.size() > before) any = true;
                }
            }
            if (!any) {
                rows.add(new MRow(null, null, "没有匹配「" + searchQuery.trim() + "」的设置项",
                        HudSettingsIcons.SEARCH, active.accent, rowX, cursor, rowW, EMPTY_STATE_H,
                        true, true, true, 0));
                cursor += EMPTY_STATE_H;
            }
        }
        contentHeight = cursor - top + 6;
    }

    private int appendGroup(HudSettingsModel.Group group, String categoryTitle, int accent,
                            int rowX, int rowW, int cursor, String query, boolean searching) {
        List<HudSettingsModel.Entry> visible = new ArrayList<HudSettingsModel.Entry>();
        for (HudSettingsModel.Entry entry : group.entries) {
            if (entry.isVisible() && (!searching || entry.matches(query))) visible.add(entry);
        }
        if (visible.isEmpty()) return cursor;

        String title = searching ? categoryTitle + " · " + group.title : group.title;
        rows.add(new MRow(null, null, title, group.icon, accent, rowX, cursor, rowW,
                GROUP_HEADER_H, false, false, false, 0));
        cursor += GROUP_HEADER_H;

        // MD3 卡片网格：每一项自己是独立卡片，两列并排；展开态卡片独占一行并追加详情区。
        int cellW = (rowW - CELL_GAP) / 2;
        boolean twoCol = twoColumns(rowW);
        int index = 0;
        while (index < visible.size()) {
            HudSettingsModel.Entry entry = visible.get(index);
            // 展开的卡片、主题色板都独占整行 —— 展开后要放滑条，半列宽会把轨道压到几乎点不中。
            boolean full = !twoCol || isExpanded(entry)
                    || entry.kind == HudSettingsModel.Kind.THEME_GRID;
            if (!full) {
                HudSettingsModel.Entry next = index + 1 < visible.size() ? visible.get(index + 1) : null;
                if (next != null && !isExpanded(next)
                        && next.kind != HudSettingsModel.Kind.THEME_GRID) {
                    int h = Math.max(cardHeight(entry, cellW), cardHeight(next, cellW));
                    rows.add(new MRow(new Cell(entry, rowX, cellW),
                            new Cell(next, rowX + cellW + CELL_GAP, rowW - cellW - CELL_GAP),
                            null, null, accent, rowX, cursor, rowW, h,
                            false, false, false, 0));
                    cursor += h + CARD_GAP_V;
                    index += 2;
                    continue;
                }
            }
            int h = cardHeight(entry, full ? rowW : cellW);
            rows.add(new MRow(new Cell(entry, rowX, rowW), null, null, null, accent,
                    rowX, cursor, rowW, h, false, false, false, 0));
            cursor += h + CARD_GAP_V;
            index += 1;
        }
        // 组尾留一档呼吸，别让下一组标题贴住本组最后一张卡。
        return cursor + CARD_GAP - CARD_GAP_V;
    }

    /**
     * 卡片高度：折叠态按「说明实际需要几行」定，展开态再追加详情区；主题色板按预设行数算。
     *
     * <p>展开追加的高度也走补间（不是一步跳到位）—— 否则点箭头的那一帧下面所有卡片会瞬移，
     * 而详情内容还在淡入，看起来像列表「抖了一下」。高度取整后才能保证绘制与命中共用同一份矩形。</p>
     */
    private int cardHeight(HudSettingsModel.Entry entry, int cellW) {
        if (entry.kind == HudSettingsModel.Kind.THEME_GRID) {
            return CARD_H + themeGridHeight() + 12;
        }
        return baseCardHeight(entry, cellW) + Math.round(DETAIL_H * expandProgress(entry));
    }

    /**
     * 折叠态卡片高度。说明是这项唯一的信息来源，所以按它实际折出来的行数给高度，
     * 而不是写死两个行距 —— 窄卡片里硬塞两行只会把后半句变成「…」，
     * 然后每个条目都得靠悬停气泡才读得全。
     */
    private int baseCardHeight(HudSettingsModel.Entry entry, int cellW) {
        int lines = descriptionLines(entry, cellW);
        return CARD_H + (lines - 2) * 13;
    }

    /**
     * 该条目在给定卡片宽度下，说明需要几行。
     *
     * <p>最少 2 行、最多 3 行：同一行里的两张卡按较高的那张对齐，屏上节奏才整齐；
     * 但也不能无限长 —— 说明是辅助信息，把卡片撑到半屏反而看不到几项。</p>
     */
    private int descriptionLines(HudSettingsModel.Entry entry, int cellW) {
        CFontRenderer fr = FontManager.pf12;
        if (fr == null || entry.description == null || entry.description.isEmpty()) return 2;
        int labelW = Math.max(40, cellW - 14 * 2 - (hasDetail(entry) ? ARROW_W : 0)
                - controlWidth(fr, entry) - 12);
        int needed = countWrappedLines(fr, entry.description, labelW);
        return Math.max(2, Math.min(3, needed));
    }

    /** 该条目当前是否处于展开态（每组同时只展开一张，避免列表被撑得过长）。 */
    private boolean isExpanded(HudSettingsModel.Entry entry) {
        return expandedEntry == entry && hasDetail(entry);
    }

    /**
     * 只有真有「更多内容」的类型才允许展开。
     *
     * <p>滑条折叠态不露轨道，需要展开才能调；非 inline 动作按钮折叠态只是个标签，
     * 展开后才出现可点的按钮。开关 / 下拉 / 色板 / 主题色板折叠态已经把全部信息
     * （标签、说明、完整控件）摆出来了，再展开只会得到一块空白 —— 所以箭头不出现。</p>
     */
    private static boolean hasDetail(HudSettingsModel.Entry entry) {
        return entry.kind == HudSettingsModel.Kind.SLIDER
                || (entry.kind == HudSettingsModel.Kind.ACTION && !entry.inlineButton);
    }

    /** 展开态进度：折叠 0 → 展开 1，用于详情区高度与箭头旋转。 */
    private float expandProgress(HudSettingsModel.Entry entry) {
        Channel chan = expandChannels.get(entry);
        if (chan == null) {
            chan = new Channel();
            chan.from = chan.to = chan.current = isExpanded(entry) ? 1f : 0f;
            chan.startNanos = System.nanoTime();
            expandChannels.put(entry, chan);
            return chan.current;
        }
        return motion(chan, isExpanded(entry) ? 1f : 0f, EXPAND_MS);
    }

    // ==================== 行绘制 ====================

    /**
     * 一张设置卡片的表面：大圆角 + 三级色调面，悬停/展开各亮一档。
     *
     * <p>不用描边表达层级 —— 深色底上 1px 白线会显得脏；这里靠三级表面色（主题「控件底色」→
     * 「控件悬停」→ 悬停往主色偏一点）拉开「静止 / 悬停 / 展开」。三级全部问 {@link MConfigTheme}
     * 要，而它读的是播放器主题，所以切主题时这一页的面色跟着变。
     * 卡片高度由 {@code cardHeight} 给出，展开时详情区高度随时间补间，这里只负责铺面。</p>
     */
    private void drawCardSurface(MRow row, Cell cell, int mouseX, int mouseY) {
        if (cell == null) return;
        boolean hovered = isInside(mouseX, mouseY, cell.x, row.y, cell.w, row.h);
        boolean expanded = isExpanded(cell.entry);
        float hp = named("card." + System.identityHashCode(cell.entry), hovered && !expanded);
        float ep = expandProgress(cell.entry);
        // 三级面全部来自播放器主题：控件底色 → 控件悬停 → 悬停往主色偏一点（展开态）。
        int fill = mix(mix(MConfigTheme.surface(), MConfigTheme.surfaceHover(), hp),
                MConfigTheme.surfaceActive(), ep);
        drawRoundedRect(cell.x, row.y, cell.w, row.h, CARD_RADIUS, fill);
        // 展开态左侧一道主色指示条：和侧栏选中条同一套语言，说明「这张卡是当前操作对象」。
        if (ep > 0.02f) {
            double barH = (row.h - 20) * ep;
            drawRoundedRect(cell.x, row.y + (row.h - barH) / 2.0, 3, barH, 1.5,
                    fade(MConfigTheme.primary(), ep));
        }
    }

    private void drawRow(CFontRenderer fr, MRow row, int mouseX, int mouseY) {
        if (row.emptyState) {
            drawIcon(FontManager.settingsIcon40, row.icon, row.x + row.w / 2.0, row.y + 26, 0x59FFFFFF);
            drawCenteredText(FontManager.pf12bold, row.title, row.x + row.w / 2.0, row.y + 46,
                    MConfigTheme.TEXT_SECONDARY);
            drawCenteredText(fr, "换个关键词，或者按 ESC 清空搜索", row.x + row.w / 2.0, row.y + 60,
                    MConfigTheme.TEXT_TERTIARY);
            return;
        }
        if (row.isGroupHeader()) {
            drawIcon(FontManager.settingsIcon24, row.icon, row.x + 8, row.y + GROUP_HEADER_H / 2.0,
                    row.accent);
            drawText(FontManager.pf12bold, row.title, row.x + 22, row.y + 7, MConfigTheme.WHITE_90);
            return;
        }
        drawCell(fr, row, row.left, mouseX, mouseY);
        if (row.right != null) drawCell(fr, row, row.right, mouseX, mouseY);
    }

    /**
     * 卡片内容：标题 + 一行说明 + 右侧主控件 + 展开箭头。
     *
     * <p>箭头是这张卡的「更多」入口：点它或点卡片空白区切换展开，展开后追加详情区
     * （滑条轨道 / 完整说明 / 动作按钮）。主控件（开关、色板、下拉药丸）仍然直接可点，
     * 不与展开冲突 —— 这是参考图里那种「一眼能开，想细调再展开」的分层。</p>
     */
    private void drawCell(CFontRenderer fr, MRow row, Cell cell, int mouseX, int mouseY) {
        HudSettingsModel.Entry entry = cell.entry;
        boolean hovered = isInside(mouseX, mouseY, cell.x, row.y, cell.w, row.h);
        boolean dragging = entry.kind == HudSettingsModel.Kind.SLIDER && draggingSlider == entry.setting;
        float[] state = progressOf(entry);
        Channel[] chan = channelsOf(entry);
        state[0] = motion(chan[0], hovered || dragging ? 1f : 0f, HOVER_MS);
        state[2] = motion(chan[2], dragging ? 1f : (hovered ? 0.6f : 0f), HOVER_MS);
        float hp = state[0];
        float ep = expandProgress(entry);

        if (hovered && !entry.isLocked()) {
            drawRoundedRect(cell.x, row.y, cell.w, row.h, CARD_RADIUS,
                    fade(MConfigTheme.WHITE_6, hp * 0.45f));
        }

        // 搜索跳转后的定位脉冲。
        if (entry == flashEntry) {
            float pulse = flashPulse();
            if (pulse > 0.01f) {
                drawOutline(cell.x, row.y, cell.w, row.h, CARD_RADIUS, 1.5,
                        fade(cellAccent(entry, row.accent), 0.85f * pulse));
            }
        }

        int padX = 14;
        int textRight = cell.x + cell.w - padX - ARROW_W;
        // 卡片上半部高度 = 总高 − 已展开的详情区；绘制与布局用的是同一个函数（baseCardHeight）。
        int detailPx = Math.round(DETAIL_H * ep);
        int topH = baseCardHeight(entry, cell.w);
        if (entry.kind == HudSettingsModel.Kind.THEME_GRID) {
            drawText(FontManager.pf12bold, entry.getLabel(), cell.x + padX, row.y + 12,
                    MConfigTheme.TEXT_PRIMARY);
            drawDescription(fr, entry, cell.x + padX, row.y + 30, cell.w - padX * 2);
            drawThemeChips(cell.x + padX, row.y + CARD_H - 8, mouseX, mouseY);
            return;
        }

        // 控件右边界要给箭头留位置；没有箭头（开关这类）就直接贴到卡片内边距。
        int controlRight = cell.x + cell.w - padX - (hasDetail(entry) ? ARROW_W : 0);
        // 主控件与箭头共用一条竖直中线，且这条中线在「标题 + 说明」那一块的正中间。
        // 控件若按整卡高度居中，展开态会被推到底部详情区里去。
        int centerY = row.y + topH / 2;
        // 说明必须止步于控件左缘 —— 否则开关会直接压在文字上（控制区宽度随类型变化，不能写死）。
        int controlW = controlWidth(fr, entry);
        int textLimit = controlRight - (controlW > 0 ? controlW + 12 : 0);
        int labelW = Math.max(40, textLimit - (cell.x + padX));

        drawText(FontManager.pf12bold, entry.getLabel(), cell.x + padX, centerY - 16,
                MConfigTheme.TEXT_PRIMARY);
        // 说明折行：行数由布局那条公式给出（同一张卡在绘制与命中里必须一致）。
        int descLines = descriptionLines(entry, cell.w);
        String[] desc = wrapByWidth(fr, entry.description, labelW, descLines);
        boolean descTruncated = countWrappedLines(fr, entry.description, labelW) > descLines;
        int descOffset = descLines >= 3 ? -6 : 0;
        for (int line = 0; line < desc.length; line++) {
            drawText(fr, desc[line], cell.x + padX, centerY + descOffset + line * 13 - 1,
                    MConfigTheme.descriptionText());
        }
        trackTooltip(entry, hovered, row, cell, descTruncated, mouseX, mouseY);

        // 折叠态只露主控件；滑条收进详情区，避免半列宽把轨道压到点不中。
        if (entry.kind != HudSettingsModel.Kind.SLIDER || isExpanded(entry)) {
            switch (entry.kind) {
                case TOGGLE: {
                    state[1] = motion(chan[1], entry.isOn() ? 1f : 0f, KNOB_MS,
                            TransitionCurveCache.SPRING_LAND);
                    drawSwitch(controlRight - SWITCH_W, centerY - SWITCH_H / 2, state[1], hp);
                    break;
                }
                case COLOR:
                    drawSwatch(controlRight - SWATCH_W, centerY - SWATCH_H / 2, entry.getColor(),
                            editingColor == entry.colorTarget, hp);
                    break;
                case CHOICE: {
                    String value = entry.getValueText();
                    int pillW = choicePillWidth(fr, value);
                    boolean open = openChoice == entry;
                    drawRoundedRect(controlRight - pillW, centerY - 10, pillW, 20, 10,
                            mix(MConfigTheme.GRAY_500, MConfigTheme.GRAY_400,
                                    Math.max(hp, open ? 1f : 0f)));
                    drawText(fr, fr.trim(value, pillW - 30), controlRight - pillW + 10, centerY - 4,
                            MConfigTheme.TEXT_PRIMARY);
                    drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT,
                            controlRight - 10 + (open ? 1.5 : 0), centerY,
                            mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY,
                                    Math.max(hp, open ? 1f : 0f)));
                    break;
                }
                case ACTION: {
                    if (entry.inlineButton) {
                        int w = 62;
                        drawRoundedRect(controlRight - w, centerY - 10, w, 20, 10,
                                MConfigTheme.primaryDark());
                        drawText(FontManager.pf12bold, "执行", controlRight - w / 2.0
                                - FontManager.pf12.getStringWidth("执行") / 2.0, centerY - 4, 0xFFFFFFFF);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        drawExpandArrow(row, cell, ep, hovered, topH);
        // 展开详情区：高度随 ep 长出来，内容在裁剪里淡入。
        if (ep > 0.01f) {
            int detailTop = row.y + topH;
            int detailH = (int) Math.round(DETAIL_H * ep);
            if (detailH > 2) {
                beginScissor(cell.x + 1, detailTop, cell.w - 2, detailH);
                float outer = pushAlpha(ep);
                try {
                    drawCompactDivider(cell.x + padX, detailTop, cell.w - padX * 2);
                    drawCellDetail(fr, row, cell, detailTop, mouseX, mouseY);
                } finally {
                    layerAlpha = outer;
                    endScissor();
                }
            }
        }

        if (entry.isLocked()) {
            drawRoundedRect(cell.x, row.y, cell.w, row.h, CARD_RADIUS, 0x96141A22);
        }
    }

    /** 一行说明：放得下就一行，放不下按宽度截断，展开后详情区给全文。 */
    private void drawDescription(CFontRenderer fr, HudSettingsModel.Entry entry,
                                 double x, double y, double width) {
        String description = entry.description;
        if (description == null || description.isEmpty() || width <= 8) return;
        drawText(fr, fr.trim(description, width), x, y, MConfigTheme.descriptionText());
    }

    /**
     * 折叠态右侧控件要占的宽度。说明折行、标题截断都以它为准 ——
     * 三种控件的宽度差得很远（开关 34、色板 44、下拉药丸随文案变），写死一个数必然有一类压字。
     */
    private int controlWidth(CFontRenderer fr, HudSettingsModel.Entry entry) {
        switch (entry.kind) {
            case TOGGLE:
                return SWITCH_W;
            case COLOR:
                return SWATCH_W;
            case CHOICE:
                return choicePillWidth(fr, entry.getValueText());
            case ACTION:
                return entry.inlineButton ? 62 : 0;
            default:
                return 0;
        }
    }

    private void drawCompactDivider(double x, double y, double w) {
        drawRoundedRect(x, y, w, 1, 1, MConfigTheme.divider());
    }

    /**
     * 详情区：滑条的完整轨道、非 compact 动作按钮的大按钮形态。
     *
     * <p>开关 / 下拉 / 色板在折叠态已经把说明完整写出来了，再展开一遍是重复信息 ——
     * 那些类型的展开态只保留左侧指示条与翻转的箭头，不再抄一遍同一句话。</p>
     */
    private void drawCellDetail(CFontRenderer fr, MRow row, Cell cell, int detailTop,
                                int mouseX, int mouseY) {
        HudSettingsModel.Entry entry = cell.entry;
        int centerY = detailTop + DETAIL_H / 2 + 2;
        int left = cell.x + 14;
        if (entry.kind == HudSettingsModel.Kind.SLIDER) {
            drawSliderControl(fr, cell, detailTop, cellAccent(entry, row.accent),
                    expandProgress(entry));
            return;
        }
        if (entry.kind == HudSettingsModel.Kind.ACTION && !entry.inlineButton) {
            int w = Math.min(120, cell.w - 28);
            drawRoundedRect(left, centerY - 11, w, 22, 11, MConfigTheme.primaryDark());
            drawText(FontManager.pf12bold, entry.getLabel(),
                    left + (w - FontManager.pf12.getStringWidth(entry.getLabel())) / 2.0,
                    centerY - 4, 0xFFFFFFFF);
        }
    }

    /** 卡片右上角的展开箭头：收起时朝下（提示「还有更多」），展开时转向上。 */
    private void drawExpandArrow(MRow row, Cell cell, float ep, boolean hovered, int topH) {
        // 没有详情区的类型不画箭头 —— 画了一个点下去什么都不动的箭头比没有更糟。
        if (!hasDetail(cell.entry)) return;
        double cx = cell.x + cell.w - 14 - ARROW_W / 2.0 + 4;
        // 与主控件同一水平中线：折叠态箭头不该孤零零贴在卡片竖直正中偏下。
        double cy = row.y + topH / 2.0;
        float shade = Math.max(hovered ? 0.6f : 0.35f, ep);
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.translate(cx, cy);
            SkijaGfx.rotateZ(90f + 90f * ep);
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT, 0, 0,
                    mix(MConfigTheme.TEXT_TERTIARY, MConfigTheme.TEXT_PRIMARY, shade));
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    /** 条目自己的强调色：COLOR / THEME 行跟分组 accent；其余行主色。 */
    private static int cellAccent(HudSettingsModel.Entry entry, int groupAccent) {
        return entry.kind == HudSettingsModel.Kind.COLOR ? groupAccent
                : entry.kind == HudSettingsModel.Kind.THEME_GRID ? groupAccent
                : MConfigTheme.primary();
    }

    // ==================== 控件 ====================

    /** 展开卡片详情区里的滑条：全宽轨道 + 右端数值框（点数值框可直接键入）。 */
    private void drawSliderControl(CFontRenderer fr, Cell cell, int detailTop, int accent,
                                   float knobProgress) {
        HudSetting setting = cell.entry.setting;
        int centerY = detailTop + DETAIL_H / 2 + 2;
        int inputX = cell.x + cell.w - 14 - VALUE_W;
        int trackX = cell.x + 14;
        int trackW = Math.max(60, inputX - 10 - trackX);
        ensureSliderValueInput();
        boolean inputting = editingSliderValue == setting && sliderValueInput != null
                && sliderValueInput.isFocused();
        if (inputting) {
            sliderValueInput.xPosition = inputX + 5;
            sliderValueInput.yPosition = centerY - 7;
            sliderValueInput.width = VALUE_W - 10;
            sliderValueInput.height = 14;
            drawRoundedRect(inputX, centerY - 10, VALUE_W, 20, 6, MConfigTheme.GRAY_850);
            drawOutline(inputX, centerY - 10, VALUE_W, 20, 6, 1, MConfigTheme.primary());
            drawText(fr, sliderValueInput.getText(), inputX + 6, centerY - 4,
                    MConfigTheme.TEXT_PRIMARY);
            if (sliderValueInput.isFocused()) {
                int caretX = inputX + 7 + fr.getStringWidth(sliderValueInput.getText());
                drawRoundedRect(caretX, centerY - 6, 1, 12, 1, 0xFFEAF3FF);
            }
        } else {
            String value = HudSettingsModel.formatValue(setting, setting.getValue());
            drawText(fr, value, inputX + (VALUE_W - fr.getStringWidth(value)) / 2.0, centerY - 4,
                    mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY, knobProgress));
        }
        drawTrackAndKnob(setting, trackX, trackW, centerY, accent, knobProgress, inputting);
    }

    /** 展开卡片里滑条轨道的起点与宽度：绘制与命中共用，避免两边各算一套。 */
    private int[] sliderTrackBounds(Cell cell) {
        int inputX = cell.x + cell.w - 14 - VALUE_W;
        int trackX = cell.x + 14;
        return new int[] { trackX, Math.max(60, inputX - 10 - trackX) };
    }

    /** OneConfig 滑条：3px 灰轨道 + 5px 主色已滑过 + 白圆把手，悬停时把手内嵌主色点。 */
    private void drawTrackAndKnob(HudSetting setting, int trackX, int trackW, int centerY,
                                  int accent, float knobProgress, boolean inputMode) {
        double trackY = centerY - TRACK_H / 2.0;
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        double fillW = trackW * progress;
        drawRoundedRect(trackX, trackY, trackW, TRACK_H, TRACK_H * 0.5, MConfigTheme.GRAY_300);
        if (fillW > 1) {
            drawRoundedRect(trackX, trackY - 1, fillW, FILL_H, FILL_H * 0.5,
                    inputMode ? fade(accent, 0.55f) : accent);
        }
        double knobX = trackX + fillW;
        double r = KNOB_R;
        if (knobProgress > 0.02f) {
            // OneConfig 的触摸指示：把手内侧一个主色点，随悬停从 0 长出来。
            double dot = r * 1.4 * knobProgress;
            drawRoundedRect(knobX - dot / 2.0, centerY - dot / 2.0, dot, dot, dot / 2.0,
                    fade(accent, 0.9f * knobProgress));
        }
        drawRoundedRect(knobX - r, centerY - r + 1, r * 2, r * 2, r, 0x55000000);
        drawRoundedRect(knobX - r, centerY - r, r * 2, r * 2, r,
                inputMode ? 0xCCF6F8FC : 0xFFFFFFFF);
    }

    /** OneConfig 开关：34×20 圆角轨道，14×14 白拇指，SPRING_LAND 回弹。 */
    private void drawSwitch(int x, int y, float progress, float hover) {
        int track = mix(MConfigTheme.GRAY_600, MConfigTheme.primary(), progress);
        drawRoundedRect(x, y, SWITCH_W, SWITCH_H, SWITCH_H * 0.5, mix(track, MConfigTheme.GRAY_500, hover * 0.4f));
        double knob = SWITCH_H - 6;
        double knobX = x + 3 + (SWITCH_W - knob - 6) * progress;
        drawRoundedRect(knobX, y + 3.5, knob, knob, knob * 0.5, 0x40000000);
        drawRoundedRect(knobX, y + 3, knob, knob, knob * 0.5, 0xFFFFFFFF);
    }

    /** OneConfig 色板：44×24 圆角块 + 透明棋盘格 + 1.5px GRAY_300 外描边。 */
    private void drawSwatch(int x, int y, Color color, boolean selected, float hover) {
        drawRoundedRect(x, y, SWATCH_W, SWATCH_H, 8, MConfigTheme.GRAY_600);
        int cell = 6;
        for (int cy = 0; cy < SWATCH_H; cy += cell) {
            for (int cx = 0; cx < SWATCH_W; cx += cell) {
                boolean light = ((cx / cell) + (cy / cell)) % 2 == 0;
                drawRoundedRect(x + cx, y + cy, Math.min(cell, SWATCH_W - cx),
                        Math.min(cell, SWATCH_H - cy), 0, light ? 0xFF3A3D42 : 0xFF2A2C30);
            }
        }
        if (color != null) {
            drawRoundedRect(x, y, SWATCH_W, SWATCH_H, 8, color.getRGB() | 0xFF000000);
        }
        drawOutline(x - 0.5, y - 0.5, SWATCH_W + 1, SWATCH_H + 1, 8, 1.5,
                selected ? 0xFFFFFFFF : mix(MConfigTheme.GRAY_300, MConfigTheme.GRAY_400, hover));
    }

    private void drawThemeChips(int x, int y, int mouseX, int mouseY) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        for (int i = 0; i < presets.length; i++) {
            int chipX = x + (i % themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            boolean selected = presets[i] == current;
            boolean lockedTheme = !NCMTheme.isSelectable(presets[i]);
            float hp = named("theme." + i, isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE));
            if (!lockedTheme && (selected || hp > 0.02f)) {
                drawRoundedRect(chipX - 2, chipY - 2, THEME_CHIP_SIZE + 4, THEME_CHIP_SIZE + 4, 8,
                        selected ? 0xFFF4F7FB : fade(MConfigTheme.WHITE_25, hp));
            }
            drawRoundedRect(chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE, 6,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            drawRoundedRect(chipX + 5, chipY + 5, THEME_CHIP_SIZE - 10, THEME_CHIP_SIZE - 10, 4,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT));
            if (lockedTheme) {
                drawRoundedRect(chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE, 6, 0x96141A22);
            }
            if (selected) {
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                        chipX + THEME_CHIP_SIZE / 2.0, chipY + THEME_CHIP_SIZE / 2.0,
                        MConfigTheme.contrastOn(0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT)));
            }
        }
    }

    private int themeChipColumns() {
        return 9;
    }

    private int themeGridHeight() {
        int count = NCMTheme.ThemePreset.values().length;
        int lines = Math.max(1, (count + themeChipColumns() - 1) / themeChipColumns());
        return lines * (THEME_CHIP_SIZE + THEME_CHIP_GAP) + 2;
    }

    private int themeChipIndexAt(int mouseX, int mouseY, int x, int y) {
        int count = NCMTheme.ThemePreset.values().length;
        for (int i = 0; i < count; i++) {
            int chipX = x + (i % themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            if (isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE)) return i;
        }
        return -1;
    }

    // ==================== 描述 tooltip ====================

    /**
     * 悬停追踪：同一格连续悬停超过 TOOLTIP_DELAY_MS 后开始淡入。
     * 直接持有条目引用（条目声明是静态常量，身份稳定）；本帧是否悬停在格上由
     * {@code tooltipSeenThisFrame} 标记（drawPanel 每帧清零，drawCell 命中时置位）。
     *
     * <p>卡片本身已经写了两行说明，tooltip 只在「说明确实被截断」时才补全文 ——
     * 否则鼠标一停就多出一块气泡压住下面的卡，信息没有增加，只有遮挡。</p>
     */
    private void trackTooltip(HudSettingsModel.Entry entry, boolean hovered, MRow row, Cell cell,
                              boolean descriptionTruncated, int mouseX, int mouseY) {
        if (!hovered || !descriptionTruncated
                || entry.description == null || entry.description.isEmpty()) return;
        // 只有「换到另一张卡」或「鼠标真的动了」才重新计时。鼠标静止时反复调用会把
        // tooltipSince 一直往前推，气泡就变成常驻浮层，把下面的卡片压住。
        boolean moved = Math.abs(mouseX - lastTooltipMouseX) > 2
                || Math.abs(mouseY - lastTooltipMouseY) > 2;
        if (tooltipEntry != entry || moved) {
            tooltipEntry = entry;
            tooltipSince = System.currentTimeMillis();
            lastTooltipMouseX = mouseX;
            lastTooltipMouseY = mouseY;
        }
        // 贴着卡片上沿出，而不是屏幕顶部：气泡要解释的是这一张卡。
        tooltipRowY = row.y;
        tooltipCenterX = cell.x + cell.w / 2.0;
        tooltipSeenThisFrame = true;
    }

    private void drawTooltip(CFontRenderer fr) {
        long now = System.currentTimeMillis();
        boolean show = tooltipSeenThisFrame && tooltipEntry != null;
        tooltipAlpha = (float) TransitionCurveCache.MOTION.at(clamp01(
                show ? (now - tooltipSince - TOOLTIP_DELAY_MS) / (float) TOOLTIP_FADE_MS : 0f));
        if (tooltipAlpha <= 0.01f) return;
        // 全文按 240 宽折行；不要再走 wrapToTwoLines 的两行截断，气泡存在的理由就是看全那句说明。
        List<String> lines = wrapByWidthList(fr, tooltipEntry.description, 240);
        double w = 0;
        for (String line : lines) w = Math.max(w, fr.getStringWidthD(line));
        w += 20;
        int h = 8 + lines.size() * 13;
        int x = (int) Math.max(4, Math.min(width - w - 4, tooltipCenterX - w / 2.0));
        // 气泡统一挂在卡片下方：卡片高度是已知量，往下放永远有位置，
        // 而往上放要依赖「上面还剩多少空间」，在窄窗口里会一路顶到标题栏上。
        int cardTop = tooltipRowY;
        int y = cardTop + CARD_H + 6;
        if (y + h > height - 4) {
            y = Math.max(4, cardTop - h - 6);
        }
        float outer = pushAlpha(tooltipAlpha);
        try {
            drawRoundedRect(x, y, w, h, 8, MConfigTheme.floatSurface());
            drawOutline(x, y, w, h, 8, 1, MConfigTheme.divider());
            for (int i = 0; i < lines.size(); i++) {
                drawText(fr, lines.get(i), x + 10, y + 5 + i * 13, MConfigTheme.TEXT_PRIMARY);
            }
        } finally {
            layerAlpha = outer;
        }
    }

    /** tooltip 当前的条目与定位（trackTooltip 记录，drawTooltip 消费）。 */
    private HudSettingsModel.Entry tooltipEntry;
    private int tooltipRowY;
    private double tooltipCenterX;
    private boolean tooltipSeenThisFrame;
    /** 上一次触发 tooltip 计时时的鼠标位置：静止不刷新，只有真的移动才重新计时。 */
    private int lastTooltipMouseX;
    private int lastTooltipMouseY;

    // ==================== CHOICE 下拉浮层 ====================

    private int[] choiceDropdownBounds(CFontRenderer fr, int[] panel) {
        if (openChoice == null) return null;
        MRow anchorRow = null;
        Cell anchor = null;
        for (MRow row : rows) {
            if (row.left != null && row.left.entry == openChoice) {
                anchorRow = row;
                anchor = row.left;
                break;
            }
            if (row.right != null && row.right.entry == openChoice) {
                anchorRow = row;
                anchor = row.right;
                break;
            }
        }
        if (anchorRow == null) return null;
        String[] choices = openChoice.getChoices();
        boolean hinted = openChoice.hasChoiceHints();
        int w = DROP_MIN_W;
        for (String choice : choices) {
            if (choice == null) continue;
            w = Math.max(w, fr.getStringWidth(choice) + 58);
        }
        w = Math.min(DROP_MAX_W, w);
        int itemH = hinted ? DROP_ITEM_H : DROP_ITEM_COMPACT_H;
        int visible = Math.min(choices.length, DROP_MAX_VISIBLE);
        int h = visible * itemH + 8;
        int bodyY = panel[1] + headerHeight(panel[3]);
        int bodyBottom = panel[1] + panel[3] - FOOTER_H;
        int x = Math.max(panel[0] + 8, Math.min(panel[0] + panel[2] - 8 - w,
                anchor.x + anchor.w - w));
        int y = anchorRow.y + anchorRow.h + 4;
        if (y + h > bodyBottom) {
            int upY = anchorRow.y - 4 - h;
            if (upY >= bodyY) {
                y = upY;
            } else {
                int fit = Math.max(1, (bodyBottom - anchorRow.y - 12) / itemH);
                if (fit < visible) {
                    visible = fit;
                    h = visible * itemH + 8;
                }
                y = Math.min(anchorRow.y + anchorRow.h + 4, bodyBottom - h);
            }
        }
        return new int[] { x, y, w, h, itemH, visible };
    }

    private int choiceItemAt(int mouseX, int mouseY, int[] drop) {
        int inner = mouseY - drop[1] - 4;
        if (inner < 0) return -1;
        int index = inner / drop[4];
        if (index >= drop[5]) return -1;
        return index;
    }

    private void drawChoiceDropdown(CFontRenderer fr, int[] panel, int mouseX, int mouseY) {
        int[] drop = choiceDropdownBounds(fr, panel);
        if (drop == null) return;
        String[] choices = openChoice == null ? new String[0] : openChoice.getChoices();
        String[] hints = openChoice == null ? new String[0] : openChoice.getChoiceHints();
        String current = openChoice == null ? null : openChoice.getValueText();
        int x = drop[0];
        int y = drop[1];
        int w = drop[2];
        int h = drop[3];
        int itemH = drop[4];
        int visible = drop[5];

        drawSoftShadow(x, y, w, h, 10);
        drawRoundedRect(x, y, w, h, 10, MConfigTheme.FLOAT_BG);
        drawOutline(x, y, w, h, 10, 1, 0x4DCCCCCC);

        beginScissor(x, y, w, h);
        try {
            for (int index = 0; index < choices.length && index < visible; index++) {
                String choice = choices[index];
                int itemY = y + 4 + index * itemH;
                boolean isCurrent = choice.equals(current);
                boolean hovered = isInside(mouseX, mouseY, x + 4, itemY, w - 8, itemH);
                float hp = named("drop." + index, hovered && !isCurrent);
                if (isCurrent) {
                    drawRoundedRect(x + 4, itemY, w - 8, itemH, 8, MConfigTheme.WHITE_10);
                } else if (hp > 0.02f) {
                    drawRoundedRect(x + 4, itemY, w - 8, itemH, 8, MConfigTheme.primaryDark());
                }
                int ink = isCurrent ? MConfigTheme.TEXT_PRIMARY
                        : mix(MConfigTheme.TEXT_SECONDARY, MConfigTheme.TEXT_PRIMARY, hp);
                drawText(FontManager.pf12bold, fr.trim(choice, w - 40),
                        x + 14, itemY + (itemH - 12) / 2 - 1, ink);
                if (isCurrent) {
                    drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                            x + w - 16, itemY + itemH / 2.0, MConfigTheme.primary());
                }
                if (hints != null && index < hints.length && hints[index] != null && !hints[index].isEmpty()) {
                    drawText(fr, fr.trim(hints[index], w - 28), x + 14, itemY + itemH - 13,
                            MConfigTheme.WHITE_25);
                }
            }
        } finally {
            endScissor();
        }
        if (choices.length > visible) {
            drawCenteredText(fr, "… 共 " + choices.length + " 项", x + w / 2.0, y + h - 11,
                    MConfigTheme.WHITE_25);
        }
    }

    // ==================== 取色器（OneConfig ColorSelector 布局） ====================

    private int pickerX(int[] panel) {
        return panel[0] + Math.max(6, (panel[2] - PICKER_W) / 2);
    }

    private int pickerY(int[] panel) {
        return panel[1] + Math.max(headerHeight(panel[3]) + 6, (panel[3] - PICKER_H) / 2);
    }

    private static String pickerTitle(HudSettingsModel.ColorTarget target) {
        switch (target) {
            case CURRENT: return "当前歌词颜色";
            case WORLD_LYRICS: return "3D 歌词文字颜色";
            default: return "普通歌词颜色";
        }
    }

    private void drawColorPicker(CFontRenderer fr, int[] panel, int mouseX, int mouseY) {
        int x = pickerX(panel);
        int y = pickerY(panel);
        drawSoftShadow(x, y, PICKER_W, PICKER_H, 12);
        drawRoundedRect(x, y, PICKER_W, PICKER_H, 12, MConfigTheme.FLOAT_BG);
        drawOutline(x, y, PICKER_W, PICKER_H, 12, 1, 0x4DCCCCCC);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE, x + 18, y + 16,
                MConfigTheme.WHITE_80);
        drawText(FontManager.pf12bold, pickerTitle(editingColor), x + 28, y + 11, MConfigTheme.TEXT_PRIMARY);
        drawGhostButton("picker.close", HudSettingsIcons.CLOSE, null,
                new int[] { x + PICKER_W - 30, y + 6, 20, 20 }, mouseX, mouseY, MConfigTheme.TEXT_SECONDARY);

        int svX = x + 14;
        int svY = y + 34;
        drawSaturationBrightnessArea(svX, svY, SV_W, SV_H);
        int hueX = svX + SV_W + 12;
        drawHueBar(hueX, svY, HUE_W, SV_H);

        Color preview = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        drawRoundedRect(x + 14, svY + SV_H + 14, 24, HEX_INPUT_H, 6, preview.getRGB() | 0xFF000000);

        ensureHexColorInput();
        int inputX = x + 48;
        int inputY = svY + SV_H + 14;
        hexColorInput.xPosition = inputX + 6;
        hexColorInput.yPosition = inputY + 5;
        hexColorInput.width = HEX_INPUT_W - 12;
        hexColorInput.height = HEX_INPUT_H - 10;
        drawRoundedRect(inputX, inputY, HEX_INPUT_W, HEX_INPUT_H, 6, MConfigTheme.GRAY_850);
        drawOutline(inputX, inputY, HEX_INPUT_W, HEX_INPUT_H, 6, 1,
                hexColorInput.isFocused() ? MConfigTheme.primary() : MConfigTheme.GRAY_700);
        drawText(fr, hexColorInput.getText(), inputX + 7, inputY + 5, MConfigTheme.TEXT_PRIMARY);
        if (hexColorInput.isFocused()) {
            int caretX = inputX + 8 + fr.getStringWidth(hexColorInput.getText());
            drawRoundedRect(caretX, inputY + 5, 1, HEX_INPUT_H - 10, 1, 0xFFEAF3FF);
        }
        drawText(fr, "输入 HEX", inputX + HEX_INPUT_W + 9, inputY + 5, MConfigTheme.WHITE_25);
    }

    private void drawSaturationBrightnessArea(int x, int y, int w, int h) {
        int hueRgb = Color.HSBtoRGB(pickerHue, 1.0f, 1.0f) | 0xFF000000;
        Gui.drawRect(x, y, x + w, y + h, hueRgb);
        for (int col = 0; col < w; col++) {
            int alpha = (int) (255.0f * (1.0f - col / (float) (w - 1)));
            Gui.drawRect(x + col, y, x + col + 1, y + h, alpha << 24 | 0xFFFFFF);
        }
        for (int rowIndex = 0; rowIndex < h; rowIndex++) {
            int alpha = (int) (255.0f * (rowIndex / (float) (h - 1)));
            Gui.drawRect(x, y + rowIndex, x + w, y + rowIndex + 1, alpha << 24);
        }
        drawPickerMarker(x + Math.round(pickerSaturation * (w - 1)),
                y + Math.round((1.0f - pickerBrightness) * (h - 1)));
    }

    private void drawHueBar(int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) {
            Gui.drawRect(x, y + row, x + w, y + row + 1,
                    Color.HSBtoRGB(1.0f - row / (float) (h - 1), 1.0f, 1.0f) | 0xFF000000);
        }
        int markerY = y + Math.round((1.0f - pickerHue) * (h - 1));
        drawRoundedRect(x - 2, markerY - 5, w + 4, 10, 5, 0xFF000000);
        drawRoundedRect(x - 1, markerY - 4, w + 2, 8, 4, 0xFFFFFFFF);
    }

    private static void drawPickerMarker(int x, int y) {
        drawRing(x, y, 3, 0xFF000000);
        drawRing(x, y, 2, 0xFFFFFFFF);
    }

    private static void drawRing(int cx, int cy, int r, int color) {
        for (int a = 0; a < 64; a++) {
            double t = a / 64.0 * Math.PI * 2;
            int px = cx + (int) Math.round(Math.cos(t) * r);
            int py = cy + (int) Math.round(Math.sin(t) * r);
            Gui.drawRect(px, py, px + 1, py + 1, color);
        }
    }

    // ==================== 按钮 ====================

    private void drawGhostButton(String key, String glyph, String label, int[] box,
                                 int mouseX, int mouseY, int tint) {
        int x = box[0];
        int y = box[1];
        int w = box[2];
        int h = box[3];
        float hp = named(key, isInside(mouseX, mouseY, x, y, w, h));
        drawRoundedRect(x, y, w, h, h * 0.5, mix(MConfigTheme.WHITE_6, MConfigTheme.GRAY_500, hp));
        int color = mix(tint, 0xFFFFFFFF, hp * 0.6f);
        if (label == null || label.isEmpty()) {
            drawIcon(FontManager.settingsIcon24, glyph, x + w / 2.0, y + h / 2.0, color);
        } else {
            drawIcon(FontManager.settingsIcon24, glyph, x + 13, y + h / 2.0, color);
            drawText(FontManager.pf12bold, label, x + 24, y + h / 2.0 - 4, color);
        }
    }

    // ==================== 鼠标 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = WindowAdaptiveScale.toLayoutXForced(mouseX);
            mouseY = WindowAdaptiveScale.toLayoutYForced(mouseY);
        }
        try {
            mouseClickedImpl(mouseX, mouseY, button);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void mouseClickedImpl(int mouseX, int mouseY, int button) throws IOException {
        if (page.isDismissing()) return;
        boolean modeBusy = resetConfirmationVisible || plate < 0.999f
                || (editing ? editGate < 0.98f : editGate > 0.02f);
        if (modeBusy) return;
        if (resetConfirmationVisible) {
            if (button == 0) handleResetConfirmationClick(mouseX, mouseY);
            return;
        }
        if (button == 0) commitSliderValueEdit();

        int[] panel = panelBounds();

        if (editing) {
            mouseClickedEditing(mouseX, mouseY, button);
            return;
        }

        if (openChoice != null) {
            int[] drop = choiceDropdownBounds(FontManager.pf12, panel);
            if (drop != null && isInside(mouseX, mouseY, drop[0], drop[1], drop[2], drop[3])) {
                int item = choiceItemAt(mouseX, mouseY, drop);
                if (item >= 0) {
                    String[] choices = openChoice.getChoices();
                    if (item < choices.length) {
                        openChoice.setChoice(choices[item]);
                        HudConfig.save();
                    }
                }
            }
            openChoice = null;
            return;
        }

        if (editingColor != HudSettingsModel.ColorTarget.NONE
                && handlePickerClick(mouseX, mouseY, button, panel)) {
            return;
        }

        if (!isInside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3])) {
            editingColor = HudSettingsModel.ColorTarget.NONE;
            openChoice = null;
            if (searchInput != null) searchInput.setFocused(false);
            return;
        }

        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];

        int[] reset = resetButton(panelX, panelY + panelH - FOOTER_H, panelW);
        if (isInside(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3])) {
            resetConfirmationVisible = true;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            draggingSlider = null;
            return;
        }

        int headerH = headerHeight(panelH);
        int bodyY = panelY + headerH;
        int bodyH = Math.max(80, panelH - headerH - FOOTER_H);
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();

        int navW = navWidth(panelW);
        if (mouseX < panelX + navW) {
            int[] edit = editHudButton(panelX, bodyY, bodyH, navW);
            if (isInside(mouseX, mouseY, edit[0], edit[1], edit[2], edit[3])) {
                enterEditMode();
                return;
            }
            int[] close = closeButton(panelX, bodyY, bodyH, navW);
            if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
                beginRetire();
                return;
            }
            int listTop = bodyY + 24;
            int listH = navListHeight(bodyH);
            int navRowH = navRowHeight(listH, categories.length);
            int navMax = Math.max(0, Math.min(navMaxScroll(listH, categories.length, navRowH), navScroll));
            for (int index = 0; index < categories.length; index++) {
                int rowY = listTop - navMax + index * navRowH;
                if (isInside(mouseX, mouseY, panelX + 8, rowY, navW - 16, navRowH)) {
                    categoryIndex = index;
                    editingColor = HudSettingsModel.ColorTarget.NONE;
                    clearSearch();
                    return;
                }
            }
            return;
        }

        int contentX = panelX + navW;
        int contentW = panelW - navW;
        int searchX = panelX + panelW - CONTENT_PAD - SEARCH_W;
        int searchY = panelY + (headerHeight(panelH) - SEARCH_H) / 2;
        ensureSearchInput();
        if (!searchInput.getText().isEmpty()) {
            int[] clear = searchClearButton(searchX, searchY);
            if (isInside(mouseX, mouseY, clear[0], clear[1], clear[2], clear[3])) {
                clearSearch();
                return;
            }
        }
        if (isInside(mouseX, mouseY, searchX, searchY, SEARCH_W, SEARCH_H)) {
            searchInput.setFocused(true);
            searchInput.mouseClicked(mouseX, mouseY, button);
            return;
        }
        searchInput.setFocused(false);

        int listY = bodyY + 10;
        int listH = Math.max(24, bodyY + bodyH - listY);
        if (!isInside(mouseX, mouseY, contentX, listY, contentW, listH)) return;

        layoutRows(contentX, listY, contentW, categories[clampCategory()]);
        boolean searching = !searchQuery.trim().isEmpty();
        for (MRow row : rows) {
            if (row.isGroupHeader() || row.emptyState) continue;
            if (!isInside(mouseX, mouseY, row.x, row.y, row.w, row.h)) continue;
            Cell cell = pickCell(row, mouseX);
            if (cell == null) continue;
            if (searching) {
                jumpToEntry(cell.entry, contentX, listY, contentW);
            } else {
                activateCell(row, cell, mouseX, mouseY);
            }
            return;
        }
    }

    /** 双列行里鼠标落在哪一格。 */
    private static Cell pickCell(MRow row, int mouseX) {
        if (row.right == null) return row.left;
        int mid = row.left.x + row.left.w + CELL_GAP / 2;
        return mouseX < mid ? row.left : row.right;
    }

    /** CHOICE 药丸宽度：值文本 + 40，钳在 [64, 150]（OneConfig 下拉按钮的宽度语义）。 */
    private static int choicePillWidth(CFontRenderer fr, String value) {
        int textWidth = fr == null || value == null ? 0 : fr.getStringWidth(value);
        return Math.max(64, Math.min(150, textWidth + 40));
    }

    private void mouseClickedEditing(int mouseX, int mouseY, int button) {
        // 快捷小窗最顶层。
        if (stage.hasSelection() || popGate > 0.01f) {
            int[] pop = widgetPopoverBounds();
            if (isInside(mouseX, mouseY, pop[0], pop[1], pop[2], pop[3])) {
                handleWidgetPopoverClick(mouseX, mouseY, pop);
                return;
            }
        }
        // 完成胶囊：返回配置页。
        int[] done = doneButtonBounds();
        if (isInside(mouseX, mouseY, done[0], done[1], done[2], done[3])) {
            editing = false;
            return;
        }
        // 其余落点（含右键）交给舞台：stage.update 在 drawScreen 里按 lmb 推进点选/拖动/缩放。
    }

    private void enterEditMode() {
        editing = true;
        editingColor = HudSettingsModel.ColorTarget.NONE;
        openChoice = null;
        if (searchInput != null) searchInput.setFocused(false);
        draggingSlider = null;
    }

    private int[] doneButtonBounds() {
        String label = "完成 · Esc 返回设置";
        int w = (FontManager.pf12 == null ? 120 : FontManager.pf12.getStringWidth(label)) + 30;
        return new int[] { (width - w) / 2, height - 40, w, 26 };
    }

    private void activateCell(MRow row, Cell cell, int mouseX, int mouseY) {
        HudSettingsModel.Entry entry = cell.entry;
        // 展开/收起优先：箭头命中区与卡片右上角空白都算「更多」。
        if (isInside(mouseX, mouseY, cell.x + cell.w - 14 - ARROW_W, row.y, ARROW_W + 14, CARD_H)) {
            toggleExpanded(entry);
            return;
        }
        if (entry.isLocked()) return;
        switch (entry.kind) {
            case SLIDER: {
                // 折叠态滑条不露轨道：点卡片先展开，展开后再点/拖才是调值。
                if (!isExpanded(entry)) {
                    toggleExpanded(entry);
                    break;
                }
                int[] track = sliderTrackBounds(cell);
                int trackX = track[0];
                int trackW = track[1];
                if (mouseX < trackX - 8 || mouseX > trackX + trackW + 8) {
                    beginSliderValueEdit(entry.setting);
                    break;
                }
                if (editingSliderValue != null) {
                    commitSliderValueEdit();
                }
                draggingSlider = entry.setting;
                draggingSliderTrackX = trackX;
                draggingTrackW = trackW;
                setSliderFromMouse(entry.setting, mouseX, trackX, trackW);
                break;
            }
            case TOGGLE:
                entry.activate();
                break;
            case ACTION:
                // 非 inline 动作按钮画在详情区，折叠态点卡片先展开；inline 的紧凑胶囊仍直接执行。
                if (!entry.inlineButton && !isExpanded(entry)) {
                    toggleExpanded(entry);
                    break;
                }
                entry.activate();
                break;
            case CHOICE:
                openChoice = openChoice == entry ? null : entry;
                break;
            case COLOR:
                beginColorEdit(entry);
                break;
            case THEME_GRID: {
                int chip = themeChipIndexAt(mouseX, mouseY, cell.x + 14, row.y + CARD_H - 4);
                if (chip >= 0) {
                    NCMTheme.ThemePreset preset = NCMTheme.ThemePreset.values()[chip];
                    if (!NCMTheme.isSelectable(preset)) break;
                    DownloadDynamicIsland.showTheme(NCMTheme.setCurrent(preset) == null
                            ? NCMTheme.getCurrentName() : preset.getDisplayName());
                }
                break;
            }
            default:
                break;
        }
    }

    /** 展开态切换：同一时刻只留一张卡展开，点第二张会把上一张收回去。 */
    private void toggleExpanded(HudSettingsModel.Entry entry) {
        expandedEntry = expandedEntry == entry ? null : entry;
        if (expandedEntry != null) {
            openChoice = null;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (searchInput != null) searchInput.setFocused(false);
        }
    }

    private boolean handlePickerClick(int mouseX, int mouseY, int button, int[] panel) {
        int x = pickerX(panel);
        int y = pickerY(panel);
        int[] close = { x + PICKER_W - 30, y + 6, 20, 20 };
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (hexColorInput != null) hexColorInput.setFocused(false);
            return true;
        }
        int svX = x + 14;
        int svY = y + 34;
        int hueX = svX + SV_W + 12;
        int inputX = x + 48;
        int inputY = svY + SV_H + 14;
        ensureHexColorInput();
        if (isInside(mouseX, mouseY, inputX, inputY, HEX_INPUT_W, HEX_INPUT_H)) {
            hexColorInput.setFocused(true);
            hexColorInput.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        hexColorInput.setFocused(false);
        if (isInside(mouseX, mouseY, svX, svY, SV_W, SV_H)) {
            draggingSaturationBrightness = true;
            applyPickerAt(mouseX, mouseY, panel);
            return true;
        }
        if (isInside(mouseX, mouseY, hueX, svY, HUE_W, SV_H)) {
            draggingHue = true;
            applyPickerAt(mouseX, mouseY, panel);
            return true;
        }
        return false;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = WindowAdaptiveScale.toLayoutXForced(mouseX);
            mouseY = WindowAdaptiveScale.toLayoutYForced(mouseY);
        }
        try {
            if (state == 0 && (draggingSaturationBrightness || draggingHue
                    || draggingSlider != null || settingsDirty || draggingPopoverScale)) {
                HudConfig.save();
                settingsDirty = false;
            }
            draggingSaturationBrightness = false;
            draggingHue = false;
            draggingSlider = null;
            draggingPopoverScale = false;
            super.mouseReleased(mouseX, mouseY, state);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void updateSliderWhileDragging(int mouseX) {
        if (draggingSlider != null && Mouse.isButtonDown(0)) {
            setSliderFromMouse(draggingSlider, mouseX, draggingSliderTrackX, draggingTrackW);
            if (draggingSlider == HudSetting.OSD_WIDTH || draggingSlider == HudSetting.OSD_HEIGHT) {
                HudSettingsModel.syncLyricViewportModuleValues();
            }
        }
        if (draggingPopoverScale && Mouse.isButtonDown(0)) {
            setPopoverScaleFromMouse(mouseX, popoverScaleTrackX, stage.selected());
        }
    }

    private void setSliderFromMouse(HudSetting setting, int mouseX, int trackX, int trackW) {
        float progress = clamp01((mouseX - trackX) / (float) trackW);
        setting.setValue(setting.getMin() + (setting.getMax() - setting.getMin()) * progress);
        settingsDirty = true;
    }

    private void updateColorPickerWhileDragging(int mouseX, int mouseY, int[] panel) {
        if (!Mouse.isButtonDown(0)) return;
        if (draggingSaturationBrightness || draggingHue) {
            applyPickerAt(mouseX, mouseY, panel);
        }
    }

    private void applyPickerAt(int mouseX, int mouseY, int[] panel) {
        int x = pickerX(panel);
        int y = pickerY(panel);
        int svX = x + 14;
        int svY = y + 34;
        int hueX = svX + SV_W + 12;
        if (draggingSaturationBrightness) {
            pickerSaturation = clamp01((mouseX - svX) / (float) (SV_W - 1));
            pickerBrightness = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
        }
        if (draggingHue) {
            pickerHue = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
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
        if (page.isDismissing()) return;
        if (resetConfirmationVisible) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
                resetConfirmationVisible = false;
            }
            return;
        }
        if (openChoice != null && keyCode == Keyboard.KEY_ESCAPE) {
            openChoice = null;
            return;
        }
        if (editing) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                editing = false;
                stage.clearSelection();
            }
            return;
        }
        if (stage.hasSelection() && keyCode == Keyboard.KEY_ESCAPE) {
            stage.clearSelection();
            return;
        }
        if (editingSliderValue != null && sliderValueInput != null && sliderValueInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER
                    || keyCode == Keyboard.KEY_ESCAPE) {
                commitSliderValueEdit();
                return;
            }
            if (sliderValueInput.textboxKeyTyped(typedChar, keyCode)) {
                Float parsed = parseSliderInput(sliderValueInput.getText());
                if (parsed != null) {
                    editingSliderValue.setValue(parsed);
                    settingsDirty = true;
                }
            }
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
            if (keyCode == Keyboard.KEY_ESCAPE) {
                hexColorInput.setFocused(false);
                return;
            }
            if (hexColorInput.textboxKeyTyped(typedChar, keyCode)) {
                applyHexColorInput();
            }
            return;
        }
        if (searchInput != null && searchInput.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                clearSearch();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                searchInput.setFocused(false);
                return;
            }
            if (searchInput.textboxKeyTyped(typedChar, keyCode)) {
                searchQuery = searchInput.getText();
                searchScroll = 0;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
            beginRetire();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void beginRetire() {
        if (page.isDismissing()) return;
        page.requestDismiss();
        list.requestDismiss();
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

    // ==================== 滑条数值直接输入 ====================

    private void beginSliderValueEdit(HudSetting setting) {
        if (editingSliderValue == setting && sliderValueInput != null
                && sliderValueInput.isFocused()) return;
        commitSliderValueEdit();
        ensureSliderValueInput();
        editingSliderValue = setting;
        sliderValueInput.setText(formatSliderInput(setting.getValue()));
        sliderValueInput.setFocused(true);
        sliderValueInput.setCursorPosition(sliderValueInput.getText().length());
    }

    private void commitSliderValueEdit() {
        if (editingSliderValue == null) return;
        HudSetting setting = editingSliderValue;
        editingSliderValue = null;
        if (sliderValueInput != null) sliderValueInput.setFocused(false);
        Float parsed = parseSliderInput(sliderValueInput == null ? null : sliderValueInput.getText());
        if (parsed == null) return;
        if (parsed != setting.getValue()) {
            setting.setValue(parsed);
            settingsDirty = true;
            HudConfig.save();
        }
    }

    private static String formatSliderInput(float value) {
        float rounded = Math.round(value * 100f) / 100f;
        if (rounded == Math.rint(rounded)) return String.valueOf((int) rounded);
        return String.valueOf(rounded);
    }

    private static Float parseSliderInput(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        if (normalized.isEmpty()) return null;
        try {
            if (normalized.endsWith("%")) {
                String bare = normalized.substring(0, normalized.length() - 1).trim();
                if (!bare.matches("-?[0-9]+(\\.[0-9]+)?")) return null;
                return Float.parseFloat(bare) / 100f;
            }
            if (!normalized.matches("-?[0-9]+(\\.[0-9]+)?")) return null;
            return Float.parseFloat(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== 恢复默认确认 ====================

    private void drawResetConfirmation(CFontRenderer fr, int mouseX, int mouseY) {
        Gui.drawRect(0, 0, width, height, fade(0x6D000000, dialogGate));
        int[] box = resetDialogBounds();
        drawSoftShadow(box[0], box[1], box[2], box[3], 12);
        drawRoundedRect(box[0], box[1], box[2], box[3], 12, MConfigTheme.FLOAT_BG);
        drawOutline(box[0], box[1], box[2], box[3], 12, 1, 0x4DCCCCCC);
        drawRoundedRect(box[0] + 16, box[1] + 18, 22, 22, 11, 0x33D96B53);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.WARNING,
                box[0] + 27, box[1] + 29, 0xFFE8836A);
        drawText(FontManager.pf12bold, "恢复默认设置？", box[0] + 46, box[1] + 20, MConfigTheme.TEXT_PRIMARY);
        drawText(fr, "歌词、灵动岛与封面的外观会恢复为默认值。", box[0] + 17, box[1] + 50,
                MConfigTheme.TEXT_SECONDARY);
        drawText(fr, "HUD 的位置与缩放不受影响。", box[0] + 17, box[1] + 64, MConfigTheme.WHITE_40);

        int[] cancel = resetDialogCancel(box);
        int[] confirm = resetDialogConfirm(box);
        float cancelHover = named("dialog.cancel",
                isInside(mouseX, mouseY, cancel[0], cancel[1], cancel[2], cancel[3]));
        float confirmHover = named("dialog.confirm",
                isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3]));
        drawRoundedRect(cancel[0], cancel[1], cancel[2], cancel[3], 9,
                mix(MConfigTheme.GRAY_500, MConfigTheme.GRAY_400, cancelHover));
        drawRoundedRect(confirm[0], confirm[1], confirm[2], confirm[3], 9,
                mix(MConfigTheme.DANGER, MConfigTheme.DANGER_HOVER, confirmHover));
        drawCenteredText(fr, "取消", cancel[0] + cancel[2] / 2.0, cancel[1] + 8, MConfigTheme.TEXT_PRIMARY);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.RESTORE,
                confirm[0] + 16, confirm[1] + confirm[3] / 2.0, 0xFFFFFFFF);
        drawText(FontManager.pf12bold, "恢复默认", confirm[0] + 27, confirm[1] + 8, 0xFFFFFFFF);
    }

    private int[] resetDialogBounds() {
        int w = Math.min(320, Math.max(244, width - 32));
        int h = 132;
        return new int[] { (width - w) / 2, Math.max(18, (height - h) / 2), w, h };
    }

    private int[] resetDialogCancel(int[] box) {
        return new int[] { box[0] + box[2] - 74 - 108 - 18, box[1] + box[3] - 34, 74, 23 };
    }

    private int[] resetDialogConfirm(int[] box) {
        return new int[] { box[0] + box[2] - 108 - 10, box[1] + box[3] - 34, 108, 23 };
    }

    private void handleResetConfirmationClick(int mouseX, int mouseY) {
        int[] box = resetDialogBounds();
        int[] confirm = resetDialogConfirm(box);
        if (isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3])) {
            HudConfig.resetLyricAppearance();
            HudConfig.resetDynamicIslandAppearance();
            HudConfig.resetCoverAppearance();
            HudConfig.resetPlayerAnimation();
            HudSettingsModel.syncAnimatedCoverModuleValue();
            try {
                MuoniumPlayerExtension.getInstance().musicLyrics.loadHudEditorSettings();
            } catch (Throwable ignored) {
                // 歌词组件还没就绪时无所谓：HudConfig 已经是新值，下次加载会跟上。
            }
            settingsDirty = false;
            HudConfig.save();
        }
        resetConfirmationVisible = false;
    }

    // ==================== HUD 舞台（编辑态） ====================

    private static int accentOf(HudOverlayStage.Kind kind) {
        return kind == HudOverlayStage.Kind.INFO ? MConfigTheme.INFO_ACCENT : MConfigTheme.LYRICS_ACCENT;
    }

    private static String titleOf(HudOverlayStage.Kind kind) {
        return kind == HudOverlayStage.Kind.INFO ? "歌曲信息" : "桌面歌词";
    }

    private int[] widgetRect(HudOverlayStage.Kind kind) {
        if (kind == HudOverlayStage.Kind.INFO) {
            int w = (int) (INFO_BASE_W * HudConfig.infoScale);
            int h = (int) (INFO_BASE_H * HudConfig.infoScale);
            return new int[] { (int) (HudConfig.infoX * (RenderSystem.getLayoutWidth() - w)),
                    (int) (HudConfig.infoY * (RenderSystem.getLayoutHeight() - h)), w, h };
        }
        int w = (int) (HudConfig.lyricWidth * HudConfig.lyricScale);
        int h = (int) (HudConfig.lyricHeight * HudConfig.lyricScale);
        return new int[] { (int) (HudConfig.lyricX * (RenderSystem.getLayoutWidth() - w)),
                (int) (HudConfig.lyricY * (RenderSystem.getLayoutHeight() - h)), w, h };
    }

    private int widgetPopoverH() {
        return stage.selected() == HudOverlayStage.Kind.INFO ? 118 : 150;
    }

    private int widgetPopoverX() {
        return widgetPopoverBounds()[0];
    }

    private int widgetPopoverY() {
        return widgetPopoverBounds()[1];
    }

    private int[] widgetPopoverBounds() {
        if (!stage.hasSelection()) return new int[] { 0, 0, WIDGET_POPOVER_W, widgetPopoverH() };
        return HudOverlayStage.popoverBounds(widgetRect(stage.selected()),
                WIDGET_POPOVER_W, widgetPopoverH(),
                (int) RenderSystem.getLayoutWidth(), (int) RenderSystem.getLayoutHeight());
    }

    /**
     * 编辑态叠加层（OneConfig HudGui 语言）：所有启用块画白实线框 + 名称；选中块半透明填充 +
     * 白框 + 8×8 白把手（悬停变 accent）+ 呼吸脉冲。几何全部来自舞台，这里只管像素。
     */
    private void drawStageOverlays(CFontRenderer fr, int[] infoRect, int[] lyricRect,
                                   int mouseX, int mouseY) {
        drawHudFrame(fr, "歌曲信息", infoRect,
                stage.selected() == HudOverlayStage.Kind.INFO, MConfigTheme.INFO_ACCENT, mouseX, mouseY);
        drawHudFrame(fr, "桌面歌词", lyricRect,
                stage.selected() == HudOverlayStage.Kind.LYRICS, MConfigTheme.LYRICS_ACCENT, mouseX, mouseY);

        if (!stage.hasSelection()) return;
        int[] r = widgetRect(stage.selected());
        int accent = accentOf(stage.selected());
        if (stage.selected() != pulsedSelection) {
            pulsedSelection = stage.selected();
            selectionPulseChannel.from = selectionPulseChannel.current = 1f;
            selectionPulseChannel.to = 0f;
            selectionPulseChannel.startNanos = System.nanoTime();
        }
        float pulse = motion(selectionPulseChannel, 0f, 900.0);
        if (pulse > 0.02f) {
            drawRoundedRect(r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, 6, fade(accent, 0.30f * pulse));
        }
        // 选中填充：OneConfig 是青色 60α 填充，这里用块的 accent 24α，身份信息保留。
        drawRoundedRect(r[0], r[1], r[2], r[3], 4, fade(accent, 0.24f));
        drawRectBorder(r[0], r[1], r[2], r[3], 1, 0xFFFFFFFF);
        for (int[] handle : stage.handleRects(r)) {
            boolean hovered = isInside(mouseX, mouseY, handle[0], handle[1], handle[2], handle[3]);
            int grip = 8;
            int hx = handle[0] + handle[2] / 2 - grip / 2;
            int hy = handle[1] + handle[3] / 2 - grip / 2;
            drawRoundedRect(hx - 1, hy - 1, grip + 2, grip + 2, 3, 0xE6151617);
            drawRoundedRect(hx, hy, grip, grip, 2, hovered ? accent : 0xFFFFFFFF);
        }

        if (stage.bubble != null) {
            int textW = fr.getStringWidth(stage.bubble);
            int chipW = textW + 18;
            int chipX = Math.max(4, Math.min((int) RenderSystem.getLayoutWidth() - chipW - 4,
                    r[0] + (r[2] - chipW) / 2));
            int chipY = Math.max(4, r[1] - 24);
            drawRoundedRect(chipX, chipY, chipW, 18, 9, MConfigTheme.FLOAT_BG);
            drawText(FontManager.pf12bold, stage.bubble, chipX + 9, chipY + 4, MConfigTheme.TEXT_PRIMARY);
        }

        drawWidgetPopover(fr, mouseX, mouseY);

        // 完成胶囊：编辑态的固定出口。
        int[] done = doneButtonBounds();
        float dhp = named("edit.done", isInside(mouseX, mouseY, done[0], done[1], done[2], done[3]));
        drawSoftShadow(done[0], done[1], done[2], done[3], 8);
        drawRoundedRect(done[0], done[1], done[2], done[3], 13,
                mix(MConfigTheme.FLOAT_BG, MConfigTheme.GRAY_600, dhp));
        drawOutline(done[0], done[1], done[2], done[3], 13, 1, 0x4DCCCCCC);
        drawCenteredText(FontManager.pf12bold, "完成 · Esc 返回设置",
                done[0] + done[2] / 2.0, done[1] + 7, MConfigTheme.TEXT_PRIMARY);
    }

    /** 单块 HUD 的编辑态框：未选中白 40% 实线 + 名称；选中由调用方补填充与把手。 */
    private void drawHudFrame(CFontRenderer fr, String title, int[] r, boolean selected,
                              int accent, int mouseX, int mouseY) {
        drawRectBorder(r[0], r[1], r[2], r[3], 1, selected ? 0xFFFFFFFF : 0x66FFFFFF);
        int labelY = Math.max(14, r[1] - 6);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.MOVE, r[0] + 12, labelY + 4,
                selected ? accent : MConfigTheme.WHITE_60);
        drawText(fr, title, r[0] + 21, labelY, selected ? MConfigTheme.TEXT_PRIMARY : MConfigTheme.WHITE_60);
    }

    private void drawWidgetPopover(CFontRenderer fr, int mouseX, int mouseY) {
        if (!stage.hasSelection() && popGate <= 0.01f) return;
        int[] pop = widgetPopoverBounds();
        HudOverlayStage.Kind kind = stage.selected();
        int accent = accentOf(kind);
        boolean lyrics = kind == HudOverlayStage.Kind.LYRICS;

        drawSoftShadow(pop[0], pop[1], pop[2], pop[3], 12);
        drawRoundedRect(pop[0], pop[1], pop[2], pop[3], 12, MConfigTheme.FLOAT_BG);
        drawOutline(pop[0], pop[1], pop[2], pop[3], 12, 1, 0x4DCCCCCC);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.RULER, pop[0] + 16, pop[1] + 15, accent);
        drawText(FontManager.pf12bold, titleOf(kind) + " · 快捷调整", pop[0] + 26, pop[1] + 11,
                MConfigTheme.TEXT_PRIMARY);

        int pad = 14;
        int right = pop[0] + pop[2] - pad;
        int trackW = 120;
        int trackX = right - VALUE_W - 10 - trackW;
        int y = pop[1] + 32;
        if (lyrics) {
            drawPopoverSlider(fr, HudSetting.OSD_WIDTH, trackX, right, y, accent, trackW, mouseX, mouseY);
            y += 30;
            drawPopoverSlider(fr, HudSetting.OSD_HEIGHT, trackX, right, y, accent, trackW, mouseX, mouseY);
            y += 30;
        }
        drawText(fr, "整体缩放", pop[0] + pad, y + 5, MConfigTheme.TEXT_SECONDARY);
        String scaleText = String.format("%.2f×",
                kind == HudOverlayStage.Kind.INFO ? HudConfig.infoScale : HudConfig.lyricScale);
        drawText(fr, scaleText, right - fr.getStringWidth(scaleText), y + 5, MConfigTheme.TEXT_PRIMARY);
        int sy = y + 14;
        drawTrackAndKnobStatic(scaleTrackProgress(kind), trackX, trackW, sy, accent);
        y += 34;

        int btnW = (pop[2] - pad * 2 - 8) / 2;
        int[] center = { pop[0] + pad, y, btnW, 22 };
        int[] reset = { pop[0] + pad + btnW + 8, y, btnW, 22 };
        drawGhostButton("wpop.center", HudSettingsIcons.MOVE, "水平居中", center, mouseX, mouseY,
                MConfigTheme.TEXT_PRIMARY);
        drawGhostButton("wpop.reset", HudSettingsIcons.RESTORE, "复位", reset, mouseX, mouseY,
                MConfigTheme.TEXT_SECONDARY);
    }

    private float scaleTrackProgress(HudOverlayStage.Kind kind) {
        float value = kind == HudOverlayStage.Kind.INFO ? HudConfig.infoScale : HudConfig.lyricScale;
        return clamp01((value - HudConfig.SCALE_MIN) / (HudConfig.SCALE_MAX - HudConfig.SCALE_MIN));
    }

    /** 快捷小窗里的一条滑条（无输入态，纯展示 + 拖动）。 */
    private void drawPopoverSlider(CFontRenderer fr, HudSetting setting, int trackX, int right,
                                   int y, int accent, int trackW, int mouseX, int mouseY) {
        int labelW = fr.getStringWidth(setting.getLabel());
        drawText(fr, setting.getLabel(), trackX - 10 - labelW, y, MConfigTheme.TEXT_SECONDARY);
        String value = HudSettingsModel.formatValue(setting, setting.getValue());
        drawText(fr, value, right - fr.getStringWidth(value), y, MConfigTheme.TEXT_PRIMARY);
        int sy = y + 14;
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        boolean dragging = draggingSlider == setting;
        drawTrackAndKnobStatic(progress, trackX, trackW, sy, accent);
    }

    /** 无状态版本的轨道 + 把手（快捷小窗的整体缩放条）。 */
    private void drawTrackAndKnobStatic(float progress, int trackX, int trackW, int centerY,
                                        int accent) {
        double trackY = centerY - TRACK_H / 2.0;
        double fillW = trackW * progress;
        drawRoundedRect(trackX, trackY, trackW, TRACK_H, TRACK_H * 0.5, MConfigTheme.GRAY_300);
        if (fillW > 1) {
            drawRoundedRect(trackX, trackY - 1, fillW, FILL_H, FILL_H * 0.5, accent);
        }
        double knobX = trackX + fillW;
        drawRoundedRect(knobX - KNOB_R, centerY - KNOB_R, KNOB_R * 2.0, KNOB_R * 2.0, KNOB_R, 0xFFFFFFFF);
    }

    private void handleWidgetPopoverClick(int mouseX, int mouseY, int[] pop) {
        HudOverlayStage.Kind kind = stage.selected();
        boolean lyrics = kind == HudOverlayStage.Kind.LYRICS;
        int pad = 14;
        int right = pop[0] + pop[2] - pad;
        int trackW = 120;
        int trackX = right - VALUE_W - 10 - trackW;
        int y = pop[1] + 32;
        if (lyrics) {
            if (tryBeginPopoverSlider(HudSetting.OSD_WIDTH, mouseX, mouseY, y, trackX, trackW)) return;
            y += 30;
            if (tryBeginPopoverSlider(HudSetting.OSD_HEIGHT, mouseX, mouseY, y, trackX, trackW)) return;
            y += 30;
        }
        int sy = y + 14;
        if (mouseX >= trackX - 8 && mouseX <= trackX + trackW + 8
                && mouseY >= sy - 8 && mouseY <= sy + 18) {
            draggingPopoverScale = true;
            popoverScaleTrackX = trackX;
            setPopoverScaleFromMouse(mouseX, trackX, kind);
            return;
        }
        y += 34;
        int btnW = (pop[2] - pad * 2 - 8) / 2;
        int[] center = { pop[0] + pad, y, btnW, 22 };
        int[] reset = { pop[0] + pad + btnW + 8, y, btnW, 22 };
        if (isInside(mouseX, mouseY, center[0], center[1], center[2], center[3])) {
            if (kind == HudOverlayStage.Kind.INFO) {
                HudConfig.infoX = 0.5f;
            } else {
                HudConfig.lyricX = 0.5f;
            }
            HudConfig.save();
            return;
        }
        if (isInside(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3])) {
            if (kind == HudOverlayStage.Kind.INFO) {
                HudConfig.infoX = 0.02f;
                HudConfig.infoY = 0.02f;
                HudConfig.infoScale = 1.0f;
            } else {
                HudConfig.lyricX = 0.5f;
                HudConfig.lyricY = 0.85f;
                HudConfig.lyricScale = 1.0f;
                HudConfig.lyricWidth = 450.0f;
                HudConfig.lyricHeight = 120.0f;
                HudSettingsModel.syncLyricViewportModuleValues();
            }
            HudConfig.save();
            return;
        }
    }

    private boolean tryBeginPopoverSlider(HudSetting setting, int mouseX, int mouseY, int y,
                                          int trackX, int trackW) {
        int sy = y + 14;
        if (mouseX >= trackX - 8 && mouseX <= trackX + trackW + 8
                && mouseY >= sy - 8 && mouseY <= sy + 18) {
            draggingSlider = setting;
            draggingSliderTrackX = trackX;
            draggingTrackW = trackW;
            setSliderFromMouse(setting, mouseX, trackX, trackW);
            return true;
        }
        return false;
    }

    private void setPopoverScaleFromMouse(int mouseX, int trackX, HudOverlayStage.Kind kind) {
        float progress = clamp01((mouseX - trackX) / 120f);
        float value = HudConfig.SCALE_MIN + (HudConfig.SCALE_MAX - HudConfig.SCALE_MIN) * progress;
        if (kind == HudOverlayStage.Kind.INFO) {
            HudConfig.infoScale = HudConfig.clampScale(value);
        } else {
            HudConfig.lyricScale = HudConfig.clampScale(value);
        }
        settingsDirty = true;
    }

    // ==================== HUD 预览（与旧版同轴同公式） ====================

    private boolean hasLiveInfoPreview() {
        return CloudMusic.currentlyPlaying != null && CloudMusic.hasLiveTrack();
    }

    private boolean hasLiveLyricsPreview() {
        return CloudMusic.hasLiveTrack() && !CloudMusic.lyrics.isEmpty();
    }

    private void renderLiveInfoPreview(MusicInfoWidget info) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            int[] rect = widgetRect(HudOverlayStage.Kind.INFO);
            info.renderEditorPreview(rect[0], rect[1], HudConfig.infoScale);
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void renderLiveLyricsPreview(MusicLyricsWidget lyrics, int x, int y) {
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

    private void drawInfoPlaceholder(CFontRenderer fr, int[] rect, float alpha) {
        int x = rect[0];
        int y = rect[1];
        int w = rect[2];
        int h = rect[3];
        float outer = pushAlpha(alpha);
        try {
            drawSoftShadow(x, y, w, h, 10);
            drawRoundedRect(x, y, w, h, 10, 0xC5151617);
            drawRoundedRect(x + 12, y + 17, 26, 26, 7, 0x55313438);
            drawIcon(FontManager.settingsIcon32, HudSettingsIcons.NOTE, x + 25, y + 30, 0xFFD5DEEC);
            drawText(FontManager.pf12bold, "歌曲信息", x + 49, y + 16, MConfigTheme.WHITE_90);
            drawText(fr, "播放歌曲后实时展示", x + 49, y + 32, MConfigTheme.TEXT_SECONDARY);
        } finally {
            layerAlpha = outer;
        }
    }

    private void drawLyricsPlaceholder(CFontRenderer fr, int[] rect, float alpha) {
        int x = rect[0];
        int y = rect[1];
        int w = rect[2];
        int h = rect[3];
        float outer = pushAlpha(alpha);
        try {
            drawSoftShadow(x, y, w, h, 12);
            drawRoundedRect(x, y, w, h, 12, 0x8C1B1524);
            int center = x + w / 2;
            drawCenteredText(FontManager.pf12bold, "桌面歌词", center, y + Math.max(8, h / 2 - 13),
                    MConfigTheme.WHITE_90);
            drawCenteredText(fr, "平滑动画与逐字染色预览", center, y + Math.max(23, h / 2 + 3),
                    MConfigTheme.TEXT_SECONDARY);
        } finally {
            layerAlpha = outer;
        }
    }

    // ==================== 搜索 / 跳转 / 闪光 ====================

    private void jumpToEntry(HudSettingsModel.Entry target, int contentX, int listY, int contentW) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int targetCategory = clampCategory();
        for (int c = 0; c < categories.length; c++) {
            for (HudSettingsModel.Group group : categories[c].groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry == target) {
                        targetCategory = c;
                        break;
                    }
                }
            }
        }
        clearSearch();
        categoryIndex = targetCategory;
        float savedScroll = displayScroll;
        displayScroll = 0f;
        layoutRows(contentX, listY, contentW, categories[targetCategory]);
        displayScroll = savedScroll;
        for (MRow row : rows) {
            if ((row.left != null && row.left.entry == target)
                    || (row.right != null && row.right.entry == target)) {
                scrollByCategory[targetCategory] = Math.max(0, row.y - listY - 24);
                displayScroll = scrollByCategory[targetCategory];
                break;
            }
        }
        flashEntry = target;
        flashDeadline = System.currentTimeMillis() + FLASH_MS;
    }

    private float flashPulse() {
        long now = System.currentTimeMillis();
        if (now >= flashDeadline) {
            flashEntry = null;
            return 0f;
        }
        return clamp01((flashDeadline - now) / (float) FLASH_MS);
    }

    private void clearSearch() {
        if (searchInput != null) {
            searchInput.setText("");
            searchInput.setFocused(false);
        }
        searchQuery = "";
        searchScroll = 0;
    }

    private int clampCategory() {
        int count = HudSettingsModel.categories().length;
        return Math.max(0, Math.min(count - 1, categoryIndex));
    }

    private int scrollKey() {
        return searchQuery.trim().isEmpty() ? clampCategory() : -1;
    }

    private int scroll() {
        if (!searchQuery.trim().isEmpty()) return searchScroll;
        if (scrollByCategory == null) return 0;
        return scrollByCategory[clampCategory()];
    }

    private void setScroll(int value, int maxScroll) {
        int clamped = Math.max(0, Math.min(maxScroll, value));
        if (!searchQuery.trim().isEmpty()) {
            searchScroll = clamped;
            return;
        }
        if (scrollByCategory != null) scrollByCategory[clampCategory()] = clamped;
    }

    private int navMaxScroll(int navH, int count, int rowH) {
        return Math.max(0, count * rowH - navH);
    }

    private int clampNavScroll(int value) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int listH = panelHeightNav();
        // 行高跟绘制/命中共用同一个函数：矮窗口下行被压缩，滚动上限也随之缩小到 0。
        int rowH = navRowHeight(listH, categories.length);
        return Math.max(0, Math.min(navMaxScroll(listH, categories.length, rowH), value));
    }

    private int panelHeightNav() {
        int[] panel = panelBounds();
        return navListHeight(panel[3] - headerHeight(panel[3]) - FOOTER_H);
    }

    private void ensureSearchInput() {
        if (searchInput == null) {
            searchInput = new GuiTextField(1, fontRendererObj, 0, 0, 100, 12);
            searchInput.setEnableBackgroundDrawing(false);
            searchInput.setMaxStringLength(28);
        }
    }

    private void ensureHexColorInput() {
        if (hexColorInput == null) {
            hexColorInput = new GuiTextField(0, fontRendererObj, 0, 0, HEX_INPUT_W - 12, HEX_INPUT_H - 8);
            hexColorInput.setEnableBackgroundDrawing(false);
            hexColorInput.setMaxStringLength(7);
        }
    }

    private void ensureSliderValueInput() {
        if (sliderValueInput == null) {
            sliderValueInput = new GuiTextField(2, fontRendererObj, 0, 0, 64, 12);
            sliderValueInput.setEnableBackgroundDrawing(false);
            sliderValueInput.setMaxStringLength(9);
        }
    }

    // ==================== 动画工具 ====================

    private static final class Channel {
        float from;
        float to;
        float current;
        long startNanos;
    }

    private float motion(Channel chan, float target, double durationMs,
                         TransitionCurveCache.CachedBezier curve) {
        long now = System.nanoTime();
        double span = Math.max(1.0, durationMs / SheetClock.speed());
        double elapsed = (now - chan.startNanos) / 1_000_000.0;
        chan.current = elapsed >= span ? chan.to
                : (float) (chan.from + (chan.to - chan.from) * curve.at(elapsed / span));
        if (target != chan.to) {
            chan.from = chan.current;
            chan.to = target;
            chan.startNanos = now;
        }
        return chan.current;
    }

    private float motion(Channel chan, float target, double durationMs) {
        return motion(chan, target, durationMs, TransitionCurveCache.MOTION);
    }

    private float motion(String key, float target, double durationMs,
                         TransitionCurveCache.CachedBezier curve) {
        Channel chan = channels.get(key);
        if (chan == null) {
            chan = new Channel();
            chan.from = chan.to = chan.current = target;
            chan.startNanos = System.nanoTime();
            channels.put(key, chan);
            return target;
        }
        return motion(chan, target, durationMs, curve);
    }

    private float motion(String key, float target, double durationMs) {
        return motion(key, target, durationMs, TransitionCurveCache.MOTION);
    }

    private float named(String key, boolean active) {
        return motion(key, active ? 1f : 0f, HOVER_MS);
    }

    private float pushAlpha(float gate) {
        float outer = layerAlpha;
        layerAlpha = outer * clamp01(gate);
        return outer;
    }

    private float gate(double first, double travel) {
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, first,
                Math.min(first + travel, 1.0)));
    }

    private float navGate(int index) {
        double first = NAV_FIRST + Math.min(index, NAV_STAGGER_LIMIT) * NAV_STEP;
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, first,
                Math.min(first + NAV_TRAVEL, 1.0)));
    }

    private float revealOf(int slot) {
        double first = ROW_FIRST + Math.min(slot, ROW_STAGGER_LIMIT) * ROW_STEP;
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(listFade, first,
                Math.min(first + ROW_TRAVEL, 1.0)));
    }

    private float slideX(float gateValue) {
        return listDirection * (1f - gateValue) * CONTENT_SLIDE;
    }

    private float slideY(float gateValue) {
        return (1f - gateValue) * CONTENT_DROP;
    }

    /** 说明文字贪心折两行；第二行放不下以省略号收尾（切割下标必须来自原文扫描，见旧版注释）。 */
    private static String[] wrapToTwoLines(CFontRenderer fr, String text, double width) {
        int fitChars = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (fr.getStringWidth(text.substring(0, i)) > width) break;
            fitChars = i;
        }
        if (fitChars >= text.length()) return new String[] { text, null };
        int lastSpace = -1;
        for (int i = 0; i <= fitChars; i++) {
            if (text.charAt(i) == ' ') lastSpace = i;
        }
        int cut = lastSpace > 0 ? lastSpace : Math.max(1, fitChars);
        String first = text.substring(0, cut).trim();
        String rest = text.substring(cut).trim();
        if (rest.isEmpty()) return new String[] { fr.trim(first, width), null };
        return new String[] { first, fr.trim(rest, width) };
    }

    // ==================== 绘制原语 ====================

    private float[] progressOf(HudSettingsModel.Entry entry) {
        float[] state = rowProgress.get(entry);
        if (state == null) {
            state = new float[] { 0f, 0f, 0f };
            rowProgress.put(entry, state);
        }
        return state;
    }

    private Channel[] channelsOf(HudSettingsModel.Entry entry) {
        Channel[] chans = rowChannels.get(entry);
        if (chans == null) {
            chans = new Channel[] { new Channel(), new Channel(), new Channel() };
            chans[1].from = chans[1].to = chans[1].current = entry.isOn() ? 1f : 0f;
            rowChannels.put(entry, chans);
        }
        return chans;
    }

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

    /** 1px 描边（OneConfig drawHollowRoundRect 的近似：四条细矩形拼一圈）。 */
    private void drawOutline(double x, double y, double w, double h, double radius, double t, int color) {
        if (w <= 0 || h <= 0) return;
        drawRoundedRect(x, y, w, t, t, color);
        drawRoundedRect(x, y + h - t, w, t, t, color);
        drawRoundedRect(x, y, t, h, t, color);
        drawRoundedRect(x + w - t, y, t, h, t, color);
    }

    /**
     * 按真实字宽折行，最多 {@code maxLines} 行，末行超出部分以省略号收尾。
     *
     * <p>与 {@link #wrapToTwoLines} 的区别：那个是先按宽度找首行断点、再把剩余整段交给
     * {@code trim} —— 中文没有空格，第二行会原样保留超宽内容，直接压到右侧控件上。
     * 这里每一行都从原文续着量，保证任何一行都不超过给定宽度。</p>
     */
    private static String[] wrapByWidth(CFontRenderer fr, String text, double width, int maxLines) {
        List<String> lines = wrapByWidthList(fr, text, width);
        if (lines.size() <= maxLines) return lines.toArray(new String[0]);
        List<String> capped = new ArrayList<String>(lines.subList(0, maxLines));
        String last = capped.get(maxLines - 1);
        if (last.length() > 1) last = last.substring(0, last.length() - 1).trim();
        capped.set(maxLines - 1, last + "…");
        return capped.toArray(new String[0]);
    }

    /** 同上但不截断行数；tooltip 要的就是全文。 */
    private static List<String> wrapByWidthList(CFontRenderer fr, String text, double width) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty() || width <= 8) return lines;
        int start = 0;
        while (start < text.length()) {
            int fit = start;
            for (int i = start + 1; i <= text.length(); i++) {
                if (fr.getStringWidth(text.substring(start, i)) > width) break;
                fit = i;
            }
            if (fit <= start) fit = Math.min(text.length(), start + 1);
            String line = text.substring(start, fit).trim();
            start = fit;
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** 这段说明在该宽度下需要几行；用来判断卡片里的两行是否已经把全文放完。 */
    private static int countWrappedLines(CFontRenderer fr, String text, double width) {
        return wrapByWidthList(fr, text, width).size();
    }

    /** 实线边框（整数坐标版，编辑态框用）。 */
    private void drawRectBorder(int x, int y, int w, int h, int t, int color) {
        Gui.drawRect(x, y, x + w, y + t, color);
        Gui.drawRect(x, y + h - t, x + w, y + h, color);
        Gui.drawRect(x, y, x + t, y + h, color);
        Gui.drawRect(x + w - t, y, x + w, y + h, color);
    }

    private void drawSoftShadow(int x, int y, int w, int h, int radius) {
        drawRoundedRect(x + 1, y + 2, w, h, radius, 0x30000000);
        drawRoundedRect(x + 2, y + 3, w, h, radius, 0x18000000);
    }

    private void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font == null || text == null) return;
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
            SkijaClip.scissorBegin(0, 0, (int) RenderSystem.getLayoutWidth(),
                    (int) RenderSystem.getLayoutHeight());
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

    private static int fade(int color, float alpha) {
        return MConfigTheme.fade(color, alpha);
    }

    private static int mix(int from, int to, float t) {
        return MConfigTheme.mix(from, to, t);
    }

    private static float clamp01(float value) {
        return MConfigTheme.clamp01(value);
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String pct(float x, float y, float scale) {
        return (int) (x * 100) + "%, " + (int) (y * 100) + "%, " + (int) (scale * 100) + "%";
    }
}
