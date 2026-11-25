package com.muoniumplayer.configskija.host.library;

import com.google.gson.*;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Blocking read-only migration of one exact legacy LOCAL music file. Call only on a worker. */
public final class OldMusicLoader {
    public static final String FILE_NAME="muonium-music-state.json";
    public static final Set<String> DOMAIN_IDS=Set.of("hud","theme","foliaBg","font","transparent","cine","acoustics","viper","spin");
    public static final long MAX_FILE_BYTES=(long)PresetLimits.MAX_JSON_CHARS*4;
    public enum Origin{LEGACY_SCHEMA_FILTERED,CURRENT_ROOT_BASELINE,CURRENT_MISSING_BASELINE}
    public record Diagnostic(String code,String path,String detail){}
    public record Result(String envelope,Origin origin,int copiedKeys,List<Diagnostic> diagnostics){
        public Result{diagnostics=List.copyOf(diagnostics);}
        @Override public String toString(){return "OldMusicResult[origin="+origin+", copiedKeys="+copiedKeys+", diagnostics="+diagnostics.size()+", content omitted]";}
    }
    public static final class Rejected extends IOException{
        private final List<Diagnostic> diagnostics;
        public Rejected(String code,String path,String reason){super(reason);diagnostics=List.of(new Diagnostic(code,path,reason));}
        public List<Diagnostic> diagnostics(){return diagnostics;}
    }
    private record Watched(Path path,BasicFileAttributes attributes){}
    private static final class Projection{
        final List<Diagnostic> diagnostics=new ArrayList<>();int copied,visited;
        void issue(String code,String path,String detail){if(diagnostics.size()<128)diagnostics.add(new Diagnostic(code,path,detail));else if(diagnostics.size()==128)diagnostics.add(new Diagnostic("more-music-diagnostics","state","额外字段诊断已合并；字段值不写入报告"));}
    }
    private final JsonObject baseline;
    public OldMusicLoader(String currentCapture){
        baseline=envelope(currentCapture);JsonObject state=baseline.getAsJsonObject("state");
        for(var item:state.entrySet())if(!DOMAIN_IDS.contains(item.getKey())||!item.getValue().isJsonObject())throw new IllegalArgumentException("当前capture不是已核实九域的music schema");
    }
    public Result load(Path runDirectory,String profile)throws IOException{
        Objects.requireNonNull(runDirectory);Objects.requireNonNull(profile);
        if(profile.isEmpty())return fallback(Origin.CURRENT_ROOT_BASELINE,"legacy-root-music-baseline","旧root不写音乐信封；使用当前主项目capture作为根配置基线");
        if(profile.length()>256||profile.equals(".")||profile.equals("..")||profile.endsWith(".")||profile.endsWith(" ")||profile.chars().anyMatch(ch->ch<32||ch==127||"\\/:".indexOf(ch)>=0))throw new Rejected("invalid-legacy-music-path","profiles/[invalid]","旧Profile名称不能构成安全单级路径");
        Path run=runDirectory.toAbsolutePath().normalize(),target=run.resolve("profiles").resolve(profile).resolve(FILE_NAME).normalize();
        if(!target.startsWith(run)||target.getNameCount()!=run.getNameCount()+3)throw new Rejected("invalid-legacy-music-path","profiles/[invalid]","音乐信封路径越出旧run目录");
        String relative="profiles/"+profile+"/"+FILE_NAME;
        List<Watched> watched=new ArrayList<>();Path cursor=target.getRoot();boolean absent=false;
        for(Path segment:target){
            cursor=cursor.resolve(segment);BasicFileAttributes attributes;
            try{attributes=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException missing){absent=true;break;}
            catch(IOException|SecurityException failure){throw new Rejected("legacy-music-read-denied",relative,"不能验证旧音乐信封的只读路径");}
            boolean file=cursor.equals(target);verify(cursor,attributes,file,relative);watched.add(new Watched(cursor,attributes));
        }
        if(absent){verifyUnchanged(watched,relative);return fallback(Origin.CURRENT_MISSING_BASELINE,"missing-legacy-music-envelope","该旧Profile缺少音乐信封，沿用当前capture基线；未宣称恢复旧音乐");}
        BasicFileAttributes before=watched.get(watched.size()-1).attributes();if(before.size()>MAX_FILE_BYTES)throw new Rejected("legacy-music-too-large",relative,"旧音乐信封超过有界读取上限");
        byte[] bytes;
        try(SeekableByteChannel channel=Files.newByteChannel(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteArrayOutputStream content=new ByteArrayOutputStream((int)Math.min(before.size(),65536));ByteBuffer buffer=ByteBuffer.allocate(8192);long total=0;
            while(channel.read(buffer)!=-1){buffer.flip();total+=buffer.remaining();if(total>MAX_FILE_BYTES)throw new Rejected("legacy-music-too-large",relative,"旧音乐信封读取时超过大小上限");content.write(buffer.array(),buffer.position(),buffer.remaining());buffer.clear();}bytes=content.toByteArray();
        }catch(Rejected rejected){throw rejected;}catch(IOException|SecurityException failure){throw new Rejected("legacy-music-read-denied",relative,"旧音乐信封无法以NOFOLLOW只读方式打开");}
        verifyUnchanged(watched,relative);
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException malformed){throw new Rejected("invalid-legacy-music-encoding",relative,"旧音乐信封不是有效UTF-8");}
        if(text.length()>PresetLimits.MAX_JSON_CHARS)throw new Rejected("legacy-music-too-large",relative,"旧音乐信封字符数超过当前协议上限");
        if(PresetLimits.nestingDepth(text)>PresetLimits.MAX_DEPTH)throw new Rejected("legacy-music-too-deep",relative,"旧音乐信封嵌套深度超过当前协议上限");
        JsonObject legacy;try{legacy=rawEnvelope(text);}catch(RuntimeException malformed){throw new Rejected("invalid-legacy-music-envelope",relative,"旧音乐信封JSON或format/version/localOnly/state header无效");}
        Projection projection=new Projection();for(String key:legacy.keySet())if(!Set.of("format","version","localOnly","state").contains(key))projection.issue("unknown-music-header",safe(key),"未知信封顶层字段已丢弃");
        JsonObject state=baseline.getAsJsonObject("state").deepCopy(),schema=baseline.getAsJsonObject("state"),incoming=legacy.getAsJsonObject("state");
        for(var item:incoming.entrySet()){
            String domain=item.getKey();if(!DOMAIN_IDS.contains(domain)||!schema.has(domain)){projection.issue("unknown-music-domain",safe(domain),"未知或当前capture不存在的域已丢弃");continue;}
            state.add(domain,project(item.getValue(),schema.get(domain),domain,projection));
        }
        for(String domain:schema.keySet())if(!incoming.has(domain))projection.issue("missing-music-domain",domain,"旧信封未包含当前域，该域保留capture基线");
        JsonObject result=header(state);if(projection.copied==0)projection.issue("no-compatible-music-keys","state","信封有效但没有可迁入字段；所有设置保留当前基线，未宣称恢复旧音乐");
        String encoded=result.toString();if(encoded.length()>PresetLimits.MAX_JSON_CHARS)throw new Rejected("filtered-music-too-large",relative,"过滤合并后的音乐状态超过当前协议上限，未发布");return new Result(encoded,Origin.LEGACY_SCHEMA_FILTERED,projection.copied,projection.diagnostics);
    }
    private Result fallback(Origin origin,String code,String detail){return new Result(baseline.toString(),origin,0,List.of(new Diagnostic(code,"state",detail)));}
    private static void verify(Path path,BasicFileAttributes attributes,boolean file,String relative)throws Rejected{
        if(attributes.isSymbolicLink()||Files.isSymbolicLink(path)||file&&!attributes.isRegularFile()||!file&&!attributes.isDirectory())throw new Rejected("unsafe-legacy-music-path",relative,"旧路径含链接、非目录父级或非普通文件，已拒绝");
        try{if(!path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)))throw new Rejected("unsafe-legacy-music-path",relative,"旧路径解析经过链接或重解析跳转，已拒绝");}
        catch(Rejected failure){throw failure;}catch(IOException failure){throw new Rejected("unsafe-legacy-music-path",relative,"无法确认旧路径的真实位置");}
    }
    private static void verifyUnchanged(List<Watched> watched,String relative)throws Rejected{
        for(int i=0;i<watched.size();i++){Watched previous=watched.get(i);BasicFileAttributes now;
            try{now=Files.readAttributes(previous.path(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(IOException|SecurityException failure){throw new Rejected("legacy-music-path-changed",relative,"只读期间旧文件或路径发生变化");}
            boolean file=i==watched.size()-1&&previous.attributes().isRegularFile();verify(previous.path(),now,file,relative);
            if(!Objects.equals(now.fileKey(),previous.attributes().fileKey())||file&&(now.size()!=previous.attributes().size()||!now.lastModifiedTime().equals(previous.attributes().lastModifiedTime())))throw new Rejected("legacy-music-path-changed",relative,"只读期间旧文件或路径身份改变，结果未发布");
        }
    }
    private static JsonElement project(JsonElement incoming,JsonElement schema,String path,Projection p){
        if(++p.visited>PresetLimits.MAX_DOMAINS*PresetLimits.MAX_KEYS_PER_DOMAIN){p.issue("music-schema-budget",path,"字段投影预算已耗尽，保留当前基线");return schema.deepCopy();}
        if(schema.isJsonObject()){
            if(!incoming.isJsonObject())return mismatch(schema,path,p);JsonObject out=schema.getAsJsonObject().deepCopy(),object=incoming.getAsJsonObject(),known=schema.getAsJsonObject();
            for(var entry:object.entrySet()){String key=entry.getKey();if(!known.has(key)){p.issue("unknown-music-key",path+"."+safe(key),"当前capture schema不存在该键，已丢弃");continue;}out.add(key,project(entry.getValue(),known.get(key),path+"."+key,p));}
            for(String key:known.keySet())if(!object.has(key))p.issue("missing-music-key",path+"."+key,"旧信封缺少该字段，保留当前基线");return out;
        }
        if(schema.isJsonArray()){
            if(!incoming.isJsonArray())return mismatch(schema,path,p);JsonArray old=incoming.getAsJsonArray(),known=schema.getAsJsonArray();
            if(old.size()>PresetLimits.MAX_KEYS_PER_DOMAIN){p.issue("music-array-too-large",path,"旧数组过长，保留当前基线");return schema.deepCopy();}
            if(known.isEmpty()){if(!old.isEmpty()){p.issue("untyped-music-array",path,"当前空数组不能证明旧元素类型，保留当前基线");return schema.deepCopy();}p.copied++;return new JsonArray();}
            int length=Math.min(old.size(),known.size());if(old.size()>known.size())p.issue("unknown-music-array-index",path,"旧数组超出当前capture已有下标，额外元素已丢弃");
            JsonArray out=new JsonArray();for(int i=0;i<length;i++)out.add(project(old.get(i),known.get(i),path+"["+i+"]",p));if(old.isEmpty())p.copied++;return out;
        }
        if(schema.isJsonNull()){if(!incoming.isJsonNull())return mismatch(schema,path,p);p.copied++;return JsonNull.INSTANCE;}
        if(!incoming.isJsonPrimitive())return mismatch(schema,path,p);JsonPrimitive value=incoming.getAsJsonPrimitive(),expected=schema.getAsJsonPrimitive();
        if(expected.isBoolean()&&!value.isBoolean()||expected.isNumber()&&(!value.isNumber()||!Double.isFinite(value.getAsDouble()))||expected.isString()&&!value.isString())return mismatch(schema,path,p);
        p.copied++;return value.deepCopy();
    }
    private static JsonElement mismatch(JsonElement schema,String path,Projection p){p.issue("music-type-mismatch",path,"旧字段类型与当前capture schema不同，保留当前基线");return schema.deepCopy();}
    private static String safe(String key){return key.matches("[A-Za-z0-9_.-]{1,80}")?key:"[unknown-key]";}
    public static JsonObject envelope(String text){return header(rawEnvelope(text).getAsJsonObject("state").deepCopy());}
    private static JsonObject rawEnvelope(String text){if(text==null||text.length()>PresetLimits.MAX_JSON_CHARS||PresetLimits.nestingDepth(text)>PresetLimits.MAX_DEPTH)throw new IllegalArgumentException("music envelope bounds");JsonElement parsed=JsonParser.parseString(text);if(!parsed.isJsonObject())throw new IllegalArgumentException("music envelope object");JsonObject o=parsed.getAsJsonObject();if(!o.has("format")||!o.get("format").isJsonPrimitive()||!o.getAsJsonPrimitive("format").isString()||!"muonium.profile.music".equals(o.get("format").getAsString())||!o.has("version")||!o.get("version").isJsonPrimitive()||!o.getAsJsonPrimitive("version").isNumber()||o.get("version").getAsDouble()!=1||!o.has("localOnly")||!o.get("localOnly").isJsonPrimitive()||!o.getAsJsonPrimitive("localOnly").isBoolean()||!o.get("localOnly").getAsBoolean()||!o.has("state")||!o.get("state").isJsonObject())throw new IllegalArgumentException("music envelope header");return o;}
    private static JsonObject header(JsonObject state){JsonObject result=new JsonObject();result.addProperty("format","muonium.profile.music");result.addProperty("version",1);result.addProperty("localOnly",true);result.add("state",state);return result;}
}
