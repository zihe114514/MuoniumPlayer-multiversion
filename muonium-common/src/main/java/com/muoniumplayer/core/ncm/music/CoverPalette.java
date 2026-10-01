package com.muoniumplayer.core.ncm.music;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从已经解码好的封面中提取代表色。
 *
 * <p>这里移植的是 Folia 使用的 weighted median-cut：大面积颜色比零散高饱和噪点更有权重，
 * 因此提取结果更接近整张封面的主视觉，而不是只挑最鲜艳的几个像素。</p>
 */
public final class CoverPalette {

    private static final int QUANTIZATION_SHIFT = 4;
    private static final int MIN_ALPHA = 128;

    private CoverPalette() {
    }

    public static int[] extract(BufferedImage image, int count) {
        if (image == null || count <= 0 || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return new int[0];
        }

        final int width = image.getWidth();
        final int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        Map<Integer, WeightedColor> histogram = new HashMap<Integer, WeightedColor>();
        for (int pixel : pixels) {
            int alpha = (pixel >>> 24) & 0xFF;
            if (alpha < MIN_ALPHA) continue;
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            int key = ((r >> QUANTIZATION_SHIFT) << 8)
                    | ((g >> QUANTIZATION_SHIFT) << 4)
                    | (b >> QUANTIZATION_SHIFT);
            WeightedColor existing = histogram.get(key);
            if (existing == null) {
                histogram.put(key, new WeightedColor(r, g, b));
            } else {
                existing.add(r, g, b);
            }
        }
        if (histogram.isEmpty()) return new int[0];

        List<WeightedColor> colors = new ArrayList<WeightedColor>(histogram.size());
        for (WeightedColor color : histogram.values()) colors.add(color.average());
        List<Bucket> buckets = new ArrayList<Bucket>();
        buckets.add(new Bucket(colors));
        while (buckets.size() < Math.min(count, colors.size())) {
            int bucketIndex = -1;
            double bestScore = -1.0;
            for (int i = 0; i < buckets.size(); i++) {
                Bucket bucket = buckets.get(i);
                if (bucket.colors.size() < 2) continue;
                double score = bucket.widestRange() * Math.sqrt(bucket.weight);
                if (score > bestScore) {
                    bestScore = score;
                    bucketIndex = i;
                }
            }
            if (bucketIndex < 0) break;
            Bucket.Split split = buckets.get(bucketIndex).split();
            if (split == null) break;
            buckets.set(bucketIndex, split.left);
            buckets.add(bucketIndex + 1, split.right);
        }

        buckets.sort(Comparator.comparingDouble((Bucket bucket) -> bucket.weight).reversed());
        int[] result = new int[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            WeightedColor color = buckets.get(i).average();
            result[i] = (color.r << 16) | (color.g << 8) | color.b;
        }
        return result;
    }

    private static final class WeightedColor {
        int r;
        int g;
        int b;
        int weight = 1;

        WeightedColor(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        void add(int r, int g, int b) {
            this.r += r;
            this.g += g;
            this.b += b;
            this.weight++;
        }

        WeightedColor average() {
            return new WeightedColor(
                    Math.round(r / (float) weight),
                    Math.round(g / (float) weight),
                    Math.round(b / (float) weight),
                    weight);
        }

        WeightedColor(int r, int g, int b, int weight) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.weight = weight;
        }
    }

    private static final class Bucket {
        final List<WeightedColor> colors;
        final int weight;

        Bucket(List<WeightedColor> colors) {
            this.colors = colors;
            int sum = 0;
            for (WeightedColor color : colors) sum += color.weight;
            this.weight = sum;
        }

        int range(char channel) {
            int min = 255;
            int max = 0;
            for (WeightedColor color : colors) {
                int value = channel == 'r' ? color.r : channel == 'g' ? color.g : color.b;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            return max - min;
        }

        int widestRange() {
            return Math.max(range('r'), Math.max(range('g'), range('b')));
        }

        WeightedColor average() {
            int r = 0;
            int g = 0;
            int b = 0;
            for (WeightedColor color : colors) {
                r += color.r * color.weight;
                g += color.g * color.weight;
                b += color.b * color.weight;
            }
            return new WeightedColor(Math.round(r / (float) weight),
                    Math.round(g / (float) weight), Math.round(b / (float) weight), weight);
        }

        Split split() {
            if (colors.size() < 2) return null;
            char channel = range('r') >= range('g') && range('r') >= range('b')
                    ? 'r' : range('g') >= range('b') ? 'g' : 'b';
            List<WeightedColor> sorted = new ArrayList<WeightedColor>(colors);
            sorted.sort(Comparator.comparingInt(color ->
                    channel == 'r' ? color.r : channel == 'g' ? color.g : color.b));

            int midpoint = weight / 2;
            int accumulated = 0;
            int splitIndex = 1;
            for (int i = 0; i < sorted.size() - 1; i++) {
                accumulated += sorted.get(i).weight;
                splitIndex = i + 1;
                if (accumulated >= midpoint) break;
            }
            return new Split(
                    new Bucket(new ArrayList<WeightedColor>(sorted.subList(0, splitIndex))),
                    new Bucket(new ArrayList<WeightedColor>(sorted.subList(splitIndex, sorted.size()))));
        }

        static final class Split {
            final Bucket left;
            final Bucket right;

            Split(Bucket left, Bucket right) {
                this.left = left;
                this.right = right;
            }
        }
    }
}
