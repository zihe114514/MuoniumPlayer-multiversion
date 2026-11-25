package com.muoniumplayer.core.ncm.music.audio;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring boundaries; queue, conversion and native ownership also have behavior tests. */
class OpenAlAudioDeviceStaticTest {
    @Test void installsSoleBackendBeforeFirstSoundFile() throws IOException {
        String player = read("com/muoniumplayer/core/ncm/music/AudioPlayer.java");
        int install = player.indexOf("installAudioDeviceFallbackIfMissing();");
        int construct = player.indexOf("new SoundFile(file.getAbsolutePath())");
        assertTrue(install >= 0 && construct > install);
        assertTrue(player.contains("AudioDeviceFactory.setInstance(OpenALAudioDevice.createIfAvailable())"));
        assertFalse(player.contains("new JavaSoundAudioDevice("));
        assertFalse(player.contains("mappedPlatform().startsWith(\"android\")"));
        assertFalse(read("repackage/com/jsyn/devices/AudioDeviceFactory.java").contains("OpenALAudioDevice"),
                "The repackaged library must not import mod classes");
    }

    @Test void earlyEffectAndObserverEntrypointsInstallTheSameBackend() throws IOException {
        for (String file : new String[]{"com/muoniumplayer/core/viper/ViperRuntime.java",
                "com/muoniumplayer/core/ncm/music/EngineOutputTap.java",
                "com/muoniumplayer/core/ncm/music/acoustics/AcousticsRuntime.java"}) {
            String source = read(file);
            int ensure = source.indexOf("AudioPlayer.ensureAudioBackend();");
            int engine = source.indexOf("Engine.getEngine()", ensure);
            assertTrue(ensure >= 0 && engine > ensure, file);
        }
        assertFalse(read("repackage/processing/sound/Engine.java")
                .contains("Class.forName(\"javax.sound.sampled.AudioSystem\")"));
    }

    @Test void preservesExistingDspInstallationAndFinalObserverOrder() throws IOException {
        String player = read("com/muoniumplayer/core/ncm/music/AudioPlayer.java");
        int start = player.indexOf("private static SoundFile loadUsableSoundFile");
        int install = player.indexOf("installAudioDeviceFallbackIfMissing();", start);
        int viper = player.indexOf("ViperRuntime.ensureInstalled();", start);
        int acoustics = player.indexOf("AcousticsRuntime.ensureInstalled();", start);
        int voice = player.indexOf("VoicechatBroadcast.ensureInstalled();", start);
        int file = player.indexOf("new SoundFile(file.getAbsolutePath())", start);
        assertTrue(start >= 0 && install > start && viper > install && acoustics > viper
                && voice > acoustics && file > voice);
        String engine = read("repackage/com/jsyn/engine/SynthesisEngine.java");
        int render = engine.indexOf("public void generateNextBuffer()");
        int process = engine.indexOf("processOutput();", render);
        int observe = engine.indexOf("observeOutput();", render);
        assertTrue(process > render && observe > process,
                "Output capture remains after final DSP, before the device consumes the buffer");
    }

    @Test void refusesProcessContextFallbackAndRestoresNativeTlsAndCapabilities() throws IOException {
        String output = read("com/muoniumplayer/core/audio/output/OpenAlOutputDriver.java");
        assertFalse(output.contains("alcMakeContextCurrent("));
        assertFalse(output.contains("AL.setCurrentProcess("));
        assertTrue(output.contains("if (!alc.ALC_EXT_thread_local_context)"));
        int verified = output.indexOf("alcGetThreadContext() != context");
        int create = output.indexOf("AL.createCapabilities(alc)");
        assertTrue(verified >= 0 && create > verified);
        assertTrue(output.contains("alcSetThreadContext(previousContext)"));
        assertTrue(output.contains("ALC.setCapabilities(previousAlc)"));
        assertTrue(output.contains("AL.setCurrentThread(restored && previousContext != 0 ? previousAl : null)"));
    }

    @Test void outputIsNonSpatialAndProbesActualFormats() throws IOException {
        String output = read("com/muoniumplayer/core/audio/output/OpenAlOutputDriver.java");
        assertTrue(output.contains("SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_FALSE"));
        assertTrue(output.contains("channels == 1 && !al.AL_SOFT_source_spatialize ? 2 : channels"));
        assertTrue(output.contains("al.AL_EXT_MCFORMATS"));
        assertTrue(output.contains("al.AL_EXT_FLOAT32"));
        assertTrue(output.contains("AL10.alGetBufferi(probe, AL10.AL_CHANNELS)"));
        assertTrue(output.contains("AL10.alGetBufferi(probe, AL10.AL_BITS)"));
        assertTrue(output.contains("float32 = capabilities.supportsFloat32(outputChannels)"));
    }

    @Test void closeDeletesOwnedNativeObjectsAndAdapterStopActuallyCloses() throws IOException {
        String output = read("com/muoniumplayer/core/audio/output/OpenAlOutputDriver.java");
        for (String call : new String[] {"AL10.alSourceStop(source)", "AL10.alSourceUnqueueBuffers(source)",
                "AL10.alDeleteSources(source)", "AL10.alDeleteBuffers(buffers[i])",
                "ALC10.alcDestroyContext(context)", "ALC10.alcCloseDevice(device)", "MemoryUtil.memFree(upload)"}) {
            assertTrue(output.contains(call), call);
        }
        String adapter = read("com/muoniumplayer/core/ncm/music/audio/OpenALAudioDevice.java");
        assertTrue(adapter.contains("try { close(); }"));
        assertTrue(adapter.contains("if (current != null) current.close()"));
        assertTrue(adapter.contains("UnsupportedOperationException"));
    }

    @Test void noEngineOrDecoderDependencyAndNoInflatedJsynChannelClaim() throws IOException {
        String adapter = read("com/muoniumplayer/core/ncm/music/audio/OpenALAudioDevice.java");
        assertTrue(adapter.contains("getMaxOutputChannels(int id) { return 2; }"));
        String sink = read("com/muoniumplayer/core/audio/output/OpenAlPcmSink.java");
        for (String forbidden : new String[] {"javax.sound", "org.bytedeco", "repackage.",
                "net.minecraft", "EngineOutputTap", "FocusVolumeDucker"}) {
            assertFalse(sink.contains(forbidden), forbidden);
        }
        assertTrue(sink.contains("new float[RING_FRAMES * channels]"));
        assertTrue(sink.contains("new float[BLOCK_FRAMES * channels]"));
    }

    @Test void killSwitchFailsExplicitlyBeforeInstallingBackend() throws IOException {
        String player = read("com/muoniumplayer/core/ncm/music/AudioPlayer.java");
        int flag = player.indexOf("private static final String OPENAL_FLAG");
        int fail = player.indexOf("OpenAL music output is disabled", flag);
        int install = player.indexOf("AudioDeviceFactory.setInstance(OpenALAudioDevice.createIfAvailable())", flag);
        assertTrue(flag >= 0 && fail > flag && install > fail);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ProjectPaths.sharedJava().resolve(relative)).replace("\r\n", "\n");
    }
}
