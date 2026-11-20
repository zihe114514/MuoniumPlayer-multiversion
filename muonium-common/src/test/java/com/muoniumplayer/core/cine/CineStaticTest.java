package com.muoniumplayer.core.cine;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 保留宿主边界与已发生故障的静态守卫；行为回归由 CineSchedulerTest 覆盖。 */
class CineStaticTest {

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String shared(String path) throws Exception {
        return read(ProjectPaths.sharedJava().resolve("com/muoniumplayer/" + path));
    }

    private static String tree(String path) throws Exception {
        return read(ProjectPaths.versionJava().resolve("com/muoniumplayer/" + path));
    }

    private static String secondLoader() {
        return ProjectPaths.versionRoot().getFileName().toString().startsWith("1.20.1") ? "forge" : "neoforge";
    }

    @Test
    void cineBusinessHasNoMinecraftOrLoaderSymbols() throws Exception {
        for (String name : new String[]{"CineDirector", "CineSession", "CineInput", "CineCameraRotation",
                "CinePose", "CineSettings", "CineShots", "CineTransition", "CineBars"}) {
            String source = shared("core/cine/" + name + ".java");
            for (String forbidden : new String[]{"import net.minecraft", "net.fabricmc.", "net.neoforged.",
                    "net.minecraftforge.", "cpw.mods."}) {
                assertFalse(source.contains(forbidden), name + " -> " + forbidden);
            }
        }
    }

    @Test
    void sessionIsSingleWriterAndRenderReadsDoNotQueryWorld() throws Exception {
        String director = shared("core/cine/CineDirector.java");
        String sample = director.substring(director.indexOf("public static CinePose currentPose("),
                director.indexOf("public static float fovOffsetDeg("));
        assertTrue(sample.contains("frame.sample(partialTick)"));
        assertFalse(sample.contains("System.nanoTime"));
        assertFalse(sample.contains("SceneProbe"));
        assertFalse(director.contains("volatile SceneProbe"));
        String transition = director.substring(director.indexOf("public static TransitionFrame currentTransition("),
                director.indexOf("public static float cinematicBarFraction("));
        assertFalse(transition.contains("SESSION."), "渲染不能清除切镜调度状态");
        assertTrue(director.contains("LOG.error("), "内部失败必须保留调用栈，不能吞异常假装成功");
    }

    @Test
    void everyLoaderRegistersCameraFovRollAndActualInput() throws Exception {
        for (String loader : new String[]{"fabric", secondLoader()}) {
            String json = read(ProjectPaths.versionRoot().resolve(loader + "/src/main/resources/muonium.mixins.json"));
            for (String mixin : new String[]{"CineCameraMixin", "CineRollMixin", "CineFovMixin",
                    "CineKeyboardMixin", "CineMouseMixin", "CineHudMixin"}) {
                assertTrue(json.contains('"' + mixin + '"'), loader + " 缺 " + mixin);
            }
            String roll = read(ProjectPaths.versionRoot().resolve(loader
                    + "/src/main/java/com/muoniumplayer/mixin/CineRollMixin.java"));
            assertTrue(roll.contains("require = 1"));
            assertTrue(roll.contains("allow = 1"));
            assertTrue(roll.contains("cineRollDegrees()"));
            assertFalse(roll.contains("require = 0"));
            if (loader.equals("neoforge")) {
                assertTrue(roll.contains("Lnet/minecraft/client/Camera;setRotation(FFF)V"));
            } else {
                assertTrue(roll.contains("Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"));
            }
        }
    }

