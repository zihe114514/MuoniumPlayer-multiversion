package com.muoniumplayer.core.ncm.music;

/**
 * 智能混音 transition families. This is a clean-room implementation of the strategy layer
 * documented by 本项目: a cut, a low-band handover, a decaying-tail ride, and a neutral blend.
 */
enum FoliaTransitionStyle {
    BEAT_CUT("beatCut", 0.0),
    BASS_SWAP("bassSwap", 0.55),
    TAIL_RIDE("tailRide", 0.50),
    PLAIN_BLEND("plainBlend", 0.0);

    final String id;
    /** Fraction of the ramp held with both decks at the same level. */
    final double together;

    FoliaTransitionStyle(String id, double together) {
        this.id = id;
        this.together = together;
    }

    boolean usesBassSwap() {
        return this == BASS_SWAP || this == TAIL_RIDE;
    }

    boolean usesEchoThrow() {
        return this == BEAT_CUT;
    }
}
