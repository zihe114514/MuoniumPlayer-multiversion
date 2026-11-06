package com.muoniumplayer.core.preset.ui;

import com.muoniumplayer.core.preset.PresetService;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 模板预设面板的「内容与行为」：两种画法共用这一份。
 *
 * <p>播放器里的 {@code PresetOverlay} 是组件树，H 键设置页里的 {@code HudPresetDialog} 是逐帧手画，
 * 长相各随宿主，但「有哪些内容、点了做什么、状态行说什么」必须完全一样 —— 所以都在这里，
 * 两边只管画。这里不碰任何绘制，也不决定换页动画：页面跳转交回给画法自己（{@link Navigator}），
 * 因为播放器那边要区分「前进 / 回退」两种翻页方向。</p>
 *
 * <p>线程：除 {@link #browseForFile} 的系统文件框外，全部在客户端主线程调用；文件框那条线程
 * 只读路径，结果经 {@link MultiThreadingUtil#runOnMainThread} 回到主线程再处理。</p>
 */
public final class PresetPanelSession {

    /** 三页：主页（内置风格 + 我的预设）、套用页（按分面勾选 + 预检）、存为预设页。 */
    public enum Page { MAIN, APPLY, SAVE }

    /** 状态行语气。画法各自把它映射成颜色与图标。 */
    public enum Tone { INFO, OK, BAD, WARN }

    /** 换页交给画法。{@code backward} 只影响翻页动画的方向。 */
    public interface Navigator {
        void showMain(boolean backward);

        void showApply(StylePreset preset);
    }

    /** 剪贴板：生产走 {@link KeyboardUtils}（GLFW，只能在主线程用），测试塞一个假的。 */
    public interface Clipboard {
        String get();

        void set(String text);
    }

    private static final Clipboard SYSTEM_CLIPBOARD = new Clipboard() {
        @Override
        public String get() {
            return KeyboardUtils.getClipboardString();
        }

        @Override
        public void set(String text) {
            KeyboardUtils.setClipboardString(text);
        }
    };

    // ---- 两边共用的文案 ----

    public static final String TITLE = "模板预设";
    public static final String MAIN_SUBTITLE = "一键换整套外观与音效 · 存下自己的配置 · 用分享码互相交换";
    public static final String SAVE_TITLE = "存为预设";
    public static final String SAVE_SUBTITLE = "把当前配置的所选部分存下来 · 存好后可以复制分享码发给别人";
    public static final String SECTION_BUILTIN = "内置风格";
    public static final String EMPTY_MINE = "还没有：点「存为预设」存下当前配置，或「粘贴导入」别人发来的分享码";
    public static final String APPLY_EMPTY = "这个预设里没有本版本支持的内容（可能来自更新的版本）";
    public static final String NAME_PLACEHOLDER = "给这套配置起个名字，比如「夜间沉浸」「直播用」";
    public static final String MAIN_NOTE = "目录：config/muonium/presets/ · 预设是 .mpreset.json 文本文件，也可以直接发文件";
    public static final String APPLY_NOTE = "布局位置与画质性能默认不勾：它们和屏幕、机器有关，照搬别人的多半不合适";
    public static final String SAVE_NOTE = "账号、Cookie、音源与缓存永远不会进预设；字体文件、卷积核、自定义图片也不随分享走";
    public static final String DELETE_TITLE = "删除预设";
    public static final String UNDO_TIP_READY = "回到上一次套用之前的样子（可以逐级往回撤）";
    public static final String UNDO_TIP_EMPTY = "还没有可以撤销的套用";

    /** 套用页最多列几条提示，多出来的并成一句「另有 N 条」。 */
    public static final int NOTE_LIMIT = 5;

    private final PresetService service;
    private final Clipboard clipboard;
    private Navigator navigator;

    private Page page = Page.MAIN;
    private volatile String statusText = "";
    private volatile Tone statusTone = Tone.INFO;
    /** 列表修订号：导入 / 删除 / 保存后自增，画法看到落后就重建主页。 */
    private volatile int revision;

    /** 系统文件框开着：防连点。 */
    private volatile boolean picking;
    private volatile boolean pickerUnavailable;

    // ---- 套用页 ----
    private StylePreset target;
    private final Set<Facet> selection = EnumSet.noneOf(Facet.class);
    private List<String> previewLines = Collections.emptyList();
    private int previewChanges;
    private boolean previewFailed;

    // ---- 存为预设页 ----
    private final Set<Facet> saveSelection = EnumSet.noneOf(Facet.class);
    private String saveHint = "";
    private boolean saveHintBad;

    public PresetPanelSession(PresetService service, Clipboard clipboard) {
        this.service = service;
        this.clipboard = clipboard;
    }

    /** 生产用：全局的预设服务 + 系统剪贴板。 */
    public static PresetPanelSession standard() {
        return new PresetPanelSession(PresetService.get(), SYSTEM_CLIPBOARD);
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    /** 打开面板时调用：回到主页，状态行换成说明。 */
    public void reset() {
        page = Page.MAIN;
        statusText = idleStatus();
        statusTone = Tone.INFO;
    }

    // ════════════════════════════ 读数 ════════════════════════════

    public Page page() {
        return page;
    }

    public String statusText() {
        return statusText;
    }

    public Tone statusTone() {
        return statusTone;
    }

    public int revision() {
        return revision;
    }

    public boolean picking() {
        return picking;
    }

    public StylePreset target() {
        return target;
    }

    public List<String> previewLines() {
        return previewLines;
    }

    public int previewChanges() {
        return previewChanges;
    }

    public String saveHint() {
        return saveHint;
    }

    public boolean saveHintBad() {
        return saveHintBad;
    }

    public List<StylePreset> builtins() {
        return service.builtins();
    }

    public List<StylePreset> mine() {
        return service.userPresets();
    }

    public boolean canUndo() {
        return service.canUndo();
    }

    public String undoTooltip() {
        return service.canUndo() ? UNDO_TIP_READY : UNDO_TIP_EMPTY;
    }

    public String idleStatus() {
        return service.canUndo()
                ? "点一张卡片查看内容并套用 · 每次套用前都会自动备份，可以逐级撤销"
                : "点一张卡片查看内容并套用 · 套用前会自动备份，随时可以撤销";
    }

    /** 「我的预设」小标题：有几份就写几份。 */
    public String mineSectionTitle(int count) {
        return count == 0 ? "我的预设" : "我的预设（" + count + "）";
    }

    /** 卡片副标题：我的预设写摘要，内置写说明（没有说明也写摘要）。 */
    public static String cardNote(StylePreset preset) {
        boolean mine = preset.source() == StylePreset.Source.USER;
        return mine || preset.meta().description().isEmpty() ? summary(preset) : preset.meta().description();
    }

    /** 套用页的副标题。 */
    public static String applySubtitle(StylePreset preset) {
        return preset.meta().description().isEmpty() ? summary(preset) : preset.meta().description();
    }

    /** 卡片副标题：分面不多就列名字，多了只说「外观 + 音效 · N 个部分」。 */
    public static String summary(StylePreset preset) {
        List<String> names = new ArrayList<String>();
        for (Facet facet : Facet.values()) {
            if (preset.facets().contains(facet)) {
                names.add(facet.displayName());
            }
        }
        String body;
        if (names.isEmpty()) {
            body = "这个预设里没有本版本认识的内容";
        } else if (names.size() <= 3) {
            body = String.join(" · ", names);
        } else {
            boolean look = preset.touches(Facet.Group.APPEARANCE);
            boolean audio = preset.touches(Facet.Group.AUDIO);
            body = (look && audio ? "外观 + 音效" : look ? "外观" : "音效") + " · " + names.size() + " 个部分";
        }
        String author = preset.meta().author();
        return author.isEmpty() ? body : body + " · by " + author;
    }

    /** 卡片左侧那枚图标（party-icons 码位）：两种画法用同一枚。 */
    public static String iconOf(StylePreset preset) {
        if (preset.source() == StylePreset.Source.USER) {
            return preset.touches(Facet.Group.AUDIO) && !preset.touches(Facet.Group.APPEARANCE)
                    ? PartyIcons.HEADPHONES : PartyIcons.PERSON;
        }
        return "builtin:minimal".equals(preset.meta().id()) ? PartyIcons.NOTE : PartyIcons.SWEEP;
    }

    /** 分面开关的标题：需要主动勾选的那几个注明一句。 */
    public static String facetCaption(Facet facet) {
        return facet.displayName() + (facet.optIn() ? "（默认不勾）" : "");
    }

    /** 预设声明的、本版本能套用的、属于这一组的分面（按分面表顺序）。 */
    public List<Facet> facetsOf(StylePreset preset, Facet.Group group) {
        List<Facet> facets = new ArrayList<Facet>();
        if (preset == null) return facets;
        Set<Facet> available = service.engine().availableFacets();
        for (Facet facet : Facet.values()) {
            if (facet.group() == group && preset.facets().contains(facet) && available.contains(facet)) {
                facets.add(facet);
            }
        }
        return facets;
    }

    /** 本版本能存进预设的、属于这一组的分面。 */
    public List<Facet> facetsAvailable(Facet.Group group) {
        List<Facet> facets = new ArrayList<Facet>();
        Set<Facet> available = service.engine().availableFacets();
        for (Facet facet : Facet.values()) {
            if (facet.group() == group && available.contains(facet)) {
                facets.add(facet);
            }
        }
        return facets;
    }

    // ════════════════════════════ 换页 ════════════════════════════

    /** 画法建主页时调用。 */
    public void enterMain() {
        page = Page.MAIN;
    }

    /** 画法建套用页时调用：勾选回到默认，并立即预检一次。 */
    public void enterApply(StylePreset preset) {
        page = Page.APPLY;
        target = preset;
        selection.clear();
        selection.addAll(service.defaultSelection(preset));
        refreshPreview();
    }

    /** 画法建存为预设页时调用：默认勾选所有不需要主动勾选的分面。 */
    public void enterSave() {
        page = Page.SAVE;
        saveSelection.clear();
        saveSelection.addAll(Facet.withoutOptIn(service.engine().availableFacets()));
        saveHint = "勾选要存进去的部分 · 只选外观或只选音效，就能单独分享那一部分";
        saveHintBad = false;
    }

    // ════════════════════════════ 套用页 ════════════════════════════

    public boolean isSelected(Facet facet) {
        return selection.contains(facet);
    }

    public void toggle(Facet facet) {
        if (!selection.remove(facet)) selection.add(facet);
        refreshPreview();
    }

    public void selectAll() {
        selection.clear();
        selection.addAll(facetsOf(target, Facet.Group.APPEARANCE));
        selection.addAll(facetsOf(target, Facet.Group.AUDIO));
        refreshPreview();
    }

    public void selectAppearanceOnly() {
        selection.clear();
        selection.addAll(Facet.withoutOptIn(withAll(facetsOf(target, Facet.Group.APPEARANCE))));
        refreshPreview();
    }

    public void selectAudioOnly() {
        selection.clear();
        selection.addAll(facetsOf(target, Facet.Group.AUDIO));
        refreshPreview();
    }

    public void refreshPreview() {
        if (target == null) return;
        try {
            CompatReport report = service.preview(target, selection);
            previewLines = report.lines();
            previewChanges = report.changedKeys();
            previewFailed = report.hasFailures();
        } catch (Throwable failure) {
            previewLines = Collections.singletonList("预检失败：" + failure.getMessage());
            previewChanges = 0;
            previewFailed = true;
        }
    }

    public String previewStatus() {
        if (selection.isEmpty()) return "先勾选要套用的部分";
        if (previewFailed) return "有部分设置套用不了，详见下方";
        if (previewChanges == 0) return "所选部分和你现在的配置一样，套用不会有变化";
        return "将改动 " + previewChanges + " 项设置" + (previewLines.isEmpty() ? "" : " · 有 " + previewLines.size() + " 条提示")
                + " · 套用前自动备份";
    }

    public Tone previewTone() {
        if (previewFailed) return Tone.BAD;
        if (selection.isEmpty() || !previewLines.isEmpty()) return Tone.WARN;
        return previewChanges > 0 ? Tone.OK : Tone.INFO;
    }

    /** 主按钮上的字：「套用 N 项」。 */
    public String applyLabel() {
        return previewChanges > 0 ? "套用 " + previewChanges + " 项" : "套用";
    }

    /** 提示区第 {@code index} 行；超过上限时最后一行并成「另有 N 条」，没有内容返回空串。 */
    public String noteLine(int index) {
        List<String> lines = previewLines;
        if (index >= lines.size()) return "";
        if (index == NOTE_LIMIT - 1 && lines.size() > NOTE_LIMIT) {
            return "· 另有 " + (lines.size() - NOTE_LIMIT + 1) + " 条提示";
        }
        return "· " + lines.get(index);
    }

    public void applyTarget() {
        if (target == null) return;
        if (selection.isEmpty()) {
            refreshPreview();
            return;
        }
        final StylePreset preset = target;
        CompatReport report = service.apply(preset, selection);
        String name = preset.meta().name();
        if (report.hasFailures()) {
            setStatus(report.failures().get(0), Tone.BAD);
        } else if (report.changedKeys() == 0) {
            setStatus("「" + name + "」和你现在的配置一样，没有需要改的", Tone.INFO);
        } else {
            List<String> lines = report.lines();
            setStatus("已套用「" + name + "」· 改动 " + report.changedKeys() + " 项"
                            + (lines.isEmpty() ? " · 不满意可以点「撤销套用」" : " · " + lines.get(0)),
                    lines.isEmpty() ? Tone.OK : Tone.WARN);
        }
        navigateMain(true);
    }

    // ════════════════════════════ 存为预设 ════════════════════════════

    public boolean isSaveSelected(Facet facet) {
        return saveSelection.contains(facet);
    }

    public void toggleSave(Facet facet) {
        if (!saveSelection.remove(facet)) saveSelection.add(facet);
        updateSaveHint();
    }

    public void saveSelectAppearanceOnly() {
        saveSelection.clear();
        saveSelection.addAll(Facet.withoutOptIn(withAll(facetsAvailable(Facet.Group.APPEARANCE))));
        updateSaveHint();
    }

    public void saveSelectAudioOnly() {
        saveSelection.clear();
        saveSelection.addAll(facetsAvailable(Facet.Group.AUDIO));
        updateSaveHint();
    }

    private void updateSaveHint() {
        saveHint = saveSelection.isEmpty() ? "至少勾选一个部分" : "已选 " + saveSelection.size() + " 个部分";
        saveHintBad = saveSelection.isEmpty();
    }

    public void save(String rawName, boolean copy) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            saveHint = "先起个名字";
            saveHintBad = true;
            return;
        }
        if (saveSelection.isEmpty()) {
            saveHint = "至少勾选一个部分";
            saveHintBad = true;
            return;
        }
        try {
            StylePreset saved = service.saveCurrent(name, saveSelection);
            if (copy) {
                String text = service.shareText(saved);
                clipboard.set(text);
                setStatus("已保存「" + saved.meta().name() + "」并复制分享码（" + text.length() + " 字）", Tone.OK);
            } else {
                setStatus("已保存「" + saved.meta().name() + "」· 卡片右侧可以复制分享码", Tone.OK);
            }
            revision++;
            navigateMain(true);
        } catch (Throwable failure) {
            saveHint = "保存失败：" + failure.getMessage();
            saveHintBad = true;
        }
    }

    // ════════════════════════════ 主页 ════════════════════════════

    public void copyShareCode(StylePreset preset) {
        try {
            String text = service.shareText(preset);
            clipboard.set(text);
            setStatus("已复制「" + preset.meta().name() + "」的分享码（" + text.length()
                    + " 字）· 发给朋友，对方点「粘贴导入」即可", Tone.OK);
        } catch (Throwable failure) {
            setStatus("生成分享码失败：" + failure.getMessage(), Tone.BAD);
        }
    }

    /** 删除确认框的正文。 */
    public static String deleteMessage(StylePreset preset) {
        return "将删除「" + preset.meta().name() + "」的文件；删了只能从分享码或备份的文件找回。";
    }

    /** 删除（二次确认由画法负责）。 */
    public void delete(StylePreset preset) {
        String name = preset.meta().name();
        if (service.delete(preset)) {
            setStatus("已删除「" + name + "」", Tone.INFO);
        } else {
            setStatus("删除失败：文件可能已被移走", Tone.BAD);
        }
        revision++;
    }

    public void importFromClipboard() {
        String text = clipboard.get();
        try {
            StylePreset imported = service.importText(text);
            setStatus("已导入「" + imported.meta().name() + "」· 在「我的预设」里", Tone.OK);
            revision++;
            if (navigator != null) navigator.showApply(imported);
        } catch (PresetFormatException bad) {
            setStatus(bad.getMessage(), Tone.BAD);
        } catch (Throwable failure) {
            setStatus("导入失败：" + failure.getMessage(), Tone.BAD);
        }
    }

    /**
     * 系统文件框挑一个预设文件。与 ViPER / 字体导入同一套路：反射调 LWJGL 带下来的 tinyfd，
     * 原生对话框是模态的，必须单开线程，在渲染线程上弹会把整个游戏冻在那一帧。
     */
    public void browseForFile() {
        if (picking) return;
        picking = true;
        setStatus("已打开系统文件选择框，请挑一个模板预设文件（.mpreset.json）", Tone.INFO);
        final Thread picker = new Thread(() -> {
            final String picked = nativeOpenDialog();
            MultiThreadingUtil.runOnMainThread(() -> {
                picking = false;
                if (picked == null || picked.trim().isEmpty()) {
                    if (pickerUnavailable) {
                        setStatus("这个版本打不开系统文件框：把文件放进 presets 目录，或复制里面的内容后点「粘贴导入」",
                                Tone.BAD);
                    } else {
                        setStatus("没有选择文件", Tone.INFO);
                    }
                    return;
                }
                try {
                    StylePreset imported = service.importFile(new File(picked.trim()));
                    setStatus("已导入「" + imported.meta().name() + "」· 在「我的预设」里", Tone.OK);
                    revision++;
                    if (navigator != null) navigator.showApply(imported);
                } catch (PresetFormatException bad) {
                    setStatus(bad.getMessage(), Tone.BAD);
                } catch (Throwable failure) {
                    setStatus("导入失败：" + failure.getMessage(), Tone.BAD);
                }
            });
        }, "MuoniumPlayer-PresetPicker");
        picker.setDaemon(true);
        picker.start();
    }

    private String nativeOpenDialog() {
        pickerUnavailable = false;
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
            java.lang.reflect.Method open = dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointerBuffer,
                    CharSequence.class, boolean.class);
            Object result = open.invoke(null, "选择模板预设文件", "", null, null, Boolean.FALSE);
            return result == null ? null : result.toString();
        } catch (Throwable unavailable) {
            pickerUnavailable = true;
            return null;
        }
    }

    public void undo() {
        if (!service.canUndo()) {
            setStatus("还没有可以撤销的套用", Tone.INFO);
            return;
        }
        CompatReport report = service.undo();
        if (report == null) {
            setStatus("备份读不出来，没法撤销", Tone.BAD);
        } else if (report.hasFailures()) {
            setStatus(report.failures().get(0), Tone.BAD);
        } else {
            setStatus("已撤销上一次套用" + (service.canUndo() ? " · 还可以继续往回撤" : ""), Tone.OK);
        }
    }

    public void openFolder() {
        File dir = ConfigPaths.PRESETS_DIR;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            setStatus("建不了目录 " + dir.getAbsolutePath(), Tone.BAD);
            return;
        }
        if (BrowserUtils.open(dir.getAbsoluteFile().toURI().toString())) {
            setStatus("已打开预设目录", Tone.INFO);
        } else {
            setStatus("打不开目录，路径是 " + dir.getAbsolutePath(), Tone.BAD);
        }
    }

    // ════════════════════════════ 小工具 ════════════════════════════

    private void navigateMain(boolean backward) {
        if (navigator != null) navigator.showMain(backward);
    }

    private void setStatus(String text, Tone tone) {
        statusText = text == null ? "" : text;
        statusTone = tone;
    }

    private static Set<Facet> withAll(List<Facet> facets) {
        Set<Facet> set = EnumSet.noneOf(Facet.class);
        set.addAll(facets);
        return set;
    }
}
