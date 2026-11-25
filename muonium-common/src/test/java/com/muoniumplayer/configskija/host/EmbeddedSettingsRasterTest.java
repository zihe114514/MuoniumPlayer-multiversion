package com.muoniumplayer.configskija.host;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema;
import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.skija.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** CPU snapshots of the real host categories. No Minecraft window, GL context or user input. */
@EnabledIfSystemProperty(named = "muonium.config.probes", matches = "true")
class EmbeddedSettingsRasterTest {
    @Test
    void renderEveryActualSettingsCategory() throws Exception { ConfigProbeProcess.run(EmbeddedSettingsRasterTest.class, "raster"); }

    public static void main(String[] args) throws Exception { new EmbeddedSettingsRasterTest().renderCategories(); }

    private void renderCategories() throws Exception {
        Path output = ProjectPaths.versionRoot().resolve("build/embedded-config-probes");
        Files.createDirectories(output);
        UiPreferences preferences = new UiPreferences(Map.of("guiOpenAnimation", "false", "showPageAnimations", "false", "instantSearch", "false", "rememberLastPage", "0"), () -> {});
        MuoniumHost actual = new MuoniumHost(() -> {}, preferences);
        ConfigView view = new ConfigView(new ProbeHost(actual), preferences);
        Path fontRoot = ProjectPaths.sharedResources().resolve("muonium/fonts");
        Map<String, Font> fonts = new HashMap<>();
        try (Typeface regular = FontMgr.getDefault().makeFromFile(fontRoot.resolve("pf_normal.ttf").toString());
             Typeface bold = FontMgr.getDefault().makeFromFile(fontRoot.resolve("pf_middleblack.ttf").toString())) {
            assertNotNull(regular); assertNotNull(bold);
            Map<String, SkijaPainter.Svg> icons = SkijaPainter.loadIcons();
            int index = 0;
            for (ConfigHost.Category category : actual.categories()) {
                view.navigate(new Navigation.Route(Navigation.Page.SETTINGS, category.id()));
                try (Surface surface = Surface.makeRasterN32Premul(1728, 960); Paint ink = new Paint().setAntiAlias(true)) {
                    Canvas canvas = surface.getCanvas();
                    SkijaPainter.Text text = new SkijaPainter.Text() {
                        Font font(double size, boolean heavy) { return fonts.computeIfAbsent(size + ":" + heavy, key -> new Font(heavy ? bold : regular, (float)size)); }
                        public void draw(String value, double x, double y, double size, boolean heavy, int color) { Font f = font(size, heavy); canvas.drawString(value, (float)x, (float)(y - f.getMetrics().getAscent()), f, ink.setColor(color)); }
                        public double measure(String value, double size, boolean heavy) { return font(size, heavy).measureTextWidth(value); }
                        public Painter.TextBounds textBounds(String value, double size, boolean heavy) {
                            Font f = font(size, heavy); FontMetrics metrics = f.getMetrics();
                            io.github.humbleui.types.Rect ink = f.measureText(value);
                            // drawString uses baseline y - ascent; bounds must use that same line-top origin.
                            return ink.getWidth() > 0 && ink.getHeight() > 0
                                    ? new Painter.TextBounds(ink.getTop() - metrics.getAscent(), ink.getBottom() - metrics.getAscent())
                                    : new Painter.TextBounds(0, metrics.getDescent() - metrics.getAscent());
                        }
                    };
                    try (SkijaPainter painter = new SkijaPainter(() -> canvas, text, icons, () -> Theme.active(preferences))) {
                        canvas.clear(0xFF0D1117);
                        view.frame(painter, 1728, 960, -100, -100, ++index * 10d);
                        view.frame(painter, 1728, 960, -100, -100, index * 10d + 2);
                    }
                    try (Image image = surface.makeImageSnapshot(); Data png = image.encodeToData(EncodedImageFormat.PNG)) {
                        assertNotNull(png);
                        Files.write(output.resolve(String.format("%02d-%s.png", index, category.title())), png.getBytes());
                    }
                }
            }
            assertEquals(actual.categories().size(), index);
        } finally { fonts.values().forEach(Font::close); }
    }

    private record ProbeHost(MuoniumHost actual) implements ConfigHost, SettingsSchema.Provider {
        public List<Category> categories() { return actual.categories(); }
        public List<HudElement> huds() { return List.of(); }
        public SettingsSchema settingsSchema() {
            return new SettingsSchema() {
                public OptionStyle option(Option option) { return actual.settingsSchema().option(option); }
            };
        }
        public void persist() { throw new AssertionError("Rendering must not save player settings"); }
        public void close() {}
        public String clipboard() { return ""; }
        public void clipboard(String value) { throw new AssertionError("Rendering must not use clipboard"); }
        public void error(String action, Throwable error) { throw new AssertionError(action, error); }
        public String playerName() { return "离屏探针 · 真实播放器设置"; }
    }
}
