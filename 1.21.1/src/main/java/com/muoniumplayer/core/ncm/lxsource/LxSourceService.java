package com.muoniumplayer.core.ncm.lxsource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 自定义源对宿主的唯一入口。
 *
 * <p>只保留一个运行时：上游也是「同时只启用一个自定义源」（{@code common.apiSource} 只有一个值）。
 * 同时跑多个会让「这首歌到底是谁解析的」变成不可回答的问题，出问题时无从下手。</p>
 *
 * <p>所有 {@code resolveXxx} 在「没启用 / 没就绪 / 脚本没声明这个源或动作 / 脚本失败」时
 * 一律返回 null，让调用方安静地回退到内置解析链。这是刻意与上游不同的一点：上游在自定义源
 * 失败时就是播放失败，而这里自定义源只是内置链路前面多出来的一环。</p>
 */
public final class LxSourceService {

    /** 首次点歌时最多等脚本初始化多久。等不到就这一首走内置链路，下一首自然就好了。 */
    private static final long WAIT_READY_MS = 8_000L;

    private static final Object LOCK = new Object();
    private static volatile LxRuntime runtime;

    private LxSourceService() {
    }

    public static final class Resolved {
        public final String url;
        public final String format;
        /** 实际使用的 LX 音质档位，展示用。 */
        public final String quality;
        public final String sourceLabel;

        Resolved(String url, String format, String quality, String sourceLabel) {
            this.url = url;
            this.format = format;
            this.quality = quality;
            this.sourceLabel = sourceLabel;
        }
    }

    /** 歌词四件套，字段可能为 null。 */
    public static final class Lyrics {
        public final String lyric;
        public final String tlyric;
        public final String rlyric;
        public final String lxlyric;

        Lyrics(String lyric, String tlyric, String rlyric, String lxlyric) {
            this.lyric = lyric;
            this.tlyric = tlyric;
            this.rlyric = rlyric;
            this.lxlyric = lxlyric;
        }
    }

    // ════════════════════════════ 生命周期 ════════════════════════════

    /** 游戏启动时调一次。没有启用任何脚本时什么都不做（不会去碰 Rhino 与 Closure）。 */
    public static void init() {
        if (LxSourceStore.getActiveId().isEmpty()) return;
        ensureStarted();
    }

    /** 切换启用的脚本；传空字符串表示关闭。 */
    public static void select(String scriptId) {
        LxSourceStore.setActiveId(scriptId == null ? "" : scriptId);
        reload();
    }

    /** 停掉当前运行时并按存档重新起一个。 */
    public static void reload() {
        synchronized (LOCK) {
            LxRuntime previous = runtime;
            runtime = null;
            if (previous != null) previous.shutdown();
        }
        ensureStarted();
    }

    private static void ensureStarted() {
        synchronized (LOCK) {
            if (runtime != null) return;
            LxSourceStore.Entry entry = LxSourceStore.getActive();
            if (entry == null) return;
            String source = entry.getSource();
            if (source.isEmpty()) {
                System.err.println("[LX] 启用的脚本正文读不出来，已忽略：" + entry.id);
                return;
            }
            runtime = LxRuntime.start(entry.id, entry.info, source);
        }
    }

    public static LxRuntime getRuntime() {
        return runtime;
    }

    public static boolean isEnabled() {
        return !LxSourceStore.getActiveId().isEmpty();
    }

    /** 面板上的一行状态。 */
    public static String statusLabel() {
        if (!isEnabled()) return "";
        LxRuntime current = runtime;
        if (current == null) return "未加载";
        switch (current.getState()) {
            case READY:
                return "已就绪 · " + String.join("/", current.getSources().keySet());
            case LOADING:
                return "正在加载脚本…";
            case FAILED:
                return "加载失败：" + current.getError();
            default:
                return "已停止";
        }
    }

