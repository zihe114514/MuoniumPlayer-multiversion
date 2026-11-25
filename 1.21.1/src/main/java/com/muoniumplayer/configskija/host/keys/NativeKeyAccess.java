package com.muoniumplayer.configskija.host.keys;

import com.mojang.blaze3d.platform.InputConstants;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.configskija.model.KeyBindings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import java.util.*;

/** 1.21.1: both resolved loader JARs checked separately; see docs/library-parity.md. Client thread only. */
public final class NativeKeyAccess implements NativeKeys {
    private InputConstants.Key current(KeyMapping mapping){return InputConstants.getKey(mapping.saveString());}
    private KeyMapping find(String id){return Arrays.stream(Minecraft.getInstance().options.keyMappings).filter(k->("minecraft."+k.getName()).equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("按键不存在："+id));}
    private int mouseNative(int button){
        return switch(button){case 0->InputConstants.MOUSE_BUTTON_LEFT;case 1->InputConstants.MOUSE_BUTTON_RIGHT;case 2->InputConstants.MOUSE_BUTTON_MIDDLE;default->button;};
    }
    private int mouseLegacy(int button){
        if(button==InputConstants.MOUSE_BUTTON_LEFT)return 0;if(button==InputConstants.MOUSE_BUTTON_RIGHT)return 1;if(button==InputConstants.MOUSE_BUTTON_MIDDLE)return 2;return button;
    }
    private KeyBindings.Chord chord(InputConstants.Key key){
        if(key==InputConstants.UNKNOWN)return KeyBindings.Chord.unbound();
        if(key.getType()==InputConstants.Type.MOUSE)return KeyBindings.Chord.mouse(mouseLegacy(key.getValue()));
        if(key.getType()==InputConstants.Type.KEYSYM){int legacy=Keyboard.fromGlfw(key.getValue());if(legacy>0)return KeyBindings.Chord.keyboard(legacy);}
        return KeyBindings.Chord.unbound();
    }
    private InputConstants.Key key(KeyBindings.Chord chord){
        if(!chord.bound())return InputConstants.UNKNOWN;
        if(!chord.mouse().isEmpty()){int button=chord.mouse().get(0);if(button<0||button>7)throw new IllegalArgumentException("鼠标按钮不在 0..7 范围内");return InputConstants.Type.MOUSE.getOrCreate(mouseNative(button));}
        int nativeCode=Keyboard.toGlfw(chord.keys().get(0));
        if(nativeCode<0)throw new IllegalArgumentException("此物理键没有已核实的宿主映射");
        return InputConstants.Type.KEYSYM.getOrCreate(nativeCode);
    }
    public List<Mapping> mappings(){
        List<Mapping> out=new ArrayList<>();
        for(KeyMapping mapping:Minecraft.getInstance().options.keyMappings){
            InputConstants.Key key=current(mapping);KeyBindings.Chord value=chord(key);
            String unavailable=key!=InputConstants.UNKNOWN&&!value.bound()?"此键使用当前 legacy 输入桥无法表达的原生键码；可在 Minecraft 控制菜单中编辑":"";
            String category=I18n.get(mapping.getCategory());
            out.add(new Mapping("minecraft."+mapping.getName(),category,I18n.get(mapping.getName()),mapping.getTranslatedKeyMessage().getString(),value,chord(mapping.getDefaultKey()),unavailable));
        }
        return List.copyOf(out);
    }
    public void set(String id,KeyBindings.Chord chord){
        if(chord.keys().size()+chord.mouse().size()>1||chord.modifiers()!=0)throw new IllegalArgumentException("原生键位只接受一个键或鼠标按钮");
        KeyMapping mapping=find(id);InputConstants.Key next=key(chord);mapping.setDown(false);mapping.setKey(next);KeyMapping.resetMapping();Minecraft.getInstance().options.save();
    }
    public void reset(String id){KeyMapping mapping=find(id);mapping.setDown(false);mapping.setKey(mapping.getDefaultKey());KeyMapping.resetMapping();Minecraft.getInstance().options.save();}
    public String display(KeyBindings.Chord chord){if(!chord.bound())return "None";List<String> names=new ArrayList<>();
        if((chord.modifiers()&KeyBindings.CONTROL)!=0)names.add("Ctrl");if((chord.modifiers()&KeyBindings.SHIFT)!=0)names.add("Shift");if((chord.modifiers()&KeyBindings.ALT)!=0)names.add("Alt");if((chord.modifiers()&KeyBindings.SUPER)!=0)names.add("Super");
        for(int code:chord.keys())names.add(key(KeyBindings.Chord.keyboard(code)).getDisplayName().getString());
        for(int button:chord.mouse())names.add(key(KeyBindings.Chord.mouse(button)).getDisplayName().getString());return String.join(" + ",names);
    }
    public boolean keyDown(int code){return Keyboard.isKeyDown(code);}
    public boolean mouseDown(int button){return Mouse.isButtonDown(button);}
}
