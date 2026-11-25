package com.muoniumplayer.configskija.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A drag is one transaction; undo/redo stores values, never a live mutable object. */
public final class History<T> {
    private record Change<T>(String label, T before, T after) {}
    private final int limit;
    private final Supplier<T> capture;
    private final java.util.function.BiConsumer<T,T> restore;
    private final Deque<Change<T>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private T before;
    private String label;
    public History(int limit, Supplier<T> capture, Consumer<T> restore) {
        if (limit < 1) throw new IllegalArgumentException("history limit");
        this.limit = limit; this.capture = capture; this.restore = (desired,previous)->restore.accept(desired);
    }
    public History(int limit,Supplier<T> capture,java.util.function.BiConsumer<T,T> restore) {
        if(limit<1)throw new IllegalArgumentException("history limit");this.limit=limit;this.capture=capture;this.restore=restore;
    }
    public void begin(String label) {
        if (before != null) throw new IllegalStateException("nested transaction");
        before = Objects.requireNonNull(capture.get()); this.label = label;
    }
    public boolean active() { return before != null; }
    public boolean commit() {
        if (before == null) return false;
        T after = capture.get(), start = before; before = null;
        if (ValueSnapshots.equal(start, after)) return false;
        undo.addLast(new Change<>(label, start, after)); redo.clear();
        while (undo.size() > limit) undo.removeFirst();
        return true;
    }
    public void cancel() {
        if (before == null) return;
        T start = before; before = null; restore.accept(start,capture.get());
    }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public boolean undo() {
        if (active()) cancel();
        if (undo.isEmpty()) return false;
        Change<T> change = undo.removeLast(); restore.accept(change.before(),change.after()); redo.addLast(change); return true;
    }
    public boolean redo() {
        if (active()) cancel();
        if (redo.isEmpty()) return false;
        Change<T> change = redo.removeLast(); restore.accept(change.after(),change.before()); undo.addLast(change); return true;
    }
    public void clear() { before=null;undo.clear();redo.clear(); }
    public int undoSize() { return undo.size(); }
}
