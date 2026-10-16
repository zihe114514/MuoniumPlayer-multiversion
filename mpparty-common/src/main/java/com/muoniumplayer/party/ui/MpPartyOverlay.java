package com.muoniumplayer.party.ui;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.ListenTogetherSettings;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.party.MpInvite;
import com.muoniumplayer.party.MpMember;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpPresence;
import com.muoniumplayer.party.MpPublicRoom;
import com.muoniumplayer.party.MpRoom;
import com.muoniumplayer.party.MpRoomPlaylists;
import com.muoniumplayer.party.MpTrack;
import com.muoniumplayer.party.MpVote;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 一起听（mpsync 服务端房间）的面板。
 *
 * <h2>版式</h2>
 * 标题栏（0~52）→ 页签条（58~88）→ 发丝分隔线 → 内容区（100 起，固定 306 高）→ 分隔线 + 状态行。
 * 内容高度<b>刻意固定</b>：切页签时弹窗不该跳，只有「投票 / 封禁」横幅升起来的时候才长高一截，
 * 让那一次高度变化本身成为提示。
 *
 * <h2>观感的三条规矩</h2>
 * <ol>
 *   <li><b>边界靠 1px 描边，不靠填充色差。</b> 卡片与行的底色只比页面底色亮一点点，轮廓由
 *       {@link Stroke} 画出来 —— 一层层灰块叠灰块是这套界面上一版最难看的地方。</li>
 *   <li><b>层级靠字号与字重，不靠颜色乱撞。</b> 主色只留给「当前」与「可点」两件事。</li>
 *   <li><b>凡是会动的都要过渡。</b> 页签指示器走弹簧，切页签是淡出→淡入而不是瞬切，
 *       列表滚动是插值位移而不是整行跳，悬停有 1.2% 的抬升。</li>
 * </ol>
 *
 * <h2>线程</h2>
 * {@link MpPartyClient} 的状态由网络线程推进，随时可能变，所以<b>控件不能按状态重建</b>：
 * 所有会变的文字都是 {@code Supplier}，所有位置都在 {@code setBeforeRenderCallback} 里算。
 * 控件只在用户切子页时重建一次。
 *
 * <h2>共享源码约束</h2>
 * 这个文件被四个版本工程同时编译，只能用它们逐字节相同的那部分框架
 * （{@code core.rendering.*}、{@code NCMPanel}、{@code FontManager}、{@code Music} 与纹理三件套）。
 */
public final class MpPartyOverlay extends NCMPanel {

    private static final double DIALOG_WIDTH = 608.0;
    /** 标题栏 + 页签条 + 分隔线之后就是内容区。 */
    private static final double CONTENT_TOP = 100.0;
    /** 内容区下面留给分隔线与状态行。 */
    private static final double CONTENT_BOTTOM = 34.0;
    /** 所有页签共用的内容高度。 */
    private static final double CONTENT_HEIGHT = 306.0;
    /** 内容左右留白。 */
    private static final double PAD = 22.0;

    private static final int QUEUE_ROWS = 5;
    private static final int MEMBER_ROWS = 5;
    private static final int PRESENCE_ROWS = 6;
    private static final int PUBLIC_ROWS = 4;
    private static final int INVITE_ROWS = 4;
    /** 选单页那几行：弹窗比主页矮，四行已经够把「Mu本地 + 导入 + 账号」三段各露出一条。 */
    private static final int PLAYLIST_ROWS = 4;
    private static final double PLAYLIST_STRIDE = 46.0;

    private static final double QUEUE_STRIDE = 48.0;
    private static final double MEMBER_STRIDE = 52.0;
    private static final double PRESENCE_STRIDE = 46.0;
    private static final double PUBLIC_STRIDE = 48.0;
    private static final double INVITE_STRIDE = 60.0;

    private static final int OK_COLOR = 0x53C68C;
    private static final int WARN_COLOR = 0xD5A44A;
    private static final int ERROR_COLOR = 0xF1767D;
    private static final int VIP_COLOR = 0xE0B152;

    /** 头像底色：按名字散列取一个，让成员列表有可辨识的色块而不是一片灰。 */
    private static final int[] AVATAR_COLORS = {
            0x5B8FF9, 0x61DDAA, 0xF6BD16, 0xE8684A,
            0x7262FD, 0x78D3F8, 0x9661BC, 0xF6903D,
    };

    private static final long COPIED_HINT_MILLIS = 1400L;

    /**
     * 图标方格的四档边长（逻辑像素），对应 {@link FontManager#partyIcon24} 那一组四个字号。
     *
     * <p>只有这四档：字形是按字号栅格化的，绘制时再缩放会重采样发虚，所以「差一点点」的尺寸
     * 要么就近取一档，要么在 FontManager 里再加一档，不要在这里乘系数。</p>
     */
    private static final double ICON_S = 12.0;
    private static final double ICON_M = 16.0;
    private static final double ICON_L = 20.0;
    private static final double ICON_XL = 28.0;

    /** 图标与紧跟其后的文字之间的间距。 */
    private static final double ICON_GAP = 5.0;

    /**
     * 标题栏右上角那一排（两颗贡献胶囊 + 「‹ 一起听」）的尺寸口径。
     *
     * <p>胶囊高度从 26 收到 20 是给第二颗让路：这一排压在副标题（{@code y = 36}）上面，26 高的
     * 时候它的下边缘已经探进副标题的字身里，只能靠把副标题裁短来躲。收成 20 之后整排落在 35
     * 以上，副标题就能用满整行 —— 两颗胶囊要的是横向空间，不该拿副标题的信息量去换。</p>
     */
    private static final double TOGGLE_TOP = 15.0;
    private static final double TOGGLE_HEIGHT = 20.0;
    /** 一颗胶囊的舒展宽（放得下「贡献会员解析 · 开」）。 */
    private static final double PILL_STRETCH = 154.0;
    /** 收缩到这一档以下，就宁可先让房名角标 / 返回按钮的文字让路，也别继续削胶囊。 */
    private static final double PILL_KEEP = 104.0;
    /** 再窄也不许低于这一档：低了图标和滑块就要在胶囊里打架。 */
    private static final double PILL_FLOOR = 56.0;
    private static final double PILL_GAP = 10.0;
    /** 右侧滑块那一段占掉的文字禁区：槽 22 宽贴着右端留 9，再留 7 的呼吸位。 */
    private static final double PILL_RAIL_ZONE = 38.0;
    /** 「‹ 一起听」那颗粒：带字的宽 / 只剩箭头的宽。 */
    private static final double BACK_LABELLED = 74.0;
    private static final double BACK_ICON = 30.0;
    /** 标题那一段文字与右上角这一排之间的呼吸位。 */
    private static final double HEAD_GAP = 8.0;
    private static final int TOGGLE_COUNT = 2;

    /** 能力标记里文字左右各留的边距（一颗标记的宽 = 字宽 + 两枚边距）。 */
    private static final double MARK_PAD = 7.0;

    /**
     * 列表行左右各预留的空隙，悬停放大就往这块空隙里长。
     *
     * <p>原来悬停是按<b>百分比</b>放大（1.2%）的，行宽 560 就意味着左右各溢出 3.4 像素，
     * 而列表的裁剪矩形正好贴着行宽 —— 于是一悬停，圆角和那圈主色描边就被削平了，看着像
     * 「框放大之后显示不全」。现在行本身缩进这么多，放大量也按像素折算，长到头刚好填满预留，
     * 永远不越过裁剪边界。顺带一个好处：不同宽度的面板上，悬停的位移量看起来是一样的，
     * 百分比缩放会让宽面板上抬得夸张、窄面板上几乎看不见。</p>
     */
    private static final double ROW_INSET = 3.0;

    /** 按钮配色。 */
    private static final int STYLE_PLAIN = 0;
    private static final int STYLE_PRIMARY = 1;
    private static final int STYLE_DANGER = 2;
    private static final int STYLE_ROW = 3;
    private static final int STYLE_ROW_DANGER = 4;
    private static final int STYLE_ROW_PRIMARY = 5;
    private static final int STYLE_GHOST = 6;

    private enum Page { OVERVIEW, CREATE, JOIN, INVITE, LINK, PLAYLIST, DIRECT }

    private enum Tab { ROOM, QUEUE, MEMBERS, ONLINE, DISCOVER, INVITES }

    private boolean closing;
    private Page page = Page.OVERVIEW;
    private Tab tab = Tab.ROOM;
    /** 已经点了、但还在等旧页淡出的目标页签。 */
    private Tab pendingTab;

    /** 弹窗入场进度，同时驱动上抬位移与整体淡入。 */
    private double presentation;
    /** 弹窗高度的逐帧值。 */
    private double dialogHeight;
    /** 横幅（投票 / 封禁）占的高度，插值的 —— 升起来那一下本身就是提示。 */
    private double bannerInset;
    /** 内容区的显隐进度：切页签时先落到 0 再换页，全程只有一页可见，不会互相压住。 */
    private double paneFade = 1.0;

    /** 当前页的输入框（没有输入框的页为 null）。 */
    private TextFieldWidget field;
    private String hint = "";
    private int hintColor = 0xAEB5C4;

    private long copiedAt;

    /**
     * 这帧的滚轮已经被列表吃掉了。
     *
     * <p>{@code AbstractWidget.renderWidget} 在<b>每一层</b>末尾都会派发一次滚轮，同一个 notch
     * 会顺着 overlay → dialog → pane → list 连着到四次。这个标志在最外层 {@code onRender} 里清零
     * （也就是在任何派发之前），让列表只吃第一次。</p>
     */
    private boolean wheelConsumed;

    /** 进度条正在被拖：拖动过程不下发 seek，松手那一下才发（否则全房一直重新对齐）。 */
    private boolean barDragging;
    private double barOverride = -1.0;

    /** {@code MpTrack} → {@code Music} 的界面级缓存：封面位置要靠 Music 算，不能每帧新建一个。 */
    private final Map<String, Music> musicCache = new HashMap<String, Music>();
    /** 已经发过下载请求的封面，避免每帧重复发起。 */
    private final Set<String> coverAsked = new HashSet<String>();

    @Override
    public void onInit() {
        showOverview();
    }

    /**
     * 直接打开到直连房间页（从「一起听」二级菜单的「直连房间」卡片跳进来）。
     * 只想看房间就点左上角的「‹ 一起听」回去——入口在跳转后仍然在。
     */
    public void openDirect() {
        directCameFromMenu = true;
        showDirectPage();
    }

    /** 直连页的返回键行为：true = 上一跳来自「一起听」二级菜单。 */
    private boolean directCameFromMenu;

