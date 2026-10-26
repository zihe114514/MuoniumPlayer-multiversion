package com.muoniumplayer.core.rendering;

/**
 * 液态玻璃的「手机档」：安卓运行时上限制真玻璃链路——翻译层渲染器<b>整档禁用</b>，
 * Mesa 真驱动渲染器（Zink / VirGL / Freedreno / Panfrost）开放<b>实验档</b>由用户自担。
 *
 * <h2>为什么整档禁用而不是降参数（翻译层）</h2>
 * 真玻璃的每个环节都按桌面预算设计：实时背景采集（每帧全屏 blit，还逼着预糊层重建
 * 全屏高斯）、逐面板折射透镜（RuntimeEffect + 全屏采样）、融合边缘距离场……这些在
 * 桌面上都要一台好电脑，安卓真机再叠一层 GL 翻译（MobileGlues 一类）与更高的逻辑
 * 分辨率，连降档组合都扛不住（真机表现：画面卡住）。中间版本做过「手机档降参数」
 * （实时关、降采样 1/4、强制降载），仍把折射透镜的余量全押在真机上；最终改为
 * 在安卓上整体禁用：玻璃各面退回自己的假玻璃 / 实色画法，流畅性不再依赖任何玻璃配置。
 *
 * <h2>为什么 Mesa 渲染器放实验档（2026-10-26）</h2>
 * Zink / VirGL / Freedreno / Panfrost 不是 ID 直通翻译层——它们是 Mesa 真桌面 GL
 * （Zink 还是 GL over Vulkan），驱动语义与桌面机同族，真玻璃的三大件（全屏采集、
 * 折射透镜、融合边缘）在语义层面没有翻译层那类「clear op 整帧丢失」的坑。
 * 但性能未经真机标定（手机 GPU 跑桌面驱动的余量没人测过），所以不是「默认开」，
 * 而是「可开、默认关、设置里标注实验」。识别器是
 * {@link MobileRendererEnv}——启动器 env/属性指纹，不依赖 GL 字符串（防「隐藏 MG 环境」）。
 *
 * <h2>三档映射</h2>
 * <ul>
 *   <li>{@code DISABLED}：安卓 + 翻译层渲染器（MobileGlues / MobileGL / NG-GL4ES /
 *       Holy-GL4ES / 未知渲染器）——真玻璃整档禁用，{@link #hardDisabled()} = true，
 *       设置 UI 把「真玻璃材质」组锁死；</li>
 *   <li>{@code EXPERIMENTAL}：安卓 + Mesa 真驱动——默认仍禁用，用户在设置里打开
 *       「真玻璃（实验）」后放行（{@link #setUserOptIn}，五棵树 HudConfig 推入）；</li>
 *   <li>{@code DESKTOP}：非安卓——一切照旧。</li>
 * </ul>
 *
 * <h2>对调用点的承诺</h2>
 * {@link #active()} 语义不变（=「真玻璃此刻整体关闭」）：两道闸
 * （绘制侧 {@code GlassLook.amount()}、采集侧 {@code GlassBackdrop.wanted()}）
 * 与所有玻璃调用点<b>一行不用改</b>。新增的 {@link #hardDisabled()} 只给设置 UI 用。
 *
 * <h2>安卓判定（从强到弱，全部落空才算桌面）</h2>
 * <ol>
 *   <li>JVM 属性 {@code -Dmuonium.glass.mobileTier=force_on|force_off}：
 *       真机上判错了（或想故意试玻璃）时的逃生门，启动日志能看到判了哪档，优先级最高；</li>
 *   <li>{@code android.os.Build} 类能否加载；</li>
 *   <li>{@code java.vendor} / {@code java.vendor.url} 含 {@code android}；</li>
 *   <li>{@code /system/build.prop} 存在，或环境变量 {@code ANDROID_ROOT}/{@code ANDROID_DATA}
 *       任一非空；</li>
 *   <li>兜底猜测：Linux + ARM（含 {@code aarch64}——它<b>不含</b> {@code "arm"} 子串）
 *       + 无 AWT。</li>
 * </ol>
 * 判定在类加载时定死并打一行启动日志（含命中的判据与档位），之后每帧只读布尔。
 */
public final class GlassMobileTier {

    /** 手机档内部档位；映射见类注释。 */
    private enum Tier {
        /** 翻译层渲染器 / 未知安卓渲染器：真玻璃整档禁用。 */
        DISABLED,
        /** Mesa 真驱动渲染器：实验档，默认禁用、用户 opt-in 后放行。 */
        EXPERIMENTAL,
        /** 桌面：照旧。 */
        DESKTOP
    }

    private static final Tier TIER = detect();

    /**
     * 用户对「真玻璃（实验）」的 opt-in；只有 EXPERIMENTAL 档读它。
     * 五棵树 HudConfig（键 {@code glassMobileExperimental}）在加载与改动时推入。
     */
    private static volatile boolean userOptIn;

