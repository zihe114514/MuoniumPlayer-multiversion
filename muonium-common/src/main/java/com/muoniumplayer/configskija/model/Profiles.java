package com.muoniumplayer.configskija.model;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Local profiles contain trusted host snapshots; they are separate from shareable template presets. */
public interface Profiles {
    record Profile(String id,String name,long modified) {}
    List<Profile> list();
    String status();
    boolean busy();
    String active();
    void refresh();
    void save(String name,Map<String,String> ui);
    void activate(String id,Consumer<Map<String,String>> restoreUi);
    void rename(String id,String name);
    void duplicate(String id,String name);
    void delete(String id);
    void close();
}
