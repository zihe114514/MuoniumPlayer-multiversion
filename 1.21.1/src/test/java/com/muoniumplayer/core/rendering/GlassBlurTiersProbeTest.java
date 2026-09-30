package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这一面糊多狠 = 全局档 × 这一面倍率」那一步<b>真跑</b>。
 *
 * <p>{@code GlassLook.blurFor} 是纯乘法加钳位，但它管的这件事一旦算错，症状分别是：
 * 全局推到 0 却还剩某一面在糊（用户以为模糊是全局的，结果四处不一个口径，像 bug）；
 * 倍率被写坏成 0 或 NaN 时那一面<b>整层没有玻璃</b>（{@code blurred(0)} 返回 null，
 * 调用点退回假玻璃画法）；上限不钳则一块面板能把整张底糊成一团色块，
 * 边缘折射也就没有东西可折了。</p>
 *
 * <p>为什么这里不读源码而直接调函数：{@code HudConfig} 那四个字段是普通静态字段，
 * 不需要 GL、不需要 Minecraft 运行时，读源码反而证不了钳位真的在那条路径上。</p>
 */
class GlassBlurTiersProbeTest {

    private static final float GLOBAL = 12f;

    @AfterEach
    void restoreDefaults() {
        HudConfig.glassBlurPlayer = 1.0f;
        HudConfig.glassBlurIsland = 0.75f;
        HudConfig.glassBlurDesktopLyric = 1.15f;
        HudConfig.glassBlurEditors = 1.0f;
    }

    @Test
    @DisplayName("倍率乘在全局档上，四面各自一档")
    void multiplierAppliesPerSurface() {
        HudConfig.glassBlurPlayer = 1.0f;
        HudConfig.glassBlurIsland = 0.5f;
        assertEquals(GLOBAL, GlassLook.blurFor(GlassLook.Surface.PLAYER, GLOBAL), 0.001f);
        assertEquals(6f, GlassLook.blurFor(GlassLook.Surface.ISLAND, GLOBAL), 0.001f);
        assertEquals(GLOBAL, GlassLook.blurFor(GlassLook.Surface.EDITORS, GLOBAL), 0.001f);
    }

    @Test
    @DisplayName("全局档为 0 时倍率不能翻案：0 就是哪一面都不糊")
    void zeroGlobalWinsOverMultiplier() {
        HudConfig.glassBlurIsland = 2.0f;
        assertEquals(0f, GlassLook.blurFor(GlassLook.Surface.ISLAND, 0f));
        // 负数与 NaN 同样按「没有模糊」处理，不能变成一档看不懂的糊
        assertEquals(0f, GlassLook.blurFor(GlassLook.Surface.PLAYER, -3f));
        assertEquals(0f, GlassLook.blurFor(GlassLook.Surface.PLAYER, Float.NaN));
    }

    @Test
    @DisplayName("倍率写坏（0 / 负 / NaN）退回与全局同档，而不是把整层玻璃弄没")
    void brokenFactorFallsBackToOne() {
        HudConfig.glassBlurDesktopLyric = 0f;
        assertEquals(GLOBAL, GlassLook.blurFor(GlassLook.Surface.DESKTOP_LYRIC, GLOBAL), 0.001f);
        HudConfig.glassBlurDesktopLyric = -1f;
        assertEquals(GLOBAL, GlassLook.blurFor(GlassLook.Surface.DESKTOP_LYRIC, GLOBAL), 0.001f);
        HudConfig.glassBlurDesktopLyric = Float.NaN;
        assertEquals(GLOBAL, GlassLook.blurFor(GlassLook.Surface.DESKTOP_LYRIC, GLOBAL), 0.001f);
    }

    @Test
    @DisplayName("倍率与乘积两头都钳：手改 json 写成 9 也只能到 2 倍，乘出来不超过 64")
    void bothEndsAreClamped() {
        HudConfig.glassBlurEditors = 9f;
        assertEquals(GLOBAL * 2f, GlassLook.blurFor(GlassLook.Surface.EDITORS, GLOBAL), 0.001f);
        HudConfig.glassBlurPlayer = 2f;
        assertEquals(64f, GlassLook.blurFor(GlassLook.Surface.PLAYER, 60f), 0.001f);
    }

    @Test
    @DisplayName("出厂默认值就是「面板当基准」：只有岛与歌词卡不等于 1")
    void shippedDefaultsKeepPlayerAsBaseline() {
        restoreDefaults();
        GlassLook.Surface[] all = GlassLook.Surface.values();
        int differentFromBaseline = 0;
        for (GlassLook.Surface surface : all) {
            if (GlassLook.blurFor(surface, GLOBAL) != GlassLook.blurFor(GlassLook.Surface.PLAYER, GLOBAL)) {
                differentFromBaseline++;
            }
        }
        assertTrue(differentFromBaseline > 0 && differentFromBaseline < all.length,
                "出厂默认要么全同档（那这一组滑块没有意义），要么把基准面也挪了（那说明书要重写）");
        assertEquals(1.0f, HudConfig.glassBlurPlayer, 0.001f,
                "播放器是基准面：它的倍率默认必须是 1");
    }
}
