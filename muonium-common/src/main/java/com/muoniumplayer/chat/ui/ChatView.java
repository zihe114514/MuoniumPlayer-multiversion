package com.muoniumplayer.chat.ui;

import com.muoniumplayer.chat.ChatClient;
import com.muoniumplayer.chat.config.ChatConfig;
import com.muoniumplayer.chat.state.ChatIdentity;
import com.muoniumplayer.chat.state.ChatMessage;
import com.muoniumplayer.chat.state.ChatSnapshot;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared Skija component tree. Version and Screen APIs stay in each lane compat adapter. */
public final class ChatView implements AutoCloseable {
    private static final int BG = 0xFF10151B, SIDEBAR = 0xFF151C24, SURFACE = 0xFF1C2630;
    private static final int ALT = 0xFF202B36, ACCENT = 0xFF6EDAA0, TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFF8B98A5, DANGER = 0xFFF1767D;

    private final ChatClient client = ChatClient.get();
    private final Panel root = new Panel();
    private final ScrollPanel channelList = new ScrollPanel(), userList = new ScrollPanel(), messageList = new ScrollPanel();
    private final List<AbstractWidget<?>> channelRows = new ArrayList<>(), userRows = new ArrayList<>(), messageRows = new ArrayList<>();
    private final Consumer<ChatSnapshot> observer = this::onSnapshot;
    private final LabelWidget title = new LabelWidget("MuoniumChat", FontManager.pf18);
    private final LabelWidget subtitle = new LabelWidget("IRC", FontManager.pf12);
    private final LabelWidget status = new LabelWidget("未连接", FontManager.pf12);
    private final LabelWidget error = new LabelWidget("", FontManager.pf12);
    private final LabelWidget channelHeader = new LabelWidget("频道", FontManager.pf12);
    private final LabelWidget roomTitle = new LabelWidget("选择频道开始聊天", FontManager.pf18);
    private final LabelWidget hint = new LabelWidget("", FontManager.pf12);
    private final LabelWidget userHeader = new LabelWidget("在线用户", FontManager.pf12);
    private final TextFieldWidget endpoint = new TextFieldWidget(FontManager.pf14);
    private final TextFieldWidget username = new TextFieldWidget(FontManager.pf14);
    private final TextFieldWidget password = new TextFieldWidget(FontManager.pf14);
    private final TextFieldWidget qq = new TextFieldWidget(FontManager.pf14);
    private final TextFieldWidget composer = new TextFieldWidget(FontManager.pf14);
    private final TextFieldWidget channelPassword = new TextFieldWidget(FontManager.pf14);
    private final RoundedRectWidget background = new RoundedRectWidget();
    private final RoundedRectWidget sidebar = new RoundedRectWidget();
    private final RoundedRectWidget timeline = new RoundedRectWidget();
    private final RoundedRectWidget composerBg = new RoundedRectWidget();
    private final RoundedRectWidget authBg = new RoundedRectWidget();
    private ChatSnapshot snapshot = client.snapshot();
    private boolean registerMode;
    private boolean autoConnect = ChatConfig.autoConnect();
    private boolean closed;
    private String pendingPrivateChannel = "";
    private double scale = 1, authX, authY, authW, contentX, contentW, roomHeaderY, channelJoinY;
    private int lastW = -1, lastH = -1;

    public ChatView() {
        endpoint.setPlaceholder("wss://your-chat-server/ws").setText(ChatConfig.endpoint());
        username.setPlaceholder("用户名");
        password.setPlaceholder("密码");
        password.getTextField().isPassword = true;
        qq.setPlaceholder("QQ 联系方式");
        channelPassword.setPlaceholder("私密频道密码");
        channelPassword.getTextField().isPassword = true;
        composer.setPlaceholder("发送消息…");
        root.addChild(background, sidebar, timeline, composerBg, authBg, channelList, userList, messageList,
                title, subtitle, status, error, channelHeader, roomTitle, hint, userHeader,
                endpoint, username, password, qq, composer, channelPassword);
        addActions();
        client.addObserver(observer);
        refresh();
    }

    public Panel root() { return root; }

