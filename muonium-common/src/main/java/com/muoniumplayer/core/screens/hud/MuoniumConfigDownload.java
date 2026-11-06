package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.utils.network.HttpDownloadRace;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MuoniumConfig（HUD 设置界面）的按需下载。
 *
 * <p>它是 OneConfig 的精简分支，带 mixin 与整套 Compose 界面，塞进主包会让成品多出
 * 9.5 MB。因此改成首次启动按需拉取，与 Skija 伴生包同一套做法：多镜像竞速、字节数与
 * sha256 双钉、装好后<b>必须重启</b>才生效（Fabric 在启动阶段扫描 mods/ 并织入 mixin，
 * 运行中塞进去的 jar 不会被加载，热加载没有可行性）。</p>
 *
 * <p>为什么落在 {@code mods/}：这是玩家手动装 mod 的同一位置，重启后由 Fabric 自己发现。
 * 放 config 目录再由我们手动挂 classloader 也能做到，但那样要自己处理 mod 发现与 mixin
 * 配置注册，出错面大得多。</p>
 *
 * <p>失败不阻挡进游戏：MuoniumConfig 只提供设置界面，下载失败时主体功能（听歌、HUD 绘制）
 * 照常运行，只少了那个界面。这与 Skija 缺原生库不同 —— 那个缺了渲染层整个起不来。</p>
 */
public final class MuoniumConfigDownload {
    private static final String TAG = "MuoniumConfigDownload";

    /**
     * 下载镜像链的公共前缀。
     *
     * <p>目录下按「MC 版本 / 加载器」再分子目录，例如
     * {@code muoniumconfig/26.3/fabric/muoniumconfig-1.6.1.jar}。</p>
     */
    private static final List<String> MIRRORS = List.of(
            "https://file.melodifymc.cc.cd/muoniumconfig/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/muoniumconfig/",
            "http://muoniummc.cc.cd/dl/muoniumconfig/",
            "https://melodifymc.cc.cd/dl/muoniumconfig/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/muoniumconfig/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/muoniumconfig/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/muoniumconfig/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/muoniumconfig/");

    /**
     * 每个「MC 版本 + 加载器」对应哪个包。
     *
     * <p>为什么必须分桶：MuoniumConfig 是用 Stonecutter 为每个 MC 版本单独编译的 ——
     * mixin 目标、映射名、Compose 版本全都不同。给 1.21.1 塞一个 26.3 编译出来的 jar，
     * 结果是启动期 mixin 找不到目标方法而崩溃，那比「没有设置界面」严重得多。</p>
     *
     * <p>表里没有的组合就<b>不下载</b>：宁可少一个设置界面，也不能装一个必然崩的包。
     * 玩家仍可自行把对应版本的 jar 放进 mods/（{@link #isInstalled()} 会认它）。</p>
     *
     * <p>新增发布线时在这里加一行。BUILD 是发布清单里那个子目录名，
     * 与 Stonecutter 的 {@code <版本>-<加载器>} 命名保持一致。</p>
     */
    private static final Map<String, Build> BUILDS = Map.of(
            "26.3/fabric", new Build("muoniumconfig-1.6.1.jar", 9937711L,
                    "697fb9e2f4d5efd23e4e9359c95f56903df2a655bff297c67ca6538bf4077894"));

    /** 一个可下载的构建：文件名 + 钉死的字节数与 sha256。 */
    private static final class Build {
        final String fileName;
        final long bytes;
        final String sha256;

        Build(String fileName, long bytes, String sha256) {
            this.fileName = fileName;
            this.bytes = bytes;
            this.sha256 = sha256;
        }

        /** 字节数与 sha256 都合法才算钉死；任一缺失就当作「暂无此版本的包」。 */
        boolean isPinned() {
            return bytes > 0 && sha256 != null && sha256.length() == 64;
        }
    }

    /** 开关：{@code -Dmuonium.muoniumconfig.autoDownload=false} 可关。 */
    private static final String PROP_ENABLED = "muonium.muoniumconfig.autoDownload";

    private static final Object LOCK = new Object();
    private static final int MAX_ATTEMPTS = 3;

    private static volatile State state = new State(Phase.IDLE, 0, null);
    private static volatile boolean inFlight;
    private static int attempts;
    private static long nextAttemptAtNanos;

    /** 本次会话选中的构建；null 表示这个 MC 版本/加载器没有对应的包。 */
    private static volatile Build build;

    /** 下载装好后需要重启才生效。 */
    private static volatile boolean restartRequired;

    private MuoniumConfigDownload() {
    }

    /** 下载状态；提示层读它决定说什么。 */
    public enum Phase {
        /** 没触发过，或不需要（已装好）。 */
        IDLE,
        DOWNLOADING,
        DONE,
        FAILED
    }

    /** 状态快照。{@code percent} 只对 DOWNLOADING 有意义，{@code detail} 只对 FAILED。 */
    public static final class State {
        private final Phase phase;
        private final int percent;
        private final String detail;

        State(Phase phase, int percent, String detail) {
            this.phase = phase;
            this.percent = percent;
            this.detail = detail;
        }

        public Phase phase() {
            return phase;
        }

        public int percent() {
            return percent;
        }

        public String detail() {
            return detail;
        }
    }

