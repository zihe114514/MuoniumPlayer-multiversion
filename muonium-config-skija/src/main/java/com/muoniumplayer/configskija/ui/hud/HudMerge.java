/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2024 Polyfrost.
 *   <https://polyfrost.org> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.org/legal/oneconfig/additional-terms>
 */

package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.HudElement.Anchor;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Painter;

import java.util.*;
import java.util.function.Function;

/** Geometry port of HudBackgroundMerge: touch=2, overlap=2, style epsilon=.01, axis trees. */
public final class HudMerge {
    public enum Axis {
        X,
        Y
    }

    public record Contact(String child, String parent, Anchor self, Anchor target, Axis axis) {}

    public record Point(double x, double y) {}

    public record Shape(List<List<Point>> loops, double radius, int color) {
        public Shape {
            loops = loops.stream().map(List::copyOf).toList();
        }
    }

    public record Group(Set<String> ids, List<Contact> links, List<Rect> rectangles, Shape shape) {
        public Group {
            ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
            links = List.copyOf(links);
            rectangles = List.copyOf(rectangles);
        }
    }

    private record Item(HudNode hud, Rect rect) {}

    private HudMerge() {}

    public static boolean candidate(HudElement e) {
        return e instanceof HudNode n
                && n.mergeable()
                && n.flag("showBackground")
                && n.flag("mergeBackground")
                && (!n.hidden() || n.flag("keepBgWhenHidden"));
    }

    public static boolean sameStyle(HudNode a, HudNode b) {
        return a.color("bgColor") == b.color("bgColor")
                && a.flag("bgChroma") == b.flag("bgChroma")
                && (!a.flag("bgChroma")
                        || Math.abs(a.number("bgChromaSpeed") - b.number("bgChromaSpeed")) <= .01)
                && Math.abs(
                                a.number("bgRadius") * a.effectiveScale()
                                        - b.number("bgRadius") * b.effectiveScale())
                        <= .01;
    }

    public static List<Group> groups(
            List<HudElement> elements,
            Function<HudElement, Rect> bounds,
            Map<String, HudDesigner.Link> anchors,
            Set<String> exclusions) {
        List<Item> items =
                elements.stream()
                        .filter(HudMerge::candidate)
                        .filter(e -> !exclusions.contains(e.id()))
                        .map(e -> new Item((HudNode) e, bounds.apply(e)))
                        .toList();
        int n = items.size();
        int[] parent = new int[n];
        int[][] contacts = new int[n][n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                Item a = items.get(i), b = items.get(j);
                if (!sameStyle(a.hud(), b.hud())) continue;
                int pins =
                        pins(
                                a.rect(),
                                b.rect(),
                                a.hud().flag("mergeDiagonally") && b.hud().flag("mergeDiagonally"));
                if (pins != 0) {
                    parent[find(parent, j)] = find(parent, i);
                    contacts[i][j] = contacts[j][i] = pins;
                }
            }
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        List<Group> out = new ArrayList<>();
        for (List<Integer> component : components.values()) {
            if (component.size() < 2) continue;
            Set<String> ids = new LinkedHashSet<>();
            List<Rect> rects = new ArrayList<>();
            for (int i : component) {
                ids.add(items.get(i).hud().id());
                rects.add(items.get(i).rect());
            }
            List<Contact> links = new ArrayList<>();
            for (Axis axis : Axis.values())
                links.addAll(axisTree(items, component, contacts, anchors, axis));
            HudNode first = items.get(component.get(0)).hud();
            out.add(
                    new Group(
                            ids,
                            links,
                            rects,
                            new Shape(
                                    outlines(rects),
                                    first.number("bgRadius") * first.effectiveScale(),
                                    first.color("bgColor"))));
        }
        return List.copyOf(out);
    }

    public static int pins(Rect a, Rect b, boolean diagonal) {
        boolean row = overlap(a.y(), a.bottom(), b.y(), b.bottom()) >= 2,
                col = overlap(a.x(), a.right(), b.x(), b.right()) >= 2;
        boolean stacked = Math.abs(a.bottom() - b.y()) <= 2 || Math.abs(b.bottom() - a.y()) <= 2,
                side = Math.abs(a.right() - b.x()) <= 2 || Math.abs(b.right() - a.x()) <= 2;
        if (stacked && col && nests(a.x(), a.right(), b.x(), b.right())) return 2;
        if (side && row && nests(a.y(), a.bottom(), b.y(), b.bottom())) return 1;
        return diagonal && (stacked && col || side && row || stacked && side) ? 3 : 0;
    }

