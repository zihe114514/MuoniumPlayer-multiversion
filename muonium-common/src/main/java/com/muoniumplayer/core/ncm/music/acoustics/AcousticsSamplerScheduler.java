package com.muoniumplayer.core.ncm.music.acoustics;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 中立采样调度:tick 记最新请求,后台采样,只允许当前 epoch 的新鲜结果交付。
 *
 * <p>调度状态和目标发布共用一把锁;capture/推导/submit 都在锁外。失效和超时
 * 只撤销目标,不释放仍在执行的采样闸。音频线程不调用本类。</p>
 */
final class AcousticsSamplerScheduler {

    static final long TARGET_TTL_MS = 2000;
    private static final long SETTLE_PROBE_MS = 200;
    private static final double TURN_THRESHOLD_RAD = 0.05;

    private final Object lock = new Object();
    private final LongSupplier clock;
    private final Function<Runnable, CompletableFuture<Void>> submitter;
    private final TargetSink target;
    private final BiFunction<RoomPlan, RoomPlan, RoomPlan> transition;
    private final long movingIntervalMs;
    private final long settledIntervalMs;
    private final long settleAfterMs;

    // 以下状态只在 lock 内读写,包括 future 回执与 tick 的交接。
    private long epoch;
    private boolean active;
    private Object context;
    private Task inFlight;
    private Request pending;
    private boolean hasSubmitted;
    private long lastSampleMs;
    private boolean hasTarget;
    private long targetRequestedMs;
    private RoomPlan lastPlan;

    private boolean hasOrigin;
    private boolean hasAngles;
    private boolean hasSettleProbe;
    private double lastOriginX, lastOriginY, lastOriginZ;
    private double lastYaw, lastPitch;
    private long lastMoveMs;
    private long lastSettleProbeMs;
    private boolean settled;

    AcousticsSamplerScheduler(LongSupplier clock,
                              Function<Runnable, CompletableFuture<Void>> submitter,
                              TargetSink target,
                              BiFunction<RoomPlan, RoomPlan, RoomPlan> transition,
                              long movingIntervalMs, long settledIntervalMs, long settleAfterMs) {
        this.clock = Objects.requireNonNull(clock);
        this.submitter = Objects.requireNonNull(submitter);
        this.target = Objects.requireNonNull(target);
        this.transition = Objects.requireNonNull(transition);
        this.movingIntervalMs = movingIntervalMs;
        this.settledIntervalMs = settledIntervalMs;
        this.settleAfterMs = settleAfterMs;
    }

    void onClientTick(boolean enabled, AcousticsRuntime.ListenerSampler sampler, float strength) {
        final Task next;
        synchronized (lock) {
            if (!enabled || sampler == null) {
                invalidateLocked();
                return;
            }
            final Object key = sampler.contextKey();
            if (!active || context != key) {
                invalidateLocked();
                active = true;
                context = key;
            }
            final long now = clock.getAsLong();
            if (hasTarget && expired(now, targetRequestedMs)) {
                clearTargetLocked();
            }
            updateSettleStateLocked(now, sampler);
            final long interval = settled ? settledIntervalMs : movingIntervalMs;
            if (!hasSubmitted || now - lastSampleMs >= interval) {
                // 单待办槽保留真实的最新采样器与 tick 强度,不是续投旧 sampler。
                pending = new Request(epoch, key, sampler, strength, now);
            }
            next = takePendingLocked(now);
        }
        submit(next);
    }

    /** tick 入口自身失败也必须撤销 epoch,不能直接拆掉实际在飞的闸。 */
    void invalidate() {
        synchronized (lock) {
            invalidateLocked();
        }
    }

    private void invalidateLocked() {
        epoch++;
        active = false;
        context = null;
        pending = null;
        hasSubmitted = false;
        hasOrigin = false;
        hasAngles = false;
        hasSettleProbe = false;
        settled = false;
        clearTargetLocked();
    }

    private void clearTargetLocked() {
        hasTarget = false;
        lastPlan = null;
        target.clear();
    }

    private void updateSettleStateLocked(long now, AcousticsRuntime.ListenerSampler sampler) {
        if (hasSettleProbe && now - lastSettleProbeMs < SETTLE_PROBE_MS) {
            return;
        }
        hasSettleProbe = true;
        lastSettleProbeMs = now;
        final double[] pose;
        try {
            pose = sampler.originMeters();
        } catch (Throwable ignored) {
            hasOrigin = false;
            hasAngles = false;
            settled = false;
            return;
        }
        if (pose == null || pose.length < 3
                || !Double.isFinite(pose[0]) || !Double.isFinite(pose[1]) || !Double.isFinite(pose[2])) {
            hasOrigin = false;
            hasAngles = false;
            settled = false;
            return;
        }
        final double dx = pose[0] - lastOriginX;
        final double dy = pose[1] - lastOriginY;
        final double dz = pose[2] - lastOriginZ;
        final boolean angles = pose.length >= 5 && Double.isFinite(pose[3]) && Double.isFinite(pose[4]);
        final boolean turned = angles && (!hasAngles
                || Math.abs(Math.IEEEremainder(pose[3] - lastYaw, 2 * Math.PI)) > TURN_THRESHOLD_RAD
                || Math.abs(Math.IEEEremainder(pose[4] - lastPitch, 2 * Math.PI)) > TURN_THRESHOLD_RAD);
        if (!angles) {
            hasAngles = false;
        }
        if (!hasOrigin || dx * dx + dy * dy + dz * dz > 0.25 || turned) {
            hasOrigin = true;
            lastOriginX = pose[0];
            lastOriginY = pose[1];
            lastOriginZ = pose[2];
            hasAngles = angles;
            if (angles) {
                lastYaw = pose[3];
                lastPitch = pose[4];
            }
            lastMoveMs = now;
            settled = false;
            return;
        }
        settled = now - lastMoveMs >= settleAfterMs;
    }