    public void render(double mouseX, double mouseY, int wheel) {
        if (closed) return;
        int w = (int) RenderSystem.getWidth(), h = (int) RenderSystem.getHeight();
        if (w != lastW || h != lastH) { layout(w, h); lastW = w; lastH = h; }
        root.renderWidget(mouseX, mouseY, wheel);
    }

    public void click(double x, double y, int button) {
        if (!closed) root.onMouseClickReceived(x, y, button);
    }

    public void release(int button) {
        if (closed || button != 0) return;
        endpoint.releaseMouse(button);
        username.releaseMouse(button);
        password.releaseMouse(button);
        qq.releaseMouse(button);
        composer.releaseMouse(button);
    }

    public boolean key(char character, int keyCode) {
        if (closed) return false;
        return root.onKeyTypedReceived(character, keyCode);
    }

    public boolean submitComposer() {
        if (closed || !composer.isFocused()) return false;
        if (!snapshot.directMessageTargetId.isEmpty()) client.sendCurrentDirectMessage(composer.getText());
        else if ("full".equals(snapshot.writeState) && snapshot.permissions.speak) client.sendText(composer.getText());
        else return false;
        composer.setText("");
        return true;
    }

    public void tick() { if (!closed) root.onTickReceived(); }

    private void layout(int width, int height) {
        double w = Math.max(320, width), h = Math.max(320, height);
        scale = Math.max(.62, Math.min(1.0, Math.min((w - 28) / 1000.0, (h - 24) / 680.0)));
        double panelW = Math.min(w - 24, 1040 * scale), panelH = Math.min(h - 20, 720 * scale);
        double x = (w - panelW) * .5, y = (h - panelH) * .5;
        double sideW = Math.max(170 * scale, Math.min(270 * scale, panelW * .29));
        double header = 74 * scale, composeH = Math.max(68, 76 * scale), gap = 12 * scale;
        contentX = x + sideW + gap;
        contentW = panelW - sideW - gap * 2;
        double authH = 138 * scale;
        authX = contentX + 8 * scale;
        authY = y + panelH - composeH - authH + 6 * scale;
        authW = contentW - 16 * scale;
        roomHeaderY = y + header;
        channelJoinY = y + panelH - composeH - 30 * scale;
        boolean ready = "READY".equals(snapshot.status);
        boolean direct = !snapshot.directMessageTargetId.isEmpty();
        boolean privateChannelPending = !pendingPrivateChannel.isEmpty();
        channelHeader.setHidden(!ready);
        userHeader.setHidden(!ready);
        hint.setHidden(ready);
        status.setHidden(!ready && !snapshot.error.isEmpty());
        error.setHidden(snapshot.error.isEmpty());

        root.setBounds(0, 0, w, h);
        background.setBounds(x, y, panelW, panelH).setRadius(18).setColor(BG);
        sidebar.setBounds(x, y, sideW, panelH).setRadius(18).setColor(SIDEBAR);
        timeline.setBounds(contentX, y, contentW, panelH - composeH).setRadius(14).setColor(BG).setHidden(!ready);
        composerBg.setBounds(contentX, y + panelH - composeH + 5 * scale, contentW, composeH - 10 * scale)
                .setRadius(12).setColor(SURFACE).setHidden(!ready);
        authBg.setBounds(contentX, y + panelH - composeH - authH + 2 * scale, contentW, authH - 8 * scale)
                .setRadius(12).setColor(ALT).setHidden(ready);
        title.setPosition(x + 18 * scale, y + 16 * scale);
        subtitle.setPosition(x + 18 * scale, y + 42 * scale);
        channelHeader.setPosition(x + 14 * scale, y + header);
        userHeader.setPosition(x + 14 * scale, y + panelH * .57);
        status.setPosition(contentX, y + 16 * scale);
        error.setPosition(contentX, y + 38 * scale);
        roomTitle.setPosition(contentX, roomHeaderY);
        hint.setPosition(contentX, y + header + 24 * scale);
        channelList.setBounds(x + 10 * scale, y + header + 24 * scale, sideW - 20 * scale,
                Math.max(70, panelH * .36)).setAlignment(ScrollPanel.Alignment.VERTICAL)
                .setSpacing(5 * scale).setScrollStrength(28 * scale);
        userList.setBounds(x + 10 * scale, y + panelH * .57 + 22 * scale, sideW - 20 * scale,
                Math.max(56, panelH * .40 - 24 * scale)).setAlignment(ScrollPanel.Alignment.VERTICAL)
                .setSpacing(4 * scale).setScrollStrength(28 * scale).setHidden(!ready);
        double contentBottom = y + panelH - composeH - (privateChannelPending ? 34 * scale : 0);
        messageList.setBounds(contentX, y + header + 54 * scale, contentW,
                ready ? contentBottom - (y + header + 54 * scale) : panelH - header - composeH - authH - 58 * scale)
                .setAlignment(ScrollPanel.Alignment.VERTICAL).setSpacing(7 * scale).setScrollStrength(30 * scale);
        endpoint.setBounds(authX, authY, authW - 78 * scale, 22 * scale).setColor(TEXT).setHidden(ready);
        username.setBounds(authX, authY + 28 * scale, authW * .46, 22 * scale).setColor(TEXT).setHidden(ready);
        password.setBounds(authX + authW * .48, authY + 28 * scale, authW * .52, 22 * scale).setColor(TEXT).setHidden(ready);
        qq.setBounds(authX, authY + 56 * scale, authW, 22 * scale).setColor(TEXT).setHidden(ready || !registerMode);
        channelPassword.setBounds(contentX + 8 * scale, channelJoinY, contentW * .58, 22 * scale)
                .setColor(TEXT).setHidden(!privateChannelPending);
        composer.setBounds(contentX + (direct ? 112 : 12) * scale, y + panelH - composeH + 16 * scale,
                contentW - (direct ? 124 : 24) * scale, composeH - 32 * scale).setColor(TEXT)
                .setEnabled(ready && (direct ? snapshot.permissions.dm : "full".equals(snapshot.writeState) && snapshot.permissions.speak))
                .setHidden(!ready);
    }

