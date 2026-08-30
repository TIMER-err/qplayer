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
        PluginManifest manifest = pkg.manifest();
        Entry entry = state.plugins.get(manifest.id);
        if (entry == null) entry = new Entry();
        entry.id = manifest.id;
        entry.name = manifest.name;
        entry.activeVersion = manifest.version;
        entry.packageDigest = pkg.packageDigest();
        entry.signed = pkg.signed();
        entry.enabled = true;
        entry.grantedPermissions.clear();
        for (PluginPermission grant : grants) entry.grantedPermissions.add(grant.wireName());
        state.plugins.put(entry.id, entry);
        if (!state.providerOrder.contains(entry.id)) state.providerOrder.add(entry.id);
        if (state.primaryProvider.isEmpty()) state.primaryProvider = entry.id;
        save();
    }

    public synchronized void setEnabled(String pluginId, boolean enabled) throws IOException {
        Entry entry = required(pluginId);
        entry.enabled = enabled;
        save();
    }

    public synchronized void setPrimaryProvider(String pluginId) throws IOException {
        Entry entry = required(pluginId);
        if (!entry.enabled) throw new IllegalArgumentException("primary provider is disabled");
        state.primaryProvider = pluginId;
        save();
    }

    public synchronized void setProviderOrder(List<String> order) throws IOException {
        if (order == null) throw new IllegalArgumentException("order == null");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : order) {
            if (!state.plugins.containsKey(id)) throw new IllegalArgumentException("unknown plugin " + id);
            normalized.add(id);
        }
        for (String id : state.plugins.keySet()) normalized.add(id);
        state.providerOrder = new ArrayList<>(normalized);
        save();
    }

    public synchronized void remove(String pluginId) throws IOException {
        state.plugins.remove(pluginId);
        state.providerOrder.remove(pluginId);
        if (pluginId.equals(state.primaryProvider)) {
            state.primaryProvider = firstEnabled();
        }
        save();
    }

    synchronized void restore(Entry previous) throws IOException {
        if (previous == null) throw new IllegalArgumentException("previous == null");
        state.plugins.put(previous.id, previous.copy());
        if (!state.providerOrder.contains(previous.id)) state.providerOrder.add(previous.id);
        save();
    }

    private Entry required(String id) {
        Entry entry = state.plugins.get(id);
        if (entry == null) throw new IllegalArgumentException("unknown plugin " + id);
        return entry;
    }

    private String firstEnabled() {
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

    private void save() throws IOException {
        StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
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
