package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NcmSheet;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Independent Skija preset page. Settings, storage and compatibility policy belong to PresetPanelSession. */
public final class PresetSettingsPanel extends NcmSheet implements PresetPanelSession.Navigator {
    public static final String FONT_NOTE = "字体文件不随分享，本机缺失会跳过。若宿主采用冷字体策略，字体变更需重启游戏；这里不绕过宿主策略。";
    private enum View { MAIN, APPLY, SAVE, IMPORT, DELETE }
    private final PresetPanelSession session;
    private final PresetSettingsActions.Mode initialMode;
    private final List<FocusTarget> focusTargets = new ArrayList<>();
    /** ScrollPanel normally culls unmeasured rows. Pre-measure our own rows before alignment, including off-screen controls. */
    private final Map<Panel, DoubleSupplier> rowHeights = new IdentityHashMap<>();
    private final Map<AbstractWidget<?>, Supplier<PresetSettingsLayout.Box>> controlBounds = new IdentityHashMap<>();
    private List<StylePreset> builtins = Collections.emptyList(), mine = Collections.emptyList();
    private int catalogRevision = -1, focusIndex = -1, navigationTicket;
    private boolean revealInitialInput;
    private View view = View.MAIN;
    private ScrollPanel content;
    private TextFieldWidget nameInput, importInput;
    private StylePreset deleting;
    private String hostFailure = "";

    public PresetSettingsPanel(PresetPanelSession session, PresetSettingsActions.Mode initialMode) {
        this.session = session;
        this.initialMode = initialMode;
    }

    @Override
    public void onInit() {
        session.setNavigator(this);
        session.reset();
        refreshCatalog();
        if (initialMode == PresetSettingsActions.Mode.SAVE) showSave();
        else if (initialMode == PresetSettingsActions.Mode.IMPORT) showImport();
        else {
            if (initialMode == PresetSettingsActions.Mode.UNDO) perform(session::undo);
            showMain(false);
        }
    }

    public boolean acceptsCallback(int ticket) { return !closing && ticket == navigationTicket; }
    public int navigationTicket() { return navigationTicket; }
    public boolean isOpen() { return !closing; }
    public void reportHostFailure(String text) { if (!closing) hostFailure = text; }
    public void afterHostAction() {
        if (!closing && catalogRevision != session.revision()) refreshCatalog();
    }

    /** Only user actions and host callbacks read the directory, never the render loop. */
    private void refreshCatalog() {
        builtins = session.builtins();
        mine = session.mine();
        catalogRevision = session.revision();
    }