    private void addActions() {
        RoundedButtonWidget connect = button(() -> "连接", this::connect, 0xFF263744);
        RoundedButtonWidget auto = button(() -> autoConnect ? "启动时自动连接：开" : "启动时自动连接：关", () -> {
            boolean next = !autoConnect;
            if (ChatConfig.configure(endpoint.getText(), next)) autoConnect = next;
            else { error.setLabel("请输入有效的 wss:// 地址；ws:// 仅支持本机"); error.setColor(DANGER); }
            refresh();
        }, autoConnect ? ACCENT : ALT);
        RoundedButtonWidget mode = button(() -> registerMode ? "转到登录" : "创建离线账号", () -> { registerMode = !registerMode; refresh(); }, ALT);
        RoundedButtonWidget auth = button(() -> registerMode ? "注册离线账号" : "密码登录", this::authenticate, ACCENT);
        RoundedButtonWidget guest = button(() -> "游客只读进入", client::authenticateGuest, 0xFF263744);
        RoundedButtonWidget joinPrivate = button(() -> "使用密码加入", this::joinPrivateChannel, ACCENT);
        RoundedButtonWidget directMode = button(() -> snapshot.directMessageTargetId.isEmpty()
                ? "" : "返回频道", client::closeDirectMessage, ALT);
        root.addChild(connect, auto, mode, auth, guest, joinPrivate, directMode);
        connect.setHidden("READY".equals(snapshot.status));
        auto.setHidden("READY".equals(snapshot.status));
        mode.setHidden("READY".equals(snapshot.status));
        auth.setHidden("READY".equals(snapshot.status));
        guest.setHidden("READY".equals(snapshot.status));
        joinPrivate.setHidden(pendingPrivateChannel.isEmpty());
        directMode.setHidden(snapshot.directMessageTargetId.isEmpty());
        connect.setBeforeRenderCallback(() -> connect.setBounds(authX + authW - 76 * scale, authY, 68 * scale, 22 * scale));
        auto.setBeforeRenderCallback(() -> auto.setBounds(authX, authY + 28 * scale, 132 * scale, 22 * scale));
        mode.setBeforeRenderCallback(() -> mode.setBounds(authX + 140 * scale, authY + 28 * scale, 132 * scale, 22 * scale));
        auth.setBeforeRenderCallback(() -> auth.setBounds(authX, authY + 56 * scale, authW * .60, 24 * scale));
        guest.setBeforeRenderCallback(() -> guest.setBounds(authX + authW * .63, authY + 56 * scale, authW * .37, 24 * scale));
        joinPrivate.setBeforeRenderCallback(() -> joinPrivate.setBounds(contentX + contentW * .64,
                channelJoinY, contentW * .32, 24 * scale));
        directMode.setBeforeRenderCallback(() -> directMode.setBounds(contentX + contentW - 104 * scale,
                roomHeaderY, 96 * scale, 22 * scale));
    }

