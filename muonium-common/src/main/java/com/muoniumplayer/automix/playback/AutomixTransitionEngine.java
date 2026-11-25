package com.muoniumplayer.automix.playback;

import com.muoniumplayer.automix.planning.AutomixPlan;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 双播放牌过渡执行器。播放监督线程负责生命周期，音频线程只采样曲线和直写参数。
 *
 * <p>不解析队列、不做分析、不访问磁盘或界面；宿主通过回调回收退场播放牌。
 * 所有音频回调只使用已发布的状态和基本数值，不分配对象、不获取锁。</p>
 */
public final class AutomixTransitionEngine<D> {
    private static final long AUDIO_DRIVEN_WINDOW_MILLIS = 40L;

    /** 宿主已有播放牌的最小接口；直写方法必须保留端口直写与滤波参数平滑。 */
    public interface DeckAccess<D> {
        boolean isPaused(D deck);
        boolean canPause(D deck);
        void pause(D deck);
        void resume(D deck);
        void stopForReplacement(D deck);
        void restore(D deck);
        float loudnessGain(D deck);
        void writeIncoming(D deck, float gain, float bassDb, float vocalDepth, float midDb, float highDb);
        void writeOutgoing(D deck, float gain, float bassDb, float vocalDepth, float midDb,
                           float highDb, float tempo, float pitch);
    }

    /** 注册与注销只发生于生命周期线程，绝不发生于音频回调。 */
    public interface AudioTasks {
        void add(Runnable task);
        void remove(Runnable task);
    }

    /** 回调只由监督或取消线程执行，音频线程不触碰宿主的锁和界面。 */
    public interface Listener<D> {
        void setPaused(boolean paused);
        void releaseOutgoing(D outgoing);
    }

    /** 只读交接视图；内部时钟与任务状态由执行器独占。 */
    public static final class Transition<D> {
        private final D outgoing;
        private final D incoming;
        private final AutomixPlan plan;
        private final long overlapMillis;
        private volatile long startedAt;
        private long tickedAt;
        private boolean outgoingPaused;
        private volatile double pausedProgress = -1.0;
        private volatile long audioDrivenAt;
        private boolean audioTaskRegistered;
        private final AudioTask<D> audioTask;

        private Transition(AutomixTransitionEngine<D> owner, D outgoing, D incoming, AutomixPlan plan,
                           long overlapMillis, long startedAt) {
            this.outgoing = outgoing;
            this.incoming = incoming;
            this.plan = plan;
            this.overlapMillis = Math.max(1L, overlapMillis);
            this.startedAt = startedAt;
            this.tickedAt = startedAt;
            this.audioTask = new AudioTask<>(owner, this);
        }

        public D outgoing() { return outgoing; }
        public D incoming() { return incoming; }
        public AutomixPlan plan() { return plan; }
        public long overlapMillis() { return overlapMillis; }
    }

    /** 即使底层注销失败，也清掉捕获的会话与播放牌，避免引擎列表留住旧会话。 */
    private static final class AudioTask<D> implements Runnable {
        private volatile AutomixTransitionEngine<D> owner;
        private volatile Transition<D> transition;

        private AudioTask(AutomixTransitionEngine<D> owner, Transition<D> transition) {
            this.owner = owner;
            this.transition = transition;
        }

        @Override
        public void run() {
            AutomixTransitionEngine<D> target = owner;
            Transition<D> fade = transition;
            if (target != null && fade != null) target.writeTransitionFrameOnAudioThread(fade);
        }

        private void clear() {
            owner = null;
            transition = null;
        }
    }

    private final DeckAccess<D> decks;
    private final AudioTasks tasks;
    private final Listener<D> listener;
    private final LongSupplier clock;
    private final AtomicReference<Transition<D>> active = new AtomicReference<>();

    public AutomixTransitionEngine(DeckAccess<D> decks, AudioTasks tasks, Listener<D> listener) {
        this(decks, tasks, listener, System::currentTimeMillis);
    }

