package com.muoniumplayer.core.preset.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Compatibility and transaction outcome; failures never mean partial success. */
public final class CompatReport {
    private final Set<String> warnings = new LinkedHashSet<String>();
    private final List<String> failures = new ArrayList<String>();
    private final Set<String> touchedDomains = new LinkedHashSet<String>();
    private int unsupportedKeys, unsupportedValues, changedKeys;
    private boolean rolledBack;
    private boolean rollbackFailed;

    public void warn(String message) {
        if (message != null && !message.trim().isEmpty()) warnings.add(message.trim());
    }
    public void unsupportedKey(String domain, String key) { unsupportedKeys++; }
    public void unsupportedValue(String domain, String key) { unsupportedValues++; }
    public void failure(String domainLabel, Throwable error) {
        String reason = error == null ? "未知错误" : error.getMessage();
        if (reason == null || reason.isEmpty()) reason = error.getClass().getSimpleName();
        failures.add(domainLabel + "套用失败：" + reason);
    }
    public void changed(String domain, int keys) {
        if (keys > 0) { touchedDomains.add(domain); changedKeys += keys; }
    }
    public void rolledBack(boolean complete) {
        rolledBack = true;
        rollbackFailed = !complete;
        changedKeys = 0;
        touchedDomains.clear();
        warn(complete ? "套用失败，已回滚全部改动" : "套用失败，部分回滚也失败；请检查配置与备份");
    }
    public List<String> warnings() { return Collections.unmodifiableList(new ArrayList<String>(warnings)); }
    public List<String> failures() { return Collections.unmodifiableList(failures); }
    public boolean hasFailures() { return !failures.isEmpty(); }
    public boolean wasRolledBack() { return rolledBack; }
    public boolean rollbackFailed() { return rollbackFailed; }
    public int unsupportedKeys() { return unsupportedKeys; }
    public int unsupportedValues() { return unsupportedValues; }
    public int changedKeys() { return changedKeys; }
    public Set<String> touchedDomains() { return Collections.unmodifiableSet(touchedDomains); }
    public List<String> lines() {
        List<String> lines = new ArrayList<String>(failures);
        int skipped = unsupportedKeys + unsupportedValues;
        if (skipped > 0) lines.add(skipped + " 项设置这个版本不支持，已忽略（多半来自更新的 MuoniumPlayer）");
        lines.addAll(warnings);
        return lines;
    }
    /** Stable JSON result for a host UI; no rendering or loader types. */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", !hasFailures());
        out.addProperty("changedKeys", changedKeys);
        out.addProperty("unsupportedKeys", unsupportedKeys);
        out.addProperty("unsupportedValues", unsupportedValues);
        out.addProperty("rolledBack", rolledBack);
        out.addProperty("rollbackFailed", rollbackFailed);
        JsonArray domainIds = new JsonArray();
        for (String id : touchedDomains) domainIds.add(id);
        out.add("touchedDomains", domainIds);
        JsonArray notes = new JsonArray();
        for (String line : lines()) notes.add(line);
        out.add("lines", notes);
        return out;
    }
}
