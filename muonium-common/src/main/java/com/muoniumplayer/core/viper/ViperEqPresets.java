package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.viper.dsp.MinPhaseIIRCoeffs;

import java.util.List;

/**
 * 本工作台自拟的温和 EQ 参数起点，不是任何厂商的官方数值或设备校准。
 * 基础频段直接来自本引擎的十段表；参数折线不是并联滤组的精确频响。
 */
public final class ViperEqPresets {

    public static final class Preset {
        public final String id;
        public final String name;
        public final String description;
        private final double[] gains;

        private Preset(String id, String name, String description, double... gains) {
            this.id = id;
            this.name = name;
            this.description = description;
            if (gains.length != MinPhaseIIRCoeffs.frequenciesForBandCount(10).length) {
                throw new IllegalArgumentException("EQ 起始预设必须使用引擎十段表");
            }
            for (double gain : gains) {
                if (!Double.isFinite(gain) || gain < -12.0 || gain > 5.0) {
                    throw new IllegalArgumentException("EQ 起始预设增益越界");
                }
            }
            this.gains = gains.clone();
        }

        public double[] gains() {
            return gains.clone();
        }
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset("flat", "平直", "所有频段归零，作为手动调节的起点。",
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new Preset("bass", "低音", "温和增强低频，稍收中频，避免过度堆叠。",
                    4, 3.5, 2.5, 1, 0, -0.5, -0.5, 0, 0.5, 0),
            new Preset("vocal", "人声", "略收低频，适度突出人声所在的中频参数。",
                    -1, -0.5, -0.5, 0, 1, 2, 2.5, 1.5, 0.5, 0),
            new Preset("pop", "流行", "轻微增强低频和中高频，保持整体温和。",
                    1.5, 1.5, 0, -0.5, 0.5, 1.5, 2, 1.5, 1, 0.5),
            new Preset("rock", "摇滚", "增强低频节奏与中高频，轻收偏厚的中低频。",
                    3, 2.5, 1, -0.5, -1, 0.5, 2, 2.5, 2, 1),
            new Preset("jazz", "爵士", "小幅增加低频与高频细节，保留中频空间。",
                    1, 1, 0.5, 0, -0.5, 0, 1, 1.5, 1.5, 0.5),
            new Preset("classical", "古典", "较小的参数偏移，轻微增加高频的开阔感。",
                    0, 0.5, 0.5, 0, -0.5, -0.5, 0, 1, 1.5, 1),
            new Preset("electronic", "电子", "适度强调低频与高频，轻收中频参数。",
                    4, 3.5, 1.5, 0, -1, -0.5, 1, 2, 3, 2));

    private ViperEqPresets() {
    }

    /** 不可变列表，预设增益通过防御副本读取。 */
    public static List<Preset> all() {
        return PRESETS;
    }

    public static double[] baseFrequencies() {
        return MinPhaseIIRCoeffs.frequenciesForBandCount(10);
    }
}
