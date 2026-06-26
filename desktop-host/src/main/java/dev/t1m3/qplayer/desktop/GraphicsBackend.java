package dev.t1m3.qplayer.desktop;

import io.github.timer_err.qml4j.render.SurfaceBackend;

/**
 * Switchable GPU backend for the desktop host. Both implementations bridge a
 * GLFW window to a Skija {@code DirectContext} + window {@code Surface}, and are
 * created/owned/destroyed entirely on the render thread (so minimize-to-tray can
 * tear the whole GPU stack down and rebuild it).
 *
 * <p>Extends qml4j's {@link SurfaceBackend} so the same per-frame contract
 * ({@code init / acquireCanvas / recordingContext / present / resize / dispose})
 * the engine already speaks is reused; the host drives it manually each frame to
 * interleave the host-drawn lyric overlay.
 *
 * <p>Implementations: {@link GLBackend} (OpenGL, default) and
 * {@link VulkanBackend} (behind {@code -Dqplayer.gfx=vulkan}).
 */
public interface GraphicsBackend extends SurfaceBackend {

    enum Kind {
        GL, VULKAN;

        /** Resolve the backend kind from {@code -Dqplayer.gfx=gl|vulkan} (default GL). */
        static Kind fromProperty() {
            String v = System.getProperty("qplayer.gfx", "gl").trim().toLowerCase();
            return "vulkan".equals(v) || "vk".equals(v) ? VULKAN : GL;
        }
    }

    /** Which backend this is (for logging / the window-creation client-API hint). */
    Kind kind();
}
