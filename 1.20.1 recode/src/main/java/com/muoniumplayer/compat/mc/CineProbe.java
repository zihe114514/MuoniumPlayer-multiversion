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
 * 「卡点运镜」的场景采样器 + 相机执行器 shim：把 MC 状态翻译成
 * common 侧的 {@link CineDirector.SceneProbe} 纯数据。
 *
 * <p>26.3 之前的 API 形态(javap 核对 merged jar 1.20.1)：
 * {@code mc.screen}、{@code Camera.getPosition()/getYRot()/getXRot()}、
 * HUD 隐藏走 {@code mc.options.hideGui}(26.x 才有 Gui.hud 子对象与 toggle)。
 * 26.x 的写法见 26.3 树同名文件。</p>
 */
public final class CineProbe implements CineDirector.SceneProbe {

    private static volatile boolean activityLatch;
    private static int lastHurtTime;
    private static double lastX, lastY, lastZ;
    private static float lastYaw, lastPitch;
    private static boolean poseBaselineValid;
    private static net.minecraft.client.CameraType previousCameraType;
    private static boolean hideGuiOverridden;

    private CineProbe() {
    }

    public static CineProbe of(Minecraft mc) {
        return new CineProbe();
    }

    public static void onKeyMouseActivity() {
        activityLatch = true;
    }

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
        activityLatch = false;
    }

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
