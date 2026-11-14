package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.lyric.skia.LiquidGlassNode;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Issue #86: native resize must not replace a pending glass copy's source attachments. */
public class GLBackendResizeTest {
    private static final int HEIGHT = 360;
    private static final int TOP = 0xFF208040;
    private static final int BOTTOM = 0xFF204080;

    @Test
    public void glassCopiesKeepOwnedAttachmentsAcrossNativeResizeAndRestore() {
        boolean linux = System.getProperty("os.name", "").equals("Linux");
        Assume.assumeTrue("A desktop display is required", !linux || System.getenv("DISPLAY") != null);
        GlContextPolicy.prepareEnvironment();
        if (linux) GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        assertTrue("GLFW initialization", GLFW.glfwInit());
        long window = MemoryUtil.NULL;
        try {
            GLFW.glfwDefaultWindowHints();
            GlContextPolicy.configureWindow();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(1100, HEIGHT, "glass-resize-regression",
                    MemoryUtil.NULL, MemoryUtil.NULL);
            assertNotEquals("GL window", MemoryUtil.NULL, window);
            // Rebuild the Skija context as the real tray restore path does, retaining
            // the native GLFW context but retiring every scene resource before it.
            for (int generation = 0; generation < 2; generation++) {
                GLBackend backend = new GLBackend(window);
                int[] size = framebufferSize(window);
                backend.init(size[0], size[1]);
                try (GlassScene scene = new GlassScene(); Paint paint = new Paint()) {
                    scene.view.renderer().setGpuContext(backend.recordingContext());
                    backend.setBackdropCapture(true);
                    int[] widths = {1100, 839, 841, 600, 420, 839, 1100};
                    for (int width : widths) {
                        size = framebufferSize(window);
                        backend.resize(size[0], size[1]);
                        Canvas canvas = backend.acquireCanvas();
                        try (Surface source = canvas.getSurface()) {
                            assertNotNull("live GPU source", source);
                            canvas.drawRect(Rect.makeWH(size[0], size[1] / 2f), paint.setColor(TOP));
                            canvas.drawRect(Rect.makeLTRB(0, size[1] / 2f, size[0], size[1]),
                                    paint.setColor(BOTTOM));
                            backend.recordingContext().flushAndSubmit(source, true);
                            assertNotEquals("glass source must not be the externally resized FBO 0", 0,
                                    GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                            boolean compact = size[0] < 840;
                            scene.draw(canvas, size[0], size[1], compact);
                            // Deliberately resize AFTER recording the glass snapshots
                            // and BEFORE their deferred copies execute in present().
                            GLFW.glfwSetWindowSize(window, width, HEIGHT);
                            GLFW.glfwPollEvents();
                            backend.present();
                            assertEquals(size[0], source.getWidth());
                            assertEquals(size[1], source.getHeight());
                            assertPixels(source, size[0], size[1], compact);
                            assertEquals("OpenGL error after resize/capture/present", GL11.GL_NO_ERROR,
                                    GL11.glGetError());
                        }
                    }
                    // Disabling glass restores the zero-extra-surface path, and
                    // enabling it again allocates a live source on this context.
                    for (boolean enabled : new boolean[]{false, true}) {
                        backend.setBackdropCapture(enabled);
                        try (Surface source = backend.acquireCanvas().getSurface()) {
                            backend.recordingContext().flushAndSubmit(source, true);
                            int fbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                            assertEquals("capture source ownership follows the setting", enabled, fbo != 0);
                            backend.present();
                        }
                    }
                } finally {
                    backend.dispose();
                }
            }
        } finally {
            GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
            GL.setCapabilities(null);
            if (window != MemoryUtil.NULL) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static int[] framebufferSize(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, width, height);
            assertTrue("nonzero framebuffer", width.get(0) > 0 && height.get(0) > 0);
            return new int[]{width.get(0), height.get(0)};
        }
    }

    private static void assertPixels(Surface source, int width, int height, boolean glass) {
        try (Bitmap pixels = new Bitmap()) {
            assertTrue(pixels.allocPixels(ImageInfo.makeN32Premul(width, height)));
            assertTrue("GPU output must be readable", source.readPixels(pixels, 0, 0));
            assertEquals("top-left orientation/background", TOP, pixels.getColor(4, 4));
            assertEquals("bottom-left orientation/background", BOTTOM, pixels.getColor(4, height - 4));
            if (glass) {
                assertNotEquals("actual glass shader output, not a skipped/fallback panel", BOTTOM,
                        pixels.getColor(width / 2, height - 68));
            }
        }
    }

    private static final class GlassScene implements AutoCloseable {
        final QmlView view = QmlView.withStockTypes(new QmlEngine());
        final LiquidGlassNode node;

        GlassScene() {
            view.renderer().setPictureCache(false);
            view.load("""
                    import QtQuick
                    Item {
                        id: marker
                        width: 300; height: 64; x: 24
                        property int itemCount: 4
                        property int selectedIndex: 0
                        property real targetIndex: 0
                        property bool pointerDown: false
                        property real accumulatedDrag: 0
                        property real accentScale: 1
                        property color surfaceColor: "#FFF0F0F0"
                        property bool darkTheme: false
                        property var normalContent: normal
                        property var accentContent: accent
                        Item { id: normal; visible: false; width: marker.width - 8; height: 56 }
                        Item { id: accent; visible: false; width: marker.width - 8; height: 56 }
                    }
                    """);
            node = new LiquidGlassNode(view.root(), view.renderer(), true);
            node.parent.set(view.root());
            view.root().children.add(node);
        }

        void draw(Canvas canvas, int width, int height, boolean visible) {
            DirtyQueue queue = view.dirtyQueue();
            queue.install();
            try {
                Item marker = view.root();
                marker.width.set(width - 48);
                marker.y.set(height - 100);
                marker.visible.set(visible);
                node.width.set(width - 48);
                node.height.set(64);
                node.advance(System.nanoTime());
                queue.flush();
                view.renderer().render(canvas, marker);
            } finally {
                queue.uninstall();
            }
        }

        @Override
        public void close() {
            node.invalidateGpuContext();
            view.dispose();
        }
    }
}
