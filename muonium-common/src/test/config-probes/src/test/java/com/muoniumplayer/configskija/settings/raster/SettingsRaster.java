package com.muoniumplayer.configskija.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.settings.*;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;
import io.github.humbleui.skija.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Actual host Skija raster rendering. Fixture images are not an in-game parity certification. */
public final class SettingsRaster {
    public static void main(String[] args) throws Exception {
        Path music = Path.of(args[0]), output = Path.of(args[1]); Files.createDirectories(output);
        try (Typeface regular = FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_normal.ttf").toString()); Typeface heavy = FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_middleblack.ttf").toString())) {
            if (regular == null || heavy == null) throw new IllegalStateException("Host font unavailable");
            Map<String, Font> fonts = new HashMap<>(); Map<String, SkijaPainter.Svg> icons = SkijaPainter.loadIcons();
            try {
                SettingsHarness.Fixture host = new SettingsHarness.Fixture(); ConfigHost.Option toggle = host.add("music.enabled", ConfigHost.Kind.TOGGLE, true), slider = host.add("music.volume", ConfigHost.Kind.SLIDER, 64.25d), choice = host.add("music.mode", ConfigHost.Kind.CHOICE, "Second"), color = host.add("lyrics.color", ConfigHost.Kind.COLOR, 0x80339acc);
                host.titles.put(toggle.id(), "音乐播放"); host.titles.put(slider.id(), "播放器音量"); host.titles.put(choice.id(), "播放模式"); host.titles.put(color.id(), "歌词颜色"); host.category("music", "播放器", toggle, slider, choice, color); host.category("hud", "HUD 叠加层", host.add("hud.enabled", ConfigHost.Kind.TOGGLE, false));
                SettingsHarness.Context ctx = new SettingsHarness.Context(host, false); render(ctx, regular, heavy, fonts, icons, output.resolve("settings.png"));
                ctx.click("settings.choice.music.mode"); render(ctx, regular, heavy, fonts, icons, output.resolve("dropdown.png")); ctx.dismissOverlay();
                ctx.click("settings.color.lyrics.color"); render(ctx, regular, heavy, fonts, icons, output.resolve("color.png")); ctx.dismissOverlay();
                SettingsHarness.Fixture accordion = new SettingsHarness.Fixture(); ConfigHost.Option a = accordion.add("a", ConfigHost.Kind.TOGGLE, true), b = accordion.add("b", ConfigHost.Kind.TOGGLE, false), c = accordion.add("c", ConfigHost.Kind.SLIDER, 33d); accordion.category("accordion", "Settings", a, b, c); accordion.groups.put("accordion.group", new GroupStyle(true, false, null, "Accordion subtitle", "settings")); render(new SettingsHarness.Context(accordion, false), regular, heavy, fonts, icons, output.resolve("accordion.png"));
                SettingsHarness.Fixture lists = new SettingsHarness.Fixture(); ConfigHost.Option list = lists.add("names", ConfigHost.Kind.CHOICE, new String[] {"Duplicate", "Duplicate", "Another entry"}); lists.styles.get(list.id()).visualizer = Visualizer.TEXT_LIST; lists.category("lists", "Lists", list); render(new SettingsHarness.Context(lists, false), regular, heavy, fonts, icons, output.resolve("text-list.png"));
            } finally { for (Font font : fonts.values()) font.close(); }
        }
        System.out.println("Settings raster passed: 5 fixtures in " + output);
    }
    private static void render(SettingsHarness.Context ctx, Typeface regular, Typeface heavy, Map<String, Font> fonts, Map<String, SkijaPainter.Svg> icons, Path output) throws Exception {
        try (Surface surface = Surface.makeRasterN32Premul(1391, 700); Paint paint = new Paint().setAntiAlias(true)) {
            Canvas canvas = surface.getCanvas(); canvas.clear(0xff11171c);
            try (SkijaPainter painter = new SkijaPainter(() -> canvas, new SkijaPainter.Text() {
                Font font(double size, boolean bold) { return fonts.computeIfAbsent(size + ":" + bold, k -> new Font(bold ? heavy : regular, (float)size)); }
                public void draw(String text, double x, double y, double size, boolean bold, int color) { Font font = font(size, bold); canvas.drawString(text, (float)x, (float)(y - font.getMetrics().getAscent()), font, paint.setColor(color)); }
                public double measure(String text, double size, boolean bold) { return font(size, bold).measureTextWidth(text); }
            }, icons)) { ctx.drawing = painter; ctx.frame(); ctx.drawing = null; }
            try (Image snapshot = surface.makeImageSnapshot(); Data encoded = snapshot.encodeToData(EncodedImageFormat.PNG)) { if (encoded == null) throw new AssertionError("PNG encode failed"); Files.write(output, encoded.getBytes()); }
        }
    }
}
