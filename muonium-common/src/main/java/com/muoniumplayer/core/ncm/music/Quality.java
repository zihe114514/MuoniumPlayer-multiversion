package com.muoniumplayer.core.ncm.music;

import lombok.Getter;

/**
 * @author MuoniumPlayer Contributors
 * @since 6/16/2023 10:03 AM
 */
public enum Quality {

    STANDARD("Standard"),
    HIGHER("Higher"),
    EXHIGH("ExHigh"),
    LOSSLESS("LossLess"),
    HIRES("HiRes"),
    JYEFFECT("JyEffect"),
    SKY("sky"),
    JYMASTER("JyMaster");

    @Getter
    private final String quality;

    Quality(String quality) {
        this.quality = quality;
    }

    /**
     * 界面上的档位短名。
     *
     * <p>音质菜单与播放条的音质标签共用这一份：两处各写一套词，用户就会看到「我选的是
     * 极高，标签上却写着 EXHIGH」这类对不上的说法。</p>
     */
    public String displayName() {
        switch (this) {
            case STANDARD: return "标准";
            case HIGHER: return "高品质";
            case EXHIGH: return "极高";
            case LOSSLESS: return "无损";
            case HIRES: return "Hi-Res";
            case JYEFFECT: return "高清环绕";
            case SKY: return "沉浸音质";
            case JYMASTER: return "母带";
            default: return quality;
        }
    }

    /** null 安全的档位短名；缺省按「无损」——与音质菜单的默认选中一致。 */
    public static String displayNameOf(Quality quality) {
        return quality == null ? LOSSLESS.displayName() : quality.displayName();
    }

    /** 英文界面的档位短名。与 {@link #displayName()} 一一对应，两处不许各写一套。 */
    public String displayNameEn() {
        switch (this) {
            case STANDARD: return "STANDARD";
            case HIGHER: return "HQ";
            case EXHIGH: return "EXHIGH";
            case LOSSLESS: return "LOSSLESS";
            case HIRES: return "HI-RES";
            case JYEFFECT: return "SPATIAL";
            case SKY: return "IMMERSIVE";
            case JYMASTER: return "MASTER";
            default: return quality;
        }
    }

    /** null 安全的英文档位短名；缺省按「无损」。 */
    public static String displayNameEnOf(Quality quality) {
        return quality == null ? LOSSLESS.displayNameEn() : quality.displayNameEn();
    }

}
