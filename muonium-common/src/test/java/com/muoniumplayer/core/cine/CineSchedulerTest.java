package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.ncm.music.CineBeatSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实 Session 路径推进可控时钟，覆盖曾经依赖渲染调用次数的故障。 */
class CineSchedulerTest {

    private static final long TICK = 50_000_000L;
    private CineSession session;
    private Probe probe;
    private long now;
    private long song;
    private long position;
    private CineBeatSource.Grid grid;

    @BeforeEach
    void setup() {
        CineSettings.resetForTest();
        CineSettings.setEnabled(true);
        CineSettings.setIdleSeconds(5);
        session = new CineSession();
        probe = new Probe();
        now = 1_000_000_000L;
        song = 1L;
        position = 0L;
        grid = null;
    }

    private void tick() {
        now += TICK;
        position += 50L;
        session.tick(probe, CineSettings.snapshot(), playback(), now);
    }

    private CineSession.Playback playback() {
        return new CineSession.Playback(true, 1L, song, position, grid, 0.5f);
    }

    private void activate() {
        for (int i = 0; i < 101; i++) tick();
        assertTrue(session.frame().active());
        assertEquals(0f, session.frame().current().fovOffsetDeg(), 1.0e-5f,
                "启动时不能把已在播放的高 pulse 补触发成变焦");
    }

    private void seconds(int seconds) {
        for (int i = 0; i < seconds * 20; i++) tick();
    }

    @Test
    void firstFrameUsesActualWorldPositionAndRepeatedReadsArePure() {
        probe.x = 3_000d;
        probe.y = -20d;
        probe.z = -8_000d;
        activate();
        CineSession.Frame frame = session.frame();
        CinePose pose = frame.sample(0.5f);
        assertNotNull(pose);
        assertTrue(Math.abs(pose.x() - probe.x) <= 6d);
        assertTrue(Math.abs(pose.z() - probe.z) <= 6d);
        assertTrue(Math.abs(pose.y() - probe.y) <= 4d);
        int clips = probe.clipCalls;
        for (int i = 0; i < 1_000; i++) assertEquals(pose, frame.sample(0.5f));
        assertEquals(clips, probe.clipCalls, "渲染不能反查世界或推进状态");
        assertSame(frame, session.frame());
    }

    @Test
    void shotDeckCoversDistinctCompositionsWithoutAdjacentRepeats() {
        CineShots.ShotDeck deck = new CineShots.ShotDeck(73L);
        java.util.Set<CineShots.Shot> shots = new java.util.HashSet<CineShots.Shot>();
        CineShots.Shot previous = null;
        for (int i = 0; i < 64; i++) {
            CineShots.Shot next = deck.next(true, false, false);
            assertNotEquals(previous, next, "相邻构图不能重复");
            shots.add(next);
            previous = next;
        }
        assertTrue(shots.size() >= 25, "跨场景牌组应覆盖多类人物与环境构图");
        assertEquals(CineShots.Shot.MOB_FOLLOW,
                new CineShots.ShotDeck(5L).next(true, true, true));
    }

    @Test
    void environmentShotDeckUsesDimensionBiomeAndTimeWithoutRepeatingAdjacentShots() {
        CineShots.ShotDeck nether = new CineShots.ShotDeck(91L);
        java.util.Set<CineShots.Shot> netherShots = new java.util.HashSet<CineShots.Shot>();
        CineShots.Shot previous = null;
        for (int i = 0; i < 80; i++) {
            CineShots.Shot next = nether.nextEnvironment(1, "crimson_forest", 6000L, true);
            assertNotNull(next);
            assertNotEquals(previous, next);
            assertFalse(next.domain == CineShots.Domain.END || next.domain == CineShots.Domain.NATURE
                    || next.domain == CineShots.Domain.CAVE);
            netherShots.add(next);
            previous = next;
        }
        assertTrue(netherShots.contains(CineShots.Shot.NETHER_CRIMSON_SWEEP));
        assertTrue(netherShots.contains(CineShots.Shot.NETHER_LAVA_RIDGE));

        CineShots.ShotDeck person = new CineShots.ShotDeck(18L);
        for (int i = 0; i < 80; i++) {
            CineShots.Shot next = person.nextPlayer(1, "basalt_deltas", 18000L, false);
            assertNotNull(next);
            assertFalse(next.domain != CineShots.Domain.ANY && next.domain != CineShots.Domain.MOB);
            assertNotEquals(CineShots.Shot.MOB_FOLLOW, next);
        }

        CineShots.ShotDeck dawn = new CineShots.ShotDeck(27L);
        java.util.Set<CineShots.Shot> dawnShots = new java.util.HashSet<CineShots.Shot>();
        for (int i = 0; i < 48; i++) {
            CineShots.Shot next = dawn.nextEnvironment(0, "plains", 1200L, true);
            assertNotNull(next);
            dawnShots.add(next);
        }
        assertTrue(dawnShots.contains(CineShots.Shot.SUNRISE_HORIZON_GLOW));
        assertFalse(dawnShots.contains(CineShots.Shot.SUNSET_GOLDEN_RIM));
    }

