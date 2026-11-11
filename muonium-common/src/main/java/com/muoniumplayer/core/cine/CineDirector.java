package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.ncm.music.CineBeatSource;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 「卡点运镜」核心调度器：播放中闲置后按音乐节拍自动运镜。
 *
 * <h3>状态机</h3>
 * <pre>IDLE（计时）→ ACTIVE（运镜）→ IDLE</pre>
 * 进入：开关开 + 音乐在播 + 玩家在世界 + 无输入/无界面/无受伤持续 idleSeconds。
 * 退出：任意输入、任意界面打开、受伤、暂停、死亡、开关关——立即恢复玩家相机与 HUD。
 *
 * <h3>卡点调度（与 Ambient Camera 的定时器切镜的本质差异）</h3>
 * <ul>
 *   <li>镜头时长以<b>拍</b>计：基准 shotBeats 拍，副歌（能量高）缩短、主歌拉长，
 *       网格置信度 0 时退化为固定 12 秒；</li>
 *   <li>切镜落点<b>压在小节第一拍</b>（downbeat）；转场在拍前 0.3 拍起（J-cut），
 *       拍点落定新机位；</li>
 *   <li>段落边界（sectionMillis 变化）必切「英雄镜头」（CRANE 大升降或大半径 ORBIT）；</li>
 *   <li>防腻：不连续同族、方位角差 ≥45°、每 4 个镜头夹一个低运动量呼吸镜头。</li>
 * </ul>
 *
 * <h3>线程与依赖纪律</h3>
 * <p>tick 桥（树侧每 tick 调 {@link #onClientTick}）驱动状态机与切镜决策；渲染帧
 * （树侧 Camera mixin 调 {@link #currentPose}）只做插值取值。共享层零 MC 符号：
 * 世界查询走 {@link SceneProbe}（树侧 compat 实现），仿 AcousticsRuntime.ListenerSampler 模式。</p>
 *
 * <p>节拍只读 {@link CineBeatSource}（网格）与 {@link PlaybackPulse}（能量兜底），
 * 不自带音频分析。</p>
 */
public final class CineDirector {

    /**
     * 日志走 slf4j（MC 自带，muonium-lan 同款先例）：neoforge 侧的日志后端
     * <b>吞 System.out</b>（1.21.1/26.x neoforge 全部历史会话 stdout=0，fabric 正常），
     * 之前用 println 时运镜启没启动在 neoforge 日志里完全不可观测——
     * 排查「1.21.1/1.21.11 运镜不生效」时因此只能靠代码审查定位。
     */
    private static final Logger LOG = LoggerFactory.getLogger("muonium-cine");

    // ---------------- SceneProbe：共享层的世界窗口（树侧 compat 实现） ----------------

    /**
     * 树侧注入的世界采样器 + 执行器。实现必须瞬时返回（不投线程）——
     * tick 桥在主线程，渲染 mixin 在渲染线程，两边各自构造/复用 probe。
     */
    public interface SceneProbe {
        /** 玩家脚底坐标；不在世界返回 NaN。 */
        double playerX();

        double playerY();

        double playerZ();

        /** 玩家朝向（度）。 */
        float playerYaw();

        float playerPitch();

        /** 当前打开的界面（null = 无）。 */
        Object openScreen();

        /** 玩家最近是否受伤（掉血事件沿）；实现自管理沿检测。 */
        boolean hurtThisTick();

        /** 任意键鼠输入沿（实现自管理沿检测）；true = 玩家动了一下。 */
        boolean inputThisTick();

        /** 是否存活（死亡/观战立即退出运镜）。 */
        boolean playerAlive();

        /** 世界射线：从 from 到 to 之间有没有挡住的方块（VISUAL 口径，不含流体）。 */
        boolean clipBlocked(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);

        /**
         * 找最近的合格移动生物（半径内、可见移动、不隐形）；没有返回 null。
         * 排除规则（敌对/蝙蝠）由实现读 {@link CineSettings}。
         */
        MobSample nearestMovingMob(double rangeBlocks);
    }

    /** 生物候选的纯数据视图。 */
    public record MobSample(double x, double y, double z, float bbHeight, boolean hostile) {
    }

    // ---------------- 状态机状态 ----------------

    private static volatile boolean active;
    private static long idleTicks;
    private static long activeTicks;

    // ---------------- 镜头执行核心（逐行为对齐 AmbientCameraClient） ----------------

    /**
     * 四镜头固定序列（参数原样移植自 AmbientCameraClient.SHOTS）：
     * PLAYER 环绕 → SCENERY → MOB_OR_SCENERY → 高位 SCENERY。
     * record 参数序：(kind, radius, height, focusHeight, focusForward, angularSpeedDegPerSec, sideOffset, backOffset)。
     */
    private static final Shot[] SHOTS = {
            new Shot(ShotKind.PLAYER, 5.5, 1.9, 1.3, 0.0, 6.0, 0.0, 0.0),
            new Shot(ShotKind.SCENERY, 0.0, 3.1, 0.0, 0.0, 0.0, 4.2, 2.2),
            new Shot(ShotKind.MOB_OR_SCENERY, 4.8, 2.2, 0.0, 0.0, 5.0, -3.5, 2.8),
            new Shot(ShotKind.SCENERY, 0.0, 7.0, 0.0, 0.0, 0.0, -5.0, 5.0)
    };

    /** 风景镜头的朝向偏移表（原样移植）。 */
    private static final double[] SCENERY_YAW_OFFSETS = {35.0, -60.0, 85.0, -105.0, 145.0};

    private enum ShotKind { PLAYER, SCENERY, MOB_OR_SCENERY }

    private record Shot(ShotKind kind, double radius, double height, double focusHeight,
                        double focusForward, double angularSpeedDegPerSec,
                        double sideOffset, double backOffset) {
    }

    /** 不可变三维向量（共享层零 MC 符号，自持一份）。 */
    public record Vec3Holder(double x, double y, double z) {
    }

    /** 一帧机位目标：焦点（看向哪）+ 相机位置。 */
    private record CameraTarget(Vec3Holder focus, Vec3Holder position) {
    }

    /** 当前镜头序列状态（命名与语义对齐 AmbientCameraClient）。 */
    private static int currentShotSequence = -1;
    private static Shot currentShot;
    private static double orbitAngleDegrees;
    private static double motionTimeSeconds;   // tick 累计：每 tick + cameraSpeed/20
    private static Vec3Holder sceneryDirection = new Vec3Holder(0, 0, 1);
    private static Vec3Holder sceneryFocus = new Vec3Holder(0, 0, 0);
    private static boolean cutPending;
    private static MobSample trackedMob;

    /**
     * 渲染帧专用的 probe 引用：tick 桥每次喂进来的 probe 是无状态轻对象，
     * currentPose（渲染线程）没有 probe 参数，但 calculateTarget 需要玩家
     * 位置/朝向 —— 树侧 probe 实现是只读快照，渲染线程直接读是安全的
     * （与 AmbientCameraClient 直接读 Minecraft 单例同语义）。tick 桥每次
     * onClientTick 刷新。
     */
    private static volatile SceneProbe currentProbeForPose;

    // 平滑状态（对齐：从当前相机起步，response = 3.2 / transitionSeconds）
    private static double smoothedX, smoothedY, smoothedZ;
    private static float smoothedYaw, smoothedPitch;
    private static long lastCameraUpdateNanos;
    private static boolean resetCameraSmoothing;

    // 卡点调度专用：本镜头起始的全局拍序（无网格时退回 tick 兜底）
    private static double shotStartBeat;
    private static double shotBeatsTotal;
    /** 上次切镜的 activeTicks(12s 兜底定时的锚点)。 */
    private static long lastCutAtTick;

    // FOV punch 状态
    private static long lastPunchNanos;
    private static double lastPunchAmplitude;

    // 能量平滑（段落能量：慢包络）
    private static float smoothedEnergy;

        // 转场状态（CineTransition 读）
    private static long transitionStartNanos;
    private static long transitionEndNanos;
    private static int transitionEffect;   // 0=无 1=黑淡 2=白淡 3=横擦 4=竖擦 5=光圈
    private static boolean transitionCovering; // true=遮出（OUT），false=揭入（IN）
    private static boolean transitionShotPending; // 揭入到中点时该 prepareShot 硬切

    private CineDirector() {
    }

    // ---------------- 公开入口 ----------------

    /** 当前是否在运镜。树侧 mixin 以此决定是否接管。 */
    public static boolean isActive() {
        return active;
    }

    /**
     * tick 桥：树侧每 client tick 调一次。probe 为 null（主菜单）视作退出条件。
     */
    public static void onClientTick(SceneProbe probe) {
        try {
            if (!CineSettings.isEnabled()) {
                stop(probe, false);
                return;
            }
            if (probe == null || Double.isNaN(probe.playerX()) || !probe.playerAlive()
                    || probe.openScreen() != null || probe.hurtThisTick() || probe.inputThisTick()) {
                idleTicks = 0;
                stop(probe, true);
                return;
            }
            if (!isMusicPlaying()) {
                idleTicks = 0;
                stop(probe, false);
                return;
            }
            if (active) {
                currentProbeForPose = probe; // 渲染帧 calculateTarget 的玩家位来源
                tickActive(probe);
            } else {
                idleTicks++;
                if (idleTicks >= CineSettings.idleSeconds() * 20L) {
                    start(probe);
                }
            }
        } catch (Throwable ignored) {
            // 运镜是装饰层：任何异常都不许打断游戏。
            try {
                stop(probe, false);
            } catch (Throwable ignoredToo) {
            }
        }
    }

    /** 渲染帧入口：mixin 每帧调。active=false 返回 null（不接管）。 */
    public static CinePose currentPose(double cameraX, double cameraY, double cameraZ) {
        if (!active || currentShot == null) return null;
        long now = System.nanoTime();
        double dt = lastCameraUpdateNanos == 0L ? 0.05d : (now - lastCameraUpdateNanos) / 1.0e9d;
        return computePose(now, dt, cameraX, cameraY, cameraZ);
    }

    /** 当前帧 FOV 偏移（度，正 = 广角）。FOV mixin 每帧叠加。 */
    public static float fovOffsetDeg() {
        if (!active || CineSettings.fovPunchDegrees() <= 0f) return 0f;
        return (float) CineShots.fovPunch(lastPunchAmplitude, lastPunchNanos,
                System.nanoTime(), 0.2d);
    }

    /**
     * 遮罩/黑边的帧末合成任务（供 CineHudMixin 走 deferPostCompositeOverlay）。
     * 返回 null 表示本帧没有要画的（active=false 或遮罩/黑边全关）——
     * 调用方一次布尔判断。
     */
    public static Runnable cineOverlayRunnable() {
        if (!active) return null;
        return () -> {
            CineTransition.render();
            CineBars.render();
        };
    }

    /**
     * 当前帧的转场遮罩状态；CineTransition 每帧读。
     *
     * @return {effect, progress0..1, covering01} 或 null（无转场）
     */
    public static TransitionFrame currentTransition() {
        if (transitionEffect == 0) return null;
        long now = System.nanoTime();
        if (now >= transitionEndNanos) {
            transitionEffect = 0;
            return null;
        }
        double total = transitionEndNanos - transitionStartNanos;
        double progress = Math.max(0d, Math.min(1d, (now - transitionStartNanos) / Math.max(1d, total)));
        return new TransitionFrame(transitionEffect, progress, transitionCovering);
    }

    /** 转场帧视图：effect 同 CineSettings 的位序（1 黑淡 2 白淡 3 横擦 4 竖擦 5 光圈）。 */
    public record TransitionFrame(int effect, double progress, boolean covering) {
    }

    /** 顶部/底部黑边参数（供 HUD 渲染层画）；关着返回 null。 */
    public static float cinematicBarFraction() {
        if (!active || !CineSettings.showCinematicBars()) return 0f;
        return CineSettings.cinematicBarPercent() / 100f;
    }

    // ---------------- 状态机实现 ----------------

    private static boolean isMusicPlaying() {
        // 播放判定交给节拍链：CloudMusic.getSnapshot().playing 为 false 时
        // CineBeatSource.currentGrid() 返回 null，onClientTick 的网格分支自然停摆。
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        return snapshot != null && snapshot.playing;
    }

    private static void start(SceneProbe probe) {
        if (active) return;
        active = true;
        activeTicks = 0;
        idleTicks = 0;
        // 对齐 AmbientCameraClient.start:镜头序列从 -1 起步、运动时钟清零、
        // 平滑状态标记重置(首帧从当前相机位置起步)。
        currentShotSequence = -1;
        currentShot = null;
        trackedMob = null;
        motionTimeSeconds = 0.0;
        lastCutAtTick = 0L;
        transitionEffect = 0;
        transitionCovering = false;
        transitionShotPending = false;
        cutPending = false;
        resetCameraSmoothing = true;
        smoothedX = 0d;
        smoothedY = 0d;
        smoothedZ = 0d;
        smoothedYaw = 0f;
        smoothedPitch = 0f;
        lastCameraUpdateNanos = 0L;
        prepareShot(probe, System.nanoTime(), true);
        LOG.info("[MuoniumPlayer/Cine] 运镜启动: 闲置 {}s, 音乐在播", CineSettings.idleSeconds());
    }

    private static void stop(SceneProbe probe, boolean restoreCamera) {
        if (!active) return;
        active = false;
        long livedTicks = activeTicks;
        idleTicks = 0;
        activeTicks = 0;
        currentShotSequence = -1;
        currentShot = null;
        trackedMob = null;
        transitionEffect = 0;
        transitionCovering = false;
        transitionShotPending = false;
        cutPending = false;
        resetCameraSmoothing = true;
        LOG.info("[MuoniumPlayer/Cine] 运镜退出: 持续 {}s ({})",
                livedTicks / 20, restoreCamera ? "输入/界面/受伤" : "音乐停止或开关关闭");
        // 恢复相机视角/HUD 是树侧动作：mixin 看到 isActive()=false 就不再接管，
        // 树桥的翻转沿调 CineProbe.entered()/left() 复原相机与 HUD。
    }

    /**
     * 运镜中的每 tick 推进。结构与 {@code AmbientCameraClient.tickActiveCamera} 一一对应：
     * 运动时钟累计 → orbit 角度状态推进 → 生物主体离场检测 → 转场三态机(OUT/IN/无) →
     * 镜头到点判断。唯一差异：它的 {@code shotTicks >= shotDurationTicks(12s 定时)}
     * 换成拍网格推进（{@code beatNow - shotStartBeat >= shotBeatsTotal}），无网格时
     * 退回同款 12s 定时。
     */
    private static void tickActive(SceneProbe probe) {
        activeTicks++;
        long now = System.nanoTime();
        double cameraSpeed = CineSettings.speedMultiplier();

        // 运动时钟:每 tick + cameraSpeed/20 —— 对齐 motionTimeSeconds 推进式。
        motionTimeSeconds += cameraSpeed / 20.0;

        // orbit 角度是持续状态:只对 PLAYER / 有主体的 MOB 镜头推进 ——
        // 对齐 orbitAngleDegrees += angularSpeed * cameraSpeed / 20。
        if (currentShot != null
                && (currentShot.kind() == ShotKind.PLAYER
                || currentShot.kind() == ShotKind.MOB_OR_SCENERY && trackedMob != null)) {
            orbitAngleDegrees += currentShot.angularSpeedDegPerSec() * cameraSpeed / 20.0;
        }

        // 生物主体离场:1.35× 半径外或消失 → 丢弃主体,立即硬切回风景 ——
        // 对齐 focusedMob 离场的 cutPending 语义。
        if (trackedMob != null) {
            double dx = trackedMob.x() - probe.playerX();
            double dy = trackedMob.y() - probe.playerY();
            double dz = trackedMob.z() - probe.playerZ();
            double mobRange = CineSettings.mobFocusRange() * 1.35;
            if (distSqr(dx, dy, dz) > mobRange * mobRange) {
                trackedMob = null;
                cutPending = true;
            }
        }

        // 能量包络（慢平滑,驱动切镜密度:副歌镜头短）。
        float energy = PlaybackPulse.loudness();
        smoothedEnergy += (Math.max(0f, Math.min(1f, energy)) - smoothedEnergy) * 0.02f;

        // 转场三态机(对齐 fadePhase OUT/IN/NONE):
        CineBeatSource.Grid grid = CineBeatSource.currentGrid();
        boolean metered = grid != null && grid.isMetered();
        if (activeTicks % 40L == 0L) { // 每 2s 一条诊断
            LOG.info("[MuoniumPlayer/Cine] tick#{} metered={} conf={} bpm={} trans={}",
                    activeTicks, metered,
                    grid == null ? "null" : String.format("%.2f", grid.confidence()),
                    grid == null ? "-" : String.format("%.0f", grid.bpm()),
                    transitionEffect);
        }
        if (transitionEffect != 0) {
            long midNanos = (transitionStartNanos + transitionEndNanos) / 2L;
            if (now >= midNanos && transitionShotPending) {
                prepareShot(probe, now, false);
                transitionShotPending = false;
            }
            if (now >= transitionEndNanos) {
                transitionEffect = 0;
                transitionCovering = false;
                lastCutAtTick = activeTicks; // 兜底定时从这次切镜重新起算
            }
        } else if (metered) {
            // 镜头到点(拍制):从本镜头起始拍起算,超过 shotBeatsTotal(能量调制)就起转场。
            double beatNow = beatIndex(grid, now);
            if (shotBeatsTotal > 0d && beatNow - shotStartBeat >= shotBeatsTotal) {
                beginTransitionTo(probe, now);
            }
            noteBeatPulse(grid, now);
        } else {
            // 无网格兜底:同 Ambient Camera 的 12s 定时(240 tick)——从上次切镜起算,
            // 否则 240 tick 后每 tick 都满足条件,转场结束后立刻再起转场(卡死循环)。
            if (activeTicks - lastCutAtTick >= 240L) {
                beginTransitionTo(probe, now);
            }
        }
    }

    private static double distSqr(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    /** 全局拍序号（浮点）：网格锚推 now 对周期取整。 */
    private static double beatIndex(CineBeatSource.Grid grid, long now) {
        if (!grid.isMetered()) return 0d;
        return (now - grid.beatAnchorNanos()) / grid.beatPeriodNanos();
    }

    /** 拍点冲高：新拍且能量足够时记 FOV punch。 */
    private static void noteBeatPulse(CineBeatSource.Grid grid, long now) {
        float amplitude = CineSettings.fovPunchDegrees() * (0.4f + 0.6f * smoothedEnergy);
        long minGapNanos = grid.isMetered() ? (long) grid.beatPeriodNanos() : 1_000_000_000L;
        if (lastPunchNanos == 0L || now - lastPunchNanos >= minGapNanos) {
            // 只在拍相位前 15% 内打：防止切镜/掉帧后补一堆 punch。
            float phase = grid.isMetered() ? grid.beatPhase(now) : 0.5f;
            if (phase <= 0.15f) {
                lastPunchNanos = now;
                lastPunchAmplitude = amplitude;
            }
        }
    }

    // ---------------- 镜头编排 ----------------

    /**
     * 起转场：遮出（0.5×transitionSeconds）→ prepareShot 硬切 → 揭入。
     * 目标落点压在下一个拍点/小节拍点上（J-cut 提前量）。
     */
    private static void beginTransitionTo(SceneProbe probe, long now) {
        CineBeatSource.Grid grid = CineBeatSource.currentGrid();
        int effect = pickTransitionEffect();
        long halfNanos = (long) (CineSettings.transitionSeconds() * 0.5e9);
        long fullNanos = halfNanos * 2L;
        // J-cut：揭入结束点对齐拍点(置信网格)。窗口全长不缩——快歌的「下一拍点」
        // 可能早于 now+全长,此时往后取下一个满足「距 now ≥ 全长」的拍点,
        // 保证遮出/切镜/揭入三段都真的发生(否则转场窗口一开启即过期,
        // 中点的 prepareShot 永远不执行,镜头永不换——2026-11-05 实机复盘)。
        long revealEndNanos = now + fullNanos;
        if (grid != null && grid.isMetered()) {
            double period = grid.beatPeriodNanos();
            double elapsed = now - grid.beatAnchorNanos();
            double beatsAhead = Math.ceil(elapsed / period) + 1d;
            long candidate = (long) Math.rint(grid.beatAnchorNanos() + beatsAhead * period);
            while (candidate - now < fullNanos) {
                candidate += (long) period;
            }
            revealEndNanos = candidate;
        }
        transitionStartNanos = now;
        transitionEndNanos = revealEndNanos;
        transitionEffect = effect;
        transitionCovering = true;
        transitionShotPending = true; // 中点必须 prepareShot,否则镜头永不换
    }

    /** 从位掩码随机挑一种特效（0 = 无）。 */
    static int pickTransitionEffect() {
        int mask = CineSettings.transitionMask();
        if (mask == 0) return 0;
        int count = Integer.bitCount(mask);
        int index = (int) (nextRandom() % count);
        for (int effect = 0; effect < 5; effect++) {
            if ((mask & (1 << effect)) != 0 && index-- == 0) return effect + 1;
        }
        return 1;
    }

    private static long rngState = 0x9E3779B97F4A7C15L;

    private static long nextRandom() {
        rngState ^= rngState << 13;
        rngState ^= rngState >>> 7;
        rngState ^= rngState << 17;
        return rngState;
    }

        /**
     * 备好下一镜头。结构与 {@code AmbientCameraClient.prepareShot} 一一对应：
     * 固定四镜头序列循环、风景朝向偏移表、风景焦点射线、MOB 镜头主体选取、
     * cutPending = 首镜 || 硬切。卡点差异只有一处：镜头时长按拍（能量调制）
     * 记在本类，替换它的 12s 定时。
     */
    private static void prepareShot(SceneProbe probe, long now, boolean firstShot) {
        boolean isFirst = currentShotSequence < 0;
        currentShotSequence = isFirst ? 0 : currentShotSequence + 1;
        currentShot = SHOTS[Math.floorMod(currentShotSequence, SHOTS.length)];
        trackedMob = null;
        // 风景朝向 = 玩家 yaw + 偏移表（原样移植 SCENERY_YAW_OFFSETS 轮转）。
        double sceneryYaw = probe.playerYaw()
                + SCENERY_YAW_OFFSETS[Math.floorMod(currentShotSequence, SCENERY_YAW_OFFSETS.length)];
        sceneryDirection = horizontalDirection(sceneryYaw);
        sceneryFocus = findSceneryFocus(probe, sceneryDirection, 32.0);
        if (isFirst) {
            orbitAngleDegrees = probe.playerYaw() + 75.0;
        }

        if (currentShot.kind() == ShotKind.MOB_OR_SCENERY && CineSettings.enableMobFocus()) {
            trackedMob = probe.nearestMovingMob(CineSettings.mobFocusRange());
        }

        cutPending = isFirst || true; // hardCuts 默认 true（同 Ambient Camera 出厂值）

        // 卡点调度:镜头时长 = 基准拍 × 能量调制;起点拍序记在这里(修掉上一版
        // 「beatIndex 是巨数、shotStartBeat=0 恒超限」的 bug)。
        // 换算成秒后钳 minShotSeconds 下限 —— 181BPM 的 4 拍只有 1.3s,闪切会晕;
        // 秒数不够就把拍数抬到最短秒数对应值。
        CineBeatSource.Grid grid = CineBeatSource.currentGrid();
        if (grid != null && grid.isMetered()) {
            shotStartBeat = beatIndex(grid, now);
            double beats = Math.max(1d, CineSettings.shotBeats() * (1.6d - 0.8d * smoothedEnergy));
            double beatSeconds = grid.beatPeriodNanos() / 1.0e9;
            double minBeats = CineSettings.minShotSeconds() / Math.max(0.05, beatSeconds);
            shotBeatsTotal = Math.max(beats, minBeats);
        } else {
            shotStartBeat = 0d;
            shotBeatsTotal = 0d; // 0 = 走 12s tick 兜底
        }
        LOG.info("[MuoniumPlayer/Cine] 切镜 #{} -> {}{} 时长={}拍",
                shotSequenceForLog(), currentShot.kind(),
                trackedMob != null ? " (mob)" : "",
                String.format("%.1f", shotBeatsTotal));
    }

    /** 水平朝向向量（原样移植 horizontalDirection:yaw=0 面南 +Z）。 */
    private static Vec3Holder horizontalDirection(double yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        return new Vec3Holder(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    /**
     * 风景焦点射线（语义对齐 findSceneryFocus）：视线方向抬 0.08 前看 distance 格。
     * probe.clipBlocked 只回「挡没挡」，命中点细节拿不到 —— 折中：挡了取射线
     * 60% 处再抬 0.6（近似原版「回退 0.45 + 抬 0.6」的构图意图），没挡取终点。
     */
    private static Vec3Holder findSceneryFocus(SceneProbe probe, Vec3Holder direction, double distance) {
        double eyeX = probe.playerX();
        double eyeY = probe.playerY() + 1.62;
        double eyeZ = probe.playerZ();
        double dirX = direction.x();
        double dirY = direction.y() + 0.08;
        double dirZ = direction.z();
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX /= len;
        dirY /= len;
        dirZ /= len;
        double endX = eyeX + dirX * distance;
        double endY = eyeY + dirY * distance;
        double endZ = eyeZ + dirZ * distance;
        boolean blocked = probe.clipBlocked(eyeX, eyeY, eyeZ, endX, endY, endZ);
        if (blocked) {
            return new Vec3Holder(
                    eyeX + dirX * distance * 0.6,
                    eyeY + dirY * distance * 0.6 + 0.6,
                    eyeZ + dirZ * distance * 0.6);
        }
        return new Vec3Holder(endX, endY, endZ);
    }

    /**
     * 逐帧机位计算。结构与 {@code AmbientCameraClient.calculateCameraPose +
     * calculateTarget + orbitPosition} 一一对应：cutPending 吸附 → 平滑基线从
     * 当前相机起步 → 目标机位（镜头表驱动）→ lerp 平滑
     * （blend = 1-exp(-3.2/transitionSeconds·dt)）→ 看向焦点。
     */
    private static CinePose computePose(long now, double dt, double cameraX, double cameraY, double cameraZ) {
        if (currentShot == null) return null;
        double motionTime = motionTimeSeconds;
        CameraTarget target = calculateTarget(cameraX, cameraY, cameraZ, motionTime);

        long nowNanos = System.nanoTime();
        if (cutPending) {
            // 硬切：直接吸附到目标机位，朝向对准焦点 —— 对齐原版 cutPending 分支。
            smoothedX = target.position().x();
            smoothedY = target.position().y();
            smoothedZ = target.position().z();
            Rotation rot = rotationLookingAt(smoothedX, smoothedY, smoothedZ, target.focus());
            smoothedYaw = rot.yaw();
            smoothedPitch = rot.pitch();
            lastCameraUpdateNanos = nowNanos;
            resetCameraSmoothing = false;
            cutPending = false;
            return finishPose(rot.yaw(), rot.pitch());
        }
        if (resetCameraSmoothing || (smoothedX == 0d && smoothedY == 0d && smoothedZ == 0d)) {
            // 首帧：从当前相机位置起步 —— 对齐原版 resetCameraSmoothing 分支。
            smoothedX = cameraX;
            smoothedY = cameraY;
            smoothedZ = cameraZ;
            Rotation rot = rotationLookingAt(smoothedX, smoothedY, smoothedZ, target.focus());
            smoothedYaw = rot.yaw();
            smoothedPitch = rot.pitch();
            lastCameraUpdateNanos = nowNanos;
            resetCameraSmoothing = false;
            return finishPose(smoothedYaw, smoothedPitch);
        }
        double deltaSeconds = Math.max(0.004166666666666667d,
                Math.min(0.1d, (nowNanos - lastCameraUpdateNanos) / 1.0e9d));
        lastCameraUpdateNanos = nowNanos;
        double transitionSeconds = Math.max(0.1d, CineSettings.trackingSoftness());
        double response = 3.2 / transitionSeconds;
        double blend = 1.0d - Math.exp(-response * deltaSeconds);
        smoothedX += (target.position().x() - smoothedX) * blend;
        smoothedY += (target.position().y() - smoothedY) * blend;
        smoothedZ += (target.position().z() - smoothedZ) * blend;
        Rotation rot = rotationLookingAt(smoothedX, smoothedY, smoothedZ, target.focus());
        smoothedYaw = approachDegrees(smoothedYaw, rot.yaw(), (float) blend);
        smoothedPitch += (rot.pitch() - smoothedPitch) * (float) blend;
        return finishPose(smoothedYaw, smoothedPitch);
    }

    /** 包一层手持感与 FOV，出口统一。 */
    private static CinePose finishPose(float yaw, float pitch) {
        double timeSeconds = System.nanoTime() / 1.0e9d;
        double intensity = CineSettings.handheldIntensity();
        float roll = (float) CineShots.handheldRoll(timeSeconds, intensity);
        float pitchOffset = (float) CineShots.handheldPitch(timeSeconds, intensity);
        return new CinePose(smoothedX, smoothedY, smoothedZ,
                CineShots.wrapDegrees(yaw),
                Math.max(-89.9f, Math.min(89.9f, pitch + pitchOffset)),
                roll,
                fovOffsetDeg());
    }

    private record Rotation(float yaw, float pitch) {
    }

    /** 位置看向焦点的 yaw/pitch（原样移植 rotationLookingAt）。 */
    private static Rotation rotationLookingAt(double px, double py, double pz, Vec3Holder focus) {
        double dx = focus.x() - px;
        double dy = focus.y() - py;
        double dz = focus.z() - pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 1.0e-5))));
        return new Rotation(yaw, pitch);
    }

    /**
     * 机位目标（结构对齐 calculateTarget）：
     * PLAYER = 焦点在玩家视线前方，相机绕玩家轨道；
     * MOB_OR_SCENERY 有主体 = 焦点在生物胸口，相机绕生物轨道；
     * SCENERY（含 MOB 缺席） = 焦点在风景射线落点，相机在玩家后方侧漂。
     */
    private static CameraTarget calculateTarget(double camX, double camY, double camZ,
                                                double motionTime) {
        SceneProbe probe = currentProbeForPose;
        double playerBaseX = probe == null ? camX : probe.playerX();
        double playerBaseY = probe == null ? camY : probe.playerY();
        double playerBaseZ = probe == null ? camZ : probe.playerZ();
        ShotKind effectiveKind = currentShot.kind();
        if (effectiveKind == ShotKind.MOB_OR_SCENERY && trackedMob == null) {
            effectiveKind = ShotKind.SCENERY;
        }

        if (effectiveKind == ShotKind.PLAYER) {
            float playerYaw = probe == null ? 0f : probe.playerYaw();
            Vec3Holder forward = horizontalDirection(playerYaw);
            Vec3Holder focus = new Vec3Holder(
                    playerBaseX + forward.x() * currentShot.focusForward(),
                    playerBaseY + currentShot.focusHeight(),
                    playerBaseZ + forward.z() * currentShot.focusForward());
            return new CameraTarget(focus, orbitPosition(playerBaseX, playerBaseY, playerBaseZ));
        } else if (effectiveKind == ShotKind.MOB_OR_SCENERY && trackedMob != null) {
            double mobFocusY = trackedMob.y() + Math.max(0.65, trackedMob.bbHeight() * 0.55);
            Vec3Holder focus = new Vec3Holder(trackedMob.x(), mobFocusY, trackedMob.z());
            return new CameraTarget(focus, orbitPosition(trackedMob.x(), trackedMob.y(), trackedMob.z()));
        } else {
            // 风景：玩家后方 backOffset、侧向 sideOffset，正弦漂移 —— 原样移植。
            Vec3Holder right = new Vec3Holder(sceneryDirection.z(), 0.0, -sceneryDirection.x());
            double drift = Math.sin(motionTime * 0.32 + currentShotSequence * 0.71) * 0.65;
            Vec3Holder position = new Vec3Holder(
                    playerBaseX - sceneryDirection.x() * (currentShot.backOffset() + drift * 0.15)
                            + right.x() * (currentShot.sideOffset() + drift),
                    playerBaseY + currentShot.height() + Math.sin(motionTime * 0.25) * 0.1,
                    playerBaseZ - sceneryDirection.z() * (currentShot.backOffset() + drift * 0.15)
                            + right.z() * (currentShot.sideOffset() + drift));
            return new CameraTarget(sceneryFocus, position);
        }
    }

    /**
     * 轨道机位（原样移植 orbitPosition）：角度取持续状态 orbitAngleDegrees
     * （tick 里推进），这里只补高度呼吸；半径/高度来自镜头表。
     */
    private static Vec3Holder orbitPosition(double centerX, double centerY, double centerZ) {
        double subtleLift = Math.sin(motionTimeSeconds * 0.42) * 0.12;
        double radians = Math.toRadians(orbitAngleDegrees);
        return new Vec3Holder(
                centerX + Math.cos(radians) * currentShot.radius(),
                centerY + currentShot.height() + subtleLift,
                centerZ + Math.sin(radians) * currentShot.radius());
    }

    private static float approachDegrees(float current, float target, float amount) {
        return current + CineShots.wrapDegrees(target - current) * amount;
    }

    /** 日志用镜头序号（复用 rngState 不合适，单计数器）。 */
    private static int shotSequenceForLog() {
        shotLogCounter++;
        return shotLogCounter;
    }

    private static int shotLogCounter;

    // ---------------- 测试钩子 ----------------

    /** 测试专用：全状态归零。 */
    static void resetForTest() {
        active = false;
        idleTicks = 0;
        activeTicks = 0;
        currentShotSequence = -1;
        currentShot = null;
        trackedMob = null;
        transitionEffect = 0;
        transitionCovering = false;
        transitionShotPending = false;
        cutPending = false;
        smoothedEnergy = 0f;
        lastPunchNanos = 0L;
        lastPunchAmplitude = 0d;
        resetCameraSmoothing = true;
        smoothedX = smoothedY = smoothedZ = 0d;
        smoothedYaw = smoothedPitch = 0f;
        lastCameraUpdateNanos = 0L;
        motionTimeSeconds = 0.0;
        orbitAngleDegrees = 0.0;
        currentProbeForPose = null;
        shotLogCounter = 0;
    }

    /** 测试专用：直接驱动内部状态（绕过 probe/节拍源）。 */
    static void forceActiveForTest(boolean value) {
        active = value;
        if (value) {
            currentShotSequence = 0;
            currentShot = SHOTS[0]; // PLAYER orbit
            shotStartBeat = 0d;
            shotBeatsTotal = 4d;
            cutPending = false;
            resetCameraSmoothing = false;
            smoothedX = 10d;
            smoothedY = 70d;
            smoothedZ = 10d;
            smoothedYaw = 0f;
            smoothedPitch = 0f;
        }
    }

    /** 测试专用：注入能量。 */
    static void forceEnergyForTest(float energy) {
        smoothedEnergy = energy;
    }

    /** 测试专用：注入转场。 */
    static void forceTransitionForTest(int effect, long startNanos, long endNanos, boolean covering) {
        transitionEffect = effect;
        transitionStartNanos = startNanos;
        transitionEndNanos = endNanos;
        transitionCovering = covering;
    }

    /** 供 StaticTest 钉签名。 */
    static List<String> probeMethodNamesForTest() {
        return List.of("playerX", "playerY", "playerZ", "playerYaw", "playerPitch",
                "openScreen", "hurtThisTick", "inputThisTick", "playerAlive",
                "clipBlocked", "nearestMovingMob");
    }
}
