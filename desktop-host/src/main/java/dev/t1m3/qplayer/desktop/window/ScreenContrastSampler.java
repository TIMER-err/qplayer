package dev.t1m3.qplayer.desktop.window;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Periodically samples the real screen content behind the floating desktop
 * lyric window (Windows only) and reports its average luminance, so the
 * "auto contrast" colour scheme (SettingsCatalog.MODE_CONTRAST) can pick light
 * or dark lyric text against whatever actually happens to be on screen — a
 * wallpaper, a video, another window — rather than against a fixed scheme.
 *
 * <p>The lyric window excludes itself from the very capture it triggers via
 * {@code SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)}: without that, the
 * BitBlt below would read back the lyric window's own (semi-transparent)
 * pixels, turning this into a feedback loop instead of a measurement of
 * what's actually behind it. That flag is also why the window becomes
 * invisible to screenshots/recording/screen-share while this mode runs — an
 * unavoidable side effect of the technique, not a bug, and reverted the
 * moment the sampler stops.
 *
 * <p>Runs entirely on its own daemon thread: every GDI call here is a
 * synchronous native round trip, and this must never share the render or main
 * thread with QML/GL work.
 */
final class ScreenContrastSampler {

    private static final int WDA_NONE = 0x00000000;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;
    // A coarse stride over the window's own footprint is enough for an average —
    // this only ever feeds a light/dark decision, not a faithful image — and
    // keeps each BitBlt/GetDIBits round trip small regardless of window size.
    private static final int SAMPLE_STRIDE = 6;

    private final long glfwWindow;
    private final int intervalMs;
    private final Thread thread;
    private volatile boolean running;
    private volatile boolean excluded;
    // 0 (black) .. 1 (white). Neutral default until the first sample lands, so
    // an early publish() before the thread has run once still picks something
    // reasonable rather than reading an uninitialized 0.
    private volatile float luminance = 0.4f;

    ScreenContrastSampler(long glfwWindow, int intervalMs) {
        this.glfwWindow = glfwWindow;
        this.intervalMs = intervalMs;
        this.thread = new Thread(this::loop, "desktop-lyric-contrast-sample");
        this.thread.setDaemon(true);
    }

    void start() {
        if (running || GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WIN32) return;
        running = true;
        thread.start();
    }

    /** Restores normal capture visibility before the thread exits — this must
     *  not linger after the user switches away from contrast mode. */
    void stop() {
        if (!running) return;
        running = false;
        thread.interrupt();
        clearExclusion();
    }

    /** Main/render thread read of the sampler thread's latest average. */
    float luminance() {
        return luminance;
    }

    private void loop() {
        while (running) {
            try {
                sampleOnce();
            } catch (Throwable error) {
                Logger.warn("desktop lyric contrast sample failed: {}", error.toString());
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private WinDef.HWND hwnd() {
        long nativeHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        if (nativeHandle == 0L) return null;
        return new WinDef.HWND(Pointer.createConstant(nativeHandle));
    }

    private void clearExclusion() {
        if (!excluded) return;
        try {
            WinDef.HWND hwnd = hwnd();
            if (hwnd != null) User32Ext.I.SetWindowDisplayAffinity(hwnd, WDA_NONE);
        } catch (Throwable error) {
            Logger.warn("desktop lyric contrast un-exclude failed: {}", error.toString());
        } finally {
            excluded = false;
        }
    }

    private void sampleOnce() {
        WinDef.HWND hwnd = hwnd();
        if (hwnd == null) return;
        if (!excluded) {
            User32Ext.I.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE);
            excluded = true;
        }
        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return;
        int screenX = rect.left;
        int screenY = rect.top;
        int width = Math.max(1, rect.right - rect.left);
        int height = Math.max(1, rect.bottom - rect.top);

        WinDef.HDC screenDC = User32.INSTANCE.GetDC(null);
        if (screenDC == null) return;
        WinDef.HDC memDC = null;
        WinDef.HBITMAP bitmap = null;
        try {
            memDC = GDI32.INSTANCE.CreateCompatibleDC(screenDC);
            bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDC, width, height);
            WinNT.HANDLE old = GDI32.INSTANCE.SelectObject(memDC, bitmap);
            boolean copied = GDI32.INSTANCE.BitBlt(memDC, 0, 0, width, height,
                    screenDC, screenX, screenY, GDI32.SRCCOPY);
            GDI32.INSTANCE.SelectObject(memDC, old);
            if (!copied) return;

            WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
            info.bmiHeader.biSize = info.bmiHeader.size();
            info.bmiHeader.biWidth = width;
            info.bmiHeader.biHeight = -height; // negative = top-down rows
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = WinGDI.BI_RGB;

            int stride = width * 4;
            Memory buffer = new Memory((long) stride * height);
            int result = GDI32.INSTANCE.GetDIBits(memDC, bitmap, 0, height,
                    buffer, info, WinGDI.DIB_RGB_COLORS);
            if (result == 0) return;

            long sum = 0;
            int count = 0;
            for (int y = 0; y < height; y += SAMPLE_STRIDE) {
                long rowOffset = (long) y * stride;
                for (int x = 0; x < width; x += SAMPLE_STRIDE) {
                    long offset = rowOffset + (long) x * 4;
                    int b = buffer.getByte(offset) & 0xFF;
                    int g = buffer.getByte(offset + 1) & 0xFF;
                    int r = buffer.getByte(offset + 2) & 0xFF;
                    // Rec. 601 luma, not a flat RGB average, so a saturated hue
                    // does not read as darker or lighter than it actually looks.
                    sum += Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                    count++;
                }
            }
            if (count > 0) luminance = (float) (sum / (double) count) / 255f;
        } finally {
            if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
            if (memDC != null) GDI32.INSTANCE.DeleteDC(memDC);
            User32.INSTANCE.ReleaseDC(null, screenDC);
        }
    }

    /** jna-platform's User32 does not expose this (added in Windows 10 2004). */
    private interface User32Ext extends Library {
        User32Ext I = Native.load("user32", User32Ext.class);

        boolean SetWindowDisplayAffinity(WinDef.HWND hWnd, int dwAffinity);
    }
}
