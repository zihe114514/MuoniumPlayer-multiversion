package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.Location;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 汽水本地账号快捷列表；活动凭据仍由 {@link QishuiSessionStore} 独立管理。 */
public final class QishuiAccountProfiles {

    private static final File FILE = ConfigPaths.QISHUI_ACCOUNTS;
    private static final Gson GSON = new Gson();
    private static final int MAX_ACCOUNTS = 8;

    private QishuiAccountProfiles() {
    }

    public static synchronized List<Account> load() {
        if (!FILE.isFile()) return Collections.emptyList();
        try {
            JsonObject root = JsonConfigStorage.readObject(FILE, GSON);
            JsonArray array = root == null ? null : root.getAsJsonArray("accounts");
            if (array == null) return Collections.emptyList();
            List<Account> result = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String cookie = string(object, "cookie");
                if (cookie.isEmpty()) continue;
                result.add(new Account(string(object, "name"), string(object, "id"),
                        string(object, "douyinId"), cookie, string(object, "avatar"),
                        bool(object, "vip"), string(object, "vipStage")));
            }
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** 将当前已验证会话放到列表头部；没有有效 Cookie 或资料时不写空记录。 */
    public static synchronized void saveCurrent() {
        QishuiSessionStore.Session session = QishuiSessionStore.snapshot();
        if (!session.loggedIn || session.cookieHeader.isEmpty()) return;
        saveAccount(new Account(session.nickname, session.userId, session.douyinId,
                session.cookieHeader, session.avatarUrl, session.vip, session.vipStage));
    }

    static synchronized void saveAccount(Account account) {
        if (account == null || account.cookie.isEmpty()) return;
        List<Account> accounts = new ArrayList<>(load());
        for (int i = accounts.size() - 1; i >= 0; i--) {
            Account old = accounts.get(i);
            if ((!account.id.isEmpty() && account.id.equals(old.id))
                    || account.cookie.equals(old.cookie)) {
                accounts.remove(i);
            }
        }
        accounts.add(0, account);
        while (accounts.size() > MAX_ACCOUNTS) accounts.remove(accounts.size() - 1);
        save(accounts);
    }

    /** 切换前由真实账号接口校验 Cookie，失败时活动会话保持不变。 */
    public static QishuiMusicService.CookieImport switchTo(Account account) {
        if (account == null || account.cookie.isEmpty()) {
            return QishuiMusicService.CookieImport.failure("已保存账号凭据为空");
        }
        return QishuiMusicService.importCookie(account.cookie);
    }

    public static synchronized boolean remove(Account account) {
        if (account == null) return false;
        List<Account> accounts = new ArrayList<>(load());
        boolean removed = false;
        for (int i = accounts.size() - 1; i >= 0; i--) {
            if (account.cookie.equals(accounts.get(i).cookie)) {
                accounts.remove(i);
                removed = true;
            }
        }
        if (removed) save(accounts);
        return removed;
    }

    public static boolean isActive(Account account) {
        if (account == null) return false;
        QishuiSessionStore.Session current = QishuiSessionStore.snapshot();
        if (!current.loggedIn) return false;
        if (!account.id.isEmpty() && account.id.equals(current.userId)) return true;
        return account.cookie.equals(current.cookieHeader);
    }

    private static synchronized void save(List<Account> accounts) {
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();
            for (Account account : accounts) {
                JsonObject object = new JsonObject();
                object.addProperty("name", account.name);
                object.addProperty("id", account.id);
                object.addProperty("douyinId", account.douyinId);
                object.addProperty("cookie", account.cookie);
                object.addProperty("avatar", account.avatar);
                object.addProperty("vip", account.vip);
                object.addProperty("vipStage", account.vipStage);
                array.add(object);
            }
            root.add("accounts", array);
            JsonConfigStorage.writeObject(FILE, GSON, root);
        } catch (Throwable ignored) {
        }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString().trim() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).getAsBoolean();
    }

    public static final class Account {
        public final String name;
        public final String id;
        public final String douyinId;
        private final String cookie;
        private final String avatar;
        public final boolean vip;
        public final String vipStage;

        private Account(String name, String id, String douyinId, String cookie, String avatar,
                        boolean vip, String vipStage) {
            this.name = QishuiMusicService.displayName(name);
            this.id = clean(id);
            this.douyinId = clean(douyinId);
            this.cookie = clean(cookie);
            this.avatar = clean(avatar);
            this.vip = vip;
            this.vipStage = clean(vipStage);
        }

        public String getDisplayName() {
            return QishuiMusicService.displayName(name);
        }

        public String getAvatarUrl() {
            return avatar;
        }

        public Location getAvatarLocation() {
            if (avatar.isEmpty()) return null;
            return QishuiMusicService.avatarLocation(id, avatar);
        }

        public String getMeta() {
            if (!douyinId.isEmpty()) return "抖音 ID " + douyinId;
            if (!id.isEmpty()) return "汽水账号 " + id;
            return "本地凭据";
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
