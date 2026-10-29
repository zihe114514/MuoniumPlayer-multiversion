package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.ListenTogetherService;
import com.muoniumplayer.core.ncm.music.ListenTogetherSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QRCodeGenerator;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.panels.PanelLoadingWidget;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpRoom;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「一起听」二级弹窗：建房 / 加入 / 房间态三合一，网易云与酷狗两种官方房间共用这一块面板。
 *
 * <p>房间状态由 {@link ListenTogetherService} 的后台线程推进，随时可能变化，因此这里
 * <b>不按状态重建控件树</b>：四段内容一次性建好，每帧只切 {@code setHidden}。重建控件只发生在
 * 「总览 ↔ 加入房间」这一次用户主动换页时（在点击回调里，不在遍历子组件的过程中）。</p>
 *
 * <p>弹窗骨架与翻页动效交给 {@link NcmSheet}：遮罩、玻璃底板、错峰入场、翻页方向与
 * 高度补间都从基类拿，这里只写「有哪些内容」。</p>
 *
 * <p><b>为什么这一页只认门面、不直接叫某个源的服务。</b>两家的房间服务方法是照着彼此写的
 * （状态机与角色枚举同名同集合），而面板只想问「能进吗、在哪个阶段、成员有谁」。若在三十多个
 * 调用点上各判一次音源，最容易出的事故不是崩溃，而是<b>把网易云的房号拿去发酷狗的心跳</b>——
 * 两边都不报错，只是静默失灵。归属由门面的 {@code ownedByKuGou()} 定：谁正开着房间就还是谁的，
 * 没房间时才看当前音源。两家能力的差集（酷狗查不到他人会员状态、网易云没有房间聊天）
 * 也集中写在那一层，界面上因此不会出现「看着像做了、其实没有」的开关。</p>
 */
public final class ListenTogetherOverlay extends NcmSheet {

    /** 成员行数上限。房间人数超过这个数时，标题里的总人数仍然是真实值。 */
    private static final int MEMBER_ROWS = 4;

    private static final Location QR_TEXTURE = Location.of("muonium/textures/listen/together_qr.png");

    private static final int OK_COLOR = 0x53C68C;
    private static final int WARN_COLOR = 0xD5A44A;
    private static final int ERROR_COLOR = 0xF1767D;

    /** 「已复制」提示的存在时长。 */
    private static final long COPIED_HINT_MILLIS = 1400L;

    /**
     * 当前在哪一页：false = 总览（创建 / 加入 / 服务器 / 房间态），true = 加入房间的填写页。
     *
     * <p>ESC 的两套行为（关弹窗 / 回总览）就靠这一位；输入框里的按键也要按它走。
     * {@link NcmSheet#pageDirection} 记的是翻页方向，这一字段记的是「停在第几页」。</p>
     */
    private boolean joinPage;

    /**
     * 总览页四段内容（不可用 / 连接中 / 空闲 / 房间进行中）的容器引用。
     *
     * <p>放在外面而不是建在 {@link #showOverview()} 的闭包里，是因为翻页时这一层会被
     * {@link NcmSheet#beginPage()} 清掉重建——下一次建完之后还得能由 {@link #onRender}
     * 继续推动显隐，所以引用必须活过那次清空。</p>
     */
    private final Panel[] overviewSections = new Panel[5];

    private String bakedQrContent = "";
    private volatile boolean bakingQr;
    private double qrReveal;

    private long copiedAtMillis;
    private String copiedLabel = "";

    /**
     * 每行成员的淡入进度。放在数组里而不是控件上，是因为控件在隐藏帧不跑回调，
     * 而进度需要在「显示的第一帧」就从 0 开始重新累积。
     */
    private final double[][] memberReveal = new double[MEMBER_ROWS][1];

    private TextFieldWidget inviteField;
    private String joinHint = "支持整条分享链接、房间号#邀请人ID，或只有房间号";
    private int joinHintColor = 0xAEB5C4;

    public void onInit() {
        showOverview();
    }

    public void handleEscape() {
        if (joinPage) {
            // NcmSheet#back(Runnable) 把这次翻页标成反向，让 pageDelta 朝负向滑回总览页。
            back(this::showOverview).run();
        } else {
            dispose();
        }
    }

