package com.muoniumplayer.core.settings;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 设置类给模板预设开的接缝：{@code applyJson(toJson())} 必须是恒等操作 —— 读档逻辑与写盘投影
 * 两边对不上（某个键写了读不回、或读档时改写了值），预设套用就会悄悄改掉玩家没碰过的设置。
 *
 * <p>{@code HudConfig} 各版本树一份，这条测试在七棵树上各跑一次，任何一棵树的两半对不上都会红。
 * 测试只把同样的值写回去，不改变进程里的设置状态，也不碰磁盘。</p>
 */
class PresetSeamRoundTripTest {

    @Test
    void HudConfig读写投影互逆() {
        JsonObject before = HudConfig.toJson();
        HudConfig.applyJson(before.deepCopy());
        assertEquals(before, HudConfig.toJson());
        assertEquals(before.keySet(), HudConfig.defaultsJson().keySet(), "出厂值快照与当前快照的键集合必须一致");
    }

    @Test
    void Folia背景读写投影互逆() {
        JsonObject before = FoliaBackgroundConfig.toJson();
        FoliaBackgroundConfig.applyJson(before.deepCopy());
        assertEquals(before, FoliaBackgroundConfig.toJson());
        assertEquals(before.keySet(), FoliaBackgroundConfig.defaultsJson().keySet());
    }
}
