package dev.t1m3.qplayer.desktop.window;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Standalone, opt-in GPU regression probe. Run in a fresh JVM with a real display;
 * for Vulkan, enable Khronos synchronization validation and inspect its output.
 * Does not start the player, open its profile, or initialize audio/network services.
 */
public final class BackendPresentationProbe {
    private BackendPresentationProbe() {}

    public static void main(String[] args) {
        run(args);
    }

    private static void run(String[] args) {
        if (args.length != 1 || !(args[0].equals("gl") || args[0].equals("vulkan"))) {
            throw new IllegalArgumentException("Expected gl or vulkan");
        }
        GlContextPolicy.prepareEnvironment();
        try (GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err)) {
            GLFW.glfwSetErrorCallback(callback);
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, switch (System.getProperty("qplayer.probe.platform", "auto")) {
                case "x11" -> GLFW.GLFW_PLATFORM_X11;
                case "wayland" -> GLFW.GLFW_PLATFORM_WAYLAND;
                default -> GLFW.GLFW_ANY_PLATFORM;
            });
            if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
            try {
                runWindow(args[0]);
            } finally {
                GLFW.glfwTerminate();
                GLFW.glfwSetErrorCallback(null);
            }
        }
        System.out.println("PROBE_CLEANUP_DONE");
    }

    private static void runWindow(String kind) {
        boolean vulkan = kind.equals("vulkan");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, vulkan ? GLFW.GLFW_NO_API : GLFW.GLFW_OPENGL_API);
        if (!vulkan) GlContextPolicy.configureWindow();
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        long window = GLFW.glfwCreateWindow(480, 240, "QPlayer presentation probe " + kind, 0, 0);
        if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
        GraphicsBackend backend = vulkan ? new VulkanBackend(window) : new GLBackend(window);
        int frames = Integer.getInteger("qplayer.probe.frames", 360);
        boolean resize = Boolean.parseBoolean(System.getProperty("qplayer.probe.resize", "true"));
        try (Paint paint = new Paint().setColor(0xFFFFFFFF);
             MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, w, h);
            backend.init(w.get(0), h.get(0));
            for (int frame = 0; frame < frames; frame++) {
                if (resize && frame > 0 && frame % 60 == 0) {
                    boolean large = (frame / 60) % 2 == 1;
                    GLFW.glfwSetWindowSize(window, large ? 640 : 480, large ? 320 : 240);
                }
                GLFW.glfwPollEvents();
                GLFW.glfwGetFramebufferSize(window, w, h);
                backend.resize(w.get(0), h.get(0));
                Canvas canvas = backend.acquireCanvas();
                // A repeating pattern has no visible end-to-start jump to confuse
                // with the intermittent backward frame under investigation.
                for (int x = (frame * 3) % 80 - 80; x < backend.width(); x += 80) {
                    canvas.drawRect(Rect.makeXYWH(x, 60, 20, 100), paint);
                }
                backend.present();
            }
            System.out.println("PROBE_FRAMES=" + frames);
        } finally {
            try {
                backend.dispose();
            } finally {
                GLFW.glfwDestroyWindow(window);
            }
        }
    }
}
