package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.RRect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;

/** Expressive 字体工作台：真实全局预览、来源筛选与字体列表，仍然点选即生效。 */
public final class FontPickerOverlay extends NcmSheet {
    private static final String[] SECTIONS = {"全部", "系统字体", "我的文件", "内置"};
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final FontPickerMotion filterMotion = new FontPickerMotion();
    private final NcmPromptMotion tabMotion = new NcmPromptMotion(0);
    private final List<FontRow> rows = new ArrayList<>();
    private final int[] counts = new int[4];
    private final double[] scrollMemory = new double[4];
    private NcmPromptMotion previewMotion = new NcmPromptMotion(1);

    private FontPickerGeometry geometry = new FontPickerGeometry(980, 740);
    private Panel dialog, tabs, preview, listStage, empty, widePreview, compactPreview;
    private ScrollPanel viewport;
    private TextFieldWidget searchField;
    private List<UiFont> catalog;
    private UiFontLibrary.State catalogState;
    private UiFont previewFont;
    private double slide, previewReveal = 1;
    private long listArrived;
    private int matches;
    private boolean filterDirty = true;

    @Override
    public void onInit() {
        UiFontLibrary.ensureScanned();
        beginPage();
        dialog = NcmExpressive.shell(this, FontPickerGeometry.WIDTH,
                () -> FontPickerGeometry.HEIGHT, () -> presentation, this::dispose);
        dialog.setBeforeRenderCallback(this::measure);
        buildHeader();
        buildSearch();
        buildTabs();
        buildPreview();
        buildList();
        buildFooter();
    }