    @Test
    void everyPoseComponentUsesOneRateLimitedSpringWithoutHardCutEscapeHatch() throws Exception {
        String session = shared("core/cine/CineSession.java");
        assertFalse(session.contains("hardCut"), "运镜运行过程中不应有跳帧分支");
        String publish = session.substring(session.indexOf("private void publish("),
                session.indexOf("private void publishPose("));
        String returnStep = session.substring(session.indexOf("private void tickReturn("),
                session.indexOf("private static boolean near("));
        String step = session.substring(session.indexOf("private void publishPose("),
                session.indexOf("private Target target("));
        assertTrue(publish.contains("publishPose(probe, scene, desired"));
        assertTrue(returnStep.contains("publishPose(probe, scene, desired"));
        assertTrue(step.contains("CAMERA_POSITION_SPEED"));
        assertTrue(returnStep.contains("springAngle") || step.contains("springAngle(old.yawDeg()"));
        assertTrue(step.contains("springVector(old.x(), old.y(), old.z()"));
        assertFalse(step.contains("spring(old.x(), targetPosition.x()"));
        assertTrue(step.contains("springAngle(old.yawDeg()"));
        assertTrue(step.contains("springAngle(old.pitchDeg()"));
        assertTrue(step.contains("springAngle(old.rollDeg()"));
        assertTrue(step.contains("spring(old.fovOffsetDeg()"));
        assertTrue(step.contains("new Frame(generation, old, pose"));
        assertFalse(step.contains("new Frame(generation, pose, pose"));
        assertFalse(publish.contains("handheld"));
        assertFalse(publish.contains("fovPunch"));
    }

    @Test
    void cameraAppliesOnePurePoseAndNeverMutatesQuaternionAfterSetter() throws Exception {
        String camera = tree("mixin/CineCameraMixin.java");
        assertTrue(camera.contains("@Mixin(Camera.class)"));
        assertTrue(camera.contains("CineDirector.currentPose(partialTick)"));
        assertEquals(1, camera.split("CineDirector.currentPose\\(", -1).length - 1);
        assertTrue(camera.contains("finally"), "roll scope 无论异常与否都要清除");
        assertFalse(camera.contains("rotateAxis"));
        assertFalse(camera.contains("rotation()"));
        assertFalse(camera.contains("shift = At.Shift.AFTER"));
        String lane = ProjectPaths.versionRoot().getFileName().toString();
        if (lane.startsWith("1.20.1")) {
            assertTrue(camera.contains("cineAppliedPose()"));
            for (String loader : new String[]{"fabric", "forge"}) {
                String worldRoll = read(ProjectPaths.versionRoot().resolve(loader
                        + "/src/main/java/com/muoniumplayer/mixin/CineWorldRollMixin.java"));
                assertTrue(worldRoll.contains("cineAppliedPose()"));
                assertTrue(worldRoll.contains("require = 1"));
                assertTrue(worldRoll.contains("renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"));
                String json = read(ProjectPaths.versionRoot().resolve(loader + "/src/main/resources/muonium.mixins.json"));
                assertTrue(json.contains("CineWorldRollMixin"));
            }
        }
        if (lane.startsWith("26.")) {
            assertTrue(camera.contains("alignWithEntity(F)V"));
        } else {
            assertTrue(camera.contains(lane.startsWith("1.21.11")
                    ? "setup(Lnet/minecraft/world/level/Level;"
                    : "setup(Lnet/minecraft/world/level/BlockGetter;"));
        }
    }

    @Test
    void fovUsesExactWorldProjectionTargetAndCancellableReturn() throws Exception {
        String fov = tree("mixin/CineFovMixin.java");
        assertTrue(fov.contains("cancellable = true"));
        assertTrue(fov.contains("fovOffsetDeg(partialTick)"));
        String lane = ProjectPaths.versionRoot().getFileName().toString();
        if (lane.startsWith("26.")) {
            assertTrue(fov.contains("calculateFov(F)F"));
            assertFalse(fov.contains("method = \"getFov"));
        } else {
            assertTrue(fov.contains("getFov(Lnet/minecraft/client/Camera;FZ)"));
            assertTrue(fov.contains(lane.equals("1.21.1") || lane.startsWith("1.20.1")
                    ? "CallbackInfoReturnable<Double>" : "CallbackInfoReturnable<Float>"));
        }
    }

