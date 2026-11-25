package com.muoniumplayer.configskija;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone Java 17 behavioral tests. No game, native library or loader substitutes are on this path. */
public final class CoreTest {
    private static int assertions;
    public static void main(String[] args) {
        history();text();navigation();geometry();hud();view();colors();
        System.out.println("CoreTest passed: "+assertions+" behavioral assertions (Java "+System.getProperty("java.version")+")");
    }
    static void check(boolean value,String message) { assertions++;if(!value) throw new AssertionError(message); }
    static void near(double a,double b,String message) { check(Math.abs(a-b)<.001,message+": "+a+" != "+b); }
    static void history() {
        AtomicInteger value=new AtomicInteger();History<Integer> history=new History<>(2,value::get,value::set);
        history.begin("drag");value.set(1);value.set(2);value.set(3);history.commit();check(history.undoSize()==1,"drag is one undo step");
        history.undo();check(value.get()==0,"undo restores start");history.redo();check(value.get()==3,"redo restores end");
        history.begin("cancel");value.set(44);history.cancel();check(value.get()==3,"cancel restores");
        history.begin("no change");check(!history.commit(),"empty transactions omitted");
        history.begin("second");value.set(4);history.commit();history.begin("third");value.set(5);history.commit();check(history.undoSize()==2,"bounded history");
        history.undo();history.begin("branch");value.set(6);history.commit();check(!history.canRedo(),"new changes invalidate redo");
        history.begin("open");boolean nested=false;try { history.begin("nested"); }catch(IllegalStateException e) { nested=true; }check(nested,"nested transactions rejected");history.cancel();
    }
    static void text() {
        TextEdit t=new TextEdit();t.text("甲😀乙");t.move(-1,false,false);t.delete(true,false);check(t.text().equals("甲乙"),"backspace removes an entire supplementary character");
        t.selectAll();t.insert("歌词\nA\tB");check(t.text().equals("歌词AB"),"paste removes controls");
        t.text("a😀b");t.edge(false,false);t.move(1,true,false);check(t.selectedText().equals("a"),"shift selection");t.insert("中");check(t.text().equals("中😀b"),"selection replacement");
        t.text("first second");t.move(-1,false,true);check(t.cursor()==6,"word motion");
        t.text("abcdef");t.click(2.4,String::length,false);check(t.cursor()==2,"measured click placement");
        t.text("x".repeat(4095));t.insert("😀");check(t.text().length()==4095,"length limit never splits a code point");
        t.text("\uD800");check(t.text().isEmpty(),"unpaired surrogates filtered");
    }
    static void navigation() {
        Navigation.Route a=new Navigation.Route(Navigation.Page.SETTINGS,"lyrics"),b=new Navigation.Route(Navigation.Page.THEMES);
        Navigation n=new Navigation(a);n.remember(84,"歌词");n.navigate(b);n.remember(20,"");check(n.back(),"back exists");check(n.current().equals(a),"back route");near(n.memory().scroll(),84,"per page scroll restored");check(n.memory().search().equals("歌词"),"search preserved");
        check(n.forward(),"forward exists");n.back();n.navigate(new Navigation.Route(Navigation.Page.FAVORITES));check(!n.canForward(),"new route clears forward");
    }
    static void geometry() {
        for(double[] size:new double[][]{{320,200},{960,540},{1920,1080},{3840,2160},{640,1000}}) for(double scale:new double[]{.5,1,1.5}) {
            Viewport v=Viewport.fit(size[0],size[1],scale);Point s=v.screen(493,277),r=v.local(s.x(),s.y());near(r.x(),493,"viewport x round trip");near(r.y(),277,"viewport y round trip");check(v.scale()>0,"positive scale");near(v.width()*v.scale(),Math.min(size[0]*.9,size[1]*.9*1391/700)*scale,"source user scale follows 90 percent fit");if(scale<=1) check(v.x()>=0&&v.y()>=0,"default and smaller shell remain in window");
        }
        Rect r=new Rect(0,0,10,10);check(r.contains(0,0),"leading edge included");check(!r.contains(10,5),"trailing edge excluded");check(!r.intersects(new Rect(10,0,4,4)),"touching rectangles do not overlap");
        double[] snapped=HudDesigner.snap(94,100,List.of(100d),6);near(snapped[0],94,"source snap threshold is strict");check(Double.isNaN(snapped[1]),"boundary outside magnet has no guide");double[] inside=HudDesigner.snap(95,100,List.of(100d),6);near(inside[0],100,"source snap applies inside threshold");
    }
    static void hud() {
        FakeHud a=new FakeHud("a",.1,.1,100,50,false),b=new FakeHud("b",.6,.6,120,60,true);AtomicInteger saved=new AtomicInteger();UiPreferences prefs=new UiPreferences(Map.of(),()->{});
        HudDesigner d=new HudDesigner(List.of(a,b),prefs,saved::incrementAndGet);d.viewport(1000,600);d.select("a",false);d.select("b",true);Rect beforeA=d.rect(a),beforeB=d.rect(b);
        check(d.begin(beforeA.x()+10,beforeA.y()+10,null),"group drag begins");d.drag(beforeA.x()+30,beforeA.y()+30,true);d.end();near(d.rect(a).x()-beforeA.x(),20,"group drag first x");near(d.rect(b).x()-beforeB.x(),20,"group drag keeps relative x");check(saved.get()==1,"one persist on drag completion");
        d.history.undo();near(d.rect(a).x(),beforeA.x(),"undo drag");near(d.rect(b).y(),beforeB.y(),"undo group member");d.history.redo();
        d.select("b",false);Rect r=d.rect(b);d.begin(r.right(),r.bottom(),HudDesigner.Corner.BOTTOM_RIGHT);d.drag(r.right()+70,r.bottom()+5,true);d.end();near(b.width()/b.height(),2,"aspect locked resize");
        d.toggleLocked();double x=b.x();d.moveBy(20,0);near(b.x(),x,"locked item cannot move");d.history.undo();check(!prefs.locked("b"),"undo lock");
        d.select("a",false);d.align(HudDesigner.Align.CENTER_X);near(d.rect(a).x(),450,"single selection aligns to screen");
        d.toggleHidden();check(a.hidden(),"hide writes actual element");d.history.undo();check(!a.hidden(),"undo visibility");
        d.marquee(new Rect(0,0,1000,600),false);check(d.selected().size()==2,"marquee selects overlapping elements");
        d.viewport(20,20);d.moveBy(5,5);check(Double.isFinite(a.x())&&Double.isFinite(a.y()),"oversize elements produce finite normalized positions");
    }
    static void view() {
        FakeHost host=new FakeHost();ConfigHost.Option second=host.addOption("second-toggle",ConfigHost.Kind.TOGGLE,false);host.addCategory(new ConfigHost.Category("second","Second category","","",List.of(new ConfigHost.Group("second/group","General",List.of(second)))));ConfigView v=new ConfigView(host,new UiPreferences(Map.of("instantSearch","false"),()->{}));check(v.route().page()==Navigation.Page.MODS,"cold open follows the original ModsGraph route");v.navigate(new Navigation.Route(Navigation.Page.SETTINGS,host.categories().get(0).id()));FakePainter p=new FakePainter();v.frame(p,960,540,-100,-100,1);
        Rect toggle=v.hitBounds().get("settings.toggle.toggle");check(toggle!=null,"actual source setting control rendered");click(v,toggle);check(Boolean.TRUE.equals(host.values.get("toggle")),"screen to layout hit test writes host setting");
        Rect category=v.hitBounds().get("settings.category.second");check(category!=null,"category chip is rendered as a screen hit target");click(v,category);
        check(v.route().page()==Navigation.Page.SETTINGS&&v.route().category().equals("second"),"category hit routes within Settings without leaving its page");
        v.frame(p,960,540,-100,-100,1.2);check(v.hitBounds().containsKey("settings.toggle.second-toggle"),"routed category renders its own setting options");check(!v.hitBounds().containsKey("settings.toggle.toggle"),"routed category hides options from the previous category");
        v.key(ConfigView.Key.Z,"",true,false,false);check(Boolean.FALSE.equals(host.values.get("toggle")),"setting undo reaches host");
        v.navigate(new Navigation.Route(Navigation.Page.FAVORITES));v.frame(p,960,540,-100,-100,3);check(!v.hitBounds().containsKey("settings.toggle.toggle"),"original FavoritesTree does not invent pinned controls without a host tree");
        v.navigate(new Navigation.Route(Navigation.Page.SETTINGS));v.frame(p,960,540,-100,-100,4);v.key(ConfigView.Key.F,"",true,false,false);v.key(ConfigView.Key.NONE,"precision",false,false,false);v.frame(p,960,540,-100,-100,5);
        check(v.hitBounds().keySet().stream().anyMatch(id->id.startsWith("settings.slider.")&&id.endsWith("slider")),"source description matching renders the slider");check(!v.hitBounds().containsKey("settings.toggle.toggle"),"search filters unmatched settings");
        v.key(ConfigView.Key.A,"",true,false,false);v.key(ConfigView.Key.BACKSPACE,"",false,false,false);v.key(ConfigView.Key.ENTER,"",false,false,false);v.frame(p,960,540,-100,-100,6);
        Rect swatch=v.hitBounds().get("settings.color.color");click(v,swatch);v.frame(p,960,540,-100,-100,7);Rect sv=v.hitBounds().get("settings.picker.color.sv");check(sv!=null,"actual source color popup rendered");click(v,sv);check(!host.values.get("color").equals(0xff123456),"picker updates live color");Object chosen=host.values.get("color");v.key(ConfigView.Key.ESCAPE,"",false,false,false);check(host.values.get("color").equals(chosen),"source Escape closes and commits the picker");v.key(ConfigView.Key.Z,"",true,false,false);check(host.values.get("color").equals(0xff123456),"picker transaction is undone as one step");
        v.frame(p,640,360,-100,-100,8);Rect dropdown=v.hitBounds().get("settings.choice.choice");click(v,dropdown);v.frame(p,640,360,-100,-100,9);Rect secondChoice=v.hitBounds().get("settings.choice.item.choice.1");check(secondChoice!=null,"source choice popup available after resize");click(v,secondChoice);check(host.values.get("choice").equals("B"),"dropdown writes selected choice");
        v.dispose();check(host.errors.isEmpty(),"no swallowed UI failures: "+host.errors);check(p.depth==0,"all painter saves restored");
    }
    static void colors() { check(ConfigView.parseColor("#123456")==0xff123456,"RGB input");check(ConfigView.parseColor("80123456")==0x80123456,"ARGB input");boolean invalid=false;try { ConfigView.parseColor("#123"); }catch(IllegalArgumentException e) { invalid=true; }check(invalid,"invalid color rejected"); }
    static void click(ConfigView v,Rect r) { check(r!=null,"clickable rectangle exists");Point screen=v.editingHud()?new Point(r.x()+r.w()/2,r.y()+r.h()/2):v.viewport().screen(r.x()+r.w()/2,r.y()+r.h()/2);v.pointerDown(screen.x(),screen.y(),0,false,false,false);v.pointerUp(screen.x(),screen.y(),0); }
    public static final class FakeHud implements HudElement {
        String id;double x,y,w,h,s=1;boolean hidden,aspect;
        public FakeHud(String id,double x,double y,double w,double h,boolean aspect) { this.id=id;this.x=x;this.y=y;this.w=w;this.h=h;this.aspect=aspect; }
        public String id() { return id; }public String title() { return id; }public double x() { return x; }public double y() { return y; }public double width() { return w*s; }public double height() { return h*s; }public double scale() { return s; }public boolean hidden() { return hidden; }
        public void position(double x,double y) { this.x=x;this.y=y; }public void size(double w,double h) { if(aspect) { this.h=w/this.w*this.h; }else this.h=h/s;this.w=w/s; }public void scale(double s) { this.s=s; }public void hidden(boolean v) { hidden=v; }public boolean aspectLocked() { return aspect; }
    }
    public static class FakeHost implements ConfigHost {
        public final Map<String,Object> values=new HashMap<>(Map.of("toggle",false,"slider",.5,"choice","A","color",0xff123456));public final List<String> errors=new ArrayList<>();
        private List<Category> categories;
        public FakeHost() {
            List<Option> options=new ArrayList<>();for(String id:List.of("toggle","slider","choice","color")) { Kind kind=Kind.valueOf(id.toUpperCase(Locale.ROOT));options.add(new Option(id,switch(id) { case "toggle"->"启用歌词";case "slider"->"歌词字号";case "choice"->"歌词样式";default->"歌词颜色"; },id.equals("slider")?"precision slider":"用于行为和渲染测试",kind,0,1,0,List.of("A","B","C"),()->values.get(id),v->values.put(id,v),()->"",null)); }
            categories=List.of(new Category("lyrics","桌面歌词","","hud",List.of(new Group("lyrics/base","歌词外观",options))));
        }
        public Option addOption(String id,Kind kind,Object value) { values.put(id,value);return new Option(id,id,"",kind,0,1,0,List.of(),()->values.get(id),v->values.put(id,v),()->"",null); }
        public void addCategory(Category category) { List<Category> next=new ArrayList<>(categories);next.add(category);categories=List.copyOf(next); }
        public List<Category> categories() { return categories; }public List<HudElement> huds() { return List.of(); }public void persist() {}public void close() {}public String clipboard() { return ""; }public void clipboard(String s) {}public void error(String a,Throwable e) { errors.add(a+": "+e); }
    }
    public static class FakePainter implements Painter {
        int depth;
        public void save() { depth++; }public void restore() { depth--;check(depth>=0,"balanced painter state"); }public void translate(double x,double y) {}public void scale(double x,double y) {}public void clip(Rect r,double radius) {}public void rect(Rect r,double radius,int color) {}public void outline(Rect r,double radius,double width,int color) {}public void line(double a,double b,double c,double d,double w,int color) {}public void text(String s,double x,double y,double size,boolean bold,int color) {}public double measure(String s,double size,boolean bold) { return s.codePointCount(0,s.length())*size*.55; }
    }
}
