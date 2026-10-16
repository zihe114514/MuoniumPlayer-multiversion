package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.utils.network.HttpDownloadRace;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 汽水签名后端（qs-backend）的「伴生包」自动下载器与托管启动。
 *
 * <h2>为什么需要它</h2>
 * 汽水 {@code /luna/pc/*} 接口的应用级签名（{@code X-Helios}/{@code X-Medusa}）只有
 * 官方客户端自带的 {@code bdms.node} 算得出来，Java 侧复现不了。签名这件事被抽成
 * 本机服务 qs-backend（Node），由它代表模组完成「拼 URL → 签名 → 带 Cookie 转发」。
 * 服务本体（含官方 Node 运行时与桥接器，约 33 MB）以「伴生包」形式托管在公开清单
 * 仓库 {@code MuoniumPlayer-updates} 的 {@code qishui-companion/} 目录：客户端在
 * <b>本机没有可用的后端</b>时自动下载、校验字节数与 sha256、解压到
 * {@code config/muonium/qishui-backend/} 并作为独立进程拉起——与 Skija 原生库
 * 伴生包（{@link com.muoniumplayer.render.skija.SkijaCompanionDownload}）同一套链路，
 * 区别只在生效方式：后端是<b>独立进程</b>，不需要重启游戏，装好即探即用。
 *
 * <h2>与 sidecar 教训的边界</h2>
 * 之前一版汽水 sidecar 因「失败后静默回落」当夜回滚。本类遵守同一条红线：
 * <b>只负责把包装好、把进程拉起来、把结果说清楚</b>；启动失败、健康检查不过、
 * Cookie 缺失都会落到 {@link #state()} 与日志里由提示层呈现，绝不替用户假装成功。
 * 后端进程本身独立于游戏存续，玩家关掉 start.bat 窗口即停。
 *
 * <h2>触发条件（同时满足才下载）</h2>
 * <ol>
 *   <li>{@link QishuiBackend#isAvailable()} 为假（本机 8787 无健康后端）；</li>
 *   <li>安装目录里没有完整的 server.mjs + Node 运行时（有则只尝试直接拉起，不重复下载）；</li>
 *   <li>没有被 {@value #PROP_ENABLED} 显式关掉；</li>
 *   <li>不在下载中，且本会话的尝试预算与冷却时间还允许。</li>
 * </ol>
 *
 * <h2>钉死字节数与 sha256</h2>
 * 下载的是「会以本机权限执行的可执行运行时」，与更新清单同一标准：字节数与 sha256
 * 钉死在 {@link #PACK}，解压后再做内容级核对（server.mjs 存在、Node 运行时体量合理）。
 * 换包时三处必须一起改：清单仓库里的 zip、本类常量、发布产物；
 * 换包后旧版本号留着也行，{@code installMarker} 只记当前版本。
 */
public final class QishuiCompanionDownload {

    /**
     * 下载镜像链，前五路与 Skija 伴生包同一套（ghfast / gh-proxy / raw / ghproxy.net / jsdelivr）。
     * 末一路是 R2 对象存储直链（Cloudflare，公网出口免费，单文件无 25MiB 限制）——
     * 汽水包超过 Pages 的 25MiB 硬限进不了官网 {@code dl/}，R2 是它的官网系兜底。
     */
    static final List<String> MIRRORS = List.of(
            "https://file.melodifymc.cc.cd/qishui-companion/",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "http://muoniummc.cc.cd/dl/qishui-companion/",
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/qishui-companion/",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/qishui-companion/");

    /** 关闭自动下载的开关（默认开，只有显式 {@code =false} 才关）。 */
    static final String PROP_ENABLED = "muonium.qishui.autoDownload";

    /** 开发期替代源：从一个本地目录取包，不经网络。 */
    static final String PROP_LOCAL_DIR = "muonium.qishui.companionDir";

    /** 本会话内最多尝试几次。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 两次尝试之间的冷却。 */
    private static final long RETRY_COOLDOWN_NANOS = 3L * 60L * 1_000_000_000L;
    /** 暂存目录前缀。 */
    private static final String INCOMING_PREFIX = ".incoming-";

    /**
     * 热补时「换不上去」的文件的暂存后缀。
     *
     * <p>Windows 上运行中的 exe 不能覆盖也不能删（文件被映射进进程映像）。热补发生在
     * 旧实例还在跑的当口，遇到这种文件不能直接放弃：先落到 {@code <原名>.new}，
     * 下一次开局（旧实例已退出）由 {@link #maintainInstallRoot()} 换上真名。</p>
     */
    private static final String STAGED_SUFFIX = ".new";

    /** 伴生包：文件名 + 字节数 + sha256，三个都必须精确。 */
    record Pack(String fileName, long bytes, String sha256) {
    }

    /** 1.0.0：Node v22.23.3 运行时 + qs-backend 脚本 + 解密器 + start.bat。
     *  1.0.1：server.mjs 补 Cookie 自动同步三件套（约定路径自动导入、config.json
     *  热重读、/api/cookie/full 反向导出）——修复「登录工具写了 Cookie 但游戏后端
     *  一直 1000016 登录失效」的断链。运行时与 1.0.0 相同。
     *  1.1.0：/api/stream 支持 ?quality=&lt;kbps&gt; 选档（≥目标的最小档，缺省最高），
     *  缓存键按档位区分；电台曲目改走 PC 签名代理优先。
     *  1.2.0：随游戏起停闭环——运行时改名 qishui-backend.exe（任务管理器显示
     *  「qishui后端 for Muo」，原来叫 node.exe 只显示「Node.js JavaScript Runtime」）；
     *  server.mjs 加 --watchdog-stdin / --parent-pid 自杀看门狗，JVM 被强杀也不会
     *  留孤儿；/api/health 增报 version/pid/parent_pid/watchdog，供游戏侧清扫孤儿。
     *
     * <p>清理旧版残留（{@code pack.files.txt} 清单 + 每局清扫）是<b>游戏侧</b>的行为，
     * 所以没有抬版本号：存量安装靠「新名运行时在位时收掉 node.exe」这条保守回落就能干净，
     * 不必为清一次目录让所有人重下一次 33 MB。下一次真的换了包内容时，清单会随热补写下，
     * 此后按清单清扫。</p> */
    static final Pack PACK = new Pack("qishui-backend-windows-x64-1.3.0.zip", 41_719_888L,
            "8931fbe490c4a2c9568518c929044e2c166b5a7d99ea81e12a58111c7ba0c7d6");

    /** 下载状态机；提示层与日志照它说话。 */
    enum Phase {
        IDLE, DOWNLOADING, INSTALLING, STARTING, RUNNING, FAILED
    }

    /** 状态快照：{@code percent} 0~100，-1 表示未知。 */
    record State(Phase phase, int percent, String detail) {
    }

    private static final Object LOCK = new Object();
    private static volatile State state = new State(Phase.IDLE, 0, null);
    private static boolean inFlight;
    private static int attempts;
    private static long nextAttemptAtNanos;

    private QishuiCompanionDownload() {
    }

    public static State state() {
        return state;
    }

    static void markBackendStarting() {
        state = new State(Phase.STARTING, 100, null);
    }

    static void markBackendRunning() {
        state = new State(Phase.RUNNING, 100, null);
    }

    static void markBackendFailed(String detail) {
        state = new State(Phase.FAILED, 0, detail);
    }

    /** 后端状态给来源卡提供稳定、可本地化的呈现语义。 */
    public enum BackendStatus {
        CONNECTING, DOWNLOADING, VERIFYING, INSTALLING, STARTING, RUNNING, STOPPED, FAILED, UNSUPPORTED
    }

    /**
     * 本平台是否支持运行伴生后端。签名后端只发布了 windows-x64 包（bdms.node 是
     * Windows 客户端里的原生模块），其它平台下载了也跑不起来——所以安装/热补/拉起
     * 三个入口都要先过这道闸，别让手机端与 Linux/macOS 白耗 33 MB 流量。
     * 照 {@code FfmpegNotice.isWindows()} 同款判法；安卓的 os.name 是 Linux，
     * 不会误判成支持。
     */
    public static boolean supportedPlatform() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** 给音源选择卡的简短状态标签。 */
    public static String backendStatusLabel() {
        if (!supportedPlatform()) return "不支持";
        State snapshot = state;
        return switch (backendStatus()) {
            case CONNECTING -> "连接中";
            case DOWNLOADING -> "下载 " + Math.max(0, snapshot.percent()) + "%";
            case VERIFYING -> "校验中";
            case INSTALLING -> "安装中";
            case STARTING -> "启动中";
            case RUNNING -> "已运行";
            case STOPPED -> "未运行";
            case FAILED -> "启动失败";
            case UNSUPPORTED -> "不支持";
        };
    }

    /** 状态来源合并了健康探测快照与安装状态；确认健康优先于过期的安装失败记录。 */
    public static BackendStatus backendStatus() {
        if (!supportedPlatform()) return BackendStatus.UNSUPPORTED;
        if (QishuiBackend.peekAvailable()) return BackendStatus.RUNNING;
        State snapshot = state;
        return switch (snapshot.phase()) {
            case DOWNLOADING -> snapshot.percent() >= 100 ? BackendStatus.VERIFYING : BackendStatus.DOWNLOADING;
            case INSTALLING -> BackendStatus.INSTALLING;
            case STARTING -> BackendStatus.STARTING;
            case RUNNING -> BackendStatus.STOPPED;
            case FAILED -> BackendStatus.FAILED;
            case IDLE -> QishuiBackend.hasProbed() ? BackendStatus.STOPPED : BackendStatus.CONNECTING;
        };
    }

    /** 给音源选择卡的详细后端状态说明。 */
    public static String backendInstallStatusLine() {
        State snapshot = state;
        return switch (backendStatus()) {
            case CONNECTING -> "正在连接签名后端…未就绪时仅试听";
            case DOWNLOADING -> "正在下载签名后端 " + Math.max(0, snapshot.percent()) + "%…";
            case VERIFYING -> "下载已接收，正在校验…";
            case INSTALLING -> "下载校验通过，正在安装…";
            case STARTING -> snapshot.phase() == Phase.RUNNING
                    ? "签名后端正在连接…" : "安装完成，正在启动…";
            case RUNNING -> "签名后端运行中 · 完整版可用";
            case STOPPED -> "签名后端未运行 · 仅试听；重新进入游戏或重选本源可自动安装";
            case FAILED -> "后端启动失败" + (snapshot.detail() == null || snapshot.detail().isEmpty() ? "" : "（" + snapshot.detail() + "）") + " · 自动重试中，期间仅试听";
            case UNSUPPORTED -> "完整版仅支持 Windows · 签名器是 Windows 客户端原生组件，本平台不安装后端，可搜索与试听";
        };
    }

    static boolean enabled() {
        String value = System.getProperty(PROP_ENABLED);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /** 伴生包安装根：{@code config/muonium/qishui-backend/}，与 zip 内布局对齐。 */
    static File installRoot() {
        return new File(new File("config", "muonium"), "qishui-backend");
    }

    /**
     * 包内 Node 运行时的文件名。
     *
     * <p>1.2.0 起运行时改名为 {@code qishui-backend.exe}：任务管理器「名称」列读 exe 的
     * 版本资源 FileDescription，原名 {@code node.exe} 在那里显示成「Node.js JavaScript
     * Runtime」，玩家分不清这是游戏的后端还是别的 Node 程序。旧包（≤1.1.0）里的
     * {@code node.exe} 仍要认——热补是后台进行的，升级窗口内旧安装还在跑。</p>
     */
    static String runtimeFileName() {
        return "qishui-backend.exe";
    }

    /**
     * 定位安装目录里现成的运行时，优先新名、回落旧名；都没有返回 {@code null}。
     *
     * <p>不能只认新名：1.1.0 及更早的安装目录里只有 {@code node.exe}，而热补要等
     * 下一次拉起才换新脚本——这个窗口里若只找新名，用户会被判成「没装」，
     * 触发一次 33 MB 的重复下载。</p>
     */
    static File findRuntime(File root) {
        for (String name : new String[]{runtimeFileName(), "node.exe"}) {
            File candidate = new File(root, name);
            if (candidate.isFile() && candidate.length() > 50_000_000L) {
                return candidate;
            }
        }
        return null;
    }

    /** 安装目录是否已有完整的后端文件（脚本 + 运行时，新旧运行时名都认）。 */
    static boolean isInstalled() {
        File root = installRoot();
        File server = new File(root, "server.mjs");
        return server.isFile() && server.length() > 10_000L && findRuntime(root) != null;
    }

    private static File versionMarker() {
        return new File(installRoot(), "pack.version");
    }

    /** 当前安装「应有的文件」清单名：装包时从包内容生成，清扫照它删旧版残留。 */
    private static final String MANIFEST_NAME = "pack.files.txt";

    /**
     * 安装根里属于「用户数据 / 运行期状态」的名字，清扫时原样保留。
     *
     * <p>配置里有设备身份与账号 Cookie，缓存里是已解密的音频——它们都不来自伴生包，
     * 任何清理动作都不能碰。清单文件与版本标记由本类生成，同样不算包内文件。</p>
     */
    private static final Set<String> PRESERVED_NAMES = Set.of(
            "config.json", "config.json.tmp", "pack.version", MANIFEST_NAME,
            "qishui-cookie.txt", "backend.log");

    /** 整体保留的目录前缀（已解密音频缓存）。 */
    private static final Set<String> PRESERVED_PREFIXES = Set.of("cache/");

    private static boolean preservedPath(String relative) {
        if (PRESERVED_NAMES.contains(relative)) {
            return true;
        }
        for (String prefix : PRESERVED_PREFIXES) {
            if (relative.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 读安装清单；没有 / 读不出 / 为空返回空集（调用方退回到「已知旧名单」的保守清扫）。 */
    static Set<String> readManifest(File root) {
        try {
            Set<String> entries = new TreeSet<>();
            for (String line : Files.readAllLines(new File(root, MANIFEST_NAME).toPath(),
                    StandardCharsets.UTF_8)) {
                String trimmed = line.trim().replace('\\', '/');
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
            return entries;
        } catch (IOException missing) {
            return Set.of();
        }
    }

    /**
     * 把包内容写成安装清单（相对安装根、'/' 分隔、按路径排序）。
     *
     * <p>写失败只记日志：没有清单时清扫退回保守名单，顶多留一点旧文件，不影响后端可用。</p>
     */
    private static void writeManifest(File packRoot, File root) {
        try {
            List<String> entries = new ArrayList<>();
            collectRelativePaths(packRoot, packRoot, entries);
            Collections.sort(entries);
            Files.write(new File(root, MANIFEST_NAME).toPath(),
                    String.join("\n", entries).getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            log("安装清单写入失败（下次启动退回到保守清扫）：" + describe(failure));
        }
    }

    private static void collectRelativePaths(File root, File current, List<String> out) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectRelativePaths(root, child, out);
            } else if (child.isFile()) {
                out.add(root.toPath().relativize(child.toPath()).toString().replace('\\', '/'));
            }
        }
    }

    /**
     * 按清单清扫安装目录：不属于当前伴生包的文件一律收掉。返回收掉的文件数。
     *
     * <p>这是「新旧混装」的根治手段。安装与热补都只做合并式覆盖，新版本删掉/改名的文件
     * （比如 1.2.0 改名前的 {@code node.exe}）会永远留在目录里，占着磁盘、也让人分不清
     * 哪个是当前版本。清单是装包时从 zip 内容现生成的，所以「包里有什么」永远说真话。</p>
     *
     * <p>三条纪律：① {@link #PRESERVED_NAMES} 与 {@code cache/} 绝不碰（设备身份、Cookie、
     * 已解密音频）；② 待启用的 {@code *.new} 暂存文件保留（它们不在清单里，但删了等于
     * 把热补成果扔了）；③ 本次删不掉的文件只记日志、绝不报错——运行中的后端占着自己的
     * exe，Windows 上删不掉是常态，下一次启动接着收。</p>
     *
     * <p>没有清单的老安装（≤1.2.0 的包不写清单）退回到「已知旧名单」：只收改名前那个
     * {@code node.exe}，而且<b>必须新名运行时已在位</b>才敢删——1.1.0 的安装里它就是唯一
     * 的运行时，清单还没写下来时删掉等于把用户装好的后端弄坏。</p>
     */
    static int sweepStaleFiles(File root) {
        if (!root.isDirectory()) {
            return 0;
        }
        int removed = 0;
        Set<String> expected = readManifest(root);
        if (expected.isEmpty()) {
            // 只认「体量像运行时」的 node.exe：目录是被托管的，但也不该因为同名就删掉
            // 用户自己放的小文件（findRuntime 用同一条体量门槛认它）。
            File legacyRuntime = new File(root, "node.exe");
            File replacement = new File(root, runtimeFileName());
            if (legacyRuntime.isFile() && legacyRuntime.length() > 50_000_000L && replacement.isFile()) {
                if (deleteTolerant(legacyRuntime, "旧运行时 node.exe")) {
                    removed++;
                }
            }
            return removed;
        }
        List<File> files = new ArrayList<>();
        List<File> directories = new ArrayList<>();
        collectAll(root, files, directories);
        for (File file : files) {
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            if (expected.contains(relative) || preservedPath(relative)
                    || isPendingPromotion(relative, expected)) {
                continue;
            }
            if (deleteTolerant(file, relative)) {
                removed++;
            }
        }
        // 顺手收掉清空后的空目录（比如整目录被新版删掉的情形）。
        for (int index = directories.size() - 1; index >= 0; index--) {
            File directory = directories.get(index);
            String relative = root.toPath().relativize(directory.toPath()).toString().replace('\\', '/');
            if (preservedPath(relative + "/")) {
                continue; // cache/ 这类保留目录：空着也要留着（用户数据目录，不是包内容）
            }
            File[] remaining = directory.listFiles();
            if (remaining != null && remaining.length == 0) {
                try {
                    Files.deleteIfExists(directory.toPath());
                } catch (IOException ignored) {
                    // 删不掉就留着，空目录不碍事。
                }
            }
        }
        if (removed > 0) {
            log("已收掉 " + removed + " 个不属于当前伴生包的旧文件（清单：" + MANIFEST_NAME + "）");
        }
        return removed;
    }

    /** 生产入口：按清单清安装根（只碰安装根本身，不碰它的上级）。 */
    static void sweepStaleFiles() {
        sweepStaleFiles(installRoot());
    }

    /** 是不是「等旧进程退出后启用」的暂存文件，且它的正品仍在当前清单里。 */
    private static boolean isPendingPromotion(String relative, Set<String> expected) {
        if (!relative.endsWith(STAGED_SUFFIX)) {
            return false;
        }
        String base = relative.substring(0, relative.length() - STAGED_SUFFIX.length());
        return expected.contains(base);
    }

    private static void collectAll(File current, List<File> files, List<File> directories) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                directories.add(child);
                collectAll(child, files, directories);
            } else if (child.isFile()) {
                files.add(child);
            }
        }
    }

    /** 删一个文件，成功记日志返回 true；被占用/没权限则静默返回 false（下次启动再试）。 */
    private static boolean deleteTolerant(File file, String label) {
        try {
            Files.deleteIfExists(file.toPath());
            log("清理旧版残留：" + label);
            return true;
        } catch (IOException inUse) {
            // 运行中的旧实例占着它（exe 被映射进进程映像）：这一局删不掉是正常的。
            return false;
        }
    }

    /** 安装目录是否已是当前 {@link #PACK} 版本；缺标记的旧安装视为待升级。 */
    static boolean isUpToDate() {
        if (!isInstalled()) return false;
        try {
            return PACK.fileName().equals(new String(Files.readAllBytes(versionMarker().toPath()),
                    StandardCharsets.UTF_8).trim());
        } catch (IOException missing) {
            return false;
        }
    }

    private static void writeVersionMarker() {
        try {
            Files.write(versionMarker().toPath(), PACK.fileName().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 标记写失败顶多让下次启动多做一次热补，不致命。
        }
    }

    /**
     * 后台热补：已安装但 pack.version 落后于当前 {@link #PACK} 时，拉新包把脚本层
     * 换上去（1.0.1 的 Cookie 同步修复就是这么送达存量安装的）。
     *
     * <p>与首次安装的三点不同：<b>绝不阻塞启动</b>（旧脚本当场就能用，新脚本下次
     * 拉起生效）、<b>失败不弹提示</b>（下次启动重试）、运行时文件被运行中的后端占用时
     * 只跳过该文件（脚本层才是热补目标；被占用的旧运行时由 {@link #sweepStaleFiles()}
     * 在后续启动里收尾——那时旧进程已退出，文件不再被映射）。</p>
     *
     * <p>热补结束后立即按新包清单清扫一次：新包里没有的旧文件（改名前后的运行时、
     * 上游删掉的脚本）当场收掉，不留给下一次启动——但被占用的那份要等下一条命。</p>
     */
    static void refreshIfNeeded() {
        if (!supportedPlatform() || !isInstalled() || isUpToDate()) return;
        synchronized (LOCK) {
            if (inFlight) return;
            inFlight = true;
        }
        Thread worker = new Thread(() -> {
            try {
                File root = installRoot();
                log("检测到后端伴生包待升级 → " + PACK.fileName());
                File archive = new File(root.getParentFile(), PACK.fileName() + ".part");
                fetch(PACK, archive);
                deleteStaleIncoming(root.getParentFile());
                File incoming = new File(root.getParentFile(), INCOMING_PREFIX + Long.toHexString(System.nanoTime()));
                unzip(archive, incoming);
                verifyExtracted(incoming);
                File packRoot = new File(incoming, "qishui-backend");
                copyTreeTolerant(packRoot, root);
                // 清单必须在删暂存之前写：它记录的正是刚解压出来的那份内容，
                // 而 incoming 删掉后就无从生成了（清单为空会让清扫退化成什么都不清）。
                writeManifest(packRoot, root);
                deleteRecursively(incoming);
                Files.deleteIfExists(archive.toPath());
                if (!new File(root, "server.mjs").isFile()) {
                    throw new IOException("热补后 server.mjs 缺失：" + root.getAbsolutePath());
                }
                // 再按清单清扫：新文件已落地、清单已就位，此刻删掉的必是上一版的残留。
                sweepStaleFiles();
                writeVersionMarker();
                log("后端伴生包已热补到 " + PACK.fileName() + "（正在运行的旧进程下次拉起换新）");
            } catch (Throwable failure) {
                log("后端伴生包后台升级失败（下次启动重试）：" + describe(failure));
            } finally {
                synchronized (LOCK) {
                    inFlight = false;
                }
            }
        }, "MuoniumPlayer-QishuiBackendRefresh");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 容错复制：单个文件被占用（比如正在运行的后端运行时）时不中断整树，改存为
     * {@code <原名>.new} 留给下一次开局换上。
     *
     * <p>只跳过不落盘是不行的：热补时被占用的恰恰是运行时本体，跳过等于新版本永远
     * 换不上（旧运行时映射在运行进程里，Windows 不让覆盖）。落 {@code .new} 后，
     * 进程一退，{@link #promoteStagedFiles()} 把它换成正品。</p>
     */
    private static void copyTreeTolerant(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                Files.createDirectories(target.toPath());
                copyTreeTolerant(child, target);
            } else {
                try {
                    Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException busy) {
                    File staged = new File(target.getParentFile(), target.getName() + STAGED_SUFFIX);
                    // 先写 .new.tmp 再原子改名成 .new：游戏在热补中途被杀时，半个文件的
                    // .new 会被下一次开局当成「热补成果」启用，把后端弄成起不来。
                    // .new 只有完整才算数；残留的 .new.tmp 由清扫当普通旧文件收掉。
                    File scratch = new File(target.getParentFile(), target.getName() + STAGED_SUFFIX + ".tmp");
                    Files.copy(child.toPath(), scratch.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.move(scratch.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log("热补暂存被占用的文件：" + target.getName() + " → " + staged.getName()
                            + "（旧进程退出后换新）");
                }
            }
        }
    }

    /**
     * 把热补时暂存的 {@code *.new} 换成正品；换不掉的（仍被占用）留到下一局。
     * 返回换成功几个。
     *
     * <p>只对「暂存文件在、正品在清单里」的成对文件动手——清单外的 {@code .new} 是垃圾，
     * 交给 {@link #sweepStaleFiles(File)} 收掉，不要在这里冒充正品。正品缺失（上一次换到
     * 一半崩了）时，暂存文件顶上——它至少是当前包里的那份内容。</p>
     *
     * <p>递归整棵树：包里有子目录，被占用的文件未必就在根下（虽然眼下只有 exe 会被锁，
     * 但没理由把规则写死在「只认根目录」上）。</p>
     */
    static int promoteStagedFiles(File root) {
        Set<String> expected = readManifest(root);
        List<File> staged = new ArrayList<>();
        collectStaged(root, staged);
        int promoted = 0;
        for (File file : staged) {
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            String base = relative.substring(0, relative.length() - STAGED_SUFFIX.length());
            if (!expected.isEmpty() && !expected.contains(base)) {
                continue; // 清单里的当前包没有这个文件：它是旧版残留的暂存，交给清扫。
            }
            File target = new File(root, base.replace('/', File.separatorChar));
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log("已把热补暂存文件启用为正品：" + base);
                promoted++;
            } catch (IOException inUse) {
                // 正品还被运行中的旧实例占着——留着 .new，下一局再换。
            }
        }
        return promoted;
    }

    private static void collectStaged(File current, List<File> out) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectStaged(child, out);
            } else if (child.isFile() && child.getName().endsWith(STAGED_SUFFIX)) {
                out.add(child);
            }
        }
    }

    /**
     * 每局开一次安装目录保养：{@link #promoteStagedFiles(File)} → {@link #sweepStaleFiles()}。
     *
     * <p>为什么必须是独立于热补的动作、每局都跑：① 热补时被占用的文件（运行中的
     * 运行时）只能等旧进程退出后才能换/删，那时版本标记已写成新版，热补不会再跑；
     * ② 清单清扫同样会撞上被占用文件，需要一次「下一条命再试」的机会。</p>
     *
     * <p>正在装包（首次下载或热补）时整个跳过：那两个流程在写新文件、换新清单，
     * 此刻清扫读到的还是旧清单，会把刚到的新文件当成「不在清单里」删掉——删完没人补，
     * 安装就缺件了。装包流程自己会在落盘后清扫，不需要这里插手。</p>
     */
    static void maintainInstallRoot() {
        if (!isInstalled()) return;
        synchronized (LOCK) {
            // 与装包互斥：装包流程正在写新文件、换新清单，此刻清扫会拿旧清单把新文件
            // 当旧残留删掉（装包流程自己会在落盘后清扫，不需要这里插手）。
            if (inFlight) return;
            inFlight = true;
        }
        try {
            File root = installRoot();
            promoteStagedFiles(root);
            sweepStaleFiles();
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /**
     * 后端不可用时的入口：装好的就拉起，没装的就下载后拉起。
     * 所有判断在调用线程做，下载/解压/启动在后台线程。
     */
    static void onBackendMissing() {
        if (!enabled() || !supportedPlatform()) {
            return;
        }
        synchronized (LOCK) {
            if (inFlight || attempts >= MAX_ATTEMPTS) {
                return;
            }
            long now = System.nanoTime();
            if (now < nextAttemptAtNanos) {
                return;
            }
            attempts++;
            inFlight = true;
            nextAttemptAtNanos = now + RETRY_COOLDOWN_NANOS;
        }
        Thread worker = new Thread(() -> run(), "MuoniumPlayer-QishuiBackend");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 下载（缺包时）→ 校验 → 解压 → 内容核对 → 拉起进程 → 等健康检查。
     * 全程后台线程。
     */
    private static void run() {
        try {
            File root = installRoot();
            if (!isInstalled()) {
                Files.createDirectories(root.toPath());
                state = new State(Phase.DOWNLOADING, 0, null);
                announceProgress(0);
                log("开始自动下载 " + PACK.fileName() + "（镜像链 " + MIRRORS.size() + " 条）");
                File archive = new File(root.getParentFile(), PACK.fileName() + ".part");
                fetch(PACK, archive);
                state = new State(Phase.INSTALLING, 100, null);
                com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendInstalling();
                deleteStaleIncoming(root.getParentFile());
                File incoming = new File(root.getParentFile(), INCOMING_PREFIX + Long.toHexString(System.nanoTime()));
                unzip(archive, incoming);
                verifyExtracted(incoming);
                // zip 根目录就是 qishui-backend/：把 incoming/qishui-backend 整个并成
                // config/muonium/qishui-backend/。曾把目标写成 root.getParentFile()——
                // node.exe/server.mjs 全被装到了 config/muonium/ 根下，装好后
                // startBackend 找不到 node.exe 报 CreateProcess error=2（真机踩过）。
                // 上一版错误安装曾把后端文件散落在 config/muonium/ 根下，装前清掉。
                deleteLegacyStrays(root.getParentFile());
                File packRoot = new File(incoming, "qishui-backend");
                // 装之前先按新包清理旧安装：目录里可能躺着上一版甚至更早混进来的文件
                // （改名前的 node.exe 等）。清单先于清理写入——它描述「装完后目录该有
                // 什么」，先写清单再清，刚落地的新文件才不会被当成旧残留。
                writeManifest(packRoot, root);
                copyTree(packRoot, root);
                deleteRecursively(incoming);
                Files.deleteIfExists(archive.toPath());
                // 装完即验最终落点:上面那次 copyTree 目标写错一层时,verifyExtracted
                // (只查 incoming)拦不住,必须在最终路径上再核一遍。
                if (!new File(root, "server.mjs").isFile() || findRuntime(root) == null) {
                    throw new IOException("安装落点校验失败：" + root.getAbsolutePath()
                            + " 下缺 server.mjs 或 Node 运行时");
                }
                sweepStaleFiles();
                log("已装好 " + PACK.fileName() + " → " + root.getAbsolutePath());
                writeVersionMarker();
            }
            state = new State(Phase.STARTING, 100, null);
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendStarting();
            Process process = startBackend(root);
            if (process != null) {
                QishuiBackendLifecycle.adoptOwnedProcess(process);
            }
            if (awaitHealthy(15_000L)) {
                QishuiBackend.onBackendUp();
                if (QishuiBackend.peekAvailable()) {
                    state = new State(Phase.RUNNING, 100, null);
                    log("后端已就绪 " + QishuiBackend.baseUrl());
                    com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendReady();
                } else {
                    state = new State(Phase.FAILED, 0, "同步账号状态时后端失去响应");
                    com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure(state.detail());
                    log(state.detail());
                }
            } else {
                state = new State(Phase.FAILED, 0, "后端进程已拉起但健康检查未通过");
                com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure(state.detail());
                log(state.detail());
            }
        } catch (Throwable failure) {
            state = new State(Phase.FAILED, 0, safeMessage(failure));
            com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendFailure(safeMessage(failure));
            log("自动安装失败：" + describe(failure));
        } finally {
            synchronized (LOCK) {
                inFlight = false;
            }
        }
    }

    /** 用安装目录里的运行时把 server.mjs 拉成独立进程（看门狗标志由
     *  {@link QishuiBackendLifecycle#spawn} 统一带上）；输出重定向到 backend.log 供排查。
     *  已有健康后端时不重复拉起返回 null；进程所有权交给 {@link QishuiBackendLifecycle} 登记。 */
    private static Process startBackend(File root) throws IOException {
        if (QishuiBackend.probeAlive() || QishuiBackendLifecycle.recentlySpawned()) {
            log("8787 已有健康后端或另一组件刚拉起，不重复拉起");
            return null;
        }
        File rootAbs = root.getAbsoluteFile();
        Process process = QishuiBackendLifecycle.spawn(rootAbs);
        QishuiBackendLifecycle.noteSpawn();
        log("后端进程已拉起 pid=" + process.pid() + " → " + rootAbs.getAbsolutePath());
        return process;
    }

    /** 等健康检查通过；期间每 500ms 探一次。 */
    private static boolean awaitHealthy(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (QishuiBackend.probeAlive()) return true;
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 取包到 {@code archive}：本地替代源优先，否则多路竞速下载（见 {@link HttpDownloadRace}），
     * 开关关掉时退回旧的逐路串行回退。字节数 + sha256 全验。
     */
    private static void fetch(Pack pack, File archive) throws Exception {
        String localDir = System.getProperty(PROP_LOCAL_DIR);
        if (localDir != null && !localDir.isBlank()) {
            File local = new File(localDir, pack.fileName());
            if (!local.isFile()) {
                throw new IOException("本地伴生包目录里没有 " + pack.fileName() + "：" + localDir);
            }
            checkAgainst(pack, local.length(), sha256(local));
            Files.copy(local.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (HttpDownloadRace.enabled()) {
            List<String> urls = new ArrayList<String>(MIRRORS.size());
            for (String mirror : MIRRORS) {
                urls.add(mirror + pack.fileName());
            }
            HttpDownloadRace.fetch(urls, pack.bytes(), pack.sha256(), archive, QishuiCompanionDownload::announceProgress);
            return;
        }
        Exception last = null;
        for (String mirror : MIRRORS) {
            try {
                download(pack, mirror + pack.fileName(), archive);
                return;
            } catch (Exception failure) {
                last = failure;
                try {
                    Files.deleteIfExists(archive.toPath());
                } catch (Throwable ignored) {
                }
            }
        }
        throw last == null ? new IOException("没有可用的下载源") : last;
    }

    /** 单次下载：读流与校验同步走，进度按整百分比推进。 */
    private static void download(Pack pack, String url, File archive) throws Exception {
        HttpURLConnection connection = openFollowing(URI.create(url).toURL());
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        long total = connection.getContentLengthLong();
        if (total > 0 && total != pack.bytes()) {
            throw new IOException("大小不符（Content-Length " + total + "，应为 " + pack.bytes() + "）");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0L;
        int shown = -1;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                if (pack.bytes() > 0 && done > pack.bytes()) {
                    throw new IOException("下超了：已收 " + done + " 字节，应为 " + pack.bytes());
                }
                if (pack.bytes() > 0) {
                    int percent = (int) Math.min(100L, done * 100L / pack.bytes());
                    if (percent != shown) {
                        shown = percent;
                        state = new State(Phase.DOWNLOADING, percent, null);
                        announceProgress(percent);
                    }
                }
            }
        }
        checkAgainst(pack, archive.length(), hex(digest.digest()));
    }

    /** 重定向自己跟（镜像会 30x 到 CDN），最多 6 跳。 */
    private static HttpURLConnection openFollowing(URL url) throws Exception {
        return openFollowing(url, 0);
    }

    private static HttpURLConnection openFollowing(URL url, int depth) throws Exception {
        if (depth > 6) {
            throw new IOException("重定向过多");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "MuoniumPlayer-QishuiBackend/1.0");
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("重定向没有 Location");
            }
            return openFollowing(new URL(url, location), depth + 1);
        }
        return connection;
    }

    /** 并入前核对包内结构：server.mjs、Node 运行时、解密器三件套必须在，体量要合理。 */
    private static void verifyExtracted(File incoming) throws IOException {
        File root = new File(incoming, "qishui-backend");
        File server = new File(root, "server.mjs");
        File decryptor = new File(root, "qishui-audio-decryptor" + File.separator + "track-decryptor.js");
        if (!server.isFile() || server.length() < 10_000L) {
            throw new IOException("包内缺 server.mjs 或体量异常");
        }
        if (findRuntime(root) == null) {
            throw new IOException("包内缺 Node 运行时(" + runtimeFileName() + ")或体量异常");
        }
        if (!decryptor.isFile()) {
            throw new IOException("包内缺解密器 track-decryptor.js");
        }
    }

    /** 清掉上一次失败留下的暂存目录。 */
    private static void deleteStaleIncoming(File parent) {
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(INCOMING_PREFIX)) {
                deleteRecursively(child);
            }
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                Files.createDirectories(target.toPath());
                copyTree(child, target);
            } else {
                Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 解压（自带 zip-slip 防护，与 Skija 安装器同款形状）。 */
    private static void unzip(File archive, File target) throws Exception {
        if (!target.exists() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("无法创建暂存目录: " + target);
        }
        String root = target.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(target, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("不安全的压缩包条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream output = new FileOutputStream(out)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Throwable ignored) {
        }
    }

    private static void checkAgainst(Pack pack, long bytes, String sha256) throws IOException {
        if (bytes != pack.bytes()) {
            throw new IOException("大小不符：得到 " + bytes + " 字节，应为 " + pack.bytes());
        }
        if (!pack.sha256().equalsIgnoreCase(sha256)) {
            throw new IOException("sha256 不符：得到 " + sha256);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("本机 JRE 没有 SHA-256", impossible);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    /** 进度推送节流:动态岛是同频道替换式更新,别每个字节都推一次。 */
    private static long lastNoticeAtMillis;

    private static void announceProgress(int percent) {
        long now = System.currentTimeMillis();
        if (now - lastNoticeAtMillis < 400L && percent < 100) {
            return;
        }
        lastNoticeAtMillis = now;
        com.muoniumplayer.core.rendering.DownloadDynamicIsland.showQishuiBackendProgress(percent);
    }

    /** 清掉错误安装散落在 config/muonium/ 根下的后端文件(只认名单,不误伤)。 */
    private static void deleteLegacyStrays(File configRoot) {
        String[] strayNames = {"node.exe", "server.mjs", "mssdk-bridge.mjs", "start.bat",
                "README.txt", "config.note.txt"};
        for (String name : strayNames) {
            try {
                Files.deleteIfExists(new File(configRoot, name).toPath());
            } catch (Throwable ignored) {
            }
        }
        deleteRecursively(new File(configRoot, "qishui-audio-decryptor"));
    }

    private static String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    private static void log(String message) {
        System.out.println("[MuoniumPlayer/QishuiCompanion] " + message);
    }
}
