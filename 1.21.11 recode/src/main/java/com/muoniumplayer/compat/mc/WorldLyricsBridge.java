package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import com.muoniumplayer.core.lyrics3d.WorldLyricsSettings;
import com.muoniumplayer.core.lyrics3d.WorldLyricsState;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 *
 * <p>世界渲染结束时（Fabric {@code WorldRenderEvents.END_MAIN} / NeoForge
 * {@code RenderLevelStageEvent.AfterLevel}）：</p>
 * <ol>
 *   <li>镜头转向<b>自己算</b>（Fabric 和加载器给的矩阵不一致，Forge 那边歌词会左右
 *       反过来；自算两边结果一致，与原版画实体用的完全相同）：
 *       {@code identity().rotateX(pitch).rotateY(yaw+180)}；</li>
 *   <li>把原版视图矩阵临时换成<b>单位矩阵</b>（仍是 JOML Matrix4fStack），
 *       画完换回来 —— 镜头转向只算这一次；</li>
 *   <li>设好世界文字的 GL 状态（透明混合、关深度测试、关剔除），等价
 *   <li>调 common 渲染编排器画所有句子；</li>
 *   <li>恢复视图矩阵与状态。</li>
 * </ol>
 *
 * <p>1.21.11 与 1.21.1 的差异（javap 核对）：{@code GlStateManager} 从
 * {@code blaze3d.platform} 挪到 {@code blaze3d.opengl}（方法同名）；
 * {@code RenderSystem.applyModelViewMatrix()} 没了 —— Matrix4fStack 是
 * RenderSystem 直接持有的同一个栈对象，就地改即生效，无需再 apply。</p>
 */
public final class WorldLyricsBridge {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    /** 渲染失败即停（照 hudRenderDisabled 先例）。 */
    private static boolean broken;
    private static boolean hostInstalled;

    /** 一次性链路诊断。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");
    private static final java.util.Set<String> TRACED_ONCE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean tracedReady;
    private static boolean tracedNoLyrics;
    private static boolean tracedRendered;

    /** 一次性诊断日志（Set 去重；仅 muonium.debug.worldLyrics 开启时输出）。
     *  与 1.21.1 标杆同名同参 —— mip 链熔断等深层模块都经它报一次。 */
    static void traceOnce(String msg) {
        if (!TRACE || !TRACED_ONCE.add(msg)) return;
        System.out.println("[WorldLyrics/trace] " + msg);
    }

    private WorldLyricsBridge() {
    }

    /** 树侧初始化：装配字形图集宿主（现代化风格的世界侧纹理通路）。 */
    public static void install() {
        if (hostInstalled) return;
        hostInstalled = true;
        try {
            com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas.setHost(LyricGlyphAtlasBridge.INSTANCE);
        } catch (Throwable t) {
            logger.error("3D 歌词字形图集宿主装配失败，现代档将回退像素字", t);
        }
    }

    /** 本帧设置快照（WorldBillboard.drawSmoothGlyph 取发光参数用；帧头装、帧末清）。 */
    static volatile WorldLyricsSettings currentSettings;

