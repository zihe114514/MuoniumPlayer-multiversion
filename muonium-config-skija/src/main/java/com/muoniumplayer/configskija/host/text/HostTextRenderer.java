package com.muoniumplayer.configskija.host.text;

import com.ibm.icu.text.BreakIterator;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.SkijaPainter;
import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.SkijaText;

import io.github.humbleui.skija.*;

import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;

/** Text on the caller's existing canvas. All native resources belong to the creating render thread. */
public final class HostTextRenderer
        implements SkijaPainter.Text, HudRuntime.TextRenderer, AutoCloseable {
    public static final String MINECRAFT_REGULAR =
            "assets/muoniumconfigskija/fonts/minecraft/Minecraft-Regular.otf";
    public static final String MINECRAFT_BOLD =
            "assets/muoniumconfigskija/fonts/minecraft/Minecraft-Bold.otf";
    private static final int FONT_LIMIT = 96, LAYOUT_LIMIT = 192, MAX_CACHED_TEXT = 2048;
    private final Thread owner = Thread.currentThread();
    private final Supplier<Canvas> canvas;
    private final Supplier<Theme> theme;
    private final Supplier<HostFonts> hostSource;
    private final Typeface minecraftRegular, minecraftBold;
    private final Font builtinRegular, builtinBold;
    private final Paint fill = new Paint().setAntiAlias(true),
            stroke = new Paint().setAntiAlias(true).setStroke(true);
    private final LinkedHashMap<FontKey, Face> faces = new LinkedHashMap<>(32, .75f, true);
    private final LinkedHashMap<LayoutKey, Layout> layouts = new LinkedHashMap<>(32, .75f, true);
    private HostFonts host;
    private long regularRevision = Long.MIN_VALUE, boldRevision = Long.MIN_VALUE, generation;
    private boolean closed;

    /** Worker-only result: byte arrays and registered AWT handles, no Skija/native objects. */
    public static final class LoadedAssets {
        private final byte[] regular, bold;
        private final Font builtinRegular, builtinBold;

        private LoadedAssets(byte[] regular, byte[] bold, Font builtinRegular, Font builtinBold) {
            this.regular = regular.clone();
            this.bold = bold.clone();
            this.builtinRegular = Objects.requireNonNull(builtinRegular);
            this.builtinBold = Objects.requireNonNull(builtinBold);
        }

        public int minecraftRegularBytes() {
            return regular.length;
        }

        public int minecraftBoldBytes() {
            return bold.length;
        }
    }

    /** Borrowed host renderers and primary handles. Neither is closed by this renderer. */
    public record HostFonts(
            CFontRenderer regular,
            CFontRenderer bold,
            Font primaryRegular,
            Font primaryBold,
            String systemFamily) {
        public HostFonts {
            Objects.requireNonNull(regular, "Host regular font is not ready");
            Objects.requireNonNull(bold, "Host bold font is not ready");
        }
    }

    /** Describes actual weight resolution, including a static font's nearest available face. */
    public record Resolution(
            String family,
            int requestedWeight,
            int actualWeight,
            boolean syntheticBold,
            boolean syntheticItalic) {}

    public record Statistics(long generation, int fonts, int layouts, boolean closed) {}

    /** Read on the existing asset worker; FontManager's public builtin loader registers the exact bytes for SkijaText. */
    public static LoadedAssets loadAssets(ClassLoader loader) throws IOException {
        Objects.requireNonNull(loader);
        byte[] regular = read(loader, MINECRAFT_REGULAR), bold = read(loader, MINECRAFT_BOLD);
        Font builtinRegular = FontManager.builtinTextMain(false),
                builtinBold = FontManager.builtinTextMain(true);
        if (builtinRegular == null || builtinBold == null)
            throw new IOException("Host builtin UI font bytes are unavailable");
        return new LoadedAssets(regular, bold, builtinRegular, builtinBold);
    }

    private static byte[] read(ClassLoader loader, String path) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing UI font resource: " + path);
            byte[] bytes = stream.readAllBytes();
            if (bytes.length < 12) throw new IOException("Unreadable UI font resource: " + path);
            return bytes;
        }
    }

    /** Call after the host has initialized its existing Skija backend and FontManager. No surface or loader is created. */
    public static HostTextRenderer create(
            LoadedAssets assets, Supplier<Canvas> canvas, Supplier<Theme> theme) {
        return create(assets, canvas, theme, HostTextRenderer::currentHostFonts);
    }

    /** Injection point for an already initialized host/test font set; all supplied objects remain caller-owned. */
    public static HostTextRenderer create(
            LoadedAssets assets,
            Supplier<Canvas> canvas,
            Supplier<Theme> theme,
            Supplier<HostFonts> hostFonts) {
        Objects.requireNonNull(assets);
        Objects.requireNonNull(canvas);
        Objects.requireNonNull(theme);
        Objects.requireNonNull(hostFonts);
        return new HostTextRenderer(assets, canvas, theme, hostFonts);
    }

    private static HostFonts currentHostFonts() {
        UiFont active = UiFontLibrary.getActive();
        return new HostFonts(
                FontManager.pf16,
                FontManager.pf16bold,
                UiFontLibrary.getWiredRegularAwt(),
                UiFontLibrary.getWiredBoldAwt(),
                active != null && active.getOrigin() == UiFont.Origin.SYSTEM
                        ? active.getFamily()
                        : null);
    }

    private HostTextRenderer(
            LoadedAssets assets,
            Supplier<Canvas> canvas,
            Supplier<Theme> theme,
            Supplier<HostFonts> hostSource) {
        this.canvas = canvas;
        this.theme = theme;
        this.hostSource = hostSource;
        this.builtinRegular = assets.builtinRegular;
        this.builtinBold = assets.builtinBold;
        Typeface regular = null, bold = null;
        try {
            regular = decode(assets.regular);
            bold = decode(assets.bold);
            this.minecraftRegular = regular;
            this.minecraftBold = bold;
            refresh();
        } catch (RuntimeException | Error error) {
            if (bold != null) bold.close();
            if (regular != null) regular.close();
            fill.close();
            stroke.close();
            throw error;
        }
    }

    private static Typeface decode(byte[] bytes) {
        try (Data data = Data.makeFromBytes(bytes)) {
            Typeface face = FontMgr.getDefault().makeFromData(data);
            if (face == null)
                throw new IllegalArgumentException("Skija rejected Minecraft font bytes");
            return face;
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException(
                    "Text renderer must be used and closed on its creating render thread");
        if (closed) throw new IllegalStateException("Text renderer is closed");
    }

    private void refresh() {
        checkThread();
        HostFonts next = Objects.requireNonNull(hostSource.get());
        long r = next.regular.metricsRevision(), b = next.bold.metricsRevision();
        if (host == null
                || host.regular != next.regular
                || host.bold != next.bold
                || regularRevision != r
                || boldRevision != b
                || host.primaryRegular != next.primaryRegular
                || host.primaryBold != next.primaryBold
                || !Objects.equals(host.systemFamily, next.systemFamily)) {
            clearFonts();
            host = next;
            regularRevision = r;
            boldRevision = b;
            generation++;
        }
    }

    private void clearFonts() {
        for (Layout layout : layouts.values()) layout.close();
        layouts.clear();
        for (Face face : faces.values()) face.close();
        faces.clear();
    }

    /** Explicit resource reload hook; normal font selection/fallback updates are detected through metricsRevision(). */
    public void invalidate() {
        checkThread();
        clearFonts();
        regularRevision = boldRevision = Long.MIN_VALUE;
    }

    public Statistics statistics() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Render thread required");
        return new Statistics(generation, faces.size(), layouts.size(), closed);
    }

    public Resolution resolution(HudRuntime.TextStyle style) {
        refresh();
        return face(normalize(style)).resolution;
    }

    @Override
    public double measure(String text, double size, boolean bold) {
        refresh();
        return measured(text, uiStyle(size, bold)).width();
    }

    @Override
    public void draw(String text, double x, double y, double size, boolean bold, int color) {
        refresh();
        draw(canvas.get(), text, x, y, uiStyle(size, bold), color, 0, false, false, 0, 0, false, 0);
    }

    /** Numeric weight entry for callers that distinguish Medium/Semibold instead of the binary painter interface. */
    public double measureUi(String text, double size, String weight, boolean italic) {
        refresh();
        return measured(
                        text,
                        new Style(
                                theme.get().minecraft() ? Family.MINECRAFT : Family.HOST,
                                size,
                                weight(weight, false),
                                italic))
                .width();
    }

    public void drawUi(
            String text,
            double x,
            double y,
            double size,
            String weight,
            boolean italic,
            int color) {
        refresh();
        draw(
                canvas.get(),
                text,
                x,
                y,
                new Style(
                        theme.get().minecraft() ? Family.MINECRAFT : Family.HOST,
                        size,
                        weight(weight, false),
                        italic),
                color,
                0,
                false,
                false,
                0,
                0,
                false,
                0);
    }

    @Override
    public HudRuntime.Metrics measure(String text, HudRuntime.TextStyle style) {
        refresh();
        return measured(text, normalize(style));
    }

    @Override
    public void draw(Painter painter, String text, double x, double y, HudRuntime.TextStyle style) {
        Objects.requireNonNull(painter);
        refresh();
        draw(
                canvas.get(),
                text,
                x,
                y,
                normalize(style),
                style.color(),
                style.shadowColor(),
                style.shadow(),
                style.outline(),
                style.shadowX(),
                style.shadowY(),
                style.underline(),
                style.align());
    }

    private HudRuntime.Metrics measured(String text, Style style) {
        Layout layout = layout(text, style);
        try {
            return layout.metrics;
        } finally {
            if (text.length() > MAX_CACHED_TEXT) layout.close();
        }
    }

    private Style uiStyle(double size, boolean bold) {
        Theme t = Objects.requireNonNull(theme.get());
        return new Style(
                t.minecraft() ? Family.MINECRAFT : Family.UI, size, bold ? 700 : 400, false);
    }

    private enum Family {
        UI,
        HOST,
        MINECRAFT
    }

    private record Style(Family family, double size, int weight, boolean italic) {
        private Style {
            if (!Double.isFinite(size) || size <= 0 || size > 4096)
                throw new IllegalArgumentException(
                        "Font size must be finite and between 0 and 4096");
        }
    }

    private record FontKey(Family family, float size, int weight, boolean italic) {}

    private record LayoutKey(String text, Style style) {}

    private static Style normalize(HudRuntime.TextStyle style) {
        Objects.requireNonNull(style);
        String font = Objects.requireNonNullElse(style.font(), "Minecraft");
        Family family =
                switch (font.toLowerCase(Locale.ROOT)) {
                    case "minecraft" -> Family.MINECRAFT;
                    case "poppins", "host", "ui" -> Family.HOST;
                    default -> throw new IllegalArgumentException("Unknown text font: " + font);
                };
        return new Style(
                family, style.size(), weight(style.weight(), style.bold()), style.italic());
    }

    private static int weight(String value, boolean bold) {
        if (bold) return 700;
        String w =
                Objects.requireNonNullElse(value, "Regular")
                        .replace(" ", "")
                        .replace("-", "")
                        .toLowerCase(Locale.ROOT);
        return switch (w) {
            case "thin", "100" -> 100;
            case "extralight", "200" -> 200;
            case "light", "300" -> 300;
            case "regular", "normal", "400" -> 400;
            case "medium", "500" -> 500;
            case "semibold", "demibold", "600" -> 600;
            case "bold", "700" -> 700;
            case "extrabold", "800" -> 800;
            case "black", "heavy", "900" -> 900;
            default -> throw new IllegalArgumentException("Unknown font weight: " + value);
        };
    }

    private Face face(Style style) {
        FontKey key = new FontKey(style.family, (float) style.size, style.weight, style.italic);
        Face result = faces.get(key);
        if (result != null) return result;
        if (faces.size() >= FONT_LIMIT) clearFonts();
        result = new Face(style);
        faces.put(key, result);
        return result;
    }

    private final class Face implements AutoCloseable {
        final CFontRenderer fallback;
        final io.github.humbleui.skija.Font font;
        final Typeface ownedFace, typeface;
        final Resolution resolution;
        final double ascent, descent, leading, underline, thickness;
        final boolean syntheticItalic;
        final Map<Integer, NativeFallback> nativeFallbacks = new HashMap<>();
        final Map<String, io.github.humbleui.skija.Font> missingClusters = new HashMap<>();
        final Style style;

        Face(Style s) {
            style = s;
            CFontRenderer anchor = s.weight >= 600 ? host.bold : host.regular;
            CFontRenderer madeFallback = null;
            io.github.humbleui.skija.Font madeFont = null;
            Typeface madeFace = null;
            try {
                madeFallback = anchor.deriveAtSize((float) s.size);
                if (s.family == Family.UI) {
                    fallback = madeFallback;
                    font = null;
                    ownedFace = null;
                    typeface = null;
                    ascent = fallback.getBaselineOffset();
                    descent = Math.max(0, fallback.lineBox() - ascent);
                    leading = 0;
                    underline = Math.max(1, s.size * .1);
                    thickness = Math.max(.5, s.size / 14);
                    syntheticItalic = s.italic;
                    resolution =
                            new Resolution(
                                    "host", s.weight, s.weight >= 600 ? 700 : 400, false, s.italic);
                    return;
                }
                Typeface base;
                if (s.family == Family.MINECRAFT)
                    base = s.weight >= 600 ? minecraftBold : minecraftRegular;
                else {
                    Font source = s.weight >= 600 ? host.primaryBold : host.primaryRegular;
                    if (source == null) source = s.weight >= 600 ? builtinBold : builtinRegular;
                    base = SkijaText.typefaceFor(source);
                    if (base == null)
                        throw new IllegalStateException("Host primary typeface is not registered");
                }
                Typeface resolved = base;
                if (s.family == Family.HOST && host.systemFamily != null) {
                    Typeface matched =
                            FontMgr.getDefault()
                                    .matchFamilyStyle(
                                            host.systemFamily,
                                            new FontStyle(
                                                    s.weight,
                                                    5,
                                                    s.italic
                                                            ? FontSlant.ITALIC
                                                            : FontSlant.UPRIGHT));
                    if (matched != null) {
                        madeFace = matched;
                        resolved = matched;
                    }
                } else {
                    FontVariationAxis[] axes = base.getVariationAxes();
                    if (axes != null)
                        for (FontVariationAxis axis : axes)
                            if (axis.getTag().equals("wght")) {
                                madeFace =
                                        base.makeClone(
                                                new FontVariation(
                                                        "wght",
                                                        Math.max(
                                                                axis.getMinValue(),
                                                                Math.min(
                                                                        axis.getMaxValue(),
                                                                        s.weight))));
                                if (madeFace != null) resolved = madeFace;
                                break;
                            }
                }
                FontStyle resolvedStyle = resolved.getFontStyle();
                boolean fakeBold = s.weight >= 600 && resolvedStyle.getWeight() < 600;
                syntheticItalic = s.italic && resolvedStyle.getSlant() == FontSlant.UPRIGHT;
                madeFont = new io.github.humbleui.skija.Font(resolved, (float) s.size);
                madeFont.setEdging(FontEdging.ANTI_ALIAS)
                        .setHinting(FontHinting.NONE)
                        .setSubpixel(true)
                        .setBaselineSnapped(false)
                        .setEmboldened(fakeBold);
                if (syntheticItalic) madeFont.setSkewX(-.25f);
                FontMetrics metrics = madeFont.getMetrics();
                ascent = -metrics.getAscent();
                descent = metrics.getDescent();
                leading = metrics.getLeading();
                underline =
                        metrics.getUnderlinePosition() == null
                                ? Math.max(1, s.size * .1)
                                : metrics.getUnderlinePosition();
                thickness =
                        metrics.getUnderlineThickness() == null
                                ? Math.max(.5, s.size / 14)
                                : Math.max(.1, metrics.getUnderlineThickness());
                fallback = madeFallback;
                font = madeFont;
                ownedFace = madeFace;
                typeface = resolved;
                resolution =
                        new Resolution(
                                resolved.getFamilyName(),
                                s.weight,
                                resolvedStyle.getWeight(),
                                fakeBold,
                                syntheticItalic);
            } catch (RuntimeException | Error error) {
                if (madeFont != null) madeFont.close();
                if (madeFace != null) madeFace.close();
                if (madeFallback != null) madeFallback.close();
                throw error;
            }
        }

        boolean supports(String text) {
            if (font == null) return false;
            Typeface tf = typeface;
            return text.codePoints()
                    .allMatch(
                            cp ->
                                    cp == 0x200d
                                            || cp == 0xfe0e
                                            || cp == 0xfe0f
                                            || tf.getUTF32Glyph(cp) != 0);
        }

        io.github.humbleui.skija.Font pick(String text) {
            if (supports(text)) return font;
            boolean hostCoverage =
                    text.codePoints()
                            .allMatch(
                                    cp -> {
                                        if (cp == 0x200d || cp == 0xfe0e || cp == 0xfe0f)
                                            return true;
                                        if (fallback.font.canDisplay(cp)) return true;
                                        for (Font f : fallback.fallBackFonts)
                                            if (f.canDisplay(cp)) return true;
                                        return false;
                                    });
            if (hostCoverage) return null;
            io.github.humbleui.skija.Font cached = missingClusters.get(text);
            if (cached != null) return cached;
            int missing =
                    text.codePoints()
                            .filter(cp -> cp != 0x200d && cp != 0xfe0e && cp != 0xfe0f)
                            .filter(
                                    cp -> {
                                        if (fallback.font.canDisplay(cp)) return false;
                                        for (Font f : fallback.fallBackFonts)
                                            if (f.canDisplay(cp)) return false;
                                        return true;
                                    })
                            .findFirst()
                            .orElse(text.codePointAt(0));
            Typeface match =
                    FontMgr.getDefault()
                            .matchFamilyStyleCharacter(
                                    null,
                                    new FontStyle(
                                            style.weight,
                                            5,
                                            style.italic ? FontSlant.ITALIC : FontSlant.UPRIGHT),
                                    new String[0],
                                    missing);
            if (match == null) match = minecraftRegular;
            boolean borrowed = match == minecraftRegular;
            int id = match.getUniqueId();
            NativeFallback resource = nativeFallbacks.get(id);
            if (resource == null) {
                io.github.humbleui.skija.Font nativeFont =
                        new io.github.humbleui.skija.Font(match, (float) style.size);
                nativeFont
                        .setEdging(FontEdging.ANTI_ALIAS)
                        .setHinting(FontHinting.NONE)
                        .setSubpixel(true)
                        .setBaselineSnapped(false);
                if (style.italic && match.getFontStyle().getSlant() == FontSlant.UPRIGHT)
                    nativeFont.setSkewX(-.25f);
                resource = new NativeFallback(nativeFont, borrowed ? null : match);
                nativeFallbacks.put(id, resource);
            } else if (!borrowed) match.close();
            if (missingClusters.size() < 256) missingClusters.put(text, resource.font);
            return resource.font;
        }

        public void close() {
            for (NativeFallback resource : nativeFallbacks.values()) resource.close();
            nativeFallbacks.clear();
            missingClusters.clear();
            if (font != null) font.close();
            if (ownedFace != null) ownedFace.close();
            fallback.close();
        }
    }

    private record NativeFallback(io.github.humbleui.skija.Font font, Typeface typeface)
            implements AutoCloseable {
        public void close() {
            font.close();
            if (typeface != null) typeface.close();
        }
    }

    private static final class Run implements AutoCloseable {
        final String text;
        final Face face;
        final TextLine shaped;
        final double width;
        final boolean fallback;
        final io.github.humbleui.skija.Font nativeFont;

        Run(String text, Face face, io.github.humbleui.skija.Font selected) {
            this.text = text;
            this.face = face;
            nativeFont = selected;
            fallback = selected == null;
            if (fallback) {
                shaped = null;
                width = face.fallback.getStringWidthD(text);
            } else {
                shaped = TextLine.make(text, selected);
                width = shaped.getWidth();
            }
        }

        void draw(Canvas canvas, double x, double baseline, Paint paint, boolean italic) {
            if (shaped != null) {
                canvas.drawTextLine(shaped, (float) x, (float) baseline, paint);
                return;
            }
            canvas.save();
            try {
                canvas.translate((float) x, (float) baseline);
                if (italic) canvas.skew(-.25f, 0);
                if (!face.fallback.drawPlainTextWithPaintOn(
                        canvas, text, 0, 2 - face.fallback.getBaselineOffset(), paint))
                    throw new IllegalStateException("Host rejected prepared text run");
            } finally {
                canvas.restore();
            }
        }

        public void close() {
            if (shaped != null) shaped.close();
        }
    }

    private record Line(List<Run> runs, double width) {}

    private static final class Layout implements AutoCloseable {
        final List<Line> lines;
        final HudRuntime.Metrics metrics;
        final Face face;

        Layout(List<Line> lines, HudRuntime.Metrics metrics, Face face) {
            this.lines = lines;
            this.metrics = metrics;
            this.face = face;
        }

        public void close() {
            for (Line line : lines) for (Run run : line.runs) run.close();
        }
    }

    private Layout layout(String text, Style style) {
        Objects.requireNonNull(text);
        LayoutKey key = new LayoutKey(text, style);
        Layout cached = layouts.get(key);
        if (cached != null) return cached;
        Face face = face(style);
        List<Line> lines = new ArrayList<>();
        double width = 0, asc = face.ascent, desc = face.descent, leading = face.leading;
        try {
            for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                String plain = CFontRenderer.stripControlCodes(line);
                List<Run> runs = new ArrayList<>();
                lines.add(new Line(runs, 0));
                BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
                clusters.setText(plain);
                int start = clusters.first(), end;
                StringBuilder span = new StringBuilder();
                io.github.humbleui.skija.Font previous = null;
                boolean havePrevious = false;
                boolean previousComplex = false;
                while ((end = clusters.next()) != BreakIterator.DONE) {
                    String cluster = plain.substring(start, end);
                    io.github.humbleui.skija.Font selected = face.pick(cluster);
                    boolean complex =
                            selected == null
                                    && cluster.codePoints()
                                            .anyMatch(
                                                    cp ->
                                                            cp > 0xFFFF
                                                                    || Character.getType(cp)
                                                                            == Character
                                                                                    .NON_SPACING_MARK
                                                                    || Character.getType(cp)
                                                                            == Character
                                                                                    .COMBINING_SPACING_MARK
                                                                    || Character.getType(cp)
                                                                            == Character
                                                                                    .ENCLOSING_MARK);
                    if (havePrevious
                            && (previous != selected
                                    || previousComplex
                                    || complex
                                    || span.length() + cluster.length() > 1024)) {
                        runs.add(new Run(span.toString(), face, previous));
                        span.setLength(0);
                    }
                    span.append(cluster);
                    previous = selected;
                    havePrevious = true;
                    previousComplex = complex;
                    start = end;
                }
                if (span.length() > 0) runs.add(new Run(span.toString(), face, previous));
                double lineWidth = 0;
                for (Run run : runs) {
                    lineWidth += run.width;
                    if (run.fallback) {
                        asc = Math.max(asc, face.fallback.getBaselineOffset());
                        desc =
                                Math.max(
                                        desc,
                                        face.fallback.lineBox()
                                                - face.fallback.getBaselineOffset());
                    } else {
                        FontMetrics m = run.nativeFont.getMetrics();
                        asc = Math.max(asc, -m.getAscent());
                        desc = Math.max(desc, m.getDescent());
                        leading = Math.max(leading, m.getLeading());
                    }
                }
                lines.set(lines.size() - 1, new Line(runs, lineWidth));
                width = Math.max(width, lineWidth);
            }
            double height = asc + desc, lineHeight = height + Math.max(0, leading);
            Layout built =
                    new Layout(
                            List.copyOf(lines),
                            new HudRuntime.Metrics(
                                    width,
                                    height + (lines.size() - 1) * lineHeight,
                                    asc,
                                    lineHeight),
                            face);
            if (text.length() <= MAX_CACHED_TEXT) {
                layouts.put(key, built);
                while (layouts.size() > LAYOUT_LIMIT) {
                    Iterator<Layout> it = layouts.values().iterator();
                    Layout old = it.next();
                    it.remove();
                    old.close();
                }
            }
            return built;
        } catch (RuntimeException | Error error) {
            for (Line line : lines) for (Run run : line.runs) run.close();
            throw error;
        }
    }

    private void draw(
            Canvas canvas,
            String text,
            double x,
            double y,
            Style style,
            int color,
            int shadowColor,
            boolean shadow,
            boolean outline,
            double shadowX,
            double shadowY,
            boolean underline,
            int align) {
        if (canvas == null)
            throw new IllegalStateException("Text draw requires the caller's active Skija canvas");
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(shadowX)
                || !Double.isFinite(shadowY))
            throw new IllegalArgumentException("Non-finite text coordinates");
        Layout layout = layout(text, style);
        try {
            int index = 0;
            for (Line line : layout.lines) {
                double
                        xx =
                                x
                                        + (layout.metrics.width() - line.width)
                                                * Math.max(0, Math.min(2, align))
                                                / 2.0,
                        baseline =
                                y
                                        + layout.metrics.baseline()
                                        + index++ * layout.metrics.lineHeight();
                if (shadow) {
                    if (outline) {
                        double border = style.size / 8;
                        stroke.setColor(shadowColor).setStrokeWidth((float) (border * 2));
                        drawLine(canvas, line, xx, baseline, stroke, style.italic);
                        if (underline)
                            underline(
                                    canvas,
                                    line,
                                    xx - border,
                                    baseline,
                                    line.width + border * 2,
                                    shadowColor,
                                    layout.face.thickness + border * 2);
                    } else {
                        fill.setColor(shadowColor);
                        drawLine(
                                canvas, line, xx + shadowX, baseline + shadowY, fill, style.italic);
                        if (underline)
                            underline(
                                    canvas,
                                    line,
                                    xx + shadowX,
                                    baseline + shadowY,
                                    line.width,
                                    shadowColor,
                                    layout.face.thickness);
                    }
                }
                fill.setColor(color);
                drawLine(canvas, line, xx, baseline, fill, style.italic);
                if (underline)
                    underline(canvas, line, xx, baseline, line.width, color, layout.face.thickness);
            }
        } finally {
            if (text.length() > MAX_CACHED_TEXT) layout.close();
        }
    }

    private static void drawLine(
            Canvas canvas, Line line, double x, double baseline, Paint paint, boolean italic) {
        double pen = x;
        for (Run run : line.runs) {
            run.draw(canvas, pen, baseline, paint, italic);
            pen += run.width;
        }
    }

    private void underline(
            Canvas canvas,
            Line line,
            double x,
            double baseline,
            double width,
            int color,
            double thickness) {
        if (line.runs.isEmpty()) return;
        stroke.setColor(color).setStrokeWidth((float) thickness);
        canvas.drawLine(
                (float) x,
                (float) (baseline + line.runs.get(0).face.underline),
                (float) (x + width),
                (float) (baseline + line.runs.get(0).face.underline),
                stroke);
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Close text renderer on its creating render thread");
        if (closed) return;
        clearFonts();
        fill.close();
        stroke.close();
        minecraftBold.close();
        minecraftRegular.close();
        closed = true;
    }
}
