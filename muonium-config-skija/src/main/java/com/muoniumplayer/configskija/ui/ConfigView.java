package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.ConfigHost.Category;
import com.muoniumplayer.configskija.model.ConfigHost.Group;
import com.muoniumplayer.configskija.model.ConfigHost.Option;
import com.muoniumplayer.configskija.model.History;
import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.Navigation;
import com.muoniumplayer.configskija.model.Navigation.Page;
import com.muoniumplayer.configskija.model.Navigation.Route;
import com.muoniumplayer.configskija.model.Presets;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.Geometry.Point;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Geometry.Viewport;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Renderer independent application. Compose is not on the source or runtime path. */
public final class ConfigView implements PageContext,TextInputController.TextInput {
    public enum Key { NONE, ESCAPE, ENTER, TAB, LEFT, RIGHT, UP, DOWN, HOME, END, BACKSPACE, DELETE, A, C, V, X, F, Z, Y, H, L, E }
    private record Hit(Rect bounds,Runnable action,String tip,String id) {}
    private final ConfigHost host;
    private final UiPreferences prefs;
    private final Navigation nav;
    private final TextEdit search=new TextEdit(),dialogEdit=new TextEdit();
    private final List<Hit> hits=new ArrayList<>(),popupHits=new ArrayList<>();
    private final List<Option> all=new ArrayList<>();
    private final Map<String,Option> byId=new LinkedHashMap<>();
    private final History<Map<String,Object>> history;
    private Set<String> historyScope=Set.of();
    private Painter p;
    private Theme theme=Theme.BUILTIN[0];
    private Viewport viewport=Viewport.fit(1400,720,1);
    private double screenW=1400,screenH=720,mx,my,sx,sy,scroll,maxScroll,now,hoverSince;
    private double lastTime;
    private Rect content=new Rect(289,90,1052,585);
    private String hoverId="",status="",title="",dialogTitle="",dialogHint="",dialogConfirm="确认";
    private double statusUntil;
    private int accent=0xFF2B4BFF;
    private Consumer<String> dialogSubmit;
    private Runnable confirmation;
    private BiConsumer<Double,Double> drag;
    private Runnable dragEnd;
    private boolean shiftDown,controlDown,altDown,closed,firstFrame=true;
    private String focusOption;
    private boolean profileTemplates;
    private final Map<Page,UiPage> pages=new java.util.EnumMap<>(Page.class);
    private final Motion motion=new Motion();
    private final TextInputController textInput=new TextInputController();
    private UiPage activePage,overlayPage,globalSearchPage;
    private boolean drawingOverlay;
    private TextEdit activeEditor;
    private Consumer<String> activeEditorChanged;
    private int nativeCode;
    private String historyOwner="";
    private static Navigation.Route rememberedRoute;
    private static double rememberedClosedAt;
    private double frameDelta;
    private double openedAt=Double.NaN,closingAt=Double.NaN,openAlpha=1,closeStart=1,transitionAt=Double.NaN,uiScaleChangedAt=Double.NaN,settledScale=1,requestedScale=1;
    private boolean transitionBack;
    private Route outgoingRoute;
    private boolean drawingOutgoing;
    private double outgoingScroll,transitionOffset,actionInputOffset,dragInputOffset;
    private String outgoingQuery="";
    private UiPage notificationCenter;

