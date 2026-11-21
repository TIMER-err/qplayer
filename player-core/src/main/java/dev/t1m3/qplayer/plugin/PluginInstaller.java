package dev.t1m3.qplayer.plugin;

import com.google.gson.GsonBuilder;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Staged, atomic installation of an already verified plugin package. */
public final class PluginInstaller {
    private final Path pluginsRoot;
    private final PluginRegistry registry;

    public PluginInstaller(PluginRegistry registry) {
        this(AppDirs.pluginsDir(), registry);
    }

    public PluginInstaller(Path pluginsRoot, PluginRegistry registry) {
        if (pluginsRoot == null || registry == null) throw new IllegalArgumentException();
        this.pluginsRoot = pluginsRoot.toAbsolutePath().normalize();
        this.registry = registry;
    }

    public Path install(VerifiedPluginPackage pkg, Set<PluginPermission> approvedPermissions)
            throws IOException {
        if (pkg == null || approvedPermissions == null) throw new IllegalArgumentException();
        PluginManifest manifest = pkg.manifest();
        if (!approvedPermissions.containsAll(manifest.permissionSet())) {
            throw new SecurityException("not every requested plugin permission was approved");
        }
        Path pluginRoot = pluginsRoot.resolve(manifest.id).normalize();
        Path target = pluginRoot.resolve(manifest.version).normalize();
        requireChild(pluginRoot, target);
        PluginRegistry.Entry existing = registry.get(manifest.id);
        if (Files.exists(target)) {
            if (existing != null && manifest.version.equals(existing.activeVersion)
                    && pkg.packageDigest().equals(existing.packageDigest)) {
                registry.activate(pkg, approvedPermissions);
                return target;
            }
            throw new IOException("a different package already uses plugin version " + manifest.version);
        }
        Files.createDirectories(pluginRoot);
        Path stagingRoot = pluginsRoot.resolve(".staging");
        Files.createDirectories(stagingRoot);
        Path staging = stagingRoot.resolve(manifest.id + "-" + UUID.randomUUID()).normalize();
        requireChild(stagingRoot, staging);
        Files.createDirectories(staging);
        try {
            extract(pkg, staging);
            preflight(staging, manifest);
            String receipt = new GsonBuilder().setPrettyPrinting().create().toJson(new Receipt(pkg));
            StorageFiles.writeUtf8Atomic(staging.resolve(".qplayer-install.json"), receipt);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                Files.move(staging, target);
            }
            try {
                registry.activate(pkg, approvedPermissions);
            } catch (IOException error) {
                deleteTree(target);
                throw error;
            }
            return target;
        } finally {
            deleteTree(staging);
        }
    }

    /** Parse and initialize the exact staged JavaScript before it can become the
     * active version. Host calls are denied during preflight, so installation
     * cannot perform network, storage, credential, or playback side effects. */
    private static void preflight(Path root, PluginManifest manifest) throws IOException {
        PluginHostApi deniedHost = (pluginId, method, arguments) -> {
            java.util.concurrent.CompletableFuture<Object> denied =
                    new java.util.concurrent.CompletableFuture<>();
            denied.completeExceptionally(new SecurityException(
                    "host calls are disabled during plugin preflight"));
            return denied;
        };
        try (PluginRuntime ignored = PluginRuntime.start(root, manifest, deniedHost)) {
            // PluginRuntime validates the exported handler table during startup.
        }
    }

    private static void extract(VerifiedPluginPackage pkg, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(pkg.file().toFile())) {
            for (String name : pkg.hashes().keySet()) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) throw new IOException("verified plugin entry disappeared: " + name);
                Path output = destination.resolve(name).normalize();
                requireChild(destination, output);
                if (output.getParent() != null) Files.createDirectories(output.getParent());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, output);
                }
            }
        }
    }

    private static void requireChild(Path root, Path child) throws IOException {
        if (!child.startsWith(root) || child.equals(root)) throw new IOException("path escapes plugin root");
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        paths.sort(Comparator.reverseOrder());
        IOException failure = null;
        for (Path path : paths) {
            try { Files.deleteIfExists(path); }
            catch (IOException error) { if (failure == null) failure = error; }
        }
        if (failure != null) throw failure;
    }

    private static final class Receipt {
        final int schemaVersion = 1;
        final String packageDigest;
        final boolean signed;
        final Map<String, String> hashes;
        Receipt(VerifiedPluginPackage pkg) {
            packageDigest = pkg.packageDigest();
            signed = pkg.signed();
            hashes = new LinkedHashMap<>(pkg.hashes());
        }
    }
}
