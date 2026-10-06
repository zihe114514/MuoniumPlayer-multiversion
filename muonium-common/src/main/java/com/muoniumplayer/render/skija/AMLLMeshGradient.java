/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (c) 2022-2024 AMLL Contributors
 *
 * Java/Skia port of AMLL Core's Mesh Gradient renderer, control-point
 * presets/generator and Bicubic Hermite Patch Mesh implementation.
 *
 * QPlayer modifications (2026):
 * - C++/Qt Quick Scene Graph -> Java 21 + Skija drawVertices
 * - Qt shader resources -> SkSL RuntimeEffect resources
 * - Uses 24 subdivisions and retains AMLL's preset/random selection behavior
 *
 * Source: https://github.com/amll-dev/applemusic-like-lyrics
 * License: GNU Affero General Public License v3.0 only.
 */
package com.muoniumplayer.render.skija;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

final class AMLLMeshGradient {
    private static final int SUBDIVISIONS = 24;
    private static final String PRESETS_RESOURCE =
            "/shaders/fluid/meshgradient_presets.txt";
    private static volatile List<Preset> presets;

    private AMLLMeshGradient() {}

    static final class Data {
        final float[] normalizedPoints;
        final float[] textureCoordinates;
        final short[] indices;
        private float[] screenPoints;
        private float screenW = -1f;
        private float screenH = -1f;

        Data(float[] normalizedPoints, float[] textureCoordinates, short[] indices) {
            this.normalizedPoints = normalizedPoints;
            this.textureCoordinates = textureCoordinates;
            this.indices = indices;
        }

        float[] points(float w, float h) {
            if (screenPoints != null && screenW == w && screenH == h) return screenPoints;
            float aspect = w / h;
            float[] out = new float[normalizedPoints.length];
            for (int i = 0; i < normalizedPoints.length; i += 2) {
                float x = normalizedPoints[i];
                // AMLL's mesh positions are WebGL clip coordinates (Y points up),
                // while Skia device coordinates point down.
                float y = -normalizedPoints[i + 1];
                if (aspect > 1f) y *= aspect;
                else x /= aspect;
                out[i] = (x * 0.5f + 0.5f) * w;
                out[i + 1] = (y * 0.5f + 0.5f) * h;
            }
            screenPoints = out;
            screenW = w;
            screenH = h;
            return out;
        }
    }

    private static final class Config {
        int cx;
        int cy;
        double x;
        double y;
        double ur;
        double vr;
        double up;
        double vp;

        Config(int cx, int cy, double x, double y, double ur, double vr, double up, double vp) {
            this.cx = cx;
            this.cy = cy;
            this.x = x;
            this.y = y;
            this.ur = ur;
            this.vr = vr;
            this.up = up;
            this.vp = vp;
        }

        Config copy() {
            return new Config(cx, cy, x, y, ur, vr, up, vp);
        }
    }

    private static final class Preset {
        final int width;
        final int height;
        final List<Config> points;

        Preset(int width, int height, List<Config> points) {
            this.width = width;
            this.height = height;
            this.points = points;
        }
    }

    private static final class ControlPoint {
        double x;
        double y;
        double ux;
        double uy;
        double vx;
        double vy;
    }

    static Data create() {
        Random random = new Random();
        List<Preset> all = presets();
        Preset chosen = random.nextDouble() > 0.8
                ? generateControlPoints(6, 6, random)
                : all.get(random.nextInt(all.size()));
        return build(chosen);
    }

    private static Data build(Preset preset) {
        ControlPoint[][] cp = new ControlPoint[preset.height][preset.width];
        double uPower = 2.0 / (preset.width - 1);
        double vPower = 2.0 / (preset.height - 1);
        for (Config conf : preset.points) {
            ControlPoint p = new ControlPoint();
            p.x = conf.x;
            p.y = conf.y;
            double ur = Math.toRadians(conf.ur);
            double vr = Math.toRadians(conf.vr);
            double us = uPower * conf.up;
            double vs = vPower * conf.vp;
            p.ux = Math.cos(ur) * us;
            p.uy = Math.sin(ur) * us;
            p.vx = -Math.sin(vr) * vs;
            p.vy = Math.cos(vr) * vs;
            cp[conf.cy][conf.cx] = p;
        }

        int vertexW = (preset.height - 1) * SUBDIVISIONS;
        int vertexH = (preset.width - 1) * SUBDIVISIONS;
        float[] positions = new float[vertexW * vertexH * 2];
        float[] texCoords = new float[positions.length];
        double invSubDivM1 = 1.0 / (SUBDIVISIONS - 1);
        double invTW = 1.0 / ((SUBDIVISIONS - 1) * (preset.height - 1));
        double invTH = 1.0 / ((SUBDIVISIONS - 1) * (preset.width - 1));

        for (int x = 0; x < preset.width - 1; x++) {
            for (int y = 0; y < preset.height - 1; y++) {
                double sx = x / (double) (preset.width - 1);
                double sy = y / (double) (preset.height - 1);
                int baseVX = y * SUBDIVISIONS;
                int baseVY = x * SUBDIVISIONS;
                for (int u = 0; u < SUBDIVISIONS; u++) {
                    double fu = u * invSubDivM1;
                    int vx = baseVX + u;
                    for (int v = 0; v < SUBDIVISIONS; v++) {
                        double fv = v * invSubDivM1;
                        int vy = baseVY + v;
                        int index = (vx + vy * vertexW) * 2;
                        // AMLL's matrix layout evaluates u along the control-point Y
                        // axis and v along X, then stores the resulting transposed grid.
                        double[] pos = evalPatch(cp, x, y, fv, fu);
                        positions[index] = (float) pos[0];
                        positions[index + 1] = (float) pos[1];
                        texCoords[index] = (float) (sx + v * invTH) * 32f;
                        texCoords[index + 1] = (float) (1.0 - sy - u * invTW) * 32f;
                    }
                }
            }
        }

        short[] indices = new short[(vertexW - 1) * (vertexH - 1) * 6];
        int offset = 0;
        for (int y = 0; y < vertexH - 1; y++) {
            for (int x = 0; x < vertexW - 1; x++) {
                int a = y * vertexW + x;
                int b = a + 1;
                int c = a + vertexW;
                int d = c + 1;
                indices[offset++] = (short) a;
                indices[offset++] = (short) b;
                indices[offset++] = (short) c;
                indices[offset++] = (short) b;
                indices[offset++] = (short) d;
                indices[offset++] = (short) c;
            }
        }
        return new Data(positions, texCoords, indices);
    }

