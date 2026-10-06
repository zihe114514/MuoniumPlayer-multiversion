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
 * 安卓音频兜底（OpenAL 声音线程泵）的跨文件不变量。
 *
 * <p>真机两轮实测钉出来的形状：FCL 的 libopenal 不是完整语义的 OpenAL Soft——
 * 第一轮，第二个 ALC 设备让 {@code alBufferData → LookupBuffer} 解引用空指针、
 * 整个游戏 SIGSEGV（hs_err_pid7862：崩在我们自己的 flushOne 里）；所以唯一的
 * 安全路线是<b>零新设备</b>：一切 AL 调用经 Executor 提交到 MC 自己的声音线程上，
 * 用的就是 MC 一直在用的那个上下文。这条链上有四件事错了不在编译期报错，
 * 只会变成「游戏正常但不出声」或者又是一次崩游戏，全部钉住。
 */
class OpenAlAudioDeviceStaticTest {

    @Test
    @DisplayName("兜底必须赶在第一个 SoundFile 之前：那一下就会触发 Engine 的设备选择")
    void installRunsBeforeFirstSoundFile() throws IOException {
        String player = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AudioPlayer.java"));
        assertTrue(player.contains("installAudioDeviceFallbackIfMissing()"),
                "loadUsableSoundFile 里要调用兜底安装");
        int install = player.indexOf("installAudioDeviceFallbackIfMissing();");
        // 只认真正的构造点，别被 javadoc 里的 {@code new SoundFile(...)} 骗到
        int firstSoundFile = player.indexOf("new SoundFile(file.getAbsolutePath())");
        assertTrue(install >= 0 && firstSoundFile > install,
                "安装调用必须出现在 new SoundFile( 之前：SoundFile 构造就会创建 Engine 单例，"
                        + "设备选择只有一次机会");
        assertTrue(player.contains("audioDeviceFallbackChecked"),
                "探测要缓存住：每首歌都跑一遍 getMixerInfo 是白费");
    }

    @Test
    @DisplayName("有可用 Mixer 时绝不抢设备：桌面与 MioJSound 走原路")
    void fallbackOnlyWhenJavaSoundIsDead() throws IOException {
        String player = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AudioPlayer.java"));
        // 安卓必须先看平台、再决定要不要碰 javax.sound：那次构造会触发 AudioSystem 的
        // Mixer 扫描，把 MioJSound 一类桥唤醒，它们与游戏声音引擎抢进程级上下文，
        // 游戏声音引擎立刻变成「Invalid name parameter」刷屏（2026-10-06 Zalith 真机）。
        int androidGate = player.indexOf("mappedPlatform().startsWith(\"android\")");
        int javaSoundCtor = player.indexOf("new JavaSoundAudioDevice()");
        assertTrue(androidGate >= 0 && javaSoundCtor > androidGate,
                "安卓分支必须挡在 JavaSoundAudioDevice 构造之前：那次构造会唤醒 MioJSound 一类桥");
        assertTrue(player.contains("getDefaultOutputDeviceID() != -1"),
                "桌面仍要问 JavaSound 有没有输出设备：有就走原路，绝不抢");
        assertTrue(player.contains("AudioDeviceFactory.setInstance("),
                "注入点必须是 setInstance：那是 JSyn 留的正门，不许改 repackaged 文件抄近路");
        String factory = read(Paths.get("repackage", "com", "jsyn", "devices",
                "AudioDeviceFactory.java"));
        assertFalse(factory.contains("OpenALAudioDevice"),
                "repackage 层不许反向依赖 mod 代码：分层一旦打通，两棵源码树就再也拆不开了");
    }

    @Test
    @DisplayName("第二设备可以开，但绑定必须走线程局部上下文并配齐 capabilities")
    void bindingMustBeThreadLocal() throws IOException {
        // hs_err_pid7862 + modernMP 的对照结论：这类启动器的 alcMakeContextCurrent 是
        // 进程级全局指针，MC 的声音线程会把它抢回去；我们的 alBufferData 于是落在
        // MC 的上下文里查我们刚生成的缓冲 → LookupBuffer 空指针 → SIGSEGV。
        // modernMP 在同一台手机上验证过的解法：ALC_EXT_thread_local_context 的
        // alcSetThreadContext + 每次使用前重绑。
        String device = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALAudioDevice.java"));
        assertTrue(device.contains("ALC_EXT_thread_local_context")
                        && device.contains("alcSetThreadContext"),
                "优先用线程局部上下文绑定：alcMakeContextCurrent 的进程级指针会被 MC 抢走");
        assertTrue(device.contains("ALC.createCapabilities")
                        && device.contains("AL.createCapabilities"),
                "LWJGL 的规定动作两条都要做，漏 AL.createCapabilities 是巧合能跑不是契约");
        assertTrue(device.contains("bindAlContext();"),
                "每次使用前都要重绑（start/write/stop 全部），把被抢走的窗口压到最小");
        assertFalse(device.contains("import javax.sound"),
                "设备不许依赖 javax.sound：它要修的正是这套后端缺失的平台");
    }

