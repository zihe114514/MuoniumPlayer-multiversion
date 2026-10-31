package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
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
 * <p>世界渲染结束时（Fabric {@code WorldRenderEvents.LAST} / NeoForge
 * {@code RenderLevelStageEvent.AFTER_LEVEL}）：</p>
 * <ol>
 *   <li>镜头转向<b>自己算</b>（Fabric 和加载器给的矩阵不一致，Forge 那边歌词会左右
 *       反过来；自算两边结果一致，与原版画实体用的完全相同）：
 *       {@code identity().rotateX(pitch).rotateY(yaw+180)}；</li>
 *   <li>把原版视图矩阵临时换成<b>单位矩阵</b>（1.20.5 起是 JOML Matrix4fStack），
 *       画完换回来 —— 镜头转向只算这一次；</li>
 *   <li>设好世界文字的 GL 状态（透明混合、关深度测试、关剔除），等价
 *       上游 {@code ModernWorld.begin → worldState} 的口径；</li>
 *   <li>调 common 渲染编排器画所有句子；</li>
 *   <li>恢复视图矩阵与状态。</li>
 * </ol>
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
     * 由两个加载器的 LAST/AFTER_LEVEL 事件调用，无参数 —— 相机与矩阵自己取。
     */
    public static void worldRenderEnd() {
        if (broken || !HudConfig.worldLyricsEnabled) {
            hudCameraValid = false;
            return;
        }
        // 懒装配（幂等）：加载器外壳忘了调 install() 时（1.20.1 fabric 曾漏），
        // 第一帧世界渲染也会把图集宿主装上 —— 现代化渲染不因接线遗漏而整档失效。
        install();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                // 换维度加载/回标题画面：本帧没有世界矩阵可捕获，显式失效 ——
                // 不让 HUD 直画拿着上一帧的陈旧投影/姿态画到加载屏上。
                hudCameraValid = false;
                return;
            }
            if (mc.gameRenderer == null) {
                hudCameraValid = false;
                return;
            }
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

            // 0.5 更激进的适配（HudConfig.worldLyricsHudDraw）：光影包激活时不在这里画
            //    （LAST 事件在 Oculus 合成链之前，画了会被光影的打光/雾/后处理二次加工），
            //    改为捕获本帧世界投影矩阵，推迟到 HUD 阶段（Oculus finalPass 写完主帧缓冲
            //    之后，isRenderingWorld 已复位 → Oculus 不再拦截原版着色器）用原版
            //    textSeeThrough 直画在最终画面上 —— 观感与无光影时一致。
            if (ShaderPackProbe.isActive() && HudConfig.worldLyricsHudDraw) {
                hudProjection.set(RenderSystem.getProjectionMatrix());
                hudProjectionValid = true;
                hudBasePose.rotationX((float) Math.toRadians(camera.getXRot()))
                        .rotateY((float) Math.toRadians(camera.getYRot() + 180.0));
                hudCameraValid = true;
                // 连续多帧 HUD 直画都没完成（异常/回调缺失）→ 世界阶段兜底重画：
                // 光影加工总比整句消失强。hudDrawMisses 在 hudPhaseDraw 成功画完时清零。
                if (hudDrawMisses < 12) {
                    hudDrawMisses++;
                    return;
                }
                if (hudDrawMisses == 12) {
                    System.out.println("[MuoniumPlayer/WorldLyrics] HUD 直画连续 12 帧未完成，"
                            + "已回退到世界阶段绘制（光影会加工歌词；开着更激进的适配却没生效"
                            + "多半是 HUD 阶段绘制在此环境不可用）");
                }
            }
            hudCameraValid = false;

            // 1. 镜头转向自己算（相机朝向独立计算）
            Matrix4f basePose = new Matrix4f()
                    .rotateX((float) Math.toRadians(camera.getXRot()))
                    .rotateY((float) Math.toRadians(camera.getYRot() + 180.0));

            // 2. 视图矩阵换单位矩阵。1.20.1 的 RenderSystem.getModelViewStack() 返回
            // PoseStack（不是 1.21.1 的 Matrix4fStack）：pushPose 后 setIdentity 等价。
            PoseStack viewStack = RenderSystem.getModelViewStack();
            viewStack.pushPose();
            viewStack.setIdentity();
            RenderSystem.applyModelViewMatrix();

            // 2.5 bob 防抖：投影临时换成纯 fov 透视（剔除 bobHurt/bobView 的摇晃增量），
            //     画完还原 —— 世界照旧抖，歌词不抖
            boolean bobFreed = bobFreeApply(mc, camera);

            // 3. 世界文字的 GL 状态（ModernWorld.worldState 一比一）
            GlCompat.blend(true);
            GlCompat.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlCompat.depthTest(false);
            GlCompat.depthMask(false);
            GlCompat.cull(false);
            GlCompat.activeTexture(GL13.GL_TEXTURE0);

            try {
                // 世界阶段辉光（无光影 / 光影但关掉更激进的适配时走这里）：本体进
                // 缓冲的同时双写 mask，画完后跑 bloom 加色补能量。
                hudBloom.beginPass();
                // mask MVP = 本体 endBatch 实际用的 RenderSystem 投影（bobFreeApply
                // 成功时是 pure fov、失败时是关卡投影 —— 无论哪条，此刻 RenderSystem
                // 里躺着的这份就是本体正在用的），直接快照，彻底同源；不再依赖反射成败。
                bobActiveProjection.set(RenderSystem.getProjectionMatrix());
                renderInner(basePose, mc, camera);
                hudBloom.endPass(mc, bobActiveProjection);
            } finally {
                // 恢复状态：视图矩阵 + 我们改过的 GL 状态。逐项还原成 1.20.1 的
                // 原版默认（defaultBlendFunc = SRC_ALPHA/ONE_MINUS_SRC_ALPHA,
                // alpha 侧 ONE/ZERO —— javap RenderSystem.defaultBlendFunc 核对），
                // 而不是「设回我们进来时的另一组值」：blend(true)/blendFunc 只在
                // 本渲染窗口内成立，LAST/AFTER_LEVEL 之后的原版绘制（gui 等）
                // 期望的是默认态。blend 与 activeTexture 同样只设不恢复会泄漏：
                // early-return 帧（无歌词/无行）后面就是原版 GUI，混合态残留会
                // 让无 transparency shard 的 RenderType 继承环境 blend。
                GlCompat.depthTest(true);
                GlCompat.depthMask(true);
                GlCompat.cull(true);
                GlCompat.blend(false);
                GlCompat.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO);
                if (bobFreed) bobFreeRestore(mc);
                viewStack.popPose();
                RenderSystem.applyModelViewMatrix();
            }
        } catch (Throwable t) {
            broken = true;
            logger.error("3D 歌词渲染失败，已整体停用（重启游戏恢复）", t);
        }
    }

    // ==================== 更激进的适配：HUD 阶段直画 ====================

    /** 本帧世界投影矩阵（世界阶段捕获；HUD 阶段恢复用）。 */
    private static final org.joml.Matrix4f hudProjection = new org.joml.Matrix4f();
    /** HUD 阶段的公告牌根姿态（rotateX(pitch)·rotateY(yaw+180)，世界阶段算好）。 */
    private static final org.joml.Matrix4f hudBasePose = new org.joml.Matrix4f();
    private static boolean hudProjectionValid;
    private static boolean hudCameraValid;
    /** HUD 直画连续缺席帧数（兜底世界阶段重画的触发器；成功画完即清零）。 */
    private static int hudDrawMisses;
    private static boolean hudFailureLogged;
    /** 辉光后处理调度（mask 采集 + bloom 合成；未配置/熔断时整段零成本跳过）。 */
    private static final HudBloomPass hudBloom = new HudBloomPass();
    private static boolean tracedHudDraw;

    /**
     * HUD 阶段直画入口（fabric HudRenderCallback / forge RenderGuiEvent.Pre 调）。
     *
     * <p>此时 Oculus 的 composite + finalPass 已把最终画面写进主帧缓冲，且
     * {@code isRenderingWorld=false} —— Oculus 对原版着色器的全部拦截都已失效，
     * 原版 textSeeThrough 就是「无光影时的样子」。投影恢复为世界阶段捕获的透视
     * 矩阵；模型视图照常推单位矩阵。GUI 正交投影先备份后还原。</p>
     */
    public static void hudPhaseDraw() {
        if (broken || !HudConfig.worldLyricsEnabled) return;
        if (!hudCameraValid || !hudProjectionValid) return;
        hudCameraValid = false; // 每帧只画一次；世界阶段下一帧会重新置位
        if (!ShaderPackProbe.isActive() || !HudConfig.worldLyricsHudDraw) {
            hudDrawMisses = 0;
            return;
        }
        if (hudDrawMisses > 0) {
            if (TRACE) System.out.println("[WorldLyrics/trace] HUD 直画恢复（此前缺席 " + hudDrawMisses + " 帧）");
            hudDrawMisses = 0;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;

            org.joml.Matrix4f previousProjection = new org.joml.Matrix4f(RenderSystem.getProjectionMatrix());
            RenderSystem.setProjectionMatrix(new org.joml.Matrix4f(hudProjection),
                    VertexSorting.ORTHOGRAPHIC_Z);

            // bob 防抖：捕获的关卡投影含 bob 合成，歌词直画同样换成纯 fov 透视
            //（失败则保留含 bob 投影 = 旧行为）。bobFreeBackup 覆写掉刚备份的
            // previousProjection 没关系 —— 还原链是 bobFreeRestore → previousProjection，
            // 两步都走、顺序固定。
            Camera hudCam = mc.gameRenderer.getMainCamera();
            boolean bobFreed = hudCam != null && bobFreeApply(mc, hudCam);

            PoseStack viewStack = RenderSystem.getModelViewStack();
            viewStack.pushPose();
            viewStack.setIdentity();
            RenderSystem.applyModelViewMatrix();

            GlCompat.blend(true);
            GlCompat.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlCompat.depthTest(false);
            GlCompat.depthMask(false);
            GlCompat.cull(false);
            GlCompat.activeTexture(GL13.GL_TEXTURE0);

            try {
                WorldBillboard.forceVanillaType = true;
                // 辉光后处理：renderInner 期间字形 quad 双写进 mask，画完后跑 bloom
                // —— 合成是加色混合，与本体绘制先后无关；字芯能量再叠一层。
                hudBloom.beginPass();
                renderInner(hudBasePose, mc, mc.gameRenderer.getMainCamera());
                hudBloom.endPass(mc);
            } finally {
                WorldBillboard.forceVanillaType = false;
                GlCompat.depthTest(true);
                GlCompat.depthMask(true);
                GlCompat.cull(true);
                GlCompat.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                if (bobFreed) bobFreeRestore(mc);
                viewStack.popPose();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(previousProjection, VertexSorting.ORTHOGRAPHIC_Z);
            }
        } catch (Throwable t) {
            // 刻意不置 broken：HUD 直画失败只损失本帧，下一帧世界阶段照常兜底 ——
            // 若这里也锁死整个 3D 歌词，用户连关掉开关都救不回来。
            // 首次失败无条件打日志（用户排障不依赖 -D 开关），之后 TRACE 节流。
            if (!hudFailureLogged) {
                hudFailureLogged = true;
                logger.warn("3D 歌词 HUD 阶段直画失败（本帧跳过，12 帧后回退世界阶段绘制）", t);
            } else if (TRACE) {
                System.out.println("[WorldLyrics/trace] HUD 阶段直画失败（本帧跳过）: " + t);
            }
        }
    }

    // ==================== bob 防抖（视角摇晃不带动歌词，防晕 3D） ====================

    /**
     * 1.20.1 的 bob 与 26.x 同构（javap renderLevel）：fov 透视 P 乘上
     * bobHurt/bobView 后经 resetProjectionMatrix 上传全球 —— 歌词直画换的只是
     * 模型视图栈，投影里的 bob 照样作用于歌词顶点，走路时歌词跟着视角一起晃。
     *
     * <p>剔除法：歌词直画窗口内把投影临时换成「纯 fov 透视」
     *（{@code gameRenderer.getProjectionMatrix(getFov(camera, partialTick, true))}
     * —— 两个方法都 public，不需要 mixin/AT），画完还原。世界照旧抖，
     * 歌词钉在无摇晃视角上。partialTick 取 {@code mc.getFrameTime()}（与
     * renderLevel 传给 bobHurt/bobView 的同一帧插值）。</p>
     */
    private static boolean bobFreeProjFailed;
    /** getFov 反射句柄（private；一次性查找缓存）。 */
    private static volatile java.lang.reflect.Method bobFovMethod;
    private static boolean bobFovLookupFailed;

    /**
     * getFov 的运行时真名：dev/named 环境是 {@code getFov}，生产 Fabric 是
     * intermediary {@code method_3196}、生产 Forge 是 SRG {@code m_109141_}。
     * 只按 named 找在生产环境必然 NoSuchMethodException → bob 防抖永久熔断
     * （连带辉光 mask 的 MVP 停在单位阵、晕整帧不可见）。逐个尝试并缓存命中。
     */
    private static String fovMethodName() {
        return new String[] {"getFov", "method_3196", "m_109141_"}[bobFovNameIndex];
    }

    private static int bobFovNameIndex;

    /**
     * 直画前调：把投影换成无 bob 的纯 fov 透视。返回 false = 本帧拿不到
     *（异常/边缘时序），调用方照旧直画（与世界同抖的旧行为），下一帧重试。
     * 配对调 {@link #bobFreeRestore(Minecraft)}。
     */
    private static boolean bobFreeApply(Minecraft mc, Camera camera) {
        if (bobFreeProjFailed) return false;
        try {
            net.minecraft.client.renderer.GameRenderer gr = mc.gameRenderer;
            // getFov(camera, partialTick, useFovSetting=true) 与 renderLevel 头部的
            // fov 读取完全同参，纯 P 与关卡 P 只差 bob。getFov 是 private —— 反射调。
            java.lang.reflect.Method fovM = bobFovMethod;
            while (fovM == null && !bobFovLookupFailed) {
                try {
                    fovM = net.minecraft.client.renderer.GameRenderer.class
                            .getDeclaredMethod(fovMethodName(), Camera.class, float.class, boolean.class);
                    fovM.setAccessible(true);
                    bobFovMethod = fovM;
                } catch (NoSuchMethodException missing) {
                    if (++bobFovNameIndex >= 3) {
                        bobFovLookupFailed = true;
                    }
                }
            }
            if (fovM == null) { bobFreeProjFailed = true; return false; }
            float partialTick = mc.getFrameTime();
            double fov = (Double) fovM.invoke(gr, camera, partialTick, true);
            org.joml.Matrix4f pure = gr.getProjectionMatrix(fov);
            if (pure == null || !isFinite(pure)) return false;
            bobActiveProjection.set(pure);
                        bobFreeBackup.set(RenderSystem.getProjectionMatrix());
            RenderSystem.setProjectionMatrix(new org.joml.Matrix4f(pure), VertexSorting.DISTANCE_TO_ORIGIN);
            return true;
        } catch (Throwable t) {
            // 一次性熔断：投影通路异常时不再反复尝试（歌词与世界同抖总比反复异常强）
            bobFreeProjFailed = true;
            if (!TRACE) {
                System.out.println("[MuoniumPlayer/WorldLyrics] bob 防抖不可用（歌词将随视角摇晃）: " + t);
            }
            return false;
        }
    }

    /** 直画后调：还原进入前的投影矩阵（与 {@link #bobFreeApply} 配对）。 */
    private static void bobFreeRestore(Minecraft mc) {
        try {
            RenderSystem.setProjectionMatrix(new org.joml.Matrix4f(bobFreeBackup),
                    VertexSorting.DISTANCE_TO_ORIGIN);
        } catch (Throwable ignored) {
        }
    }

    /** bobFreeApply 备份的关卡投影（1.20.1 投影是 Matrix4f 值，需手动备份）。 */
    private static final org.joml.Matrix4f bobFreeBackup = new org.joml.Matrix4f();
    /** 本帧本体直画实际生效的透视投影（bobFreeApply 写；辉光 mask 的 MVP 来源）。 */
    private static final org.joml.Matrix4f bobActiveProjection = new org.joml.Matrix4f();

    private static boolean isFinite(org.joml.Matrix4f m) {
        return Float.isFinite(m.m00()) && Float.isFinite(m.m11())
                && Float.isFinite(m.m22()) && Float.isFinite(m.m23())
                && Float.isFinite(m.m33());
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
        // 绘光贴图预热（幂等）：惰性生成的首帧成本移出歌内
        if (settings.huiguang) {
            WorldBillboard.INSTANCE.prewarmTextures();
        }
        try {
            renderInnerWithSettings(basePose, mc, cameraAccess, settings);
        } finally {
            // 帧末清空（含 early-return 路径）：发光参数快照只在本渲染窗口内有效。
            WorldLyricsBridge.currentSettings = null;
        }
    }

    private static void renderInnerWithSettings(Matrix4f basePose, Minecraft mc,
                                                CameraAccess cameraAccess,
                                                WorldLyricsSettings settings) {
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
