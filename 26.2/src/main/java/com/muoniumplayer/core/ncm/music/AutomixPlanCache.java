package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 已算出的 automix 交接计划的缓存（内存 + 落盘）。
 *
 * <p>为什么需要它：算出「这一首怎么交给下一首」这件事很贵。装了模型的机器要跑一次 Folia，
 * 里面是一个 Python 的 stem 分离进程；只有内置信号处理那一路也要把两首歌各解码一遍、读 PCM、
 * 做前奏扫描 / 节拍网格 / 调性估计。而同一对曲目反复出现是常态 —— 列表循环、一起听房间里
 * 轮流点歌、同一首歌在不同房间被放。每一轮都从头推理一遍纯属白烧 CPU，尤其是 Folia 那条路。</p>
 *
 * <p>键是「<b>两首歌的实际音频</b> + 会影响结果的设置 + 退出曲目最后一句人声的位置」。
 * 取音频文件的<b>文件名与字节长度</b>而不是曲目 ID：同一首歌换音质、或者缓存被重新下载成了
 * 另一份字节，算出来的交接点本来就可能不同，用 ID 当键会把一份过期的计划当成命中。长度变了
 * 自然失配，旧文件随后由容量兜底回收。</p>
 *
 * <p>{@code lastVocalEndMillis} 也必须进键：它由歌词时间轴得出，而歌词可能比这次分析晚加载。
 * 同一对曲目因此可能留下两份计划（带歌词 / 不带歌词），两份都是合法的，命中哪份都不算错，
 * 只是带歌词的那份混出点更准。</p>
 *
 * <p>设置同样进键。引擎从 Folia 换成内置信号处理、或者关掉低音交换，算出来的就不是同一条曲线；
 * 不把这些算进键，用户改了设置却听不到变化，会显得开关是坏的。</p>
 *
 * <h3>语义</h3>
 * <p>与 {@link LyricCacheStore} 一致：<b>整个目录可以随时删掉</b>，删掉只会让下次交接重新分析一遍，
 * 不丢任何用户数据。任何读写失败都静默降级为「未命中」—— 缓存永远不能成为一个能把播放卡住的
 * 新故障点。</p>
 */
final class AutomixPlanCache {

    /** 缓存格式版本。任何字段语义变化都要 +1，旧版本会被当成未命中。 */
    private static final int FORMAT_VERSION = 1;

    /** 落盘条目上限。一份计划约 1 KB，2000 条约 2 MB。 */
    private static final int MAX_ENTRIES = 2000;

    /** 每写这么多次才整理一次目录，避免每次都 listFiles。 */
    private static final int PRUNE_INTERVAL = 64;

    /**
     * 内存缓存条数。
     *
     * <p>落盘缓存解决的是「重启之后不用重算」，但读盘本身也要几毫秒、还要反序列化。房间与本地
     * 播放最常命中的是「刚刚算过的那几对」，所以内存里再留一层，用访问序 LRU 淘汰。</p>
     */
    private static final int MEMORY_ENTRIES = 64;

    private static final AtomicInteger WRITES_SINCE_PRUNE = new AtomicInteger();

    /**
     * 落盘目录。默认 {@link ConfigPaths#AUTOMIX_PLAN_CACHE}，测试里可换成一个临时目录 ——
     * 缓存的行为（键、原子替换、损坏清理、容量淘汰）都值得单独测，而它们全都要落到真实文件上。
     */
    private static volatile File directory = ConfigPaths.AUTOMIX_PLAN_CACHE;

