package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「回弹胶囊」样式的离线探针（不碰 GL，不需要客户端）。
 *
 * <p>这一款是从作者另一份 Hypixel 端的 Dynamic Island 脚本里搬数值来的，全部数学都收在
 * {@link BounceIslandMath} 里，所以能在这里逐条对拍：弹簧必须真的过冲（否则就退化成
 * 其他七款那条低通通道，这一款存在的意义就没了）、圆角必须在两行以后不再跟高度、
 * 像素吸附必须在缩放 2/3 档时消掉半像素。</p>
 *
 * <p>后半截是「不许动旧样式」的守卫：新增样式必须追加在枚举末尾，因为
 * {@code HudConfig.dynamicIslandStyle} 持久化的是序号。</p>
 */
class BounceIslandMathProbeTest {

    /** 一帧一帧地把弹簧推到目标值，返回途中离目标最远的那一次（含越过目标的部分）。 */
    private static double runSpring(double from, double to, double dt, int frames,
                                    double[] maxValue) {
        BounceIslandMath.Spring spring = new BounceIslandMath.Spring(from);
        double furthest = Math.abs(to - from);
        for (int i = 0; i < frames; i++) {
            double value = spring.stepTo(to, dt);
            furthest = Math.max(furthest, Math.abs(value - from));
            if (maxValue != null) maxValue[0] = Math.max(maxValue[0], value);
        }
        assertEquals(to, spring.value(), 1e-6, "弹簧必须在有限帧内吸附到目标值");
        assertEquals(0.0, spring.velocity(), 1e-9, "吸附后速度必须清零，否则会永远留一丝抖动");
        return furthest;
    }

    @Test
    void springActuallyOvershootsWhichIsTheWholePoint() {
        // 60fps、120 -> 200。低通那一路的位移永远单调，不会超过 200；弹簧会。
        double[] peak = {0.0};
        runSpring(120.0, 200.0, 1.0 / 60.0, 240, peak);
        assertTrue(peak[0] > 200.0, "回弹样式必须过冲，实测峰值 " + peak[0]);
        assertTrue(peak[0] < 200.0 + 0.5 * 80.0, "过冲要收敛，不能弹成振荡器");
    }

    @Test
    void springSettlesInAboutTheReferenceDuration() {
        // 参考稿观感是「一下弹到位」，一秒半还在动就说明阻尼给小了。
        long frames = 0L;
        BounceIslandMath.Spring spring = new BounceIslandMath.Spring(120.0);
        while (frames < 600L && (Math.abs(spring.value() - 200.0) > 0.02
                || Math.abs(spring.velocity()) > 0.1)) {
            spring.stepTo(200.0, 1.0 / 60.0);
            frames++;
        }
        assertTrue(frames <= 90L, "60fps 下应在约一秒内收住，实际 " + frames + " 帧");
    }

    @Test
    void frameDeltaIsClampedSoOneStallCannotBlowUpTheSpring() {
        // 卡了四秒：dt 必须被钳到 1/20，否则 170 的刚度会直接把尺寸甩飞。
        assertEquals(BounceIslandMath.MAX_FRAME_SECONDS,
                BounceIslandMath.frameSeconds(5_000_000_000L, 1_000_000_000L), 1e-9);
        assertEquals(1.0 / 240.0, BounceIslandMath.frameSeconds(1_001_000_000L, 1_000_000_000L),
                1e-9, "240fps 以上不再加速");
        assertEquals(1.0 / 60.0, BounceIslandMath.frameSeconds(123L, 0L), 1e-9, "首帧按 60fps 起步");
    }

    @Test
    void radiusIsPillForOneRowAndStopsTrackingHeightAfterwards() {
        assertEquals(16.0, BounceIslandMath.pillRadius(32.0), 1e-9);
        assertEquals(16.0, BounceIslandMath.pillRadius(64.0), 1e-9, "两行以后圆角封顶，不再跟着变");
        assertEquals(8.0, BounceIslandMath.pillRadius(16.0), 1e-9, "收起过程中仍是标准胶囊");
    }

    @Test
    void pixelSnappingRemovesTheHalfPixelAtGuiScaleTwoAndThree() {
        // 缩放 2 时 0.5 的坐标偏移会画成一根半透明边；吸附之后必须落在整数物理像素上。
        assertEquals(60.0, BounceIslandMath.alignFloor(60.4, 2.0), 1e-9);
        assertEquals(120.5, BounceIslandMath.alignFloor(120.9, 2.0), 1e-9, "只丢半个物理像素，不多不少");
        assertEquals(60.5, BounceIslandMath.alignUp(60.1, 2.0), 1e-9);
        assertEquals(61.0, BounceIslandMath.alignSize(60.1, 2.0), 1e-9, "宽度还要补回一个物理像素");
        // 缩放 1 时 GUI 单位就等于物理像素，小数坐标本身就是半像素边，照样要吸附。
        assertEquals(60.0, BounceIslandMath.alignFloor(60.37, 1.0), 1e-9);
        assertEquals(62.0, BounceIslandMath.alignSize(60.37, 1.0), 1e-9, "向上取整到 61 再补一个整像素");
        // 拿不到缩放值（<1，防御性分支）时必须原样返回，否则等于给旧逻辑引入误差。
        assertEquals(60.37, BounceIslandMath.alignFloor(60.37, 0.5), 1e-9);
        assertEquals(60.37, BounceIslandMath.alignSize(60.37, 0.5), 1e-9);
    }

