package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.compat.mc.ScreenCapture;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.io.File;
import java.nio.file.Files;

/**
 * 引导页的全屏背景：抓一帧玩家此刻看到的画面，糊掉、压暗，铺满整屏。
 *
 * <h2>为什么模糊是在加载时烘一次，而不是每帧糊</h2>
 * 每帧对整屏跑一次 {@code ImageFilter} 是实打实的 GPU 开销，而这张背景在整段引导里
 * 只需要变一次——它存在的意义是「让画面有个底」，不是直播。烘进图里的另一条好处是
 * 压暗也能一起烘掉，绘制端就只剩一次 cover-fit 的 {@code drawImageRect}。
 *
 * <h2>为什么抓不到就必须安静降级，而不是等</h2>
 * 抓帧牵着一整条原版截图链路（帧缓冲回读 + PNG 编解码 + 磁盘往返），五棵树两种形状。
 * 引导是玩家进游戏看到的第一个东西，它宁可退回原来的黑曜石底 + 品牌光晕，
 * 也不能因为背景没糊出来就白屏或卡住。失败原因每一环都打一行日志，
 * 真机上「为什么没糊」只能靠那行日志查。
 */
public final class OnboardingBackdrop implements AutoCloseable {

    private static final String TAG = "[MuoniumPlayer/Backdrop] ";

    /**
     * 高斯半径，单位是<b>抓帧像素</b>，不是屏幕像素。
     *
     * <p>抓帧的缩小倍数由 {@code ScreenCapture.pickScale} 按「谁整除得下」现算，所以这一层的
     * 实际模糊量随窗口尺寸变：1920×1080 走 1/4，屏幕上就是 12 个像素；854×480 只能走 1/2
     * （854 不被 4 整除，真机实测），屏幕上就是 6 个像素；而 1.20.1 / 1.21.1 没有 scale 重载，
     * 全分辨率抓 → 半径就是 3 个像素，<b>糊得明显更轻</b>。要按树调的是这里，不是绘制端。</p>
     */
    private static final float BLUR_SIGMA = 3.0f;

    /** 压暗量：ARGB。彩虹 hello 要立在背景上读得清，靠的是这层而不是描边加粗。 */
    private static final int DARKEN = 0x990B0C11;

    /** 绘制时的过扫比例：模糊与圆角衰减都发生在边缘，留 12% 余量免得露出糊边。 */
    private static final double OVERSCAN = 1.12;

    /**
     * 抓帧图到货之后的淡入速率，交给 {@code Interpolations}（与页面 alpha 的 .18 同一族）。
     *
     * <p>取 .16 而不是更快：这一层要盖住的是一块<b>整屏</b>，跳得太快就又成了一次硬切；
     * 也不能太慢——描字与骨架都压在它上面，它迟迟不淡满，底下那层网格光晕会透太久。</p>
     */
    private static final float REVEAL_RATE = .16f;

    private final File png;

    private Image baked;
    /** 抓帧图淡到第几成了（0 = 刚到货，1 = 完全盖住垫底）。见 {@link #REVEAL_RATE}。 */
    private double reveal;
    private boolean attempted;
    private boolean failedLogged;
    private int bakedWidth;
    private int bakedHeight;
    /** 抓帧回来的图；回调可能在发起之后的某一帧，所以按可见性要求声明。 */
    private volatile ScreenCapture.Frame readyFrame;

    public OnboardingBackdrop() {
        this(ConfigPaths.INTRO_CAPTURE);
    }

    /** 测试用：指到别处的草稿文件，避免碰真配置目录。 */
    OnboardingBackdrop(File png) {
        this.png = png;
    }

    /**
     * 第一次调用时发起抓帧；之后直接返回。必须在渲染线程调用。
     *
     * <p>刻意不做定时刷新：抓帧是 GPU 回读，每帧之外再定时来一次会给一个四秒的覆盖层
     * 凭空加几次卡顿。要「活的」背景留给以后按需扩，且必须先量过开销。</p>
     *
     * <p>结果<b>不会当场到</b>（见 {@code ScreenCapture} 的类注释），所以这里只负责发起，
     * 到了的图由 {@link #pump()} 在随后的某一帧收下。</p>
     */
    public void prepare() {
        if (attempted || baked != null) {
            return;
        }
        attempted = true;
        try {
            ScreenCapture.capture(png, frame -> {
                if (frame == null) {
                    logFailure("抓帧没成功（上一行 ScreenCapture 是原因），引导退回纯色底");
                    return;
                }
                readyFrame = frame;
            });
        } catch (Throwable t) {
            logFailure("发起抓帧就抛了，引导退回纯色底: " + t);
        }
    }

    /** 抓帧回来是异步的，所以每次绘制前先看一眼到没到；到了就烘一次。 */
    private void pump() {
        ScreenCapture.Frame frame = readyFrame;
        if (frame == null || baked != null) {
            return;
        }
        readyFrame = null;
        try {
            bake(frame);
        } catch (Throwable t) {
            logFailure("烘背景时抛了，引导退回纯色底: " + t);
        } finally {
            ScreenCapture.release(png);
        }
    }

