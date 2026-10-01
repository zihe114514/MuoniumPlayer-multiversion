package com.muoniumplayer.render.skija;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预糊分档策略<b>真跑</b>：这个类是纯数组算术，所以这里不是读源码形状，而是直接驱动它。
 *
 * <p>钉住的四件事，每一件都对应一种「只有跑起来才会付钱」的失败：</p>
 * <ol>
 *   <li>同屏两面不同强度时<b>不能互顶</b>——一格缓存的老形状是 A 把 B 顶掉、下一帧 B 再把 A 顶掉，
 *       于是每帧两遍全屏高斯；多槽之后第二遍根本不该发生；</li>
 *   <li>一次采集的重建次数<b>封顶</b>——预算花完必须改成「借最近的档」，而不是继续糊；</li>
 *   <li>采集往前走一格，<b>所有档一起作废</b>——否则实时档下玻璃透的是十秒前的画面；</li>
 *   <li>槽位用光时挑<b>最久没用的</b>那个下手，而不是固定的 0 号槽——固定 0 号会让第一面
 *       每次都被抢，等于回到一格缓存。</li>
 * </ol>
 */
class GlassBlurLevelsProbeTest {

    private static final float KEY_PANEL = 24f;    // sigma 12 → round(12*2)
    private static final float KEY_ISLAND = 16f;   // sigma 8
    private static final float KEY_LYRIC = 28f;    // sigma 14
    private static final float KEY_EDITOR = 20f;   // sigma 10

    @Test
    @DisplayName("同一代里命中就是命中；采集往前走一格就全部要重糊")
    void hitRequiresSameGeneration() {
        GlassBlurLevels levels = new GlassBlurLevels();
        levels.beginCapture(1L);
        int slot = levels.allocate(KEY_PANEL, 1L);
        levels.markBuilt(slot, KEY_PANEL, 1L);

        assertEquals(slot, levels.find(KEY_PANEL, 1L), "同档同代必须命中，命中就是白捡一遍全屏高斯");
        // 采集往前走了一格：内容已经不是这一帧的了
        levels.beginCapture(2L);
        assertEquals(GlassBlurLevels.NO_SLOT, levels.find(KEY_PANEL, 2L),
                "代数变了还判命中，玻璃透的就是上一次采集的画面");
    }

    @Test
    @DisplayName("两面不同强度同屏：各自占一格，谁也不许把谁顶掉")
    void twoTiersCoexist() {
        GlassBlurLevels levels = new GlassBlurLevels();
        levels.beginCapture(10L);

        int panelSlot = levels.allocate(KEY_PANEL, 10L);
        levels.markBuilt(panelSlot, KEY_PANEL, 10L);
        int islandSlot = levels.allocate(KEY_ISLAND, 10L);
        levels.markBuilt(islandSlot, KEY_ISLAND, 10L);
        assertNotEquals(panelSlot, islandSlot, "第二档落在了第一档那一格上 = 旧的一格缓存形状");

        // 下一帧：两面各自再要一次，都不该重建
        assertEquals(panelSlot, levels.find(KEY_PANEL, 10L));
        assertEquals(islandSlot, levels.find(KEY_ISLAND, 10L));
        assertEquals(2, levels.rebuildsThisCapture(), "两档各建一次，之后一律命中");
        assertTrue(levels.overBudget(),
                "两档正是预算上限：再来第三种强度就该借档，而不是在同一帧里糊第三遍");
    }

    @Test
    @DisplayName("预算花完之后不再糊第三档，改借最接近的那一档")
    void thirdTierBorrowsInsteadOfRebuilding() {
        GlassBlurLevels levels = new GlassBlurLevels();
        levels.beginCapture(7L);
        levels.markBuilt(levels.allocate(KEY_PANEL, 7L), KEY_PANEL, 7L);
        levels.markBuilt(levels.allocate(KEY_ISLAND, 7L), KEY_ISLAND, 7L);
        assertTrue(levels.overBudget(), "两档建完就该到预算上限");

        // sigma 14 那一面：本代已有 12 与 8，12 离它更近
        int borrowed = levels.nearest(KEY_LYRIC, 7L);
        assertTrue(borrowed >= 0, "本代已经建过档，就该有得借");
        assertEquals(KEY_PANEL, levels.keyAt(borrowed),
                "借错了档：模糊强度会偏得比必要的更远，而这在糊着的底上是看不出来的");
        assertEquals(2, levels.rebuildsThisCapture(), "借档不该再花一次重建预算");
    }

