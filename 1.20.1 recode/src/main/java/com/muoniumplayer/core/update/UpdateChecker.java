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
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 更新检查的状态与持久化。界面在 {@code com.muoniumplayer.core.screens.GuiUpdateNotice}。
 *
 * <p>结构与 {@code FfmpegNotice} 刻意保持一致（后台探一次 + 本次启动只弹一次）：
 * 那套「守护线程探测 + 每 tick 轮询是否该弹」的骨架已经在四个版本上跑通过，
 * 这里换的只是数据来源 —— 从「探本机有没有 ffmpeg」换成「拉一份远端版本清单」。</p>
 *
 * <p>网络行为（<b>不可关闭</b> —— 曾经有过一个「不再检查更新」的开关，现在已删掉：检查只发一个
 * 匿名 GET，而用户一旦关掉就再也收不到新版本与安全提醒，代价远大于那点请求）：
 * <ul>
 *   <li>启动后在守护线程上把清单源<b>全部同时拉一遍</b>（连接与读取超时各 5 秒，总预算 12 秒），
 *       取版本号最高的那一份；个别源失败不影响结论；</li>
 *   <li>结果在 {@code config/muonium/update_notice.json} 里缓存 30 分钟，期间完全不联网；</li>
 *   <li>只发匿名 GET，不带任何用户标识，也绝不自动下载或替换 jar。</li>
 * </ul>
 *
 * <p><b>两种「不让用户随手滑过去」的策略</b>（都只作用于提示窗，不会改动用户的游戏内容）：
 * <ol>
 *   <li><b>「先不更新」有次数上限</b>：结论是「有更新」时，<b>每次启动都会重新弹</b>这个提示 ——
 *       额度决定的不是「弹不弹」，而是「还能不能先不更新」。同一个新版本最多先不更新
 *       {@link #MAX_SKIP_COUNT} 次（每次收起提示算一次，见 {@link #noteSkippedOnce()}），
 *       用尽后 {@link #mustUpdate()} 成立，界面不再提供任何绕过更新的出口（只剩「前往下载」）。
 *       清单换成更新的版本时额度重新给满 —— 每个新版本各有一份，不会被历史记录连坐。</li>
 *   <li><b>连不上服务器会被点出来</b>：连续 {@link #MAX_FAILURE_COUNT} 次启动都没能连上清单
 *       服务器时 {@link #STATE_CHECK_FAILED} 成立，提示用户检查网络。注意「连上了但清单里没有
 *       本 channel 的条目」不算失败：那是服务器有回应，只是这次没发布。</li>
 * </ol>
 * 两个计数都落在 {@code update_notice.json} 里 —— 只放内存等于每次重启都归零，限制形同虚设。</p>
 */
public final class UpdateChecker {

    /**
     * 官方下载页。
     *
     * <p>「前往下载」的固定落点，也是清单里的 {@code page} 为空 / 不在白名单时的兜底。
     * 地址写死在客户端而不是只信清单：清单是公开仓库上的纯文本，万一被篡改，这一条至少能保证
     * 用户去的是我们自己的页面。</p>
     *
     * <p>老客户端（白名单里还没有这个域名的版本）拿到指向这里的 {@code page} 会判定不合规，
     * 退到它们各自内置的 GitHub 仓库页 —— 能打开，只是不在官网，不会变成打不开。</p>
     */
    public static final String DOWNLOAD_PAGE = "https://melodifymc.cc.cd/download";

    /**
     * 清单源。启动时<b>全部同时拉</b>（每个源一条线程），再取版本号最高的那一份
     * —— 见 {@link #fetchEntry()} 与 {@link #pickNewest}。
     *
     * <p>为什么不是「第一个成功就用」：各源的缓存策略不一样，同一时刻可能一个给新清单、
     * 一个给几小时前的旧清单。又因为每一次谁快谁慢都可能变，所以「谁先答」并不能代表「谁的新」
     * —— 全部试一遍取最高的，才不会因为恰好先问到了缓存源而漏掉新版本。</p>
     *
     * <p>三个都实测过（curl 直连，本机在国内）：ghfast.top 约 2.4s、raw 约 1.0s、
     * gcore.jsdelivr.net 约 1.3s。raw 在部分网络下不可达（实测一小时之内会从「秒回」变成
     * 「连续超时」），所以必须有加速入口兜底。顺序现在只决定「版本号相同时用谁那一条」，
     * 不再决定取到哪个版本。</p>
     *
     * <p>{@code ghfast.top} 是 raw 的加速入口，响应头里的 {@code Cache-Control} 是
     * {@code max-age=300}（5 分钟），比 jsDelivr 的 12 小时新得多，所以排在最前面。</p>
     */
    public static final String[] MANIFEST_URLS = {
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/update.json",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/update.json",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/update.json",
    };

    /** 还没查完，或者这一次没能得出任何结论。 */
    public static final int STATE_UNKNOWN = 0;
    /** 已经是最新版。 */
    public static final int STATE_UP_TO_DATE = 1;
    /** 远端有更新的版本。 */
    public static final int STATE_OUTDATED = -1;
    /**
     * 连续多次启动都没能连上清单服务器。
     *
     * <p>与 {@link #STATE_UNKNOWN} 分开：UNKNOWN 只是「还不知道」，这个状态是「已经连续失败到
     * 值得提醒用户检查网络了」，界面会明说次数。</p>
     */
    public static final int STATE_CHECK_FAILED = -2;

    /**
     * 同一个新版本最多允许「先不更新」几次。用尽后界面只留「前往下载」，不再提供任何绕过的出口。
     */
    public static final int MAX_SKIP_COUNT = 3;

    /** 连续多少次启动都没连上清单服务器，就提示用户检查网络。 */
    public static final int MAX_FAILURE_COUNT = 3;

    /** 缓存有效期。30 分钟足以挡掉频繁重启的重复请求，又不至于让用户等太久才知道有新版。 */
    private static final long CHECK_INTERVAL_MILLIS = 30L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 5000;

    /**
     * 「所有源一起拉」的总等待预算 —— 是给整体的，不是每个源各一份。
     *
     * <p>单源的上限是 connect + read = 10 秒，所以 12 秒足够让正常源交卷；到点还没回来的只可能
     * 是卡在超时管不到的环节（例如 DNS 解析），那一次就当这个源不可用。之所以要一个<b>总量</b>：
     * 源的数量以后还会加，等待时间必须是常数，不能随源数线性涨。</p>
     */
    private static final long FETCH_BUDGET_MILLIS = 12000L;

    /** 失败次数的落盘上限：纯粹为了让配置文件里的数字不会无限涨上去。 */
    private static final int FAILURE_COUNT_CAP = 99;

    /**
     * 【临时调试开关】强制把一个界面顶出来看效果，看完必须改回 {@code OFF}。
     *
     * <p>清单里的版本号目前不比本地新（远端 1.8.0 / 本地 1.8.0），走真实链路根本弹不出来，
     * 所以调试期直接指定要预览哪一套界面：</p>
     * <ul>
     *   <li>{@code OFF} —— 正常逻辑，只有这一个值是发布状态；</li>
     *   <li>{@code OUTDATED} —— 预览「有新版本」那一套（内容取 {@link #previewEntry()}，
     *       版本号写死 1.8.1，断网也能稳定复现；可以连点三次「跳过」看锁死效果）；</li>
     *   <li>{@code FAILED} —— 预览「连不上服务器」那一套（不联网，直接进失败状态）。</li>
     * </ul>
     *
     * <p>调试状态下检查线程不联网、失败计数也不写盘。注意 {@code OUTDATED} 预览会真的把
     * 额度用掉：测完请把 {@code config/muonium/update_notice.json} 删掉，否则里面记的
     * {@code skipCountVersion=1.8.1} 会让以后真发 1.8.1 时的额度从 0 开始 ——
     * 用户一看到提示就是锁死状态。</p>
     */
    private enum Preview { OFF, OUTDATED, FAILED }

    private static final Preview PREVIEW = Preview.OFF;

    /**
     * 下载页的域名白名单。
     *
     * <p>清单是纯文本、放在公开仓库上：万一被篡改，「点一下就跳浏览器」这一步会变成把用户
     * 导去任意站点。所以域名由客户端写死，不在名单里就退到 {@link #DOWNLOAD_PAGE}。</p>
     */
    private static final String[] ALLOWED_PAGE_HOSTS = {
            "melodifymc.cc.cd",
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
    /** 检查线程是否正在跑。用户点「重试检查」时靠它避免叠起第二条线程。 */
    private static volatile boolean checkInFlight;
    /** 本次启动是否已经弹过。弹过就不再弹，避免每次回到主菜单都糊一次全屏。 */
    private static volatile boolean shownThisSession;

    private static volatile String currentVersion = "";
    private static volatile String mcVersion = "";
    private static volatile String loaderId = "";
    private static volatile String latestVersion = "";
    private static volatile String downloadPage = DOWNLOAD_PAGE;
    private static volatile String releaseNotes = "";
    private static volatile boolean critical;

    // 以下几项由 LOCK 保护，只在持久化读写时访问。
    private static boolean loaded;
    /** 针对 {@link #skipCountVersion} 已经「先不更新」过几次。 */
    private static int skipCount;
    /** {@link #skipCount} 记的是哪个版本的账。清单换版本时这一对一起重置。 */
    private static String skipCountVersion = "";
    /** 连续几次启动没连上清单服务器。任何一次连上就清零。 */
    private static int failureCount;
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
            checkInFlight = true;
        }
        currentVersion = modVersion == null ? "" : modVersion.trim();
        mcVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
        loaderId = loader == null ? "" : loader.trim();
        if (PREVIEW != Preview.OFF) {
            // 调试开关：不等后台线程，立刻把预览内容填上，免得第一帧显示空版本号。
            applyPreview();
            log("preview mode " + PREVIEW + ", no network");
        }
        startCheckThread();
    }

    /**
     * 「重试检查」：再起一次后台检查，让用户不用重启游戏就能从网络故障里恢复。
     *
     * <p>正在跑的时候不叠第二条线程，返回 false —— 界面据此提示「上一次还在跑」。</p>
     *
     * @return 是否真的发起了这次重试
     */
    public static boolean retryCheck() {
        synchronized (LOCK) {
            if (checkInFlight) {
                return false;
            }
            checkInFlight = true;
        }
        state = STATE_UNKNOWN;
        startCheckThread();
        return true;
    }

    /**
     * 把「本次启动已经弹过」的标记清掉，允许宿主再弹一次。
     *
     * <p>只有一种用法：用户在「连不上服务器」那个界面上点了重试、并且这次连上了。重试可能
     * 查出新版本，而当前这个提示窗是「连不上」那一套组件，装不下版本号与更新说明 —— 放开标记，
     * 让宿主下一 tick 用新结论重新弹一个。</p>
     */
    public static void allowNoticeAgain() {
        shownThisSession = false;
    }

    /** {@link #STATE_UNKNOWN} / {@link #STATE_UP_TO_DATE} / {@link #STATE_OUTDATED} / {@link #STATE_CHECK_FAILED}。 */
    public static int state() {
        return state;
    }

    public static String currentVersion() {
        return currentVersion;
    }

    public static String latestVersion() {
        return latestVersion;
    }

    /** 一定是 https 且域名在白名单里（清单给的地址不合规时是 {@link #DOWNLOAD_PAGE}）。 */
    public static String downloadPage() {
        return downloadPage;
    }

    public static String releaseNotes() {
        return releaseNotes;
    }

    /** true 时清单把这次更新标成了关键更新，界面上同样不提供「跳过这个版本」。 */
    public static boolean isCritical() {
        return critical;
    }

    public static String channel() {
        return channelKey();
    }

    /** 当前这个版本还能「先不更新」几次。关键更新、或额度已用尽时是 0。 */
    public static int remainingSkips() {
        if (critical) {
            return 0;
        }
        String latest = latestVersion;
        if (latest.isEmpty()) {
            return 0;
        }
        load();
        synchronized (LOCK) {
            // 清单换成别的版本了 → 记账对象不同，额度重新给满：每个新版本各有一份。
            if (!latest.equals(skipCountVersion)) {
                return MAX_SKIP_COUNT;
            }
            return Math.max(0, MAX_SKIP_COUNT - skipCount);
        }
    }

    /** 当前这个版本已经「先不更新」过几次（界面用它解释为什么那个出口不见了）。 */
    public static int usedSkips() {
        String latest = latestVersion;
        if (latest.isEmpty()) {
            return 0;
        }
        load();
        synchronized (LOCK) {
            return latest.equals(skipCountVersion) ? Math.max(0, skipCount) : 0;
        }
    }

    /** 还能跳过这个版本吗。额度用尽或关键更新时为 false。 */
    public static boolean canSkip() {
        return remainingSkips() > 0;
    }

    /**
     * 必须更新：结论是「有更新」，而跳过额度已经用尽（或这次是关键更新）。
     *
     * <p>界面上据此不再提供任何绕过更新的出口，只留「前往下载」。</p>
     */
    public static boolean mustUpdate() {
        return state == STATE_OUTDATED && !canSkip();
    }

    /** 连续几次启动没连上清单服务器。 */
    public static int failureCount() {
        if (PREVIEW == Preview.FAILED) {
            return MAX_FAILURE_COUNT;
        }
        load();
        synchronized (LOCK) {
            return failureCount;
        }
    }

    /** 连续失败次数是否已经达到该提醒用户的阈值。 */
    public static boolean hasFailedTooOften() {
        if (PREVIEW == Preview.FAILED) {
            return true;
        }
        load();
        synchronized (LOCK) {
            return failureCount >= MAX_FAILURE_COUNT;
        }
    }

    /**
     * 现在该不该弹提示。
     *
     * <p>两种结论会弹：</p>
     * <ul>
     *   <li><b>有更新</b> —— <b>每次启动都弹</b>，与「先不更新」用掉几次无关。额度用尽的区别是
     *       界面上不再给出任何绕过的出口（见 {@link #mustUpdate()}），而不是干脆不弹 ——
     *       不弹的话额度永远用不完，也就谈不上「次数用尽」。</li>
     *   <li><b>连续多次连不上服务器</b> —— 提醒用户检查网络。</li>
     * </ul>
     * 还没查完时返回 {@code false} 而不是等待 —— 调用方在客户端 tick 里，下一 tick 再问一次即可。
     */
    public static boolean shouldShow() {
        if (shownThisSession) {
            return false;
        }
        if (PREVIEW != Preview.OFF) {
            return true;
        }
        if (state == STATE_CHECK_FAILED) {
            return hasFailedTooOften();
        }
        return state == STATE_OUTDATED && !latestVersion.isEmpty();
    }

    /** 由界面在真正显示出来的那一刻调用。 */
    public static void markShownThisSession() {
        shownThisSession = true;
    }

    /**
     * 记一次「先不更新」：把当前版本的额度用掉一格。
     *
     * <p>额度是由界面在用户收起提示时调的（按钮、ESC 都算一次），这里再挡一道：已经用尽时
     * 只当作「收起提示」，不再往上加计数。</p>
     */
    public static void noteSkippedOnce() {
        String latest = latestVersion;
        if (latest.isEmpty()) {
            return;
        }
        load();
        synchronized (LOCK) {
            if (!latest.equals(skipCountVersion)) {
                // 换版本了：这一次算新版本的第 1 次。
                skipCountVersion = latest;
                skipCount = 0;
            }
            if (skipCount < MAX_SKIP_COUNT) {
                skipCount++;
            }
            save();
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

    private static void startCheckThread() {
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

    private static void runCheck() {
        try {
            if (PREVIEW != Preview.OFF) {
                // 调试预览：不联网，界面要的内容由 applyPreview 填好了（重试也只重放它一次）。
                applyPreview();
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
            if (entry != null) {
                apply(entry);
                log("cache latest=" + latestVersion + " current=" + currentVersion
                        + " channel=" + channelKey() + " state=" + state);
                return;
            }

            FetchOutcome outcome = fetchEntry();
            if (outcome.reachedServer) {
                // 服务器有回应（哪怕这一次没有本 channel 的条目）＝ 网络没问题：失败计数清零。
                noteReachable();
            } else {
                noteUnreachable();
            }
            if (outcome.entry != null) {
                rememberEntry(outcome.entry);
            }
            apply(outcome.entry);
            if (state == STATE_UNKNOWN && hasFailedTooOften()) {
                state = STATE_CHECK_FAILED;
            }
            log((outcome.reachedServer ? "remote" : "unreachable") + " latest=" + latestVersion
                    + " current=" + currentVersion + " channel=" + channelKey()
                    + " state=" + state + " failures=" + failureCount());
        } catch (Throwable ignored) {
            // 更新检查失败的正确表现是「什么都不提示」，绝不能影响启动。
            state = STATE_UNKNOWN;
        } finally {
            checkInFlight = false;
        }
    }

    /** 一次清单抓取的结果：拿到了什么，以及「服务器到底有没有回应」。 */
    private static final class FetchOutcome {

        private final UpdateManifest.Entry entry;
        /**
         * 是否收到过任何一份响应体。它和 {@code entry == null} 是两件事：
         * 拿到了清单但里面没有本 channel 的条目，说明网络是通的，不该计进失败次数。
         */
        private final boolean reachedServer;

        private FetchOutcome(UpdateManifest.Entry entry, boolean reachedServer) {
            this.entry = entry;
            this.reachedServer = reachedServer;
        }
    }

    /** 单个源的结果：拿到哪一个条目（可能没有），以及到底有没有收到响应体。 */
    private static final class SourceResult {

        private final UpdateManifest.Entry entry;
        private final boolean responded;

        private SourceResult(UpdateManifest.Entry entry, boolean responded) {
            this.entry = entry;
            this.responded = responded;
        }
    }

    /**
     * 把<b>全部</b>清单源同时拉一遍，然后取版本号最高的那一份。
     *
     * <p>并发而不是排队：三个源串起来最坏要等 3 × 10 秒，并发只需要等最慢的那一个
     * （实测约 2.4 秒）。等不齐也没关系——等不到的那个源按「这次不可用」处理，其它源的结果照用。
     * 工作线程与检查线程都是守护线程，到点收工也不会挡住游戏退出。</p>
     */
    private static FetchOutcome fetchEntry() {
        Map<String, String> headers = new HashMap<String, String>();
        // 只用来让对面的日志能区分版本，不含任何用户标识。
        headers.put("User-Agent", "MuoniumPlayer/" + currentVersion + " (" + channelKey() + ")");
        headers.put("Accept", "application/json");

        final int count = MANIFEST_URLS.length;
        // AtomicReferenceArray：worker 线程写、检查线程读，跨线程可见性交给它，别再自己拼同步。
        final AtomicReferenceArray<SourceResult> results = new AtomicReferenceArray<SourceResult>(count);
        Thread[] workers = new Thread[count];
        for (int index = 0; index < count; index++) {
            final int slot = index;
            final String url = MANIFEST_URLS[index];
            workers[index] = new Thread(new Runnable() {
                @Override
                public void run() {
                    results.set(slot, fetchOne(url, headers));
                }
            }, "muonium-update-fetch-" + (index + 1));
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

        boolean reachedServer = false;
        UpdateManifest.Entry[] candidates = new UpdateManifest.Entry[count];
        StringBuilder trace = new StringBuilder();
        for (int index = 0; index < count; index++) {
            SourceResult result = results.get(index);
            trace.append(" #").append(index + 1);
            if (result == null) {
                // 预算用完了还没交卷：这一次当它不可用，但不连累其它源。
                trace.append("=timeout");
                continue;
            }
            if (result.responded) {
                reachedServer = true;
            }
            if (result.entry == null) {
                trace.append(result.responded ? "=none" : "=fail");
                continue;
            }
            trace.append('=').append(result.entry.latest());
            candidates[index] = result.entry;
        }
        log("sources" + trace);

        return new FetchOutcome(pickNewest(candidates), reachedServer);
    }

    /** 拉一个源并读出本 channel 的条目。任何异常都只是「这个源这次不可用」，绝不往上抛。 */
    private static SourceResult fetchOne(String url, Map<String, String> headers) {
        InputStream stream = null;
        try {
            stream = HttpUtils.get(url, null, headers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
            if (stream == null) {
                return new SourceResult(null, false);
            }
            String body = HttpUtils.readString(stream);
            if (body == null) {
                return new SourceResult(null, false);
            }
            // 读到响应体就算「连上了」——后面解析失败、或没有本 channel 的条目都不算网络故障。
            UpdateManifest manifest = UpdateManifest.parse(body);
            if (manifest == null) {
                return new SourceResult(null, true);
            }
            return new SourceResult(manifest.entryFor(mcVersion, loaderId), true);
        } catch (Throwable unreachable) {
            return new SourceResult(null, false);
        } finally {
            closeQuietly(stream);
        }
    }

    /**
     * 从各源交上来的条目里挑版本号最高的那份（{@code null} 与空版本号一律跳过）。
     *
     * <p>包级可见是为了让静态测试直接钉住这条规则 —— 它不需要网络。同版本时取<b>先出现的</b>
     * 那个，也就是 {@link #MANIFEST_URLS} 里排前面的源。</p>
     */
    static UpdateManifest.Entry pickNewest(UpdateManifest.Entry... candidates) {
        if (candidates == null) {
            return null;
        }
        UpdateManifest.Entry newest = null;
        for (UpdateManifest.Entry candidate : candidates) {
            if (candidate == null || candidate.latest().isEmpty()) {
                continue;
            }
            if (newest == null || SemVer.isNewer(candidate.latest(), newest.latest())) {
                newest = candidate;
            }
        }
        return newest;
    }

    private static void apply(UpdateManifest.Entry entry) {
        if (entry == null) {
            state = STATE_UNKNOWN;
            return;
        }
        latestVersion = entry.latest();
        downloadPage = isAllowedPage(entry.page()) ? entry.page() : DOWNLOAD_PAGE;
        releaseNotes = entry.notes();
        critical = entry.critical();
        state = SemVer.isNewer(latestVersion, currentVersion) ? STATE_OUTDATED : STATE_UP_TO_DATE;
    }

    /** 调试模式下要预览的那套内容（见 {@link Preview}）。 */
    private static void applyPreview() {
        if (PREVIEW == Preview.FAILED) {
            latestVersion = "";
            state = STATE_CHECK_FAILED;
            return;
        }
        apply(previewEntry());
    }

    /** 调试开关打开时用的演示条目：版本号故意比当前高一档，保证界面进入「有更新」的分支。 */
    private static UpdateManifest.Entry previewEntry() {
        return new UpdateManifest.Entry(
                "1.8.1",
                DOWNLOAD_PAGE,
                "新增：重写 ffmpeg 缺失提示与更新提示的界面渲染；"
                        + "修复：界面文字因宽度上限被裁成空串而整行不显示；"
                        + "修复：界面文字颜色缺少透明度导致完全不可见。"
                        + "这是一段特意写长的更新说明，用来检查正文换行、面板高度与按钮位置是否仍然正确。",
                false);
    }

    private static String channelKey() {
        return mcVersion + "-" + loaderId;
    }

    /** 连续失败计数清零。已经是 0 时不动盘。 */
    private static void noteReachable() {
        load();
        synchronized (LOCK) {
            if (failureCount == 0) {
                return;
            }
            failureCount = 0;
            save();
        }
    }

    /** 连续失败计数 +1 并落盘：只放内存的话，重启就归零，永远攒不到阈值。 */
    private static void noteUnreachable() {
        load();
        synchronized (LOCK) {
            if (failureCount >= FAILURE_COUNT_CAP) {
                return;
            }
            failureCount++;
            save();
        }
    }

    /** 缓存还新鲜（同一个 channel + 30 分钟内）时直接用，完全不联网。 */
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
                skipCount = readInt(root, "skipCount");
                skipCountVersion = readString(root, "skipCountVersion");
                failureCount = readInt(root, "failureCount");
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
            root.addProperty("skipCount", skipCount);
            root.addProperty("skipCountVersion", skipCountVersion);
            root.addProperty("failureCount", failureCount);
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

    private static int readInt(JsonObject root, String key) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) {
                return 0;
            }
            return root.get(key).getAsInt();
        } catch (Throwable ignored) {
            return 0;
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
