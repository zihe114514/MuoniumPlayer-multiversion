package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import com.muoniumplayer.core.lyrics3d.WorldLyricsSettings;
import com.muoniumplayer.core.lyrics3d.WorldLyricsState;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;

import org.joml.Matrix4f;

/**
 *
 * <p>世界渲染的收集阶段（Fabric {@code LevelRenderEvents.COLLECT_SUBMITS} / NeoForge
 * {@code SubmitCustomGeometryEvent}，两个挂点都在 {@code LevelRenderer.submitFeatures}
 * 里、原版实体收集完之后）：</p>
 * <ol>
 *   <li>镜头转向<b>自己算</b>（1.21.1 同款结论：加载器给的矩阵两树不一致，
 *       自算两边结果一致，与原版画实体用的完全相同）：
 *       {@code identity().rotateX(pitch).rotateY(yaw+180)}；</li>
 *   <li>用一个根矩阵为 basePose 的专用 {@link PoseStack} 收集歌词公告牌；</li>
 *   <li>调 common 渲染编排器把所有句子以 {@code submitCustomGeometry} 提交进
 *       原版 submit-collect 管线（比照名字牌）。</li>
 * </ol>
 *
 * <h2>26.3 相对 1.21.1 少掉的两步</h2>
 * <ul>
 *   <li><b>视图矩阵换单位矩阵</b>：1.21.1 时代把 {@code RenderSystem.getModelViewStack()}
 *       临时换成单位阵、画完换回。26.3 世界绘制改 submit-collect，视图旋转在
 *       {@code LevelRenderer.render} 里已进 modelViewStack（{@code viewRotationMatrix}），
 *       顶点在收集期由 pose 矩阵 bake，无需再动全局栈 —— 动了反而会把
 *       {@code RenderType.prepare()} 捕获的 dynamicTransforms 搅乱。</li>
 *   <li><b>GL 状态逐项设置/恢复</b>：1.21.1 的 {@code GlCompat}（混合/深度/剔除/纹理单元）
 *       在 26.3 没有意义 —— 深度测试、混合等全部由 {@code RenderPipelines.TEXT_SEE_THROUGH}
 *       的管线状态持有（javap 实测 {@code withDepthStencilState(Optional.empty())}），
 *       顶点期根本不经过固定管线状态。</li>
 * </ul>
 *
 * <p>顶点坐标沿用 1.21.1 的口径：镜头相对、未旋转（common 渲染层传
 * {@code 世界坐标 − 相机位置}），镜头旋转只存在于 basePose。</p>
 */
public final class WorldLyricsBridge {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    /** 渲染失败即停（照 hudRenderDisabled 先例）。 */
    private static boolean broken;
    private static boolean hostInstalled;

    /** 一次性链路诊断。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");
    private static boolean tracedReady;
    private static boolean tracedNoLyrics;
    private static boolean tracedRendered;

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
     * 由两个加载器的 COLLECT_SUBMITS / SubmitCustomGeometryEvent 调用 ——
     * 相机自己取，收集器由加载器的事件上下文提供。
     *
     * @param collector 本帧的世界 submit 收集器（原版 submitFeatures 同款）
     */
    public static void collectWorldLyrics(SubmitNodeCollector collector) {
        if (broken || !HudConfig.worldLyricsEnabled || collector == null) return;
        // always-on-top 门旗标（见 fabric 侧 LyricOnTopSubmitter.submittedThisFrame 的
        // 注释）：帧头复位 —— 本帧没提交就保持 vanilla 门。fabric 的
        // LevelRendererAlwaysOnTopGateMixin 读它补执行门的 OR 半句。
        LyricOnTopSubmitter.submittedThisFrame = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            if (mc.gameRenderer == null) return;
            Camera camera = mc.gameRenderer.mainCamera();
            if (camera == null) return;

            // 1. 根矩阵必须是单位矩阵 —— 26.3 与 26.2 同款 submit-collect 管线：
            // LevelRenderer.render 在 submitFeatures 前 mul 了视图旋转（popMatrix 在
            // 帧图执行后），RenderType.prepare() 在 buildGroup（发顶点时）捕获
            // getModelViewMatrixCopy() 写进 ModelViewMat uniform：GPU 端变换 =
            // 视图旋转 × 根矩阵 × billboard。这里再算一遍相机旋转就是转两次 ——
            // 只适用于「事件时视图矩阵是单位矩阵」的旧管线。
            // 顶点坐标仍然用镜头相对坐标（rx = 世界 − 相机），视图旋转把它转回相机空间。
            Matrix4f basePose = new Matrix4f();

            // 2.（26.3 已无）视图矩阵换单位矩阵 / GL 状态设置 —— 见类注释

            // 3. 收集所有句子
            collectInner(basePose, mc, camera, collector);
        } catch (Throwable t) {
            broken = true;
            logger.error("3D 歌词渲染失败，已整体停用（重启游戏恢复）", t);
        }
    }

    private static void collectInner(Matrix4f basePose, Minecraft mc, Camera camera,
                                     SubmitNodeCollector collector) {
        CameraAccess cameraAccess = CameraAccess.capture(0f);
        if (cameraAccess == null) return;

        WorldLyricsSettings settings = WorldLyricsSettings.snapshot();
        if (!settings.enabled) return;
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
                    + com.muoniumplayer.core.ncm.music.CloudMusic.lyricsTrackKey + "），开始收集");
        }

        // 本仓库没有自建矩阵栈：宿主的 PoseStack 原点就是 basePose —— 用一个
        // 专用 PoseStack，pose() 直接填 basePose，push/pop 由 WorldBillboard 自己管。
        PoseStack poseStack = new PoseStack();
        // 新栈的根矩阵直接写成 basePose（rotateX(pitch)·rotateY(yaw+180)）。
        // 之后 WorldBillboard.pushBillboard/popBillboard 在此之上 push/pop，必须配对。
        poseStack.pushPose();
        poseStack.last().pose().set(basePose);

        WorldBillboard.INSTANCE.begin(poseStack, collector,
                cameraAccess.eyeX(), cameraAccess.eyeY(), cameraAccess.eyeZ());
        try {
            WorldLyricsRenderer.render(WorldLyricsState.get(), cameraAccess, settings,
                    WorldBillboard.INSTANCE, System.nanoTime());
            if (TRACE && !tracedRendered) {
                tracedRendered = true;
                System.out.println("[WorldLyrics/trace] 首次 update+collect 编排完成（active/fading 组帧）");
            }
        } finally {
            WorldLyricsBridge.currentSettings = null;
            WorldBillboard.INSTANCE.end();
        }
    }
}
