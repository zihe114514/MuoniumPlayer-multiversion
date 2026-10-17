package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL12;

/**
 * {@link LyricGlyphAtlas.Host} 的 1.21.1 实现 —— 字形图集页 = 一个注册进
 * TextureManager 的 {@link DynamicTexture}。
 *
 * <h2>为什么继承 DynamicTexture</h2>
 * <p>页的用法是「整页一次分配 + 每个新字形一次子矩形上传」，
 * {@code DynamicTexture.upload()} 只会整页重传（1024² = 4MB/次），所以上传自己做；
 * 但<b>存储分配必须走它的构造器</b>：{@code new DynamicTexture(w, h, true)} 内部经
 * {@code TextureUtil.prepareImage} 落 {@code glTexImage2D}。绕开它自管分配就是
 * 「黑方块」事故的根因 —— 只有 {@code glTexSubImage2D} 而没有底层
 * {@code glTexImage2D} 的纹理在 GL 里是<b>不完整纹理</b>，子矩形上传静默失败
 * （GL_INVALID_OPERATION），GPU 采样返回不透明黑 (0,0,0,1)，每个字形 quad 都成了
 * 黑色实心块。原版 FontTexture 也是「AbstractTexture + prepareImage + 注册」这一套。</p>
 *
 * <h2>注册与寻址</h2>
 * <p>绘制走原版 {@code RenderType.textSeeThrough(rl)}，它的 {@code TextureStateShard}
 * 经 {@code TextureManager.getTexture(rl)} 按路径取纹理 —— 不注册就回退缺失纹理
 * （品红方框 + FileNotFoundException，另一个已修的事故）。页在创建时
 * {@code register(rl, texture)}、删除时 {@code release(rl)}（内部 safeClose → close，
 * 不要再手动 close 一次）。</p>
 *
 * <h2>线性过滤</h2>
 * <p>shard 绑定纹理时无条件调 {@code setFilter(blur, mipmap)}（javap 核对），
 * 原版 textSeeThrough 传 (false,false)=NEAREST，会把 5× 超采样的字形毁成马赛克；
 * 重写 {@link PageTexture#setFilter} 强制 (true,false)=LINEAR。零自定义 RenderType、
 * 零 AccessWidener。</p>
 *
 * <p>像素写入 {@link NativeImage#setPixelRGBA}（内存序 ABGR），上传走 9 参
 * {@link NativeImage#upload} 只发子矩形、且上传前要 {@code bind()} 自己
 * （_upload 不自行绑定，javap 核对）。全部方法只在世界渲染事件（渲染线程）里被图集调用。</p>
 */
final class LyricGlyphAtlasBridge implements LyricGlyphAtlas.Host {

    static final LyricGlyphAtlasBridge INSTANCE = new LyricGlyphAtlasBridge();

    private static final String RL_PREFIX = "worldlyrics_glyph_page_";
    private static int rlCounter;

    private LyricGlyphAtlasBridge() {
    }

    @Override
    public Object createPage(int width, int height) {
        PageTexture texture = new PageTexture(width, height);
        // 注册键在创建时分配；TextureManager 只当查找表，内容我们自管。
        // register(String, DynamicTexture) 的自增前缀路径不收外部 RL，用 (RL, AbstractTexture) 重载。
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("muoniumplayer",
                "dynamic/" + RL_PREFIX + (rlCounter++));
        Minecraft.getInstance().getTextureManager().register(rl, texture);
        return new PageHandle(rl, texture);
    }

    @Override
    public void uploadCell(Object page, int x, int y, int w, int h, int[] pixelsArgb) {
        if (page instanceof PageHandle) {
            ((PageHandle) page).texture.uploadSub(x, y, w, h, pixelsArgb);
        }
    }

    @Override
    public void deletePage(Object page) {
        if (!(page instanceof PageHandle)) return;
        // 防线：invalidateAll 是共享层公共 API，若未来从非渲染线程调用
        // （字体链路的某个新入口），CPU 侧 NativeImage 会被当场释放而渲染
        // 线程可能正握着它 upload —— 分流到渲染线程执行。
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() ->
                    Minecraft.getInstance().getTextureManager().release(((PageHandle) page).rl));
            return;
        }
        if (page instanceof PageHandle) {
            // release 内部 safeClose → DynamicTexture.close()（关 NativeImage + 释放 id），
            // 这里不要再 close 一次——NativeImage 的释放不是幂等的。
            Minecraft.getInstance().getTextureManager().release(((PageHandle) page).rl);
        }
    }

    /** 图集侧拿到的页句柄：TextureManager 寻址路径 + 纹理对象。 */
    static final class PageHandle {
        final ResourceLocation rl;
        final PageTexture texture;

        PageHandle(ResourceLocation rl, PageTexture texture) {
            this.rl = rl;
            this.texture = texture;
        }

        ResourceLocation resourceLocation() {
            return rl;
        }
    }

    /**
     * 一页 = RGBA8 动态纹理（构造器完成 prepareImage 分配）、强制 LINEAR、CLAMP_TO_EDGE。
     */
    private static final class PageTexture extends DynamicTexture {

        PageTexture(int width, int height) {
            super(width, height, true);
            // 构造器分配时已绑定本纹理，顺手设边缘钳制（UV 恒在格内，REPEAT 也不出错，
            // CLAMP 只是保险）。MIN/MAG 不在这里设——shard 每次 bind 都会经 setFilter 落。
            com.muoniumplayer.compat.gl.GL11.glTexParameteri(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            com.muoniumplayer.compat.gl.GL11.glTexParameteri(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                    GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }

        /** shard 绑定时的过滤参数改写：无论 shard 传什么，落成 LINEAR。 */
        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(true, false);
        }

        /** 资源重载钩子：页像素全程自管，不走 ResourceManager 重载。 */
        @Override
        public void load(net.minecraft.server.packs.resources.ResourceManager manager) {
        }

        /** 把一格 ARGB int 写进 CPU 侧 NativeImage 的子矩形并上传。 */
        void uploadSub(int x, int y, int w, int h, int[] pixelsArgb) {
            RenderSystem.assertOnRenderThread();
            NativeImage image = this.getPixels();
            if (image == null) return;
            // NativeImage._upload 不自行绑定纹理（javap 核对无 bind 调用），
            // 每次上传前显式绑回自己——中间可能插过别的纹理绑定。
            this.bind();
            for (int row = 0; row < h; row++) {
                int src = row * w;
                for (int col = 0; col < w; col++) {
                    int argb = pixelsArgb[src + col];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage 内存序 ABGR：setPixelRGBA 吃 (a<<24)|(b<<16)|(g<<8)|r
                    image.setPixelRGBA(x + col, y + row, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            // 子矩形上传（javap 核对的 9 参重载 → _upload(level, xOffset, yOffset,
            // unpackSkipPixels=x, unpackSkipRows=y, regionWidth, regionHeight,
            // blur=false, clamp=false, mipmap=false, closeAfter=false)）。
            image.upload(0, x, y, x, y, w, h, false, false);
        }
    }
}
