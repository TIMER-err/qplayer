// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Modified for QPlayer: Java SensorManager lifecycle and quantized cross-thread
// angle delivery, retaining upstream device-space rotation-vector gravity.
// Adapted from miuix DeviceTilt.android.kt and LiquidGlassNavigationBar.kt at
// b587aaf2c907c77a8c3c02d8df0aede2937ef8f0.
package io.github.timer_err.qml4j.android;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** UI-thread sensor ownership; the GL thread only reads the volatile angle. */
final class LiquidGlassTilt implements SensorEventListener {
    private static final float DEFAULT_ANGLE = (float) (-Math.PI / 2.0);
    private static final float ANGLE_STEP = (float) (Math.PI / 60.0);
    private static final float SMOOTHING = 0.15f;

    private final View host;
    private final Runnable wakeRenderer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRegistration = this::updateRegistration;
    private final float[] rotationMatrix = new float[9];
    private volatile float angle = DEFAULT_ANGLE;
    private volatile boolean active;
    private volatile boolean disposed;
    // QPlayer creates its SurfaceView from a posted splash task, after the
    // Activity's first onResume. Later explicit pauses/resumes override this.
    private boolean resumed = true;
    private SensorManager sensorManager;
    private Sensor sensor;
    private boolean registered;
    private boolean initialized;
    private float gravityX;
    private float gravityY;

    LiquidGlassTilt(View host, Runnable wakeRenderer) {
        this.host = host;
        this.wakeRenderer = wakeRenderer;
    }

    float angle() {
        return angle;
    }

    /** Called after composition, when the actual phone-only surface is known. */
    void setActive(boolean enabled) {
        if (disposed || active == enabled) return;
        active = enabled;
        // Only activity transitions cross threads, never individual sensor events.
        mainHandler.removeCallbacks(updateRegistration);
        mainHandler.post(updateRegistration);
        if (disposed) mainHandler.removeCallbacks(updateRegistration);
    }

    /** Lifecycle entry points are called on the UI thread. */
    void onResume() {
        if (disposed) return;
        resumed = true;
        // Wait for a new composed frame rather than reusing pre-pause visibility.
        active = false;
    }

    void onPause() {
        resumed = false;
        active = false;
        mainHandler.removeCallbacks(updateRegistration);
        unregister();
    }

    void dispose() {
        disposed = true;
        onPause();
    }

    private void updateRegistration() {
        if (disposed || !resumed || !active || !host.isAttachedToWindow()
                || host.getWindowVisibility() != View.VISIBLE) {
            unregister();
            return;
        }
        if (registered) return;
        if (sensorManager == null) {
            sensorManager = (SensorManager) host.getContext().getApplicationContext()
                    .getSystemService(Context.SENSOR_SERVICE);
        }
        if (sensorManager == null) return;
        if (sensor == null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (sensor == null) sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (sensor != null) {
            registered = sensorManager.registerListener(this, sensor,
                    SensorManager.SENSOR_DELAY_GAME, mainHandler);
        }
    }

    private void unregister() {
        if (registered) {
            sensorManager.unregisterListener(this);
            registered = false;
        }
        initialized = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!registered || !resumed || !active || disposed) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        float x = -rotationMatrix[6];
        float y = -rotationMatrix[7];
        if (!initialized) {
            gravityX = x;
            gravityY = y;
            initialized = true;
        } else {
            gravityX += (x - gravityX) * SMOOTHING;
            gravityY += (y - gravityY) * SMOOTHING;
        }
        // Upstream deliberately uses device-space gravity without display remapping.
        float next = gravityX * gravityX + gravityY * gravityY > 0.01f
                ? Math.round((float) Math.atan2(gravityY, gravityX) / ANGLE_STEP) * ANGLE_STEP
                : DEFAULT_ANGLE;
        if (next != angle) {
            angle = next;
            wakeRenderer.run();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