    private void bake(ScreenCapture.Frame frame) throws Exception {
        byte[] bytes = Files.readAllBytes(png.toPath());
        if (bytes.length == 0) {
            logFailure("抓帧 PNG 是空的，引导退回纯色底");
            return;
        }
        try (Image raw = Image.makeFromEncoded(bytes)) {
            if (raw == null) {
                logFailure("抓帧 PNG 解码失败，引导退回纯色底");
                return;
            }
            int width = raw.getImageInfo().getWidth();
            int height = raw.getImageInfo().getHeight();
            if (width <= 0 || height <= 0) {
                logFailure("抓帧尺寸非法 " + width + "x" + height + "，引导退回纯色底");
                return;
            }
            try (Surface surface = Surface.makeRaster(ImageInfo.makeN32Premul(width, height));
                 ImageFilter blur = ImageFilter.makeBlur(BLUR_SIGMA, BLUR_SIGMA, FilterTileMode.CLAMP);
                 Paint paint = new Paint()) {
                if (surface == null) {
                    logFailure("离屏 Surface 建不出来，引导退回纯色底");
                    return;
                }
                Canvas canvas = surface.getCanvas();
                paint.setAntiAlias(true);
                paint.setImageFilter(blur);
                // 源图整块贴满，模糊由 CLAMP 处理边缘；四周再往外扩一点免得把边缘拉薄。
                canvas.drawImageRect(raw, Rect.makeLTRB(0f, 0f, width, height),
                        Rect.makeXYWH(-BLUR_SIGMA * 2f, -BLUR_SIGMA * 2f,
                                width + BLUR_SIGMA * 4f, height + BLUR_SIGMA * 4f),
                        SamplingMode.LINEAR, paint, true);
                try (Paint scrim = new Paint()) {
                    scrim.setColor(DARKEN);
                    canvas.drawRect(Rect.makeLTRB(0f, 0f, width, height), scrim);
                }
                Image snapshot = surface.makeImageSnapshot();
                if (snapshot == null) {
                    logFailure("快照返回 null，引导退回纯色底");
                    return;
                }
                closeBaked();
                baked = snapshot;
                bakedWidth = width;
                bakedHeight = height;
            }
        }
    }

    /**
     * 铺满 {@code width x height} 的背景，并负责「淡入期间底下垫什么」这件事。
     *
     * <p>抓帧是<b>异步</b>的（回读 + 写盘 + 解码 + 烘焙，实测 878 ms 那一档），而引导页自己的
     * 淡入只有半秒——于是原图到货那一刻，页面 alpha 已经快到 1 了。以前这里画不画得成一个
     * 布尔值，调用方拿它决定要不要画降级底，结果就是<b>纯色底 → 一张糊掉的照片在一帧之内换完</b>，
     * 玩家看到的正是「模糊突然出现、没有过程」。现在改成：图到货之后自己按帧淡入
     * （和页面 alpha、描字时钟同一套帧步进语义，大帧不会让它跳过去），
     * 淡入没走完就一直先画调用方给的垫底，交接处是交叉溶，不是硬切。</p>
     *
     * @param underneath 还没有抓帧图、或者抓帧图还没淡满时，先画在这一层下面的东西
     */
    public void draw(double width, double height, float alpha, Runnable underneath) {
        // 抓帧是异步回来的，先收下再说画不画。
        pump();
        if (baked == null || width <= 0 || height <= 0) {
            underneath.run();
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            underneath.run();
            return;
        }
        reveal = Interpolations.interpolate(reveal, 1.0, REVEAL_RATE);
        if (reveal < .999) {
            underneath.run();
        }
        double scale = Math.max(width / bakedWidth, height / bakedHeight) * OVERSCAN;
        double drawWidth = bakedWidth * scale;
        double drawHeight = bakedHeight * scale;
        Rect dst = Rect.makeXYWH((float) ((width - drawWidth) * .5),
                (float) ((height - drawHeight) * .5), (float) drawWidth, (float) drawHeight);
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setAlphaf(Math.max(0f, Math.min(1f, alpha * (float) reveal)));
            canvas.drawImageRect(baked, Rect.makeLTRB(0f, 0f, bakedWidth, bakedHeight), dst,
                    SamplingMode.LINEAR, paint, true);
        }
    }

    private void closeBaked() {
        if (baked != null) {
            try {
                baked.close();
            } catch (Throwable ignored) {
                // 关不掉只是多占一块显存，界面关闭后也没人再读它。
            }
            baked = null;
        }
    }

    @Override
    public void close() {
        closeBaked();
    }

    private void logFailure(String reason) {
        if (failedLogged) {
            return;
        }
        failedLogged = true;
        System.out.println(TAG + reason);
    }
}
