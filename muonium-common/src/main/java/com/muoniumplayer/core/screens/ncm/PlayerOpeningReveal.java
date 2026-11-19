package com.muoniumplayer.core.screens.ncm;

/** Ready sections join the opening independently, on its bounded frame clock. */
final class PlayerOpeningReveal {
    private static final double REVEAL_MILLIS = 80.0;
    private double base, controls, page;
    private boolean baseReady, controlsReady, pageReady;

    void reset(int layoutStage) {
        baseReady = controlsReady = layoutStage == 0 || layoutStage >= 5;
        pageReady = layoutStage == 0;
        base = baseReady ? 1.0 : 0.0;
        controls = controlsReady ? 1.0 : 0.0;
        page = pageReady ? 1.0 : 0.0;
    }

    void advance(int layoutStage, double deltaMillis) {
        double step = Math.max(0.0, Math.min(80.0, deltaMillis)) / REVEAL_MILLIS;
        boolean hasBase = layoutStage == 0 || layoutStage >= 3;
        boolean hasControls = layoutStage == 0 || layoutStage >= 4;
        boolean hasPage = layoutStage == 0;
        // The first frame of a newly attached section starts at zero, not half opaque
        // because the previous frame spent time constructing it.
        if (baseReady) base = Math.min(1.0, base + step);
        if (controlsReady) controls = Math.min(1.0, controls + step);
        if (pageReady) page = Math.min(1.0, page + step);
        baseReady |= hasBase;
        controlsReady |= hasControls;
        pageReady |= hasPage;
    }

    float baseAlpha() {
        return eased(base);
    }

    float controlsAlpha() {
        return eased(controls);
    }

    float pageAlpha() {
        return eased(page);
    }

    boolean isComplete() {
        return base == 1.0 && controls == 1.0 && page == 1.0;
    }

    private static float eased(double t) {
        return (float) (t * t * t * (10.0 + t * (-15.0 + 6.0 * t)));
    }
}
