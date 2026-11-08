package com.muoniumplayer.core.cine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinePresetSeamTest {
    @BeforeEach void reset() { CineSettings.resetForTest(); }
    @Test void 读写投影互逆且出厂值同形() {
        JsonObject before = CineSettings.toJson(); CineSettings.applyJson(before.deepCopy());
        assertEquals(before, CineSettings.toJson()); assertEquals(before, CineSettings.defaultsJson());
    }
    @Test void 套用照样钳制且缺的键保留当前值() {
        CineSettings.setShotBeats(8); JsonObject partial = new JsonObject(); partial.addProperty("enabled", true); partial.addProperty("idleSeconds", 1);
        CineSettings.applyJson(partial); assertTrue(CineSettings.isEnabled()); assertEquals(CineSettings.IDLE_SECONDS_MIN, CineSettings.idleSeconds()); assertEquals(8, CineSettings.shotBeats());
    }
}
