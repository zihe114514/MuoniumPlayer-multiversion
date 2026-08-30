package com.muoniumplayer.core.settings;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「播放器开关动画速度」这一个配置项的不变量。
 *
 * <p>需求是<b>只调播放器界面打开/关闭的淡入淡出</b>，其它动画一律不动。这件事没有任何编译期
 * 约束：谁把 {@code HudConfig.playerFadeSpeed} 顺手乘到页面切换、歌词页或灵动岛的逼近系数上，
 * 代码照样编译通过，只有肉眼盯着动画才可能发现。所以这里用源码扫描把「只有一个消费者」钉住。</p>
 *
 * <p>纯文本 + 配置层，不加载任何渲染类，因此不需要 GL、不需要客户端。</p>
 */
class PlayerFadeSpeedProbeTest {

    private static final Path MAIN_JAVA = ProjectPaths.sharedJava();
    private static final Path NCM_SCREEN =
            MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "screens", "ncm", "NCMScreen.java"));
    private static final Path SETTINGS_MODEL =
            MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java"));
    private static final Path CLASSIC_EDITOR =
            MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "screens", "hud", "GuiHudEditor.java"));

    @Test
    @DisplayName("配置项：默认 1.00×，区间 0.30×~3.00×，越界被夹住")
    void 配置项的区间与默认值() {
        assertEquals(1.0f, HudConfig.playerFadeSpeed, 1.0e-6f, "默认必须是 1.00×，即历史速度不变");
        assertEquals(0.30f, HudSetting.PLAYER_FADE_SPEED.getMin(), 1.0e-6f);
        assertEquals(3.00f, HudSetting.PLAYER_FADE_SPEED.getMax(), 1.0e-6f);

        float original = HudConfig.playerFadeSpeed;
        try {
            HudSetting.PLAYER_FADE_SPEED.setValue(99.0f);
            assertEquals(3.00f, HudSetting.PLAYER_FADE_SPEED.getValue(), 1.0e-6f, "上界必须夹住");
            HudSetting.PLAYER_FADE_SPEED.setValue(-5.0f);
            assertEquals(0.30f, HudSetting.PLAYER_FADE_SPEED.getValue(), 1.0e-6f, "下界必须夹住");
            HudSetting.PLAYER_FADE_SPEED.setValue(1.75f);
            assertEquals(1.75f, HudConfig.playerFadeSpeed, 1.0e-6f, "区间内必须原样写入 HudConfig");
        } finally {
            HudConfig.playerFadeSpeed = original;
        }
    }

    @Test
    @DisplayName("恢复默认会把它写回 1.00×")
    void 恢复默认覆盖这一项() {
        float original = HudConfig.playerFadeSpeed;
        try {
            HudConfig.playerFadeSpeed = 2.5f;
            HudConfig.resetPlayerAnimation();
            assertEquals(1.0f, HudConfig.playerFadeSpeed, 1.0e-6f);
        } finally {
            HudConfig.playerFadeSpeed = original;
        }
    }

    @Test
    @DisplayName("只有播放器开关那一处淡入淡出读这个配置")
    void 唯一消费者() throws IOException {
        List<String> referencing = new ArrayList<String>();
        final List<String> hits = referencing;
        Files.walkFileTree(MAIN_JAVA, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                String source = stripComments(read(file));
                if (source.contains("playerFadeSpeed") || source.contains("PLAYER_FADE_SPEED")) {
                    hits.add(file.getFileName().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // 允许出现的四个文件：字段本身、枚举、唯一消费者、两套设置界面的声明。
        // 多出任何一个都意味着这个倍率被接到了别的动画上 —— 那正是需求明确排除的。
        List<String> allowed = new ArrayList<String>();
        allowed.add("HudConfig.java");
        allowed.add("HudSetting.java");
        allowed.add("NCMScreen.java");
        allowed.add("HudSettingsModel.java");
        allowed.add("GuiHudEditor.java");
        for (String file : referencing) {
            assertTrue(allowed.contains(file),
                    "不该有别的文件读播放器开关动画速度: " + file + "（需求是只调这一段淡入淡出）");
        }
        assertTrue(referencing.contains("NCMScreen.java"), "唯一的消费者 NCMScreen 不见了");
    }

    @Test
    @DisplayName("NCMScreen：开关动画走倍率，页面切换的 0.4 保持写死")
    void 消费点与非消费点() throws IOException {
        String source = stripComments(read(NCM_SCREEN));
        assertTrue(source.contains("Interpolations.interpolate(alpha, closing ? 0f : 1f, fadeRate())"),
                "整屏 alpha 的逼近必须走 fadeRate()");
        assertTrue(source.contains("BASE_FADE_RATE = .4f"),
                "基准系数必须仍是历史默认 .4：用户配的是它的倍率，改基准会让所有存量配置整体偏移");
        assertTrue(source.contains("Math.max(.02f"),
                "下界必须留住：系数为 0 时 alpha 永远到不了 0.02，播放器就再也关不掉");
        assertTrue(source.contains("float alphaInterpolateSpeed = 0.4f"),
                "页面切换的交叉淡入必须仍是写死的 0.4，不受这个配置影响");
        assertTrue(source.contains("interpolate(this.animatedPanelScale, targetScale, .18f)"),
                "窗口尺寸档位的动画必须仍是写死的 .18，不受这个配置影响");
    }

    @Test
    @DisplayName("两套设置界面都能调到它")
    void 两套界面都有入口() throws IOException {
        assertTrue(stripComments(read(SETTINGS_MODEL)).contains("slider(HudSetting.PLAYER_FADE_SPEED"),
                "现代设置界面（HudSettingsModel 的声明）里没有这一项");
        assertTrue(stripComments(read(CLASSIC_EDITOR)).contains("HudSetting.PLAYER_FADE_SPEED"),
                "经典设置界面（GuiHudEditor 的滑块清单）里没有这一项");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** 注释里会提到别的动画参数作为说明，扫描前先去掉，免得把说明当成引用。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
