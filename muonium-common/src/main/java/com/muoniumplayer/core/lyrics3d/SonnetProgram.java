package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.lyrics3d.SonnetTypography.Glyph;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Kind;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Segment;
import com.muoniumplayer.core.screens.ncm.LyricGraphemeTimeline;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// Adapted from Project Folia's AGPL-3.0 Sonnet program and semantic compiler.
/** Compiled, renderer-independent Sonnet timeline. All times are absolute milliseconds. */
final class SonnetProgram {
    private static final double LAST_LINE_DURATION_MS = 4000.0;
    private static final double PARAGRAPH_DURATION_MS = 18000.0;
    private static final double SHOT_DURATION_MS = 6000.0;
    private static final int MAX_FALLBACK_HAN_GRAPHEMES = 4;
    private static final Kind[] KINDS = {
            Kind.EDITORIAL_COLUMN, Kind.TYPE_IMPACT, Kind.FRAGMENT_COLLAGE,
            Kind.TRACKING_RIBBON, Kind.MASK_REVEAL, Kind.POSTER_BLOCKS, Kind.QUIET_TABLEAU
    };

    final String seed;
    final List<Shot> shots;
    final List<Paragraph> paragraphs;

    SonnetProgram(String seed, List<Shot> shots, List<Paragraph> paragraphs) {
        this.seed = seed;
        this.shots = List.copyOf(shots);
        this.paragraphs = List.copyOf(paragraphs);
    }

    /** Explicit paragraph timing: content ending does not destroy a held scene in an interlude. */
    static final class Paragraph {
        final int index, firstShot, shotCount, transitionKind, transitionSeed;
        final double startMs, renderEndMs, exitStartMs, exitEndMs;

        Paragraph(int index, int firstShot, int shotCount, int kind, String seed,
                  double startMs, double renderEndMs, double nextStartMs) {
            this.index = index; this.firstShot = firstShot; this.shotCount = shotCount;
            this.startMs = startMs; this.renderEndMs = renderEndMs; transitionKind = kind;
            transitionSeed = SonnetTypography.hash(seed + ":sonnet-p" + index + ":transition-frame");
            double gap = nextStartMs - renderEndMs;
            double duration = kind < 0 ? 0 : Math.min(300, Math.max(160, gap > 0 ? gap * 0.5 : 200));
            exitEndMs = kind < 0 ? renderEndMs : nextStartMs;
            exitStartMs = kind < 0 ? renderEndMs : Math.max(startMs, nextStartMs - duration);
        }
    }

    static final class Shot {
        final String id;
        final Kind kind;
        final double startMs;
        /** Authored content end, clipped by the next line. */
        final double endMs;
        /** Folia pass/exit render hint, independent of authored syllable timing. */
        final double renderEndMs;
        final int paragraphIndex;
        final List<List<Segment>> lines;
        final int[] sourceLineIndices;
        final boolean chorus;
        /** 0: fast blur, 1: monochrome glitch, 2: fade. */
        final int transitionKind;

        Shot(String id, Kind kind, double startMs, double endMs,
             List<List<Segment>> lines, int[] sourceLineIndices,
             boolean chorus, int transitionKind, double renderEndMs, int paragraphIndex) {
            this.id = id;
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
            this.renderEndMs = renderEndMs;
            this.paragraphIndex = paragraphIndex;
            List<List<Segment>> snapshot = new ArrayList<>(lines.size());
            for (List<Segment> line : lines) snapshot.add(List.copyOf(line));
            this.lines = List.copyOf(snapshot);
            this.sourceLineIndices = sourceLineIndices.clone();
            this.chorus = chorus;
            this.transitionKind = transitionKind;
        }
    }

    private static final class CompiledLine {
        final int sourceIndex;
        final LyricLine source;
        final double endMs, renderEndMs;
        final List<Segment> segments;

        CompiledLine(int sourceIndex, LyricLine source, double endMs, double renderEndMs, List<Segment> segments) {
            this.sourceIndex = sourceIndex;
            this.source = source;
            this.endMs = endMs;
            this.renderEndMs = renderEndMs;
            this.segments = segments;
        }
    }

