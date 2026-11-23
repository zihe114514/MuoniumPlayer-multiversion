package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Project Folia's AGPL-3.0 poster-block zone flow, adapted to actual pixel font sizes.
 * Pure layout: emphasis zones float beside support runs in timeline order. Candidates
 * stay detached from the boxes until the first fitting retry (or emergency fit) is chosen.
 */
final class SonnetPosterLayout {
    private SonnetPosterLayout() { }

    static void layout(List<SonnetTypography.Box> boxes, float stageWidth, float stageHeight,
                       float baseFontSize, int seed) {
        if (boxes.isEmpty()) return;
        // Double intermediates preserve the JavaScript retry thresholds and justification decisions.
        double gap = Math.min(40.0, Math.max(16.0, baseFontSize * 0.35));
        double canvasX = -stageWidth * 0.42;
        double canvasY = -stageHeight * 0.40;
        double canvasWidth = stageWidth * 0.84;
        double canvasHeight = stageHeight * 0.80;
        boolean verticalFlow = seed % 2 != 0;
        double uCapacity = verticalFlow ? canvasHeight : canvasWidth;
        double vCapacity = verticalFlow ? canvasWidth : canvasHeight;
        List<Item> items = partition(boxes);

        Attempt attempt = new Attempt(items, verticalFlow, uCapacity, vCapacity, 1.0, gap, seed);
        for (double scale : new double[] {0.92, 0.84, 0.76, 0.68, 0.6, 0.52}) {
            if (attempt.vTotal <= vCapacity + 0.5) break;
            attempt = new Attempt(items, verticalFlow, uCapacity, vCapacity, scale, gap, seed);
        }
        // The tolerance above only selects a retry; final geometry still fits the exact stack capacity.
        if (attempt.vTotal > vCapacity) {
            double fit = vCapacity / attempt.vTotal;
            for (Placement placement : attempt.placements) {
                placement.u *= fit;
                placement.v *= fit;
                placement.uSize *= fit;
                placement.vSize *= fit;
                placement.fontSize *= fit;
            }
            attempt.vTotal = vCapacity;
        }
        double vShift = Math.max(0.0, (vCapacity - attempt.vTotal) / 2.0);
        for (Placement placement : attempt.placements) {
            double v = placement.v + vShift;
            double x = verticalFlow ? canvasX + canvasWidth - v - placement.vSize : canvasX + placement.u;
            double y = verticalFlow ? canvasY + placement.u : canvasY + v;
            double width = verticalFlow ? placement.vSize : placement.uSize;
            double height = verticalFlow ? placement.uSize : placement.vSize;
            double centerX = x + width / 2.0;
            double centerY = y + height / 2.0;
            SonnetTypography.Box box = placement.box;
            box.fontSize = (float) placement.fontSize;
            box.width = (float) width;
            box.height = (float) height;
            box.x = (float) centerX;
            box.y = (float) centerY;
            box.rotationRad = 0f;
            box.vertical = placement.vertical;
            box.flowVertical = verticalFlow;
            if (verticalFlow) {
                box.enterX = (float) Math.min(18.0, baseFontSize * 0.25);
                box.enterY = (float) ((centerY < 0.0 ? -1.0 : 1.0) * Math.min(28.0, baseFontSize * 0.45));
            } else {
                box.enterX = (float) ((centerX < 0.0 ? -1.0 : 1.0) * Math.min(28.0, baseFontSize * 0.45));
                box.enterY = (float) Math.min(18.0, baseFontSize * 0.25);
            }
        }
    }

    private static List<Item> partition(List<SonnetTypography.Box> boxes) {
        List<Item> items = new ArrayList<>();
        List<SonnetTypography.Box> group = new ArrayList<>();
        for (SonnetTypography.Box box : boxes) {
            if (box.hero || box.semiHero) {
                if (!group.isEmpty()) items.add(new Item(null, group));
                group = new ArrayList<>();
                items.add(new Item(box, null));
            } else {
                group.add(box);
            }
        }
        if (!group.isEmpty()) items.add(new Item(null, group));
        return items;
    }

    private record Item(SonnetTypography.Box zone, List<SonnetTypography.Box> group) { }
    private record ZoneFloat(double extent, double bottom) { }
    private record Measure(boolean vertical, double fontSize, double uSize, double vSize) { }

    private static final class Placement {
        final SonnetTypography.Box box;
        final boolean vertical;
        double u, v, uSize, vSize, fontSize;

        Placement(SonnetTypography.Box box, Measure measure, double u, double v,
                  double shrink, double globalScale) {
            this.box = box;
            this.vertical = measure.vertical;
            this.u = u;
            this.v = v;
            this.uSize = measure.uSize * shrink;
            this.vSize = measure.vSize * shrink;
            this.fontSize = measure.fontSize * globalScale * shrink;
        }
    }

    private static final class Chip {
        final SonnetTypography.Box box;
        final Measure measure;
        double shrink = 1.0;

        Chip(SonnetTypography.Box box, Measure measure) {
            this.box = box;
            this.measure = measure;
        }
    }