    @Test
    void playerAndEnvironmentFamiliesStayDisjoint() {
        CineShots.ShotDeck deck = new CineShots.ShotDeck(0x5EEDL);
        for (int i = 0; i < 120; i++) {
            CineShots.Shot playerShot = deck.nextPlayer(0, "plains", 6000L, true);
            assertNotNull(playerShot);
            assertFalse(CineShots.ShotDeck.isEnvironment(playerShot), "人物家族不能混入环境镜头");
            assertFalse(playerShot.domain == CineShots.Domain.MOB, "人物家族不能混入生物镜头");
            CineShots.Shot environmentShot = deck.nextEnvironment(0, "plains", 6000L, true);
            assertNotNull(environmentShot);
            assertTrue(CineShots.ShotDeck.isEnvironment(environmentShot), "环境家族不能混入人物镜头");
            assertFalse(environmentShot.domain == CineShots.Domain.MOB, "环境家族不能混入生物镜头");
        }
    }

    @Test
    void mobChannelServesFollowAndWildlifeWithoutAdjacentRepeat() {
        boolean sawFollow = false;
        boolean sawWildlife = false;
        for (long seed : new long[]{0x5EEDL, 0xC1E5EEDL, 0x2545F4914F6CDD1DL, 1234L, 0xABCDL}) {
            CineShots.ShotDeck deck = new CineShots.ShotDeck(seed);
            CineShots.Shot previous = null;
            for (int i = 0; i < 200; i++) {
                CineShots.Shot taken = deck.takeMobIfAvailable();
                if (taken == null) {
                    // 两个生物镜头都暂不可用时走一次普通抽卡，让牌组耗尽重洗后继续
                    deck.next(0, "plains", 6000L, true, false, false);
                    previous = null;
                    continue;
                }
                assertTrue(taken == CineShots.Shot.MOB_FOLLOW || taken == CineShots.Shot.ENV_WILDLIFE_WATCH,
                        "生物通道只能给出跟随或野生动物镜头");
                assertNotEquals(previous, taken, "生物通道相邻不能重复");
                previous = taken;
                sawFollow |= taken == CineShots.Shot.MOB_FOLLOW;
                sawWildlife |= taken == CineShots.Shot.ENV_WILDLIFE_WATCH;
            }
        }
        assertTrue(sawFollow, "生物通道应提供跟随镜头");
        assertTrue(sawWildlife, "生物通道应提供野生动物观察镜头");
    }

    @Test
    void environmentShotRatioSwitchesOnlyTheShotFamily() {
        CineSettings.setEnvironmentShotPercent(0);
        activate();
        CinePose previous = session.frame().current();
        for (int i = 0; i < 160; i++) {
            tick();
            CinePose current = session.frame().current();
            assertTrue(distance(previous, current) <= 5d * 0.05d + 1.0e-6d);
            previous = current;
        }
        assertEquals(0, CineSettings.environmentShotPercent());

        session = new CineSession();
        probe = new Probe();
        now = 1_000_000_000L;
        position = 0L;
        CineSettings.setEnvironmentShotPercent(100);
        activate();
        assertTrue(session.frame().active());
        assertEquals(100, CineSettings.environmentShotPercent());
    }

