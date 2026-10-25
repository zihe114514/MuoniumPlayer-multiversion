package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL12;

/**
 * {@link LyricGlyphAtlas.Host} 的 1.20.1 实现 —— 字形图集页 = 一个注册进
 * TextureManager 的 {@link DynamicTexture}（照 1.21.1 的同名类移植）。
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
 * 零 AccessWidener。mip 配方见 {@link PageTexture}（2026-10-25 与 1.21.1 标杆同款
 * 的安全三线性：解钳 + 生成 + 读回自检，失败永久熔断回纯 LINEAR）。</p>
 *
 * <p>像素写入 {@link NativeImage#setPixelRGBA}（内存序 ABGR），上传走 9 参
 * {@link NativeImage#upload} 只发子矩形、且上传前要 {@code bind()} 自己
 * （_upload 不自行绑定，javap 核对）。全部方法只在世界渲染事件（渲染线程）里被图集调用。</p>
 *
 * <h2>与 1.21.1 的差异（javap 核对）</h2>
 * <p>1.20.1 的 {@code ResourceLocation} 只有 {@code new ResourceLocation(String, String)}
 * 构造器（{@code fromNamespaceAndPath} 是 1.21 起的工厂方法）；其余触点
 * （DynamicTexture(int,int,boolean)、NativeImage.setPixelRGBA / 9 参 upload、
 * AbstractTexture.setFilter/load/close、TextureManager.register/release）与 1.21.1 同名同参。</p>
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
        // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
        ResourceLocation rl = new ResourceLocation("muoniumplayer",
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
     * 一页 = RGBA8 动态纹理（构造器完成 prepareImage 分配）+ 安全三线性 mip +
     * CLAMP_TO_EDGE。
     *
     * <p>mip 配方（字节码侦查结论，与 1.21.1 标杆同款；1.20.1 的
     * {@code TextureUtil.prepareImage} 逐字核对）：恒以 mipLevels=0 分配并把
     * MAX_LEVEL/MIN_LOD/MAX_LOD/LOD_BIAS 四参数钉死 0——MAX_LOD=0 会把采样
     * LOD 钳在 0 级（上一次"三线性"静默退化双线性的直接原因）。安全顺序：
     * 解钳（MAX_LEVEL 与 MAX_LOD 都设为精确 floor(log2(min(w,h)))）→ 上传
     * level 0 → 同一绑定窗口 glGenerateMipmap（texImage2D 型纹理可扩级）→
     * 读回自检，失败永久熔断回纯 LINEAR（不完整纹理采样 undefined，主流驱动
     * 返回不透明黑——宁可不糊也不黑）。每次基底层变更后必须重跑 generate，
     * 链不会自动跟随。</p>
     */
    private static final class PageTexture extends DynamicTexture {

        /** 本页的 mip 顶层数（floor(log2(min(w,h)))）；-1 = 已熔断纯 LINEAR。 */
        private final int mipTop;

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
            this.mipTop = setupMipChain(width, height);
        }

        /** shard 绑定时的过滤参数改写：mip 链健康时落三线性，熔断时落 LINEAR。 */
        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(true, mipTop > 0);
        }

        /**
         * 解钳 + 生成完整 mip 链 + 读回自检。返回顶层数；自检不过返回 -1
         * （setFilter 据此永久落纯 LINEAR，纹理由 prepareImage 只分配 0 级，
         * MAX_LEVEL 维持 0 → 完整性只需 0 级，绝不产生不完整采样）。
         * 调用前提：本纹理当前已绑定（构造器分配时刚绑定）。
         */
        private static int setupMipChain(int width, int height) {
            try {
                int top = 31 - Integer.numberOfLeadingZeros(Math.min(width, height));
                if (top < 1) return -1;
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
            // 基底层变了 → 重生成整条链（每个新字形烘焙才触发一次，稳态零成本）
            if (mipTop > 0) {
                org.lwjgl.opengl.GL30.glGenerateMipmap(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D);
            }
        }
    }
}
