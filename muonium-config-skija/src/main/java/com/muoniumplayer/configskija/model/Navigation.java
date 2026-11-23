package com.muoniumplayer.configskija.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class Navigation {
    public enum Page { SETTINGS, FAVORITES, MODS, PROFILES, THEMES, PREFERENCES, KEYS, CREDITS, HUD }
    public record Route(Page page, String category) {
        public Route(Page page) { this(page, ""); }
        public String key() { return page+":"+category; }
    }
    public record Memory(double scroll, String search) {}
    private Route current;
    private final Deque<Route> back = new ArrayDeque<>(), forward = new ArrayDeque<>();
    private final Map<Route, Memory> memories = new HashMap<>();
    public Navigation(Route first) { current = first; }
    public void temporary(Route route) { current=route; }
    public Route current() { return current; }
    public void navigate(Route next) {
        if (next.equals(current)) return;
        back.addLast(current); while (back.size() > 100) back.removeFirst();
        current = next; forward.clear();
    }
    public boolean back() {
        if (back.isEmpty()) return false;
        forward.addLast(current); current = back.removeLast(); return true;
    }
    public boolean forward() {
        if (forward.isEmpty()) return false;
        back.addLast(current); current = forward.removeLast(); return true;
    }
    public Route previous(){return back.peekLast();}
    public Route next(){return forward.peekLast();}
    public boolean canBack() { return !back.isEmpty(); }
    public boolean canForward() { return !forward.isEmpty(); }
    public void remember(double scroll, String search) { memories.put(current,new Memory(scroll,search)); }
    public Memory memory() { return memories.getOrDefault(current,new Memory(0,"")); }
}
