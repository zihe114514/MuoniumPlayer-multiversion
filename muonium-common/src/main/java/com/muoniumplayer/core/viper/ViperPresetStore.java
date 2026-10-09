package com.muoniumplayer.core.viper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ViPER 预设的文件仓库（默认目录 {@code config/muonium/viper/presets/}）。
 *
 * <p>目录里接受三种来源的文件，全部按 {@link ViperPresetParser} 统一解析：</p>
 * <ul>
 *   <li>官方 App 导出的 {@code .json}（schema 2.1，或更早的分组 / 扁平 JSON）；</li>
 *   <li>旧版 {@code .xml}（{@code <map>} 数字 ID 格式，即「蝰蛇音效配置文件」经典形态）；</li>
 * </ul>
 *
 * <p>读取时永远解析原文、不做隐式写回；只有 {@link #importFile} / {@link #save} 会把
 * 归一化后的 schema 2.1 JSON 写进目录。加载优先取 {@code <名>.json}，其次 {@code <名>.xml}，
 * 因此同一预设「先导入、后手改 xml」的行为是确定性的（json 优先）。</p>
 */
public final class ViperPresetStore {

    /** 目录扫描与按名加载时认可的扩展名（顺序即优先级）。 */
    private static final String[] EXTENSIONS = {".json", ".xml"};

    private final File directory;

    public ViperPresetStore(File directory) {
        this.directory = directory;
    }

    public File directory() {
        return directory;
    }

    /**
     * 列出目录里的全部可解析预设（解析失败的条目跳过并打日志），按名称排序。
     */
    public List<ViperPreset> listAll() {
        List<ViperPreset> presets = new ArrayList<>();
        File[] files = directory.isDirectory() ? directory.listFiles() : null;
        if (files == null) {
            return presets;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (!file.isFile() || !hasRecognizedExtension(file.getName())) {
                continue;
            }
            ViperPreset preset = readPreset(file);
            if (preset != null) {
                presets.add(preset);
            }
        }
        return presets;
    }

    /** 目录里的预设名列表（文件基名）。 */
    public List<String> listNames() {
        List<String> names = new ArrayList<>();
        File[] files = directory.isDirectory() ? directory.listFiles() : null;
        if (files == null) {
            return names;
        }
        for (File file : files) {
            if (!file.isFile() || !hasRecognizedExtension(file.getName())) {
                continue;
            }
            String base = baseName(file.getName());
            // 同名 json/xml 只出一次。
            boolean present = false;
            for (String existing : names) {
                if (existing.equalsIgnoreCase(base)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                names.add(base);
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** 按名加载；找不到或解析失败返回 {@code null}。 */
    public ViperPreset load(String name) {
        File file = find(name);
        return file == null ? null : readPreset(file);
    }

    /** 找到预设对应的实际文件（json 优先于 xml）；不存在返回 {@code null}。 */
    public File find(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String base = sanitizeName(name);
        for (String extension : EXTENSIONS) {
            File candidate = new File(directory, base + extension);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** 预设文件（json 或 xml）的修改时间戳；用于运行时判断是否需要重载。0 = 不存在。 */
    public long stamp(String name) {
        File file = find(name);
        if (file == null) {
            return 0L;
        }
        long modified = file.lastModified();
        return modified == 0L ? 1L : modified;
    }

    /**
     * 导入一份预设文件：解析 → 归一化 → 落为目录里的 {@code <名>.json}。
     *
     * @return 落盘后的预设名（文件基名）
     * @throws IOException              读取源文件失败
     * @throws IllegalArgumentException 内容无法解析
     */
    public String importFile(File source) throws IOException {
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        return importText(text, baseName(source.getName()));
    }

    /** 与 {@link #importFile} 相同，但内容直接来自字符串。 */
    public String importText(String text, String sourceName) {
        ViperPreset parsed = ViperPresetParser.parse(text, sanitizeName(sourceName));
        String name = sanitizeName(parsed.name());
        save(new ViperPreset(name, parsed.json()));
        return name;
    }

    /** 把一份预设写为 {@code <名>.json}（覆盖同名文件）。 */
    public void save(ViperPreset preset) {
        String name = sanitizeName(preset.name());
        try {
            Files.createDirectories(directory.toPath());
            File target = new File(directory, name + ".json");
            Files.write(target.toPath(),
                    ViperPresetParser.toJsonText(preset).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/ViPER] 预设写入失败（" + name + "）：" + failure);
        }
    }

    /** 删除预设（json 与 xml 同名的都删）。 */
    public void delete(String name) {
        String base = sanitizeName(name);
        for (String extension : EXTENSIONS) {
            File file = new File(directory, base + extension);
            if (file.isFile() && !file.delete()) {
                System.out.println("[MuoniumPlayer/ViPER] 预设删除失败：" + file);
            }
        }
    }

    private ViperPreset readPreset(File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return ViperPresetParser.parse(text, baseName(file.getName()));
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/ViPER] 预设解析失败（" + file.getName() + "）：" + failure.getMessage());
            return null;
        }
    }

    private static boolean hasRecognizedExtension(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 文件基名净化：去掉路径分隔符与 Windows 非法字符，限长 64。 */
    public static String sanitizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean illegal = current == '\\' || current == '/' || current == ':' || current == '*'
                    || current == '?' || current == '"' || current == '<' || current == '>'
                    || current == '|' || current < 0x20;
            cleaned.append(illegal ? '_' : current);
        }
        String result = cleaned.toString().trim();
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) {
            result = "未命名";
        }
        return result.length() > 64 ? result.substring(0, 64) : result;
    }
}
