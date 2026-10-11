package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本地保存汽水 Passport Cookie 与账号展示资料，不记录二维码 token 或轮询状态。 */
final class QishuiSessionStore {

    static final class Session {
        final boolean loggedIn;
        final String cookieHeader;
        final String userId;
        final String nickname;
        final String douyinId;
        final String avatarUrl;
        final boolean vip;
        final String vipStage;
        /** 上一次成功打过账号接口的时刻；0 表示从没验证过。 */
        final long verifiedAt;
        /** 凭据仍在本地，但上游已判定过期——界面据此提示重登，而不是静默当未登录。 */
        final boolean reauthRequired;

        private Session(Data data) {
            Map<String, String> cookies = data.cookies == null ? Collections.emptyMap() : data.cookies;
            this.loggedIn = containsSessionCookie(cookies);
            this.cookieHeader = joinCookies(cookies);
            this.userId = nonBlank(data.userId);
            this.nickname = nonBlank(data.nickname);
            this.douyinId = nonBlank(data.douyinId);
            this.avatarUrl = nonBlank(data.avatarUrl);
            this.vip = data.vip;
            this.vipStage = nonBlank(data.vipStage);
            this.verifiedAt = Math.max(0L, data.verifiedAt);
            this.reauthRequired = data.reauthRequired;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    /** 读盘完成后只在这里换引用（写路径仍持 {@link #LOCK}），读路径因此可以不加锁。 */
    private static volatile Data data = new Data();
    private static volatile boolean loaded;

    /** 已排过读盘。挡住「预读还没跑完时，渲染路径每帧又各排一次」的重复排程。 */
    private static final java.util.concurrent.atomic.AtomicBoolean LOAD_SCHEDULED =
            new java.util.concurrent.atomic.AtomicBoolean();

    static {
        // 预读：类第一次被碰到就把这次读盘排到后台。这样渲染路径随后读到的已经是内存快照，
        // 不会在某次绘制里为了「这账号登没登录」去等一次文件 I/O。
        scheduleLoad();
    }

    private QishuiSessionStore() {
    }

    /** 排一次后台读盘；已经排过就直接返回。 */
    private static void scheduleLoad() {
        if (!LOAD_SCHEDULED.compareAndSet(false, true)) return;
        MultiThreadingUtil.runAsync(QishuiSessionStore::ensureLoadedAsync);
    }

    /** 持锁读盘一次。只允许后台线程调用（渲染路径见 {@link #snapshot()}）。 */
    private static void ensureLoadedAsync() {
        synchronized (LOCK) {
            ensureLoaded();
        }
    }

    /**
     * 当前会话快照。
     *
     * <p><b>不阻塞</b>：这是一个渲染路径也在调的方法（红心按不按得、来源卡片怎么写副标题）。
     * 磁盘读只在后台发生——静态初始化那次预读，或下面这条兜底分支。还没读完时按「空会话」
     * 作答，同时把读盘排到后台；读完之后的调用都直接读内存。</p>
     */
    static Session snapshot() {
        if (!loaded) {
            // 兜底路径：类的静态预读还没跑完（或读盘很慢）。绝不在调用方线程上等磁盘 ——
            // 这里可能是渲染线程。排一次后台读（有闸门，重复调用不会重复排），本次按空会话作答。
            scheduleLoad();
        }
        return new Session(data);
    }

    static boolean isLoggedIn() {
        return snapshot().loggedIn;
    }

    static String getCookieHeader() {
        return snapshot().cookieHeader;
    }

    /**
     * 拿一份可安全修改的副本。
     *
     * <p>写入一律走「改副本 → 换引用」，绝不原地改 {@code data} 里的集合：读路径
     * （{@link #snapshot()}）不加锁且会遍历 cookie 表，原地改就是并发修改异常。</p>
     */
    private static Data editableCopy() {
        ensureLoaded();
        return data.copy();
    }

    static void saveCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return;
        synchronized (LOCK) {
            Data next = editableCopy();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String name = nonBlank(entry.getKey());
                String value = nonBlank(entry.getValue());
                if (!name.isEmpty() && !value.isEmpty()) next.cookies.put(name, value);
            }
            next.updatedAt = System.currentTimeMillis();
            // 新凭据到手就等于「待验证」：既不算已验证，也不该继续挂着过期标记。
            next.verifiedAt = 0L;
            next.reauthRequired = false;
            persist(next);
        }
    }

