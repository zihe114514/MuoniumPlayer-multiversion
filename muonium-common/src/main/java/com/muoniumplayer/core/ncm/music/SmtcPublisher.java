package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.spotify.SpotifyConnectMirror;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 发布模式：把本模组<b>自己</b>的播放写成一条系统媒体会话（SMTC 的另一半）。
 *
 * <p>镜像（{@link ExternalPlayback}）是读系统；这里是写系统——内部播放时，媒体键可以
 * 播放/暂停/切歌，Windows 音量浮窗与锁屏里出现这条会话。实现全部落在助手进程里
 * （interop {@code GetForWindow}，见 {@code SmtcWatcher.cs} 发布段），Java 侧只负责
 * 三件事：把播放态推给它、把媒体键回传接进 {@link CloudMusic} 的播控、管住开关。</p>
 *
 * <p>推送节奏：元数据与状态只在变化时推；时间轴播放中每秒一次（微软建议约 5 秒，
 * 推密一点只是多一行 stdin，换取浮窗进度条更连贯）；封面只在换歌时推一次。
 * 与镜像的互斥是单向的——镜像接管时内部播放本来就被拒了，这里顺势把发布会话
 * 收掉，浮窗里只剩外部播放器一条，不打架。</p>
 *
 * <p>开关持久化在 {@code config/muonium/smtc_publish.json}，启动参数
 * {@code -Dmuonium.smtc.publish=false} 可以硬关（排查问题时不用去改文件）。
 * 读写全部在后台线程，主线程 tick 只做内存读。</p>
 */
public final class SmtcPublisher {

    /** 播放中时间轴的推送间隔。与微软「约 5 秒」建议比是加密了，但一行文本的代价可以忽略。 */
    static final long TIMELINE_INTERVAL_MS = 1_000L;

    /**
     * 发布会话在系统里的身份。这个 AUMID 同时是三样东西的键：
     * 浮窗显示名的注册表键（Software\Classes\AppUserModelId\...）、
     * 镜像端「不镜像自己」的排除键、发布会话的全局身份。
     */
    public static final String PUBLISH_AUMID = "MuoniumPlayer.SMTC";
    public static final String PUBLISH_DISPLAY = "MuoniumPlayer";

    private static final Object CONFIG_LOCK = new Object();
    private static boolean configLoaded;
    private static volatile boolean enabled = true;

    /**
     * 媒体键回传的队列。助手的事件线程写入，主线程 tick 里取走执行——
     * CloudMusic 的播控不是线程安全的，绝不能在管道读线程上直接调。
     */
    private static final ConcurrentLinkedQueue<String[]> COMMANDS = new ConcurrentLinkedQueue<String[]>();

    /** 上一次推送出去的曲目键；换歌检测与「没有在播」的收起动作都靠它。 */
    private static String pushedTrackKey = "";
    private static String pushedState = "";
    private static String pushedArtPath = "";
    private static long lastTimelinePushMs;
    /** 镜像/无播放时已经收起过一次的记号，避免每 tick 重复发 pub off。 */
    private static boolean collapsed;
    /** 发布图标落盘后的绝对路径；null 表示还没解出来（浮窗暂时没图标，不影响显示名）。 */
    private static volatile String iconPath;
    private static volatile boolean iconReady;
    /** 双开游戏的另一个实例已占用发布会话时只说一次。 */
    private static boolean warnedAlreadyRunning;

    private SmtcPublisher() {
    }

    /** 发布是否开着。文件还没读到时按默认（开）回答。 */
    public static boolean enabled() {
        ensureConfigAsync();
        return enabled;
    }

    /** 设置页将来接开关用；写盘在后台，立即生效的是内存值。 */
    public static void setEnabled(boolean value) {
        enabled = value;
        ensureConfigAsync();
        final boolean v = value;
        MultiThreadingUtil.runAsync(() -> {
            synchronized (CONFIG_LOCK) {
                writeConfig(v);
            }
        });
    }