    public ConfigView(ConfigHost host,UiPreferences preferences) {
        this.host=host;this.prefs=preferences;
        List<Category> models=new ArrayList<>(host.categories());models.addAll(host.favoriteCategories());
        for(Category c:models) for(Group g:c.groups()) for(Option option:g.options()) {
            if(byId.put(option.id(),option)!=null) throw new IllegalArgumentException("Duplicate setting identity: "+option.id());
            all.add(option);
        }
        String category=host.categories().isEmpty()?"":host.categories().get(0).id();
        nav=new Navigation(new Route(Page.SETTINGS,category));
        int remember=(int)prefs.number("rememberLastPage",1);double nowSeconds=System.nanoTime()/1_000_000_000d;double ttl=switch(remember){case 1->15;case 2->60;case 3->300;case 4->Double.POSITIVE_INFINITY;default->0;};
        if(rememberedRoute!=null&&ttl>0&&nowSeconds-rememberedClosedAt<=ttl&&(rememberedRoute.page()!=Page.HUD||prefs.flag("restoreHudEditor",false)))nav.temporary(rememberedRoute);
        if(prefs.flag("instantSearch",true)) { search.focused=true;search.selectAll(); }
        settledScale=requestedScale=prefs.number("uiScale",100)/100;
        history=new History<Map<String,Object>>(100,this::capture,(desired,previous)->restore(desired,previous));
        registerDefaultPages();
    }
    private void registerDefaultPages(){
        register(Page.SETTINGS,new com.muoniumplayer.configskija.ui.settings.SettingsPage(false));register(Page.FAVORITES,new com.muoniumplayer.configskija.ui.settings.SettingsPage(true));
        register(Page.MODS,com.muoniumplayer.configskija.ui.pages.LibraryPages.mods());register(Page.PROFILES,com.muoniumplayer.configskija.ui.pages.LibraryPages.profiles());register(Page.KEYS,com.muoniumplayer.configskija.ui.pages.LibraryPages.keys());register(Page.CREDITS,com.muoniumplayer.configskija.ui.pages.LibraryPages.credits());register(Page.THEMES,com.muoniumplayer.configskija.ui.pages.AppearancePages.themes());register(Page.PREFERENCES,com.muoniumplayer.configskija.ui.pages.AppearancePages.preferences());register(Page.HUD,host instanceof com.muoniumplayer.configskija.host.HudExtensions extension&&extension.existingHudRuntime()!=null?new com.muoniumplayer.configskija.ui.hud.HudStudio(host,prefs,extension.existingHudRuntime()):new com.muoniumplayer.configskija.ui.hud.HudStudio(host,prefs));
    }
    public Navigation navigation() { return nav; }
    public Viewport viewport() { return viewport; }
    public List<Option> options() { return List.copyOf(all); }
    public Map<String,Rect> hitBounds() { Map<String,Rect> out=new LinkedHashMap<>();for(Hit h:hits) if(!h.id().isEmpty()) out.put(h.id(),h.bounds());for(Hit h:popupHits) if(!h.id().isEmpty()) out.put(h.id(),h.bounds());return out; }
    public boolean editingHud() { return nav.current().page()==Page.HUD; }
    public void status(String message) { if(message!=null&&!message.isBlank())com.muoniumplayer.configskija.model.Notifications.shared().info("MuoniumConfig",message); }
    public void modifiers(boolean control,boolean shift,boolean alt) { controlDown=control;shiftDown=shift;altDown=alt; }
    public void navigate(Route route) {
        boolean samePage=nav.current().page()==route.page();textInput.blur(this);
        if(!samePage)prepareTransition(false);else outgoingRoute=null;
        if(activePage!=null)activePage.leave(this);
        finishPopup();activeEditor=null;nav.remember(scroll,search.text());nav.navigate(route);
        if(!samePage){restoreNavigation();search.text("");if(prefs.flag("instantSearch",true)){search.focused=true;search.selectAll();}}
        host.sound("navigate");
    }
    private void restoreNavigation(){Navigation.Memory memory=nav.memory();scroll=memory.scroll();search.text(memory.search());focusOption=null;if(prefs.flag("instantSearch",true)){search.focused=true;search.selectAll();}}
    private void back(){
        if(!nav.canBack())return;boolean same=nav.previous().page()==nav.current().page();textInput.blur(this);if(!same)prepareTransition(true);else outgoingRoute=null;
        finishPopup();nav.remember(scroll,search.text());if(nav.back()&&!same)restoreNavigation();
    }
    private void forward(){
        if(!nav.canForward())return;boolean same=nav.next().page()==nav.current().page();textInput.blur(this);if(!same)prepareTransition(false);else outgoingRoute=null;
        finishPopup();nav.remember(scroll,search.text());if(nav.forward()&&!same)restoreNavigation();
    }
    public void frame(Painter painter,double width,double height,double mouseX,double mouseY,double seconds) {
        String owner=prefs.get("profile.active","");if(!owner.equals(historyOwner)){if(history.active())history.cancel();history.clear();historyOwner=owner;}
        p=painter;frameDelta=lastTime==0?1d/60:Geometry.clamp(seconds-lastTime,0,.1);screenW=width;screenH=height;now=seconds;sx=mouseX;sy=mouseY;
        theme=Theme.transition(this);accent=Theme.accent(prefs,now);
        if(Double.isNaN(openedAt)) openedAt=now;
        double userScale=prefs.number("uiScale",prefs.number("ui.scale",1)*100)/100;
        if(Double.compare(userScale,requestedScale)!=0) { requestedScale=userScale;uiScaleChangedAt=now; }
        if(!Double.isNaN(uiScaleChangedAt)&&now-uiScaleChangedAt>=.4) { settledScale=requestedScale;uiScaleChangedAt=Double.NaN; }
        boolean snap=(int)prefs.number("pixelGrid",0)==1||((int)prefs.number("pixelGridMode",0)==0&&theme.id().startsWith("minecraft"));
        double progress=Geometry.clamp((now-openedAt)/Math.max(.001,prefs.flag("guiOpenAnimation",true)?prefs.number("animationTime",.6):.001),0,1);
        openAlpha=Motion.ease(Motion.Easing.EASE_OUT_EXPO,progress);
        if(!Double.isNaN(closingAt)) { double duration=prefs.flag("guiClosingAnimation",true)?Math.min(.16,prefs.number("animationTime",.6)):0;double t=duration<=0?1:Geometry.clamp((now-closingAt)/duration,0,1);openAlpha=closeStart*(1-Motion.ease(Motion.Easing.EASE_OUT_CUBIC,t));if(t>=1) { host.close();return; } }
        viewport=Viewport.original(width,height,host.uiDensity(),settledScale,snap,host.physicalPerLayout()).scaledAboutCenter(.9+.1*openAlpha);
        Point local=editingHud()?new Point(mouseX,mouseY):viewport.local(mouseX,mouseY);mx=local.x();my=local.y();
        textInput.beginFrame(this);textInput.pointerMove(this);
        if(drag!=null) run("拖动",()->drag.accept(mx-dragInputOffset,my));
        hits.clear();popupHits.clear();
        if(editingHud()) {
            activePage=pages.get(Page.HUD);activePage.render(this);
            renderOverlay();
            if(activePage!=null&&(dialogSubmit!=null||confirmation!=null)) drawDialog();
        }else {

            p.save();try {
                p.translate(viewport.x(),viewport.y());p.scale(viewport.scale(),viewport.scale());
                p.layer(new Rect(-100,-100,1591,900),openAlpha);
                try { drawShell();
                if(dialogSubmit!=null||confirmation!=null) drawDialog();
                renderOverlay();
                drawTooltip();
                }finally { p.restore(); }
            }finally { p.restore(); }
        }
        textInput.endFrame(this);motion.prune(now);firstFrame=false;lastTime=now;
    }
    private void drawShell() {
        Rect shell=new Rect(0,0,1391,700);
        if(theme.rounded()) p.shadow(shell,theme.radius(20),50*.57735,4,0,13,Theme.alpha(0xff000000,.42));
        p.save();try {
            p.clip(shell,theme.radius(20));
            double glow=Geometry.clamp(prefs.number("glowOpacity",prefs.number("appearance.glow",25))/100,0,1);
            if(glow>0) {p.glow(shell,147,-199,1400,accent,glow);p.glow(shell,893,736,1400,accent,glow); }
            p.rect(new Rect(0,0,264,700),0,Theme.alpha(theme.sidebar(),prefs.number("sidebarOpacity",prefs.number("appearance.sidebarOpacity",80))/100));
            p.rect(new Rect(264,0,1127,700),0,Theme.alpha(theme.page(),prefs.number("pageOpacity",prefs.number("appearance.pageOpacity",88))/100));
            p.line(264,0,264,700,1,theme.border());
            drawSidebar();drawHeader();drawTransitionPages();

        }finally { p.restore(); }
        p.outline(shell,theme.radius(20),1,theme.border());
    }
    private void drawSidebar() {
        boolean flip=prefs.flag("flipTopOptionOrder",prefs.flag("appearance.flipTop",false));
        String brand=theme.id().equals("minecraft-dark")?"brand/oneconfig-mc.svg":theme.id().equals("minecraft-light")?"brand/oneconfig-mc-light.svg":theme.id().endsWith("light")?"brand/oneconfig-light.svg":"brand/oneconfig.svg";
        if(p instanceof com.muoniumplayer.configskija.ui.pages.AppearancePages.Images images)images.image(brand,new Rect(35,28,34,22));else p.icon(brand,35,28,34,theme.text());label("MuoniumPlayer",77,31,15,true,theme.text());
        double y=78;
        if(!flip) { navButton(Page.HUD,tr("oneconfig.sidebar.edit_hud","Edit HUD"),"hud",y);y+=61; }
        smallLabel(tr("oneconfig.pagegroup.favorites","常用"),26,y);y+=23;navButton(Page.FAVORITES,tr("oneconfig.page.favorites.title","常用"),"star",y);y+=51;
        smallLabel(tr("oneconfig.pagegroup.mods_and_options","Mods & Options"),26,y);y+=23;
        navButton(Page.MODS,tr("oneconfig.page.mods.title","Mods"),"settings",y);y+=41;navButton(Page.PROFILES,tr("oneconfig.page.profiles.title","Profiles"),"profiles",y);y+=51;
        smallLabel(tr("oneconfig.pagegroup.personalization","Personalization"),26,y);y+=23;
        navButton(Page.THEMES,tr("oneconfig.page.themes.title","Themes"),"paintbrush",y);y+=41;navButton(Page.PREFERENCES,tr("oneconfig.page.preferences.title","Preferences"),"cog",y);y+=51;
        smallLabel(tr("oneconfig.pagegroup.oneconfig","About"),26,y);y+=23;navButton(Page.CREDITS,tr("oneconfig.page.credits.title","Credits"),"star",y);
        if(flip) navButton(Page.HUD,tr("oneconfig.sidebar.edit_hud","Edit HUD"),"hud",576);
        p.line(0,627,264,627,1,theme.border());
        Rect avatar=new Rect(20,647,32,32);byte[] head=host.playerHead();if(head!=null) { p.save();try { p.clip(avatar,theme.radius(8));p.image(head,avatar,true); }finally { p.restore(); } }else { p.rect(avatar,theme.radius(8),theme.component());p.icon("hud",27,654,18,theme.secondary()); }
        label(host.playerName(),64,644,14,false,theme.text());label(host.versionLabel(),64,665,11,false,theme.secondary());
        Rect bell=new Rect(228,654,18,18);p.icon("bell",bell.x(),bell.y(),18,theme.text());if(com.muoniumplayer.configskija.model.Notifications.shared().unreadCount()>0)p.rect(new Rect(bell.right()-4,bell.y()-2,6,6),3,accent);
        hit(bell,()-> { overlay(new com.muoniumplayer.configskija.ui.notifications.NotificationsCenter(com.muoniumplayer.configskija.model.Notifications.shared(),bell,tr("oneconfig.notifications.clear_all","Clear all notifications"))); },tr("oneconfig.notifications.title","Notifications"),"notifications:bell");
    }
    private void smallLabel(String value,double x,double y) { label(value.toUpperCase(Locale.ROOT),x,y,11,false,theme.text()); }
    private void navButton(Page page,String name,String icon,double y) {
        boolean selected=nav.current().page()==page||(page==Page.MODS&&nav.current().page()==Page.SETTINGS);
        Rect r=new Rect(19.5,y,225,33);boolean hovered=r.contains(mx,my);
        double selectedAlpha=motion.value("nav/"+page+"/selected",selected?1:0,.4,Motion.Easing.SPRING,now);
        double hoveredAlpha=motion.value("nav/"+page+"/hover",hovered?1:0,.4,Motion.Easing.SPRING,now);
        if(selectedAlpha>.001) { p.save();try { p.clip(r,theme.radius(8));int end=Theme.accentEnd(prefs,now);p.gradient(r,Theme.alpha(accent,selectedAlpha),Theme.alpha(end,selectedAlpha),Theme.alpha(end,selectedAlpha),Theme.alpha(accent,selectedAlpha)); }finally { p.restore(); } }
        if(hoveredAlpha>.001||selectedAlpha>.001) p.outline(r,theme.radius(8),1,Theme.alpha(theme.border(),((theme.border()>>>24)&255)/255d*Math.max(hoveredAlpha,selectedAlpha)));
        int color=Theme.mix(Theme.alpha(theme.text(),.8),theme.text(),hoveredAlpha);color=Theme.mix(color,theme.accentText(),selectedAlpha);
        p.icon(icon,r.x()+12,r.y()+8,17,color);label(name,r.x()+45,r.y()+9,14,false,color);
        hit(r,()-> { if(nav.current().page()==page||(page==Page.MODS&&nav.current().page()==Page.SETTINGS)) { search.text("");scroll=0; }else navigate(new Route(page)); },"","nav:"+page);
    }
    private void drawHeader() {
        button(new Rect(289,21,32,32),"","left-arrow",false,nav.canBack(),this::back,"返回","header:back");
        button(new Rect(329,21,32,32),"","right-arrow",false,nav.canForward(),this::forward,"前进","header:forward");
        title=switch(nav.current().page()) { case SETTINGS -> "MuoniumPlayer";case FAVORITES -> tr("oneconfig.page.favorites.title","常用");case MODS -> tr("oneconfig.page.mods.title","Mods");case PROFILES -> tr("oneconfig.page.profiles.title","Profiles");case THEMES -> tr("oneconfig.page.themes.title","Themes");case PREFERENCES -> tr("oneconfig.page.preferences.title","Preferences");case KEYS -> tr("oneconfig.keybinds.title","Keybinds");case CREDITS -> tr("oneconfig.page.credits.title","Credits");case HUD -> tr("oneconfig.sidebar.edit_hud","Edit HUD"); };
        label(title,385,24,24,false,theme.text());
        Rect box=new Rect(1052,21,256,32);drawEdit(search,box,searchPlaceholder(),false);
        hit(box,()-> { if(activeEditor!=null) activeEditor.focused=false;activeEditor=null;search.focused=true;search.click(mx-box.x()-34,v->p.measure(v,12,false),shiftDown); },"Ctrl+F 搜索；支持标题、说明、分类和多个关键词","search");
        button(new Rect(1334,21,32,32),"","close",false,true,this::close,"关闭","header:close");
    }
    private void drawPage() {
        content=new Rect(289,74,1077,607);
        activePage=nav.current().page()==Page.MODS&&!search.text().isBlank()&&globalSearchPage!=null?globalSearchPage:pages.get(nav.current().page());
        if(activePage==null)throw new IllegalStateException("No renderer registered for "+nav.current().page());
        activePage.render(this);
    }
    private void change(Option option,Object value) {
        if(!option.enabled()) return;
        historyScope=Set.of(option.id());history.begin(option.title());try { host.sound("click");option.set(value);if(history.commit()) host.persist();historyScope=Set.of(); }catch(RuntimeException ex) { history.cancel();throw ex; }
    }
    private Map<String,Object> capture() { Map<String,Object> out=new LinkedHashMap<>();for(Option o:all) if(o.kind()!=ConfigHost.Kind.ACTION&&(historyScope.isEmpty()||historyScope.contains(o.id()))) out.put(o.id(),com.muoniumplayer.configskija.model.ValueSnapshots.copy(o.value()));return Collections.unmodifiableMap(out); }
    private void restore(Map<String,Object> desired,Map<String,Object> previous) {
        for(var e:desired.entrySet()) {
            Option option=byId.get(e.getKey());
            if(option!=null&&!com.muoniumplayer.configskija.model.ValueSnapshots.equal(e.getValue(),previous.get(e.getKey()))) option.write().accept(com.muoniumplayer.configskija.model.ValueSnapshots.copy(e.getValue()));
        }
        host.persist();
    }


