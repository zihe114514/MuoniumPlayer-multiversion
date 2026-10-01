package com.muoniumplayer.core.rendering.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「玻璃浓度」推到 0 时那一层底色到底剩多少——这条链在 JVM 上算得清，而它错了的代价是整页黑斑。
 *
 * <h2>要钉住的症状</h2>
 * 2026-09-30 真机：玩家把「玻璃浓度」往左拖（想要更透），结果曲目行、搜索框、来源按钮、
 * 底栏<em>更黑</em>了。原因不在浓度曲线，在 {@code setFillAlpha} 的坏值判据：它写的是
 * {@code fillAlpha > 0f}，于是浓度=0 时 {@code washAlpha} 算出的那个<em>正确的</em> 0
 * 被当成坏值顶回 1.0 —— 每一层内底都变回全不透明，而面板根底走的是另一条带 0.18 下限的曲线
 * 还留着透。所以「外面是玻璃、里面一块块黑斑」，而且越拖越黑。
 *
 * <h2>这里钉住的两件事</h2>
 * <ol>
 *   <li>0 是合法值，必须原样收下；只有 NaN / 负数 / 大于 1 才退回 1（宁可看见原来的实色底，
 *       也不要一块凭空消失的按钮）。</li>
 *   <li>浓度=0 时内层确实该归零，而根底仍有 0.18 兜着白字可读 —— 两档在 0 处<em>不该</em>相等，
 *       在 1 处才同时回到 1（那条由 {@code GlassTintStaticTest} 钉）。</li>
 * </ol>
 */
class FillAlphaStaticTest {

    @Test
    @DisplayName("浓度=0 算出的 0 是合法值，不许被当成坏值顶回 1")
    void zeroIsARealValue() {
        assertEquals(0f, AbstractWidget.sanitizeFillAlpha(0f), 0f,
                "这一条就是 2026-09-30 那个「越调越黑」的 bug：0 一旦被判成坏值，整页内底全回实心");
        assertEquals(0.19f, AbstractWidget.sanitizeFillAlpha(0.19f), 0f);
        assertEquals(1f, AbstractWidget.sanitizeFillAlpha(1f), 0f);
    }

    @Test
    @DisplayName("坏值仍然退回 1：NaN、负数、大于 1")
    void brokenValuesFallBackToOpaque() {
        assertEquals(1f, AbstractWidget.sanitizeFillAlpha(Float.NaN), 0f);
        assertEquals(1f, AbstractWidget.sanitizeFillAlpha(-0.01f), 0f);
        assertEquals(1f, AbstractWidget.sanitizeFillAlpha(1.5f), 0f);
        // -0.0f 在 IEEE 里等于 0，走「合法值」那一边：它要的就是完全透明，不是坏值。
        assertEquals(0f, AbstractWidget.sanitizeFillAlpha(-0.0f), 0f);
    }

}
