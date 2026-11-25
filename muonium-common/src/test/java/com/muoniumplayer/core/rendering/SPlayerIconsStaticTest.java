package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPlayer 风格主界面图标表的静态测试。
 *
 * <p>{@link SPlayerIconData} 由脚本把 lucide 的 circle/rect/line/poly* 换算成 path 字符串；
 * 换算写坏不会报错，只会在运行时画不出来（{@link SPlayerIcons#draw} 返回 false，界面上缺一枚
 * 图标）。这里逐枚解析，并确认几何落在自己的网格里。控制点盒对圆弧偏大，所以只给一格余量。</p>
 */
@RequiresSkijaNative
class SPlayerIconsStaticTest {

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded(), "Skija 原生库加载失败: " + SkijaNatives.failure());
    }

    @Test
    @DisplayName("每枚 SPlayer 图标都能解析，且几何落在自己的网格内")
    void everyIconParsesInsideItsGrid() {
        Set<String> names = SPlayerIcons.namesForTest();
        assertTrue(names.size() >= 100, "图标表几乎是空的：" + names.size());
        for (String name : names) {
            Path path = SPlayerIcons.pathForTest(name);
            assertNotNull(path, name + " 的路径解析不出来");
            Rect bounds = path.getBounds();
            double grid = SPlayerIcons.gridForTest(name);
            double slack = grid / 24.0 * 3.0;
            assertTrue(bounds.getWidth() > 0 || bounds.getHeight() > 0, name + " 没有几何");
            assertTrue(bounds.getLeft() >= -slack && bounds.getTop() >= -slack
                            && bounds.getRight() <= grid + slack && bounds.getBottom() <= grid + slack,
                    name + " 跑出网格：" + bounds);
        }
    }

    @Test
    @DisplayName("主界面直接引用的图标常量都在表里")
    void referencedConstantsExist() {
        String[] used = {SPlayerIcons.HOME, SPlayerIcons.HEART, SPlayerIcons.PLAY, SPlayerIcons.PAUSE,
                SPlayerIcons.SKIP_BACK, SPlayerIcons.SKIP_FORWARD, SPlayerIcons.REPEAT, SPlayerIcons.REPEAT_ONE,
                SPlayerIcons.SHUFFLE, SPlayerIcons.PLAY_ORDER, SPlayerIcons.HEART_MODE, SPlayerIcons.LIST_MUSIC,
                SPlayerIcons.SEARCH, SPlayerIcons.SETTINGS, SPlayerIcons.CHEVRON_LEFT, SPlayerIcons.CHEVRON_RIGHT,
                SPlayerIcons.VOLUME_2, SPlayerIcons.CAPTIONS, SPlayerIcons.ELLIPSIS_VERTICAL, SPlayerIcons.DICES,
                SPlayerIcons.CALENDAR_DAYS, SPlayerIcons.RADIO, SPlayerIcons.HEADPHONES};
        for (String name : used) {
            assertTrue(SPlayerIcons.has(name), name + " 不在图标表里");
        }
    }
}
