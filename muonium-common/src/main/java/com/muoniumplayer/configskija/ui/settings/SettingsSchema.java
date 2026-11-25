package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.PageContext;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/** Optional presentation metadata, supplied by the host rather than inferred from setting names. */
public interface SettingsSchema {
    SettingsSchema DEFAULT = new SettingsSchema() {};
    interface Provider { SettingsSchema settingsSchema(); }
    enum Visualizer {
        SWITCH, CHECKBOX, SLIDER, RANGE_SLIDER, RANGE_NUMBER, INHERITABLE_SLIDER, NUMBER_CHAIN,
        NUMBER, TEXT, TEXT_LIST, FILE, FILE_LIST, COLOR_LIST, NUMBER_LIST, SLIDER_LIST,
        RANGE_SLIDER_LIST, RANGE_NUMBER_LIST, DROPDOWN, RADIO, COLOR, KEYBIND, BUTTON,
        DRAGGABLE_LIST, MULTI_SELECT, INFO, HUD_DROPDOWN, HUD_RADIO, ALIGNMENT
    }
    /** Mutable only while the host constructs it; getters/suppliers are evaluated each frame. */
    final class OptionStyle {
        public Visualizer visualizer;
        public Supplier<Boolean> visible = () -> true;
        public String icon = "", placeholder = "", regex = "", unit = "", buttonText = "Click";
        public String confirmText, inheritLabel = "Inherit", infoType = "info", addText = "Add";
        public boolean fullWidth, multiline, alpha = true, chroma, gradient, checkable = true;
        public boolean reorderable = true, singleKey, directory;
        public int maxEntries, lockedLeading;
        public Object inheritSentinel;
        public Supplier<Double> inheritedValue = () -> 0d;
        public DoubleFunction<String> format;
        public java.util.function.DoubleSupplier minimum, maximum, step;
        public java.util.function.Function<List<Double>, Double> nextValue;
        public List<Object> optionValues = List.of();
        public List<String> optionIcons = List.of(), filterPatterns = List.of();
        public String filterName = "";
        public OptionStyle(Visualizer visualizer) { this.visualizer = visualizer; }
        public static OptionStyle forOption(ConfigHost.Option option) {
            return new OptionStyle(switch(option.kind()) {
                case TOGGLE -> Visualizer.SWITCH; case SLIDER -> Visualizer.SLIDER;
                case CHOICE -> Visualizer.DROPDOWN; case COLOR -> Visualizer.COLOR;
                case ACTION -> Visualizer.BUTTON;
            });
        }
    }
    record GroupStyle(boolean accordion, boolean collapsed, ConfigHost.Option head, String description, String icon) {
        public static GroupStyle plain() { return new GroupStyle(false, false, null, "", ""); }
    }
    /** Immutable flags need a real host setter; Int colors deliberately do not acquire these flags. */
    record ColorValue(int argb, boolean chroma, double speed, boolean gradient, int gradientArgb) {}
    record Binding(List<Integer> keys, List<Integer> mouse, int modifiers, String displayName) {
        public Binding { keys = List.copyOf(keys); mouse = List.copyOf(mouse); }
        public static Binding none() { return new Binding(List.of(), List.of(), 0, "None"); }
    }
    default OptionStyle option(ConfigHost.Option option) { return OptionStyle.forOption(option); }
    default GroupStyle group(ConfigHost.Group group) { return GroupStyle.plain(); }
    default String text(String key, String fallback) { return fallback; }
    default int accentEnd(PageContext context) { return context.accent(); }
    default boolean locked() { return false; }
    default String lockExemptId() { return ""; }
    /** A file dialog runs off the render thread; null is cancellation. Callback must return on the UI thread. */
    default boolean chooseFile(ConfigHost.Option option, OptionStyle style, String current, Consumer<String> selected) { return false; }
    default boolean bindingConflict(ConfigHost.Option option) { return false; }
    default String keyName(int code) { return Integer.toString(code); }
    default String mouseName(int button) { return "Mouse " + (button + 1); }
    /** Native key bridge supplies modifier bits. No dependency on any loader or GLFW is introduced here. */
    default int modifierBit(int code) { return 0; }
    default double chromaCycleSeconds() { return 10; }
    /** Supply actual font metrics when integrating a custom font. */
    default double lineHeight(PageContext context, double size, boolean bold) { return size; }
}
