package dev.t1m3.qplayer.desktop.resources;

import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

/**
 * Resolves runtime fonts, images and JavaScript modules from the classpath or disk.
 * BundledQml supplies precompiled component classes and scene manifests separately.
 */
public final class ClasspathResourceLoader implements ResourceLoader {

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
