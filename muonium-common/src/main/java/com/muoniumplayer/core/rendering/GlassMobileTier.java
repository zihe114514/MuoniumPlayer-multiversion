package com.muoniumplayer.core.rendering;

/**
 * 液态玻璃的「手机档」：安卓运行时上<b>整条真玻璃链路直接禁用</b>。
 *
 * <h2>为什么整档禁用而不是降参数</h2>
 * 真玻璃的每个环节都按桌面预算设计：实时背景采集（每帧全屏 blit，还逼着预糊层重建
 * 全屏高斯）、逐面板折射透镜（RuntimeEffect + 全屏采样）、融合边缘距离场……这些在
 * 桌面上都要一台好电脑，安卓真机再叠一层 GL 翻译（MobileGlues 一类）与更高的逻辑
 * 分辨率，连降档组合都扛不住（真机表现：画面卡住）。中间版本做过「手机档降参数」
 * （实时关、降采样 1/4、强制降载），仍把折射透镜的余量全押在真机上；最终改为
 * 在安卓上整体禁用：玻璃各面退回自己的假玻璃 / 实色画法，流畅性不再依赖任何玻璃配置。
 *
 * <h2>禁用的落点（两道闸，都在共享层）</h2>
 * <ul>
 *   <li>绘制侧 {@code GlassLook.amount()}：手机档直接返回 0 —— 全部真玻璃绘制
 *       （播放器面板 / 灵动岛 / 桌面歌词 / HUD 编辑器、融合边缘、压薄底色）都过这一道，
 *       返回 0 后各调用点走回自己原来的画法；</li>
 *   <li>采集侧 {@code GlassBackdrop.wanted()}：手机档直接 false —— 全屏 blit、预糊
 *       重建、bloom 底图一个都不跑。玻璃一个像素不画，就不付任何背景采集的钱。</li>
 * </ul>
 * 配置里的玻璃开关与性能项在手机上只是存档（迁移 v2 仍把它们当普通字段补缺，桌面语义不变）。
 *
 * <h2>安卓判定（从强到弱，全部落空才算桌面）</h2>
 * <ol>
 *   <li>JVM 属性 {@code -Dmuonium.glass.mobileTier=force_on|force_off}：
 *       真机上判错了（或想故意试玻璃）时的逃生门，启动日志能看到判了哪档。</li>
 *   <li>{@code android.os.Build} 类能否加载：ART/Dalvik 原生环境满足；个别
 *       「桌面 JRE 桥」的 bootclasspath 里也带着 Android framework 类。</li>
 *   <li>{@code java.vendor} / {@code java.vendor.url} 含 {@code android}：
 *       Dalvik 系 JVM 的自报家门。</li>
 *   <li>{@code /system/build.prop} 存在，或环境变量 {@code ANDROID_ROOT}/{@code ANDROID_DATA}
 *       任一非空：与伴生包目录 {@code SkijaNativeCatalog.mappedPlatform()} 同一组信号，
 *       那套判定在真机上被「下对了原生库、界面正常渲染」长期验证过。启动器带的完整
 *       桌面 JRE 里 {@code android.os.Build} 对 JVM 类加载器不可见、vendor 也不含
 *       android，前两条恰恰全落空——2026-10-08 真机「配置没灰、真玻璃还能选」的根因
 *       正是这里判漏。</li>
 *   <li>兜底猜测：Linux + ARM（含 {@code aarch64}——它<b>不含</b> {@code "arm"} 子串，
 *       第一版就在这儿放跑了所有 ARM64 真机）+ 无 AWT。精简 JRE 是 headless 的，
 *       而标准 Linux 桌面 JDK 必有 AWT。误伤面是「个别裁剪过的 Linux 桌机」，
 *       代价只是玻璃被禁用，不是损坏；反过来放跑一台真机的代价是整条链路把帧预算打爆。</li>
 * </ol>
 * 判定在类加载时定死并打一行启动日志（含命中的判据，真机排查「判没判对」全靠它），
 * 之后每帧只读布尔。
 */
public final class GlassMobileTier {

    private static final boolean MOBILE = detect();

    private GlassMobileTier() {
    }

    /** 当前运行时是否禁用真玻璃（手机档）。桌面恒为 {@code false}，判定顺序见类注释。 */
    public static boolean active() {
        return MOBILE;
    }

    private static boolean detect() {
        String forced = System.getProperty("muonium.glass.mobileTier", "");
        if ("force_on".equals(forced) || "force_off".equals(forced)) {
            boolean on = "force_on".equals(forced);
            System.out.println("[MuoniumPlayer/GlassMobileTier] JVM 属性强制禁用玻璃=" + on);
            return on;
        }
        String hit = androidHit();
        System.out.println("[MuoniumPlayer/GlassMobileTier] 手机档（禁用真玻璃）=" + (hit != null)
                + (hit == null ? "" : "（命中判据:" + hit + "）")
                + "（os.name=" + System.getProperty("os.name", "?")
                + ", os.arch=" + System.getProperty("os.arch", "?") + "）");
        return hit != null;
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
            // aarch64 不含 "arm" 子串，必须单列——第一版兜底就在这儿放跑了 ARM64 真机。
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