    private static double[] evalPatch(ControlPoint[][] cp, int x, int y, double u, double v) {
        ControlPoint p00 = cp[y][x];
        ControlPoint p10 = cp[y][x + 1];
        ControlPoint p01 = cp[y + 1][x];
        ControlPoint p11 = cp[y + 1][x + 1];
        double[] out = new double[2];
        double hu0 = 2 * u * u * u - 3 * u * u + 1;
        double hu1 = u * u * u - 2 * u * u + u;
        double hu2 = -2 * u * u * u + 3 * u * u;
        double hu3 = u * u * u - u * u;
        double hv0 = 2 * v * v * v - 3 * v * v + 1;
        double hv1 = v * v * v - 2 * v * v + v;
        double hv2 = -2 * v * v * v + 3 * v * v;
        double hv3 = v * v * v - v * v;
        for (int axis = 0; axis < 2; axis++) {
            double a00 = axis == 0 ? p00.x : p00.y;
            double a10 = axis == 0 ? p10.x : p10.y;
            double a01 = axis == 0 ? p01.x : p01.y;
            double a11 = axis == 0 ? p11.x : p11.y;
            double u00 = axis == 0 ? p00.ux : p00.uy;
            double u10 = axis == 0 ? p10.ux : p10.uy;
            double u01 = axis == 0 ? p01.ux : p01.uy;
            double u11 = axis == 0 ? p11.ux : p11.uy;
            double v00 = axis == 0 ? p00.vx : p00.vy;
            double v10 = axis == 0 ? p10.vx : p10.vy;
            double v01 = axis == 0 ? p01.vx : p01.vy;
            double v11 = axis == 0 ? p11.vx : p11.vy;
            double q0 = hu0 * a00 + hu1 * u00 + hu2 * a10 + hu3 * u10;
            double q1 = hu0 * a01 + hu1 * u01 + hu2 * a11 + hu3 * u11;
            double r0 = hu0 * v00 + hu2 * v10;
            double r1 = hu0 * v01 + hu2 * v11;
            out[axis] = hv0 * q0 + hv1 * r0 + hv2 * q1 + hv3 * r1;
        }
        return out;
    }

    private static List<Preset> presets() {
        List<Preset> result = presets;
        if (result != null) return result;
        synchronized (AMLLMeshGradient.class) {
            if (presets == null) presets = Collections.unmodifiableList(loadPresets());
            return presets;
        }
    }

