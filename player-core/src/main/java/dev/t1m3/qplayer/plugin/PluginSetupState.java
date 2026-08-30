package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists whether the user has already handled the source-plugin onboarding.
 * Removing the last plugin must leave a clear empty state, but must not force the
 * first-run dialog back onto users who deliberately chose local-only playback. */
public final class PluginSetupState {
    private static final int SCHEMA = 1;
    private final Gson gson = new Gson();
    private final Path file;

    public PluginSetupState() {
        this(AppDirs.configFile("source-setup.json"));
    }

    PluginSetupState(Path file) {
        if (file == null) throw new IllegalArgumentException("file == null");
        this.file = file;
    }

    public boolean acknowledged() {
        if (!Files.isRegularFile(file)) return false;
        try {
            State state = gson.fromJson(StorageFiles.readUtf8(file), State.class);
            return state != null && state.schemaVersion == SCHEMA && state.acknowledged;
        } catch (Exception error) {
            Logger.warn("source setup state could not be read: {}", error.getMessage());
            return false;
        }
    }

    public void acknowledge() {
        State state = new State();
        state.acknowledged = true;
        try {
            StorageFiles.writeUtf8Atomic(file, gson.toJson(state));
        } catch (IOException error) {
            Logger.warn("source setup state could not be saved: {}", error.getMessage());
        }
    }

    private static final class State {
        int schemaVersion = SCHEMA;
        boolean acknowledged;
    }
}
