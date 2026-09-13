package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.util.Logger;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Optional submission marker, independent of QML state and renderer caches. */
final class FrameDiagnostics implements AutoCloseable {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 36;
    private final String backend;
    private final BufferedWriter log;
    private final Typeface typeface = FontMgr.getDefault().matchFamilyStyle("monospace", FontStyle.NORMAL);
    private final Font font = new Font(typeface, 14);
    private final Paint paint = new Paint();
    private final Bitmap readback;
    private long sequence;
    private long drawNanos;
    private long gpuSequence = -1;

    static FrameDiagnostics open(String backend, long window, boolean allowReadback) {
        String directory = System.getProperty("qplayer.frameTrace", "");
        if (directory.isBlank()) return null;
        try {
            Path root = Path.of(directory);
            Files.createDirectories(root);
            Path file = Files.createTempFile(root, backend + "-" + Long.toHexString(window) + "-", ".csv");
            BufferedWriter log = Files.newBufferedWriter(file);
            log.write("sequence,draw_ns,gpu_sequence,present_return_ns\n");
            log.flush();
            Logger.info("Frame diagnostics: {}", file.toAbsolutePath());
            return new FrameDiagnostics(backend, log,
                    allowReadback && Boolean.getBoolean("qplayer.frameReadback"));
        } catch (IOException e) {
            Logger.warn("Frame diagnostics unavailable: {}", e.toString());
            return null;
        }
    }

    private FrameDiagnostics(String backend, BufferedWriter log, boolean capture) {
        this.backend = backend;
        this.log = log;
        readback = capture ? new Bitmap() : null;
        if (readback != null) readback.allocN32Pixels(WIDTH, HEIGHT);
    }

    void draw(Surface surface) {
        ++sequence;
        drawNanos = System.nanoTime();
        gpuSequence = -1;
        Canvas canvas = surface.getCanvas();
        float x = Math.max(0, surface.getWidth() - WIDTH);
        canvas.save();
        canvas.resetMatrix();
        paint.setColor(0xFF000000);
        canvas.drawRect(Rect.makeXYWH(x, 0, WIDTH, HEIGHT), paint);
        paint.setColor(0xFF00E676);
        canvas.drawString("Frame " + sequence + " " + backend, x + 8, 16, font, paint);
        // 24 fixed cells, least significant bit first, readable from compressed video.
        for (int bit = 0; bit < 24; bit++) {
            paint.setColor((sequence & (1L << bit)) == 0 ? 0xFF000000 : 0xFFFFFFFF);
            canvas.drawRect(Rect.makeXYWH(x + 8 + bit * 10, 22, 10, 10), paint);
        }
        canvas.restore();
    }

    /** GL only: Skija readPixels can change a Vulkan image's tracked layout. */
    void readback(Surface surface) {
        if (readback == null) return;
        gpuSequence = -2;
        if (surface.getWidth() < WIDTH || surface.getHeight() < HEIGHT) return;
        if (!surface.readPixels(readback, surface.getWidth() - WIDTH, 0)) return;
        gpuSequence = 0;
        for (int bit = 0; bit < 24; bit++) {
            if ((readback.getColor(13 + bit * 10, 27) & 0xFF) > 127) gpuSequence |= 1L << bit;
        }
        if (gpuSequence != (sequence & 0xFFFFFF)) {
            Logger.warn("Frame marker mismatch: CPU {}, GPU {}", sequence, gpuSequence);
        }
    }

    void presented() {
        long returned = System.nanoTime();
        try {
            log.write(sequence + "," + drawNanos + "," + gpuSequence + "," + returned + "\n");
            if (sequence % 120 == 0) log.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write frame diagnostics", e);
        }
    }

    @Override
    public void close() {
        if (readback != null) readback.close();
        paint.close();
        font.close();
        if (typeface != null) typeface.close();
        try {
            log.close();
        } catch (IOException e) {
            Logger.warn("Cannot close frame diagnostics: {}", e.toString());
        }
    }
}
