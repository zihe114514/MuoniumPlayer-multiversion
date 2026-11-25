package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.KeyBindings;
import java.util.*;

/** Recording state is discarded on leave; no host binding is changed until commit/explicit clear. */
public final class KeysRecording {
    private final LinkedHashSet<Integer> keys=new LinkedHashSet<>(),mouse=new LinkedHashSet<>();
    private final Set<Integer> heldKeys=new HashSet<>(),heldMouse=new HashSet<>();
    public void key(int code,boolean pressed){if(code<=0)return;if(pressed){keys.add(code);heldKeys.add(code);}else heldKeys.remove(code);}
    public void mouse(int button,boolean pressed){if(button<0)return;if(pressed){mouse.add(button);heldMouse.add(button);}else heldMouse.remove(button);}
    public boolean any(){return !keys.isEmpty()||!mouse.isEmpty();}
    public boolean released(){return any()&&heldKeys.isEmpty()&&heldMouse.isEmpty();}
    public void poll(KeyBindings.InputState input){heldKeys.removeIf(key->!input.keyDown(key));heldMouse.removeIf(button->!input.mouseDown(button));}
    public KeyBindings.Chord raw(){return new KeyBindings.Chord(List.copyOf(keys),List.copyOf(mouse),0);}
    public KeyBindings.Chord chord(){
        List<Integer> ordinary=new ArrayList<>();int modifiers=0;
        for(int key:keys){int bit=modifier(key);if(bit==0)ordinary.add(key);else modifiers|=bit;}
        if(ordinary.isEmpty()&&mouse.isEmpty())return raw();
        return new KeyBindings.Chord(ordinary,List.copyOf(mouse),modifiers);
    }
    // These are the public LWJGL2 codes supplied by every host compat Keyboard, not native GLFW/SDL codes.
    private static int modifier(int code){return switch(code){case 29,157->KeyBindings.CONTROL;case 42,54->KeyBindings.SHIFT;case 56,184->KeyBindings.ALT;case 219,220->KeyBindings.SUPER;default->0;};}
}
