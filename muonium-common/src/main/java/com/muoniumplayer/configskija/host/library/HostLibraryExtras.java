package com.muoniumplayer.configskija.host.library;

import com.muoniumplayer.configskija.host.MuoniumProfiles;
import com.muoniumplayer.configskija.host.MuoniumPresets;
import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.pages.*;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Real host library services. Construct/call on client main; injected worker owns all files/dialogs. */
public final class HostLibraryExtras implements LibraryServices.ProfileExtras,LibraryServices.PresetExtras,
        LibraryServices.Information,LibraryServices.ModCatalog,AutoCloseable {
    private final MuoniumProfiles profiles;
    private final MuoniumPresets presets;
    private final ConfigHost host;
    private final UiPreferences preferences;
    private final Executor main,worker;
    private final LibraryFiles files;
    private final KeyBindings nativeKeys;
    private final Supplier<String> configVersion;
    private final Path metadata;
    private final LibraryKeys keys=new LibraryKeys();
    private final Set<String> heldProfiles=new HashSet<>();
    private final Set<Long> pending=new HashSet<>();
    private Map<String,String> settings=Map.of(),committedSettings=Map.of();
    private boolean ready,chooseAvailable,folderAvailable;
    private volatile boolean closed;
    private volatile long generation,settingsGeneration;
    private long nextTask;
    private String status="";
    private CompletableFuture<Void> metadataPending=CompletableFuture.completedFuture(null);
    public HostLibraryExtras(MuoniumProfiles profiles,MuoniumPresets presets,ConfigHost host,UiPreferences preferences,
                             Executor main,Executor worker,LibraryFiles files,KeyBindings nativeKeys,Supplier<String> configVersion){
        this.profiles=Objects.requireNonNull(profiles);this.presets=Objects.requireNonNull(presets);this.host=Objects.requireNonNull(host);this.preferences=Objects.requireNonNull(preferences);this.main=Objects.requireNonNull(main);this.worker=Objects.requireNonNull(worker);this.files=Objects.requireNonNull(files);this.nativeKeys=nativeKeys;this.configVersion=Objects.requireNonNull(configVersion);
        metadata=profiles.directory().getParent().resolve("library.properties");presets.profileGeneration(profiles::profileGeneration);
        profiles.attach(preferences::snapshot,preferences::replace,new MuoniumProfiles.Lifecycle(){
            public Map<String,String> capture(){if(separateControls()&&HostLibraryExtras.this.nativeKeys!=null){Map<String,String> values=new HashMap<>(ProfileChords.capture(HostLibraryExtras.this.nativeKeys));values.put("controlPolicy","separate");return Map.copyOf(values);}return Map.of("controlPolicy","shared");}
            public void restore(Map<String,String> snapshot){if(separateControls()&&HostLibraryExtras.this.nativeKeys!=null)ProfileChords.restore(HostLibraryExtras.this.nativeKeys,snapshot);}
            public void changed(String previous,String current){preferences.set("profile.active",current);generation++;presets.main();presets.refresh();}
            public void removed(String id){if(ready())persistSetting("key."+ProfileChords.id(id),null);heldProfiles.remove(id);}
            public Map<String,String> export(String id){return Map.of("profileHotkey",ProfileChords.encode(chord(id)),"controlPolicy",separateControls()?"separate":"shared");}
        });
        submit("读取配置扩展",false,()->{Properties loaded=Files.exists(metadata)?LibraryIo.read(metadata,LibraryIo.MAX_PROFILE_BYTES):new Properties();Map<String,String> values=new HashMap<>();for(String key:loaded.stringPropertyNames()){String value=loaded.getProperty(key);if(key.startsWith("key.")){ProfileChords.decodeId(key.substring(4));ProfileChords.decode(value);}if(key.equals("controls.separate")&&!value.equals("true")&&!value.equals("false"))throw new java.io.IOException("无效的独立键位策略");values.put(key,value);}if(loaded.containsKey("formatVersion")&&!"1".equals(loaded.getProperty("formatVersion")))throw new java.io.IOException("不支持的配置扩展版本");return new Loaded(Map.copyOf(values),files.canChoose(),files.canOpenFolder());},loaded->{settings=committedSettings=loaded.settings();chooseAvailable=loaded.choose();folderAvailable=loaded.folder();ready=true;});
    }
    public HostLibraryExtras(MuoniumProfiles profiles,MuoniumPresets presets,ConfigHost host,UiPreferences preferences,Executor main,KeyBindings nativeKeys){this(profiles,presets,host,preferences,main,LibraryExecutors.worker(),new DesktopLibraryFiles(),nativeKeys,()->{String version=HostLibraryExtras.class.getPackage().getImplementationVersion();return version==null?"":version;});}
    private record Loaded(Map<String,String> settings,boolean choose,boolean folder){}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private boolean valid(long ticket,long profile){return !closed&&generation==ticket&&profiles.profileGeneration()==profile;}
    private <T> void submit(String action,boolean checkNavigation,Work<T> work,Consumer<T> done){
        if(closed)return;long ticket=generation,profile=profiles.profileGeneration(),navigation=presets.navigationGeneration(),task=++nextTask;pending.add(task);status=action+"…";
        CompletableFuture.supplyAsync(()->{if(!valid(ticket,profile))throw new CancellationException("操作已过期");try{return work.run();}catch(Exception error){throw new CompletionException(error);}},worker).whenComplete((result,error)->main.execute(()->{
            pending.remove(task);if(!valid(ticket,profile)||checkNavigation&&navigation!=presets.navigationGeneration())return;
            if(error!=null){Throwable failure=LibraryIo.unwrap(error);if(!(failure instanceof CancellationException)){status=action+"失败："+LibraryIo.message(failure);host.error(action,failure);}return;}
            try{done.accept(result);if(status.equals(action+"…"))status=action+"完成";}catch(RuntimeException failure){status=action+"失败："+LibraryIo.message(failure);host.error(action,failure);}
        }));
    }
    /** Call when the Profiles route opens or after an external migration/import has completed. */
    public void refresh(){if(closed)return;profiles.refresh();presets.refresh();}
    public boolean ready(){return ready&&!closed;}
    public String status(){return status;}
    public Set<String> capabilities(){if(!ready())return Set.of();Set<String> available=new HashSet<>();available.add("icons");if(nativeKeys!=null)available.add("controls");if(folderAvailable)available.add("folder");if(chooseAvailable)available.add("export");return Set.copyOf(available);}
    public boolean separateControls(){return Boolean.parseBoolean(settings.getOrDefault("controls.separate","true"));}
    public void separateControls(boolean enabled){if(nativeKeys==null)throw new UnsupportedOperationException("宿主尚未提供原生控制键位");persistSetting("controls.separate",Boolean.toString(enabled));}
    private void persistSetting(String key,String value){
        if(!ready())throw new IllegalStateException("配置扩展尚未就绪");Map<String,String> next=new HashMap<>(settings);if(value==null)next.remove(key);else next.put(key,value);Map<String,String> snapshot=Map.copyOf(next);settings=snapshot;
        long write=++settingsGeneration,profile=profiles.profileGeneration(),epoch=generation,task=++nextTask;pending.add(task);status="保存配置扩展…";CompletableFuture<Void> saved=new CompletableFuture<>();metadataPending=saved;
        CompletableFuture.runAsync(()->{if(write!=settingsGeneration||!valid(epoch,profile))throw new CancellationException("设置写入已更新");try{Properties p=new Properties();snapshot.forEach(p::setProperty);p.setProperty("formatVersion","1");LibraryIo.write(metadata,p,()->write==settingsGeneration&&valid(epoch,profile));}catch(java.io.IOException error){throw new CompletionException(error);}},worker).whenComplete((ignored,error)->main.execute(()->{
            pending.remove(task);if(!valid(epoch,profile)||write!=settingsGeneration){if(!closed&&write==settingsGeneration)settings=committedSettings;saved.completeExceptionally(new CancellationException("配置扩展写入已过期"));return;}
            if(error==null){committedSettings=snapshot;status="配置扩展已保存";saved.complete(null);}else{settings=committedSettings;Throwable failure=LibraryIo.unwrap(error);if(!(failure instanceof CancellationException)){status="配置扩展保存失败："+LibraryIo.message(failure);host.error("保存配置扩展",failure);}saved.completeExceptionally(failure);}
        }));
    }
    public String profileIcon(String id){return profiles.icon(id);}
    public void profileIcon(String id,String icon){profiles.icon(id,icon);}
    public void openProfilesFolder(){if(!folderAvailable)throw new UnsupportedOperationException("系统没有可用的目录打开器");submit("打开配置目录",false,()->{files.openFolder(profiles.directory());return true;},ignored->{});}
    public void exportProfile(String id){
        if(!chooseAvailable)throw new UnsupportedOperationException("系统文件选择器不可用");Profiles.Profile profile=profiles.list().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("配置不存在"));
        long ticket=generation,profileGeneration=profiles.profileGeneration();String filename=profile.name().replaceAll("[^a-zA-Z0-9._-]","_")+".zip";
        submit("选择配置导出位置",false,()->files.chooseProfileExport(profiles.directory(),filename),destination->{if(destination==null){status="已取消导出";return;}Path target=destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")?destination:destination.resolveSibling(destination.getFileName()+".zip");
            profiles.export(id,target,()->valid(ticket,profileGeneration)).whenComplete((ignored,error)->main.execute(()->{if(!valid(ticket,profileGeneration))return;if(error!=null){status="导出配置失败："+LibraryIo.message(error);host.error("导出配置",LibraryIo.unwrap(error));}else status="已导出配置「"+profile.name()+"」";}));});
    }
    public boolean busy(){return !closed&&(!pending.isEmpty()||profiles.busy()||presets.busy());}
    public String facetDescription(String id){return presets.facetDescription(id);}
    public String cardSummary(String id){return presets.cardSummary(id);}
    public int changedKeys(){return presets.changedKeys();}
    public boolean previewOk(){return presets.previewOk();}
    public boolean canOpenFolder(){return ready()&&folderAvailable;}
    public boolean canImportFile(){return ready()&&chooseAvailable;}
    public void openPresetsFolder(){if(!canOpenFolder())throw new UnsupportedOperationException("系统没有可用的目录打开器");submit("打开模板目录",false,()->{files.openFolder(presets.directory());return true;},ignored->{});}
    public void importPresetFile(){
        if(!canImportFile())throw new UnsupportedOperationException("系统文件选择器不可用");long ticket=generation,profile=profiles.profileGeneration(),navigation=presets.navigationGeneration();
        submit("选择模板文件",true,()->files.choosePreset(presets.directory()),path->{if(path==null){status="已取消导入";return;}presets.importFile(path,()->valid(ticket,profile)&&navigation==presets.navigationGeneration()).whenComplete((ignored,error)->main.execute(()->{if(!valid(ticket,profile))return;if(error!=null){status="导入模板失败："+LibraryIo.message(error);host.error("导入模板",LibraryIo.unwrap(error));}else status=presets.status();}));});
    }
    public String playerVersion(){return MuoniumPlayerExtension.VERSION;}
    public String configVersion(){String version=configVersion.get();return version==null?"":version.strip();}
    public List<LibraryMods.Card> modCards(){
        List<LibraryMods.Card> cards=new ArrayList<>();if(!host.categories().isEmpty())cards.add(new LibraryMods.Card("muonium.hud","MuoniumPlayer","MuoniumPlayer 的 HUD、歌词、灵动岛与播放器外观设置。","hud","hud","","",null,null,null,c->c.navigate(new Navigation.Route(Navigation.Page.SETTINGS))));
        if(host.favoriteCategories().stream().anyMatch(category->category.id().equals("muonium.favorites")))cards.add(new LibraryMods.Card("muonium.favorites","常用","","star","qol","","",null,null,null,c->c.navigate(new Navigation.Route(Navigation.Page.FAVORITES))));
        for(HudElement hud:host.huds())cards.add(new LibraryMods.Card("hud:"+hud.id(),hud.title().endsWith("HUD")?hud.title():hud.title()+" HUD","",hud.icon(),"hud","huds","HUDs",hud,null,null,c->c.navigate(new Navigation.Route(Navigation.Page.HUD,hud.id()))));return List.copyOf(cards);
    }
    public KeyBindings keyBindings(){return keys;}
    public KeyBindings.Entry profileKey(String id){if(!ready()||!(nativeKeys instanceof KeyBindings.InputState))return null;Profiles.Profile profile=profiles.list().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("配置不存在"));String key="profile:"+id;KeyBindings.Chord chord=chord(id);return new KeyBindings.Entry(key,"profile-keybinds","Profiles","profiles","Profiles","",profile.name(),"切换到此本地配置",keys.display(chord),chord,KeyBindings.Chord.unbound(),false,"",List.of(),key);}
    private KeyBindings.Chord chord(String id){String value=settings.get("key."+ProfileChords.id(id));return value==null?KeyBindings.Chord.unbound():ProfileChords.decode(value);}
    private void bind(String id,KeyBindings.Chord chord){if(profiles.list().stream().noneMatch(p->p.id().equals(id)))throw new IllegalArgumentException("配置不存在");ProfileChords.decode(ProfileChords.encode(chord));persistSetting("key."+ProfileChords.id(id),ProfileChords.encode(chord));}
    /** Call once per client tick on main, including when the configuration screen is closed. */
    public void tick(boolean screenOpen){
        if(!ready()||!(nativeKeys instanceof KeyBindings.InputState input))return;int modifiers=0;if(input.keyDown(29)||input.keyDown(157))modifiers|=KeyBindings.CONTROL;if(input.keyDown(42)||input.keyDown(54))modifiers|=KeyBindings.SHIFT;if(input.keyDown(56)||input.keyDown(184))modifiers|=KeyBindings.ALT;if(input.keyDown(219)||input.keyDown(220))modifiers|=KeyBindings.SUPER;
        Set<String> down=new HashSet<>();String candidate=null;for(Profiles.Profile profile:profiles.list()){
            KeyBindings.Chord chord=chord(profile.id());boolean pressed=chord.bound()&&(modifiers&chord.modifiers())==chord.modifiers()&&chord.keys().stream().allMatch(input::keyDown)&&chord.mouse().stream().allMatch(input::mouseDown);
            if(!pressed)continue;down.add(profile.id());if(candidate==null&&!screenOpen&&!heldProfiles.contains(profile.id())&&!busy()&&!profiles.active().equals(profile.id()))candidate=profile.id();
        }
        heldProfiles.retainAll(down);heldProfiles.addAll(down);
        if(candidate!=null)profiles.activateAsync(candidate,preferences::replace).whenComplete((ignored,error)->main.execute(()->{if(closed)return;if(error!=null)host.error("配置快捷键",LibraryIo.unwrap(error));}));
    }

    private final class LibraryKeys implements KeyBindings,KeyBindings.InputState {
        public List<Entry> entries(){List<Entry> out=new ArrayList<>();if(nativeKeys!=null)out.addAll(nativeKeys.entries());if(ready()&&nativeKeys instanceof KeyBindings.InputState)for(Profiles.Profile p:profiles.list()){Entry entry=profileKey(p.id());if(entry!=null)out.add(entry);}return List.copyOf(out);}
        public void set(String id,Chord chord){if(id.startsWith("profile:"))bind(id.substring(8),chord);else if(nativeKeys!=null)nativeKeys.set(id,chord);else throw new IllegalArgumentException("没有此原生键位");}
        public void reset(String id){if(id.startsWith("profile:"))bind(id.substring(8),Chord.unbound());else if(nativeKeys!=null)nativeKeys.reset(id);else throw new IllegalArgumentException("没有此原生键位");}
        public String display(Chord chord){if(!chord.bound())return "None";return nativeKeys==null?"未提供键名服务":nativeKeys.display(chord);}
        public boolean keyDown(int code){return nativeKeys instanceof KeyBindings.InputState input&&input.keyDown(code);}
        public boolean mouseDown(int button){return nativeKeys instanceof KeyBindings.InputState input&&input.mouseDown(button);}
        public String status(){return HostLibraryExtras.this.status;}
    }
    /** Host screen disposal must await this future, then close, without blocking client main. */
    public CompletableFuture<Void> flush(){if(closed)return CompletableFuture.failedFuture(new IllegalStateException("配置扩展已关闭"));return metadataPending.thenCompose(ignored->{try{return profiles.checkpoint();}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});}
    public void close(){closed=true;generation++;settingsGeneration++;heldProfiles.clear();pending.clear();presets.close();profiles.close();}
}
