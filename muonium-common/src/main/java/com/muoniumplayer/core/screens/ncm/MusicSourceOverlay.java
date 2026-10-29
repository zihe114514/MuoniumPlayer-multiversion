package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.lxsource.LxRuntime;
import com.muoniumplayer.core.ncm.lxsource.LxSourceService;
import com.muoniumplayer.core.ncm.lxsource.LxSourceStore;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.QishuiBackend;
import com.muoniumplayer.core.ncm.music.QishuiCompanionDownload;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseUnblockSettings;
import com.muoniumplayer.core.ncm.music.SwitchSourceSettings;
import com.muoniumplayer.core.ncm.music.YouTubeMusicService;
import com.muoniumplayer.core.ncm.music.YouTubeProxySettings;
import com.muoniumplayer.core.ncm.music.YouTubeSessionStore;
import com.muoniumplayer.core.ncm.spotify.SpotifyConfigStore;
import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService;
import com.muoniumplayer.core.ncm.spotify.SpotifyOAuthFlow;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.core.screens.ncm.panels.NavigateBar;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 音乐来源浮层：官方源（网易云 / QQ / 酷狗 / YouTube / 哔哩哔哩）与 GD音乐台 聚合源的切换。
 *
 * <p>版式、动效与图标对齐都在 {@link NcmSheet}，这里只描述「有哪些来源、每张卡上写什么」。
 * 改写的起因是原来这个文件自己写了一遍遮罩、弹窗、标题、卡片和胶囊，纵向位置是一串写死的
 * 常量（68 / 88 / 154 / 220 / 292 / 312 / 380），而弹窗高度会被视口夹住 —— GUI 缩放一开大，
 * 底部的解灰开关和说明就被内容压在下面，用户永远点不到。</p>
 */
public final class MusicSourceOverlay extends NcmSheet {

    /** 页眉占掉的高度（图标盘 34 + 上下留白）。内容区从这里开始。 */
    private static final double HEADER_H = 64.0;
    /**
     * 品牌字标底盘的边长。
     *
     * <p>46 不是随手取的：品牌字形的墨迹约 36 逻辑像素（{@code musicBrand52} 的网易云字形占
     * 0.69 em、{@code qqMusicIcon68} 的 QQ 字形占 0.53 em，两者都落在 36 附近），46 的盘刚好
     * 留出一圈均匀的内边距。品牌字体只有 16 / 18 / 52 这几档，中间没有可用字号，
     * 所以是盘去迁就字形而不是反过来 —— 缩放字形会重采样发虚。</p>
     */
    private static final double DISC = 46.0;
    /** 分组小标题占的行高。 */
    private static final double LABEL_ROW = 22.0;
    private static final double LABEL_ROW_2 = 26.0;
    /**
     * 页脚：解灰 + 自动换源两格开关并排一行、一行来源说明。
     *
     * <p>两行开关原来上下排开各占一行、整段 124 高，把内容区压得首屏都放不下三张卡；
     * 并排成两格之后这段只需要 76，省下的高度正好是总览页从「必然滚动」回到「一屏放下」的那一笔。</p>
     */
    private static final double FOOTER_H = 76.0;
    /** GD 平台列表的行高与卡高。 */
    private static final double PLATFORM_ROW = 56.0;
    private static final double PLATFORM_CARD = 48.0;

    // ── 总览页方格矩阵 ──
    /** 方格的列间距。 */
    private static final double CELL_GAP = 10.0;
    /** 一颗方格的高。盘 32 + 两行文字，压着 84 刚好三行加间距放得进一屏。 */
    private static final double CELL_H = 84.0;
    /** 方格里品牌字标底盘的边长（46 那档是大卡用的；方格里收成 32）。 */
    private static final double CELL_DISC = 32.0;
    /** 方格内文字的左边缘。 */
    private static final double CELL_TEXT_LEFT = 11.0;
    /** 角标圆钮的边长（YouTube 代理/登录、Spotify 登录）。 */
    private static final double CELL_CORNER = 22.0;

    /**
     * 自定义源那一组的强调色。
     *
     * <p>刻意用紫色而不是从主题色里取：这一页列的是<b>用户自己带进来的东西</b>，和上面几张
     * 官方卡（品牌色）、聚合源卡（靛蓝）必须一眼分得开。紫色在浅色与深色主题上都还站得住。</p>
     */
    private static final int LX_COLOR = 0x9C7CF4;
    /** 自定义源页：页眉（返回键 + 标题 + 副标题）加一行状态，共 88。 */
    private static final double LX_HEADER_H = 88.0;
    /**
     * 自定义源页页脚：只剩一行四颗导入/刷新按钮。
     *
     * <p>原来页脚还挤着两行权限开关与一行安全警告（整段 138 高），把脚本列表压到只剩两三行；
     * 权限是脚本的属性、警告是权限的说明，都搬进滚动区跟脚本放在一起，页脚只留动作。</p>
     */
    private static final double LX_FOOTER_H = 44.0;
    private static final double LX_CARD_H = 50.0;
    private static final double LX_ROW_H = LX_CARD_H + 7.0;
    private static final double LX_DISC = 36.0;
    private static final double LX_TEXT_LEFT = 12.0 + LX_DISC + 11.0;
    /** 行右端留给「使用中」胶囊与删除键的宽度，长脚本名必须在这之前收住。 */
    private static final double LX_RESERVE = 112.0;
    private static final double LX_RUNTIME_CARD = 82.0;
    private static final double LX_RUNTIME_ROW = LX_RUNTIME_CARD + 7.0;
    /** 权限开关行的高（挂进滚动区之后与脚本行同一条 feed）。 */
    private static final double LX_PERM_ROW = 34.0;
    /** 运行状态卡上摊开几行 console。三行刚好放得下一条堆栈的头尾。 */
    private static final int LX_LOG_LINES = 3;

    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_GD = 1;
    private static final int PAGE_LX = 2;
    private static final int PAGE_YOUTUBE_PROXY = 3;
    private static final int PAGE_YOUTUBE_LOGIN = 4;
    private static final int PAGE_SPOTIFY_LOGIN = 5;
    private static final int PAGE_APPLE_LOGIN = 6;

    /** Cookie 校验要联网（最坏 25 秒），期间把按钮钉成「正在验证…」，不让人连点。 */
    private boolean youtubeLoginBusy;

    /** OAuth 有一次浏览器往返（可能几分钟），期间登录态由 SpotifyOAuthFlow 的阶段接管。 */
    private boolean spotifyLoginBusy;

    /** Apple 的 cookies 校验要联网（一次 /v1/me，最坏 15 秒），期间钉住「正在验证…」。 */
    private boolean appleLoginBusy;

    /** 网址导入弹窗的开合。组件随页面重建，开合状态由它独立保管。 */
    private boolean lxUrlDialogOpen;
    /** 弹窗里的半成品网址：误触关闭后重开还在，提交成功才清。 */
    private String lxUrlDraft = "";
    /** 弹窗内那行提示：空串表示没有要专门说的话（此时显示默认说明）。 */
    private String lxUrlDialogStatus = "";
    /** 上面那行是不是坏消息（坏消息用红色与关闭图标）。 */
    private boolean lxUrlDialogBad;
    /** 二级弹窗的入场进度：遮罩暗下去、卡片从下方落上来都读它。 */
    private double lxUrlReveal;
    /** 弹窗组件：开合就是切 hidden，页面重建时由 {@link #buildLxUrlDialog()} 重新构建。 */
    private RectWidget lxUrlMask;
    private RoundedRectWidget lxUrlCard;
    private TextFieldWidget lxUrlField;

    /**
     * 当前在哪一页。
     *
     * <p>原来是一个 {@code choosingGdPlatform} 布尔值；加进自定义源之后就有三页了，
     * 再叠一个布尔值会让「两个都为 false 才是总览」这种隐式约定散落在返回键和 ESC 两处。</p>
     */
    private int page = PAGE_OVERVIEW;
    /** 建这一页时的脚本列表版本号。只有导入 / 删除会让它落后（那时行数变了，必须重建）。 */
    private int lxRevision = -1;
    private String statusText = "选一个平台后即可直接搜索与播放该平台的曲目";
    /**
     * 状态行的语气：0 说明、1 成功、2 失败。
     *
     * <p>存语气而不是直接存颜色：说明态的颜色要跟着主题走（原来写死 {@code 0xAEB5C4}，
     * 浅色主题下这行字几乎看不见），而图标也得按语气挑，拿颜色反推会在主题切换后错配。</p>
     */
    private int statusTone;

    @Override
    public void onInit() {
        showOverview();
    }

    public void handleEscape() {
        // 弹窗开着时 ESC 只关弹窗。正常路径输入框自己会拦（它聚焦时吞掉一切按键），
        // 这里兜的是「焦点被 updateStyles 抢走的那一帧」，别让 ESC 穿到翻页层级去。
        if (lxUrlDialogOpen) {
            closeLxUrlDialog();
            return;
        }
        if (page == PAGE_OVERVIEW) {
            dispose();
        } else {
            back(this::showOverview).run();
        }
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        if (page == PAGE_LX) {
            LxSourceService.pollUpdateAlert();
            // 行数变了就重建。放在这里是安全的：renderWidget 先调 onRender，之后才遍历子组件，
            // 此时清空子组件列表不会撞上遍历（与 FontPickerOverlay 同一处理）。
            if (lxRevision != LxSourceService.getRevision()) {
                showLxSources();
            }
        }
        // 弹窗的入场只有一条补间：挂在 onRender 而不是某个组件的回调里，同一帧所有组件读到同一个数。
        if (lxUrlDialogOpen) {
            lxUrlReveal = Interpolations.interpolate(lxUrlReveal, 1.0, .22f);
        }
        super.onRender(mouseX, mouseY);
    }

    // ════════════════════════════ 动画助手 ════════════════════════════

    /**
     * 真毫秒半衰期补间：按墙上时钟把 {@code state[0]} 朝目标推进，{@code halfLifeMs} 是
     * 「走完一半路程」所需的毫秒数。
     *
     * <p>为什么不用 {@link Interpolations#interpolate(double, double, double)}：那条路读全局
     * 帧间隔，而 {@code calcFrameDelta()} 在帧间隔超过 200ms 时把步长压成 0.01 —— MC 一卡
     * （加载区块、GC），所有补间当场冻结；恢复后又因为「每帧固定比例」在一台 30fps 的机器上
     * 慢一倍、144Hz 的机器上快一倍多。这里改读 {@code System.nanoTime}：帧率无关、断档不冻
     * （超长帧照实按毫秒走，上限一档防瞬跳），和 {@link SheetClock} 的真时钟哲学同一条线 ——
     * 本页的入场瀑布是 {@code SheetClock} 在演，如果 hover/选中还是那套「先窜后糊」的指数，
     * 两种节奏混在一块就是肉眼可见的不同步。</p>
     *
     * <p>半衰期而不是线性时长：颜色类补间「先快后缓」收得才稳（线性逼近终点会有肉眼可见的
     * 匀速爬行尾巴），且不需要记起点时间，一个 double 状态格就够。</p>
     *
     * @param state       单元素状态格，就地更新
     * @param target      目标值（0/1 或任意）
     * @param halfLifeMs  半程毫秒数
     * @param lastNanos   单元素时间戳格（各补间独享，避免共享同一个「上一帧」）
     */
    private static void easeMs(final double[] state, final double target,
                               final double halfLifeMs, final long[] lastNanos) {
        long now = System.nanoTime();
        double frameMs = lastNanos[0] == 0L ? 0.0
                : Math.min(100.0, (now - lastNanos[0]) / 1_000_000.0);
        lastNanos[0] = now;
        if (frameMs <= 0.0 || state[0] == target) {
            // 首帧不动、到位即锁死（避免无限趋近的微小抖动常年跑着）。
            return;
        }
        // 指数衰减的解析解：剩余距离每过 halfLifeMs 减半。
        double blend = 1.0 - Math.pow(.5, frameMs / halfLifeMs);
        state[0] += (target - state[0]) * blend;
        // 过冲保护：blend<1 恒成立，但浮点累加可能在端点上越过，夹一下。
        if ((target - state[0]) * (target - state[0]) < 1e-8) state[0] = target;
    }

    // ════════════════════════════ 一级页：来源总览 ════════════════════════════