    /** 剥掉注释再查源码：类文档里留着「踩过什么坑」的记述不算违规。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("write 的阻塞就是 JSyn 的时钟：节拍与 PCM 公式都必须对齐 JavaSound")
    void writeSemanticsMatchJavaSound() throws IOException {
        String device = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALAudioDevice.java"));
        assertTrue(device.contains("Thread.sleep(2)"),
                "队列满时 write 必须阻塞：这是合成线程的时钟，丢掉它 JSyn 会极速空转");
        String javaSound = read(Paths.get("repackage", "com", "jsyn", "devices", "javasound",
                "JavaSoundAudioDevice.java"));
        String formula = "(32767.0 * buffer[i + start]) + 32768.5";
        assertTrue(device.contains(formula), "PCM16 转换必须与 JavaSound 的公式逐字一致");
        assertTrue(javaSound.contains(formula), "基准公式被人改了的话，两条设备路径听感会分叉");
        assertTrue(device.contains("alSourcePlay"),
                "起播与欠载重启都要显式 play：OpenAL 不会因为 queue 了缓冲就自动出声");
        assertTrue(device.contains("UnsupportedOperationException"),
                "录音未实现就要明说，别留一个静默吐垃圾的输入流");
    }

    @Test
    @DisplayName("第一个缓冲不必等任何人：空闲池空了才允许进入「等已消费」循环")
    void firstFlushNeverWaitsForConsumption() throws IOException {
        // 2026-10-06 真机翻的车：取缓冲写成「先等已消费再回收」，第一个缓冲进队之前
        // AL_BUFFERS_PROCESSED 永远是 0，等满超时 → 丢弃 → 再等，音乐永远不出声、
        // 合成线程永远卡在等待里，进度条不动。
        String device = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALAudioDevice.java"));
        int poolCheck = device.indexOf("if (freeCount == 0)");
        int waitLoop = device.indexOf("AL_BUFFERS_PROCESSED");
        assertTrue(poolCheck >= 0 && waitLoop > poolCheck,
                "泵必须先从空闲池取缓冲，池空才等消费："
                        + "等待循环放在取缓冲之前就是首缓冲死锁");
        assertTrue(device.contains("freeBuffers[--freeCount]"),
                "空闲缓冲必须先进池再出队，不许绕过池直接 recycle");
        assertTrue(device.contains("queuedBuffers >= 2"),
                "起播要攒到两个缓冲（≈93ms）再 play，第一口就欠载会有杂音");
    }

    @Test
    @DisplayName("AL 侧救不回来时 write 要硬闸掉，环形缓冲不能无限增长")
    void brokenStopsAcceptingSamples() throws IOException {
        String device = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "audio",
                "OpenALAudioDevice.java"));
        assertTrue(device.contains("private boolean broken;"), "要有 broken 闸");
        assertTrue(device.contains("if (broken || count <= 0)"),
                "write 入口要检查 broken：AL 侧死了之后引擎线程继续喂只会白耗 CPU");
    }

    @Test
    @DisplayName("兜底要有急停开关：默认开，但一键能退回无音频的原有失败")
    void fallbackHasKillSwitch() throws IOException {
        String player = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AudioPlayer.java"));
        assertTrue(player.contains("muonium.audio.openal"),
                "要认 -Dmuonium.audio.openal：哪台机器上泵另有古怪，一键退回原有失败不用等发版");
        int gate = player.indexOf("muonium.audio.openal");
        int install = player.indexOf("OpenALAudioDevice.createIfAvailable()");
        assertTrue(gate >= 0 && install > gate, "开关检查要在创建设备之前");
    }

    private static String read(Path relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(relative);
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
