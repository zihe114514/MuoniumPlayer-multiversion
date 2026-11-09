package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.update.SemVer;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MuoniumConfig 的<b>远端版本清单</b>：每个车道一个 {@code index.json}，说清"这个
 * MC 版本 + 加载器现在该装哪个包"。
 *
 * <h2>为什么不能继续把版本钉在客户端里</h2>
 * 现在的 {@link MuoniumConfigDownload#BUILDS} 表是把「文件名 + 字节数 + sha256」硬编码进
 * 玩家客户端 jar 的。这套做法的后果是：<b>MuoniumConfig 一发新版，老客户端就永远拉不到</b>
 * —— 它们手里那张表还是老的，而 BUILDS 表只随 MuoniumPlayer 自己的版本走。于是"修 MuoniumConfig
 * 的 bug"这件事，必须靠玩家升级整个播放器 mod 才能解决，那正是我们要消掉的一层耦合。
 *
 * <p>改成运行时读清单后：发新版 = 换清单里的一个文件；玩家下次启动就拿到新包，不需要动播放器。
 * 客户端仍然保留一张<b>内置表</b>（{@link MuoniumConfigDownload}）当兜底：清单拉不到
 * （断网、镜像全挂、清单被删）时退回内置值，至少不会"什么都没有"。
 *
 * <h2>清单长什么样</h2>
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "id": "muoniumconfigv1",
 *   "lane": "26.3/fabric",
 *   "version": "1.8.0",
 *   "file": "muoniumconfig-1.8.0.jar",
 *   "bytes": 26123481,
 *   "sha256": "e13b...",
 *   "minLoader": "1.7.0",
 *   "notes": "修复：…"
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code schema} 不认就整份丢弃 —— 以后要加字段才有退路；</li>
 *   <li>{@code id} 必须是 {@value MuoniumConfigJarProbe#MOD_ID}，防止清单被指到别的 mod；</li>
 *   <li>{@code lane} 必须与请求的车道一致 —— 防止 26.3 的清单被错放到 1.21.1 目录下
 *       时装出一个必然崩的包；</li>
 *   <li>{@code bytes} 与 {@code sha256} 是下载的硬校验，缺一不可（这两个值也决定了
 *       "装哪个文件"这件事，不允许清单只说版本不说包）；</li>
 *   <li>{@code minLoader} 可选：本次播放器版本低于它时<b>不自动更新</b>。用来表达
 *       "新版 MuoniumConfig 需要新播放器配合"这一类耦合，避免把人更到崩。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * {@link #fetch} 本身是阻塞的，全部在调用方的守护线程上跑（{@link MuoniumConfigDownload}
 * 的下载线程），不碰主线程。多镜像并发拉取，任一源给出<b>可用的</b>清单即可返回。
 */
final class MuoniumConfigIndex {

    /**
     * 支持的清单结构版本。客户端只认自己认识的那一版：清单改成 2 而客户端只懂 1 时，
     * 整份丢弃退到内置表 —— 比按半懂不懂的字段去下载安全得多。
     */
    static final int SCHEMA = 1;

    /** 单个 index.json 的读取上限：正常不到 1 KB，超过就是有人在塞垃圾。 */
    private static final int MAX_BYTES = 64 * 1024;

    private static final int CONNECT_TIMEOUT_MILLIS = 8000;
    private static final int READ_TIMEOUT_MILLIS = 8000;

    /**
     * 所有镜像一起拉的总预算。
     *
     * <p>与 {@code UpdateChecker.fetchEntry} 同一个道理：源的数量会增加，等待时间必须是
     * 常数。一个 index.json 不到 1 KB，正常源 100ms 内就回来了，8 秒足够；卡住的源直接
     * 丢弃，不让它拖住整个更新流程（后面还有一个 26 MB 的包要下）。</p>
     */
    private static final long FETCH_BUDGET_MILLIS = 8000L;

    /** 一个车道该装什么。字段语义见类注释。 */
    static final class Entry {
        final String lane;
        final String version;
        final String fileName;
        final long bytes;
        final String sha256;
        /** 低于这个播放器版本就不自动装；空串表示不限。 */
        final String minLoader;
        final String notes;
        /** 是从远端清单读来的，还是客户端内置的兜底。日志与提示文案用。 */
        final boolean remote;

        Entry(String lane, String version, String fileName, long bytes, String sha256,
              String minLoader, String notes, boolean remote) {
            this.lane = lane == null ? "" : lane;
            this.version = version == null ? "" : version;
            this.fileName = fileName == null ? "" : fileName;
            this.bytes = bytes;
            this.sha256 = sha256 == null ? "" : sha256;
            this.minLoader = minLoader == null ? "" : minLoader;
            this.notes = notes == null ? "" : notes;
            this.remote = remote;
        }

        /**
         * 字段是否齐到能下载。
         *
         * <p>文件名必须是<b>裸文件名</b>：清单来自网络，若允许 {@code ../../} 之类的路径，
         * 一个被篡改的清单就能把文件写到 mods/ 之外。这里直接拒收，不做"清理后接受"。</p>
         */
        boolean isUsable() {
            return !fileName.isEmpty()
                    && fileName.indexOf('/') < 0
                    && fileName.indexOf('\\') < 0
                    && !fileName.contains("..")
                    && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                    && bytes > 0L
                    && sha256.length() == 64
                    && isHex(sha256);
        }

        /** 版本号可比较。 */
        boolean hasVersion() {
            return !version.isEmpty() && !SemVer.isPreRelease(version);
        }

        private static boolean isHex(String value) {
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
                if (!hex) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return lane + " → " + fileName + " (" + (remote ? "清单" : "内置") + ")";
        }
    }

    private MuoniumConfigIndex() {
    }

    /**
     * 从全部镜像拉这个车道的清单，取<b>版本号最高</b>的那一份。
     *
     * <p>与 {@code UpdateChecker} 同一个策略：并发拉全部源、取最高版本，而不是"第一个答的"。
     * 各镜像缓存策略不同，同一时刻可能一个给新清单一个给旧的，而谁先答每次都可能变。</p>
     *
     * @param mirrors  镜像前缀（以 {@code /} 结尾），会自动拼 {@code <lane>/index.json}
     * @param lane     车道键，例如 {@code 26.3/fabric}
     * @param loaderVersion 当前播放器版本（用于 {@code minLoader} 判定）；空串表示不判定
     * @return 可用的最高版本条目；全部源都失败或都不合规时返回 {@code null}
     */
    static Entry fetch(List<String> mirrors, String lane, String loaderVersion) {
        if (mirrors == null || mirrors.isEmpty() || lane == null || lane.isEmpty()) {
            return null;
        }
        final int count = mirrors.size();
        final AtomicReferenceArray<Entry> results = new AtomicReferenceArray<>(count);
        Thread[] workers = new Thread[count];
        for (int index = 0; index < count; index++) {
            final int slot = index;
            final String url = mirrors.get(index) + lane + "/index.json";
            workers[index] = new Thread(() -> results.set(slot, fetchOne(url, lane, loaderVersion)),
                    "muonium-config-index-" + (index + 1));
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

        Entry newest = null;
        StringBuilder trace = new StringBuilder();
        for (int index = 0; index < count; index++) {
            Entry entry = results.get(index);
            trace.append(" #").append(index + 1);
            if (entry == null) {
                trace.append("=none");
                continue;
            }
            trace.append('=').append(entry.version);
            if (newest == null || SemVer.isNewer(entry.version, newest.version)) {
                newest = entry;
            }
        }
        System.out.println("[MuoniumPlayer] MuoniumConfig 清单（" + lane + "）：" + trace);
        return newest;
    }

    /** 拉一个源。任何异常都只是"这个源这次不可用"，绝不往上抛。 */
    private static Entry fetchOne(String url, String lane, String loaderVersion) {
        InputStream stream = null;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "MuoniumPlayer-MuoniumConfigIndex/1.0");
            headers.put("Accept", "application/json");
            // 清单必须新鲜：镜像的 12 小时缓存会让"刚发的新版"看不见，那是这类问题的
            // 主要来源。no-cache 让 CDN 回源；不支持该头的源照常返回，不影响。
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
            stream = HttpUtils.get(url, null, headers, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
            if (stream == null) {
                return null;
            }
            String body = HttpUtils.readString(stream);
            if (body == null || body.isBlank()) {
                return null;
            }
            return parse(body, lane, loaderVersion);
        } catch (Throwable unreachable) {
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 解析并校验一份清单。
     *
     * <p>用宽松的正则抠字段而不是引入 JSON 解析器：这里只有 8 个标量字段、没有嵌套结构，
     * 而清单是网络上来的不可信文本 —— 手写匹配对"多一个字段 / 少一个字段 / 顺序变了"
     * 都是天然容忍的，这正是我们想要的宽进严出。</p>
     *
     * <p>包级可见是为了让静态测试直接钉住校验规则，不需要网络。</p>
     *
     * @return 合规时返回条目；schema/id/lane 不符、字段缺失或路径可疑时返回 {@code null}
     */
    static Entry parse(String body, String lane, String loaderVersion) {
        if (body == null || body.length() > MAX_BYTES) {
            return null;
        }
        int schema = (int) number(body, "schema", -1L);
        if (schema != SCHEMA) {
            return null;
        }
        String id = text(body, "id");
        if (!MuoniumConfigJarProbe.MOD_ID.equals(id)) {
            return null;
        }
        String declaredLane = text(body, "lane");
        if (!lane.equals(declaredLane)) {
            // 车道对不上：说明清单放错了位置，或者这个镜像目录被别的车道覆盖了。
            // 装错版本的 MuoniumConfig 会在启动期崩，宁可这一轮什么都不装。
            return null;
        }
        String version = text(body, "version");
        if (version.isEmpty()) {
            return null;
        }
        Entry entry = new Entry(
                lane,
                version,
                text(body, "file"),
                number(body, "bytes", 0L),
                text(body, "sha256").toLowerCase(java.util.Locale.ROOT),
                text(body, "minLoader"),
                text(body, "notes"),
                true);
        if (!entry.isUsable()) {
            return null;
        }
        if (!entry.minLoader.isEmpty() && loaderVersion != null && !loaderVersion.isEmpty()
                && SemVer.isNewer(entry.minLoader, loaderVersion)) {
            // 这个包要求更新的播放器版本：不自动装（装了可能因接口变动而崩），
            // 但也不算失败 —— 调用方会继续用已装的旧版。
            System.out.println("[MuoniumPlayer] MuoniumConfig " + entry.version
                    + " 要求播放器 ≥ " + entry.minLoader + "（当前 " + loaderVersion + "），跳过本次更新");
            return null;
        }
        return entry;
    }

    /** 抠字符串字段：{@code "version": "1.8.0"}。缺失或非字符串返回空串。 */
    private static String text(String body, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(body);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** 抠数字字段：{@code "bytes": 26123481}（允许写成字符串形式）。 */
    private static long number(String body, String key, long fallback) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(-?[0-9]{1,19})\"?")
                .matcher(body);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException overflow) {
            return fallback;
        }
    }

    /**
     * 把一个内置（硬编码）条目包成 {@link Entry}，与远端条目走同一条下载与删除链路。
     *
     * <p>内置条目的 {@code lane}/{@code version} 由调用方从键与文件名推出 —— 它本来就在
     * 客户端里，不需要 minLoader 之类的防护。</p>
     */
    static Entry builtin(String lane, String fileName, long bytes, String sha256) {
        return new Entry(lane, versionOfFileName(fileName), fileName, bytes, sha256, "", "", false);
    }

    /** 从 {@code muoniumconfig-1.7.0.jar} 里取 {@code 1.7.0}；取不出返回空串。 */
    static String versionOfFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("-(\\d+(?:\\.\\d+)+)\\.jar$").matcher(fileName);
        return matcher.find() ? matcher.group(1) : "";
    }
}
