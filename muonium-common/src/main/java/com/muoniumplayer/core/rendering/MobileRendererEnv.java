package com.muoniumplayer.core.rendering;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 移动端「渲染器环境」识别：FCL / Zalith 家族启动器 + 渲染器（MobileGlues / MobileGL /
 * NG-GL4ES / Holy-GL4ES / Zink / VirGL / Freedreno / Panfrost）。
 *
 * <h2>为什么不能只用「是不是安卓」</h2>
 * {@link GlassMobileTier} 的老判定只有「安卓 / 非安卓」一档，但真机上渲染器之间差异巨大：
 * MobileGlues / MobileGL 是 GL 翻译层（提示层与玻璃策略要对它们特别关照），Zink / VirGL /
 * Freedreno 则是 Mesa 真桌面 GL（真玻璃的实验档只在它们身上才有意义）。识别结果同时供
 * 提示文案按渲染器×游戏版本路由（26.3+ 的正解是 MobileGL，MobileGlues 建议只对旧树成立）。
 *
 * <h2>判据（全部来自启动器与渲染器源码，2026-10-26 调研沉淀）</h2>
 * 两个启动器对任何渲染器都会设：
 * <ul>
 *   <li>{@code POJAVEXEC_EGL}（env）：EGL 库名——{@code libmobileglues.so}（MobileGlues 插件）、
 *       {@code libEGL_mesa.so}（Zink/VirGL/Freedreno）、{@code libMobileGL.so}（MobileGL）。
 *       ZL2 自己判定 MobileGlues 用的正是它；</li>
 *   <li>{@code org.lwjgl.opengl.libname}（JVM 属性）：LWJGL 的 GL 门——
 *       {@code libmobileglues.so} / {@code libMobileGL.so} / {@code libng_gl4es.so}（NG-GL4ES，
 *       ZL2 与 FCL 内置「Krypton Wrapper」）/ {@code libgl4es_114.so}（Holy-GL4ES）/
 *       {@code libglxshim.so}（Zink，整个库只导出 glXGetProcAddress 一个符号）/
 *       {@code libOSMesa_81.so}+{@code VTEST_SOCKET_NAME}（VirGL）、
 *       {@code libOSMesa_8.so}（Freedreno）、{@code libOSMesa_2300d.so}（Panfrost）；</li>
 *   <li>{@code POJAV_RENDERER}（env）：{@code opengles3} 被 MobileGlues / MobileGL /
 *       NG-GL4ES 三家共用，只能当兜底消歧，不能当主判据；</li>
 *   <li>{@code MOBILEGL_BACKEND_TYPE}（env，MobileGL 专属）：DirectGLES / DirectVulkan；</li>
 *   <li>启动器家族：属性 {@code pojav.path.minecraft}（ZL2 设），env {@code FCL_NATIVEDIR} /
 *       {@code FCL_VERSION_CODE}（FCL 设）。</li>
 * </ul>
 * 刻意<b>不用</b> GL_VENDOR/GL_RENDERER 字符串参与判定：MobileGlues 有「隐藏 MG 环境」
 * 开关，开启后这些串会被随机化；env/属性没有对应的伪装面。
 *
 * <h2>工程约束</h2>
 * 判定在类加载时定死并打一行启动日志（含命中判据）；判定本体收
 * {@code (Properties, Map)} 纯参数，单元测试可以直接喂合成环境。
 * 逃生门 {@code -Dmuonium.renderer.override=<id>}，id 用 {@link #id()} 值。
 */
public final class MobileRendererEnv {

    /** 识别结果。UNKNOWN 不等于桌面：只是「没有启动器指纹」，桌面通常落在这里。 */
    public enum Kind {
        /** MobileGlues 翻译层（渲染器插件，1.17+ 不限版本；POJAVEXEC_EGL=libmobileglues.so）。 */
        MOBILEGLUES("mobileglues", "MobileGlues"),
        /** MobileGL（GL 4.2，26.3+ 的正解；DirectGLES / DirectVulkan 双后端）。 */
        MOBILEGL("mobilegl", "MobileGL"),
        /** NG-GL4ES（Krypton Wrapper，两启动器内置，上限 26.2，可能弄坏世界贴图）。 */
        NG_GL4ES("ng_gl4es", "NG-GL4ES"),
        /** Holy-GL4ES（旧内置，上限 1.21.4）。 */
        HOLY_GL4ES("holy_gl4es", "Holy-GL4ES"),
        /** Zink/Kopper（Mesa 真桌面 GL over Vulkan，libglxshim 门）。 */
        ZINK("zink", "Zink"),
        /** VirGL（Mesa 虚拟 GL，libOSMesa + vtest 门）。 */
        VIRGL("virgl", "VirGL"),
        /** Freedreno（Adreno 真驱动，Mesa 门）。 */
        FREEDRENO("freedreno", "Freedreno"),
        /** Panfrost（Mali 真驱动，Mesa 门，仅 ZL2）。 */
        PANFROST("panfrost", "Panfrost"),
        /** 未见渲染器指纹：桌面，或启动器不在已知家族里。 */
        UNKNOWN("unknown", "默认");

        /** 稳定 id，逃生门属性与配置里用它。 */
        public final String id;
        /** 给人看的名字，提示文案直接用。 */
        public final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    private static final Env CURRENT = detect(
            System.getProperties(), System.getenv(),
            System.getProperty("os.name", ""), System.getProperty("os.version", ""));

    /** 当前环境的识别结果，类加载时定死，每帧只读。 */
    public static Env current() {
        return CURRENT;
    }

    /** 仅日志与测试用：识别结果 + 命中判据。 */
    public record Env(Kind kind, String hit, boolean launcherFamily) {

        /** 给提示层用的显示名。 */
        public String displayName() {
            return kind.displayName;
        }
    }

    /** 测试门面：与 {@link #detect(Properties, Map, String, String)} 同参，包外可调。 */
    public static Env detectForTest(Properties properties, Map<String, String> env,
                                    String osName, String osVersion) {
        return detect(properties, env, osName, osVersion);
    }

    private static Env detect(Properties properties, Map<String, String> env,
                              String osName, String osVersion) {
        String forced = properties.getProperty("muonium.renderer.override", "").trim();
        if (!forced.isEmpty()) {
            for (Kind kind : Kind.values()) {
                if (kind.id.equalsIgnoreCase(forced)) {
                    return new Env(kind, "JVM 属性 muonium.renderer.override", true);
                }
            }
        }
        String hit = detectHit(properties, env);
        if (hit == null) {
            return new Env(Kind.UNKNOWN, null, launcherFamily(properties, env, osVersion) != null);
        }
        return new Env(kindOf(hit, properties, env, osName), hit,
                launcherFamily(properties, env, osVersion) != null);
    }

    /**
     * 按信号强度逐条试，返回「判据名:值」；全部落空返回 null。
     * 顺序 = 证据强度：EGL 门最专一，GL 门次之，POJAV_RENDERER 只能收尾消歧。
     */
    private static String detectHit(Properties properties, Map<String, String> env) {
        // 1. POJAVEXEC_EGL：MobileGlues 插件与 MobileGL 插件各设各的 EGL 名。
        String egl = basename(env.get("POJAVEXEC_EGL"));
        if ("libmobileglues.so".equals(egl)) {
            return "POJAVEXEC_EGL:" + egl;
        }
        if ("libMobileGL.so".equals(egl)) {
            return "POJAVEXEC_EGL:" + egl;
        }
        // 2. org.lwjgl.opengl.libname：GL 门库名，覆盖所有内置渲染器与插件渲染器。
        String gl = basename(properties.getProperty("org.lwjgl.opengl.libname", ""));
        if ("libmobileglues.so".equals(gl)) {
            return "opengl.libname:" + gl;
        }
        if ("libMobileGL.so".equals(gl)) {
            return "opengl.libname:" + gl;
        }
        if ("libng_gl4es.so".equals(gl)) {
            return "opengl.libname:" + gl;
        }
        if ("libgl4es_114.so".equals(gl)) {
            return "opengl.libname:" + gl;
        }
        if ("libglxshim.so".equals(gl)) {
            return "opengl.libname:" + gl;
        }
        if (gl.toLowerCase(Locale.ROOT).startsWith("libosmesa")) {
            return "opengl.libname:" + gl;
        }
        // 3. POJAV_RENDERER 收尾消歧：只认唯一指向的值。
        String renderer = env.getOrDefault("POJAV_RENDERER", "");
        if ("opengles3_desktopgl_zink_kopper".equals(renderer)) {
            return "POJAV_RENDERER:" + renderer;
        }
        if ("gallium_virgl".equals(renderer)) {
            return "POJAV_RENDERER:" + renderer;
        }
        if ("gallium_freedreno".equals(renderer)) {
            return "POJAV_RENDERER:" + renderer;
        }
        if ("gallium_panfrost".equals(renderer)) {
            return "POJAV_RENDERER:" + renderer;
        }
        return null;
    }

    private static Kind kindOf(String hit, Properties properties, Map<String, String> env,
                               String osName) {
        String value = hit.substring(hit.indexOf(':') + 1);
        if (hit.startsWith("POJAVEXEC_EGL:") || hit.startsWith("opengl.libname:")) {
            switch (value.toLowerCase(Locale.ROOT)) {
                case "libmobileglues.so":
                    return Kind.MOBILEGLUES;
                case "libmobilegl.so":
                    return Kind.MOBILEGL;
                case "libng_gl4es.so":
                    return Kind.NG_GL4ES;
                case "libgl4es_114.so":
                    return Kind.HOLY_GL4ES;
                case "libglxshim.so":
                    return Kind.ZINK;
                default:
                    // libOSMesa 系：看 POJAV_RENDERER 定 VirGL/Freedreno/Panfrost。
                    String renderer = env.getOrDefault("POJAV_RENDERER", "");
                    if ("gallium_freedreno".equals(renderer)) {
                        return Kind.FREEDRENO;
                    }
                    if ("gallium_panfrost".equals(renderer)) {
                        return Kind.PANFROST;
                    }
                    if ("gallium_virgl".equals(renderer)
                            || env.get("VTEST_SOCKET_NAME") != null) {
                        return Kind.VIRGL;
                    }
                    // OSMesa 系在两启动器里当前只有 VirGL（81）/Freedreno（8）/Panfrost（2300d）
                    // 三个变体；认不出变体时按最老的 VirGL 处理。
                    return Kind.VIRGL;
            }
        }
        // POJAV_RENDERER 命中的四个唯一值。
        if (value.contains("zink")) {
            return Kind.ZINK;
        }
        if (value.contains("freedreno")) {
            return Kind.FREEDRENO;
        }
        if (value.contains("panfrost")) {
            return Kind.PANFROST;
        }
        return Kind.VIRGL;
    }

    /** 是否 FCL / Zalith（pojav 家族）启动器：识别不到渲染器时它决定「要不要给移动端建议」。 */
    private static String launcherFamily(Properties properties, Map<String, String> env,
                                         String osVersion) {
        if (properties.getProperty("pojav.path.minecraft") != null) {
            return "pojav.path.minecraft";
        }
        if (env.get("FCL_NATIVEDIR") != null) {
            return "FCL_NATIVEDIR";
        }
        if (env.get("FCL_VERSION_CODE") != null) {
            return "FCL_VERSION_CODE";
        }
        if (env.get("POJAV_NATIVEDIR") != null) {
            return "POJAV_NATIVEDIR";
        }
        return osVersion != null && osVersion.startsWith("Android") ? "os.version" : null;
    }

    /** 取路径/库名的最后一段，兼容 / 与 \\；空串安全。 */
    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static {
        System.out.println("[MuoniumPlayer/MobileRendererEnv] 渲染器环境 = "
                + CURRENT.kind.id + "（" + CURRENT.displayName() + "）"
                + (CURRENT.hit == null ? "" : "（命中判据:" + CURRENT.hit + "）")
                + (CURRENT.launcherFamily ? "，FCL/Zalith 家族" : ""));
    }

    private MobileRendererEnv() {
    }
}
