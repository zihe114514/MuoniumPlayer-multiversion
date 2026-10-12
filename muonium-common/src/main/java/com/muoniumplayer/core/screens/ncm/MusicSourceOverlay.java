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
    private static final double CARD_H = 60.0;
    /** 滚动行 = 卡高 + 行距。行距做进行高里，于是最后一行自带一段收尾留白。 */
    private static final double ROW_H = CARD_H + 7.0;
    /**
     * 分组小标题占的行高（第二组前面多留一段，让两组分开）。
     *
     * <p>这几个数和上面的卡高都是按“1080p + GUI 缩放 2”量出来的：那个档下内容区约 266
     * 逻辑像素，刚好能把三张官方卡 + 「在线聚合源」分组标题 + 聚合源卡的顶部露出来。露出一截
     * 是有意的：它是“下面还有东西”唯一可靠的提示，刚好切齐则会让人以为到底了。</p>
     */
    private static final double LABEL_ROW = 22.0;
    private static final double LABEL_ROW_2 = 26.0;
    /** 页脚：解灰 + 自动换源两行开关、一行来源说明。这些贴着弹窗底边，不参与滚动。 */
    private static final double FOOTER_H = 124.0;
    /** 卡片上文字块的左边缘 = 盘的左留白 + 盘宽 + 盘到文字的间距。 */
    private static final double TEXT_LEFT = 12.0 + DISC + 12.0;
    /** GD 平台列表的行高与卡高。 */
    private static final double PLATFORM_ROW = 56.0;
    private static final double PLATFORM_CARD = 48.0;

    /**
     * 自定义源那一组的强调色。
     *
     * <p>刻意用紫色而不是从主题色里取：这一页列的是<b>用户自己带进来的东西</b>，和上面几张
     * 官方卡（品牌色）、聚合源卡（靛蓝）必须一眼分得开。紫色在浅色与深色主题上都还站得住。</p>
     */
    private static final int LX_COLOR = 0x9C7CF4;
    /** 自定义源页：页眉（返回键 + 标题 + 副标题）加一行状态，共 88。 */
    private static final double LX_HEADER_H = 88.0;
    /** 自定义源页页脚：分隔线 + 四颗按钮 + 两行开关 + 一行安全说明。 */
    private static final double LX_FOOTER_H = 138.0;
    private static final double LX_CARD_H = 58.0;
    private static final double LX_ROW_H = LX_CARD_H + 7.0;
    private static final double LX_DISC = 36.0;
    private static final double LX_TEXT_LEFT = 12.0 + LX_DISC + 11.0;
    /** 行右端留给「使用中」胶囊与删除键的宽度，长脚本名必须在这之前收住。 */
    private static final double LX_RESERVE = 112.0;
    private static final double LX_RUNTIME_CARD = 82.0;
    private static final double LX_RUNTIME_ROW = LX_RUNTIME_CARD + 7.0;
    /** 运行状态卡上摊开几行 console。三行刚好放得下一条堆栈的头尾。 */
    private static final int LX_LOG_LINES = 3;

    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_GD = 1;
    private static final int PAGE_LX = 2;
    private static final int PAGE_YOUTUBE_PROXY = 3;
    private static final int PAGE_YOUTUBE_LOGIN = 4;
    private static final int PAGE_SPOTIFY_LOGIN = 5;

    /** Cookie 校验要联网（最坏 25 秒），期间把按钮钉成「正在验证…」，不让人连点。 */
    private boolean youtubeLoginBusy;

    /** OAuth 有一次浏览器往返（可能几分钟），期间登录态由 SpotifyOAuthFlow 的阶段接管。 */
    private boolean spotifyLoginBusy;

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

    // ════════════════════════════ 一级页：来源总览 ════════════════════════════

    private void showOverview() {
        page = PAGE_OVERVIEW;
        beginPage();

        final Panel dialog = sheet(476, () -> HEADER_H + LABEL_ROW + ROW_H * 7
                + LABEL_ROW_2 + ROW_H + LABEL_ROW_2 + ROW_H + FOOTER_H);
        header(dialog, PartyIcons.NOTE, "音乐来源",
                () -> "官方源、在线聚合源与自定义脚本源各自独立，随时切换",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final Panel body = body(dialog, () -> HEADER_H, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);

        sectionLabel(strip(feed, LABEL_ROW), "官方音乐源", () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        officialCard(strip(feed, ROW_H), 0, MusicPlatform.NETEASE);
        officialCard(strip(feed, ROW_H), 1, MusicPlatform.QQ);
        officialCard(strip(feed, ROW_H), 2, MusicPlatform.KUGOU);
        officialCard(strip(feed, ROW_H), 3, MusicPlatform.YOUTUBE);
        officialCard(strip(feed, ROW_H), 4, MusicPlatform.BILIBILI);
        officialCard(strip(feed, ROW_H), 5, MusicPlatform.QISHUI);
        officialCard(strip(feed, ROW_H), 6, MusicPlatform.SPOTIFY);

        sectionLabel(strip(feed, LABEL_ROW_2), "在线聚合源", () -> 9.0, MusicPlatform.GD::getBrandColor);
        gdCard(strip(feed, ROW_H), 7);

        sectionLabel(strip(feed, LABEL_ROW_2), "自定义音源", () -> 9.0, () -> LX_COLOR);
        lxEntryCard(strip(feed, ROW_H), 8);

        unblockRow(dialog);
        switchSourceRow(dialog);
        tip(dialog, () -> "GD音乐台来源由 music-api.gdstudio.xyz 提供，请遵循上游服务的使用要求",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 30);
    }

    /** 一张官方来源卡。点一下即切换并关闭浮层 —— 这是终点操作，所以没有 chevron。 */
    private void officialCard(final Panel row, final int slot, final MusicPlatform platform) {
        final BooleanSupplier picked = () -> NeteaseMusicService.getCurrentPlatform() == platform;
        // 底色要用卡片自己的悬停进度推，而 card(...) 在构造时就要拿到颜色供应器。
        // 供应器只在渲染时求值，所以先留一个格子、拿到卡片之后回填。
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? platform.getBrandColor()
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        // 侧栏那枚来源按钮上写着的就是当前来源，所以接力板落在当前来源这一行。
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

        selectionSheen(card, picked);
        brandDisc(card, platform, picked);

        final IntSupplier ink = () -> picked.getAsBoolean()
                ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
        cardText(card, platform::getDisplayName, () -> accountLine(platform), ink,
                () -> picked.getAsBoolean()
                        // 选中卡是品牌色底，副标题不能直接用次要文字色（那是给深/浅底色调的），
                        // 从品牌色朝白推 .78 得到的才是"同一块底上的第二层文字"。
                        ? mix(platform.getBrandColor(), 0xFFFFFF, .78)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> platform == MusicPlatform.QISHUI ? 118.0
                        : (platform == MusicPlatform.YOUTUBE ? 74.0 : (picked.getAsBoolean() ? 46.0 : 16.0)));

        RoundedRectWidget qishuiStatus = null;
        if (platform == MusicPlatform.QISHUI) {
            qishuiStatus = pill(card, this::qishuiBackendStatusGlyph,
                    QishuiCompanionDownload::backendStatusLabel,
                    this::qishuiBackendStatusColor,
                    () -> card.getWidth() - 12, () -> card.getHeight() * .5);
        }
        final RoundedRectWidget statusPill = qishuiStatus;
        checkBadge(card, picked, platform::getBrandColor,
                () -> statusPill == null ? card.getWidth() - 23 : statusPill.getRelativeX() - 15);
        if (platform == MusicPlatform.YOUTUBE) {
            youtubeProxyButton(card);
            youtubeLoginButton(card);
        }
        if (platform == MusicPlatform.SPOTIFY) {
            spotifyLoginButton(card);
        }
    }

    /**
     * Spotify 卡上的登录入口。
     *
     * <p>Spotify 是唯一要「用户先去别处办一件事」的官方源：自建开发者应用拿 Client ID。
     * 登录页里把步骤写死在界面上，比丢一份链接让用户自己找文档靠谱。</p>
     */
    private void spotifyLoginButton(final Card card) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(10);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            showSpotifyLogin();
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            button.setBounds(32, 32);
            button.setPosition(card.getWidth() - 64, card.getHeight() * .5 - 16);
            button.setColor(button.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER),
                    MusicPlatform.SPOTIFY.getBrandColor(), .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            button.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        icon(button, PartyIcons.KEY, ICON_M, () -> 16.0, () -> 16.0,
                () -> NeteaseMusicService.isLoggedIn(MusicPlatform.SPOTIFY)
                        ? MusicPlatform.SPOTIFY.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }

    /** YouTube 专属设置入口。代理只作用于 YouTube 链路，所以留在 YouTube 卡上而不是全局设置。 */
    private void youtubeProxyButton(final Card card) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(10);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            showYouTubeProxySettings();
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            button.setBounds(32, 32);
            button.setPosition(card.getWidth() - 64, card.getHeight() * .5 - 16);
            button.setColor(button.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), MusicPlatform.YOUTUBE.getBrandColor(), .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            button.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        icon(button, PartyIcons.LINK, ICON_M, () -> 16.0, () -> 16.0,
                () -> YouTubeProxySettings.isEnabled() ? MusicPlatform.YOUTUBE.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
    }

    /** 二级页：YouTube 专用 HTTP 代理。 */
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

    /**
     * YouTube 的登录入口。
     *
     * <p>YouTube 是唯一「不登录也能搜能播」的源，所以这颗按钮不是主流程而是一颗能力增强：
     * 登录之后才亮起来的是歌词与私人歌单。挂在这张卡上而不是账号页，是因为账号页那颗主按钮
     * 的整条状态机是围着扫码转的，硬塞一个不能扫码的平台会把那边搞脏。</p>
     */
    private void youtubeLoginButton(final Card card) {
        final RoundedButtonWidget button = new RoundedButtonWidget("", FontManager.pf12bold);
        card.addChild(button);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setRadius(10);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouse) -> {
            if (mouse != 0) return false;
            showYouTubeLogin();
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            button.setBounds(32, 32);
            // 代理那颗按钮占着 card.getWidth()-64，这一颗排在它左边，两张卡图标不重叠。
            button.setPosition(card.getWidth() - 104, card.getHeight() * .5 - 16);
            button.setColor(button.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER),
                            MusicPlatform.YOUTUBE.getBrandColor(), .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            button.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        icon(button, PartyIcons.KEY, ICON_M, () -> 16.0, () -> 16.0,
                () -> YouTubeSessionStore.isLoggedIn() ? MusicPlatform.YOUTUBE.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
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
            paste.setBounds(96, 30);
            paste.setPosition(PAD + CARD_INSET, dialog.getHeight() - 46);
            paste.setColor(paste.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget verify = action(dialog, () -> PartyIcons.CHECK,
                () -> youtubeLoginBusy ? "正在验证…" : "验证并登录", FontManager.pf12bold,
                () -> 0xFFFFFF, () -> submitYouTubeCookie(input));
        verify.setBeforeRenderCallback(() -> {
            verify.setBounds(118, 30);
            verify.setPosition(paste.getRelativeX() + 104, dialog.getHeight() - 46);
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
            signOut.setBounds(96, 30);
            signOut.setPosition(verify.getRelativeX() + 126, dialog.getHeight() - 46);
            signOut.setColor(signOut.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
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
            save.setBounds(112, 30);
            save.setPosition(PAD + CARD_INSET, dialog.getHeight() - 46);
            save.setColor(save.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, .16)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        });

        final RoundedButtonWidget authorize = action(dialog, () -> PartyIcons.LINK,
                () -> spotifyLoginBusy ? "等待授权…" : "打开浏览器授权", FontManager.pf12bold,
                () -> 0xFFFFFF, () -> startSpotifyAuthorize());
        authorize.setBeforeRenderCallback(() -> {
            authorize.setBounds(124, 30);
            authorize.setPosition(save.getRelativeX() + 120, dialog.getHeight() - 46);
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
            signOut.setBounds(96, 30);
            signOut.setPosition(authorize.getRelativeX() + 132, dialog.getHeight() - 46);
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

    /** GD 聚合源卡。它通往下一页，所以右端常驻一枚 chevron。 */
    private void gdCard(final Panel row, final int slot) {
        final BooleanSupplier picked = this::isGdActive;
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? MusicPlatform.GD.getBrandColor()
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });

        selectionSheen(card, picked);

        final IntSupplier ink = () -> picked.getAsBoolean()
                ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setRadius(14);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(DISC, DISC);
            disc.setPosition(12, card.getHeight() * .5 - DISC * .5);
            disc.setColor(picked.getAsBoolean()
                    ? mix(MusicPlatform.GD.getBrandColor(), 0xFFFFFF, .20)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    MusicPlatform.GD.getBrandColor(), .18));
        });
        // 聚合源没有品牌字标，用"探索"这一枚：它比品牌盘小一圈，一眼就能和上面三张官方卡分开。
        icon(disc, PartyIcons.EXPLORE, ICON_XL, () -> DISC * .5, () -> DISC * .5,
                () -> picked.getAsBoolean() ? 0xFFFFFF : MusicPlatform.GD.getBrandColor());

        cardText(card, () -> "GD音乐台 · 在线聚合", this::buildGdSummary, ink,
                () -> picked.getAsBoolean()
                        ? mix(MusicPlatform.GD.getBrandColor(), 0xFFFFFF, .78)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> picked.getAsBoolean() ? 74.0 : 40.0);

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_M, () -> card.getWidth() - 20,
                () -> card.getHeight() * .5, () -> picked.getAsBoolean() ? 0xFFFFFF
                        : mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), self[0].hover));
        checkBadge(card, picked, MusicPlatform.GD::getBrandColor, () -> card.getWidth() - 46);
    }

    /**
     * 「灰色歌曲解灰」一行。
     *
     * <p>留在音乐来源这一层而不是挪进 HUD 配置：它决定的就是「官方源没有流的时候要不要换一家」，
     * 和这个面板讨论的是同一件事。钉在页脚是因为它是设置而不是列表项，滚动内容不该把它带走。</p>
     */
    private void unblockRow(final Panel dialog) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 40);
            surface.setPosition(PAD + CARD_INSET, dialog.getHeight() - FOOTER_H + 4);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        icon(surface, PartyIcons.SWEEP, ICON_M, () -> 20.0, () -> 20.0,
                () -> NeteaseUnblockSettings.isEnabled()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

        final LabelWidget title = label(surface, "灰色歌曲解灰", FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(38, inkTop(title, 13.0));
        });

        final LabelWidget hint = label(surface, "官方源没有可播放流时，改到第三方源按曲名匹配", FontManager.pf12);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 96 = 开关宽 34 + 开关到右边缘 12 + 文字到开关 12 + 一点余量。
            hint.setMaxWidth(Math.max(1, surface.getWidth() - 38 - 96));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(38, inkTop(hint, 28.0));
        });

        toggle(surface, NeteaseUnblockSettings::isEnabled,
                () -> NeteaseUnblockSettings.setEnabled(!NeteaseUnblockSettings.isEnabled()),
                () -> surface.getWidth() - 12, () -> 20.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
    }

    /**
     * 「跨平台自动换源」一行，紧贴在解灰行下面。
     *
     * <p>它管的是解灰也救不回来之后的最后一级兜底：按曲名 / 艺人去别的平台匹配一条真正的
     * 曲目来替换播放。两行上下排开是因为顺序就是执行顺序 —— 先试着保住这首歌的身份，
     * 保不住才换身份。</p>
     */
    private void switchSourceRow(final Panel dialog) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 40);
            surface.setPosition(PAD + CARD_INSET, dialog.getHeight() - FOOTER_H + 48);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        icon(surface, PartyIcons.SYNC, ICON_M, () -> 20.0, () -> 20.0,
                () -> SwitchSourceSettings.isEnabled()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

        final LabelWidget title = label(surface, "跨平台自动换源", FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(38, inkTop(title, 13.0));
        });

        final LabelWidget hint = label(surface, "取不到流或只能拿到试听片段时，到其他平台按曲名匹配完整曲目替换播放；要会员的平台没会员不试（GD/B 站不限；LX 自定义源覆盖的平台免会员也试；汽水需汽水会员）", FontManager.pf12);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 96 = 开关宽 34 + 开关到右边缘 12 + 文字到开关 12 + 一点余量。
            hint.setMaxWidth(Math.max(1, surface.getWidth() - 38 - 96));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(38, inkTop(hint, 28.0));
        });

        toggle(surface, SwitchSourceSettings::isEnabled,
                () -> SwitchSourceSettings.setEnabled(!SwitchSourceSettings.isEnabled()),
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

        lxFooter(dialog);

        // 网址导入弹窗挂在整层最上面：它不属于任何一页的滚动内容，每次重建页面都按开合状态
        // 重新搭一遍 —— hidden 的组件不渲染也不吃事件，关着的时候等于不存在。
        buildLxUrlDialog();
    }

    private double lxContentHeight(final int count) {
        return 6.0 + LABEL_ROW + (count == 0 ? 44.0 : LX_ROW_H * count)
                + LABEL_ROW_2 + LX_RUNTIME_ROW;
    }

    /** 总览页上通往自定义源那一页的入口卡。有 chevron，因为它不是终点操作。 */
    private void lxEntryCard(final Panel row, final int slot) {
        final BooleanSupplier picked = LxSourceService::isEnabled;
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? LX_COLOR
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showLxSources();
            return true;
        });

        selectionSheen(card, picked);

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setRadius(14);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(DISC, DISC);
            disc.setPosition(12, card.getHeight() * .5 - DISC * .5);
            disc.setColor(picked.getAsBoolean()
                    ? mix(LX_COLOR, 0xFFFFFF, .20)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), LX_COLOR, .18));
        });
        icon(disc, PartyIcons.LINK, ICON_XL, () -> DISC * .5, () -> DISC * .5,
                () -> picked.getAsBoolean() ? 0xFFFFFF : LX_COLOR);

        cardText(card, () -> "自定义音源 · LX 脚本", this::buildLxSummary,
                () -> picked.getAsBoolean() ? 0xFFFFFF
                        : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> picked.getAsBoolean() ? mix(LX_COLOR, 0xFFFFFF, .78)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> picked.getAsBoolean() ? 74.0 : 40.0);

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_M, () -> card.getWidth() - 20,
                () -> card.getHeight() * .5, () -> picked.getAsBoolean() ? 0xFFFFFF
                        : mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), self[0].hover));
        checkBadge(card, picked, () -> LX_COLOR, () -> card.getWidth() - 46);
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

    private void lxFooter(final Panel dialog) {
        divider(dialog, () -> dialog.getHeight() - LX_FOOTER_H + 2);

        lxFooterButton(dialog, 0, () -> PartyIcons.FOLDER_OPEN, () -> "从文件导入",
                LxSourceService::browseForFile);
        // 一个按钮吃下「链接」和「正文」两种剪贴板内容：用户复制过来的要么是 raw 链接、
        // 要么是整段脚本，让程序看一眼比让他先选一次更省事。
        lxFooterButton(dialog, 1, () -> PartyIcons.LINK, () -> "从剪贴板导入",
                () -> LxSourceService.importFromText(KeyboardUtils.getClipboardString()));
        // 手输的网址走二级弹窗：页脚已经排满，再塞一行常驻输入栏会把开关和安全说明挤上去；
        // 而网址导入终究是「偶尔用一次」的路径，弹窗恰好表达这种「要用的时候才出现」。
        lxFooterButton(dialog, 2, () -> PartyIcons.PUBLIC_ROOM, () -> "从网址导入",
                this::openLxUrlDialog);
        lxFooterButton(dialog, 3, () -> PartyIcons.SYNC, () -> "重新加载",
                LxSourceService::reloadActive);

        // 两行开关都是「有启用项才有意义」，没有启用项时整行调暗。
        final IntSupplier footerInk = () -> LxSourceService.isEnabled()
                ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)
                : mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), .45);

        // 这一行是宿主自己的扩展，不是上游协议的一部分：上游只给 local 源放开 lyric / pic，
        // 而 local 指的是洛雪的本机文件，这里的曲目全来自远端平台。默认关，与上游一字不差。
        lxToggleRow(dialog, () -> PartyIcons.ALBUM, "允许脚本提供搜索 / 歌词 / 封面",
                LxSourceService::isExtraActionsEnabled,
                () -> LxSourceService.setExtraActions(!LxSourceService.isExtraActionsEnabled()),
                -78.0, footerInk);
        // 更新提示是「这个脚本」的开关而不是全局设置。
        lxToggleRow(dialog, () -> PartyIcons.MAIL, "允许脚本弹出作者的更新提示",
                LxSourceService::isUpdateAlertAllowed, LxSourceService::flipUpdateAlert,
                -50.0, footerInk);

        tip(dialog, () -> PartyIcons.WARNING,
                () -> "脚本在本机执行、能发任意网络请求（已断掉它访问 Java 类的路）：只导入你信得过的来源",
                () -> WARN_COLOR, () -> dialog.getHeight() - 26.0);
    }

    /**
     * 页脚上的一行开关：图标 + 一行字 + 右端的开关。
     *
     * @param offsetFromBottom 这一行的基线相对弹窗底边的偏移，负数
     */
    private void lxToggleRow(final Panel dialog, final Supplier<String> glyph, final String text,
                             final BooleanSupplier state, final Runnable flip,
                             final double offsetFromBottom, final IntSupplier ink) {
        icon(dialog, glyph, ICON_S, () -> PAD + CARD_INSET + ICON_S * .5,
                () -> dialog.getHeight() + offsetFromBottom, ink);

        final LabelWidget caption = label(dialog, text, FontManager.pf12);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(ink.getAsInt());
            caption.setMaxWidth(Math.max(1, dialog.getWidth() - PAD * 2 - ICON_S - 60));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(PAD + CARD_INSET + ICON_S + 7.0,
                    inkTop(caption, dialog.getHeight() + offsetFromBottom));
        });

        toggle(dialog, state, flip,
                () -> dialog.getWidth() - PAD - CARD_INSET, () -> dialog.getHeight() + offsetFromBottom,
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
            // 按钮下面还有两行开关与一行安全说明，所以从底边往上抬到 126。
            button.setPosition(PAD + CARD_INSET + (width + 8.0) * index, dialog.getHeight() - 126.0);
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
        final Wash sheen = wash(card, true, 12.0);
        sheen.tint(0xFFFFFF, 0xFFFFFF).alphas(.14, 0.0);
        sheen.setBeforeRenderCallback(() -> {
            sheen.setBounds(Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));
            sheen.setPosition(0, 0);
            sheen.setAlpha(picked.getAsBoolean() ? 1f : 0f);
        });
        stroke(card, 12.0, () -> 0xFFFFFF, () -> picked.getAsBoolean() ? .26 : 0.0);
    }

    /** 品牌字标底盘。未选中时是「卡片底色朝品牌色推一小步」，于是浅色主题上也看得见。 */
    private void brandDisc(final Card card, final MusicPlatform platform, final BooleanSupplier picked) {
        final boolean kugou = platform == MusicPlatform.KUGOU;
        final boolean qq = platform == MusicPlatform.QQ;
        final boolean youtube = platform == MusicPlatform.YOUTUBE;
        final boolean bilibili = platform == MusicPlatform.BILIBILI;
        final boolean qishui = platform == MusicPlatform.QISHUI;
        final boolean spotify = platform == MusicPlatform.SPOTIFY;
        // 品牌字形必须配对到自己那份字体：music-brand-icons 里没有 U+E888（QQ 音乐），
        // 拿错字体会落到兜底字体上、画出一片空白。酷狗两份字体里都没有，用正文字体画一枚汉字字标。
        // 酷狗、哔哩哔哩、汽水与 Spotify 现在有真标识了（brandMark 里的矢量分支），
        // 下面这对字形/字体只在矢量画不出来时兜底。
        final String mark = kugou ? MusicBrandIcons.KUGOU_MONOGRAM
                : qq ? MusicBrandIcons.QQ_MUSIC
                : youtube ? MusicBrandIcons.YOUTUBE
                : bilibili ? MusicBrandIcons.BILIBILI_MONOGRAM
                : qishui ? MusicBrandIcons.QISHUI_MONOGRAM
                : spotify ? MusicBrandIcons.SPOTIFY_MONOGRAM
                : MusicBrandIcons.NETEASE_CLOUD_MUSIC;
        final CFontRenderer font = kugou ? FontManager.pf34bold
                : youtube ? FontManager.youtubeIcon34
                : bilibili ? FontManager.pf34bold
                : qishui ? FontManager.pf34bold
                : spotify ? FontManager.pf34bold
                : qq ? FontManager.qqMusicIcon68 : FontManager.musicBrand52;

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setGlassRim(GlassLook.Surface.PLAYER);
        disc.setClickable(false);
        disc.setRadius(14);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(DISC, DISC);
            disc.setPosition(12, card.getHeight() * .5 - DISC * .5);
            disc.setColor(picked.getAsBoolean()
                    ? mix(platform.getBrandColor(), 0xFFFFFF, .20)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    platform.getBrandColor(), .18));
        });
        brandMark(disc, platform, DISC * BrandVectorIcons.MARK_RATIO, mark, font,
                () -> DISC * .5, () -> DISC * .5,
                () -> picked.getAsBoolean() ? 0xFFFFFF : platform.getBrandColor());
    }

    /**
     * 卡片上的「标题 + 副标题」两行。
     *
     * <p>两行绕着卡片中线各让 9 像素，而不是写死 y+13 / y+34：卡高一改，写死的那两个数就得
     * 重新调，而按中线算的这一对永远居中。{@code reserve} 是右端零件（勾标、chevron）占掉的宽度，
     * 副标题必须在它之前收住，否则长文案会压到底下去。</p>
     */
    private void cardText(final Card card, final java.util.function.Supplier<String> titleText,
                          final java.util.function.Supplier<String> noteText,
                          final IntSupplier titleInk, final IntSupplier noteInk,
                          final java.util.function.DoubleSupplier reserve) {
        final LabelWidget title = label(card, titleText, FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(titleInk.getAsInt());
            title.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve.getAsDouble()));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(TEXT_LEFT, inkTop(title, card.getHeight() * .5 - 9));
        });

        final LabelWidget note = label(card, noteText, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(noteInk.getAsInt());
            note.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve.getAsDouble()));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(TEXT_LEFT, inkTop(note, card.getHeight() * .5 + 9));
        });
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
        final Panel layer = veil(card, () -> picked.getAsBoolean() ? 1.0 : 0.0);
        final RoundedRectWidget badge = new RoundedRectWidget();
        layer.addChild(badge);
        badge.setClickable(false);
        badge.setRadius(11);
        badge.setBeforeRenderCallback(() -> {
            badge.setBounds(22, 22);
            badge.setPosition(centerX.getAsDouble() - 11, layer.getHeight() * .5 - 11);
            badge.setColor(0xFFFFFF);
        });
        icon(badge, PartyIcons.CHECK, ICON_M, () -> 11.0, () -> 11.0, tint);
    }

    // ════════════════════════════ 文案与状态 ════════════════════════════

    /** 官方卡副标题：这个来源现在能不能用。比一句写死的能力描述有用得多。 */
    private int qishuiBackendStatusColor() {
        return switch (QishuiCompanionDownload.backendStatus()) {
            case RUNNING -> OK_COLOR;
            case FAILED, STOPPED -> BAD_COLOR;
            case CONNECTING, DOWNLOADING, VERIFYING, INSTALLING, STARTING -> WARN_COLOR;
        };
    }

    private String qishuiBackendStatusGlyph() {
        return switch (QishuiCompanionDownload.backendStatus()) {
            case RUNNING -> PartyIcons.CHECK;
            case FAILED, STOPPED -> PartyIcons.WARNING;
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
     * <p>先回主页再重建导航栏，而不是把上一个来源的搜索 / 发现面板留在屏幕上。</p>
     */
    private void resetToHomeForCurrentSource() {
        // 换源会重建左栏，但搜索框本身会被 NavigateBar 复用；旧源的异步结果同时失效。
        NCMScreen screen = NCMScreen.getInstance();
        screen.setCurrentPanel(new HomePanel());
        screen.markDirty();
    }
}
