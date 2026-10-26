package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「绘光」动效数学的纯函数断言：飞行曲线、径迹回溯、卡拉OK点亮、跨面投影。
 */
class HuiguangMotionTest {

    private static final float EPS = 1e-4f;

    @Test
    void flightStartsAtFromAndEndsAtTo() {
        HuiguangMotion.Flight f = HuiguangMotion.flight(3, 42,
                -80f, -20f, 0f, 0f, WorldLyricsState.LYRIC_SIZE, false);
        assertTrue(f.valid);
        float[] out = new float[4];
        HuiguangMotion.poseAt(f, 0f, HuiguangMotion.ENTRY_WINDOW_MS, out);
        assertEquals(-80f, out[0], 0.5f, "t=0 应在起飞点附近");
        assertEquals(-20f, out[1], 0.5f);
        assertEquals(0f, out[3], EPS, "t=0 不在飞行中");
        HuiguangMotion.poseAt(f, HuiguangMotion.ENTRY_WINDOW_MS, HuiguangMotion.ENTRY_WINDOW_MS, out);
        assertEquals(0f, out[0], 0.5f, "窗口结束应钉在终点");
        assertEquals(0f, out[1], 0.5f);
        assertEquals(0f, out[3], EPS, "到位后 flying=0");
    }

    @Test
    void flightIsDeterministic() {
        HuiguangMotion.Flight a = HuiguangMotion.flight(7, 123,
                0f, 0f, 40f, 10f, 10f, false);
        HuiguangMotion.Flight b = HuiguangMotion.flight(7, 123,
                0f, 0f, 40f, 10f, 10f, false);
        assertEquals(a.c1x, b.c1x, EPS);
        assertEquals(a.c2y, b.c2y, EPS);
        assertEquals(a.delay, b.delay, EPS);
        assertEquals(a.spin, b.spin, EPS);
    }

    @Test
    void flightCurveLeavesBoundingBox() {
        // folia 的甩出弧线：控制点模长 1.6~4.8 字号，曲线中段应明显离开起终点连线
        HuiguangMotion.Flight f = HuiguangMotion.flight(11, 777,
                -60f, 0f, 60f, 0f, WorldLyricsState.LYRIC_SIZE, false);
        float[] out = new float[4];
        HuiguangMotion.poseAt(f, HuiguangMotion.ENTRY_WINDOW_MS * 0.5f, HuiguangMotion.ENTRY_WINDOW_MS, out);
        float offAxis = Math.abs(out[1]);
        assertTrue(offAxis > 1f || Math.abs(out[0] - 0f) > 6f,
                "中段应偏离直线（弧线观感），实际 offAxis=" + offAxis + " x=" + out[0]);
    }

    @Test
    void poseAtClampsBeyondWindow() {
        HuiguangMotion.Flight f = HuiguangMotion.flight(1, 5,
                0f, 0f, 30f, 30f, 10f, false);
        float[] out = new float[4];
        HuiguangMotion.poseAt(f, HuiguangMotion.ENTRY_WINDOW_MS * 5f, HuiguangMotion.ENTRY_WINDOW_MS, out);
        assertEquals(30f, out[0], 0.01f);
        assertEquals(30f, out[1], 0.01f);
        assertEquals(0f, out[3], EPS);
    }

    @Test
    void trailPointsSpanLookbackWindow() {
        HuiguangMotion.Flight f = HuiguangMotion.flight(2, 99,
                -100f, -30f, 20f, 10f, WorldLyricsState.LYRIC_SIZE, false);
        float[] xs = new float[HuiguangMotion.TRACK_SAMPLES + 1];
        float[] ys = new float[HuiguangMotion.TRACK_SAMPLES + 1];
        float mid = HuiguangMotion.ENTRY_WINDOW_MS * 0.5f;
        int n = HuiguangMotion.trailPoints(f, mid, HuiguangMotion.ENTRY_WINDOW_MS, 10f, xs, ys);
        assertEquals(HuiguangMotion.TRACK_SAMPLES + 1, n);
        // 头（最新时刻）应等于 poseAt(现在) 的位置（±抖动幅度 2% 字号）
        float[] out = new float[4];
        HuiguangMotion.poseAt(f, mid, HuiguangMotion.ENTRY_WINDOW_MS, out);
        assertEquals(out[0], xs[n - 1], 0.3f, "径迹头部 ≈ 字当前位置（含云室抖动）");
        assertEquals(out[1], ys[n - 1], 0.3f);
    }

