package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Optional host contract. Only JDK types cross the MuoniumConfig boundary; no loader or Compose dependency. */
public final class PresetConfigBridge {
    public static final int PROTOCOL_VERSION = PresetService.PROTOCOL_VERSION;
    private static final String LOCAL_FORMAT = "muonium.profile.music";
    private static volatile Function<String, Boolean> nativeUi;

    private PresetConfigBridge() { }

    public static int protocolVersion() { return PROTOCOL_VERSION; }

    /** Register after the native page is ready. Returning false from the opener preserves the built-in fallback. */
    public static synchronized boolean registerUi(int version, Function<String, Boolean> opener) {
        if (version != PROTOCOL_VERSION || opener == null) return false;
        if (nativeUi != null && nativeUi != opener) return false;
        nativeUi = opener;
        return true;
    }

    public static synchronized void unregisterUi(Function<String, Boolean> opener) {
        if (nativeUi == opener) nativeUi = null;
    }

    /** Called on the client main thread. The host must marshal its Compose navigation without blocking this thread. */
    public static boolean tryOpenUi(String mode) {
        Function<String, Boolean> opener = nativeUi;
        if (opener == null || !validMode(mode)) return false;
        try { return Boolean.TRUE.equals(opener.apply(mode)); }
        catch (RuntimeException | LinkageError failure) { return false; }
    }

    public static String capabilities() {
        JsonObject out = new JsonObject();
        out.addProperty("protocolVersion", PROTOCOL_VERSION);
        out.addProperty("nativeUiRegistered", nativeUi != null);
        out.addProperty("localStateVersion", 1);
        out.addProperty("requestLimit", PresetLimits.MAX_SHARE_TEXT_CHARS + 4096);
        out.addProperty("completionThread", "client-main");
        out.addProperty("profileLifecycleBound", false);
        return out.toString();
    }

    /** Shareable template actions only. Never blocks the calling UI thread or exports complete local settings. */
    public static CompletableFuture<String> request(String requestJson) {
        return enqueue(MultiThreadingUtil::runOnMainThread,
                () -> execute(PresetService.get(), requestJson));
    }

    /** Complete trusted LOCAL music state. Store in the host Profile directory; do not put it in a share code. */
    public static CompletableFuture<String> captureLocalState() {
        return enqueue(MultiThreadingUtil::runOnMainThread, () -> localEnvelope(PresetService.get()).toString());
    }

    /** The host owns Profile lifecycle, ordering and file I/O. This method only restores validated local state. */
    public static CompletableFuture<String> restoreLocalState(String localJson) {
        return enqueue(MultiThreadingUtil::runOnMainThread,
                () -> restoreLocal(PresetService.get(), localJson).toString());
    }

    static String execute(PresetService service, String text) {
        try {
            JsonObject request = parseObject(text, PresetLimits.MAX_SHARE_TEXT_CHARS + 4096);
            if (request.has("protocolVersion") && (!request.get("protocolVersion").isJsonPrimitive()
                    || !request.get("protocolVersion").getAsJsonPrimitive().isNumber()
                    || request.get("protocolVersion").getAsDouble() != PROTOCOL_VERSION))
                throw new IllegalArgumentException("预设接口版本不兼容");
            return service.actionJson(request).toString();
        } catch (RuntimeException failure) { return error(failure).toString(); }
    }

    static JsonObject localEnvelope(PresetService service) {
        JsonObject out = new JsonObject();
        out.addProperty("format", LOCAL_FORMAT);
        out.addProperty("version", 1);
        out.addProperty("localOnly", true);
        out.add("state", service.captureState());
        return out;
    }

    static JsonObject restoreLocal(PresetService service, String text) {
        try {
            JsonObject envelope = parseObject(text, PresetLimits.MAX_JSON_CHARS);
            if (!envelope.has("format") || !LOCAL_FORMAT.equals(envelope.get("format").getAsString())
                    || !envelope.has("version") || !envelope.get("version").isJsonPrimitive()
                    || !envelope.get("version").getAsJsonPrimitive().isNumber() || envelope.get("version").getAsDouble() != 1
                    || !envelope.has("localOnly") || !envelope.get("localOnly").isJsonPrimitive()
                    || !envelope.get("localOnly").getAsJsonPrimitive().isBoolean() || !envelope.get("localOnly").getAsBoolean()
                    || !envelope.has("state") || !envelope.get("state").isJsonObject())
                throw new IllegalArgumentException("不是受支持的本地音乐配置状态");
            JsonObject report = service.restoreState(envelope.getAsJsonObject("state")).toJson();
            JsonObject out = new JsonObject();
            out.addProperty("protocolVersion", PROTOCOL_VERSION);
            out.addProperty("ok", report.get("ok").getAsBoolean());
            out.add("report", report);
            return out;
        } catch (RuntimeException failure) { return error(failure); }
    }

    static JsonObject parseObject(String text, int limit) {
        if (text == null || text.length() > limit || PresetLimits.nestingDepth(text) > PresetLimits.MAX_DEPTH)
            throw new IllegalArgumentException("请求为空、过长或嵌套过深");
        var parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("请求必须是 JSON 对象");
        return parsed.getAsJsonObject();
    }

    static CompletableFuture<String> enqueue(Consumer<Runnable> scheduler, Supplier<String> action) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            scheduler.accept(() -> {
                if (future.isCancelled()) return;
                try { future.complete(action.get()); }
                catch (RuntimeException failure) { future.complete(error(failure).toString()); }
            });
        } catch (RuntimeException failure) { future.complete(error(failure).toString()); }
        return future;
    }

    private static JsonObject error(RuntimeException failure) {
        JsonObject out = new JsonObject();
        out.addProperty("protocolVersion", PROTOCOL_VERSION);
        out.addProperty("ok", false);
        out.addProperty("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        return out;
    }

    private static boolean validMode(String mode) {
        if (mode == null || !mode.equals(mode.toLowerCase(Locale.ROOT))) return false;
        return "manage".equals(mode) || "save".equals(mode) || "undo".equals(mode) || "import".equals(mode);
    }
}
