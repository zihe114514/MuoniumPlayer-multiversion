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
     * 连续 120 帧（约 2~6 秒）没采到才回退世界路由。
     *
     * <p>为什么不能太敏感：字形图集按帧预算烘焙（每帧 4 格），换句歌词的最初几帧
     * 新字还没烘出来、huiguang 模式下静默跳过 —— 采集天然会间歇空几帧
     * （2026-10-28 真机日志：quads 40→0→13 交替）。12 帧就回退会把这种正常
     * 窗口误判成通路不可用，造成「回退打印刷屏 + 世界路由双画」。</p>
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
        if (presentMisses < 120) {
            presentMisses++;
            return true;
        }
        if (presentMisses == 120) {
            presentMisses++; // 只打一次；之后保持 >120 恒 false，不再刷屏
            System.out.println("[MuoniumPlayer/WorldLyrics] present 兜底采集连续 120 帧为空，"
                    + "已回退到世界阶段绘制（光影会加工歌词；开着更激进的适配却没生效"
                    + "多半是 present 直画在此环境不可用）");
        }
        return false;
    }

    /**
     * 「更激进的适配」的主绘制入口 —— 挂在 GameRenderer.renderLevel 的
     * LevelRenderer.render INVOKE AFTER：
     * 世界帧图与 Iris 合成已完成、mainRenderTarget 是最终画面；手部/GUI 未画、
     * present 的 blit 未发生 —— 直接画进 mainTarget，字随原版正常链路上屏。
     *
     * <p>矩阵：投影从显存 UBO 读（含视角摇晃，与原版画世界同源）；视图旋转此时
     * 还在 RenderSystem 的 modelViewStack 上（popMatrix 在帧图后由原版执行）——
     * 按原版惯例把它推进栈顶换成单位矩阵，画完恢复，MVP = 投影 × basePose × 字形
     * 变换。GL 状态用 GlState 快照进出来还。</p>
     */
    private static int wrDiagFrame;
    private static int wrActiveFrames;

    public static void worldRenderDraw() {
        wrActiveFrames++;
        Minecraft mcDiag = Minecraft.getInstance();
        if (wrDiagFrame++ % 60 == 0) {
            // 入口探针：确认 mixin 生效 + 各早退条件状态
            CameraRenderState crsD = mcDiag != null && mcDiag.gameRenderer != null
                    && mcDiag.gameRenderer.gameRenderState() != null
                    ? mcDiag.gameRenderer.gameRenderState().levelRenderState.cameraRenderState : null;
            Matrix4f pD = crsD != null ? crsD.projectionMatrix : null;
            System.out.println("[WorldLyrics/diag][worldDraw] broken2=" + broken2
                    + " captureMode=" + captureModeThisFrame()
                    + " probe=" + ShaderPackProbe.isActive()
                    + " activeFrames=" + wrActiveFrames
                    + " noViewRot=" + NO_VIEW_ROT
                    + " P=(" + (pD == null ? "null" : pD.m03() + "," + pD.m13() + "," + pD.m23()
                        + " | " + pD.m00() + "," + pD.m11()) + ")");
        }
        if (broken2 || !HudConfig.worldLyricsEnabled) return;
        if (!captureModeThisFrame()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            if (mc.gameRenderer == null || mc.gameRenderer.mainRenderTarget() == null) return;
            Camera camera = mc.gameRenderer.mainCamera();
            if (camera == null) return;

            // 本帧采集的 quad 在 collectWorldLyrics 时已写入 LyricPresentRenderer。
            // 采到的变换仍按旧约定（相机相对世界轴），MVP = P × V。
            // V：视图旋转（从 CameraRenderState 拿，语义与 modelViewStack 栈顶一致）。
            Object dev = null;
            try {
                dev = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            } catch (Throwable ignored) {
            }
            CameraRenderState crs = mc.gameRenderer.gameRenderState() != null
                    ? mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState : null;
            Matrix4f proj = crs != null ? crs.projectionMatrix : null;
            Matrix4f view = NO_VIEW_ROT ? new Matrix4f()
                    : (crs != null ? crs.viewRotationMatrix : null);
            if (proj == null || view == null || !isFinite(proj) || !isFinite(view)) return;

            com.mojang.blaze3d.pipeline.RenderTarget target = mc.gameRenderer.mainRenderTarget();
            com.mojang.blaze3d.platform.Window win = mc.getWindow();
            LyricPresentRenderer.setWindowSize(win != null ? win.getWidth() : target.width,
                    win != null ? win.getHeight() : target.height);

            // 全套 GL 状态快照进出（program/vao/vbo/FBO/纹理绑定/混合/深度/
            // 剔除/剪裁/视口/色掩码）
            LyricPresentRenderer.drawInMainTarget(proj, view, target, mc);
        } catch (Throwable t) {
            LyricPresentRenderer.reset();
            LyricPresentRenderer.logFailure(t);
        }
    }

    /** worldRenderDraw 自身异常熔断（present 旧通路仍可用作后备）。 */
    private static volatile boolean broken2;
    /** A/B 对照：-Dmuonium.lyricsNoViewRot=1 = MVP 不乘视图旋转矩阵。 */
    static final boolean NO_VIEW_ROT = Boolean.getBoolean("muonium.lyricsNoViewRot");

    /** present 钩子（GlSurfaceMixin 调）：主绘制已迁走，这里只做兜底复位。 */
    public static void presentComposite() {
        // 主绘制已迁到 worldRenderDraw（GameRenderer.renderLevel INVOKE AFTER）；
        // present 钩子不再画 —— 采集缓冲已被世界入口消费，这里只剩兜底复位。
        LyricPresentRenderer.reset();
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
