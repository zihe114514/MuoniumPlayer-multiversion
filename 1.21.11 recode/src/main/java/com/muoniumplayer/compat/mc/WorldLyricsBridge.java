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
 * 3D 世界歌词的树侧桥 —— <b>世界渲染结束事件入口（Fabric LAST / NeoForge AFTER_LEVEL）</b>。
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
 *       上游 {@code ModernWorld.begin → worldState} 的口径；</li>
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
    private static boolean tracedShaderActive;

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
     * 世界渲染结束事件入口（Fabric LAST / NeoForge AFTER_LEVEL）。
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

            // 0. 光影包探测（帧头一次，本帧内渲染层读缓存值）：
            //    光影包激活时 WorldBillboard 改走信标全亮程序（ShaderPackProbe 类注释）
            ShaderPackProbe.refresh();
            // 「更激进的适配」关闭沿检测：HUD 阶段的连续失败可能把渲染器锁死，
            // 用户关掉开关回到世界阶段路径时立即恢复（不用重启游戏）。
            if (tracedHudDraw && !HudConfig.worldLyricsHudDraw) {
                com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer.clearBrokenLatch();
                hudDrawMisses = 0;
                if (TRACE) System.out.println("[WorldLyrics/trace] 更激进的适配已关闭：渲染器锁已清除，恢复世界阶段绘制");
            }
            tracedHudDraw = HudConfig.worldLyricsHudDraw;
            if (TRACE) {
                boolean shaderNow = ShaderPackProbe.isActive();
                if (shaderNow != tracedShaderActive) {
                    tracedShaderActive = shaderNow;
                    System.out.println("[WorldLyrics/trace] 光影包状态切换 → "
                            + (shaderNow ? "光影模式（信标全亮程序）" : "普通模式（textSeeThrough）"));
                }
            }

            // 0.5 更激进的适配（hudDraw）：1.21.11 暂不启用 —— GUI 已 deferred 化（GuiGraphicsExtractor），
            // 回调期间即时 endBatch 实测不落最终画面；需等 renderpearl 原生 HUD 提交通路。
            // 这里恒走世界阶段绘制（beaconBeam 路由），开关在本树是无害的 no-op。

            // 1. 镜头转向自己算（相机朝向独立计算）
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
                // 恢复状态：视图矩阵 + 我们改过的 GL 状态（上游用 GlState 快照恢复，
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

    // ==================== 更激进的适配：HUD 阶段直画（1.21.11 暂不启用） ====================

    // 1.21.11 的 GUI 已全面 deferred 化（GuiGraphicsExtractor）：HudRenderCallback
    // 回调期间用 BufferSource 即时 endBatch 的顶点不落最终画面（实测隐形）——
    // 即时通路要等 renderpearl 原生的 HUD 提交（低层 RenderPass + 私有顶点缓冲），
    // 投入产出比当前不成立。开关在本树是无害的 no-op，世界阶段恒走 beaconBeam
    // 路由（光影加工的版本）。字段暂留占位：hudDrawMisses/tracedHudDraw 供
    // 未来接入 renderpearl 通路时复用。
    private static int hudDrawMisses;
    private static boolean tracedHudDraw;

    /**
     * HUD 阶段直画入口（fabric HudRenderCallback / neoforge RenderGuiEvent.Pre 调）。
     * Iris 的 composite + finalPass 已写完主帧缓冲且 isRenderingWorld 已复位 ——
     * 原版 textSeeThrough 直出「无光影时的样子」。投影用世界阶段捕获的透视矩阵，
     * GUI 正交投影先备份后还原。
     */
    public static void hudPhaseDraw() {
        if (broken || !HudConfig.worldLyricsEnabled) return;
        // 1.21.11：HUD 直画通路暂不启用（见 worldRenderEnd 注释）—— 恒 no-op。
        hudDrawMisses = 0;
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

        // 上游 ModernWorld.begin 口径：Fixed.reset(basePose)。
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

    /** GlStateManager 同步的 GL 状态封装（上游 Gl 的最小子集）。 */
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
