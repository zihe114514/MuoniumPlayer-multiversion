package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.muoniumplayer.testsupport.ProjectPaths;

/**
 * 播放页那根进度条必须<b>每帧</b>重取主题色。
 *
 * <p>这是个真实存在过的 bug：整块面板里 23 处取色，21 处写在 `setBeforeRenderCallback` 里，
 * 偏偏进度条的底色（BORDER）与填充（ACCENT）写在建控件那条链上 —— 于是开屏那一刻是什么色就
 * 永远是什么色。切主题不动、「封面跟随」开着换歌也不动，而同面板的音量条（`VolumeControl`
 * 在 render 里读）一直在跟着变，两根条并排放着，一个活一个死，玩家看到的就是「进度条不跟主题」。
 *
 * <p>静态断言的形状：不查颜色值（那是渲染期的事），只查<b>取色发生在哪里</b>。
 * 判据全部在压掉空白后的源码上做，缩进怎么改都不影响。
 */
class ControlsBarThemeFollowStaticTest {

    private static final Path CONTROLS_BAR = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "ControlsBar.java"));
    private static final Path VOLUME_CONTROL = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "VolumeControl.java"));

    @Test
    @DisplayName("进度条底色与填充的取色，都排在各自的每帧回调之后")
    void barColorsAreReadInsideRenderCallbacks() throws IOException {
        String src = flat(CONTROLS_BAR);

        int bgCallback = src.indexOf(".setShouldOverrideMouseCursor(true).setBeforeRenderCallback(()->{");
        assertTrue(bgCallback >= 0,
                "找不到进度条底色的每帧回调本体；结构被动过就要同步这个判据：" + CONTROLS_BAR);
        int bgColor = src.indexOf("progressBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))");
        assertTrue(bgColor > bgCallback,
                "底色取色没有落在每帧回调里面 —— 它就会停在开屏那一刻的主题色上");

        int fillCallback = src.indexOf(".setClickable(false).setBeforeRenderCallback(()->{");
        assertTrue(fillCallback >= 0, "找不到进度条填充的每帧回调本体");
        int fillColor = src.indexOf("progressBar.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))");
        assertTrue(fillColor > fillCallback,
                "强调色取色没有落在每帧回调里面 —— 这就是「进度条不跟随主题」的成因");
    }

    @Test
    @DisplayName("建控件那条链上不许再出现取色（旧的冻结写法必须绝迹）")
    void noColorReadLeftOnTheConstructionChain() throws IOException {
        String src = flat(CONTROLS_BAR);
        assertTrue(!src.contains(".setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER)).setRadius(1)"),
                "底色又回到了建控件时取一次色 —— 换成每帧回调里 setColor");
        assertTrue(!src.contains(".setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT)).setWidth(0)"),
                "填充又回到了建控件时取一次色 —— 换成每帧回调里 setColor");
    }

    @Test
    @DisplayName("填充取色排在镜像态那个 return 之前：没进度也得有颜色")
    void fillColorIsReadBeforeTheEarlyReturns() throws IOException {
        String src = flat(CONTROLS_BAR);
        int fillColor = src.indexOf("progressBar.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))");
        int mirrorReturn = src.indexOf("if(!CloudMusic.hasPlaybackClock())return");
        int noDuration = src.indexOf("if(total<=0.0f){progressBar.setWidth(0).setRadius(0);return;}");

        assertTrue(fillColor < mirrorReturn,
                "系统播放器镜像态一 return 就跳过了取色：镜像态下进度条照样要画，颜色也得跟主题");
        assertTrue(fillColor < noDuration,
                "总时长还没落地的那次 return 同样会跳过取色（起播头几帧就是这个状态）");
    }

    @Test
    @DisplayName("音量条仍是每帧取强调色 —— 两根条必须同源，别只改一边")
    void volumeBarStillReadsTheSameAccent() throws IOException {
        String src = new String(Files.readAllBytes(VOLUME_CONTROL), StandardCharsets.UTF_8);
        assertTrue(src.contains("NCMScreen.getColor(NCMScreen.ColorType.ACCENT)"),
                "音量条不再读强调色了；那进度条与它并排就会一活一死，这个对照关系要一起维护");
    }

    /** 压掉所有空白，让断言只依赖语句顺序而不依赖缩进与换行。 */
    private static String flat(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replaceAll("\\s+", "");
    }
}