    /**
     * 由两个加载器的 END_MAIN/AfterLevel 事件调用，无参数 —— 相机与矩阵自己取。
     */
    public static void worldRenderEnd() {
        if (broken || !HudConfig.worldLyricsEnabled) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            if (mc.gameRenderer == null) return;
            Camera camera = mc.gameRenderer.getMainCamera();
            if (camera == null) return;

            // 1. 镜头转向自己算（ModernDango 一比一）
            Matrix4f basePose = new Matrix4f()
                    .rotateX((float) Math.toRadians(camera.xRot()))
                    .rotateY((float) Math.toRadians(camera.yRot() + 180.0));

            // 2. 视图矩阵换单位矩阵（1.21.11：RenderSystem.getModelViewStack 仍是 Matrix4fStack，
            //    且就是 RenderSystem 用的那个栈 —— 1.21.1 的 applyModelViewMatrix 已被移除）
            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.getModelViewStack().identity();

            // 3. 世界文字的 GL 状态（ModernWorld.worldState 一比一）
            GlCompat.blend(true);
            GlCompat.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlCompat.depthTest(false);
            GlCompat.depthMask(false);
            GlCompat.cull(false);
            GlCompat.activeTexture(GL13.GL_TEXTURE0);

            try {
                renderInner(basePose, mc, camera);
            } finally {
                // 这里逐项还原等价的几项，避免泄漏给本帧后续的绘制）。
                GlCompat.depthTest(true);
                GlCompat.depthMask(true);
                GlCompat.cull(true);
                GlCompat.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                RenderSystem.getModelViewStack().popMatrix();
            }
        } catch (Throwable t) {
            broken = true;
            logger.error("3D 歌词渲染失败，已整体停用（重启游戏恢复）", t);
        }
    }

    private static void renderInner(Matrix4f basePose, Minecraft mc, Camera camera) {
        CameraAccess cameraAccess = CameraAccess.capture(0f);
        if (cameraAccess == null) return;

        WorldLyricsSettings settings = WorldLyricsSettings.snapshot();
        if (!settings.enabled) return;
        // 画质档同步（幂等）：自动档按当前帧缓冲高度解析——用户改窗口大小后
        // 下一次渲染自然跟上；手动档 setBakeScale 同值直接短路，零成本
        com.muoniumplayer.core.screens.hud.HudSettingsModel.syncWorldLyricsQuality();
        WorldLyricsBridge.currentSettings = settings;

        if (!WorldLyricsState.hasLyrics()) {
            if (TRACE && !tracedNoLyrics) {
                tracedNoLyrics = true;
                System.out.println("[WorldLyrics/trace] 开关已开、世界正常，但当前没有已加载的歌词"
                        + "（trackKey=" + com.muoniumplayer.core.ncm.music.CloudMusic.lyricsTrackKey
                        + "，行数=" + com.muoniumplayer.core.ncm.music.CloudMusic.lyrics.size() + "）");
            }
            return;
        }
        if (TRACE && !tracedReady) {
            tracedReady = true;
            System.out.println("[WorldLyrics/trace] 歌词就绪（trackKey="
                    + com.muoniumplayer.core.ncm.music.CloudMusic.lyricsTrackKey + "），开始渲染");
        }

        // 本仓库没有自建矩阵栈：宿主的 PoseStack 原点就是 basePose —— 用一个
        // 专用 PoseStack，pose() 直接填 basePose，push/pop 由 WorldBillboard 自己管。
        PoseStack poseStack = new PoseStack();
        // 新栈的根矩阵直接写成 basePose（rotateX(pitch)·rotateY(yaw+180)）。
        // 之后 WorldBillboard.pushBillboard/popBillboard 在此之上 push/pop，必须配对。
        poseStack.pushPose();
        poseStack.last().pose().set(basePose);

        WorldBillboard.INSTANCE.begin(poseStack,
                mc.renderBuffers().bufferSource(),
                cameraAccess.eyeX(), cameraAccess.eyeY(), cameraAccess.eyeZ());
        try {
            WorldLyricsRenderer.render(WorldLyricsState.get(), cameraAccess, settings,
                    WorldBillboard.INSTANCE, System.nanoTime());
            if (TRACE && !tracedRendered) {
                tracedRendered = true;
                System.out.println("[WorldLyrics/trace] 首次 update+render 编排完成（active/fading 组帧）");
            }
        } finally {
            WorldLyricsBridge.currentSettings = null;
            WorldBillboard.INSTANCE.end();
        }
    }

    private static final class GlCompat {
        static void blend(boolean on) {
            if (on) GlStateManager._enableBlend();
            else GlStateManager._disableBlend();
            if (on) GL11.glEnable(GL11.GL_BLEND);
            else GL11.glDisable(GL11.GL_BLEND);
        }

        static void blendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }

        static void depthTest(boolean on) {
            if (on) GlStateManager._enableDepthTest();
            else GlStateManager._disableDepthTest();
            if (on) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        static void depthMask(boolean on) {
            GlStateManager._depthMask(on);
            GL11.glDepthMask(on);
        }

        static void cull(boolean on) {
            if (on) GlStateManager._enableCull();
            else GlStateManager._disableCull();
            if (on) GL11.glEnable(GL11.GL_CULL_FACE);
            else GL11.glDisable(GL11.GL_CULL_FACE);
        }

        static void activeTexture(int unit) {
            GlStateManager._activeTexture(unit);
            GL13.glActiveTexture(unit);
        }
    }
}
