package com.muoniumplayer.core.ncm.music.acoustics;

import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.processing.sound.Engine;
/**
 * 场景声学的运行时:把「树侧每 tick 采一帧听者环境」接到「音频线程的尾槽处理器」。
 *
 * <h2>线程与闸门</h2>
 * <ul>
 *   <li><b>游戏线程</b>:{@link #onClientTick(Object)} 由各版本树的共享 tick 桥调进来
 *       (参数是树的 {@code Minecraft} 实例)。每 tick 只做:开关判断、节流、
 *       <b>把采样任务投递到后台池</b>(latest-wins)—— 主线程零方块读,射线
 *       的几十上百次体素投射全部发生在 MIN_PRIORITY 工作线程上,画面对采样
 *       完全无感。全部 try/Throwable 包住 —— tick 上的任何异常都不能带崩游戏。</li>
 *   <li><b>后台线程</b>:消费树侧采样器 {@code capture()}(那里才知道树的
 *       Minecraft),把一帧 {@link ListenerSample} 交给推导,结果写进
 *       {@link SceneAcoustics} 的 volatile 目标量。同一时刻至多一个采样任务在飞
 *       (AtomicBoolean 闸):任务没跑完时新 tick 的投递被<b>跳过</b>(busy-skip)
 *       —— 采样周期拉长为 max(200ms, 采样+排队时长),没有待办记忆,但下一个
 *       达标 tick 必然重采,不存在永久丢失;采样幂等,乱序无意义。</li>
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

    /** 同一环境连续 tick 的复用窗口(毫秒):射线采样要遍历方块,后台线程跑也
     *  不白给;环境本身变化尺度是秒级,200ms 足够平滑。 */
    private static final long SAMPLE_INTERVAL_MS = 200;

    private static long lastSampleMs;
    /** 采样任务在飞标记(latest-wins 的闸):true = 后台还有一轮没跑完。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SAMPLING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 一次性诊断:首次成功采样 / 首次采样异常 / 房间跨越,各自只打一条。 */
    private static boolean sampleLogged;
    private static boolean sampleFailureLogged;
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
     * 引擎线程卡死,表现就是「下载解码都完成、进度条不走」。tick 只投递采样任务,
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
     * <p><b>本方法不触碰音频引擎</b> —— 不创建、不查询、不挂接。也不做几何采样:
     * 节流窗口到期时把采样投到后台池(latest-wins),游戏线程立刻返回。后台
     * 任务里再完成「采一帧 → 推导 → 写目标量」的全程。</p>
     *
     * @param sampler 树侧的采样器;传 {@code null} 表示「当前采不到」(主菜单等),
     *                等价于把目标量回静息。
     */
    public static void onClientTick(ListenerSampler sampler) {
        try {
            if (!AcousticsSettings.isEnabled() || sampler == null) {
                // 关闭/采不到(主菜单):目标量回静息。闸不主动清 —— 在飞任务
                // 的 finally 自会复位;清早了会与在飞任务竞态出第二个采样
                // (BackgroundWork 并发 2),破坏「至多一个在飞」的契约。
                // lastSampleMs 清零:下次进世界/重开开关立刻可采。
                lastSampleMs = 0L;
                SceneAcoustics.clearTarget();
                return;
            }
            final long now = System.currentTimeMillis();
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) {
                return; // 复用窗口内不投递;目标量保持上一帧,收敛交给音频线程。
            }
            lastSampleMs = now;
            if (!SAMPLING.compareAndSet(false, true)) {
                return; // 上一轮还没跑完:本轮跳过(latest-wins 由闸语义保证)
            }
            final ListenerSampler task = sampler;
            BackgroundWork.submit(() -> {
                try {
                    runSample(task);
                } finally {
                    SAMPLING.set(false);
                }
            });
        } catch (Throwable failure) {
            // 投递失败(池关闭等)不当回事:下个 tick 再试。
            SAMPLING.set(false);
        }
    }

    /** 后台线程:采一帧 → 诊断 → 推导 → 写目标量。任何异常静默跳过本轮。 */
    private static void runSample(ListenerSampler sampler) {
        try {
            ListenerSample sample = sampler.capture();
            if (sample != null && !sampleLogged) {
                sampleLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] 首次采样: 浸液=" + sample.submerged()
                        + " 天光=" + sample.openSky() + " 围合=" + String.format("%.2f", sample.enclosure())
                        + " 降水=" + sample.precipitation());
            }
            final RoomPlan plan = SceneResponse.respond(sample, AcousticsSettings.strength());
            // 房间跨越诊断:湿声从静息爬过可闻阈时打一条 —— 实机一眼看出
            // 探针有没有「看见」房间,数值合不合理(回静息复位,可反复报)。
            if (plan.wet >= ROOM_LOG_WET && sample != null && !roomLogged) {
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
            if (!sampleFailureLogged) {
                sampleFailureLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] 采样异常(此后同类静默):" + failure);
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
