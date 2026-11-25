package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.KeyBindings;
import java.util.List;
import java.util.Set;

/** Optional host capabilities which cannot be inferred from settings categories. No capability is simulated. */
public final class LibraryServices {
    private LibraryServices() {}
    public interface Keys { KeyBindings keyBindings(); }
    public interface Information {
        String playerVersion();
        String configVersion();
    }
    public interface ProfileExtras {
        Set<String> capabilities();
        default boolean separateControls() { return true; }
        default void separateControls(boolean enabled) { throw unsupported(); }
        default void openProfilesFolder() { throw unsupported(); }
        default void exportProfile(String id) { throw unsupported(); }
        default String profileIcon(String id) { return "profiles"; }
        default void profileIcon(String id, String icon) { throw unsupported(); }
        default KeyBindings.Entry profileKey(String id) { return null; }
        private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("宿主尚未接入此配置能力"); }
    }
    public interface PresetExtras {
        default boolean busy() { return false; }
        default String facetDescription(String id) { return ""; }
        default String cardSummary(String id) { return ""; }
        default int changedKeys() { return -1; }
        default boolean previewOk() { return true; }
        default boolean canOpenFolder() { return false; }
        default boolean canImportFile() { return false; }
        default void openPresetsFolder() { throw new UnsupportedOperationException("宿主尚未接入模板目录"); }
        default void importPresetFile() { throw new UnsupportedOperationException("宿主尚未接入文件选择"); }
    }
    public interface ModCatalog { List<LibraryMods.Card> modCards(); }
}
