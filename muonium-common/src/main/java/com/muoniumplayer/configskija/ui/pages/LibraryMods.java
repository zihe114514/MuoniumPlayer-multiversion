package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;

/** Four-column mod grid, including grouping, persistent favorite slots and constrained reordering. */
public final class LibraryMods implements UiPage {
    public record Card(String id, String title, String description, String icon, String category,
                       String type, String typeTitle, HudElement preview, Supplier<Boolean> enabled,
                       Consumer<Boolean> toggle, Consumer<PageContext> open, List<String> tags) {
        public Card { tags=List.copyOf(tags); }
        public Card(String id,String title,String description,String icon,String category,String type,String typeTitle,HudElement preview,Supplier<Boolean> enabled,Consumer<Boolean> toggle,Consumer<PageContext> open){this(id,title,description,icon,category,type,typeTitle,preview,enabled,toggle,open,List.of());}
    }
    private String category="all", dragging="";
    private List<Card> live=List.of(), ordered=List.of();
    private final Map<String,Rect> slots=new LinkedHashMap<>();
    private double downX,downY,dragX,dragY,offsetX,offsetY;
    private boolean moved;
    private Card dragCard;
    private Rect dragOrigin, gridBounds;
    private CardRenderer searchRenderer;
    public static final double CARD_HEIGHT=140, FOOTER_HEIGHT=36, GAP=19;
    public static double cardWidth(double contentWidth) { return (contentWidth-3*GAP)/4; }

