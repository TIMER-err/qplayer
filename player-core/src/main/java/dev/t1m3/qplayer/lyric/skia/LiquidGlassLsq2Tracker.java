// Copyright 2019 The Android Open Source Project
// SPDX-License-Identifier: Apache-2.0
// Modified Java port of Compose VelocityTracker1D's Lsq2 strategy.
package dev.t1m3.qplayer.lyric.skia;

/** Compose's 20-sample, 100ms quadratic fit; all scratch storage is reused. */
final class LiquidGlassLsq2Tracker {
    private final long[] times = new long[20];
    private final float[] positions = new float[20];
    private final float[] x = new float[20];
    private final float[] y = new float[20];
    private final float[][] a = new float[3][20];
    private final float[][] q = new float[3][20];
    private final float[][] r = new float[3][3];
    private final float[] coefficients = new float[3];
    private int index;
    private int size;

    void reset() {
        index = 0;
        size = 0;
    }

    float addPosition(long timeMillis, float position) {
        index = (index + 1) % 20;
        times[index] = timeMillis;
        positions[index] = position;
        size = Math.min(20, size + 1);
        int count = 0;
        long previous = timeMillis;
        for (int i = 0; i < size; i++) {
            int sample = (index - i + 20) % 20;
            long age = timeMillis - times[sample];
            if (age > 100 || Math.abs(times[sample] - previous) > 40) break;
            previous = times[sample];
            x[count] = -age;
            y[count++] = positions[sample];
        }
        if (count < 2) return 0;
        int degree = Math.min(3, count);
        for (int h = 0; h < count; h++) {
            a[0][h] = 1;
            for (int j = 1; j < degree; j++) a[j][h] = a[j - 1][h] * x[h];
        }
        for (int j = 0; j < degree; j++) {
            float[] w = q[j];
            System.arraycopy(a[j], 0, w, 0, count);
            for (int i = 0; i < j; i++) {
                float dot = dot(w, q[i], count);
                for (int h = 0; h < count; h++) w[h] -= dot * q[i][h];
            }
            float inverseNorm = 1f / Math.max(1e-6f, (float) Math.sqrt(dot(w, w, count)));
            for (int h = 0; h < count; h++) w[h] *= inverseNorm;
            for (int i = 0; i < degree; i++) r[j][i] = i < j ? 0 : dot(w, a[i], count);
        }
        for (int i = degree - 1; i >= 0; i--) {
            float c = dot(q[i], y, count);
            for (int j = degree - 1; j > i; j--) c -= r[i][j] * coefficients[j];
            coefficients[i] = c / r[i][i];
        }
        float velocity = coefficients[1] * 1000;
        return Float.isNaN(velocity) ? 0 : Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, velocity));
    }

    private static float dot(float[] a, float[] b, int count) {
        float result = 0;
        for (int i = 0; i < count; i++) result += a[i] * b[i];
        return result;
    }
}
