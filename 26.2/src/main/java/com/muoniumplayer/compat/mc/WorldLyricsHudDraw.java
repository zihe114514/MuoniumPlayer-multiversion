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
     * 「更激进的适配」矩阵快照入口 —— 挂在 GameRenderer.renderLevel 的
     * LevelRenderer.render INVOKE AFTER：世界帧图与 Iris 合成已完成，
     * CameraRenderState 的世界矩阵仍然有效。这里只把本帧的投影/视图旋转
     * 抓进静态快照供 present 阶段使用，不画任何东西。
     */
    private static int wrDiagFrame;
    private static int wrActiveFrames;
    /** 本帧矩阵快照（worldRenderDraw 写、presentComposite 消费）。 */
    private static final Matrix4f snapProj = new Matrix4f();
    private static final Matrix4f snapView = new Matrix4f();
    /** 本帧 bob 共轭阵快照（worldRenderDraw 从 bobConj 拷入、presentComposite 消费）。 */
    private static final Matrix4f snapBobConj = new Matrix4f();
    private static boolean snapBobConjValid;
    private static boolean snapValid;

    /** 关卡投影捕获（ProjectionMatrixBufferMixin 写、worldRenderDraw 读后清）。 */
    private static final Matrix4f liveProj = new Matrix4f();
    private static boolean liveProjFresh;
    /** level 实例缓存（惰性取一次；取不到 = mixin 环境异常，恒走 crs 兜底）。 */
    private static Object levelBufferInstance;
    private static boolean levelBufferSearched;
    /** 诊断：最近一帧快照的投影来源。 */
    private static volatile String projSrc = "-";

    /**
     * ProjectionMatrixBufferMixin 调（getBuffer(Matrix4f) HEAD）：level 实例上
     * 的调用携带 renderLevel 即将上传 UBO 的合成矩阵（extract 透视 × bob ×
     * portal 屏效），与画世界逐字节同源。非本实例的调用（hud3d/Iris 自建缓冲）
     * 全过滤。
     *
     * <p>同时在这里拆出「无 bob 投影」：合成矩阵 P×B 减去 extract 纯透视 P
     * 就是摇晃+屏效增量 B（B⁻¹ = (P×B)⁻¹ × P，P 可逆）。歌词用它构造
     * bob 共轭阵 —— 世界照旧抖，歌词钉在无摇晃视角上（防晕 3D）。</p>
     */
    public static void noteLevelProjection(Object buffer, Matrix4f matrix) {
        if (!HudConfig.worldLyricsEnabled) return;
        if (levelBufferSearched) {
            if (buffer != levelBufferInstance) return;
        } else {
            // 惰性比对：第一次到达的实例先记下，等 GameRendererAccessor 解析后核对。
            // accessor 解析失败（环境异常）时清空实例，后续调用全过滤、恒走 crs 兜底。
            Object resolved = resolveLevelBuffer();
            levelBufferSearched = true;
            levelBufferInstance = resolved;
            if (resolved == null || buffer != resolved) return;
        }
        if (matrix == null || !isFinite(matrix)) return;
        liveProj.set(matrix);
        liveProjFresh = true;
        updateBobConjugate();
    }

    /**
     * 由 liveProj（P×B）与 crs.projectionMatrix（P）拆出 B⁻¹，共轭进视图旋转：
     * M = viewᵀ · B⁻¹ · view。歌词顶点在世界路由的变换链是
     * P·B·view·(billboard)，根矩阵乘 M 后变 P·B·view·M·(billboard) =
     * P·view·(billboard) —— 与「无摇晃视角」逐字节同源。B=I（不移动/关摇晃）
     * 时 M=I，矩阵保留原值零影响。
     *
     * <p>crs 缺席（extract 时序边缘）则 B⁻¹ 取单位阵 —— 本帧歌词与世界同抖，
     * 与旧行为一致，下帧自动恢复。</p>
     */
    private static void updateBobConjugate() {
        bobConjStampNanos = 0;
        Matrix4f p = extractProj();
        if (p == null) return;
        try {
            // B⁻¹ = (P×B)⁻¹ × P；M = viewᵀ · B⁻¹ · view（view 也是正交旋转，viewᵀ=view⁻¹）
            Matrix4f bInv = new Matrix4f(liveProj).invert();
            if (!isFinite(bInv)) return;
            bInv.mul(p);
            Matrix4f view = extractViewRot();
            if (view == null) return;
            Matrix4f m = new Matrix4f(view).transpose().mul(bInv).mul(view);
            if (!isFinite(m)) return;
            bobConj.set(m);
            bobConjStampNanos = System.nanoTime();
        } catch (Throwable ignored) {
            bobConjStampNanos = 0;
        }
    }

    private static Matrix4f extractProj() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameRenderer == null) return null;
            net.minecraft.client.renderer.state.level.CameraRenderState crs =
                    mc.gameRenderer.gameRenderState() != null
                            ? mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState : null;
            if (crs == null || crs.projectionMatrix == null || !isFinite(crs.projectionMatrix)) return null;
            return crs.projectionMatrix;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Matrix4f extractViewRot() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameRenderer == null) return null;
            net.minecraft.client.renderer.state.level.CameraRenderState crs =
                    mc.gameRenderer.gameRenderState() != null
                            ? mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState : null;
            if (crs == null || crs.viewRotationMatrix == null || !isFinite(crs.viewRotationMatrix)) return null;
            return crs.viewRotationMatrix;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本帧 bob 共轭阵（updateBobConjugate 写、present 直画与世界根矩阵读）。 */
    private static final Matrix4f bobConj = new Matrix4f();
    /**
     * 共轭阵生成时间戳（纳秒）。捕获（renderLevel 前段的 getBuffer）与消费
     * （collect / worldRenderDraw）在同帧内相邻发生 —— 时间窗只为兜住
     * 「mixin 本帧缺席」时的陈旧值，过期即退回旧行为（歌词与世界同抖）。
     * 不能用「collect 帧头失效」做新鲜度守卫：getBuffer 在 collect 之前，
     * 帧头失效会把本帧刚算好的共轭阵作废，防线等于永远不生效。
     */
    private static volatile long bobConjStampNanos;
    private static final long BOB_CONJ_FRESH_NANOS = 100_000_000L;

    /** 本帧是否拿到了有效的 bob 共轭阵；false = 歌词与世界同抖（旧行为）。 */
    static boolean bobConjValid() {
        long t = bobConjStampNanos;
        return t != 0 && System.nanoTime() - t < BOB_CONJ_FRESH_NANOS;
    }

    /** bob 共轭阵快照（只读；先查 {@link #bobConjValid()}）。 */
    static Matrix4f bobConjSnapshot(Matrix4f dst) {
        dst.set(bobConj);
        return dst;
    }

    /** 经 accessor 解析 level 缓冲实例；反射/mixin 环境异常返回 null（不重试）。 */
    private static Object resolveLevelBuffer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameRenderer == null) return null;
            return ((com.muoniumplayer.mixin.GameRendererAccessor) mc.gameRenderer)
                    .muonium$levelProjectionMatrixBuffer();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void worldRenderDraw() {
        wrActiveFrames++;
        // 诊断日志默认关：3D 歌词链路已稳定，留在 TRACE 后面按需开
        //（-Dmuonium.debug.worldLyrics=true），避免正常游玩时刷屏
        if (TRACE && wrDiagFrame++ % 60 == 0) {
            System.out.println("[WorldLyrics/diag][worldDraw] broken2=" + broken2
                    + " captureMode=" + captureModeThisFrame()
                    + " probe=" + ShaderPackProbe.isActive()
                    + " activeFrames=" + wrActiveFrames
                    + " quads=" + LyricPresentRenderer.quadCount()
                    + " drawn=" + LyricPresentRenderer.lastDrawnQuads()
                    + " z=[" + LyricPresentRenderer.lastVertexZRange() + "]"
                    + " projSrc=" + projSrc);
        }
        if (broken2 || !HudConfig.worldLyricsEnabled) return;
        if (!captureModeThisFrame()) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            CameraRenderState crs = mc.gameRenderer.gameRenderState() != null
                    ? mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState : null;
            if (crs == null) return;
            // 投影优先取本帧 getBuffer(Matrix4f) 捕获（含 bob 合成，与画世界同源）；
            // 捕获缺席（mixin 未生效/异常）退回 crs 原值 = 旧行为。读后清标志防跨帧陈旧。
            Matrix4f proj = liveProjFresh ? liveProj : crs.projectionMatrix;
            projSrc = liveProjFresh ? "live" : "crs";
            liveProjFresh = false;
            Matrix4f view = crs.viewRotationMatrix;
            if (proj == null || view == null || !isFinite(proj) || !isFinite(view)) {
                snapValid = false;
                return;
            }
            // 快照拷贝（这些矩阵下一帧会被复用改写）
            snapProj.set(proj);
            snapView.set(view);
            // bob 共轭阵随帧快照（present 直画用它把摇晃从歌词里减掉；
            // 共轭阵缺席 = 旧行为，歌词与世界同抖）
            snapBobConjValid = bobConjValid();
            if (snapBobConjValid) snapBobConj.set(bobConj);
            snapValid = true;
        } catch (Throwable t) {
            snapValid = false;
            LyricPresentRenderer.reset();
            LyricPresentRenderer.logFailure(t);
        }
    }

    /** worldRenderDraw 自身异常熔断（present 旧通路仍可用作后备）。 */
    private static volatile boolean broken2;

    /**
     * present 钩子（GlSurfaceMixin 调，GlSurface.present() HEAD）：最终画面已
     * blit 进 FBO 0、交换未发生 —— 把采集的 quad 直接画上 FBO 0。此刻在一切
     * 渲染/后处理/合成之后，画上的像素物理上不可能再被任何 pass 加工。
     */
    public static void presentComposite() {
        if (!HudConfig.worldLyricsEnabled) {
            LyricPresentRenderer.reset();
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null || !snapValid) {
                LyricPresentRenderer.reset();
                return;
            }
            if (!captureModeThisFrame()) {
                LyricPresentRenderer.reset();
                return;
            }
            // 层序守卫：本帧开着界面（背包/设置/聊天/播放器 Screen）就不画 ——
            // present 直画落在一切之后，画了必然盖在原版 GUI 之上；不画，
            // 歌词与被界面盖住的世界保持同层（GUI 打开时它们一起被盖住）。
            if (mc.gui != null && mc.gui.screen() != null) {
                LyricPresentRenderer.reset();
                return;
            }
            com.mojang.blaze3d.pipeline.RenderTarget target = mc.gameRenderer.mainRenderTarget();
            if (target == null) {
                LyricPresentRenderer.reset();
                return;
            }
            com.mojang.blaze3d.platform.Window win = mc.getWindow();
            LyricPresentRenderer.drawToFbo0(snapProj, snapView,
                    snapBobConjValid ? snapBobConj : null,
                    win != null ? win.getWidth() : target.width,
                    win != null ? win.getHeight() : target.height);
        } catch (Throwable t) {
            LyricPresentRenderer.reset();
            LyricPresentRenderer.logFailure(t);
        } finally {
            snapValid = false;
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
