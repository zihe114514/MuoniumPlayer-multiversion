package com.muoniumplayer.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.chat.config.ChatConfig;
import com.muoniumplayer.chat.protocol.ChatFrame;
import com.muoniumplayer.chat.state.ChatIdentity;
import com.muoniumplayer.chat.state.ChatMessage;
import com.muoniumplayer.chat.state.ChatSnapshot;
import com.muoniumplayer.chat.state.ChatUser;
import com.muoniumplayer.chat.transport.ChatTransport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Shared MuoniumChat protocol/session/state facade. No Minecraft or loader types enter this package. */
public final class ChatClient implements AutoCloseable {
    public enum Status { STOPPED, CONNECTING, CONNECTED, AUTHENTICATING, READY, BACKOFF, ERROR }

    private static final int MAX_LOCAL_MESSAGES = 200;
    private static final ChatClient INSTANCE = new ChatClient();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final List<Consumer<ChatSnapshot>> observers = new CopyOnWriteArrayList<>();
    private final LinkedHashMap<String, ChatIdentity.Channel> channels = new LinkedHashMap<>();
    private final LinkedHashMap<String, ChatMessage> messages = new LinkedHashMap<>();
    private final LinkedHashMap<String, ChatMessage> directMessages = new LinkedHashMap<>();
    private final LinkedHashMap<String, ChatUser> users = new LinkedHashMap<>();

    private volatile ChatTransport transport;
    private volatile Status status = Status.STOPPED;
    private volatile String endpoint = "";
    private volatile String error = "请先配置聊天服务器地址";
    private volatile String accountId = "";
    private volatile String displayName = "访客";
    private volatile String accountKind = "guest";
    private volatile String writeState = "readonly";
    private volatile String restrictedReason = "guest";
    private volatile String activeChannelId = "";
    private volatile String pendingChannelId = "";
    private volatile String directMessageTargetId = "";
    private volatile String directMessageTargetName = "";
    private volatile boolean maintenanceMode;
    private volatile String pendingAuthMode = "";
    private volatile String pendingAuthRequestId = "";
    private volatile String refreshToken = ""; // Kept in memory only; never written to chat.json.
    private volatile long serverOffsetMs;
    private volatile long sessionGeneration;
    private volatile long channelGeneration;
    private volatile long expectedHistorySession = -1L;
    private volatile long expectedHistoryGeneration = -1L;
    private volatile int totalOnline;
    private volatile int totalGuests;
    private volatile boolean reconnectAsGuest;
    private volatile PendingOfflineAuth pendingOfflineAuth;
    private volatile ChatIdentity.Permissions permissions = new ChatIdentity.Permissions();
    private volatile ChatIdentity.Limits limits = new ChatIdentity.Limits();

    private ChatClient() {}

    public static ChatClient get() { return INSTANCE; }

    public synchronized void initialize() {
        ChatConfig.load();
        String configured = ChatConfig.endpoint();
        if (configured == null || configured.isEmpty()) {
            endpoint = "";
            status = Status.STOPPED;
            error = "请在设置中配置 MuoniumChat 服务器地址";
            publish();
            return;
        }
        configure(configured, ChatConfig.autoConnect());
    }

    public synchronized void configure(String address, boolean connectOnStartup) {
        String normalized = ChatConfig.normalizeEndpoint(address);
        if (normalized == null) {
            error = "请输入有效的 wss:// 地址；ws:// 仅允许本机开发服务器";
            publish();
            return;
        }
        boolean changed = !normalized.equals(endpoint);
        endpoint = normalized;
        if (!changed && transport != null) {
            if (connectOnStartup && (status == Status.STOPPED || status == Status.ERROR)) connect();
            else publish();
            return;
        }

        connectionGeneration.incrementAndGet();
        sessionGeneration++;
        channelGeneration++;
        ChatTransport previous = transport;
        transport = null;
        if (previous != null) previous.close();
        clearPendingAuthentication();
        resetIdentity();
        clearCollections();
        long owner = connectionGeneration.get();
        ChatTransport created = new ChatTransport(normalized);
        created.setFrameListener(frame -> onFrame(owner, frame));
        created.setStateListener(value -> onTransportState(owner, value));
        created.setErrorListener(failure -> {
            if (owner == connectionGeneration.get()) setError(safeMessage(failure));
        });
        transport = created;
        status = Status.STOPPED;
        error = "";
        if (connectOnStartup) connect();
        else publish();
    }