    public static int parseColor(String text) {
        String value=text.strip().replaceFirst("^#","");
        if(value.length()==6) return (int)(0xff000000L|Long.parseLong(value,16));
        if(value.length()==8) return (int)Long.parseLong(value,16);
        throw new IllegalArgumentException("颜色必须是 6 位 RGB 或 8 位 ARGB");
    }
    public void textDialog(String title,String hint,String value,String confirm,Consumer<String> submit) {
        finishPopup();search.focused=false;dialogTitle=title;dialogHint=hint;dialogConfirm=confirm;dialogEdit.text(value);dialogEdit.focused=true;dialogEdit.selectAll();dialogSubmit=submit;
    }
    public void confirm(String title,String hint,Runnable action) { finishPopup();search.focused=false;dialogTitle=title;dialogHint=hint;dialogConfirm="确认";confirmation=action;dialogEdit.focused=false; }
    private Rect dialogBounds() { return editingHud()?new Rect((screenW-440)/2,(screenH-204)/2,440,204):new Rect(475,242,440,216); }
    private void drawDialog() {
        popupHits.clear();p.rect(new Rect(0,0,editingHud()?screenW:1391,editingHud()?screenH:700),0,0x99000000);
        Rect box=dialogBounds();p.rect(box,theme.radius(20),theme.popup());p.outline(box,theme.radius(20),1,theme.border());label(dialogTitle,box.x()+22,box.y()+20,19,true,theme.text());
        wrap(dialogHint,box.x()+22,box.y()+54,box.w()-44,13,false,theme.secondary(),2);
        if(dialogSubmit!=null) { Rect input=new Rect(box.x()+22,box.y()+100,box.w()-44,36);drawEdit(dialogEdit,input,"",true);popupHit(input,()-> { dialogEdit.focused=true;dialogEdit.click(mx-input.x()-12,v->p.measure(v,14,false),shiftDown); },"","dialog:input"); }
        Rect cancel=new Rect(box.right()-232,box.bottom()-50,98,32),accept=new Rect(box.right()-122,box.bottom()-50,100,32);
        p.rect(cancel,theme.radius(8),theme.chip());center("取消",cancel,14,false,theme.text());popupHit(cancel,()-> { dialogSubmit=null;confirmation=null;dialogEdit.focused=false; },"","dialog:cancel");
        p.rect(accept,theme.radius(8),accent);center(dialogConfirm,accept,14,true,theme.accentText());popupHit(accept,this::submitDialog,"","dialog:submit");
    }
    private void submitDialog() { if(run("应用输入",()-> { if(dialogSubmit!=null) dialogSubmit.accept(dialogEdit.text());else if(confirmation!=null) confirmation.run(); })) { dialogSubmit=null;confirmation=null;dialogEdit.focused=false; } }
    private void finishPopup(){releaseDrag();if(overlayPage!=null){UiPage previous=overlayPage;overlayPage=null;previous.leave(this);}}
    private void drawEdit(TextEdit edit,Rect box,String placeholder,boolean plain) {
        double fontSize=plain?14:12;
        p.rect(box,theme.radius(8),plain?theme.component():theme.chip());p.outline(box,theme.radius(8),1,plain&&edit.focused?accent:theme.border());
        double start=box.x()+(plain?12:34),right=box.right()-(plain?12:edit.text().isEmpty()?10:34);
        if(!plain) p.icon("search",box.x()+10,box.y()+9,14,theme.secondary());
        p.save();try {
            p.clip(new Rect(start,box.y()+3,right-start,box.h()-6),0);
            double cursor=p.measure(edit.text().substring(0,edit.cursor()),fontSize,!plain),offset=Math.max(0,cursor-(right-start)+3);
            double origin=start-offset,top=box.y()+(box.h()-fontSize)/2;
            if(edit.focused&&edit.selected()) {
                double a=p.measure(edit.text().substring(0,edit.selectionStart()),fontSize,!plain),b=p.measure(edit.text().substring(0,edit.selectionEnd()),fontSize,!plain);p.rect(new Rect(origin+a,top-2,b-a,fontSize+5),0,Theme.alpha(accent,.4));
            }
            if(!edit.text().isEmpty()) label(edit.text(),origin,top,fontSize,!plain,plain?theme.text():theme.secondary());
            else if(!edit.focused) label(placeholder,origin,top,fontSize,false,theme.secondary());
            if(edit.focused&&((long)(now*2)%2==0)) p.line(origin+cursor,top-1,origin+cursor,top+fontSize+2,1,theme.secondary());
        }finally { p.restore(); }
        if(!plain&&!edit.text().isEmpty()) { Rect clear=new Rect(box.right()-26,box.y()+8,16,16);p.icon("close",clear.x()+2,clear.y()+2,12,theme.secondary());hit(clear,()-> { edit.text("");scroll=0; },tr("oneconfig.search.clear","Clear"),"search:clear"); }
    }
    public boolean pointerDown(double screenX,double screenY,int button,boolean control,boolean shift,boolean alt) {
        modifiers(control,shift,alt);Point local=editingHud()?new Point(screenX,screenY):viewport.local(screenX,screenY);mx=local.x();my=local.y();sx=screenX;sy=screenY;
        if(!Double.isNaN(closingAt)) return true;
        UiPage rawPage=overlayPage!=null?overlayPage:activePage;
        if(rawPage!=null&&rawPage.recording()&&rawPage.pointerDown(this,button))return true;
        if(rawPage instanceof com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw&&raw.rawMouse(this,button,true))return true;
        if(rawPage instanceof com.muoniumplayer.configskija.ui.pages.KeysPage keys&&!keys.recordingId().isEmpty()&&keys.pointerDown(this,button))return true;
        if(button==3) { back();return true; }if(button==4) { forward();return true; }
        if(button!=0&&activePage!=null&&activePage.pointerDown(this,button)) return true;
        if(button!=0)return false;
        releaseDrag();
        if(overlayPage!=null) {
            for(int i=popupHits.size()-1;i>=0;i--) { Hit hit=popupHits.get(i);if(hit.bounds().contains(mx,my)) { run("弹层操作",hit.action());return true; } }
            overlayPage.pointerDown(this,button);return true;
        }
        if(dialogSubmit!=null||confirmation!=null) { for(int i=popupHits.size()-1;i>=0;i--) { Hit hit=popupHits.get(i);if(hit.bounds().contains(mx,my)) { run("输入框操作",hit.action());return true; } }return true; }
        textInput.pointerDown(this,button);
        for(int i=hits.size()-1;i>=0;i--) {
            Hit hit=hits.get(i);if(hit.bounds().contains(mx,my)) { if(hit.id().contains("input")||hit.id().contains("keybind")||hit.id().contains(".bind.")||hit.id().contains(".name")) search.focused=false;run("界面操作",hit.action());return true; }
        }
        search.focused=false;
        if(activeEditor!=null) activeEditor.focused=false;
        if(activePage!=null&&activePage.pointerDown(this,button)) return true;
        return false;
    }
    public void pointerUp(double screenX,double screenY,int button) {
        Point local=editingHud()?new Point(screenX,screenY):viewport.local(screenX,screenY);mx=local.x();my=local.y();sx=screenX;sy=screenY;
        if(drag!=null) run("拖动完成",()->drag.accept(mx-dragInputOffset,my));
        UiPage rawPage=overlayPage!=null?overlayPage:activePage;
        if(rawPage instanceof com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw) raw.rawMouse(this,button,false);
        if(button!=0) { if(rawPage!=null) rawPage.pointerUp(this,button);return; }
        releaseDrag();textInput.pointerUp(this,button);
        if(overlayPage!=null) overlayPage.pointerUp(this,button);else if(activePage!=null) activePage.pointerUp(this,button);
    }
    private void releaseDrag() { if(drag==null) return;drag=null;Runnable end=dragEnd;dragEnd=null;if(end!=null) run("完成拖动",end); }
    public void wheel(double delta) {
        if(textInput.wheel(this,delta))return;
        if(overlayPage!=null) { overlayPage.wheel(this,delta);return; }
        if(activePage!=null&&activePage.wheel(this,delta)) return;
        if(dialogSubmit!=null||confirmation!=null) return;
        if(editingHud())return;
        if(content.contains(mx,my)) scroll=Geometry.clamp(scroll-delta*44,0,maxScroll);
    }
    public boolean key(Key key,String typed,boolean control,boolean shift,boolean alt) {
        modifiers(control,shift,alt);
        if(dialogSubmit!=null&&key==Key.ENTER){submitDialog();return true;}
        if(overlayPage!=null&&overlayPage.key(this,key,typed,control,shift,alt)) return true;
        if(overlayPage==null&&activePage!=null&&(key==Key.ENTER||key==Key.ESCAPE)&&activePage.key(this,key,typed,control,shift,alt))return true;
        if(textInput.key(this,key,typed,control,shift,alt))return true;
        if(overlayPage==null&&activePage!=null&&!textFocused()&&activePage.key(this,key,typed,control,shift,alt)) return true;
        TextEdit edit=dialogSubmit!=null?dialogEdit:activeEditor!=null&&activeEditor.focused?activeEditor:search.focused?search:null;
        String previousText=edit==null?null:edit.text();
        if(key==Key.ESCAPE) {
            if(overlayPage!=null) { dismissOverlay();return true; }
            if(dialogSubmit!=null||confirmation!=null) { dialogSubmit=null;confirmation=null;dialogEdit.focused=false;return true; }
            if(editingHud()) { navigate(new Route(Page.SETTINGS));return true; }
            close();return true;
        }
        if(key==Key.ENTER&&(dialogSubmit!=null||confirmation!=null)) { submitDialog();return true; }
        if(control&&key==Key.F&&prefs.get("searchKeybind","ACTION+F").equals("ACTION+F")) { requestSearch();return true; }
        if(edit!=null) {
            if(control) {
                switch(key) { case A -> edit.selectAll();case C -> host.clipboard(edit.selectedText());case X -> { host.clipboard(edit.selectedText());edit.insert(""); }case V -> edit.insert(host.clipboard());default -> {} }
                if(key==Key.A||key==Key.C||key==Key.X||key==Key.V) { if(edit==search) scroll=0;if(edit==activeEditor&&activeEditorChanged!=null&&!edit.text().equals(previousText)) activeEditorChanged.accept(edit.text());return true; }
            }
            switch(key) {
                case LEFT -> edit.move(-1,shift,control);case RIGHT -> edit.move(1,shift,control);case HOME -> edit.edge(false,shift);case END -> edit.edge(true,shift);case BACKSPACE -> edit.delete(true,control);case DELETE -> edit.delete(false,control);
                case ENTER -> edit.focused=false;
                default -> { if(!control&&!alt&&typed!=null&&!typed.isEmpty()) edit.insert(typed); }
            }
            if(edit==search) scroll=0;return true;
        }
        if(control&&(key==Key.Z||key==Key.Y)) { if(shift||key==Key.Y)history.redo();else history.undo();return true; }
        if(alt&&key==Key.LEFT) { back();return true; }if(alt&&key==Key.RIGHT) { forward();return true; }
        return false;
    }
    public void dispose() { if(closed) return;if(activePage!=null)activePage.leave(this);if(globalSearchPage instanceof AutoCloseable resource)try{resource.close();}catch(Exception error){host.error("关闭搜索页面",error);}rememberedRoute=nav.current();rememberedClosedAt=System.nanoTime()/1_000_000_000d;textInput.clear(this);closed=true;finishPopup();host.persist();host.dispose(); }
    private void drawScroll() {
        if(maxScroll<=0) return;double h=Math.max(24,content.h()*content.h()/(content.h()+maxScroll));double y=content.y()+(content.h()-h)*scroll/maxScroll;
        Rect track=new Rect(content.right()+9,content.y(),8,content.h()),thumb=new Rect(track.x()+1,y,5,h);p.rect(thumb,3,Theme.alpha(theme.text(),.4));
        hit(track,()-> { drag=(x,py)->scroll=Geometry.clamp((py-content.y()-h/2)/Math.max(1,content.h()-h)*maxScroll,0,maxScroll);drag.accept(mx,my); },"滚动","scrollbar");
    }
    private void buttonContent(Rect r,String text,String icon,Runnable action,String tip,String id) {
        drawButton(r,text,icon,false,true);hitContent(r,action,tip,id);
    }
    private void button(Rect r,String text,String icon,boolean selected,boolean enabled,Runnable action,String tip,String id) {
        drawButton(r,text,icon,selected,enabled);if(enabled) hit(r,()->{host.sound("click");action.run();},tip,id);
    }
    private void drawButton(Rect r,String text,String icon,boolean selected,boolean enabled) {
        boolean hovered=r.contains(mx,my);p.rect(r,theme.radius(8),selected?Theme.alpha(accent,.25):hovered?Theme.alpha(theme.text(),.1):theme.chip());
        if(selected||hovered) p.outline(r,theme.radius(8),1,selected?accent:theme.border());
        int color=enabled?(selected?theme.accentText():theme.text()):Theme.alpha(theme.secondary(),.4);
        if(icon!=null&&!icon.isEmpty()) { double x=text.isEmpty()?r.x()+(r.w()-16)/2:r.x()+11;p.icon(icon,x,r.y()+(r.h()-16)/2,16,color); }
        if(text!=null&&!text.isEmpty()) { if(icon==null||icon.isEmpty()) center(trim(text,r.w()-14,13,false),r,13,false,color);else label(trim(text,r.w()-42,13,false),r.x()+34,r.y()+(r.h()-14)/2,13,false,color); }
    }
    private void hit(Rect r,Runnable action,String tip,String id) { if(drawingOutgoing) return;if(transitionOffset!=0&&!drawingOverlay) {double offset=transitionOffset;r=new Rect(r.x()+offset,r.y(),r.w(),r.h());Runnable original=action;action=()->{double previous=mx,oldOffset=actionInputOffset;mx-=offset;actionInputOffset=offset;try{original.run();}finally{mx=previous;actionInputOffset=oldOffset;}};}if(drawingOverlay) { popupHit(r,action,tip,id);return; }Hit h=new Hit(r,action,tip,id);hits.add(h);if(r.contains(mx,my)&&tip!=null&&!tip.isBlank()) tooltipCandidate(h); }
    private void hitContent(Rect r,Runnable action,String tip,String id) { double x=Math.max(r.x(),content.x()),y=Math.max(r.y(),content.y()),right=Math.min(r.right(),content.right()),bottom=Math.min(r.bottom(),content.bottom());if(right>x&&bottom>y) hit(new Rect(x,y,right-x,bottom-y),action,tip,id); }
    private void popupHit(Rect r,Runnable action,String tip,String id) { popupHits.add(new Hit(r,action,tip,id)); }
    private Hit tooltip;
    private void tooltipCandidate(Hit hit) { tooltip=hit; }
    private void drawTooltip() {
        Hit hovered=tooltip;tooltip=null;
        if(hovered==null||!hovered.bounds().contains(mx,my)||dialogSubmit!=null||confirmation!=null||drag!=null) { hoverId="";return; }
        if(!hovered.id().equals(hoverId)) { hoverId=hovered.id();hoverSince=now;return; }
        if(now-hoverSince<.2) return;
        double fontSize=13,maxWidth=260,inner=Math.max(20,Math.min(maxWidth-24,p.measure(hovered.tip(),fontSize,false))),limitW=editingHud()?screenW:1391,limitH=editingHud()?screenH:700;
        List<String> lines=new ArrayList<>();String rest=hovered.tip();
        while(!rest.isEmpty()){String part=trim(rest,inner,fontSize,false);boolean cut=part.endsWith("…")&&!part.equals(rest);int used=cut?part.length()-1:part.length();if(used<=0)break;lines.add(rest.substring(0,used));rest=rest.substring(used);}
        double w=inner+24,h=lines.size()*18+16;Rect box=new Rect(Geometry.clamp(mx+12,8,Math.max(8,limitW-w-8)),Geometry.clamp(my+20,8,Math.max(8,limitH-h-8)),w,h);
        p.shadow(box,theme.radius(8),8,0,0,3,0x55000000);p.rect(box,theme.radius(8),theme.popup());
        for(int i=0;i<lines.size();i++)label(lines.get(i),box.x()+12,box.y()+8+i*18,fontSize,false,theme.text());
    }

