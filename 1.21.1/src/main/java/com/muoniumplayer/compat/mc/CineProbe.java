package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CineDirector.MobSample;
import com.muoniumplayer.core.cine.CineDirector.Vec3Holder;
import com.muoniumplayer.core.cine.CineInput;
import com.muoniumplayer.core.cine.CineSettings;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 1.21.1 的主线程场景快照与相机/HUD 归还；不会传入渲染帧。 */
public final class CineProbe implements CineDirector.SceneProbe {

    private static final CineInput INPUT = new CineInput();
    private static LocalPlayer hurtPlayer;
    private static int lastHurtTime;
    private static CameraType previousCameraType;
    private static boolean owned;
    private static boolean hudOverridden;

    private final Minecraft mc;
    private final LocalPlayer player;
    private final Level level;
    private final Object screen;
    private final boolean input;
    private final boolean hurt;
    private final boolean skyVisible;
    private final int dimensionKind;
    private final String biomeKey;
    private final long timeOfDay;

    private CineProbe(Minecraft mc) {
        this.mc = mc;
        this.player = mc.player;
        this.level = mc.level;
        this.screen = mc.screen;
        this.skyVisible = level != null && player != null
                && level.canSeeSky(net.minecraft.core.BlockPos.containing(player.getX(), player.getEyeY(), player.getZ()));
        this.dimensionKind = level == null ? -1 : level.dimensionTypeRegistration().unwrapKey()
                .map(key -> key.location().getPath().contains("nether") ? 1
                        : key.location().getPath().contains("end") ? 2 : 0).orElse(3);
        this.biomeKey = level == null || player == null ? "" : level.getBiome(player.blockPosition())
                .unwrapKey().map(key -> key.location().getPath()).orElse("");
        this.timeOfDay = level == null ? 0L : Math.floorMod(level.getDayTime(), 24000L);
        var options = mc.options;
        boolean held = options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown();
        this.input = INPUT.consume(mc.isWindowActive(), held);
        int hurtTime = player == null ? 0 : player.hurtTime;
        this.hurt = hurtPlayer == player && hurtTime > lastHurtTime;
        hurtPlayer = player;
        lastHurtTime = hurtTime;
    }

    public static CineProbe of(Minecraft mc) {
        return new CineProbe(mc);
    }

    public static void onKey(int key, int action) {
        INPUT.key(key, action);
    }

    public static void onButton(int button, int action) {
        INPUT.button(button, action);
    }

    public static void onScroll(double x, double y) {
        INPUT.scroll(x, y);
    }

    public static void onMove(double x, double y) {
        INPUT.move(x, y);
    }

    public static void onRelativeMove(double dx, double dy) {
        INPUT.relativeMove(dx, dy);
    }

    /** 每 tick 对账，异常退出也能归还；不依赖调用前后的布尔翻转恰好被观察到。 */
    public static void sync() {
        if (CineDirector.isActive()) {
            if (!owned) entered();
        } else if (owned) {
            left();
        }
    }

    public static void entered() {
        if (owned) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        previousCameraType = mc.options.getCameraType();
        owned = true;
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        if (CineSettings.hideHud() && !mc.options.hideGui) {
            hudOverridden = true;
            mc.options.hideGui = true;
        }
    }

