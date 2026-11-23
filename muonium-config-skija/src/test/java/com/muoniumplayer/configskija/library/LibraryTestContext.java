package com.muoniumplayer.configskija.library;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;

final class LibraryTestContext implements PageContext {
    record Hit(Rect bounds,Runnable action,String tooltip) {}
    record Field(TextEdit edit,Consumer<String> changed) {}
    final ConfigHost host;
    final UiPreferences prefs=new UiPreferences(Map.of(),()->{});
    final RecordingPainter painter=new RecordingPainter();
    final Map<String,Hit> hits=new LinkedHashMap<>();
    final Map<String,Field> fields=new HashMap<>();
    final List<String> messages=new ArrayList<>();
    final Map<String,String> tips=new HashMap<>();
    Rect bounds=new Rect(289,88,1077,587);
    Navigation.Route route=new Navigation.Route(Navigation.Page.MODS);
    String query="";
    double mx=-100,my=-100,scroll,maxScroll,time=1;
    int nativeCode;
    BiConsumer<Double,Double> drag;
    Runnable finish;
    UiPage overlay;
    LibraryTestContext(ConfigHost host){this.host=host;}
    void frame(UiPage page){for(int step=0;step<2;step++){hits.clear();fields.clear();tips.clear();painter.texts.clear();time+=.75;page.render(this);}if(painter.depth!=0)throw new AssertionError("Unbalanced painter stack: "+painter.depth);if(!bounds.equals(new Rect(289,88,1077,587)))throw new AssertionError("Page leaked content bounds "+bounds);}
    void click(String id){Hit hit=hits.get(id);if(hit==null)throw new AssertionError("Missing hit: "+id+"; available "+hits.keySet());hit.action.run();}
    Rect rect(String id){Hit hit=hits.get(id);if(hit==null)throw new AssertionError("Missing bounds: "+id);return hit.bounds;}
    void field(String id,String value){Field f=fields.get(id);if(f==null)throw new AssertionError("Missing field "+id);f.edit.text(value);f.changed.accept(value);}
    void move(Rect r){mx=r.x()+r.w()/2;my=r.y()+r.h()/2;}
    public Painter painter(){return painter;}
    public Theme theme(){return Theme.BUILTIN[0];}
    public int accent(){return 0xFF6677FF;}
    public ConfigHost host(){return host;}
    public UiPreferences preferences(){return prefs;}
    public String query(){return query;}
    public Navigation.Route route(){return route;}
    public double mouseX(){return mx;}
    public double mouseY(){return my;}
    public double width(){return 1391;}
    public double height(){return 700;}
    public double time(){return time;}
    public double delta(){return .05;}
    public boolean control(){return false;}
    public boolean shift(){return false;}
    public boolean alt(){return false;}
    public int nativeKeyCode(){return nativeCode;}
    public boolean textFocused(){return fields.values().stream().anyMatch(f->f.edit().focused);}
    public Rect content(){return bounds;}
    public void content(Rect bounds){this.bounds=bounds;}
    public double scroll(){return scroll;}
    public void scroll(double value){scroll=Math.max(0,value);}
    public void scrollRange(double maximum){maxScroll=maximum;scroll=Geometry.clamp(scroll,0,maximum);}
    public void hit(String id,Rect bounds,Runnable action,String tooltip){hits.put(id,new Hit(bounds,action,tooltip));}
    public void contentHit(String id,Rect r,Runnable action,String tooltip){double x=Math.max(bounds.x(),r.x()),y=Math.max(bounds.y(),r.y()),right=Math.min(bounds.right(),r.right()),bottom=Math.min(bounds.bottom(),r.bottom());if(right>x&&bottom>y)hit(id,new Rect(x,y,right-x,bottom-y),action,tooltip);}
    public void tooltip(String id,Rect r,String text){tips.put(id,text);}
    public void startDrag(BiConsumer<Double,Double> update,Runnable finish){this.drag=update;this.finish=finish;}
    public void navigate(Navigation.Route route){this.route=route;}
    public void toast(String message){messages.add(message);}
    public void sound(String event){}
    public void edit(ConfigHost.Option o,Object value){o.set(value);}
    public void beginEdit(String name){}
    public void commitEdit(){}
    public void cancelEdit(){}
    public void textField(String id,TextEdit edit,Rect r,String placeholder,Consumer<String> changed){fields.put(id,new Field(edit,changed));}
    public void textDialog(String title,String hint,String initial,String action,Consumer<String> submit){throw new AssertionError("Profile editors should be inline");}
    public void confirm(String title,String description,Runnable accepted){throw new AssertionError("Expected inline confirmation");}
    public void overlay(UiPage page){overlay=page;}
    public void dismissOverlay(){overlay=null;}
    public void close(){}
    public double animate(String key,double target,double seconds,Motion.Easing easing){return target;}
    static final class RecordingPainter implements Painter {
        record Text(String value,double x,double y,double size,boolean bold,int color) {}
        final List<Text> texts=new ArrayList<>();
        int depth;
        public void save(){depth++;}
        public void restore(){depth--;if(depth<0)throw new AssertionError("restore without save");}
        public void translate(double x,double y){}
        public void scale(double x,double y){}
        public void clip(Rect r,double radius){}
        public void rect(Rect r,double radius,int color){if(!Double.isFinite(r.w())||r.w()<0||r.h()<0)throw new AssertionError("Invalid rect "+r);}
        public void outline(Rect r,double radius,double width,int color){}
        public void line(double x1,double y1,double x2,double y2,double width,int color){}
        public void text(String text,double x,double y,double size,boolean bold,int color){texts.add(new Text(text,x,y,size,bold,color));}
        public double measure(String text,double size,boolean bold){return text.codePointCount(0,text.length())*size*.55;}
        public void icon(String icon,double x,double y,double size,int color){}
    }
}
