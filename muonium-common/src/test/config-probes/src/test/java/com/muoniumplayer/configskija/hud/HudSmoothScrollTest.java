package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;

/** CPU integration regressions for smooth user wheel and existing automatic section springs. */
public final class HudSmoothScrollTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void near(double a,double b,String why){check(Math.abs(a-b)<.0001,why+": "+a+" / "+b);}
    private static Object field(Object owner,String name){try{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static double number(Object owner,String name){return (double)field(owner,name);}
    private static final class Rig {
        final HudParityTest.Host host=new HudParityTest.Host();
        final HudParityTest.Context c=new HudParityTest.Context(host);
        final HudRuntime runtime=HudRuntime.open(host,HudParityTest.prefs());
        final HudStudio studio=new HudStudio(host,HudParityTest.prefs(),runtime);
        HudNode node;
        Rig(boolean panel,double density){
            host.density=density;c.width=host.frameWidth=1000*density;c.height=host.frameHeight=(panel?600:380)*density;
            if(panel){node=HudParityTest.nativeBox("smooth",20,20,100,40).node();runtime.designer().add(node,20,20);studio.select(List.of(node.id()),true);}
            frame(0);frame(2);
        }
        void frame(double time){c.time=time;c.next();c.painter.glyphs.clear();studio.render(c);check(c.painter.depth==0,"balanced CPU painter");}
        void point(Rect r){c.mouseX=(r.x()+40)*host.density;c.mouseY=(r.y()+100)*host.density;}
        void wheel(boolean panel,double delta){point(panel?studio.panel.bounds():studio.library.bounds());HudChromeContext chrome=new HudChromeContext(c,new HudChromeContext.Focus());check(panel?studio.panel.wheel(chrome,delta):studio.library.wheel(chrome,delta),"wheel routed to visible component");}
        double panelScroll(){return number(studio.panel,"scroll");}
        double panelMax(){return Math.max(0,number(studio.panel,"total")-((Rect)field(studio.panel,"body")).h());}
        void click(String id){var hit=c.hits.get(id);check(hit!=null,"registered "+id);c.mouseX=hit.rect().x()+hit.rect().w()/2;c.mouseY=hit.rect().y()+hit.rect().h()/2;hit.action().run();}
        void dragTo(String id,boolean bottom){var hit=c.hits.get(id);check(hit!=null,"scrollbar exists "+id);c.mouseX=hit.rect().x()+hit.rect().w()/2;c.mouseY=bottom?hit.rect().bottom()-1:hit.rect().y()+1;hit.action().run();c.release();}
    }
    private static void panel(double density){
        Rig r=new Rig(true,density);double maximum=r.panelMax();check(maximum>200,"panel has enough settings to scroll");
        r.wheel(true,-1);near(r.panelScroll(),0,"panel wheel preserves painted position in event frame");
        r.frame(2.03);double current=r.panelScroll();check(current>0&&current<44,"panel scroll interpolates");
        r.click("hud:setting/lock");check(r.c.preferences.locked(r.node.id()),"moving panel hit edits the visible lock row");r.c.preferences.locked(r.node.id(),false);
        r.wheel(true,1);near(r.panelScroll(),current,"panel reverse is continuous");r.frame(2.04);check(r.panelScroll()<current,"panel reverses immediately");
        for(int i=0;i<8;i++)r.wheel(true,-1);r.frame(10);check(r.panelScroll()>200&&r.panelScroll()<=r.panelMax(),"panel burst accumulates and settles within bounds");
        r.dragTo("hud:panel-scrollbar",true);near(r.panelScroll(),r.panelMax(),"panel track jumps to exact bottom");r.frame(11);near(r.panelScroll(),r.panelMax(),"panel drag cancels wheel motion");
        var panelTrack=r.c.hits.get("hud:panel-scrollbar").rect();double panelH=panelTrack.h()/density,panelThumb=Math.max(24,panelH*panelH/number(r.studio.panel,"total"));
        r.c.mouseX=panelTrack.x()+panelTrack.w()/2;r.c.mouseY=panelTrack.bottom()-panelThumb*density+3*density;r.c.hits.get("hud:panel-scrollbar").action().run();
        near(r.panelScroll(),r.panelMax(),"panel thumb grab retains its pointer offset");
        r.c.move(r.c.mouseX,panelTrack.y()+((panelH-panelThumb)*.37+3)*density);near(r.panelScroll(),r.panelMax()*.37,"panel thumb drag jumps to exact fraction");
        r.c.move(r.c.mouseX,panelTrack.bottom()+3*density);r.c.release();near(r.panelScroll(),r.panelMax(),"panel drag clamps exact lower endpoint");
        r.c.height=r.host.frameHeight=1800*density;r.frame(12);near(r.panelScroll(),0,"panel resize clamps before painting");
        check(r.c.hits.containsKey("hud:reset-position"),"resize renders newly exposed top controls in same frame");
        r.c.height=r.host.frameHeight=600*density;r.frame(13);r.dragTo("hud:panel-scrollbar",true);r.frame(14);
        r.studio.panel.search.text("GUI Scale");r.frame(15);near(r.panelScroll(),0,"panel search clamps before paint");
        check(r.c.hits.containsKey("hud:number/customScale/+"),"filtered top control has current hit geometry");
        check(r.c.hits.keySet().stream().noneMatch(k->k.contains("showInChat")||k.contains("mergeDiagonally")),"filtered-out panel controls expose no old hit regions");
        r.studio.panel.search.text("");r.frame(16);r.click("hud:tab-designer");r.frame(17);r.dragTo("hud:panel-scrollbar",true);r.frame(18);double designer=r.panelScroll();
        r.click("hud:tab-settings");r.frame(19);near(r.panelScroll(),0,"settings tab restores its stored clamped scroll");
        r.click("hud:tab-designer");r.frame(20);near(r.panelScroll(),designer,"designer tab restores exact visual scroll");
        r.c.preferences.set("animations",false);double before=r.panelScroll();r.wheel(true,1);near(r.panelScroll(),Math.max(0,before-44),"panel disabled preference jumps immediately");
        r.studio.panel.closed();r.frame(21);near(r.panelScroll(),0,"closed panel resets smooth state");
    }
    private static void library(double density){
        Rig r=new Rig(false,density);r.wheel(false,-1);near(r.studio.library.scrollOffset(),0,"library wheel preserves event-frame paint");
        r.frame(2.03);double current=r.studio.library.scrollOffset();check(current>0&&current<36,"library wheel interpolates at density "+density+": "+current+" total "+number(r.studio.library,"total"));
        r.wheel(false,1);r.frame(2.04);check(r.studio.library.scrollOffset()<current,"library reverses immediately");
        r.studio.library.requestSection("oneconfig.builtin");r.frame(3);check(r.studio.library.scrolling(),"section request retains original automatic spring");
        r.frame(3.10);double automatic=r.studio.library.scrollOffset();check(automatic>36,"automatic spring advances");
        r.wheel(false,1);near(r.studio.library.scrollOffset(),automatic,"manual handoff is continuous");check(!r.studio.library.scrolling(),"manual wheel cancels automatic section spring");
        r.frame(3.12);double handoff=r.studio.library.scrollOffset();check(handoff<automatic&&handoff>automatic-36,"manual handoff follows its smooth target");
        r.frame(6);near(r.studio.library.scrollOffset(),automatic-36,"cancelled auto target never resumes; manual target settles");
        r.dragTo("hud:library-scrollbar",true);r.frame(7);double bottom=r.studio.library.scrollOffset();check(bottom>0,"library bottom reached");
        var track=r.c.hits.get("hud:library-scrollbar").rect();double h=track.h()/density,total=number(r.studio.library,"total"),thumb=Math.max(24,h*h/total);
        r.c.mouseX=track.x()+track.w()/2;r.c.mouseY=track.bottom()-thumb*density+2*density;r.c.hits.get("hud:library-scrollbar").action().run();
        near(r.studio.library.scrollOffset(),bottom,"library grab retains exact thumb offset");r.c.move(r.c.mouseX,track.y()+2*density);r.c.release();near(r.studio.library.scrollOffset(),0,"library thumb jumps precisely to top");r.frame(8);near(r.studio.library.scrollOffset(),0,"library direct drag cancels interpolation");
        r.c.preferences.set("animations",false);r.wheel(false,-1);near(r.studio.library.scrollOffset(),36,"library disabled preference jumps immediately");
        r.studio.library.requestSection("oneconfig.builtin");r.frame(9);check(!r.studio.library.scrolling(),"disabled section request is immediate");check(r.studio.library.scrollOffset()>36,"disabled section reaches requested group");
        r.studio.library.search.text("no such hud");r.frame(10);near(r.studio.library.scrollOffset(),0,"library search clamps in same frame");
        check(r.c.hits.keySet().stream().noneMatch(k->k.startsWith("hud:library-card")),"search removes old library card regions");
        r.studio.library.search.text("");r.c.preferences.set("animations",true);r.frame(11);r.dragTo("hud:library-scrollbar",true);r.frame(12);
        r.c.width=r.host.frameWidth=480*density;r.c.height=r.host.frameHeight=350*density;r.frame(13);
        check(r.studio.library.scrollOffset()<=Math.max(0,number(r.studio.library,"total")-(r.studio.library.bounds().h()-106)),"library resize reclamps actual packed rows");
        r.wheel(false,-1e6);r.frame(1000);check(Double.isFinite(r.studio.library.scrollOffset()),"long stalled library frame settles finitely");
    }
    private static double sampled(boolean panel,int hz){Rig r=new Rig(panel,1);r.wheel(panel,-4);for(int i=1;i<=hz/10;i++)r.frame(2+(double)i/hz);r.frame(2.1);return panel?r.panelScroll():r.studio.library.scrollOffset();}
    public static void main(String[] args){
        for(double density:new double[]{.75,1,1.5,2}){panel(density);library(density);}
        for(boolean panel:new boolean[]{true,false}){double reference=sampled(panel,30);for(int hz:new int[]{60,144,240})near(sampled(panel,hz),reference,"actual HUD wheel is frame-rate independent "+hz);}
        HudUiDetailsTest.scroll();HudUiDetailsTest.menus();HudUiDetailsTest.tooltips();HudUiDetailsTest.colors();HudUiDetailsTest.dropdown();HudUiDetailsTest.animation();HudUiDetailsTest.hostOwnership();HudUiDetailsTest.textGeometry();
        System.out.println("HudSmoothScrollTest PASS: "+checks+" checks plus unchanged HUD menu/tooltip/color/dropdown/spring/ownership probes");
    }
}
