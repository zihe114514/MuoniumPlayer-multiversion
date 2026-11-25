package com.muoniumplayer.configskija;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import io.github.humbleui.skija.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Raster smoke test using the same view/painter. Its fixture data is deliberately labeled. */
public final class PreviewMain {
    public static void main(String[] args) throws Exception {
        Path music=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        Typeface regular=FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_normal.ttf").toString());
        Typeface bold=FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_middleblack.ttf").toString());
        if(regular==null||bold==null) throw new IllegalStateException("Host font unavailable");
        Map<String,Font> fonts=new HashMap<>();Map<String,SkijaPainter.Svg> icons=SkijaPainter.loadIcons();
        CoreTest.FakeHost host=new CoreTest.FakeHost() {
            private final List<HudElement> huds=List.of(new CoreTest.FakeHud("桌面歌词",.3,.65,420,120,false),new CoreTest.FakeHud("歌曲信息卡",.1,.15,230,56,true));
            public List<HudElement> huds() { return huds; }
        };
        UiPreferences prefs=new UiPreferences(Map.of(),()->{});ConfigView view=new ConfigView(host,prefs);
        view.register(Navigation.Page.SETTINGS,new com.muoniumplayer.configskija.ui.settings.SettingsPage(false));view.register(Navigation.Page.FAVORITES,new com.muoniumplayer.configskija.ui.settings.SettingsPage(true));view.register(Navigation.Page.MODS,com.muoniumplayer.configskija.ui.pages.LibraryPages.mods());view.register(Navigation.Page.PROFILES,com.muoniumplayer.configskija.ui.pages.LibraryPages.profiles());view.register(Navigation.Page.KEYS,com.muoniumplayer.configskija.ui.pages.LibraryPages.keys());view.register(Navigation.Page.CREDITS,com.muoniumplayer.configskija.ui.pages.LibraryPages.credits());view.register(Navigation.Page.THEMES,com.muoniumplayer.configskija.ui.pages.AppearancePages.themes());view.register(Navigation.Page.PREFERENCES,com.muoniumplayer.configskija.ui.pages.AppearancePages.preferences());view.register(Navigation.Page.HUD,new com.muoniumplayer.configskija.ui.hud.HudStudio(host,prefs));
        for(String page:new String[]{"MODS","FAVORITES","PROFILES","THEMES","PREFERENCES","KEYS","CREDITS","HUD"}) {
            view.navigate(new Navigation.Route(Navigation.Page.valueOf(page)));
            render(view,regular,bold,fonts,icons,output.resolve(page.toLowerCase(Locale.ROOT)+".png"),1920,1080);
        }
        view.navigate(new Navigation.Route(Navigation.Page.SETTINGS,host.categories().get(0).id()));
        render(view,regular,bold,fonts,icons,output.resolve("settings.png"),1920,1080);
        render(view,regular,bold,fonts,icons,output.resolve("settings-small.png"),640,360);
        view.confirm("确认操作","这是用于检查遮罩、对话框与按钮层级的静态探针状态。",()->{});
        render(view,regular,bold,fonts,icons,output.resolve("dialog-confirm.png"),1920,1080);
        view.navigate(new Navigation.Route(Navigation.Page.MODS));
        String[] themes={"polyglass-dark","polyglass-light","minecraft-dark","minecraft-light"};
        for(String theme:themes) {
            prefs.set("theme",theme);
            render(view,regular,bold,fonts,icons,output.resolve("mods-"+theme+".png"),1920,1080);
        }
        for(Font font:fonts.values()) font.close();regular.close();bold.close();
        if(!host.errors.isEmpty()) throw new AssertionError(host.errors.toString());
        writeIndex(output);
        System.out.println("Skija raster smoke test passed: 15 fixture previews in "+output);
    }
    private static void writeIndex(Path output) throws Exception {
        String[] names={"mods","favorites","profiles","themes","preferences","keys","credits","hud","settings","settings-small","dialog-confirm","mods-polyglass-dark","mods-polyglass-light","mods-minecraft-dark","mods-minecraft-light"};
        StringBuilder html=new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>MuoniumConfig Skija probes</title><style>body{font:14px system-ui;background:#15191e;color:#e5e8ef;margin:24px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:20px}figure{margin:0}img{display:block;width:100%;height:auto;border:1px solid #555}figcaption{padding:8px 0}</style><main>");
        for(String name:names) html.append("<figure><a href=\"").append(name).append(".png\"><img loading=\"lazy\" src=\"").append(name).append(".png\"></a><figcaption>").append(name).append("</figcaption></figure>");
        html.append("</main>");Files.writeString(output.resolve("index.html"),html.toString());
    }
    static void render(ConfigView view,Typeface regular,Typeface bold,Map<String,Font> fonts,Map<String,SkijaPainter.Svg> icons,Path file,int width,int height) throws Exception {
        try(Surface surface=Surface.makeRasterN32Premul(width,height);Paint ink=new Paint().setAntiAlias(true)) {
            Canvas canvas=surface.getCanvas();canvas.clear(0xFF18232D);
            try(SkijaPainter painter=new SkijaPainter(()->canvas,new SkijaPainter.Text() {
                Font font(double size,boolean heavy) { return fonts.computeIfAbsent(size+":"+heavy,key->new Font(heavy?bold:regular,(float)size)); }
                public void draw(String text,double x,double y,double size,boolean heavy,int color) { Font font=font(size,heavy);canvas.drawString(text,(float)x,(float)(y-font.getMetrics().getAscent()),font,ink.setColor(color)); }
                public double measure(String text,double size,boolean heavy) { return font(size,heavy).measureTextWidth(text); }
                public Painter.TextBounds textBounds(String text, double size, boolean heavy) {
                    Font f = font(size, heavy); FontMetrics metrics = f.getMetrics();
                    io.github.humbleui.types.Rect ink = f.measureText(text);
                    // drawString uses baseline y - ascent; bounds must use that same line-top origin.
                    return ink.getWidth() > 0 && ink.getHeight() > 0
                            ? new Painter.TextBounds(ink.getTop() - metrics.getAscent(), ink.getBottom() - metrics.getAscent())
                            : new Painter.TextBounds(0, metrics.getDescent() - metrics.getAscent());
                }
            },icons)) { view.frame(painter,width,height,-100,-100,view.time()+2);view.frame(painter,width,height,-100,-100,view.time()+1); }
            try(Image image=surface.makeImageSnapshot();Data encoded=image.encodeToData(EncodedImageFormat.PNG)) {
                if(encoded==null) throw new AssertionError("PNG encoding failed");Files.write(file,encoded.getBytes());
            }
        }
    }
}
