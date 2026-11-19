package com.muoniumplayer.core.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 公告系统的状态与持久化。界面在 {@code com.muoniumplayer.core.screens.GuiAnnouncements}。
 *
 * <p>清单与更新提示<b>同一份文件</b>（{@code update.json} 的 {@code announcements} 数组），
 * 但这个子系统刻意自己拉、自己存、自己决定弹不弹：</p>
 * <ul>
 *   <li><b>记账对象不同</b>：更新提示记的是「这个版本跳过了几次」，公告记的是「哪些 id 看过」。
 *       两者写入时机也完全不同，塞进同一个 json 等于让一次写坏同时废掉两件事；</li>
 *   <li><b>互不牵连</b>：{@code UpdateChecker} 被一堆结构不变量测试钉着，公告这条路再要改一次
 *       就得陪它一起回归。而公告本来就不需要那套额度 / 失败阈值 / 重试的判定 —— 少一套状态
 *       就少一类能复发的 bug；</li>
 *   <li>该复用的还是复用：源地址 {@link UpdateChecker#MANIFEST_URLS}、域名白名单
 *       {@link UpdateChecker#isAllowedPage(String)}、以及「全部源同时拉、取版本号最高那一份」
 *       这条已经被实证过的规则。</li>
 * </ul>
 *
 * <p><b>弹出策略与更新提示不同档</b>：有未读公告时每次启动弹一次，<b>窗口一关就把这批全部记为
 * 已读</b>，之后只能从入口进。更新提示那套「每次启动都弹、三次额度用尽锁死」是为「别让用户停在
 * 有安全问题的版本上」设计的；公告没有这个理由，反复弹只会让人学会条件反射地关窗口，
 * 那时候真正要紧的公告也一样被关掉。</p>
 *
 * <p><b>失败一律静默</b>：拉不到就是「暂时没有公告」，不提醒、不计数、不给重试入口
 * （网络故障由更新提示那套负责说出来，同一份清单的两个源列表，没必要报两遍）。</p>
 */
public final class AnnouncementChecker {

    /** 已读 id 的落盘上限，只保留最近这些条：清单作者换 id 很快，老记录留着没有意义。 */
    public static final int MAX_READ_IDS = 100;

    /**
     * 缓存有效期。与更新检查同取 30 分钟。
     *
     * <p>公告本身变化不频繁，这个值本可以更长，但「维护公告」这类内容晚半小时到就是少半小时的
     * 触达；而 30 分钟一次的匿名 GET 对一个静态 JSON 来说不构成任何负担。</p>
     */
    private static final long CACHE_INTERVAL_MILLIS = 30L * 60L * 1000L;

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;

    /** 「所有源一起拉」的总等待预算，是给整体的，不是每个源各一份（同 {@code UpdateChecker}）。 */
    private static final long FETCH_BUDGET_MILLIS = 12000L;

    /** 链接文字为空时界面用的默认文案。放在服务层是为了让界面不必自己造一个。 */
    public static final String DEFAULT_LINK_TEXT = "查看详细说明";

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    /** One immutable publication: readers must not combine lists from different fetches. */
    public static final class Snapshot {
        private final List<Announcement> announcements;
        private final List<Announcement> sponsorships;
        private final List<Announcement> communities;

        public Snapshot(List<Announcement> announcements, List<Announcement> sponsorships,
                        List<Announcement> communities) {
            this.announcements = immutable(announcements);
            this.sponsorships = immutable(sponsorships);
            this.communities = immutable(communities);
        }

        public List<Announcement> announcements() {
            return this.announcements;
        }

        public List<Announcement> sponsorships() {
            return this.sponsorships;
        }

        public List<Announcement> communities() {
            return this.communities;
        }

        public boolean hasContent() {
            return !this.announcements.isEmpty() || !this.sponsorships.isEmpty()
                    || !this.communities.isEmpty();
        }

        private static List<Announcement> immutable(List<Announcement> cards) {
            return cards == null ? Collections.<Announcement>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Announcement>(cards));
        }
    }

    private static volatile Snapshot currentSnapshot = new Snapshot(null, null, null);
    /** Read-side calls never load files or wait on persistence. */
    private static volatile Set<String> readSnapshot = Collections.emptySet();
    private static final ExecutorService STATE_WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "muonium-announcement-state");
        thread.setDaemon(true);
        return thread;
    });
    /** 这一次启动里有没有拿到过任何一份清单（成功的或本地覆盖的）。界面据此区分「加载中」与「没有公告」。 */
    private static volatile boolean resolved;
    private static volatile boolean fetching;
    private static volatile boolean started;
    /** 本次启动是否已经弹过公告窗。弹过就不再弹，避免每次回到主菜单都糊一次全屏。 */
    private static volatile boolean shownThisSession;

    private static volatile String currentVersion = "";
    private static volatile String mcVersion = "";
    private static volatile String loaderId = "";

    // 以下几项由 LOCK 保护，只在持久化读写时访问。
    private static boolean loaded;
    private static boolean loading;
    private static boolean savePending;
    /** 已经看过的公告 id，按看过先后排列。 */
    private static final List<String> readIds = new ArrayList<String>();
    private static long cachedAt;
    private static String cachedChannel = "";
    private static String cachedBody = "";

    private AnnouncementChecker() {
    }

    /**
     * 启动时调用一次（在共享入口的 {@code init()} 里）。
     *
     * <p>三个参数与更新检查同一套：公告本身不分渠道，但「取哪一份清单」要按版本号比高低，
     * 而版本号是按 {@code <mc>-<loader>} 查出来的 —— 所以查表要的 mc / loader 这里也得给。
     * 共享源码不许出现任何加载器 API，加载器 id 只能由外壳登记后传进来。</p>
     */
    public static void beginFetch(String modVersion, String minecraftVersion, String loader) {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;
        }
        currentVersion = modVersion == null ? "" : modVersion.trim();
        mcVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
        loaderId = loader == null ? "" : loader.trim();
        startThread(false);
    }

    /**
     * 强制重拉一次，绕开 30 分钟缓存。公告窗底部的「刷新」用它。
     *
     * <p>正在拉的时候不叠第二条线程，返回 {@code false} —— 界面据此提示「上一次还在跑」。</p>
     */
    public static boolean refresh() {
        return startThread(true);
    }

    /** 当前公告列表（不可变，永不为 null）。 */
    public static List<Announcement> all() {
        return snapshot().announcements();
    }

    public static Snapshot snapshot() {
        return currentSnapshot;
    }

    public static boolean hasContent() {
        return snapshot().hasContent();
    }

    /** 还没拿到任何结论（启动后的头几秒、或点了刷新正在等）。 */
    public static boolean isFetching() {
        return fetching;
    }

    /** Have we accepted a valid manifest this session? False on an initial failed fetch. */
    public static boolean isResolved() {
        return resolved;
    }

    public static boolean isRead(String id) {
        return id != null && readSnapshot.contains(id);
    }

    /** 没读过的条数。侧栏那颗红点与页眉上的「N 条未读」都读它。 */
    public static int unreadCount() {
        List<Announcement> list = all();
        Set<String> read = readSnapshot;
        int unread = 0;
        for (Announcement announcement : list) {
            if (!read.contains(announcement.id())) {
                unread++;
            }
        }
        return unread;
    }

    public static boolean hasUnread() {
        return unreadCount() > 0;
    }

    /**
     * 设置界面那一行的标签，顺带把没读过的条数说出来。
     *
     * <p>数字比「有新内容」这种说法更能说明该不该点 —— 与 {@code HudSettingsModel} 里
     * 「立即清理 · 当前 xxx」那一行是同一个思路。</p>
     */
    public static String unreadLabel() {
        int unread = unreadCount();
        return unread > 0 ? "查看公告 · " + unread + " 条未读" : "查看公告";
    }

    /**
     * 现在该不该自动弹公告窗。
     *
     * <p>本次启动弹一次，且只在真的拿到过清单、并且里面有没读过的条目时弹。拉不到东西
     * 什么都不弹 —— 公告是加分项，不该因为网络问题去打扰用户（那是更新提示的职责）。</p>
     */
    public static boolean shouldShow() {
        if (shownThisSession || !resolved) {
            return false;
        }
        return unreadCount() > 0;
    }

    /** 由界面在真正显示出来的那一刻调用。 */
    public static void markShownThisSession() {
        shownThisSession = true;
    }

    /**
     * 把当前列表里的公告全部记为已读。
     *
     * <p>公告窗<b>任何</b>收起路径都调它（关闭按钮、ESC、点遮罩），从入口点进去看也算读过。
     * 记账的是「当前清单上的条目」，所以上面写着的那些即使一条都没展开，下次也不会再自动弹。</p>
     */
    public static void markAllRead() {
        List<Announcement> list = all();
        if (list.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<String>(list.size());
        for (Announcement announcement : list) {
            ids.add(announcement.id());
        }
        synchronized (LOCK) {
            boolean changed = false;
            for (String id : ids) {
                if (readIds.contains(id)) {
                    continue;
                }
                readIds.add(id);
                changed = true;
            }
            if (!changed) {
                return;
            }
            while (readIds.size() > MAX_READ_IDS) {
                readIds.remove(0);
            }
            publishReadIds();
            saveLater();
        }
    }

    /** 附带的链接能不能交给浏览器 / 剪贴板：必须是 https 且域名在白名单里。 */
    public static boolean isLinkAllowed(String link) {
        return UpdateChecker.isAllowedPage(link);
    }

    /** 一条链接的显示文字：清单没写就用默认文案。 */
    public static String linkText(Announcement announcement) {
        if (announcement == null) {
            return DEFAULT_LINK_TEXT;
        }
        return announcement.linkText().isEmpty() ? DEFAULT_LINK_TEXT : announcement.linkText();
    }

    // ==================== 内部实现 ====================

    private static boolean startThread(final boolean forceNetwork) {
        synchronized (LOCK) {
            if (fetching) {
                return false;
            }
            fetching = true;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runFetch(forceNetwork);
            }
        }, "muonium-announcement-fetch");
        thread.setDaemon(true);
        try {
            thread.start();
            return true;
        } catch (RuntimeException | Error cannotStart) {
            fetching = false;
            return false;
        }
    }

    private static void runFetch(boolean forceNetwork) {
        try {
            load();
            String body = localOverride();
            if (body != null) {
                log((apply(body) ? "local override " : "invalid local override ")
                        + ConfigPaths.ANNOUNCEMENTS_LOCAL.getName() + ", no network");
                return;
            }
            String cached = cachedBody(forceNetwork);
            if (cached != null && apply(cached)) {
                log("cache " + all().size() + " announcements");
                return;
            }
            String fetched = fetchAll();
            if (fetched == null) {
                // 一个源都没答：保持「还不知道」，下次启动或下次刷新再试，不做失败计数。
                log("unreachable, silent");
                return;
            }
            if (apply(fetched)) {
                remember(fetched);
                log(all().size() + " announcements, channel=" + channelKey());
            }
        } catch (Throwable ignored) {
            // 公告拉失败的正确表现是「什么都不提示」，绝不能影响启动。
        } finally {
            fetching = false;
        }
    }

    /**
     * 开发者覆盖：本地存在 {@code announcements_local.json} 时只用它。
     *
     * @return Content (possibly invalid/empty), or null only when no override file exists.
     */
    private static String localOverride() {
        return localOverride(ConfigPaths.ANNOUNCEMENTS_LOCAL);
    }

    static String localOverride(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            if (Files.size(file.toPath()) > UpdateManifest.MAX_MANIFEST_CHARS) {
                return "";
            }
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable unreadable) {
            log("local override unreadable: " + unreadable);
            // Existing override is authoritative even when invalid: never fall back to networking.
            return "";
        }
    }

    /** 缓存还新鲜（同一个 channel + 30 分钟内）时直接用，完全不联网。 */
    private static String cachedBody(boolean forceNetwork) {
        if (forceNetwork) {
            return null;
        }
        load();
        synchronized (LOCK) {
            if (cachedBody.isEmpty() || !cachedChannel.equals(channelKey())) {
                return null;
            }
            long age = System.currentTimeMillis() - cachedAt;
            if (age < 0L || age > CACHE_INTERVAL_MILLIS) {
                return null;
            }
            return cachedBody;
        }
    }

    /**
     * 把<b>全部</b>清单源同时拉一遍，取版本号最高的那一份的正文。
     *
     * <p>与更新检查同一条规则，理由也一样：各源缓存策略不同，同一时刻可能一个给新清单、
     * 一个给几小时前的旧清单，「谁先答」不代表「谁的新」。<b>取的是整份正文</b>而不是只取公告 ——
     * 公告与版本号必须来自同一个源，否则会出现「公告是新的、版本号是那一份旧的」这种拼接结论。</p>
     *
     * @return 胜出那一份清单的正文；没有任何源给出可读内容时 {@code null}
     */
    private static String fetchAll() {
        Map<String, String> headers = new HashMap<String, String>();
        // 只用来让对面的日志能区分版本，不含任何用户标识。
        headers.put("User-Agent", "MuoniumPlayer/" + currentVersion + " (announcements)");
        headers.put("Accept", "application/json");

        final int count = UpdateChecker.MANIFEST_URLS.length;
        final AtomicReferenceArray<String> bodies = new AtomicReferenceArray<String>(count);
        Thread[] workers = new Thread[count];
        for (int index = 0; index < count; index++) {
            final int slot = index;
            final String url = UpdateChecker.MANIFEST_URLS[index];
            workers[index] = new Thread(new Runnable() {
                @Override
                public void run() {
                    bodies.set(slot, fetchOne(url, headers));
                }
            }, "muonium-announcement-fetch-" + (index + 1));
            workers[index].setDaemon(true);
            workers[index].start();
        }

        long deadline = System.currentTimeMillis() + FETCH_BUDGET_MILLIS;
        for (Thread worker : workers) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            try {
                worker.join(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        String[] candidates = new String[count];
        for (int index = 0; index < count; index++) {
            candidates[index] = bodies.get(index);
        }
        return pickNewestBody(candidates);
    }

    /** Pure selection keeps same-version remote edits independent of response timing. */
    static String pickNewestBody(String... candidates) {
        if (candidates == null) {
            return null;
        }
        String winner = null;
        String winnerLatest = "";
        long winnerRevision = 0L;
        for (String body : candidates) {
            if (body == null) {
                // 预算用完了还没交卷，或这个源这次不可用：不连累其它源。
                continue;
            }
            UpdateManifest manifest = UpdateManifest.parse(body);
            if (manifest == null) {
                // 读到响应体但解析不出来（CDN 错误页、写坏的 JSON）：这份不能当结论用。
                continue;
            }
            UpdateManifest.Entry entry = manifest.entryFor(mcVersion, loaderId);
            String latest = entry == null ? "" : entry.latest();
            if (winner == null) {
                winner = body;
                winnerLatest = latest;
                winnerRevision = manifest.contentRevision();
                continue;
            }
            // Release version remains primary; contentRevision only breaks semantic-version ties.
            // Legacy equal revisions retain source order, including content-only manifests.
            if ((!latest.isEmpty() && SemVer.isNewer(latest, winnerLatest))
                    || (SemVer.compare(latest, winnerLatest) == 0
                        && manifest.contentRevision() > winnerRevision)) {
                winner = body;
                winnerLatest = latest;
                winnerRevision = manifest.contentRevision();
            }
        }
        return winner;
    }

    /** 拉一个源。任何异常都只是「这个源这次不可用」，绝不往上抛。 */
    private static String fetchOne(String url, Map<String, String> headers) {
        InputStream stream = null;
        try {
            stream = HttpUtils.get(url, null, headers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
            if (stream == null) {
                return null;
            }
            return readManifest(stream);
        } catch (Throwable unreachable) {
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    static String readManifest(InputStream stream) throws IOException {
        StringBuilder body = new StringBuilder();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (body.length() + read > UpdateManifest.MAX_MANIFEST_CHARS) {
                    throw new IOException("Manifest text exceeds budget");
                }
                body.append(buffer, 0, read);
            }
        }
        return body.toString();
    }

    private static boolean apply(String body) {
        UpdateManifest manifest = UpdateManifest.parse(body);
        if (manifest == null) {
            // Failed refresh/invalid override must not erase the last useful publication.
            return false;
        }
        currentSnapshot = new Snapshot(manifest.announcements(), manifest.sponsorships(),
                manifest.communities());
        resolved = true;
        return true;
    }

    private static String channelKey() {
        return mcVersion + "-" + loaderId;
    }

    private static void remember(String body) {
        load();
        synchronized (LOCK) {
            cachedChannel = channelKey();
            cachedBody = body;
            cachedAt = System.currentTimeMillis();
            saveLater();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded || loading) {
                return;
            }
            loading = true;
        }
        JsonObject root = null;
        try {
            File file = ConfigPaths.ANNOUNCEMENTS;
            if (file.isFile()) {
                root = JsonConfigStorage.readObject(file, GSON);
            }
        } catch (Throwable ignored) {
            // Corrupt state is an empty disk snapshot, not a UI error.
        }
        acceptSavedState(root);
    }

    /** Merge an off-thread disk read with any close action that happened while reading. */
    static void acceptSavedState(JsonObject root) {
        List<String> persisted = new ArrayList<String>();
        if (root != null && root.has("readIds") && root.get("readIds").isJsonArray()) {
            JsonArray ids = root.getAsJsonArray("readIds");
            for (int index = 0; index < ids.size(); index++) {
                try {
                    if (ids.get(index).isJsonPrimitive()
                            && ids.get(index).getAsJsonPrimitive().isString()) {
                        String id = ids.get(index).getAsString().trim();
                        if (!id.isEmpty() && id.length() <= Announcement.MAX_ID_CHARS) {
                            persisted.add(id);
                        }
                    }
                } catch (RuntimeException malformedItem) {
                    // Ignore this item only.
                }
            }
        }
        long savedAt = stateLong(root, "cachedAt");
        String savedChannel = stateString(root, "cachedChannel");
        String savedBody = stateString(root, "cachedBody");
        synchronized (LOCK) {
            List<String> merged = mergeReadIds(persisted, readIds);
            readIds.clear();
            readIds.addAll(merged);
            cachedAt = savedAt;
            cachedChannel = savedChannel;
            cachedBody = savedBody.length() > UpdateManifest.MAX_MANIFEST_CHARS ? "" : savedBody;
            loaded = true;
            loading = false;
            publishReadIds();
            if (savePending) {
                savePending = false;
                saveLater();
            }
        }
    }

    static List<String> mergeReadIds(List<String> persisted, List<String> recent) {
        List<String> merged = new ArrayList<String>();
        for (String id : persisted) {
            if (!merged.contains(id)) {
                merged.add(id);
            }
        }
        for (String id : recent) {
            merged.remove(id);
            merged.add(id);
        }
        while (merged.size() > MAX_READ_IDS) {
            merged.remove(0);
        }
        return merged;
    }

    private static long stateLong(JsonObject root, String key) {
        try {
            return root == null || !root.has(key) ? 0L : root.get(key).getAsLong();
        } catch (RuntimeException invalid) {
            return 0L;
        }
    }

    private static String stateString(JsonObject root, String key) {
        try {
            return root == null || !root.has(key) ? "" : root.get(key).getAsString();
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private static void publishReadIds() {
        readSnapshot = Collections.unmodifiableSet(new HashSet<String>(readIds));
    }

    /** Capture under LOCK; a single daemon writer serializes disk writes outside LOCK. */
    private static void saveLater() {
        if (!loaded) {
            // Preserve the on-disk cache/read ids until the outstanding load can merge them.
            savePending = true;
            return;
        }
        JsonObject root = new JsonObject();
        JsonArray ids = new JsonArray();
        for (String id : readIds) {
            ids.add(id);
        }
        root.add("readIds", ids);
        root.addProperty("cachedAt", cachedAt);
        root.addProperty("cachedChannel", cachedChannel);
        root.addProperty("cachedBody", cachedBody);
        STATE_WRITER.execute(() -> save(root));
    }

    private static void save(JsonObject root) {
        save(ConfigPaths.ANNOUNCEMENTS, root);
    }

    static void save(File file, JsonObject root) {
        try {
            JsonConfigStorage.writeObject(file, GSON, root);
        } catch (Throwable ignored) {
            // Persistence failure is silent, like network failure.
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
        System.out.println("[MuoniumPlayer] announcements: " + message);
    }
}
