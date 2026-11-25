package com.muoniumplayer.core.screens.ncm;

import io.github.humbleui.types.Rect;

final class LyricBlurClip {
    private LyricBlurClip() {}

    static Rect layer(double viewportLeft, double viewportTop, double viewportRight, double viewportBottom,
                      double rowTop, double rowBottom, float pad) {
        float top = (float) Math.max(viewportTop - pad, rowTop - pad);
        float bottom = (float) Math.min(viewportBottom + pad, rowBottom + pad);
        // Pre-rendered rows may sit entirely outside the padded viewport. Skija rejects inverted bounds.
        if (top >= bottom) return null;
        return Rect.makeLTRB((float) viewportLeft - pad, top, (float) viewportRight + pad, bottom);
    }
}
