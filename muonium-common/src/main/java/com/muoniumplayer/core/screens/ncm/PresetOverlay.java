package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.preset.PresetService;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 模板预设面板：内置风格与「我的预设」、套用（按分面勾选 + 预检提示）、存为预设、分享码、导入、撤销。
 *
 * <p>所有逻辑都在 {@link PresetService}；这里只管「有哪些内容、点了做什么」。与 {@link ViperOverlay}
 * 同一套版式：列表内容变化（导入 / 删除 / 保存）只置修订号，真正的重建放到 {@link #onRender}；
 * 换页（进套用页 / 存为预设页 / 返回）直接走，与其它浮层一致。</p>
 */
public final class PresetOverlay extends NcmSheet {

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
    private static final int NOTE_LIMIT = 5;

    private enum Page { MAIN, APPLY, SAVE }

    private final PresetService service = PresetService.get();

    private Page current = Page.MAIN;
    private volatile String statusText = "";
    /** 状态行语气：0 说明、1 成功、2 失败、3 提醒。 */
    private volatile int statusTone;

    /** 列表修订号：导入 / 删除 / 保存后自增，{@link #onRender} 看到落后就重建主页。 */
    private int revision = -1;
    private int pendingRevision;

    /** 系统文件框开着：防连点。 */
    private volatile boolean picking;
    private volatile boolean pickerUnavailable;

    // ---- 套用页 ----
    private StylePreset target;
    private final Set<Facet> selection = EnumSet.noneOf(Facet.class);
    private List<String> previewLines = Collections.emptyList();
    private int previewChanges;
    private boolean previewFailed;

    // ---- 存为预设页 ----
    private TextFieldWidget nameInput;
    private final Set<Facet> saveSelection = EnumSet.noneOf(Facet.class);
    private String saveHint = "";
    private boolean saveHintBad;

    @Override
    public void onInit() {
        statusText = idleStatus();
        statusTone = 0;
        showMain();
    }

    /** ESC：子页先回主页，主页再关。 */
    public void handleEscape() {
        if (current == Page.MAIN) {
            dispose();
        } else {
            back(this::showMain).run();
        }
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 重建放在这里：renderWidget 先调 onRender、之后才遍历子组件，此时清空子组件列表不会撞上遍历。
        if (!closing && revision != pendingRevision) {
            if (current == Page.MAIN) {
                showMain();
            } else {
                revision = pendingRevision;
            }
        }
        super.onRender(mouseX, mouseY);
    }

    private void requestRebuild() {
        pendingRevision++;
    }

    private String idleStatus() {
        return service.canUndo()
                ? "点一张卡片查看内容并套用 · 每次套用前都会自动备份，可以逐级撤销"
                : "点一张卡片查看内容并套用 · 套用前会自动备份，随时可以撤销";
    }

    // ════════════════════════════ 主页 ════════════════════════════

    private void showMain() {
        current = Page.MAIN;
        revision = pendingRevision;
        final List<StylePreset> builtins = service.builtins();
        final List<StylePreset> mine = service.userPresets();
        beginPage();

        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP
                + Math.min(mainContentHeight(builtins.size(), mine.size()), CONTENT_MAX) + FOOTER_H);
        header(dialog, PartyIcons.EXPLORE, "模板预设",
                () -> "一键换整套外观与音效 · 存下自己的配置 · 用分享码互相交换",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> STATUS_TOP);

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停生长是绕着自己中心做的，贴着上边会被硬裁切掉圆角。
        strip(feed, 6.0);
        sectionLabel(strip(feed, LABEL_ROW), "内置风格", () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        int slot = 0;
        for (StylePreset preset : builtins) {
            presetRow(strip(feed, ROW_H), slot++, preset);
        }
        strip(feed, 6.0);
        sectionLabel(strip(feed, LABEL_ROW), mine.isEmpty() ? "我的预设" : "我的预设（" + mine.size() + "）",
                () -> 5.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        if (mine.isEmpty()) {
            tip(strip(feed, 46.0), () -> PartyIcons.FOLDER_OPEN,
                    () -> "还没有：点「存为预设」存下当前配置，或「粘贴导入」别人发来的分享码",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> 10.0);
        }
        for (StylePreset preset : mine) {
            presetRow(strip(feed, ROW_H), slot++, preset);
        }
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 5, () -> PartyIcons.ADD, () -> "存为预设", this::showSave);
        footerButton(dialog, 1, 5, () -> PartyIcons.LINK, () -> "粘贴导入", this::importFromClipboard);
        footerButton(dialog, 2, 5, () -> PartyIcons.FOLDER_OPEN,
                () -> picking ? "选择中…" : "文件导入", this::browseForFile);
        RoundedButtonWidget undo = footerButton(dialog, 3, 5, () -> PartyIcons.SYNC, () -> "撤销套用", this::undo);
        undo.setTooltip(() -> service.canUndo() ? "回到上一次套用之前的样子（可以逐级往回撤）" : "还没有可以撤销的套用");
        footerButton(dialog, 4, 5, () -> PartyIcons.FOLDER_OPEN, () -> "打开目录", this::openFolder);
        tip(dialog, () -> PartyIcons.INFO,
                () -> "目录：config/muonium/presets/ · 预设是 .mpreset.json 文本文件，也可以直接发文件",
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
            showApply(preset);
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
        icon(disc, glyphOf(preset), ICON_M, () -> 18.0, () -> 18.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final LabelWidget title = label(card, preset.meta().name(), FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(TEXT_LEFT, inkTop(title, card.getHeight() * .5 - 9));
        });

        final String noteText = mine || preset.meta().description().isEmpty()
                ? summary(preset) : preset.meta().description();
        final LabelWidget note = label(card, noteText, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(TEXT_LEFT, inkTop(note, card.getHeight() * .5 + 9));
        });

        if (mine) {
            rowButton(card, 64.0, PartyIcons.LINK, "复制分享码", false, () -> copyShareCode(preset));
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

    private static String glyphOf(StylePreset preset) {
        if (preset.source() == StylePreset.Source.USER) {
            return preset.touches(Facet.Group.AUDIO) && !preset.touches(Facet.Group.APPEARANCE)
                    ? PartyIcons.HEADPHONES : PartyIcons.PERSON;
        }
        return "builtin:minimal".equals(preset.meta().id()) ? PartyIcons.NOTE : PartyIcons.SWEEP;
    }

    /** 卡片副标题：分面不多就列名字，多了只说「外观 + 音效 · N 个部分」。 */
    private static String summary(StylePreset preset) {
        List<String> names = new ArrayList<String>();
        for (Facet facet : Facet.values()) {
            if (preset.facets().contains(facet)) {
                names.add(facet.displayName());
            }
        }
        String body;
        if (names.isEmpty()) {
            body = "这个预设里没有本版本认识的内容";
        } else if (names.size() <= 3) {
            body = String.join(" · ", names);
        } else {
            boolean look = preset.touches(Facet.Group.APPEARANCE);
            boolean audio = preset.touches(Facet.Group.AUDIO);
            body = (look && audio ? "外观 + 音效" : look ? "外观" : "音效") + " · " + names.size() + " 个部分";
        }
        String author = preset.meta().author();
        return author.isEmpty() ? body : body + " · by " + author;
    }

    private void copyShareCode(StylePreset preset) {
        try {
            String text = service.shareText(preset);
            KeyboardUtils.setClipboardString(text);
            setStatus("已复制「" + preset.meta().name() + "」的分享码（" + text.length()
                    + " 字）· 发给朋友，对方点「粘贴导入」即可", 1);
        } catch (Throwable failure) {
            setStatus("生成分享码失败：" + failure.getMessage(), 2);
        }
    }

    /** 删除要二次确认：预设往往是自己调了半天存下的，删了只能从分享码或文件找回。 */
    private void confirmDelete(StylePreset preset) {
        final String name = preset.meta().name();
        NCMScreen.getInstance().openConfirmation("删除预设",
                "将删除「" + name + "」的文件；删了只能从分享码或备份的文件找回。", "删除", () -> {
                    if (service.delete(preset)) {
                        setStatus("已删除「" + name + "」", 0);
                    } else {
                        setStatus("删除失败：文件可能已被移走", 2);
                    }
                    requestRebuild();
                });
    }

    private void importFromClipboard() {
        String text = KeyboardUtils.getClipboardString();
        try {
            StylePreset imported = service.importText(text);
            setStatus("已导入「" + imported.meta().name() + "」· 在「我的预设」里", 1);
            requestRebuild();
            showApply(imported);
        } catch (PresetFormatException bad) {
            setStatus(bad.getMessage(), 2);
        } catch (Throwable failure) {
            setStatus("导入失败：" + failure.getMessage(), 2);
        }
    }

    /**
     * 系统文件框挑一个预设文件。与 ViPER / 字体导入同一套路：反射调 LWJGL 带下来的 tinyfd，
     * 原生对话框是模态的，必须单开线程，在渲染线程上弹会把整个游戏冻在那一帧。
     */
    private void browseForFile() {
        if (picking) return;
        picking = true;
        setStatus("已打开系统文件选择框，请挑一个模板预设文件（.mpreset.json）", 0);
        final Thread picker = new Thread(() -> {
            final String picked = nativeOpenDialog();
            MultiThreadingUtil.runOnMainThread(() -> {
                picking = false;
                if (picked == null || picked.trim().isEmpty()) {
                    if (pickerUnavailable) {
                        setStatus("这个版本打不开系统文件框：把文件放进 presets 目录，或复制里面的内容后点「粘贴导入」", 2);
                    } else {
                        setStatus("没有选择文件", 0);
                    }
                    return;
                }
                try {
                    StylePreset imported = service.importFile(new File(picked.trim()));
                    setStatus("已导入「" + imported.meta().name() + "」· 在「我的预设」里", 1);
                    requestRebuild();
                    if (!closing) showApply(imported);
                } catch (PresetFormatException bad) {
                    setStatus(bad.getMessage(), 2);
                } catch (Throwable failure) {
                    setStatus("导入失败：" + failure.getMessage(), 2);
                }
            });
        }, "MuoniumPlayer-PresetPicker");
        picker.setDaemon(true);
        picker.start();
    }

    private String nativeOpenDialog() {
        pickerUnavailable = false;
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
            java.lang.reflect.Method open = dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointerBuffer,
                    CharSequence.class, boolean.class);
            Object result = open.invoke(null, "选择模板预设文件", "", null, null, Boolean.FALSE);
            return result == null ? null : result.toString();
        } catch (Throwable unavailable) {
            pickerUnavailable = true;
            return null;
        }
    }

    private void undo() {
        if (!service.canUndo()) {
            setStatus("还没有可以撤销的套用", 0);
            return;
        }
        CompatReport report = service.undo();
        if (report == null) {
            setStatus("备份读不出来，没法撤销", 2);
        } else if (report.hasFailures()) {
            setStatus(report.failures().get(0), 2);
        } else {
            setStatus("已撤销上一次套用" + (service.canUndo() ? " · 还可以继续往回撤" : ""), 1);
        }
    }

    private void openFolder() {
        File dir = ConfigPaths.PRESETS_DIR;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            setStatus("建不了目录 " + dir.getAbsolutePath(), 2);
            return;
        }
        if (BrowserUtils.open(dir.getAbsoluteFile().toURI().toString())) {
            setStatus("已打开预设目录", 0);
        } else {
            setStatus("打不开目录，路径是 " + dir.getAbsolutePath(), 2);
        }
    }

    // ════════════════════════════ 套用页 ════════════════════════════

    private void showApply(final StylePreset preset) {
        current = Page.APPLY;
        target = preset;
        selection.clear();
        selection.addAll(service.defaultSelection(preset));
        refreshPreview();
        beginPage();

        final List<Facet> look = facetsOf(preset, Facet.Group.APPEARANCE);
        final List<Facet> audio = facetsOf(preset, Facet.Group.AUDIO);
        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP
                + Math.min(applyContentHeight(look.size(), audio.size()), CONTENT_MAX) + FOOTER_H);
        subHeader(dialog, back(this::showMain), () -> preset.meta().name(),
                () -> preset.meta().description().isEmpty() ? summary(preset) : preset.meta().description(),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, this::previewGlyph, this::previewStatus, this::previewColor, () -> STATUS_TOP);

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        strip(feed, 6.0);
        if (look.isEmpty() && audio.isEmpty()) {
            tip(strip(feed, 46.0), () -> PartyIcons.INFO,
                    () -> "这个预设里没有本版本支持的内容（可能来自更新的版本）",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> 10.0);
        }
        facetSection(feed, "外观", look, selection, this::refreshPreview);
        facetSection(feed, "音效", audio, selection, this::refreshPreview);

        // 提示区：行数跟着勾选实时变化，用可变高度的行而不是重建整页（重建会让卡片重新入场）。
        final Panel noteHeader = strip(feed, () -> previewLines.isEmpty() ? 1.0 : 6.0 + LABEL_ROW);
        final Panel noteHeaderVeil = veil(noteHeader, () -> previewLines.isEmpty() ? 0.0 : 1.0);
        sectionLabel(noteHeaderVeil, "注意", () -> 11.0, () -> WARN_COLOR);
        for (int i = 0; i < NOTE_LIMIT; i++) {
            final int index = i;
            final Supplier<String> line = () -> noteLine(index);
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
        footerButton(dialog, 0, 4, () -> PartyIcons.CHECK, () -> "全选", () -> {
            selection.clear();
            selection.addAll(look);
            selection.addAll(audio);
            refreshPreview();
        });
        footerButton(dialog, 1, 4, () -> PartyIcons.TEXT_FORMAT, () -> "只选外观", () -> {
            selection.clear();
            selection.addAll(Facet.withoutOptIn(withAll(look)));
            refreshPreview();
        });
        footerButton(dialog, 2, 4, () -> PartyIcons.HEADPHONES, () -> "只选音效", () -> {
            selection.clear();
            selection.addAll(audio);
            refreshPreview();
        });
        primaryButton(dialog, 3, 4, () -> PartyIcons.CHECK,
                () -> previewChanges > 0 ? "套用 " + previewChanges + " 项" : "套用", this::applyTarget);
        tip(dialog, () -> PartyIcons.INFO,
                () -> "布局位置与画质性能默认不勾：它们和屏幕、机器有关，照搬别人的多半不合适",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 34.0);
    }

    private double applyContentHeight(int look, int audio) {
        double height = 6.0;
        if (look == 0 && audio == 0) height += 46.0;
        if (look > 0) height += LABEL_ROW + FACET_ROW_H * look + 6.0;
        if (audio > 0) height += LABEL_ROW + FACET_ROW_H * audio + 6.0;
        if (!previewLines.isEmpty()) height += 6.0 + LABEL_ROW + NOTE_ROW_H * Math.min(previewLines.size(), NOTE_LIMIT);
        return height + 10.0;
    }

    private String noteLine(int index) {
        List<String> lines = previewLines;
        if (index >= lines.size()) return "";
        if (index == NOTE_LIMIT - 1 && lines.size() > NOTE_LIMIT) {
            return "· 另有 " + (lines.size() - NOTE_LIMIT + 1) + " 条提示";
        }
        return "· " + lines.get(index);
    }

    /** 预设声明的、本版本能套用的、属于这一组的分面（按分面表顺序）。 */
    private List<Facet> facetsOf(StylePreset preset, Facet.Group group) {
        List<Facet> facets = new ArrayList<Facet>();
        Set<Facet> available = service.engine().availableFacets();
        for (Facet facet : Facet.values()) {
            if (facet.group() == group && preset.facets().contains(facet) && available.contains(facet)) {
                facets.add(facet);
            }
        }
        return facets;
    }

    private static Set<Facet> withAll(List<Facet> facets) {
        Set<Facet> set = EnumSet.noneOf(Facet.class);
        set.addAll(facets);
        return set;
    }

    private void refreshPreview() {
        if (target == null) return;
        try {
            CompatReport report = service.preview(target, selection);
            previewLines = report.lines();
            previewChanges = report.changedKeys();
            previewFailed = report.hasFailures();
        } catch (Throwable failure) {
            previewLines = Collections.singletonList("预检失败：" + failure.getMessage());
            previewChanges = 0;
            previewFailed = true;
        }
    }

    private String previewStatus() {
        if (selection.isEmpty()) return "先勾选要套用的部分";
        if (previewFailed) return "有部分设置套用不了，详见下方";
        if (previewChanges == 0) return "所选部分和你现在的配置一样，套用不会有变化";
        return "将改动 " + previewChanges + " 项设置" + (previewLines.isEmpty() ? "" : " · 有 " + previewLines.size() + " 条提示")
                + " · 套用前自动备份";
    }

    private int previewColor() {
        if (previewFailed) return BAD_COLOR;
        if (selection.isEmpty() || !previewLines.isEmpty()) return WARN_COLOR;
        return previewChanges > 0 ? OK_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String previewGlyph() {
        if (previewFailed) return PartyIcons.WARNING;
        if (selection.isEmpty() || !previewLines.isEmpty()) return PartyIcons.HOURGLASS;
        return previewChanges > 0 ? PartyIcons.CHECK : PartyIcons.INFO;
    }

    private void applyTarget() {
        if (target == null) return;
        if (selection.isEmpty()) {
            refreshPreview();
            return;
        }
        final StylePreset preset = target;
        CompatReport report = service.apply(preset, selection);
        String name = preset.meta().name();
        if (report.hasFailures()) {
            setStatus(report.failures().get(0), 2);
        } else if (report.changedKeys() == 0) {
            setStatus("「" + name + "」和你现在的配置一样，没有需要改的", 0);
        } else {
            List<String> lines = report.lines();
            setStatus("已套用「" + name + "」· 改动 " + report.changedKeys() + " 项"
                            + (lines.isEmpty() ? " · 不满意可以点「撤销套用」" : " · " + lines.get(0)),
                    lines.isEmpty() ? 1 : 3);
        }
        back(this::showMain).run();
    }

    // ════════════════════════════ 存为预设 ════════════════════════════

    private void showSave() {
        current = Page.SAVE;
        saveSelection.clear();
        saveSelection.addAll(Facet.withoutOptIn(service.engine().availableFacets()));
        saveHint = "勾选要存进去的部分 · 只选外观或只选音效，就能单独分享那一部分";
        saveHintBad = false;
        beginPage();

        final List<Facet> look = facetsAvailable(Facet.Group.APPEARANCE);
        final List<Facet> audio = facetsAvailable(Facet.Group.AUDIO);
        final double listTop = BODY_TOP + FIELD_H + 10.0;
        final Panel dialog = sheet(SHEET_W, () -> listTop
                + Math.min(saveContentHeight(look.size(), audio.size()), CONTENT_MAX - FIELD_H) + FOOTER_H);
        subHeader(dialog, back(this::showMain), () -> "存为预设",
                () -> "把当前配置的所选部分存下来 · 存好后可以复制分享码发给别人",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, () -> saveHintBad ? PartyIcons.WARNING : PartyIcons.INFO, () -> saveHint,
                () -> saveHintBad ? BAD_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
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
                    save(false);
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        dialog.addChild(nameInput);
        nameInput.getTextField().setMaxStringLength(PresetLimits.MAX_NAME_CHARS);
        nameInput.setEnabled(true).setFocused(true);
        nameInput.setPlaceholder("给这套配置起个名字，比如「夜间沉浸」「直播用」");
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
        Runnable onToggle = () -> {
            saveHint = saveSelection.isEmpty() ? "至少勾选一个部分" : "已选 " + saveSelection.size() + " 个部分";
            saveHintBad = saveSelection.isEmpty();
        };
        facetSection(feed, "外观", look, saveSelection, onToggle);
        facetSection(feed, "音效", audio, saveSelection, onToggle);
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 4, () -> PartyIcons.TEXT_FORMAT, () -> "只选外观", () -> {
            saveSelection.clear();
            saveSelection.addAll(Facet.withoutOptIn(withAll(look)));
            onToggle.run();
        });
        footerButton(dialog, 1, 4, () -> PartyIcons.HEADPHONES, () -> "只选音效", () -> {
            saveSelection.clear();
            saveSelection.addAll(audio);
            onToggle.run();
        });
        footerButton(dialog, 2, 4, () -> PartyIcons.CHECK, () -> "保存", () -> save(false));
        primaryButton(dialog, 3, 4, () -> PartyIcons.LINK, () -> "保存并复制分享码", () -> save(true));
        tip(dialog, () -> PartyIcons.INFO,
                () -> "账号、Cookie、音源与缓存永远不会进预设；字体文件、卷积核、自定义图片也不随分享走",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 34.0);
    }

    private double saveContentHeight(int look, int audio) {
        double height = 4.0;
        if (look > 0) height += LABEL_ROW + FACET_ROW_H * look + 6.0;
        if (audio > 0) height += LABEL_ROW + FACET_ROW_H * audio + 6.0;
        return height + 10.0;
    }

    private List<Facet> facetsAvailable(Facet.Group group) {
        List<Facet> facets = new ArrayList<Facet>();
        Set<Facet> available = service.engine().availableFacets();
        for (Facet facet : Facet.values()) {
            if (facet.group() == group && available.contains(facet)) {
                facets.add(facet);
            }
        }
        return facets;
    }

    private void save(boolean copy) {
        String name = nameInput == null || nameInput.getText() == null ? "" : nameInput.getText().trim();
        if (name.isEmpty()) {
            saveHint = "先起个名字";
            saveHintBad = true;
            return;
        }
        if (saveSelection.isEmpty()) {
            saveHint = "至少勾选一个部分";
            saveHintBad = true;
            return;
        }
        try {
            StylePreset saved = service.saveCurrent(name, saveSelection);
            if (copy) {
                String text = service.shareText(saved);
                KeyboardUtils.setClipboardString(text);
                setStatus("已保存「" + saved.meta().name() + "」并复制分享码（" + text.length() + " 字）", 1);
            } else {
                setStatus("已保存「" + saved.meta().name() + "」· 卡片右侧可以复制分享码", 1);
            }
            requestRebuild();
            back(this::showMain).run();
        } catch (Throwable failure) {
            saveHint = "保存失败：" + failure.getMessage();
            saveHintBad = true;
        }
    }

    // ════════════════════════════ 共用 ════════════════════════════

    /** 一组分面开关：分组小标题 + 每个分面一行（标题 + 说明 + 开关）。 */
    private void facetSection(final ScrollPanel feed, final String title, final List<Facet> facets,
                              final Set<Facet> chosen, final Runnable onChange) {
        if (facets.isEmpty()) return;
        sectionLabel(strip(feed, LABEL_ROW), title, () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        for (final Facet facet : facets) {
            final Panel row = strip(feed, FACET_ROW_H);
            final LabelWidget caption = label(row, facet.displayName() + (facet.optIn() ? "（默认不勾）" : ""),
                    FontManager.pf14bold);
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
            toggle(row, () -> chosen.contains(facet), () -> {
                if (!chosen.remove(facet)) chosen.add(facet);
                onChange.run();
            }, () -> row.getWidth() - PAD - CARD_INSET, () -> row.getHeight() * .5,
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

    private void setStatus(String text, int tone) {
        statusText = text == null ? "" : text;
        statusTone = tone;
    }

    private int statusColor() {
        return statusTone == 1 ? OK_COLOR : statusTone == 2 ? BAD_COLOR
                : statusTone == 3 ? WARN_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String statusGlyph() {
        return statusTone == 1 ? PartyIcons.CHECK : statusTone == 2 ? PartyIcons.WARNING
                : statusTone == 3 ? PartyIcons.HOURGLASS
                : PartyIcons.INFO;
    }
}
