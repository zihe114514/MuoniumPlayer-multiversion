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
     * 一页 = RGBA8 动态纹理 + 安全三线性 mip + CLAMP_TO_EDGE。
     *
     * <p>mip 配方（字节码侦查结论，与 1.21.1 标杆同款；1.21.11 的
     * {@code DynamicTexture.createTexture} javap 核对）：恒以 mipLevels=1 分配
     * 单级纹理，且 {@code GlDevice.createTexture} 把纹理对象的
     * MAX_LEVEL/MIN_LOD/MAX_LOD 钉死 0——MAX_LOD=0 会把采样 LOD 钳在 0 级
     * （上一次"三线性"静默退化双线性的直接原因）。但 1.21.11 的纹理是
     * <b>glTexImage2D 型可变存储</b>（{@code GlStateManager._texImage2D}，
     * 不是 texStorage 不可变），mip 链可以事后自管扩级，所以配方成立：</p>
     * <ol>
     *   <li>分配：{@code GpuDevice.createTexture(..., mipLevels = top+1)}
     *       多级分配（COPY_DST|TEXTURE_BINDING），换掉基类单级纹理 + 重建
     *       全范围 view；</li>
     *   <li>解钳：{@code GL_TEXTURE_MAX_LEVEL = top}、{@code GL_TEXTURE_MAX_LOD =
     *       top}（draw 期 encoder 每次绑定都按 view 的 mipLevels 重钉
     *       MAX_LEVEL/MIN_LOD，与全范围 view 一致；MAX_LOD 只有采样器对象
     *       这一处来源——缓存采样器把非 sRGB 档 MAX_LOD 钉 0，必须换
     *       maxLod=empty 的自建采样器，javap 核对 GlSampler）；</li>
     *   <li>生成 + 读回自检：失败永久熔断回纯 LINEAR 单级（不完整纹理采样
     *       undefined，主流驱动返回不透明黑——宁可不糊也不黑）。</li>
     * </ol>
     * <p>每次基底层变更后必须重跑 generate，链不会自动跟随（见 uploadSub）。</p>
     */
    private static final class PageTexture extends DynamicTexture {

        /** 本页的 mip 顶层数（floor(log2(min(w,h)))）；-1 = 已熔断纯 LINEAR 单级。 */
        private final int mipTop;

        PageTexture(int index, int width, int height) {
            super(RL_PREFIX + index, width, height, true);
            // 基类构造器内部已按 mipLevels=1 建纹理 + view + NEAREST 采样器；
            // 这里整体换成多级分配。原单级纹理/view 随 close 释放（id 各自独立，
            // 复用基类字段不泄漏）。分配失败则维持单级并熔断。
            int top = 31 - Integer.numberOfLeadingZeros(Math.min(width, height));
            int rebuilt = -1;
            if (top >= 1) {
                try {
                    com.mojang.blaze3d.textures.GpuTexture oldTex = this.getTexture();
                    com.mojang.blaze3d.textures.GpuTextureView oldView = this.getTextureView();
                    com.mojang.blaze3d.textures.GpuTexture mipTex =
                            RenderSystem.getDevice().createTexture(RL_PREFIX + index,
                                    com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST
                                            | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
                                    com.mojang.blaze3d.textures.TextureFormat.RGBA8,
                                    width, height, 1, top + 1);
                    this.texture = mipTex;
                    this.textureView = RenderSystem.getDevice()
                            .createTextureView(mipTex, 0, top + 1);
                    // 0 级像素：基类构造器只 upload 进旧单级纹理，新多级纹理还是空的
                    // —— 先把 CPU 侧整图发进新纹理 0 级（与基类 upload() 同一 encoder 口）。
                    RenderSystem.getDevice().createCommandEncoder()
                            .writeToTexture(mipTex, this.getPixels());
                    if (oldView != null) oldView.close();
                    if (oldTex != null) oldTex.close();
                    rebuilt = setupMipChain(width, height, top);
                } catch (Throwable t) {
                    rebuilt = -2;
                    WorldLyricsBridge.traceOnce("字形页 mip 分配失败，熔断回纯 LINEAR: " + t);
                }
            }
            this.mipTop = rebuilt;
            // 采样器：过滤承载在 GpuSampler 上（1.21.11 无 setFilter）。缓存采样器的
            // 非 sRGB 档把 GL_TEXTURE_MAX_LOD 钉 0（SamplerCache.initialize javap 核对：
            // 非 sRGB → OptionalDouble.of(0.0) → glSamplerParameterf(33083, 0)），LOD
            // 恒 0 三线性必退化 —— 熔断档继续用缓存采样器；健康档换 maxLod=empty
            // 的自建采样器（不参与 SamplerCache 生命周期，随本纹理 close）。
            // GlSampler 不设 sRGB 解码参数（javap 核对），文字管线走非 sRGB 不受影响。
            if (this.mipTop > 0) {
                this.sampler = RenderSystem.getDevice().createSampler(
                        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                        com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                        com.mojang.blaze3d.textures.FilterMode.LINEAR,
                        com.mojang.blaze3d.textures.FilterMode.LINEAR,
                        0, java.util.OptionalDouble.empty());
            } else {
                this.sampler = RenderSystem.getSamplerCache().getClampToEdge(
                        com.mojang.blaze3d.textures.FilterMode.LINEAR);
            }
        }

        /**
         * 解钳 + 生成完整 mip 链 + 读回自检。返回顶层数；自检不过返回 -1
         * （构造器据此永久落纯 LINEAR：纹理虽按 top+1 分配，draw 期 encoder
         * 钉 MAX_LEVEL=view.mipLevels-1 也只展开到链尾，1.21.11 的采样器在
         * MAX_LOD=0 时读 0 级 —— 熔断档的采样器已换回缓存单级口径，绝不
         * 产生不完整采样）。调用前提：本纹理当前已绑定（构造器刚 bind 过）。
         */
        private static int setupMipChain(int width, int height, int top) {
            try {
                com.muoniumplayer.compat.gl.GL11.glTexParameteri(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, top);
                com.muoniumplayer.compat.gl.GL11.glTexParameteri(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL12.GL_TEXTURE_MIN_LOD, 0);
                com.muoniumplayer.compat.gl.GL11.glTexParameterf(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL13.GL_TEXTURE_MAX_LOD, (float) top);
                org.lwjgl.opengl.GL30.glGenerateMipmap(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D);
                // 读回自检：链尾必须存在且宽度正确；GL 错误必须为 0
                int tailW = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D, top,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                int err = org.lwjgl.opengl.GL11.glGetError();
                if (err != 0 || tailW != Math.max(1, width >> top)) {
                    WorldLyricsBridge.traceOnce("mip 链自检失败（err=0x"
                            + Integer.toHexString(err) + " tailW=" + tailW
                            + "），图集页熔断回纯 LINEAR");
                    return -1;
                }
                return top;
            } catch (Throwable t) {
                WorldLyricsBridge.traceOnce("mip 链生成异常，图集页熔断回纯 LINEAR: " + t);
                return -1;
            }
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
            // writeToTexture 自行绑定目标纹理（GlCommandEncoder javap 核对），无需先 bind。
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(this.getTexture(), image, 0, 0, x, y, w, h, x, y);
            // 基底层变了 → 重生成整条链（每个新字形烘焙才触发一次，稳态零成本）。
            // 写在同一绑定窗口内：writeToTexture 刚绑完目标纹理，generate 直接生效。
            if (mipTop > 0) {
                org.lwjgl.opengl.GL30.glGenerateMipmap(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D);
            }
        }
    }
}