    public static List<Card> cards(PageContext c) {
        if(c.host() instanceof LibraryServices.ModCatalog catalog) return List.copyOf(catalog.modCards());
        List<Card> cards=new ArrayList<>();
        if(!c.host().categories().isEmpty())cards.add(new Card("muonium.hud","MuoniumPlayer","MuoniumPlayer 的 HUD、歌词、灵动岛与播放器外观设置。","hud","hud","","",null,null,null,
                ctx->ctx.navigate(new Navigation.Route(Navigation.Page.SETTINGS))));
        if(c.host().favoriteCategories().stream().anyMatch(category->category.id().equals("muonium.favorites")))cards.add(new Card("muonium.favorites","常用","","star","qol","","",null,null,null,ctx->ctx.navigate(new Navigation.Route(Navigation.Page.FAVORITES))));
        for(HudElement hud:c.host().huds()) cards.add(new Card("hud:"+hud.id(),hud.title().endsWith("HUD")?hud.title():hud.title()+" HUD",
                "","hud","hud","huds","HUDs",hud,null,null,ctx->ctx.navigate(new Navigation.Route(Navigation.Page.HUD,hud.id()))));
        return List.copyOf(cards);
    }
    /** Instance aliases for result renderers which already retain a LibraryMods owner. */
    public List<Card> catalog(PageContext context){return cards(context);}
    public void drawSearchCard(PageContext context,Card card,Rect bounds){if(searchRenderer==null)searchRenderer=cardRenderer();searchRenderer.draw(context,card,bounds);}
    /** Reuses the real grid card, retaining its hit IDs, live toggle, favorite, hide and tooltip behavior. */
    public interface CardRenderer {
        double cardHeight();
        Optional<Card> fromDocument(PageContext context,SearchIndex.Document<?> document);
        void draw(PageContext context,Card card,Rect bounds);
        default boolean drawDocument(PageContext context,SearchIndex.Document<?> document,Rect bounds){
            Optional<Card> card=fromDocument(context,document);if(card.isEmpty())return false;draw(context,card.get(),bounds);return true;
        }
        void leave(PageContext context);
    }
    public static CardRenderer cardRenderer(){return new DocumentCards();}
    private static final class DocumentCards implements CardRenderer {
        private final LibraryMods painter=new LibraryMods();
        private long generation;
        public double cardHeight(){return CARD_HEIGHT;}
        public Optional<Card> fromDocument(PageContext c,SearchIndex.Document<?> document){
            if(document==null||!document.scopes().contains(SearchIndex.Scope.MODS))return Optional.empty();
            List<Card> current=cards(c);Object payload=document.payload();
            LinkedHashSet<String> ids=new LinkedHashSet<>();
            if(payload instanceof Card card)ids.add(card.id());
            if(payload instanceof SearchDocuments.Owner owner)ids.add(owner.id());
            if(payload instanceof HudElement hud)ids.add("hud:"+hud.id());
            if(document.metadata().id()!=null)ids.add(document.metadata().id());
            if(document.id().startsWith("mod::"))ids.add(document.id().substring(5));
            for(String id:ids)for(Card card:current)if(card.id().equals(id))return Optional.of(card);
            // The old settings corpus used the mod id for the one real muonium.hud settings tree.
            if(ids.contains("muonium"))for(Card card:current)if(card.id().equals("muonium.hud"))return Optional.of(card);
            if(payload instanceof Card card)return Optional.of(card);
            return Optional.empty();
        }
        public void draw(PageContext c,Card card,Rect bounds){
            Objects.requireNonNull(card,"card");Objects.requireNonNull(bounds,"bounds");
            if(Double.compare(bounds.h(),CARD_HEIGHT)!=0)throw new IllegalArgumentException("ModCard height must match source 140dp");
            painter.drawCard(c,card,bounds,true,painter.favorite(c,card),()->{
                long ticket=generation;double x=c.mouseX(),y=c.mouseY();boolean[] moved={false};
                c.startDrag((mx,my)->moved[0]|=Math.hypot(mx-x,my-y)>=5,()->{
                    if(ticket==generation&&!moved[0]&&bounds.contains(c.mouseX(),c.mouseY()))card.open().accept(c);
                });
            });
        }
        public void leave(PageContext c){generation++;painter.cancel();}
    }
    static List<String> storedOrder(UiPreferences prefs) {
        String raw=prefs.get("mods.order","");return raw.isEmpty()?List.of():Arrays.stream(raw.split("\n")).filter(s->!s.isBlank()).distinct().toList();
    }
    static Comparator<Card> comparator(UiPreferences prefs) {
        List<String> order=storedOrder(prefs); boolean alphabetical=prefs.get("mods.sort","custom").equals("alphabetical");
        return Comparator.comparingInt((Card card)->card.type().isEmpty()?-1:0)
                .thenComparing(Card::type).thenComparing(card->!prefs.flag("mods.favorite."+card.id(),false))
                .thenComparingInt(card->alphabetical?0:order.contains(card.id())?order.indexOf(card.id()):Integer.MIN_VALUE)
                .thenComparingInt(card->card.preview()==null?0:1).thenComparing(Card::title,String.CASE_INSENSITIVE_ORDER).thenComparing(Card::id);
    }
    static void reorder(UiPreferences prefs,List<String> visible,List<String> all) {
        List<String> stored=new ArrayList<>(storedOrder(prefs));List<String> unknown=all.stream().filter(id->!stored.contains(id)).toList();stored.addAll(0,unknown);
        Set<String> unique=new HashSet<>(visible);if(unique.size()!=visible.size()) throw new IllegalArgumentException("重复模组 ID");
        List<Integer> indexes=new ArrayList<>();for(int i=0;i<stored.size();i++) if(unique.contains(stored.get(i))) indexes.add(i);
        if(indexes.size()!=visible.size()) throw new IllegalArgumentException("排序槽位与模组数量不一致");
        for(int i=0;i<indexes.size();i++) stored.set(indexes.get(i),visible.get(i));prefs.set("mods.order",String.join("\n",stored));
    }
    private boolean favorite(PageContext c,Card card) { return c.preferences().flag("mods.favorite."+card.id(),false); }
    private boolean hidden(PageContext c,Card card) { return c.preferences().flag("mods.hidden."+card.id(),false); }
    private void reset(PageContext c,String next) { category=category.equals(next)?"all":next;c.scroll(0);cancel(); }
    public void render(PageContext c) {
        Rect body=c.content(); Painter p=c.painter();Theme t=c.theme();live=cards(c);
        double x=body.x(), y=body.y();
        String[][] cats={{"all","All mods",""},{"favorites","Favorites","star"},{"performance","Performance","lightning-01"},{"visuals","Visuals","paintbrush"},{"hud","HUD","hud"},{"utility","Utility","settings"},{"qol","Quality of Life","qol"},{"other","Other",""},{"hidden","Hidden","eye-off"}};
        for(String[] cat:cats) {
            boolean shown=cat[0].equals("all")||cat[0].equals("favorites")||category.equals(cat[0])||live.stream().anyMatch(card->cat[0].equals("hidden")?hidden(c,card):!hidden(c,card)&&card.category().equals(cat[0]));
            if(!shown) continue;
            x+=LibraryPaint.chip(c,"mods.category."+cat[0],cat[1],cat[2].isEmpty()?null:cat[2],x,y,category.equals(cat[0]),true,()->reset(c,cat[0]),"")+8;
        }
        Rect sort=new Rect(body.right()-90,y,90,36);p.rect(sort,t.radius(8),t.component());p.outline(sort,t.radius(8),1,t.border());
        boolean alpha=c.preferences().get("mods.sort","custom").equals("alphabetical");
        for(int n=0;n<2;n++) { final String mode=n==0?"custom":"alphabetical";Rect r=new Rect(sort.x()+3+n*43,y+3,41,30);
            if((n==1)==alpha) p.rect(r,t.radius(3),Theme.alpha(t.text(),.08));
            LibraryPaint.icon(c,"mods.sort."+mode,n==0?"dots-grid":"capitalized",r,(n==1)==alpha?t.text():t.secondary(),true,()->{c.preferences().set("mods.sort",mode);cancel();},n==0?"Custom order":"A to Z"); }
        Rect grid=new Rect(body.x(),body.y()+52,body.w(),Math.max(0,body.h()-52));
        gridBounds=grid;
        List<Card> filtered=live.stream().filter(card->hidden(c,card)==category.equals("hidden"))
                .filter(card->!category.equals("favorites")||favorite(c,card))
                .filter(card->Set.of("all","favorites","hidden").contains(category)||category.equals(card.category()))
                .filter(card->LibrarySearch.matches(c,card.title(),c.query())||card.tags().stream().anyMatch(tag->LibrarySearch.matches(c,tag,c.query()))).sorted(comparator(c.preferences())).toList();
        if(dragCard==null) ordered=filtered;
        else if(filtered.stream().noneMatch(card->card.id().equals(dragging))) cancel();
        slots.clear();double cy=grid.y()-c.scroll(), cw=cardWidth(grid.w());int column=0;String lastType=null;
        c.content(grid);p.save();try { p.clip(grid,0);
            for(Card card:ordered) {
                if(!Objects.equals(lastType,card.type())) {
                    if(column>0) { cy+=CARD_HEIGHT+GAP;column=0; }
                    lastType=card.type();
                    if(!card.type().isEmpty()) { header(c,card,new Rect(grid.x(),cy,grid.w(),18));cy+=37; }
                }
                if(!card.type().isEmpty()&&c.preferences().flag("mods.collapsed."+card.type(),false)) continue;
                Rect slot=new Rect(grid.x()+column*(cw+GAP),cy,cw,CARD_HEIGHT);slots.put(card.id(),slot);
                double animatedY=LibraryMotion.spring(c,"mods.place.y."+card.id(),slot.y()+c.scroll(),400,1)-c.scroll();
                double animatedX=LibraryMotion.spring(c,"mods.place.x."+card.id(),slot.x(),400,1);
                Rect displayed=new Rect(animatedX,animatedY,cw,CARD_HEIGHT);
                if(slot.intersects(grid)) {
                    if(card.id().equals(dragging)&&moved) { LibraryPaint.card(c,slot,Theme.alpha(t.secondary(),.35));dashes(c,slot,favoriteAt(c,ordered,ordered.indexOf(card))?t.favorite():t.secondary()); }
                    else drawCard(c,card,displayed,true,favorite(c,card));
                }
                if(++column==4) { column=0;cy+=CARD_HEIGHT+GAP; }
            }
            if(ordered.isEmpty()) LibraryPaint.empty(c,category.equals("hidden")?"No hidden mods.":category.equals("favorites")?"No favorite mods.":c.query().isBlank()?"No mods.":"No mods match \""+c.query()+"\"",grid);
        }finally {p.restore();c.content(body);}
        double bottom=cy+(column>0?CARD_HEIGHT:0)+c.scroll();c.scrollRange(Math.max(0,bottom-grid.bottom()));
        if(dragCard!=null&&moved) drawCard(c,dragCard,new Rect(dragX-offsetX,dragY-offsetY,cw,CARD_HEIGHT),false,favoriteAt(c,ordered,ordered.indexOf(dragCard)));
    }
    private void header(PageContext c,Card card,Rect r) {
        Painter p=c.painter();boolean collapsed=c.preferences().flag("mods.collapsed."+card.type(),false);
        long count=ordered.stream().filter(it->it.type().equals(card.type())).count();String title=card.typeTitle()+(collapsed?" ("+count+")":"");
        double w=p.measure(title,11,true)+50,cx=r.x()+r.w()/2;
        p.line(r.x(),r.y()+9,cx-w/2-10,r.y()+9,1,c.theme().border());p.line(cx+w/2+10,r.y()+9,r.right(),r.y()+9,1,c.theme().border());
        LibraryPaint.drawIcon(c,"hud",cx-w/2,r.y()+2,14,c.theme().secondary());p.text(title,cx-w/2+24,r.y()+1,11,true,c.theme().secondary());
        double turn=LibraryMotion.animate(c,"mods.chevron."+card.type(),collapsed?0:1,.2,Motion.Easing.SPRING);
        double ax=cx+w/2-8,ay=r.y()+9;p.line(ax-3,ay+(turn-.5)*4,ax,ay-(turn-.5)*4,1.4,c.theme().secondary());p.line(ax,ay-(turn-.5)*4,ax+3,ay+(turn-.5)*4,1.4,c.theme().secondary());
        c.contentHit("mods.group."+card.type(),r,()->{c.preferences().set("mods.collapsed."+card.type(),!collapsed);cancel();},title);
    }
    private void drawCard(PageContext c,Card card,Rect r,boolean interactive,boolean fav) {
        drawCard(c,card,r,interactive,fav,()->beginDrag(c,card,r));
    }
    private void drawCard(PageContext c,Card card,Rect r,boolean interactive,boolean fav,Runnable press) {
        Painter p=c.painter();Theme t=c.theme();boolean hovered=r.contains(c.mouseX(),c.mouseY());LibraryPaint.card(c,r,t.border());
        p.save();try {p.clip(r,t.radius(16));
            Rect top=new Rect(r.x(),r.y(),r.w(),CARD_HEIGHT-FOOTER_HEIGHT);
            if(card.preview()!=null&&!card.preview().hidden()) {
                HudElement hud=card.preview();double s=Math.min((r.w()-24)/Math.max(1,hud.width()),88/Math.max(1,hud.height()));
                p.save();try {p.clip(new Rect(r.x()+12,r.y()+8,r.w()-24,88),0);p.translate(r.x()+(r.w()-hud.width()*s)/2,r.y()+8+(88-hud.height()*s)/2);p.scale(s,s);hud.preview(0,0);}finally{p.restore();}
            } else if(card.icon()!=null&&!card.icon().isBlank()) LibraryPaint.drawIcon(c,card.icon(),top.x()+(top.w()-48)/2,top.y()+28,48,t.text());
            else LibraryPaint.center(c,LibraryPaint.ellipsis(p,card.title(),r.w()-24,16,true),r.x()+r.w()/2,r.y()+48,16,true,t.text());
            boolean enabled=card.enabled()!=null&&Boolean.TRUE.equals(card.enabled().get());double amount=LibraryMotion.animate(c,"mods.enabled."+card.id(),enabled?1:0,.2,Motion.Easing.SPRING);
            Rect footer=new Rect(r.x(),r.bottom()-FOOTER_HEIGHT,r.w(),FOOTER_HEIGHT);p.rect(footer,0,Theme.alpha(t.text(),.08*(1-amount)));
            if(amount>.001) p.rect(footer,0,Theme.alpha(c.accent(),amount));
            LibraryPaint.center(c,LibraryPaint.ellipsis(p,card.title(),r.w()-16,16,false),r.x()+r.w()/2,footer.y()+10,16,false,Theme.mix(Theme.alpha(t.text(),.75),t.accentText(),amount));
            if(interactive) {
                c.contentHit("mods.card."+card.id(),r,press,card.description());
                if(card.toggle()!=null) c.contentHit("mods.toggle."+card.id(),footer,()->LibraryPaint.run(c,"切换模组",()->{card.toggle().accept(!enabled);c.host().persist();}),enabled?"Enabled":"Disabled");
            }
            double starAlpha=LibraryMotion.animate(c,"mods.star."+card.id(),fav?1:hovered?.6:0,.18,Motion.Easing.SPRING);
            Rect star=new Rect(r.x()+4,r.y()+4,24,24);if(star.contains(c.mouseX(),c.mouseY())) starAlpha=1;
            if(starAlpha>.001) LibraryPaint.drawIcon(c,fav?"star-filled":"star",star.x()+3,star.y()+3,18,Theme.alpha(fav?t.favorite():t.text(),starAlpha));
            if(interactive) c.contentHit("mods.favorite."+card.id(),star,()->c.preferences().set("mods.favorite."+card.id(),!fav),fav?"Remove from favorites":"Favorite");
            boolean hide=hidden(c,card);Rect eye=new Rect(r.x()+28,r.y()+4,24,24);double eyeAlpha=LibraryMotion.animate(c,"mods.eye."+card.id(),hide||eye.contains(c.mouseX(),c.mouseY())?1:hovered?.6:0,.18,Motion.Easing.SPRING);
            if(eyeAlpha>.001) LibraryPaint.drawIcon(c,hide?"eye-off":"eye",eye.x()+3,eye.y()+3,18,Theme.alpha(t.text(),eyeAlpha));
            if(interactive) c.contentHit("mods.hidden."+card.id(),eye,()->{c.preferences().set("mods.hidden."+card.id(),!hide);cancel();},hide?"Show in mod list":"Hide from mod list");
            Rect dot=new Rect(r.right()-23,r.y()+11,12,12);if(enabled) p.glow(r,dot.x()+6,dot.y()+6,11,c.accent(),.41*amount);
            p.rect(dot,6,card.toggle()==null?0x804F505B:Theme.mix(c.accent(),0xFF181600,.42));p.rect(dot.inset(3),3,card.toggle()==null?0xFF4F505B:c.accent());
            if(interactive)c.tooltip("mods.state."+card.id(),new Rect(r.right()-28,r.y()+6,24,24),card.toggle()==null?"Toggle unavailable":enabled?"Enabled":"Disabled");
        } finally {p.restore();}
    }
    private void dashes(PageContext c,Rect r,int color) {for(double x=r.x()+8;x<r.right()-8;x+=10){c.painter().line(x,r.y()+1,Math.min(x+5,r.right()-8),r.y()+1,1,color);c.painter().line(x,r.bottom()-1,Math.min(x+5,r.right()-8),r.bottom()-1,1,color);}for(double y=r.y()+8;y<r.bottom()-8;y+=10){c.painter().line(r.x()+1,y,r.x()+1,Math.min(y+5,r.bottom()-8),1,color);c.painter().line(r.right()-1,y,r.right()-1,Math.min(y+5,r.bottom()-8),1,color);}}
    public boolean pointerDown(PageContext c,int button) {
        if(button!=0||gridBounds==null||!gridBounds.contains(c.mouseX(),c.mouseY()))return false;
        for(Card card:ordered) {Rect r=slots.get(card.id());if(r==null||!r.contains(c.mouseX(),c.mouseY()))continue;
            if(c.mouseX()<r.x()+52&&c.mouseY()<r.y()+28||c.mouseY()>=r.bottom()-FOOTER_HEIGHT&&card.toggle()!=null)return false;
            beginDrag(c,card,r);return true;
        }return false;
    }
    private void beginDrag(PageContext c,Card card,Rect r){
        dragging=card.id();dragCard=card;dragOrigin=r;downX=dragX=c.mouseX();downY=dragY=c.mouseY();offsetX=downX-r.x();offsetY=downY-r.y();moved=false;
        c.startDrag((mx,my)->drag(c,mx,my),()->drop(c));
    }
    private void drag(PageContext c,double mx,double my) {
        if(dragCard==null)return;dragX=mx;dragY=my;if(!moved&&Math.hypot(mx-downX,my-downY)<5)return;moved=true;
        double edge=c.content().y()+52;if(my<edge+28)c.scroll(Math.max(0,c.scroll()-12));else if(my>c.content().bottom()-28)c.scroll(c.scroll()+12);
        Card nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(Card card:ordered) {if(!card.type().equals(dragCard.type()))continue;Rect r=slots.get(card.id());if(r==null)continue;double d=Math.hypot(mx-r.x()-r.w()/2,my-r.y()-r.h()/2);if(d<distance){distance=d;nearest=card;}}
        if(nearest!=null&&!nearest.id().equals(dragging)){List<Card> next=new ArrayList<>(ordered);int to=next.indexOf(nearest);next.remove(dragCard);next.add(Math.min(to,next.size()),dragCard);ordered=List.copyOf(next);}
    }
    static boolean favoriteAt(PageContext c,List<Card> cards,int index) {
        if(index<0||index>=cards.size())return false;Card card=cards.get(index);int first=index,last=index;
        while(first>0&&cards.get(first-1).type().equals(card.type()))first--;while(last+1<cards.size()&&cards.get(last+1).type().equals(card.type()))last++;
        int boundary=first;for(int i=first;i<=last;i++)if(i!=index&&c.preferences().flag("mods.favorite."+cards.get(i).id(),false))boundary++;
        return index<boundary||index==boundary&&c.preferences().flag("mods.favorite."+card.id(),false);
    }
    private void drop(PageContext c) {
        if(dragCard==null)return;Card card=dragCard;
        if(!moved){cancel();card.open().accept(c);return;}
        c.preferences().set("mods.favorite."+card.id(),favoriteAt(c,ordered,ordered.indexOf(card)));
        if(!c.preferences().get("mods.sort","custom").equals("alphabetical")) reorder(c.preferences(),ordered.stream().filter(it->it.type().equals(card.type())).map(Card::id).toList(),live.stream().sorted(comparator(c.preferences())).map(Card::id).toList());
        cancel();c.sound("drag-end");
    }
    private void cancel(){dragging="";dragCard=null;dragOrigin=null;moved=false;}
    public void leave(PageContext c){cancel();if(searchRenderer!=null)searchRenderer.leave(c);}
}