    private void label(String text,double x,double y,double size,boolean bold,int color) { if(text!=null&&!text.isEmpty()) p.text(text,x,y,size,bold,color); }
    private void center(String text,Rect r,double size,boolean bold,int color) { label(text,r.x()+(r.w()-p.measure(text,size,bold))/2,r.y()+(r.h()-size)/2,size,bold,color); }
    private String trim(String text,double width,double size,boolean bold) { if(text==null) return "";if(p.measure(text,size,bold)<=width) return text;String ending="…";double room=Math.max(0,width-p.measure(ending,size,bold));int end=0;while(end<text.length()) { int next=text.offsetByCodePoints(end,1);if(p.measure(text.substring(0,next),size,bold)>room) break;end=next; }return text.substring(0,end)+ending; }
    private void wrap(String text,double x,double y,double width,double size,boolean bold,int color,int lines) { if(text==null) return;String remaining=text;for(int line=0;line<lines&&!remaining.isEmpty();line++) { String part=trim(remaining,width,size,bold);boolean cut=part.endsWith("…")&&!part.equals(remaining);String drawn=cut&&line<lines-1?part.substring(0,part.length()-1):part;label(drawn,x,y+line*(size+5),size,bold,color);if(!cut) break;int used=part.length()-1;if(used==0) break;remaining=remaining.substring(used); } }
    private void empty(Rect r,String title,String subtitle) { center(title,new Rect(r.x(),r.y()+r.h()/2-32,r.w(),30),20,true,theme.text());center(subtitle,new Rect(r.x(),r.y()+r.h()/2+5,r.w(),26),13,false,theme.secondary()); }
    public static String format(double value) { if(!Double.isFinite(value)) return "—";if(Math.abs(value-Math.rint(value))<.0001) return String.format(Locale.ROOT,"%.0f",value);String out=String.format(Locale.ROOT,"%.3f",value);while(out.endsWith("0")) out=out.substring(0,out.length()-1);return out; }
    private boolean run(String action,Runnable task) { try { task.run();return true; }catch(RuntimeException ex) { host.error(action,ex);return false; } }
    public void globalSearch(UiPage page){globalSearchPage=page;}
    public void register(Page page,UiPage renderer) { pages.put(page,renderer); }
    public Painter painter() { return p; }
    public Theme theme() { return theme; }
    public int accent() { return accent; }
    public ConfigHost host() { return host; }
    public UiPreferences preferences() { return prefs; }
    public String query() { return search.text(); }
    public Navigation.Route route() { return nav.current(); }
    public double mouseX() { return mx-transitionOffset; }
    public double mouseY() { return my; }
    public double width() { return editingHud()?screenW:1391; }
    public double height() { return editingHud()?screenH:700; }
    public double time() { return now; }
    public double delta() { return frameDelta; }
    public boolean control() { return controlDown; }
    public boolean shift() { return shiftDown; }
    public boolean alt() { return altDown; }
    public int nativeKeyCode() { return nativeCode; }
    public boolean textFocused() { return textInput.focused()||search.focused||dialogSubmit!=null||(activeEditor!=null&&activeEditor.focused); }
    public void nativeKeyCode(int code) { nativeCode=code; }
    public Rect content() { return content; }
    public void content(Rect bounds) { content=bounds; }
    public double scroll() { return scroll; }
    public void scroll(double value) { scroll=Geometry.clamp(value,0,Math.max(0,maxScroll)); }
    public void scrollRange(double maximum) { maxScroll=Math.max(0,maximum);scroll=Geometry.clamp(scroll,0,maxScroll); }
    public void hit(String id,Rect bounds,Runnable action,String tooltip) { hit(bounds,action,tooltip,id); }
    public void contentHit(String id,Rect bounds,Runnable action,String tooltip) { hitContent(bounds,action,tooltip,id); }
    public void tooltip(String id,Rect bounds,String text) { if(bounds.contains(mx,my)) tooltipCandidate(new Hit(bounds,null,text,id)); }
    public void startDrag(BiConsumer<Double,Double> update,Runnable finish) { releaseDrag();dragInputOffset=actionInputOffset;drag=update;dragEnd=finish;if(update!=null) update.accept(mx,my); }
    public void toast(String message) { status(message); }
    public void sound(String event) { host.sound(event); }
    public void edit(Option option,Object value) { change(option,value); }
    public void beginEdit(String name) { if(history.active()) commitEdit();historyScope=Set.of();history.begin(name); }
    public void beginEdit(Option option) { if(history.active()) commitEdit();historyScope=Set.of(option.id());history.begin(option.title()); }
    public void commitEdit() { if(history.commit()) host.persist();historyScope=Set.of(); }
    public void cancelEdit() { history.cancel(); }
    public TextInputController textInputController(){return textInput;}
    public void bareTextField(String id,TextEdit edit,Rect bounds,TextInputController.Style style,Consumer<String> changed,Runnable blur) {
        if(drawingOutgoing){p.text(edit.text(),bounds.x(),bounds.y(),style.fontSize,false,style.color==null?theme.text():style.color);return;}
        textInput.field(this,id,edit,bounds,style,changed,blur,null);
    }
    public void bareTextField(String id,TextEdit edit,Rect bounds,TextInputController.Style style,Consumer<String> changed,Runnable blur,Runnable submit) {
        if(drawingOutgoing){p.text(edit.text(),bounds.x(),bounds.y(),style.fontSize,false,style.color==null?theme.text():style.color);return;}
        textInput.field(this,id,edit,bounds,style,changed,blur,submit);
    }
    public void textField(String id,TextEdit edit,Rect bounds,String placeholder,Consumer<String> changed) {
        p.rect(bounds,theme.radius(8),theme.component());p.outline(bounds,theme.radius(8),1,edit.focused?accent:theme.border());
        TextInputController.Style style=new TextInputController.Style(14).placeholder(placeholder);style.clip=bounds;bareTextField(id,edit,new Rect(bounds.x()+12,bounds.y()+7,Math.max(1,bounds.w()-24),Math.max(1,bounds.h()-14)),style,changed,null);
    }
    public void overlay(UiPage page) { finishPopup();overlayPage=page;activeEditor=null; }
    public void dismissOverlay() { if(overlayPage!=null) { UiPage previous=overlayPage;overlayPage=null;previous.leave(this); }activeEditor=null; }
    private void renderOverlay() { if(overlayPage==null) return;popupHits.clear();drawingOverlay=true;textInput.pushScope(this,"overlay",true);try { overlayPage.render(this); }finally { textInput.popScope();drawingOverlay=false; } }
    public void close() { if(Double.isNaN(closingAt)) { finishPopup();closeStart=openAlpha;closingAt=now;host.sound("CLOSE"); } }
    public double animate(String key,double target,double seconds,Motion.Easing easing) { return motion.value((key.startsWith("appearance.theme.")?"global/":nav.current().key()+"/")+key,target,seconds,easing,now); }

