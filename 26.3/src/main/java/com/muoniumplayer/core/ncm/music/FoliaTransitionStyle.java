package com.muoniumplayer.core.ncm.music;

/**
 * 智能混音 过渡族。这是对 本项目 所记载策略层的洁净室
 * 实现：硬切、低频交接、衰减尾滑行、中性混音。
 */
enum FoliaTransitionStyle {
    BEAT_CUT("beatCut", 0.0),
    BASS_SWAP("bassSwap", 0.55),
    TAIL_RIDE("tailRide", 0.50),
    PLAIN_BLEND("plainBlend", 0.0);

    final String id;
    /** 两个 deck 同电平保持的渐变占比。 */
    final double together;

    FoliaTransitionStyle(String id, double together) {
        this.id = id;
        this.together = together;
    }

    boolean usesBassSwap() {
        return this == BASS_SWAP || this == TAIL_RIDE;
    }

}