    public static void left() {
        if (!owned) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            // 用户或其他相机已经更改视角时，不把新选择覆盖成进入前的旧视角。
            if (previousCameraType != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
                mc.options.setCameraType(previousCameraType);
            }
            if (hudOverridden) {
                if (mc.options.hideGui) mc.options.hideGui = false;
            }
        } finally {
            previousCameraType = null;
            owned = false;
            hudOverridden = false;
        }
    }

    @Override
    public Object worldIdentity() {
        return level;
    }

    @Override
    public Object playerIdentity() {
        return player;
    }

    @Override
    public double playerX() {
        return player == null ? Double.NaN : player.getX();
    }

    @Override
    public double playerY() {
        return player == null ? Double.NaN : player.getY();
    }

    @Override
    public double playerZ() {
        return player == null ? Double.NaN : player.getZ();
    }

    @Override
    public double playerEyeY() {
        return player == null ? Double.NaN : player.getEyeY();
    }

    @Override
    public float playerYaw() {
        return player == null ? 0f : player.getYRot();
    }

    @Override
    public float playerPitch() {
        return player == null ? 0f : player.getXRot();
    }

    @Override
    public Object openScreen() {
        return screen;
    }

    @Override
    public boolean hurtThisTick() {
        return hurt;
    }

    @Override
    public boolean inputThisTick() {
        return input;
    }

    @Override
    public boolean skyVisible() {
        return skyVisible;
    }

    @Override
    public int dimensionKind() {
        return dimensionKind;
    }

    @Override
    public String biomeKey() {
        return biomeKey;
    }

    @Override
    public long timeOfDay() {
        return timeOfDay;
    }

    @Override
    public boolean playerAlive() {
        return player != null && level != null && player.isAlive() && !player.isSpectator()
                && mc.getCameraEntity() == player && !mc.isPaused();
    }

    @Override
    public Vec3Holder clipCamera(double fromX, double fromY, double fromZ,
                                 double toX, double toY, double toZ) {
        if (level == null) return new Vec3Holder(fromX, fromY, fromZ);
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length)) throw new IllegalArgumentException("非有限运镜射线");
        if (length < 1.0e-5d) return new Vec3Holder(fromX, fromY, fromZ);
        double fraction = 1d;
        // 中心与八角射线约束近裁面，命中后退 0.25 格，避免镜头贴进墙体。
        for (int i = -1; i < 8; i++) {
            double ox = i < 0 ? 0d : ((i & 1) == 0 ? -0.1d : 0.1d);
            double oy = i < 0 ? 0d : ((i & 2) == 0 ? -0.1d : 0.1d);
            double oz = i < 0 ? 0d : ((i & 4) == 0 ? -0.1d : 0.1d);
            Vec3 from = new Vec3(fromX + ox, fromY + oy, fromZ + oz);
            Vec3 to = new Vec3(toX + ox, toY + oy, toZ + oz);
            var hit = level.clip(new ClipContext(from, to,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) continue;
            Vec3 point = hit.getLocation();
            double t = ((point.x - from.x) * dx + (point.y - from.y) * dy + (point.z - from.z) * dz)
                    / (length * length) - 0.25d / length;
            fraction = Math.max(0d, Math.min(fraction, t));
        }
        return new Vec3Holder(fromX + dx * fraction, fromY + dy * fraction, fromZ + dz * fraction);
    }

    @Override
    public MobSample nearestMovingMob(double rangeBlocks) {
        if (player == null || level == null) return null;
        AABB box = player.getBoundingBox().inflate(rangeBlocks, Math.max(8d, rangeBlocks * 0.6d), rangeBlocks);
        Mob best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, candidate -> isAllowedMob(candidate, rangeBlocks))) {
            double dx = mob.getX() - mob.xo, dy = mob.getY() - mob.yo, dz = mob.getZ() - mob.zo;
            double movement = Math.max(dx * dx + dy * dy + dz * dz, mob.getDeltaMovement().lengthSqr());
            if (movement < 4.0e-4d) continue;
            var sight = level.clip(new ClipContext(new Vec3(player.getX(), player.getEyeY(), player.getZ()),
                    new Vec3(mob.getX(), mob.getY() + mob.getBbHeight() * 0.55d, mob.getZ()),
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
            if (sight.getType() != HitResult.Type.MISS) continue;
            double score = movement * 600d - mob.distanceToSqr(player) / (rangeBlocks * rangeBlocks);
            if (score > bestScore) {
                bestScore = score;
                best = mob;
            }
        }
        return best == null ? null : sample(best);
    }

    @Override
    public MobSample trackedMob(int entityId, double rangeBlocks) {
        if (player == null || level == null) return null;
        Entity entity = level.getEntity(entityId);
        return entity instanceof Mob mob && isAllowedMob(mob, rangeBlocks) ? sample(mob) : null;
    }

    private static MobSample sample(Mob mob) {
        return new MobSample(mob.getId(), mob.xo, mob.yo, mob.zo,
                mob.getX(), mob.getY(), mob.getZ(), mob.getBbHeight());
    }

    private boolean isAllowedMob(Mob mob, double range) {
        if (!mob.isAlive() || mob.isRemoved() || mob.isInvisible() || mob.distanceToSqr(player) > range * range) return false;
        if (CineSettings.excludeHostileMobs() && mob instanceof net.minecraft.world.entity.monster.Enemy) return false;
        return !CineSettings.excludeBats() || !(mob instanceof net.minecraft.world.entity.ambient.Bat);
    }
}
