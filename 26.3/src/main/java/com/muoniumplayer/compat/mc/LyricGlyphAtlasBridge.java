package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * {@link LyricGlyphAtlas.Host} 的 26.3 实现 —— 字形图集页 = 一个注册进
 * TextureManager 的 {@link DynamicTexture}。
 *
 * <h2>与 1.21.1 的 API 差异（javap 核对 minecraft-merged-deobf-26.3.jar）</h2>
 * <ul>
 *   <li>{@code ResourceLocation} 改名 {@code Identifier}（{@code fromNamespaceAndPath} 仍在）；</li>
 *   <li>{@code AbstractTexture} 不再有 {@code setFilter/load}：过滤改由
 *       {@code GpuSampler} 承载（GpuTexture/View/Sampler 换到了
 *       {@code com.mojang.renderpearl.api.textures}，字段语义不变）。{@code DynamicTexture(
 *       String,int,int,boolean)} 构造时 {@code createTexture} 落
 *       {@code GpuDevice.createTexture}（USAGE 含 COPY_DST，完整整页分配）+
 *       {@code createTextureView} + sampler = {@code SamplerCache.getRepeat(NEAREST)}；</li>
 *   <li>{@code NativeImage} 的 9 参 {@code upload}（1.21.1 的子矩形上传口）已删除；
 *       26.x 的上传统一走 {@code CommandEncoder.writeToTexture(GpuTexture, NativeImage,
 *       mip, depthLayer, x, y)} 子矩形重载 —— 显存拷贝由驱动层完成，
 *       <b>不经过 glTexSubImage2D、不读当前纹理绑定</b>，也就不需要 1.21.1
 *       那句「上传前 bind 回自己」。</li>
 * </ul>
 *
 * <h2>注册与寻址</h2>
 * <p>绘制走 {@code RenderTypes.textSeeThrough(rl)}。javap 其 RenderSetup：2 参
 * {@code withTexture("Sampler0", rl)} 的 sampler supplier 恒为 null，
 * {@code RenderSetup.prepareTextures} 对 null 回退 {@code AbstractTexture.getSampler()}
 * —— 所以<b>过滤完全由纹理对象自己的 sampler 决定</b>：重写
 * {@link PageTexture#getSampler()} 返回 LINEAR（CLAMP_TO_EDGE）的缓存采样器，
 * 绑定路径既不会重设也无处可设 NEAREST（1.21.1 的 setFilter 拦截在 26.x 没有对应物，
 * 这是唯一也足够的强制 LINEAR 点）。纹理查找经
 * {@code TextureManager.getTexture(rl)}（prepareTextures 字节码直调）——
 * 不注册就回退缺失纹理（品红）。页创建时 {@code register(rl, texture)}、
 * 删除时 {@code release(rl)}（内部 safeClose → close：关 NativeImage +
 * {@code GpuTexture.close} + view.close，不要再手动 close 一次）。</p>
 *
 * <h2>像素写入</h2>
 * <p>{@link NativeImage#setPixelABGR}（内存序 ABGR，吃 (a&lt;&lt;24)|(b&lt;&lt;16)|(g&lt;&lt;8)|r，
 * 与 1.21.1 的 setPixelRGBA 同序）。子矩形上传前把 ARGB 行写进整页 CPU 侧
 * NativeImage，再 {@code writeToTexture(texture, image, 0, 0, x, y)} 只发那一格。
 * 全部方法只在世界渲染事件（渲染线程）里被图集调用。</p>
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
        // register(String, DynamicTexture) 的自增前缀路径不收外部 Identifier，用 (Identifier, AbstractTexture) 重载。
        Identifier rl = Identifier.fromNamespaceAndPath("muoniumplayer",
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
        // 防线：invalidateAll 是共享层公共 API，若未来从非渲染线程调用，
        // CPU 侧 NativeImage 会被当场释放而渲染线程可能正握着它 upload ——
        // 分流到渲染线程执行。
        if (!RenderSystem.isOnRenderThread()) {
            // 26.x 无 recordRenderCall（javap 核对），渲染线程投递口是 queueFencedTask。
            RenderSystem.queueFencedTask(() ->
                    Minecraft.getInstance().getTextureManager().release(((PageHandle) page).rl));
            return;
        }
        if (page instanceof PageHandle) {
            // release 内部 safeClose → DynamicTexture.close()（关 NativeImage + 释放
            // GpuTexture/View），这里不要再 close 一次——NativeImage 的释放不是幂等的。
            Minecraft.getInstance().getTextureManager().release(((PageHandle) page).rl);
        }
    }

    /** 图集侧拿到的页句柄：TextureManager 寻址路径 + 纹理对象。 */
    static final class PageHandle {
        final Identifier rl;
        final PageTexture texture;

        PageHandle(Identifier rl, PageTexture texture) {
            this.rl = rl;
            this.texture = texture;
        }

        Identifier resourceLocation() {
            return rl;
        }
    }

    /**
     * 一页 = RGBA8 动态纹理（构造器完成 GpuDevice.createTexture 整页分配）、
     * 采样器强制 LINEAR + CLAMP_TO_EDGE。
     */
    private static final class PageTexture extends DynamicTexture {

        PageTexture(int width, int height) {
            // (String, int, int, boolean) 构造：内部 NativeImage(w,h,true) +
            // createTexture(label)（GpuDevice.createTexture 整页分配）+ NEAREST repeat 采样器。
            // 采样器马上被 getSampler() 覆盖成 LINEAR（见下）。
            super("muoniumplayer_worldlyrics_glyph_page", width, height, true);
        }

        /**
         * textSeeThrough 的绑定路径（RenderSetup.prepareTextures）对 2 参 withTexture
         * 的 null sampler 回退到本方法 —— 1.21.1 时代「shard 每次 bind 都经 setFilter」
         * 的强制点在 26.x 换成这一处：无论谁取这个纹理，拿到的都是 LINEAR。
         */
        @Override
        public com.mojang.renderpearl.api.textures.GpuSampler getSampler() {
            return RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        }

        /** 把一格 ARGB int 写进暂存 NativeImage（恰好 w×h）并按格上传显存。 */
        void uploadSub(int x, int y, int w, int h, int[] pixelsArgb) {
            RenderSystem.assertOnRenderThread();
            // 26.x 的 writeToTexture(NativeImage) 6 参重载是「整幅图平移到 (x,y) 上传」：
            // 越界校验用 image 自己的 getWidth/getHeight（javap 核对），把整页 1024² 的
            // CPU 镜像直接传进去，除 (0,0) 第一格外必然抛 IllegalArgumentException。
            // 所以每格切一张恰好 w×h 的暂存图上传 —— 整页 CPU 镜像保留，用于诊断与
            // 全量重传兜底。
            NativeImage pageImage = this.getPixels();
            if (pageImage == null || this.getTexture() == null) return;
            try (NativeImage cell = new NativeImage(w, h, true)) {
                for (int row = 0; row < h; row++) {
                    int src = row * w;
                    for (int col = 0; col < w; col++) {
                        int argb = pixelsArgb[src + col];
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >>> 16) & 0xFF;
                        int g = (argb >>> 8) & 0xFF;
                        int b = argb & 0xFF;
                        // NativeImage 内存序 ABGR：setPixelABGR 吃 (a<<24)|(b<<16)|(g<<8)|r
                        cell.setPixelABGR(col, row, (a << 24) | (b << 16) | (g << 8) | r);
                        pageImage.setPixelABGR(x + col, y + row, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
                // 暂存图全尺寸 = 格尺寸，6 参的 (x,y) 平移目标合法。
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(this.getTexture(), cell, 0, 0, x, y);
            }
        }
    }
}
