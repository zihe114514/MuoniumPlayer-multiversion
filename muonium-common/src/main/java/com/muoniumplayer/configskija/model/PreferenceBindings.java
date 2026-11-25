package com.muoniumplayer.configskija.model;

import com.muoniumplayer.configskija.ui.pages.AppearanceBindings;
import java.util.*;
import java.util.function.IntFunction;

/** Source named keybind semantics: required keys/buttons and modifier subset, rising-edge actions. */
public final class PreferenceBindings {
    private final Map<String,Boolean> previous=new HashMap<>();
    public boolean pressed(String id,String chord,Set<Integer> keys,Set<Integer> mouse,IntFunction<String> names,boolean strictShift) {
        boolean down=matches(chord,keys,mouse,names,strictShift);boolean before=previous.put(id,down)==Boolean.TRUE;return down&&!before;
    }
    public static boolean matches(String chord,Set<Integer> keys,Set<Integer> mouse,IntFunction<String> names,boolean strictShift) {
        if(chord==null||chord.isBlank()||chord.equalsIgnoreCase("NONE"))return false;
        String resolved=AppearanceBindings.resolveAction(chord).toUpperCase(Locale.ROOT);int needed=0;boolean any=false;
        Set<String> held=new HashSet<>();for(int key:keys){held.add(AppearanceBindings.normalize(names.apply(key)));held.add("KEY:"+key);}
        for(String raw:resolved.split("\\+")) {
            String token=AppearanceBindings.normalize(raw);int bit=switch(token){case "CTRL"->1;case "SHIFT"->2;case "ALT"->4;case "SUPER"->8;default->0;};
            if(bit!=0){needed|=bit;continue;}
            any=true;if(token.startsWith("MOUSE:")){try{if(!mouse.contains(Integer.parseInt(token.substring(6))))return false;}catch(NumberFormatException ex){return false;}}
            else {String value=token.startsWith("KEY:")?token.substring(4):token;if(!held.contains(value)&&!held.contains(token))return false;}
        }
        int actual=(keys.contains(29)||keys.contains(157)?1:0)|(keys.contains(42)||keys.contains(54)?2:0)|(keys.contains(56)||keys.contains(184)?4:0)|(keys.contains(219)||keys.contains(220)?8:0);
        return (any||needed!=0)&&(actual&needed)==needed&&(!strictShift||(needed&2)!=0||(actual&2)==0);
    }
    public void clear(){previous.clear();}
}
