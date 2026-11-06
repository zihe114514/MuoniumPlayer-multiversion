package com.muoniumplayer.core.screens.hud;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 给 MuoniumConfig 的 HUD 设置面板构造「快捷配置」属性。
 *
 * <p>问题背景：{@link MuoniumHudProviders} 接进去的两块 HUD 只有位置与缩放，
 * 编辑器右侧面板因此显示「没有可用的设置」—— 而玩家真正想调的（歌词字号、发光、
 * 信息卡背景样式……）全在 {@link com.muoniumplayer.core.settings.HudConfig} 里。</p>
 *
 * <p>做法：MuoniumConfig 的 {@code NormalizedHudProvider.getLinkedProperties()} 允许宿主
 * 返回任意 {@code Property}，编辑器会把它们渲染成与内置项一样的控件。这里用反射构造，
 * 与桥接层其余部分保持同一套「不编译期依赖 MuoniumConfig」的约束。</p>
 *
 * <p>失败策略：任何一步抛异常都返回空列表，面板退回「只有位置与缩放」。
 * 少几个滑条不影响 HUD 使用，但让整块 HUD 从编辑器里消失就没法调位置了。</p>
 *
 * <p><b>不要写 category 元数据</b>：内置属性都没有 category，写了的会被分到另一个 tab，
 * 面板顶部就会多出一个只有我们这几项的分组（实测会看到空的 General 加一个 HUD tab）。
 * 只写 subcategory，属性就并进默认分组。</p>
 */
final class MuoniumHudProperties {

    private static final String PROPERTIES = "org.polyfrost.oneconfig.api.config.v1.Properties";
    private static final String PROPERTY = "org.polyfrost.oneconfig.api.config.v1.Property";
    private static final String VISUALIZER = "org.polyfrost.oneconfig.api.config.v1.Visualizer";
    private static final String NODE = "org.polyfrost.oneconfig.api.config.v1.Node";

    private MuoniumHudProperties() {
    }

    /** 浮点滑条。 */
    static final class Slider {
        final String id;
        final String label;
        final String description;
        final float min;
        final float max;
        final float step;
        final Supplier<Float> getter;
        final Consumer<Float> setter;
        /** 仅当它返回 true 时显示；null 表示始终显示。 */
        java.util.function.BooleanSupplier visibleWhen;

        Slider(String id, String label, String description, float min, float max, float step,
               Supplier<Float> getter, Consumer<Float> setter) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
        }

