package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Bridge from MuoniumPlayer's HUD model to OneConfig v1.
 *
 * <p>This class deliberately has no compile-time dependency on OneConfig. The project still builds and
 * runs when OneConfig is absent; when it is installed, this bridge lazily builds a OneConfig
 * {@code Tree} from {@link HudSettingsModel} and opens the real OneConfig UI.</p>
 *
 * <p>The registered tree is marked {@code ui_only}. OneConfig therefore renders and edits it, but never
 * writes a second config file. Every property is functional and reads/writes MuoniumPlayer's live
 * settings, then calls {@link HudConfig#save()}.</p>
 */
public final class OneConfigHudBridge {
    private static final String TREE_ID = "muonium.hud";
    private static final String UI_CLASS = "org.polyfrost.oneconfig.api.ui.v1.OneConfigUI";
    private static final String BRIDGE_CLASS = "org.polyfrost.oneconfig.api.ui.v1.internal.OneConfigUIBridgeImpl";
    private static final String ROUTE_CLASS = "org.polyfrost.oneconfig.internal.ui.navigation.graph.ModConfigRoute";
    private static final String TREE_API = "org.polyfrost.oneconfig.api.config.v1.Tree";
    private static final String NODE_API = "org.polyfrost.oneconfig.api.config.v1.Node";
    private static final String PROPERTY_API = "org.polyfrost.oneconfig.api.config.v1.Property";
    private static final String PROPERTIES_API = "org.polyfrost.oneconfig.api.config.v1.Properties";
    private static final String CONFIG_MANAGER_API = "org.polyfrost.oneconfig.api.config.v1.ConfigManager";
    private static final String CONFIG_CATEGORY_API = "org.polyfrost.oneconfig.api.config.v1.Config$Category";

    private static Boolean available;
    private static boolean registered;
    private static boolean registrationFailed;

    private OneConfigHudBridge() {
    }

    /** Whether the complete OneConfig UI stack (API, implementation, and config route) is present. */
    public static boolean isAvailable() {
        if (available != null) return available;
        ClassLoader loader = OneConfigHudBridge.class.getClassLoader();
        try {
            Class.forName(UI_CLASS, false, loader);
            Class.forName(BRIDGE_CLASS, false, loader);
            Class.forName(ROUTE_CLASS, false, loader);
            Class.forName(PROPERTIES_API, false, loader);
            Class.forName(CONFIG_MANAGER_API, false, loader);
            available = Boolean.TRUE;
        } catch (Throwable ignored) {
            available = Boolean.FALSE;
        }
        return available;
    }

    /** Registers the tree if needed and opens OneConfig directly on MuoniumPlayer's HUD page. */
    public static boolean open() {
        if (!isAvailable()) return false;
        try {
            ensureRegistered();
            ClassLoader loader = OneConfigHudBridge.class.getClassLoader();
            Class<?> routeType = Class.forName(ROUTE_CLASS, true, loader);
            Constructor<?> constructor = routeType.getConstructor(String.class, String.class);
            Object route = constructor.newInstance(TREE_ID, null);
            Class<?> ui = Class.forName(UI_CLASS, true, loader);
            Method open = ui.getMethod("open", Object.class);
            open.invoke(null, route);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized void ensureRegistered() throws Exception {
        if (registered || registrationFailed) return;
        try {
            ClassLoader loader = OneConfigHudBridge.class.getClassLoader();
            Class<?> treeType = Class.forName(TREE_API, true, loader);
            Class<?> nodeType = Class.forName(NODE_API, true, loader);
            Class<?> propertiesType = Class.forName(PROPERTIES_API, true, loader);
            Class<?> configManagerType = Class.forName(CONFIG_MANAGER_API, true, loader);
            Class<?> categoryType = Class.forName(CONFIG_CATEGORY_API, true, loader);

            Method treeOf = treeType.getMethod("tree", String.class);
            Method setTitle = treeType.getMethod("setTitle", Object.class);
            Method addMetadata = nodeType.getMethod("addMetadata", String.class, Object.class);
            Method put = treeType.getMethod("put", nodeType);
            Method functional = propertiesType.getMethod("functional",
                    java.util.function.Supplier.class, java.util.function.Consumer.class,
                    String.class, Object.class, Object.class, Class.class);
            Method dummy = propertiesType.getMethod("dummy", String.class, String.class, String.class);
            Method activeManager = configManagerType.getMethod("active");
            Method register = configManagerType.getMethod("register", treeType);
            Object hudCategory = categoryType.getField("HUD").get(null);

            Object tree = treeOf.invoke(null, TREE_ID);
            setTitle.invoke(tree, "MuoniumPlayer HUD");
            addMetadata.invoke(tree, "mod_card_title", "MuoniumPlayer");
            addMetadata.invoke(tree, "mod_card_description", "MuoniumPlayer 的 HUD、歌词、灵动岛与播放器外观设置。");
            addMetadata.invoke(tree, "icon_name", "hud");
            addMetadata.invoke(tree, "category", hudCategory);
            addMetadata.invoke(tree, "ui_only", Boolean.TRUE);
            addMetadata.invoke(tree, "custom_save", (Runnable) HudConfig::save);

            addUiChoice(tree, functional, addMetadata, put);
            int entryIndex = 0;
            for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
                for (HudSettingsModel.Group group : category.groups) {
                    for (HudSettingsModel.Entry entry : group.entries) {
                        if (!entry.isVisible() || entry.kind == HudSettingsModel.Kind.THEME_GRID) continue;
                        Object property = createProperty(entry, entryIndex++, functional, dummy);
                        if (property == null) continue;
                        addMetadata.invoke(property, "category", category.title);
                        addMetadata.invoke(property, "subcategory", group.title);
                        put.invoke(tree, property);
                    }
                }
            }

            Object manager = activeManager.invoke(null);
            register.invoke(manager, tree);
            registered = true;
        } catch (Throwable failure) {
            registrationFailed = true;
            throw failure;
        }
    }

    private static void addUiChoice(Object tree, Method functional, Method addMetadata, Method put) throws Exception {
        Object property = functional.invoke(null,
                (java.util.function.Supplier<Object>) () -> isOneConfigSelected() ? "OneConfig" : "内置",
                (java.util.function.Consumer<Object>) value -> {
                    HudConfig.hudEditorUi = "OneConfig".equals(value)
                            ? HudConfig.HUD_EDITOR_UI_ONECONFIG : HudConfig.HUD_EDITOR_UI_BUILTIN;
                    HudConfig.save();
                },
                "hud_editor_ui", "HUD 设置界面",
                "选择打开 HUD 设置时使用 MuoniumPlayer 内置界面还是 OneConfig。未安装 OneConfig 时自动回退。",
                String.class);
        addMetadata.invoke(property, "visualizer", visualizer("DropdownVisualizer"));
        addMetadata.invoke(property, "options", Arrays.asList("内置", "OneConfig"));
        addMetadata.invoke(property, "optionValues", Arrays.asList("内置", "OneConfig"));
        addMetadata.invoke(property, "category", "界面");
        addMetadata.invoke(property, "subcategory", "启动方式");
        addMetadata.invoke(property, "default", "内置");
        put.invoke(tree, property);
    }

    private static Object createProperty(HudSettingsModel.Entry entry, int index,
                                         Method functional, Method dummy) throws Exception {
        String id = "hud_" + index;
        String title = entry.getLabel();
        String description = entry.description == null ? "" : entry.description;
        switch (entry.kind) {
            case SLIDER: {
                final HudSetting setting = entry.setting;
                Object property = functional.invoke(null,
                        (java.util.function.Supplier<Object>) setting::getValue,
                        (java.util.function.Consumer<Object>) value -> {
                            if (value instanceof Number) {
                                setting.setValue(((Number) value).floatValue());
                                HudConfig.save();
                            }
                        },
                        id, title, description, Float.class);
                addMetadataReflective(property, "visualizer", visualizer("SliderVisualizer"));
                addMetadataReflective(property, "min", setting.getMin());
                addMetadataReflective(property, "max", setting.getMax());
                addMetadataReflective(property, "step", 0.01f);
                addMetadataReflective(property, "default", setting.getValue());
                return property;
            }
            case TOGGLE: {
                Object property = functional.invoke(null,
                        (java.util.function.Supplier<Object>) entry::isOn,
                        (java.util.function.Consumer<Object>) value -> {
                            boolean enabled = Boolean.TRUE.equals(value);
                            if (entry.isOn() != enabled) entry.activate();
                            HudConfig.save();
                        },
                        id, title, description, Boolean.class);
                addMetadataReflective(property, "visualizer", visualizer("SwitchVisualizer"));
                addMetadataReflective(property, "default", entry.isOn());
                return property;
            }
            case COLOR: {
                Object property = functional.invoke(null,
                        (java.util.function.Supplier<Object>) entry::getColor,
                        (java.util.function.Consumer<Object>) value -> {
                            if (value instanceof Color) {
                                entry.setColor((Color) value);
                                HudConfig.save();
                            }
                        },
                        id, title, description, Color.class);
                addMetadataReflective(property, "visualizer", visualizer("ColorVisualizer"));
                addMetadataReflective(property, "default", entry.getColor());
                return property;
            }
            case CHOICE: {
                String[] choices = entry.getChoices();
                if (choices.length == 0) {
                    Object property = dummy.invoke(null, id, title, description);
                    addMetadataReflective(property, "visualizer", visualizer("ButtonVisualizer"));
                    addMetadataReflective(property, "runnable", (Runnable) entry::activate);
                    addMetadataReflective(property, "text", entry.getValueText());
                    return property;
                }
                Object property = functional.invoke(null,
                        (java.util.function.Supplier<Object>) entry::getValueText,
                        (java.util.function.Consumer<Object>) value -> {
                            entry.setChoice(String.valueOf(value));
                            HudConfig.save();
                        },
                        id, title, description, String.class);
                addMetadataReflective(property, "visualizer", visualizer("DropdownVisualizer"));
                addMetadataReflective(property, "options", Arrays.asList(choices));
                addMetadataReflective(property, "optionValues", Arrays.asList(choices));
                addMetadataReflective(property, "default", entry.getValueText());
                return property;
            }
            case ACTION: {
                Object property = dummy.invoke(null, id, title, description);
                addMetadataReflective(property, "visualizer", visualizer("ButtonVisualizer"));
                addMetadataReflective(property, "runnable", (Runnable) entry::activate);
                addMetadataReflective(property, "text", "执行");
                return property;
            }
            default:
                return null;
        }
    }

    private static void addMetadataReflective(Object node, String key, Object value) throws Exception {
        Class<?> nodeType = Class.forName(NODE_API, true, OneConfigHudBridge.class.getClassLoader());
        Method method = nodeType.getMethod("addMetadata", String.class, Object.class);
        method.invoke(node, key, value);
    }

    private static Object visualizer(String name) throws Exception {
        Class<?> type = Class.forName(
                "org.polyfrost.oneconfig.api.config.v1.Visualizer$" + name,
                true, OneConfigHudBridge.class.getClassLoader());
        return type.getDeclaredConstructor().newInstance();
    }

    private static boolean isOneConfigSelected() {
        return HudConfig.HUD_EDITOR_UI_ONECONFIG.equals(HudConfig.hudEditorUi);
    }
}