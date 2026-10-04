package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 界面字体选择浮层。
 *
 * <p>三组候选：内置字体、{@code config/muonium/fonts/} 里的字体文件、系统装着的字体族。
 * 点一行立刻生效 —— 换字体是纯视觉的事，「先预览再确认」这一步反而让人看不到真实效果，
 * 而整个界面（包括这个浮层自己）当帧就换成新字体，本身就是最准的预览。</p>
 *
 * <p><b>搜索不重建页面。</b>系统字体动辄上百款，行是一次性建好的；筛选靠在滚动区的
 * {@code beforeRenderCallback} 里逐行 {@code setHidden}，而 {@link ScrollPanel} 的排版与
 * 滚动范围都会跳过隐藏的子组件，于是被筛掉的行自然收起来、滚动条也跟着变短。
 * 隐藏必须从<b>父组件</b>那里驱动：{@code renderWidget} 对隐藏的组件直接 return，
 * 行自己的回调一旦隐藏就再也不会跑，那样搜索词删掉之后行就永远回不来了。</p>
 */
public final class FontPickerOverlay extends NcmSheet {

    private static final double HEADER_H = 64.0;
    /** 搜索条：贴在页眉下面，不参与滚动 —— 上百行的列表里，搜索框必须一直在手边。 */
    private static final double SEARCH_H = 46.0;
    private static final double CARD_H = 56.0;
    private static final double ROW_H = CARD_H + 7.0;
    private static final double LABEL_ROW = 24.0;
    private static final double LABEL_ROW_2 = 28.0;
    /** 页脚：一条分隔线 + 示例文本 + 三颗按钮 + 状态行，全部贴底不滚。 */
    private static final double FOOTER_H = 106.0;
    /** 字体标记盘的边长。 */
    private static final double DISC = 36.0;
    private static final double TEXT_LEFT = 12.0 + DISC + 11.0;
    /** 文字块右侧给状态胶囊预留的宽度。 */
    private static final double BADGE_RESERVE = 74.0;

    private static final int GROUP_BUILTIN = 0;
    private static final int GROUP_EXTERNAL = 1;
    private static final int GROUP_SYSTEM = 2;
    private static final int GROUPS = 3;

    private static final int EXTERNAL_COLOR = 0xFF6EDAA0;
    private static final int SYSTEM_COLOR = 0xFF73B9FF;

    private TextFieldWidget searchField;
    private final List<Entry> entries = new ArrayList<Entry>();
    /** 建这一页时用的目录快照。目录是后台扫出来的，换了就要重建一次列表。 */
    private List<UiFont> builtFrom;
    private UiFontLibrary.State builtState;
    /** 搜索词自己存一份：重建页面会把输入框连带清掉，重建完要填回去。 */
    private String query = "";

    /** 列表里的一行。字体行、分组小标题和提示行都算一行，筛选时统一在这里控制显示。 */
    private static final class Entry {
        private final Panel strip;
        private final UiFont font;
        private final int group;
        /** 只在没有搜索词时出现（提示行：搜索时它们只会挡着结果）。 */
        private final boolean emptyQueryOnly;

        private Entry(Panel strip, UiFont font, int group, boolean emptyQueryOnly) {
            this.strip = strip;
            this.font = font;
            this.group = group;
            this.emptyQueryOnly = emptyQueryOnly;
        }
    }

    @Override
    public void onInit() {
        UiFontLibrary.ensureScanned();
        rebuild();
    }

    public void handleEscape() {
        dispose();
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 目录扫完要把列表补上。放在这里是安全的：renderWidget 先调 onRender，
        // 之后才遍历子组件，此时清空子组件列表不会撞上遍历。
        if (builtFrom != UiFontLibrary.getCatalog() || builtState != UiFontLibrary.getState()) {
            rebuild();
        }
        super.onRender(mouseX, mouseY);
    }
    // ════════════════════════════ 页面 ════════════════════════════