    private static List<Contact> axisTree(
            List<Item> items,
            List<Integer> component,
            int[][] contacts,
            Map<String, HudDesigner.Link> anchors,
            Axis axis) {
        Set<Integer> seen = new LinkedHashSet<>();
        Map<String, String> parents = new HashMap<>();
        List<Contact> out = new ArrayList<>();
        for (int i : component) if (anchors.containsKey(items.get(i).hud().id())) seen.add(i);
        while (seen.size() < component.size()) {
            int bp = -1, bc = -1;
            boolean pinned = false;
            for (int p : seen)
                for (int c : component)
                    if (!seen.contains(c) && contacts[p][c] != 0) {
                        boolean pin = (contacts[p][c] & (axis == Axis.X ? 1 : 2)) != 0;
                        if (bc != -1 && (pinned || !pin)) continue;
                        String target = items.get(c).hud().id(), node = items.get(p).hud().id();
                        Set<String> walked = new HashSet<>();
                        boolean cycle = false;
                        for (int depth = 0;
                                node != null && depth < component.size() + 16 && walked.add(node);
                                depth++) {
                            if (node.equals(target)) {
                                cycle = true;
                                break;
                            }
                            node =
                                    parents.containsKey(node)
                                            ? parents.get(node)
                                            : anchors.containsKey(node)
                                                    ? anchors.get(node).parent()
                                                    : null;
                        }
                        if (cycle) continue;
                        bp = p;
                        bc = c;
                        pinned = pin;
                    }
            if (bc < 0) {
                for (int i : component) if (seen.add(i)) break;
                continue;
            }
            seen.add(bc);
            Item child = items.get(bc), p = items.get(bp);
            Anchor[] points = contactPoints(child.rect(), p.rect());
            parents.put(child.hud().id(), p.hud().id());
            out.add(new Contact(child.hud().id(), p.hud().id(), points[0], points[1], axis));
        }
        return out;
    }

    private static Anchor[] contactPoints(Rect c, Rect p) {
        double cx = Double.NaN, px = Double.NaN, cy = Double.NaN, py = Double.NaN;
        if (Math.abs(c.y() - p.bottom()) <= 2) {
            cy = 0;
            py = 1;
        } else if (Math.abs(c.bottom() - p.y()) <= 2) {
            cy = 1;
            py = 0;
        }
        if (Math.abs(c.x() - p.right()) <= 2) {
            cx = 0;
            px = 1;
        } else if (Math.abs(c.right() - p.x()) <= 2) {
            cx = 1;
            px = 0;
        }
        if (Double.isNaN(cx)) cx = px = aligned(c.x(), c.right(), p.x(), p.right());
        if (Double.isNaN(cy)) cy = py = aligned(c.y(), c.bottom(), p.y(), p.bottom());
        return new Anchor[] {
            Anchor.at((int) (cx * 2), (int) (cy * 2)), Anchor.at((int) (px * 2), (int) (py * 2))
        };
    }

