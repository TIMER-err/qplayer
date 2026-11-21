package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.store.AppDirs;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Bitmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User illustrations. IO/decode belongs to the worker; published images are
 * borrowed and retired only on the render thread. A failed version is attempted
 * once, including when the file is corrupt or unreadable. */
public final class TemperaImagePool {
    private static final long SCAN_INTERVAL_NS = 1_000_000_000L;
    private final Path dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "qplayer-tempera-images");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Entry> entries = new HashMap<>();
    private final ConcurrentLinkedQueue<Image> retired = new ConcurrentLinkedQueue<>();
    private volatile long revision;
    private volatile List<TemperaTypes.LayerImage> pool = java.util.Collections.emptyList();
    private long nextScanNs;
    private boolean scanning;
    private boolean disposed;

    private static final class Entry {
        final Path path;
        final long size;
        final java.nio.file.attribute.FileTime modified;
        final Object fileKey;
        final boolean readable;
        boolean attempted;
        Image image;

        Entry(Path path, BasicFileAttributes attrs) {
            this.path = path;
            this.size = attrs.size();
            this.modified = attrs.lastModifiedTime();
            this.fileKey = attrs.fileKey();
            this.readable = Files.isReadable(path);
        }

        boolean sameVersion(Entry other) {
            return path.equals(other.path) && size == other.size && modified.equals(other.modified)
                    && readable == other.readable && java.util.Objects.equals(fileKey, other.fileKey);
        }
    }

    public TemperaImagePool() {
        String base = AppDirs.base();
        dir = base == null || base.trim().isEmpty() ? null
                : java.nio.file.Paths.get(base).resolve("tempera").resolve("images");
    }

    public List<TemperaTypes.LayerImage> list() {
        drainRetired();
        requestScan();
        return pool;
    }

    public int size() { return pool.size(); }

    public long revision() {
        list();
        return revision;
    }
    /** No stat/read/decode on the caller. The returned image lives at least until
     * the next render-thread pool call; callers draw it immediately, not retain it. */
    public Image imageFor(String id) {
        drainRetired();
        requestScan();
        synchronized (this) {
            Entry entry = entries.get(id);
            if (disposed || entry == null) return null;
            if (!entry.attempted) {
                entry.attempted = true;
                worker.execute(() -> decode(id, entry));
            }
            return entry.image;
        }
    }

    public Path directory() { return dir; }

    private synchronized void requestScan() {
        long now = System.nanoTime();
        if (disposed || dir == null || scanning || now < nextScanNs) return;
        scanning = true;
        nextScanNs = now + SCAN_INTERVAL_NS;
        worker.execute(this::scan);
    }

    private void scan() {
        Map<String, Entry> found = new HashMap<>();
        try {
            if (Files.isDirectory(dir)) {
                try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path path : stream) {
                        String name = path.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (!(lower.endsWith(".png") || lower.endsWith(".jpg")
                                || lower.endsWith(".jpeg") || lower.endsWith(".webp"))) continue;
                        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                        if (attrs.isRegularFile()) found.put(name, new Entry(path.toAbsolutePath(), attrs));
                    }
                }
            }
        } catch (java.io.IOException | SecurityException e) {
            // A transient directory failure does not invalidate already published images.
            synchronized (this) { scanning = false; }
            return;
        }
        synchronized (this) {
            scanning = false;
            if (disposed) return;
            boolean changed = found.size() != entries.size();
            for (Map.Entry<String, Entry> old : entries.entrySet()) {
                Entry replacement = found.get(old.getKey());
                if (replacement != null && replacement.sameVersion(old.getValue())) {
                    found.put(old.getKey(), old.getValue());
                } else {
                    changed = true;
                    if (old.getValue().image != null) retired.add(old.getValue().image);
                }
            }
            for (String id : found.keySet()) {
                if (!entries.containsKey(id)) changed = true;
            }
            if (!changed) return;
            revision++;
            entries.clear();
            entries.putAll(found);
            List<String> names = new ArrayList<>(entries.keySet());
            names.sort(String::compareTo);
            List<TemperaTypes.LayerImage> images = new ArrayList<>(names.size());
            for (String name : names) {
                TemperaTypes.LayerImage image = new TemperaTypes.LayerImage();
                image.id = name;
                image.path = entries.get(name).path.toString();
                images.add(image);
            }
            pool = java.util.Collections.unmodifiableList(images);
        }
    }

    private void decode(String id, Entry entry) {
        Image decoded = null;
        try (Image encoded = Image.makeFromEncoded(Files.readAllBytes(entry.path));
             Bitmap pixels = new Bitmap()) {
            // Encoded Images can be lazy: force the actual codec work here,
            // then share immutable CPU pixels with the render-owned image.
            if (encoded != null && pixels.allocPixels(encoded.getImageInfo()) && encoded.readPixels(pixels)) {
                pixels.setImmutable();
                decoded = Image.makeRasterFromBitmap(pixels);
            }
        } catch (java.io.IOException | RuntimeException e) {
            // attempted remains true for this exact material version.
        }
        synchronized (this) {
            if (!disposed && entries.get(id) == entry) {
                entry.image = decoded;
                return;
            }
        }
        if (decoded != null) decoded.close(); // never published: worker still owns it
    }

    private void drainRetired() {
        Image image;
        while ((image = retired.poll()) != null) image.close();
    }

    public void dispose() {
        synchronized (this) {
            if (disposed) return;
            disposed = true;
            for (Entry entry : entries.values()) {
                if (entry.image != null) retired.add(entry.image);
            }
            entries.clear();
            pool = java.util.Collections.emptyList();
            worker.shutdownNow();
        }
        drainRetired();
    }
}
