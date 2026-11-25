package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import io.github.humbleui.skija.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** CPU Skija fixture output of the integrated HUD panel; no game, window, GL or MOD artifact. */
public final class HudPanelRasterPreview {
    public static void main(String[] args) throws Exception {
        Path music = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        try (Typeface regular = FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_normal.ttf").toString());
             Typeface bold = FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_middleblack.ttf").toString())) {
            if (regular == null || bold == null) throw new IllegalStateException("Host fonts unavailable");
            for (int theme : new int[] {0, 1, 2, 3}) {
                render(regular, bold, output.resolve("numbers-"+Theme.BUILTIN[theme].id()+".png"), 960, 900, 1, theme, "GUI Scale");
            }
            render(regular, bold, output.resolve("padding-wide.png"), 960, 900, 1, 0, "Padding");
            render(regular, bold, output.resolve("padding-narrow.png"), 320, 900, 1, 1, "Padding");
            render(regular, bold, output.resolve("text-narrow.png"), 360, 1100, 1, 0, "Text Options");
            render(regular, bold, output.resolve("numbers-density-150.png"), 960, 1000, 1.5, 1, "GUI Scale");
            render(regular, bold, output.resolve("host-settings.png"), 960, 1100, 1, 1, "host");
        }
        System.out.println("HUD CPU raster probes passed: 9 fixture images in " + output);
    }
    private static void render(Typeface regular, Typeface bold, Path file, int width, int height,
            double density, int theme, String query) throws Exception {
        var host = new HudParityTest.Host() {
            private double scale = .608, glow = .162;
            @Override public List<ConfigHost.Group> hudSettings(HudElement element) {
                return List.of(new ConfigHost.Group("line", "当前行", List.of(
                        new ConfigHost.Option("scale", "整行缩放", "离屏夹具：拖动、精确输入与步进", ConfigHost.Kind.SLIDER,
                                .25, 4, .01, List.of(), () -> scale, v -> scale=((Number)v).doubleValue(), () -> "", () -> scale=1),
                        new ConfigHost.Option("glow", "普通歌词的背景发光强度", "离屏夹具", ConfigHost.Kind.SLIDER,
                                0, 1, .01, List.of(), () -> glow, v -> glow=((Number)v).doubleValue(), () -> "", () -> glow=0))));
            }
        };
        host.density = density; host.frameWidth = width; host.frameHeight = height;
        var c = new HudParityTest.Context(host);
        c.width=width; c.height=height; c.content=new Rect(0,0,width,height); c.palette=Theme.BUILTIN[theme];
        var runtime=HudRuntime.open(host,HudParityTest.prefs());
        var studio=new HudStudio(host,HudParityTest.prefs(),runtime);
        if (query.equals("host")) {
            studio.select(List.of(host.elements.get(0).id()),true);
        } else {
            HudNode node=HudParityTest.nativeBox("fixture",20,20,160,50).node();
            node.set("customScale",1.37); runtime.designer().add(node,20,20);
            studio.select(List.of(node.id()),true); studio.panel.search.text(query);
        }
        Map<String,Font> fonts=new HashMap<>();
        try (Surface surface=Surface.makeRasterN32Premul(width,height); Paint ink=new Paint().setAntiAlias(true)) {
            Canvas canvas=surface.getCanvas();
            try (SkijaPainter painter=new SkijaPainter(()->canvas,new SkijaPainter.Text() {
                Font font(double size,boolean heavy) { return fonts.computeIfAbsent(size+":"+heavy,k->new Font(heavy?bold:regular,(float)size)); }
                public void draw(String text,double x,double y,double size,boolean heavy,int color) {
                    Font f=font(size,heavy); canvas.drawString(text,(float)x,(float)(y-f.getMetrics().getAscent()),f,ink.setColor(color));
                }
                public double measure(String text,double size,boolean heavy) { return font(size,heavy).measureTextWidth(text); }
                public Painter.TextBounds textBounds(String text, double size, boolean heavy) {
                    Font f = font(size, heavy); FontMetrics metrics = f.getMetrics();
                    io.github.humbleui.types.Rect ink = f.measureText(text);
                    // drawString uses baseline y - ascent; bounds must use that same line-top origin.
                    return ink.getWidth() > 0 && ink.getHeight() > 0
                            ? new Painter.TextBounds(ink.getTop() - metrics.getAscent(), ink.getBottom() - metrics.getAscent())
                            : new Painter.TextBounds(0, metrics.getDescent() - metrics.getAscent());
                }
            },SkijaPainter.loadIcons())) {
                c.rasterPainter=painter;
                for (int frame=0;frame<3;frame++) {
                    canvas.clear(c.palette.page()); c.time=frame*2; c.next(); studio.render(c);
                }
            }
            try (Image image=surface.makeImageSnapshot(); Data encoded=image.encodeToData(EncodedImageFormat.PNG)) {
                if (encoded==null) throw new AssertionError("PNG encoding failed");
                Files.write(file,encoded.getBytes());
            }
        } finally { for (Font font:fonts.values()) font.close(); }
    }
}
