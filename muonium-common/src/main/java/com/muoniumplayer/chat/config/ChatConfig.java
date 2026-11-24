package com.muoniumplayer.chat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Non-secret connection settings. Authentication tokens never belong in this file. */
public final class ChatConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_ENDPOINT = "";
    private static volatile String endpoint = DEFAULT_ENDPOINT;
    private static volatile boolean autoConnect = false;

    private ChatConfig() {}

    public static String endpoint() {
        return endpoint;
    }

    public static boolean autoConnect() {
        return autoConnect;
    }

    public static synchronized boolean configure(String value, boolean connectOnStartup) {
        String normalized = normalizeEndpoint(value);
        if (normalized == null) return false;
        endpoint = normalized;
        autoConnect = connectOnStartup;
        save();
        com.muoniumplayer.chat.ChatClient.get().configure(normalized, connectOnStartup);
        return true;
    }

    public static synchronized void load() {
        Path path = path();
        if (!Files.isRegularFile(path)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            String configured = json.has("endpoint") ? json.get("endpoint").getAsString() : null;
            String normalized = normalizeEndpoint(configured);
            if (normalized != null) endpoint = normalized;
            autoConnect = !json.has("autoConnect") || json.get("autoConnect").getAsBoolean();
        } catch (Exception ignored) {
            endpoint = DEFAULT_ENDPOINT;
            autoConnect = false;
        }
    }

    private static void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("endpoint", endpoint);
            json.addProperty("autoConnect", autoConnect);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Settings remain active for this process; persistence can retry on the next edit.
        }
    }

    private static Path path() {
        return Path.of("config", "muonium", "chat.json");
    }

    public static String normalizeEndpoint(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("wss") || scheme.equalsIgnoreCase("ws"))) return null;
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getRawQuery() != null) return null;
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (scheme.equalsIgnoreCase("ws") && !isLoopback(host)) return null;
            int port = uri.getPort();
            if (port < -1 || port == 0 || port > 65535) return null;
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/ws";
            if (!path.equals("/ws")) return null;
            String authority = host;
            if (uri.getPort() >= 0) authority += ":" + uri.getPort();
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + authority + path;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
    }
}
