package com.muoniumplayer.configskija.host;

import com.muoniumplayer.configskija.model.Presets;
import com.muoniumplayer.configskija.host.library.*;
import com.muoniumplayer.core.preset.PresetService;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Cached catalog on the UI thread. Repository reads/writes and parsing run on the injected worker. */
public final class MuoniumPresets implements Presets,AutoCloseable {
    private record Catalog(List<StylePreset> cards,boolean canUndo){}
    private final PresetService service;
    private final PresetPanelSession session;
    private final PresetPanelSession.Clipboard clipboard;
    private final Executor worker,main;
    private List<StylePreset> catalog=List.of();
    private String status="";
    private boolean busy,canUndo;
    private volatile boolean closed;
    private volatile long generation,navigationGeneration;
    private LongSupplier profileGeneration=()->0;
    private long operationProfile;
    public MuoniumPresets(){this(PresetService.get(),LibraryExecutors.worker(),MultiThreadingUtil::runOnMainThread,new PresetPanelSession.Clipboard(){public String get(){return com.muoniumplayer.compat.mc.GuiScreen.getClipboardString();}public void set(String text){com.muoniumplayer.compat.mc.GuiScreen.setClipboardString(text);}});}
    public MuoniumPresets(PresetService service,Executor worker,Executor main,PresetPanelSession.Clipboard clipboard){this.service=Objects.requireNonNull(service);this.worker=Objects.requireNonNull(worker);this.main=Objects.requireNonNull(main);this.clipboard=Objects.requireNonNull(clipboard);session=new PresetPanelSession(service,clipboard);refresh();}
    public void profileGeneration(LongSupplier generation){profileGeneration=Objects.requireNonNull(generation);}
    public long navigationGeneration(){return navigationGeneration;}
    public boolean busy(){if(busy&&!valid(generation)){busy=false;generation++;}return busy;}
    public Path directory(){return service.repository().userDir().toPath();}
    public String page(){return session.page().name();}
    public String status(){return status;}
    public int changedKeys(){return session.page()==PresetPanelSession.Page.APPLY?session.previewChanges():-1;}
    public boolean previewOk(){return session.page()!=PresetPanelSession.Page.APPLY||!session.stateJson().get("previewFailed").getAsBoolean();}
    private StylePreset find(String id){return catalog.stream().filter(p->p.meta().id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("预设不存在："+id));}
    public List<Card> cards(){return catalog.stream().map(p->new Card(p.meta().id(),p.meta().name(),p.meta().description(),p.source()==StylePreset.Source.BUILTIN)).toList();}
    public List<Presets.Facet> facets(){
        List<Presets.Facet> out=new ArrayList<>();boolean save=session.page()==PresetPanelSession.Page.SAVE;
        for(com.muoniumplayer.core.preset.model.Facet.Group g:com.muoniumplayer.core.preset.model.Facet.Group.values())for(com.muoniumplayer.core.preset.model.Facet f:save?session.facetsAvailable(g):session.facetsOf(session.target(),g))out.add(new Presets.Facet(f.name(),f.displayName(),g.name(),save?session.isSaveSelected(f):session.isSelected(f),f.optIn()));return List.copyOf(out);
    }
    public String facetDescription(String id){return facet(id).description();}
    private com.muoniumplayer.core.preset.model.Facet facet(String id){for(com.muoniumplayer.core.preset.model.Facet f:com.muoniumplayer.core.preset.model.Facet.values())if(f.name().equals(id)||f.id().equals(id))return f;throw new IllegalArgumentException("未知模板分面："+id);}
    public String cardSummary(String id){StylePreset p=find(id);List<String> names=p.facets().stream().map(com.muoniumplayer.core.preset.model.Facet::displayName).toList();if(names.isEmpty())return "这个版本没有可套用的内容";if(names.size()<=3)return String.join(" · ",names);boolean look=p.touches(com.muoniumplayer.core.preset.model.Facet.Group.APPEARANCE),audio=p.touches(com.muoniumplayer.core.preset.model.Facet.Group.AUDIO);return (look&&audio?"外观 + 音效":look?"外观":"音效")+" · "+names.size()+" 个部分";}
    public List<String> preview(){return session.previewLines();}
    public String targetName(){return session.target()==null?"":session.target().meta().name();}
    public boolean canUndo(){return canUndo;}
    public void choose(String id){if(busy()||closed)return;navigationGeneration++;session.enterApply(find(id));status="";}
    public void main(){if(closed)return;navigationGeneration++;session.enterMain();}
    public void savePage(){if(busy()||closed)return;navigationGeneration++;session.enterSave();status="";}
    public void toggle(String id){if(busy()||closed)return;com.muoniumplayer.core.preset.model.Facet f=facet(id);if(session.page()==PresetPanelSession.Page.SAVE)session.toggleSave(f);else session.toggle(f);}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private boolean valid(long ticket){return !closed&&generation==ticket&&operationProfile==profileGeneration.getAsLong();}
    private long begin(String action){if(closed)throw new IllegalStateException("预设服务已关闭");if(busy())throw new IllegalStateException("另一个模板操作尚未完成");busy=true;status=action+"…";operationProfile=profileGeneration.getAsLong();return ++generation;}
    private <T> CompletableFuture<T> work(long ticket,Work<T> task){return CompletableFuture.supplyAsync(()->{if(!valid(ticket))throw new CancellationException("模板操作已过期");try{return task.run();}catch(Exception error){throw new CompletionException(error);}},worker);}
    private Catalog scan(){List<StylePreset> all=new ArrayList<>(service.builtins());all.addAll(service.userPresets());return new Catalog(List.copyOf(all),service.canUndo());}
    private void loaded(Catalog data){catalog=data.cards();canUndo=data.canUndo();}
    private void failed(long ticket,String action,Throwable error){if(!valid(ticket))return;busy=false;status=action+"失败："+LibraryIo.message(error);}
    public void refresh(){if(closed||busy())return;long ticket=begin("读取模板目录");work(ticket,this::scan).whenComplete((data,error)->main.execute(()->{if(!valid(ticket))return;if(error!=null)failed(ticket,"读取模板目录",error);else{loaded(data);busy=false;status="";}}));}
    public void save(String rawName,boolean copy){
        String name=rawName==null?"":rawName.strip();if(name.isEmpty())throw new IllegalArgumentException("先起个名字");Set<com.muoniumplayer.core.preset.model.Facet> selected=EnumSet.noneOf(com.muoniumplayer.core.preset.model.Facet.class);for(Presets.Facet f:facets())if(f.selected())selected.add(facet(f.id()));if(selected.isEmpty())throw new IllegalArgumentException("至少勾选一个部分");
        long ticket=begin("保存模板");StylePreset captured;try{captured=service.snapshot(name,selected);}catch(RuntimeException error){failed(ticket,"读取当前设置",error);return;}
        work(ticket,()->{StylePreset saved=service.repository().saveUser(captured);String share=copy?service.shareText(saved):"";return new Object[]{scan(),share};}).whenComplete((data,error)->main.execute(()->{if(!valid(ticket))return;if(error!=null){failed(ticket,"保存模板",error);return;}loaded((Catalog)data[0]);busy=false;session.enterMain();navigationGeneration++;status="已保存「"+name+"」";if(copy){clipboard.set((String)data[1]);status+="并复制分享码";}}));
    }
    public void copy(String id){StylePreset preset=find(id);long ticket=begin("生成分享码");work(ticket,()->service.shareText(preset)).whenComplete((share,error)->main.execute(()->{if(!valid(ticket))return;if(error!=null)failed(ticket,"生成分享码",error);else{busy=false;clipboard.set(share);status="已复制「"+preset.meta().name()+"」的分享码";}}));}
    public void delete(String id){StylePreset preset=find(id);if(preset.source()!=StylePreset.Source.USER)throw new IllegalArgumentException("内置模板不能删除");long ticket=begin("删除模板");work(ticket,()->{if(!service.delete(preset))throw new IllegalStateException("模板文件未删除");return scan();}).whenComplete((data,error)->main.execute(()->{if(!valid(ticket))return;if(error!=null)failed(ticket,"删除模板",error);else{loaded(data);busy=false;status="已删除「"+preset.meta().name()+"」";}}));}
    public void importText(String text){importContent(()->text,()->true);}
    public CompletableFuture<Void> importFile(Path file,BooleanSupplier accepted){return importContent(()->{if(Files.isSymbolicLink(file))throw new java.io.IOException("不接受符号链接模板");try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){int max=PresetLimits.MAX_JSON_CHARS*4;byte[] bytes=in.readNBytes(max+1);if(bytes.length>max)throw new java.io.IOException("模板文件过大");return new String(bytes,StandardCharsets.UTF_8);}},accepted);}
    private CompletableFuture<Void> importContent(Work<String> text,BooleanSupplier accepted){
        long ticket=begin("导入模板");long navigation=navigationGeneration;CompletableFuture<Void> result=new CompletableFuture<>();
        work(ticket,()->{String content=text.run();StylePreset parsed=service.parseText(content);if(!accepted.getAsBoolean()||!valid(ticket))throw new CancellationException("导入操作已过期");StylePreset saved=service.repository().saveUser(parsed.withMeta(parsed.meta().withId("user:"+UUID.randomUUID())));return new Object[]{saved,scan()};}).whenComplete((data,error)->main.execute(()->{if(!valid(ticket)){result.completeExceptionally(new CancellationException("导入操作已过期"));return;}if(error!=null){failed(ticket,"导入模板",error);result.completeExceptionally(LibraryIo.unwrap(error));return;}loaded((Catalog)data[1]);busy=false;StylePreset imported=(StylePreset)data[0];status="已导入「"+imported.meta().name()+"」";if(navigation==navigationGeneration&&accepted.getAsBoolean()){session.enterApply(imported);navigationGeneration++;}result.complete(null);}));return result;
    }
    public void apply(){if(session.target()==null)return;long ticket=begin("套用模板");main.execute(()->{if(!valid(ticket))return;try{session.applyTarget();status=session.statusText();}catch(RuntimeException error){failed(ticket,"套用模板",error);return;}reloadAfterAction(ticket);});}
    public void undo(){long ticket=begin("撤销套用");main.execute(()->{if(!valid(ticket))return;try{CompatReport report=service.undo();status=report==null?"还没有可以撤销的套用":report.hasFailures()?String.join("；",report.failures()):"已撤销上一次套用";}catch(RuntimeException error){failed(ticket,"撤销套用",error);return;}reloadAfterAction(ticket);});}
    private void reloadAfterAction(long ticket){navigationGeneration++;work(ticket,this::scan).whenComplete((data,error)->main.execute(()->{if(!valid(ticket))return;if(error!=null)failed(ticket,"刷新模板目录",error);else{loaded(data);busy=false;}}));}
    public void close(){closed=true;generation++;navigationGeneration++;busy=false;}
}
