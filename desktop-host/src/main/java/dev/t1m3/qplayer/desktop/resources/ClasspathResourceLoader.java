package dev.t1m3.qplayer.desktop.resources;

import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

/**
 * Resolves QML, fonts and assets off the classpath. The shared-qml tree
 * (Main.qml, the app pages, miuix/Core/*, fonts/*) is mounted as a Maven resource
 * directory, so {@code load("Main.qml")} reads {@code /Main.qml} and
 * {@code import miuix.Core} resolves {@code /miuix/Core/X.qml} — the same layout the
 * Android shell sees through its merged assets.
 */
public final class ClasspathResourceLoader implements ResourceLoader {

    /**
     * Fingerprints every packaged QML, qmldir and JavaScript module together with
     * the qml4j runtime. A changed resource or engine therefore selects a fresh
     * compiled-scene cache entry without relying on timestamps.
     */
    public String qmlFingerprint(String applicationVersion) {
        try {
            return QmlResourceFingerprint.create(applicationVersion);
        } catch (Exception error) {
            Logger.warn("QML cache disabled: cannot fingerprint resources ({})", error);
            return null;
        }
    }

    @Override
    public byte[] load(String source) {
        if (source == null) return null;

        // Disk-backed sources — the now-playing cover is cached under the user's
        // cache dir and handed to QML as an ABSOLUTE path (player.coverPath), which
        // isn't on the classpath. Read those from the filesystem; the shared-qml
        // tree (Main.qml, miuix/Core, fonts) is relative and stays on the classpath.
        // http(s) covers never reach here (the engine fetches those off-thread). On
        // Android the engine's loader already handles such paths — this closes the
        // gap on desktop, where otherwise the cover flashes (from coverUrl) then
        // vanishes the instant coverPath switches in.
        try {
            File f = source.startsWith("file:") ? new File(URI.create(source)) : new File(source);
            if (f.isAbsolute() && f.isFile()) {
                return Files.readAllBytes(f.toPath());
            }
        } catch (Exception ignored) {
            // not a readable file path — fall through to the classpath
        }

        String path = source.startsWith("/") ? source : "/" + source;
        try (InputStream in = ClasspathResourceLoader.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
