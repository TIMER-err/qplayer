package dev.t1m3.qplayer.desktop.audio;

import dev.t1m3.qplayer.desktop.app.DesktopNativeBrowser;
import dev.t1m3.qplayer.util.Logger;
import org.lwjgl.system.Platform;

/** CoreAudio can change the default twice while OpenAL still sees the same name. */
final class MacOutputRoute {
    private static boolean enabled = Platform.get() == Platform.MACOSX;

    // Called only by the OpenAL owner thread.
    static long generation() {
        if (!enabled) return 0;
        try {
            DesktopNativeBrowser.ensureNativeLibraryLoaded();
            long generation = defaultOutputGeneration();
            if (generation >= 0) return generation;
            Logger.warn("audio: CoreAudio default-output listener unavailable (OSStatus {})", generation);
        } catch (LinkageError | RuntimeException e) {
            Logger.warn("audio: CoreAudio default-output listener unavailable: {}", e);
        }
        enabled = false;
        return 0;
    }

    private static native long defaultOutputGeneration();

    private MacOutputRoute() {}
}
