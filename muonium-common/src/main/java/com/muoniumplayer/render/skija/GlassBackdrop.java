package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * 背景取样：把<b>本帧游戏的最终画面</b>搞成一张 Skia 能采样的纹理，给玻璃材质当底。
 *
 * <h2>为什么非得自己拷一次</h2>
 * 本渲染层的 Skia 画在<b>自己的透明离屏</b>上，帧末才由 {@link SkijaHost#compositeFrame()} 盖回
 * 窗口默认帧缓冲（见 {@code GlSurfaceMixin} / {@code FramebufferMixin}）。因此
 * {@link SkijaEffects#backdropBlur} 那套 {@code saveLayer} 加 backdrop 只能糊到 Skia 自己
 * 已经画过的东西，<b>糊不到游戏画面</b>——桌面歌词那张毛玻璃卡片之所以成立，是因为要糊的内容
 * 本来就在同一个 Skia 图层里。想让玻璃面板透出的确实是「玩家此刻看到的画面」，只有一条路：
 * 把默认帧缓冲读出来，再作为一张图喂给 Skia。
 *
 * <h2>为什么采样点要挑在「我们的 HUD 还没上屏」那一刻</h2>
 * 延迟批处理的三棵树（1.21.11 / 26.2 / 26.3）上，那一刻就是帧末合成之前：窗口默认帧缓冲
 * （FBO 名 0）里正好是「世界 + 原版 GUI」的最终画面，而我们的 HUD 一个像素都还没盖上去。
 * 放到合成之后采，抄进去的背景就带上我们自己的界面，玻璃会变成「透自己」。即时合成的两棵
 * （1.20.1 / 1.21.1）没有这个时刻，改用 {@code beginFrame}，理由见 {@link #captureNow()}。
 *
 * <h2>为什么用 blit 而不是 glCopyTexImage2D</h2>
 * blit 是唯一能在 GPU 上<b>顺带缩小</b>的取法（读侧到写侧用 {@code GL_LINEAR}），而缩小本身就是
 * 一层廉价的预糊——玻璃底要的就是糊掉的画面，先降采样再糊比全分辨率糊便宜得多。
 * {@code glCopyTexImage2D} 只能 1:1。两者都是裸 GL 调用，五棵树通用，不碰 MC 的渲染 API，
 * 所以不随版本改名而变（对比 {@code ScreenCapture} 里那三条版本差异面）。
 *
 * <h2>没有读后写风险</h2>
 * 采集与 Skia 的绘制命令同属一个 GL 上下文、按提交顺序执行，而采集总是排在「本帧要用这张
 * 背景的绘制」之前（新树还额外排在上一次使用已经 flushAndSubmit 之后）。因此不需要双缓冲。
 *
 * <h2>坐标系</h2>
 * 默认帧缓冲是 GL 的左下原点，于是第 0 行纹理对应屏幕<b>底边</b>。包装成 Image 时用
 * {@link SurfaceOrigin#BOTTOM_LEFT} 让 Skia 内部翻转，上层继续按「(0,0) 在左上」用。
 * 注意这与 {@link SkijaImages#wrap} 相反——那边采的是 MC 用 {@code NativeImage} 上传的纹理，
 * 第 0 行就是顶边，所以那边用 {@code TOP_LEFT}。两处别抄错，抄错的表现是背景上下颠倒，
 * 而一块被糊住的背景恰恰最容易被当成「反正糊了，看不出反」。
 *
 * <h2>色彩编码</h2>
 * {@code -Dmuonium.glass.srgb=true} 把内部格式换成 {@code GL_SRGB8_ALPHA8}。默认关：
 * 现在这条链路（{@link SkijaHost} 合成）就是「按字节原样搬 + Skia 当 sRGB 读」，真机观感是对的；
 * 若某些版本的窗口默认帧缓冲带 sRGB 编码，blit 会做一次解码，画面就整体偏暗，
 * 那时把这个开关打开即可。留成开关是因为这事只能靠真机截图判，不值得赌。
 *
 * <h2>线程约束</h2>
 * 全部方法只能在渲染线程调用，且 GL 上下文为当前。
 */
public final class GlassBackdrop {

    private static final String TAG = "[MuoniumPlayer/GlassBackdrop] ";

    /**
     * 探针档：{@code -Dmuonium.debug.glass=true} 时每帧采集，并在界面上贴对比块
     * （见 {@code GlassBackdropProbe}）。正式档的开关已经接进 {@link #wanted()}
     * （主题强度 × 四个生效面），探针留着是为了「没选琉璃主题也能先看一眼采到的到底是什么」。
     */
    public static final boolean DEBUG_PROBE = Boolean.getBoolean("muonium.debug.glass");

    /** 探针档专用的采集倍率覆盖：正式档走 {@code HudConfig.glassBackdropScale}。 */
    private static final int PROBE_DOWNSCALE = Math.max(1, Integer.getInteger("muonium.glass.downscale", 1));

    /** 用 sRGB 内部格式采集（见类注释「色彩编码」）。 */
    private static final boolean SRGB_INTERNAL = Boolean.getBoolean("muonium.glass.srgb");

    /*
     * 这里曾经手写过三个 GL token，两个是错的（2026-09-30 真机查出）：
     * 把 {@code GL_READ_FRAMEBUFFER_BINDING} 写成 0x8CA6、把 {@code GL_DRAW_FRAMEBUFFER_BINDING}
     * 写成 0x8CA8 —— 正确值是 0x8CAA 与 0x8CA6。0x8CA8 其实是 {@code GL_READ_FRAMEBUFFER}
     * （一个 target，不是 pname），拿去问 glGetIntegerv 就是每次采集一句
     * {@code GL_INVALID_ENUM: Unknown parameter enum}，而它返回的是垃圾值，
     * 于是「把读/写帧缓冲绑回原样」这两句一直在还原错的东西。
     * 当时没被发现的原因很具体：查询之后紧跟着一句 {@code while (glGetError() != 0)} 清错误，
     * 把这条错误自己吃掉了，而 blit 之后的那次检查当然是干净的。
     * 结论：GL 常量一律用 LWJGL 的具名字段，编译期就能查出来，不要凭记忆写十六进制。
     */

    /** 采集目标。尺寸变了就整组重建。 */
    private static int textureId;
    private static int framebufferId;
    private static int textureWidth;
    private static int textureHeight;

    /**
     * 指向 {@link #textureId} 的 Skia 视图。
     *
     * <p>只建一次：Image 是<b>视图</b>而不是像素快照，纹理内容变了下次绘制自然就是新内容
     * （同 {@link SkijaImages} 的取舍）。每帧重建视图会让 Skia 反复丢弃并重绑外部纹理。
     */
    private static Image image;

    /**
     * 预糊那一层：最多 {@link GlassBlurLevels#SLOTS} 个槽，每个槽是「一张自己的纹理 + FBO +
     * 包出来的画布 + 借给 Skia 的视图」，按半径分档（一处一档模糊强度）。尺寸永远跟
     * {@link #textureWidth} 一致，所以映射与源矩形两头通用。
     *
     * <p>为什么不用 {@code makeImageSnapshot}：快照是<b>带生命周期的副本</b>，下一次绘制会触发
     * copy-on-write，而 {@code GlassRefraction} 把背景当子 shader 缓存着跨帧用——快照一旦被回收，
     * 缓存里的 shader 就指向一个死对象。借来的外部纹理没有这个问题：内容原地换，视图不动。
     * 同一句话对多槽版成立，只是「原地换」的范围从一张变成每档一张。</p>
     *
     * <p>哪一格命中、往哪一格重建、预算花完借谁，全在 {@link GlassBlurLevels} 里算，
     * 这里只负责持有 GL 对象。</p>
     */
    private static final BlurSlot[] blurSlots = new BlurSlot[GlassBlurLevels.SLOTS];
    private static final GlassBlurLevels blurLevels = new GlassBlurLevels();

    static {
        for (int i = 0; i < blurSlots.length; i++) {
            blurSlots[i] = new BlurSlot();
        }
    }

    /** 一个预糊槽位的裸资源。字段留成包内可见，好让策略与探针直接读，不做封装。 */
    static final class BlurSlot {
        int textureId;
        int framebufferId;
        BackendRenderTarget backend;
        Surface surface;
        Image image;
        int width;
        int height;
    }

    /** 预糊这一层建不起来（只影响开销，不影响有没有玻璃）。 */
    private static boolean blurBroken;

    /** 上一次采集失败过（不完整的目标或 GL 报错），置位后不再重试，玻璃退回无背景模式。 */
    private static boolean broken;
    /** 是否已经打过「首次采集成功」那行日志。 */
    private static boolean firstCaptureUnlogged;
    /** 映射不可用只说一次：这事每帧都成立，刷屏会把日志冲走。 */
    private static boolean mappingWarned;
    /** 采集过的帧数与上一次耗时，探针用来读数。 */
    private static long captures;
    private static long lastCaptureNanos;
    /** 上一次发起采集的时刻，见 {@link #captureNow()} 里的时间闸。 */
    private static long lastCaptureStart;
    /**
     * 每帧一档的时间闸：8ms（≈125fps 一帧），比这更快的那部分帧直接复用上一次采集。
     *
     * <p>两个消费者用它，而且它们<b>都不该看到面板自己</b>：探针档要判的就是「这一帧的画面
     * 进没进来」，压频率会把结论一起压掉；而设置里的「实时背景采集」（
     * {@code HudConfig.glassRealtimeCapture}）给的是「玻璃后面那一层跟着游戏一起动」——
     * 30Hz 的底在走位与转视角时会隔一两帧跳一下，那正是玩家说的「不稳」里能被眼睛抓住的
     * 一部分。这两笔开销都不在 blit 上（实测一次 0.4~1.1ms），而在它逼着预糊那一层每帧
     * 重做一遍全屏高斯（见 {@link #blurred(float)}，它按采集代数重做）。</p>
     */
    private static final long PER_FRAME_CAPTURE_INTERVAL_NANOS = 8_000_000L;
    /**
     * 正式档的时间闸：33ms，也就是背景最多 30Hz 更新。
     *
     * <p>真机第一次量下来一次采集是 0.41~1.07ms（1920x1009 → 960x504 的 blit），
     * 每帧都采等于白送掉百分之一到百分之六的帧预算，而静止画面换来的东西看不出来——这块画面
     * 出门就要被按 sigma 糊一遍。要看得出来的只有<em>动</em>的那部分，所以它现在是设置里
     * 一个手动档位，而不是默认（见 {@link #realtime()}）。</p>
     */
    private static final long CAPTURE_INTERVAL_NANOS = 33_000_000L;

    private GlassBackdrop() {
    }

    /**
     * 用户有没有要「实时背景采集」。
     *
     * <p>探针档也算实时：{@code GlassBackdropProbe} 判的「这一帧采到没采到」在 30Hz 下
     * 本来就测不出东西。</p>
     */
    public static boolean realtime() {
        return debugging() || com.muoniumplayer.core.settings.HudConfig.glassRealtimeCapture;
    }

    /**
     * 采集倍率。探针档读 JVM 属性（要能单独试 1/2/4 看画质与耗时的取舍），正式档读设置；
     * 设置里存的是 1~4 的整数，钳一下防着手改坏配置的人除出 0。
     */
    private static int downscale() {
        if (DEBUG_PROBE) {
            return PROBE_DOWNSCALE;
        }
        return Math.max(1, Math.min(4,
                com.muoniumplayer.core.settings.HudConfig.glassBackdropScale));
    }

    /**
     * 本帧该不该采。判据从严到宽三层：
     *
     * <ol>
     *   <li>开发探针：JVM 属性 {@code -Dmuonium.debug.glass=true}，或存在
     *       {@code config/muonium/dev_glass_probe} 文件（IDE 的 runClient 塞 JVM 参数不方便）。
     *       探针档永远采，这样主题没选琉璃也能先看一眼采到的到底是什么。</li>
     *   <li>主题的<b>真玻璃强度</b>为 0（{@code NCMTheme.getTrueGlassAmount()}）就整条链路不启动——
     *       默认那十四套主题一个像素都不多拷。读的是「动画中的强度」而不是「当前是不是琉璃」：
     *       从琉璃切走时玻璃要淡出 0.95 秒，那一刻强度还没到 0，得继续采，
     *       否则淡出过程中铺着一帧过期画面，看着像卡了一层脏玻璃。</li>
     *   <li>主题开着但四个生效面全关，同样不采。</li>
     * </ol>
     *
     * <p>文件那次问得慢（一秒一次）而不是每帧 stat：这个调用点每帧都跑。</p>
     */
    public static boolean wanted() {
        if (debugging()) {
            return true;
        }
        if (com.muoniumplayer.core.screens.ncm.NCMTheme.getTrueGlassAmount() <= 0.001f) {
            return false;
        }
        return com.muoniumplayer.core.settings.HudConfig.glassSurfacePlayer
                || com.muoniumplayer.core.settings.HudConfig.glassSurfaceIsland
                || com.muoniumplayer.core.settings.HudConfig.glassSurfaceDesktopLyric
                || com.muoniumplayer.core.settings.HudConfig.glassSurfaceEditors;
    }

    /**
     * 探针档到底开没开——<b>只</b>问那两个开关，不含主题判据。
     *
     * <p>这个方法是 {@code GlassBackdropProbe} 的入口闸，和 {@link #wanted()} 必须分开：
     * 后者现在也包含「主题选了琉璃」，探针若继续读它，玩家一开真玻璃就会在屏幕左下角
     * 长出两块对比块。2026-09-29 真机第一次跑 P1 就踩了这个：删掉 {@code dev_glass_probe}
     * 文件后探针块还在，因为让采集成立的原因（琉璃主题）同时也让探针成立了。</p>
     */
    public static boolean debugging() {
        return DEBUG_PROBE || probeFilePresent();
    }

    private static boolean probeFilePresent() {
        long now = System.nanoTime();
        if (now - probeFlagCheckedAt >= PROBE_FLAG_INTERVAL_NANOS) {
            probeFlagCheckedAt = now;
            try {
                probeFlagOn = com.muoniumplayer.core.settings.ConfigPaths.DEV_GLASS_PROBE.isFile();
            } catch (Throwable ignored) {
                probeFlagOn = false;
            }
        }
        return probeFlagOn;
    }

    private static long probeFlagCheckedAt;
    private static boolean probeFlagOn;
    private static final long PROBE_FLAG_INTERVAL_NANOS = 1_000_000_000L;

    /**
     * 采一次当前的干净背景。调用点按树分：
     *
     * <ul>
     *   <li><b>1.21.11 / 26.2 / 26.3</b>：{@link SkijaHost#compositeFrame()} 开头。那三棵的 GUI
     *       是延迟批处理，我们的 HUD 统一攒到帧末才盖，所以那一刻默认帧缓冲正好是
     *       「本帧游戏最终画面 + 还不含我们的 HUD」。</li>
     *   <li><b>1.20.1 / 1.21.1</b>：{@link SkijaHost#beginFrame()} 建好 canvas 之后。
     *       那两棵每次 {@code endFrame} 就即时合成，帧末已经没有「不含我们 HUD」的时刻了；
     *       而 {@code beginFrame} 落在原版 HUD 之后（HUD 回调）或界面背景之后（GuiScreen），
     *       主帧缓冲里正好只剩游戏画面。一帧里可能出现多对 begin/end，靠下面那道时间闸
     *       保证一帧最多采一次——第二次采到就会把我们自己的界面抄进背景。</li>
     * </ul>
     *
     * <p>任何一步不对都只置 {@link #broken} 并退回不采集：玻璃材质拿不到背景时应当退化成
     * 现在这套渐变高光，而不是把整个 HUD 层带走。
     *
     * @param sourceFramebuffer 干净背景所在帧缓冲的 GL 名。它<b>按树不同</b>，所以由调用点给：
     *        延迟批处理的三棵传 {@code 0}（帧末时最终画面已经 blit 到窗口默认帧缓冲），
     *        即时合成的两棵传 Minecraft 当前绑定的主渲染目标（那两棵要到帧末才 blit，
     *        HUD 回调时窗口默认帧缓冲里还是上一帧的东西）。
     * @param sourceWidth 该帧缓冲的像素宽。由调用点给而不是自己去问窗口——
     *        {@code RenderLayerStaticTest} 钉着「渲染层只允许 SkijaHost 认识 Minecraft」，
     *        而 {@code SkijaHost} 本来就已经把这两个数算好了。
     * @param sourceHeight 该帧缓冲的像素高。
     */
    public static void captureNow(int sourceFramebuffer, int sourceWidth, int sourceHeight) {
        // 这一层已经停用，或 Skia 上下文还没装出来（beginFrame 一次都没跑过），那就是没有消费者。
        if (broken || !wanted() || SkijaHost.isDisabled() || SkijaHost.context() == null) {
            return;
        }
        long startedAt = System.nanoTime();
        // 时间闸有两个作用：给 1.20.1/1.21.1 那种「一帧里多次 beginFrame」的树兜底，以及把
        // 正式档压在 30Hz（实测一次 blit 0.4~1.1ms，而这块画面出门还要整个糊一遍）。
        // 用户手动开了「实时背景采集」就每帧采——他要的正是玻璃背后那一层跟着画面走。
        long interval = realtime() ? PER_FRAME_CAPTURE_INTERVAL_NANOS : CAPTURE_INTERVAL_NANOS;
        if (startedAt - lastCaptureStart < interval) {
            return;
        }
        lastCaptureStart = startedAt;
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        int targetWidth = Math.max(1, sourceWidth / downscale());
        int targetHeight = Math.max(1, sourceHeight / downscale());
        if (!ensureTarget(targetWidth, targetHeight)) {
            return;
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousDrawBuffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        java.nio.ByteBuffer maskBuffer = java.nio.ByteBuffer.allocateDirect(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, maskBuffer);
        boolean[] previousColorMask = {maskBuffer.get(0) != 0, maskBuffer.get(1) != 0,
                maskBuffer.get(2) != 0, maskBuffer.get(3) != 0};
        float[] previousClearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previousClearColor);
        boolean previousScissor = GL11.glGetBoolean(GL11.GL_SCISSOR_TEST);
        // 先把自己欠下的错误清干净：glGetError 是「取走并清零」，别处的残留会被下面的检查
        // 当成 blit 失败，症状是日志里来一句「停用背景采集」而画面其实好着。
        while (GL11.glGetError() != 0) {
            // 丢弃
        }
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            // Minecraft changes the default framebuffer's read buffer during the live world
            // pipeline. If we inherit GL_NONE / a stale attachment, the blit succeeds but copies
            // black. Pause/menu rendering happens to leave GL_BACK selected, which is why this bug
            // only appears while the world is running. Select the correct buffer explicitly.
            GL11.glReadBuffer(sourceFramebuffer == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebufferId);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0, 0, sourceWidth, sourceHeight,
                    0, 0, targetWidth, targetHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            forceOpaqueAlpha();
        } finally {
            // 绑定必须原样还回去：合成随后要把写侧绑到窗口默认帧缓冲，
            // 残留成我们的目标就会把 HUD 贴进背景纹理里，症状是「玻璃越来越亮地糊住自己」。
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL11.glReadBuffer(previousReadBuffer);
            GL11.glDrawBuffer(previousDrawBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL11.glColorMask(previousColorMask[0], previousColorMask[1],
                    previousColorMask[2], previousColorMask[3]);
            GL11.glClearColor(previousClearColor[0], previousClearColor[1],
                    previousClearColor[2], previousClearColor[3]);
            if (previousScissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }

        int error = GL11.glGetError();
        if (error != 0) {
            broken = true;
            System.out.println(TAG + "blit 报 GL 错误 0x" + Integer.toHexString(error)
                    + "，已停用背景采集");
            return;
        }

        captures++;
        lastCaptureNanos = System.nanoTime() - startedAt;
        if (!firstCaptureUnlogged) {
            firstCaptureUnlogged = true;
            System.out.println(TAG + "首次背景采集完成 " + sourceWidth + "x" + sourceHeight
                    + " → " + targetWidth + "x" + targetHeight
                    + "（1/" + downscale() + "，" + (SRGB_INTERNAL ? "sRGB" : "RGBA8") + "）");
        }
    }

    /**
     * 把刚 blit 进来的这一帧的 alpha 通道强制刷成 1（RGB 原样保留）。
     *
     * <h2>为什么必须这么做</h2>
     * 1.21.11 的世界渲染往帧缓冲里写的 alpha 不是 1：天空这类纯背景像素 alpha=0，
     * 只有几何才是 1（2026-10-02 实测：岛后面那块天 = 160,194,255,<b>a0</b>）。这张纹理按
     * UNPREMUL 借给 Skia 之后，采样会先做预乘——a=0 的像素无论 RGB 多亮，到透镜
     * 着色器手里都是 (0,0,0,0)，折射出来的就是一块黑。暂停/编辑器预览之所以一直正常，
     * 是原版菜单那层压暗把整屏 alpha 抬回了 1；26.2/26.3 的世界管线写 alpha 1，
     * 所以那两棵从来见不到这个症状。把 alpha 刷成 1 对本来就不透明的树是无操作。
     *
     * <p>用 colorMask 只开 alpha 位清屏：RGB 已经在图里，再走一遍全屏 blit 就白花了。
     * 调用点处于 DRAW 绑定还是本类目标的时刻；colorMask/clearColor/scissor 由
     * {@link #captureNow(int, int, int)} 的 finally 统一还回去。</p>
     */
    private static void forceOpaqueAlpha() {
        // glClear 受 scissor 裁剪：MC 游戏内常留着 scissor，不关会把整张图刷漏。
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(false, false, false, true);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    /**
     * 本帧可用的背景图；还没采到或已停用时返回 null。
     *
     * <p>返回的对象由本类持有，<b>调用方不要 close</b>。
     */
    public static Image image() {
        return broken ? null : wrapIntoSkia();
    }

    /**
     * <b>预糊</b>过的那张背景：与 {@link #image()} 同尺寸，只是整张已经按 {@code sigma} 高斯过一遍。
     *
     * <h2>为什么要多这一张</h2>
     * 一块玻璃要糊，靠的是绘制时给 paint 挂 {@code ImageFilter.makeBlur}，而 Skia 会为此
     * 单独开一层（saveLayer + 两趟可分离高斯）。整块面板一层时无所谓，<b>逐元素</b>就完全不同：
     * 一屏十行曲目、二十颗按钮、再加二级菜单里那几片，每帧就是三十多次开层。所以糊这件事
     * 挪到「每次采集之后做一次」，元素只负责贴。默认采集压在 30Hz
     * （{@link #CAPTURE_INTERVAL_NANOS}），于是这块糊的成本与帧率脱钩，只与刷新率有关。
     *
     * <h2>实时采集时这里就是那笔大头</h2>
     * 设置里开了「实时背景采集」以后 {@link #captures()} 每帧都在走，这一层也就<b>每帧</b>
     * 重糊一遍全屏。面板 blit 只要 0.4~1.1ms，而这里是 960x504 的两趟可分离高斯——真正
     * 让实时档需要一台好电脑的正是这一句，不是采集本身。
     *
     * <h2>失败就退回原路</h2>
     * 返回 {@code null} 时调用方（{@code SkijaEffects.frostedBackdrop}）继续走它原来那套
     * 「自己挂 blur 滤镜」的画法。<b>预糊是优化，不是依赖</b>：建不起来的那台机器上玻璃照样在，
     * 只是回到逐面板糊的开销。
     *
     * @param sigma 高斯半径（GUI 单位，来自设置里的「背景模糊强度」）
     */
    public static Image blurred(float sigma) {
        Image raw = image();
        if (raw == null || blurBroken || sigma <= 0.004f) {
            return null;
        }
        // 半高斯半径一档就够：滑块是连续的，按整数分档会让「拖滑块的过程中」每帧重建一次。
        float key = Math.round(sigma * 2f);
        blurLevels.beginCapture(captures);
        int hit = blurLevels.find(key, captures);
        if (hit >= 0) {
            blurLevels.touch(hit);
            return borrowBlur(SkijaHost.context(), blurSlots[hit]);
        }
        // 预算花完就不糊新一档了，借本代数里已经糊好的那一档：差半档半径看不出来，
        // 多一遍全屏高斯看得出来（实时档下每一档都是每付一次的钱）。
        if (blurLevels.overBudget()) {
            int borrowed = blurLevels.nearest(key, captures);
            if (borrowed >= 0) {
                blurLevels.touch(borrowed);
                return borrowBlur(SkijaHost.context(), blurSlots[borrowed]);
            }
            // 一档都没建过（第一次请求就撞上预算为 0 的边界）→ 往下照常建，绝不能返回 null，
            // 那等于让玻璃整层消失。
        }
        return rebuildBlur(raw, sigma, key, blurLevels.allocate(key, captures));
    }

    /** 建（或重建）第 {@code slot} 档预糊。任何一步不对都只置 {@link #blurBroken}，不影响没预糊时的旧画法。 */
    private static Image rebuildBlur(Image raw, float sigma, float key, int slot) {
        DirectContext context = SkijaHost.context();
        if (context == null || textureWidth <= 0 || textureHeight <= 0 || slot < 0) {
            return null;
        }
        try {
            if (!ensureBlurTarget(blurSlots[slot])) {
                return null;
            }
            try (ImageFilter blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
                 Paint paint = new Paint().setImageFilter(blur)) {
                Canvas canvas = blurSlots[slot].surface.getCanvas();
                // CLAMP 的糊是在图内做的，整张图铺满画一次就是完整结果，不需要先 clear。
                canvas.drawImage(raw, 0f, 0f, paint);
            }
            context.flush(blurSlots[slot].surface);
            blurLevels.markBuilt(slot, key, captures);
            return borrowBlur(context, blurSlots[slot]);
        } catch (Throwable failure) {
            blurBroken = true;
            releaseAllBlurTargets();
            System.out.println(TAG + "预糊背景建不出来，玻璃退回逐面板自己糊（功能不受影响）: " + failure);
            return null;
        }
    }

    /**
     * 第 {@code slot} 档预糊目标：一张同尺寸纹理 + 自己的 FBO，包成一块 Skia 画布。
     *
     * <p>尺寸跟着采集目标走，所以倍率与窗口一改就要整组重建。纹理所有权仍在 GL 这边
     * （{@code borrow} 而不是 {@code adopt}），Skia 只借看，不会替我们删。</p>
     */
    private static boolean ensureBlurTarget(BlurSlot target) {
        if (target.surface != null && target.width == textureWidth && target.height == textureHeight) {
            return true;
        }
        releaseBlurTarget(target);
        target.textureId = GL11.glGenTextures();
        target.framebufferId = GL30.glGenFramebuffers();
        if (target.textureId == 0 || target.framebufferId == 0) {
            blurBroken = true;
            releaseBlurTarget(target);
            System.out.println(TAG + "预糊目标的 GL 对象建不出来，玻璃退回逐面板自己糊");
            return false;
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat(),
                    textureWidth, textureHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebufferId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, target.textureId, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                blurBroken = true;
                releaseBlurTarget(target);
                System.out.println(TAG + "预糊目标不完整，玻璃退回逐面板自己糊");
                return false;
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        DirectContext context = SkijaHost.context();
        target.backend = BackendRenderTarget.makeGL(textureWidth, textureHeight, 0, 0,
                target.framebufferId, FramebufferFormat.GR_GL_RGBA8);
        target.surface = Surface.wrapBackendRenderTarget(context, target.backend,
                // 与采集目标同一个原点约定：这块 FBO 是 Skia 画出来的，而它写的那张纹理
                // 之后要按 BOTTOM_LEFT 借给 Skia 读（见 wrapIntoSkia），两头必须同一套朝向。
                SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB());
        if (target.surface == null) {
            blurBroken = true;
            releaseBlurTarget(target);
            System.out.println(TAG + "预糊目标包不成 Skia 画布，玻璃退回逐面板自己糊");
            return false;
        }
        target.width = textureWidth;
        target.height = textureHeight;
        return true;
    }

    /** 预糊纹理借给 Skia 的视图：只在尺寸变化后重建，理由同 {@link #wrapIntoSkia()}。 */
    private static Image borrowBlur(DirectContext context, BlurSlot target) {
        if (target.image != null || target.textureId == 0 || context == null) {
            return target.image;
        }
        BackendTexture backend = BackendTexture.makeGL(target.width, target.height, false,
                new GLTextureInfo(GL11.GL_TEXTURE_2D, target.textureId, internalFormat()));
        target.image = Image.borrowTextureFrom(context, backend, SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
        if (target.image == null) {
            blurBroken = true;
        }
        return target.image;
    }

    private static void releaseBlurTarget(BlurSlot target) {
        if (target.image != null) {
            target.image.close();
            target.image = null;
        }
        if (target.surface != null) {
            target.surface.close();
            target.surface = null;
        }
        if (target.backend != null) {
            target.backend.close();
            target.backend = null;
        }
        if (target.framebufferId != 0) {
            GL30.glDeleteFramebuffers(target.framebufferId);
            target.framebufferId = 0;
        }
        if (target.textureId != 0) {
            GL11.glDeleteTextures(target.textureId);
            target.textureId = 0;
        }
        target.width = 0;
        target.height = 0;
    }

    /** 所有会占一张纹理的层：四档预糊 + bloom 那一层。回收与作废都走这一份清单。 */
    private static BlurSlot[] blurTargets() {
        BlurSlot[] all = new BlurSlot[blurSlots.length + 1];
        System.arraycopy(blurSlots, 0, all, 0, blurSlots.length);
        all[blurSlots.length] = bloomSlot;
        return all;
    }

    /** 全丢：预糊这一层整体作废时（停用、上下文没了、建不起来）没有理由留任何一档。 */
    private static void releaseAllBlurTargets() {
        for (BlurSlot slot : blurTargets()) {
            releaseBlurTarget(slot);
        }
        bloomBuiltAtCapture = -1L;
        blurLevels.reset();
    }

    // ------------------------------------------------------------------
    // bloom：亮部溢出
    // ------------------------------------------------------------------

    /**
     * bloom 那张图（亮部提取 + 糊过），与采集目标同尺寸，所以 {@code frostedBackdrop} 那边
     * 算好的源矩形原样可用。
     */
    private static final BlurSlot bloomSlot = new BlurSlot();
    private static long bloomBuiltAtCapture = -1L;
    /** bloom 这一层建不起来：只少一股亮晕，玻璃本身一点不变。 */
    private static boolean bloomBroken;
    private static RuntimeEffect bloomEffect;
    private static boolean bloomEffectUnavailable;
    /** 采下来的原图当子 shader 用：与 {@code GlassRefraction} 同一套「认对象不认句柄」的缓存。 */
    private static Shader bloomChild;
    private static Image bloomChildImage;

    /** 亮度阈值（sRGB 相对亮度 0..1）：低于这条的一律不参与 bloom。 */
    static final float BLOOM_THRESHOLD = 0.55f;
    /** 阈值到全亮之间的窗口宽度；窗口内做平方衰减，所以没有硬边。 */
    static final float BLOOM_WINDOW = 0.45f;
    /**
     * bloom 的高斯半径 = 这一面的模糊档 × 系数，并且<em>有下限</em>。
     *
     * <p>下限是给「背景模糊推到 0」那台机器留的：那一档为 0 时预糊整层不存在，
     * 但 bloom 要的只是一团散开的亮斑，跟磨砂没关系。</p>
     */
    private static final float BLOOM_SIGMA_FACTOR = 1.6f;
    private static final float BLOOM_SIGMA_FLOOR = 6f;

    /**
     * 亮部提取：按相对亮度开一道窗口，窗口内平方衰减。
     *
     * <p>为什么在 SkSL 里做而不用 {@code ColorFilter.makeMatrix}：Skia 那份颜色矩阵的偏移列
     * 到底是 0..1 还是 0..255，五棵树与两次 Skia 版本的说法不一样，写错了的症状是
     * 「整张 bloom 全黑」或「整张都是灰」——两种都在糊着的底上看不出来。自己写就不可能有这种歧义。</p>
     */
    private static final String BLOOM_SKSL = """
            uniform shader uRaw;
            uniform float4 uParams;   // x = 阈值, y = 窗口宽度, zw 备用

            half4 main(float2 xy) {
                float4 c = uRaw.eval(xy);
                float l = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));
                float k = clamp((l - uParams.x) / max(uParams.y, 1e-4), 0.0, 1.0);
                k *= k;
                return half4(half3(clamp(c.rgb, float3(0.0), float3(1.0)) * k), 1.0);
            }
            """;

    /**
     * 设置里那一档「玻璃亮部溢出」，钳过之后的值。
     *
     * <p>0 = 整条链路一格都不许多画：不建纹理、不做亮部提取、不做高斯，
     * {@link #bloom()} 直接返回 null，{@code SkijaEffects} 那边连一次 drawImage 都不发。
     * 这一条是被 {@code GlassBloomStaticTest} 钉着的——「新加的开销默认必须是零」。</p>
     */
    public static float bloomStrength() {
        float strength = com.muoniumplayer.core.settings.HudConfig.glassBloom;
        return strength > 0f && strength <= 1f ? strength : 0f;
    }

    /**
     * 本帧可用的 bloom 图；没有背景、强度为 0、或这一层建不起来时为 null。
     *
     * <p>与预糊同一个刷新节奏：每次采集之后重做一次，而不是每帧（{@link #captures()} 就是判据）。
     * 强度滑块拖动时<b>不</b>重建——滑块只改最后叠加那一层的 alpha，图本身与强度无关，
     * 这一点和预糊相反（预糊的半径就是它的身份）。</p>
     */
    public static Image bloom() {
        if (bloomBroken || bloomStrength() <= 0f) {
            return null;
        }
        Image raw = image();
        if (raw == null || SkijaHost.isDisabled()) {
            return null;
        }
        if (bloomSlot.image != null && bloomBuiltAtCapture == captures) {
            return bloomSlot.image;
        }
        return rebuildBloom(raw);
    }

    private static Image rebuildBloom(Image raw) {
        DirectContext context = SkijaHost.context();
        if (context == null || textureWidth <= 0 || textureHeight <= 0) {
            return null;
        }
        RuntimeEffect effect = bloomEffect();
        Shader child = bloomChild(raw);
        if (effect == null || child == null) {
            return null;
        }
        float sigma = Math.max(BLOOM_SIGMA_FLOOR, blurSigma() * BLOOM_SIGMA_FACTOR);
        try {
            if (!ensureBlurTarget(bloomSlot)) {
                return null;
            }
            try (Data uniforms = bloomUniforms(effect);
                 Shader bright = effect.makeShader(uniforms, new Shader[]{child});
                 ImageFilter spread = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
                 Paint paint = new Paint().setImageFilter(spread)) {
                if (bright == null) {
                    bloomBroken = true;
                    return null;
                }
                paint.setShader(bright);
                Canvas canvas = bloomSlot.surface.getCanvas();
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(0f, 0f,
                        (float) textureWidth, (float) textureHeight), paint);
            }
            context.flush(bloomSlot.surface);
            bloomBuiltAtCapture = captures;
            return borrowBlur(context, bloomSlot);
        } catch (Throwable failure) {
            bloomBroken = true;
            releaseBlurTarget(bloomSlot);
            System.out.println(TAG + "亮部溢出那层建不出来，玻璃少一圈亮晕（功能不受影响）: " + failure);
            return null;
        }
    }

    private static RuntimeEffect bloomEffect() {
        if (bloomEffect == null && !bloomEffectUnavailable) {
            try {
                bloomEffect = RuntimeEffect.makeForShader(BLOOM_SKSL);
            } catch (Throwable failure) {
                bloomEffectUnavailable = true;
                System.out.println(TAG + "亮部提取着色器编译失败，这一层停用: " + failure);
            }
        }
        return bloomEffect;
    }

    /** uniform 就一个 float4：阈值、窗口，后两格是留的位（Skia 要 16 字节对齐）。 */
    private static Data bloomUniforms(RuntimeEffect effect) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer
                .allocate(Math.max(16, effect.getUniformSize()))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(BLOOM_THRESHOLD).putFloat(BLOOM_WINDOW).putFloat(0f).putFloat(0f);
        return Data.makeFromBytes(buf.array());
    }

    private static Shader bloomChild(Image raw) {
        if (bloomChild != null && bloomChildImage == raw) {
            return bloomChild;
        }
        if (bloomChild != null) {
            bloomChild.close();
            bloomChild = null;
            bloomChildImage = null;
        }
        try {
            // 1:1 画到同一张尺寸的 FBO 上，所以不需要局部矩阵；朝向与预糊那一层同一套约定
            // （两头都是 BOTTOM_LEFT，见 borrowBlur）。
            bloomChild = raw.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP);
            bloomChildImage = raw;
            return bloomChild;
        } catch (Throwable failure) {
            bloomBroken = true;
            System.out.println(TAG + "背景图包不成 shader，亮部溢出停用: " + failure);
            return null;
        }
    }

    /** 给探针与静态测试：亮部提取的 SkSL 源码。 */
    public static String bloomSource() {
        return BLOOM_SKSL;
    }

    /** bloom 那层此刻活着吗（建不起来时 false，玻璃照样画）。 */
    public static boolean bloomBroken() {
        return bloomBroken;
    }

    /** 背景纹理尺寸（设备像素）。 */
    public static int width() {
        return textureWidth;
    }

    public static int height() {
        return textureHeight;
    }

    /**
     * 此刻「画布局部单位 → 背景<b>图像素</b>」的映射；采不到、映射不出，一律返回 {@code null}。
     *
     * <h2>为什么不接调用方报上来的倍率</h2>
     * 玻璃要采的是屏幕上那块位置，而调用点的坐标活在它自己的局部空间里：灵动岛外面套着
     * 用户的尺寸缩放与入场缩放（{@code scaleAtPos} 带轴心），HUD 编辑器套着设计空间与面板
     * 出场动画，播放器那一层套着窗口自适应。以前这些倍率由每个调用点各自算给玻璃，代价是
     * <b>错了也看不出来</b>——底图整块糊着，画面偏移在截图上和「本来就该长这样」没有区别
     * （2026-09-29 复查时发现灵动岛在尺寸档位 ≠ 1 时透的是屏幕别处的画面，而轴心平移更是
     * 纯倍率表达不出来的）。改成直接问 Skia：{@code local → device} 的矩阵就是画这块矩形
     * <em>将要</em>用的那个矩阵，它天然包含轴心、平移与每一层缩放，调用点再无倍率可报错。
     *
     * <h2>只认「正的轴向缩放 + 平移」</h2>
     * 背景图是轴对齐的一张，源矩形只能轴对齐地取。矩阵里一旦有旋转或错切，画布局部的那个
     * 矩形映到屏幕上就变成一个斜四边形，轴对齐的外接框会采到邻接的画面——那比没有玻璃更
     * 像 bug。所以这里直接返回 {@code null}，让调用点退回它自己那套假玻璃画法。
     */
    public static Mapping mapping(Canvas canvas) {
        if (canvas == null || image() == null) {
            return null;
        }
        float[] m;
        try {
            m = canvas.getLocalToDeviceAsMatrix33().getMat();
        } catch (Throwable failure) {
            logOnce("读画布矩阵失败，玻璃这一帧不铺背景: " + failure);
            return null;
        }
        Mapping mapped = mappingFromMatrix(m, Math.max(1, downscale()));
        if (mapped == null && m != null && m.length >= 9) {
            logOnce("画布矩阵给不出轴对齐的背景取样（旋转/错切/镜像），玻璃退回假玻璃画法");
        }
        return mapped;
    }

    /**
     * 同一件事，只问的对象换成<em>一份矩阵</em>而不是活画布。
     *
     * <p>给融合批次用：那一组的条目坐标活在开组那一刻那套坐标系里，而收尾可能发生在
     * 别的矩阵下（组里最后那块控件的 push 还没 pop 干净、或容器又平移了一段）。取样档必须
     * 跟着<em>坐标那套</em>矩阵算，否则「这一圈折的是画面别处」——而它只有几像素、背后又糊着，
     * 截图上判不出来。</p>
     */
    static Mapping mapping(float[] deviceMatrix) {
        if (image() == null || deviceMatrix == null) {
            return null;
        }
        return mappingFromMatrix(deviceMatrix, Math.max(1, downscale()));
    }

    /**
     * {@link #mapping(Canvas)} 里那层纯数学，单独拎出来是因为真机上矩阵只能从活画布读，
     * 而它错了要付的代价是「整块玻璃透的是屏幕别处的画面」——这种账必须在 JVM 里用喂进去的
     * 矩阵算得清，{@code GlassCoordinateStaticTest} 钉的就是这里。
     *
     * @param down 采集倍率：背景图每一个像素对应 {@code down} 个设备像素
     */
    static Mapping mappingFromMatrix(float[] m, float down) {
        if (m == null || m.length < 9 || down <= 0f) {
            return null;
        }
        // Skia 的 3x3 按行存：[sx, kx, tx, ky, sy, ty, p0, p1, p2]
        float scaleX = m[0] / down;
        float scaleY = m[4] / down;
        if (Math.abs(m[1]) > 1e-3f || Math.abs(m[3]) > 1e-3f
                || Math.abs(m[6]) > 1e-3f || Math.abs(m[7]) > 1e-3f
                || Math.abs(m[8] - 1f) > 1e-3f) {
            // 旋转/错切/透视下画布局部那个矩形映到屏幕是一个斜四边形，
            // 轴对齐的外接框会采进邻接的画面——那比没有玻璃更像 bug，直接不采。
            return null;
        }
        if (scaleX <= 1e-4f || scaleY <= 1e-4f) {
            // 负=镜像，零=退化。这两种都不该去采背景。
            return null;
        }
        return new Mapping(scaleX, scaleY, m[2] / down, m[5] / down);
    }

    /**
     * 「画布局部单位 → 背景图像素」的仿射：{@code tex = scale * local + offset}。
     *
     * <p>由 {@link #mapping(Canvas)} 每帧现算，因为矩阵每帧都不同（缩放动画、窗口变化）。
     * 两个轴分开存，是因为播放器那一层允许 x/y 系数不相等（{@code WindowAdaptiveScale}）。</p>
     */
    public static final class Mapping {
        /** 局部单位 → 图像素，x 轴倍率。 */
        public final float scaleX;
        public final float scaleY;
        /** 局部 x=0 落在图像素的哪一列（轴心缩放的平移就体现在这里）。 */
        public final float offsetX;
        public final float offsetY;

        Mapping(float scaleX, float scaleY, float offsetX, float offsetY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        /** 局部 x → 图像素列。没有错切，所以与 y 无关。 */
        public float textureX(float localX) {
            return scaleX * localX + offsetX;
        }

        public float textureY(float localY) {
            return scaleY * localY + offsetY;
        }
    }

    /** 内部格式：默认按字节原样搬（RGBA8），逃生开关打开才让驱动做一次 sRGB 解码。 */
    private static int internalFormat() {
        return SRGB_INTERNAL ? GL21.GL_SRGB8_ALPHA8 : GL11.GL_RGBA8;
    }

    /**
     * 设置里那一档「背景模糊强度」，钳过之后的值（0 = 不糊，只压暗）。
     *
     * <p>放在这里而不是留在 {@code GlassLook}，是因为预糊这一层归本类管，而糊它的两处
     * （贴背景、折边缘）必须用<em>同一个</em>半径——两头各读一次配置，滑块一动就会出现
     * 「面板糊成一片、元素边缘却折着清晰画面」这种一半生效的怪相。
     * {@code GlassLook.blurSigma()} 现在只是转发过来。</p>
     */
    public static float blurSigma() {
        float sigma = com.muoniumplayer.core.settings.HudConfig.glassBlurSigma;
        return sigma > 0f && sigma <= 64f ? sigma : 0f;
    }

    public static long captures() {
        return captures;
    }

    public static long lastCaptureNanos() {
        return lastCaptureNanos;
    }

    public static boolean isBroken() {
        return broken;
    }

    /**
     * 上下文重建 / 渲染层关闭时丢掉目标；下一次采集会重新分配。
     *
     * <p>{@link SkijaHost#shutdown()} 里叫一次。GL 调用包在 try 里是因为这个入口<strong>可能</strong>
     * 在上下文已经不 current 的时候被调到（shutdown 现在还没有调用方，等谁把它接上时线程未必对），
     * 那时删纹理不是「失败」而是崩在原生层——丢掉引用即可，反正上下文都没了。</p>
     */
    public static void invalidate() {
        GlassLiquidSurface.release();
        try {
            releaseAllBlurTargets();
            releaseTarget();
        } catch (Throwable ignored) {
            image = null;
            textureId = 0;
            framebufferId = 0;
            textureWidth = 0;
            textureHeight = 0;
            for (BlurSlot slot : blurTargets()) {
                // 上下文可能已经没了：这里只丢引用，不再发 GL 删除调用（发出去是崩在原生层）。
                slot.image = null;
                slot.surface = null;
                slot.backend = null;
                slot.textureId = 0;
                slot.framebufferId = 0;
                slot.width = 0;
                slot.height = 0;
            }
            blurLevels.reset();
            bloomBuiltAtCapture = -1L;
            bloomChild = null;
            bloomChildImage = null;
        }
        broken = false;
        blurBroken = false;
        bloomBroken = false;
        firstCaptureUnlogged = false;
        mappingWarned = false;
    }

    // ------------------------------------------------------------------ 内部

    /** 同一类提醒只打一次，直到 {@link #invalidate()} 把它重新打开。 */
    private static void logOnce(String message) {
        if (mappingWarned) {
            return;
        }
        mappingWarned = true;
        System.out.println(TAG + message);
    }

    private static boolean ensureTarget(int width, int height) {
        if (textureId != 0 && textureWidth == width && textureHeight == height) {
            return true;
        }
        releaseTarget();
        textureId = GL11.glGenTextures();
        if (textureId == 0) {
            broken = true;
            System.out.println(TAG + "glGenTextures 返回 0，已停用背景采集");
            return false;
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0,
                    internalFormat(),
                    width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

            framebufferId = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, textureId, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                broken = true;
                System.out.println(TAG + "背景采集目标不完整，已停用");
                return false;
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        textureWidth = width;
        textureHeight = height;
        // 尺寸变了就是另一张图，旧视图必须丢掉，否则 Skia 还按旧尺寸算源矩形。
        if (image != null) {
            image.close();
            image = null;
        }
        return true;
    }

    private static void releaseTarget() {
        GlassLiquidSurface.release();
        if (image != null) {
            image.close();
            image = null;
        }
        if (framebufferId != 0) {
            GL30.glDeleteFramebuffers(framebufferId);
            framebufferId = 0;
        }
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
        textureWidth = 0;
        textureHeight = 0;
    }

    /**
     * 把采集纹理借给 Skia。只在尺寸变化后重建一次。
     *
     * <p>{@code borrow} 而不是 {@code adopt}：所有权仍在 GL 纹理手上，Skia 不会去删它
     * （删了下一帧就采不到东西了）。
     */
    static Image wrapIntoSkia() {
        DirectContext context = SkijaHost.context();
        if (context == null || textureId == 0 || textureWidth <= 0) {
            return null;
        }
        if (image != null) {
            return image;
        }
        try {
            BackendTexture backend = BackendTexture.makeGL(textureWidth, textureHeight, false,
                    new GLTextureInfo(GL11.GL_TEXTURE_2D, textureId, internalFormat()));
            image = Image.borrowTextureFrom(context, backend, SurfaceOrigin.BOTTOM_LEFT,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (image == null) {
                broken = true;
                System.out.println(TAG + "borrowTextureFrom 返回 null，已停用背景采集");
            }
            return image;
        } catch (Throwable failure) {
            broken = true;
            System.out.println(TAG + "包装背景纹理失败: " + failure);
            return null;
        }
    }
}
