package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** One small synthetic raster supply; no cached-art manifest, network or mass-render loop. */
@EnabledOnOs(OS.WINDOWS)
class MusicCardEffectsRenderTest {
    @BeforeAll
    static void fonts() { MusicCardRenderTest.fonts(); }

    @Test
    void vectorInkCentersAndProductionWaveStaysBehindRoundedCoversInsideCardClip() throws Exception {
        MusicCapsuleCard card = MusicCapsuleCard.INSTANCE;
        Field canvasField = field(SkijaHost.class, "canvas"), inFrameField = field(SkijaHost.class, "inFrame");
        Field snapshotField = field(MusicCapsuleCard.class, "frameSnapshot");
        Field accentField = field(MusicCapsuleCard.class, "accent"), secondaryField = field(MusicCapsuleCard.class, "secondaryAccent");
        Field oldKeyField = field(MusicCapsuleCard.class, "previousTrackKey");
        MusicCardEffects effects = (MusicCardEffects) field(MusicCapsuleCard.class, "effects").get(card);
        Field rippleElapsedField = field(MusicCardEffects.Ripple.class, "elapsed");
        Object oldCanvas = canvasField.get(null), oldInFrame = inFrameField.get(null), oldSnapshot = snapshotField.get(null);
        Object oldAccent = accentField.get(card), oldSecondary = secondaryField.get(card), oldKey = oldKeyField.get(null);
        Object oldElapsed = rippleElapsedField.get(effects.ripple);
        try (Surface sheet = Surface.makeRasterN32Premul(760, 842);
             Surface coverSurface = Surface.makeRasterN32Premul(80, 40);
             Bitmap bitmap = new Bitmap()) {
            Canvas canvas = sheet.getCanvas();
            canvas.clear(0);
            canvasField.set(null, canvas);
            inFrameField.set(null, true);
            // Non-square supply has red margins that must be cropped away, not squished into a square.
            coverSurface.getCanvas().clear(0xFFEF3340);
            coverSurface.getCanvas().drawRect(Rect.makeXYWH(20, 0, 40, 40), SkijaGfx.fill(0xFF52C57C));
            try (Image cover = coverSurface.makeImageSnapshot()) {
                Music music = new Music("Ripple probe", "Ripple probe", "", 9243L, Collections.emptyList(),
                        Collections.emptyList(), null, 180_000L, 0L, 0L, Collections.emptyList());
                music.setSource(MusicPlatform.QQ);
                music.setSourceId("card-effects-probe");
                music.setArtistsName("Centered vectors");
                snapshotField.set(null, new CloudMusic.PlaybackSnapshot(9243L, music.getId(), 60_000L,
                        180_000L, true, null, music));
                accentField.set(card, 0xE39ACA);
                secondaryField.set(card, 0x8B71B2);
                oldKeyField.set(null, ""); // The ripple must outlive the separate old-cover flip state.
                rippleElapsedField.set(effects.ripple, MusicCardEffects.RIPPLE_MILLIS);
                MusicCardEffects.Icon[] icons = {MusicCardEffects.Icon.PREVIOUS, MusicCardEffects.Icon.NEXT,
                        MusicCardEffects.Icon.PLAY_PAUSE, MusicCardEffects.Icon.PLAY_PAUSE, MusicCardEffects.Icon.PLAY_PAUSE};
                double[] morph = {0.0, 0.0, 0.0, .5, 1.0};
                for (int i = 0; i < icons.length; i++) {
                    MusicCapsuleCard.drawTransportIcon(canvas, icons[i], 82 + i * 140, 62, 50,
                            morph[i], 0xFFF7F7FA);
                }
                card.drawSurface(32, 134, MusicCardLayout.WIDTH * 2, MusicCardLayout.HEIGHT * 2,
                        MusicCardLayout.RADIUS * 2, 1f);
                card.drawCard(32, 134, MusicCardLayout.WIDTH * 2, MusicCardLayout.HEIGHT * 2, 1f, cover);
                effects.ripple.trigger();
                for (int i = 0; i < 10; i++) effects.ripple.tick(30.0);
                card.drawSurface(32, 448, MusicCardLayout.WIDTH * 2, MusicCardLayout.HEIGHT * 2,
                        MusicCardLayout.RADIUS * 2, 1f);
                card.drawCard(32, 448, MusicCardLayout.WIDTH * 2, MusicCardLayout.HEIGHT * 2, 1f, cover);
                int save = canvas.save();
                try {
                    canvas.translate(32, 782);
                    canvas.scale(2, 2);
                    card.drawRow(0, 0, 196, MusicCapsuleCard.ROW_HEIGHT, 1f, cover);
                    assertDoesNotThrow(() -> card.drawRow(0, 0, 0, 0, 1f, (Image) null));
                } finally {
                    canvas.restoreToCount(save);
                }
                // A following plain fill must not inherit any wave mask/shader or the card clip.
                try (Paint paint = new Paint().setColor(0xFFFFFFFF)) {
                    canvas.drawRect(Rect.makeXYWH(2, 2, 2, 2), paint);
                }
                canvas.drawRect(Rect.makeXYWH(6, 2, 2, 2), SkijaGfx.fill(0xFFFFFFFF));
                assertTrue(bitmap.allocN32Pixels(760, 842, false));
                assertTrue(sheet.readPixels(bitmap, 0, 0));
                for (int i = 0; i < icons.length; i++) {
                    if (icons[i] == MusicCardEffects.Icon.PLAY_PAUSE) assertInkAreaCentered(bitmap, 82 + i * 140, 62);
                    if (icons[i] != MusicCardEffects.Icon.PLAY_PAUSE || morph[i] == 1.0) {
                        assertInkCentered(bitmap, 82 + i * 140, 62);
                    }
                }
                assertEquals(0xFFFFFFFF, bitmap.getColor(2, 2));
                assertEquals(0xFFFFFFFF, bitmap.getColor(6, 2));
                assertEquals(0, bitmap.getColor(5, 2), "blur mask must not bleed into the next plain fill");
                assertEquals(0, bitmap.getColor(33, 135), "outer continuous rounded clip must hold");
                assertEquals(0, bitmap.getColor(33, 449), "wave cannot fill the transparent outer corner");
                for (int y = 22; y < 58; y++) for (int x = 22; x < 58; x++) {
                    if (!MusicCardShape.contains(x, y, 16, 16, 48, 48, 10.5)) continue;
                    int still = bitmap.getColor(32 + x * 2, 134 + y * 2);
                    int wave = bitmap.getColor(32 + x * 2, 448 + y * 2);
                    assertEquals(still, wave, "wave is background, never an overlay on the cover");
                    assertTrue(green(still) > red(still) + 25, "center crop must exclude the red image margins");
                }
                assertTrue(green(bitmap.getColor(112, 214)) > red(bitmap.getColor(112, 214)) + 25);
                int corner = bitmap.getColor(66, 168);
                assertTrue(green(corner) < red(corner) + 10, "rounded cover must leave its top-left corner uncovered");
                int changed = 0;
                for (int y = 2; y < 105; y++) for (int x = 2; x < 118; x++) {
                    if (MusicCardShape.contains(x, y, 16, 16, 48, 48, 10.5)) continue;
                    if (bitmap.getColor(32 + x * 2, 134 + y * 2)
                            != bitmap.getColor(32 + x * 2, 448 + y * 2)) changed++;
                }
                assertTrue(changed > 50, "a real visible expanding wave must reach the card background");
                int rowCenter = bitmap.getColor(64, 808), rowCorner = bitmap.getColor(46, 790);
                assertTrue(green(rowCenter) > red(rowCenter) + 25, "collapsed row must show its supplied cover");
                assertEquals(0, rowCorner >>> 24, "row cover uses the same rounded production path");
                assertEquals(0, bitmap.getColor(180, 830), "single music row must not draw a progress rail along the bottom");
                String output = System.getProperty("muonium.island.effectsPreviewDir");
                if (output != null && !output.isBlank()) {
                    Path target = Path.of(output).resolve("music-card-effects.png");
                    Files.createDirectories(target.getParent());
                    try (Image image = sheet.makeImageSnapshot(); Data png = EncoderPNG.encode(image)) {
                        assertNotNull(png);
                        Files.write(target, png.getBytes());
                    }
                }
            }
        } finally {
            canvasField.set(null, oldCanvas);
            inFrameField.set(null, oldInFrame);
            snapshotField.set(null, oldSnapshot);
            accentField.set(card, oldAccent);
            secondaryField.set(card, oldSecondary);
            oldKeyField.set(null, oldKey);
            rippleElapsedField.set(effects.ripple, oldElapsed);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    @Test
    void productionPlayPauseControlHasCenteredInkAtRealSizeThroughMorphHoverPressAndScale() throws Exception {
        MusicCapsuleCard card = MusicCapsuleCard.INSTANCE;
        MusicCardEffects effects = (MusicCardEffects) field(MusicCapsuleCard.class, "effects").get(card);
        Field canvasField = field(SkijaHost.class, "canvas"), inFrameField = field(SkijaHost.class, "inFrame");
        Object oldCanvas = canvasField.get(null), oldInFrame = inFrameField.get(null);
        float[] hover = (float[]) field(MusicCapsuleCard.class, "hoverAmounts").get(card);
        float[] press = (float[]) field(MusicCapsuleCard.class, "pressAmounts").get(card);
        float[] oldHover = hover.clone(), oldPress = press.clone();
        String[] morphFields = {"initialized", "targetPlaying", "pendingResult", "resultRemaining", "pauseAmount"};
        String[] tapFields = {"amount", "velocity", "elapsed"};
        Object[] oldMorph = new Object[morphFields.length], oldTap = new Object[tapFields.length];
        for (int i = 0; i < morphFields.length; i++) oldMorph[i] = field(MusicCardEffects.Morph.class, morphFields[i]).get(effects.playback);
        for (int i = 0; i < tapFields.length; i++) oldTap[i] = field(MusicCardEffects.Tap.class, tapFields[i]).get(effects.playPause);
        int oldColor = SkijaGfx.color();
        io.github.humbleui.skija.BlendMode oldBlend = SkijaGfx.blendMode();
        java.lang.reflect.Method control = MusicCapsuleCard.class.getDeclaredMethod("drawControl",
                MusicCardLayout.Target.class, boolean.class, float.class, CloudMusic.PlaybackSnapshot.class);
        java.lang.reflect.Method disc = MusicCapsuleCard.class.getDeclaredMethod("drawTransportDisc",
                double.class, double.class, double.class, float.class, double.class, double.class);
        control.setAccessible(true);
        disc.setAccessible(true);
        int index = MusicCardLayout.Target.PLAY_PAUSE.ordinal();
        try (Surface surface = Surface.makeRasterN32Premul(160, 160);
             Surface baseline = Surface.makeRasterN32Premul(160, 160);
             Bitmap bitmap = new Bitmap(); Bitmap base = new Bitmap()) {
            assertTrue(bitmap.allocN32Pixels(160, 160, false));
            assertTrue(base.allocN32Pixels(160, 160, false));
            inFrameField.set(null, true);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.SRC_OVER);
            field(MusicCardEffects.Morph.class, "initialized").set(effects.playback, true);
            field(MusicCardEffects.Morph.class, "pendingResult").set(effects.playback, false);
            field(MusicCardEffects.Tap.class, "velocity").set(effects.playPause, 0.0);
            field(MusicCardEffects.Tap.class, "elapsed").set(effects.playPause, MusicCardEffects.TAP_MILLIS);
            for (double scale : new double[]{.6, 1.0, 1.35, 2.0}) {
                for (double feedback : new double[]{0.0, .65}) {
                    hover[index] = (float) feedback;
                    press[index] = (float) (feedback * .5);
                    field(MusicCardEffects.Tap.class, "amount").set(effects.playPause, feedback * .5);
                    for (int phase = 0; phase <= 20; phase++) {
                        double p = phase / 20.0;
                        field(MusicCardEffects.Morph.class, "pauseAmount").set(effects.playback, p);
                        CloudMusic.PlaybackSnapshot snapshot = new CloudMusic.PlaybackSnapshot(9L, 0L,
                                0L, 0L, p >= .5, null, null);
                        double centerX = 80.25, centerY = 79.65;
                        for (int pass = 0; pass < 2; pass++) {
                            Canvas canvas = (pass == 0 ? baseline : surface).getCanvas();
                            canvas.clear(0xFF10121B);
                            canvasField.set(null, canvas);
                            int depth = canvas.save();
                            try {
                                canvas.translate((float) (centerX - MusicCardLayout.controlX(MusicCardLayout.Target.PLAY_PAUSE) * scale),
                                        (float) (centerY - MusicCardLayout.CONTROLS_Y * scale));
                                canvas.scale((float) scale, (float) scale);
                                if (pass == 0) disc.invoke(card, MusicCardLayout.controlX(MusicCardLayout.Target.PLAY_PAUSE),
                                        MusicCardLayout.CONTROLS_Y, MusicCardLayout.PLAY_DISC_RADIUS, 1f,
                                        hover[index], Math.max(press[index], feedback * .5));
                                else control.invoke(card, MusicCardLayout.Target.PLAY_PAUSE, true, 1f, snapshot);
                            } finally {
                                canvas.restoreToCount(depth);
                            }
                        }
                        assertTrue(surface.readPixels(bitmap, 0, 0));
                        assertTrue(baseline.readPixels(base, 0, 0));
                        double weight = 0.0, sumX = 0.0, sumY = 0.0;
                        int bright = 0;
                        for (int y = 45; y < 115; y++) for (int x = 45; x < 115; x++) {
                            int rendered = bitmap.getColor(x, y), background = base.getColor(x, y);
                            if (red(rendered) > 205 && green(rendered) > 205) bright++;
                            double pixelWeight = 0.0;
                            for (int channel = 0; channel < 3; channel++) {
                                int shift = channel * 8;
                                int foreground = channel == 0 ? 250 : 247;
                                double b = (background >>> shift) & 255;
                                double a = (rendered >>> shift) & 255;
                                pixelWeight += Math.max(0.0, Math.min(1.0, (a - b) / (foreground - b))) / 3.0;
                            }
                            weight += pixelWeight;
                            sumX += pixelWeight * (x + .5);
                            sumY += pixelWeight * (y + .5);
                        }
                        assertTrue(bright > 15 && weight > 20.0, "real-size production icon cannot be empty");
                        String sample = "phase=" + p + ", scale=" + scale + ", feedback=" + feedback;
                        assertEquals(centerX, sumX / weight, .55, "optical horizontal center " + sample);
                        assertEquals(centerY, sumY / weight, .55, "optical vertical center " + sample);
                    }
                }
            }
        } finally {
            System.arraycopy(oldHover, 0, hover, 0, hover.length);
            System.arraycopy(oldPress, 0, press, 0, press.length);
            for (int i = 0; i < morphFields.length; i++) field(MusicCardEffects.Morph.class, morphFields[i]).set(effects.playback, oldMorph[i]);
            for (int i = 0; i < tapFields.length; i++) field(MusicCardEffects.Tap.class, tapFields[i]).set(effects.playPause, oldTap[i]);
            SkijaGfx.setColor(oldColor);
            SkijaGfx.setBlendMode(oldBlend);
            canvasField.set(null, oldCanvas);
            inFrameField.set(null, oldInFrame);
        }
    }

    @Test
    void unavailableCoverDowngradesAtDeadlineWithoutReusingAnotherTracksLocation() throws Exception {
        MusicCapsuleCard card = MusicCapsuleCard.INSTANCE;
        MusicCardEffects effects = (MusicCardEffects) field(MusicCapsuleCard.class, "effects").get(card);
        String[] statics = {"frameSnapshot", "heldSnapshot", "heldTrackKey", "heldSessionId",
                "previousTrackKey", "previousCoverLocation", "previousCoverTextureId"};
        String[] members = {"trackKey", "coverTrackKey", "coverLocation", "flipCoverLocation",
                "coverTextureId", "trackFlipPending", "coverFlipProgress"};
        Object[] oldStatics = new Object[statics.length], oldMembers = new Object[members.length];
        for (int i = 0; i < statics.length; i++) oldStatics[i] = field(MusicCapsuleCard.class, statics[i]).get(null);
        for (int i = 0; i < members.length; i++) oldMembers[i] = field(MusicCapsuleCard.class, members[i]).get(card);
        Field waitElapsed = field(MusicCardEffects.CoverWait.class, "elapsed"), rippleElapsed = field(MusicCardEffects.Ripple.class, "elapsed");
        Object oldWait = waitElapsed.get(effects.coverWait), oldRipple = rippleElapsed.get(effects.ripple);
        com.muoniumplayer.core.utils.Location staleLocation = com.muoniumplayer.core.utils.Location.of("old-song-cover");
        com.muoniumplayer.core.rendering.texture.ITextureObject staleTexture = new com.muoniumplayer.core.rendering.texture.ITextureObject() {
            public void setBlurMipmap(boolean blur, boolean mipmap) { }
            public void restoreLastBlurMipmap() { }
            public int getGlTextureId() { return 7352421; }
            public void linearFilter() { }
            public void nearestFilter() { }
        };
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, int[]> sizes = (java.util.Map<Integer, int[]>) field(
                com.muoniumplayer.render.skija.SkijaImages.class, "SIZES").get(null);
        int[] oldSize = sizes.put(7352421, new int[]{16, 16});
        com.muoniumplayer.core.rendering.texture.ITextureObject oldTexture = TextureManager.getInstance()
                .mapTextureObjects.put(staleLocation, staleTexture);
        try {
            Music music = new Music("Missing art", "Missing art", "", 752396134L, Collections.emptyList(),
                    Collections.emptyList(), null, 180_000L, 0L, 0L, Collections.emptyList());
            music.setSource(MusicPlatform.QQ);
            music.setSourceId("missing-art-deadline-probe");
            CloudMusic.PlaybackSnapshot snapshot = new CloudMusic.PlaybackSnapshot(852396134L, music.getId(),
                    0L, 180_000L, true, null, music);
            field(MusicCapsuleCard.class, "frameSnapshot").set(null, snapshot);
            field(MusicCapsuleCard.class, "heldSnapshot").set(null, snapshot);
            field(MusicCapsuleCard.class, "heldTrackKey").set(null, music.getStableKey());
            field(MusicCapsuleCard.class, "heldSessionId").set(null, snapshot.sessionId);
            field(MusicCapsuleCard.class, "previousTrackKey").set(null, "old-song");
            field(MusicCapsuleCard.class, "previousCoverTextureId").set(null, 7352421);
            field(MusicCapsuleCard.class, "trackKey").set(card, music.getStableKey());
            field(MusicCapsuleCard.class, "coverTrackKey").set(card, "old-song");
            field(MusicCapsuleCard.class, "coverLocation").set(card, staleLocation);
            field(MusicCapsuleCard.class, "flipCoverLocation").set(card, null);
            field(MusicCapsuleCard.class, "trackFlipPending").set(card, true);
            field(MusicCapsuleCard.class, "coverFlipProgress").set(card, 0.0);
            effects.coverWait.begin();
            rippleElapsed.set(effects.ripple, MusicCardEffects.RIPPLE_MILLIS);
            assertFalse(MusicCapsuleCard.trackCoverReady(snapshot));
            for (int i = 0; i < 19; i++) effects.coverWait.tick(40.0);
            MusicCapsuleCard.tickTrackTransition(40f);
            assertTrue((Boolean) field(MusicCapsuleCard.class, "trackFlipPending").get(card));
            assertFalse(effects.ripple.active(), "wave waits for actual cover flip or explicit placeholder downgrade");
            effects.coverWait.tick(40.0);
            assertTrue(MusicCapsuleCard.trackCoverReady(snapshot));
            MusicCapsuleCard.tickTrackTransition(0f);
            assertTrue((Boolean) field(MusicCapsuleCard.class, "trackFlipPending").get(card),
                    "unpresented cover must not spend its flip or placeholder transition");
            assertFalse(effects.ripple.active());
            field(MusicCapsuleCard.class, "trackKey").set(card, "a-newer-track");
            MusicCapsuleCard.tickTrackTransition(40f);
            assertTrue((Boolean) field(MusicCapsuleCard.class, "trackFlipPending").get(card), "stale deadline must not affect a newer track");
            field(MusicCapsuleCard.class, "trackKey").set(card, music.getStableKey());
            MusicCapsuleCard.tickTrackTransition(40f);
            assertFalse((Boolean) field(MusicCapsuleCard.class, "trackFlipPending").get(card));
            assertEquals("", field(MusicCapsuleCard.class, "previousTrackKey").get(null));
            assertEquals(0, field(MusicCapsuleCard.class, "previousCoverTextureId").get(null));
            assertEquals(1.0, field(MusicCapsuleCard.class, "coverFlipProgress").get(card));
            assertEquals(music.getSmallCoverLocation(), field(MusicCapsuleCard.class, "coverLocation").get(card));
            assertTrue(effects.ripple.active());
            for (int i = 0; i < 11; i++) effects.ripple.tick(40.0);
            MusicCapsuleCard.tickTrackTransition(40f);
            assertTrue(effects.ripple.active(), "cleared old-track state must not shorten ripple lifecycle");
            java.lang.reflect.Method valid = MusicCapsuleCard.class.getDeclaredMethod("validTexture", int.class, int[].class);
            valid.setAccessible(true);
            assertEquals(false, valid.invoke(null, 7352421, null));
            assertEquals(false, valid.invoke(null, 7352421, new int[]{16}));
            assertEquals(false, valid.invoke(null, 7352421, new int[]{0, 16}));
            assertEquals(false, valid.invoke(null, 0, new int[]{16, 16}));
        } finally {
            for (int i = 0; i < statics.length; i++) field(MusicCapsuleCard.class, statics[i]).set(null, oldStatics[i]);
            for (int i = 0; i < members.length; i++) field(MusicCapsuleCard.class, members[i]).set(card, oldMembers[i]);
            waitElapsed.set(effects.coverWait, oldWait);
            rippleElapsed.set(effects.ripple, oldRipple);
            if (oldSize == null) sizes.remove(7352421); else sizes.put(7352421, oldSize);
            if (oldTexture == null) TextureManager.getInstance().mapTextureObjects.remove(staleLocation);
            else TextureManager.getInstance().mapTextureObjects.put(staleLocation, oldTexture);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int green(int color) { return (color >>> 8) & 255; }
    private static int red(int color) { return (color >>> 16) & 255; }

    private static void assertInkAreaCentered(Bitmap bitmap, int centerX, int centerY) {
        double total = 0.0, horizontal = 0.0, vertical = 0.0;
        for (int y = centerY - 35; y < centerY + 35; y++) for (int x = centerX - 35; x < centerX + 35; x++) {
            double weight = (bitmap.getColor(x, y) >>> 24) / 255.0;
            total += weight;
            horizontal += weight * (x + .5);
            vertical += weight * (y + .5);
        }
        assertTrue(total > 600.0, "play/pause icon must retain its real filled area");
        assertEquals(centerX, horizontal / total, .35, "filled icon area centered horizontally");
        assertEquals(centerY, vertical / total, .35, "filled icon area centered vertically");
    }

    private static void assertInkCentered(Bitmap bitmap, int centerX, int centerY) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int count = 0;
        for (int y = centerY - 35; y < centerY + 35; y++) for (int x = centerX - 35; x < centerX + 35; x++) {
            if ((bitmap.getColor(x, y) >>> 24) < 128) continue;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            count++;
        }
        assertTrue(count > 300, "transport icon must have real visible ink");
        assertEquals(centerX, (minX + maxX + 1) * .5, .5, "ink bounds centered horizontally");
        assertEquals(centerY, (minY + maxY + 1) * .5, .5, "ink bounds centered vertically");
    }
}