    private static final class Attempt {
        final List<Placement> placements = new ArrayList<>();
        final List<ZoneFloat> floats = new ArrayList<>();
        final boolean verticalFlow;
        final double uCapacity, vCapacity, globalScale, chipGap, lineGap;
        double vCursor, vTotal;

        Attempt(List<Item> items, boolean verticalFlow, double uCapacity, double vCapacity,
                double globalScale, double gap, int seed) {
            this.verticalFlow = verticalFlow;
            this.uCapacity = uCapacity;
            this.vCapacity = vCapacity;
            this.globalScale = globalScale;
            chipGap = gap;
            lineGap = gap * 1.15;
            boolean ownBandOnEndSide = ((seed >> 1) & 1) == 1;
            for (int index = 0; index < items.size(); index++) {
                pruneFloats();
                Item item = items.get(index);
                if (item.group != null) {
                    placeGroup(item.group);
                    continue;
                }
                // A new zone starts beyond every previous zone's remaining float span.
                for (ZoneFloat entry : floats) vCursor = Math.max(vCursor, entry.bottom);
                vCursor = Math.max(vCursor, 0.0);
                floats.clear();
                Measure measure = measure(item.zone);
                boolean followedByGroup = index + 1 < items.size() && items.get(index + 1).group != null;
                double shrink = Math.min(1.0, Math.min(
                        uCapacity * (followedByGroup ? 0.62 : 0.9) / measure.uSize,
                        vCapacity * 0.66 / measure.vSize));
                double uSize = measure.uSize * shrink;
                double vSize = measure.vSize * shrink;
                double u = items.size() == 1 ? (uCapacity - uSize) / 2.0
                        : followedByGroup ? 0.0 : ownBandOnEndSide ? uCapacity - uSize : 0.0;
                placements.add(new Placement(item.zone, measure, u, vCursor, shrink, globalScale));
                if (followedByGroup) {
                    floats.add(new ZoneFloat(uSize + chipGap, vCursor + vSize + lineGap));
                } else {
                    vCursor += vSize + lineGap;
                    ownBandOnEndSide = !ownBandOnEndSide;
                }
            }
            for (Placement placement : placements) vTotal = Math.max(vTotal, placement.v + placement.vSize);
        }

        private Measure measure(SonnetTypography.Box box) {
            // The Java contract uses zero instead of absent optional TypeScript measurements.
            boolean vertical = verticalFlow && box.verticalWidth > 0f && box.verticalHeight > 0f
                    && box.verticalFontSize > 0f;
            double font = vertical ? box.verticalFontSize : box.fontSize;
            double width = (vertical ? box.verticalWidth : box.width) * globalScale;
            double height = (vertical ? box.verticalHeight : box.height) * globalScale;
            return new Measure(vertical, font, verticalFlow ? height : width, verticalFlow ? width : height);
        }

        private void pruneFloats() {
            for (int index = floats.size() - 1; index >= 0; index--) {
                if (floats.get(index).bottom <= vCursor) floats.remove(index);
            }
        }

        private void placeGroup(List<SonnetTypography.Box> group) {
            double reservedU = 0.0;
            for (ZoneFloat entry : floats) reservedU += entry.extent;
            // Like upstream, capacity/uStart are fixed for this entire support run, even after
            // flushLine prunes the float. Only the following item reclaims its vacated space.
            double capacity = Math.max(chipGap * 2.0, uCapacity - reservedU);
            double uStart = reservedU;
            List<Chip> line = new ArrayList<>();
            double used = 0.0;
            for (SonnetTypography.Box box : group) {
                Chip chip = new Chip(box, measure(box));
                double needed = used + (line.isEmpty() ? 0.0 : chipGap) + chip.measure.uSize;
                if (needed > capacity && !line.isEmpty()) {
                    flushLine(line, used, uStart, capacity);
                    used = 0.0;
                }
                if (chip.measure.uSize > capacity) {
                    // Preserve the source's 50% minimum for a single oversized support chip.
                    chip.shrink = Math.max(0.5, capacity / chip.measure.uSize);
                    line.add(chip);
                    flushLine(line, 0.0, uStart, capacity);
                    used = 0.0;
                    continue;
                }
                used += (line.isEmpty() ? 0.0 : chipGap) + chip.measure.uSize;
                line.add(chip);
            }
            flushLine(line, used, uStart, capacity);
        }

        private void flushLine(List<Chip> line, double used, double uStart, double capacity) {
            if (line.isEmpty()) return;
            double lineV = 0.0;
            for (Chip chip : line) lineV = Math.max(lineV, chip.measure.vSize * chip.shrink);
            double leftover = capacity - used;
            double spread = line.size() > 1 && leftover > 0.0
                    ? Math.min(leftover / (line.size() - 1), chipGap * 2.5) : 0.0;
            double u = uStart;
            for (Chip chip : line) {
                placements.add(new Placement(chip.box, chip.measure, u, vCursor, chip.shrink, globalScale));
                u += chip.measure.uSize * chip.shrink + chipGap + spread;
            }
            vCursor += lineV + lineGap;
            pruneFloats();
            line.clear();
        }
    }
}
