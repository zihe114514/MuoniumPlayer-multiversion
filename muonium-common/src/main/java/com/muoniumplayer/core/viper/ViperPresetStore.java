package com.muoniumplayer.core.viper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    private static final Gson SAVE_JSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private final File directory;

    public ViperPresetStore(File directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
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

    /** 把一份预设写为 {@code <名>.json}；兼容入口只记录失败。 */
    public void save(ViperPreset preset) {
        try {
            saveChecked(preset);
        } catch (IOException | RuntimeException failure) {
            String name = preset == null ? "null" : sanitizeName(preset.name());
            System.out.println("[MuoniumPlayer/ViPER] 预设写入失败（" + name + "）：" + failure);
        }
    }

    /**
     * Checked 保存：先完整写入同目录临时文件，再替换目标；支持时使用原子移动。
     * 序列化/写入/替换失败均向调用方抛出，不先截断旧文件，文件内嵌 name 与净化文件名一致。
     */
    public void saveChecked(ViperPreset preset) throws IOException {
        String text = saveText(preset);
        replaceChecked(targetPath(preset), text);
    }

    /** 新建会话的首存：先以 CREATE_NEW 占位；已有 JSON/XML 或目录均视为名称冲突。 */
    String saveNewChecked(ViperPreset preset) throws IOException {
        String text = saveText(preset);
        Path target = targetPath(preset);
        Files.createDirectories(target.getParent());
        String candidateName = sanitizeName(preset.name());
        File[] entries = directory.listFiles();
        if (entries == null) {
            throw new IOException("无法扫描预设目录：" + directory);
        }
        for (File entry : entries) {
            if (hasRecognizedExtension(entry.getName())
                    && baseName(entry.getName()).equalsIgnoreCase(candidateName)) {
                throw new FileAlreadyExistsException(target.toString());
            }
        }
        // Atomic move 在一些文件系统上会无视 no-replace，故先原子占位以保护外部同名文件。
        Files.createFile(target);
        try {
            replaceChecked(target, text);
        } catch (IOException | RuntimeException failure) {
            try {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == 0L) {
                    Files.deleteIfExists(target);
                }
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return text;
    }

    /** 同一新建会话继续保存：文件内容已被外部替换/修改则失败，不冒认其所有权。 */
    String saveIfUnchangedChecked(ViperPreset preset, String previousText) throws IOException {
        String text = saveText(preset);
        Path target = targetPath(preset);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(target, StandardCharsets.UTF_8).equals(previousText)) {
            throw new IOException("新建预设已被外部更改，拒绝覆盖：" + target.getFileName());
        }
        replaceChecked(target, text);
        return text;
    }

    private Path targetPath(ViperPreset preset) {
        return directory.toPath().toAbsolutePath().resolve(sanitizeName(preset.name()) + ".json");
    }

    private static String saveText(ViperPreset preset) {
        Objects.requireNonNull(preset, "preset");
        JsonObject body = preset.json().deepCopy();
        String name = sanitizeName(preset.name());
        body.addProperty(ViperPreset.KEY_NAME, name);
        requireFiniteJson(body);
        ViperPreset normalized = ViperPresetParser.parse(body.toString(), name);
        // Parser 的兼容导出省略 null；保存须保留未知字段（包括 null）的原有形状。
        return SAVE_JSON.toJson(normalized.json()) + "\n";
    }

    private static void requireFiniteJson(JsonElement value) {
        if (value.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                requireFiniteJson(entry.getValue());
            }
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                requireFiniteJson(element);
            }
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            String literal = value.getAsString();
            // 未知字段可能是比 double 更大的合法 JSON 数，不以溢出的 double 视图拒绝它。
            if ("NaN".equals(literal) || "Infinity".equals(literal)
                    || "+Infinity".equals(literal) || "-Infinity".equals(literal)) {
                throw new IllegalArgumentException("预设包含非有限数值，拒绝写入");
            }
        }
    }

    private static void replaceChecked(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) {
            throw new IOException("预设目标为符号链接：" + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("预设目标不是普通文件：" + target);
        }
        Path temporary = Files.createTempFile(target.getParent(), ".viper-preset-", ".tmp");
        Throwable pending = null;
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // 只对“不支持原子移动”降级；权限/锁定等错误不能换一路伪装成功。
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error failure) {
            pending = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanup) {
                if (pending != null) {
                    pending.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
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
