package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.joml.Matrix4f;

/**
 * 「更激进的适配」的 26.x 兜底直画调度 —— <b>世界阶段不画、帧末落屏</b>。
 *
 * <h2>26.x 为什么不能照抄 1.21.1 的 HUD 直画</h2>
 * 26.x 的 HUD 回调（fabric HudElementRegistry / neoforge RenderGuiEvent）全部改到
 * <b>extract 阶段</b>（GameRenderer.extract → Hud.extractRenderState），此时本帧
 * 渲染根本没开始：GameRenderer.render 一进来就把主渲染目标整屏清色 —— 在回调里
 * 画的任何东西都会在下一行被抹掉。而且 MultiBufferSource 已删除，即时 endBatch
 * 的通路也不存在了。Iris finalPass 又注入在 LevelRenderer.render 的 popMatrix
 * （帧图执行后、GUI 前）—— 所以唯一「光影永远碰不到」的落点是帧末
 * {@code GlSurface#present()} 的入口：那一刻最终画面已 blit 进窗口 FBO 0、
 * 交换还没发生（SkijaHost 每帧在此合成 HUD，仓内已验证的槽位）。
 *
 * <h2>通路：采集（collect）→ 落屏（present）</h2>
 * <ol>
 *   <li><b>采集</b>：{@link WorldBillboard} 开 capture 模式 —— 平滑字形路径不再
 *       submit，改把每个 quad 的四顶点（相机相对世界轴坐标 + 颜色 + UV + 页纹理）
 *       记进 {@link LyricPresentRenderer} 的复用缓冲；同时按本帧
 *       {@link CameraRenderState} 的公开矩阵字段算好 MVP。</li>
 *   <li><b>落屏</b>：{@code GlSurfaceMixin} 在 present 入口先调
 *       {@link #presentComposite()}（画在 Skija HUD 之下，与其他树「歌词在 HUD 下」
 *       语义一致）—— 极简 GL 3.3 着色器把缓冲里的 quad 直接画上 FBO 0。
 *       Iris 的合成链早已收工，不可能再加工这些像素。</li>
 * </ol>
 *
 * <p>像素字路径不进采集（原版名牌同路径，光影下本就正常）。开关关或光影未激活
 * 时零分支开销（一个布尔判断）。12 帧兜底：present 直画缺席（异常/后端不符）时
 * 自动回退世界路由 —— 光影加工总比整句消失强。</p>
 */
public final class WorldLyricsHudDraw {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    /** 世界采集连续缺席帧数（present 兜底触发器；present 成功画完即清零）。 */
    private static int presentMisses;
    private static boolean fallbackLogged;
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");
    private static boolean tracedCapture;
    private static int diagFrame;

    private WorldLyricsHudDraw() {
    }

    /**
     * 本帧是否走「采集 + present 兜底」通路（collectWorldLyrics 帧头调用）。
     * 光影激活 + 开关开 = 采集模式；否则照旧 submit 进原版管线。
     */
    public static boolean captureModeThisFrame() {
        return ShaderPackProbe.isActive() && HudConfig.worldLyricsHudDraw;
    }

    /**
     * 采集帧的收尾（collectInner 完成后调）：本帧采到顶点则标记成功；
     * 连续 12 帧没采到（图集全回退/异常）→ 回退世界路由。
     *
     * @param captured 本帧是否采到顶点
     * @return true = 采集成功、本帧跳过世界 submit；false = 回退世界路由
     */
    public static boolean onCaptureEnd(boolean captured) {
        if (captured) {
            presentMisses = 0;
            if (TRACE && !tracedCapture) {
                tracedCapture = true;
                System.out.println("[WorldLyrics/trace] 更激进的适配（26.x）：首帧采集完成，转 present 兜底直画");
            }
            return true;
        }
        if (presentMisses < 12) {
            presentMisses++;
            return true;
        }
        if (presentMisses == 12) {
            // 首次兜底无条件打印（用户侧排障不依赖 -D 开关）
            System.out.println("[MuoniumPlayer/WorldLyrics] present 兜底直画连续 12 帧未完成，"
                    + "已回退到世界阶段绘制（光影会加工歌词；开着更激进的适配却没生效"
                    + "多半是 present 直画在此环境不可用）");
        }
        return false;
    }

    /** GlSurfaceMixin present 入口调：把采集的 quad 画上 FBO 0（在 Skija HUD 之前）。 */
    public static void presentComposite() {
        if (!HudConfig.worldLyricsEnabled) {
            LyricPresentRenderer.reset();
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                LyricPresentRenderer.reset();
                return;
            }
            Camera camera = mc.gameRenderer != null ? mc.gameRenderer.mainCamera() : null;
            if (camera == null || mc.gameRenderer.gameRenderState() == null) {
                LyricPresentRenderer.reset();
                return;
            }
            // CameraRenderState 的矩阵在 extract 阶段由 Camera.extractRenderState 填好：
            // projectionMatrix = 透视（fov/aspect/zNear/depthFar），viewRotationMatrix =
            // 视图旋转（无平移、无 bob —— bob 在 GameRenderer.renderLevel 里才 mul）。
            // 我们的顶点是「相机相对世界轴」坐标，MVP = P × V 一步到位。
            CameraRenderState crs = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            Matrix4f proj = crs != null ? crs.projectionMatrix : null;
            Matrix4f view = crs != null ? crs.viewRotationMatrix : null;
            if (proj == null || view == null || !isFinite(proj) || !isFinite(view)) {
                LyricPresentRenderer.reset();
                return;
            }
            if (diagFrame++ % 60 == 0) {
                System.out.println("[WorldLyrics/diag][present] cam(yaw=" + crs.yRot + " pitch=" + crs.xRot
                        + ") quads=" + LyricPresentRenderer.quadCount() + " activePos="
                        + com.muoniumplayer.core.lyrics3d.WorldLyricsState.activeLineDebugPos());
            }
            LyricPresentRenderer.draw(proj, view);
        } catch (Throwable t) {
            // 刻意不置全局 broken：present 直画失败只损失本帧，采集端下一帧照常。
            LyricPresentRenderer.reset();
            LyricPresentRenderer.logFailure(t);
        }
    }

    private static boolean isFinite(Matrix4f m) {
        // joml 的元素遍历口是 getTransposition/get(FloatBuffer)；逐元素用字段访问
        return Float.isFinite(m.m00()) && Float.isFinite(m.m11())
                && Float.isFinite(m.m22()) && Float.isFinite(m.m33())
                && Float.isFinite(m.m23()) && Float.isFinite(m.m30())
                && Float.isFinite(m.m31()) && Float.isFinite(m.m32());
    }

    /** present 侧诊断/日志封装（放 LyricPresentRenderer 里以便共用熔断）。 */
    static void logPresentFailure(Throwable t) {
        LyricPresentRenderer.logFailure(t);
    }

    static {
        // 触碰类时打一次装机日志（TRACE 或首次失败由 LyricPresentRenderer 自理）
        if (TRACE) {
            logger.info("[WorldLyrics/trace] 更激进的适配（26.x present 兜底）调度器就绪");
        }
    }
}
