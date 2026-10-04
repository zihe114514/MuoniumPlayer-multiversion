package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PersonalFmManager;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NcmSheet;
import com.muoniumplayer.core.screens.ncm.SegmentControl;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.utils.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 私人 FM 发现界面。
 *
 * <p>这一页的数据形状决定了版式：{@code PersonalFmManager.BATCH_SIZE} 是 1，一次只拉一首推荐，
 * 所以它不是一张歌单，没有「一列曲目」可摆。原来照歌单页摆的结果是顶上四组共 44 枚场景标签堆成
 * 一面墙、底下只有一行曲子，中间和下面大半屏是空的。现在把当前这首当成页面的主体（hero 卡），
 * 标签收成「每组一行、行内横向滚」，被换下去的那些首留在下面一条「刚才推荐过」里 ——
 * 空白被有内容的东西填住，而不是靠留白遮过去。</p>
 *
 * <p>动效跨不过整树重排：点一次模式走的是 {@code selectMode} → 请求 → {@code notifyUi} →
 * {@code NCMScreen#reloadCurrentPanel} → 面板再 {@code onInit} 建一遍子组件，所以「从上一次演到
 * 这一次」的那些量（滑块在哪、标签区展开到多少、上一张封面是谁）只能放在类上。侧栏的
 * {@code NavigateBar#announcedRows} 用的是同一招。</p>
 */
public final class PersonalFmPanel extends NCMPanel {

    private static final double MARGIN = 12.0;
    private static final double BUTTON_GAP = 6.0;
    private static final double CHIP_H = 17.0;
    /** 页眉那一行的高度：与主页 {@code HomePanel}、排行榜、热搜写的是同一个数，切页时页眉不再上下跳。 */
    private static final double HEADER_TOP = 12.0;

    private static final double MODE_TOP = 56.0;
    /** 模式行：一条轨道 + 一颗滑块，六段等宽，用的就是搜索栏「歌曲 / 歌单」那一条控件。 */
    private static final double MODE_H = 20.0;
    private static final double MODE_INSET = 3.0;
    /** 一格 = 最宽那枚标签的墨迹 + 左右各 5 像素。墨迹宽度要等字体装好才知道，所以首次渲染时才算。 */
    private static double modeCellW = Double.NaN;
    private static final double SCENE_TOP = 80.0;
    /** 一个分组固定占一行：轨道 19 + 行与行之间 5。四组就是 96，不再随窗口宽度堆高。 */
    private static final double SCENE_ROW = 24.0;
    /** 场景那一行的轨道高度：比模式行矮一档，四行叠起来才不压人。 */
    private static final double SCENE_TRACK_H = 19.0;
    /** 组名那一列的宽度，四行的 chip 左边缘因此对齐。 */
    private static final double GROUP_LABEL_W = 40.0;

    private static final double HERO_H = 132.0;
    private static final double HERO_PAD = 14.0;
    private static final double COVER_BOX = 104.0;

    private static final PersonalFmManager.Mode[] MODES = {
            PersonalFmManager.Mode.DEFAULT, PersonalFmManager.Mode.FAMILIAR,
            PersonalFmManager.Mode.EXPLORE, PersonalFmManager.Mode.SCENE_RCMD,
            PersonalFmManager.Mode.PUZZLE_MODE_RCMD, PersonalFmManager.Mode.AIDJ
    };
    private static final String[] MODE_LABELS = new String[MODES.length];

    /** 场景子模式的分组展示：分组标题 + 组内中文名，成员顺序与 PersonalFmManager 的分组一致。 */
    private static final String[][] SCENE_GROUP_LABELS = new String[PersonalFmManager.SCENE_GROUP_MODES.length][];

    /** 四组场景标签各自那颗滑块走到哪了，同一件事按组各记一份。 */
    private static final double[] sceneSlides =
            new double[PersonalFmManager.SCENE_GROUP_MODES.length];

    static {
        for (int index = 0; index < MODES.length; index++) MODE_LABELS[index] = MODES[index].getDisplayName();
        for (int group = 0; group < SCENE_GROUP_LABELS.length; group++) {
            String[] modes = PersonalFmManager.SCENE_GROUP_MODES[group];
            String[] labels = new String[modes.length];
            for (int index = 0; index < modes.length; index++) labels[index] = PersonalFmManager.sceneLabel(modes[index]);
            SCENE_GROUP_LABELS[group] = labels;
            // 0 会被读成「上一帧滑块在第一格」，于是每次装配都从第一格滑过来。NaN 才是「没有上一帧」。
            sceneSlides[group] = Double.NaN;
        }
    }

    // ── 跨重建留下的动画状态 ─────────────────────────────────────────────────
    /** 标签区展开到多少：0 = 整块收起，1 = 四组全在。切到/切离「场景推荐」时朝目标缓动过去。 */
    private static double sceneReveal;
    private static long sceneLastNanos;

    /** 模式行滑块走到哪了：整棵重排之后接着走完这一趟，而不是瞬移到新那一格。 */
    private static double modeSlide = Double.NaN;

    /** 已经演过进场的「刚才推荐过」那一行的指纹，用来只让新来的那一首落进来。 */
    private static final java.util.Set<String> historySeen = new java.util.LinkedHashSet<>();

    /** 上一张 hero 封面，以及它淡出、新封面淡入走到哪了。 */
    private static Location heroPreviousCover;
    private static Location heroCurrentCover;
    private static double heroSwapT = 1.0;
    /** 这一趟接力是从什么时候起算的，用来给「等新封面纹理」那道闸兜底。 */
    private static long heroSwapStartNanos;

    /** 卡上这一首是不是上一栏目的：是的话要把内容交给骨架。 */
    private static boolean heroStale;
    /** 交接走到哪了：0 = 内容全实，1 = 整张卡交给骨架。 */
    private static double handoffT;
    /** 这一轮过期是从什么时候起的，用来给骨架那 180 毫秒的宽限计时。 */
    private static long heroStaleSinceNanos;
    /** 卡上这一首是谁、它是哪个栏目要来的，用来认出「曲子还没换但栏目已经换了」。 */
    private static String heroSongKey;
    private static String heroSongStamp;

    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();
    private double lastFrameMs;
    private long lastFrameNanos;

    /**
     * 四行场景标签的子树，建一次就复用（见 {@link #addSceneRows} 那段为什么不能重建）。
     * 面板实例本身活得过整棵重排，所以这三份挂在实例上就够了。
     */
    private LabelWidget[] sceneHeaders;
    private Panel[] sceneRows;
    private ScrollPanel[] sceneViewports;
    /** 上一次各组的选中是哪一枚；变了才去把那一枚留在视口里。 */
    private static final int[] sceneShown = new int[PersonalFmManager.SCENE_GROUP_MODES.length];

    static {
        for (int group = 0; group < sceneShown.length; group++) sceneShown[group] = -1;
    }

    /** 上一次这一页真的画出来是什么时候，用来分「切走了又切回来」和「数据到了原地重排」。 */
    private long lastVisibleNanos;
    /** 这一次装配算不算重新进入这一页：算的话进场要重演一遍。 */
    private boolean reentering;

    @Override
    public void onInit() {
        this.reentering = isReenter();
        if (this.reentering) {
            // 标签区收回零、hero 的封面槽交回去：重新进入这一页应该像第一次打开那样把东西
            // 交出来一次，而不是「上一页留下的进度」直接摆在那。
            sceneReveal = 0.0;
            heroPreviousCover = null;
            heroCurrentCover = null;
            beginHeroRelay();
        }
        renderLayout();
        PersonalFmManager.ensureInitialLoad();
    }

    /**
     * 这一次 {@code onInit} 是重新进入这一页，还是数据到了原地重排。
     *
     * <p>两种情况用的是同一个面板实例（{@code NCMScreen#reloadCurrentPanel} 会拿原实例再跑一遍
     * {@code onInit}），所以只能问「上一次真的画出来是什么时候」：这一页不在屏幕上的时候它的
     * 回调根本不跑，隔了半秒以上才装配一次，中间它一定没在屏幕上。</p>
     */
    private boolean isReenter() {
        final long now = System.nanoTime();
        final double gap = this.lastVisibleNanos == 0L ? Double.POSITIVE_INFINITY
                : (now - this.lastVisibleNanos) / 1_000_000.0;
        this.lastVisibleNanos = now;
        return gap > 500.0;
    }

    private void renderLayout() {
        getChildren().clear();

        // 所有动画都从这一格推进：父组件的回调跑在子组件之前，所以子组件读到的
        // sceneReveal / pillX / heroSwapT 永远是「这一帧已经算好的」那一份。
        this.setBeforeRenderCallback(() -> {
            this.lastVisibleNanos = System.nanoTime();
            this.lastFrameMs = frameMillis();
            this.revealQueue.beginFrame();
            this.advanceScene();
            this.advanceHandoff();
            this.advanceHeroCover();
        });

        // 标题与副标题先装配：右边那两颗按钮要读它本帧的真实位置来对齐，而子组件是按列表
        // 顺序正序渲染的，排在后面的回调才量得到前面那一格。
        LabelWidget title = new LabelWidget("私人 FM", FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(MARGIN, HEADER_TOP)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0 - 84.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));

        LabelWidget subtitle = new LabelWidget(PersonalFmManager::getStatus, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(MARGIN, 39)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0 - 60.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL));

        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        addChild(back);
        back.setShouldOverrideMouseCursor(true);
        back.setGlassPlate(GlassLook.Surface.PLAYER);
        back.setBeforeRenderCallback(() -> {
            back.setBounds(28, 22)
                    .setPosition(Math.max(MARGIN, getWidth() - back.getWidth() - MARGIN),
                            headerCenter(title) - back.getHeight() * .5)
                    .setRadius(6)
                    .setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        addRefresh(back, title);
        addModeRow();
        addSceneRows();

        List<Music> batch = PersonalFmManager.getCurrentBatchSnapshot();
        Music current = batch.isEmpty() ? null : batch.get(0);
        boolean stale = isHeroStale(current);
        if (stale && !heroStale) heroStaleSinceNanos = System.nanoTime();
        heroStale = stale;
        addHero(current, stale);
        addHistory();
    }

    /**
     * 卡上这一首还算不算「这一栏目的下一首」。
     *
     * <p>换栏目走的是 {@code selectMode} → 请求 → {@code notifyUi} → 重排，而 {@code currentBatch}
     * 只有在新推荐真的回来那一刻才被换掉。中间那一两百毫秒里，hero 卡画的是<b>上一栏目</b>那一首，
     * 只是已经被展开的标签区推到了新位置 —— 用户看到的就是「上一首的残影」，要等请求回来才换。
     * 所以这一首得记住自己是哪个栏目要来的：栏目一换、曲子还没换，就先把内容交出去。</p>
     */
    private static boolean isHeroStale(Music current) {
        String selection = PersonalFmManager.getSelectedMode() + "|" + PersonalFmManager.getSelectedSubMode();
        if (current != null) {
            String key = current.getStableKey();
            if (!key.equals(heroSongKey)) {
                heroSongKey = key;
                heroSongStamp = selection;
            }
        }
        return current == null || !selection.equals(heroSongStamp);
    }

    /**
     * 页眉那一行的光学中线。
     *
     * <p>这一页原来把标题写在 y=31，而主页、排行榜、热搜都写在 y=12 —— 侧栏点下来时页眉会
     * 上下跳 20 像素。右边两颗按钮（返回 22 高、刷新 17 高）也不各写各的 y，而是都对准标题
     * 这一行的中心：盒子的中心对齐文字的中心，才读得出它们在同一行上。</p>
     */
    private static double headerCenter(final LabelWidget title) {
        return title.getRelativeY() + title.getHeight() * .5;
    }

    // ── 刷新：文字按钮 + 转轮 ────────────────────────────────────────────────

    private void addRefresh(final RoundedButtonWidget back, final LabelWidget title) {
        RoundedButtonWidget refresh = new RoundedButtonWidget("刷新", FontManager.pf12bold);
        addChild(refresh);
        refresh.setShouldOverrideMouseCursor(true);
        refresh.setGlassPlate(GlassLook.Surface.PLAYER);
        refresh.setBeforeRenderCallback(() -> {
            boolean loading = PersonalFmManager.isLoading();
            refresh.setBounds(40, 17)
                    // 挤在返回的左边，和它同一行：原来这两颗在右上角上下叠着，
                    // 而标题被推到下面一行去对刷新那条线。
                    .setPosition(Math.max(MARGIN, back.getRelativeX() - BUTTON_GAP - refresh.getWidth()),
                            headerCenter(title) - refresh.getHeight() * .5)
                    .setRadius(4)
                    .setClickable(!loading)
                    // 只把字压暗是不够的：原来加载中这颗按钮只是变成 45% 透明，看着像被禁用了，
                    // 却没有任何地方说「正在来的东西在哪」。转轮盖在它身上，一句话都不用改。
                    .setAlpha(loading ? .35f : 1.0f)
                    .setColor(refresh.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            refresh.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            refresh.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        refresh.setOnClickCallback((x, y, button) -> {
            if (button != 0 || PersonalFmManager.isLoading()) return button == 0;
            PersonalFmManager.openOrRefresh();
            return true;
        });

        PanelLoadingWidget spinner = new PanelLoadingWidget("");
        addChild(spinner);
        spinner.setClickable(false);
        spinner.setBeforeRenderCallback(() -> {
            boolean loading = PersonalFmManager.isLoading();
            spinner.setSpinning(loading);
            spinner.setAlpha(loading ? 1f : 0f);
            spinner.setBounds(refresh.getWidth(), refresh.getHeight())
                    .setPosition(refresh.getRelativeX(), refresh.getRelativeY());
        });
    }

    // ── 模式行：一条轨道 + 一颗滑块，六段等宽 ────────────────────────────────

    /**
     * 六枚模式换成搜索栏那一条 {@link SegmentControl}。
     *
     * <p>原来这里是一条自己画的滑块贴在文字底下：没有轨道，选中态就只是「一块粉色底」，和搜索栏
     * 那两颗「歌曲 / 歌单」看着是两种东西。共用同一条控件之后，轨道、内缩、圆角、按压回弹、悬停
     * 淡底、按墨迹居中都只有一份实现，改一处两边一起好。</p>
     */
    private void addModeRow() {
        final SegmentControl.Spec spec = new SegmentControl.Spec();
        spec.y = MODE_TOP;
        spec.height = MODE_H;
        spec.radius = 7.0;
        spec.inset = MODE_INSET;
        // 基准 1150：默认档（3.0×）下走满整条轨道约 383 毫秒。这一页其余几步（标签区展开、
        // hero 让位、封面接力）都在三百毫秒这一档，滑块比它们快就没人和它们对得上拍子。
        spec.durationMs = 1150.0;
        spec.font = FontManager.pf12bold;
        // 点一次模式就是一次整棵重排，控件对象活不过那一趟，所以滑块位置交给类上的那一份记忆。
        spec.memory = new SegmentControl.SlideMemory() {
            @Override
            public double last() { return modeSlide; }

            @Override
            public void remember(double slide) { modeSlide = slide; }
        };

        final String[] glyphs = new String[MODES.length];
        for (int index = 0; index < glyphs.length; index++) glyphs[index] = "";

        SegmentControl.attach(this, () -> {
            spec.x = MARGIN;
            spec.width = modeTrackWidth();
            return spec;
        }, () -> modeSlotOf(PersonalFmManager.getSelectedMode()),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                glyphs, MODE_LABELS,
                index -> {
                    // 加载中不许换档：请求线程还拿着上一批的游标，这时候换模式会串成「用 A 模式的
                    // 游标去要 B 模式的推荐」。返回 false 那一趟点击不作数，滑块不动也不回弹。
                    if (PersonalFmManager.isLoading()) return false;
                    PersonalFmManager.Mode mode = MODES[index];
                    if (mode != PersonalFmManager.getSelectedMode()) {
                        PersonalFmManager.selectMode(mode, mode.getDefaultSubMode());
                    }
                    return true;
                });
    }

    /** 一格多宽：按最宽那枚标签的墨迹算。字体要等界面装好才有度量，所以拖到第一次渲染时才算。 */
    private static double modeCellWidth() {
        if (Double.isNaN(modeCellW)) {
            double widest = 0.0;
            for (String label : MODE_LABELS) widest = Math.max(widest, FontManager.pf12bold.getStringWidthD(label));
            modeCellW = widest + 10.0;
        }
        return modeCellW;
    }

    /** 轨道总宽：内容区放不下就整条收窄，宁可挤一点，也不把最后一枚推出右边。 */
    private double modeTrackWidth() {
        return Math.min(modeCellWidth() * MODES.length + MODE_INSET * 2.0,
                Math.max(1.0, getWidth() - MARGIN * 2.0));
    }

    private static int modeSlotOf(PersonalFmManager.Mode mode) {
        for (int index = 0; index < MODES.length; index++) if (MODES[index] == mode) return index;
        return 0;
    }

    // ── 标签区：每组一行，行内横向滚 ─────────────────────────────────────────

    /**
     * 四组场景标签各占一行，行内横向滚动，并且<b>建一次就复用</b>。
     *
     * <p>原来是随窗口宽度换行：宽窗口下 18 枚的风格流派刚好塞进一行，窄窗口就堆成两三行，
     * 四组叠起来一面墙，把曲子挤到屏幕底下。改成每组一行之后这一区的高度是算得出来的
     * （{@link #SCENE_ROW} × 组数），hero 卡的位置也就稳定了；一行放不下的部分交给
     * {@link ScrollPanel} 横向滚，它自带裁切。</p>
     *
     * <p>为什么不能像别的部分那样每次装配重建：点一枚标签走的是 {@code selectMode} → 请求 →
     * {@code notifyUi} → 整棵重排，而这一区的<b>横向滚动位置</b>与<b>滑块实例</b>都活在那棵子里。
     * 重建之后滚动位置回到行首 —— 用户得先把 18 枚里的「说唱」滚出来才点得到，点完那一行
     * 弹回「摇滚」，看着就是画面跳了一下；滑块则是旧那一格瞬间消失、新那一格瞬间出现，
     * 因为淡出的是那个已经被丢掉的实例。所以这四行连视口带控件一起留着，只重挂不改建。</p>
     */
    private void addSceneRows() {
        if (this.sceneHeaders == null) {
            this.sceneHeaders = new LabelWidget[SCENE_GROUP_LABELS.length];
            this.sceneRows = new Panel[SCENE_GROUP_LABELS.length];
            this.sceneViewports = new ScrollPanel[SCENE_GROUP_LABELS.length];
            for (int group = 0; group < SCENE_GROUP_LABELS.length; group++) buildSceneRow(group);
        }
        for (int group = 0; group < SCENE_GROUP_LABELS.length; group++) {
            addChild(this.sceneHeaders[group]);
            addChild(this.sceneRows[group]);
        }
    }

    private void buildSceneRow(final int slot) {
        LabelWidget header = new LabelWidget(PersonalFmManager.SCENE_GROUP_TITLES[slot], FontManager.pf12bold);
        header.setClickable(false);
        header.setBeforeRenderCallback(() -> header
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setBounds(GROUP_LABEL_W, CHIP_H)
                // 组名和这一行的轨道是同一条中线上的两样东西：按墨迹对过去，
                // 而不是照着一个写死的 y 各摆各的。
                .setPosition(MARGIN, NcmSheet.inkTop(header, sceneRowTop(slot) + SCENE_TRACK_H * .5))
                .setAlpha((float) groupReveal(slot))
                .setMaxWidth(GROUP_LABEL_W)
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));

        Panel row = new Panel();
        ScrollPanel viewport = new ScrollPanel();
        row.addChild(viewport);
        viewport.setSpacing(0).setScrollStrength(42).setAlignment(ScrollPanel.Alignment.HORIZONTAL);
        viewport.setBeforeRenderCallback(() -> viewport.setBounds(0, 0, row.getWidth(), row.getHeight()));
        final SegmentControl control = addSceneSegment(viewport, slot);

        // 收起只压 alpha，<b>不能</b> setHidden：这一格是行自己管自己的显隐，而 renderWidget
        // 在隐藏态连 beforeRenderCallback 都不跑，就没有任何东西能把它再翻回来 —— 收起一次
        // 就永久看不见标签区了。透明的那几行还照旧渲染，代价是四行，可以接受。
        row.setBeforeRenderCallback(() -> {
            double eased = TransitionCurveCache.MOTION.at(groupReveal(slot));
            // 但透明的行不能再被点到：整块的显隐交给控件自己那份 presence，它同时管着
            // 轨道淡出与命中区开关，不会出现「看不见但按得到」。
            control.setEnabled(sceneReveal > .5);
            row.setBounds(Math.max(1.0, getWidth() - MARGIN - GROUP_LABEL_W - MARGIN), SCENE_TRACK_H)
                    // 除了淡入还带一点从左边推进来：四组依次到场，展开才不像一整块亮灯。
                    .setPosition(MARGIN + GROUP_LABEL_W + 8.0 * (1.0 - eased), sceneRowTop(slot))
                    .setAlpha((float) eased);
            int chosen = sceneSelectionOf(slot);
            if (chosen != sceneShown[slot]) {
                sceneShown[slot] = chosen;
                keepChipInView(slot, viewport, chosen);
            }
        });

        this.sceneHeaders[slot] = header;
        this.sceneRows[slot] = row;
        this.sceneViewports[slot] = viewport;
    }

    /**
     * 把刚选中的那一枚留在视口里，但只在它真的看不见时才动。
     *
     * <p>滚到行尾去点「游戏」，点完不该弹回行首；而已经看得见的标签也不该被强行居中 ——
     * 那会让每一枚点下去都带一次整行位移，正是这里要避免的那种闪。</p>
     */
    private void keepChipInView(int slot, ScrollPanel viewport, int index) {
        if (index < 0 || viewport == null) return;
        double left = SCENE_INSET;
        for (int at = 0; at < index; at++) left += labelWidth(SCENE_GROUP_LABELS[slot][at]);
        double width = labelWidth(SCENE_GROUP_LABELS[slot][index]);
        double offset = Math.max(0.0, Math.min(viewport.targetScrollOffset,
                Math.max(0.0, sceneTrackWidth(slot) - viewport.getWidth())));
        if (left - SCENE_INSET < offset) offset = Math.max(0.0, left - width);
        else if (left + width > offset + viewport.getWidth()) offset = left + width - viewport.getWidth();
        viewport.targetScrollOffset = offset;
        viewport.actualScrollOffset = offset;
    }

    /** 场景轨道的内缩，与 {@link #addSceneSegment} 里那份是同一个数。 */
    private static final double SCENE_INSET = 2.0;

    /**
     * 一组场景标签 = 一条轨道 + 一颗滑块，格与格按文字长短排。
     *
     * <p>原来这一行是一堆各自换色的 chip，选中态只是「一块粉底」，和上面那条模式行看着是两种
     * 东西。共用同一条控件之后，四组里只有当前那一组有滑块（宿主报 {@code -1}，其余三组只剩
     * 轨道），点一枚标签滑块就从上一枚滑过去 —— 和换模式是同一个动作、同一拍子。</p>
     */
    private SegmentControl addSceneSegment(final ScrollPanel viewport, final int group) {
        final String[] labels = SCENE_GROUP_LABELS[group];
        final String[] subModes = PersonalFmManager.SCENE_GROUP_MODES[group];

        final SegmentControl.Spec spec = new SegmentControl.Spec();
        spec.height = SCENE_TRACK_H;
        spec.radius = 6.0;
        spec.inset = SCENE_INSET;
        spec.durationMs = SCENE_MILLIS;
        spec.font = FontManager.pf12bold;
        spec.cellWidth = index -> labelWidth(labels[index]);
        // 轨道是这一格视口里唯一的子组件，位置由 ScrollPanel 按滚动偏移摆：自己再报一次位置
        // 就会把横向滚过去的那点偏移写回零。
        spec.positionsItself = false;
        spec.memory = new SegmentControl.SlideMemory() {
            @Override public double last() { return sceneSlides[group]; }

            @Override public void remember(double slide) { sceneSlides[group] = slide; }
        };

        final String[] glyphs = new String[labels.length];
        for (int index = 0; index < glyphs.length; index++) glyphs[index] = "";

        return SegmentControl.attach(viewport, () -> {
            spec.width = sceneTrackWidth(group);
            return spec;
        }, () -> sceneSelectionOf(group),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                glyphs, labels,
                index -> {
                    if (PersonalFmManager.isLoading()) return false;
                    PersonalFmManager.selectMode(PersonalFmManager.Mode.SCENE_RCMD, subModes[index]);
                    return true;
                });
    }

    /** 轨道要多宽：每一格自己的宽度加起来，外加两侧内缩。放不下的部分由视口横向滚。 */
    private static double sceneTrackWidth(int group) {
        double at = 0.0;
        for (String label : SCENE_GROUP_LABELS[group]) at += labelWidth(label);
        return at + SCENE_INSET * 2.0;
    }

    /**
     * 这一组里当前选中的那一枚，不在这一组就报 {@code -1}。
     *
     * <p>四组是互斥的：一次只有一枚子模式生效，所以另外三组没有「选中」可言。报 -1 让滑块淡出、
     * 位置原地冻住，比把它们各自的第一枚点亮更诚实。</p>
     */
    private static int sceneSelectionOf(int group) {
        if (PersonalFmManager.getSelectedMode() != PersonalFmManager.Mode.SCENE_RCMD) return -1;
        String chosen = PersonalFmManager.getSelectedSubMode();
        String[] modes = PersonalFmManager.SCENE_GROUP_MODES[group];
        for (int index = 0; index < modes.length; index++) if (modes[index].equals(chosen)) return index;
        return -1;
    }

    private double sceneRowTop(int group) {
        return SCENE_TOP + group * SCENE_ROW;
    }

    /** 标签区当前展开到多少高度 —— hero 卡与下面那一列都从它接着摆。 */
    /** 「刚才推荐过」那一列的标题位置：紧跟在 hero 卡底下。 */
    private double historyTop() {
        return heroTop() + HERO_H + 12.0;
    }

    private double sceneHeight() {
        return SCENE_ROW * SCENE_GROUP_LABELS.length * sceneReveal;
    }

    /**
     * 某一组展开到多少：整块进度里每组占 0.55 宽的一扇、彼此错开 0.15，四组就自上而下依次到场。
     * 最后一扇正好在 {@link #sceneReveal} 走到 1 时落位，收起时按相反顺序退场。
     */
    private static double groupReveal(int group) {
        return NcmSheet.clamp01((sceneReveal - group * 0.15) / 0.55);
    }

    /** 朝目标线性走一步：展开与收起用同一条时长，反过来切不会比切过去更快。 */
    private void advanceScene() {
        boolean wanted = PersonalFmManager.getSelectedMode() == PersonalFmManager.Mode.SCENE_RCMD;
        double target = wanted ? 1.0 : 0.0;
        double step = this.lastFrameMs / Math.max(1.0, SCENE_MILLIS / SheetClock.speed());
        if (sceneReveal < target) {
            sceneReveal = Math.min(target, sceneReveal + step);
        } else if (sceneReveal > target) {
            sceneReveal = Math.max(target, sceneReveal - step);
        }
    }

    /**
     * 标签区展开/收起要多少毫秒（基准值，运行时还要除以「动画速度」倍率）。
     *
     * <p>这一档必须比「一块板子淡进来」长：它一走就是四行标签到场、hero 卡往下让 84 像素，
     * 是一串东西依次落位。基准 220 在默认档（3.0×）下只剩 73 毫秒，四行与卡片就是同一帧里
     * 换了一次位置 —— 读不出任何先后。{@code NcmSheet} 那条分层瀑布遇到过同一件事，
     * 答案是单独给一档基准（见 {@code SheetClock#CASCADE_OPEN_MS}），这里照同一档办。</p>
     */
    private static final double SCENE_MILLIS = 900.0;

    // ── hero 卡：当前这首 ────────────────────────────────────────────────────

    /**
     * hero 卡这一帧该摆在哪。
     *
     * <p>只能逐帧问，不能在装配时算完传下去：标签区正在展开/收起的那两百毫秒里，
     * {@link #sceneHeight()} 每帧都在变，装配那一刻取到的那个数是上一帧的，卡片就会
     * 和它上面那四行脱开、各走各的。</p>
     */
    private double heroTop() {
        return SCENE_TOP + sceneHeight() + 8.0;
    }

    /** hero 卡与里面所有东西共用的宽度，同样逐帧问。 */
    private double heroWidth() {
        return Math.max(1.0, getWidth() - MARGIN * 2.0);
    }

    private void addHero(Music current, boolean stale) {
        // 卡底一层就够：面板底色就是 GENERIC_BACKGROUND，同色同 alpha 的「卡」等于没画，
        // 所以往文字色提亮一点，才读得出这是一张卡。
        RoundedRectWidget card = new RoundedRectWidget();
        addChild(card);
        card.setGlassPlate(GlassLook.Surface.PLAYER);
        card.setClickable(false);
        card.setBeforeRenderCallback(() -> card
                .setBounds(heroWidth(), HERO_H)
                .setPosition(MARGIN, heroTop())
                .setRadius(6)
                .setColor(NcmSheet.mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .055))
                .setAlpha(.9f)
                .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f)));

        // 交接是一进一出同一趟：内容淡出的那两百多毫秒里骨架淡进来，中间不会出现
        // 「两张都在」或者「两张都没有」那一帧。
        PanelLoadingWidget waiting = new PanelLoadingWidget(current == null
                ? (PersonalFmManager.isLoading() ? "正在获取推荐…" : "暂无私人 FM 推荐")
                : "正在换取这一栏目的推荐…");
        addChild(waiting);
        waiting.setClickable(false);
        waiting.setBeforeRenderCallback(() -> waiting.setSpinning(PersonalFmManager.isLoading())
                .setAlpha((float) handoffT)
                .setBounds(heroWidth(), HERO_H).setPosition(MARGIN, heroTop()));

        if (current == null || handoffT >= .995) return;

        addHeroCover(current);
        addHeroActions(current);
        addHeroText(current);
    }

    /** 卡上那一首现在该有多实：交接走到底就是零，整张卡交给骨架。 */
    private static double heroKeep() {
        return 1.0 - handoffT;
    }

    /**
     * 朝「交出去 / 收回来」走一步：两个方向同一条时长，反过来不会比交出去更快。
     *
     * <p>骨架要等过期<b>持续了一小段</b>才亮：请求一百毫秒就回来时，不兜这一道就是
     * 「闪一下骨架又闪回去」，比留着上一首更难看。真在等的时候 180 毫秒的宽限看不出来。</p>
     */
    private void advanceHandoff() {
        boolean showSkeleton = heroStale && (System.nanoTime() - heroStaleSinceNanos) / 1_000_000.0 > 180.0;
        double step = this.lastFrameMs / Math.max(1.0, HERO_SWAP_MILLIS / SheetClock.speed());
        handoffT = showSkeleton ? Math.min(1.0, handoffT + step) : Math.max(0.0, handoffT - step);
    }

    /**
     * 换一首时新封面从旧封面上接力：旧的边淡出边放大，新的边淡入边从 .94 落到原位。
     *
     * <p>整树重排之后旧封面那张图已经没有任何控件持有它了，所以「上一张是谁」只能记在类上。
     * 两张叠着画，中间那一瞬两张都在，读起来是换了一张图而不是画面闪了一下黑；前缘走
     * {@link TransitionCurveCache#SPRING_LAND} 所以新封面会稍微弹过头一点再落回槽位。</p>
     */
    private void addHeroCover(Music current) {
        Location next = current.getSmallCoverLocation();
        if (heroCurrentCover != next) {
            heroPreviousCover = heroCurrentCover;
            heroCurrentCover = next;
            // 换曲和进场走同一趟：底下是空卡时也从 .94 弹进来、文字逐行接力，
            // 而不是整块直接亮灯。
            beginHeroRelay();
        }

        double boxLeft = MARGIN + HERO_PAD;

        if (heroSwapT < 1.0 && heroPreviousCover != null) {
            RoundedImageWidget stale = new RoundedImageWidget(heroPreviousCover, 0, 0, 0, 0);
            addChild(stale);
            stale.setClickable(false);
            stale.setLinearFilter(true);
            stale.setBeforeRenderCallback(() -> {
                double grow = COVER_BOX * (1.0 + .06 * heroSwapT);
                double top = coverTop();
                stale.setBounds(grow, grow)
                        .setPosition(boxLeft - (grow - COVER_BOX) * .5,
                                top - (grow - COVER_BOX) * .5)
                        .setRadius(5).setAlpha((float) ((1.0 - heroSwapT) * heroKeep()));
            });
        }

        RoundedImageWidget cover = new RoundedImageWidget(() -> current.getSmallCoverLocation(), 0, 0, 0, 0);
        addChild(cover);
        cover.setLinearFilter(true);
        cover.setBeforeRenderCallback(() -> {
            double land = TransitionCurveCache.SPRING_LAND.at(heroSwapT);
            double size = COVER_BOX * (.94 + .06 * land);
            double top = coverTop();
            cover.setBounds(size, size)
                    .setPosition(boxLeft + (COVER_BOX - size) * .5, top + (COVER_BOX - size) * .5)
                    .setRadius(5).setAlpha((float) (heroSwapT * heroKeep()));
        });
        cover.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            playCurrent(current);
            return true;
        });
    }

    /**
     * 封面槽位这一帧该在哪。
     *
     * <p>必须逐帧问，和 {@link #heroTop()} 同一个理由：重新进入这一页时标签区是从零展开的，
     * 装配那一刻算出来的那个数比往后的每一帧都高 96 逻辑像素 —— 卡片往下走，封面留在原地，
     * 就压在四行标签上。上一版正是把 {@code heroTop()} 在装配时取了一次塞进 final 变量，
     * 播放中切走再切回来就是这个样子。</p>
     */
    private double coverTop() {
        return heroTop() + (HERO_H - COVER_BOX) * .5;
    }

    /** 起一趟接力：封面与逐行文字都读 {@link #heroSwapT} 这一份，所以只需要按一次表。 */
    private static void beginHeroRelay() {
        heroSwapT = 0.0;
        heroSwapStartNanos = System.nanoTime();
    }

    private void advanceHeroCover() {
        if (heroSwapT >= 1.0) return;
        // 真在两张图之间接力时，新封面那张图还没进纹理表就别急着换：先让上一张顶着，接得上才
        // 开始淡入。少了这一道闸，接力会在一百毫秒里淡入一张还没下载完的封面，屏幕上就是一块白。
        // 只在头 1.2 秒里等：图一直下不来时，不能让整张卡连文字一起永远空着。
        if (heroPreviousCover != null && TextureManager.getInstance().getTexture(heroCurrentCover) == null
                && (System.nanoTime() - heroSwapStartNanos) / 1_000_000.0 < 1200.0) return;
        heroSwapT = Math.min(1.0, heroSwapT + this.lastFrameMs
                / Math.max(1.0, HERO_SWAP_MILLIS / SheetClock.speed()));
    }

    /**
     * 封面接力与三行文字的基准毫秒。和 {@link #SCENE_MILLIS} 同一条理由：这一趟要演的是
     * 「旧图让位 → 新图落位 → 曲名 → 艺人 → 专辑」五步，默认档下只剩一百多毫秒就数不出步数了。
     */
    private static final double HERO_SWAP_MILLIS = 900.0;

    /**
     * 换一首时文字逐行接力：曲名先到，艺人行、专辑行各晚一档。
     *
     * <p>进度挂在 {@link #heroSwapT} 上而不是另起一条时钟 —— 封面与文字因此永远是同一趟，
     * 不会出现「图已经换了、字还是上一首」。开页那一趟 heroSwapT 直接是 1，所以什么都不演。</p>
     */
    private double lineReveal(int line) {
        double t = (heroSwapT - line * 0.14) / 0.62;
        return NcmSheet.clamp01(t);
    }

    private void addHeroText(Music current) {
        double textX = MARGIN + HERO_PAD + COVER_BOX + 16.0;

        LabelWidget name = new LabelWidget(current.getName(), FontManager.pf18bold);
        addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            double eased = TransitionCurveCache.MOTION.at(lineReveal(0));
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setPosition(textX, heroTop() + 14 + 5.0 * (1.0 - eased))
                    .setAlpha((float) (eased * heroKeep()))
                    .setMaxWidth(heroTextWidth(textX))
                    .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL);
        });

        // 徽标跟着曲名排在同一趟推进里，和曲行上那几枚小胶囊同一个尺寸、同一套底色：
        // 这一页同时出现「实心小胶囊」和「裸文字」两种 VIP，就是原来那处不协调的来源。
        addHeroBadges(current, name);

        String artists = current.getArtistsName();
        LabelWidget byline = new LabelWidget((artists.isEmpty() ? "" : artists + " · ")
                + MusicWidget.formatDuration(current.getDuration()), FontManager.pf12);
        addChild(byline);
        byline.setClickable(false);
        byline.setBeforeRenderCallback(() -> {
            double eased = TransitionCurveCache.MOTION.at(lineReveal(1));
            byline.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(textX, heroTop() + 38 + 5.0 * (1.0 - eased))
                    .setAlpha((float) (eased * heroKeep()))
                    .setMaxWidth(heroTextWidth(textX))
                    .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        });

        String album = current.getAlbum() == null ? "" : nvl(current.getAlbum().getName());
        if (!album.isEmpty()) {
            LabelWidget albumLine = new LabelWidget("专辑 · " + album, FontManager.pf12);
            addChild(albumLine);
            albumLine.setClickable(false);
            albumLine.setBeforeRenderCallback(() -> {
                double eased = TransitionCurveCache.MOTION.at(lineReveal(2));
                albumLine.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(textX, heroTop() + 54 + 5.0 * (1.0 - eased))
                        .setAlpha((float) (eased * .82 * heroKeep()))
                        .setMaxWidth(heroTextWidth(textX))
                        .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            });
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 曲名右边缘之后接着排徽标 —— 曲名的回调跑在这一格之前，所以量到的是它本帧的真实位置。 */
    private void addHeroBadges(Music current, LabelWidget name) {
        List<String> tags = new ArrayList<>(3);
        if (current.isDigitalAlbumTrack()) tags.add("专辑");
        else if (current.hasVipRestriction()) tags.add("VIP");
        if (current.isCloudSong()) tags.add("云盘");
        String unblock = MusicWidget.unblockBadgeText(current);
        if (!unblock.isEmpty()) tags.add(unblock);
        if (tags.isEmpty()) return;

        double x = 0.0;
        for (String tag : tags) {
            double width = Math.max(21.0, FontManager.pf12bold.getStringWidthD(tag) + 6.0);
            RoundedRectWidget plate = new RoundedRectWidget();
            addChild(plate);
            plate.setClickable(false);
            final double left = x;
            final double plateWidth = width;
            final boolean accent = tag.equals("VIP");
            plate.setBeforeRenderCallback(() -> {
                double eased = TransitionCurveCache.MOTION.at(lineReveal(0));
                plate.setBounds(plateWidth, 10)
                        .setPosition(name.getRelativeX() + name.getWidth() + 3 + left,
                                name.getRelativeY() + name.getHeight() * .5 - plate.getHeight() * .5)
                        .setRadius(2.5)
                        .setAlpha((float) (.95 * eased * heroKeep()))
                        .setColor(NCMScreen.getColor(accent
                                ? NCMScreen.ColorType.ACCENT : NCMScreen.ColorType.ELEMENT_HOVER));
            });
            LabelWidget text = new LabelWidget(tag, FontManager.pf12bold);
            plate.addChild(text);
            text.setClickable(false);
            text.setBeforeRenderCallback(() -> {
                text.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                text.center();
            });
            x += width + 3.0;
        }
    }

    private double heroTextWidth(double textX) {
        return Math.max(1.0, getWidth() - textX - MARGIN - HERO_PAD - ACTION_RESERVE);
    }

    /** 动作行右对齐到卡片右边缘，文字列因此要让出这么宽一段。 */
    private static final double ACTION_RESERVE = 20.0 + 6.0 + 52.0 + 6.0 + 52.0 + 14.0;

    private void addHeroActions(Music current) {
        double width = LikeButton.BOX + BUTTON_GAP + 52.0 + BUTTON_GAP + 52.0;
        Panel actions = new Panel();
        addChild(actions);
        actions.setBeforeRenderCallback(() -> actions
                .setBounds(width, 22)
                .setAlpha((float) heroKeep())
                .setPosition(MARGIN + heroWidth() - HERO_PAD - width, heroTop() + HERO_H - 32.0));

        // 三样东西同高 20，都摆在 22 高的这一行里：红心自己 centerVertically，两颗按钮就写 y=1，
        // 于是同一水平线上。原来红心按 20 格顶格、按钮往下挪了 2 像素，看上去就是没对齐。
        new LikeButton(actions, current, () -> 0.0);

        RoundedButtonWidget play = new RoundedButtonWidget("播放", FontManager.pf12bold);
        actions.addChild(play);
        play.setGlassPlate(GlassLook.Surface.PLAYER);
        play.setShouldOverrideMouseCursor(true);
        play.setBeforeRenderCallback(() -> play.setBounds(52, 20)
                .setPosition(actions.getWidth() - 52.0, 1).setRadius(4)
                .setColor(play.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                        .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f)));
        play.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        play.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            playCurrent(current);
            return true;
        });

        RoundedButtonWidget skip = new RoundedButtonWidget("换一首", FontManager.pf12bold);
        actions.addChild(skip);
        skip.setGlassRim(GlassLook.Surface.PLAYER);
        skip.setShouldOverrideMouseCursor(true);
        skip.setBeforeRenderCallback(() -> skip.setBounds(52, 20)
                .setPosition(play.getRelativeX() - BUTTON_GAP - 52.0, 1).setRadius(4)
                .setClickable(!PersonalFmManager.isLoading())
                .setAlpha(PersonalFmManager.isLoading() ? .5f : 1.0f)
                .setColor(skip.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ACCENT)));
        skip.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        skip.setOnClickCallback((x, y, button) -> {
            if (button != 0 || PersonalFmManager.isLoading()) return button == 0;
            // 「垃圾桶」在这个实现里就是换一首：skipCurrentAndRequestNext 会先把当前这首
            // 交给电台的垃圾桶端点，再拉下一首。文案与播放条上那颗保持一致。
            PersonalFmManager.skipCurrentAndRequestNext();
            return true;
        });
    }

    /** 和曲行点下去完全同一条路：FM 走 playFm，才能保住它那套隔离的会话与「不顺着列表往下播」。 */
    private static void playCurrent(Music current) {
        List<Music> batch = PersonalFmManager.getCurrentBatchSnapshot();
        CloudMusic.playFm(new CopyOnWriteArrayList<>(batch), batch.indexOf(current));
    }

    // ── 刚才推荐过 ───────────────────────────────────────────────────────────

    private void addHistory() {
        List<Music> history = PersonalFmManager.getHistorySnapshot();
        if (history.isEmpty()) return;

        LabelWidget header = new LabelWidget("刚才推荐过", FontManager.pf12bold);
        addChild(header);
        header.setClickable(false);
        header.setBeforeRenderCallback(() -> header
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(MARGIN, historyTop()));

        ScrollPanel list = new ScrollPanel();
        addChild(list);
        list.setSpacing(0).setScrollStrength(42).setAlignment(ScrollPanel.Alignment.VERTICAL);
        // 与歌单详情那一列同一档：历史里的行也挨着，收尾时按一整张距离场画边。
        // 这一列每来一首新的只淡入<em>新来的那一行</em>，它自己那套进场缩放正需要
        // 「组内各块矩阵不同也画得对」这条（见 GlassEdgeBatch#bakeIntoGroup）。
        list.setGlassFusion(GlassLook.Surface.PLAYER);
        list.setBeforeRenderCallback(() -> list.setBounds(MARGIN, historyTop() + 18.0,
                Math.max(1.0, getWidth() - MARGIN * 2.0),
                Math.max(1.0, getHeight() - historyTop() - 26.0)));

        PlayList fm = fmPlaylist(history);
        // 只让<b>新来的那一首</b>落进来。每来一首推荐都会走一次 reloadCurrentPanel，
        // 按「整列指纹变没变」来挂进场的话，底下那十几行会在每次数据到达时一起重演一遍
        // 「逐条落进来」—— 用户刚点了一枚标签，整列跟着抖一次，看着就是页面在抽风。
        List<String> keys = new ArrayList<>(history.size());
        for (Music song : history) keys.add(song.getStableKey());
        if (this.reentering) historySeen.clear();
        else historySeen.retainAll(keys);
        List<String> fresh = new ArrayList<>(2);
        for (String key : keys) if (!historySeen.contains(key)) fresh.add(key);
        historySeen.addAll(fresh);
        if (!fresh.isEmpty()) this.revealQueue.restart();

        List<AbstractWidget<?>> rows = new ArrayList<>(history.size());
        for (int index = 0; index < history.size(); index++) {
            MusicWidget row = new MusicWidget(history.get(index), fm, index);
            row.setShouldOverrideMouseCursor(true);
            if (fresh.contains(keys.get(index))) row.setEntranceAnimation(this.revealQueue);
            rows.add(row);
        }
        list.addChild(rows);
    }

    /**
     * 给这批曲子搭一张只在内存里的 {@link PlayList}。
     *
     * <p>{@link MusicWidget} 的点击分派要问它挂在谁身上（{@code isPersonalFm()} 决定走
     * {@code playFm} 而不是普通 {@code play}），所以哪怕这一页只有一首，也得有这么一张单子。
     * id 用固定的负数：它只进封面缓存的键，不进任何歌单表。</p>
     */
    private static PlayList fmPlaylist(List<Music> songs) {
        PlayList fmPlaylist = new PlayList(-900001L, "私人 FM", "", songs.size(), 0L, null, "个人推荐", 0L);
        fmPlaylist.setSearchMode(true);
        fmPlaylist.setPersonalFm(true);
        fmPlaylist.setMusics(new CopyOnWriteArrayList<>(songs));
        fmPlaylist.setMusicsQueried(true);
        fmPlaylist.setMusicsLoaded(true);
        return fmPlaylist;
    }

    private static double labelWidth(String label) {
        return Math.max(31.0, FontManager.pf12bold.getStringWidth(label) + 12.0);
    }

    /** 真毫秒时钟，单帧最多补 {@value #MAX_FRAME_MS} ms：切后台回来不让滑块与展开区一步跨过整段曲线。 */
    private double frameMillis() {
        long now = System.nanoTime();
        double frame = this.lastFrameNanos == 0L ? 0.0
                : Math.min(MAX_FRAME_MS, (now - this.lastFrameNanos) / 1_000_000.0);
        this.lastFrameNanos = now;
        return frame;
    }

    private static final double MAX_FRAME_MS = 250.0;

}
