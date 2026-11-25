package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.Presets;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.Predicate;

/** Template strip and its three focused views. The host owns all preset capture/apply/share operations. */
final class LibraryTemplates {
    static final double CARD_WIDTH=190, CARD_HEIGHT=176, GAP=19;
    private final TextEdit saveName=new TextEdit(),importText=new TextEdit();
    private boolean importing;
    private double horizontal,localScroll,pendingReturn=-1;
    private String confirmDelete="",lastPage="MAIN",validation="";
    private Rect strip;
    private LibraryServices.PresetExtras extras(PageContext c){return c.host() instanceof LibraryServices.PresetExtras e?e:null;}
    private boolean busy(PageContext c){LibraryServices.PresetExtras e=extras(c);return e!=null&&e.busy();}
    boolean focused(PageContext c){Presets s=c.host().presets();if(s!=null&&s.page().equals("APPLY"))importing=false;boolean focused=importing||s!=null&&!s.page().equals("MAIN");if(!focused&&!lastPage.equals("MAIN")){lastPage="MAIN";pendingReturn=localScroll;}return focused;}
    private void focus(PageContext c,Runnable open){localScroll=c.scroll();c.scroll(0);validation="";confirmDelete="";open.run();}
    private void back(PageContext c){importing=false;c.host().presets().main();pendingReturn=localScroll;validation="";saveName.focused=importText.focused=false;}
    void restoreIntegratedScroll(PageContext c){if(pendingReturn>=0){c.scroll(pendingReturn);pendingReturn=-1;}}
    void renderSection(PageContext c,Rect bounds){
        Presets service=c.host().presets();LibraryServices.PresetExtras extra=extras(c);Painter p=c.painter();
        p.text("模板预设",bounds.x(),bounds.y()+9,13,false,c.theme().secondary());
        if(service==null){p.text("安装 MuoniumPlayer 后，音乐外观与音效模板会显示在这里。",bounds.x(),bounds.y()+43,12,false,c.theme().secondary());strip=null;return;}
        String[] labels={"存为模板","导入模板","撤销套用","打开目录"},icons={"add","down","undo","folder"};double total=24;for(int n=0;n<labels.length;n++)total+=LibraryPaint.chipWidth(c,labels[n],icons[n]);double x=bounds.right()-total;
        for(int n=0;n<labels.length;n++){final int action=n;boolean enabled=n!=3||extra!=null&&extra.canOpenFolder();
            x+=LibraryPaint.chip(c,"templates.action."+n,labels[n],icons[n],x,bounds.y(),false,enabled&&!busy(c),()->{
                switch(action){case 0->focus(c,()->{importing=false;service.savePage();});case 1->focus(c,()->{service.main();importing=true;importText.text("");importText.focused=true;});case 2->{if(service.canUndo())LibraryPaint.run(c,"撤销套用",()->{service.undo();c.toast(service.status());});else c.toast("还没有可以撤销的套用");}case 3->LibraryPaint.run(c,"打开模板目录",extra::openPresetsFolder);}
            },enabled?"":"宿主尚未提供模板目录入口")+8;
        }
        strip=new Rect(bounds.x(),bounds.y()+43,bounds.w(),CARD_HEIGHT+8);
        List<Presets.Card> cards=service.cards().stream().filter(card->LibrarySearch.matches(c,card.name(),c.query().trim())||LibrarySearch.matches(c,card.description(),c.query().trim())).toList();
        double max=Math.max(0,cards.size()*(CARD_WIDTH+GAP)-GAP-strip.w());horizontal=Geometry.clamp(horizontal,0,max);
        Rect previous=c.content();c.content(strip);p.save();try{p.clip(strip,0);double px=strip.x()-horizontal;
            for(Presets.Card card:cards){Rect r=new Rect(px,strip.y(),CARD_WIDTH,CARD_HEIGHT);if(r.intersects(strip))templateCard(c,service,card,r);px+=CARD_WIDTH+GAP;}
            if(cards.isEmpty())p.text(c.query().isBlank()?"这个版本没有可用的模板":"没有匹配「"+c.query()+"」的模板",strip.x(),strip.y()+5,12,false,c.theme().secondary());
        }finally{p.restore();c.content(previous);}
        if(max>0){Rect track=new Rect(strip.x(),strip.bottom()-4,strip.w(),3);p.rect(track,2,Theme.alpha(c.theme().text(),.04));double thumb=Math.max(30,strip.w()*strip.w()/(max+strip.w()));Rect handle=new Rect(track.x()+(track.w()-thumb)*horizontal/max,track.y(),thumb,3);p.rect(handle,2,Theme.alpha(c.theme().secondary(),.55));
            c.hit("templates.scroll",track,()->{double start=c.mouseX(),value=horizontal;c.startDrag((mx,my)->horizontal=Geometry.clamp(value+(mx-start)*max/Math.max(1,track.w()-thumb),0,max),()->{});},"");}
        if(!service.status().isBlank())c.tooltip("templates.status",strip,service.status());
    }
    private void templateCard(PageContext c,Presets service,Presets.Card card,Rect r){
        boolean hover=r.contains(c.mouseX(),c.mouseY());double amount=LibraryMotion.animate(c,"templates.card.hover."+card.id(),hover?1:0,.2,Motion.Easing.SPRING);
        LibraryPaint.card(c,r,Theme.mix(c.theme().border(),c.accent(),amount));
        if(confirmDelete.equals(card.id())){
            double y=r.y()+53;for(String line:LibraryPaint.wrap(c.painter(),"删除后只能从分享码或文件找回，确认？",r.w()-24,11)){LibraryPaint.center(c,line,r.x()+r.w()/2,y,11,false,LibraryPaint.DANGER);y+=16;}
            LibraryPaint.icon(c,"templates.delete.confirm","tick",new Rect(r.x()+62,y+8,26,26),LibraryPaint.DANGER,!busy(c),()->{boolean completed=LibraryPaint.run(c,"删除模板",()->service.delete(card.id()));confirmDelete="";if(completed)c.toast(service.status());},"确认删除");
            LibraryPaint.icon(c,"templates.delete.cancel","close",new Rect(r.x()+102,y+8,26,26),c.theme().secondary(),true,()->confirmDelete="","取消");return;
        }
        c.contentHit("templates.card."+card.id(),r,()->focus(c,()->LibraryPaint.run(c,"打开模板",()->service.choose(card.id()))),card.description());
        Rect icon=new Rect(r.x()+69,r.y()+29,52,52);c.painter().rect(icon,c.theme().radius(14),Theme.alpha(card.builtin()?c.theme().text():c.accent(),card.builtin()?.06:.16));
        LibraryPaint.drawIcon(c,card.builtin()?"paintbrush":"profiles",icon.x()+14,icon.y()+14,24,card.builtin()?c.theme().secondary():c.accent());
        LibraryPaint.center(c,LibraryPaint.ellipsis(c.painter(),card.name(),166,14,false),r.x()+95,r.y()+88,14,false,c.theme().text());
        LibraryServices.PresetExtras extra=extras(c);String summary=extra==null?card.description():extra.cardSummary(card.id());if(summary.isBlank())summary=card.description();
        List<String> lines=LibraryPaint.wrap(c.painter(),summary,166,11);for(int n=0;n<Math.min(2,lines.size());n++)LibraryPaint.center(c,lines.get(n),r.x()+95,r.y()+112+n*14,11,false,c.theme().secondary());
        if(card.builtin())LibraryPaint.center(c,"内置",r.x()+95,r.y()+144,11,false,c.accent());
        else{
            LibraryPaint.icon(c,"templates.copy."+card.id(),"copy",new Rect(r.right()-67,r.y()+9,26,26),c.theme().secondary(),!busy(c),()->{if(LibraryPaint.run(c,"复制分享码",()->service.copy(card.id())))c.toast(service.status());},"复制分享码");
            LibraryPaint.icon(c,"templates.delete."+card.id(),"trash",new Rect(r.right()-35,r.y()+9,26,26),c.theme().secondary(),!busy(c),()->confirmDelete=card.id(),"删除模板");
        }
    }
    void renderFocus(PageContext c){
        Presets service=c.host().presets();if(service==null)return;
        String page=importing?"IMPORT":service.page();if(!lastPage.equals(page)){c.scroll(0);lastPage=page;validation="";}
        if(importing){renderImport(c,service);return;}if(page.equals("SAVE"))renderSave(c,service);else renderDetail(c,service);
    }
    private double heading(PageContext c,String title){Rect b=c.content();double x=b.x();x+=LibraryPaint.chip(c,"templates.back","返回","undo",x,b.y(),false,true,()->back(c),"")+8;c.painter().text(title,x,b.y()+8,15,false,c.theme().text());return x+c.painter().measure(title,15,false);}
    private void renderDetail(PageContext c,Presets service){
        Rect b=c.content();LibraryServices.PresetExtras extra=extras(c);heading(c,service.targetName());int changed=extra==null?-1:extra.changedKeys();String apply=changed>0?"套用（"+changed+" 项）":"套用";
        String[] labels={"全选","只选外观","只选音效",apply};double total=24;for(int n=0;n<4;n++)total+=LibraryPaint.chipWidth(c,labels[n],n==3?"tick":null);double x=b.right()-total;
        for(int n=0;n<4;n++){final int action=n;x+=LibraryPaint.chip(c,"templates.detail.action."+n,labels[n],n==3?"tick":null,x,b.y(),n==3,!busy(c),()->{
            if(action<3)select(service,facet->action==0||audio(facet)==(action==2));else{if(LibraryPaint.run(c,"套用模板",service::apply))c.toast(service.status());}
        },"")+8;}
        boolean selected=service.facets().stream().anyMatch(Presets.Facet::selected);String status=!selected?"先勾选要套用的部分":extra!=null&&!extra.previewOk()?"部分内容无法套用，详见下方":changed==0?"所选部分与你现在的配置一致，套用不会有变化":changed>0?"将改动 "+changed+" 项"+(service.preview().isEmpty()?"":" · 有 "+service.preview().size()+" 条提示")+" · 套用前会自动备份，可以撤销":"套用前会自动备份，可以撤销";
        c.painter().text(status,b.x(),b.y()+54,12,false,extra!=null&&!extra.previewOk()?LibraryPaint.DANGER:c.theme().secondary());
        renderFacets(c,service,new Rect(b.x(),b.y()+87,b.w(),b.h()-87),true);
    }
    private void renderSave(PageContext c,Presets service){
        Rect b=c.content();heading(c,"存为模板");String[] labels={"只选外观","只选音效","保存","保存并复制分享码"};double total=24;for(int n=0;n<4;n++)total+=LibraryPaint.chipWidth(c,labels[n],n==2?"tick":n==3?"copy":null);double x=b.right()-total;
        for(int n=0;n<4;n++){final int action=n;x+=LibraryPaint.chip(c,"templates.save.action."+n,labels[n],n==2?"tick":n==3?"copy":null,x,b.y(),n>=2,!busy(c),()->{if(action<2)select(service,facet->audio(facet)==(action==1));else save(c,service,action==3);},"")+8;}
        c.textField("templates.save.name",saveName,new Rect(b.x(),b.y()+52,b.w(),35),"给这套配置起个名字，比如「夜间沉浸」「直播用」",v->validation="");
        c.painter().text(validation.isEmpty()?"勾选要存进去的部分 · 账号、Cookie、音源与缓存永远不会进模板；只选外观或只选音效，就能单独分享那一部分":validation,b.x(),b.y()+106,11,false,validation.isEmpty()?c.theme().secondary():LibraryPaint.DANGER);
        renderFacets(c,service,new Rect(b.x(),b.y()+140,b.w(),b.h()-140),false);
    }
    private void save(PageContext c,Presets service,boolean copy){
        if(busy(c))return;if(saveName.text().trim().isEmpty()){validation="先起个名字";return;}if(service.facets().stream().noneMatch(Presets.Facet::selected)){validation="至少勾选一个部分";return;}
        if(!LibraryPaint.run(c,"保存模板",()->service.save(saveName.text(),copy)))return;if(service.page().equals("MAIN")){saveName.text("");c.toast(service.status());}else validation="模板未保存，请检查名称和所选部分";
    }
    private void renderImport(PageContext c,Presets service){
        Rect b=c.content();LibraryServices.PresetExtras extra=extras(c);heading(c,"导入模板");double fileW=LibraryPaint.chipWidth(c,"选择文件","folder"),textW=LibraryPaint.chipWidth(c,"导入输入内容","tick");
        LibraryPaint.chip(c,"templates.import.submit","导入输入内容","tick",b.right()-fileW-textW-8,b.y(),true,!busy(c),()->importValue(c,service),"");
        LibraryPaint.chip(c,"templates.import.file","选择文件","folder",b.right()-fileW,b.y(),false,extra!=null&&extra.canImportFile()&&!busy(c),()->LibraryPaint.run(c,"导入模板文件",extra::importPresetFile),extra!=null&&extra.canImportFile()?"":"宿主尚未提供模板文件选择入口");
        c.textField("templates.import.text",importText,new Rect(b.x(),b.y()+52,b.w(),35),"在这里粘贴分享码或 JSON · 回车导入",v->validation="");
        c.painter().text("导入只保存一张模板卡，不会立即改动设置；之后选择分面并明确点击「套用」才会生效。",b.x(),b.y()+106,11,false,c.theme().secondary());
        if(!validation.isEmpty())c.painter().text(validation,b.x(),b.y()+134,12,false,LibraryPaint.DANGER);else LibraryPaint.status(c,service.status(),new Rect(b.x(),b.y()+134,b.w(),18));c.scrollRange(0);
    }
    private void importValue(PageContext c,Presets service){if(busy(c))return;if(importText.text().isBlank()){validation="请粘贴分享码或 JSON";return;}if(!LibraryPaint.run(c,"导入模板",()->service.importText(importText.text())))return;if(service.page().equals("APPLY")){importing=false;importText.text("");importText.focused=false;}else validation=service.status();}
    private static boolean audio(Presets.Facet facet){return facet.group().equalsIgnoreCase("audio");}
    /** The source detail's look-only action includes opt-in look facets; save follows its current explicit selection. */
    static void select(Presets service,Predicate<Presets.Facet> selected){for(Presets.Facet facet:List.copyOf(service.facets()))if(facet.selected()!=selected.test(facet))service.toggle(facet.id());}
    private void renderFacets(PageContext c,Presets service,Rect viewport,boolean detail){
        LibraryServices.PresetExtras extra=extras(c);double y=viewport.y()-c.scroll();Painter p=c.painter();
        Rect previous=c.content();c.content(viewport);p.save();try{p.clip(viewport,0);
            for(boolean audio:new boolean[]{false,true}){
                List<Presets.Facet> facets=service.facets().stream().filter(facet->audio(facet)==audio).toList();if(facets.isEmpty())continue;
                p.text(audio?"音效":"外观",viewport.x(),y+4,13,false,c.theme().secondary());y+=31;
                for(Presets.Facet facet:facets){String description=extra==null?"":extra.facetDescription(facet.id());double height=description.isBlank()?37:55;Rect row=new Rect(viewport.x(),y,viewport.w(),height);
                    if(row.intersects(viewport)){
                        p.rect(row,c.theme().radius(16),c.theme().card());p.text(facet.name(),row.x()+14,row.y()+9,13,false,c.theme().text());if(!description.isBlank())p.text(LibraryPaint.ellipsis(p,description,row.w()-100,11,false),row.x()+14,row.y()+29,11,false,c.theme().secondary());
                        double amount=LibraryMotion.animate(c,"templates.facet."+service.page()+"."+facet.id(),facet.selected()?1:0,.15,Motion.Easing.FAST_OUT_SLOW_IN);Rect sw=new Rect(row.right()-56,row.y()+(row.h()-21)/2,42,21);String on=facet.selected()?"On":"Off";p.text(on,sw.x()-8-p.measure(on,12,false),row.y()+(row.h()-17)/2,12,false,c.theme().secondary());p.rect(sw,10.5,Theme.mix(c.theme().component(),c.accent(),amount));p.rect(new Rect(sw.x()+3+21*amount,sw.y()+3,15,15),7.5,c.theme().thumb());
                        if(!busy(c))c.contentHit("templates.facet."+facet.id(),row,()->LibraryPaint.run(c,"选择模板部分",()->service.toggle(facet.id())),description);
                    }y+=height+8;
                }
            }
            if(detail){List<String> notes=service.preview();if(!notes.isEmpty()){p.text("兼容性与资源提示",viewport.x(),y+4,13,false,c.theme().secondary());y+=31;for(String note:notes.stream().limit(5).toList())y=LibraryPaint.paragraph(c,"· "+note,viewport.x(),y,viewport.w(),12,c.theme().secondary())+8;}
                y=LibraryPaint.paragraph(c,"布局位置与画质性能默认不勾（和屏幕、机器有关）；字体文件不随分享，冷字体策略下字体变更需重启游戏。",viewport.x(),y,viewport.w(),11,c.theme().secondary())+8;}
        }finally{p.restore();c.content(previous);}c.scrollRange(Math.max(0,y+c.scroll()-viewport.bottom()));
    }
    boolean wheel(PageContext c,double delta){if(!focused(c)&&strip!=null&&strip.contains(c.mouseX(),c.mouseY())){horizontal=Math.max(0,horizontal-delta*60);return true;}return false;}
    boolean pointerDown(PageContext c,int button){return false;}
    boolean key(PageContext c,ConfigView.Key key){if(key==ConfigView.Key.ESCAPE){back(c);return true;}if(key==ConfigView.Key.ENTER){Presets s=c.host().presets();if(importing&&importText.focused){importValue(c,s);return true;}if(s.page().equals("SAVE")&&saveName.focused){save(c,s,false);return true;}}return false;}
    void leave(PageContext c){saveName.focused=importText.focused=false;confirmDelete="";}
}
