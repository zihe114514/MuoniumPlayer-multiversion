package com.muoniumplayer.configskija.host.library;

import com.muoniumplayer.configskija.model.KeyBindings;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stable legacy-code storage. Native GLFW/SDL values never enter local profile archives. */
public final class ProfileChords {
    private ProfileChords(){}
    public static String id(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    public static String decodeId(String value){return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);}
    public static String encode(KeyBindings.Chord chord){return chord.modifiers()+";"+join(chord.keys())+";"+join(chord.mouse());}
    private static String join(List<Integer> codes){return String.join(",",codes.stream().map(String::valueOf).toList());}
    public static KeyBindings.Chord decode(String text){String[] parts=text.split(";",-1);if(parts.length!=3)throw new IllegalArgumentException("无效配置键位");int modifiers=Integer.parseInt(parts[0]);if(modifiers<0||modifiers>15)throw new IllegalArgumentException("无效修饰键");return new KeyBindings.Chord(codes(parts[1],1,255),codes(parts[2],0,7),modifiers);}
    private static List<Integer> codes(String value,int min,int max){if(value.isEmpty())return List.of();String[] parts=value.split(",",-1);if(parts.length>16)throw new IllegalArgumentException("组合键过长");List<Integer> out=new ArrayList<>();for(String part:parts){int code=Integer.parseInt(part);if(code<min||code>max)throw new IllegalArgumentException("键码超出已核实范围");out.add(code);}return List.copyOf(out);}
    public static Map<String,String> capture(KeyBindings keys){Map<String,String> out=new LinkedHashMap<>();for(KeyBindings.Entry entry:keys.entries())if(entry.editable())out.put("controls."+id(entry.id()),encode(entry.current()));return Map.copyOf(out);}
    public static void restore(KeyBindings keys,Map<String,String> snapshot){
        Map<String,KeyBindings.Entry> available=new HashMap<>();keys.entries().forEach(entry->available.put(entry.id(),entry));Map<String,KeyBindings.Chord> before=new LinkedHashMap<>();
        try{for(var item:snapshot.entrySet())if(item.getKey().startsWith("controls.")){String id=decodeId(item.getKey().substring(9));KeyBindings.Entry entry=available.get(id);if(entry==null||!entry.editable())continue;KeyBindings.Chord next=decode(item.getValue());if(!entry.current().equals(next)){before.put(id,entry.current());keys.set(id,next);}}}
        catch(RuntimeException broken){for(var item:before.entrySet())try{keys.set(item.getKey(),item.getValue());}catch(RuntimeException rollback){broken.addSuppressed(rollback);}throw broken;}
    }
}