    /**
     * 总览页：3 × 4 方格矩阵，一格一个可切换项（Apple 加入后第十格落第四行行首）。
     *
     * <p>官方源 + GD 聚合 + LX 自定义共十项，前三行填满、第四行单格收尾 —— 矩阵不再是
     * 「一格不空」，但比四列九格留三窟窿的排法规整：缺口集中在最后一行右端，视线扫过去
     * 是一条完整的 3 列光栅。旧版七张 67px 的大卡加三行分组标题加两行页脚开关，目标高度
     * 750 上下，一屏根本放不下；压成方格之后整页 ~560，绝大多数视口不再需要滚动。</p>
     *
     * <p>格子按行主序排：上两行是官方源（第一行网易云/QQ/酷狗，第二行 YouTube/B站/汽水，
     * 第三行 Spotify/Apple/GD），第四行 LX。选中的格子整块铺品牌色，扫一眼就知道现在用着谁。</p>
     */
    private void showOverview() {
        page = PAGE_OVERVIEW;
        beginPage();

        final Panel dialog = sheet(476, () -> HEADER_H + LABEL_ROW + CELL_ROW_H * 4 + FOOTER_H);
        header(dialog, PartyIcons.NOTE, "音乐来源",
                () -> "点按任一格即切换；官方源、聚合源与自定义脚本源各自独立",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final Panel body = body(dialog, () -> HEADER_H, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);

        sectionLabel(strip(feed, LABEL_ROW), "选择音源", () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        // 方格行：行内三格平分宽度，格间距 CELL_GAP。ScrollPanel 的行是等宽的 Panel，
        // 格子的 x 在行回调里按当帧宽度现算（窄视口下弹窗被夹窄，格子跟着收）。
        gridRow(feed, 0, cell -> officialCell(cell, 0, MusicPlatform.NETEASE),
                cell -> officialCell(cell, 1, MusicPlatform.QQ),
                cell -> officialCell(cell, 2, MusicPlatform.KUGOU));
        gridRow(feed, 1, cell -> officialCell(cell, 3, MusicPlatform.YOUTUBE),
                cell -> officialCell(cell, 4, MusicPlatform.BILIBILI),
                cell -> officialCell(cell, 5, MusicPlatform.QISHUI));
        // 第三行官方源收尾 + GD 入口格；第四行 LX 入口格（进二级页，不直接切换）。
        // 同一行同规格，规整优先于分组 —— 分组信息由格子自己的颜色体系（品牌色/靛蓝/紫）承担。
        gridRow(feed, 2, cell -> officialCell(cell, 6, MusicPlatform.SPOTIFY),
                cell -> officialCell(cell, 7, MusicPlatform.APPLE),
                this::gdCell);
        // 第十格（LX）独占一行行首：空出的两格是隐形 Panel，不画任何东西。
        gridRow(feed, 3, this::lxCell, cell -> { }, cell -> { });

        settingsRow(dialog);
        tip(dialog, () -> "GD音乐台来源由 music-api.gdstudio.xyz 提供，请遵循上游服务的使用要求",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 24);
    }

    /** 方格行高 = 格高 + 上下留给 hover 生长与行距的呼吸位。 */
    private static final double CELL_ROW_H = CELL_H + 8.0;

    /**
     * 方格里的一行：三格等宽平铺。
     *
     * <p>x 按行宽现算而不是按 476 写死 —— 弹窗宽是「min(476, 视口−24)」的，窄视口下每格
     * 等比收窄，三格永远占满一行，不会挤成一坨或留一截。</p>
     *
     * <p>行里每格有自己的入场档位（{@code rowSlot * 3 + 列}），九格是一条连贯的瀑布——
     * 整行共用一个 gate 的话，三格会同时到场，瀑布断成三截。</p>
     */
    private void gridRow(final ScrollPanel feed, final int rowSlot,
                         final java.util.function.Consumer<Panel> left,
                         final java.util.function.Consumer<Panel> middle,
                         final java.util.function.Consumer<Panel> right) {
        final Panel[] cells = new Panel[3];
        for (int i = 0; i < 3; i++) {
            cells[i] = new Panel();
        }
        final Panel row = new Panel();
        feed.addChild(row);
        for (Panel cell : cells) {
            row.addChild(cell);
        }
        // 方格行：行内三格平分宽度，格间距 CELL_GAP。
        //
        // 行不能走 strip(feed, …)：那个帮助方法把「设行尺寸」挂进它自己的 beforeRenderCallback，
        // 而这里必须再挂一个排三格的回调 —— AbstractWidget 的回调是单槽的，后挂的会把先挂的覆盖掉，
        // 行就永远是 0×0（三格塌在左上角叠成一摞）。所以行在这里自管尺寸，语义与 strip 相同：
        // 宽随滚动区现算、高等于 CELL_ROW_H，feed(VERTICAL) 只负责摆 y，x 由这个回调自己排。
        row.setBeforeRenderCallback(() -> {
            row.setBounds(Math.max(1, feed.getWidth()), Math.max(1, CELL_ROW_H));
            double width = Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2);
            double cellWidth = (width - CELL_GAP * 2) / 3.0;
            for (int i = 0; i < 3; i++) {
                // 行只排 x；入场位移由格子自己的回调按各自的 gate 走（行级位移会和它
                // 叠成 13px 的双重拖尾，瀑布反而发闷）。
                cells[i].setBounds(cellWidth, Math.max(1, row.getHeight()));
                cells[i].setPosition(PAD + CARD_INSET + (cellWidth + CELL_GAP) * i, 0.0);
            }
        });
        left.accept(cells[0]);
        middle.accept(cells[1]);
        right.accept(cells[2]);
    }

    /**
     * 总览页的一颗方格。
     *
     * <p>与 {@link NcmSheet#card} 的区别：格子并排铺在行里，hover 的生长量必须收小
     * （每侧 2px，而不是卡片那档 CARD_INSET=3 的两侧 6px），不然 10px 的格距下相邻两格
     * 悬停时会互相压住圆角。版式：品牌盘 32 在左上，名字与状态两行在盘下。</p>
     *
     * <p>选中态是一条<b>连续的颜色补间</b>而不是布尔跳变：{@code pickedProgress} 从 0 走到 1，
     * 底色从面板底色滑向品牌色、盘与文字随之换墨。切换来源的那一下，旧格淡出与新格点亮
     * 是同时发生的两段补间，眼睛读到的是「颜色流过去」，而不是两块色块互换。</p>
     *
     * @param slot 入场瀑布次序（0..8，九格全在 stagger 窗口内）
     * @param pickedProgressOut 可选：把选中进度补间写进调用方给的格子，文字/勾标与底色
     *                          共用同一条曲线；不关心就传 null
     */
    private Card gridCell(final Panel cell, final int slot, final IntSupplier accent,
                          final BooleanSupplier picked, final boolean entryStyle,
                          final double[] pickedProgressOut) {
        final Card[] self = new Card[1];
        // 兜底数组同样按当前状态起步；调用方传了自己的格子时（官方格）以那一格为准。
        final double[] pickedProgress = pickedProgressOut != null
                ? pickedProgressOut : new double[]{picked.getAsBoolean() ? 1.0 : 0.0};
        final long[] pickedNanos = new long[1];
        // hover 独立走真毫秒补间：Card.hover 是基类的帧比例补间，两者不要混用节奏。
        final double[] hoverSmooth = {0.0};
        final long[] hoverNanos = new long[1];
        final Card card = new Card();
        cell.addChild(card);
        card.setRadius(12);
        card.setGlassRim(GlassLook.Surface.PLAYER);
        card.setBeforeRenderCallback(() -> {
            card.reveal = cardGate(slot);
            easeMs(pickedProgress, picked.getAsBoolean() ? 1.0 : 0.0, 90.0, pickedNanos);
            easeMs(hoverSmooth, card.hover > .02 ? 1.0 : 0.0, 60.0, hoverNanos);
            card.setBounds(Math.max(1, cell.getWidth() - 2.0), Math.max(1, CELL_H));
            card.setPosition(1.0, 2.0 + (1.0 - card.reveal) * 9.0);
            card.setColor(mix(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hoverSmooth[0]),
                    accent.getAsInt(), pickedProgress[0]));
            card.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            card.setAlpha((float) card.reveal);
        });
        self[0] = card;
        card.setTransformations(() -> {
            double w = Math.max(1, card.getWidth());
            card.scaleAtPos(card.getX() + w * .5, card.getY() + card.getHeight() * .5,
                    1.0 + (4.0 / w) * card.hover);
        });
        if (entryStyle) {
            // 入口格（GD/LX）的选中态不铺整块品牌色（它们不是「当前音源」的概念），
            // 只上一圈品牌色描边 + 常规底色，与官方格一眼区分「这是扇门，不是一块已选的地」。
            // 描边亮度跟着同一条 pickedProgress 走，点亮/熄灭也是渐变的。
            stroke(card, 12.0, accent, () -> .55 * pickedProgress[0]);
        } else {
            selectionSheen(card, picked, () -> pickedProgress[0]);
        }
        return card;
    }

