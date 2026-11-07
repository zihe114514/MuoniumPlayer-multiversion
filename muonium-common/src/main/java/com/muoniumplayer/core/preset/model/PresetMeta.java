package com.muoniumplayer.core.preset.model;

/** Preset metadata. Diagnostic labels never control application. */
public final class PresetMeta {
    private final String id, name, author, description, created, generatorMod, generatorMc;

    public PresetMeta(String id, String name, String author, String description, String created,
                      String generatorMod, String generatorMc) {
        this.id = nz(id);
        this.name = nz(name);
        this.author = nz(author);
        this.description = nz(description);
        this.created = nz(created);
        this.generatorMod = nz(generatorMod);
        this.generatorMc = nz(generatorMc);
    }

    private static String nz(String value) { return value == null ? "" : value; }
    public String id() { return id; }
    public String name() { return name; }
    public String author() { return author; }
    public String description() { return description; }
    public String created() { return created; }
    public String generatorMod() { return generatorMod; }
    public String generatorMc() { return generatorMc; }
    public PresetMeta withId(String newId) {
        return new PresetMeta(newId, name, author, description, created, generatorMod, generatorMc);
    }
    public PresetMeta withName(String newName) {
        return new PresetMeta(id, newName, author, description, created, generatorMod, generatorMc);
    }
}
