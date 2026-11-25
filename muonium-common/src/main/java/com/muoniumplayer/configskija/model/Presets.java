package com.muoniumplayer.configskija.model;

import java.util.List;

/** UI adapter for MuoniumPlayer's existing preset engine and its selective apply/undo semantics. */
public interface Presets {
    record Card(String id,String name,String description,boolean builtin) {}
    record Facet(String id,String name,String group,boolean selected,boolean optIn) {}
    String page();
    String status();
    List<Card> cards();
    List<Facet> facets();
    List<String> preview();
    String targetName();
    boolean canUndo();
    void choose(String id);
    void main();
    void savePage();
    void toggle(String id);
    void apply();
    void save(String name,boolean copy);
    void copy(String id);
    void delete(String id);
    void importText(String text);
    void undo();
}
