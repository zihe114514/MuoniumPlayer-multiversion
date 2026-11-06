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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 预设的存放：内置（类路径，只读）、玩家的（presets 目录）、套用前备份（presets/backups）。
 *
 * <p>内置预设按 {@code index.json} 列出的文件名逐个读：jar 里没法可靠地列目录。它们直接从 jar 读、
 * 不往配置目录铺副本 —— 风格要随版本迭代修参数，玩家想改就「另存为我的预设」。</p>
 *
 * <p>只在客户端主线程上用（读写都是几 KB 的小文件，与现有设置的同步落盘同一约定）。</p>
 */
public final class PresetRepository {

    public static final String EXTENSION = ".mpreset.json";
    static final String BUILTIN_DIR = "/muonium/presets/builtin/";
    /** 备份保留几份。撤销是逐级往回退的，十级足够，多了只是占目录。 */
    static final int BACKUP_KEEP = 10;
    /** 单个预设文件的大小上限（字节）。 */
    private static final long MAX_FILE_BYTES = PresetLimits.MAX_JSON_CHARS * 4L;

    private final File userDir;
    private final File backupDir;
    private final Function<String, InputStream> resources;
    private List<StylePreset> builtins;
    private int backupCount = -1;

    public PresetRepository(File userDir, File backupDir, Function<String, InputStream> resources) {
        this.userDir = userDir;
        this.backupDir = backupDir;
        this.resources = resources;
    }

    public static PresetRepository standard() {
        return new PresetRepository(ConfigPaths.PRESETS_DIR, ConfigPaths.PRESETS_BACKUP_DIR,
                name -> PresetRepository.class.getResourceAsStream(BUILTIN_DIR + name));
    }

    public File userDir() {
        return userDir;
    }

    // ==================== 内置 ====================

    /** 内置预设（按 index.json 的顺序）。读一次就缓存：jar 里的内容运行期不会变。 */
    public synchronized List<StylePreset> builtins() {
        if (builtins == null) {
            List<StylePreset> list = new ArrayList<StylePreset>();
            try {
                String index = readResource("index.json");
                JsonElement parsed = index == null ? null : JsonParser.parseString(index);
                JsonArray files = parsed != null && parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
                for (JsonElement file : files) {
                    if (file == null || !file.isJsonPrimitive()) {
                        continue;
                    }
                    String text = readResource(file.getAsString());
                    if (text == null) {
                        log("内置预设缺失：" + file.getAsString());
                        continue;
                    }
                    try {
                        list.add(PresetJson.parse(text, StylePreset.Source.BUILTIN, null));
                    } catch (PresetFormatException broken) {
                        log("内置预设解析失败（" + file.getAsString() + "）：" + broken.getMessage());
                    }
                }
            } catch (Throwable failure) {
                log("内置预设读取失败：" + failure);
            }
            builtins = Collections.unmodifiableList(list);
        }
        return builtins;
    }

