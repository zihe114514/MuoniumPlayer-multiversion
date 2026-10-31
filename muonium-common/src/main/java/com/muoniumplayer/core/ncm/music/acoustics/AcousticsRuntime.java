package com.muoniumplayer.core.ncm.music.acoustics;

import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.processing.sound.Engine;
/**
 * 场景声学的运行时:把「树侧每 tick 采一帧听者环境」接到「音频线程的尾槽处理器」。
 *
 * <h2>线程与闸门</h2>
 * <ul>
 *   <li><b>游戏线程</b>:{@link #onClientTick(Object)} 由各版本树的共享 tick 桥调进来
 *       (参数是树的 {@code Minecraft} 实例,由树侧的采样器消费,这里不认识它)。
 *       每 tick 只做:开关判断、节流、把采样交给推导、结果写进 {@link SceneAcoustics}
 *       的 volatile 目标量。全部 try/Throwable 包住 —— tick 上的任何异常都不能带崩游戏。</li>
 *   <li><b>音频线程</b>:{@link OutputChain} 只做 double→float→double 的搬运和最终
 *       交付,具体滤波在 {@link SceneAcoustics#render} 里,实时纪律见那边的类注释。</li>
 * </ul>
 *
 * <h2>挂接点</h2>
 * <p>走引擎的<b>尾槽</b>({@code setTrailingOutputProcessor}),与蝰蛇音效的主槽互不
 * 覆盖:蝰蛇先把整个混音处理完,声场修饰看的是处理完的最终信号。两个槽各自
 * try/catch,一边熔断不影响另一边。挂接时机与 {@code ViperRuntime.ensureInstalled}
 * 相同 —— 每路 deck 装载时调 {@link #ensureInstalled},幂等。</p>
 *
 * <p>采样不在世界/引擎没在出声时目标量回到静息,处理器仍挂在链上但每缓冲只是
 * 提前返回 —— 比反复挂/摘一个槽便宜,也不存在「忘了摘」这类半状态。</p>
 */
public final class AcousticsRuntime {

    private static final Object LOCK = new Object();

    private static boolean installed;

    /** 同一环境连续 tick 的复用窗口(毫秒):采样要遍历方块,空旷场景一 tick 几十次
     *  方块读不算贵但也不白给;环境本身变化尺度是秒级,200ms 足够平滑。 */
    private static final long SAMPLE_INTERVAL_MS = 200;

    private static long lastSampleMs;
    private static boolean lastHadListener;
    /** 一次性诊断:首次成功采样 / 首次 tick 异常 / 房间跨越,各自只打一条。 */
    private static boolean sampleLogged;
    private static boolean tickFailureLogged;
    private static boolean roomLogged;
    /** 房间声学诊断的触发阈:低于它的湿声不值得占日志。 */
    private static final float ROOM_LOG_WET = 0.05f;

    private AcousticsRuntime() {
    }

    /**
     * 挂尾槽。幂等;只允许在 deck 装载路径上调用({@code AudioPlayer.loadUsableSoundFile},
     * 紧随设备兜底探测与蝰蛇挂接之后)。
     *
     * <p><b>为什么 tick 路径绝不能调它</b>:本方法会触发 {@code Engine.getEngine()} 的
     * 引擎创建,而引擎在构造那一刻快照式选定音频设备管理器。设备兜底探测
     * ({@code installAudioDeviceFallbackIfMissing})必须在引擎创建<b>之前</b>完成,
     * 晚了就换不掉了 —— 提前建出来的引擎用着默认(可能是坏的)设备线,播放写进去
     * 引擎线程卡死,表现就是「下载解码都完成、进度条不走」。tick 只写目标量,
     * 目标量先于尾槽存在毫无问题:链路装上后的第一个缓冲就会读到它们。</p>
     */
    public static void ensureInstalled() {
        if (installed) {
            return;
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            try {
                Engine.getEngine().setTrailingOutputProcessor(new OutputChain());
                installed = true;
            } catch (Throwable failure) {
                // 引擎还没起来等异常:下一次 deck 装载会再试。不置 installed 即可。
                System.out.println("[MuoniumPlayer/Acoustics] 尾槽挂接失败,下首曲子重试:" + failure);
                return;
            }
            System.out.println("[MuoniumPlayer/Acoustics] 尾槽已挂接(主效果链之后)");
        }
    }

