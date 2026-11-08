package com.muoniumplayer.core.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.codec.PresetJson;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.settings.ConfigPaths;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Main-thread local storage. Bounded reads, confined paths and same-directory atomic writes. */
public final class PresetRepository {
    public static final String EXTENSION = ".mpreset.json";
    static final String BUILTIN_DIR = "/muonium/presets/builtin/";
    static final int BACKUP_KEEP = 10;
    private static final long MAX_FILE_BYTES = PresetLimits.MAX_JSON_CHARS * 4L;
    private final File userDir, backupDir;
    private final Function<String, InputStream> resources;
    private List<StylePreset> builtins;
    private int backupCount = -1;
    private long lastBackupTime;
    public PresetRepository(File userDir, File backupDir, Function<String, InputStream> resources) {
        if (userDir == null || backupDir == null || resources == null) throw new IllegalArgumentException("preset directories/resources");
        this.userDir = userDir; this.backupDir = backupDir; this.resources = resources;
    }
    public static PresetRepository standard() {
        return new PresetRepository(ConfigPaths.PRESETS_DIR, ConfigPaths.PRESETS_BACKUP_DIR,
                name -> PresetRepository.class.getResourceAsStream(BUILTIN_DIR + name));
    }
    public File userDir() { return userDir; }
    public synchronized List<StylePreset> builtins() {
        if (builtins == null) {
            List<StylePreset> list = new ArrayList<StylePreset>();
            try {
                String index = readResource("index.json");
                JsonElement parsed = index == null ? null : JsonParser.parseString(index);
                JsonArray files = parsed != null && parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
                if (files.size() > PresetLimits.MAX_KEYS_PER_DOMAIN) throw new IOException("内置索引过大");
                for (JsonElement file : files) {
                    if (file == null || !file.isJsonPrimitive() || !file.getAsJsonPrimitive().isString()) continue;
                    String name = file.getAsString();
                    if (!bareName(name) || !name.endsWith(EXTENSION)) continue;
                    try {
                        String text = readResource(name);
                        if (text != null) list.add(PresetJson.parse(text, StylePreset.Source.BUILTIN, null));
                    } catch (PresetFormatException broken) { log("内置预设解析失败：" + name); }
                }
            } catch (Throwable failure) { log("内置预设读取失败：" + failure.getClass().getSimpleName()); }
            builtins = Collections.unmodifiableList(list);
        }
        return builtins;
    }
    private String readResource(String name) throws IOException {
        if (!bareName(name)) throw new IOException("资源名无效");
        InputStream stream = resources.apply(name);
        if (stream == null) return null;
        try (InputStream in = stream) { return new String(readAll(in, MAX_FILE_BYTES), StandardCharsets.UTF_8); }
    }
    public List<StylePreset> userPresets() {
        List<StylePreset> presets = new ArrayList<StylePreset>();
        for (File file : list(userDir)) {
            StylePreset preset = read(file, StylePreset.Source.USER);
            if (preset != null) presets.add(preset);
        }
        return presets;
    }
    public StylePreset saveUser(StylePreset preset) throws IOException {
        File target = uniqueFile(userDir, slug(preset.meta().name()));
        StylePreset located = preset.located(StylePreset.Source.USER, target);
        writeAtomically(target, PresetJson.write(located, true));
        return located;
    }
    public boolean delete(StylePreset preset) {
        if (preset == null || preset.source() != StylePreset.Source.USER || !owned(preset.file(), userDir)) return false;
        return preset.file().delete();
    }
    public StylePreset saveBackup(StylePreset backup) throws IOException {
        // Monotonic filenames even if the clock moves backwards or an earlier process left future-dated backups.
        List<File> existing = listBackups();
        if (!existing.isEmpty()) {
            String latest = existing.get(0).getName();
            try { lastBackupTime = Math.max(lastBackupTime, new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                    .parse(latest.substring(7, 26)).getTime()); } catch (RuntimeException | java.text.ParseException ignored) {}
        }
        long stampTime = Math.max(System.currentTimeMillis(), lastBackupTime + 1);
        lastBackupTime = stampTime;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date(stampTime));
        File target = uniqueFile(backupDir, "backup-" + stamp + "-000");
        StylePreset located = backup.located(StylePreset.Source.BACKUP, target);
        com.google.gson.JsonObject body = PresetJson.toJson(located);
        if (located.localState() != null) body.add("localState", located.localState());
        writeAtomically(target, body.toString() + "\n");
        backupCount = -1;
        prune();
        return located;
    }
    public StylePreset latestBackup() {
        for (File file : listBackups()) {
            StylePreset preset = read(file, StylePreset.Source.BACKUP);
            if (preset != null) return preset;
        }
        return null;
    }
    public void removeBackup(StylePreset backup) {
        if (backup != null && backup.source() == StylePreset.Source.BACKUP && owned(backup.file(), backupDir)) backup.file().delete();
        backupCount = -1;
    }
    public int backupCount() {
        if (backupCount < 0) backupCount = listBackups().size();
        return backupCount;
    }
    private void prune() throws IOException {
        List<File> files = listBackups();
        for (int i = BACKUP_KEEP; i < files.size(); i++)
            if (!files.get(i).delete() && files.get(i).exists()) throw new IOException("旧备份清理失败");
    }
    private List<File> listBackups() {
        List<File> files = list(backupDir);
        files.sort((a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }
    private static List<File> list(File dir) {
        File[] files = !dir.isDirectory() ? null : dir.listFiles(file -> owned(file, dir)
                && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS));
        if (files == null) return new ArrayList<File>();
        List<File> sorted = new ArrayList<File>(Arrays.asList(files));
        sorted.sort((a, b) -> { int time = Long.compare(b.lastModified(), a.lastModified());
            return time != 0 ? time : b.getName().compareTo(a.getName()); });
        return sorted;
    }
    private static StylePreset read(File file, StylePreset.Source source) {
        try (InputStream in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            String text = new String(readAll(in, MAX_FILE_BYTES), StandardCharsets.UTF_8);
            StylePreset preset = PresetJson.parse(text, source, file);
            if (source == StylePreset.Source.BACKUP) {
                com.google.gson.JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                JsonElement local = root.get("localState");
                if (local != null && local.isJsonObject()) preset = preset.withLocalState(local.getAsJsonObject());
            }
            return preset;
        } catch (Throwable failure) { log("预设读取失败：" + file.getName() + "（" + failure.getClass().getSimpleName() + "）"); }
        return null;
    }
    static String slug(String name) {
        String value = name == null ? "" : name.trim();
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), 96));
        for (int i = 0; i < value.length() && cleaned.length() < 96; i++) {
            char c = value.charAt(i);
            boolean illegal = c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20 || Character.getType(c) == Character.FORMAT;
            cleaned.append(illegal ? '_' : c);
        }
        String result = cleaned.toString().trim();
        while (result.startsWith(".")) result = result.substring(1);
        while (result.endsWith(".") || result.endsWith(" ")) result = result.substring(0, result.length() - 1);
        if (result.isEmpty()) result = "预设";
        result = PresetJson.clean(result, 48);
        String device = result.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (device.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) result = "_" + result;
        return result;
    }
    private static File uniqueFile(File dir, String base) throws IOException {
        Files.createDirectories(dir.toPath());
        for (int n = 1; n < 1000; n++) {
            File candidate = new File(dir, base + (n == 1 ? "" : " (" + n + ")") + EXTENSION);
            if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!isInside(candidate, dir)) throw new IOException("预设文件越出目录");
                return candidate;
            }
        }
        throw new IOException("同名预设太多，换个名字再试");
    }
    private static boolean bareName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0
                && name.indexOf('\\') < 0 && name.indexOf(':') < 0 && name.indexOf('\0') < 0;
    }
    private static boolean owned(File file, File dir) {
        return file != null && file.getName().endsWith(EXTENSION) && !Files.isSymbolicLink(file.toPath()) && isInside(file, dir);
    }
    private static boolean isInside(File file, File dir) {
        try { File parent = file.getCanonicalFile().getParentFile(); return parent != null && parent.equals(dir.getCanonicalFile()); }
        catch (IOException | SecurityException failure) { return false; }
    }
    /** Unique temp, fsync, atomic replacement when supported; a failed write never truncates the old file. */
    static void writeAtomically(File target, String text) throws IOException {
        if (text == null || text.length() > PresetLimits.MAX_JSON_CHARS) throw new IOException("预设内容过大");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("预设文件过大");
        Path path = target.toPath().toAbsolutePath(), parent = path.getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(path)) throw new IOException("预设目标不能是符号链接");
        Path temp = Files.createTempFile(parent, ".mpreset-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException fallback) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n == 0) { int one = in.read(); if (one == -1) break;
                if (out.size() >= limit) throw new IOException("内容过大"); out.write(one); continue; }
            if ((long) out.size() + n > limit) throw new IOException("内容过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    private static void log(String message) { System.out.println("[MuoniumPlayer/Preset] " + message); }
}
