package com.muoniumplayer.core.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 供持久化播放器设置使用的共享 UTF-8 JSON 对象读写。 */
public final class JsonConfigStorage {

    private JsonConfigStorage() {
    }

    public static JsonObject readObject(File file, Gson gson) throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        }
    }

    public static void writeObject(File file, Gson gson, JsonObject object) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        // 先写同目录临时文件再原子替换：进程在中途被杀（GameRestart 的自动重启就是主动杀进程）
        // 时，直接覆盖写会留下截断的 JSON，下次读取按空状态处理，用户丢设置。
        Path target = file.toPath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write(gson.toJson(object));
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
