package com.muoniumplayer.configskija.model;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The application contract. Rendering never owns a second copy of music settings. */
public interface ConfigHost {
    enum Kind { TOGGLE, SLIDER, CHOICE, COLOR, ACTION }
    record Option(String id, String title, String description, Kind kind, double min, double max,
                  double step, List<String> choices, Supplier<Object> read, Consumer<Object> write,
                  Supplier<String> disabledReason, Runnable reset, Supplier<String> liveTitle) {
        public Option(String id,String title,String description,Kind kind,double min,double max,double step,List<String> choices,Supplier<Object> read,Consumer<Object> write,Supplier<String> disabledReason,Runnable reset) {
            this(id,title,description,kind,min,max,step,choices,read,write,disabledReason,reset,null);
        }
        public String title() { return liveTitle==null?title:liveTitle.get(); }
        public boolean enabled() { return disabledReason.get().isEmpty(); }
        public Object value() { return read.get(); }
        public void set(Object value) { if (enabled()) write.accept(value); }
    }
    record Group(String id, String title, List<Option> options) {}
    record Category(String id, String title, String subtitle, String icon, List<Group> groups) {}
    List<Category> categories();
    default List<Category> favoriteCategories(){return List.of();}
    List<HudElement> huds();
    default Presets presets() { return null; }
    default Profiles profiles() { return null; }
    default void dispose() {}
    void persist();
    void close();
    String clipboard();
    void clipboard(String text);
    void error(String action, Throwable error);
    default double uiDensity() { return 1; }
    default double physicalPerLayout() { return 1; }
    default String playerName() { return "Player"; }
    default String versionLabel() { return "MuoniumPlayer"; }
    default byte[] playerHead() { return null; }
    default String translate(String key,String fallback,Object... args) { return args.length==0?fallback:String.format(fallback,args); }
    default String playerKey() { return ""; }
    default String editorKey() { return ""; }
    default void sound(String event) {}
}
