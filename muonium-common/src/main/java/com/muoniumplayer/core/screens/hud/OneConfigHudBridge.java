package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 从 MuoniumPlayer 的 HUD 模型到 OneConfig v1 的桥。
 *
 * <p>本类刻意不依赖 OneConfig 的编译期。项目在没有 OneConfig 时仍能构建并
 * 运行；安装了它时，本桥从 {@link HudSettingsModel} 惰性构建一个 OneConfig
 * {@code Tree}，并打开真正的 OneConfig UI。</p>
 *
 * <p>注册的树标记为 {@code ui_only}。因此 OneConfig 渲染并编辑它，但从不
 * 写第二个配置文件。每个属性都是功能性的，读写 MuoniumPlayer 的实时
 * 设置，然后调用 {@link HudConfig#save()}。</p>
 *
 * <h2>全部条目一次性注册，首次打开就完整</h2>
 *
 * <p>曾经的写法是「注册那一刻不满足 {@code isVisible()} 或 {@code isLocked()} 就
 * {@code continue}」。这在条件项上是致命的：3D 歌词总开关出厂为关，它的二十多个子项
 * 全都挂着 {@code visibleWhen(3D 歌词开)}，于是首次打开设置时整页只剩总开关与三个不
 * 带条件的项；而树只注册一次、之后不再重建，用户在界面里把 3D 打开也补不回来，
 * 必须重启游戏才可能看到 —— 这就是「配置项展示不全」的根因。</p>
 *
 * <p>现在一律全量注册，<b>一条都不删</b>。前置条件没满足的项也整行显示，只是置灰并
 * 在说明里写清原因（玩家能看见这里有什么、为什么现在动不了）。值一旦变化就
 * {@code revaluateDisplay()} 重算一遍，开关打开后对应项当场解锁，不需要重开界面、
 * 更不需要重启游戏。</p>
 *
 * <p>{@code THEME_GRID} 仍然不注册：它是色卡网格这种专用控件，宿主没有等价物；
 * 同一组里的「播放器主题」下拉已经是它的完整等价入口（同一个
 * {@code NCMTheme.setCurrent}），再注册一遍只是重复一件控件。</p>
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

    /**
     * 注册进树的全部属性：写值之后要拿它们重算显隐。
     *
     * <p>宿主的 {@code Property} 把显示状态算好缓存在 {@code display} 字段里，只在
     * {@code revaluateDisplay()} 被调用时更新；不主动重算，「把 3D 总开关打开后子项当场
     * 出现」就不会发生。注册只在客户端线程做一次，之后只读遍历，因此普通 {@link ArrayList} 够用。</p>
     */
    /**
     * 注册进树的一条属性，连同它对应的模型条目。
     *
     * <p>写值之后要拿它们重算显隐；刷新「为什么置灰」的说明时还要回头问模型的锁定原因。</p>
     */
    private static final class Tracked {
        final Object property;
        final HudSettingsModel.Entry entry;
        /** {@code Node.description} 字段：宿主界面直接读它，改它就能改那行小字。 */
        final java.lang.reflect.Field descriptionField;
        /** 注册时写进树的原始说明，解锁后要换回来。 */
        final String baseDescription;
        /** 上一次写进去的说明，避免每次重算都重复反射赋值。 */
        String lastDescription;

        Tracked(Object property, HudSettingsModel.Entry entry, java.lang.reflect.Field descriptionField,
                String baseDescription) {
            this.property = property;
            this.entry = entry;
            this.descriptionField = descriptionField;
            this.baseDescription = baseDescription;
            this.lastDescription = baseDescription;
        }
    }

    private static final List<Tracked> displayTracked = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** {@code Property.revaluateDisplay()}；解析不到时为 null（宿主太老，退回注册时过滤）。 */
    private static Method revaluateDisplay;
    /** 宿主是否提供动态显隐/禁用；null = 还没解析过。 */
    private static Boolean displayApiReady;
    /**
     * 宿主 {@code Node.description} 字段：界面直接读它显示那行小字。
     *
     * <p>解析不到也不影响主体功能 —— 只是「为什么置灰」显示不出来，退回原始说明。</p>
     */
    private static java.lang.reflect.Field descriptionField;

    private static java.lang.reflect.Field resolveDescriptionField(Class<?> propertyType) {
        try {
            java.lang.reflect.Field field = propertyType.getField("description");
            return field;
        } catch (Throwable absent) {
            return null;
        }
    }
    /** {@code Property$Display.SHOWN / HIDDEN / DISABLED}。 */
    private static Object displayShown;
    private static Object displayHidden;
    private static Object displayDisabled;

    private OneConfigHudBridge() {
    }

    /**
     * 一条模型条目在宿主里应该处于什么状态。
     *
     * <p>这个枚举只是把宿主 {@code Property$Display} 的三个取值搬一份到本工程里，
     * 好让注册策略在没有 OneConfig 的测试环境里也能被断言 —— 桥的其余部分仍是纯反射。</p>
     */
    public enum DisplayState {
        /** 正常显示、可操作。 */
        SHOWN,
        /** 整行不出现。本桥不再使用，保留只为兼容旧宿主语义。 */
        HIDDEN,
        /** 显示但置灰、拦交互（前置条件还没满足，或当前环境用不了）。 */
        DISABLED
    }

    /**
     * 这条目要不要进树。
     *
     * <p>只有色卡网格被排除：它要画的是一整片颜色方块网格，宿主没有等价控件，而同一组里的
     * 「播放器主题」下拉走同一个 {@code NCMTheme.setCurrent}，已经是它的完整入口。</p>
     *
     * <p><b>其余一律注册</b>，包括当下不可见、当下锁死的。把它们排除掉正是 3D 歌词
     * 栏目只剩 4 项的根因：总开关出厂为关，二十多个子项当场全被扔掉，而树只注册一次。</p>
     */
    public static boolean shouldRegister(HudSettingsModel.Entry entry) {
        return entry != null && entry.kind != HudSettingsModel.Kind.THEME_GRID;
    }

    /**
     * 条目此刻的显示状态。
     *
     * <p><b>一律 DISABLED，绝不 HIDDEN。</b>这是有意的取舍：玩家要的是「打开界面就看到
     * 全部选项」。把前置条件没满足的项藏起来，看起来像少了功能；整行显示出来并置灰，
     * 玩家既知道这里有什么，也知道为什么现在动不了（说明里会写原因）。</p>
     *
     * <p>谓词自己抛异常时按「可用」处理 —— 少一项比整页凭空消失难排查得多。</p>
     */
    public static DisplayState displayStateOf(HudSettingsModel.Entry entry) {
        if (entry == null) return DisplayState.SHOWN;
        try {
            if (!entry.isVisible()) return DisplayState.DISABLED;
            if (entry.isLocked()) return DisplayState.DISABLED;
        } catch (Throwable broken) {
            return DisplayState.SHOWN;
        }
        return DisplayState.SHOWN;
    }

    /** 完整 OneConfig UI 栈（API、实现和配置路由）是否存在。 */
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

    /** 按需注册树，并在 MuoniumPlayer 的 HUD 页面上直接打开 OneConfig。 */
    public static boolean open() {
        if (!isAvailable()) {
            // 明确区分「没装」和「装了但打不开」：以前两者都只返回 false，
            // 调用方静默回退到内置界面，排查时完全看不出是哪一层的问题。
            System.out.println("[MuoniumPlayer/OneConfigBridge] open(): isAvailable()=false，"
                    + "OneConfig UI 栈不在类路径上（MuoniumConfig 未加载）");
            return false;
        }
        try {
            ensureRegistered();
            ClassLoader loader = OneConfigHudBridge.class.getClassLoader();
            Class<?> routeType = Class.forName(ROUTE_CLASS, true, loader);
            Constructor<?> constructor = routeType.getConstructor(String.class, String.class);
            Object route = constructor.newInstance(TREE_ID, null);
            Class<?> ui = Class.forName(UI_CLASS, true, loader);
            Method open = ui.getMethod("open", Object.class);
            open.invoke(null, route);
            System.out.println("[MuoniumPlayer/OneConfigBridge] open(): 已请求打开 route=" + TREE_ID);
            return true;
        } catch (Throwable t) {
            // 这里必须留日志：MuoniumConfig 装了但某个类/方法对不上时，
            // 静默回退会让人以为是「没装」，实际是版本不匹配。
            System.out.println("[MuoniumPlayer/OneConfigBridge] open(): 失败 —— " + t);
            t.printStackTrace(System.out);
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

            Class<?> propertyType = Class.forName(PROPERTY_API, true, loader);
            Class<?> displayType = Class.forName(PROPERTY_API + "$Display", true, loader);
            resolveDisplayApi(propertyType, displayType);
            descriptionField = resolveDescriptionField(propertyType);

            Object tree = treeOf.invoke(null, TREE_ID);
            setTitle.invoke(tree, "MuoniumPlayer HUD");
            addMetadata.invoke(tree, "mod_card_title", "MuoniumPlayer");
            addMetadata.invoke(tree, "mod_card_description", "MuoniumPlayer 的 HUD、歌词、灵动岛与播放器外观设置。");
            addMetadata.invoke(tree, "icon_name", "hud");
            addMetadata.invoke(tree, "category", hudCategory);
            addMetadata.invoke(tree, "ui_only", Boolean.TRUE);
            addMetadata.invoke(tree, "custom_save", (Runnable) HudConfig::save);

            int entryIndex = 0;
            for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
                for (HudSettingsModel.Group group : category.groups) {
                    for (HudSettingsModel.Entry entry : group.entries) {
                        if (!shouldRegister(entry)) continue;
                        // 一律注册：即使宿主没有动态置灰能力，也要把全部选项摆出来，
                        // 宁可让一行暂时可点（行为闸仍在 entry.isLocked() 里），
                        // 也不要让玩家看到一个缺项的设置页。
                        Object property = createProperty(entry, entryIndex++, functional, dummy);
                        if (property == null) continue;
                        String baseDescription = entry.description == null ? "" : entry.description;
                        attachDisplayConditions(property, entry, baseDescription);
                        addMetadata.invoke(property, "category", category.title);
                        addMetadata.invoke(property, "subcategory", group.title);
                        put.invoke(tree, property);
                    }
                }
            }
            // 「界面」排最后：它只有一行（选哪套设置界面渲染），属于「装好之后基本不再动」的
            // 配置。放在第一位会挤掉真正高频的分类 —— 标签栏的顺序就是属性的插入顺序。
            addUiChoice(tree, functional, addMetadata, put);


            Object manager = activeManager.invoke(null);
            register.invoke(manager, tree);
            registered = true;
            // 注册时就把「为什么置灰」写进说明：否则首次打开界面看到的是一排灰控件、
            // 一句原因都没有，用户只会以为坏了。
            revaluateAllDisplays();
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
                            if (entry.isLocked()) return; // 锁死行：滑条不走 activate，直写也要过闸
                            if (value instanceof Number) {
                                setting.setValue(((Number) value).floatValue());
                                afterValueChanged();
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
                            afterValueChanged();
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
                            if (entry.isLocked()) return; // 锁死行：颜色直写同样过闸
                            if (value instanceof Color) {
                                entry.setColor((Color) value);
                                afterValueChanged();
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
                    addMetadataReflective(property, "runnable", (Runnable) () -> {
                        entry.activate();
                        revaluateAllDisplays();
                    });
                    addMetadataReflective(property, "text", entry.getValueText());
                    return property;
                }
                Object property = functional.invoke(null,
                        (java.util.function.Supplier<Object>) entry::getValueText,
                        (java.util.function.Consumer<Object>) value -> {
                            entry.setChoice(String.valueOf(value));
                            afterValueChanged();
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
                // 动作可能改掉整片配置（恢复默认、清缓存、切主题），执行完重算一次显隐。
                addMetadataReflective(property, "runnable", (Runnable) () -> {
                    entry.activate();
                    revaluateAllDisplays();
                });
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

    /**
     * 解析宿主的动态显隐 API（{@code Property.revaluateDisplay} + {@code Display} 枚举）。
     *
     * <p>解析不到不算失败：条目照样全部注册（见注册循环的注释），只是没有置灰效果 ——
     * 老宿主上仍然能看到全部选项，行为闸由 {@code entry.isLocked()} 与各 setter 自己把住。</p>
     */
    private static void resolveDisplayApi(Class<?> propertyType, Class<?> displayType) {
        try {
            revaluateDisplay = propertyType.getMethod("revaluateDisplay");
            displayShown = displayType.getField("SHOWN").get(null);
            displayHidden = displayType.getField("HIDDEN").get(null);
            displayDisabled = displayType.getField("DISABLED").get(null);
            displayApiReady = Boolean.TRUE;
        } catch (Throwable unsupported) {
            revaluateDisplay = null;
            displayShown = null;
            displayHidden = null;
            displayDisabled = null;
            displayApiReady = Boolean.FALSE;
            System.out.println("[MuoniumPlayer/OneConfigBridge] 宿主没有动态置灰 API，"
                    + "全部条目仍然注册，只是不显示置灰状态：" + unsupported);
        }
    }

    /**
     * 把模型的可操作性谓词接到宿主的显示条件上。
     *
     * <p><b>只产出 SHOWN 或 DISABLED，绝不用 HIDDEN。</b>玩家要的是「第一次打开就看到
     * 全部选项」：前置条件没满足的项也整行显示，只是置灰并写明原因。这样既不会误以为
     * 功能缺失，也能立刻明白要先开哪一项。</p>
     */
    private static void attachDisplayConditions(Object property, HudSettingsModel.Entry entry,
                                                String baseDescription) throws Exception {
        if (!Boolean.TRUE.equals(displayApiReady)) return;
        final Supplier<Object> condition = () -> {
            switch (displayStateOf(entry)) {
                case DISABLED:
                    return displayDisabled;
                default:
                    return displayShown;
            }
        };
        Class<?> propertyType = Class.forName(PROPERTY_API, true, OneConfigHudBridge.class.getClassLoader());
        propertyType.getMethod("addDisplayCondition", Supplier.class).invoke(property, condition);
        displayTracked.add(new Tracked(property, entry, descriptionField, baseDescription));
    }

    /**
     * 值变了之后重算所有属性的显示状态。
     *
     * <p>宿主的 {@code Property.display} 是缓存字段，只在 {@code revaluateDisplay()} 被调用时
     * 更新；界面又只在收到显示状态变更通知时重建列表。所以「开 3D 总开关 → 子项当场出现」
     * 必须靠这里主动推一把，否则要等下次打开界面。</p>
     *
     * <p>一次写值会让几十个属性重算一遍，但每个条件只是一两次字段读，代价可以忽略；
     * 相比「用户改完看不到，以为功能坏了」，这个方向明显更值。</p>
     */
    private static void revaluateAllDisplays() {
        if (!Boolean.TRUE.equals(displayApiReady) || revaluateDisplay == null) return;
        for (Tracked tracked : displayTracked) {
            try {
                // 置灰的项要能说清为什么灰：宿主实时读 description，这里把原因换上去。
                refreshDescription(tracked);
                revaluateDisplay.invoke(tracked.property);
            } catch (Throwable ignored) {
                // 单个属性重算失败不影响其余项。
            }
        }
    }

    /**
     * 把「为什么置灰」写进这条属性的说明。
     *
     * <p>宿主的描述是 {@code Node.description} 这个 <b>public transient 字段</b>，
     * 界面每次取 {@code localizedDescription()} 都直接读它（外加 {@code descriptionKey}
     * metadata 做 i18n）。所以直接改字段就等于改界面上那行小字，不需要重建树。
     * 恢复可编辑时换回原始说明，不留下陈旧的原因。</p>
     */
    private static void refreshDescription(Tracked tracked) {
        try {
            boolean locked = displayStateOf(tracked.entry) == DisplayState.DISABLED;
            // 置灰项必须说一句为什么，否则一整行灰控件看不出它为什么点不动。
            // 不可见的那类（前置开关没开）与锁死的那类（当前环境不适用）原因不同，分开写。
            String hint = null;
            if (locked) {
                hint = tracked.entry.isVisible()
                        ? "这一项在当前环境不可用。"
                        : "先把相关的开关打开，这一项才生效。";
            }
            String text;
            if (hint == null) {
                text = tracked.baseDescription;
            } else {
                text = tracked.baseDescription == null || tracked.baseDescription.isEmpty()
                        ? hint
                        : tracked.baseDescription + "\n" + hint;
            }
            if (!text.equals(tracked.lastDescription)) {
                tracked.lastDescription = text;
                if (tracked.descriptionField != null) {
                    tracked.descriptionField.set(tracked.property, text);
                }
            }
        } catch (Throwable ignored) {
            // 描述刷新失败不该影响显隐重算。
        }
    }

    /** 每个写值入口在 {@link HudConfig#save()} 之后调一次，让条件项当场跟着出现/消失。 */
    private static void afterValueChanged() {
        HudConfig.save();
        revaluateAllDisplays();
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