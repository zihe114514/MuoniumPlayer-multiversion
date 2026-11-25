package com.muoniumplayer.configskija.host.keys;

import com.muoniumplayer.configskija.model.KeyBindings;
import java.util.List;

/** Implemented by one independently verified version adapter. Contains no MC or loader types. */
public interface NativeKeys extends KeyBindings.InputState {
    record Mapping(String id,String category,String title,String display,KeyBindings.Chord current,
                   KeyBindings.Chord defaults,String unavailable) {}
    List<Mapping> mappings();
    void set(String id,KeyBindings.Chord chord);
    void reset(String id);
    String display(KeyBindings.Chord chord);
}
