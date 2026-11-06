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

    /**
     * 采样节流窗口(毫秒)。两档自适应 —— 照 Wwise 的 CPU Limit Mode 思路:
     * 移动/环境在变时短周期快跟上,站定后拉长省 CPU。
     *
     * <p>为什么不再固定 200ms:玩家走进山洞是「几秒内完成的瞬态」,而混响
     * 参数一阶收敛另要 0.3s、抽头组交叉淡化还要 0.4s —— 采样周期叠上去,
     * 感知延迟能到 2s。移动时压到 150ms 是这条链上最便宜的一刀。</p>
     */
    private static final long INTERVAL_MOVING_MS = 150;
    /** 站定后的长周期:环境不再变,射线只用来兜「方块被挖了」这类延迟变化。 */
    private static final long INTERVAL_SETTLED_MS = 500;
    /** 站定判据(毫秒没挪动):连续这么久位置不变就切长周期。 */
    private static final long SETTLE_AFTER_MS = 1000;

    private static long lastSampleMs;
    /** 最近一次观察到的采样原点(仅游戏线程读写):判站定用。 */
    private static double lastOriginX, lastOriginY, lastOriginZ;
    /** 上次位置变化/首次采样的时刻(毫秒)。 */
    private static long lastMoveMs;
    /** 当前是否处于站定态(仅游戏线程读写)。 */
    private static boolean settled;

    /** 采样任务在飞标记(latest-wins 的闸):true = 后台还有一轮没跑完。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SAMPLING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 「跑完这一轮还要再来一轮」的标志。
     *
     * <p>修前是 busy-skip:采样在飞时新 tick 直接跳过 —— 若射线要 250ms,
     * 实际周期就变成「等 200 + 跑 250 + 再等 200」≈ 400ms,移动时白白慢
     * 一倍。改成 latest-wins:在飞时只记一个待办位,任务结束时若置位就
     * 立刻续投一轮。最坏延迟回到「一次射线时长」。</p>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean PENDING =
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
                lastSettleProbeMs = 0L;
                lastMoveMs = 0L;
                PENDING.set(false);
                settled = false;
                lastPlan = null;   // 关闭期间不留基准,重开首帧不算「跨房间」
                SceneAcoustics.clearTarget();
                return;
            }
            final long now = System.currentTimeMillis();
            updateSettleState(now, sampler);
            final long interval = settled ? INTERVAL_SETTLED_MS : INTERVAL_MOVING_MS;if (now - lastSampleMs < interval) {
                return; // 节流窗口内不投递;目标量保持上一帧,收敛交给音频线程。
            }
            lastSampleMs = now;
            submitSample(sampler);
        } catch (Throwable failure) {
            // 投递失败(池关闭等)不当回事:下个 tick 再试。
            SAMPLING.set(false);
        }
    }

    /**
     * 站定判定(仅游戏线程):位置连续 {@link #SETTLE_AFTER_MS} 没变才算站定。
     *
     * <p>只看位置不看视角 —— 转头会改声像但不会改房间几何,而探针的几何
     * 缓存本来就在每次采样重算声像,所以转头不需要加密采样。</p>
     *
     * <p>判定本身每 {@link #SETTLE_PROBE_MS} 才做一次:读玩家眼位在 MC 里会
     * 新建 Vec3,没必要每个 tick 都付这笔钱。200ms 的判定粒度对「1s 才算
     * 站定」的阈值绰绰有余。</p>
     */
    private static long lastSettleProbeMs;
    private static final long SETTLE_PROBE_MS = 200;

    private static void updateSettleState(long now, ListenerSampler sampler) {
        if (now - lastSettleProbeMs < SETTLE_PROBE_MS && lastMoveMs != 0L) {
            return;   // 判定窗口内复用上次结论(首帧 lastMoveMs=0 一定判一次)
        }
        lastSettleProbeMs = now;
        final double[] pose;
        try {
            pose = sampler.originMeters();
        } catch (Throwable ignored) {
            settled = false;
            return;
        }
        if (pose == null || pose.length < 3) {
            // 树没提供原点:不判站定,一律快周期。
            settled = false;
            return;
        }
        final double dx = pose[0] - lastOriginX;
        final double dy = pose[1] - lastOriginY;
        final double dz = pose[2] - lastOriginZ;
        // 0.5 格:与探针的 STATIONARY_SQ 同一量级 —— 低于它几何不会变。
        if (Double.isNaN(dx) || dx * dx + dy * dy + dz * dz > 0.25) {
            lastOriginX = pose[0];
            lastOriginY = pose[1];
            lastOriginZ = pose[2];
            lastMoveMs = now;
            settled = false;
            return;
        }
        settled = now - lastMoveMs >= SETTLE_AFTER_MS;
    }

    /**
     * 投递一轮采样。在飞时不排队、只置待办位(latest-wins):任务结束时
     * 若发现有待办就立刻续投 —— 移动中「采样要 250ms」时周期不再翻倍。
     */
    private static void submitSample(ListenerSampler sampler) {
        if (!SAMPLING.compareAndSet(false, true)) {
            PENDING.set(true);
            return;
        }
        PENDING.set(false);
        submitNow(sampler);
    }

    private static void submitNow(final ListenerSampler sampler) {
        try {
            BackgroundWork.submit(() -> {
                try {
                    runSample(sampler);
                } finally {
                    SAMPLING.set(false);
                    // 待办位:这一轮期间有 tick 想要新数据 → 立刻再来一轮。
                    // 只在还开着且采得到时续投(采样器可能已失效)。
                    if (PENDING.compareAndSet(true, false)
                            && AcousticsSettings.isEnabled()) {
                        lastSampleMs = System.currentTimeMillis();
                        submitSample(sampler);
                    }
                }
            });
        } catch (Throwable failure) {
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
            // 变化幅度驱动的过渡时长(阶段二):与上一帧比,跨房间要大改就快收敛。
            final RoomPlan withTransition = withTransition(plan);
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
                        + " → " + withTransition);
            } else if (plan.wet < 0.02f) {
                roomLogged = false;
            }
            SceneAcoustics.applyTarget(withTransition);
        } catch (Throwable failure) {
            // 环境采样是锦上添花:任何异常都宁可让效果僵在上一帧,也不往上抛。
            // 但静默吞掉会让「没效果」无从排查 —— 同一种异常只报一次。
            if (!sampleFailureLogged) {
                sampleFailureLogged = true;
                System.out.println("[MuoniumPlayer/Acoustics] 采样异常(此后同类静默):" + failure);
            }
        }
    }

    // ---- 变化幅度 → 过渡时长(阶段二) ----

    /** 小变化(房间内的抖动):沿用原有的慢收敛,参数不追噪声。 */
    private static final float TRANSITION_SMALL_SEC = 0.35f;
    /** 大变化(跨房间):快跟上脚步。行业做法是跨房间 1.5s 量级的 Snapshot
     *  过渡,但那条链还叠着 0.4s 抽头交叉淡化,这里取 0.18s 让总感知延迟
     *  落在 0.6s 内。 */
    private static final float TRANSITION_LARGE_SEC = 0.18f;
    /** 判「大变化」的阈值:围合度或湿声的相对跳变。围合度 0.25 大约等于
     *  「从屋里跨出门」;湿声 0.2 是「本来几乎没有混响」。 */
    private static final float BIG_ENCLOSURE_DELTA = 0.25f;
    private static final float BIG_WET_DELTA = 0.20f;
    /** 判「大变化」的衰减时长相对跳变(进出大空间)。 */
    private static final float BIG_DECAY_RATIO = 1.8f;

    /** 上一帧的关键量(仅采样线程读写):跨房间判定的基准。 */
    private static RoomPlan lastPlan;

    /**
     * 给计划补一个过渡时长。纯比较,不改任何声学量。
     *
     * <p>为什么不放进 {@link SceneResponse}:那个类是纯函数、可单测,加
     * 跨帧记忆会破坏它的定位。这里做的是「与上一帧比较」,天然属于运行时。</p>
     */
    private static RoomPlan withTransition(RoomPlan plan) {
        final RoomPlan prev = lastPlan;
        lastPlan = plan;
        float sec = TRANSITION_SMALL_SEC;
        if (prev != null && looksLikeRoomChange(prev, plan)) {
            sec = TRANSITION_LARGE_SEC;
        }
        return new RoomPlan(plan.bandHz, plan.wet, plan.dryGain, plan.loudness,
                plan.predelaySec, plan.decaySec, plan.tone,
                plan.decayLowSec, plan.decayHighSec, plan.diffusion,
                plan.tapDelaySec, plan.tapGainL, plan.tapGainR,
                plan.directGain, plan.directCutoffHz, plan.directPan,
                plan.directDistanceMeters,
                plan.useOpenAlReverb, plan.reverbDecayHfRatio, plan.reverbDiffusion,
                plan.hasSpeakerAnchor, plan.speakerX, plan.speakerY, plan.speakerZ,
                sec);
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
         * 采样原点(米,{x,y,z}):玩家眼位,或世界声源锚点。只用于<b>站定判定</b>
         * —— 位置连续 1s 不变就把采样周期拉长省 CPU。
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