    private void rebuild() {
        if (searchField != null) {
            query = searchField.getText() == null ? "" : searchField.getText();
        }
        builtFrom = UiFontLibrary.getCatalog();
        builtState = UiFontLibrary.getState();
        entries.clear();
        searchField = null;
        beginPage();

        final List<UiFont> external = filterByOrigin(builtFrom, UiFont.Origin.EXTERNAL);
        final List<UiFont> system = filterByOrigin(builtFrom, UiFont.Origin.SYSTEM);

        final Panel dialog = sheet(504, () -> HEADER_H + SEARCH_H + 372.0 + FOOTER_H);
        header(dialog, PartyIcons.TEXT_FORMAT, "界面字体",
                () -> "点一行立刻生效；这款字体缺的字仍由内置字体补上",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        searchBar(dialog);

        final Panel body = body(dialog, () -> HEADER_H + SEARCH_H, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);
        // 筛选从滚动区这一层驱动，理由见类注释。
        feed.setBeforeRenderCallback(() -> {
            feed.setBounds(Math.max(1, body.getWidth()), Math.max(1, body.getHeight()));
            feed.setPosition(0, 0);
            applyFilter();
        });

        int slot = 0;
        label(feed, "随 mod 打包", LABEL_ROW, GROUP_BUILTIN,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        fontRow(feed, UiFont.BUILTIN, GROUP_BUILTIN, slot++);

        label(feed, external.isEmpty() ? "我的字体文件" : "我的字体文件（" + external.size() + "）",
                LABEL_ROW_2, GROUP_EXTERNAL, () -> EXTERNAL_COLOR);
        if (external.isEmpty()) {
            note(feed, GROUP_EXTERNAL, () -> PartyIcons.FOLDER_OPEN,
                    () -> "还没有自备字体。点下面「选择字体文件」，或把 ttf/otf 丢进 "
                            + UiFontLibrary.getExternalDir().getPath());
        }
        for (UiFont font : external) {
            fontRow(feed, font, GROUP_EXTERNAL, slot++);
        }

        label(feed, systemLabel(system), LABEL_ROW_2, GROUP_SYSTEM, () -> SYSTEM_COLOR);
        if (system.isEmpty()) {
            note(feed, GROUP_SYSTEM,
                    () -> builtState == UiFontLibrary.State.SCANNING
                            ? PartyIcons.HOURGLASS : PartyIcons.WARNING,
                    () -> builtState == UiFontLibrary.State.SCANNING
                            ? "正在读取系统里装了哪些字体…"
                            : "没读到系统字体。可以用上面的字体文件方式自备字体。");
        }
        for (UiFont font : system) {
            fontRow(feed, font, GROUP_SYSTEM, slot++);
        }

        footer(dialog);

        if (!query.isEmpty() && searchField != null) {
            searchField.setText(query);
        }
    }

    private String systemLabel(List<UiFont> system) {
        if (builtState == UiFontLibrary.State.SCANNING) {
            return "系统字体（正在读取…）";
        }
        return system.isEmpty() ? "系统字体" : "系统字体（" + system.size() + "）";
    }

    private static List<UiFont> filterByOrigin(List<UiFont> all, UiFont.Origin origin) {
        List<UiFont> out = new ArrayList<UiFont>();
        if (all != null) {
            for (UiFont font : all) {
                if (font.getOrigin() == origin) {
                    out.add(font);
                }
            }
        }
        return out;
    }

    // ════════════════════════════ 搜索条 ════════════════════════════

    private void searchBar(final Panel dialog) {
        final double height = 32.0;
        final double refreshBox = 32.0;

        final RoundedRectWidget field = new RoundedRectWidget();
        dialog.addChild(field);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2 - refreshBox - 8),
                    height);
            field.setPosition(PAD + CARD_INSET, HEADER_H + (SEARCH_H - height) * .5);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        icon(field, PartyIcons.SEARCH, ICON_S, () -> 10.0 + ICON_S * .5, () -> height * .5,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(input);
        // 打开就聚焦：上百款字体，进来第一件事十有八九是打字筛选。
        // ESC 由 NCMScreen 抢在控件树之前处理，所以聚焦不会把这一层关不掉。
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder("搜索字体名，比如 雅黑 / Consolas");
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            double left = 10.0 + ICON_S + 6.0;
            input.setBounds(Math.max(1, field.getWidth() - left - 10.0), Math.max(1, height - 10.0));
            input.setPosition(field.getRelativeX() + left, field.getRelativeY() + 5);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> input.isFocused() ? .62 : 0.0);
        searchField = input;

        // 重新扫描：用户刚往字体目录里丢了文件、或者刚在系统里装了字体，不该为此重开界面。
        final RoundedButtonWidget refresh = new RoundedButtonWidget("", FontManager.pf12bold);
        dialog.addChild(refresh);
        refresh.setRadius(9);
        refresh.setGlassRim(GlassLook.Surface.PLAYER);
        refresh.setShouldOverrideMouseCursor(true);
        refresh.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            UiFontLibrary.refresh();
            return true;
        });
        refresh.setBeforeRenderCallback(() -> {
            refresh.setBounds(refreshBox, height);
            refresh.setPosition(field.getRelativeX() + field.getWidth() + 8, field.getRelativeY());
            refresh.setColor(refresh.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            refresh.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            refresh.setTextColor(0x00FFFFFF);
        });
        icon(refresh, PartyIcons.SYNC, ICON_M, () -> refreshBox * .5, () -> height * .5,
                () -> UiFontLibrary.getState() == UiFontLibrary.State.SCANNING
                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }
    // ════════════════════════════ 列表行 ════════════════════════════

    /**
     * 列表里的一行，高度当场就填好。
     *
     * <p>{@link ScrollPanel} 是在自己的 {@code onRender} 里读 {@code getHeight()} 排版的，
     * 而行自己的回调要等排完版才跑。行高不预置的话，一行从隐藏变回显示的那一帧高度还是 0，
     * 下面所有行会往上跳一帧 —— 清空搜索词时看得很清楚。</p>
     */
    private Panel row(final ScrollPanel feed, final double height) {
        final Panel strip = strip(feed, height);
        strip.setBounds(1.0, height);
        return strip;
    }

    private void label(final ScrollPanel feed, final String text, final double height,
                       final int group, final IntSupplier accent) {
        final Panel strip = row(feed, height);
        entries.add(new Entry(strip, null, group, false));
        // 小标题贴着这一行的底：多出来的高度留在上面，两组之间才有呼吸。
        sectionLabel(strip, text, () -> height - 19.0, accent);
    }

    private void note(final ScrollPanel feed, final int group,
                      final Supplier<String> glyph, final Supplier<String> text) {
        final Panel strip = row(feed, 38.0);
        entries.add(new Entry(strip, null, group, true));
        tip(strip, glyph, text, () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> 8.0);
    }

    private void fontRow(final ScrollPanel feed, final UiFont font, final int group, final int slot) {
        final Panel strip = row(feed, ROW_H);
        entries.add(new Entry(strip, font, group, false));

        final int tint = originColor(font);
        final BooleanSupplier picked = () -> UiFontLibrary.isActive(font);
        final BooleanSupplier busy = () -> UiFontLibrary.isPending(font);

        final Card[] self = new Card[1];
        // 入场是按 slot 依次开闸的，而这个列表可能有上百行：闸门到第 15 行就再也不会打开，
        // 所以序号夹住 —— 前十行还是一条条落下来，后面的跟着最后一档一起出现。
        final int gate = Math.min(slot, 9);
        final Card card = card(strip, gate, () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), tint, .30)
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            UiFontLibrary.select(font);
            return true;
        });
        stroke(card, 12.0, () -> tint, () -> picked.getAsBoolean() ? .58 : 0.0);

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setClickable(false);
        disc.setBeforeRenderCallback(() -> {
            disc.setRadius(11);
            disc.setBounds(DISC, DISC);
            disc.setPosition(12.0, (CARD_H - DISC) * .5);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), tint,
                    picked.getAsBoolean() ? .44 : .26));
        });
        icon(disc, originGlyph(font), ICON_M, () -> DISC * .5, () -> DISC * .5, () -> tint);

        final LabelWidget title = label(card, font::getDisplayName, FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - BADGE_RESERVE));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(TEXT_LEFT, inkTop(title, CARD_H * .5 - 9.0));
        });

        final LabelWidget caption = label(card, font::describe, FontManager.pf12);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - BADGE_RESERVE));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(TEXT_LEFT, inkTop(caption, CARD_H * .5 + 9.0));
        });

        // 胶囊整组控透明度：不能让它自己按状态改颜色，那样"没在用"的行上会留一枚透明的空胶囊。
        final Panel badge = veil(card,
                () -> picked.getAsBoolean() || busy.getAsBoolean() ? 1.0 : 0.0);
        pill(badge, () -> busy.getAsBoolean() ? PartyIcons.HOURGLASS : PartyIcons.CHECK,
                () -> busy.getAsBoolean() ? "载入中" : "使用中",
                () -> busy.getAsBoolean() ? WARN_COLOR : tint,
                () -> card.getWidth() - 12.0, () -> CARD_H * .5);
    }

    private static int originColor(UiFont font) {
        switch (font.getOrigin()) {
            case EXTERNAL:
                return EXTERNAL_COLOR;
            case SYSTEM:
                return SYSTEM_COLOR;
            default:
                return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        }
    }

    private static String originGlyph(UiFont font) {
        return font.getOrigin() == UiFont.Origin.EXTERNAL
                ? PartyIcons.FOLDER_OPEN : PartyIcons.TEXT_FORMAT;
    }

    // ════════════════════════════ 筛选 ════════════════════════════

    private void applyFilter() {
        final String needle = normalize(searchField == null ? "" : searchField.getText());
        final int[] hits = new int[GROUPS];
        for (Entry entry : entries) {
            if (entry.font == null) {
                continue;
            }
            boolean hit = matches(entry.font, needle);
            entry.strip.setHidden(!hit);
            if (hit) {
                hits[entry.group]++;
            }
        }
        for (Entry entry : entries) {
            if (entry.font != null) {
                continue;
            }
            entry.strip.setHidden(entry.emptyQueryOnly
                    ? !needle.isEmpty()
                    : !needle.isEmpty() && hits[entry.group] == 0);
        }
    }

    private static boolean matches(UiFont font, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        if (font.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        // 族名也要搜：中文用户看到的是「微软雅黑」，但很多人习惯敲 yahei。
        String family = font.getFamily();
        return family != null && family.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
    // ════════════════════════════ 页脚 ════════════════════════════

    private void footer(final Panel dialog) {
        divider(dialog, () -> dialog.getHeight() - 100.0);

        // 示例行用的是界面自己的字体，所以它天然就是当前选择的实时预览。
        icon(dialog, PartyIcons.TEXT_FORMAT, ICON_S, () -> PAD + CARD_INSET + ICON_S * .5,
                () -> dialog.getHeight() - 84.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        final LabelWidget sample = label(dialog, UiFontLibrary::sampleText, FontManager.pf20bold);
        sample.setBeforeRenderCallback(() -> {
            sample.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            sample.setMaxWidth(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2 - ICON_S - 7.0));
            sample.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sample.setPosition(PAD + CARD_INSET + ICON_S + 7.0,
                    inkTop(sample, dialog.getHeight() - 84.0));
        });

        footerButton(dialog, 0, () -> PartyIcons.ADD, () -> "选择字体文件",
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                UiFontLibrary::browseForFile);
        footerButton(dialog, 1, () -> PartyIcons.FOLDER_OPEN, () -> "字体目录",
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                UiFontLibrary::openExternalDir);
        footerButton(dialog, 2, () -> PartyIcons.SYNC, () -> "恢复内置",
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                UiFontLibrary::restoreBuiltin);

        tip(dialog, this::statusGlyph, this::statusText, this::statusColor,
                () -> dialog.getHeight() - 28.0);
    }

    private void footerButton(final Panel dialog, final int index, final Supplier<String> glyph,
                              final Supplier<String> text, final IntSupplier ink,
                              final Runnable onClick) {
        final RoundedButtonWidget button = action(dialog, glyph, text, FontManager.pf12bold, ink, onClick);
        button.setBeforeRenderCallback(() -> {
            double span = Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2);
            double width = Math.max(1, (span - 16.0) / 3.0);
            button.setBounds(width, 30);
            button.setPosition(PAD + CARD_INSET + (width + 8.0) * index, dialog.getHeight() - 66.0);
            button.setColor(button.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
    }

    private String statusGlyph() {
        if (UiFontLibrary.isBusy()) {
            return PartyIcons.HOURGLASS;
        }
        if (UiFontLibrary.isStatusBad()) {
            return PartyIcons.WARNING;
        }
        return has(UiFontLibrary.getStatus()) ? PartyIcons.CHECK : PartyIcons.INFO;
    }

    private int statusColor() {
        if (UiFontLibrary.isBusy()) {
            return WARN_COLOR;
        }
        if (UiFontLibrary.isStatusBad()) {
            return BAD_COLOR;
        }
        return has(UiFontLibrary.getStatus())
                ? OK_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String statusText() {
        String status = UiFontLibrary.getStatus();
        if (has(status)) {
            return status;
        }
        return "当前使用：" + UiFontLibrary.getActive().getDisplayName();
    }
}