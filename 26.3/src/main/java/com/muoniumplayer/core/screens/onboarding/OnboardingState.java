package com.muoniumplayer.core.screens.onboarding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 持久化的首次运行状态。与播放器设置分开，使新手引导门控隔离。 */
public final class OnboardingState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static boolean loaded;
    private static boolean completed;
    /**
     * 覆盖层是否已经放过。
     *
     * <p>与 {@code completed} 分开记，是因为这两件事的退出方式不同：覆盖层描完字就收，
     * 玩家可以在引导里随时跳过（那算 completed）；但他在覆盖层上按 ESC 是「连引导一起不要」。
     * 合成一个布尔的话，收掉覆盖层的人永远看不到引导，而放过覆盖层又退出游戏的人
     * 下次进来还得再看一遍描字。</p>
     */
    private static boolean introSeen;

    private OnboardingState() {
    }

    public static boolean isCompleted() {
        synchronized (LOCK) {
            loadLocked();
            return completed;
        }
    }

    public static boolean isIntroSeen() {
        synchronized (LOCK) {
            loadLocked();
            return introSeen;
        }
    }

    /** 覆盖层已经放过（或被玩家点开/ESC 掉），此后不再重复放。 */
    public static void markIntroSeen() {
        synchronized (LOCK) {
            if (introSeen) {
                return;
            }
            introSeen = true;
            loaded = true;
            saveLocked();
        }
    }

    public static void markCompleted() {
        synchronized (LOCK) {
            completed = true;
            introSeen = true;
            loaded = true;
            saveLocked();
        }
    }

    /** 测试/开发钩子；不从普通 UI 暴露。 */
    public static void reset() {
        synchronized (LOCK) {
            completed = false;
            introSeen = false;
            loaded = true;
            saveLocked();
        }
    }

    private static void loadLocked() {
        if (loaded) return;
        loaded = true;
        File file = ConfigPaths.ONBOARDING;
        if (!file.isFile()) return;
        try {
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null) {
                return;
            }
            completed = root.has("completed") && root.get("completed").getAsBoolean();
            // 旧档（schemaVersion 1）没有这个字段：那时根本没有这层描字，看过就等于看过。
            // 按 false 处理会让所有人重看一遍 4 秒动画，按 true 处理才对老用户成立。
            introSeen = !root.has("introSeen") || root.get("introSeen").getAsBoolean();
        } catch (Throwable ignored) {
            completed = false;
            introSeen = false;
        }
    }

    private static void saveLocked() {
        File file = ConfigPaths.ONBOARDING;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("completed", completed);
        root.addProperty("introSeen", introSeen);
        try {
            JsonConfigStorage.writeObject(temp.toFile(), GSON, root);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignoredCleanup) {
            }
        }
    }
}
