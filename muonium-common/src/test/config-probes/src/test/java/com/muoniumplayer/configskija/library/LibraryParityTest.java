package com.muoniumplayer.configskija.library;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.pages.*;
import com.muoniumplayer.configskija.host.keys.*;
import java.util.*;
import java.util.function.*;

/** Standalone JDK-only interaction tests: no Minecraft/GL, Gradle, disk profile or account state. */
public final class LibraryParityTest {
    private static int assertions;
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static void near(double value,double expected,String message){check(Math.abs(value-expected)<.001,message+": "+value+" != "+expected);}
    public static void main(String[] args){geometry();mods();documentCards();profiles();templates();keys();recording();credits();nativeAdapter();shellIntegration();System.out.println("LibraryParityTest: "+assertions+" assertions passed");}
    private static void geometry(){
        near(LibraryMods.cardWidth(1077),255,"four equal columns and three 19dp gaps");
        near(LibraryProfiles.cardWidth(1077),200.2,"five equal profile columns and four 19dp gaps");
        near(LibraryMods.CARD_HEIGHT,140,"source mod card height");near(LibraryMods.FOOTER_HEIGHT,36,"source footer height");near(LibraryProfiles.CARD_HEIGHT,180,"source profile card height");
        check(LibraryProfiles.nextCloneName("Night",List.of("night copy","NIGHT COPY 2")).equals("Night copy 3"),"clone names are case-insensitive");
        check(!LibraryProfiles.nameError("../escape",List.of(),null).isEmpty(),"profile traversal name rejected");
        check(!LibraryProfiles.nameError(" ",List.of(),null).isEmpty(),"blank profile rejected");
        check(!LibraryProfiles.nameError("a".repeat(81),List.of(),null).isEmpty(),"host 80 character bound");
    }
    private static void mods(){
        FakeHost host=new FakeHost();LibraryTestContext c=new LibraryTestContext(host);UiPage page=LibraryPages.mods();c.frame(page);
        Rect first=c.rect("mods.card.muonium.hud");near(first.x(),289,"first card x");near(first.y(),140,"33dp chip plus 19dp gap");near(first.w(),255,"mod card width");near(first.h(),140,"mod card height");
        check(c.hits.keySet().stream().noneMatch(id->id.contains("settings-category")),"setting categories are not fake mods");
        check(c.hits.containsKey("mods.card.hud:muonium.lyrics"),"registered HUD receives its own card");
        c.click("mods.favorite.muonium.hud");c.click("mods.category.favorites");c.frame(page);check(c.hits.containsKey("mods.card.muonium.hud"),"favorite filter retains pinned mod");check(!c.hits.containsKey("mods.card.hud:muonium.lyrics"),"favorite filter excludes other mods");
        c.click("mods.hidden.muonium.hud");c.frame(page);check(!c.hits.containsKey("mods.card.muonium.hud"),"hidden favorite is removed from favorites");check(c.hits.containsKey("mods.category.hidden"),"hidden category appears when needed");
        c.click("mods.category.hidden");c.frame(page);check(c.hits.containsKey("mods.card.muonium.hud"),"hidden category exposes restore action");c.click("mods.hidden.muonium.hud");c.click("mods.category.all");c.frame(page);
        c.click("mods.group.huds");c.frame(page);check(!c.hits.containsKey("mods.card.hud:muonium.lyrics"),"collapsed type hides its cards");c.click("mods.group.huds");
        c.query="外观";c.frame(page);check(!c.hits.containsKey("mods.card.muonium.hud"),"mod search excludes description per source scope");check(!c.hits.containsKey("mods.card.hud:muonium.lyrics"),"search excludes unrelated HUDs");
        c.query="";c.frame(page);c.move(c.rect("mods.card.muonium.hud"));c.click("mods.card.muonium.hud");c.finish.run();check(c.route.page()==Navigation.Page.SETTINGS,"main card opens real settings");
        FakeCatalog catalog=new FakeCatalog();LibraryTestContext d=new LibraryTestContext(catalog);UiPage drag=LibraryPages.mods();d.frame(drag);Rect a=d.rect("mods.card.a"),b=d.rect("mods.card.b");d.move(a);check(drag.pointerDown(d,0),"mod card captures drag");d.drag.accept(b.x()+b.w()/2,b.y()+b.h()/2);d.finish.run();d.frame(drag);check(d.rect("mods.card.b").x()<d.rect("mods.card.a").x(),"drop persists custom order");check(d.prefs.get("mods.order","").contains("a"),"order saved to UI preferences");
        String stored=d.prefs.get("mods.order","");d.click("mods.sort.alphabetical");d.frame(drag);check(d.rect("mods.card.a").x()<d.rect("mods.card.b").x(),"alphabetical mode reorders by title");check(d.prefs.get("mods.order","").equals(stored),"alphabetical mode preserves custom arrangement");
        d.scroll=400;d.frame(drag);check(d.hits.keySet().stream().noneMatch(id->id.startsWith("mods.card.")&&d.rect(id).y()<140),"scrolled cards cannot intercept filter controls");
    }
    private static void documentCards(){
        FakeHost host=new FakeHost();LibraryTestContext c=new LibraryTestContext(host);LibraryMods.CardRenderer renderer=LibraryPages.modCardRenderer();
        check(renderer.cardHeight()==140,"search card height matches Mods.kt source");
        LibraryTestContext registered=new LibraryTestContext(new FakeCatalog());SearchIndex.Document<?> registryDoc=SearchDocuments.mod("a","Old name","",null,null,List.of(),new SearchDocuments.Owner("a","Old name"));check(renderer.fromDocument(registered,registryDoc).orElseThrow().title().equals("Alpha"),"host ModCatalog is authoritative for old Owner payloads");
        SearchIndex.Document<?> legacy=SearchDocuments.mod("muonium","MuoniumPlayer","",null,null,List.of(),new SearchDocuments.Owner("muonium","MuoniumPlayer"));
        FakeHost favoritesHost=new FakeHost(){public List<Category> favoriteCategories(){return List.of(new Category("muonium.favorites","HUD 叠加层","","star",List.of()));}};LibraryTestContext favoritesContext=new LibraryTestContext(favoritesHost);SearchIndex.Document<?> favoritesDocument=SearchDocuments.mod("muonium.favorites","常用","",null,null,List.of(),new SearchDocuments.Owner("muonium.favorites","常用"));LibraryMods.Card favoriteCard=renderer.fromDocument(favoritesContext,favoritesDocument).orElseThrow();check(favoriteCard.category().equals("qol")&&favoriteCard.icon().equals("star"),"global search resolves real FavoritesCard metadata from the existing favorite category");favoriteCard.open().accept(favoritesContext);check(favoritesContext.route.page()==Navigation.Page.FAVORITES,"search FavoritesCard routes to dedicated favorites settings");check(LibraryMods.cards(favoritesContext).stream().filter(card->card.preview()!=null).count()==2,"Favorites registration retains both real host HUD cards");check(LibraryMods.cards(favoritesContext).stream().noneMatch(card->card.id().equals("oneconfig.builtin")),"original hidden OneConfig builtin is not synthesized as visible mod");
        LibraryMods.Card resolved=renderer.fromDocument(c,legacy).orElseThrow();check(resolved.id().equals("muonium.hud"),"old Owner mod id resolves to real settings registry card");
        SearchIndex.Document<?> byId=SearchDocuments.mod("muonium.hud","stale label","",null,null,List.of(),new Object());check(renderer.fromDocument(c,byId).orElseThrow().title().equals("MuoniumPlayer"),"metadata id uses current registered card");
        SearchIndex.Document<?> hud=SearchDocuments.mod("legacy.hud","","",null,null,List.of(),host.huds().get(0));check(renderer.fromDocument(c,hud).orElseThrow().id().equals("hud:muonium.lyrics"),"real HUD payload maps to registered preview card");
        SearchIndex.Document<?> unsupported=SearchDocuments.mod("unknown","MuoniumPlayer","",null,null,List.of(),new Object());check(renderer.fromDocument(c,unsupported).isEmpty(),"equal title cannot fabricate an unknown mod card");
        SearchIndex.Document<?> option=new SearchIndex.Document<>("mod::muonium.hud",Set.of(SearchIndex.Scope.OPTIONS),SearchIndex.Metadata.title("MuoniumPlayer"),resolved);check(renderer.fromDocument(c,option).isEmpty(),"card renderer does not turn option documents into mods");
        Rect bounds=new Rect(300,150,255,140);check(renderer.drawDocument(c,legacy,bounds),"legacy document draws through shared renderer");check(c.hits.containsKey("mods.card.muonium.hud")&&c.hits.containsKey("mods.favorite.muonium.hud")&&c.hits.containsKey("mods.hidden.muonium.hud"),"search retains original card action IDs");
        c.move(bounds);c.click("mods.card.muonium.hud");check(c.route.page()==Navigation.Page.MODS,"search card opens on release");c.finish.run();check(c.route.page()==Navigation.Page.SETTINGS,"search click opens real settings route");
        c.click("mods.favorite.muonium.hud");check(c.prefs.flag("mods.favorite.muonium.hud",false),"search favorite writes the same preference");c.click("mods.hidden.muonium.hud");check(c.prefs.flag("mods.hidden.muonium.hud",false),"search hide writes the same preference");
        c.route=new Navigation.Route(Navigation.Page.MODS);renderer.draw(c,resolved,bounds);c.move(bounds);c.click("mods.card.muonium.hud");renderer.leave(c);c.finish.run();check(c.route.page()==Navigation.Page.MODS,"leaving global results cancels pending card click");
        boolean[] enabled={false};int[] opened={0};LibraryMods.Card live=new LibraryMods.Card("fixture.live","Live","actual tooltip","hud","hud","","",null,()->enabled[0],value->enabled[0]=value,ctx->opened[0]++);
        SearchIndex.Document<?> direct=SearchDocuments.mod("fixture.live","Live","",null,null,List.of(),live);check(renderer.fromDocument(c,direct).orElseThrow()==live,"explicit real Card payload retains live callbacks");renderer.drawDocument(c,direct,bounds);check(c.rect("mods.toggle.fixture.live").h()==36,"search uses original 36dp footer");c.click("mods.toggle.fixture.live");check(enabled[0]&&opened[0]==0,"search toggle changes live state without opening settings");check(c.hits.get("mods.card.fixture.live").tooltip().equals("actual tooltip"),"search tooltip comes from the actual card");
        c.move(bounds);c.click("mods.card.fixture.live");c.drag.accept(bounds.x()+bounds.w()/2+10,bounds.y()+bounds.h()/2);c.finish.run();check(opened[0]==0,"search card does not open after pointer drag");check(!renderer.drawDocument(c,unsupported,bounds),"unsupported document has no fake fallback drawing");check(c.painter.depth==0,"shared card rendering balances painter state");
    }
    private static void profiles(){
        FakeHost host=new FakeHost();LibraryTestContext c=new LibraryTestContext(host);UiPage page=LibraryPages.profiles();c.frame(page);
        Rect create=c.rect("profiles.create");near(create.x(),289,"create first");near(create.y(),140,"profile grid below chips");near(create.w(),200.2,"five columns");near(create.h(),180,"profile card height");
        check(c.hits.containsKey("profiles.activate."),"default profile remains actionable, not mistaken for edit sentinel");
        c.click("profiles.create");c.frame(page);c.field("profiles.new.name","Night");page.key(c,ConfigView.Key.ENTER,"",false,false,false);c.frame(page);check(host.local.list().stream().anyMatch(p->p.name().equals("Night")),"inline create reaches profile service");
        c.click("profiles.activate.p2");check(host.local.active().equals("p2"),"activate reaches real service");check(c.prefs.get("restored","").equals("p2"),"host UI snapshot restored");
        c.frame(page);c.move(c.rect("profiles.activate.p2"));c.frame(page);c.click("profiles.menu.p2");c.frame(page);c.click("profiles.action.rename");c.frame(page);c.field("profiles.edit.name","Night renamed");page.key(c,ConfigView.Key.ENTER,"",false,false,false);c.frame(page);check(host.local.list().stream().anyMatch(p->p.name().equals("Night renamed")),"inline rename");
        c.move(c.rect("profiles.activate.p2"));c.frame(page);c.click("profiles.menu.p2");c.frame(page);c.click("profiles.action.clone");c.frame(page);check(c.fields.get("profiles.edit.name").edit().text().equals("Night renamed copy"),"clone has source-compatible suggested name");page.key(c,ConfigView.Key.ENTER,"",false,false,false);c.frame(page);check(host.local.list().stream().anyMatch(p->p.name().equals("Night renamed copy")),"clone reaches service");
        c.move(c.rect("profiles.activate.p2"));c.frame(page);c.click("profiles.menu.p2");c.frame(page);c.click("profiles.action.favorite");c.click("profiles.favorites");c.frame(page);check(c.hits.containsKey("profiles.activate.p2"),"favorite profiles filter");check(!c.hits.containsKey("profiles.create"),"create omitted from favorites");
        c.click("profiles.all");c.frame(page);c.move(c.rect("profiles.activate.p2"));c.frame(page);c.click("profiles.menu.p2");c.frame(page);c.click("profiles.action.icon");c.frame(page);c.click("profiles.icon.cloud");c.frame(page);check(host.profileIcon("p2").equals("cloud"),"icon selection reaches optional real capability");
        c.frame(page);c.click("keys.bind.player");check(page.recording(),"profile page exposes active recording to Shell");c.nativeCode=35;page.key(c,ConfigView.Key.NONE,"",false,false,false);check(host.bindings.current.get("player").equals(KeyBindings.Chord.keyboard(35)),"profile card records inline without another page");check(!page.recording(),"profile page clears recording after commit");
        c.click("profiles.separate");check(!host.separate,"separate controls forwarded");c.click("profiles.folder");check(host.folderOpened,"open folder forwarded");
        c.query="Dya";c.frame(page);check(c.hits.containsKey("profiles.activate.p1"),"source fuzzy profile name search");c.prefs.set("searchDistance",0);c.frame(page);check(!c.hits.containsKey("profiles.activate.p1"),"search distance zero disables fuzzy matches");c.prefs.set("searchDistance",2);c.query="missing";c.frame(page);check(c.hits.keySet().stream().noneMatch(id->id.startsWith("profiles.activate.")),"profile page search");check(!c.hits.containsKey("profiles.create"),"create omitted while searching");c.query="";c.frame(page);
        c.move(c.rect("profiles.activate.p2"));c.frame(page);c.click("profiles.menu.p2");c.frame(page);c.click("profiles.action.delete");c.frame(page);check(host.local.list().stream().anyMatch(p->p.id().equals("p2")),"delete requires explicit inline confirmation");c.click("profiles.accept.p2");c.frame(page);check(host.local.list().stream().noneMatch(p->p.id().equals("p2")),"confirmed delete reaches service");
        c.scroll=150;c.frame(page);check(c.hits.entrySet().stream().filter(e->e.getKey().startsWith("profiles.activate.")).allMatch(e->e.getValue().bounds().bottom()<=429),"profile scroll hits do not cover template strip");
    }
    private static void templates(){
        FakeHost host=new FakeHost();LibraryTestContext c=new LibraryTestContext(host);UiPage page=LibraryPages.profiles();c.frame(page);
        near(c.rect("templates.card.builtin").w(),190,"source template width");near(c.rect("templates.card.builtin").h(),176,"source template height");
        c.click("templates.card.builtin");c.frame(page);check(host.preset.page().equals("APPLY"),"detail is delegated to session");check(!c.hits.containsKey("profiles.create"),"focused template takes over body");
        c.click("templates.detail.action.2");c.frame(page);check(host.preset.facets().stream().filter(Presets.Facet::selected).allMatch(f->f.group().equals("AUDIO")),"audio-only chooses audio facets");
        c.click("templates.detail.action.1");c.frame(page);check(host.preset.facets().stream().filter(Presets.Facet::selected).anyMatch(Presets.Facet::optIn),"detail look-only includes opt-in look facets per original");
        c.click("templates.detail.action.3");c.frame(page);check(host.preset.applied==1,"explicit apply forwarded exactly once");check(c.hits.containsKey("profiles.create"),"apply returns to integrated Profiles");
        c.click("templates.action.0");c.frame(page);c.field("templates.save.name","Cinema");c.click("templates.save.action.3");c.frame(page);check(host.preset.saved.equals("Cinema")&&host.preset.copied,"save-and-copy preserves name and share behavior");
        c.click("templates.action.1");c.frame(page);c.field("templates.import.text","MPP2:sample");page.key(c,ConfigView.Key.ENTER,"",false,false,false);c.frame(page);check(host.preset.imported.equals("MPP2:sample"),"import accepts pasted share code");check(host.preset.applied==1,"import did not apply settings");check(c.hits.containsKey("templates.facet.audio"),"import lands in selective detail");
        c.click("templates.back");c.frame(page);c.click("templates.copy.user");check(host.preset.copyId.equals("user"),"copy specific user preset");
        c.click("templates.delete.user");c.frame(page);check(host.preset.cards().stream().anyMatch(p->p.id().equals("user")),"template delete confirmation is inline");c.click("templates.delete.confirm");c.frame(page);check(host.preset.cards().stream().noneMatch(p->p.id().equals("user")),"delete delegated after confirmation");
        c.click("templates.action.2");check(host.preset.undo==1,"undo delegated");
    }
    private static void keys(){
        FakeHost host=new FakeHost();LibraryTestContext c=new LibraryTestContext(host);KeysPage page=new KeysPage();c.frame(page);
        check(c.hits.containsKey("keys.bind.player"),"live keybinding rendered");check(c.tips.get("keys.conflicts.player").contains("Editor"),"live conflicting labels");
        c.click("keys.bind.player");c.nativeCode=30;check(page.key(c,ConfigView.Key.NONE,"",false,false,false),"raw key consumed while recording");check(host.bindings.current.get("player").equals(KeyBindings.Chord.keyboard(30)),"arbitrary physical key saved");
        c.frame(page);c.click("keys.bind.player");page.key(c,ConfigView.Key.ESCAPE,"",false,false,false);check(!host.bindings.current.get("player").bound(),"Escape clears binding instead of closing");
        c.frame(page);c.click("keys.bind.player");page.pointerDown(c,0);check(host.bindings.current.get("player").equals(KeyBindings.Chord.mouse(0)),"left mouse zero is a valid binding");
        c.frame(page);c.click("keys.reset.player");check(host.bindings.current.get("player").equals(KeyBindings.Chord.keyboard(54)),"reset uses entry default");
        c.query="Editor";c.frame(page);check(c.hits.containsKey("keys.bind.editor")&&!c.hits.containsKey("keys.bind.player"),"key search matches titles");
        c.query="Playre";c.frame(page);check(c.hits.containsKey("keys.bind.player"),"fuzzy key title search");c.query="";c.frame(page);c.click("keys.group.muonium");c.frame(page);check(!c.hits.containsKey("keys.bind.player"),"group collapse persisted");c.query="Player";c.frame(page);check(c.hits.containsKey("keys.bind.player"),"search opens collapsed groups");
        c.click("keys.bind.player");page.leave(c);check(!page.recording(),"leaving releases recording capture");check(host.bindings.current.get("player").equals(KeyBindings.Chord.keyboard(54)),"leaving recording did not mutate host");
        c.query="";c.prefs.set("keys.collapsed.muonium",false);c.frame(page);c.move(c.rect("keys.bind.player"));check(page.pointerDown(c,1),"right-click opens binding actions");c.frame(page);c.click("keys.menu.clear");check(!host.bindings.current.get("player").bound(),"context menu clear");
        check(host.errors.isEmpty(),"no swallowed UI errors: "+host.errors);
    }
    private static void recording(){
        KeysRecording recording=new KeysRecording();recording.key(29,true);recording.key(34,true);recording.key(34,false);check(!recording.released(),"held modifier delays completion");recording.key(29,false);check(recording.released(),"completion waits for all releases");check(recording.chord().keys().equals(List.of(34))&&recording.chord().modifiers()==KeyBindings.CONTROL,"modifier split");
        KeysRecording only=new KeysRecording();only.key(42,true);only.key(42,false);check(only.chord().keys().equals(List.of(42))&&only.chord().modifiers()==0,"modifier-only is a literal binding");
        KeysRecording mouse=new KeysRecording();mouse.mouse(0,true);mouse.mouse(0,false);check(mouse.chord().mouse().equals(List.of(0)),"mouse zero retained");
        KeyBindings.Chord orderA=new KeyBindings.Chord(List.of(34,29,34),List.of(2,0),0),orderB=new KeyBindings.Chord(List.of(29,34),List.of(0,2),0);check(orderA.equals(orderB),"conflicts use set equality");
        FakeBindings bindings=new FakeBindings();check(bindings.conflictMap(bindings.entries()).get("player").size()==1,"one snapshot conflict map");
    }
    private static void credits(){
        FakeHost host=new FakeHost();LibraryTestContext c=new LibraryTestContext(host);UiPage page=LibraryPages.credits();c.frame(page);
        var texts=c.painter.texts;check(texts.stream().anyMatch(t->t.value().equals("v2.6.1-test")),"real host version distinct from config version");check(texts.stream().anyMatch(t->t.value().contains("MuoniumConfig v1.9-test")),"config version supplied independently");
        for(String name:List.of("心事抱抱 — 项目所有者","云杉","N0ne","eyz2021","Chryui (Cherry7593)","Compose Multiplatform — JetBrains","OpenAL Soft — 音频输出"))check(texts.stream().anyMatch(t->t.value().contains(name)),"credit retained: "+name);
        check(texts.stream().anyMatch(t->t.value().equals("MuoniumPlayer")&&t.size()==26),"26sp brand title");check(texts.stream().anyMatch(t->t.value().equals("界面与配置")&&t.size()==24),"24sp section heading");check(texts.stream().anyMatch(t->t.value().contains("LGPL-3.0")),"license attribution retained");check(c.maxScroll>0,"credits scroll beyond pane");
    }
    private static void nativeAdapter(){
        FakeNative nativeKeys=new FakeNative();MinecraftKeyBindings service=new MinecraftKeyBindings(nativeKeys);check(service.entries().size()==1,"same native mapping is not duplicated");service.set("minecraft.key.player",KeyBindings.Chord.mouse(0));check(nativeKeys.writes==1&&nativeKeys.last.mouse().equals(List.of(0)),"native adapter writes once without registering");
        boolean rejected=false;try{service.set("minecraft.key.player",new KeyBindings.Chord(List.of(30,31),List.of(),0));}catch(IllegalArgumentException expected){rejected=true;}check(rejected&&nativeKeys.writes==1,"single native binding rejects chords before writing");service.reset("minecraft.key.player");check(nativeKeys.resets==1,"reset delegated");
    }

