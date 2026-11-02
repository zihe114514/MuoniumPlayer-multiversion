package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * 场景声学调参的射线可视化:把 {@link ListenerProbe} 最近一轮采样的射线段与
 * 命中点画进世界。
 *
 * <p>绘制通路与 {@link WorldBillboard} 同款:bufferSource 批
 * {@code RenderTypes.lines()}(原版 debug 画线管线,POSITION_COLOR + 线宽状态),
 * 矩阵用本 mod 的专用 PoseStack(根矩阵 = 相机旋转,与
 * {@code WorldLyricsBridge} 的 basePose 同构),坐标全部相机相对。
 * 白线 = 射线路径;黄十字 = 墙面命中点。</p>
 *
 * <p>只在 1.21.11 fabric 注册(调参期专用);{@code showRays=false} 时第一行
 * 就返回,一次布尔读。所有异常静默 —— debug 渲染绝不能带崩帧。</p>
 */
public final class AcousticsRayDebugRenderer {

    private AcousticsRayDebugRenderer() {
    }

    /** 帧 Tail 调用;debug 关闭时零成本返回。 */
    public static void render() {
        if (!AcousticsDebugLoader.showRays) {
            return;
        }
        try {
            final AcousticsDebugLoader.RaySnapshot rays = AcousticsDebugLoader.lastRays;
            if (rays == null || (rays.segCount <= 0 && rays.hitCount <= 0)) {
                return;
            }
            final Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.gameRenderer == null || mc.level == null) {
                return;
            }
            final Vec3 cam = mc.gameRenderer.getMainCamera().position();

            // 专用 PoseStack,根矩阵 = 相机旋转(pitch·(yaw+180)),与
            // WorldLyricsBridge 的 basePose 同构;线段顶点全部相机相对。
            final com.mojang.blaze3d.vertex.PoseStack poseStack =
                    new com.mojang.blaze3d.vertex.PoseStack();
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(
                    mc.gameRenderer.getMainCamera().xRot()));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                    mc.gameRenderer.getMainCamera().yRot() + 180.0f));
            final com.mojang.blaze3d.vertex.PoseStack.Pose pose = poseStack.last();

            final var buffers = mc.renderBuffers().bufferSource();
            final var consumer = buffers.getBuffer(RenderTypes.lines());

            // 射线段:白(1.21.11 LINES 管线要求每顶点带 normal;线段的
            // normal 就取线方向,debug 工具不需要光照语义)
            for (int s = 0; s < rays.segCount; s++) {
                final int o = s * 6;
                float ax = rays.segs[o] - (float) cam.x;
                float ay = rays.segs[o + 1] - (float) cam.y;
                float az = rays.segs[o + 2] - (float) cam.z;
                float bx = rays.segs[o + 3] - (float) cam.x;
                float by = rays.segs[o + 4] - (float) cam.y;
                float bz = rays.segs[o + 5] - (float) cam.z;
                float dx = bx - ax;
                float dy = by - ay;
                float dz = bz - az;
                final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-4f) {
                    continue;
                }
                final float nx = dx / len;
                final float ny = dy / len;
                final float nz = dz / len;
                consumer.addVertex(pose.pose(), ax, ay, az)
                        .setColor(255, 255, 255, 180)
                        .setNormal(pose, nx, ny, nz);
                consumer.addVertex(pose.pose(), bx, by, bz)
                        .setColor(255, 255, 255, 180)
                        .setNormal(pose, nx, ny, nz);
            }
            // 命中点:黄色小十字(三轴短线,LINES 管线)
            if (rays.hits != null) {
                for (int h = 0; h < rays.hitCount; h++) {
                    final float x = rays.hits[h * 3] - (float) cam.x;
                    final float y = rays.hits[h * 3 + 1] - (float) cam.y;
                    final float z = rays.hits[h * 3 + 2] - (float) cam.z;
                    cross(consumer, pose, x, y, z, 0.15f);
                }
            }
            buffers.endBatch();
        } catch (Throwable ignored) {
            // debug 渲染绝不能带崩帧
        }
    }

    /** 三轴小十字。 */
    private static void cross(com.mojang.blaze3d.vertex.VertexConsumer consumer,
                              com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                              float x, float y, float z, float r) {
        line(consumer, pose, x - r, y, z, x + r, y, z);
        line(consumer, pose, x, y - r, z, x, y + r, z);
        line(consumer, pose, x, y, z - r, x, y, z + r);
    }

    private static void line(com.mojang.blaze3d.vertex.VertexConsumer consumer,
                             com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                             float ax, float ay, float az,
                             float bx, float by, float bz) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return;
        }
        final float nx = dx / len;
        final float ny = dy / len;
        final float nz = dz / len;
        consumer.addVertex(pose.pose(), ax, ay, az)
                .setColor(255, 220, 60, 230)
                .setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose.pose(), bx, by, bz)
                .setColor(255, 220, 60, 230)
                .setNormal(pose, nx, ny, nz);
    }
}
