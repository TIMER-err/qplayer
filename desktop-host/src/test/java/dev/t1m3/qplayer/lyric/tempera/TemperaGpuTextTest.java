package dev.t1m3.qplayer.lyric.tempera;

import com.sun.jna.NativeLibrary;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertTrue;

/** Issue #67: the lyric invert must survive the window framebuffer, not only raster. */
public class TemperaGpuTextTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 160;

    @BeforeClass
    public static void initializeFonts() {
        TemperaTestFonts.initialize();
    }

    @Test
    public void glFramebufferKeepsInvertedHanLyricsVisible() {
        boolean xwayland = System.getProperty("os.name", "").equals("Linux")
                && System.getenv("DISPLAY") != null && System.getenv("WAYLAND_DISPLAY") != null;
        if (xwayland) {
            // Match the production XWayland/EGL setup instead of testing an
            // unrelated native-Wayland or GLX path on this desktop session.
            NativeLibrary.getInstance("c").getFunction("setenv").invokeInt(
                    new Object[]{"__GL_THREADED_OPTIMIZATIONS", "0", 0});
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }
        Assume.assumeTrue("GLFW", GLFW.glfwInit());
        long window = MemoryUtil.NULL;
        try {
            GLFW.glfwDefaultWindowHints();
            if (xwayland) {
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
            }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(
                    WIDTH, HEIGHT, "tempera", MemoryUtil.NULL, MemoryUtil.NULL);
            Assume.assumeTrue("GL window", window != MemoryUtil.NULL);
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            try (DirectContext context = DirectContext.makeGL();
                 BackendRenderTarget target = BackendRenderTarget.makeGL(
                         WIDTH, HEIGHT, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8);
                 Surface surface = Surface.wrapBackendRenderTarget(
                         context, target, SurfaceOrigin.BOTTOM_LEFT,
                         ColorType.RGBA_8888, ColorSpace.getSRGB())) {
                Assume.assumeTrue("GPU surface", surface != null);
                try {
                    Canvas canvas = surface.getCanvas();
                    canvas.clear(0xFFFFFFFF);
                    TemperaTextView.paint(canvas, List.of(glyph()), 2, 0, 0, true, true);
                    context.flushAndSubmit(surface, true);
                    try (Image image = surface.makeImageSnapshot(); Bitmap bitmap = new Bitmap()) {
                        bitmap.allocPixels(ImageInfo.makeN32Premul(WIDTH, HEIGHT));
                        assertTrue(image.readPixels(context, bitmap, 0, 0, false));
                        ByteBuffer pixels = bitmap.peekPixels().getBuffer();
                        int dark = 0;
                        while (pixels.remaining() >= 4) {
                            int b = pixels.get() & 255;
                            int g = pixels.get() & 255;
                            int r = pixels.get() & 255;
                            pixels.get();
                            if (r + g + b < 400) dark++;
                        }
                        assertTrue("inverted Han pixels on GL = " + dark, dark > 250);
                    }
                } finally {
                    // Scene-owned resources retire while their rendering context is alive.
                    TemperaTextView.dispose();
                    TemperaMeasure.dispose();
                    TemperaFonts.dispose();
                }
            }
        } finally {
            GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
            GL.setCapabilities(null);
            if (window != MemoryUtil.NULL) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static TemperaTextView.View glyph() {
        TemperaTextView.View glyph = new TemperaTextView.View();
        glyph.charText = "裂";
        glyph.fontWeight = 600;
        glyph.fontSize = 96;
        glyph.baseX = WIDTH / 2f;
        glyph.baseY = HEIGHT / 2f;
        glyph.displayColor = "#111111";
        glyph.motion.startTime = 0;
        glyph.motion.settleTime = 0.5;
        glyph.motion.endTime = 10;
        glyph.motion.releaseTime = 12;
        glyph.motion.enterScale = 1;
        return glyph;
    }
}
