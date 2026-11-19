package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.input.TouchGestures;
import com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings;
import com.muoniumplayer.core.ncm.music.ambient.AmbientMusicController;
import com.muoniumplayer.core.ncm.music.ambient.AmbientSettings;
import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.core.screens.ncm.NcmSheet;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.settings.TouchSettings;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.viper.ViperDynamicEqModel;
import com.muoniumplayer.core.viper.ViperEqPresets;
import com.muoniumplayer.core.viper.ViperEqualizerModel;
import com.muoniumplayer.core.viper.ViperFieldKind;
import com.muoniumplayer.core.viper.ViperPresetEditor;
import com.muoniumplayer.core.viper.ViperRuntime;
import com.muoniumplayer.core.viper.ViperWorkbenchCatalog;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import static com.muoniumplayer.core.screens.ncm.effects.EffectsLayout.Box;
import static com.muoniumplayer.core.screens.ncm.effects.EffectsPaint.*;

/**
 * 独立音效工作台。NcmSheet 仅提供整层进退场，不复用其他菜单的玻璃与页脚。
 *
 * <p>渲染线程拥有工作副本与交互，串行后台队列拥有磁盘操作。拖动只改内存，松手、
 * 换页、resize、交互权交接和关屏统一结算；提交深拷贝，完成回调不覆盖之后的编辑。
 * 宿主结束后只允许已交接的保存完成，导入选择器和 UI 回调均失效。</p>
 */