    @Test
    void beatGridChangesNeverChangeFov() {
        CineSettings.setFovPunchDegrees(20f);
        CineSettings.setTransitionMask(0);
        activate();
        int initialShot = session.shotSequence();
        CineSession control = new CineSession();
        Probe controlProbe = new Probe();
        long controlNow = 1_000_000_000L;
        long controlPosition = 0L;
        CineSession.Playback controlPlayback = new CineSession.Playback(true, 1L, 1L, 0L, null, 0.5f);
        for (int i = 0; i < 101; i++) {
            controlNow += TICK;
            controlPosition += 50L;
            controlPlayback = new CineSession.Playback(true, 1L, 1L, controlPosition, null, 0.5f);
            control.tick(controlProbe, CineSettings.snapshot(), controlPlayback, controlNow);
        }
        assertEquals(initialShot, control.shotSequence());
        assertEquals(session.frame().current().fovOffsetDeg(), control.frame().current().fovOffsetDeg(), 1.0e-6f);
        grid = new CineBeatSource.Grid(120f, 500_000_000d, now, 4, now, 0.9f);
        for (int i = 0; i < 12; i++) {
            tick();
            controlNow += TICK;
            controlPosition += 50L;
            controlPlayback = new CineSession.Playback(true, 1L, 1L, controlPosition, null, 0.5f);
            control.tick(controlProbe, CineSettings.snapshot(), controlPlayback, controlNow);
            assertEquals(control.frame().current().fovOffsetDeg(), session.frame().current().fovOffsetDeg(),
                    1.0e-6f, "鼓点网格不能改变构图 FOV");
        }
    }

    @Test
    void shotFovCompositionEasesWithoutFrameCallDependentState() {
        activate();
        long deadline = now + 20_000_000_000L;
        CinePose previous = session.frame().current();
        boolean changed = false;
        for (int i = 0; i < 1200 && !changed; i++) {
            tick();
            CinePose current = session.frame().current();
            assertTrue(Math.abs(current.fovOffsetDeg() - previous.fovOffsetDeg()) <= 3d * 0.05d + 1.0e-5d,
                    "构图切换时 FOV 必须限速平滑");
            changed = current.fovOffsetDeg() != 0f;
            previous = current;
        }
        CineSession.Frame frame = session.frame();
        assertTrue(frame.current().fovOffsetDeg() != 0f);
        CinePose sample = frame.sample(0.5f);
        for (int i = 0; i < 500; i++) assertEquals(sample, frame.sample(0.5f));
        assertTrue(now < deadline, "镜头构图测试等待时长有上界");
    }

    @Test
    void disabledTransitionsStillCutOnceAndKeepFallbackDeadline() {
        CineSettings.setTransitionMask(0);
        activate();
        int initial = session.shotSequence();
        seconds(13);
        assertEquals(initial + 1, session.shotSequence());
        seconds(2);
        assertEquals(initial + 1, session.shotSequence(), "不能在期限过后每 tick 连续切镜");
        seconds(10);
        assertEquals(initial + 2, session.shotSequence());
    }

    @Test
    void rendererSkippingWholeTransitionCannotConsumePendingCut() {
        activate();
        int initial = session.shotSequence();
        // tick 和渲染都漏过转场窗口，下次 tick 仍必须完成一次切镜。
        now += 15_000_000_000L;
        position += 15_000L;
        session.tick(probe, CineSettings.snapshot(), playback(), now);
        assertEquals(initial + 1, session.shotSequence());
        assertNotNull(session.frame().transition(), "漏帧后仍须给新机位完整揭入半程");
        assertTrue(session.frame().transition().applied());
        assertEquals(0d, session.frame().transition().at(now).progress());
        tick();
        assertEquals(initial + 1, session.shotSequence());
    }

    @Test
    void transitionHasCoverAndRevealAndReadDoesNotClearPendingCut() {
        activate();
        while (session.frame().transition() == null) tick();
        CineSession.Transition transition = session.frame().transition();
        assertTrue(transition.at(transition.startNanos()).covering());
        assertEquals(0d, transition.at(transition.startNanos()).progress());
        assertTrue(transition.at(transition.cutNanos() - 1L).progress() > 0.99d);
        CineSession.Transition revealed = new CineSession.Transition(transition.effect(), transition.startNanos(),
                transition.cutNanos(), transition.endNanos(), true);
        assertTrue(transition.at(transition.endNanos() + 1L).covering(), "未提交机位前保持全遮");
        assertEquals(1d, transition.at(transition.endNanos() + 1L).progress());
        assertFalse(revealed.at(revealed.cutNanos()).covering());
        assertEquals(0d, revealed.at(revealed.cutNanos()).progress());
        assertTrue(revealed.at(revealed.endNanos() - 1L).progress() > 0.99d);
        assertNull(revealed.at(revealed.endNanos()));
        int initial = session.shotSequence();
        assertSame(transition, session.frame().transition());
        now = transition.endNanos() + 1L;
        position += 1_000L;
        session.tick(probe, CineSettings.snapshot(), playback(), now);
        assertEquals(initial + 1, session.shotSequence());
    }