    /**
     * 主线程每 tick 调用（挂在 {@link ExternalPlayback#onClientTick()} 的最前面，
     * 早于一切镜像分支的 return——发布在任何模式下都要被喂）。
     *
     * <p>整段包在 try 里：它依赖外部进程与配置文件，任何一步出错都只该让
     * 「这一拍不推送」，不该把镜像或游戏带下去。</p>
     */
    static void onClientTick() {
        try {
            if (!ExternalPlayback.supportsPlatform()) return;
            ensureConfigAsync();
            // 愿望先行：发布开着时助手必须活着（即使玩家还没开播，浮窗里要有位置）。
            ExternalMediaService.setPublishDesired(enabled);
            if (!ExternalMediaService.isRunning()) return;
            if (ExternalMediaService.helperProtocolVersion() < 2) return;   // v1 助手没有发布通道

            drainCommands();

            CloudMusic.PlaybackSnapshot snap = CloudMusic.getSnapshot();
            boolean mirrorOccupied = ExternalPlayback.isMirroring()
                    || SpotifyConnectMirror.isMirroring();
            Music track = snap == null ? null : snap.music;
            if (mirrorOccupied || track == null) {
                collapse();
                return;
            }

            String trackKey = track.getStableKey();
            boolean trackChanged = !trackKey.equals(pushedTrackKey);
            if (collapsed || trackChanged) {
                collapsed = false;
                pushedTrackKey = trackKey;
                pushedState = "";
                pushedArtPath = "";
                // 身份先行：AUMID 必须在会话创建前定型（helper 侧拿到才建会话），
                // 同时它也是镜像端「不镜像自己」的排除键。
                ensureIconAsync();
                ExternalMediaService.sendPublishAppId(PUBLISH_AUMID, PUBLISH_DISPLAY, iconPath);
                ExternalMediaService.sendPublishOn();
                ExternalMediaService.sendPublishMeta(metaJson(track));
                String art = coverPathFor(track);
                pushedArtPath = art;
                ExternalMediaService.sendPublishArt(art == null ? "" : art);
                lastTimelinePushMs = 0L;   // 换歌后时间轴立刻推一拍，不等间隔
            }

            String state = snap.playing ? "Playing" : "Paused";
            if (!state.equals(pushedState)) {
                pushedState = state;
                ExternalMediaService.sendPublishState(state);
            }

            long now = System.currentTimeMillis();
            if (snap.playing || trackChanged) {
                if (now - lastTimelinePushMs >= TIMELINE_INTERVAL_MS) {
                    lastTimelinePushMs = now;
                    ExternalMediaService.sendPublishTimeline(
                            Math.max(0L, snap.positionMs), Math.max(0L, snap.durationMs));
                }
            }
        } catch (Throwable failure) {
            System.err.println("[Music/SMTC] 发布 tick 异常: " + failure);
        }
    }

    /** 游戏退出/总闸：收起会话并撤掉对助手的愿望。幂等。 */
    public static void shutdown() {
        try {
            ExternalMediaService.sendPublishOff();
        } catch (Throwable ignored) {
        }
        ExternalMediaService.setPublishDesired(false);
    }

    /** 镜像接管或没有内部播放：把发布会话收起来，浮窗里只剩外部播放器那条。 */
    private static void collapse() {
        if (collapsed) return;
        collapsed = true;
        pushedTrackKey = "";
        pushedState = "";
        pushedArtPath = "";
        ExternalMediaService.sendPublishOff();
    }

    /** 把媒体键回传落进 CloudMusic 的播控。主线程，每 tick 清一次队列。 */
    private static void drainCommands() {
        String[] command;
        while ((command = COMMANDS.poll()) != null) {
            String key = command[0];
            String arg = command[1];
            try {
                if ("play".equals(key) || "pause".equals(key) || "toggle".equals(key)) {
                    CloudMusic.togglePlayPause();
                } else if ("next".equals(key)) {
                    CloudMusic.next();
                } else if ("prev".equals(key)) {
                    CloudMusic.prev();
                } else if ("stop".equals(key)) {
                    CloudMusic.stopPlaybackAndClear(CloudMusic.currentlyPlaying);
                } else if ("seek".equals(key)) {
                    long ms = -1L;
                    try { ms = Long.parseLong(arg.trim()); } catch (Throwable ignored) { }
                    if (ms >= 0L) CloudMusic.seekToMillis((float) ms);
                }
            } catch (Throwable failure) {
                System.err.println("[Music/SMTC] 媒体键命令执行失败: " + key + " " + failure);
            }
        }
    }

