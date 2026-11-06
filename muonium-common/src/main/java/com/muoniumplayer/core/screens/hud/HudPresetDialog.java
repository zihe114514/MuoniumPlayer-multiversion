package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.mc.GuiTextField;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.screens.ncm.NcmSheet;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.BUTTON_BG;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.BUTTON_HOVER;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.CARD_BG;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.CARD_EDGE;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.CARD_RADIUS;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.FOCUS_MS;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.KNOB_MS;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.PANEL_EDGE;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.POP_MS;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.ROW_HOVER;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.SWITCH_H;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.SWITCH_W;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.TEXT_PRIMARY;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.TEXT_SECONDARY;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.TEXT_TERTIARY;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.clamp01;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.fade;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.isInside;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.mix;
import static com.muoniumplayer.core.screens.hud.GuiHudEditorPro.wrapToTwoLines;

/**
 * H 键设置页里的「模板预设」弹窗：标题栏那根魔法棒打开它。
 *
 * <p>内容与行为和播放器里那张（{@code PresetOverlay}）是同一份 {@link PresetPanelSession}：同样的三页
 * （内置风格 + 我的预设 / 按部分勾选套用 / 存为预设）、同样的按钮、同样的提示文案。这里只是换成 H 的
 * 画法 —— 跟播放器里那张一样，弹窗落在<b>它所在面板</b>的正中，底下压一层只盖住面板的暗幕：</p>
 * <ul>
 *   <li>配色、卡片、开关、胶囊按钮全部取自 {@link GuiHudEditorPro}（同包直接用它的绘制原语与色板），
 *       强调色跟着当前分类走，和标题栏按钮那道高光同一个颜色；</li>
 *   <li>守 H 的三条规矩：绘制与命中共用同一份矩形（{@link #items} 每帧排一次，点击读同一份）；
 *       动画只改颜色 / 透明度 / 偏移，不动命中矩形；分层淡入只经宿主的 {@code layerAlpha} 收口。</li>
 * </ul>
 *
 * <p>「我的预设」列表要读盘解析，手画界面又是每帧重画，所以按会话的修订号缓存，导入 / 删除 / 保存后才重读。</p>
 */
final class HudPresetDialog implements PresetPanelSession.Navigator {

    // ==================== 尺寸 ====================

    private static final int MAX_W = 452;
    /** 弹窗离设置面板四边的最小留白。 */
    private static final int INSET = 14;
    private static final int RADIUS = 14;
    private static final int PAD = 16;
    private static final int HEADER_H = 52;
    private static final int STATUS_H = 26;
    private static final int FIELD_H = 26;
    private static final int FIELD_GAP = 8;
    /** 页脚：一排按钮 + 一行说明。 */
    private static final int FOOTER_H = 62;
    private static final int BUTTON_H = 24;
    private static final int BUTTON_GAP = 8;
    private static final int LIST_MAX_H = 300;
    private static final int LIST_MIN_H = 64;

    private static final int SECTION_H = 24;
    private static final int CARD_H = 46;
    private static final int CARD_GAP = 6;
    private static final int FACET_H = 40;
    private static final int NOTE_H = 17;
    private static final int EMPTY_H = 44;
    private static final int GROUP_GAP = 8;
    private static final int ROW_BUTTON = 22;
    private static final int DISC = 30;

    // ==================== 配色 ====================

    /** 与「恢复默认」确认框同一块底：这一页凭空出现的板子都是这个颜色。 */
    private static final int DIALOG_BG = 0xF41A202A;
    private static final int CONFIRM_BG = 0xFA222A37;
    private static final int SCRIM = 0x6D000000;
    /** 状态行三种语气，与播放器那张弹窗同值。 */
    private static final int OK_INK = 0xFF6EDAA0;
    private static final int WARN_INK = 0xFFE0B268;
    private static final int BAD_INK = 0xFFF1767D;
    /** 删除键：与「恢复默认」的确认键同一对红。 */
    private static final int DANGER = 0xFFD94F4D;
    private static final int DANGER_HOVER = 0xFFF2756B;

    // ==================== 动效 ====================

    /** 换页时内容那一趟的总长；各块在它上面错峰开窗。 */
    private static final double PAGE_MS = 420.0;
    /** 换页时弹窗高度补过去的时长。 */
    private static final double HEIGHT_MS = 240.0;
    private static final double ROW_FIRST = .14;
    private static final double ROW_STEP = .04;
    private static final double ROW_TRAVEL = .50;
    private static final int ROW_STAGGER_LIMIT = 12;
    private static final int CONTENT_SLIDE = 14;
    private static final int CONTENT_DROP = 6;

    /** 鼠标「不在任何地方」：给被盖住的那一层传它，悬停就不会在暗幕底下亮起来。 */
    private static final int NOWHERE = -10000;

    // ==================== 一项内容的几何 ====================

    private enum Kind { SECTION, CARD, EMPTY, GROUP, FACET, NOTE }

    /** 列表里的一项。分组小标题、预设卡、空态、开关组底板、开关行、提示行共用这个结构。 */
    private static final class Item {
        final Kind kind;
        final int x;
        final int y;
        final int w;
        final int h;
        /** 入场错峰的序号：开关行和它所在的底板同一档，免得「字到了板还在路上」。 */
        final int slot;
        String text;
        String partyGlyph;
        String settingsGlyph;
        int tint;
        StylePreset preset;
        Facet facet;
        boolean first;