    private boolean current(Request request) {
        return active && request.epoch == epoch && request.context == context;
    }

    private static boolean expired(long now, long requestedMs) {
        return now - requestedMs >= TARGET_TTL_MS;
    }

    private Task takePendingLocked(long now) {
        if (inFlight != null || pending == null) {
            return null;
        }
        final Request request = pending;
        pending = null;
        if (!current(request) || expired(now, request.requestedMs)) {
            hasSubmitted = false;
            return null;
        }
        final Task task = new Task(request);
        inFlight = task;
        hasSubmitted = true;
        lastSampleMs = now;
        return task;
    }

    private void submit(Task task) {
        if (task == null) {
            return;
        }
        try {
            final CompletableFuture<Void> future = submitter.apply(() -> runSample(task));
            if (future == null) {
                throw new IllegalStateException("采样提交没有返回 future");
            }
            // BackgroundWork 的拒绝只写 future,不能只依赖 submit 的 catch。
            future.whenComplete((ignored, failure) -> submissionCompleted(task, failure));
        } catch (Throwable failure) {
            submissionCompleted(task, failure);
        }
    }

    private void runSample(Task task) {
        final boolean capture;
        synchronized (lock) {
            if (inFlight != task || task.finished || task.started) {
                return;
            }
            task.started = true;
            capture = current(task.request) && !expired(clock.getAsLong(), task.request.requestedMs);
        }
        ListenerSample sample = null;
        RoomPlan plan = null;
        Throwable failure = null;
        try {
            if (capture) {
                sample = task.request.sampler.capture();
                plan = SceneResponse.respond(sample, task.request.strength);
            }
        } catch (Throwable caught) {
            failure = caught;
        } finally {
            final Task next;
            synchronized (lock) {
                next = finishLocked(task, sample, plan, failure, clock.getAsLong());
            }
            submit(next);
        }
    }

    private void submissionCompleted(Task task, Throwable failure) {
        final Task next;
        synchronized (lock) {
            if (inFlight != task || task.finished) {
                return;
            }
            if (task.started) {
                if (failure != null) {
                    task.futureFailure = failure;
                    failOpenLocked(task, failure);
                }
                // future 失败/取消不证明 capture 已经退出;只由真实任务结束释放闸。
                return;
            }
            next = finishLocked(task, null, null, failure, clock.getAsLong());
        }
        submit(next);
    }

    private Task finishLocked(Task task, ListenerSample sample, RoomPlan plan,
                              Throwable failure, long now) {
        if (inFlight != task || task.finished) {
            return null;
        }
        task.finished = true;
        try {
            if (current(task.request)) {
                final Throwable cause = task.futureFailure == null ? failure : task.futureFailure;
                if (cause != null) {
                    failOpenLocked(task, cause);
                } else if (plan == null || expired(now, task.request.requestedMs)) {
                    clearTargetLocked();
                    hasSubmitted = false;
                } else {
                    target.apply(sample, transition.apply(plan, lastPlan));
                    lastPlan = plan;
                    targetRequestedMs = task.request.requestedMs;
                    hasTarget = true;
                }
            }
        } catch (Throwable deliveryFailure) {
            failOpenLocked(task, deliveryFailure);
        } finally {
            // token 校验使 runnable/future 的重复终结不能释放下一轮的闸。
            inFlight = null;
        }
        return takePendingLocked(now);
    }

    private void failOpenLocked(Task task, Throwable failure) {
        if (!current(task.request) || task.failureHandled) {
            return;
        }
        task.failureHandled = true;
        hasSubmitted = false;
        clearTargetLocked();
        target.failed(failure);
    }

    /** 回调只在调度锁内执行,必须是短时的目标发布/诊断,不能启动音频引擎。 */
    interface TargetSink {
        void apply(ListenerSample sample, RoomPlan plan);

        void clear();

        void failed(Throwable failure);
    }

    private static final class Request {
        final long epoch;
        final Object context;
        final AcousticsRuntime.ListenerSampler sampler;
        final float strength;
        final long requestedMs;

        Request(long epoch, Object context, AcousticsRuntime.ListenerSampler sampler,
                float strength, long requestedMs) {
            this.epoch = epoch;
            this.context = context;
            this.sampler = sampler;
            this.strength = strength;
            this.requestedMs = requestedMs;
        }
    }

    private static final class Task {
        final Request request;
        boolean started;
        boolean finished;
        boolean failureHandled;
        Throwable futureFailure;

        Task(Request request) {
            this.request = request;
        }
    }
}
