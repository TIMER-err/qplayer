package dev.t1m3.qplayer.android.app;

import android.os.Build;

import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.util.Logger;

import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The font files this app can actually open, indexed by family name.
 *
 * <p>Android's Skija font manager only knows what {@code /system/etc/fonts.xml}
 * declares, so anything an OEM skin or a theme engine installed elsewhere is
 * invisible to it — it isn't listed in the picker, and asking for it by name
 * gets Roboto back rather than nothing. Desktop has no equivalent problem
 * (DirectWrite/CoreText/Fontconfig index every installed family), which is why
 * this lives here and not in player-core.
 *
 * <p>The index doubles as the host's family-to-FILE lookup: qml4j's
 * {@code uiTypefaces} takes raw font bytes, not a Typeface, so following the
 * font setting for QML's own text needs the file behind the family either way —
 * the same job {@code DesktopWindow.findSystemFontFile} does with the Windows
 * registry, fontconfig, or a macOS directory scan.
 *
 * <p>Scanning parses every candidate file to read its real family name (a file
 * name says nothing about it), so it runs once, on a background thread, and
 * publishes to the picker when it finishes.
 */
public final class AndroidFontIndex implements Fonts.FileIndex {

    /** Every directory a normal app might be able to read fonts from. Ones that
     *  don't exist or aren't readable are skipped silently — this list is a
     *  superset across OEMs and Android versions, not a set of requirements. */
    private static final String[] FONT_DIRS = {
        "/system/fonts",
        "/system/font",
        "/system_ext/fonts",
        "/product/fonts",
        "/vendor/fonts",
        "/odm/fonts",
        // Updatable system fonts (API 31+).
        "/data/fonts",
        // MIUI/HyperOS extract an applied theme's fonts here. System-owned, so
        // whether an app can read them depends on the ROM's own permissions and
        // SELinux policy — worth trying, since the framework loads the very same
        // files from inside each app's process.
        "/data/system/theme/fonts",
        "/data/system/theme",
        "/data/theme/fonts",
        // The stock theme that ships on the system partition, always readable.
        "/system/media/theme/default/fonts",
    };

    private static final String[] EXTENSIONS = {".ttf", ".otf", ".ttc", ".otc"};
    /** Guard against a pathological directory; a device ships ~200 font files. */
    private static final int MAX_FILES = 600;

    private static final AndroidFontIndex INSTANCE = new AndroidFontIndex();

    public static AndroidFontIndex instance() {
        return INSTANCE;
    }

    /** One face: the file it came from and the weight it reports. */
    private static final class Face {
        final String path;
        final int weight;

        Face(String path, int weight) {
            this.path = path;
            this.weight = weight;
        }
    }

    // family (lower-cased) -> faces, and the display name to show for it.
    private final Map<String, List<Face>> faces = new LinkedHashMap<>();
    private final Map<String, String> displayNames = new LinkedHashMap<>();
    private volatile String[] families = new String[0];
    private volatile boolean scanned;
    private boolean scanning;

    private AndroidFontIndex() {
    }

