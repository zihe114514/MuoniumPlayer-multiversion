package com.muoniumplayer.configskija.text;

import com.muoniumplayer.configskija.host.text.HostTextRenderer;
import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Invokes the actual HUD chrome input; no independent editor or hit-test implementation. */
public final class HudInputCenteringTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual-expected)<1e-7,message+": "+actual+" vs "+expected);
    }
    private static final Rect FIELD=new Rect(20,65,176,32);
    private record Case(String value,String placeholder,boolean focused,boolean selected) {}
    private static final List<Case> CASES=List.of(
            new Case("12.5","",true,false),new Case("中文 gyp","Search...",false,false),
            new Case("","Search...",false,false),new Case("","搜索设置",false,false),
            new Case("","Search...",true,false),new Case("gyp 12.5%","",true,true),
            new Case("1234567890".repeat(8),"",true,false));
    private static final class Recorder extends TextCenteringTest.RecordingPainter {
        final List<Rect> rectangles=new ArrayList<>();
        final List<double[]> strokes=new ArrayList<>();
        Rect clipped;
        @Override public TextBounds textBounds(String value,double size,boolean bold) {
            return new TextBounds(size*.3,size*1.4);
        }
        @Override public void rect(Rect r,double radius,int color) { rectangles.add(r); }
        @Override public void line(double x,double y,double tx,double ty,double width,int color) { strokes.add(new double[]{x,y,tx,ty}); }
        @Override public void clip(Rect r,double radius) { clipped=r; }
    }
    private static final class Fixture {
        final Object chrome, focus;
        final Method input;
        final double density;
        final Consumer<String> changed=value->{throw new AssertionError("Drawing must not edit host values");};
        double time=.1, mouseX;
        Rect hitBounds;
        Runnable action;
        Fixture(Painter painter,double density,Supplier<Theme> theme) throws Exception {
            this.density=density;
            UiPreferences preferences=new UiPreferences(Map.of(),()->{});
            ConfigHost host=(ConfigHost)Proxy.newProxyInstance(ConfigHost.class.getClassLoader(),new Class<?>[]{ConfigHost.class},(proxy,m,a)-> {
                if(m.getName().equals("uiDensity"))return density;
                return null;
            });
            PageContext base=(PageContext)Proxy.newProxyInstance(PageContext.class.getClassLoader(),new Class<?>[]{PageContext.class},(proxy,m,a)-> {
                return switch(m.getName()) {
                    case "host" -> host;
                    case "painter" -> painter;
                    case "theme" -> theme.get();
                    case "preferences" -> preferences;
                    case "time" -> time;
                    case "mouseX" -> mouseX;
                    case "mouseY" -> (FIELD.y()+FIELD.h()/2)*density;
                    case "accent" -> 0xff3080ff;
                    case "hit" -> { hitBounds=(Rect)a[1];action=(Runnable)a[2];yield null; }
                    case "width" -> 1024d;
                    case "height" -> 512d;
                    case "control", "shift", "alt", "textFocused" -> false;
                    default -> null;
                };
            });
            Class<?> type=Class.forName("com.muoniumplayer.configskija.ui.hud.HudChromeContext");
            Class<?> focusType=Class.forName(type.getName()+"$Focus");
            Constructor<?> f=focusType.getDeclaredConstructor();f.setAccessible(true);focus=f.newInstance();
            Constructor<?> c=type.getDeclaredConstructor(PageContext.class,focusType);c.setAccessible(true);chrome=c.newInstance(base,focus);
            input=type.getDeclaredMethod("input",String.class,TextEdit.class,Rect.class,String.class,Consumer.class,boolean.class,double.class);
            input.setAccessible(true);
        }
        void render(TextEdit edit,String placeholder,double size) throws Exception {
            input.invoke(chrome,"probe",edit,FIELD,placeholder,changed,false,size);
        }
        void checkState(TextEdit edit,TextEdit.Snapshot before,long revision) throws Exception {
            check(before.equals(edit.snapshot()),"drawing preserves text, cursor and selection");
            check(revision==edit.revision(),"drawing preserves edit revision");
            near(hitBounds.x(),FIELD.x()*density,"hit x unchanged");
            near(hitBounds.y(),FIELD.y()*density,"hit y unchanged");
            near(hitBounds.w(),FIELD.w()*density,"hit width unchanged");
            near(hitBounds.h(),FIELD.h()*density,"hit height unchanged");
            if(edit.focused) {
                Field f=focus.getClass().getDeclaredField("edit");f.setAccessible(true);
                check(f.get(focus)==edit,"focus keeps the same editor");
                f=focus.getClass().getDeclaredField("changed");f.setAccessible(true);
                check(f.get(focus)==changed,"focus keeps the same change callback");
            }
        }
    }
    private static TextEdit editor(Case test) {
        TextEdit edit=new TextEdit();edit.text(test.value());edit.focused=test.focused();
        if(test.selected())edit.selection(1,5);
        return edit;
    }
    public static void verifyContract() throws Exception {
        checks=0;
        for(double density:new double[]{.75,1,1.5,2})for(double size:new double[]{12,13,17.5})for(Case test:CASES) {
            Recorder p=new Recorder();Fixture fixture=new Fixture(p,density,()->Theme.BUILTIN[0]);
            TextEdit edit=editor(test);TextEdit.Snapshot before=edit.snapshot();long revision=edit.revision();
            fixture.render(edit,test.placeholder(),size);fixture.checkState(edit,before,revision);
            check(edit.focused==test.focused(),"drawing preserves focus state");
            String shown=test.value().isEmpty()&&!test.focused()?test.placeholder():test.value();
            check(p.value.equals(shown),"input renders actual text or the unfocused placeholder");
            Painter.TextBounds bounds=p.textBounds(shown,size*density,false);
            double top=p.y+bounds.top(),bottom=p.y+bounds.bottom(),center=(FIELD.y()+FIELD.h()/2)*density;
            near((top+bottom)/2,center,"HUD input ink centers at actual field midpoint");
            double start=p.measure(test.value().substring(0,edit.cursor()),size*density,false)/density;
            near(p.x,(FIELD.x()+12-Math.max(0,start-FIELD.w()+28))*density,"horizontal scrolling unchanged");
            near(p.clipped.y(),FIELD.y()*density,"input clip top unchanged");
            near(p.clipped.h(),FIELD.h()*density,"input clip height unchanged");
            if(test.focused()) {
                check(p.strokes.size()==1,"active caret draws in its existing blink phase");
                near(p.strokes.get(0)[1],top,"caret starts at the same visible top as text");
                near(p.strokes.get(0)[3],bottom,"caret ends at the same visible bottom as text");
            } else check(p.strokes.isEmpty(),"unfocused input has no caret");
            if(test.selected()) {
                check(p.rectangles.size()==1,"existing selection is rendered once");
                near(p.rectangles.get(0).y(),top,"selection shares text ink top");
                near(p.rectangles.get(0).h(),bounds.height(),"selection shares text ink height");
            } else check(p.rectangles.isEmpty(),"drawing does not introduce a selection");
        }
        Recorder p=new Recorder();Fixture fixture=new Fixture(p,1,()->Theme.BUILTIN[0]);
        TextEdit edit=new TextEdit();edit.text("12345");edit.focused=true;fixture.time=.8;
        fixture.render(edit,"",13);check(p.strokes.isEmpty(),"caret blink-off phase preserved");
        fixture.mouseX=FIELD.x()+12+p.measure("12",13,false);fixture.action.run();
        check(edit.cursor()==2&&!edit.selected()&&edit.focused,"existing click action still places the caret by horizontal advance");
        System.out.println("HudInputCenteringTest geometry/state: "+checks+" assertions passed");
    }
    public static void verifyNative(HostTextRenderer renderer,Canvas canvas,Bitmap bitmap,AtomicReference<Theme> theme) throws Exception {
        checks=0;Theme previous=theme.get();
        try(SkijaPainter p=new SkijaPainter(()->canvas,renderer,Map.of())) {
            for(Theme palette:new Theme[]{Theme.BUILTIN[0],Theme.BUILTIN[2]}) {
                theme.set(palette);
                for(double density:new double[]{.75,1,1.5,2})for(double size:new double[]{12,13,17.5})for(Case test:CASES) {
                    Fixture fixture=new Fixture(p,density,theme::get);TextEdit edit=editor(test);
                    canvas.clear(0);fixture.render(edit,test.placeholder(),size);
                    byte[] pixels=bitmap.readPixels();int top=512,bottom=-1,count=0;
                    for(int y=0;y<512;y++)for(int x=0;x<1024;x++)if((pixels[(y*1024+x)*4+3]&255)>0){top=Math.min(top,y);bottom=Math.max(bottom,y);count++;}
                    check(count>0,"CPU renders text/placeholder/caret in actual HUD input");
                    check(Math.abs((top+bottom+1)/2d-(FIELD.y()+FIELD.h()/2)*density)<=1.01,
                            "CPU HUD input pixels center: "+palette.id()+" / "+test.value()+" / "+density+" / "+size+" -> "+top+","+bottom);
                }
            }
        } finally {theme.set(previous);}
        System.out.println("HudInputCenteringTest production CPU pixels: "+checks+" assertions passed");
    }
}