    private GlassMobileTier() {
    }

    /**
     * 真玻璃此刻是否整体关闭。语义与历史版本完全一致：调用点（GlassLook.amount /
     * GlassBackdrop.wanted 等）零改动。桌面恒 false；手机档 DISABLED 恒 true；
     * EXPERIMENTAL 取决于用户是否打开实验开关。
     */
    public static boolean active() {
        return switch (TIER) {
            case DISABLED -> true;
            case EXPERIMENTAL -> !userOptIn;
            case DESKTOP -> false;
        };
    }

    /**
     * 设置 UI 是否应当把「真玻璃材质」组<b>整体锁死</b>：只有翻译层渲染器的 DISABLED 档。
     * EXPERIMENTAL 档不锁——用户要能点开那个实验开关， DESKTOP 恒不锁。
     */
    public static boolean hardDisabled() {
        return TIER == Tier.DISABLED;
    }

    /** EXPERIMENTAL 档（Mesa 渲染器、默认关）才允许用户放行；其它档调用无效果。 */
    public static void setUserOptIn(boolean optIn) {
        userOptIn = optIn;
        System.out.println("[MuoniumPlayer/GlassMobileTier] 实验档 opt-in = " + optIn
                + "（生效与否取决于档位=" + TIER + "，当前 active=" + active() + "）");
    }

    private static Tier detect() {
        String forced = System.getProperty("muonium.glass.mobileTier", "");
        if ("force_on".equals(forced) || "force_off".equals(forced)) {
            boolean on = "force_on".equals(forced);
            System.out.println("[MuoniumPlayer/GlassMobileTier] JVM 属性强制禁用玻璃=" + on);
            return on ? Tier.DISABLED : Tier.DESKTOP;
        }
        String androidHit = androidHit();
        if (androidHit == null) {
            System.out.println("[MuoniumPlayer/GlassMobileTier] 手机档（禁用真玻璃）=false"
                    + "（桌面，os.name=" + System.getProperty("os.name", "?")
                    + ", os.arch=" + System.getProperty("os.arch", "?") + "）");
            return Tier.DESKTOP;
        }
        MobileRendererEnv.Env env = MobileRendererEnv.current();
        Tier tier = mesaRenderer(env.kind()) ? Tier.EXPERIMENTAL : Tier.DISABLED;
        System.out.println("[MuoniumPlayer/GlassMobileTier] 手机档（禁用真玻璃）=" + (tier != Tier.DESKTOP)
                + "（命中判据:" + androidHit + "，渲染器=" + env.kind().id + "，档=" + tier + "）"
                + "（os.name=" + System.getProperty("os.name", "?")
                + ", os.arch=" + System.getProperty("os.arch", "?") + "）");
        return tier;
    }

    /** Mesa 真驱动家族（非翻译层）：玻璃实验档只在它们身上开放。 */
    private static boolean mesaRenderer(MobileRendererEnv.Kind kind) {
        return switch (kind) {
            case ZINK, VIRGL, FREEDRENO, PANFROST -> true;
            default -> false;
        };
    }

    /** 逐条试安卓判据，命中返回判据名（进启动日志），全部落空返回 {@code null}=桌面。 */
    private static String androidHit() {
        try {
            if (classPresent("android.os.Build")) {
                return "android.os.Build";
            }
            String vendor = (System.getProperty("java.vendor", "") + " "
                    + System.getProperty("java.vendor.url", ""))
                    .toLowerCase(java.util.Locale.ROOT);
            if (vendor.contains("android")) {
                return "java.vendor";
            }
            // 伴生包目录（SkijaNativeCatalog.mappedPlatform）同款真机验证过的两枚标记：
            // /system/build.prop 安卓必有、桌面 Linux 必无；ANDROID_ROOT/ANDROID_DATA
            // 是 Android 应用进程与 Termux 都带的环境变量。
            if (new java.io.File("/system/build.prop").isFile()) {
                return "/system/build.prop";
            }
            if (envFlag("ANDROID_ROOT") || envFlag("ANDROID_DATA")) {
                return "ANDROID_ROOT/ANDROID_DATA";
            }
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
            // aarch64 不含 "arm" 子串，必须单列——第一版兜底就在这儿放跑了所有 ARM64 真机。
            boolean arm = arch.contains("arm") || arch.contains("aarch64");
            if (os.contains("linux") && arm && !classPresent("java.awt.Canvas")) {
                return "linux+arm+无AWT";
            }
            return null;
        } catch (Throwable unexpected) {
            // 判定自己绝不能把游戏带崩；拿不准就当桌面（逃生门属性仍可强制）。
            return null;
        }
    }

    private static boolean envFlag(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    /** 只探测类是否可见；{@code initialize=false}，不触发目标类的静态初始化。 */
    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, GlassMobileTier.class.getClassLoader());
            return true;
        } catch (Throwable notPresent) {
            return false;
        }
    }
}
