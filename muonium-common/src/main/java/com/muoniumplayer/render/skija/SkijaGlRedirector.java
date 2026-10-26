package com.muoniumplayer.render.skija;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * 把 libskija 里的 GL/EGL 导入<b>重定向</b>到 Minecraft 实际在用的那扇 GL 门上。
 *
 * <h2>为什么需要它（2026-10 MobileGL 真机定位的根因）</h2>
 * 官方 {@code skija-android-arm64} 的 {@code libskija.so} 链接的是<b>系统的</b>
 * {@code libGLESv2.so} / {@code libEGL.so}（readelf -d 实测 NEEDED），其余函数经系统
 * {@code eglGetProcAddress} 解析（objdump -T 实测 108 个 gl 与 egl 前缀的 UND 导入全部如此）。
 * 也就是说：<b>Skia 的每一个 GL 调用都绕过启动器的翻译层、直达底层 GLES 驱动</b>。
 *
 * <p>在 Zink / gl4es / MobileGlues 这类「ID 直通」的翻译层上，两扇门恰好指向同一批
 * 真实对象，绕行无害（2026-10-05 真机：MobileGlues 关 ANGLE 时 Skia 互操作完整可用）。
 * 但 MobileGL 的 DirectGLES 后端在前端维护<b>自己的一套对象名空间</b>（MG_State 的
 * IndexGenerator 从 1 起独立编号，见其 {@code TextureState::GenerateNames}）与
 * <b>整套状态影子</b>（{@code g_synced*} 差量推送）。于是同一物理 GLES 上下文被两扇门
 * 同时改写，后果正好是 26.3 真机日志里的两个症状：
 * <ol>
 *   <li><b>Skija 界面不显示</b>：{@code SkijaHost#wrapOwnedOffscreen} 用 LWJGL（走
 *       MobileGL 前端）创建的离屏 FBO 拿到的是<b>前端编号</b>；Skia 用裸驱动去
 *       {@code glBindFramebuffer(同一个号)}，绑到的是毫不相干的真实 GLES 对象
 *       （甚至不存在）→ 帧缓冲不完整 → 所有绘制静默落空。合成目标 FBO 0 在两扇门里
 *       同名且同指窗口表面，这解释了「日志全部成功、屏幕上什么都没有」。</li>
 *   <li><b>画面闪烁</b>：Skia 每帧在裸驱动上换绑 program / 纹理 / FBO / 混合状态，
 *       MobileGL 的影子毫不知情，后续为 Minecraft 差量推送的状态基于失真的「上次已
 *       推送」基线 → 该推的不推 → 原版画面随机花屏、闪烁。</li>
 * </ol>
 *
 * <h2>修法：让 Skia 走同一扇门</h2>
 * Skia 的接口装配器 {@code GrGLMakeAssembledInterface} 会先经解析到的
 * {@code glGetString(GL_VERSION)} 判断方言再装配（desktop / ES / WebGL 自动识别，
 * 库内两个 autogen 装配器都在、无 SK_ASSUME_GL_ES 裁剪——SkiaBuild 的 android 参数
 * 实测没开）。因此只要把它解析<b>每一个</b>函数的出口改指 libMobileGL：
 * <ul>
 *   <li>它能拿到的版本串变成 "4.6.0 MobileGL…"（desktop）→ 装配出桌面 GL 接口，
 *       DSA / GLSL 4xx 与 Minecraft 同待遇，由 MobileGL 翻译；</li>
 *   <li>对象名、状态影子全在前端一侧对齐——{@code SkijaHost} 现有的快照/还原、
 *       离屏、合成逻辑一行都不用改，从「两条门互相打架」变成「一条门」。</li>
 * </ul>
 *
 * <p>实现为一次性的 GOT 重写：libskija 加载完成后（重评热载同样幂等），按磁盘上的
 * ELF 文件解析 {@code .rela.dyn / .rela.plt}，凡符号名以 {@code gl}/{@code egl} 开头
 * 且目标库确实导出的，把重定位槽位的值改写成目标库的函数地址。只读页先
 * {@code mprotect} 改可写、写完还原只读；本就落在可写映射（懒绑定）里的槽位直接写。
 * 全部经 JNA 完成（启动器带 jna.boot.library.path，本 mod 亦 JiJ JNA）。
 *
 * <h2>门型：目标库怎么给出函数指针（2026-10-26 Zink 全黑扩展）</h2>
 * 触发条件不变：只在启动器设置了 {@code org.lwjgl.opengl.libname} 时动手。但目标库
 * 提供 GL 函数指针的「门型」有两种，按序探测：
 * <ol>
 *   <li><b>dlsym 门</b>（MobileGL 一类的完整前端）：库本身导出 {@code eglGetProcAddress}
 *       与 {@code glGetString}，每个符号查表直取。</li>
 *   <li><b>过程地址门</b>（Zink 的 libglxshim、Mesa 系的 libOSMesa）：库只导出一个
 *       「给名字、回指针」的过程地址桥——{@code glXGetProcAddress} /
 *       {@code glXGetProcAddressARB} / {@code OSMesaGetProcAddress}——其余一切
 *       gl 与 egl 前缀的导入都得<b>调用</b>这个桥来拿。发行二进制实测（ZL2 随包
 *       kopper-zink-release.aar 里的 libglxshim.so）：动态符号表中 GL 相关导出只有
 *       {@code glXGetProcAddress} 一个，旧的 dlsym 探针双双落空 → 重定向整段跳过 →
 *       Skia 的每个 GL 调用落到系统 GLES 驱动，而 Minecraft 上下文在 Mesa/Zink 栈里，
 *       两套驱动栈互不相识，症状正是「日志全部成功、屏幕一个像素都没有」。让 Skia
 *       也走这扇桥即与 LWJGL 同源（ZL2 的 LWJGL 在 Linux 上正是先探
 *       {@code glXGetProcAddress} 再逐函数取址）。健全性探针：桥必须能为
 *       {@code glGetString} 与 {@code eglGetProcAddress} 都给出非零地址——前者验证
 *       GL 函数面可达，后者验证 egl 门在同一侧（Mesa 的 eglGetProcAddress 对核心
 *       GL 函数同样返回有效指针，EGL 1.5 语义）；任一给不出就放弃，绝不做半扇门。</li>
 * </ol>
 * 两型都不满足才跳过——行为退回本修复之前，不会更糟。
 * {@link #outcome()} 把「进了哪扇门、写了几条、还有什么拿不到」留给提示层对账；
 * {@code -Dmuonium.skija.noGlRedirect=true} 仍可整体关掉本机制，用于异常环境 A/B。
 *
 * <h2>失败语义</h2>
 * 任何一步失败（无 /proc、非 ELF64、JNA 不可用、目标库打不开……）只记一条日志然后
 * 放弃，绝不抛出、绝不禁用渲染层：缺了重定向的 MobileGL 本来就是坏的，只在「能对齐」
 * 时改善。{@code -Dmuonium.skija.noGlRedirect=true} 可整体关掉本机制，用于异常环境 A/B。
 *
 * <p><b>线程</b>：由 {@link SkijaNatives#ensureLoaded()} 的成功路径调用（synchronized），
 * 那时 libskija 刚加载、还没有任何 GL 调用发生（首个 GL 调用是 SkijaHost 里的
 * DirectContext.makeGL，晚得多），GOT 重写不存在竞态。
 */
public final class SkijaGlRedirector {

    private static final String TAG = "[MuoniumPlayer/SkijaGlRedirect] ";

    /** LWJGL 用的 GL 库被启动器换成翻译层时设置的属性（26.3 真机日志可证）。 */
    private static final String PROP_LWJGL_OPENGL_LIB = "org.lwjgl.opengl.libname";

    /** 异常环境 A/B 的总闸。 */
    private static final String PROP_DISABLE = "muonium.skija.noGlRedirect";

    /** 只重定向这两种前缀的符号：GL/EGL 函数，绝不碰 libc/JNI 等其它命名空间。 */
    private static final String[] SYMBOL_PREFIXES = {"gl", "egl"};

    /** 动手之前目标库必须给出的两个探针符号（dlsym 门的判据）。 */
    private static final String PROBE_GETPROC = "eglGetProcAddress";
    private static final String PROBE_GETSTRING = "glGetString";

    /**
     * 过程地址门的桥符号候选，按序探测：Zink 的 libglxshim 只导出第一个；
     * VirGL / Freedreno 的 libOSMesa 用最后一个（{@code OSMesaGetProcAddress}）。
     */
    private static final String BRIDGE_GLX = "glXGetProcAddress";
    private static final String BRIDGE_GLX_ARB = "glXGetProcAddressARB";
    private static final String BRIDGE_OSMESA = "OSMesaGetProcAddress";
    private static final String[] BRIDGE_SYMBOLS = {BRIDGE_GLX, BRIDGE_GLX_ARB, BRIDGE_OSMESA};

    /** dlsym 门在结果与日志里的名字。 */
    static final String DLSYM_DOOR = "dlsym 门";

    // ---- ELF 常量（只写用到的）----
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_PLTRELSZ = 2;
    private static final long DT_STRTAB = 5;
    private static final long DT_SYMTAB = 6;
    private static final long DT_RELA = 7;
    private static final long DT_RELASZ = 8;
    private static final long DT_SYMENT = 11;
    private static final long DT_PLTREL = 20;
    private static final long DT_JMPREL = 23;
    private static final long DT_RELA_VALUE = 7;
    private static final int EM_AARCH64 = 183;
    private static final int EM_X86_64 = 62;
    private static final int R_AARCH64_GLOB_DAT = 1025;
    private static final int R_AARCH64_JUMP_SLOT = 1026;
    private static final int R_X86_64_GLOB_DAT = 6;
    private static final int R_X86_64_JUMP_SLOT = 7;

    private static final int PROT_READ = 1;
    private static final int PROT_WRITE = 2;

    /** 一次性执行；重评热载会再次走到，避免重复解析与重复日志。 */
    private static boolean attempted;

    /**
     * 一次成功重定向的对账单：进了哪扇门、用什么探针、写了几条、还剩几条拿不到。
     * 提示层拿它与日志对账（「重定向到底做没做成」不用再翻日志）。
     */
    public record Outcome(String door, String probe, int redirected, int unresolved,
                          String targetLibrary) {
    }

    /** 最近一次成功重定向的结果；未动手（不需要 / 失败 / 跳过）恒为 null。 */
    private static volatile Outcome outcome;

    /** 最近一次成功重定向的结果，未动手为 null。供提示层与诊断对账。 */
    public static Outcome outcome() {
        return outcome;
    }

    /**
     * 一扇可用的 GL 门：{@code probe} 是判定它的探针符号，{@code bridge} 为 true 时
     * 表示「逐符号调用探针拿地址」的过程地址门，false 表示「逐符号 dlsym」的完整前端。
     */
    private record Door(String label, String probe, boolean bridge) {
    }

    /**
     * 门判定本体：导出符号集 → 命中的门，参数全是字符串，单元测试可直接喂合成符号表。
     *
     * <p>顺序刻意先 dlsym 后桥：dlsym 门每个符号一次查表，代价与能力都优于「逐个调桥」，
     * MobileGL 这类完整前端不该被桥再跳一道。命中 dlsym 门要求两个探针<b>同时</b>在场；
     * 只见桥（或探针缺一但有桥）走过程地址门。都见不着返回 null = 不动手。
     */
    static Door doorForSymbols(Set<String> exportedSymbols) {
        if (exportedSymbols.contains(PROBE_GETPROC) && exportedSymbols.contains(PROBE_GETSTRING)) {
            return new Door(DLSYM_DOOR, PROBE_GETPROC, false);
        }
        for (String bridge : BRIDGE_SYMBOLS) {
            if (exportedSymbols.contains(bridge)) {
                return new Door("过程地址门", bridge, true);
            }
        }
        return null;
    }

    /** 门判定要探测的全部符号；JNA 拿不到导出表，就对我们关心的这份名单逐个 resolve。 */
    private static final List<String> DOOR_PROBE_SYMBOLS = List.of(
            PROBE_GETPROC, PROBE_GETSTRING, BRIDGE_GLX, BRIDGE_GLX_ARB, BRIDGE_OSMESA);

    private static Set<String> symbolsOf(NativeLibrary target) {
        Set<String> present = new LinkedHashSet<>();
        for (String name : DOOR_PROBE_SYMBOLS) {
            if (resolve(target, name) != 0) {
                present.add(name);
            }
        }
        return present;
    }

    private SkijaGlRedirector() {
    }

    /**
     * libskija 加载成功后调用一次。静默返回 = 「不需要或没做成」，都不算错误；
     * 做成了会打一条带统计的日志，供真机日志一眼对账。
     */
    public static synchronized void afterLibraryLoad() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            doRedirect();
        } catch (Throwable t) {
            // 兜底：任何未预料的形状都不允许影响游戏本体。
            log("重定向未执行（异常）: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void doRedirect() {
        if (Boolean.getBoolean(PROP_DISABLE)) {
            log("已被 " + PROP_DISABLE + " 关闭");
            return;
        }
        String glLib = System.getProperty(PROP_LWJGL_OPENGL_LIB, "").trim();
        if (glLib.isEmpty()) {
            // 桌面默认环境：LWJGL 用系统 GL，Skia 也解析系统 GL，本就同一扇门。
            // 但安卓启动器家族里属性缺失本身就是异常信号，留一行日志便于真机对账。
            if (launcherFamilySignal() != null) {
                log("启动器未设置 " + PROP_LWJGL_OPENGL_LIB + "，无需重定向");
            }
            return;
        }

        Mapping skija = findSkijaMapping();
        if (skija == null) {
            // Windows / macOS（无 /proc/self/maps）或库还没映射进来。
            log("未在 /proc/self/maps 里找到 libskija 映射，跳过");
            return;
        }

        ParsedElf elf;
        try (FileChannel ch = FileChannel.open(skija.file)) {
            ByteBuffer image = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            long[] slotTypes = slotRelocTypes(machineOf(image));
            if (slotTypes == null) {
                log("libskija 不是受支持的 ELF64 机器类型，跳过");
                return;
            }
            long loadBias = skija.start - lowestLoadVaddr(image);
            List<Reloc> relocs = collectGlRelocs(image, slotTypes);
            elf = new ParsedElf(loadBias, relocs);
        } catch (Throwable parseFailure) {
            log("解析 libskija ELF 失败: " + parseFailure);
            return;
        }
        if (elf.relocs.isEmpty()) {
            log("libskija 里没有可重定向的 GL/EGL 导入，跳过");
            return;
        }

        NativeLibrary target = openTargetLibrary(glLib);
        if (target == null) {
            log("打不开 GL 库 " + glLib + "（含启动器原生目录搜索），跳过");
            return;
        }

        // 门判定：完整前端（dlsym 门）或过程地址桥，二者其一才动手。
        Door door = doorForSymbols(symbolsOf(target));
        if (door == null) {
            log(glLib + " 既不导出完整前端探针（" + PROBE_GETPROC + "/" + PROBE_GETSTRING
                    + "）也没有过程地址桥（" + String.join("/", BRIDGE_SYMBOLS) + "），跳过");
            return;
        }
        if (door.bridge()) {
            // 桥必须能为「GL 函数面」与「egl 门」都给出非零地址，任一给不出就是半扇门——
            // 半扇门比不修更糟（eglGetProcAddress 落到系统侧会重新劈成两条门）。
            long glProbe = resolveViaBridge(target, door.probe(), PROBE_GETSTRING);
            long eglProbe = resolveViaBridge(target, door.probe(), PROBE_GETPROC);
            if (glProbe == 0 || eglProbe == 0) {
                log(door.probe() + " 不肯为 " + PROBE_GETSTRING + "/" + PROBE_GETPROC
                        + " 给出地址，放弃重定向");
                return;
            }
            log("命中过程地址门（" + door.probe() + "），逐符号经桥解析");
        } else {
            log("命中" + door.label() + "，逐符号查表解析");
        }

        int pageSize = pageSize();
        Map<Long, List<Slot>> byPage = new LinkedHashMap<>();
        int unresolved = 0;
        for (Reloc reloc : elf.relocs) {
            long addr = door.bridge()
                    ? resolveViaBridge(target, door.probe(), reloc.name)
                    : resolve(target, reloc.name);
            if (addr == 0) {
                unresolved++;
                continue;
            }
            long slot = elf.loadBias + reloc.vaddr;
            byPage.computeIfAbsent(slot & ~((long) pageSize - 1), k -> new ArrayList<>())
                    .add(new Slot(slot, addr));
        }

        int patched = 0;
        for (Map.Entry<Long, List<Slot>> page : byPage.entrySet()) {
            patched += patchPage(page.getKey(), page.getValue(), pageSize, skija);
        }
        if (patched > 0) {
            outcome = new Outcome(door.label(), door.probe(), patched, unresolved, glLib);
            log("已把 libskija 的 " + patched + " 个 GL/EGL 导入重定向到 " + glLib
                    + "（" + door.label() + (door.bridge() ? "：" + door.probe() : "")
                    + (unresolved > 0
                    ? "；另有 " + unresolved + " 个符号解析不到，保留原解析）" : "）"));
        } else {
            log("目标库 " + glLib + " 可解析的符号没有一个写入成功");
        }
    }

    // ------------------------------------------------------------------
    // 过程地址桥与目标库定位
    // ------------------------------------------------------------------

    /**
     * 经过程地址桥解析一个符号：先 dlsym（有的库恰好也直接导出部分函数），
     * 查不到再<b>调用</b>桥本身——桥的签名统一是「{@code const char*} 进、指针出」，
     * 传 Java String 即可，不需要任何 GL 上下文。
     */
    private static long resolveViaBridge(NativeLibrary bridge, String bridgeName, String name) {
        long direct = resolve(bridge, name);
        if (direct != 0) {
            return direct;
        }
        try {
            Pointer p = bridge.getFunction(bridgeName).invokePointer(new Object[]{name});
            return p == null ? 0 : Pointer.nativeValue(p);
        } catch (Throwable bridgeFailure) {
            return 0;
        }
    }

    /**
     * 打开目标库。属性值可能是绝对路径（FCL 的 MobileGL 日志实证），也可能是裸 soname
     * （ZL2/FCL 的 Zink 都是 {@code libglxshim.so}）——后者 dlopen 搜不到时（JVM 的
     * 链接器命名空间未必覆盖启动器的插件目录），再按启动器原生目录逐个找文件打开。
     */
    private static NativeLibrary openTargetLibrary(String glLib) {
        NativeLibrary direct = openLibrary(glLib);
        if (direct != null) {
            return direct;
        }
        for (String dir : launcherNativeDirectories()) {
            File candidate = new File(dir, glLib);
            if (!candidate.isFile()) {
                continue;
            }
            NativeLibrary lib = openLibrary(candidate.getAbsolutePath());
            if (lib != null) {
                log("GL 库 " + glLib + " 在启动器原生目录找到：" + dir);
                return lib;
            }
        }
        return null;
    }

    /** 启动器原生目录候选：渲染器插件的 nativeLibraryDir 必然在链接器允许的命名空间里。 */
    private static List<String> launcherNativeDirectories() {
        List<String> out = new ArrayList<>();
        for (String key : new String[]{"POJAV_NATIVEDIR", "FCL_NATIVEDIR"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                out.add(value);
            }
        }
        // SDL_EGL_LIBRARY 是绝对路径（ZL2/FCL 都是）；POJAVEXEC_EGL / LIB_MESA_NAME
        // 常是裸名——只收带路径分隔符的那些的所在目录。
        for (String key : new String[]{"SDL_EGL_LIBRARY", "POJAVEXEC_EGL", "LIB_MESA_NAME"}) {
            String value = System.getenv(key);
            if (value != null) {
                int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
                if (slash > 0) {
                    out.add(value.substring(0, slash));
                }
            }
        }
        String lwjglPath = System.getProperty("org.lwjgl.librarypath", "");
        if (!lwjglPath.isBlank()) {
            for (String part : lwjglPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!part.isBlank()) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    /** 安卓启动器家族信号（pojav 系 env）；拿不准就返回 null，桌面保持静默。 */
    private static String launcherFamilySignal() {
        if (System.getenv("POJAV_NATIVEDIR") != null) {
            return "POJAV_NATIVEDIR";
        }
        if (System.getenv("FCL_NATIVEDIR") != null) {
            return "FCL_NATIVEDIR";
        }
        String osVersion = System.getProperty("os.version", "");
        return osVersion.startsWith("Android") ? "os.version" : null;
    }

    // ------------------------------------------------------------------
    // /proc/self/maps：libskija 的映射起点、各段权限
    // ------------------------------------------------------------------

    private record MapRange(long start, long end, boolean writable) {
    }

    private record Mapping(Path file, long start, List<MapRange> ranges) {
    }

    private record Reloc(long vaddr, String name) {
    }

    private record Slot(long address, long value) {
    }

    private record ParsedElf(long loadBias, List<Reloc> relocs) {
    }

    private static Mapping findSkijaMapping() {
        try {
            Map<Path, Mapping> found = new LinkedHashMap<>();
            for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
                int dash = line.indexOf('-');
                int space = line.indexOf(' ');
                int slash = line.indexOf('/');
                if (dash <= 0 || space < dash || slash < 0) {
                    continue;
                }
                Path path = Path.of(line.substring(slash).trim());
                if (!path.getFileName().toString().equals("libskija.so")) {
                    continue;
                }
                long start = Long.parseUnsignedLong(line.substring(0, dash), 16);
                long end = Long.parseUnsignedLong(line.substring(dash + 1, space), 16);
                // 形如 "r--p"/"rw-p"：第 2 个字符是写权限。
                boolean writable = line.length() > space + 2
                        && line.charAt(space + 2) == 'w';
                Mapping mapping = found.get(path);
                if (mapping == null) {
                    List<MapRange> ranges = new ArrayList<>();
                    ranges.add(new MapRange(start, end, writable));
                    found.put(path, new Mapping(path, start, ranges));
                } else {
                    mapping.ranges().add(new MapRange(start, end, writable));
                }
            }
            // 多个不同路径的 libskija（理论上不该有）：取最先出现的那个。
            return found.isEmpty() ? null : found.values().iterator().next();
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** 该地址落在哪段映射里；找不到返回 null。 */
    private static MapRange rangeOf(Mapping mapping, long address) {
        for (MapRange range : mapping.ranges()) {
            if (address >= range.start() && address < range.end()) {
                return range;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // ELF 解析（磁盘上的小端 ELF64 共享库）
    // ------------------------------------------------------------------

    private static int machineOf(ByteBuffer elf) {
        return elf.getShort(18) & 0xFFFF;
    }

    private static long[] slotRelocTypes(int machine) {
        if (machine == EM_AARCH64) {
            return new long[]{R_AARCH64_GLOB_DAT, R_AARCH64_JUMP_SLOT};
        }
        if (machine == EM_X86_64) {
            return new long[]{R_X86_64_GLOB_DAT, R_X86_64_JUMP_SLOT};
        }
        return null;
    }

    /** 第一个 PT_LOAD 的 p_vaddr（几乎所有 .so 都是 0）；maps 最小起点减它即 load bias。 */
    private static long lowestLoadVaddr(ByteBuffer elf) {
        long phoff = elf.getLong(32);
        int phentsize = elf.getShort(54) & 0xFFFF;
        int phnum = elf.getShort(56) & 0xFFFF;
        long lowest = Long.MAX_VALUE;
        for (int i = 0; i < phnum; i++) {
            int type = elf.getInt((int) (phoff + (long) i * phentsize)) & 0xFFFF_FFFF;
            if (type == PT_LOAD) {
                lowest = Math.min(lowest, elf.getLong((int) (phoff + (long) i * phentsize + 16)));
            }
        }
        return lowest == Long.MAX_VALUE ? 0 : lowest;
    }

    /**
     * 收集「符号名以 gl/egl 开头」的 GLOB_DAT / JUMP_SLOT 重定位。
     *
     * <p>名字在解析阶段就取好（strtab 只在手边这一次）；同一符号多处导入会逐条返回，
     * 各自的槽位都要写。
     */
    private static List<Reloc> collectGlRelocs(ByteBuffer elf, long[] slotTypes) {
        long phoff = elf.getLong(32);
        int phentsize = elf.getShort(54) & 0xFFFF;
        int phnum = elf.getShort(56) & 0xFFFF;
        long dynOff = -1;
        long dynSize = 0;
        for (int i = 0; i < phnum; i++) {
            int base = (int) (phoff + (long) i * phentsize);
            if ((elf.getInt(base) & 0xFFFF_FFFF) == PT_DYNAMIC) {
                dynOff = elf.getLong(base + 8);
                dynSize = elf.getLong(base + 32);
                break;
            }
        }
        if (dynOff < 0 || dynSize <= 0) {
            return List.of();
        }
        long strtabAddr = 0;
        long symtabAddr = 0;
        long syment = 24;
        long jmprelAddr = 0;
        long jmprelSize = 0;
        long relaAddr = 0;
        long relaSize = 0;
        boolean jmprelIsRela = true;
        for (int pos = 0; pos + 16 <= dynSize; pos += 16) {
            long tag = elf.getLong((int) dynOff + pos);
            if (tag == DT_NULL) {
                break;
            }
            long val = elf.getLong((int) dynOff + pos + 8);
            if (tag == DT_STRTAB) {
                strtabAddr = val;
            } else if (tag == DT_SYMTAB) {
                symtabAddr = val;
            } else if (tag == DT_SYMENT) {
                syment = val;
            } else if (tag == DT_JMPREL) {
                jmprelAddr = val;
            } else if (tag == DT_PLTRELSZ) {
                jmprelSize = val;
            } else if (tag == DT_RELA) {
                relaAddr = val;
            } else if (tag == DT_RELASZ) {
                relaSize = val;
            } else if (tag == DT_PLTREL) {
                jmprelIsRela = val == DT_RELA_VALUE;
            }
        }
        if (strtabAddr == 0 || symtabAddr == 0) {
            return List.of();
        }
        List<Reloc> out = new ArrayList<>();
        if (jmprelIsRela && jmprelAddr != 0 && jmprelSize > 0) {
            collectRange(elf, jmprelAddr, jmprelSize, slotTypes, symtabAddr, syment,
                    strtabAddr, out);
        }
        if (relaAddr != 0 && relaSize > 0) {
            collectRange(elf, relaAddr, relaSize, slotTypes, symtabAddr, syment,
                    strtabAddr, out);
        }
        return out;
    }

    private static void collectRange(ByteBuffer elf, long vaddr, long size, long[] slotTypes,
                                     long symtabAddr, long syment, long strtabAddr,
                                     List<Reloc> out) {
        long relaOff = vaddrToFileOffset(elf, vaddr);
        if (relaOff < 0) {
            return;
        }
        for (int pos = 0; pos + 24 <= size; pos += 24) {
            long rOffset = elf.getLong((int) relaOff + pos);
            long rInfo = elf.getLong((int) relaOff + pos + 8);
            int type = (int) (rInfo & 0xFFFF_FFFFL);
            if (type != slotTypes[0] && type != slotTypes[1]) {
                continue;
            }
            long symIndex = rInfo >>> 32;
            if (symIndex == 0) {
                continue;
            }
            long symOff = vaddrToFileOffset(elf, symtabAddr + symIndex * syment);
            if (symOff < 0 || symOff + 24 > elf.capacity()) {
                continue;
            }
            long stName = elf.getInt((int) symOff) & 0xFFFF_FFFFL;
            String name = cstringAtVaddr(elf, strtabAddr, stName);
            if (name == null || !hasGlPrefix(name)) {
                continue;
            }
            out.add(new Reloc(rOffset, name));
        }
    }

    /** 链接期虚地址 → 文件偏移（找包含它的 PT_LOAD）；不在任何段里返回 -1。 */
    private static long vaddrToFileOffset(ByteBuffer elf, long vaddr) {
        long phoff = elf.getLong(32);
        int phentsize = elf.getShort(54) & 0xFFFF;
        int phnum = elf.getShort(56) & 0xFFFF;
        for (int i = 0; i < phnum; i++) {
            int base = (int) (phoff + (long) i * phentsize);
            if ((elf.getInt(base) & 0xFFFF_FFFF) != PT_LOAD) {
                continue;
            }
            long pVaddr = elf.getLong(base + 16);
            long pFilesz = elf.getLong(base + 32);
            if (vaddr >= pVaddr && vaddr < pVaddr + pFilesz) {
                return elf.getLong(base + 8) + (vaddr - pVaddr);
            }
        }
        return -1;
    }

    /** 从 strtab（按虚地址定位）里读一个 NUL 结尾字符串；越界返回 null。 */
    private static String cstringAtVaddr(ByteBuffer elf, long strtabAddr, long offset) {
        long strtabOff = vaddrToFileOffset(elf, strtabAddr);
        if (strtabOff < 0 || strtabOff + offset >= elf.capacity()) {
            return null;
        }
        int start = (int) (strtabOff + offset);
        int end = start;
        while (end < elf.capacity() && elf.get(end) != 0) {
            end++;
        }
        if (end == start) {
            return "";
        }
        byte[] bytes = new byte[end - start];
        elf.get(start, bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static boolean hasGlPrefix(String name) {
        for (String prefix : SYMBOL_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // JNA：目标库解析 / 内存写入 / mprotect
    // ------------------------------------------------------------------

    private static NativeLibrary openLibrary(String path) {
        try {
            return NativeLibrary.getInstance(path);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在目标库里解析符号；不存在返回 0（JNA 对缺失符号抛 UnsatisfiedLinkError）。 */
    private static long resolve(NativeLibrary lib, String name) {
        try {
            return Pointer.nativeValue(lib.getFunction(name));
        } catch (Throwable missing) {
            return 0;
        }
    }

    private static int pageSize() {
        NativeLibrary libc = libc();
        if (libc == null) {
            return 4096;
        }
        try {
            return libc.getFunction("getpagesize").invokeInt(new Object[0]);
        } catch (Throwable t) {
            return 4096;
        }
    }

    private static int mprotect(long address, long length, int prot) {
        NativeLibrary libc = libc();
        if (libc == null) {
            return -1;
        }
        try {
            return libc.getFunction("mprotect")
                    .invokeInt(new Object[]{new Pointer(address), length, prot});
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * libc 的句柄。JNA 在 glibc 上把 {@code "c"} 映射到 libc.so.6，Android 的 bionic
     * 没有那个文件名——所以按 {@code "c"} → {@code libc.so} 的顺序试，谁先开谁算。
     */
    private static NativeLibrary libc() {
        for (String name : new String[]{"c", "libc.so"}) {
            try {
                return NativeLibrary.getInstance(name);
            } catch (Throwable unavailable) {
                // 换下一个名字。
            }
        }
        return null;
    }

    /**
     * 写一页的槽位，返回成功条数。
     *
     * <p>权限策略按页所在映射的真实可写性分两路（信息来自 /proc/self/maps，不猜）：
     * <ul>
     *   <li><b>只读映射</b>（full RELRO 的 .got）：mprotect 临时改 RW，写完还原 R。
     *       BIND_NOW 下这一页此后不会再被动态链接器改写，还原只读无副作用。</li>
     *   <li><b>可写映射</b>（部分 RELRO 的 .got.plt）：直接写，<b>绝不动权限</b>——
     *       链接器还要往这页里做其它符号的懒解析，改只读会让下一个 PLT 调用崩掉。</li>
     * </ul>
     */
    private static int patchPage(long page, List<Slot> slots, int pageSize, Mapping mapping) {
        long spanEnd = page + pageSize;
        for (Slot slot : slots) {
            spanEnd = Math.max(spanEnd, slot.address() + 8);
        }
        MapRange range = rangeOf(mapping, slots.get(0).address());
        boolean needMprotect = range == null || !range.writable();
        if (needMprotect
                && mprotect(page, spanEnd - page, PROT_READ | PROT_WRITE) != 0) {
            return 0;
        }
        int written = 0;
        try {
            for (Slot slot : slots) {
                new Pointer(slot.address()).setLong(0, slot.value());
                written++;
            }
        } finally {
            if (needMprotect) {
                // 还原失败不影响正确性（只是页保持可写），吞掉即可。
                mprotect(page, spanEnd - page, PROT_READ);
            }
        }
        return written;
    }

    private static void log(String message) {
        System.out.println(TAG + message);
    }
}