        Item(Kind kind, int x, int y, int w, int h, int slot) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.slot = slot;
        }
    }

    /** 页脚的一颗键。每帧按当前页重建（文字会变：「选择中…」「套用 N 项」）。 */
    private static final class FooterButton {
        final String glyph;
        final String label;
        final Runnable action;
        final boolean primary;
        /** 悬停时页脚说明行换成这句；{@code null} 即沿用说明。 */
        final String hint;

        FooterButton(String glyph, String label, Runnable action, boolean primary, String hint) {
            this.glyph = glyph;
            this.label = label;
            this.action = action;
            this.primary = primary;
            this.hint = hint;
        }
    }

    private static final Map<CFontRenderer, GlyphInkMetrics> PARTY_SQUARE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    // ==================== 状态 ====================

    private final GuiHudEditorPro host;
    /** 第一次打开时才建：没点魔法棒的人不必为它初始化预设服务。 */
    private PresetPanelSession session;
    private boolean open;
    private int accent = 0xFFE59AC8;

    /** 这一页从哪一侧进来：-1 回退、+1 前进、0 刚打开（只做纵向落位）。 */
    private int pageDir;
    private long pageStartNanos;
    /** 这一帧换页那条 t 的原始值（错峰窗口开在它上面，不开在缓动后的进度上）。 */
    private double pageT = 1.0;

    private float scroll;
    private float displayScroll;
    private int maxScroll;
    private int contentHeight;

    /** 上一帧的弹窗矩形与列表视口：点击读它，与眼睛看到的是同一份。 */
    private int[] box = { 0, 0, 0, 0 };
    private int listTop;
    private int listBottom;
    private final List<Item> items = new ArrayList<Item>();

    /** 这一页涉及的分面（进页时取一次）。 */
    private List<Facet> look = Collections.emptyList();
    private List<Facet> audio = Collections.emptyList();

    private List<StylePreset> builtins = Collections.emptyList();
    private List<StylePreset> mine = Collections.emptyList();
    private int mineRevision = Integer.MIN_VALUE;

    private GuiTextField nameInput;

    /** 等待二次确认的删除；{@link #confirmShown} 留着最后一份，好让确认框淡出时还有字可画。 */
    private StylePreset pendingDelete;
    private StylePreset confirmShown;
    private float confirmGate;

    HudPresetDialog(GuiHudEditorPro host) {
        this.host = host;
    }

    // ==================== 开关 ====================

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) return;
        if (session == null) {
            session = PresetPanelSession.standard();
        }
        session.setNavigator(this);
        session.reset();
        open = true;
        pendingDelete = null;
        builtins = session.builtins();
        refreshMine(true);
        session.enterMain();
        beginPage(0);
    }

    void close() {
        open = false;
        pendingDelete = null;
        if (nameInput != null) nameInput.setFocused(false);
    }

    // ==================== 换页 ====================

    @Override
    public void showMain(boolean backward) {
        if (open) showMainPage(backward);
    }

    @Override
    public void showApply(StylePreset preset) {
        if (open) showApplyPage(preset);
    }

    private void showMainPage(boolean backward) {
        session.enterMain();
        refreshMine(false);
        beginPage(backward ? -1 : 1);
    }

    private void showApplyPage(StylePreset preset) {
        session.enterApply(preset);
        beginPage(1);
    }

    private void showSavePage() {
        session.enterSave();
        beginPage(1);
    }

    private void beginPage(int direction) {
        pageDir = direction;
        pageStartNanos = System.nanoTime();
        pageT = 0.0;
        scroll = 0f;
        displayScroll = 0f;
        PresetPanelSession.Page page = session.page();
        if (page == PresetPanelSession.Page.APPLY) {
            look = session.facetsOf(session.target(), Facet.Group.APPEARANCE);
            audio = session.facetsOf(session.target(), Facet.Group.AUDIO);
        } else if (page == PresetPanelSession.Page.SAVE) {
            look = session.facetsAvailable(Facet.Group.APPEARANCE);
            audio = session.facetsAvailable(Facet.Group.AUDIO);
        } else {
            look = Collections.emptyList();
            audio = Collections.emptyList();
        }
        if (page == PresetPanelSession.Page.SAVE) {
            ensureNameInput();
            nameInput.setText("");
            nameInput.setFocused(true);
        } else if (nameInput != null) {
            nameInput.setFocused(false);
        }
    }

    private void refreshMine(boolean force) {
        if (force || mineRevision != session.revision()) {
            mine = session.mine();
            mineRevision = session.revision();
        }
    }

    private void ensureNameInput() {
        if (nameInput == null) {
            nameInput = host.newTextField(PresetLimits.MAX_NAME_CHARS);
        }
    }

    private PresetPanelSession.Page page() {
        return session.page();
    }

    // ==================== 绘制 ====================

    /**
     * 画一帧。由宿主在 {@code pushAlpha(到场进度)} 里调用：整块的淡入淡出由宿主收口，
     * {@code rise} 是到场那一段的下坠量（暗幕不跟着走，只有弹窗本体走）。
     */
    void draw(int panelX, int panelY, int panelW, int panelH, int accent,
              int mouseX, int mouseY, int wheel, float rise) {
        if (session == null) return;
        this.accent = accent;
        if (open) refreshMine(false);
        long now = System.nanoTime();
        pageT = clamp01((float) ((now - pageStartNanos) / 1_000_000.0 / Math.max(1.0, PAGE_MS / SheetClock.speed())));
        confirmGate = host.motion("preset.confirm", pendingDelete != null ? 1f : 0f, POP_MS);
        if (pendingDelete != null) confirmShown = pendingDelete;

        // 暗幕只盖住设置面板：和播放器里那张一样，弹窗属于它所在的那块面板。
        host.drawRoundedRect(panelX, panelY, panelW, panelH, 16, SCRIM);

        int w = Math.max(200, Math.min(MAX_W, panelW - INSET * 2));
        int listW = w - PAD * 2;
        layout(0, 0, listW);
        int measured = contentHeight;
        int chrome = HEADER_H + STATUS_H + fieldReserve() + FOOTER_H;
        int maxH = Math.max(chrome + LIST_MIN_H, panelH - INSET * 2);
        int target = Math.min(maxH, chrome + Math.max(LIST_MIN_H, Math.min(LIST_MAX_H, measured)));
        int h = Math.round(Math.min(maxH, host.motion("preset.h", target, HEIGHT_MS)));
        int x = panelX + (panelW - w) / 2;
        int y = panelY + (panelH - h) / 2;
        box = new int[] { x, y, w, h };
        listTop = y + HEADER_H + STATUS_H + fieldReserve();
        listBottom = y + h - FOOTER_H;
        int listH = Math.max(1, listBottom - listTop);
        maxScroll = Math.max(0, measured - listH);

        int my = mouseY - Math.round(rise);
        if (open && pendingDelete == null && wheel != 0 && isInside(mouseX, my, x, y, w, h)) {
            scroll += wheel > 0 ? -32f : 32f;
        }
        scroll = Math.max(0f, Math.min(maxScroll, scroll));
        displayScroll = Interpolations.interpolate(displayScroll, scroll, 19f);
        if (Math.abs(displayScroll - scroll) < 0.4f) displayScroll = scroll;
        layout(x + PAD, listTop, listW);

        // 确认框开着、或者这一层已经在退场：底下的部件不再跟着鼠标亮。
        boolean live = open && pendingDelete == null;
        int mx = live ? mouseX : NOWHERE;
        int hy = live ? my : NOWHERE;

        SkijaGfx.pushMatrix();
        SkijaGfx.translate(0f, rise);
        try {
            host.drawSoftShadow(x, y, w, h, RADIUS);
            host.drawRoundedRect(x, y, w, h, RADIUS, DIALOG_BG);
            host.drawRoundedRect(x + 6, y, w - 12, 1, 1, PANEL_EDGE);
            drawHeader(x, y, w, mx, hy);
            drawStatus(x, y + HEADER_H, w);
            if (page() == PresetPanelSession.Page.SAVE) {
                drawNameField(x + PAD, y + HEADER_H + STATUS_H, w - PAD * 2, mx, hy);
            }
            drawList(x, w, listH, mx, hy);
            drawFooter(x, y + h - FOOTER_H, w, mx, hy);
            drawConfirm(open ? mouseX : NOWHERE, open ? my : NOWHERE);
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    private int fieldReserve() {
        return page() == PresetPanelSession.Page.SAVE ? FIELD_H + FIELD_GAP : 0;
    }

    // ---- 页眉 ----

    private int[] closeButton() {
        return new int[] { box[0] + box[2] - PAD - 22 + 4, box[1] + 15, 22, 22 };
    }

    private int[] backButton() {
        return new int[] { box[0] + PAD - 4, box[1] + 15, 22, 22 };
    }

    private void drawHeader(int x, int y, int w, int mx, int my) {
        float outer = host.alpha();
        host.setAlpha(outer * pageGate(0.0, 0.42));
        try {
            int[] close = closeButton();
            host.drawGhostButton("preset.close", FontManager.pf12, HudSettingsIcons.CLOSE, null,
                    close, mx, my, TEXT_SECONDARY, accent);
            CFontRenderer title = FontManager.pf14bold;
            CFontRenderer small = FontManager.pf12;
            if (page() == PresetPanelSession.Page.MAIN) {
                int plateX = x + PAD;
                int plateY = y + 12;
                host.drawRoundedRect(plateX, plateY, 28, 28, 9, fade(accent, 0.22f));
                wand(plateX + 14, plateY + 14, 15, accent);
                int textX = plateX + 38;
                double room = Math.max(20, close[0] - textX - 8);
                host.drawText(title, PresetPanelSession.TITLE, textX, y + 14, TEXT_PRIMARY);
                if (small != null) {
                    host.drawText(small, small.trim(PresetPanelSession.MAIN_SUBTITLE, room), textX, y + 30,
                            TEXT_SECONDARY);
                }
            } else {
                int[] back = backButton();
                host.drawGhostButton("preset.back", FontManager.pf12, HudSettingsIcons.BACK, null,
                        back, mx, my, TEXT_PRIMARY, accent);
                int textX = back[0] + back[2] + 8;
                double room = Math.max(20, close[0] - textX - 8);
                boolean apply = page() == PresetPanelSession.Page.APPLY && session.target() != null;
                String heading = apply ? session.target().meta().name() : PresetPanelSession.SAVE_TITLE;
                String sub = apply ? PresetPanelSession.applySubtitle(session.target())
                        : PresetPanelSession.SAVE_SUBTITLE;
                if (title != null) host.drawText(title, title.trim(heading, room), textX, y + 14, accent);
                if (small != null) host.drawText(small, small.trim(sub, room), textX, y + 30, TEXT_SECONDARY);
            }
            host.drawRoundedRect(x + 14, y + HEADER_H - 1, w - 28, 1, 1, 0x1AFFFFFF);
        } finally {
            host.setAlpha(outer);
        }
    }

    // ---- 状态行 ----

    private void drawStatus(int x, int y, int w) {
        String text;
        PresetPanelSession.Tone tone;
        PresetPanelSession.Page page = page();
        if (page == PresetPanelSession.Page.APPLY) {
            text = session.previewStatus();
            tone = session.previewTone();
        } else if (page == PresetPanelSession.Page.SAVE) {
            text = session.saveHint();
            tone = session.saveHintBad() ? PresetPanelSession.Tone.BAD : PresetPanelSession.Tone.INFO;
        } else {
            text = session.statusText();
            tone = session.statusTone();
        }
        CFontRenderer fr = FontManager.pf12;
        if (fr == null) return;
        int ink = toneInk(tone);
        double centerY = y + STATUS_H / 2.0 + 1;
        partyIcon(toneGlyph(tone), 12, x + PAD + 6, centerY, ink);
        host.drawText(fr, fr.trim(text, Math.max(20, w - PAD * 2 - 18)), x + PAD + 16, centerY - 4,
                tone == PresetPanelSession.Tone.INFO ? TEXT_SECONDARY : ink);
    }

    private static int toneInk(PresetPanelSession.Tone tone) {
        switch (tone) {
            case OK:
                return OK_INK;
            case BAD:
                return BAD_INK;
            case WARN:
                return WARN_INK;
            default:
                return TEXT_TERTIARY;
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

    // ---- 名字输入框（存为预设页） ----

    private int[] nameFieldRect() {
        return new int[] { box[0] + PAD, box[1] + HEADER_H + STATUS_H, box[2] - PAD * 2, FIELD_H };
    }

    private void drawNameField(int x, int y, int w, int mx, int my) {
        ensureNameInput();
        CFontRenderer fr = FontManager.pf12;
        if (fr == null) return;
        boolean focused = nameInput.isFocused();
        float focus = host.motion("preset.name.focus", focused ? 1f : 0f, FOCUS_MS);
        float hp = host.named("preset.name.box", isInside(mx, my, x, y, w, FIELD_H));
        host.drawRoundedRect(x, y, w, FIELD_H, FIELD_H * 0.5,
                mix(mix(0x18FFFFFF, 0x22FFFFFF, hp), 0x2BFFFFFF, focus));
        int lineW = Math.round((w - 24) * focus);
        if (lineW > 1) {
            host.drawRoundedRect(x + (w - lineW) / 2.0, y + FIELD_H - 1.5, lineW, 1.5, 1, fade(accent, 0.85f));
        }
        partyIcon(PartyIcons.TEXT_FORMAT, 12, x + 14, y + FIELD_H / 2.0,
                mix(TEXT_TERTIARY, TEXT_SECONDARY, Math.max(hp, focus)));

        int textLeft = x + 26;
        int room = Math.max(10, x + w - 12 - textLeft);
        nameInput.xPosition = textLeft;
        nameInput.yPosition = y + 7;
        nameInput.width = room;
        nameInput.height = FIELD_H - 10;
        String text = nameInput.getText() == null ? "" : nameInput.getText();
        if (text.isEmpty()) {
            host.drawText(fr, fr.trim(PresetPanelSession.NAME_PLACEHOLDER, room), textLeft, y + 10, TEXT_TERTIARY);
            if (focused) {
                host.drawRoundedRect(textLeft, y + 8, 1, FIELD_H - 16, 1, fade(0xFFEAF3FF, focus));
            }
            return;
        }
        String shown = tail(fr, text, room);
        host.drawText(fr, shown, textLeft, y + 10, TEXT_PRIMARY);
        if (focused) {
            double caretX = textLeft + 1 + Math.min(fr.getStringWidthD(shown), room);
            host.drawRoundedRect(caretX, y + 8, 1, FIELD_H - 16, 1, fade(0xFFEAF3FF, focus));
        }
    }

    /** 放不下时露出尾巴而不是开头：正在打字的人要看的是光标前面那几个字。 */
    private static String tail(CFontRenderer fr, String text, double room) {
        if (fr.getStringWidthD(text) <= room) return text;
        int start = 0;
        while (start < text.length() - 1 && fr.getStringWidthD("…" + text.substring(start)) > room) {
            start++;
        }
        return "…" + text.substring(start);
    }

    // ---- 列表 ----

    private void drawList(int x, int w, int listH, int mx, int my) {
        boolean overList = isInside(mx, my, x, listTop, w, listH);
        int lx = overList ? mx : NOWHERE;
        int ly = overList ? my : NOWHERE;
        host.beginScissor(x + 1, listTop, w - 2, listH);
        try {
            for (Item item : items) {
                if (item.y + item.h < listTop - 4 || item.y > listBottom + 4) continue;
                float reveal = reveal(item.slot);
                float dx = pageDir * (1f - reveal) * CONTENT_SLIDE;
                float dy = (1f - reveal) * CONTENT_DROP;
                float outer = host.alpha();
                host.setAlpha(outer * reveal);
                SkijaGfx.pushMatrix();
                SkijaGfx.translate(dx, dy);
                try {
                    // 鼠标反向补偿：悬停跟着滑进来的视觉走，命中矩形本身不动。
                    drawItem(item, lx - Math.round(dx), ly - Math.round(dy));
                } finally {
                    SkijaGfx.popMatrix();
                    host.setAlpha(outer);
                }
            }
        } finally {
            host.endScissor();
        }
        if (maxScroll > 0) {
            int trackTop = listTop + 2;
            int trackH = listH - 4;
            int thumbH = Math.max(20, trackH * listH / Math.max(1, contentHeight));
            float thumbY = trackTop + (trackH - thumbH) * clamp01(displayScroll / maxScroll);
            host.drawRoundedRect(x + w - 8, trackTop, 3, trackH, 2, 0x22FFFFFF);
            host.drawRoundedRect(x + w - 9, thumbY, 5, thumbH, 3, 0x66FFFFFF);
        }
    }

    private void drawItem(Item item, int mx, int my) {
        switch (item.kind) {
            case SECTION:
                drawSection(item);
                break;
            case CARD:
                drawCard(item, mx, my);
                break;
            case EMPTY:
                drawEmpty(item);
                break;
            case GROUP:
                host.drawRoundedRect(item.x, item.y, item.w, item.h, CARD_RADIUS, CARD_BG);
                host.drawRoundedRect(item.x + CARD_RADIUS, item.y, item.w - CARD_RADIUS * 2, 1, 1, CARD_EDGE);
                break;
            case FACET:
                drawFacet(item, mx, my);
                break;
            case NOTE: {
                CFontRenderer fr = FontManager.pf12;
                if (fr != null) {
                    host.drawText(fr, fr.trim(item.text, item.w - 14), item.x + 10, item.y + 4, TEXT_SECONDARY);
                }
                break;
            }
            default:
                break;
        }
    }

    /** 分组小标题：与设置页的分组标题同一套量（图标在 x+9、字在 x+21）。 */
    private void drawSection(Item item) {
        double cy = item.y + SECTION_H / 2.0 + 1;
        if (item.settingsGlyph != null) {
            host.drawIcon(FontManager.settingsIcon24, item.settingsGlyph, item.x + 9, cy, item.tint);
        } else if (item.partyGlyph != null) {
            partyIcon(item.partyGlyph, 12, item.x + 9, cy, item.tint);
        }
        host.drawText(FontManager.pf12bold, item.text, item.x + 21, item.y + 8,
                item.tint == WARN_INK ? WARN_INK : TEXT_SECONDARY);
    }

    private void drawEmpty(Item item) {
        CFontRenderer fr = FontManager.pf12;
        if (fr == null) return;
        host.drawRoundedRect(item.x, item.y, item.w, item.h - 4, CARD_RADIUS, 0x0CFFFFFF);
        partyIcon(item.partyGlyph, 16, item.x + 20, item.y + (item.h - 4) / 2.0, TEXT_TERTIARY);
        String[] lines = wrapToTwoLines(fr, item.text, Math.max(20, item.w - 46));
        if (lines[1] == null) {
            host.drawText(fr, lines[0], item.x + 36, item.y + (item.h - 4) / 2.0 - 4, TEXT_SECONDARY);
        } else {
            host.drawText(fr, lines[0], item.x + 36, item.y + (item.h - 4) / 2.0 - 10, TEXT_SECONDARY);
            host.drawText(fr, lines[1], item.x + 36, item.y + (item.h - 4) / 2.0 + 2, TEXT_SECONDARY);
        }
    }

    // ---- 预设卡 ----

    private static boolean isMine(StylePreset preset) {
        return preset.source() == StylePreset.Source.USER;
    }

    private int[] copyButton(Item item) {
        return new int[] { item.x + item.w - 12 - ROW_BUTTON - 6 - ROW_BUTTON,
                item.y + (item.h - ROW_BUTTON) / 2, ROW_BUTTON, ROW_BUTTON };
    }

    private int[] deleteButton(Item item) {
        return new int[] { item.x + item.w - 12 - ROW_BUTTON, item.y + (item.h - ROW_BUTTON) / 2,
                ROW_BUTTON, ROW_BUTTON };
    }

    private static String idOf(StylePreset preset) {
        String id = preset.meta().id();
        return id == null ? preset.meta().name() : id;
    }

    private void drawCard(Item item, int mx, int my) {
        StylePreset preset = item.preset;
        boolean mine = isMine(preset);
        boolean over = isInside(mx, my, item.x, item.y, item.w, item.h);
        float hp = host.named("preset.card." + idOf(preset), over);
        host.drawRoundedRect(item.x, item.y, item.w, item.h, CARD_RADIUS, CARD_BG);
        host.drawRoundedRect(item.x + CARD_RADIUS, item.y, item.w - CARD_RADIUS * 2, 1, 1, CARD_EDGE);
        if (hp > 0.01f) {
            host.drawRoundedRect(item.x + 1, item.y + 1, item.w - 2, item.h - 2, CARD_RADIUS - 1,
                    fade(ROW_HOVER, hp));
            int barH = Math.round((item.h - 18) * hp);
            if (barH > 1) {
                host.drawRoundedRect(item.x + 2, item.y + (item.h - barH) / 2.0, 2, barH, 1, fade(accent, hp));
            }
        }

        int discX = item.x + 10;
        int discY = item.y + (item.h - DISC) / 2;
        host.drawRoundedRect(discX, discY, DISC, DISC, 9, fade(accent, mine ? 0.16f : 0.24f));
        partyIcon(PresetPanelSession.iconOf(preset), 16, discX + DISC / 2.0, discY + DISC / 2.0, accent);

        CFontRenderer bold = FontManager.pf12bold;
        CFontRenderer fr = FontManager.pf12;
        int textX = discX + DISC + 10;
        int reserve;
        if (mine) {
            reserve = 12 + ROW_BUTTON * 2 + 6 + 8;
        } else {
            reserve = 12 + builtinPillWidth() + 8;
        }
        double room = Math.max(20, item.x + item.w - reserve - textX);
        if (bold != null) host.drawText(bold, bold.trim(preset.meta().name(), room), textX, item.y + 11, TEXT_PRIMARY);
        if (fr != null) {
            host.drawText(fr, fr.trim(PresetPanelSession.cardNote(preset), room), textX, item.y + 26,
                    mix(TEXT_TERTIARY, TEXT_SECONDARY, 0.55f + 0.45f * hp));
        }

        if (mine) {
            drawRowButton("preset.copy." + idOf(preset), copyButton(item), PartyIcons.LINK, false, mx, my);
            drawRowButton("preset.del." + idOf(preset), deleteButton(item), PartyIcons.CLOSE, true, mx, my);
        } else {
            drawBuiltinPill(item);
        }
    }

    private int builtinPillWidth() {
        CFontRenderer fr = FontManager.pf12;
        return 12 + 4 + (fr == null ? 24 : fr.getStringWidth("内置")) + 12;
    }

    private void drawBuiltinPill(Item item) {
        CFontRenderer fr = FontManager.pf12;
        int w = builtinPillWidth();
        int h = 18;
        int x = item.x + item.w - 12 - w;
        int y = item.y + (item.h - h) / 2;
        host.drawRoundedRect(x, y, w, h, h * 0.5, fade(accent, 0.16f));
        partyIcon(PartyIcons.CHECK, 12, x + 12, y + h / 2.0, accent);
        if (fr != null) host.drawText(fr, "内置", x + 20, y + h / 2.0 - 4, accent);
    }

    private void drawRowButton(String key, int[] r, String glyph, boolean danger, int mx, int my) {
        float hp = host.named(key, isInside(mx, my, r[0], r[1], r[2], r[3]));
        int fill = mix(BUTTON_BG, danger ? DANGER : accent, hp);
        host.drawRoundedRect(r[0], r[1], r[2], r[3], r[3] * 0.5, fill);
        partyIcon(glyph, 12, r[0] + r[2] / 2.0, r[1] + r[3] / 2.0, mix(TEXT_SECONDARY, 0xFFFFFFFF, hp));
    }

    // ---- 分面开关行 ----

    private void drawFacet(Item item, int mx, int my) {
        boolean apply = page() == PresetPanelSession.Page.APPLY;
        boolean over = isInside(mx, my, item.x, item.y, item.w, item.h);
        String key = (apply ? "preset.ap." : "preset.sv.") + item.facet.id();
        float hp = host.named(key, over);
        if (!item.first) {
            host.drawRoundedRect(item.x + 12, item.y, item.w - 24, 1, 1, fade(0x14FFFFFF, 1f - hp));
        }
        if (hp > 0.01f) {
            host.drawRoundedRect(item.x + 2, item.y + 1, item.w - 4, item.h - 2, CARD_RADIUS - 3,
                    fade(ROW_HOVER, hp));
            int barH = Math.round((item.h - 16) * hp);
            if (barH > 1) {
                host.drawRoundedRect(item.x + 3, item.y + (item.h - barH) / 2.0, 2, barH, 1, fade(accent, hp));
            }
        }
        CFontRenderer bold = FontManager.pf12bold;
        CFontRenderer fr = FontManager.pf12;
        String name = item.facet.displayName();
        if (bold != null) {
            host.drawText(bold, name, item.x + 14, item.y + 8, TEXT_PRIMARY);
            if (item.facet.optIn() && fr != null) {
                host.drawText(fr, "（默认不勾）", item.x + 14 + bold.getStringWidthD(name) + 2, item.y + 8,
                        TEXT_TERTIARY);
            }
        }
        if (fr != null) {
            double room = Math.max(20, item.w - 14 - SWITCH_W - 14 - 12);
            host.drawText(fr, fr.trim(item.facet.description(), room), item.x + 14, item.y + 22,
                    mix(TEXT_TERTIARY, TEXT_SECONDARY, hp));
        }
        boolean on = apply ? session.isSelected(item.facet) : session.isSaveSelected(item.facet);
        float knob = host.motion(key + ".knob", on ? 1f : 0f, KNOB_MS);
        host.drawSwitch(item.x + item.w - 14 - SWITCH_W, item.y + (item.h - SWITCH_H) / 2, knob, accent, hp);
    }

    // ---- 页脚 ----

    private List<FooterButton> footerButtons() {
        List<FooterButton> buttons = new ArrayList<FooterButton>();
        switch (page()) {
            case APPLY:
                buttons.add(new FooterButton(PartyIcons.CHECK, "全选", session::selectAll, false, null));
                buttons.add(new FooterButton(PartyIcons.TEXT_FORMAT, "只选外观", session::selectAppearanceOnly,
                        false, null));
                buttons.add(new FooterButton(PartyIcons.HEADPHONES, "只选音效", session::selectAudioOnly,
                        false, null));
                buttons.add(new FooterButton(PartyIcons.CHECK, session.applyLabel(), session::applyTarget,
                        true, null));
                break;
            case SAVE:
                buttons.add(new FooterButton(PartyIcons.TEXT_FORMAT, "只选外观", session::saveSelectAppearanceOnly,
                        false, null));
                buttons.add(new FooterButton(PartyIcons.HEADPHONES, "只选音效", session::saveSelectAudioOnly,
                        false, null));
                buttons.add(new FooterButton(PartyIcons.CHECK, "保存", () -> session.save(nameText(), false),
                        false, null));
                buttons.add(new FooterButton(PartyIcons.LINK, "保存并复制分享码",
                        () -> session.save(nameText(), true), true, null));
                break;
            default:
                buttons.add(new FooterButton(PartyIcons.ADD, "存为预设", this::showSavePage, false, null));
                buttons.add(new FooterButton(PartyIcons.LINK, "粘贴导入", session::importFromClipboard, false, null));
                buttons.add(new FooterButton(PartyIcons.FOLDER_OPEN, session.picking() ? "选择中…" : "文件导入",
                        session::browseForFile, false, null));
                buttons.add(new FooterButton(PartyIcons.SYNC, "撤销套用", session::undo, false,
                        session.undoTooltip()));
                buttons.add(new FooterButton(PartyIcons.FOLDER_OPEN, "打开目录", session::openFolder, false, null));
                break;
        }
        return buttons;
    }

    private String footerNote() {
        switch (page()) {
            case APPLY:
                return PresetPanelSession.APPLY_NOTE;
            case SAVE:
                return PresetPanelSession.SAVE_NOTE;
            default:
                return PresetPanelSession.MAIN_NOTE;
        }
    }

    private int[] footerRect(int index, int count) {
        int span = box[2] - PAD * 2;
        int width = Math.max(1, (span - BUTTON_GAP * (count - 1)) / count);
        return new int[] { box[0] + PAD + (width + BUTTON_GAP) * index, box[1] + box[3] - FOOTER_H + 10,
                width, BUTTON_H };
    }

    /** 每颗键都放得下「图标 + 字」才带字；有一颗放不下就整排只留图标（一排里一半有字一半没有更难看）。 */
    private boolean footerFitsLabels(List<FooterButton> buttons) {
        CFontRenderer fr = FontManager.pf12bold;
        if (fr == null) return false;
        int width = footerRect(0, buttons.size())[2];
        for (FooterButton button : buttons) {
            if (12 + 5 + fr.getStringWidth(button.label) + 16 > width) return false;
        }
        return true;
    }

    private void drawFooter(int x, int footerY, int w, int mx, int my) {
        float outer = host.alpha();
        host.setAlpha(outer * pageGate(0.18, 0.62));
        try {
            host.drawRoundedRect(x + 14, footerY, w - 28, 1, 1, 0x1AFFFFFF);
            List<FooterButton> buttons = footerButtons();
            boolean labels = footerFitsLabels(buttons);
            String hovered = null;
            CFontRenderer bold = FontManager.pf12bold;
            for (int i = 0; i < buttons.size(); i++) {
                FooterButton button = buttons.get(i);
                int[] r = footerRect(i, buttons.size());
                boolean over = isInside(mx, my, r[0], r[1], r[2], r[3]);
                float hp = host.named("preset.foot." + page().name() + "." + i, over);
                if (over) hovered = button.hint != null ? button.hint : (labels ? null : button.label);
                int fill;
                int ink;
                if (button.primary) {
                    fill = mix(accent, 0xFFFFFFFF, 0.16f * hp);
                    ink = 0xFFFFFFFF;
                } else {
                    fill = mix(BUTTON_BG, BUTTON_HOVER, hp);
                    ink = mix(TEXT_PRIMARY, 0xFFFFFFFF, hp * 0.6f);
                    if (hp > 0.02f) {
                        host.drawRoundedRect(r[0] + r[3] * 0.35, r[1], r[2] - r[3] * 0.7, 1, 1, fade(accent, hp * 0.9f));
                    }
                }
                host.drawRoundedRect(r[0], r[1], r[2], r[3], r[3] * 0.5, fill);
                double cy = r[1] + r[3] / 2.0;
                if (labels && bold != null) {
                    double content = 12 + 5 + bold.getStringWidthD(button.label);
                    double left = r[0] + (r[2] - content) / 2.0;
                    partyIcon(button.glyph, 12, left + 6, cy, ink);
                    host.drawText(bold, button.label, left + 17, cy - 4, ink);
                } else {
                    partyIcon(button.glyph, 12, r[0] + r[2] / 2.0, cy, ink);
                }
            }
            CFontRenderer fr = FontManager.pf12;
            if (fr != null) {
                int noteY = footerY + 44;
                host.drawIcon(FontManager.settingsIcon24, HudSettingsIcons.INFO, x + PAD + 5, noteY + 4, TEXT_TERTIARY);
                String note = hovered != null ? hovered : footerNote();
                host.drawText(fr, fr.trim(note, Math.max(20, w - PAD * 2 - 16)), x + PAD + 14, noteY, TEXT_TERTIARY);
            }
        } finally {
            host.setAlpha(outer);
        }
    }

    // ---- 删除确认 ----

    private int[] confirmBounds() {
        int w = Math.min(300, box[2] - 32);
        int h = 116;
        return new int[] { box[0] + (box[2] - w) / 2, box[1] + Math.max(16, (box[3] - h) / 2), w, h };
    }

    private int[] confirmCancel(int[] c) {
        return new int[] { c[0] + c[2] - 74 - 92 - 18, c[1] + c[3] - 34, 74, 23 };
    }

    private int[] confirmDelete(int[] c) {
        return new int[] { c[0] + c[2] - 92 - 10, c[1] + c[3] - 34, 92, 23 };
    }

    private void drawConfirm(int mx, int my) {
        if (confirmGate <= 0.01f || confirmShown == null) return;
        CFontRenderer fr = FontManager.pf12;
        CFontRenderer bold = FontManager.pf12bold;
        if (fr == null || bold == null) return;
        float outer = host.alpha();
        host.setAlpha(outer * confirmGate);
        SkijaGfx.pushMatrix();
        SkijaGfx.translate(0f, (1f - confirmGate) * CONTENT_DROP);
        try {
            host.drawRoundedRect(box[0], box[1], box[2], box[3], RADIUS, 0x66000000);
            int[] c = confirmBounds();
            host.drawSoftShadow(c[0], c[1], c[2], c[3], 12);
            host.drawRoundedRect(c[0], c[1], c[2], c[3], 12, CONFIRM_BG);
            host.drawRoundedRect(c[0] + 6, c[1], c[2] - 12, 1, 1, PANEL_EDGE);
            host.drawRoundedRect(c[0] + 16, c[1] + 16, 22, 22, 11, 0x33D96B53);
            host.drawIcon(FontManager.settingsIcon24, HudSettingsIcons.WARNING, c[0] + 27, c[1] + 27, 0xFFE8836A);
            host.drawText(bold, bold.trim(PresetPanelSession.DELETE_TITLE + "「" + confirmShown.meta().name() + "」？",
                    c[2] - 62), c[0] + 46, c[1] + 19, TEXT_PRIMARY);
            String[] lines = wrapToTwoLines(fr, PresetPanelSession.deleteMessage(confirmShown), c[2] - 34);
            host.drawText(fr, lines[0], c[0] + 17, c[1] + 48, TEXT_SECONDARY);
            if (lines[1] != null) host.drawText(fr, lines[1], c[0] + 17, c[1] + 61, TEXT_SECONDARY);

            int[] cancel = confirmCancel(c);
            int[] confirm = confirmDelete(c);
            float cancelHover = host.named("preset.confirm.cancel",
                    isInside(mx, my, cancel[0], cancel[1], cancel[2], cancel[3]));
            float confirmHover = host.named("preset.confirm.ok",
                    isInside(mx, my, confirm[0], confirm[1], confirm[2], confirm[3]));
            host.drawRoundedRect(cancel[0], cancel[1], cancel[2], cancel[3], 9, mix(BUTTON_BG, BUTTON_HOVER, cancelHover));
            host.drawRoundedRect(confirm[0], confirm[1], confirm[2], confirm[3], 9, mix(DANGER, DANGER_HOVER, confirmHover));
            host.drawCenteredText(fr, "取消", cancel[0] + cancel[2] / 2.0, cancel[1] + 8, TEXT_PRIMARY);
            partyIcon(PartyIcons.CLOSE, 12, confirm[0] + 18, confirm[1] + confirm[3] / 2.0, 0xFFFFFFFF);
            host.drawText(fr, "删除", confirm[0] + 30, confirm[1] + 8, 0xFFFFFFFF);
        } finally {
            SkijaGfx.popMatrix();
            host.setAlpha(outer);
        }
    }

    // ==================== 排版（绘制与命中共用） ====================

    /**
     * 把当前页排成一串矩形。<b>这一页唯一的坐标来源</b>：绘制读它，{@link #mouseClicked} 也读它。
     * 纵向起点用 {@link #displayScroll}，滚动中点击也不会错位。
     */
    private void layout(int x, int top, int w) {
        items.clear();
        int cursor = top - Math.round(displayScroll);
        int start = cursor;
        int slot = 0;
        switch (page()) {
            case APPLY: {
                cursor += 2;
                if (look.isEmpty() && audio.isEmpty()) {
                    Item empty = new Item(Kind.EMPTY, x, cursor, w, EMPTY_H, slot++);
                    empty.text = PresetPanelSession.APPLY_EMPTY;
                    empty.partyGlyph = PartyIcons.INFO;
                    items.add(empty);
                    cursor += EMPTY_H;
                }
                cursor = facetGroup(x, cursor, w, "外观", HudSettingsIcons.PALETTE, null, look, slot);
                slot += look.isEmpty() ? 0 : 2;
                cursor = facetGroup(x, cursor, w, "音效", null, PartyIcons.HEADPHONES, audio, slot);
                slot += audio.isEmpty() ? 0 : 2;
                List<String> lines = session.previewLines();
                if (!lines.isEmpty()) {
                    Item label = new Item(Kind.SECTION, x, cursor, w, SECTION_H, slot++);
                    label.text = "注意";
                    label.settingsGlyph = HudSettingsIcons.WARNING;
                    label.tint = WARN_INK;
                    items.add(label);
                    cursor += SECTION_H;
                    int count = Math.min(lines.size(), PresetPanelSession.NOTE_LIMIT);
                    for (int i = 0; i < count; i++) {
                        Item note = new Item(Kind.NOTE, x, cursor, w, NOTE_H, slot);
                        note.text = session.noteLine(i);
                        items.add(note);
                        cursor += NOTE_H;
                    }
                    cursor += 4;
                }
                break;
            }
            case SAVE: {
                cursor += 2;
                cursor = facetGroup(x, cursor, w, "外观", HudSettingsIcons.PALETTE, null, look, slot);
                slot += look.isEmpty() ? 0 : 2;
                cursor = facetGroup(x, cursor, w, "音效", null, PartyIcons.HEADPHONES, audio, slot);
                break;
            }
            default: {
                cursor += 2;
                Item builtinLabel = new Item(Kind.SECTION, x, cursor, w, SECTION_H, slot++);
                builtinLabel.text = PresetPanelSession.SECTION_BUILTIN;
                builtinLabel.settingsGlyph = HudSettingsIcons.SPARKLE;
                builtinLabel.tint = accent;
                items.add(builtinLabel);
                cursor += SECTION_H;
                for (StylePreset preset : builtins) {
                    Item card = new Item(Kind.CARD, x, cursor, w, CARD_H, slot++);
                    card.preset = preset;
                    items.add(card);
                    cursor += CARD_H + CARD_GAP;
                }
                cursor += 4;
                Item mineLabel = new Item(Kind.SECTION, x, cursor, w, SECTION_H, slot++);
                mineLabel.text = session.mineSectionTitle(mine.size());
                mineLabel.partyGlyph = PartyIcons.PERSON;
                mineLabel.tint = accent;
                items.add(mineLabel);
                cursor += SECTION_H;
                if (mine.isEmpty()) {
                    Item empty = new Item(Kind.EMPTY, x, cursor, w, EMPTY_H, slot++);
                    empty.text = PresetPanelSession.EMPTY_MINE;
                    empty.partyGlyph = PartyIcons.FOLDER_OPEN;
                    items.add(empty);
                    cursor += EMPTY_H;
                }
                for (StylePreset preset : mine) {
                    Item card = new Item(Kind.CARD, x, cursor, w, CARD_H, slot++);
                    card.preset = preset;
                    items.add(card);
                    cursor += CARD_H + CARD_GAP;
                }
                break;
            }
        }
        contentHeight = cursor - start + 6;
    }

    /** 一组开关：小标题 + 一张底板 + 若干行。底板与行同一档入场。 */
    private int facetGroup(int x, int cursor, int w, String title, String settingsGlyph, String partyGlyph,
                           List<Facet> facets, int slot) {
        if (facets.isEmpty()) return cursor;
        Item label = new Item(Kind.SECTION, x, cursor, w, SECTION_H, slot);
        label.text = title;
        label.settingsGlyph = settingsGlyph;
        label.partyGlyph = partyGlyph;
        label.tint = accent;
        items.add(label);
        cursor += SECTION_H;
        items.add(new Item(Kind.GROUP, x, cursor, w, facets.size() * FACET_H, slot + 1));
        for (int i = 0; i < facets.size(); i++) {
            Item row = new Item(Kind.FACET, x, cursor, w, FACET_H, slot + 1);
            row.facet = facets.get(i);
            row.first = i == 0;
            items.add(row);
            cursor += FACET_H;
        }
        return cursor + GROUP_GAP;
    }

    // ==================== 输入 ====================

    void mouseClicked(int mouseX, int mouseY, int button) {
        if (!open || box[2] <= 0) return;
        if (button != 0) return;
        if (pendingDelete != null) {
            handleConfirmClick(mouseX, mouseY);
            return;
        }
        // 点在弹窗外面（暗幕上、或者整块面板之外）：和播放器那张一样，收起弹窗，这一下不再往下传。
        if (!isInside(mouseX, mouseY, box[0], box[1], box[2], box[3])) {
            close();
            return;
        }
        int[] close = closeButton();
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            close();
            return;
        }
        if (page() != PresetPanelSession.Page.MAIN) {
            int[] back = backButton();
            if (isInside(mouseX, mouseY, back[0], back[1], back[2], back[3])) {
                showMainPage(true);
                return;
            }
        }
        if (page() == PresetPanelSession.Page.SAVE) {
            ensureNameInput();
            int[] field = nameFieldRect();
            if (isInside(mouseX, mouseY, field[0], field[1], field[2], field[3])) {
                nameInput.setFocused(true);
                nameInput.mouseClicked(mouseX, mouseY, button);
                return;
            }
            nameInput.setFocused(false);
        }

        List<FooterButton> buttons = footerButtons();
        for (int i = 0; i < buttons.size(); i++) {
            int[] r = footerRect(i, buttons.size());
            if (isInside(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                buttons.get(i).action.run();
                return;
            }
        }

        if (!isInside(mouseX, mouseY, box[0], listTop, box[2], listBottom - listTop)) return;
        for (Item item : items) {
            if (!isInside(mouseX, mouseY, item.x, item.y, item.w, item.h)) continue;
            if (item.kind == Kind.CARD) {
                if (isMine(item.preset)) {
                    int[] copy = copyButton(item);
                    if (isInside(mouseX, mouseY, copy[0], copy[1], copy[2], copy[3])) {
                        session.copyShareCode(item.preset);
                        return;
                    }
                    int[] delete = deleteButton(item);
                    if (isInside(mouseX, mouseY, delete[0], delete[1], delete[2], delete[3])) {
                        pendingDelete = item.preset;
                        return;
                    }
                }
                showApplyPage(item.preset);
                return;
            }
            if (item.kind == Kind.FACET) {
                if (page() == PresetPanelSession.Page.APPLY) {
                    session.toggle(item.facet);
                } else {
                    session.toggleSave(item.facet);
                }
                return;
            }
        }
    }

    private void handleConfirmClick(int mouseX, int mouseY) {
        int[] c = confirmBounds();
        int[] confirm = confirmDelete(c);
        if (isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3])) {
            StylePreset target = pendingDelete;
            pendingDelete = null;
            session.delete(target);
            refreshMine(false);
            return;
        }
        // 取消键、确认框之外：都当作「不删了」。
        pendingDelete = null;
    }

    void keyTyped(char typedChar, int keyCode) {
        if (!open) return;
        if (pendingDelete != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                pendingDelete = null;
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                StylePreset target = pendingDelete;
                pendingDelete = null;
                session.delete(target);
                refreshMine(false);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // 子页先回主页，主页再关 —— 关的只是这一层弹窗，不是整个设置页。
            if (page() == PresetPanelSession.Page.MAIN) {
                close();
            } else {
                showMainPage(true);
            }
            return;
        }
        if (page() == PresetPanelSession.Page.SAVE && nameInput != null && nameInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                session.save(nameText(), false);
                return;
            }
            nameInput.textboxKeyTyped(typedChar, keyCode);
        }
    }

    private String nameText() {
        return nameInput == null || nameInput.getText() == null ? "" : nameInput.getText();
    }

    // ==================== 动效与图标 ====================

    /** 换页那条 t 上开的一个缓动窗口。 */
    private float pageGate(double first, double travel) {
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(pageT, first, Math.min(first + travel, 1.0)));
    }

    /** 第 {@code slot} 块这一帧该有多实。窗口右端钉在 1.0 以内，序号并档，长列表底部也能到场。 */
    private float reveal(int slot) {
        double first = ROW_FIRST + Math.min(slot, ROW_STAGGER_LIMIT) * ROW_STEP;
        return (float) TransitionCurveCache.MOTION.at(SheetClock.window(pageT, first, Math.min(first + ROW_TRAVEL, 1.0)));
    }

    /**
     * 一枚 party-icons 图标，em 方格中心落在 {@code (cx, cy)}。
     *
     * <p>不能走宿主的 {@code drawIcon}：那一套量的是 hud-settings-icons 的满格方块，party-icons 里没有它；
     * 这里量 {@link PartyIcons#FRAME}，和播放器那张弹窗的图标落在同一个网格上。</p>
     */
    private void partyIcon(String glyph, double box, double cx, double cy, int color) {
        if (glyph == null || glyph.isEmpty()) return;
        CFontRenderer font = NcmSheet.iconFont(box);
        if (font == null) return;
        GlyphInkMetrics square = partySquare(font);
        double x;
        double y;
        if (square != null) {
            x = square.alignCenterX(cx);
            y = square.alignCenterY(cy);
        } else {
            x = cx - font.getStringWidthD(glyph) * .5;
            y = cy - font.getFontHeight() * .5;
        }
        host.drawText(font, glyph, x, y, color);
    }

    private static GlyphInkMetrics partySquare(CFontRenderer font) {
        synchronized (PARTY_SQUARE) {
            if (PARTY_SQUARE.containsKey(font)) return PARTY_SQUARE.get(font);
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
        synchronized (PARTY_SQUARE) {
            PARTY_SQUARE.put(font, measured);
        }
        return measured;
    }

    /** 魔法棒（lucide wand-sparkles），与播放条上那一枚同一套几何。画不了时退回 sparkle 字形。 */
    void wand(double cx, double cy, double size, int color) {
        drawWand(host, cx, cy, size, color);
    }

    static void drawWand(GuiHudEditorPro host, double cx, double cy, double size, int color) {
        int tinted = fade(color, host.alpha());
        if (((tinted >>> 24) & 0xFF) == 0) return;
        if (!FoliaLucideIcons.draw(FoliaLucideIcons.PRESET, cx, cy, size, tinted)) {
            host.drawIcon(FontManager.settingsIcon24, HudSettingsIcons.SPARKLE, cx, cy, color);
        }
    }
}
