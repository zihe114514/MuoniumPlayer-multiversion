package com.muoniumplayer.core.screens.ncm;

import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseAccountProfiles;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.QRCodeGenerator;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.AccountAvatar;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 账号与登录浮层：网易云 / QQ / 酷狗的扫码登录、Cookie 登录与本地已保存账号。
 *
 * <p>版式与动效在 {@link NcmSheet}；这里负责「哪一页显示什么」和全部网络流程。网络请求与
 * 二维码解码都在后台线程，贴图提交与换页一律回主线程。</p>
 *
 * <p><b>这一轮为什么重写。</b>原来四个页面各自把弹窗、标题、卡片、状态行手写一遍，纵向位置是
 * 写死的偏移（详情页的二维码盘在 48、按钮排在 {@code height-48 / -80 / -112}），而弹窗高度又被
 * 视口夹住 —— 窗口一矮，二维码就压在按钮上。另外登录成功后页面停在原处只改一行字，用户得自己
 * 点返回；现在成功反馈停留一下就自动回上一级。</p>
 */
public class AccountManagerOverlay extends NcmSheet {

    private static final Location NETEASE_QR = Location.of("muonium/textures/account/netease_qr.png");
    private static final Location QQ_QR = Location.of("muonium/textures/account/qq_qr.png");
    private static final Location KUGOU_QR = Location.of("muonium/textures/account/kugou_qr.png");
    private static final Location QISHUI_QR = Location.of("muonium/textures/account/qishui_qr.png");

    /** 页眉占掉的高度：图标盘 / 返回键 34 见方 + 上下留白，标题与副标题都在这一段里。 */
    private static final double HEADER_H = 64.0;
    private static final double ACCOUNT_CARD = 70.0;
    private static final double ACCOUNT_ROW = ACCOUNT_CARD + 8.0;
    /** 二维码白盘边长。扫码靠明暗对比，盘必须是浅色，和主题无关。 */
    private static final double QR_PLATE = 148.0;
    private static final double QR_BLOCK = 8.0 + QR_PLATE + 30.0;
    private static final double IDENTITY_BLOCK = 96.0;
    private static final double STATUS_BLOCK = 30.0;
    private static final double SEGMENT_BLOCK = 52.0;
    private static final double SAVED_ROW = 60.0;
    /** 滚动区开头的让位（见 {@code plain} 的注释：悬停生长会越过上边界）。 */
    private static final double LEAD = 6.0;
    /** 卡片上文字块的左边缘 = 头像左留白 + 头像宽 + 间距。 */
    private static final double TEXT_LEFT = 12.0 + 46.0 + 12.0;
    /**
     * 登录成功后停留多久再换页。
     *
     * <p>不留这一下的话用户只看到界面闪了一下，分不清是登上了还是被关掉了。</p>
     */
    private static final long SUCCESS_HOLD_MILLIS = 850L;

    private final AtomicLong pageGeneration = new AtomicLong();
    private volatile MusicPlatform detailPlatform;
    private volatile String statusText = "";
    /**
     * 状态行语气：0 说明、1 成功、2 失败、3 进行中。
     *
     * <p>存语气而不是直接存颜色：说明态该跟着主题走（原来写死 {@code 0xAEB5C4}，浅色主题下几乎
     * 看不见），而状态图标也得按语气挑 —— 拿颜色反推会在主题切换之后错配。</p>
     */
    private volatile int statusTone;
    private volatile boolean requestRunning;
    /** 二维码贴图是否已就绪。没就绪时盘里放占位图标，而不是露出一片空白。 */
    private volatile boolean qrReady;
    /**
     * QQ 音乐有两条互不相干的扫码链路（QQ 的 ptlogin、微信开放平台的 OAuth）。这个选择必须显式保存，
     * 才能保证选了微信之后走的是微信那条会话、而不是误用 QQ 的二维码。
     */
    private volatile QQLoginChannel qqLoginChannel = QQLoginChannel.QQ;

    private enum QQLoginChannel {
        QQ,
        WECHAT
    }

    @Override
    public void onInit() {
        showOverview();
    }

    @Override
    public void dispose() {
        super.dispose();
        pageGeneration.incrementAndGet();
    }

    public void handleEscape() {
        if (detailPlatform != null) {
            showOverview();
        } else {
            dispose();
        }
    }
    // ════════════════════════════ 一级页：账号总览 ════════════════════════════

    private void showOverview() {
        pageGeneration.incrementAndGet();
        detailPlatform = null;
        requestRunning = false;
        beginPage();

        final Panel dialog = sheet(482, () -> HEADER_H + LEAD + ACCOUNT_ROW * 4 + 44);
        header(dialog, PartyIcons.PERSON, "账号与登录",
                () -> "凭据只保存在本地 · 四个来源各自独立",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final Panel body = body(dialog, () -> HEADER_H, () -> 44.0);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停时绕着自己中心长，第一行贴着滚动区上边的话会被硬裁切掉圆角。
        strip(feed, LEAD);
        accountCard(strip(feed, ACCOUNT_ROW), 0, MusicPlatform.NETEASE);
        accountCard(strip(feed, ACCOUNT_ROW), 1, MusicPlatform.QQ);
        accountCard(strip(feed, ACCOUNT_ROW), 2, MusicPlatform.KUGOU);
        accountCard(strip(feed, ACCOUNT_ROW), 3, MusicPlatform.QISHUI);

        tip(dialog, () -> PartyIcons.LOCK,
                () -> "登录信息仅保存在本地配置中，界面不会显示 Cookie 内容",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 30);
    }

    private void accountCard(final Panel row, final int slot, final MusicPlatform platform) {
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> ACCOUNT_CARD,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showDetail(platform);
            return true;
        });

        avatarSlot(card, platform, 46.0, () -> 12.0, () -> card.getHeight() * .5);

        // 状态胶囊先建：昵称行的可用宽度要按它当帧的实际宽度让位，长昵称才不会压到它底下。
        final RoundedRectWidget state = pill(card,
                () -> NeteaseMusicService.isLoggedIn(platform) ? PartyIcons.CLOUD_DONE : PartyIcons.CLOUD_OFF,
                () -> NeteaseMusicService.isLoggedIn(platform) ? "已登录" : "未登录",
                () -> NeteaseMusicService.isLoggedIn(platform)
                        ? OK_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> card.getWidth() - 34, () -> card.getHeight() * .5 - 11);