    private String readResource(String name) throws IOException {
        InputStream stream = resources.apply(name);
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            return new String(readAll(in, MAX_FILE_BYTES), StandardCharsets.UTF_8);
        }
    }

    // ==================== 玩家的 ====================

    /** 玩家的预设，新的在前。读坏的文件跳过并记日志，不让一份坏文件拖垮整个列表。 */
    public List<StylePreset> userPresets() {
        List<StylePreset> presets = new ArrayList<StylePreset>();
        for (File file : list(userDir)) {
            StylePreset preset = read(file, StylePreset.Source.USER);
            if (preset != null) {
                presets.add(preset);
            }
        }
        return presets;
    }

    /** 存为一个新文件（文件名由名字推出，重名自动加序号），返回带文件位置的预设。 */
    public StylePreset saveUser(StylePreset preset) throws IOException {
        File target = uniqueFile(userDir, slug(preset.meta().name()));
        StylePreset located = preset.located(StylePreset.Source.USER, target);
        writeAtomically(target, PresetJson.write(located, true));
        return located;
    }

    /** 删除玩家的预设；内置与不在 presets 目录里的文件一律不删。 */
    public boolean delete(StylePreset preset) {
        if (preset == null || preset.source() != StylePreset.Source.USER || preset.file() == null) {
            return false;
        }
        File file = preset.file();
        if (!isInside(file, userDir)) {
            return false;
        }
        return file.delete();
    }

    // ==================== 备份 ====================

    public StylePreset saveBackup(StylePreset backup) throws IOException {
        // 文件名 = 时间戳 + 三位序号，按名字排序就是先后顺序：不靠修改时间（有的文件系统只精确到秒，
        // 同一秒里的两份备份会排乱，撤销就退错了版本）。
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        File target = null;
        for (int sequence = 0; sequence < 1000; sequence++) {
            target = new File(backupDir, String.format(Locale.ROOT, "backup-%s-%03d%s", stamp, sequence, EXTENSION));
            if (!target.exists()) {
                break;
            }
        }
        StylePreset located = backup.located(StylePreset.Source.BACKUP, target);
        writeAtomically(target, PresetJson.write(located, true));
        prune();
        backupCount = -1;
        return located;
    }

    /** 最近的一份可读备份；没有返回 {@code null}。 */
    public StylePreset latestBackup() {
        for (File file : listBackups()) {
            StylePreset preset = read(file, StylePreset.Source.BACKUP);
            if (preset != null) {
                return preset;
            }
        }
        return null;
    }

    public void removeBackup(StylePreset backup) {
        if (backup != null && backup.file() != null && isInside(backup.file(), backupDir)) {
            //noinspection ResultOfMethodCallIgnored
            backup.file().delete();
        }
        backupCount = -1;
    }

    /** 备份份数。界面每帧都要问「能不能撤销」，所以缓存，只在增删时失效。 */
    public int backupCount() {
        if (backupCount < 0) {
            backupCount = listBackups().size();
        }
        return backupCount;
    }

    private void prune() {
        List<File> files = listBackups();
        for (int i = BACKUP_KEEP; i < files.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            files.get(i).delete();
        }
    }

    // ==================== 文件工具 ====================

    /** 备份，新的在前：按文件名倒序（名字里就是时间戳 + 序号）。 */
    private List<File> listBackups() {
        List<File> files = list(backupDir);
        files.sort((a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }

    /** 目录里的预设文件，新的在前（修改时间相同再按文件名倒序，备份名里带时间戳）。 */
    private static List<File> list(File dir) {
        File[] files = dir == null || !dir.isDirectory() ? null
                : dir.listFiles(file -> file.isFile() && file.getName().endsWith(EXTENSION));
        if (files == null) {
            return new ArrayList<File>();
        }
        List<File> sorted = new ArrayList<File>(Arrays.asList(files));
        sorted.sort((a, b) -> {
            int byTime = Long.compare(b.lastModified(), a.lastModified());
            return byTime != 0 ? byTime : b.getName().compareTo(a.getName());
        });
        return sorted;
    }

    private static StylePreset read(File file, StylePreset.Source source) {
        try {
            if (file.length() > MAX_FILE_BYTES) {
                log("预设文件过大，已跳过：" + file.getName());
                return null;
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return PresetJson.parse(text, source, file);
        } catch (PresetFormatException broken) {
            log("预设读不出来（" + file.getName() + "）：" + broken.getMessage());
        } catch (Throwable failure) {
            log("预设读取失败（" + file.getName() + "）：" + failure);
        }
        return null;
    }

    /** 文件名：去掉路径分隔符与 Windows 非法字符，限长；空了就用「预设」。 */
    static String slug(String name) {
        String value = name == null ? "" : name.trim();
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean illegal = c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20;
            cleaned.append(illegal ? '_' : c);
        }
        String result = cleaned.toString().trim();
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) {
            result = "预设";
        }
        return result.length() > 48 ? result.substring(0, 48) : result;
    }

    private static File uniqueFile(File dir, String base) {
        File candidate = new File(dir, base + EXTENSION);
        for (int n = 2; candidate.exists() && n < 1000; n++) {
            candidate = new File(dir, base + " (" + n + ")" + EXTENSION);
        }
        return candidate;
    }

    private static boolean isInside(File file, File dir) {
        try {
            File parent = file.getCanonicalFile().getParentFile();
            return parent != null && parent.equals(dir.getCanonicalFile());
        } catch (IOException failure) {
            return false;
        }
    }

    /** 先写同目录临时文件再原子替换，与 {@code JsonConfigStorage} 同一做法：进程被杀也不留半截文件。 */
    static void writeAtomically(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Path path = target.toPath();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > limit) {
                throw new IOException("内容过大");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void log(String message) {
        System.out.println("[MuoniumPlayer/Preset] " + message);
    }
}
