package com.muoniumplayer.configskija.host.library;

import com.muoniumplayer.configskija.model.Profiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Existing version-1 .properties archives remain readable. Empty ID has a real default snapshot. */
public final class ProfileFiles {
    public record Listing(List<Profiles.Profile> profiles,Map<String,String> icons,String active){}
    private final Path directory;
    public ProfileFiles(Path directory){this.directory=directory.toAbsolutePath().normalize();}
    public Path directory(){return directory;}
    public Path path(String id){if(id.isEmpty())return directory.resolve(".default.properties");if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("配置身份无效");return directory.resolve(id+".properties");}
    public Properties read(String id)throws IOException{Properties p=LibraryIo.read(path(id),LibraryIo.MAX_PROFILE_BYTES);if(!"1".equals(p.getProperty("version")))throw new IOException("不支持的本地配置版本");return p;}
    public boolean exists(String id){return Files.isRegularFile(path(id),LinkOption.NOFOLLOW_LINKS);}
    public void write(String id,Properties p,BooleanSupplier valid)throws IOException{Properties next=LibraryIo.copy(p);if(exists(id)){Properties previous=read(id);for(String key:previous.stringPropertyNames())if((key.startsWith("legacy.import.")||"shared".equals(next.getProperty("extra.controlPolicy"))&&key.startsWith("extra.controls."))&&!next.containsKey(key))next.setProperty(key,previous.getProperty(key));}LibraryIo.write(path(id),next,valid);}
    public void delete(String id,BooleanSupplier valid)throws IOException{if(id.isEmpty())throw new IllegalArgumentException("默认配置不能删除");if(valid.getAsBoolean())Files.delete(path(id));}
    public void active(String id,BooleanSupplier valid)throws IOException{if(!id.isEmpty())path(id);Properties state=new Properties();state.setProperty("version","1");state.setProperty("active",id);LibraryIo.write(directory.resolve(".state.properties"),state,valid);}
    public Listing scan()throws IOException{
        List<Profiles.Profile> profiles=new ArrayList<>();Map<String,String> icons=new HashMap<>();
        profiles.add(new Profiles.Profile("","Default",exists("")?Files.getLastModifiedTime(path("")).toMillis():0));
        if(exists(""))icons.put("",read("").getProperty("icon","profiles"));
        if(Files.isDirectory(directory))try(var paths=Files.list(directory)){
            for(Path p:paths.filter(file->file.getFileName().toString().endsWith(".properties")&&!file.getFileName().toString().startsWith(".")).toList()){
                String filename=p.getFileName().toString(),id=filename.substring(0,filename.length()-11);
                try{path(id);}catch(IllegalArgumentException invalid){continue;}
                Properties state=read(id);profiles.add(new Profiles.Profile(id,state.getProperty("name",id),Files.getLastModifiedTime(p).toMillis()));icons.put(id,state.getProperty("icon","profiles"));
            }
        }
        profiles.subList(1,profiles.size()).sort(Comparator.comparing(Profiles.Profile::name,String.CASE_INSENSITIVE_ORDER));String active="";Path state=directory.resolve(".state.properties");if(Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS)){Properties persisted=LibraryIo.read(state,8192);if(!"1".equals(persisted.getProperty("version")))throw new IOException("不支持的激活状态版本");active=persisted.getProperty("active","");String selected=active;if(profiles.stream().noneMatch(profile->profile.id().equals(selected)))active="";}return new Listing(List.copyOf(profiles),Map.copyOf(icons),active);
    }
}
