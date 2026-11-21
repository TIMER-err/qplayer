package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomic, versioned record of installed plugins and user grants. */
public final class PluginRegistry {
    private static final int SCHEMA = 1;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private State state;

    public PluginRegistry() {
        this(AppDirs.configFile("plugins.json"));
    }

    public PluginRegistry(Path file) {
        if (file == null) throw new IllegalArgumentException("file == null");
        this.file = file;
        this.state = load();
    }

    public synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : state.plugins.values()) result.add(entry.copy());
        return Collections.unmodifiableList(result);
    }

    public synchronized Entry get(String pluginId) {
        Entry entry = state.plugins.get(pluginId);
        return entry != null ? entry.copy() : null;
    }

    public synchronized String primaryProvider() { return state.primaryProvider; }

    public synchronized List<String> providerOrder() {
        return Collections.unmodifiableList(new ArrayList<>(state.providerOrder));
    }

    public synchronized void activate(VerifiedPluginPackage pkg, Set<PluginPermission> grants)
            throws IOException {
        State next = copyState();
        PluginManifest manifest = pkg.manifest();
        Entry entry = next.plugins.get(manifest.id);
        if (entry == null) entry = new Entry();
        entry.id = manifest.id;
        entry.name = manifest.name;
        entry.activeVersion = manifest.version;
        entry.packageDigest = pkg.packageDigest();
        entry.signed = pkg.signed();
        entry.enabled = true;
        entry.grantedPermissions.clear();
        for (PluginPermission grant : grants) entry.grantedPermissions.add(grant.wireName());
        next.plugins.put(entry.id, entry);
        if (!next.providerOrder.contains(entry.id)) next.providerOrder.add(entry.id);
        if (next.primaryProvider.isEmpty()) next.primaryProvider = entry.id;
        save(next);
    }

    public synchronized void setEnabled(String pluginId, boolean enabled) throws IOException {
        State next = copyState();
        required(next, pluginId).enabled = enabled;
        save(next);
    }

    public synchronized void setPrimaryProvider(String pluginId) throws IOException {
        State next = copyState();
        Entry entry = required(next, pluginId);
        if (!entry.enabled) throw new IllegalArgumentException("primary provider is disabled");
        next.primaryProvider = pluginId;
        save(next);
    }

    public synchronized void setProviderOrder(List<String> order) throws IOException {
        if (order == null) throw new IllegalArgumentException("order == null");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : order) {
            if (!state.plugins.containsKey(id)) throw new IllegalArgumentException("unknown plugin " + id);
            normalized.add(id);
        }
        for (String id : state.plugins.keySet()) normalized.add(id);
        State next = copyState();
        next.providerOrder = new ArrayList<>(normalized);
        save(next);
    }

    public synchronized void remove(String pluginId) throws IOException {
        State next = copyState();
        next.plugins.remove(pluginId);
        next.providerOrder.remove(pluginId);
        if (pluginId.equals(next.primaryProvider)) {
            next.primaryProvider = firstEnabled(next);
        }
        save(next);
    }

    static final class Snapshot {
        private final State state;
        private Snapshot(State state) { this.state = state; }
    }

    synchronized Snapshot snapshot() { return new Snapshot(copyState()); }

    synchronized void restore(Snapshot previous) throws IOException {
        if (previous == null) throw new IllegalArgumentException("previous == null");
        // Even if the storage device stops accepting writes, never publish a
        // pointer to a failed update in this process.
        try {
            save(previous.state);
        } finally {
            state = previous.state;
        }
    }

    private static Entry required(State state, String id) {
        Entry entry = state.plugins.get(id);
        if (entry == null) throw new IllegalArgumentException("unknown plugin " + id);
        return entry;
    }

    private static String firstEnabled(State state) {
        for (String id : state.providerOrder) {
            Entry entry = state.plugins.get(id);
            if (entry != null && entry.enabled) return id;
        }
        return "";
    }

    private State load() {
        if (!Files.isRegularFile(file)) return new State();
        try {
            State loaded = gson.fromJson(StorageFiles.readUtf8(file), State.class);
            if (loaded == null || loaded.schemaVersion != SCHEMA || loaded.plugins == null) {
                throw new IOException("unsupported plugin registry");
            }
            if (loaded.providerOrder == null) loaded.providerOrder = new ArrayList<>();
            if (loaded.primaryProvider == null) loaded.primaryProvider = "";
            for (Map.Entry<String, Entry> item : loaded.plugins.entrySet()) {
                if (item.getValue() == null || !item.getKey().equals(item.getValue().id)) {
                    throw new IOException("invalid plugin registry entry");
                }
                dev.t1m3.qplayer.media.MediaId.validateProvider(item.getKey());
                if (item.getValue().grantedPermissions == null) {
                    item.getValue().grantedPermissions = new LinkedHashSet<>();
                }
                item.getValue().permissionSet();
            }
            for (String id : loaded.providerOrder) {
                if (!loaded.plugins.containsKey(id)) throw new IOException("unknown provider in order");
            }
            if (!loaded.primaryProvider.isEmpty() && !loaded.plugins.containsKey(loaded.primaryProvider)) {
                throw new IOException("unknown primary provider");
            }
            return loaded;
        } catch (Exception error) {
            Logger.warn("plugin registry could not be read; starting disabled: {}", error.getMessage());
            preserveCorruptRegistry();
            return new State();
        }
    }

    private void preserveCorruptRegistry() {
        if (!Files.isRegularFile(file)) return;
        Path backup = file.resolveSibling(file.getFileName().toString() + ".corrupt-"
                + System.currentTimeMillis());
        try {
            Files.move(file, backup);
            Logger.warn("invalid plugin registry preserved as {}", backup.getFileName());
        } catch (IOException moveError) {
            Logger.warn("invalid plugin registry could not be preserved: {}", moveError.getMessage());
        }
    }

    private State copyState() {
        State copy = new State();
        copy.primaryProvider = state.primaryProvider;
        copy.providerOrder = new ArrayList<>(state.providerOrder);
        for (Map.Entry<String, Entry> item : state.plugins.entrySet()) {
            copy.plugins.put(item.getKey(), item.getValue().copy());
        }
        return copy;
    }

    private void save(State next) throws IOException {
        StorageFiles.writeUtf8Atomic(file, gson.toJson(next));
        state = next;
    }

    private static final class State {
        int schemaVersion = SCHEMA;
        String primaryProvider = "";
        List<String> providerOrder = new ArrayList<>();
        Map<String, Entry> plugins = new LinkedHashMap<>();
    }

    public static final class Entry {
        public String id = "";
        public String name = "";
        public String activeVersion = "";
        public String packageDigest = "";
        public boolean signed;
        public boolean enabled;
        public Set<String> grantedPermissions = new LinkedHashSet<>();

        public Set<PluginPermission> permissionSet() {
            Set<PluginPermission> result = new LinkedHashSet<>();
            for (String permission : grantedPermissions) {
                result.add(PluginPermission.fromWireName(permission));
            }
            return result;
        }

        Entry copy() {
            Entry copy = new Entry();
            copy.id = id;
            copy.name = name;
            copy.activeVersion = activeVersion;
            copy.packageDigest = packageDigest;
            copy.signed = signed;
            copy.enabled = enabled;
            copy.grantedPermissions = new LinkedHashSet<>(grantedPermissions);
            return copy;
        }
    }
}
