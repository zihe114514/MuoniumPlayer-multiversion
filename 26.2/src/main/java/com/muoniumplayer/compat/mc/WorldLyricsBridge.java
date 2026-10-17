package com.muoniumplayer.compat.mc;

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
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;

import org.joml.Matrix4f;

/**
 * 按到 26.2 renderpearl 渲染管线重排钩子。
 *
 * <h2>26.2 相对 1.21.1 的钩子差异（javap + 字节码核对）</h2>
 * <p>1.21.1 是「事件里直接画」：{@code WorldRenderEvents.LAST} /
 * {@code RenderLevelStageEvent.AFTER_LEVEL} 回调时视图矩阵还在栈上，换单位矩阵 +
 * 设 GL 状态 + {@code Font.drawInBatch} 立即发顶点即可。26.2 改成 renderpearl 的
 * <b>延迟采集</b>：实体文字全部改为 {@code OrderedSubmitNodeCollector.submitText(...)}
 * 进 {@code SubmitNodeStorage}，随后 {@code FeatureRenderDispatcher.prepareFrame}
 * 把相位 drain 进 {@code PreparedFrame}（字节码：drainPhases 在 prepareFrameWithContext
 * 里，{@code removeIf} 清空已消费相位），帧图 main pass 里 {@code executeTranslucent()}
 * 才真正画（texts 相位 → {@code textSeeThrough} 管线）。事件时序：</p>
 * <ol>
 *   <li>{@code LevelRenderer.render}：push modelViewStack、mul 视图旋转；</li>
 *   <li>{@code submitFeatures}（Fabric：{@code COLLECT_SUBMITS} 在它的 RETURN 派发、
 *       context 带 {@code submitNodeCollector()}；NeoForge：{@code SubmitCustomGeometryEvent}
 *       在实体/B/E/粒子提交完、finalizeGizmos 前派发、自带 collector）——
 *       <b>本桥的采集点</b>；</li>
 *   <li>{@code prepareFrame}（drain）→ 帧图 main pass execute（Fabric 的
 *       {@code END_MAIN} / NeoForge 的 {@code AfterLevel} 都在这之后 ——
 *       collector 已被 drain，作为钩子太迟，只能做「保险熔断复位」）。</li>
 * </ol>
 * <p>所以移植形态从「事件里画」改成「事件里提交」：相机转向仍在采集时自己算
 * submit 一起走 —— 与实体文字完全同管线（同一 texts 相位、同一 see-through 批），
 * 1.21.1 里「Fabric/NeoForge 给的矩阵不一致」的老问题随 drawInBatch 一起消失。
 * GL 状态不再手设：{@code textSeeThrough} 的 RenderType 自带混合/深度设置；
 * 26.2 的 {@code GlStateManager} 在 {@code com.mojang.blaze3d.opengl} 包下、
 * {@code RenderSystem.getModelViewStack()} 仍是 JOML Matrix4fStack，但都不再需要动。</p>
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
     * 采集形态）。由 Fabric {@code LevelRenderEvents.COLLECT_SUBMITS} /
     * NeoForge {@code SubmitCustomGeometryEvent} 调用；两处都拿得到
     * {@code OrderedSubmitNodeCollector}（Fabric 从 context、NeoForge 从事件），
     * caller 必须传入，为 null 时本帧跳过 —— 26.2 没有「绕过事件直接拿
     * LevelRenderer 存储」的公开路径（javap 核对：{@code submitNodeStorage} 是
     * private，{@code LevelRenderer} 不实现 collector 接口）。
     */
    public static void worldRenderCollect(OrderedSubmitNodeCollector collector) {
        if (broken || !HudConfig.worldLyricsEnabled) return;
        if (collector == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            if (mc.gameRenderer == null) return;
            Camera camera = mc.gameRenderer.mainCamera();
            if (camera == null) return;
            WorldBillboard.WorldLyricsBridgeAccess.current = collector;

            // 根矩阵必须是单位矩阵 —— 相机旋转已经在模型视图栈上（LevelRenderer.render
            // 在 submitFeatures 前 mul 了视图旋转、popMatrix 在帧图执行之后），而
            // RenderType.prepare() 在 buildGroup（= 我们发顶点的时候）捕获
            // getModelViewMatrixCopy() 写进 ModelViewMat uniform：GPU 端变换 =
            // 视图旋转 × 根矩阵 × billboard。这里再算一遍相机旋转就是转两次 ——
            // 只适用于「事件时视图矩阵是单位矩阵」的旧管线。
            // 顶点坐标仍然用镜头相对坐标（rx = 世界 − 相机），视图旋转把它转回相机空间。
            Matrix4f basePose = new Matrix4f();

            renderInner(basePose, mc, camera);
        } catch (Throwable t) {
            broken = true;
            logger.error("3D 歌词渲染失败，已整体停用（重启游戏恢复）", t);
        } finally {
            WorldBillboard.WorldLyricsBridgeAccess.current = null;
        }
    }

    private static void renderInner(Matrix4f basePose, Minecraft mc, Camera camera) {
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
                    + com.muoniumplayer.core.ncm.music.CloudMusic.lyricsTrackKey + "），开始渲染");
        }

        // 本仓库没有自建矩阵栈：宿主的 PoseStack 原点就是 basePose —— 用一个
        // 专用 PoseStack，pose() 直接填 basePose，push/pop 由 WorldBillboard 自己管。
        PoseStack poseStack = new PoseStack();
        // 新栈的根矩阵直接写成 basePose（rotateX(pitch)·rotateY(yaw+180)）。
        // 之后 WorldBillboard.pushBillboard/popBillboard 在此之上 push/pop，必须配对。
        poseStack.pushPose();
        poseStack.last().pose().set(basePose);

        WorldBillboard.INSTANCE.begin(poseStack, mc,
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
}
