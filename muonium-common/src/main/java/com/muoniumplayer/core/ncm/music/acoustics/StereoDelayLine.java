package com.muoniumplayer.core.ncm.music.acoustics;

import java.util.Arrays;

/** 双通路因果延迟线。延迟 0 读取刚写入的样本,分数部分向更旧的样本插值。 */
final class StereoDelayLine {

    private float[] left = new float[0];
    private float[] right = new float[0];
    private int position;
    private int written;

    void rebuild(int capacity) {
        left = new float[Math.max(4, capacity)];
        right = new float[left.length];
        position = 0;
        written = 0;
    }

    void write(float l, float r) {
        written = position;
        left[position] = l;
        right[position] = r;
        position = position + 1 == left.length ? 0 : position + 1;
    }

    float left(double delay) {
        return read(left, delay);
    }

    float right(double delay) {
        return read(right, delay);
    }

    private float read(float[] ring, double delay) {
        final double age = Double.isFinite(delay)
                ? Math.max(0.0, Math.min(ring.length - 2.0, delay)) : 0.0;
        final int whole = (int) age;
        final float fraction = (float) (age - whole);
        int index = written - whole;
        if (index < 0) {
            index += ring.length;
        }
        final int older = index == 0 ? ring.length - 1 : index - 1;
        return ring[index] + (ring[older] - ring[index]) * fraction;
    }

    void flush() {
        Arrays.fill(left, 0f);
        Arrays.fill(right, 0f);
        position = 0;
        written = 0;
    }
}