    /** 从直连页回到「一起听」二级菜单（同 NCMScreen 里那两颗返回键的跳法）。 */
    private void returnToMenu() {
        dispose();
        NCMScreen.getInstance().openListenTogether();
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        wheelConsumed = false;
        super.onRender(mouseX, mouseY);
    }

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
    }

    public void handleEscape() {
        if (page == Page.OVERVIEW) {
            dispose();
        } else {
            showOverview();
        }
    }

    @Override
    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        // ESC 交回给 NCMScreen 的模态栈路由，别把退出键吃掉。
        if (keyCode == Keyboard.KEY_ESCAPE) return false;
        // 交给「当前有焦点」的那个输入框，而不是全局 field：
        // 直连页同时有两个输入框，inputField 构造时 field 总是被后建的那个覆盖——
        // 点了端口框后 field 仍指着加入框，端口框永远收不到按键（B3）。
        // 深度遍历整棵控件树，isFocused 的输入框（TextField 点击聚焦）才收按键。
        TextFieldWidget target = focusedField(this);
        if (target != null) {
            target.onKeyTyped(typedChar, keyCode);
            return true;
        }
        return false;
    }

    /** 深度优先找本页里有键盘焦点的输入框；没有返回 null。 */
    private TextFieldWidget focusedField(AbstractWidget<?> host) {
        for (AbstractWidget<?> child : host.getChildren()) {
            if (child instanceof TextFieldWidget) {
                TextFieldWidget tf = (TextFieldWidget) child;
                if (tf.isFocused()) return tf;
            }
            TextFieldWidget nested = focusedField(child);
            if (nested != null) return nested;
        }
        return null;
    }

    // ──────────────────────────── 总览（页签壳） ────────────────────────────

    private void showOverview() {
        page = Page.OVERVIEW;
        field = null;
        pendingTab = null;
        paneFade = 1.0;
        // 回过大厅之后，直连页的返回键就该回大厅了；「来自二级菜单」只在 openDirect
        // 落地的第一屏成立。
        directCameFromMenu = false;
        getChildren().clear();

        // 0 页签条 / 1 没装服务端 / 2 房间 / 3 歌单 / 4 成员 / 5 在线 / 6 发现 / 7 邀请
        final Panel[] parts = new Panel[8];
        final Panel[] roomBoxes = new Panel[2];

        final Panel dialog = createDialog(DIALOG_WIDTH, this::targetOverviewHeight, () -> {
            boolean present = MpPartyClient.serverPresent();
            boolean inRoom = MpPartyClient.inRoom();
            // 面板开着就算「正在看在线态」：大厅统计、邀请角标、在线页、发现页全靠这一句续命。
            MpPartyClient.touchPresence();

            if (!present) requestTab(Tab.ROOM);
            if (!inRoom && (target() == Tab.QUEUE || target() == Tab.MEMBERS)) requestTab(Tab.ROOM);
            advanceTabFade();
            bannerInset = Interpolations.interpolate(bannerInset, targetBannerInset(), .2f);

            parts[0].setHidden(!present);
            parts[1].setHidden(present);
            parts[2].setHidden(!present || tab != Tab.ROOM);
            parts[3].setHidden(!present || tab != Tab.QUEUE);
            parts[4].setHidden(!present || tab != Tab.MEMBERS);
            parts[5].setHidden(!present || tab != Tab.ONLINE);
            parts[6].setHidden(!present || tab != Tab.DISCOVER);
            parts[7].setHidden(!present || tab != Tab.INVITES);
            roomBoxes[0].setHidden(inRoom);
            roomBoxes[1].setHidden(!inRoom);

            if (!inRoom) {
                barDragging = false;
                barOverride = -1.0;
            }
        });

        buildHeader(dialog);
        parts[0] = buildTabStrip(dialog);
        divider(dialog, () -> 92.0);

        parts[1] = section(dialog);
        buildNoServer(parts[1]);

        parts[2] = pane(dialog);
        roomBoxes[0] = box(parts[2]);
        buildLobby(roomBoxes[0]);
        roomBoxes[1] = box(parts[2]);
        buildRoom(roomBoxes[1]);

        parts[3] = pane(dialog);
        buildQueuePane(parts[3]);
        parts[4] = pane(dialog);
        buildMembersPane(parts[4]);
        parts[5] = pane(dialog);
        buildOnlinePane(parts[5]);
        parts[6] = pane(dialog);
        buildDiscoverPane(parts[6]);
        parts[7] = pane(dialog);
        buildInvitesPane(parts[7]);

        divider(dialog, () -> dialog.getHeight() - CONTENT_BOTTOM);
        addStatusLine(dialog);
    }

    /** 用户想去的那一页：指示器立刻跟它走，内容等淡出结束再换 —— 指示器领先一点，观感更利落。 */
    private Tab target() {
        return pendingTab != null ? pendingTab : tab;
    }

    private void requestTab(Tab next) {
        if (next == tab) {
            pendingTab = null;
        } else if (next != pendingTab) {
            pendingTab = next;
        }
    }

    private void advanceTabFade() {
        if (pendingTab != null) {
            paneFade = Interpolations.interpolate(paneFade, 0.0, .75f);
            if (paneFade < .06) {
                tab = pendingTab;
                pendingTab = null;
            }
        } else {
            paneFade = Interpolations.interpolate(paneFade, 1.0, .55f);
        }
    }

    private double targetOverviewHeight() {
        if (!MpPartyClient.serverPresent()) return 288.0;
        return CONTENT_TOP + CONTENT_HEIGHT + CONTENT_BOTTOM + bannerInset;
    }

    /** 横幅只出现在「房间」页：封禁通知优先于投票卡，两者不会同时有意义。 */
    private double targetBannerInset() {
        if (target() != Tab.ROOM || !MpPartyClient.serverPresent()) return 0.0;
        if (MpPartyClient.isBanned()) return 62.0;
        return MpPartyClient.inRoom() && MpPartyClient.openVote() != null ? 68.0 : 0.0;
    }

    // ──────────────────────────── 标题栏 ────────────────────────────

    private void buildHeader(final Panel dialog) {
        LabelWidget title = label(dialog, "一起听", FontManager.pf20bold);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(PAD, 15));

        LabelWidget kicker = label(dialog, () -> {
            // 来源标签：用户要能分清面板上显示的是哪个房间（S6）。
            // 直连活跃时这里的一切都来自直连引擎，不是 MC 服务器。
            if (com.muoniumplayer.party.direct.MpDirect.isActive()) return "直连房间";
            MpRoom room = MpPartyClient.room();
            if (room == null) return "服务器房间";
            return room.kindLabel();
        }, FontManager.pf12bold);
        // 这一排的位置要按「标题 + 角标」的右边界让位，所以角标本身也得先建出来再排。
        final HeaderBar bar = new HeaderBar(dialog, title, kicker);
        final double[] kickerFade = new double[1];
        kicker.setBeforeRenderCallback(() -> {
            bar.layout();
            kicker.setColor(MpPartyClient.inRoom() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 挤不下时第一个让位的就是它：房间页顶上本来就写着房间名，这一排却是唯一的入口。
            kickerFade[0] = Interpolations.interpolate(kickerFade[0], bar.kickerFits ? 1.0 : 0.0, .3f);
            kicker.setAlpha((float) kickerFade[0]);
            kicker.setPosition(PAD + title.getWidth() + 10, 21);
        });

        LabelWidget subtitle = label(dialog, this::headerSubtitle, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 两颗胶囊收在副标题上面那一行（见 TOGGLE_HEIGHT 的说明），这里只用避开「‹ 一起听」
            // 那一列 —— 换成两颗胶囊之前它是按「整排控件」让位的，所以副标题一直短了一截。
            bar.layout();
            subtitle.setMaxWidth(Math.max(1, bar.bandRight - PAD - 6));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(PAD, 36);
        });

        // 双向入口：这一层是从「一起听」面板点进来的，右上角给一条回去的路。
        // 它和两颗胶囊共用同一条带，所以宽与位置都听 HeaderBar 的：挤不动时先丢掉「一起听」三字，
        // 「‹」本身已经说明这是回去。
        btn(dialog, () -> PartyIcons.CHEVRON_LEFT, () -> {
            bar.layout();
            return bar.backLabelled ? "一起听" : "";
        }, null, () -> {
            dispose();
            NCMScreen.getInstance().openListenTogether();
        }, STYLE_GHOST, b -> {
            bar.layout();
            b.setBounds(bar.backWidth, TOGGLE_HEIGHT);
            b.setPosition(Math.max(1, dialog.getWidth() - PAD - bar.backWidth), TOGGLE_TOP);
        });

        addContributeToggles(dialog, bar);
    }

    private String headerSubtitle() {
        // 来源前缀：与标题旁角标、房间页色带同一套口径——用户在任何一处都能读出
        // 当前会话是哪条链路的。
        String source = com.muoniumplayer.party.direct.MpDirect.isActive() ? "【直连房间】"
                : MpPartyClient.inRoom() ? "【服务器房间】" : "";
        if (!MpPartyClient.serverPresent() && !com.muoniumplayer.party.direct.MpDirect.isActive()) {
            return source + "同一台服务器里的玩家共享一条播放进度 · 不限播放源";
        }
        StringBuilder sb = new StringBuilder(source);
        sb.append("在线 ").append(Math.max(0, MpPartyClient.onlineCount())).append(" 人");
        if (!MpPartyClient.v2()) {
            sb.append(" · 服务端是旧版协议 v").append(MpPartyClient.serverProtocol())
                    .append("，在线态与公开房间不可用");
            return sb.toString();
        }
        sb.append("  ·  会员解析 ").append(MpPartyClient.serverResolve() ? "服务端已配置" : "靠玩家贡献");
        if (MpPartyClient.channelEnabled()) {
            sb.append("  ·  频道「").append(MpPartyClient.channelName()).append("」");
        }
        return sb.toString();
    }

    /**
     * 标题栏右上角的两颗贡献胶囊：本家会员解析、LX 自定义源解歌。
     *
     * <p>放在标题栏而不是某一页里：这是账号级别的授权，跟当前在哪个房间无关，而且要让人随时
     * 一眼看见自己有没有在替别人解歌。</p>
     *
     * <p>两颗各开各的，因为交出去的东西不一样：会员那颗交出去的是「我这个平台的会员身份能解出来
     * 的流」，自定义源那颗交出去的是「我那个脚本能解出来的流」。后者不动用任何账号会员权益，
     * 但第三方接口的调用量会记到这台机器头上 —— 所以它必须是一扇单独关得上的门，而不是跟着前者走。</p>
     */
    private void addContributeToggles(final Panel dialog, final HeaderBar bar) {
        addContributeToggle(dialog, bar, 0,
                ListenTogetherSettings::isPartyContributeVip,
                ListenTogetherSettings::setPartyContributeVip,
                PartyIcons.VIP, "贡献会员解析", "会员解析", VIP_COLOR);
        // 放大镜：自定义源干的事就是「去别处找一条能播的流」。不选链条 —— LINK 在点歌页里已经
        // 表示「链接点歌」，同一套图标里一枚字形只讲一件事。语义再往下压就只有自绘新图标，那不在这一版里做。
        addContributeToggle(dialog, bar, 1,
                ListenTogetherSettings::isPartyContributeLx,
                ListenTogetherSettings::setPartyContributeLx,
                PartyIcons.SEARCH, "贡献自定义源", "自定义源", OK_COLOR);
    }

    /**
     * 一颗贡献胶囊。{@code slot} 是它在这一排里的位置（0 = 靠返回按钮那颗），宽度与左边距都由
     * {@link HeaderBar} 按当帧的弹窗宽度算出来。
     *
     * <p>绑定的那一头只给 getter / setter：翻完立刻重报档案这件事留在这一层做一次，
     * 免得多一颗胶囊就多一处「忘了跟一句 {@code reportProfile()}」的余地 —— 房友要马上在成员列表里
     * 看到这颗徽标变化，不该等下一发五秒心跳。</p>
     *
     * <p>{@code glyph} 给 null 就是没有图标的一颗（纯文案 + 滑块），左侧缩进会跟着收。</p>
     */
    private void addContributeToggle(final Panel dialog, final HeaderBar bar, final int slot,
                                     final BooleanSupplier enabled, final BooleanWrite write,
                                     final String glyph, final String fullText,
                                     final String shortText, final int accent) {
        final double[] hover = new double[1];
        final double[] on = new double[1];
        // 图标要不要占位、文案从哪儿起笔：两颗都按同一套口径算，别写两份数字。
        final double textLeft = glyph == null ? 9.0 : 9.0 + ICON_S + ICON_GAP;
        // 这一格当帧还剩多少文字位。胶囊每帧写一次，文案与 maxWidth 两处共用同一个数，
        // 免得「挑哪条文案」和「裁到哪」用了两套口径。
        final double[] room = new double[1];
        final double centerY = TOGGLE_HEIGHT * .5;

        final RoundedRectWidget pill = new RoundedRectWidget();
        dialog.addChild(pill);
        pill.setRadius(centerY).setShouldOverrideMouseCursor(true);
        pill.setGlassRim(GlassLook.Surface.PLAYER);
        pill.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            write.set(!enabled.getAsBoolean());
            MpPartyClient.reportProfile();
            return true;
        });
        pill.setBeforeRenderCallback(() -> {
            bar.layout();
            boolean opened = enabled.getAsBoolean();
            hover[0] = Interpolations.interpolate(hover[0], pill.isHovering() ? 1.0 : 0.0, .25f);
            on[0] = Interpolations.interpolate(on[0], opened ? 1.0 : 0.0, .3f);
            pill.setBounds(bar.pillWidth, TOGGLE_HEIGHT);
            pill.setPosition(Math.max(1, bar.pillX[slot]), TOGGLE_TOP);
            pill.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND), accent,
                    on[0] * (.62 + hover[0] * .2) + (1.0 - on[0]) * hover[0] * .1));
            room[0] = bar.pillWidth - textLeft - PILL_RAIL_ZONE;
            pill.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER,
                    (float) (.46 + on[0] * .38 + hover[0] * .16)));
        });
        stroke(pill, centerY, () -> mix(NCMScreen.getColor(NCMScreen.ColorType.BORDER), accent, on[0]),
                () -> .45 + on[0] * .35);

        // 版式：图标 → 文案 → 最右边的滑块。原来滑块在最左、文案紧跟着它，右边空出一大片，
        // 看着像「文字没居中」；把滑块推到右端，这颗胶囊才读得出是个开关。
        if (glyph != null) {
            icon(pill, glyph, ICON_S, () -> 9.0 + ICON_S * .5, () -> centerY,
                    () -> mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), accent,
                            .35 + Math.max(on[0], hover[0] * .5) * .65));
        }

        // 滑块下面垫一条槽：没有槽的话那颗圆点挪几个像素几乎看不出来。槽与滑块都是胶囊的直接
        // 子组件，不是父子 —— 子组件的 alpha 会乘上父级的，滑块挂在半透明的槽里会一起变灰。
        final RoundedRectWidget rail = new RoundedRectWidget();
        pill.addChild(rail);
        rail.setClickable(false).setRadius(6).setBeforeRenderCallback(() -> {
            rail.setBounds(22, 12);
            rail.setPosition(Math.max(1, pill.getWidth() - 31.0), (TOGGLE_HEIGHT - 12.0) * .5);
            rail.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), accent,
                    on[0] * .6));
            rail.setAlpha(.6f);
        });

        // 滑块的位置本身就说明了开与关，比只换颜色好认 —— 也正因为如此，窄到放不下文案时
        // 文案可以先走，这颗胶囊仍然读得出是个开关。
        final RoundedRectWidget knob = new RoundedRectWidget();
        pill.addChild(knob);
        knob.setClickable(false).setRadius(5).setBeforeRenderCallback(() -> {
            knob.setBounds(10, 10);
            knob.setPosition(Math.max(1, pill.getWidth() - 30.0) + on[0] * 10.0,
                    (TOGGLE_HEIGHT - 10.0) * .5);
            knob.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), 0xFFFFFF, on[0]));
        });

        LabelWidget caption = label(pill, () -> fitToggleCaption(room[0], fullText, shortText,
                enabled.getAsBoolean()), FontManager.pf10bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), 0xFFFFFF,
                    Math.max(on[0], hover[0] * .5)));
            caption.setMaxWidth(Math.max(1, room[0]));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(textLeft, inkTop(caption, centerY));
        });
    }

    /**
     * 按当帧剩下的文字位，从文案阶梯上挑一条写得下的。
     *
     * <p>胶囊是这一排里该先收缩的那一颗（见 {@link HeaderBar}），而「· 开 / · 关」本来就只是滑块
     * 位置的文字复述，所以它第一个被扔：写全 → 去掉「贡献」→ 只留名字 → 只留开关字。</p>
     */
    private static String fitToggleCaption(double room, String fullText, String shortText,
                                           boolean opened) {
        if (room <= 1.0) return "";
        CFontRenderer font = FontManager.pf10bold;
        String tail = opened ? " · 开" : " · 关";
        if (font.getStringWidthD(fullText + tail) <= room) return fullText + tail;
        if (font.getStringWidthD(shortText + tail) <= room) return shortText + tail;
        if (font.getStringWidthD(shortText) <= room) return shortText;
        String state = opened ? "开" : "关";
        return font.getStringWidthD(state) <= room ? state : "";
    }

    /**
     * 一个布尔设置项的写入口。
     *
     * <p>{@code java.util.function} 里有 {@link BooleanSupplier}，却没有对偶的那个 —— 不想为了凑
     * 参数退到 {@code Consumer<Boolean>}（每次点击都白装一个包），就地一个窄接口。</p>
     */
    private interface BooleanWrite {
        void set(boolean value);
    }

    /**
     * 标题栏右上角那一排的当帧排布：两颗贡献胶囊 + 「‹ 一起听」挤在同一条带上。
     *
     * <p>弹窗宽度是按视口收的（{@code createDialog} 里那句 {@code min(608, 视口宽 - 24)}），GUI
     * 缩放一大就装不下「两颗舒展宽的胶囊 + 带字的返回按钮 + 房名角标」。所以按信息量从小到大
     * 逐级让位：胶囊先收缩（文案有阶梯，滑块位置本身也说得出开关）→ 房名角标淡掉（房间页顶上
     * 本来就写着房间名）→ 返回按钮收成纯箭头。每一级都重新问一遍「这下两颗胶囊能用舒展宽吗」，
     * 于是拉窗口时是滑进下一档，而不是在两个档位之间来回抖。</p>
     *
     * <p>三条硬规矩：两颗胶囊<b>等宽</b>、彼此留 {@code PILL_GAP}、整排右边界锁在
     * {@code 弹窗宽 - PAD}，位置一律从右往左推 —— 于是它们既不会叠在一起，也不会越过右边界。</p>
     *
     * <p>「放不下就换行」在这一排里做不成：标题栏统共 52 高，两行 20 的胶囊加上呼吸位就顶到
     * 56 起的页签条，副标题也还压在下面那一行里。所以这里走的是收缩，代价是窄到约 280 逻辑像素
     * 以下时最左那颗会开始往标题那一段里挤。</p>
     *
     * <p>{@link #layout()} 只是当帧宽度的函数，用到这一排的控件各自调一次，不依赖「谁先渲染」。</p>
     */
    private static final class HeaderBar {

        private final Panel dialog;
        private final LabelWidget title;
        private final LabelWidget kicker;

        /** 第 {@code i} 颗胶囊的左边界 x：0 = 靠近返回按钮那颗。 */
        private final double[] pillX = new double[TOGGLE_COUNT];
        private double pillWidth = PILL_STRETCH;
        private double backWidth = BACK_LABELLED;
        private boolean backLabelled = true;
        private boolean kickerFits = true;
        /** 这一排最右边那颗粒的右边界：副标题按它让位，免得文字钻进返回按钮底下。 */
        private double bandRight = 0.0;

        private HeaderBar(Panel dialog, LabelWidget title, LabelWidget kicker) {
            this.dialog = dialog;
            this.title = title;
            this.kicker = kicker;
        }

        /** 三档：留角标带字按钮 → 收角标 → 再把返回按钮收成箭头。最后一档无论如何都要落定。 */
        private void layout() {
            double right = dialog.getWidth() - PAD;
            double titleEdge = PAD + title.getWidth() + HEAD_GAP;
            double kickerEdge = titleEdge + kicker.getWidth() + HEAD_GAP;
            for (int tier = 0; tier <= 2; tier++) {
                kickerFits = tier == 0;
                backLabelled = tier < 2;
                backWidth = backLabelled ? BACK_LABELLED : BACK_ICON;
                double left = kickerFits ? kickerEdge : titleEdge;
                double per = (right - backWidth - PILL_GAP - left
                        - PILL_GAP * (TOGGLE_COUNT - 1)) / TOGGLE_COUNT;
                if (tier == 2) {
                    // 挤到这份上不再让位：宁可细到贴住标题那一段，也不让胶囊削到图标打架。
                    pillWidth = Math.min(PILL_STRETCH, Math.max(PILL_FLOOR, per));
                    break;
                }
                if (per >= PILL_KEEP) {
                    pillWidth = Math.min(PILL_STRETCH, per);
                    break;
                }
            }
            bandRight = right - backWidth - PILL_GAP;
            double x = bandRight;
            for (int i = 0; i < TOGGLE_COUNT; i++) {
                x -= pillWidth;
                pillX[i] = x;
                x -= PILL_GAP;
            }
        }
    }

    // ──────────────────────────── 页签条 ────────────────────────────

    /**
     * 页签条。选中态是一颗<b>弹簧滑过去</b>的胶囊，而不是六个各自换色的按钮 ——
     * 位移本身告诉眼睛「从哪来、到哪去」，这是整块面板里最值得花动画的地方。
     */
    private Panel buildTabStrip(final Panel dialog) {
        final Tab[] order = {Tab.ROOM, Tab.QUEUE, Tab.MEMBERS, Tab.ONLINE, Tab.DISCOVER, Tab.INVITES};
        final int count = order.length;

        final Panel strip = new Panel();
        dialog.addChild(strip);
        strip.setBeforeRenderCallback(() -> {
            strip.setBounds(Math.max(1, dialog.getWidth() - PAD * 2), 30);
            strip.setPosition(PAD, 56);
        });

        // 阻尼略低于临界值：滑到位时有一丝回弹，停得干净又不呆板。
        final SpringAnimation slideX = new SpringAnimation(new SpringParams(.8, 20.0, 220.0, false));
        final RoundedRectWidget indicator = new RoundedRectWidget();
        strip.addChild(indicator);
        indicator.setClickable(false).setRadius(9);
        indicator.setGlassRim(GlassLook.Surface.PLAYER);
        indicator.setBeforeRenderCallback(() -> {
            double slot = slotWidth(strip, count);
            int index = indexOf(order, target());
            slideX.setTargetPosition((slot + 4.0) * index);
            slideX.update(RenderSystem.getFrameDeltaTime() * .0125);
            double x = Math.max(0, Math.min((slot + 4.0) * (count - 1), slideX.getCurrentPosition()));
            indicator.setBounds(slot, 30);
            indicator.setPosition(x, 0);
            indicator.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ACCENT), .16));
            indicator.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .38f));
        });
        stroke(indicator, 9, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> .5);

        for (int index = 0; index < count; index++) {
            buildTab(strip, order[index], index, count);
        }
        return strip;
    }

    private static double slotWidth(final Panel strip, final int count) {
        return (Math.max(1, strip.getWidth()) - 4.0 * (count - 1)) / count;
    }

    private static int indexOf(final Tab[] order, final Tab wanted) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == wanted) return i;
        }
        return 0;
    }

    private void buildTab(final Panel strip, final Tab self, final int slot, final int count) {
        final Supplier<String> text;
        final BooleanSupplier usable;
        final Supplier<String> glyph;
        switch (self) {
            case QUEUE:
                text = () -> {
                    int size = MpPartyClient.queue().size();
                    return size > 0 ? "歌单 " + size : "歌单";
                };
                glyph = () -> PartyIcons.QUEUE_MUSIC;
                usable = MpPartyClient::inRoom;
                break;
            case MEMBERS:
                text = () -> {
                    int size = MpPartyClient.members().size();
                    return size > 0 ? "成员 " + size : "成员";
                };
                glyph = () -> PartyIcons.GROUP;
                usable = MpPartyClient::inRoom;
                break;
            case ONLINE:
                text = () -> "在线";
                glyph = () -> PartyIcons.PERSON;
                usable = MpPartyClient::v2;
                break;
            case DISCOVER:
                text = () -> "发现";
                glyph = () -> PartyIcons.EXPLORE;
                usable = MpPartyClient::v2;
                break;
            case INVITES:
                text = () -> {
                    int size = MpPartyClient.pendingInvites().size();
                    return size > 0 ? "邀请 " + size : "邀请";
                };
                // 有待处理邀请时换成带角标的信封：页签上的数字之外再给一次视觉提示。
                glyph = () -> MpPartyClient.pendingInvites().isEmpty()
                        ? PartyIcons.MAIL : PartyIcons.MAIL_UNREAD;
                usable = MpPartyClient::v2;
                break;
            default:
                text = () -> "房间";
                glyph = () -> PartyIcons.RADIO;
                usable = null;
                break;
        }

        final double[] hover = new double[1];
        // 页签本身是一块透明的可点区域：形状交给底下那颗滑动胶囊，这里只管文字与命中。
        final Panel hit = new Panel();
        strip.addChild(hit);
        hit.setShouldOverrideMouseCursor(true);
        hit.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            // 不可用时也吃掉这次点击：否则会穿到底下的遮罩，把整个弹窗关掉。
            if (usable != null && !usable.getAsBoolean()) return true;
            requestTab(self);
            return true;
        });
        hit.setBeforeRenderCallback(() -> {
            double w = slotWidth(strip, count);
            hit.setBounds(w, 30);
            hit.setPosition((w + 4.0) * slot, 0);
            boolean can = usable == null || usable.getAsBoolean();
            hover[0] = Interpolations.interpolate(hover[0], can && hit.isHovering() ? 1.0 : 0.0, .25f);
            hit.setClickable(can);
            hit.setAlpha(can ? 1f : .3f);
        });

        // 图标 + 文字整组居中：各自居中会让文字偏右、图标偏左，中间裂开一道缝。
        final double[] place = new double[2];
        final Panel arrange = new Panel();
        hit.addChild(arrange);
        arrange.setClickable(false).setBeforeRenderCallback(() -> {
            arrange.setBounds(1, 1);
            arrange.setPosition(0, 0);
            double textWidth = FontManager.pf12bold.getStringWidthD(text.get());
            double left = (hit.getWidth() - (ICON_S + ICON_GAP + textWidth)) * .5;
            place[0] = left + ICON_S * .5;
            place[1] = left + ICON_S + ICON_GAP;
        });

        final IntSupplier tint = () -> {
            boolean active = target() == self;
            return active ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), hover[0] * .7);
        };

        icon(hit, glyph, ICON_S, () -> place[0], () -> 15.0, tint);

        final LabelWidget caption = label(hit, text, FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(tint.getAsInt());
            caption.setPosition(place[1], inkTop(caption, 15.0));
        });
    }

    // ──────────────────────────── 服务端未安装 ────────────────────────────

    private void buildNoServer(final Panel host) {
        final RoundedRectWidget card = card(host, 82, 138);
        // This is the dominant inner surface, so it gets a real lens rather than only an outline.
        card.setGlassPlate(GlassLook.Surface.PLAYER);
        final Wash gloss = new Wash(false, 12.0);
        card.addChild(gloss);
        gloss.setBeforeRenderCallback(() -> {
            gloss.tint(0xFFFFFF, 0xFFFFFF);
            gloss.alphas(.075, 0.0);
            gloss.setBounds(Math.max(1, card.getWidth()), Math.min(72, card.getHeight()));
            gloss.setPosition(0, 0);
        });
        final RoundedRectWidget statusPlate = new RoundedRectWidget();
        card.addChild(statusPlate);
        statusPlate.setClickable(false).setRadius(7);
        statusPlate.setGlassRim(GlassLook.Surface.PLAYER);
        statusPlate.setBeforeRenderCallback(() -> {
            statusPlate.setBounds(86, 22);
            statusPlate.setPosition(card.getWidth() - 106, 18);
            statusPlate.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), WARN_COLOR, .28));
            statusPlate.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .52f));
        });
        LabelWidget statusText = label(statusPlate, "未连接", FontManager.pf10bold);
        statusText.setBeforeRenderCallback(() -> statusText
                .setColor(WARN_COLOR)
                .setPosition(statusPlate.getWidth() * .5 - statusText.getWidth() * .5,
                        inkTop(statusText, statusPlate.getHeight() * .5)));

        // 「云带斜线」比警告三角准确：不是出错了，而是这台服务器上根本没有这个服务。
        icon(card, PartyIcons.CLOUD_OFF, ICON_XL, () -> 38.0, () -> 40.0, () -> WARN_COLOR);

        LabelWidget title = label(card, "这台服务器没有一起听服务端", FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(66, inkTop(title, 30.0)));

        LabelWidget detail = label(card,
                "房间、点歌与全房进度都由服务端安排。请让服主装上 mpsync 服务端 mod"
                        + "（Fabric / NeoForge / Forge / Paper 均可），进服后这里会自动亮起来。",
                FontManager.pf12);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, card.getWidth() - 86));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.SCROLL);
            detail.setPosition(66, 44);
        });

        LabelWidget note = label(card, "服务端装好后，房间也可以用聊天指令 /mp 操作。", FontManager.pf12);
        note.setBeforeRenderCallback(() -> note
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(20, 84));

        // 探测状态直接显示出来：channel 是「服务器声明了这个通道」（Paper 不会声明），
        // hello 是「服务端回过话」。两个都为 false 才真的是没装。
        final RoundedRectWidget probePlate = new RoundedRectWidget();
        card.addChild(probePlate);
        probePlate.setClickable(false).setRadius(6);
        probePlate.setGlassRim(GlassLook.Surface.PLAYER);
        probePlate.setBeforeRenderCallback(() -> {
            probePlate.setBounds(Math.max(1, card.getWidth() - 40), 22);
            probePlate.setPosition(20, 104);
            probePlate.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            probePlate.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .34f));
        });
        LabelWidget probe = label(card, MpPartyClient::diag, FontManager.pf10bold);
        probe.setBeforeRenderCallback(() -> {
            probe.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            probe.setMaxWidth(Math.max(1, card.getWidth() - 40));
            probe.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            probe.setPosition(28, 110);
            probe.setAlpha(.62f);
        });
    }

    // ──────────────────────────── 房间页 · 横幅（投票 / 封禁） ────────────────────────────

    /**
     * 顶部横幅：封禁通知与投票卡共用同一个位置。
     *
     * <p>整块高度由 {@link #bannerInset} 插值撑开，出现 / 消失时底下的内容是滑下去 / 收回来的，
     * 不会「闪」一下。没有内容时高度为 0，卡片自己也会被 alpha 收掉。</p>
     */
    private void buildBanner(final Panel host) {
        final RoundedRectWidget card = new RoundedRectWidget();
        host.addChild(card);
        card.setClickable(false).setRadius(12).setBeforeRenderCallback(() -> {
            boolean banned = MpPartyClient.isBanned();
            card.setBounds(Math.max(1, host.getWidth() - PAD * 2), Math.max(1, bannerInset - 8));
            card.setPosition(PAD, 0);
            card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    banned ? ERROR_COLOR : NCMScreen.getColor(NCMScreen.ColorType.ACCENT), .14));
            card.setAlpha((float) clamp01(bannerInset / 44.0));
        });
        stroke(card, 12, () -> MpPartyClient.isBanned() ? ERROR_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> .45);

        LabelWidget headline = label(card, () -> {
            if (MpPartyClient.isBanned()) return "你被禁止使用一起听";
            MpVote vote = MpPartyClient.openVote();
            return vote == null ? "" : vote.headline;
        }, FontManager.pf12bold);
        headline.setBeforeRenderCallback(() -> {
            headline.setColor(MpPartyClient.isBanned() ? ERROR_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            headline.setMaxWidth(Math.max(1, card.getWidth() - 180));
            headline.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            headline.setPosition(18, 9);
        });

        LabelWidget sub = label(card, () -> {
            if (MpPartyClient.isBanned()) {
                String reason = MpPartyClient.banReason();
                String left = MpPartyClient.banRemaining();
                return (has(reason) ? reason : "未说明原因") + (has(left) ? "  ·  剩余 " + left : "");
            }
            MpVote vote = MpPartyClient.openVote();
            if (vote == null) return "";
            return vote.kindLabel() + "  ·  " + vote.tallyLabel() + "  ·  " + vote.remainingLabel();
        }, FontManager.pf10bold);
        sub.setBeforeRenderCallback(() -> {
            sub.setColor(MpPartyClient.isBanned() ? ERROR_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            sub.setMaxWidth(Math.max(1, card.getWidth() - 180));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(18, 28);
        });

        // 倒计时条：宽度按 MpVote.progress() 收缩，收完就是投票结束。
        final RoundedRectWidget track = new RoundedRectWidget();
        card.addChild(track);
        track.setClickable(false).setRadius(2).setBeforeRenderCallback(() -> {
            boolean show = !MpPartyClient.isBanned() && MpPartyClient.openVote() != null;
            track.setBounds(Math.max(1, card.getWidth() - 36), 3);
            track.setPosition(18, 46);
            track.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            track.setAlpha(show ? .6f : 0f);
        });
        final Wash fill = new Wash(true, 2.0);
        track.addChild(fill);
        fill.setClickable(false).setBeforeRenderCallback(() -> {
            MpVote vote = MpPartyClient.openVote();
            double percent = vote == null ? 0.0 : vote.progress();
            fill.tint(NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                    lighten(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), .35));
            fill.setBounds(Math.max(0.001, percent * track.getWidth()), 3);
            fill.setPosition(0, 0);
        });

        btn(card, () -> PartyIcons.CHECK, () -> "同意", MpPartyOverlay::voteOpen, () -> {
            MpVote vote = MpPartyClient.openVote();
            if (vote != null) MpPartyClient.castVote(vote.voteId, true);
        }, STYLE_ROW_PRIMARY, b -> {
            b.setBounds(60, 26);
            b.setPosition(Math.max(1, card.getWidth() - 18 - 60 - 8 - 60), 12);
        });

        btn(card, () -> PartyIcons.CLOSE, () -> "反对", MpPartyOverlay::voteOpen, () -> {
            MpVote vote = MpPartyClient.openVote();
            if (vote != null) MpPartyClient.castVote(vote.voteId, false);
        }, STYLE_ROW_DANGER, b -> {
            b.setBounds(60, 26);
            b.setPosition(Math.max(1, card.getWidth() - 18 - 60), 12);
        });
    }

    private static boolean voteOpen() {
        MpVote vote = MpPartyClient.openVote();
        return vote != null && !vote.closed && !MpPartyClient.isBanned();
    }

    // ──────────────────────────── 房间页 · 大厅态 ────────────────────────────

    private void buildLobby(final Panel host) {
        buildBanner(host);

        final RoundedRectWidget card = roomCard(host, 0, 124);

        LabelWidget headline = label(card, () -> MpPartyClient.isBanned()
                ? "一起听已被封禁" : "还没有加入房间", FontManager.pf16bold);
        headline.setBeforeRenderCallback(() -> headline
                .setColor(MpPartyClient.isBanned() ? ERROR_COLOR
                        : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(20, 18));

        LabelWidget sub = label(card, () -> {
            if (MpPartyClient.isBanned()) return "封禁到期后才能建房或加入，具体原因见上方横幅。";
            if (!MpPartyClient.v2()) return "旧版服务端：只有创建 / 加入房间可用。";
            if (MpPartyClient.channelEnabled()) {
                return "建一个自己的房间，或者直接进服务器频道 —— 全服共用的大房间，切歌由投票决定。";
            }
            return "建一个房间把好友请进来，或者用别人给的邀请码加入。";
        }, FontManager.pf12);
        sub.setBeforeRenderCallback(() -> {
            sub.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            sub.setMaxWidth(Math.max(1, card.getWidth() - 40));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(20, 42);
        });

        stat(card, 0, PartyIcons.PERSON,
                () -> String.valueOf(Math.max(0, MpPartyClient.onlineCount())), () -> "服务器在线");
        stat(card, 1, PartyIcons.PUBLIC_ROOM,
                () -> MpPartyClient.v2() ? String.valueOf(MpPartyClient.publicRooms().size()) : "—",
                () -> "公开房间");
        stat(card, 2, PartyIcons.MAIL,
                () -> MpPartyClient.v2() ? String.valueOf(MpPartyClient.pendingInvites().size()) : "—",
                () -> "待处理邀请");

        final String[] labels = {"创建房间", "加入房间", "进入频道", "接受邀请"};
        final String[] glyphs = {PartyIcons.ADD, PartyIcons.KEY, PartyIcons.CHANNEL, PartyIcons.MAIL};
        final Runnable[] actions = {
                this::showCreatePage,
                this::showJoinPage,
                MpPartyClient::joinChannel,
                () -> MpPartyClient.joinRoom(MpPartyClient.inviteCode())
        };
        final BooleanSupplier[] enabled = {
                () -> !MpPartyClient.isBanned(),
                () -> !MpPartyClient.isBanned(),
                () -> !MpPartyClient.isBanned() && MpPartyClient.channelEnabled(),
                () -> !MpPartyClient.isBanned() && has(MpPartyClient.inviteCode())
        };
        for (int index = 0; index < labels.length; index++) {
            final int slot = index;
            btn(host, () -> glyphs[slot], () -> labels[slot], enabled[slot], actions[slot],
                    slot == 0 ? STYLE_PRIMARY : STYLE_PLAIN, b -> {
                        double total = Math.max(1, host.getWidth() - PAD * 2);
                        double w = (total - 8 * 3) / 4.0;
                        b.setBounds(w, 36);
                        b.setPosition(PAD + (w + 8) * slot, bannerInset + 140);
                    });
        }

        lobbyLine(host, 0,
                () -> has(MpPartyClient.inviteCode()) ? PartyIcons.MAIL_UNREAD : PartyIcons.MAIL, () -> {
            String code = MpPartyClient.inviteCode();
            if (code == null || code.isEmpty()) return "还没有收到邀请。别人邀请你时会出现在「邀请」页，不刷聊天框。";
            return "来自 " + MpPartyClient.inviteFrom() + " 的邀请  ·  房间「"
                    + MpPartyClient.inviteRoomName() + "」·  邀请码 " + code;
        }, true);
        lobbyLine(host, 1,
                () -> MpPartyClient.serverResolve() ? PartyIcons.CLOUD_DONE : PartyIcons.CLOUD_OFF,
                () -> MpPartyClient.serverResolve()
                ? "服主配置了平台 Token，没有会员的玩家也能听到会员曲。"
                : "服主没有配置平台 Token；打开右上角「贡献会员解析」可以帮没有会员的房友解析。", false);
        lobbyLine(host, 2,
                () -> MpPartyClient.isServerAdmin() ? PartyIcons.GAVEL : PartyIcons.EXPLORE,
                () -> MpPartyClient.isServerAdmin()
                ? "你是服务器管理员：/mp ban <玩家> <原因> <时长> 可以封禁一起听功能。"
                : "「发现」页能看到公开房间与频道，「在线」页能看到谁在听什么。", false);
    }

    /** 大厅那三个数字：数字用大字号，说明用小字号配一枚图标，别都挤成一行灰字。 */
    private void stat(final RoundedRectWidget card, final int slot, final String glyph,
                      final Supplier<String> value, final Supplier<String> caption) {
        LabelWidget number = label(card, value, FontManager.pf18bold);
        number.setBeforeRenderCallback(() -> {
            double w = (Math.max(1, card.getWidth() - 40)) / 3.0;
            number.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            number.setPosition(20 + w * slot, inkTop(number, 79.0));
        });

        icon(card, glyph, ICON_S, () -> {
            double w = (Math.max(1, card.getWidth() - 40)) / 3.0;
            return 20 + w * slot + ICON_S * .5;
        }, () -> 101.0, MpPartyOverlay::secondary);

        LabelWidget note = label(card, caption, FontManager.pf10bold);
        note.setBeforeRenderCallback(() -> {
            double w = (Math.max(1, card.getWidth() - 40)) / 3.0;
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setPosition(20 + w * slot + ICON_S + ICON_GAP, inkTop(note, 101.0));
        });
    }

    /**
     * 大厅底部那三行说明。每行前面一枚图标，图标与文字同色、同步变色。
     *
     * <p>原来是三条等长的灰字，扫过去只看到「一片字」，得逐行读完才知道哪句和自己有关。图标先把
     * 「这条讲邀请 / 这条讲解析 / 这条讲权限」说完，那句话读不读由用户定。</p>
     */
    private void lobbyLine(final Panel host, final int row, final Supplier<String> glyph,
                           final Supplier<String> text, final boolean invite) {
        // bannerInset 每帧插值，位置只能在回调里取；构造时读到的是动画起点。
        final DoubleSupplier centerY = () -> bannerInset + 193 + row * 22;
        final IntSupplier tint = () -> invite && has(MpPartyClient.inviteCode()) ? OK_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);

        icon(host, glyph, ICON_S, () -> PAD + ICON_S * .5, centerY, tint);

        LabelWidget widget = label(host, text, FontManager.pf12);
        widget.setBeforeRenderCallback(() -> {
            widget.setColor(tint.getAsInt());
            widget.setMaxWidth(Math.max(1, host.getWidth() - PAD * 2 - ICON_S - ICON_GAP));
            widget.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            widget.setPosition(PAD + ICON_S + ICON_GAP, inkTop(widget, centerY.getAsDouble()));
        });
    }

    // ──────────────────────────── 房间页 · 房间态 ────────────────────────────

    private void buildRoom(final Panel host) {
        buildBanner(host);
        buildRoomIdentity(host);
        buildHeroCard(host);
        buildTransportRow(host);
        buildRoomActions(host);
        buildRoomTip(host);
    }

    /** 房名与房间元信息：不套卡片，直接作为这一页的小标题存在，视觉重量留给下面的在播卡。 */
    private void buildRoomIdentity(final Panel host) {
        LabelWidget name = label(host, () -> {
            MpRoom room = MpPartyClient.room();
            return room == null ? "" : room.name;
        }, FontManager.pf16bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, host.getWidth() - PAD * 2 - 190));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(PAD, bannerInset + 4);
        });

        // 来源色带：房名下的一短条，颜色按会话来源区分——
        // 绿=直连、蓝=服务器房间、橙=频道。扫一眼就知道当前是哪条链路的一起听。
        RoundedRectWidget sourceBand = new RoundedRectWidget();
        host.addChild(sourceBand);
        sourceBand.setClickable(false).setRadius(1.5).setBeforeRenderCallback(() -> {
            sourceBand.setBounds(Math.max(6, Math.min(name.getWidth(), 120)), 3);
            sourceBand.setPosition(PAD, bannerInset + 26);
            sourceBand.setColor(com.muoniumplayer.party.direct.MpDirect.isActive() ? OK_COLOR
                    : MpPartyClient.inChannel() ? WARN_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        });

        // 邀请码 / 人数 / 在听 / 会员 / 循环 / 我的身份：原来是一句「A  ·  B  ·  C」的长句，
        // 要从头读到尾才能找到想看的那一项。换成图标信息条之后是扫一眼的事。
        // 右边界钉在公开开关（左缘 W−194）左边：窄窗口下 chips 超界整颗隐藏，
        // 不然文字会半埋进右上角那两颗按钮底下。
        metaChips(host, () -> PAD, () -> bannerInset + 32, ICON_S, FontManager.pf10bold,
                () -> Math.max(PAD + 120, host.getWidth() - PAD - 194 - 12),
                meta(PartyIcons.KEY, () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null || room.channel ? "" : room.code;
                }),
                meta(PartyIcons.GROUP, () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null ? "" : room.members.size() + " 人";
                }),
                meta(PartyIcons.HEADPHONES, () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null ? "" : room.listeners + " 在听";
                }, () -> {
                    MpRoom room = MpPartyClient.room();
                    return room != null && room.listeners > 0 ? OK_COLOR : secondary();
                }),
                meta(PartyIcons.VIP, () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null || room.vipCount() <= 0 ? "" : room.vipCount() + " 会员";
                }, () -> VIP_COLOR),
                meta(() -> repeatGlyph(), () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null ? "" : room.repeatLabel();
                }, MpPartyOverlay::secondary),
                meta(() -> {
                    MpMember me = MpPartyClient.self();
                    return me != null && me.controls() ? PartyIcons.HOST : PartyIcons.PERSON;
                }, () -> {
                    MpMember me = MpPartyClient.self();
                    return me == null ? "" : me.roleLabel();
                }, () -> {
                    MpMember me = MpPartyClient.self();
                    return me != null && me.controls()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT) : secondary();
                }));

        btn(host, () -> System.currentTimeMillis() - copiedAt < COPIED_HINT_MILLIS
                ? PartyIcons.CHECK : PartyIcons.KEY,
                () -> System.currentTimeMillis() - copiedAt < COPIED_HINT_MILLIS
                ? "已复制" : "复制邀请码", () -> {
            MpRoom room = MpPartyClient.room();
            return room != null && !room.channel && has(room.code);
        }, () -> {
            MpRoom room = MpPartyClient.room();
            if (room == null || !has(room.code)) return;
            KeyboardUtils.setClipboardString(room.code);
            copiedAt = System.currentTimeMillis();
        }, STYLE_GHOST, b -> {
            b.setBounds(88, 26);
            b.setPosition(Math.max(1, host.getWidth() - PAD - 88), bannerInset + 8);
        });

        // 公开房间开关：只有房主能改，频道本身永远是公开的，所以频道里这颗按钮不出现。
        btn(host, () -> {
            MpRoom room = MpPartyClient.room();
            return room != null && room.open ? PartyIcons.PUBLIC_ROOM : PartyIcons.LOCK;
        }, () -> {
            MpRoom room = MpPartyClient.room();
            return room != null && room.open ? "公开中" : "设为公开";
        }, () -> {
            MpRoom room = MpPartyClient.room();
            return room != null && !room.channel && MpPartyClient.isOwner()
                    && MpPartyClient.publicRoomsEnabled();
        }, () -> {
            MpRoom room = MpPartyClient.room();
            if (room != null) MpPartyClient.setPublic(!room.open);
        }, STYLE_ROW_PRIMARY, b -> {
            b.setBounds(76, 26);
            b.setPosition(Math.max(1, host.getWidth() - PAD - 88 - 8 - 76), bannerInset + 8);
        });
    }

    /**
     * 在播卡：整块面板的视觉重心。
     *
     * <p>封面是这一版最大的观感改动 —— 服务端下发的 {@code MpTrack.cover} 本来就在协议里，
     * 上一版只是没画出来。左边一块 78px 的封面 + 右边三行字 + 一条渐变进度条，
     * 比一堆等宽灰字有层次得多。</p>
     */
    private void buildHeroCard(final Panel host) {
        final RoundedRectWidget card = roomCard(host, 46, 124);
        // 卡片底色额外压一层主色渐变：只有 6%~0%，但足够把「正在放」这块地方托起来。
        final Wash wash = new Wash(false, 12.0);
        card.addChild(wash);
        wash.setClickable(false).setBeforeRenderCallback(() -> {
            wash.tint(NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            wash.alphas(.10, 0.0);
            wash.setBounds(Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));
            wash.setPosition(0, 0);
        });

        buildCover(card);

        LabelWidget title = label(card, () -> {
            MpTrack track = MpPartyClient.currentTrack();
            if (track == null) return MpPartyClient.waitingUrl() ? "正在等待取流链接…" : "房间还没有在播的歌";
            return track.name;
        }, FontManager.pf16bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, card.getWidth() - 140));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(116, 20);
        });

        LabelWidget artist = label(card, () -> {
            MpTrack track = MpPartyClient.currentTrack();
            if (track == null) return "房主或管理员可以直接切歌，成员点的歌会排进歌单。";
            return track.artist == null || track.artist.trim().isEmpty() ? "未知歌手" : track.artist;
        }, FontManager.pf12);
        artist.setBeforeRenderCallback(() -> {
            artist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            artist.setMaxWidth(Math.max(1, card.getWidth() - 140));
            artist.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            artist.setPosition(116, 44);
        });

        LabelWidget state = label(card, () -> {
            if (MpPartyClient.selfPaused()) return "你已个人暂停 · 点「继续收听」会按房间进度补上";
            MpTrack track = MpPartyClient.currentTrack();
            if (track == null) return "";
            String who = has(track.requesterName) ? "点歌：" + track.requesterName : "";
            if (!MpPartyClient.playing()) return has(who) ? who + "  ·  全房已暂停" : "全房已暂停";
            return who;
        }, FontManager.pf10bold);
        // 状态图标：个人暂停是圆圈双竖条，全房暂停是双竖条，正常在放就显示点歌人（一个人）。
        icon(card, () -> {
            if (MpPartyClient.selfPaused()) return PartyIcons.PAUSE_CIRCLE;
            if (MpPartyClient.currentTrack() == null) return "";
            if (!MpPartyClient.playing()) return PartyIcons.PAUSE;
            return PartyIcons.PERSON;
        }, ICON_S, () -> 116.0 + ICON_S * .5, () -> 71.0, () -> MpPartyClient.selfPaused() ? WARN_COLOR
                : MpPartyClient.playing() ? OK_COLOR : WARN_COLOR);

        state.setBeforeRenderCallback(() -> {
            state.setColor(MpPartyClient.selfPaused() ? WARN_COLOR
                    : MpPartyClient.playing() ? OK_COLOR : WARN_COLOR);
            state.setMaxWidth(Math.max(1, card.getWidth() - 158));
            state.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            state.setPosition(116 + ICON_S + ICON_GAP, inkTop(state, 71.0));
        });

        buildProgressBar(card);

        // 两端的时间是纯数字：按行盒摆会比同一行的其它文字高出几像素，按墨迹摆才齐。
        LabelWidget cur = label(card, () -> mmss(displayPositionMs()), FontManager.pf10bold);
        cur.setBeforeRenderCallback(() -> cur
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(116, inkTop(cur, 105.0)));

        LabelWidget total = label(card, () -> mmss(MpPartyClient.durationMs()), FontManager.pf10bold);
        total.setBeforeRenderCallback(() -> total
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(Math.max(1, card.getWidth() - 24 - total.getWidth()), inkTop(total, 105.0)));
    }

    /** 在播封面：底下垫一块占位面，纹理没到之前不会是个洞。 */
    private void buildCover(final RoundedRectWidget card) {
        final RoundedRectWidget placeholder = new RoundedRectWidget();
        card.addChild(placeholder);
        placeholder.setClickable(false).setRadius(12).setBeforeRenderCallback(() -> {
            placeholder.setBounds(86, 86);
            placeholder.setPosition(16, 19);
            placeholder.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        // 占位从「歌名首字」换成音符：一个大号汉字在封面位置上很像渲染错了。
        icon(placeholder, PartyIcons.NOTE, ICON_XL, () -> 43.0, () -> 43.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)).setAlpha(.35f);

        final RoundedImageWidget cover = new RoundedImageWidget(
                () -> coverLocation(MpPartyClient.currentTrack(), true), 0, 0, 86, 86);
        placeholder.addChild(cover);
        cover.setClickable(false).setLinearFilter(true).setRadius(12);
        cover.fadeIn().setBeforeRenderCallback(() -> {
            cover.setBounds(86, 86);
            cover.setPosition(0, 0);
        });
        stroke(placeholder, 12, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER), () -> .5);
    }

    /**
     * 房间进度条。拖动时只改本地显示值，松手那一帧才下发一条 {@code seek}：
     * 服务端会把它广播给全房，每帧都发等于让所有人的播放器一直重新对齐。
     */
    private void buildProgressBar(final RoundedRectWidget card) {
        final double[] hover = new double[1];
        final RoundedRectWidget track = new RoundedRectWidget() {

            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);

                boolean down = Mouse.isButtonDown(0);
                boolean controller = MpPartyClient.hasControl() && MpPartyClient.durationMs() > 0L;
                if (!controller) {
                    barDragging = false;
                    barOverride = -1.0;
                    return;
                }
                if (!barDragging && down && this.testHovered(mouseX, mouseY, 6)) {
                    barDragging = true;
                }
                if (barDragging) {
                    double delta = Math.max(0, Math.min(this.getWidth(), mouseX - this.getX()));
                    barOverride = this.getWidth() <= 0 ? 0 : delta / this.getWidth();
                    if (!down) {
                        barDragging = false;
                        MpPartyClient.seek((long) (barOverride * MpPartyClient.durationMs()));
                        barOverride = -1.0;
                    }
                }
            }
        };
        card.addChild(track);
        track.setRadius(2).setShouldOverrideMouseCursor(true).setBeforeRenderCallback(() -> {
            boolean can = MpPartyClient.hasControl() && MpPartyClient.durationMs() > 0L;
            hover[0] = Interpolations.interpolate(hover[0],
                    can && (track.isHovering() || barDragging) ? 1.0 : 0.0, .25f);
            track.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            track.setBounds(Math.max(1, card.getWidth() - 140), 4);
            track.setPosition(116, 90);
        });

        final Wash fill = new Wash(true, 2.0);
        track.addChild(fill);
        fill.setClickable(false).setBeforeRenderCallback(() -> {
            int base = MpPartyClient.selfPaused() ? WARN_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            fill.tint(base, lighten(base, .38));
            fill.setBounds(Math.max(0.001, playedPercent() * track.getWidth()), 4);
            fill.setPosition(0, 0);
        });

        // 把手：能拖的时候才浮出来，拖着的时候略微变大 —— 不可拖时进度条就该像一条静态的线。
        final RoundedRectWidget knob = new RoundedRectWidget();
        track.addChild(knob);
        knob.setClickable(false).setBeforeRenderCallback(() -> {
            double size = 8.0 + hover[0] * 3.0;
            knob.setRadius(size * .5);
            knob.setBounds(size, size);
            knob.setPosition(playedPercent() * track.getWidth() - size * .5, 2.0 - size * .5);
            knob.setColor(0xFFFFFF);
            knob.setAlpha((float) (hover[0] * .95));
        });
    }

    private double playedPercent() {
        long duration = MpPartyClient.durationMs();
        if (barOverride >= 0.0) return clamp01(barOverride);
        if (duration <= 0L) return 0.0;
        return clamp01(displayPositionMs() / (double) duration);
    }

    private long displayPositionMs() {
        if (barOverride >= 0.0) return (long) (barOverride * MpPartyClient.durationMs());
        return MpPartyClient.positionMs();
    }

    /**
     * 上一曲 / 播放暂停 / 下一曲 / 循环模式 / 清空歌单。
     *
     * <p>频道里的普通成员按「下一曲」不是切歌，而是<b>发起切歌投票</b> —— 服务端只认投票结果，
     * 这里提前把语义摆出来，免得按下去以为没反应。</p>
     */
    private void buildTransportRow(final Panel host) {
        final String[] labels = {"上一曲", "", "", "", "清空歌单"};
        final Runnable[] actions = {
                MpPartyClient::prev,
                () -> {
                    if (MpPartyClient.playing()) MpPartyClient.pause();
                    else MpPartyClient.resume();
                },
                () -> {
                    if (MpPartyClient.inChannel() && !MpPartyClient.hasControl()) MpPartyClient.startSkipVote();
                    else MpPartyClient.next();
                },
                () -> {
                    MpRoom room = MpPartyClient.room();
                    String cur = room == null || room.repeat == null ? "off" : room.repeat;
                    MpPartyClient.setRepeat("off".equals(cur) ? "one" : ("one".equals(cur) ? "all" : "off"));
                },
                MpPartyClient::clearQueue
        };
        final BooleanSupplier control = MpPartyClient::hasControl;
        final BooleanSupplier[] enabled = {
                control,
                control,
                () -> MpPartyClient.hasControl() || MpPartyClient.inChannel(),
                control,
                control
        };
        for (int index = 0; index < labels.length; index++) {
            final int slot = index;
            Supplier<String> text;
            Supplier<String> glyph;
            if (slot == 1) {
                text = () -> MpPartyClient.playing() ? "暂停" : "播放";
                glyph = () -> MpPartyClient.playing() ? PartyIcons.PAUSE : PartyIcons.PLAY;
            } else if (slot == 2) {
                text = () -> MpPartyClient.inChannel() && !MpPartyClient.hasControl() ? "投票切歌" : "下一曲";
                glyph = () -> MpPartyClient.inChannel() && !MpPartyClient.hasControl()
                        ? PartyIcons.VOTE : PartyIcons.SKIP_NEXT;
            } else if (slot == 3) {
                text = () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null ? "顺序播放" : room.repeatLabel();
                };
                glyph = () -> repeatGlyph();
            } else if (slot == 0) {
                text = () -> labels[slot];
                glyph = () -> PartyIcons.SKIP_PREVIOUS;
            } else {
                text = () -> labels[slot];
                glyph = () -> PartyIcons.SWEEP;
            }
            btn(host, glyph, text, enabled[slot], actions[slot], slot == 1 ? STYLE_PRIMARY : STYLE_PLAIN, b -> {
                double total = Math.max(1, host.getWidth() - PAD * 2);
                double w = (total - 8 * 4) / 5.0;
                b.setBounds(w, 34);
                b.setPosition(PAD + (w + 8) * slot, bannerInset + 184);
            });
        }
    }

    private void buildRoomActions(final Panel host) {
        final String[] labels = {"链接点歌", "邀请玩家", "", "同步一次", ""};
        final Runnable[] actions = {
                this::showLinkPage,
                this::showInvitePage,
                MpPartyClient::toggleSelfPause,
                MpPartyClient::sync,
                () -> {
                    if (MpPartyClient.isOwner() && !MpPartyClient.inChannel()) MpPartyClient.disband();
                    else MpPartyClient.leaveRoom();
                }
        };
        final String[] glyphs = {PartyIcons.LINK, PartyIcons.PERSON_ADD, "", PartyIcons.SYNC, ""};
        for (int index = 0; index < labels.length; index++) {
            final int slot = index;
            Supplier<String> text;
            Supplier<String> glyph;
            if (slot == 2) {
                text = () -> MpPartyClient.selfPaused() ? "继续收听" : "个人暂停";
                glyph = () -> MpPartyClient.selfPaused()
                        ? PartyIcons.PLAY_CIRCLE : PartyIcons.PAUSE_CIRCLE;
            } else if (slot == 4) {
                text = () -> MpPartyClient.inChannel() ? "离开频道"
                        : (MpPartyClient.isOwner() ? "解散房间" : "退出房间");
                glyph = () -> !MpPartyClient.inChannel() && MpPartyClient.isOwner()
                        ? PartyIcons.CANCEL : PartyIcons.LOGOUT;
            } else {
                text = () -> labels[slot];
                glyph = () -> glyphs[slot];
            }
            final int style = slot == 4 ? STYLE_DANGER : (slot == 2 ? STYLE_GHOST : STYLE_GHOST);
            btn(host, glyph, text, null, actions[slot], style, b -> {
                double total = Math.max(1, host.getWidth() - PAD * 2);
                double w = (total - 8 * 4) / 5.0;
                b.setBounds(w, 34);
                b.setPosition(PAD + (w + 8) * slot, bannerInset + 226);
            });
        }
    }

    private void buildRoomTip(final Panel host) {
        // 图标跟着状态换：个人暂停 / 频道规则 / 只有房主能点 / 一般提示各一枚。这行字平时没人读完，
        // 图标是那种「不读也能扫到」的部分；颜色也跟着走，配色弱的主题下不至于只靠变红提示报错。
        final IntSupplier tint = () -> MpPartyClient.selfPaused() ? WARN_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        final DoubleSupplier centerY = () -> bannerInset + 277.0;

        icon(host, () -> {
            if (MpPartyClient.selfPaused()) return PartyIcons.PAUSE_CIRCLE;
            if (MpPartyClient.inChannel()) return PartyIcons.CHANNEL;
            if (!MpPartyClient.membersCanEnqueue()) return PartyIcons.LOCK;
            return PartyIcons.INFO;
        }, ICON_S, () -> PAD + ICON_S * .5, centerY, tint);

        LabelWidget tip = label(host, () -> {
            if (MpPartyClient.selfPaused()) {
                return "个人暂停只静你自己；点「继续收听」会立刻按房间进度补上，不会从头开始。";
            }
            if (MpPartyClient.inChannel()) {
                int cap = MpPartyClient.channelMaxPerMember();
                return "服务器频道：切歌要投票，每人最多同时点 " + Math.max(1, cap) + " 首。";
            }
            if (!MpPartyClient.membersCanEnqueue()) {
                return "当前只有房主与管理员可以点歌。";
            }
            return "任何成员都可以点歌排队 —— 在播放器里点「下一首播放」，或者用「链接点歌」。";
        }, FontManager.pf12);
        tip.setBeforeRenderCallback(() -> {
            tip.setColor(tint.getAsInt());
            tip.setMaxWidth(Math.max(1, host.getWidth() - PAD * 2 - ICON_S - ICON_GAP));
            tip.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            tip.setPosition(PAD + ICON_S + ICON_GAP, inkTop(tip, centerY.getAsDouble()));
        });
    }

    // ──────────────────────────── 页内通用件 ────────────────────────────

    /**
     * 每一页顶上的一行标题。
     *
     * <p>左边一句主语（多少首 / 多少人），右边一句右对齐的补充（上限、翻页提示、管理员命令）。
     * 两句都是<b>字号与字重</b>分级，不靠背景块划区域 —— 页里已经有一堆行卡片了，
     * 再套一层容器就成了灰块叠灰块。</p>
     */
    private void paneHeader(final Panel host, final Supplier<String> title, final Supplier<String> aside,
                            final IntSupplier asideColor, final double top) {
        LabelWidget head = label(host, title, FontManager.pf14bold);
        head.setBeforeRenderCallback(() -> {
            head.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            head.setMaxWidth(Math.max(1, host.getWidth() - PAD * 2 - 240));
            head.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            head.setPosition(PAD, top);
        });

        LabelWidget note = label(host, aside, FontManager.pf10bold);
        note.setBeforeRenderCallback(() -> {
            note.setColor(asideColor.getAsInt());
            note.setMaxWidth(300);
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(Math.max(1, host.getWidth() - PAD - note.getWidth()), top + 4);
        });
    }

    /**
     * 空列表时的提示：一枚大图标 + 一句话，<b>整组水平居中</b>。
     *
     * <p>原来是贴着左边的一行灰字，位置和列表行完全一样，看着像「第一行渲染坏了」。空状态是这
     * 页唯一的内容，它就该在正中间。列表本身在它上面，行是透明的，不会互相压住。</p>
     */
    private void emptyNote(final Panel host, final String glyph, final Supplier<String> text,
                           final BooleanSupplier show, final double top) {
        final double[] fade = new double[1];

        // 图标的 alpha 跟着下面那句话一起走：两件东西同时出现、同时消失才像一个空状态。
        icon(host, glyph, ICON_XL, () -> host.getWidth() * .5, () -> top + 14.0,
                MpPartyOverlay::secondary, () -> fade[0] * .55);

        LabelWidget note = label(host, text, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            fade[0] = Interpolations.interpolate(fade[0], show.getAsBoolean() ? 1.0 : 0.0, .3f);
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, host.getWidth() - PAD * 4));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition((host.getWidth() - note.getWidth()) * .5, top + 40.0);
            note.setAlpha((float) fade[0]);
        });
    }

    /** 一页底部的一句说明。前面一枚信息圆：一行灰字很容易被当成上面列表的一部分。 */
    private void paneTip(final Panel host, final Supplier<String> text, final IntSupplier color,
                         final DoubleSupplier top) {
        icon(host, PartyIcons.INFO, ICON_S, () -> PAD + ICON_S * .5,
                () -> top.getAsDouble() + 6.0, color);

        LabelWidget tip = label(host, text, FontManager.pf12);
        tip.setBeforeRenderCallback(() -> {
            tip.setColor(color.getAsInt());
            tip.setMaxWidth(Math.max(1, host.getWidth() - PAD * 2 - ICON_S - ICON_GAP));
            tip.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            tip.setPosition(PAD + ICON_S + ICON_GAP, inkTop(tip, top.getAsDouble() + 6.0));
        });
    }

    /** 循环模式对应的图标。顺序播放没有专用字形，用「带音符的列表」表示「按列表顺序走」。 */
    private static String repeatGlyph() {
        MpRoom room = MpPartyClient.room();
        String mode = room == null || room.repeat == null ? "off" : room.repeat;
        if ("one".equals(mode)) return PartyIcons.REPEAT_ONE;
        if ("all".equals(mode)) return PartyIcons.REPEAT;
        return PartyIcons.QUEUE_MUSIC;
    }

    /** 页眉里判断「要不要提示能滚」用的当前可见行数；列表还没建好时按最大值算。 */
    private static int shown(final ListView[] shelf) {
        return shelf[0] == null ? Integer.MAX_VALUE : shelf[0].rows();
    }

    private static int secondary() {
        return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    // ──────────────────────────── 歌单页 ────────────────────────────

    private void buildQueuePane(final Panel host) {
        // 列表是下面才建的，页眉那句「滚轮翻页」要按<b>这一帧真正画了几行</b>来判断，
        // 所以先留一个格子，建好之后填进去。
        final ListView[] shelf = new ListView[1];
        paneHeader(host, () -> {
            int size = MpPartyClient.queue().size();
            return size > 0 ? "待播 " + size + " 首" : "一起听歌单";
        }, () -> {
            StringBuilder sb = new StringBuilder();
            MpRoom room = MpPartyClient.room();
            if (room != null && room.perMemberCap > 0) {
                sb.append("每人同时最多 ").append(room.perMemberCap).append(" 首");
            }
            // 挂着几张歌单要说出来：一张 100 首的单子灌进来之后，队列里「谁的排队歌在前面」这件事
            // 全靠行副标题那一句「歌单 …」，而页眉给一个总数才看得出这批是分几次倒进来的。
            int lists = MpPartyClient.playlistLabelsInQueue().size();
            if (lists > 0) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("含 ").append(lists).append(" 张歌单");
            }
            if (MpPartyClient.queue().size() > shown(shelf)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("滚轮翻页");
            }
            return sb.toString();
        }, MpPartyOverlay::secondary, 0);

        emptyNote(host, PartyIcons.QUEUE_MUSIC, () -> MpPartyClient.membersCanEnqueue()
                ? "歌单是空的。在播放器里点「下一首播放」，或者用下面的「链接点歌」。"
                : "歌单是空的。这个房间只有房主与管理员可以点歌。",
                () -> MpPartyClient.queue().isEmpty(), 40.0);

        final ListView list = listView(host, () -> 26.0, QUEUE_ROWS, QUEUE_STRIDE,
                () -> MpPartyClient.queue().size(), 40.0);
        shelf[0] = list;
        for (int index = 0; index <= QUEUE_ROWS; index++) {
            buildQueueRow(list, index);
        }

        // 底部那一排按钮是<b>按数量平分宽度</b>的，因为「播放歌单」这颗只在服务端支持时才出现：
        // 老服务端上是三颗、支持了是四颗，写死三份坐标的话，少一颗就留下一块谁都不属于的空位。
        final List<String> labels = new ArrayList<String>();
        final List<String> glyphs = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        final List<BooleanSupplier> enabled = new ArrayList<BooleanSupplier>();
        final List<Integer> styles = new ArrayList<Integer>();

        labels.add("链接点歌");
        glyphs.add(PartyIcons.LINK);
        actions.add(this::showLinkPage);
        enabled.add(null);
        styles.add(STYLE_PRIMARY);

        if (MpPartyClient.playlistSupported()) {
            // 出现条件只看「这台服务端认不认这个 op、在不在房间、是不是频道」，<b>不看权限</b>：
            // 看得见但置灰，别人才知道功能是有的、只是这台服务器把它收给了管理组；
            // 直接藏掉的话，普通成员会以为这局房间坏了。
            labels.add("播放歌单");
            glyphs.add(PartyIcons.GROUP);
            actions.add(this::showPlaylistPage);
            enabled.add(MpPartyClient::canPlaylist);
            styles.add(STYLE_GHOST);
        }

        labels.add("同步一次");
        glyphs.add(PartyIcons.SYNC);
        actions.add(MpPartyClient::sync);
        enabled.add(null);
        styles.add(STYLE_GHOST);

        labels.add("清空歌单");
        glyphs.add(PartyIcons.SWEEP);
        actions.add(MpPartyClient::clearQueue);
        enabled.add(MpPartyClient::hasControl);
        styles.add(STYLE_GHOST);

        final int count = labels.size();
        for (int index = 0; index < count; index++) {
            final int slot = index;
            final String glyph = glyphs.get(slot);
            final String label = labels.get(slot);
            final Runnable action = actions.get(slot);
            final BooleanSupplier gate = enabled.get(slot);
            btn(host, () -> glyph, () -> label, gate, action, styles.get(slot).intValue(), b -> {
                double total = Math.max(1, host.getWidth() - PAD * 2);
                double w = (total - 8 * (count - 1)) / count;
                b.setBounds(w, 32);
                // 贴着页面底边，而不是写死的 272：弹窗被视口夹矮时这排按钮要跟着上来。
                b.setPosition(PAD + (w + 8) * slot, host.getHeight() - 34);
            });
        }
    }

    /**
     * 歌单里的一行：封面 + 歌名 + 副行 + 时长 + 三颗行内按钮。
     *
     * <p>封面是这一版的关键改动：{@code MpTrack.cover} 本来就在协议里，画出来之后这一列
     * 才有节奏，而不是五条等宽的灰字。</p>
     */
    private void buildQueueRow(final ListView list, final int slot) {
        final Row bg = row(list, slot, QUEUE_STRIDE, 44.0, () -> queueAt(list, slot) != null);

        final RoundedRectWidget art = new RoundedRectWidget();
        bg.addChild(art);
        art.setClickable(false).setRadius(7).setBeforeRenderCallback(() -> {
            art.setBounds(32, 32);
            art.setPosition(10, 6);
            art.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        // 以前是歌名首字（pf 字体里没有 U+266A，画出来是空盒子）；现在有 party-icons 了。
        icon(art, PartyIcons.NOTE, ICON_M, () -> 16.0, () -> 16.0,
                MpPartyOverlay::secondary).setAlpha(.45f);

        final RoundedImageWidget cover = new RoundedImageWidget(
                () -> coverLocation(queueAt(list, slot), false), 0, 0, 32, 32);
        art.addChild(cover);
        cover.setClickable(false).setLinearFilter(true).setRadius(7);
        cover.fadeIn().setBeforeRenderCallback(() -> {
            cover.setBounds(32, 32);
            cover.setPosition(0, 0);
        });

        LabelWidget title = label(bg, () -> {
            MpTrack track = queueAt(list, slot);
            if (track == null) return "";
            return (list.base() + slot + 1) + ".  " + track.name;
        }, FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, bg.getWidth() - 230));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(52, 9);
        });

        LabelWidget sub = label(bg, () -> {
            MpTrack track = queueAt(list, slot);
            if (track == null) return "";
            StringBuilder sb = new StringBuilder();
            // 「交接点已算好」放在最前：它是这一行唯一一条关于「房间现在处于什么状态」的信息，
            // 后面两句都是静态元数据。没有它，分析这件事在整个界面上完全不可见。
            if (track.planReady) sb.append("无缝已就绪");
            if (has(track.artist)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(track.artist);
            }
            if (track.fromPlaylist()) {
                // 灌进来的那批<b>不写「点歌 某人」</b>：一行行全是「点歌 张三」时，别人看不出
                // 后面几十首是自动排的，更不知道自己刚点的那首其实插在那批歌前面 —— 看起来就像
                // 排序坏了。这里换成歌单名，优先级这件事才在界面上看得见。
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("歌单 ").append(track.playlist);
            } else if (has(track.requesterName)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("点歌 ").append(track.requesterName);
            }
            return sb.toString();
        }, FontManager.pf10bold);
        sub.setBeforeRenderCallback(() -> {
            // 有标记时用强调色：这一行会在整列灰字里跳出来，用户一眼就能看到「这首已经准备好了」。
            MpTrack track = queueAt(list, slot);
            sub.setColor(track != null && track.planReady
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT) : secondary());
            sub.setMaxWidth(Math.max(1, bg.getWidth() - 230));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(52, 26);
        });

        LabelWidget length = label(bg, () -> {
            MpTrack track = queueAt(list, slot);
            return track == null || track.durationMs <= 0L ? "" : mmss(track.durationMs);
        }, FontManager.pf10bold);
        length.setBeforeRenderCallback(() -> {
            length.setColor(secondary());
            // 按墨迹居中：时长是纯数字，只有大写高度，按行盒摆会比同一行按钮上的中文高好几像素。
            length.setPosition(Math.max(1, bg.getWidth() - 122 - length.getWidth()), inkTop(length, 22.0));
        });

        final Panel tools = rowTools(bg);

        // 行内三颗改成纯图标：插播 / 置顶 / 移除都是通用的播放器手势，图标比两个汉字好认，
        // 也把宽度还给了歌名（maxWidth 少扣 34）。语义在页脚那句提示里写着。
        btn(tools, () -> PartyIcons.PLAY_NEXT, null,
                () -> queueAt(list, slot) != null && MpPartyClient.hasControl(), () -> {
            MpTrack track = queueAt(list, slot);
            if (track != null) MpPartyClient.playEntry(track.entryId);
        }, STYLE_ROW, b -> {
            b.setBounds(28, 24);
            b.setPosition(bg.getWidth() - 106, 10);
        });

        btn(tools, () -> PartyIcons.TOP, null,
                () -> queueAt(list, slot) != null && MpPartyClient.hasControl(), () -> {
            MpTrack track = queueAt(list, slot);
            if (track != null) MpPartyClient.moveEntry(track.entryId, 0);
        }, STYLE_ROW, b -> {
            b.setBounds(28, 24);
            b.setPosition(bg.getWidth() - 72, 10);
        });

        // 移除对「自己点的那一首」也开放：服务端仍会再校验一次权限。
        btn(tools, () -> PartyIcons.CLOSE, null, () -> {
            MpTrack track = queueAt(list, slot);
            if (track == null) return false;
            if (MpPartyClient.hasControl()) return true;
            MpMember me = MpPartyClient.self();
            return me != null && me.name != null && me.name.equals(track.requesterName);
        }, () -> {
            MpTrack track = queueAt(list, slot);
            if (track != null) MpPartyClient.removeEntry(track.entryId);
        }, STYLE_ROW_DANGER, b -> {
            b.setBounds(28, 24);
            b.setPosition(bg.getWidth() - 38, 10);
        });
    }

    /** 歌单第 {@code slot} 个槽位当前对应的曲目；这一槽空着时返回 null。 */
    private MpTrack queueAt(final ListView list, final int slot) {
        List<MpTrack> queue = MpPartyClient.queue();
        int index = list.base() + slot;
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    // ──────────────────────────── 成员页 ────────────────────────────

    private void buildMembersPane(final Panel host) {
        final ListView[] shelf = new ListView[1];
        paneHeader(host, () -> {
            MpRoom room = MpPartyClient.room();
            if (room == null) return "房间成员";
            return room.members.size() + " 人在房  ·  " + room.listeners + " 在听";
        }, () -> {
            MpRoom room = MpPartyClient.room();
            if (room == null) return "";
            StringBuilder sb = new StringBuilder();
            if (room.vipCount() > 0) sb.append(room.vipCount()).append(" 人可解析会员曲");
            if (room.members.size() > shown(shelf)) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append("滚轮翻页");
            }
            return sb.toString();
        }, MpPartyOverlay::secondary, 0);

        final ListView list = listView(host, () -> 26.0, MEMBER_ROWS, MEMBER_STRIDE,
                () -> MpPartyClient.members().size(), 20.0);
        shelf[0] = list;
        for (int index = 0; index <= MEMBER_ROWS; index++) {
            buildMemberRow(list, index);
        }

        paneTip(host, () -> MpPartyClient.inChannel()
                ? "频道里踢人要投票：超过 80% 同意才生效，票数不够会进冷却。"
                : "转让房主后你会变成管理员；踢出只影响这个房间，不影响服务器。",
                MpPartyOverlay::secondary, () -> host.getHeight() - 14);
    }

    private void buildMemberRow(final ListView list, final int slot) {
        final Row bg = row(list, slot, MEMBER_STRIDE, 46.0, () -> memberAt(list, slot) != null);

        avatar(bg, () -> {
            MpMember member = memberAt(list, slot);
            return member == null ? "" : member.name;
        }, 10, 6, 34);

        LabelWidget name = label(bg, () -> {
            MpMember member = memberAt(list, slot);
            if (member == null) return "";
            return member.name + (isSelf(member) ? "（你）" : "");
        }, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            MpMember member = memberAt(list, slot);
            name.setColor(member != null && member.controls()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            // automix 标记就挂在名字这一行的右端，它这一帧真的画出来才给名字让位；
            // 老服务端没下发那一档时标记是空的，名字该用满原来的宽度。
            String mark = member == null ? "" : member.automixLabel();
            double reserve = has(mark)
                    ? FontManager.pf10bold.getStringWidthD(mark) + MARK_PAD * 2 + 8.0 : 0.0;
            name.setMaxWidth(Math.max(1, bg.getWidth() - 380 - reserve));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(54, 9);
        });

        // MP / Mu 徽标紧跟名字：一眼看出对方用的是哪个播放器。
        LabelWidget badge = label(bg, () -> {
            MpMember member = memberAt(list, slot);
            return member == null ? "" : member.badge();
        }, FontManager.pf10bold);
        badge.setBeforeRenderCallback(() -> {
            badge.setColor(secondary());
            badge.setPosition(54 + name.getWidth() + 7, 11);
        });

        // 身份 / 客户端 / 在听状态：三段各自配一枚图标，别再堆成一句「A · B · C」。
        metaChips(bg, () -> 54.0, () -> 32.0, ICON_S, FontManager.pf10bold,
                meta(() -> {
                    MpMember member = memberAt(list, slot);
                    return member != null && member.controls() ? PartyIcons.HOST : PartyIcons.PERSON;
                }, () -> {
                    MpMember member = memberAt(list, slot);
                    return member == null ? "" : member.roleLabel();
                }, () -> {
                    MpMember member = memberAt(list, slot);
                    return member != null && member.controls()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT) : secondary();
                }),
                meta(() -> {
                    MpMember member = memberAt(list, slot);
                    if (member == null) return PartyIcons.HOURGLASS;
                    if (!member.online) return PartyIcons.CLOUD_OFF;
                    if (member.selfPaused) return PartyIcons.PAUSE_CIRCLE;
                    return member.listening ? PartyIcons.HEADPHONES : PartyIcons.HOURGLASS;
                }, () -> {
                    MpMember member = memberAt(list, slot);
                    return member == null ? "" : member.stateLabel();
                }, () -> {
                    MpMember member = memberAt(list, slot);
                    return member == null ? secondary()
                            : member.selfPaused || !member.online ? WARN_COLOR
                            : member.listening ? OK_COLOR : secondary();
                }));

        chip(bg, () -> {
            MpMember member = memberAt(list, slot);
            return member != null && member.contributeVip ? "贡献" : "VIP";
        }, () -> {
            MpMember member = memberAt(list, slot);
            return member != null && member.vip;
        }, b -> b.setPosition(Math.max(1, bg.getWidth() - 262), 15));

        // automix 能力标记：这一台能不能替房间算下一首的交接点。挑了「贡献 / VIP」那颗左边那块
        // —— 那一列本来就是「这个人能给房间什么」的位置，右边三颗按钮与徽标都各占一格，只有它是
        // 一条按右边界锚死的空档。没下发这一档时文案为空，整颗淡掉，什么都不画。
        automixMark(bg, () -> {
            MpMember member = memberAt(list, slot);
            return member == null ? "" : member.automixLabel();
        }, () -> automixTint(memberAt(list, slot)),
                b -> b.setPosition(Math.max(1, bg.getWidth() - 262 - 6 - b.getWidth()), 15));

        final Panel tools = rowTools(bg);

        // 三颗按钮的宽度一个都没改：62 宽里放三个中文字只用掉 18，剩下的留白足够塞一枚 12 的图标。
        btn(tools, () -> PartyIcons.GAVEL, () -> {
            MpMember member = memberAt(list, slot);
            return member != null && "admin".equals(member.role) ? "撤管理" : "设管理";
        }, () -> {
            MpMember member = memberAt(list, slot);
            return member != null && MpPartyClient.isOwner() && !MpPartyClient.inChannel()
                    && !isSelf(member) && !"owner".equals(member.role);
        }, () -> {
            MpMember member = memberAt(list, slot);
            if (member != null) MpPartyClient.setAdmin(member.name, !"admin".equals(member.role));
        }, STYLE_ROW, b -> {
            b.setBounds(62, 26);
            b.setPosition(bg.getWidth() - 220, 10);
        });

        btn(tools, () -> PartyIcons.HOST, () -> "转房主", () -> {
            MpMember member = memberAt(list, slot);
            return member != null && MpPartyClient.isOwner() && !MpPartyClient.inChannel()
                    && !isSelf(member);
        }, () -> {
            MpMember member = memberAt(list, slot);
            if (member != null) MpPartyClient.transferOwner(member.name);
        }, STYLE_ROW, b -> {
            b.setBounds(62, 26);
            b.setPosition(bg.getWidth() - 152, 10);
        });

        // 频道里的普通成员踢人得走投票；自己的房间里房主 / 管理员直接踢。
        btn(tools, () -> MpPartyClient.inChannel() && !MpPartyClient.hasControl()
                        ? PartyIcons.VOTE : PartyIcons.CLOSE,
                () -> MpPartyClient.inChannel() && !MpPartyClient.hasControl() ? "发起踢出" : "踢出", () -> {
            MpMember member = memberAt(list, slot);
            if (member == null || isSelf(member)) return false;
            if (MpPartyClient.inChannel() && !MpPartyClient.hasControl()) return MpPartyClient.v2();
            return MpPartyClient.hasControl() && !"owner".equals(member.role);
        }, () -> {
            MpMember member = memberAt(list, slot);
            if (member == null) return;
            if (MpPartyClient.inChannel() && !MpPartyClient.hasControl()) {
                MpPartyClient.startKickVote(member.uuid);
            } else {
                MpPartyClient.kick(member.name);
            }
        }, STYLE_ROW_DANGER, b -> {
            b.setBounds(76, 26);
            b.setPosition(bg.getWidth() - 86, 10);
        });
    }

    private MpMember memberAt(final ListView list, final int slot) {
        List<MpMember> members = MpPartyClient.members();
        int index = list.base() + slot;
        return index >= 0 && index < members.size() ? members.get(index) : null;
    }

    private static boolean isSelf(MpMember member) {
        MpMember me = MpPartyClient.self();
        return me != null && member != null && me.uuid != null && me.uuid.equals(member.uuid);
    }
    // ──────────────────────────── 在线页 ────────────────────────────

    private void buildOnlinePane(final Panel host) {
        final ListView[] shelf = new ListView[1];
        paneHeader(host, () -> {
            int size = MpPartyClient.presence().size();
            return "服务器在线 " + Math.max(size, MpPartyClient.onlineCount()) + " 人";
        }, () -> {
            if (MpPartyClient.isServerAdmin()) return "/mp ban <玩家> <原因> <时长>";
            return MpPartyClient.presence().size() > shown(shelf) ? "滚轮翻页" : "";
        }, () -> MpPartyClient.isServerAdmin() ? WARN_COLOR : secondary(), 0);

        emptyNote(host, PartyIcons.HOURGLASS, () -> "还没收到在线名单，稍等一下…",
                () -> MpPartyClient.presence().isEmpty(), 40.0);

        final ListView list = listView(host, () -> 26.0, PRESENCE_ROWS, PRESENCE_STRIDE,
                () -> MpPartyClient.presence().size(), 4.0);
        shelf[0] = list;
        for (int index = 0; index <= PRESENCE_ROWS; index++) {
            buildPresenceRow(list, index);
        }
    }

    private void buildPresenceRow(final ListView list, final int slot) {
        final Row bg = row(list, slot, PRESENCE_STRIDE, 40.0, () -> presenceAt(list, slot) != null);

        avatar(bg, () -> {
            MpPresence who = presenceAt(list, slot);
            return who == null ? "" : who.name;
        }, 10, 6, 28);

        LabelWidget name = label(bg, () -> {
            MpPresence who = presenceAt(list, slot);
            if (who == null) return "";
            String badge = has(who.modBadge) ? "   " + who.modBadge : "";
            return who.name + badge;
        }, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            MpPresence who = presenceAt(list, slot);
            name.setColor(who != null && who.hasClient
                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT) : secondary());
            name.setMaxWidth(Math.max(1, bg.getWidth() - 214));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(46, 5);
        });

        // 右边界钉在贡献 chip（左缘 W−138）左边：极窄窗口下 chips 超界隐藏，不钻进右列。
        metaChips(bg, () -> 46.0, () -> 27.0, ICON_S, FontManager.pf10bold,
                () -> Math.max(146.0, bg.getWidth() - 138 - 10),
                meta(() -> {
                    MpPresence who = presenceAt(list, slot);
                    return who != null && who.localPlaying ? PartyIcons.NOTE : PartyIcons.HOURGLASS;
                }, () -> {
                    MpPresence who = presenceAt(list, slot);
                    return who == null ? "" : who.stateLabel();
                }, () -> {
                    MpPresence who = presenceAt(list, slot);
                    return who != null && who.localPlaying ? OK_COLOR : secondary();
                }),
                meta(PartyIcons.RADIO, () -> {
                    MpPresence who = presenceAt(list, slot);
                    return who == null || !who.hasClient ? "" : who.clientLabel();
                }));

        chip(bg, () -> {
            MpPresence who = presenceAt(list, slot);
            return who != null && who.contributeVip ? "贡献" : "VIP";
        }, () -> {
            MpPresence who = presenceAt(list, slot);
            return who != null && who.vip;
        }, b -> b.setPosition(Math.max(1, bg.getWidth() - 138), 12));

        final Panel tools = rowTools(bg);

        btn(tools, () -> PartyIcons.PERSON_ADD, () -> "邀请", () -> {
            MpPresence who = presenceAt(list, slot);
            return who != null && who.hasClient && MpPartyClient.inRoom() && !MpPartyClient.inChannel()
                    && who.uuid != null && !who.uuid.equals(MpPartyClient.selfUuid());
        }, () -> {
            MpPresence who = presenceAt(list, slot);
            if (who != null) MpPartyClient.invite(who.name);
        }, STYLE_ROW_PRIMARY, b -> {
            // chip 右缘 -90，这里左缘 -82：中间自然留 8px，右侧留 16px——
            // 原来两件控件零间距相接，悬停时行再往两侧一扩就贴出行缘。
            b.setBounds(66, 26);
            b.setPosition(Math.max(1, bg.getWidth() - 82), 7);
        });
    }

    private MpPresence presenceAt(final ListView list, final int slot) {
        List<MpPresence> all = MpPartyClient.presence();
        int index = list.base() + slot;
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    // ──────────────────────────── 发现页（频道 + 公开房间） ────────────────────────────

    private void buildDiscoverPane(final Panel host) {
        final ListView[] shelf = new ListView[1];
        final RoundedRectWidget channelCard = card(host, 0, 62);
        // 频道卡压一层横向渐变：它是这一页唯一的「全服入口」，值得比普通行重一点。
        final Wash wash = new Wash(true, 12.0);
        channelCard.addChild(wash);
        wash.setClickable(false).setBeforeRenderCallback(() -> {
            boolean on = MpPartyClient.channelEnabled();
            wash.tint(NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            wash.alphas(on ? .14 : .04, 0.0);
            wash.setBounds(Math.max(1, channelCard.getWidth()), Math.max(1, channelCard.getHeight()));
            wash.setPosition(0, 0);
        });

        LabelWidget channelTitle = label(channelCard, () -> MpPartyClient.channelEnabled()
                ? "服务器频道 · " + MpPartyClient.channelName()
                : "服务器频道未开启", FontManager.pf14bold);
        channelTitle.setBeforeRenderCallback(() -> {
            channelTitle.setColor(MpPartyClient.channelEnabled()
                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT) : secondary());
            channelTitle.setMaxWidth(Math.max(1, channelCard.getWidth() - 140));
            channelTitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            channelTitle.setPosition(18, 12);
        });

        LabelWidget channelSub = label(channelCard, () -> MpPartyClient.channelEnabled()
                ? "全服共用的大房间，谁都能进；切歌由房内投票决定，每人最多同时点 "
                        + Math.max(1, MpPartyClient.channelMaxPerMember()) + " 首。"
                : "服主可以在服务端配置里打开频道，让全服共用一个大房间。", FontManager.pf10bold);
        channelSub.setBeforeRenderCallback(() -> {
            channelSub.setColor(secondary());
            channelSub.setMaxWidth(Math.max(1, channelCard.getWidth() - 140));
            channelSub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            channelSub.setPosition(18, 34);
        });

        btn(channelCard, () -> MpPartyClient.inChannel() ? PartyIcons.CHECK : PartyIcons.CHANNEL,
                () -> MpPartyClient.inChannel() ? "已在频道" : "进入频道",
                () -> MpPartyClient.channelEnabled() && !MpPartyClient.inChannel()
                        && !MpPartyClient.isBanned(),
                MpPartyClient::joinChannel, STYLE_PRIMARY, b -> {
                    b.setBounds(84, 28);
                    b.setPosition(Math.max(1, channelCard.getWidth() - 18 - 84), 17);
                });

        paneHeader(host, () -> {
            if (!MpPartyClient.publicRoomsEnabled()) return "公开房间已被服主关闭";
            int size = MpPartyClient.publicRooms().size();
            return size > 0 ? "公开房间 " + size + " 个" : "公开房间";
        }, () -> MpPartyClient.publicRooms().size() > shown(shelf) ? "滚轮翻页" : "",
                MpPartyOverlay::secondary, 70);

        emptyNote(host, PartyIcons.PUBLIC_ROOM, () -> MpPartyClient.publicRoomsEnabled()
                ? "现在没有公开房间。自己建一个，然后在房间页点「设为公开」。"
                : "这台服务器没有开放公开房间列表，只能用邀请码加入。",
                () -> MpPartyClient.publicRooms().isEmpty(), 106.0);

        final ListView list = listView(host, () -> 92.0, PUBLIC_ROWS, PUBLIC_STRIDE,
                () -> MpPartyClient.publicRooms().size(), 22.0);
        shelf[0] = list;
        for (int index = 0; index <= PUBLIC_ROWS; index++) {
            buildPublicRow(list, index);
        }

        paneTip(host, () -> "公开房间不用邀请码，点「加入」直接进；房间满了会显示「已满」。",
                MpPartyOverlay::secondary, () -> host.getHeight() - 16);
    }

    private void buildPublicRow(final ListView list, final int slot) {
        final Row bg = row(list, slot, PUBLIC_STRIDE, 42.0, () -> publicAt(list, slot) != null);

        // 行首一枚类型图标：频道是喇叭，普通公开房间是地球。一列全是字的列表最难扫。
        icon(bg, () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room != null && room.channel ? PartyIcons.CHANNEL : PartyIcons.PUBLIC_ROOM;
        }, ICON_M, () -> 24.0, () -> 21.0, () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room != null && room.channel
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT) : secondary();
        });

        LabelWidget name = label(bg, () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room == null ? "" : room.name;
        }, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            MpPublicRoom room = publicAt(list, slot);
            name.setColor(room != null && room.channel
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, bg.getWidth() - 200));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(40, 8);
        });

        LabelWidget kind = label(bg, () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room == null ? "" : room.kindLabel();
        }, FontManager.pf10bold);
        kind.setBeforeRenderCallback(() -> {
            kind.setColor(secondary());
            kind.setPosition(40 + name.getWidth() + 8, inkTop(kind, 14.0));
        });

        LabelWidget meta = label(bg, () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room == null ? "" : room.metaLabel() + "  ·  " + room.nowLabel();
        }, FontManager.pf10bold);
        meta.setBeforeRenderCallback(() -> {
            meta.setColor(secondary());
            meta.setMaxWidth(Math.max(1, bg.getWidth() - 136));
            meta.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            meta.setPosition(40, 25);
        });

        final Panel tools = rowTools(bg);

        // 满员时按钮整颗淡掉（行内样式的规矩），所以另给一句右对齐的说明，
        // 否则那一行右边会空出一块，看着像没渲染完。
        LabelWidget full = label(bg, () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room != null && room.full ? "已满" : "";
        }, FontManager.pf10bold);
        full.setBeforeRenderCallback(() -> {
            full.setColor(WARN_COLOR);
            full.setPosition(Math.max(1, bg.getWidth() - 80 + (66 - full.getWidth()) * .5),
                    inkTop(full, 21.0));
        });

        btn(tools, () -> PartyIcons.CHEVRON_RIGHT, () -> "加入", () -> {
            MpPublicRoom room = publicAt(list, slot);
            return room != null && !room.full && !MpPartyClient.isBanned();
        }, () -> {
            MpPublicRoom room = publicAt(list, slot);
            if (room == null) return;
            if (room.channel) MpPartyClient.joinChannel();
            else MpPartyClient.joinRoom(room.code);
        }, STYLE_ROW_PRIMARY, b -> {
            b.setBounds(66, 26);
            b.setPosition(bg.getWidth() - 80, 8);
        });
    }

    private MpPublicRoom publicAt(final ListView list, final int slot) {
        List<MpPublicRoom> all = MpPartyClient.publicRooms();
        int index = list.base() + slot;
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    // ──────────────────────────── 邀请页 ────────────────────────────

    private void buildInvitesPane(final Panel host) {
        final ListView[] shelf = new ListView[1];
        paneHeader(host, () -> {
            int size = MpPartyClient.pendingInvites().size();
            return size > 0 ? "待处理邀请 " + size + " 条" : "待处理邀请";
        }, () -> MpPartyClient.pendingInvites().size() > shown(shelf) ? "滚轮翻页" : "",
                MpPartyOverlay::secondary, 0);

        emptyNote(host, PartyIcons.MAIL, () -> "没有待处理的邀请。别人邀请你的时候会出现在这里，不会刷聊天框。",
                () -> MpPartyClient.pendingInvites().isEmpty(), 40.0);

        final ListView list = listView(host, () -> 26.0, INVITE_ROWS, INVITE_STRIDE,
                () -> MpPartyClient.pendingInvites().size(), 40.0);
        shelf[0] = list;
        for (int index = 0; index <= INVITE_ROWS; index++) {
            buildInviteRow(list, index);
        }

        paneTip(host, () -> "接受会直接把你换进对方的房间；忽略只是从这份列表里去掉。",
                MpPartyOverlay::secondary, () -> host.getHeight() - 32);
    }

    private void buildInviteRow(final ListView list, final int slot) {
        final Row bg = row(list, slot, INVITE_STRIDE, 54.0, () -> inviteAt(list, slot) != null);

        avatar(bg, () -> {
            MpInvite invite = inviteAt(list, slot);
            return invite == null ? "" : invite.fromName;
        }, 12, 12, 30);

        LabelWidget headline = label(bg, () -> {
            MpInvite invite = inviteAt(list, slot);
            return invite == null ? "" : invite.headline();
        }, FontManager.pf12bold);
        headline.setBeforeRenderCallback(() -> {
            headline.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            headline.setMaxWidth(Math.max(1, bg.getWidth() - 200));
            headline.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            headline.setPosition(52, 12);
        });

        LabelWidget sub = label(bg, () -> {
            MpInvite invite = inviteAt(list, slot);
            if (invite == null) return "";
            return has(invite.code) ? "邀请码 " + invite.code : "点「接受」直接进房";
        }, FontManager.pf10bold);
        sub.setBeforeRenderCallback(() -> {
            sub.setColor(secondary());
            sub.setMaxWidth(Math.max(1, bg.getWidth() - 200));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(52, 31);
        });

        final Panel tools = rowTools(bg);

        btn(tools, () -> PartyIcons.CHECK, () -> "接受",
                () -> inviteAt(list, slot) != null && !MpPartyClient.isBanned(), () -> {
            MpInvite invite = inviteAt(list, slot);
            if (invite != null) MpPartyClient.acceptInvite(invite.roomId);
        }, STYLE_ROW_PRIMARY, b -> {
            b.setBounds(62, 26);
            b.setPosition(bg.getWidth() - 142, 14);
        });

        btn(tools, () -> PartyIcons.CLOSE, () -> "忽略", () -> inviteAt(list, slot) != null, () -> {
            MpInvite invite = inviteAt(list, slot);
            if (invite != null) MpPartyClient.declineInvite(invite.roomId);
        }, STYLE_ROW_DANGER, b -> {
            b.setBounds(62, 26);
            b.setPosition(bg.getWidth() - 74, 14);
        });
    }

    private MpInvite inviteAt(final ListView list, final int slot) {
        List<MpInvite> all = MpPartyClient.pendingInvites();
        int index = list.base() + slot;
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    // ──────────────────────────── 输入型子页 ────────────────────────────

    private void showCreatePage() {
        inputPage(Page.CREATE, "创建房间", "给房间起个名字，留空就用默认名。建好之后你是房主。",
                "建好后可以邀请玩家，也可以在房间页点「设为公开」让别人在发现页看到。",
                "例如：深夜电台", 32, PartyIcons.ADD, "创建房间", value -> {
                    MpPartyClient.createRoom(value);
                    showOverview();
                }, false);
    }

    private void showJoinPage() {
        inputPage(Page.JOIN, "加入房间", "粘贴好友给的邀请码。房主在房间页点「复制邀请码」就能拿到。",
                "加入后播放器跟着房间走：本地起播会自动变成点歌排队。",
                "邀请码", 64, PartyIcons.KEY, "加入房间", value -> {
                    if (value.isEmpty()) {
                        hint = "请先填写邀请码";
                        hintColor = ERROR_COLOR;
                        return;
                    }
                    MpPartyClient.joinRoom(value);
                    showOverview();
                }, true);
    }

    private void showInvitePage() {
        inputPage(Page.INVITE, "邀请玩家", "填服务器里的玩家名。邀请只出现在对方的「邀请」页，不刷聊天框。",
                "「在线」页也可以直接点某个人后面的「邀请」。",
                "玩家名", 32, PartyIcons.PERSON_ADD, "发送邀请", value -> {
                    if (value.isEmpty()) {
                        hint = "请先填写玩家名";
                        hintColor = ERROR_COLOR;
                        return;
                    }
                    MpPartyClient.invite(value);
                    showOverview();
                }, true);
    }

    /**
     * 直连房间页：上半是「开直连房间」（端口 + 局域网开关），下半是「加入」（邀请串输入框）。
     *
     * <p>两个动作放同一页是有意的：这个功能最常见的场景是「一个人懂，四个朋友不懂」——
     * 懂的那位开房、把串发到群里，其余人只需要知道「去直连房间粘贴那串东西」。分两页反而
     * 让「粘贴」这一步离「开房」太远。</p>
     *
     * <p>托管态整个换一版布局：房主此刻唯一要干的事是把邀请串发出去，所以地址输入 +
     * 三颗复制按钮（整串 / 端口 / 密码）常驻页面中央；「关闭直连房间」走完之后立刻
     * 回大厅（关房是终结动作，留在原地只会看到一堆「已断开」的空状态）。</p>
     */
    private void showDirectPage() {
        page = Page.DIRECT;
        field = null;
        hint = "";
        hintColor = 0xAEB5C4;
        getChildren().clear();

        final boolean hosting = com.muoniumplayer.party.direct.MpDirect.isHosting();
        final boolean joined = com.muoniumplayer.party.direct.MpDirect.isJoined();

        final Panel dialog = createDialog(468, () -> hosting ? 286.0 : 300.0, null);
        // 托管/加入态的布局是按构建那一刻的状态定型的；如果会话随后被动终结
        // （休眠恢复打断 socket 等），留在页面上会看到一具「空密码、0 人」的僵尸。
        // 状态翻 false 就重建一次本页。
        //
        // 检测挂在一张不画东西的透明子件上——不能覆盖 dialog 自己的
        // beforeRender（那里跑着入场动画与尺寸布局，覆盖掉弹窗就永远是 0 尺寸，
        // 只剩子控件裸渲染在主界面上）。
        if (hosting || joined) {
            final RectWidget watcher = new RectWidget();
            dialog.addChild(watcher);
            watcher.setClickable(false).setBeforeRenderCallback(() -> {
                watcher.setBounds(0, 0);
                watcher.setAlpha(0f);
                if (!com.muoniumplayer.party.direct.MpDirect.isHosting()
                        && !com.muoniumplayer.party.direct.MpDirect.isJoined()) {
                    showDirectPage();
                }
            });
        }
        // 回程按来路区分：从「一起听」二级菜单的直连卡片跳来时，返回键回二级菜单
        // （那里才是不打断的路径）；从服务器房间大厅进来时照旧回大厅。
        addBackButton(dialog, directCameFromMenu ? this::returnToMenu : this::showOverview);
        // 副标题单行只有 ~388px 可用（TRIM 截断），长文案会丢字——
        // 非托管态的连接指引放进下方的 listenNote 行，这里只留短句。
        addDetailTitle(dialog, "直连房间",
                hosting ? "把邀请串发给好友，对方在「直连房间」页粘贴即可加入"
                        : joined ? "已连接到房主的直连房间，用「‹ 一起听」返回房间"
                        : "服务器没装一起听 mod？在这里开房或凭邀请串加入");

        // ── 托管态 ──────────────────────────────────────────────────────────
        if (hosting) {
            // 房主状态行：端口 + 在线成员数 + 密码。
            LabelWidget status = label(dialog, () -> {
                int members = com.muoniumplayer.party.direct.MpDirect.onlineMemberCount();
                return "端口 " + com.muoniumplayer.party.direct.MpDirect.port()
                        + "  ·  " + members + " 位好友在线  ·  密码 "
                        + com.muoniumplayer.party.direct.MpDirect.hostPassword();
            }, FontManager.pf12bold);
            status.setBeforeRenderCallback(() -> {
                status.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                status.setMaxWidth(Math.max(1, dialog.getWidth() - 40));
                status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                status.setPosition(20, 68);
            });

            // 地址输入框：穿透给什么就填什么；填过一次会记住，下次直接复制。
            LabelWidget hostCaption = label(dialog, () -> {
                String h = DirectRoomSettingsBridge.lastHost();
                return h.isEmpty() ? "邀请地址（填内网穿透给你的地址，或局域网 IP）"
                        : "邀请地址（已记住；换穿透地址时再改）";
            }, FontManager.pf10bold);
            hostCaption.setBeforeRenderCallback(() -> hostCaption
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(20, 94));

            final TextFieldWidget hostInput = inputField(dialog,
                    "例如 cn-zhejiang.xxx.com 或 192.168.1.5", 160, 20, 112, -1);
            hostInput.setText(DirectRoomSettingsBridge.lastHost());

            dialogHint(dialog, 162);

            // 复制整串：主按钮。地址没填时提示但不拦——有人只需要密码和端口口头说。
            btn(dialog, () -> System.currentTimeMillis() - copiedAt < COPIED_HINT_MILLIS
                            ? PartyIcons.CHECK : PartyIcons.KEY,
                    () -> System.currentTimeMillis() - copiedAt < COPIED_HINT_MILLIS
                            ? "已复制邀请串" : "复制邀请串", null, () -> {
                        String host = text(hostInput);
                        DirectRoomSettingsBridge.setLastHost(host);
                        String s = com.muoniumplayer.party.direct.MpDirect.inviteString(host);
                        if (s.isEmpty()) return;
                        KeyboardUtils.setClipboardString(s);
                        copiedAt = System.currentTimeMillis();
                    }, STYLE_PRIMARY, b -> {
                        double w = (Math.max(1, dialog.getWidth() - 40) - 16) / 3.0;
                        b.setBounds(w, 34);
                        b.setPosition(20, dialog.getHeight() - 96);
                    });
            // 只复制端口 + 密码（对方已经知道地址时用）。
            btn(dialog, () -> PartyIcons.KEY, () -> "复制端口密码", null, () -> {
                String s = com.muoniumplayer.party.direct.MpDirect.inviteString("");
                if (s.isEmpty()) return;
                KeyboardUtils.setClipboardString(s);
                hint = "已复制「端口#密码」：" + s;
                hintColor = OK_COLOR;
            }, STYLE_GHOST, b -> {
                double w = (Math.max(1, dialog.getWidth() - 40) - 16) / 3.0;
                b.setBounds(w, 34);
                b.setPosition(20 + w + 8, dialog.getHeight() - 96);
            });
            // 关房：终结动作，走完立刻回大厅。
            btn(dialog, () -> PartyIcons.CANCEL, () -> "关闭直连房间", null, () -> {
                com.muoniumplayer.party.direct.MpDirect.close();
                showOverview();
            }, STYLE_DANGER, b -> {
                double w = (Math.max(1, dialog.getWidth() - 40) - 16) / 3.0;
                b.setBounds(w, 34);
                b.setPosition(20 + (w + 8) * 2, dialog.getHeight() - 96);
            });
            return;
        }

        // ── 已加入态 ────────────────────────────────────────────────────────
        // joined 有自己的布局：此刻只有两件事有意义（回房间 / 断开重连）。
        // 落回「开房+加入」表单的话，玩家会踩到重入守卫的拒绝，也找不到断开的门。
        if (joined) {
            LabelWidget status = label(dialog, "已连接到好友的直连房间。", FontManager.pf14bold);
            status.setBeforeRenderCallback(() -> {
                status.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                status.setPosition(20, 72);
            });
            LabelWidget sub = label(dialog, "回房间用左上角的「‹ 返回」或顶部标签栏；想换房间或重连，先断开。",
                    FontManager.pf12);
            sub.setBeforeRenderCallback(() -> {
                sub.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                sub.setMaxWidth(Math.max(1, dialog.getWidth() - 40));
                sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                sub.setPosition(20, 96);
            });

            dialogHint(dialog);

            btn(dialog, () -> PartyIcons.CANCEL, () -> "断开连接", null, () -> {
                com.muoniumplayer.party.direct.MpDirect.close();
                showDirectPage();
            }, STYLE_DANGER, b -> {
                b.setBounds(Math.max(1, dialog.getWidth() - 40), 34);
                b.setPosition(20, dialog.getHeight() - 54);
            });
            return;
        }

        // ── 未托管态 ────────────────────────────────────────────────────────
        // 开房区。监听一律 0.0.0.0：局域网 IP 与 frp 回连两条路都通，
        // 走哪条由邀请串里的地址决定——因此没有「仅回环/可直连」开关。
        LabelWidget portCaption = label(dialog, "开一个直连房间 · 监听端口", FontManager.pf10bold);
        portCaption.setBeforeRenderCallback(() -> portCaption
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(20, 66));

        final TextFieldWidget portInput = inputField(dialog, "端口", 5, 20, 84, 150);
        portInput.getTextField().setMaxStringLength(5);
        portInput.setText(String.valueOf(com.muoniumplayer.party.direct.MpDirect
                .lastPortOr(com.muoniumplayer.party.direct.DirectRoomConfig.DEFAULT_PORT)));

        // 监听范围说明：替代原来那颗令人困惑的开关。
        LabelWidget listenNote = label(dialog, "同一局域网用你的内网 IP 直连；"
                + "公网好友用内网穿透（frp）——都能连", FontManager.pf10bold);
        listenNote.setBeforeRenderCallback(() -> {
            listenNote.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            listenNote.setMaxWidth(Math.max(1, dialog.getWidth() - 40 - 150 - 12));
            listenNote.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            listenNote.setPosition(20 + 150 + 12, inkTop(listenNote, 102.0));
        });

        // 加入区。
        LabelWidget joinCaption = label(dialog, "加入好友的直连房间", FontManager.pf10bold);
        joinCaption.setBeforeRenderCallback(() -> joinCaption
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(20, 138));

        final TextFieldWidget joinInput = inputField(dialog,
                "地址:端口#密码（好友发你的整串）", 160, 20, 156, -1);

        dialogHint(dialog, 206);

        // 底部动作行：开房（主）+ 加入。
        btn(dialog, () -> PartyIcons.ADD, () -> "开直连房间", null, () -> {
            String value = text(portInput);
            int port;
            try {
                port = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                hint = "端口要是数字";
                hintColor = ERROR_COLOR;
                return;
            }
            String err = com.muoniumplayer.party.direct.MpDirect.hostRoom(port, null);
            if (err != null) {
                hint = err;
                hintColor = ERROR_COLOR;
                return;
            }
            // 开好直接重进本页：托管态有地址输入和复制按钮，下一步顺理成章。
            showDirectPage();
        }, STYLE_PRIMARY, b -> {
            b.setBounds(120, 34);
            b.setPosition(20, dialog.getHeight() - 54);
        });

        btn(dialog, () -> PartyIcons.KEY, () -> "加入", null, () -> {
            String value = text(joinInput);
            if (value.isEmpty()) {
                hint = "请先粘贴好友发的邀请串";
                hintColor = ERROR_COLOR;
                return;
            }
            // 建连在后台（最长 16s），不能冻住渲染线程：先给「连接中」提示，
            // 结果回主线程后再刷新页面。
            hint = "正在连接好友的直连房间…";
            hintColor = 0xAEB5C4;
            String syncErr = com.muoniumplayer.party.direct.MpDirect.joinRoom(value, err -> {
                if (err != null) {
                    hint = err;
                    hintColor = ERROR_COLOR;
                } else {
                    showOverview();
                }
            });
            if (syncErr != null) {
                hint = syncErr;
                hintColor = ERROR_COLOR;
            }
        }, STYLE_GHOST, b -> {
            double w = Math.max(1, dialog.getWidth() - 40) - 120 - 10;
            b.setBounds(w, 34);
            b.setPosition(20 + 120 + 10, dialog.getHeight() - 54);
        });
    }

    /** 直连设置的小桥：UI 层不直接 import muonium-common 的类，走这里解包。 */
    private static final class DirectRoomSettingsBridge {
        static String lastHost() {
            try {
                return com.muoniumplayer.core.ncm.music.DirectRoomSettings.lastHost();
            } catch (Throwable ignored) {
                return "";
            }
        }

        static void setLastHost(String host) {
            try {
                com.muoniumplayer.core.ncm.music.DirectRoomSettings.setLastHost(host);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 链接点歌：整段分享文案 / 短链 / 纯歌曲 ID 都收。 */
    private void showLinkPage() {
        page = Page.LINK;
        field = null;
        hint = "支持整段分享文案、song?id=… 链接、163cn.tv 短链，或只有歌曲 ID";
        hintColor = 0xAEB5C4;
        getChildren().clear();

        final Panel dialog = createDialog(468, () -> 246.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "链接点歌", "解析在你本机完成，只把歌名与播放链接发给服务端");

        final TextFieldWidget input = inputField(dialog, "https://music.163.com/song?id=… 或 1234567", 512);
        // 单输入框页直接把焦点给它：进页面就能粘贴/打字。
        input.setFocused(true);

        dialogHint(dialog);

        btn(dialog, () -> PartyIcons.PLAY_NEXT, () -> "排队点歌", null, () -> {
            String value = text(input);
            if (value.isEmpty()) {
                hint = "请先粘贴链接或歌曲 ID";
                hintColor = ERROR_COLOR;
                return;
            }
            MpPartyClient.requestSongByLink(value);
            showOverview();
        }, STYLE_PRIMARY, b -> {
            double total = Math.max(1, dialog.getWidth() - 40);
            b.setBounds((total - 10) * .5, 34);
            b.setPosition(20, dialog.getHeight() - 54);
        });

        btn(dialog, () -> PartyIcons.PLAY_CIRCLE, () -> "立即播放", MpPartyClient::hasControl, () -> {
            String value = text(input);
            if (value.isEmpty()) {
                hint = "请先粘贴链接或歌曲 ID";
                hintColor = ERROR_COLOR;
                return;
            }
            MpPartyClient.playNowByLink(value);
            showOverview();
        }, STYLE_GHOST, b -> {
            double total = Math.max(1, dialog.getWidth() - 40);
            double w = (total - 10) * .5;
            b.setBounds(w, 34);
            b.setPosition(20 + w + 10, dialog.getHeight() - 54);
        });
    }

    /**
     * 选单页：把本机选得到的歌单列出来，一行一张，按下去就是「排进房间」。
     *
     * <p>这一页的话术全部围绕<b>优先级</b>，因为用户按下这颗按钮时唯一的意外就是「我灌的 100 首
     * 把别人的排队挤到哪去了」。所以副标题、页脚、按钮文字三处都在说同一件事：这批歌排在最后，
     * 谁点歌谁就先到它前面去；而它<b>不会</b>打断眼下正在播的那一首。</p>
     *
     * <p>列表是打开这一页时<b>取一次快照</b>的：账号歌单本来就是异步加载的内存快照，为了「刷新一下
     * 多出一行」而每帧去碰那些服务类既不划算也不安全（五棵树上各家的加载状态还不一样）。
     * 想要新的单子，回上一页再进来 —— 那正好也是用户在播放器侧栏里刚看过新单子的时机。</p>
     */
    private void showPlaylistPage() {
        page = Page.PLAYLIST;
        field = null;
        getChildren().clear();

        final List<MpRoomPlaylists.Item> items = MpRoomPlaylists.collect();

        final Panel dialog = createDialog(560, () -> 330.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "播放歌单",
                "整张单子排在最后：插队与别人的点歌都在它前面，正在播的那一首不会被打断");

        emptyNote(dialog, PartyIcons.GROUP,
                () -> "这里一张歌单都没有。可以先在播放器里建一张本地歌单，或者粘贴一段歌单分享链接导入。",
                () -> items.isEmpty(), 96.0);

        final ListView list = listView(dialog, () -> 76.0, PLAYLIST_ROWS, PLAYLIST_STRIDE,
                () -> items.size(), 52.0);
        for (int index = 0; index <= PLAYLIST_ROWS; index++) {
            buildPlaylistRow(list, index, items);
        }

        paneTip(dialog, () -> MpPartyClient.hasControl()
                        ? "想插到别人前面，用曲行上的「下一首播放」；这里排进去的一律垫在最后。"
                        : "这批歌排在所有人的点歌之后。谁按一次「下一首播放」都会到它前面。",
                MpPartyOverlay::secondary, () -> dialog.getHeight() - 22.0);
    }

    /** 选单页的一行：分组图标 + 歌单名 + 「来源 · 曲目数」+ 一颗排入/撤回按钮。 */
    private void buildPlaylistRow(final ListView list, final int slot,
                                  final List<MpRoomPlaylists.Item> items) {
        final Row bg = row(list, slot, PLAYLIST_STRIDE, 42.0,
                () -> playlistAt(list, slot, items) != null);

        icon(bg, () -> {
            // 已经在队列里的那批换个图标：这时候按「排入房间」只会换来一串「跳过 N 首已在队列」，
            // 而用户真想做的多半是收回来。
            MpRoomPlaylists.Item item = playlistAt(list, slot, items);
            return item != null && pendingCount(item) > 0
                    ? PartyIcons.QUEUE_MUSIC : PartyIcons.GROUP;
        }, ICON_M, () -> 24.0, () -> 21.0, MpPartyOverlay::secondary);

        LabelWidget name = label(bg, () -> {
            MpRoomPlaylists.Item item = playlistAt(list, slot, items);
            return item == null ? "" : item.title;
        }, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, bg.getWidth() - 190));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(40, 8);
        });

        LabelWidget sub = label(bg, () -> {
            MpRoomPlaylists.Item item = playlistAt(list, slot, items);
            if (item == null) return "";
            StringBuilder sb = new StringBuilder(item.group);
            if (has(item.subtitle)) sb.append("  ·  ").append(item.subtitle);
            int pending = pendingCount(item);
            if (pending > 0) sb.append("  ·  已排 ").append(pending).append(" 首");
            return sb.toString();
        }, FontManager.pf10bold);
        sub.setBeforeRenderCallback(() -> {
            sub.setColor(secondary());
            sub.setMaxWidth(Math.max(1, bg.getWidth() - 190));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(40, 25);
        });

        final Panel tools = rowTools(bg);

        btn(tools, () -> PartyIcons.PLAY_NEXT,
                () -> pendingCountOf(list, slot, items) > 0 ? "撤回" : "排入房间",
                MpPartyClient::canPlaylist, () -> {
            MpRoomPlaylists.Item item = playlistAt(list, slot, items);
            if (item == null) return;
            if (pendingCount(item) > 0) MpPartyClient.clearPlaylist(item.title);
            else MpPartyClient.requestPlaylist(item.playlist);
        }, STYLE_ROW_PRIMARY, b -> {
            b.setBounds(78, 26);
            b.setPosition(bg.getWidth() - 92, 8);
        });
    }

    private MpRoomPlaylists.Item playlistAt(final ListView list, final int slot,
                                            final List<MpRoomPlaylists.Item> items) {
        int index = list.base() + slot;
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /** 这一槽那张单子当前在队列里挂着多少首（槽位空着时 0）。 */
    private int pendingCountOf(final ListView list, final int slot,
                               final List<MpRoomPlaylists.Item> items) {
        MpRoomPlaylists.Item item = playlistAt(list, slot, items);
        return item == null ? 0 : pendingCount(item);
    }

    /**
     * 这张歌单在房间队列里还挂着多少首没播。
     *
     * <p>按<b>名字</b>比对，因为服务端认的就是名字（队列条目上的 {@code playlist}）。两张同名的
     * 单子会在界面上合并成「已排 N 首」，撤回时也会一起撤走 —— 这是拿名字当键的固有代价，换来的是
     * 服务端不必额外记「第几批」，而批次 id 在客户端本来就不存在。</p>
     */
    private int pendingCount(MpRoomPlaylists.Item item) {
        return item == null ? 0 : MpPartyClient.pendingOfPlaylist(item.title);
    }

    private void inputPage(final Page target, final String title, final String subtitle,
                           final String hintText, final String placeholder, final int maxLength,
                           final String confirmGlyph, final String confirmText,
                           final java.util.function.Consumer<String> action, final boolean required) {
        page = target;
        field = null;
        // 提示行与副标题不能是同一句话，否则同一段文字会在弹窗里出现两遍。
        hint = hintText;
        hintColor = 0xAEB5C4;
        getChildren().clear();

        final Panel dialog = createDialog(452, () -> 226.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, title, subtitle);

        final TextFieldWidget input = inputField(dialog, placeholder, maxLength);
        // 单输入框页直接把焦点给它：进页面就能打字（多框的直连页走点击聚焦）。
        input.setFocused(true);

        dialogHint(dialog);

        btn(dialog, () -> confirmGlyph, () -> confirmText, null, () -> {
            String value = text(input);
            if (required && value.isEmpty()) {
                hint = "这里不能留空";
                hintColor = ERROR_COLOR;
                return;
            }
            action.accept(value);
        }, STYLE_PRIMARY, b -> {
            b.setBounds(Math.max(1, dialog.getWidth() - 40), 34);
            b.setPosition(20, dialog.getHeight() - 54);
        });
    }

    /**
     * 弹窗里那行提示。平时一枚信息图标，报错时换成警示图标。
     *
     * <p>「这里不能留空」这类反馈原来只是把字变红：低对比度主题下几乎看不出变化，色觉障碍的用户
     * 更是完全收不到。换图标是不依赖配色的那一半信息。</p>
     */
    private void dialogHint(final Panel dialog) {
        dialogHint(dialog, 139.0);
    }

    /** 定位版提示行：直连页有两个输入区，提示行要落在各自下面。 */
    private void dialogHint(final Panel dialog, final double y) {
        icon(dialog, () -> hintColor == ERROR_COLOR ? PartyIcons.WARNING : PartyIcons.INFO,
                ICON_S, () -> 20 + ICON_S * .5, () -> y, () -> hintColor);

        LabelWidget hintLabel = label(dialog, () -> hint, FontManager.pf12);
        hintLabel.setBeforeRenderCallback(() -> {
            hintLabel.setColor(hintColor);
            hintLabel.setMaxWidth(Math.max(1, dialog.getWidth() - 40 - ICON_S - ICON_GAP));
            hintLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hintLabel.setPosition(20 + ICON_S + ICON_GAP, inkTop(hintLabel, y));
        });
    }

    private TextFieldWidget inputField(final Panel dialog, String placeholder, int maxLength) {
        return inputField(dialog, placeholder, maxLength, 20, 88, -1);
    }

    /**
     * 定位版输入框：surface 落在 {@code (x, y)}；{@code width} 给 -1 就占满整行。
     *
     * <p>要挪输入框<b>只能挪 surface</b>——文本框自己的回调是按 surface 的相对坐标定位的，
     * 直接改文本框坐标会跟回调打架（surface 留在原地，文本框飞走，背景板还占着整行）。</p>
     *
     * <p>构造时<b>不抢焦点</b>：一页可能有多个输入框（直连页的端口 + 邀请串），默认聚焦
     * 只会让最后建的框吃掉键盘路由、其余框的文字以 disabled 色渲染得几乎看不见。
     * 焦点交给点击（TextField.mouseClicked 原生支持），未聚焦的文字用七成亮度的主色——
     * 看得清，又与聚焦态有区别。</p>
     */
    private TextFieldWidget inputField(final Panel dialog, String placeholder, int maxLength,
                                       final double x, final double y, final double width) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setClickable(false).setRadius(8).setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, width < 0 ? dialog.getWidth() - 40 : width), 36);
            surface.setPosition(x, y);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        stroke(surface, 8, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER), () -> .6);

        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(input);
        input.getTextField().setMaxStringLength(maxLength);
        input.setEnabled(true);
        input.setPlaceholder(placeholder);
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, surface.getWidth() - 16), Math.max(1, surface.getHeight() - 8));
            input.setPosition(surface.getRelativeX() + 8, surface.getRelativeY() + 4);
            // 聚焦全亮；未聚焦七成亮度——文字必须始终可读（「选中才看得见」就是这么来的）。
            boolean focused = input.isFocused();
            input.setColor(focused ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), 0, .3));
            input.setDisabledTextColor(secondary());
        });
        field = input;
        return input;
    }

    private static String text(TextFieldWidget input) {
        String value = input == null ? null : input.getText();
        return value == null ? "" : value.trim();
    }
    // ──────────────────────────── 弹窗骨架 ────────────────────────────

    /**
     * 建一个居中的弹窗。
     *
     * <p>入场是<b>淡入 + 上抬 12px</b>，高度第一帧直接落在目标值、之后才插值 —— 从 1px 拉开像卷帘门。
     * 遮罩自己也跟着淡，不然弹窗还在飘、背景已经黑透了。</p>
     */
    private Panel createDialog(final double preferredWidth, final DoubleSupplier preferredHeight,
                               final Runnable perFrame) {
        presentation = 0.0;
        dialogHeight = 0.0;

        final RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0).setClickable(false);
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .5));
        });
        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        final Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            presentation = Interpolations.interpolate(presentation, 1.0, .2f);
            double width = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double target = Math.max(1, Math.min(preferredHeight.getAsDouble(), getHeight() - 24));
            dialogHeight = dialogHeight <= 0.0 ? target
                    : Interpolations.interpolate(dialogHeight, target, .22f);
            dialog.setBounds(width, dialogHeight);
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - dialogHeight * .5 + (1.0 - presentation) * 12.0);
            if (perFrame != null) perFrame.run();
        });

        final RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false).setRadius(14).setBeforeRenderCallback(() -> {
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            background.setMargin(0);
            background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        background.setGlassSheet(GlassLook.Surface.PLAYER);

        // 顶部一道极淡的白色高光：把标题栏那一段轻轻托起来，弹窗就不是一块死板。
        final Wash gloss = new Wash(false, 14.0);
        background.addChild(gloss);
        gloss.setBeforeRenderCallback(() -> {
            gloss.tint(0xFFFFFF, 0xFFFFFF);
            gloss.alphas(.055, 0.0);
            gloss.setBounds(Math.max(1, background.getWidth()), 104);
            gloss.setPosition(0, 0);
        });

        stroke(background, 14, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER), () -> .9);
        return dialog;
    }

    /** 一条发丝分隔线。左右都留白，横贯整块会显得廉价。 */
    private void divider(final Panel dialog, final DoubleSupplier y) {
        final RectWidget line = new RectWidget();
        dialog.addChild(line);
        line.setClickable(false).setBeforeRenderCallback(() -> {
            line.setBounds(Math.max(1, dialog.getWidth() - PAD * 2), 1);
            line.setPosition(PAD, y.getAsDouble());
            line.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            line.setAlpha(.55f);
        });
    }

    private void addDetailTitle(final Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = label(dialog, titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(60, 16));

        LabelWidget subtitle = label(dialog, subtitleText, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(secondary());
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 80));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(60, 42);
        });
    }

    private void addBackButton(final Panel dialog, final Runnable action) {
        final double[] hover = new double[1];
        final RoundedButtonWidget back = new RoundedButtonWidget(() -> "", FontManager.pf12bold);
        dialog.addChild(back);
        icon(back, PartyIcons.CHEVRON_LEFT, ICON_M, () -> 15.0, () -> 13.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        back.setRadius(9);
        back.setGlassRim(GlassLook.Surface.PLAYER);
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        back.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], back.isHovering() ? 1.0 : 0.0, .25f);
            back.setBounds(30, 26);
            back.setPosition(20, 16);
            back.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), .55 + hover[0] * .45));
            back.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .44f + (float) hover[0] * .42f));
        });
        stroke(back, 9, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER), () -> .6);
    }

    /**
     * 底部状态行：一枚说明状态的图标 + 一句话。
     *
     * <p>原来是一颗纯色小圆点：颜色只能区分「好 / 不好」，而且没有房间、被封禁、在等取流链接
     * 三种情况下那句话本来就可能是空的，于是屏幕左下角只剩一个孤零零的点。图标本身带语义，
     * 空文案时也讲得清。</p>
     */
    private void addStatusLine(final Panel dialog) {
        icon(dialog, () -> {
            if (MpPartyClient.isBanned()) return PartyIcons.BLOCK;
            if (MpPartyClient.waitingUrl()) return PartyIcons.HOURGLASS;
            if (MpPartyClient.lastMessageIsError()) return PartyIcons.WARNING;
            if (MpPartyClient.inRoom()) return PartyIcons.HEADPHONES;
            if (MpPartyClient.serverPresent()) return PartyIcons.CLOUD_DONE;
            return PartyIcons.CLOUD_OFF;
        }, ICON_S, () -> PAD + ICON_S * .5, () -> dialog.getHeight() - 18.0,
                MpPartyOverlay::statusColor);

        LabelWidget status = label(dialog, () -> {
            String message = MpPartyClient.lastMessage();
            if (has(message)) return message;
            if (MpPartyClient.isBanned()) return "你被禁止使用一起听，等封禁到期后才能建房 / 加入。";
            if (MpPartyClient.waitingUrl()) return "服务端在等有人补一条可用的取流链接…";
            if (MpPartyClient.inRoom()) return "房间已连接。";
            if (MpPartyClient.serverPresent()) return "已连上一起听服务端。";
            return "";
        }, FontManager.pf12);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor());
            status.setMaxWidth(Math.max(1, dialog.getWidth() - PAD * 2 - ICON_S - ICON_GAP));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(PAD + ICON_S + ICON_GAP, inkTop(status, dialog.getHeight() - 18.0));
        });
    }

    private static int statusColor() {
        if (MpPartyClient.lastMessageIsError() || MpPartyClient.isBanned()) return ERROR_COLOR;
        if (MpPartyClient.waitingUrl()) return WARN_COLOR;
        if (MpPartyClient.inRoom()) return OK_COLOR;
        return secondary();
    }

    /** 铺满整个弹窗的一段内容：本身不画东西，只是用来整段切显隐（坐标仍是弹窗坐标）。 */
    private Panel section(final Panel dialog) {
        final Panel panel = new Panel();
        dialog.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            panel.setBounds(Math.max(1, dialog.getWidth()), Math.max(1, dialog.getHeight()));
            panel.setPosition(0, 0);
        });
        return panel;
    }

    /**
     * 一个页签的内容区。
     *
     * <p>原点落在页签条下面，各页就不用记 {@link #CONTENT_TOP} 这个偏移。整层的 alpha 是
     * {@link #paneFade}，切页签时先淡出到 0 才换页 —— 同一时刻只有一页可见，不会两页叠着糊成一团。
     * 顺带带 8px 的落位位移，让「换页」这件事有方向感。</p>
     */
    private Panel pane(final Panel dialog) {
        // 裁剪是兜底而不是布局手段：列表已经会按高度收行数了，但房间页那几张卡片是定高的，
        // 视口再矮也只能被切。宁可切掉，也不能让它盖在底部分隔线和状态行上面。
        final Panel panel = new Panel() {
            @Override
            protected void beforeRenderChildren(double mouseX, double mouseY) {
                ScissorClipManager.begin(getX(), getY(), getWidth(), getHeight());
            }

            @Override
            protected void afterRenderChildren(double mouseX, double mouseY) {
                ScissorClipManager.end();
            }
        };
        dialog.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            panel.setBounds(Math.max(1, dialog.getWidth()),
                    Math.max(1, dialog.getHeight() - CONTENT_TOP - CONTENT_BOTTOM));
            panel.setPosition(0, CONTENT_TOP + (1.0 - paneFade) * 8.0);
            panel.setAlpha((float) clamp01(paneFade));
        });
        return panel;
    }

    /** 内容区里的一层：和宿主同尺寸，用来在「大厅 / 房间」之间整块切换。 */
    private Panel box(final Panel host) {
        final Panel panel = new Panel();
        host.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            panel.setBounds(Math.max(1, host.getWidth()), Math.max(1, host.getHeight()));
            panel.setPosition(0, 0);
        });
        return panel;
    }

    // ──────────────────────────── 卡片与描边 ────────────────────────────

    private RoundedRectWidget card(final Panel host, final double top, final double height) {
        return cardAt(host, () -> top, height);
    }

    /** 「房间」页专用：纵坐标跟着横幅一起下移，否则投票卡升起来会压住房间名。 */
    private RoundedRectWidget roomCard(final Panel host, final double top, final double height) {
        return cardAt(host, () -> bannerInset + top, height);
    }

    private RoundedRectWidget cardAt(final Panel host, final DoubleSupplier top, final double height) {
        final RoundedRectWidget widget = new RoundedRectWidget();
        host.addChild(widget);
        widget.setClickable(false).setRadius(12);
        widget.setGlassRim(GlassLook.Surface.PLAYER);
        widget.setBeforeRenderCallback(() -> {
            widget.setBounds(Math.max(1, host.getWidth() - PAD * 2), height);
            widget.setPosition(PAD, top.getAsDouble());
            // 只比页面底色亮一点点，轮廓交给描边。
            widget.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), .82));
            widget.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, .82f));
        });
        stroke(widget, 12, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER), () -> .6);
        return widget;
    }

    /**
     * 给一个组件套一圈描边。
     *
     * <p>描边是<b>宿主的子组件</b>，所以宿主淡出时它一起淡出，位置也永远跟着宿主，不用再算一遍坐标。</p>
     */
    private void stroke(final AbstractWidget<?> host, final double radius, final IntSupplier color,
                       final DoubleSupplier alpha) {
        final Stroke outline = new Stroke();
        host.addChild(outline);
        outline.setClickable(false).setBeforeRenderCallback(() -> {
            outline.radius = radius;
            outline.setBounds(Math.max(1, host.getWidth()), Math.max(1, host.getHeight()));
            outline.setPosition(0, 0);
            outline.setColor(color.getAsInt());
            outline.setAlpha((float) clamp01(alpha.getAsDouble()));
        });
    }

    // ──────────────────────────── 列表 ────────────────────────────

    /**
     * 一块列表。
     *
     * <p>{@code maxRows} 是槽位上限，真正画几行由这一帧的可用高度决定：GUI 缩放大、窗口矮的时候
     * 弹窗会被夹到视口高度以内，而列表原来仍按 {@code maxRows × stride} 画满 —— 于是最后几行压在
     * 分隔线和状态行上面，就是「成员溢出画布、文字和下方文字重叠」。现在行数跟着高度收，
     * 收到只剩一行也仍然能滚，列表内容不会丢。</p>
     *
     * <p>{@code bottomReserve} 是这一页页脚（按钮排 / 说明行）要占掉的高度，列表不许伸进去。</p>
     */
    private ListView listView(final Panel host, final DoubleSupplier top, final int maxRows,
                              final double stride, final IntSupplier size,
                              final double bottomReserve) {
        final ListView list = new ListView(size, maxRows, stride, () -> {
            double room = host.getHeight() - top.getAsDouble() - bottomReserve;
            return (int) Math.floor(room / stride);
        });
        host.addChild(list);
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, host.getWidth() - PAD * 2), list.rows() * stride);
            list.setPosition(PAD, top.getAsDouble());
        });

        // 右侧的滚动指示条：滚完 1.5 秒自己淡掉，不常驻占地方。
        final RoundedRectWidget bar = new RoundedRectWidget();
        host.addChild(bar);
        bar.setClickable(false).setRadius(1.5).setBeforeRenderCallback(() -> {
            int shown = list.rows();
            int total = Math.max(1, size.getAsInt());
            double visible = Math.min(1.0, shown / (double) total);
            double travel = Math.max(1, list.getHeight() * (1.0 - visible));
            double maxScroll = Math.max(1, total - shown);
            bar.setBounds(3, Math.max(8, list.getHeight() * visible));
            bar.setPosition(list.getRelativeX() + list.getWidth() + 6,
                    list.getRelativeY() + travel * clamp01(list.actual / maxScroll));
            bar.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            // 能滚的时候常留一点底色：原来滚完 1.5 秒整条淡到全透明，
            // 于是「这块还能往下」这件事在静止时完全没有提示。
            bar.setAlpha(total > shown ? (float) (.28 + .5 * list.recency()) : 0f);
        });
        return list;
    }

    /**
     * 列表里的一行底板。
     *
     * <p>行是<b>固定槽位</b>：槽 {@code slot} 只负责画「当前第 base()+slot 条数据」，滚动时靠
     * {@code frac()} 做像素级位移，所以既没有每帧新建组件，也没有整行整行地跳。</p>
     */
    private Row row(final ListView list, final int slot, final double stride, final double height,
                    final BooleanSupplier hasData) {
        // 槽位总是按 maxRows 分配的；面板矮下来时可见行数变少，多出来的槽位在这里整条让位。
        final BooleanSupplier present = () -> slot <= list.rows() && hasData.getAsBoolean();
        final Row bg = new Row();
        list.addChild(bg);
        // 不可点：它铺满整行，可点的话会把行自己的悬停判定顶掉。
        bg.setClickable(false);
        bg.setRadius(10);
        bg.setGlassRim(GlassLook.Surface.PLAYER);
        bg.setBeforeRenderCallback(() -> {
            // 级联入场：切到这一页时行一条一条落下来，而不是整块一起闪出来。
            double gate = clamp01((paneFade - slot * .05) / .5);
            bg.reveal = Interpolations.interpolate(bg.reveal, present.getAsBoolean() ? gate : 0.0, .3f);
            bg.setBounds(Math.max(1, list.getWidth() - ROW_INSET * 2), height);
            bg.setPosition(ROW_INSET, slot * stride - list.frac() * stride);
            bg.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), .7 + bg.hover * .55));
            bg.setAlpha((float) bg.reveal);
            bg.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER,
                    (float) (.22 + .58 * bg.hover)));
        });
        // 悬停时扩展 ROW_INSET 像素说明「这一行可以动手」，但只向左长——
        // 右缘固定：右列锚着贡献/邀请按钮，两边一起扩就把它们推出行缘。
        bg.setTransformations(() -> {
            double w = Math.max(1, bg.getWidth());
            bg.scaleAtPos(bg.getX() + w - ROW_INSET, bg.getY() + bg.getHeight() * .5,
                    1.0 + (ROW_INSET * 2 / w) * bg.hover);
        });

        stroke(bg, 10, () -> mix(NCMScreen.getColor(NCMScreen.ColorType.BORDER),
                NCMScreen.getColor(NCMScreen.ColorType.ACCENT), bg.hover * .5),
                () -> .4 + bg.hover * .4);

        // 左侧一道主色细条，从行中间往两头长出来。
        final RoundedRectWidget edge = new RoundedRectWidget();
        bg.addChild(edge);
        edge.setClickable(false).setRadius(1.5).setBeforeRenderCallback(() -> {
            double tall = Math.max(.001, (height - 18.0) * bg.hover);
            edge.setBounds(3, tall);
            edge.setPosition(0, (height - tall) * .5);
            edge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            edge.setAlpha((float) bg.hover);
        });
        return bg;
    }

    /**
     * 行内按钮层。
     *
     * <p>{@code getAlpha()} 是逐层相乘的，所以这一层的 alpha 一改，整排行内按钮跟着变淡变实：
     * 平时半透明、鼠标进了这一行才实起来。五行 × 三颗按钮全都实心的话，整块列表就糊成按钮墙了。</p>
     */
    private Panel rowTools(final Row bg) {
        final Panel tools = new Panel();
        bg.addChild(tools);
        tools.setClickable(false);
        tools.setBeforeRenderCallback(() -> {
            tools.setBounds(Math.max(1, bg.getWidth()), Math.max(1, bg.getHeight()));
            tools.setPosition(0, 0);
            tools.setAlpha((float) (.48 + .52 * bg.hover));
        });
        return tools;
    }

    // ──────────────────────────── 小件 ────────────────────────────

    /** 一颗小徽标（会员 / 贡献）。不该出现时整颗淡掉，位置由调用方给。 */
    private void chip(final AbstractWidget<?> host, final Supplier<String> text,
                      final BooleanSupplier show, final Placement placement) {
        final double[] fade = new double[1];
        final RoundedRectWidget bg = new RoundedRectWidget();
        host.addChild(bg);
        bg.setClickable(false).setRadius(5).setBeforeRenderCallback(() -> {
            fade[0] = Interpolations.interpolate(fade[0], show.getAsBoolean() ? 1.0 : 0.0, .3f);
            // 从 38 加宽到 48：里面多了一枚 12 的图标。
            bg.setBounds(48, 16);
            placement.apply(bg);
            bg.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), VIP_COLOR, .28));
            bg.setAlpha((float) fade[0]);
        });
        stroke(bg, 5, () -> VIP_COLOR, () -> .75);

        // 图标 + 文字整组居中：桂冠奖章一眼就是「会员」，两个汉字得读。
        final double[] place = new double[2];
        final Panel arrange = new Panel();
        bg.addChild(arrange);
        arrange.setClickable(false).setBeforeRenderCallback(() -> {
            arrange.setBounds(1, 1);
            arrange.setPosition(0, 0);
            double textWidth = FontManager.pf10bold.getStringWidthD(text.get());
            double left = (bg.getWidth() - (ICON_S + 3.0 + textWidth)) * .5;
            place[0] = left + ICON_S * .5;
            place[1] = left + ICON_S + 3.0;
        });

        icon(bg, PartyIcons.VIP, ICON_S, () -> place[0], () -> 8.0, () -> VIP_COLOR);

        LabelWidget caption = label(bg, text, FontManager.pf10bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(VIP_COLOR);
            caption.setPosition(place[1], inkTop(caption, 8.0));
        });
    }

    /**
     * 一枚 automix 能力标记：跟 {@link #chip} 同一档字号、同一套描边口径，只是没有图标。
     *
     * <p>文案一律来自服务端下发的成员档案。这里<b>不许</b>顺手去问本机或 {@code RoomAutomix}
     * 「他到底能不能算」：这条回调每帧、每个行槽都要跑一遍，而那件事的背面是模型装没装、
     * 解码器起不起 —— 在渲染帧里现算能力是这套界面出过的真事故，不是假想的讲究。</p>
     */
    private void automixMark(final AbstractWidget<?> host, final Supplier<String> text,
                             final IntSupplier color, final Placement placement) {
        final double[] fade = new double[1];
        final RoundedRectWidget bg = new RoundedRectWidget();
        host.addChild(bg);
        bg.setClickable(false).setRadius(5).setBeforeRenderCallback(() -> {
            String label = text.get();
            fade[0] = Interpolations.interpolate(fade[0], has(label) ? 1.0 : 0.0, .3f);
            // 宽度跟着字数走：「模型」两个字不该占掉「信号处理」那么一格，行里横向本来就紧。
            bg.setBounds(Math.max(1, FontManager.pf10bold.getStringWidthD(label) + MARK_PAD * 2), 16);
            placement.apply(bg);
            bg.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    color.getAsInt(), .16));
            bg.setAlpha((float) fade[0]);
        });
        stroke(bg, 5, color, () -> .6);

        LabelWidget caption = label(bg, text, FontManager.pf10bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(color.getAsInt());
            caption.setPosition((bg.getWidth() - caption.getWidth()) * .5, inkTop(caption, 8.0));
        });
    }

    /**
     * automix 档位的配色。
     *
     * <p>「不支持」用次要色而不是警告色：那是个事实，不是谁做错了什么，标红了房主就会去踢人。
     * 能算的两档留在成功色那一族里，模型档实、信号处理档淡，扫一行就分得出谁高谁低。</p>
     */
    private static int automixTint(MpMember member) {
        if (member == null || !member.automixCapable) return secondary();
        return "model".equals(member.automixEngine)
                ? OK_COLOR : mix(OK_COLOR, secondary(), .45);
    }

    /**
     * 头像：按名字散列取一个底色 + 首字。
     *
     * <p>这套协议里没有皮肤头像可用，但成员 / 在线 / 邀请三页都需要「这一行是一个人」的视觉锚点。
     * 一片纯灰名字列表最难扫，有色块就好扫多了。</p>
     */
    private void avatar(final AbstractWidget<?> host, final Supplier<String> name,
                        final double x, final double y, final double size) {
        final RoundedRectWidget disc = new RoundedRectWidget();
        host.addChild(disc);
        disc.setClickable(false).setBeforeRenderCallback(() -> {
            String who = name.get();
            disc.setRadius(size * .5);
            disc.setBounds(size, size);
            disc.setPosition(x, y);
            disc.setColor(has(who)
                    ? AVATAR_COLORS[(who.hashCode() & 0x7FFFFFFF) % AVATAR_COLORS.length]
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            disc.setAlpha(has(who) ? .92f : 0f);
        });

        LabelWidget initial = label(disc, () -> initial(name.get()),
                size >= 32 ? FontManager.pf14bold : FontManager.pf12bold);
        initial.setBeforeRenderCallback(() -> {
            initial.setColor(0xFFFFFF);
            initial.setPosition((size - initial.getWidth()) * .5, (size - initial.getHeight()) * .5);
        });
    }

    // ──────────────────────────── 图标与文字对齐 ────────────────────────────

    /**
     * 按方格边长取图标字体。
     *
     * <p>四档一一对应 {@link #ICON_S}~{@link #ICON_XL}；传别的值会落到最近的一档，因为宁可差
     * 一档也不能靠缩放，那会让图标发虚。</p>
     */
    private static CFontRenderer iconFont(final double box) {
        if (box <= (ICON_S + ICON_M) * .5) return FontManager.partyIcon24;
        if (box <= (ICON_M + ICON_L) * .5) return FontManager.partyIcon32;
        if (box <= (ICON_L + ICON_XL) * .5) return FontManager.partyIcon40;
        return FontManager.partyIcon56;
    }

    /** 每个图标字号的 em 方格量一次就够；{@link GlyphInkMetrics} 自己也有缓存，这层只是省掉查表。 */
    private static final Map<CFontRenderer, GlyphInkMetrics> EM_SQUARE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    /**
     * 图标字体的 em 方格（{@link PartyIcons#FRAME} 的墨迹盒）。
     *
     * <p>为什么不按图标自己的墨迹盒居中：{@code CHEVRON_LEFT} 只占 0.27 em 宽，{@code GROUP}
     * 占 0.92 em，各自居中会让细长的那些被「撑」到和实心图形一样大、行里一高一低。量满格方块
     * 得到的就是 Material 的 24 设计网格，所有图标按它摆才在同一个网格上。</p>
     */
    private static GlyphInkMetrics emSquare(final CFontRenderer font) {
        if (font == null) return null;
        synchronized (EM_SQUARE) {
            if (EM_SQUARE.containsKey(font)) return EM_SQUARE.get(font);
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
        synchronized (EM_SQUARE) {
            EM_SQUARE.put(font, measured);
        }
        return measured;
    }

    /**
     * 一枚图标，墨迹中心落在宿主坐标系的 {@code (cx, cy)} 上。
     *
     * <p>回调里每帧重算位置而不是构造时定好：这一整块面板的坐标全是按父组件当帧宽度算出来的。</p>
     */
    private LabelWidget icon(final AbstractWidget<?> host, final Supplier<String> glyph,
                            final double box, final DoubleSupplier cx, final DoubleSupplier cy,
                            final IntSupplier color) {
        final CFontRenderer font = iconFont(box);
        final LabelWidget widget = label(host, glyph, font);
        widget.setBeforeRenderCallback(() -> {
            widget.setColor(color.getAsInt());
            GlyphInkMetrics square = emSquare(font);
            if (square == null) {
                // 量不出来就退回行盒居中：位置略偏，但图标仍在该在的地方。
                widget.setPosition(cx.getAsDouble() - widget.getWidth() * .5,
                        cy.getAsDouble() - widget.getHeight() * .5);
                return;
            }
            widget.setPosition(square.alignCenterX(cx.getAsDouble()),
                    square.alignCenterY(cy.getAsDouble()));
        });
        return widget;
    }

    /** {@link #icon(AbstractWidget, Supplier, double, DoubleSupplier, DoubleSupplier, IntSupplier)} 的定值版本。 */
    private LabelWidget icon(final AbstractWidget<?> host, final String glyph, final double box,
                             final DoubleSupplier cx, final DoubleSupplier cy, final IntSupplier color) {
        return icon(host, () -> glyph, box, cx, cy, color);
    }

    /**
     * 每帧跟着算不透明度的图标。
     *
     * <p>另开一档而不是让基础版也管 alpha：占位音符那类图标是构造时 {@code setAlpha} 定死的，
     * 基础版每帧再写一次就把它们冲掉了。</p>
     */
    private LabelWidget icon(final AbstractWidget<?> host, final String glyph, final double box,
                             final DoubleSupplier cx, final DoubleSupplier cy, final IntSupplier color,
                             final DoubleSupplier alpha) {
        final LabelWidget mark = icon(host, () -> glyph, box, cx, cy, color);
        final RoundedRectWidget alphaDriver = new RoundedRectWidget();
        host.addChild(alphaDriver);
        // 借一个不画东西的组件在图标之后跑一次，把 alpha 写进去：图标自己的回调已经占用了。
        alphaDriver.setClickable(false).setBeforeRenderCallback(() -> {
            alphaDriver.setBounds(0, 0);
            alphaDriver.setAlpha(0f);
            mark.setAlpha((float) clamp01(alpha.getAsDouble()));
        });
        return mark;
    }

    /**
     * 让一行文字的<b>墨迹</b>中心落在 {@code centerY} 上，返回该传给 {@code setPosition} 的 y。
     *
     * <p>这是整块面板里「文字看着没居中」的根因：数字只有大写高度，中文占满字身，两者共享基线，
     * 所以按行盒（{@code getHeight()}）摆出来的同一行里，时长「04:05」会明显比按钮上的「插播」
     * 高出好几像素。按墨迹摆就都落在视觉中线上。</p>
     */
    private static double inkTop(final LabelWidget label, final double centerY) {
        GlyphInkMetrics ink = GlyphInkMetrics.of(label.getFont(), inkProbe(label.getLabel()));
        if (ink == null) return centerY - label.getHeight() * .5;
        return ink.alignCenterY(centerY);
    }

    /**
     * 拿哪个字符去量这一行的墨迹带。
     *
     * <p>优先中日韩字符：它们占满字身，是这行字里最高的那一档，按它居中之后夹在中间的数字与
     * 拉丁字母也在带内。纯数字 / 纯拉丁的行才按首个可见字符量。</p>
     */
    private static char inkProbe(final String text) {
        char latin = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c <= ' ') continue;
                if (c >= 0x2E80) return c;
                if (latin == 0) latin = c;
            }
        }
        return latin == 0 ? '\u4e2d' : latin;
    }

    /** 一条「图标 + 文字」信息：{@link #metaChips} 的一格。 */
    private static final class Chip {
        private final Supplier<String> glyph;
        private final Supplier<String> text;
        private final IntSupplier color;

        private Chip(Supplier<String> glyph, Supplier<String> text, IntSupplier color) {
            this.glyph = glyph;
            this.text = text;
            this.color = color;
        }
    }

    private static Chip meta(final String glyph, final Supplier<String> text) {
        return new Chip(() -> glyph, text, MpPartyOverlay::secondary);
    }

    private static Chip meta(final String glyph, final Supplier<String> text, final IntSupplier color) {
        return new Chip(() -> glyph, text, color);
    }

    private static Chip meta(final Supplier<String> glyph, final Supplier<String> text, final IntSupplier color) {
        return new Chip(glyph, text, color);
    }

    /**
     * 一行「图标 + 数值」的信息条，代替「A  ·  B  ·  C」那种要逐字读的长句。
     *
     * <p>文字为空的格子整格让位（后面的往左收），所以「没有邀请码的频道」「没有会员的房间」不会
     * 留下空洞。排版每帧重算：数值随时会变，宽度也跟着变。</p>
     */
    private void metaChips(final AbstractWidget<?> host, final DoubleSupplier x, final DoubleSupplier y,
                           final double box, final CFontRenderer font, final Chip... chips) {
        metaChips(host, x, y, box, font, () -> Double.MAX_VALUE, chips);
    }

    /**
     * 带右边界的信息条：{@code maxX} 之后的 chip 整个隐藏（不换行——这行只有一层高）。
     * 房间页的 chips 行与右上角两颗按钮同高，窄窗口下不钳制就会钻进按钮底下。
     */
    private void metaChips(final AbstractWidget<?> host, final DoubleSupplier x, final DoubleSupplier y,
                           final double box, final CFontRenderer font,
                           final DoubleSupplier maxX, final Chip... chips) {
        final LabelWidget[] marks = new LabelWidget[chips.length];
        final LabelWidget[] texts = new LabelWidget[chips.length];
        final boolean[][] visible = new boolean[chips.length][1];
        final double[][] slot = new double[chips.length][2];

        // 排版组件不画东西，只把每格的左上角写进 slot[]。它必须<b>先</b>建：子组件按插入顺序
        // 渲染，建在后面就意味着图标与文字每帧都在用上一帧的坐标（第一帧还全挤在原点）。
        final Panel layout = new Panel();
        host.addChild(layout);
        layout.setClickable(false).setBeforeRenderCallback(() -> {
            layout.setBounds(1, 1);
            layout.setPosition(0, 0);
            double pen = x.getAsDouble();
            double line = y.getAsDouble();
            double limit = maxX.getAsDouble();
            for (int index = 0; index < chips.length; index++) {
                String value = chips[index].text.get();
                slot[index][0] = pen;
                slot[index][1] = line;
                boolean show = has(value) && (pen + box + ICON_GAP + font.getStringWidthD(value) <= limit);
                visible[index][0] = show;
                if (!show) continue;
                pen += box + ICON_GAP + font.getStringWidthD(value) + 14.0;
            }
        });

        for (int index = 0; index < chips.length; index++) {
            final int self = index;
            final Chip spec = chips[index];
            // 位置由下面的排版回调统一写进 slot[]，这里只负责颜色与显隐。
            final LabelWidget mark = label(host, spec.glyph, iconFont(box));
            marks[index] = mark;
            mark.setBeforeRenderCallback(() -> {
                boolean show = visible[self][0];
                mark.setColor(spec.color.getAsInt());
                mark.setAlpha(show ? .85f : 0f);
                if (!show) return;
                GlyphInkMetrics square = emSquare(iconFont(box));
                double cx = slot[self][0] + box * .5;
                double cy = slot[self][1];
                if (square == null) {
                    mark.setPosition(cx - mark.getWidth() * .5, cy - mark.getHeight() * .5);
                } else {
                    mark.setPosition(square.alignCenterX(cx), square.alignCenterY(cy));
                }
            });

            final LabelWidget text = label(host, spec.text, font);
            texts[index] = text;
            text.setBeforeRenderCallback(() -> {
                boolean show = visible[self][0];
                text.setAlpha(show ? 1f : 0f);
                if (!show) return;
                text.setColor(spec.color.getAsInt());
                text.setPosition(slot[self][0] + box + ICON_GAP, inkTop(text, slot[self][1]));
            });
        }
    }

    private LabelWidget label(final AbstractWidget<?> host, String text, CFontRenderer font) {
        LabelWidget widget = new LabelWidget(text, font);
        host.addChild(widget);
        widget.setClickable(false);
        return widget;
    }

    private LabelWidget label(final AbstractWidget<?> host, Supplier<String> text, CFontRenderer font) {
        LabelWidget widget = new LabelWidget(text, font);
        host.addChild(widget);
        widget.setClickable(false);
        return widget;
    }

    /**
     * 一个按钮。{@code enabled} 为 null 表示永远可按。
     *
     * <p>不可用时用 alpha + {@code setClickable(false)}，而不是 {@code setHidden}：被隐藏的组件
     * 不再执行自己的回调，也就再没有机会把自己显示回来。行内按钮与幽灵按钮不可用时直接透明
     * （那一格本来就可能是空的），页面级按钮只变暗。</p>
     */
    private RoundedButtonWidget btn(final AbstractWidget<?> host, final Supplier<String> text,
                                    final BooleanSupplier enabled, final Runnable action,
                                    final int style, final Layout layout) {
        return btn(host, null, text, enabled, action, style, layout);
    }

    /**
     * 带图标的按钮。{@code glyph} 为 null 就是纯文字，{@code text} 为 null / 空就是纯图标。
     *
     * <p>文字<b>不</b>用 {@link RoundedButtonWidget} 自带的那一个，而是自己挂一个 —— 自带的那个
     * 按行盒居中（{@code lw.center()}），而这块面板里所有文字都按墨迹居中，两套口径混用会让同一
     * 行里的按钮文字和列表文字差出几像素。自带的标签留着但内容为空，不画任何东西。</p>
     */
    private RoundedButtonWidget btn(final AbstractWidget<?> host, final Supplier<String> glyph,
                                    final Supplier<String> text,
                                    final BooleanSupplier enabled, final Runnable action,
                                    final int style, final Layout layout) {
        final double[] hover = new double[1];
        // 这一帧的字色：图标与文字都跟它走，免得配色逻辑写两遍。
        final int[] ink = {0xFFFFFF};
        final boolean row = style == STYLE_ROW || style == STYLE_ROW_DANGER || style == STYLE_ROW_PRIMARY;
        // 行内的三种按钮全部走描边：实心色块一行摆三颗，整块列表立刻变成一堵按钮墙。
        final boolean outlined = style != STYLE_PRIMARY && style != STYLE_DANGER;

        final RoundedButtonWidget button = new RoundedButtonWidget(() -> "", FontManager.pf12bold);
        host.addChild(button);
        button.setRadius(row ? 7 : 9);
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            // 不可用时也吃掉这次点击：否则会穿到底下的遮罩，把整个弹窗关掉。
            if (enabled != null && !enabled.getAsBoolean()) return true;
            action.run();
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            boolean usable = enabled == null || enabled.getAsBoolean();
            hover[0] = Interpolations.interpolate(hover[0], usable && button.isHovering() ? 1.0 : 0.0, .25f);
            layout.apply(button);

            int page = NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
            int surface = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
            int hovered = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            int input = NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND);
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            switch (style) {
                case STYLE_PRIMARY:
                    button.setColor(mix(accent, 0xFFFFFF, hover[0] * .18));
                    ink[0] = 0xFFFFFF;
                    break;
                case STYLE_DANGER:
                    button.setColor(mix(surface, ERROR_COLOR, .3 + hover[0] * .55));
                    ink[0] = 0xFFFFFF;
                    break;
                case STYLE_ROW:
                    button.setColor(mix(input, hovered, hover[0]));
                    ink[0] = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
                    break;
                case STYLE_ROW_PRIMARY:
                    // 平时只有一圈主色描边和主色字，鼠标进来才填实 —— 「可以点」不必靠色块喊。
                    button.setColor(mix(page, accent, .1 + hover[0] * .72));
                    ink[0] = mix(accent, 0xFFFFFF, hover[0]);
                    break;
                case STYLE_ROW_DANGER:
                    button.setColor(mix(page, ERROR_COLOR, .08 + hover[0] * .68));
                    ink[0] = mix(ERROR_COLOR, 0xFFFFFF, hover[0]);
                    break;
                case STYLE_GHOST:
                    // 幽灵：平时几乎只有一圈描边和字，悬停才浮出底色。
                    button.setColor(mix(page, hovered, hover[0] * .85));
                    ink[0] = mix(secondary(),
                            NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), hover[0]);
                    break;
                default:
                    button.setColor(mix(page, surface, .8 + hover[0] * .5));
                    ink[0] = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
                    break;
            }
            button.setClickable(usable);
            // getAlpha() 已经是「父 alpha × 自身 alpha」，这里只给自身那一份，不要再乘一次父级。
            button.setAlpha(usable ? 1f : (row || style == STYLE_GHOST ? 0f : .35f));
            float fill = outlined ? (float) (.20 + hover[0] * .58) : (float) (.72 + hover[0] * .20);
            button.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, fill));
        });
        final double box = row ? ICON_S : ICON_M;
        // 0 = 文字左边缘，1 = 图标中心 x，2 = 按钮中线 y。图标与文字作为<b>一组</b>居中，
        // 而不是各自居中 —— 后者会让「图标+文字」的重心偏到左边去。
        final double[] place = new double[3];
        final Panel arrange = new Panel();
        button.addChild(arrange);
        arrange.setClickable(false).setBeforeRenderCallback(() -> {
            arrange.setBounds(1, 1);
            arrange.setPosition(0, 0);
            String caption = text == null ? "" : text.get();
            double textWidth = has(caption) ? FontManager.pf12bold.getStringWidthD(caption) : 0.0;
            double iconWidth = glyph == null || !has(glyph.get()) ? 0.0 : box;
            double gap = iconWidth > 0 && textWidth > 0 ? ICON_GAP : 0.0;
            double left = (button.getWidth() - (iconWidth + gap + textWidth)) * .5;
            place[0] = left + iconWidth + gap;
            place[1] = left + iconWidth * .5;
            place[2] = button.getHeight() * .5;
        });

        if (text != null) {
            final LabelWidget caption = label(button, text, FontManager.pf12bold);
            caption.setBeforeRenderCallback(() -> {
                caption.setColor(ink[0]);
                caption.setPosition(place[0], inkTop(caption, place[2]));
            });
        }
        if (glyph != null) {
            icon(button, glyph, box, () -> place[1], () -> place[2], () -> ink[0]);
        }

        if (outlined) {
            stroke(button, row ? 7 : 9, () -> {
                int border = NCMScreen.getColor(NCMScreen.ColorType.BORDER);
                if (style == STYLE_ROW_PRIMARY) {
                    return mix(border, NCMScreen.getColor(NCMScreen.ColorType.ACCENT), .8);
                }
                if (style == STYLE_ROW_DANGER) return mix(border, ERROR_COLOR, .72);
                return mix(border, NCMScreen.getColor(NCMScreen.ColorType.ACCENT), hover[0] * .55);
            }, () -> .55 + hover[0] * .35);
        }
        return button;
    }

    // ──────────────────────────── 封面 ────────────────────────────

    /**
     * 取一首歌的封面纹理位置，顺手把没下载过的那张排进下载队列。
     *
     * <p>{@code MpTrack.cover} 一直在协议里，只是上一版没画。纹理还没到时返回的位置是空的，
     * {@link RoundedImageWidget} 自己会跳过绘制，底下的占位面露出来，不会是个洞。</p>
     */
    private Location coverLocation(final MpTrack track, final boolean big) {
        if (track == null) return null;
        Music music = musicOf(track);
        Location location = big ? music.getCoverLocation() : music.getSmallCoverLocation();
        if (location == null) return null;
        if (TextureManager.getInstance().getTexture(location) != null) return location;

        String key = track.key() + (big ? "@big" : "@small");
        if (coverAsked.add(key)) {
            String url = music.getCoverUrl(big ? 160 : 64);
            if (has(url)) {
                Textures.downloadTextureAndLoadAsync(url, location);
            } else {
                // 封面地址还没到（例如刚点的歌），下一帧再问一次。
                coverAsked.remove(key);
            }
        }
        return location;
    }

    private Music musicOf(final MpTrack track) {
        String key = track.key();
        Music music = musicCache.get(key);
        if (music == null) {
            // 房间换过几十首之后就没必要留着旧的了，整批丢掉比逐个淘汰简单可靠。
            if (musicCache.size() > 64) {
                musicCache.clear();
                coverAsked.clear();
            }
            music = track.toMusic();
            musicCache.put(key, music);
        }
        return music;
    }

    // ──────────────────────────── 纯工具 ────────────────────────────

    private static boolean has(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String mmss(long millis) {
        if (millis <= 0L) return "00:00";
        long total = millis / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        return (minutes < 10 ? "0" : "") + minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    /** 取一段文字的首个字（码点安全）。头像与封面占位共用：没有图就用字，别留空盒子。 */
    private static String initial(String value) {
        if (!has(value)) return "";
        String trimmed = value.trim();
        return new String(Character.toChars(trimmed.codePointAt(0))).toUpperCase(java.util.Locale.ROOT);
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : Math.min(value, 1.0);
    }

    private static int lighten(int color, double amount) {
        return mix(color, 0xFFFFFF, amount);
    }

    /**
     * 在两个 {@code 0xRRGGBB} 之间线性插值。
     *
     * <p>只混 RGB：{@code AbstractWidget.setColor(int)} 走 {@code new Color(int)}，高位 alpha
     * 会被丢掉（透明度由组件自己的 alpha 决定），传半透明色没有意义。</p>
     */
    private static int mix(int from, int to, double amount) {
        double t = clamp01(amount);
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, double t) {
        return Math.max(0, Math.min(255, (int) Math.round(from + (to - from) * t)));
    }

    // ──────────────────────────── 自定义绘制件 ────────────────────────────

    private interface Layout {
        void apply(RoundedButtonWidget button);
    }

    private interface Placement {
        void apply(RoundedRectWidget widget);
    }

    /**
     * 一圈 1px 描边。
     *
     * <p>整套界面的边界都靠它，而不是靠「比背景更亮的填充块」—— 一层层灰块叠灰块是上一版
     * 最难看的地方，描边能在不增加视觉重量的前提下把区域讲清楚。</p>
     */
    private static final class Stroke extends AbstractWidget<Stroke> {

        private double radius;
        private double thickness = 1.0;

        @Override
        public void onRender(double mouseX, double mouseY) {
            if (getAlpha() <= .004f) return;
            roundedOutline(getX(), getY(), getWidth(), getHeight(), radius, thickness, getColor());
        }
    }

    /**
     * 一块渐变面：给「正在放」「服务器频道」这类重点区域垫一层极淡的主色。
     *
     * <p>两端的 alpha 各自可调，并且都会再乘一次组件自己的 {@code getAlpha()}，
     * 所以整块淡出时渐变是整体变淡而不是先塌一半。</p>
     */
    private static final class Wash extends AbstractWidget<Wash> {

        private final boolean horizontal;
        private final double radius;
        private int from;
        private int to;
        private double fromAlpha = 1.0;
        private double toAlpha = 1.0;

        private Wash(boolean horizontal, double radius) {
            this.horizontal = horizontal;
            this.radius = radius;
            this.setClickable(false);
        }

        private Wash tint(int from, int to) {
            this.from = from;
            this.to = to;
            return this;
        }

        private Wash alphas(double from, double to) {
            this.fromAlpha = from;
            this.toAlpha = to;
            return this;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = getAlpha();
            if (alpha <= .004f) return;
            Color head = new Color(reAlpha(from, (float) (fromAlpha * alpha)), true);
            Color tail = new Color(reAlpha(to, (float) (toAlpha * alpha)), true);
            if (horizontal) {
                roundedRectGradientHorizontal(getX(), getY(), getWidth(), getHeight(), radius, head, tail);
            } else {
                roundedRectGradientVertical(getX(), getY(), getWidth(), getHeight(), radius, head, tail);
            }
        }
    }

    /**
     * 行底板。
     *
     * <p>自己算一次悬停：框架的 {@code hovering} 在<b>任意可点子组件</b>被悬停时会被清掉，
     * 而每一行里都有两三颗按钮，直接用它的话鼠标一挪到按钮上整行的悬停态就塌了。</p>
     */
    private final class Row extends RoundedRectWidget {

        private double hover;
        private double reveal;

        @Override
        public void onRender(double mouseX, double mouseY) {
            boolean inside = getAlpha() > .5f && testHovered(mouseX, mouseY);
            hover = Interpolations.interpolate(hover, inside ? 1.0 : 0.0, .25f);
            super.onRender(mouseX, mouseY);
        }
    }

    /**
     * 一块能滚的列表。
     *
     * <p>滚动位置是 {@code double}：{@link #base()} 决定槽位绑哪条数据，{@link #frac()} 决定这一帧
     * 整列往上挪多少像素。于是滚轮一格是「平滑滑过去」而不是「整行跳一下」，而组件数量始终是
     * {@code rows + 1} 个槽位，翻页不分配任何东西。</p>
     *
     * <p>滚轮挂在容器上而不是每一行上，这样整块区域（含行内按钮）都能滚。</p>
     */
    private final class ListView extends Panel {

        private final IntSupplier size;
        private final int maxRows;
        private final double stride;
        private final IntSupplier fit;

        private double target;
        private double actual;
        private long lastScrollAt;

        private ListView(IntSupplier size, int maxRows, double stride, IntSupplier fit) {
            this.size = size;
            this.maxRows = maxRows;
            this.stride = stride;
            this.fit = fit;
        }

        /** 这一帧真正画几行。至少一行 —— 高度再不够也要留一行出来给滚动，不能整块消失。 */
        private int rows() {
            int want = fit.getAsInt();
            if (want < 1) return 1;
            return Math.min(want, maxRows);
        }

        private double clampScroll(double value) {
            double max = Math.max(0, size.getAsInt() - rows());
            return value < 0.0 ? 0.0 : Math.min(value, max);
        }

        /** 最近一次滚动的新鲜度：用来让滚动条自己淡掉。 */
        private double recency() {
            long idle = System.currentTimeMillis() - lastScrollAt;
            return clamp01((1500.0 - idle) / 500.0);
        }

        private int base() {
            return (int) Math.floor(actual);
        }

        private double frac() {
            return actual - Math.floor(actual);
        }

        @Override
        public boolean canBeScrolled() {
            return size.getAsInt() > rows();
        }

        @Override
        public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
            if (dWheel == 0 || wheelConsumed) return true;
            wheelConsumed = true;
            lastScrollAt = System.currentTimeMillis();
            target = clampScroll(target + (dWheel > 0 ? -1 : 1));
            return true;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            target = clampScroll(target);
            actual = Interpolations.interpolate(actual, target, .7);
            super.onRender(mouseX, mouseY);
        }

        @Override
        protected void beforeRenderChildren(double mouseX, double mouseY) {
            // 模板抄 ScrollPanel：模板裁剪负责常规遮挡，投影裁剪是硬边界，
            // 圆角封面会开新的模板层级，但越不过这块矩形。
            ScissorClipManager.begin(getX(), getY(), getWidth(), getHeight());
            StencilClipManager.beginClip(() -> Rect.draw(getX(), getY(), getWidth(), getHeight(), -1));
        }

        @Override
        protected void afterRenderChildren(double mouseX, double mouseY) {
            try {
                StencilClipManager.endClip();
            } finally {
                ScissorClipManager.end();
            }
        }

        @Override
        protected boolean shouldClickChildren(double mouseX, double mouseY) {
            // 只有鼠标真的在列表里才把点击派下去：被裁掉的那半行不该还能点。
            return testHovered(mouseX, mouseY);
        }
    }
}