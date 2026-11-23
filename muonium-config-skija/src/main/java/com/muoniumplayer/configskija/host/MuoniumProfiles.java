package com.muoniumplayer.configskija.host;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.configskija.model.Profiles;
import com.muoniumplayer.configskija.host.library.*;
import com.muoniumplayer.core.preset.PresetConfigBridge;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Main-thread host snapshots, worker archives, and generation-checked main-thread completion. */
public final class MuoniumProfiles implements Profiles {
    public interface LocalState {
        CompletableFuture<String> capture();
        CompletableFuture<String> restore(String json);
    }
    public interface Lifecycle {
        Map<String,String> capture();
        void restore(Map<String,String> snapshot);
        default void changed(String previous,String current){}
        default Map<String,String> export(String id){return Map.of();}
        default void removed(String id){}
    }
    private final ProfileFiles files;
    private final Executor worker,main;
    private final LocalState state;
    private List<Profile> profiles=List.of();
    private Map<String,String> icons=Map.of();
    private String status="",active="";
    private boolean busy,initialized;
    private volatile boolean closed;
    private volatile long generation,profileGeneration;
    private CompletableFuture<?> pendingRestore;
    private Supplier<Map<String,String>> ui=Map::of;
    private Consumer<Map<String,String>> attachedRestore=snapshot->{};
    private Lifecycle lifecycle=new Lifecycle(){public Map<String,String> capture(){return Map.of();}public void restore(Map<String,String> ignored){}};
    public MuoniumProfiles(){this(ConfigPaths.HUD.toPath().getParent().resolve("skija-config/profiles"),LibraryExecutors.worker(),MultiThreadingUtil::runOnMainThread,new LocalState(){public CompletableFuture<String> capture(){return PresetConfigBridge.captureLocalState();}public CompletableFuture<String> restore(String json){return PresetConfigBridge.restoreLocalState(json);}});}
    public MuoniumProfiles(Path directory,Executor worker,Executor main,LocalState state){this.files=new ProfileFiles(directory);this.worker=Objects.requireNonNull(worker);this.main=Objects.requireNonNull(main);this.state=Objects.requireNonNull(state);refresh();}
    public void attach(Supplier<Map<String,String>> ui,Lifecycle lifecycle){attach(ui,snapshot->{},lifecycle);}
    public void attach(Supplier<Map<String,String>> ui,Consumer<Map<String,String>> restore,Lifecycle lifecycle){this.ui=Objects.requireNonNull(ui);attachedRestore=Objects.requireNonNull(restore);this.lifecycle=Objects.requireNonNull(lifecycle);}
    public Path directory(){return files.directory();}
    public long profileGeneration(){return profileGeneration;}
    public boolean closed(){return closed;}
    public List<Profile> list(){return profiles;}
    public String status(){return status;}
    public boolean busy(){return busy;}
    public String active(){return active;}
    public String icon(String id){require(id);return icons.getOrDefault(id,"profiles");}
    private void require(String id){if(profiles.stream().noneMatch(p->p.id().equals(id)))throw new IllegalArgumentException("配置不存在："+id);}
    private static String name(String raw){String clean=raw==null?"":raw.strip();if(clean.isEmpty()||clean.length()>80||clean.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("配置名称需要 1 — 80 个可见字符");return clean;}
    private boolean valid(long ticket){return !closed&&generation==ticket;}
    private long begin(String action){if(closed)throw new IllegalStateException("配置服务已关闭");if(busy)throw new IllegalStateException("另一个配置操作尚未完成");busy=true;status=action+"…";return ++generation;}
    private void listing(ProfileFiles.Listing loaded){profiles=loaded.profiles();icons=loaded.icons();if(!initialized){active=loaded.active();initialized=true;}}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T> CompletableFuture<T> work(long ticket,Work<T> task){return CompletableFuture.supplyAsync(()->{if(!valid(ticket))throw new CancellationException("配置操作已过期");try{return task.run();}catch(Exception e){throw new CompletionException(e);}},worker);}
    private void finish(long ticket,String action,Throwable error){if(!valid(ticket))return;busy=false;status=error==null?action+"完成":action+"失败："+LibraryIo.message(error);}
    public void refresh(){if(closed||busy)return;long ticket=begin("读取本地配置");work(ticket,files::scan).whenComplete((loaded,error)->main.execute(()->{if(!valid(ticket))return;if(error==null)listing(loaded);finish(ticket,"读取本地配置",error);}));}
    private CompletableFuture<Properties> capture(long ticket,String id,String title,Map<String,String> uiSnapshot){
        CompletableFuture<Properties> result=new CompletableFuture<>();Map<String,String> frozenUi,extra;try{frozenUi=Map.copyOf(uiSnapshot);extra=Map.copyOf(lifecycle.capture());}catch(RuntimeException failure){result.completeExceptionally(failure);return result;}String icon=icons.getOrDefault(id,"profiles");
        CompletableFuture<String> captured;try{captured=state.capture();}catch(RuntimeException failure){result.completeExceptionally(failure);return result;}
        captured.whenComplete((music,error)->main.execute(()->{
            if(!valid(ticket)){result.completeExceptionally(new CancellationException("配置操作已过期"));return;}if(error!=null){result.completeExceptionally(error);return;}
            try{JsonObject json=JsonParser.parseString(music).getAsJsonObject();if(!json.has("format")||!json.has("state"))throw new IllegalArgumentException("主项目没有返回本地快照");
                Properties out=new Properties();out.setProperty("version","1");out.setProperty("name",title);out.setProperty("icon",icon);out.setProperty("music",music);frozenUi.forEach((key,value)->out.setProperty("ui."+key,value));extra.forEach((key,value)->out.setProperty("extra."+key,value));result.complete(out);
            }catch(RuntimeException failure){result.completeExceptionally(failure);}
        }));return result;
    }
    public void save(String rawName,Map<String,String> snapshot){
        String title=name(rawName);if(profiles.stream().anyMatch(p->p.name().equalsIgnoreCase(title)))throw new IllegalArgumentException("此名称已被使用");String id=UUID.randomUUID().toString();long ticket=begin("保存配置");
        capture(ticket,id,title,snapshot).thenCompose(p->work(ticket,()->{files.write(id,p,()->valid(ticket));return files.scan();})).whenComplete((loaded,error)->main.execute(()->{if(!valid(ticket))return;if(error==null)listing(loaded);finish(ticket,"保存配置",error);}));
    }
    public void activate(String id,Consumer<Map<String,String>> restoreUi){activateAsync(id,restoreUi);}
    public CompletableFuture<Void> activateAsync(String id,Consumer<Map<String,String>> restoreUi){
        require(id);if(id.equals(active))return CompletableFuture.completedFuture(null);String previous=active;long ticket=begin("切换配置");profileGeneration++;CompletableFuture<Void> result=new CompletableFuture<>();
        String previousTitle=profiles.stream().filter(p->p.id().equals(previous)).findFirst().map(Profile::name).orElse("Default");
        capture(ticket,previous,previousTitle,ui.get()).thenCompose(outgoing->work(ticket,()->{files.write(previous,outgoing,()->valid(ticket));Properties incoming=files.read(id);return new Properties[]{outgoing,incoming};})).whenComplete((pair,error)->main.execute(()->{
            if(!valid(ticket)){result.completeExceptionally(new CancellationException("配置切换已过期"));return;}if(error!=null){finish(ticket,"切换配置",error);result.completeExceptionally(LibraryIo.unwrap(error));return;}
            Properties outgoing=pair[0],incoming=pair[1];try{pendingRestore=state.restore(incoming.getProperty("music",""));}catch(RuntimeException restoreError){rollback(ticket,outgoing,restoreUi,restoreError,result);return;}
            @SuppressWarnings("unchecked") CompletableFuture<String> restoring=(CompletableFuture<String>)pendingRestore;
            restoring.whenComplete((report,failure)->main.execute(()->{
                if(!valid(ticket)){result.completeExceptionally(new CancellationException("配置切换已过期"));return;}
                try{if(failure!=null)throw new CompletionException(failure);JsonObject json=JsonParser.parseString(report).getAsJsonObject();if(!json.has("ok")||!json.get("ok").getAsBoolean())throw new IllegalStateException("主项目拒绝恢复配置："+report);
                    lifecycle.restore(values(incoming,"extra."));restoreUi.accept(values(incoming,"ui."));
                    work(ticket,()->{files.active(id,()->valid(ticket));return true;}).whenComplete((stored,writeFailure)->main.execute(()->{if(!valid(ticket)){result.completeExceptionally(new CancellationException("配置状态写入已过期"));return;}if(writeFailure!=null){rollback(ticket,outgoing,restoreUi,writeFailure,result);return;}active=id;profileGeneration++;lifecycle.changed(previous,id);finish(ticket,"切换配置",null);status="已切换到「"+incoming.getProperty("name",id)+"」";result.complete(null);}));
                }catch(RuntimeException broken){
                    rollback(ticket,outgoing,restoreUi,broken,result);
                }
            }));
        }));return result;
    }
    private void rollback(long ticket,Properties outgoing,Consumer<Map<String,String>> restoreUi,Throwable failure,CompletableFuture<Void> result){
        Throwable broken=LibraryIo.unwrap(failure);try{pendingRestore=state.restore(outgoing.getProperty("music",""));}catch(RuntimeException failedRollback){broken.addSuppressed(failedRollback);finish(ticket,"切换配置",broken);result.completeExceptionally(broken);return;}pendingRestore.whenComplete((report,rollback)->main.execute(()->{
            if(!valid(ticket)){result.completeExceptionally(new CancellationException("配置回滚已过期"));return;}
            try{lifecycle.restore(values(outgoing,"extra."));restoreUi.accept(values(outgoing,"ui."));}catch(RuntimeException restoreError){broken.addSuppressed(restoreError);}
            if(rollback!=null)broken.addSuppressed(rollback);else try{JsonObject json=JsonParser.parseString(String.valueOf(report)).getAsJsonObject();if(!json.has("ok")||!json.get("ok").getAsBoolean())broken.addSuppressed(new IllegalStateException("主项目回滚被拒绝："+report));}catch(RuntimeException invalid){broken.addSuppressed(invalid);}
            finish(ticket,"切换配置",broken);result.completeExceptionally(broken);
        }));
    }
    private static Map<String,String> values(Properties p,String prefix){Map<String,String> out=new HashMap<>();for(String key:p.stringPropertyNames())if(key.startsWith(prefix))out.put(key.substring(prefix.length()),p.getProperty(key));return Map.copyOf(out);}
    private void mutate(String action,String id,Consumer<Properties> change,String destination){
        require(id);long ticket=begin(action);work(ticket,()->{Properties props=files.read(id);change.accept(props);if(!destination.equals(id)){for(String key:new ArrayList<>(props.stringPropertyNames()))if(key.startsWith("legacy.import."))props.remove(key);props.setProperty("ui.profile.active",destination);}files.write(destination,props,()->valid(ticket));return files.scan();}).whenComplete((loaded,error)->main.execute(()->{if(!valid(ticket))return;if(error==null)listing(loaded);finish(ticket,action,error);}));
    }
    public void rename(String id,String rawName){if(id.isEmpty())throw new IllegalArgumentException("默认配置不能重命名");String title=name(rawName);if(profiles.stream().anyMatch(p->!p.id().equals(id)&&p.name().equalsIgnoreCase(title)))throw new IllegalArgumentException("此名称已被使用");mutate("重命名配置",id,p->p.setProperty("name",title),id);}
    public void duplicate(String id,String rawName){String title=name(rawName);if(profiles.stream().anyMatch(p->p.name().equalsIgnoreCase(title)))throw new IllegalArgumentException("此名称已被使用");String next=UUID.randomUUID().toString();if(id.equals(active)){long ticket=begin("复制配置");capture(ticket,id,title,ui.get()).thenCompose(p->work(ticket,()->{files.write(next,p,()->valid(ticket));return files.scan();})).whenComplete((loaded,error)->main.execute(()->{if(!valid(ticket))return;if(error==null)listing(loaded);finish(ticket,"复制配置",error);}));}else mutate("复制配置",id,p->p.setProperty("name",title),next);}
    public void icon(String id,String icon){if(!Set.of("profiles","star","settings","cog","paintbrush","keyboard","hud","combat","qol","cloud").contains(icon))throw new IllegalArgumentException("不支持的图标");if(id.equals(active)){long ticket=begin("更改配置图标");capture(ticket,id,profiles.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow().name(),ui.get()).thenCompose(p->work(ticket,()->{p.setProperty("icon",icon);files.write(id,p,()->valid(ticket));return files.scan();})).whenComplete((loaded,error)->main.execute(()->{if(!valid(ticket))return;if(error==null)listing(loaded);finish(ticket,"更改配置图标",error);}));}else mutate("更改配置图标",id,p->p.setProperty("icon",icon),id);}
    public void delete(String id){require(id);if(id.isEmpty())throw new IllegalArgumentException("默认配置不能删除");if(id.equals(active)){activateAsync("",attachedRestore).whenComplete((ignored,error)->{if(error==null)main.execute(()->delete(id));});return;}long ticket=begin("删除配置");work(ticket,()->{files.delete(id,()->valid(ticket));return files.scan();}).whenComplete((loaded,error)->main.execute(()->{if(!valid(ticket))return;if(error==null){listing(loaded);lifecycle.removed(id);}finish(ticket,"删除配置",error);}));}
    public CompletableFuture<Void> export(String id,Path destination,BooleanSupplier accepted){
        require(id);long ticket=begin("导出配置");CompletableFuture<Properties> snapshot=id.equals(active)?capture(ticket,id,profiles.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow().name(),ui.get()):work(ticket,()->files.read(id));
        CompletableFuture<Void> result=new CompletableFuture<>();snapshot.thenCompose(p->{Map<String,String> exported=Map.copyOf(lifecycle.export(id));exported.forEach((key,value)->p.setProperty("extra."+key,value));return work(ticket,()->{LibraryIo.export(destination,p,()->valid(ticket)&&accepted.getAsBoolean());return true;});}).whenComplete((ignored,error)->main.execute(()->{if(valid(ticket))finish(ticket,"导出配置",error);if(error==null)result.complete(null);else result.completeExceptionally(LibraryIo.unwrap(error));}));return result;
    }
    private record ImportBatch(LegacyProfileImport.Result result,ProfileFiles.Listing listing){}
    /** Converts every detached old Profile into its own new UUID without selecting it or reading old files. */
    public CompletableFuture<LegacyProfileImport.Result> importLegacyProfiles(List<LegacyProfileImport.Profile> profiles,Map<String,String> currentUi){
        return importLegacyProfiles("oneconfig-legacy-v1",profiles,currentUi);
    }
    /** Worker reads the exact named legacy LOCAL music files after main captures the current schema. */
    public CompletableFuture<LegacyProfileImport.Result> importLegacyProfiles(Path runDirectory,List<LegacyProfileImport.Profile> profiles,Map<String,String> currentUi){
        return importLegacyProfiles("oneconfig-legacy-v1",runDirectory,profiles,currentUi);
    }
    /** Retained detached UI/HUD-only overload; emits an explicit missing-run-directory baseline diagnostic. */
    public CompletableFuture<LegacyProfileImport.Result> importLegacyProfiles(String source,List<LegacyProfileImport.Profile> profiles,Map<String,String> currentUi){
        return importLegacyProfiles(source,null,profiles,currentUi);
    }
    /** Use a stable source identity when the host can migrate more than one old installation. */
    public CompletableFuture<LegacyProfileImport.Result> importLegacyProfiles(String source,Path runDirectory,List<LegacyProfileImport.Profile> profiles,Map<String,String> currentUi){
        final List<LegacyProfileImport.Profile> frozen;final Map<String,String> uiSnapshot;final Path legacyRun;final long ticket,owner;
        try{if(source==null||source.isBlank()||source.length()>512)throw new IllegalArgumentException("旧档案来源标识无效");frozen=List.copyOf(profiles);uiSnapshot=Map.copyOf(currentUi);legacyRun=runDirectory==null?null:runDirectory.toAbsolutePath().normalize();if(frozen.size()>512)throw new IllegalArgumentException("旧档案数量超过512");if(frozen.isEmpty())return CompletableFuture.completedFuture(new LegacyProfileImport.Result(List.of(),List.of(),List.of(),false));ticket=begin("迁移旧配置");owner=profileGeneration;}
        catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
        CompletableFuture<LegacyProfileImport.Result> result=new CompletableFuture<>();
        capture(ticket,active,"Migration baseline",uiSnapshot).thenCompose(baseline->work(ticket,()->{
            LegacyProfileImport.Result report=LegacyProfileImport.publish(files,source,frozen,baseline,()->valid(ticket)&&profileGeneration==owner,legacyRun);
            ProfileFiles.Listing loaded=null;
            try{if(valid(ticket)&&profileGeneration==owner)loaded=files.scan();}
            catch(Exception scanFailure){List<LegacyProfileImport.Issue> issues=new ArrayList<>(report.issues());issues.add(new LegacyProfileImport.Issue("","refresh-failed","新档案已按逐项结果发布，但目录刷新失败："+LibraryIo.message(scanFailure)));report=new LegacyProfileImport.Result(report.imported(),issues,report.notImported(),report.cancelled());}
            return new ImportBatch(report,loaded);
        })).whenComplete((batch,error)->main.execute(()->{
            if(error!=null){if(valid(ticket))finish(ticket,"迁移旧配置",error);result.completeExceptionally(LibraryIo.unwrap(error));return;}
            LegacyProfileImport.Result report=batch.result();
            if(valid(ticket)&&profileGeneration==owner){if(batch.listing()!=null)listing(batch.listing());finish(ticket,"迁移旧配置",null);long created=report.imported().stream().filter(item->!item.reused()).count(),music=report.imported().stream().filter(item->item.musicOrigin()==LegacyProfileImport.MusicOrigin.LEGACY_SCHEMA_FILTERED&&item.musicKeys()>0).count();status="旧配置迁移：新增 "+created+"，复用 "+(report.imported().size()-created)+"，未迁 "+report.notImported().size()+"；旧音乐过滤迁入 "+music+"，基线/保留 "+(report.imported().size()-music)+"（详见逐项报告）";}
            else if(!report.cancelled()){List<LegacyProfileImport.Issue> issues=new ArrayList<>(report.issues());issues.add(new LegacyProfileImport.Issue("","callback-expired","目标生命周期已改变；已落盘项保留在结果中，未更新当前列表或激活配置"));report=new LegacyProfileImport.Result(report.imported(),issues,report.notImported(),true);}
            result.complete(report);
        }));return result;
    }
    /** Freeze current host/UI/controls on main, then atomically save on worker. Await before disposing the service. */
    public CompletableFuture<Void> checkpoint(){
        require(active);String id=active,title=profiles.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow().name();long ticket=begin("保存当前配置");CompletableFuture<Void> result=new CompletableFuture<>();
        capture(ticket,id,title,ui.get()).thenCompose(snapshot->work(ticket,()->{files.write(id,snapshot,()->valid(ticket));return files.scan();})).whenComplete((loaded,error)->main.execute(()->{if(valid(ticket)){if(error==null)listing(loaded);finish(ticket,"保存当前配置",error);}if(error==null)result.complete(null);else result.completeExceptionally(LibraryIo.unwrap(error));}));return result;
    }
    public void close(){closed=true;generation++;profileGeneration++;busy=false;if(pendingRestore!=null)pendingRestore.cancel(false);}
}