    public void handleEscape() { dispose(); }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        layout.render(this, mouseX, mouseY, dWheel, (x, y, wheel) -> {
            super.renderWidget(x, y, 0);
            // 控件树不重复消费滚轮；筛选收场期间也不把它喂给旧列表。
            if (!closing && filterMotion.acceptsInput() && wheel != 0) viewport.forwardWheel(wheel);
        });
    }

    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int button) {
        layout.dispatch(this, mouseX, mouseY, button, (x, y, input) -> super.onMouseClickReceived(x, y, input));
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        if (!closing && filterMotion.acceptsInput()) viewport.forwardWheel(dWheel);
    }

    /** 在子控件遍历及 ScrollPanel 排版之前提交几何、目录快照与隐藏状态。 */
    private void measure() {
        long now = System.nanoTime();
        double width = Math.max(1, Math.min(FontPickerGeometry.WIDTH, getWidth() - 32));
        double height = Math.max(1, Math.min(FontPickerGeometry.HEIGHT, getHeight() - 32));
        if (width != geometry.width || height != geometry.height) geometry = new FontPickerGeometry(width, height);
        dialog.setBounds(width, height);
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1 - presentation) * 18);
        dialog.setAlpha((float) presentation);
        tabs.setBounds(geometry.contentWidth, 48);
        tabs.setPosition(geometry.inset, geometry.tabsY);
        preview.setHidden(geometry.previewHeight <= 0);
        preview.setBounds(geometry.previewWidth, geometry.previewHeight);
        preview.setPosition(geometry.previewX, geometry.previewY + (1 - previewReveal) * 4);
        widePreview.setHidden(!geometry.columns || geometry.previewHeight < 340);
        compactPreview.setHidden(geometry.columns || geometry.shortWindow);
        widePreview.setBounds(preview.getWidth(), preview.getHeight());
        compactPreview.setBounds(preview.getWidth(), preview.getHeight());
        listStage.setBounds(geometry.listWidth, geometry.listHeight);
        listStage.setPosition(geometry.inset, geometry.listY + filterMotion.offset());
        listStage.setAlpha((float) (cardGate(0) * filterMotion.alpha()));
        viewport.setBounds(geometry.listWidth, geometry.listHeight);
        viewport.setPosition(0, 0);

        List<UiFont> current = UiFontLibrary.getCatalog();
        if (!closing && catalog != current) {
            catalog = current;
            rebuildRows();
        }
        if (catalogState != UiFontLibrary.getState()) {
            catalogState = UiFontLibrary.getState();
            filterDirty = true;
        }
        if (!closing) filterMotion.request(normalize(searchField.getText()), filterMotion.targetSection());
        int previousSection = filterMotion.section();
        String previousQuery = filterMotion.query();
        if (!closing && filterMotion.tick(now, SheetClock.speed())) {
            scrollMemory[previousSection] = viewport.targetScrollOffset;
            boolean sectionChanged = previousSection != filterMotion.section();
            double offset = sectionChanged && previousQuery.equals(filterMotion.query())
                    ? scrollMemory[filterMotion.section()] : 0;
            viewport.actualScrollOffset = viewport.targetScrollOffset = offset;
            listArrived = now;
            filterDirty = true;
        }
        slide = tabMotion.update(filterMotion.targetSection(), 48 / SheetClock.speed(), now);
        if (filterDirty) applyFilter();
        for (FontRow row : rows) row.setBounds(geometry.listWidth, FontPickerGeometry.ROW_HEIGHT + FontPickerGeometry.ROW_GAP);
        empty.setBounds(geometry.listWidth, Math.max(160, geometry.listHeight));
        empty.setHidden(matches != 0);

        UiFont active = UiFontLibrary.getActive();
        if (active != previewFont) {
            if (previewFont != null) previewMotion = new NcmPromptMotion(0);
            previewFont = active;
        }
        previewReveal = previewMotion.update(1, 55 / SheetClock.speed(), now);
    }

    private Panel stage(DoubleSupplier gate) {
        Panel layer = veil(dialog, gate);
        layer.setBeforeRenderCallback(() -> {
            layer.setBounds(dialog.getWidth(), dialog.getHeight());
            layer.setPosition(0, (1 - gate.getAsDouble()) * 8);
            layer.setAlpha((float) gate.getAsDouble());
        });
        return layer;
    }

    private void buildHeader() {
        Panel header = stage(this::headerGate);
        NcmExpressive.label(header, () -> "个性化 / TYPOGRAPHY", () -> FontManager.pf20,
                () -> geometry.inset, () -> geometry.shortWindow ? 21 : 25,
                () -> geometry.contentWidth - 64, () -> palette().muted());
        NcmExpressive.label(header, () -> "让文字，也合你心意", () -> geometry.shortWindow ? FontManager.pf34bold : FontManager.pf40bold,
                () -> geometry.inset, () -> geometry.shortWindow ? 49 : 61,
                () -> geometry.contentWidth - 60, () -> palette().text());
        RoundedRectWidget close = NcmExpressive.hitSurface(header,
                () -> dialog.getWidth() - geometry.inset - 42, () -> 20,
                () -> 42, () -> 42, 21, () -> palette().container(), this::dispose);
        close.setTooltip("关闭字体选择（Esc）");
        NcmExpressive.icon(close, PartyIcons.CLOSE, ICON_M, () -> 21, () -> 21, () -> palette().muted());
    }

    private void buildSearch() {
        Panel search = stage(() -> cardGate(0));
        RoundedRectWidget field = NcmExpressive.surface(search, () -> geometry.inset, () -> geometry.searchY,
                () -> geometry.contentWidth - 58, () -> 48, 24, () -> palette().input());
        NcmExpressive.icon(field, PartyIcons.SEARCH, ICON_M, () -> 24, () -> 24, () -> palette().primary());
        searchField = new TextFieldWidget(FontManager.pf25);
        search.addChild(searchField);
        searchField.setEnabled(true).setFocused(true).drawUnderline(false);
        searchField.setPlaceholder("搜索字体名称或英文族名");
        searchField.setBeforeRenderCallback(() -> {
            searchField.setBounds(Math.max(1, field.getWidth() - 82), 30);
            searchField.setPosition(geometry.inset + 46, geometry.searchY + 9);
            searchField.setColor(palette().text());
            searchField.setDisabledTextColor(palette().muted());
        });
        stroke(field, 24, () -> palette().primary(), () -> searchField.isFocused() ? .42 : 0);
        RoundedRectWidget clear = NcmExpressive.hitSurface(search,
                () -> geometry.inset + geometry.contentWidth - 92, () -> geometry.searchY + 8,
                () -> 32, () -> 32, 16, () -> palette().input(), () -> searchField.setText(""));
        clear.setBeforeRenderCallback(() -> {
            clear.setBounds(32, 32);
            clear.setPosition(geometry.inset + geometry.contentWidth - 92, geometry.searchY + 8);
            clear.setHidden(searchField.getText().isEmpty());
        });
        // 隐藏与恢复从父级驱动，不能放在 clear 自己的回调（隐藏后回调不会再执行）。
        search.setBeforeRenderCallback(() -> {
            search.setBounds(dialog.getWidth(), dialog.getHeight());
            search.setPosition(0, (1 - cardGate(0)) * 8);
            search.setAlpha((float) cardGate(0));
            clear.setHidden(searchField.getText().isEmpty());
        });
        clear.setTooltip("清空搜索");
        NcmExpressive.icon(clear, PartyIcons.CLOSE, ICON_S, () -> 16, () -> 16, () -> palette().muted());
        RoundedRectWidget refresh = NcmExpressive.hitSurface(search,
                () -> dialog.getWidth() - geometry.inset - 48, () -> geometry.searchY,
                () -> 48, () -> 48, 18, () -> palette().primaryContainer(), UiFontLibrary::refresh);
        refresh.setTooltip("重新扫描系统字体与字体目录");
        NcmExpressive.icon(refresh, () -> UiFontLibrary.getState() == UiFontLibrary.State.SCANNING
                        ? PartyIcons.HOURGLASS : PartyIcons.SYNC, ICON_M,
                () -> 24, () -> 24, () -> palette().primary());
    }

    private void buildTabs() {
        Panel layer = stage(() -> cardGate(1));
        tabs = new Panel();
        layer.addChild(tabs);
        tabs.setClickable(false);
        NcmExpressive.surface(tabs, 0, 0, tabs::getWidth, tabs::getHeight, 24, () -> palette().container());
        NcmExpressive.surface(tabs, () -> 4 + slide * tabWidth(), () -> 4,
                this::tabWidth, () -> 40, 20, () -> palette().primary());
        for (int i = 0; i < SECTIONS.length; i++) {
            final int section = i;
            Panel tab = new Panel();
            tabs.addChild(tab);
            tab.setShouldOverrideMouseCursor(true).setBeforeRenderCallback(() -> {
                tab.setBounds(tabWidth(), 40);
                tab.setPosition(4 + section * tabWidth(), 4);
            });
            tab.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                filterMotion.request(normalize(searchField.getText()), section);
                return true;
            });
            NcmExpressive.centered(tab, () -> SECTIONS[section], () -> FontManager.pf24bold,
                    () -> tab.getWidth() * .5, () -> 20, () -> tab.getWidth() - 12,
                    () -> NcmExpressivePalette.readableColor(section == filterMotion.targetSection()
                            ? palette().onPrimary() : palette().muted(),
                            NcmExpressivePalette.mix(palette().container(), palette().primary(), Math.max(0, 1 - Math.abs(slide - section)))));
        }
        NcmExpressive.label(layer, this::resultText, () -> FontManager.pf20,
                () -> geometry.inset, () -> geometry.summaryY,
                () -> geometry.contentWidth, () -> palette().muted());
    }

    private double tabWidth() { return Math.max(1, (tabs.getWidth() - 8) / 4); }

    private void buildPreview() {
        Panel layer = stage(() -> cardGate(2));
        preview = new Panel();
        layer.addChild(preview);
        preview.setClickable(false);
        RoundedRectWidget plate = NcmExpressive.surface(preview, 0, 0, preview::getWidth, preview::getHeight,
                28, () -> palette().primaryContainer());
        plate.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));
        plate.setGlassRim(GlassLook.Surface.PLAYER);
        NcmExpressive.label(preview, () -> "当前字体 · 实时预览", () -> FontManager.pf20,
                () -> 20, () -> 24, () -> preview.getWidth() - 40, () -> palette().primary());
        NcmExpressive.label(preview, () -> activeFont().getDisplayName(), () -> FontManager.pf28bold,
                () -> 20, () -> geometry.columns ? 59 : 52,
                () -> preview.getWidth() - (geometry.columns ? 40 : 130), () -> palette().text());
        Panel specimen = new Panel();
        preview.addChild(specimen);
        specimen.setClickable(false).setBeforeRenderCallback(() -> {
            specimen.setBounds(preview.getWidth(), preview.getHeight());
            specimen.setPosition(0, (1 - previewReveal) * 5);
            specimen.setAlpha((float) previewReveal);
        });
        NcmExpressive.label(specimen, () -> "Aa", () -> FontManager.pf65bold,
                () -> geometry.columns ? 22 : preview.getWidth() - 96,
                () -> geometry.columns ? 131 : geometry.shortWindow ? 48 : 61,
                () -> geometry.columns ? preview.getWidth() - 44 : 76, () -> palette().primary());
        Panel wide = new Panel();
        widePreview = wide;
        specimen.addChild(wide);
        wide.setClickable(false);
        NcmExpressive.label(wide, () -> "好音乐，", () -> FontManager.pf40bold,
                () -> 20, () -> 197, () -> preview.getWidth() - 40, () -> palette().text());
        NcmExpressive.label(wide, () -> "也要好字形。", () -> FontManager.pf40bold,
                () -> 20, () -> 230, () -> preview.getWidth() - 40, () -> palette().text());
        NcmExpressive.label(wide, () -> "Aa Bb Gg  0123456789", () -> FontManager.pf25,
                () -> 20, () -> 274, () -> preview.getWidth() - 40, () -> palette().muted());
        NcmExpressive.rule(wide, () -> 20, () -> 302, () -> preview.getWidth() - 40, () -> palette().outline());
        NcmExpressive.label(wide, () -> activeFont().hasCjk() ? "中文与拉丁字形，随你选择。" : "这款字体缺少中文，已用内置补齐。",
                () -> FontManager.pf20, () -> 20, () -> 329, () -> preview.getWidth() - 40, () -> palette().muted());
        NcmExpressive.label(wide, () -> "点选即应用 · 无需重启", () -> FontManager.pf20,
                () -> 20, () -> 355, () -> preview.getWidth() - 40, () -> palette().primary());
        Panel compact = new Panel();
        compactPreview = compact;
        specimen.addChild(compact);
        compact.setClickable(false);
        NcmExpressive.label(compact, () -> "好音乐，也要好字形。  Aa 0123", () -> FontManager.pf20,
                () -> 20, () -> 91, () -> preview.getWidth() - 40, () -> palette().muted());
        NcmExpressive.medallion(wide, PartyIcons.TEXT_FORMAT, () -> preview.getWidth() - 63,
                () -> 126, () -> 64, () -> palette().primary(), () -> palette().onPrimary());
    }

    private void buildList() {
        listStage = new Panel();
        dialog.addChild(listStage);
        listStage.setClickable(false);
        viewport = new ScrollPanel() {
            @Override protected boolean acceptsTouchInput() { return !closing && filterMotion.acceptsInput(); }
            @Override protected boolean shouldClickChildren(double x, double y) {
                return !closing && filterMotion.acceptsInput() && testHovered(x, y);
            }
        };
        empty = new Panel();
        listStage.addChild(viewport);
        listStage.addChild(empty);
        viewport.setSpacing(0).setScrollStrength(54).setIndicatorInsideViewport();
        empty.setClickable(false);
        empty.setBeforeRenderCallback(() -> {
            empty.setBounds(geometry.listWidth, geometry.listHeight);
            empty.setPosition(0, 0);
            empty.setHidden(matches != 0);
        });
        NcmExpressive.medallion(empty, PartyIcons.SEARCH, () -> empty.getWidth() * .5,
                () -> 45, () -> 50, () -> palette().primaryContainer(), () -> palette().primary());
        NcmExpressive.centered(empty, this::emptyTitle, () -> FontManager.pf28bold,
                () -> empty.getWidth() * .5, () -> 99, () -> empty.getWidth() - 32, () -> palette().text());
        NcmExpressive.centered(empty, this::emptyHint, () -> FontManager.pf20,
                () -> empty.getWidth() * .5, () -> 132, () -> empty.getWidth() - 32, () -> palette().muted());
    }

    private void rebuildRows() {
        viewport.getChildren().clear();
        rows.clear();
        rows.add(new FontRow(UiFont.BUILTIN));
        for (UiFont font : catalog) rows.add(new FontRow(font));
        for (FontRow row : rows) viewport.addChild(row);
        counts[0] = rows.size();
        counts[1] = counts[2] = 0;
        counts[3] = 1;
        for (UiFont font : catalog) counts[sectionOf(font)]++;
        listArrived = System.nanoTime();
        filterDirty = true;
    }

    /** 隐藏由父级驱动；目录刷新不重建搜索框、窗口和进出时钟。 */
    private void applyFilter() {
        matches = 0;
        for (FontRow row : rows) {
            boolean visible = matches(row.font, filterMotion.query())
                    && (filterMotion.section() == 0 || sectionOf(row.font) == filterMotion.section());
            row.setHidden(!visible);
            if (visible) row.slot = matches++;
        }
        filterDirty = false;
    }

    static boolean matches(UiFont font, String needle) {
        if (needle.isEmpty()) return true;
        if (font.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)) return true;
        String family = font.getFamily();
        return family != null && family.toLowerCase(Locale.ROOT).contains(needle);
    }

    static String normalize(String text) { return text == null ? "" : text.trim().toLowerCase(Locale.ROOT); }

    private static int sectionOf(UiFont font) {
        return font.isBuiltin() ? 3 : font.getOrigin() == UiFont.Origin.SYSTEM ? 1 : 2;
    }

    private String resultText() {
        if (UiFontLibrary.getState() == UiFontLibrary.State.SCANNING) return "正在扫描字体目录…  现有字体仍可选用";
        return filterMotion.query().isEmpty() ? "可选字体 " + counts[filterMotion.section()] + " 款 · 点选即应用"
                : "找到 " + matches + " 款字体 · 中文名与英文族名均可搜索";
    }

    private String emptyTitle() {
        if (!filterMotion.query().isEmpty()) return "没有找到这款字体";
        if (UiFontLibrary.getState() == UiFontLibrary.State.SCANNING) return "字体正在路上";
        return filterMotion.section() == 2 ? "带上你喜欢的字体" : "暂时没有可选字体";
    }

    private String emptyHint() {
        if (!filterMotion.query().isEmpty()) return "试试更短的名称，或切换到「全部」。";
        return filterMotion.section() == 2 ? "选择字体文件导入，或打开字体目录。" : "扫描完成后自动更新，也可导入字体文件。";
    }

    private void buildFooter() {
        Panel footer = stage(this::footerGate);
        String[] captions = {"选择字体文件", "字体目录", "恢复内置"};
        String[] glyphs = {PartyIcons.ADD, PartyIcons.FOLDER_OPEN, PartyIcons.SYNC};
        Runnable[] actions = {UiFontLibrary::browseForFile, UiFontLibrary::openExternalDir, UiFontLibrary::restoreBuiltin};
        for (int i = 0; i < actions.length; i++) {
            final int index = i;
            RoundedButtonWidget action = NcmExpressive.action(footer, glyphs[i], () -> captions[index],
                    i == 0 ? NcmExpressive.ActionStyle.PRIMARY : i == 1 ? NcmExpressive.ActionStyle.TONAL : NcmExpressive.ActionStyle.NEUTRAL,
                    () -> geometry.actionX(index), () -> geometry.footerY,
                    () -> geometry.actionWidth(index), () -> geometry.actionHeight, actions[i]);
            action.setTooltip(() -> captions[index]);
        }
        NcmExpressive.icon(footer, this::statusGlyph, ICON_S, () -> geometry.inset + 6,
                () -> geometry.footerY - 20, this::statusColor);
        NcmExpressive.label(footer, this::statusText, () -> FontManager.pf20,
                () -> geometry.inset + 20, () -> geometry.footerY - 20,
                () -> geometry.contentWidth - 20, this::statusColor).setTooltip(this::statusText);
    }

    private String statusGlyph() { return UiFontLibrary.isBusy() ? PartyIcons.HOURGLASS : UiFontLibrary.isStatusBad() ? PartyIcons.WARNING : PartyIcons.CHECK; }
    private String statusText() {
        if (has(UiFontLibrary.getStatus())) return UiFontLibrary.getStatus();
        return "当前使用：" + activeFont().getDisplayName();
    }
    private int statusColor() { return UiFontLibrary.isBusy() ? palette().warning() : UiFontLibrary.isStatusBad() ? palette().error() : palette().success(); }
    private static UiFont activeFont() { return UiFontLibrary.getActive(); }
    private static NcmExpressivePalette palette() { return NcmExpressivePalette.live(); }

    /** 固定命中边界，形状/选中底色与勾线在内部过渡；不逐行加载候选字体做假预览。 */
    private final class FontRow extends Panel {
        final UiFont font;
        final NcmPromptMotion selected, hover = new NcmPromptMotion(0), press = new NcmPromptMotion(0);
        int slot;
        double selectedAmount;
        final RoundedRectWidget plate;

        FontRow(UiFont font) {
            this.font = font;
            selected = new NcmPromptMotion(UiFontLibrary.isActive(font) ? 1 : 0);
            setBounds(geometry.listWidth, FontPickerGeometry.ROW_HEIGHT + FontPickerGeometry.ROW_GAP);
            setClickable(true);
            setShouldOverrideMouseCursor(true);
            setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (!closing && filterMotion.acceptsInput()) UiFontLibrary.select(font);
                return true;
            });
            plate = NcmExpressive.surface(this, () -> 0, () -> 0, this::getWidth,
                    () -> FontPickerGeometry.ROW_HEIGHT, 20, () -> palette().container());
            plate.setGlassRim(GlassLook.Surface.PLAYER);
            RoundedRectWidget monogram = NcmExpressive.surface(this, () -> 12, () -> 12,
                    () -> 52, () -> 52, 17, () -> selectedAmount > .5 ? palette().primary() : palette().primaryContainer());
            NcmExpressive.centered(monogram, () -> "Aa", () -> FontManager.pf28bold,
                    () -> 26, () -> 26, () -> 44, () -> selectedAmount > .5 ? palette().onPrimary() : palette().primary());
            NcmExpressive.label(this, font::getDisplayName, () -> FontManager.pf28bold,
                    () -> 78, () -> 27, () -> getWidth() - 132, () -> palette().text());
            NcmExpressive.label(this, this::caption, () -> FontManager.pf20,
                    () -> 78, () -> 53, () -> getWidth() - 132, () -> palette().muted());
            Panel badge = veil(this, () -> selectedAmount);
            NcmExpressive.successMark(badge, () -> getWidth() - 28, () -> 38, () -> 28,
                    () -> selectedAmount, () -> palette().primary());
            Panel pending = veil(this, () -> UiFontLibrary.isPending(font) ? 1 : 0);
            NcmExpressive.loading(pending, () -> getWidth() - 28, () -> 38, () -> 24, () -> palette().primary());
            setTooltip(() -> font.getDisplayName() + " · " + font.describe());
        }

        private String caption() {
            return font.isBuiltin() ? "SF Pro + 苹方 · 随 mod 打包"
                    : (font.getOrigin() == UiFont.Origin.EXTERNAL ? "字体文件" : "系统字体")
                    + " · " + (font.hasCjk() ? "含中文" : "中文由内置补齐") + (font.hasBold() ? " · 有粗体" : " · 模拟粗体");
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            long now = System.nanoTime();
            selectedAmount = selected.update(UiFontLibrary.isActive(font) ? 1 : 0, 48 / SheetClock.speed(), now);
            double hovering = hover.update(testHovered(mouseX, mouseY) && filterMotion.acceptsInput() ? 1 : 0, 45 / SheetClock.speed(), now);
            double pressed = press.update(testHovered(mouseX, mouseY) && Mouse.isButtonDown(0) ? 1 : 0, 28 / SheetClock.speed(), now);
            plate.setBeforeRenderCallback(() -> {
                plate.setBounds(getWidth(), FontPickerGeometry.ROW_HEIGHT);
                plate.setPosition(0, 0);
                plate.setRadius(20 - selectedAmount * 8 - pressed * 2);
                plate.setColor(NcmExpressivePalette.mix(palette().container(), palette().primaryContainer(), Math.min(1, selectedAmount + hovering * .6)));
                plate.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));
            });
            double arrive = NcmPromptMotion.reveal(listArrived, now, (430 + Math.min(slot, 5) * 34) / SheetClock.speed());
            setAlpha((float) (cardGate(Math.min(slot + 2, 7)) * arrive));
            Canvas canvas = SkijaGfx.canvas();
            if (canvas != null && selectedAmount > .001) {
                canvas.drawRRect(RRect.makeXYWH((float) (getX() + 1), (float) (getY() + 1),
                        (float) Math.max(0, getWidth() - 2), (float) (FontPickerGeometry.ROW_HEIGHT - 2), (float) (20 - selectedAmount * 8)),
                        SkijaGfx.stroke(NcmExpressive.withAlpha(palette().primary(), getAlpha() * selectedAmount * .6), 1.5f));
            }
        }
    }
}
