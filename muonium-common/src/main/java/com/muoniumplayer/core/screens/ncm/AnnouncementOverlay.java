package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.update.Announcement;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.update.AnnouncementImageCache;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 远端公告、赞助与交流群共用一个 Expressive 内容中心，宿主只负责尺寸与输入。 */
public final class AnnouncementOverlay extends NCMPanel {
    private static final double WIDTH = 960;
    private static final double HEIGHT = 720;
    private static final double DESKTOP_ASPECT = 4.0 / 3.0;
    private static final double INSET = 28;
    private static final double CONTENT_TOP = 178;
    private static final double FOOTER = 76;
    private static final double CARD_PAD = 22;
    private static final int FOLDED_LINES = 3;
    private static final String[] TABS = {"公告", "赞助", "交流群"};
    private static final String[] GLYPHS = {PartyIcons.CHANNEL, PartyIcons.VIP, PartyIcons.GROUP};
    private static final String[] TITLES = {"好音乐，也有新消息", "让热爱，继续发声", "音乐之外，还有同好"};
    private static final String[] INTRO = {
            "更新、活动与重要提醒，都在这里。",
            "每一份支持，都是让项目继续前进的力量。",
            "交流使用体验、分享歌单，一起把播放器变得更好。"
    };

    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final SheetClock clock = SheetClock.cascade();
    private final AnnouncementMotion motion = new AnnouncementMotion();
    private final NcmPromptMotion tabSlide = new NcmPromptMotion(0);
    private final AnnouncementImageCache images = new AnnouncementImageCache();
    private final Map<String, Boolean> expanded = new HashMap<>();
    private final List<CardView> cards = new ArrayList<>();
    private final List<Panel> tabHits = new ArrayList<>();
    private final double[] scrollMemory = new double[3];

    private AnnouncementChecker.Snapshot snapshot;
    private List<Announcement> entries = List.of();
    private Panel dialog;
    private Panel tabs;
    private Panel scrollRoot;
    private Panel content;
    private Panel hero;
    private Panel empty;
    private ScrollPanel viewport;
    private AbstractWidget<?> loading;
    private AbstractWidget<?> progress;
    private double presentation;
    private boolean closing;
    private boolean released;
    private boolean refreshRequested;
    private String status = "";
    private double slide;
    private long contentArrived;

    @Override
    protected boolean isDismissing() { return closing; }

    public boolean shouldClose() { return clock.isGone(); }

    /** 保留关闭整批已读；图片仍要画退场，直到宿主真正移除才释放。 */
    public void dispose() {
        if (closing) return;
        AnnouncementChecker.markAllRead();
        closing = true;
        clock.requestDismiss();
    }

    public void releaseResources() {
        if (released) return;
        released = true;
        images.close();
    }

