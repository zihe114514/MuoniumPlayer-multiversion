package com.muoniumplayer.core.music.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 凭证落盘（music_auth.json）。自 Cadence（原 music-api-0.1.1.jar，原 jar 的 store 包 包）
 * 手翻，2026-11 吸收进仓。
 *
 * <p>文件格式只增不改：老文件缺 QQ 字段读空串（黄金基准 legacyFile 向量已覆盖）。
 * set* 方法写内存后立即回盘，落盘失败只记日志不影响内存值——与原实现一致。</p>
 */
public final class MuCredentialStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private String neteaseCookie = "";
    private String qqMusicId = "";
    private String qqMusicKey = "";
    // 2026-11 补齐：QQ 凭证续期字段（旧文件缺这些键读默认值，格式只增不改）。
    // loginType: 1=QQ 扫码，2=微信；refreshKey/refreshToken 是 Login 响应里现成带的续期材料。
    private String qqRefreshKey = "";
    private String qqRefreshToken = "";
    private String qqOpenid = "";
    private String qqUnionid = "";
    private int qqLoginType = 0;
    private long qqExpiredAt = 0L;

    public MuCredentialStore(Path file) {
        this.file = file;
    }

    public String getNeteaseCookie() {
        return neteaseCookie;
    }

    public String getQqMusicId() {
        return qqMusicId;
    }

    public String getQqMusicKey() {
        return qqMusicKey;
    }

    public void load() {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            MuLog.debug("No music auth file at " + file);
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) return;
            neteaseCookie = stringOrEmpty(json, "neteaseCookie");
            qqMusicId = stringOrEmpty(json, "qqMusicId");
            qqMusicKey = stringOrEmpty(json, "qqMusicKey");
            qqRefreshKey = stringOrEmpty(json, "qqRefreshKey");
            qqRefreshToken = stringOrEmpty(json, "qqRefreshToken");
            qqOpenid = stringOrEmpty(json, "qqOpenid");
            qqUnionid = stringOrEmpty(json, "qqUnionid");
            qqLoginType = intOr(json, "qqLoginType", 0);
            qqExpiredAt = longOr(json, "qqExpiredAt", 0L);
            MuLog.debug("Loaded music auth from " + file);
        } catch (Exception e) {
            MuLog.error("Failed to load music auth file", e);
        }
    }

    public void setNetease(String cookie) {
        this.neteaseCookie = cookie;
        save();
    }

    public void clearNetease() {
        this.neteaseCookie = "";
        save();
    }

    public void setQq(String musicId, String musicKey) {
        this.qqMusicId = musicId;
        this.qqMusicKey = musicKey;
        save();
    }

    public void clearQq() {
        this.qqMusicId = "";
        this.qqMusicKey = "";
        this.qqRefreshKey = "";
        this.qqRefreshToken = "";
        this.qqOpenid = "";
        this.qqUnionid = "";
        this.qqLoginType = 0;
        this.qqExpiredAt = 0L;
        save();
    }

    /** 续期材料整体落盘（QQ 扫码成功或刷新成功时调用）。 */
    public void setQqRefreshMaterial(String refreshKey, String refreshToken, String openid,
                                     String unionid, int loginType, long expiredAt) {
        this.qqRefreshKey = refreshKey == null ? "" : refreshKey;
        this.qqRefreshToken = refreshToken == null ? "" : refreshToken;
        this.qqOpenid = openid == null ? "" : openid;
        this.qqUnionid = unionid == null ? "" : unionid;
        this.qqLoginType = loginType;
        this.qqExpiredAt = expiredAt;
        save();
    }

    public String getQqRefreshKey() { return qqRefreshKey; }

    public String getQqRefreshToken() { return qqRefreshToken; }

    public String getQqOpenid() { return qqOpenid; }

    public String getQqUnionid() { return qqUnionid; }

    public int getQqLoginType() { return qqLoginType; }

    public long getQqExpiredAt() { return qqExpiredAt; }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            JsonObject json = new JsonObject();
            json.addProperty("neteaseCookie", neteaseCookie);
            json.addProperty("qqMusicId", qqMusicId);
            json.addProperty("qqMusicKey", qqMusicKey);
            json.addProperty("qqRefreshKey", qqRefreshKey);
            json.addProperty("qqRefreshToken", qqRefreshToken);
            json.addProperty("qqOpenid", qqOpenid);
            json.addProperty("qqUnionid", qqUnionid);
            json.addProperty("qqLoginType", qqLoginType);
            json.addProperty("qqExpiredAt", qqExpiredAt);
            json.addProperty("lastUpdated", System.currentTimeMillis());
            try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(file),
                    StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (Exception e) {
            MuLog.error("Failed to save music auth file", e);
        }
    }

    private static String stringOrEmpty(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element != null && !element.isJsonNull()) {
            String value = element.getAsString();
            if (value != null) return value;
        }
        return "";
    }

    private static int intOr(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        try {
            return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static long longOr(JsonObject json, String key, long fallback) {
        JsonElement element = json.get(key);
        try {
            return element != null && !element.isJsonNull() ? element.getAsLong() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }
}