    AutomixTransitionEngine(DeckAccess<D> decks, AudioTasks tasks, Listener<D> listener,
                            LongSupplier clock) {
        this.decks = Objects.requireNonNull(decks);
        this.tasks = Objects.requireNonNull(tasks);
        this.listener = Objects.requireNonNull(listener);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 仅在宿主完成队列和播放牌所有权交接后调用；上一过渡必须已经收尾。 */
    public void start(D outgoing, D incoming, AutomixPlan plan, long overlapMillis) {
        Transition<D> fade = new Transition<>(this, outgoing, incoming,
                Objects.requireNonNull(plan), overlapMillis, clock.getAsLong());
        if (!active.compareAndSet(null, fade)) {
            throw new IllegalStateException("上一次 AUTOMIX 过渡尚未结束");
        }
        registerAudioTask(fade);
    }

    public boolean isActive() { return active.get() != null; }

    public Transition<D> current() { return active.get(); }

    /** 暂停期间直接采用已发布的冻结进度，房间保护窗也不会在两次 tick 之间缩短。 */
    public long remainingMillis() {
        Transition<D> fade = active.get();
        if (fade == null) return 0L;
        double held = fade.pausedProgress;
        double progress = held >= 0.0 ? held : progressAt(fade, clock.getAsLong());
        if (progress >= 1.0) return 0L;
        if (progress <= 0.0) return fade.overlapMillis;
        return (long) Math.ceil((1.0 - progress) * fade.overlapMillis);
    }

    /** 由播放监督 tick 调用；音频任务停更 40 ms 后自动接回同一份曲线写入器。 */
    public void tick(D activeDeck) {
        Transition<D> fade = active.get();
        if (fade == null) return;
        boolean completed = false;
        // 只串行化控制路径，防止取消已经停声后，旧 tick 又恢复退场播放牌。
        synchronized (fade) {
            if (active.get() != fade) return;
            long now = clock.getAsLong();
            if (activeDeck != null && decks.isPaused(activeDeck)) {
                fade.startedAt += Math.max(0L, now - fade.tickedAt);
                fade.tickedAt = now;
                fade.pausedProgress = progressAt(fade, now);
                listener.setPaused(true);
                if (!fade.outgoingPaused && fade.outgoing != null && decks.canPause(fade.outgoing)) {
                    decks.pause(fade.outgoing);
                    fade.outgoingPaused = true;
                }
                return;
            }

            // 暂停期间已在每个监督 tick 平移时钟，恢复时只解除冻结并继续推进。
            listener.setPaused(false);
            if (fade.outgoingPaused && fade.outgoing != null) {
                decks.resume(fade.outgoing);
                fade.outgoingPaused = false;
            }
            fade.pausedProgress = -1.0;
            fade.tickedAt = now;
            double progress = progressAt(fade, now);
            if (now - fade.audioDrivenAt >= AUDIO_DRIVEN_WINDOW_MILLIS) {
                writeTransitionFrame(fade, progress);
            }
            completed = progress >= 1.0 && active.compareAndSet(fade, null);
        }
        // 回收会获取宿主的播放牌锁，必须离开内部控制锁后再回调。
        if (completed) complete(fade, fade.incoming);
    }

    /** 提前结束时只拉平仍归宿主管理的进场播放牌，退场回收交给宿主裁决。 */
    public void finish(D ownedDeck) {
        Transition<D> fade = active.getAndSet(null);
        if (fade != null) complete(fade, ownedDeck);
    }

    /**
     * 取消播放会话时先撤销音频任务，再同步停声；返回的播放牌由宿主统一异步释放。
     * 与自然收尾竞争时只有原子取得所有权的一侧可以释放同一次过渡。
     */
    public Transition<D> detachForReplacement() {
        Transition<D> fade = active.getAndSet(null);
        if (fade == null) return null;
        synchronized (fade) {
            unregisterAudioTask(fade);
            if (fade.outgoing != null) decks.stopForReplacement(fade.outgoing);
            if (fade.incoming != null) decks.stopForReplacement(fade.incoming);
        }
        return fade;
    }

    private void complete(Transition<D> fade, D ownedDeck) {
        // active 必须先清空。端口直写结束后，宿主的复位命令在音频命令队列中随后执行。
        try {
            synchronized (fade) {
                unregisterAudioTask(fade);
                if (fade.incoming != null && fade.incoming == ownedDeck) decks.restore(fade.incoming);
            }
        } finally {
            if (fade.outgoing != null) listener.releaseOutgoing(fade.outgoing);
        }
    }

    private static double progressAt(Transition<?> fade, long now) {
        return (now - fade.startedAt) / (double) fade.overlapMillis;
    }

    /** 唯一曲线执行入口；监督兜底与音频任务必须使用完全相同的采样和端口写入。 */
    private void writeTransitionFrame(Transition<D> fade, double progress) {
        if (fade.incoming != null) {
            double ratio = fade.outgoing == null ? 1.0 : decks.loudnessGain(fade.outgoing)
                    / Math.max(.000001f, decks.loudnessGain(fade.incoming));
            decks.writeIncoming(fade.incoming, fade.plan.incomingGain(progress, ratio),
                    fade.plan.incomingBassDb(progress), fade.plan.incomingVocalDepth(progress),
                    fade.plan.incomingMidDb(progress), fade.plan.incomingHighDb(progress));
        }
        if (fade.outgoing != null) {
            // 以实际缩短后的渐变长度计算节奏与音高，保留两者的独立控制。
            decks.writeOutgoing(fade.outgoing, fade.plan.outgoingGain(progress),
                    fade.plan.outgoingBassDb(progress), fade.plan.outgoingVocalDepth(progress),
                    fade.plan.outgoingMidDb(progress), fade.plan.outgoingHighDb(progress),
                    fade.plan.outgoingTempo(progress, fade.overlapMillis),
                    fade.plan.outgoingPitch(progress, fade.overlapMillis));
        }
    }

    private void writeTransitionFrameOnAudioThread(Transition<D> fade) {
        try {
            if (active.get() != fade) return;
            double held = fade.pausedProgress;
            long now = clock.getAsLong();
            double progress = held >= 0.0 ? held : progressAt(fade, now);
            writeTransitionFrame(fade, progress);
            fade.audioDrivenAt = now;
        } catch (Throwable ignored) {
            // 不做日志或宿主调用；不更新存活时间，监督 tick 自动收回写入权。
        }
    }

    private void registerAudioTask(Transition<D> fade) {
        // 只在控制线程同步注册与撤销；音频线程绝不获取这把锁。
        synchronized (fade) {
            if (active.get() != fade) return;
            try {
                tasks.add(fade.audioTask);
                fade.audioTaskRegistered = true;
            } catch (Throwable ignored) {
                // 注册失败只降低曲线分辨率，不影响监督 tick 推进或收尾。
            }
        }
    }

    private void unregisterAudioTask(Transition<D> fade) {
        synchronized (fade) {
            try {
                if (fade.audioTaskRegistered) {
                    fade.audioTaskRegistered = false;
                    tasks.remove(fade.audioTask);
                }
            } catch (Throwable ignored) {
                // 清除捕获引用后，底层残留的回调也只会立即返回。
            } finally {
                fade.audioTask.clear();
            }
        }
    }
}
