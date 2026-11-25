package com.muoniumplayer.core.ncm.music.acoustics;

import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import org.jetbrains.annotations.Nullable;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.processing.sound.Engine;
/**
 * 场景声学的运行时:把「树侧每 tick 采一帧听者环境」接到「音频线程的尾槽处理器」。
 *
 * <h2>线程与闸门</h2>
 * <ul>
 *   <li><b>游戏线程</b>:{@link #onClientTick(ListenerSampler)} 由各版本树的共享 tick
 *       桥调进来。每 tick 检查开关/上下文、目标有效期与节流,把最新采样请求
 *       交给后台池;主线程不做方块读和射线采样。异常只令目标回静息。</li>
 *   <li><b>后台线程</b>:消费树侧采样器 {@code capture()},把一帧
 *       {@link ListenerSample} 交给推导。调度锁只保护 epoch、最新待办和发布;
 *       几何采样在锁外,同刻至多一轮。关闭/换世界不提前拆闸,旧结果与旧异常
 *       都不能写目标;排队或执行超过 2s 的结果也不能重新发布。</li>
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

    private static volatile boolean installed;

    /**
     * 采样节流窗口(毫秒)。两档自适应 —— 照 Wwise 的 CPU Limit Mode 思路:
     * 移动/环境在变时短周期快跟上,站定后拉长省 CPU。
     *
     * <p>移动或转头时缩短到 150ms,站定后拉长到 500ms。参数收敛与抽头
     * 淡化独立推进;新计划不会重启正在进行的反射淡化。</p>
     */
    private static final long INTERVAL_MOVING_MS = 150;
    /** 站定后的长周期:环境不再变,射线只用来兜「方块被挖了」这类延迟变化。 */
    private static final long INTERVAL_SETTLED_MS = 500;
    /** 站定判据(毫秒没挪动):连续这么久位置不变就切长周期。 */
    private static final long SETTLE_AFTER_MS = 1000;

    /** latest-wins 的 PENDING 请求槽与在飞 token 都由调度器的锁保护。 */
    private static final AcousticsSamplerScheduler SAMPLER = new AcousticsSamplerScheduler(
            () -> System.nanoTime() / 1_000_000L, BackgroundWork::submit,
            new AcousticsSamplerScheduler.TargetSink() {
                @Override
                public void apply(ListenerSample sample, RoomPlan plan) {
                    SceneAcoustics.applyTarget(plan);
                    logSample(sample, plan);
                }

                @Override
                public void clear() {
                    roomLogged = false;
                    SceneAcoustics.clearTarget();
                }

                @Override
                public void failed(Throwable failure) {
                    logFailure(failure);
                }
            }, AcousticsRuntime::withTransition,
            INTERVAL_MOVING_MS, INTERVAL_SETTLED_MS, SETTLE_AFTER_MS);
    /** 一次性诊断(只在调度锁内):首次成功采样 / 首次采样异常 / 房间跨越。 */
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
                com.muoniumplayer.core.ncm.music.AudioPlayer.ensureAudioBackend();
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
            final boolean enabled = AcousticsSettings.isEnabled();
            final float strength = enabled && sampler != null ? AcousticsSettings.strength() : 0f;
            SAMPLER.onClientTick(enabled, sampler, strength);
            if (enabled && sampler != null) {
                SceneAcoustics.prepareSpatialAudio();
            }
        } catch (Throwable failure) {
            // tick 失败只撤销 epoch/目标;真实 capture 的结束才有权释放在飞闸。
            try {
                SAMPLER.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 只诊断已获准发布的采样,与开关清态共享调度锁。 */
    private static void logSample(ListenerSample sample, RoomPlan plan) {
        try {
            if (sample != null && !sampleLogged) {
                sampleLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] 首次采样: 浸液=" + sample.submerged()
                        + " 天光=" + sample.openSky() + " 围合=" + String.format("%.2f", sample.enclosure())
                        + " 降水=" + sample.precipitation());
            }
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
        } catch (Throwable ignored) {
            // 诊断失败不能撤回已成功发布的声学计划。
        }
    }

    private static void logFailure(Throwable failure) {
        if (!sampleFailureLogged) {
            sampleFailureLogged = true;
            System.out.println("[MuoniumPlayer/Acoustics] 采样异常,已回静息(此后静默):" + failure);
        }
    }

    // ---- 变化幅度 → 过渡时长(阶段二) ----

    /** 小变化(房间内的抖动):沿用原有的慢收敛,参数不追噪声。 */
    private static final float TRANSITION_SMALL_SEC = 0.35f;
    /** 大变化(跨房间):采用 0.18s 参数收敛,不改变抽头组自己的交叉淡化。 */
    private static final float TRANSITION_LARGE_SEC = 0.18f;
    /** 判「大变化」的阈值:围合度或湿声的相对跳变。围合度 0.25 大约等于
     *  「从屋里跨出门」;湿声 0.2 是「本来几乎没有混响」。 */
    private static final float BIG_ENCLOSURE_DELTA = 0.25f;
    private static final float BIG_WET_DELTA = 0.20f;
    /** 判「大变化」的衰减时长相对跳变(进出大空间)。 */
    private static final float BIG_DECAY_RATIO = 1.8f;

    /**
     * 给计划补一个过渡时长。纯比较,不改任何声学量。
     *
     * <p>基准由调度器在锁内持有,只有已接纳的本 epoch 结果能推进它。</p>
     */
    static RoomPlan withTransition(RoomPlan plan, RoomPlan prev) {
        float sec = TRANSITION_SMALL_SEC;
        if (prev != null && looksLikeRoomChange(prev, plan)) {
            sec = TRANSITION_LARGE_SEC;
        }
        return plan.withTransition(sec);
    }

    /** 两帧之间是否算「换了个地方」。任一项跨过阈值即可,取宽松口径 ——
     *  宁可偶尔快一点(听感是「跟得上」),不要漏判(听感是「卡住」)。 */
    private static boolean looksLikeRoomChange(RoomPlan a, RoomPlan b) {
        if (Math.abs(b.wet - a.wet) >= BIG_WET_DELTA) {
            return true;
        }
        // 湿声从无到有/从有到无:进出房间最典型的一跳
        if ((a.wet < 0.02f) != (b.wet < 0.02f)) {
            return true;
        }
        // 衰减时长的相对跳变:小房间 ↔ 大厅
        final float shortSide = Math.max(0.02f, Math.min(a.decaySec, b.decaySec));
        if (Math.max(a.decaySec, b.decaySec) / shortSide >= BIG_DECAY_RATIO) {
            return true;
        }
        // 抽头条数剧变:几何结构换了(开阔 ↔ 大量可达反射)
        final int tapGap = Math.abs(a.tapCount - b.tapCount);
        return tapGap >= 12;
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

        /**
         * 中立上下文身份(世界/会话等):每 tick 在游戏线程读取,按引用身份比较。
         * 同一上下文返回同一对象,更换身份立即撤销旧结果;null = 未提供上下文键。
         */
        @Nullable
        default Object contextKey() {
            return null;
        }

        /**
         * 听者位置(米,{x,y,z}),可选追加 {yaw,pitch}(弧度)。只用于<b>站定判定</b>,
         * 不是射线声源原点;位置/角度连续 1s 不变才把采样周期拉长省 CPU。
         *
         * <p>default 返回 null = 「不参与站定判定」,此时一律按快周期采样
         * (等价于修前的固定周期,只是周期更短)。树侧覆盖它才有自适应收益;
         * 不覆盖也不会错。</p>
         */
        @Nullable
        default double[] originMeters() {
            return null;
        }
    }
}