    private void joinPrivateChannel() {
        String channel = pendingPrivateChannel;
        if (channel.isEmpty()) return;
        if (!snapshot.permissions.joinPrivate) {
            error.setLabel("当前账号没有加入私密频道的权限");
            error.setColor(DANGER);
            return;
        }
        String secret = channelPassword.getText();
        try {
            client.joinChannel(channel, secret);
            pendingPrivateChannel = "";
        } finally {
            channelPassword.setText("");
            channelPassword.setFocused(false);
        }
        refresh();
    }

    private RoundedButtonWidget button(Supplier<String> label, Runnable action, int color) {
        RoundedButtonWidget button = new RoundedButtonWidget(label, FontManager.pf12);
        button.setColor(color).setRadius(7);
        button.setOnClickCallback((x, y, mouse) -> { if (mouse != 0) return false; action.run(); return true; });
        return button;
    }

    private void connect() {
        if (!ChatConfig.configure(endpoint.getText(), autoConnect)) {
            error.setLabel("请输入有效的 wss:// 服务地址；ws:// 仅支持本机开发服务");
            error.setColor(DANGER);
            return;
        }
        client.connect();
    }

    private void authenticate() {
        String user = username.getText(), secret = password.getText();
        try {
            if (registerMode) client.startOfflineRegister(user, secret, qq.getText());
            else client.startOfflineLogin(user, secret);
        } finally { password.setText(""); password.setFocused(false); }
    }

    private void onSnapshot(ChatSnapshot next) { snapshot = next; refresh(); }
    private void refresh() {
        status.setLabel(statusLabel(snapshot.status));
        error.setLabel(snapshot.error);
        error.setColor(snapshot.error.isEmpty() ? MUTED : DANGER);
        roomTitle.setLabel(snapshot.directMessageTargetId.isEmpty()
                ? (snapshot.activeChannelId.isEmpty() ? "选择频道开始聊天" : channelName(snapshot.activeChannelId))
                : "私聊 · " + snapshot.directMessageTargetName);
        hint.setLabel(permissionLabel());
        composer.setEnabled("READY".equals(snapshot.status) && "full".equals(snapshot.writeState)
                && (snapshot.directMessageTargetId.isEmpty() ? snapshot.permissions.speak : snapshot.permissions.dm));
        rebuildChannels();
        rebuildUsers();
        rebuildMessages();
    }

    private void rebuildChannels() {
        channelList.getChildren().removeAll(channelRows);
        channelRows.clear();
        for (ChatIdentity.Channel channel : snapshot.channels) {
            String label = channel.name + "   " + (channel.memberCount + channel.guestCount)
                    + (channel.hasPassword ? "  ·  锁" : "");
            RoundedButtonWidget row = button(() -> label, () -> {
                if (channel.hasPassword) {
                    pendingPrivateChannel = channel.name;
                    channelPassword.setText("");
                    refresh();
                } else {
                    client.joinChannel(channel.name);
                }
            }, channel.id.equals(snapshot.activeChannelId) ? 0xFF263744 : SIDEBAR);
            row.setRadius(8).setBeforeRenderCallback(() -> row.setBounds(4, row.getRelativeY(), channelList.getWidth() - 12, 32));
            channelList.addChild(row);
            channelRows.add(row);
        }
        channelList.invalidateLayout();
    }

    private void rebuildUsers() {
        userList.getChildren().removeAll(userRows);
        userRows.clear();
        for (com.muoniumplayer.chat.state.ChatUser user : snapshot.users) {
            if (user.accountId.isEmpty() || user.accountId.equals(snapshot.accountId)) continue;
            RoundedButtonWidget row = button(() -> user.displayName,
                    () -> client.openDirectMessage(user), snapshot.directMessageTargetId.equals(user.accountId)
                            ? 0xFF263744 : SIDEBAR);
            row.setRadius(7).setBeforeRenderCallback(() -> row.setBounds(4, row.getRelativeY(),
                    userList.getWidth() - 12, 27));
            userList.addChild(row);
            userRows.add(row);
        }
        userList.invalidateLayout();
    }

