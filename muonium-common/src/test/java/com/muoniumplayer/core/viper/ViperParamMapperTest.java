package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.viper.dsp.ViPERParams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViperParamMapper}：归一化预设（schema 2.1）→ {@link ViPERParams} 的映射回归。
 *
 * <p>钉住三类事实：① 官方默认值填充；② 官方量程钳位；③ 两处 setter 域 ×100 补偿
 * （StereoImager 宽度、PsychoacousticBass intensity/originalLevel——上游 setter 内部再除以 100，
 * 与官方旧版裸参数链路把它作为百分数下发一致）。</p>
 */
class ViperParamMapperTest {

    private static ViperParamMapper.Mapped map(String json) {
        return ViperParamMapper.map(ViperPresetParser.parse(json, "映射测试"));
    }

    @Test
    @DisplayName("空预设（仅 schemaVersion）→ 全部取官方默认值")
    void defaultsFromOfficialTables() {
        ViPERParams p = map("{\"schemaVersion\":2.1}").params;
        assertEquals(1.0f, p.masterLimiter.threshold, 1e-9f);
        assertEquals(1.0f, p.masterLimiter.outputVolume, 1e-9f);
        assertEquals(0.0f, p.masterLimiter.channelPan, 1e-9f);
        assertEquals(0.5f, p.bass.gain, 1e-9f);
        assertEquals(60, p.bass.frequency);
        assertEquals(10, p.equalizer.bandCount);
        assertEquals(7600, p.spectrumExtension.strength);
        assertEquals(3, p.dynamicEq.bandCount);
        assertEquals(5, p.multibandCompressor.bandCount);
        assertEquals(50, p.psychoacousticBass.intensity, "默认 intensity 0.5 → setter 域 50");
        assertEquals(100, p.psychoacousticBass.originalLevel, "默认 originalLevel 1.0 → setter 域 100");
    }

    @Test
    @DisplayName("StereoImager 宽度 ×100 补偿（1.25 → 125.0，与官方裸参数链路一致）")
    void stereoImagerWidthCompensation() {
        ViPERParams p = map("{\"schemaVersion\":2.1,\"stereoImager\":"
                + "{\"enable\":true,\"lowWidth\":0.8,\"midWidth\":1.25,\"highWidth\":2.0}}").params;
        assertEquals(80.0f, p.stereoImager.lowWidth, 1e-4f);
        assertEquals(125.0f, p.stereoImager.midWidth, 1e-4f);
        assertEquals(200.0f, p.stereoImager.highWidth, 1e-4f);
    }

    @Test
    @DisplayName("PsychoacousticBass 的 int 型参数按 setter 域换算并四舍五入")
    void psychoacousticIntCompensation() {
        ViPERParams p = map("{\"schemaVersion\":2.1,\"psychoacousticBass\":"
                + "{\"enable\":true,\"intensity\":0.37,\"originalLevel\":0.82}}").params;
        assertEquals(37, p.psychoacousticBass.intensity);
        assertEquals(82, p.psychoacousticBass.originalLevel);
    }

    @Test
    @DisplayName("量程钳位按官方范围（如 threshold 0.3..1.0、pan -1..1）")
    void clampsToOfficialRanges() {
        ViPERParams p = map("{\"schemaVersion\":2.1,\"masterLimiter\":"
                + "{\"threshold\":0.05,\"outputVolume\":9.0,\"channelPan\":-3.0}}").params;
        assertEquals(0.3f, p.masterLimiter.threshold, 1e-6f);
        assertEquals(2.0f, p.masterLimiter.outputVolume, 1e-6f);
        assertEquals(-1.0f, p.masterLimiter.channelPan, 1e-6f);
    }

    @Test
    @DisplayName("数组类字段逐元素映射（EQ 频段 / 多段压缩器 / 动态 EQ）")
    void arrayFields() {
        ViPERParams p = map("{\"schemaVersion\":2.1,"
                + "\"equalizer\":{\"enable\":true,\"bandCount\":10,\"bands\":[1,2,3,4,5,6,7,8,9,10]},"
                + "\"multibandCompressor\":{\"enable\":true,\"bandEnables\":[true,false,true,false,true],"
                + "\"crossovers\":[100,600,3000,9000],\"thresholds\":[-1,-2,-3,-4,-5]},"
                + "\"dynamicEq\":{\"enable\":true,\"bandCount\":2,\"freqs\":[80,240],"
                + "\"qs\":[1.0,2.0],\"gains\":[1.5,-1.5],\"thresholds\":[-10,-20],"
                + "\"attacks\":[5,15],\"releases\":[60,120],\"filterTypes\":[0,2]}}").params;

        assertEquals(10, p.equalizer.bandCount);
        assertEquals(1.0f, p.equalizer.bandLevels[0], 1e-6f);
        assertEquals(10.0f, p.equalizer.bandLevels[9], 1e-6f);
        assertEquals(0.0f, p.equalizer.bandLevels[10], 1e-6f);

        assertTrue(p.multibandCompressor.bands[0].enable);
        assertTrue(!p.multibandCompressor.bands[1].enable);
        assertTrue(p.multibandCompressor.bands[4].enable);
        assertEquals(100.0f, p.multibandCompressor.crossoverFrequencies[0], 1e-6f);
        assertEquals(9000.0f, p.multibandCompressor.crossoverFrequencies[3], 1e-6f);
        assertEquals(0.0f, p.multibandCompressor.crossoverFrequencies[4], 1e-6f, "第 5 个交叉频率缺省补 0");
        assertEquals(-1.0f, p.multibandCompressor.bands[0].threshold, 1e-6f);
        assertEquals(-5.0f, p.multibandCompressor.bands[4].threshold, 1e-6f);

        assertEquals(2, p.dynamicEq.bandCount);
        assertEquals(80.0f, p.dynamicEq.bands[0].frequency, 1e-6f);
        assertEquals(2.0f, p.dynamicEq.bands[1].q, 1e-6f);
        assertEquals(-1.5f, p.dynamicEq.bands[1].gain, 1e-6f);
        assertEquals(2, p.dynamicEq.bands[1].filterType);
        assertEquals(0.0f, p.dynamicEq.bands[2].frequency, 1e-6f, "第 3 段缺省为结构默认 0");
    }

    @Test
    @DisplayName("卷积核文件名与 DDC 设备名透传到资源通道")
    void resourceNamesPassThrough() {
        ViperParamMapper.Mapped m = map("{\"schemaVersion\":2.1,"
                + "\"convolver\":{\"enable\":true,\"kernelFile\":\"MyIRS.irs\"},"
                + "\"ddc\":{\"enable\":true,\"device\":\"MyHeadphone\"}}");
        assertEquals("MyIRS.irs", m.convolverKernelFile);
        assertEquals("MyHeadphone", m.ddcDevice);
        assertTrue(m.params.convolver.enable);
        assertTrue(m.params.ddc.enable);
    }

    @Test
    @DisplayName("旧编码（schema 2）经解析器换算后再映射（低音频率 +15）")
    void legacyEncodingIncluded() {
        ViPERParams p = map("{\"schemaVersion\":2.0,\"bass\":{\"enable\":true,\"frequency\":50,\"gain\":500}}").params;
        assertEquals(65, p.bass.frequency, "旧值 50 + 15 = 65");
        assertEquals(5.0f, p.bass.gain, 1e-4f, "旧值 500 / 100 = 5.0");
    }
}