    @Override
    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        // ESC 必须留给 NCMScreen 的模态链路（回总览 / 关弹窗），不能被输入框吃掉。
        if (keyCode == com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE) return false;
        if (joinPage && inviteField != null && inviteField.isFocused()) {
            inviteField.onKeyTyped(typedChar, keyCode);
            return true;
        }
        return false;
    }

    /**
     * 房间状态由后台线程推进，每帧都要把四段内容里哪段该显示推一遍。
     *
     * <p>挂在 {@link #onRender} 而不是塞进 {@link NcmSheet#sheet} 的回调 —— 后者只管弹窗
     * 自身的尺寸与位置，加上「总览 ↔ 加入房间」的翻页透明度，混进业务状态切换会撑到一百行。
     * 这里只读 {@link ListenTogetherService} 的快照并切显隐，与 UI 时钟解耦。</p>
     */
    @Override
    public void onRender(double mouseX, double mouseY) {
        super.onRender(mouseX, mouseY);
        if (joinPage) return;
        if (overviewSections[0] == null) return;
        boolean available = ListenTogetherService.isAvailable();
        ListenTogetherService.Phase phase = ListenTogetherService.getPhase();
        boolean busy = phase == ListenTogetherService.Phase.CONNECTING;
        boolean active = phase == ListenTogetherService.Phase.ACTIVE;
        // 官方区（0=警告卡 / 1=连接中 / 2=空闲卡）随官方状态切换；4=下排两卡
        // （服务器房间/直连房间）在官方不占满页面时**常驻显示**——官方不可用时
        // 它们是唯一的可用入口，警告卡与它同屏，不能被整页盖住。
        overviewSections[0].setHidden(available || busy || active);
        overviewSections[1].setHidden(!available || !busy);
        overviewSections[2].setHidden(!available || busy || active);
        overviewSections[3].setHidden(!available || busy || !active);
        overviewSections[4].setHidden(busy || active);
        if (!active) {
            for (double[] slot : memberReveal) slot[0] = 0.0;
            qrReveal = 0.0;
        }
        ensureQrTexture();
    }
    // ──────────────────────────── 总览页 ────────────────────────────

    private void showOverview() {
        // 初次进入 / 从加入房间页回退，都走这条路。NcmSheet#beginPage 帮我们清掉旧页并切翻页方向。
        beginPage();
        joinPage = false;
        inviteField = null;
        for (double[] slot : memberReveal) slot[0] = 0.0;
        qrReveal = 0.0;

        final Panel dialog = sheet(474, this::targetOverviewHeight);
        // 副标题写清楚是哪一家的房间：两个源的房间互不相通，界面上却共用这一块面板，
        // 「和好友一起听」这句话单独看分不出对方要登的是哪个 App。
        addTitle(dialog, "一起听", "与好友共享同一条播放进度 · "
                + ListenTogetherService.backendLabel() + "官方房间");
        // 服务器房间入口常驻在标题右侧：它和网易云官方一起听是两套互不相干的实现，
        // 但对用户是同一件事，所以合并在这一个面板里。四种状态下都要能点到 ——
        // 网易云那半边不可用（没登录 / 非网易云源）时，服务器房间照样能用。
        addServerRoomPill(dialog);

        overviewSections[0] = section(dialog);
        buildUnavailable(overviewSections[0]);
        overviewSections[1] = section(dialog);
        buildConnecting(overviewSections[1]);
        overviewSections[2] = section(dialog);
        buildIdle(overviewSections[2]);
        overviewSections[3] = section(dialog);
        buildActive(overviewSections[3]);
        // 常驻区：服务器房间 / 直连房间两张卡。官方空闲或不可用时与官方区同屏——
        // 它们不受登录态与音源限制，官方不可用时就是唯一的可用入口。
        overviewSections[4] = section(dialog);
        buildSelfHostedRow(overviewSections[4]);

        addStatusLine(dialog);
    }

    /** 总览页高度：官方区（警告/空闲）+ 常驻两卡，随状态收缩。 */
    private double targetOverviewHeight() {
        if (!ListenTogetherService.isAvailable()) return 344.0;
        ListenTogetherService.Phase phase = ListenTogetherService.getPhase();
        if (phase == ListenTogetherService.Phase.CONNECTING) return 344.0;
        if (phase == ListenTogetherService.Phase.ACTIVE) return 440.0;
        // 空闲态：全宽官方卡 + 两张半宽卡（两行）+ 两行脚注。
        return 344.0;
    }

    /** 一段内容的容器：本身不画任何东西，只是用来整段切显隐。 */
    private Panel section(final Panel dialog) {
        Panel panel = new Panel();
        dialog.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            panel.setBounds(Math.max(1, dialog.getWidth()), Math.max(1, dialog.getHeight()));
            panel.setPosition(0, 0);
        });
        return panel;
    }

    private void addStatusLine(final Panel dialog) {
        LabelWidget status = new LabelWidget(ListenTogetherService::getStatusText, FontManager.pf12);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(ListenTogetherService.isStatusError() ? ERROR_COLOR
                    : ListenTogetherService.isActive() ? OK_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            status.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16, dialog.getHeight() - 22);
        });
    }

    // ──────────────────────────── 不可用 ────────────────────────────

    /**
     * 官方一起听不可用（未登录 / 音源不支持）时的提示。
     *
     * <p>刻意做成<b>上半一块紧凑卡</b>而不是占满整页：服务器房间与直连房间不受登录态与
     * 音源限制，此时仍然完全可用——它们的两张入口卡就挂在这块提示的下面（
     * {@link #buildIdle} 的下排），把整页让给警告等于告诉用户「全都没了」。</p>
     */
    private void buildUnavailable(final Panel host) {
        RoundedRectWidget card = new RoundedRectWidget();
        host.addChild(card);
        card.setGlassRim(GlassLook.Surface.PLAYER);
        card.setClickable(false);
        card.setRadius(10);
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, host.getWidth() - 32), 76);
            card.setPosition(16, 68);
            card.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            card.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        LabelWidget icon = new LabelWidget(FontelloIcons.WARNING, FontManager.fontello22);
        host.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(WARN_COLOR);
            icon.setPosition(32, 92);
        });

        LabelWidget title = new LabelWidget("官方一起听暂不可用", FontManager.pf14bold);
        host.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(68, 86);
        });

        LabelWidget detail = new LabelWidget(this::unavailableReason, FontManager.pf12);
        host.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 右界 = 右侧动作键（150 宽，贴右缘 16）的左缘再让 8：原来的定值 -240 在窄弹窗
            // 下把说明夹到只剩 8 个字，而动作键其实只占右端一小截。
            detail.setMaxWidth(Math.max(60, host.getWidth() - 68 - 166 - 8));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(68, 106);
        });

        // 登录 / 换源按钮：不再占满整行，收窄成右侧动作键——下方还有两张可用的入口卡。
        final double[] hover = new double[1];
        RoundedButtonWidget action = new RoundedButtonWidget(this::unavailableActionLabel, FontManager.pf12bold);
        host.addChild(action);
        action.setGlassRim(GlassLook.Surface.PLAYER);
        action.setRadius(8);
        action.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            dispose();
            if (needsSourceSwitch()) {
                NCMScreen.getInstance().openMusicSourceManager();
            } else {
                NCMScreen.getInstance().openAccountManager();
            }
            return true;
        });
        action.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], action.isHovering() ? 1.0 : 0.0, .22f);
            action.setBounds(150, 34);
            action.setPosition(Math.max(1, host.getWidth() - 16 - 150), 86);
            action.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .16));
            action.setTextColor(0xFFFFFF);
        });
    }

    // ──────────────────────────── 服务器房间（mpsync 服务端 mod） ────────────────────────────

    /**
     * 标题右侧那颗常驻的「服务器房间」按钮。
     *
     * <p>网易云官方一起听与服务器房间是两套完全独立的实现（一个走网易云的接口，一个走本服的
     * 服务端 mod），但对用户是同一件事，所以入口合并在这一个面板里。这颗按钮在四种状态下都在，
     * 因为网易云那半边不可用时（没登录、或当前音源不是网易云）服务器房间照样能用。</p>
     */
    private void addServerRoomPill(final Panel dialog) {
        final double[] hover = new double[1];
        RoundedButtonWidget pill = new RoundedButtonWidget(this::sourcePillLabel, FontManager.pf12bold);
        dialog.addChild(pill);
        pill.setGlassRim(GlassLook.Surface.PLAYER);
        pill.setRadius(8);
        pill.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            openServerRoom();
            return true;
        });
        pill.setBeforeRenderCallback(() -> {
            boolean present = MpPartyClient.serverPresent();
            boolean inRoom = MpPartyClient.inRoom();
            boolean directOn = com.muoniumplayer.party.direct.MpDirect.isActive();
            hover[0] = Interpolations.interpolate(hover[0], pill.isHovering() ? 1.0 : 0.0, .22f);
            pill.setBounds(pillLabelWidth(), 28);
            pill.setPosition(Math.max(1, dialog.getWidth() - 16 - pill.getWidth()), 14);
            if (inRoom || directOn) {
                // 有活跃会话（服务器房间或直连）：绿底白字，一眼看出「现在连着的是它」。
                pill.setColor(mix(OK_COLOR, 0xFFFFFF, hover[0] * .18));
                pill.setTextColor(0xFFFFFF);
            } else {
                pill.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
                pill.setTextColor(present ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            }
            pill.setFillAlpha(inRoom || directOn ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            // 服务端没装也照样可点：房间面板会把「这台服务器没装服务端 mod」直接写在脸上，
            // 比让入口凭空消失、用户以为功能不存在要好。
        });

        // 来源状态行：pill 下方一行小字，把「当前会话是哪条链路的」写成一句话。
        LabelWidget source = new LabelWidget(this::sourceStateLine, FontManager.pf10bold);
        dialog.addChild(source);
        source.setClickable(false);
        source.setBeforeRenderCallback(() -> {
            source.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            source.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            source.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            source.setPosition(16, 57);
        });
    }

    /**
     * 右上角 pill 的文案：按活跃会话分级。
     *
     * <p>直连会话的房间快照与服务器房间写的是<b>同一个字段</b>（{@code MpPartyClient.room}），
     * 所以 {@code inRoom()} 在直连会话里恒为真 —— 不能拿它当「MC 服务器会话」的证据，
     * 否则直连会话会被误报成「双会话并行」。直连优先判定后再看 inRoom。</p>
     */
    private String sourcePillLabel() {
        boolean inRoom = MpPartyClient.inRoom();
        boolean directOn = com.muoniumplayer.party.direct.MpDirect.isActive();
        if (directOn) return inRoom ? "直连房间 · 在听" : "直连房间";
        if (inRoom) return "服务器房间 · 在房";
        return "服务器房间";
    }

    private double pillLabelWidth() {
        String label = sourcePillLabel();
        return Math.max(96, FontManager.pf12bold.getStringWidthD(label) + 24);
    }

    /**
     * 来源状态行：一句话讲清当前会话来自哪条链路。
     *
     * <p>与 {@link #sourcePillLabel()} 同一条规则：直连活跃时 {@code inRoom()} 读到的是
     * 直连快照，不能并列报成两条会话 —— 传输层（{@code MpHostRouter}）同一时刻只有一路，
     * 客户端永远只在一个房间里。</p>
     */
    private String sourceStateLine() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        boolean directOn = com.muoniumplayer.party.direct.MpDirect.isActive();
        if (com.muoniumplayer.party.direct.MpDirect.isHosting()) {
            parts.add("直连房间（本机托管）");
        } else if (com.muoniumplayer.party.direct.MpDirect.isJoined()) {
            parts.add("直连房间（好友托管）");
        }
        if (!directOn && MpPartyClient.inRoom()) {
            MpRoom room = MpPartyClient.room();
            parts.add("MC 服务器（" + (room == null || !room.channel
                    ? "mpsync 服务端" : "频道") + "）");
        }
        if (ListenTogetherService.isActive()) {
            parts.add("官方一起听（" + ListenTogetherService.backendLabel() + "）");
        }
        if (parts.isEmpty()) {
            return "当前没有活跃的一起听会话";
        }
        return "当前会话来源：" + String.join(" + ", parts);
    }

    /** 服务器房间卡片的副标题：跟着房间状态走。 */
    private String serverRoomCardDetail() {
        if (!MpPartyClient.serverPresent()) {
            return "本服未安装一起听服务端 mod，点开可查看说明";
        }
        if (!MpPartyClient.inRoom()) {
            return "同一台服务器的玩家共享一条进度，房主切歌、成员点歌";
        }
        String room = MpPartyClient.room() == null ? "" : MpPartyClient.room().name;
        String track = MpPartyClient.currentTrack() == null ? "暂无在播"
                : MpPartyClient.currentTrack().name;
        return "在房间「" + room + "」· " + MpPartyClient.members().size() + " 人 · " + track;
    }

    private void openServerRoom() {
        // 与来源面板跳账号面板同一套做法：先收掉自己，再开下一层，避免两层模态叠在一起。
        dispose();
        NCMScreen.getInstance().openMpParty();
    }

    private String unavailableReason() {
        if (needsSourceSwitch()) {
            return "一起听只有网易云与酷狗两种官方实现，当前来源没有房间接口。";
        }
        return "需要先登录" + ListenTogetherService.backendLabel() + "账号，房间与邀请都依赖登录态。";
    }

    private String unavailableActionLabel() {
        return needsSourceSwitch() ? "切换音乐来源" : "登录" + ListenTogetherService.backendLabel() + "账号";
    }

    /**
     * 这一侧不可用，到底是「当前来源根本没有房间实现」还是「来源对了但没登录」。
     *
     * <p>分开的理由：两种情况要给的不是同一个入口——前者该去来源面板，后者该去账号面板。
     * 合成一句「不是网易云就去换源」的话，酷狗登录着的时候点这颗按钮会被踢去换源。</p>
     */
    private boolean needsSourceSwitch() {
        MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
        return platform != MusicPlatform.NETEASE && platform != MusicPlatform.KUGOU;
    }

    // ──────────────────────────── 连接中 ────────────────────────────

    private void buildConnecting(final Panel host) {
        PanelLoadingWidget loading = new PanelLoadingWidget(ListenTogetherService::getStatusText);
        host.addChild(loading);
        loading.setClickable(false);
        loading.setBeforeRenderCallback(() -> {
            loading.setBounds(Math.max(1, host.getWidth()), 120);
            loading.setPosition(0, 74);
        });
    }
    // ──────────────────────────── 空闲（官方卡） ────────────────────────────

    /** 官方空闲区：只放官方卡；服务器房间/直连房间在常驻区（buildSelfHostedRow）。 */
    private void buildIdle(final Panel host) {
        // 全宽官方卡：名字里写清是谁的房间——「创建房间」这类泛称会让人
        // 以为是又一个独立功能。创建/加入在其内页二选一（showOfficialPage）。
        addWideCard(host, 0, FontelloIcons.ACCOUNT_ADD,
                "官方一起听 · " + ListenTogetherService.backendLabel(),
                this::officialCardDetail, true, this::showOfficialPage);

        // 脚注挂在常驻两卡（row 1 → 172..268）的下方：官方卡与常驻卡同屏时，
        // 这两行说明若留在旧位（190/206）会被后渲染的卡片整个压住。
        addFootnote(host, "受官方接口限制：加入方无法自动跟随主机的暂停与切歌，", 278);
        addFootnote(host, "需要在房间页点「同步一次」手动对齐当前曲目与进度。", 294);
    }

    /**
     * 常驻区：服务器房间 / 直连房间两张半宽卡。
     *
     * <p>不受登录态与音源限制：官方一起听不可用（没登录 / 音源不支持）时，
     * 警告卡与这一排<b>同屏</b>——用户看到「官方用不了」的同时，立刻能看到
     * 还有哪些路可走，而不是整页只有一句警告。</p>
     */
    private void buildSelfHostedRow(final Panel host) {
        // row 1：y=172，正好接在全宽官方卡（68~164）下方；不可用态警告卡（68~144）
        // 与它之间留 28px 空隙，同屏不重叠。
        addGridCard(host, 0, 1, FontelloIcons.LEADERBOARD, "服务器房间",
                this::serverRoomCardDetail, false, this::openServerRoom);
        addGridCard(host, 1, 1, FontelloIcons.UNLINK, "直连房间",
                this::directRoomCardDetail, false, this::openDirectRoom);
    }

    /** 官方一起听卡的详情：随音源与在房状态走。 */
    private String officialCardDetail() {
        if (ListenTogetherService.isActive()) {
            return "在房 · " + ListenTogetherService.getMembers().size() + " 人 · 房间 "
                    + shortRoom(ListenTogetherService.getRoomId());
        }
        return "网易云 / 酷狗的官方房间：与好友共享一条播放进度，点开创建或加入";
    }

    /** 官方一起听的内页：创建 / 加入二选一（沿用加入页的翻页骨架）。 */
    private void showOfficialPage() {
        beginPage();
        joinPage = false;
        inviteField = null;

        final Panel dialog = sheet(452, () -> 232.0);
        addBackButton(dialog, back(this::showOverview));
        addDetailTitle(dialog, "官方一起听 · " + ListenTogetherService.backendLabel(),
                "用你的 " + ListenTogetherService.backendLabel() + " 账号开房，或加入好友的房间");

        LabelWidget createTitle = new LabelWidget("创建房间", FontManager.pf14bold);
        dialog.addChild(createTitle);
        createTitle.setClickable(false);
        createTitle.setBeforeRenderCallback(() -> {
            createTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            createTitle.setPosition(16, 66);
        });
        LabelWidget createSub = new LabelWidget(
                ListenTogetherService.ownedByKuGou()
                        ? "生成房间与邀请码，你的播放、暂停、切歌同步给好友"
                        : "生成房间与分享链接，你的播放、暂停、切歌同步给好友",
                FontManager.pf12);
        dialog.addChild(createSub);
        createSub.setClickable(false);
        createSub.setBeforeRenderCallback(() -> {
            createSub.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            createSub.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            createSub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            createSub.setPosition(16, 88);
        });

        LabelWidget joinTitle = new LabelWidget("加入房间", FontManager.pf14bold);
        dialog.addChild(joinTitle);
        joinTitle.setClickable(false);
        joinTitle.setBeforeRenderCallback(() -> {
            joinTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            joinTitle.setPosition(16, 118);
        });
        LabelWidget joinSub = new LabelWidget("粘贴好友发来的分享链接或邀请码", FontManager.pf12);
        dialog.addChild(joinSub);
        joinSub.setClickable(false);
        joinSub.setBeforeRenderCallback(() -> {
            joinSub.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            joinSub.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            joinSub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            joinSub.setPosition(16, 140);
        });

        final double[] createHover = new double[1];
        RoundedButtonWidget create = new RoundedButtonWidget("创建房间", FontManager.pf12bold);
        dialog.addChild(create);
        create.setGlassRim(GlassLook.Surface.PLAYER);
        create.setRadius(8);
        create.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            ListenTogetherService.createRoom();
            return true;
        });
        create.setBeforeRenderCallback(() -> {
            createHover[0] = Interpolations.interpolate(createHover[0], create.isHovering() ? 1.0 : 0.0, .22f);
            create.setBounds((Math.max(1, dialog.getWidth() - 32) - 8) * .5, 34);
            create.setPosition(16, dialog.getHeight() - 56);
            create.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, createHover[0] * .16));
            create.setTextColor(0xFFFFFF);
        });

        final double[] joinHover = new double[1];
        RoundedButtonWidget join = new RoundedButtonWidget("加入房间", FontManager.pf12bold);
        dialog.addChild(join);
        join.setGlassRim(GlassLook.Surface.PLAYER);
        join.setRadius(8);
        join.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showJoinPage();
            return true;
        });
        join.setBeforeRenderCallback(() -> {
            joinHover[0] = Interpolations.interpolate(joinHover[0], join.isHovering() ? 1.0 : 0.0, .22f);
            double w = (Math.max(1, dialog.getWidth() - 32) - 8) * .5;
            join.setBounds(w, 34);
            join.setPosition(16 + w + 8, dialog.getHeight() - 56);
            join.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), joinHover[0]));
            join.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            join.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
    }

    /** 全宽卡：占满整行（官方一起听这类需要整行说清楚的事）。 */
    private void addWideCard(final Panel host, final int row,
                             final String glyph, String titleText, Supplier<String> detailText,
                             final boolean primary, final Runnable action) {
        addGridCardFull(host, row, 0, 1.0, glyph, titleText, detailText, primary, action);
    }

    /** 2×2 卡片网格里的半宽卡：col 0/1，宽度与位置在每帧回调里按宿主实宽算。 */
    private void addGridCard(final Panel host, final int col, final int row,
                             final String glyph, String titleText, Supplier<String> detailText,
                             final boolean primary, final Runnable action) {
        addGridCardFull(host, row, col, 0.5, glyph, titleText, detailText, primary, action);
    }

    /**
     * 卡片网格的公共实现：{@code widthFrac} 是这张卡占内容区宽的比例（全宽 1.0 / 半宽 0.5），
     * {@code col} 只在半宽时用于横向偏移。宽度与位置都在每帧回调里按宿主实宽算——
     * 构建时面板还没渲染，宽度是 0。
     */
    private void addGridCardFull(final Panel host, final int row, final int col, final double widthFrac,
                                 final String glyph, String titleText, Supplier<String> detailText,
                                 final boolean primary, final Runnable action) {
        final double[] hover = new double[1];
        final double y = 68 + row * 104.0;

        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        host.addChild(card);
        card.setGlassRim(GlassLook.Surface.PLAYER);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], card.isHovering() ? 1.0 : 0.0, .2f);
            double contentW = Math.max(1, host.getWidth() - 32);
            double cardW = widthFrac >= 1.0 ? contentW : (contentW - 8.0) * .5;
            card.setBounds(Math.max(1, cardW), 96);
            // 悬停整卡轻微上抬 2px：与旧单列版同一手感。
            card.setPosition(16 + (widthFrac >= 1.0 ? 0 : (cardW + 8.0) * col),
                    y - hover[0] * 2.0);
            if (primary) {
                card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .18));
            } else {
                card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
            }
            card.setFillAlpha(primary ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            card.setTextColor(0x00FFFFFF);
        });

        LabelWidget icon = new LabelWidget(glyph, FontManager.fontello18);
        card.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(primary ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            icon.setPosition(16, 21);
        });

        LabelWidget title = new LabelWidget(titleText, FontManager.pf14bold);
        card.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(primary ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(44, 16);
        });

        LabelWidget detail = new LabelWidget(detailText, FontManager.pf10bold);
        card.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(primary ? 0xE0FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, card.getWidth() - 60));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(44, 40);
        });

        LabelWidget chevron = new LabelWidget("›", FontManager.pf20bold);
        card.addChild(chevron);
        chevron.setClickable(false);
        chevron.setBeforeRenderCallback(() -> {
            chevron.setColor(primary ? 0xE0FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            chevron.setPosition(card.getWidth() - 20 + hover[0] * 3.0, 18);
        });
    }

    /** 直连房间卡片的副标题：跟着直连状态走。 */
    private String directRoomCardDetail() {
        if (com.muoniumplayer.party.direct.MpDirect.isHosting()) {
            return "托管中 · " + com.muoniumplayer.party.direct.MpDirect.onlineMemberCount() + " 位好友在线";
        }
        if (com.muoniumplayer.party.direct.MpDirect.isJoined()) {
            return "已连接好友的直连房间";
        }
        return "第三方服务器也能一起听：一人开房、好友凭邀请串加入";
    }

    private void openDirectRoom() {
        // 与服务器房间同款跳法：先收掉自己再开下一层，两层模态叠在一起会抢键盘与点击。
        dispose();
        NCMScreen.getInstance().openMpDirect();
    }

    private void addFootnote(final Panel host, String text, final double y) {
        LabelWidget note = new LabelWidget(text, FontManager.pf12);
        host.addChild(note);
        note.setClickable(false);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, host.getWidth() - 32));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(16, y);
        });
    }

    private void addActionCard(final Panel host, final double y, final String glyph,
                               String titleText, final String detailText,
                               final boolean primary, final Runnable action) {
        addActionCard(host, y, glyph, titleText, () -> detailText, primary, action);
    }

    /** 详情随状态变的版本：服务器房间那张卡片要显示房间名与在听人数。 */
    private void addActionCard(final Panel host, final double y, final String glyph,
                               String titleText, Supplier<String> detailText,
                               final boolean primary, final Runnable action) {
        final double[] hover = new double[1];

        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        host.addChild(card);
        card.setGlassRim(GlassLook.Surface.PLAYER);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], card.isHovering() ? 1.0 : 0.0, .2f);
            card.setBounds(Math.max(1, host.getWidth() - 32), 88);
            // 悬停时整块卡片轻微上抬 2px：比单纯变色更能说明"这是个可以点的东西"。
            card.setPosition(16, y - hover[0] * 2.0);
            if (primary) {
                card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .18));
            } else {
                card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
            }
            card.setFillAlpha(primary ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            card.setTextColor(0x00FFFFFF);
        });

        LabelWidget icon = new LabelWidget(glyph, FontManager.fontello22);
        card.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(primary ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            icon.setPosition(20, 30);
        });

        LabelWidget title = new LabelWidget(titleText, FontManager.pf16bold);
        card.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(primary ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(60, 24);
        });

        LabelWidget detail = new LabelWidget(detailText, FontManager.pf12);
        card.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(primary ? 0xE0FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, card.getWidth() - 84));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(60, 50);
        });

        LabelWidget chevron = new LabelWidget("›", FontManager.pf20bold);
        card.addChild(chevron);
        chevron.setClickable(false);
        chevron.setBeforeRenderCallback(() -> {
            chevron.setColor(primary ? 0xE0FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 悬停时向右滑 3px，配合卡片上抬形成"被推开"的方向感。
            chevron.setPosition(card.getWidth() - 22 + hover[0] * 3.0, 34);
        });
    }
    // ──────────────────────────── 房间进行中 ────────────────────────────

    /** 右侧二维码底板的边长；左侧文本列的可用宽度按它反推。 */
    private static final double QR_PLATE = 96.0;

    private void buildActive(final Panel host) {
        RoundedRectWidget card = new RoundedRectWidget();
        host.addChild(card);
        card.setGlassRim(GlassLook.Surface.PLAYER);
        card.setClickable(false);
        card.setRadius(10);
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, host.getWidth() - 32), 58);
            card.setPosition(16, 64);
            card.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            card.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        PulseDot dot = new PulseDot();
        host.addChild(dot);
        dot.setClickable(false);
        dot.setBeforeRenderCallback(() -> {
            dot.setBounds(16, 16);
            dot.setPosition(32, 85);
        });

        LabelWidget roomTitle = new LabelWidget(
                () -> "房间 " + shortRoom(ListenTogetherService.getRoomId()), FontManager.pf14bold);
        host.addChild(roomTitle);
        roomTitle.setClickable(false);
        roomTitle.setBeforeRenderCallback(() -> {
            roomTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            roomTitle.setMaxWidth(Math.max(1, host.getWidth() - 72));
            roomTitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            roomTitle.setPosition(56, 74);
        });

        LabelWidget role = new LabelWidget(this::roleDescription, FontManager.pf12);
        host.addChild(role);
        role.setClickable(false);
        role.setBeforeRenderCallback(() -> {
            role.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            role.setMaxWidth(Math.max(1, host.getWidth() - 72));
            role.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            role.setPosition(56, 96);
        });

        // 二维码：底板先 add、二维码后 add，靠声明顺序保证底板画在下面。
        RoundedRectWidget plate = new RoundedRectWidget();
        host.addChild(plate);
        plate.setClickable(false);
        plate.setRadius(10);
        plate.setBeforeRenderCallback(() -> {
            plate.setBounds(QR_PLATE, QR_PLATE);
            plate.setPosition(host.getWidth() - 16 - QR_PLATE, 132);
            plate.setColor(0xFFFFFF);
            plate.setAlpha(host.getAlpha() * (float) (.10 + .90 * qrReveal));
        });

        RoundedImageWidget qr = new RoundedImageWidget(() -> QR_TEXTURE, 0, 0, QR_PLATE - 8, QR_PLATE - 8);
        host.addChild(qr);
        qr.setClickable(false);
        qr.setRadius(6);
        qr.setLinearFilter(true);
        qr.setBeforeRenderCallback(() -> {
            // 淡入时同步做 .94 → 1.0 的轻微放大，二维码是从底板里"长"出来的。
            double scale = .94 + .06 * qrReveal;
            double size = (QR_PLATE - 8) * scale;
            qr.setBounds(size, size);
            qr.setPosition(host.getWidth() - 16 - QR_PLATE + (QR_PLATE - size) * .5, 132 + (QR_PLATE - size) * .5);
            qr.setAlpha(host.getAlpha() * (float) qrReveal);
        });

        // 左侧文本列必须在二维码底板之前收住，否则长链接会压到二维码底下。
        double textRight = 16 + QR_PLATE + 12;
        // 酷狗没有官方分享链接，这一行复制的就是那串短码本身，标题跟着改口才不会让人
        // 以为粘出去的是个打不开的链接。
        addCopyRow(host, 132, textRight,
                ListenTogetherService.ownedByKuGou() ? "邀请码" : "分享链接",
                ListenTogetherService::getShareLink,
                ListenTogetherService.ownedByKuGou() ? "复制邀请码" : "复制链接");
        addCopyRow(host, 178, textRight, "邀请码", ListenTogetherService::getInviteCode, "复制邀请码");

        LabelWidget membersLabel = new LabelWidget(() -> {
            int count = ListenTogetherService.getMembers().size();
            return count > MEMBER_ROWS ? "房间成员 · " + count + "（仅显示前 " + MEMBER_ROWS + " 位）"
                    : "房间成员 · " + count;
        }, FontManager.pf12bold);
        host.addChild(membersLabel);
        membersLabel.setClickable(false);
        membersLabel.setBeforeRenderCallback(() -> {
            membersLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 右界 = 「自动跳过 VIP」开关（126 定宽贴右缘 16）的左缘再让 12：
            // 长文案「房间成员 · 8（仅显示前 5 位）」窄弹窗下不再压进开关。
            membersLabel.setMaxWidth(Math.max(60, host.getWidth() - 16 - 126 - 12 - 16));
            membersLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            membersLabel.setPosition(16, 232);
        });

        addSkipToggle(host);

        for (int row = 0; row < MEMBER_ROWS; row++) addMemberRow(host, row);

        // 房里有非会员时才出现的一行说明。VIP 专享曲对他们只有 30 秒试听，
        // 这里把"为什么歌会自己跳掉"提前讲清楚，而不是等它跳了才在灵动岛解释。
        LabelWidget vipHint = new LabelWidget(this::vipHintText, FontManager.pf12);
        host.addChild(vipHint);
        vipHint.setClickable(false);
        vipHint.setBeforeRenderCallback(() -> {
            vipHint.setColor(WARN_COLOR);
            vipHint.setMaxWidth(Math.max(1, host.getWidth() - 32));
            vipHint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            vipHint.setPosition(16, 348);
            vipHint.setAlpha(ListenTogetherService.hasNonVipMember() ? host.getAlpha() : 0f);
        });

        LabelWidget empty = new LabelWidget("还没有人加入，把链接或邀请码发给好友吧。", FontManager.pf12);
        host.addChild(empty);
        empty.setClickable(false);
        empty.setBeforeRenderCallback(() -> {
            empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            empty.setPosition(16, 256);
            empty.setAlpha(ListenTogetherService.getMembers().isEmpty() ? host.getAlpha() : 0f);
        });

        addFooterButtons(host);
    }

    /**
     * 自动跳过开关，右对齐挂在「房间成员」那一行上。
     *
     * <p>刻意不占独立一行：房间页纵向已经排满，而这个开关平时不需要看，只在"歌怎么自己跳了"
     * 的时候要能立刻找到。</p>
     */
    private void addSkipToggle(final Panel host) {
        final double[] hover = new double[1];
        RoundedButtonWidget toggle = new RoundedButtonWidget(
                () -> ListenTogetherSettings.isSkipVipWhenRoomHasNonVip()
                        ? "自动跳过 VIP 专享 · 开" : "自动跳过 VIP 专享 · 关",
                FontManager.pf10bold);
        host.addChild(toggle);
        toggle.setGlassRim(GlassLook.Surface.PLAYER);
        toggle.setRadius(6);
        toggle.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            ListenTogetherSettings.setSkipVipWhenRoomHasNonVip(
                    !ListenTogetherSettings.isSkipVipWhenRoomHasNonVip());
            return true;
        });
        toggle.setBeforeRenderCallback(() -> {
            boolean on = ListenTogetherSettings.isSkipVipWhenRoomHasNonVip();
            hover[0] = Interpolations.interpolate(hover[0], toggle.isHovering() ? 1.0 : 0.0, .22f);
            toggle.setBounds(126, 20);
            toggle.setPosition(host.getWidth() - 16 - 126, 228);
            int base = on ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
            toggle.setColor(mix(base, on ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER),
                    on ? hover[0] * .14 : hover[0]));
            toggle.setTextColor(on ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            toggle.setFillAlpha(on ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
    }

    private String vipHintText() {
        List<String> names = ListenTogetherService.getNonVipMemberNames();
        if (names.isEmpty()) return "";
        String who = names.size() == 1 ? names.get(0) : names.get(0) + " 等 " + names.size() + " 人";
        return ListenTogetherSettings.isSkipVipWhenRoomHasNonVip()
                ? who + " 不是会员：VIP 专享曲只有 30 秒试听，将自动跳过以保持同步"
                : who + " 不是会员：VIP 专享曲他们只能听 30 秒，房间会在那时脱队";
    }

    private String shortRoom(String roomId) {
        if (roomId == null || roomId.isEmpty()) return "—";
        return roomId.length() <= 18 ? roomId : roomId.substring(0, 18) + "…";
    }

    private String roleDescription() {
        ListenTogetherService.Role role = ListenTogetherService.getRole();
        if (role == ListenTogetherService.Role.HOST) return "主机 · 你的播放进度正在同步给房间成员";
        if (role == ListenTogetherService.Role.GUEST) return "参与者 · 需要手动「同步一次」对齐主机";
        return "房间已就绪";
    }
    private void addCopyRow(final Panel host, final double y, final double reservedRight,
                            final String labelText, final Supplier<String> valueSupplier,
                            final String buttonText) {
        LabelWidget caption = new LabelWidget(labelText, FontManager.pf10bold);
        host.addChild(caption);
        caption.setClickable(false);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(16, y);
        });

        LabelWidget value = new LabelWidget(valueSupplier, FontManager.pf12);
        host.addChild(value);
        value.setClickable(false);
        value.setBeforeRenderCallback(() -> {
            value.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            value.setMaxWidth(Math.max(1, host.getWidth() - 16 - reservedRight - 92));
            value.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            value.setPosition(16, y + 14);
        });

        final double[] hover = new double[1];
        RoundedButtonWidget copy = new RoundedButtonWidget(
                () -> isCopiedHintVisible(labelText) ? "已复制" : buttonText, FontManager.pf12bold);
        host.addChild(copy);
        copy.setGlassRim(GlassLook.Surface.PLAYER);
        copy.setRadius(7);
        copy.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            String text = valueSupplier.get();
            if (text == null || text.isEmpty()) return true;
            KeyboardUtils.setClipboardString(text);
            copiedLabel = labelText;
            copiedAtMillis = System.currentTimeMillis();
            return true;
        });
        copy.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], copy.isHovering() ? 1.0 : 0.0, .22f);
            copy.setBounds(84, 26);
            copy.setPosition(host.getWidth() - reservedRight - 84, y + 4);
            boolean copied = isCopiedHintVisible(labelText);
            if (copied) {
                copy.setColor(mix(OK_COLOR, 0xFFFFFF, hover[0] * .12));
                copy.setTextColor(0xFFFFFF);
            } else {
                copy.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
                copy.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            }
            copy.setFillAlpha(copied ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
    }

    private boolean isCopiedHintVisible(String labelText) {
        return labelText.equals(copiedLabel)
                && System.currentTimeMillis() - copiedAtMillis < COPIED_HINT_MILLIS;
    }

    private void addMemberRow(final Panel host, final int row) {
        final double y = 252 + row * 24;
        final double[] reveal = memberReveal[row];

        final Monogram avatar = new Monogram();
        host.addChild(avatar);
        avatar.setClickable(false);
        avatar.setBeforeRenderCallback(() -> {
            List<ListenTogetherService.Member> members = ListenTogetherService.getMembers();
            boolean present = row < members.size();
            // 错峰淡入：上一行露出 35% 之后本行才开始，自上而下形成瀑布感。
            boolean gate = row == 0 || memberReveal[row - 1][0] > .35;
            reveal[0] = Interpolations.interpolate(reveal[0], present && gate ? 1.0 : 0.0, .18f);
            if (!present) {
                avatar.setAlpha(0f);
                return;
            }
            ListenTogetherService.Member member = members.get(row);
            avatar.setIdentity(member.userId, member.nickname);
            avatar.setBounds(18, 18);
            avatar.setPosition(16, y + (1.0 - reveal[0]) * 6.0);
            avatar.setAlpha(host.getAlpha() * (float) reveal[0]);
        });

        final LabelWidget name = new LabelWidget("", FontManager.pf12);
        host.addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            List<ListenTogetherService.Member> members = ListenTogetherService.getMembers();
            if (row >= members.size()) {
                name.setAlpha(0f);
                return;
            }
            ListenTogetherService.Member member = members.get(row);
            String nickname = member.nickname == null || member.nickname.trim().isEmpty()
                    ? "用户 " + member.userId : member.nickname;
            name.setLabel(nickname);
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            // 给右侧的「无 VIP」标记留位置，否则长昵称会压到标记底下。
            name.setMaxWidth(Math.max(1, host.getWidth() - 60 - 52));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(40, y + 5 + (1.0 - reveal[0]) * 6.0);
            name.setAlpha(host.getAlpha() * (float) reveal[0]);
        });

        // 「无 VIP」标记右对齐：和列表里其它行的右侧控件共用一条竖线，长短昵称都不会让它左右跳。
        final RoundedRectWidget chip = new RoundedRectWidget();
        host.addChild(chip);
        chip.setClickable(false);
        chip.setRadius(5);
        chip.setBeforeRenderCallback(() -> {
            List<ListenTogetherService.Member> members = ListenTogetherService.getMembers();
            boolean flag = row < members.size()
                    && ListenTogetherService.isMemberNonVip(members.get(row).userId);
            if (!flag) {
                chip.setAlpha(0f);
                return;
            }
            chip.setBounds(44, 16);
            chip.setPosition(host.getWidth() - 16 - 44, y + 2 + (1.0 - reveal[0]) * 6.0);
            chip.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), WARN_COLOR, .3));
            chip.setAlpha(host.getAlpha() * (float) reveal[0]);
        });

        final LabelWidget chipText = new LabelWidget("无 VIP", FontManager.pf10bold);
        chip.addChild(chipText);
        chipText.setClickable(false);
        chipText.setBeforeRenderCallback(() -> {
            chipText.setColor(0xFFFFFF);
            chipText.setPosition(chip.getWidth() * .5 - chipText.getWidth() * .5,
                    Math.max(1.0, (chip.getHeight() - chipText.getHeight()) * .5));
        });
    }

    private void addFooterButtons(final Panel host) {
        final double[] syncHover = new double[1];
        RoundedButtonWidget sync = new RoundedButtonWidget("同步一次", FontManager.pf12bold);
        host.addChild(sync);
        sync.setGlassRim(GlassLook.Surface.PLAYER);
        sync.setRadius(8);
        sync.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            ListenTogetherService.syncOnce();
            return true;
        });
        sync.setBeforeRenderCallback(() -> {
            boolean visible = ListenTogetherService.getRole() == ListenTogetherService.Role.GUEST;
            syncHover[0] = Interpolations.interpolate(syncHover[0], visible && sync.isHovering() ? 1.0 : 0.0, .22f);
            double total = Math.max(1, host.getWidth() - 32);
            double width = visible ? (total - 8) * .5 : total;
            sync.setBounds(width, 32);
            sync.setPosition(16, host.getHeight() - 62);
            sync.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), syncHover[0]));
            sync.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            sync.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            // 主机没有"同步"这个动作。用 alpha + clickable 而不是 setHidden：
            // 被隐藏的组件不再执行自己的回调，也就没机会把自己显示回来。
            sync.setAlpha(visible ? host.getAlpha() : 0f);
            sync.setClickable(visible);
        });

        final double[] leaveHover = new double[1];
        RoundedButtonWidget leave = new RoundedButtonWidget(
                () -> ListenTogetherService.getRole() == ListenTogetherService.Role.HOST
                        ? "结束房间" : "退出房间", FontManager.pf12bold);
        host.addChild(leave);
        leave.setGlassRim(GlassLook.Surface.PLAYER);
        leave.setRadius(8);
        leave.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            ListenTogetherService.leave();
            return true;
        });
        leave.setBeforeRenderCallback(() -> {
            boolean guest = ListenTogetherService.getRole() == ListenTogetherService.Role.GUEST;
            leaveHover[0] = Interpolations.interpolate(leaveHover[0], leave.isHovering() ? 1.0 : 0.0, .22f);
            double total = Math.max(1, host.getWidth() - 32);
            double width = guest ? (total - 8) * .5 : total;
            leave.setBounds(width, 32);
            leave.setPosition(guest ? 16 + (total - 8) * .5 + 8 : 16, host.getHeight() - 62);
            leave.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), ERROR_COLOR,
                    .3 + leaveHover[0] * .55));
            leave.setTextColor(0xFFFFFF);
            leave.setFillAlpha(leave.isHovering() ? 1.0f : GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
    }
    // ──────────────────────────── 加入房间页 ────────────────────────────

    private void showJoinPage() {
        // 从总览点进来的：翻页方向前进，不走翻页反向那条。
        beginPage();
        joinPage = true;
        // 两家的邀请形态不是一回事：网易云有官方分享链接（也能只填房间号#邀请人ID），
        // 酷狗没有链接 scheme，只有本机面板上那串 kugou: 开头的短码。
        boolean kugouRoom = ListenTogetherService.ownedByKuGou();
        joinHint = kugouRoom
                ? "粘贴好友面板上的邀请码（kugou: 开头那一串）"
                : "支持整条分享链接、房间号#邀请人ID，或只有房间号";
        joinHintColor = 0xAEB5C4;

        final Panel dialog = sheet(452, () -> 262.0);
        // 返回键的回弹：交给 NcmSheet#back 包一层，让翻页方向置成反向。
        addBackButton(dialog, back(this::showOverview));
        addDetailTitle(dialog, "加入房间",
                ListenTogetherService.backendLabel() + "房间的加入页 · 粘贴对方发来的邀请码");

        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setGlassRim(GlassLook.Surface.PLAYER);
        surface.setClickable(false);
        surface.setRadius(7);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, dialog.getWidth() - 32), 34);
            surface.setPosition(16, 86);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });

        final TextFieldWidget field = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(field);
        field.getTextField().setMaxStringLength(512);
        field.setEnabled(true).setFocused(true);
        field.setPlaceholder(kugouRoom
                ? "kugou:……"
                : "https://st.music.163.com/listen-together/share/?… 或 房间号#邀请人ID");
        field.drawUnderline(false);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, surface.getWidth() - 12), Math.max(1, surface.getHeight() - 8));
            field.setPosition(surface.getRelativeX() + 6, surface.getRelativeY() + 4);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            field.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        inviteField = field;

        final LabelWidget hint = new LabelWidget(() -> joinHint, FontManager.pf12);
        dialog.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(joinHintColor);
            hint.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(16, 132);
        });

        final double[] hover = new double[1];
        final RoundedButtonWidget confirm = new RoundedButtonWidget("加入房间", FontManager.pf12bold);
        dialog.addChild(confirm);
        confirm.setGlassRim(GlassLook.Surface.PLAYER);
        confirm.setRadius(8);
        confirm.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            String invite = field.getText() == null ? "" : field.getText().trim();
            if (invite.isEmpty()) {
                joinHint = "请先粘贴分享链接或邀请码";
                joinHintColor = ERROR_COLOR;
                return true;
            }
            ListenTogetherService.joinRoom(invite, 0L);
            back(this::showOverview).run();
            return true;
        });
        confirm.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], confirm.isHovering() ? 1.0 : 0.0, .22f);
            confirm.setBounds(Math.max(1, dialog.getWidth() - 32), 34);
            confirm.setPosition(16, dialog.getHeight() - 56);
            confirm.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .16));
            confirm.setTextColor(0xFFFFFF);
        });
    }

    // ──────────────────────────── 对话框骨架 ────────────────────────────
    //
    // 旧版的 createDialog() 已经删掉：现在遮罩、玻璃底板、高光、描边、高度补间都从 NcmSheet.sheet() 拿。
    // 这里只剩「添加按钮 / 卡片」两类。

    private void addTitle(final Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(16, 14);
        });
        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 右上角留给「服务器房间」那颗常驻按钮。
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 168));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(16, 39);
        });
    }

    private void addBackButton(final Panel dialog, final Runnable action) {
        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        dialog.addChild(back);
        back.setGlassRim(GlassLook.Surface.PLAYER);
        back.setRadius(7);
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        back.setBeforeRenderCallback(() -> {
            back.setBounds(28, 24);
            back.setPosition(16, 13);
            back.setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void addDetailTitle(final Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(56, 14);
        });
        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 72));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(56, 39);
        });
    }
    // ──────────────────────────── 二维码 ────────────────────────────

    /**
     * 分享链接变了才重新烘一张二维码。
     *
     * <p>由对话框的每帧回调调用，因此不需要单独的轮询线程；生成放后台，纹理提交回主线程。
     * 失败静默 —— 二维码只是便利入口，链接与邀请码照样能复制。</p>
     */
    private void ensureQrTexture() {
        if (!ListenTogetherService.isActive()) return;
        final String link = ListenTogetherService.getShareLink();
        if (link == null || link.isEmpty()) return;
        if (link.equals(bakedQrContent)) {
            qrReveal = Interpolations.interpolate(qrReveal, 1.0, .16f);
            return;
        }
        if (bakingQr) return;
        bakingQr = true;
        qrReveal = 0.0;
        MultiThreadingUtil.runAsync(() -> {
            try {
                final BufferedImage image = QRCodeGenerator.generateQRCode(link, 128, 128);
                if (image == null) throw new IllegalStateException("二维码图片为空");
                MultiThreadingUtil.runOnMainThread(() -> {
                    try {
                        TextureManager manager = TextureManager.getInstance();
                        if (manager.getTexture(QR_TEXTURE) != null) manager.deleteTexture(QR_TEXTURE);
                        manager.loadTexture(QR_TEXTURE, new DynamicTexture(image));
                        bakedQrContent = link;
                    } finally {
                        bakingQr = false;
                    }
                });
            } catch (Throwable throwable) {
                bakingQr = false;
            }
        });
    }

    // ──────────────────────────── 配色 ────────────────────────────
    //
    // {@code mix(int, int, double)} 现在从 {@link NcmSheet} 继承（public static），
    // 本来自己写的那一份删掉 —— 不删的话会因为权限更严而被编译器当成「覆盖」。

    // ──────────────────────────── 自绘控件 ────────────────────────────

    /** 房间在线指示：内圈常亮，外圈按正弦向外扩散并淡出。 */
    private static final class PulseDot extends AbstractWidget<PulseDot> {

        private double phase;

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            if (alpha <= .015f) return;
            // 与帧率无关：掉帧时走大步而不是变慢。
            phase = (phase + RenderSystem.getFrameDeltaTime() * .012) % 1.0;

            double centerX = this.getX() + this.getWidth() * .5;
            double centerY = this.getY() + this.getHeight() * .5;
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);

            double wave = Math.sin(phase * Math.PI * 2.0) * .5 + .5;
            double ring = 3.2 + wave * 4.6;
            this.roundedRect(centerX - ring, centerY - ring, ring * 2.0, ring * 2.0, ring,
                    reAlpha(accent, (float) ((1.0 - wave) * .42) * alpha));
            this.roundedRect(centerX - 3.0, centerY - 3.0, 6.0, 6.0, 3.0, reAlpha(accent, alpha));
        }
    }

    /**
     * 成员头像用字标圆点，<b>刻意不下载真头像</b>：每人一张贴图等于一次网络请求加一次 GL 上传，
     * 而这里只有 18px。底色按 userId 取模选，同一个人每次进房间颜色一致。
     */
    private static final class Monogram extends AbstractWidget<Monogram> {

        private static final int[] PALETTE = {0xE05B6A, 0xE0885B, 0x5BA0E0, 0x5BC9A6, 0x9A7BE0, 0xE0B25B};

        private long userId;
        private String nickname = "";

        void setIdentity(long userId, String nickname) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            if (alpha <= .015f) return;
            double size = Math.max(1.0, Math.min(this.getWidth(), this.getHeight()));
            double radius = size * .5;
            int color = PALETTE[(int) Math.abs(userId % PALETTE.length)];
            this.roundedRect(this.getX(), this.getY(), size, size, radius, reAlpha(color, alpha));

            String initial = nickname.isEmpty() ? "?" : nickname.substring(0, 1);
            double textWidth = FontManager.pf10bold.getStringWidthD(initial);
            FontManager.pf10bold.drawString(initial,
                    this.getX() + radius - textWidth * .5,
                    this.getY() + radius - FontManager.pf10bold.getHeight() * .5 + 1.0,
                    reAlpha(0xFFFFFF, alpha));
        }
    }
}