package com.muoniumplayer.chat;

import java.util.Locale;

/** Runtime facts supplied by each loader shell before shared client initialization. */
public final class ChatRuntime {
    private static volatile String loader = "unknown";
    private static volatile String loaderVersion = "";
    private static volatile String platform = "unknown";
    private static volatile String osVersion = "";
    private static volatile String clientVersion = "unknown";
    private static volatile String minecraftVersion = "unknown";
    private static volatile java.util.function.Consumer<Runnable> mainThread = Runnable::run;
    private static volatile Runnable screenOpener = () -> {};

    private ChatRuntime() {}

    public static void configure(String loaderId, String loaderVer, String osName, String osRelease) {
        loader = sanitize(loaderId, "unknown");
        loaderVersion = sanitize(loaderVer, "");
        platform = normalizePlatform(osName);
        osVersion = sanitize(osRelease, "");
    }

    public static void configureRuntime(String appVersion, String gameVersion) {
        clientVersion = sanitize(appVersion, "unknown");
        minecraftVersion = sanitize(gameVersion, "unknown");
    }

    public static void configureMainThread(java.util.function.Consumer<Runnable> executor) {
        mainThread = executor == null ? Runnable::run : executor;
    }

    public static void configureScreenOpener(Runnable opener) {
        screenOpener = opener == null ? () -> {} : opener;
    }

    public static void executeMain(Runnable task) {
        if (task != null) mainThread.accept(task);
    }

    public static void openScreen() {
        executeMain(screenOpener);
    }

    public static String loader() { return loader; }
    public static String loaderVersion() { return loaderVersion; }
    public static String platform() { return platform; }
    public static String osVersion() { return osVersion; }
    public static String clientVersion() { return clientVersion; }
    public static String minecraftVersion() { return minecraftVersion; }

    private static String normalizePlatform(String value) {
        String os = sanitize(value, "").toLowerCase(Locale.ROOT);
        if (os.contains("android")) return "android";
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("linux")) return "linux";
        return "unknown";
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String clean = value.trim();
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}
