package com.muoniumplayer.configskija.host.migration;

import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.hud.HudDocument;
import com.muoniumplayer.configskija.ui.hud.HudNode;
import com.muoniumplayer.configskija.ui.pages.AppearanceSchema;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Reads the verified legacy UI schema only. Never writes, deletes, moves or hashes any legacy file. */
public final class LegacyMigration {
    private LegacyMigration() {}
    public static final String MARKER = "migration.oneconfig.v1";
    private static final int MAX_FILE = 4*1024*1024, MAX_FILES=2048;
    private static final Set<String> SAFE_TYPES=Set.of("text","date","time","icon");
    private static final String TEXT="org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple";
    private static final String DATETIME="org.polyfrost.oneconfig.api.hud.v1.TextHud$DateTime";
    private static final Set<String> BOOLEAN_HUD=Set.of("staticWidth","textBold","textItalic","textUnderline","useGuiScale","hidden","showBackground","bgChroma","mergeBackground","mergeDiagonally","keepBgWhenHidden","textChroma","showShadow","fullShadow","shadowChroma","showInF3","showInTab","showInScreens","showInChat","brackets");
    private static final Set<String> NUMBER_HUD=Set.of("staticW","staticH","padTop","padBottom","padLeft","padRight","textScale","textAlign","caseType","customScale","bgChromaSpeed","bgRadius","textChromaSpeed","shadowChromaSpeed","shadowOffsetX","shadowOffsetY","toggleKey","showKey");
    private static final List<String> SECTIONS=List.of("TopLeft","TopCenter","TopRight","CenterLeft","Center","CenterRight","BottomLeft","BottomCenter","BottomRight");

