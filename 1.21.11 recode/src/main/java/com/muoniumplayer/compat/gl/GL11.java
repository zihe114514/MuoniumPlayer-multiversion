package com.muoniumplayer.compat.gl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * 纹理上传用的 GL 绑定。
 *
 * <h2>这个类现在只剩什么</h2>
 * 重写渲染层之前，这里是一整套 OpenGL 1.x 固定管线的模拟层：{@code glBegin/glVertex}、
 * 矩阵栈、属性栈、显示列表、{@code glTexEnvi} 的纹理环境……总共 400 多行，
 * 背后还有一个 43 KB 的 {@code FixedFunction} 把这些调用翻译成 core profile 的着色器与 VBO。
 * 那一整套已经删掉了：界面绘制现在由 Skia 完成，不再需要固定管线。
 *
 * <p>剩下的只有<b>纹理上传</b>相关的入口。这部分不是「界面渲染」而是「资源加载」：
 * 把解码好的像素传到显存。它没有旧管线的包袱，重写只会凭空引入 bug，
 * 所以保留，由 Skia 借用这些纹理来采样（见 {@code SkijaImages}）。
 *
 * <p>继承 {@code org.lwjgl.opengl.GL11} 只是为了让常量（{@code GL_TEXTURE_2D} 等）
 * 继续可以从这个类名访问，免得动那几个纹理类的 import。
 */
public class GL11 extends org.lwjgl.opengl.GL11 {

    protected GL11() {
    }

    // ---------------- 纹理对象 ----------------

    /** 走 MC 的 {@code GlStateManager}，让它的绑定缓存与真实状态保持一致。 */
    public static void glBindTexture(int target, int texture) {
        if (target == org.lwjgl.opengl.GL11.GL_TEXTURE_2D) {
            com.mojang.blaze3d.opengl.GlStateManager._bindTexture(texture);
        } else {
            org.lwjgl.opengl.GL11.glBindTexture(target, texture);
        }
    }

    public static void glDeleteTextures(int texture) {
        // 先让 Skia 侧指向这个纹理的视图作废，顺序反了就是悬垂引用：
        // Skia 会拿一个已经不存在的纹理名去采样。
        com.muoniumplayer.render.skija.SkijaImages.invalidate(texture);
        com.mojang.blaze3d.opengl.GlStateManager._deleteTexture(texture);
    }

    public static int glGenTextures() {
        return com.mojang.blaze3d.opengl.GlStateManager._genTexture();
    }

    public static void glTexParameteri(int target, int pname, int param) {
        com.mojang.blaze3d.opengl.GlStateManager._texParameter(target, pname, param);
    }

    /**
     * 1.21.11 的 {@code GlStateManager} 只留下了 {@code _texParameter(int,int,int)}，
     * 浮点重载没了。直接裸调：纹理参数本来就不在它的缓存里（缓存的只有绑定与开关类状态），
     * 绕过它不会制造缓存分歧。
     */
    public static void glTexParameterf(int target, int pname, float param) {
        org.lwjgl.opengl.GL11.glTexParameterf(target, pname, param);
    }

    // ---------------- 像素上传 ----------------

    /** 走 MC 的 {@code GlStateManager}，与它自己的 NativeImage 上传共用同一份解包状态。 */
    public static void glPixelStorei(int pname, int param) {
        com.mojang.blaze3d.opengl.GlStateManager._pixelStore(pname, param);
    }

    /**
     * 清掉「像素解包」的行跨距与跳过量。
     *
     * <p>Minecraft 的 {@code NativeImage#upload} 会设置 {@code GL_UNPACK_ROW_LENGTH}
     * 以及 {@code GL_UNPACK_SKIP_PIXELS/SKIP_ROWS} 而且<b>用完不还原</b>。这是全局状态，
     * 残留下来会让紧接着的上传按错误的跨距读源数据，画面上是撕裂或错位的贴图。
     * 每次上传前先清一遍，代价可以忽略。
     */
    private static void resetPixelUnpackLayout() {
        com.mojang.blaze3d.opengl.GlStateManager._pixelStore(
                org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        com.mojang.blaze3d.opengl.GlStateManager._pixelStore(
                org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        com.mojang.blaze3d.opengl.GlStateManager._pixelStore(
                org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        com.mojang.blaze3d.opengl.GlStateManager._pixelStore(
                org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
                                    int border, int format, int type, ByteBuffer pixels) {
        resetPixelUnpackLayout();
        org.lwjgl.opengl.GL11.glTexImage2D(target, level, internalFormat, width, height, border,
                format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
                                    int border, int format, int type, IntBuffer pixels) {
        resetPixelUnpackLayout();
        org.lwjgl.opengl.GL11.glTexImage2D(target, level, internalFormat, width, height, border,
                format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                       int width, int height, int format, int type, ByteBuffer pixels) {
        resetPixelUnpackLayout();
        org.lwjgl.opengl.GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                format, type, pixels);
    }

    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                       int width, int height, int format, int type, IntBuffer pixels) {
        resetPixelUnpackLayout();
        org.lwjgl.opengl.GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height,
                format, type, pixels);
    }
}