    private static void shellIntegration(){
        FakeHost host=new FakeHost();ConfigView view=new ConfigView(host,new UiPreferences(Map.of("instantSearch","false","showPageAnimations","false"),()->{}));
        view.register(Navigation.Page.MODS,LibraryPages.mods());view.register(Navigation.Page.KEYS,LibraryPages.keys());
        LibraryTestContext.RecordingPainter painter=new LibraryTestContext.RecordingPainter();
        view.navigate(new Navigation.Route(Navigation.Page.MODS));view.frame(painter,1400,720,-100,-100,1);
        Rect card=view.hitBounds().get("mods.card.muonium.hud");check(card!=null,"Shell registers library mod page");
        Geometry.Point point=view.viewport().screen(card.x()+card.w()/2,card.y()+card.h()/2);
        check(view.pointerDown(point.x(),point.y(),0,false,false,false),"Shell card hit consumes pointer down");
        view.pointerUp(point.x(),point.y(),0);check(view.navigation().current().page()==Navigation.Page.SETTINGS,"Shell hit dispatch opens mod on release");
        view.navigate(new Navigation.Route(Navigation.Page.KEYS));view.frame(painter,1400,720,-100,-100,2);
        Rect bind=view.hitBounds().get("keys.bind.player");check(bind!=null,"Shell registers key catalog");point=view.viewport().screen(bind.x()+bind.w()/2,bind.y()+bind.h()/2);
        view.pointerDown(point.x(),point.y(),0,false,false,false);view.pointerUp(point.x(),point.y(),0);view.frame(painter,1400,720,-100,-100,3);
        check(view.hitBounds().containsKey("keys.record.capture"),"recording registers full shell left mouse capture");
        Geometry.Point outside=view.viewport().screen(60,80);view.pointerDown(outside.x(),outside.y(),0,false,false,false);view.pointerUp(outside.x(),outside.y(),0);
        check(host.bindings.current.get("player").equals(KeyBindings.Chord.mouse(0)),"Shell mouse binding wins over sidebar hits");check(painter.depth==0,"Shell integration balances painter stack");
    }