    /**
     * 每 tick 入口,由树的共享 client tick 调。
     *
     * <p><b>本方法不触碰音频引擎</b> —— 不创建、不查询、不挂接。引擎的创建时机归
     * deck 装载路径管(见 {@link #ensureInstalled} 的时序说明);这里只把采样结果
     * 写进 {@link SceneAcoustics} 的 volatile 目标量,链路装上前的写入会在第一个
     * 缓冲被读到。</p>
     *
     * @param sampler 树侧的采样器;传 {@code null} 表示「当前采不到」(主菜单等),
     *                等价于把目标量回静息。
     */
    public static void onClientTick(ListenerSampler sampler) {
        try {
            if (!AcousticsSettings.isEnabled()) {
                lastHadListener = false;
                SceneAcoustics.clearTarget();
                return;
            }

            final long now = System.currentTimeMillis();
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS && lastHadListener) {
                return; // 复用窗口内不重采;目标量保持上一帧,收敛交给音频线程。
            }
            lastSampleMs = now;

            ListenerSample sample = sampler == null ? null : sampler.capture();
            if (sample != null && !sampleLogged) {
                sampleLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] 首次采样: 浸液=" + sample.submerged()
                        + " 天光=" + sample.openSky() + " 围合=" + String.format("%.2f", sample.enclosure())
                        + " 降水=" + sample.precipitation());
            }
            lastHadListener = sample != null;
            RoomPlan plan = SceneResponse.respond(sample, AcousticsSettings.strength());
            // 房间跨越诊断:湿声从静息爬过可闻阈时打一条 —— 实机一眼看出
            // 探针有没有「看见」房间,数值合不合理(回静息复位,可反复报)。
            if (plan.wet >= ROOM_LOG_WET && !roomLogged) {
                roomLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] 房间声学: 围合="
                        + String.format("%.2f", sample.enclosure())
                        + " 首反射=" + String.format("%.1f", sample.firstHitMeanMeters()) + "m"
                        + " 反射率=" + String.format("%.2f", sample.meanReflectivity())
                        + " 可达=" + String.format("%.2f", sample.reachableFraction())
                        + " 抽头=" + sample.tapCount()
                        + " → " + plan);
            } else if (plan.wet < 0.02f) {
                roomLogged = false;
            }
            SceneAcoustics.applyTarget(plan);
        } catch (Throwable failure) {
            // 环境采样是锦上添花:任何异常都宁可让效果僵在上一帧,也不往上抛。
            // 但静默吞掉会让「没效果」无从排查 —— 同一种异常只报一次。
            if (!tickFailureLogged) {
                tickFailureLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] tick 异常(此后同类静默):" + failure);
            }
        }
    }

    /** 分项开关(供采样器在树侧判断哪几项要采;关掉的项不需要遍历方块)。 */
    public static boolean wantsSubmersion() {
        return AcousticsSettings.isSubmersion();
    }

    public static boolean wantsEnclosure() {
        return AcousticsSettings.isEnclosure();
    }

    public static boolean wantsRainfall() {
        return AcousticsSettings.isRainfall();
    }

    /** 曲目切换/seek:清掉滤波与延迟线里上一首的余音。 */
    public static void onTrackChanged() {
        try {
            SceneAcoustics.flush();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 尾槽处理器:引擎 → 这里 → 声卡。引擎缓冲本来就是 double,直接透传给
     * {@link SceneAcoustics}(它按 double 工作);任何异常在这里熔断成直通,
     * 绝不带崩播放循环。
     */
    private static final class OutputChain implements SynthesisEngine.OutputProcessor {

        @Override
        public void process(double[] interleaved, int channels, int sampleRate) {
            if (interleaved == null || channels <= 0 || interleaved.length == 0) {
                return;
            }
            try {
                SceneAcoustics.render(interleaved, channels, sampleRate);
            } catch (Throwable failure) {
                // 声场修饰挂了就永久直通;不打断播放。
                System.out.println("[MuoniumPlayer/Acoustics] 处理异常,已直通:" + failure);
                Engine.getEngine().setTrailingOutputProcessor(null);
            }
        }
    }

    /** 树侧实现:从当前游戏状态采一帧环境。 */
    public interface ListenerSampler {
        ListenerSample capture();
    }
}
