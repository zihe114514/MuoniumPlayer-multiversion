package com.muoniumplayer.configskija.host.keys;

import com.muoniumplayer.configskija.model.KeyBindings;
import java.util.*;

/** Reads existing options.keyMappings; never registers, mirrors or duplicates an H binding. */
public final class MinecraftKeyBindings implements KeyBindings, KeyBindings.InputState {
    private final NativeKeys nativeKeys;
    public MinecraftKeyBindings(NativeKeys nativeKeys){this.nativeKeys=Objects.requireNonNull(nativeKeys);}
    public List<Entry> entries(){
        List<Entry> entries=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(NativeKeys.Mapping mapping:nativeKeys.mappings()){
            if(!seen.add(mapping.id()))continue;
            String category=mapping.category();String group="minecraft-keybinds."+category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]","-")+"-"+Integer.toHexString(category.hashCode());
            entries.add(new Entry(mapping.id(),group,category,"keyboard","Minecraft","Controls",mapping.title(),"",mapping.display(),mapping.current(),mapping.defaults(),true,mapping.unavailable()));
        }
        entries.sort(Comparator.comparing(Entry::groupTitle,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::title,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));return List.copyOf(entries);
    }
    public void set(String id,Chord chord){
        Entry entry=entries().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("按键不存在："+id));
        if(!entry.editable())throw new IllegalStateException(entry.unavailable());
        if(chord.keys().size()+chord.mouse().size()>1||chord.modifiers()!=0)throw new IllegalArgumentException("原生 Minecraft 键位只接受一个键或鼠标按钮");
        nativeKeys.set(id,chord);
    }
    public void reset(String id){nativeKeys.reset(id);}
    public String display(Chord chord){return nativeKeys.display(chord);}
    public boolean keyDown(int key){return nativeKeys.keyDown(key);}
    public boolean mouseDown(int button){return nativeKeys.mouseDown(button);}
}
