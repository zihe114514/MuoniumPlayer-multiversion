package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.ncm.music.acoustics.AcousticsDebugTuning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 场景声学的实机调参钩子:监听 {@code config/muonium/acoustics-debug.txt},
 * 文件内容是「键=值」行(支持 # 注释),每 200ms 与采样同步热载 —— 改完存盘,
 * 下一轮采样就生效,不用重启游戏。
 *
 * <p>首次运行会把带全部键与注释的模板写到 {@code acoustics-debug.example.txt}
 * (实际生效的是 acoustics-debug.txt;删掉它 = 回到编译期默认)。</p>
 *
 * <h2>键表</h2>
 * <pre>
 *   wetBoost      湿声总量倍率        (1.0 = 默认)
 *   decayScale    全频带衰减倍率      (1.0 = 默认)
 *   lowDecayScale 低频衰减额外倍率    (1.0 = 默认;DJ 鼓点糊 → 0.5-0.75)
 *   tapSumCeil    单耳抽头幅度和上限  (NaN = 用内置值 1.1)
 *   fdnInputGain  FDN 注入量          (NaN = 用内置值 0.45)
 *   tailMakeup    FDN 输出补偿        (NaN = 用内置值 2.6)
 *   erBoost       早反射通路额外倍率  (1.0 = 默认)
 *   showRays      画探针射线          (false)
 * </pre>
 *
 * <p>只在 1.21.11 fabric 的 tick 桥上挂(调参期专用);参数定稿后把倍率烘回
 * 编译期常量、删除本类即可。</p>
 */
public final class AcousticsDebugLoader {

    private static final Path FILE = Path.of("config/muonium/acoustics-debug.txt");
    private static final Path TEMPLATE = Path.of("config/muonium/acoustics-debug.example.txt");
    private static final long CHECK_INTERVAL_MS = 200;

    private static long lastCheckMs;
    private static long lastModified;
    /** 射线可视化开关(ListenerProbe 采样时读;渲染器每帧读)。 */
    public static volatile boolean showRays;
    /** 最近一轮采样的射线快照(可视化渲染器读;采样线程写完整体替换)。 */
    public static volatile RaySnapshot lastRays;

    /** 一轮采样的射线可视化数据:线段与命中点的世界坐标(flat xyz 数组)。 */
    public static final class RaySnapshot {
        /** 每段两个端点:[2i],[2i+1](各 xyz 相邻)。 */
        public final float[] segs;
        public final int segCount;
        /** 命中点 [i](xyz 相邻)。 */
        public final float[] hits;
        public final int hitCount;

        public RaySnapshot(float[] segs, int segCount, float[] hits, int hitCount) {
            this.segs = segs;
            this.segCount = segCount;
            this.hits = hits;
            this.hitCount = hitCount;
        }
    }

    private AcousticsDebugLoader() {
    }

    /** tick 桥调用(每 tick,内部 200ms 节流):热载调参文件。 */
    public static void onClientTick() {
        final long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckMs = now;
        try {
            if (!Files.isRegularFile(FILE)) {
                if (!Files.isRegularFile(TEMPLATE)) {
                    writeTemplate();
                }
                return; // 没有调试文件 = 全默认
            }
            final long mtime = Files.getLastModifiedTime(FILE).toMillis();
            if (mtime == lastModified) {
                return;
            }
            lastModified = mtime;
            load();
            System.out.println("[MuoniumPlayer/AcousticsDebug] 已载入调参: " + describe());
        } catch (Throwable ignored) {
            // 调参文件是锦上添花:任何异常静默跳过本轮。
        }
    }

    private static void load() throws Exception {
        AcousticsDebugTuning.clear();
        showRays = false;
        for (String raw : Files.readAllLines(FILE)) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim();
            final String value = line.substring(eq + 1).trim();
            switch (key) {
                case "wetBoost" -> AcousticsDebugTuning.wetBoost = parseFloat(value);
                case "decayScale" -> AcousticsDebugTuning.decayScale = parseFloat(value);
                case "lowDecayScale" -> AcousticsDebugTuning.lowDecayScale = parseFloat(value);
                case "tapSumCeil" -> AcousticsDebugTuning.tapSumCeil = parseFloat(value);
                case "fdnInputGain" -> AcousticsDebugTuning.fdnInputGain = parseFloat(value);
                case "tailMakeup" -> AcousticsDebugTuning.tailMakeup = parseFloat(value);
                case "erBoost" -> AcousticsDebugTuning.erBoost = parseFloat(value);
                case "showRays" -> showRays = value.equalsIgnoreCase("true") || value.equals("1");
                default -> { }
            }
        }
    }

    /** 解析浮点;非法值/空值按 NaN(不干预)。 */
    private static float parseFloat(String value) {
        if (value.isEmpty() || value.equalsIgnoreCase("nan") || value.equalsIgnoreCase("default")) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String describe() {
        final StringBuilder sb = new StringBuilder();
        append(sb, "wetBoost", AcousticsDebugTuning.wetBoost);
        append(sb, "decayScale", AcousticsDebugTuning.decayScale);
        append(sb, "lowDecayScale", AcousticsDebugTuning.lowDecayScale);
        append(sb, "tapSumCeil", AcousticsDebugTuning.tapSumCeil);
        append(sb, "fdnInputGain", AcousticsDebugTuning.fdnInputGain);
        append(sb, "tailMakeup", AcousticsDebugTuning.tailMakeup);
        append(sb, "erBoost", AcousticsDebugTuning.erBoost);
        sb.append("showRays=").append(showRays);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, float v) {
        if (!Float.isNaN(v)) {
            sb.append(name).append('=').append(v).append(' ');
        }
    }

    private static void writeTemplate() throws Exception {
        Files.createDirectories(TEMPLATE.getParent());
        Files.writeString(TEMPLATE, List.of(
                "# 场景声学实机调参(把本文件改名/复制为 acoustics-debug.txt 生效)",
                "# 值留空或填 nan = 用内置默认;改完保存,≤200ms 生效,不用重启。",
                "#",
                "# wetBoost      湿声总量倍率        例:1.3(整体更湿)",
                "# decayScale    全频带衰减倍率      例:1.5(尾巴更长)",
                "# lowDecayScale 低频衰减额外倍率    例:0.6(DJ 鼓点不糊)",
                "# tapSumCeil    单耳抽头幅度和上限  例:1.4(小房间回声更密)",
                "# fdnInputGain  FDN 注入量          例:0.6(尾巴更响)",
                "# tailMakeup    FDN 输出补偿        例:3.0(尾巴更响,另一路)",
                "# erBoost       早反射额外倍率      例:1.4(回声更响)",
                "# showRays      画探针射线          例:true(世界画线+命中点)",
                ""
        ).stream().reduce((a, b) -> a + "\n" + b).orElse(""));
    }
}
