package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/** The fullscreen background uses cover pixels prepared during the original artwork decode. */
final class AppleCoverBackdrop {
    private static final float[] STOPS = {0f, .46f, 1f};
    private final int[] previous = new int[3];
    private final int[] target = new int[3];
    private long lastFrame;
    private double fade = 1;
    private final CoverRhythmMotion motion = new CoverRhythmMotion();
    private byte[] mountedBytes;
    private Image currentArt;
    private Image previousArt;
    private long waitingSince;
    private double artFade = 1;
    private String trackKey;

    void reset() {
        lastFrame = 0;
        fade = 1;
        motion.reset();
        if (previousArt != null) previousArt.close();
        if (currentArt != null) currentArt.close();
        previousArt = currentArt = null;
        mountedBytes = null;
        waitingSince = 0;
        trackKey = null;
        artFade = 1;
    }

    boolean render(double x, double y, double width, double height, float alpha,
                   CloudMusic.PlaybackSnapshot snapshot, boolean staticMode) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || width <= 0 || height <= 0) return false;
        long now = System.nanoTime();
        boolean first = lastFrame == 0;
        double dt = first ? 0 : Math.min(.05, Math.max(0, (now - lastFrame) / 1e9));
        if (!first && now - lastFrame > 400_000_000L) motion.reset();
        lastFrame = now;
        Music track = snapshot.music;
        updateArtwork(track, now);
        artFade = staticMode ? 1 : Math.min(1, artFade + dt / .84);
        if (artFade == 1 && previousArt != null) {
            previousArt.close();
            previousArt = null;
        }
        int[] palette = track == null ? null : track.getRepresentativeColors();
        if (track != null && (palette == null || palette.length == 0)) palette = track.getCoverPalette();
        if (first) {
            for (int i = 0; i < target.length; i++) {
                previous[i] = target[i] = AppleLyricEffects.backdropColor(palette, i);
            }
        }
        boolean changed = false;
        for (int i = 0; i < target.length; i++) {
            if (target[i] != AppleLyricEffects.backdropColor(palette, i)) changed = true;
        }
        if (changed) {
            for (int i = 0; i < target.length; i++) {
                previous[i] = tint(i);
                target[i] = AppleLyricEffects.backdropColor(palette, i);
            }
            fade = 0;
        }
        fade = staticMode ? 1 : Math.min(1, fade + dt / .72);
        // A mirror can report a playback clock without supplying audio to the local analyser.
        boolean playing = !staticMode && snapshot.player != null && CloudMusic.isAudibleSnapshot(snapshot);
        float onset = playing ? PlaybackPulse.beat() : 0f;
        float loudness = playing ? PlaybackPulse.loudness() : 0f;
        var beat = motion.sample(dt, snapshot.sessionId, snapshot.positionMs, playing, staticMode, onset, loudness);
        double pulse = beat.getPulse();
        double energy = beat.getEnergy();
        Rect bounds = Rect.makeXYWH((float) x, (float) y, (float) width, (float) height);
        Rect unit = Rect.makeWH(1f, 1f);
        try (Paint paint = new Paint()) {
            int saved = canvas.save();
            try {
                if (alpha < .999f) {
                    paint.setAlphaf(alpha);
                    canvas.saveLayer(bounds, paint);
                }
                canvas.translate((float) x, (float) y);
                canvas.scale((float) width, (float) height);
                canvas.clipRect(unit);
                paint.setColor(0xff171618);
                canvas.drawRect(unit, paint);

                drawField(canvas, unit, paint, (float) (.17 - .085 * pulse),
                        (float) (.16 + .09 * energy), (float) (.90 + .16 * pulse), tint(0));
                drawField(canvas, unit, paint, (float) (.88 + .085 * pulse - .05 * energy),
                        (float) (.57 - .12 * pulse), (float) (.84 + .10 * energy), tint(1));
                drawField(canvas, unit, paint, (float) (.39 + .12 * energy),
                        (float) (1.02 - .12 * pulse), (float) (.77 + .12 * pulse), tint(2));

                float blend = (float) (artFade * artFade * (3 - 2 * artFade));
                if (previousArt != null) {
                    drawArtwork(canvas, paint, previousArt, pulse, energy, currentArt == null ? 1 - blend : 1);
                }
                if (currentArt != null) drawArtwork(canvas, paint, currentArt, pulse, energy, blend);

                try (Shader shade = Shader.makeLinearGradient(0f, 0f, 0f, 1f,
                        new int[] {0x0c000000, 0x28000000, 0xaa000000}, STOPS)) {
                    paint.setColor(0xffffffff);
                    paint.setShader(shade);
                    canvas.drawRect(unit, paint);
                    paint.setShader(null);
                }
            } finally {
                canvas.restoreToCount(saved);
            }
        }
        return true;
    }

    private void updateArtwork(Music track, long now) {
        String key = track == null ? "" : track.getStableKey();
        if (!key.equals(trackKey)) {
            trackKey = key;
            waitingSince = now;
        }
        byte[] bytes = CoverBackdropArtwork.find(key);
        if (bytes == mountedBytes || bytes == null && now - waitingSince < 750_000_000L) return;
        Image incoming = bytes == null ? null : Image.makeDeferredFromEncodedBytes(bytes);
        if (previousArt != null) previousArt.close();
        previousArt = currentArt;
        currentArt = incoming;
        mountedBytes = bytes;
        artFade = waitingSince == now && previousArt == null ? 1 : 0;
    }

    private static void drawArtwork(Canvas canvas, Paint paint, Image image,
                                    double pulse, double energy, float alpha) {
        if (alpha <= .001f) return;
        Rect source = Rect.makeWH(image.getWidth(), image.getHeight());
        drawDrift(canvas, paint, image, source, alpha * .82f,
                -.078 * pulse, .08 + .08 * energy, -12 * pulse, 0);
        drawDrift(canvas, paint, image, source, alpha * .32f,
                .12 + .08 * pulse, -.07 * energy, 19 + 10 * pulse, 180);
    }

    private static void drawDrift(Canvas canvas, Paint paint, Image image, Rect source, float alpha,
                                  double x, double y, double angle, double flip) {
        int saved = canvas.save();
        try {
            canvas.translate((float) (.5 + x), (float) (.5 + y));
            canvas.rotate((float) (angle + flip));
            paint.setColor(0xffffffff);
            paint.setAlphaf(alpha);
            canvas.drawImageRect(image, source, Rect.makeXYWH(-.96f, -.89f, 1.92f, 1.78f),
                    SamplingMode.LINEAR, paint, true);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private double smoothFade() {
        return fade * fade * (3 - 2 * fade);
    }

    private int tint(int index) {
        return AppleLyricEffects.mix(previous[index], target[index], smoothFade());
    }

    private static void drawField(Canvas canvas, Rect area, Paint paint,
                                  float x, float y, float radius, int color) {
        int rgb = color & 0x00ffffff;
        try (Shader field = Shader.makeRadialGradient(x, y, radius,
                new int[] {0xeb000000 | rgb, 0xa7000000 | rgb, rgb}, STOPS)) {
            paint.setColor(0xffffffff);
            paint.setShader(field);
            canvas.drawRect(area, paint);
            paint.setShader(null);
        }
    }
}
