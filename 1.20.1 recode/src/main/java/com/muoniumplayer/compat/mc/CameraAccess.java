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
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) {
            return null;
        }
        return new CameraAccess(camera, partialTick);
    }

    @Override
    public double eyeX() {
        Vec3 pos = camera.getPosition();
        return pos.x;
    }

    @Override
    public double eyeY() {
        Vec3 pos = camera.getPosition();
        return pos.y;
    }

    @Override
    public double eyeZ() {
        Vec3 pos = camera.getPosition();
        return pos.z;
    }

    @Override
    public double lookX() {
        org.joml.Vector3f dir = camera.getLookVector();
        return dir.x();
    }

    @Override
    public double lookY() {
        org.joml.Vector3f dir = camera.getLookVector();
        return dir.y();
    }

    @Override
    public double lookZ() {
        org.joml.Vector3f dir = camera.getLookVector();
        return dir.z();
    }

    @Override
    public double yawDeg() {
        return camera.getYRot();
    }

    @Override
    public double pitchDeg() {
        return camera.getXRot();
    }

    /** 本帧的 partialTick（逐词动画插值用）。 */
    public float partialTick() {
        return partialTick;
    }
}
