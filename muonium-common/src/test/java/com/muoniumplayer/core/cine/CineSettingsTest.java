package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CineSettings 的 clamp/掩码/默认值纪律。
 *
 * <p>刻意不碰真实文件（{@code ConfigPaths.CINE} 指向游戏目录），只测内存
 * 路径的钳制与位掩码——文件读写由 JsonConfigStorage 的既有保障兜底，
 * 这里守的是「写进去的值永远合法」。</p>
 */
class CineSettingsTest {

    @BeforeEach
    void reset() {
        CineSettings.resetForTest();
    }

    @Test
    void 默认值() {
        assertFalse(CineSettings.isEnabled(), "总开关默认关（新装用户零打扰）");
        assertEquals(300, CineSettings.idleSeconds(), "对齐 Ambient Camera 的 idleSeconds 默认 300");
        assertEquals(4, CineSettings.shotBeats());
        assertEquals(0.7f, CineSettings.speedMultiplier(), 1.0e-6f, "对齐 Ambient Camera 的 cameraSpeed 默认 0.7");
        assertEquals(8f, CineSettings.fovPunchDegrees(), 1.0e-6f);
        assertTrue(CineSettings.hideHud());
        assertFalse(CineSettings.showCinematicBars(), "黑边默认关（口味问题）");
        assertEquals(13, CineSettings.cinematicBarPercent());
        assertEquals(0b00001, CineSettings.transitionMask(), "对齐 Ambient Camera:默认只开黑淡");
        assertEquals(1.0f, CineSettings.transitionSeconds(), 1.0e-6f, "对齐 fadeDurationSeconds 默认 1.0");
        assertEquals(32f, CineSettings.mobFocusRange(), 1.0e-6f, "对齐 mobFocusRange 默认 32");
        assertEquals(0.8f, CineSettings.trackingSoftness(), 1.0e-6f, "对齐 transitionSeconds(tracking) 默认 0.8");
        assertTrue(CineSettings.showStatusMessages());
        assertTrue(CineSettings.enableMobFocus());
        assertTrue(CineSettings.excludeHostileMobs(), "敌对默认排除");
        assertTrue(CineSettings.excludeBats());
    }

    @Test
    void 闲置秒数钳制() {
        CineSettings.setIdleSeconds(1);
        assertEquals(CineSettings.IDLE_SECONDS_MIN, CineSettings.idleSeconds(),
                "低于下限必须钳到 5s（防误触）");
        CineSettings.setIdleSeconds(99999);
        assertEquals(CineSettings.IDLE_SECONDS_MAX, CineSettings.idleSeconds());
        CineSettings.setIdleSeconds(30);
        assertEquals(30, CineSettings.idleSeconds());
    }

    @Test
    void 镜头拍数与速度钳制() {
        CineSettings.setShotBeats(0);
        assertEquals(CineSettings.SHOT_BEATS_MIN, CineSettings.shotBeats());
        CineSettings.setShotBeats(64);
        assertEquals(CineSettings.SHOT_BEATS_MAX, CineSettings.shotBeats());
        CineSettings.setSpeedMultiplier(-1f);
        assertEquals(CineSettings.SPEED_MIN, CineSettings.speedMultiplier(), 1.0e-6f,
                "负速度钳 0（静止镜头合法）");
        CineSettings.setSpeedMultiplier(99f);
        assertEquals(CineSettings.SPEED_MAX, CineSettings.speedMultiplier(), 1.0e-6f);
        // NaN 也要安全
        CineSettings.setSpeedMultiplier(Float.NaN);
        assertEquals(CineSettings.SPEED_MIN, CineSettings.speedMultiplier(), 1.0e-6f,
                "NaN 钳到下限,不能让运镜状态机中毒");
    }

    @Test
    void fovPunch与手持感钳制() {
        CineSettings.setFovPunchDegrees(-5f);
        assertEquals(0f, CineSettings.fovPunchDegrees(), 1.0e-6f, "0 = 关闭 punch 是合法值");
        CineSettings.setFovPunchDegrees(50f);
        assertEquals(CineSettings.FOV_PUNCH_MAX, CineSettings.fovPunchDegrees(), 1.0e-6f);
        CineSettings.setHandheldIntensity(2f);
        assertEquals(CineSettings.HANDHELD_MAX, CineSettings.handheldIntensity(), 1.0e-6f);
    }

    @Test
    void 黑边百分比钳制() {
        CineSettings.setCinematicBarPercent(-1);
        assertEquals(0, CineSettings.cinematicBarPercent());
        CineSettings.setCinematicBarPercent(50);
        assertEquals(CineSettings.BAR_PERCENT_MAX, CineSettings.cinematicBarPercent(),
                "超过 25% 屏高的黑边没意义,钳住");
    }

    @Test
    void 转场位掩码按位与过滤() {
        CineSettings.setTransitionMask(0b111);
        assertEquals(0b111, CineSettings.transitionMask());
        CineSettings.setTransitionMask(0b11111 | 0b1000000); // 高位垃圾位
        assertEquals(CineSettings.TRANSITION_MASK_ALL, CineSettings.transitionMask(),
                "越界位必须被 & 掩码滤掉");
        CineSettings.setTransitionMask(0);
        assertEquals(0, CineSettings.transitionMask(), "全关合法（纯硬切模式）");
    }

    @Test
    void force注入与快照投影() {
        CineSettings.forceForTest(true, 45, 0b00101);
        assertTrue(CineSettings.isEnabled());
        assertEquals(45, CineSettings.idleSeconds());
        assertEquals(0b00101, CineSettings.transitionMask());
        // JSON 投影含全部键（save 布局锚点：新增字段漏写这里立刻红）
        var root = CineSettings.snapshotForTest();
        for (String key : new String[]{"enabled", "idleSeconds", "shotBeats", "speedMultiplier",
                "fovPunchDegrees", "handheldIntensity", "hideHud", "showCinematicBars",
                "cinematicBarPercent", "transitionMask", "transitionSeconds",
                "enableMobFocus", "mobFocusRange", "excludeHostileMobs", "excludeBats",
                "trackingSoftness", "showStatusMessages"}) {
            assertTrue(root.has(key), "JSON 投影缺键：" + key);
        }
    }
}
