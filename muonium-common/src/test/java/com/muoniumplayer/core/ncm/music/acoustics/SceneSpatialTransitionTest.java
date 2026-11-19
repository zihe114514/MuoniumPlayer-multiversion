package com.muoniumplayer.core.ncm.music.acoustics;

import com.muoniumplayer.core.ncm.music.audio.OpenAlReverbBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 原生总线接入 Scene 后的朝向过渡,不能只测直接调用 bus 的静态方位。 */
class SceneSpatialTransitionTest {

    private OpenAlReverbBus bus;

    @AfterEach
    void clear() {
        SceneAcoustics.clearTarget();
        SceneAcoustics.flush();
        SceneAcoustics.render(new double[128], 2, 48000);
        if (bus != null) bus.close();
    }

    @Test
    @DisplayName("Scene 180度急转头不锁旧方向,两缓冲内朝向翻转")
    void antipodalHeadTurnDoesNotStick() throws Exception {
        bus = OpenAlReverbBus.createIfAvailable(48000, 512);
        assumeTrue(bus != null, "明确降级: 本环境不能创建HRTF loopback");
        assertFalse(bus.isBroken());
        SceneAcoustics.setListenerPose(0, 0, 0, 0, 0, 1, 0, 1, 0);
        SceneAcoustics.applyTarget(new RoomPlan(20000, 0, 1, 1, 0, 0, 0.28f,
                0, 0, 1, new float[0], new float[0], new float[0],
                1, DirectPath.FC_OPEN, 0, 4, true, 1, 0.5f, true, -4, 0, 0));
        SceneAcoustics.flush();
        for (int block = 0; block < 40; block++) SceneAcoustics.render(new double[128], 2, 48000);
        final Field orientation = OpenAlReverbBus.class.getDeclaredField("listenerOrientation");
        orientation.setAccessible(true);
        assertTrue(((float[]) orientation.get(bus))[2] > 0.99);
        SceneAcoustics.setListenerPose(0, 0, 0, 0, 0, -1, 0, 1, 0);
        SceneAcoustics.render(new double[128], 2, 48000);
        SceneAcoustics.render(new double[128], 2, 48000);
        assertTrue(((float[]) orientation.get(bus))[2] < -0.99,
                "不能每块归一回旧+Z方向");
        assertFalse(bus.isBroken());
    }
}
