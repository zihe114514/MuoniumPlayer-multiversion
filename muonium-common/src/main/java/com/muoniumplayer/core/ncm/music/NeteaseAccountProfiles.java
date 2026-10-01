package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地的网易云账号列表（基于 cookie 切换）。cookie 不会离开现有的网易云请求链路。
 *
 * <p>除了 cookie 还存昵称 / id / 头像地址：切账号这个动作本质上是“认人”，而一列只有昵称和
 * 一串数字 id 的文字行是认不出人的。头像地址必须落盘：它只能从“当时登录的那个账号”身上拿到，
 * 不存的话列表里除了当前账号其他全是字母头像。</p>
 */
public final class NeteaseAccountProfiles {

    private static final File FILE = ConfigPaths.NETEASE_ACCOUNTS;
    private static final Gson GSON = new Gson();

    private NeteaseAccountProfiles() { }

    public static synchronized List<Account> load() {
        if (!FILE.isFile()) return Collections.emptyList();
        try {
            JsonObject root = JsonConfigStorage.readObject(FILE, GSON);
            JsonArray accounts = root == null ? null : root.getAsJsonArray("accounts");
            if (accounts == null) return Collections.emptyList();
            List<Account> result = new ArrayList<Account>();
            for (JsonElement element : accounts) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String cookie = string(object, "cookie");
                if (cookie.isEmpty()) continue;
                result.add(new Account(string(object, "name"), string(object, "id"), cookie,
                        string(object, "avatar")));
            }
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static synchronized void saveCurrent() {
        String cookie = OptionsUtil.getCookie();
        if (cookie == null || cookie.trim().isEmpty() || CloudMusic.profile == null) return;
        String id = String.valueOf(CloudMusic.profile.getId());
        String name = CloudMusic.profile.getName() == null ? "网易云账号" : CloudMusic.profile.getName();
        String avatar = CloudMusic.profile.getAvatarUrl() == null ? "" : CloudMusic.profile.getAvatarUrl();
        List<Account> accounts = new ArrayList<Account>(load());
        for (int i = accounts.size() - 1; i >= 0; i--) {
            Account old = accounts.get(i);
            if (cookie.equals(old.cookie) || (!id.isEmpty() && id.equals(old.id))) accounts.remove(i);
        }
        accounts.add(0, new Account(name, id, cookie, avatar));
        while (accounts.size() > 8) accounts.remove(accounts.size() - 1);
        save(accounts);
    }

    public static synchronized boolean switchTo(Account account) {
        if (account == null || account.cookie.trim().isEmpty()) return false;
        CloudMusic.loadNCM(account.cookie);
        if (CloudMusic.profile == null) return false;
        saveCurrent();
        return true;
    }

    /**
     * 删一条已保存账号。只动本地列表，不退当前登录：删的是“快捷方式”，不是账号本身。
     *
     * <p>按 cookie 认同一条而不是按 id：id 在历史数据里可能是空串，按空串匹配会一次删掩一批。</p>
     */
    public static synchronized boolean remove(Account account) {
        if (account == null) return false;
        List<Account> accounts = new ArrayList<Account>(load());
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

    /**
     * 这一条是不是当前正在用的账号。
     *
     * <p>先比 cookie 再比 id：同一个账号重新扇码登录会拿到新 cookie，光比 cookie 会让「当前」
     * 标记落空；而 id 在历史数据里可能缺失，光比 id 又会把两条空 id 记录一起点亮。</p>
     */
    public static synchronized boolean isActive(Account account) {
        if (account == null) return false;
        String cookie = OptionsUtil.getCookie();
        if (cookie != null && !cookie.trim().isEmpty() && cookie.equals(account.cookie)) return true;
        User profile = CloudMusic.profile;
        if (profile == null || account.id == null || account.id.trim().isEmpty()) return false;
        return account.id.equals(String.valueOf(profile.getId()));
    }

    private static void save(List<Account> accounts) {
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();
            for (Account account : accounts) {
                JsonObject object = new JsonObject();
                object.addProperty("name", account.name);
                object.addProperty("id", account.id);
                object.addProperty("cookie", account.cookie);
                object.addProperty("avatar", account.avatar);
                array.add(object);
            }
            root.add("accounts", array);
            JsonConfigStorage.writeObject(FILE, GSON, root);
        } catch (Throwable ignored) { }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    public static final class Account {
        public final String name;
        public final String id;
        private final String cookie;
        private final String avatar;

        private Account(String name, String id, String cookie, String avatar) {
            this.name = name;
            this.id = id;
            this.cookie = cookie;
            this.avatar = avatar == null ? "" : avatar;
        }

        public String getDisplayName() { return name == null || name.trim().isEmpty() ? "网易云账号" : name; }

        public String getAvatarUrl() { return avatar; }

        /**
         * 头像贴图位置；没有 id 时返回 {@code null}（调用方退回字母头像）。
         *
         * <p>路径刻意和 {@link User#getAvatarLocation()} 一致：当前账号的头像已经在那个位置上了，
         * 共用同一个槽位就不会为同一张图存两份、也不会在列表里重新下一次。</p>
         */
        public Location getAvatarLocation() {
            if (id == null || id.trim().isEmpty()) return null;
            return Location.of("muonium/textures/user/" + id + "/avatar.png");
        }
    }
}
