package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.viper.dsp.WavReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置内核/预设的铺设与内容校验（无头）：铺进临时目录后逐个解析预设、走真实映射链核对
 * 卷积器参数，再按 {@code kernelFile} 读回 WAV——把「面板里能选中 → 引擎能装载」这条链
 * 在无头环境下走通。
 */
class ViperBuiltinTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("首次铺设：内核与预设全部落盘、版本戳就位；同版本重复调用不再复制")
    void provisionOnce() {
        File viperDir = temp.toFile();
        int copied = ViperBuiltin.provision(viperDir, ViperBuiltin.BUNDLE_VERSION);
        assertEquals(ViperBuiltin.KERNELS.length + ViperBuiltin.PRESETS.length, copied, "首次应全量复制");

        for (String name : ViperBuiltin.KERNELS) {
            assertTrue(new File(new File(viperDir, "kernels"), name).isFile(), "内核缺失：" + name);
        }
        for (String name : ViperBuiltin.PRESETS) {
            assertTrue(new File(new File(viperDir, "presets"), name).isFile(), "预设缺失：" + name);
        }
        assertTrue(new File(viperDir, ViperBuiltin.BUNDLE_MARKER).isFile(), "版本戳应写入");

        assertEquals(0, ViperBuiltin.provision(viperDir, ViperBuiltin.BUNDLE_VERSION), "同版本重复调用不复制");
    }

    @Test
    @DisplayName("删除的内置项不复活；版本升级时只补缺、不覆盖用户改过的文件")
    void deletionRespectedUntilVersionBump() throws Exception {
        File viperDir = temp.toFile();
        ViperBuiltin.provision(viperDir, ViperBuiltin.BUNDLE_VERSION);

        File victim = new File(new File(viperDir, "presets"), ViperBuiltin.PRESETS[0]);
        assertTrue(victim.delete());
        assertEquals(0, ViperBuiltin.provision(viperDir, ViperBuiltin.BUNDLE_VERSION), "同版本不补回已删项");

        File kept = new File(new File(viperDir, "kernels"), ViperBuiltin.KERNELS[0]);
        byte[] marker = "user-edited".getBytes(StandardCharsets.UTF_8);
        Files.write(kept.toPath(), marker);

        int copied = ViperBuiltin.provision(viperDir, ViperBuiltin.BUNDLE_VERSION + 1);
        assertTrue(victim.isFile(), "版本升级补回缺失文件");
        assertEquals(1, copied, "只补缺的那一个");
        assertArrayEquals(marker, Files.readAllBytes(kept.toPath()), "已有文件不得被覆盖");
    }

    @Test
    @DisplayName("每个内置预设：可按 2.1 解析、走映射链后卷积器启用且 kernelFile 指向可读 WAV")
    void presetsDescribeReadableKernels() throws Exception {
        File viperDir = temp.toFile();
        ViperBuiltin.provision(viperDir, ViperBuiltin.BUNDLE_VERSION);
        File kernelDir = new File(viperDir, "kernels");

        assertEquals(ViperBuiltin.KERNELS.length, ViperBuiltin.PRESETS.length, "内核与预设一一对应");
        for (String presetFile : ViperBuiltin.PRESETS) {
            File file = new File(new File(viperDir, "presets"), presetFile);
            String base = presetFile.substring(0, presetFile.length() - ".json".length());
            ViperPreset preset = ViperPresetParser.parse(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), base);

            ViperParamMapper.Mapped mapped = ViperParamMapper.map(preset);
            assertTrue(mapped.params.convolver.enable, "映射后卷积器应启用：" + presetFile);
            String kernelFile = mapped.convolverKernelFile;
            assertFalse(kernelFile == null || kernelFile.isEmpty(), "kernelFile 不应为空：" + presetFile);

            File kernel = new File(kernelDir, kernelFile);
            assertTrue(kernel.isFile(), "内核文件应对得上：" + kernelFile);

            WavReader.WavData wav = new WavReader.WavData();
            assertTrue(WavReader.readWavFile(kernel.getAbsolutePath(), wav), "WAV 应可读：" + kernelFile);
            assertTrue(wav.frameCount >= 16, "内核帧数过短：" + kernelFile);
            assertTrue(wav.channels == 1 || wav.channels == 2 || wav.channels == 4,
                    "内核声道数不受支持：" + kernelFile);
        }
    }
}
