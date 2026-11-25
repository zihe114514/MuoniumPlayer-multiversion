package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置 Skija 默认路由与可选 ModernUI 宿主边界。
 *
 * <p>只读源码，不触发 OpenAPI / 渲染线程初始化。</p>
 *
 * <p>这里最要紧的一条是最后一项：ModernUI 按 Minecraft 版本发版，本项目四棵树里只有部分版本
 * 拿得到构件（26.2 目前就没有），所以共享源码只能靠反射引用它。一旦有人在 {@code src/main/java}
 * 里直接写 {@code import icyllis.modernui.*}，另外几棵树就整棵编不过去 —— 那正是本测试要拦的事。</p>
 */
class HudExternalUiStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("H 键默认打开 Skija 调参页，显式 ModernUI 不可用时回到内置调参页")
    void dispatchUsesEmbeddedSkija() throws IOException {
        String client = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "MuoniumPlayerClient.java")));
        int start = client.indexOf("private void openHudEditor(Minecraft mc)");
        assertTrue(start >= 0, "缺少 H 键界面分派");
        String dispatch = client.substring(start, client.indexOf("\n    }", start));
        assertTrue(dispatch.contains("HUD_EDITOR_UI_MODERNUI.equals(HudConfig.hudEditorUi)"));
        assertTrue(dispatch.contains("ModernUiHudBridge.open()"));
        assertTrue(dispatch.contains("SkijaConfigScreen.openSettings()"));
        assertTrue(dispatch.indexOf("ModernUiHudBridge.open()") < dispatch.indexOf("SkijaConfigScreen.openSettings()"));
        assertTrue(!client.contains("OneConfigHudBridge.open()"), "内置界面不能重新进入独立 MOD");
        assertTrue(!client.contains("MuoniumConfigNotice.draw"), "不再展示独立设置 MOD 下载提示");
        String screen = read(ProjectPaths.sharedJava().resolve("com/muoniumplayer/configskija/host/SkijaConfigScreen.java"));
        assertTrue(screen.contains("new SkijaConfigScreen(Navigation.Page.SETTINGS)"),
                "调参入口必须显式选择设置页，不受上次 HUD 编辑路由记忆影响");
    }

    @Test
    @DisplayName("内置 Skija 是持久化默认档，设置模型不再按独立 OneConfig 安装状态降级")
    void configAndModelExposeEmbeddedDefault() throws IOException {
        String config = read(settings("HudConfig.java"));
        assertTrue(config.contains("hudEditorUi = HUD_EDITOR_UI_BUILTIN;"));
        assertTrue(config.contains("o.addProperty(\"hudEditorUi\", hudEditorUi)"));
        String model = read(hud("HudSettingsModel.java"));
        assertTrue(model.contains("new String[] {\"内置 Skija\", \"ModernUI\"}"));
        assertTrue(model.contains("HudSettingsModel::setUiLayerChoice"));
        assertTrue(model.contains("ModernUiHudBridge.statusLabel()"));
        assertTrue(model.contains("回退内置 Skija"));
        assertTrue(!model.contains("OneConfigHudBridge.isAvailable()"));
    }

    @Test
    @DisplayName("插座是纯反射，并且分两半判可用性")
    void bridgeDetectsInstallAndPanelSeparately() throws IOException {
        String bridge = read(hud("ModernUiHudBridge.java"));
        assertTrue(bridge.contains("\"icyllis.modernui.mc.MuiModApi\""),
                "探测的必须是 ModernUI 的 modding API 门面类名");
        assertTrue(bridge.contains("\"com.muoniumplayer.core.screens.hud.modernui.ModernUiHudProvider\""),
                "面板实现按约定的 provider 类名转交");
        assertTrue(bridge.contains("return isInstalled() && hasPanel();"),
                "「装了 ModernUI」与「本构建编入了面板」要分开判：只装没面板时不能谎报可用");
        assertTrue(bridge.contains("if (!isAvailable()) return false;"),
                "不可用时 open() 要直接返回 false，由调用方回落");
    }

    @Test
    @DisplayName("共享源码不得在编译期依赖 ModernUI，否则拿不到构件的版本树整棵编不过")
    void sharedSourceNeverImportsModernUi() throws IOException {
        final List<Path> offenders = new ArrayList<Path>();
        { java.util.stream.Stream<Path> walk = ProjectPaths.allSourceFiles().stream();
            walk.filter(Files::isRegularFile)
                    .filter(ProjectPaths::isSourceFile)
                    .forEach(p -> {
                        try {
                            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            if (text.contains("import icyllis.modernui")) {
                                offenders.add(SHARED_JAVA.relativize(p));
                            }
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        }
        assertTrue(offenders.isEmpty(), "这些共享文件直接 import 了 ModernUI：" + offenders
                + " —— 只能走反射桥，见 ModernUiHudBridge");
    }

    @Test
    @DisplayName("控制中心页签也走同一条反射桥，注册时机交给每 tick 问一次")
    void actionCenterTabIsRegisteredThroughTheBridge() throws IOException {
        String bridge = read(hud("ModernUiHudBridge.java"));
        assertTrue(bridge.contains("public static boolean registerActionCenterTab()"),
                "控制中心页签的注册口要在桥上，不能让客户端直接认识 ModernUI");
        assertTrue(bridge.contains("if (actionCenterHooked || !isAvailable()) return actionCenterHooked;"),
                "没装 / 本树没面板 / 已挂过 三种情况都不该去反射");
        assertTrue(bridge.contains("\"registerActionCenterTab\""),
                "转交的是 provider 上那个同名静态方法");

        String client = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "MuoniumPlayerClient.java")));
        assertTrue(client.contains("ModernUiHudBridge.registerActionCenterTab();"),
                "客户端要有注册入口：ModernUI 的初始化时机不由我们掌握");
    }

    private static Path settings(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "settings", fileName));
    }

    private static Path hud(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", fileName));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
