package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyReflectionsTest {

    private static final int RATE = 48000;

    private static RoomPlan tap(float delay, float pan) {
        return new RoomPlan(20000, 0.5f, 1f, 1f, 0, 0.3f, 0.28f,
                0.3f, 0.2f, 1,
                new float[] {delay}, new float[] {(float) DirectPath.gainL(pan)},
                new float[] {(float) DirectPath.gainR(pan)});
    }

    @Test
    @DisplayName("转头但几何不变:反射 ITD 与 ILD 一起更新,不留旧方向")
    void headTurnUpdatesBothEarCues() {
        final EarlyReflections er = new EarlyReflections();
        er.rebuild(RATE);
        final float[] out = new float[2];
        final RoomPlan left = tap(0.010f, -0.6f);
        er.update(left, 64);
        final RoomPlan right = tap(0.010f, 0.6f);
        for (int block = 0; block < 400; block++) {
            er.update(right, 64);
            for (int f = 0; f < 64; f++) {
                er.process(0, 0, out);
            }
        }
        int firstL = -1;
        int firstR = -1;
        double energyL = 0;
        double energyR = 0;
        for (int f = 0; f < 1024; f++) {
            if (f % 64 == 0) {
                er.update(right, 64);
            }
            er.process(f == 0 ? 0.5f : 0, f == 0 ? 0.5f : 0, out);
            if (Math.abs(out[0]) > 1e-4 && firstL < 0) firstL = f;
            if (Math.abs(out[1]) > 1e-4 && firstR < 0) firstR = f;
            energyL += out[0] * out[0];
            energyR += out[1] * out[1];
        }
        assertTrue(firstR >= 0 && firstL > firstR, "右耳应先到: L=" + firstL + " R=" + firstR);
        assertTrue(energyR > energyL * 3, "右耳应更响: L=" + energyL + " R=" + energyR);
    }

    @Test
    @DisplayName("150ms 新计划不能饿死反射组交叉淡化")
    void repeatedPlansDoNotRestartFade() {
        final EarlyReflections er = new EarlyReflections();
        er.rebuild(RATE);
        final RoomPlan start = tap(0.010f, -1f);
        final float[] out = new float[2];
        er.update(start, 64);
        for (int f = 0; f < RATE; f++) {
            er.process(0, 0, out);
        }
        double leftEnergy = 0;
        double rightEnergy = 0;
        RoomPlan next = tap(0.020f, 1f);
        for (int f = 0; f < RATE * 2; f += 64) {
            if (f % 7168 == 0) {
                next = tap((f / 7168 & 1) == 0 ? 0.020f : 0.021f, 1f);
            }
            er.update(next, 64);
            for (int i = 0; i < 64; i++) {
                final float pulse = (f + i) % 211 == 0 ? 0.2f : 0;
                er.process(pulse, pulse, out);
                if (f > RATE) {
                    leftEnergy += out[0] * out[0];
                    rightEnergy += out[1] * out[1];
                }
            }
        }
        assertTrue(rightEnergy > 1e-5, "新反射组必须可闻");
        assertTrue(leftEnergy < rightEnergy * 0.01,
                "旧的左偏组应完成退出: L=" + leftEnergy + " R=" + rightEnergy);
    }
}
