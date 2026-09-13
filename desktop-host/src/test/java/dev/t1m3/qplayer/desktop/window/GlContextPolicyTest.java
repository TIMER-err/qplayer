package dev.t1m3.qplayer.desktop.window;

import org.junit.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.Assert.assertEquals;

public class GlContextPolicyTest {
    @Test
    public void autoSelectsEglOnlyForXwayland() {
        assertEquals(GLFW.GLFW_EGL_CONTEXT_API,
                GlContextPolicy.select("auto", GLFW.GLFW_PLATFORM_X11, true));
        for (int platform : new int[]{GLFW.GLFW_PLATFORM_WIN32, GLFW.GLFW_PLATFORM_COCOA,
                GLFW.GLFW_PLATFORM_WAYLAND, GLFW.GLFW_PLATFORM_X11}) {
            assertEquals(GLFW.GLFW_NATIVE_CONTEXT_API, GlContextPolicy.select("auto", platform, false));
        }
        assertEquals(GLFW.GLFW_NATIVE_CONTEXT_API,
                GlContextPolicy.select("auto", GLFW.GLFW_PLATFORM_WIN32, true));
    }

    @Test
    public void explicitSelectionOverridesSessionPolicy() {
        assertEquals(GLFW.GLFW_NATIVE_CONTEXT_API,
                GlContextPolicy.select("native", GLFW.GLFW_PLATFORM_X11, true));
        assertEquals(GLFW.GLFW_EGL_CONTEXT_API,
                GlContextPolicy.select("egl", GLFW.GLFW_PLATFORM_X11, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidSelectionIsRejected() {
        GlContextPolicy.select("eg1", GLFW.GLFW_PLATFORM_X11, true);
    }
}