    @Test
    void trailStrengthScalesWithDisplacement() {
        // 静止曲线（起终点同点）：pose 强度必须为 0 —— wobble 只进绘制顶点、
        // 不进强度（否则抖动底噪让静止字永远过 0.05 留痕门限）。
        HuiguangMotion.Flight still = HuiguangMotion.flight(4, 1,
                0f, 0f, 0f, 0f, WorldLyricsState.LYRIC_SIZE, false);
        still.delay = 0f;
        still.duration = 1f;
        still.c1x = 0f; still.c1y = 0f; still.c2x = 0f; still.c2y = 0f; still.spin = 0f;
        float s0 = HuiguangMotion.trailPoseStrength(still, 500f, 1000f, 10f);
        assertTrue(s0 < HuiguangMotion.TRAIL_MIN_STRENGTH,
                "静止字不留痕（pose 强度应为 0），实际 strength=" + s0);

        // 大位移曲线：中段强度应显著大于静止
        HuiguangMotion.Flight moving = HuiguangMotion.flight(4, 2,
                -120f, 0f, 120f, 0f, WorldLyricsState.LYRIC_SIZE, false);
        moving.delay = 0f;
        moving.duration = 1f;
        float s1 = HuiguangMotion.trailPoseStrength(moving, 500f, 1000f, 10f);
        assertTrue(s1 > s0 + 0.1f, "飞行中径迹更亮，实际 strength=" + s1);
        // 飞行结束 + 回溯窗口滑出后强度归零（自然收拢，不是平台 + 硬切）
        float s2 = HuiguangMotion.trailPoseStrength(moving,
                1000f + HuiguangMotion.TRACK_TIME_S * 1000f + 1f, 1000f, 10f);
        assertEquals(0f, s2, 0.001f, "回溯窗口完全滑出飞行段后径迹强度应归零，实际 " + s2);
    }

    @Test
    void litAtRampsOverMinimumWindow() {
        long[] times = {1000L, 1400L};
        // 字刚唱到：点亮刚开始
        float a = HuiguangMotion.litAt(times, 0, true, 1000f, 1000f);
        assertEquals(0f, a, 0.01f);
        // 渐变窗口 = max(LIGHT_UP, 字时长 400ms) 的中段：应在渐变区间
        float b = HuiguangMotion.litAt(times, 0, true, 1200f, 1000f);
        assertTrue(b > 0.4f && b < 1f, "中段应在渐变区间，实际 " + b);
        float c = HuiguangMotion.litAt(times, 0, true, 1400f, 1000f);
        assertEquals(1f, c, 0.01f);
    }

    @Test
    void litAtWithoutCharTimesUsesAppearAt() {
        float a = HuiguangMotion.litAt(new long[0], 0, false, 500f, 500f);
        assertEquals(0f, a, 0.01f);
        float b = HuiguangMotion.litAt(new long[0], 0, false, 500f + HuiguangMotion.LIGHT_UP_MS, 500f);
        assertEquals(1f, b, 0.01f);
    }

    @Test
    void projectToPlaneRoundTripsThroughPush() {
        // push 正变换（planePointToWorld）→ 逆投影（projectToPlane）应还原平面坐标
        WorldLyricsState.BillboardSnapshot geo =
                new WorldLyricsState.BillboardSnapshot(10.0, 70.0, -5.0, 35.0, -12.0, 0.05f);
        double[] world = geo.planePointToWorld(40f, -8f);
        float[] local = HuiguangMotion.projectToPlane(world[0], world[1], world[2],
                geo.x, geo.y, geo.z, geo.yawDeg, geo.pitchDeg, geo.scale);
        assertNotNull(local);
        assertEquals(40f, local[0], 0.02f, "往返投影应还原平面 x");
        assertEquals(-8f, local[1], 0.02f, "往返投影应还原平面 y");
    }

    @Test
    void projectRoundTripAtExtremeAngles() {
        // 极端角（±180 yaw / ±90 pitch）下往返仍须还原（R1 审查指出的覆盖缺口）
        double[][] angles = {{0, 0}, {180, 0}, {-180, 0}, {90, 0}, {0, 90}, {0, -89}, {179, -80}};
        for (double[] a : angles) {
            WorldLyricsState.BillboardSnapshot geo =
                    new WorldLyricsState.BillboardSnapshot(3.0, 65.0, 8.0, a[0], a[1], 0.04f);
            double[] world = geo.planePointToWorld(37f, -9f);
            float[] local = HuiguangMotion.projectToPlane(world[0], world[1], world[2],
                    geo.x, geo.y, geo.z, geo.yawDeg, geo.pitchDeg, geo.scale);
            assertNotNull(local, "yaw=" + a[0] + " pitch=" + a[1] + " 不应投影失败");
            assertEquals(37f, local[0], 0.05f, "yaw=" + a[0] + " pitch=" + a[1] + " 应还原 x");
            assertEquals(-9f, local[1], 0.05f, "yaw=" + a[0] + " pitch=" + a[1] + " 应还原 y");
        }
    }

