package com.muoniumplayer.core.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.FontAssetDownload;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegNativeAssets;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegRuntime;
import com.muoniumplayer.core.ncm.lxsource.ClosureCompilerDownload;
import com.muoniumplayer.automix.model.FoliaModelManager;
import com.muoniumplayer.core.ncm.music.QishuiCompanionDownload;
import com.muoniumplayer.core.ncm.music.ZstdNativeDownload;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.network.HttpDownloadRace;
import com.muoniumplayer.render.skija.SkijaNatives;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only inventory and user actions for managed runtime components. */
public final class DownloadCatalog {
    public enum Status {
        INSTALLED,
        DOWNLOADING,
        WAITING_NETWORK,
        INSTALLING,
        RESTART_REQUIRED,
        NOT_INSTALLED,
        FAILED,
        UNSUPPORTED
    }

    public enum Group {
        GRAPHICS,
        FONTS,
        AUDIO,
        AUTOMIX,
        TOOLS
    }

    public static final class Item {
        private final String id;
        private final String name;
        private final String detail;
        private final String location;
        private final Group group;
        private final Status status;
        private final int progress;
        private final String message;
        private final boolean optional;
        private final Runnable action;
        private final String actionLabel;

        private Item(String id, String name, String detail, String location, Group group,
                     Status status, int progress, String message, boolean optional,
                     String actionLabel, Runnable action) {
            this.id = id;
            this.name = name;
            this.detail = detail;
            this.location = location;
            this.group = group;
            this.status = status;
            this.progress = progress;
            this.message = message == null ? "" : message;
            this.optional = optional;
            this.actionLabel = actionLabel == null ? "" : actionLabel;
            this.action = action;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String detail() { return detail; }
        public String location() { return location; }
        public Group group() { return group; }
        public Status status() { return status; }
        public int progress() { return progress; }
        public String message() { return message; }
        public boolean optional() { return optional; }
        public boolean canStart() { return action != null && !actionLabel.isEmpty(); }
        public String actionLabel() { return actionLabel; }
        public void start() { if (action != null) action.run(); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean startupChecked;
    private static boolean showEntryThisSession;

    private DownloadCatalog() {
    }

    /** Call once at extension initialization to freeze the first-run visibility decision. */
    public static void beginSession() {
        File file = ConfigPaths.DOWNLOADS;
        startupChecked = true;
        if (!file.isFile()) {
            showEntryThisSession = false;
            return;
        }
        try {
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            showEntryThisSession = root != null && root.has("ready") && root.get("ready").getAsBoolean();
        } catch (Exception ignored) {
            showEntryThisSession = false;
        }
    }

    /** Called only after the managed initialization has completed, not from the Skija rescue page. */
    public static void markStartupReady() {
        File file = ConfigPaths.DOWNLOADS;
        if (file.isFile()) return;
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("ready", true);
        try {
            JsonConfigStorage.writeObject(file, GSON, root);
        } catch (Exception ignored) {
        }
    }

    public static boolean shouldShowEntry() {
        return startupChecked && showEntryThisSession;
    }

    public static List<Item> snapshot() {
        List<Item> items = new ArrayList<Item>();
        addSkija(items);
        addFonts(items);
        addQishui(items);
        addZstd(items);
        addClosure(items);
        addFfmpegNative(items);
        addFolia(items);
        return Collections.unmodifiableList(items);
    }

    public static int activeTasks() {
        return HttpDownloadRace.networkState().activeTasks();
    }

    public static int waitingTasks() {
        return HttpDownloadRace.networkState().waitingTasks();
    }

    public static String lastNetworkFailure() {
        return HttpDownloadRace.networkState().lastFailure();
    }

    private static void addSkija(List<Item> items) {
        SkijaNatives.Status nativeStatus = SkijaNatives.status();
        SkijaNatives.DownloadSnapshot state = SkijaNatives.companionDownloadSnapshot();
        Status status;
        String message = state.detail();
        if (nativeStatus.loaded()) status = Status.INSTALLED;
        else if (SkijaNatives.companionRestartPending()) status = Status.RESTART_REQUIRED;
        else if ("DOWNLOADING".equals(state.phase())) {
            status = HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING;
        } else if ("INSTALLING".equals(state.phase())) status = Status.INSTALLING;
        else if ("FAILED".equals(state.phase())) status = Status.FAILED;
        else status = nativeStatus.nativeFound() ? Status.INSTALLED : Status.NOT_INSTALLED;
        items.add(item("skija", "渲染原生库", nativeStatus.expectedPackageName() + " · Skija "
                        + nativeStatus.pinnedVersion(), nativeStatus.expectedDirectory(), Group.GRAPHICS,
                status, state.percent(), message, false, "", null));
    }

    private static void addFonts(List<Item> items) {
        boolean complete = FontAssetDownload.isCacheComplete();
        boolean downloading = FontAssetDownload.isDownloading();
        Status status = complete ? Status.INSTALLED : downloading
                ? (HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING)
                : FontAssetDownload.hasFailed() ? Status.FAILED : Status.NOT_INSTALLED;
        items.add(item("fonts", "完整字体", "苹方 + Noto Sans KR · 4 个字体文件",
                FontAssetDownload.fontCacheDir().getAbsolutePath(), Group.FONTS, status,
                FontAssetDownload.catalogProgress(), FontAssetDownload.catalogFailure(), false, "", null));
    }

    private static void addQishui(List<Item> items) {
        if (!QishuiCompanionDownload.supportedPlatform()) {
            items.add(item("qishui", "汽水签名后端", "Windows x64 专用", "", Group.AUDIO,
                    Status.UNSUPPORTED, -1, "当前平台不支持", true, "", null));
            return;
        }
        String phase = QishuiCompanionDownload.catalogPhase();
        Status status = QishuiCompanionDownload.catalogIsInstalled() ? Status.INSTALLED
                : "DOWNLOADING".equals(phase)
                ? (HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING)
                : "INSTALLING".equals(phase) ? Status.INSTALLING
                : "FAILED".equals(phase) ? Status.FAILED : Status.NOT_INSTALLED;
        items.add(item("qishui", "汽水签名后端", "完整取流需要 · 41.7 MB",
                QishuiCompanionDownload.installRoot().getAbsolutePath(), Group.AUDIO,
                status, QishuiCompanionDownload.catalogProgress(), QishuiCompanionDownload.catalogFailure(), true,
                status == Status.NOT_INSTALLED || status == Status.FAILED ? "安装" : "",
                status == Status.NOT_INSTALLED || status == Status.FAILED
                        ? QishuiCompanionDownload::requestInstall : null));
    }

    private static void addZstd(List<Item> items) {
        String phase = ZstdNativeDownload.catalogPhase();
        Status status = ZstdNativeDownload.isDownloaded() ? Status.INSTALLED
                : "DOWNLOADING".equals(phase)
                ? (HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING)
                : "FAILED".equals(phase) ? Status.FAILED : Status.NOT_INSTALLED;
        items.add(item("zstd", "网易云打卡压缩组件", "zstd-jni slim · 登录网易云后按需下载",
                ZstdNativeDownload.cachedFile().getAbsolutePath(), Group.AUDIO, status,
                -1, ZstdNativeDownload.catalogFailure(), true, "", null));
    }

    private static void addClosure(List<Item> items) {
        String phase = ClosureCompilerDownload.catalogPhase();
        Status status = ClosureCompilerDownload.isDownloaded() ? Status.INSTALLED
                : "DOWNLOADING".equals(phase)
                ? (HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING)
                : "FAILED".equals(phase) ? Status.FAILED : Status.NOT_INSTALLED;
        String failure = ClosureCompilerDownload.catalogFailure();
        items.add(item("closure", "LX 脚本转译器", "Closure Compiler · 自定义源可选",
                ClosureCompilerDownload.cachedFile().getAbsolutePath(), Group.TOOLS, status,
                -1, failure, true,
                status == Status.NOT_INSTALLED || status == Status.FAILED ? "安装" : "",
                status == Status.NOT_INSTALLED || status == Status.FAILED
                        ? ClosureCompilerDownload::ensureDownload : null));
    }

    private static void addFfmpegNative(List<Item> items) {
        FfmpegNativeAssets.Snapshot state = FfmpegNativeAssets.snapshot();
        Status status;
        boolean embedded = FfmpegRuntime.hasEmbeddedNative();
        if (FfmpegRuntime.linkageFailure() != null) status = Status.FAILED;
        else if (embedded || FfmpegRuntime.isLoaded() || state.phase() == FfmpegNativeAssets.Phase.READY) status = Status.INSTALLED;
        else status = switch (state.phase()) {
            case DOWNLOADING -> HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING;
            case INSTALLING -> Status.INSTALLING;
            case FAILED -> Status.FAILED;
            case UNSUPPORTED -> Status.UNSUPPORTED;
            default -> Status.NOT_INSTALLED;
        };
        boolean canPrepare = !embedded && FfmpegRuntime.linkageFailure() == null
                && (status == Status.NOT_INSTALLED || status == Status.FAILED);
        String message = FfmpegRuntime.linkageFailure() == null ? state.detail() : "原生音频初始化失败，修复依赖后请重启游戏";
        String location = embedded ? "随 MOD 内置" : ConfigPaths.FFMPEG_NATIVE_ROOT.getAbsolutePath();
        items.add(item("ffmpeg-native", "音频解码引擎", "FFmpeg 8.1.2 · JavaCPP · "
                        + (embedded ? "Windows 精简版" : "首次使用按需下载"), location, Group.AUDIO,
                status, state.percent(), message, false, canPrepare ? "下载/修复" : "",
                canPrepare ? FfmpegNativeAssets::retryAsync : null));
    }

    private static void addFolia(List<Item> items) {
        for (FoliaModelManager.Kind kind : FoliaModelManager.Kind.values()) {
            if (kind == FoliaModelManager.Kind.RUNTIME && FoliaModelManager.isAndroidPlatform()) continue;
            if (kind == FoliaModelManager.Kind.ONNX_RUNTIME && !FoliaModelManager.isAndroidPlatform()) continue;
            boolean installed = FoliaModelManager.isInstalled(kind);
            boolean downloading = FoliaModelManager.isDownloading(kind);
            boolean installing = FoliaModelManager.isInstalling(kind);
            Status status = installed ? Status.INSTALLED : downloading
                    ? (HttpDownloadRace.networkState().waitingTasks() > 0 ? Status.WAITING_NETWORK : Status.DOWNLOADING)
                    : installing ? Status.INSTALLING : Status.NOT_INSTALLED;
            String title = kind == FoliaModelManager.Kind.BEAT_THIS ? "Beat This!"
                    : kind == FoliaModelManager.Kind.HTDEMUCS ? "HTDemucs" : FoliaModelManager.kindLabel(kind);
            items.add(item("folia-" + kind.name().toLowerCase(java.util.Locale.ROOT),
                    "Folia · " + title, "智能混音 · 可选模型/运行组件",
                    FoliaModelManager.path(kind).getAbsolutePath(), Group.AUTOMIX, status,
                    FoliaModelManager.progressPercent(kind), "", true,
                    installed || downloading || installing ? "" : "安装",
                    installed || downloading || installing
                            ? null : () -> FoliaModelManager.downloadAsync(kind, null)));
        }
    }

    private static Item item(String id, String name, String detail, String location, Group group,
                             Status status, int progress, String message, boolean optional,
                             String actionLabel, Runnable action) {
        return new Item(id, name, detail, location, group, status, progress, message,
                optional, actionLabel, action);
    }
}