    @Test
    void gridArrivingMidShotAndThenReanchoringCannotStallCuts() {
        activate();
        seconds(1);
        grid = new CineBeatSource.Grid(120f, 500_000_000d, now, 4, now, 0.9f);
        int initial = session.shotSequence();
        for (int i = 0; i < 140; i++) {
            // 实时估算会持续重锚；期限不能变成 beatNow-shotStartBeat 的不相干差值。
            grid = new CineBeatSource.Grid(120f, 500_000_000d, now, 4, now, 0.9f);
            tick();
        }
        assertTrue(session.shotSequence() > initial, "有网格后应在有限时间切镜");
        assertTrue(session.shotSequence() <= initial + 2, "重锚不能制造闪切风暴");
    }

    @Test
    void newSongAndSeekRebaseDeadlineAndClearOldPulse() {
        activate();
        seconds(11);
        int initial = session.shotSequence();
        song = 2L;
        position = 0L;
        tick();
        seconds(3);
        assertEquals(initial, session.shotSequence(), "新歌不能继承上一首即将到点的切镜");
        seconds(10);
        assertEquals(initial + 1, session.shotSequence());
        position += 60_000L;
        tick();
        int afterSeek = session.shotSequence();
        seconds(3);
        assertEquals(afterSeek, session.shotSequence());
    }

    @Test
    void stopsReturnSmoothlyAndWorldChangeReleasesAllFrameState() {
        activate();
        probe.input = true;
        for (int i = 0; i < 100 && session.frame().active(); i++) {
            CinePose previous = session.frame().current();
            tick();
            if (session.frame().active()) {
                CinePose current = session.frame().current();
                assertTrue(distance(previous, current) <= 5d * 0.05d + 1.0e-6d);
                assertAngleStep(previous.yawDeg(), current.yawDeg(), 45d * 0.05d);
                assertAngleStep(previous.pitchDeg(), current.pitchDeg(), 45d * 0.05d);
                assertTrue(Math.abs(current.fovOffsetDeg() - previous.fovOffsetDeg()) <= 3d * 0.05d + 1.0e-4d);
            }
        }
        assertFalse(session.frame().active(), "普通停止需先平滑归还");
        probe.input = false;
        seconds(5);
        assertTrue(session.frame().active());
        probe.screen = new Object();
        for (int i = 0; i < 100 && session.frame().active(); i++) tick();
        assertFalse(session.frame().active());
        probe.screen = null;
        seconds(5);
        assertTrue(session.frame().active());
        session.tick(probe, CineSettings.snapshot(), CineSession.Playback.STOPPED, now + TICK);
        for (int i = 0; i < 100 && session.frame().active(); i++) tick();
        assertFalse(session.frame().active());
        seconds(5);
        assertTrue(session.frame().active());
        probe.world = new Object();
        tick();
        assertInactive();
        seconds(5);
        assertTrue(session.frame().active());
        assertTrue(session.frame().generation() >= 4L);
    }

    @Test
    void disabledSettingClearsIdleCounterAndNonFiniteSceneCannotActivate() {
        tick();
        seconds(4);
        CineSettings.setEnabled(false);
        tick();
        CineSettings.setEnabled(true);
        seconds(1);
        assertInactive();
        probe.z = Double.POSITIVE_INFINITY;
        seconds(10);
        assertInactive();
    }