    public boolean recording(){UiPage page=overlayPage!=null?overlayPage:activePage;return page!=null&&(page.recording()||(page instanceof com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw&&raw.recording()));}
    public void notificationCenter(UiPage page) { notificationCenter=page; }
    public double backgroundAlpha() { if(Double.isNaN(openedAt)) return 0;if(Double.isNaN(closingAt)) return Geometry.clamp((now-openedAt)/.25,0,1);double duration=Math.min(.16,prefs.number("animationTime",.6));return 1-Motion.ease(Motion.Easing.EASE_OUT_EXPO,Geometry.clamp((now-closingAt)/Math.max(.001,duration),0,1)); }
    public double shellAlpha() { return openAlpha; }
    private String tr(String key,String fallback) { return host.translate(key,fallback); }
    private String searchPlaceholder() { return switch(nav.current().page()) { case SETTINGS -> tr("oneconfig.search.search_settings","Search settings...");case MODS -> tr("oneconfig.page.mods.search","Search Mods...");case FAVORITES -> tr("oneconfig.page.favorites.search","Search Favorites...");case PROFILES -> tr("oneconfig.page.profiles.search","Search Profiles...");case THEMES -> tr("oneconfig.page.themes.search","Search Themes...");case PREFERENCES -> tr("oneconfig.page.preferences.search","Search Preferences...");case KEYS -> tr("oneconfig.keybinds.search","Search Keybinds...");default -> tr("oneconfig.search.placeholder","Search..."); }; }
    private void prepareTransition(boolean back) {
        if(!prefs.flag("showPageAnimations",true)||p==null) { outgoingRoute=null;return; }
        outgoingRoute=nav.current();outgoingScroll=scroll;outgoingQuery=search.text();transitionAt=now;transitionBack=back;
    }
    private void drawTransitionPages() {
        double duration=prefs.number("pageAnimationDuration",.3),linear=Double.isNaN(transitionAt)?1:Geometry.clamp((now-transitionAt)/Math.max(.001,duration),0,1);
        if(outgoingRoute==null||linear>=1) { outgoingRoute=null;transitionOffset=0;drawPage();return; }
        double eased=Motion.ease(Motion.Easing.FAST_OUT_SLOW_IN,linear),distance=1127/5d,direction=transitionBack?-1:1;
        Route current=nav.current();double currentScroll=scroll;String currentQuery=search.text();UiPage currentPage=activePage;
        // Source NavHost runs outgoing and incoming slide/fade simultaneously; only the incoming page receives input.
        p.save();try { p.clip(new Rect(264,74,1127,626),0);
            drawingOutgoing=true;nav.temporary(outgoingRoute);scroll=outgoingScroll;search.text(outgoingQuery);p.save();try { p.translate(-direction*distance*eased,0);p.layer(new Rect(264,74,1127,626),1-eased);try { drawPage(); }finally { p.restore(); } }finally { p.restore(); }
            drawingOutgoing=false;nav.temporary(current);scroll=currentScroll;search.text(currentQuery);activePage=currentPage;
            transitionOffset=direction*distance*(1-eased);p.save();try { p.translate(transitionOffset,0);p.layer(new Rect(264,74,1127,626),eased);try { drawPage(); }finally { p.restore(); } }finally { p.restore(); }
        }finally { drawingOutgoing=false;transitionOffset=0;nav.temporary(current);p.restore(); }
    }

