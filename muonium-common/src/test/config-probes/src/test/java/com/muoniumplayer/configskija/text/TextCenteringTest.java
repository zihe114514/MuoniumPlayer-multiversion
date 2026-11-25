package com.muoniumplayer.configskija.text;

import com.muoniumplayer.configskija.host.text.HostTextRenderer;
import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.muoniumplayer.configskija.ui.settings.SettingsPage;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema;

/** Production painter on a CPU bitmap; no independent draw/measure approximation. */
public final class TextCenteringTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " vs " + expected);
    }
    static class RecordingPainter implements Painter {
        double x, y, size, iconY, iconSize, measuredSize;
        String value;
        public void save() {} public void restore() {}
        public void translate(double x,double y) {} public void scale(double x,double y) {}
        public void clip(Rect r,double radius) {} public void rect(Rect r,double radius,int color) {}
        public void outline(Rect r,double radius,double width,int color) {}
        public void line(double x,double y,double tx,double ty,double width,int color) {}
        public void text(String text,double x,double y,double size,boolean bold,int color) {
            this.value=text; this.x=x; this.y=y; this.size=size;
        }
        public double measure(String text,double size,boolean bold) { return text.length()*size*.5; }
        public void icon(String icon,double x,double y,double size,int color) { iconY=y;iconSize=size; }
    }
    static final class MetricPainter extends RecordingPainter {
        public TextBounds textBounds(String text,double size,boolean bold) {
            measuredSize=size;
            return new TextBounds(size*.25,size*1.15);
        }
    }
    private static final Rect[] inputBounds = new Rect[1];
    private static PageContext context(Painter p) {
        UiPreferences preferences=new UiPreferences(Map.of("animations","false"),()->{});
        return (PageContext)Proxy.newProxyInstance(PageContext.class.getClassLoader(),new Class<?>[]{TextInputController.TextInput.class},(proxy,m,a)-> {
            return switch(m.getName()) {
                case "painter" -> p;
                case "shell", "content" -> new Rect(0,0,1391,700);
                case "bareTextField" -> { inputBounds[0]=(Rect)a[2]; yield null; }
                case "theme" -> Theme.BUILTIN[0];
                case "preferences" -> preferences;
                case "route" -> new Navigation.Route(Navigation.Page.SETTINGS);
                case "animate" -> a[1];
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==a[0];
                case "mouseX", "mouseY" -> -100d;
                default -> m.getReturnType()==double.class?0d:m.getReturnType()==int.class?0:m.getReturnType()==boolean.class?false:null;
            };
        });
    }
    private static Object call(String type,String name,Object target,Class<?>[] types,Object... args) throws Exception {
        Method m=Class.forName(type).getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(target,args);
    }
    private static Object construct(String type,Class<?>[] types,Object... args) throws Exception {
        Constructor<?> ctor=Class.forName(type).getDeclaredConstructor(types);ctor.setAccessible(true);return ctor.newInstance(args);
    }
    public static void main(String[] args) throws Exception {
        Rect box=new Rect(11,23,180,38);
        RecordingPainter legacy=new RecordingPainter();legacy.textCentered("On",box,12,false,-1);
        near(legacy.y,36,0,"legacy nominal line box preserved");
        near(legacy.x,95,0,"legacy horizontal advance preserved");
        MetricPainter p=new MetricPainter();PageContext ctx=context(p);
        p.text("paragraph",1,37,14,false,-1);near(p.y,37,0,"ordinary text keeps line-top");
        p.textCentered("On",box,12,false,-1);
        near(p.y+p.textBounds("On",12,false).top()/2+p.textBounds("On",12,false).bottom()/2,42,1e-9,"ink center contract");
        String prefix="com.muoniumplayer.configskija.ui.";
        call(prefix+"settings.SettingsDraw","center",null,new Class<?>[]{PageContext.class,String.class,Rect.class,double.class,boolean.class,int.class},ctx,"On",box,12d,false,-1);
        near(p.y,33.6,1e-9,"settings center consumes real metrics");
        call(prefix+"hud.HudWidgets","text",null,new Class<?>[]{PageContext.class,String.class,Rect.class,double.class,boolean.class,int.class},ctx,"On",box,12d,false,-1);
        near(p.y,33.6,1e-9,"HUD text consumes real metrics");
        Object appearance=construct(prefix+"pages.AppearanceControls",new Class<?>[]{});
        call(prefix+"pages.AppearanceControls","center",appearance,new Class<?>[]{PageContext.class,String.class,Rect.class,double.class,int.class},ctx,"On",box,12d,-1);
        near(p.y,33.6,1e-9,"appearance center consumes real metrics");
        call(prefix+"pages.LibraryPaint","button",null,new Class<?>[]{PageContext.class,String.class,String.class,String.class,Rect.class,boolean.class,boolean.class,Runnable.class,String.class},ctx,"test","On","cog",box,false,true,(Runnable)()->{},"");
        near(p.y,32.2,1e-9,"library button consumes real metrics");
        near(p.iconY+p.iconSize/2,42,0,"library icon follows actual button height");
        call(prefix+"pages.LibraryPaint","center",null,new Class<?>[]{PageContext.class,String.class,double.class,double.class,double.class,boolean.class,int.class},ctx,"paragraph",90d,37d,14d,false,-1);
        near(p.y,37,0,"library horizontal-only center keeps line-top");
        SettingsPage page=new SettingsPage(false);
        String controlsType=prefix+"settings.SettingsControls";
        Object controls=construct(controlsType,new Class<?>[]{SettingsPage.class},page);
        ConfigHost.Option option=new ConfigHost.Option("probe","Probe","",ConfigHost.Kind.CHOICE,0,100,1,List.of("On","Off"),()->0,v->{},()->"",()->{});
        SettingsSchema.OptionStyle style=new SettingsSchema.OptionStyle(SettingsSchema.Visualizer.SWITCH);
        call(controlsType,"toggle",controls,new Class<?>[]{PageContext.class,ConfigHost.Option.class,SettingsSchema.OptionStyle.class,Rect.class,Consumer.class},ctx,option,style,box,(Consumer<Boolean>)v->{});
        near(p.y,33.6,1e-9,"On/Off uses control center with metrics");
        for(SettingsSchema.Visualizer visualizer:new SettingsSchema.Visualizer[]{SettingsSchema.Visualizer.DROPDOWN,SettingsSchema.Visualizer.HUD_DROPDOWN}) {
            style.visualizer=visualizer;
            call(controlsType,"dropdown",controls,new Class<?>[]{PageContext.class,ConfigHost.Option.class,SettingsSchema.OptionStyle.class,Rect.class,double.class},ctx,option,style,box,1d);
            near(p.y,42-(visualizer==SettingsSchema.Visualizer.HUD_DROPDOWN?12:13)*.7,1e-9,"dropdown uses its actual font size");
        }
        style.visualizer=SettingsSchema.Visualizer.NUMBER;style.unit="%";
        Rect spinner=new Rect(11,23,87,26);
        call(controlsType,"spinner",controls,new Class<?>[]{PageContext.class,ConfigHost.Option.class,String.class,Rect.class,double.class,double.class,double.class,double.class,SettingsSchema.OptionStyle.class,Consumer.class,double.class},ctx,option,"probe",spinner,12.5d,0d,100d,1d,style,(Consumer<Double>)v->{},1d);
        near(inputBounds[0].y(),36-13*.7,1e-9,"precision field passes the same centered line-top to the editor");
        check(inputBounds[0].h()>=p.textBounds("12.5%",13,false).bottom(),"precision input clipping includes actual descent");
        style.format=v->"12.50";
        call(controlsType,"spinner",controls,new Class<?>[]{PageContext.class,ConfigHost.Option.class,String.class,Rect.class,double.class,double.class,double.class,double.class,SettingsSchema.OptionStyle.class,Consumer.class,double.class},ctx,option,"probe",spinner,12.5d,0d,100d,1d,style,(Consumer<Double>)v->{},1d);
        near(p.y,36-13*.7,1e-9,"formatted precision overlay uses the same vertical contract");
        for(double density:new double[]{.75,1,1.25,1.5,2}) {
            Painter scaled=(Painter)construct(prefix+"hud.HudChromeContext$ScaledPainter",new Class<?>[]{Painter.class,double.class,Theme.class},p,density,Theme.BUILTIN[0]);
            Painter.TextBounds bounds=scaled.textBounds("On",12,false);
            near(p.measuredSize,12*density,1e-9,"density queries physical font size");
            near(bounds.top(),3,1e-9,"density returns logical top");
            near(bounds.bottom(),13.8,1e-9,"density returns logical bottom");
            scaled.textCenteredY("On",10,42,12,false,-1);
            near(p.y/density,33.6,1e-9,"density forwards centered line-top once");
        }
        System.out.println("TextCenteringTest contract/helpers: "+checks+" assertions passed");
        HudInputCenteringTest.verifyContract();
    }
    public static void verifyNative(HostTextRenderer renderer,Canvas canvas,Bitmap bitmap,AtomicReference<Theme> theme) throws Exception {
        checks=0;
        Theme previous=theme.get();
        try(SkijaPainter painter=new SkijaPainter(()->canvas,renderer,Map.of())) {
            Rect box=new Rect(40,70,420,44);
            for(Theme t:new Theme[]{Theme.BUILTIN[0],Theme.BUILTIN[2]}) {
                theme.set(t);
                for(double size:new double[]{12,13,14,17.5,24}) for(boolean bold:new boolean[]{false,true}) {
                    for(String text:List.of("On","Off","Music AV","Settings","中文设置","gyp 12.5%","AV汉字","Line 1\n中文gyp")) {
                        canvas.clear(0);painter.textCentered(text,box,size,bold,-1);
                        double[] ink=ink(bitmap);
                        check(ink[2]>0,"CPU drew "+t.id()+"/"+text);
                        near((ink[0]+ink[1])/2,box.y()+box.h()/2,1.01,"actual pixel center "+t.id()+"/"+text+"/"+size+"/"+bold);
                        Painter.TextBounds bounds=painter.textBounds(text,size,bold);
                        near(bounds.top(),renderer.textBounds(text,size,bold).top(),0,"SkijaPainter forwards production ink top");
                        canvas.clear(0);renderer.draw(text,40,40,size,bold,-1);ink=ink(bitmap);
                        near(ink[0],40+bounds.top(),1.01,"bounds match unchanged line-top pixels");
                        near(ink[1],40+bounds.bottom(),1.01,"bounds match unchanged line-top bottom");
                    }
                }
                for(String text:List.of(""," ","   ","\n")) {
                    Painter.TextBounds bounds=painter.textBounds(text,14,false);
                    check(bounds.height()>0 && Double.isFinite(bounds.centeredTop(40)),"empty text uses finite font line box");
                    canvas.clear(0);painter.textCenteredY(text,20,40,14,false,-1);
                    check(ink(bitmap)[2]==0,"blank text creates no visible ink");
                }
            }
            theme.set(Theme.BUILTIN[0]);
            canvas.clear(0);painter.icon("missing-icon-for-centering-probe",40,40,18,-1);
            double[] missingIcon=ink(bitmap);
            near((missingIcon[0]+missingIcon[1])/2,49,1.01,"missing SVG fallback glyph centers inside its icon box");
            for(double density:new double[]{.75,1.25,1.5,2}) {
                Painter scaled=(Painter)construct("com.muoniumplayer.configskija.ui.hud.HudChromeContext$ScaledPainter",new Class<?>[]{Painter.class,double.class,Theme.class},painter,density,theme.get());
                canvas.clear(0);scaled.textCenteredY("中文 On gyp",20,70,14,false,-1);
                double[] ink=ink(bitmap);
                near((ink[0]+ink[1])/2,70*density,1.01,"CPU physical density center "+density);
            }
            for(String text:List.of("e\u0301","𝄞","👩‍👩‍👧‍👧")) {
                Painter.TextBounds bounds=renderer.textBounds(text,14,false);
                check(bounds.height()>0 && Double.isFinite(bounds.centeredTop(70)),"complex fallback supplies a finite font-metric box");
            }
            java.util.concurrent.atomic.AtomicReference<Throwable> crossThread=new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker=new Thread(()-> {try {renderer.textBounds("On",14,false);} catch(Throwable error) {crossThread.set(error);}});
            worker.start();worker.join();
            check(crossThread.get() instanceof IllegalStateException,"text bounds preserve render-thread ownership");
            Painter.TextBounds before=renderer.textBounds("中文设置",14,false);
            long generation=renderer.statistics().generation();renderer.invalidate();
            Painter.TextBounds after=renderer.textBounds("中文设置",14,false);
            check(renderer.statistics().generation()>generation,"font invalidation refreshes bounds cache");
            near(before.top(),after.top(),0,"rebuilt ink top stable");
            near(before.bottom(),after.bottom(),0,"rebuilt ink bottom stable");
            Painter.TextBounds longBounds=renderer.textBounds("On ".repeat(700),14,false);
            check(Double.isFinite(longBounds.height()),"uncached long layout measures and releases");
        } finally { theme.set(previous); }
        System.out.println("TextCenteringTest production CPU pixels: "+checks+" assertions passed");
        HudInputCenteringTest.verifyNative(renderer, canvas, bitmap, theme);
    }
    private static double[] ink(Bitmap bitmap) {
        byte[] pixels=bitmap.readPixels();int top=512,bottom=-1,count=0;
        for(int y=0;y<512;y++)for(int x=0;x<1024;x++)if((pixels[(y*1024+x)*4+3]&255)>0) {top=Math.min(top,y);bottom=Math.max(bottom,y);count++;}
        return new double[]{top,bottom+1,count};
    }
}