public class EffectsWorkbench extends NcmSheet {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "MuoniumPlayer-EffectsIO");
        thread.setDaemon(true);
        return thread;
    });
    private static final int HISTORY_LIMIT = 32;

    private enum Page {
        PRESETS("ViPER 预设", "预设", Icon.PRESETS), EQ("图形均衡器", "EQ", Icon.EQ),
        EFFECTS("高级效果器", "效果器", Icon.EFFECTS), ACOUSTICS("场景声学", "声学", Icon.ROOM),
        AMBIENT("环境选曲", "选曲", Icon.AMBIENT);
        final String title, compact;
        final Icon icon;
        Page(String title, String compact, Icon icon) { this.title = title; this.compact = compact; this.icon = icon; }
    }

    private record View(Page page, ViperPresetEditor editor, int group, ViperWorkbenchCatalog.Category category,
                        String query, boolean advanced, boolean faders, double scroll,
                        int eqBand, int dynamicBand, int arrayBand) { }
    private record Departure(View view, EffectsMotion opacity, EffectsMotion.Spring offset) { }
    private record Popup(List<MenuItem> items, Box anchor, double scroll,
                         EffectsMotion opacity, EffectsMotion.Spring offset) { }
    private record Dialog(String title, String body, String value, String error, boolean form, boolean naming,
                          double scroll, EffectsMotion opacity, EffectsMotion.Spring offset) { }
    private record Loaded(ViperPresetEditor editor, List<String> presets, List<String> kernels, List<String> ddc,
                          boolean enabled, String active, boolean acoustics, boolean submersion, boolean enclosure,
                          boolean rainfall, int strength, boolean ambient, int stableMs) { }
    private record Hit(String id, Box box, Box clip, Runnable click, DoubleConsumer drag, boolean vertical,
                       boolean graph, DoubleConsumer graphX) {
        boolean contains(double x, double y) { return box.contains(x, y) && clip.contains(x, y); }
    }
    private record MenuItem(String label, Icon icon, boolean danger, Runnable action) { }
    private record Numeric(String title, String unit, double min, double max, boolean integer,
                           DoubleSupplier value, DoubleConsumer write) { }
    private interface Job<T> { T run() throws Exception; }

    private EffectsPaint painter;
    private EffectsPalette palette = EffectsPalette.fallback();
    private int BACKGROUND, SURFACE, CONTAINER, HIGH, OUTLINE, TEXT, MUTED, PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY, GREEN, AMBER, ERROR;
    private EffectsLayout layout;

    private enum NoticeTone { INFO, SUCCESS, WARNING, ERROR }

    public EffectsWorkbench() { applyPalette(palette); }

    private void applyPalette(EffectsPalette next) {
        palette = next;
        BACKGROUND = next.background();
        SURFACE = next.surface();
        CONTAINER = next.container();
        HIGH = next.high();
        OUTLINE = next.outline();
        TEXT = next.text();
        MUTED = next.muted();
        PRIMARY = next.primary();
        PRIMARY_CONTAINER = next.primaryContainer();
        ON_PRIMARY = next.onPrimary();
        GREEN = next.success();
        AMBER = next.warning();
        ERROR = next.error();
        if (painter != null) painter.setPalette(next);
    }

    private void refreshPalette() {
        applyPalette(EffectsPalette.fromColors(
                NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER),
                NCMScreen.getColor(NCMScreen.ColorType.BORDER),
                NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER),
                NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND)));
    }
    private EffectsLayout.Viewport viewport = new EffectsLayout.Viewport(0, 0, WindowAdaptiveScale.DESIGN_PER_LAYOUT);
    private Page current = Page.PRESETS;
    private ViperPresetEditor editor;
    private int selectedGroup = -1;
    private ViperWorkbenchCatalog.Category category = ViperWorkbenchCatalog.Category.TONE;
    private int selectedBand, dynamicBand, arrayBand;
    private boolean advanced;
    private boolean faders = true;
    private final TextField search = new TextField(0, 0, 0, 1, 1);
    private final TextField input = new TextField(1, 0, 0, 1, 1);
    private String query = "";
    private Box searchBox = new Box(0, 0, 0, 0), inputBox = new Box(0, 0, 0, 0);
    private String dialogTitle, dialogBody;
    private Runnable dialogAction;
    private Numeric numeric;
    private boolean naming;
    private String inputError = "";
    private List<MenuItem> menu;
    private Box menuAnchor;
    private final EffectsMotion menuProgress = new EffectsMotion(0);
    private final EffectsMotion.Spring menuOffset = new EffectsMotion.Spring(0, EffectsMotion.Scheme.FAST_SPATIAL);
    private Popup retiringMenu;
    private final EffectsMotion dialogProgress = new EffectsMotion(1);
    private final EffectsMotion.Spring dialogOffset = new EffectsMotion.Spring(0, EffectsMotion.Scheme.DEFAULT_SPATIAL);
    private Dialog retiringDialog;

    private List<String> presets = List.of(), kernels = List.of(), ddc = List.of();
    private boolean enabled;
    private String active;
    private boolean acoustics, submersion, enclosure, rainfall, ambient;
    private int strength = 100, stableMs = 15_000;
    private String notice = "正在读取音效配置…";
    private NoticeTone noticeTone = NoticeTone.INFO;
    private String saveError;
    private boolean loading = true, busy, importing;
    private volatile boolean hostAlive = true;
    private boolean ownsInput = true;
    private long operation, editorEpoch, editRevision;
    private long queuedRevision = -1;
    private int pendingSaves;
    private final Deque<ViperPresetEditor> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, EffectsMotion> hover = new HashMap<>(), selection = new HashMap<>();
    private final Map<String, EffectsMotion.Spring> shape = new HashMap<>(), press = new HashMap<>();
    private final EffectsMotion.Spring navigationIndicator = new EffectsMotion.Spring(0, EffectsMotion.Scheme.DEFAULT_SPATIAL);
    private final EffectsMotion.Spring pageOffset = new EffectsMotion.Spring(0, EffectsMotion.Scheme.DEFAULT_SPATIAL);
    private final EffectsMotion pageOpacity = new EffectsMotion(1);
    private final List<Departure> departing = new ArrayList<>();
    private final EffectsMotion.Scroll scroll = new EffectsMotion.Scroll(), menuScroll = new EffectsMotion.Scroll(), dialogScroll = new EffectsMotion.Scroll();
    private Box dialogViewport = new Box(0, 0, 0, 0);
    private Box pageClip = new Box(0, 0, 0, 0);
    private long now, previousFrame;
    private boolean reducedMotion;
    private boolean paintingLive = true;
    private Hit pressedTarget;
    private boolean wasDown;
    private long suppressClickUntil;
    private Hit drag;
    private ViperPresetEditor dragBaseline;
    private int dragStrength, dragStable;
    private boolean dragChanged, touchingScroll, touchGraphOwned;
    private double mouseX, mouseY;

    @Override
    public void onInit() {
        search.setFontRenderer(EffectsTypography.body());
        search.setMaxStringLength(80);
        search.setCanLoseFocus(false);
        search.setPlaceholder("搜索预设");
        search.setCallback(text -> { query = text; scroll.reset(); });
        input.setFontRenderer(EffectsTypography.body());
        input.setMaxStringLength(80);
        input.setCanLoseFocus(false);
        requestLoad();
    }

    /** 由宿主明确交接，不以“有任意模态”判断，否则工作台自己也会永远被禁用。 */
    public void setInteractionOwned(boolean owned) {
        if (ownsInput && !owned) {
            finishDrag(true);
            pressedTarget = null;
            search.setFocused(false);
            input.setFocused(false);
            wasDown = Mouse.isButtonDown(0);
        }
        ownsInput = owned;
    }

    @Override
    public void onRender(double absoluteX, double absoluteY) {
        super.onRender(absoluteX, absoluteY);
        now = System.nanoTime();
        refreshPalette();
        EffectsLayout.Viewport next = new EffectsLayout.Viewport(getWidth(), getHeight(), WindowAdaptiveScale.DESIGN_PER_LAYOUT);
        if (!next.equals(viewport)) {
            finishDrag(true);
            pressedTarget = null;
            viewport = next;
            closeMenu();
            retiringMenu = null;
        }
        mouseX = viewport.toDesign(absoluteX - getX());
        mouseY = viewport.toDesign(absoluteY - getY());
        if (previousFrame != 0 && now - previousFrame > 250_000_000L) finishDrag(true);
        previousFrame = now;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || !hostAlive) return;
        if (painter == null) painter = new EffectsPaint();
        painter.setPalette(palette);
        layout = EffectsLayout.calculate(viewport.width(), viewport.height(),
                reducedMotion ? 0 : (1 - EffectsMotion.spatial(pageFade, EffectsMotion.Scheme.DEFAULT_SPATIAL)) * 22);
        samplePress(Mouse.isButtonDown(0));
        hits.clear();
        drawInHost(canvas, getAlpha() * presentation);
        advancePointer();
    }

    /** 继承宿主的窗口/GUI 缩放，只在本页将设计轴折回播放器布局轴。 */
    private void drawInHost(Canvas canvas, double opacity) {
        int saved = canvas.save();
        canvas.translate((float) getX(), (float) getY());
        canvas.scale((float) viewport.renderScale(), (float) viewport.renderScale());
        try { draw(canvas, opacity); }
        finally { canvas.restoreToCount(saved); }
    }

    private void draw(Canvas canvas, double opacity) {
        painter.rect(canvas, new Box(0, 0, viewport.width(), viewport.height()), alpha(0xFF000000, opacity * .48));
        double entrance = reducedMotion ? 1 : EffectsMotion.spatial(pageFade, EffectsMotion.Scheme.DEFAULT_SPATIAL);
        double radius = 28 + Math.max(0, 1 - entrance) * 12;
        painter.round(canvas, layout.surface.offset(0, 7).inset(2), radius, alpha(0xFF000000, opacity * .12));
        int saved = canvas.saveLayerAlpha(EffectsPaint.rect(layout.surface), (int) Math.round(opacity * 255));
        try {
            painter.round(canvas, layout.surface, radius, BACKGROUND);
            painter.outline(canvas, layout.surface.inset(.5), radius - .5, 1, alpha(OUTLINE, .48));
            canvas.clipRRect(io.github.humbleui.types.RRect.makeXYWH((float) layout.surface.x(),
                    (float) layout.surface.y(), (float) layout.surface.width(), (float) layout.surface.height(), (float) radius), true);
            drawShell(canvas);
            if (!layout.usable) {
                painter.centered(canvas, EffectsTypography.section(), "请放大播放器后调音", layout.surface, TEXT);
                return;
            }
            drawToolbar(canvas);
            departing.removeIf(layer -> layer.opacity.settled(now));
            for (Departure layer : departing) drawPageLayer(canvas, layer.view,
                    layer.opacity.value(now), layer.offset.value(now), false);
            double incoming = Math.max(0, Math.min(1, pageOpacity.value(now)));
            drawPageLayer(canvas, view(), incoming, pageOffset.value(now), incoming > .9);
            drawStatus(canvas);
            if (retiringMenu != null) {
                if (retiringMenu.opacity.settled(now)) retiringMenu = null;
                else drawPopup(canvas, retiringMenu.items, retiringMenu.anchor, retiringMenu.scroll,
                        retiringMenu.opacity.value(now), retiringMenu.offset.value(now), false);
            }
            if (menu != null) drawMenu(canvas);
            if (retiringDialog != null) {
                if (retiringDialog.opacity.settled(now)) retiringDialog = null;
                else drawDialogContent(canvas, retiringDialog, false);
            }
            if (dialogTitle != null) drawDialog(canvas);
        } finally { canvas.restoreToCount(saved); }
    }

    private void drawPageLayer(Canvas canvas, View state, double opacity, double offset, boolean interactive) {
        if (opacity <= .003) return;
        int saved = canvas.saveLayerAlpha(EffectsPaint.rect(layout.viewport), (int) Math.round(opacity * 255));
        boolean wasLive = paintingLive;
        paintingLive = interactive;
        canvas.clipRect(EffectsPaint.rect(layout.viewport), true);
        canvas.translate((float) offset, 0);
        try {
            pageClip = layout.viewport.offset(-offset, 0);
            drawPage(canvas, state, interactive);
        } finally {
            pageClip = layout.viewport;
            paintingLive = wasLive;
            canvas.restoreToCount(saved);
        }
    }

    private View view() {
        return new View(current, editor, selectedGroup, category, query, advanced, faders, scroll.value(now),
                selectedBand, dynamicBand, arrayBand);
    }

    private void navigate(Page page, int group) {
        if (current == page && selectedGroup == group) return;
        finishDrag(true);
        closeMenu();
        search.setFocused(false);
        int direction = page.ordinal() < current.ordinal() || group < 0 && selectedGroup >= 0 ? -1 : 1;
        View before = view();
        EffectsMotion fading = pageOpacity.copy();
        EffectsMotion.Spring sliding = pageOffset.copy();
        fading.retarget(0, now, reducedMotion ? 90 : motionMs(160));
        sliding.retarget(reducedMotion ? 0 : -18 * direction, now, spatialTime());
        departing.add(new Departure(new View(before.page, before.editor == null ? null : before.editor.copy(),
                before.group, before.category, before.query, before.advanced, before.faders, before.scroll,
                before.eqBand, before.dynamicBand, before.arrayBand), fading, sliding));
        if (departing.size() > 2) departing.remove(0);
        current = page;
        selectedGroup = group;
        advanced = false;
        arrayBand = dynamicBand = 0;
        query = "";
        search.setText("");
        search.setPlaceholder(page == Page.PRESETS ? "搜索预设" : "搜索效果器");
        scroll.reset();
        pageOffset.snap(reducedMotion ? 0 : 24 * direction);
        pageOffset.retarget(0, now, spatialTime());
        pageOpacity.snap(0);
        pageOpacity.retarget(1, now, reducedMotion ? 90 : motionMs(180));
        hover.clear();
    }

    private void drawShell(Canvas canvas) {
        Box surface = layout.surface;
        if (layout.rail) {
            painter.round(canvas, new Box(surface.x(), surface.y(), EffectsLayout.NAVIGATION_WIDTH, surface.height()), 28, SURFACE);
            painter.rect(canvas, new Box(surface.x() + EffectsLayout.NAVIGATION_WIDTH - 28, surface.y(), 28, surface.height()), SURFACE);
        }
        double reveal = reducedMotion ? 1 : EffectsMotion.spatial(pageFade, EffectsMotion.Scheme.DEFAULT_SPATIAL);
        painter.emblem(canvas, Icon.EQ, new Box(surface.x() + 20, surface.y() + 18, 34, 34),
                reveal, PRIMARY_CONTAINER, PRIMARY);
        painter.text(canvas, EffectsTypography.title(), "音效工作台", surface.x() + 64, surface.y() + 29,
                Math.max(1, surface.width() - 215), TEXT);
        painter.text(canvas, EffectsTypography.captionStrong(), "SOUND STUDIO", surface.x() + 64,
                surface.y() + 49, Math.max(1, surface.width() - 215), MUTED);
        chip(canvas, "motion.reduce", new Box(surface.right() - 142, surface.y() + 19, 82, 30),
                "轻动效", reducedMotion, this::flipReducedMotion, true, surface);
        iconButton(canvas, "close", new Box(surface.right() - 48, surface.y() + 17, 32, 32),
                Icon.CLOSE, this::dispose, true, false, surface);
        Box nav = layout.navigation;
        navigationIndicator.retarget(current.ordinal(), now, spatialTime());
        double indicator = Math.max(0, Math.min(Page.values().length - 1, navigationIndicator.value(now)));
        double travel = reducedMotion ? 0 : Math.min(1, Math.abs(navigationIndicator.velocity(now)) * .035);
        Box indicatorBox;
        if (layout.rail) indicatorBox = new Box(nav.x() + 2, nav.y() + indicator * 57 - travel * 3,
                nav.width() - 4, 48 + travel * 6);
        else {
            double width = nav.width() / Page.values().length;
            indicatorBox = new Box(nav.x() + indicator * width + 3 - travel * 3, nav.y() + 3,
                    Math.max(1, width - 6 + travel * 6), 38);
        }
        painter.round(canvas, indicatorBox, layout.rail ? 18 + travel * 6 : 15 + travel * 4, PRIMARY_CONTAINER);
        for (Page page : Page.values()) {
            Box target = layout.rail ? new Box(nav.x(), nav.y() + page.ordinal() * 57, nav.width(), 48)
                    : new Box(nav.x() + nav.width() * page.ordinal() / 5, nav.y() + 3, nav.width() / 5, 38);
            double h = hover("nav." + page, target, true), p = press("nav." + page, true);
            if (h + p > .01) painter.round(canvas, target.inset(3 + Math.max(0, p)), 16, alpha(PRIMARY, h * .06 + Math.max(0, p) * .08));
            double state = selected("nav.color." + page, current == page);
            int color = EffectsPaint.mix(MUTED, PRIMARY, state);
            if (layout.rail) {
                painter.icon(canvas, page.icon, new Box(target.x() + 16, target.y() + 14, 20, 20), color);
                painter.text(canvas, EffectsTypography.label(), page.title, target.x() + 47, target.centerY(), target.width() - 59, color);
                if (current == page) painter.circle(canvas, target.right() - 15, target.centerY(), 2.5, PRIMARY);
            } else {
                boolean icons = target.width() >= 93;
                painter.buttonLabel(canvas, EffectsTypography.label(), page.compact, icons ? page.icon : null, target.inset(2), color);
            }
            addHit("nav." + page, target, layout.surface, () -> navigate(page, -1), null, false, false, null, true);
        }
        if (layout.rail) {
            painter.wave(canvas, new Box(nav.x() + 14, surface.bottom() - 66, nav.width() - 28, 14), 0, .65, alpha(PRIMARY, .45));
            painter.text(canvas, EffectsTypography.caption(), "内部音源 · 本地处理", nav.x() + 12, surface.bottom() - 42, nav.width() - 24, MUTED);
            painter.text(canvas, EffectsTypography.caption(), "配置自动保存", nav.x() + 12, surface.bottom() - 23, nav.width() - 24, MUTED);
        }
    }

    private void flipReducedMotion() {
        reducedMotion = !reducedMotion;
        pressedTarget = null;
        if (reducedMotion) {
            navigationIndicator.snap(current.ordinal());
            pageOffset.snap(0);
            pageOpacity.snap(1);
            departing.clear();
            retiringMenu = null;
            retiringDialog = null;
            shape.clear();
            press.clear();
            menuOffset.snap(0);
            dialogOffset.snap(0);
        }
        message(reducedMotion ? "轻动效：保留淡入反馈，关闭位移与形状回弹" : "灵动反馈：形状与位置使用弹性动效", NoticeTone.INFO);
    }

    private void drawToolbar(Canvas canvas) {
        Box bar = layout.toolbar;
        String title = current.title;
        String subtitle = switch (current) {
            case PRESETS -> "选择完整音效方案，均衡与空间效果分开管理";
            case EQ -> "编辑：「" + targetName() + "」 · 拖动完成后保存并应用";
            case EFFECTS -> selectedGroup < 0 ? "按用途分类，开启状态与编辑入口分开" : "编辑：「" + targetName() + "」";
            case ACOUSTICS -> "环境改变声音，不改变正在播放的歌曲";
            case AMBIENT -> "环境改变选曲，不属于音效处理链";
        };
        double titleX = bar.x();
        if (current == Page.EFFECTS && selectedGroup >= 0 && editor != null) {
            title = editor.groupDisplayName(selectedGroup);
            iconButton(canvas, "group.back", new Box(bar.x() - 5, bar.y() + 8, 30, 30), Icon.BACK,
                    () -> navigate(Page.EFFECTS, -1), true, false, bar);
            titleX += 34;
        }
        boolean environment = current == Page.ACOUSTICS || current == Page.AMBIENT;
        double reserve = environment ? 66 : 132;
        painter.text(canvas, EffectsTypography.heading(), title, titleX, bar.y() + 20,
                Math.max(1, bar.width() - reserve - (titleX - bar.x())), TEXT);
        painter.text(canvas, EffectsTypography.body(), subtitle, bar.x(), bar.y() + 45, bar.width(), MUTED);
        if (environment) toggle(canvas, "page.master", new Box(bar.right() - 52, bar.y() + 7, 48, 30),
                current == Page.ACOUSTICS ? acoustics : ambient, () -> flipEnvironment(current), !loading && !busy, bar);
        else {
            iconButton(canvas, "undo", new Box(bar.right() - 130, bar.y() + 6, 30, 30), Icon.UNDO, this::undo,
                    editor != null && !undo.isEmpty() && !busy, false, bar);
            iconButton(canvas, "redo", new Box(bar.right() - 94, bar.y() + 6, 30, 30), Icon.REDO, this::redo,
                    editor != null && !redo.isEmpty() && !busy, false, bar);
            toggle(canvas, "viper.master", new Box(bar.right() - 51, bar.y() + 7, 48, 30), enabled,
                    this::flipViper, !loading && !busy, bar);
        }
        if (current == Page.PRESETS || current == Page.EFFECTS && selectedGroup < 0) {
            double searchWidth = current == Page.PRESETS && bar.width() >= 480
                    ? Math.min(420, bar.width() - 168) : bar.width();
            searchBox = new Box(bar.x(), bar.y() + 60, searchWidth, 34);
            painter.input(canvas, search, EffectsTypography.body(), searchBox, now);
            addHit("search", searchBox, bar, () -> focus(search, searchBox), null, false, false, null, true);
        } else {
            searchBox = new Box(0, 0, 0, 0);
            boolean on = environment ? current == Page.ACOUSTICS ? acoustics : ambient : enabled;
            String state = environment ? on ? "环境功能已开启" : "环境功能已关闭"
                    : on ? "ViPER 总链开启" : "ViPER 总链关闭 · 可编辑，开启后处理声音";
            painter.text(canvas, EffectsTypography.body(), state, bar.x(), bar.y() + 77, bar.width(), on ? GREEN : MUTED);
        }
        if (current == Page.PRESETS && bar.width() >= 480) button(canvas, "import.quick",
                new Box(bar.right() - 156, bar.y() + 60, 156, 34), importing ? "选择文件中…" : "导入预设", Icon.IMPORT,
                this::browseForPreset, !loading && !busy && !importing, false, bar);
    }

    private void drawPage(Canvas canvas, View view, boolean interactive) {
        if (view.editor == null || loading) {
            if (!loading && !busy && view.editor == null) {
                section(canvas, "音效配置无法读取", notice, layout.viewport.y() + 14, layout.viewport.width());
                button(canvas, "load.retry", new Box(layout.viewport.x(), layout.viewport.y() + 76, 148, 33), "重新读取", Icon.REFRESH,
                        this::requestLoad, interactive, false, pageClip);
                return;
            }
            for (int i = 0; i < 3; i++) {
                painter.round(canvas, new Box(layout.viewport.x(), layout.viewport.y() + 8 + i * 88, layout.viewport.width(), 74), 18, CONTAINER);
                painter.round(canvas, new Box(layout.viewport.x() + 20, layout.viewport.y() + 28 + i * 88,
                        Math.min(180, Math.max(1, layout.viewport.width() - 40)), 10), 5, HIGH);
            }
            return;
        }
        switch (view.page) {
            case PRESETS -> drawPresets(canvas, view, interactive);
            case EQ -> drawEqualizer(canvas, view, interactive);
            case EFFECTS -> {
                if (view.group < 0) drawGroups(canvas, view, interactive);
                else if ("dynamicEq".equals(view.editor.groupKey(view.group))) drawDynamicEq(canvas, view, interactive);
                else drawGroup(canvas, view, interactive);
            }
            case ACOUSTICS -> drawAcoustics(canvas, view, interactive);
            case AMBIENT -> drawAmbient(canvas, view, interactive);
        }
    }

    private void drawStatus(Canvas canvas) {
        Box status = layout.status;
        painter.line(canvas, status.x(), status.y(), status.right(), status.y(), 1, alpha(OUTLINE, .45));
        int color = saveError != null ? ERROR : switch (noticeTone) {
            case INFO -> MUTED;
            case SUCCESS -> GREEN;
            case WARNING -> AMBER;
            case ERROR -> ERROR;
        };
        String text = saveError != null ? "保存失败：" + saveError : pendingSaves > 0 ? "正在保存并应用…" : notice;
        painter.circle(canvas, status.x() + 4, status.centerY(), 2.5, color);
        painter.text(canvas, EffectsTypography.caption(), text, status.x() + 16, status.centerY(), Math.max(1, status.width() - (saveError == null ? 20 : 72)), color);
        if (saveError != null) button(canvas, "save.retry", new Box(status.right() - 56, status.y() + 5, 56, 27), "重试", null,
                () -> { queuedRevision = -1; commitEditor(); }, !busy && pendingSaves == 0, false, status);
    }

    private double hover(String id, Box box, boolean available) {
        if (!paintingLive) return 0;
        EffectsMotion state = hover.computeIfAbsent(id, ignored -> new EffectsMotion(0));
        boolean menuItem = id.startsWith("menu."), dialogItem = id.startsWith("dialog.");
        boolean permitted = dialogTitle != null ? dialogItem : menu != null ? menuItem : true;
        Box clip = menuItem && menu != null ? menuBox() : dialogItem ? dialogBox() : layout.surface;
        state.retarget(available && ownsInput && permitted && box.intersect(clip).contains(mouseX, mouseY) ? 1 : 0,
                now, reducedMotion ? 90 : motionMs(140));
        return state.value(now);
    }

    private double selected(String id, boolean on) {
        if (!paintingLive) return on ? 1 : 0;
        EffectsMotion state = selection.computeIfAbsent(id, ignored -> new EffectsMotion(on ? 1 : 0));
        state.retarget(on ? 1 : 0, now, reducedMotion ? 90 : motionMs(170));
        return state.value(now);
    }

    private double shape(String id, boolean on) {
        if (!paintingLive || reducedMotion) return on ? 1 : 0;
        EffectsMotion.Spring state = shape.computeIfAbsent(id, ignored -> new EffectsMotion.Spring(on ? 1 : 0, EffectsMotion.Scheme.FAST_SPATIAL));
        state.retarget(on ? 1 : 0, now, spatialTime());
        return state.value(now);
    }

    private double press(String id, boolean available) {
        if (!paintingLive || reducedMotion) return 0;
        EffectsMotion.Spring state = press.computeIfAbsent(id, ignored -> new EffectsMotion.Spring(0, EffectsMotion.Scheme.FAST_SPATIAL));
        boolean held = available && ownsInput && pressedTarget != null && id.equals(pressedTarget.id)
                && pressedTarget.contains(mouseX, mouseY);
        state.retarget(held ? 1 : 0, now, spatialTime());
        return Math.max(-.12, Math.min(1, state.value(now)));
    }

    private void samplePress(boolean down) {
        if (!down || !ownsInput || closing || !hostAlive || layout == null || presentation < .98) {
            pressedTarget = null;
            return;
        }
        if (!wasDown && pressedTarget == null) pressedTarget = findHit(mouseX, mouseY);
    }

    private double spatialTime() { return reducedMotion ? 0 : 3 / SheetClock.speed(); }

    private void addHit(String id, Box box, Box clip, Runnable click, DoubleConsumer drag,
                        boolean vertical, boolean graph, DoubleConsumer graphX, boolean available) {
        if (!hostAlive) return;
        if (!available || !paintingLive || closing || !ownsInput || presentation < .98
                || retiringDialog != null && dialogTitle == null || retiringMenu != null && menu == null) return;
        if (clip.equals(pageClip)) {
            double offset = pageOffset.value(now);
            box = box.offset(offset, 0);
            clip = clip.offset(offset, 0);
        }
        hits.add(new Hit(id, box, clip, click, drag, vertical, graph, graphX));
    }

    private void button(Canvas canvas, String id, Box box, String label, Icon icon, Runnable action,
                        boolean available, boolean primary, Box clip) {
        double h = hover(id, box, available), p = press(id, available);
        double inset = Math.max(0, p) * Math.min(1.5, box.height() * .035);
        Box face = box.inset(inset);
        double radius = Math.max(7, face.height() * .5 - Math.max(0, p) * 8);
        int base = primary ? PRIMARY : CONTAINER, ink = primary ? ON_PRIMARY : TEXT;
        painter.round(canvas, face, radius,
                alpha(EffectsPaint.mix(base, primary ? palette.primaryHover() : HIGH, Math.min(1, h + Math.max(0, p) * .55)), available ? 1 : .5));
        painter.buttonLabel(canvas, EffectsTypography.label(), label, icon, face, alpha(ink, available ? 1 : .45));
        addHit(id, box, clip, action, null, false, false, null, available);
    }

    private void iconButton(Canvas canvas, String id, Box box, Icon icon, Runnable action,
                            boolean available, boolean danger, Box clip) {
        double h = hover(id, box, available), p = press(id, available);
        Box face = box.inset(Math.max(0, p));
        if (h + p > .01) painter.round(canvas, face, Math.max(8, face.height() * .5 - Math.max(0, p) * 6),
                alpha(danger ? ERROR : PRIMARY, h * .08 + Math.max(0, p) * .1));
        painter.icon(canvas, icon, face.inset(7), alpha(danger ? ERROR : MUTED, available ? 1 : .35));
        addHit(id, box, clip, action, null, false, false, null, available);
    }

    private void chip(Canvas canvas, String id, Box box, String label, boolean on, Runnable action,
                      boolean available, Box clip) {
        double state = selected(id, on), morph = shape(id, on), h = hover(id, box, available), p = press(id, available);
        Box face = box.inset(Math.max(0, p));
        double radius = Math.max(7, face.height() * .5 - Math.max(0, morph) * 5 - Math.max(0, p) * 4);
        painter.round(canvas, face, radius, alpha(EffectsPaint.mix(EffectsPaint.mix(CONTAINER, HIGH, h), PRIMARY_CONTAINER, state), available ? 1 : .5));
        painter.centered(canvas, EffectsTypography.label(), label, face.inset(4),
                alpha(EffectsPaint.mix(MUTED, PRIMARY, state), available ? 1 : .5));
        addHit(id, box, clip, action, null, false, false, null, available);
    }

    private void toggle(Canvas canvas, String id, Box box, boolean on, Runnable action, boolean available, Box clip) {
        double state = selected(id, on), position = Math.max(-.045, Math.min(1.045, shape(id, on))), p = press(id, available);
        Box track = new Box(box.x(), box.centerY() - 12, box.width(), 24);
        painter.round(canvas, track, 12, alpha(EffectsPaint.mix(OUTLINE, PRIMARY, state), available ? 1 : .5));
        double knob = 15 + state * 4 + Math.max(0, p) * 2;
        double cx = track.x() + 12 + position * (track.width() - 24);
        painter.circle(canvas, cx, track.centerY(), knob * .5, alpha(EffectsPaint.mix(MUTED, ON_PRIMARY, state), available ? 1 : .8));
        if (state > .01) painter.icon(canvas, Icon.CHECK, new Box(cx - 5, track.centerY() - 5, 10, 10), alpha(PRIMARY, state));
        painter.circle(canvas, cx, track.centerY(), 15, alpha(PRIMARY, hover(id, box, available) * .08));
        addHit(id, box, clip, action, null, false, false, null, available);
    }

    private void section(Canvas canvas, String title, String note, double y, double width) {
        painter.text(canvas, EffectsTypography.section(), title, layout.viewport.x(), y + 13, width, TEXT);
        if (note != null) painter.text(canvas, EffectsTypography.body(), note, layout.viewport.x(), y + 31, width, MUTED);
    }

    private double contentTop(View view) { return layout.viewport.y() + 8 - view.scroll; }
    private void contentBounds(Canvas canvas, double height, boolean live) {
        if (!live) return;
        scroll.bounds(height + 16, layout.viewport.height(), now);
        if (scroll.max() <= 0) return;
        double size = Math.max(26, layout.viewport.height() * layout.viewport.height() / (height + 16));
        double top = layout.viewport.y() + scroll.value(now) / scroll.max() * Math.max(0, layout.viewport.height() - size);
        painter.round(canvas, new Box(layout.viewport.right() - 3, top, 3, size), 1.5, alpha(MUTED, .32));
    }

    private void drawPresets(Canvas canvas, View view, boolean interactive) {
        double top = contentTop(view), width = layout.viewport.width();
        double heroTextWidth = Math.max(1, width - 128);
        String heroTitle = active == null ? "从一个预设开始" : active;
        double titleHeight = painter.lines(EffectsTypography.heading(), heroTitle, heroTextWidth).length * painter.lineHeight(EffectsTypography.heading());
        double noteHeight = painter.lines(EffectsTypography.body(), "切换完整方案不会替你开启总链", Math.max(1, width - 92)).length * painter.lineHeight(EffectsTypography.body());
        Box hero = new Box(layout.viewport.x(), top, width, Math.max(104, 50 + titleHeight + noteHeight));
        painter.round(canvas, hero, 26, PRIMARY_CONTAINER);
        painter.emblem(canvas, Icon.PRESETS, new Box(hero.x() + 16, hero.y() + 21, 44, 44), 1, alpha(PRIMARY, .12), PRIMARY);
        painter.text(canvas, EffectsTypography.captionStrong(), active == null ? "尚未选用完整方案" : enabled ? "当前方案 · 总链开启" : "当前方案 · 总链关闭",
                hero.x() + 72, hero.y() + 25, heroTextWidth, PRIMARY);
        painter.paragraph(canvas, EffectsTypography.heading(), heroTitle, hero.x() + 72, hero.y() + 39, heroTextWidth, TEXT);
        painter.paragraph(canvas, EffectsTypography.body(), "切换完整方案不会替你开启总链", hero.x() + 72, hero.y() + 42 + titleHeight, width - 92, MUTED);
        iconButton(canvas, "preset.active.eq", new Box(hero.right() - 51, hero.y() + 21, 36, 36), Icon.EQ,
                () -> navigate(Page.EQ, -1), interactive, false, pageClip);
        top = hero.bottom() + 12;
        int columns = width >= 590 ? 2 : 1, tools = width >= 450 ? 3 : 2;
        double toolWidth = (width - (tools - 1) * 8) / tools;
        button(canvas, "preset.create", new Box(layout.viewport.x(), top, toolWidth, 32), "新建方案", Icon.PLUS,
                this::newPreset, interactive && !busy, false, pageClip);
        button(canvas, "preset.copy", new Box(layout.viewport.x() + toolWidth + 8, top, toolWidth, 32), "保存副本", Icon.COPY,
                this::openSaveAs, interactive && !busy, false, pageClip);
        if (tools == 3) button(canvas, "preset.refresh", new Box(layout.viewport.x() + (toolWidth + 8) * 2, top, toolWidth, 32),
                "刷新文件", Icon.REFRESH, this::reloadPresets, interactive && !busy, false, pageClip);
        else {
            top += 40;
            button(canvas, "preset.import", new Box(layout.viewport.x(), top, toolWidth, 32), "导入文件", Icon.IMPORT,
                    this::browseForPreset, interactive && !busy && !importing, false, pageClip);
            button(canvas, "preset.refresh", new Box(layout.viewport.x() + toolWidth + 8, top, toolWidth, 32), "刷新文件", Icon.REFRESH,
                    this::reloadPresets, interactive && !busy, false, pageClip);
        }
        top += 49;
        section(canvas, "预设库", "选用是明确动作；再次点击当前方案不会误取消", top, width);
        top += 45;
        String filter = view.query.toLowerCase(Locale.ROOT).trim();
        List<String> shown = presets.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(filter)).toList();
        if (shown.isEmpty()) {
            Box empty = new Box(layout.viewport.x(), top, width, 94);
            painter.round(canvas, empty, 18, CONTAINER);
            painter.centered(canvas, EffectsTypography.section(), presets.isEmpty() ? "导入文件或新建一个方案" : "没有匹配的预设", empty, MUTED);
            top += 106;
        } else {
            double cardWidth = (width - (columns - 1) * 10) / columns;
            for (int i = 0; i < shown.size(); i++) {
                String name = shown.get(i);
                boolean picked = name.equals(active);
                Box card = new Box(layout.viewport.x() + (cardWidth + 10) * (i % columns), top + (i / columns) * 112, cardWidth, 101);
                if (card.bottom() < pageClip.y() || card.y() > pageClip.bottom()) continue;
                double state = selected("preset." + name, picked);
                painter.round(canvas, card, 18, EffectsPaint.mix(CONTAINER, PRIMARY_CONTAINER, state * .75));
                if (picked) painter.outline(canvas, card.inset(.7), 17.3, 1.3, alpha(PRIMARY, .75));
                painter.text(canvas, EffectsTypography.section(), name, card.x() + 17, card.y() + 25, card.width() - 58, TEXT);
                painter.text(canvas, EffectsTypography.body(), picked ? enabled ? "已选用 · 处理播放输出" : "已选用 · 等待开启总链" : "完整 ViPER 音效方案",
                        card.x() + 17, card.y() + 48, card.width() - 34, picked ? PRIMARY : MUTED);
                Box useBox = new Box(card.x() + 14, card.bottom() - 36, 106, 26);
                if (picked) {
                    painter.round(canvas, useBox, 13, PRIMARY_CONTAINER);
                    painter.buttonLabel(canvas, EffectsTypography.label(), "已选用", Icon.CHECK, useBox, PRIMARY);
                } else button(canvas, "preset.use." + name, useBox, "选用", null,
                        () -> selectPreset(name), interactive && !busy, false, pageClip);
                iconButton(canvas, "preset.menu." + name, new Box(card.right() - 41, card.y() + 10, 29, 29), Icon.CHEVRON,
                        () -> openPresetMenu(name, card), interactive && !busy, false, pageClip);
            }
            top += ((shown.size() + columns - 1) / columns) * 112;
        }
        painter.text(canvas, EffectsTypography.body(), "文件目录：config/muonium/viper/ · presets / kernels / ddc", layout.viewport.x(), top + 16, width, MUTED);
        contentBounds(canvas, top + 34 - contentTop(view), interactive);
    }

    private void newPreset() {
        stateJob(() -> {
            ViperPresetEditor next = ViperPresetEditor.newScratch();
            next.useCheckedRuntimeHooks();
            return next;
        }, value -> { replaceEditor(value); navigate(Page.EQ, -1); },
                "新建空白方案 · 第一次调整后保存为独立预设");
    }

    private void reloadPresets() {
        Runnable reload = () -> stateJob(() -> { ViperRuntime.reload(); return load(); },
                value -> applyLoaded(value, true), "已从磁盘重新读取音效配置");
        if (saveError != null) confirm("重新读取配置", "将用磁盘版本替换当前工作副本。未成功保存的修改会丢弃。", reload);
        else reload.run();
    }

    private void openPresetMenu(String name, Box anchor) {
        menu = new ArrayList<>();
        if (!name.equals(active)) menu.add(new MenuItem("选用这个方案", Icon.CHECK, false, () -> selectPreset(name)));
        else menu.add(new MenuItem("解除当前选择", Icon.POWER, false, () -> selectPreset(null)));
        menu.add(new MenuItem("复制为新方案", Icon.COPY, false, () -> duplicatePreset(name)));
        menu.add(new MenuItem("删除文件…", Icon.DELETE, true, () -> confirm("删除预设", "将删除「" + name + "」的预设文件。卷积核和 DDC 系数不删除，恢复需要重新导入。", () -> {
            boolean replace = name.equals(active);
            stateJob(() -> {
                ViperRuntime.removePreset(name);
                if (ViperRuntime.hasPreset(name)) throw new IllegalStateException("预设文件未成功删除");
                return load();
            }, value -> applyLoaded(value, replace), "已删除「" + name + "」");
        })));
        openMenu(new Box(anchor.right() - 30, anchor.y() + 40, 1, 1));
    }

    private void duplicatePreset(String name) {
        stateJob(() -> {
            com.google.gson.JsonObject source = ViperRuntime.exportPresetJson(name);
            if (source == null) throw new IllegalArgumentException("预设不可读取：" + name);
            source.addProperty("name", name + " 副本");
            ViperRuntime.importPresetJson(source, name + " 副本");
            return ViperRuntime.listPresetNames();
        }, result -> presets = List.copyOf(result), "已创建独立副本，原方案保持不变");
    }

    private void drawEqualizer(Canvas canvas, View view, boolean interactive) {
        ViperEqualizerModel eq;
        try { eq = new ViperEqualizerModel(view.editor); }
        catch (IllegalArgumentException invalid) {
            double top = contentTop(view);
            section(canvas, "导入的 EQ 布局无法识别", "明确恢复默认值后再编辑，不猜测非法段数的频率", top, layout.viewport.width());
            button(canvas, "eq.recover", new Box(layout.viewport.x(), top + 52, 180, 34), "恢复 EQ 默认值", Icon.REFRESH,
                    () -> edit(() -> editor.resetGroupQuiet(editor.groupIndex("equalizer"))), interactive && !busy, true, pageClip);
            contentBounds(canvas, 100, interactive);
            return;
        }
        double top = contentTop(view), width = layout.viewport.width();
        String eqHint = eq.enabled() ? "均衡器开启，ViPER 总链开关独立" : "均衡器旁路，曲线与频段参数保留";
        double eqHintHeight = painter.lines(EffectsTypography.caption(), eqHint, width - 88).length * painter.lineHeight(EffectsTypography.caption());
        Box state = new Box(layout.viewport.x(), top, width, Math.max(64, 38 + eqHintHeight));
        painter.round(canvas, state, 20, CONTAINER);
        painter.text(canvas, EffectsTypography.section(), "图形 EQ", state.x() + 16, state.y() + 20, width - 88, TEXT);
        painter.paragraph(canvas, EffectsTypography.caption(), eqHint, state.x() + 16, state.y() + 34, width - 88, MUTED);
        toggle(canvas, "eq.enable", new Box(state.right() - 65, state.y() + 15, 46, 30), eq.enabled(),
                () -> edit(() -> new ViperEqualizerModel(editor).setEnabledQuiet(!new ViperEqualizerModel(editor).enabled())), interactive && !busy, pageClip);
        top = state.bottom() + 12;
        int count = eq.bandCount();
        double selectorWidth = width < 520 ? width : 316;
        int[] counts = ViperEqualizerModel.supportedBandCounts();
        for (int i = 0; i < counts.length; i++) {
            int next = counts[i];
            chip(canvas, "eq.count." + next, new Box(layout.viewport.x() + i * (selectorWidth / 4), top, selectorWidth / 4 - 6, 32),
                    next + " 段", count == next, () -> edit(() -> new ViperEqualizerModel(editor).setBandCountQuiet(next)), interactive && !busy, pageClip);
        }
        if (width >= 520) chip(canvas, "eq.view", new Box(layout.viewport.right() - 118, top, 118, 32), view.faders ? "隐藏推子" : "频段推子",
                view.faders, () -> { finishDrag(true); faders = !faders; scroll.reset(); }, interactive, pageClip);
        top += 42;
        String matching = eq.matchingPresetId();
        String presetName = ViperEqPresets.all().stream().filter(preset -> preset.id.equals(matching)).map(preset -> preset.name).findFirst().orElse("自定义曲线");
        double resetWidth = Math.min(102, width * .35);
        button(canvas, "eq.preset.quick", new Box(layout.viewport.x(), top, Math.max(1, width - resetWidth - 8), 32), "音色起点：" + presetName, Icon.CHEVRON,
                () -> openEqPresets(new Box(layout.viewport.x(), layout.viewport.y() + 96, Math.max(1, width - resetWidth - 8), 32)), interactive && !busy, false, pageClip);
        button(canvas, "eq.flatten.quick", new Box(layout.viewport.right() - resetWidth, top, resetWidth, 32), "全部归零", null,
                () -> edit(() -> new ViperEqualizerModel(editor).flattenQuiet()), interactive && !busy, false, pageClip);
        top += 42;
        if (width < 520) {
            chip(canvas, "eq.view.compact", new Box(layout.viewport.x(), top, width, 32), view.faders ? "隐藏频段推子" : "展开频段推子",
                    view.faders, () -> { finishDrag(true); faders = !faders; scroll.reset(); }, interactive, pageClip);
            top += 42;
        }
        Box chart = new Box(layout.viewport.x(), top, width, width < 400 ? 188 : 220);
        painter.round(canvas, chart, 20, SURFACE);
        painter.text(canvas, EffectsTypography.label(), "目标增益曲线", chart.x() + 16, chart.y() + 19, width - 60, TEXT);
        painter.text(canvas, EffectsTypography.caption(), "dB", chart.right() - 30, chart.y() + 19, 26, MUTED);
        Box plot = EffectsLayout.chart(chart);
        double[] frequencies = eq.frequencies(), gains = eq.gains(), display = gains;
        // 调音参数和推子始终跟手；只给选段与按压反馈补间，不把回弹写成另一条目标曲线。
        for (int db = -12; db <= 12; db += 6) {
            double y = EffectsLayout.gainY(db, plot);
            painter.line(canvas, plot.x(), y, plot.right(), y, db == 0 ? 1.2 : .7, alpha(OUTLINE, db == 0 ? .9 : .65));
            painter.text(canvas, EffectsTypography.caption(), (db > 0 ? "+" : "") + db, chart.x() + 9, y, 28, MUTED);
        }
        double[] ticks = width < 460 ? new double[]{62, 250, 1000, 4000, 16000}
                : width < 660 ? new double[]{31, 125, 500, 2000, 8000, 16000}
                : new double[]{31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
        for (double hz : ticks) {
            double x = EffectsLayout.frequencyX(hz, plot);
            painter.line(canvas, x, plot.y(), x, plot.bottom(), .7, alpha(OUTLINE, .3));
            painter.centered(canvas, EffectsTypography.caption(), frequencyText(hz), new Box(x - 21, plot.bottom() + 10, 42, 19), MUTED);
        }
        drawCurve(canvas, frequencies, display, plot, eq.enabled() ? PRIMARY : MUTED);
        int shownBand = Math.max(0, Math.min(count - 1, view.eqBand));
        if (interactive) selectedBand = shownBand;
        double focus = Math.max(0, Math.min(1, shape("eq.focus", drag != null && drag.id.startsWith("eq."))));
        for (int i = 0; i < frequencies.length; i++) {
            double x = EffectsLayout.frequencyX(frequencies[i], plot), y = EffectsLayout.gainY(display[i], plot);
            double picked = selected("eq.point." + i, i == shownBand);
            if (picked > .005) painter.circle(canvas, x, y, 8 + focus * 3, alpha(PRIMARY, picked * .16));
            painter.circle(canvas, x, y, (count > 15 ? 2.6 : 3.4) + picked * 1.1, eq.enabled() ? PRIMARY : MUTED);
        }
        addHit("eq.graph", plot, pageClip, () -> { }, value -> new ViperEqualizerModel(editor)
                .setBandGainQuiet(selectedBand, Math.round(value * 10) / 10.0), true, true, x -> {
            double[] freqs = new ViperEqualizerModel(editor).frequencies();
            int nearest = 0;
            double distance = Double.MAX_VALUE;
            for (int i = 0; i < freqs.length; i++) {
                double d = Math.abs(EffectsLayout.frequencyX(freqs[i], plot) + pageOffset.value(now) - x);
                if (d < distance) { distance = d; nearest = i; }
            }
            selectedBand = nearest;
        }, interactive && !busy);
        top = chart.bottom() + 11;
        double detailHeight = width < 440 ? 90 : 46;
        Box detail = new Box(layout.viewport.x(), top, width, detailHeight);
        painter.round(canvas, detail, 16, CONTAINER);
        final int band = shownBand;
        painter.text(canvas, EffectsTypography.section(), frequencyText(frequencies[band]) + " Hz", detail.x() + 16, detail.y() + 22,
                width < 440 ? width - 118 : width - 258, PRIMARY);
        Numeric bandValue = new Numeric("频段增益 · " + frequencyText(frequencies[band]) + " Hz", "dB", -12, 12, false,
                () -> new ViperEqualizerModel(editor).gains()[band], value -> new ViperEqualizerModel(editor).setBandGainQuiet(band, value));
        if (width >= 440) {
            button(canvas, "eq.value", new Box(detail.right() - 220, detail.y() + 8, 91, 30), signed(gains[band]) + " dB", null,
                    () -> openNumber(bandValue), interactive && !busy, false, pageClip);
            button(canvas, "eq.zero", new Box(detail.right() - 120, detail.y() + 8, 106, 30), "此频段归零", null,
                    () -> edit(() -> new ViperEqualizerModel(editor).setBandGainQuiet(band, 0)), interactive && !busy, false, pageClip);
        } else {
            painter.text(canvas, EffectsTypography.section(), signed(gains[band]) + " dB", detail.right() - 88, detail.y() + 22, 74, TEXT);
            double bw = (width - 38) / 3;
            button(canvas, "eq.band.prev", new Box(detail.x() + 12, detail.y() + 50, bw, 29), "上一段", null,
                    () -> selectedBand = (selectedBand + count - 1) % count, interactive, false, pageClip);
            button(canvas, "eq.band.edit", new Box(detail.x() + 19 + bw, detail.y() + 50, bw, 29), "精确数值", null,
                    () -> openNumber(bandValue), interactive && !busy, false, pageClip);
            button(canvas, "eq.band.next", new Box(detail.x() + 26 + bw * 2, detail.y() + 50, bw, 29), "下一段", null,
                    () -> selectedBand = (selectedBand + 1) % count, interactive, false, pageClip);
        }
        top += detailHeight + 12;
        if (view.faders) top = drawFaders(canvas, eq, top, shownBand, interactive);
        section(canvas, "音色起点", "只改 EQ，保留其他效果；听感偏好，不是设备校准", top, width);
        top += 47;
        int presetColumns = Math.max(2, Math.min(4, (int) (width / 135)));
        List<ViperEqPresets.Preset> eqPresets = ViperEqPresets.all();
        double cell = (width - (presetColumns - 1) * 8) / presetColumns;
        for (int i = 0; i < eqPresets.size(); i++) {
            ViperEqPresets.Preset preset = eqPresets.get(i);
            Box card = new Box(layout.viewport.x() + (i % presetColumns) * (cell + 8), top + (i / presetColumns) * 78, cell, 70);
            double stateValue = selected("eq.preset." + preset.id, preset.id.equals(matching));
            painter.round(canvas, card, 14, EffectsPaint.mix(CONTAINER, PRIMARY_CONTAINER, stateValue));
            painter.text(canvas, EffectsTypography.label(), preset.name, card.x() + 12, card.y() + 19, card.width() - 24, stateValue > .5 ? PRIMARY : TEXT);
            painter.text(canvas, EffectsTypography.caption(), eqPresetNote(preset.id), card.x() + 12, card.y() + 36, card.width() - 24, MUTED);
            double[] points = preset.gains();
            for (int k = 0; k < points.length - 1; k++) painter.line(canvas,
                    card.x() + 12 + k * (cell - 24) / 9, card.bottom() - 15 - points[k] * 1.5,
                    card.x() + 12 + (k + 1) * (cell - 24) / 9, card.bottom() - 15 - points[k + 1] * 1.5, 1.4, PRIMARY);
            addHit("eq.preset." + preset.id, card, pageClip,
                    () -> edit(() -> new ViperEqualizerModel(editor).applyPresetQuiet(preset.id)), null, false, false, null, interactive && !busy);
        }
        top += ((eqPresets.size() + presetColumns - 1) / presetColumns) * 78 + 9;
        int outputGroup = view.editor.groupIndex("masterLimiter"), outputField = view.editor.fieldIndex(outputGroup, "outputVolume");
        top = numberRow(canvas, view.editor, "eq.output", "链尾输出补偿", "手动降低输出电平 · 不是 EQ preamp",
                new Box(layout.viewport.x(), top, width, 76), outputGroup, outputField, -1, interactive);
        top += 11;
        double half = (width - 8) / 2;
        button(canvas, "eq.flatten", new Box(layout.viewport.x(), top, half, 32), "全部频段归零", Icon.REFRESH,
                () -> edit(() -> new ViperEqualizerModel(editor).flattenQuiet()), interactive && !busy, false, pageClip);
        button(canvas, "eq.saveAs", new Box(layout.viewport.x() + half + 8, top, half, 32), "保存为新方案", Icon.COPY,
                this::openSaveAs, interactive && !busy, false, pageClip);
        top += 45;
        painter.text(canvas, EffectsTypography.caption(), "目标曲线非实测频响。松手后重配现有链路，不逐帧重载音频引擎。", layout.viewport.x(), top + 12, width, MUTED);
        contentBounds(canvas, top + 26 - contentTop(view), interactive);
    }

    private void openEqPresets(Box anchor) {
        finishDrag(true);
        menu = new ArrayList<>();
        String matching = new ViperEqualizerModel(editor).matchingPresetId();
        for (ViperEqPresets.Preset preset : ViperEqPresets.all()) {
            menu.add(new MenuItem(preset.name + " · " + eqPresetNote(preset.id), preset.id.equals(matching) ? Icon.CHECK : null, false,
                    () -> edit(() -> new ViperEqualizerModel(editor).applyPresetQuiet(preset.id))));
        }
        openMenu(anchor);
    }

    private static String eqPresetNote(String id) {
        return switch (id) {
            case "flat" -> "所有频段归零";
            case "bass" -> "低频厚度";
            case "vocal" -> "中频存在感";
            case "pop" -> "均衡明亮";
            case "rock" -> "节奏与力度";
            case "jazz" -> "细节与温暖";
            case "classical" -> "轻微开阔";
            case "electronic" -> "低频与空气感";
            default -> "手动曲线";
        };
    }

    private void drawCurve(Canvas canvas, double[] frequencies, double[] gains, Box plot, int color) {
        try (PathBuilder builder = new PathBuilder()) {
            for (int i = 0; i < frequencies.length; i++) {
                float x = (float) EffectsLayout.frequencyX(frequencies[i], plot), y = (float) EffectsLayout.gainY(gains[i], plot);
                if (i == 0) builder.moveTo(x, y);
                else {
                    float px = (float) EffectsLayout.frequencyX(frequencies[i - 1], plot), py = (float) EffectsLayout.gainY(gains[i - 1], plot);
                    float mid = (px + x) * .5f;
                    builder.cubicTo(mid, py, mid, y, x, y);
                }
            }
            try (Path line = builder.build()) { painter.path(canvas, line, 2.3, color); }
        }
    }

    private double drawFaders(Canvas canvas, ViperEqualizerModel eq, double top, int selected, boolean interactive) {
        double width = layout.viewport.width();
        double[] gains = eq.gains(), frequencies = eq.frequencies();
        int perRow = Math.min(gains.length, Math.max(4, (int) (width / 58)));
        int rows = (gains.length + perRow - 1) / perRow;
        double cellWidth = width / perRow;
        for (int i = 0; i < gains.length; i++) {
            int band = i;
            Box cell = new Box(layout.viewport.x() + (i % perRow) * cellWidth, top + (i / perRow) * 156, cellWidth - 3, 147);
            double state = selected("eq.fader.focus." + i, selected == i);
            double engagement = Math.max(0, press("eq.fader." + i, interactive));
            painter.round(canvas, cell, 14, EffectsPaint.mix(SURFACE, PRIMARY_CONTAINER, state));
            painter.centered(canvas, EffectsTypography.caption(), frequencyText(frequencies[i]), new Box(cell.x(), cell.y() + 5, cell.width(), 21), MUTED);
            Box track = new Box(cell.x() + 6, cell.y() + 32, Math.max(1, cell.width() - 12), 78);
            double pos = EffectsLayout.gainY(gains[i], track), center = track.centerY();
            double thumbWidth = 18 + engagement * 4, gap = 5;
            painter.round(canvas, new Box(track.centerX() - 3, track.y(), 6, Math.max(0, pos - track.y() - gap)), 3, OUTLINE);
            painter.round(canvas, new Box(track.centerX() - 3, pos + gap, 6, Math.max(0, track.bottom() - pos - gap)), 3, OUTLINE);
            if (Math.abs(center - pos) > gap) painter.round(canvas, new Box(track.centerX() - 3,
                    Math.min(center, pos + gap), 6, Math.max(0, Math.abs(center - pos) - gap)), 3, PRIMARY);
            painter.round(canvas, new Box(track.centerX() - thumbWidth * .5, pos - 3, thumbWidth, 6), 3, PRIMARY);
            addHit("eq.fader." + i, track, pageClip, () -> selectedBand = band,
                    value -> { selectedBand = band; new ViperEqualizerModel(editor).setBandGainQuiet(band, Math.round(value * 10) / 10.0); },
                    true, true, null, interactive && !busy);
            Box value = new Box(cell.x() + 3, cell.bottom() - 28, cell.width() - 6, 23);
            painter.centered(canvas, EffectsTypography.captionStrong(), signed(gains[i]), value, TEXT);
            addHit("eq.fader.value." + i, value, pageClip, () -> openNumber(new Numeric("增益 · " + frequencyText(frequencies[band]) + " Hz", "dB", -12, 12,
                    false, () -> new ViperEqualizerModel(editor).gains()[band], v -> new ViperEqualizerModel(editor).setBandGainQuiet(band, v))),
                    null, false, false, null, interactive && !busy);
        }
        return top + rows * 156 + 6;
    }

    private static String frequencyText(double hz) {
        if (hz >= 1000) return String.format(Locale.ROOT, hz % 1000 == 0 ? "%.0fk" : "%.1fk", hz / 1000);
        return String.format(Locale.ROOT, hz % 1 == 0 ? "%.0f" : "%.1f", hz);
    }
    private static String signed(double value) { return String.format(Locale.ROOT, "%+.1f", value == 0 ? 0 : value); }

    private void drawGroups(Canvas canvas, View view, boolean interactive) {
        double top = contentTop(view), width = layout.viewport.width();
        ViperWorkbenchCatalog.Category[] categories = ViperWorkbenchCatalog.Category.values();
        int chipColumns = width < 450 ? 3 : 5;
        double chipWidth = (width - (chipColumns - 1) * 7) / chipColumns;
        for (int i = 0; i < categories.length; i++) {
            ViperWorkbenchCatalog.Category next = categories[i];
            chip(canvas, "category." + next, new Box(layout.viewport.x() + (i % chipColumns) * (chipWidth + 7), top + (i / chipColumns) * 37, chipWidth, 30),
                    next.title, view.category == next, () -> { finishDrag(true); category = next; scroll.reset(); }, interactive, pageClip);
        }
        top += ((categories.length + chipColumns - 1) / chipColumns) * 37 + 11;
        section(canvas, view.query.isBlank() ? view.category.title : "搜索结果", view.query.isBlank() ? view.category.description : "按效果器名称、说明或配置键匹配", top, width);
        top += 45;
        String filter = view.query.trim().toLowerCase(Locale.ROOT);
        int found = 0;
        for (int gi = 0; gi < view.editor.groupCount(); gi++) {
            String key = view.editor.groupKey(gi), title = view.editor.groupDisplayName(gi);
            ViperWorkbenchCatalog.Category itemCategory = ViperWorkbenchCatalog.category(key);
            if (filter.isBlank() && itemCategory != view.category) continue;
            if (!filter.isBlank() && !(title + " " + key + " " + ViperWorkbenchCatalog.description(key)).toLowerCase(Locale.ROOT).contains(filter)) continue;
            int group = gi, enableField = view.editor.fieldIndex(gi, "enable");
            boolean on = enableField < 0 || view.editor.getBoolean(gi, enableField, false);
            String description = ViperWorkbenchCatalog.description(key);
            double textWidth = Math.max(1, width - 78);
            double titleWidth = Math.max(1, width - 164);
            double titleHeight = painter.lines(EffectsTypography.section(), title, titleWidth).length
                    * painter.lineHeight(EffectsTypography.section());
            double descriptionHeight = painter.lines(EffectsTypography.caption(), description, textWidth).length
                    * painter.lineHeight(EffectsTypography.caption());
            double stateTop = 18 + titleHeight + descriptionHeight;
            Box card = new Box(layout.viewport.x(), top, width, Math.max(88, stateTop + 28));
            painter.round(canvas, card, 18, CONTAINER);
            painter.icon(canvas, categoryIcon(itemCategory), new Box(card.x() + 15, card.y() + 22, 25, 25), on ? PRIMARY : MUTED);
            painter.paragraph(canvas, EffectsTypography.section(), title, card.x() + 54, card.y() + 12, titleWidth, TEXT);
            painter.paragraph(canvas, EffectsTypography.caption(), description, card.x() + 54, card.y() + 16 + titleHeight, textWidth, MUTED);
            painter.text(canvas, EffectsTypography.caption(), enableField < 0 ? "固定输出段" : on ? "效果器开启" : "效果器旁路",
                    card.x() + 54, card.y() + stateTop + 12, textWidth, on ? GREEN : MUTED);
            if ("dynamicEq".equals(key)) {
                menuSafeDynamicEnable(canvas, "group.toggle." + key, new Box(card.right() - 105, card.y() + 10, 42, 30), on, interactive);
            } else if (enableField >= 0) toggle(canvas, "group.toggle." + key, new Box(card.right() - 105, card.y() + 10, 42, 30), on,
                    () -> edit(() -> editor.setBooleanQuiet(group, enableField, !editor.getBoolean(group, enableField, false))), interactive && !busy, pageClip);
            iconButton(canvas, "group.open." + key, new Box(card.right() - 46, card.y() + 9, 32, 32), Icon.CHEVRON,
                    () -> navigate("equalizer".equals(key) ? Page.EQ : Page.EFFECTS, "equalizer".equals(key) ? -1 : group), interactive, false, pageClip);
            addHit("group.card." + key, new Box(card.x(), card.y() + 42, card.width(), card.height() - 42), pageClip,
                    () -> navigate("equalizer".equals(key) ? Page.EQ : Page.EFFECTS, "equalizer".equals(key) ? -1 : group), null, false, false, null, interactive);
            top = card.bottom() + 10;
            found++;
        }
        if (found == 0) {
            painter.centered(canvas, EffectsTypography.section(), "没有匹配的效果器", new Box(layout.viewport.x(), top, width, 90), MUTED);
            top += 90;
        }
        contentBounds(canvas, top - contentTop(view), interactive);
    }

    private static Icon categoryIcon(ViperWorkbenchCatalog.Category category) {
        return switch (category) {
            case TONE -> Icon.EQ;
            case BASS -> Icon.EFFECTS;
            case SPACE -> Icon.ROOM;
            case DYNAMICS -> Icon.POWER;
            case DEVICE -> Icon.PRESETS;
        };
    }

    private double groupIntro(Canvas canvas, View view, double top, boolean interactive) {
        ViperPresetEditor draft = view.editor;
        String key = draft.groupKey(view.group);
        int enable = draft.fieldIndex(view.group, "enable");
        double textWidth = Math.max(1, layout.viewport.width() - 86);
        String description = ViperWorkbenchCatalog.description(key);
        String hint = enable < 0 ? "此输出段始终存在；ViPER 总链开关独立" : "单独开关只控制此效果器；总链需另行开启";
        double descriptionHeight = painter.lines(EffectsTypography.label(), description, textWidth).length
                * painter.lineHeight(EffectsTypography.label());
        double hintHeight = painter.lines(EffectsTypography.caption(), hint, textWidth).length
                * painter.lineHeight(EffectsTypography.caption());
        double height = Math.max(76, 28 + descriptionHeight + hintHeight);
        Box intro = new Box(layout.viewport.x(), top, layout.viewport.width(), height);
        painter.round(canvas, intro, 18, CONTAINER);
        painter.paragraph(canvas, EffectsTypography.label(), description, intro.x() + 16, intro.y() + 13, textWidth, TEXT);
        painter.paragraph(canvas, EffectsTypography.caption(), hint, intro.x() + 16, intro.y() + 18 + descriptionHeight, textWidth, MUTED);
        if ("dynamicEq".equals(key)) {
            menuSafeDynamicEnable(canvas, "detail.enable", new Box(intro.right() - 65, intro.y() + 22, 45, 30), draft.getBoolean(view.group, enable, false), interactive);
        } else if (enable >= 0) toggle(canvas, "detail.enable", new Box(intro.right() - 65, intro.y() + 22, 45, 30), draft.getBoolean(view.group, enable, false),
                () -> edit(() -> editor.setBooleanQuiet(view.group, enable, !editor.getBoolean(view.group, enable, false))), interactive && !busy, pageClip);
        return intro.bottom() + 12;
    }

    private void drawGroup(Canvas canvas, View view, boolean interactive) {
        ViperPresetEditor draft = view.editor;
        int gi = view.group;
        String key = draft.groupKey(gi);
        double top = groupIntro(canvas, view, contentTop(view), interactive), width = layout.viewport.width();
        boolean multiband = "multibandCompressor".equals(key);
        if (multiband) {
            int bandEnables = draft.fieldIndex(gi, "bandEnables");
            int bands = Math.max(1, Math.min(5, draft.getBooleanArray(gi, bandEnables).length));
            int activeArrayBand = Math.max(0, Math.min(bands - 1, view.arrayBand));
            if (interactive) arrayBand = activeArrayBand;
            painter.text(canvas, EffectsTypography.label(), "选择动态频段", layout.viewport.x(), top + 13, width, TEXT);
            top += 31;
            double cell = width / bands;
            for (int i = 0; i < bands; i++) {
                int next = i;
                chip(canvas, "mbc.band." + i, new Box(layout.viewport.x() + i * cell, top, cell - 7, 29), "频段 " + (i + 1), activeArrayBand == i,
                        () -> { finishDrag(true); arrayBand = next; }, interactive, pageClip);
            }
            top += 41;
        }
        int hidden = 0;
        for (int fi = 0; fi < draft.fieldCount(gi); fi++) {
            String field = draft.fieldKey(gi, fi);
            if ("enable".equals(field)) continue;
            if (ViperWorkbenchCatalog.advanced(key, field) && !view.advanced) { hidden++; continue; }
            ViperFieldKind kind = draft.fieldKind(gi, fi);
            String id = "field." + key + "." + field;
            if (draft.fieldUnused(gi, fi) || kind == ViperFieldKind.NULLABLE_LONG) {
                top = readonly(canvas, draft.fieldDisplayName(gi, fi), ViperWorkbenchCatalog.fieldHint(key, field),
                        new Box(layout.viewport.x(), top, width, 55)) + 10;
            } else if (kind == ViperFieldKind.BOOL) {
                int f = fi;
                top = booleanRow(canvas, id, draft.fieldDisplayName(gi, fi), ViperWorkbenchCatalog.fieldHint(key, field),
                        new Box(layout.viewport.x(), top, width, 64), draft.getBoolean(gi, fi, false),
                        () -> edit(() -> editor.setBooleanQuiet(gi, f, !editor.getBoolean(gi, f, false))), interactive);
                top += 10;
            } else if (kind == ViperFieldKind.INT && draft.fieldIntChoices(gi, fi) != null) {
                top = choiceRow(canvas, draft, id, new Box(layout.viewport.x(), top, width, 67), gi, fi, interactive) + 10;
            } else if (kind == ViperFieldKind.INT || kind == ViperFieldKind.FLOAT) {
                if (!draft.fieldRanged(gi, fi)) {
                    top = readonly(canvas, draft.fieldDisplayName(gi, fi), "原始配置字段，未确认可用的调节范围",
                            new Box(layout.viewport.x(), top, width, 55)) + 10;
                } else top = numberRow(canvas, draft, id, draft.fieldDisplayName(gi, fi), ViperWorkbenchCatalog.fieldHint(key, field),
                        new Box(layout.viewport.x(), top, width, 80), gi, fi, -1, interactive) + 10;
            } else if (kind == ViperFieldKind.STRING) {
                int f = fi;
                List<String> options = "kernelFile".equals(field) ? kernels : "device".equals(field) ? ddc : List.of();
                String value = draft.getString(gi, fi, "");
                Box row = new Box(layout.viewport.x(), top, width, 78);
                painter.round(canvas, row, 16, CONTAINER);
                painter.text(canvas, EffectsTypography.label(), draft.fieldDisplayName(gi, fi), row.x() + 16, row.y() + 19, width - 32, TEXT);
                button(canvas, id, new Box(row.x() + 14, row.y() + 36, width - 28, 29), value.isEmpty() ? "选择资源文件…" : value, Icon.CHEVRON,
                        () -> openFileOptions(gi, f, options, row), interactive && !busy, false, pageClip);
                top += 88;
                if (options.isEmpty()) {
                    painter.text(canvas, EffectsTypography.body(), "候选目录为空，现有导入引用保留；放入文件后刷新预设库", layout.viewport.x() + 8, top + 10, width - 16, AMBER);
                    top += 31;
                }
            } else if (kind.isArray()) {
                if (multiband && !"crossovers".equals(field)) {
                    int f = fi, band = view.arrayBand;
                    if (kind == ViperFieldKind.BOOL_ARRAY) {
                        boolean[] values = draft.getBooleanArray(gi, fi);
                        if (band >= values.length) {
                            top = readonly(canvas, draft.fieldDisplayName(gi, fi), "此预设未提供该频段的值，恢复字段默认可补齐",
                                    new Box(layout.viewport.x(), top, width, 55)) + 10;
                        } else {
                            top = booleanRow(canvas, id, draft.fieldDisplayName(gi, fi) + " · 频段 " + (band + 1), ViperWorkbenchCatalog.fieldHint(key, field),
                                    new Box(layout.viewport.x(), top, width, 64), values[band],
                                    () -> edit(() -> editor.setBooleanArrayElementQuiet(gi, f, band, !editor.getBooleanArray(gi, f)[band])), interactive) + 10;
                        }
                    } else {
                        double[] values = draft.getArray(gi, fi);
                        if (band >= values.length) {
                            top = readonly(canvas, draft.fieldDisplayName(gi, fi), "此预设未提供该频段的值，恢复字段默认可补齐",
                                    new Box(layout.viewport.x(), top, width, 55)) + 10;
                        } else top = numberRow(canvas, draft, id, draft.fieldDisplayName(gi, fi) + " · 频段 " + (band + 1), ViperWorkbenchCatalog.fieldHint(key, field),
                                new Box(layout.viewport.x(), top, width, 80), gi, fi, band, interactive) + 10;
                    }
                } else {
                    double[] values = draft.getArray(gi, fi);
                    for (int index = 0; index < values.length; index++) top = numberRow(canvas, draft, id + "." + index,
                            draft.fieldDisplayName(gi, fi) + " " + (index + 1), ViperWorkbenchCatalog.fieldHint(key, field),
                            new Box(layout.viewport.x(), top, width, 80), gi, fi, index, interactive) + 10;
                }
            }
        }
        if (hidden > 0 || view.advanced) {
            button(canvas, "detail.advanced", new Box(layout.viewport.x(), top, width, 33), view.advanced ? "收起高级参数" : "展开高级参数（" + hidden + "）", Icon.CHEVRON,
                    () -> { finishDrag(true); advanced = !advanced; }, interactive, false, pageClip);
            top += 46;
        }
        double half = (width - 8) / 2;
        button(canvas, "detail.reset", new Box(layout.viewport.x(), top, half, 33), "恢复本组默认", Icon.REFRESH,
                () -> confirm("恢复效果器默认", "仅重置「" + draft.groupDisplayName(gi) + "」的已知字段，其他效果和未知扩展字段保留。", () -> edit(() -> editor.resetGroupQuiet(gi))), interactive && !busy, false, pageClip);
        button(canvas, "detail.saveAs", new Box(layout.viewport.x() + half + 8, top, half, 33), "保存副本", Icon.COPY,
                this::openSaveAs, interactive && !busy, false, pageClip);
        contentBounds(canvas, top + 44 - contentTop(view), interactive);
    }

    private double booleanRow(Canvas canvas, String id, String title, String hint, Box row, boolean value, Runnable action, boolean interactive) {
        double titleHeight = painter.lines(EffectsTypography.label(), title, row.width() - 90).length
                * painter.lineHeight(EffectsTypography.label());
        double hintHeight = painter.lines(EffectsTypography.caption(), hint, row.width() - 90).length
                * painter.lineHeight(EffectsTypography.caption());
        double height = Math.max(row.height(), 24 + titleHeight + 5 + hintHeight);
        Box actual = new Box(row.x(), row.y(), row.width(), height);
        painter.round(canvas, actual, 16, CONTAINER);
        painter.paragraph(canvas, EffectsTypography.label(), title, actual.x() + 16, actual.y() + 12, actual.width() - 90, TEXT);
        painter.paragraph(canvas, EffectsTypography.caption(), hint, actual.x() + 16, actual.y() + 17 + titleHeight, actual.width() - 90, MUTED);
        toggle(canvas, id, new Box(actual.right() - 63, actual.y() + 17, 45, 30), value, action, interactive && !busy, pageClip);
        return actual.bottom();
    }

    private double readonly(Canvas canvas, String title, String hint, Box row) {
        double titleHeight = painter.lines(EffectsTypography.label(), title, row.width() - 32).length
                * painter.lineHeight(EffectsTypography.label());
        double hintHeight = painter.lines(EffectsTypography.caption(), hint, row.width() - 32).length
                * painter.lineHeight(EffectsTypography.caption());
        Box actual = new Box(row.x(), row.y(), row.width(), Math.max(row.height(), 26 + titleHeight + hintHeight));
        painter.round(canvas, actual, 16, SURFACE);
        painter.paragraph(canvas, EffectsTypography.label(), title, actual.x() + 16, actual.y() + 12, actual.width() - 32, TEXT);
        painter.paragraph(canvas, EffectsTypography.caption(), hint, actual.x() + 16, actual.y() + 17 + titleHeight, actual.width() - 32, MUTED);
        return actual.bottom();
    }

    private double choiceRow(Canvas canvas, ViperPresetEditor draft, String id, Box row, int gi, int fi, boolean interactive) {
        painter.round(canvas, row, 16, CONTAINER);
        String group = draft.groupKey(gi), key = draft.fieldKey(gi, fi);
        painter.text(canvas, EffectsTypography.label(), draft.fieldDisplayName(gi, fi), row.x() + 16, row.y() + 19, row.width() - 32, TEXT);
        int value = draft.getInt(gi, fi, 0);
        String name = draft.fieldIntChoiceName(gi, fi, value);
        if (name == null) name = switch (group + "." + key) {
            case "bass.mode", "bassMono.mode" -> value == 0 ? "自然低音" : value == 1 ? "Pure Bass+" : "低音炮";
            case "clarity.mode" -> value == 0 ? "自然" : value == 1 ? "Ozone" : "XHiFi";
            case "analogX.mode" -> value == 0 ? "轻度染色" : value == 1 ? "中度染色" : "强烈染色";
            default -> "档位 " + value;
        };
        button(canvas, id, new Box(row.x() + 14, row.y() + 32, row.width() - 28, 27), name, Icon.CHEVRON,
                () -> openChoices(draft, gi, fi, row), interactive && !busy, false, pageClip);
        return row.bottom();
    }

    private void openChoices(ViperPresetEditor draft, int gi, int fi, Box anchor) {
        finishDrag(true);
        menu = new ArrayList<>();
        int current = draft.getInt(gi, fi, 0);
        for (int value : draft.fieldIntChoices(gi, fi)) {
            String name = draft.fieldIntChoiceName(gi, fi, value);
            if (name == null) name = "档位 " + value;
            menu.add(new MenuItem(name, value == current ? Icon.CHECK : null, false, () -> edit(() -> editor.setNumberQuiet(gi, fi, value))));
        }
        openMenu(anchor);
    }

    private void openFileOptions(int gi, int fi, List<String> options, Box anchor) {
        finishDrag(true);
        menu = new ArrayList<>();
        String current = editor.getString(gi, fi, "");
        menu.add(new MenuItem("不使用资源文件", current.isBlank() ? Icon.CHECK : null, false, () -> edit(() -> editor.setStringQuiet(gi, fi, ""))));
        for (String file : options) menu.add(new MenuItem(file, file.equals(current) ? Icon.CHECK : null, false,
                () -> edit(() -> editor.setStringQuiet(gi, fi, file))));
        openMenu(anchor);
    }

    private double numberRow(Canvas canvas, ViperPresetEditor draft, String id, String title, String hint, Box row,
                             int gi, int fi, int index, boolean interactive) {
        String group = draft.groupKey(gi), field = draft.fieldKey(gi, fi), unit = ViperWorkbenchCatalog.unit(group, field);
        double raw = index < 0 ? draft.getNumber(gi, fi, 0) : draft.getArray(gi, fi)[index];
        double minRaw = draft.fieldMin(gi, fi), maxRaw = draft.fieldMax(gi, fi);
        if (index >= 0 && "multibandCompressor".equals(group) && "crossovers".equals(field)) {
            double[] frequencies = draft.getArray(gi, fi);
            if (index > 0) minRaw = Math.max(minRaw, frequencies[index - 1] + 1);
            if (index + 1 < frequencies.length) maxRaw = Math.min(maxRaw, frequencies[index + 1] - 1);
        }
        if (!Double.isFinite(raw) || minRaw > maxRaw) {
            return readonly(canvas, title, "参数布局无效，恢复字段或本组默认值后再编辑", row);
        }
        double a = ViperWorkbenchCatalog.toDisplay(group, field, minRaw), b = ViperWorkbenchCatalog.toDisplay(group, field, maxRaw);
        double min = Math.min(a, b), max = Math.max(a, b), value = ViperWorkbenchCatalog.toDisplay(group, field, raw);
        boolean integer = draft.fieldKind(gi, fi) == ViperFieldKind.INT || draft.fieldKind(gi, fi) == ViperFieldKind.INT_ARRAY;
        DoubleConsumer write = display -> {
            double stored = ViperWorkbenchCatalog.fromDisplay(group, field, display);
            if (index < 0) editor.setNumberQuiet(gi, fi, stored);
            else {
                if ("multibandCompressor".equals(group) && "crossovers".equals(field)) {
                    double[] values = editor.getArray(gi, fi);
                    if (index > 0 && stored <= values[index - 1] || index + 1 < values.length && stored >= values[index + 1]) throw new IllegalArgumentException("分频点必须严格递增");
                }
                editor.setArrayElementQuiet(gi, fi, index, stored);
            }
        };
        Numeric number = new Numeric(title, unit, min, max, integer, () -> {
            double stored = index < 0 ? editor.getNumber(gi, fi, 0) : editor.getArray(gi, fi)[index];
            return ViperWorkbenchCatalog.toDisplay(group, field, stored);
        }, write);
        final boolean editable = interactive && !busy;
        String displayValue = format(value, integer) + (unit.isBlank() ? "" : " " + unit);
        double valueWidth = Math.max(102, EffectsTypography.label().getStringWidthD(displayValue) + 30);
        double titleWidth = EffectsTypography.label().getStringWidthD(title);
        boolean stacked = row.width() < 420 || titleWidth + valueWidth + 62 > row.width();
        valueWidth = Math.min(valueWidth, Math.max(1, row.width() - 56));
        double titleSpace = stacked ? row.width() - 60 : row.width() - valueWidth - 62;
        double titleHeight = painter.lines(EffectsTypography.label(), title, titleSpace).length
                * painter.lineHeight(EffectsTypography.label());
        double hintTop = 16 + titleHeight;
        double hintHeight = painter.lines(EffectsTypography.caption(), hint, row.width() - 32).length
                * painter.lineHeight(EffectsTypography.caption());
        double valueTop = stacked ? hintTop + hintHeight + 7 : 5;
        double trackTop = stacked ? valueTop + 35 : Math.max(52, hintTop + hintHeight + 9);
        double height = Math.max(row.height(), trackTop + 32);
        Box actual = new Box(row.x(), row.y(), row.width(), height);
        painter.round(canvas, actual, 16, CONTAINER);
        painter.paragraph(canvas, EffectsTypography.label(), title, actual.x() + 16, actual.y() + 10, titleSpace, TEXT);
        Box valueBox = new Box(actual.right() - valueWidth - 39, actual.y() + valueTop, valueWidth, 27);
        button(canvas, id + ".value", valueBox, displayValue, null,
                () -> openNumber(number), editable, false, pageClip);
        iconButton(canvas, id + ".reset", new Box(actual.right() - 34, actual.y() + 4, 27, 27), Icon.REFRESH,
                () -> edit(() -> editor.resetFieldQuiet(gi, fi)), editable, false, pageClip);
        painter.paragraph(canvas, EffectsTypography.caption(), hint, actual.x() + 16, actual.y() + hintTop, actual.width() - 32, MUTED);
        Box track = new Box(actual.x() + 16, actual.y() + trackTop, actual.width() - 32, 23);
        slider(canvas, id + ".slider", track, value, min, max, ViperWorkbenchCatalog.logarithmic(group, field), write, editable);
        return actual.bottom();
    }

    private void slider(Canvas canvas, String id, Box box, double value, double min, double max, boolean logarithmic,
                        DoubleConsumer write, boolean interactive) {
        boolean log = logarithmic && min > 0;
        double t = log ? Math.log(Math.max(min, value) / min) / Math.log(max / min) : (value - min) / Math.max(1e-9, max - min);
        t = Math.max(0, Math.min(1, t));
        double feedback = Math.max(0, press(id, interactive));
        double active = Math.max(0, Math.min(1, shape(id + ".engaged", drag != null && id.equals(drag.id))));
        double x = box.x() + t * box.width();
        double gap = 5, trackHeight = 8 + active * 2;
        painter.round(canvas, new Box(box.x(), box.centerY() - trackHeight * .5, Math.max(0, x - box.x() - gap), trackHeight), 4, PRIMARY);
        painter.round(canvas, new Box(x + gap, box.centerY() - trackHeight * .5, Math.max(0, box.right() - x - gap), trackHeight), 4, OUTLINE);
        painter.circle(canvas, x, box.centerY(), 11 + active * 2, alpha(PRIMARY, hover(id, box, interactive) * .08));
        painter.round(canvas, new Box(x - 2.5 - feedback, box.centerY() - 9 - active, 5 + feedback * 2, 18 + active * 2), 3, PRIMARY);
        addHit(id, box, pageClip, () -> { }, pct -> write.accept(log ? min * Math.pow(max / min, pct) : min + (max - min) * pct),
                false, false, null, interactive && !busy);
    }

    private static String format(double value, boolean integer) {
        if (integer) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, Math.abs(value) >= 100 ? "%.0f" : Math.abs(value) >= 10 ? "%.1f" : "%.2f", value);
    }

    private void menuSafeDynamicEnable(Canvas canvas, String id, Box box, boolean on, boolean interactive) {
        toggle(canvas, id, box, on, () -> edit(() -> new ViperDynamicEqModel(editor).setEnabledQuiet(!on)), interactive && !busy, pageClip);
    }

    private void drawDynamicEq(Canvas canvas, View view, boolean interactive) {
        ViperDynamicEqModel model;
        try { model = new ViperDynamicEqModel(view.editor); }
        catch (IllegalArgumentException invalid) {
            double top = contentTop(view);
            section(canvas, "动态 EQ 参数布局无法安全编辑", errorMessage(invalid), top, layout.viewport.width());
            button(canvas, "deq.recover", new Box(layout.viewport.x(), top + 55, 200, 34), "恢复动态 EQ 默认值", Icon.REFRESH,
                    () -> confirm("恢复动态 EQ", "将恢复频段数和完整参数数组。仅修改此效果器，其他效果保留。", () -> edit(() -> editor.resetGroupQuiet(view.group))), interactive && !busy, false, pageClip);
            contentBounds(canvas, 112, interactive);
            return;
        }
        int count = model.bandCount();
        double top = groupIntro(canvas, view, contentTop(view), interactive), width = layout.viewport.width();
        painter.text(canvas, EffectsTypography.label(), "动态频段 · " + count + " 段", layout.viewport.x(), top + 14, width - 110, TEXT);
        button(canvas, "deq.count", new Box(layout.viewport.right() - 103, top, 103, 28), "修改段数", Icon.CHEVRON,
                () -> {
                    menu = new ArrayList<>();
                    for (int i = 1; i <= 10; i++) {
                        int next = i;
                        menu.add(new MenuItem(i + " 个频段", i == count ? Icon.CHECK : null, false,
                                () -> edit(() -> new ViperDynamicEqModel(editor).setBandCountQuiet(next))));
                    }
                    openMenu(new Box(layout.viewport.right() - 103, layout.viewport.y(), 103, 28));
                }, interactive && !busy, false, pageClip);
        top += 40;
        int activeDynamicBand = Math.max(0, Math.min(Math.max(0, count - 1), view.dynamicBand));
        if (interactive) dynamicBand = activeDynamicBand;
        int columns = Math.max(3, Math.min(10, (int) (width / 61)));
        for (int i = 0; i < count; i++) {
            int band = i;
            chip(canvas, "deq.band." + i, new Box(layout.viewport.x() + (i % columns) * width / columns, top + (i / columns) * 37, width / columns - 7, 29),
                    "段 " + (i + 1), i == activeDynamicBand, () -> { finishDrag(true); dynamicBand = band; }, interactive, pageClip);
        }
        top += ((count + columns - 1) / columns) * 37 + 12;
        if (count <= 0) {
            readonly(canvas, "此预设没有活动动态频段", "选择 1–10 段后生成完整的安全参数数组", new Box(layout.viewport.x(), top, width, 68));
            contentBounds(canvas, top + 80 - contentTop(view), interactive);
            return;
        }
        int band = activeDynamicBand;
        String[] fields = {"freqs", "qs", "gains", "thresholds", "attacks", "releases"};
        String[] titles = {"中心频率", "Q / Shelf 坡度", "目标增益", "输入 RMS 门限", "启动时间", "释放时间"};
        double[] mins = {20, .5, -12, -80, 1, 10}, maxs = {20000, model.filterType(band) == 0 ? 8 : 1, 12, 0, 100, 500};
        Box types = new Box(layout.viewport.x(), top, width, 66);
        painter.round(canvas, types, 16, CONTAINER);
        painter.text(canvas, EffectsTypography.label(), "滤波类型", types.x() + 16, types.y() + 18, width - 32, TEXT);
        String[] labels = {"Peak", "Low shelf", "High shelf"};
        for (int i = 0; i < 3; i++) {
            int next = i;
            chip(canvas, "deq.type." + i, new Box(types.x() + 12 + i * (width - 24) / 3, types.y() + 31, (width - 24) / 3 - 6, 27),
                    labels[i], model.filterType(band) == i, () -> edit(() -> new ViperDynamicEqModel(editor).setFilterTypeQuiet(band, next)), interactive && !busy, pageClip);
        }
        top += 77;
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i], title = titles[i], unit = ViperWorkbenchCatalog.unit("dynamicEq", field);
            double min = mins[i], max = maxs[i], value = model.value(field, band);
            DoubleConsumer write = v -> new ViperDynamicEqModel(editor).setValueQuiet(field, band, v);
            Numeric number = new Numeric(title, unit, min, max, "freqs".equals(field), () -> new ViperDynamicEqModel(editor).value(field, band), write);
            String hint = ViperWorkbenchCatalog.fieldHint("dynamicEq", field);
            String valueText = format(value, "freqs".equals(field)) + (unit.isBlank() ? "" : " " + unit);
            double valueWidth = Math.min(width - 32, Math.max(112, EffectsTypography.label().getStringWidthD(valueText) + 30));
            boolean stacked = width < 420 || EffectsTypography.label().getStringWidthD(title) + valueWidth + 48 > width;
            double hintHeight = painter.lines(EffectsTypography.caption(), hint, width - 32).length
                    * painter.lineHeight(EffectsTypography.caption());
            double valueTop = stacked ? 39 + hintHeight : 5;
            double trackTop = stacked ? valueTop + 36 : Math.max(55, 42 + hintHeight);
            Box row = new Box(layout.viewport.x(), top, width, Math.max(79, trackTop + 32));
            painter.round(canvas, row, 16, CONTAINER);
            painter.text(canvas, EffectsTypography.label(), title, row.x() + 16, row.y() + 19,
                    stacked ? width - 32 : width - valueWidth - 48, TEXT);
            button(canvas, "deq." + field + ".value", new Box(row.right() - valueWidth - 16, row.y() + valueTop, valueWidth, 27), valueText, null,
                    () -> openNumber(number), interactive && !busy, false, pageClip);
            painter.paragraph(canvas, EffectsTypography.caption(), hint, row.x() + 16, row.y() + 32, width - 32, MUTED);
            slider(canvas, "deq." + field, new Box(row.x() + 16, row.y() + trackTop, width - 32, 22), value, min, max, "freqs".equals(field), write, interactive);
            top = row.bottom() + 10;
        }
        button(canvas, "deq.reset", new Box(layout.viewport.x(), top, width, 33), "恢复动态 EQ 默认值", Icon.REFRESH,
                () -> confirm("恢复动态 EQ", "将恢复频段数、所有频段数组及开关，其他效果保留。", () -> edit(() -> editor.resetGroupQuiet(view.group))), interactive && !busy, false, pageClip);
        contentBounds(canvas, top + 44 - contentTop(view), interactive);
    }

    private void drawAcoustics(Canvas canvas, View view, boolean interactive) {
        double top = contentTop(view), width = layout.viewport.width();
        double iconSize = layout.surface.width() < 500 ? 40 : 52;
        double textWidth = Math.max(1, width - 92);
        String heading = "让音乐置身于世界", description = "独立的输出尾链 · ViPER 关闭时也可使用";
        double headingHeight = painter.lines(EffectsTypography.heading(), heading, textWidth).length * painter.lineHeight(EffectsTypography.heading());
        double descriptionHeight = painter.lines(EffectsTypography.body(), description, textWidth).length * painter.lineHeight(EffectsTypography.body());
        Box hero = new Box(layout.viewport.x(), top, width, Math.max(104, 32 + headingHeight + descriptionHeight));
        painter.round(canvas, hero, 26, PRIMARY_CONTAINER);
        painter.emblem(canvas, Icon.ROOM, new Box(hero.x() + 16, hero.y() + 20, iconSize, iconSize), 1, alpha(PRIMARY, .12), PRIMARY);
        painter.paragraph(canvas, EffectsTypography.heading(), heading, hero.x() + 76, hero.y() + 17, textWidth, TEXT);
        painter.paragraph(canvas, EffectsTypography.body(), description, hero.x() + 76, hero.y() + 23 + headingHeight, textWidth, MUTED);
        top = hero.bottom() + 14;
        top = environmentRow(canvas, "acoustics.water", "水面之下", "浸入水中时让音乐变得低沉、朦胧", Icon.WATER,
                new Box(layout.viewport.x(), top, width, 76), submersion, () -> flipAcousticsPart("submersion"), interactive) + 10;
        top = environmentRow(canvas, "acoustics.enclosure", "封闭空间", "屋顶与墙壁的围合改变反射与空间感", Icon.ROOM,
                new Box(layout.viewport.x(), top, width, 76), enclosure, () -> flipAcousticsPart("enclosure"), interactive) + 10;
        top = environmentRow(canvas, "acoustics.rain", "降水天气", "雨雪与雷暴时降低音乐存在感，给环境声留空间", Icon.RAIN,
                new Box(layout.viewport.x(), top, width, 76), rainfall, () -> flipAcousticsPart("rainfall"), interactive) + 18;
        Box row = new Box(layout.viewport.x(), top, width, 88);
        painter.round(canvas, row, 18, CONTAINER);
        painter.text(canvas, EffectsTypography.section(), "整体强度", row.x() + 16, row.y() + 23, width - 106, TEXT);
        button(canvas, "acoustics.strength.value", new Box(row.right() - 81, row.y() + 8, 65, 28), strength + "%", null,
                () -> openNumber(new Numeric("场景声学强度", "%", 0, 100, true, () -> strength, v -> strength = (int) Math.round(v))), interactive && !busy, false, pageClip);
        painter.text(canvas, EffectsTypography.body(), "0% 为最弱，100% 为完整场景响应", row.x() + 16, row.y() + 45, width - 32, MUTED);
        slider(canvas, "acoustics.strength", new Box(row.x() + 17, row.y() + 58, width - 34, 24), strength, 0, 100, false,
                v -> strength = (int) Math.round(v), interactive);
        top += 105;
        top = readonly(canvas, "响应时序", "环境采样与参数平滑有自然延迟；保存后按新设置响应，不改变播放队列。",
                new Box(layout.viewport.x(), top, width, 59));
        contentBounds(canvas, top + 12 - contentTop(view), interactive);
    }

    private double environmentRow(Canvas canvas, String id, String title, String description, Icon icon, Box row,
                                  boolean on, Runnable action, boolean interactive) {
        double textWidth = Math.max(1, row.width() - 132);
        double titleHeight = painter.lines(EffectsTypography.section(), title, textWidth).length * painter.lineHeight(EffectsTypography.section());
        double descriptionHeight = painter.lines(EffectsTypography.body(), description, textWidth).length * painter.lineHeight(EffectsTypography.body());
        double height = Math.max(row.height(), 30 + titleHeight + descriptionHeight);
        Box actual = new Box(row.x(), row.y(), row.width(), height);
        painter.round(canvas, actual, 20, CONTAINER);
        painter.emblem(canvas, icon, new Box(actual.x() + 14, actual.y() + 19, 32, 32), selected(id + ".emblem", on),
                on ? PRIMARY_CONTAINER : SURFACE, on ? PRIMARY : MUTED);
        painter.paragraph(canvas, EffectsTypography.section(), title, actual.x() + 58, actual.y() + 13, textWidth, TEXT);
        painter.paragraph(canvas, EffectsTypography.body(), description, actual.x() + 58, actual.y() + 18 + titleHeight, textWidth, MUTED);
        toggle(canvas, id, new Box(actual.right() - 65, actual.y() + 20, 46, 30), on, action, interactive && !busy, pageClip);
        return actual.bottom();
    }

    private void flipAcousticsPart(String field) {
        boolean next = switch (field) { case "submersion" -> !submersion; case "enclosure" -> !enclosure; default -> !rainfall; };
        stateJob(() -> {
            switch (field) { case "submersion" -> AcousticsSettings.setSubmersion(next); case "enclosure" -> AcousticsSettings.setEnclosure(next); default -> AcousticsSettings.setRainfall(next); }
            AcousticsSettings.verifyPresetPersistence();
            return next;
        }, value -> { switch (field) { case "submersion" -> submersion = value; case "enclosure" -> enclosure = value; default -> rainfall = value; } }, "场景响应项已保存");
    }

    private void drawAmbient(Canvas canvas, View view, boolean interactive) {
        double top = contentTop(view), width = layout.viewport.width();
        double textWidth = Math.max(1, width - 92);
        String heading = "为环境挑选下一种心情", description = "群系、天气、敌情与深浅决定选曲方向";
        String warning = "使用当前音乐平台搜索，可能替换播放列表";
        double headingHeight = painter.lines(EffectsTypography.heading(), heading, textWidth).length * painter.lineHeight(EffectsTypography.heading());
        double descriptionHeight = painter.lines(EffectsTypography.body(), description, textWidth).length * painter.lineHeight(EffectsTypography.body());
        double warningHeight = painter.lines(EffectsTypography.caption(), warning, textWidth).length * painter.lineHeight(EffectsTypography.caption());
        Box hero = new Box(layout.viewport.x(), top, width, Math.max(122, 40 + headingHeight + descriptionHeight + warningHeight));
        painter.round(canvas, hero, 26, PRIMARY_CONTAINER);
        painter.emblem(canvas, Icon.AMBIENT, new Box(hero.x() + 16, hero.y() + 23, 48, 48), 1, alpha(PRIMARY, .12), PRIMARY);
        painter.paragraph(canvas, EffectsTypography.heading(), heading, hero.x() + 76, hero.y() + 17, textWidth, TEXT);
        painter.paragraph(canvas, EffectsTypography.body(), description, hero.x() + 76, hero.y() + 24 + headingHeight, textWidth, MUTED);
        painter.paragraph(canvas, EffectsTypography.caption(), warning, hero.x() + 76, hero.y() + 29 + headingHeight + descriptionHeight, textWidth, AMBER);
        top = hero.bottom() + 14;
        Box stable = new Box(layout.viewport.x(), top, width, 91);
        painter.round(canvas, stable, 18, CONTAINER);
        painter.text(canvas, EffectsTypography.section(), "环境稳定期", stable.x() + 16, stable.y() + 23, width - 127, TEXT);
        button(canvas, "ambient.stable.value", new Box(stable.right() - 105, stable.y() + 8, 88, 28), (stableMs / 1000) + " 秒", null,
                () -> openNumber(new Numeric("环境稳定多久才切歌", "秒", 5, 120, true, () -> stableMs / 1000.0, v -> stableMs = (int) Math.round(v * 1000))), interactive && !busy, false, pageClip);
        painter.text(canvas, EffectsTypography.body(), "越短越灵敏，越长越少打断当前歌曲", stable.x() + 16, stable.y() + 46, width - 32, MUTED);
        slider(canvas, "ambient.stable", new Box(stable.x() + 17, stable.y() + 60, width - 34, 24), stableMs / 1000.0, 5, 120, false,
                v -> stableMs = (int) Math.round(v) * 1000, interactive);
        top += 108;
        painter.text(canvas, EffectsTypography.label(), "快速选择", layout.viewport.x(), top + 12, width, TEXT);
        top += 29;
        int[] times = {5, 15, 30, 60};
        for (int i = 0; i < times.length; i++) {
            int seconds = times[i];
            chip(canvas, "ambient.quick." + seconds, new Box(layout.viewport.x() + i * width / 4, top, width / 4 - 7, 29), seconds + " 秒", stableMs == seconds * 1000,
                    () -> { stableMs = seconds * 1000; saveEnvironment(Page.AMBIENT); }, interactive && !busy, pageClip);
        }
        top += 49;
        top = readonly(canvas, "不会抢占房间控制", "一起听、派对、直播与外部播放镜像中让路，不自动替房间点歌。",
                new Box(layout.viewport.x(), top, width, 62)) + 12;
        top = readonly(canvas, "与场景声学不同", "此页调整的是选曲行为；声学页只处理声音，两者开关互不依赖。",
                new Box(layout.viewport.x(), top, width, 62));
        contentBounds(canvas, top + 12 - contentTop(view), interactive);
    }

    private String targetName() { return editor == null ? "读取中" : editor.targetName(); }
    private static double motionMs(double milliseconds) { return milliseconds * 3 / SheetClock.speed(); }
    private void message(String text, NoticeTone tone) {
        notice = text;
        noticeTone = tone;
    }

    private void requestLoad() {
        long ticket = ++operation;
        busy = true;
        IO.execute(() -> {
            try {
                ViperRuntime.syncFromDisk();
                Loaded loaded = load();
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!hostAlive || ticket != operation) return;
                    applyLoaded(loaded, true);
                    busy = loading = false;
                    message("配置已就绪 · 修改后自动保存", NoticeTone.INFO);
                });
            } catch (Exception failure) { postFailure(ticket, failure); }
        });
    }

    private static Loaded load() {
        String selected = ViperRuntime.activePresetName();
        if (selected != null && ViperRuntime.exportPresetJson(selected) == null) {
            throw new IllegalStateException("当前选用预设无法读取：" + selected + "；不会用空白方案覆盖它");
        }
        ViperPresetEditor draft = selected == null ? ViperPresetEditor.newScratch() : ViperPresetEditor.overActive();
        draft.useCheckedRuntimeHooks();
        return new Loaded(draft, ViperRuntime.listPresetNames(), draft.kernelFiles(), draft.ddcDevices(),
                ViperRuntime.isEnabled(), ViperRuntime.activePresetName(), AcousticsSettings.isEnabled(), AcousticsSettings.isSubmersion(),
                AcousticsSettings.isEnclosure(), AcousticsSettings.isRainfall(), AcousticsSettings.strengthPercent(), AmbientSettings.isEnabled(), AmbientSettings.stableMs());
    }

    private void applyLoaded(Loaded loaded, boolean replaceEditor) {
        presets = List.copyOf(loaded.presets);
        kernels = List.copyOf(loaded.kernels);
        ddc = List.copyOf(loaded.ddc);
        enabled = loaded.enabled;
        active = loaded.active;
        acoustics = loaded.acoustics;
        submersion = loaded.submersion;
        enclosure = loaded.enclosure;
        rainfall = loaded.rainfall;
        strength = loaded.strength;
        ambient = loaded.ambient;
        stableMs = loaded.stableMs;
        if (replaceEditor) replaceEditor(loaded.editor);
    }

    private void replaceEditor(ViperPresetEditor next) {
        editor = next;
        editorEpoch++;
        editRevision = 0;
        queuedRevision = -1;
        undo.clear();
        redo.clear();
        saveError = null;
        selectedBand = dynamicBand = arrayBand = 0;
    }

    private <T> void stateJob(Job<T> action, Consumer<T> accept, String success) {
        if (busy || loading) return;
        finishDrag(true);
        if (pendingSaves > 0) { message("正在保存上一项修改，请稍候…", NoticeTone.INFO); return; }
        long ticket = ++operation;
        busy = true;
        IO.execute(() -> {
            try {
                T result = action.run();
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!hostAlive || ticket != operation) return;
                    busy = false;
                    accept.accept(result);
                    message(success, NoticeTone.SUCCESS);
                });
            } catch (Exception failure) { postFailure(ticket, failure); }
        });
    }

    private void postFailure(long ticket, Exception failure) {
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!hostAlive || ticket != operation) return;
            busy = loading = importing = false;
            message("操作失败：" + errorMessage(failure), NoticeTone.ERROR);
        });
    }

    private static String errorMessage(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }

    private void edit(Runnable action) {
        if (editor == null || busy || loading) return;
        finishDrag(true);
        ViperPresetEditor before = editor.copy();
        try {
            action.run();
            if (!different(before, editor)) return;
            remember(before);
            editRevision++;
            queuedRevision = -1;
            commitEditor();
        } catch (IllegalArgumentException failure) {
            editor = before;
            message(errorMessage(failure), NoticeTone.ERROR);
        }
    }

    private static boolean different(ViperPresetEditor before, ViperPresetEditor after) {
        for (int i = 0; i < before.groupCount(); i++) {
            if (!before.presetGroup(before.groupKey(i)).equals(after.presetGroup(after.groupKey(i)))) return true;
        }
        return false;
    }

    private void remember(ViperPresetEditor before) {
        undo.addLast(before);
        while (undo.size() > HISTORY_LIMIT) undo.removeFirst();
        redo.clear();
    }

    private void commitEditor() {
        if (editor == null || editRevision == queuedRevision) return;
        final ViperPresetEditor snapshot = editor.copy();
        final long revision = editRevision, epoch = editorEpoch;
        queuedRevision = revision;
        pendingSaves++;
        IO.execute(() -> {
            try {
                snapshot.persistChecked();
                List<String> names = ViperRuntime.listPresetNames();
                String selected = ViperRuntime.activePresetName();
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!hostAlive) return;
                    pendingSaves = Math.max(0, pendingSaves - 1);
                    if (epoch != editorEpoch) return;
                    editor.acknowledgeCommit(snapshot);
                    presets = List.copyOf(names);
                    active = selected;
                    if (revision == editRevision) {
                        saveError = null;
                        message(enabled ? "已保存并应用 ·「" + targetName() + "」" : "已保存 · 开启 ViPER 总链后生效", enabled ? NoticeTone.SUCCESS : NoticeTone.INFO);
                    }
                });
            } catch (Exception failure) {
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!hostAlive) return;
                    pendingSaves = Math.max(0, pendingSaves - 1);
                    if (epoch == editorEpoch && revision == editRevision) saveError = errorMessage(failure);
                });
            }
        });
    }

    private void undo() {
        finishDrag(true);
        if (undo.isEmpty() || busy || editor == null) return;
        redo.addLast(editor.copy());
        editor = undo.removeLast();
        editRevision++;
        commitEditor();
    }

    private void redo() {
        finishDrag(true);
        if (redo.isEmpty() || busy || editor == null) return;
        undo.addLast(editor.copy());
        editor = redo.removeLast();
        editRevision++;
        commitEditor();
    }

    private void flipViper() {
        final boolean next = !enabled;
        final String preset = active;
        stateJob(() -> { ViperRuntime.applyState(next, preset); return ViperRuntime.isEnabled(); },
                value -> enabled = value, next ? "ViPER 总链已开启" : "ViPER 总链已关闭 · 参数保留");
    }

    private void selectPreset(String name) {
        closeMenu();
        final boolean on = enabled;
        stateJob(() -> {
            if (name != null && ViperRuntime.exportPresetJson(name) == null) throw new IllegalArgumentException("预设不可读取：" + name);
            ViperRuntime.applyState(on, name);
            return load();
        }, value -> applyLoaded(value, true), name == null ? "已解除当前选择" : "已选用「" + name + "」" + (on ? " · 已应用" : " · 开启总链后生效"));
    }

    private void flipEnvironment(Page page) {
        if (page == Page.ACOUSTICS) {
            final boolean next = !acoustics;
            stateJob(() -> { AcousticsSettings.setEnabled(next); AcousticsSettings.verifyPresetPersistence(); return AcousticsSettings.isEnabled(); },
                    value -> acoustics = value, next ? "场景声学已开启" : "场景声学已关闭");
        } else {
            final boolean next = !ambient;
            stateJob(() -> { AmbientSettings.setEnabled(next); verifyAmbientPersistence(); return AmbientSettings.isEnabled(); },
                    value -> { ambient = value; if (value) AmbientMusicController.reset(); }, next ? "环境选曲已开启 · 开始等待环境稳定" : "环境选曲已关闭");
        }
    }

    private static void verifyAmbientPersistence() throws java.io.IOException {
        com.google.gson.JsonObject stored = com.muoniumplayer.core.settings.JsonConfigStorage.readObject(
                com.muoniumplayer.core.settings.ConfigPaths.AMBIENT_DJ, new com.google.gson.Gson());
        if (stored == null || !stored.has("enabled") || !stored.has("stableMs")
                || stored.get("enabled").getAsBoolean() != AmbientSettings.isEnabled()
                || stored.get("stableMs").getAsInt() != AmbientSettings.stableMs()) {
            throw new java.io.IOException("环境选曲配置未成功落盘");
        }
    }

    private void saveEnvironment(Page page) {
        final int value = page == Page.ACOUSTICS ? strength : stableMs;
        IO.execute(() -> {
            try {
                if (page == Page.ACOUSTICS) { AcousticsSettings.setStrengthPercent(value); AcousticsSettings.verifyPresetPersistence(); }
                else { AmbientSettings.setStableMs(value); verifyAmbientPersistence(); }
                MultiThreadingUtil.runOnMainThread(() -> { if (hostAlive) message("设置已更新 · 环境采样按新参数响应", NoticeTone.SUCCESS); });
            } catch (Exception failure) {
                MultiThreadingUtil.runOnMainThread(() -> { if (hostAlive) message("设置保存失败：" + errorMessage(failure), NoticeTone.ERROR); });
            }
        });
    }

    private void browseForPreset() {
        if (importing || busy || loading) return;
        finishDrag(true);
        importing = true;
        message("选择 ViPER 预设文件 · .json / .xml", NoticeTone.INFO);
        Thread picker = new Thread(() -> {
            final String path;
            try {
                Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
                Class<?> pointers = Class.forName("org.lwjgl.PointerBuffer");
                Object result = dialogs.getMethod("tinyfd_openFileDialog", CharSequence.class, CharSequence.class,
                        pointers, CharSequence.class, boolean.class).invoke(null, "选择 ViPER 预设文件", "", null, null, Boolean.FALSE);
                path = result == null ? null : result.toString();
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!hostAlive) return;
                    importing = false;
                    message("文件选择器不可用 · 放入 config/muonium/viper/presets 后刷新", NoticeTone.ERROR);
                });
                return;
            }
            if (!hostAlive) return;
            if (path == null || path.isBlank()) {
                MultiThreadingUtil.runOnMainThread(() -> { if (hostAlive) { importing = false; message("已取消导入", NoticeTone.INFO); } });
                return;
            }
            IO.execute(() -> {
                if (!hostAlive) return;
                try {
                    com.muoniumplayer.core.viper.ViperPreset imported = EffectsPresetFiles.read(new File(path.trim()));
                    String name = ViperRuntime.importPresetJson(imported.json(), imported.name());
                    List<String> names = ViperRuntime.listPresetNames();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        if (!hostAlive) return;
                        importing = false;
                        presets = List.copyOf(names);
                        message("已导入「" + name + "」 · 选用后生效，不中断当前编辑", NoticeTone.SUCCESS);
                    });
                } catch (Exception failure) {
                    MultiThreadingUtil.runOnMainThread(() -> { if (hostAlive) { importing = false; message("导入失败：" + errorMessage(failure), NoticeTone.ERROR); } });
                }
            });
        }, "MuoniumPlayer-ViperPresetPicker");
        picker.setDaemon(true);
        picker.start();
    }

    private void focus(TextField field, Box box) {
        finishDrag(true);
        if (field == search) input.setFocused(false); else search.setFocused(false);
        field.setFocused(true);
        field.setPosition(box.x() + 12, box.y());
        field.setBounds(Math.max(1, box.width() - 24), box.height());
        field.mouseClicked(mouseX, mouseY, 0);
    }

    private void openMenu(Box anchor) {
        finishDrag(true);
        search.setFocused(false);
        pressedTarget = null;
        retiringMenu = null;
        menuAnchor = anchor;
        menuScroll.reset();
        menuProgress.snap(0);
        menuProgress.retarget(1, now, reducedMotion ? 90 : motionMs(150));
        menuOffset.snap(reducedMotion ? 0 : -10);
        menuOffset.retarget(0, now, spatialTime());
    }

    private void closeMenu() {
        if (menu == null) return;
        EffectsMotion opacity = menuProgress.copy();
        EffectsMotion.Spring offset = menuOffset.copy();
        opacity.retarget(0, now, reducedMotion ? 90 : motionMs(120));
        offset.retarget(reducedMotion ? 0 : -6, now, spatialTime());
        retiringMenu = new Popup(List.copyOf(menu), menuAnchor, menuScroll.value(now), opacity, offset);
        menu = null;
        pressedTarget = null;
        hits.clear();
    }

    private Box menuBox() { return menuBox(menu, menuAnchor); }

    private Box menuBox(List<MenuItem> items, Box anchor) {
        double width = Math.min(400, Math.max(1, layout.surface.width() - 32));
        double height = Math.min(items.size() * 41 + 12, Math.max(40, layout.surface.height() - 48));
        double x = Math.max(layout.surface.x() + 16, Math.min(anchor.x(), layout.surface.right() - width - 16));
        double y = Math.max(layout.surface.y() + 16, Math.min(anchor.bottom() + 4, layout.surface.bottom() - height - 16));
        return new Box(x, y, width, height);
    }

    private void drawMenu(Canvas canvas) {
        hits.clear();
        menuScroll.bounds(menu.size() * 41 + 12, menuBox().height(), now);
        drawPopup(canvas, menu, menuAnchor, menuScroll.value(now), menuProgress.value(now), menuOffset.value(now), true);
    }

    private void drawPopup(Canvas canvas, List<MenuItem> items, Box anchor, double scroll,
                           double opacity, double offset, boolean interactive) {
        if (opacity <= .003) return;
        Box box = menuBox(items, anchor).offset(0, offset);
        painter.round(canvas, box.offset(0, 4), 20, alpha(0xFF000000, opacity * .18));
        int saved = canvas.saveLayerAlpha(EffectsPaint.rect(box), (int) Math.round(opacity * 255));
        boolean wasLive = paintingLive;
        paintingLive = interactive;
        canvas.clipRRect(io.github.humbleui.types.RRect.makeXYWH((float) box.x(), (float) box.y(),
                (float) box.width(), (float) box.height(), 20), true);
        try {
            painter.round(canvas, box, 20, HIGH);
            painter.outline(canvas, box.inset(.5), 19.5, 1, alpha(OUTLINE, .45));
            for (int i = 0; i < items.size(); i++) {
                MenuItem item = items.get(i);
                Box row = new Box(box.x() + 6, box.y() + 6 + i * 41 - scroll, box.width() - 12, 38);
                double h = hover("menu." + i, row, interactive), p = press("menu." + i, interactive);
                if (h + p > .01) painter.round(canvas, row.inset(Math.max(0, p)), 14,
                        alpha(item.danger ? ERROR : PRIMARY, h * .08 + Math.max(0, p) * .08));
                if (item.icon != null) painter.icon(canvas, item.icon, new Box(row.x() + 10, row.centerY() - 9, 18, 18), item.danger ? ERROR : PRIMARY);
                painter.text(canvas, EffectsTypography.body(), item.label, row.x() + 38, row.centerY(), row.width() - 50, item.danger ? ERROR : TEXT);
                addHit("menu." + i, row, box, () -> { closeMenu(); item.action.run(); }, null, false, false, null, interactive && opacity > .9);
            }
        } finally {
            paintingLive = wasLive;
            canvas.restoreToCount(saved);
        }
    }

    private void confirm(String title, String body, Runnable action) {
        finishDrag(true);
        closeMenu();
        search.setFocused(false);
        dialogTitle = title;
        dialogBody = body;
        dialogAction = action;
        numeric = null;
        naming = false;
        inputError = "";
        dialogScroll.reset();
        revealDialog();
    }

    private void openNumber(Numeric value) {
        if (busy || editor == null) return;
        finishDrag(true);
        closeMenu();
        search.setFocused(false);
        dialogTitle = value.title;
        dialogBody = "允许范围：" + format(value.min, value.integer) + " – " + format(value.max, value.integer) + " " + value.unit;
        numeric = value;
        naming = false;
        dialogAction = null;
        inputError = "";
        input.setText(format(value.value.getAsDouble(), value.integer));
        input.setFocused(true);
        input.selectAll();
        dialogScroll.reset();
        revealDialog();
    }

    private void openSaveAs() {
        if (busy || editor == null) return;
        finishDrag(true);
        closeMenu();
        search.setFocused(false);
        dialogTitle = "保存为独立方案";
        dialogBody = "保存全部效果参数并选用新方案。原文件保留，不覆盖同名文件。";
        numeric = null;
        naming = true;
        dialogAction = null;
        inputError = "";
        input.setText(targetName() + " 副本");
        input.setFocused(true);
        input.selectAll();
        dialogScroll.reset();
        revealDialog();
    }

    private void revealDialog() {
        pressedTarget = null;
        retiringDialog = null;
        dialogProgress.snap(0);
        dialogProgress.retarget(1, now, reducedMotion ? 90 : motionMs(170));
        dialogOffset.snap(reducedMotion ? 0 : 14);
        dialogOffset.retarget(0, now, spatialTime());
    }

    private double dialogBodyHeight(double width, String body) {
        return painter.lines(EffectsTypography.body(), body, Math.max(1, width - 48)).length
                * painter.lineHeight(EffectsTypography.body());
    }

    private Box dialogBox() {
        return dialogBox(dialogTitle, dialogBody, inputError, numeric != null || naming);
    }

    private Box dialogBox(String title, String body, String error, boolean form) {
        double width = Math.min(500, Math.max(1, layout.surface.width() - 32));
        double titleHeight = painter.lines(EffectsTypography.heading(), title, width - 48).length * painter.lineHeight(EffectsTypography.heading());
        double bodyHeight = dialogBodyHeight(width, body), formHeight = form ? 50 : 0;
        double errorHeight = error.isBlank() ? 0 : painter.lines(EffectsTypography.caption(), error, width - 48).length
                * painter.lineHeight(EffectsTypography.caption()) + 8;
        double height = Math.min(32 + titleHeight + bodyHeight + 18 + formHeight + errorHeight + 65,
                Math.max(1, layout.surface.height() - 24));
        return new Box(layout.surface.centerX() - width * .5, layout.surface.centerY() - height * .5, width, height);
    }

    private void drawDialog(Canvas canvas) {
        hits.clear();
        drawDialogContent(canvas, new Dialog(dialogTitle, dialogBody, input.getText(), inputError, numeric != null || naming,
                naming, dialogScroll.value(now), dialogProgress, dialogOffset), true);
    }

    private void drawDialogContent(Canvas canvas, Dialog state, boolean interactive) {
        double opacity = state.opacity.value(now), offset = state.offset.value(now);
        painter.round(canvas, layout.surface, 28, alpha(0xFF000000, opacity * .38));
        Box box = dialogBox(state.title, state.body, state.error, state.form).offset(0, offset);
        int layer = canvas.saveLayerAlpha(EffectsPaint.rect(box), (int) Math.round(opacity * 255));
        boolean wasLive = paintingLive;
        paintingLive = interactive;
        try {
            painter.round(canvas, box, 28, HIGH);
            painter.outline(canvas, box.inset(.5), 27.5, 1, alpha(OUTLINE, .45));
            double titleHeight = painter.lines(EffectsTypography.heading(), state.title, box.width() - 48).length * painter.lineHeight(EffectsTypography.heading());
            painter.paragraph(canvas, EffectsTypography.heading(), state.title, box.x() + 24, box.y() + 20, box.width() - 48, TEXT);
            double bodyHeight = dialogBodyHeight(box.width(), state.body), formHeight = state.form ? 50 : 0;
            double errorHeight = state.error.isBlank() ? 0 : painter.lines(EffectsTypography.caption(), state.error, box.width() - 48).length
                    * painter.lineHeight(EffectsTypography.caption()) + 8;
            Box bodyClip = new Box(box.x() + 16, box.y() + 28 + titleHeight, box.width() - 32,
                    Math.max(1, box.height() - titleHeight - 88));
            if (interactive) {
                dialogViewport = bodyClip;
                dialogScroll.bounds(bodyHeight + 12 + formHeight + errorHeight, bodyClip.height(), now);
            }
            double scrollPosition = interactive ? dialogScroll.value(now) : state.scroll;
            double contentTop = bodyClip.y() - scrollPosition;
            int saved = canvas.save();
            canvas.clipRect(EffectsPaint.rect(bodyClip), true);
            try {
                painter.paragraph(canvas, EffectsTypography.body(), state.body, box.x() + 24, contentTop, box.width() - 48, MUTED);
                double formTop = contentTop + bodyHeight + 12;
                if (state.form) {
                    Box field = new Box(box.x() + 24, formTop, box.width() - 48, 38);
                    if (interactive) {
                        inputBox = field;
                        painter.input(canvas, input, EffectsTypography.body(), field, now);
                        addHit("dialog.input", field, bodyClip, () -> focus(input, inputBox), null, false, false, null, opacity > .9);
                    } else {
                        painter.round(canvas, field, 12, palette.input());
                        painter.text(canvas, EffectsTypography.body(), state.value, field.x() + 12, field.centerY(), field.width() - 24, TEXT);
                    }
                    if (!state.error.isBlank()) painter.paragraph(canvas, EffectsTypography.caption(), state.error,
                            box.x() + 24, field.bottom() + 8, box.width() - 48, ERROR);
                }
            } finally { canvas.restoreToCount(saved); }
            double max = Math.max(0, bodyHeight + 12 + formHeight + errorHeight - bodyClip.height());
            if (max > 0) {
                double thumb = Math.max(18, bodyClip.height() * bodyClip.height() / (bodyHeight + 12 + formHeight + errorHeight));
                double y = bodyClip.y() + scrollPosition / max * (bodyClip.height() - thumb);
                painter.round(canvas, new Box(box.right() - 12, y, 3, thumb), 1.5, alpha(MUTED, .45));
            }
            double width = Math.min(110, (box.width() - 56) * .5);
            button(canvas, "dialog.cancel", new Box(box.right() - width * 2 - 32, box.bottom() - 49, width, 33), "取消", null,
                    this::closeDialog, interactive && opacity > .9, false, box);
            button(canvas, "dialog.accept", new Box(box.right() - width - 24, box.bottom() - 49, width, 33), state.naming ? "保存副本" : state.form ? "应用" : "确认", null,
                    this::acceptDialog, interactive && opacity > .9 && !busy, true, box);
        } finally {
            paintingLive = wasLive;
            canvas.restoreToCount(layer);
        }
    }

    private void acceptDialog() {
        if (naming) {
            String name = input.getText().trim();
            String sanitized = com.muoniumplayer.core.viper.ViperPresetStore.sanitizeName(name);
            if (name.isBlank() || !sanitized.equals(name)) { inputError = "请输入有效名称，避免路径分隔符与非法字符"; return; }
            if (presets.stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) { inputError = "已有同名方案，请换一个名称"; return; }
            ViperPresetEditor snapshot = editor.copy();
            closeDialog();
            stateJob(() -> { snapshot.saveAsNewChecked(name); return load(); }, value -> applyLoaded(value, true), "已保存并选用独立方案");
            return;
        }
        if (numeric != null) {
            Numeric target = numeric;
            final double value;
            try {
                value = Double.parseDouble(input.getText().trim());
                if (!Double.isFinite(value)) throw new NumberFormatException();
                if (value < target.min || value > target.max) { inputError = "数值超出允许范围"; return; }
                if (target.integer && value != Math.rint(value)) { inputError = "这个参数需要整数"; return; }
            } catch (NumberFormatException invalid) { inputError = "请输入有限数值，例如 2.5；不接受 NaN 或 Infinity"; return; }
            closeDialog();
            if (current == Page.ACOUSTICS || current == Page.AMBIENT) {
                target.write.accept(value);
                saveEnvironment(current);
            } else edit(() -> target.write.accept(value));
            return;
        }
        Runnable action = dialogAction;
        closeDialog();
        if (action != null) action.run();
    }

    private void closeDialog() {
        if (dialogTitle != null) {
            EffectsMotion opacity = dialogProgress.copy();
            EffectsMotion.Spring offset = dialogOffset.copy();
            opacity.retarget(0, now, reducedMotion ? 90 : motionMs(130));
            offset.retarget(reducedMotion ? 0 : 10, now, spatialTime());
            retiringDialog = new Dialog(dialogTitle, dialogBody, input.getText(), inputError, numeric != null || naming,
                    naming, dialogScroll.value(now), opacity, offset);
        }
        pressedTarget = null;
        hits.clear();
        input.setFocused(false);
        dialogTitle = dialogBody = null;
        dialogAction = null;
        numeric = null;
        naming = false;
        inputError = "";
    }

    @Override
    public boolean onMouseClicked(double relativeX, double relativeY, int button) {
        if (!ownsInput || closing || !hostAlive || layout == null) return true;
        mouseX = viewport.toDesign(relativeX);
        mouseY = viewport.toDesign(relativeY);
        if (System.nanoTime() < suppressClickUntil) return true;
        if (button == 1 && dialogTitle == null && menu == null) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.contains(mouseX, mouseY) && hit.id.startsWith("preset.use.")) {
                    openPresetMenu(hit.id.substring("preset.use.".length()), hit.box);
                    return true;
                }
            }
            return true;
        }
        if (button != 0) return true;
        if (retiringDialog != null && dialogTitle == null || retiringMenu != null && menu == null) return true;
        if (dialogTitle != null) {
            dispatchClick(mouseX, mouseY);
            return true;
        }
        if (menu != null) {
            if (!menuBox().contains(mouseX, mouseY)) closeMenu();
            else dispatchClick(mouseX, mouseY);
            return true;
        }
        if (!layout.surface.contains(mouseX, mouseY)) { dispose(); return true; }
        if (!searchBox.contains(mouseX, mouseY)) search.setFocused(false);
        dispatchClick(mouseX, mouseY);
        return true;
    }

    private void dispatchClick(double x, double y) {
        Hit found = findHit(x, y);
        if (found == null) return;
        if (found.drag == null) {
            EffectsMotion.Spring state = press.computeIfAbsent(found.id, ignored -> new EffectsMotion.Spring(0, EffectsMotion.Scheme.FAST_SPATIAL));
            state.retarget(1, now, spatialTime());
            if (Mouse.isButtonDown(0)) pressedTarget = found;
            else {
                state.snap(.6);
                state.retarget(0, now, spatialTime());
            }
        }
        if (found.drag != null) {
            beginDrag(found);
            applyDrag(x, y);
            if (!Mouse.isButtonDown(0)) finishDrag(true);
        } else if (found.click != null) found.click.run();
    }

    private Hit findHit(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) return hits.get(i);
        return null;
    }

    private void beginDrag(Hit hit) {
        if (drag != null || busy || loading || dialogTitle != null || menu != null) return;
        search.setFocused(false);
        drag = hit;
        dragBaseline = editor == null ? null : editor.copy();
        dragStrength = strength;
        dragStable = stableMs;
        dragChanged = false;
        touchGraphOwned = false;
        if (hit.click != null) hit.click.run();
        if (hit.graphX != null) hit.graphX.accept(mouseX);
    }

    private void applyDrag(double x, double y) {
        if (drag == null) return;
        double value = drag.graph ? EffectsLayout.gainAtY(y, drag.box)
                : Math.max(0, Math.min(1, drag.vertical ? 1 - (y - drag.box.y()) / Math.max(1, drag.box.height()) : (x - drag.box.x()) / Math.max(1, drag.box.width())));
        try {
            drag.drag.accept(value);
            dragChanged = true;
        } catch (IllegalArgumentException invalid) {
            message(errorMessage(invalid), NoticeTone.ERROR);
            finishDrag(false);
        }
    }

    private void advancePointer() {
        boolean down = Mouse.isButtonDown(0);
        if (!ownsInput || closing || !hostAlive || layout == null) { finishDrag(true); wasDown = down; return; }
        if (menu != null || dialogTitle != null || retiringMenu != null || retiringDialog != null) { finishDrag(true); wasDown = down; return; }
        TouchGestures gestures = TouchGestures.get();
        if (down && !wasDown && presentation > .9) {
            Hit hit = findHit(mouseX, mouseY);
            if (hit != null && hit.drag != null) beginDrag(hit);
        }
        if (drag != null) {
            boolean verticalScroll = TouchSettings.ENABLED && gestures.isVerticalDrag();
            if (verticalScroll && drag.graph && !touchGraphOwned && gestures.longPressElapsed()) touchGraphOwned = true;
            if (TouchSettings.ENABLED && drag.graph && gestures.longPressElapsed()) touchGraphOwned = true;
            // 横向滑条让位纵向滚动；图形/竖推子触屏长按后才拥有纵向编辑权。
            if (verticalScroll && (!drag.graph || !touchGraphOwned)) finishDrag(false);
            else if (!down) finishDrag(true);
            else if (!TouchSettings.ENABLED || !drag.graph || touchGraphOwned) applyDrag(mouseX, mouseY);
        }
        if (TouchSettings.ENABLED && gestures.isVerticalDrag() && drag == null
                && pageClip.contains(viewport.toDesign(gestures.startX() - getX()), viewport.toDesign(gestures.startY() - getY()))) {
            if (gestures.claimedBy() == null) gestures.claim(this);
            if (gestures.claimedBy() == this) { scroll.drag(-viewport.toDesign(gestures.dy()), now); touchingScroll = true; }
        } else if (touchingScroll && !down) {
            double velocity = gestures.velocityY();
            double distance = Math.min(500, velocity * velocity / (2 * Math.max(.001, TouchSettings.FLING_DECEL)));
            scroll.move(-Math.signum(velocity) * viewport.toDesign(distance), now, motionMs(450));
            touchingScroll = false;
        }
        wasDown = down;
    }

    private void finishDrag(boolean commit) {
        if (drag == null) return;
        Hit ended = drag;
        drag = null;
        touchGraphOwned = false;
        if (!commit) {
            if (dragBaseline != null) editor = dragBaseline;
            strength = dragStrength;
            stableMs = dragStable;
        } else if (dragChanged) {
            if (ended.id.startsWith("acoustics.")) saveEnvironment(Page.ACOUSTICS);
            else if (ended.id.startsWith("ambient.")) saveEnvironment(Page.AMBIENT);
            else if (dragBaseline != null && different(dragBaseline, editor)) {
                remember(dragBaseline);
                editRevision++;
                commitEditor();
            }
        }
        dragBaseline = null;
        dragChanged = false;
        suppressClickUntil = System.nanoTime() + 100_000_000L;
    }

    /** 空控件树只收到一次 wheel；不调用父类的递归派发，也不重新读取原生滚轮。 */
    @Override
    public void onDWheelReceived(double absoluteX, double absoluteY, int amount) {
        if (!ownsInput || closing || !hostAlive || amount == 0 || layout == null
                || retiringDialog != null && dialogTitle == null || retiringMenu != null && menu == null) return;
        double x = viewport.toDesign(absoluteX - getX()), y = viewport.toDesign(absoluteY - getY());
        if (dialogTitle != null) {
            if (dialogViewport.contains(x, y)) dialogScroll.move(-amount / 120.0 * 40, System.nanoTime(), motionMs(210));
            return;
        }
        if (menu != null) {
            if (menuBox().contains(x, y)) menuScroll.move(-amount / 120.0 * 40, System.nanoTime(), motionMs(210));
            return;
        }
        if (drag != null || !layout.surface.contains(x, y)) return;
        search.setFocused(false);
        scroll.move(-amount / 120.0 * 54, System.nanoTime(), motionMs(230));
    }

    @Override
    public boolean onKeyTyped(char character, int keyCode) {
        if (!ownsInput || closing || !hostAlive) return false;
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (dialogTitle != null) { closeDialog(); return true; }
            if (menu != null) { closeMenu(); return true; }
            if (search.isFocused()) { search.setFocused(false); return true; }
            if (selectedGroup >= 0) { navigate(Page.EFFECTS, -1); return true; }
            return false;
        }
        if (dialogTitle != null) {
            if (enter) { acceptDialog(); return true; }
            if (numeric != null || naming) input.textboxKeyTyped(character, keyCode);
            return true;
        }
        if (menu != null) return true;
        if (search.isFocused()) {
            if (enter || keyCode == Keyboard.KEY_TAB) search.setFocused(false);
            else search.textboxKeyTyped(character, keyCode);
            return true;
        }
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (ctrl && keyCode == Keyboard.KEY_Z) { if (shift) redo(); else undo(); return true; }
        if (ctrl && keyCode == Keyboard.KEY_Y) { redo(); return true; }
        if (keyCode == Keyboard.KEY_TAB) {
            navigate(Page.values()[(current.ordinal() + (shift ? 4 : 1)) % 5], -1);
            return true;
        }
        if (current == Page.EQ && editor != null && !busy) {
            try {
                ViperEqualizerModel model = new ViperEqualizerModel(editor);
                selectedBand = Math.max(0, Math.min(model.bandCount() - 1, selectedBand));
                if (keyCode == Keyboard.KEY_LEFT) selectedBand = (selectedBand + model.bandCount() - 1) % model.bandCount();
                else if (keyCode == Keyboard.KEY_RIGHT) selectedBand = (selectedBand + 1) % model.bandCount();
                else if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
                    int band = selectedBand;
                    double next = model.gains()[band] + (keyCode == Keyboard.KEY_UP ? 1 : -1) * (shift ? .1 : .5);
                    edit(() -> new ViperEqualizerModel(editor).setBandGainQuiet(band, next));
                }
            } catch (IllegalArgumentException invalid) { message("请先恢复有效的 EQ 布局：" + errorMessage(invalid), NoticeTone.ERROR); }
        }
        return true;
    }

    public void handleEscape() { dispose(); }

    @Override
    public void dispose() {
        if (closing) return;
        finishDrag(true);
        search.setFocused(false);
        input.setFocused(false);
        closeMenu();
        closeDialog();
        super.dispose();
    }

    /** 宿主已经结束时不再等待退场时钟；释放页面 Paint，但不释放借用的 Canvas/字体。 */
    public void onHostClosed() {
        if (!hostAlive) return;
        finishDrag(true);
        hostAlive = false;
        operation++;
        search.setFocused(false);
        input.setFocused(false);
        closeMenu();
        closeDialog();
        hits.clear();
        hover.clear();
        selection.clear();
        shape.clear();
        press.clear();
        pressedTarget = null;
        departing.clear();
        retiringMenu = null;
        retiringDialog = null;
        undo.clear();
        redo.clear();
        if (painter != null) { painter.close(); painter = null; }
    }
}
