package com.muoniumplayer.configskija.host;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;
import com.muoniumplayer.configskija.host.text.HostTextRenderer;
import com.muoniumplayer.configskija.host.sound.*;
import com.muoniumplayer.configskija.host.notifications.NotificationIntegration;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One host-owned session survives menu close, reload and profile changes. No renderer context is created. */
public final class SkijaConfigService {
    private record Assets(Map<String,SkijaPainter.Svg> icons,HostTextRenderer.LoadedAssets fonts,Map<String,Map<String,String>> language,byte[] avatar) {}
    private static volatile SkijaConfigService instance;
    private static SkijaConfigService current(){SkijaConfigService value=instance;if(value==null){synchronized(SkijaConfigService.class){value=instance;if(value==null)instance=value=new SkijaConfigService();}}return value;}
    private final UiStateStore store=new UiStateStore(ConfigPaths.HUD.toPath().getParent().resolve("skija-config/ui.properties"));
    private final CompletableFuture<Map<String,String>> state;
    private final CompletableFuture<Assets> assets;
    private UiPreferences preferences;
    private MuoniumHost host;
    private Assets loaded;
    private SoundSession sounds;
    private HudRuntime runtime;
    private SearchIndex searchIndex;
    private SearchDocuments.SettingsSnapshot searchSnapshot;
    private HostTextRenderer text;
    private SkijaPainter painter;
    private ConfigBackdrop backdrop;
    private final java.util.concurrent.ExecutorService avatarWorker=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"MuoniumConfig-avatar");thread.setDaemon(true);return thread;});
    private com.muoniumplayer.configskija.host.avatar.AvatarLoader avatarLoader;
    private UUID avatarAccount;
    private boolean avatarInWorld;
    private SkijaConfigScreen screen;
    private boolean dirty,failed;
    private long changedAt;
    private double lastFrame;
    private com.muoniumplayer.configskija.host.migration.MigrationService migration;
    private String lastTheme="";
    private String lastHudOwner="";
    private long menuClosedAt;
    private String error="";
    private final PreferenceBindings bindingEdges=new PreferenceBindings();
    private SkijaConfigService() {
        state=store.load();assets=CompletableFuture.supplyAsync(()-> {
            try {
                ClassLoader loader=SkijaConfigService.class.getClassLoader();Map<String,Map<String,String>> langs=new HashMap<>();
                for(String lang:List.of("en_us","zh_cn")) try(InputStream stream=loader.getResourceAsStream("assets/muoniumconfigskija/lang/"+lang+".json")) {
                    if(stream==null) throw new IOException("Missing UI language: "+lang);var root=com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();Map<String,String> values=new HashMap<>();root.entrySet().forEach(e->values.put(e.getKey(),e.getValue().getAsString()));langs.put(lang,Map.copyOf(values));
                }
                byte[] avatar;try(InputStream stream=loader.getResourceAsStream("assets/muoniumconfigskija/images/head.png")) { if(stream==null)throw new IOException("Missing player-head fallback");avatar=stream.readAllBytes(); }
                return new Assets(SkijaPainter.loadIcons(),HostTextRenderer.loadAssets(),Map.copyOf(langs),avatar);
            }catch(IOException ex) {throw new java.io.UncheckedIOException(ex);}
        });
    }
    public static SkijaConfigService get() { return current(); }
    public static void tick() { if(!Boolean.getBoolean("muonium.config.skija.disabled"))current().update(); }
    private void update() {
        if(failed)return;
        if(host==null&&state.isDone()&&assets.isDone()) {
            try {
                loaded=assets.join();preferences=new UiPreferences(state.join(),()->{dirty=true;changedAt=System.nanoTime();});
                avatarLoader=com.muoniumplayer.configskija.host.avatar.AvatarLoader.create(avatarWorker,com.muoniumplayer.configskija.host.avatar.AvatarLoader.Mode.ORIGINAL_FALLBACK);
                host=new MuoniumHost(()->{if(screen!=null)screen.closeScreen();},preferences);sounds=new SoundSession(preferences,new McSoundBackend());host.soundSession(sounds);
                runtime=HudRuntime.open(host,preferences);host.initializeLibrary();initializeSearch();startMigration();NotificationIntegration.errors(host::error);
                System.out.println("[MuoniumConfig/Skija] session ready; existing host settings="+host.categories().stream().flatMap(c->c.groups().stream()).mapToInt(g->g.options().size()).sum());
            }catch(RuntimeException ex) { failed=true;error=String.valueOf(ex.getMessage());System.err.println("[MuoniumConfig/Skija] session initialization failed: "+ex);ex.printStackTrace(System.err); }
        }
        if(host==null)return;sounds.tick();host.tickLibrary(screen!=null);
        UUID account=WindowAccess.profileId();boolean inWorld=WindowAccess.inWorld();
        if(!Objects.equals(account,avatarAccount)||inWorld!=avatarInWorld){avatarAccount=account;avatarInWorld=inWorld;avatarLoader.request();}
        if(!host.hudOwner().equals(lastHudOwner)){lastHudOwner=host.hudOwner();runtime.reload(host.loadHudDocument());}
        pollBindings();
        String theme=Theme.active(preferences).id();if(!lastTheme.isEmpty()&&!lastTheme.equals(theme)&&screen!=null)sounds.themeChanged(theme);lastTheme=theme;
        if(dirty&&System.nanoTime()-changedAt>300_000_000L)save();
    }
    private void initializeSearch(){
        searchSnapshot=SearchDocuments.settings("muonium.hud","MuoniumPlayer",host.categories(),new com.muoniumplayer.configskija.ui.search.SettingsSearchAdapter(host.settingsSchema()));
        searchIndex=new SearchIndex((int)preferences.number("searchDistance",2));searchIndex.registerSource(searchSnapshot);
        searchIndex.init();
    }
    public com.muoniumplayer.configskija.ui.search.SearchPage searchPage(){
        return new com.muoniumplayer.configskija.ui.search.SearchPage(searchIndex,searchSnapshot.rows(),com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil::runOnMainThread,new com.muoniumplayer.configskija.ui.search.GlobalResultsRenderer(owner->host.settingsSchema()));
    }
    private void startMigration(){
        if(preferences.snapshot().containsKey(com.muoniumplayer.configskija.host.migration.LegacyMigration.MARKER)&&preferences.flag("migration.oneconfig.profiles.v1",false))return;
        migration=new com.muoniumplayer.configskija.host.migration.MigrationService(com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil::runOnMainThread);
        var request=new com.muoniumplayer.configskija.host.migration.LegacyMigration.Request(java.nio.file.Path.of("."),RenderSystem.getLayoutWidth(),RenderSystem.getLayoutHeight(),WindowAccess.guiScale(),WindowAccess.physicalPerLayout());
        migration.start(request,snapshot->{
            if(!preferences.snapshot().containsKey(com.muoniumplayer.configskija.host.migration.LegacyMigration.MARKER))snapshot.apply(preferences,host.loadHudDocument(),document->{host.saveHudDocument(document);runtime.reload(document);});
            if(!preferences.flag("migration.oneconfig.profiles.v1",false)){
                var profiles=snapshot.profiles().stream().map(profile->new com.muoniumplayer.configskija.host.library.LegacyProfileImport.Profile(profile.legacyName(),profile.preferences(),profile.hud())).toList();
                ((MuoniumProfiles)host.profiles()).importLegacyProfiles(request.runDirectory(),profiles,preferences.snapshot()).whenComplete((report,failure)->com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runOnMainThread(()->{
                    if(failure!=null){host.error("导入原命名配置",failure);return;}
                    for(var issue:report.issues())System.out.println("[MuoniumConfig/Skija/ProfileMigration] "+issue.legacyName()+" "+issue.code()+": "+issue.detail());
                    if(!report.cancelled()&&report.notImported().isEmpty())preferences.set("migration.oneconfig.profiles.v1",true);
                    save();
                }));
            }
            for(var issue:snapshot.issues())System.out.println("[MuoniumConfig/Skija/Migration] "+issue.code()+" "+issue.file()+": "+issue.detail());
            if(snapshot.filesRead()>0)Notifications.shared().info("设置迁移",snapshot.issues().isEmpty()?"已读取原设置中心的界面偏好与 HUD。":"已迁移可识别配置；未确认条目保留在原文件并已记录日志。");
            save();
        },error->host.error("读取原设置中心配置",error));
    }
    private void pollBindings(){
        if(screen!=null&&screen.recording()){bindingEdges.clear();return;}
        Set<Integer> keys=new HashSet<>(),mouse=new HashSet<>();for(int i=1;i<256;i++)if(com.muoniumplayer.compat.lwjgl2.Keyboard.isKeyDown(i))keys.add(i);for(int i=0;i<8;i++)if(com.muoniumplayer.compat.lwjgl2.Mouse.isButtonDown(i))mouse.add(i);
        if(bindingEdges.pressed("menu",preferences.get("oneConfigKeybind","NONE"),keys,mouse,KeyTokens::token,false)){if(screen!=null)screen.menuKey();else if(System.nanoTime()-menuClosedAt>300_000_000L)SkijaConfigScreen.open();}
        if(bindingEdges.pressed("search",preferences.get("searchKeybind","ACTION+F"),keys,mouse,KeyTokens::token,false)&&screen!=null)screen.search();
    }
    public boolean ready() { update();return host!=null; }
    public boolean failed() { return failed; }
    public String failure() { return error; }
    public MuoniumHost host() { if(!ready())throw new IllegalStateException("UI session not ready");return host; }
    public UiPreferences preferences() { if(!ready())throw new IllegalStateException("UI session not ready");return preferences; }
    public HudRuntime runtime() { return runtime; }
    public Map<String,SkijaPainter.Svg> icons() { return loaded.icons(); }
    public byte[] avatar() { byte[] actual=avatarLoader==null?null:avatarLoader.loadCached(WindowAccess.profileId());return actual!=null?actual:loaded==null?null:loaded.avatar(); }
    public String translate(String key,String fallback,Object... args) {
        if(loaded==null)return fallback;Map<String,String> lang=loaded.language().getOrDefault(WindowAccess.language(),loaded.language().get("en_us"));String value=lang.getOrDefault(key,loaded.language().get("en_us").getOrDefault(key,fallback));
        try { return args.length==0?value:String.format(Locale.ROOT,value,args); }catch(IllegalFormatException ex) { host.error("翻译参数",ex);return fallback; }
    }
    public void opened(SkijaConfigScreen screen) { this.screen=screen;avatarLoader.request();sounds.open(Theme.active(preferences).id()); }
    public void beginClose() { if(sounds!=null)sounds.close(); }
    public void removed(SkijaConfigScreen screen) { if(this.screen!=screen)return;this.screen=null;menuClosedAt=System.nanoTime();sounds.releaseAmbience();save();NotificationIntegration.cancelInput(); }
    public HostTextRenderer text() {
        if(text==null) {text=HostTextRenderer.create(loaded.fonts(),SkijaGfx::canvas,()->Theme.active(preferences));host.textRenderer(text);}return text;
    }
    public SkijaPainter painter() {
        if(painter==null) {HostTextRenderer renderer=text();painter=new SkijaPainter(SkijaGfx::canvas,new SkijaPainter.Text() {
            public void draw(String value,double x,double y,double size,boolean bold,int color){renderer.draw(value,x,y,size,bold,color);}
            public double measure(String value,double size,boolean bold){return renderer.measure(value,size,bold);}
            public long textMetricsRevision(){return renderer.textMetricsRevision();}
            public com.muoniumplayer.configskija.ui.Painter.TextBounds textBounds(String value,double size,boolean bold){return renderer.textBounds(value,size,bold);}
            public void drawWeighted(String value,double x,double y,double size,int weight,int color){renderer.drawUi(value,x,y,size,Integer.toString(weight),false,color);}
            public double measureWeighted(String value,double size,int weight){return renderer.measureUi(value,size,Integer.toString(weight),false);}
        },loaded.icons(),()->Theme.active(preferences));}return painter;
    }
    public void save() { if(preferences==null||!dirty)return;dirty=false;store.save(preferences.snapshot()).whenComplete((ignored,error)->{if(error!=null)System.err.println("[MuoniumConfig/Skija] state save failed: "+error);}); }
    public static void afterHud() { if(WindowAccess.screen()==null)renderGlobal(true); }
    public static void afterScreen() { if(WindowAccess.screen()!=null)renderGlobal(false); }
    public static void renderGlobal() {
        renderGlobal(true);
    }
    private static void renderGlobal(boolean includeHud) {
        if(Boolean.getBoolean("muonium.config.skija.disabled"))return;
        try { current().renderOwnedFrame(includeHud); }
        catch(RuntimeException failure) { System.err.println("[MuoniumConfig/Skija] global render failed: "+failure); }
    }
    private void renderOwnedFrame(boolean includeHud) {
        update();if(host==null||screen!=null)return;
        boolean own=!SkijaGfx.active();if(own&&SkijaHost.beginFrame()==null)return;
        SkijaGfx.pushAttrib();SkijaGfx.pushMatrix();
        try {
            RenderSystem.refreshResolution();double physical=WindowAccess.physicalPerLayout();SkijaGfx.scale(physical/SkijaGfx.guiScale(),physical/SkijaGfx.guiScale());
            if(includeHud&&WindowAccess.inWorld())runtime.render(painter(),WindowAccess.hudFrame());
            renderToasts();
        }catch(RuntimeException ex){host.error("全局 HUD 绘制",ex);}finally{SkijaGfx.popMatrix();SkijaGfx.popAttrib();if(own)SkijaHost.endFrame();}
    }
    public void drawBackground(double width,double height,double alpha){if(backgroundWanted()){if(backdrop==null)backdrop=new ConfigBackdrop();backdrop.draw(width,height,alpha,WindowAccess.paused());}}
    public void renderToasts() {
        double now=System.nanoTime()/1_000_000_000d,delta=lastFrame==0?1d/60:Math.max(0,Math.min(.1,now-lastFrame));lastFrame=now;
        double physical=WindowAccess.physicalPerLayout();
        NotificationIntegration.Viewport viewport=NotificationIntegration.Viewport.fromGui(WindowAccess.guiWidth(),WindowAccess.guiHeight(),WindowAccess.guiScale());
        NotificationIntegration.tick(new NotificationIntegration.Frame(painter(),preferences,viewport,now,delta,WindowAccess.screen(),WindowAccess.focused()));
        SkijaGfx.pushMatrix();try{SkijaGfx.scale(1/physical,1/physical);NotificationIntegration.render(painter());}finally{SkijaGfx.popMatrix();}
    }
    public void pointerMove(double layoutX,double layoutY){NotificationIntegration.pointerMove(WindowAccess.screen(),layoutX*WindowAccess.physicalPerLayout(),layoutY*WindowAccess.physicalPerLayout());}
    public static boolean backgroundWanted(){SkijaConfigService s=instance;return !Boolean.getBoolean("muonium.config.skija.disabled")&&s!=null&&s.screen!=null&&s.preferences!=null&&s.preferences.flag("enableBackgroundBlur",true);}
    public static void shutdown() {
        SkijaConfigService s=instance;if(s==null)return;
        if(s.backdrop!=null){s.backdrop.close();s.backdrop=null;}
        if(s.text!=null){s.text.close();s.text=null;}
        if(s.painter!=null){s.painter.close();s.painter=null;}
        if(s.sounds!=null)s.sounds.stopImmediately();
        if(s.avatarLoader!=null){s.avatarLoader.invalidate();s.avatarAccount=null;}
        s.save();
    }
}
