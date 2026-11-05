package com.muoniumplayer.core.cine;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住卡点运镜的架构不变量：共享层纯净、树侧接线完整、版本差异只在 compat。
 *
 * <p>运镜是「共享层调度 + 树侧接管」结构：共享层一行 MC 代码都不能有
 * （铁律 1），树侧 mixin 注入 Camera 的形状由 26.3 先行，回打六树时
 * 这组断言就是移植检查单。</p>
 */
class CineStaticTest {

    private static String readShared(String... parts) throws Exception {
        Path path = ProjectPaths.sharedJava()
                .resolve(Paths.get("com", "muoniumplayer"))
                .resolve(Paths.get("", parts));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * 读当前树（测试挂在哪棵树就验证哪棵；26.3 先行，回打六树后逐树生效）的
     * 版本差异层文件。
     */
    private static String readTree(String... parts) throws Exception {
        Path path = ProjectPaths.versionJava()
                .resolve(Paths.get("com", "muoniumplayer"))
                .resolve(Paths.get("", parts));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 当前树是否 26.x 世代（Camera 有 alignWithEntity/Gui 拆 Hud 子对象）。 */
    private static boolean is26x() {
        return ProjectPaths.versionRoot().getFileName().toString().startsWith("26.");
    }

    /** 本树第二个加载器子工程名（1.20.1 是 forge,其余 neoforge;fabriques 侧恒 fabric）。 */
    private static String secondLoader() {
        return ProjectPaths.versionRoot().getFileName().toString().startsWith("1.20.1") ? "forge" : "neoforge";
    }

    // ---------------- 铁律 1:共享层零加载器符号 ----------------

    @Test
    void 共享层cine包零MC与loader符号() throws Exception {
        String[] files = {
                "core/cine/CineSettings.java",
                "core/cine/CineDirector.java",
                "core/cine/CinePose.java",
                "core/cine/CineShots.java",
                "core/cine/CineTransition.java",
                "core/cine/CineBars.java",
                "core/ncm/music/CineBeatSource.java",
        };
        for (String file : files) {
            String source = readShared(file.split("/"));
            assertFalse(source.contains("import net.minecraft"),
                    file + " 不得 import MC（铁律 1）");
            assertFalse(source.contains("import net.fabricmc"), file + " 不得 import Fabric");
            assertFalse(source.contains("import net.neoforged"), file + " 不得 import NeoForge");
            assertFalse(source.contains("import net.minecraftforge"), file + " 不得 import Forge");
            assertFalse(source.contains("import cpw.mods"), file + " 不得 import cpw.mods");
        }
    }

    // ---------------- 26.3 树侧接线 ----------------

    @Test
    void 树tick桥接线与翻转执行器() throws Exception {
        String source = readTree("MuoniumPlayerClient.java");
        assertTrue(source.contains("CineDirector.onClientTick("),
                "tick 桥必须喂 SceneProbe 给调度器");
        assertTrue(source.contains("com.muoniumplayer.compat.mc.CineProbe.of(mc)"),
                "probe 由树侧构造");
        assertTrue(source.contains("CineProbe.entered()") && source.contains("CineProbe.left()"),
                "isActive 翻转沿必须调树侧执行器（切第三人称/隐藏 HUD 及恢复）");
    }

    @Test
    void 树mixin注册与注入点形状() throws Exception {
        // 当前树（26.3 先行）的两份 mixin 清单都要注册；versionRoot 是 <树>/，
        // loader 子工程在其下。
        boolean is263 = ProjectPaths.versionRoot().getFileName().toString().equals("26.3");
        for (String loader : new String[]{"fabric", secondLoader()}) {
            Path json = ProjectPaths.versionRoot()
                    .resolve(Paths.get(loader, "src", "main", "resources", "muonium.mixins.json"));
            String source = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
            assertTrue(source.contains("CineCameraMixin"), loader + " 必须注册 CineCameraMixin");
            if (!is263) {
                assertTrue(source.contains("CineFovMixin"), loader + " 必须注册 CineFovMixin");
            }
        }
        // 相机 mixin:26.x 注 alignWithEntity 首个 setPosition AFTER;旧世代注 setup 同点
        String camera = readTree("mixin", "CineCameraMixin.java");
        assertTrue(camera.contains("@Mixin(Camera.class)"));
        if (is26x()) {
            assertTrue(camera.contains("alignWithEntity"),
                    "26.x 注入点必须是 alignWithEntity(与 TransparentCameraMixin 相邻)");
        } else {
            assertTrue(camera.contains("method = \"setup\""),
                    "1.21.11 前无 alignWithEntity,注入点必须是 setup 首个 setPosition");
        }
        assertTrue(camera.contains("CineDirector.isActive()"),
                "接管必须以 isActive() 为门");
        // FOV mixin:旧世代挂 GameRenderer.getFov(主投影);26.3 有意不注册——
        // 26.x 的 getFov() 只被 3D 歌词投影消费,主投影走 Projection 对象,
        // punch 无主画面效果反而带偏歌词投影(2026-11-05 实机复盘下架)。
        if (!is263) {
            String fov = readTree("mixin", "CineFovMixin.java");
            assertTrue(fov.contains("getFov") && fov.contains("RETURN"),
                    "FOV punch 必须挂在 getFov 的返回值上");
            if (!is26x()) {
                assertTrue(fov.contains("GameRenderer.class"),
                        "旧世代 FOV 在 GameRenderer.getFov(Camera,float,boolean)");
            }
        }
    }

    @Test
    void 树probe实现SceneProbe契约() throws Exception {
        String probe = readTree("compat", "mc", "CineProbe.java");
        assertTrue(probe.contains("implements CineDirector.SceneProbe"),
                "CineProbe 必须实现共享层接口");
        for (String method : new String[]{"playerX", "playerY", "playerZ", "playerYaw",
                "playerPitch", "openScreen", "hurtThisTick", "inputThisTick",
                "playerAlive", "clipBlocked", "nearestMovingMob"}) {
            assertTrue(probe.contains(method + "("),
                    "CineProbe 缺 SceneProbe 方法：" + method);
        }
        // 执行器:进入/退出恢复(相机类型 + HUD)。26.x 走 gui.hud.toggle;
        // 1.21.11 前无 Hud 子对象,走 options.hideGui(F3 同款字段)。
        assertTrue(probe.contains("setCameraType"), "entered/left 必须处理相机类型");
        if (is26x() && !ProjectPaths.versionRoot().getFileName().toString().equals("26.1.2")) {
            assertTrue(probe.contains("gui.hud.isHidden") && probe.contains("hud.toggle()"),
                    "26.2+ 的 HUD 隐藏走 gui.hud.toggle(Ambient Camera 亲证的 API)");
        } else {
            assertTrue(probe.contains("options.hideGui"),
                    "26.1.2 与旧世代的 HUD 隐藏走 options.hideGui");
        }
        // 生物排除规则读共享设置,不是写死
        assertTrue(probe.contains("CineSettings.excludeHostileMobs()"),
                "敌对排除必须读设置(共享层单真相)");
    }

    // ---------------- 渲染路径 ----------------

    @Test
    void 转场与黑边走extract闸门外的注入点() throws Exception {
        // 常规 HUD 路(HudElementRegistry/EventRender2D)在 Hud.isHidden 时被
        // extractRenderState 的 hidden 闸门整个跳过 —— 运镜开着 hideHud,遮罩入口
        // 必须在闸门之外:CineHudMixin 注入 extractRenderState TAIL(照 AmbientCamera
        // Fabric 版 HudMixin 的解法)。
        String handler = readShared("core", "MuoniumPlayerEventHandler.java");
        assertFalse(handler.contains("CineTransition.render()"),
                "转场遮罩不许走 onRender2D 常规 HUD 路(hidden 闸门会把它关掉)");
        String mixin = readTree("mixin", "CineHudMixin.java");
        if (is26x() && !ProjectPaths.versionRoot().getFileName().toString().equals("26.1.2")) {
            assertTrue(mixin.contains("@Mixin(Hud.class)")
                            && mixin.contains("extractRenderState") && mixin.contains("TAIL"),
                    "26.2+ 遮罩/黑边必须从 Hud.extractRenderState TAIL 画(hidden 闸门之外)");
        } else {
            // 26.1.2 与旧世代:Gui 世代。26.1.2 是 extractRenderState(隐藏走 options.hideGui,
            // TAIL 在闸门外);1.21.x/1.20.1 是 render(GuiGraphics,float/DeltaTracker),
            // hideGui 闸门在方法中部,TAIL 照样在闸门后。
            assertTrue(mixin.contains("@Mixin(Gui.class)") && mixin.contains("TAIL"),
                    "26.1.2 与旧世代遮罩走 Gui 的 TAIL(在 hidden 闸门之外)");
        }
        assertTrue(mixin.contains("deferPostCompositeOverlay"),
                "遮罩绘制必须 defer 到帧末合成后 —— extract TAIL 处自开 beginFrame"
                        + " 会做 FBO 切换+GL 快照还原,与同帧其它 Skija 调用方交错时"
                        + "回滚 MC 收集中间态(实机:偶发方块全黑/画面抽搐)");
        assertFalse(mixin.contains("beginFrame"),
                "mixin 里不许自开 Skija 帧(抽搐根因,已改 defer 路)");
        String director = readShared("core", "cine", "CineDirector.java");
        assertTrue(director.contains("cineOverlayRunnable"),
                "遮罩/黑边打包成帧末合成任务由 CineDirector 提供");
        assertTrue(director.contains("CineTransition.render()") && director.contains("CineBars.render()"),
                "转场与黑边都在这个任务里驱动");
        String transition = readShared("core", "cine", "CineTransition.java");
        assertTrue(transition.contains("SkijaGfx.canvas()"),
                "绘制走 Skija 栈(与 mod 其余部分同路径)");
    }

    @Test
    void 设置文件单入口注册() throws Exception {
        String paths = readShared("core", "settings", "ConfigPaths.java");
        assertTrue(paths.contains("cine.json") && paths.contains("CINE"),
                "cine.json 必须在 ConfigPaths 注册(失败半径隔离纪律)");
        String settings = readShared("core", "cine", "CineSettings.java");
        assertTrue(settings.contains("ConfigPaths.CINE"),
                "CineSettings 必须读 ConfigPaths.CINE,不许自带路径");
    }

    @Test
    void 节拍链只读不重复分析() throws Exception {
        String beat = readShared("core", "ncm", "music", "CineBeatSource.java");
        assertTrue(beat.contains("FoliaTrackProfiler.peek"),
                "一级网格必须走既有 peek(profile 惰性分析在 BeatPulseSource,不重复投)");
        assertTrue(beat.contains("BeatPulseSource.estimatedBeatForCine"),
                "二级估算必须复用 BeatPulseSource 的探针");
        assertFalse(beat.contains("new AudioPlayer("),
                "CineBeatSource 不许自建播放器分析(节拍链单真相)");
    }
}