    public boolean rawKey(int code,boolean pressed) {
        nativeCode=code;UiPage page=overlayPage!=null?overlayPage:activePage;
        if(page instanceof com.muoniumplayer.configskija.ui.search.SearchPage search&&search.rawKey(this,code,pressed))return true;
        if(page instanceof com.muoniumplayer.configskija.ui.settings.SettingsPage settings&&settings.rawKey(this,code,pressed))return true;
        if(page instanceof com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw&&raw.rawKey(this,legacyToken(code),pressed))return true;
        if(!pressed&&page instanceof com.muoniumplayer.configskija.ui.pages.KeysPage keys)return keys.keyReleased(this,code);
        return false;
    }
    private String legacyToken(int code) {
        return switch(code) {case 29->"LCONTROL";case 157->"RCONTROL";case 42->"LSHIFT";case 54->"RSHIFT";case 56->"LMENU";case 184->"RMENU";case 219->"LMETA";case 220->"RMETA";case 1->"ESCAPE";case 14->"BACKSPACE";case 211->"DELETE";case 28,156->"ENTER";default->nativeToken.apply(code);};
    }
    private java.util.function.IntFunction<String> nativeToken=code->"KEY:"+code;
    public void keyTokens(java.util.function.IntFunction<String> tokens){nativeToken=tokens;}
    public void requestSearch(){textInput.blur(this);if(editingHud())navigate(new Route(Page.SETTINGS));search.focused=true;search.selectAll();}
    public void menuKey(){if(!Double.isNaN(closingAt)){double remaining=Math.max(0,openAlpha);openedAt=now+Math.log(Math.max(.00001,1-remaining))/Math.log(2)*prefs.number("animationTime",.6)/10;closingAt=Double.NaN;}else if(prefs.flag("keybindClosesGui",false))close();else navigate(new Route(editingHud()?Page.SETTINGS:Page.HUD));}

}
