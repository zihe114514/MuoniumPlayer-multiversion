package com.muoniumplayer.configskija.model;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;

/** Live key mappings. Keyboard codes use the host GuiScreen's LWJGL2 compatibility namespace. */
public interface KeyBindings {
    int CONTROL = 1, SHIFT = 2, ALT = 4, SUPER = 8;
    record Chord(List<Integer> keys, List<Integer> mouse, int modifiers) {
        public Chord {
            keys = keys.stream().filter(k -> k > 0).distinct().sorted().toList();
            mouse = mouse.stream().filter(k -> k >= 0).distinct().sorted().toList();
            modifiers &= 15;
        }
        public static Chord unbound() { return new Chord(List.of(), List.of(), 0); }
        public static Chord keyboard(int code) { return new Chord(List.of(code), List.of(), 0); }
        public static Chord mouse(int button) { return new Chord(List.of(), List.of(button), 0); }
        public boolean bound() { return !keys.isEmpty() || !mouse.isEmpty(); }
    }
    record Entry(String id, String groupId, String groupTitle, String groupIcon, String category,
                 String subcategory, String title, String description, String display,
                 Chord current, Chord defaults, boolean singleKey, String unavailable, List<String> tags, String path) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(current); Objects.requireNonNull(defaults);
            tags=List.copyOf(tags);path=path==null?id:path;
            unavailable = unavailable == null ? "" : unavailable;
        }
        public Entry(String id,String groupId,String groupTitle,String groupIcon,String category,String subcategory,String title,String description,String display,Chord current,Chord defaults,boolean singleKey,String unavailable){
            this(id,groupId,groupTitle,groupIcon,category,subcategory,title,description,display,current,defaults,singleKey,unavailable,List.of(),id);
        }
        public boolean editable() { return unavailable.isEmpty(); }
        public boolean changed() { return !current.equals(defaults); }
    }
    /** Optional raw state used to finish multi-key recording on release. */
    interface InputState { boolean keyDown(int legacyCode); boolean mouseDown(int button); }
    List<Entry> entries();
    void set(String id, Chord chord);
    void reset(String id);
    String display(Chord chord);
    default String status() { return ""; }
    private static boolean contextGated(String id) {
        return id.startsWith("minecraft.key.debug.") || id.startsWith("minecraft.key.spectator") || id.startsWith("minecraft.key.toggleSpectatorShaderEffects");
    }
    default Map<String,List<Entry>> conflictMap(List<Entry> catalog){
        Map<Chord,List<Entry>> bound=new LinkedHashMap<>();
        for(Entry entry:catalog)if(entry.current().bound()&&!contextGated(entry.id()))bound.computeIfAbsent(entry.current(),key->new ArrayList<>()).add(entry);
        Map<String,List<Entry>> conflicts=new LinkedHashMap<>();
        for(List<Entry> group:bound.values())if(group.size()>1)for(Entry entry:group)conflicts.put(entry.id(),group.stream().filter(other->!other.id().equals(entry.id())).toList());
        return Map.copyOf(conflicts);
    }
    default List<Entry> conflicts(Entry entry) {
        return !entry.current().bound() ? List.of() : entries().stream()
                .filter(other -> !other.id().equals(entry.id()) && !contextGated(entry.id()) && !contextGated(other.id()) && other.current().equals(entry.current())).toList();
    }
}
