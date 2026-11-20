package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.ncm.music.CineBeatSource;

/** Main-thread Cine state; rendering only samples immutable frames. */
final class CineSession {

    private static final long FALLBACK_SHOT_NANOS = 12_000_000_000L;
    private static final long TICK_NANOS = 50_000_000L;
    private static final double TICK_SECONDS = 0.05d;
    private static final double CAMERA_POSITION_ACCEL = 12d;
    private static final double CAMERA_POSITION_SPEED = 5d;
    private static final double CAMERA_ANGLE_ACCEL = 90d;
    private static final double CAMERA_ANGLE_SPEED = 45d;
    private static final double CAMERA_FOV_ACCEL = 6d;
    private static final double CAMERA_FOV_SPEED = 3d;

    record Playback(boolean playing, long sessionId, long songId, long positionMillis,
                    CineBeatSource.Grid grid, float energy) {
        static final Playback STOPPED = new Playback(false, 0L, 0L, 0L, null, 0f);
    }


    record Transition(int effect, long startNanos, long cutNanos, long endNanos, boolean applied) {
        CineDirector.TransitionFrame at(long now) {
            if (effect == 0 || now < startNanos || applied && now >= endNanos) return null;
            boolean covering = !applied || now < cutNanos;
            long start = covering ? startNanos : cutNanos;
            long end = covering ? cutNanos : endNanos;
            double progress = Math.max(0d, Math.min(1d,
                    (now - start) / (double) Math.max(1L, end - start)));
            return new CineDirector.TransitionFrame(effect, progress, covering);
        }
    }

    record Motion(double x, double y, double z, double yaw, double pitch, double roll, double fov) {
        static final Motion ZERO = new Motion(0d, 0d, 0d, 0d, 0d, 0d, 0d);
    }

    record Frame(long generation, CinePose previous, CinePose current,
                 Transition transition, float barFraction, Motion previousMotion, Motion currentMotion) {
        static final Frame IDLE = new Frame(0L, null, null, null, 0f, Motion.ZERO, Motion.ZERO);
        boolean active() { return current != null; }
        CinePose sample(float partialTick) {
            return current == null ? null
                    : interpolateMotion(previous, current, previousMotion, currentMotion, partialTick);
        }
    }

