package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「通透模式」的果冻缩放曲线与配置钳制。缩放是纯数学，直接钉端点与单调性；
 * 持久化走 {@code TransparentMode} 自己的懒加载文件，这里不测 IO，只测钳制。
 */
class TransparentModeTest {

    @Test
    void heightScaleEndpoints() {
        // 零脉冲 = 原样；满脉冲按幅度压扁；幅度 50 是默认档。
        assertEquals(1f, TransparentMode.heightScale(0f, 50f), 1e-6);
        assertEquals(0.5f, TransparentMode.heightScale(1f, 50f), 1e-6);
        assertEquals(1f, TransparentMode.heightScale(1f, 0f), 1e-6);
        // 90% 是滑条上限：压到底还剩一成高，不许把模型压没。
        assertEquals(0.1f, TransparentMode.heightScale(1f, 90f), 1e-6);
    }

    @Test
    void widthScaleComplementsHeight() {
        assertEquals(1f, TransparentMode.widthScale(0f, 50f), 1e-6);
        assertEquals(1.5f, TransparentMode.widthScale(1f, 50f), 1e-6);
        // 深度不变是这套效果的立身之本：Z 轴根本没有接口，这条注释只是把意图钉在测试名里。
        assertEquals(1f, TransparentMode.widthScale(0f, 200f), 1e-6);
        assertEquals(3f, TransparentMode.widthScale(1f, 200f), 1e-6);
    }

    @Test
    void scaleIsMonotonicInSquash() {
        // 节拍越响压得越扁：任何幅度下高度随脉冲单调不增、宽度单调不减。
        for (float compression = 0f; compression <= 90f; compression += 15f) {
            for (float width = 0f; width <= 200f; width += 40f) {
                float previousHeight = TransparentMode.heightScale(0f, compression);
                float previousWidth = TransparentMode.widthScale(0f, width);
                for (float squash = 0.05f; squash <= 1.0001f; squash += 0.05f) {
                    float height = TransparentMode.heightScale(squash, compression);
                    float scaleWidth = TransparentMode.widthScale(squash, width);
                    assertTrue(height <= previousHeight + 1e-6, "高度随脉冲回升了");
                    assertTrue(scaleWidth >= previousWidth - 1e-6, "宽度随脉冲变窄了");
                    assertTrue(height > 0f, "压出非正高度会画出倒置模型");
                    previousHeight = height;
                    previousWidth = scaleWidth;
                }
            }
        }
    }

    @Test
    void outOfRangeInputsAreClamped() {
        // 恶意/损坏的配置值不能画出翻转型的模型：脉冲钳到 0..1，幅度钳到滑条区间。
        assertEquals(1f, TransparentMode.heightScale(-3f, 50f), 1e-6);
        assertEquals(0.5f, TransparentMode.heightScale(2f, 50f), 1e-6);
        assertEquals(0.1f, TransparentMode.heightScale(1f, 500f), 1e-6);
        assertEquals(1f, TransparentMode.widthScale(1f, -50f), 1e-6);
    }
}
