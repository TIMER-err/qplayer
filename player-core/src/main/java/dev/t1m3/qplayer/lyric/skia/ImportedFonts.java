package dev.t1m3.qplayer.lyric.skia;

import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fonts the user handed to the app as a file, rather than ones the platform
 * reports as installed.
 *
 * <p>This is the answer to a font the OS will not share. A phone's system-wide
 * font change is done in the framework, by swapping the default typeface inside
 * every app's process; an app that draws its own text with Skia never sees it,
 * and the file behind it lives wherever that vendor's theme engine decided to
 * put it — usually somewhere an ordinary app has no permission to read, under a
 * different path on every OEM skin. There is no public API that hands over the
 * bytes. Importing the file sidesteps the whole question: it needs no
 * permission, works the same on every vendor and Android version, and is the
 * only approach that does not have to be re-guessed per ROM.
 *
 * <p>Imported files are copied into {@link AppDirs#fontsDir()} and indexed by
 * the family name inside the file, so they appear in the picker next to the
 * installed ones and resolve through the same {@link Fonts.FileIndex} path.
 */
public final class ImportedFonts implements Fonts.FileIndex {

    private static final ImportedFonts INSTANCE = new ImportedFonts();

    public static ImportedFonts instance() {
        return INSTANCE;
    }

    /** What a font file may be called, and the extensions worth offering in a
     *  file chooser. TTC/OTC collections are accepted but only their first face
     *  is read, which is all Skija's path-based loader exposes. */
    public static final String[] EXTENSIONS = {"ttf", "otf", "ttc", "otc"};

    /** A sane cap for a single font file. CJK faces are genuinely large (the
     *  bundled PingFang weights are ~13 MiB each), so this is deliberately
     *  generous; it only exists to stop an obviously wrong file. */
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final class Entry {
        final Path path;
        final String family;
        final int weight;

        Entry(Path path, String family, int weight) {
            this.path = path;
            this.family = family;
            this.weight = weight;
        }
    }

    // family (lower-cased) -> its faces, and the name to display for it.
    private final Map<String, List<Entry>> byFamily = new LinkedHashMap<>();
    private final Map<String, String> displayNames = new LinkedHashMap<>();
    private volatile String[] families = new String[0];
    private boolean loaded;

    private ImportedFonts() {
    }

    /** Read whatever is already in the font directory. Cheap — a user imports a
     *  handful of files, not a system font set — so it runs inline. */
    public synchronized void reload() {
        byFamily.clear();
        displayNames.clear();
        Path dir = AppDirs.fontsDir();
        FontMgr mgr = FontMgr.getDefault();
        if (mgr != null && Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
                for (Path file : (Iterable<Path>) entries::iterator) {
                    index(mgr, file);
                }
            } catch (Throwable t) {
                Logger.warn("reading imported fonts failed: {}", t.toString());
            }
        }
        families = displayNames.values().toArray(new String[0]);
        loaded = true;
    }

    private synchronized void ensureLoaded() {
        if (!loaded) reload();
    }

    /** Record one file under the family name declared inside it. */
    private void index(FontMgr mgr, Path file) {
        if (!Files.isRegularFile(file)) return;
        Typeface typeface = null;
        try {
            typeface = mgr.makeFromFile(file.toString());
            if (typeface == null) return;
            String family = typeface.getFamilyName();
            if (family == null || family.isEmpty()) return;
            String key = family.toLowerCase(Locale.ROOT);
            displayNames.putIfAbsent(key, family);
            byFamily.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Entry(file, family, typeface.getFontStyle().getWeight()));
        } catch (Throwable ignored) {
            // Not a font, or one Skija can't parse — ignore the file.
        } finally {
            if (typeface != null) {
                try { typeface.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Copy a picked font into the app's font directory and index it.
     *
     * @param bytes        the file's contents
     * @param suggestedName the original file name, used only for the stored name
     * @return the family name now available, or null when the bytes aren't a
     *         font this build can read (the caller reports that to the user —
     *         silently storing an unusable file would be worse)
     */
    public synchronized String importFont(byte[] bytes, String suggestedName) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        FontMgr mgr = FontMgr.getDefault();
        if (mgr == null) return null;
        // Parse before storing: an unreadable file must not end up in the
        // directory, where it would be re-parsed on every launch forever.
        String family;
        try (Data data = Data.makeFromBytes(bytes)) {
            Typeface parsed = mgr.makeFromData(data);
            if (parsed == null) return null;
            try {
                family = parsed.getFamilyName();
            } finally {
                try { parsed.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Logger.warn("imported font could not be parsed: {}", t.toString());
            return null;
        }
        if (family == null || family.isEmpty()) return null;
        try {
            Path dir = AppDirs.fontsDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName(family, suggestedName));
            Files.write(target, bytes);
            ensureLoaded();
            index(mgr, target);
            families = displayNames.values().toArray(new String[0]);
            Logger.info("imported font {} ({} KiB) as {}", family, bytes.length / 1024,
                    target.getFileName());
            return family;
        } catch (Throwable t) {
            Logger.warn("storing imported font failed: {}", t.toString());
            return null;
        }
    }

    /** A stable, filesystem-safe name: the family plus the original extension,
     *  so re-importing the same font overwrites rather than accumulates. */
    private static String fileName(String family, String suggestedName) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < family.length() && safe.length() < 48; i++) {
            char c = family.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            safe.append(ok ? c : '_');
        }
        if (safe.length() == 0) safe.append("font");
        return safe + "." + extensionOf(suggestedName);
    }

    private static String extensionOf(String suggestedName) {
        String lower = suggestedName == null ? "" : suggestedName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith('.' + extension)) return extension;
        }
        return "ttf";
    }

    @Override
    public String[] families() {
        ensureLoaded();
        return families.clone();
    }

    @Override
    public byte[] read(String family, int weight) {
        if (family == null || family.isEmpty()) return null;
        ensureLoaded();
        Path path;
        synchronized (this) {
            List<Entry> row = byFamily.get(family.toLowerCase(Locale.ROOT));
            if (row == null || row.isEmpty()) return null;
            Entry best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Entry entry : row) {
                int distance = Math.abs(entry.weight - weight);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entry;
                }
            }
            if (best == null) return null;
            path = best.path;
        }
        try {
            return Files.readAllBytes(path);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