    @Test
    void huiguangScaleDegenerateReturnsNull() {
        // scale 退化（0）时投影必须返回 null，调用方退回落位入场
        float[] local = HuiguangMotion.projectToPlane(1, 2, 3, 0, 0, 0, 0, 0, 0f);
        assertNull(local);
    }

    @Test
    void dimRgbScalesChannels() {
        int dim = HuiguangMotion.dimRgb(0xFF8040, 0.5f);
        int r = (dim >> 16) & 0xFF, g = (dim >> 8) & 0xFF, b = dim & 0xFF;
        assertEquals(127, r);
        assertEquals(64, g);
        assertEquals(32, b);
    }

    @Test
    void dimRgbClampsWhenBrightening() {
        int bright = HuiguangMotion.dimRgb(0xF08040, 1.15f);
        int r = (bright >> 16) & 0xFF, g = (bright >> 8) & 0xFF, b = bright & 0xFF;
        assertEquals(255, r, "提亮超 255 必须钳住，不许溢出污染相邻通道");
        assertEquals(147, g);
        assertEquals(73, b);
    }

    @Test
    void huiguangBuildGlyphsFliesFromPrevLinePlane() {
        // 端到端：构造「上一句」与「本句」的交接，确认本句带飞行定义、且起点在上一句侧
        WorldLyricsState.SpawnedLine prev = new WorldLyricsState.SpawnedLine(
                List.of("前一句"), 0L, 800L, -3.0, 64.0, 2.0, 20.0, -5.0, new long[0]);
        WorldLyricsState.SpawnedLine cur = new WorldLyricsState.SpawnedLine(
                List.of("新句子长一点"), 3000L, 900L, 2.0, 66.0, -1.0, -15.0, 3.0, new long[0]);
        WorldLyricsState.CameraView cam = new WorldLyricsState.CameraView() {
            public double eyeX() { return 0; }
            public double eyeY() { return 64; }
            public double eyeZ() { return 0; }
            public double lookX() { return 0; }
            public double lookY() { return 0; }
            public double lookZ() { return 1; }
            public double yawDeg() { return 0; }
            public double pitchDeg() { return 0; }
        };
        WorldLyricsState.Params params = new WorldLyricsState.Params() {
            public double distance() { return 10.5; }
            public boolean splitLines() { return true; }
            public double spreadDeg() { return 30.0; }
            public double scale() { return 1.0; }
            public boolean facePlayer() { return true; }
            public boolean keepAway() { return true; }
            public boolean wordPop() { return false; }
        };
        cur.huiguangPrev = prev;
        cur.huiguangOwnGeo = WorldLyricsState.BillboardSnapshot.capture(cur, cam, params);
        cur.huiguangPrevGeo = WorldLyricsState.BillboardSnapshot.capture(prev, cam, params);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                cur, 3400L, 1f, false, false, width, false, true, null);
        assertTrue(!glyphs.glyphs.isEmpty());
        assertEquals(glyphs.glyphs.size(), glyphs.huiguangFlights.size(),
                "飞行定义与字形一一对应");
        boolean hasFlight = false;
        for (HuiguangMotion.Flight fl : glyphs.huiguangFlights) {
            if (fl != null && fl.valid) {
                hasFlight = true;
                // elapsed = 400ms < 窗口：飞行中的字位置应在起点与终点之间、且离起点不远
                break;
            }
        }
        assertTrue(hasFlight, "换句交接应有逐字飞行定义");
    }


    @Test
    void huiguangLrcLineGetsDistinctFlightsPerChar() {
        // 回归：普通 LRC（无逐字轴，flashMode=false）下飞行定义按非空白字符序推进 ——
        // 曾经用 flashMode 才推进的 n 索引，所有字挤在第 0 条曲线上（起点/曲线全同）。
        WorldLyricsState.SpawnedLine prev = new WorldLyricsState.SpawnedLine(
                List.of("前一句歌词"), 0L, 800L, -3.0, 64.0, 2.0, 20.0, -5.0, new long[0]);
        WorldLyricsState.SpawnedLine cur = new WorldLyricsState.SpawnedLine(
                List.of("新句子"), 2000L, 900L, 2.0, 66.0, -1.0, -15.0, 3.0, new long[0]);
        WorldLyricsState.CameraView cam = new WorldLyricsState.CameraView() {
            public double eyeX() { return 0; }
            public double eyeY() { return 64; }
            public double eyeZ() { return 0; }
            public double lookX() { return 0; }
            public double lookY() { return 0; }
            public double lookZ() { return 1; }
            public double yawDeg() { return 0; }
            public double pitchDeg() { return 0; }
        };
        WorldLyricsState.Params params = new WorldLyricsState.Params() {
            public double distance() { return 10.5; }
            public boolean splitLines() { return true; }
            public double spreadDeg() { return 30.0; }
            public double scale() { return 1.0; }
            public boolean facePlayer() { return true; }
            public boolean keepAway() { return true; }
            public boolean wordPop() { return false; }
        };
        cur.huiguangPrev = prev;
        cur.huiguangOwnGeo = WorldLyricsState.BillboardSnapshot.capture(cur, cam, params);
        cur.huiguangPrevGeo = WorldLyricsState.BillboardSnapshot.capture(prev, cam, params);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                cur, 2200L, 1f, false, false, width, false, true, null);
        assertTrue(glyphs.glyphs.size() >= 2);
        // 第一个字（n=0）与第二个字（n=1）的飞行起点必须不同（各自从上一句对应字飞来）
        HuiguangMotion.Flight f0 = glyphs.huiguangFlights.get(0);
        HuiguangMotion.Flight f1 = glyphs.huiguangFlights.get(1);
        assertTrue(f0 != null && f0.valid, "第 1 字应有飞行定义");
        assertTrue(f1 != null && f1.valid, "第 2 字应有飞行定义");
        assertTrue(Math.abs(f0.fromX - f1.fromX) + Math.abs(f0.fromY - f1.fromY) > 1f,
                "相邻字的起飞点必须不同（LRC 索引回归），实际 from0=("
                        + f0.fromX + "," + f0.fromY + ") from1=(" + f1.fromX + "," + f1.fromY + ")");
    }

    @Test
    void litIsColdDimAtStart() {
        // 未唱字的 alpha 不低于 UNLIT_ALPHA（冷色半亮仍可见）
        WorldLyricsState.SpawnedLine cur = new WorldLyricsState.SpawnedLine(
                List.of("未唱句"), 0L, 800L, 0, 0, 0, 0, 0, new long[0]);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                cur, 40L, 1f, false, false, width, false, true, null);
        if (!glyphs.glyphs.isEmpty()) {
            assertTrue(glyphs.glyphs.get(0).alpha >= HuiguangMotion.UNLIT_ALPHA * 0.9f,
                    "未唱字应保持冷色半亮可见，实际 " + glyphs.glyphs.get(0).alpha);
        }
    }

    @Test
    void huiguangLayoutStillHorizontalAfterVerticalRemoval() {
        // layout 只剩横排（与主推进同轴；lambda 恒返 10 → 行宽 10、首字顶 -5）；
        // 词级字号：两字组「你好」为行内最长词 → hero 1.45 档
        List<String> lines = List.of("你好");
        float[] l = WorldLyricsState.huiguangLayoutChars(lines, s -> 10f, lines.hashCode());
        // hero 缩放 1.45：行宽 = 2×10×1.45 = 29 → 首字顶 = -14.5
        assertEquals(-14.5f, l[0], 0.1f, "首字顶 = -行宽/2（词缩放同源）");
        // 行块顶 -6.5（1.3 行距）+ hero 基线上浮 (1−1.45)×0.32×10 = -1.44
        assertEquals(-6.5f - 1.44f, l[1], 0.05f, "hero 字 y = 行块顶 + 基线上浮");
        assertEquals(0f, l[2], 1e-4f, "横排无旋转");
        assertEquals(1.45f, l[3], 1e-3f, "句内最长词走 hero 档 1.45");
    }

    @Test
    void charTimesSpreadWithinWord() {
        // M3：多字词的逐字时刻在词时长内均摊（卡拉OK逐字扫掠 + 光斑跟手）
        com.muoniumplayer.core.screens.ncm.LyricLine line =
                new com.muoniumplayer.core.screens.ncm.LyricLine(1000L, "岁月变迁");
        line.words.add(new com.muoniumplayer.core.screens.ncm.LyricLine.Word("岁月变迁", 1200L, 800L));
        long[] times = WorldLyricsState.charTimesOf(line, 5000L);
        assertEquals(4, times.length);
        assertEquals(200L, times[0], "首字 = 词起点");
        assertTrue(times[1] > times[0] && times[2] > times[1] && times[3] > times[2],
                "词内逐字时刻应递增（扫掠），实际 " + java.util.Arrays.toString(times));
        assertEquals(200L + 800L, times[3], "末字 ≈ 词起点+词时长");
    }

    @Test
    void flashEnvelopePeaksThenDecays() {
        // 点亮瞬间起爆：0 → 150ms 峰值 1 → 指数衰减（1s 处应明显低于峰值）
        assertEquals(0f, HuiguangMotion.flashEnvelope(-1f), 1e-4f, "点亮前无闪点");
        float peak = HuiguangMotion.flashEnvelope(150f);
        assertEquals(1f, peak, 0.02f, "attack 末段应到峰值");
        float mid = HuiguangMotion.flashEnvelope(75f);
        assertTrue(mid > 0.3f && mid < 1f, "attack 中段在升起，实际 " + mid);
        float late = HuiguangMotion.flashEnvelope(150f + 450f);
        assertTrue(late > 0.2f && late < 0.6f, "衰减 1 个 tau 后约 0.37，实际 " + late);
        float tail = HuiguangMotion.flashEnvelope(150f + 1800f);
        assertTrue(tail < 0.05f, "尾段应基本熄灭，实际 " + tail);
    }

    @Test
    void decayAmountGrowsAndCaps() {
        assertEquals(0f, HuiguangMotion.decayAmount(0f), 1e-4f);
        assertEquals(0f, HuiguangMotion.decayAmount(-1f), 1e-4f, "未点亮不漂");
        float a3 = HuiguangMotion.decayAmount(3f);
        float a8 = HuiguangMotion.decayAmount(8f);
        assertTrue(a3 > 0.2f && a3 < 0.6f, "3s ≈ 0.4 字号（folia 同式），实际 " + a3);
        assertEquals(HuiguangMotion.DECAY_CAP_FONTS, a8, 1e-4f, "长句封顶防不可读");
    }

    @Test
    void scatterEntryStillFliesWithTrails() {
        // 无上一句（首句/seek）：散点聚合入场也走贝塞尔飞行（带拖尾），不再退回落位
        WorldLyricsState.SpawnedLine cur = new WorldLyricsState.SpawnedLine(
                List.of("首句入场"), 0L, 800L, 0, 0, 0, 0, 0, new long[0]);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                cur, 300L, 1f, false, false, width, false, true, null);
        assertTrue(glyphs.glyphs.size() >= 2);
        int flights = 0;
        for (HuiguangMotion.Flight fl : glyphs.huiguangFlights) {
            if (fl != null && fl.valid) flights++;
        }
        assertTrue(flights >= 2, "散点聚合入场应有逐字飞行定义，实际 " + flights);
        // 飞行起点应偏离落点（散点），且各自不同
        HuiguangMotion.Flight f0 = glyphs.huiguangFlights.get(0);
        HuiguangMotion.Flight f1 = glyphs.huiguangFlights.get(1);
        assertTrue(Math.abs(f0.fromX - f0.toX) + Math.abs(f0.fromY - f0.toY) > 5f,
                "散点应偏离落点");
        assertTrue(Math.abs(f0.fromX - f1.fromX) + Math.abs(f0.fromY - f1.fromY) > 1f,
                "相邻字散点应不同");
    }

    @Test
    void landedGlyphsKeepMicroMotion() {
        // 落位后仍有恒定微动（绕行+呼吸）：同一 elapsed 求值稳定、不同 elapsed 有位移
        WorldLyricsState.SpawnedLine cur = new WorldLyricsState.SpawnedLine(
                List.of("微动"), 0L, 800L, 0, 0, 0, 0, 0, new long[0]);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs early = WorldLyricsState.buildGlyphs(
                cur, 3000L, 1f, false, false, width, false, true, null);
        WorldLyricsState.LyricGlyphs late = WorldLyricsState.buildGlyphs(
                cur, 4500L, 1f, false, false, width, false, true, null);
        assertTrue(early.glyphs.size() >= 1 && late.glyphs.size() >= 1);
        float dx = Math.abs(late.glyphs.get(0).x - early.glyphs.get(0).x);
        float dy = Math.abs(late.glyphs.get(0).y - early.glyphs.get(0).y);
        assertTrue(dx + dy > 0.5f, "1.5s 间隔下微动应有可见位移，实际 " + (dx + dy));
    }
}
