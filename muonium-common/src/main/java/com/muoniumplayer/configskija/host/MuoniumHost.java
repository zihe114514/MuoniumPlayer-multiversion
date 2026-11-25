package com.muoniumplayer.configskija.host;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.Presets;
import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.screens.hud.MuoniumKeysBridge;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.compat.mc.GuiScreen;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Direct binding to the public host model; no OneConfig reflection or duplicate music config. */
final class MuoniumHost implements ConfigHost,com.muoniumplayer.configskija.ui.settings.SettingsSchema.Provider,com.muoniumplayer.configskija.ui.pages.LibraryServices.Keys,com.muoniumplayer.configskija.ui.pages.LibraryServices.Information,com.muoniumplayer.configskija.ui.pages.LibraryServices.ProfileExtras,com.muoniumplayer.configskija.ui.pages.LibraryServices.PresetExtras,com.muoniumplayer.configskija.ui.pages.LibraryServices.ModCatalog,HudExtensions {
    private final Runnable close;
    private final com.muoniumplayer.configskija.model.UiPreferences preferences;
    private com.muoniumplayer.configskija.ui.hud.HudRuntime runtime;
    private com.muoniumplayer.configskija.host.text.HostTextRenderer textRenderer;
    private final List<Category> categories;
    private List<Category> favorites;
    private final List<HudElement> huds=List.of(new HostHud(true),new HostHud(false));
    private final java.util.Map<String,com.muoniumplayer.configskija.ui.settings.SettingsSchema.OptionStyle> optionStyles=new java.util.HashMap<>();
    private final com.muoniumplayer.configskija.model.KeyBindings keys=new com.muoniumplayer.configskija.host.keys.MinecraftKeyBindings(new com.muoniumplayer.configskija.host.keys.NativeKeyAccess());
    private com.muoniumplayer.configskija.host.sound.SoundSession sounds;
    private com.muoniumplayer.configskija.host.library.HostLibraryExtras library;
    private Presets presets;
    private com.muoniumplayer.configskija.model.Profiles profiles;
    MuoniumHost(Runnable close,com.muoniumplayer.configskija.model.UiPreferences preferences) { this.close=close;this.preferences=preferences;categories=readCategories(); }
    public com.muoniumplayer.configskija.ui.settings.SettingsSchema settingsSchema() {
        return new com.muoniumplayer.configskija.ui.settings.SettingsSchema() {
            public OptionStyle option(Option option) { return optionStyles.getOrDefault(option.id(),OptionStyle.forOption(option)); }
            public String text(String key,String fallback) { return MuoniumHost.this.translate(key,fallback); }
            public int accentEnd(com.muoniumplayer.configskija.ui.PageContext context) { return com.muoniumplayer.configskija.ui.Theme.accentEnd(context.preferences(),context.time()); }
            public int modifierBit(int code) { return switch(code) { case 29,157 -> 1;case 42,54 -> 2;case 56,184 -> 4;case 219,220 -> 8;default -> 0; }; }
        };
    }
    public List<Category> categories() { return categories; }
    public List<HudElement> huds() { return huds; }
    public com.muoniumplayer.configskija.model.KeyBindings keyBindings() { return library==null?keys:library.keyBindings(); }
    public String playerVersion() { return MuoniumPlayerExtension.VERSION; }
    public String configVersion() { return "Skija 0.1.0"; }
    public void soundSession(com.muoniumplayer.configskija.host.sound.SoundSession session) { sounds=session; }
    public void sound(String event) { if(sounds!=null) sounds.play(event); }
    public Presets presets() { if(presets==null) presets=new MuoniumPresets();return presets; }
    public com.muoniumplayer.configskija.model.Profiles profiles() { if(profiles==null) profiles=new MuoniumProfiles();return profiles; }
    public void dispose() {}
    public void initializeLibrary() { if(library==null) library=new com.muoniumplayer.configskija.host.library.HostLibraryExtras((MuoniumProfiles)profiles(),(MuoniumPresets)presets(),this,preferences,com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil::runOnMainThread,keys); }
    public void tickLibrary(boolean open) { if(library!=null)library.tick(open); }
    public void shutdownLibrary(){if(library!=null)library.close();}
    public void persist() { HudConfig.save(); }
    public void close() { close.run(); }
    public String clipboard() { return GuiScreen.getClipboardString(); }
    public void clipboard(String text) { GuiScreen.setClipboardString(text); }
    public double uiDensity() { return WindowAccess.density(); }
    public double physicalPerLayout() { return WindowAccess.physicalPerLayout(); }
    public String playerName() { return WindowAccess.playerName(); }
    public String versionLabel() { return "MuoniumPlayer " + MuoniumPlayerExtension.VERSION; }
    public String translate(String key,String fallback,Object... args) { return SkijaConfigService.get().translate(key,fallback,args); }
    public byte[] playerHead() { return SkijaConfigService.get().avatar(); }
    public String playerKey() { return MuoniumKeysBridge.openPlayerKeyName(); }
    public String editorKey() { return MuoniumKeysBridge.editHudKeyName(); }
    public void error(String action,Throwable error) { System.err.println("[MuoniumConfig/Skija] "+action+": "+error);error.printStackTrace(System.err);com.muoniumplayer.configskija.model.Notifications.shared().error(action,error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()); }
    public java.util.Set<HudExtensions.Capability> hudCapabilities() {
        java.util.EnumSet<HudExtensions.Capability> set=java.util.EnumSet.of(HudExtensions.Capability.ADDITIONAL_HUDS,HudExtensions.Capability.HOST_SETTINGS,HudExtensions.Capability.GUI_VISIBILITY);
        if(textRenderer!=null) set.add(HudExtensions.Capability.EXACT_TEXT);return java.util.Set.copyOf(set);
    }
    public String hudOwner() { return preferences.get("profile.active","default"); }
    public com.muoniumplayer.configskija.ui.hud.HudDocument loadHudDocument() {
        String saved=preferences.get("hud.document","");return saved.isEmpty()?com.muoniumplayer.configskija.ui.hud.HudDocument.empty():com.muoniumplayer.configskija.ui.hud.HudDocument.decode(saved);
    }
    public void saveHudDocument(com.muoniumplayer.configskija.ui.hud.HudDocument document) { preferences.set("hud.document",document.encode()); }
    public void bindHudRuntime(com.muoniumplayer.configskija.ui.hud.HudRuntime runtime) { this.runtime=runtime; }
    public com.muoniumplayer.configskija.ui.hud.HudRuntime existingHudRuntime(){return runtime;}
    public com.muoniumplayer.configskija.ui.hud.HudRuntime runtime() { return runtime; }
    public com.muoniumplayer.configskija.ui.hud.HudRuntime.Frame hudFrame() { return WindowAccess.hudFrame(); }
    public List<HudElement> hudProviders() { return List.of(); }
    public List<ConfigHost.Group> hudSettings(HudElement element) {
        String keyword=element.id().contains("lyrics")?"lyric":"info";List<ConfigHost.Group> groups=new ArrayList<>();
        for(Category category:categories) if(category.id().toLowerCase(java.util.Locale.ROOT).contains(keyword)||category.title().contains(keyword.equals("lyric")?"歌词":"信息")) groups.addAll(category.groups());return List.copyOf(groups);
    }
    public void textRenderer(com.muoniumplayer.configskija.host.text.HostTextRenderer renderer) { textRenderer=renderer; }
    public com.muoniumplayer.configskija.ui.hud.HudRuntime.TextRenderer hudTextRenderer() { return textRenderer; }
    public void renderHostHud(com.muoniumplayer.configskija.ui.Painter painter,HudElement element,Geometry.Rect bounds,com.muoniumplayer.configskija.ui.hud.HudRuntime.Frame frame) {
        // Legacy widgets use the host matrix stack; their preview cannot own editor chrome state.
        int depth=com.muoniumplayer.render.skija.SkijaGfx.saveDepth();
        com.muoniumplayer.render.skija.SkijaGfx.pushAttrib();
        com.muoniumplayer.render.skija.SkijaGfx.pushMatrix();
        try { element.preview(bounds.x(),bounds.y()); }
        finally {
            com.muoniumplayer.render.skija.SkijaGfx.restoreDepthTo(depth);
            com.muoniumplayer.render.skija.SkijaGfx.popAttrib();
        }
    }
    public void renderHudShape(com.muoniumplayer.configskija.ui.Painter painter,com.muoniumplayer.configskija.ui.hud.HudMerge.Shape shape) {
        double[][] loops=new double[shape.loops().size()][];
        for(int i=0;i<loops.length;i++) { var source=shape.loops().get(i);loops[i]=new double[source.size()*2];for(int j=0;j<source.size();j++) {loops[i][j*2]=source.get(j).x();loops[i][j*2+1]=source.get(j).y();} }
        painter.roundedPolygon(loops,shape.radius(),shape.color());
    }
    public java.util.Set<String> capabilities(){initializeLibrary();return library.capabilities();}
    public boolean separateControls(){initializeLibrary();return library.separateControls();}
    public void separateControls(boolean enabled){initializeLibrary();library.separateControls(enabled);}
    public void openProfilesFolder(){initializeLibrary();library.openProfilesFolder();}
    public void exportProfile(String id){initializeLibrary();library.exportProfile(id);}
    public String profileIcon(String id){initializeLibrary();return library.profileIcon(id);}
    public void profileIcon(String id,String icon){initializeLibrary();library.profileIcon(id,icon);}
    public com.muoniumplayer.configskija.model.KeyBindings.Entry profileKey(String id){initializeLibrary();return library.profileKey(id);}
    public boolean busy(){initializeLibrary();return library.busy();}
    public String facetDescription(String id){initializeLibrary();return library.facetDescription(id);}
    public String cardSummary(String id){initializeLibrary();return library.cardSummary(id);}
    public int changedKeys(){initializeLibrary();return library.changedKeys();}
    public boolean previewOk(){initializeLibrary();return library.previewOk();}
    public boolean canOpenFolder(){initializeLibrary();return library.canOpenFolder();}
    public boolean canImportFile(){initializeLibrary();return library.canImportFile();}
    public void openPresetsFolder(){initializeLibrary();library.openPresetsFolder();}
    public void importPresetFile(){initializeLibrary();library.importPresetFile();}
    public List<com.muoniumplayer.configskija.ui.pages.LibraryMods.Card> modCards(){initializeLibrary();return library.modCards();}
    public List<Category> favoriteCategories(){
        if(favorites!=null)return favorites;
        List<Option> switches=new ArrayList<>(),sliders=new ArrayList<>();
        switches.add(favoriteToggle("muonium.fav.lyrics","桌面歌词","屏幕下方的滚动歌词。关掉后不再绘制。",()->HudConfig.musicLyricsEnabled,value->new HostHud(true).hidden(!value)));
        switches.add(favoriteToggle("muonium.fav.info","歌曲信息卡","左上角的歌名、封面与进度条。",()->HudConfig.musicInfoEnabled,value->new HostHud(false).hidden(!value)));
        switches.add(favoriteToggle("muonium.fav.island","灵动岛","屏幕顶部的胶囊通知：切歌、音量、下载进度。",()->HudConfig.dynamicIslandEnabled,value->HudConfig.dynamicIslandEnabled=value));
        switches.add(favoriteToggle("muonium.fav.spectrum","音符频谱","跟着音乐跳动的柱状频谱。",()->HudConfig.spectrumEnabled,value->HudConfig.spectrumEnabled=value));
        switches.add(favoriteToggle("muonium.fav.acoustics","场景声学","按所处环境（水下、洞穴、雨天）给音乐加混响。",com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings::isEnabled,com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings::setEnabled));
        sliders.add(favoriteSlider("muonium.fav.lyric.font","歌词字号","桌面歌词的基础字号。",.6,1.8,()->(double)HudConfig.lyricFontScale,value->HudConfig.lyricFontScale=value.floatValue()));
        sliders.add(favoriteSlider("muonium.fav.island.scale","灵动岛大小","顶部胶囊的缩放。",.6,1.35,()->(double)HudConfig.dynamicIslandScale,value->HudConfig.dynamicIslandScale=value.floatValue()));
        sliders.add(favoriteSlider("muonium.fav.spectrum.opacity","频谱不透明度","柱子有多显眼。",.1,1,()->(double)HudConfig.spectrumOpacity,value->HudConfig.spectrumOpacity=value.floatValue()));
        favorites=List.of(new Category("muonium.favorites","HUD 叠加层","","star",List.of(new Group("muonium.favorites/switches","显示开关",switches),new Group("muonium.favorites/sliders","常用调节",sliders))));return favorites;
    }
    private Option favoriteToggle(String id,String title,String description,Supplier<Boolean> getter,Consumer<Boolean> setter){boolean initial=getter.get();return new Option(id,title,description,Kind.TOGGLE,0,1,1,List.of(),getter::get,value->setter.accept((Boolean)value),()->"",()->setter.accept(initial));}
    private Option favoriteSlider(String id,String title,String description,double min,double max,Supplier<Double> getter,Consumer<Double> setter){double initial=getter.get();return new Option(id,title,description,Kind.SLIDER,min,max,.01,List.of(),getter::get,value->setter.accept(((Number)value).doubleValue()),()->"",()->setter.accept(initial));}
    private List<Category> readCategories() {
        List<Category> out=new ArrayList<>();
        java.util.Map<String,Integer> identities=new java.util.HashMap<>();
        for(HudSettingsModel.Category category:HudSettingsModel.categories()) {
            String categoryId=category.id.isBlank()?"muonium.category."+UUID.nameUUIDFromBytes(category.title.getBytes(StandardCharsets.UTF_8)):category.id;
            List<Group> groups=new ArrayList<>();int groupIndex=0;
            for(HudSettingsModel.Group group:category.groups) {
                String groupId=categoryId+"/"+groupIndex++; List<Option> options=new ArrayList<>();
                for(HudSettingsModel.Entry entry:group.entries) {
                    // THEME_GRID duplicates the same host theme choice; the original bridge omits it too.
                    if(entry.kind==HudSettingsModel.Kind.THEME_GRID) continue;
                    String id=entry.setting!=null?"setting/"+entry.setting.name():categoryId+"/"+UUID.nameUUIDFromBytes((group.title+"\n"+entry.description+"\n"+entry.kind+"\n"+entry.colorTarget).getBytes(StandardCharsets.UTF_8));
                    int occurrence=identities.merge(id,1,Integer::sum);if(occurrence>1) id=id+"/"+occurrence;
                    Supplier<Object> read;Consumer<Object> write;Kind kind;
                    switch(entry.kind) {
                        case TOGGLE -> { kind=Kind.TOGGLE;read=entry::isOn;write=v->{ if(entry.isOn()!=(Boolean)v) entry.activate(); }; }
                        case SLIDER -> { kind=Kind.SLIDER;read=()->(double)entry.setting.getValue();write=v->{ entry.setting.setValue(((Number)v).floatValue());HudSettingsModel.syncWorldLyricsQuality();HudSettingsModel.syncAnimatedCoverModuleValue();HudSettingsModel.syncLyricViewportModuleValues(); }; }
                        case CHOICE -> { kind=entry.getChoices().length==0?Kind.ACTION:Kind.CHOICE;read=entry::getValueText;write=v->{ if(entry.getChoices().length==0) entry.activate();else entry.setChoice(String.valueOf(v)); }; }
                        case COLOR -> { kind=Kind.COLOR;read=()->entry.getColor().getRGB();write=v->entry.setColor(new Color(((Number)v).intValue(),true)); }
                        case ACTION -> { kind=Kind.ACTION;read=()->"";write=v->entry.activate(); }
                        default -> throw new IllegalStateException("Unexpected model kind: "+entry.kind);
                    }
                    Object initial=read.get();Object defaultValue=kind==Kind.TOGGLE?entry.getToggleDefaultState():initial;
                    Consumer<Object> setter=write;
                    Option built=new Option(id,entry.getLabel(),entry.description,kind,entry.setting==null?0:entry.setting.getMin(),entry.setting==null?1:entry.setting.getMax(),.01,Arrays.asList(entry.getChoices()),read,write,()->!entry.isVisible()?"需要先启用相关功能；此项会随主项目状态即时更新":entry.isLocked()?"当前运行环境下不可用": "",kind==Kind.ACTION?null:()->setter.accept(defaultValue),entry::getLabel);
                    var style=com.muoniumplayer.configskija.ui.settings.SettingsSchema.OptionStyle.forOption(built);
                    if(kind==Kind.ACTION) style.buttonText="执行";
                    if(entry.kind==HudSettingsModel.Kind.CHOICE&&entry.getChoices().length==0) { style.visualizer=com.muoniumplayer.configskija.ui.settings.SettingsSchema.Visualizer.BUTTON;style.buttonText=entry.getValueText(); }
                    if(kind==Kind.SLIDER) style.format=value->HudSettingsModel.formatValue(entry.setting,(float)value);
                    optionStyles.put(id,style);options.add(built);
                }
                groups.add(new Group(groupId,group.title,List.copyOf(options)));
            }
            out.add(new Category(categoryId,category.title,category.subtitle,category.icon,List.copyOf(groups)));
        }
        return List.copyOf(out);
    }
    private static final class HostHud implements HudElement {
        private final boolean lyrics;
        HostHud(boolean lyrics) { this.lyrics=lyrics; }
        public String id() { return lyrics?"muonium.lyrics":"muonium.info"; }
        public String title() { return lyrics?"桌面歌词":"歌曲信息卡"; }
        public double x() { return lyrics?HudConfig.lyricX:HudConfig.infoX; }
        public double y() { return lyrics?HudConfig.lyricY:HudConfig.infoY; }
        public double width() { return (lyrics?HudConfig.lyricWidth:230)*scale(); }
        public double height() { return (lyrics?HudConfig.lyricHeight:56)*scale(); }
        public double scale() { return lyrics?HudConfig.lyricScale:HudConfig.infoScale; }
        public boolean hidden() { return lyrics?!HudConfig.musicLyricsEnabled:!HudConfig.musicInfoEnabled; }
        public boolean aspectLocked() { return !lyrics; }
        public void position(double x,double y) { if(lyrics) { HudConfig.lyricX=(float)x;HudConfig.lyricY=(float)y; }else { HudConfig.infoX=(float)x;HudConfig.infoY=(float)y; } }
        public void size(double width,double height) {
            if(lyrics) { HudConfig.lyricWidth=(float)Geometry.clamp(width/scale(),225,900);HudConfig.lyricHeight=(float)Geometry.clamp(height/scale(),60,480);HudSettingsModel.syncLyricViewportModuleValues(); }
            else scale(width/230);
        }
        public void scale(double value) { value=Geometry.clamp(value,.25,4);if(lyrics) HudConfig.lyricScale=(float)value;else HudConfig.infoScale=(float)value; }
        public void hidden(boolean hidden) {
            if(lyrics) HudConfig.musicLyricsEnabled=!hidden;else HudConfig.musicInfoEnabled=!hidden;
            MuoniumPlayerExtension ext=MuoniumPlayerExtension.getInstance();
            if(ext!=null) { if(lyrics&&ext.musicLyrics!=null) ext.musicLyrics.setEnabled(!hidden);if(!lyrics&&ext.musicInfo!=null) ext.musicInfo.setEnabled(!hidden); }
        }
        public void preview(double x,double y) {
            MuoniumPlayerExtension ext=MuoniumPlayerExtension.getInstance();if(ext==null||hidden()) return;
            if(lyrics) { MusicLyricsWidget widget=ext.musicLyrics;if(widget!=null) widget.renderEditorPreview((float)x,(float)y,(float)scale()); }
            else { MusicInfoWidget widget=ext.musicInfo;if(widget!=null) widget.renderEditorPreview((float)x,(float)y,(float)scale()); }
        }
    }
}
