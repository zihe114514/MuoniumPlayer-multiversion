package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.TextBlob;
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
    private static final double STABLE_BLOB_PREWARM_LEAD_MS = 240.0;
    private static final int STABLE_BLOB_PREWARM_BUDGET = 2;
    private static SonnetCanvasRenderer instance;
    private static String reportedRoute;
    private final SonnetFrameSurface surface = new SonnetFrameSurface();
    private final SonnetTextFonts fonts;
    private final SonnetTextTransitions filters = new SonnetTextTransitions();
    private final Paint fill = new Paint().setAntiAlias(true);
    private final Paint screen = new Paint().setAntiAlias(true).setBlendMode(BlendMode.SCREEN);
    private final Paint outline = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE);
    private final Paint composite = new Paint().setAntiAlias(true);
    private final Paint readableEdge = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE);
    private float readabilityStroke = 2f;
    boolean stableGlyphBatching = true;
    int stableGlyphCountLastFrame;
    private long preparedGlowKey = Long.MIN_VALUE;
    private final SonnetTextMotion.Transition transition = new SonnetTextMotion.Transition();
    private final Map<Integer, SonnetTextScene> scenes = new LinkedHashMap<Integer, SonnetTextScene>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, SonnetTextScene> entry) {
            if (size() <= 3) return false;
            entry.getValue().close();
            return true;
        }
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
    private float anchorYaw, anchorPitch;
    private long lastSongTime = Long.MIN_VALUE;
    private long lastRenderNanos;
    private long lastAnchorNanos;

    SonnetCanvasRenderer(SonnetTextFonts fonts) { this.fonts = fonts; }

    static boolean render(WorldLyricsState.Frame frame, WorldLyricsState.CameraView camera,
                          WorldLyricsSettings settings, WorldLyricsRenderer.Host host,
                          CloudMusic.PlaybackSnapshot snap, long nowNanos) {
        if (!SkijaNatives.ensureLoaded()) {
            reportRoute("Skija 尚未就绪，暂用基础文字；就绪后自动恢复完整动画");
            return false;
        }
        if (instance != null && !instance.fonts.matchesCurrent()) release();
        if (instance == null) {
            SonnetTextFonts fonts = SonnetTextFonts.current();
            if (fonts == null) {
                reportRoute("字体尚未就绪，暂用基础文字；就绪后自动恢复完整动画");
                return false;
            }
            try { instance = new SonnetCanvasRenderer(fonts); }
            catch (Throwable failure) { fonts.close(); throw failure; }
        }
        instance.lastRenderNanos = System.nanoTime();
        return instance.draw(frame, camera, settings, host, snap, nowNanos);
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
        return draw(frame, null, settings, host, snap, System.nanoTime());
    }

    boolean draw(WorldLyricsState.Frame frame, WorldLyricsState.CameraView camera,
                 WorldLyricsSettings settings, WorldLyricsRenderer.Host host,
                 CloudMusic.PlaybackSnapshot snap, long nowNanos) {
        List<LyricLine> lyrics = WorldLyricsState.effectiveLines(false);
        String key = snap.music == null ? "sonnet" : snap.music.getStableKey();
        updateProgram(lyrics, CloudMusic.lyricsRevision, key);
        if (program.shots.isEmpty()) return false;
        WorldLyricsState.Row row = null;
        if (frame != null) for (WorldLyricsState.Row candidate : frame.rows) {
            // A row is an optional spatial hint, never a visibility/timing authority for Sonnet.
            if (candidate.active || row == null || !row.active && candidate.alpha > row.alpha) row = candidate;
        }
        if (camera == null && row == null) return true;
        double camX = camera != null ? camera.eyeX() : row.camX;
        double camY = camera != null ? camera.eyeY() : row.camY;
        double camZ = camera != null ? camera.eyeZ() : row.camZ;
        int shotIndex = program.shotAt(snap.positionMs);
        if (snap.positionMs < program.shots.get(0).startMs - 380
                || snap.positionMs >= program.finalRenderEndMs() + 1250) {
            clearPicture();
            return true;
        }
        int width = surfaceWidth(camera, settings);
        int height = Math.round(width * STAGE_HEIGHT / STAGE_WIDTH);
        float edge = minimumStageStroke(camera, settings);
        long options = optionsKey(settings) * 31 + Float.floatToIntBits(edge);
        readabilityStroke = edge;
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
        boolean seek = lastSongTime != Long.MIN_VALUE && snap.positionMs < lastSongTime - 300;
        double jump = Math.sqrt(Math.pow(camX - lastCamX, 2) + Math.pow(camY - lastCamY, 2)
                + Math.pow(camZ - lastCamZ, 2));
        boolean teleport = anchorShot >= 0 && jump > Math.max(64, settings.distance * 4);
        if (anchorShot != shotIndex || seek || teleport) {
            if (row != null && !teleport) {
                anchorX = row.x; anchorY = row.y; anchorZ = row.z;
                anchorYaw = (float) row.yawDeg; anchorPitch = (float) row.pitchDeg;
            } else {
                anchorX = camX + camera.lookX() * settings.distance;
                anchorY = camY + camera.lookY() * settings.distance;
                anchorZ = camZ + camera.lookZ() * settings.distance;
                anchorYaw = (float) camera.yawDeg(); anchorPitch = (float) camera.pitchDeg();
            }
            anchorShot = shotIndex;
        } else if (settings.keepDistance) {
            anchorX += camX - lastCamX;
            anchorY += camY - lastCamY;
            anchorZ += camZ - lastCamZ;
        }
        if (settings.keepDistance) {
            double rx = anchorX - camX, ry = anchorY - camY, rz = anchorZ - camZ;
            double length = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (length > 0.001 && Math.abs(length - settings.distance) > 0.001) {
                double ratio = settings.distance / length;
                anchorX = camX + rx * ratio; anchorY = camY + ry * ratio; anchorZ = camZ + rz * ratio;
            }
        }
        double deltaSeconds = lastAnchorNanos == 0 ? 1.0 / 60
                : Math.max(0, Math.min(0.1, (nowNanos - lastAnchorNanos) / 1_000_000_000.0));
        if (camera != null && settings.stayOnScreen) followView(camera, settings, deltaSeconds);
        lastAnchorNanos = nowNanos;
        if (settings.aboveGround) anchorY = Math.max(anchorY, camY - 0.5);
        lastCamX = camX; lastCamY = camY; lastCamZ = camZ;
        lastSongTime = snap.positionMs;
        float yaw = camera != null && settings.facePlayer ? (float) camera.yawDeg() : anchorYaw;
        float pitch = camera != null && settings.facePlayer ? (float) camera.pitchDeg() : anchorPitch;
        double distance = Math.sqrt(Math.pow(anchorX - camX, 2) + Math.pow(anchorY - camY, 2)
                + Math.pow(anchorZ - camZ, 2));
        float scale = (float) (WorldLyricsState.PX_TO_WORLD * settings.scale * distance / WorldLyricsState.DISTANCE_NORM);
        host.pushBillboard(anchorX - camX, anchorY - camY, anchorZ - camZ, yaw, pitch, scale);
        try {
            // Scene entry/exit, pause and gap visibility come exclusively from the song timeline.
            // Multiplying by legacy row alpha used to erase pre-guides and cut off cleanup frames.
            boolean presented = surface.present(host, picture, width, height, DESIGN_WIDTH, DESIGN_HEIGHT, 1, 0);
            reportRoute(presented ? "完整文字 PV / " + surface.presentationRoute()
                    : "画面提交失败，暂用基础文字（需要查看宿主纹理日志）");
            return presented;
        } finally {
            host.popBillboard();
        }
    }

    /** The shot can outlive every legacy row (pause/interlude); it needs its own view constraint. */
    private void followView(WorldLyricsState.CameraView camera, WorldLyricsSettings settings, double dt) {
        double rx = anchorX - camera.eyeX(), ry = anchorY - camera.eyeY(), rz = anchorZ - camera.eyeZ();
        double distance = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (distance < 0.001) return;
        double yaw = Math.toDegrees(Math.atan2(-rx, rz));
        double pitch = Math.toDegrees(Math.atan2(-ry, Math.hypot(rx, rz)));
        double aspect = camera.windowWidth() > 0 && camera.windowHeight() > 0
                ? camera.windowWidth() / (double) camera.windowHeight() : 16.0 / 9;
        double vertical = Math.max(20, Math.min(150, camera.fovDeg())) * 0.5;
        double horizontal = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vertical)) * aspect));
        double ratio = WorldLyricsState.PX_TO_WORLD * settings.scale / WorldLyricsState.DISTANCE_NORM;
        double yawLimit = Math.max(0, horizontal - Math.toDegrees(Math.atan(DESIGN_WIDTH * 0.5 * ratio)) - 2);
        double pitchLimit = Math.max(0, vertical - Math.toDegrees(Math.atan(DESIGN_HEIGHT * 0.5 * ratio)) - 2);
        double yawOffset = wrapDegrees(yaw - camera.yawDeg());
        double pitchOffset = pitch - camera.pitchDeg();
        double targetYaw = yaw + Math.max(-yawLimit, Math.min(yawLimit, yawOffset)) - yawOffset;
        double targetPitch = Math.max(-89, Math.min(89, camera.pitchDeg()
                + Math.max(-pitchLimit, Math.min(pitchLimit, pitchOffset))));
        double ease = 1 - Math.exp(-dt * 10);
        double nextYaw = yaw + wrapDegrees(targetYaw - yaw) * ease;
        double nextPitch = pitch + (targetPitch - pitch) * ease;
        double yr = Math.toRadians(nextYaw), pr = Math.toRadians(nextPitch);
        anchorX = camera.eyeX() - Math.sin(yr) * Math.cos(pr) * distance;
        anchorY = camera.eyeY() - Math.sin(pr) * distance;
        anchorZ = camera.eyeZ() + Math.cos(yr) * Math.cos(pr) * distance;
        if (!settings.facePlayer) {
            anchorYaw += (float) wrapDegrees(nextYaw - yaw);
            anchorPitch += (float) (nextPitch - pitch);
        }
    }

    private static double wrapDegrees(double angle) { return angle - Math.floor((angle + 180) / 360) * 360; }

    private static void reportRoute(String route) {
        if (!Objects.equals(route, reportedRoute)) {
            reportedRoute = route;
            System.out.println("[MuoniumPlayer/Sonnet] " + route);
        }
    }

    private static float projectedStagePixel(WorldLyricsState.CameraView camera, WorldLyricsSettings settings) {
        int height = camera != null && camera.windowHeight() > 0 ? camera.windowHeight() : 1080;
        double fov = camera != null ? Math.max(20, Math.min(150, camera.fovDeg())) : 70;
        return (float) (height * WorldLyricsState.PX_TO_WORLD * settings.scale * DESIGN_WIDTH
                / (2 * Math.tan(Math.toRadians(fov / 2)) * WorldLyricsState.DISTANCE_NORM * STAGE_WIDTH));
    }

    static int surfaceWidth(WorldLyricsState.CameraView camera, WorldLyricsSettings settings) {
        float projected = projectedStagePixel(camera, settings) * STAGE_WIDTH;
        return Math.max(1024, Math.min(1600, (int) Math.ceil(projected * 1.5 / 64) * 64));
    }

    static float minimumStageStroke(WorldLyricsState.CameraView camera, WorldLyricsSettings settings) {
        return Math.max(1.4f, Math.min(4f, 1.1f / Math.max(0.05f, projectedStagePixel(camera, settings))));
    }

    void updateProgram(List<LyricLine> lyrics, long revision, String key) {
        if (source == lyrics && this.revision == revision && Objects.equals(trackKey, key)) return;
        source = lyrics;
        this.revision = revision;
        trackKey = key;
        program = SonnetProgram.compile(lyrics == null ? List.of() : lyrics, key);
        clearScenes();
        anchorShot = -1;
        lastSongTime = Long.MIN_VALUE;
        lastAnchorNanos = 0;
        clearPicture();
    }

    Picture record(int index, double timeMs, WorldLyricsSettings settings, int width, int height) {
        SonnetTextScene scene = scenes.get(index);
        if (scene == null) {
            scene = new SonnetTextScene(program.shots.get(index), fonts, STAGE_WIDTH, STAGE_HEIGHT);
            scenes.put(index, scene);
        }
        scene.update(timeMs);
        prepareGlowMasks(settings);
        if (stableGlyphBatching) prewarmStableBlobs(scene, timeMs, settings.sonnetGlyphEffects);
        resolveTransition(index, timeMs, settings.sonnetGlyphEffects);
        SonnetDecorations.setReadabilityStroke(readabilityStroke / Math.max(0.05f, scene.layoutScale));
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

    private void prepareGlowMasks(WorldLyricsSettings settings) {
        if (!settings.glowEnabled || settings.glowStrength <= 0.004f) return;
        long key = (long) Float.floatToIntBits(settings.glowSpread) << 32
                | (settings.glowTier & 0xFFFFFFFFL);
        if (preparedGlowKey == key) return;
        TextGlow.prepareGlowMasks(settings.glowSpread, settings.glowTier);
        preparedGlowKey = key;
    }

    /**
     * Construct the next settled prefix while its entry animation is still running.
     * TextBlob creation is small on average but can otherwise land on the exact frame
     * where a new glyph becomes opaque. The bounded budget keeps a multi-line shot from
     * trading that spike for a different one during a scene transition.
     */
    private void prewarmStableBlobs(SonnetTextScene scene, double timeMs, boolean glyphEffects) {
        int budget = STABLE_BLOB_PREWARM_BUDGET;
        double ghostDuration = SonnetTextMotion.ghostDuration(scene.shot.endMs - scene.shot.startMs);
        for (SonnetTextScene.Segment segment : scene.segments) {
            if (budget == 0) break;
            if (segment.staff || (segment.box.role == SonnetTypography.Role.DECORATION && !glyphEffects)) continue;
            int settled = stablePrefixCount(scene, segment, timeMs);
            int target = Math.max(2, settled + 1);
            if (target > segment.glyphs.size()) continue;
            double ready = segment.stableReadyMs(target, ghostDuration);
            if (timeMs < ready - STABLE_BLOB_PREWARM_LEAD_MS || timeMs > ready + 40) continue;
            if (segment.prewarmStableBlob(fonts, target) != null) budget--;
        }
    }

    private void resolveTransition(int index, double timeMs, boolean glyphEffects) {
        program.transitionAt(index, timeMs, transition);
        double retract = transition.exitProgress;
        if (!glyphEffects) transition.reset();
        transition.exitProgress = retract;
        // Preserve Folia's lyric half of the credits handoff, without adding the credits poster.
        double end = SonnetTextMotion.clamp((timeMs - program.finalRenderEndMs()) / 1250);
        if (end > 0) {
            double eased = SonnetTextMotion.inOut(end);
            transition.alpha *= 1 - eased;
            transition.exitProgress = Math.max(transition.exitProgress, end);
            if (glyphEffects) transition.blur = Math.max(transition.blur, eased * 18);
        }
    }

    private void drawScene(Canvas canvas, SonnetTextScene scene, double timeMs, WorldLyricsSettings settings) {
        SonnetProgram.Shot shot = scene.shot;
        TextBlob[] stableBlobs = new TextBlob[scene.segments.size()];
        int[] stableCounts = new int[scene.segments.size()];
        stableGlyphCountLastFrame = 0;
        canvas.save();
        try {
            // World distance, scale and facing belong exclusively to the configured 3D billboard.
            // Inside it, words appear at their measured layout positions without a second camera.
            canvas.translate(STAGE_WIDTH * 0.5f, STAGE_HEIGHT * 0.5f);
            canvas.scale(scene.layoutScale, scene.layoutScale);
            canvas.translate(-scene.layoutCenterX, -scene.layoutCenterY);
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
            if (stableGlyphBatching) {
                for (int i = 0; i < scene.segments.size(); i++) {
                    SonnetTextScene.Segment segment = scene.segments.get(i);
                    if (segment.staff || (segment.box.role == SonnetTypography.Role.DECORATION
                            && !settings.sonnetGlyphEffects)) continue;
                    int count = stablePrefixCount(scene, segment, timeMs);
                    if (count < 2) continue;
                    TextBlob blob = segment.stableBlob(fonts, count);
                    if (blob != null) {
                        stableBlobs[i] = blob;
                        stableCounts[i] = segment.stableBlobCount();
                        stableGlyphCountLastFrame += stableCounts[i];
                    }
                }
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
                                localWidth, localHeight, segment.box.fontSize, segment.accentRgb, segment.frameVariant, p, (float) transition.exitProgress);
                    } else if (segment.fixed && timeMs >= segment.firstMs && !segment.glyphs.isEmpty()) {
                        SonnetTextScene.Glyph first = segment.glyphs.get(0);
                        float p = first.progress;
                        float px = segment.box.enterX * (1 - p);
                        float py = segment.box.enterY * (1 - p);
                        float scale = 0.86f + p * 0.14f;
                        float enter = (float) SonnetTextMotion.clamp((timeMs - first.startMs)
                                / Math.max(80, first.settleMs - first.startMs));
                        canvas.save();
                        try {
                            canvas.translate(segment.x + px, segment.y + py);
                            canvas.rotate((float) Math.toDegrees(segment.box.rotationRad));
                            canvas.scale(scale, scale);
                            SonnetFixedGeometry.draw(canvas, segment.box.fontSize, STAGE_WIDTH, segment.bodyRgb,
                                    segment.accentRgb, mix(segment.bodyRgb, segment.accentRgb, 0.5f),
                                    segment.textSeed, segment.chorusEffect, first.alpha, timeMs / 1000,
                                    enter, (float) transition.exitProgress);
                        } finally { canvas.restore(); }
                    }
                }
            }
            // One SCREEN layer, followed by all normal-mode glyphs; no per-letter host blend switches.
            if (settings.sonnetGlyphEffects) {
                for (int i = 0; i < scene.segments.size(); i++) {
                    SonnetTextScene.Segment segment = scene.segments.get(i);
                    if (segment.box.role == SonnetTypography.Role.DECORATION || segment.staff) continue;
                    for (int glyphIndex = 0; glyphIndex < segment.glyphs.size(); glyphIndex++) {
                        SonnetTextScene.Glyph glyph = segment.glyphs.get(glyphIndex);
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
            for (int segmentIndex = 0; segmentIndex < scene.segments.size(); segmentIndex++) {
                SonnetTextScene.Segment segment = scene.segments.get(segmentIndex);
                SonnetTypography.Role role = segment.box.role;
                boolean decor = role == SonnetTypography.Role.DECORATION;
                if (decor && !settings.sonnetGlyphEffects) continue;
                TextBlob blob = stableBlobs[segmentIndex];
                if (blob != null) {
                    if (decor) {
                        outline.setColor(argb(segment.accentRgb, 0.2f));
                        outline.setStrokeWidth(Math.max(1, Math.min(8, segment.box.fontSize * 0.006f))
                                * 64 / segment.box.fontSize);
                        fonts.drawBatch(canvas, blob, segment.box.fontSize, outline);
                    } else {
                        if (settings.glowEnabled) {
                            fonts.glowBatch(canvas, blob, role, segment.box.fontSize,
                                    segment.glowRgb, segment.bodyRgb, settings.glowStrength,
                                    settings.glowSpread, settings.glowTier);
                        }
                        int edgeRgb = contrastInk(segment.bodyRgb);
                        readableEdge.setColor(argb(edgeRgb, 0.86f));
                        readableEdge.setStrokeWidth(readabilityStroke
                                / Math.max(0.05f, scene.layoutScale) * 64 / segment.box.fontSize);
                        fonts.drawBatch(canvas, blob, segment.box.fontSize, readableEdge);
                        fill.setColor(argb(segment.bodyRgb, 1f));
                        fonts.drawBatch(canvas, blob, segment.box.fontSize, fill);
                    }
                }
                for (int glyphIndex = stableCounts[segmentIndex];
                     glyphIndex < segment.glyphs.size(); glyphIndex++) {
                    SonnetTextScene.Glyph glyph = segment.glyphs.get(glyphIndex);
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
                        // Tight opposite-luminance ink contour: keeps the real glyph edge sharp over
                        // sky, snow and dark terrain, without a background panel or permanent blur.
                        int edgeRgb = contrastInk(segment.bodyRgb);
                        readableEdge.setColor(argb(edgeRgb, glyph.alpha * 0.86f));
                        readableEdge.setStrokeWidth(readabilityStroke / Math.max(0.05f, scene.layoutScale) * 64 / segment.box.fontSize);
                        fonts.draw(canvas, glyph.text, role, glyph.geometry, segment.box.fontSize, readableEdge);
                        fill.setColor(argb(segment.bodyRgb, glyph.alpha));
                        fonts.draw(canvas, glyph.text, role, glyph.geometry, segment.box.fontSize, fill);
                    } finally { canvas.restore(); }
                }
            }
        } finally {
            canvas.restore();
        }
    }

    private static int stablePrefixCount(SonnetTextScene scene, SonnetTextScene.Segment segment,
                                         double timeMs) {
        int count = 0;
        for (SonnetTextScene.Glyph glyph : segment.glyphs) {
            if (glyph.progress < 1 || glyph.alpha < 1) break;
            if (segment.box.role == SonnetTypography.Role.SEMI_HERO) {
                double ghost = (timeMs - glyph.startMs)
                        / SonnetTextMotion.ghostDuration(scene.shot.endMs - scene.shot.startMs);
                if (ghost > 0 && ghost < 1) break;
            }
            count++;
        }
        return count;
    }

    private void clearScenes() {
        for (SonnetTextScene scene : scenes.values()) scene.close();
        scenes.clear();
    }

    static int contrastInk(int rgb) {
        double luminance = ((rgb >> 16 & 255) * 0.2126 + (rgb >> 8 & 255) * 0.7152 + (rgb & 255) * 0.0722) / 255;
        return luminance >= 0.45 ? 0x10141C : 0xF4F6FA;
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
        clearScenes();
        source = null; program = null;
        surface.close();
        fonts.close();
        filters.close();
        fill.close(); screen.close(); outline.close(); composite.close(); readableEdge.close();
    }
}
