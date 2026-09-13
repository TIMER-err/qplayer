package dev.t1m3.qplayer.desktop.window;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** GLFW/OpenGL-only comparison: never creates a Skija context or QML runtime. */
public final class RawGlPresentationProbe {
    private RawGlPresentationProbe() {}

    public static void main(String[] args) throws IOException {
        try (GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err)) {
            GLFW.glfwSetErrorCallback(callback);
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, switch (System.getProperty("qplayer.probe.platform", "auto")) {
                case "x11" -> GLFW.GLFW_PLATFORM_X11;
                case "wayland" -> GLFW.GLFW_PLATFORM_WAYLAND;
                default -> GLFW.GLFW_ANY_PLATFORM;
            });
            if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");
            try {
                runWindow();
            } finally {
                GLFW.glfwTerminate();
                GLFW.glfwSetErrorCallback(null);
            }
        }
        System.out.println("PROBE_CLEANUP_DONE");
    }

    private static void runWindow() throws IOException {
        String platform = System.getProperty("qplayer.probe.platform", "auto");
        String contextApi = System.getProperty("qplayer.probe.context", "native");
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API,
                contextApi.equals("egl") ? GLFW.GLFW_EGL_CONTEXT_API : GLFW.GLFW_NATIVE_CONTEXT_API);
        long window = GLFW.glfwCreateWindow(480, 240,
                "Raw OpenGL [" + platform + "/" + contextApi + "]: no Skija / no QML", 0, 0);
        if (window == 0) throw new IllegalStateException("glfwCreateWindow failed");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        GLFW.glfwSwapInterval(1);
        System.out.println("GL_RENDERER=" + GL11.glGetString(GL11.GL_RENDERER));
        System.out.println("GL_VERSION=" + GL11.glGetString(GL11.GL_VERSION));
        System.out.println("GLFW_PLATFORM=" + GLFW.glfwGetPlatform());
        System.out.println("GLFW_CONTEXT_CREATION_API=" + GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_CREATION_API));
        Path root = Path.of(System.getProperty("qplayer.frameTrace", "frame-traces"));
        Files.createDirectories(root);
        Path file = Files.createTempFile(root, "rawgl-", ".csv");
        int frames = Integer.getInteger("qplayer.probe.frames", 360);
        boolean capture = Boolean.getBoolean("qplayer.frameReadback");
        boolean resize = Boolean.parseBoolean(System.getProperty("qplayer.probe.resize", "true"));
        ByteBuffer pixels = MemoryUtil.memAlloc(640 * 4);
        try (BufferedWriter log = Files.newBufferedWriter(file);
             MemoryStack stack = MemoryStack.stackPush()) {
            log.write("sequence,draw_ns,gpu_sequence,present_return_ns,wrong_scene_pixels,focused\n");
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            for (int frame = 0; frame < frames; frame++) {
                if (resize && frame > 0 && frame % 60 == 0) {
                    boolean large = (frame / 60) % 2 == 1;
                    GLFW.glfwSetWindowSize(window, large ? 640 : 480, large ? 320 : 240);
                }
                GLFW.glfwPollEvents();
                boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
                GLFW.glfwGetFramebufferSize(window, w, h);
                int width = w.get(0), height = h.get(0);
                if (width * 4 > pixels.capacity()) {
                    MemoryUtil.memFree(pixels);
                    pixels = MemoryUtil.memAlloc(width * 4);
                }
                long sequence = frame + 1L, drawn = System.nanoTime();
                GL11.glViewport(0, 0, width, height);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glClearColor(0, 0, 0, 1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glClearColor(1, 1, 1, 1);
                int phase = (frame * 3) % 80;
                for (int x = phase - 80; x < width; x += 80) {
                    GL11.glScissor(x, height - 160, 20, 100);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
                int markerX = Math.max(0, width - 256) + 8;
                for (int bit = 0; bit < 24; bit++) {
                    if ((sequence & (1L << bit)) == 0) continue;
                    GL11.glScissor(markerX + bit * 10, height - 32, 10, 10);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
                long gpuSequence = -1;
                int wrong = -1;
                if (capture) {
                    GL11.glReadBuffer(GL11.GL_BACK);
                    pixels.clear();
                    GL11.glReadPixels(0, height - 80, width, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                    wrong = 0;
                    for (int x = 0; x < width; x++) {
                        boolean expected = Math.floorMod(x - phase, 80) < 20;
                        if (((pixels.get(x * 4) & 255) > 127) != expected) wrong++;
                    }
                    pixels.clear();
                    GL11.glReadPixels(0, height - 27, width, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                    gpuSequence = 0;
                    for (int bit = 0; bit < 24; bit++) {
                        if ((pixels.get((markerX + 5 + bit * 10) * 4) & 255) > 127) gpuSequence |= 1L << bit;
                    }
                }
                GLFW.glfwSwapBuffers(window);
                long returned = System.nanoTime();
                log.write(sequence + "," + drawn + "," + gpuSequence + "," + returned + "," + wrong + "," + focused + "\n");
                if (sequence % 120 == 0) log.flush();
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL error: " + error);
            System.out.println("PROBE_FRAMES=" + frames);
        } finally {
            MemoryUtil.memFree(pixels);
            GLFW.glfwMakeContextCurrent(0);
            GLFW.glfwDestroyWindow(window);
        }
    }
}
