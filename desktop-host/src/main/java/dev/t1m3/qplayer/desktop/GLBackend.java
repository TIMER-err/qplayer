package dev.t1m3.qplayer.desktop;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

/**
 * OpenGL graphics backend: a GLFW GL context wrapped by a Skija
 * {@code DirectContext} rendering into the window's default framebuffer. A near
 * 1:1 port of the qml4j demo's {@code GlfwSurfaceBackend} (and the Android
 * shell's {@code SkijaGlSurface}), with context-current handling kept inside the
 * backend so the render thread only has to call {@link #init}.
 *
 * <p>All methods run on the render thread; the GL context is current on that
 * thread between {@link #init} and {@link #dispose}.
 */
final class GLBackend implements GraphicsBackend {

    private final long window;
    private int width;
    private int height;
    private DirectContext context;
    private BackendRenderTarget target;
    private Surface surface;

    GLBackend(long window) {
        this.window = window;
    }

    @Override
    public Kind kind() {
        return Kind.GL;
    }

    @Override
    public void init(int w, int h) {
        this.width = w;
        this.height = h;
        // The GL context is created against this window; make it current on the
        // calling (render) thread before any GL / Skija call.
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        boolean vsync = !"false".equals(System.getProperty("qplayer.vsync", "true"));
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
        // DirectContext.makeGL() binds to the GL context current on this thread.
        context = DirectContext.makeGL();
        rebuildSurface();
    }

    @Override
    public Canvas acquireCanvas() {
        // Clear through Skija, not a raw glClear: a bare glClear is invisible to
        // Skija's DirectContext and drops the frame's first draw (the root's full
        // fill), leaving the background black.
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF000000);
        return canvas;
    }

    @Override
    public DirectContext recordingContext() {
        return context;
    }

    @Override
    public void present() {
        context.flush();
        GLFW.glfwSwapBuffers(window);
    }

    @Override
    public void resize(int w, int h) {
        if (w == width && h == height) return;
        this.width = w;
        this.height = h;
        rebuildSurface();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }


    @Override
    public void dispose() {
        if (surface != null) { surface.close(); surface = null; }
        if (target != null) { target.close(); target = null; }
        if (context != null) { context.close(); context = null; }
        // Unbind the context so it isn't current when the render thread exits and
        // a fresh thread later re-binds it on restore-from-tray.
        GLFW.glfwMakeContextCurrent(0L);
    }

    private void rebuildSurface() {
        if (surface != null) surface.close();
        if (target != null) target.close();
        target = BackendRenderTarget.makeGL(width, height, 0, 8, 0,
                FramebufferFormat.GR_GL_RGBA8);
        surface = Surface.wrapBackendRenderTarget(
                context, target,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                ColorSpace.getSRGB()
        );
    }
}
