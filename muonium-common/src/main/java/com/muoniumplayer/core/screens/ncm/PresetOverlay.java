package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 模板预设面板：内置风格与「我的预设」、套用（按分面勾选 + 预检提示）、存为预设、分享码、导入、撤销。
 *
 * <p>「有哪些内容、点了做什么、状态行说什么」都在 {@link PresetPanelSession}，H 键设置页里那个手画的
 * 弹窗（{@code HudPresetDialog}）用的是同一份；这里只管把它搭成组件树。与 {@link ViperOverlay}
 * 同一套版式：列表内容变化（导入 / 删除 / 保存）只置修订号，真正的重建放到 {@link #onRender}；
 * 换页（进套用页 / 存为预设页 / 返回）直接走，与其它浮层一致。</p>
 */
public final class PresetOverlay extends NcmSheet implements PresetPanelSession.Navigator {

    private static final double SHEET_W = 500.0;
    private static final double STATUS_TOP = 70.0;
    private static final double BODY_TOP = 92.0;
    private static final double CARD_H = 56.0;
    private static final double ROW_H = CARD_H + 7.0;
    private static final double LABEL_ROW = 22.0;
    private static final double FACET_ROW_H = 44.0;
    private static final double NOTE_ROW_H = 20.0;
    /** 页脚：一排按钮 + 一行说明。 */
    private static final double FOOTER_H = 84.0;
    private static final double CONTENT_MAX = 320.0;
    private static final double FIELD_H = 34.0;
    /** 行上文字块的左边缘 = 盘的左留白 + 盘宽 + 盘到文字的间距。 */
    private static final double TEXT_LEFT = 12.0 + 36.0 + 11.0;
    /** 我的预设：行右侧留给「分享」「删除」两颗键。 */
    private static final double RESERVE_USER = 80.0;
    /** 内置：行右侧留给「内置」胶囊。 */
    private static final double RESERVE_BUILTIN = 66.0;
    /** 套用页最多列几条提示，多出来的并成一句「另有 N 条」。 */
    private static final int NOTE_LIMIT = PresetPanelSession.NOTE_LIMIT;

    private final PresetPanelSession session = PresetPanelSession.standard();

    /** 主页是按哪一版列表建的；落后于 {@link PresetPanelSession#revision()} 就在 {@link #onRender} 里重建。 */
    private int revision = -1;

    // ---- 存为预设页 ----
    private TextFieldWidget nameInput;

    @Override
    public void onInit() {
        session.setNavigator(this);
        session.reset();
        showMain();
    }

    /** ESC：子页先回主页，主页再关。 */
    public void handleEscape() {
        if (session.page() == PresetPanelSession.Page.MAIN) {
            dispose();
        } else {
            back(this::showMain).run();
        }
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 重建放在这里：renderWidget 先调 onRender、之后才遍历子组件，此时清空子组件列表不会撞上遍历。
        if (!closing && revision != session.revision()) {
            if (session.page() == PresetPanelSession.Page.MAIN) {
                showMain();
            } else {
                revision = session.revision();
            }
        }
        super.onRender(mouseX, mouseY);
    }

    // ---- 换页（会话里的动作做完后经这里回来） ----

    @Override
    public void showMain(boolean backward) {
        if (backward) {
            back(this::showMain).run();
        } else {
            showMain();
        }
    }

    @Override
    public void showApply(StylePreset preset) {
        if (!closing) showApplyPage(preset);
    }

    // ════════════════════════════ 主页 ════════════════════════════

    private void showMain() {
        session.enterMain();
        revision = session.revision();
        final List<StylePreset> builtins = session.builtins();
        final List<StylePreset> mine = session.mine();
        beginPage();

        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP
                + Math.min(mainContentHeight(builtins.size(), mine.size()), CONTENT_MAX) + FOOTER_H);
        header(dialog, PartyIcons.EXPLORE, PresetPanelSession.TITLE,
                () -> PresetPanelSession.MAIN_SUBTITLE,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, () -> toneGlyph(session.statusTone()), session::statusText,
                () -> toneColor(session.statusTone()), () -> STATUS_TOP);

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停生长是绕着自己中心做的，贴着上边会被硬裁切掉圆角。
        strip(feed, 6.0);
        sectionLabel(strip(feed, LABEL_ROW), PresetPanelSession.SECTION_BUILTIN, () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        int slot = 0;
        for (StylePreset preset : builtins) {
            presetRow(strip(feed, ROW_H), slot++, preset);
        }
        strip(feed, 6.0);
        sectionLabel(strip(feed, LABEL_ROW), session.mineSectionTitle(mine.size()),
                () -> 5.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        if (mine.isEmpty()) {
            tip(strip(feed, 46.0), () -> PartyIcons.FOLDER_OPEN, () -> PresetPanelSession.EMPTY_MINE,
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> 10.0);
        }
        for (StylePreset preset : mine) {
            presetRow(strip(feed, ROW_H), slot++, preset);
        }
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 5, () -> PartyIcons.ADD, () -> "存为预设", this::showSave);
        footerButton(dialog, 1, 5, () -> PartyIcons.LINK, () -> "粘贴导入", session::importFromClipboard);
        footerButton(dialog, 2, 5, () -> PartyIcons.FOLDER_OPEN,
                () -> session.picking() ? "选择中…" : "文件导入", session::browseForFile);
        RoundedButtonWidget undo = footerButton(dialog, 3, 5, () -> PartyIcons.SYNC, () -> "撤销套用", session::undo);
        undo.setTooltip(session::undoTooltip);
        footerButton(dialog, 4, 5, () -> PartyIcons.FOLDER_OPEN, () -> "打开目录", session::openFolder);
        tip(dialog, () -> PartyIcons.INFO, () -> PresetPanelSession.MAIN_NOTE,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 34.0);
    }

    private double mainContentHeight(int builtins, int mine) {
        return 6.0 + LABEL_ROW + ROW_H * builtins + 6.0 + LABEL_ROW + (mine == 0 ? 46.0 : ROW_H * mine) + 10.0;
    }

    /** 一张预设卡：点卡片进套用页；我的预设右侧有「复制分享码」与「删除」。 */
    private void presetRow(final Panel row, final int slot, final StylePreset preset) {
        final boolean mine = preset.source() == StylePreset.Source.USER;
        final double reserve = mine ? RESERVE_USER : RESERVE_BUILTIN;
        final Card[] self = new Card[1];
        // 入场闸门按 slot 递推，预设数量不可控，序号必须夹住（与 ViPER 列表同因）。
        final Card card = card(row, Math.min(slot, 9), () -> 0.0, () -> CARD_H,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showApplyPage(preset);
            return true;
        });

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setBeforeRenderCallback(() -> {
            disc.setRadius(11);
            disc.setBounds(36.0, 36.0);
            disc.setPosition(12.0, card.getHeight() * .5 - 18.0);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ACCENT), mine ? .22 : .34));
        });
        icon(disc, PresetPanelSession.iconOf(preset), ICON_M, () -> 18.0, () -> 18.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final LabelWidget title = label(card, preset.meta().name(), FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(TEXT_LEFT, inkTop(title, card.getHeight() * .5 - 9));
        });

        final String noteText = PresetPanelSession.cardNote(preset);
        final LabelWidget note = label(card, noteText, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(TEXT_LEFT, inkTop(note, card.getHeight() * .5 + 9));
        });

        if (mine) {
            rowButton(card, 64.0, PartyIcons.LINK, "复制分享码", false, () -> session.copyShareCode(preset));
            rowButton(card, 34.0, PartyIcons.CLOSE, "删除", true, () -> confirmDelete(preset));
        } else {
            pill(card, () -> PartyIcons.CHECK, () -> "内置",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                    () -> card.getWidth() - 14.0, () -> card.getHeight() * .5);
        }
    }

    /** 卡片右侧的小方键；{@code fromRight} 是键左缘到卡片右缘的距离。 */
    private void rowButton(final Card card, final double fromRight, final String glyph, final String tooltip,
                           final boolean danger, final Runnable onClick) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(9);
        button.setShouldOverrideMouseCursor(true);
        button.setTooltip(tooltip);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            onClick.run();
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            button.setBounds(26, 26);
            button.setPosition(card.getWidth() - fromRight, card.getHeight() * .5 - 13);
            button.setColor(button.isHovering()
                    ? (danger ? BAD_COLOR : NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            button.setTextColor(0x00FFFFFF);
        });
        icon(button, glyph, ICON_S, () -> 13.0, () -> 13.0,
                () -> button.isHovering() ? 0xFFFFFF
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }

    /** 删除要二次确认：预设往往是自己调了半天存下的，删了只能从分享码或文件找回。 */
    private void confirmDelete(final StylePreset preset) {
        NCMScreen.getInstance().openConfirmation(PresetPanelSession.DELETE_TITLE,
                PresetPanelSession.deleteMessage(preset), "删除", () -> session.delete(preset));
    }

    // ════════════════════════════ 套用页 ════════════════════════════

    private void showApplyPage(final StylePreset preset) {
        session.enterApply(preset);
        beginPage();

        final List<Facet> look = session.facetsOf(preset, Facet.Group.APPEARANCE);
        final List<Facet> audio = session.facetsOf(preset, Facet.Group.AUDIO);
        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP
                + Math.min(applyContentHeight(look.size(), audio.size()), CONTENT_MAX) + FOOTER_H);
        subHeader(dialog, back(this::showMain), () -> preset.meta().name(),
                () -> PresetPanelSession.applySubtitle(preset),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, () -> toneGlyph(session.previewTone()), session::previewStatus,
                () -> toneColor(session.previewTone()), () -> STATUS_TOP);

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        strip(feed, 6.0);
        if (look.isEmpty() && audio.isEmpty()) {
            tip(strip(feed, 46.0), () -> PartyIcons.INFO, () -> PresetPanelSession.APPLY_EMPTY,
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> 10.0);
        }
        facetSection(feed, "外观", look, session::isSelected, session::toggle);
        facetSection(feed, "音效", audio, session::isSelected, session::toggle);

        // 提示区：行数跟着勾选实时变化，用可变高度的行而不是重建整页（重建会让卡片重新入场）。
        final Panel noteHeader = strip(feed, () -> session.previewLines().isEmpty() ? 1.0 : 6.0 + LABEL_ROW);
        final Panel noteHeaderVeil = veil(noteHeader, () -> session.previewLines().isEmpty() ? 0.0 : 1.0);
        sectionLabel(noteHeaderVeil, "注意", () -> 11.0, () -> WARN_COLOR);
        for (int i = 0; i < NOTE_LIMIT; i++) {
            final int index = i;
            final Supplier<String> line = () -> session.noteLine(index);
            final Panel row = strip(feed, () -> line.get().isEmpty() ? 1.0 : NOTE_ROW_H);
            final Panel veil = veil(row, () -> line.get().isEmpty() ? 0.0 : 1.0);
            final LabelWidget text = label(veil, line, FontManager.pf12);
            text.setBeforeRenderCallback(() -> {
                text.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                text.setMaxWidth(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2 - 10));
                text.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                text.setPosition(PAD + CARD_INSET + 10, inkTop(text, NOTE_ROW_H * .5));
            });
        }
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 4, () -> PartyIcons.CHECK, () -> "全选", session::selectAll);
        footerButton(dialog, 1, 4, () -> PartyIcons.TEXT_FORMAT, () -> "只选外观", session::selectAppearanceOnly);
        footerButton(dialog, 2, 4, () -> PartyIcons.HEADPHONES, () -> "只选音效", session::selectAudioOnly);
        primaryButton(dialog, 3, 4, () -> PartyIcons.CHECK, session::applyLabel, session::applyTarget);
        tip(dialog, () -> PartyIcons.INFO, () -> PresetPanelSession.APPLY_NOTE,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 34.0);
    }

    private double applyContentHeight(int look, int audio) {
        double height = 6.0;
        if (look == 0 && audio == 0) height += 46.0;
        if (look > 0) height += LABEL_ROW + FACET_ROW_H * look + 6.0;
        if (audio > 0) height += LABEL_ROW + FACET_ROW_H * audio + 6.0;
        List<String> lines = session.previewLines();
        if (!lines.isEmpty()) height += 6.0 + LABEL_ROW + NOTE_ROW_H * Math.min(lines.size(), NOTE_LIMIT);
        return height + 10.0;
    }

    // ════════════════════════════ 存为预设 ════════════════════════════

    private void showSave() {
        session.enterSave();
        beginPage();

        final List<Facet> look = session.facetsAvailable(Facet.Group.APPEARANCE);
        final List<Facet> audio = session.facetsAvailable(Facet.Group.AUDIO);
        final double listTop = BODY_TOP + FIELD_H + 10.0;
        final Panel dialog = sheet(SHEET_W, () -> listTop
                + Math.min(saveContentHeight(look.size(), audio.size()), CONTENT_MAX - FIELD_H) + FOOTER_H);
        subHeader(dialog, back(this::showMain), () -> PresetPanelSession.SAVE_TITLE,
                () -> PresetPanelSession.SAVE_SUBTITLE,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, () -> session.saveHintBad() ? PartyIcons.WARNING : PartyIcons.INFO, session::saveHint,
                () -> session.saveHintBad() ? BAD_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> STATUS_TOP);

        final RoundedRectWidget field = new RoundedRectWidget();
        dialog.addChild(field);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), FIELD_H);
            field.setPosition(PAD + CARD_INSET, BODY_TOP);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        // 回车即保存：TextFieldWidget 聚焦后吞掉所有按键，默认没人处理回车。
        final CFontRenderer font = FontManager.pf12;
        nameInput = new TextFieldWidget(font) {
            @Override
            public boolean onKeyTyped(char character, int keyCode) {
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    session.save(nameText(), false);
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        dialog.addChild(nameInput);
        nameInput.getTextField().setMaxStringLength(PresetLimits.MAX_NAME_CHARS);
        nameInput.setEnabled(true).setFocused(true);
        nameInput.setPlaceholder(PresetPanelSession.NAME_PLACEHOLDER);
        nameInput.drawUnderline(false);
        nameInput.setBeforeRenderCallback(() -> {
            nameInput.setBounds(Math.max(1, field.getWidth() - 20), Math.max(1, field.getHeight() - 10));
            nameInput.setPosition(field.getRelativeX() + 10, field.getRelativeY() + 5);
            nameInput.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            nameInput.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> nameInput.isFocused() ? .7 : 0.0);

        final Panel body = body(dialog, () -> listTop, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        strip(feed, 4.0);
        facetSection(feed, "外观", look, session::isSaveSelected, session::toggleSave);
        facetSection(feed, "音效", audio, session::isSaveSelected, session::toggleSave);
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 4, () -> PartyIcons.TEXT_FORMAT, () -> "只选外观", session::saveSelectAppearanceOnly);
        footerButton(dialog, 1, 4, () -> PartyIcons.HEADPHONES, () -> "只选音效", session::saveSelectAudioOnly);
        footerButton(dialog, 2, 4, () -> PartyIcons.CHECK, () -> "保存", () -> session.save(nameText(), false));
        primaryButton(dialog, 3, 4, () -> PartyIcons.LINK, () -> "保存并复制分享码", () -> session.save(nameText(), true));
        tip(dialog, () -> PartyIcons.INFO, () -> PresetPanelSession.SAVE_NOTE,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 34.0);
    }

    private double saveContentHeight(int look, int audio) {
        double height = 4.0;
        if (look > 0) height += LABEL_ROW + FACET_ROW_H * look + 6.0;
        if (audio > 0) height += LABEL_ROW + FACET_ROW_H * audio + 6.0;
        return height + 10.0;
    }

    private String nameText() {
        return nameInput == null || nameInput.getText() == null ? "" : nameInput.getText();
    }

    // ════════════════════════════ 共用 ════════════════════════════

    /** 一组分面开关：分组小标题 + 每个分面一行（标题 + 说明 + 开关）。 */
    private void facetSection(final ScrollPanel feed, final String title, final List<Facet> facets,
                              final Predicate<Facet> chosen, final Consumer<Facet> onToggle) {
        if (facets.isEmpty()) return;
        sectionLabel(strip(feed, LABEL_ROW), title, () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        for (final Facet facet : facets) {
            final Panel row = strip(feed, FACET_ROW_H);
            final LabelWidget caption = label(row, PresetPanelSession.facetCaption(facet), FontManager.pf14bold);
            caption.setBeforeRenderCallback(() -> {
                caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                caption.setPosition(PAD + CARD_INSET, inkTop(caption, 14.0));
            });
            final LabelWidget detail = label(row, facet.description(), FontManager.pf12);
            detail.setBeforeRenderCallback(() -> {
                detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                detail.setMaxWidth(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2 - 52.0));
                detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                detail.setPosition(PAD + CARD_INSET, inkTop(detail, 31.0));
            });
            toggle(row, () -> chosen.test(facet), () -> onToggle.accept(facet),
                    () -> row.getWidth() - PAD - CARD_INSET, () -> row.getHeight() * .5,
                    () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        }
        strip(feed, 6.0);
    }

    private RoundedButtonWidget footerButton(final Panel dialog, final int index, final int count,
                                             final Supplier<String> glyph, final Supplier<String> text,
                                             final Runnable onClick) {
        final RoundedButtonWidget button = action(dialog, glyph, text, FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), onClick);
        button.setBeforeRenderCallback(() -> {
            layoutFooterButton(dialog, button, index, count);
            button.setColor(button.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        return button;
    }

    /** 主操作键：实心强调色，白字。 */
    private void primaryButton(final Panel dialog, final int index, final int count,
                               final Supplier<String> glyph, final Supplier<String> text, final Runnable onClick) {
        final RoundedButtonWidget button = action(dialog, glyph, text, FontManager.pf12bold,
                () -> 0xFFFFFF, onClick);
        button.setBeforeRenderCallback(() -> {
            layoutFooterButton(dialog, button, index, count);
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            button.setColor(mix(accent, 0xFFFFFF, button.isHovering() ? .16 : 0.0));
        });
    }

    private static void layoutFooterButton(Panel dialog, RoundedButtonWidget button, int index, int count) {
        double span = Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2);
        double width = Math.max(1, (span - 8.0 * (count - 1)) / count);
        button.setBounds(width, 30);
        button.setPosition(PAD + CARD_INSET + (width + 8.0) * index, dialog.getHeight() - FOOTER_H + 10.0);
    }

    // ════════════════════════════ 状态行 ════════════════════════════

    private static int toneColor(PresetPanelSession.Tone tone) {
        switch (tone) {
            case OK:
                return OK_COLOR;
            case BAD:
                return BAD_COLOR;
            case WARN:
                return WARN_COLOR;
            default:
                return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        }
    }

    private static String toneGlyph(PresetPanelSession.Tone tone) {
        switch (tone) {
            case OK:
                return PartyIcons.CHECK;
            case BAD:
                return PartyIcons.WARNING;
            case WARN:
                return PartyIcons.HOURGLASS;
            default:
                return PartyIcons.INFO;
        }
    }
}