    private void perform(Runnable action) {
        if (closing) return;
        hostFailure = "";
        try {
            action.run();
            afterHostAction();
        } catch (RuntimeException failure) {
            hostFailure = "操作失败：" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public void handleEscape() {
        if (closing) return;
        if (view == View.MAIN) dispose();
        else back(() -> showMain(false)).run();
    }

    @Override
    public void showMain(boolean backward) {
        if (closing) return;
        if (backward) { back(() -> showMain(false)).run(); return; }
        session.enterMain();
        if (catalogRevision != session.revision()) refreshCatalog();
        page(View.MAIN, PresetPanelSession.TITLE, PresetPanelSession.MAIN_SUBTITLE);
        sectionLabel(strip(content, 22), PresetPanelSession.SECTION_BUILTIN, () -> 5, this::accent);
        int slot = 0;
        for (StylePreset preset : builtins) presetRow(strip(content, 66), slot++, preset);
        sectionLabel(strip(content, 22), session.mineSectionTitle(mine.size()), () -> 5, this::accent);
        if (mine.isEmpty()) paragraph(content, () -> PresetPanelSession.EMPTY_MINE, this::secondary);
        for (StylePreset preset : mine) presetRow(strip(content, 66), slot++, preset);
        Panel footer = footer(6);
        footerButton(footer, 0, 6, PartyIcons.ADD, () -> "存为模板", this::showSave, false);
        footerButton(footer, 1, 6, PartyIcons.LINK, () -> "导入模板", this::showImport, false);
        RoundedButtonWidget undo = footerButton(footer, 2, 6, PartyIcons.SYNC, () -> "撤销套用", session::undo, false);
        undo.setTooltip(session::undoTooltip);
        footerButton(footer, 3, 6, PartyIcons.LINK, () -> "粘贴导入", session::importFromClipboard, false);
        footerButton(footer, 4, 6, PartyIcons.FOLDER_OPEN, () -> "打开目录", session::openFolder, false);
        footerButton(footer, 5, 6, PartyIcons.SYNC, () -> "刷新目录", () -> { refreshCatalog(); showMain(false); }, false);
        paragraph(content, () -> PresetPanelSession.MAIN_NOTE, this::secondary);
        strip(content, 6);
    }

    @Override
    public void showApply(StylePreset preset) {
        if (closing) return;
        session.enterApply(preset);
        renderApply();
    }

    private void renderApply() {
        StylePreset preset = session.target();
        if (preset == null || closing) return;
        page(View.APPLY, preset.meta().name(), PresetPanelSession.applySubtitle(preset));
        paragraph(content, session::previewStatus, () -> toneColor(session.previewTone()));
        List<Facet> look = session.facetsOf(preset, Facet.Group.APPEARANCE);
        List<Facet> audio = session.facetsOf(preset, Facet.Group.AUDIO);
        if (look.isEmpty() && audio.isEmpty()) paragraph(content, () -> PresetPanelSession.APPLY_EMPTY, this::secondary);
        facetSection("外观", look, session::isSelected, session::toggle);
        facetSection("音效", audio, session::isSelected, session::toggle);
        sectionLabel(strip(content, 22), "兼容性与资源提示", () -> 5, () -> WARN_COLOR);
        for (int i = 0; i < PresetPanelSession.NOTE_LIMIT; i++) {
            final int index = i;
            paragraph(content, () -> session.noteLine(index), () -> WARN_COLOR);
        }
        paragraph(content, () -> FONT_NOTE, this::secondary);
        paragraph(content, () -> PresetPanelSession.APPLY_NOTE, this::secondary);
        Panel footer = footer(4);
        footerButton(footer, 0, 4, PartyIcons.CHECK, () -> "全选", session::selectAll, false);
        footerButton(footer, 1, 4, PartyIcons.TEXT_FORMAT, () -> "只选外观", session::selectAppearanceOnly, false);
        footerButton(footer, 2, 4, PartyIcons.HEADPHONES, () -> "只选音效", session::selectAudioOnly, false);
        footerButton(footer, 3, 4, PartyIcons.CHECK, session::applyLabel, session::applyTarget, true);
    }

    private void showSave() {
        session.enterSave();
        page(View.SAVE, "保存当前为模板", PresetPanelSession.SAVE_SUBTITLE);
        nameInput = input(PresetPanelSession.NAME_PLACEHOLDER, PresetLimits.MAX_NAME_CHARS,
                () -> session.save(nameText(), false));
        nameInput.setFocused(true);
        revealInitialInput = true;
        paragraph(content, session::saveHint, () -> session.saveHintBad() ? BAD_COLOR : secondary());
        facetSection("外观", session.facetsAvailable(Facet.Group.APPEARANCE), session::isSaveSelected, session::toggleSave);
        facetSection("音效", session.facetsAvailable(Facet.Group.AUDIO), session::isSaveSelected, session::toggleSave);
        paragraph(content, () -> PresetPanelSession.SAVE_NOTE, this::secondary);
        paragraph(content, () -> FONT_NOTE, this::secondary);
        Panel footer = footer(4);
        footerButton(footer, 0, 4, PartyIcons.TEXT_FORMAT, () -> "只选外观", session::saveSelectAppearanceOnly, false);
        footerButton(footer, 1, 4, PartyIcons.HEADPHONES, () -> "只选音效", session::saveSelectAudioOnly, false);
        footerButton(footer, 2, 4, PartyIcons.CHECK, () -> "保存", () -> session.save(nameText(), false), true);
        footerButton(footer, 3, 4, PartyIcons.LINK, () -> "保存并复制", () -> session.save(nameText(), true), true)
                .setTooltip("保存模板并复制完整分享码");
    }

    private String nameText() { return nameInput == null ? "" : nameInput.getText(); }

    private void showImport() {
        session.enterMain();
        page(View.IMPORT, "导入模板", "粘贴分享码或 JSON，也可以选择 .mpreset.json 文件");
        importInput = input("在这里粘贴分享码或 JSON · 回车导入", PresetLimits.MAX_SHARE_TEXT_CHARS,
                () -> session.importText(importInput.getText()));
        importInput.setFocused(true);
        revealInitialInput = true;
        paragraph(content, () -> "导入只保存一张模板卡片，不会立即改动设置。之后选择分面并明确点击「套用」。", this::secondary);
        Panel footer = footer(4);
        footerButton(footer, 0, 4, PartyIcons.LINK, () -> "导入输入", () -> session.importText(importInput.getText()), true);
        footerButton(footer, 1, 4, PartyIcons.LINK, () -> "粘贴导入", session::importFromClipboard, false);
        footerButton(footer, 2, 4, PartyIcons.FOLDER_OPEN, () -> session.picking() ? "选择中…" : "文件导入", session::browseForFile, false);
        footerButton(footer, 3, 4, PartyIcons.FOLDER_OPEN, () -> "打开目录", session::openFolder, false);
        paragraph(content, () -> PresetPanelSession.MAIN_NOTE, this::secondary);
    }

    /** First click opens this page; only its explicit second action can delete the file. */
    private void confirmDelete(StylePreset preset) {
        deleting = preset;
        page(View.DELETE, PresetPanelSession.DELETE_TITLE, "这个操作无法撤销，请先保留分享码或文件备份");
        paragraph(content, () -> PresetPanelSession.deleteMessage(preset), () -> BAD_COLOR);
        Panel footer = footer(2);
        footerButton(footer, 0, 2, PartyIcons.CHEVRON_LEFT, () -> "取消", () -> showMain(true), false);
        footerButton(footer, 1, 2, PartyIcons.CLOSE, () -> "确认删除", () -> {
            if (deleting == preset) { session.delete(preset); deleting = null; showMain(true); }
        }, true);
    }

    private Panel page(View next, String title, String subtitle) {
        beginPage();
        navigationTicket++;
        view = next;
        focusTargets.clear(); rowHeights.clear(); controlBounds.clear(); focusIndex = -1;
        revealInitialInput = false;
        nameInput = null; importInput = null;
        Panel dialog = sheet(PresetSettingsLayout.SHEET_W, () -> PresetSettingsLayout.sheetHeight(getHeight()));
        // A back control is available on every page, including the independent root page.
        RoundedButtonWidget back = backButton(dialog, this::handleEscape);
        focus(back, this::handleEscape, null);
        LabelWidget heading = label(dialog, title, FontManager.pf18bold);
        heading.setBeforeRenderCallback(() -> {
            heading.setColor(accent());
            heading.setMaxWidth(Math.max(1, dialog.getWidth() - 84));
            heading.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            heading.setPosition(60, inkTop(heading, 28));
        });
        LabelWidget sub = label(dialog, subtitle, FontManager.pf12);
        sub.setTooltip(subtitle);
        sub.setBeforeRenderCallback(() -> {
            sub.setColor(secondary());
            sub.setMaxWidth(Math.max(1, dialog.getWidth() - 84));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(60, inkTop(sub, 45));
        });
        tip(dialog, () -> hostFailure.isEmpty() ? session.statusText() : hostFailure,
                () -> hostFailure.isEmpty() ? toneColor(session.statusTone()) : BAD_COLOR, () -> 69);
        // The entire body, including inputs and wrapped action rows, scrolls on short/narrow windows.
        content = feed(body(dialog, () -> PresetSettingsLayout.BODY_TOP, () -> 8));
        strip(content, 5);
        paragraph(content, () -> hostFailure.isEmpty() ? session.statusText() : hostFailure,
                () -> hostFailure.isEmpty() ? toneColor(session.statusTone()) : BAD_COLOR);
        return dialog;
    }

    @Override
    protected ScrollPanel feed(Panel body) {
        ScrollPanel list = new ScrollPanel() {
            @Override public void onRender(double mouseX, double mouseY) {
                for (Map.Entry<Panel, DoubleSupplier> row : rowHeights.entrySet())
                    row.getKey().setBounds(Math.max(1, getWidth()), Math.max(1, row.getValue().getAsDouble()));
                for (Map.Entry<AbstractWidget<?>, Supplier<PresetSettingsLayout.Box>> control : controlBounds.entrySet()) {
                    PresetSettingsLayout.Box box = control.getValue().get();
                    control.getKey().setBounds(box.width, box.height);
                    control.getKey().setPosition(box.x, box.y);
                }
                super.onRender(mouseX, mouseY);
                if (revealInitialInput) {
                    revealInitialInput = false;
                    for (int i = 0; i < focusTargets.size(); i++) {
                        TextFieldWidget field = focusTargets.get(i).field;
                        if (field != null && field.isFocused()) { focusIndex = i; revealFocus(); alignChildren(); break; }
                    }
                }
            }
        };
        body.addChild(list);
        list.setSpacing(0).setScrollStrength(34).setAlignment(ScrollPanel.Alignment.VERTICAL);
        list.setIndicatorInsideViewport();
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, body.getWidth()), Math.max(1, body.getHeight()));
            list.setPosition(0, 0);
        });
        return list;
    }

    @Override
    protected Panel strip(ScrollPanel list, DoubleSupplier height) {
        Panel row = super.strip(list, height);
        rowHeights.put(row, height);
        return row;
    }

    @Override
    protected Panel strip(ScrollPanel list, double height) { return strip(list, () -> height); }

    private void presetRow(Panel row, int slot, StylePreset preset) {
        boolean user = preset.source() == StylePreset.Source.USER;
        final Card[] self = new Card[1];
        Card card = card(row, Math.min(slot, 9), () -> 0, () -> 59,
                () -> mix(getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        // Entrance/hover affects ink only. Drawing and hit testing use these same, fixed bounds.
        card.setTransformations(null);
        card.setBeforeRenderCallback(() -> {
            card.reveal = cardGate(Math.min(slot, 9));
            card.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 59);
            card.setPosition(PAD + CARD_INSET, 0);
            card.setColor(mix(getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), getColor(NCMScreen.ColorType.ELEMENT_HOVER), card.hover));
            card.setAlpha((float) card.reveal);
        });
        stroke(card, 12, this::accent, () -> card.hover * .25);
        card.setShouldOverrideMouseCursor(true);
        Runnable enter = () -> showApply(preset);
        card.setOnClickCallback((x, y, button) -> { if (button != 0) return false; perform(enter); return true; });
        focus(card, enter, null);
        icon(card, presetGlyph(preset), ICON_M, () -> 23, () -> 29.5, this::accent);
        double reserve = user ? 75 : 60;
        LabelWidget title = label(card, preset.meta().name(), FontManager.pf14bold);
        title.setTooltip(preset.meta().name());
        title.setBeforeRenderCallback(() -> {
            title.setColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - 45 - reserve));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(45, inkTop(title, 14));
        });
        String description = preset.meta().description().isEmpty() ? PresetPanelSession.cardNote(preset) : preset.meta().description();
        LabelWidget detail = label(card, description, FontManager.pf12);
        detail.setTooltip(description);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(secondary());
            detail.setMaxWidth(Math.max(1, card.getWidth() - 45 - reserve));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(45, inkTop(detail, 30));
        });
        LabelWidget range = label(card, PresetPanelSession.summary(preset), FontManager.pf10bold);
        range.setTooltip(PresetPanelSession.summary(preset));
        range.setBeforeRenderCallback(() -> {
            range.setColor(secondary());
            range.setMaxWidth(Math.max(1, card.getWidth() - 45 - reserve));
            range.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            range.setPosition(45, inkTop(range, 46));
        });
        if (user) {
            rowButton(card, 64, PartyIcons.LINK, "复制分享码", () -> session.copyShareCode(preset), false);
            rowButton(card, 34, PartyIcons.CLOSE, "删除模板", () -> confirmDelete(preset), true);
        } else pill(card, () -> PartyIcons.CHECK, () -> "内置", this::accent, () -> card.getWidth() - 10, () -> 29.5);
    }

    private String presetGlyph(StylePreset preset) {
        switch (PresetPanelSession.iconOf(preset)) {
            case "headphones": return PartyIcons.HEADPHONES;
            case "person": return PartyIcons.PERSON;
            case "note": return PartyIcons.NOTE;
            default: return PartyIcons.SWEEP;
        }
    }

    private void rowButton(Card card, double fromRight, String glyph, String tooltip, Runnable action, boolean danger) {
        RoundedButtonWidget button = action(card, () -> glyph, () -> "", FontManager.pf12bold,
                () -> danger ? BAD_COLOR : accent(), () -> perform(action));
        button.setTooltip(tooltip);
        button.setBeforeRenderCallback(() -> {
            button.setBounds(26, 26);
            button.setPosition(card.getWidth() - fromRight, 16.5);
            button.setColor(getColor(button.isHovering() ? NCMScreen.ColorType.ELEMENT_HOVER : NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        focus(button, action, null);
    }

    private void facetSection(String title, List<Facet> facets, Predicate<Facet> selected, Consumer<Facet> toggleAction) {
        if (facets.isEmpty()) return;
        sectionLabel(strip(content, 22), title, () -> 5, this::accent);
        for (Facet facet : facets) {
            Panel row = strip(content, 47);
            Runnable flip = () -> toggleAction.accept(facet);
            row.setOnClickCallback((x, y, button) -> { if (button != 0) return false; perform(flip); return true; });
            row.setShouldOverrideMouseCursor(true);
            focus(row, flip, null);
            LabelWidget caption = label(row, PresetPanelSession.facetCaption(facet), FontManager.pf14bold);
            caption.setBeforeRenderCallback(() -> {
                caption.setColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                caption.setMaxWidth(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2 - 44));
                caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                caption.setPosition(PAD + CARD_INSET, inkTop(caption, 14));
            });
            LabelWidget detail = label(row, facet.description(), FontManager.pf12);
            row.setTooltip(facet.description());
            detail.setBeforeRenderCallback(() -> {
                detail.setColor(secondary());
                detail.setMaxWidth(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2 - 44));
                detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                detail.setPosition(PAD + CARD_INSET, inkTop(detail, 33));
            });
            toggle(row, () -> selected.test(facet), () -> perform(flip), () -> row.getWidth() - PAD - CARD_INSET, () -> 23.5, this::accent);
        }
        strip(content, 6);
    }

    private TextFieldWidget input(String placeholder, int limit, Runnable submit) {
        Panel row = strip(content, 42);
        RoundedRectWidget plate = new RoundedRectWidget();
        row.addChild(plate);
        plate.setClickable(false).setRadius(9).setBeforeRenderCallback(() -> {
            plate.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 34);
            plate.setPosition(PAD + CARD_INSET, 4);
            plate.setColor(getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        TextFieldWidget field = new TextFieldWidget(FontManager.pf12) {
            @Override public boolean onKeyTyped(char character, int keyCode) {
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    perform(submit); return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        row.addChild(field);
        field.getTextField().setMaxStringLength(limit);
        field.setEnabled(true).setPlaceholder(placeholder).drawUnderline(false);
        controlBounds.put(field, () -> new PresetSettingsLayout.Box(PAD + CARD_INSET + 9, 9,
                Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2 - 18), 24));
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, plate.getWidth() - 18), 24);
            field.setPosition(PAD + CARD_INSET + 9, 9);
            field.setColor(getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        stroke(plate, 9, this::accent, () -> field.isFocused() ? .8 : 0);
        focus(field, null, field);
        return field;
    }

    private Panel footer(int count) {
        Panel row = strip(content, () -> PresetSettingsLayout.footerHeight(content.getWidth(), count));
        return row;
    }

    private RoundedButtonWidget footerButton(Panel footer, int index, int count, String glyph,
                                              Supplier<String> text, Runnable run, boolean primary) {
        RoundedButtonWidget button = action(footer, () -> glyph, text, FontManager.pf12bold,
                () -> primary ? 0xFFFFFF : getColor(NCMScreen.ColorType.PRIMARY_TEXT), () -> perform(run));
        button.setTooltip(text);
        controlBounds.put(button, () -> PresetSettingsLayout.footerButton(footer.getWidth(), index, count));
        button.setBeforeRenderCallback(() -> {
            PresetSettingsLayout.Box box = PresetSettingsLayout.footerButton(footer.getWidth(), index, count);
            button.setBounds(box.width, box.height);
            button.setPosition(box.x, box.y);
            button.setColor(primary ? mix(accent(), 0xFFFFFF, button.isHovering() ? .15 : 0)
                    : getColor(button.isHovering() ? NCMScreen.ColorType.ELEMENT_HOVER : NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        focus(button, run, null);
        return button;
    }

    /** Wrap critical status/policy text; source and width are part of the cache, and explicit newlines stay paragraphs. */
    private void paragraph(ScrollPanel feed, Supplier<String> source, IntSupplier color) {
        final List<String> lines = new ArrayList<>();
        final String[] previous = { null };
        final double[] width = { -1 };
        final long[] fontRevision = { -1 };
        Panel row = strip(feed, 1);
        DoubleSupplier measure = () -> {
            double span = Math.max(1, feed.getWidth() - (PAD + CARD_INSET) * 2);
            String text = source.get() == null ? "" : source.get();
            long metrics = FontManager.pf12.metricsRevision();
            if (!text.equals(previous[0]) || Math.abs(span - width[0]) >= .5 || metrics != fontRevision[0]) {
                lines.clear();
                if (!text.isEmpty()) for (String part : text.split("\n", -1))
                    lines.addAll(Arrays.asList(FontManager.pf12.fitWidth(part.trim(), span)));
                previous[0] = text; width[0] = span; fontRevision[0] = metrics;
            }
            return lines.isEmpty() ? 1 : Math.min(12, lines.size()) * 17 + 8;
        };
        rowHeights.put(row, measure);
        row.setBeforeRenderCallback(() -> row.setBounds(Math.max(1, feed.getWidth()), measure.getAsDouble()));
        row.setTooltip(source);
        for (int i = 0; i < 12; i++) {
            final int index = i;
            LabelWidget line = label(row, () -> index < lines.size() ? lines.get(index) : "", FontManager.pf12);
            line.setBeforeRenderCallback(() -> {
                line.setColor(color.getAsInt());
                line.setMaxWidth(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2));
                line.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                line.setPosition(PAD + CARD_INSET, inkTop(line, 12 + index * 17));
            });
        }
    }

    private void focus(AbstractWidget<?> widget, Runnable action, TextFieldWidget field) {
        final int index = focusTargets.size();
        focusTargets.add(new FocusTarget(widget, action, field));
        stroke(widget, 8, this::accent, () -> focusIndex == index ? .9 : 0);
    }

    @Override
    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        if (closing) return true;
        if (keyCode == Keyboard.KEY_ESCAPE) { handleEscape(); return true; }
        if (keyCode == Keyboard.KEY_TAB && !focusTargets.isEmpty()) {
            for (int i = 0; i < focusTargets.size(); i++) {
                TextFieldWidget field = focusTargets.get(i).field;
                if (field != null && field.isFocused()) focusIndex = i;
            }
            int step = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ? -1 : 1;
            if (focusIndex < 0 && step < 0) focusIndex = 0;
            focusIndex = Math.floorMod(focusIndex + step, focusTargets.size());
            for (int i = 0; i < focusTargets.size(); i++) {
                TextFieldWidget field = focusTargets.get(i).field;
                if (field != null) field.setFocused(i == focusIndex);
            }
            revealFocus();
            return true;
        }
        boolean editing = (nameInput != null && nameInput.isFocused()) || (importInput != null && importInput.isFocused());
        if (!editing && focusIndex >= 0 && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER || keyCode == Keyboard.KEY_SPACE)) {
            Runnable action = focusTargets.get(focusIndex).action;
            if (action != null) perform(action);
            return true;
        }
        if (!editing && content != null) {
            if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_NEXT) { content.forwardWheel(-1); return true; }
            if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_PRIOR) { content.forwardWheel(1); return true; }
            if (keyCode == Keyboard.KEY_HOME) { content.scrollToOffsetImmediately(0); return true; }
            if (keyCode == Keyboard.KEY_END) { content.scrollToEnd(); return true; }
        }
        return super.onKeyTypedReceived(typedChar, keyCode);
    }

    private void revealFocus() {
        FocusTarget target = focusTargets.get(focusIndex);
        AbstractWidget<?> row = target.widget;
        while (row.getParent() != null && row.getParent() != content) row = row.getParent();
        if (row.getParent() != content) return;
        double top = 0;
        for (AbstractWidget<?> item : content.getChildren()) {
            if (item == row) break;
            top += item.getHeight() + content.getSpacing();
        }
        AbstractWidget<?> item = target.widget;
        while (item != row) {
            top += item.getRelativeY();
            item = item.getParent();
        }
        // An off-screen control has not rendered its bounds yet. Reveal its row's start; the next frame measures it.
        double height = target.widget.getHeight() > 0 ? target.widget.getHeight() : PresetSettingsLayout.BUTTON_H;
        content.scrollToOffsetImmediately(PresetSettingsLayout.revealOffset(content.actualScrollOffset, top, height, content.getHeight()));
    }

    private int accent() { return getColor(NCMScreen.ColorType.ACCENT); }
    private int secondary() { return getColor(NCMScreen.ColorType.SECONDARY_TEXT); }
    private int toneColor(PresetPanelSession.Tone tone) {
        switch (tone) {
            case OK: return OK_COLOR;
            case BAD: return BAD_COLOR;
            case WARN: return WARN_COLOR;
            default: return secondary();
        }
    }
    private static final class FocusTarget {
        final AbstractWidget<?> widget;
        final Runnable action;
        final TextFieldWidget field;
        FocusTarget(AbstractWidget<?> widget, Runnable action, TextFieldWidget field) {
            this.widget = widget; this.action = action; this.field = field;
        }
    }
}
