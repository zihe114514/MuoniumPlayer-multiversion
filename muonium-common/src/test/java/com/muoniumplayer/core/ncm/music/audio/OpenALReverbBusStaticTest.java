package com.muoniumplayer.core.ncm.music.audio;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EAX 混响总线（{@code OpenALReverbBus}）的绑定纪律。
 *
 * <p>2026-11-04 用户实机复盘钉出来的形状：2.4.0 的 init 在建完 loopback 上下文后走
 * <b>进程级</b> {@code alcMakeContextCurrent} 绑定（还先清 0），任何一步失败都不恢复 ——
 * 而 MC 自己的 Sound engine 线程正是靠那个进程级指针拿当前上下文。结果一首歌放完
 * 游戏全哑（实机日志 1154 条「Allocate new source: Invalid operation」），主界面
 * Watermedia 的视频背景同因建不出 ALEngine 而黑掉。线程局部绑定
 * （ALC_EXT_thread_local_context 的 alcSetThreadContext）是唯一安全路线，
 * OpenALAudioDevice 的类注释与静态测试早有前车之鉴。</p>
 */
class OpenALReverbBusStaticTest {

    @Test
    @DisplayName("代码里绝不许出现进程级 alcMakeContextCurrent —— MC 声音线程靠它活")
    void noProcessWideContextBinding() throws IOException {
        String bus = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALReverbBus.java"));
        // 剥掉注释再查：类文档里留着「踩过什么坑」的记述不算违规。
        String code = bus.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        assertFalse(code.contains("alcMakeContextCurrent"),
                "总线只许走 alcSetThreadContext 线程局部绑定："
                        + "alcMakeContextCurrent 是进程级全局指针，会清/抢 MC Sound engine 线程的上下文"
                        + "（2026-11-04 实机：游戏全哑 + 主界面视频背景丢失）");
    }

    @Test
    @DisplayName("每个 AL 入口（含 render）必须过 bind 闸、finally 里 unbind")
    void everyEntryBindsAndUnbinds() throws IOException {
        String bus = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALReverbBus.java"));
        for (String entry : new String[] {"setSendGain(", "setSourcePose(", "setReverbParams(",
                "setListener(", "setSourceRolloff(", "setSidePose(", "setSideGain(",
                "render(short[]"}) {
            assertTrue(bus.contains(entry), "入口还在: " + entry);
        }
        assertTrue(bus.contains("broken || !bind()"),
                "所有公开入口都要过 bind() 闸：线程局部上下文不绑就调 AL 是无定义行为");
        assertTrue(bus.contains("unbind();"),
                "AL 调用完成后必须 unbind：同一根 JSyn 音频线程还要继续喂主输出");
    }

    @Test
    @DisplayName("能力表必须在线程绑定之后解析：AL.createCapabilities 需要当前上下文")
    void capabilitiesAfterThreadBinding() throws IOException {
        String bus = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALReverbBus.java"));
        int setThread = bus.indexOf("EXTThreadLocalContext.alcSetThreadContext(context)");
        int alCaps = bus.indexOf("AL.createCapabilities(alcCaps)");
        assertTrue(setThread >= 0 && alCaps > setThread,
                "AL.createCapabilities 要排在 alcSetThreadContext 之后："
                        + "函数指针解析要求本线程已有当前上下文（2.4.0 就是先解指针后绑定才炸的）");
        assertTrue(bus.contains("ALC_EXT_thread_local_context"),
                "缺线程局部扩展必须放弃总线，绝不退回进程级绑定");
    }

    @Test
    @DisplayName("失败路径必须释放设备与上下文：半开的 loopback 设备会被反复探测")
    void failurePathReleasesDevice() throws IOException {
        String bus = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALReverbBus.java"));
        for (String marker : new String[] {"releaseDevice()", "alcDestroyContext", "alcCloseDevice"}) {
            assertTrue(bus.contains(marker), "要有清理动作: " + marker);
        }
        int okReset = bus.indexOf("if (!ok) {");
        assertTrue(okReset >= 0 && bus.indexOf("releaseDevice();", okReset) > okReset,
                "init 失败要走 finally 的 releaseDevice");
    }

    @Test
    @DisplayName("总线要有急停开关：-Dmuonium.acoustics.eax=false 一键退自研链")
    void busHasKillSwitch() throws IOException {
        String bus = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALReverbBus.java"));
        assertTrue(bus.contains("muonium.acoustics.eax"),
                "要认 -Dmuonium.acoustics.eax：哪台机器上总线另有古怪，一键退回自研湿声链不用等发版");
        int gate = bus.indexOf("muonium.acoustics.eax");
        int init = bus.indexOf("bus.init(");
        assertTrue(gate >= 0 && init > gate, "开关检查要在 bus.init 之前");
    }

    private static String read(Path relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(relative);
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