    @Test
    @DisplayName("本代一档都没建过时不许借：宁可我糊，也不能返回没有背景")
    void firstTierOfFreshCaptureAlwaysBuilds() {
        GlassBlurLevels levels = new GlassBlurLevels();
        levels.beginCapture(1L);
        levels.markBuilt(levels.allocate(KEY_PANEL, 1L), KEY_PANEL, 1L);
        levels.markBuilt(levels.allocate(KEY_ISLAND, 1L), KEY_ISLAND, 1L);
        assertTrue(levels.overBudget(), "这一代的两档预算已经花完");

        // 采集往前走一格：老档内容已经不是这一帧的了，既不能借，预算也必须重新发
        levels.beginCapture(99L);
        assertEquals(GlassBlurLevels.NO_SLOT, levels.nearest(KEY_LYRIC, 99L),
                "借到上一次采集的档 = 玻璃透十秒前的画面");
        assertFalse(levels.overBudget(),
                "新代数的预算要重新发；不重置就会在第一次采集之后永远只肯借档");
    }

    @Test
    @DisplayName("槽位分配：空槽优先，其次过期槽，全新才按最久没用下手")
    void victimOrderPrefersEmptyThenStaleThenLru() {
        GlassBlurLevels levels = new GlassBlurLevels();
        levels.beginCapture(1L);
        int first = levels.allocate(KEY_PANEL, 1L);
        levels.markBuilt(first, KEY_PANEL, 1L);
        int second = levels.allocate(KEY_ISLAND, 1L);
        levels.markBuilt(second, KEY_ISLAND, 1L);
        assertNotEquals(first, second, "还有空槽就去覆盖已建的档，等于自废多槽");

        // 四槽全在本代上：先 touch 一次第一档，之后被牺牲的必须是它之外最久没用的那格
        int third = levels.allocate(KEY_LYRIC, 1L);
        levels.markBuilt(third, KEY_LYRIC, 1L);
        int fourth = levels.allocate(KEY_EDITOR, 1L);
        levels.markBuilt(fourth, KEY_EDITOR, 1L);
        levels.touch(first);
        levels.touch(second);
        levels.touch(third);
        int victim = levels.allocate(40f, 1L);
        assertEquals(fourth, victim, "全槽都在本代时该牺牲最久没被用的那一格");
    }

    @Test
    @DisplayName("reset 之后一切当作没糊过（尺寸变了、层停用了都走这条）")
    void resetClearsEverything() {
        GlassBlurLevels levels = new GlassBlurLevels();
        levels.beginCapture(3L);
        levels.markBuilt(levels.allocate(KEY_PANEL, 3L), KEY_PANEL, 3L);
        levels.reset();
        assertEquals(GlassBlurLevels.NO_SLOT, levels.find(KEY_PANEL, 3L));
        assertEquals(0, levels.builtThisCapture(3L));
        assertEquals(0, levels.rebuildsThisCapture());
    }

    @Test
    @DisplayName("槽位数就是生效面的数量：四面各自一档，不留浪费也不留排队")
    void slotCountMatchesSurfaceCount() {
        assertEquals(4, GlassBlurLevels.SLOTS,
                "四个生效面（播放器/岛/桌面歌词/编辑器）各一档；改这个数字要连同 HudConfig 的倍率数量一起想");
        assertEquals(2, GlassBlurLevels.REBUILDS_PER_CAPTURE,
                "一次采集最多糊两档：这是「常见同屏两档全精确」与「实时档每帧多付一遍全屏高斯」之间的取舍");
    }
}
