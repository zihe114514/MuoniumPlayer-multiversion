package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StereoDelayLineTest {

    @Test
    @DisplayName("传播延迟:0 是当前样本,分数部分插向更旧的一格")
    void fractionalDelayIsCausal() {
        final StereoDelayLine line = new StereoDelayLine();
        line.rebuild(8);
        for (int i = 0; i < 24; i++) {
            line.write(i, -i);
            assertEquals(i, line.left(0), 1e-9);
            if (i > 2) {
                assertEquals(i - 1.25, line.left(1.25), 1e-9);
                assertEquals(-(i - 1.25), line.right(1.25), 1e-9);
            }
        }
    }

    @Test
    @DisplayName("传播延迟:单帧脉冲在指定整数帧出现,不多延迟一帧")
    void impulseHasExactIntegerDelay() {
        final StereoDelayLine line = new StereoDelayLine();
        line.rebuild(32);
        for (int i = 0; i < 24; i++) {
            line.write(i == 0 ? 1f : 0f, i == 0 ? -1f : 0f);
            assertEquals(i == 7 ? 1f : 0f, line.left(7), 1e-9, "frame=" + i);
            assertEquals(i == 7 ? -1f : 0f, line.right(7), 1e-9, "frame=" + i);
        }
        line.flush();
        line.write(0, 0);
        for (int i = 0; i < 24; i++) {
            assertEquals(0, line.left(i), 1e-9);
            assertEquals(0, line.right(i), 1e-9);
        }
    }
}
