package com.muoniumplayer.core.viper;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接线回归：这些断言保护的是「一次顺手重构把 ViPER 从音频链上摘掉」这类事故 ——
 * 只读源码串，不启动游戏、不碰音频设备。
 */
class ViperIntegrationStaticTest {

    private static String shared(String relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("合成引擎在写设备前、观察者前调用输出处理器")
    void engineInvokesProcessorBeforeObserver() throws IOException {
        String source = shared("repackage/com/jsyn/engine/SynthesisEngine.java");
        assertTrue(source.contains("public interface OutputProcessor"), "可写钩子接口");
        assertTrue(source.contains("processOutput();\n        observeOutput();"),
                "处理器必须排在观察者之前（频谱看到的应是处理后音频）");
        assertTrue(source.contains("setOutputProcessor"), "引擎必须暴露挂接入口");
    }

    @Test
    @DisplayName("Engine 在换音频驱动后重新挂接处理器")
    void engineReappliesProcessorOnDriverSwitch() throws IOException {
        String source = shared("repackage/processing/sound/Engine.java");
        assertTrue(source.contains("setOutputProcessor(outputProcessor);"),
                "createSynth 重建合成器后必须补挂");
    }

    @Test
    @DisplayName("播放器装载 deck 时挂接 ViPER 运行时")
    void playerInstallsRuntime() throws IOException {
        String source = shared("com/muoniumplayer/core/ncm/music/AudioPlayer.java");
        assertTrue(source.contains("ViperRuntime.ensureInstalled();"), "出声前的挂接点");
    }

    @Test
    @DisplayName("配置路径暴露 ViPER 目录")
    void configPathsExposeViper() throws IOException {
        String source = shared("com/muoniumplayer/core/settings/ConfigPaths.java");
        assertTrue(source.contains("VIPER_DIR"));
        assertTrue(source.contains("VIPER_SETTINGS"));
        assertTrue(source.contains("VIPER_PRESETS"));
        assertTrue(source.contains("\"viper\""));
    }

    @Test
    @DisplayName("主限幅器常量与上游一致（前瞻 256 / 80ms / 44100 固定系数），且处理器驱动全链引擎")
    void processorConstantsMatchUpstream() throws IOException {
        String limiter = shared("com/muoniumplayer/core/viper/dsp/SoftwareLimiter.java");
        assertTrue(limiter.contains("K_LOOKAHEAD = 256"), "前瞻窗口 256");
        assertTrue(limiter.contains("K_RELEASE_TAU_SEC = 0.080f"), "80ms 释放");
        assertTrue(limiter.contains("DEFAULT_SAMPLING_RATE = 44100"), "释放系数固定按 44100 计算（上游行为）");

        String processor = shared("com/muoniumplayer/core/viper/ViperMasterProcessor.java");
        assertTrue(processor.contains("new ViPER()"), "处理器持有全链引擎");
        assertTrue(processor.contains("engine.process(buffer, frames)"), "音频线程驱动引擎 Process 链");
        assertTrue(processor.contains("engine.applySnapshot(next.params)"),
                "重载时全量快照下发（对应上游 DspGraph::Prepare → ApplySnapshot 的无条件语义）");

        String engine = shared("com/muoniumplayer/core/viper/dsp/ViPER.java");
        assertTrue(engine.contains("public void applySnapshot(ViPERParams params)"), "引擎提供快照全量入口");
        assertTrue(engine.contains("public void applyParams(ViPERParams params)"), "增量 diff 通道保留");
    }

    @Test
    @DisplayName("蝰蛇音效面板接线：播放条入口、NCMScreen 六处接入、面板动作全走运行时")
    void viperPanelWiring() throws IOException {
        String screen = shared("com/muoniumplayer/core/screens/ncm/NCMScreen.java");
        assertTrue(screen.contains("public ViperOverlay viperOverlay = null;"), "面板字段");
        assertTrue(screen.contains("public void openViperEffects()"), "打开入口");
        assertTrue(screen.contains("this.viperOverlay.renderWidget(mouseX, mouseY, dWheel);"), "渲染接入");
        assertTrue(screen.contains("this.viperOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);"), "点击接入");
        assertTrue(screen.contains("this.viperOverlay.onKeyTypedReceived(typedChar, keyCode)"), "键盘接入");
        assertTrue(screen.contains("this.viperOverlay.handleEscape();"), "ESC 接入");
        assertTrue(screen.contains("|| this.viperOverlay != null"), "模态判断");

        String bar = shared("com/muoniumplayer/core/screens/ncm/panels/ControlsBar.java");
        assertTrue(bar.contains("NCMScreen.getInstance().openViperEffects();"), "播放条入口指向面板");

        String overlay = shared("com/muoniumplayer/core/screens/ncm/ViperOverlay.java");
        assertTrue(overlay.contains("extends NcmSheet"), "面板沿用共用表现层");
        assertTrue(overlay.contains("ViperRuntime.selectPreset("), "选用走运行时");
        assertTrue(overlay.contains("ViperRuntime.setEnabled("), "总开关走运行时");
        assertTrue(overlay.contains("ViperRuntime.importPreset("), "导入走运行时");
        assertTrue(overlay.contains("ViperRuntime.removePreset("), "删除走运行时");
        assertTrue(overlay.contains("ViperRuntime.syncFromDisk()"), "开面板先对齐磁盘状态（尚未装载曲目时也显示真实开关）");

        String runtime = shared("com/muoniumplayer/core/viper/ViperRuntime.java");
        assertTrue(runtime.contains("ViperBuiltin.ensureProvisioned()"), "运行时必须铺设随包内置内核/预设");
        assertEquals(2, runtime.split("provisionBuiltins\\(\\);", -1).length - 1, "装载与面板同步两条路径都要铺设");
    }
}
