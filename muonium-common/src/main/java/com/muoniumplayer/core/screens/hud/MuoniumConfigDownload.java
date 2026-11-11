package com.muoniumplayer.core.screens.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.update.SemVer;
import com.muoniumplayer.core.update.UpdateChecker;
import com.muoniumplayer.core.utils.network.HttpDownloadRace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MuoniumConfig（HUD 设置界面）的<b>按需安装与自更新</b>。
 *
 * <p>它是 OneConfig 的精简分支，带 mixin 与整套 Compose 界面，塞进主包会让成品多出
 * 9.5 MB。因此改成启动时按需拉取，与 Skija 伴生包同一套做法：多镜像竞速、字节数与
 * sha256 双钉、装好后<b>必须重启</b>才生效（加载器在启动阶段扫描 mods/ 并织入 mixin，
 * 运行中塞进去的 jar 不会被加载，热加载没有可行性）。</p>
 *
 * <p>为什么落在 {@code mods/}：这是玩家手动装 mod 的同一位置，重启后由加载器自己发现。
 * 放 config 目录再由我们手动挂 classloader 也能做到，但那样要自己处理 mod 发现与 mixin
 * 配置注册，出错面大得多。</p>
 *
 * <p>失败不阻挡进游戏：MuoniumConfig 只提供设置界面，下载失败时主体功能（听歌、HUD 绘制）
 * 照常运行，只少了那个界面。这与 Skija 缺原生库不同 —— 那个缺了渲染层整个起不来。</p>
 *
 * <h2>每次启动都查更新，而不是"只在没装时下载"</h2>
 * 以前这里只做「mods/ 里没有就下一次」，于是 MuoniumConfig 的 bug 修复只能等玩家升级
 * 整个播放器 —— 客户端把版本号钉死在 {@link #BUILDS} 里，老客户端永远看不到新版。
 * 现在每次启动都读一次远端清单（{@link MuoniumConfigIndex}），比已装版本新才下载：
 *
 * <ol>
 *   <li>清单优先，{@link #BUILDS} 只是<b>兜底</b>（断网 / 镜像全挂时退回内置值）；</li>
 *   <li>已装版本 &ge; 目标版本就什么都不做（玩家手装的更新版本不会被降级）；</li>
 *   <li>需要更新时：先下新包并校验 → 再尽力让旧包退场 → 最后置"待重启"。</li>
 * </ol>
 *
 * <h2>旧包退场：能删就删，删不掉就明确告诉用户</h2>
 * 两个同 id 的 jar 同时躺在 mods/ 里，<b>下一次启动</b>会被加载器当成重复 mod 直接拒绝
 * 启动 —— 这是这个功能里唯一真正危险的状态，所以处理方式必须写清楚：
 *
 * <p><b>实测结论（2026-11-09，Windows 11 + JDK 21，26.3 NeoForge 真实 runClient）：</b>
 * 游戏运行期间，加载器对 mod jar 持有<b>独占锁</b>，{@link Files#delete} 与
 * {@link Files#move} 都是 {@code WinError 32}（另一个程序正在使用此文件）。进程退出后
 * 锁立刻释放。早先一次独立小实验里 {@code ZipFileSystem} 能删同名文件，但那不能推广到
 * 真实加载器 —— 以真实 runClient 的结论为准。</p>
 *
 * <p>因此三级处理：</p>
 * <ol>
 *   <li>{@link Files#delete} 直接删掉（有些环境/平台放得开，这是最好结果）；</li>
 *   <li>删不掉就改名成 {@code <名>.jar.disabled} —— 扩展名不再是 {@code .jar}，
 *       加载器不会扫到它，效果等同删除；</li>
 *   <li>两者都失败（运行期就是这种情况）：<b>保留新包</b>，把要删的文件名记进待处理清单，
 *       并在提示里<b>点名文件 + 说清后果</b>（不删下次启动会因重复 mod 失败）。</li>
 * </ol>
 *
 * <h2>为什么第 3 档选择"保留新包"而不是回滚</h2>
 * 新包落进 mods/ 对<b>当前这一局</b>没有任何影响（加载器只在启动阶段扫 mods/）。
 * 而回滚掉新包会让自动更新在被锁的环境里<b>永远失败</b>——运行期根本删不掉旧包。
 * 保留新包 + 提示手动删旧包，用户动一次手就完成了升级，这条路是能走通的。
 *
 * <p>代价是必须在提示里把后果讲清楚，并且这时<b>不能自动重启</b>：带着两个同 id 的 jar
 * 重启，正好会撞上加载失败。</p>
 *
 * <p>下一次启动时 {@link #retryPendingRemoval()} 会再试一次删旧包（那时锁已随进程退出
 * 释放，通常一次就成），但提示依然以"请手动删"为准 —— 自动重试是惊喜，不能当承诺。</p>
 */
public final class MuoniumConfigDownload {
    private static final String TAG = "MuoniumConfigDownload";

    /**
     * 下载镜像链的公共前缀。
     *
     * <p>目录下按「MC 版本 / 加载器」再分子目录，例如
     * {@code muoniumconfig/26.3/fabric/muoniumconfig-1.8.0.jar}。同一个前缀下还有
     * 该车道的版本清单 {@code index.json}。</p>
     *
     * <p>测试/自建镜像可以用 {@code -Dmuonium.muoniumconfig.mirror=<前缀>} 整个换掉这条链：
     * 更新流程的删除与回滚分支在真实平台上很难复现（要恰好让旧包被锁住），
     * 有一条能指向本地目录的开关才有可能在交付前跑通整条路径。</p>
     */
    private static List<String> mirrors() {
        String override = System.getProperty("muonium.muoniumconfig.mirror");
        if (override != null && !override.isBlank()) {
            String prefix = override.trim();
            return List.of(prefix.endsWith("/") ? prefix : prefix + "/");
        }
        return MIRRORS;
    }

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
     * <b>兜底</b>表：每个「MC 版本 + 加载器」在内置版本里对应哪个包。
     *
     * <p>它曾经是唯一的版本来源，现在退居二线：正常路径读远端 {@code index.json}
     * （见 {@link MuoniumConfigIndex}），只有清单拉不到时才用这里的值。保留它的意义是
     * "断网也能装出可用环境"——一个刚装好、还没联网的客户端至少能拿到一个能用的设置界面。</p>
     *
     * <p>为什么必须分桶：MuoniumConfig 是用 Stonecutter 为每个 MC 版本单独编译的 ——
     * mixin 目标、映射名、Compose 版本全都不同。给 1.21.1 塞一个 26.3 编译出来的 jar，
     * 结果是启动期 mixin 找不到目标方法而崩溃，那比「没有设置界面」严重得多。</p>
     *
     * <p>表里没有的组合就<b>不下载</b>：宁可少一个设置界面，也不能装一个必然崩的包。
     * 玩家仍可自行把对应版本的 jar 放进 mods/（{@link #isInstalled()} 会认它）。</p>
     *
     * <p>本表由 MuoniumConfig 仓库的 {@code tools/publish.py} 生成，别手改。</p>
     */
    private static final Map<String, Build> BUILDS = Map.ofEntries(
            Map.entry("26.3/fabric", new Build("muoniumconfig-1.8.0.jar",
                    26127283L, "482dee8aef0886f42c3e0a7781971cd0e034942dbdb42ad973f14b661a74dd66")),
            Map.entry("26.3/neoforge", new Build("muoniumconfig-1.8.0.jar",
                    28384897L, "6511f0c4b6793af65fbb577d3bae57c4c1651fca37cae0e9ba07b2709aca14d8")),
            Map.entry("26.2/fabric", new Build("muoniumconfig-1.8.0.jar",
                    26117103L, "f6132725742e86904e401f7bbcc4fcf8f26f805380a5e5bf4cf91029bdd9e8bc")),
            Map.entry("26.2/neoforge", new Build("muoniumconfig-1.8.0.jar",
                    28374841L, "438bc477ea79919fe924c28e15a3ed7d1f186c132dcef0fb52b825c780ed10fe")),
            Map.entry("26.1.2/fabric", new Build("muoniumconfig-1.8.0.jar",
                    26122758L, "063ea267f9eba39a99f9499741e15a7b2fb671e8577d49a91fb28fb85e88ba2b")),
            Map.entry("26.1.2/neoforge", new Build("muoniumconfig-1.8.0.jar",
                    28380521L, "4cc2d428be9fa56a9ed1fac72abab70c2043d5d58e8e79642c0fe0c9fbe63830")),
            Map.entry("1.21.11/fabric", new Build("muoniumconfig-1.8.0.jar",
                    26223310L, "0a37fb509bb679cee2f10ac5a190fcff4cbd69ab6c6197b5442d63ad8efa7ccd")),
            Map.entry("1.21.11/neoforge", new Build("muoniumconfig-1.8.0.jar",
                    28375664L, "a390c7b5d086b10a863b7705437ebaff84848f583e9520fe82078bf304f3e2e9")),
            Map.entry("1.21.10/fabric", new Build("muoniumconfig-1.8.0.jar",
                    26091928L, "c71c425a4cef6d6c10c6d20a91f6cbbf316a823b83dac3c84c66d390a536ceb5")),
            Map.entry("1.21.10/neoforge", new Build("muoniumconfig-1.8.0.jar",
                    28246642L, "8cf2a723e5fea2fafb60effff4ccb6ce6ee322184c39ea2d604f5acfeaf589e6")),
            Map.entry("1.21.1/fabric", new Build("muoniumconfig-1.8.0.jar",
                    26094211L, "f76899367739c84027a17abfaa01913554b6b3e22316c59bfbc30e922ac3ce50")),
            Map.entry("1.21.1/neoforge", new Build("muoniumconfig-1.8.0.jar",
                    27987682L, "9bec3dba3f9f430904ecb3ab2ef37b94c34cf393110f8045a078f756cc46b5b1")),
            Map.entry("1.20.1/fabric", new Build("muoniumconfig-1.8.0.jar",
                    25911194L, "9899a117b0a15fd5423a9716aff80893b011a9ba2582b0a0251666b687abd0df")),
            Map.entry("1.20.1/forge", new Build("muoniumconfig-1.8.2.jar",
                    28308795L, "858b2029c97b27a487f20db1b3e535a6d70f4c130e856f6a39d2f476dcff024c"))
    );

    /** 内置兜底构建：文件名 + 钉死的字节数与 sha256。 */
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

    /** 开关：{@code -Dmuonium.muoniumconfig.autoDownload=false} 可关（开发期跑测试用）。 */
    private static final String PROP_ENABLED = "muonium.muoniumconfig.autoDownload";

    /** 被让位的旧包的扩展名。不再以 {@code .jar} 结尾的任何加载器都不会扫到。 */
    private static final String DISABLED_SUFFIX = ".jar.disabled";

    /** 待人工处理的旧包清单：自动删不掉时记在这里，下次启动重试一次。 */
    private static final String PENDING_FILE = "muoniumconfig_pending.json";

    private static final Object LOCK = new Object();
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final int MAX_ATTEMPTS = 3;

    private static volatile State state = new State(Phase.IDLE, 0, null);
    private static volatile boolean inFlight;
    /** 本会话是否已经跑过一次（含"查出无需更新"）。每次启动只做一轮，避免两处调用点各拉一次。 */
    private static volatile boolean started;
    private static int attempts;
    private static long nextAttemptAtNanos;

    /** 本次会话选中的目标包；null 表示这个 MC 版本/加载器没有可用的包。 */
    private static volatile MuoniumConfigIndex.Entry target;

    /** 本会话解析出来的车道键（{@code "26.3/fabric"}），会话内重试要用。 */
    private static volatile String lane = "";

    /** 下载装好后需要重启才生效。 */
    private static volatile boolean restartRequired;

    /** 已经探明的已装版本（避免重复开 jar）。 */
    private static volatile List<MuoniumConfigJarProbe.Info> installedCache;

    private MuoniumConfigDownload() {
    }

    /** 下载/更新状态；提示层读它决定说什么。 */
    public enum Phase {
        /** 没触发过，或不需要（已是最新）。 */
        IDLE,
        /** 正在读远端清单。 */
        CHECKING,
        DOWNLOADING,
        /** 装好了，等重启生效。 */
        DONE,
        /** 这一轮没成（会说明是下载失败还是旧版删不掉）。 */
        FAILED
    }

    /** 状态快照。{@code percent} 只对 DOWNLOADING 有意义，{@code detail} 只对 FAILED 有意义。 */
    public static final class State {
        private final Phase phase;
        private final int percent;
        private final String detail;
        /** 更新前的版本（首次安装为空串）。 */
        private final String fromVersion;
        /** 更新后的版本。 */
        private final String toVersion;
        /** 自动删不掉、需要玩家手动删的旧包文件名。 */
        private final List<String> manualRemoval;

        State(Phase phase, int percent, String detail) {
            this(phase, percent, detail, "", "", List.of());
        }

        State(Phase phase, int percent, String detail,
              String fromVersion, String toVersion, List<String> manualRemoval) {
            this.phase = phase;
            this.percent = percent;
            this.detail = detail;
            this.fromVersion = fromVersion == null ? "" : fromVersion;
            this.toVersion = toVersion == null ? "" : toVersion;
            this.manualRemoval = manualRemoval == null ? List.of() : List.copyOf(manualRemoval);
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

        public String fromVersion() {
            return fromVersion;
        }

        public String toVersion() {
            return toVersion;
        }

        /** 需要玩家手动删除的旧包文件名；空列表表示没有。 */
        public List<String> manualRemoval() {
            return manualRemoval;
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

    /** 目标落点：游戏目录的 mods/ 下的文件名。 */
    private static File targetJar() {
        MuoniumConfigIndex.Entry entry = target;
        return new File(new File("mods"), entry == null ? "" : entry.fileName);
    }

    /**
     * MuoniumConfig 是否已经在 mods/ 里（任意版本都算）。
     *
     * <p>「任意版本」是刻意的：玩家手工装了别的版本、或换了条发布线，也算已装。
     * 要不要<b>替换</b>它是 {@link #ensureDownloaded} 的事，与这里"装没装"是两回事。</p>
     */
    public static boolean isInstalled() {
        return !installed().isEmpty();
    }

    /** 已装的那些 MuoniumConfig（带版本），按文件名排序。 */
    private static List<MuoniumConfigJarProbe.Info> installed() {
        List<MuoniumConfigJarProbe.Info> cached = installedCache;
        if (cached != null) {
            return cached;
        }
        List<MuoniumConfigJarProbe.Info> found = MuoniumConfigJarProbe.scan(new File("mods"));
        installedCache = found;
        return found;
    }

    /** 强制重新扫描（下载装好之后要重算，缓存会过期）。 */
    private static void invalidateInstalled() {
        installedCache = null;
    }

    /**
     * 每次启动游戏都查一遍：没装就装，装了但不是最新就更新。
     *
     * <p>调用点是七树共用的 {@code UpdateChecker.beginCheck} 与各加载器入口 —— 每个客户端
     * init 都会走，所以「每次启动都查」由它们天然保证。判重靠 {@link #started}：一轮会话
     * 只会真正跑一次。只判断就走，绝不在调用线程（通常是主线程）上等网络。</p>
     *
     * @param mcVersion 当前游戏的 MC 版本，例如 {@code "26.3"}
     * @param loaderId  加载器 id，例如 {@code "fabric"}
     * @return 是否真的起了后台线程
     */
    public static boolean ensureDownloaded(String mcVersion, String loaderId) {
        if (!enabled()) return false;
        String key = buildKey(mcVersion, loaderId);
        if (key.isEmpty()) return false;
        lane = key;

        // 这是「每次启动都查」的唯一闸：两处调用点（beginCheck 与加载器入口）都会进来，
        // 只有第一个能通过。失败后的会话内重试走 retry()，不走这里。
        synchronized (LOCK) {
            if (started || inFlight) {
                return false;
            }
            started = true;
        }
        return startWorker(key);
    }

    /**
     * 会话内重试：失败后用同一套 30 秒冷却 + 3 次上限再试一轮，不用重启游戏。
     *
     * <p>与 {@link #ensureDownloaded} 的区别只有"是否受 {@code started} 闸限制"，
     * 「每次启动只查一次」的纪律仍然成立（{@code started} 不再被重置）。
     * 由宿主每 tick 询问 {@link #shouldRetry()} 决定要不要调它。</p>
     *
     * @return 是否真的起了后台线程
     */
    private static boolean retry() {
        if (!enabled()) return false;
        String key = lane;
        if (key == null || key.isEmpty()) return false;
        return startWorker(key);
    }

    /**
     * 现在是不是该自己再试一轮（宿主每 tick 问一次）。
     *
     * <p>只在「下载类失败」时为真：网络抖一下值得自动重试；而"旧版本删不掉"重试一百次也
     * 还是删不掉（文件被别的进程锁着），那种要靠用户动手，自动重试只会刷日志。</p>
     */
    public static boolean shouldRetry() {
        if (!enabled() || restartRequired || inFlight) {
            return false;
        }
        State current = state;
        if (current.phase() != Phase.FAILED || !current.manualRemoval().isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            return attempts < MAX_ATTEMPTS && System.nanoTime() >= nextAttemptAtNanos;
        }
    }

    /** 供宿主 tick 调用：该重试时起一轮。返回是否真的起了。 */
    public static boolean tick() {
        return shouldRetry() && retry();
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
        String loader = loaderId.trim().toLowerCase(Locale.ROOT);
        String mc = mcVersion.trim();
        if (mc.isEmpty() || loader.isEmpty() || "unknown".equals(loader)) return "";
        return mc + "/" + loader;
    }

    /** 当前选中的包名；没选中时为 null。供提示层显示。 */
    public static String fileName() {
        MuoniumConfigIndex.Entry entry = target;
        return entry == null ? null : entry.fileName;
    }

    /** 目标版本号；不知道时为空串。 */
    public static String targetVersion() {
        MuoniumConfigIndex.Entry entry = target;
        return entry == null ? "" : entry.version;
    }

    private static boolean startWorker(String lane) {
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
        Thread worker = new Thread(() -> run(lane), "MuoniumPlayer-MuoniumConfig");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static void run(String lane) {
        try {
            // ① 先把上一轮删不掉的旧包再试一次：那时它被占用，现在多半已经自由了。
            //    这一步放在探测之前 —— 清掉了就自动恢复正常流程，用户什么都不用做。
            //    清不掉时它会留下一条 FAILED 状态（要用户动手），所以 ② 之后若判定
            //    "无需更新"，不能无条件把状态改回 IDLE，否则那条提示会被吞掉。
            List<String> stuck = retryPendingRemoval();

            // ② 读远端清单；拉不到就用内置兜底表。
            state = new State(Phase.CHECKING, 0, null);
            String loaderVersion = UpdateChecker.currentVersion();
            MuoniumConfigIndex.Entry entry = MuoniumConfigIndex.fetch(mirrors(), lane, loaderVersion);
            if (entry == null) {
                entry = builtinEntry(lane);
                if (entry != null) {
                    log("清单不可用，改用内置兜底：" + entry);
                }
            }
            if (entry == null || !entry.isUsable()) {
                // 这个 MC 版本/加载器还没有对应构建：不下载，也不刷日志。
                state = stuckState(stuck);
                return;
            }
            target = entry;

            // ③ 和已装的比一比。
            List<MuoniumConfigJarProbe.Info> installed = installed();
            MuoniumConfigJarProbe.Info newest = newest(installed);
            if (newest != null && newest.hasVersion()
                    && !SemVer.isNewer(entry.version, newest.version)) {
                // 已装版本不比目标旧：不重复下载、也不降级（玩家手装的更新版本要留住）。
                log("已是最新：" + newest.version + "（目标 " + entry.version + "）");
                state = stuckState(stuck);
                return;
            }

            // ④ 下载新包（落盘即校验：字节数 + sha256 由竞速下载器负责）。
            state = new State(Phase.DOWNLOADING, 0, null);
            File targetFile = targetJar();
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("建不了 mods 目录：" + parent);
            }
            List<String> urls = new ArrayList<>();
            for (String mirror : mirrors()) {
                urls.add(mirror + lane + "/" + entry.fileName);
            }
            HttpDownloadRace.fetch(urls, entry.bytes, entry.sha256, targetFile,
                    percent -> state = new State(Phase.DOWNLOADING, percent, null));

            // ⑤ 再验一次"这个文件真的是我们要的那版"。
            //    字节数与 sha256 已经证明"传输没坏"，这里证明"内容就是 MuoniumConfig 且版本对"
            //    —— 清单本身是网络上来的，万一 lane 目录被别的包覆盖，这一步是最后一道闸。
            MuoniumConfigJarProbe.Info fresh = MuoniumConfigJarProbe.readInfo(targetFile);
            if (fresh == null) {
                // readInfo 内部已校验 modId：拿不到 Info 就是"这不是 MuoniumConfig 的 jar"
                // （或包坏到读不出元数据）。两种情况都不能留在 mods/ 里。
                discard(targetFile);
                throw new IOException("下载到的不是有效的 MuoniumConfig 包");
            }
            if (!fresh.version.isEmpty() && !entry.version.isEmpty()
                    && !fresh.version.equals(entry.version)) {
                discard(targetFile);
                throw new IOException("版本不符：清单说 " + entry.version
                        + "，包内是 " + fresh.version);
            }

            // ⑥ 让旧包退场。删不掉必须回滚 —— 两个同 id 的 jar 会让下一次启动直接失败。
            //
            // 判据是「mods/ 里所有 MuoniumConfig，除了刚下的这个新文件」—— 而不是
            // 「除了版本最高的那个」。后一种写法在「只装了一版、现在要升级」这个最常见的
            // 场景下 stale 为空（那个旧包正是 newest），于是旧包不会被删，新包却已经落盘：
            // 结果是日志报「更新成功」、下一次启动因为重复 mod id 直接失败。
            // 2026-11-09 本地端到端验证时踩到的就是这个。
            String fromVersion = newest == null ? "" : newest.version;
            String targetPath = targetFile.getAbsolutePath();
            List<MuoniumConfigJarProbe.Info> stale = new ArrayList<>();
            for (MuoniumConfigJarProbe.Info info : installed) {
                if (!info.file.getAbsolutePath().equals(targetPath)) {
                    stale.add(info);
                }
            }
            List<String> manual = new ArrayList<>();
            List<String> renamed = new ArrayList<>();
            for (MuoniumConfigJarProbe.Info info : stale) {
                if (remove(info.file, renamed) == Removal.FAILED) {
                    manual.add(info.file.getName());
                }
            }
            if (!manual.isEmpty()) {
                // 旧包删不掉：**保留新包**，把"该删哪个"交给用户。
                //
                // 为什么不回滚（2026-11-09 实测后的决定）：加载器只在启动阶段扫 mods/，
                // 新包落进 mods/ 对<b>当前这一局</b>毫无影响；而回滚会让"自动更新"在这类
                // 环境里永远失败（旧包被运行中的加载器行锁住，运行期删和改名都是
                // WinError 32）。反过来，保留新包 + 明确告知，用户下次启动前删掉旧包就
                // 完成了升级；不删的话两个同 id 的 jar 会让那次启动失败 —— 这个后果必须
                // 说清楚，所以文案里点名文件、点名后果、并说明我们不会自动重启。
                savePending(manual, renamed);
                String list = String.join("、", manual);
                state = new State(Phase.FAILED, 0,
                        "新版已下载但旧版删不掉：请手动删除 mods/ 里的 " + list,
                        fromVersion, entry.version, manual);
                log("旧包删不掉，已保留新包并提示手动删除：" + list);
                // 不置 restartRequired：那会触发自动重启，而带着两个同 id 的 jar 重启
                // 正好会撞上加载失败。等用户删掉旧包自己重启。
                return;
            }
            // 旧包已全部让位；改名残骸留给下次启动清（它们不会被加载，不急）。
            if (!renamed.isEmpty()) {
                savePending(List.of(), renamed);
            }

            // 能走到这里：新包在位、旧包已退场。
            invalidateInstalled();
            state = new State(Phase.DONE, 100, null, fromVersion, entry.version, List.of());
            log((fromVersion.isEmpty() ? "已安装 " : "已从 " + fromVersion + " 更新到 ")
                    + entry.version + "，重启后生效");
            // 不试热加载：加载器只在启动阶段扫 mods/，运行中塞进去的 jar 不会被加载
            restartRequired = true;
        } catch (Throwable failure) {
            state = new State(Phase.FAILED, 0, describe(failure));
            log("失败：" + describe(failure));
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /** 内置兜底表里的这一条，包成与远端条目同一种形状。 */
    private static MuoniumConfigIndex.Entry builtinEntry(String lane) {
        Build pinned = BUILDS.get(lane);
        if (pinned == null || !pinned.isPinned()) {
            return null;
        }
        return MuoniumConfigIndex.builtin(lane, pinned.fileName, pinned.bytes, pinned.sha256);
    }

    /** 已装的一堆里版本最高的那个；都没有版本号时取第一个（至少知道装了）。 */
    private static MuoniumConfigJarProbe.Info newest(List<MuoniumConfigJarProbe.Info> installed) {
        MuoniumConfigJarProbe.Info newest = null;
        for (MuoniumConfigJarProbe.Info info : installed) {
            if (!info.hasVersion()) {
                if (newest == null) {
                    newest = info;
                }
                continue;
            }
            if (newest == null || !newest.hasVersion()
                    || SemVer.isNewer(info.version, newest.version)) {
                newest = info;
            }
        }
        return newest;
    }

    /** 让一个包退场的三级处理结果。 */
    private enum Removal {
        /** 直接删掉了。 */
        DELETED,
        /** 删不掉，但改名成 {@code .jar.disabled} 了：加载器不会再扫到它。 */
        DISABLED,
        /** 删不掉也改不了名：文件被独占锁住，只能交给用户/下次启动。 */
        FAILED
    }

    /**
     * 让一个 jar 从"会被加载器扫到"的位置消失。
     *
     * <p>Windows 上「能删」与「能改名」不一定同时成立：{@code ZipFileSystem} 打开的 jar
     * 两者都行，{@code JarFile} 打开的连改名都失败。所以两条都试，都不成才算失败。</p>
     *
     * @param renamedTo 改名成功时把新名字写进这个列表（下一次启动要清掉这个残骸）
     */
    private static Removal remove(File jar, List<String> renamedTo) {
        try {
            Files.deleteIfExists(jar.toPath());
            return Removal.DELETED;
        } catch (Throwable deleteFailed) {
            // 落到下一级
        }
        File disabled = new File(jar.getAbsolutePath() + DISABLED_SUFFIX);
        try {
            Files.move(jar.toPath(), disabled.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log("旧包删不掉，已改名让它失效：" + disabled.getName());
            if (renamedTo != null) {
                renamedTo.add(disabled.getName());
            }
            return Removal.DISABLED;
        } catch (Throwable moveFailed) {
            return Removal.FAILED;
        }
    }

    /** 丢掉一个我们自己刚写下的文件（回滚/校验失败时用）。删不掉就改名让它失效。 */
    private static void discard(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
            return;
        } catch (Throwable ignored) {
        }
        try {
            Files.move(file.toPath(), new File(file.getAbsolutePath() + DISABLED_SUFFIX).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 待清理清单（跨启动） ====================

    /**
     * 记下"这一轮没能彻底清掉的东西"，下次启动再试。
     *
     * <p>两类分开记，因为下一步动作不同：</p>
     * <ul>
     *   <li>{@code files}：<b>要玩家手动删</b>的旧包（删不掉也改不了名，被独占锁住）。
     *       这是唯一需要告知用户的情况；</li>
     *   <li>{@code renamed}：已改名成 {@code .jar.disabled} 的残骸。它们已经不会被加载，
     *       纯粹是磁盘占用，下次启动悄悄删掉即可，不该打扰用户。</li>
     * </ul>
     */
    private static void savePending(List<String> files, List<String> renamed) {
        try {
            JsonObject root = new JsonObject();
            root.add("files", arrayOf(files));
            root.add("renamed", arrayOf(renamed));
            JsonConfigStorage.writeObject(pendingFile(), GSON, root);
        } catch (Throwable ignored) {
            // 写不了就只在本次会话提示，不影响主流程。
        }
    }

    private static JsonArray arrayOf(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> loadPending(String key) {
        List<String> names = new ArrayList<>();
        try {
            File file = pendingFile();
            if (!file.isFile()) {
                return names;
            }
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has(key) || !root.get(key).isJsonArray()) {
                return names;
            }
            for (JsonElement element : root.getAsJsonArray(key)) {
                if (element != null && !element.isJsonNull()) {
                    String name = element.getAsString();
                    // 只接受裸文件名：这个字段会拼进 mods/ 路径。
                    if (name != null && !name.isBlank() && isBareName(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    /** 裸文件名校验（不含路径分隔符、不含 {@code ..}）。 */
    private static boolean isBareName(String name) {
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains("..");
    }

    private static File pendingFile() {
        return new File(new File("config", "muonium"), PENDING_FILE);
    }

    /**
     * 重试上一轮没能彻底清掉的东西。
     *
     * <p>为什么值得这么做：{@code JarFile} 那种锁是<b>进程级</b>的，上个进程退出后就没了。
     * 上一轮因为删不掉而回滚的更新，这一轮往往第一次尝试就干净删掉了 —— 用户看到的效果是
     * "重启一次自己就好了"，而不是"每次都要我手动删"。</p>
     *
     * @return 仍然删不掉、必须由玩家处理的旧包文件名（空列表表示没有）
     */
    private static List<String> retryPendingRemoval() {
        List<String> pending = loadPending("files");
        List<String> renamed = new ArrayList<>(loadPending("renamed"));
        if (pending.isEmpty() && renamed.isEmpty()) {
            return List.of();
        }
        List<String> stillThere = new ArrayList<>();
        for (String name : pending) {
            File jar = new File(new File("mods"), name);
            if (!jar.isFile()) {
                continue; // 已经不在了（删了/改名了/玩家自己处理了）
            }
            List<String> moved = new ArrayList<>();
            if (remove(jar, moved) == Removal.FAILED) {
                stillThere.add(name);
            } else {
                renamed.addAll(moved);
            }
        }
        // 上一轮改名的残骸：不会被加载，删得掉就删，删不掉继续留着下次试。
        List<String> keep = new ArrayList<>();
        for (String name : renamed) {
            if (!isBareName(name)) {
                continue;
            }
            File disabled = new File(new File("mods"), name);
            if (!disabled.isFile()) {
                continue;
            }
            boolean gone;
            try {
                Files.deleteIfExists(disabled.toPath());
                gone = true;
            } catch (Throwable ignored) {
                gone = false;
            }
            if (!gone) {
                keep.add(name);
            }
        }
        savePending(stillThere, keep);
        if (stillThere.isEmpty() && !pending.isEmpty()) {
            log("上一轮遗留的旧包已清理：" + pending);
        } else if (!stillThere.isEmpty()) {
            log("旧包仍然删不掉：" + stillThere);
        }
        return stillThere;
    }

    /**
     * 「无需更新」时该处于什么状态。
     *
     * <p>不能无条件写成 IDLE：若上一轮有删不掉的旧包，那条"请手动删除"的提示必须留在
     * 界面上 —— 把它改回 IDLE 等于告诉用户"一切正常"，而实际上 mods/ 里躺着一个
     * 加载器会拒绝启动的重复 mod。</p>
     */
    private static State stuckState(List<String> stuck) {
        if (stuck == null || stuck.isEmpty()) {
            return new State(Phase.IDLE, 0, null);
        }
        return new State(Phase.FAILED, 0,
                "旧版本删不掉；请手动删除 mods/ 里的 " + String.join("、", stuck),
                "", "", stuck);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }

    private static void log(String message) {
        System.out.println("[MuoniumPlayer/" + TAG + "] " + message);
    }

    // ==================== 提示层门面（与 Skija 的 SkijaNatives.nativeMissingLines 同一角色） ====================

    /**
     * 提示块此刻是否该常驻：下载中 / 已装好待重启，两段都必须整块挂着——
     * 下载中缩成一行角标会被当成卡死；装好待重启时「必须重启游戏」是这一屏唯一要传达的事。
     *
     * <p>「旧版删不掉」也常驻：那是要玩家动手的事，一闪而过等于没说。其余失败仍只短暂出现
     * （纯外观损失，常驻会变成骚扰），由各树渲染器自己管窗口。</p>
     */
    public static boolean noticeVisible() {
        State current = state();
        if (current.phase() == Phase.DOWNLOADING || current.phase() == Phase.DONE) {
            return true;
        }
        return current.phase() == Phase.FAILED && !current.manualRemoval().isEmpty();
    }

    /** 下载进度（0~100）；DONE 恒为 100（绿条到头 = 「可以重启了」）。 */
    public static int noticePercent() {
        return state().percent();
    }

    /**
     * 提示块的整块文案。分岔按「用户下一步动作」切——下载中什么都别做、装好了等自动重启、
     * 失败了才知道还能怎么办；这与 {@code SkijaNatives.nativeMissingLines} 是同一条纪律。
     *
     * <p>{@link Phase#CHECKING} 的文案存在但 {@link #noticeVisible()} 不会让它常驻：
     * 读一份不到 1 KB 的清单通常几百毫秒就完了，把它画成一闪而过的面板比不画更差。
     * 只有在"检查卡住"（比如网络极慢）时才轮到它 —— 那时下载阶段本来也不会来，
     * 而它至少能解释为什么没动静。</p>
     */
    public static List<String> noticeLines() {
        State current = state();
        List<String> lines = new ArrayList<>();
        switch (current.phase()) {
            case CHECKING -> lines.add("正在检查 MuoniumConfig（HUD 设置界面）更新…");
            case DOWNLOADING -> {
                String version = current.toVersion();
                String what = version.isEmpty()
                        ? "MuoniumConfig（HUD 设置界面）"
                        : "MuoniumConfig " + version;
                lines.add(current.percent() >= 0
                        ? "正在下载 " + what + "：" + current.percent() + "%"
                        : "正在下载 " + what + "…");
            }
            case DONE -> {
                // 版本号可能缺（清单没给 / 内置兜底里文件名解析不出），拼句子时必须容得下，
                // 否则会出现「MuoniumConfig  已自动装好」这种中间空一格的文案。
                String to = current.toVersion();
                String target = to.isEmpty() ? "MuoniumConfig" : "MuoniumConfig " + to;
                if (current.fromVersion().isEmpty()) {
                    lines.add(target + " 已自动装好，需要重启游戏后生效");
                } else {
                    lines.add("MuoniumConfig 已从 " + current.fromVersion()
                            + " 更新到 " + (to.isEmpty() ? "新版本" : to) + "，需要重启游戏后生效");
                }
                lines.add("通常马上会自动重启；等急了可手动退出重进，按 ESC 能取消自动重启");
                String restartLine = com.muoniumplayer.core.update.GameRestart.statusLine();
                if (restartLine != null) {
                    lines.add(restartLine);
                }
            }
            case FAILED -> {
                List<String> manual = current.manualRemoval();
                if (!manual.isEmpty()) {
                    // 这段是这个功能里<b>唯一必须让用户看懂后果</b>的文案：
                    // 新包已经在 mods/ 里了，旧包删不掉；两个同 id 的 jar 会让下一次启动
                    // 直接失败。所以三行缺一不可 —— 出了什么事、该删哪个、删完怎么办。
                    //
                    // 版本号得容得下"不知道"：跨启动重试仍失败时（stuckState）手上没有目录
                    // 版本号（那一轮压根没读清单），此时不能拼出「MuoniumConfig  已下载完成」
                    // 这种中间空一格的句子 —— 宁可只说「新版本」。
                    String list = String.join("、", manual);
                    String version = current.toVersion();
                    String what = version.isEmpty() ? "MuoniumConfig 新版本" : "MuoniumConfig " + version;
                    lines.add(what + " 已下载完成，但旧版本删不掉");
                    lines.add("请手动删除 mods/ 里的：" + list);
                    lines.add("不删的话下次启动会因重复的 mod 而失败；删掉后重启即可生效");
                } else {
                    lines.add("MuoniumConfig 自动更新失败：" + current.detail());
                    lines.add("不影响听歌与 HUD；下次启动游戏会自动重试");
                    String name = fileName();
                    if (name != null) {
                        lines.add("也可以手动安装：把 " + name + " 放进 mods/ 目录");
                    }
                }
            }
            default -> { }
        }
        return lines;
    }
}
