package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;

/** Local profile cards and the original bottom template strip share the same page. */
public final class LibraryProfiles implements UiPage {
    public static final int COLUMNS=5;
    public static final double CARD_HEIGHT=180, GAP=19, TEMPLATE_SECTION_HEIGHT=227;
    private static final List<String> ICONS=List.of("profiles","star","settings","cog","paintbrush","keyboard","hud","combat","qol","cloud");
    private final TextEdit name=new TextEdit();
    private final LibraryTemplates templates=new LibraryTemplates();
    private final KeysPage profileKeys=new KeysPage();
    private boolean recordedProfileVisible;
    private boolean favorites, creating, loaded, pending;
    private static final String NONE = "\u0000";
    private String editing=NONE, editor="", menu=NONE, error="", pendingName="";
    private Rect grid;
    public static double cardWidth(double width) { return (width-4*GAP)/5; }
    public static String nextCloneName(String title,Collection<String> existing) {
        Set<String> ids=new HashSet<>();existing.forEach(s->ids.add(s.toLowerCase(Locale.ROOT)));
        String base=title+" copy",candidate=base;int suffix=2;while(ids.contains(candidate.toLowerCase(Locale.ROOT)))candidate=base+" "+suffix++;return candidate;
    }
    public static String nameError(String raw,List<Profiles.Profile> profiles,String except) {
        String value=raw.trim();if(value.isEmpty())return "请输入配置名称";
        if(value.length()>80)return "配置名称不能超过 80 个字符";
        if(value.equals(".")||value.equals("..")||value.chars().anyMatch(ch -> ch < 32 || "\\/:*?\"<>|".indexOf(ch) >= 0))return "配置名称不能包含路径或控制字符";
        if(profiles.stream().anyMatch(p->!p.id().equals(except)&&p.name().equalsIgnoreCase(value)))return "此名称已被使用";
        return "";
    }
    public void render(PageContext c) {
        Profiles service=c.host().profiles();Rect b=c.content();profileKeys.pollRecording(c);recordedProfileVisible=false;
        if(!loaded&&service!=null){loaded=true;service.refresh();}
        if(pending&&service!=null&&!service.busy()) {
            pending=false;String status=service.status();
            if(status.contains("失败")||status.contains("拒绝"))error=status;
            else {if(creating||!editing.equals(NONE)){creating=false;editing=NONE;editor="";name.text("");error="";} if(!pendingName.isEmpty())c.toast(status);}
            pendingName="";
        }
        if(templates.focused(c)) { profileKeys.leave(c);templates.renderFocus(c);if(busy(c,service))busyOverlay(c);return; }
        double x=b.x(),y=b.y();
        x+=LibraryPaint.chip(c,"profiles.all","All profiles",null,x,y,!favorites,true,()->{favorites=false;clearEditor();c.scroll(0);},"")+8;
        LibraryPaint.chip(c,"profiles.favorites","Favorites","star",x,y,favorites,true,()->{favorites=true;clearEditor();c.scroll(0);},"");
        LibraryServices.ProfileExtras extras=c.host() instanceof LibraryServices.ProfileExtras p?p:null;
        Set<String> caps=extras==null?Set.of():extras.capabilities();
        double folderWidth=LibraryPaint.chipWidth(c,"Open folder","folder");double controlsWidth=LibraryPaint.chipWidth(c,"Separate controls","keyboard");
        LibraryPaint.chip(c,"profiles.separate","Separate controls","keyboard",b.right()-folderWidth-controlsWidth-8,y,extras==null||extras.separateControls(),caps.contains("controls")&&!busy(c,service),()->LibraryPaint.run(c,"切换独立键位",()->extras.separateControls(!extras.separateControls())),caps.contains("controls")?"":"宿主尚未提供按配置隔离键位的契约");
        LibraryPaint.chip(c,"profiles.folder","Open folder","folder",b.right()-folderWidth,y,false,caps.contains("folder"),()->LibraryPaint.run(c,"打开配置目录",extras::openProfilesFolder),caps.contains("folder")?"":"宿主尚未提供配置目录入口");
        grid=new Rect(b.x(),y+52,b.w(),Math.max(0,b.h()-52-TEMPLATE_SECTION_HEIGHT-GAP));
        List<Profiles.Profile> all=service==null?List.of():List.copyOf(service.list());
        List<Profiles.Profile> visible=all.stream().filter(p->!favorites||favorite(c,p.id())).filter(p->matches(c,p,extras)).toList();
        if(!editing.equals(NONE)&&visible.stream().noneMatch(p->p.id().equals(editing)))clearEditor();
        if(creating&&(favorites||!c.query().isBlank()))clearEditor();
        int count=visible.size()+(!favorites&&c.query().isBlank()?1:0);c.scrollRange(Math.max(0,Math.ceil(count/5.0)*(CARD_HEIGHT+GAP)-GAP-grid.h()));templates.restoreIntegratedScroll(c);
        double cw=cardWidth(b.w()),cy=grid.y()-c.scroll();int n=0;
        c.content(grid);c.painter().save();try {c.painter().clip(grid,0);
            if(!favorites&&c.query().isBlank()) {Rect create=new Rect(grid.x(),cy,cw,CARD_HEIGHT);if(create.intersects(grid))createCard(c,service,all,create);n++;}
            for(Profiles.Profile profile:visible) {
                int col=n%5,row=n/5;Rect r=new Rect(grid.x()+col*(cw+GAP),cy+row*(CARD_HEIGHT+GAP),cw,CARD_HEIGHT);
                if(r.intersects(grid))profileCard(c,service,all,profile,extras,caps,r);n++;
            }
            if(n==0) LibraryPaint.empty(c,c.query().isBlank()?"No favorite profiles.":"No profiles match \""+c.query()+"\"",grid);
        }finally{c.painter().restore();c.content(b);}
        if(profileKeys.recording()&&!recordedProfileVisible)profileKeys.leave(c);
        c.scrollRange(Math.max(0,Math.ceil(n/5.0)*(CARD_HEIGHT+GAP)-GAP-grid.h()));
        templates.renderSection(c,new Rect(b.x(),b.bottom()-TEMPLATE_SECTION_HEIGHT,b.w(),TEMPLATE_SECTION_HEIGHT));
        if(service==null)LibraryPaint.status(c,"宿主未提供本地配置服务",new Rect(b.x(),grid.bottom()-20,b.w(),18));
        else if(!service.status().isBlank())c.tooltip("profiles.status",grid,service.status());
        if(!menu.equals(NONE)){Profiles.Profile selected=all.stream().filter(p->p.id().equals(menu)).findFirst().orElse(null);if(selected==null)menu=NONE;else renderMenu(c,service,all,selected,extras,caps);}
        if(profileKeys.recording())profileKeys.capturePointer(c);
        if(busy(c,service))busyOverlay(c);
    }
    private boolean matches(PageContext c,Profiles.Profile p,LibraryServices.ProfileExtras extras) {
        String q=c.query().trim().toLowerCase(Locale.ROOT);return q.isEmpty()||LibrarySearch.matches(c,p.name(),q)||LibrarySearch.matches(c,p.id(),q)||LibrarySearch.matches(c,extras==null?"profiles":extras.profileIcon(p.id()),q);
    }
    private boolean busy(PageContext c,Profiles service){return service!=null&&service.busy()||c.host() instanceof LibraryServices.PresetExtras extras&&extras.busy();}
    private boolean favorite(PageContext c,String id){return c.preferences().flag("profiles.favorite."+id,false);}
    private void clearEditor(){creating=false;editing=NONE;editor="";menu=NONE;error="";name.focused=false;}
    private void createCard(PageContext c,Profiles service,List<Profiles.Profile> all,Rect r) {
        boolean hover=r.contains(c.mouseX(),c.mouseY());double a=LibraryMotion.animate(c,"profiles.new.hover",hover||creating?1:0,.2,Motion.Easing.SPRING);
        LibraryPaint.card(c,r,Theme.mix(c.theme().border(),c.accent(),a));double cx=r.x()+r.w()/2;
        Rect circle=new Rect(cx-32,r.y()+36,64,64);c.painter().rect(circle,32,Theme.mix(Theme.alpha(c.theme().text(),.06),Theme.alpha(c.accent(),.18),a));
        LibraryPaint.drawIcon(c,"plus",cx-15,r.y()+53,30,Theme.mix(c.theme().secondary(),c.accent(),a));
        if(creating){
            Rect field=new Rect(cx-75,r.y()+124,150,33);c.textField("profiles.new.name",name,field,"Profile name",v->error="");
            actions(c,"new",r,()->submit(c,service,all,"create",""),()->{clearEditor();name.text("");},service!=null&&!service.busy(),false);
            if(!error.isEmpty())profileError(c,error,field.x(),field.bottom()+4,150);
        } else {LibraryPaint.center(c,"New profile",cx,r.y()+124,18,true,Theme.alpha(c.theme().text(),.86));
            if(service!=null)c.contentHit("profiles.create",r,()->{clearEditor();creating=true;name.text("");name.focused=true;},"New profile");}
    }
    private Rect menuAnchor;
    private void profileCard(PageContext c,Profiles service,List<Profiles.Profile> all,Profiles.Profile profile,LibraryServices.ProfileExtras extras,Set<String> caps,Rect r) {
        boolean active=service!=null&&profile.id().equals(service.active()),hover=r.contains(c.mouseX(),c.mouseY()),isEditing=editing.equals(profile.id()),opened=menu.equals(profile.id());
        String mode=isEditing?editor:"";boolean compact=isEditing&&!mode.equals("delete");
        LibraryPaint.card(c,r,c.theme().border());double selection=LibraryMotion.animate(c,"profiles.active."+profile.id(),active?1:0,.2,Motion.Easing.SPRING);
        if(selection>.001)c.painter().outline(r,c.theme().radius(16),3,Theme.alpha(c.accent(),selection));
        if(!isEditing&&!opened)c.contentHit("profiles.activate."+profile.id(),r,()->{if(service!=null&&!service.busy()&&!active)LibraryPaint.run(c,"切换配置",()->service.activate(profile.id(),c.preferences()::replace));},active?"Active profile":"Activate profile");
        double cx=r.x()+r.w()/2;String icon=extras==null?c.preferences().get("profiles.icon."+profile.id(),"profiles"):extras.profileIcon(profile.id());double iconSize=compact?42:64;
        LibraryPaint.drawIcon(c,icon,cx-iconSize/2,r.y()+(compact?28:36),iconSize,c.theme().text());
        if(mode.equals("rename")||mode.equals("clone")){
            Rect field=new Rect(cx-75,r.y()+84,150,33);c.textField("profiles.edit.name",name,field,"Profile name",v->error="");if(!error.isEmpty())profileError(c,error,field.x(),field.bottom()+4,150);
        }else if(mode.equals("icon")){
            for(int n=0;n<ICONS.size();n++){String choice=ICONS.get(n);Rect cell=new Rect(cx-76+n%5*32,r.y()+84+n/5*30,24,24);c.painter().rect(cell,c.theme().radius(8),choice.equals(icon)?Theme.alpha(c.accent(),.16):0);
                c.painter().outline(cell,c.theme().radius(8),1,choice.equals(icon)?c.accent():c.theme().border());LibraryPaint.drawIcon(c,choice,cell.x()+5,cell.y()+5,14,choice.equals(icon)?c.accent():c.theme().secondary());
                c.contentHit("profiles.icon."+choice,cell,()->LibraryPaint.run(c,"更换配置图标",()->{extras.profileIcon(profile.id(),choice);clearEditor();}),choice);}
        }else{
            LibraryPaint.center(c,LibraryPaint.ellipsis(c.painter(),profile.name(),r.w()-36,18,true),cx,r.y()+124,18,true,c.theme().text());
            if(mode.equals("delete"))LibraryPaint.center(c,"确认删除此配置？",cx,r.y()+149,13,false,LibraryPaint.DANGER);
        }
        if(isEditing)actions(c,profile.id(),r,()->{if(mode.equals("delete")){LibraryPaint.run(c,"删除配置",()->service.delete(profile.id()));clearEditor();}else submit(c,service,all,mode,profile.id());},this::clearEditor,service!=null&&!service.busy(),mode.equals("delete"));
        else{
            if(favorite(c,profile.id())) LibraryPaint.icon(c,"profiles.favorite."+profile.id(),"star-filled",new Rect(r.right()-67,r.y()+13,22,22),c.theme().favorite(),true,()->c.preferences().set("profiles.favorite."+profile.id(),false),"Remove favorite");
            if(active||hover||opened)LibraryPaint.icon(c,"profiles.menu."+profile.id(),"settings",new Rect(r.right()-35,r.y()+13,22,22),c.theme().secondary(),service!=null&&!service.busy(),()->{clearEditor();menu=profile.id();menuAnchor=r;},"Profile actions");
            if(extras!=null){KeyBindings.Entry binding=extras.profileKey(profile.id());if(binding!=null&&(active||hover||opened||binding.current().bound()||profileKeys.recordingId().equals(binding.id()))){profileKeys.renderInline(c,binding,new Rect(cx-55,r.bottom()-30,110,24));if(profileKeys.recordingId().equals(binding.id()))recordedProfileVisible=true;}}
        }
    }
    private void actions(PageContext c,String id,Rect r,Runnable accept,Runnable cancel,boolean enabled,boolean danger){
        LibraryPaint.icon(c,"profiles.accept."+id,"tick",new Rect(r.right()-67,r.y()+13,22,22),danger?LibraryPaint.DANGER:c.accent(),enabled,accept,"Confirm");
        LibraryPaint.icon(c,"profiles.cancel."+id,"close",new Rect(r.right()-35,r.y()+13,22,22),c.theme().secondary(),true,cancel,"Cancel");
    }
    private void submit(PageContext c,Profiles service,List<Profiles.Profile> all,String mode,String id){
        if(service==null||service.busy())return;error=nameError(name.text(),all,mode.equals("rename")?id:null);if(!error.isEmpty())return;
        LibraryPaint.run(c,"保存配置",()->{switch(mode){case "create"->service.save(name.text().trim(),c.preferences().snapshot());case "clone"->service.duplicate(id,name.text().trim());case "rename"->service.rename(id,name.text().trim());default->throw new IllegalStateException(mode);}pending=true;pendingName=name.text();});
    }
    private void profileError(PageContext c,String value,double x,double y,double width){for(String line:LibraryPaint.wrap(c.painter(),value,width,11)){c.painter().text(line,x,y,11,false,LibraryPaint.DANGER);y+=13;}}
    private void renderMenu(PageContext c,Profiles service,List<Profiles.Profile> all,Profiles.Profile profile,LibraryServices.ProfileExtras extras,Set<String> caps){
        Rect anchor=menuAnchor==null?grid:menuAnchor;double width=188;double x=Math.min(c.content().right()-width,anchor.right()-width+3),y=anchor.y()+38;
        boolean editable=!profile.id().isEmpty();List<String[]> items=new ArrayList<>();items.add(new String[]{"clone","copy","Clone"});if(editable){items.add(new String[]{"rename","text-input","Rename"});items.add(new String[]{"icon","paintbrush","Change icon"});}items.add(new String[]{"favorite",favorite(c,profile.id())?"star-filled":"star",favorite(c,profile.id())?"Remove favorite":"Favorite"});items.add(new String[]{"export","down","Export / share"});if(editable)items.add(new String[]{"delete","trash","Delete"});
        Rect panel=new Rect(x,Math.min(y,c.content().bottom()-items.size()*31-8),width,items.size()*31+12);c.hit("profiles.menu.dismiss",c.shell(),()->menu=NONE,"");c.painter().rect(panel,c.theme().radius(16),c.theme().popup());c.painter().outline(panel,c.theme().radius(16),1,c.theme().border());
        for(int n=0;n<items.size();n++){String[] item=items.get(n);Rect row=new Rect(panel.x()+4,panel.y()+4+n*31,panel.w()-8,31);boolean enabled=!service.busy()&&(!item[0].equals("icon")||caps.contains("icons"))&&(!item[0].equals("export")||caps.contains("export"));
            double hover=LibraryMotion.animate(c,"profiles.menu.hover."+item[0],row.contains(c.mouseX(),c.mouseY())&&enabled?1:0,.12,Motion.Easing.FAST_OUT_SLOW_IN);if(hover>.001)c.painter().rect(row,c.theme().radius(12),Theme.alpha(c.theme().text(),.10*hover));int color=item[0].equals("delete")?LibraryPaint.DANGER:c.theme().text();if(!enabled)color=Theme.alpha(color,.35);
            LibraryPaint.drawIcon(c,item[1],row.x()+10,row.y()+8,14,color);c.painter().text(item[2],row.x()+32,row.y()+7,13,false,color);
            if(enabled)c.hit("profiles.action."+item[0],row,()->{menu=NONE;switch(item[0]){case "favorite"->c.preferences().set("profiles.favorite."+profile.id(),!favorite(c,profile.id()));case "export"->LibraryPaint.run(c,"导出配置",()->extras.exportProfile(profile.id()));default->{creating=false;editing=profile.id();editor=item[0];error="";name.text(editor.equals("clone")?nextCloneName(profile.name(),all.stream().map(Profiles.Profile::name).toList()):profile.name());name.focused=editor.equals("clone")||editor.equals("rename");}}},item[2]);else c.tooltip("profiles.unavailable."+item[0],row,"宿主尚未提供此能力");}
    }
    private void busyOverlay(PageContext c){
        Rect r=c.shell();c.painter().rect(r,0,0x8C000000);
        String title="Applying profile changes…",detail="The game will stop responding until it finishes.";
        double width=Math.max(c.painter().measure(title,18,true),c.painter().measure(detail,14,false))+64,height=101;
        Rect panel=new Rect(r.x()+(r.w()-width)/2,r.y()+(r.h()-height)/2,width,height);
        c.painter().rect(panel,c.theme().radius(16),c.theme().card());
        LibraryPaint.center(c,title,panel.x()+width/2,panel.y()+24,18,true,c.theme().text());
        LibraryPaint.center(c,detail,panel.x()+width/2,panel.y()+24+26+6,14,false,c.theme().secondary());
        c.hit("profiles.busy",r,()->{},"");
    }
    @Override public boolean recording(){return profileKeys.recording();}
    public boolean wheel(PageContext c,double delta){return busy(c,c.host().profiles())||templates.wheel(c,delta);}
    public boolean pointerDown(PageContext c,int button){return busy(c,c.host().profiles())||profileKeys.pointerDown(c,button)||templates.pointerDown(c,button);}
    public boolean key(PageContext c,ConfigView.Key key,String typed,boolean control,boolean shift,boolean alt){
        if(busy(c,c.host().profiles()))return true;
        if(profileKeys.recording())return profileKeys.key(c,key,typed,control,shift,alt);
        if(templates.focused(c))return templates.key(c,key);
        if(key==ConfigView.Key.ESCAPE&&(!menu.equals(NONE)||creating||!editing.equals(NONE))){clearEditor();return true;}
        if(key==ConfigView.Key.ENTER&&name.focused&&(creating||editor.equals("rename")||editor.equals("clone"))){Profiles service=c.host().profiles();submit(c,service,service==null?List.of():service.list(),creating?"create":editor,editing);return true;}return false;
    }
    public void pointerUp(PageContext c,int button){profileKeys.pointerUp(c,button);}
    public void leave(PageContext c){clearEditor();templates.leave(c);profileKeys.leave(c);}
}
