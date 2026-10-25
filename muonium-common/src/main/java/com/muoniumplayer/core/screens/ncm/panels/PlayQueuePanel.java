package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.input.TouchGestures;
import com.muoniumplayer.core.settings.TouchSettings;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.utils.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放器底部的"下一首播放"抽屉。
 *
 * <p>列表内容是 {@link CloudMusic#getQueuedNextSongs()} 的每帧快照，顺序调整只走
 * {@link CloudMusic#moveQueuedNext(int, int)}：当前播放位置、队列长度、播放模式都不受影响，
 * 没用过这个功能的用户拿到的仍然是原来的行为。</p>
 *
 * <p>进了一起听房间后抽屉整体切到<b>房间模式</b>（{@link PlayQueueRoomBridge}）：显示的是一起听
 * 歌单而不是本地待播段——房间里的「下一首播放」本来就被改写成房间点歌，本地段在房间里永远是空的。
 * 拖动/移除/清空改走房间协议（服务器房间全量支持；酷狗官方房间只读展示），松手才发一条
 * {@code move}，避免一次拖动变成几十条消息。</p>
 *
 * <p>每一行的位置由本面板在 {@link #onRender(double, double)} 里统一计算（父组件的 onRender
 * 先于子组件执行），列表容器只负责滚动与裁剪，因此不存在"容器自动排列覆盖动画位置"的问题。</p>
 *
 * @author Codex
 */
public class PlayQueuePanel extends NCMPanel {

    /** 行高与槽距。包内可见是给 {@code PlayQueueDragStaticTest} 的：那条夹取要按「一整行留在视口里」
     *  断言，把这两个数在测试里再抄一份迟早和实现对不上。 */
    static final double ROW_HEIGHT = 26.0;
    private static final double ROW_GAP = 2.0;
    static final double SLOT_HEIGHT = ROW_HEIGHT + ROW_GAP;
    private static final double HEADER_HEIGHT = 20.0;
    private static final double DRAWER_INSET = 8.0;
    private static final double DRAWER_PADDING = 6.0;
    /** 展开时逐行错峰出现的间隔，让整列像被铺开而不是整块闪现。 */
    private static final long ROW_STAGGER_MILLIS = 45L;
    private static final long ROW_FADE_MILLIS = 190L;
    /** 「排列」按钮的宽度，与「清空」之间同样隔 4px。 */
    private static final double FLOW_BUTTON_WIDTH = 34.0;
    /** 排列结果那一句话在提示位上停留多久。 */
    private static final long FLOW_NOTICE_MILLIS = 4_000L;
    /**
     * 抽屉展开/收起各自的基准毫秒（再除以「动画速度」倍率）。
     *
     * <p>默认倍率是 3.0，也就是最快那一档，所以这两个数要按<b>除完</b>那一档估观感：
     * 展开约 260ms、收起约 140ms。原来那条 {@code Interpolations.interpolate(…, .32)}
     * 在 60fps 下约 135ms 走完，但它没有时长也没有终点，掉到 30fps 整段翻倍。</p>
     */
    private static final double OPEN_MS = 780.0;
    private static final double CLOSE_MS = 420.0;
    /** 单帧最多补这么多毫秒：与换页、侧栏高亮条同一档。 */
    private static final double MAX_FRAME_MS = 40.0;
    /** 提起来的这一行放大多少（相对自身），{@link QueueRow} 的变换与裁剪外扩都用这一个数。 */
    private static final double DRAG_LIFT_SCALE = .045;
    /** 指针停在列表上下多少像素以内开始自动滚动。 */
    private static final double DRAG_EDGE = 14.0;
    /** 自动滚动的速度，像素/秒（越靠近边缘越快）。原来是每帧 2.5 像素：220fps 下是 550px/s。 */
    private static final double DRAG_AUTO_SCROLL_PPS = 320.0;

    private boolean open;
    /** 这一帧抽屉是否处于一起听房间模式（显示房间歌单而非本地待播段）。 */
    private boolean roomMode;
    /** 房间模式的数据源：快照、乐观顺序与协议落地都在那一层。 */
    private final PlayQueueRoomBridge room = new PlayQueueRoomBridge();
    /** 抽屉滑入/滑出的进度，0 为完全收起、1 为完全展开。线性推进，曲线在读的时候才套。 */
    private double openAnimation;
    private long lastFrameNanos;
    /**
     * 触发这颗抽屉的按钮在<b>本面板坐标系</b>里的右边缘，由 {@code ControlsBar} 每帧推进来。
     *
     * <p>抽屉原来写死相对内容区居中，和那颗按钮零几何关系 —— 于是点开之后视线要从按钮横穿
     * 半屏去找面板。现在右边缘贴着按钮，展开也就像是从按钮那里长出来的。</p>
     */
    private double anchorRight = Double.NaN;

    private final RoundedRectWidget drawer = new RoundedRectWidget();
    private final QueueList list = new QueueList();
    private LabelWidget emptyHint;

    /** 界面上的行，顺序与用户队列一致。 */
    private final List<QueueRow> rows = new ArrayList<>();
    /** 已经离开队列、正在淡出的行；它们不参与排位，只是别让列表凭空少一行。 */
    private final List<QueueRow> fadingRows = new ArrayList<>();
    /**
     * 「排列」按下去之后的那一句回显，以及它该消失的时刻（墙钟毫秒）。
     *
     * <p>回显借用表头那条「拖动可调整顺序」的位置：抽屉里没有第二个能放一句话的地方，
     * 而为一条临时提示再加一个组件，就要再算一次它和「清空」「×」的避让关系。</p>
     */
    private String flowNotice = "";
    private long flowNoticeUntil;
    /**
     * 后台排列每出一句新话就把这个序号 +1；界面靠「序号变了」而不是「文案变了」来重新计那 4 秒。
     *
     * <p>每帧刷倒计时会让那句结论永远不消失，而结论到货的时刻又不在本帧里 —— 只有批次自己知道
     * 什么时候换了一句话。</p>
     */
    private long flowNoticeSerial;
    /** 「排列 / 取消」那颗按钮此刻写的字，只在真的换字时才 setLabel。 */
    private String flowSortLabel = "排列";
    /** 提示标签上当前那句文案；只在真的换字时才动它，避免每帧重建一个 supplier。 */
    private String hintText = "拖动可调整顺序";
    /** 空状态那句话的当前文案；与提示标签同一个换字套路。 */
    private String emptyHintText = "队列为空 · 点击歌曲右侧的排队按钮加入";

    private QueueRow draggingRow;
    private double dragGrabOffset;
    /**
     * 提起来的那一行超出列表框多少：放大 {@link #DRAG_LIFT_SCALE} 之后，行会比列表还宽一圈，
     * 而列表用 scissor/stencil 把子组件裁在自己的框里 —— 于是被拖的这张「卡片」左右两边各被
     * 削掉约 3 像素。裁剪矩形按这个量外扩，行就完整了；外扩上限是抽屉自己的内边距，绝不越出抽屉。
     */
    private double dragOverhangX;
    private double dragOverhangY;

    /** 平滑线性进度，让起步与收尾都不生硬。 */
    private static double smooth(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /** 表头标题：本地是「下一首播放」，一起听房间里换成名副其实的「一起听歌单」。 */
    private String queueTitle() {
        return this.roomMode ? "一起听歌单" : "下一首播放";
    }

    public boolean isOpen() {
        return this.open;
    }

    /**
     * 由 {@code ControlsBar} 每帧推进来：触发这颗抽屉的按钮在本面板坐标系里的右边缘。
     *
     * <p>两者共用同一个 x 原点（都按 {@code currentPanelBg.getX()} 摆），所以按钮的绝对 x
     * 减去面板的绝对 x 就是本面板坐标系里的位置。</p>
     */
    public void setAnchorRight(double right) {
        this.anchorRight = right;
    }

    /** 收起动画播完前仍然需要渲染，否则关闭会变成瞬间消失。 */
    public boolean isVisible() {
        return this.open || this.openAnimation > .004;
    }

    public void toggle() {
        this.setOpen(!this.open);
    }

    public void setOpen(boolean open) {
        if (this.open == open) return;
        this.open = open;

        if (!open) {
            this.cancelDrag();
            return;
        }

        this.list.scrollTarget = 0.0;
        this.list.scrollActual = 0.0;
        long now = System.currentTimeMillis();
        for (int slot = 0; slot < this.rows.size(); slot++) {
            QueueRow row = this.rows.get(slot);
            // 起始时间放在未来，靠后的行就会自己晚一点出现。
            row.appearedAt = now + slot * ROW_STAGGER_MILLIS;
            row.animation.setPosition(slot * SLOT_HEIGHT + 8.0);
        }
    }

    @Override
    public void onInit() {
        this.getChildren().clear();
        this.list.getChildren().clear();
        this.rows.clear();
        this.fadingRows.clear();
        this.draggingRow = null;
        this.list.scrollTarget = 0.0;
        this.list.scrollActual = 0.0;

        this.addChild(this.drawer);
        this.drawer.setGlassSheet(GlassLook.Surface.PLAYER);
        this.drawer
                .setShouldOverrideMouseCursor(false)
                .setBeforeRenderCallback(() -> this.drawer
                        .setRadius(9.0)
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                        .setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f)));
        // 抽屉盖住了下面的歌曲列表，落在空白处的点击必须由它吃掉，不能穿透触发播放。
        this.drawer.setOnClickCallback((relativeX, relativeY, mouseButton) -> true);

        LabelWidget title = new LabelWidget(() -> this.rows.isEmpty()
                ? this.queueTitle()
                : (this.queueTitle() + " · " + this.rows.size() + " 首"), FontManager.pf12bold);
        this.drawer.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(DRAWER_PADDING + 2.0,
                        DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - title.getHeight()) * .5)));

        LabelWidget hint = new LabelWidget("拖动可调整顺序", FontManager.pf10bold);
        this.drawer.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> {
            // 刚按过「排列」就把这句换成结果，几秒后自动换回来。换文案而不是加组件：
            // 表头只有 20px 高，挤不进第三个东西，而每条临时提示都要重新算一遍与
            // 「清空」「×」的避让关系——那才是这个抽屉里最容易错位的代码。
            //
            // 排列已经改到后台跑了，所以这里每帧读一次它的状态：正在扫就显示进度，
            // 扫完显示那一句结论（靠序号判断是不是新结论，不能每帧刷新倒计时）。
            // 房间模式下没有「排列」这回事，提示位让给房间那句（能不能拖、谁在控制顺序）。
            String wanted;
            if (this.roomMode) {
                wanted = this.room.hint();
            } else {
                long serial = com.muoniumplayer.core.ncm.music.QueueFlowSorter.noticeSerial();
                if (serial != this.flowNoticeSerial) {
                    this.flowNoticeSerial = serial;
                    this.flowNotice = com.muoniumplayer.core.ncm.music.QueueFlowSorter.lastNotice();
                    this.flowNoticeUntil = System.currentTimeMillis() + FLOW_NOTICE_MILLIS;
                }
                if (com.muoniumplayer.core.ncm.music.QueueFlowSorter.isRunning()) {
                    wanted = com.muoniumplayer.core.ncm.music.QueueFlowSorter.notice();
                } else if (System.currentTimeMillis() < this.flowNoticeUntil) {
                    wanted = this.flowNotice;
                } else {
                    wanted = "拖动可调整顺序";
                }
            }
            if (!wanted.equals(this.hintText)) {
                this.hintText = wanted;
                hint.setLabel(wanted);
            }
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(title.getRelativeX() + title.getWidth() + 8.0,
                            DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - hint.getHeight()) * .5));
        });

        // 进阶模式的「一键排列」：只在总开关开着时出现。关掉它就等于这个功能不存在——
        // 而不是留一个按下去什么都不做的按钮。
        RoundedRectWidget flowSort = new RoundedRectWidget();
        this.drawer.addChild(flowSort);
        flowSort.setShouldOverrideMouseCursor(true);
        flowSort.setBeforeRenderCallback(() -> {
            // 少于两首待播没有「相邻」可言，与「清空」同一个做法：连同命中框一起收掉。
            // 正在排的时候不收：那时它写的是「取消」，收掉就没法停了。
            // 房间模式下排的是别人的队列（本地段在房间里永远为空），整个功能不存在。
            flowSort.setHidden(this.roomMode
                    || (!com.muoniumplayer.core.ncm.music.QueueFlowSorter.isRunning()
                    && (this.rows.size() < 2
                    || !com.muoniumplayer.core.ncm.music.AutomixSettings.isAdvancedModeEnabled())));
            flowSort.setBounds(FLOW_BUTTON_WIDTH, 14.0)
                    .setPosition(this.drawer.getWidth() - DRAWER_PADDING - 16.0 - 4.0 - 34.0
                                    - 4.0 - FLOW_BUTTON_WIDTH,
                            DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - 14.0) * .5))
                    .setRadius(3.0)
                    .setColor(flowSort.isHovering()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        flowSort.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                // 正在拖的时候先取消拖：一次排列会整段改顺序，而拖拽记的是<b>按下时</b>的那一格，
                // 不先收掉的话松手会按旧下标再搬一次，把刚排好的顺序搅乱。
                this.cancelDrag();
                // 跑着的时候这一记点击是「别排了」；空闲时才是一次新请求。两句都走
                // QueueFlowSorter 的结论通道（序号一变提示条自己会显示 4 秒），这里不自己存文案。
                if (!com.muoniumplayer.core.ncm.music.QueueFlowSorter.cancel()) {
                    com.muoniumplayer.core.ncm.music.QueueFlowSorter.request();
                }
            }
            return true;
        });

        LabelWidget flowSortText = new LabelWidget("排列", FontManager.pf10bold);
        flowSort.addChild(flowSortText);
        flowSortText.setClickable(false);
        flowSortText.setBeforeRenderCallback(() -> {
            // 同一颗按钮两种意思，写在字上而不是再加一颗：表头那一排已经挤满了
            // 「标题 / 提示 / 排列 / 清空 / ×」，多一颗就得重新算一遍避让。
            String wanted = com.muoniumplayer.core.ncm.music.QueueFlowSorter.isRunning()
                    ? "取消" : "排列";
            if (!wanted.equals(this.flowSortLabel)) {
                this.flowSortLabel = wanted;
                flowSortText.setLabel(wanted);
            }
            flowSortText.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .center();
        });

        RoundedRectWidget clearAll = new RoundedRectWidget();
        this.drawer.addChild(clearAll);
        clearAll.setShouldOverrideMouseCursor(true);
        clearAll.setBeforeRenderCallback(() -> {
            // 队列为空时没有可清空的内容，按钮连同命中框一起收掉。
            // 房间模式里清空是发全房的一条指令：清空权跟着房间面板「清空歌单」同一个判据，
            // 没有控制权的成员根本不该看到这颗按钮。
            clearAll.setHidden(this.rows.isEmpty() || (this.roomMode && !this.room.canClear()));
            clearAll.setBounds(34.0, 14.0)
                    .setPosition(this.drawer.getWidth() - DRAWER_PADDING - 16.0 - 4.0 - 34.0,
                            DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - 14.0) * .5))
                    .setRadius(3.0)
                    .setColor(clearAll.isHovering()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        clearAll.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0 && !this.rows.isEmpty()) {
                this.cancelDrag();
                if (this.roomMode) this.room.clear();
                else CloudMusic.clearQueuedNextSongs();
            }
            return true;
        });

        LabelWidget clearAllText = new LabelWidget("清空", FontManager.pf10bold);
        clearAll.addChild(clearAllText);
        clearAllText.setClickable(false);
        clearAllText.setBeforeRenderCallback(() -> clearAllText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .center());

        RoundedRectWidget close = new RoundedRectWidget();
        this.drawer.addChild(close);
        close.setShouldOverrideMouseCursor(true);
        close.setBeforeRenderCallback(() -> close
                .setBounds(16.0, 14.0)
                .setPosition(this.drawer.getWidth() - DRAWER_PADDING - 16.0,
                        DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - 14.0) * .5))
                .setRadius(3.0)
                .setColor(close.isHovering()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
        close.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) this.setOpen(false);
            return true;
        });

        LabelWidget closeText = new LabelWidget("×", FontManager.pf12bold);
        close.addChild(closeText);
        closeText.setClickable(false);
        closeText.setBeforeRenderCallback(() -> closeText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .center());

        this.drawer.addChild(this.list);

        this.emptyHint = new LabelWidget("队列为空 · 点击歌曲右侧的排队按钮加入", FontManager.pf10bold);
        this.drawer.addChild(this.emptyHint);
        this.emptyHint.setClickable(false);
        this.emptyHint.setBeforeRenderCallback(() -> {
            // 房间模式那句空提示是另一句人话（能不能点歌、去哪点），每帧按模式换文案而不是加组件。
            String wanted = this.roomMode ? this.room.emptyHint()
                    : "队列为空 · 点击歌曲右侧的排队按钮加入";
            if (!wanted.equals(this.emptyHintText)) {
                this.emptyHintText = wanted;
                this.emptyHint.setLabel(wanted);
            }
            this.emptyHint
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(this.list.getRelativeX() + Math.max(0.0, (this.list.getWidth() - this.emptyHint.getWidth()) * .5),
                            this.list.getRelativeY() + Math.max(0.0, (this.list.getHeight() - this.emptyHint.getHeight()) * .5));
        });
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        long clockNow = System.nanoTime();
        double frameMs = this.lastFrameNanos == 0L ? 0.0
                : Math.min(MAX_FRAME_MS, (clockNow - this.lastFrameNanos) / 1_000_000.0);
        this.lastFrameNanos = clockNow;
        // 线性推进到 0 或 1，曲线留到用的时候再套：中途反悔（连点开关）就从当前位置往回走，
        // 而不是先跳回端点重来。
        double duration = Math.max(1.0, (this.open ? OPEN_MS : CLOSE_MS) / SheetClock.speed());
        this.openAnimation = Math.max(0.0, Math.min(1.0,
                this.openAnimation + (this.open ? frameMs : -frameMs) / duration));

        if (!this.isVisible()) {
            this.drawer.setHidden(true);
            this.cancelDrag();
            return;
        }
        this.drawer.setHidden(false);

        // 进房/退房随时发生，模式必须每帧判定；判定本身只读两个 volatile 布尔，零开销。
        this.roomMode = PlayQueueRoomBridge.roomActive();
        this.syncRows();

        double progress = smooth(this.openAnimation);
        // 抽屉走的是位移，不是缩放：表头与列表是抽屉的子组件，缩抽屉的 bounds 不会缩它们，
        // 半路上内容就会溢出到圆角外面。所以这里只平移，让前缘（上沿）带一点过冲。
        double lead = TransitionCurveCache.SPRING_LAND.at(this.openAnimation);
        double panelWidth = this.getWidth();
        double panelHeight = this.getHeight();
        // 抽屉收窄到不到一半，并把右边缘贴到触发它的那颗按钮上：原来它相对内容区居中，
        // 和按钮零几何关系，点开之后视线要从按钮横穿半屏去找这块面板。
        double drawerWidth = Math.max(150.0,
                Math.min(panelWidth - DRAWER_INSET * 2.0, panelWidth * .46));
        // 锚点进来的是那枚按钮的绝对 x，而这一层所有坐标都是「相对本面板」的：不减掉面板自己的
        // 绝对 x，抽屉就会整体往右跑一个面板宽度的距离（客户端里量过一次，右边缘落在按钮右边约 300）。
        double drawerRight = Double.isNaN(this.anchorRight)
                ? (panelWidth - drawerWidth) * .5 + drawerWidth               // 没收到锚点就退回居中
                : Math.max(DRAWER_INSET + drawerWidth,
                Math.min(panelWidth - DRAWER_INSET, this.anchorRight - this.getX()));

        int rowCount = this.rows.size();
        double contentHeight = Math.max(0.0, rowCount * SLOT_HEIGHT - ROW_GAP);
        double maxListHeight = Math.max(SLOT_HEIGHT,
                panelHeight * .60 - (DRAWER_PADDING * 2.0 + HEADER_HEIGHT));
        double listHeight = rowCount == 0
                ? 26.0
                : Math.max(SLOT_HEIGHT, Math.min(maxListHeight, contentHeight));
        double drawerHeight = DRAWER_PADDING * 2.0 + HEADER_HEIGHT + listHeight;
        // 收起时整块滑到内容区下沿之外，由内容区的裁剪吃掉，看起来就是抽屉缩回底部；
        // 前缘走过冲曲线，于是它会稍微弹过头一点再落回槽位。
        double drawerBottom = panelHeight - 4.0 + (1.0 - lead) * (drawerHeight + 8.0);

        this.drawer
                .setBounds(drawerWidth, drawerHeight)
                .setPosition(drawerRight - drawerWidth, drawerBottom - drawerHeight)
                .setAlpha((float) (.99f * (.25 + .75 * progress)));

        this.list
                .setBounds(Math.max(0.0, drawerWidth - DRAWER_PADDING * 2.0), listHeight)
                .setPosition(DRAWER_PADDING, DRAWER_PADDING + HEADER_HEIGHT);
        this.list.contentHeight = contentHeight;
        this.emptyHint.setAlpha(rowCount == 0 ? 1.0f : 0.0f);

        this.updateDrag(mouseX, mouseY, frameMs);
        this.maybeBeginDragByLongPress();

        double frameDelta = RenderSystem.getFrameDeltaTime();
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < rowCount; slot++) {
            QueueRow row = this.rows.get(slot);
            row.slot = slot;

            if (row == this.draggingRow) {
                // 拖动中的行 1:1 跟着鼠标，松手后弹簧从当前位置接着走，不会跳。
                row.animation.setPosition(row.dragY);
            } else {
                row.animation.setTargetPosition(slot * SLOT_HEIGHT);
            }
            row.animation.update(frameDelta * .0125);
            row.lift = Interpolations.interpolate(row.lift, row == this.draggingRow ? 1.0 : 0.0, .38);
            this.applyRowLayout(row, now);
        }

        for (int index = this.fadingRows.size() - 1; index >= 0; index--) {
            QueueRow row = this.fadingRows.get(index);
            row.fade = Interpolations.interpolate(row.fade, 0.0, .55);
            row.lift = Interpolations.interpolate(row.lift, 0.0, .38);
            row.animation.update(frameDelta * .0125);
            this.applyRowLayout(row, now);

            if (row.fade <= .02) {
                this.list.getChildren().remove(row);
                this.fadingRows.remove(index);
            }
        }

        // 裁剪外扩量跟着抬起程度走，所以排在 lift 这一帧插值之后算。没在拖的时候是 0，
        // 列表的裁剪和以前一模一样。父 onRender 先于子组件渲染，这里写完列表这帧就能用上。
        // 取的是「所有行里抬得最高的那一行」而不是「正在被拖的那一行」：松手之后 draggingRow
        // 立刻清空，可那一行还要带着放大后的尺寸回落好几帧，按被拖的那行算就会在松手的一瞬间
        // 把它重新削掉一截。
        double lifted = 0.0;
        for (QueueRow row : this.rows) lifted = Math.max(lifted, row.lift);
        for (QueueRow row : this.fadingRows) lifted = Math.max(lifted, row.lift);
        this.dragOverhangX = Math.min(DRAWER_PADDING, this.list.getWidth() * DRAG_LIFT_SCALE * lifted * .5);
        this.dragOverhangY = Math.min(DRAWER_PADDING, ROW_HEIGHT * DRAG_LIFT_SCALE * lifted * .5);
    }

    /** 把一行搬到它这一帧该在的位置，并处理视口内外的可见性与命中框。 */
    private void applyRowLayout(QueueRow row, long now) {
        // 房间模式下每一行的两枚手势控件（拖动把手、移除）按权限逐帧开合；正在淡出的行
        // 两个都关掉，别让一个已经不在歌单里的条目还能被点出一条指令。
        if (this.roomMode && !row.removing) {
            row.canDrag = this.room.canReorder();
            row.canRemove = this.room.canRemove(row.slot);
        } else {
            row.canDrag = !row.removing;
            row.canRemove = !row.removing;
        }

        double y = row.animation.getCurrentPosition() - this.list.scrollActual;
        row.setBounds(this.list.getWidth(), ROW_HEIGHT).setPosition(0.0, y);

        boolean offscreen = y + ROW_HEIGHT < -.5 || y > this.list.getHeight() + .5;
        boolean fullyInside = y >= -.5 && y + ROW_HEIGHT <= this.list.getHeight() + .5;
        // 手里那一行永远要看得见：它被 {@link #updateDrag} 夹在视口里，正常不会走到这条分支，
        // 但一旦因为边界情况被判成「滚出去了」，表现就是拖着拖着行凭空消失，比裁切还糟。
        boolean dragging = row == this.draggingRow;
        row.setHidden(offscreen && !dragging);
        row.setClickable((fullyInside || dragging) && !row.removing);

        double entrance = row.removing ? 1.0 : Math.max(0.0, Math.min(1.0,
                (now - row.appearedAt) / (double) ROW_FADE_MILLIS));
        row.setAlpha((float) (row.fade * smooth(entrance)));
    }

    /** 用队列快照对齐界面上的行，尽量复用同一个行对象，动画状态才不会被重建打断。 */
    private void syncRows() {
        List<Music> queued = this.roomMode ? this.room.snapshot() : CloudMusic.getQueuedNextSongs();

        // 拖动途中队列长度变了（比如这首歌播完了），继续按旧下标搬动就会错位，直接放手最安全。
        // 房间模式下这条同样成立：别人点歌/撤歌都会让房间歌单变长变短。
        if (this.draggingRow != null && queued.size() != this.rows.size()) {
            this.cancelDrag();
        }

        if (this.matchesRows(queued)) return;

        List<QueueRow> reusable = new ArrayList<>(this.rows);
        List<QueueRow> next = new ArrayList<>(queued.size());
        long now = System.currentTimeMillis();

        for (int index = 0; index < queued.size(); index++) {
            Music song = queued.get(index);
            QueueRow row = takeMatching(reusable, song);

            if (row == null) {
                row = new QueueRow(song);
                row.appearedAt = now;
                // 新行从下方稍微滑上来，插入时有一个明确的"进来了"的动作。
                row.animation.setPosition(index * SLOT_HEIGHT + SLOT_HEIGHT * .55);
                this.list.addChild(row);
            }

            row.slot = index;
            next.add(row);
        }

        for (QueueRow leftover : reusable) {
            leftover.removing = true;
            leftover.setClickable(false);
            if (leftover == this.draggingRow) this.cancelDrag();
            if (!this.fadingRows.contains(leftover)) this.fadingRows.add(leftover);
        }

        this.rows.clear();
        this.rows.addAll(next);
    }

    private boolean matchesRows(List<Music> queued) {
        if (queued.size() != this.rows.size()) return false;
        for (int index = 0; index < queued.size(); index++) {
            QueueRow row = this.rows.get(index);
            if (row.removing) return false;
            Music song = queued.get(index);
            if (row.music != song && (row.music == null || !row.music.equals(song))) return false;
        }
        return true;
    }

    private static QueueRow takeMatching(List<QueueRow> pool, Music song) {
        for (int index = 0; index < pool.size(); index++) {
            if (pool.get(index).music == song) return pool.remove(index);
        }
        for (int index = 0; index < pool.size(); index++) {
            QueueRow row = pool.get(index);
            if (row.music != null && song != null && row.music.equals(song)) return pool.remove(index);
        }
        return null;
    }

    /**
     * 长按抓取排序：按住一行不动超过 {@link TouchSettings#LONG_PRESS_MS} 且<b>尚未锁轴</b>
     * （一动就会变成滚动或横向拖动，长按边沿随之作废）时，把那一行抓起来交给既有的
     * {@link #updateDrag} 接管。之后手指怎么动都由行拖动自己消费，QueueList 的触摸滚动
     * 见到 {@code draggingRow != null} 会主动让位。
     *
     * <p>抓取落点按<b>按压起点</b>换算行与抓取偏移——长按的前提是没动过，起点≈当前点，
     * 而 updateDrag 期望的 grabOffset 语义是「指针在该行内的相对 Y」（旧点击路径里就是
     * 点击的 relativeY）。</p>
     */
    private void maybeBeginDragByLongPress() {
        if (!TouchSettings.ENABLED || this.draggingRow != null) {
            return;
        }
        TouchGestures gestures = TouchGestures.get();
        if (!gestures.longPressElapsed() || !gestures.isPressed()) {
            return;
        }
        // 按压起点换算到列表内容坐标（updateDrag 同一套换算的逆推），落不进任何一行就不抓。
        double contentY = gestures.startY() - this.list.getY() + this.list.scrollActual;
        if (contentY < 0.0) {
            return;
        }
        int slot = (int) Math.floor(contentY / SLOT_HEIGHT);
        if (slot < 0 || slot >= this.rows.size()) {
            return;
        }
        QueueRow row = this.rows.get(slot);
        if (row == null || row.removing || !row.canDrag) {
            return;
        }
        this.beginDrag(row, gestures.startY() - row.getY());
    }

    private void beginDrag(QueueRow row, double grabOffsetY) {
        if (row == null || row.removing || !this.rows.contains(row)) return;
        // 房间模式里没有控制权的成员、以及只读的官方房间歌单，按住一行不该有任何反应。
        if (this.roomMode && !this.room.canReorder()) return;
        this.draggingRow = row;
        this.dragGrabOffset = grabOffsetY;
        row.dragY = row.animation.getCurrentPosition();
        // 子组件顺序就是渲染顺序：把被拖的行挪到最后，它才会浮在其它行之上。
        this.list.getChildren().remove(row);
        this.list.getChildren().add(row);
    }

    private void updateDrag(double mouseX, double mouseY, double frameMs) {
        QueueRow row = this.draggingRow;
        if (row == null) return;

        // 松手是一次完成（房间模式下这里才把最终位置发给服务器）；行被移除、被对齐掉
        // 都是中途夭折，不发。两种结局分开，别把「提交」混进 cancelDrag 里——
        // 收起抽屉、点「排列」这些地方也在调它。
        boolean released = !Mouse.isButtonDown(0);
        if (released || row.removing || !this.rows.contains(row)) {
            this.endDrag(released);
            return;
        }

        int rowCount = this.rows.size();
        double contentHigh = Math.max(0.0, (rowCount - 1) * SLOT_HEIGHT);
        double grow = ROW_HEIGHT * DRAG_LIFT_SCALE * row.lift * .5;
        double desired = mouseY - this.list.getY() + this.list.scrollActual - this.dragGrabOffset;
        row.dragY = clampDragToViewport(desired, this.list.scrollActual, this.list.getHeight(),
                contentHigh, grow);

        // 拖到列表上下边缘时自动滚动，长队列才能一路拖到头或拖到底。速度按毫秒折算：
        // 原来写的是「每帧 2.5 像素」，220fps 下是 550px/s、60fps 下只有 150px/s，帧率一改手感就变。
        double top = this.list.getY();
        double bottom = top + this.list.getHeight();
        double scrollStep = DRAG_AUTO_SCROLL_PPS * frameMs / 1000.0;
        if (mouseY < top + DRAG_EDGE) {
            this.list.scrollTarget -= scrollStep * edgeStrength(top + DRAG_EDGE - mouseY);
        } else if (mouseY > bottom - DRAG_EDGE) {
            this.list.scrollTarget += scrollStep * edgeStrength(mouseY - bottom + DRAG_EDGE);
        }

        int from = this.rows.indexOf(row);
        int target = (int) Math.round(row.dragY / SLOT_HEIGHT);
        target = Math.max(0, Math.min(rowCount - 1, target));

        // 本地模式每一格换位都直接写进 playList；房间模式只动暂存顺序（松手才发一条 move），
        // 但两边的返回值语义相同：顺序真的变了才让界面跟着搬行。
        if (target != from && this.moveDisplayRow(from, target)) {
            this.rows.remove(from);
            this.rows.add(target, row);
        }
    }

    /** 把一次换位落到当前模式的数据源上：本地写活队列，房间写暂存。 */
    private boolean moveDisplayRow(int from, int to) {
        return this.roomMode ? this.room.move(from, to) : CloudMusic.moveQueuedNext(from, to);
    }

    /**
     * 一次拖动的两种结局。松手（{@code commit}）时房间模式把暂存顺序发给服务器——
     * 本地模式没有任何收尾要做，它的每一格换位在拖动途中就已经写进队列了。
     */
    private void endDrag(boolean commit) {
        QueueRow row = this.draggingRow;
        this.draggingRow = null;
        if (this.roomMode) {
            if (commit && row != null) this.room.commitMove(row.music);
            else this.room.rollbackMove();
        }
    }

    /** 越出边缘带多少就使多大劲：刚进带子是四分之一速，越到边界外越满。 */
    private static double edgeStrength(double over) {
        return .25 + .75 * Math.max(0.0, Math.min(1.0, over / DRAG_EDGE));
    }

    /**
     * 把被拖那一行的<b>内容</b>坐标夹进「此刻看得见的那一段内容」里。
     *
     * <p>行在屏幕上的位置是 {@code dragY - scroll}，所以只按内容范围 {@code [0, contentHigh]} 夹
     * 是不够的：边缘自动滚动一把内容挪走，或者指针冲得比滚动快，行就会跑到视口之外，被列表的
     * scissor 削掉半张卡片，更糟的是被 {@link #applyRowLayout} 判成「完全滚出视口」而整行隐藏 ——
     * 手里那张卡片就这么凭空消失，松手才发现自己拖了个空。</p>
     *
     * <p>两段范围取交集：内容范围保证下标换算不出界，视口范围保证看得见。空集时（比如只剩一行）
     * 退化成视口下界，行仍然钉在看得见的位置上。{@code overhang} 是提起来后放大溢出的一边，
     * 留出来才不会被裁剪矩形削到。</p>
     *
     * <p>包内可见是给 {@code PlayQueueDragStaticTest} 的：这一段是纯算术，也正是「手里的行会不会
     * 消失」这个问题的全部答案，能在内存里真跑，不该只留在源码里被字符串比对。</p>
     */
    static double clampDragToViewport(double desired, double scroll, double viewportHeight,
                                      double contentHigh, double overhang) {
        double viewLow = scroll + overhang;
        double viewHigh = scroll + Math.max(0.0, viewportHeight - ROW_HEIGHT - overhang);
        double low = Math.max(0.0, Math.min(contentHigh, viewLow));
        double high = Math.max(low, Math.min(contentHigh, viewHigh));
        return Math.max(low, Math.min(high, desired));
    }

    /**
     * 把某一行从用户队列里移除。位置按界面上的行序换算成队列下标，移除成功后本帧就把它
     * 交给淡出列表，界面不会等到下一次快照对齐才有反馈。
     */
    private void removeRow(QueueRow row) {
        int index = this.rows.indexOf(row);
        if (index < 0 || row.removing) return;
        // 房间模式先提交移除、再断拖动：cancelDrag 会回滚未提交的拖动顺序，顺序颠倒的话
        // 回滚就发生在「按用户看到的顺序取下标」之后，删掉的不再是用户指着的那一行。
        boolean removed = this.roomMode ? this.room.remove(index) : CloudMusic.removeQueuedNext(index);
        if (row == this.draggingRow) this.cancelDrag();
        if (!removed) return;

        this.rows.remove(index);
        row.removing = true;
        row.setClickable(false);
        if (!this.fadingRows.contains(row)) this.fadingRows.add(row);
    }

    private void cancelDrag() {
        this.draggingRow = null;
        // 房间模式下没发出去的拖动顺序一并退掉：中断的拖动不该在界面上留下一份
        // 服务器从来没有过的顺序（回滚只动未提交的移动，已发的移除/清空不受影响）。
        if (this.roomMode) this.room.rollbackMove();
    }

    /**
     * 抽屉范围内的点击由它自己处理，并且不再往下传。
     * @return 是否吃掉了这次点击
     */
    public boolean consumeClick(double mouseX, double mouseY, int mouseButton) {
        if (!this.open || this.drawer.isHidden()) return false;
        if (!this.isHovered(mouseX, mouseY, this.drawer.getX(), this.drawer.getY(),
                this.drawer.getWidth(), this.drawer.getHeight())) {
            return false;
        }
        this.onMouseClickReceived(mouseX, mouseY, mouseButton);
        return true;
    }

    /** 滚轮停在抽屉里时，下面的歌单不能跟着一起滚。 */
    public boolean capturesWheel(double mouseX, double mouseY) {
        if (!this.open || this.drawer.isHidden()) return false;
        return this.isHovered(mouseX, mouseY, this.drawer.getX(), this.drawer.getY(),
                this.drawer.getWidth(), this.drawer.getHeight());
    }

    private static boolean loadCover(Music music) {
        TextureManager textureManager = TextureManager.getInstance();
        Location coverLocation = music.getSmallCoverLocation();
        if (textureManager.getTexture(coverLocation) != null) return true;

        String url = music.getCoverUrl(64);
        if (url == null || url.trim().isEmpty()) {
            // GD 封面仍在异步预取：下一帧再试，不要对空串发起无效下载。
            return false;
        }
        Textures.downloadTextureAndLoadAsync(url, coverLocation);
        return true;
    }

    /**
     * 队列列表容器：只管滚动、裁剪和滚轮，不自动排列子组件——行的位置由
     * {@link PlayQueuePanel#onRender(double, double)} 决定。
     */
    private final class QueueList extends AbstractWidget<QueueList> {

        private double scrollTarget;
        private double scrollActual;
        private double contentHeight;

        /** 本轮纵向拖动是否由本列表认领（行抓取排序期间必须让位）。 */
        private boolean touchDragging;

        /** 甩动的最小速度（GUI 像素/ms）：低于它的松手只停住，不给惯性。 */
        private static final double QUEUE_FLING_MIN_VELOCITY = 0.05;

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.advanceTouchDrag();
            double max = Math.max(0.0, this.contentHeight - this.getHeight());
            this.scrollTarget = Math.max(0.0, Math.min(this.scrollTarget, max));
            this.scrollActual = Interpolations.interpolate(this.scrollActual, this.scrollTarget, 1.0);
            this.scrollActual = Math.max(0.0, Math.min(this.scrollActual, max));
        }

        /**
         * 触屏拖动滚动（符号与滚轮一致：偏移 0 = 顶，手指向下 dy&gt;0 = {@code target -= dy}）。
         * 与 ScrollPanel 的同款逻辑分开写而不是抽公共类：这里滚动状态是裸字段、
         * 抓取排序还要参与互斥，抽象反而把两处差异藏进参数里。
         */
        private void advanceTouchDrag() {
            TouchGestures gestures = TouchGestures.get();
            if (!TouchSettings.ENABLED || PlayQueuePanel.this.draggingRow != null) {
                this.touchDragging = false;
                return;
            }
            if (this.touchDragging) {
                if (gestures.isVerticalDrag() && gestures.claimedBy() == this) {
                    double dy = gestures.dy();
                    if (dy != 0) {
                        double max = Math.max(0.0, this.contentHeight - this.getHeight());
                        this.scrollTarget = Math.max(0.0, Math.min(this.scrollTarget - dy, max));
                        this.scrollActual = this.scrollTarget;
                    }
                    return;
                }
                this.touchDragging = false;
                if (!gestures.isPressed()) {
                    double velocity = gestures.velocityY();
                    if (Math.abs(velocity) > QUEUE_FLING_MIN_VELOCITY) {
                        double distance = Math.min(TouchSettings.FLING_MAX_DISTANCE,
                                (velocity * velocity) / (2.0 * TouchSettings.FLING_DECEL));
                        double max = Math.max(0.0, this.contentHeight - this.getHeight());
                        this.scrollTarget = Math.max(0.0, Math.min(
                                this.scrollTarget - Math.signum(velocity) * distance, max));
                    }
                }
                return;
            }
            if (gestures.isVerticalDrag()
                    && gestures.claimedBy() == null
                    && this.contentHeight - this.getHeight() > 0.0
                    && this.testHovered(gestures.startX(), gestures.startY())) {
                this.touchDragging = gestures.claim(this);
            }
        }

        @Override
        public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
            if (dWheel > 0) this.scrollTarget -= SLOT_HEIGHT;
            else if (dWheel < 0) this.scrollTarget += SLOT_HEIGHT;

            double max = Math.max(0.0, this.contentHeight - this.getHeight());
            this.scrollTarget = Math.max(0.0, Math.min(this.scrollTarget, max));
            return true;
        }

        @Override
        public boolean canBeScrolled() {
            return true;
        }

        @Override
        protected boolean shouldClickChildren(double mouseX, double mouseY) {
            return this.testHovered(mouseX, mouseY);
        }

        @Override
        protected void beforeRenderChildren(double mouseX, double mouseY) {
            // 裁剪矩形跟着被提起来的那一行外扩：放大之后的行比列表宽一圈，不扩就会被削掉左右两边。
            // 上限是抽屉自己的内边距（父面板里已经夹过一次），所以最多是长到抽屉边缘为止。
            double inflateX = PlayQueuePanel.this.dragOverhangX;
            double inflateY = PlayQueuePanel.this.dragOverhangY;
            double x = this.getX() - inflateX;
            double y = this.getY() - inflateY;
            double width = this.getWidth() + inflateX * 2.0;
            double height = this.getHeight() + inflateY * 2.0;
            ScissorClipManager.begin(x, y, width, height);
            StencilClipManager.beginClip(() -> Rect.draw(x, y, width, height, -1));
        }

        @Override
        protected void afterRenderChildren(double mouseX, double mouseY) {
            try {
                StencilClipManager.endClip();
            } finally {
                ScissorClipManager.end();
            }
        }
    }

    /** 队列里的一行：只负责自己的外观，位置与透明度由父面板每帧写入。 */
    private final class QueueRow extends RoundedRectWidget {

        private final Music music;
        private final SpringAnimation animation = new SpringAnimation(new SpringParams(.9, 15.0, 90.0, false));

        private int slot;
        /** 被拖起来的程度，0 为静止、1 为完全提起。 */
        private double lift;
        /** 拖动中这一帧应该在的内容坐标 Y。 */
        private double dragY;
        private double fade = 1.0;
        private boolean removing;
        /** 这一帧这行允不允许拖动 / 移除：本地模式恒开，房间模式按权限逐帧写入（见 applyRowLayout）。 */
        private boolean canDrag = true;
        private boolean canRemove = true;
        private long appearedAt;
        private boolean coverLoaded;

        private QueueRow(Music music) {
            this.music = music;
            this.setShouldOverrideMouseCursor(true);
            this.setGlassRim(GlassLook.Surface.PLAYER);

            this.setBeforeRenderCallback(() -> {
                if (!this.coverLoaded) this.coverLoaded = loadCover(this.music);

                boolean dragging = this == PlayQueuePanel.this.draggingRow;
                this.setRadius(4.0);
                this.setColor(dragging
                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : (this.isHovering()
                                ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                : NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND)));
                        this.setFillAlpha(dragging ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            });

            // 拖起来的一行略微放大浮起，放手后 lift 归零就自己恢复原样。放大比例必须和
            // 面板算裁剪外扩用的那一个数是同一个，不然削掉的还是会被削掉。
            this.setTransformations(() -> {
                if (this.lift <= .002) return;
                this.scaleAtPos(this.getX() + this.getWidth() * .5,
                        this.getY() + this.getHeight() * .5, 1.0 + DRAG_LIFT_SCALE * this.lift);
            });

            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return true;
                // 触屏时代「点击即抓取」会让滚动列表的第一步变成抓起一张卡片：
                // 抓取改由长按触发（maybeBeginDragByLongPress），点击只负责消费掉自己。
                // 手势层关闭时回到旧行为（按下即抓、按住拖动），那是纯鼠标时代的语义。
                if (!TouchSettings.ENABLED) {
                    PlayQueuePanel.this.beginDrag(this, relativeY);
                }
                return true;
            });

            LabelWidget index = new LabelWidget(() -> String.valueOf(this.slot + 1), FontManager.pf10bold);
            this.addChild(index);
            index.setClickable(false);
            index.setBeforeRenderCallback(() -> index
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(Math.max(2.0, 14.0 - index.getWidth()),
                            (ROW_HEIGHT - index.getHeight()) * .5));

            RoundedImageWidget cover = new RoundedImageWidget(this.music.getSmallCoverLocation(), 0, 0, 0, 0);
            this.addChild(cover);
            cover.setClickable(false);
            cover.fadeIn().setLinearFilter(true);
            cover.setBeforeRenderCallback(() -> cover
                    .setBounds(20.0, 20.0)
                    .setRadius(2.0)
                    .setPosition(18.0, (ROW_HEIGHT - 20.0) * .5));

            LabelWidget name = new LabelWidget(this.music.getName(), FontManager.pf12bold);
            this.addChild(name);
            name.setClickable(false);
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setBeforeRenderCallback(() -> name
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setMaxWidth(Math.max(10.0, this.getWidth() - 42.0 - 38.0))
                    .setPosition(42.0, 4.0));

            LabelWidget artist = new LabelWidget(this.music.getArtistsName(), FontManager.pf10bold);
            this.addChild(artist);
            artist.setClickable(false);
            artist.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            artist.setBeforeRenderCallback(() -> artist
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setMaxWidth(Math.max(10.0, this.getWidth() - 42.0 - 38.0))
                    .setPosition(42.0, ROW_HEIGHT - artist.getHeight() - 4.0));

            // 移除按钮排在把手左边，并且自己吃掉点击，否则按下去会变成"开始拖动"。
            // 房间模式下没有权限（别人的点歌、且自己不是房主/管理员）时整颗收掉：
            // 一颗按了只会被服务端拒绝的按钮，比没有按钮更让人误会。
            RoundedRectWidget remove = new RoundedRectWidget();
            this.addChild(remove);
            remove.setShouldOverrideMouseCursor(true);
            remove.setBeforeRenderCallback(() -> {
                remove.setHidden(this.removing || !this.canRemove);
                remove.setBounds(14.0, 14.0)
                        .setPosition(Math.max(0.0, this.getWidth() - 32.0), (ROW_HEIGHT - 14.0) * .5)
                        .setRadius(3.0)
                        .setColor(remove.isHovering()
                                ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                remove.setAlpha(remove.isHovering() ? .95f : .55f);
            });
            remove.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton == 0 && !this.removing) {
                    PlayQueuePanel.this.removeRow(this);
                }
                return true;
            });

            LabelWidget removeText = new LabelWidget("×", FontManager.pf10bold);
            remove.addChild(removeText);
            removeText.setClickable(false);
            removeText.setBeforeRenderCallback(() -> removeText
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .center());

            DragHandleWidget handle = new DragHandleWidget();
            this.addChild(handle);
            handle.setClickable(false);
            handle.setBeforeRenderCallback(() -> {
                // 拖不动的时候把手收掉：三条横线的意思是「抓住我」，对一个抓不起来的行
                // 摆这个手势是在邀请用户做一件做不到的事。
                handle.setHidden(!this.canDrag);
                handle
                        .setBounds(10.0, 7.0)
                        .setPosition(Math.max(0.0, this.getWidth() - 16.0), (ROW_HEIGHT - 7.0) * .5)
                        .setColor(NCMScreen.getColor(this == PlayQueuePanel.this.draggingRow
                                ? NCMScreen.ColorType.PRIMARY_TEXT
                                : NCMScreen.ColorType.SECONDARY_TEXT));
            });
        }
    }

    /** 右侧的拖动把手：三条短横线，纯几何绘制，不依赖任何图标字体。 */
    private static final class DragHandleWidget extends AbstractWidget<DragHandleWidget> {

        @Override
        public void onRender(double mouseX, double mouseY) {
            int color = this.getHexColor();
            for (int bar = 0; bar < 3; bar++) {
                this.roundedRect(this.getX(), this.getY() + bar * 3.0, this.getWidth(), 1.0, .5, color);
            }
        }
    }
}
