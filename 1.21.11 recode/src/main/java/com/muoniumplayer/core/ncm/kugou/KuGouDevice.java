package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/**
 * 酷狗的设备标识与登录凭证的持久化。
 *
 * <p>设备 GUID 与登录 token 放在同一个文件里，因为它们必须一起保持稳定：token 是绑在注册设备上的，
 * 换了 GUID 相当于换了一台机器，已登录的 token 会被上游按风控拒掉。</p>
 */
public final class KuGouDevice {

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile String guid = "";
    private static volatile String mid = "";
    /** 酷狗设备指纹。新版 tracker 接口将它作为风控输入；生成后持久化，不能每次启动变化。 */
    private static volatile String dfid = "";
    private static volatile boolean registered;
    private static volatile String token = "";
    private static volatile String userId = "";
    private static volatile String nickname = "";
    private static volatile String avatarUrl = "";

    private KuGouDevice() {
    }

    public static String mid() {
        ensureLoaded();
        return mid;
    }

    public static String dfid() {
        ensureLoaded();
        return dfid;
    }

    public static String token() {
        ensureLoaded();
        return token;
    }

    public static String userId() {
        ensureLoaded();
        return userId;
    }

    public static String nickname() {
        ensureLoaded();
        return nickname;
    }

    public static String avatarUrl() {
        ensureLoaded();
        return avatarUrl;
    }

    /** 现代 tracker API 所用的、由服务端下发的设备指纹注册。 */
    public static void ensureRegistered() {
        ensureLoaded();
        if (registered) return;
        synchronized (LOCK) {
            if (registered) return;
            String serverDfid = registerDevice();
            if (!KuGouSign.isBlank(serverDfid)) {
                dfid = serverDfid.trim();
                registered = true;
                persist();
            }
        }
    }

    public static boolean isLoggedIn() {
        ensureLoaded();
        return !KuGouSign.isBlank(token) && !KuGouSign.isBlank(userId) && !"0".equals(userId.trim());
    }

    /** 扫码成功后写入。{@code nickname}/{@code avatarUrl} 可以为空，随后由用户资料接口补齐。 */
    public static void saveLogin(String newToken, String newUserId, String newNickname, String newAvatarUrl) {
        ensureLoaded();
        synchronized (LOCK) {
            token = KuGouSign.nonNull(newToken).trim();
            userId = KuGouSign.nonNull(newUserId).trim();
            if (!KuGouSign.isBlank(newNickname)) nickname = newNickname.trim();
            if (!KuGouSign.isBlank(newAvatarUrl)) avatarUrl = newAvatarUrl.trim();
            persist();
        }
    }

    public static void saveProfile(String newNickname, String newAvatarUrl) {
        ensureLoaded();
        synchronized (LOCK) {
            if (!KuGouSign.isBlank(newNickname)) nickname = newNickname.trim();
            if (!KuGouSign.isBlank(newAvatarUrl)) avatarUrl = newAvatarUrl.trim();
            persist();
        }
    }

