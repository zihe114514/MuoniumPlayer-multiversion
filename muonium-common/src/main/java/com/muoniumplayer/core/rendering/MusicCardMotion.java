package com.muoniumplayer.core.rendering;

/** 单一展开度派生分体、落位与内容层；反向和中途重开不切换几何公式。 */
final class MusicCardMotion {
    private MusicCardMotion() {
    }

    static boolean showCapsuleRow(boolean preview, boolean notice, boolean downloading, boolean completing,
                                  boolean mixing, boolean musicActive) {
        return showCapsuleRow(preview, notice, false, downloading, completing, mixing, musicActive);
    }

    static boolean showCapsuleRow(boolean preview, boolean notice, boolean capsuleBorderActive,
                                  boolean downloading, boolean completing, boolean mixing, boolean musicActive) {
        return (!notice || capsuleBorderActive) && (!downloading || capsuleBorderActive)
                && !completing && !mixing && (preview || musicActive);
    }

    static double contentReveal(double openness) {
        return smooth((safe(openness) - .30) / .58);
    }

    static final double JOIN_OPENNESS = .12;

    static Frame frame(double openness, double centerX, double top, double baseWidth, double baseHeight,
                       double compactWidth, double compactHeight, double cardScale) {
        return frame(openness, centerX, top, baseWidth, baseHeight, compactWidth, compactHeight,
                cardScale, contentReveal(openness));
    }

    static Frame frame(double openness, double centerX, double top, double baseWidth, double baseHeight,
                       double compactWidth, double compactHeight, double cardScale, double contentReveal) {
        double p = safe(openness);
        // 卡片直接接回原胶囊轮廓，不保留手机摄像头式的独立黑点。
        double anchorWidth = baseWidth;
        double anchorHeight = baseHeight;
        double anchorTop = top;
        double anchorAlpha = 1.0 - smooth((p - .085) / .11);
        double widthGrow = smooth((p - JOIN_OPENNESS) / .74);
        double heightGrow = smooth((p - JOIN_OPENNESS) / .76);
        double fullWidth = MusicCardLayout.WIDTH * cardScale;
        double fullHeight = MusicCardLayout.HEIGHT * cardScale;
        double width = lerp(baseWidth, fullWidth, widthGrow);
        double height = lerp(baseHeight, fullHeight, heightGrow);
        // 收起直接对齐胶囊中心与尺寸，两个表面只在交接段短暂重合。
        double detached = smooth((p - JOIN_OPENNESS) / .30);
        double separatedTop = compactHeight + 12.0 * cardScale;
        double cardTop = top + lerp((baseHeight - height) * .5, separatedTop, detached);
        double radius = lerp(baseHeight * .5, MusicCardLayout.RADIUS * cardScale, heightGrow);
        double alpha = smooth((p - .025) / .085);
        double content = safe(contentReveal);
        double contentScale = cardScale * (.94 + .06 * heightGrow);
        return new Frame(centerX - anchorWidth * .5, anchorTop, anchorWidth, anchorHeight, anchorAlpha,
                centerX - width * .5, cardTop, width, height, Math.min(radius, height * .5), alpha,
                centerX - MusicCardLayout.WIDTH * contentScale * .5,
                cardTop + 6.0 * cardScale * (1.0 - content), contentScale, content,
                Math.sin(Math.PI * p));
    }

    static double islandHandoff(double openness) {
        return smooth((safe(openness) - .005) / .10);
    }

    private static double safe(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static double smooth(double value) {
        double t = safe(value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    record Frame(double anchorX, double anchorY, double anchorWidth, double anchorHeight, double anchorAlpha,
                 double x, double y, double width, double height, double radius, double alpha,
                 double contentX, double contentY, double contentScale, double contentReveal, double motion) {
    }
}