    /** Input order is the lyric timeline order; source indices always address that input. */
    static SonnetProgram compile(List<LyricLine> lines, String seed) {
        Objects.requireNonNull(lines, "lines");
        String resolvedSeed = seed == null ? "sonnet" : seed;
        List<CompiledLine> compiled = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            LyricLine line = Objects.requireNonNull(lines.get(index), "line");
            double nextStart = index + 1 < lines.size()
                    ? Objects.requireNonNull(lines.get(index + 1), "next line").timestamp
                    : Double.POSITIVE_INFINITY;
            double fallbackEnd = Double.isFinite(nextStart)
                    ? Math.max(line.timestamp, nextStart) : line.timestamp + LAST_LINE_DURATION_MS;
            double end = line.duration > 0 ? line.timestamp + (double) line.duration : fallbackEnd;
            // Authored line duration can end before its final syllable. Preserve that visual tail,
            // but a following line always takes ownership at its own start.
            for (LyricLine.Word word : line.words) {
                if (word != null) end = Math.max(end, word.timestamp + (double) Math.max(0L, word.duration));
            }
            for (LyricGraphemeTimeline.Glyph glyph : LyricGraphemeTimeline.of(line)) {
                end = Math.max(end, glyph.timestamp + (double) glyph.duration);
            }
            double renderEnd = visualEnd(line, Math.max(line.timestamp,
                    line.duration > 0 ? line.timestamp + (double) line.duration : fallbackEnd));
            renderEnd = Math.max(line.timestamp, Math.min(Math.max(renderEnd, end), nextStart));
            end = Math.max(line.timestamp, Math.min(end, nextStart));
            compiled.add(new CompiledLine(index, line, end, renderEnd, segments(line, fallbackEnd)));
        }

        double threshold = paragraphGapThreshold(compiled);
        List<List<CompiledLine>> paragraphs = new ArrayList<>();
        int paragraphStart = 0;
        for (int index = 1; index < compiled.size(); index++) {
            CompiledLine previous = compiled.get(index - 1);
            CompiledLine next = compiled.get(index);
            if (metadataChanged(previous.source, next.source)
                    || next.source.timestamp - previous.renderEndMs >= threshold) {
                splitParagraph(compiled.subList(paragraphStart, index), paragraphs);
                paragraphStart = index;
            }
        }
        if (!compiled.isEmpty()) splitParagraph(compiled.subList(paragraphStart, compiled.size()), paragraphs);

