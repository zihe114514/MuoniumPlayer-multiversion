package com.muoniumplayer.configskija.ui;

import java.text.BreakIterator;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** UTF-16 offsets, Unicode-safe selection, and single/multiple logical lines. No native/loader dependency. */
public final class TextEdit {
    public record Snapshot(String text, int cursor, int anchor) {}
    private String text = "";
    private int cursor, anchor, preferredColumn = -1, limit = 4096;
    private boolean multiline, preserveSingleLineBreaks;
    private long revision;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    public boolean focused;
    public String text() { return text; }
    public int cursor() { return cursor; }
    public int anchor() { return anchor; }
    public int selectionStart() { return Math.min(cursor, anchor); }
    public int selectionEnd() { return Math.max(cursor, anchor); }
    public boolean selected() { return cursor != anchor; }
    public long revision() { return revision; }
    public boolean multiline() { return multiline; }
    public boolean preserveSingleLineBreaks() { return preserveSingleLineBreaks; }
    public void preserveSingleLineBreaks(boolean value) { if (preserveSingleLineBreaks == value) return; preserveSingleLineBreaks = value; replace(text, false); }
    public int limit() { return limit; }
    public void multiline(boolean value) { if (multiline == value) return; multiline = value; replace(text, false); }
    public void limit(int value) { limit = Math.max(0, value); if (text.length() > limit) replace(text, false); }
    /** External values reset the editing history and retain the legacy caret-at-end contract. */
    public void text(String value) { String clean = sanitize(value, multiline, preserveSingleLineBreaks, limit); if (!text.equals(clean)) { text = clean; revision++; } cursor = anchor = text.length(); preferredColumn = -1; clearHistory(); }
    /** Apply a validator/transform without throwing away selection or the local undo chain. */
    public void replace(String value, boolean remember) { String clean = sanitize(value, multiline, preserveSingleLineBreaks, limit); if (clean.equals(text)) return; if (remember) checkpoint(); text = clean; cursor = boundary(Math.min(cursor, text.length())); anchor = boundary(Math.min(anchor, text.length())); preferredColumn = -1; revision++; }
    public Snapshot snapshot() { return new Snapshot(text, cursor, anchor); }
    public void restore(Snapshot state) { text = sanitize(state.text(), multiline, preserveSingleLineBreaks, limit); cursor = boundary(state.cursor()); anchor = boundary(state.anchor()); preferredColumn = -1; revision++; }
    public void selectAll() { anchor = 0; cursor = text.length(); preferredColumn = -1; }
    public void selection(int anchor, int cursor) { this.anchor = boundary(anchor); this.cursor = boundary(cursor); preferredColumn = -1; }
    public String selectedText() { return text.substring(selectionStart(), selectionEnd()); }
    public void insert(String value) {
        int start = selectionStart(), end = selectionEnd(), room = Math.max(0, limit - (text.length() - (end - start)));
        String clean = sanitize(value, multiline, preserveSingleLineBreaks, room); if (start == end && clean.isEmpty()) return;
        checkpoint(); text = text.substring(0, start) + clean + text.substring(end); cursor = anchor = start + clean.length(); preferredColumn = -1; revision++;
    }
    public void move(int direction, boolean extend, boolean word) {
        if (!extend && selected()) { cursor = direction < 0 ? selectionStart() : selectionEnd(); anchor = cursor; preferredColumn = -1; return; }
        int next = word ? direction < 0 ? previousWord(cursor) : nextWord(cursor) : direction < 0 ? previousGrapheme(cursor) : nextGrapheme(cursor);
        cursor = next; if (!extend) anchor = cursor; preferredColumn = -1;
    }
    /** Legacy edge() is the whole document; lineEdge() implements unmodified Home/End. */
    public void edge(boolean end, boolean extend) { cursor = end ? text.length() : 0; if (!extend) anchor = cursor; preferredColumn = -1; }
    public void lineEdge(boolean end, boolean extend) { cursor = end ? lineEnd(cursor) : lineStart(cursor); if (!extend) anchor = cursor; preferredColumn = -1; }
    public void paragraph(int direction, boolean extend) { int start = lineStart(cursor); cursor = direction < 0 ? cursor == start && start > 0 ? lineStart(start - 1) : start : Math.min(text.length(), lineEnd(cursor) + 1); if (!extend) anchor = cursor; preferredColumn = -1; }
    public void vertical(int direction, boolean extend) { if (preferredColumn < 0) preferredColumn = column(cursor); int row = (int)Geometry.clamp(line(cursor) + direction, 0, lineCount() - 1); cursor = position(row, preferredColumn); if (!extend) anchor = cursor; }
    public void delete(boolean backward, boolean word) { Snapshot before = snapshot(); if (!selected()) { int start = cursor; move(backward ? -1 : 1, true, word); if (start == cursor) return; } insert(""); undo.removeLast(); undo.addLast(before); }
    public void click(double x, ToDoubleFunction<String> measure, boolean extend) { int hit = hitLine(0, text.length(), x, measure); selection(extend ? anchor : hit, hit); }
    public int hitLine(int start, int end, double x, ToDoubleFunction<String> measure) {
        start = boundary(start); end = boundary(end); int best = start; double distance = Math.abs(x);
        for (int offset = start; offset < end;) { int next = Math.min(end, nextGrapheme(offset)); if (next <= offset) break; double d = Math.abs(x - measure.applyAsDouble(text.substring(start, next))); if (d < distance) { best = next; distance = d; } offset = next; }
        return best;
    }
    public int[] wordRange(int offset) {
        if (text.isEmpty()) return new int[] {0, 0}; int at = boundary(Math.min(offset, text.length() - 1));
        BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT); words.setText(text); int end = words.following(at); if (end == BreakIterator.DONE) end = text.length(); int start = words.preceding(end); if (start == BreakIterator.DONE) start = 0;
        return new int[] {boundary(start), boundary(end)};
    }
    public int[] lineRange(int offset) { int start = lineStart(offset), end = lineEnd(offset); return new int[] {start, end < text.length() ? end + 1 : end}; }
    public void selectWord(int offset) { int[] range = wordRange(offset); selection(range[0], range[1]); }
    public void selectLine(int offset) { int[] range = lineRange(offset); selection(range[0], range[1]); }
    public int lineStart(int offset) { int at = boundary(offset); return at == 0 ? 0 : text.lastIndexOf('\n', at - 1) + 1; }
    public int lineEnd(int offset) { int found = text.indexOf('\n', boundary(offset)); return found < 0 ? text.length() : found; }
    public int line(int offset) { int row = 0; for (int i = 0, end = boundary(offset); i < end; i++) if (text.charAt(i) == '\n') row++; return row; }
    public int lineCount() { return 1 + line(text.length()); }
    public int column(int offset) { int at = boundary(offset); return text.codePointCount(lineStart(at), at); }
    public int position(int row, int column) { int start = 0; for (int i = 0; i < row; i++) { int next = text.indexOf('\n', start); if (next < 0) return text.length(); start = next + 1; } int end = lineEnd(start); return text.offsetByCodePoints(start, Math.min(Math.max(0, column), text.codePointCount(start, end))); }
    public int previousGrapheme(int offset) { int at = boundary(offset); if (at == 0) return 0; BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT); iterator.setText(text); int previous = iterator.preceding(at); if (previous == BreakIterator.DONE) return 0;
        while (previous > 0 && (text.codePointAt(previous) == 0x200d || text.codePointBefore(previous) == 0x200d || isContinuation(text.codePointAt(previous)))) { previous = iterator.preceding(previous); if (previous == BreakIterator.DONE) return 0; } return previous; }
    public int nextGrapheme(int offset) { int at = boundary(offset); if (at == text.length()) return at; BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT); iterator.setText(text); int next = iterator.following(at); if (next == BreakIterator.DONE) return text.length(); while (next < text.length() && (text.codePointAt(next) == 0x200d || text.codePointBefore(next) == 0x200d || isContinuation(text.codePointAt(next)))) { next = iterator.following(next); if (next == BreakIterator.DONE) return text.length(); } return next; }
    private static boolean isContinuation(int code) { int type = Character.getType(code); return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || code >= 0xfe00 && code <= 0xfe0f || code >= 0x1f3fb && code <= 0x1f3ff; }
    private int previousWord(int offset) { int at = boundary(offset); while (at > 0 && Character.isWhitespace(text.codePointBefore(at))) at = text.offsetByCodePoints(at, -1); if (at == 0) return 0; return wordRange(text.offsetByCodePoints(at, -1))[0]; }
    private int nextWord(int offset) { int at = boundary(offset); if (at == text.length()) return at; int end = wordRange(at)[1]; while (end < text.length() && Character.isWhitespace(text.codePointAt(end))) end = text.offsetByCodePoints(end, 1); return end; }
    private int boundary(int offset) { int at = Math.max(0, Math.min(offset, text.length())); return at > 0 && at < text.length() && Character.isLowSurrogate(text.charAt(at)) && Character.isHighSurrogate(text.charAt(at - 1)) ? at - 1 : at; }
    public boolean undo() { if (undo.isEmpty()) return false; redo.addLast(snapshot()); restore(undo.removeLast()); return true; }
    public boolean redo() { if (redo.isEmpty()) return false; undo.addLast(snapshot()); restore(redo.removeLast()); return true; }
    public void clearHistory() { undo.clear(); redo.clear(); }
    private void checkpoint() { undo.addLast(snapshot()); while (undo.size() > 100) undo.removeFirst(); redo.clear(); }
    private static String sanitize(String value, boolean multiline, boolean preserveSeparators, int maximum) {
        if (value == null || maximum == 0) return ""; StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length();) { int cp = value.codePointAt(i); i += Character.charCount(cp); if (cp == '\r' && (multiline || !preserveSeparators)) { if (i < value.length() && value.charAt(i) == '\n') i++; cp = '\n'; }
            if (cp >= 0xd800 && cp <= 0xdfff || Character.isISOControl(cp) && !((multiline || preserveSeparators) && (cp == '\n' || cp == '\r' || cp == '\t'))) continue;
            if (out.length() + Character.charCount(cp) > maximum) break; out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
