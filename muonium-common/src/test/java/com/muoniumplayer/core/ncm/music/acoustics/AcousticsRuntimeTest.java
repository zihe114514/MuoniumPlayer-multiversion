package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 可控时钟与提交队列覆盖采样生命周期;不读配置、不启动引擎、不访问 Minecraft。 */
class AcousticsRuntimeTest {

    @Test
    @DisplayName("关闭后迟到的水下结果不得恢复目标或续投旧待办")
    void lateResultAfterDisableIsIgnored() {
        Harness h = new Harness();
        Object world = new Object();
        Probe old = h.probe(world, true);
        Probe pending = h.probe(world, true);
        h.tick(true, old, 1f);
        h.now.addAndGet(150);
        h.tick(true, pending, 1f);
        old.duringCapture = () -> h.tick(false, old, 0f);

        h.executor.runNext();

        assertIdle(h.target.current);
        assertEquals(0, h.target.applied.size());
        assertEquals(0, pending.captures.get());
        assertEquals(1, h.executor.submissions());
        assertEquals(0, h.executor.queued());
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("在飞期间关闭再重开:闸保持,旧结果不得覆盖新 epoch")
    void disableAndReenableKeepsGateUntilOldCaptureReturns() {
        Harness h = new Harness();
        Object world = new Object();
        Probe old = h.probe(world, true);
        Probe latest = h.probe(world, false);
        int[] submissionsDuringCapture = new int[1];
        old.duringCapture = () -> {
            h.tick(false, old, 0f);
            h.tick(true, latest, 0.4f);
            submissionsDuringCapture[0] = h.executor.submissions();
        };
        h.tick(true, old, 1f);

        h.executor.runNext();

        assertEquals(1, submissionsDuringCapture[0], "失效不能提早释放在飞闸");
        assertIdle(h.target.current);
        assertEquals(0, h.target.applied.size());
        assertEquals(1, h.executor.queued());
        h.executor.runNext();
        assertEquals(List.of(latest.sample), h.target.samples);
        assertIdle(h.target.current);
        assertEquals(1, h.maxCaptures.get());
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("null 上下文每 tick 清目标,旧采样不会在重新进世界后发布")
    void nullSamplerInvalidatesInFlightAndDropsPending() {
        Harness h = new Harness();
        Object world = new Object();
        Probe old = h.probe(world, true);
        old.duringCapture = () -> h.tick(true, null, 1f);
        h.tick(true, old, 1f);
        h.executor.runNext();
        int clears = h.target.clears;
        h.tick(true, null, 1f);
        assertEquals(clears + 1, h.target.clears);
        assertEquals(0, h.target.applied.size());
        assertEquals(0, h.executor.queued());

        Probe latest = h.probe(world, false);
        h.tick(true, latest, 1f);
        h.executor.runNext();
        assertEquals(List.of(latest.sample), h.target.samples);
        assertIdle(h.target.current);
    }

    @Test
    @DisplayName("世界按身份而非 equals 换代,旧采样结束才可续投新世界")
    void contextIdentityChangeRejectsLateResult() {
        Harness h = new Harness();
        Object firstWorld = new String("world");
        Object secondWorld = new String("world");
        Probe old = h.probe(firstWorld, true);
        Probe latest = h.probe(secondWorld, false);
        int[] queuedDuringCapture = new int[1];
        old.duringCapture = () -> {
            h.tick(true, latest, 1f);
            queuedDuringCapture[0] = h.executor.queued();
        };
        h.tick(true, old, 1f);
        h.executor.runNext();

        assertEquals(0, queuedDuringCapture[0]);
        assertEquals(0, h.target.applied.size());
        assertIdle(h.target.current);
        h.executor.runNext();
        assertEquals(List.of(latest.sample), h.target.samples);
        assertEquals(1, h.maxCaptures.get());
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("旧 epoch 的 capture 异常不清新上下文,也不占失败诊断")
    void lateCaptureFailureIsIgnored() {
        Harness h = new Harness();
        Probe old = h.probe(new Object(), true);
        Probe latest = h.probe(new Object(), false);
        old.failure = new IllegalStateException("obsolete capture");
        old.duringCapture = () -> h.tick(true, latest, 1f);
        h.tick(true, old, 1f);
        h.executor.runNext();
        int clears = h.target.clears;
        h.executor.runNext();

        assertEquals(clears, h.target.clears);
        assertEquals(List.of(latest.sample), h.target.samples);
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("在飞只保留最新 sampler 和强度,不能续投旧 sampler 或旧待办")
    void pendingRequestUsesLatestSamplerAndCapturedStrength() {
        Harness h = new Harness();
        Object world = new Object();
        Probe first = h.probe(world, true);
        Probe superseded = h.probe(world, false);
        Probe latest = h.probe(world, true);
        h.tick(true, first, 1f);
        h.now.addAndGet(150);
        h.tick(true, superseded, 0.8f);
        h.now.addAndGet(25);
        h.tick(true, latest, 0.25f);
        assertEquals(1, h.executor.submissions());
        h.executor.runNext();
        h.executor.runNext();

        RoomPlan expected = SceneResponse.respond(latest.sample, 0.25f);
        assertEquals(List.of(first.sample, latest.sample), h.target.samples);
        assertEquals(0, superseded.captures.get());
        assertEquals(1, first.captures.get());
        assertEquals(1, latest.captures.get());
        assertEquals(expected.bandHz, h.target.current.bandHz, 1e-9);
        assertEquals(expected.loudness, h.target.current.loudness, 1e-6f);
        assertEquals(2, h.executor.submissions());
        assertEquals(0, h.executor.queued());
        assertEquals(1, h.maxCaptures.get());
    }

    @Test
    @DisplayName("submit 的 rejected future 必须释放闸并允许下一个 tick 立即重试")
    void rejectedFutureReleasesGateAndRetries() {
        Harness h = new Harness();
        Probe probe = h.probe(new Object(), true);
        h.executor.rejectNext();
        h.tick(true, probe, 1f);
        assertIdle(h.target.current);
        assertEquals(0, probe.captures.get());
        assertEquals(1, h.target.failures.size());

        h.now.incrementAndGet();
        h.tick(true, probe, 1f);
        h.executor.runNext();
        assertEquals(2, h.executor.submissions());
        assertEquals(1, probe.captures.get());
        assertEquals(420.0, h.target.current.bandHz, 1e-6);
    }

    @Test
    @DisplayName("submit 直接抛出也走同一终结路径,不把闸永久占住")
    void thrownSubmissionFailureRetries() {
        Harness h = new Harness();
        Probe probe = h.probe(new Object(), false);
        h.executor.throwNext();
        h.tick(true, probe, 1f);
        h.tick(true, probe, 1f);
        h.executor.runNext();

        assertEquals(2, h.executor.submissions());
        assertEquals(1, probe.captures.get());
        assertEquals(1, h.target.failures.size());
        assertIdle(h.target.current);
    }

    @Test
    @DisplayName("排队 future 先失败:释放 token 后旧 runnable 即使被调用也不得 capture")
    void failedQueuedFutureCannotCaptureAfterRetry() {
        Harness h = new Harness();
        Object world = new Object();
        Probe old = h.probe(world, true);
        Probe latest = h.probe(world, false);
        h.tick(true, old, 1f);
        Job oldJob = h.executor.takeNext();
        oldJob.future.completeExceptionally(new RejectedExecutionException("controlled rejection"));
        h.tick(true, latest, 1f);
        oldJob.run();
        h.executor.runNext();

        assertEquals(0, old.captures.get());
        assertEquals(List.of(latest.sample), h.target.samples);
        assertEquals(1, h.target.failures.size());
        assertEquals(1, h.maxCaptures.get());
    }

    @Test
    @DisplayName("真实任务结束后迟到的旧 future failure 不清新目标或释放新闸")
    void lateFutureFailureCannotClearNewTargetOrReleaseNextTask() {
        Harness h = new Harness();
        Object world = new Object();
        Probe first = h.probe(world, true);
        Probe latest = h.probe(world, false);
        h.tick(true, first, 1f);
        h.now.addAndGet(150);
        h.tick(true, latest, 1f);
        Job firstJob = h.executor.takeNext();
        firstJob.runTask();
        h.executor.runNext();
        RoomPlan newTarget = h.target.current;
        int clears = h.target.clears;

        firstJob.future.completeExceptionally(new IllegalStateException("late completion"));
        assertSame(newTarget, h.target.current);
        assertEquals(clears, h.target.clears);
        assertTrue(h.target.failures.isEmpty());

        h.now.addAndGet(150);
        h.tick(true, latest, 1f);
        firstJob.run();
        h.tick(true, latest, 1f);
        assertEquals(3, h.executor.submissions(), "旧 finally/future 不能拆掉下一轮的闸");
        assertEquals(1, h.executor.queued());
        h.executor.runNext();
        assertEquals(1, first.captures.get());
        assertEquals(2, latest.captures.get());
    }

    @Test
    @DisplayName("在 capture 中 future 失败:立即 fail-open,真实 capture 退出前不放闸")
    void runningFutureFailureKeepsSingleInFlightAndFailsOpenOnce() {
        Harness h = new Harness();
        Object world = new Object();
        Probe first = h.probe(world, true);
        Probe latest = h.probe(world, false);
        h.tick(true, first, 1f);
        Job job = h.executor.takeNext();
        RuntimeException failure = new IllegalStateException("controlled future failure");
        int[] submissionsDuringCapture = new int[1];
        first.duringCapture = () -> {
            h.now.addAndGet(150);
            h.tick(true, latest, 1f);
            job.future.completeExceptionally(failure);
            h.tick(true, latest, 1f);
            submissionsDuringCapture[0] = h.executor.submissions();
        };
        job.run();

        assertEquals(1, submissionsDuringCapture[0]);
        assertEquals(List.of(failure), h.target.failures);
        assertEquals(2, h.target.clears, "future 与 finally 不能重复处理同一次失败");
        assertEquals(0, h.target.applied.size());
        assertIdle(h.target.current);
        h.executor.runNext();
        assertEquals(List.of(latest.sample), h.target.samples);
        assertEquals(1, h.maxCaptures.get());
    }

    @Test
    @DisplayName("当前 capture 失败立即回静息,不能无限保留水下 420Hz,随后可恢复")
    void captureFailureFailsOpenAndRecoversWithoutThrottleDelay() {
        Harness h = new Harness();
        Object world = new Object();
        Probe underwater = h.probe(world, true);
        h.tick(true, underwater, 1f);
        h.executor.runNext();
        assertEquals(420.0, h.target.current.bandHz, 1e-6);
        Probe failing = h.probe(world, false);
        failing.failure = new IllegalArgumentException("controlled capture");
        h.now.addAndGet(150);
        h.tick(true, failing, 1f);
        h.executor.runNext();
        assertIdle(h.target.current);
        assertEquals(List.of(failing.failure), h.target.failures);

        h.tick(true, underwater, 1f);
        h.executor.runNext();
        assertEquals(2, underwater.captures.get());
        assertEquals(420.0, h.target.current.bandHz, 1e-6);
        assertEquals(3, h.executor.submissions());
    }

    @Test
    @DisplayName("排队卡住时 tick 在 2 秒撤销旧目标,排队过老的采样不再执行或发布")
    void watchdogClearsOldTargetAndExpiredQueuedCaptureIsSkipped() {
        Harness h = new Harness();
        Object world = new Object();
        Probe underwater = h.probe(world, true);
        Probe latest = h.probe(world, false);
        h.tick(true, underwater, 1f);
        h.executor.runNext();
        h.now.addAndGet(150);
        h.tick(true, underwater, 1f);
        h.now.set(1000 + AcousticsSamplerScheduler.TARGET_TTL_MS - 1);
        h.tick(true, latest, 1f);
        assertEquals(420.0, h.target.current.bandHz, 1e-6);
        h.now.incrementAndGet();
        h.tick(true, latest, 1f);
        assertIdle(h.target.current);
        assertEquals(2, h.executor.submissions(), "watchdog 不提前拆在飞闸");
        h.now.addAndGet(150);
        h.tick(true, latest, 1f);
        h.executor.runNext();

        assertEquals(1, underwater.captures.get(), "过老的排队采样应在 capture 前跳过");
        assertEquals(1, h.target.applied.size());
        assertIdle(h.target.current);
        h.executor.runNext();
        assertEquals(List.of(underwater.sample, latest.sample), h.target.samples);
        assertIdle(h.target.current);
    }

    @Test
    @DisplayName("执行卡住时 target 按请求时间过期,迟到结果不得延长有效期")
    void stalledCaptureExpiresWithoutReleasingGateOrRepublishing() {
        Harness h = new Harness();
        Object world = new Object();
        Probe underwater = h.probe(world, true);
        Probe stalled = h.probe(world, true);
        Probe latest = h.probe(world, false);
        h.tick(true, underwater, 1f);
        h.executor.runNext();
        h.now.addAndGet(150);
        h.tick(true, stalled, 1f);
        int[] submissionsDuringCapture = new int[1];
        stalled.duringCapture = () -> {
            h.now.addAndGet(AcousticsSamplerScheduler.TARGET_TTL_MS);
            h.tick(true, latest, 1f);
            submissionsDuringCapture[0] = h.executor.submissions();
        };
        h.executor.runNext();

        assertEquals(2, submissionsDuringCapture[0]);
        assertEquals(1, h.target.applied.size());
        assertIdle(h.target.current);
        h.executor.runNext();
        assertEquals(List.of(underwater.sample, latest.sample), h.target.samples);
        assertEquals(1, h.maxCaptures.get());
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("无新 tick 时过老待办也不能续投,下一次新鲜 tick 仍可恢复")
    void expiredPendingRequestIsDroppedAndFreshTickRetries() {
        Harness h = new Harness();
        Object world = new Object();
        Probe old = h.probe(world, true);
        Probe pending = h.probe(world, true);
        Probe latest = h.probe(world, false);
        h.tick(true, old, 1f);
        h.now.addAndGet(150);
        h.tick(true, pending, 1f);
        old.duringCapture = () -> h.now.addAndGet(AcousticsSamplerScheduler.TARGET_TTL_MS);
        h.executor.runNext();

        assertEquals(0, pending.captures.get());
        assertEquals(0, h.executor.queued());
        assertEquals(0, h.target.applied.size());
        h.tick(true, latest, 1f);
        h.executor.runNext();
        assertEquals(List.of(latest.sample), h.target.samples);
    }

    @Test
    @DisplayName("并发 tick 关闭/换世界时实际 capture 保持单在飞")
    void concurrentInvalidationCannotStartSecondCapture() throws Exception {
        Harness h = new Harness();
        Probe old = h.probe(new Object(), true);
        Probe superseded = h.probe(new Object(), true);
        Probe latest = h.probe(new Object(), false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        old.duringCapture = () -> {
            entered.countDown();
            await(release);
        };
        h.tick(true, old, 1f);
        Job job = h.executor.takeNext();
        Thread worker = new Thread(job::run, "acoustics-test-capture");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            h.tick(false, old, 0f);
            h.tick(true, superseded, 1f);
            h.tick(true, latest, 1f);
            assertEquals(1, h.executor.submissions());
            assertEquals(0, h.executor.queued());
            assertEquals(1, h.activeCaptures.get());
            assertIdle(h.target.current);
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertEquals(0, h.activeCaptures.get());
        h.executor.runNext();
        assertEquals(List.of(latest.sample), h.target.samples);
        assertEquals(0, superseded.captures.get());
        assertEquals(1, h.maxCaptures.get());
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("站定沿用长周期,移动恢复快周期;时钟从零起也立即可采")
    void adaptiveThrottleUsesSettledAndMovingIntervals() {
        Harness h = new Harness();
        Probe probe = h.probe(new Object(), false);
        probe.origin = new double[] {0, 0, 0};
        h.now.set(0);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        h.now.set(149);
        h.tick(true, probe, 1f);
        assertEquals(0, h.executor.queued());
        h.now.set(150);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        h.now.set(1000);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        h.now.set(1150);
        h.tick(true, probe, 1f);
        assertEquals(0, h.executor.queued());
        h.now.set(1500);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        probe.origin = new double[] {2, 0, 0};
        h.now.set(1700);
        h.tick(true, probe, 1f);
        h.executor.runNext();

        assertEquals(5, probe.captures.get());
        assertEquals(0, h.executor.queued());
        assertTrue(h.target.failures.isEmpty());
    }

    @Test
    @DisplayName("可选角度跨 ±π 不误判,真正转头/俯仰恢复移动快周期")
    void optionalAnglesUseWrappedDifferenceAndResetSettling() {
        Harness h = new Harness();
        Probe probe = h.probe(new Object(), false);
        probe.origin = new double[] {0, 0, 0, Math.PI - 0.01, 0};
        h.now.set(0);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        h.now.set(1000);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        probe.origin[3] = -Math.PI + 0.01;
        h.now.set(1200);
        h.tick(true, probe, 1f);
        assertEquals(0, h.executor.queued(), "±π 两侧仅差0.02rad,仍应站定");
        probe.origin[3] = -Math.PI + 0.1;
        h.now.set(1400);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        h.now.set(2400);
        h.tick(true, probe, 1f);
        h.executor.runNext();
        probe.origin[4] = 0.1;
        h.now.set(2600);
        h.tick(true, probe, 1f);
        h.executor.runNext();

        assertEquals(5, probe.captures.get());
        assertEquals(0, h.executor.queued());
    }

    @Test
    @DisplayName("过渡只改时长,绕射标记与完整计划字段都保留")
    void transitionPreservesDiffractionAndPlanFields() {
        RoomPlan plan = new RoomPlan(7000, 0.32f, 0.83f, 0.91f, 0.013f, 0.6f, 0.24f,
                0.81f, 0.42f, 0.68f,
                new float[] {0.02f, 0.03f}, new float[] {0.12f, 0.08f}, new float[] {0.07f, 0.11f},
                0.62f, 3800, -0.45f, 23f,
                true, 0.7f, 0.6f, true, 10, 20, 30, 0f, true);
        RoomPlan result = AcousticsRuntime.withTransition(plan, null);

        assertEquals(0.35f, result.transitionSec);
        assertTrue(result.diffracted);
        assertEquals(plan.bandHz, result.bandHz);
        assertEquals(plan.wet, result.wet);
        assertEquals(plan.dryGain, result.dryGain);
        assertEquals(plan.loudness, result.loudness);
        assertEquals(plan.predelaySec, result.predelaySec);
        assertEquals(plan.decaySec, result.decaySec);
        assertEquals(plan.tone, result.tone);
        assertEquals(plan.decayLowSec, result.decayLowSec);
        assertEquals(plan.decayHighSec, result.decayHighSec);
        assertEquals(plan.diffusion, result.diffusion);
        assertEquals(plan.tapCount, result.tapCount);
        assertArrayEquals(plan.tapDelaySec, result.tapDelaySec);
        assertArrayEquals(plan.tapGainL, result.tapGainL);
        assertArrayEquals(plan.tapGainR, result.tapGainR);
        assertEquals(plan.directGain, result.directGain);
        assertEquals(plan.directCutoffHz, result.directCutoffHz);
        assertEquals(plan.directPan, result.directPan);
        assertEquals(plan.directDistanceMeters, result.directDistanceMeters);
        assertEquals(plan.useOpenAlReverb, result.useOpenAlReverb);
        assertEquals(plan.reverbDecayHfRatio, result.reverbDecayHfRatio);
        assertEquals(plan.reverbDiffusion, result.reverbDiffusion);
        assertEquals(plan.hasSpeakerAnchor, result.hasSpeakerAnchor);
        assertEquals(plan.speakerX, result.speakerX);
        assertEquals(plan.speakerY, result.speakerY);
        assertEquals(plan.speakerZ, result.speakerZ);
        assertEquals(0.18f, AcousticsRuntime.withTransition(plan, RoomPlan.idle()).transitionSec);
    }

    @Test
    @DisplayName("旧采样器的 contextKey 与原点都可不提供")
    void listenerSamplerDefaultsStayNeutral() {
        AcousticsRuntime.ListenerSampler sampler = () -> null;
        assertNull(sampler.contextKey());
        assertNull(sampler.originMeters());
    }

    private static void assertIdle(RoomPlan plan) {
        assertEquals(SceneAcoustics.bandOpenHz(), plan.bandHz, 1e-9);
        assertEquals(0f, plan.wet);
        assertEquals(1f, plan.dryGain);
        assertEquals(1f, plan.loudness);
        assertEquals(0, plan.tapCount);
        assertEquals(1f, plan.directGain);
        assertEquals(DirectPath.FC_OPEN, plan.directCutoffHz);
        assertFalse(plan.hasSpeakerAnchor);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("capture release timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class Harness {
        final AtomicLong now = new AtomicLong(1000);
        final AtomicInteger activeCaptures = new AtomicInteger();
        final AtomicInteger maxCaptures = new AtomicInteger();
        final ManualExecutor executor = new ManualExecutor();
        final Target target = new Target();
        final AcousticsSamplerScheduler scheduler = new AcousticsSamplerScheduler(
                now::get, executor::submit, target, AcousticsRuntime::withTransition, 150, 500, 1000);

        void tick(boolean enabled, AcousticsRuntime.ListenerSampler sampler, float strength) {
            scheduler.onClientTick(enabled, sampler, strength);
        }

        Probe probe(Object context, boolean submerged) {
            return new Probe(this, context, new Sample(submerged));
        }
    }

    private static final class Probe implements AcousticsRuntime.ListenerSampler {
        final Harness owner;
        final Object context;
        final ListenerSample sample;
        final AtomicInteger captures = new AtomicInteger();
        Runnable duringCapture;
        RuntimeException failure;
        double[] origin;

        Probe(Harness owner, Object context, ListenerSample sample) {
            this.owner = owner;
            this.context = context;
            this.sample = sample;
        }

        @Override
        public ListenerSample capture() {
            captures.incrementAndGet();
            int active = owner.activeCaptures.incrementAndGet();
            owner.maxCaptures.accumulateAndGet(active, Math::max);
            try {
                if (duringCapture != null) duringCapture.run();
                if (failure != null) throw failure;
                return sample;
            } finally {
                owner.activeCaptures.decrementAndGet();
            }
        }

        @Override public Object contextKey() { return context; }
        @Override public double[] originMeters() { return origin; }
    }

    private static final class Sample implements ListenerSample {
        private static final float[] EMPTY = new float[0];
        private final boolean submerged;

        Sample(boolean submerged) {
            this.submerged = submerged;
        }

        @Override public boolean submerged() { return submerged; }
        @Override public boolean openSky() { return true; }
        @Override public float enclosure() { return 0f; }
        @Override public float precipitation() { return 0f; }
        @Override public float firstHitMeanMeters() { return NO_HIT; }
        @Override public float bounceSegmentMeanMeters() { return 0f; }
        @Override public float meanReflectivity() { return 0f; }
        @Override public float reachableFraction() { return 0f; }
        @Override public int tapCount() { return 0; }
        @Override public float[] tapDelaysSec() { return EMPTY; }
        @Override public float[] tapGains() { return EMPTY; }
        @Override public float[] tapPans() { return EMPTY; }
        @Override public EnergyField bandHistogram() { return null; }
    }

    private static final class Target implements AcousticsSamplerScheduler.TargetSink {
        volatile RoomPlan current = RoomPlan.idle();
        final List<RoomPlan> applied = new ArrayList<>();
        final List<ListenerSample> samples = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        int clears;

        @Override
        public void apply(ListenerSample sample, RoomPlan plan) {
            current = plan;
            applied.add(plan);
            samples.add(sample);
        }

        @Override
        public void clear() {
            clears++;
            current = RoomPlan.idle();
        }

        @Override
        public void failed(Throwable failure) {
            failures.add(failure);
        }
    }

    private static final class ManualExecutor {
        private final ArrayDeque<Job> queue = new ArrayDeque<>();
        private boolean rejectNext;
        private boolean throwNext;
        private int submissions;

        synchronized CompletableFuture<Void> submit(Runnable runnable) {
            submissions++;
            if (throwNext) {
                throwNext = false;
                throw new RejectedExecutionException("controlled thrown rejection");
            }
            if (rejectNext) {
                rejectNext = false;
                return CompletableFuture.failedFuture(new RejectedExecutionException("controlled rejection"));
            }
            Job job = new Job(runnable);
            queue.addLast(job);
            return job.future;
        }

        synchronized void rejectNext() { rejectNext = true; }
        synchronized void throwNext() { throwNext = true; }
        synchronized int submissions() { return submissions; }
        synchronized int queued() { return queue.size(); }

        synchronized Job takeNext() {
            assertFalse(queue.isEmpty(), "没有可执行的采样任务");
            return queue.removeFirst();
        }

        void runNext() {
            takeNext().run();
        }
    }

    private static final class Job {
        final Runnable runnable;
        final CompletableFuture<Void> future = new CompletableFuture<>();

        Job(Runnable runnable) {
            this.runnable = runnable;
        }

        void runTask() {
            runnable.run();
        }

        void run() {
            try {
                runTask();
                future.complete(null);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }
    }
}
