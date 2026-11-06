package com.muoniumplayer.core.cine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 卡点运镜给模板预设开的接缝：投影互逆、出厂值、套用时照样钳制。测试闸挡住一切文件读写。 */
class CinePresetSeamTest {

    @BeforeEach
    void reset() {
        CineSettings.resetForTest();
    }

    @Test
    void 读写投影互逆且出厂值同形() {
        JsonObject before = CineSettings.toJson();
        CineSettings.applyJson(before.deepCopy());
        assertEquals(before, CineSettings.toJson());
        assertEquals(before, CineSettings.defaultsJson(), "resetForTest 之后的状态就是出厂值");
    }

    @Test
    void 套用照样钳制且缺的键保留当前值() {
        CineSettings.setShotBeats(8);
        JsonObject partial = new JsonObject();
        partial.addProperty("enabled", true);
        partial.addProperty("idleSeconds", 1);
        CineSettings.applyJson(partial);
        assertTrue(CineSettings.isEnabled());
        assertEquals(CineSettings.IDLE_SECONDS_MIN, CineSettings.idleSeconds(), "越界值走与读档同一份钳制");
        assertEquals(8, CineSettings.shotBeats(), "没写到的键保持当前值");
    }
}
