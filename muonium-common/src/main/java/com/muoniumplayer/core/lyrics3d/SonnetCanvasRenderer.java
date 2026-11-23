package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.types.Rect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Folia Sonnet typography on a transparent world-lyric plane. The layout, curves,
 * particles and compositing are recorded once and replayed by the existing host
 * surface path. It never captures the game, cover art or a scene background.
 * Adapted from chthollyphile/folia-major Sonnet, AGPL-3.0.
 */
final class SonnetCanvasRenderer implements AutoCloseable {
    static final float STAGE_WIDTH = 1280, STAGE_HEIGHT = 720;
    private static final float DESIGN_WIDTH = 200, DESIGN_HEIGHT = DESIGN_WIDTH * STAGE_HEIGHT / STAGE_WIDTH;
    private static SonnetCanvasRenderer instance;
    private final SonnetFrameSurface surface = new SonnetFrameSurface();
    private final SonnetTextFonts fonts;
    private final SonnetTextTransitions filters = new SonnetTextTransitions();
    private final Paint fill = new Paint().setAntiAlias(true);
    private final Paint screen = new Paint().setAntiAlias(true).setBlendMode(BlendMode.SCREEN);
    private final Paint outline = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE);
    private final Paint composite = new Paint().setAntiAlias(true);
    private final SonnetTextMotion.Transition transition = new SonnetTextMotion.Transition();
    private final Map<Integer, SonnetTextScene> scenes = new LinkedHashMap<Integer, SonnetTextScene>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, SonnetTextScene> entry) { return size() > 3; }
    };
    private SonnetProgram program;
    private List<LyricLine> source;
    private long revision = Long.MIN_VALUE;
    private String trackKey;
    private Picture picture;
    private long pictureTime = Long.MIN_VALUE, pictureOptions;
    private int pictureShot = -1, pictureWidth;
    private double anchorX, anchorY, anchorZ, lastCamX, lastCamY, lastCamZ;
    private int anchorShot = -1;
    private long lastSongTime = Long.MIN_VALUE;
    private long lastRenderNanos;

    SonnetCanvasRenderer(SonnetTextFonts fonts) { this.fonts = fonts; }

    static boolean render(WorldLyricsState.Frame frame, WorldLyricsSettings settings,
                          WorldLyricsRenderer.Host host, CloudMusic.PlaybackSnapshot snap) {
        if (!SkijaNatives.ensureLoaded()) return false;
        if (instance != null && !instance.fonts.matchesCurrent()) release();
        if (instance == null) {
            SonnetTextFonts fonts = SonnetTextFonts.current();
            if (fonts == null) return false;
            try { instance = new SonnetCanvasRenderer(fonts); }
            catch (Throwable failure) { fonts.close(); throw failure; }
        }
        instance.lastRenderNanos = System.nanoTime();
        return instance.draw(frame, settings, host, snap);
    }

    static void releaseIfIdle(long nowNanos) {
        if (instance != null && nowNanos - instance.lastRenderNanos > 3_000_000_000L) release();
    }

    static void release() {
        if (instance != null) instance.close();
        instance = null;
        SonnetDecorations.close();
        SonnetStaff.close();
    }

    boolean draw(WorldLyricsState.Frame frame, WorldLyricsSettings settings,
                         WorldLyricsRenderer.Host host, CloudMusic.PlaybackSnapshot snap) {
        List<LyricLine> lyrics = WorldLyricsState.effectiveLines(false);
        String key = snap.music == null ? "sonnet" : snap.music.getStableKey();
        updateProgram(lyrics, CloudMusic.lyricsRevision, key);
        if (program.shots.isEmpty()) return false;
        WorldLyricsState.Row row = null;
        for (WorldLyricsState.Row candidate : frame.rows) {
            if (candidate.alpha <= 0.01f) continue;
            if (candidate.active || row == null || !row.active && candidate.alpha > row.alpha) row = candidate;
        }
        if (row == null) return true;
        int shotIndex = program.shotAt(snap.positionMs);
        if (shotIndex < 0) return true;
        int width = Math.max(768, Math.min(1600, Math.round(STAGE_WIDTH * LyricGlyphAtlas.bakeScale() / 5f)));
        int height = Math.round(width * STAGE_HEIGHT / STAGE_WIDTH);
        long options = optionsKey(settings);
        if (picture == null || pictureTime != snap.positionMs || pictureOptions != options
                || pictureShot != shotIndex || pictureWidth != width) {
            Picture next = record(shotIndex, snap.positionMs, settings, width, height);
            if (picture != null) picture.close();
            picture = next;
            pictureTime = snap.positionMs;
            pictureOptions = options;
            pictureShot = shotIndex;
            pictureWidth = width;
        }
        if (anchorShot != shotIndex || lastSongTime != Long.MIN_VALUE && snap.positionMs < lastSongTime - 300) {
            anchorX = row.x; anchorY = row.y; anchorZ = row.z;
            anchorShot = shotIndex;
        } else if (settings.keepDistance) {
            anchorX += row.camX - lastCamX;
            anchorY += row.camY - lastCamY;
            anchorZ += row.camZ - lastCamZ;
        }
        lastCamX = row.camX; lastCamY = row.camY; lastCamZ = row.camZ;
        lastSongTime = snap.positionMs;
        host.pushBillboard(anchorX - row.camX, anchorY - row.camY, anchorZ - row.camZ,
                row.yawDeg, row.pitchDeg, anchorScale(row, settings));
        try {
            return surface.present(host, picture, width, height, DESIGN_WIDTH, DESIGN_HEIGHT, shotAlpha(row), 0);
        } finally {
            host.popBillboard();
        }
    }

    private float anchorScale(WorldLyricsState.Row row, WorldLyricsSettings settings) {
        double distance = Math.sqrt(Math.pow(anchorX - row.camX, 2) + Math.pow(anchorY - row.camY, 2)
                + Math.pow(anchorZ - row.camZ, 2));
        return (float) (WorldLyricsState.PX_TO_WORLD * settings.scale * distance / WorldLyricsState.DISTANCE_NORM);
    }

    static float shotAlpha(WorldLyricsState.Row row) {
        return Math.min(1, row.alpha / Math.max(0.001f, row.protectDim));
    }

    void updateProgram(List<LyricLine> lyrics, long revision, String key) {
        if (source == lyrics && this.revision == revision && Objects.equals(trackKey, key)) return;
        source = lyrics;
        this.revision = revision;
        trackKey = key;
        program = SonnetProgram.compile(lyrics == null ? List.of() : lyrics, key);
        scenes.clear();
        anchorShot = -1;
        lastSongTime = Long.MIN_VALUE;
        clearPicture();
    }

    Picture record(int index, double timeMs, WorldLyricsSettings settings, int width, int height) {
        SonnetTextScene scene = scenes.get(index);
        if (scene == null) {
            scene = new SonnetTextScene(program.shots.get(index), fonts, STAGE_WIDTH, STAGE_HEIGHT);
            scenes.put(index, scene);
        }
        scene.update(timeMs);
        resolveTransition(index, timeMs);
        if (!settings.sonnetGlyphEffects) transition.reset();
        Canvas canvas = surface.begin(width, height);
        canvas.scale(width / STAGE_WIDTH, height / STAGE_HEIGHT);
        canvas.save();
        try (ImageFilter filter = filters.filter(transition, STAGE_WIDTH, STAGE_HEIGHT)) {
            composite.setColor(0xFFFFFFFF).setAlphaf((float) transition.alpha).setImageFilter(filter);
            canvas.saveLayer(Rect.makeWH(STAGE_WIDTH, STAGE_HEIGHT), composite);
            try {
                drawScene(canvas, scene, timeMs, settings);
            } finally {
                canvas.restore();
                composite.setImageFilter(null);
            }
        } finally {
            canvas.restore();
        }
        return surface.finish();
    }

    private void resolveTransition(int index, double timeMs) {
        transition.reset();
        SonnetProgram.Shot shot = program.shots.get(index);
        if (index > 0) {
            SonnetProgram.Shot previous = program.shots.get(index - 1);
            double duration = Math.min(240, Math.max(140, (shot.startMs - previous.startMs) * 0.18));
            if (timeMs >= shot.startMs && timeMs <= shot.startMs + duration) {
                SonnetTextMotion.transition(previous.transitionKind, false,
                        (timeMs - shot.startMs) / duration, SonnetTypography.hash(shot.id), transition);
                return;
            }
        }
        if (index + 1 < program.shots.size()) {
            SonnetProgram.Shot next = program.shots.get(index + 1);
            double duration = Math.min(240, Math.max(140, (next.startMs - shot.startMs) * 0.18));
            if (timeMs >= next.startMs - duration) {
                SonnetTextMotion.transition(shot.transitionKind, true,
                        (timeMs - next.startMs + duration) / duration, SonnetTypography.hash(shot.id), transition);
            }
        }
    }

    private void drawScene(Canvas canvas, SonnetTextScene scene, double timeMs, WorldLyricsSettings settings) {
        SonnetTextMotion.Camera camera = scene.camera;
        SonnetProgram.Shot shot = scene.shot;
        canvas.save();
        try {
            boolean poster = shot.kind == SonnetTypography.Kind.POSTER_BLOCKS;
            float x = STAGE_WIDTH * (poster ? 0.5f : 0.5f + shot.cameraX);
            float y = STAGE_HEIGHT * (poster ? 0.5f : 0.48f + shot.cameraY);
            canvas.translate(x + (float) camera.x * STAGE_WIDTH, y + (float) camera.y * STAGE_HEIGHT);
            canvas.rotate((float) Math.toDegrees(shot.rotationRad + camera.rotation));
            float zoom = shot.zoom * (float) camera.scale;
            canvas.scale(zoom, zoom);
            canvas.translate(-scene.focusX, -scene.focusY);
            int body = settings.colorCustom ? settings.colorRgb & 0xFFFFFF : 0xFFFFFF;
            int[] palette = WorldLyricsRenderer.effectiveCoverPalette(settings);
            if (palette != null && palette.length > 0) body = palette[0] & 0xFFFFFF;
            int total = Math.max(1, scene.segments.size() - 1);
            for (int i = 0; i < scene.segments.size(); i++) {
                SonnetTextScene.Segment segment = scene.segments.get(i);
                int rgb = body;
                if (settings.coverColorGradient && palette != null && palette.length > 1) {
                    float at = Math.min(palette.length - 1, i / (float) total * (palette.length - 1));
                    int left = Math.min(palette.length - 2, (int) at);
                    rgb = mix(palette[left], palette[left + 1], at - left);
                }
                segment.bodyRgb = rgb;
                segment.glowRgb = keywordColor(settings.keywordColors, segment.box.segment.text,
                        settings.glowColorMode == 1 ? settings.glowColorRgb : rgb);
                segment.accentRgb = segment.box.role == SonnetTypography.Role.HERO
                        || segment.box.role == SonnetTypography.Role.SEMI_HERO
                        ? segment.glowRgb : mix(rgb, segment.glowRgb, 0.55f);
            }
            // Guides arrive before their word. They are independent of core-glyph visibility.
            if (settings.sonnetDecorations) {
                for (SonnetTextScene.Segment segment : scene.segments) {
                    if (segment.box.role == SonnetTypography.Role.DECORATION) continue;
                    if (timeMs >= segment.guideStartMs && timeMs <= segment.guideEndMs) {
                        boolean emphasis = segment.box.role == SonnetTypography.Role.HERO
                                || segment.box.role == SonnetTypography.Role.SEMI_HERO;
                        float p = (float) ((timeMs - segment.guideStartMs) / (segment.guideEndMs - segment.guideStartMs));
                        SonnetDecorations.drawGuide(canvas, segment.x, segment.y, segment.accentRgb,
                                segment.box.fontSize, segment.box.enterX, segment.box.enterY, p, emphasis, segment.seed);
                    }
                }
                for (SonnetTextScene.Segment segment : scene.segments) {
                    if (segment.box.role == SonnetTypography.Role.DECORATION) continue;
                    if (segment.frame) {
                        float p = (float) SonnetTextMotion.clamp((timeMs - segment.firstMs)
                                / Math.max(1, segment.frameEndMs - segment.firstMs));
                        float localWidth = segment.box.width, localHeight = segment.box.height;
                        if (Math.abs(Math.cos(segment.box.rotationRad)) < 0.000001) {
                            localWidth = segment.box.height; localHeight = segment.box.width;
                        }
                        SonnetDecorations.drawFrame(canvas, segment.x, segment.y, segment.box.rotationRad,
                                localWidth, localHeight, segment.box.fontSize, segment.accentRgb, segment.frameVariant, p);
                    } else if (segment.fixed && timeMs >= segment.firstMs && !segment.glyphs.isEmpty()) {
                        SonnetTextScene.Glyph first = segment.glyphs.get(0);
                        float px = (float) (camera.x * STAGE_WIDTH * -0.7 * 2.5);
                        float py = (float) (camera.y * STAGE_HEIGHT * -0.7 * 2.5);
                        SonnetDecorations.drawFixedGeometry(canvas, segment.x + px, segment.y + py,
                                segment.box.rotationRad, segment.box.fontSize, STAGE_WIDTH, segment.bodyRgb,
                                segment.accentRgb, mix(segment.bodyRgb, segment.accentRgb, 0.5f),
                                segment.textSeed, segment.chorusEffect, first.alpha);
                    }
                }
            }
            // One SCREEN layer, followed by all normal-mode glyphs; no per-letter host blend switches.
            if (settings.sonnetGlyphEffects) {
                for (SonnetTextScene.Segment segment : scene.segments) {
                    if (segment.box.role == SonnetTypography.Role.DECORATION || segment.staff) continue;
                    for (SonnetTextScene.Glyph glyph : segment.glyphs) {
                        if (glyph.alpha <= 0) continue;
                        canvas.save();
                        try {
                            glyphTransform(canvas, glyph);
                            boolean emphasis = segment.box.role == SonnetTypography.Role.HERO
                                    || segment.box.role == SonnetTypography.Role.SEMI_HERO;
                            float offset = segment.box.fontSize * (emphasis ? 0.025f : 0.01f)
                                    * (float) (1 - SonnetTextMotion.inOut(glyph.progress) * 0.8);
                            float alpha = glyph.alpha * (emphasis ? 0.8f : 0.5f);
                            int cyan = segment.bodyRgb & 0x00FFFF;
                            int red = (segment.bodyRgb & 0xFF0000) | ((segment.bodyRgb & 255) * 68 / 255);
                            drawDispersion(canvas, segment, glyph, -offset, offset * 0.5f, cyan, alpha);
                            drawDispersion(canvas, segment, glyph, offset, -offset * 0.5f, red, alpha);
                        } finally { canvas.restore(); }
                    }
                }
            }
            for (SonnetTextScene.Segment segment : scene.segments) {
                SonnetTypography.Role role = segment.box.role;
                boolean decor = role == SonnetTypography.Role.DECORATION;
                if (decor && !settings.sonnetGlyphEffects) continue;
                for (SonnetTextScene.Glyph glyph : segment.glyphs) {
                    if (glyph.alpha <= 0) continue;
                    canvas.save();
                    try {
                        glyphTransform(canvas, glyph);
                        if (segment.staff) {
                            canvas.saveLayerAlpha(Rect.makeXYWH(-STAGE_WIDTH / 2, -STAGE_HEIGHT / 2,
                                    STAGE_WIDTH, STAGE_HEIGHT), Math.round(glyph.alpha * 255));
                            try {
                                SonnetStaff.draw(canvas, STAGE_WIDTH, scene.baseFont,
                                        segment.bodyRgb, segment.glowRgb, timeMs - shot.startMs);
                            } finally { canvas.restore(); }
                            continue;
                        }
                        if (decor) {
                            outline.setColor(argb(segment.accentRgb, glyph.alpha * 0.2f));
                            outline.setStrokeWidth(Math.max(1, Math.min(8, segment.box.fontSize * 0.006f))
                                    * 64 / segment.box.fontSize);
                            fonts.draw(canvas, glyph.text, role, glyph.geometry, segment.box.fontSize, outline);
                            continue;
                        }
                        if (settings.sonnetGlyphEffects && role == SonnetTypography.Role.SEMI_HERO) {
                            drawGhosts(canvas, scene, segment, glyph, timeMs);
                        }
                        if (settings.glowEnabled) {
                            fonts.glow(canvas, glyph.text, role, glyph.geometry, segment.box.fontSize,
                                    segment.glowRgb, segment.bodyRgb, settings.glowStrength * glyph.alpha,
                                    settings.glowSpread, settings.glowTier);
                        }
                        fill.setColor(argb(segment.bodyRgb, glyph.alpha));
                        fonts.draw(canvas, glyph.text, role, glyph.geometry, segment.box.fontSize, fill);
                    } finally { canvas.restore(); }
                }
            }
        } finally { canvas.restore(); }
    }

    private void drawDispersion(Canvas canvas, SonnetTextScene.Segment segment,
                                SonnetTextScene.Glyph glyph, float x, float y, int rgb, float alpha) {
        canvas.save();
        try {
            canvas.translate(x, y);
            screen.setColor(argb(rgb, alpha));
            fonts.draw(canvas, glyph.text, segment.box.role, glyph.geometry, segment.box.fontSize, screen);
        } finally { canvas.restore(); }
    }

    private void drawGhosts(Canvas canvas, SonnetTextScene scene, SonnetTextScene.Segment segment,
                            SonnetTextScene.Glyph glyph, double timeMs) {
        double progress = (timeMs - glyph.startMs)
                / SonnetTextMotion.ghostDuration(scene.shot.endMs - scene.shot.startMs);
        if (progress <= 0 || progress >= 1) return;
        float envelope = (float) SonnetTextMotion.ghostEnvelope(progress);
        float spread = (float) SonnetTextMotion.ghostSpread(progress);
        float side = (segment.normalSeed & 1) == 0 ? 1 : -1;
        double angle = -segment.box.rotationRad;
        float nx = segment.box.flowVertical ? (float) Math.cos(angle) : -(float) Math.sin(angle);
        float ny = segment.box.flowVertical ? (float) Math.sin(angle) : (float) Math.cos(angle);
        for (int layer = 0; layer < 2; layer++) {
            float distance = segment.box.fontSize * 0.85f * (layer == 0 ? 1 : 1.7f) * side * spread;
            canvas.save();
            try {
                canvas.translate(nx * distance, ny * distance);
                outline.setColor(argb(segment.glowRgb, glyph.alpha * envelope * (layer == 0 ? 0.3f : 0.16f)));
                outline.setStrokeWidth(Math.max(1, Math.min(8, segment.box.fontSize * 0.006f))
                        * 64 / segment.box.fontSize);
                fonts.draw(canvas, glyph.text, segment.box.role, glyph.geometry, segment.box.fontSize, outline);
            } finally { canvas.restore(); }
        }
    }

    private static void glyphTransform(Canvas canvas, SonnetTextScene.Glyph glyph) {
        canvas.translate(glyph.x, glyph.y);
        canvas.rotate((float) Math.toDegrees(glyph.rotation));
        canvas.scale(glyph.scale, glyph.scale);
    }

    private static int argb(int rgb, float alpha) {
        return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static int mix(int a, int b, float t) {
        float p = Math.max(0, Math.min(1, t));
        int r = Math.round((a >> 16 & 255) * (1 - p) + (b >> 16 & 255) * p);
        int g = Math.round((a >> 8 & 255) * (1 - p) + (b >> 8 & 255) * p);
        int blue = Math.round((a & 255) * (1 - p) + (b & 255) * p);
        return r << 16 | g << 8 | blue;
    }

    private static int keywordColor(String specification, String segmentText, int fallback) {
        if (specification == null || specification.isEmpty()) return fallback & 0xFFFFFF;
        String needle = segmentText.trim();
        for (String entry : specification.split(",")) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || !entry.substring(0, colon).trim().equalsIgnoreCase(needle)) continue;
            String hex = entry.substring(colon + 1).trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            try { return Integer.parseInt(hex, 16) & 0xFFFFFF; }
            catch (NumberFormatException invalid) { return fallback & 0xFFFFFF; }
        }
        return fallback & 0xFFFFFF;
    }

    private long optionsKey(WorldLyricsSettings settings) {
        long result = settings.sonnetDecorations ? 1 : 0;
        result = result * 31 + (settings.sonnetGlyphEffects ? 1 : 0);
        result = result * 31 + (settings.colorCustom ? settings.colorRgb : 0xFFFFFF);
        result = result * 31 + (settings.glowEnabled ? Float.floatToIntBits(settings.glowStrength) : 0);
        result = result * 31 + Float.floatToIntBits(settings.glowSpread);
        result = result * 31 + settings.glowColorRgb + settings.glowColorMode * 97 + settings.glowTier;
        result = result * 31 + (settings.keywordColors == null ? 0 : settings.keywordColors.hashCode());
        result = result * 31 + settings.coverColorMode + (settings.coverColorGradient ? 17 : 0);
        if (settings.coverColorMode == 1) result = result * 31 + com.muoniumplayer.core.ncm.music.CoverGradientColors.version();
        else if (settings.coverColorMode == 2) result = result * 31
                + java.util.Arrays.hashCode(WorldLyricsRenderer.effectiveCoverPalette(settings));
        return result;
    }

    private void clearPicture() {
        if (picture != null) picture.close();
        picture = null;
        pictureTime = Long.MIN_VALUE;
    }

    @Override public void close() {
        clearPicture();
        scenes.clear();
        source = null; program = null;
        surface.close();
        fonts.close();
        filters.close();
        fill.close(); screen.close(); outline.close(); composite.close();
    }
}