    @Test
    void rowFadesInOver180msAndOutOverTheLast260ms() {
        assertEquals(0.0, BounceIslandMath.rowAlpha(0L, 2_200L), 1e-9);
        assertEquals(1.0, BounceIslandMath.rowAlpha(180L, 2_200L), 1e-9);
        assertEquals(1.0, BounceIslandMath.rowAlpha(1_000L, 2_200L), 1e-9);
        // 退出那条 260ms 是淡出窗口：还剩 100ms 时已经在往下走，剩 50ms 时过半。
        assertTrue(BounceIslandMath.rowAlpha(2_100L, 2_200L) < 1.0, "还剩 100ms 时应当已经在淡出");
        assertTrue(BounceIslandMath.rowAlpha(2_150L, 2_200L) < 0.5, "还剩 50ms 时应当已经淡掉一半");
        assertEquals(0.0, BounceIslandMath.rowAlpha(2_200L, 2_200L), 1e-9);
        assertEquals(0.0, BounceIslandMath.rowAlpha(9_000L, 2_200L), 1e-9, "过期之后不许回弹成不透明");
    }

    @Test
    void percentRampGoesFromNearWhiteToCyanBlue() {
        assertEquals(0xFFF7F7F9, BounceIslandMath.percentColor(0.0));
        // 梯度终点：R 247-162=85、G 247+8=255、B 249-164=85，也就是 0xFF55FF55。
        assertEquals(0xFF55FF55, BounceIslandMath.percentColor(1.0));
        int middle = BounceIslandMath.percentColor(0.5);
        assertTrue(((middle >> 16) & 255) < 200 && ((middle >> 8) & 255) > 200,
                "中途应该已经开始偏蓝：" + Integer.toHexString(middle));
    }

    @Test
    void tintStepsTowardTheSemanticColorAndNeverLeavesRange() {
        BounceIslandMath.Tint tint = new BounceIslandMath.Tint();
        for (int i = 0; i < 600; i++) {
            tint.stepTo(BounceIslandMath.TINT_CATEGORY_DANGER, 1.0 / 60.0);
        }
        assertEquals(0x24, tint.redChannel(), 1.0);
        assertEquals(0x0A, tint.greenChannel(), 1.0);
        assertEquals(0x0C, tint.blueChannel(), 1.0);
        for (int i = 0; i < 600; i++) {
            tint.stepTo(BounceIslandMath.TINT_CATEGORY_SUCCESS, 1.0 / 60.0);
        }
        assertEquals(0x0A, tint.redChannel(), 1.0);
        assertEquals(0x20, tint.greenChannel(), 1.0);
    }

    @Test
    void progressSmoothingNeverCrossesItsTarget() {
        BounceIslandMath.DampedValue value = new BounceIslandMath.DampedValue();
        value.snapTo(0.0);
        double peak = 0.0;
        for (int i = 0; i < 300; i++) {
            peak = Math.max(peak, value.stepTo(1.0, 1.0 / 60.0));
        }
        assertTrue(peak <= 1.0 + 1e-9, "进度条不许过冲，越过会在 100% 处顶一下再退回");
        assertEquals(1.0, value.value(), 1e-6);
    }

    @Test
    void surfaceAlphaIsOpaqueButNotFullySolid() {
        // 必须用无符号右移：0xF0 塞进符号位后算术右移会填成 0xFF，读出来永远是 255。
        assertEquals(240, (BounceIslandMath.argb(BounceIslandMath.SURFACE_ALPHA, 8, 8, 10) >>> 24) & 255);
        assertEquals(0x08080A, BounceIslandMath.argb(255, 8, 8, 10) & 0xFFFFFF);
        assertEquals(0, (BounceIslandMath.alphaOf(0xFF102030, 0.0) >> 24) & 255);
    }

    /** 新增样式必须追加在末尾：序号是持久化值，中间插一项会把所有人已选的样式改掉。 */
    @Test
    void existingStylesKeepTheirOrdinalsAndTheNewOneIsAppended() {
        DownloadDynamicIsland.DynamicIslandStyle[] styles =
                DownloadDynamicIsland.DynamicIslandStyle.values();
        String[] order = {"PILL", "GLASS", "COMPACT", "CARD", "SYSTEM_CARD", "MUSIC_FOCUS",
                "LIQUID_GLASS", "BOUNCE"};
        assertEquals(order.length, styles.length);
        for (int i = 0; i < order.length; i++) {
            assertEquals(order[i], styles[i].name(), "样式序号 " + i + " 变了，用户配置会串样式");
        }
        assertEquals("经典胶囊", styles[0].getDisplayName());
        assertEquals("液态玻璃", styles[6].getDisplayName());
    }

    @Test
    void geometryConstantsMatchTheReference() {
        assertEquals(32.0, BounceIslandMath.ROW_HEIGHT, 1e-9);
        assertEquals(7.0, BounceIslandMath.TOP, 1e-9);
        assertEquals(30.0, BounceIslandMath.ICON_SIZE, 1e-9);
        assertEquals(28.0, BounceIslandMath.LOGO_AREA_WIDTH, 1e-9);
        assertEquals(4, BounceIslandMath.MAX_ROWS);
        assertEquals(180L, BounceIslandMath.ENTER_MILLIS);
        assertEquals(260L, BounceIslandMath.EXIT_MILLIS);
    }
}
