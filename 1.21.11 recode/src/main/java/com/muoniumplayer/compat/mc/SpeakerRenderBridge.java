package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 世界声源的可见性(旧世代树):在锚点画一个小型「音箱」线框(立方体 + 底座
 * 十字),颜色随降级状态变化 —— 绿 = 正常、黄 = 降级。没声源时第一行就返回。
 *
 * <p>绘制通路与 {@link AcousticsRayDebugRenderer} 同款:bufferSource 批
 * {@code RenderTypes.lines()},相机相对顶点 + 帧尾调用。由各树的帧尾挂点
 * (WorldLyricsRenderTailMixin / WorldRenderEvents.LAST)调用 {@link #render()}。
 * 所有异常静默 —— debug 性质的渲染绝不能带崩帧。</p>
 */
public final class SpeakerRenderBridge {

    /** 音箱线框的半边长(格):约 0.35 = 一个 0.7 大小的方块体。 */
    private static final float HALF = 0.35f;
    /** 底座十字半径(格)。 */
    private static final float BASE_R = 0.55f;

    /** 渲染失败即停。 */
    private static boolean broken;

    private SpeakerRenderBridge() {
    }

    /** 帧尾调用(各树的帧尾挂点);debug 关闭态零成本。 */
    public static void render() {
        if (broken || !SpeakerManager.hasSpeaker()) {
            return;
        }
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.gameRenderer == null) {
                return;
            }
            final com.muoniumplayer.core.ncm.music.acoustics.SpeakerState s =
                    com.muoniumplayer.core.ncm.music.acoustics.SpeakerState.get();
            if (s == null) {
                return;
            }
            final Vec3 cam = mc.gameRenderer.getMainCamera().position();
            final int rgb = SpeakerManager.currentStatus()
                    == com.muoniumplayer.core.ncm.music.acoustics.SpeakerState.Status.ACTIVE
                    ? 0x3CE86A : 0xF0C440;

            final PoseStack poseStack = new PoseStack();
            final float cx = (float) (s.x() - cam.x);
            final float cy = (float) (s.y() - cam.y);
            final float cz = (float) (s.z() - cam.z);

            final MultiBufferSource.BufferSource buffers =
                    mc.renderBuffers().bufferSource();
            final VertexConsumer consumer = buffers.getBuffer(RenderTypes.lines());
            box(consumer, poseStack.last(), cx, cy, cz, HALF, rgb, 230);
            cross(consumer, poseStack.last(), cx, cy - HALF - 0.05f, cz, BASE_R, rgb, 200);
            buffers.endBatch();
        } catch (Throwable ignored) {
            broken = true;
        }
    }

    /** 轴对齐立方体线框。 */
    private static void box(VertexConsumer c, PoseStack.Pose pose,
                            float cx, float cy, float cz, float h, int rgb, int a) {
        final float x0 = cx - h, x1 = cx + h;
        final float y0 = cy - h, y1 = cy + h;
        final float z0 = cz - h, z1 = cz + h;
        line(c, pose, x0, y0, z0, x1, y0, z0, rgb, a);
        line(c, pose, x1, y0, z0, x1, y0, z1, rgb, a);
        line(c, pose, x1, y0, z1, x0, y0, z1, rgb, a);
        line(c, pose, x0, y0, z1, x0, y0, z0, rgb, a);
        line(c, pose, x0, y1, z0, x1, y1, z0, rgb, a);
        line(c, pose, x1, y1, z0, x1, y1, z1, rgb, a);
        line(c, pose, x1, y1, z1, x0, y1, z1, rgb, a);
        line(c, pose, x0, y1, z1, x0, y1, z0, rgb, a);
        line(c, pose, x0, y0, z0, x0, y1, z0, rgb, a);
        line(c, pose, x1, y0, z0, x1, y1, z0, rgb, a);
        line(c, pose, x1, y0, z1, x1, y1, z1, rgb, a);
        line(c, pose, x0, y0, z1, x0, y1, z1, rgb, a);
    }

    /** 水平十字(底座)。 */
    private static void cross(VertexConsumer c, PoseStack.Pose pose,
                              float cx, float cy, float cz, float r, int rgb, int a) {
        line(c, pose, cx - r, cy, cz, cx + r, cy, cz, rgb, a);
        line(c, pose, cx, cy, cz - r, cx, cy, cz + r, rgb, a);
    }

    private static void line(VertexConsumer c, PoseStack.Pose pose,
                             float ax, float ay, float az, float bx, float by, float bz,
                             int rgb, int a) {
        final float dx = bx - ax, dy = by - ay, dz = bz - az;
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return;
        }
        final float nx = dx / len, ny = dy / len, nz = dz / len;
        final int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        c.addVertex(pose.pose(), ax, ay, az).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz);
        c.addVertex(pose.pose(), bx, by, bz).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz);
    }
}
