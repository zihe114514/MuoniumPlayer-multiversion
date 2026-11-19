package com.muoniumplayer.core.rendering;

import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.modules.values.ValueManager;
import com.muoniumplayer.api.interfaces.render.FontUtil;
import com.muoniumplayer.api.interfaces.render.GLStateManager;
import com.muoniumplayer.api.interfaces.render.RenderUtil;
import com.muoniumplayer.api.interfaces.render.ShaderUtil;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.panels.LikeButton;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaNatives;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class MusicCardRenderTest {
    @BeforeAll
    static void fonts() {
        assertTrue(SkijaNatives.ensureLoaded());
        if (OpenAPI.getInstance() == null) OpenAPI.setInstance(new OpenAPI() {
            public void registerEvent(EventHandler handler) { }
            public void unregisterEvent(EventHandler handler) { }
            public void registerFeature(Object feature) { }
            public void displayScreen(ExtensionScreen screen) { }
            public void printMessage(String message) { }
            public ValueManager getValueManager() { return new com.muoniumplayer.api.impl.ValueManagerImpl(); }
            public GLStateManager getGLStateManager() { return new com.muoniumplayer.api.impl.GLStateManagerImpl(); }
            public ShaderUtil getShaderUtil() { return null; }
            public FontUtil getFontUtil() { return null; }
            public RenderUtil getRenderUtil() { return null; }
        });
        FontManager.loadFonts();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Music music(MusicPlatform platform) {
        String title = "命运却要我们危难中相爱 · 一首很长的歌名用于卡片截断";
        Music music = new Music(title, title, "", 762341L, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), null, 235_000L, 0L, 0L, java.util.Collections.emptyList());
        music.setSource(platform);
        music.setSourceId("island-render-test");
        music.setArtistsName("G.E.M.邓紫棋");
        music.setCoverPalette(new int[]{0xB94E8C, 0x4055A8, 0xC49B49});
        return music;
    }

    @Test
    void productionCardRendersReadableContentAndReleasesItsRoundedClip() throws Exception {
        Field canvasField = field(SkijaHost.class, "canvas");
        Field frameField = field(SkijaHost.class, "inFrame");
        Field snapshotField = field(MusicCapsuleCard.class, "frameSnapshot");
        Object oldCanvas = canvasField.get(null);
        Object oldFrame = frameField.get(null);
        Object oldSnapshot = snapshotField.get(null);
        double oldDelta = RenderSystem.getFrameDeltaTime();
        MusicCapsuleCard card = MusicCapsuleCard.INSTANCE;
        String oldHeadline = (String) field(MusicCapsuleCard.class, "headline").get(card);
        Object oldFade = field(MusicCapsuleCard.class, "headlineFade").get(card);
        try {
            RenderSystem.setFrameDeltaTime(1.6);
            for (MusicPlatform platform : new MusicPlatform[]{MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.APPLE}) {
                snapshotField.set(null, new CloudMusic.PlaybackSnapshot(921L, 762341L, 54_000L,
                        235_000L, true, null, music(platform)));
                field(MusicCapsuleCard.class, "accent").set(card, 0xEBA4DB);
                field(MusicCapsuleCard.class, "secondaryAccent").set(card, 0x7C88CA);
                field(MusicCapsuleCard.class, "headline").set(card, "命运却要我们危难中相爱");
                field(MusicCapsuleCard.class, "headlineFade").set(card, 1f);
                try (Surface surface = Surface.makeRasterN32Premul(760, 360); Bitmap bitmap = new Bitmap()) {
                    Canvas canvas = surface.getCanvas();
                    canvas.clear(0);
                    canvasField.set(null, canvas);
                    frameField.set(null, true);
                    card.drawSurface(32, 24, MusicCardLayout.WIDTH * 2, MusicCardLayout.HEIGHT * 2,
                            MusicCardLayout.RADIUS * 2, 1f);
                    card.drawCard(32, 24, MusicCardLayout.WIDTH * 2, MusicCardLayout.HEIGHT * 2, 1f, false);
                    try (Paint paint = new Paint().setColor(0xFFFFFFFF)) {
                        canvas.drawRect(Rect.makeXYWH(2, 2, 2, 2), paint);
                    }
                    assertTrue(bitmap.allocN32Pixels(760, 360, false));
                    assertTrue(surface.readPixels(bitmap, 0, 0));
                    assertEquals(0xFFFFFFFF, bitmap.getColor(2, 2), "卡片不能泄漏剪形或变换");
                    assertEquals(0, bitmap.getColor(10, 100) >>> 24);
                    assertEquals(0, bitmap.getColor(33, 25) >>> 24, "圆角外不能画出方形内容");
                    int visible = 0;
                    for (int y = 40; y < 305; y++) for (int x = 45; x < 692; x++) {
                        if ((bitmap.getColor(x, y) >>> 24) > 150) visible++;
                    }
                    assertTrue(visible > 2500, "歌词、时间、品牌和控制图标都必须真正可见");
                    int controlY = 24 + (int) (MusicCardLayout.CONTROLS_Y * 2);
                    int heartX = 32 + (int) (MusicCardLayout.controlX(MusicCardLayout.Target.LIKE) * 2);
                    int whiteHeart = countBright(bitmap, heartX - 22, controlY - 22, 44, 44);
                    assertTrue(platform != MusicPlatform.NETEASE || whiteHeart > 25,
                            "支持喜欢的平台即使贴图没有加载也必须显示爱心");
                    assertTrue(platform == MusicPlatform.NETEASE || whiteHeart < 5,
                            "不支持的平台必须腾出爱心位置");
                    int controlX = 32 + (int) (MusicCardLayout.controlX(MusicCardLayout.Target.PREVIOUS) * 2);
                    assertTrue(countBright(bitmap, controlX - 20, controlY - 20, 40, 40) > 150,
                            "上一曲图标要按墨迹大小绘制，不能缩成细小占位");
                    String output = System.getProperty("muonium.island.previewDir");
                    if (output != null && !output.isBlank()) {
                        Path target = Path.of(output).resolve("music-card-" + platform.name().toLowerCase() + ".png");
                        Files.createDirectories(target.getParent());
                        try (Image image = surface.makeImageSnapshot(); Data data = EncoderPNG.encode(image)) {
                            assertNotNull(data);
                            Files.write(target, data.getBytes());
                        }
                    }
                }
            }
        } finally {
            canvasField.set(null, oldCanvas);
            frameField.set(null, oldFrame);
            snapshotField.set(null, oldSnapshot);
            field(MusicCapsuleCard.class, "headline").set(card, oldHeadline);
            field(MusicCapsuleCard.class, "headlineFade").set(card, oldFade);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            RenderSystem.setFrameDeltaTime(oldDelta);
        }
    }

    private static int countBright(Bitmap bitmap, int left, int top, int width, int height) {
        int count = 0;
        for (int y = top; y < top + height; y++) for (int x = left; x < left + width; x++) {
            int color = bitmap.getColor(x, y);
            if (((color >>> 16) & 255) > 205 && ((color >>> 8) & 255) > 205 && (color & 255) > 205) count++;
        }
        return count;
    }

    @Test
    void unsupportedPlatformsCannotDispatchFavouriteOrOpenAConfirmation() {
        for (MusicPlatform platform : new MusicPlatform[]{MusicPlatform.QQ, MusicPlatform.APPLE,
                MusicPlatform.SPOTIFY, MusicPlatform.YOUTUBE, MusicPlatform.LOCAL, MusicPlatform.SMTC}) {
            Music music = music(platform);
            assertFalse(LikeButton.canFavourite(music));
            assertFalse(LikeButton.toggleLiked(music, () -> fail("不支持的平台不能播放爆心"),
                    (title, message, text, action) -> fail("不支持的平台不能发起取消喜欢")));
        }
        assertFalse(LikeButton.toggleLiked(null, null, null));
    }

    @Test
    void staleLyricsFallBackToCurrentTrackMetadata() {
        String oldKey = CloudMusic.lyricsTrackKey;
        Music oldMusic = CloudMusic.currentlyPlaying;
        boolean oldSetting = HudConfig.islandMusicShowsLyric;
        try {
            Music music = music(MusicPlatform.QQ);
            CloudMusic.currentlyPlaying = music;
            CloudMusic.lyricsTrackKey = "another-source:762341";
            HudConfig.islandMusicShowsLyric = true;
            CloudMusic.PlaybackSnapshot snapshot = new CloudMusic.PlaybackSnapshot(1, music.getId(), 3000, 5000,
                    false, null, music);
            assertTrue(MusicCapsuleCard.rowText(snapshot).startsWith(music.getName()));
        } finally {
            CloudMusic.lyricsTrackKey = oldKey;
            CloudMusic.currentlyPlaying = oldMusic;
            HudConfig.islandMusicShowsLyric = oldSetting;
        }
    }
}
