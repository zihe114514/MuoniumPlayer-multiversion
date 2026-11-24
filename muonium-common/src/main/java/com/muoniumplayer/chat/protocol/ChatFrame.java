package com.muoniumplayer.chat.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Strict JSON envelope handling for MuoniumChat protocol v1. */
public final class ChatFrame {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 16 * 1024;
    public static final int MAX_SHARE_FRAME_BYTES = 64 * 1024;
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private ChatFrame() {}

    public static JsonObject request(String type, JsonObject data) {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", PROTOCOL_VERSION);
        frame.addProperty("t", type);
        frame.addProperty("id", "muonium-" + Long.toUnsignedString(NEXT_ID.incrementAndGet(), 36));
        frame.add("d", data == null ? new JsonObject() : data);
        return frame;
    }

    public static JsonObject data(Object... pairs) {
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("data requires key/value pairs");
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value == null) continue;
            if (value instanceof String) result.addProperty(key, (String) value);
            else if (value instanceof Number) result.addProperty(key, (Number) value);
            else if (value instanceof Boolean) result.addProperty(key, (Boolean) value);
            else if (value instanceof JsonElement) result.add(key, (JsonElement) value);
            else throw new IllegalArgumentException("unsupported JSON value for " + key);
        }
        return result;
    }

    public static Inbound parse(String text) throws ProtocolException {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_SHARE_FRAME_BYTES) {
            throw new ProtocolException("frame too large");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException invalid) {
            throw new ProtocolException("invalid JSON");
        }
        if (!parsed.isJsonObject()) throw new ProtocolException("frame must be an object");
        JsonObject root = parsed.getAsJsonObject();
        String type = string(root, "t", true, 64);
        JsonObject data = object(root, "d", true);
        int limit = containsShare(type, data) ? MAX_SHARE_FRAME_BYTES : MAX_FRAME_BYTES;
        if (text.getBytes(StandardCharsets.UTF_8).length > limit) throw new ProtocolException("frame too large");
        requireKeys(root, Set.of("v", "t", "id", "d"));
        int version = integer(root, "v", true, 0);
        if (version != PROTOCOL_VERSION) throw new ProtocolException("unsupported protocol version " + version);
        String id = string(root, "id", false, 64);
        return new Inbound(type, id, data);
    }

    private static boolean containsShare(String type, JsonObject data) {
        if ("msg.send".equals(type)) {
            return data.has("kind") && data.get("kind").isJsonPrimitive()
                    && "share".equals(data.get("kind").getAsString());
        }
        if ("msg.new".equals(type)) {
            return data.has("kind") && data.get("kind").isJsonPrimitive()
                    && "share".equals(data.get("kind").getAsString());
        }
        return false;
    }

    public static void requireKeys(JsonObject object, Set<String> allowed) throws ProtocolException {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new ProtocolException("unknown field " + key);
        }
    }

    public static String string(JsonObject object, String key, boolean required, int maxLength) throws ProtocolException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) throw new ProtocolException("missing field " + key);
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new ProtocolException("invalid field " + key);
        String text = value.getAsString();
        if (text.length() > maxLength) throw new ProtocolException("field too long " + key);
        return text;
    }

    public static int integer(JsonObject object, String key, boolean required, int fallback) throws ProtocolException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) throw new ProtocolException("missing field " + key);
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ProtocolException("invalid field " + key);
        try {
            return value.getAsInt();
        } catch (RuntimeException invalid) {
            throw new ProtocolException("invalid field " + key);
        }
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) throws ProtocolException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new ProtocolException("invalid field " + key);
        return value.getAsBoolean();
    }

    public static JsonObject object(JsonObject object, String key, boolean required) throws ProtocolException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) throw new ProtocolException("missing field " + key);
            return null;
        }
        if (!value.isJsonObject()) throw new ProtocolException("invalid field " + key);
        return value.getAsJsonObject();
    }

    public static JsonArray array(JsonObject object, String key, boolean required) throws ProtocolException {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) throw new ProtocolException("missing field " + key);
            return new JsonArray();
        }
        if (!value.isJsonArray()) throw new ProtocolException("invalid field " + key);
        return value.getAsJsonArray();
    }

    public static final class Inbound {
        public final String type;
        public final String id;
        public final JsonObject data;

        private Inbound(String type, String id, JsonObject data) {
            this.type = type;
            this.id = id;
            this.data = data;
        }
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) { super(message); }
    }
}