    /** 退出登录只清凭证，<b>保留</b> GUID：设备身份继续沿用，下次登录不会被当成新设备。 */
    public static void logout() {
        ensureLoaded();
        synchronized (LOCK) {
            token = "";
            userId = "";
            nickname = "";
            avatarUrl = "";
            persist();
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                File file = ConfigPaths.KUGOU_AUTH;
                if (file.isFile()) {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject root = new JsonParser().parse(text).getAsJsonObject();
                    guid = readString(root, "guid");
                    dfid = readString(root, "dfid");
                    registered = readBoolean(root, "dfid_registered");
                    token = readString(root, "token");
                    userId = readString(root, "userid");
                    nickname = readString(root, "nickname");
                    avatarUrl = readString(root, "avatar");
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/KuGou] 凭证读取失败，按未登录处理: " + throwable.getMessage());
            }
            if (KuGouSign.isBlank(guid)) {
                guid = UUID.randomUUID().toString();
            }
            mid = KuGouSign.mid(guid);
            if (KuGouSign.isBlank(dfid)) {
                dfid = "-";
                registered = false;
            }
            loaded = true;
            persist();
        }
    }

    private static String registerDevice() {
        try {
            String temporaryKey = randomKey();
            String digest = KuGouSign.md5(temporaryKey);
            String aesKey = digest.substring(0, 16);
            String iv = digest.substring(16, 32);
            JsonObject device = new JsonObject();
            device.addProperty("availableRamSize", 4983533568L);
            device.addProperty("availableRomSize", 48114719L);
            device.addProperty("availableSDSize", 48114717L);
            device.addProperty("basebandVer", "");
            device.addProperty("batteryLevel", 100);
            device.addProperty("batteryStatus", 3);
            device.addProperty("brand", "Redmi");
            device.addProperty("buildSerial", "unknown");
            device.addProperty("device", "marble");
            device.addProperty("imei", guid);
            device.addProperty("imsi", "");
            device.addProperty("manufacturer", "Xiaomi");
            device.addProperty("uuid", guid);
            device.addProperty("accelerometer", false);
            device.addProperty("accelerometerValue", "");
            device.addProperty("gravity", false);
            device.addProperty("gravityValue", "");
            device.addProperty("gyroscope", false);
            device.addProperty("gyroscopeValue", "");
            device.addProperty("light", false);
            device.addProperty("lightValue", "");
            device.addProperty("magnetic", false);
            device.addProperty("magneticValue", "");
            device.addProperty("orientation", false);
            device.addProperty("orientationValue", "");
            device.addProperty("pressure", false);
            device.addProperty("pressureValue", "");
            device.addProperty("step_counter", false);
            device.addProperty("step_counterValue", "");
            device.addProperty("temperature", false);
            device.addProperty("temperatureValue", "");
            String encryptedBody = playlistAesEncrypt(device.toString(), aesKey, iv);
            JsonObject rsaBody = new JsonObject();
            rsaBody.addProperty("aes", temporaryKey);
            rsaBody.addProperty("uid", KuGouSign.isBlank(userId) ? 0L : Long.parseLong(userId));
            rsaBody.addProperty("token", KuGouSign.nonNull(token));
            String rsa = rsaEncrypt(rsaBody.toString());
            Map<String, String> params = new java.util.LinkedHashMap<String, String>();
            params.put("dfid", "-");
            params.put("mid", mid);
            params.put("uuid", "-");
            params.put("appid", KuGouHttp.APP_ID);
            params.put("clientver", KuGouHttp.CLIENT_VER);
            params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000L));
            params.put("part", "1");
            params.put("platid", "1");
            params.put("p", rsa);
            params.put("signature", KuGouSign.android(params, encryptedBody));
            String url = "https://userservice.kugou.com/risk/v2/r_register_dev?" + query(params);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
            connection.setRequestProperty("dfid", "-");
            connection.setRequestProperty("mid", mid);
            connection.setRequestProperty("kg-rc", "1");
            connection.setRequestProperty("kg-thash", "5d816a0");
            connection.setRequestProperty("kg-rec", "1");
            connection.setRequestProperty("clienttime", params.get("clienttime"));
            byte[] requestBytes = encryptedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(requestBytes); }
            if (connection.getResponseCode() >= 300) return "";
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) response.write(buffer, 0, count);
            }
            String responseText = playlistAesDecrypt(
                    java.util.Base64.getEncoder().encodeToString(response.toByteArray()), aesKey, iv);
            JsonObject root = new JsonParser().parse(responseText).getAsJsonObject();
            JsonObject data = root != null && root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
            return data == null ? "" : readString(data, "dfid");
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] device registration failed: " + throwable.getMessage());
            return "";
        }
    }

    private static String randomKey() {
        String alphabet = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder result = new StringBuilder(6);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int index = 0; index < 6; index++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return result.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String playlistAesEncrypt(String plain, String key, String iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"), new javax.crypto.spec.IvParameterSpec(iv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String playlistAesDecrypt(String base64, String key, String iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"), new javax.crypto.spec.IvParameterSpec(iv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new String(cipher.doFinal(java.util.Base64.getDecoder().decode(base64)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String rsaEncrypt(String plain) throws Exception {
        String publicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB";
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(publicKey));
        java.security.PublicKey key = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(encrypted.length * 2);
        for (byte value : encrypted) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    private static String query(Map<String, String> params) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(KuGouSign.urlEncode(entry.getKey())).append('=').append(KuGouSign.urlEncode(entry.getValue()));
        }
        return result.toString();
    }

    private static void persist() {
        try {
            File file = ConfigPaths.KUGOU_AUTH;
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("guid", guid);
            root.addProperty("dfid", dfid);
            root.addProperty("dfid_registered", registered);
            root.addProperty("token", token);
            root.addProperty("userid", userId);
            root.addProperty("nickname", nickname);
            root.addProperty("avatar", avatarUrl);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 凭证写入失败: " + throwable.getMessage());
        }
    }

    private static boolean readBoolean(JsonObject root, String name) {
        try {
            return root != null && root.has(name) && !root.get(name).isJsonNull()
                    && root.get(name).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readString(JsonObject root, String name) {
        try {
            return root != null && root.has(name) && !root.get(name).isJsonNull()
                    ? root.get(name).getAsString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}