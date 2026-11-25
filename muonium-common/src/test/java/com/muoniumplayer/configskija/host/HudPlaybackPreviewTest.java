package com.muoniumplayer.configskija.host;

import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.modules.values.ValueManager;
import com.muoniumplayer.api.interfaces.render.*;
import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.skija.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual playing-lyrics preview with a silent engine fixture; no game/window/audio device. */
@EnabledOnOs(OS.WINDOWS)
class HudPlaybackPreviewTest {
    @Test
    void playingLyricsCannotScaleOrClipEditorChrome() throws Exception {
        ConfigProbeProcess.run(HudPlaybackPreviewTest.class, "playback");
    }

    public static void main(String[] args) throws Exception {
        OpenAPI.setInstance(new OpenAPI() {
            public void registerEvent(EventHandler h) {}
            public void unregisterEvent(EventHandler h) {}
            public void registerFeature(Object f) {}
            public void displayScreen(ExtensionScreen s) {}
            public void printMessage(String s) {}
            public ValueManager getValueManager() { return new com.muoniumplayer.api.impl.ValueManagerImpl(); }
            public GLStateManager getGLStateManager() { return new com.muoniumplayer.api.impl.GLStateManagerImpl(); }
            public ShaderUtil getShaderUtil() { return null; }
            public FontUtil getFontUtil() { return null; }
            public RenderUtil getRenderUtil() { return null; }
        });
        assertTrue(SkijaNatives.ensureLoaded());
        FontManager.loadFonts();
        MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe)unsafeField.get(null);
        // Allocate only scalar playback state; no AudioPlayer constructor, decoding or output is run.
        CloudMusic.player = (AudioPlayer)unsafe.allocateInstance(AudioPlayer.class);
        CloudMusic.currentlyPlaying = new Music("播放中的离屏预览", "播放中的离屏预览", "", 1234L,
                List.of(), List.of(), null, 180000L, 0L, 0L, List.of());
        LyricLine line = new LyricLine(0, "歌词预览不能改变工具栏的大小");
        CloudMusic.lyrics.clear(); CloudMusic.lyrics.add(line); CloudMusic.currentLyric = line;
        CloudMusic.lyricsTrackKey = CloudMusic.currentlyPlaying.getStableKey();
        CloudMusic.haveNoWords = true;
        HudConfig.musicLyricsEnabled = true; HudConfig.musicInfoEnabled = false;
        lyrics.dynIsland.setValue(false);
        assertTrue(CloudMusic.hasLiveTrack());
        try (Surface surface = Surface.makeRasterN32Premul(1920, 1080)) {
            assertTrue(SkijaGfx.renderTo(surface.getCanvas(), () -> {
                Canvas canvas = surface.getCanvas();
                canvas.scale(2, 2);
                float[] before = canvas.getLocalToDeviceAsMatrix33().getMat().clone();
                int saves = canvas.getSaveCount(), depth = SkijaGfx.saveDepth();
                for (int style : new int[] {0, 1}) for (float scale : new float[] {.25f, .608f, 1f, 1.7f}) {
                    HudConfig.osdLyricStyle = style; HudConfig.lyricScale = scale;
                    lyrics.renderEditorPreview(20, 180, scale);
                    assertEquals(depth, SkijaGfx.saveDepth(), "playing lyric preview leaked a host matrix");
                    assertEquals(saves, canvas.getSaveCount(), "playing lyric preview leaked a canvas save");
                    assertArrayEquals(before, canvas.getLocalToDeviceAsMatrix33().getMat(), .0001f,
                            "editor chrome inherited the lyric scale/translation");
                }
            }));
        }
        renderEditor(lyrics);
    }

    private static void renderEditor(MusicLyricsWidget lyrics) throws Exception {
        HudConfig.osdLyricStyle = 0; HudConfig.lyricScale = .608f;
        UiPreferences prefs = new UiPreferences(Map.of("rememberLastPage", "0", "guiOpenAnimation", "false", "showPageAnimations", "false", "hud.tour.seen", "true"), () -> {});
        MuoniumHost actual = new MuoniumHost(() -> {}, prefs);
        double[] time = {1};
        ConfigHost host = (ConfigHost)Proxy.newProxyInstance(ConfigHost.class.getClassLoader(), new Class<?>[] {ConfigHost.class, HudExtensions.class}, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "uiDensity": return .5;
                case "physicalPerLayout": return 2d;
                case "hudFrame": return HudRuntime.Frame.editor(960, 540, time[0]).withPhysicalScale(2);
                case "persist": case "sound": case "dispose": case "clipboard": return method.getReturnType() == String.class ? "" : null;
                case "translate": return arguments[1];
                case "playerHead": return null;
                case "playerName": return "CPU playback fixture";
                case "playerKey": case "editorKey": return "";
                default: return method.invoke(actual, arguments);
            }
        });
        ConfigView view = new ConfigView(host, prefs);
        view.navigate(new Navigation.Route(Navigation.Page.HUD));
        var output = ProjectPaths.versionRoot().resolve("build/embedded-config-probes/hud-playing-classic.png");
        Files.createDirectories(output.getParent());
        try (Surface surface = Surface.makeRasterN32Premul(1920, 1080)) {
            Canvas canvas = surface.getCanvas();
            var assets = com.muoniumplayer.configskija.host.text.HostTextRenderer.loadAssets();
            try (var renderer = com.muoniumplayer.configskija.host.text.HostTextRenderer.create(assets, () -> canvas, () -> Theme.active(prefs));
                 SkijaPainter painter = new SkijaPainter(() -> canvas, renderer, SkijaPainter.loadIcons())) {
                actual.textRenderer(renderer);
                assertTrue(SkijaGfx.renderTo(canvas, () -> {
                    canvas.scale(2, 2);
                    for (int frame = 0; frame < 3; frame++) {
                        canvas.clear(0xFF11171C); time[0] = frame + 1;
                        view.frame(painter, 960, 540, -100, -100, time[0]);
                    }
                    // Every visible button must retain a useful physical target after real host preview.
                    assertTrue(view.hitBounds().entrySet().stream().anyMatch(e -> e.getKey().startsWith("hud:") && e.getValue().w() * 2 >= 40 && e.getValue().h() * 2 >= 24));
                    assertEquals(1, canvas.getSaveCount(), "editor frame must balance canvas layers");
                }));
            }
            try (Image image = surface.makeImageSnapshot(); Data png = image.encodeToData(EncodedImageFormat.PNG)) {
                assertNotNull(png); Files.write(output, png.getBytes());
            }
        }
    }
}
