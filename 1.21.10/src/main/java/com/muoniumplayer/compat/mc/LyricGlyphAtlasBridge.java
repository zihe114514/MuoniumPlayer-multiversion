package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * {@link LyricGlyphAtlas.Host} 的 1.21.10 实现 —— 字形图集页 = 一个注册进
 * TextureManager 的 {@link DynamicTexture}。
 *
 * <h2>1.21.10 的代际位置（决定了本文件长这样）</h2>
 * <p>1.21.9 起纹理分配已经现代化（{@code DynamicTexture} 构造器内部经
 * {@code GpuDevice.createTexture} 落 texStorage 型不可变存储，CPU 侧 NativeImage 与
 * GPU 侧 GpuTexture 都归它管），但 {@code GpuSampler} / {@code SamplerCache} 那套
 * 「过滤住进采样器对象」的抽象<b>还没进来</b>：{@code AbstractTexture} 仍保留
 * {@code setFilter(boolean,boolean)} / {@code setClamp(boolean)}（javap 核对，落在
 * {@code GpuTexture.setTextureFilter/setAddressMode} 上）。所以过滤/钳制走 1.21.1
 * 的老路（重写 setFilter），上传走 1.21.11 的新路（CommandEncoder.writeToTexture）——
 * 这两头正是 1.21.10 作为过渡版本的形状。</p>
 *
 * <h2>为什么继承 DynamicTexture</h2>
 * <p>页的用法是「整页一次分配 + 每个新字形一次子矩形上传」，所以存储分配
 * （{@code new DynamicTexture(label, w, h, true)}）必须走它的构造器；上传自己做子矩形。
 * 也因此<b>不做 mip</b>：GpuDevice 分配的是单级不可变存储（texStorage 语义），
 * {@code glGenerateMipmap} 不能扩级，1.21.1 那套「解钳 + 生成 + 读回自检」在这里
 * 拿到的是不完整采样链（主流驱动整页发黑）。字形平滑由 5× 超采样 + LINEAR 承担
 * ——与 26.x 同一结论。</p>
 *
 * <h2>注册与寻址</h2>
 * <p>绘制走原版 {@code RenderType.textSeeThrough(rl)}（1.21.10 还在
 * {@code net.minecraft.client.renderer} 包，1.21.11 才搬去 rendertype 子包），
 * 它经 {@code TextureManager.getTexture(rl)} 按路径取纹理 —— 不注册就回退缺失纹理。
 * 页在创建时 {@code register(rl, texture)}、删除时 {@code release(rl)}
 * （内部 safeClose → close，不要再手动 close 一次）。</p>
 *
 * <p>像素写入 {@link NativeImage#setPixelABGR}（内存序 ABGR，吃 (a&lt;&lt;24)|(b&lt;&lt;16)|(g&lt;&lt;8)|r）；
 * 上传走 {@code GlCommandEncoder.writeToTexture} 的 10 参重载
 * {@code (tex, image, mip, layer, dx, dy, w, h, sx, sy)}（javap 核对：p3 与 mipLevels 比、
 * p7+p9 与图像宽比、p5+p7 与纹理宽比 —— 即 p5/p6=目的、p7/p8=尺寸、p9/p10=源偏移）。
 * 全部方法只在世界渲染事件（渲染线程）里被图集调用，且不在 render pass 内 ——
 * {@code writeToTexture} 会被 {@code inRenderPass} 拒掉；本类的调用点都在
 * {@code endBatch(RenderType)} 之间，与原版 FontTexture 懒烘焙同一时机。</p>
 */
final class LyricGlyphAtlasBridge implements LyricGlyphAtlas.Host {

    static final LyricGlyphAtlasBridge INSTANCE = new LyricGlyphAtlasBridge();

    private static final String RL_PREFIX = "worldlyrics_glyph_page_";
    private static int rlCounter;

    private LyricGlyphAtlasBridge() {
    }

    @Override
    public Object createPage(int width, int height) {
        // 一页一个序号：ResourceLocation 与 GpuTexture 的调试 label 共用（此前两处各自
        // rlCounter++，label 永久错位一页，调试/崩溃报告里的名字对不上）。
        int index = rlCounter++;
        PageTexture texture = new PageTexture(index, width, height);
        // 注册键在创建时分配；TextureManager 只当查找表，内容我们自管。
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("muoniumplayer",
                "dynamic/" + RL_PREFIX + index);
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
        // 防线：invalidateAll 是共享层公共 API，若未来从非渲染线程调用，
        // CPU 侧 NativeImage 会被当场释放而渲染线程可能正握着它 upload ——
        // 分流到渲染线程执行。1.21.10 既没有 26.x 的 queueFencedTask，也没有更早的
        // recordRenderCall（javap 核对），客户端的渲染线程就是主线程，
        // Minecraft.execute 投递过去与两个被删的口子同义。
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().getTextureManager().release(((PageHandle) page).rl));
            return;
        }
        if (page instanceof PageHandle) {
            // release 内部 safeClose → DynamicTexture.close()（关 NativeImage + 释放 GpuTexture），
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
     * 一页 = RGBA8 动态纹理（构造器完成 NativeImage + GpuDevice 整页分配）、
     * 强制 LINEAR 采样 + CLAMP_TO_EDGE、单级分配（无 mip 链）。
     */
    private static final class PageTexture extends DynamicTexture {

        PageTexture(int index, int width, int height) {
            super(RL_PREFIX + index, width, height, true);
            // 1.21.10 的过滤/钳制还挂在 AbstractTexture 上（GpuSampler 尚未出现），
            // 而绘制管线的绑定路径会按 RenderType 的 shard 参数无条件调
            // setFilter(blur, mipmap)（1.21.1 同款，javap 核对 setFilter/setClamp
            // 都直接落在 GpuTexture 的纹理对象上、随绑定生效）。
            // NEAREST 会把 5× 超采样的字形毁成马赛克，所以两处都拦：
            // 构造时钳制 + LINEAR 先落一次，绑定时的 setFilter 一律改写成 (true,false)。
            setClamp(true);
            setFilter(true, false);
        }

        /** shard 绑定时的过滤参数改写：无论谁来绑，拿到的都是 LINEAR、无 mip。 */
        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(true, false);
        }

        /** 把一格 ARGB int 写进 CPU 侧 NativeImage 的子矩形并上传。 */
        void uploadSub(int x, int y, int w, int h, int[] pixelsArgb) {
            RenderSystem.assertOnRenderThread();
            NativeImage image = this.getPixels();
            if (image == null) return;
            for (int row = 0; row < h; row++) {
                int src = row * w;
                for (int col = 0; col < w; col++) {
                    int argb = pixelsArgb[src + col];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage 内存序 ABGR：setPixelABGR 吃 (a<<24)|(b<<16)|(g<<8)|r
                    image.setPixelABGR(x + col, y + row, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            // 子矩形上传（javap 核对的 10 参 writeToTexture：mip、layer、目的 (dx,dy)、
            // 尺寸 (w,h)、源偏移 (sx,sy) —— 与原版 GlyphBitmap.upload 的调用点同序）。
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(this.getTexture(), image, 0, 0, x, y, w, h, x, y);
        }
    }
}
