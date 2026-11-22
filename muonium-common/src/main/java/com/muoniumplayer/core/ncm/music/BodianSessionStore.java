package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 波点设备身份、活动会话及本地记住的账号。 */
public final class BodianSessionStore {

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ACCOUNTS = 8;
    private static volatile Data data = load();

    private BodianSessionStore() {
    }

    public static final class Session {
        public final String uid;
        public final String token;
        public final String nickname;
        public final String avatarUrl;
        public final boolean vip;

        Session(String uid, String token, String nickname, String avatarUrl, boolean vip) {
            this.uid = clean(uid);
            this.token = clean(token);
            this.nickname = clean(nickname);
            this.avatarUrl = clean(avatarUrl);
            this.vip = vip;
        }

        public boolean isValid() {
            return !uid.isEmpty() && !token.isEmpty();
        }
    }

    public static final class Account {
        public final String uid;
        public final String nickname;
        public final String avatarUrl;
        public final boolean vip;
        private final String token;

        private Account(Session session) {
            uid = session.uid;
            nickname = session.nickname;
            avatarUrl = session.avatarUrl;
            vip = session.vip;
            token = session.token;
        }

        public String getDisplayName() {
            return nickname.isEmpty() ? "波点音乐账号" : nickname;
        }

        public String getMeta() {
            return uid.isEmpty() ? "本地凭据" : "波点 UID " + uid;
        }

        Session toSession() {
            return new Session(uid, token, nickname, avatarUrl, vip);
        }
    }

    private static final class Data {
        String deviceId = "";
        Session active;
        final List<Account> accounts = new ArrayList<>();
    }

    public static Session snapshot() {
        return data.active;
    }

    public static String deviceId() {
        Data current = data;
        if (!current.deviceId.isEmpty()) return current.deviceId;
        synchronized (BodianSessionStore.class) {
            current = data;
            if (current.deviceId.isEmpty()) {
                Data next = copy(current);
                byte[] bytes = new byte[16];
                RANDOM.nextBytes(bytes);
                StringBuilder id = new StringBuilder(32);
                for (byte value : bytes) id.append(String.format("%02x", value & 0xff));
                next.deviceId = id.toString();
                data = next;
                persist(next);
            }
            return data.deviceId;
        }
    }

    public static synchronized void setSession(Session session) {
        Data next = copy(data);
        next.active = session != null && session.isValid() ? session : null;
        if (next.active != null) remember(next, new Account(next.active));
        data = next;
        persist(next);
    }

    public static synchronized void clearSession() {
        Data next = copy(data);
        next.active = null;
        data = next;
        persist(next);
    }

    public static synchronized List<Account> accounts() {
        return Collections.unmodifiableList(new ArrayList<>(data.accounts));
    }

    public static boolean isActive(Account account) {
        Session active = snapshot();
        return account != null && active != null && active.uid.equals(account.uid);
    }

    static synchronized void activate(Account account) {
        if (account == null) return;
        Data next = copy(data);
        next.active = account.toSession();
        data = next;
        persist(next);
    }

    public static synchronized boolean remove(Account account) {
        if (account == null) return false;
        Data next = copy(data);
        boolean removed = next.accounts.removeIf(saved -> saved.uid.equals(account.uid));
        if (removed) {
            data = next;
            persist(next);
        }
        return removed;
    }

    private static void remember(Data target, Account account) {
        target.accounts.removeIf(saved -> saved.uid.equals(account.uid));
        target.accounts.add(0, account);
        while (target.accounts.size() > MAX_ACCOUNTS) target.accounts.remove(target.accounts.size() - 1);
    }

    private static Data load() {
        Data result = new Data();
        result.deviceId = "";
        File file = ConfigPaths.BODIAN_AUTH;
        if (!file.isFile()) return result;
        try {
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null) return result;
            result.deviceId = string(root, "deviceId");
            JsonObject active = object(root, "session");
            if (active != null) result.active = parseSession(active);
            JsonArray saved = array(root, "accounts");
            if (saved != null) {
                for (JsonElement item : saved) {
                    if (item != null && item.isJsonObject()) {
                        Session session = parseSession(item.getAsJsonObject());
                        if (session.isValid()) result.accounts.add(new Account(session));
                    }
                }
            }
        } catch (Throwable ignored) {
            // A malformed credential file must not block other music sources.
        }
        return result;
    }

    private static Data copy(Data source) {
        Data result = new Data();
        result.deviceId = source.deviceId;
        result.active = source.active;
        result.accounts.addAll(source.accounts);
        return result;
    }

    private static void persist(Data value) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("deviceId", value.deviceId);
            if (value.active != null) root.add("session", toJson(value.active));
            JsonArray accounts = new JsonArray();
            for (Account account : value.accounts) accounts.add(toJson(account.toSession()));
            root.add("accounts", accounts);
            JsonConfigStorage.writeObject(ConfigPaths.BODIAN_AUTH, GSON, root);
        } catch (Throwable failure) {
            System.err.println("[Music/Bodian] Could not persist local session: " + failure.getMessage());
        }
    }

    private static JsonObject toJson(Session session) {
        JsonObject object = new JsonObject();
        object.addProperty("uid", session.uid);
        object.addProperty("token", session.token);
        object.addProperty("nickname", session.nickname);
        object.addProperty("avatarUrl", session.avatarUrl);
        object.addProperty("vip", session.vip);
        return object;
    }

    private static Session parseSession(JsonObject object) {
        return new Session(string(object, "uid"), string(object, "token"),
                string(object, "nickname"), string(object, "avatarUrl"), bool(object, "vip"));
    }

    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? clean(object.get(key).getAsString()) : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