        Slider visibleWhen(java.util.function.BooleanSupplier condition) {
            this.visibleWhen = condition;
            return this;
        }
    }

    /** 开关。 */
    static final class Toggle {
        final String id;
        final String label;
        final String description;
        final Supplier<Boolean> getter;
        final Consumer<Boolean> setter;
        java.util.function.BooleanSupplier visibleWhen;

        Toggle(String id, String label, String description, Supplier<Boolean> getter, Consumer<Boolean> setter) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.getter = getter;
            this.setter = setter;
        }

        Toggle visibleWhen(java.util.function.BooleanSupplier condition) {
            this.visibleWhen = condition;
            return this;
        }
    }

    /**
     * 枚举式下拉。宿主用 int 存序号（HudConfig 里就是这么设计的：中间不能插项），
     * 这里负责把序号翻成给人看的标签，选中后再写回序号。
     */
    static final class Choice {
        final String id;
        final String label;
        final String description;
        final String[] options;
        final Supplier<Integer> getter;
        final Consumer<Integer> setter;
        java.util.function.BooleanSupplier visibleWhen;

        Choice(String id, String label, String description, String[] options,
               Supplier<Integer> getter, Consumer<Integer> setter) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.options = options;
            this.getter = getter;
            this.setter = setter;
        }

        Choice visibleWhen(java.util.function.BooleanSupplier condition) {
            this.visibleWhen = condition;
            return this;
        }
    }

    /** 声明一组快捷配置。 */
    static final class Set_ {
        final List<Slider> sliders = new ArrayList<>();
        final List<Toggle> toggles = new ArrayList<>();
        final List<Choice> choices = new ArrayList<>();

        Set_ slider(Slider s) {
            sliders.add(s);
            return this;
        }

        Set_ toggle(Toggle t) {
            toggles.add(t);
            return this;
        }

        Set_ choice(Choice c) {
            choices.add(c);
            return this;
        }

        List<Object> build() {
            return MuoniumHudProperties.build(this);
        }
    }

    static Set_ set() {
        return new Set_();
    }

    private static List<Object> build(Set_ decl) {
        if (decl.sliders.isEmpty() && decl.toggles.isEmpty() && decl.choices.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            ClassLoader loader = MuoniumHudProperties.class.getClassLoader();
            Class<?> propertiesType = Class.forName(PROPERTIES, true, loader);
            Class<?> nodeType = Class.forName(NODE, true, loader);

            Method functional = propertiesType.getMethod("functional",
                    Supplier.class, Consumer.class, String.class, Object.class, Object.class, Class.class);
            Method addMetadata = nodeType.getMethod("addMetadata", String.class, Object.class);

            List<Object> out = new ArrayList<>(decl.sliders.size() + decl.toggles.size() + decl.choices.size());

            for (Slider s : decl.sliders) {
                Object prop = functional.invoke(null, s.getter, s.setter,
                        s.id, s.label, s.description, Float.class);
                addMetadata.invoke(prop, "visualizer", visualizer("SliderVisualizer"));
                addMetadata.invoke(prop, "min", s.min);
                addMetadata.invoke(prop, "max", s.max);
                addMetadata.invoke(prop, "step", s.step);
                addMetadata.invoke(prop, "default", s.getter.get());
                addMetadata.invoke(prop, "subcategory", SUBCATEGORY);
                attachVisibility(prop, s.visibleWhen);
                out.add(prop);
            }

            for (Toggle t : decl.toggles) {
                Object prop = functional.invoke(null, t.getter, t.setter,
                        t.id, t.label, t.description, Boolean.class);
                addMetadata.invoke(prop, "visualizer", visualizer("SwitchVisualizer"));
                addMetadata.invoke(prop, "default", t.getter.get());
                addMetadata.invoke(prop, "subcategory", SUBCATEGORY);
                attachVisibility(prop, t.visibleWhen);
                out.add(prop);
            }

            for (Choice c : decl.choices) {
                // 属性值是标签字符串（编辑器按字符串比对选项），写回时再翻回序号
                Supplier<String> getLabel = () -> {
                    int idx = c.getter.get();
                    return idx >= 0 && idx < c.options.length ? c.options[idx] : c.options[0];
                };
                Consumer<String> setLabel = label -> {
                    for (int i = 0; i < c.options.length; i++) {
                        if (c.options[i].equals(label)) {
                            c.setter.accept(i);
                            return;
                        }
                    }
                };
                Object prop = functional.invoke(null, getLabel, setLabel,
                        c.id, c.label, c.description, String.class);
                addMetadata.invoke(prop, "visualizer", visualizer("DropdownVisualizer"));
                addMetadata.invoke(prop, "options", Arrays.asList(c.options));
                addMetadata.invoke(prop, "optionValues", Arrays.asList(c.options));
                addMetadata.invoke(prop, "default", getLabel.get());
                addMetadata.invoke(prop, "subcategory", SUBCATEGORY);
                attachVisibility(prop, c.visibleWhen);
                out.add(prop);
            }

            // 诊断：确认这些属性真的带上了 visualizer 与 SHOWN 状态。
            // 面板按 visualizer 是否存在决定渲不渲染，按 Display 决定显不显示，
            // 两者任一不满足都会表现为「右侧空的」而与「没传属性」无法区分。
            if (Boolean.getBoolean("muonium.hudprops.debug")) {
                Class<?> nodeT = Class.forName(NODE, true, loader);
                Method getMeta = nodeT.getMethod("getMetadata", String.class);
                for (Object o : out) {
                    Object vis = getMeta.invoke(o, "visualizer");
                    Object disp = o.getClass().getMethod("getDisplay").invoke(o);
                    System.out.println("[HudProps] " + o.getClass().getSimpleName()
                            + " visualizer=" + (vis == null ? "null" : vis.getClass().getSimpleName())
                            + " display=" + disp);
                }
            }
            return out;
        } catch (Throwable t) {
            // MuoniumConfig 不在、或版本对不上：面板退回只有位置与缩放。
            // 必须留下日志：这条路径静默失败的表现是「右侧面板空的」，与「没写属性」
            // 完全一样，不打日志就没法区分。
            System.out.println("[MuoniumPlayer/HudProps] 构造快捷配置失败：" + t);
            t.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * 把宿主给的「何时显示」接成编辑器的 display condition。
     *
     * <p>面板每次重绘都会问一遍条件，所以宿主切换歌词样式后，另一套专属项当场消失/出现，
     * 不需要重开界面。条件抛异常按「显示」处理：宁可多显示一项，也别让配置项莫名消失。</p>
     */
    private static void attachVisibility(Object prop,
                                        java.util.function.BooleanSupplier visibleWhen) throws Exception {
        if (visibleWhen == null) return;
        // addDisplayCondition 定义在 Property 上（不是 Node），返回泛型 Property<T>，
        // 反射时按 Property 声明的方法找即可
        Class<?> propertyType = Class.forName(PROPERTY, true,
                MuoniumHudProperties.class.getClassLoader());
        Class<?> displayEnum = Class.forName(
                "org.polyfrost.oneconfig.api.config.v1.Property$Display", true,
                MuoniumHudProperties.class.getClassLoader());
        Object hidden = displayEnum.getField("HIDDEN").get(null);
        Object shown = displayEnum.getField("SHOWN").get(null);

        Supplier<Object> condition = () -> {
            try {
                return visibleWhen.getAsBoolean() ? shown : hidden;
            } catch (Throwable t) {
                return shown;
            }
        };
        propertyType.getMethod("addDisplayCondition", Supplier.class).invoke(prop, condition);
    }

    private static Object visualizer(String simpleName) throws Exception {
        Class<?> type = Class.forName(VISUALIZER + "$" + simpleName, true,
                MuoniumHudProperties.class.getClassLoader());
        return type.getDeclaredConstructor().newInstance();
    }

    private static final String SUBCATEGORY = "快捷配置";
}
