package com.muoniumplayer.render.skija;

import com.mojang.blaze3d.platform.GlStateManager;
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
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;

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
 *   <li><b>裁剪需要 stencil</b>：1.20.1 的主 framebuffer 默认没有 stencil 附件。
 *       让 Skia 自己拥有 render target，stencil 由 Ganesh 按需分配，
 *       复杂路径裁剪不会静默退化成低质量的 mask 裁剪。</li>
 *   <li><b>状态隔离</b>：Skia 与 Minecraft 只在「合成」那一刻交接一次 GL 状态，
 *       而不是在几百次绘制调用之间反复交错。</li>
 *   <li><b>为移植铺路</b>：1.21.11 的 GUI 是延迟批处理的，必须有一个
 *       「把画好的东西作为一个 GUI 元素提交回去」的接口。现在就按离屏设计，
 *       将来移植时上层一行都不用改。</li>
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

    private static DirectContext context;
    /**
     * 初始化失败后置位，避免每帧重试刷日志。
     *
     * <p>不是「永远不再试」：{@link #maybeResume()} 按 {@link #RESUME_INTERVAL_NANOS} 的退避
     * 重新评估一次。理由很具体——非 Windows 用户的原生库来自伴生包，「装好文件但没重启」
     * 和「压根没装」如果都表现为永久停用，用户看到的是一模一样的白屏，
     * 而前者其实只需要等一个重评周期就能活。
     */
    private static boolean disabled;

    /** 停用后的重评间隔。20 秒：够短，用户在文件管理器里放好文件回来就能看到；够长，不刷日志。 */
    private static final long RESUME_INTERVAL_NANOS = 20L * 1_000_000_000L;

    /** 下一次允许重评的时刻（{@link System#nanoTime()} 轴）。 */
    private static long resumeAtNanos;

    /** 已经重评过多少次，只用来限制日志条数。 */
    private static int resumeAttempts;

    /** 最后一次停用的原因，给原版 GUI 的提示层直接读。 */
    private static String suspendReason;

    // ---- 离屏目标（Skia 自己拥有，含按需分配的 stencil）----
    private static Surface offscreen;
    private static int offscreenWidth;
    private static int offscreenHeight;

    // ---- 自持离屏目标（Android GL 翻译层上 canvas.clear() 会整帧丢失，见 beginOffscreen）----
    private static int ownedFbo;
    private static int ownedTexture;
    private static int ownedDepthStencil;
    /** true = {@link #offscreen} 包装的是上面自持的 FBO；false = Skia 自管目标（退化路径）。 */
    private static boolean offscreenOwned;

    /** AB 开关：-Dmuonium.skija.legacyOffscreen=true 回到 Skia 自管离屏的旧行为。 */
    private static final boolean LEGACY_OFFSCREEN =
            Boolean.getBoolean("muonium.skija.legacyOffscreen");

    /**
     * 是否用「自持 FBO + 裸 GL 清屏」承载离屏层。
     *
     * <p>Android 的 GL 翻译层（FCL 的 Zink/gl4es 一类）上，{@code canvas.clear()} 被优化成
     * 的 clear op 可能整帧丢失——离屏层于是带着上一帧的残影合成上屏，表现为歌词滚动拖尾、
     * HUD 编辑器残影、换句瞬间闪一下（2026-10-07 真机截图实证，Windows 无此症状）。
     * 桌面 Windows 驱动没有这个问题，保持 Skia 自管目标的历史路径不动；其余平台走自持
     * FBO，清屏不再依赖 Skia 的 clear 优化与驱动 clear 实现的咬合。
     */
    private static boolean ownedOffscreenDecided;
    private static boolean ownedOffscreenDecision;

    private static boolean useOwnedOffscreen() {
        if (LEGACY_OFFSCREEN) {
            return false;
        }
        // 判定结果钉住：mappedPlatform 内部有文件 stat，beginFrame 每帧都来这里，
        // 按本库惯例不做每帧 stat。
        if (!ownedOffscreenDecided) {
            ownedOffscreenDecision = !SkijaNatives.mappedPlatform().startsWith("windows");
            ownedOffscreenDecided = true;
        }
        return ownedOffscreenDecision;
    }

    // ---- 帧内状态 ----
    private static boolean inFrame;
    private static Canvas canvas;
    private static int savedFbo;
    private static final int[] SAVED_VIEWPORT = new int[4];
    private static int frameGuiWidth;
    private static int frameGuiHeight;
    /** 帧缓冲像素 / GUI 单位。等价于 Minecraft 的 guiScale，但不需要业务层碰 MC 类。 */
    private static double frameGuiScale = 1.0;

    /**
     * HUD 与 Screen 的即时回调在 1.21.1 可能在同一显示帧内各进一次。每个 beginFrame 都
     * 采一次的话，后进来的那个采到的就是「本帧已经即时合成上去的我们自己的 HUD」，
     * 透镜一放大就是整面板的拉丝，时有时无就是闪烁。
     *
     * <p>所以采集按<b>显示帧</b>闸：用 {@code getFrameTime()}（partialTick）当帧号——
     * 同一显示帧内它不变，第二次 beginFrame 一看帧号相同就跳过采集，沿用本帧开头
     * 采到的那张干净背景。暂停时 partialTick 冻结，背景跟着冻结也正合适（单机暂停
     * 场景本来就是静止的）。1.20.1 同一份修法，见那棵的同名字段。
     */
    private static float lastImmediateBackdropFrameTime = Float.NaN;

    private SkijaHost() {
    }

    /** 渲染层是否可用。false 时上层应当整帧跳过绘制，而不是画一半。 */
    public static boolean isAvailable() {
        return !disabled;
    }

    /**
     * 开一帧。成功返回一块已经设置好 GUI 坐标系的 {@link Canvas}，失败返回 null。
     *
     * <p>返回的 canvas 已经：清空为全透明、按 GUI 缩放因子做过 scale、
     * 压了一层 {@code save()}（{@link #endFrame()} 会配对 restore，
     * 因此上层即使 push/pop 不配对也不会污染下一帧）。
     */
    public static Canvas beginFrame() {
        if (inFrame) {
            return null;
        }
        if (disabled) {
            maybeResume();
            if (disabled) {
                return null;
            }
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

            int guiWidth = window.getGuiScaledWidth();
            int guiHeight = window.getGuiScaledHeight();
            int pixelWidth = window.getWidth();
            int pixelHeight = window.getHeight();
            if (guiWidth <= 0 || guiHeight <= 0 || pixelWidth <= 0 || pixelHeight <= 0) {
                return null;
            }

            if (!SkijaNatives.ensureLoaded()) {
                fail("Skija 原生库不可用: " + SkijaNatives.failure(), null);
                return null;
            }
            if (context == null) {
                try {
                    context = DirectContext.makeGL();
                } catch (Throwable makeGlThrew) {
                    // Skija 在这里不是返回 null，而是拿 nullptr 造对象时抛「Can't wrap nullptr」
                    // ——真机第一条就是这个形状。单独兜一句，日志里就有明确的「上下文装不出来」字样，
                    // 不用从 beginFrame 那一串堆栈里反推。
                    fail("DirectContext.makeGL() 抛异常（Skia 装配不出当前 GL 上下文）: "
                            + makeGlThrew, makeGlThrew);
                    return null;
                }
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
            // 【版本差异】这两棵是即时合成，没有帧末合成钩子：Minecraft 要到帧末才把主渲染目标 blit 到窗口默认帧缓冲，
            // 所以此刻唯一干净的背景就是它当前绑定的主目标（savedFbo）。放在 Skia 绑自己的离屏目标之前，裸 GL 不会踩到 Skia 的状态缓存。
            // 帧号闸：同一显示帧内只有第一个 beginFrame 采集，见 lastImmediateBackdropFrameTime 的注释。
            // 1.21.1 的计时器换成了 DeltaTracker（没有 1.20.1 的 getFrameTime），取当帧 partialTick 当帧号。
            float frameTime = client.getTimer().getGameTimeDeltaPartialTick(true);
            if (frameTime != lastImmediateBackdropFrameTime) {
                // This is the underlay captured before the current Skia Screen content. The post-render
                // hook replaces it with the completed topmost Screen image for the island lens.
                GlassBackdrop.captureNow(savedFbo, pixelWidth, pixelHeight, false);
                lastImmediateBackdropFrameTime = frameTime;
            }
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
        boolean wantOwned = useOwnedOffscreen();
        if (offscreen == null || offscreenWidth != pixelWidth || offscreenHeight != pixelHeight
                || wantOwned != offscreenOwned) {
            closeOffscreen();
            if (wantOwned) {
                offscreen = wrapOwnedOffscreen(pixelWidth, pixelHeight);
            }
            if (offscreen == null) {
                // budgeted=false：这块目标生命周期由我们自己控制，不希望 Skia 的资源缓存
                // 在内存压力下把它回收掉。
                offscreen = Surface.makeRenderTarget(context, false,
                        ImageInfo.makeN32Premul(pixelWidth, pixelHeight));
                if (offscreen == null) {
                    fail("Surface.makeRenderTarget 返回 null（" + pixelWidth + "x" + pixelHeight + "）", null);
                    return null;
                }
                offscreenOwned = false;
                log("离屏目标（Skia 自管）已建立 " + pixelWidth + "x" + pixelHeight);
            }
            offscreenWidth = pixelWidth;
            offscreenHeight = pixelHeight;
        }
        Canvas c = offscreen.getCanvas();
        // 必须清成全透明。清成不透明黑会把整个屏幕糊掉。
        if (offscreenOwned) {
            clearOwnedOffscreenRaw();
        }
        c.clear(0x00000000);
        return c;
    }

    /**
     * 建一块<b>我们自己持有</b>的离屏 FBO（纹理 + 深度模板渲染缓冲）并包成 Skia Surface。
     *
     * <p>与 {@link Surface#makeRenderTarget} 的差别只有一个，却是这次修复的全部：
     * FBO 名在我们手里，beginFrame 可以在 Skia 排队之前用<b>裸 GL</b> 把它清成全透明，
     * 不再依赖 Skia 的 clear 路径在手机 GL 栈上是否生效。任何一步失败都返回
     * {@code null}，调用方退回 Skia 自管目标，功能不丢。
     *
     * <p>Origin 声明 BOTTOM_LEFT 与 {@link #composite} 里包装 MC FBO 的口径一致：
     * GL 纹理 FBO 的原点本来就是左下，两个同源目标之间 drawImage 逐纹素 1:1，没有额外翻转；
     * 画布坐标系仍然是上层依赖的「GUI 单位、左上原点、y 向下」，对业务层零变化。
     */
    private static Surface wrapOwnedOffscreen(int pixelWidth, int pixelHeight) {
        try {
            ownedTexture = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, ownedTexture);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA8, pixelWidth, pixelHeight, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);

            ownedDepthStencil = org.lwjgl.opengl.GL30.glGenRenderbuffers();
            org.lwjgl.opengl.GL30.glBindRenderbuffer(org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                    ownedDepthStencil);
            org.lwjgl.opengl.GL30.glRenderbufferStorage(org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                    org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8, pixelWidth, pixelHeight);

            ownedFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, ownedFbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    ownedTexture, 0);
            org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                    org.lwjgl.opengl.GL30.GL_RENDERBUFFER, ownedDepthStencil);
            boolean complete = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER)
                    == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
            org.lwjgl.opengl.GL30.glBindRenderbuffer(org.lwjgl.opengl.GL30.GL_RENDERBUFFER, 0);
            if (!complete) {
                destroyOwnedOffscreenGl();
                return null;
            }
            BackendRenderTarget target = BackendRenderTarget.makeGL(pixelWidth, pixelHeight,
                    0, 8, ownedFbo, FramebufferFormat.GR_GL_RGBA8);
            Surface s = Surface.wrapBackendRenderTarget(context, target, SurfaceOrigin.BOTTOM_LEFT,
                    ColorType.RGBA_8888, ColorSpace.getSRGB());
            if (s == null) {
                target.close();
                destroyOwnedOffscreenGl();
                return null;
            }
            offscreenOwned = true;
            log("离屏目标（自持 FBO + 裸 GL 清屏）已建立 " + pixelWidth + "x" + pixelHeight);
            return s;
        } catch (Throwable t) {
            destroyOwnedOffscreenGl();
            return null;
        }
    }

    /**
     * 裸 GL 清屏：绑定自己的 FBO，显式关 scissor、开满 colorMask，把颜色位清成全透明。
     *
     * <p>不再经 {@code canvas.clear()} 那条链：它会被 Skia 优化成 clear op，而 clear op
     * 依赖 Skia 对 GL scissor/colorMask 的缓存与驱动的 clear 实现咬合——真机上这一咬合
     * 会整帧失效，离屏层于是带着上一帧的残影合成上屏。这里每一项状态都显式设置；
     * clearColor 不在 {@code GlSnapshot} 的快照范围内，清完必须还回去，
     * 否则 0,0,0,0 会泄漏给 Minecraft 自己的下一次清屏。
     */
    private static void clearOwnedOffscreenRaw() {
        float[] previousClearColor = new float[4];
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_COLOR_CLEAR_VALUE,
                previousClearColor);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, ownedFbo);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
        org.lwjgl.opengl.GL11.glClearColor(0f, 0f, 0f, 0f);
        org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
        org.lwjgl.opengl.GL11.glClearColor(previousClearColor[0], previousClearColor[1],
                previousClearColor[2], previousClearColor[3]);
    }

    /** 删掉自持离屏的 GL 对象。必须在渲染线程、GL 上下文仍 current 的时候调。 */
    private static void destroyOwnedOffscreenGl() {
        if (ownedFbo != 0) {
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(ownedFbo);
            ownedFbo = 0;
        }
        if (ownedDepthStencil != 0) {
            org.lwjgl.opengl.GL30.glDeleteRenderbuffers(ownedDepthStencil);
            ownedDepthStencil = 0;
        }
        if (ownedTexture != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(ownedTexture);
            ownedTexture = 0;
        }
        offscreenOwned = false;
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

    /** 结束一帧：把离屏内容合成回 Minecraft 的画面，并把 GL 状态交还给 Minecraft。 */
    public static void endFrame() {
        if (!inFrame) {
            return;
        }
        inFrame = false;
        try {
            // 业务代码可能压了变换却没弹（异常路径、或者 push/pop 数量写错）。
            // 只 restore 一层的话，本帧的坐标系会残留到下一帧。这里先把上层压的层数收干净。
            // 融合组必须在调用点自己那套矩阵下收尾（见 GlassEdgeBatch 的类注释）。
            // 到这一行还开着的组已经没有正确的坐标系可用，只能丢掉并说一声。
            GlassEdgeBatch.abandonIfOpen();
            SkijaGfx.restoreDepthTo(0);
            SkijaGfx.endFrame();
            SkijaClip.resetFrame();
            canvas.restore();
            if (DEBUG_DRAW_DIRECT) {
                context.flushAndSubmit(directSurface);
            } else {
                composite();
            }
        } catch (Throwable t) {
            fail("endFrame 失败", t);
        } finally {
            canvas = null;
            closeDirect();
            restoreMinecraftState();
        }
    }

    /** 把离屏纹理作为一张图片画到 Minecraft 当前的 FBO 上。 */
    private static void composite() {
        context.flush(offscreen);
        // makeImageSnapshot 在 GPU 目标上通常只是加一次纹理引用，不做像素拷贝。
        try (Image snapshot = offscreen.makeImageSnapshot();
             BackendRenderTarget mcTarget = BackendRenderTarget.makeGL(
                     offscreenWidth, offscreenHeight, 0, 0, savedFbo,
                     FramebufferFormat.GR_GL_RGBA8);
             Surface mcSurface = Surface.wrapBackendRenderTarget(context, mcTarget,
                     // BOTTOM_LEFT 告诉 Skia 这个 FBO 是 GL 的左下原点，
                     // 于是它内部翻转，canvas 的 (0,0) 在视觉上仍是左上角。
                     SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB())) {
            if (snapshot == null || mcSurface == null) {
                return;
            }
            // 离屏是 TOP_LEFT 原点的 Skia 自有目标，主 FBO 是 BOTTOM_LEFT，
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
     * <h2>纹理单元为什么走 GlStateManager 而不是裸 GL</h2>
     * 纹理上传链路（{@code compat/gl/GL11}、{@code TextureUtil}）会在 Skia 帧内部通过
     * {@code GlStateManager._bindTexture} 换绑纹理，这会更新缓存。如果还原时只改真实 GL、
     * 不动缓存，就会反过来制造出「缓存说绑着 A、真实绑着 B」的新分歧。
     * 所以这一项用 {@code GlStateManager} 还原：缓存与真实状态一起回到快照值。
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

            // 纹理单元：这一项刻意走 GlStateManager，让它的缓存与真实状态一起回到快照值。
            for (int unit = 0; unit < UNITS; unit++) {
                GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
                writeSampler(unit, boundSamplers[unit]);
                GlStateManager._bindTexture(boundTextures[unit]);
            }
            GlStateManager._activeTexture(activeTexture);

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

            // Minecraft 的 BufferUploader 单独缓存「当前绑定的 VertexBuffer」，这份缓存不经过
            // GlStateManager。快照已经把 VAO 绑回去了，理论上它的缓存仍然有效；但让它失效只是
            // 让下一次绘制多做一次 bind，代价可以忽略，而漏掉它的后果是驱动直接报
            // "GL_INVALID_OPERATION: Array object is not active"（首次实测复现过 12 条）。
            com.mojang.blaze3d.vertex.BufferUploader.reset();
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

    /** Refresh glass from the already rendered Screen before drawing the global island overlay. */
    public static void captureTopmostBackdrop() {
        if (!inFrame || disabled || savedFbo < 0) return;
        try {
            Minecraft client = Minecraft.getInstance();
            Window window = client == null ? null : client.getWindow();
            if (client == null || client.screen == null || window == null) return;
            GlassBackdrop.captureTopmostNow(savedFbo, window.getWidth(), window.getHeight());
            if (context != null) context.resetAll();
        } catch (Throwable ignored) {
            // Missing capture only disables this visual enhancement; keep the island itself alive.
        }
    }

    /** Immediate hosts already render the island after Screen#render. */
    public static boolean deferPostCompositeOverlay(Runnable overlay) {
        return false;
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
        GlassLiquidSurface.release();
        GlassRefraction.release();
        GlassBackdrop.invalidate();
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
        destroyOwnedOffscreenGl();
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

    /**
     * 到点就把停用结论撤掉，让下一帧重新走一遍初始化。
     *
     * <p>必须同时叫 {@link SkijaNatives#reset()}：那边的 {@code loaded} 缓存成 false 之后
     * 再也不会回头，只解这边这一层的闸，重试到的永远是「原生库不可用」这个旧结论。
     * 撤闸本身不做任何加载工作——真正的重活在下一次 {@link #beginFrame()} 里，
     * 那时 GL 上下文是当前的，失败也照样会被 {@link #fail} 重新按上退避。
     */
    private static void maybeResume() {
        long now = System.nanoTime();
        if (now < resumeAtNanos) {
            return;
        }
        resumeAttempts++;
        resumeAtNanos = now + RESUME_INTERVAL_NANOS;
        SkijaNatives.reset();
        disabled = false;
        // 前几次要说，之后每 10 次说一句：长期缺伴生包的机器上一秒 60 帧，全打会把日志淹掉
        if (resumeAttempts <= 3 || resumeAttempts % 10 == 0) {
            log("第 " + resumeAttempts + " 次重新评估渲染层（上次原因：" + suspendReason + "）");
        }
    }

    /** 渲染层当前是否处于停用状态（供原版 GUI 的提示层判断要不要说话）。 */
    public static boolean isSuspended() {
        return disabled;
    }

    /** 最后一次停用的原因；从未停用时为 null。 */
    public static String suspendReason() {
        return suspendReason;
    }

    /** 已经重评过多少次。 */
    public static int resumeAttempts() {
        return resumeAttempts;
    }

    /** 原生库的加载交代；提示层用它区分「缺伴生包」与「库有了但 GPU 上下文建不起来」。 */
    public static SkijaNatives.Status nativeStatus() {
        return SkijaNatives.status();
    }

    private static void fail(String message, Throwable t) {
        disabled = true;
        resumeAtNanos = System.nanoTime() + RESUME_INTERVAL_NANOS;
        suspendReason = message;
        log("已停用 Skia 渲染层: " + message);
        if (t != null) {
            t.printStackTrace();
        }
    }

    private static void log(String message) {
        System.out.println(TAG + message);
    }
}