    /**
     * Index the device's font files off the main thread, then hand the result to
     * {@code onReady} so the picker's list can be republished. Repeat calls after
     * the first scan invoke {@code onReady} immediately instead of rescanning —
     * installed fonts don't change while the process lives.
     */
    public void scanAsync(Runnable onReady) {
        synchronized (this) {
            if (scanned) {
                if (onReady != null) onReady.run();
                return;
            }
            if (scanning) return;
            scanning = true;
        }
        Thread worker = new Thread(() -> {
            try {
                scan();
            } catch (Throwable t) {
                Logger.warn("font index scan failed: {}", t);
            } finally {
                synchronized (AndroidFontIndex.this) {
                    scanned = true;
                    scanning = false;
                    AndroidFontIndex.this.notifyAll();
                }
            }
            if (onReady != null) {
                try {
                    onReady.run();
                } catch (Throwable ignored) {
                    // The caller's republish failing must not kill this thread.
                }
            }
        }, "qplayer-font-index");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void scan() {
        long startedAtNanos = System.nanoTime();
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return;
        Map<String, List<Face>> found = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        int parsed = 0;
        for (String path : candidateFiles()) {
            if (parsed >= MAX_FILES) break;
            Typeface typeface = null;
            try {
                typeface = mgr.makeFromFile(path);
                if (typeface == null) continue;
                String family = typeface.getFamilyName();
                if (family == null || family.isEmpty()) continue;
                parsed++;
                String key = family.toLowerCase(Locale.ROOT);
                names.putIfAbsent(key, family);
                List<Face> row = found.get(key);
                if (row == null) {
                    row = new ArrayList<>();
                    found.put(key, row);
                }
                row.add(new Face(path, typeface.getFontStyle().getWeight()));
            } catch (Throwable ignored) {
                // Unparseable, truncated, or permission-denied — just not a font
                // we can offer.
            } finally {
                // Only the path and weight are kept; the native face would
                // otherwise hold every scanned file mapped for the whole session.
                if (typeface != null) {
                    try { typeface.close(); } catch (Throwable ignored) { }
                }
            }
        }
        synchronized (this) {
            faces.clear();
            faces.putAll(found);
            displayNames.clear();
            displayNames.putAll(names);
            families = names.values().toArray(new String[0]);
        }
        Logger.info("font index: {} families from {} files in {} ms",
                families.length, parsed, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        // The picker shows family names, so a font that is present but listed
        // under an unexpected name is indistinguishable from a missing one
        // without this.
        Logger.info("font index: {}", String.join(", ", families));
    }

    /**
     * Font file paths to try, system-font-config entries first.
     *
     * <p>Logs one line per source. Which directories a given ROM actually lets an
     * app read is the whole question here — a theme engine's font directory is
     * system-owned, and whether that is reachable varies by OEM, Android version
     * and SELinux policy — so the log has to say what was found where rather than
     * just how many fonts turned up.
     */
    private static List<String> candidateFiles() {
        Set<String> paths = new LinkedHashSet<>();
        // The platform's own font config knows about updatable and OEM fonts that
        // are not necessarily in /system/fonts.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                int before = paths.size();
                for (android.graphics.fonts.Font font
                        : android.graphics.fonts.SystemFonts.getAvailableFonts()) {
                    File file = font.getFile();
                    if (file != null && file.isFile()) paths.add(file.getAbsolutePath());
                }
                Logger.info("font index: SystemFonts gave {} files", paths.size() - before);
            } catch (Throwable t) {
                Logger.info("font index: SystemFonts unavailable ({})", t);
                // Not fatal: the directory walk below covers the same ground on
                // any device where this API misbehaves.
            }
        }
        for (String dir : FONT_DIRS) {
            File directory = new File(dir);
            File[] files;
            try {
                if (!directory.exists()) continue; // nothing to report
                files = directory.listFiles();
            } catch (Throwable t) {
                Logger.info("font index: {} not readable ({})", dir, t);
                continue;
            }
            if (files == null) {
                // Exists but listFiles() returned null: no read permission, which
                // is the expected answer for a theme engine's own directory.
                Logger.info("font index: {} exists but is not listable", dir);
                continue;
            }
            int added = 0;
            for (File f : files) {
                if (f == null || !f.isFile() || !hasFontExtension(f.getName())) continue;
                if (paths.add(f.getAbsolutePath())) added++;
            }
            Logger.info("font index: {} -> {} font files ({} entries)", dir, added, files.length);
        }
        return new ArrayList<>(paths);
    }

    private static boolean hasFontExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    @Override
    public String[] families() {
        return families.clone();
    }

    @Override
    public byte[] read(String family, int weight) {
        if (family == null || family.isEmpty()) return null;
        String path;
        synchronized (this) {
            List<Face> row = faces.get(family.toLowerCase(Locale.ROOT));
            if (row == null || row.isEmpty()) return null;
            Face best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Face face : row) {
                int distance = Math.abs(face.weight - weight);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = face;
                }
            }
            if (best == null) return null;
            path = best.path;
        }
        try {
            return java.nio.file.Files.readAllBytes(new File(path).toPath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether the scan has finished, for callers that would rather wait than
     *  publish an empty list. */
    public boolean isReady() {
        return scanned;
    }

    /**
     * Block for at most {@code timeoutMs} until the scan finishes.
     *
     * <p>For the one caller that cannot proceed without the answer: the QML view
     * is built on the render thread and asks for the selected family's file
     * bytes exactly once, so losing the race against the scan would silently
     * leave the UI on the bundled font for that whole run. The scan starts as
     * early as the process does and is normally long finished by then; the
     * timeout only bounds a pathological device.
     *
     * @return true when the index is ready to be read
     */
    public boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (this) {
            // Nobody started a scan: waiting would just burn the whole timeout.
            if (!scanned && !scanning) return false;
            while (!scanned) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** The families found so far, for debugging/logging. */
    public List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(families)));
    }
}
