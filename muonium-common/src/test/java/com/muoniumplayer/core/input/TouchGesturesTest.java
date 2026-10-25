package com.muoniumplayer.core.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TouchGestures} 状态机的合成帧测试——它是纯逻辑（时间与按键都由参数喂入），
 * 不需要 Minecraft 环境，这正是把识别器做成「喂数式」而不是自己去轮询 Mouse 的回报：
 * 阈值、锁轴、认领、长按、点按结算这些触屏手感的地基可以在内存里真跑，而不是只在
 * 真机上猜。
 */
class TouchGesturesTest {

    private static final Object PANEL_A = new Object();
    private static final Object PANEL_B = new Object();

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    @Test
    void 阈值内的抖动始终是点按不是拖动() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(20), 103, 98, true);
        g.advance(ms(40), 97, 103, true);
        g.advance(ms(60), 100, 100, true);
        assertTrue(g.isPressed());
        assertEquals(TouchGestures.Axis.NONE, g.axis());
        assertFalse(g.isVerticalDrag());
        // 阈值内的移动不产生拖动增量，滚动容器读到的永远是 0。
        assertEquals(0.0, g.dy(), 1e-9);
        g.advance(ms(80), 100, 100, false);
        assertTrue(g.consumeTap());
        assertFalse(g.lastPressWasDrag());
    }

    @Test
    void 超过阈值后锁纵向轴并只允许一个认领者() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(16), 100, 104, true);
        g.advance(ms(32), 100, 112, true);   // 累计 12px > 8，纵向优势
        assertTrue(g.isVerticalDrag());
        assertTrue(g.claim(PANEL_A));
        assertFalse(g.claim(PANEL_B), "第二个容器不许抢已认领的手势");
        assertEquals(PANEL_A, g.claimedBy());
        // 本帧增量来自锁轴后的原始 dy（8px 向下）。
        assertEquals(8.0, g.dy(), 1e-9);
        // 认领只对纵向拖动开放：横向拖动/非拖动状态一律 false。
        g.advance(ms(48), 100, 120, false);
        assertFalse(g.isVerticalDrag());
        assertNull(g.claimedBy());
        assertFalse(g.claim(PANEL_A));
        // 拖动过的按压在松手后不再是点按。
        assertFalse(g.consumeTap());
        assertTrue(g.lastPressWasDrag());
    }

    @Test
    void 横向移动锁横轴且纵向容器无法认领() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(16), 108, 101, true);
        g.advance(ms(32), 118, 103, true);   // dx=18、dy=3，横向优势
        assertTrue(g.isHorizontalDrag());
        assertFalse(g.isVerticalDrag());
        assertFalse(g.claim(PANEL_A), "横向拖动不允许纵向认领");
        assertEquals(0.0, g.dy(), 1e-9);
        g.advance(ms(48), 120, 103, false);
        assertFalse(g.consumeTap());
    }

    @Test
    void 上滑的速度为负供甩动换算方向() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 300, true);
        for (int frame = 1; frame <= 6; frame++) {
            g.advance(ms(frame * 16), 100, 300 - frame * 15.0, true);   // ~15px/16ms 向上
        }
        assertTrue(g.isVerticalDrag());
        assertTrue(g.velocityY() < -0.5, "拖动中的实时速度应当已经收敛为负（向上）");
        g.advance(ms(112), 100, 210, false);
        assertTrue(g.velocityY() < -0.5, "松手后速度冻结供甩动消费");
        assertFalse(g.consumeTap());
    }

    @Test
    void 长按边沿只出现一帧且被锁轴取消() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(100), 100, 100, true);
        g.advance(ms(200), 100, 100, true);
        g.advance(ms(300), 100, 100, true);
        assertFalse(g.longPressElapsed());
        g.advance(ms(400), 100, 100, true);   // 400ms ≥ 350ms
        assertTrue(g.longPressElapsed());
        g.advance(ms(416), 100, 100, true);
        assertFalse(g.longPressElapsed(), "边沿只在该帧出现一次");
        // 长按成立后开始拖动：锁轴正常，长按边沿不再来。
        g.advance(ms(432), 100, 115, true);
        assertTrue(g.isVerticalDrag());
        assertFalse(g.longPressElapsed());
    }

    @Test
    void 先动后停的按压长按不作数但仍是拖动() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(16), 100, 110, true);    // 10px > 阈值，立刻锁纵向
        for (long t = 32; t <= 500; t += 32) {
            g.advance(ms(t), 100, 110, true); // 之后一直按住不动
        }
        assertTrue(g.isVerticalDrag());
        assertFalse(g.longPressElapsed(), "锁轴后的按住不触发长按");
    }

    @Test
    void 断档太久自动复位不带旧按压进入新界面() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(16), 100, 112, true);
        assertTrue(g.isVerticalDrag());
        // 切后台 300ms 再回来，手指已松开。
        g.advance(ms(316), 100, 112, false);
        assertFalse(g.isPressed());
        assertFalse(g.consumeTap());
        // 断档期间一直按着：按压保留但速度清零重新采样。
        TouchGestures h = TouchGestures.newInstanceForTest();
        h.advance(ms(0), 50, 50, true);
        h.advance(ms(400), 50, 60, true);
        assertTrue(h.isPressed());
        assertEquals(0.0, h.velocityY(), 1e-9);
    }

    @Test
    void 同帧重复推进不会重复采样速度() {
        TouchGestures g = TouchGestures.newInstanceForTest();
        g.advance(ms(0), 100, 100, true);
        g.advance(ms(16), 100, 110, true);
        double velocityOnce = g.velocityY();
        // 同一帧再推一次（渲染回调里可能被调两次）：位置与速度都不再变。
        g.advance(ms(16), 100, 110, true);
        assertEquals(velocityOnce, g.velocityY(), 1e-12);
    }
}
