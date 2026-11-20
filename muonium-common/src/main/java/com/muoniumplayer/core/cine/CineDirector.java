package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.ncm.music.BeatPulseSource;
import com.muoniumplayer.core.ncm.music.CineBeatSource;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 卡点运镜的主线程入口。CineSession 独占调度状态；相机、FOV 和遮罩只消费不可变帧。
 * 世界对象不保留到渲染阶段，重复采样同一 partialTick 不会推进镜头或消费切镜标记。
 */
public final class CineDirector {

    private static final Logger LOG = LoggerFactory.getLogger("muonium-cine");
    private static final CineSession SESSION = new CineSession();
    private static volatile CineSession.Frame frame = CineSession.Frame.IDLE;

    /** 主线程场景窗口；渲染线程不得调用这些方法或持有实现对象。 */
    public interface SceneProbe {
        Object worldIdentity();

        Object playerIdentity();

        double playerX();

        double playerY();

        double playerZ();

        double playerEyeY();

        float playerYaw();

        float playerPitch();

        Object openScreen();

        boolean hurtThisTick();

        boolean inputThisTick();

        boolean skyVisible();

        int dimensionKind();

        String biomeKey();

        long timeOfDay();

        /** 活着且本地玩家持有相机；观战、外部相机、暂停时不可接管。 */
        boolean playerAlive();

        /** 返回从起点到目标间可达的相机位置，含近裁面余量；不命中返回目标。 */
        Vec3Holder clipCamera(double fromX, double fromY, double fromZ,
                              double toX, double toY, double toZ);

        MobSample nearestMovingMob(double rangeBlocks);

        /** 按实体标识刷新同一个主体，离场/失效返回 null；不重新选择另一只生物。 */
        MobSample trackedMob(int entityId, double rangeBlocks);
    }

    public record Vec3Holder(double x, double y, double z) {
    }

    public record MobSample(int entityId, double x, double y, double z, float bbHeight) {
    }

    public record TransitionFrame(int effect, double progress, boolean covering) {
    }

    private CineDirector() {
    }

    public static boolean isActive() {
        return frame.active();
    }

    public static void onClientTick(SceneProbe probe) {
        boolean wasActive = isActive();
        int previousShot = SESSION.shotSequence();
        long livedTicks = SESSION.activeTicks();
        try {
            CineSettings.Snapshot settings = CineSettings.snapshot();
            SESSION.tick(probe, settings, playback(settings.enabled()), System.nanoTime());
            frame = SESSION.frame();
            if (!wasActive && frame.active()) {
                LOG.info("[MuoniumPlayer/Cine] 运镜启动: 闲置 {}s", settings.idleSeconds());
            } else if (wasActive && !frame.active()) {
                LOG.info("[MuoniumPlayer/Cine] 运镜退出: 持续 {}s ({})", livedTicks / 20L, SESSION.stopReason());
            } else if (frame.active() && SESSION.shotSequence() != previousShot) {
                LOG.debug("[MuoniumPlayer/Cine] 切镜 #{}", SESSION.shotSequence());
            }
        } catch (RuntimeException | LinkageError failure) {
            SESSION.stop("内部异常");
            frame = SESSION.frame();
            LOG.error("[MuoniumPlayer/Cine] 运镜停止，已释放相机接管", failure);
        }
    }

    private static CineSession.Playback playback(boolean enabled) {
        if (!enabled) return CineSession.Playback.STOPPED;
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        if (snapshot == null || !snapshot.playing) return CineSession.Playback.STOPPED;
        // Advance the existing onset estimator even when HUD consumers are disabled.
        BeatPulseSource.beat();
        return new CineSession.Playback(true, snapshot.sessionId, snapshot.songId, snapshot.positionMs,
                CineBeatSource.currentGrid(), PlaybackPulse.loudness());
    }

    /** 同一 tick、同一 partialTick 的相机与 FOV 采样结果恒等，且没有世界查询。 */
    public static CinePose currentPose(float partialTick) {
        return frame.sample(partialTick);
    }

    public static float fovOffsetDeg(float partialTick) {
        CineSession.Frame current = frame;
        if (!current.active()) return 0f;
        CinePose pose = current.sample(partialTick);
        return pose.fovOffsetDeg();
    }

    /** 纯读：遮罩过期不能在这里修改调度状态，漏帧也由下一 tick 完成切镜。 */
    public static TransitionFrame currentTransition() {
        CineSession.Frame current = frame;
        return current.active() && current.transition() != null
                ? current.transition().at(System.nanoTime()) : null;
    }

    public static float cinematicBarFraction() {
        return frame.barFraction();
    }

    public static Runnable cineOverlayRunnable() {
        CineSession.Frame current = frame;
        if (!current.active() || current.transition() == null && current.barFraction() <= 0f) return null;
        long generation = current.generation();
        return () -> {
            if (!frame.active() || frame.generation() != generation) return;
            CineTransition.render();
            CineBars.render();
        };
    }
}
