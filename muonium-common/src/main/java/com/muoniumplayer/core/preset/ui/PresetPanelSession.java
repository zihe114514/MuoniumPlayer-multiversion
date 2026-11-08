package com.muoniumplayer.core.preset.ui;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.PresetService;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Shared data/actions only. No player entry, GLFW, rendering, Compose, loader, thread or native dialog dependency. */
public final class PresetPanelSession {
    public enum Page { MAIN, APPLY, SAVE }
    public enum Tone { INFO, OK, BAD, WARN }
    public interface Navigator {
        void showMain(boolean backward);
        void showApply(StylePreset preset);
    }
    public interface Clipboard { String get(); void set(String text); }
    /** Host owns native dialogs, thread scheduling and OS integration; callbacks must return on the client main thread. */
    public interface Host {
        void chooseFile(java.util.function.Consumer<File> result);
        boolean openFolder(File directory);
    }
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
    public static final int NOTE_LIMIT = 5;
    private final PresetService service;
    private final Clipboard clipboard;
    private Navigator navigator;
    private Host host;
    private Page page = Page.MAIN;
    private String statusText = "";
    private Tone statusTone = Tone.INFO;
    private int revision;
    private boolean picking;
    private StylePreset target;
    private final Set<Facet> selection = EnumSet.noneOf(Facet.class), saveSelection = EnumSet.noneOf(Facet.class);
    private List<String> previewLines = Collections.emptyList();
    private int previewChanges;
    private boolean previewFailed, saveHintBad;
    private String saveHint = "";
    public PresetPanelSession(PresetService service, Clipboard clipboard) {
        if (service == null || clipboard == null) throw new IllegalArgumentException("service/clipboard");
        this.service = service; this.clipboard = clipboard;
    }
    public void setNavigator(Navigator navigator) { this.navigator = navigator; }
    public void setHost(Host host) { this.host = host; }
    public void reset() { page = Page.MAIN; target = null; selection.clear(); setStatus(idleStatus(), Tone.INFO); }
    public Page page() { return page; }
    public String statusText() { return statusText; }
    public Tone statusTone() { return statusTone; }
    public int revision() { return revision; }
    public boolean picking() { return picking; }
    public StylePreset target() { return target; }
    public List<String> previewLines() { return Collections.unmodifiableList(previewLines); }
    public int previewChanges() { return previewChanges; }
    public String saveHint() { return saveHint; }
    public boolean saveHintBad() { return saveHintBad; }
    public List<StylePreset> builtins() { return service.builtins(); }
    public List<StylePreset> mine() { return service.userPresets(); }
    public boolean canUndo() { return service.canUndo(); }
    public String undoTooltip() { return canUndo() ? UNDO_TIP_READY : UNDO_TIP_EMPTY; }
    public String idleStatus() {
        return canUndo() ? "点一张卡片查看内容并套用 · 每次套用前都会自动备份，可以逐级撤销"
                : "点一张卡片查看内容并套用 · 套用前会自动备份，随时可以撤销";
    }
    public String mineSectionTitle(int count) { return count == 0 ? "我的预设" : "我的预设（" + count + "）"; }
    public static String cardNote(StylePreset preset) {
        return preset.source() == StylePreset.Source.USER || preset.meta().description().isEmpty() ? summary(preset) : preset.meta().description();
    }
    public static String applySubtitle(StylePreset preset) { return preset.meta().description().isEmpty() ? summary(preset) : preset.meta().description(); }
    public static String summary(StylePreset preset) {
        List<String> names = new ArrayList<String>();
        for (Facet facet : Facet.values()) if (preset.facets().contains(facet)) names.add(facet.displayName());
        String body;
        if (names.isEmpty()) body = "这个预设里没有本版本认识的内容";
        else if (names.size() <= 3) body = String.join(" · ", names);
        else {
            boolean look = preset.touches(Facet.Group.APPEARANCE), audio = preset.touches(Facet.Group.AUDIO);
            body = (look && audio ? "外观 + 音效" : look ? "外观" : "音效") + " · " + names.size() + " 个部分";
        }
        return preset.meta().author().isEmpty() ? body : body + " · by " + preset.meta().author();
    }
    /** Semantic icon id, interpreted by the host; no font/render dependency. */
    public static String iconOf(StylePreset preset) {
        if (preset.source() == StylePreset.Source.USER)
            return preset.touches(Facet.Group.AUDIO) && !preset.touches(Facet.Group.APPEARANCE) ? "headphones" : "person";
        return "builtin:minimal".equals(preset.meta().id()) ? "note" : "sweep";
    }
    public static String facetCaption(Facet facet) { return facet.displayName() + (facet.optIn() ? "（默认不勾）" : ""); }
    public List<Facet> facetsOf(StylePreset preset, Facet.Group group) {
        List<Facet> out = new ArrayList<Facet>();
        if (preset != null) for (Facet facet : Facet.values())
            if (facet.group() == group && preset.facets().contains(facet) && service.engine().availableFacets().contains(facet)) out.add(facet);
        return out;
    }
    public List<Facet> facetsAvailable(Facet.Group group) {
        List<Facet> out = new ArrayList<Facet>();
        for (Facet facet : Facet.values()) if (facet.group() == group && service.engine().availableFacets().contains(facet)) out.add(facet);
        return out;
    }
    public void enterMain() { page = Page.MAIN; }
    public void enterApply(StylePreset preset) {
        page = Page.APPLY; target = preset; selection.clear(); selection.addAll(service.defaultSelection(preset)); refreshPreview();
    }
    public void enterSave() {
        page = Page.SAVE; saveSelection.clear(); saveSelection.addAll(Facet.withoutOptIn(service.engine().availableFacets()));
        saveHint = "勾选要存进去的部分 · 只选外观或只选音效，就能单独分享那一部分"; saveHintBad = false;
    }
    public boolean isSelected(Facet facet) { return selection.contains(facet); }
    public void toggle(Facet facet) {
        if (target == null || !target.facets().contains(facet) || !service.engine().availableFacets().contains(facet)) return;
        if (!selection.remove(facet)) selection.add(facet); refreshPreview();
    }
    public void selectAll() { selection.clear(); selection.addAll(facetsOf(target, Facet.Group.APPEARANCE)); selection.addAll(facetsOf(target, Facet.Group.AUDIO)); refreshPreview(); }
    public void selectAppearanceOnly() { selection.clear(); selection.addAll(Facet.withoutOptIn(withAll(facetsOf(target, Facet.Group.APPEARANCE)))); refreshPreview(); }
    public void selectAudioOnly() { selection.clear(); selection.addAll(facetsOf(target, Facet.Group.AUDIO)); refreshPreview(); }
    public void refreshPreview() {
        if (target == null) return;
        try {
            CompatReport report = service.preview(target, selection); previewLines = report.lines(); previewChanges = report.changedKeys(); previewFailed = report.hasFailures();
        } catch (Throwable failure) {
            previewLines = Collections.singletonList("预检失败：" + failure.getMessage()); previewChanges = 0; previewFailed = true;
        }
    }
    public String previewStatus() {
        if (selection.isEmpty()) return "先勾选要套用的部分";
        if (previewFailed) return "有部分设置套用不了，详见下方";
        if (previewChanges == 0) return "所选部分和你现在的配置一样，套用不会有变化";
        return "将改动 " + previewChanges + " 项设置" + (previewLines.isEmpty() ? "" : " · 有 " + previewLines.size() + " 条提示") + " · 套用前自动备份";
    }
    public Tone previewTone() {
        if (previewFailed) return Tone.BAD;
        if (selection.isEmpty() || !previewLines.isEmpty()) return Tone.WARN;
        return previewChanges > 0 ? Tone.OK : Tone.INFO;
    }
    public String applyLabel() { return previewChanges > 0 ? "套用 " + previewChanges + " 项" : "套用"; }
    public String noteLine(int index) {
        if (index < 0 || index >= previewLines.size()) return "";
        if (index == NOTE_LIMIT - 1 && previewLines.size() > NOTE_LIMIT) return "· 另有 " + (previewLines.size() - NOTE_LIMIT + 1) + " 条提示";
        return "· " + previewLines.get(index);
    }
    public void applyTarget() {
        if (target == null || selection.isEmpty()) { refreshPreview(); return; }
        CompatReport report = service.apply(target, selection);
        if (report.hasFailures()) setStatus(report.failures().get(0), Tone.BAD);
        else if (report.changedKeys() == 0) setStatus("「" + target.meta().name() + "」和你现在的配置一样，没有需要改的", Tone.INFO);
        else setStatus("已套用「" + target.meta().name() + "」· 改动 " + report.changedKeys() + " 项"
                + (report.lines().isEmpty() ? " · 不满意可以点「撤销套用」" : " · " + report.lines().get(0)), report.lines().isEmpty() ? Tone.OK : Tone.WARN);
        navigateMain(true);
    }
    public boolean isSaveSelected(Facet facet) { return saveSelection.contains(facet); }
    public void toggleSave(Facet facet) {
        if (!service.engine().availableFacets().contains(facet)) return;
        if (!saveSelection.remove(facet)) saveSelection.add(facet); updateSaveHint();
    }
    public void saveSelectAppearanceOnly() { saveSelection.clear(); saveSelection.addAll(Facet.withoutOptIn(withAll(facetsAvailable(Facet.Group.APPEARANCE)))); updateSaveHint(); }
    public void saveSelectAudioOnly() { saveSelection.clear(); saveSelection.addAll(facetsAvailable(Facet.Group.AUDIO)); updateSaveHint(); }
    private void updateSaveHint() { saveHint = saveSelection.isEmpty() ? "至少勾选一个部分" : "已选 " + saveSelection.size() + " 个部分"; saveHintBad = saveSelection.isEmpty(); }
    public void save(String rawName, boolean copy) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) { saveHint = "先起个名字"; saveHintBad = true; return; }
        if (saveSelection.isEmpty()) { saveHint = "至少勾选一个部分"; saveHintBad = true; return; }
        try {
            StylePreset saved = service.saveCurrent(name, saveSelection); revision++;
            if (copy) {
                String text = service.shareText(saved); clipboard.set(text);
                setStatus("已保存「" + saved.meta().name() + "」并复制分享码（" + text.length() + " 字）", Tone.OK);
            } else setStatus("已保存「" + saved.meta().name() + "」· 卡片右侧可以复制分享码", Tone.OK);
            navigateMain(true);
        } catch (Throwable failure) { saveHint = "保存失败：" + failure.getMessage(); saveHintBad = true; }
    }
    public void copyShareCode(StylePreset preset) {
        try { String text = service.shareText(preset); clipboard.set(text); setStatus("已复制「" + preset.meta().name() + "」的分享码（" + text.length() + " 字）", Tone.OK); }
        catch (Throwable failure) { setStatus("生成分享码失败：" + failure.getMessage(), Tone.BAD); }
    }
    public static String deleteMessage(StylePreset preset) { return "将删除「" + preset.meta().name() + "」的文件；删了只能从分享码或备份的文件找回。"; }
    public void delete(StylePreset preset) {
        if (service.delete(preset)) { setStatus("已删除「" + preset.meta().name() + "」", Tone.INFO); revision++; }
        else setStatus("删除失败：文件可能已被移走", Tone.BAD);
    }
    public void importFromClipboard() {
        try { importText(clipboard.get()); } catch (Throwable failure) { setStatus("读取剪贴板失败：" + failure.getMessage(), Tone.BAD); }
    }
    public void importText(String text) {
        try {
            StylePreset imported = service.importText(text); revision++;
            setStatus("已导入「" + imported.meta().name() + "」· 在「我的预设」里", Tone.OK);
            if (navigator != null) navigator.showApply(imported); else enterApply(imported);
        } catch (PresetFormatException failure) { setStatus(failure.getMessage(), Tone.BAD); }
        catch (Throwable failure) { setStatus("导入失败：" + failure.getMessage(), Tone.BAD); }
    }
    public void importFile(File file) {
        picking = false;
        if (file == null) { setStatus("没有选择文件", Tone.INFO); return; }
        try {
            StylePreset imported = service.importFile(file); revision++;
            setStatus("已导入「" + imported.meta().name() + "」· 在「我的预设」里", Tone.OK);
            if (navigator != null) navigator.showApply(imported); else enterApply(imported);
        } catch (Throwable failure) { setStatus("导入失败：" + failure.getMessage(), Tone.BAD); }
    }
    public void browseForFile() {
        if (picking) return;
        if (host == null) { setStatus("宿主未提供文件选择器，请粘贴导入", Tone.BAD); return; }
        picking = true;
        try { host.chooseFile(this::importFile); }
        catch (Throwable failure) { picking = false; setStatus("打开文件选择器失败：" + failure.getMessage(), Tone.BAD); }
    }
    public void undo() {
        if (!service.canUndo()) { setStatus("还没有可以撤销的套用", Tone.INFO); return; }
        CompatReport report = service.undo();
        if (report == null) setStatus("备份读不出来，没法撤销", Tone.BAD);
        else if (report.hasFailures()) setStatus(report.failures().get(0), Tone.BAD);
        else setStatus("已撤销上一次套用" + (service.canUndo() ? " · 还可以继续往回撤" : ""), Tone.OK);
    }
    public void openFolder() {
        if (host != null && host.openFolder(service.repository().userDir())) setStatus("已打开预设目录", Tone.INFO);
        else setStatus("宿主未能打开预设目录", Tone.BAD);
    }
    public JsonObject stateJson() {
        JsonObject out = new JsonObject(); out.addProperty("protocolVersion", PresetService.PROTOCOL_VERSION);
        out.addProperty("page", page.name().toLowerCase(java.util.Locale.ROOT)); out.addProperty("statusText", statusText);
        out.addProperty("statusTone", statusTone.name().toLowerCase(java.util.Locale.ROOT)); out.addProperty("revision", revision);
        out.addProperty("canUndo", canUndo()); out.addProperty("previewChanges", previewChanges); out.addProperty("previewFailed", previewFailed);
        if (target != null) out.addProperty("targetId", target.meta().id());
        return out;
    }
    private void navigateMain(boolean backward) { page = Page.MAIN; if (navigator != null) navigator.showMain(backward); }
    private void setStatus(String text, Tone tone) { statusText = text == null ? "" : text; statusTone = tone; }
    private static Set<Facet> withAll(List<Facet> facets) { Set<Facet> out = EnumSet.noneOf(Facet.class); out.addAll(facets); return out; }
}
