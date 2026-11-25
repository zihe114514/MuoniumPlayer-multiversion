package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.model.Navigation.*;
import com.muoniumplayer.configskija.ui.Geometry.*;
import java.util.*;

/** Real ConfigView + SettingsPage CPU input probe, including moving controls and navigation memory. */
public final class ConfigInteractionTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void near(double a,double b,String why){check(Math.abs(a-b)<.00001,why+": "+a+" / "+b);}
    static final class Host implements ConfigHost {
        final Map<String,Object> values=new HashMap<>();final List<Category> categories=new ArrayList<>();
        Host(){for(String category:List.of("a","b")){List<Option> options=new ArrayList<>();for(int i=0;i<(category.equals("a")?40:2);i++){
            String id=category+i;values.put(id,false);options.add(new Option(id,id,"",Kind.TOGGLE,0,1,1,List.of(),()->values.get(id),v->values.put(id,v),()->"",()->{}));
        }categories.add(new Category(category,category,"","",List.of(new Group(category,"General",options))));}}
        public List<Category> categories(){return categories;}public List<HudElement> huds(){return List.of();}
        public void persist(){}public void close(){}public String clipboard(){return "";}public void clipboard(String s){}
        public void error(String action,Throwable error){throw new AssertionError(action,error);}
    }
    record Text(String value,double y,double size,boolean bold){}
    static final class Paint implements Painter {
        int depth;final List<Text> text=new ArrayList<>();final Map<String,Rect> icons=new HashMap<>();
        public void save(){depth++;}public void restore(){check(--depth>=0,"balanced painter");}
        public void translate(double x,double y){}public void scale(double x,double y){}public void clip(Rect r,double radius){}
        public void rect(Rect r,double radius,int color){}public void outline(Rect r,double radius,double w,int color){}
        public void line(double x,double y,double x2,double y2,double w,int color){}
        public void text(String s,double x,double y,double size,boolean bold,int color){text.add(new Text(s,y,size,bold));}
        public double measure(String s,double size,boolean bold){return s.length()*size*.5;}
        public TextBounds textBounds(String s,double size,boolean bold){return new TextBounds(3,size+5);}
        public void icon(String name,double x,double y,double size,int color){icons.putIfAbsent(name,new Rect(x,y,size,size));}
        void reset(){text.clear();icons.clear();}
    }
    static final Host host=new Host();static final UiPreferences prefs=new UiPreferences(Map.of("instantSearch","false","rememberLastPage","0"),()->{});
    static final ConfigView view=new ConfigView(host,prefs);static final Paint paint=new Paint();static double time=10;
    static void frame(double at,double x,double y){time=at;Point screen=view.viewport().screen(x,y);paint.reset();view.frame(paint,1600,900,screen.x(),screen.y(),at);check(paint.depth==0,"frame painter restored");}
    static void frame(double at){frame(at,800,400);}
    static void down(double x,double y){Point p=view.viewport().screen(x,y);view.pointerDown(p.x(),p.y(),0,false,false,false);}
    static void up(double x,double y){Point p=view.viewport().screen(x,y);view.pointerUp(p.x(),p.y(),0);}
    static Rect bounds(String id){Rect r=view.hitBounds().get(id);check(r!=null,"registered "+id);return r;}
    static void click(String id){Rect r=bounds(id);down(r.x()+r.w()/2,r.y()+r.h()/2);up(r.x()+r.w()/2,r.y()+r.h()/2);}
    static void back(){view.key(ConfigView.Key.LEFT,"",false,false,true);}
    static void forward(){view.key(ConfigView.Key.RIGHT,"",false,false,true);}
    public static void main(String[] args){
        view.navigate(new Route(Page.SETTINGS,"a"));frame(10);frame(11);
        Text sidebar=paint.text.stream().filter(t->t.value().equals("播放器设置")).findFirst().orElseThrow();
        Rect icon=paint.icons.get("settings");near(sidebar.y()+(3+sidebar.size()+5)/2,icon.y()+icon.h()/2,"sidebar icon and real ink share center");
        Text header=paint.text.stream().filter(t->t.value().equals("MuoniumPlayer")&&t.size()==24).findFirst().orElseThrow();
        near(header.y()+(3+header.size()+5)/2,37,"header uses ink metrics");
        Text placeholder=paint.text.stream().filter(t->t.value().equals("Search settings...")).findFirst().orElseThrow();
        near(placeholder.y()+(3+placeholder.size()+5)/2,37,"header search placeholder uses ink metrics");
        frame(11.01);for(int i=0;i<8;i++)view.wheel(-1);near(view.scroll(),0,"wheel keeps last painted position until frame");
        frame(11.04);double moving=view.scroll();check(moving>0&&moving<352,"wheel interpolates");
        String visible=view.hitBounds().keySet().stream().filter(k->k.startsWith("settings.toggle.a")).findFirst().orElseThrow();
        click(visible);check(Boolean.TRUE.equals(host.values.get(visible.substring("settings.toggle.".length()))),"click during scrolling edits painted row");
        view.key(ConfigView.Key.Z,"",true,false,false);
        view.wheel(1);frame(11.05);check(view.scroll()<moving,"reverse interrupts without old queue");frame(15);
        view.scroll(300);frame(15.01);double remembered=view.scroll();
        Rect old=bounds("settings.toggle.a6");click("settings.category.b");
        check(view.hitBounds().isEmpty(),"old page hit registry invalid immediately");down(old.x()+2,old.y()+2);up(old.x()+2,old.y()+2);
        check(host.values.values().stream().noneMatch(Boolean.TRUE::equals),"no stale old click before next frame");
        frame(15.02);near(view.scroll(),0,"short category clamps before paint");
        check(view.hitBounds().keySet().stream().noneMatch(k->k.startsWith("settings.toggle.a")),"outgoing settings never registers rows");
        Rect movingToggle=bounds("settings.toggle.b0");check(movingToggle.y()>126,"incoming control has slight layout motion");
        click("settings.toggle.b0");check(Boolean.TRUE.equals(host.values.get("b0")),"animation click edits visible category");
        view.key(ConfigView.Key.Z,"",true,false,false);check(Boolean.FALSE.equals(host.values.get("b0")),"undo survives category transition");
        view.key(ConfigView.Key.Z,"",true,true,false);check(Boolean.TRUE.equals(host.values.get("b0")),"redo survives category transition");
        back();frame(15.03);near(view.scroll(),remembered,"back restores exact visible scroll");
        forward();frame(15.04);check(view.route().category().equals("b"),"forward restores category");back();frame(16);
        Rect track=bounds("settings.scrollbar");double maximum=0;
        // Bottom track click followed by pointer release must settle at the exact lower endpoint.
        down(track.x()+4,track.bottom()-1);up(track.x()+4,track.bottom()-1);frame(16.01);maximum=view.scroll();check(maximum>300,"track reaches end");
        Rect bottomTrack=bounds("settings.scrollbar");double thumbHeight=Math.max(24,bottomTrack.h()*bottomTrack.h()/(bottomTrack.h()+maximum));
        double grabY=bottomTrack.bottom()-thumbHeight+2;down(bottomTrack.x()+3,grabY);near(view.scroll(),maximum,"thumb grab does not recenter");
        up(bottomTrack.x()+3,bottomTrack.y()+2);near(view.scroll(),0,"thumb drag reaches exact top");frame(16.02);
        prefs.set("animations",false);view.wheel(-1);near(view.scroll(),44,"animation preference disables smooth scroll");
        click("settings.category.b");frame(16.03);Rect settled=bounds("settings.toggle.b0");frame(20);near(bounds("settings.toggle.b0").y(),settled.y(),"disabled page animation has no delayed shift");
        prefs.set("animations",true);click("settings.category.a");frame(20.01);
        Rect animatedTrack=bounds("settings.scrollbar");double initial=view.scroll();
        double tallMax=maximum;double initialThumb=Math.max(24,animatedTrack.h()*animatedTrack.h()/(animatedTrack.h()+tallMax));
        double heldY=animatedTrack.y()+(animatedTrack.h()-initialThumb)*initial/tallMax+2;
        down(animatedTrack.x()+3,heldY);frame(20.1,animatedTrack.x()+3,heldY);
        Rect movedTrack=bounds("settings.scrollbar");double actualThumbY=movedTrack.y()+(movedTrack.h()-initialThumb)*view.scroll()/tallMax;
        near(actualThumbY+2,heldY,"scrollbar stays at grab point while entrance moves layout");up(animatedTrack.x()+3,heldY);frame(1000);check(view.hitBounds().keySet().stream().anyMatch(k->k.startsWith("settings.toggle.a")),"long frame gap settles current page with restored scroll");
        // A page is rendered only while current; moving text input uses the same actual bounds as paint.
        int[] renders={0,0}, leaves={0,0};TextEdit editor=new TextEdit();
        view.register(Page.THEMES,new UiPage(){public void render(PageContext ctx){renders[0]++;Rect r=new Rect(ctx.content().x()+20,ctx.content().y()+30,200,30);view.bareTextField("probe.field",editor,r,new TextInputController.Style(14),null,null);}public void leave(PageContext ctx){leaves[0]++;}});
        view.register(Page.PROFILES,new UiPage(){public void render(PageContext ctx){renders[1]++;ctx.scrollRange(0);ctx.hit("probe.current",new Rect(ctx.content().x()+20,ctx.content().y()+30,200,30),()->{},"");}public void leave(PageContext ctx){leaves[1]++;}});
        view.navigate(new Route(Page.THEMES));frame(1001);click("probe.field");check(view.textInputController().focused(),"moving text editor focuses at its visual bounds");
        int before=renders[0];view.navigate(new Route(Page.PROFILES));check(!view.textInputController().focused(),"leaving page blurs editor immediately");frame(1001.01);frame(1001.05);
        near(renders[0],before,"outgoing page is never rendered again");near(leaves[0],1,"outgoing page leave executes exactly once");
        check(view.hitBounds().containsKey("probe.current")&&!view.hitBounds().containsKey("probe.field"),"only current page exposes input during transition");
        // Interrupt an unfinished page entrance, including two changes between frames.
        view.navigate(new Route(Page.THEMES));view.navigate(new Route(Page.PROFILES));frame(1001.06);
        near(renders[0],before,"unpainted intermediate route never renders");check(view.hitBounds().containsKey("probe.current"),"rapid retarget retains final route input");
        System.out.println("ConfigInteractionTest PASS: "+checks+" checks");
    }
}