    public synchronized void connect() {
        if (endpoint.isEmpty()) {
            setError("请先配置聊天服务器地址");
            return;
        }
        if (transport == null) configure(endpoint, false);
        ChatTransport current = transport;
        if (current == null) return;
        error = "";
        current.connect();
        publish();
    }

    public synchronized void disconnect() {
        reconnectAsGuest = false;
        connectionGeneration.incrementAndGet();
        sessionGeneration++;
        ChatTransport current = transport;
        transport = null;
        if (current != null) current.close();
        clearPendingAuthentication();
        resetIdentity();
        clearCollections();
        status = Status.STOPPED;
        publish();
    }

    public void authenticateGuest() {
        if (status != Status.CONNECTED) { setError("请先连接聊天服务器"); return; }
        reconnectAsGuest = true;
        pendingAuthRequestId = "";
        sendHello("guest");
    }

    public void startOfflineLogin(String username, String password) {
        String user = normalizeUsername(username);
        if (!validateCredentials(user, password)) return;
        if (status != Status.CONNECTED && status != Status.READY) { setError("请先连接聊天服务器"); return; }
        reconnectAsGuest = false;
        invalidateAuthenticationAttempt();
        PendingOfflineAuth pending = new PendingOfflineAuth(user, password, "");
        if (status == Status.CONNECTED) beginOfflineHello(pending, "login");
        else sendOfflineCredentials(pending, "login");
    }

    public void startOfflineRegister(String username, String password, String qq) {
        String user = normalizeUsername(username);
        String contact = qq == null ? "" : qq.trim();
        if (!validateCredentials(user, password)) return;
        if (!contact.matches("[0-9]{5,11}")) { setError("QQ 必须是 5–11 位数字，仅用于联系"); return; }
        if (status != Status.CONNECTED && status != Status.READY) { setError("请先连接聊天服务器"); return; }
        reconnectAsGuest = false;
        invalidateAuthenticationAttempt();
        PendingOfflineAuth pending = new PendingOfflineAuth(user, password, contact);
        if (status == Status.CONNECTED) beginOfflineHello(pending, "register");
        else sendOfflineCredentials(pending, "register");
    }

    public void startSponsorLogin(String username, String password) {
        if (!normalizeUsername(username).matches("[A-Za-z0-9_]{3,16}") || password == null
                || password.length() < 8 || password.length() > 128) {
            setError("请输入有效的赞助账号与密码");
            return;
        }
        setError("赞助登录依赖连接绑定的设备私钥签名；该密钥能力尚未安全接入，暂不可用");
    }

    public void cancelPendingAuthentication() {
        invalidateAuthenticationAttempt();
        if (status == Status.AUTHENTICATING) status = Status.CONNECTED;
        publish();
    }

    private void invalidateAuthenticationAttempt() {
        clearPendingAuthentication();
        pendingAuthRequestId = "";
    }

    private void sendHello(String mode) {
        status = Status.AUTHENTICATING;
        error = "";
        publish();
        JsonObject frame = ChatFrame.request("auth.hello", ChatFrame.data(
                "clientVersion", ChatRuntime.clientVersion(), "protocolVersion", ChatFrame.PROTOCOL_VERSION,
                "mcVersion", ChatRuntime.minecraftVersion(), "loader", ChatRuntime.loader(),
                "loaderVersion", ChatRuntime.loaderVersion(), "mode", mode,
                "platform", ChatRuntime.platform(), "osVersion", ChatRuntime.osVersion()));
        pendingAuthRequestId = frame.get("id").getAsString();
        sendFrame(frame);
    }

    private void beginOfflineHello(PendingOfflineAuth pending, String operation) {
        clearPendingAuthentication();
        pendingOfflineAuth = pending;
        pendingAuthMode = "offline_" + operation;
        sendHello("offline");
    }

    private void sendOfflineCredentials(PendingOfflineAuth pending, String operation) {
        char[] chars = pending.takePassword();
        String oneShotPassword = new String(chars);
        java.util.Arrays.fill(chars, (char) 0);
        pending.clear();
        if (pendingOfflineAuth == pending) pendingOfflineAuth = null;
        pendingAuthMode = "";
        status = Status.AUTHENTICATING;
        JsonObject body = "register".equals(operation)
                ? ChatFrame.data("username", pending.username, "password", oneShotPassword, "qq", pending.qq)
                : ChatFrame.data("username", pending.username, "password", oneShotPassword);
        String type = "register".equals(operation) ? "auth.offline.register" : "auth.offline.login";
        JsonObject frame = ChatFrame.request(type, body);
        pendingAuthRequestId = frame.get("id").getAsString();
        sendFrame(frame);
        publish();
    }

    public void joinChannel(String channel) { joinChannel(channel, ""); }

