package com.muoniumplayer.render.skija;

import com.mojang.blaze3d.opengl.GlStateManager;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * Skia 宿主层：整个渲染层里<b>唯一</b>与 Minecraft 打交道的类。
 *
 * <p>换 Minecraft 版本时只需要改这一个文件。它对上层只暴露「给我一块 Canvas，
 * 坐标系是 GUI 缩放坐标、原点左上、y 向下」这一件事。
 *
 * <h2>为什么要离屏一层</h2>
 * 探针已经证明可以直接把 Minecraft 当前绑定的 FBO 包成 Skia {@code Surface} 来画
 * （像素回读确认过）。但正式实现仍然先画到 Skia 自己的离屏 render target，理由有三条：
 * <ol>
 *   <li><b>裁剪需要 stencil</b>：Minecraft 的主渲染目标默认没有 stencil 附件
 *       （1.20.1 时确认，26.2 依然如此）。让 Skia 自己拥有 render target，
 *       stencil 由 Ganesh 按需分配，复杂路径裁剪不会静默退化成低质量的 mask 裁剪。</li>
 *   <li><b>状态隔离</b>：Skia 与 Minecraft 只在「合成」那一刻交接一次 GL 状态，
 *       而不是在几百次绘制调用之间反复交错。</li>
 *   <li><b>跨版本稳定</b>：1.21.11 起 GUI 变成延迟批处理，绘制与提交分离；
 *       离屏设计正好把「什么时候画」和「什么时候贴上屏」解耦，
 *       所以从 1.20.1 到 1.21.11 再到 26.2，上层业务代码一行都没改过——
 *       每次只需要换帧末那个合成钩子。</li>
 * </ol>
 *
 * <h2>为什么合成也走 Skia</h2>
 * 把离屏纹理贴回主 framebuffer，在 core profile 下需要 VAO + 着色器——那正是本次重写
 * 要删掉的东西。所以合成用第二个 Skia {@code Surface} 包住 Minecraft 的 FBO，
 * 再 {@code drawImage} 过去。全程零手写 GL 绘制代码。
 *
 * <h2>线程约束</h2>
 * 所有方法都必须在渲染线程上调用，且 GL 上下文为当前。{@link #beginFrame()} 会自己检查。
 */
public final class SkijaHost {

    private static final String TAG = "[MuoniumPlayer/SkijaHost] ";

    /** 设成 true 则跳过离屏，直接画到 Minecraft 的 FBO 上。仅用于 A/B 对比排查。 */
    private static final boolean DEBUG_DRAW_DIRECT =
            Boolean.getBoolean("muonium.skija.drawDirect");

    /**
     * {@code DeviceInfo#backendName()} 在 OpenGL 后端下的取值。
     * 字节码实测来自 {@code GlHeuristics#createDeviceInfo} 里的字面量。
     */
    private static final String BACKEND_OPENGL = "OpenGL";

    private static DirectContext context;
    /** 初始化失败后置位，避免每帧重试刷日志。 */
    private static boolean disabled;
    /** 后端探测只做一次；见 {@link #requireOpenGlBackend()}。 */
    private static boolean backendChecked;

    // ---- 离屏目标（Skia 自己拥有，含按需分配的 stencil）----
    private static Surface offscreen;
    private static int offscreenWidth;
    private static int offscreenHeight;

    // ---- 帧内状态 ----
    private static boolean inFrame;
    private static Canvas canvas;
    private static int savedFbo;
    private static final int[] SAVED_VIEWPORT = new int[4];
    private static int frameGuiWidth;
    private static int frameGuiHeight;
    /** 帧缓冲像素 / GUI 单位。等价于 Minecraft 的 guiScale，但不需要业务层碰 MC 类。 */
    private static double frameGuiScale = 1.0;
    /** 本帧是否已经清过离屏图。见 {@link #beginOffscreen}。 */
    private static boolean frameCleared;
    /** 本帧是否有内容等着在帧末合成。见 {@link #compositeFrame()}。 */
    private static boolean pendingComposite;

    /**
     * 窗口默认帧缓冲的 FBO 名。合成目标恒为它，理由见 {@link #compositeFrame()}。
     *
     * <p>写成常量而不是「读当前绑定」是刻意的：每个 render pass 结束时
     * {@code GlCommandEncoder#closePass()} 都会把绑定重置成 0，
     * 「当前绑定」这个概念在帧末已经不携带任何有用信息了。
     */
    private static final int WINDOW_FRAMEBUFFER = 0;

    /**
     * 是否还没打过「首次合成成功」那条日志。
     *
     * <p>留这条日志是有理由的：合成失败时的表现是「没有任何报错、界面就是不出现」，
     * 光看日志无法区分「根本没走到合成」和「合成了但被覆盖」。有这一行就能一眼分辨。
     */
    private static boolean firstCompositeUnlogged = true;

    private SkijaHost() {
    }

    /** 渲染层是否可用。false 时上层应当整帧跳过绘制，而不是画一半。 */
    public static boolean isAvailable() {
        return !disabled;
    }

    /**
     * 确认当前 GPU 后端是 OpenGL；不是就 {@link #fail} 掉整个渲染层。
     *
     * <h2>为什么 26.2 需要这一步</h2>
     * 26.2 引入了可切换的图形后端（{@code net.minecraft.client.PreferredGraphicsApi}：
     * {@code DEFAULT / OPENGL / VULKAN}），并新增了完整的 Vulkan 实现
     * （{@code com.mojang.blaze3d.vulkan.*}）。而本渲染层是 GL 互操作：
     * {@code DirectContext.makeGL()} 让 Skia 直接复用 Minecraft 的 GL 上下文，
     * 合成时又把 GL 帧缓冲名包成 Skia surface（见 {@link #composite}）。
     * Vulkan 下这些前提一条都不成立，帧末钩子（{@code GlSurface#present()}）
     * 也根本不会被调用。
     *
     * <p>所以这里必须<b>主动报错</b>，而不是让它静默失败。1.21.11 时期踩过的坑
     * 就是「初始化成功、无异常、无 GL 错误，界面就是不出现」——最难排查的一类症状。
     * 用户把画面 API 切到 Vulkan 时如果什么日志都没有，会以为是 mod 装坏了。
     *
     * <h2>为什么用 backendName() 而不是 instanceof</h2>
     * 26.2 的 {@code GpuDevice} 是<b>具体类</b>，OpenGL 与 Vulkan 的差异藏在它私有的
     * {@code GpuDeviceBackend} 字段里，从外部拿不到，用 instanceof 判不出来。
     * 公开可达的唯一后端标识是 {@code getDeviceInfo().backendName()}，
     * 字节码实测：{@code GlHeuristics#createDeviceInfo} 写死 {@code "OpenGL"}，
     * {@code VulkanDevice} 写死 {@code "Vulkan"}。
     *
     * <p>用 {@code tryGetDevice()} 而不是 {@code getDevice()}：后者在渲染器尚未初始化时
     * 直接抛 {@code IllegalStateException}。设备还没建好只说明「现在问得太早」，
     * 那时应该安静地跳过这一帧，而不是把渲染层永久停用。
     *
     * @return true 表示可以继续；false 表示本帧必须放弃（可能是暂时的，也可能已被停用）
     */
    private static boolean requireOpenGlBackend() {
        if (backendChecked) {
            return !disabled;
        }
        com.mojang.blaze3d.systems.GpuDevice device =
                com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
        if (device == null) {
            // 渲染器还没初始化完，不是错误。下一帧再问。
            return false;
        }
        String backend = device.getDeviceInfo().backendName();
        backendChecked = true;
        if (BACKEND_OPENGL.equalsIgnoreCase(backend)) {
            log("GPU 后端 = " + backend + "，Skia 的 GL 互操作可用");
            return true;
        }
        fail("当前 GPU 后端是 " + backend + "，本渲染层只支持 OpenGL。"
                + "请在 视频设置 → 图形 API 中选择 OpenGL（或 默认）后重启游戏", null);
        return false;
    }

    /**
     * 开一帧。成功返回一块已经设置好 GUI 坐标系的 {@link Canvas}，失败返回 null。
     *
     * <p>返回的 canvas 已经：清空为全透明、按 GUI 缩放因子做过 scale、
     * 压了一层 {@code save()}（{@link #endFrame()} 会配对 restore，
     * 因此上层即使 push/pop 不配对也不会污染下一帧）。
     */
    public static Canvas beginFrame() {
        if (disabled || inFrame) {
            return null;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return null;
            }
            Window window = client.getWindow();
            if (window == null) {
                return null;
            }

            // 26.2 的官方名：getGuiScaledWidth/Height 是 GUI 逻辑尺寸，
            // getWidth/getHeight 是 framebuffer 像素尺寸（Yarn 里后者叫 getFramebufferWidth/Height）。
            int guiWidth = window.getGuiScaledWidth();
            int guiHeight = window.getGuiScaledHeight();
            int pixelWidth = window.getWidth();
            int pixelHeight = window.getHeight();
            if (guiWidth <= 0 || guiHeight <= 0 || pixelWidth <= 0 || pixelHeight <= 0) {
                return null;
            }

            if (!requireOpenGlBackend()) {
                return null;
            }
            if (!SkijaNatives.ensureLoaded()) {
                fail("Skija 原生库不可用: " + SkijaNatives.failure(), null);
                return null;
            }
            if (context == null) {
                context = DirectContext.makeGL();
                if (context == null) {
                    fail("DirectContext.makeGL() 返回 null", null);
                    return null;
                }
                log("DirectContext 创建成功");
            }

            savedFbo = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            org.lwjgl.opengl.GL11.glGetIntegerv(
                    org.lwjgl.opengl.GL11.GL_VIEWPORT, SAVED_VIEWPORT);

            // Minecraft 刚刚做过大量 GL 调用，Skia 对 GL 状态的缓存已经失真。
            // 不 reset 的话它会基于错误假设省略状态切换，画出来的东西随机出错。
            // 顺序很重要：先拍快照（此刻的真实 GL 状态就是 Minecraft 期望的状态），
            // 再让 Skia 忘掉它自己的状态缓存，最后才为 Skia 的字形图集上传清像素解包布局。
            SNAPSHOT.capture();
            context.resetAll();
            resetPixelUnpackLayout();

            frameGuiWidth = guiWidth;
            frameGuiHeight = guiHeight;
            frameGuiScale = (double) pixelWidth / (double) guiWidth;

            if (DEBUG_DRAW_DIRECT) {
                canvas = beginDirect(pixelWidth, pixelHeight);
            } else {
                canvas = beginOffscreen(pixelWidth, pixelHeight);
            }
            if (canvas == null) {
                return null;
            }

            // 离屏目标按帧缓冲像素尺寸分配，而业务代码用 GUI 缩放坐标。
            // 这里一次抹平，之后上层只见 GUI 坐标。
            canvas.save();
            float scale = (float) pixelWidth / (float) guiWidth;
            canvas.scale(scale, scale);

            inFrame = true;
            // 绘制状态（当前颜色 / 混合模式 / 两个状态栈）与裁剪栈每帧从零开始。
            // 上一帧如果因为异常留下了没配对的 push，不清掉会一路传染下去。
            SkijaGfx.beginFrame();
            SkijaClip.resetFrame();
            return canvas;
        } catch (Throwable t) {
            fail("beginFrame 失败", t);
            return null;
        }
    }

    /**
     * 清掉「像素解包」的行跨距与跳过量。
     *
     * <p>这是 Skia 在 Minecraft 里画文字的必要前提，原因很具体：
     * Minecraft 的 {@code NativeImage#upload} 会设置 {@code GL_UNPACK_ROW_LENGTH}
     * 以及 {@code GL_UNPACK_SKIP_PIXELS/SKIP_ROWS}，而且<b>用完不还原</b>。
     * 这是全局状态。Skia 的 GPU 文字渲染依赖一张字形图集纹理，
     * 每有新字形就用 {@code glTexSubImage2D} 增量上传；跨距不对时图集内容错位，
     * 表现为「形状、渐变、裁剪全都正常，只有文字画出少数几个错误字形、多数字符缺失」。
     *
     * <p>实测过程：本工程的文字链路静态测试（{@code SkijaTextStaticTest}）四层全绿——
     * 字形覆盖、度量、CPU 光栅、HarfBuzz 整形都正确——唯独 GPU 路径出错，
     * 由此定位到「与 GPU 纹理上传相关的全局状态」，而本代码库早先就记录过
     * 同一个坑（旧渲染层的 {@code FixedFunction.resetPixelUnpackLayout}）。
     *
     * <p>只清跨距与跳过量，并把 {@code GL_UNPACK_ALIGNMENT} 恢复成 GL 默认值 4。
     */
    private static void resetPixelUnpackLayout() {
        GlStateManager._pixelStore(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    private static Canvas beginOffscreen(int pixelWidth, int pixelHeight) {
        if (offscreen == null || offscreenWidth != pixelWidth || offscreenHeight != pixelHeight) {
            closeOffscreen();
            // budgeted=false：这块目标生命周期由我们自己控制，不希望 Skia 的资源缓存
            // 在内存压力下把它回收掉。
            offscreen = Surface.makeRenderTarget(context, false,
                    ImageInfo.makeN32Premul(pixelWidth, pixelHeight));
            if (offscreen == null) {
                fail("Surface.makeRenderTarget 返回 null（" + pixelWidth + "x" + pixelHeight + "）", null);
                return null;
            }
            offscreenWidth = pixelWidth;
            offscreenHeight = pixelHeight;
            log("离屏目标已建立 " + pixelWidth + "x" + pixelHeight);
        }
        Canvas c = offscreen.getCanvas();
        // 必须清成全透明。清成不透明黑会把整个屏幕糊掉。
        //
        // 一帧里 beginFrame/endFrame 会成对出现<b>多次</b>（HUD 覆盖层、GuiScreen 渲染、
        // 任意 Screen 的 afterRender 各一次），而合成被推迟到了帧末（见 compositeFrame）。
        // 如果每次 beginFrame 都清屏，最后一对 begin/end 就会把前面几次画好的内容全部擦掉，
        // 帧末合成的是一张只剩最后一块内容的图。所以每帧只清第一次。
        if (!frameCleared) {
            c.clear(0x00000000);
            frameCleared = true;
        }
        return c;
    }

    private static Canvas beginDirect(int pixelWidth, int pixelHeight) {
        // 直连模式不建离屏，也就没有 stencil；只用于对比排查，不是正式路径。
        BackendRenderTarget target = BackendRenderTarget.makeGL(
                pixelWidth, pixelHeight, 0, 0, savedFbo, FramebufferFormat.GR_GL_RGBA8);
        Surface s = Surface.wrapBackendRenderTarget(context, target, SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888, ColorSpace.getSRGB());
        if (s == null) {
            target.close();
            fail("直连模式 wrapBackendRenderTarget 返回 null", null);
            return null;
        }
        directTarget = target;
        directSurface = s;
        return s.getCanvas();
    }

    private static BackendRenderTarget directTarget;
    private static Surface directSurface;

    /**
     * 结束一段绘制：把 GL 状态交还给 Minecraft。
     *
     * <p><b>这里刻意不做合成。</b>26.2 的 GUI 是延迟批处理：{@code GuiGraphicsExtractor} 的每个方法
     * 只是往 {@code GuiRenderState} 里追加一条 render state，真正的 GPU 提交由
     * {@code GuiRenderer#render} 在帧末统一完成。而本渲染层的调用点
     * （HUD 元素回调、{@code Screen#extractRenderState}、{@code ScreenEvents.afterExtract}）全都落在
     * 「收集」阶段——此刻原版 GUI <b>一个像素都还没画</b>。
     * 在这里合成，画面会被随后提交的原版 GUI（界面背景的模糊/暗化等）整片盖住。
     *
     * <p>所以合成推迟到 {@link #compositeFrame()}，由挂在 {@code GlSurface#present()}
     * 入口的 mixin 触发：那时这一帧的最终画面已经在窗口默认帧缓冲里，我们盖在最上面。
     */
    public static void endFrame() {
        if (!inFrame) {
            return;
        }
        inFrame = false;
        try {
            // 业务代码可能压了变换却没弹（异常路径、或者 push/pop 数量写错）。
            // 只 restore 一层的话，本帧的坐标系会残留到下一帧。这里先把上层压的层数收干净。
            SkijaGfx.restoreDepthTo(0);
            SkijaGfx.endFrame();
            SkijaClip.resetFrame();
            canvas.restore();
            if (DEBUG_DRAW_DIRECT) {
                // 直连模式没有离屏图，本来就是即时画到 MC 的 FBO 上，只需要提交。
                context.flushAndSubmit(directSurface);
            } else {
                // 只 flush，不合成：把 Skia 排的活真正交给 GPU，但先别贴回去。
                context.flush(offscreen);
                pendingComposite = true;
            }
        } catch (Throwable t) {
            fail("endFrame 失败", t);
        } finally {
            canvas = null;
            closeDirect();
            restoreMinecraftState();
        }
    }

    /**
     * 帧末合成：把本帧攒好的离屏图贴到<b>窗口默认帧缓冲（FBO 0）</b>上。
     *
     * <p>由 {@code GlSurfaceMixin} 在 {@code GlSurface#present()} 的入口调用。
     *
     * <h2>为什么不能在自己的调用点里合成</h2>
     * 26.2 的 GUI 是延迟批处理：{@code GuiGraphicsExtractor} 只往 {@code GuiRenderState} 追加
     * render state，真正的提交在帧末的 {@code GuiRenderer#render} 里。本渲染层的三个调用点
     * （HUD 元素回调、{@code Screen#extractRenderState}、{@code ScreenEvents.afterExtract}）都在
     * 「收集」阶段，那时原版 GUI 一个像素都还没画，在那里贴图会被随后提交的原版 GUI 整片盖住。
     *
     * <h2>为什么挂 GuiRenderer#render 的出口不行（1.21.11 时实测翻过车）</h2>
     * 每个 render pass 结束时 {@code GlCommandEncoder#closePass()} 都会执行
     * {@code glBindFramebuffer(GL_FRAMEBUFFER, 0)}，所以那个出口处绑定的是窗口默认帧缓冲；
     * 但画面此时还没送到默认帧缓冲上，随后 Minecraft 还要把主帧缓冲整屏 blit 过来，
     * 正好把我们刚贴的内容<b>全部覆盖</b>。
     * 症状是「Skija 初始化成功、没有任何报错、也没有 GL 错误，界面就是不出现」。
     *
     * <h2>为什么挂 GlSurface#present() 的入口是对的</h2>
     * 26.2 把「把画面送上屏」抽成了 {@code GpuSurfaceBackend}：先
     * {@code blitFromTexture(...)}（OpenGL 实现里是
     * {@code GlCommandEncoder#presentTexture}，字节码实测是
     * {@code blitFrameBuffers(drawFbo, 0, ...)}，即把主目标 blit 到默认帧缓冲 0），
     * 再 {@code present()}（OpenGL 实现里只有一句 {@code glfwSwapBuffers}）。
     * 所以 {@code present()} 的入口就是「最终画面已经在默认帧缓冲里、还没交换缓冲」那一刻，
     * 与 1.21.11 时挂 {@code Framebuffer#blitToScreen()} 出口等价——而那个方法在 26.2 已被删除。
     *
     * <p>注意这条链路只存在于 OpenGL 后端。26.2 新增了 Vulkan 后端（{@code PreferredGraphicsApi}），
     * 那种情况下本 mixin 的目标方法根本不会被调用，Skija 的 GL 互操作也无从谈起；
     * 后端探测与明确报错在 {@link #requireOpenGlBackend()}。
     * 也不需要把离屏纹理包成 {@code GpuTextureView} 塞进 GUI 批次
     * （吃 {@code GpuTextureView} 的 {@code drawTexturedQuad} 重载是 private，
     * 既要额外放开 access widener，还得处理预乘 alpha 的差异）。
     *
     * <p>无论本帧有没有内容都要调用：它同时负责复位「本帧已清屏」的标志，
     * 否则下一帧会在旧内容上继续画。
     */
    public static void compositeFrame() {
        // 背景采集必须排在合成<b>之前</b>：这一刻窗口默认帧缓冲里正好是「本帧游戏最终画面 +
        // 还不含我们的 HUD」，那就是玻璃要折射的底。放到合成之后采，抄进去的背景就带上我们
        // 自己的界面，玻璃会变成「透自己」。
        GlassBackdrop.captureNow(0, offscreenWidth, offscreenHeight);
        boolean hadContent = pendingComposite;
        pendingComposite = false;
        frameCleared = false;
        if (disabled || DEBUG_DRAW_DIRECT || !hadContent || offscreen == null || context == null) {
            return;
        }
        if (inFrame) {
            // 不该发生：说明有调用点 beginFrame 之后没 endFrame。此时离屏图还没 flush，
            // 强行合成会画出半成品，宁可这一帧不显示。
            fail("compositeFrame 时仍在帧内，跳过合成", null);
            return;
        }
        try {
            savedFbo = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            org.lwjgl.opengl.GL11.glGetIntegerv(
                    org.lwjgl.opengl.GL11.GL_VIEWPORT, SAVED_VIEWPORT);
            SNAPSHOT.capture();
            context.resetAll();
            resetPixelUnpackLayout();
            // 显式绑定合成目标。用 GL_FRAMEBUFFER 一次改掉读和写两个绑定，
            // 不给 Skia 留下一个还指向别处的 READ 绑定。
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, WINDOW_FRAMEBUFFER);
            // 默认帧缓冲的尺寸就是窗口帧缓冲尺寸，也就是离屏图的尺寸；视口按整屏给。
            GlStateManager._viewport(0, 0, offscreenWidth, offscreenHeight);
            org.lwjgl.opengl.GL11.glViewport(0, 0, offscreenWidth, offscreenHeight);
            composite(WINDOW_FRAMEBUFFER);
            if (firstCompositeUnlogged) {
                firstCompositeUnlogged = false;
                log("首次帧末合成完成 " + offscreenWidth + "x" + offscreenHeight
                        + " → 窗口默认帧缓冲");
            }
        } catch (Throwable t) {
            fail("compositeFrame 失败", t);
        } finally {
            restoreMinecraftState();
        }
    }

    /**
     * 把离屏纹理作为一张图片画到给定的 FBO 上。
     *
     * @param targetFbo 目标帧缓冲的 GL 名。当前恒为 {@link #WINDOW_FRAMEBUFFER}，
     *                  保留成参数是为了让「往哪儿合成」这件事在调用点上写明，
     *                  而不是藏在一个共享字段里（{@code savedFbo} 曾经兼任过这个角色，
     *                  正是那次混用导致贴错目标）。
     */
    private static void composite(int targetFbo) {
        context.flush(offscreen);
        // makeImageSnapshot 在 GPU 目标上通常只是加一次纹理引用，不做像素拷贝。
        try (Image snapshot = offscreen.makeImageSnapshot();
             BackendRenderTarget mcTarget = BackendRenderTarget.makeGL(
                     offscreenWidth, offscreenHeight, 0, 0, targetFbo,
                     FramebufferFormat.GR_GL_RGBA8);
             Surface mcSurface = Surface.wrapBackendRenderTarget(context, mcTarget,
                     // BOTTOM_LEFT 告诉 Skia 这个 FBO 是 GL 的左下原点，
                     // 于是它内部翻转，canvas 的 (0,0) 在视觉上仍是左上角。
                     SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB())) {
            if (snapshot == null || mcSurface == null) {
                return;
            }
            // 离屏是 TOP_LEFT 原点的 Skia 自有目标，目标 FBO 是 BOTTOM_LEFT，
            // 翻转由 SurfaceOrigin 处理，这里直接 1:1 贴上去即可。
            mcSurface.getCanvas().drawImage(snapshot, 0f, 0f);
            context.flushAndSubmit(mcSurface);
        }
    }

    /**
     * Skia 运行前的 GL 状态快照。{@link #restoreMinecraftState()} 把它<b>原样</b>写回去。
     *
     * <h2>为什么必须是「快照 + 原样还原」，而不是「设成一套已知的好值」</h2>
     * Minecraft 的 {@code GlStateManager} 对几乎所有状态都做了缓存：想设的值和缓存相同就
     * <b>直接跳过 GL 调用</b>。Skia 用裸 GL 在背后改了真实状态，缓存却以为没变，于是
     * Minecraft 后续绘制会省略必要的状态切换。
     *
     * <p>早先的做法是「把状态强设成 GUI 阶段应该的样子」，还要靠「先设反值再设回」逼
     * {@code GlStateManager} 真的发调用。这个做法有两个洞：
     * <ol>
     *   <li>我们的钩子不止在 GUI 阶段触发（HUD 覆盖层、任意 Screen 的 afterRender），
     *       「GUI 阶段应该的样子」并不总是调用点原本的样子；</li>
     *   <li>{@code GlStateManager} 压根不管的状态（混合方程、{@code GL_STENCIL_TEST} 开关、
     *       采样器对象、{@code GL_FRAMEBUFFER_SRGB}…）永远不会被 Minecraft 复位，
     *       Skia 改了就一直留着。实测表现就是天空与云被画成错误色块、主菜单全景图糊掉、
     *       手持与掉落物贴图错乱。</li>
     * </ol>
     *
     * <p>换成快照之后这两个洞一起消失：还原之后真实状态 == Skia 运行之前的状态，
     * 而那正是 {@code GlStateManager} 缓存所描述的状态，缓存自动重新变得诚实，
     * 一次「设反值」的技巧都不需要。
     *
     * <h2>纹理单元为什么要「缓存与裸 GL 一起写」</h2>
     * 纹理上传链路（{@code compat/gl/GL11}、{@code TextureUtil}）会在 Skia 帧内部通过
     * {@code GlStateManager._bindTexture} 换绑纹理，这会更新缓存。如果还原时只改真实 GL、
     * 不动缓存，就会制造出「缓存说绑着 A、真实绑着 B」的分歧。
     *
     * <p>但反过来「只调 GlStateManager」是<b>错的</b>，这一条踩过实机故障：
     * {@code _activeTexture} 与 {@code _bindTexture} 都是「值和缓存相同就跳过 GL 调用」的
     * 实现，而 Skia 是用裸 GL 改绑定的，缓存压根没变。于是「把缓存值再设一遍」这个动作
     * 每一项都会被跳过，真实绑定留在 Skia 那边。后果不是立刻花屏，而是延迟到
     * <b>下一次纹理上传</b>：{@code GlCommandEncoder#writeToTexture} 里那句
     * {@code _bindTexture} 同样会被跳过，字形数据被写进了 Skia 的纹理，
     * 原版字体图集对应的格子永远是垃圾像素——表现为原版界面里个别汉字变成彩色噪点
     * （实测是暂停菜单「回到游戏」的「游戏」两字）。
     *
     * <p>所以这一项必须两头都写：缓存走 {@code GlStateManager}，真实状态用裸 GL 兜底。
     */
    private static final class GlSnapshot {

        private static final int UNITS = 12;

        /** {@code glBindSampler} / {@code GL_SAMPLER_BINDING} 不可用时置位，避免每帧抛异常。 */
        private boolean samplersUnavailable;
        private boolean valid;

        private int program;
        private int vertexArray;
        private int arrayBuffer;
        private int elementArrayBuffer;

        private int activeTexture;
        private final int[] boundTextures = new int[UNITS];
        private final int[] boundSamplers = new int[UNITS];

        private boolean blend;
        private int blendSrcRgb;
        private int blendDstRgb;
        private int blendSrcAlpha;
        private int blendDstAlpha;
        private int blendEquationRgb;
        private int blendEquationAlpha;

        private boolean depthTest;
        private boolean depthMask;
        private int depthFunc;
        private final float[] depthRange = new float[2];

        private boolean cull;
        private int cullFaceMode;
        private int frontFace;

        private boolean scissor;
        private final int[] scissorBox = new int[4];

        private boolean stencilTest;
        private int stencilWriteMask;
        private int stencilFunc;
        private int stencilRef;
        private int stencilValueMask;
        private int stencilFail;
        private int stencilPassDepthFail;
        private int stencilPassDepthPass;

        private final int[] colorMask = new int[4];

        private boolean polygonOffsetFill;
        private final float[] polygonOffset = new float[2];

        private boolean colorLogicOp;
        private int logicOpMode;

        private boolean framebufferSrgb;
        private boolean primitiveRestart;

        private int unpackRowLength;
        private int unpackSkipPixels;
        private int unpackSkipRows;
        private int unpackAlignment;

        void capture() {
            program = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
            vertexArray = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
            arrayBuffer = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
            elementArrayBuffer = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

            activeTexture = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            for (int unit = 0; unit < UNITS; unit++) {
                org.lwjgl.opengl.GL13.glActiveTexture(
                        org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
                boundTextures[unit] = org.lwjgl.opengl.GL11.glGetInteger(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                boundSamplers[unit] = readSampler();
            }
            org.lwjgl.opengl.GL13.glActiveTexture(activeTexture);

            blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
            blendSrcRgb = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB);
            blendDstRgb = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
            blendSrcAlpha = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA);
            blendDstAlpha = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
            blendEquationRgb = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_RGB);
            blendEquationAlpha = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_ALPHA);

            depthTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            depthMask = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK) != 0;
            depthFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC);
            org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_DEPTH_RANGE, depthRange);

            cull = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
            cullFaceMode = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_CULL_FACE_MODE);
            frontFace = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_FRONT_FACE);

            scissor = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL11.glGetIntegerv(
                    org.lwjgl.opengl.GL11.GL_SCISSOR_BOX, scissorBox);

            stencilTest = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            stencilWriteMask = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_WRITEMASK);
            stencilFunc = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_FUNC);
            stencilRef = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_REF);
            stencilValueMask = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_VALUE_MASK);
            stencilFail = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_FAIL);
            stencilPassDepthFail = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_PASS_DEPTH_FAIL);
            stencilPassDepthPass = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_PASS_DEPTH_PASS);

            org.lwjgl.opengl.GL11.glGetIntegerv(
                    org.lwjgl.opengl.GL11.GL_COLOR_WRITEMASK, colorMask);

            polygonOffsetFill = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL);
            polygonOffset[0] = org.lwjgl.opengl.GL11.glGetFloat(
                    org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FACTOR);
            polygonOffset[1] = org.lwjgl.opengl.GL11.glGetFloat(
                    org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_UNITS);

            colorLogicOp = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL11.GL_COLOR_LOGIC_OP);
            logicOpMode = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_LOGIC_OP_MODE);

            framebufferSrgb = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB);
            primitiveRestart = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL31.GL_PRIMITIVE_RESTART);

            unpackRowLength = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH);
            unpackSkipPixels = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS);
            unpackSkipRows = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS);
            unpackAlignment = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT);

            valid = true;
        }

        void restore() {
            if (!valid) {
                return;
            }
            org.lwjgl.opengl.GL20.glUseProgram(program);
            // 顺序有讲究：GL_ELEMENT_ARRAY_BUFFER 的绑定属于 VAO 状态，
            // 必须先把 VAO 绑回去，否则会把 index buffer 写到错误的 VAO 上。
            org.lwjgl.opengl.GL30.glBindVertexArray(vertexArray);
            org.lwjgl.opengl.GL15.glBindBuffer(
                    org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
            org.lwjgl.opengl.GL15.glBindBuffer(
                    org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, arrayBuffer);

            // 纹理单元：缓存与真实状态都要落到快照值。
            // 只调 GlStateManager 会因为「值相同就跳过」而整段变成空操作，见类注释。
            for (int unit = 0; unit < UNITS; unit++) {
                selectUnit(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
                writeSampler(unit, boundSamplers[unit]);
                bindTextureExactly(boundTextures[unit]);
            }
            selectUnit(activeTexture);

            setEnabled(org.lwjgl.opengl.GL11.GL_BLEND, blend);
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(
                    blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            org.lwjgl.opengl.GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);

            setEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST, depthTest);
            org.lwjgl.opengl.GL11.glDepthMask(depthMask);
            org.lwjgl.opengl.GL11.glDepthFunc(depthFunc);
            org.lwjgl.opengl.GL11.glDepthRange(depthRange[0], depthRange[1]);

            setEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE, cull);
            org.lwjgl.opengl.GL11.glCullFace(cullFaceMode);
            org.lwjgl.opengl.GL11.glFrontFace(frontFace);

            setEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST, scissor);
            org.lwjgl.opengl.GL11.glScissor(
                    scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);

            setEnabled(org.lwjgl.opengl.GL11.GL_STENCIL_TEST, stencilTest);
            org.lwjgl.opengl.GL11.glStencilMask(stencilWriteMask);
            org.lwjgl.opengl.GL11.glStencilFunc(stencilFunc, stencilRef, stencilValueMask);
            org.lwjgl.opengl.GL11.glStencilOp(
                    stencilFail, stencilPassDepthFail, stencilPassDepthPass);

            org.lwjgl.opengl.GL11.glColorMask(colorMask[0] != 0, colorMask[1] != 0,
                    colorMask[2] != 0, colorMask[3] != 0);

            setEnabled(org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL, polygonOffsetFill);
            org.lwjgl.opengl.GL11.glPolygonOffset(polygonOffset[0], polygonOffset[1]);

            setEnabled(org.lwjgl.opengl.GL11.GL_COLOR_LOGIC_OP, colorLogicOp);
            org.lwjgl.opengl.GL11.glLogicOp(logicOpMode);

            setEnabled(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB, framebufferSrgb);
            setEnabled(org.lwjgl.opengl.GL31.GL_PRIMITIVE_RESTART, primitiveRestart);

            // 像素解包同样走 GlStateManager：纹理上传链路读写的是它的缓存。
            GlStateManager._pixelStore(
                    org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, unpackRowLength);
            GlStateManager._pixelStore(
                    org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels);
            GlStateManager._pixelStore(
                    org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, unpackSkipRows);
            GlStateManager._pixelStore(
                    org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
        }

        /**
         * 切换活动纹理单元，并保证 {@code GlStateManager} 的缓存与真实 GL 都指向它。
         *
         * <p>{@code _activeTexture} 会在「缓存已经是这个单元」时跳过 GL 调用。
         * 而 {@code capture()} 是用裸 {@code glActiveTexture} 走完 12 个单元的，
         * Skia 也用裸 GL 切单元，所以缓存值完全不能代表真实状态。
         * 先调 {@code GlStateManager}（缓存对了，跳过与否都无妨），再裸调一次（真实一定对）。
         *
         * @param textureEnum {@code GL_TEXTURE0 + unit} 形式的枚举值，不是单元序号
         */
        private static void selectUnit(int textureEnum) {
            GlStateManager._activeTexture(textureEnum);
            org.lwjgl.opengl.GL13.glActiveTexture(textureEnum);
        }

        /**
         * 把当前单元的 {@code GL_TEXTURE_2D} 绑定精确恢复成 {@code texture}，
         * 缓存与真实 GL 都对。
         *
         * <p>{@code _bindTexture} 比较的是缓存值。快照值非 0 时先经缓存绑 0：
         * 此时缓存必然不等于 0（它还停在快照值上），这一步一定会发出真正的
         * {@code glBindTexture}，紧接着绑回快照值也一定会发出，两头都对。
         * 快照值为 0 时缓存可能已经是 0 而被跳过，所以补一次裸调兜底。
         */
        private static void bindTextureExactly(int texture) {
            if (texture != 0) {
                GlStateManager._bindTexture(0);
                GlStateManager._bindTexture(texture);
            } else {
                GlStateManager._bindTexture(0);
                org.lwjgl.opengl.GL11.glBindTexture(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
            }
        }

        private static void setEnabled(int cap, boolean enabled) {
            if (enabled) {
                org.lwjgl.opengl.GL11.glEnable(cap);
            } else {
                org.lwjgl.opengl.GL11.glDisable(cap);
            }
        }

        /**
         * 读取当前纹理单元上绑定的采样器对象。
         *
         * <p>Minecraft 申请的是 GL 3.2 上下文，而采样器对象属于 3.3 / ARB_sampler_objects。
         * 驱动一般都导出（Skia 正是靠它给纹理单元挂采样器，而采样器一旦绑上就<b>完全覆盖</b>
         * 纹理自身的过滤与包裹参数——这正是方块图集会变糊/错误重复的原因）。
         * 但不能假定一定有：第一次失败就记下来，之后不再尝试。
         */
        private int readSampler() {
            if (samplersUnavailable) {
                return 0;
            }
            try {
                return org.lwjgl.opengl.GL11.glGetInteger(
                        org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING);
            } catch (Throwable t) {
                samplersUnavailable = true;
                log("采样器对象不可用，跳过其快照/还原: " + t);
                return 0;
            }
        }

        private void writeSampler(int unit, int sampler) {
            if (samplersUnavailable) {
                return;
            }
            try {
                org.lwjgl.opengl.GL33.glBindSampler(unit, sampler);
            } catch (Throwable t) {
                samplersUnavailable = true;
                log("采样器对象不可用，跳过其快照/还原: " + t);
            }
        }
    }

    private static final GlSnapshot SNAPSHOT = new GlSnapshot();

    /**
     * 把 GL 状态交还给 Minecraft：原样写回 {@link GlSnapshot} 在本帧开始前拍下的快照。
     *
     * <p>还原之后真实 GL 状态与 {@code GlStateManager} 的缓存重新一致，
     * 因此不需要再对任何一项做「设反值再设回」的强制刷新。
     */
    private static void restoreMinecraftState() {
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, savedFbo);
            // _viewport 还要把值写进 GlStateManager 的静态字段（Minecraft 靠它回答
            // getViewportWidth 之类的查询），所以两边都设。
            GlStateManager._viewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1],
                    SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
            org.lwjgl.opengl.GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1],
                    SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);

            SNAPSHOT.restore();

            // 1.20.1 时这里要调 net.minecraft.client.render.BufferRenderer.reset()：那个类单独缓存
            // 「当前绑定的 VertexBuffer」，不经过 GlStateManager，漏掉就会让驱动报
            // "GL_INVALID_OPERATION: Array object is not active"。
            //
            // 1.21.11 里 BufferRenderer 整个类已经删除，顶点缓冲的绑定由 GlCommandEncoder /
            // RenderPass 在每个 pass 里自己管。加上 GlSnapshot 是「原样还原」——VAO 与两个 buffer
            // 都回到 Skia 运行前的值——所以不存在需要额外失效的第三方缓存。
        } catch (Throwable t) {
            fail("restoreMinecraftState 失败", t);
        }
    }

    /** GUI 缩放坐标下的画布宽度（帧内有效）。 */
    public static int guiWidth() {
        return frameGuiWidth;
    }

    /** GUI 缩放坐标下的画布高度（帧内有效）。 */
    public static int guiHeight() {
        return frameGuiHeight;
    }

    /**
     * 一个 GUI 单位对应多少物理像素。
     *
     * <p>旧的泛光/模糊着色器有一部分半径是按<b>物理像素</b>给的（texel_size 用了
     * Display 尺寸），而 Skia 画布工作在 GUI 单位上。需要这个比例才能把那些半径
     * 换算成等效的 sigma，否则不同 guiScale 下模糊强度会漂移。
     */
    public static double guiScale() {
        return frameGuiScale <= 0 ? 1.0 : frameGuiScale;
    }

    /**
     * Skia 的 GPU 上下文。{@link SkijaImages} 需要它来包装 Minecraft 的 GL 纹理。
     * 只在渲染层内部使用，业务代码不应该看见它。
     */
    static DirectContext context() {
        return context;
    }

    /**
     * 渲染层是否已停用（原生库缺失、初始化失败、后端不对等）。
     *
     * <p>给 {@link GlassBackdrop} 用：停用那一刻就没有任何消费者会去读背景图，
     * 每帧再拷一次纹理纯属白干。
     */
    static boolean isDisabled() {
        return disabled;
    }

    /** 帧内当前 canvas；不在帧内时为 null。 */
    public static Canvas canvas() {
        return canvas;
    }

    public static boolean inFrame() {
        return inFrame;
    }

    /** 游戏关闭 / 渲染层重载时释放 GPU 资源。 */
    public static void shutdown() {
        // 背景采集与折射各自持有一个绑在<b>当前</b> GL 上下文 / DirectContext 上的对象：
        // 上下文没了之后那些 id 就是别人的了，必须在这里一起丢掉（GL 还是 current 的，
        // 所以现在删才删得掉）。丢掉之后 broken 也复位，重载完成的那一帧会重新建。
        GlassBackdrop.invalidate();
        GlassRefraction.release();
        SkijaGfx.dispose();
        SkijaText.dispose();
        SkijaImages.clear();
        closeDirect();
        closeOffscreen();
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private static void closeOffscreen() {
        if (offscreen != null) {
            offscreen.close();
            offscreen = null;
        }
        offscreenWidth = 0;
        offscreenHeight = 0;
    }

    private static void closeDirect() {
        if (directSurface != null) {
            directSurface.close();
            directSurface = null;
        }
        if (directTarget != null) {
            directTarget.close();
            directTarget = null;
        }
    }

    private static void fail(String message, Throwable t) {
        disabled = true;
        log("已停用 Skia 渲染层: " + message);
        if (t != null) {
            t.printStackTrace();
        }
    }

    private static void log(String message) {
        System.out.println(TAG + message);
    }
}