    public static List<List<Point>> outlines(List<Rect> rectangles) {
        Map<Double, Double>
                xmap =
                        snapEdges(
                                rectangles.stream()
                                        .flatMap(r -> java.util.stream.Stream.of(r.x(), r.right()))
                                        .toList()),
                ymap =
                        snapEdges(
                                rectangles.stream()
                                        .flatMap(r -> java.util.stream.Stream.of(r.y(), r.bottom()))
                                        .toList());
        List<Rect> rects =
                rectangles.stream()
                        .map(
                                r ->
                                        new Rect(
                                                xmap.get(r.x()),
                                                ymap.get(r.y()),
                                                xmap.get(r.right()) - xmap.get(r.x()),
                                                ymap.get(r.bottom()) - ymap.get(r.y())))
                        .toList();
        List<Double>
                xs =
                        rects.stream()
                                .flatMap(r -> java.util.stream.Stream.of(r.x(), r.right()))
                                .distinct()
                                .sorted()
                                .toList(),
                ys =
                        rects.stream()
                                .flatMap(r -> java.util.stream.Stream.of(r.y(), r.bottom()))
                                .distinct()
                                .sorted()
                                .toList();
        int nx = xs.size() - 1, ny = ys.size() - 1;
        if (nx <= 0 || ny <= 0) return List.of();
        boolean[][] covered = new boolean[nx][ny];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++) {
                double x = (xs.get(i) + xs.get(i + 1)) / 2, y = (ys.get(j) + ys.get(j + 1)) / 2;
                for (Rect r : rects)
                    if (r.contains(x, y)) {
                        covered[i][j] = true;
                        break;
                    }
            }
        Map<Integer, List<Integer>> edges = new LinkedHashMap<>();
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                if (covered[i][j]) {
                    if (!covered(covered, i, j - 1)) edge(edges, i, j, i + 1, j, ny);
                    if (!covered(covered, i + 1, j)) edge(edges, i + 1, j, i + 1, j + 1, ny);
                    if (!covered(covered, i, j + 1)) edge(edges, i + 1, j + 1, i, j + 1, ny);
                    if (!covered(covered, i - 1, j)) edge(edges, i, j + 1, i, j, ny);
                }
        List<List<Point>> loops = new ArrayList<>();
        while (!edges.isEmpty()) {
            int start = edges.keySet().iterator().next(), current = start;
            List<Integer> nodes = new ArrayList<>();
            boolean closed = false;
            while (edges.containsKey(current)) {
                var outgoing = edges.get(current);
                int next = outgoing.remove(outgoing.size() - 1);
                if (outgoing.isEmpty()) edges.remove(current);
                nodes.add(current);
                current = next;
                if (current == start) {
                    closed = true;
                    break;
                }
            }
            if (!closed || nodes.size() < 4) continue;
            List<Point> loop = new ArrayList<>();
            for (int k = 0; k < nodes.size(); k++) {
                int prev = nodes.get((k + nodes.size() - 1) % nodes.size()),
                        cur = nodes.get(k),
                        next = nodes.get((k + 1) % nodes.size());
                int px = prev / (ny + 1),
                        py = prev % (ny + 1),
                        cx = cur / (ny + 1),
                        cy = cur % (ny + 1),
                        bx = next / (ny + 1),
                        by = next % (ny + 1);
                if (px == cx && cx == bx || py == cy && cy == by) continue;
                loop.add(new Point(xs.get(cx), ys.get(cy)));
            }
            loops.add(List.copyOf(loop));
        }
        return List.copyOf(loops);
    }

    /** Fallback uses a single even-odd scan conversion, so overlapping translucent HUDs never darken. */
    public static void draw(Painter painter, Shape shape) {
        List<List<Point>> loops =
                shape.loops().stream().map(loop -> rounded(loop, shape.radius())).toList();
        double top = Double.POSITIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (var l : loops)
            for (var p : l) {
                top = Math.min(top, p.y());
                bottom = Math.max(bottom, p.y());
            }
        if (!Double.isFinite(top)) return;
        for (double y = top; y < bottom; y += .75) {
            double sample = Math.min(bottom, y + .375);
            List<Double> crossings = new ArrayList<>();
            for (var l : loops)
                for (int i = 0; i < l.size(); i++) {
                    Point a = l.get(i), b = l.get((i + 1) % l.size());
                    if (a.y() <= sample && b.y() > sample || b.y() <= sample && a.y() > sample)
                        crossings.add(a.x() + (sample - a.y()) * (b.x() - a.x()) / (b.y() - a.y()));
                }
            crossings.sort(Double::compare);
            for (int i = 0; i + 1 < crossings.size(); i += 2)
                painter.rect(
                        new Rect(
                                crossings.get(i),
                                y,
                                Math.max(0, crossings.get(i + 1) - crossings.get(i)),
                                Math.min(.75, bottom - y)),
                        0,
                        shape.color());
        }
    }

    public static List<Point> rounded(List<Point> loop, double radius) {
        List<Point> out = new ArrayList<>();
        int n = loop.size();
        for (int i = 0; i < n; i++) {
            Point a = loop.get((i + n - 1) % n), c = loop.get(i), b = loop.get((i + 1) % n);
            double al = Math.hypot(a.x() - c.x(), a.y() - c.y()),
                    bl = Math.hypot(b.x() - c.x(), b.y() - c.y()),
                    r = Math.max(0, Math.min(radius, Math.min(al, bl) / 2));
            if (r <= .01) {
                out.add(c);
                continue;
            }
            double ux = (a.x() - c.x()) / al,
                    uy = (a.y() - c.y()) / al,
                    vx = (b.x() - c.x()) / bl,
                    vy = (b.y() - c.y()) / bl,
                    ox = c.x() + (ux + vx) * r,
                    oy = c.y() + (uy + vy) * r;
            double start = Math.atan2(-vy, -vx),
                    end = Math.atan2(-uy, -ux),
                    cross = ux * vy - uy * vx;
            if (cross < 0) while (end < start) end += Math.PI * 2;
            else while (end > start) end -= Math.PI * 2;
            for (int step = 0; step <= 8; step++) {
                double t = start + (end - start) * step / 8;
                out.add(new Point(ox + Math.cos(t) * r, oy + Math.sin(t) * r));
            }
        }
        return List.copyOf(out);
    }

    private static Map<Double, Double> snapEdges(List<Double> values) {
        List<Double> sorted = values.stream().distinct().sorted().toList();
        Map<Double, Double> map = new HashMap<>();
        for (int i = 0; i < sorted.size(); ) {
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j) - sorted.get(j - 1) <= 2) j++;
            double mean = 0;
            for (int k = i; k < j; k++) mean += sorted.get(k);
            mean /= (j - i);
            for (int k = i; k < j; k++) map.put(sorted.get(k), mean);
            i = j;
        }
        return map;
    }

    private static boolean covered(boolean[][] c, int x, int y) {
        return x >= 0 && y >= 0 && x < c.length && y < c[x].length && c[x][y];
    }

    private static void edge(
            Map<Integer, List<Integer>> edges, int x, int y, int bx, int by, int ny) {
        edges.computeIfAbsent(x * (ny + 1) + y, k -> new ArrayList<>()).add(bx * (ny + 1) + by);
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static double overlap(double a, double b, double c, double d) {
        return Math.min(b, d) - Math.max(a, c);
    }

    private static boolean nests(double a, double b, double c, double d) {
        return a >= c - 2 && b <= d + 2 || c >= a - 2 && d <= b + 2;
    }

    private static double aligned(double a, double b, double c, double d) {
        return Math.abs(a - c) <= 2 ? 0 : Math.abs(b - d) <= 2 ? 1 : .5;
    }
}