    public void joinChannel(String channel, String password) {
        if (channel == null || channel.trim().isEmpty()) return;
        String id = channel.trim();
        channelGeneration++;
        pendingChannelId = id;
        activeChannelId = "";
        synchronized (messages) { messages.clear(); }
        synchronized (users) { users.clear(); }
        publish();
        request("chan.join", ChatFrame.data("channel", id,
                password == null || password.isEmpty() ? null : password));
    }

    public void leaveChannel(String channel) {
        String id = channel == null || channel.trim().isEmpty() ? activeChannelId : channel.trim();
        if (!id.isEmpty()) request("chan.leave", ChatFrame.data("channel", id));
    }

    public void loadEarlier() {
        String channel = activeChannelId;
        String before = "";
        synchronized (messages) {
            for (ChatMessage message : messages.values()) if (!message.id.isEmpty()) { before = message.id; break; }
        }
        if (!channel.isEmpty() && !before.isEmpty()) request("chan.history", ChatFrame.data("channel", channel, "before", before, "limit", 50));
    }

    public void sendText(String text) { sendMessage("text", text); }
    public void sendAction(String text) { sendMessage("action", text); }

    private void sendMessage(String kind, String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return;
        if (activeChannelId.isEmpty()) { setError("请先加入频道"); return; }
        if (!"full".equals(writeState) || !permissions.speak) { setError(readOnlyMessage()); return; }
        if (text.codePointCount(0, text.length()) > limits.maxTextChars) { setError("内容超过当前上限 " + limits.maxTextChars + " 字"); return; }
        request("msg.send", ChatFrame.data("channel", activeChannelId, "kind", kind, "text", text));
    }

    public void sendDirect(String toAccountId, String text) {
        if (!permissions.dm || !"full".equals(writeState)) { setError("当前账号没有私信权限"); return; }
        if (toAccountId == null || toAccountId.trim().isEmpty()) { setError("私信需要先选择在线用户"); return; }
        String value = text == null ? "" : text.trim();
        if (!value.isEmpty()) request("dm.send", ChatFrame.data("toAccountId", toAccountId.trim(), "text", value));
    }

    public void openDirectMessage(ChatUser user) {
        if (!permissions.dm || !"full".equals(writeState)) { setError("当前账号没有私信权限"); return; }
        if (user == null || user.accountId.isEmpty()) { setError("游客没有可用的账号私信地址"); return; }
        directMessageTargetId = user.accountId;
        directMessageTargetName = user.displayName;
        publish();
    }

    public void closeDirectMessage() {
        directMessageTargetId = "";
        directMessageTargetName = "";
        publish();
    }

    public void sendCurrentDirectMessage(String text) {
        String target = directMessageTargetId;
        if (target.isEmpty()) { setError("请先从在线名单选择私信对象"); return; }
        if (!permissions.dm || !"full".equals(writeState)) { setError("当前账号没有私信权限"); return; }
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        CompletableFuture<JsonObject> response = request("dm.send",
                ChatFrame.data("toAccountId", target, "text", value));
        response.whenComplete((data, failure) -> {
            if (failure != null) return;
            ChatRuntime.executeMain(() -> {
                if (!target.equals(directMessageTargetId)) return;
                try {
                    String id = optionalString(data, "dmId", "");
                    if (id.isEmpty()) return;
                    ChatMessage sent = new ChatMessage(id, "@dm:" + target, accountId, displayName,
                            accountKind, "player", "dm", value, java.time.Instant.now().toString(),
                            "", "", "", false);
                    addDirectMessage(sent);
                } catch (ChatFrame.ProtocolException ignored) {
                }
            });
        });
    }

    public ChatSnapshot snapshot() {
        synchronized (channels) {
            synchronized (messages) {
                synchronized (directMessages) {
                    synchronized (users) {
                        List<ChatMessage> selectedDirect = new ArrayList<>();
                        String targetChannel = "@dm:" + directMessageTargetId;
                        if (!directMessageTargetId.isEmpty()) {
                            for (ChatMessage message : directMessages.values()) {
                                if (targetChannel.equals(message.channelId)) selectedDirect.add(message);
                            }
                        }
                        return new ChatSnapshot(endpoint, status.name(), error, accountId, displayName,
                                accountKind, writeState, restrictedReason, activeChannelId,
                                new ArrayList<>(channels.values()), new ArrayList<>(messages.values()),
                                selectedDirect, directMessageTargetId, directMessageTargetName,
                                new ArrayList<>(users.values()), permissions, limits, totalOnline, totalGuests);
                    }
                }
            }
        }
    }