    @Test
    void cameraPoseAlwaysUsesRateLimitedSpringAcrossShotsAndTeleport() {
        CineSettings.setTransitionMask(0);
        activate();
        int initialShot = session.shotSequence();
        CinePose previous = session.frame().current();
        for (int i = 0; i < 600 && session.shotSequence() == initialShot; i++) {
            tick();
            CinePose current = session.frame().current();
            assertTrue(distance(previous, current) <= 5d * 0.05d + 1.0e-6d,
                    "切镜不能绕过三维位置速度限制");
            assertAngleStep(previous.yawDeg(), current.yawDeg(), 45d * 0.05d);
            assertAngleStep(previous.pitchDeg(), current.pitchDeg(), 45d * 0.05d);
            assertAngleStep(previous.rollDeg(), current.rollDeg(), 45d * 0.05d);
            assertTrue(Math.abs(current.fovOffsetDeg() - previous.fovOffsetDeg()) <= 3d * 0.05d + 1.0e-4d);
            previous = current;
        }
        assertEquals(initialShot + 1, session.shotSequence(), "必须覆盖一次实际切镜");

        CinePose beforeTeleport = session.frame().current();
        double relativeX = beforeTeleport.x() - probe.x;
        double relativeY = beforeTeleport.y() - probe.playerEyeY();
        double relativeZ = beforeTeleport.z() - probe.z;
        probe.x += 100d;
        tick();
        CinePose afterTeleport = session.frame().current();
        assertTrue(Math.abs((afterTeleport.x() - probe.x) - relativeX) <= 5d * 0.05d + 1.0e-6d,
                "传送跟随不能产生相机相对位移跳变");
        assertTrue(Math.abs((afterTeleport.y() - probe.playerEyeY()) - relativeY) <= 5d * 0.05d + 1.0e-6d,
                "传送跟随不能产生相机相对高度跳变");
        assertTrue(Math.abs((afterTeleport.z() - probe.z) - relativeZ) <= 5d * 0.05d + 1.0e-6d,
                "传送跟随不能产生相机相对位移跳变");
        assertTrue(distance(beforeTeleport, afterTeleport) > 90d,
                "机位需跟随玩家传送，而不能留在旧世界坐标");
    }

    private static void assertAngleStep(float previous, float current, double maxStep) {
        assertTrue(Math.abs(CineShots.wrapDegrees(current - previous)) <= maxStep + 1.0e-4d);
    }

    @Test
    void collisionAndTargetLossDoNotForcePoseSnap() {
        activate();
        CinePose previous = session.frame().current();
        probe.blockCameraPath = true;
        tick();
        assertTrue(distance(previous, session.frame().current()) <= 5d * 0.05d + 1.0e-6d);
        probe.blockCameraPath = false;
        seconds(20);
        previous = session.frame().current();
        probe.mobPresent = false;
        tick();
        assertTrue(distance(previous, session.frame().current()) <= 5d * 0.05d + 1.0e-6d);
        assertTrue(session.frame().current().isFinite());
    }

    @Test
    void inputAndMusicStopReturnCameraWithRateLimits() {
        activate();
        CinePose previous = session.frame().current();
        probe.input = true;
        tick();
        assertTrue(session.frame().active(), "归还期间仍需保持相机接管");
        CinePose current = session.frame().current();
        assertTrue(distance(previous, current) <= 5d * 0.05d + 1.0e-6d);
        assertAngleStep(previous.yawDeg(), current.yawDeg(), 45d * 0.05d);
        assertTrue(Math.abs(current.fovOffsetDeg() - previous.fovOffsetDeg()) <= 3d * 0.05d + 1.0e-4d);
        CinePose last = current;
        for (int i = 0; i < 100 && session.frame().active(); i++) {
            tick();
            if (!session.frame().active()) break;
            current = session.frame().current();
            assertTrue(distance(last, current) <= 5d * 0.05d + 1.0e-6d);
            assertAngleStep(last.yawDeg(), current.yawDeg(), 45d * 0.05d);
            assertAngleStep(last.pitchDeg(), current.pitchDeg(), 45d * 0.05d);
            assertTrue(Math.abs(current.fovOffsetDeg() - last.fovOffsetDeg()) <= 3d * 0.05d + 1.0e-4d);
            last = current;
        }
        assertFalse(session.frame().active(), "归还抵达玩家视角后才释放接管");
        assertNull(session.frame().sample(0.5f));
    }

