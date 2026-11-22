package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomPlanTest {

    @Test
    @DisplayName("房间交接复制输入抽头,过渡复制保留绕射路径与到达坐标")
    void plansOwnTheirArraysAndKeepArrivalPath() {
        final float[] delay = {0.01f};
        final float[] left = {0.1f};
        final float[] right = {0.3f};
        final float[] high = {0.42f};
        final RoomPlan plan = new RoomPlan(20000, 0.4f, 0.9f, 1,
                0.004f, 0.5f, 0.28f, 0.6f, 0.3f, 1, delay, left, right, high,
                0.1f, 12000, 0.6f, 10, true, 1, 0.5f,
                true, 2, 64, 3, 0, true, 12);
        delay[0] = 0.15f;
        left[0] = 1f;
        right[0] = 1f;
        assertEquals(0.01f, plan.tapDelaySec[0]);
        assertEquals(0.1f, plan.tapGainL[0]);
        assertEquals(0.3f, plan.tapGainR[0]);
        assertEquals(0.42f, plan.tapHighRatio[0]);
        final RoomPlan transition = plan.withTransition(0.18f);
        assertTrue(transition.diffracted);
        assertEquals(12f, transition.directPathMeters);
        assertEquals(10f, transition.directDistanceMeters);
        assertEquals(2, transition.speakerX);
        assertEquals(64, transition.speakerY);
        assertEquals(3, transition.speakerZ);
        assertNotSame(plan.tapDelaySec, transition.tapDelaySec);
    }

    @Test
    @DisplayName("坏采样不能把NaN目标灌入长期滤波状态")
    void invalidNumbersFailOpen() {
        final RoomPlan plan = new RoomPlan(Double.NaN, Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                new float[] {Float.NaN, 0.02f}, new float[] {Float.NaN}, new float[] {Float.NaN},
                Float.NaN, Double.NaN, Float.NaN, Float.NaN,
                true, Float.NaN, Float.NaN, true, Double.NaN, 0, 0,
                Float.NaN, true);
        assertEquals(SceneAcoustics.bandOpenHz(), plan.bandHz);
        assertEquals(0, plan.wet);
        assertEquals(1, plan.dryGain);
        assertEquals(1, plan.loudness);
        assertEquals(1, plan.directGain);
        assertEquals(DirectPath.FC_OPEN, plan.directCutoffHz);
        assertEquals(1, plan.tapCount, "长度按最短数组收束");
        assertEquals(0, plan.tapGainL[0]);
        assertTrue(!plan.hasSpeakerAnchor && !plan.useOpenAlReverb);
    }
}
