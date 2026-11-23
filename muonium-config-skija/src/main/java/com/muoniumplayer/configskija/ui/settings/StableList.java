package com.muoniumplayer.configskija.ui.settings;

import java.util.*;

/** Stable slot identities keep duplicates separate while editing and reordering. */
public final class StableList<T> {
    public record Entry<T>(long id, T value) {}
    private long nextId; private final List<Entry<T>> entries = new ArrayList<>();
    public StableList(List<T> initial) { for (T value : initial) add(value); }
    public List<Entry<T>> entries() { return List.copyOf(entries); }
    public List<T> values() { return entries.stream().map(Entry::value).toList(); }
    public long add(T value) { long id = nextId++; entries.add(new Entry<>(id, value)); return id; }
    public boolean update(long id, T value) { for (int i = 0; i < entries.size(); i++) if (entries.get(i).id() == id) { entries.set(i, new Entry<>(id, value)); return true; } return false; }
    public void remove(long id) { entries.removeIf(e -> e.id() == id); }
    public void move(int from, int to, int lockedLeading) { if (from < lockedLeading || to < lockedLeading || from < 0 || to < 0 || from >= entries.size() || to >= entries.size()) throw new IllegalArgumentException("locked/out of bounds list move"); entries.add(to, entries.remove(from)); }
    public void replace(List<T> values) { Map<T, ArrayDeque<Entry<T>>> slots = new HashMap<>(); for (Entry<T> entry : entries) slots.computeIfAbsent(entry.value(), ignored -> new ArrayDeque<>()).addLast(entry); entries.clear(); for (T value : values) { ArrayDeque<Entry<T>> same = slots.get(value); entries.add(same == null || same.isEmpty() ? new Entry<>(nextId++, value) : same.removeFirst()); } }
}
