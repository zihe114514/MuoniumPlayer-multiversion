package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 世界声源的可见性:在锚点画一个小型「音箱」线框(立方体 + 底座十字),
 * 颜色随降级状态变化 —— 绿 = 正常、黄 = 降级(超程/换维度/区块卸载)。
 * 没有声源时第一行就返回,零开销。
 *
 * <p>渲染通路与 3D 歌词同点(26.3 的 COLLECT_SUBMITS / SubmitCustomGeometryEvent,
 * 原版 submitFeatures 收集阶段):专用 PoseStack 根矩阵 = 单位阵 + bob 共轭不在
 * 本标记的需求内(小线框随 view bob 一起微晃是真实感,不是病),顶点坐标 =
 * 「世界坐标 − 相机位置」镜头相对口径,视图旋转由管线根矩阵完成(与
 * WorldLyricsBridge 的结论一致:26.3 在 submitFeatures 前已 mul 视图旋转,
 * 根矩阵必须是单位阵)。线段用 {@code RenderTypes.lines()}(原版 debug 画线
 * 管线,POSITION_COLOR + 法线;线段 normal 取线方向,无光照语义)。</p>
 *
 * <p>只在 26.3 注册(本树验收);所有异常静默 —— debug 性质的渲染绝不能带崩帧。</p>
 */
public final class SpeakerRenderBridge {

    /** 音箱线框的半边长(格):约 0.35 = 一个 0.7 大小的方块体。 */
    private static final float HALF = 0.35f;
    /** 底座十字半径(格)。 */
    private static final float BASE_R = 0.55f;

    /** 渲染失败即停(照 WorldLyricsBridge 的 broken 先例)。 */
    private static boolean broken;

    private SpeakerRenderBridge() {
    }

    /**
     * 世界渲染收集阶段入口,由两个加载器的事件回调调用(与
     * {@code WorldLyricsBridge.collectWorldLyrics} 同一挂点,先后无碍)。
     *
     * @param collector 本帧的 submit 收集器
     */
    public static void collect(SubmitNodeCollector collector) {
        if (broken || collector == null || !SpeakerManager.hasSpeaker()) {
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
            final Vec3 cam = mc.gameRenderer.mainCamera().position();
            // 状态取色:绿(正常)/黄(降级)。降级时也可以加个呼吸闪烁 —— 保持
            // 简单:颜色区分就够,降级提示已走聊天栏。
            final int rgb = SpeakerManager.currentStatus()
                    == com.muoniumplayer.core.ncm.music.acoustics.SpeakerState.Status.ACTIVE
                    ? 0x3CE86A : 0xF0C440;

            final PoseStack poseStack = new PoseStack(); // 根矩阵 = 单位阵(见类注释)
            final float cx = (float) (s.x() - cam.x);
            final float cy = (float) (s.y() - cam.y);
            final float cz = (float) (s.z() - cam.z);

            collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, consumer) -> {
                // 立方体 12 条棱
                box(consumer, pose, cx, cy, cz, HALF, rgb, 230);
                // 底座十字:标记「这里可以站」,也是降级状态之外的第三重确认
                cross(consumer, pose, cx, cy - HALF - 0.05f, cz, BASE_R, rgb, 200);
            });
        } catch (Throwable ignored) {
            broken = true; // 渲染失败永久停用,绝不能带崩帧
        }
    }

    /** 轴对齐立方体线框。 */
    private static void box(VertexConsumer c, PoseStack.Pose pose,
                            float cx, float cy, float cz, float h, int rgb, int a) {
        final float x0 = cx - h, x1 = cx + h;
        final float y0 = cy - h, y1 = cy + h;
        final float z0 = cz - h, z1 = cz + h;
        // 底面 4 条
        line(c, pose, x0, y0, z0, x1, y0, z0, rgb, a);
        line(c, pose, x1, y0, z0, x1, y0, z1, rgb, a);
        line(c, pose, x1, y0, z1, x0, y0, z1, rgb, a);
        line(c, pose, x0, y0, z1, x0, y0, z0, rgb, a);
        // 顶面 4 条
        line(c, pose, x0, y1, z0, x1, y1, z0, rgb, a);
        line(c, pose, x1, y1, z0, x1, y1, z1, rgb, a);
        line(c, pose, x1, y1, z1, x0, y1, z1, rgb, a);
        line(c, pose, x0, y1, z1, x0, y1, z0, rgb, a);
        // 立柱 4 条
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
        // 26.3 的 lines 管线格式是 POSITION_COLOR_NORMAL_LINE_WIDTH —— 每顶点
        // 必须带线宽,漏一项 BufferBuilder 直接抛「Missing elements in vertex」。
        c.addVertex(pose.pose(), ax, ay, az).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz).setLineWidth(1.0f);
        c.addVertex(pose.pose(), bx, by, bz).setColor(r, g, b, a)
                .setNormal(pose, nx, ny, nz).setLineWidth(1.0f);
    }
}