    /** Viewport dimensions use layout units; guiScale and physicalPerUnit are physical pixels per respective unit. */
    public record Request(Path runDirectory,double viewportWidth,double viewportHeight,double guiScale,double physicalPerUnit,
                          Map<String,String> hudTypes,Set<String> knownModIds,boolean includeProfiles) {
        public Request {
            runDirectory=runDirectory.toAbsolutePath().normalize();
            if(!Double.isFinite(viewportWidth)||!Double.isFinite(viewportHeight)||!Double.isFinite(guiScale)||!Double.isFinite(physicalPerUnit)||!Double.isFinite(guiScale/physicalPerUnit)||guiScale/physicalPerUnit<=0||viewportWidth<=0||viewportHeight<=0||guiScale<=0||physicalPerUnit<=0)throw new IllegalArgumentException("Capture a valid UI viewport before migration");
            hudTypes=Map.copyOf(hudTypes);knownModIds=Set.copyOf(knownModIds);
            if(hudTypes.values().stream().anyMatch(type->!SAFE_TYPES.contains(type)))throw new IllegalArgumentException("Unregistered destination HUD type");
        }
        /** Compatibility: an unspecified viewport axis is the original GUI axis. */
        public Request(Path root,double width,double height,double scale){this(root,width,height,scale,scale,Map.of(),Set.of(),true);}
        public Request(Path root,double width,double height,double scale,double physicalPerUnit){this(root,width,height,scale,physicalPerUnit,Map.of(),Set.of(),true);}
        /** Preserve the original seven-argument contract with GUI-axis viewport semantics. */
        public Request(Path root,double width,double height,double scale,Map<String,String> hudTypes,Set<String> knownModIds,boolean includeProfiles){this(root,width,height,scale,scale,hudTypes,knownModIds,includeProfiles);}
    }
    public record Issue(String code,String file,String detail) {}
    public record ProfileSnapshot(String legacyName,Map<String,String> preferences,HudDocument hud) {
        public ProfileSnapshot { preferences=Map.copyOf(preferences); }
    }
    public record Snapshot(String activeProfile,Map<String,String> preferences,HudDocument hud,List<ProfileSnapshot> profiles,List<Issue> issues,int filesRead) {
        public Snapshot { preferences=Map.copyOf(preferences);profiles=List.copyOf(profiles);issues=List.copyOf(issues); }
        /** Client-thread only. Existing new settings/HUD identities win, so repeated application is idempotent. */
        public Applied apply(UiPreferences target,HudDocument existing,Consumer<HudDocument> applyHud) {
            Objects.requireNonNull(target);Objects.requireNonNull(existing);Objects.requireNonNull(applyHud);
            Map<String,String> merged=new LinkedHashMap<>(preferences);merged.putAll(target.snapshot());
            LinkedHashMap<String,HudDesigner.State> elements=new LinkedHashMap<>();
            for(var state:existing.elements())elements.put(state.id(),state);
            for(var state:hud.elements())elements.putIfAbsent(state.id(),state);
            HudDocument converted=new HudDocument(new ArrayList<>(elements.values()));
            // The host applies/persists the detached HUD first. A failure does not mark preferences migrated.
            applyHud.accept(converted);
            boolean partial=issues.stream().anyMatch(issue->!issue.code().equals("host-hud-authoritative"));
            merged.put(MARKER,partial?"partial":"applied");target.replace(Map.copyOf(merged));
            return new Applied(converted,profiles,issues);
        }
    }
    public record Applied(HudDocument hud,List<ProfileSnapshot> profiles,List<Issue> issues) {}
    private static final class Reader {
        final Request request;final Path root;final List<Issue> issues=new ArrayList<>();int files;
        Reader(Request request)throws IOException{this.request=request;root=request.runDirectory.toRealPath();}
        void issue(String code,String file,String detail){issues.add(new Issue(code,file,detail));}
        Path safe(String relative)throws IOException{
            Path file=root.resolve(relative).normalize();if(!file.startsWith(root))throw new IOException("Legacy path leaves run directory");
            Path cursor=root;for(Path part:root.relativize(file)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IOException("Legacy symlink is not followed");}
            return file;
        }
        String read(String relative)throws IOException{
            Path file=safe(relative);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_FILE||++files>MAX_FILES)throw new IOException("Legacy input type/size/count limit");
            byte[] bytes;try(var stream=Files.newInputStream(file)){bytes=stream.readNBytes(MAX_FILE+1);}if(bytes.length>MAX_FILE)throw new IOException("Legacy input grew beyond size limit");
            try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException error){throw new IOException("Legacy input is not valid UTF-8");}
        }
        Map<String,Object> data(String base){
            List<String> found=new ArrayList<>();try{for(String suffix:List.of(".json",".yml",".yaml",".toml"))if(Files.exists(safe(base+suffix),LinkOption.NOFOLLOW_LINKS))found.add(base+suffix);}catch(IOException error){issue("unsafe-path",base,error.getMessage());return Map.of();}
            if(found.isEmpty())return Map.of();if(found.size()>1){issue("ambiguous-format",base,"Multiple legacy formats exist; none selected automatically");return Map.of();}
            return exact(found.get(0));
        }
        Map<String,Object> exact(String relative){try{String text=read(relative);return text==null?Map.of():LegacyData.object(LegacyData.parse(relative,text));}catch(IOException|IllegalArgumentException error){issue("invalid-data",relative,error.getMessage());return Map.of();}}
        List<String> lines(String relative){try{String text=read(relative);if(text==null)return List.of();LinkedHashSet<String> ids=new LinkedHashSet<>();for(String line:text.split("\\r?\\n")){String value=line.trim();if(value.isEmpty())continue;if(!safeId(value)){issue("unsupported-identity",relative,"An invalid or sensitive identity was excluded");continue;}ids.add(value);}return List.copyOf(ids);}catch(IOException error){issue("invalid-data",relative,error.getMessage());return List.of();}}
    }
    /** Worker-thread entry. Reads fixed OneConfig files and regular HUD files only; arbitrary config trees are untouched. */
    public static Snapshot read(Request request)throws IOException{
        Reader reader=new Reader(request);Map<String,Object> profileMetadata=reader.data("oneconfig/profiles");
        String active=string(profileMetadata.get("activeProfile"),"");
        if(!validProfile(active)||!active.isEmpty()&&!Files.isDirectory(reader.safe("profiles/"+active),LinkOption.NOFOLLOW_LINKS)){
            reader.issue("invalid-active-profile","oneconfig/profiles","Legacy active profile is absent or unsafe; root config retained");active="";
        }
        Map<String,String> common=new LinkedHashMap<>();
        for(String id:reader.lines("oneconfig/favorite-mods"))putMod(reader,common,"mods.favorite.",id);
        for(String id:reader.lines("oneconfig/hidden-mods"))putMod(reader,common,"mods.hidden.",id);
        List<String> order=reader.lines("oneconfig/mod-order");if(!order.isEmpty())common.put("mods.order",String.join("\n",order));
        List<String> sort=reader.lines("oneconfig/mod-sort");if(!sort.isEmpty()&&Set.of("custom","alphabetical").contains(sort.get(0)))common.put("mods.sort",sort.get(0));
        for(Object item:list(profileMetadata.get("favoriteProfiles")))if(item instanceof String name&&validProfile(name)&&!name.isEmpty())common.put("profiles.favorite."+name,"true");
        for(Object item:list(profileMetadata.get("profileIcons")))if(item instanceof String pair){int equal=pair.indexOf('=');if(equal>0){String name=pair.substring(0,equal),icon=pair.substring(equal+1);if(validProfile(name)&&safeId(icon))common.put("profiles.icon."+name,icon);}}
        ProfileSnapshot current=profile(reader,active,common);
        List<ProfileSnapshot> profiles=new ArrayList<>();
        if(request.includeProfiles)profiles.add(active.isEmpty()?current:profile(reader,"",common));
        if(request.includeProfiles&&Files.isDirectory(reader.safe("profiles"),LinkOption.NOFOLLOW_LINKS)){
            try(Stream<Path> children=Files.list(reader.safe("profiles"))){for(Path file:children.sorted().limit(513).toList()){
                String name=file.getFileName().toString();if(!validProfile(name)||name.isEmpty()||Files.isSymbolicLink(file)||!Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))continue;
                if(profiles.size()>=512){reader.issue("profile-limit","profiles","More than 512 profiles; remaining legacy folders are untouched");break;}
                profiles.add(name.equals(active)?current:profile(reader,name,common));
            }}
        }
        return new Snapshot(active,current.preferences,current.hud,profiles,reader.issues,reader.files);
    }
    private static void putMod(Reader reader,Map<String,String> out,String prefix,String id){
        if(!reader.request.knownModIds.isEmpty()&&!reader.request.knownModIds.contains(id)){reader.issue("unmapped-mod-id","oneconfig", "Legacy mod identity has no current card; retained only in original files");return;}out.put(prefix+id,"true");
    }
    private static ProfileSnapshot profile(Reader reader,String name,Map<String,String> common){
        String base=name.isEmpty()?"config":"profiles/"+name;Map<String,String> values=new LinkedHashMap<>(common);
        preferences(reader,base,values);theme(reader,base,values);HudDocument hud=huds(reader,base);
        return new ProfileSnapshot(name,values,hud);
    }
    private static void preferences(Reader reader,String base,Map<String,String> out){
        Map<String,Object> source=reader.data(base+"/oneconfig");
        for(AppearanceSchema.Setting setting:AppearanceSchema.PREFERENCES){
            Object value=source.get(setting.key());if(value==null)continue;
            try{
                if(setting.kind()==AppearanceSchema.Kind.KEYBIND){String chord=binding(value);if(chord!=null)out.put(setting.key(),chord);else reader.issue("unresolved-keybind",base+"/oneconfig",setting.key()+" requires a lane-specific legacy numeric key resolver");}
                else if(setting.kind()==AppearanceSchema.Kind.TOGGLE){if(!(value instanceof Boolean))throw new IllegalArgumentException("Expected boolean");out.put(setting.key(),value.toString());}
                else if(value instanceof Number number){double n=number.doubleValue();double min=setting.kind()==AppearanceSchema.Kind.CHOICE?0:setting.min(),max=setting.kind()==AppearanceSchema.Kind.CHOICE?setting.choices().size()-1:setting.max();if(!Double.isFinite(n)||n<min||n>max||setting.kind()==AppearanceSchema.Kind.CHOICE&&n!=Math.rint(n))throw new IllegalArgumentException("Out-of-range preference");out.put(setting.key(),setting.kind()==AppearanceSchema.Kind.CHOICE?Integer.toString((int)n):number.toString());}
                else throw new IllegalArgumentException("Unsupported preference type");
            }catch(IllegalArgumentException error){reader.issue("invalid-preference",base+"/oneconfig",setting.key()+": "+error.getMessage());}
        }
        if(source.get("openingBehavior") instanceof Number opening&&opening.intValue()>=0){int remembered=switch(opening.intValue()){case 2->4;case 3->number(source.get("timeBeforeReset"),15)>37.5?2:1;default->0;};out.put("rememberLastPage",Integer.toString(remembered));}
        if(source.get("hudEditorTourSeen") instanceof Boolean seen)out.put("hudEditorTourSeen",seen.toString());
        // No values are copied from arbitrary files or unknown preference keys (including auth/account fields).
    }
    private static void theme(Reader reader,String base,Map<String,String> out){
        Map<String,Object> source=reader.data(base+"/themes");Object raw=source.get("activeTheme");
        if(raw instanceof String name){Theme chosen=null;for(Theme theme:Theme.BUILTIN)if(theme.title().equals(name)||theme.id().equals(name))chosen=theme;
            if(chosen==null)reader.issue("unknown-theme",base+"/themes","Unregistered theme remains in legacy data");else{out.put("activeTheme",chosen.title());out.put("theme",chosen.id());}}
        if(source.get("animations") instanceof Boolean animations)out.put("animations",animations.toString());
        if(source.containsKey("accentColor"))try{Color color=color(source.get("accentColor"));out.put("accentColor",hex(color.argb));out.put("accentColor.chroma",Boolean.toString(color.chroma));out.put("accentColor.chromaSpeed",Double.toString(color.speed));out.put("accentColor.gradientEnabled",Boolean.toString(color.gradient));out.put("accentColor.gradientArgb",hex(color.gradientArgb));}catch(IllegalArgumentException error){reader.issue("invalid-color",base+"/themes","accentColor: "+error.getMessage());}
    }
    private record Color(int argb,boolean chroma,double speed,boolean gradient,int gradientArgb){}
    private static Color color(Object raw){
        if(raw instanceof Map<?,?>){Map<String,Object> object=LegacyData.object(raw);String cls=string(object.get("class"),"");if(!cls.isEmpty()&&!cls.equals("org.polyfrost.compose.render.PolyColor"))throw new IllegalArgumentException("Unknown color wrapper");if(object.containsKey("value"))return color(object.get("value"));Color main=color(object.get("rgba"));boolean gradient=object.containsKey("gradient");return new Color(main.argb,Boolean.TRUE.equals(object.get("chroma")),number(object.get("chromaSpeed"),1),gradient,gradient?color(object.get("gradient")).argb:main.argb);}
        if(raw instanceof List<?> rgba&&rgba.size()==4){int[] values=new int[4];for(int i=0;i<4;i++){double n=number(rgba.get(i),-1);if(n<0||n>255||n!=Math.rint(n))throw new IllegalArgumentException("RGBA channel out of range");values[i]=(int)n;}int argb=values[3]<<24|values[0]<<16|values[1]<<8|values[2];return new Color(argb,false,1,false,argb);}
        if(raw instanceof Number n){int argb=(int)n.longValue();return new Color(argb,false,1,false,argb);}
        if(raw instanceof String text){String hex=text.startsWith("#")?text.substring(1):text;if(hex.matches("[0-9a-fA-F]{6}"))hex="FF"+hex;if(!hex.matches("[0-9a-fA-F]{8}"))throw new IllegalArgumentException("Invalid color literal");int argb=(int)Long.parseLong(hex,16);return new Color(argb,false,1,false,argb);}
        throw new IllegalArgumentException("Unsupported color data");
    }
    private static String hex(int color){return String.format(Locale.ROOT,"#%08X",color);}
    private static String binding(Object value){
        Map<String,Object> data=LegacyData.object(value);String cls=string(data.get("class"),"");if(!Set.of("org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind","org.polyfrost.oneconfig.api.ui.v1.keybind.BindNotInScreen").contains(cls))return null;
        if(number(data.get("durationNanos"),0)!=0)return null;List<String> parts=new ArrayList<>();int mods=(int)number(data.get("mods"),0);if((mods&~15)!=0)return null;
        if((mods&2)!=0)parts.add("CTRL");if((mods&1)!=0)parts.add("SHIFT");if((mods&4)!=0)parts.add("ALT");if((mods&8)!=0)parts.add("SUPER");
        for(Object key:list(data.get("keyCodes"))){if(!(key instanceof String name)||!name.startsWith("key.keyboard."))return null;String token=name.substring(13).toUpperCase(Locale.ROOT).replace('.','_');if(!token.matches("[A-Z0-9_]+"))return null;parts.add(token);}
        for(Object key:list(data.get("mouseBtns"))){if(!(key instanceof String name)||!name.startsWith("key.mouse."))return null;String token=name.substring(10);int mouse=switch(token){case "left"->0;case "right"->1;case "middle"->2;default->{try{yield Integer.parseInt(token)-1;}catch(NumberFormatException error){yield -1;}}};if(mouse<0||mouse>7)return null;parts.add("MOUSE:"+mouse);}
        return parts.isEmpty()?"NONE":String.join("+",parts);
    }
    private static HudDocument huds(Reader reader,String base){
        List<HudDesigner.State> out=new ArrayList<>();Path directory;
        try{directory=reader.safe(base+"/huds");if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))return HudDocument.empty();
            try(Stream<Path> files=Files.list(directory)){for(Path file:files.sorted().limit(513).toList()){
                String name=file.getFileName().toString();if(!safeId(name)||!name.matches("[A-Za-z0-9_. -]+\\.(?:json|yml|yaml|toml)")||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                if(out.size()>=512){reader.issue("hud-limit",base+"/huds","Remaining legacy HUDs retained without overwrite");break;}
                String relative=base+"/huds/"+name;Map<String,Object> data=reader.exact(relative);if(data.isEmpty())continue;
                try{HudDesigner.State state=hud(reader,relative,"huds/"+name,data,out.size());if(state!=null)out.add(state);}catch(IllegalArgumentException error){reader.issue("unsupported-hud",relative,error.getMessage());}
            }}
        }catch(IOException error){reader.issue("hud-read-error",base+"/huds",error.getMessage());}
        Set<String> ids=new LinkedHashSet<>();for(var s:out)ids.add(s.id());List<HudDesigner.State> valid=new ArrayList<>();
        for(var s:out){if(s.anchor()!=null&&!ids.contains(s.anchor().parent())){reader.issue("unresolved-anchor",base+"/huds","A target HUD was not converted; keeping independent stored position");s=new HudDesigner.State(s.id(),s.x(),s.y(),s.width(),s.height(),s.scale(),s.hidden(),s.locked(),s.type(),s.properties(),null,s.order());}valid.add(s);}
        try{return new HudDocument(valid);}catch(IllegalArgumentException error){reader.issue("invalid-hud-graph",base+"/huds",error.getMessage());return HudDocument.empty();}
    }
    private static HudDesigner.State hud(Reader reader,String file,String id,Map<String,Object> source,int order){
        String cls=string(source.get("hudClass"),"");if(cls.endsWith(".OneConfigHudCompat")){reader.issue("host-hud-authoritative",file,"Host compat HUD stores its real position/settings in the existing MuoniumPlayer configuration; no duplicate UI-owned HUD created");return null;}
        String type=reader.request.hudTypes.get(cls);if(type==null){if(cls.equals(TEXT)||cls.equals("org.polyfrost.oneconfig.api.hud.v1.HudManager$TextTestHud"))type="text";else if(cls.equals(DATETIME)||cls.equals("org.polyfrost.oneconfig.api.hud.v1.HudManager$TimeTestHud"))type="time";else if(cls.equals("org.polyfrost.oneconfig.api.hud.v1.HudManager$DateTestHud"))type="date";}
        if(type==null)throw new IllegalArgumentException("HUD provider type has no explicit new implementation mapping");
        Map<String,String> properties=new LinkedHashMap<>();for(var e:HudNode.defaults().entrySet())properties.put(e.getKey(),String.valueOf(e.getValue()));
        for(String key:BOOLEAN_HUD)if(source.containsKey(key)){if(!(source.get(key) instanceof Boolean))throw new IllegalArgumentException("HUD boolean type mismatch: "+key);properties.put(key,source.get(key).toString());}
        for(String key:NUMBER_HUD)if(source.containsKey(key)){double value=number(source.get(key),Double.NaN);if(!Double.isFinite(value))throw new IllegalArgumentException("HUD number type mismatch: "+key);properties.put(key,Double.toString(value));}
        for(String key:List.of("bgColor","textColor","shadowColor"))if(source.containsKey(key)){Color c=color(source.get(key));properties.put(key,Integer.toString(c.argb));}
        for(String key:List.of("prefix","suffix","template"))if(source.get(key) instanceof String s){if(s.length()>8192)throw new IllegalArgumentException("HUD text limit");properties.put(key,s);}
        if(source.get("it") instanceof String text){if(text.length()>8192)throw new IllegalArgumentException("HUD text limit");properties.put("text",text);}
        for(String key:List.of("font","textWeight"))if(source.containsKey(key)){String name=enumName(source.get(key));Set<String> known=key.equals("font")?Set.of("Minecraft","Poppins"):Set.of("Thin","Regular","Medium","Bold","Black");if(!known.contains(name))throw new IllegalArgumentException("Unknown HUD text style: "+key);properties.put(key,name);}
        if(source.containsKey("alignment"))properties.put("alignment",anchor(enumName(source.get("alignment"))).name());
        if(source.containsKey("growthAnchor"))properties.put("growthAnchor",anchor(enumName(source.get("growthAnchor"))).name());
        int section=source.containsKey("section")?SECTIONS.indexOf(enumName(source.get("section"))):0;if(section<0)throw new IllegalArgumentException("Unknown HUD section");
        if(source.containsKey("section")&&number(source.get("posSchema"),0)!=1)throw new IllegalArgumentException("Legacy position schema requires explicit old-runtime layout evidence");
        properties.put("section",Integer.toString(section));properties.put("relativeX",Double.toString(Math.round(number(source.get("relativeX"),10))));properties.put("relativeY",Double.toString(Math.round(number(source.get("relativeY"),10))));
        double scale=number(source.get("customScale"),1);if(scale<=0||!Double.isFinite(scale))throw new IllegalArgumentException("Invalid HUD scale");
        HudNode node=new HudNode(id,type);node.restoreProperties(properties);node.frame(reader.request.guiScale,reader.request.physicalPerUnit);
        if(!source.containsKey("section")&&(source.containsKey("x")||source.containsKey("y"))){
            double guiToLayout=reader.request.guiScale/reader.request.physicalPerUnit;
            node.absolutePosition(number(source.get("x"),0)*guiToLayout,number(source.get("y"),0)*guiToLayout,reader.request.viewportWidth,reader.request.viewportHeight);
        }
        if(!Boolean.TRUE.equals(source.get("staticWidth")))reader.issue("hud-metrics-pending",file,"Dynamic text metrics will be remeasured by the real client font renderer; geometry is provisional until runtime acceptance");
        var bounds=node.bounds(reader.request.viewportWidth,reader.request.viewportHeight);
        // Link offsets remain original GUI scalars; the native HUD positionUnitScale converts them at runtime.
        HudDesigner.Link link=null;String target=string(source.get("anchorTargetId"),"");if(!target.isEmpty()){
            if(!target.startsWith("huds/")||!safeId(target))throw new IllegalArgumentException("Invalid HUD anchor target");link=new HudDesigner.Link(target,anchor(enumName(source.getOrDefault("anchorPoint","TopLeft"))),anchor(enumName(source.getOrDefault("selfAnchorPoint","Auto"))),number(source.get("anchorOffsetX"),0),number(source.get("anchorOffsetY"),0));}
        return new HudDesigner.State(id,node.x(),node.y(),bounds.w(),bounds.h(),scale,Boolean.parseBoolean(properties.get("hidden")),Boolean.TRUE.equals(source.get("locked")),type,node.captureProperties(),link,order);
    }
    private static HudElement.Anchor anchor(String name){return switch(name){case "Auto","AUTO"->HudElement.Anchor.AUTO;case "TopLeft","TOP_LEFT"->HudElement.Anchor.TOP_LEFT;case "Top","TopCenter","TOP"->HudElement.Anchor.TOP;case "TopRight","TOP_RIGHT"->HudElement.Anchor.TOP_RIGHT;case "Left","CenterLeft","LEFT"->HudElement.Anchor.LEFT;case "Center","CENTER"->HudElement.Anchor.CENTER;case "Right","CenterRight","RIGHT"->HudElement.Anchor.RIGHT;case "BottomLeft","BOTTOM_LEFT"->HudElement.Anchor.BOTTOM_LEFT;case "Bottom","BottomCenter","BOTTOM"->HudElement.Anchor.BOTTOM;case "BottomRight","BOTTOM_RIGHT"->HudElement.Anchor.BOTTOM_RIGHT;default->throw new IllegalArgumentException("Unknown HUD anchor/alignment");};}
    private static String enumName(Object raw){if(raw instanceof String name)return name;if(raw instanceof Map<?,?> map&&map.get("value") instanceof String name)return name;throw new IllegalArgumentException("Unsupported enum representation");}
    private static double number(Object value,double fallback){if(value==null)return fallback;if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Expected finite numeric data");return n.doubleValue();}
    private static String string(Object value,String fallback){return value instanceof String s?s:fallback;}
    private static List<?> list(Object value){return value instanceof List<?> list?list:List.of();}
    private static boolean validProfile(String name){return name!=null&&(name.isEmpty()||name.length()<=128&&!name.equals(".")&&!name.equals("..")&&!name.contains("/")&&!name.contains("\\")&&!name.contains(":")&&name.chars().noneMatch(c->c<32));}
    private static boolean safeId(String value){if(value==null||value.isBlank()||value.length()>256||!value.matches("[A-Za-z0-9_.:/ -]+"))return false;String lower=value.toLowerCase(Locale.ROOT);return !lower.matches(".*(?:password|credential|access.?token|refresh.?token|cookie|secret|session|account).*" )&&!value.contains("..");}
}