    /**
     * 整体替换本地凭据，供手动导入 Cookie 用。与 {@link #saveCookies} 的合并语义不同：
     * 导入一份新 cookie 时如果和旧账号的 cookie 按名字合并，会得到一个两个账号字段混装的
     * 畸形会话（旧的 uid_tt 配新的 sessionid），上游行为不可预测。所以这里先清空再写，
     * 并且只在调用方已经拿这份 cookie 打过账号接口确认有效之后才调。
     */
    static void replaceCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return;
        synchronized (LOCK) {
            Data next = editableCopy();
            next.cookies = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String name = nonBlank(entry.getKey());
                String value = nonBlank(entry.getValue());
                if (!name.isEmpty() && !value.isEmpty()) next.cookies.put(name, value);
            }
            // 换账号了，上一个账号的展示资料必须一起清掉，否则界面会显示错人的昵称头像。
            next.userId = "";
            next.nickname = "";
            next.douyinId = "";
            next.avatarUrl = "";
            next.vip = false;
            next.vipStage = "";
            next.updatedAt = System.currentTimeMillis();
            next.verifiedAt = 0L;
            next.reauthRequired = false;
            persist(next);
        }
    }

    /** 账号接口确认成功时记录时刻，并撤销之前的过期标记。 */
    static void markVerified() {
        synchronized (LOCK) {
            Data next = editableCopy();
            if (!containsSessionCookie(next.cookies)) return;
            next.verifiedAt = System.currentTimeMillis();
            next.reauthRequired = false;
            persist(next);
        }
    }

    /**
     * 上游判定凭据失效。<b>只打标记、绝不清空凭据</b>：读失败或被顶下线时把 cookie 删了，
     * 用户就只能重新扫一次码，而他那份 cookie 可能本来还能救（比如只是临时风控）。
     */
    static void markReauthRequired() {
        synchronized (LOCK) {
            Data next = editableCopy();
            if (!next.reauthRequired) {
                next.reauthRequired = true;
                persist(next);
            }
        }
    }

    static boolean isReauthRequired() {
        return snapshot().reauthRequired;
    }

    static long getVerifiedAt() {
        return snapshot().verifiedAt;
    }

    static void saveProfile(String userId, String nickname, String douyinId,
                            String avatarUrl, boolean vip, String vipStage) {
        synchronized (LOCK) {
            Data next = editableCopy();
            if (!isBlank(userId)) next.userId = userId.trim();
            if (!isBlank(nickname)) next.nickname = nickname.trim();
            if (!isBlank(douyinId)) next.douyinId = douyinId.trim();
            if (!isBlank(avatarUrl)) next.avatarUrl = avatarUrl.trim();
            next.vip = vip;
            if (!isBlank(vipStage)) next.vipStage = vipStage.trim();
            next.updatedAt = System.currentTimeMillis();
            persist(next);
        }
    }

    static void clear() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            try {
                Files.deleteIfExists(ConfigPaths.QISHUI_AUTH.toPath());
            } catch (IOException ignored) {
                // 退出登录以内存状态为准；文件删不掉时下次 save 也会覆盖它。
            }
        }
    }

    /** 读盘一次。只在持锁的后台路径上调用；发布走 volatile 写，读路径不必等它。 */
    private static void ensureLoaded() {
        if (loaded) return;
        Path path = ConfigPaths.QISHUI_AUTH.toPath();
        Data parsed = new Data();
        if (Files.isRegularFile(path)) {
            try {
                Data loadedData = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
                if (loadedData != null) parsed = loadedData.normalized();
            } catch (Throwable ignored) {
                // 损坏的会话文件按空会话处理，与旧行为一致。
            }
        }
        data = parsed;
        loaded = true;
    }

    /** 落盘当前这一份。写入方已经把 {@code next} 挂上去了，这里只负责写文件。 */
    private static void persist(Data value) {
        data = value;
        Path path = ConfigPaths.QISHUI_AUTH.toPath();
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("[Music/Qishui] Failed to persist session: " + error.getMessage());
        }
    }

    private static boolean containsSessionCookie(Map<String, String> cookies) {
        return !nonBlank(cookies.get("sessionid")).isEmpty()
                || !nonBlank(cookies.get("sessionid_ss")).isEmpty()
                || !nonBlank(cookies.get("sid_tt")).isEmpty()
                || !nonBlank(cookies.get("sid_guard")).isEmpty();
    }

    private static String joinCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return "";
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(cookies).entrySet()) {
            String name = nonBlank(entry.getKey());
            String value = nonBlank(entry.getValue());
            if (name.isEmpty() || value.isEmpty() || !validCookiePart(name, value)) continue;
            if (header.length() > 0) header.append("; ");
            header.append(name).append('=').append(value);
        }
        if (header.length() == 0) return "";
        return header.append(';').toString();
    }

    private static boolean validCookiePart(String name, String value) {
        return name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]+")
                && value.indexOf(';') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return nonBlank(value).isEmpty();
    }

    private static final class Data {
        Map<String, String> cookies = new LinkedHashMap<>();
        String userId = "";
        String nickname = "";
        String douyinId = "";
        String avatarUrl = "";
        boolean vip;
        String vipStage = "";
        long updatedAt;
        long verifiedAt;
        boolean reauthRequired;

        Data normalized() {
            if (cookies == null) cookies = new LinkedHashMap<>();
            Map<String, String> clean = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String name = nonBlank(entry.getKey());
                String value = nonBlank(entry.getValue());
                if (!name.isEmpty() && !value.isEmpty()) clean.put(name, value);
            }
            cookies = clean;
            userId = nonBlank(userId);
            nickname = nonBlank(nickname);
            douyinId = nonBlank(douyinId);
            avatarUrl = nonBlank(avatarUrl);
            vipStage = nonBlank(vipStage);
            return this;
        }

        /**
         * 深拷贝。写路径据此「改副本再换引用」——读路径不加锁且会遍历 cookie 表，
         * 原地改就会让读者撞上并发修改。
         */
        Data copy() {
            Data clone = new Data();
            clone.cookies = new LinkedHashMap<>(cookies);
            clone.userId = userId;
            clone.nickname = nickname;
            clone.douyinId = douyinId;
            clone.avatarUrl = avatarUrl;
            clone.vip = vip;
            clone.vipStage = vipStage;
            clone.updatedAt = updatedAt;
            clone.verifiedAt = verifiedAt;
            clone.reauthRequired = reauthRequired;
            return clone;
        }
    }
}