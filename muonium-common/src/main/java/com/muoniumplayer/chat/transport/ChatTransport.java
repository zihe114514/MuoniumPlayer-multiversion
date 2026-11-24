package com.muoniumplayer.chat.transport;

import com.google.gson.JsonObject;
import com.muoniumplayer.chat.ChatRuntime;
import com.muoniumplayer.chat.protocol.ChatFrame;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** WebSocket transport. It knows wire frames but does not own player/session policy. */
public final class ChatTransport implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, BACKOFF, STOPPED }

    private static final long REQUEST_TIMEOUT_MS = 12_000L;
    private static final long MIN_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final long MAX_MAINTENANCE_BACKOFF_MS = 300_000L;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MuoniumChat-Timer");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong backoffMs = new AtomicLong(MIN_BACKOFF_MS);
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingTypes = new ConcurrentHashMap<>();
    private volatile WebSocket socket;
    private volatile String endpoint;
    private volatile long activeGeneration;
    private volatile boolean reconnectEnabled;
    private volatile long minimumBackoffMs = MIN_BACKOFF_MS;
    private volatile Consumer<ChatFrame.Inbound> frameListener = ignored -> {};
    private volatile Consumer<State> stateListener = ignored -> {};
    private volatile Consumer<Throwable> errorListener = ignored -> {};

    public ChatTransport(String endpoint) {
        this.endpoint = endpoint;
    }

    public State state() { return state.get(); }
    public void setFrameListener(Consumer<ChatFrame.Inbound> listener) { frameListener = listener == null ? ignored -> {} : listener; }
    public void setStateListener(Consumer<State> listener) { stateListener = listener == null ? ignored -> {} : listener; }
    public void setErrorListener(Consumer<Throwable> listener) { errorListener = listener == null ? ignored -> {} : listener; }

    public synchronized void connect() {
        if (state.get() == State.STOPPED || state.get() == State.CONNECTED || state.get() == State.CONNECTING) return;
        reconnectEnabled = true;
        long current = generation.incrementAndGet();
        activeGeneration = current;
        setState(State.CONNECTING);
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(endpoint), new Listener(current))
                .whenComplete((connected, failure) -> {
                    if (current != activeGeneration) {
                        if (connected != null) connected.abort();
                        return;
                    }
                    if (failure != null) failConnection(current, failure);
                    else {
                        socket = connected;
                        backoffMs.set(MIN_BACKOFF_MS);
                        setState(State.CONNECTED);
                    }
                });
    }

    public void updateEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void send(String type, JsonObject data) {
        sendFrame(ChatFrame.request(type, data));
    }

    public void sendFrame(JsonObject frame) {
        WebSocket current = socket;
        if (current == null || state.get() != State.CONNECTED) throw new IllegalStateException("chat is not connected");
        current.sendText(frame.toString(), true).whenComplete((ignored, failure) -> {
            if (failure != null) reportError(failure);
        });
    }

    public void pauseReconnectForMaintenance(boolean enabled) {
        minimumBackoffMs = enabled ? MAX_MAINTENANCE_BACKOFF_MS : MIN_BACKOFF_MS;
        if (enabled) backoffMs.updateAndGet(current -> Math.max(current, MAX_MAINTENANCE_BACKOFF_MS));
        else backoffMs.set(MIN_BACKOFF_MS);
    }

    public void stopReconnect() {
        disconnect();
    }

    /** Requests with IDs resolve at the matching ack/auth response; server error frames fail the future. */
    public CompletableFuture<JsonObject> request(String type, JsonObject data) {
        if (state.get() != State.CONNECTED) return failed(new IllegalStateException("chat is not connected"));
        JsonObject frame = ChatFrame.request(type, data);
        String id = frame.get("id").getAsString();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        pending.put(id, result);
        pendingTypes.put(id, type);
        TIMER.schedule(() -> {
            CompletableFuture<JsonObject> waiting = pending.remove(id);
            if (waiting != null) waiting.completeExceptionally(new java.util.concurrent.TimeoutException("request timed out: " + type));
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        WebSocket current = socket;
        if (current == null) {
            pending.remove(id);
            result.completeExceptionally(new IllegalStateException("chat socket is unavailable"));
            return result;
        }
        current.sendText(frame.toString(), true).whenComplete((ignored, failure) -> {
            if (failure != null) {
                CompletableFuture<JsonObject> waiting = pending.remove(id);
                if (waiting != null) waiting.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void sendRequest(String type, JsonObject data) {
        WebSocket current = socket;
        if (current == null || state.get() != State.CONNECTED) throw new IllegalStateException("chat is not connected");
        current.sendText(ChatFrame.request(type, data).toString(), true).whenComplete((ignored, failure) -> {
            if (failure != null) reportError(failure);
        });
    }

    private void receive(long connectionGeneration, String text) {
        if (connectionGeneration != activeGeneration) return;
        ChatRuntime.executeMain(() -> receiveOnMain(connectionGeneration, text));
    }

    private void receiveOnMain(long connectionGeneration, String text) {
        if (connectionGeneration != activeGeneration) return;
        final ChatFrame.Inbound inbound;
        try {
            inbound = ChatFrame.parse(text);
        } catch (Exception invalid) {
            reportError(invalid);
            return;
        }
        if (inbound.id != null) {
            CompletableFuture<JsonObject> waiting = pending.remove(inbound.id);
            String requestType = pendingTypes.remove(inbound.id);
            if (waiting != null && isResponseFor(requestType, inbound.type)) {
                if ("err".equals(inbound.type)) waiting.completeExceptionally(new ChatRemoteException(inbound.data));
                else waiting.complete(inbound.data);
            } else if (waiting != null) {
                pending.put(inbound.id, waiting);
                if (requestType != null) pendingTypes.put(inbound.id, requestType);
            }
        }
        try {
            frameListener.accept(inbound);
        } catch (RuntimeException listenerFailure) {
            reportError(listenerFailure);
        }
    }

    private void failConnection(long current, Throwable failure) {
        if (current != activeGeneration) return;
        socket = null;
        failPending(failure);
        reportError(failure);
        if (reconnectEnabled) scheduleReconnect(current);
        else setState(State.DISCONNECTED);
    }

    private void scheduleReconnect(long failedGeneration) {
        if (!reconnectEnabled || state.get() == State.STOPPED) return;
        setState(State.BACKOFF);
        long currentDelay = backoffMs.getAndUpdate(current -> Math.min(
                Math.max(MAX_BACKOFF_MS, minimumBackoffMs), current * 2));
        long delay = Math.max(currentDelay, minimumBackoffMs);
        TIMER.schedule(() -> {
            if (reconnectEnabled && state.get() != State.STOPPED && activeGeneration == failedGeneration) {
                setState(State.DISCONNECTED);
                connect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public synchronized void disconnect() {
        reconnectEnabled = false;
        generation.incrementAndGet();
        activeGeneration = generation.get();
        WebSocket current = socket;
        socket = null;
        failPending(new IllegalStateException("chat disconnected"));
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
        setState(State.DISCONNECTED);
    }

    @Override
    public synchronized void close() {
        disconnect();
        setState(State.STOPPED);
    }

    private static boolean isResponseFor(String requestType, String responseType) {
        if ("err".equals(responseType)) return true;
        if (requestType == null) return false;
        switch (requestType) {
            case "auth.hello": return "ack".equals(responseType) || "auth.challenge".equals(responseType) || "auth.ok".equals(responseType);
            case "auth.offline.login":
            case "auth.offline.register":
            case "auth.sponsor.login":
            case "auth.sponsor.register":
            case "auth.resume": return "auth.ok".equals(responseType);
            case "chan.list": return "chan.list".equals(responseType);
            case "chan.join": return "chan.joined".equals(responseType);
            case "chan.leave": return "chan.left".equals(responseType);
            case "chan.history": return "history".equals(responseType);
            case "who": return "who".equals(responseType);
            case "msg.send": return "ack".equals(responseType);
            case "dm.send": return "ack".equals(responseType);
            case "ping": return "ack".equals(responseType);
            default: return "ack".equals(responseType);
        }
    }

    private void setState(State value) {
        State previous = state.getAndSet(value);
        if (previous != value) com.muoniumplayer.chat.ChatRuntime.executeMain(() -> stateListener.accept(value));
    }

    private void failPending(Throwable cause) {
        for (CompletableFuture<JsonObject> future : pending.values()) future.completeExceptionally(cause);
        pending.clear();
    }

    private void reportError(Throwable error) {
        com.muoniumplayer.chat.ChatRuntime.executeMain(() -> errorListener.accept(error));
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private final class Listener implements WebSocket.Listener {
        private final long listenerGeneration;
        private final StringBuilder fragments = new StringBuilder();
        private Listener(long listenerGeneration) { this.listenerGeneration = listenerGeneration; }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String message = null;
            synchronized (fragments) {
                if ((long) fragments.length() + data.length() > ChatFrame.MAX_SHARE_FRAME_BYTES) {
                    fragments.setLength(0);
                    webSocket.abort();
                    reportError(new IllegalArgumentException("websocket text frame exceeds 64 KiB"));
                    return null;
                }
                fragments.append(data);
                if (last) {
                    message = fragments.toString();
                    fragments.setLength(0);
                }
            }
            if (message != null) receive(listenerGeneration, message);
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.abort();
            reportError(new IllegalArgumentException("binary WebSocket frame rejected"));
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failConnection(listenerGeneration, new IllegalStateException("chat socket closed: " + statusCode));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failConnection(listenerGeneration, error);
        }
    }

    public static final class ChatRemoteException extends RuntimeException {
        public final String code;
        public final long retryAfterMs;
        ChatRemoteException(JsonObject data) {
            super(value(data, "message", "request rejected"));
            this.code = value(data, "code", "unknown");
            this.retryAfterMs = data.has("retryAfterMs") ? data.get("retryAfterMs").getAsLong() : 0L;
        }
        private static String value(JsonObject data, String key, String fallback) {
            return data != null && data.has(key) && data.get(key).isJsonPrimitive()
                    ? data.get(key).getAsString() : fallback;
        }
    }
}