    private void rebuildMessages() {
        boolean direct = !snapshot.directMessageTargetId.isEmpty();
        List<ChatMessage> visible = direct ? snapshot.directMessages : snapshot.messages;
        boolean wasAtEnd = messageList.isScrolledToEnd();
        messageList.getChildren().removeAll(messageRows);
        messageRows.clear();
        for (ChatMessage message : visible) {
            RoundedRectWidget bubble = new RoundedRectWidget();
            bubble.setRadius(9).setColor(message.deleted ? 0xFF252B31 : SURFACE);
            LabelWidget author = new LabelWidget(message.authorName, FontManager.pf11);
            author.setColor(ACCENT).setClickable(false);
            String content = message.deleted ? "消息已被管理员删除" : messageText(message);
            LabelWidget body = new LabelWidget(content, FontManager.pf14);
            body.setColor(message.deleted ? MUTED : TEXT).setClickable(false);
            bubble.addChild(author, body);
            bubble.setBeforeRenderCallback(() -> {
                double bubbleW = Math.max(120, messageList.getWidth() * .82), textW = Math.max(1, bubbleW - 20);
                author.setPosition(10, 7).setMaxWidth(textW).setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                String[] wrapped = FontManager.pf14.fitWidth(safeText(content), textW);
                double bodyHeight = Math.max(1, wrapped.length) * FontManager.pf14.getFontHeight();
                body.setLabel(String.join("\n", wrapped));
                body.setPosition(10, 22).setMaxWidth(textW).setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                bubble.setBounds(4, bubble.getRelativeY(), bubbleW, Math.max(40, bodyHeight + 34));
            });
            messageList.addChild(bubble);
            messageRows.add(bubble);
        }
        messageList.invalidateLayout();
        if (wasAtEnd) messageList.scrollToEnd();
    }

    private static String messageText(ChatMessage message) {
        if (!"share".equals(message.kind)) return safeText(message.text);
        String summary = message.shareSummary.isEmpty() ? "分享内容" : message.shareSummary;
        return "[分享] " + summary + (message.text.isEmpty() ? "" : "\n" + safeText(message.text));
    }

    private String channelName(String id) { for (ChatIdentity.Channel c : snapshot.channels) if (c.id.equals(id)) return c.name; return id; }
    private String permissionLabel() {
        if (snapshot.status.equals("READY") && snapshot.permissions.browseChannels) return "点击在线账号开始私聊";
        if (!"READY".equals(snapshot.status)) return "连接服务器以开始聊天";
        if ("full".equals(snapshot.writeState) && snapshot.permissions.speak) return "消息由 MuoniumChat 服务端保存和同步";
        if ("guest".equals(snapshot.restrictedReason)) return "游客只读 · 注册或登录后发言";
        if ("muted".equals(snapshot.restrictedReason)) return "你目前处于禁言状态";
        if ("banned".equals(snapshot.restrictedReason)) return "账号已封禁，仅可处理工单";
        return "当前账号只读或没有发言权限";
    }
    private static String statusLabel(String value) {
        switch (value) {
            case "CONNECTING": return "正在连接";
            case "CONNECTED": return "已连接，等待认证";
            case "AUTHENTICATING": return "正在验证登录";
            case "READY": return "已连接";
            case "BACKOFF": return "连接中断，正在退避重连";
            case "ERROR": return "连接或认证失败";
            default: return "未连接";
        }
    }
    private static String safeText(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(4096, value.length()));
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if (!Character.isISOControl(cp) || cp == '\n' || cp == '\t') result.appendCodePoint(cp);
            i += Character.charCount(cp);
            if (result.length() >= 4096) break;
        }
        return result.toString();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        client.removeObserver(observer);
        client.cancelPendingAuthentication();
        password.setText(""); username.setText(""); qq.setText(""); endpoint.setText("");
        channelPassword.setText("");
        password.setFocused(false); username.setFocused(false); qq.setFocused(false); endpoint.setFocused(false);
        channelPassword.setFocused(false);
    }
}
