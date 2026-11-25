package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;

/** Keybind catalog with the source component's single-key/chord recording and live conflict coloring. */
public final class KeysPage implements UiPage {
    public static final int CONFLICT_COLOR=0xFFE0524F;
    private String recording="",menu="",initial;
    private KeyBindings supplied;
    private KeysRecording recorder;
    private KeyBindings.Entry recordingEntry;
    private Rect menuAnchor;
    private Map<String,List<KeyBindings.Entry>> conflictMap=Map.of();
    public KeysPage(){this("");}
    public KeysPage(String initial){this.initial=initial;}
    public KeysPage(KeyBindings bindings){this();supplied=Objects.requireNonNull(bindings);}
    private KeyBindings service(PageContext c){return supplied!=null?supplied:c.host() instanceof LibraryServices.Keys source?source.keyBindings():null;}
    @Override public boolean recording(){return !recording.isEmpty();}
    private void begin(KeyBindings.Entry entry){if(!entry.editable())return;recording=entry.id();recordingEntry=entry;recorder=new KeysRecording();menu="";}
    private void cancel(){recording="";recordingEntry=null;recorder=null;}
    private void commit(PageContext c,KeyBindings.Chord chord){KeyBindings s=service(c);String id=recording;if(s==null){cancel();return;}try{s.set(id,chord);cancel();c.sound("keybind");}catch(RuntimeException failure){c.host().error("修改按键",failure);cancel();}}
    public String recordingId(){return recording;}
    public void capturePointer(PageContext c){if(recording())c.hit("keys.record.capture",c.shell(),()->pointerDown(c,0),"Press keys...");}
    public void pollRecording(PageContext c){KeyBindings s=service(c);if(recording()&&!recordingEntry.singleKey()&&s instanceof KeyBindings.InputState input){recorder.poll(input);if(recorder.released())commit(c,recorder.chord());}}
    public void renderInline(PageContext c,KeyBindings.Entry entry,Rect bounds){
        KeyBindings s=service(c);boolean capture=entry.id().equals(recording);String label=capture?(recorder.any()&&s!=null?s.display(recorder.raw()):"Press keys..."):entry.display();
        KeyBindings.Entry shown=new KeyBindings.Entry(entry.id(),entry.groupId(),entry.groupTitle(),entry.groupIcon(),entry.category(),entry.subcategory(),entry.title(),entry.description(),label,entry.current(),entry.defaults(),entry.singleKey(),s==null?"宿主尚未接入配置快捷键服务":entry.unavailable());
        bindingButton(c,shown,bounds,s!=null&&!s.conflicts(entry).isEmpty(),()->begin(entry),capture);
    }
    public void render(PageContext c){
        keyButtons.clear();KeyBindings s=service(c);Rect b=c.content();if(s==null){readOnly(c,b);return;}List<KeyBindings.Entry> all=List.copyOf(s.entries());conflictMap=s.conflictMap(all);
        if(!initial.isEmpty()){String id=initial;initial="";all.stream().filter(e->e.id().equals(id)).findFirst().ifPresent(this::begin);}
        if(recording()&&all.stream().noneMatch(e->e.id().equals(recording)))cancel();
        if(recording()&&!recordingEntry.singleKey()&&s instanceof KeyBindings.InputState input){recorder.poll(input);if(recorder.released())commit(c,recorder.chord());}
        List<KeyBindings.Entry> visible=all.stream().filter(e->LibrarySearch.matches(c,e.title(),c.query())||LibrarySearch.matches(c,e.description(),c.query())||LibrarySearch.matches(c,e.category(),c.query())||LibrarySearch.matches(c,e.subcategory(),c.query())||LibrarySearch.matches(c,e.id(),c.query())||LibrarySearch.matches(c,e.path(),c.query())||e.tags().stream().anyMatch(tag->LibrarySearch.matches(c,tag,c.query()))).toList();LinkedHashMap<String,List<KeyBindings.Entry>> groups=new LinkedHashMap<>();for(KeyBindings.Entry e:visible)groups.computeIfAbsent(e.groupId(),k->new ArrayList<>()).add(e);
        double y=b.y()-c.scroll();Painter p=c.painter();p.save();try{p.clip(b,0);
            for(var group:groups.entrySet()){
                KeyBindings.Entry first=group.getValue().get(0);boolean collapsed=c.preferences().flag("keys.collapsed."+group.getKey(),group.getKey().startsWith("minecraft-keybinds."));
                boolean searching=!c.query().isBlank();Rect header=new Rect(b.x(),y,b.w(),36);p.rect(header,c.theme().radius(8),c.theme().chip());LibraryPaint.drawIcon(c,first.groupIcon()==null?"keyboard":first.groupIcon(),header.x()+12,y+9,18,c.theme().secondary());p.text(first.groupTitle(),header.x()+40,y+8,14,true,c.theme().text());
                String count="("+group.getValue().size()+")";p.text(count,header.x()+50+p.measure(first.groupTitle(),14,true),y+10,12,false,c.theme().secondary());
                double open=LibraryMotion.animate(c,"keys.group."+group.getKey(),collapsed&&!searching?0:1,.18,Motion.Easing.FAST_OUT_SLOW_IN);double ax=header.right()-22,ay=y+18;p.line(ax-4,ay+(open-.5)*5,ax,ay-(open-.5)*5,1.5,c.theme().secondary());p.line(ax,ay-(open-.5)*5,ax+4,ay+(open-.5)*5,1.5,c.theme().secondary());
                c.contentHit("keys.group."+group.getKey(),header,()->c.preferences().set("keys.collapsed."+group.getKey(),!collapsed),first.groupTitle());y+=44;
                if(collapsed&&!searching)continue;String category="";
                for(KeyBindings.Entry entry:group.getValue()){
                    String section=entry.category()+(entry.subcategory().isBlank()?"":" / "+entry.subcategory());if(!category.equals(section)&&!section.isBlank()){p.text(section,b.x()+4,y+4,12,false,c.theme().secondary());y+=28;category=section;}
                    Rect row=new Rect(b.x(),y,b.w(),entry.description().isBlank()?49:65);if(row.intersects(b))row(c,s,entry,row);y+=row.h()+8;
                }y+=11;
            }
            if(visible.isEmpty())LibraryPaint.empty(c,c.query().isBlank()?"No keybinds available.":"No keybinds match \""+c.query()+"\"",b);
        }finally{p.restore();}c.scrollRange(Math.max(0,y+c.scroll()-b.bottom()));
        if(!menu.isEmpty())menu(c,s,all);
        if(!s.status().isBlank())c.tooltip("keys.status",b,s.status());
        capturePointer(c);
    }
    private void row(PageContext c,KeyBindings s,KeyBindings.Entry entry,Rect row){
        Painter p=c.painter();p.rect(row,c.theme().radius(16),c.theme().card());p.text(LibraryPaint.ellipsis(p,entry.title(),row.w()-310,14,false),row.x()+14,row.y()+9,14,false,c.theme().text());
        if(!entry.description().isBlank())p.text(LibraryPaint.ellipsis(p,entry.description(),row.w()-310,11,false),row.x()+14,row.y()+32,11,false,c.theme().secondary());
        List<KeyBindings.Entry> conflicts=conflictMap.getOrDefault(entry.id(),List.of());boolean isRecording=entry.id().equals(recording);String value=isRecording?(recorder.any()?s.display(recorder.raw()):"Press keys..."):entry.display().isBlank()?"None":entry.display();
        double width=Math.min(row.w()*.48,Math.max(82,p.measure(value,13,false)+48));Rect button=new Rect(row.right()-width-14,row.y()+(row.h()-32)/2,width,32);
        KeyBindings.Entry display=new KeyBindings.Entry(entry.id(),entry.groupId(),entry.groupTitle(),entry.groupIcon(),entry.category(),entry.subcategory(),entry.title(),entry.description(),value,entry.current(),entry.defaults(),entry.singleKey(),entry.unavailable());
        bindingButton(c,display,button,!conflicts.isEmpty(),()->begin(entry),isRecording);
        if(!conflicts.isEmpty()&&!isRecording)c.tooltip("keys.conflicts."+entry.id(),button,"Conflicts with: "+String.join(", ",conflicts.stream().map(KeyBindings.Entry::title).toList()));
        if(entry.changed())LibraryPaint.icon(c,"keys.reset."+entry.id(),"undo",new Rect(button.x()-32,row.y()+(row.h()-24)/2,24,24),c.theme().secondary(),entry.editable(),()->LibraryPaint.run(c,"恢复默认按键",()->s.reset(entry.id())),"Reset to default");
        c.tooltip("keys.menuhint."+entry.id(),row,"右键按键可恢复默认或清除绑定");
        keyButtons.put(entry.id(),button);
    }
    private final Map<String,Rect> keyButtons=new HashMap<>();
    static void bindingButton(PageContext c,KeyBindings.Entry e,Rect r,boolean conflict,Runnable click){bindingButton(c,e,r,conflict,click,false);}
    private static void bindingButton(PageContext c,KeyBindings.Entry e,Rect r,boolean conflict,Runnable click,boolean recording){
        boolean hover=r.contains(c.mouseX(),c.mouseY());double a=LibraryMotion.animate(c,"keys.hover."+e.id(),hover?1:0,.2,Motion.Easing.SPRING);double rec=LibraryMotion.animate(c,"keys.record."+e.id(),recording?1:0,.2,Motion.Easing.SPRING);
        int base=Theme.mix(c.theme().component(),Theme.alpha(c.theme().component(),.8),a);int bg=Theme.mix(base,Theme.alpha(c.accent(),.2),rec);int edge=recording?c.accent():conflict?CONFLICT_COLOR:hover?Theme.alpha(c.theme().text(),.2):c.theme().border();int text=recording?c.accent():conflict?CONFLICT_COLOR:c.theme().text();
        if(!e.editable())text=Theme.alpha(text,.5);c.painter().rect(r,c.theme().radius(8),bg);c.painter().outline(r,c.theme().radius(8),1,edge);LibraryPaint.drawIcon(c,"keyboard",r.x()+12,r.y()+(r.h()-16)/2,16,text);
        c.painter().text(LibraryPaint.ellipsis(c.painter(),e.display().isBlank()?"None":e.display(),r.w()-48,13,false),r.x()+36,r.y()+(r.h()-17)/2,13,false,text);
        if(e.editable())c.contentHit("keys.bind."+e.id(),r,click,recording?"Esc / Backspace / Delete: clear binding":"Click to record a new key");else c.tooltip("keys.unavailable."+e.id(),r,e.unavailable());
    }
    private void menu(PageContext c,KeyBindings s,List<KeyBindings.Entry> all){
        KeyBindings.Entry entry=all.stream().filter(e->e.id().equals(menu)).findFirst().orElse(null);if(entry==null){menu="";return;}Rect anchor=menuAnchor==null?c.content():menuAnchor;Rect box=new Rect(Math.min(anchor.x(),c.content().right()-180),Math.min(anchor.bottom()+4,c.content().bottom()-78),180,78);c.hit("keys.menu.dismiss",c.shell(),()->menu="","");c.painter().rect(box,c.theme().radius(8),c.theme().popup());c.painter().outline(box,c.theme().radius(8),1,c.theme().border());
        LibraryPaint.button(c,"keys.menu.reset","Reset to default","undo",new Rect(box.x()+4,box.y()+4,172,33),false,entry.editable(),()->{LibraryPaint.run(c,"恢复默认按键",()->s.reset(entry.id()));menu="";},"");
        LibraryPaint.button(c,"keys.menu.clear","Clear binding","close",new Rect(box.x()+4,box.y()+41,172,33),false,entry.editable(),()->{LibraryPaint.run(c,"清除按键",()->s.set(entry.id(),KeyBindings.Chord.unbound()));menu="";},"");
    }
    private void readOnly(PageContext c,Rect b){
        String[][] names={{"打开音乐界面",c.host().playerKey()},{"编辑 HUD / 设置中心",c.host().editorKey()}};double y=b.y();c.painter().text("MuoniumPlayer",b.x(),y+8,16,true,c.theme().text());y+=46;
        for(String[] value:names){Rect row=new Rect(b.x(),y,b.w(),49);c.painter().rect(row,c.theme().radius(16),c.theme().card());c.painter().text(value[0],row.x()+14,row.y()+13,14,false,c.theme().text());String title=value[1].isBlank()?"None":value[1];LibraryPaint.center(c,title,row.right()-90,row.y()+14,13,false,c.theme().secondary());c.tooltip("keys.readonly."+y,row,"当前宿主仅提供实时显示名，尚未接入改键服务");y+=57;}
        LibraryPaint.status(c,"按键写入服务尚未接入",new Rect(b.x(),y+8,b.w(),18));c.scrollRange(0);
    }
    public boolean pointerDown(PageContext c,int button){
        if(recording()){if(button<0)return true;if(recordingEntry.singleKey())commit(c,KeyBindings.Chord.mouse(button));else recorder.mouse(button,true);return true;}
        if(button==1){KeyBindings s=service(c);if(s==null)return false;for(var entry:keyButtons.entrySet())if(entry.getValue().contains(c.mouseX(),c.mouseY())){menu=entry.getKey();menuAnchor=entry.getValue();return true;}}return false;
    }
    public void pointerUp(PageContext c,int button){if(recording()&&!recordingEntry.singleKey()){recorder.mouse(button,false);if(recorder.released())commit(c,recorder.chord());}}
    public boolean key(PageContext c,ConfigView.Key key,String typed,boolean control,boolean shift,boolean alt){
        if(!recording()){if(key==ConfigView.Key.ESCAPE&&!menu.isEmpty()){menu="";return true;}return false;}
        if(key==ConfigView.Key.ESCAPE||(key==ConfigView.Key.BACKSPACE||key==ConfigView.Key.DELETE)&&!recorder.any()){commit(c,KeyBindings.Chord.unbound());return true;}
        int code=c.nativeKeyCode();if(code<=0)return true;if(recordingEntry.singleKey())commit(c,KeyBindings.Chord.keyboard(code));else recorder.key(code,true);return true;
    }
    /** Optional event bridge; polling also supplies release completion for real host mappings. */
    public boolean keyReleased(PageContext c,int legacyCode){if(!recording()||recordingEntry.singleKey())return false;recorder.key(legacyCode,false);if(recorder.released())commit(c,recorder.chord());return true;}
    public void leave(PageContext c){cancel();menu="";keyButtons.clear();}
}
