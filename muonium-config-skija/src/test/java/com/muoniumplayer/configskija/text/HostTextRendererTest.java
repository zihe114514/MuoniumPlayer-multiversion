package com.muoniumplayer.configskija.text;

import com.muoniumplayer.configskija.host.text.HostTextRenderer;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** CPU bitmap tests with the host's existing Skija loader; no Surface or GL context. */
public final class HostTextRendererTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double a, double b, double tolerance, String message) {
        check(Math.abs(a - b) <= tolerance, message + ": " + a + " / " + b);
    }

    private static void fails(Class<? extends Throwable> kind, Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (Throwable error) {
            check(kind.isInstance(error), message + ": " + error);
        }
    }

    private static HudRuntime.TextStyle style(
            String family,
            String weight,
            double size,
            boolean italic,
            boolean underline,
            boolean shadow,
            boolean outline,
            int align) {
        return new HudRuntime.TextStyle(
                family,
                weight,
                size,
                false,
                italic,
                underline,
                shadow,
                outline,
                0xFFFFFFFF,
                0xFFFF0000,
                3,
                4,
                align);
    }

    public static void main(String[] args) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        HostTextRenderer.LoadedAssets assets;
        Font fallback;
        try {
            assets =
                    worker.submit(
                                    () ->
                                            HostTextRenderer.loadAssets(
                                                    HostTextRendererTest.class.getClassLoader()))
                            .get();
            fallback =
                    worker.submit(
                                    () -> {
                                        byte[] data = FontManager.builtinUiFallbackBytes();
                                        Font font =
                                                Font.createFont(
                                                        Font.TRUETYPE_FONT,
                                                        new ByteArrayInputStream(data));
                                        SkijaText.register(font, data);
                                        return font;
                                    })
                            .get();
        } finally {
            worker.shutdown();
        }
        check(
                assets.minecraftRegularBytes() > 1000 && assets.minecraftBoldBytes() > 1000,
                "both original OTF assets read by worker");
        fails(
                java.io.UncheckedIOException.class,
                () -> {
                    try {
                        HostTextRenderer.loadAssets(new ClassLoader(null) {});
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                },
                "missing font fails explicitly");
        if (!SkijaNatives.ensureLoaded())
            throw new IllegalStateException(
                    "Existing host Skija runtime unavailable: " + SkijaNatives.mode());
        Font regular = FontManager.builtinTextMain(false), bold = FontManager.builtinTextMain(true);
        CFontRenderer hostRegular = new CFontRenderer(regular, 16, fallback),
                hostBold = new CFontRenderer(bold, 16, fallback);
        AtomicReference<HostTextRenderer.HostFonts> current =
                new AtomicReference<>(
                        new HostTextRenderer.HostFonts(hostRegular, hostBold, regular, bold, null));
        AtomicReference<Theme> theme = new AtomicReference<>(Theme.BUILTIN[0]);
        try (Bitmap bitmap = new Bitmap()) {
            check(bitmap.allocN32Pixels(1024, 512), "CPU bitmap allocated");
            try (Canvas canvas = new Canvas(bitmap);
                    HostTextRenderer renderer =
                            HostTextRenderer.create(
                                    assets, () -> canvas, theme::get, current::get)) {
                baseline(renderer, canvas, bitmap, hostRegular);
                styles(renderer, canvas, bitmap);
                fallback(renderer, canvas, bitmap);
                multiline(renderer, canvas, bitmap);
                hotSwitch(renderer, canvas, bitmap, current, hostRegular, regular, bold, fallback);
                systemFont(renderer, current, hostRegular, hostBold, regular, bold);
                threading(renderer);
                caches(renderer);
                check(
                        !SkijaText.typefaceFor(regular).isClosed(),
                        "borrowed host typeface stays live");
                double hostWidth = renderer.measure("Glyphs 123", 17, false);
                theme.set(Theme.BUILTIN[2]);
                double mcWidth = renderer.measure("Glyphs 123", 17, false);
                check(Math.abs(hostWidth - mcWidth) > .1, "UI theme switches font immediately");
                theme.set(Theme.BUILTIN[0]);
                renderer.close();
                check(
                        renderer.statistics().closed()
                                && renderer.statistics().fonts() == 0
                                && renderer.statistics().layouts() == 0,
                        "close releases owned caches");
                renderer.close();
                fails(
                        IllegalStateException.class,
                        () -> renderer.measure("closed", 14, false),
                        "closed renderer rejects work");
                check(
                        hostRegular.getStringWidthD("still owned by host") > 0,
                        "host renderer not closed by adapter");
                check(
                        !SkijaText.typefaceFor(regular).isClosed(),
                        "host typeface not closed by adapter");
                FontManager.pf16 = hostRegular;
                FontManager.pf16bold = hostBold;
                try (HostTextRenderer defaultHost =
                        HostTextRenderer.create(assets, () -> canvas, theme::get)) {
                    check(
                            defaultHost.measure("default host factory", 15, false) > 0,
                            "production factory reads real FontManager slots");
                }
                try (HostTextRenderer game =
                        HostTextRenderer.create(assets, () -> canvas, theme::get, current::get)) {
                    check(
                            game.measure(
                                                    "persistent HUD",
                                                    style(
                                                            "Minecraft",
                                                            "Regular",
                                                            16,
                                                            false,
                                                            false,
                                                            false,
                                                            false,
                                                            0))
                                            .width()
                                    > 0,
                            "new persistent renderer independent from closed screen renderer");
                }
            }
        } finally {
            hostRegular.close();
            hostBold.close();
        }
        System.out.println(
                "HostTextRendererTest: "
                        + checks
                        + " assertions passed; runtime="
                        + SkijaNatives.mode());
    }

    private static void baseline(HostTextRenderer r, Canvas c, Bitmap bitmap, CFontRenderer host) {
        for (double size : new double[] {8, 11, 13, 14, 15, 17.5, 22, 24, 31})
            for (boolean bold : new boolean[] {false, true}) {
                double width = r.measure("Music AV 中文", size, bold);
                check(width > 0 && Double.isFinite(width), "UI real size metrics " + size);
                c.clear(0);
                r.draw("Music AV 中文", 40, 35, size, bold, 0xFFFFFFFF);
                check(ink(bitmap).count > 0, "UI draws at size " + size);
            }
        try (CFontRenderer exact = host.deriveAtSize(17.5f)) {
            near(
                    exact.getStringWidthD("Music AV 中文"),
                    r.measure("Music AV 中文", 17.5, false),
                    1e-5,
                    "UI uses exact-size CFont with same fallback/kerning");
        }
        c.clear(0);
        r.draw("Hg", 40, 35, 22, false, 0xFFFFFFFF);
        Bounds b = ink(bitmap);
        check(b.top >= 34 && b.top <= 55, "UI coordinates are top-left, not legacy y-2");
        check(r.measureUi("Weights", 20, "Medium", false) > 0, "numeric/medium entry point");
    }

    private static void styles(HostTextRenderer r, Canvas c, Bitmap bitmap) {
        for (String family : List.of("Poppins", "Minecraft"))
            for (String weight : List.of("Thin", "Regular", "Medium", "Bold", "Black")) {
                var s = style(family, weight, 24, false, false, false, false, 0);
                HudRuntime.Metrics m = r.measure("Music AV 中文", s);
                check(
                        m.width() > 0 && m.height() > 0 && m.baseline() > 0 && m.lineHeight() > 0,
                        "HUD metrics " + family + weight);
                c.clear(0);
                r.draw(PAINTER, "Music AV 中文", 40, 35, s);
                check(ink(bitmap).count > 0, "HUD native draw " + family + weight);
                var resolution = r.resolution(s);
                check(
                        resolution.requestedWeight() > 0 && resolution.actualWeight() > 0,
                        "weight resolution recorded");
            }
        var normal = style("Poppins", "Regular", 28, false, false, false, false, 0);
        var italic = style("Poppins", "Regular", 28, true, false, false, false, 0);
        c.clear(0);
        r.draw(PAINTER, "Italic HUD", 40, 40, normal);
        byte[] a = bitmap.readPixels();
        c.clear(0);
        r.draw(PAINTER, "Italic HUD", 40, 40, italic);
        check(!Arrays.equals(a, bitmap.readPixels()), "italic changes glyphs");
        near(
                r.measure("Italic HUD", normal).width(),
                r.measure("Italic HUD", italic).width(),
                1,
                "italic keeps advance metrics");
        c.clear(0);
        r.draw(PAINTER, "Underline", 40, 40, normal);
        Bounds base = ink(bitmap);
        c.clear(0);
        r.draw(
                PAINTER,
                "Underline",
                40,
                40,
                style("Poppins", "Regular", 28, false, true, false, false, 0));
        Bounds under = ink(bitmap);
        check(under.count > base.count && under.bottom >= base.bottom, "underline actually draws");
        c.clear(0);
        r.draw(PAINTER, "Outline", 40, 40, normal);
        Bounds fill = ink(bitmap);
        c.clear(0);
        r.draw(
                PAINTER,
                "Outline",
                40,
                40,
                style("Poppins", "Regular", 28, false, false, true, true, 0));
        Bounds outline = ink(bitmap);
        check(
                outline.count > fill.count && outline.left < fill.left,
                "true stroke outline expands glyphs");
        c.clear(0);
        r.draw(PAINTER, "Shadow", 40, 40, normal);
        Bounds noShadow = ink(bitmap);
        c.clear(0);
        r.draw(
                PAINTER,
                "Shadow",
                40,
                40,
                style("Poppins", "Regular", 28, false, false, true, false, 0));
        Bounds shadow = ink(bitmap);
        check(
                shadow.count > noShadow.count
                        && shadow.right >= noShadow.right + 2
                        && shadow.bottom >= noShadow.bottom + 3,
                "shadow offsets reach native draw");
    }

    private static void fallback(HostTextRenderer r, Canvas c, Bitmap bitmap) {
        for (String text : List.of("中文测试", "가나다", "a\u0301", "𝄞", "👩‍💻", "AV汉字Mixed")) {
            var s = style("Minecraft", "Regular", 18, false, false, false, false, 0);
            var m = r.measure(text, s);
            check(
                    Double.isFinite(m.width()) && m.width() > 0,
                    "same-size fallback and shaping: " + text);
            c.clear(0);
            r.draw(PAINTER, text, 40, 40, s);
            check(ink(bitmap).count > 0, "fallback glyphs actually draw: " + text);
        }
    }

    private static void multiline(HostTextRenderer r, Canvas c, Bitmap bitmap) {
        var left = style("Poppins", "Regular", 22, false, false, false, false, 0);
        var m = r.measure("Wide line\nx\n", left);
        var one = r.measure("Wide line", left);
        near(one.width(), m.width(), 1e-6, "multiline max width");
        near(one.height() + 2 * m.lineHeight(), m.height(), 1e-6, "trailing blank line preserved");
        near(r.measure("Wide line\r\nx\r", left).height(), m.height(), 1e-6, "CRLF normalized");
        c.clear(0);
        r.draw(PAINTER, "Wide line\nx", 40, 30, left);
        byte[] a = bitmap.readPixels();
        c.clear(0);
        r.draw(
                PAINTER,
                "Wide line\nx",
                40,
                30,
                style("Poppins", "Regular", 22, false, false, false, false, 2));
        check(!Arrays.equals(a, bitmap.readPixels()), "right align changes short line position");
        near(
                r.measure("Wide line\nx", left).width(),
                r.measure(
                                "Wide line\nx",
                                style("Poppins", "Regular", 22, false, false, false, false, 2))
                        .width(),
                0,
                "align does not alter measured width");
    }

    private static void hotSwitch(
            HostTextRenderer r,
            Canvas c,
            Bitmap bitmap,
            AtomicReference<HostTextRenderer.HostFonts> source,
            CFontRenderer host,
            Font regular,
            Font bold,
            Font fallback) {
        double old = r.measure("iiiiMMMM", 20, false);
        long generation = r.statistics().generation();
        host.retarget(bold, new Font[] {fallback}, -1, false, null);
        double next = r.measure("iiiiMMMM", 20, false);
        check(
                r.statistics().generation() > generation,
                "metricsRevision invalidates all derived fonts/layouts");
        check(Math.abs(old - next) > .01, "hot retarget changes UI metrics");
        c.clear(0);
        r.draw("iiiiMMMM", 40, 40, 20, false, 0xFFFFFFFF);
        check(ink(bitmap).count > 0, "hot retarget remains drawable");
        host.retarget(regular, new Font[] {fallback}, -1, false, null);
        r.measure("fallback reload", 15, false);
        long beforeFallback = r.statistics().generation();
        check(host.appendFallbackFont(bold), "test changes actual host fallback chain");
        r.measure("fallback reload", 15, false);
        check(
                r.statistics().generation() > beforeFallback,
                "fallback revision invalidates text resources");
    }

    private static void systemFont(
            HostTextRenderer renderer,
            AtomicReference<HostTextRenderer.HostFonts> source,
            CFontRenderer regular,
            CFontRenderer bold,
            Font primaryRegular,
            Font primaryBold) {
        var original = source.get();
        try {
            String family =
                    com.muoniumplayer.render.skija.SkijaText.typefaceFor(primaryRegular)
                            .getFamilyName();
            source.set(
                    new HostTextRenderer.HostFonts(
                            regular, bold, primaryRegular, primaryBold, family));
            for (String weight : List.of("Thin", "Regular", "Medium", "Bold", "Black")) {
                var resolution =
                        renderer.resolution(
                                style("Poppins", weight, 18, true, false, false, false, 0));
                check(
                        resolution.family() != null && !resolution.family().isBlank(),
                        "system family weight/italic request resolves: " + weight);
            }
            var flag =
                    new HudRuntime.TextStyle(
                            "Poppins",
                            "Thin",
                            18,
                            true,
                            false,
                            false,
                            false,
                            false,
                            0xFFFFFFFF,
                            0,
                            0,
                            0,
                            0);
            check(
                    renderer.resolution(flag).requestedWeight() == 700,
                    "explicit bold overrides Thin weight like original");
        } finally {
            source.set(original);
        }
    }

    private static void threading(HostTextRenderer r) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrong =
                new Thread(
                        () -> {
                            try {
                                r.measure("wrong thread", 14, false);
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        wrong.start();
        wrong.join();
        check(
                failure.get() instanceof IllegalStateException,
                "native resources restricted to creating thread");
        fails(
                IllegalStateException.class,
                () -> {
                    AtomicReference<Throwable> closeError = new AtomicReference<>();
                    Thread thread =
                            new Thread(
                                    () -> {
                                        try {
                                            r.close();
                                        } catch (Throwable error) {
                                            closeError.set(error);
                                        }
                                    });
                    thread.start();
                    try {
                        thread.join();
                    } catch (InterruptedException error) {
                        throw new AssertionError(error);
                    }
                    if (closeError.get() instanceof RuntimeException error) throw error;
                },
                "wrong-thread close is rejected");
        fails(
                IllegalArgumentException.class,
                () -> r.measure("bad", Double.NaN, false),
                "non-finite size rejected");
        fails(
                IllegalArgumentException.class,
                () ->
                        r.measure(
                                "bad",
                                style("Unknown", "Regular", 14, false, false, false, false, 0)),
                "unknown family not silently substituted");
    }

    private static void caches(HostTextRenderer r) {
        r.invalidate();
        check(
                r.statistics().fonts() == 0 && r.statistics().layouts() == 0,
                "explicit invalidate releases native caches");
        for (int i = 0; i < 160; i++) r.measure("cache" + i, 8 + i / 10d, false);
        check(
                r.statistics().fonts() <= 96 && r.statistics().layouts() <= 192,
                "font/layout caches bounded");
        for (int i = 0; i < 220; i++) r.measure("layout" + i, 16, false);
        check(r.statistics().layouts() <= 192, "layout LRU bounded");
        r.measure(
                "long".repeat(800),
                style("Minecraft", "Regular", 15, false, false, false, false, 0));
        check(r.statistics().layouts() <= 192, "large transient layout not retained");
    }

    private record Bounds(int left, int top, int right, int bottom, int count) {}

    private static Bounds ink(Bitmap bitmap) {
        byte[] pixels = bitmap.readPixels();
        int l = 1024, t = 512, r = -1, b = -1, n = 0;
        for (int y = 0; y < 512; y++)
            for (int x = 0; x < 1024; x++)
                if ((pixels[(y * 1024 + x) * 4 + 3] & 255) > 0) {
                    l = Math.min(l, x);
                    t = Math.min(t, y);
                    r = Math.max(r, x);
                    b = Math.max(b, y);
                    n++;
                }
        return new Bounds(l, t, r, b, n);
    }

    private static final Painter PAINTER =
            new Painter() {
                public void save() {}

                public void restore() {}

                public void translate(double x, double y) {}

                public void scale(double x, double y) {}

                public void clip(Rect r, double radius) {}

                public void rect(Rect r, double radius, int color) {}

                public void outline(Rect r, double radius, double width, int color) {}

                public void line(
                        double x1, double y1, double x2, double y2, double width, int color) {}

                public void text(
                        String text, double x, double y, double size, boolean bold, int color) {}

                public double measure(String text, double size, boolean bold) {
                    throw new AssertionError(
                            "Exact HUD text must not fall back to painter.measure");
                }
            };
}