        List<Shot> shots = new ArrayList<>();
        List<Paragraph> timeline = new ArrayList<>();
        Kind previousKind = null;
        int previousTransition = -1;
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.size(); paragraphIndex++) {
            List<CompiledLine> paragraph = paragraphs.get(paragraphIndex);
            boolean chorus = paragraph.stream().anyMatch(item -> item.source.isChorus);
            boolean breath = !chorus && paragraphIndex + 1 < paragraphs.size()
                    && paragraph.stream().noneMatch(item -> item.source.isBreakLine)
                    && (duration(paragraph) <= 3500.0 || wordCount(paragraph) <= 3);
            // Paragraph exits and intra-paragraph shot boundaries are two independent schedules.
            int transition = paragraphIndex + 1 < paragraphs.size()
                    ? chooseIndex(3, resolvedSeed + ":" + paragraphIndex + ":transition", previousTransition) : -1;
            if (transition >= 0) previousTransition = transition;
            List<List<CompiledLine>> groups = groupShotLines(paragraph);
            timeline.add(new Paragraph(paragraphIndex, shots.size(), groups.size(), transition, resolvedSeed,
                    paragraph.get(0).source.timestamp, paragraph.get(paragraph.size() - 1).renderEndMs,
                    paragraphIndex + 1 < paragraphs.size() ? paragraphs.get(paragraphIndex + 1).get(0).source.timestamp : 0));
            for (int shotIndex = 0; shotIndex < groups.size(); shotIndex++) {
                List<CompiledLine> group = groups.get(shotIndex);
                StringBuilder signature = new StringBuilder();
                List<List<Segment>> shotLines = new ArrayList<>(group.size());
                int[] sourceIndices = new int[group.size()];
                for (int index = 0; index < group.size(); index++) {
                    CompiledLine line = group.get(index);
                    if (index > 0) signature.append('|');
                    signature.append(line.source.lyric);
                    shotLines.add(line.segments);
                    sourceIndices[index] = line.sourceIndex;
                }
                String shotSeed = resolvedSeed + ":" + paragraphIndex + ":" + shotIndex;
                Kind kind = KINDS[chooseIndex(KINDS.length, shotSeed + ":" + signature,
                        previousKind == null ? -1 : previousKind.ordinal())];
                if (breath && shotIndex == 0 && wordCount(group) <= 2) kind = Kind.QUIET_TABLEAU;
                if (chorus && kind == Kind.QUIET_TABLEAU) kind = Kind.TYPE_IMPACT;
                // Paragraph preferences must not reintroduce a repeat after the seeded choice.
                while (kind == previousKind || (chorus && kind == Kind.QUIET_TABLEAU)) {
                    kind = KINDS[(kind.ordinal() + 1) % KINDS.length];
                }
                previousKind = kind;
                shots.add(new Shot("p" + paragraphIndex + "-s" + shotIndex, kind,
                        group.get(0).source.timestamp, group.get(group.size() - 1).endMs,
                        shotLines, sourceIndices,
                        chorus, transition, group.get(group.size() - 1).renderEndMs, paragraphIndex));
            }
        }
        return new SonnetProgram(resolvedSeed, shots, timeline);
    }

    /** Upstream renderHints.ts: a visual pass/exit tail, without changing sung glyph times. */
    static double visualEnd(LyricLine line, double declaredEndMs) {
        double raw = Math.max(0, declaredEndMs - line.timestamp);
        if (raw < 100) return Math.max(declaredEndMs, line.timestamp + 67);
        boolean fast = raw < 180;
        double enter = fast ? Math.min(60, Math.max(45, raw * 0.45))
                : Math.min(420, Math.max(220, Math.max(raw, 120) * 0.34));
        double exit = fast ? Math.min(40, Math.max(30, raw * 0.22))
                : Math.min(320, Math.max(180, Math.max(raw, 120) * 0.18));
        double lastWordEnd = declaredEndMs;
        for (int i = line.words.size() - 1; i >= 0; i--) {
            LyricLine.Word word = line.words.get(i);
            if (word != null) { lastWordEnd = word.timestamp + (double) Math.max(0L, word.duration); break; }
        }
        double pass = Math.max(line.timestamp, lastWordEnd) + (fast ? 30 : 60);
        double exitStart = Math.max(pass, declaredEndMs - exit);
        if (fast) exitStart = Math.max(exitStart, line.timestamp + enter + 10);
        return Math.max(declaredEndMs, exitStart + exit);
    }

    double finalRenderEndMs() { return paragraphs.isEmpty() ? 0 : paragraphs.get(paragraphs.size() - 1).renderEndMs; }

    /** Seek-stable scene scheduler, with the same shot-over-paragraph priority as Folia. */
    void transitionAt(int shotIndex, double timeMs, SonnetTextMotion.Transition out) {
        out.reset();
        Shot shot = shots.get(shotIndex);
        Paragraph paragraph = paragraphs.get(shot.paragraphIndex);
        int local = shotIndex - paragraph.firstShot;
        if (local > 0) {
            Shot previous = shots.get(shotIndex - 1);
            double duration = Math.min(240, Math.max(140, (shot.startMs - previous.startMs) * 0.18));
            if (timeMs >= shot.startMs && timeMs <= shot.startMs + duration) {
                SonnetTextMotion.transition(boundaryKind(paragraph.transitionSeed, local - 1), false,
                        (timeMs - shot.startMs) / duration, Integer.toUnsignedLong(paragraph.transitionSeed) + local * 97.0, out);
                return;
            }
        }
        if (local + 1 < paragraph.shotCount) {
            Shot next = shots.get(shotIndex + 1);
            double duration = Math.min(240, Math.max(140, (next.startMs - shot.startMs) * 0.18));
            if (timeMs >= next.startMs - duration) {
                SonnetTextMotion.transition(boundaryKind(paragraph.transitionSeed, local), true,
                        (timeMs - next.startMs + duration) / duration,
                        Integer.toUnsignedLong(paragraph.transitionSeed) + (local + 1) * 97.0, out);
                return;
            }
        }
        if (paragraph.index > 0) {
            Paragraph previous = paragraphs.get(paragraph.index - 1);
            double duration = Math.min(300, Math.max(160, previous.exitEndMs - previous.exitStartMs));
            if (previous.transitionKind >= 0 && timeMs >= paragraph.startMs && timeMs <= paragraph.startMs + duration) {
                SonnetTextMotion.transition(previous.transitionKind, false,
                        (timeMs - paragraph.startMs) / duration, Integer.toUnsignedLong(paragraph.transitionSeed), out);
                return;
            }
        }
        if (paragraph.transitionKind >= 0 && timeMs >= paragraph.exitStartMs) {
            SonnetTextMotion.transition(paragraph.transitionKind, true,
                    (timeMs - paragraph.exitStartMs) / Math.max(1, paragraph.exitEndMs - paragraph.exitStartMs),
                    Integer.toUnsignedLong(paragraph.transitionSeed), out);
        }
    }

    static int boundaryKind(int seed, int boundaryIndex) {
        return (int) (Integer.toUnsignedLong(seed ^ ((boundaryIndex + 1) * 0x9e3779b1)) % 3);
    }

    /** Last started shot, held through gaps and clamped at both ends; -1 for an empty program. */
    int shotAt(double absoluteMs) {
        if (shots.isEmpty()) return -1;
        int active = 0;
        int low = 0;
        int high = shots.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (absoluteMs >= shots.get(middle).startMs) {
                active = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return active;
    }

    /** Lossless UTF-16 source ranges, each carrying the existing parser-derived grapheme timing. */
    static List<Segment> segments(LyricLine line, double fallbackEndMs) {
        Objects.requireNonNull(line, "line");
        String text = line.lyric;
        if (text == null || text.isEmpty()) return List.of();
        List<LyricGraphemeTimeline.Glyph> timeline = LyricGraphemeTimeline.of(line);
        boolean hasTiming = line.duration > 0;
        for (LyricGraphemeTimeline.Glyph glyph : timeline) {
            if (glyph.duration > 0 || glyph.timestamp != line.timestamp) hasTiming = true;
        }
        double endMs = Math.max(line.timestamp, Double.isFinite(fallbackEndMs)
                ? fallbackEndMs : line.timestamp + LAST_LINE_DURATION_MS);
        List<Glyph> glyphs = new ArrayList<>(timeline.size());
        for (int index = 0; index < timeline.size(); index++) {
            LyricGraphemeTimeline.Glyph glyph = timeline.get(index);
            double start = hasTiming ? glyph.timestamp
                    : line.timestamp + (endMs - line.timestamp) * index / timeline.size();
            double end = hasTiming ? glyph.timestamp + (double) glyph.duration
                    : line.timestamp + (endMs - line.timestamp) * (index + 1) / timeline.size();
            glyphs.add(new Glyph(glyph.text, start, end));
        }

        int[] offsets = new int[glyphs.size() + 1];
        for (int index = 0; index < glyphs.size(); index++) {
            offsets[index + 1] = offsets[index] + glyphs.get(index).text.length();
        }
        boolean[] boundaries = semanticBoundaries(line, timeline, offsets);
        List<Segment> result = new ArrayList<>();
        int glyphStart = 0;
        for (int glyphEnd = 1; glyphEnd <= glyphs.size(); glyphEnd++) {
            if (!boundaries[glyphEnd]) continue;
            int start = offsets[glyphStart];
            int end = offsets[glyphEnd];
            String part = text.substring(start, end);
            List<Glyph> partGlyphs = new ArrayList<>(glyphs.subList(glyphStart, glyphEnd));
            double partStart = partGlyphs.isEmpty() ? line.timestamp : partGlyphs.get(0).startMs;
            double partEnd = partGlyphs.isEmpty() ? endMs : partGlyphs.get(partGlyphs.size() - 1).endMs;
            boolean wordLike = part.codePoints().anyMatch(SonnetProgram::isWordCodePoint);
            if (!result.isEmpty() && !wordLike && !part.codePoints().allMatch(SonnetProgram::isSpace)) {
                Segment previous = result.remove(result.size() - 1);
                List<Glyph> joined = new ArrayList<>(previous.glyphs);
                joined.addAll(partGlyphs);
                result.add(new Segment(previous.text + part, previous.startOffset, end,
                        previous.startMs, Math.max(previous.endMs, partEnd), previous.wordLike, List.copyOf(joined)));
            } else {
                result.add(new Segment(part, start, end, partStart, partEnd, wordLike, List.copyOf(partGlyphs)));
            }
            glyphStart = glyphEnd;
        }
        return List.copyOf(result);
    }

    /**
     * Folia prefers saved/jieba boundaries, then Intl.Segmenter with its default locale.
     * The checked JDK 17/21/25 ROOT and Chinese word iterators leave long Han runs intact.
     * Unresolved runs therefore use visual chunks of at most four Han graphemes; these
     * are composition bounds, not Chinese dictionary segmentation. Exactly aligned parser
     * words with multiple word-like graphemes retain their own boundaries, even if longer.
     * Single-grapheme syllables and a lone span covering all visible words provide timing
     * only, so neither makes an entire Chinese line one semantic word.
     */
    private static boolean[] semanticBoundaries(LyricLine line,
                                                List<LyricGraphemeTimeline.Glyph> timeline,
                                                int[] offsets) {
        String text = line.lyric;
        boolean[] boundaries = new boolean[offsets.length];
        BreakIterator words = BreakIterator.getWordInstance(text.codePoints().anyMatch(SonnetProgram::isHan)
                ? Locale.CHINESE : Locale.ROOT);
        words.setText(text);
        for (int end = words.first(); end != BreakIterator.DONE; end = words.next()) {
            // Never cut inside an existing timeline glyph, including supplementary/ZWJ strings.
            int glyphBoundary = Arrays.binarySearch(offsets, end);
            if (glyphBoundary >= 0) boundaries[glyphBoundary] = true;
        }
        boundaries[0] = true;
        boundaries[timeline.size()] = true;
        int firstWordGlyph = timeline.size();
        int lastWordGlyph = -1;
        for (int index = 0; index < timeline.size(); index++) {
            if (timeline.get(index).text.codePoints().anyMatch(SonnetProgram::isWordCodePoint)) {
                firstWordGlyph = Math.min(firstWordGlyph, index);
                lastWordGlyph = index;
            }
        }
        boolean[] authored = new boolean[timeline.size()];
        for (int start = 0; start < timeline.size();) {
            int owner = timeline.get(start).wordIndex;
            int end = start + 1;
            while (end < timeline.size() && timeline.get(end).wordIndex == owner) end++;
            LyricLine.Word word = owner >= 0 && owner < line.words.size() ? line.words.get(owner) : null;
            // The timing mapper can recover an unmatched syllable by position. That is useful
            // timing, but only an exact text match can establish a semantic word boundary.
            if (word != null && text.substring(offsets[start], offsets[end]).equals(word.word)) {
                int coreStart = start;
                int coreEnd = end;
                while (coreStart < coreEnd && !timeline.get(coreStart).text.codePoints()
                        .anyMatch(SonnetProgram::isWordCodePoint)) coreStart++;
                while (coreEnd > coreStart && !timeline.get(coreEnd - 1).text.codePoints()
                        .anyMatch(SonnetProgram::isWordCodePoint)) coreEnd--;
                boolean outsideWord = firstWordGlyph < coreStart || lastWordGlyph >= coreEnd;
                if (coreEnd - coreStart > 1 && outsideWord
                        && text.substring(offsets[coreStart], offsets[coreEnd]).codePoints()
                        .allMatch(SonnetProgram::isWordCodePoint)) {
                    boundaries[coreStart] = true;
                    boundaries[coreEnd] = true;
                    Arrays.fill(boundaries, coreStart + 1, coreEnd, false);
                    Arrays.fill(authored, coreStart, coreEnd, true);
                }
            }
            start = end;
        }
        int unresolvedHan = 0;
        for (int index = 0; index < timeline.size(); index++) {
            if (boundaries[index]) unresolvedHan = 0;
            if (authored[index] || !timeline.get(index).text.codePoints().anyMatch(SonnetProgram::isHan)) {
                unresolvedHan = 0;
            } else {
                if (unresolvedHan == MAX_FALLBACK_HAN_GRAPHEMES) {
                    boundaries[index] = true;
                    unresolvedHan = 0;
                }
                unresolvedHan++;
            }
        }
        return boundaries;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xfeff;
    }

    private static boolean isWordCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private static boolean metadataChanged(LyricLine previous, LyricLine next) {
        return previous.isChorus != next.isChorus || previous.isBreakLine != next.isBreakLine
                || (previous.chorusIndex >= 0 && next.chorusIndex >= 0 && previous.chorusIndex != next.chorusIndex);
    }

    private static double paragraphGapThreshold(List<CompiledLine> lines) {
        double[] gaps = new double[Math.max(0, lines.size() - 1)];
        int count = 0;
        for (int index = 1; index < lines.size(); index++) {
            double gap = lines.get(index).source.timestamp - lines.get(index - 1).renderEndMs;
            if (gap > 0) gaps[count++] = gap;
        }
        Arrays.sort(gaps, 0, count);
        double median = count == 0 ? 500.0 : (count % 2 == 0
                ? (gaps[count / 2 - 1] + gaps[count / 2]) * 0.5 : gaps[count / 2]);
        return Math.min(3500.0, Math.max(1250.0, median * 2.5));
    }

    private static double duration(List<CompiledLine> lines) {
        return lines.get(lines.size() - 1).renderEndMs - lines.get(0).source.timestamp;
    }

    private static int wordCount(List<CompiledLine> lines) {
        int count = 0;
        for (CompiledLine line : lines) {
            for (Segment segment : line.segments) if (segment.wordLike) count++;
        }
        return count;
    }

    private static int chooseIndex(int count, String seed, int previous) {
        int start = (int) (Integer.toUnsignedLong(SonnetTypography.hash(seed)) % count);
        return start == previous ? (start + 1) % count : start;
    }

    private static void splitParagraph(List<CompiledLine> draft, List<List<CompiledLine>> output) {
        List<CompiledLine> remaining = draft;
        while (remaining.size() > 6 || (remaining.size() > 1 && duration(remaining) > PARAGRAPH_DURATION_MS)) {
            int maxPrefix = 1;
            while (maxPrefix < Math.min(6, remaining.size() - 1)
                    && remaining.get(maxPrefix).renderEndMs - remaining.get(0).source.timestamp <= PARAGRAPH_DURATION_MS) {
                maxPrefix++;
            }
            int split = Math.min(4, maxPrefix);
            double largestGap = Double.NEGATIVE_INFINITY;
            // Prefer the largest internal gap as upstream does, restricted to bounded prefixes.
            for (int index = 2; index <= maxPrefix && index < remaining.size() - 1; index++) {
                double gap = remaining.get(index).source.timestamp - remaining.get(index - 1).renderEndMs;
                if (gap > largestGap) {
                    largestGap = gap;
                    split = index;
                }
            }
            output.add(List.copyOf(remaining.subList(0, split)));
            remaining = remaining.subList(split, remaining.size());
        }
        if (!remaining.isEmpty()) output.add(List.copyOf(remaining));
    }

    private static List<List<CompiledLine>> groupShotLines(List<CompiledLine> lines) {
        List<List<CompiledLine>> groups = new ArrayList<>();
        int start = 0;
        for (int index = 1; index < lines.size(); index++) {
            if (index - start >= 4 || lines.get(index).renderEndMs - lines.get(start).source.timestamp > SHOT_DURATION_MS) {
                groups.add(lines.subList(start, index));
                start = index;
            }
        }
        // An authored line is indivisible; a single long line keeps its complete timing.
        if (!lines.isEmpty()) groups.add(lines.subList(start, lines.size()));
        return groups;
    }
}