        // 会员标记就该长在账号旁边。原来它是主页左下角一行灰字，那个位置离「我的账号」最远。
        final Panel vipLayer = veil(card, () -> isAccountVip(platform) ? 1.0 : 0.0);
        pill(vipLayer, () -> PartyIcons.VIP, () -> "会员", () -> VIP_COLOR,
                () -> vipLayer.getWidth() - 34, () -> vipLayer.getHeight() * .5 + 11);

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_M, () -> card.getWidth() - 18,
                () -> card.getHeight() * .5,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        platform.getBrandColor(), self[0].hover));

        final LabelWidget name = label(card, platform::getDisplayName, FontManager.pf14bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - 44 - state.getWidth()));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(TEXT_LEFT, inkTop(name, card.getHeight() * .5 - 10));
        });

        final LabelWidget account = label(card, () -> NeteaseMusicService.isLoggedIn(platform)
                ? NeteaseMusicService.getAccountName(platform)
                : "未登录 · 点击选择登录方式", FontManager.pf12);
        account.setBeforeRenderCallback(() -> {
            account.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            account.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - 44 - state.getWidth()));
            account.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            account.setPosition(TEXT_LEFT, inkTop(account, card.getHeight() * .5 + 11));
        });
    }

    /**
     * 头像位：三层叠着画，一层比一层更确切。
     *
     * <p>底盘（品牌色调）→ 未登录时的品牌字标 / 已登录时的昵称首字 → 头像照片。照片就绪就盖住
     * 下面两层，没就绪也不会露出一个空盘。头像下载走 {@link AccountAvatar} 的按地址节流，
     * 所以每帧都问一次是安全的。</p>
     */
    private void avatarSlot(final AbstractWidget<?> host, final MusicPlatform platform, final double size,
                            final DoubleSupplier left, final DoubleSupplier centerY) {
        final RoundedRectWidget disc = new RoundedRectWidget();
        host.addChild(disc);
        disc.setClickable(false);
        disc.setRadius(size * .3);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(size, size);
            disc.setPosition(left.getAsDouble(), centerY.getAsDouble() - size * .5);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    platform.getBrandColor(), NeteaseMusicService.isLoggedIn(platform) ? .32 : .16));
        });

        final Panel brandLayer = veil(disc, () -> NeteaseMusicService.isLoggedIn(platform) ? 0.0 : 1.0);
        brandMark(brandLayer, platform, size * BrandVectorIcons.MARK_RATIO,
                brandGlyph(platform), brandFont(platform),
                () -> size * .5, () -> size * .5, platform::getBrandColor);

        final Panel letterLayer = veil(disc, () -> NeteaseMusicService.isLoggedIn(platform) ? 1.0 : 0.0);
        final CFontRenderer letterFont = size >= 48 ? FontManager.pf20bold : FontManager.pf16bold;
        final Supplier<String> letterText = () -> initial(NeteaseMusicService.getAccountName(platform));
        final LabelWidget letter = label(letterLayer, letterText, letterFont);
        letter.setBeforeRenderCallback(() -> {
            letter.setColor(0xFFFFFF);
            // 按字体宽度算而不是读 getWidth()：LabelWidget 的尺寸是在 onRender 末尾回填的，
            // 这里读到的是上一帧的值，首帧还是 0，会让首字明显闪一下。
            letter.setPosition(size * .5 - letterFont.getStringWidthD(letterText.get()) * .5,
                    inkTop(letter, size * .5));
        });

        final RoundedImageWidget photo = new RoundedImageWidget(
                () -> AccountAvatar.textureOrNull(NeteaseMusicService.getAccountAvatarUrl(platform),
                        NeteaseMusicService.getAccountAvatarLocation(platform)) == null
                        ? null : NeteaseMusicService.getAccountAvatarLocation(platform),
                0, 0, size, size);
        disc.addChild(photo);
        photo.setClickable(false);
        photo.setRadius(size * .3);
        photo.setLinearFilter(true);
        photo.setBeforeRenderCallback(() -> {
            photo.setBounds(size, size);
            photo.setPosition(0, 0);
        });
    }
    // ════════════════════════════ 二级页：某个平台的账号 ════════════════════════════

    /** 听歌历史同步的授权文案。登录时的询问与详情页开关共用一份，避免同一件事出现两种说法。 */
    private static final String SYNC_CONSENT_TITLE = "开启后会上报数据到网易云";
    private static final String SYNC_CONSENT_BODY =
            "开启后会收集你的电脑唯一标识符，包括但不限于 CPU、系统 OS、网卡、硬盘序列号、"
                    + "IP 地址等信息。信息全部用于上报网易云客户端。";
    private static final String SYNC_CONSENT_CONFIRM = "我已了解，开启";

    private void showDetail(MusicPlatform platform) {
        showDetail(platform, false);
    }

    /** {@code addAccountByQr}：即便已经有网易云账号在线，也走扫码添加另一个账号的流程。 */
    private void showDetail(final MusicPlatform platform, final boolean addAccountByQr) {
        final long token = pageGeneration.incrementAndGet();
        detailPlatform = platform;
        requestRunning = false;
        qrReady = false;

        final boolean loggedIn = NeteaseMusicService.isLoggedIn(platform);
        final boolean qrVisible = !loggedIn || addAccountByQr;
        final boolean qqChannelPicker = platform == MusicPlatform.QQ && !loggedIn;
        final boolean wechat = platform == MusicPlatform.QQ && qqLoginChannel == QQLoginChannel.WECHAT;

        statusText = addAccountByQr ? "正在准备扫码添加账号…"
                : loggedIn ? "当前账号已登录"
                : platform == MusicPlatform.QQ
                ? (wechat ? "已选择微信登录，点下方按钮获取二维码" : "先选一种登录方式")
                : "正在获取登录二维码…";
        statusTone = !addAccountByQr && loggedIn ? 1 : 0;

        beginPage();

        // 页脚按钮排的高度随状态变。算不准的话，内容区要么被按钮盖住、要么白留一大块。
        final int extras = addAccountByQr || platform != MusicPlatform.NETEASE ? 0 : (loggedIn ? 2 : 1);
        final double footer = 48.0 + extras * 36.0;
        final double content = LEAD + (qrVisible ? QR_BLOCK : IDENTITY_BLOCK) + STATUS_BLOCK
                + (qqChannelPicker ? SEGMENT_BLOCK : 0.0);

        final Panel dialog = sheet(462, () -> HEADER_H + content + footer);
        subHeader(dialog, addAccountByQr ? this::showSavedNeteaseAccounts : this::showOverview,
                () -> addAccountByQr ? "扫码添加网易云账号" : platform.getDisplayName() + "账号",
                () -> addAccountByQr ? "扫码成功后自动回到已保存账号"
                        : loggedIn ? "本地已保存这个账号的登录凭据"
                        : "请使用" + scanClientName(platform) + "扫码并确认",
                platform::getBrandColor);

        final Panel body = body(dialog, () -> HEADER_H, () -> footer);
        final ScrollPanel feed = feed(body);
        strip(feed, LEAD);
        if (qrVisible) {
            qrBlock(strip(feed, QR_BLOCK), 0, platform);
        } else {
            identityBlock(strip(feed, IDENTITY_BLOCK), 0, platform);
        }
        tip(strip(feed, STATUS_BLOCK), this::statusGlyph, () -> statusText, this::statusColor, () -> 6.0);
        if (qqChannelPicker) {
            channelSegment(strip(feed, SEGMENT_BLOCK));
        }

        primaryAction(dialog, platform, loggedIn, addAccountByQr);
        if (!addAccountByQr && platform == MusicPlatform.NETEASE) {
            if (loggedIn) {
                secondaryAction(dialog, 1, () -> PartyIcons.GROUP,
                        () -> "切换已保存账号（" + NeteaseAccountProfiles.load().size() + "）",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                        this::showSavedNeteaseAccounts);
                historySyncAction(dialog, 2);
            } else {
                secondaryAction(dialog, 1, () -> PartyIcons.KEY, () -> "Cookie 登录",
                        () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                        this::showCookieLogin);
            }
        }

        // 网易云、酷狗与汽水都只有一条扫码链路，进页面就直接取码。QQ 必须先定下走 QQ 还是微信，
        // 否则会拿 QQ 的码去问微信的状态。
        if ((platform == MusicPlatform.NETEASE || platform == MusicPlatform.KUGOU
                || platform == MusicPlatform.QISHUI) && qrVisible) {
            startQrLogin(platform, token, addAccountByQr);
        }
        if (platform == MusicPlatform.NETEASE && loggedIn && !addAccountByQr) {
            maybePromptListeningHistorySync();
        }
    }

    private void qrBlock(final Panel row, final int slot, final MusicPlatform platform) {
        final RoundedRectWidget board = new RoundedRectWidget();
        row.addChild(board);
        board.setClickable(false);
        board.setRadius(14);
        board.setBeforeRenderCallback(() -> {
            double gate = clamp01((pageFade - slot * .07) / .55);
            board.setBounds(QR_PLATE, QR_PLATE);
            board.setPosition(row.getWidth() * .5 - QR_PLATE * .5, 8 + (1.0 - gate) * 7.0);
            // 二维码必须画在浅底上：扫码靠的是明暗对比，深色主题下铺主题底色会扫不出来。
            board.setColor(0xF6F7F9);
            board.setAlpha((float) gate);
        });

        final RoundedImageWidget image = new RoundedImageWidget(
                () -> qrReady ? getQrLocation(platform) : null, 0, 0, QR_PLATE - 16, QR_PLATE - 16);
        board.addChild(image);
        image.setClickable(false);
        image.setRadius(4);
        image.setLinearFilter(true);
        image.setBeforeRenderCallback(() -> {
            image.setBounds(QR_PLATE - 16, QR_PLATE - 16);
            image.setPosition(8, 8);
        });

        // 码还没到手时盘里不能是一片空白：一枚随状态变的图标加一行说明，用户才分得清
        // 「正在取码」和「还没开始取」。
        final Panel waiting = veil(board, () -> qrReady ? 0.0 : 1.0);
        icon(waiting, () -> requestRunning ? PartyIcons.HOURGLASS : PartyIcons.SYNC, ICON_XL,
                () -> QR_PLATE * .5, () -> QR_PLATE * .5 - 12, () -> 0x9AA3B2);
        final Supplier<String> waitText = () -> requestRunning ? "正在获取二维码…" : "点下方按钮获取二维码";
        final LabelWidget waitHint = label(waiting, waitText, FontManager.pf12);
        waitHint.setBeforeRenderCallback(() -> {
            waitHint.setColor(0x8A93A4);
            waitHint.setPosition(QR_PLATE * .5 - FontManager.pf12.getStringWidthD(waitText.get()) * .5,
                    inkTop(waitHint, QR_PLATE * .5 + 20));
        });

        final Supplier<String> caption = () -> "请使用" + scanClientName(platform) + "扫码并确认";
        final LabelWidget scanHint = label(row, caption, FontManager.pf12bold);
        scanHint.setBeforeRenderCallback(() -> {
            scanHint.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            scanHint.setMaxWidth(Math.max(1, row.getWidth() - PAD * 2));
            scanHint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            scanHint.setPosition(
                    row.getWidth() * .5 - FontManager.pf12bold.getStringWidthD(caption.get()) * .5,
                    inkTop(scanHint, 8 + QR_PLATE + 15));
        });
    }

    private void identityBlock(final Panel row, final int slot, final MusicPlatform platform) {
        // 这张卡只是展示当前账号，不能点 —— 所以也不该跟着鼠标长。
        final Card card = plain(card(row, slot, () -> 0.0, () -> 84.0,
                () -> mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        platform.getBrandColor(), .12)));
        avatarSlot(card, platform, 52.0, () -> 14.0, () -> card.getHeight() * .5);

        final Panel vipLayer = veil(card, () -> isAccountVip(platform) ? 1.0 : 0.0);
        final RoundedRectWidget vip = pill(vipLayer, () -> PartyIcons.VIP, () -> "会员", () -> VIP_COLOR,
                () -> vipLayer.getWidth() - 14, () -> vipLayer.getHeight() * .5);

        final LabelWidget name = label(card, () -> NeteaseMusicService.getAccountName(platform),
                FontManager.pf16bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - 80
                    - (isAccountVip(platform) ? vip.getWidth() + 22 : 20)));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(80, inkTop(name, card.getHeight() * .5 - 11));
        });

        final LabelWidget meta = label(card, () -> identityMeta(platform), FontManager.pf12);
        meta.setBeforeRenderCallback(() -> {
            meta.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            meta.setMaxWidth(Math.max(1, card.getWidth() - 80 - 20));
            meta.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            meta.setPosition(80, inkTop(meta, card.getHeight() * .5 + 10));
        });
    }

    /**
     * QQ 的登录方式分段控件。
     *
     * <p>做成一条轨道加一颗会滑的滑块，而不是两颗各自换色的按钮：滑块的位置本身就说明了
     * 「这是二选一」，而两颗独立按钮看上去像两个可以各自开关的选项。</p>
     */
    private void channelSegment(final Panel row) {
        final RoundedRectWidget track = new RoundedRectWidget();
        row.addChild(track);
        track.setClickable(false);
        track.setRadius(11);
        track.setBeforeRenderCallback(() -> {
            track.setBounds(Math.max(1, row.getWidth() - (PAD + CARD_INSET) * 2), 34);
            track.setPosition(PAD + CARD_INSET, 8);
            track.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        final double[] slide = {qqLoginChannel == QQLoginChannel.WECHAT ? 1.0 : 0.0};
        final RoundedRectWidget knob = new RoundedRectWidget();
        track.addChild(knob);
        knob.setClickable(false);
        knob.setRadius(9);
        knob.setBeforeRenderCallback(() -> {
            slide[0] = Interpolations.interpolate(slide[0],
                    qqLoginChannel == QQLoginChannel.WECHAT ? 1.0 : 0.0, .26f);
            double half = Math.max(1, (track.getWidth() - 8) * .5);
            knob.setBounds(half, Math.max(1, track.getHeight() - 8));
            knob.setPosition(4 + half * slide[0], 4);
            // 颜色跟着滑块一起走：落点才不会突然换个色。
            knob.setColor(mix(MusicPlatform.QQ.getBrandColor(), 0x4CAF72, slide[0]));
        });

        channelHalf(track, QQLoginChannel.QQ, FontelloIcons.QQ, "QQ 扫码", 0, slide);
        channelHalf(track, QQLoginChannel.WECHAT, FontelloIcons.WECHAT, "微信登录", 1, slide);
    }

    private void channelHalf(final RoundedRectWidget track, final QQLoginChannel channel, final String mark,
                             final String text, final int index, final double[] slide) {
        // [0] 图标左边缘、[1] 文字左边缘，都是相对轨道自身的坐标。
        final double[] slot = new double[2];
        final IntSupplier ink = () -> mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), 0xFFFFFF,
                index == 0 ? 1.0 - slide[0] : slide[0]);

        // 命中区单独一层且完全透明：图标与文字必须挂在轨道上，挂在这一层里会被它的 alpha 乘掉。
        final RoundedRectWidget hit = new RoundedRectWidget();
        track.addChild(hit);
        hit.setRadius(9);
        hit.setShouldOverrideMouseCursor(true);
        hit.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            qqLoginChannel = channel;
            statusText = channel == QQLoginChannel.WECHAT
                    ? "已选择微信登录，点下方按钮获取二维码"
                    : "已选择 QQ 扫码，点下方按钮获取二维码";
            statusTone = 0;
            return true;
        });
        hit.setBeforeRenderCallback(() -> {
            double half = Math.max(1, (track.getWidth() - 8) * .5);
            hit.setBounds(half, Math.max(1, track.getHeight() - 8));
            hit.setPosition(4 + half * index, 4);
            hit.setAlpha(0f);
            double glyphWidth = FontManager.fontello16.getStringWidthD(mark);
            double total = glyphWidth + 6.0 + FontManager.pf12bold.getStringWidthD(text);
            double left = 4 + half * index + (half - total) * .5;
            slot[0] = left;
            slot[1] = left + glyphWidth + 6.0;
        });

        final LabelWidget markLabel = label(track, mark, FontManager.fontello16);
        markLabel.setBeforeRenderCallback(() -> {
            markLabel.setColor(ink.getAsInt());
            markLabel.setPosition(slot[0], inkTop(markLabel, track.getHeight() * .5));
        });
        final LabelWidget caption = label(track, text, FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(ink.getAsInt());
            caption.setPosition(slot[1], inkTop(caption, track.getHeight() * .5));
        });
    }
    // ════════════════════════════ 页脚按钮 ════════════════════════════

    private void primaryAction(final Panel dialog, final MusicPlatform platform,
                               final boolean loggedIn, final boolean addAccountByQr) {
        final boolean signOut = loggedIn && !addAccountByQr;
        final RoundedButtonWidget button = action(dialog,
                () -> signOut ? PartyIcons.LOGOUT : requestRunning ? PartyIcons.HOURGLASS : PartyIcons.KEY,
                () -> signOut ? "退出登录"
                        : requestRunning ? "等待扫码…"
                        : addAccountByQr ? "重新获取二维码" : "开始扫码登录",
                FontManager.pf12bold, () -> 0xFFFFFF,
                () -> {
                    if (addAccountByQr) {
                        if (requestRunning) {
                            statusText = "等待扫码中，正在持续检查登录状态…";
                            statusTone = 3;
                        } else {
                            startQrLogin(platform, pageGeneration.get(), true);
                        }
                    } else if (NeteaseMusicService.isLoggedIn(platform)) {
                        logout(platform);
                    } else if (!requestRunning) {
                        startQrLogin(platform, pageGeneration.get());
                    }
                });
        button.setBeforeRenderCallback(() -> {
            button.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 32);
            button.setPosition(PAD + CARD_INSET, dialog.getHeight() - 42);
            int base = signOut ? BAD_COLOR : platform.getBrandColor();
            button.setColor(mix(base, 0xFFFFFF, button.isHovering() ? .16 : 0.0));
        });
    }

    /** 次级按钮：从下往上第 {@code index} 排（0 是主按钮那一排，所以次级从 1 起）。 */
    private RoundedButtonWidget secondaryAction(final Panel dialog, final int index, final Supplier<String> glyph,
                                                final Supplier<String> text, final IntSupplier ink,
                                                final Runnable onClick) {
        final RoundedButtonWidget button = action(dialog, glyph, text, FontManager.pf12bold, ink, onClick);
        button.setBeforeRenderCallback(() -> {
            button.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 30);
            button.setPosition(PAD + CARD_INSET, dialog.getHeight() - 42 - 36 * index);
            button.setColor(button.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        return button;
    }

    private void historySyncAction(final Panel dialog, final int index) {
        secondaryAction(dialog, index, () -> PartyIcons.SYNC,
                () -> "听歌历史同步：" + (NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled() ? "已开启" : "已关闭"),
                () -> NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled()
                        ? OK_COLOR : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> {
                    if (NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled()) {
                        // 关闭是收回授权，立即生效，不需要再确认一次。
                        applyListeningHistorySync(false);
                        return;
                    }
                    // 开启等于一次隐私授权：压暗页面、居中弹出模态确认，用户明确同意之前不写配置、
                    // 也不允许操作下层界面（确认层在所有播放器内容之上，并独占鼠标与键盘）。
                    NCMScreen.getInstance().openConfirmation(SYNC_CONSENT_TITLE, SYNC_CONSENT_BODY,
                            SYNC_CONSENT_CONFIRM, () -> applyListeningHistorySync(true));
                    // 这里同时把「问过」记下：用户已经自己找到这个开关了，登录时不必再弹一次。
                    NCMPlayerConfig.markNeteaseListeningHistorySyncAsked();
                });
    }

    /**
     * 登录成功后就地问一次要不要开听歌历史同步。
     *
     * <p>这一项过去只藏在账号详情页的一个按钮里，登录完没有任何提示，等于默认没人知道它存在。
     * 放在登录成功这一刻问，是因为此时用户的注意力正落在「我的账号」上，要不要把播放记录带回
     * 网易云这件事的语境最清楚。标记先落盘再弹窗：窗口被直接关掉也算问过，否则每次登录都会
     * 再弹一遍。</p>
     */
    private void maybePromptListeningHistorySync() {
        if (!NCMPlayerConfig.shouldAskNeteaseListeningHistorySync()) return;
        NCMScreen screen = NCMScreen.getInstance();
        if (screen == null) return;
        NCMPlayerConfig.markNeteaseListeningHistorySyncAsked();
        screen.openConfirmation(SYNC_CONSENT_TITLE, SYNC_CONSENT_BODY, SYNC_CONSENT_CONFIRM,
                () -> applyListeningHistorySync(true));
    }

    /**
     * 写入听歌历史同步开关并刷新状态行。开启只会从确认框的回调进来，
     * 因此「用户已明确同意」是这个方法被调用的前提。
     */
    private void applyListeningHistorySync(boolean enabled) {
        NCMPlayerConfig.setNeteaseListeningHistorySyncEnabled(enabled);
        statusText = enabled
                ? "已开启：仅同步本地实际播放达到阈值的网易云歌曲"
                : "已关闭：后续播放仅保留在本地播放器";
        statusTone = enabled ? 1 : 0;
    }

    // ════════════════════════════ 三级页：Cookie 登录 ════════════════════════════

    /** Cookie 导入会先校验登录状态，成功之后才替换本地会话。 */
    private void showCookieLogin() {
        final long token = pageGeneration.incrementAndGet();
        detailPlatform = MusicPlatform.NETEASE;
        requestRunning = false;
        statusText = "粘贴网易云 Cookie 后进行验证";
        statusTone = 0;
        beginPage();

        final Panel dialog = sheet(452, () -> 200.0);
        subHeader(dialog, () -> showDetail(MusicPlatform.NETEASE), () -> "Cookie 登录",
                () -> "仅本地保存 · 校验失败不会覆盖当前登录状态",
                MusicPlatform.NETEASE::getBrandColor);

        final RoundedRectWidget field = new RoundedRectWidget();
        dialog.addChild(field);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 36);
            field.setPosition(PAD + CARD_INSET, HEADER_H + 4);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        final TextFieldWidget cookieField = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(cookieField);
        cookieField.getTextField().setMaxStringLength(4096);
        cookieField.getTextField().isPassword = true;
        cookieField.setEnabled(true).setFocused(true);
        cookieField.setPlaceholder("粘贴 MUSIC_U、MUSIC_A 或完整 Cookie 字符串");
        cookieField.drawUnderline(false);
        cookieField.setBeforeRenderCallback(() -> {
            cookieField.setBounds(Math.max(1, field.getWidth() - 20), Math.max(1, field.getHeight() - 10));
            cookieField.setPosition(field.getRelativeX() + 10, field.getRelativeY() + 5);
            cookieField.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            cookieField.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        // 聚焦描边：输入框是这一页唯一要动手的地方，得让它自己看起来就是焦点。
        stroke(field, 9.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> cookieField.isFocused() ? .7 : 0.0);

        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> HEADER_H + 50);

        final RoundedButtonWidget confirm = action(dialog,
                () -> requestRunning ? PartyIcons.HOURGLASS : PartyIcons.CHECK,
                () -> requestRunning ? "正在校验…" : "验证并登录", FontManager.pf12bold, () -> 0xFFFFFF,
                () -> submitCookie(cookieField, token));
        confirm.setBeforeRenderCallback(() -> {
            confirm.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 32);
            confirm.setPosition(PAD + CARD_INSET, dialog.getHeight() - 42);
            confirm.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF,
                    confirm.isHovering() ? .16 : 0.0));
        });
    }

    private void submitCookie(final TextFieldWidget cookieField, final long token) {
        if (requestRunning) return;
        final String cookie = cookieField.getText() == null ? "" : cookieField.getText().trim();
        if (cookie.isEmpty()) {
            statusText = "请先粘贴 Cookie";
            statusTone = 2;
            return;
        }
        requestRunning = true;
        statusText = "正在验证 Cookie…";
        statusTone = 3;
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject result = CloudMusicApi.loginStatusWithCookie(cookie).toJsonObject();
                if (!hasProfile(result)) {
                    throw new IllegalStateException("Cookie 无效、已过期或缺少登录凭据");
                }
                // 上面那次校验用的是一次性的 per-request Cookie，还没碰 OptionsUtil。
                // 只有校验通过才写进当前会话。
                CloudMusic.loadNCM(cookie);
                if (CloudMusic.profile == null) {
                    throw new IllegalStateException("登录资料加载失败");
                }
                NeteaseAccountProfiles.saveCurrent();
                statusText = "Cookie 登录成功";
                statusTone = 1;
                DownloadDynamicIsland.showNetworkConnectionSuccess("网易云账号");
                Thread.sleep(SUCCESS_HOLD_MILLIS);
                MultiThreadingUtil.runOnMainThread(() -> {
                    requestRunning = false;
                    if (!isCurrent(MusicPlatform.NETEASE, token)) return;
                    NCMScreen.getInstance().markDirty();
                    showOverview();
                    maybePromptListeningHistorySync();
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                MultiThreadingUtil.runOnMainThread(() -> {
                    requestRunning = false;
                    if (!isCurrent(MusicPlatform.NETEASE, token)) return;
                    statusText = "Cookie 登录失败：" + safeMessage(throwable);
                    statusTone = 2;
                    DownloadDynamicIsland.showNetworkConnectionFailure("网易云账号", safeMessage(throwable));
                });
            }
        });
    }

    private static boolean hasProfile(JsonObject result) {
        if (result == null) return false;
        if (result.has("profile") && result.get("profile").isJsonObject()) return true;
        return result.has("data") && result.get("data").isJsonObject()
                && result.getAsJsonObject("data").has("profile")
                && result.getAsJsonObject("data").get("profile").isJsonObject();
    }
    // ════════════════════════════ 三级页：已保存账号 ════════════════════════════

    private void showSavedNeteaseAccounts() {
        final long token = pageGeneration.incrementAndGet();
        detailPlatform = MusicPlatform.NETEASE;
        requestRunning = false;
        statusText = "点一行即可切换；删除只移除本地记录，不会退出账号";
        statusTone = 0;
        beginPage();

        final List<NeteaseAccountProfiles.Account> accounts = NeteaseAccountProfiles.load();
        // 最多先给四行的高度，再多就滚 —— 八个账号全摊开会把弹窗顶到视口边上。
        final double listHeight = accounts.isEmpty()
                ? 84.0 : Math.min(accounts.size() * SAVED_ROW, 4 * SAVED_ROW);
        final Panel dialog = sheet(468, () -> HEADER_H + LEAD + listHeight + 84.0);
        subHeader(dialog, () -> showDetail(MusicPlatform.NETEASE), () -> "已保存账号",
                () -> "本地切换 · 最多保存 8 个",
                MusicPlatform.NETEASE::getBrandColor);

        final Panel body = body(dialog, () -> HEADER_H, () -> 84.0);
        final ScrollPanel feed = feed(body);
        strip(feed, LEAD);
        if (accounts.isEmpty()) {
            emptyState(strip(feed, 84.0));
        } else {
            for (int i = 0; i < accounts.size(); i++) {
                savedAccountRow(strip(feed, SAVED_ROW), i, accounts.get(i), token);
            }
        }

        // 扫码是主路径；Cookie 添加是给手上已经有凭据的人留的，所以做成次级样式。
        final RoundedButtonWidget scan = action(dialog, () -> PartyIcons.PERSON_ADD, () -> "扫码添加",
                FontManager.pf12bold, () -> 0xFFFFFF, () -> {
                    if (requestRunning) return;
                    showDetail(MusicPlatform.NETEASE, true);
                });
        scan.setBeforeRenderCallback(() -> {
            double full = Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2);
            scan.setBounds(Math.max(1, (full - 8) * .54), 30);
            scan.setPosition(PAD + CARD_INSET, dialog.getHeight() - 76);
            scan.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF,
                    scan.isHovering() ? .16 : 0.0));
        });

        final RoundedButtonWidget byCookie = action(dialog, () -> PartyIcons.KEY, () -> "Cookie 添加",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), () -> {
                    if (requestRunning) return;
                    showCookieLogin();
                });
        byCookie.setBeforeRenderCallback(() -> {
            double full = Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2);
            double scanWidth = (full - 8) * .54;
            byCookie.setBounds(Math.max(1, full - scanWidth - 8), 30);
            byCookie.setPosition(PAD + CARD_INSET + scanWidth + 8, dialog.getHeight() - 76);
            byCookie.setColor(byCookie.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> dialog.getHeight() - 30);
    }

    private void emptyState(final Panel row) {
        icon(row, PartyIcons.PERSON_ADD, ICON_XL, () -> row.getWidth() * .5, () -> 30.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        final String text = "还没有保存过账号";
        final LabelWidget caption = label(row, text, FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(row.getWidth() * .5 - FontManager.pf12bold.getStringWidthD(text) * .5,
                    inkTop(caption, 58.0));
        });
    }

    /**
     * 已保存账号的一行。
     *
     * <p>「是不是当前账号」在建页时取一次快照而不是每帧问：{@code NeteaseAccountProfiles.isActive}
     * 要去读配置里的 cookie，那不是每帧该做的事；而这一页只要切过账号就会换页重建。</p>
     */
    private void savedAccountRow(final Panel row, final int slot,
                                 final NeteaseAccountProfiles.Account account, final long token) {
        final boolean current = NeteaseAccountProfiles.isActive(account);
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> 52.0, () -> current
                ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                MusicPlatform.NETEASE.getBrandColor(), .22)
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(!current);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning || current) return false;
            switchAccount(account, token);
            return true;
        });
        stroke(card, 12.0, MusicPlatform.NETEASE::getBrandColor, () -> current ? .5 : 0.0);

        savedAvatar(card, account);

        final RoundedRectWidget remove = new RoundedRectWidget();
        card.addChild(remove);
        remove.setRadius(9);
        remove.setShouldOverrideMouseCursor(true);
        remove.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            removeAccount(account);
            return true;
        });
        remove.setBeforeRenderCallback(() -> {
            remove.setBounds(26, 26);
            remove.setPosition(Math.max(0, card.getWidth() - 38), card.getHeight() * .5 - 13);
            remove.setColor(remove.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), BAD_COLOR, .35)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        icon(remove, PartyIcons.CLOSE, ICON_S, () -> 13.0, () -> 13.0,
                () -> remove.isHovering() ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

        final Panel currentLayer = veil(card, () -> current ? 1.0 : 0.0);
        final RoundedRectWidget flag = pill(currentLayer, () -> PartyIcons.CHECK, () -> "当前",
                () -> OK_COLOR, () -> currentLayer.getWidth() - 46, () -> currentLayer.getHeight() * .5);

        final LabelWidget name = label(card, account::getDisplayName, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - 58 - 54 - (current ? flag.getWidth() : 0)));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(58, inkTop(name, card.getHeight() * .5 - 8));
        });

        final String meta = account.id == null || account.id.trim().isEmpty()
                ? "本地凭据" : "网易云 ID " + account.id;
        final LabelWidget metaLabel = label(card, meta, FontManager.pf12);
        metaLabel.setBeforeRenderCallback(() -> {
            metaLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            metaLabel.setMaxWidth(Math.max(1, card.getWidth() - 58 - 54 - (current ? flag.getWidth() : 0)));
            metaLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            metaLabel.setPosition(58, inkTop(metaLabel, card.getHeight() * .5 + 8));
        });
    }

    private void savedAvatar(final Card card, final NeteaseAccountProfiles.Account account) {
        final Location location = account.getAvatarLocation();
        final String url = account.getAvatarUrl();
        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setClickable(false);
        disc.setRadius(11);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(36, 36);
            disc.setPosition(12, card.getHeight() * .5 - 18);
            disc.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    MusicPlatform.NETEASE.getBrandColor(), .30));
        });

        final String first = initial(account.getDisplayName());
        final LabelWidget letter = label(disc, first, FontManager.pf16bold);
        letter.setBeforeRenderCallback(() -> {
            letter.setColor(0xFFFFFF);
            letter.setPosition(18 - FontManager.pf16bold.getStringWidthD(first) * .5, inkTop(letter, 18.0));
        });

        final RoundedImageWidget photo = new RoundedImageWidget(
                () -> AccountAvatar.textureOrNull(url, location) == null ? null : location, 0, 0, 36, 36);
        disc.addChild(photo);
        photo.setClickable(false);
        photo.setRadius(11);
        photo.setLinearFilter(true);
        photo.setBeforeRenderCallback(() -> {
            photo.setBounds(36, 36);
            photo.setPosition(0, 0);
        });
    }

    private void switchAccount(final NeteaseAccountProfiles.Account account, final long token) {
        requestRunning = true;
        statusText = "正在切换账号…";
        statusTone = 3;
        MultiThreadingUtil.runAsync(() -> {
            boolean success = NeteaseAccountProfiles.switchTo(account);
            MultiThreadingUtil.runOnMainThread(() -> {
                requestRunning = false;
                if (!isCurrent(MusicPlatform.NETEASE, token)) return;
                if (!success) {
                    // 失败就留在这一页：换页会把这行提示冲掉，用户不知道刚才发生了什么。
                    statusText = "账号切换失败，Cookie 可能已过期";
                    statusTone = 2;
                    return;
                }
                NCMScreen.getInstance().markDirty();
                // 成功回上一级（网易云账号页）：这一页的任务已经完成了。
                showDetail(MusicPlatform.NETEASE);
            });
        });
    }

    private void removeAccount(final NeteaseAccountProfiles.Account account) {
        NCMScreen screen = NCMScreen.getInstance();
        if (screen == null) return;
        screen.openConfirmation("移除已保存账号",
                "只从本地列表里移除「" + account.getDisplayName() + "」，不会退出当前登录，也不会影响网易云账号本身。",
                "移除", () -> {
                    NeteaseAccountProfiles.remove(account);
                    // 先重建再写状态：重建那一步会把状态行重置成默认说明。
                    showSavedNeteaseAccounts();
                    statusText = "已移除「" + account.getDisplayName() + "」";
                    statusTone = 1;
                });
    }
    // ════════════════════════════ 扫码登录 ════════════════════════════

    private void startQrLogin(MusicPlatform platform, long token) {
        startQrLogin(platform, token, false);
    }

    /**
     * 让一次扫码会话一直活到上游给出终态。状态变更都发回主线程，
     * 所以「等待」「已扫码」不会在轮询还在跑的时候凭空消失。
     */
    private void startQrLogin(MusicPlatform platform, long token, boolean addAccountByQr) {
        if (requestRunning || closing || detailPlatform != platform || token != pageGeneration.get()) return;
        requestRunning = true;
        qrReady = false;
        publishQrStatus(platform, token, "正在获取登录二维码…", 0);

        // 微信是一条独立的会话：取码与轮询都必须成对地走微信那两个方法。在这里一次性定下来，
        // 循环里就不会出现「用 QQ 的码去问微信的状态」这类跨链路误用。
        final boolean wechat = platform == MusicPlatform.QQ && qqLoginChannel == QQLoginChannel.WECHAT;
        MultiThreadingUtil.runAsync(() -> {
            try {
                QrCode qr = wechat
                        ? NeteaseMusicService.createQqWechatQrCode()
                        : NeteaseMusicService.createQrCode(platform);
                if (!isCurrent(platform, token)) return;
                loadQrTexture(platform, qr.getQrContent(), token);
                publishQrStatus(platform, token, "等待扫码 · 正在持续检查登录状态…", 3);
                int transientErrors = 0;

                while (isCurrent(platform, token)) {
                    // 二维码已经初始化了 Cadence 的登录会话。不要在每次轮询时重新套用旧的网易云
                    // cookie：那会在 Cadence 落盘之前就把刚扫上的账号弄失效。
                    QrLoginState state = wechat
                            ? NeteaseMusicService.checkQqWechatQrCode(qr)
                            : NeteaseMusicService.checkQrCodeSession(platform, qr);
                    if (!isCurrent(platform, token)) return;
                    if (state == QrLoginState.WAITING) {
                        transientErrors = 0;
                        publishQrStatus(platform, token, "等待扫码 · 正在持续检查登录状态…", 3);
                    } else if (state == QrLoginState.SCANNED) {
                        transientErrors = 0;
                        publishQrStatus(platform, token, "已扫码，请在手机上确认…", 3);
                    } else if (state == QrLoginState.CONFIRMED) {
                        if (platform == MusicPlatform.NETEASE) {
                            String confirmedCookie = OptionsUtil.getCookie();
                            if (confirmedCookie == null || confirmedCookie.trim().isEmpty()) {
                                throw new IllegalStateException("服务端未返回有效登录凭据");
                            }
                            CloudMusic.loadNCM(confirmedCookie);
                            if (CloudMusic.profile == null) {
                                throw new IllegalStateException("登录资料加载失败");
                            }
                            NeteaseAccountProfiles.saveCurrent();
                        } else if (platform == MusicPlatform.QQ) {
                            NeteaseMusicService.refreshQQAccountData();
                        }
                        // 酷狗的资料与歌单已经在 checkQrCodeSession 确认那一刻异步拉起，这里不再重复。
                        requestRunning = false;
                        publishQrStatus(platform, token, addAccountByQr ? "扫码成功，账号已添加" : "登录成功", 1);
                        DownloadDynamicIsland.showNetworkConnectionSuccess(platform.getDisplayName() + "账号");
                        // 让成功那一行字停留一下再换页。立刻跳走的话用户只看到界面闪了一下，
                        // 分不清是登上了还是被关掉了。
                        Thread.sleep(SUCCESS_HOLD_MILLIS);
                        MultiThreadingUtil.runOnMainThread(() -> {
                            if (!isCurrent(platform, token)) return;
                            NCMScreen.getInstance().markDirty();
                            if (addAccountByQr && platform == MusicPlatform.NETEASE) {
                                showSavedNeteaseAccounts();
                            } else {
                                showOverview();
                            }
                            if (platform == MusicPlatform.NETEASE) {
                                maybePromptListeningHistorySync();
                            }
                        });
                        return;
                    } else if (state == QrLoginState.EXPIRED) {
                        publishQrStatus(platform, token, "二维码已过期，点下方按钮重新获取", 2);
                        DownloadDynamicIsland.showNetworkConnectionFailure(platform.getDisplayName() + "登录", "二维码已过期");
                        requestRunning = false;
                        return;
                    } else if (state == QrLoginState.ERROR) {
                        transientErrors++;
                        if (transientErrors >= 3) {
                            publishQrStatus(platform, token, "登录状态检查失败，点下方按钮重新获取", 2);
                            DownloadDynamicIsland.showNetworkConnectionFailure(platform.getDisplayName() + "登录", "状态检查失败");
                            requestRunning = false;
                            return;
                        }
                        publishQrStatus(platform, token, "检查连接波动，正在继续检查（" + transientErrors + "/3）…", 3);
                    }
                    Thread.sleep(2000L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                if (isCurrent(platform, token)) {
                    publishQrStatus(platform, token, "二维码登录失败：" + safeMessage(throwable), 2);
                    DownloadDynamicIsland.showNetworkConnectionFailure(platform.getDisplayName() + "登录", safeMessage(throwable));
                    requestRunning = false;
                }
            }
        });
    }

    private void publishQrStatus(MusicPlatform platform, long token, String text, int tone) {
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!isCurrent(platform, token)) return;
            statusText = text;
            statusTone = tone;
        });
    }

    private void logout(MusicPlatform platform) {
        long token = pageGeneration.incrementAndGet();
        requestRunning = true;
        statusText = "正在退出登录…";
        statusTone = 3;
        MultiThreadingUtil.runAsync(() -> {
            try {
                NeteaseMusicService.logout(platform);
                statusText = "已退出登录";
                statusTone = 1;
            } catch (Throwable throwable) {
                statusText = "退出失败：" + safeMessage(throwable);
                statusTone = 2;
            } finally {
                requestRunning = false;
                MultiThreadingUtil.runOnMainThread(() -> {
                    NCMScreen.getInstance().markDirty();
                    // 退出之后留在这一页：接下来大概就是要重新登，不该把人踢回总览。
                    if (!closing && detailPlatform == platform && token == pageGeneration.get()) {
                        showDetail(platform);
                    }
                });
            }
        });
    }

    private void loadQrTexture(MusicPlatform platform, String content, long token) throws Exception {
        BufferedImage image;
        if (platform == MusicPlatform.QQ || platform == MusicPlatform.QISHUI) {
            String encoded = content == null ? "" : content;
            int comma = encoded.indexOf(',');
            if (comma >= 0) encoded = encoded.substring(comma + 1);
            image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        } else {
            image = QRCodeGenerator.generateQRCode(content, 128, 128);
        }
        if (image == null || !isCurrent(platform, token)) throw new IllegalStateException("二维码图片为空");
        final BufferedImage finalImage = image;
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!isCurrent(platform, token)) return;
            TextureManager manager = TextureManager.getInstance();
            Location location = getQrLocation(platform);
            if (manager.getTexture(location) != null) manager.deleteTexture(location);
            manager.loadTexture(location, new DynamicTexture(finalImage));
            // 只有走到这里才算「盘里真有一张能扫的码」。拿贴图存在与否判断会误判：
            // 同一平台上一次会话留下的旧贴图还在表里。
            qrReady = true;
        });
    }

    private boolean isCurrent(MusicPlatform platform, long token) {
        return !closing && detailPlatform == platform && pageGeneration.get() == token;
    }

    private Location getQrLocation(MusicPlatform platform) {
        if (platform == MusicPlatform.KUGOU) return KUGOU_QR;
        if (platform == MusicPlatform.QISHUI) return QISHUI_QR;
        return platform == MusicPlatform.QQ ? QQ_QR : NETEASE_QR;
    }

    // ════════════════════════════ 文案与状态 ════════════════════════════

    /** 扫码提示里该出现的客户端名。微信那条链路要扫的是微信，不是 QQ 音乐。 */
    private String scanClientName(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ && qqLoginChannel == QQLoginChannel.WECHAT) return "微信";
        if (platform == MusicPlatform.KUGOU) return "酷狗音乐客户端";
        if (platform == MusicPlatform.QISHUI) return "抖音 App";
        if (platform == MusicPlatform.QQ) return "手机 QQ";
        return "网易云音乐客户端";
    }

    private String identityMeta(MusicPlatform platform) {
        if (platform == MusicPlatform.NETEASE) {
            User profile = CloudMusic.profile;
            if (profile != null) return "网易云 ID " + profile.getId();
        }
        if (platform == MusicPlatform.QISHUI) {
            String douyinId = QishuiMusicService.getDouyinId();
            if (!douyinId.isEmpty()) return "抖音 ID " + douyinId;
            String accountId = QishuiMusicService.getAccountId();
            if (!accountId.isEmpty()) return "汽水账号 " + accountId;
        }
        return platform.getDisplayName() + " · 已登录";
    }

    private boolean isAccountVip(MusicPlatform platform) {
        if (platform == MusicPlatform.QISHUI) {
            return NeteaseMusicService.isLoggedIn(platform) && QishuiMusicService.isVipAccount();
        }
        return isNeteaseVip(platform);
    }

    /**
     * 是不是「网易云 + 已登录 + 会员」。
     *
     * <p>三个条件缺一不可：QQ 与酷狗没有对应的会员字段，而 {@code CloudMusic.profile} 在切到别的
     * 来源之后仍然留着上一次的网易云资料，只看 profile 会让 QQ 卡上也挂一枚网易云会员标。</p>
     */
    private boolean isNeteaseVip(MusicPlatform platform) {
        if (platform != MusicPlatform.NETEASE || !NeteaseMusicService.isLoggedIn(platform)) return false;
        User profile = CloudMusic.profile;
        return profile != null && profile.isVipMember();
    }

    private int statusColor() {
        if (statusTone == 1) return OK_COLOR;
        if (statusTone == 2) return BAD_COLOR;
        if (statusTone == 3) return WARN_COLOR;
        return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String statusGlyph() {
        if (statusTone == 1) return PartyIcons.CLOUD_DONE;
        if (statusTone == 2) return PartyIcons.WARNING;
        if (statusTone == 3) return PartyIcons.HOURGLASS;
        return PartyIcons.INFO;
    }

    private static String brandGlyph(MusicPlatform platform) {
        if (platform == MusicPlatform.KUGOU) return MusicBrandIcons.KUGOU_MONOGRAM;
        if (platform == MusicPlatform.QISHUI) return MusicBrandIcons.QISHUI_MONOGRAM;
        return platform == MusicPlatform.QQ ? MusicBrandIcons.QQ_MUSIC : MusicBrandIcons.NETEASE_CLOUD_MUSIC;
    }

    /**
     * 品牌字标的字体。
     *
     * <p>必须配对到自己那份字体：music-brand-icons 里没有 U+E888（QQ 音乐），拿错字体会落到
     * 兜底字体上、画出一片空白。酷狗两份图标字体里都没有，用正文字体画一枚汉字字标。</p>
     */
    private static CFontRenderer brandFont(MusicPlatform platform) {
        if (platform == MusicPlatform.KUGOU || platform == MusicPlatform.QISHUI) return FontManager.pf34bold;
        return platform == MusicPlatform.QQ ? FontManager.qqMusicIcon68 : FontManager.musicBrand52;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}