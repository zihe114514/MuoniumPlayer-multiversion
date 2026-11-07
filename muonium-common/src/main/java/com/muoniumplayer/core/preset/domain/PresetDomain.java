package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.List;
import java.util.Map;

/** Configuration seam. All live-state operations run on the client main/render thread. */
public interface PresetDomain {
    String id();
    String displayName();
    int fragmentVersion();
    int shareCode();
    /** Published MPP2 key numbers: append only, never remove or reorder. */
    List<String> shareKeys();
    KeyPolicy policy();
    JsonObject snapshot();
    JsonObject defaults();
    /** Apply a complete state through the existing settings owner; failures must throw or be reported. */
    void apply(JsonObject state, CompatReport report);
    default JsonElement compactValue(String key, JsonElement value) { return value; }
    /** Pure validation. Returning null skips the incoming value and preserves the current value. */
    default JsonElement sanitize(String key, JsonElement value, CompatReport report) { return value; }
    default Map<String, JsonElement> migrate(Map<String, JsonElement> flat, int fromVersion) { return flat; }
    /** Trusted local state for rollback/Profiles, including values excluded from sharing. Never share this. */
    default JsonObject captureState() { return snapshot(); }
    /** Restore trusted local state, without remote-input sanitization or lossy resource normalization. */
    default void restoreState(JsonObject state, CompatReport report) { apply(state, report); }
    /** Undo a failed transaction, including any fresh resource files created during apply/restore. */
    default void rollbackState(JsonObject state, CompatReport report) { restoreState(state, report); }
    /** Clear any transaction bookkeeping after all domains have succeeded. Must not throw or perform I/O. */
    default void completeTransaction() {}
}
