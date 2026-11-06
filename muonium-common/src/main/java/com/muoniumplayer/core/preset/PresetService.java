package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.codec.PresetJson;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.codec.ShareCode;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.update.UpdateChecker;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * 模板预设对界面的门面：列出、预检、套用（自动备份）、撤销、存为预设、分享码、导入。
 *
 * <p><b>只在客户端主线程上调用</b>：套用会改渲染线程每帧读的静态字段，读写的文件都是几 KB，
 * 与现有设置界面「改完立即同步落盘」同一约定。</p>
 */
public final class PresetService {

    private static PresetService instance;

    private final PresetEngine engine;
    private final PresetRepository repository;
    private final CompactShareCode shareCode;

    public PresetService(PresetEngine engine, PresetRepository repository) {
        this.engine = engine;
        this.repository = repository;
        this.shareCode = new CompactShareCode(engine.domains());
    }

    public static synchronized PresetService get() {
        if (instance == null) {
            instance = new PresetService(new PresetEngine(PresetDomains.all()), PresetRepository.standard());
        }
        return instance;
    }

    public PresetEngine engine() {
        return engine;
    }

    public PresetRepository repository() {
        return repository;
    }

    public List<StylePreset> builtins() {
        return repository.builtins();
    }

    public List<StylePreset> userPresets() {
        return repository.userPresets();
    }

    /** 套用面板的默认勾选：预设声明的分面里，本版本有的、且不需要主动勾选的那些。 */
    public Set<Facet> defaultSelection(StylePreset preset) {
        Set<Facet> available = EnumSet.noneOf(Facet.class);
        for (Facet facet : preset.facets()) {
            if (engine.availableFacets().contains(facet)) {
                available.add(facet);
            }
        }
        return Facet.withoutOptIn(available);
    }

    /** 预检：会改多少项、有哪些提示。不改任何东西。 */
    public CompatReport preview(StylePreset preset, Set<Facet> selected) {
        return engine.plan(preset, selected);
    }

    /**
     * 套用。先把这次要动的分面整体备份（「撤销上次套用」就是套回这份备份），再套用。
     * 备份失败不拦着套用，但会在报告里说清楚这一次撤销不了。
     */
    public CompatReport apply(StylePreset preset, Set<Facet> selected) {
        Set<Facet> effective = EnumSet.noneOf(Facet.class);
        for (Facet facet : selected) {
            if (preset.facets().contains(facet)) {
                effective.add(facet);
            }
        }
        String backupProblem = null;
        try {
            Map<String, DomainFragment> fragments = engine.capture(effective);
            PresetMeta meta = new PresetMeta("backup:" + UUID.randomUUID(),
                    "套用「" + preset.meta().name() + "」之前", "", "", now(),
                    MuoniumPlayerExtension.VERSION, UpdateChecker.minecraftVersion());
            repository.saveBackup(new StylePreset(meta, StylePreset.Base.CURRENT, effective, null, fragments,
                    StylePreset.Source.BACKUP, null));
        } catch (Throwable failure) {
            backupProblem = "这次没能备份当前配置（" + failure.getMessage() + "），套用后无法撤销";
        }
        CompatReport report = engine.apply(preset, effective);
        if (backupProblem != null) {
            report.warn(backupProblem);
        }
        return report;
    }

    public boolean canUndo() {
        return repository.backupCount() > 0;
    }

    /** 撤销上一次套用：套回最近一份备份并删掉它（再点一次就继续往回退）。没有可撤销的返回 {@code null}。 */
    public CompatReport undo() {
        StylePreset backup = repository.latestBackup();
        if (backup == null) {
            return null;
        }
        CompatReport report = engine.apply(backup, backup.facets());
        repository.removeBackup(backup);
        return report;
    }

    /** 把当前配置的所选分面存成一份预设（{@code base: defaults}，捕获的是这些分面的全部键）。 */
    public StylePreset saveCurrent(String name, Set<Facet> facets) throws IOException {
        return repository.saveUser(snapshot(name, facets));
    }

    /** 把当前配置的所选分面做成一份预设，不落盘（例如 H 键设置页「复制当前外观的分享码」）。 */
    public StylePreset snapshot(String name, Set<Facet> facets) {
        String cleanName = PresetJson.clean(name, PresetLimits.MAX_NAME_CHARS);
        PresetMeta meta = new PresetMeta("user:" + UUID.randomUUID(),
                cleanName.isEmpty() ? "我的预设" : cleanName, "", "", now(),
                MuoniumPlayerExtension.VERSION, UpdateChecker.minecraftVersion());
        Map<String, DomainFragment> fragments = engine.capture(facets);
        return new StylePreset(meta, StylePreset.Base.DEFAULTS, facets, null, fragments,
                StylePreset.Source.USER, null);
    }

    /** 复制到剪贴板的一行：短名字 + v2 分享码（只写与出厂值不同的项）。 */
    public String shareText(StylePreset preset) {
        return shareCode.shareLine(preset);
    }

    /**
     * 从一段文字导入，存进「我的预设」。认三种：v2 分享码（{@code MPP2:}）、v1 分享码（{@code MPP1:}，
     * 早期测试包生成的）、直接粘贴的预设 JSON。
     */
    public StylePreset importText(String text) throws PresetFormatException, IOException {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1).trim();
        }
        StylePreset parsed;
        if (trimmed.startsWith("{")) {
            parsed = PresetJson.parse(trimmed, StylePreset.Source.USER, null);
        } else if (CompactShareCode.contains(text)) {
            parsed = shareCode.decode(text);
        } else {
            parsed = PresetJson.parse(ShareCode.decode(text), StylePreset.Source.USER, null);
        }
        // 导入的一律换成新的 user id：内置与备份的 id 不许被外来文件冒用。
        PresetMeta meta = parsed.meta().withId("user:" + UUID.randomUUID());
        return repository.saveUser(parsed.withMeta(meta));
    }

    /** 从文件导入（.mpreset.json，或任何装着分享码 / 预设 JSON 的文本文件）。 */
    public StylePreset importFile(File file) throws PresetFormatException, IOException {
        if (file == null || !file.isFile()) {
            throw new PresetFormatException("文件不存在");
        }
        String text;
        try (InputStream in = new FileInputStream(file)) {
            text = new String(PresetRepository.readAll(in, PresetLimits.MAX_JSON_CHARS * 4L), StandardCharsets.UTF_8);
        } catch (IOException tooLarge) {
            throw new PresetFormatException("文件读不出来或过大：" + tooLarge.getMessage(), tooLarge);
        }
        return importText(text);
    }

    public boolean delete(StylePreset preset) {
        return repository.delete(preset);
    }

    private static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