    public void addObserver(Consumer<ChatSnapshot> observer) {
        if (observer == null) return;
        observers.add(observer);
        ChatRuntime.executeMain(() -> observer.accept(snapshot()));
    }
    public void removeObserver(Consumer<ChatSnapshot> observer) { observers.remove(observer); }

    public void setLoaderInfo(String loader, String version, String osName, String osRelease) {
        ChatRuntime.configure(loader, version, osName, osRelease);
    }

    private void onTransportState(long owner, ChatTransport.State next) {
        if (owner != connectionGeneration.get()) return;
        switch (next) {
            case CONNECTING: status = Status.CONNECTING; break;
            case CONNECTED:
                status = Status.CONNECTED;
                if (reconnectAsGuest) sendHello("guest");
                else if (!accountId.isEmpty()) { resetIdentity(); error = "连接恢复后请重新登录"; }
                break;
            case BACKOFF:
                status = Status.BACKOFF;
                if (!accountId.isEmpty()) { resetIdentity(); error = "连接断开，凭据未持久保存，请重连后重新登录"; }
                break;
            case STOPPED:
            case DISCONNECTED: status = Status.STOPPED; break;
        }
        publish();
    }

    private void onFrame(long owner, ChatFrame.Inbound frame) {
        if (owner != connectionGeneration.get()) return;
        try {
            switch (frame.type) {
                case "auth.challenge":
                    if (frame.id != null && !frame.id.equals(pendingAuthRequestId)) return;
                    invalidateAuthenticationAttempt();
                    status = Status.ERROR;
                    setError("当前身份需要设备签名挑战；该认证方式尚未接入");
                    break;
                case "auth.ok":
                    if (frame.id == null || !frame.id.equals(pendingAuthRequestId)) return;
                    invalidateAuthenticationAttempt();
                    applyAuthOk(frame.data);
                    break;
                case "auth.update": applyAuthUpdate(frame.data); break;
                case "ack": handleAck(frame.id, frame.data); break;
                case "err":
                    if (frame.id != null && frame.id.equals(pendingAuthRequestId)) {
                        invalidateAuthenticationAttempt();
                        if (status == Status.AUTHENTICATING) status = Status.CONNECTED;
                    }
                    setError(optionalString(frame.data, "message", "聊天请求失败"));
                    break;
                case "chan.list": applyChannelList(frame.data); break;
                case "chan.joined": applyJoined(frame.data); break;
                case "chan.left": applyLeft(frame.data); break;
                case "history": applyHistory(frame.data); break;
                case "msg.new": addMessage(parseMessage(frame.data)); break;
                case "msg.deleted": markDeleted(frame.data); break;
                case "dm.new": addDirectMessage(parseDirect(frame.data)); break;
                case "who": applyWho(frame.data); break;
                case "presence.count":
                    totalOnline = optionalInt(frame.data, "totalUsers", totalOnline, 0, 1_000_000);
                    totalGuests = optionalInt(frame.data, "totalGuests", totalGuests, 0, 1_000_000);
                    publish();
                    break;
                case "sys.maintenance":
                    maintenanceMode = optionalBoolean(frame.data, "enabled", true);
                    ChatTransport current = transport;
                    if (current != null) current.pauseReconnectForMaintenance(maintenanceMode);
                    setError(maintenanceMode
                            ? optionalString(frame.data, "message", "服务器维护中，连接重试间隔已延长") : "");
                    break;
                case "sys.kicked":
                    ChatTransport kicked = transport;
                    if (kicked != null) kicked.stopReconnect();
                    reconnectAsGuest = false;
                    invalidateAuthenticationAttempt();
                    sessionGeneration++;
                    resetIdentity();
                    synchronized (channels) { channels.clear(); }
                    clearCollections();
                    synchronized (directMessages) { directMessages.clear(); }
                    status = Status.ERROR;
                    setError("会话已被服务器撤销，请重新登录：" + optionalString(frame.data, "reason", "unknown"));
                    break;
                case "sys.notice": setError(optionalString(frame.data, "text", "服务器通知")); break;
                case "sys.punish": applyPunishment(frame.data); break;
                default: break; // Unknown additive server frames are ignored.
            }
        } catch (Exception invalid) {
            if ("history".equals(frame.type)) return; // History is independently authorized; malformed/denied history must not corrupt state.
            setError("服务端消息格式无效：" + safeMessage(invalid));
        }
    }

