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

    private OnboardingState() {
    }

    public static boolean isCompleted() {
        synchronized (LOCK) {
            loadLocked();
            return completed;
        }
    }

    public static void markCompleted() {
        synchronized (LOCK) {
            completed = true;
            loaded = true;
            saveLocked();
        }
    }

    /** 测试/开发钩子；不从普通 UI 暴露。 */
    public static void reset() {
        synchronized (LOCK) {
            completed = false;
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
            completed = root != null && root.has("completed") && root.get("completed").getAsBoolean();
        } catch (Throwable ignored) {
            completed = false;
        }
    }

    private static void saveLocked() {
        File file = ConfigPaths.ONBOARDING;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("completed", completed);
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