    private static List<Preset> loadPresets() {
        List<Preset> result = new ArrayList<>();
        try (InputStream in = AMLLMeshGradient.class.getResourceAsStream(PRESETS_RESOURCE)) {
            if (in == null) throw new IOException("resource not found: " + PRESETS_RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int width = 0;
            int height = 0;
            List<Config> points = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] values = line.split("\\s+");
                if ("preset".equals(values[0])) {
                    if (points != null) result.add(new Preset(width, height, points));
                    width = Integer.parseInt(values[1]);
                    height = Integer.parseInt(values[2]);
                    points = new ArrayList<>(width * height);
                } else if (points != null) {
                    double ur = values.length > 4 ? Double.parseDouble(values[4]) : 0;
                    double vr = values.length > 5 ? Double.parseDouble(values[5]) : 0;
                    double up = values.length > 6 ? Double.parseDouble(values[6]) : 1;
                    double vp = values.length > 7 ? Double.parseDouble(values[7]) : 1;
                    points.add(new Config(Integer.parseInt(values[0]), Integer.parseInt(values[1]),
                            Double.parseDouble(values[2]), Double.parseDouble(values[3]), ur, vr, up, vp));
                }
            }
            if (points != null) result.add(new Preset(width, height, points));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load AMLL mesh gradient presets", e);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No AMLL mesh gradient presets loaded");
        }
        return result;
    }

    private static Preset generateControlPoints(int width, int height, Random random) {
        double variationFraction = randomRange(random, 0.4, 0.6);
        double normalOffset = randomRange(random, 0.3, 0.6);
        double blendFactor = 0.8;
        int smoothIterations = (int) Math.floor(randomRange(random, 3, 5));
        double smoothFactor = randomRange(random, 0.2, 0.3);
        double smoothModifier = randomRange(random, -0.1, -0.05);
        List<Config> conf = new ArrayList<>(width * height);
        double dx = 2.0 / (width - 1);
        double dy = 2.0 / (height - 1);

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                double baseX = i / (double) (width - 1) * 2 - 1;
                double baseY = j / (double) (height - 1) * 2 - 1;
                boolean border = i == 0 || i == width - 1 || j == 0 || j == height - 1;
                double x = baseX + (border ? 0 : randomRange(random,
                        -variationFraction * dx, variationFraction * dx));
                double y = baseY + (border ? 0 : randomRange(random,
                        -variationFraction * dy, variationFraction * dy));
                double ur = border ? 0 : randomRange(random, -60, 60);
                double vr = border ? 0 : randomRange(random, -60, 60);
                double up = border ? 1 : randomRange(random, 0.8, 1.2);
                double vp = border ? 1 : randomRange(random, 0.8, 1.2);
                if (!border) {
                    double un = (baseX + 1) / 2;
                    double vn = (baseY + 1) / 2;
                    double[] gradient = noiseGradient(un, vn);
                    double distance = Math.min(Math.min(un, 1 - un), Math.min(vn, 1 - vn));
                    double weight = smoothstep(distance);
                    x += gradient[0] * normalOffset * weight * blendFactor;
                    y += gradient[1] * normalOffset * weight * blendFactor;
                }
                conf.add(new Config(i, j, x, y, ur, vr, up, vp));
            }
        }
        smoothify(conf, width, height, smoothIterations, smoothFactor, smoothModifier);
        return new Preset(width, height, conf);
    }

    private static void smoothify(List<Config> conf, int w, int h, int iterations,
                                  double factor, double modifier) {
        Config[][] grid = new Config[h][w];
        for (Config c : conf) grid[c.cy][c.cx] = c;
        int[][] kernel = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}};
        double f = factor;
        for (int iteration = 0; iteration < iterations; iteration++) {
            Config[][] next = new Config[h][w];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    Config current = grid[j][i];
                    if (i == 0 || i == w - 1 || j == 0 || j == h - 1) {
                        next[j][i] = current.copy();
                        continue;
                    }
                    double[] sums = new double[6];
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int weight = kernel[dj + 1][di + 1];
                            Config n = grid[j + dj][i + di];
                            sums[0] += n.x * weight;
                            sums[1] += n.y * weight;
                            sums[2] += n.ur * weight;
                            sums[3] += n.vr * weight;
                            sums[4] += n.up * weight;
                            sums[5] += n.vp * weight;
                        }
                    }
                    next[j][i] = new Config(i, j,
                            mix(current.x, sums[0] / 16, f), mix(current.y, sums[1] / 16, f),
                            mix(current.ur, sums[2] / 16, f), mix(current.vr, sums[3] / 16, f),
                            mix(current.up, sums[4] / 16, f), mix(current.vp, sums[5] / 16, f));
                }
            }
            grid = next;
            f = clamp01(f + modifier);
        }
        conf.clear();
        for (int j = 0; j < h; j++) Collections.addAll(conf, grid[j]);
    }

    private static double[] noiseGradient(double x, double y) {
        double epsilon = 0.001;
        double dx = (smoothNoise(x + epsilon, y) - smoothNoise(x - epsilon, y)) / (2 * epsilon);
        double dy = (smoothNoise(x, y + epsilon) - smoothNoise(x, y - epsilon)) / (2 * epsilon);
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length == 0) length = 1;
        return new double[]{dx / length, dy / length};
    }

    private static double smoothNoise(double x, double y) {
        double x0 = Math.floor(x);
        double y0 = Math.floor(y);
        double xf = x - x0;
        double yf = y - y0;
        double u = xf * xf * (3 - 2 * xf);
        double v = yf * yf * (3 - 2 * yf);
        double nx0 = mix(noise(x0, y0), noise(x0 + 1, y0), u);
        double nx1 = mix(noise(x0, y0 + 1), noise(x0 + 1, y0 + 1), u);
        return mix(nx0, nx1, v);
    }

    private static double noise(double x, double y) {
        return fract(Math.sin(x * 12.9898 + y * 78.233) * 43758.5453);
    }

    private static double smoothstep(double x) {
        double t = clamp01(x);
        return t * t * (3 - 2 * t);
    }

    private static double randomRange(Random random, double min, double max) {
        return random.nextDouble() * (max - min) + min;
    }

    private static double mix(double a, double b, double t) {
        return a * (1 - t) + b * t;
    }

    private static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static double fract(double x) {
        return x - Math.floor(x);
    }
}
