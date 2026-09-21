package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.update.Announcement;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.update.UpdateManifest;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 公告中心的界面本体：一屏可以滚动的公告卡片。
 *
 * <p>与 {@link UpdateNoticeOverlay} 是两回事 —— 那个是「有一个新版本」这一句话的两种结论，
 * 定高定行；公告是<b>条数不定、长短不定</b>的一批内容，所以这里用 {@link NcmSheet} 那套
 * 「遮罩 + 弹窗 + 硬裁内容区 + 整页滚动」的骨架，而不是自己再手写一遍裁剪与页脚。</p>
 *
 * <p><b>为什么整套版式都按当帧宽度算。</b>弹窗宽度是 {@code min(偏好宽度, 窗口宽 - 24)}，窗口一变
 * 它就把折行宽度、每张卡的高度、卡片总高、弹窗高度一起带着变；GUI 缩放开到三四档时可用高度只剩
 * 两三百逻辑像素，所以正文必须能滚、页脚必须贴底 —— 这两件事都由 {@link NcmSheet} 夹好，
 * 这里只负责把量出来的数字交出去。</p>
 *
 * <p><b>高度必须在滚动区排版之前量好。</b>{@code ScrollPanel} 在自己的 {@code onRender} 里按子项
 * <b>当前</b>高度排 Y，而子项自己的 {@code beforeRenderCallback} 要到它被渲染时才跑 —— 把尺寸交给
 * 行自己算会晚一帧，折叠/展开那一下看得见卡片跳位。所以测量全部挂在根面板的回调上
 * （{@link #measure()}），它先于弹窗、内容区与滚动区的回调执行。</p>
 *
 * <p>组件树只在建卡片时搭一次：每张卡上的文字都是从 {@link CardView#data} 现取的，
 * 换公告、点刷新、拖窗口都不需要重建。</p>
 */
public final class AnnouncementOverlay extends NcmSheet {

    // ──────────────────────────── 版式常量 ────────────────────────────

    /** 弹窗偏好宽度。再宽一行正文就要来回扫；比更新提示那张卡宽是因为这里要装多条内容。 */
    private static final double PREFERRED_WIDTH = 520.0;
    /** 页眉高度（图标盘 34 + 上下留白），内容区从它下面开始。 */
    private static final double HEADER_HEIGHT = 62.0;
    /** 页脚高度：一条分隔线 + 一行说明 / 两颗按钮。 */
    private static final double FOOTER_HEIGHT = 48.0;
    /** 卡片之间的间隙，同时是滚动区的 {@code spacing}。 */
    private static final double CARD_GAP = 9.0;
    /** 卡片文字到卡片边缘的内缩。 */
    private static final double INNER_PAD = 13.0;
    private static final double CARD_TOP_PAD = 11.0;
    private static final double CARD_BOTTOM_PAD = 11.0;
    /** 标题那一行的高度（18 号标题与 19 高的类型胶囊取高的那个再补一点）。 */
    private static final double TITLE_ROW = 22.0;
    /** 「展开全文 / 收起」与链接共用的那一行。 */
    private static final double META_ROW = 16.0;
    /** 折叠态显示几行正文。三条足够判断这条与自己有没有关系。 */
    private static final int FOLDED_LINES = 3;
    /**
     * 单条公告最多排多少行，超出在最后一行截断。
     *
     * <p>整页能滚，所以这不是防溢出而是防「一条公告写了两千字」：真需要那么长应该发一篇文档
     * 再给个链接，公告卡片是给人扫一眼的。</p>
     */
    private static final int MAX_LINES = 14;
    /** 未读圆点占掉的横向空间，已读的条目同样让出来 —— 一列标题左边缘对齐比省那几个像素重要。 */
    private static final double DOT_SLOT = 9.0;
    /** 正文里的显式换行 = 分段。段内折行由 {@code fitWidth} 负责，它不认换行符。 */
    private static final String PARAGRAPH_BREAK = "\n";
    /** 入场错峰最多排到第几张（见 {@link #buildCard}）。 */
    private static final int MAX_REVEAL_SLOT = 8;
    /** 页脚那两颗按钮合计要占掉的宽度，说明文字据此让位。 */
    private static final double FOOTER_BUTTONS = 150.0;

    // ──────────────────────────── 文案 ────────────────────────────

    private static final String HEADING = "MuoniumPlayer 公告";
    private static final String CLOSE = "关闭";
    private static final String REFRESH = "刷新";
    private static final String EXPAND = "展开全文";
    private static final String COLLAPSE = "收起";
    private static final String DEFAULT_HINT = "滚轮翻页 · 点卡片展开或收起正文";
    private static final String LOADING = "正在获取公告喵…";
    private static final String NOTHING = "暂时没有公告喵。";
    private static final String NOTHING_HINT = "有新公告时这里会列出，主菜单上也会弹一次。";

    // ──────────────────────────── 配色 ────────────────────────────

    /** {@code fix} 用的蓝。与更新提示的信息蓝同族，但垫在卡片上要亮一档才读得清。 */
    private static final int FIX_COLOR = 0x7FB2E5;

    // ──────────────────────────── 状态 ────────────────────────────

    private final List<CardView> cards = new ArrayList<CardView>();
    /** 折行结果，键是「id @ 取整宽度」。折行要逐字累加字形宽度，不能每帧每条都重算。 */
    private final Map<String, String[]> lineCache = new HashMap<String, String[]>();
    /** 用户手动点过的折叠状态；没点过的按「未读展开、已读折叠」定。 */
    private final Map<String, Boolean> foldOverride = new HashMap<String, Boolean>();

    private ScrollPanel feed;
    private Panel body;
    private Panel emptyHint;
    /** 所有卡片高度之和（不含间隙），弹窗的偏好高度由它算。 */
    private double stackedHeight;
    private int visibleCount;

    private String status;
    /** 点了「刷新」正在等结论，用来在拉完那一刻把结果写进 {@link #status}。 */
    private boolean refreshRequested;

    @Override
    public void onInit() {
        // 测量挂在根面板上：它先于弹窗与滚动区的回调执行，卡片高度才能在本帧就参与排版。
        setBeforeRenderCallback(this::measure);

        final Panel dialog = sheet(PREFERRED_WIDTH, this::preferredHeight);
        header(dialog, PartyIcons.NOTE, HEADING, this::subtitleText, this::accent);

        body = body(dialog, () -> HEADER_HEIGHT, () -> FOOTER_HEIGHT);
        feed = feed(body);
        feed.setSpacing(CARD_GAP);
        feed.setScrollStrength(30);

        buildEmptyState();
        footer(dialog);
    }

    // ──────────────────────────── 收起 ────────────────────────────

    /**
     * 关掉公告窗 = 把这一批全部记为已读。
     *
     * <p>所有出口都走这里：关闭按钮、ESC、点遮罩（{@code NcmSheet} 的遮罩点击直接调
     * {@code dispose()}）。从入口点进去看也算读过 —— 「读过」如果只由自动弹那一次算，
     * 用户主动看过一遍的东西下次启动还要再被弹一次，那是在惩罚认真阅读的人。</p>
     */
    @Override
    public void dispose() {
        AnnouncementChecker.markAllRead();
        closing = true;
    }

    /** 宿主的 ESC 转发到这里。 */
    public void handleEscape() {
        dispose();
    }

    // ──────────────────────────── 测量与排版 ────────────────────────────

    /**
     * 每帧一次：按当前宽度把每条公告折行、算出每张卡的高度，并处理空态与刷新回执。
     *
     * <p>公告是守护线程拉到什么就是什么，这一帧之前列表可能还是空的，所以每次都要重新问一遍。
     * 折行结果按「id + 宽度」缓存（{@link #lineCache}），所以窗口没变时这里只剩加减法。</p>
     */
    private void measure() {
        pollRefresh();

        List<Announcement> list = AnnouncementChecker.all();
        double width = contentWidth();
        // 字形没就绪时量出来的宽度全是 0，那种结果不许进缓存 —— 见 fullLinesOf。
        boolean glyphs = FontManager.pf14 != null;
        ensureCards(list.size());
        double textWidth = Math.max(1.0, width - (PAD + CARD_INSET) * 2 - INNER_PAD * 2);
        double total = 0.0;
        int shown = 0;
        for (int index = 0; index < cards.size(); index++) {
            CardView view = cards.get(index);
            Announcement data = index < list.size() ? list.get(index) : null;
            view.data = data;
            if (data == null) {
                // 用 setHidden 而不是留着：被隐藏的组件整帧跳过自己的回调，也不参与滚动区的求和。
                view.row.setHidden(true);
                continue;
            }
            shown++;
            view.row.setHidden(false);
            view.expanded = isExpanded(data);
            view.full = fullLinesOf(data, textWidth, glyphs);
            view.truncated = view.full.length > FOLDED_LINES;
            view.lines = view.expanded || !view.truncated
                    ? view.full
                    : foldOf(view.full, textWidth);
            view.hasLink = !data.link().isEmpty() && AnnouncementChecker.isLinkAllowed(data.link());
            boolean meta = view.truncated || view.hasLink;
            view.height = CARD_TOP_PAD + TITLE_ROW + view.lines.length * lineHeight()
                    + (meta ? META_ROW + 2.0 : 0.0) + CARD_BOTTOM_PAD;
            view.row.setBounds(width, view.height);
            ensureLines(view);
            // 被隐藏的组件不再执行自己的回调，所以「这一帧到底显不显示」只能在测量里统一说。
            view.unreadDot.setHidden(AnnouncementChecker.isRead(data.id()));
            view.linkLabel.setHidden(!view.hasLink);
            view.linkIcon.setHidden(!view.hasLink);
            view.foldLabel.setHidden(!view.truncated);
            total += view.height;
        }
        visibleCount = shown;
        stackedHeight = total + Math.max(0, shown - 1) * CARD_GAP;

        boolean empty = shown == 0;
        feed.setHidden(empty);
        emptyHint.setHidden(!empty);
        if (!empty) {
            return;
        }
        emptyHint.setBounds(Math.max(1.0, body.getWidth()), Math.max(1.0, body.getHeight()));
        emptyHint.setPosition(0, 0);
    }

    /** 建一张卡片上的正文行标签。按历史最大行数留着，不为每次展开都重建组件。 */
    private void ensureLines(CardView view) {
        while (view.lineLabels.size() < view.lines.length) {
            final int lineIndex = view.lineLabels.size();
            LabelWidget label = label(view.plate, () -> {
                if (view.data == null || lineIndex >= view.lines.length) {
                    return "";
                }
                return view.lines[lineIndex];
            }, FontManager.pf14);
            label.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            label.setBeforeRenderCallback(() -> {
                label.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                label.setMaxWidth(bodyWidth() - INNER_PAD * 2);
                label.setPosition(INNER_PAD,
                        CARD_TOP_PAD + TITLE_ROW + lineIndex * lineHeight());
            });
            view.lineLabels.add(label);
        }
        for (int index = 0; index < view.lineLabels.size(); index++) {
            view.lineLabels.get(index).setHidden(index >= view.lines.length);
        }
    }

    /** 「这条还没拉下来」与「拉下来但没有公告」是两种状态，得分开说。 */
    private void buildEmptyState() {
        emptyHint = new Panel();
        body.addChild(emptyHint);
        emptyHint.setClickable(false);

        final LabelWidget main = label(emptyHint,
                () -> AnnouncementChecker.isResolved() ? NOTHING : LOADING, FontManager.pf16bold);
        main.setBeforeRenderCallback(() -> {
            main.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            main.setPosition(Math.max(0.0, emptyHint.getWidth() * .5 - main.getWidth() * .5),
                    Math.max(0.0, emptyHint.getHeight() * .5 - 20.0));
        });

        final LabelWidget second = label(emptyHint,
                () -> AnnouncementChecker.isResolved() ? NOTHING_HINT : DEFAULT_HINT, FontManager.pf12);
        second.setBeforeRenderCallback(() -> {
            second.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            second.setPosition(Math.max(0.0, emptyHint.getWidth() * .5 - second.getWidth() * .5),
                    Math.max(0.0, emptyHint.getHeight() * .5 + 4.0));
        });
    }

    /** 「刷新」拉完之后给一行回执。列表本身是响应式的，不需要在这里重建任何东西。 */
    private void pollRefresh() {
        if (!refreshRequested || AnnouncementChecker.isFetching()) {
            return;
        }
        refreshRequested = false;
        int count = AnnouncementChecker.all().size();
        status = count == 0 ? "没有拉到公告喵…" : "已刷新，共 " + count + " 条。";
    }

    private String subtitleText() {
        int total = AnnouncementChecker.all().size();
        if (total == 0) {
            return AnnouncementChecker.isFetching() ? LOADING : "暂无公告，只影响公告不影响更新提示";
        }
        int unread = AnnouncementChecker.unreadCount();
        return "共 " + total + " 条" + (unread > 0 ? " · " + unread + " 条未读" : " · 都看过了")
                + " · 关掉之后不再自动弹";
    }

    /** 弹窗想要的高度：全部卡片摊平之后的高度。装不下时由 {@code NcmSheet#sheet} 夹到视口内。 */
    private double preferredHeight() {
        return HEADER_HEIGHT + stackedHeight + FOOTER_HEIGHT + 12.0;
    }

    /**
     * 弹窗宽度，与 {@code NcmSheet#sheet} 内部同一个算式。
     *
     * <p>之所以在这儿重算而不是读 {@code dialog.getWidth()}：测量必须早于弹窗定尺寸，
     * 而 {@code sheet()} 的回调里已经把这一句写死了。两处算式改了不一致，卡片就会按旧宽度折行、
     * 却按新宽度摆 —— 表现为右边整列文字被切。改成从 {@code NcmSheet} 暴露宽度是更大的动作，
     * 先在这里把约束写清楚。</p>
     */
    private double contentWidth() {
        return Math.max(1.0, Math.min(PREFERRED_WIDTH, getWidth() - 24.0));
    }

    /** 卡片底板宽度（比弹窗窄出 {@link #PAD} 与悬停生长预留的 {@link #CARD_INSET}）。 */
    private double cardWidth() {
        return Math.max(1.0, contentWidth() - (PAD + CARD_INSET) * 2);
    }

    /** 卡片内可用文字宽度。 */
    private double bodyWidth() {
        return Math.max(1.0, cardWidth() - INNER_PAD * 2);
    }

    private int accent() {
        return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
    }

    private static double lineHeight() {
        return (FontManager.pf14 == null ? 13.0 : FontManager.pf14.getFontHeight()) + 3.0;
    }

    private boolean isExpanded(Announcement announcement) {
        Boolean override = foldOverride.get(announcement.id());
        if (override != null) {
            return override.booleanValue();
        }
        // 未读的默认展开（既然弹给我看了就让我读完），已读的默认折叠（只看标题扫一遍列表）。
        return !AnnouncementChecker.isRead(announcement.id());
    }

    /** 这条公告完整展开时的全部行（超过 {@link #MAX_LINES} 的那一份会被截断并补省略号）。 */
    private String[] fullLinesOf(Announcement announcement, double textWidth, boolean glyphs) {
        String key = announcement.id() + '@' + Math.round(textWidth);
        String[] cached = lineCache.get(key);
        if (cached != null) {
            return cached;
        }
        String[] computed = wrap(announcement.body(), textWidth);
        if (glyphs) {
            if (lineCache.size() > 512) {
                lineCache.clear();
            }
            lineCache.put(key, computed);
        }
        return computed;
    }

    /** 折叠态取前 {@link #FOLDED_LINES} 行，并在最后一行补省略号 —— 少了几行要说得出来。 */
    private static String[] foldOf(String[] full, double textWidth) {
        String[] folded = new String[FOLDED_LINES];
        System.arraycopy(full, 0, folded, 0, FOLDED_LINES);
        folded[FOLDED_LINES - 1] = trimWithEllipsis(folded[FOLDED_LINES - 1], textWidth);
        return folded;
    }

    /**
     * 折行：先按 {@link #PARAGRAPH_BREAK} 切段，每段各自按宽度折，再拼成行表。
     *
     * <p>必须自己切 —— {@code fitWidth} 的断点表里只有空白与标点，换行符会被当成普通字符留在行里：
     * 文字既不会真的另起一行，行尾还多一份看不见的宽度。切段之后再套行数上限，顺序不能反。</p>
     */
    private static String[] wrap(String source, double textWidth) {
        List<String> lines = new ArrayList<String>();
        if (source == null || source.trim().isEmpty()) {
            return new String[0];
        }
        boolean overflow = false;
        for (String paragraph : source.split(PARAGRAPH_BREAK, -1)) {
            String text = paragraph.trim();
            if (text.isEmpty()) {
                continue;
            }
            String[] wrapped;
            try {
                wrapped = FontManager.pf14.fitWidth(text, textWidth);
            } catch (Throwable ignored) {
                // 换行只是排版，任何异常都不该让一个公告窗打不开。
                wrapped = new String[]{text};
            }
            if (wrapped == null || wrapped.length == 0) {
                wrapped = new String[]{text};
            }
            for (String line : wrapped) {
                if (lines.size() >= MAX_LINES) {
                    overflow = true;
                    break;
                }
                lines.add(line);
            }
            if (overflow) {
                break;
            }
        }
        if (lines.isEmpty()) {
            lines.add(source.trim());
        }
        String[] result = lines.toArray(new String[0]);
        if (overflow) {
            result[result.length - 1] = trimWithEllipsis(result[result.length - 1], textWidth);
        }
        return result;
    }

    private static String trimWithEllipsis(String line, double textWidth) {
        if (FontManager.pf14 == null) {
            return line;
        }
        try {
            return FontManager.pf14.trim(line, Math.max(1.0, textWidth - 8.0)) + "…";
        } catch (Throwable ignored) {
            return line;
        }
    }

    // ──────────────────────────── 卡片 ────────────────────────────

    private void ensureCards(int count) {
        int limit = Math.min(count, UpdateManifest.MAX_ANNOUNCEMENTS);
        while (cards.size() < limit) {
            cards.add(buildCard(cards.size()));
        }
    }

    /** 建一张卡片。数据每帧由 {@link #measure()} 喂，所以这一步只做一次。 */
    private CardView buildCard(final int index) {
        final CardView view = new CardView();
        view.row = new Panel();
        feed.addChild(view.row);

        // 超过 MAX_REVEAL_SLOT 就再也开不了闸（pageFade 只会渐近到 1），所以后面的卡片共用最后一档，
        // 一起出现 —— 宁可少一点错峰，也不能让第一条之后的整列永远透明。
        view.plate = card(view.row, Math.min(index, MAX_REVEAL_SLOT), () -> 0.0, () -> view.height,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), view.plate.hover));
        view.plate.setShouldOverrideMouseCursor(true);
        view.plate.setOnClickCallback((x, y, button) -> {
            if (button != 0 || view.data == null || !view.truncated) {
                return false;
            }
            // 只有正文真的多过折叠行数时才值得点：短公告点上去没反应会让人以为界面坏了。
            foldOverride.put(view.data.id(), !view.expanded);
            return true;
        });

        view.unreadDot = new RoundedRectWidget();
        view.plate.addChild(view.unreadDot);
        view.unreadDot.setClickable(false);
        view.unreadDot.setBeforeRenderCallback(() -> {
            double size = 5.0;
            view.unreadDot.setRadius(size * .5);
            view.unreadDot.setBounds(size, size);
            view.unreadDot.setPosition(INNER_PAD, CARD_TOP_PAD + TITLE_ROW * .5 - size * .5);
            view.unreadDot.setColor(accent());
        });

        // 类型胶囊要在标题之前加入：它自己算宽度，而标题的可用宽度按它的左边缘让位。
        view.kindPill = pill(view.plate, () -> kindGlyph(view), () -> kindLabel(view),
                () -> kindColor(view), () -> pillRightEdge(view), () -> CARD_TOP_PAD + TITLE_ROW * .5);

        view.date = label(view.plate, () -> view.data == null ? "" : view.data.date(), FontManager.pf12);
        view.date.setBeforeRenderCallback(() -> {
            view.date.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            view.date.setPosition(cardWidth() - INNER_PAD - view.date.getWidth(),
                    inkTop(view.date, CARD_TOP_PAD + TITLE_ROW * .5));
        });

        view.title = label(view.plate, () -> view.data == null ? "" : view.data.title(), FontManager.pf16bold);
        view.title.setBeforeRenderCallback(() -> {
            view.title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            view.title.setMaxWidth(Math.max(1.0, view.kindPill.getX() - view.plate.getX()
                    - INNER_PAD - DOT_SLOT - 8.0));
            view.title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            view.title.setPosition(INNER_PAD + DOT_SLOT, inkTop(view.title, CARD_TOP_PAD + TITLE_ROW * .5));
        });

        buildMetaRow(view);
        return view;
    }

    /** 元信息那一行：左边链接（可点），右边「展开全文 / 收起」。 */
    private void buildMetaRow(final CardView view) {
        view.linkIcon = icon(view.plate, PartyIcons.LINK, ICON_S,
                () -> INNER_PAD + ICON_S * .5, () -> metaCenterY(view), this::accent);
        view.linkLabel = label(view.plate,
                () -> view.data == null ? "" : AnnouncementChecker.linkText(view.data), FontManager.pf12);
        view.linkLabel.setClickable(true);
        view.linkLabel.setShouldOverrideMouseCursor(true);
        view.linkLabel.setBeforeRenderCallback(() -> {
            view.linkLabel.setColor(view.linkLabel.isHovering() ? accent()
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            view.linkLabel.setMaxWidth(Math.max(1.0, bodyWidth() - ICON_S - 5.0 - 64.0));
            view.linkLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            view.linkLabel.setPosition(INNER_PAD + ICON_S + 5.0, inkTop(view.linkLabel, metaCenterY(view)));
        });
        view.linkLabel.setOnClickCallback((x, y, button) -> {
            if (button != 0) {
                return false;
            }
            openLink(view.data);
            return true;
        });

        view.foldLabel = label(view.plate, () -> view.expanded ? COLLAPSE : EXPAND, FontManager.pf12bold);
        view.foldLabel.setBeforeRenderCallback(() -> {
            view.foldLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            view.foldLabel.setPosition(cardWidth() - INNER_PAD - view.foldLabel.getWidth(),
                    inkTop(view.foldLabel, metaCenterY(view)));
        });
    }

    /** 元信息那一行的垂直中心：它垫在正文下面，所以只有按「这一张卡」的行数才量得出来。 */
    private double metaCenterY(CardView view) {
        return CARD_TOP_PAD + TITLE_ROW + view.lines.length * lineHeight() + META_ROW * .5 + 2.0;
    }

    /**
     * 类型胶囊的右边界。
     *
     * <p>日期在最右、胶囊在它左边，两者都不定宽，所以这里按字体直接量一次日期宽度再让位 ——
     * 不能读日期标签自己的宽度：胶囊的回调排在它前面，那一帧它还没被定过尺寸。</p>
     */
    private double pillRightEdge(CardView view) {
        String date = view.data == null ? "" : view.data.date();
        double reserve = 0.0;
        if (!date.isEmpty() && FontManager.pf12 != null) {
            reserve = FontManager.pf12.getStringWidthD(date) + 8.0;
        }
        return cardWidth() - INNER_PAD - reserve;
    }

    // ──────────────────────────── 语气 ────────────────────────────

    private static String kindLabel(CardView view) {
        String kind = kindOf(view);
        if ("feature".equals(kind)) return "新功能";
        if ("fix".equals(kind)) return "修复";
        if ("event".equals(kind)) return "活动";
        if ("warning".equals(kind)) return "重要";
        return "公告";
    }

    private static String kindGlyph(CardView view) {
        String kind = kindOf(view);
        if ("feature".equals(kind)) return PartyIcons.ADD;
        if ("fix".equals(kind)) return PartyIcons.CHECK;
        if ("event".equals(kind)) return PartyIcons.TIMER;
        if ("warning".equals(kind)) return PartyIcons.WARNING;
        return PartyIcons.INFO;
    }

    private int kindColor(CardView view) {
        String kind = kindOf(view);
        if ("feature".equals(kind)) return OK_COLOR;
        if ("fix".equals(kind)) return FIX_COLOR;
        if ("event".equals(kind)) return VIP_COLOR;
        if ("warning".equals(kind)) return BAD_COLOR;
        return accent();
    }

    /** 统一转小写比较：清单里写 {@code Feature} 也该是同一个语气。 */
    private static String kindOf(CardView view) {
        Announcement data = view.data;
        return data == null ? "" : data.kind().toLowerCase(Locale.ROOT);
    }

    // ──────────────────────────── 页脚 ────────────────────────────

    private void footer(final Panel dialog) {
        divider(dialog, () -> dialog.getHeight() - FOOTER_HEIGHT + 6.0);

        final RoundedButtonWidget close = action(dialog, () -> PartyIcons.CLOSE, () -> CLOSE,
                FontManager.pf12bold, () -> 0xFFFFFF, this::dispose);
        close.setBeforeRenderCallback(() -> {
            double width = 68.0;
            close.setBounds(width, 26.0);
            close.setPosition(dialog.getWidth() - PAD - width, dialog.getHeight() - FOOTER_HEIGHT + 12.0);
            close.setColor(close.isHovering() ? mix(accent(), 0xFFFFFF, .18) : accent());
        });

        final RoundedButtonWidget refresh = action(dialog, () -> PartyIcons.SYNC, () -> REFRESH,
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                this::beginRefresh);
        refresh.setBeforeRenderCallback(() -> {
            double width = 68.0;
            refresh.setBounds(width, 26.0);
            refresh.setPosition(dialog.getWidth() - PAD - width - 76.0,
                    dialog.getHeight() - FOOTER_HEIGHT + 12.0);
            refresh.setColor(refresh.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        icon(dialog, () -> AnnouncementChecker.isFetching() ? PartyIcons.SYNC : PartyIcons.INFO,
                ICON_S, () -> PAD + ICON_S * .5, () -> dialog.getHeight() - FOOTER_HEIGHT + 25.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        final LabelWidget hint = label(dialog, this::footerText, FontManager.pf12);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 右边那两颗按钮占掉的宽度必须让开：让不开就是字压在按钮上，看着像界面坏了。
            hint.setMaxWidth(Math.max(1.0, dialog.getWidth() - PAD * 2 - ICON_S - 5.0 - FOOTER_BUTTONS));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(PAD + ICON_S + 5.0, inkTop(hint, dialog.getHeight() - FOOTER_HEIGHT + 25.0));
        });
    }

    private String footerText() {
        if (status != null && !status.isEmpty()) {
            return status;
        }
        if (visibleCount == 0) {
            return AnnouncementChecker.isFetching() ? LOADING : NOTHING_HINT;
        }
        return DEFAULT_HINT;
    }

    private void beginRefresh() {
        if (!AnnouncementChecker.refresh()) {
            status = "上一次获取还在跑，稍等一下喵…";
            return;
        }
        refreshRequested = true;
        status = "正在重新获取…";
    }

    /** 打开公告附带的链接；打不开浏览器就把地址放进剪贴板并说明。 */
    private void openLink(Announcement announcement) {
        if (announcement == null) {
            return;
        }
        String link = announcement.link();
        if (!AnnouncementChecker.isLinkAllowed(link)) {
            return;
        }
        if (BrowserUtils.open(link)) {
            status = "已在浏览器里打开喵。";
            return;
        }
        KeyboardUtils.setClipboardString(link);
        status = "打不开浏览器喵…地址已复制到剪贴板：" + link;
    }

    /** 一张卡片用到的组件。全部由 {@link #measure()} 每帧喂数据，所以没有「重建」这一步。 */
    private static final class CardView {

        private Panel row;
        private Card plate;
        private RoundedRectWidget unreadDot;
        private RoundedRectWidget kindPill;
        private LabelWidget title;
        private LabelWidget date;
        private LabelWidget linkIcon;
        private LabelWidget linkLabel;
        private LabelWidget foldLabel;
        private final List<LabelWidget> lineLabels = new ArrayList<LabelWidget>();

        /** 本帧绑到这张卡上的公告；{@code null} 表示这一格不显示。 */
        private Announcement data;
        private String[] lines = new String[0];
        private String[] full = new String[0];
        private double height;
        private boolean expanded;
        private boolean truncated;
        private boolean hasLink;
    }
}