    /**
     * 选中格文字的墨色：随 pickedProgress 在「正文字色 → 白」之间连续插值，与底色补间同一条曲线。
     *
     * <p>端点语义与旧版一致：progress=0 是停泊在深底上的 PRIMARY_TEXT，progress=1 是停在
     * 品牌色底上的白。中途某一帧文字可能同时压在两种底色的过渡带上，朝白推的插值在两种底上
     * 都保持可读，而旧的布尔切换会在补间中点闪一下。</p>
     */
    private int cellInk(final double pickedProgress) {
        return mix(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), 0xFFFFFF, pickedProgress);
    }

    /**
     * 官方源的方格：点击切换音源，右上角按需挂特例入口（YouTube 的代理/登录、Spotify 的登录）。
     *
     * <p>特例入口收成角标圆钮而不是占一整行：方格里放不下三颗并排的按钮，而这些入口
     * （偶尔用一次的设置）hover 才亮起，平时不抢注意力。这是终点操作，所以没有 chevron。</p>
     */
    private void officialCell(final Panel cell, final int slot, final MusicPlatform platform) {
        final BooleanSupplier picked = () -> NeteaseMusicService.getCurrentPlatform() == platform;
        // 按当前状态初始化（与 NcmSheet.toggle 的 knob 同一范本）：翻页重建后选中格
        // 直接以品牌色到场，不再从「未选中色」闪一遍补间。
        final double[] pickedProgress = {picked.getAsBoolean() ? 1.0 : 0.0};
        final Card card = gridCell(cell, slot, platform::getBrandColor, picked, false, pickedProgress);
        // 侧栏那枚来源按钮上写着的就是当前来源，所以接力板落在当前来源这一格。
        if (NeteaseMusicService.getCurrentPlatform() == platform) hero(card);
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            boolean changed = NeteaseMusicService.getCurrentPlatform() != platform;
            // 即便点的就是当前来源也要走一遍 setter：它顺带清掉残留的 GD 平台选择。
            NeteaseMusicService.setCurrentPlatform(platform);
            if (changed) resetToHomeForCurrentSource();
            dispose();
            return true;
        });

        cellDisc(card, platform, pickedProgress);

        // 状态行：汽水挂一颗后端状态圆点（它的后端有五态，颜色就是状态），
        // 其余平台右侧留给角标钮或对勾。
        final boolean qishui = platform == MusicPlatform.QISHUI;

        final LabelWidget name = label(card, platform::getDisplayName, FontManager.pf12bold);
        final LabelWidget note = label(card, () -> accountLine(platform), FontManager.pf12);
        name.setBeforeRenderCallback(() -> {
            name.setColor(cellInk(pickedProgress[0]));
            // 右端让位：角标钮（YouTube 两颗 22 + 间距 + 边距 ≈ 58）或勾标（22 + 10）。
            double reserve = cornerReserve(platform, pickedProgress[0]);
            name.setMaxWidth(Math.max(1, card.getWidth() - CELL_TEXT_LEFT - reserve));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(CELL_TEXT_LEFT, inkTop(name, 52.0));
        });
        note.setBeforeRenderCallback(() -> {
            // 副标题走同一条补间：从次要文字色滑向「品牌色底上的第二层白」，中点两色在
            // 明度上已经相当接近，过渡是连续的，不会再有布尔切换那一帧的色跳。
            note.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    mix(platform.getBrandColor(), 0xFFFFFF, .78), pickedProgress[0]));
            note.setMaxWidth(Math.max(1, card.getWidth() - CELL_TEXT_LEFT - 10.0));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(qishui ? CELL_TEXT_LEFT + 12.0 : CELL_TEXT_LEFT, inkTop(note, 68.0));
        });

        if (qishui) {
            // 后端状态圆点：直径 6，跟在状态文字左边。旧版是一枚胶囊，方格里放不下还破坏规整。
            final RoundedRectWidget dot = new RoundedRectWidget();
            card.addChild(dot);
            dot.setClickable(false).setBeforeRenderCallback(() -> {
                dot.setRadius(3);
                dot.setBounds(6, 6);
                dot.setPosition(CELL_TEXT_LEFT, inkTop(note, 68.0) - 1.0);
                dot.setColor(qishuiBackendStatusColor());
            });
        }

        checkBadge(card, picked, platform::getBrandColor, () -> card.getWidth() - 19.0,
                () -> pickedProgress[0]);

        if (platform == MusicPlatform.YOUTUBE) {
            cornerButton(card, PartyIcons.LINK, MusicPlatform.YOUTUBE,
                    () -> YouTubeProxySettings.isEnabled(),
                    this::showYouTubeProxySettings, 0);
            cornerButton(card, PartyIcons.KEY, MusicPlatform.YOUTUBE,
                    () -> NeteaseMusicService.isLoggedIn(MusicPlatform.YOUTUBE),
                    this::showYouTubeLogin, 1);
        }
        if (platform == MusicPlatform.SPOTIFY) {
            cornerButton(card, PartyIcons.KEY, MusicPlatform.SPOTIFY,
                    () -> NeteaseMusicService.isLoggedIn(MusicPlatform.SPOTIFY),
                    this::showSpotifyLogin, 0);
        }
        if (platform == MusicPlatform.APPLE) {
            cornerButton(card, PartyIcons.KEY, MusicPlatform.APPLE,
                    () -> NeteaseMusicService.isLoggedIn(MusicPlatform.APPLE),
                    this::showAppleLogin, 0);
        }
    }

    /** 名字行右端的让位宽度：角标钮（YouTube 两颗）> 勾标 > 无。勾标让位随补间连续变化。 */
    private double cornerReserve(final MusicPlatform platform, final double pickedProgress) {
        double badgeReserve = 12.0 + 18.0 * pickedProgress;
        if (platform == MusicPlatform.YOUTUBE) return CELL_CORNER * 2 + 4.0 + 8.0;
        if (platform == MusicPlatform.SPOTIFY) return CELL_CORNER + 8.0;
        if (platform == MusicPlatform.APPLE) return CELL_CORNER + 8.0;
        return badgeReserve;
    }

    /**
     * 方格右上角的入口圆钮。hover 才亮：这些是「偶尔用一次」的设置，平时不该和切换动作抢注意力。
     *
     * @param index 从右往左数的第几颗（0 = 最靠右）
     */
    private void cornerButton(final Card card, final String glyph, final MusicPlatform platform,
                              final BooleanSupplier active, final Runnable onClick, final int index) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(CELL_CORNER * .5);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            onClick.run();
            return true;
        });
        final double[] hover = {0.0};
        final long[] hoverNanos = new long[1];
        button.setBeforeRenderCallback(() -> {
            // 平时半亮、hover 实起，走真毫秒补间 —— 布尔跳变的角标在鼠标扫过一排格子时
            // 会连闪三下，补间之后是三段连续的亮起。
            easeMs(hover, button.isHovering() ? 1.0 : 0.0, 55.0, hoverNanos);
            button.setAlpha((float) (.45 + .55 * hover[0]));
            button.setBounds(CELL_CORNER, CELL_CORNER);
            button.setPosition(card.getWidth() - 6.0 - CELL_CORNER - (CELL_CORNER + 4.0) * index,
                    5.0);
            button.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER),
                            platform.getBrandColor(), .16), hover[0]));
            button.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        icon(button, glyph, ICON_S, () -> CELL_CORNER * .5, () -> CELL_CORNER * .5,
                () -> active.getAsBoolean() ? platform.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }

    /**
     * 方格里的品牌字标底盘（32 那档）。方格里盘居上、文字两行居下；盘的配色随选中进度补间，
     * 品牌字形的字体配对沿用大卡那一套 —— 字形配错字体画出来是空白，这一段不能另起炉灶。
     */
    private void cellDisc(final Card card, final MusicPlatform platform,
                          final double[] pickedProgress) {
        final IntSupplier accent = platform::getBrandColor;
        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setRadius(10);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(CELL_DISC, CELL_DISC);
            disc.setPosition(CELL_TEXT_LEFT, 10.0);
            disc.setColor(mix(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    accent.getAsInt(), .18), mix(accent.getAsInt(), 0xFFFFFF, .20),
                    pickedProgress[0]));
        });
        brandMark(disc, platform, CELL_DISC * BrandVectorIcons.MARK_RATIO,
                brandGlyph(platform), brandFont(platform),
                () -> CELL_DISC * .5, () -> CELL_DISC * .5,
                () -> mix(accent.getAsInt(), 0xFFFFFF, pickedProgress[0]));
    }

    /** 品牌字形的兜底字形（矢量画不出来时用）。配对关系与品牌盘一致。 */
    private String brandGlyph(final MusicPlatform platform) {
        return switch (platform) {
            case KUGOU -> MusicBrandIcons.KUGOU_MONOGRAM;
            case QQ -> MusicBrandIcons.QQ_MUSIC;
            case YOUTUBE -> MusicBrandIcons.YOUTUBE;
            case BILIBILI -> MusicBrandIcons.BILIBILI_MONOGRAM;
            case QISHUI -> MusicBrandIcons.QISHUI_MONOGRAM;
            case SPOTIFY -> MusicBrandIcons.SPOTIFY_MONOGRAM;
            case APPLE -> MusicBrandIcons.APPLE_MONOGRAM;
            default -> MusicBrandIcons.NETEASE_CLOUD_MUSIC;
        };
    }

    /** 品牌字形的兜底字体。酷狗/哔哩/汽水/Spotify/Apple 用正文字体画一枚字符字标。 */
    private CFontRenderer brandFont(final MusicPlatform platform) {
        return switch (platform) {
            case KUGOU, BILIBILI, QISHUI, SPOTIFY, APPLE -> FontManager.pf34bold;
            case YOUTUBE -> FontManager.youtubeIcon34;
            case QQ -> FontManager.qqMusicIcon68;
            default -> FontManager.musicBrand52;
        };
    }

    /**
     * GD 聚合格：进入平台选择页。它不是「当前音源」而是一扇门，选中态用描边而不是铺色。
     */
    private void gdCell(final Panel cell) {
        final BooleanSupplier picked = this::isGdActive;
        final Card card = gridCell(cell, 8, MusicPlatform.GD::getBrandColor, picked, true, null);
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setRadius(10);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(CELL_DISC, CELL_DISC);
            disc.setPosition(CELL_TEXT_LEFT, 10.0);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    MusicPlatform.GD.getBrandColor(), .18));
        });
        // 聚合源没有品牌字标，用"探索"这一枚。
        icon(disc, PartyIcons.EXPLORE, ICON_M, () -> CELL_DISC * .5, () -> CELL_DISC * .5,
                () -> MusicPlatform.GD.getBrandColor());

        final LabelWidget name = label(card, () -> "GD音乐台", FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - CELL_TEXT_LEFT - 22.0));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(CELL_TEXT_LEFT, inkTop(name, 52.0));
        });
        final LabelWidget note = label(card, this::buildGdSummary, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, card.getWidth() - CELL_TEXT_LEFT - 10.0));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(CELL_TEXT_LEFT, inkTop(note, 68.0));
        });

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_S, () -> card.getWidth() - 14.0,
                () -> 16.0, () -> mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), card.hover));
        checkBadge(card, picked, MusicPlatform.GD::getBrandColor, () -> card.getWidth() - 19.0);
    }

    /** LX 自定义源格：进入脚本管理页。与 GD 同一套入口版式，紫色系。 */
    private void lxCell(final Panel cell) {
        final BooleanSupplier picked = LxSourceService::isEnabled;
        final Card card = gridCell(cell, 9, () -> LX_COLOR, picked, true, null);
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showLxSources();
            return true;
        });

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setRadius(10);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(CELL_DISC, CELL_DISC);
            disc.setPosition(CELL_TEXT_LEFT, 10.0);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    LX_COLOR, .18));
        });
        icon(disc, PartyIcons.LINK, ICON_M, () -> CELL_DISC * .5, () -> CELL_DISC * .5,
                () -> LX_COLOR);

        final LabelWidget name = label(card, () -> "自定义音源", FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - CELL_TEXT_LEFT - 22.0));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(CELL_TEXT_LEFT, inkTop(name, 52.0));
        });
        final LabelWidget note = label(card, this::buildLxSummary, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, card.getWidth() - CELL_TEXT_LEFT - 10.0));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(CELL_TEXT_LEFT, inkTop(note, 68.0));
        });

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_S, () -> card.getWidth() - 14.0,
                () -> 16.0, () -> mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), card.hover));
        checkBadge(card, picked, () -> LX_COLOR, () -> card.getWidth() - 19.0);
    }

    /** YouTube 专属设置走方格角标（见 {@link #cornerButton}），二级页从那里进入。 */
    private void showYouTubeProxySettings() {
        page = PAGE_YOUTUBE_PROXY;
        beginPage();

        statusText = YouTubeProxySettings.describe();
        statusTone = YouTubeProxySettings.isEnabled() ? 1 : 0;

        final Panel dialog = sheet(476, () -> 300.0);
        subHeader(dialog, back(this::showOverview), () -> "YouTube · 网络代理",
                () -> "仅影响 YouTube 搜索、取流、音频下载与封面下载",
                MusicPlatform.YOUTUBE::getBrandColor);
        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> 60.0);

        final Panel body = body(dialog, () -> 88.0, () -> 54.0);
        final ScrollPanel feed = feed(body);
        sectionLabel(strip(feed, 20.0), "HTTP 代理", () -> 4.0,
                MusicPlatform.YOUTUBE::getBrandColor);
        youtubeProxyToggleRow(strip(feed, 48.0));
        sectionLabel(strip(feed, 24.0), "代理地址", () -> 8.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        final TextFieldWidget input = youtubeProxyInputRow(strip(feed, 46.0));

        youtubeProxyFooter(dialog, input);
    }

    /** 二级页：YouTube 登录（粘贴浏览器 Cookie）。 */
    private void showYouTubeLogin() {
        page = PAGE_YOUTUBE_LOGIN;
        beginPage();

        statusText = YouTubeSessionStore.describe();
        statusTone = YouTubeSessionStore.isLoggedIn() ? 1 : 0;

        final Panel dialog = sheet(476, () -> 300.0);
        subHeader(dialog, back(this::showOverview), () -> "YouTube · 登录",
                () -> "匿名就能搜与播；登录之后才有的是歌词与私人歌单",
                MusicPlatform.YOUTUBE::getBrandColor);
        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> 60.0);

        final Panel body = body(dialog, () -> 88.0, () -> 54.0);
        final ScrollPanel feed = feed(body);
        sectionLabel(strip(feed, 20.0), "粘贴 Cookie", () -> 4.0,
                MusicPlatform.YOUTUBE::getBrandColor);
        final TextFieldWidget input = youtubeCookieInputRow(strip(feed, 46.0));
        youtubeLoginFooter(dialog, input);
    }

    /**
     * Cookie 输入行。
     *
     * <p>Cookie 是一整行上千个字符，所以按单行长文本用，并在旁边留一颗「填入剪贴板」——
     * 不然用户得在聊天框里选中上千字再粘贴。</p>
     */
    private TextFieldWidget youtubeCookieInputRow(final Panel row) {
        final RoundedRectWidget field = new RoundedRectWidget();
        row.addChild(field);
        field.setGlassRim(GlassLook.Surface.PLAYER);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            field.setPosition(PAD + CARD_INSET, 4.0);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            field.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        row.addChild(input);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder("从已登录 music.youtube.com 的浏览器复制整串 Cookie");
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 22), 24);
            input.setPosition(field.getRelativeX() + 11, field.getRelativeY() + 8);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, MusicPlatform.YOUTUBE::getBrandColor, () -> input.isFocused() ? .62 : 0.0);
        return input;
    }

    private void youtubeLoginFooter(final Panel dialog, final TextFieldWidget input) {
        final RoundedButtonWidget paste = action(dialog, () -> PartyIcons.LINK,
                () -> "填入剪贴板", FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), () -> {
                    String text = KeyboardUtils.getClipboardString();
                    if (text == null || text.trim().isEmpty()) {
                        statusText = "剪贴板是空的";
                        statusTone = 2;
                        return;
                    }
                    input.setText(text.trim());
                    statusText = "已填入剪贴板内容，再点「验证并登录」";
                    statusTone = 1;
                });
        paste.setBeforeRenderCallback(() -> {
            paste.setBounds(footerTripleWidth(dialog), 30);
            paste.setPosition(PAD + CARD_INSET, dialog.getHeight() - 46);
            paste.setColor(paste.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget verify = action(dialog, () -> PartyIcons.CHECK,
                () -> youtubeLoginBusy ? "正在验证…" : "验证并登录", FontManager.pf12bold,
                () -> 0xFFFFFF, () -> submitYouTubeCookie(input));
        verify.setBeforeRenderCallback(() -> {
            verify.setBounds(footerTripleWidth(dialog), 30);
            verify.setPosition(PAD + CARD_INSET + footerTripleWidth(dialog) + 8.0, dialog.getHeight() - 46);
            verify.setColor(verify.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        });

        final RoundedButtonWidget signOut = action(dialog, () -> PartyIcons.LOGOUT, () -> "退出登录",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> {
                    if (!YouTubeSessionStore.isLoggedIn()) {
                        statusText = "现在本来就是未登录状态";
                        statusTone = 0;
                        return;
                    }
                    // 走账号分派那一层，别在这里直接清文件：账号名与头像的缓存要一起失效。
                    NeteaseMusicService.logout(MusicPlatform.YOUTUBE);
                    statusText = YouTubeSessionStore.describe();
                    statusTone = 0;
                    input.setText("");
                });
        signOut.setBeforeRenderCallback(() -> {
            signOut.setBounds(footerTripleWidth(dialog), 30);
            signOut.setPosition(PAD + CARD_INSET + (footerTripleWidth(dialog) + 8.0) * 2, dialog.getHeight() - 46);
            signOut.setColor(signOut.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
    }

    /**
     * 登录页页脚三键的等分宽度：三键 + 两道 8 间隙铺满内容区。
     *
     * <p>原来是 96/118/96 三段定宽链（行尾 349/371px），弹窗被视口夹窄后整条链画出弹窗右缘、
     * 「退出登录」点不到。改成按当帧宽等分，极窄时靠 {@code action()} 的文字截断兜底。</p>
     */
    private double footerTripleWidth(final Panel dialog) {
        return Math.max(64.0, (dialog.getWidth() - (PAD + CARD_INSET) * 2 - 16.0) / 3.0);
    }

    /**
     * 后台校验 Cookie。
     *
     * <p>必须开在后台线程：这一步要打一次账号接口，最坏 25 秒，放主线程等于把整个游戏冻住。
     * 回写状态再切回主线程做。</p>
     */
    private void submitYouTubeCookie(final TextFieldWidget input) {
        if (youtubeLoginBusy) return;
        final String cookie = input.getText() == null ? "" : input.getText().trim();
        if (cookie.isEmpty()) {
            statusText = "请先粘贴 Cookie";
            statusTone = 2;
            return;
        }
        youtubeLoginBusy = true;
        statusText = "正在验证 Cookie…";
        statusTone = 3;
        MultiThreadingUtil.runAsync(() -> {
            final YouTubeMusicService.LoginResult result;
            try {
                result = YouTubeMusicService.loginWithCookie(cookie);
            } catch (Throwable throwable) {
                final String message = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName() : throwable.getMessage();
                MultiThreadingUtil.runOnMainThread(() -> {
                    youtubeLoginBusy = false;
                    statusText = "Cookie 登录失败：" + message;
                    statusTone = 2;
                });
                return;
            }
            MultiThreadingUtil.runOnMainThread(() -> {
                youtubeLoginBusy = false;
                statusText = result.ok ? "已登录：" + result.accountName : result.message;
                statusTone = result.ok ? 1 : 2;
            });
        });
    }

    // ── Spotify 登录（自备 Client ID + OAuth PKCE） ───────────────────────────

    /**
     * 二级页：Spotify 登录。
     *
     * <p>与 YouTube 的粘贴 Cookie 同一版式，但两步走：先存 Client ID，再开浏览器授权。
     * 状态行是<b>活的</b> —— OAuth 的等待/换token 阶段由 {@link SpotifyOAuthFlow} 的事件推进，
     * 直接读它而不是抄一份快照，回调落地后页面自己翻成「已登录」。</p>
     */
    private void showSpotifyLogin() {
        page = PAGE_SPOTIFY_LOGIN;
        beginPage();

        // busy 标记按流程当前阶段复位：上次开着页面时流程被看门狗/取消收掉的，
        // 这里不复位的话「打开浏览器授权」会永远停在「等待授权…」。
        SpotifyOAuthFlow.Status flow = SpotifyOAuthFlow.currentStatus();
        spotifyLoginBusy = flow == SpotifyOAuthFlow.Status.WAITING_BROWSER
                || flow == SpotifyOAuthFlow.Status.EXCHANGING;

        statusText = SpotifyMusicService.describeStatus();
        statusTone = SpotifyMusicService.isLoggedIn() ? 1 : 0;

        final Panel dialog = sheet(476, () -> 356.0);
        subHeader(dialog, back(this::showOverview), () -> "Spotify · 登录",
                () -> "自备 Client ID · 播放始终由跨平台换源匹配",
                MusicPlatform.SPOTIFY::getBrandColor);
        tip(dialog, this::spotifyStatusGlyph, this::spotifyLiveStatus, this::spotifyLiveColor, () -> 60.0);

        final Panel body = body(dialog, () -> 88.0, () -> 54.0);
        final ScrollPanel feed = feed(body);
        sectionLabel(strip(feed, 20.0), "Client ID", () -> 4.0,
                MusicPlatform.SPOTIFY::getBrandColor);
        final TextFieldWidget input = spotifyClientIdInputRow(strip(feed, 46.0));
        spotifyStepsCard(strip(feed, 96.0));
        spotifyLoginFooter(dialog, input);
    }

    /** Client ID 输入行。预填已保存的值，用户换号时看得见自己粘过什么。 */
    private TextFieldWidget spotifyClientIdInputRow(final Panel row) {
        final RoundedRectWidget field = new RoundedRectWidget();
        row.addChild(field);
        field.setGlassRim(GlassLook.Surface.PLAYER);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            field.setPosition(PAD + CARD_INSET, 4.0);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            field.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        row.addChild(input);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder("粘贴你的 Spotify Client ID（约 32 位字母数字）");
        String saved = SpotifyConfigStore.clientId();
        if (!saved.isEmpty()) input.setText(saved);
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 22), 24);
            input.setPosition(field.getRelativeX() + 11, field.getRelativeY() + 8);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, MusicPlatform.SPOTIFY::getBrandColor, () -> input.isFocused() ? .62 : 0.0);
        return input;
    }

    /** 三行前置步骤说明。放卡片里而不是塞进 tip：步骤值得一块安静的底。 */
    private void spotifyStepsCard(final Panel row) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        row.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 90);
            surface.setPosition(PAD + CARD_INSET, 4.0);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        String[][] steps = {
                {"①", "developer.spotify.com/dashboard 建应用：勾选 Web API，Redirect URI 填 "
                        + SpotifyConfigStore.REDIRECT_URI},
                {"②", "粘贴 Client ID 并保存，再点「打开浏览器授权」（应用所有者需要 Premium）"},
                {"③", "Spotify 不提供音频：点播由换源在网易云 / QQ / 酷狗等平台找同名同歌手版本"},
        };
        for (int index = 0; index < steps.length; index++) {
            final LabelWidget label = label(surface, steps[index][1], FontManager.pf12);
            final double rowTop = 12.0 + index * 26.0;
            label.setBeforeRenderCallback(() -> {
                label.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                label.setMaxWidth(Math.max(1, surface.getWidth() - 26));
                label.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                label.setPosition(14, rowTop);
            });
        }
    }

    private void spotifyLoginFooter(final Panel dialog, final TextFieldWidget input) {
        final RoundedButtonWidget save = action(dialog, () -> PartyIcons.CHECK,
                () -> "保存 Client ID", FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> submitSpotifyClientId(input));
        save.setBeforeRenderCallback(() -> {
            save.setBounds(footerTripleWidth(dialog), 30);
            save.setPosition(PAD + CARD_INSET, dialog.getHeight() - 46);
            save.setColor(save.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        });

        final RoundedButtonWidget authorize = action(dialog, () -> PartyIcons.LINK,
                () -> spotifyLoginBusy ? "等待授权…" : "打开浏览器授权", FontManager.pf12bold,
                () -> 0xFFFFFF, () -> startSpotifyAuthorize());
        authorize.setBeforeRenderCallback(() -> {
            authorize.setBounds(footerTripleWidth(dialog), 30);
            authorize.setPosition(PAD + CARD_INSET + footerTripleWidth(dialog) + 8.0, dialog.getHeight() - 46);
            authorize.setColor(authorize.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        });

        final RoundedButtonWidget signOut = action(dialog, () -> PartyIcons.LOGOUT, () -> "退出登录",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> {
                    if (!SpotifyMusicService.isLoggedIn()) {
                        statusText = "现在本来就是未登录状态";
                        statusTone = 0;
                        return;
                    }
                    // 走账号分派那一层：与 YouTube 相同，登出只清 Spotify 自己的凭据文件。
                    NeteaseMusicService.logout(MusicPlatform.SPOTIFY);
                    SpotifyOAuthFlow.cancel();
                    statusText = SpotifyMusicService.describeStatus();
                    statusTone = 0;
                });
        signOut.setBeforeRenderCallback(() -> {
            signOut.setBounds(footerTripleWidth(dialog), 30);
            signOut.setPosition(PAD + CARD_INSET + (footerTripleWidth(dialog) + 8.0) * 2, dialog.getHeight() - 46);
            signOut.setColor(signOut.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
    }

    /** 保存 Client ID。纯本地校验（形状），不联网 —— 联网的失败留给授权那一步去说。 */
    private void submitSpotifyClientId(final TextFieldWidget input) {
        String clientId = input.getText() == null ? "" : input.getText().trim();
        if (clientId.isEmpty()) {
            statusText = "请先粘贴 Client ID";
            statusTone = 2;
            return;
        }
        if (SpotifyConfigStore.saveClientId(clientId)) {
            statusText = "已保存 Client ID，现在可以打开浏览器授权";
            statusTone = 1;
        } else {
            statusText = "Client ID 形状不对：应是一串约 32 位的字母数字（在开发者后台的应用页里）";
            statusTone = 2;
        }
    }

    /**
     * 起回调服务并拉起浏览器。全程无网络请求（授权页是 Spotify 的），快到可以直接在点击线程做。
     * 浏览器打不开时把 URL 塞进剪贴板 —— 手动粘贴到浏览器照样能完成授权。
     */
    private void startSpotifyAuthorize() {
        if (spotifyLoginBusy) return;
        if (!SpotifyMusicService.isConfigured()) {
            statusText = "先粘贴并保存 Client ID，再打开授权";
            statusTone = 2;
            return;
        }
        try {
            final String url = SpotifyOAuthFlow.beginLogin();
            spotifyLoginBusy = true;
            if (com.muoniumplayer.core.utils.other.BrowserUtils.open(url)) {
                statusText = "浏览器已打开：登录 Spotify 并点同意授权，完成后自动回到游戏";
                statusTone = 3;
                return;
            }
            KeyboardUtils.setClipboardString(url);
            statusText = "浏览器打不开：授权链接已复制到剪贴板，手动粘贴到浏览器打开即可";
            statusTone = 2;
        } catch (Throwable throwable) {
            SpotifyOAuthFlow.cancel();
            spotifyLoginBusy = false;
            statusText = "起不了回调服务（端口 " + SpotifyOAuthFlow.callbackPort() + " 被占用？）："
                    + throwable.getMessage();
            statusTone = 2;
        }
    }

    /**
     * 活状态行：OAuth 进行中直接读流程阶段，其余时候显示最近一次操作的反馈。
     * DONE 不落缓存 —— 授权在页面开着的时候完成时，这里要立刻翻成「已登录」。
     */
    private String spotifyLiveStatus() {
        SpotifyOAuthFlow.Status flow = SpotifyOAuthFlow.currentStatus();
        if (flow == SpotifyOAuthFlow.Status.WAITING_BROWSER) {
            return "等待浏览器授权…完成后自动回到游戏（五分钟内有效）";
        }
        if (flow == SpotifyOAuthFlow.Status.EXCHANGING) {
            return "已收到授权回调，正在换取 token…";
        }
        if (flow == SpotifyOAuthFlow.Status.DONE) {
            if (spotifyLoginBusy) {
                spotifyLoginBusy = false;
                statusText = SpotifyMusicService.describeStatus();
                statusTone = 1;
            }
            return statusText;
        }
        if (flow == SpotifyOAuthFlow.Status.FAILED) {
            if (spotifyLoginBusy) spotifyLoginBusy = false;
            return "授权失败：" + SpotifyOAuthFlow.statusMessage();
        }
        return statusText;
    }

    private int spotifyLiveColor() {
        SpotifyOAuthFlow.Status flow = SpotifyOAuthFlow.currentStatus();
        if (flow == SpotifyOAuthFlow.Status.WAITING_BROWSER
                || flow == SpotifyOAuthFlow.Status.EXCHANGING) {
            return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        }
        if (flow == SpotifyOAuthFlow.Status.FAILED) return BAD_COLOR;
        return statusTone == 1 ? OK_COLOR : statusTone == 2 ? BAD_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String spotifyStatusGlyph() {
        SpotifyOAuthFlow.Status flow = SpotifyOAuthFlow.currentStatus();
        if (flow == SpotifyOAuthFlow.Status.WAITING_BROWSER
                || flow == SpotifyOAuthFlow.Status.EXCHANGING) {
            return PartyIcons.HOURGLASS;
        }
        if (flow == SpotifyOAuthFlow.Status.FAILED) return PartyIcons.WARNING;
        return statusTone == 1 ? PartyIcons.CHECK : statusTone == 2 ? PartyIcons.WARNING : PartyIcons.INFO;
    }

    // ── Apple Music 登录（粘贴 cookies.txt + gamdl 伴生管理） ────────────────

    /**
     * 二级页：Apple Music 凭据与伴生后端。
     *
     * <p>与 YouTube 的粘贴 Cookie 同一版式，多两块本平台特有的东西：gamdl 伴生后端状态行
     * （与汽水源卡片同款的实时状态）和两个行为开关。cookies 是<b>多行</b>的 Netscape 文件，
     * 单行输入框装不下 —— 导入走「从剪贴板导入」按钮直接读剪贴板（汽水同款交互），
     * 输入框只作只读摘要展示，不承接输入。</p>
     */
    private void showAppleLogin() {
        page = PAGE_APPLE_LOGIN;
        beginPage();

        statusText = com.muoniumplayer.core.ncm.apple.AppleMusicService.describeStatus();
        statusTone = com.muoniumplayer.core.ncm.apple.AppleMusicService.isLoggedIn() ? 1 : 0;
        appleLoginBusy = false;

        final Panel dialog = sheet(476, () -> 430.0);
        subHeader(dialog, back(this::showOverview), () -> "Apple Music · 凭据",
                () -> "搜索无需登录 · cookies 换来本人歌单与逐词歌词 · 完整版走 gamdl 伴生",
                MusicPlatform.APPLE::getBrandColor);
        tip(dialog, this::appleStatusGlyph, this::appleLiveStatus, this::appleLiveColor, () -> 60.0);

        final Panel body = body(dialog, () -> 88.0, () -> 54.0);
        final ScrollPanel feed = feed(body);
        sectionLabel(strip(feed, 20.0), "cookies.txt（music.apple.com 导出）", () -> 4.0,
                MusicPlatform.APPLE::getBrandColor);
        appleCookieDigestRow(strip(feed, 46.0));
        sectionLabel(strip(feed, 20.0), "gamdl 伴生后端（完整版音频）", () -> 4.0,
                MusicPlatform.APPLE::getBrandColor);
        appleCompanionRow(strip(feed, 46.0));
        sectionLabel(strip(feed, 20.0), "播放偏好", () -> 4.0,
                MusicPlatform.APPLE::getBrandColor);
        applePreferenceRows(strip(feed, 84.0));
        appleManualGamdlRow(strip(feed, 46.0));
        appleLoginFooter(dialog);
    }

    /** cookies 摘要行：只读展示当前凭据状态，导入动作在页脚按钮上。 */
    private void appleCookieDigestRow(final Panel row) {
        final RoundedRectWidget field = new RoundedRectWidget();
        row.addChild(field);
        field.setGlassRim(GlassLook.Surface.PLAYER);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            field.setPosition(PAD + CARD_INSET, 4.0);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            field.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        final LabelWidget digest = label(field,
                com.muoniumplayer.core.ncm.apple.AppleCookieSupport::describe,
                FontManager.pf12);
        digest.setBeforeRenderCallback(() -> {
            digest.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            digest.setMaxWidth(Math.max(1, field.getWidth() - 22));
            digest.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            digest.setPosition(11, inkTop(digest, 20.0));
        });
        stroke(field, 9.0, MusicPlatform.APPLE::getBrandColor,
                () -> appleLoginBusy ? .62 : 0.0);
    }

    /** 伴生状态行：实时轮询 AppleCompanionDownload 的状态机 + 行内「一键安装」钮。 */
    private void appleCompanionRow(final Panel row) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        row.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            surface.setPosition(PAD + CARD_INSET, 4.0);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        final LabelWidget status = label(surface,
                com.muoniumplayer.core.ncm.apple.AppleGamdlBridge::describeStatus,
                FontManager.pf12);
        status.setBeforeRenderCallback(() -> {
            status.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            status.setMaxWidth(Math.max(1, surface.getWidth() - 130));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(14, inkTop(status, 20.0));
        });
        final RoundedButtonWidget install = action(surface, () -> PartyIcons.ADD,
                () -> com.muoniumplayer.core.ncm.apple.AppleCompanionDownload.isReady()
                        ? "重新验证" : "一键安装",
                FontManager.pf12bold, () -> 0xFFFFFF,
                com.muoniumplayer.core.ncm.apple.AppleCompanionDownload::startManualInstall);
        install.setBeforeRenderCallback(() -> {
            install.setBounds(96, 26);
            install.setPosition(surface.getWidth() - 104, 7);
            install.setColor(install.isHovering()
                    ? mix(MusicPlatform.APPLE.getBrandColor(), 0xFFFFFF, .16)
                    : MusicPlatform.APPLE.getBrandColor());
        });
    }

    /** 两条偏好开关：gamdl 完整播放总开关 + 30 秒试听兜底开关（汽水 toggle 同款）。 */
    private void applePreferenceRows(final Panel row) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        row.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 78);
            surface.setPosition(PAD + CARD_INSET, 4.0);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        final LabelWidget gamdlTitle = label(surface, "gamdl 完整播放", FontManager.pf12bold);
        gamdlTitle.setBeforeRenderCallback(() -> {
            gamdlTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            gamdlTitle.setPosition(14, 12.0);
        });
        toggle(surface,
                () -> com.muoniumplayer.core.ncm.apple.AppleConfigStore.snapshot().gamdlEnabled,
                () -> com.muoniumplayer.core.ncm.apple.AppleConfigStore
                        .saveGamdlEnabled(!com.muoniumplayer.core.ncm.apple.AppleConfigStore
                                .snapshot().gamdlEnabled),
                () -> surface.getWidth() - 14.0, () -> 21.0, MusicPlatform.APPLE::getBrandColor);
        final LabelWidget previewTitle = label(surface, "30 秒试听兜底（换源也失败时）",
                FontManager.pf12bold);
        previewTitle.setBeforeRenderCallback(() -> {
            previewTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            previewTitle.setPosition(14, 48.0);
        });
        toggle(surface,
                () -> com.muoniumplayer.core.ncm.apple.AppleConfigStore.snapshot().previewFallback,
                () -> com.muoniumplayer.core.ncm.apple.AppleConfigStore
                        .savePreviewFallback(!com.muoniumplayer.core.ncm.apple.AppleConfigStore
                                .snapshot().previewFallback),
                () -> surface.getWidth() - 14.0, () -> 57.0, MusicPlatform.APPLE::getBrandColor);
    }

    /**
     * 非 Windows 用户的降级入口：手动指定 gamdl 可执行文件路径。
     *
     * <p>每个按键都落盘是有意的：这份 JSON 只有几百字节、原子写，存配置的代价比
     * 「填完忘了点保存，重启后路径丢了」小得多。</p>
     */
    private void appleManualGamdlRow(final Panel row) {
        final RoundedRectWidget field = new RoundedRectWidget();
        row.addChild(field);
        field.setGlassRim(GlassLook.Surface.PLAYER);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            field.setPosition(PAD + CARD_INSET, 4.0);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            field.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        row.addChild(input);
        input.setEnabled(true);
        input.setPlaceholder("（可选）手动指定 gamdl 可执行文件完整路径");
        String saved = com.muoniumplayer.core.ncm.apple.AppleConfigStore.snapshot().manualGamdlPath;
        if (!saved.isEmpty()) input.setText(saved);
        input.drawUnderline(false);
        input.setTextChangedCallback(newText -> com.muoniumplayer.core.ncm.apple.AppleConfigStore
                .saveManualGamdlPath(newText));
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 22), 24);
            input.setPosition(field.getRelativeX() + 11, field.getRelativeY() + 8);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, MusicPlatform.APPLE::getBrandColor, () -> input.isFocused() ? .62 : 0.0);
    }

    private void appleLoginFooter(final Panel dialog) {
        final RoundedButtonWidget paste = action(dialog, () -> PartyIcons.LINK,
                () -> "从剪贴板导入 cookies", FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                this::submitAppleCookiesFromClipboard);
        paste.setBeforeRenderCallback(() -> {
            paste.setBounds(footerTripleWidth(dialog), 30);
            paste.setPosition(PAD + CARD_INSET, dialog.getHeight() - 46);
            paste.setColor(paste.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget install = action(dialog, () -> PartyIcons.ADD,
                () -> "一键安装伴生", FontManager.pf12bold, () -> 0xFFFFFF,
                com.muoniumplayer.core.ncm.apple.AppleCompanionDownload::startManualInstall);
        install.setBeforeRenderCallback(() -> {
            install.setBounds(footerTripleWidth(dialog), 30);
            install.setPosition(PAD + CARD_INSET + footerTripleWidth(dialog) + 8.0,
                    dialog.getHeight() - 46);
            install.setColor(install.isHovering()
                    ? mix(MusicPlatform.APPLE.getBrandColor(), 0xFFFFFF, .16)
                    : MusicPlatform.APPLE.getBrandColor());
        });

        final RoundedButtonWidget signOut = action(dialog, () -> PartyIcons.LOGOUT, () -> "退出登录",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> {
                    if (!NeteaseMusicService.isLoggedIn(MusicPlatform.APPLE)) {
                        statusText = "现在本来就是未登录状态";
                        statusTone = 0;
                        return;
                    }
                    // 走账号分派那一层，别在这里直接清文件：账号名缓存要一起失效。
                    NeteaseMusicService.logout(MusicPlatform.APPLE);
                    statusText = com.muoniumplayer.core.ncm.apple.AppleMusicService.describeStatus();
                    statusTone = 0;
                });
        signOut.setBeforeRenderCallback(() -> {
            signOut.setBounds(footerTripleWidth(dialog), 30);
            signOut.setPosition(PAD + CARD_INSET + (footerTripleWidth(dialog) + 8.0) * 2,
                    dialog.getHeight() - 46);
            signOut.setColor(signOut.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
    }

    /** 页脚导入按钮：读剪贴板 → 后台落盘校验（/v1/me 实测），回主线程写状态。 */
    private void submitAppleCookiesFromClipboard() {
        if (appleLoginBusy) return;
        final String cookies = KeyboardUtils.getClipboardString();
        if (cookies == null || cookies.trim().isEmpty()) {
            statusText = "剪贴板是空的：先在浏览器里导出 cookies.txt 并复制全部内容";
            statusTone = 2;
            return;
        }
        appleLoginBusy = true;
        statusText = "正在校验 cookies…";
        statusTone = 3;
        MultiThreadingUtil.runAsync(() -> {
            String outcome;
            int tone;
            try {
                String token = com.muoniumplayer.core.ncm.apple.AppleCookieSupport
                        .importCookies(cookies);
                if (token == null) {
                    outcome = "这不像一份 cookies.txt：应包含多行制表符分隔的 cookie 记录";
                    tone = 2;
                } else {
                    // /v1/me/account 实测账号态与订阅；无订阅不当致命错误 —— 目录能力
                    // 照常可用，换源替身也不受影响，但状态行要说清「完整版没有着落」。
                    com.muoniumplayer.core.ncm.apple.AppleApiClient.AccountProbe probe =
                            com.muoniumplayer.core.ncm.apple.AppleApiClient.probeAccount();
                    if (probe.valid && probe.subscriptionActive) {
                        outcome = "已导入并验证：订阅有效，本人歌单与逐词歌词已解锁";
                        tone = 1;
                    } else if (probe.valid) {
                        outcome = token.isEmpty()
                                ? "已导入（游客 cookies：无 media-user-token，无本人歌单）"
                                : "已导入：账号有效，但该 Apple ID 无 Apple Music 订阅（完整版不可用）";
                        tone = 0;
                    } else {
                        // 归因分两类：网络抖动（重试即可，cookies 别删）与凭据被拒（重导）。
                        outcome = "已导入，但验证未通过：" + probe.reason;
                        tone = 2;
                    }
                }
            } catch (Throwable throwable) {
                outcome = "导入失败：" + (throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName() : throwable.getMessage());
                tone = 2;
            }
            final String finalOutcome = outcome;
            final int finalTone = tone;
            MultiThreadingUtil.runOnMainThread(() -> {
                appleLoginBusy = false;
                statusText = finalOutcome;
                statusTone = finalTone;
            });
        });
    }

    /** 活状态行：校验进行中直接说校验，其余显示最近一次操作反馈。 */
    private String appleLiveStatus() {
        if (appleLoginBusy) return "正在校验 cookies（最坏 15 秒）…";
        return statusText;
    }

    private int appleLiveColor() {
        if (appleLoginBusy) return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        return statusTone == 1 ? OK_COLOR : statusTone == 2 ? BAD_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String appleStatusGlyph() {
        if (appleLoginBusy) return PartyIcons.HOURGLASS;
        return statusTone == 1 ? PartyIcons.CHECK : statusTone == 2 ? PartyIcons.WARNING : PartyIcons.INFO;
    }

    private void youtubeProxyToggleRow(final Panel row) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        row.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            surface.setPosition(PAD + CARD_INSET, 4.0);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        icon(surface, PartyIcons.LINK, ICON_M, () -> 20.0, () -> 24.0,
                () -> YouTubeProxySettings.isEnabled() ? MusicPlatform.YOUTUBE.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        final LabelWidget title = label(surface, "启用 YouTube 代理", FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(38, inkTop(title, 13.0));
        });
        final LabelWidget note = label(surface, "网易云、QQ、酷狗、Bilibili 与更新检查保持直连", FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, surface.getWidth() - 38 - 96));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(38, inkTop(note, 28.0));
        });

        toggle(surface, YouTubeProxySettings::isEnabled, () -> {
            if (!YouTubeProxySettings.isConfigured()) {
                statusText = "请先保存一个有效的 host:port 代理地址";
                statusTone = 2;
                return;
            }
            boolean enable = !YouTubeProxySettings.isEnabled();
            YouTubeProxySettings.setConfiguration(enable, YouTubeProxySettings.getEndpoint());
            statusText = YouTubeProxySettings.describe();
            statusTone = enable ? 1 : 0;
            showYouTubeProxySettings();
        }, () -> surface.getWidth() - 12, () -> 24.0, MusicPlatform.YOUTUBE::getBrandColor);
    }

    private TextFieldWidget youtubeProxyInputRow(final Panel row) {
        final RoundedRectWidget field = new RoundedRectWidget();
        row.addChild(field);
        field.setGlassRim(GlassLook.Surface.PLAYER);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 40);
            field.setPosition(PAD + CARD_INSET, 4.0);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            field.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        row.addChild(input);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder("127.0.0.1:7890");
        input.drawUnderline(false);
        input.setText(YouTubeProxySettings.getEndpoint());
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 22), 24);
            input.setPosition(field.getRelativeX() + 11, field.getRelativeY() + 8);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, MusicPlatform.YOUTUBE::getBrandColor, () -> input.isFocused() ? .62 : 0.0);
        return input;
    }

    private void youtubeProxyFooter(final Panel dialog, final TextFieldWidget input) {
        final RoundedButtonWidget save = action(dialog, () -> PartyIcons.CHECK, () -> "保存并启用",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> {
                    String value = input.getText();
                    if (!YouTubeProxySettings.setConfiguration(!value.trim().isEmpty(), value)) {
                        statusText = "代理地址无效，请使用 host:port（支持 http:// 前缀）";
                        statusTone = 2;
                        return;
                    }
                    statusText = YouTubeProxySettings.describe();
                    statusTone = YouTubeProxySettings.isEnabled() ? 1 : 0;
                });
        save.setBeforeRenderCallback(() -> {
            save.setBounds(118, 30);
            save.setPosition(PAD + CARD_INSET, dialog.getHeight() - 46);
            save.setColor(save.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), MusicPlatform.YOUTUBE.getBrandColor(), .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget clear = action(dialog, () -> PartyIcons.CLOSE, () -> "关闭并清空",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> {
                    YouTubeProxySettings.setConfiguration(false, "");
                    statusText = YouTubeProxySettings.describe();
                    statusTone = 0;
                    showYouTubeProxySettings();
                });
        clear.setBeforeRenderCallback(() -> {
            clear.setBounds(118, 30);
            clear.setPosition(save.getRelativeX() + 126, dialog.getHeight() - 46);
            clear.setColor(clear.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        tip(dialog, () -> PartyIcons.INFO,
                () -> "格式：host:port，例如 127.0.0.1:7890；暂不支持 SOCKS 与代理认证",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 20);
    }

    /**
     * 页脚两格设置开关并排一行：左「灰色歌曲解灰」、右「跨平台自动换源」。
     *
     * <p>留在音乐来源这一层而不是挪进 HUD 配置：它们决定的就是「官方源没有流的时候怎么办」，
     * 和这个面板讨论的是同一件事。钉在页脚是因为它们是设置而不是列表项，滚动内容不该把它带走；
     * 原来上下各占一行（页脚整段 124 高），并排成两格之后页脚只需要 76 —— 省下的高度正是
     * 总览页从「必然滚动」回到「一屏放下」的那一笔。左格在前也是执行顺序：先解灰保住身份，
     * 解不开才换平台换身份。</p>
     */
    private void settingsRow(final Panel dialog) {
        footerToggle(dialog, 0, () -> PartyIcons.SWEEP, "灰色歌曲解灰", "官方没流时按曲名匹配",
                NeteaseUnblockSettings::isEnabled,
                () -> NeteaseUnblockSettings.setEnabled(!NeteaseUnblockSettings.isEnabled()));
        footerToggle(dialog, 1, () -> PartyIcons.SYNC, "跨平台自动换源", "解灰仍失败时换平台匹配",
                SwitchSourceSettings::isEnabled,
                () -> SwitchSourceSettings.setEnabled(!SwitchSourceSettings.isEnabled()));
    }

    /**
     * 页脚上的一格开关：图标 + 标题一行 + 短副标题一行 + 右端开关。
     *
     * <p>开关的轨道由 {@link NcmSheet#toggle} 自己补间；这一格补的是整格的 hover 底色与
     * 左侧图标的点亮 —— 鼠标扫过来时，图标先于开关半拍亮起，指向「这一整格是一件事」。</p>
     */
    private void footerToggle(final Panel dialog, final int index, final Supplier<String> glyph,
                              final String titleText, final String hint,
                              final BooleanSupplier state, final Runnable flip) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        final double[] hover = {0.0};
        final long[] hoverNanos = new long[1];
        surface.setBeforeRenderCallback(() -> {
            easeMs(hover, surface.isHovering() ? 1.0 : 0.0, 70.0, hoverNanos);
            double width = Math.max(1, (dialog.getWidth() - (PAD + CARD_INSET) * 2 - 8.0) / 2.0);
            surface.setBounds(width, 40);
            surface.setPosition(PAD + CARD_INSET + (width + 8.0) * index,
                    dialog.getHeight() - FOOTER_H + 4);
            surface.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        icon(surface, glyph, ICON_M, () -> 17.0, () -> 20.0,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        state.getAsBoolean() ? .72 + .28 * hover[0] : hover[0] * .35));

        final LabelWidget title = label(surface, titleText, FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, surface.getWidth() - 32 - 46));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(32, inkTop(title, 13.0));
        });

        final LabelWidget hintLabel = label(surface, hint, FontManager.pf12);
        hintLabel.setBeforeRenderCallback(() -> {
            hintLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 46 = 开关宽 34 + 到右缘 12。
            hintLabel.setMaxWidth(Math.max(1, surface.getWidth() - 32 - 46));
            hintLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hintLabel.setPosition(32, inkTop(hintLabel, 28.0));
        });

        toggle(surface, state, flip,
                () -> surface.getWidth() - 12, () -> 20.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
    }

    // ════════════════════════════ 二级页：GD 平台选择 ════════════════════════════

    private void showGdPlatforms() {
        page = PAGE_GD;
        beginPage();

        final List<GdStudioMusicService.Platform> platforms = GdStudioMusicService.getPlatforms();
        final Panel dialog = sheet(516,
                () -> 88 + 6 + Math.min(platforms.size() * PLATFORM_ROW, 280.0) + 44);
        subHeader(dialog, back(this::showOverview), () -> "GD音乐台 · 在线聚合",
                () -> "选一个平台作为独立内容源；再点当前平台即可关闭",
                MusicPlatform.GD::getBrandColor);
        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> 60.0);

        final Panel body = body(dialog, () -> 88.0, () -> 44.0);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停生长是绕着自己中心做的，贴着上边会被硬裁切掉圆角。
        strip(feed, 6.0);
        for (int i = 0; i < platforms.size(); i++) {
            platformRow(strip(feed, PLATFORM_ROW), i, platforms.get(i));
        }

        tip(dialog, () -> PartyIcons.TIMER,
                () -> "额度剩余 " + GdStudioMusicService.remainingRequestBudget() + "/50 · 由 music-api.gdstudio.xyz 提供",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 30);
    }

    private void platformRow(final Panel row, final int slot, final GdStudioMusicService.Platform platform) {
        final BooleanSupplier picked = () -> platform.key.equals(GdStudioSourceSettings.getPlatform());
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> PLATFORM_CARD, () -> picked.getAsBoolean()
                ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                MusicPlatform.GD.getBrandColor(), .26)
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            togglePlatform(platform);
            return true;
        });
        stroke(card, 12.0, MusicPlatform.GD::getBrandColor, () -> picked.getAsBoolean() ? .55 : 0.0);

        // 左侧状态盘：选中 / 冷却 / 可用各有自己的图标，不必去读右边那行小字才知道能不能用。
        final RoundedRectWidget dot = new RoundedRectWidget();
        card.addChild(dot);
        dot.setGlassRim(GlassLook.Surface.PLAYER);
        dot.setClickable(false);
        dot.setRadius(9);
        dot.setBeforeRenderCallback(() -> {
            dot.setBounds(26, 26);
            dot.setPosition(12, card.getHeight() * .5 - 13);
            dot.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    rowTone(platform, picked), .30));
        });
        icon(dot, () -> rowGlyph(platform, picked), ICON_M, () -> 13.0, () -> 13.0,
                () -> rowTone(platform, picked));

        final LabelWidget name = label(card, platform.displayName, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - 48 - 96));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(48, inkTop(name, card.getHeight() * .5 - 8));
        });

        // 静态说明写的是上游支持什么；运行时说明写的是熔断冷却 —— 刚失败过的源要能就地解释自己。
        final LabelWidget desc = label(card, () -> {
            String runtime = GdStudioMusicService.statusLabel(platform.key);
            return platform.key + " · " + (runtime.isEmpty() ? platform.status.description : runtime);
        }, FontManager.pf12);
        desc.setBeforeRenderCallback(() -> {
            desc.setColor(GdStudioMusicService.statusLabel(platform.key).isEmpty()
                    ? platform.status.color : WARN_COLOR);
            desc.setMaxWidth(Math.max(1, card.getWidth() - 48 - 96));
            desc.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            desc.setPosition(48, inkTop(desc, card.getHeight() * .5 + 8));
        });

        pill(card, () -> picked.getAsBoolean() ? PartyIcons.CHECK : PartyIcons.PLAY,
                () -> picked.getAsBoolean() ? "使用中" : "选用",
                () -> picked.getAsBoolean() ? OK_COLOR
                        : mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.ACCENT), self[0].hover),
                () -> card.getWidth() - 12, () -> card.getHeight() * .5);
    }

    private void togglePlatform(final GdStudioMusicService.Platform platform) {
        boolean enable = !platform.key.equals(GdStudioSourceSettings.getPlatform());
        if (!GdStudioSourceSettings.setPlatform(enable ? platform.key : "")) return;

        if (enable) {
            NeteaseMusicService.setCurrentPlatform(MusicPlatform.GD);
            statusText = "已切换到「" + platform.displayName + "」内容源";
            statusTone = 1;
        } else {
            if (NeteaseMusicService.getCurrentPlatform() == MusicPlatform.GD) {
                NeteaseMusicService.setCurrentPlatform(MusicPlatform.NETEASE);
            }
            statusText = "已关闭 GD音乐台 内容源";
            statusTone = 0;
        }
        resetToHomeForCurrentSource();
        showGdPlatforms();
    }

    // ════════════════════════════ 三级页：自定义音源（LX Music 脚本） ════════════════════════════

    private void showLxSources() {
        page = PAGE_LX;
        lxRevision = LxSourceService.getRevision();
        // 转译器在进游戏时就开始下载（见 MuoniumPlayerExtension.init）；这里再兜一次重评：
        // 启动时网络没好、预算冷却刚过期的会话，用户进这一页时补触发，不用等下次启动。
        com.muoniumplayer.core.ncm.lxsource.ClosureCompilerDownload.ensureDownload();
        beginPage();

        final List<LxSourceStore.Entry> scripts = LxSourceStore.list();
        final Panel dialog = sheet(524,
                () -> LX_HEADER_H + Math.min(lxContentHeight(scripts.size()), 300.0) + LX_FOOTER_H);
        subHeader(dialog, back(this::showOverview), () -> "自定义音源 · LX Music 脚本",
                () -> "导入 .js 脚本由它来解析播放链接；与上游一致，同时只启用一个",
                () -> LX_COLOR);
        tip(dialog, this::lxStatusGlyph, this::lxStatusText, this::lxStatusColor, () -> 60.0);

        final Panel body = body(dialog, () -> LX_HEADER_H, () -> LX_FOOTER_H);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停生长绕着自己中心做，贴着上边会被硬裁切掉圆角。
        strip(feed, 6.0);

        sectionLabel(strip(feed, LABEL_ROW),
                scripts.isEmpty() ? "已导入的脚本" : "已导入的脚本（" + scripts.size() + "）",
                () -> 5.0, () -> LX_COLOR);
        if (scripts.isEmpty()) {
            tip(strip(feed, 44.0), () -> PartyIcons.FOLDER_OPEN,
                    () -> "还没有脚本。点下方「从网址导入」填一个下载链接、挑一个 .js 文件，或复制正文 / 链接后从剪贴板导入。",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), () -> 10.0);
        }
        for (int i = 0; i < scripts.size(); i++) {
            scriptRow(strip(feed, LX_ROW_H), i, scripts.get(i));
        }

        sectionLabel(strip(feed, LABEL_ROW_2), "运行状态", () -> 9.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        runtimeCard(strip(feed, LX_RUNTIME_ROW), scripts.size());

        // 脚本权限跟着脚本走：这两颗开关决定的是「启用的那个脚本能多做什么」，挂在滚动区里
        // 紧挨着脚本列表（旧版塞在页脚，把脚本列表压得只剩两三行）。没有启用项时整组调暗——
        // 调暗走一条补间而不是瞬时色差：启用/停用脚本的那一下，权限组的墨色是滑过去的。
        // 补间按当前状态起步：LX 页重建（导入/删除触发的原地重建）时墨色不闪。
        final double[] enabledInk = {LxSourceService.isEnabled() ? 1.0 : 0.0};
        final long[] enabledNanos = new long[1];
        final IntSupplier permInk = () -> mix(
                mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), .45),
                NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), enabledInk[0]);

        sectionLabel(strip(feed, LABEL_ROW_2), "脚本权限", () -> 9.0, () -> LX_COLOR);
        lxToggleRow(strip(feed, LX_PERM_ROW), () -> PartyIcons.ALBUM,
                "允许脚本提供搜索 / 歌词 / 封面",
                LxSourceService::isExtraActionsEnabled,
                () -> LxSourceService.setExtraActions(!LxSourceService.isExtraActionsEnabled()),
                permInk);
        lxToggleRow(strip(feed, LX_PERM_ROW), () -> PartyIcons.MAIL,
                "允许脚本弹出作者的更新提示",
                LxSourceService::isUpdateAlertAllowed, LxSourceService::flipUpdateAlert,
                permInk);
        // 补间驱动器：两行开关各自占用了自己的回调，借一块不画东西的板子统一推进墨色。
        final RoundedRectWidget inkDriver = new RoundedRectWidget();
        addChild(inkDriver);
        inkDriver.setClickable(false).setBeforeRenderCallback(() -> {
            inkDriver.setBounds(0, 0);
            inkDriver.setAlpha(0f);
            easeMs(enabledInk, LxSourceService.isEnabled() ? 1.0 : 0.0, 90.0, enabledNanos);
        });
        // 安全说明是权限那一组的注脚：警告的本体就是上面两颗开关放出去的东西。
        tip(strip(feed, 24.0), () -> PartyIcons.WARNING,
                () -> "脚本在本机执行、能发任意网络请求（已断掉它访问 Java 类的路）：只导入你信得过的来源",
                () -> WARN_COLOR, () -> 2.0);

        lxFooter(dialog);

        // 网址导入弹窗挂在整层最上面：它不属于任何一页的滚动内容，每次重建页面都按开合状态
        // 重新搭一遍 —— hidden 的组件不渲染也不吃事件，关着的时候等于不存在。
        buildLxUrlDialog();
    }

    private double lxContentHeight(final int count) {
        return 6.0 + LABEL_ROW + (count == 0 ? 44.0 : LX_ROW_H * count)
                + LABEL_ROW_2 + LX_RUNTIME_ROW
                + LABEL_ROW_2 + LX_PERM_ROW * 2 + 24.0;
    }

    /** 列表里的一行脚本。点一下即启用；已启用的再点一下就关掉，与字体列表同一套手感。 */
    private void scriptRow(final Panel row, final int slot, final LxSourceStore.Entry entry) {
        final BooleanSupplier picked = () -> LxSourceService.isActive(entry.id);
        final Card[] self = new Card[1];
        // card(...) 的入场闸门是 slot*.07，slot 到 15 就再也不会打开；脚本数量不可控，必须夹住。
        final Card card = card(row, Math.min(slot, 9), () -> 0.0, () -> LX_CARD_H,
                () -> picked.getAsBoolean()
                        ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), LX_COLOR, .30)
                        : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            LxSourceService.toggle(entry.id);
            return true;
        });
        stroke(card, 12.0, () -> LX_COLOR, () -> picked.getAsBoolean() ? .58 : 0.0);

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setBeforeRenderCallback(() -> {
            disc.setRadius(11);
            disc.setBounds(LX_DISC, LX_DISC);
            disc.setPosition(12.0, card.getHeight() * .5 - LX_DISC * .5);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), LX_COLOR,
                    picked.getAsBoolean() ? .44 : .26));
        });
        icon(disc, PartyIcons.TEXT_FORMAT, ICON_M, () -> LX_DISC * .5, () -> LX_DISC * .5,
                () -> LX_COLOR);

        final LabelWidget title = label(card, () -> scriptTitle(entry), FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - LX_TEXT_LEFT - LX_RESERVE));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(LX_TEXT_LEFT, inkTop(title, card.getHeight() * .5 - 9));
        });

        final LabelWidget note = label(card, () -> scriptNote(entry), FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, card.getWidth() - LX_TEXT_LEFT - LX_RESERVE));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(LX_TEXT_LEFT, inkTop(note, card.getHeight() * .5 + 9));
        });

        // 胶囊整组控透明度：让它自己按状态改颜色的话，没在用的行上会留一枚透明的空胶囊。
        final Panel badge = veil(card, () -> picked.getAsBoolean() ? 1.0 : 0.0);
        pill(badge, () -> PartyIcons.CHECK, () -> "使用中", () -> LX_COLOR,
                () -> card.getWidth() - 46.0, () -> card.getHeight() * .5);

        deleteButton(card, entry);
    }

    /**
     * 删除按钮。
     *
     * <p>不做二次确认页：导入是一秒钟的事，删错了再导一次就好，而一个确认页会让「清理掉三个
     * 试过的脚本」变成六次点击。</p>
     */
    private void deleteButton(final Card card, final LxSourceStore.Entry entry) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(9);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            LxSourceService.removeScript(entry.id);
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
    /**
     * 运行状态卡：脚本声明了哪些源、有没有经过转译，以及 console 的最后几行。
     *
     * <p>把 console 摊在界面上而不是只写进日志文件：自定义源出问题时，用户手里唯一的线索就是
     * 脚本自己打的那几行，让他去翻 latest.log 等于让他放弃。</p>
     */
    private void runtimeCard(final Panel row, final int slot) {
        final Card card = plain(card(row, Math.min(slot + 1, 9), () -> 0.0, () -> LX_RUNTIME_CARD,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));

        icon(card, this::lxStatusGlyph, ICON_M, () -> 22.0, () -> 22.0, this::lxStatusColor);

        final LabelWidget headline = label(card, this::runtimeHeadline, FontManager.pf12bold);
        headline.setBeforeRenderCallback(() -> {
            headline.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            headline.setMaxWidth(Math.max(1, card.getWidth() - 52));
            headline.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            headline.setPosition(40, inkTop(headline, 22.0));
        });

        for (int i = 0; i < LX_LOG_LINES; i++) {
            final int index = i;
            final LabelWidget line = label(card, () -> logLine(index), FontManager.pf10bold);
            line.setBeforeRenderCallback(() -> {
                line.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), .82));
                line.setMaxWidth(Math.max(1, card.getWidth() - 32));
                line.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                line.setPosition(16, inkTop(line, 44.0 + index * 12.0));
            });
        }
    }

    // ──────────────────────────── 自定义源页脚 ────────────────────────────

    /**
     * LX 页页脚：只剩一行四颗导入/刷新按钮。
     *
     * <p>两行权限开关与安全警告搬进了滚动区（它们是脚本权限，语义上跟脚本走），页脚从 138
     * 收到 44，脚本列表 因此多出近一屏的呼吸位。</p>
     */
    private void lxFooter(final Panel dialog) {
        divider(dialog, () -> dialog.getHeight() - LX_FOOTER_H + 2);

        lxFooterButton(dialog, 0, () -> PartyIcons.FOLDER_OPEN, () -> "从文件导入",
                LxSourceService::browseForFile);
        // 一个按钮吃下「链接」和「正文」两种剪贴板内容：用户复制过来的要么是 raw 链接、
        // 要么是整段脚本，让程序看一眼比让他先选一次更省事。
        lxFooterButton(dialog, 1, () -> PartyIcons.LINK, () -> "从剪贴板导入",
                () -> LxSourceService.importFromText(KeyboardUtils.getClipboardString()));
        // 手输的网址走二级弹窗：网址导入终究是「偶尔用一次」的路径，弹窗恰好表达
        // 「要用的时候才出现」。
        lxFooterButton(dialog, 2, () -> PartyIcons.PUBLIC_ROOM, () -> "从网址导入",
                this::openLxUrlDialog);
        lxFooterButton(dialog, 3, () -> PartyIcons.SYNC, () -> "重新加载",
                LxSourceService::reloadActive);
    }

    /**
     * 滚动区里的一行权限开关：图标 + 一行字 + 右端的开关。
     *
     * <p>宿主是 {@code feed} 里的一条 {@code strip}（等宽行），所以文字的可用宽度按行宽算，
     * 不再需要读弹窗高度。</p>
     */
    private void lxToggleRow(final Panel row, final Supplier<String> glyph, final String text,
                             final BooleanSupplier state, final Runnable flip,
                             final IntSupplier ink) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        row.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(9);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 30);
            surface.setPosition(PAD + CARD_INSET, 2.0);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        icon(surface, glyph, ICON_S, () -> 17.0, () -> 15.0, ink);

        final LabelWidget caption = label(surface, text, FontManager.pf12);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(ink.getAsInt());
            caption.setMaxWidth(Math.max(1, surface.getWidth() - 30 - 52));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(30, inkTop(caption, 15.0));
        });

        toggle(surface, state, flip,
                () -> surface.getWidth() - 10, () -> 15.0,
                () -> LX_COLOR);
    }

    /**
     * 网址导入弹窗：页脚「从网址导入」点开的二级弹窗，一个输入框加两颗按钮。
     *
     * <p>「从剪贴板导入」只覆盖「先复制好了再点」的路径；很多用户是照着别处开着的一个链接，
     * 手动输进来更直接。给它开一个二级弹窗而不是在页脚塞一行常驻输入栏：网址导入是「偶尔用
     * 一次」的路径，页脚还要给两行开关与安全说明留位置，弹窗的「要用时才出现」恰好合适。</p>
     *
     * <p>组件挂在整层（{@code this}）而不是 lx 页的弹窗板上：页间切换会 {@code beginPage()}
     * 清空子组件，挂在那上面会被误清。每次页面重建都按 {@link #lxUrlDialogOpen} 重新搭一遍，
     * 输入内容经 {@link #lxUrlDraft} 回填；关着的时候整套 hidden，不渲染也不吃事件，等于不存在。</p>
     */
    private void buildLxUrlDialog() {
        final boolean open = lxUrlDialogOpen;

        // 遮罩要能点：点它关弹窗。sheet() 那层遮罩不吃点击是因为「点空白关整层」走根回调，
        // 而根回调在这里的意思还是关整层 —— 二级弹窗的点空白关闭必须自己消费掉，不然会漏下去。
        lxUrlMask = new RectWidget();
        addChild(lxUrlMask);
        lxUrlMask.setColor(0x000000);
        lxUrlMask.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            closeLxUrlDialog();
            return true;
        });
        lxUrlMask.setBeforeRenderCallback(() -> {
            lxUrlMask.setMargin(0);
            lxUrlMask.setBounds(Math.max(1, getWidth()), Math.max(1, getHeight()));
            lxUrlMask.setPosition(0, 0);
            lxUrlMask.setAlpha((float) (clamp01(lxUrlReveal) * .36 * presentation));
        });

        lxUrlCard = new RoundedRectWidget();
        addChild(lxUrlCard);
        lxUrlCard.setRadius(14);
        lxUrlCard.setGlassRim(GlassLook.Surface.PLAYER);
        // 卡片自己吃掉落在它身上的点击：不返回 true 的话，点卡片会透过它触发遮罩的关闭。
        lxUrlCard.setOnClickCallback((x, y, button) -> button == 0);
        lxUrlCard.setBeforeRenderCallback(() -> {
            double width = Math.max(1, Math.min(452, getWidth() - 40));
            lxUrlCard.setBounds(width, 164);
            lxUrlCard.setAlpha((float) (clamp01(lxUrlReveal) * presentation));
            lxUrlCard.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - 82 + (1.0 - clamp01(lxUrlReveal)) * 10.0);
            lxUrlCard.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            lxUrlCard.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        stroke(lxUrlCard, 14.0, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER),
                () -> .55 * clamp01(lxUrlReveal));

        // 标题盘与标题：与 header() 同一套版式，只是没有返回键 —— 关闭走遮罩、ESC 和取消。
        final RoundedRectWidget plate = new RoundedRectWidget();
        lxUrlCard.addChild(plate);
        plate.setClickable(false).setRadius(11);
        plate.setGlassRim(GlassLook.Surface.PLAYER);
        plate.setBeforeRenderCallback(() -> {
            plate.setBounds(34, 34);
            plate.setPosition(PAD, 12);
            plate.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), LX_COLOR, .28));
        });
        icon(plate, PartyIcons.LINK, ICON_M, () -> 17.0, () -> 17.0, () -> LX_COLOR);

        final LabelWidget title = label(lxUrlCard, "从网址导入", FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(PAD + 45, inkTop(title, 26.0));
        });

        final LabelWidget caption = label(lxUrlCard, this::lxUrlDialogCaption, FontManager.pf12);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(lxUrlDialogBad ? BAD_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setMaxWidth(Math.max(1, lxUrlCard.getWidth() - (PAD + CARD_INSET) * 2));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(PAD + CARD_INSET + 2, inkTop(caption, 56.0));
        });

        // 输入框：与账号页 Cookie 栏同一套手感（粘贴、选中、聚焦描边）。
        final RoundedRectWidget backing = new RoundedRectWidget();
        lxUrlCard.addChild(backing);
        backing.setClickable(false);
        backing.setRadius(9);
        backing.setBeforeRenderCallback(() -> {
            backing.setBounds(Math.max(1, lxUrlCard.getWidth() - (PAD + CARD_INSET) * 2), 36);
            backing.setPosition(PAD + CARD_INSET, 66);
            backing.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            backing.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        stroke(backing, 9.0, () -> LX_COLOR,
                () -> lxUrlField != null && lxUrlField.isFocused() ? .7 : 0.0);

        lxUrlField = new TextFieldWidget(FontManager.pf12) {
            @Override
            public boolean onKeyTyped(char character, int keyCode) {
                // 聚焦的 TextField 会吞掉一切按键（PlaylistCreateSheet 有同样的拦截），
                // 所以回车提交与 ESC 关闭都得在这里拦，拦完就别再放给 super。
                if (keyCode == Keyboard.KEY_ESCAPE) {
                    closeLxUrlDialog();
                    return true;
                }
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN
                        || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    submitLxUrlDialog();
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        lxUrlCard.addChild(lxUrlField);
        lxUrlField.getTextField().setMaxStringLength(1024);
        lxUrlField.setEnabled(true);
        lxUrlField.setPlaceholder("输入或粘贴脚本的下载链接 https://…");
        lxUrlField.drawUnderline(false);
        // 草稿跟着每次编辑走，而不是在关闭时从框里捞：提交路径要把草稿清空，
        // 关闭时再读一遍会把刚提交的网址又存回去。
        lxUrlField.setTextChangedCallback(newText -> lxUrlDraft = newText == null ? "" : newText);
        lxUrlField.setBeforeRenderCallback(() -> {
            lxUrlField.setBounds(Math.max(1, backing.getWidth() - 20),
                    Math.max(1, backing.getHeight() - 10));
            lxUrlField.setPosition(backing.getRelativeX() + 10, backing.getRelativeY() + 5);
            lxUrlField.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            lxUrlField.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 模态弹窗里它是唯一的输入目标，索性钉住焦点：TextField#updateStyles 对「框外按下」
            // 一律失焦，而打开弹窗的那次点击恰好就是框外按下 —— 不钉的话焦点一帧就丢。
            lxUrlField.setFocused(true);
        });

        final RoundedButtonWidget cancel = action(lxUrlCard, () -> PartyIcons.CLOSE, () -> "取消",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                this::closeLxUrlDialog);
        cancel.setBeforeRenderCallback(() -> {
            cancel.setBounds(88, 30);
            cancel.setPosition(PAD + CARD_INSET, lxUrlCard.getHeight() - 40);
            cancel.setColor(cancel.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget confirm = action(lxUrlCard, () -> PartyIcons.CHECK, () -> "导入",
                FontManager.pf12bold, () -> 0xFFFFFF, this::submitLxUrlDialog);
        confirm.setBeforeRenderCallback(() -> {
            confirm.setBounds(120, 30);
            confirm.setPosition(lxUrlCard.getWidth() - PAD - CARD_INSET - 120,
                    lxUrlCard.getHeight() - 40);
            confirm.setColor(mix(LX_COLOR, 0xFFFFFF, confirm.isHovering() ? .16 : 0.0));
        });

        lxUrlMask.setHidden(!open);
        lxUrlCard.setHidden(!open);
        lxUrlField.setText(lxUrlDraft);
        if (open) lxUrlField.setFocused(true);
    }

    /** 弹窗内那行提示：没有要专门说的话就给默认说明。 */
    private String lxUrlDialogCaption() {
        return lxUrlDialogStatus.isEmpty()
                ? "粘贴脚本作者给出的 http(s) 下载链接，回车或点「导入」" : lxUrlDialogStatus;
    }

    /** 打开弹窗。组件由最近一次页面重建搭好，这里只负责点亮与归零。 */
    private void openLxUrlDialog() {
        lxUrlDialogOpen = true;
        lxUrlDialogStatus = "";
        lxUrlDialogBad = false;
        lxUrlReveal = 0.0;
        if (lxUrlCard != null) lxUrlCard.setHidden(false);
        if (lxUrlMask != null) lxUrlMask.setHidden(false);
        if (lxUrlField != null) {
            lxUrlField.setText(lxUrlDraft);
            lxUrlField.setFocused(true);
        }
    }

    /**
     * 关掉弹窗。组件 hidden 掉即可，页面与列表原地不动。
     *
     * <p>草稿不在这里捞：它由输入框的编辑回调实时维护（见 {@link #buildLxUrlDialog()}），
     * 提交路径已经把它清掉了，这里再读一遍会把刚提交的网址又存回去。</p>
     */
    private void closeLxUrlDialog() {
        lxUrlDialogOpen = false;
        lxUrlDialogStatus = "";
        lxUrlDialogBad = false;
        if (lxUrlCard != null) lxUrlCard.setHidden(true);
        if (lxUrlMask != null) lxUrlMask.setHidden(true);
    }

    /**
     * 提交网址。校验放在弹窗里当场直说，别等下载线程回头才报错；真正的下载仍交给
     * {@link LxSourceService#importFromUrl}，结果（正在下载 / 成功 / 失败）走页眉状态行 ——
     * 与剪贴板导入是同一条反馈链路。
     */
    private void submitLxUrlDialog() {
        final String url = lxUrlField.getText() == null ? "" : lxUrlField.getText().trim();
        if (url.isEmpty()) {
            lxUrlDialogStatus = "先填一个脚本的下载链接";
            lxUrlDialogBad = true;
            return;
        }
        if (!LxSourceService.looksLikeUrl(url)) {
            lxUrlDialogStatus = "这不像一个链接：要以 http:// 或 https:// 开头";
            lxUrlDialogBad = true;
            return;
        }
        if (LxSourceService.isBusy()) {
            lxUrlDialogStatus = "正在处理上一次导入，稍等一下再试";
            lxUrlDialogBad = true;
            return;
        }
        lxUrlDraft = "";
        closeLxUrlDialog();
        LxSourceService.importFromUrl(url);
    }

    private void lxFooterButton(final Panel dialog, final int index, final Supplier<String> glyph,
                                final Supplier<String> text, final Runnable onClick) {
        final RoundedButtonWidget button = action(dialog, glyph, text, FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), onClick);
        button.setBeforeRenderCallback(() -> {
            double span = Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2);
            double width = Math.max(1, (span - 24.0) / 4.0);
            button.setBounds(width, 30);
            // 页脚带高 44：按钮 30 在带内垂直居中，距底 12。旧的 -126 是两行开关还塞在页脚
            // 时代的坐标，页脚收到 44 之后这句没人跟着改，按钮飘进滚动区、压在「脚本权限」上。
            button.setPosition(PAD + CARD_INSET + (width + 8.0) * index,
                    dialog.getHeight() - LX_FOOTER_H + (LX_FOOTER_H - 30.0) * .5);
            // 与账号浮层页脚同一道闸：进场随页脚档落位，退场先收；没到场不接点击。
            button.setClickable(footerGate() > .5);
            button.setAlpha((float) footerGate());
            button.setColor(button.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
    }
    // ──────────────────────────── 自定义源文案 ────────────────────────────

    private String buildLxSummary() {
        int count = LxSourceStore.list().size();
        if (count == 0) return "未导入 · 支持 LX Music 的 .js 自定义源脚本";
        if (!LxSourceService.isEnabled()) return "已导入 " + count + " 个脚本 · 当前未启用";
        String status = LxSourceService.statusLabel();
        return "已启用 · " + (status.isEmpty() ? "正在加载" : status);
    }

    private String scriptTitle(final LxSourceStore.Entry entry) {
        String name = entry.info.getName();
        return name.isEmpty() ? "未命名脚本" : name;
    }

    private String scriptNote(final LxSourceStore.Entry entry) {
        String byline = entry.info.getByline();
        String description = entry.info.getDescription();
        if (byline.isEmpty()) return description.isEmpty() ? "头注释里没写版本与作者" : description;
        return description.isEmpty() ? byline : byline + " · " + description;
    }

    private String runtimeHeadline() {
        if (!LxSourceService.isEnabled()) return "未启用脚本，播放走内置解析链路";
        // 转译器（Closure，进游戏时按需下载）没就绪时脚本被暂缓：明说「准备中」，
        // 带下载进度。绝不能落进下面 runtime == null 的「等待加载」或 FAILED 的「加载失败」——
        // 那两种都读起来像坏了，而这是暂态。
        final String transpiler = com.muoniumplayer.core.ncm.lxsource.ClosureCompilerDownload.headlineSuffix();
        if (LxSourceService.isWaitingForTranspiler()) {
            return "正在准备脚本转译器" + (transpiler == null ? "…" : " · " + transpiler.replace("转译器", ""));
        }
        LxRuntime runtime = LxSourceService.getRuntime();
        if (runtime == null) return "已选中脚本，等待加载";
        final String base;
        switch (runtime.getState()) {
            case READY:
                base = "已就绪 · 可解析 " + String.join("/", runtime.getSources().keySet())
                        + (runtime.isTranspiled() ? " · 已转译为 ES5" : "");
                break;
            case LOADING:
                base = "正在转译并执行脚本…";
                break;
            case FAILED:
                base = "加载失败：" + runtime.getError();
                break;
            default:
                base = "已停止";
                break;
        }
        // 转译器没就绪时在这行补一句下载状态：用户能看出「新语法脚本暂时解析不了」
        // 是转译器还在路上，而不是脚本坏了。就绪时 suffix 为 null、一行照旧。
        return transpiler == null ? base : base + " · " + transpiler;
    }

    /** 取 console 尾巴的第 index 行（不足时给空串，让那一行自然消失）。 */
    private String logLine(final int index) {
        List<String> logs = LxSourceService.getLogs();
        int at = Math.max(0, logs.size() - LX_LOG_LINES) + index;
        return at >= 0 && at < logs.size() ? logs.get(at) : "";
    }

    /** 状态行优先显示上一次操作的结果，没有操作时显示运行时状态。 */
    private String lxStatusText() {
        String notice = LxSourceService.getNotice();
        if (!notice.isEmpty()) return notice;
        String status = LxSourceService.statusLabel();
        return status.isEmpty()
                ? "导入的脚本排在内置解析链路之前；它取不到链接时会自动回退"
                : status;
    }

    private int lxStatusColor() {
        if (LxSourceService.isNoticeBad()) return BAD_COLOR;
        if (LxSourceService.isBusy()) return WARN_COLOR;
        LxRuntime runtime = LxSourceService.getRuntime();
        if (runtime == null) return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        if (runtime.getState() == LxRuntime.State.FAILED) return BAD_COLOR;
        if (runtime.getState() == LxRuntime.State.LOADING) return WARN_COLOR;
        return runtime.getState() == LxRuntime.State.READY
                ? OK_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String lxStatusGlyph() {
        if (LxSourceService.isNoticeBad()) return PartyIcons.WARNING;
        if (LxSourceService.isBusy()) return PartyIcons.HOURGLASS;
        LxRuntime runtime = LxSourceService.getRuntime();
        if (runtime == null) return PartyIcons.INFO;
        switch (runtime.getState()) {
            case READY:
                return PartyIcons.CHECK;
            case LOADING:
                return PartyIcons.HOURGLASS;
            case FAILED:
                return PartyIcons.WARNING;
            default:
                return PartyIcons.INFO;
        }
    }

    // ════════════════════════════ 卡片零件 ════════════════════════════

    /** 选中卡上的一层横向白色渐隐 + 一圈描边。品牌色铺整块会是一片死板的纯色。 */
    private void selectionSheen(final Card card, final BooleanSupplier picked) {
        selectionSheen(card, picked, null);
    }

    /**
     * 同上，但高光与描边跟一条<b>已有的选中进度</b>补间（0..1），而不是布尔跳变。
     *
     * <p>方格版传 {@code progress}：切换来源时旧格的高光收回去、新格的高光亮起来，
     * 两段是同时推进的补间而不是两下闪变。传 null 走旧的布尔路径（本文件已无调用方，
     * 留着是为了不让签名变更惊动别的 sheet —— 它们用的是基类同名方法）。</p>
     */
    private void selectionSheen(final Card card, final BooleanSupplier picked,
                                final java.util.function.DoubleSupplier progress) {
        final Wash sheen = wash(card, true, 12.0);
        sheen.tint(0xFFFFFF, 0xFFFFFF).alphas(.14, 0.0);
        sheen.setBeforeRenderCallback(() -> {
            sheen.setBounds(Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));
            sheen.setPosition(0, 0);
            sheen.setAlpha((float) (progress == null
                    ? (picked.getAsBoolean() ? 1.0 : 0.0)
                    : progress.getAsDouble()));
        });
        stroke(card, 12.0, () -> 0xFFFFFF, () -> .26 * (progress == null
                ? (picked.getAsBoolean() ? 1.0 : 0.0)
                : progress.getAsDouble()));
    }



    /**
     * 选中标记：一枚白底圆里放品牌色的勾。
     *
     * <p>不用 {@link #pill}：胶囊的底色是从页面底色推出来的，落在品牌色卡片上会变成一块灰扑扑的
     * 补丁。白底 + 品牌色勾在任何品牌色上都成立。整块套一层 {@link #veil} 控透明度，是因为
     * 胶囊/图标这类零件自己已经占了 {@code beforeRenderCallback}，调用方再挂一个会覆盖它的排版。</p>
     */
    private void checkBadge(final Card card, final BooleanSupplier picked, final IntSupplier tint,
                            final java.util.function.DoubleSupplier centerX) {
        checkBadge(card, picked, tint, centerX, null);
    }

    /**
     * 同上，但徽标跟一条已有的选中进度补间，并且带<b>缩放弹入</b>。
     *
     * <p>进度走 .22f 补间的同时徽标从 0.6 倍长到 1 倍（过了中点才开始放大，读起来是
     * 「颜色先铺满、对勾再点头」的两段动作），消失时反着缩回去。传 {@code progress == null}
     * 走旧的布尔路径（GD/LX 入口格的选中态不铺色，徽标突然出现也不突兀）。</p>
     */
    private void checkBadge(final Card card, final BooleanSupplier picked, final IntSupplier tint,
                            final java.util.function.DoubleSupplier centerX,
                            final java.util.function.DoubleSupplier progress) {
        final Panel layer = veil(card, () -> progress == null
                ? (picked.getAsBoolean() ? 1.0 : 0.0)
                : Math.min(1.0, progress.getAsDouble() * 1.6));
        final RoundedRectWidget badge = new RoundedRectWidget();
        layer.addChild(badge);
        badge.setClickable(false);
        badge.setRadius(11);
        badge.setBeforeRenderCallback(() -> {
            double scale = progress == null ? 1.0
                    : .6 + .4 * Math.max(0.0, progress.getAsDouble() * 2.0 - 1.0);
            double size = 22.0 * scale;
            badge.setRadius(size * .5);
            badge.setBounds(size, size);
            // 徽标缩放绕自己的中心做：右缘仍钉在 centerX，中心点不随缩放漂移。
            badge.setPosition(centerX.getAsDouble() - size * .5,
                    layer.getHeight() * .5 - size * .5);
            badge.setColor(0xFFFFFF);
        });
        icon(badge, PartyIcons.CHECK, ICON_M, () -> badge.getWidth() * .5,
                () -> badge.getHeight() * .5, tint);
    }

    // ════════════════════════════ 文案与状态 ════════════════════════════

    /** 官方卡副标题：这个来源现在能不能用。比一句写死的能力描述有用得多。 */
    private int qishuiBackendStatusColor() {
        return switch (QishuiCompanionDownload.backendStatus()) {
            case RUNNING -> OK_COLOR;
            case FAILED, STOPPED, UNSUPPORTED -> BAD_COLOR;
            case CONNECTING, DOWNLOADING, VERIFYING, INSTALLING, STARTING -> WARN_COLOR;
        };
    }

    private String qishuiBackendStatusGlyph() {
        return switch (QishuiCompanionDownload.backendStatus()) {
            case RUNNING -> PartyIcons.CHECK;
            case FAILED, STOPPED, UNSUPPORTED -> PartyIcons.WARNING;
            case CONNECTING, DOWNLOADING, VERIFYING, INSTALLING, STARTING -> PartyIcons.HOURGLASS;
        };
    }

    private String accountLine(final MusicPlatform platform) {
        if (platform == MusicPlatform.YOUTUBE) {
            return "无需登录 · 搜索并播放 YouTube 音频";
        }
        if (platform == MusicPlatform.BILIBILI) {
            if (NeteaseMusicService.isLoggedIn(platform)) {
                return "已登录 · " + NeteaseMusicService.getAccountName(platform);
            }
            // 匿名就能搜与播，这一点不能写成「未登录不可用」；扫码换来的则是字幕歌词和收藏夹。
            return "未登录 · 可搜索播放，扫码后解锁歌词与收藏夹";
        }
        if (platform == MusicPlatform.QISHUI) {
            // 平台不支持先把话说死：签名后端只有 Windows 包（签名器是 Windows 客户端里的
            // 原生模块），别的系统装不了也跑不了。搜索与试听仍可用——匿名 H5 接口不经过后端。
            if (!QishuiCompanionDownload.supportedPlatform()) {
                return "完整版仅支持 Windows · 本平台可搜索与试听";
            }
            if (!QishuiBackend.peekAvailable()) {
                return QishuiBackend.hasProbed()
                        ? "仅试听 · 后端未就绪时会自动重试"
                        : "正在连接 · 后端就绪后可播放完整版";
            }
            // 完整版的 Cookie 实际由后端持有(签名代理必须带它转发)。登录工具导入的凭据
            // 只落在本机后端时,模组会话库是空的——这时绝不能显示「未登录」误导用户重登。
            if (NeteaseMusicService.isLoggedIn(platform)) {
                return "已登录 · " + NeteaseMusicService.getAccountName(platform);
            }
            if (QishuiBackend.peekHasCookie()) {
                return "已登录 · 凭据在本机签名后端";
            }
            return "未登录 · 可试听；导入 Cookie 后播放完整版";
        }
        if (platform == MusicPlatform.SPOTIFY) {
            if (NeteaseMusicService.isLoggedIn(platform)) {
                return "已登录 · " + NeteaseMusicService.getAccountName(platform);
            }
            // 与 B 站同理：不能把「未登录」写成不可用——搜索本来就不需要登录。
            return SpotifyMusicService.isConfigured()
                    ? "未登录 · 已配置，开浏览器授权后可浏览本人歌单"
                    : "未登录 · 元数据源，搜索无需登录，登录需自备 Client ID";
        }
        if (platform == MusicPlatform.APPLE) {
            // 与 Spotify 同理：目录搜索公开，登录只是账号能力；gamdl 是完整版的另一条腿，
            // 它的状态由 AppleGamdlBridge 的活状态行承担，这里只说账号与能力边界。
            if (NeteaseMusicService.isLoggedIn(platform)) {
                return "已登录 · 本人歌单与逐词歌词已解锁";
            }
            return com.muoniumplayer.core.ncm.apple.AppleMusicService.isConfigured()
                    ? "cookies 已导入 · 待验证或已过期，重新导入即可"
                    : "未登录 · 搜索无需登录，cookies 换来本人歌单与逐词歌词";
        }
        if (NeteaseMusicService.isLoggedIn(platform)) {
            return "已登录 · " + NeteaseMusicService.getAccountName(platform);
        }
        return "未登录 · 可搜索与播放，会员曲目需登录";
    }

    private boolean isGdActive() {
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.GD && GdStudioSourceSettings.isEnabled();
    }

    private String buildGdSummary() {
        String key = GdStudioSourceSettings.getPlatform();
        if (key.isEmpty()) return "未选用 · 选一个平台后作为独立内容源";
        String label = GdStudioMusicService.displayName(key) + "（" + key + "）";
        // 上游的额度是 5 分钟 50 次；把剩余额度摊开来写，节流就不会看着像一次随机失败。
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.GD
                ? "当前平台：" + label + " · 额度剩余 " + GdStudioMusicService.remainingRequestBudget() + "/50"
                : "已选平台：" + label + " · 点击切回";
    }

    private int statusColor() {
        return statusTone == 1 ? OK_COLOR : statusTone == 2 ? BAD_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String statusGlyph() {
        return statusTone == 1 ? PartyIcons.CHECK : statusTone == 2 ? PartyIcons.WARNING : PartyIcons.INFO;
    }

    private int rowTone(final GdStudioMusicService.Platform platform, final BooleanSupplier picked) {
        if (picked.getAsBoolean()) return MusicPlatform.GD.getBrandColor();
        return GdStudioMusicService.statusLabel(platform.key).isEmpty()
                ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT) : WARN_COLOR;
    }

    private String rowGlyph(final GdStudioMusicService.Platform platform, final BooleanSupplier picked) {
        if (picked.getAsBoolean()) return PartyIcons.CHECK;
        return GdStudioMusicService.statusLabel(platform.key).isEmpty()
                ? PartyIcons.RADIO : PartyIcons.HOURGLASS;
    }

    /**
     * 换来源等于换「当前面板能渲染什么」。
     *
     * <p>先回主页再重建导航栏，而不是把上一个来源的搜索 / 发现面板留在屏幕上。整段交接交给
     * {@link NCMScreen#switchSourcePanel}：旧源内容不参与交叉淡入、侧栏静默换代，避免两套内容
     * 与两批行动画同时叠在屏幕上。</p>
     */
    private void resetToHomeForCurrentSource() {
        // 换源会重建左栏，但搜索框本身会被 NavigateBar 复用；旧源的异步结果同时失效。
        NCMScreen.getInstance().switchSourcePanel(new HomePanel());
    }
}
