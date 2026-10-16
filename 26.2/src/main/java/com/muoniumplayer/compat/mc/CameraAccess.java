package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import com.muoniumplayer.core.lyrics3d.WorldLyricsState;

/**
 * 3D 世界歌词的相机/玩家访问 shim：把 MC 的相机状态翻译成 common 侧的
 * {@link WorldLyricsState.CameraView}。
 *
 * <p>照 {@code IslandAmbientStatus} 的直接 MC 引用模式 —— common 里碰 MC 的先例
 * 本来就只有寥寥几处工具路径，世界渲染这一条整条住在树侧，common 只认接口。</p>
 *
 * <p>与 1.21.1 的 API 差异（javap 核对自 26.2 官方 jar，26.x 起不混淆）：
 * {@code getPosition()} → {@link Camera#position()}、
 * {@code getLookVector()} → {@link Camera#forwardVector()}（返回 {@code Vector3fc}）、
 * {@code getYRot()/getXRot()} → {@link Camera#yRot()/xRot()}（方法名整体去 get 前缀）、
 * {@code gameRenderer.getMainCamera()} → {@code gameRenderer.mainCamera()}。
 * 语义都没变：yaw/pitch 仍是 MC 口径的度数。</p>
 */
public final class CameraAccess implements WorldLyricsState.CameraView {

    private final Camera camera;
    private final float partialTick;

    private CameraAccess(Camera camera, float partialTick) {
        this.camera = camera;
        this.partialTick = partialTick;
    }

    /**
     * 从当前游戏状态取一帧相机视图。不在世界里（主菜单/无玩家）返回 null。
     */
    public static CameraAccess capture(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        if (camera == null) {
            return null;
        }
        return new CameraAccess(camera, partialTick);
    }

    @Override
    public double eyeX() {
        Vec3 pos = camera.position();
        return pos.x;
    }

    @Override
    public double eyeY() {
        Vec3 pos = camera.position();
        return pos.y;
    }

    @Override
    public double eyeZ() {
        Vec3 pos = camera.position();
        return pos.z;
    }

    @Override
    public double lookX() {
        org.joml.Vector3fc dir = camera.forwardVector();
        return dir.x();
    }

    @Override
    public double lookY() {
        org.joml.Vector3fc dir = camera.forwardVector();
        return dir.y();
    }

    @Override
    public double lookZ() {
        org.joml.Vector3fc dir = camera.forwardVector();
        return dir.z();
    }

    @Override
    public double yawDeg() {
        return camera.yRot();
    }

    @Override
    public double pitchDeg() {
        return camera.xRot();
    }

    /** 本帧的 partialTick（逐词动画插值用）。 */
    public float partialTick() {
        return partialTick;
    }
}