    @Test
    void actualInputReplacesEntityPoseHeuristicsAndHostReconcilesOwnership() throws Exception {
        String probe = tree("compat/mc/CineProbe.java");
        assertTrue(probe.contains("implements CineDirector.SceneProbe"));
        assertTrue(probe.contains("public float playerPitch()"));
        assertTrue(probe.contains("public int dimensionKind()"));
        assertTrue(probe.contains("public String biomeKey()"));
        assertTrue(probe.contains("public long timeOfDay()"));
        assertTrue(probe.contains("INPUT.consume(mc.isWindowActive(), held)"));
        assertTrue(probe.contains("keyUp.isDown()"));
        assertTrue(probe.contains("mc.getCameraEntity() == player"));
        assertFalse(probe.contains("lastYaw"));
        assertFalse(probe.contains("lastX"));
        assertTrue(probe.contains("level.getEntity(entityId)"));
        assertTrue(probe.contains("CineSettings.excludeHostileMobs()"));
        String client = tree("MuoniumPlayerClient.java");
        assertTrue(client.contains("CineDirector.onClientTick("));
        assertTrue(client.contains("CineProbe.of(mc)"));
        assertTrue(client.contains("CineProbe.sync()"));
        String keys = tree("mixin/CineKeyboardMixin.java");
        String mouse = tree("mixin/CineMouseMixin.java");
        assertTrue(keys.contains("CineProbe.onKey("));
        assertTrue(mouse.contains("CineProbe.onButton("));
        assertTrue(mouse.contains("CineProbe.onScroll("));
        assertTrue(mouse.contains("CineProbe.onMove(") || mouse.contains("CineProbe.onRelativeMove("));
    }

    @Test
    void overlaysStayOnDeferredSkijaPathOutsideHiddenHud() throws Exception {
        String mixin = tree("mixin/CineHudMixin.java");
        assertTrue(mixin.contains("TAIL"));
        assertTrue(mixin.contains("deferPostCompositeOverlay"));
        assertFalse(mixin.contains("beginFrame"));
        String director = shared("core/cine/CineDirector.java");
        assertTrue(director.contains("CineTransition.render()"));
        assertTrue(director.contains("CineBars.render()"));
        String fovSample = director.substring(director.indexOf("public static float fovOffsetDeg("),
                director.indexOf("public static TransitionFrame currentTransition("));
        assertTrue(fovSample.contains("current.sample(partialTick)"));
        assertTrue(director.contains("frame.sample(partialTick)"));
        assertFalse(shared("core/cine/CineSession.java").contains("lastMeteredBeatNanos"));
        assertFalse(shared("core/cine/CineSession.java").contains("fovPunch"));
        assertFalse(shared("core/cine/CineSession.java").contains("playback.pulse()"));
        assertTrue(shared("core/cine/CineShots.java").contains("class ShotDeck"));
        assertTrue(shared("core/cine/CineShots.java").contains("nextEnvironment("));
        assertTrue(shared("core/cine/CineShots.java").contains("nextPlayer("));
        assertTrue(shared("core/cine/CineSession.java").contains("smootherstep(elapsed / 2.0d)"));
        assertTrue(shared("core/cine/CineSession.java").contains("CAMERA_POSITION_SPEED"));
        assertTrue(shared("core/cine/CineSession.java").contains("shot.rollDegrees"));
        assertTrue(shared("core/cine/CineSettings.java").contains("environmentShotPercent"));
        assertTrue(shared("core/cine/CineSession.java").contains("playback.grid()"));
        assertTrue(shared("core/cine/CineTransition.java").contains("SkijaGfx.canvas()"));
    }

    @Test
    void settingsAndBeatAnalysisKeepExistingSingleSource() throws Exception {
        assertTrue(shared("core/settings/ConfigPaths.java").contains("cine.json"));
        assertTrue(shared("core/cine/CineSettings.java").contains("ConfigPaths.CINE"));
        String beat = shared("core/ncm/music/CineBeatSource.java");
        assertTrue(beat.contains("FoliaTrackProfiler.peek"));
        assertTrue(beat.contains("BeatPulseSource.estimatedBeatForCine"));
        assertFalse(beat.contains("new AudioPlayer("));
        assertTrue(shared("core/cine/CineDirector.java").contains("BeatPulseSource.beat()"));
    }
}
