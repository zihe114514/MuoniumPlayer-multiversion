package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * {@link LyricGlyphAtlas.Host} 的 1.21.11 实现 —— 字形图集页 = 一个注册进
 * TextureManager 的 {@link DynamicTexture}（照 1.21.1 的同名类移植）。
 *
 * <h2>为什么继承 DynamicTexture</h2>
 * <p>页的用法是「整页一次分配 + 每个新字形一次子矩形上传」，所以存储分配
 * （{@code new DynamicTexture(label, w, h, true)} → 内部 new NativeImage + GpuDevice
 * createTexture，usage = COPY_DST|TEXTURE_BINDING，javap 核对）必须走它的构造器，
 * CPU 侧 NativeImage 与 GPU 侧 GpuTexture 都归它管；上传自己做子矩形。</p>
 *
 * <h2>注册与寻址</h2>
 * <p>绘制走原版 {@code RenderType.textSeeThrough(rl)}（1.21.11 挪到了
 * {@code net.minecraft.client.renderer.rendertype} 包），它的 RenderSetup 在
 * {@code RenderType.draw} 时经 {@code TextureManager.getTexture(rl)} 按路径取纹理、
 * 再取 {@code AbstractTexture.getTextureView()/getSampler()}（javap 核对）——
 * 不注册就回退缺失纹理。页在创建时 {@code register(rl, texture)}、删除时
 * {@code release(rl)}（内部 safeClose → close，不要再手动 close 一次）。</p>
 *
 * <h2>线性过滤</h2>
 * <p>1.21.11 没有 {@code AbstractTexture.setFilter}（GL 直接管纹理时代已删，javap
 * 核对），过滤参数住进了 {@code GpuSampler}：{@code DynamicTexture} 构造时默认挂
 * {@code SamplerCache.getRepeat(NEAREST)}，会把 5× 超采样的字形毁成马赛克；
 * RenderSetup 解析纹理时用的正是 {@code AbstractTexture.getSampler()} 这个字段，
 * 所以构造后把 {@code sampler} 字段（protected，子类可写）换成
 * {@code getClampToEdge(LINEAR)} —— 线性 + 边缘钳制（与 1.21.1 版 CLAMP_TO_EDGE
 * 同口径）。零自定义 RenderType、零 AccessWidener。mip 配方见 {@link PageTexture}
 * （2026-10-25 与 1.21.1 标杆同款的安全三线性：多级分配 + 解钳 + 生成 + 读回
 * 自检，失败永久熔断回纯 LINEAR）。</p>
 *
 * <p>像素写入 {@link NativeImage#setPixelABGR}（内存序 ABGR，与 1.21.1 的
 * setPixelRGBA 同为直写无换位）；上传走 {@code GlCommandEncoder.writeToTexture} 的
 * 10 参重载 {@code (tex, image, mip=0, layer=0, dx, dy, w, h, sx, sy)} 只发子矩形
 * （参数序与原版 {@code GlyphBitmap.upload} 调用点一比一，javap 核对）。全部方法
 * 只在世界渲染事件（渲染线程）里被图集调用，且不在 render pass 内 ——
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
        // 一页一个序号：Identifier 与 GpuTexture 的调试 label 共用（此前两处各自
        // rlCounter++，label 永久错位一页，调试/崩溃报告里的名字对不上）。
        int index = rlCounter++;
        PageTexture texture = new PageTexture(index, width, height);
        // 注册键在创建时分配；TextureManager 只当查找表，内容我们自管。
        // 1.21.11 的 ResourceLocation 已更名 Identifier，工厂方法是
        // Identifier.fromNamespaceAndPath（javap 核对，1.20.1 的公开构造器已不存在）。
        Identifier rl = Identifier.fromNamespaceAndPath("muoniumplayer",
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
        // 分流到渲染线程执行。
        if (!RenderSystem.isOnRenderThread()) {
            // 1.21.11 无 recordRenderCall（javap 核对），渲染线程投递口是 queueFencedTask。
            RenderSystem.queueFencedTask(() ->
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
     * 一页 = RGBA8 动态纹理（构造器完成 NativeImage + GpuTexture 分配）、
     * 强制 LINEAR 采样、CLAMP_TO_EDGE。
     */
    private static final class PageTexture extends DynamicTexture {

        PageTexture(int index, int width, int height) {
            super(RL_PREFIX + index, width, height, true);
            // DynamicTexture 构造时默认挂 NEAREST repeat 采样器（javap 核对）——
            // 换成 LINEAR + CLAMP_TO_EDGE，让 5× 超采样不被 NEAREST 毁掉。
            // RenderSetup 解析纹理走 AbstractTexture.getSampler()，改这个字段就生效。
            // 采样器归 SamplerCache 统一持有（id 复用、随 RenderSystem 生命周期回收），
            // 纹理 close 时不归我们释放；sRGB 变体只是 SamplerCache 的缓存索引位，
            // GlSampler 本体不设 sRGB 解码参数（javap 核对），GUI 文字管线走非 sRGB。
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
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