    /** 助手事件线程调（管道读线程）：只入队，不执行。 */
    static void enqueueCommand(String key, String argument) {
        COMMANDS.add(new String[] {key, argument});
    }

    /**
     * 助手的 pubcmd 事件行（{@code {"event":"pubcmd","key":"play","arg":""}}）。
     * 这里只解字段并入队——跑在管道读线程上，碰 CloudMusic 是主线程 tick 的事。
     */
    static void onHelperCommand(String jsonLine) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(jsonLine).getAsJsonObject();
            String key = root.has("key") && !root.get("key").isJsonNull()
                    ? root.get("key").getAsString() : "";
            String arg = root.has("arg") && !root.get("arg").isJsonNull()
                    ? root.get("arg").getAsString() : "";
            if (!key.isEmpty()) enqueueCommand(key, arg);
        } catch (Throwable ignored) {
            // 截断的行丢弃即可；媒体键下一按还会来。
        }
    }

    /**
     * 助手回了 already_running：系统里已有另一条 mod 发布会话（双开游戏的另一个
     * 实例，或上一个进程还没死干净）。这是设计内的行为——发布会话全系统只能一条，
     * 本实例的发布静默让位，但要让玩家知道为什么浮窗里没有自己。
     */
    static void onPublishDenied() {
        if (warnedAlreadyRunning) return;
        warnedAlreadyRunning = true;
        try {
            DownloadDynamicIsland.showExternalMedia(
                    "另一局游戏已占用系统媒体会话，本局的发布会让位", false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把 jar 里的 mod 图标解到 SMTC 目录，给发布浮窗当 IconUri——没有它浮窗
     * 图标是默认的灰方块（显示名靠注册表，与图标无关）。后台线程做，只做一次。
     */
    private static void ensureIconAsync() {
        if (iconReady) return;
        MultiThreadingUtil.runAsync(() -> {
            try {
                File target = new File(ConfigPaths.SMTC_DIR, "muonium-icon.png");
                if (!target.isFile() || target.length() == 0L) {
                    java.io.InputStream in = SmtcPublisher.class
                            .getResourceAsStream("/assets/muonium/icon.png");
                    if (in == null) return;   // jar 打包缺资源：浮窗没图标，不算错
                    try {
                        Files.copy(in, target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        in.close();
                    }
                }
                iconPath = target.getAbsolutePath();
                iconReady = true;
                // 首次发布时图标往往还没解出来，这里补发一次身份（带图标路径）。
                ExternalMediaService.sendPublishAppId(PUBLISH_AUMID, PUBLISH_DISPLAY, iconPath);
            } catch (Throwable failure) {
                // 图标缺失只影响浮窗好看与否，不值得打扰。
                System.out.println("[Music/SMTC] 发布图标落盘失败: " + failure);
            }
        });
    }

    /** 曲目元数据的一行 JSON。字段缺失就发空串，助手侧对空值一律照收。 */
    private static String metaJson(Music track) {
        JsonObject json = new JsonObject();
        json.addProperty("title", safe(track.getName()));
        json.addProperty("artist", firstArtist(track));
        json.addProperty("album", track.getAlbum() == null ? "" : safe(track.getAlbum().getName()));
        json.addProperty("albumArtist", "");
        return json.toString();
    }

    private static String firstArtist(Music track) {
        try {
            if (track.getArtists() != null && !track.getArtists().isEmpty()) {
                return safe(track.getArtists().get(0).getName());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ── 封面文件 ────────────────────────────────────────────────────────────────
    //
    // 助手的发布端只认文件路径（StorageFile.GetFileFromPathAsync），而本模组的封面
    // 在内存里是字节流。这里在封面下载的那个点把字节 tee 一份到 smtc 目录，
    // 以曲目稳定键命名；发布端拿到的是「当前这首对应的文件」。

    /** 封面 tee 的目录上限。只服务「当前这首」，四个文件足够两台设备来回切。 */
    private static final int ART_KEEP = 4;

    /** 已落盘的「曲目稳定键 → 绝对路径」。只增不减，键的数量以会话曲目数为上界。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> ART_PATHS =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * 封面字节就位（后台线程）。只处理当前正在播的那首：队列里的歌把字节留在内存就好，
     * 全 tee 下来是无谓的磁盘 IO。
     */
    static void offerCoverArt(Music track, byte[] bytes) {
        try {
            if (track == null || bytes == null || bytes.length == 0L) return;
            if (track != CloudMusic.currentlyPlaying) return;
            if (!enabled()) return;
            String key = track.getStableKey();
            String name = "pub-art-" + stableHash(key) + extensionOf(bytes);
            File target = new File(ConfigPaths.SMTC_DIR, name);
            if (!target.isFile() || target.length() != bytes.length) {
                Files.write(target.toPath(), bytes);
                pruneArt();
            }
            ART_PATHS.put(key, target.getAbsolutePath());
        } catch (Throwable failure) {
            // tee 失败只影响发布浮窗有没有封面，一句话进日志就够。
            System.out.println("[Music/SMTC] 发布封面落盘失败: " + failure);
        }
    }

    /** 发布端要的封面路径；没有（还没下好、本地没嵌图）返回 {@code null}。 */
    private static String coverPathFor(Music track) {
        if (track == null) return null;
        // 本地音乐的封面本来就是磁盘上的文件（LocalMusicLibrary 提取的 PNG），直接给路径。
        if (track.isLocal()) {
            try {
                File file = LocalMusicLibrary.coverFileOf(track);
                if (file != null && file.isFile()) return file.getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        return ART_PATHS.get(track.getStableKey());
    }

    private static void pruneArt() {
        try {
            File[] files = ConfigPaths.SMTC_DIR.listFiles(
                    (dir, name) -> name.startsWith("pub-art-"));
            if (files == null || files.length <= ART_KEEP) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - ART_KEEP; i++) {
                try { Files.deleteIfExists(files[i].toPath()); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 清不掉旧文件不影响这一首的封面。
        }
    }

    /** 按文件头认图片类型，助手侧 StorageFile 不挑后缀，但认得对的文件利于排查。 */
    private static String extensionOf(byte[] bytes) {
        if (bytes.length > 3 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) return ".png";
        if (bytes.length > 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return ".jpg";
        return ".png";
    }

    /** 与 {@code ExternalPlayback.stableLong} 同族的 FNV-1a，文件名要稳、要短。 */
    private static String stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }

    // ── 配置 ───────────────────────────────────────────────────────────────────

    private static void ensureConfigAsync() {
        if (configLoaded) return;
        synchronized (CONFIG_LOCK) {
            if (configLoaded) return;
            configLoaded = true;
        }
        MultiThreadingUtil.runAsync(() -> {
            try {
                // 启动参数优先：排查问题时 -Dmuonium.smtc.publish=false 一条就够，不用改文件。
                String overrideValue = System.getProperty("muonium.smtc.publish");
                if (overrideValue != null && !overrideValue.trim().isEmpty()) {
                    enabled = Boolean.parseBoolean(overrideValue.trim());
                    return;
                }
                File file = ConfigPaths.SMTC_PUBLISH;
                if (file.isFile()) {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                    if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
                }
            } catch (Throwable failure) {
                // 读不出配置就按默认（开）：发布的失败半径是「浮窗里多一条会话」。
                System.out.println("[Music/SMTC] 发布配置读取失败，按默认开: " + failure);
            }
        });
    }

    /** 只在持有 {@link #CONFIG_LOCK} 的后台线程里调。 */
    private static void writeConfig(boolean value) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("enabled", value);
            Files.write(ConfigPaths.SMTC_PUBLISH.toPath(),
                    json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            System.err.println("[Music/SMTC] 发布配置写盘失败: " + failure);
        }
    }
}
