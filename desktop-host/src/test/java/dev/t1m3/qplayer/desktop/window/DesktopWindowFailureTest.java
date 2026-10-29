package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopWindowFailureTest {

    @Test
    public void vulkanBackendFailuresMayFallBackOnce() {
        assertTrue(DesktopWindow.shouldFallbackToOpenGL(
                GraphicsBackend.Kind.VULKAN, false,
                RenderThread.FailureStage.BACKEND_INITIALIZATION));
        assertTrue(DesktopWindow.shouldFallbackToOpenGL(
                GraphicsBackend.Kind.VULKAN, false,
                RenderThread.FailureStage.BACKEND_FRAME));
        assertFalse(DesktopWindow.shouldFallbackToOpenGL(
                GraphicsBackend.Kind.VULKAN, true,
                RenderThread.FailureStage.BACKEND_FRAME));
    }

    @Test
    public void applicationRenderingErrorsNeverChangeGraphicsBackend() {
        assertFalse(DesktopWindow.shouldFallbackToOpenGL(
                GraphicsBackend.Kind.VULKAN, false,
                RenderThread.FailureStage.APPLICATION_FRAME));
        assertFalse(DesktopWindow.shouldFallbackToOpenGL(
                GraphicsBackend.Kind.GL, false,
                RenderThread.FailureStage.BACKEND_INITIALIZATION));
    }

    @Test
    public void applicationFrameCrashesAskForRenderThreadRespawn() {
        assertTrue(DesktopWindow.shouldRespawnAfterApplicationError(
                RenderThread.FailureStage.APPLICATION_FRAME));
        assertFalse(DesktopWindow.shouldRespawnAfterApplicationError(
                RenderThread.FailureStage.BACKEND_FRAME));
        assertFalse(DesktopWindow.shouldRespawnAfterApplicationError(
                RenderThread.FailureStage.BACKEND_INITIALIZATION));
    }
}
