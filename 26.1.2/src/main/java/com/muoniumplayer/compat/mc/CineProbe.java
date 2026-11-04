package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CineDirector.MobSample;
import com.muoniumplayer.core.cine.CineSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 「卡点运镜」的场景采样器 + 相机执行器 shim：把 26.3 的 MC 状态翻译成
 * common 侧的 {@link CineDirector.SceneProbe} 纯数据。
 *
 * <p>照 {@code ListenerProbe}/{@code CameraAccess} 的 compat 模式：世界状态住在
 * 树侧，common 只认接口。26.3 的 API 形态（javap 核对 merged jar 26.3）：

 *
 * <h2>输入/受伤沿检测</h2>
 * <p>闲置检测要的是「事件沿」不是「状态」：{@link #onKeyMouseActivity()} 由树侧的
 * 输入桥调用（MuoniumPlayerClient 已有按键/鼠标事件路径），置 {@code activityLatch}；
 * tick 桥消费后清零。受伤同理走 {@link LocalPlayer#hurtTime} 的降沿。</p>
 */
public final class CineProbe implements CineDirector.SceneProbe {

    // ---- 沿锁存（主线程 tick 桥单消费）----
    /** 输入活动沿：视角/位置/速度变化推断，onClientTick 消费后清零。 */
    private static volatile boolean activityLatch;
    /** 上一次的玩家 hurtTime（降沿检测受伤）。 */
    private static int lastHurtTime;
    /** 上一 tick 的视角/位置快照（闲置推断的差分基线）。 */
    private static double lastX, lastY, lastZ;
    private static float lastYaw, lastPitch;
    private static boolean poseBaselineValid;
    /** 运镜进入前的相机视角与 HUD 状态（退出时恢复）。 */
    private static net.minecraft.client.CameraType previousCameraType;
    private static boolean hideGuiOverridden;

    private CineProbe() {
    }

    /** 构造当帧/当 tick 的 probe。主线程调用。 */
    public static CineProbe of(Minecraft mc) {
        return new CineProbe();
    }

    /** 输入桥（保留给树侧显式事件路径；当前闲置推断走 inputThisTick 的差分）。 */
    public static void onKeyMouseActivity() {
        activityLatch = true;
    }

    /**
     * 运镜刚启动时树侧调：切第三人称背后 + 可选隐藏 HUD。
     * （mixin 只在 isActive 时接管，相机类型必须是第三人称才能看到玩家。）
     */
    public static void entered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        previousCameraType = mc.options.getCameraType();
        if (previousCameraType != net.minecraft.client.CameraType.THIRD_PERSON_BACK) {
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        }
        if (CineSettings.hideHud() && !mc.options.hideGui) {
            hideGuiOverridden = true;
            mc.options.hideGui = true;
        }
        poseBaselineValid = false;
        lastHurtTime = 0;
        // 进入瞬间不置输入闩：start() 里闲置计时本来就归零了，这里再置闩会被
        // 下一 tick 的 inputThisTick() 消费成「玩家动了」→ 立即 stop（2026-11-04
        // 实机复盘：启动→0s 退出每 10s 循环）。基线也必须重置——切第三人称
        // 前后视角差分无意义。
        activityLatch = false;
        poseBaselineValid = false;
    }

    /**
     * 运镜退出时树侧调：恢复相机视角与 HUD。
     */
    public static void left() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (previousCameraType != null) {
            mc.options.setCameraType(previousCameraType);
            previousCameraType = null;
        }
        if (hideGuiOverridden) {
            mc.options.hideGui = false;
            hideGuiOverridden = false;
        }
        activityLatch = false;
        lastHurtTime = 0;
        poseBaselineValid = false;
    }

    // ---------------- SceneProbe 实现 ----------------

    @Override
    public double playerX() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Double.NaN : player.getX();
    }

    @Override
    public double playerY() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Double.NaN : player.getY();
    }

    @Override
    public double playerZ() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Double.NaN : player.getZ();
    }

    @Override
    public float playerYaw() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0f : player.getYRot();
    }

    @Override
    public float playerPitch() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0f : player.getXRot();
    }

    @Override
    public Object openScreen() {
        return Minecraft.getInstance().screen;
    }

    @Override
    public boolean hurtThisTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        int hurtTime = player.hurtTime;
        boolean fallingEdge = lastHurtTime > 0 && hurtTime == 0;
        boolean risingEdge = hurtTime > 0 && lastHurtTime == 0;
        lastHurtTime = hurtTime;
        return risingEdge || fallingEdge;
    }

    @Override
    public boolean inputThisTick() {
        boolean hadLatch = activityLatch;
        activityLatch = false;

        // 差分推断：视角或位置相对上一 tick 变了 = 玩家在动（走路/转头/被推挤都算）。
        // 这比挂键鼠事件桥便宜且不漏：前进键按住时位置每 tick 在变，光标晃动时 yaw/pitch 在变。
        LocalPlayer player = Minecraft.getInstance().player;
        boolean moved = false;
        if (player != null) {
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            float yaw = player.getYRot();
            float pitch = player.getXRot();
            if (poseBaselineValid) {
                double dx = x - lastX;
                double dy = y - lastY;
                double dz = z - lastZ;
                moved = dx * dx + dy * dy + dz * dz > 1.0e-6
                        || Math.abs(yaw - lastYaw) > 0.05f
                        || Math.abs(pitch - lastPitch) > 0.05f;
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            lastYaw = yaw;
            lastPitch = pitch;
            poseBaselineValid = true;
        } else {
            poseBaselineValid = false;
        }
        return hadLatch || moved;
    }

    @Override
    public boolean playerAlive() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.isAlive();
    }

    @Override
    public boolean clipBlocked(double fromX, double fromY, double fromZ,
                               double toX, double toY, double toZ) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        Vec3 from = new Vec3(fromX, fromY, fromZ);
        Vec3 to = new Vec3(toX, toY, toZ);
        var hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
    }

    @Override
    public MobSample nearestMovingMob(double rangeBlocks) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return null;
        double vertical = Math.max(8d, rangeBlocks * 0.6d);
        AABB box = player.getBoundingBox().inflate(rangeBlocks, vertical, rangeBlocks);
        Mob best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entity entity : level.getEntitiesOfClass(Mob.class, box, mob -> isAllowedMob(mob, player, rangeBlocks))) {
            if (!(entity instanceof Mob mob)) continue;
            double frameMove = (mob.getX() - mob.xo) * (mob.getX() - mob.xo)
                    + (mob.getY() - mob.yo) * (mob.getY() - mob.yo)
                    + (mob.getZ() - mob.zo) * (mob.getZ() - mob.zo);
            double motion = mob.getDeltaMovement().lengthSqr();
            double movement = Math.max(frameMove, motion);
            if (movement < 4.0e-4) continue;
            // 评分：移动量占大头、近距加成 —— 照 AmbientCameraClient.movingMobScore 的比例。
            double score = movement * 600d - mob.distanceToSqr(player) / (rangeBlocks * rangeBlocks);
            if (score > bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        if (best == null) return null;
        return new MobSample(best.getX(), best.getY(), best.getZ(),
                best.getBbHeight(), best instanceof net.minecraft.world.entity.monster.Enemy);
    }

    private static boolean isAllowedMob(Mob mob, LocalPlayer player, double range) {
        if (!mob.isAlive() || mob.isRemoved() || mob.isInvisible()) return false;
        if (mob.distanceToSqr(player) > range * range) return false;
        if (CineSettings.excludeHostileMobs() && mob instanceof net.minecraft.world.entity.monster.Enemy) {
            return false;
        }
        return !CineSettings.excludeBats() || !(mob instanceof net.minecraft.world.entity.ambient.Bat);
    }
}