    private void handleAck(String requestId, JsonObject data) throws Exception {
        if (requestId == null || !requestId.equals(pendingAuthRequestId)) return;
        if (!("offline_register".equals(pendingAuthMode) || "offline_login".equals(pendingAuthMode))) return;
        if (!"offline".equals(optionalString(data, "mode", ""))) return;
        PendingOfflineAuth pending = pendingOfflineAuth;
        String operation = pendingAuthMode.endsWith("register") ? "register" : "login";
        if (pending != null) sendOfflineCredentials(pending, operation);
    }

    private void applyAuthOk(JsonObject data) throws Exception {
        String id = optionalString(data, "accountId", "");
        String name = requiredString(data, "displayName");
        String kind = requiredString(data, "kind");
        String write = requiredString(data, "writeState");
        String reason = optionalString(data, "restrictedReason", "");
        String token = requiredString(data, "refreshToken");
        int protocol = requiredInt(data, "protocolVersion", 1, 1000);
        if (protocol != ChatFrame.PROTOCOL_VERSION) throw new ChatFrame.ProtocolException("unsupported protocol version");
        requiredInt(data, "sessionTtlMs", 0, Integer.MAX_VALUE);
        requiredInt(data, "resumeWindowMs", 0, Integer.MAX_VALUE);
        long serverTime = parseServerTime(requiredString(data, "serverTime"));
        JsonObject caps = ChatFrame.object(data, "capabilities", true);
        ChatIdentity.Permissions nextPermissions = parsePermissions(ChatFrame.object(data, "permissions", true));
        ChatIdentity.Limits nextLimits = parseLimits(ChatFrame.object(data, "limits", true));
        JsonArray channelArray = ChatFrame.array(data, "channels", true);
        LinkedHashMap<String, ChatIdentity.Channel> nextChannels = new LinkedHashMap<>();
        for (JsonElement entry : channelArray) {
            if (!entry.isJsonObject()) throw new ChatFrame.ProtocolException("invalid channel entry");
            ChatIdentity.Channel channel = parseChannel(entry.getAsJsonObject());
            if (!channel.id.isEmpty()) nextChannels.put(channel.id, channel);
        }
        if (caps.size() == 0) throw new ChatFrame.ProtocolException("capabilities are empty");
        accountId = id;
        displayName = name;
        accountKind = kind;
        writeState = write;
        restrictedReason = reason;
        refreshToken = token;
        permissions = nextPermissions;
        limits = nextLimits;
        serverOffsetMs = serverTime - System.currentTimeMillis();
        synchronized (channels) { channels.clear(); channels.putAll(nextChannels); }
        sessionGeneration++;
        status = Status.READY;
        error = "";
        publish();
        if (permissions.browseChannels) {
            if (channels.isEmpty()) request("chan.list", new JsonObject());
            else if (activeChannelId.isEmpty()) joinChannel(channels.values().iterator().next().name);
        }
    }

    private void applyAuthUpdate(JsonObject data) throws Exception {
        ChatIdentity.Permissions nextPermissions = parsePermissions(ChatFrame.object(data, "permissions", true));
        ChatIdentity.Limits nextLimits = parseLimits(ChatFrame.object(data, "limits", true));
        String write = requiredString(data, "writeState");
        String reason = optionalString(data, "restrictedReason", "");
        ChatFrame.object(data, "capabilities", true);
        permissions = nextPermissions;
        limits = nextLimits;
        writeState = write;
        restrictedReason = reason;
        publish();
    }