    static class FakeHost implements ConfigHost,LibraryServices.Keys,LibraryServices.ProfileExtras,LibraryServices.PresetExtras,LibraryServices.Information {
        final FakeProfiles local=new FakeProfiles();final FakePresets preset=new FakePresets();final FakeBindings bindings=new FakeBindings();final List<String> errors=new ArrayList<>();final Map<String,String> icons=new HashMap<>();boolean separate=true,folderOpened;String clipboard="";
        public List<Category> categories(){return List.of(new Category("display","外观","", "hud",List.of()),new Category("audio","音效","", "hud",List.of()));}
        public List<HudElement> huds(){return List.of(new FakeHud("muonium.lyrics","桌面歌词"),new FakeHud("muonium.info","歌曲信息卡"));}
        public Profiles profiles(){return local;}public Presets presets(){return preset;}public KeyBindings keyBindings(){return bindings;}
        public void persist(){}public void close(){}public String clipboard(){return clipboard;}public void clipboard(String text){clipboard=text;}public void error(String action,Throwable error){errors.add(action+": "+error);}
        public Set<String> capabilities(){return Set.of("controls","folder","icons","export");}public boolean separateControls(){return separate;}public void separateControls(boolean enabled){separate=enabled;}public void openProfilesFolder(){folderOpened=true;}public void exportProfile(String id){}public String profileIcon(String id){return icons.getOrDefault(id,"profiles");}public void profileIcon(String id,String icon){icons.put(id,icon);}
        public String playerVersion(){return "2.6.1-test";}public String configVersion(){return "1.9-test";}public String facetDescription(String id){return "真实宿主描述 "+id;}public String cardSummary(String id){return "外观 · 音效";}public int changedKeys(){return 7;}public KeyBindings.Entry profileKey(String id){return bindings.entries().get(0);}
    }
    static final class FakeCatalog extends FakeHost implements LibraryServices.ModCatalog {
        public List<LibraryMods.Card> modCards(){return List.of(card("a","Alpha"),card("b","Beta"),card("c","Gamma"));}
        private LibraryMods.Card card(String id,String title){return new LibraryMods.Card(id,title,"","hud","hud","","",null,null,null,c->{});}
    }
    static final class FakeHud implements HudElement {
        final String id,title;FakeHud(String id,String title){this.id=id;this.title=title;}public String id(){return id;}public String title(){return title;}public double x(){return 0;}public double y(){return 0;}public double width(){return 150;}public double height(){return 40;}public double scale(){return 1;}public boolean hidden(){return true;}public boolean aspectLocked(){return true;}public void position(double x,double y){}public void size(double w,double h){}public void scale(double value){}public void hidden(boolean hidden){}public void preview(double x,double y){}
    }
    static final class FakeProfiles implements Profiles {
        final List<Profile> data=new ArrayList<>(List.of(new Profile("","Default",0),new Profile("p1","Day",1)));String active="",status="";int next=2;
        public List<Profile> list(){return List.copyOf(data);}public String status(){return status;}public boolean busy(){return false;}public String active(){return active;}public void refresh(){}
        public void save(String name,Map<String,String> ui){data.add(new Profile("p"+next++,name,2));status="已保存";}public void activate(String id,Consumer<Map<String,String>> restore){active=id;restore.accept(Map.of("restored",id));}
        public void rename(String id,String name){Profile p=data.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();data.set(data.indexOf(p),new Profile(id,name,3));status="已重命名";}public void duplicate(String id,String name){save(name,Map.of());}public void delete(String id){data.removeIf(e->e.id().equals(id));status="已删除";}public void close(){}
    }
    static final class FakePresets implements Presets {
        String page="MAIN",target="",saved="",imported="",copyId="";int applied,undo;boolean copied;final List<Card> data=new ArrayList<>(List.of(new Card("builtin","内置模板","外观与音效",true),new Card("user","我的模板","本地",false)));
        final Map<String,Boolean> selected=new LinkedHashMap<>(Map.of("look",true,"layout",false,"audio",true));
        public String page(){return page;}public String status(){return "已完成";}public List<Card> cards(){return List.copyOf(data);}public List<Facet> facets(){return List.of(new Facet("look","外观","APPEARANCE",selected.get("look"),false),new Facet("layout","布局","APPEARANCE",selected.get("layout"),true),new Facet("audio","音效","AUDIO",selected.get("audio"),false));}public List<String> preview(){return List.of("字体只在本机可用");}public String targetName(){return "内置模板";}public boolean canUndo(){return true;}
        public void choose(String id){target=id;page="APPLY";}public void main(){page="MAIN";}public void savePage(){page="SAVE";}public void toggle(String id){selected.put(id,!selected.get(id));}public void apply(){applied++;page="MAIN";}public void save(String name,boolean copy){saved=name;copied=copy;page="MAIN";}public void copy(String id){copyId=id;}public void delete(String id){data.removeIf(c->c.id().equals(id));}public void importText(String text){imported=text;page="APPLY";}public void undo(){undo++;}
    }
    static final class FakeBindings implements KeyBindings {
        final Map<String,Chord> current=new LinkedHashMap<>(Map.of("player",Chord.keyboard(54),"editor",Chord.keyboard(54)));
        public List<Entry> entries(){return List.of(entry("player","Player"),entry("editor","Editor"));}
        private Entry entry(String id,String title){Chord chord=current.get(id);return new Entry(id,"muonium","MuoniumPlayer","keyboard","","",title,"",display(chord),chord,Chord.keyboard(54),true,"");}
        public void set(String id,Chord chord){current.put(id,chord);}public void reset(String id){current.put(id,Chord.keyboard(54));}public String display(Chord chord){return chord.bound()?"Key "+chord:"None";}
    }
    static final class FakeNative implements NativeKeys {
        int writes,resets;KeyBindings.Chord last;
        public List<Mapping> mappings(){Mapping a=new Mapping("minecraft.key.player","MuoniumPlayer","Player","Right Shift",KeyBindings.Chord.keyboard(54),KeyBindings.Chord.keyboard(54),"");return List.of(a,a);}
        public void set(String id,KeyBindings.Chord chord){writes++;last=chord;}public void reset(String id){resets++;}public String display(KeyBindings.Chord chord){return "test";}public boolean keyDown(int code){return false;}public boolean mouseDown(int button){return false;}
    }
}
