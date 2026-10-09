package com.muoniumplayer.core.screens.ncm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.viper.ViperFieldKind;
import com.muoniumplayer.core.viper.ViperPresetEditor;
import com.muoniumplayer.core.viper.ViperRuntime;
import com.muoniumplayer.party.ui.PartyIcons;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 蝰蛇音效（ViPER）面板：总开关、预设列表（选用 / 导入 / 删除）与一条状态行。
 *
 * <p>所有读写都走 {@link ViperRuntime}，面板不直接碰配置文件与音频链 —— 改开关 / 换预设 /
 * 导入 / 删除都会立即落盘并重配处理器（下一个音频缓冲生效），所以这里没有「保存」按钮。</p>
 *
 * <p><b>重建时机</b>：动作在点击回调里只置一个修订号，真正的重建放在 {@link #onRender} ——
 * {@code renderWidget} 先调 {@code onRender}、之后才遍历子组件，此时清空子组件列表不会撞上
 * 遍历（与 {@link MusicSourceOverlay} 的自定义源页、{@code FontPickerOverlay} 同一处理）。</p>
 */
public final class ViperOverlay extends NcmSheet {

    private static final double SHEET_W = 484.0;
    /** 状态行与内容区各自的顶端：页眉占 64，状态行落在 70，内容从 92 起。 */
    private static final double STATUS_TOP = 70.0;
    private static final double BODY_TOP = 92.0;
    private static final double CARD_H = 56.0;
    /** 滚动行 = 卡高 + 行距。行距做进行高里，于是最后一行自带一段收尾留白。 */
    private static final double ROW_H = CARD_H + 7.0;
    private static final double LABEL_ROW = 22.0;
    /** 页脚：两颗按钮 + 一行总开关 + 一行目录说明。贴着弹窗底边，不参与滚动。 */
    private static final double FOOTER_H = 118.0;
    /** 预设多时内容区的封顶高度，与来源页的 300 同一档。 */
    private static final double CONTENT_MAX = 300.0;
    /** 行右端留给「使用中」胶囊与删除键的宽度，长预设名必须在这之前收住。 */
    private static final double RESERVE = 112.0;
    /** 行上文字块的左边缘 = 盘的左留白 + 盘宽 + 盘到文字的间距。 */
    private static final double TEXT_LEFT = 12.0 + 36.0 + 11.0;

    /** 原生文件框或导入解析在跑期间置位：按钮改文案并防连点。 */
    private boolean importing;
    /** 原生文件框打不开（拿不到 tinyfd）时置位，用来把「用户取消」与「打不开」两种结果分开。 */
    private volatile boolean pickerUnavailable;
    private volatile String statusText = "";
    /** 状态行的语气：0 说明、1 成功、2 失败、3 待办（启用但还没选预设）。 */
    private int statusTone;

    /** 已构建列表对应的修订号；导入完成 / 删除 / 重载后自增，{@link #onRender} 看到落后就重建。 */
    private int presetRevision = -1;
    private int pendingRevision;

    @Override
    public void onInit() {
        // 本 session 还没装载过曲目时，运行时的状态还是空的；先对齐一次磁盘（只读 + 配置处理器，
        // 不碰引擎），否则「配置文件里明明开着」会被显示成「已关闭」。
        ViperRuntime.syncFromDisk();
        statusText = ViperRuntime.describe();
        statusTone = describeTone();
        showEffects();
    }

    /** 单页面板：ESC 与点遮罩都是直接关闭。 */
    public void handleEscape() {
        dispose();
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 重建放在这里而不是动作回调里：renderWidget 先调 onRender、之后才遍历子组件，
        // 此时清空子组件列表不会撞上遍历。退场中不重建，免得在半路的树上再叠一张弹窗。
        if (!closing && presetRevision != pendingRevision) {
            showEffects();
        }
        super.onRender(mouseX, mouseY);
    }

    private void requestRebuild() {
        pendingRevision++;
    }

    // ════════════════════════════ 主页面 ════════════════════════════

    private void showEffects() {
        presetRevision = pendingRevision;
        final List<String> presets = ViperRuntime.listPresetNames();
        beginPage();

        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP
                + Math.min(contentHeight(presets.size()), CONTENT_MAX) + FOOTER_H);
        header(dialog, PartyIcons.HEADPHONES, "蝰蛇音效",
                () -> "ViPER 音频效果链 · 兼容官方配置文件格式，作用于所有音源的播放输出",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> STATUS_TOP);

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停生长是绕着自己中心做的，贴着上边会被硬裁切掉圆角。
        strip(feed, 6.0);

        sectionLabel(strip(feed, LABEL_ROW),
                presets.isEmpty() ? "预设" : "预设（" + presets.size() + "）",
                () -> 5.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        if (presets.isEmpty()) {
            tip(strip(feed, 46.0), () -> PartyIcons.FOLDER_OPEN,
                    () -> "还没有预设：点下面的「从文件导入」，或把预设文件放进 presets 目录后点「重新加载」",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> 10.0);
        }
        for (int i = 0; i < presets.size(); i++) {
            presetRow(strip(feed, ROW_H), i, presets.get(i));
        }
        strip(feed, 10.0);

        showFooter(dialog);
    }

    private double contentHeight(final int count) {
        return 6.0 + LABEL_ROW + (count == 0 ? 46.0 : ROW_H * count) + 10.0;
    }

    /** 列表里的一行预设。点一下即选用；已选用的再点一下取消（与字体、脚本列表同一套手感）。 */
    private void presetRow(final Panel row, final int slot, final String name) {
        final BooleanSupplier picked = () -> name.equals(ViperRuntime.activePresetName());
        final Card[] self = new Card[1];
        // card(...) 的入场闸门按 slot 递推，预设数量不可控，序号必须夹住（与脚本列表同因）。
        final Card card = card(row, Math.min(slot, 9), () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ACCENT), .30)
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (picked.getAsBoolean()) {
                ViperRuntime.selectPreset(null);
                statusText = "已取消选择预设 ·「" + name + "」不再作用于播放";
                statusTone = 0;
            } else {
                ViperRuntime.selectPreset(name);
                statusText = "已选用「" + name + "」· 立即作用于音频链";
                statusTone = 1;
            }
            requestRebuild();
            return true;
        });
        stroke(card, 12.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> picked.getAsBoolean() ? .58 : 0.0);

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setBeforeRenderCallback(() -> {
            disc.setRadius(11);
            disc.setBounds(36.0, 36.0);
            disc.setPosition(12.0, card.getHeight() * .5 - 18.0);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ACCENT), picked.getAsBoolean() ? .44 : .26));
        });
        icon(disc, PartyIcons.HEADPHONES, ICON_M, () -> 18.0, () -> 18.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final LabelWidget title = label(card, name, FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - RESERVE));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(TEXT_LEFT, inkTop(title, card.getHeight() * .5 - 9));
        });

        final LabelWidget note = label(card, () -> picked.getAsBoolean()
                ? "生效中 · 再点一下取消选择" : "点击选用为当前预设 · 立即生效", FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - RESERVE));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(TEXT_LEFT, inkTop(note, card.getHeight() * .5 + 9));
        });

        // 胶囊整组控透明度：让它自己按状态改颜色的话，没在用的行上会留一枚透明的空胶囊。
        final Panel badge = veil(card, () -> picked.getAsBoolean() ? 1.0 : 0.0);
        pill(badge, () -> PartyIcons.CHECK, () -> "使用中",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> card.getWidth() - 46.0, () -> card.getHeight() * .5);

        deleteButton(card, name);
    }

    /**
     * 删除键。走一次 {@code openConfirmation} 二次确认：预设往往是用户自己导入攒下的文件，
     * 删掉就得从原文件重新导入，比一次性脚本那类（删错再导一次就好）贵一档。
     */
    private void deleteButton(final Card card, final String name) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(9);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            NCMScreen.getInstance().openConfirmation("删除预设",
                    "将删除预设「" + name + "」的文件；之后要从原文件重新导入才能恢复。",
                    "删除", () -> {
                        ViperRuntime.removePreset(name);
                        statusText = "已删除预设「" + name + "」";
                        statusTone = 0;
                        requestRebuild();
                    });
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            button.setBounds(26, 26);
            button.setPosition(card.getWidth() - 38.0, card.getHeight() * .5 - 13);
            button.setColor(button.isHovering() ? BAD_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            button.setTextColor(0x00FFFFFF);
        });
        icon(button, PartyIcons.CLOSE, ICON_S, () -> 13.0, () -> 13.0,
                () -> button.isHovering() ? 0xFFFFFF
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }

    // ════════════════════════════ 页脚 ════════════════════════════

    private void showFooter(final Panel dialog) {
        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);

        footerButton(dialog, 0, 3, () -> PartyIcons.FOLDER_OPEN,
                () -> importing ? "正在导入…" : "从文件导入", this::browseForPreset);
        footerButton(dialog, 1, 3, () -> PartyIcons.TEXT_FORMAT, () -> "调参", this::showTuningGroups);
        footerButton(dialog, 2, 3, () -> PartyIcons.SYNC, () -> "重新加载", () -> {
            ViperRuntime.reload();
            statusText = ViperRuntime.describe();
            statusTone = describeTone();
            requestRebuild();
        });

        // 总开关钉在页脚：它是设置而不是列表项，滚动内容不该把它带走。
        icon(dialog, () -> PartyIcons.HEADPHONES, ICON_S,
                () -> PAD + CARD_INSET + ICON_S * .5, () -> dialog.getHeight() - 64.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final LabelWidget caption = label(dialog, "启用蝰蛇音效", FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            caption.setMaxWidth(Math.max(1, dialog.getWidth() - PAD * 2 - ICON_S - 60));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(PAD + CARD_INSET + ICON_S + 7.0,
                    inkTop(caption, dialog.getHeight() - 64.0));
        });

        toggle(dialog, ViperRuntime::isEnabled, () -> {
            ViperRuntime.setEnabled(!ViperRuntime.isEnabled());
            statusText = ViperRuntime.describe();
            statusTone = describeTone();
        }, () -> dialog.getWidth() - PAD - CARD_INSET, () -> dialog.getHeight() - 64.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        tip(dialog, () -> PartyIcons.INFO,
                () -> "目录：config/muonium/viper/（presets / kernels / ddc）；改文件后点「重新加载」即刻生效",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 32.0);
    }

    private void footerButton(final Panel dialog, final int index, final int count,
                              final Supplier<String> glyph,
                              final Supplier<String> text, final Runnable onClick) {
        final RoundedButtonWidget button = action(dialog, glyph, text, FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), onClick);
        button.setBeforeRenderCallback(() -> {
            double span = Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2);
            double width = Math.max(1, (span - 8.0 * (count - 1)) / count);
            button.setBounds(width, 30);
            button.setPosition(PAD + CARD_INSET + (width + 8.0) * index, dialog.getHeight() - 112.0);
            button.setColor(button.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
    }

    // ════════════════════════════ 调参 ════════════════════════════

    /** 调参会话：进入调参页时以当前选中预设（无则全默认底稿）创建。 */
    private ViperPresetEditor editor;

    /** 调参入口：列出官方 24 组效果，右侧圆点是各组的启用状态。 */
    private void showTuningGroups() {
        editor = ViperPresetEditor.overActive();
        beginPage();

        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP + 296.0 + FOOTER_H);
        subHeader(dialog, back(this::showEffects), () -> "调参 · 效果器",
                () -> "编辑目标：「" + editor.targetName() + "」· 改动立即保存并生效",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        tip(dialog, () -> PartyIcons.INFO,
                () -> "官方全部 24 组效果的可调参数都在这里；名字右侧的圆点表示该组已启用",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> STATUS_TOP);

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        strip(feed, 6.0);
        for (int i = 0; i < editor.groupCount(); i++) {
            tuningGroupRow(strip(feed, 40.0), i);
        }
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 1, () -> PartyIcons.SWEEP,
                () -> "重载底稿（撤销全部修改）", this::showTuningGroups);
        tip(dialog, () -> PartyIcons.INFO,
                () -> "点进任意分组即可逐项调参；「自定义」底稿第一次改动会自动被选中",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 32.0);
    }

    /** 分组行：显示名 + 启用圆点 + 进场箭头。 */
    private void tuningGroupRow(final Panel row, final int gi) {
        final BooleanSupplier enabled = () -> {
            for (int f = 0; f < editor.fieldCount(gi); f++) {
                if ("enable".equals(editor.fieldKey(gi, f))) {
                    return editor.getBoolean(gi, f, false);
                }
            }
            return false;
        };
        final Card[] self = new Card[1];
        final Card card = card(row, Math.min(gi, 9), () -> 0.0, () -> 40.0,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showGroupFields(gi);
            return true;
        });

        final LabelWidget title = label(card, editor.groupDisplayName(gi), FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(12.0, inkTop(title, card.getHeight() * .5));
        });

        final RoundedRectWidget dot = new RoundedRectWidget();
        card.addChild(dot);
        dot.setClickable(false).setBeforeRenderCallback(() -> {
            dot.setRadius(3);
            dot.setBounds(6, 6);
            dot.setPosition(card.getWidth() - 46.0, card.getHeight() * .5 - 3);
            dot.setColor(enabled.getAsBoolean() ? OK_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.BORDER));
        });

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_S, () -> card.getWidth() - 22.0,
                () -> card.getHeight() * .5,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }

    /** 分组详情页：按字段类型分发行控件。 */
    private void showGroupFields(final int gi) {
        if (editor == null) {
            showTuningGroups();
            return;
        }
        beginPage();

        double estimated = 8.0;
        for (int f = 0; f < editor.fieldCount(gi); f++) {
            ViperFieldKind kind = editor.fieldKind(gi, f);
            estimated += rowHeightFor(gi, f, kind) + 6.0;
        }
        final double contentHeight = estimated;

        final Panel dialog = sheet(SHEET_W, () -> BODY_TOP + Math.min(contentHeight, 296.0) + FOOTER_H);
        subHeader(dialog, back(this::showTuningGroups), () -> editor.groupDisplayName(gi),
                () -> "编辑目标：「" + editor.targetName() + "」· 即改即存即生效",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final Panel body = body(dialog, () -> BODY_TOP, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        strip(feed, 6.0);
        for (int f = 0; f < editor.fieldCount(gi); f++) {
            ViperFieldKind kind = editor.fieldKind(gi, f);
            if (kind == ViperFieldKind.STRING && isPickerField(gi, f)) {
                buildPickerRows(feed, gi, f);
                continue;
            }
            double height = rowHeightFor(gi, f, kind);
            buildFieldRow(strip(feed, height + 6.0), gi, f, kind, height);
        }
        strip(feed, 10.0);

        divider(dialog, () -> dialog.getHeight() - FOOTER_H + 2);
        footerButton(dialog, 0, 1, () -> PartyIcons.SWEEP,
                () -> "恢复本组默认", () -> editor.resetGroup(gi));
        tip(dialog, () -> PartyIcons.INFO,
                () -> "本组的所有改动都会立即写入「" + editor.targetName() + "」并即时生效",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 32.0);
    }

    private double rowHeightFor(int gi, int fi, ViperFieldKind kind) {
        if (kind == ViperFieldKind.BOOL) return 40.0;
        if (kind == ViperFieldKind.INT || kind == ViperFieldKind.FLOAT) return 56.0;
        return 36.0;
    }

    private String groupFieldKey(int gi, int fi) {
        return editor.groupKey(gi) + "." + editor.fieldKey(gi, fi);
    }

    private boolean isPickerField(int gi, int fi) {
        return "convolver.kernelFile".equals(groupFieldKey(gi, fi))
                || "ddc.device".equals(groupFieldKey(gi, fi));
    }

    private void buildFieldRow(final Panel row, final int gi, final int fi,
                               final ViperFieldKind kind, final double height) {
        if (kind == ViperFieldKind.BOOL) {
            toggleFieldRow(row, gi, fi, height);
            return;
        }
        if (kind == ViperFieldKind.INT || kind == ViperFieldKind.FLOAT) {
            if (editor.fieldUnused(gi, fi)) {
                readOnlyFieldRow(row, gi, fi, height, "官方遗留字段，调整无效果");
                return;
            }
            if (kind == ViperFieldKind.INT && editor.fieldIntChoices(gi, fi) != null) {
                stepperFieldRow(row, gi, fi, height, editor.fieldIntChoices(gi, fi));
                return;
            }
            if (editor.fieldRanged(gi, fi)) {
                sliderFieldRow(row, gi, fi, height);
                return;
            }
            readOnlyFieldRow(row, gi, fi, height, "档位表未收录");
            return;
        }
        if (kind.isArray() || kind == ViperFieldKind.NULLABLE_LONG) {
            int count = 0;
            try {
                count = presetArrayLength(gi, fi);
            } catch (RuntimeException ignored) {
                count = 0;
            }
            readOnlyFieldRow(row, gi, fi, height,
                    count > 0 ? "数组字段（" + count + " 项）· 本版只读，随预设保留" : "本版只读，随预设保留");
            return;
        }
        readOnlyFieldRow(row, gi, fi, height, "文本字段");
    }

    /** 数组字段的元素数（读不出按 0）。 */
    private int presetArrayLength(int gi, int fi) {
        JsonObject group = editor.presetGroup(editor.groupKey(gi));
        if (group == null) return 0;
        JsonElement element = group.get(editor.fieldKey(gi, fi));
        if (element == null || !element.isJsonArray()) return 0;
        return element.getAsJsonArray().size();
    }

    /** 布尔字段行：说明 + 右侧开关。 */
    private void toggleFieldRow(final Panel row, final int gi, final int fi, final double height) {
        final LabelWidget caption = label(row, editor.fieldDisplayName(gi, fi), FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            caption.setPosition(PAD + CARD_INSET, inkTop(caption, height * .5));
        });
        toggle(row, () -> editor.getBoolean(gi, fi, false),
                () -> editor.setBoolean(gi, fi, !editor.getBoolean(gi, fi, false)),
                () -> row.getWidth() - PAD - CARD_INSET, () -> height * .5,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
    }

    /** 数值字段行（有量程）：上排「名称 + 当前值」，下排滑条。 */
    private void sliderFieldRow(final Panel row, final int gi, final int fi, final double height) {
        final LabelWidget caption = label(row, () -> editor.fieldDisplayName(gi, fi), FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(PAD + CARD_INSET, inkTop(caption, 10.0));
        });

        final LabelWidget value = label(row, () -> formatFieldValue(gi, fi), FontManager.pf12bold);
        value.setBeforeRenderCallback(() -> {
            value.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            value.setPosition(Math.max(PAD + CARD_INSET,
                    row.getWidth() - PAD - CARD_INSET - value.getWidth()), inkTop(value, 10.0));
        });

        final SliderRow slider = new SliderRow(gi, fi);
        row.addChild(slider);
        slider.setBeforeRenderCallback(() -> {
            slider.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 16);
            slider.setPosition(PAD + CARD_INSET, height - 24);
        });
    }

    /** 档位字段行（离散取值）：说明 + 「◀ 值 ▶」循环步进。 */
    private void stepperFieldRow(final Panel row, final int gi, final int fi,
                                 final double height, final int[] choices) {
        final LabelWidget caption = label(row, editor.fieldDisplayName(gi, fi), FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(PAD + CARD_INSET, inkTop(caption, height * .5));
        });

        final LabelWidget value = label(row, () -> {
            int current = editor.getInt(gi, fi, choices[0]);
            String name = editor.fieldIntChoiceName(gi, fi, current);
            for (int choice : choices) {
                if (choice == current) {
                    return name != null ? name : String.valueOf(current);
                }
            }
            return choices[0] + " →";
        }, FontManager.pf12bold);
        value.setBeforeRenderCallback(() -> {
            value.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            value.setPosition(row.getWidth() - PAD - CARD_INSET - 104.0 - value.getWidth() * .5,
                    inkTop(value, height * .5));
        });

        final RoundedButtonWidget prev = new RoundedButtonWidget("◀", FontManager.pf12bold);
        row.addChild(prev);
        prev.setRadius(7);
        prev.setGlassRim(GlassLook.Surface.PLAYER);
        prev.setShouldOverrideMouseCursor(true);
        prev.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            stepChoice(gi, fi, choices, -1);
            return true;
        });
        prev.setBeforeRenderCallback(() -> {
            prev.setBounds(30, 24);
            prev.setPosition(row.getWidth() - PAD - CARD_INSET - 80.0, Math.max(0.0, height * .5 - 12));
            prev.setColor(prev.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            prev.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        final RoundedButtonWidget next = new RoundedButtonWidget("▶", FontManager.pf12bold);
        row.addChild(next);
        next.setRadius(7);
        next.setGlassRim(GlassLook.Surface.PLAYER);
        next.setShouldOverrideMouseCursor(true);
        next.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            stepChoice(gi, fi, choices, 1);
            return true;
        });
        next.setBeforeRenderCallback(() -> {
            next.setBounds(30, 24);
            next.setPosition(row.getWidth() - PAD - CARD_INSET - 30.0, Math.max(0.0, height * .5 - 12));
            next.setColor(next.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            next.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    /** 在离散档位里循环步进（绕回），每步立即落盘生效。 */
    private void stepChoice(int gi, int fi, int[] choices, int direction) {
        int current = editor.getInt(gi, fi, choices[0]);
        int index = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == current) {
                index = i;
                break;
            }
        }
        index = (index + direction + choices.length) % choices.length;
        editor.setNumber(gi, fi, choices[index]);
    }

    /** 只读字段行：名称 + 当前值/说明。 */
    private void readOnlyFieldRow(final Panel row, final int gi, final int fi,
                                  final double height, final String note) {
        final LabelWidget caption = label(row, editor.fieldDisplayName(gi, fi), FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(PAD + CARD_INSET, inkTop(caption, 10.0));
        });

        final LabelWidget line = label(row, () -> {
            String current = editor.fieldKind(gi, fi) == ViperFieldKind.INT
                    ? String.valueOf(editor.getInt(gi, fi, 0))
                    : editor.getString(gi, fi, "");
            return (current.isEmpty() ? "（空）" : current) + " · " + note;
        }, FontManager.pf12);
        line.setBeforeRenderCallback(() -> {
            line.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            line.setMaxWidth(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2));
            line.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            line.setPosition(PAD + CARD_INSET, inkTop(line, 26.0));
        });
    }

    /** kernelFile / ddc.device 的候选清单：说明行 + 每个候选一行 + 清除行。 */
    private void buildPickerRows(final ScrollPanel feed, final int gi, final int fi) {
        boolean kernel = "kernelFile".equals(editor.fieldKey(gi, fi));
        java.util.List<String> options = kernel ? editor.kernelFiles() : editor.ddcDevices();

        final Panel head = strip(feed, 34.0);
        final LabelWidget caption = label(head,
                editor.fieldDisplayName(gi, fi) + (options.isEmpty() ? " · 目录里还没有候选文件" : ""),
                FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(PAD + CARD_INSET, inkTop(caption, 12.0));
        });

        for (final String option : options) {
            final BooleanSupplier picked = () -> option.equals(editor.getString(gi, fi, ""));
            final Panel optionRow = strip(feed, 30.0);
            final RoundedRectWidget plate = new RoundedRectWidget();
            optionRow.addChild(plate);
            plate.setRadius(8);
            plate.setGlassRim(GlassLook.Surface.PLAYER);
            plate.setShouldOverrideMouseCursor(true);
            plate.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                editor.setString(gi, fi, option);
                return true;
            });
            plate.setBeforeRenderCallback(() -> {
                plate.setBounds(Math.max(1, optionRow.getWidth() - (PAD + CARD_INSET) * 2), 28);
                plate.setPosition(PAD + CARD_INSET, 1);
                plate.setColor(plate.isHovering()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                plate.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
                plate.setAlpha(picked.getAsBoolean() ? 1.0f : .72f);
            });
            final LabelWidget name = label(plate, option, FontManager.pf12);
            name.setBeforeRenderCallback(() -> {
                name.setColor(picked.getAsBoolean() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                name.setMaxWidth(Math.max(1, plate.getWidth() - 40.0));
                name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                name.setPosition(10.0, inkTop(name, plate.getHeight() * .5));
            });
            icon(plate, PartyIcons.CHECK, ICON_S, () -> plate.getWidth() - 18.0,
                    () -> plate.getHeight() * .5, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        }

        final Panel clearRow = strip(feed, 30.0);
        final RoundedRectWidget clear = new RoundedRectWidget();
        clearRow.addChild(clear);
        clear.setRadius(8);
        clear.setGlassRim(GlassLook.Surface.PLAYER);
        clear.setShouldOverrideMouseCursor(true);
        clear.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            editor.setString(gi, fi, "");
            return true;
        });
        clear.setBeforeRenderCallback(() -> {
            clear.setBounds(Math.max(1, clearRow.getWidth() - (PAD + CARD_INSET) * 2), 28);
            clear.setPosition(PAD + CARD_INSET, 1);
            clear.setColor(clear.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            clear.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            clear.setAlpha(.72f);
        });
        final LabelWidget clearText = label(clear, "（清除，不使用）", FontManager.pf12);
        clearText.setBeforeRenderCallback(() -> {
            clearText.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            clearText.setPosition(10.0, inkTop(clearText, clear.getHeight() * .5));
        });
        strip(feed, 6.0);
    }

    private String formatFieldValue(int gi, int fi) {
        double value = editor.getNumber(gi, fi, 0.0);
        if (editor.fieldKind(gi, fi) == ViperFieldKind.INT) {
            return String.valueOf((int) Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /**
     * 可拖动的参数滑条。拖拽用 VolumeControl 的轮询式实现（每帧查鼠标左键 + 悬停判定）：
     * 按住期间静默更新工作副本，松手才落盘一次——滑过一整条也只写一次文件。
     */
    private final class SliderRow extends RoundedRectWidget {

        private final int groupIndex;
        private final int fieldIndex;
        private boolean dragging;
        private boolean wasDragging;

        SliderRow(int groupIndex, int fieldIndex) {
            this.groupIndex = groupIndex;
            this.fieldIndex = fieldIndex;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            double x = getX();
            double y = getY();
            double w = Math.max(1.0, getWidth());
            double h = Math.max(1.0, getHeight());
            float alpha = getAlpha();

            boolean hovered = testHovered(mouseX, mouseY);
            boolean down = Mouse.isButtonDown(0);
            if (!down) {
                dragging = false;
            } else if (hovered) {
                dragging = true;
            }
            if (dragging) {
                double min = editor.fieldMin(groupIndex, fieldIndex);
                double max = editor.fieldMax(groupIndex, fieldIndex);
                double pct = clamp01((mouseX - x) / w);
                editor.setNumberQuiet(groupIndex, fieldIndex, min + (max - min) * pct);
            }
            if (wasDragging && !dragging) {
                editor.persist();
            }
            wasDragging = dragging;

            double min = editor.fieldMin(groupIndex, fieldIndex);
            double max = editor.fieldMax(groupIndex, fieldIndex);
            double t = clamp01((editor.getNumber(groupIndex, fieldIndex, min) - min)
                    / Math.max(1e-9, max - min));

            roundedRect(x, y + h * .5 - 2.5, w, 5, 2.5,
                    RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.BORDER), alpha * .8f));
            double filled = Math.max(0.0, Math.min(w, w * t));
            if (filled > .5) {
                roundedRect(x, y + h * .5 - 2.5, filled, 5, 2.5,
                        RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), alpha));
            }
            double knob = 10.0;
            roundedRect(x + Math.max(0.0, Math.min(w - knob, w * t - knob * .5)),
                    y + h * .5 - knob * .5, knob, knob, knob * .5,
                    RenderSystem.reAlpha(0xFFFFFF, alpha * .95f));

            if (hovered || dragging) {
                setShouldOverrideMouseCursor(true);
            }
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
            // 值由 onRender 的轮询路径设置；这里只吞掉点击，防止穿透到底下的弹窗遮罩。
            return true;
        }
    }

    // ════════════════════════════ 导入 ════════════════════════════

    /**
     * 打开系统文件框挑一个预设文件（.json / .xml）。
     *
     * <p>与 LX 脚本导入同一套路：反射调 LWJGL 带下来的 tinyfd —— 它随 Minecraft 一起分发，
     * 但版本之间未必都在编译期可见，反射拿不到就提示改走「放进目录 + 重新加载」那条路。
     * 原生对话框是模态的，必须单开一条线程，在渲染线程上弹会把整个游戏冻在那一帧。</p>
     */
    private void browseForPreset() {
        if (importing) return;
        importing = true;
        statusText = "已打开系统文件选择框，请挑一个 ViPER 预设（.json / .xml）";
        statusTone = 0;
        final Thread picker = new Thread(() -> {
            final String picked = nativeOpenDialog();
            if (picked == null || picked.trim().isEmpty()) {
                MultiThreadingUtil.runOnMainThread(() -> {
                    importing = false;
                    if (pickerUnavailable) {
                        statusText = "这个版本打不开系统文件选择框；把预设文件放进 presets 目录后点「重新加载」";
                        statusTone = 2;
                    } else {
                        statusText = "没有选择预设文件";
                        statusTone = 0;
                    }
                });
                return;
            }
            final String name;
            try {
                name = ViperRuntime.importPreset(new File(picked.trim()));
            } catch (Throwable failure) {
                final String message = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                MultiThreadingUtil.runOnMainThread(() -> {
                    importing = false;
                    statusText = "导入失败：" + message;
                    statusTone = 2;
                });
                return;
            }
            MultiThreadingUtil.runOnMainThread(() -> {
                importing = false;
                statusText = "已导入「" + name + "」· 点击选用后即可生效";
                statusTone = 1;
                requestRebuild();
            });
        }, "MuoniumPlayer-ViperPresetPicker");
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
            Object result = open.invoke(null, "选择 ViPER 预设文件", "", null, null, Boolean.FALSE);
            return result == null ? null : result.toString();
        } catch (Throwable unavailable) {
            pickerUnavailable = true;
            return null;
        }
    }

    // ════════════════════════════ 状态行 ════════════════════════════

    /** 语气跟着「开关 + 有没有选中预设」走：开着却没预设是待办，不是错误。 */
    private static int describeTone() {
        if (!ViperRuntime.isEnabled()) return 0;
        return ViperRuntime.activePresetName() == null ? 3 : 1;
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