    /**
     * MuoniumPlayer 的平台 → LX 的源 key。
     *
     * @param platform    {@code MusicPlatform} 的枚举名
     * @param gdPlatform  平台为 GD 时的聚合子平台（netease/kuwo/tencent/...），其余情况传空
     * @return null 表示这个平台在 LX 里没有对应的源，自定义源管不了它
     */
    public static String sourceKeyFor(String platform, String gdPlatform) {
        String name = platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);
        switch (name) {
            case "NETEASE":
                return "wy";
            case "QQ":
                return "tx";
            case "KUGOU":
                return "kg";
            case "GD":
                String key = gdPlatform == null ? "" : gdPlatform.trim().toLowerCase(Locale.ROOT);
                if ("netease".equals(key)) return "wy";
                if ("kuwo".equals(key)) return "kw";
                if ("tencent".equals(key)) return "tx";
                if ("kugou".equals(key)) return "kg";
                if ("migu".equals(key)) return "mg";
                // joox / bilibili / tidal / spotify 这些 LX 没有对应的源。
                return null;
            default:
                return null;
        }
    }

    // ════════════════════════════ 解析 ════════════════════════════

    /** @return null 表示这一首不该由自定义源出面（调用方继续走内置链路） */
    public static Resolved resolveMusicUrl(LxTrack track, String muQuality) {
        LxRuntime current = ready(track, "musicUrl");
        if (current == null) return null;
        LxRuntime.SourceDecl decl = current.getSources().get(track.getSource());
        String quality = LxQuality.pick(LxQuality.fromMuQuality(muQuality), decl.qualitys);
        if (quality == null) return null;
        String info = "{\"type\":\"" + quality + "\",\"musicInfo\":" + track.toMusicInfoJson() + "}";
        try {
            JsonObject payload = JsonParser.parseString(
                    current.request(track.getSource(), "musicUrl", info)).getAsJsonObject();
            String url = payload.has("url") ? payload.get("url").getAsString() : "";
            if (url.isEmpty()) return null;
            return new Resolved(url, LxQuality.formatFor(url, quality), quality,
                    current.getInfo().getName().isEmpty() ? "自定义源" : current.getInfo().getName());
        } catch (LxSourceException failure) {
            System.err.println("[LX] musicUrl 失败（" + track.getSource() + "/" + quality + "）："
                    + failure.getMessage());
            return null;
        } catch (Throwable broken) {
            System.err.println("[LX] musicUrl 返回值无法解析：" + broken);
            return null;
        }
    }

    public static Lyrics resolveLyric(LxTrack track) {
        LxRuntime current = ready(track, "lyric");
        if (current == null) return null;
        try {
            JsonObject payload = JsonParser.parseString(current.request(track.getSource(), "lyric",
                    "{\"musicInfo\":" + track.toMusicInfoJson() + "}")).getAsJsonObject();
            return new Lyrics(optional(payload, "lyric"), optional(payload, "tlyric"),
                    optional(payload, "rlyric"), optional(payload, "lxlyric"));
        } catch (LxSourceException failure) {
            System.err.println("[LX] lyric 失败：" + failure.getMessage());
            return null;
        } catch (Throwable broken) {
            return null;
        }
    }

    public static String resolvePic(LxTrack track) {
        LxRuntime current = ready(track, "pic");
        if (current == null) return null;
        try {
            JsonObject payload = JsonParser.parseString(current.request(track.getSource(), "pic",
                    "{\"musicInfo\":" + track.toMusicInfoJson() + "}")).getAsJsonObject();
            String url = optional(payload, "url");
            return url == null || url.isEmpty() ? null : url;
        } catch (LxSourceException failure) {
            System.err.println("[LX] pic 失败：" + failure.getMessage());
            return null;
        } catch (Throwable broken) {
            return null;
        }
    }

    /** 四道门：启用了、起来了、声明了这个源、声明了这个动作。 */
    private static LxRuntime ready(LxTrack track, String action) {
        if (track == null || track.getSource().isEmpty()) return null;
        if (!isEnabled()) return null;
        ensureStarted();
        LxRuntime current = runtime;
        if (current == null) return null;
        if (current.getState() == LxRuntime.State.LOADING && !current.awaitReady(WAIT_READY_MS)) return null;
        if (current.getState() != LxRuntime.State.READY) return null;
        LxRuntime.SourceDecl decl = current.getSources().get(track.getSource());
        return decl != null && decl.supports(action) ? current : null;
    }

    // ════════════════════════════ 面板要用的状态与操作 ════════════════════════════

    /** 单个脚本的正文上限。官方参考源约 18 KB，打包过的第三方源也在几十 KB 量级。 */
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;

    /** 上一次操作的结果，显示在面板底部那一行。 */
    private static volatile String notice = "";
    private static volatile boolean noticeBad;
    private static volatile boolean busy;
    /**
     * 列表版本号，只在<b>行数变了</b>（导入 / 删除）时递增。
     *
     * <p>选中态、更新提示开关、运行状态全都是面板每帧读的供应器，不需要重建整页 —— 而重建整页
     * 会连带重置弹窗的入场动画，于是每点一下都要看它重新淡入一遍。</p>
     */
    private static volatile int revision;

    public static String getNotice() {
        return notice;
    }

    public static boolean isNoticeBad() {
        return noticeBad;
    }

    /** 正在下载或读文件。面板据此把按钮换成等待态。 */
    public static boolean isBusy() {
        return busy;
    }

    public static int getRevision() {
        return revision;
    }

    /**
     * 打开系统文件选择框挑一个 .js 脚本。
     *
     * <p>反射调 LWJGL 的 tinyfd，理由与 {@code UiFontLibrary#browseForFile()} 相同：它随
     * Minecraft 一起分发，但版本之间未必都在编译期可见，而这个功能不该成为跨版本编译的
     * 风险点。拿不到就提示改用剪贴板那条路。</p>
     *
     * <p>单开一个线程：原生对话框是模态的，在渲染线程上弹会把整个游戏冻在那一帧。</p>
     */
    public static void browseForFile() {
        if (busy) return;
        busy = true;
        notice = "已打开系统文件选择框，请挑一个 .js 脚本";
        noticeBad = false;
        Thread picker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String picked = nativeOpenDialog();
                    if (picked != null && !picked.trim().isEmpty()) {
                        importFile(new File(picked.trim()));
                    } else if (!noticeBad) {
                        notice = "没有选择脚本文件";
                    }
                } finally {
                    busy = false;
                }
            }
        }, "MuoniumPlayer-LxSourcePicker");
        picker.setDaemon(true);
        picker.start();
    }

    private static String nativeOpenDialog() {
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
            java.lang.reflect.Method open = dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointerBuffer,
                    CharSequence.class, boolean.class);
            Object result = open.invoke(null, "选择 LX Music 自定义源脚本", "", null, null, Boolean.FALSE);
            return result == null ? null : result.toString();
        } catch (Throwable unavailable) {
            fail("这个版本打不开系统文件选择框，请复制脚本正文或链接后用「从剪贴板导入」");
            return null;
        }
    }

    /** 同步导入一个本地文件。已经在后台线程上时才该直接调它。 */
    public static void importFile(File file) {
        if (file == null || !file.isFile()) {
            fail("找不到这个文件");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > MAX_SCRIPT_BYTES) {
                fail("这个文件有 " + (bytes.length / 1024) + " KB，不像是自定义源脚本");
                return;
            }
            adopt(new String(bytes, StandardCharsets.UTF_8), file.getName());
        } catch (Throwable broken) {
            fail("读文件失败：" + describe(broken));
        }
    }
    /**
     * 从一段文本导入：{@code http(s)} 链接就先下载，否则当成脚本正文。
     *
     * <p>合成一个入口而不是拆成两个按钮：用户复制过来的要么是一个 raw 链接、要么是整段脚本，
     * 让程序看一眼比让用户先选一次更省事。</p>
     */
    public static void importFromText(final String text) {
        final String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            fail("剪贴板是空的：先复制脚本正文，或它的下载链接");
            return;
        }
        if (busy) return;
        busy = true;
        final boolean remote = looksLikeUrl(trimmed);
        notice = remote ? "正在下载脚本…" : "正在导入脚本…";
        noticeBad = false;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!remote) {
                        adopt(trimmed, "剪贴板");
                        return;
                    }
                    String downloaded = download(trimmed);
                    if (downloaded != null) adopt(downloaded, trimmed);
                } finally {
                    busy = false;
                }
            }
        }, "MuoniumPlayer-LxSourceImport");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean looksLikeUrl(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        // 带换行的一定是正文：脚本正文里也可能出现 http 开头的一行。
        return (lower.startsWith("http://") || lower.startsWith("https://")) && text.indexOf('\n') < 0;
    }

    /**
     * 下载脚本正文。
     *
     * <p>刻意<b>跟随</b>重定向，与 {@link LxHttp} 相反：那边不跟随是协议要求（脚本靠读 302 的
     * location 取直链），而这里下载的是一个文件，raw.githubusercontent 之类的镜像几乎都要跳一次。</p>
     */
    private static String download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "MuoniumPlayer/LxSource");
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                fail("下载失败：服务器返回 " + status);
                return null;
            }
            InputStream stream = connection.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    out.write(chunk, 0, read);
                    if (out.size() > MAX_SCRIPT_BYTES) {
                        fail("这个链接返回的内容超过 " + (MAX_SCRIPT_BYTES / 1024 / 1024) + " MB，不像是脚本");
                        return null;
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                stream.close();
            }
        } catch (Throwable broken) {
            fail("下载失败：" + describe(broken));
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
    /** 导入成功就立刻启用：刚导进来的那一个，十有八九就是想马上用的那一个。 */
    private static void adopt(String source, String origin) {
        try {
            LxSourceStore.Entry entry = LxSourceStore.importScript(source);
            select(entry.id);
            revision++;
            notice = "已导入并启用「" + displayName(entry) + "」";
            noticeBad = false;
        } catch (IllegalArgumentException rejected) {
            fail(rejected.getMessage());
        } catch (Throwable broken) {
            fail("导入失败（" + origin + "）：" + describe(broken));
        }
    }

    /** 点一行：已经在用的就关掉，否则换成它。 */
    public static void toggle(String scriptId) {
        if (scriptId == null || scriptId.isEmpty()) return;
        boolean turnOff = scriptId.equals(LxSourceStore.getActiveId());
        LxSourceStore.Entry entry = LxSourceStore.find(scriptId);
        select(turnOff ? "" : scriptId);
        notice = turnOff
                ? "已关闭自定义源，回到内置解析链路"
                : "已启用「" + (entry == null ? scriptId : displayName(entry)) + "」，正在加载…";
        noticeBad = false;
    }

    public static void removeScript(String scriptId) {
        LxSourceStore.Entry entry = LxSourceStore.find(scriptId);
        if (entry == null) return;
        boolean wasActive = scriptId.equals(LxSourceStore.getActiveId());
        LxSourceStore.remove(scriptId);
        // 删掉的正是在用的那一个：必须顺手把运行时停掉，否则它还在后台替这一首取流。
        if (wasActive) reload();
        revision++;
        notice = "已删除「" + displayName(entry) + "」";
        noticeBad = false;
    }

    /** 手动重开运行时。脚本作者刚发了新版、或者上次加载碰上网络抖动时用。 */
    public static void reloadActive() {
        if (!isEnabled()) {
            fail("还没有启用任何自定义源");
            return;
        }
        reload();
        notice = "正在重新加载脚本…";
        noticeBad = false;
    }

    public static boolean isActive(String scriptId) {
        return scriptId != null && scriptId.equals(LxSourceStore.getActiveId());
    }

    /** 启用中的脚本是否允许弹作者的更新提示。 */
    public static boolean isUpdateAlertAllowed() {
        LxSourceStore.Entry entry = LxSourceStore.getActive();
        return entry != null && entry.allowUpdateAlert;
    }

    public static void flipUpdateAlert() {
        LxSourceStore.Entry entry = LxSourceStore.getActive();
        if (entry == null) return;
        LxSourceStore.setAllowUpdateAlert(entry.id, !entry.allowUpdateAlert);
    }

    /**
     * 把脚本主动发来的更新提示搬到状态行上。由面板每帧调一次。
     *
     * <p>只在面板打开时取，是因为这个提示唯一的去处就是那一行字：上游是弹一个模态窗，
     * 而游戏里在任何时刻弹窗都可能正好盖住玩家在做的事。</p>
     */
    public static void pollUpdateAlert() {
        LxRuntime current = runtime;
        if (current == null) return;
        LxRuntime.UpdateAlert alert = current.consumeUpdateAlert();
        if (alert == null || !isUpdateAlertAllowed()) return;
        notice = "脚本提示更新：" + (alert.log.isEmpty() ? "作者发布了新版本" : alert.log)
                + (alert.updateUrl.isEmpty() ? "" : "（" + alert.updateUrl + "）");
        noticeBad = false;
    }

    /** 脚本 console 的最近几行。脚本自己报错时，这是唯一能给用户看的线索。 */
    public static List<String> getLogs() {
        LxRuntime current = runtime;
        return current == null ? Collections.<String>emptyList() : current.getLogs();
    }

    private static String displayName(LxSourceStore.Entry entry) {
        String name = entry.info.getName();
        return name.isEmpty() ? entry.id : name;
    }

    private static void fail(String message) {
        notice = message == null || message.isEmpty() ? "操作失败" : message;
        noticeBad = true;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    private static String optional(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
}