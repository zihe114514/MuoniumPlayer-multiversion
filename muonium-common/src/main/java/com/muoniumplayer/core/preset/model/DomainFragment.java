package com.muoniumplayer.core.preset.model;

import com.google.gson.JsonObject;

/** Immutable, versioned sparse values for one configuration domain. */
public final class DomainFragment {
    private final int version;
    private final JsonObject values;

    public DomainFragment(int version, JsonObject values) {
        this.version = Math.max(1, version);
        this.values = values == null ? new JsonObject() : values.deepCopy();
    }

    public int version() { return version; }
    public JsonObject values() { return values.deepCopy(); }
    public boolean isEmpty() { return values.size() == 0; }
}
