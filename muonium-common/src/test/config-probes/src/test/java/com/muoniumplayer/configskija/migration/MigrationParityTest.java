package com.muoniumplayer.configskija.migration;

import com.muoniumplayer.configskija.host.migration.LegacyData;
import com.muoniumplayer.configskija.host.migration.LegacyMigration;
import com.muoniumplayer.configskija.host.migration.MigrationService;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.hud.HudDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Generated, non-sensitive fixtures under this workspace build directory only. No real run writes. */
public final class MigrationParityTest {
    static int checks;static List<String> groups=new ArrayList<>();static Path root;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void equal(Object a,Object b,String message){checks++;if(!Objects.equals(a,b))throw new AssertionError(message+" expected="+b+" actual="+a);}
    interface Work{void run()throws Exception;}
    static void group(String name,Work work)throws Exception{work.run();groups.add(name);}
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(root);
        group("data-only-json-yaml-toml-and-parser-rejection",MigrationParityTest::parsers);
        group("root-profile-theme-color-binding-and-favorites",MigrationParityTest::rootProfile);
        group("active-profile-isolation-and-no-account-copy",MigrationParityTest::profiles);
        group("hud-scalar-layout-enum-anchor-and-roundtrip",MigrationParityTest::huds);
        group("unsupported-types-old-numeric-and-invalid-data-reported",MigrationParityTest::diagnostics);
        group("target-wins-idempotence-and-no-legacy-write",MigrationParityTest::apply);
        group("worker-client-delivery-close-and-stale-callbacks",MigrationParityTest::service);
        group("safe-path-bounds-and-credential-file-exclusion",MigrationParityTest::boundaries);
        System.out.println("MigrationParityTest PASS: "+groups.size()+" groups, "+checks+" assertions");for(String name:groups)System.out.println("PASS "+name);
    }
    static Path fixture(String name)throws IOException{Path directory=Files.createTempDirectory(root,name+"-");Files.createDirectories(directory.resolve("config"));Files.createDirectories(directory.resolve("oneconfig"));Files.createDirectories(directory.resolve("profiles"));return directory;}
    static void write(Path root,String path,String value)throws IOException{Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,value,StandardCharsets.UTF_8);}
    static LegacyMigration.Snapshot read(Path root)throws IOException{return LegacyMigration.read(new LegacyMigration.Request(root,1280,720,1));}
    static void bad(String name,String text){boolean rejected=false;try{LegacyData.parse(name,text);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"reject "+name+": "+text);}
    static void parsers(){
        equal(LegacyData.object(LegacyData.parse("x.json","{\"value\":[1,2,3,4],\"class\":\"never.load.Class\"}")).get("class"),"never.load.Class","class remains inert data");
        Map<String,Object> yaml=LegacyData.object(LegacyData.parse("x.yml","hidden: false\nsection:\n  class: SomeEnum\n  value: TopLeft\ncolor: [1, 2, 3, 255]\ntext: 'hello # world'\nkeys:\n- a\n- b\nnext: true\n"));
        equal(LegacyData.object(yaml.get("section")).get("value"),"TopLeft","nested YAML enum");equal(yaml.get("keys"),List.of("a","b"),"indentless safe sequence");equal(yaml.get("next"),true,"sequence ends before sibling");equal(yaml.get("text"),"hello # world","quoted comment preserved");
        Map<String,Object> toml=LegacyData.object(LegacyData.parse("x.toml","hidden = true\n[section]\nclass = \"SomeEnum\"\nvalue = \"TopLeft\"\n"));equal(LegacyData.object(toml.get("section")).get("value"),"TopLeft","TOML table enum");
        for(String text:List.of("{\"a\":1,\"a\":2}","{\"x\":NaN}","{\"x\":1e999}","{\"x\":\"\\q\"}","[] junk","{\"x\":+1}"))bad("x.json",text);
        bad("x.yml","!!java/object:Other {}\n");bad("x.yml","x: &id hello\n");bad("x.toml","[[unknown]]\nx = 1\n");bad("x.yml","x: |\n  not-supported\n");bad("x.yml","x: "+"[".repeat(45)+"a"+"]".repeat(45));
    }
    static void rootProfile()throws Exception{
        Path directory=fixture("root");
        write(directory,"oneconfig/profiles.json","{\"activeProfile\":\"\",\"favoriteProfiles\":[],\"profileIcons\":[]}");
        write(directory,"oneconfig/favorite-mods","muonium.hud\nmuonium.hud\n");write(directory,"oneconfig/hidden-mods","other.ui\n");write(directory,"oneconfig/mod-order","other.ui\nmuonium.hud\n");write(directory,"oneconfig/mod-sort","alphabetical\n");
        write(directory,"config/oneconfig.json","{\"searchDistance\":1,\"rememberLastPage\":4,\"uiScale\":125,\"enableUISounds\":false,\"hudEditorTourSeen\":true,\"hudCopyKeybind\":{\"class\":\"org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind\",\"keyCodes\":[\"key.keyboard.c\"],\"mods\":2},\"accessToken\":\"NEVER_COPY\"}");
        write(directory,"config/themes.json","{\"activeTheme\":\"Minecraft Light\",\"animations\":false,\"accentColor\":{\"class\":\"org.polyfrost.compose.render.PolyColor\",\"value\":{\"rgba\":[1,2,3,255],\"chroma\":true,\"chromaSpeed\":2,\"gradient\":[4,5,6,128]}}}");
        LegacyMigration.Snapshot snapshot=read(directory);equal(snapshot.activeProfile(),"","root owner");equal(snapshot.preferences().get("searchDistance"),"1","distance retained");equal(snapshot.preferences().get("rememberLastPage"),"4","choice bound follows choices rather than slider max");equal(snapshot.preferences().get("uiScale"),"125","scale retained");equal(snapshot.preferences().get("hudCopyKeybind"),"CTRL+C","stable named chord");equal(snapshot.preferences().get("mods.favorite.muonium.hud"),"true","mod favorite ID");equal(snapshot.preferences().get("mods.order"),"other.ui\nmuonium.hud","stored order");equal(snapshot.preferences().get("activeTheme"),"Minecraft Light","theme label");equal(snapshot.preferences().get("accentColor"),"#FF010203","RGBA converted to ARGB");equal(snapshot.preferences().get("accentColor.gradientArgb"),"#80040506","gradient RGBA");check(!snapshot.preferences().toString().contains("NEVER_COPY"),"unknown credential discarded");equal(snapshot.hud().elements().size(),0,"empty legacy hud stays empty");
    }
    static void profiles()throws Exception{
        Path directory=fixture("profiles");write(directory,"oneconfig/profiles.json","{\"activeProfile\":\"Studio\",\"favoriteProfiles\":[\"Studio\"],\"profileIcons\":[\"Studio=star\"]}");
        write(directory,"config/oneconfig.json","{\"searchDistance\":0}");write(directory,"profiles/Studio/oneconfig.json","{\"searchDistance\":3,\"enableUISounds\":false}");write(directory,"profiles/Other/themes.json","{\"activeTheme\":\"PolyGlass Light\"}");
        write(directory,"profiles/Studio/music-accounts.json","not-json secret should never be opened");write(directory,"config/muonium/accounts.json","private config not parsed");write(directory,"profiles/Other/cookies.json","also not parsed");
        LegacyMigration.Snapshot snapshot=read(directory);equal(snapshot.activeProfile(),"Studio","active profile resolved");equal(snapshot.preferences().get("searchDistance"),"3","active values not root");equal(snapshot.profiles().size(),3,"root plus both named profile snapshots");equal(snapshot.preferences().get("profiles.favorite.Studio"),"true","favorite profile list");equal(snapshot.preferences().get("profiles.icon.Studio"),"star","profile icon name=value");check(snapshot.issues().stream().noneMatch(issue->issue.file().contains("accounts")||issue.file().contains("cookies")),"arbitrary auth files never read");
        LegacyMigration.ProfileSnapshot other=snapshot.profiles().stream().filter(p->p.legacyName().equals("Other")).findFirst().orElseThrow();check(!other.preferences().containsKey("searchDistance"),"named missing config does not leak active/root values");equal(snapshot.profiles().stream().filter(p->p.legacyName().isEmpty()).findFirst().orElseThrow().preferences().get("searchDistance"),"0","inactive root configuration retained separately");check(snapshot.profiles().stream().noneMatch(p->p.preferences().toString().contains("secret")),"profile contains only allowlisted UI scalars");
    }
    static String hud(String cls,String relative,String target){return "{\"hudClass\":\""+cls+"\",\"staticWidth\":true,\"staticW\":100,\"staticH\":30,\"customScale\":1.5,\"section\":{\"class\":\"org.polyfrost.oneconfig.api.hud.v1.Section\",\"value\":\"TopLeft\"},\"posSchema\":1,\"relativeX\":"+relative+",\"relativeY\":20,\"growthAnchor\":{\"class\":\"org.polyfrost.oneconfig.api.hud.v1.HudAnchor\",\"value\":\"TopLeft\"},\"font\":{\"class\":\"org.polyfrost.oneconfig.api.hud.v1.Font\",\"value\":\"Poppins\"},\"textWeight\":{\"class\":\"x\",\"value\":\"Medium\"},\"it\":\"Preserved text\",\"textColor\":-1,\"anchorTargetId\":\""+target+"\",\"hidden\":false,\"locked\":true,\"authToken\":\"NEVER_COPY_HUD\"}";}
    static void huds()throws Exception{
        Path directory=fixture("huds");write(directory,"config/huds/1000-text_hud.yml", "hudClass: org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple\nstaticWidth: true\nstaticW: 100\nstaticH: 30\ncustomScale: 1\nsection: {class: org.polyfrost.oneconfig.api.hud.v1.Section, value: TopLeft}\nposSchema: 1\nrelativeX: 12\nrelativeY: 24\nit: 'Legacy yaml text'\n");
        write(directory,"config/huds/2000-text_hud.json",hud("org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple","20.7","huds/1000-text_hud.yml"));
        LegacyMigration.Snapshot snapshot=read(directory);equal(snapshot.hud().elements().size(),2,"yaml and JSON hud converted");var child=snapshot.hud().elements().get(1);equal(child.id(),"huds/2000-text_hud.json","legacy tree path stable");equal(child.anchor().parent(),"huds/1000-text_hud.yml","anchor target stable path");equal(child.properties().get("text"),"Preserved text","text field it mapping");equal(child.properties().get("font"),"Poppins","enum name retained");equal(child.properties().get("relativeX"),"21.0","old Math.round coordinate applied");check(child.locked(),"locked state retained");check(!child.properties().toString().contains("NEVER_COPY_HUD"),"unknown HUD fields not copied");equal(HudDocument.decode(snapshot.hud().encode()),snapshot.hud(),"target detached document roundtrip");
    }
    static void diagnostics()throws Exception{
        Path directory=fixture("errors");write(directory,"config/oneconfig.json","{\"searchDistance\":999,\"uiScale\":-5,\"hudCopyKeybind\":{\"class\":\"org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind\",\"keyCodes\":[67],\"mods\":2}}");write(directory,"config/themes.json","{\"activeTheme\":\"Unknown theme\"}");
        write(directory,"config/huds/custom.json",hud("third.party.Unknown","1",""));write(directory,"config/huds/old.json",hud("org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple","1","").replace("\"posSchema\":1","\"posSchema\":0"));write(directory,"config/huds/compat.json",hud("org.polyfrost.oneconfig.api.hud.v1.OneConfigHudCompat","1",""));
        LegacyMigration.Snapshot snapshot=read(directory);check(snapshot.issues().stream().anyMatch(i->i.code().equals("unresolved-keybind")),"raw numeric key not guessed");check(snapshot.issues().stream().anyMatch(i->i.code().equals("invalid-preference")),"range rejected not silently clamped");check(snapshot.issues().stream().anyMatch(i->i.code().equals("unknown-theme")),"unknown theme reported");check(snapshot.issues().stream().filter(i->i.code().equals("unsupported-hud")).count()==2,"unknown type and old pos schema pending");check(snapshot.issues().stream().anyMatch(i->i.code().equals("host-hud-authoritative")),"host HUD not duplicated");UiPreferences unresolved=new UiPreferences(Map.of(),()->{});snapshot.apply(unresolved,HudDocument.empty(),ignored->{});equal(unresolved.get(LegacyMigration.MARKER,""),"partial","unresolved conversion cannot mark all data migrated");
        write(directory,"config/oneconfig.yml","searchDistance: 2\n");check(read(directory).issues().stream().anyMatch(i->i.code().equals("ambiguous-format")),"multiple format ambiguity reported");
        Path old=fixture("opening-behavior");write(old,"config/oneconfig.json","{\"openingBehavior\":3,\"timeBeforeReset\":60}");equal(read(old).preferences().get("rememberLastPage"),"2","original smart-reset migration threshold");
    }
    static void apply()throws Exception{
        Path directory=fixture("apply");write(directory,"config/oneconfig.json","{\"searchDistance\":1,\"enableUISounds\":false}");write(directory,"config/huds/text.json",hud("org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple","5",""));Map<String,String> before=hashes(directory);
        LegacyMigration.Snapshot snapshot=read(directory);UiPreferences target=new UiPreferences(Map.of("searchDistance","0","new.setting","keep"),()->{});AtomicReference<HudDocument> document=new AtomicReference<>(HudDocument.empty());snapshot.apply(target,document.get(),document::set);equal(target.number("searchDistance",2),0d,"existing new preference wins");equal(target.get("new.setting",""),"keep","new value retained");check(target.flag("enableUISounds",true)==false,"missing preference imported");equal(target.get(LegacyMigration.MARKER,""),"applied","target-only marker");int count=document.get().elements().size();snapshot.apply(target,document.get(),document::set);equal(document.get().elements().size(),count,"repeat migration does not duplicate HUDs");equal(hashes(directory),before,"all legacy bytes unchanged after read and apply");
        UiPreferences fresh=new UiPreferences(Map.of(),()->{});boolean threw=false;try{snapshot.apply(fresh,HudDocument.empty(),ignored->{throw new IllegalStateException("host failed");});}catch(IllegalStateException expected){threw=true;}check(threw&&!fresh.snapshot().containsKey(LegacyMigration.MARKER),"failed client apply not marked done");
    }
    static void service()throws Exception{
        Path directory=fixture("service");write(directory,"config/oneconfig.json","{\"searchDistance\":1}");QueueExecutor worker=new QueueExecutor();List<Runnable> client=new ArrayList<>();AtomicInteger applied=new AtomicInteger();
        try(MigrationService service=new MigrationService(worker,client::add)){
            CompletableFuture<?> first=service.start(new LegacyMigration.Request(directory,1280,720,1),s->applied.incrementAndGet(),error->{throw new AssertionError(error);});equal(applied.get(),0,"worker has not run inline");worker.drain();equal(applied.get(),0,"apply awaits client executor");drain(client);equal(applied.get(),1,"client applies snapshot");check(first.isDone(),"future completes after client apply");
            service.start(new LegacyMigration.Request(directory,1280,720,1),s->applied.incrementAndGet(),error->{throw new AssertionError(error);});worker.drain();service.invalidate();drain(client);equal(applied.get(),1,"obsolete snapshot not applied");
            service.start(new LegacyMigration.Request(directory,1280,720,1),s->applied.incrementAndGet(),error->{throw new AssertionError(error);});worker.drain();service.close();drain(client);equal(applied.get(),1,"close suppresses late apply");check(!worker.isShutdown(),"injected worker caller-owned");
        }

        QueueExecutor queued=new QueueExecutor();MigrationService early=new MigrationService(queued,client::add);CompletableFuture<?> never=early.start(new LegacyMigration.Request(directory,1280,720,1),s->applied.incrementAndGet(),error->{});early.close();check(never.isCompletedExceptionally(),"close completes queued future even before worker starts");queued.drain();equal(applied.get(),1,"closed queued work cannot apply");
    }
    static void boundaries()throws Exception{
        Path directory=fixture("bounds");write(directory,"oneconfig/profiles.json","{\"activeProfile\":\"../escape\"}");write(directory,"config/oneconfig.json","{\"searchDistance\":2}");write(directory,"config/huds/accounts.json","not parsed");write(directory,"config/huds/secret.pem","not parsed");write(directory,"config/unrelated.json","not parsed");
        LegacyMigration.Snapshot snapshot=read(directory);check(snapshot.issues().stream().anyMatch(i->i.code().equals("invalid-active-profile")),"profile traversal refused");check(snapshot.issues().stream().noneMatch(i->i.file().contains("accounts")||i.file().contains("secret")),"credential-like HUD file excluded before reading");
        boolean invalid=false;try{new LegacyMigration.Request(directory,0,720,1);}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"invalid captured viewport rejected");
        String huge="x".repeat(4*1024*1024+1);write(directory,"config/themes.json",huge);check(read(directory).issues().stream().anyMatch(i->i.code().equals("invalid-data")&&i.file().contains("themes")),"oversize input reported");
    }
    static Map<String,String> hashes(Path directory)throws Exception{Map<String,String> out=new LinkedHashMap<>();try(Stream<Path> walk=Files.walk(directory)){for(Path file:walk.filter(Files::isRegularFile).sorted().toList())out.put(directory.relativize(file).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));}return out;}
    static void drain(List<Runnable> tasks){while(!tasks.isEmpty()){List<Runnable> copy=List.copyOf(tasks);tasks.clear();copy.forEach(Runnable::run);}}
    static final class QueueExecutor extends AbstractExecutorService{final List<Runnable> tasks=new ArrayList<>();boolean closed;public void execute(Runnable task){if(closed)throw new java.util.concurrent.RejectedExecutionException();tasks.add(task);}void drain(){MigrationParityTest.drain(tasks);}public void shutdown(){closed=true;}public List<Runnable> shutdownNow(){closed=true;List<Runnable> copy=List.copyOf(tasks);tasks.clear();return copy;}public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed&&tasks.isEmpty();}public boolean awaitTermination(long t,java.util.concurrent.TimeUnit u){return isTerminated();}}
}
