package com.muoniumplayer.configskija.host.library;

import com.google.gson.*;
import com.muoniumplayer.configskija.ui.hud.HudDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Publishes converted UI/HUD plus field-filtered LOCAL music; never copies a legacy directory. */
public final class LegacyProfileImport {
    private static final Set<String> ICONS=Set.of("profiles","star","settings","cog","paintbrush","keyboard","hud","combat","qol","cloud");
    private LegacyProfileImport(){}
    public record Profile(String legacyName,Map<String,String> preferences,HudDocument hud){
        public Profile{Objects.requireNonNull(legacyName);preferences=Map.copyOf(preferences);Objects.requireNonNull(hud);}
    }
    public enum MusicOrigin{CURRENT_CAPTURE_BASELINE,LEGACY_SCHEMA_FILTERED}
    public record Imported(String legacyName,String id,String name,MusicOrigin musicOrigin,String musicSource,boolean reused,int musicKeys){
        public Imported(String legacyName,String id,String name,MusicOrigin musicOrigin,String musicSource,boolean reused){this(legacyName,id,name,musicOrigin,musicSource,reused,0);}
    }
    private record MusicChoice(String json,MusicOrigin origin,String source,String outcome,int keys){}
    public record Issue(String legacyName,String code,String detail){}
    public record Result(List<Imported> imported,List<Issue> issues,List<String> notImported,boolean cancelled){
        public Result{imported=List.copyOf(imported);issues=List.copyOf(issues);notImported=List.copyOf(notImported);}
        public Map<String,String> oldNameToId(){Map<String,String> ids=new LinkedHashMap<>();for(Imported item:imported)ids.put(item.legacyName(),item.id());return Collections.unmodifiableMap(ids);}
    }
    public static Result publish(ProfileFiles files,String source,List<Profile> incoming,Properties baseline,BooleanSupplier valid)throws IOException{
        return publish(files,source,incoming,baseline,valid,null);
    }
    public static Result publish(ProfileFiles files,String source,List<Profile> incoming,Properties baseline,BooleanSupplier valid,Path runDirectory)throws IOException{
        Objects.requireNonNull(files);Objects.requireNonNull(source);if(source.isBlank()||source.length()>512)throw new IllegalArgumentException("旧档案来源标识无效");if(incoming.size()>512)throw new IllegalArgumentException("旧档案数量超过512");
        JsonObject current=OldMusicLoader.envelope(baseline.getProperty("music",""));String baselineJson=current.toString(),stamp=Instant.now().toString();OldMusicLoader loader=runDirectory==null?null:new OldMusicLoader(baselineJson);
        List<Imported> imported=new ArrayList<>();List<Issue> issues=new ArrayList<>();List<String> missing=new ArrayList<>();
        Set<String> seen=new HashSet<>(),titles=new HashSet<>();Map<String,Properties> prior=new LinkedHashMap<>();Map<String,String> priorIds=new LinkedHashMap<>();
        ProfileFiles.Listing existing=files.scan();for(var entry:existing.profiles()){
            titles.add(entry.name().toLowerCase(Locale.ROOT));if(!files.exists(entry.id()))continue;Properties stored=files.read(entry.id());
            if(source.equals(stored.getProperty("legacy.import.source"))){String legacy=stored.getProperty("legacy.import.name");if(legacy!=null){if(priorIds.putIfAbsent(legacy,entry.id())!=null)throw new IOException("迁移记录中同一旧档案存在多个目标，拒绝猜测");prior.put(legacy,stored);}}
        }
        boolean cancelled=false;
        for(int i=0;i<incoming.size();i++){
            Profile snapshot=incoming.get(i);String legacy=snapshot.legacyName();
            if(!valid.getAsBoolean()){cancelled=true;for(int j=i;j<incoming.size();j++)missing.add(incoming.get(j).legacyName());issues.add(new Issue(legacy,"cancelled","迁移已关闭或目标配置代次已改变；尚未发布的旧档案保留原状"));break;}
            if(!seen.add(legacy)){missing.add(legacy);issues.add(new Issue(legacy,"duplicate-legacy-name","输入含同名旧档案，无法建立一对一映射；重复项未导入"));continue;}
            if(legacy.length()>256||legacy.chars().anyMatch(Character::isISOControl)){missing.add(legacy);issues.add(new Issue(legacy,"invalid-legacy-name","旧档案名称过长或含控制字符；未导入"));continue;}
            Properties previous=prior.get(legacy);if(previous!=null){
                if(!"1".equals(previous.getProperty("legacy.import.version"))){missing.add(legacy);issues.add(new Issue(legacy,"invalid-import-provenance","既有迁移记录版本无法确认，未覆盖该档案"));continue;}
                MusicOrigin origin;try{origin=MusicOrigin.valueOf(previous.getProperty("legacy.import.musicOrigin","CURRENT_CAPTURE_BASELINE"));}catch(IllegalArgumentException unknown){missing.add(legacy);issues.add(new Issue(legacy,"invalid-import-provenance","既有迁移记录来源不完整，未覆盖该档案"));continue;}
                int copied=integer(previous.getProperty("legacy.import.musicKeys","0"));String musicSource=previous.getProperty("legacy.import.musicSource","unknown");
                if(loader!=null&&origin==MusicOrigin.CURRENT_CAPTURE_BASELINE)try{
                    MusicChoice recovered=choose(loader,runDirectory,legacy,baselineJson,issues);
                    if(recovered.origin()==MusicOrigin.LEGACY_SCHEMA_FILTERED){
                        String fingerprint=previous.getProperty("legacy.import.musicFingerprint","");
                        if(!fingerprint.isEmpty()&&fingerprint.equals(fingerprint(previous.getProperty("music","")))){
                            previous.setProperty("music",recovered.json());provenance(previous,recovered);files.write(priorIds.get(legacy),previous,valid);origin=recovered.origin();musicSource=recovered.source();copied=recovered.keys();issues.add(new Issue(legacy,"legacy-music-baseline-upgraded","既有基线尚未修改，已就地补入字段过滤后的旧音乐，UUID保持不变"));
                        }else issues.add(new Issue(legacy,"existing-music-kept","已有档案音乐已改动或旧迁移未保存基线指纹，保留用户状态；本次未覆盖，可用不同source创建独立迁移档案"));
                    }
                }catch(OldMusicLoader.Rejected rejected){for(var issue:rejected.diagnostics())issues.add(new Issue(legacy,issue.code(),issue.path()+"："+issue.detail()));}
                catch(CancellationException stale){cancelled=true;for(int j=i;j<incoming.size();j++)missing.add(incoming.get(j).legacyName());break;}
                catch(IOException failure){issues.add(new Issue(legacy,"legacy-music-upgrade-failed","既有档案未覆盖，旧音乐补迁失败："+LibraryIo.message(failure)));}
                imported.add(new Imported(legacy,priorIds.get(legacy),previous.getProperty("name",legacy),origin,musicSource,true,copied));issues.add(new Issue(legacy,"already-imported","复用既有UUID与UI/HUD，不覆盖后续用户修改"));continue;
            }
            String id=UUID.randomUUID().toString(),title=title(legacy,titles);
            try{
            MusicChoice choice=choose(loader,runDirectory,legacy,baselineJson,issues);
            Properties converted=LibraryIo.copy(baseline);converted.setProperty("name",title);converted.setProperty("music",choice.json());
            Map<String,String> ui=new LinkedHashMap<>();for(String key:baseline.stringPropertyNames())if(key.startsWith("ui."))ui.put(key.substring(3),baseline.getProperty(key));ui.putAll(snapshot.preferences());
            ui.put("profile.active",id);ui.put("hud.document",snapshot.hud().encode());for(String key:new ArrayList<>(converted.stringPropertyNames()))if(key.startsWith("ui."))converted.remove(key);ui.forEach((key,value)->converted.setProperty("ui."+key,value));
            String icon=snapshot.preferences().getOrDefault("profiles.icon."+legacy,ui.getOrDefault("profiles.icon."+legacy,"profiles"));if(!ICONS.contains(icon)){issues.add(new Issue(legacy,"unsupported-profile-icon","旧图标不在已核实的十种图标内；使用profiles并保留原迁移输入"));icon="profiles";}converted.setProperty("icon",icon);
            converted.setProperty("legacy.import.version","1");converted.setProperty("legacy.import.source",source);converted.setProperty("legacy.import.name",legacy);provenance(converted,choice);converted.setProperty("legacy.import.created",stamp);converted.setProperty("legacy.import.scope","converted-ui-hud; current-schema-filtered-music-or-explicit-baseline; current-controls-baseline");
            files.write(id,converted,valid);imported.add(new Imported(legacy,id,title,choice.origin(),choice.source(),false,choice.keys()));issues.add(new Issue(legacy,"legacy-controls-not-imported","旧原生键位、旧Profile快捷键和其它mod配置目录未在此批次迁移；新档案controls沿用导入时的可用当前基线"));}
            catch(OldMusicLoader.Rejected rejected){missing.add(legacy);for(var issue:rejected.diagnostics())issues.add(new Issue(legacy,issue.code(),issue.path()+"："+issue.detail()));}
            catch(CancellationException stale){cancelled=true;missing.add(legacy);for(int j=i+1;j<incoming.size();j++)missing.add(incoming.get(j).legacyName());issues.add(new Issue(legacy,"cancelled","发布前目标已过期；该档案未导入，先前成功项保留并在结果中列出"));break;}
            catch(IOException|RuntimeException failure){missing.add(legacy);issues.add(new Issue(legacy,"profile-write-failed","新档案写入失败："+LibraryIo.message(failure)));}
        }
        Map<String,String> mapped=new LinkedHashMap<>();for(Imported item:imported)mapped.put(item.legacyName(),item.id());
        for(Imported item:imported){
            if(item.reused())continue;
            if(!valid.getAsBoolean()){cancelled=true;issues.add(new Issue(item.legacyName(),"metadata-map-cancelled","档案已保存，但关闭/目标变化使UI元数据重映射未完成；主线程应按返回UUID映射复核"));break;}
            try{Properties saved=files.read(item.id());boolean changed=false;
                for(var mapping:mapped.entrySet())for(String prefix:List.of("ui.profiles.favorite.","ui.profiles.icon.")){String old=prefix+mapping.getKey(),next=prefix+mapping.getValue();String value=saved.getProperty(old);if(value==null)continue;if(!old.equals(next)){saved.setProperty(next,value);if(!mapped.containsValue(mapping.getKey()))saved.remove(old);changed=true;}}
                if(changed)files.write(item.id(),saved,valid);
            }catch(CancellationException stale){cancelled=true;issues.add(new Issue(item.legacyName(),"metadata-map-cancelled","档案已保存，UI元数据重映射发布前过期"));break;}
            catch(IOException|RuntimeException failure){issues.add(new Issue(item.legacyName(),"metadata-map-incomplete","档案已保存，但UI收藏/图标键映射未完成："+LibraryIo.message(failure)));}
        }
        return new Result(imported,issues,missing,cancelled);
    }
    private static String title(String legacy,Set<String> used){String base=legacy.isEmpty()?"Default (legacy)":legacy.strip();if(base.isEmpty())base="Legacy profile";if(base.length()>65){int end=Character.isHighSurrogate(base.charAt(64))?64:65;base=base.substring(0,end);}String title=base;int index=2;while(!used.add(title.toLowerCase(Locale.ROOT)))title=base+" (legacy "+index+++")";return title;}
    private static MusicChoice choose(OldMusicLoader loader,Path run,String legacy,String baseline,List<Issue> issues)throws IOException{
        if(loader==null){issues.add(new Issue(legacy,"current-music-baseline","未提供旧run目录；music使用当前capture基线，没有恢复旧音乐"));return new MusicChoice(baseline,MusicOrigin.CURRENT_CAPTURE_BASELINE,"PresetConfigBridge.captureLocalState at import time","RUN_DIRECTORY_NOT_PROVIDED",0);}
        OldMusicLoader.Result loaded=loader.load(run,legacy);for(var issue:loaded.diagnostics())issues.add(new Issue(legacy,issue.code(),issue.path()+"："+issue.detail()));
        MusicOrigin origin=loaded.origin()==OldMusicLoader.Origin.LEGACY_SCHEMA_FILTERED?MusicOrigin.LEGACY_SCHEMA_FILTERED:MusicOrigin.CURRENT_CAPTURE_BASELINE;
        String source=origin==MusicOrigin.LEGACY_SCHEMA_FILTERED?"profiles/"+legacy+"/"+OldMusicLoader.FILE_NAME+"; current-capture schema filtered":"PresetConfigBridge.captureLocalState at import time";
        return new MusicChoice(loaded.envelope(),origin,source,loaded.origin().name(),loaded.copiedKeys());
    }
    private static void provenance(Properties p,MusicChoice choice){p.setProperty("legacy.import.musicOrigin",choice.origin().name());p.setProperty("legacy.import.musicSource",choice.source());p.setProperty("legacy.import.musicOutcome",choice.outcome());p.setProperty("legacy.import.musicKeys",Integer.toString(choice.keys()));p.setProperty("legacy.import.musicFingerprint",fingerprint(choice.json()));}
    private static String fingerprint(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("JDK SHA-256 unavailable",unavailable);}}
    private static int integer(String raw){try{return Math.max(0,Integer.parseInt(raw));}catch(NumberFormatException invalid){return 0;}}
}