    private void applyChannelList(JsonObject data) throws Exception {
        JsonArray array = ChatFrame.array(data, "channels", true);
        synchronized (channels) {
            channels.clear();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) throw new ChatFrame.ProtocolException("invalid channel entry");
                ChatIdentity.Channel channel = parseChannel(item.getAsJsonObject());
                if (!channel.id.isEmpty()) channels.put(channel.id, channel);
            }
        }
        publish();
        if (activeChannelId.isEmpty() && !channels.isEmpty()) joinChannel(channels.values().iterator().next().name);
    }

    private void applyJoined(JsonObject data) throws Exception {
        ChatIdentity.Channel channel = parseChannel(ChatFrame.object(data, "channel", true));
        String requested = pendingChannelId;
        if (!requested.isEmpty() && !requested.equals(channel.id) && !requested.equals(channel.name)) return;
        synchronized (channels) { channels.put(channel.id, channel); }
        activeChannelId = channel.id;
        pendingChannelId = "";
        expectedHistoryGeneration = permissions.readHistory ? channelGeneration : -1L;
        expectedHistorySession = permissions.readHistory ? sessionGeneration : -1L;
        synchronized (messages) { messages.clear(); }
        synchronized (directMessages) { directMessages.clear(); }
        synchronized (users) { users.clear(); }
        publish();
        if (permissions.viewOnline) request("who", ChatFrame.data("channel", channel.id, "cursor", "0", "limit", 100));
    }

    private void applyHistory(JsonObject data) throws Exception {
        String channel = requiredString(data, "channelId");
        if (channelGeneration != expectedHistoryGeneration || sessionGeneration != expectedHistorySession
                || !channel.equals(activeChannelId)) return;
        JsonArray items = ChatFrame.array(data, "messages", true);
        synchronized (messages) {
            LinkedHashMap<String, ChatMessage> merged = new LinkedHashMap<>();
            for (JsonElement item : items) {
                if (!item.isJsonObject()) throw new ChatFrame.ProtocolException("invalid history item");
                ChatMessage message = parseMessage(item.getAsJsonObject());
                if (!message.id.isEmpty()) merged.put(message.id, message);
            }
            merged.putAll(messages);
            while (merged.size() > MAX_LOCAL_MESSAGES) merged.remove(merged.keySet().iterator().next());
            messages.clear();
            messages.putAll(merged);
        }
        publish();
    }

    private void applyLeft(JsonObject data) throws Exception {
        String channel = requiredString(data, "channelId");
        synchronized (channels) { channels.remove(channel); }
        if (channel.equals(activeChannelId)) {
            activeChannelId = "";
            channelGeneration++;
            expectedHistoryGeneration = -1L;
            expectedHistorySession = -1L;
            synchronized (messages) { messages.clear(); }
            synchronized (users) { users.clear(); }
        }
        publish();
    }

    private void applyWho(JsonObject data) throws Exception {
        JsonArray list = ChatFrame.array(data, "users", true);
        synchronized (users) {
            users.clear();
            for (JsonElement item : list) {
                if (!item.isJsonObject()) throw new ChatFrame.ProtocolException("invalid user entry");
                JsonObject user = item.getAsJsonObject();
                String key = optionalString(user, "accountId", "");
                if (key.isEmpty()) key = optionalString(user, "displayName", "guest");
                users.put(key, new ChatUser(optionalString(user, "accountId", ""),
                        optionalString(user, "displayName", "访客"), optionalString(user, "kind", "guest"),
                        optionalString(user, "badge", "guest"), optionalBoolean(user, "guest", false)));
            }
        }
        publish();
    }

    private void applyPunishment(JsonObject data) throws Exception {
        String kind = optionalString(data, "kind", "");
        if (kind.contains("mute") || kind.contains("ban")) {
            writeState = "restricted";
            restrictedReason = kind.contains("mute") ? "muted" : "banned";
        }
        publish();
    }

    private void addMessage(ChatMessage message) {
        if (message.id.isEmpty()) return;
        synchronized (messages) {
            if (!message.channelId.isEmpty() && !message.channelId.equals(activeChannelId)) return;
            messages.put(message.id, message);
            while (messages.size() > MAX_LOCAL_MESSAGES) messages.remove(messages.keySet().iterator().next());
        }
        publish();
    }

    private void addDirectMessage(ChatMessage message) {
        if (message.id.isEmpty() || message.authorId.isEmpty()) return;
        synchronized (directMessages) {
            directMessages.put(message.id, message);
            while (directMessages.size() > MAX_LOCAL_MESSAGES) {
                directMessages.remove(directMessages.keySet().iterator().next());
            }
        }
        publish();
    }

    private void markDeleted(JsonObject data) throws Exception {
        String id = requiredString(data, "messageId");
        synchronized (messages) {
            ChatMessage old = messages.get(id);
            if (old != null) messages.put(id, new ChatMessage(old.id, old.channelId, old.authorId, old.authorName,
                    old.authorKind, old.badge, old.kind, "", old.createdAt, old.replyTo, true));
        }
        publish();
    }

    private ChatMessage parseMessage(JsonObject data) throws Exception {
        JsonObject user = ChatFrame.object(data, "user", true);
        String kind = requiredString(data, "kind");
        JsonObject share = null;
        if ("share".equals(kind)) {
            share = ChatFrame.object(data, "share", true);
            ChatFrame.requireKeys(share, java.util.Set.of("type", "source", "trackId", "title", "artists", "album",
                    "durationMs", "coverUrl", "playlistId", "name", "trackCount", "creator", "snapshot",
                    "facet", "desc", "shareCode", "schemaVersion", "mcRange"));
        }
        return new ChatMessage(requiredString(data, "id"), requiredString(data, "channelId"),
                optionalString(user, "accountId", ""), requiredString(user, "displayName"),
                optionalString(user, "kind", "unknown"), optionalString(user, "badge", "player"), kind,
                optionalString(data, "text", ""), requiredString(data, "createdAt"),
                optionalString(data, "replyTo", ""), share == null ? "" : optionalString(share, "type", "share"),
                share == null ? "" : summarizeShare(share), optionalBoolean(data, "deleted", false));
    }

    private static String summarizeShare(JsonObject share) throws ChatFrame.ProtocolException {
        String type = optionalString(share, "type", "share");
        String label = "playlist".equals(type) ? optionalString(share, "name", "歌单")
                : "preset".equals(type) ? optionalString(share, "name", "预设")
                : optionalString(share, "title", "歌曲");
        String creator = "track".equals(type) ? optionalString(share, "album", "")
                : optionalString(share, "creator", "");
        return cleanSummary(label, 120) + (creator.isEmpty() ? "" : " · " + cleanSummary(creator, 80));
    }

    private static String cleanSummary(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private ChatMessage parseDirect(JsonObject data) throws Exception {
        JsonObject from = ChatFrame.object(data, "from", true);
        String fromId = optionalString(from, "accountId", "");
        return new ChatMessage(requiredString(data, "id"), "@dm:" + fromId, fromId,
                requiredString(from, "displayName"), optionalString(from, "kind", "unknown"),
                optionalString(from, "badge", "player"), "dm", requiredString(data, "text"),
                requiredString(data, "createdAt"), "", "", "", false);
    }

    private ChatIdentity.Channel parseChannel(JsonObject data) throws Exception {
        return new ChatIdentity.Channel(requiredString(data, "id"), requiredString(data, "name"),
                requiredString(data, "type"), optionalString(data, "topic", ""),
                optionalInt(data, "memberCount", 0, 0, Integer.MAX_VALUE),
                optionalInt(data, "guestCount", 0, 0, Integer.MAX_VALUE),
                optionalBoolean(data, "hasPassword", false), optionalBoolean(data, "archived", false),
                optionalBoolean(data, "guestReadable", false));
    }

    private ChatIdentity.Permissions parsePermissions(JsonObject data) throws Exception {
        return new ChatIdentity.Permissions(requiredBoolean(data, "browseChannels"), requiredBoolean(data, "readHistory"),
                requiredBoolean(data, "viewOnline"), requiredBoolean(data, "joinPrivate"), requiredBoolean(data, "speak"),
                requiredBoolean(data, "action"), requiredBoolean(data, "dm"), requiredBoolean(data, "links"),
                requiredBoolean(data, "shareTrack"), requiredBoolean(data, "sharePlaylist"), requiredBoolean(data, "sharePreset"),
                requiredBoolean(data, "browsePresets"), requiredBoolean(data, "likePresets"));
    }

    private ChatIdentity.Limits parseLimits(JsonObject data) throws Exception {
        JsonObject messages = ChatFrame.object(data, "messages", true);
        return new ChatIdentity.Limits(requiredInt(data, "maxTextChars", 1, 4096),
                requiredInt(data, "minIntervalMs", 0, 86_400_000),
                requiredInt(messages, "minute", 1, 1_000_000), requiredInt(messages, "hour", 1, 1_000_000),
                requiredInt(messages, "day", 1, 1_000_000));
    }

    private static long parseServerTime(String text) throws ChatFrame.ProtocolException {
        try {
            long value = java.time.Instant.parse(text).toEpochMilli();
            long now = System.currentTimeMillis();
            if (Math.abs(value - now) > 30L * 24 * 60 * 60 * 1000) throw new IllegalArgumentException("server clock differs by more than 30 days");
            return value - now;
        } catch (RuntimeException invalid) {
            throw new ChatFrame.ProtocolException("invalid serverTime");
        }
    }

    private static boolean requiredBoolean(JsonObject object, String key) throws ChatFrame.ProtocolException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ChatFrame.ProtocolException("invalid boolean field " + key);
        }
        return value.getAsBoolean();
    }

    private CompletableFuture<JsonObject> request(String type, JsonObject data) {
        ChatTransport current = transport;
        if (current == null) {
            setError("聊天连接尚未初始化");
            CompletableFuture<JsonObject> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("chat transport is unavailable"));
            return failed;
        }
        long owner = connectionGeneration.get();
        CompletableFuture<JsonObject> result = current.request(type, data);
        result.whenComplete((response, failure) -> {
            if (failure == null || owner != connectionGeneration.get()) return;
            ChatRuntime.executeMain(() -> {
                if (owner == connectionGeneration.get()) setError(safeMessage(failure));
            });
        });
        return result;
    }

    private void sendFrame(JsonObject frame) {
        ChatTransport current = transport;
        if (current == null) { setError("聊天连接尚未初始化"); return; }
        try { current.sendFrame(frame); }
        catch (RuntimeException failure) { setError(safeMessage(failure)); }
    }

    private void send(String type, JsonObject data) {
        ChatTransport current = transport;
        if (current == null) { setError("聊天连接尚未初始化"); return; }
        try { current.send(type, data); }
        catch (RuntimeException failure) { setError(safeMessage(failure)); }
    }

    private void publish() {
        ChatSnapshot value = snapshot();
        ChatRuntime.executeMain(() -> {
            for (Consumer<ChatSnapshot> observer : observers) {
                try { observer.accept(value); } catch (RuntimeException ignored) { }
            }
        });
    }

    private void setError(String message) {
        error = message == null || message.trim().isEmpty() ? "聊天操作失败" : message;
        publish();
    }

    private String readOnlyMessage() {
        if ("muted".equals(restrictedReason)) return "你目前处于禁言状态";
        if ("banned".equals(restrictedReason)) return "账号已封禁，仅可处理工单";
        if ("sponsor_expired".equals(restrictedReason)) return "赞助状态已到期，当前为只读";
        if ("guest".equals(restrictedReason)) return "游客只读，请选择离线账号或赞助账号登录";
        return "当前账号只读或没有发言权限";
    }

    private static String normalizeUsername(String value) { return value == null ? "" : value.trim(); }

    private boolean validateCredentials(String username, String password) {
        if (!username.matches("[A-Za-z0-9_]{3,16}")) { setError("用户名须为 3–16 位英文、数字或下划线"); return false; }
        if (password == null || password.length() < 8 || password.length() > 128) { setError("密码长度须为 8–128 字符"); return false; }
        return true;
    }
    private static String requiredString(JsonObject object, String key) throws ChatFrame.ProtocolException { return ChatFrame.string(object, key, true, 4096); }
    private static String optionalString(JsonObject object, String key, String fallback) throws ChatFrame.ProtocolException {
        String value = ChatFrame.string(object, key, false, 4096); return value == null ? fallback : value;
    }
    private static int requiredInt(JsonObject object, String key, int min, int max) throws ChatFrame.ProtocolException {
        int value = ChatFrame.integer(object, key, true, 0);
        if (value < min || value > max) throw new ChatFrame.ProtocolException("invalid integer field " + key);
        return value;
    }
    private static int optionalInt(JsonObject object, String key, int fallback, int min, int max) throws ChatFrame.ProtocolException {
        int value = ChatFrame.integer(object, key, false, fallback);
        if (value < min || value > max) throw new ChatFrame.ProtocolException("invalid integer field " + key);
        return value;
    }
    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback) throws ChatFrame.ProtocolException {
        return ChatFrame.bool(object, key, fallback);
    }
    private static String safeMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
    }
    private static String blankToNull(String text) { return text == null || text.isEmpty() ? null : text; }

    private void clearPendingAuthentication() {
        PendingOfflineAuth pending = pendingOfflineAuth;
        pendingOfflineAuth = null;
        pendingAuthMode = "";
        if (pending != null) pending.clear();
    }

    private void resetIdentity() {
        accountId = ""; displayName = "访客"; accountKind = "guest";
        writeState = "readonly"; restrictedReason = "guest";
        permissions = new ChatIdentity.Permissions(); limits = new ChatIdentity.Limits();
        refreshToken = ""; serverOffsetMs = 0L;
    }

    private void clearCollections() {
        channelGeneration++;
        expectedHistoryGeneration = -1L;
        expectedHistorySession = -1L;
        pendingChannelId = "";
        synchronized (channels) { channels.clear(); }
        synchronized (messages) { messages.clear(); }
        synchronized (directMessages) { directMessages.clear(); }
        synchronized (users) { users.clear(); }
        activeChannelId = ""; totalOnline = 0; totalGuests = 0;
    }

    private static final class PendingOfflineAuth {
        final String username;
        private char[] password;
        final String qq;
        PendingOfflineAuth(String username, String password, String qq) {
            this.username = username;
            this.password = password == null ? new char[0] : password.toCharArray();
            this.qq = qq == null ? "" : qq;
        }
        char[] takePassword() { char[] value = password; password = new char[0]; return value; }
        void clear() { java.util.Arrays.fill(password, (char) 0); password = new char[0]; }
    }

    @Override public synchronized void close() {
        disconnect();
        status = Status.STOPPED;
        publish();
    }
}
