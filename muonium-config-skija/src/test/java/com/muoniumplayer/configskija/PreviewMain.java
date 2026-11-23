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
        for(String page:new String[]{"SETTINGS","FAVORITES","MODS","THEMES","PREFERENCES","KEYS","CREDITS","HUD"}) {
            view.navigate(new Navigation.Route(Navigation.Page.valueOf(page)));
            render(view,regular,bold,fonts,icons,output.resolve(page.toLowerCase(Locale.ROOT)+".png"),1920,1080);
        }
        view.navigate(new Navigation.Route(Navigation.Page.SETTINGS));render(view,regular,bold,fonts,icons,output.resolve("settings-small.png"),640,360);
        prefs.set("theme","polyglass-light");render(view,regular,bold,fonts,icons,output.resolve("settings-light.png"),1920,1080);
        for(Font font:fonts.values()) font.close();regular.close();bold.close();
        if(!host.errors.isEmpty()) throw new AssertionError(host.errors.toString());
        System.out.println("Skija raster smoke test passed: 10 fixture previews in "+output);
    }
    static void render(ConfigView view,Typeface regular,Typeface bold,Map<String,Font> fonts,Map<String,SkijaPainter.Svg> icons,Path file,int width,int height) throws Exception {
        try(Surface surface=Surface.makeRasterN32Premul(width,height);Paint ink=new Paint().setAntiAlias(true)) {
            Canvas canvas=surface.getCanvas();canvas.clear(0xFF18232D);
            try(SkijaPainter painter=new SkijaPainter(()->canvas,new SkijaPainter.Text() {
                Font font(double size,boolean heavy) { return fonts.computeIfAbsent(size+":"+heavy,key->new Font(heavy?bold:regular,(float)size)); }
                public void draw(String text,double x,double y,double size,boolean heavy,int color) { Font font=font(size,heavy);canvas.drawString(text,(float)x,(float)(y-font.getMetrics().getAscent()),font,ink.setColor(color)); }
                public double measure(String text,double size,boolean heavy) { return font(size,heavy).measureTextWidth(text); }
            },icons)) { view.frame(painter,width,height,-100,-100,view.time()+2);view.frame(painter,width,height,-100,-100,view.time()+1); }
            try(Image image=surface.makeImageSnapshot();Data encoded=image.encodeToData(EncodedImageFormat.PNG)) {
                if(encoded==null) throw new AssertionError("PNG encoding failed");Files.write(file,encoded.getBytes());
            }
        }
    }
}
