package dev.t1m3.qplayer.desktop.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import dev.t1m3.qplayer.settings.SettingsStore;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;

/**
 * Desktop half of the settings framework: a JSON file at
 * {@code <AppDirs.base()>/config/settings.json}. Everything else about settings —
 * which ones exist, their defaults, what they do, how they're rendered — lives
 * in {@code player-core}'s {@code dev.t1m3.qplayer.settings}, shared with
 * Android (which stores the same keys in SharedPreferences).
 */
public final class JsonSettingsStore implements SettingsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final JsonObject store;

    public JsonSettingsStore() {
        this.file = AppDirs.configFile("settings.json").toFile();
        this.store = read(file);
    }

    @Override
    public synchronized boolean getBool(String key, boolean def) {
        try {
            return store.has(key) ? store.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public synchronized int getInt(String key, int def) {
        try {
            return store.has(key) ? store.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public synchronized String getString(String key, String def) {
        try {
            return store.has(key) ? store.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public synchronized boolean has(String key) {
        return store.has(key);
    }

    @Override
    public synchronized void putBool(String key, boolean value) {
        store.addProperty(key, value);
        persist();
    }

    @Override
    public synchronized void putInt(String key, int value) {
        store.addProperty(key, value);
        persist();
    }

    public synchronized void putInts(String key1, int value1, String key2, int value2) {
        store.addProperty(key1, value1);
        store.addProperty(key2, value2);
        persist();
    }

    @Override
    public synchronized void putString(String key, String value) {
        store.addProperty(key, value);
        persist();
    }

    private static JsonObject read(File f) {
        try {
            if (f.isFile()) {
                String json = StorageFiles.readUtf8(f.toPath());
                JsonObject o = GSON.fromJson(json, JsonObject.class);
                if (o != null) return o;
            }
        } catch (Exception e) {
            Logger.warn("settings read failed: {}", e);
        }
        return new JsonObject();
    }

    // Called with this instance's monitor held through serialization and atomic replacement.
    private void persist() {
        try {
            StorageFiles.writeUtf8Atomic(file.toPath(), GSON.toJson(store));
        } catch (Exception e) {
            Logger.warn("settings write failed: {}", e);
        }
    }
}
