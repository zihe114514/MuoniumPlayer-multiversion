package com.muoniumplayer.core.rendering.animation;

import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.muoniumplayer.testsupport.ProjectPaths;

/**
 * 「界面淡入淡出速度」这个配置项的行为约束。不需要 GL、不需要启动客户端。
 *
 * <p>钉两件事：
 * <ol>
 *   <li>倍率的作用点<b>只有</b>播放器界面打开/关闭时那层整屏 alpha。曾经的做法是在
 *       {@code GuiScreen#render} 期间给 {@link Interpolations} 设一个全局倍率，
 *       那会把这一屏里所有元素一起加速；各元素之间的相对快慢是设计过的，
 *       统一提速之后的走形肉眼很难定位，所以那套东西已经撤掉，不要再回来。</li>
 *   <li>配置项必须两套设置界面都能改到、并且能落盘。少了任何一样，
 *       用户就只能改一次或者重启即丢。</li>
 * </ol>
 */
class AnimationSpeedStaticTest {

    private static final Path MAIN_JAVA = ProjectPaths.sharedJava();

    @AfterEach
    void 还原默认倍率() {
        HudConfig.playerFadeSpeed = 1.0f;
    }

    @Test
    @DisplayName("全局动画倍率已经撤掉：其它动画一律原速")
    void 没有全局倍率() throws IOException {
        // 这里只能做文本扫描：Interpolations 的插值要读 RenderSystem 的帧间隔，
        // 而 RenderSystem 的静态初始化需要真实的客户端（离线跑会 ExceptionInInitializerError）。
        String interpolations = read(MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "core",
                "rendering", "animation", "Interpolations.java")));
        assertFalse(interpolations.contains("speedScale"),
                "Interpolations 不许再有全局动画倍率：它会把一屏里所有动画一起缩放，"
                        + "各元素之间的相对快慢就会走形");
        assertFalse(interpolations.contains("PLAYER_UI_ANIMATION_SPEED"),
                "整屏提速的常量已经撤掉，不要再引入");

        String guiScreen = read(MAIN_JAVA.resolve(
                Paths.get("com", "muoniumplayer", "compat", "mc", "GuiScreen.java")));
        assertFalse(guiScreen.contains("animationSpeedScale"),
                "GuiScreen#render 不许再按屏设置动画倍率");
        assertFalse(guiScreen.contains("SpeedScale"),
                "GuiScreen#render 不许再碰全局动画倍率");
    }

    @Test
    @DisplayName("倍率只作用于打开/关闭转场，每个界面各一处")
    void 倍率的作用点只有淡入淡出() throws IOException {
        for (String screen : new String[]{
                "core/screens/ncm/NCMScreen.java",
                "core/screens/ncm/CoverflowOverlay.java"}) {
            String code = read(MAIN_JAVA.resolve("com/muoniumplayer").resolve(screen));

            int at = code.indexOf("HudConfig.playerFadeSpeed");
            assertTrue(at > 0, screen + " 没有读 HudConfig.playerFadeSpeed，"
                    + "这一屏的淡入淡出就不受配置控制");
            assertEquals(-1, code.indexOf("HudConfig.playerFadeSpeed", at + 1),
                    screen + " 里 playerFadeSpeed 出现了不止一次。这个倍率只许作用于"
                            + "打开/关闭的整屏 alpha；一旦蔓延到别的动画上，"
                            + "界面内部的节奏关系就会随用户拖动而走形");

            if (screen.endsWith("NCMScreen.java")) {
                assertTrue(code.contains("PlayerIslandTransition.open(HudConfig.playerFadeSpeed)"),
                        screen + " 的 playerFadeSpeed 必须传给 PlayerIslandTransition.open，"
                                + "否则开关动画不受配置控制");
            } else {
                String statement = code.substring(code.lastIndexOf("alpha =", at), at);
                assertTrue(statement.contains("Interpolations.interpolate(alpha, closing"),
                        screen + " 的 playerFadeSpeed 不在整屏淡入淡出那一句里："
                                + statement.trim());
            }
        }
    }

    @Test
    @DisplayName("配置项读写正确，且钳位后仍然是正数")
    void 配置项读写与钳位() {
        HudSetting fade = HudSetting.PLAYER_FADE_SPEED;

        fade.setValue(2.0f);
        assertEquals(2.0f, fade.getValue(), 1e-6);
        assertEquals(2.0f, HudConfig.playerFadeSpeed, 1e-6);

        // 0 或负数会让界面永远停在半透明，那种状态很难和「卡住」区分开。
        assertTrue(fade.getMin() > 0.0f, "下限必须大于 0，否则界面能被调成永远淡不进来");
        fade.setValue(-5.0f);
        assertEquals(fade.getMin(), fade.getValue(), 1e-6);
        fade.setValue(999.0f);
        assertEquals(fade.getMax(), fade.getValue(), 1e-6);
        assertTrue(fade.getMax() > 1.0f && fade.getMin() < 1.0f,
                "默认速度 1.0 必须落在可调区间内部，否则用户改不回默认观感");
    }

    @Test
    @DisplayName("两套设置界面都能改到这一项")
    void 设置界面里能找到() throws IOException {
        boolean found = false;
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.SLIDER
                            && entry.setting == HudSetting.PLAYER_FADE_SPEED) {
                        found = true;
                    }
                }
            }
        }
        assertTrue(found, "设置声明里没有这一项，新版设置界面（GuiHudEditorPro）就看不到它");

        // 经典界面的分组是硬编码的，不走上面的声明，所以要单独钉一次。
        String classic = read(MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "core",
                "screens", "hud", "GuiHudEditor.java")));
        assertTrue(classic.contains("HudSetting.PLAYER_FADE_SPEED"),
                "经典设置界面里没有这一项，切到经典界面的用户就改不了");
    }

    @Test
    @DisplayName("配置会落盘，重启不会丢")
    void 配置会持久化() throws IOException {
        String config = read(MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "core",
                "settings", "HudConfig.java")));
        assertTrue(config.contains("getFloat(o, \"playerFadeSpeed\""),
                "HudConfig.load 没有读这一项，重启之后又回到默认速度");
        assertTrue(config.contains("o.addProperty(\"playerFadeSpeed\""),
                "HudConfig.save 没有写这一项，用户改完就丢");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