    public void handleEscape() { dispose(); }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        layout.render(this, mouseX, mouseY, dWheel, (x, y, input) -> {
            super.renderWidget(x, y, 0);
            if (!closing && motion.acceptsInput() && input != 0) viewport.forwardWheel(input);
        });
    }

    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int button) {
        layout.dispatch(this, mouseX, mouseY, button, (x, y, input) -> super.onMouseClickReceived(x, y, input));
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        if (closing || !motion.acceptsInput()) return;
        viewport.forwardWheel(dWheel);
    }

    @Override
    public void onInit() {
        setBeforeRenderCallback(() -> {
            presentation = TransitionCurveCache.MOTION.at(clock.tick());
            slide = tabSlide.update(motion.target(), 55 / SheetClock.speed(), System.nanoTime());
        });
        dialog = NcmExpressive.shell(this, WIDTH, () -> HEIGHT, () -> presentation, this::dispose);
        dialog.setBeforeRenderCallback(this::measure);
        buildHeader();
        buildTabs();

        viewport = new ScrollPanel();
        dialog.addChild(viewport);
        viewport.setAlignment(ScrollPanel.Alignment.VERTICAL).setSpacing(0).setScrollStrength(52)
                .setIndicatorInsideViewport().setIndexedVerticalLayout();
        scrollRoot = new Panel();
        viewport.addChild(scrollRoot);
        scrollRoot.setClickable(false);
        content = new Panel() {
            @Override protected boolean shouldClickChildren(double x, double y) {
                return !closing && motion.acceptsInput() && insideViewport(x, y);
            }
            @Override protected boolean shouldRenderChildren(AbstractWidget<?> child, double x, double y) {
                return child.getY() + child.getHeight() >= viewport.getY()
                        && child.getY() <= viewport.getY() + viewport.getHeight();
            }
        };
        scrollRoot.addChild(content);
        content.setClickable(false);
        buildHero();
        buildEmpty();
        buildFooter();
    }

    private void buildHeader() {
        NcmExpressive.label(dialog, () -> "MUONIUMPLAYER / 内容中心", NcmExpressive::captionFont,
                () -> INSET, () -> 28, () -> dialog.getWidth() - INSET * 2 - 64,
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(dialog, () -> "公告中心", NcmExpressive::titleFont,
                () -> INSET, () -> 65, () -> dialog.getWidth() - INSET * 2 - 180,
                () -> NcmExpressivePalette.live().text());
        NcmExpressive.rightAligned(dialog, this::unreadCaption, NcmExpressive::captionFont,
                () -> dialog.getWidth() - INSET - 64, () -> 65, () -> 148,
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.hitSurface(dialog, () -> dialog.getWidth() - INSET - 44, () -> 22,
                () -> 44, () -> 44, 22, () -> NcmExpressivePalette.live().container(), this::dispose);
        NcmExpressive.icon(dialog, PartyIcons.CLOSE, NcmSheet.ICON_M,
                () -> dialog.getWidth() - INSET - 22, () -> 44,
                () -> NcmExpressivePalette.live().muted());
    }

    private void buildTabs() {
        tabs = new Panel();
        dialog.addChild(tabs);
        tabs.setClickable(false);
        NcmExpressive.surface(tabs, 0, 0, tabs::getWidth, tabs::getHeight, 28,
                () -> NcmExpressivePalette.live().container());
        NcmExpressive.surface(tabs, () -> 5 + slide * tabWidth(), () -> 5,
                this::tabWidth, () -> 46, 23, () -> NcmExpressivePalette.live().primary());
        for (int index = 0; index < TABS.length; index++) {
            final int tab = index;
            Panel hit = new Panel();
            tabs.addChild(hit);
            tabHits.add(hit);
            hit.setShouldOverrideMouseCursor(true).setBeforeRenderCallback(() -> {
                hit.setBounds(tabWidth(), 46);
                hit.setPosition(5 + tab * tabWidth(), 5);
            });
            hit.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                selectSection(tab);
                return true;
            });
            NcmExpressive.icon(hit, GLYPHS[tab], NcmSheet.ICON_M,
                    () -> tabWidth() * .5 - 30, () -> 23, () -> tabInk(tab));
            NcmExpressive.label(hit, () -> TABS[tab], NcmExpressive::labelFont,
                    () -> tabWidth() * .5 - 10, () -> 23, () -> Math.max(1, tabWidth() * .5),
                    () -> tabInk(tab));
        }
    }

    private int tabInk(int tab) {
        NcmExpressivePalette p = NcmExpressivePalette.live();
        return NcmExpressivePalette.mix(p.muted(), p.onPrimary(), Math.max(0, 1 - Math.abs(slide - tab)));
    }

    private double tabWidth() { return Math.max(1, (tabs.getWidth() - 10) / 3); }

    private void buildHero() {
        hero = new Panel();
        content.addChild(hero);
        hero.setClickable(false);
        NcmExpressive.surface(hero, 0, 0, hero::getWidth, hero::getHeight, 26,
                () -> NcmExpressivePalette.live().primaryContainer());
        NcmExpressive.label(hero, () -> sectionEyebrow(), NcmExpressive::captionFont,
                () -> 24, () -> 26, () -> heroTextWidth(), () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(hero, () -> TITLES[motion.visible()], NcmExpressive::dialogTitleFont,
                () -> 24, () -> 64, this::heroTextWidth, () -> NcmExpressivePalette.live().text());
        NcmExpressive.label(hero, () -> INTRO[motion.visible()], NcmExpressive::captionFont,
                () -> 24, () -> 101, this::heroTextWidth, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.medallion(hero, PartyIcons.HEADPHONES, () -> hero.getWidth() - 70,
                () -> 68, () -> hero.getWidth() < 560 ? 0 : 84,
                () -> NcmExpressivePalette.live().primary(), () -> NcmExpressivePalette.live().onPrimary());
    }

    private double heroTextWidth() { return Math.max(1, hero.getWidth() - (hero.getWidth() < 560 ? 48 : 158)); }

    private String sectionEyebrow() {
        if (motion.visible() == 1) return "SUPPORT / 赞助专栏";
        if (motion.visible() == 2) return "COMMUNITY / 交流群专栏";
        return "WHAT’S NEW / 公告专栏";
    }

    private void buildEmpty() {
        empty = new Panel();
        content.addChild(empty);
        empty.setClickable(false);
        loading = NcmExpressive.loading(empty, () -> empty.getWidth() * .5, () -> 78,
                () -> 58, () -> NcmExpressivePalette.live().primary());
        NcmExpressive.medallion(empty, PartyIcons.NOTE, () -> empty.getWidth() * .5, () -> 78,
                () -> AnnouncementChecker.isFetching() ? 0 : 58,
                () -> NcmExpressivePalette.live().primaryContainer(), () -> NcmExpressivePalette.live().primary());
        NcmExpressive.centered(empty, this::emptyTitle, NcmExpressive::labelFont,
                () -> empty.getWidth() * .5, () -> 136, () -> empty.getWidth() - 32,
                () -> NcmExpressivePalette.live().text());
        NcmExpressive.centered(empty, () -> AnnouncementChecker.isFetching()
                        ? "正在同步远端内容，请稍候…" : "内容发布后会自动出现在这里。",
                NcmExpressive::captionFont, () -> empty.getWidth() * .5, () -> 169,
                () -> empty.getWidth() - 32, () -> NcmExpressivePalette.live().muted());
        for (int index = 0; index < 2; index++) {
            final int row = index;
            NcmExpressive.surface(empty, () -> 16, () -> 215 + row * 65,
                    () -> empty.getWidth() - 32, () -> 48, 18, () -> NcmExpressivePalette.live().container());
        }
    }

    private String emptyTitle() {
        if (AnnouncementChecker.isFetching()) return "好消息，正在路上";
        return motion.visible() == 0 ? "暂时没有新公告" : motion.visible() == 1 ? "赞助专栏待更新" : "交流群专栏待更新";
    }

    private void buildFooter() {
        NcmExpressive.rule(dialog, () -> INSET, () -> dialog.getHeight() - FOOTER,
                () -> dialog.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().outline());
        NcmExpressive.label(dialog, this::footerText, NcmExpressive::captionFont,
                () -> INSET, () -> dialog.getHeight() - 40,
                () -> Math.max(1, dialog.getWidth() - INSET * 2 - 260),
                () -> NcmExpressivePalette.live().muted()).setTooltip(this::footerText);
        NcmExpressive.action(dialog, PartyIcons.SYNC, () -> AnnouncementChecker.isFetching() ? "同步中" : "刷新",
                NcmExpressive.ActionStyle.TONAL, () -> dialog.getWidth() - INSET - 236,
                () -> dialog.getHeight() - 62, () -> 112, () -> 48, this::beginRefresh);
        NcmExpressive.action(dialog, PartyIcons.CHECK, () -> "看完了", NcmExpressive.ActionStyle.PRIMARY,
                () -> dialog.getWidth() - INSET - 112, () -> dialog.getHeight() - 62,
                () -> 112, () -> 48, this::dispose);
        progress = NcmExpressive.progress(dialog, () -> INSET, () -> dialog.getHeight() - 14,
                () -> dialog.getWidth() - INSET * 2, () -> -1, AnnouncementChecker::isFetching);
    }

    /** 所有尺寸在 ScrollPanel 排版之前提交；异步内容用不可变快照一次取齐。 */
    private void measure() {
        long now = System.nanoTime();
        double maxWidth = Math.max(1, getWidth() - 40);
        double maxHeight = Math.max(1, getHeight() - 32);
        double aspect = Math.min(DESKTOP_ASPECT, maxWidth / maxHeight);
        double width = Math.max(1, Math.min(WIDTH, Math.min(maxWidth, maxHeight * aspect)));
        double height = Math.max(1, Math.min(HEIGHT, Math.min(maxHeight, width / aspect)));
        dialog.setBounds(width, height);
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1 - presentation) * 18);
        dialog.setAlpha((float) presentation);
        tabs.setBounds(contentWidth(), 56);
        tabs.setPosition(INSET, 103);
        viewport.setBounds(contentWidth(), Math.max(1, dialog.getHeight() - CONTENT_TOP - FOOTER - 12));
        viewport.setPosition(INSET, CONTENT_TOP);

        boolean switched = motion.tick(now, SheetClock.speed());
        AnnouncementChecker.Snapshot current = AnnouncementChecker.snapshot();
        if (switched || snapshot != current) {
            snapshot = current;
            entries = entriesFor(motion.visible());
            contentArrived = now;
            if (switched) viewport.targetScrollOffset = viewport.actualScrollOffset = scrollMemory[motion.visible()];
        }
        content.setAlpha((float) motion.alpha());
        content.setPosition(motion.offset(), 0);
        content.setClickable(motion.acceptsInput());
        hero.setBounds(contentWidth() - 8, 134);
        hero.setPosition(0, 0);
        ensureCards(entries.size());
        double y = 150;
        boolean changed = switched;
        for (int i = 0; i < cards.size(); i++) {
            CardView view = cards.get(i);
            boolean shown = i < entries.size();
            if (view.row.isHidden() == shown) changed = true;
            view.row.setHidden(!shown);
            if (!shown) continue;
            double oldHeight = view.row.getHeight();
            measureCard(view, entries.get(i), now);
            view.row.setPosition(0, y);
            double reveal = NcmPromptMotion.reveal(contentArrived + Math.min(i, 5) * 25_000_000L,
                    now, 520 / SheetClock.speed());
            view.row.setAlpha((float) reveal);
            view.plate.setPosition(0, (1 - reveal) * 10);
            y += view.row.getHeight() + 14;
            changed |= oldHeight != view.row.getHeight();
        }
        boolean isEmpty = entries.isEmpty();
        empty.setHidden(!isEmpty);
        empty.setBounds(contentWidth() - 8, 340);
        empty.setPosition(0, 150);
        loading.setHidden(!AnnouncementChecker.isFetching());
        if (isEmpty) y += 340;
        double contentHeight = y + 4;
        changed |= content.getWidth() != contentWidth() || content.getHeight() != contentHeight;
        content.setBounds(contentWidth(), contentHeight);
        scrollRoot.setBounds(contentWidth(), contentHeight);
        if (changed) viewport.invalidateLayout();
        progress.setHidden(!AnnouncementChecker.isFetching());
        if (refreshRequested && !AnnouncementChecker.isFetching()) {
            refreshRequested = false;
            status = "当前内容 · " + snapshot.announcements().size() + " 条公告 / "
                    + snapshot.sponsorships().size() + " 项赞助 / " + snapshot.communities().size() + " 个交流群";
        }
    }

    private List<Announcement> entriesFor(int section) {
        return section == 1 ? snapshot.sponsorships() : section == 2 ? snapshot.communities() : snapshot.announcements();
    }

    private boolean insideViewport(double x, double y) {
        return x >= viewport.getX() && x <= viewport.getX() + viewport.getWidth()
                && y >= viewport.getY() && y <= viewport.getY() + viewport.getHeight();
    }

    private double contentWidth() { return Math.max(1, dialog.getWidth() - INSET * 2); }

    private void selectSection(int section) {
        if (closing || section == motion.target()) return;
        scrollMemory[motion.visible()] = viewport.targetScrollOffset;
        motion.select(section);
        status = "";
    }

    private String unreadCaption() {
        int unread = AnnouncementChecker.unreadCount();
        return unread == 0 ? "都看过了" : unread + " 条未读";
    }

    private String footerText() {
        if (!status.isEmpty()) return status;
        return AnnouncementChecker.isFetching() ? "正在同步远端内容…" : "远端更新 · 滚动浏览";
    }

    private void beginRefresh() {
        if (AnnouncementChecker.refresh()) {
            refreshRequested = true;
            status = "";
        }
    }

    private void ensureCards(int count) {
        while (cards.size() < count) cards.add(buildCard());
    }

    private CardView buildCard() {
        CardView view = new CardView();
        view.row = new Panel();
        content.addChild(view.row);
        view.row.setClickable(false);
        view.plate = new Panel() {
            @Override protected boolean shouldClickChildren(double x, double y) {
                return !closing && motion.acceptsInput() && testHovered(x, y) && insideViewport(x, y);
            }
            @Override protected void beforeRenderChildren(double x, double y) {
                ScissorClipManager.begin(getX(), getY(), getWidth(), getHeight());
            }
            @Override protected void afterRenderChildren(double x, double y) { ScissorClipManager.end(); }
        };
        view.row.addChild(view.plate);
        view.plate.setShouldOverrideMouseCursor(true).setOnClickCallback((x, y, button) -> {
            if (button != 0 || !motion.acceptsInput() || view.data == null || view.full.length <= FOLDED_LINES) return true;
            expanded.put(cardKey(view.data), !view.expanded);
            return true;
        });
        NcmExpressive.surface(view.plate, 0, 0, view.plate::getWidth, view.plate::getHeight, 22,
                () -> NcmExpressivePalette.live().container());
        view.textRail = NcmExpressive.surface(view.plate, () -> 0, () -> 18, () -> 5,
                () -> Math.max(1, view.plate.getHeight() - 36), 2,
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.medallion(view.plate, PartyIcons.NOTE, () -> 39, () -> 35, () -> 34,
                () -> NcmExpressivePalette.live().primaryContainer(), () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(view.plate, () -> cardMeta(view), NcmExpressive::captionFont,
                () -> 65, () -> 35, () -> view.plate.getWidth() - 170,
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.rightAligned(view.plate, () -> view.data == null ? "" : view.data.date(),
                NcmExpressive::captionFont, () -> view.plate.getWidth() - CARD_PAD, () -> 35,
                () -> 106, () -> NcmExpressivePalette.live().muted());
        view.title = NcmExpressive.label(view.plate, () -> view.data == null ? "" : view.data.title(),
                NcmExpressive::dialogTitleFont, () -> CARD_PAD, () -> 77,
                () -> view.textWidth, () -> NcmExpressivePalette.live().containerInk());
        view.title.setTooltip(() -> view.data == null ? "" : view.data.title());
        view.art = new Panel() {
            @Override public void onRender(double x, double y) {
                if (view.data == null || getWidth() <= 1) return;
                String url = view.data.image();
                images.request(url);
                AnnouncementImageCache.State state = images.state(url);
                view.imageState = state;
                if (state == AnnouncementImageCache.State.READY && view.imageArrived == 0) {
                    view.imageArrived = System.nanoTime();
                }
                view.imageLoading.setHidden(state != AnnouncementImageCache.State.LOADING);
                view.imageHint.setHidden(state == AnnouncementImageCache.State.READY);
            }
        };
        view.plate.addChild(view.art);
        view.art.setClickable(false);
        NcmExpressive.surface(view.art, 0, 0, view.art::getWidth, view.art::getHeight, 18,
                () -> NcmExpressivePalette.live().input());
        // 背板先画，图片后画；否则子组件的填色会把异步图片盖住。
        Panel imageInk = new Panel() {
            @Override public void onRender(double x, double y) {
                if (view.imageState != AnnouncementImageCache.State.READY || view.data == null) return;
                float alpha = (float) NcmPromptMotion.reveal(view.imageArrived, System.nanoTime(), 360);
                images.draw(view.data.image(), SkijaGfx.canvas(), Rect.makeXYWH((float) getX(), (float) getY(),
                        (float) getWidth(), (float) getHeight()), 18, "contain".equals(view.data.imageFit()), getAlpha() * alpha);
            }
        };
        view.art.addChild(imageInk);
        imageInk.setClickable(false).setBeforeRenderCallback(() -> imageInk.setBounds(0, 0, view.art.getWidth(), view.art.getHeight()));
        view.imageLoading = NcmExpressive.loading(view.art, () -> view.art.getWidth() * .5,
                () -> view.art.getHeight() * .5 - 12, () -> 36, () -> NcmExpressivePalette.live().primary());
        view.imageHint = NcmExpressive.centered(view.art, () -> view.imageState == AnnouncementImageCache.State.FAILED
                        ? "图片暂不可用 · 文字和链接仍可使用" : "正在加载图片…",
                NcmExpressive::captionFont, () -> view.art.getWidth() * .5, () -> view.art.getHeight() * .5 + 32,
                () -> view.art.getWidth() - 32, () -> NcmExpressivePalette.live().muted());
        for (int index = 0; index < 3; index++) {
            final int action = index;
            RoundedButtonWidget button = NcmExpressive.action(view.plate, PartyIcons.LINK,
                    () -> action < view.actions.size() ? view.actions.get(action).text() : "",
                    action == 0 ? NcmExpressive.ActionStyle.PRIMARY : NcmExpressive.ActionStyle.TONAL,
                    () -> CARD_PAD + (view.stackActions ? 0 : action * (view.actionWidth + 8)),
                    () -> view.actionsTop + (view.stackActions ? action * 52 : 0),
                    () -> view.actionWidth, () -> 44,
                    () -> { if (action < view.actions.size()) openLink(view.actions.get(action).url()); });
            view.buttons.add(button);
        }
        view.fold = NcmExpressive.hitSurface(view.plate,
                () -> view.stackActions ? CARD_PAD : view.plate.getWidth() - CARD_PAD - 118,
                () -> view.actionsTop + (view.stackActions ? view.actions.size() * 52 : 0),
                () -> view.stackActions ? view.textWidth : 118, () -> 44, 22,
                () -> NcmExpressivePalette.live().input(), () -> {
                    if (view.data != null) expanded.put(cardKey(view.data), !view.expanded);
                });
        NcmExpressive.centered(view.fold, () -> view.expanded ? "收起正文" : "展开全文",
                NcmExpressive::captionFont, () -> view.stackActions ? view.textWidth * .5 : 59,
                () -> 22, () -> view.stackActions ? view.textWidth - 24 : 102,
                () -> NcmExpressivePalette.live().muted());
        return view;
    }

    private void measureCard(CardView view, Announcement data, long now) {
        if (view.data != data) {
            view.data = data;
            view.actions = actionsFor(data);
            view.imageArrived = 0;
            view.imageState = AnnouncementImageCache.State.EMPTY;
        }
        view.textWidth = Math.max(1, contentWidth() - 8 - CARD_PAD * 2);
        CFontRenderer font = NcmExpressive.bodyFont();
        long revision = font == null ? -1 : font.metricsRevision();
        boolean ready = font != null && font.areGlyphsLoaded(data.body());
        boolean wrapped = !ready || font != view.font || revision != view.revision || !data.body().equals(view.source)
                || view.wrappedWidth != view.textWidth;
        if (wrapped) {
            view.full = data.body().isEmpty() ? new String[0] : NcmExpressive.wrap(font, data.body(), view.textWidth);
            view.font = font;
            view.revision = revision;
            view.source = data.body();
            view.wrappedWidth = view.textWidth;
        }
        view.expanded = expanded.getOrDefault(cardKey(data), motion.visible() != 0 || !AnnouncementChecker.isRead(data.id()));
        int count = view.expanded ? view.full.length : Math.min(FOLDED_LINES, view.full.length);
        if (wrapped || view.lines.length != count) {
            view.lines = new String[count];
            System.arraycopy(view.full, 0, view.lines, 0, count);
            if (count < view.full.length && count > 0 && font != null) {
                view.lines[count - 1] = font.trim(view.lines[count - 1],
                        Math.max(1, view.textWidth - font.getStringWidthD("…"))) + "…";
            }
        }
        boolean art = !data.image().isEmpty() && AnnouncementChecker.isLinkAllowed(data.image());
        view.hasImage = art;
        view.art.setHidden(!art);
        view.textRail.setHidden(art);
        double artHeight = art ? ("contain".equals(data.imageFit())
                ? Math.max(180, Math.min(280, view.textWidth * .38))
                : Math.max(156, Math.min(256, view.textWidth * .34))) : 0;
        view.art.setBounds(CARD_PAD, 103, view.textWidth, artHeight);
        view.art.setTooltip(data.imageAlt());
        view.bodyTop = art ? 105 + artHeight + 18 : 110;
        boolean fold = view.full.length > FOLDED_LINES;
        view.fold.setHidden(!fold);
        view.actionsTop = view.bodyTop + count * lineHeight() + 14;
        double actionArea = view.textWidth - (fold ? 130 : 0);
        view.stackActions = actionArea < view.actions.size() * 104;
        int actionRows = view.stackActions ? view.actions.size() + (fold ? 1 : 0)
                : fold || !view.actions.isEmpty() ? 1 : 0;
        double targetHeight = view.actionsTop + Math.max(0, actionRows * 52 - 8) + CARD_PAD;
        if (view.height == null) view.height = new NcmPromptMotion(targetHeight);
        double height = view.height.update(targetHeight, 38 / SheetClock.speed(), now);
        view.row.setBounds(contentWidth() - 8, height);
        view.plate.setBounds(contentWidth() - 8, height);
        view.actionWidth = view.stackActions ? view.textWidth
                : Math.max(1, Math.min(196, (actionArea - Math.max(0, view.actions.size() - 1) * 8)
                / Math.max(1, view.actions.size())));
        for (int i = 0; i < view.buttons.size(); i++) view.buttons.get(i).setHidden(i >= view.actions.size());
        ensureLines(view, count);
    }

    private void ensureLines(CardView view, int count) {
        while (view.labels.size() < count) {
            int index = view.labels.size();
            LabelWidget label = NcmExpressive.label(view.plate, () -> index < view.lines.length ? view.lines[index] : "",
                    NcmExpressive::bodyFont, () -> CARD_PAD, () -> view.bodyTop + (index + .5) * lineHeight(),
                    () -> view.textWidth, () -> NcmExpressivePalette.live().containerInk());
            view.labels.add(label);
        }
        for (int i = 0; i < view.labels.size(); i++) view.labels.get(i).setHidden(i >= count);
    }

    private static double lineHeight() {
        CFontRenderer font = NcmExpressive.bodyFont();
        return Math.max(22, (font == null ? 16 : font.getFontHeight()) + 8);
    }

    private String cardKey(Announcement data) { return motion.visible() + ":" + data.id(); }

    private String cardMeta(CardView view) {
        if (view.data == null) return "";
        if (!view.data.badge().isEmpty()) return view.data.badge();
        if (motion.visible() == 1) return "赞助项目";
        if (motion.visible() == 2) return "加入交流";
        String kind = view.data.kind().toLowerCase(Locale.ROOT);
        String label = "feature".equals(kind) ? "新功能" : "fix".equals(kind) ? "修复"
                : "warning".equals(kind) ? "重要提醒" : "event".equals(kind) ? "活动" : "公告";
        return label + (AnnouncementChecker.isRead(view.data.id()) ? "" : " · 未读");
    }

    private static List<Announcement.Action> actionsFor(Announcement data) {
        List<Announcement.Action> actions = new ArrayList<>();
        if (AnnouncementChecker.isLinkAllowed(data.link())) {
            actions.add(new Announcement.Action(AnnouncementChecker.linkText(data), data.link()));
        }
        for (Announcement.Action action : data.actions()) {
            if (actions.size() >= 3) break;
            if (!AnnouncementChecker.isLinkAllowed(action.url())) continue;
            boolean duplicate = false;
            for (Announcement.Action prior : actions) if (prior.url().equals(action.url())) duplicate = true;
            if (!duplicate) actions.add(action);
        }
        return actions;
    }

    private void openLink(String link) {
        if (closing || !motion.acceptsInput() || !AnnouncementChecker.isLinkAllowed(link)) return;
        if (BrowserUtils.open(link)) {
            status = "已在浏览器打开";
        } else {
            KeyboardUtils.setClipboardString(link);
            status = "地址已复制到剪贴板：" + link;
        }
    }

    private static final class CardView {
        private Panel row;
        private Panel plate;
        private Panel art;
        private AbstractWidget<?> textRail;
        private LabelWidget title;
        private LabelWidget imageHint;
        private AbstractWidget<?> imageLoading;
        private AbstractWidget<?> fold;
        private final List<RoundedButtonWidget> buttons = new ArrayList<>();
        private final List<LabelWidget> labels = new ArrayList<>();
        private Announcement data;
        private List<Announcement.Action> actions = List.of();
        private String[] full = new String[0];
        private String[] lines = new String[0];
        private String source = "";
        private CFontRenderer font;
        private long revision = -1;
        private double wrappedWidth = -1;
        private double textWidth;
        private double bodyTop;
        private double actionsTop;
        private double actionWidth;
        private boolean expanded;
        private boolean hasImage;
        private boolean stackActions;
        private NcmPromptMotion height;
        private long imageArrived;
        private AnnouncementImageCache.State imageState = AnnouncementImageCache.State.EMPTY;
    }
}