    private static double distance(CinePose a, CinePose b) {
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void longTransitionWithShortShotCannotOverlapNextCut() {
        CineSettings.setTransitionSeconds(3f);
        CineSettings.setMinShotSeconds(1f);
        CineSettings.setShotBeats(1);
        grid = new CineBeatSource.Grid(120f, 500_000_000d, now, 4, now, 0.9f);
        activate();
        int previous = session.shotSequence();
        long lastCut = now;
        for (int i = 0; i < 400; i++) {
            tick();
            if (session.shotSequence() != previous) {
                assertTrue(now - lastCut >= 3_000_000_000L, "两个切镜之间必须留出完整遮出/揭入");
                previous = session.shotSequence();
                lastCut = now;
            }
        }
    }

    @Test
    void trackedMobRefreshesSameEntityAndDisappearingMobFallsBack() {
        activate();
        seconds(25);
        assertEquals(2, session.shotSequence());
        assertTrue(probe.trackedCalls > 0);
        assertEquals(41, probe.lastTrackedId);
        CinePose before = session.frame().current();
        probe.mobX += 2d;
        seconds(1);
        assertNotEquals(before, session.frame().current());
        CinePose previous = session.frame().current();
        probe.mobPresent = false;
        tick();
        CinePose afterLoss = session.frame().current();
        assertTrue(afterLoss.isFinite());
        assertTrue(distance(previous, afterLoss) <= 5d * 0.05d + 1.0e-6d,
                "目标消失不应触发硬切");
        assertTrue(session.frame().active());
    }

    @Test
    void effectSelectionNeverEscapesMaskIncludingNegativeRandomValues() {
        assertEquals(0, session.pickEffect(0));
        for (int mask = 1; mask <= 31; mask++) {
            for (int i = 0; i < 100; i++) {
                int effect = session.pickEffect(mask);
                assertTrue(effect >= 1 && effect <= 5);
                assertNotEquals(0, mask & (1 << (effect - 1)));
            }
        }
    }

    @Test
    void gridMathValidatesValuesAndAlignsAbsoluteDeadlineToBar() {
        long anchor = 1_000_000_000L;
        CineBeatSource.Grid valid = new CineBeatSource.Grid(120f, 500_000_000d, anchor, 4, anchor, 0.9f);
        assertTrue(valid.isMetered());
        assertEquals(0.5f, valid.beatPhase(anchor + 250_000_000L));
        assertEquals(1, valid.beatInBar(anchor + 500_000_000L));
        assertEquals(anchor + 2_000_000_000L, valid.nextBarAtOrAfter(anchor + 250_000_000L));
        assertEquals(anchor, valid.nextBarAtOrAfter(anchor));
        assertFalse(new CineBeatSource.Grid(120f, Double.NaN, anchor, 4, anchor, 1f).isMetered());
        assertFalse(new CineBeatSource.Grid(120f, 500_000_000d, anchor, 4, anchor, Float.NaN).isMetered());
        assertFalse(new CineBeatSource.Grid(0f, 0d, 0d, 4, 0d, 0f).isMetered());
    }

    private void assertInactive() {
        assertFalse(session.frame().active());
        assertNull(session.frame().sample(0.5f));
        assertNull(session.frame().transition());
        assertEquals(0f, session.frame().barFraction());
    }

    private static final class Probe implements CineDirector.SceneProbe {
        Object world = new Object();
        final Object player = new Object();
        double x, y = 64d, z;
        boolean input;
        boolean sky = true;
        int dimension;
        String biome = "plains";
        long time = 6000L;
        Object screen;
        double wallX = Double.POSITIVE_INFINITY;
        int clipCalls;
        int trackedCalls;
        int lastTrackedId;
        boolean mobPresent = true;
        boolean blockCameraPath;
        double mobX = 3d;

        @Override public Object worldIdentity() { return world; }
        @Override public Object playerIdentity() { return player; }
        @Override public double playerX() { return x; }
        @Override public double playerY() { return y; }
        @Override public double playerZ() { return z; }
        @Override public double playerEyeY() { return y + 1.62d; }
        @Override public float playerYaw() { return 0f; }
        @Override public float playerPitch() { return 0f; }
        @Override public Object openScreen() { return screen; }
        @Override public boolean hurtThisTick() { return false; }
        @Override public boolean inputThisTick() { return input; }
        @Override public boolean skyVisible() { return sky; }
        @Override public int dimensionKind() { return dimension; }
        @Override public String biomeKey() { return biome; }
        @Override public long timeOfDay() { return time; }
        @Override public boolean playerAlive() { return true; }
        @Override public CineDirector.Vec3Holder clipCamera(double fx, double fy, double fz, double tx, double ty, double tz) {
            clipCalls++;
            if (blockCameraPath && fy != playerEyeY()) return new CineDirector.Vec3Holder(fx, fy, fz);
            return new CineDirector.Vec3Holder(Math.min(wallX, tx), ty, tz);
        }
        @Override public CineDirector.MobSample nearestMovingMob(double range) {
            return mobPresent ? new CineDirector.MobSample(41, mobX - 0.2d, y, z + 2.8d,
                    mobX, y, z + 3d, 1.8f) : null;
        }
        @Override public CineDirector.MobSample trackedMob(int id, double range) {
            trackedCalls++;
            lastTrackedId = id;
            return nearestMovingMob(range);
        }
    }
}
