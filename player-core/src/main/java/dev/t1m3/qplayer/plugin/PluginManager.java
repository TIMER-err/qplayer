package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns enabled runtimes independently of any QML/render-thread lifecycle. */
public final class PluginManager implements AutoCloseable {
    private final Path pluginsRoot;
    private final PluginRegistry registry;
    private final PluginHostApi hostApi;
    private final Map<String, PluginRuntime> runtimes = new LinkedHashMap<>();
    private final Set<String> backgroundHandlers = ConcurrentHashMap.newKeySet();
    private final Set<String> backgroundBusy = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService background =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "qplayer-plugin-background");
                thread.setDaemon(true);
                return thread;
            });
    private final Gson gson = new Gson();

    public PluginManager(Path pluginsRoot, PluginRegistry registry, PluginHostApi hostApi) {
        this.pluginsRoot = pluginsRoot.toAbsolutePath().normalize();
        this.registry = registry;
        this.hostApi = hostApi;
        background.scheduleAtFixedRate(this::tickBackgroundPlugins,
                1L, 1L, TimeUnit.SECONDS);
    }

    public synchronized void startEnabled() {
        for (PluginRegistry.Entry entry : registry.entries()) {
            if (!entry.enabled || runtimes.containsKey(entry.id)) continue;
            try { start(entry); }
            catch (Exception error) {
                Logger.warn("plugin {} failed integrity/startup checks and was disabled: {}",
                        entry.id, error.getMessage());
                try { registry.setEnabled(entry.id, false); }
                catch (IOException saveError) {
                    Logger.warn("plugin {} disabled state could not be saved: {}",
                            entry.id, saveError.getMessage());
                }
            }
        }
    }

    public synchronized boolean enable(String pluginId) throws IOException {
        PluginRegistry.Entry entry = registry.get(pluginId);
        if (entry == null) throw new IllegalArgumentException("unknown plugin " + pluginId);
        PluginRuntime current = runtimes.get(pluginId);
        if (current != null && !entry.activeVersion.equals(current.manifest().version)) {
            runtimes.remove(pluginId);
            backgroundHandlers.remove(pluginId);
            backgroundBusy.remove(pluginId);
            current.close();
            if (hostApi instanceof PolicyAwarePluginHostApi) {
                ((PolicyAwarePluginHostApi) hostApi).unregister(pluginId);
            }
            current = null;
        }
        if (current == null) start(entry);
        registry.setEnabled(pluginId, true);
        return true;
    }

    /** Install/update and switch runtimes as one recoverable operation. A staged
     * package is already preflighted by the installer; if real-host startup still
     * fails, restore the previous registry entry and runtime before returning. */
    public synchronized void installAndEnable(PluginInstaller installer,
                                               VerifiedPluginPackage pkg,
                                               Set<PluginPermission> grants)
            throws IOException {
        if (installer == null || pkg == null || grants == null) {
            throw new IllegalArgumentException("installer, package and grants are required");
        }
        PluginRegistry.Entry previous = registry.get(pkg.manifest().id);
        installer.install(pkg, grants);
        try {
            enable(pkg.manifest().id);
            if (previous != null && !previous.enabled) disable(pkg.manifest().id);
        } catch (Throwable failure) {
            PluginRuntime failed = runtimes.remove(pkg.manifest().id);
            backgroundHandlers.remove(pkg.manifest().id);
            backgroundBusy.remove(pkg.manifest().id);
            if (failed != null) failed.close();
            if (hostApi instanceof PolicyAwarePluginHostApi) {
                ((PolicyAwarePluginHostApi) hostApi).unregister(pkg.manifest().id);
            }
            try {
                if (previous != null) {
                    registry.restore(previous);
                    if (previous.enabled) start(previous);
                } else {
                    registry.remove(pkg.manifest().id);
                }
                if (previous == null || !previous.activeVersion.equals(pkg.manifest().version)) {
                    Path failedVersion = pluginsRoot.resolve(pkg.manifest().id)
                            .resolve(pkg.manifest().version).normalize();
                    if (failedVersion.startsWith(pluginsRoot)) {
                        PluginInstaller.deleteTree(failedVersion);
                    }
                }
            } catch (Throwable rollback) {
                failure.addSuppressed(rollback);
            }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("plugin activation failed", failure);
        }
    }

    public synchronized void disable(String pluginId) throws IOException {
        PluginRuntime runtime = runtimes.remove(pluginId);
        backgroundHandlers.remove(pluginId);
        backgroundBusy.remove(pluginId);
        if (runtime != null) runtime.close();
        if (hostApi instanceof PolicyAwarePluginHostApi) {
            ((PolicyAwarePluginHostApi) hostApi).unregister(pluginId);
        }
        registry.setEnabled(pluginId, false);
    }

    /** Remove executable package files and registry state. Plugin-scoped settings
     * and encrypted credentials are retained so a reinstall can restore the account;
     * the plugin cannot access them while no runtime is active. */
    public synchronized void remove(String pluginId) throws IOException {
        PluginRegistry.Entry entry = registry.get(pluginId);
        if (entry == null) throw new IllegalArgumentException("unknown plugin " + pluginId);
        PluginRuntime runtime = runtimes.remove(pluginId);
        backgroundHandlers.remove(pluginId);
        backgroundBusy.remove(pluginId);
        if (runtime != null) runtime.close();
        if (hostApi instanceof PolicyAwarePluginHostApi) {
            ((PolicyAwarePluginHostApi) hostApi).unregister(pluginId);
        }
        registry.remove(pluginId);
        Path pluginRoot = pluginsRoot.resolve(pluginId).normalize();
        if (!pluginRoot.startsWith(pluginsRoot) || pluginRoot.equals(pluginsRoot)) {
            throw new IOException("plugin removal path is invalid");
        }
        try { PluginInstaller.deleteTree(pluginRoot); }
        catch (IOException error) {
            // Registry removal is authoritative. Leftover inert files cannot be
            // loaded and may be cleaned manually or by a later maintenance pass.
            Logger.warn("inactive plugin files could not be removed for {}: {}",
                    pluginId, error.getMessage());
        }
    }

    public synchronized List<PluginManifest> enabledProviders() {
        List<PluginManifest> result = new ArrayList<>();
        for (String id : registry.providerOrder()) {
            PluginRuntime runtime = runtimes.get(id);
            if (runtime != null) result.add(runtime.manifest());
        }
        return Collections.unmodifiableList(result);
    }

    public CompletableFuture<Object> invoke(String pluginId, String handler,
                                            Map<String, Object> arguments) {
        PluginRuntime runtime;
        synchronized (this) { runtime = runtimes.get(pluginId); }
        if (runtime == null) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("plugin is not enabled: " + pluginId));
            return failed;
        }
        return runtime.invoke(handler, arguments);
    }

    public synchronized List<PluginUiContributionRow> uiContributions() {
        List<PluginUiContributionRow> result = new ArrayList<>();
        for (PluginManifest manifest : enabledProviders()) {
            for (PluginManifest.UiContribution item : manifest.ui) {
                PluginUiContributionRow row = new PluginUiContributionRow();
                row.pluginId = manifest.id;
                row.pluginName = manifest.name;
                row.id = item.id;
                row.placement = item.placement;
                row.source = item.source;
                row.label = item.label == null || item.label.isEmpty()
                        ? manifest.name : item.label;
                row.icon = item.icon == null || item.icon.isEmpty() ? "extension" : item.icon;
                result.add(row);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized PluginUiSession openUiSession(String pluginId, String contributionId,
                                                       CorePluginHostApi coreHost)
            throws IOException {
        PluginRuntime runtime = runtimes.get(pluginId);
        if (runtime == null) throw new IOException("plugin is not enabled");
        PluginManifest.UiContribution selected = null;
        for (PluginManifest.UiContribution item : runtime.manifest().ui) {
            if (item.id.equals(contributionId)) { selected = item; break; }
        }
        if (selected == null) throw new IOException("plugin UI contribution does not exist");
        PluginUiContributionRow row = new PluginUiContributionRow();
        row.pluginId = runtime.manifest().id;
        row.pluginName = runtime.manifest().name;
        row.id = selected.id;
        row.placement = selected.placement;
        row.source = selected.source;
        row.label = selected.label == null || selected.label.isEmpty()
                ? runtime.manifest().name : selected.label;
        row.icon = selected.icon == null || selected.icon.isEmpty() ? "extension" : selected.icon;
        Path root = pluginsRoot.resolve(pluginId).resolve(runtime.manifest().version).normalize();
        if (!root.startsWith(pluginsRoot)) throw new IOException("plugin UI root is invalid");
        return new PluginUiSession(this, coreHost, root, row,
                runtime.manifest().permissionSet().contains(PluginPermission.CLIPBOARD));
    }

    private void start(PluginRegistry.Entry entry) throws IOException {
        Path root = pluginsRoot.resolve(entry.id).resolve(entry.activeVersion).normalize();
        if (!root.startsWith(pluginsRoot) || !Files.isDirectory(root)) {
            throw new IOException("active plugin files are missing");
        }
        verifyInstalledFiles(root, entry);
        Path manifestFile = root.resolve("plugin.json");
        PluginManifest manifest = gson.fromJson(StorageFiles.readUtf8(manifestFile), PluginManifest.class);
        if (manifest == null || !entry.id.equals(manifest.id)
                || !entry.activeVersion.equals(manifest.version)) {
            throw new IOException("installed plugin manifest does not match registry");
        }
        manifest.validate();
        PluginCompatibility.requireCompatible(manifest);
        if (!entry.permissionSet().containsAll(manifest.permissionSet())) {
            throw new SecurityException("plugin requests permissions that were not granted");
        }
        if (hostApi instanceof PolicyAwarePluginHostApi) {
            ((PolicyAwarePluginHostApi) hostApi).register(manifest);
        }
        try {
            PluginRuntime runtime = PluginRuntime.start(root, manifest, hostApi);
            runtimes.put(entry.id, runtime);
            if (manifest.permissionSet().contains(PluginPermission.BACKGROUND_TIMERS)) {
                try {
                    if (runtime.hasHandler("backgroundTick").get(5, TimeUnit.SECONDS)) {
                        backgroundHandlers.add(entry.id);
                    }
                } catch (Exception error) {
                    throw new IOException("plugin background handler check failed", error);
                }
            }
        } catch (IOException | RuntimeException error) {
            PluginRuntime failed = runtimes.remove(entry.id);
            backgroundHandlers.remove(entry.id);
            backgroundBusy.remove(entry.id);
            if (failed != null) failed.close();
            if (hostApi instanceof PolicyAwarePluginHostApi) {
                ((PolicyAwarePluginHostApi) hostApi).unregister(entry.id);
            }
            throw error;
        }
    }

    /** Re-check the signed/verified file set on every activation. Without this,
     * an added module or modified extracted script would execute after restart
     * even though the original .qplug passed verification. */
    private void verifyInstalledFiles(Path root, PluginRegistry.Entry entry) throws IOException {
        Path receiptFile = root.resolve(".qplayer-install.json");
        if (!Files.isRegularFile(receiptFile)) throw new IOException("plugin install receipt is missing");
        InstallReceipt receipt = gson.fromJson(StorageFiles.readUtf8(receiptFile), InstallReceipt.class);
        if (receipt == null || receipt.schemaVersion != 1 || receipt.hashes == null
                || !entry.packageDigest.equals(receipt.packageDigest) || receipt.hashes.isEmpty()) {
            throw new IOException("plugin install receipt is invalid");
        }
        Set<Path> expected = new LinkedHashSet<>();
        for (Map.Entry<String, String> item : receipt.hashes.entrySet()) {
            try { PluginManifest.requirePackagePath("installed plugin", item.getKey(), null); }
            catch (IllegalArgumentException error) { throw new IOException(error.getMessage(), error); }
            if (item.getValue() == null || !item.getValue().matches("[a-fA-F0-9]{64}")) {
                throw new IOException("plugin install receipt contains an invalid hash");
            }
            Path file = root.resolve(item.getKey()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)
                    || !hex(digestFile(file)).equals(item.getValue().toLowerCase(Locale.ROOT))) {
                throw new IOException("installed plugin integrity check failed: " + item.getKey());
            }
            expected.add(file);
        }
        expected.add(receiptFile);
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                if (!expected.contains(file.normalize())) {
                    throw new IOException("installed plugin contains an unverified file: "
                            + root.relativize(file));
                }
            }
        }
    }

    private static byte[] digestFile(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (GeneralSecurityException error) { throw new AssertionError(error); }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private void tickBackgroundPlugins() {
        for (String pluginId : new ArrayList<>(backgroundHandlers)) {
            if (!backgroundBusy.add(pluginId)) continue;
            invoke(pluginId, "backgroundTick", Collections.<String, Object>emptyMap())
                    .whenComplete((ignored, error) -> {
                        backgroundBusy.remove(pluginId);
                        if (error != null) Logger.warn("plugin {} background tick failed: {}",
                                pluginId, error.getMessage());
                    });
        }
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }

    private static final class InstallReceipt {
        int schemaVersion;
        String packageDigest = "";
        Map<String, String> hashes;
    }

    @Override public synchronized void close() {
        background.shutdownNow();
        backgroundHandlers.clear();
        backgroundBusy.clear();
        for (Map.Entry<String, PluginRuntime> item : runtimes.entrySet()) {
            item.getValue().close();
            if (hostApi instanceof PolicyAwarePluginHostApi) {
                ((PolicyAwarePluginHostApi) hostApi).unregister(item.getKey());
            }
        }
        runtimes.clear();
    }
}
