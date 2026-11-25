package com.muoniumplayer.configskija.model;

import java.util.HashMap;
import java.util.Map;

/** Stores UI state only; all music settings remain in the host's existing configuration. */
public final class UiPreferences {
    private final Map<String,String> values;
    private final Runnable changed;
    public UiPreferences(Map<String,String> values, Runnable changed) { this.values=new HashMap<>(values); this.changed=changed; }
    public Map<String,String> snapshot() { return Map.copyOf(values); }
    public String get(String key, String fallback) { return values.getOrDefault(key,fallback); }
    public boolean flag(String key, boolean fallback) { return Boolean.parseBoolean(get(key,Boolean.toString(fallback))); }
    public double number(String key, double fallback) { try { double n=Double.parseDouble(get(key,"")); return Double.isFinite(n)?n:fallback; } catch(NumberFormatException e) { return fallback; } }
    public void set(String key, Object value) { if(!String.valueOf(value).equals(values.put(key,String.valueOf(value)))) changed.run(); }
    public void replace(Map<String,String> snapshot) { values.clear();values.putAll(snapshot);changed.run(); }
    public boolean favorite(String id) { return flag("favorite."+id,false); }
    public void favorite(String id, boolean selected) { set("favorite."+id,selected); }
    public boolean locked(String id) { return flag("hud.locked."+id,false); }
    public void locked(String id, boolean locked) { set("hud.locked."+id,locked); }
}
