package com.muoniumplayer.automix.playback;

import com.muoniumplayer.automix.planning.AutomixPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立过渡执行器的线程契约测试，不启动 Minecraft、JSyn 或模型运行时。 */
class AutomixTransitionEngineTest {

    @Test
    void 音频任务和监督兜底共用同一条曲线写入路径() {
        AtomicLong now = new AtomicLong();
        FakeDeckAccess decks = new FakeDeckAccess();
        FakeAudioTasks tasks = new FakeAudioTasks();
        FakeListener listener = new FakeListener();
        AutomixTransitionEngine<String> engine = new AutomixTransitionEngine<>(
                decks, tasks, listener, now::get);

        engine.start("out", "in", AutomixPlan.manualQuickMix(1_000L), 1_000L);
        assertEquals(1, tasks.added.size());

        now.set(20L);
        tasks.added.get(0).run();
        assertEquals(1, decks.incomingWrites);
        assertEquals(1, decks.outgoingWrites);

        now.set(30L);
        engine.tick("in");
        assertEquals(1, decks.incomingWrites, "音频任务仍存活时监督线程不得重复写帧");

        now.set(70L);
        engine.tick("in");
        assertEquals(2, decks.incomingWrites, "音频任务超过 40 ms 未报数时监督线程必须接管");
    }

    @Test
    void 暂停冻结进度并在自然结束时清理任务和退场牌() {
        AtomicLong now = new AtomicLong();
        FakeDeckAccess decks = new FakeDeckAccess();
        FakeAudioTasks tasks = new FakeAudioTasks();
        FakeListener listener = new FakeListener();
        AutomixTransitionEngine<String> engine = new AutomixTransitionEngine<>(
                decks, tasks, listener, now::get);

        engine.start("out", "in", AutomixPlan.manualQuickMix(1_000L), 1_000L);
        now.set(100L);
        decks.activePaused = true;
        engine.tick("in");
        assertTrue(listener.paused);
        assertEquals(1_000L, engine.remainingMillis());

        now.set(400L);
        tasks.added.get(0).run();
        assertEquals(1, decks.incomingWrites, "暂停时音频任务应重复写入冻结进度");

        decks.activePaused = false;
        now.set(1_100L);
        engine.tick("in");
        assertFalse(engine.isActive());
        assertEquals(1, tasks.removed.size());
        assertEquals(1, decks.restores);
        assertEquals(1, listener.released.size());
        assertTrue(decks.resumeCount > 0);
    }

    @Test
    void 取消时先停声并撤销音频任务() {
        AtomicLong now = new AtomicLong();
        FakeDeckAccess decks = new FakeDeckAccess();
        FakeAudioTasks tasks = new FakeAudioTasks();
        FakeListener listener = new FakeListener();
        AutomixTransitionEngine<String> engine = new AutomixTransitionEngine<>(
                decks, tasks, listener, now::get);

        engine.start("out", "in", AutomixPlan.manualQuickMix(1_000L), 1_000L);
        AutomixTransitionEngine.Transition<String> detached = engine.detachForReplacement();

        assertNotNull(detached);
        assertFalse(engine.isActive());
        assertEquals(1, tasks.removed.size());
        assertEquals(List.of("out", "in"), decks.stopped);
        assertTrue(listener.released.isEmpty(), "取消后的回收归宿主异步释放，不由执行器重复回调");
    }

    private static final class FakeDeckAccess implements AutomixTransitionEngine.DeckAccess<String> {
        private boolean activePaused;
        private boolean outgoingPaused;
        private int incomingWrites;
        private int outgoingWrites;
        private int restores;
        private int resumeCount;
        private final List<String> stopped = new ArrayList<>();

        @Override
        public boolean isPaused(String deck) { return activePaused; }

        @Override
        public boolean canPause(String deck) { return !outgoingPaused; }

        @Override
        public void pause(String deck) { outgoingPaused = true; }

        @Override
        public void resume(String deck) {
            resumeCount++;
            outgoingPaused = false;
        }

        @Override
        public void stopForReplacement(String deck) { stopped.add(deck); }

        @Override
        public void restore(String deck) { restores++; }

        @Override
        public float loudnessGain(String deck) { return 1.0f; }

        @Override
        public void writeIncoming(String deck, float gain, float bassDb, float vocalDepth,
                                   float midDb, float highDb) { incomingWrites++; }

        @Override
        public void writeOutgoing(String deck, float gain, float bassDb, float vocalDepth,
                                   float midDb, float highDb, float tempo, float pitch) {
            outgoingWrites++;
        }
    }

    private static final class FakeAudioTasks implements AutomixTransitionEngine.AudioTasks {
        private final List<Runnable> added = new ArrayList<>();
        private final List<Runnable> removed = new ArrayList<>();

        @Override
        public void add(Runnable task) { added.add(task); }

        @Override
        public void remove(Runnable task) { removed.add(task); }
    }

    private static final class FakeListener implements AutomixTransitionEngine.Listener<String> {
        private boolean paused;
        private final List<String> released = new ArrayList<>();

        @Override
        public void setPaused(boolean paused) { this.paused = paused; }

        @Override
        public void releaseOutgoing(String outgoing) { released.add(outgoing); }
    }
}