    public static State state() {
        return state;
    }

    /** 本会话是否已装好、需要重启才生效。 */
    public static boolean isRestartRequired() {
        return restartRequired;
    }

    private static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /**
     * 目标落点：游戏目录的 mods/。
     *
     * <p>文件名就是发布时的名字（含版本号）。不同 MC 版本的文件名可能撞车（比如两条线
     * 都是 1.6.1），但它们本来就只在各自的游戏目录里存在，不会互相覆盖。</p>
     */
    private static File targetJar() {
        Build selected = build;
        return new File(new File("mods"), selected == null ? "" : selected.fileName);
    }

    /**
     * MuoniumConfig 是否已经在 mods/ 里。
     *
     * <p>认「任意版本的 MuoniumConfig」而不只是我们钉的那一个：玩家手动装了别的版本
     * （或换了条发布线）也算已装，此时不该再下一份进去——两个同名 mod 会让 Fabric 报重复 id。</p>
     */
    public static boolean isInstalled() {
        Build selected = build;
        if (selected != null) {
            File jar = targetJar();
            if (jar.isFile() && jar.length() > 0) return true;
        }
        return anyMuoniumConfigInMods();
    }

    /** mods/ 里有没有任何 {@code muoniumconfig-*.jar}。 */
    private static boolean anyMuoniumConfigInMods() {
        File[] files = new File("mods").listFiles();
        if (files == null) return false;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("muoniumconfig") && name.endsWith(".jar")
                    && file.length() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 没装时起一次后台下载。
     *
     * <p>只判断一次就走，绝不在调用线程（通常是渲染线程）上等网络。</p>
     *
     * @param mcVersion 当前游戏的 MC 版本，例如 {@code "26.3"}
     * @param loaderId  加载器 id，例如 {@code "fabric"}
     * @return 是否真的起了下载
     */
    public static boolean ensureDownloaded(String mcVersion, String loaderId) {
        if (!enabled()) return false;
        if (build == null) {
            bucketKey = buildKey(mcVersion, loaderId);
            build = bucketKey.isEmpty() ? null : BUILDS.get(bucketKey);
            if (build == null) {
                // 这个 MC 版本/加载器还没有对应构建：不下载，也不刷日志。
                return false;
            }
            if (!build.isPinned()) return false;
        }
        if (isInstalled()) return false;
        synchronized (LOCK) {
            if (inFlight || attempts >= MAX_ATTEMPTS) {
                return false;
            }
            long now = System.nanoTime();
            if (now < nextAttemptAtNanos) {
                return false;
            }
            attempts++;
            inFlight = true;
            nextAttemptAtNanos = now + 30_000_000_000L;
        }
        Thread worker = new Thread(MuoniumConfigDownload::run, "MuoniumPlayer-MuoniumConfig");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * 拼「MC 版本 + 加载器」的分桶键，例如 {@code "26.3/fabric"}。
     *
     * <p>加载器 id 统一小写。NeoForge 有时上报 {@code forge}、有时 {@code neoforge}，
     * 但两者在 Stonecutter 里是不同的车道，所以不做自动归一 —— 表里写哪个就匹配哪个，
     * 没写就当没有包，宁可少一个设置界面也不装错构建。</p>
     *
     * @return 规范化后的键；参数缺失或加载器未知时返回空串
     */
    private static String buildKey(String mcVersion, String loaderId) {
        if (mcVersion == null || loaderId == null) return "";
        String loader = loaderId.trim().toLowerCase(java.util.Locale.ROOT);
        String mc = mcVersion.trim();
        if (mc.isEmpty() || loader.isEmpty() || "unknown".equals(loader)) return "";
        return mc + "/" + loader;
    }

    /** 当前选中的包名；没选中时为 null。供提示层显示。 */
    public static String fileName() {
        Build selected = build;
        return selected == null ? null : selected.fileName;
    }

    private static void run() {
        Build selected = build;
        if (selected == null) return;
        try {
            File target = targetJar();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("建不了 mods 目录：" + parent);
            }
            state = new State(Phase.DOWNLOADING, 0, null);

            // 镜像链 + 版本子目录 + 文件名，例如
            // https://file.melodifymc.cc.cd/muoniumconfig/26.3/fabric/muoniumconfig-1.6.1.jar
            List<String> urls = new ArrayList<>(MIRRORS.size());
            for (String mirror : MIRRORS) {
                urls.add(mirror + bucketPrefix() + selected.fileName);
            }
            HttpDownloadRace.fetch(urls, selected.bytes, selected.sha256, target,
                    percent -> state = new State(Phase.DOWNLOADING, percent, null));

            state = new State(Phase.DONE, 100, null);
            // 不试热加载：Fabric 只在启动阶段扫 mods/，运行中塞进去的 jar 不会被加载
            restartRequired = true;
        } catch (Throwable failure) {
            state = new State(Phase.FAILED, 0, describe(failure));
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /** 记住解析出来的分桶键（{@code "26.3/fabric"}），供 run() 拼 URL。 */
    private static volatile String bucketKey = "";

    /** {@code "26.3/fabric/"} 这种带结尾斜杠的前缀。 */
    private static String bucketPrefix() {
        String key = bucketKey;
        return key.isEmpty() ? "" : key + "/";
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