    private record Scene(Object world, Object player, double x, double y, double z,
                         double eyeY, float yaw, float pitch, boolean skyVisible,
                         int dimensionKind, String biomeKey, long timeOfDay) {
        boolean valid() {
            return world != null && player != null && Double.isFinite(x) && Double.isFinite(y)
                    && Double.isFinite(z) && Double.isFinite(eyeY)
                    && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }

    private record Target(CineDirector.Vec3Holder position, CineDirector.Vec3Holder focus) { }
    private record Step(double value, double velocity) { }
    private record VectorStep(double x, double y, double z, double vx, double vy, double vz) { }

    static CinePose interpolateMotion(CinePose previous, CinePose current,
                                      Motion previousMotion, Motion currentMotion, float partialTick) {
        if (previous == null || previous == current) return current;
        float t = Float.isFinite(partialTick) ? Math.max(0f, Math.min(1f, partialTick)) : 1f;
        double yawEnd = previous.yawDeg()
                + CineShots.wrapDegrees(current.yawDeg() - previous.yawDeg());
        return new CinePose(
                hermite(previous.x(), current.x(), previousMotion.x(), currentMotion.x(), t),
                hermite(previous.y(), current.y(), previousMotion.y(), currentMotion.y(), t),
                hermite(previous.z(), current.z(), previousMotion.z(), currentMotion.z(), t),
                CineShots.wrapDegrees((float) hermite(previous.yawDeg(), yawEnd,
                        previousMotion.yaw(), currentMotion.yaw(), t)),
                (float) hermite(previous.pitchDeg(), current.pitchDeg(), previousMotion.pitch(), currentMotion.pitch(), t),
                (float) hermite(previous.rollDeg(), current.rollDeg(), previousMotion.roll(), currentMotion.roll(), t),
                (float) hermite(previous.fovOffsetDeg(), current.fovOffsetDeg(), previousMotion.fov(), currentMotion.fov(), t));
    }

    private static double hermite(double start, double end, double startVelocity, double endVelocity, double t) {
        if (t <= 0d) return start;
        if (t >= 1d) return end;
        double delta = end - start;
        if (Math.abs(delta) < 1.0e-9d) return start;
        double startTangent = finite(startVelocity) * TICK_SECONDS;
        double endTangent = finite(endVelocity) * TICK_SECONDS;
        double alpha = startTangent / delta;
        double beta = endTangent / delta;
        if (alpha < 0d) alpha = startTangent = 0d;
        if (beta < 0d) beta = endTangent = 0d;
        double magnitude = alpha * alpha + beta * beta;
        if (magnitude > 9d) {
            double scale = 3d / Math.sqrt(magnitude);
            startTangent = scale * alpha * delta;
            endTangent = scale * beta * delta;
        }
        double t2 = t * t;
        double t3 = t2 * t;
        return (2d * t3 - 3d * t2 + 1d) * start
                + (t3 - 2d * t2 + t) * startTangent
                + (-2d * t3 + 3d * t2) * end
                + (t3 - t2) * endTangent;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0d;
    }

    private Frame frame = Frame.IDLE;
    private Object world;
    private Object player;
    private long idleTicks;
    private long activeTicks;
    private long generation;
    private int shotSequence;
    private long songId;
    private long playbackSessionId;
    private long playbackPosition;
    private long previousTickNanos;
    private long cutDeadline;
    private boolean plannedWithGrid;
    private Transition transition;
    private boolean transitionCut;
    private boolean returning;
    private double motionSeconds;
    private double shotMotionStart;
    private double orbitStartDegrees;
    private double lastPlayerX, lastPlayerY, lastPlayerZ;
    private double xVelocity, yVelocity, zVelocity, yawVelocity, pitchVelocity, rollVelocity;
    private double fovVelocity;
    private final CineShots.ShotDeck shotDeck = new CineShots.ShotDeck(0xC1E5EEDL);
    private CineShots.Shot shot = CineShots.Shot.ORBIT_WIDE;
    private float shotFovFrom;
    private double shotFovStartMotion;
    private float energy;
    private CineDirector.MobSample mob;
    private String stopReason;

    Frame frame() { return frame; }
    String stopReason() { return stopReason; }
    long activeTicks() { return activeTicks; }
    int shotSequence() { return shotSequence; }

    void tick(CineDirector.SceneProbe probe, CineSettings.Snapshot settings, Playback playback, long now) {
        if (!returning) stopReason = null;
        boolean input = probe != null && probe.inputThisTick();
        boolean hurt = probe != null && probe.hurtThisTick();
        Scene scene = probe == null ? null : new Scene(probe.worldIdentity(), probe.playerIdentity(),
                probe.playerX(), probe.playerY(), probe.playerZ(), probe.playerEyeY(), probe.playerYaw(),
                probe.playerPitch(), probe.skyVisible(), probe.dimensionKind(), probe.biomeKey(), probe.timeOfDay());
        if (scene == null || !scene.valid() || !probe.playerAlive()) {
            stop("不在世界/相机不可接管");
            world = player = null;
            return;
        }
        boolean contextChanged = world != scene.world() || player != scene.player();
        if (contextChanged) {
            stop("世界/玩家变化");
            world = scene.world();
            player = scene.player();
            lastPlayerX = scene.x();
            lastPlayerY = scene.y();
            lastPlayerZ = scene.z();
            return;
        }
        world = scene.world();
        player = scene.player();

        Object screen = probe.openScreen();
        String exitReason = !settings.enabled() ? "开关关闭" : screen != null ? "打开界面"
                : hurt ? "受伤" : input ? "键鼠输入" : !playback.playing() ? "音乐停止" : null;
        if (returning) {
            double dx = scene.x() - lastPlayerX;
            double dy = scene.y() - lastPlayerY;
            double dz = scene.z() - lastPlayerZ;
            lastPlayerX = scene.x();
            lastPlayerY = scene.y();
            lastPlayerZ = scene.z();
            if (dx * dx + dy * dy + dz * dz > 64d) shiftFrame(dx, dy, dz);
            if (exitReason != null) stopReason = exitReason;
            tickReturn(probe, scene, settings);
            return;
        }
        if (exitReason != null) {
            if (frame.active()) beginReturn(scene, exitReason);
            else idleTicks = 0L;
            if (returning) tickReturn(probe, scene, settings);
            return;
        }
        if (!frame.active()) {
            if (++idleTicks < settings.idleSeconds() * 20L) return;
            start(probe, scene, settings, playback, now);
            return;
        }

        activeTicks++;
        motionSeconds += TICK_SECONDS * settings.speedMultiplier();
        energy += (unit(playback.energy()) - energy) * 0.08f;
        boolean playbackChanged = playbackSessionId != playback.sessionId() || songId != playback.songId();
        long expectedAdvance = Math.max(0L, now - previousTickNanos) / 1_000_000L;
        boolean seeked = Math.abs(playback.positionMillis() - playbackPosition - expectedAdvance) > 1_500L;
        rememberPlayback(playback, now);
        double playerDx = scene.x() - lastPlayerX;
        double playerDy = scene.y() - lastPlayerY;
        double playerDz = scene.z() - lastPlayerZ;
        lastPlayerX = scene.x();
        lastPlayerY = scene.y();
        lastPlayerZ = scene.z();
        boolean teleported = playerDx * playerDx + playerDy * playerDy + playerDz * playerDz > 64d;
        if (teleported) {
            shiftFrame(playerDx, playerDy, playerDz);
            transition = null;
            transitionCut = false;
            prepareShot(probe, scene, settings, playback.grid(), now);
        }
        if (playbackChanged || seeked) {
            transition = null;
            transitionCut = false;
            shotFovFrom = shotFovValue();
            shotFovStartMotion = motionSeconds;
            planCut(settings, playback.grid(), now, now);
        }

        if (transition == null && metered(playback.grid()) && !plannedWithGrid) {
            planCut(settings, playback.grid(), now, now);
        }
        if (transition == null && now >= cutDeadline - halfTransition(settings)) {
            long half = halfTransition(settings);
            transition = new Transition(pickEffect(settings.transitionMask()), cutDeadline - half,
                    cutDeadline, cutDeadline + half, false);
            transitionCut = false;
        }
        if (transition != null) {
            if (!transitionCut && now >= transition.cutNanos()) {
                long revealDuration = transition.endNanos() - transition.cutNanos();
                prepareShot(probe, scene, settings, playback.grid(), now);
                transition = new Transition(transition.effect(), transition.startNanos(), now,
                        now + revealDuration, true);
                transitionCut = true;
            }
            if (now >= transition.endNanos()) transition = null;
        }
        if (mob != null) {
            mob = probe.trackedMob(mob.entityId(), settings.mobFocusRange() * 1.35d);
            if (mob == null) prepareShot(probe, scene, settings, playback.grid(), now);
        }
        publish(probe, scene, settings);
    }

    private void start(CineDirector.SceneProbe probe, Scene scene, CineSettings.Snapshot settings,
                       Playback playback, long now) {
        idleTicks = activeTicks = 0L;
        generation++;
        shotSequence = -1;
        motionSeconds = 0d;
        energy = unit(playback.energy());
        shotFovFrom = 0f;
        shotFovStartMotion = motionSeconds;
        transition = null;
        transitionCut = false;
        resetVelocity();
        rememberPlayback(playback, now);
        lastPlayerX = scene.x();
        lastPlayerY = scene.y();
        lastPlayerZ = scene.z();
        prepareShot(probe, scene, settings, playback.grid(), now);
        CinePose vanilla = playerEyePose(scene);
        frame = new Frame(generation, vanilla, vanilla, null,
                settings.showCinematicBars() ? settings.cinematicBarPercent() / 100f : 0f,
                Motion.ZERO, Motion.ZERO);
        publish(probe, scene, settings);
    }

    private void beginReturn(Scene scene, String reason) {
        stopReason = reason;
        idleTicks = 0L;
        transition = null;
        transitionCut = false;
        mob = null;
        shotFovFrom = frame.current().fovOffsetDeg();
        shotFovStartMotion = motionSeconds;
        returning = true;
    }

    private void tickReturn(CineDirector.SceneProbe probe, Scene scene, CineSettings.Snapshot settings) {
        motionSeconds += TICK_SECONDS;
        CinePose target = CinePose.of(scene.x(), scene.eyeY(), scene.z(), scene.yaw(),
                scene.pitch(), 0f);
        CinePose desired = new CinePose(target.x(), target.y(), target.z(), target.yawDeg(),
                target.pitchDeg(), 0f, 0f);
        publishPose(probe, scene, desired, TICK_SECONDS, 0f, null);
        CinePose current = frame.current();
        if (near(current, target)) {
            frame = Frame.IDLE;
            returning = false;
            idleTicks = 0L;
            resetVelocity();
        }
    }

    private static boolean near(CinePose current, CinePose target) {
        return distanceSquared(current, target) <= 0.01d
                && Math.abs(CineShots.wrapDegrees(current.yawDeg() - target.yawDeg())) <= 0.1f
                && Math.abs(current.pitchDeg() - target.pitchDeg()) <= 0.1f
                && Math.abs(current.rollDeg()) <= 0.1f
                && Math.abs(current.fovOffsetDeg()) <= 0.01f;
    }

    private static double distanceSquared(CinePose a, CinePose b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private void releaseImmediately(String reason) {
        if (frame.active()) stopReason = reason;
        frame = Frame.IDLE;
        idleTicks = activeTicks = 0L;
        returning = false;
        transition = null;
        transitionCut = false;
        mob = null;
        energy = 0f;
        shotFovFrom = 0f;
        shotFovStartMotion = 0d;
        resetVelocity();
    }

    void stop(String reason) {
        releaseImmediately(reason);
    }

    private void resetVelocity() {
        xVelocity = yVelocity = zVelocity = 0d;
        yawVelocity = pitchVelocity = rollVelocity = fovVelocity = 0d;
    }

    private static Step spring(double current, double target, double velocity, double acceleration,
                               double maxSpeed, double dt) {
        double error = target - current;
        double desired = clamp(error / Math.max(dt, 1.0e-4d), -maxSpeed, maxSpeed);
        double nextVelocity = clamp(velocity + clamp(desired - velocity, -acceleration * dt, acceleration * dt),
                -maxSpeed, maxSpeed);
        double next = current + nextVelocity * dt;
        return (target - next) * error <= 0d ? new Step(target, 0d) : new Step(next, nextVelocity);
    }

    private static Step springAngle(double current, double target, double velocity, boolean wrap,
                                    double acceleration, double maxSpeed, double dt) {
        double error = wrap ? CineShots.wrapDegrees((float) (target - current)) : target - current;
        double desired = clamp(error / Math.max(dt, 1.0e-4d), -maxSpeed, maxSpeed);
        double nextVelocity = clamp(velocity + clamp(desired - velocity,
                -acceleration * dt, acceleration * dt), -maxSpeed, maxSpeed);
        double step = nextVelocity * dt;
        if (Math.abs(step) >= Math.abs(error)) return new Step(current + error, 0d);
        return new Step(current + step, nextVelocity);
    }

    private static VectorStep springVector(double x, double y, double z, double targetX, double targetY,
                                           double targetZ, double velocityX, double velocityY,
                                           double velocityZ, double acceleration, double maxSpeed, double dt) {
        double errorX = targetX - x;
        double errorY = targetY - y;
        double errorZ = targetZ - z;
        double errorLength = Math.sqrt(errorX * errorX + errorY * errorY + errorZ * errorZ);
        double desiredSpeed = Math.min(maxSpeed, errorLength / Math.max(dt, 1.0e-4d));
        double desiredScale = errorLength <= 1.0e-9d ? 0d : desiredSpeed / errorLength;
        double desiredX = errorX * desiredScale;
        double desiredY = errorY * desiredScale;
        double desiredZ = errorZ * desiredScale;
        double changeX = desiredX - velocityX;
        double changeY = desiredY - velocityY;
        double changeZ = desiredZ - velocityZ;
        double changeLength = Math.sqrt(changeX * changeX + changeY * changeY + changeZ * changeZ);
        double maxChange = acceleration * dt;
        if (changeLength > maxChange && changeLength > 0d) {
            double scale = maxChange / changeLength;
            changeX *= scale;
            changeY *= scale;
            changeZ *= scale;
        }
        double nextVelocityX = velocityX + changeX;
        double nextVelocityY = velocityY + changeY;
        double nextVelocityZ = velocityZ + changeZ;
        double speed = Math.sqrt(nextVelocityX * nextVelocityX + nextVelocityY * nextVelocityY
                + nextVelocityZ * nextVelocityZ);
        if (speed > maxSpeed && speed > 0d) {
            double scale = maxSpeed / speed;
            nextVelocityX *= scale;
            nextVelocityY *= scale;
            nextVelocityZ *= scale;
        }
        double stepX = nextVelocityX * dt;
        double stepY = nextVelocityY * dt;
        double stepZ = nextVelocityZ * dt;
        if (stepX * errorX + stepY * errorY + stepZ * errorZ >= errorLength * errorLength) {
            return new VectorStep(targetX, targetY, targetZ, 0d, 0d, 0d);
        }
        return new VectorStep(x + stepX, y + stepY, z + stepZ,
                nextVelocityX, nextVelocityY, nextVelocityZ);
    }

    private static double[] clampVector(double x, double y, double z, double maxLength) {
        double length = Math.sqrt(x * x + y * y + z * z);
        double scale = length > maxLength && length > 0d ? maxLength / length : 1d;
        return new double[]{x * scale, y * scale, z * scale};
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static CinePose playerEyePose(Scene scene) {
        return CinePose.of(scene.x(), scene.eyeY(), scene.z(), scene.yaw(), scene.pitch(), 0f);
    }

    private void shiftFrame(double dx, double dy, double dz) {
        CinePose current = frame.current();
        CinePose previous = frame.previous();
        if (current == null || previous == null) return;
        Motion previousMotion = frame.currentMotion();
        frame = new Frame(frame.generation(), shifted(previous, dx, dy, dz),
                shifted(current, dx, dy, dz), frame.transition(), frame.barFraction(),
                previousMotion, previousMotion);
    }

    private static CinePose shifted(CinePose pose, double dx, double dy, double dz) {
        return new CinePose(pose.x() + dx, pose.y() + dy, pose.z() + dz,
                pose.yawDeg(), pose.pitchDeg(), pose.rollDeg(), pose.fovOffsetDeg());
    }

    private void rememberPlayback(Playback playback, long now) {
        playbackSessionId = playback.sessionId();
        songId = playback.songId();
        playbackPosition = playback.positionMillis();
        previousTickNanos = now;
    }

    private void prepareShot(CineDirector.SceneProbe probe, Scene scene, CineSettings.Snapshot settings,
                             CineBeatSource.Grid grid, long now) {
        CinePose current = frame.current();
        shotFovFrom = current == null ? 0f : current.fovOffsetDeg();
        shotFovStartMotion = motionSeconds;
        shotSequence++;
        shotMotionStart = motionSeconds;
        orbitStartDegrees = scene.yaw() + 75d + shotSequence * 57d;
        CineDirector.MobSample candidate = settings.enableMobFocus() && shotSequence % 4 == 2
                ? probe.nearestMovingMob(settings.mobFocusRange()) : null;
        CineShots.Shot mobShot = candidate == null ? null : shotDeck.takeMobIfAvailable();
        if (mobShot != null) {
            shot = mobShot;
        } else {
            int environmentRemaining = settings.environmentShotPercent();
            boolean chooseEnvironment = Math.floorMod(nextSceneRandom(), 100L) < environmentRemaining;
            shot = chooseEnvironment
                    ? shotDeck.nextEnvironment(scene.dimensionKind(), scene.biomeKey(), scene.timeOfDay(), scene.skyVisible())
                    : shotDeck.nextPlayer(scene.dimensionKind(), scene.biomeKey(), scene.timeOfDay(), scene.skyVisible());
            if (shot == null) {
                shot = chooseEnvironment
                        ? shotDeck.nextPlayer(scene.dimensionKind(), scene.biomeKey(), scene.timeOfDay(), scene.skyVisible())
                        : shotDeck.nextEnvironment(scene.dimensionKind(), scene.biomeKey(), scene.timeOfDay(),
                                scene.skyVisible());
            }
            if (shot == null) throw new IllegalStateException("当前场景没有可用镜头");
        }
        mob = shot == CineShots.Shot.MOB_FOLLOW || shot == CineShots.Shot.ENV_WILDLIFE_WATCH ? candidate : null;
        planCut(settings, grid, now, now);
    }

    private void planCut(CineSettings.Snapshot settings, CineBeatSource.Grid grid, long shotStart, long now) {
        plannedWithGrid = metered(grid);
        long duration = FALLBACK_SHOT_NANOS;
        if (plannedWithGrid) {
            double beats = settings.shotBeats() * (1.6d - 0.8d * energy);
            duration = (long) Math.max(settings.minShotSeconds() * 1.0e9d,
                    beats * grid.beatPeriodNanos());
        }
        long earliest = Math.max(shotStart + duration, now + halfTransition(settings) * 2L + TICK_NANOS);
        cutDeadline = plannedWithGrid ? grid.nextBarAtOrAfter(earliest) : earliest;
    }

    private float shotFovValue() {
        double elapsed = Math.max(0d, motionSeconds - shotFovStartMotion);
        float blend = (float) CineShots.smootherstep(elapsed / 2.0d);
        return shotFovFrom + (shot.fovOffset - shotFovFrom) * blend;
    }

    private void publish(CineDirector.SceneProbe probe, Scene scene, CineSettings.Snapshot settings) {
        Target target = target(scene);
        CineDirector.Vec3Holder safe = probe.clipCamera(scene.x(), scene.eyeY(), scene.z(),
                target.position().x(), target.position().y(), target.position().z());
        double dx = target.focus().x() - safe.x();
        double dy = target.focus().y() - safe.y();
        double dz = target.focus().z() - safe.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy,
                Math.max(1.0e-5d, Math.hypot(dx, dz))));
        double shotElapsed = Math.max(0d, motionSeconds - shotMotionStart);
        CinePose desired = new CinePose(safe.x(), safe.y(), safe.z(), CineShots.wrapDegrees(yaw),
                Math.max(-89.9f, Math.min(89.9f, pitch)),
                (float) (shot.rollDegrees * CineShots.smootherstep(Math.min(1d, shotElapsed / 2d))),
                shotFovValue());
        float bars = settings.showCinematicBars() ? settings.cinematicBarPercent() / 100f : 0f;
        publishPose(probe, scene, desired, TICK_SECONDS, bars, transition);
    }

    private void publishPose(CineDirector.SceneProbe probe, Scene scene, CinePose desired,
                             double dt, float bars, Transition frameTransition) {
        CinePose old = frame.current();
        if (old == null) {
            old = playerEyePose(scene);
            frame = new Frame(generation, old, old, null, bars, Motion.ZERO, Motion.ZERO);
        }
        VectorStep position = springVector(old.x(), old.y(), old.z(), desired.x(), desired.y(), desired.z(),
                xVelocity, yVelocity, zVelocity, CAMERA_POSITION_ACCEL, CAMERA_POSITION_SPEED, dt);
        CineDirector.Vec3Holder path = probe.clipCamera(old.x(), old.y(), old.z(),
                position.x(), position.y(), position.z());
        boolean clipped = Math.abs(path.x() - position.x()) + Math.abs(path.y() - position.y())
                + Math.abs(path.z() - position.z()) > 1.0e-6d;
        if (clipped) {
            double[] velocity = clampVector((path.x() - old.x()) / dt, (path.y() - old.y()) / dt,
                    (path.z() - old.z()) / dt, CAMERA_POSITION_SPEED);
            xVelocity = velocity[0];
            yVelocity = velocity[1];
            zVelocity = velocity[2];
        } else {
            xVelocity = position.vx();
            yVelocity = position.vy();
            zVelocity = position.vz();
        }
        Step yaw = springAngle(old.yawDeg(), desired.yawDeg(), yawVelocity, true,
                CAMERA_ANGLE_ACCEL, CAMERA_ANGLE_SPEED, dt);
        Step pitch = springAngle(old.pitchDeg(), desired.pitchDeg(), pitchVelocity, false,
                CAMERA_ANGLE_ACCEL, CAMERA_ANGLE_SPEED, dt);
        Step roll = springAngle(old.rollDeg(), desired.rollDeg(), rollVelocity, true,
                CAMERA_ANGLE_ACCEL, CAMERA_ANGLE_SPEED, dt);
        Step fov = spring(old.fovOffsetDeg(), desired.fovOffsetDeg(), fovVelocity,
                CAMERA_FOV_ACCEL, CAMERA_FOV_SPEED, dt);
        yawVelocity = yaw.velocity();
        pitchVelocity = pitch.velocity();
        rollVelocity = roll.velocity();
        fovVelocity = fov.velocity();
        CinePose pose = new CinePose(path.x(), path.y(), path.z(), CineShots.wrapDegrees((float) yaw.value()),
                (float) pitch.value(), (float) roll.value(), (float) fov.value());
        if (!pose.isFinite()) throw new IllegalStateException("运镜生成了非有限相机位姿");
        Motion previousMotion = frame.currentMotion();
        Motion currentMotion = new Motion(xVelocity, yVelocity, zVelocity,
                yawVelocity, pitchVelocity, rollVelocity, fovVelocity);
        frame = new Frame(generation, old, pose, frameTransition, bars, previousMotion, currentMotion);
    }

    private Target target(Scene scene) {
        double age = Math.max(0d, motionSeconds - shotMotionStart);
        CineShots.Style style = shot.style;
        boolean environment = CineShots.ShotDeck.isEnvironment(shot);
        boolean landscape = environment && shot.domain != CineShots.Domain.MOB;
        double centerX = landscape ? scene.x() : mob == null ? scene.x() : mob.x();
        double centerY = landscape ? scene.y() : mob == null ? scene.y() : mob.y();
        double centerZ = landscape ? scene.z() : mob == null ? scene.z() : mob.z();
        double focusY = mob == null ? scene.y() + shot.focusHeight
                : mob.y() + Math.max(0.65d, mob.bbHeight() * 0.55d);
        double cameraAngle = shot.cameraAngle + (environment ? 0d : scene.yaw());
        double radius = shot.radius;
        if (style == CineShots.Style.DOLLY_IN) {
            radius = shot.radius - Math.max(0d, shot.radius - 3.4d) * CineShots.smootherstep(age / 7d);
        } else if (style == CineShots.Style.DOLLY_OUT) {
            radius = shot.radius + 4.5d * CineShots.smootherstep(age / 7d);
        } else if (style == CineShots.Style.ORBIT) {
            cameraAngle = orbitStartDegrees + age * 18d;
        } else if (style == CineShots.Style.PAN) {
            cameraAngle += Math.sin(age * 0.12d) * 8d;
        }
        double cameraRadians = Math.toRadians(cameraAngle);
        // 机位角沿用旧约定：人物镜头 c=0 在人物背后、180 在面前；环境镜头为世界方位角。
        double cameraX = centerX + Math.sin(cameraRadians) * radius;
        double cameraY = centerY + shot.cameraHeight;
        double cameraZ = centerZ - Math.cos(cameraRadians) * radius;
        if (style == CineShots.Style.ORBIT) cameraY += Math.sin(age * 0.24d) * 0.18d;

        double focusX = centerX;
        double focusZ = centerZ;
        if (shot.focusDistance > 0d) {
            double focusRadians = Math.toRadians((environment ? 0d : scene.yaw()) + shot.focusAngle);
            focusX = scene.x() - Math.sin(focusRadians) * shot.focusDistance;
            focusZ = scene.z() + Math.cos(focusRadians) * shot.focusDistance;
        }
        if (style == CineShots.Style.LANDSCAPE || style == CineShots.Style.SKY) {
            focusY += Math.sin(age * 0.07d) * 0.05d;
        }
        if (style == CineShots.Style.CAVE) {
            double detailRadians = Math.toRadians((environment ? 0d : scene.yaw()) + shot.focusAngle
                    + (shotSequence % 2 == 0 ? 0d : -35d));
            focusX = scene.x() - Math.sin(detailRadians) * Math.max(2d, shot.focusDistance);
            focusY = scene.y() + shot.focusHeight + Math.sin(age * 0.16d) * 0.08d;
            focusZ = scene.z() + Math.cos(detailRadians) * Math.max(2d, shot.focusDistance);
        }
        if (style == CineShots.Style.PAN) {
            double drift = Math.sin(age * 0.08d) * Math.min(0.55d, Math.abs(shot.sweepDegrees) * 0.01d);
            cameraX += Math.cos(cameraRadians) * drift;
            cameraZ += Math.sin(cameraRadians) * drift;
        }
        return new Target(new CineDirector.Vec3Holder(cameraX, cameraY, cameraZ),
                new CineDirector.Vec3Holder(focusX, focusY, focusZ));
    }

    private static long halfTransition(CineSettings.Snapshot settings) {
        return (long) (settings.transitionSeconds() * 0.5e9d);
    }

    private static boolean metered(CineBeatSource.Grid grid) {
        return grid != null && grid.isMetered();
    }

    private static float unit(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }

    private long rngState = 0x9E3779B97F4A7C15L;

    private long nextSceneRandom() {
        rngState ^= rngState << 13;
        rngState ^= rngState >>> 7;
        rngState ^= rngState << 17;
        return rngState;
    }

    int pickEffect(int mask) {
        mask &= CineSettings.TRANSITION_MASK_ALL;
        if (mask == 0) return 0;
        int index = (int) Math.floorMod(nextSceneRandom(), Integer.bitCount(mask));
        for (int effect = 0; effect < 5; effect++) {
            if ((mask & (1 << effect)) != 0 && index-- == 0) return effect + 1;
        }
        throw new IllegalStateException("运镜转场掩码无有效特效");
    }
}