    /** 访问序 LRU：命中时会被移到队尾，淘汰时丢队首。 */
    private static final Map<String, AutomixPlan> MEMORY =
            new LinkedHashMap<String, AutomixPlan>(16, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AutomixPlan> eldest) {
                    return size() > MEMORY_ENTRIES;
                }
            };

    private AutomixPlanCache() {
    }

    /** 换一个落盘目录（测试用）。同时清掉内存层，免得旧目录的命中串到新目录上。 */
    static void useDirectory(File target) {
        directory = target == null ? ConfigPaths.AUTOMIX_PLAN_CACHE : target;
        synchronized (MEMORY) {
            MEMORY.clear();
        }
    }

    // ── 读 / 写 ─────────────────────────────────────────────────────────────

    /**
     * 取一份已经算过的计划。
     *
     * @param lastVocalEndMillis 本次退出曲目最后一句人声的位置，与 {@link AutomixPlanner} 同一个参数
     * @return 命中且仍然有效时返回计划；未命中、键不全、内容损坏时返回 {@code null}
     */
    static AutomixPlan load(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis) {
        String key = keyOf(sourceOf(outgoing), sourceOf(incoming), lastVocalEndMillis);
        if (key.isEmpty()) return null;

        AutomixPlan memory = memory(key);
        if (memory != null) return memory;

        AutomixPlan disk = read(key);
        if (disk != null) remember(key, disk);
        return disk;
    }

    /**
     * 记下一份刚算出的计划。失败一律静默 —— 缓存写不进去不影响这次交接。
     *
     * <p>{@code null} 计划<b>不</b>会被缓存。算不出计划的原因里有一类是「这次音频没读到」
     * （解码缓存还没落盘、读窗口暂时失败），那是暂时状态；把它当命中记下来，会让这首曲子
     * 在之后所有轮次里都永远不交叠。</p>
     */
    static void store(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis,
                      AutomixPlan plan) {
        if (plan == null) return;
        store(sourceOf(outgoing), sourceOf(incoming), lastVocalEndMillis, plan);
    }

    /** 同 {@link #store(AudioPlayer, AudioPlayer, long, AutomixPlan)}，但直接收音频文件。 */
    static void store(File outgoing, File incoming, long lastVocalEndMillis, AutomixPlan plan) {
        if (plan == null) return;
        String key = keyOf(outgoing, incoming, lastVocalEndMillis);
        if (key.isEmpty()) return;

        remember(key, plan);
        try {
            File target = directory;
            if (!target.isDirectory() && !target.mkdirs()) return;

            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("key", key);
            root.add("plan", RoomAutomix.encode(plan));

            File file = fileFor(key);
            // 先写临时文件再原子替换：写到一半被杀不会留下半截 JSON 被当成命中。
            File temporary = new File(target, file.getName() + ".tmp");
            Files.write(temporary.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            if (WRITES_SINCE_PRUNE.incrementAndGet() >= PRUNE_INTERVAL) {
                WRITES_SINCE_PRUNE.set(0);
                prune();
            }
        } catch (Throwable failure) {
            System.err.println("[Automix/PlanCache] 写入失败: " + failure.getMessage());
        }
    }

    /** 直接按音频文件取一份计划（测试与内部用）。 */
    static AutomixPlan load(File outgoing, File incoming, long lastVocalEndMillis) {
        String key = keyOf(outgoing, incoming, lastVocalEndMillis);
        if (key.isEmpty()) return null;
        AutomixPlan memory = memory(key);
        if (memory != null) return memory;
        AutomixPlan disk = read(key);
        if (disk != null) remember(key, disk);
        return disk;
    }

    /** 删掉全部落盘缓存与内存副本。用户手动「清空缓存」时用。 */
    static void clear() {
        synchronized (MEMORY) {
            MEMORY.clear();
        }
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                System.err.println("[Automix/PlanCache] 删除失败 " + file.getName());
            }
        }
    }

    // ── 键 ──────────────────────────────────────────────────────────────────

    /**
     * 一次交接的完整键。
     *
     * <p>三样都缺一不可：两首歌的音频、影响曲线的设置、以及退出曲目的歌词边界。任何一台
     * deck 拿不到自己的源文件（极少见，但解码失败的路径确实会走到）就直接放弃缓存 ——
     * 宁可每次重算，也不能把一份不知道属于哪首歌的计划交出去。</p>
     */
    private static String keyOf(File outgoing, File incoming, long lastVocalEndMillis) {
        String left = audioKey(outgoing);
        String right = audioKey(incoming);
        if (left.isEmpty() || right.isEmpty()) return "";
        return left + ">" + right + "@" + settingsKey() + "#" + lastVocalEndMillis;
    }

    /** 一路 deck 背后的解码文件；拿不到时为 {@code null}。 */
    private static File sourceOf(AudioPlayer deck) {
        if (deck == null) return null;
        try {
            return deck.getSourceFile();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * 一路 deck 的音频身份：文件名 + 字节长度。
     *
     * <p>文件名里已经带着曲目的稳定键与音质（见 {@code CloudMusic#audioCacheKey}），长度再兜一层
     * 「同一首歌被重新下载成了另一份字节」。两者都不含路径，因此同一首歌在不同机器、不同游戏
     * 目录下算出的键一致 —— 这正是房间场景里「别人算过我也能直接用」的前提。</p>
     */
    private static String audioKey(File file) {
        if (file == null) return "";
        try {
            String name = file.getName();
            if (name == null || name.isEmpty()) return "";
            return name + ":" + file.length();
        } catch (Throwable unavailable) {
            return "";
        }
    }

    /**
     * 会影响计划结果的设置。
     *
     * <p>这些就是两个规划器实际读到的全部开关（{@link AutomixPlanner} 与
     * {@link FoliaAutomixPlanner} 里的 {@code AutomixSettings} 引用）。漏掉任何一个，用户改了它
     * 却仍然命中旧计划，看起来就是那个开关没接上。</p>
     */
    private static String settingsKey() {
        try {
            return AutomixSettings.getEngine()
                    + "," + AutomixSettings.getOverlapMillis()
                    + "," + AutomixSettings.isBeatAlignEnabled()
                    + "," + AutomixSettings.isBassSwapEnabled()
                    + "," + AutomixSettings.isTempoLockEnabled()
                    + "," + AutomixSettings.isVocalDuckEnabled()
                    + "," + AutomixSettings.isPitchMatchEnabled()
                    + "," + AutomixSettings.isSkipIntroEnabled()
                    + "," + AutomixSettings.isPerformanceModeEnabled();
        } catch (Throwable unavailable) {
            // 设置读不到就不给键：缓存整个失效，比拿一份「不知道按什么设置算的」计划安全。
            return "";
        }
    }

    // ── 内存层 ──────────────────────────────────────────────────────────────

    private static AutomixPlan memory(String key) {
        synchronized (MEMORY) {
            return MEMORY.get(key);
        }
    }

    private static void remember(String key, AutomixPlan plan) {
        synchronized (MEMORY) {
            MEMORY.put(key, plan);
        }
    }

    // ── 落盘层 ──────────────────────────────────────────────────────────────

    /** 读盘。任何异常都当未命中，并顺手把坏文件删掉，免得每次交接都踩一次。 */
    private static AutomixPlan read(String key) {
        File source = fileFor(key);
        if (!source.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(text).getAsJsonObject();
            if (root.get("version").getAsInt() != FORMAT_VERSION) return null;
            // 哈希撞了不要紧，但绝不能把别的曲目的计划交出去。
            if (!key.equals(root.get("key").getAsString())) return null;
            return RoomAutomix.decode(root.getAsJsonObject("plan"));
        } catch (Throwable failure) {
            invalidate(source);
            return null;
        }
    }

    private static void invalidate(File source) {
        if (source.isFile() && !source.delete()) {
            System.err.println("[Automix/PlanCache] 清理损坏缓存失败 " + source.getName());
        }
    }

    /** 超出上限时按最后修改时间删掉最旧的一批。 */
    private static void prune() {
        File target = directory;
        File[] files = target.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;

        List<File> candidates = new ArrayList<>(Arrays.asList(files));
        candidates.sort(new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(left.lastModified(), right.lastModified());
            }
        });
        int remove = candidates.size() - MAX_ENTRIES;
        for (int index = 0; index < remove; index++) {
            File file = candidates.get(index);
            // 只删自己写出来的 .json；临时文件留给下一次原子替换覆盖。
            if (file.isFile() && file.getName().endsWith(".json") && !file.delete()) {
                System.err.println("[Automix/PlanCache] 清理失败 " + file.getName());
            }
        }
    }

    /**
     * 由键推一个文件名。
     *
     * <p>不能用 {@code hashCode()}：它跨进程不稳定，而这份缓存要跨进程复用。用 FNV-1a 得到
     * 定长十六进制名，键里的 {@code :} {@code >} {@code @} 也就不会跑进文件名。</p>
     */
    private static File fileFor(String key) {
        byte[] bytes = ("automix:" + key).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return new File(directory, Long.toHexString(hash) + ".json");
    }
}
