package com.muoniumplayer.core.settings;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresetSeamRoundTripTest {
    @Test void HudConfig读写投影互逆() {
        JsonObject before = HudConfig.toJson();
        try { HudConfig.applyJson(before.deepCopy()); assertEquals(before, HudConfig.toJson()); assertEquals(before.keySet(), HudConfig.defaultsJson().keySet()); }
        finally { HudConfig.applyJson(before); }
    }
    @Test void Folia背景读写投影互逆() {
        JsonObject before = FoliaBackgroundConfig.toJson();
        try { FoliaBackgroundConfig.applyJson(before.deepCopy()); assertEquals(before, FoliaBackgroundConfig.toJson()); assertEquals(before.keySet(), FoliaBackgroundConfig.defaultsJson().keySet()); }
        finally { FoliaBackgroundConfig.applyJson(before); }
    }
}
