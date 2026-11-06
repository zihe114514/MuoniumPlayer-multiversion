package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 抓一帧「当前画面」存成 PNG，给首启片头当模糊背景。
 *
 * <h2>为什么走原版截图链路而不是自己读帧缓冲</h2>
 * 本渲染层的 Skia 画在<b>自己的透明离屏</b>上，帧末才由 {@code GlSurface#present()} 入口的
 * mixin 合成到窗口（见 {@code SkijaHost}）。因此 {@code SkijaEffects.backdropBlur} 只能糊到
 * Skia 自己已经画过的东西，<b>糊不到游戏画面</b>——桌面歌词那张毛玻璃卡片之所以成立，是因为
 * 要糊的内容本来就在同一个 Skia 图层里。要让片头背景是「玩家此刻看到的画面」，只有一条路：
 * 把游戏帧缓冲读出来，再作为一张图喂给 Skia。读这件事交给原版 {@link Screenshot}，
 * 因为它是五个版本里唯一一条被官方自己每帧用过、且名字稳定的链路。
 *
 * <h2>为什么交出去的是 PNG 文件而不是像素数组</h2>
 * 五棵树的像素通道打包方式不是一套：1.20.1 / 1.21.1 只有 {@code getPixelsRGBA()}，
 * 1.21.11 起才有 {@code getPixels()} / {@code getPixelsABGR()}，而 {@code writeToByteArray}
 * 五版全都没有（javap 实测）。自己拼 {@code int[]} 就得赌「packed int 里哪 8 位是 R」，
 * 赌错的表现为红蓝互换——而一张要被糊成背景的图恰恰最容易让人以为「反正糊了，无所谓」。
 * {@code writeToFile} 五版都有，PNG 编解码两端各自负责自己的字节序，因此这条路<b>没有通道顺序
 * 可猜</b>。代价是一次几十 KB 的磁盘往返，而它只在片头打开时发生一次。
 *
 * <h2>回调是延迟的，别按同步写</h2>
 * 26.3 的 {@code takeScreenshot} 只是把 {@code copyTextureToBuffer(texture, buffer, 0, done, flags)}
 * 排进命令编码器就返回，{@code done} 里才建 {@code NativeImage} 交给 Consumer
 * （字节码实测：这个方法连编码器都没 close）。真机上按「调用完立刻读结果」写的版本拿到的就是
 * {@code null}，症状是背景永远退回纯色底，而日志里看不出任何异常。所以 {@link Callback} 的语义是
 * 「稍后会有一次回调」，调用方必须能接受结果晚到。
 *
 * <h2>五棵树的差异面（同步到另外四棵树时要逐条打回，别盲 cp）</h2>
 * <ol>
 *   <li><b>主目标取法</b>：26.2 / 26.3 是 {@code mc.gameRenderer.mainRenderTarget()}；
 *       1.20.1 / 1.21.1 / 1.21.11 是 {@code mc.getMainRenderTarget()}（后者在 26.2 已被删除）。</li>
 *   <li><b>取帧形式</b>：1.20.1 / 1.21.1 只有同步返回的
 *       {@code NativeImage takeScreenshot(RenderTarget)}——那两棵把 {@link #grab} 改成
 *       {@code store(Screenshot.takeScreenshot(target), pngTarget, callback);} 即可；
 *       1.21.11 / 26.2 / 26.3 是 Consumer 形式。</li>
 *   <li><b>scale 重载是否存在</b>：1.20.1 / 1.21.1 没有——那两棵只能全分辨率抓，
 *       缩小交给 Skia 绘制端。</li>
 * </ol>
 *
 * <p>三条全收在 {@link #mainTarget} 与 {@link #grab} 两个方法里，同步时只看这两处。</p>
 */
public final class ScreenCapture {

    private static final String TAG = "[MuoniumPlayer/ScreenCapture] ";

    /**
     * 抓帧的缩小倍数候选。26.3 的字节码里写死了「宽高必须被 scale 整除」，
     * 所以这不是「想要多大就多大」，而是从大到小试第一个能整除的。
     */
    private static final int[] SCALE_CANDIDATES = {4, 2, 1};

    /** 调试：{@code -Dmuonium.debug.capture=true} 时保留 PNG 并打日志，不删。 */
    private static final boolean DEBUG_KEEP = Boolean.getBoolean("muonium.debug.capture");

    /** 一次只允许一个未完成的抓帧；回调回来才放行。 */
    private static final AtomicBoolean PENDING = new AtomicBoolean();

    private ScreenCapture() {
    }

    /** 一次抓帧的结果：PNG 已落盘，{@code width/height} 是图本身的尺寸。 */
    public static final class Frame {
        public final File png;
        public final int width;
        public final int height;

        Frame(File png, int width, int height) {
            this.png = png;
            this.width = width;
            this.height = height;
        }
    }

    public interface Callback {
        /** 失败时 {@code frame} 为 null；失败原因已经打过日志，调用方只需要走降级路径。 */
        void onFrame(Frame frame);
    }

    /**
     * 发起一次抓帧，结果异步交给 {@code callback}。必须在渲染线程调用。
     *
     * <p>已经在飞的那次没回来时直接忽略并留一行日志——片头只要一张底，
     * 排队抓第二张没有意义。</p>
     */
    public static void capture(File pngTarget, Callback callback) {
        if (!PENDING.compareAndSet(false, true)) {
            System.out.println(TAG + "上一次抓帧还没回来，本次忽略");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            finish(callback, null, "Minecraft 实例为 null");
            return;
        }
        RenderTarget target = mainTarget(mc);
        if (target == null) {
            finish(callback, null, "主渲染目标为 null");
            return;
        }
        try {
            grab(target, pngTarget, callback, System.nanoTime());
        } catch (Throwable t) {
            finish(callback, null, "发起抓帧就抛了异常: " + t);
        }
    }

    /** PNG 是否只是临时草稿：调试模式下要留着给人看。 */
    public static boolean keepsDebugCopy() {
        return DEBUG_KEEP;
    }

    /** 用完就删；调试模式留着。删失败不打日志——那是个可以消失的临时文件。 */
    public static void release(File png) {
        if (DEBUG_KEEP || png == null) {
            if (DEBUG_KEEP && png != null) {
                System.out.println(TAG + "调试模式保留抓帧图: " + png.getAbsolutePath());
            }
            return;
        }
        try {
            Files.deleteIfExists(png.toPath());
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- 版本差异面

    /**
     * 26.2 / 26.3：主目标挂在 GameRenderer 上。
     *
     * <p>另外三棵树改成 {@code return mc.getMainRenderTarget();}——这个方法在 26.2 里已经不存在
     * （javap 计数 0），所以这行差异是版本 API 差异，不是历史漂移，同步时必须按树保留。</p>
     */
    private static RenderTarget mainTarget(Minecraft mc) {
        return mc.getMainRenderTarget();
    }

    /**
     * 26.x：带 scale 的 Consumer 重载，且 scale 必须整除目标宽高。
     *
     * <p>1.20.1 / 1.21.1 没有 scale 重载，改成
     * {@code store(Screenshot.takeScreenshot(target), pngTarget, callback, startedAt);}。</p>
     */
    private static void grab(RenderTarget target, File pngTarget, Callback callback,
                             long startedAtNanos) {
        int scale = pickScale(target.width, target.height);
        Screenshot.takeScreenshot(target, scale,
                image -> store(image, pngTarget, callback, startedAtNanos, scale));
    }

    /**
     * 把回读到的图写成 PNG，再交给调用方。
     *
     * <p>{@code NativeImage} 由这里负责关：26.3 的字节码里 MC 只关它自己的 MappedView 与
     * GpuBuffer，Consumer 收到的那张图没人管。</p>
     */
    private static void store(NativeImage image, File pngTarget, Callback callback,
                              long startedAtNanos, int scale) {
        if (image == null) {
            finish(callback, null, "抓帧回调给的是 null");
            return;
        }
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                finish(callback, null, "抓帧回来的尺寸非法 " + width + "x" + height);
                return;
            }
            File parent = pngTarget.getParentFile();
            if (parent != null && !parent.isDirectory()
                    && !parent.mkdirs() && !parent.isDirectory()) {
                finish(callback, null, "目录建不出来: " + parent);
                return;
            }
            long writeStartedAt = System.nanoTime();
            image.writeToFile(pngTarget.toPath());
            if (DEBUG_KEEP) {
                // 只有调试档打这行：片头只要一张底，正常玩家不该在日志里看见计时。
                // 量这两个数是给「能不能每帧抓」划界用的——回读是同步阻塞在调用线程上的。
                System.out.println(TAG + "抓帧 " + width + "x" + height + "（1/" + scale + "）"
                        + " 回读 " + millisSince(startedAtNanos) + "ms"
                        + " 写盘 " + millisSince(writeStartedAt) + "ms");
            }
            finish(callback, new Frame(pngTarget, width, height), null);
        } catch (Throwable t) {
            finish(callback, null, "写抓帧 PNG 失败: " + t);
        } finally {
            try {
                image.close();
            } catch (Throwable ignored) {
                // 关不掉只是漏一块本地内存，不值得为此打断片头。
            }
        }
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** 从 4 倍往下找第一个能同时整除宽高的缩小倍数；都不行就 1（不缩小）。 */
    private static int pickScale(int width, int height) {
        for (int scale : SCALE_CANDIDATES) {
            if (width % scale == 0 && height % scale == 0) {
                return scale;
            }
        }
        return 1;
    }

    private static void finish(Callback callback, Frame frame, String reason) {
        PENDING.set(false);
        if (frame == null) {
            // 每一环失败都要留一行日志：真机上「背景为什么没糊」只能靠这个查。
            System.out.println(TAG + reason);
        }
        callback.onFrame(frame);
    }
}
