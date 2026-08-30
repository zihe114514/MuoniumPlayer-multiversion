package com.muoniumplayer.compat.gl;

/**
 * {@code org.lwjgl.opengl.GL14} 的替身：只接管 {@code glBlendFuncSeparate}，
 * 让它走 MC 的 {@code GlStateManager} 缓存。其余入口与常量靠继承透传。
 */
public class GL14 extends org.lwjgl.opengl.GL14 {

    protected GL14() {
    }

    public static void glBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        com.mojang.blaze3d.opengl.GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }
}
