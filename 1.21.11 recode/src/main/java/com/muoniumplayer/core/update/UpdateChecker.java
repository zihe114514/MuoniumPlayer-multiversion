package com.muoniumplayer.core.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 更新检查的状态与持久化。界面在 {@code com.muoniumplayer.core.screens.GuiUpdateNotice}。
 *
 * <p>结构与 {@code FfmpegNotice} 刻意保持一致（后台探一次 + 本次启动只弹一次 + 一个持久化开关）：
 * 那套「守护线程探测 + 每 tick 轮询是否该弹」的骨架已经在四个版本上跑通过，
 * 这里换的只是数据来源 —— 从「探本机有没有 ffmpeg」换成「拉一份远端版本清单」。</p>
 *
 * <p>网络行为（用户可关，见 {@link #disable()}）：
 * <ul>
 *   <li>启动后在守护线程上最多拉一次清单，连接与读取超时各 5 秒，任何失败都静默；</li>
 *   <li>结果在 {@code config/muonium/update_notice.json} 里缓存 6 小时，期间完全不联网；</li>
 *   <li>只发匿名 GET，不带任何用户标识，也绝不自动下载或替换 jar。</li>
 * </ul>
 */
public final class UpdateChecker {

    /** 清单仓库首页。清单里的下载页不可用 / 不在白名单时退到这里。 */
    public static final String HOME_PAGE = "https://github.com/zihe114514/MuoniumPlayer-updates";

    /**
     * 清单源，按顺序试，第一个成功解析出条目的就用。
     *
     * <p>三个都实测过（curl 直连，本机在国内）：raw 约 1.0s、cdn.jsdelivr.net 约 2.0s、
     * gcore.jsdelivr.net 约 1.3s。raw 在部分网络下不可达，所以必须有 CDN 兜底；
     * jsDelivr 的两个入口分属不同 CDN，不会一起挂。</p>
     */
    public static final String[] MANIFEST_URLS = {
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/update.json",
            "https://cdn.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/update.json",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/update.json",
    };

    /** 还没查完 / 查失败 / 用户关掉了检查。 */
    public static final int STATE_UNKNOWN = 0;
    /** 已经是最新版。 */
    public static final int STATE_UP_TO_DATE = 1;
    /** 远端有更新的版本。 */
    public static final int STATE_OUTDATED = -1;

    /** 缓存有效期。6 小时既不会让用户等太久才知道有新版，也不会每次启动都打一次网络。 */
    private static final long CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;

    /**
     * 下载页的域名白名单。
     *
     * <p>清单是纯文本、放在公开仓库上：万一被篡改，「点一下就跳浏览器」这一步会变成把用户
     * 导去任意站点。所以域名由客户端写死，不在名单里就退到 {@link #HOME_PAGE}。</p>
     */
    private static final String[] ALLOWED_PAGE_HOSTS = {
            "github.com",
            "githubusercontent.com",
            "jsdelivr.net",
            "modrinth.com",
            "gitee.com",
    };

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static volatile int state = STATE_UNKNOWN;
    private static volatile boolean checkStarted;
    /** 本次启动是否已经弹过。弹过就不再弹，避免每次回到主菜单都糊一次全屏。 */
    private static volatile boolean shownThisSession;

    private static volatile String currentVersion = "";
    private static volatile String mcVersion = "";
    private static volatile String loaderId = "";
    private static volatile String latestVersion = "";
    private static volatile String downloadPage = HOME_PAGE;
    private static volatile String releaseNotes = "";
    private static volatile boolean critical;

    // 以下几项由 LOCK 保护，只在持久化读写时访问。
    private static boolean loaded;
    private static boolean enabled = true;
    private static String skipVersion = "";
    private static long lastCheckAt;
    private static String cachedChannel = "";
    private static String cachedLatest = "";
    private static String cachedPage = "";
    private static String cachedNotes = "";
    private static boolean cachedCritical;

    private UpdateChecker() {
    }

    /**
     * 启动时调用一次（在共享入口的 {@code init()} 里）。
     *
     * <p>三个参数都由调用方给：mod 版本与 MC 版本是各版本工程里的常量，加载器 id 由入口外壳
     * 登记 —— 共享源码不许出现任何加载器 API，所以不能在这里自己探测是 Fabric 还是 NeoForge。</p>
     */
    public static void beginCheck(String modVersion, String minecraftVersion, String loader) {
        synchronized (LOCK) {
            if (checkStarted) {
                return;
            }
            checkStarted = true;
        }
        currentVersion = modVersion == null ? "" : modVersion.trim();
        mcVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
        loaderId = loader == null ? "" : loader.trim();

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runCheck();
            }
        }, "muonium-update-check");
        // 守护线程：网络卡住时不能拖着游戏退不出去。
        thread.setDaemon(true);
        thread.start();
    }

    /** {@link #STATE_UNKNOWN} / {@link #STATE_UP_TO_DATE} / {@link #STATE_OUTDATED}。 */
    public static int state() {
        return state;
    }

    public static String currentVersion() {
        return currentVersion;
    }

    public static String latestVersion() {
        return latestVersion;
    }

    /** 一定是 https 且域名在白名单里（清单给的地址不合规时是 {@link #HOME_PAGE}）。 */
    public static String downloadPage() {
        return downloadPage;
    }

    public static String releaseNotes() {
        return releaseNotes;
    }

    /** true 时界面上不提供「跳过这个版本」。 */
    public static boolean isCritical() {
        return critical;
    }

    public static String channel() {
        return channelKey();
    }

    /**
     * 现在该不该弹提示。
     *
     * <p>五个条件同时成立才弹：本次启动还没弹过、检查没被关掉、结论是「有更新」、
     * 拿到了版本号、这个版本没被用户跳过。还没查完时返回 {@code false} 而不是等待 ——
     * 调用方在客户端 tick 里，下一 tick 再问一次即可。</p>
     */
    public static boolean shouldShow() {
        if (shownThisSession) {
            return false;
        }
        if (state != STATE_OUTDATED) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        String latest = latestVersion;
        if (latest.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            // 跳过的是「这个版本及更早」：用户跳过 1.4.0 之后清单又回退到 1.3.5，不该再弹。
            return skipVersion.isEmpty() || SemVer.compare(skipVersion, latest) < 0;
        }
    }

    /** 由界面在真正显示出来的那一刻调用。 */
    public static void markShownThisSession() {
        shownThisSession = true;
    }

    /** 「跳过这个版本」：记下当前 latest，等清单里出现更新的版本再提示。 */
    public static void skipCurrentVersion() {
        String latest = latestVersion;
        if (latest.isEmpty()) {
            shownThisSession = true;
            return;
        }
        load();
        synchronized (LOCK) {
            skipVersion = latest;
            save();
        }
        shownThisSession = true;
    }

    /** 「不再检查更新」：彻底关掉，下次启动连网络都不会打。 */
    public static void disable() {
        load();
        synchronized (LOCK) {
            enabled = false;
            save();
        }
        shownThisSession = true;
    }

    /** 用户没关掉更新检查。改 {@code config/muonium/update_notice.json} 的 enabled 也能关。 */
    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    /**
     * 下载页是否可以交给浏览器 / 剪贴板。
     *
     * <p>规则：必须是 https；authority 里不许出现 {@code @}（{@code https://github.com@evil.com/}
     * 这种写法真正的 host 是 {@code evil.com}）；host 必须等于白名单里的域名或是它的子域。</p>
     */
    public static boolean isAllowedPage(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return false;
        }
        String rest = trimmed.substring(8);
        int cut = rest.length();
        for (int index = 0; index < rest.length(); index++) {
            char ch = rest.charAt(index);
            if (ch == '/' || ch == '?' || ch == '#') {
                cut = index;
                break;
            }
        }
        String authority = rest.substring(0, cut);
        if (authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('\\') >= 0) {
            return false;
        }
        int colon = authority.indexOf(':');
        String host = (colon < 0 ? authority : authority.substring(0, colon)).toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        for (String allowed : ALLOWED_PAGE_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 内部实现 ====================

    private static void runCheck() {
        try {
            if (!isEnabled()) {
                log("disabled by user, no network");
                return;
            }
            if (currentVersion.isEmpty()) {
                return;
            }
            if (SemVer.isPreRelease(currentVersion)) {
                // 开发构建（1.3.0-dev 之类）永远比正式版「旧」，一启动就会被提示更新，没有意义。
                log("dev build " + currentVersion + ", skipped");
                return;
            }

            UpdateManifest.Entry entry = cachedEntry();
            boolean fromCache = entry != null;
            if (entry == null) {
                entry = fetchEntry();
                if (entry != null) {
                    rememberEntry(entry);
                }
            }
            apply(entry);
            log((fromCache ? "cache" : "remote") + " latest=" + latestVersion
                    + " current=" + currentVersion + " channel=" + channelKey()
                    + " state=" + state);
        } catch (Throwable ignored) {
            // 更新检查失败的正确表现是「什么都不提示」，绝不能影响启动。
            state = STATE_UNKNOWN;
        }
    }

    private static UpdateManifest.Entry fetchEntry() {
        Map<String, String> headers = new HashMap<String, String>();
        // 只用来让对面的日志能区分版本，不含任何用户标识。
        headers.put("User-Agent", "MuoniumPlayer/" + currentVersion + " (" + channelKey() + ")");
        headers.put("Accept", "application/json");

        for (String url : MANIFEST_URLS) {
            InputStream stream = null;
            try {
                stream = HttpUtils.get(url, null, headers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
                if (stream == null) {
                    continue;
                }
                UpdateManifest manifest = UpdateManifest.parse(HttpUtils.readString(stream));
                if (manifest == null) {
                    continue;
                }
                UpdateManifest.Entry entry = manifest.entryFor(mcVersion, loaderId);
                if (entry != null) {
                    return entry;
                }
            } catch (Throwable unreachable) {
                // 换下一个源。三个源全挂就是「什么都不提示」。
            } finally {
                closeQuietly(stream);
            }
        }
        return null;
    }

    private static void apply(UpdateManifest.Entry entry) {
        if (entry == null) {
            state = STATE_UNKNOWN;
            return;
        }
        latestVersion = entry.latest();
        downloadPage = isAllowedPage(entry.page()) ? entry.page() : HOME_PAGE;
        releaseNotes = entry.notes();
        critical = entry.critical();
        state = SemVer.isNewer(latestVersion, currentVersion) ? STATE_OUTDATED : STATE_UP_TO_DATE;
    }

    private static String channelKey() {
        return mcVersion + "-" + loaderId;
    }

    /** 缓存还新鲜（同一个 channel + 6 小时内）时直接用，完全不联网。 */
    private static UpdateManifest.Entry cachedEntry() {
        load();
        synchronized (LOCK) {
            if (cachedLatest.isEmpty() || !cachedChannel.equals(channelKey())) {
                return null;
            }
            long age = System.currentTimeMillis() - lastCheckAt;
            if (age < 0L || age > CHECK_INTERVAL_MILLIS) {
                return null;
            }
            return new UpdateManifest.Entry(cachedLatest, cachedPage, cachedNotes, cachedCritical);
        }
    }

    private static void rememberEntry(UpdateManifest.Entry entry) {
        load();
        synchronized (LOCK) {
            cachedChannel = channelKey();
            cachedLatest = entry.latest();
            cachedPage = entry.page();
            cachedNotes = entry.notes();
            cachedCritical = entry.critical();
            lastCheckAt = System.currentTimeMillis();
            save();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
            try {
                File file = ConfigPaths.UPDATE_NOTICE;
                if (!file.isFile()) {
                    return;
                }
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) {
                    return;
                }
                enabled = readBoolean(root, "enabled", true);
                skipVersion = readString(root, "skipVersion");
                lastCheckAt = readLong(root, "lastCheckAt");
                cachedChannel = readString(root, "channel");
                cachedLatest = readString(root, "latest");
                cachedPage = readString(root, "page");
                cachedNotes = readString(root, "notes");
                cachedCritical = readBoolean(root, "critical", false);
            } catch (Throwable ignored) {
                // 读坏了就当作全新状态：最坏结果是多打一次网络请求、多提示一次。
            }
        }
    }

    private static void save() {
        try {
            File parent = ConfigPaths.UPDATE_NOTICE.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("skipVersion", skipVersion);
            root.addProperty("lastCheckAt", lastCheckAt);
            root.addProperty("channel", cachedChannel);
            root.addProperty("latest", cachedLatest);
            root.addProperty("page", cachedPage);
            root.addProperty("notes", cachedNotes);
            root.addProperty("critical", cachedCritical);
            JsonConfigStorage.writeObject(ConfigPaths.UPDATE_NOTICE, GSON, root);
        } catch (Throwable ignored) {
        }
    }

    private static String readString(JsonObject root, String key) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) {
                return "";
            }
            String value = root.get(key).getAsString();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) {
                return fallback;
            }
            return root.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long readLong(JsonObject root, String key) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) {
                return 0L;
            }
            return root.get(key).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[MuoniumPlayer] update check: " + message);
    }
}
