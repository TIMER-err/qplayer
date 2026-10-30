package dev.t1m3.qplayer.desktop.app;

import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Native system dialogs; no AWT toolkit or GTK is loaded into the GLFW process. */
final class DesktopFilePicker {
    private static final AtomicBoolean OPEN = new AtomicBoolean(false);
    private static final String[] PLAYLIST_EXTENSIONS = {"qpl", "json"};
    private static final String[] AUDIO_EXTENSIONS = {"mp3", "flac", "m4a", "ogg"};
    private static volatile Thread mainThread;
    private static volatile Consumer<Runnable> mainExec;

    private DesktopFilePicker() {}

    /** AppKit panels must be presented on the GLFW/AppKit main thread. */
    static void setMainLoop(Thread thread, Consumer<Runnable> exec) {
        mainThread = Objects.requireNonNull(thread);
        mainExec = Objects.requireNonNull(exec);
    }

    static void pickDirectory(String initialPath, Consumer<String> onPicked) {
        show(I18n.tr("settings.folder.choose"), initialDirectory(initialPath), true,
                null, null, null, null, onPicked);
    }

    static void pickImage(Consumer<String> onPicked) {
        show(I18n.tr("picker.playlistCover"), initialDirectory(homeSubdirectory("Pictures")), false,
                I18n.tr("picker.imageFiles"), new String[]{"png", "jpg", "jpeg", "webp", "gif", "bmp"},
                null, null, onPicked);
    }

    static void pickPlugin(Consumer<String> onPicked) {
        show(I18n.tr("picker.importPlugin"), initialDirectory(homeSubdirectory("Downloads")), false,
                I18n.tr("picker.pluginFiles"), new String[]{"qplug"}, null, null, onPicked);
    }

    static void pickFont(Consumer<String> onPicked) {
        show(I18n.tr("picker.importFont"), initialDirectory(homeSubdirectory("Downloads")), false,
                I18n.tr("picker.fontFiles"), dev.t1m3.qplayer.lyric.skia.ImportedFonts.EXTENSIONS,
                null, null, onPicked);
    }

    static void pickPlaylist(Consumer<String> onPicked) {
        show(I18n.tr("picker.importPlaylist"), initialDirectory(homeSubdirectory("Documents")), false,
                I18n.tr("picker.playlistFiles"), PLAYLIST_EXTENSIONS, null, null, onPicked);
    }

    static CompletableFuture<String> saveAudio(String suggestedFileName) {
        return show(I18n.tr("picker.saveAudio"), initialDirectory(homeSubdirectory("Documents")), false,
                I18n.tr("picker.audioFiles"), AUDIO_EXTENSIONS,
                suggestedFileName == null ? "" : suggestedFileName, ".mp3", ignored -> { });
    }

    static void savePlaylist(String suggestedFileName, Consumer<String> onPicked) {
        show(I18n.tr("picker.exportPlaylist"), initialDirectory(homeSubdirectory("Documents")), false,
                I18n.tr("picker.playlistFiles"), PLAYLIST_EXTENSIONS,
                suggestedFileName == null ? "" : suggestedFileName, ".qpl.json", onPicked);
    }

    private static CompletableFuture<String> show(String title, File directory, boolean directoryOnly,
                             String description, String[] extensions, String suggestedName,
                             String suffix, Consumer<String> onPicked) {
        Objects.requireNonNull(onPicked);
        CompletableFuture<String> result = new CompletableFuture<>();
        if (!OPEN.compareAndSet(false, true)) {
            result.complete(null);
            return result;
        }
        Runnable dialog = () -> {
            try {
                boolean save = suffix != null;
                String path;
                if (MacNativeDialog.isMac()) {
                    path = save
                            ? MacNativeDialog.save(title, directory.getAbsolutePath(), suggestedName, extensions)
                            : MacNativeDialog.open(title, directory.getAbsolutePath(), directoryOnly, extensions);
                } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                    path = WindowsFileDialog.show(title, directory.getAbsolutePath(), directoryOnly,
                            description, extensions, suggestedName, save);
                } else {
                    path = LinuxPortalFileDialog.show(title, directory.getAbsolutePath(), directoryOnly,
                            description, extensions, suggestedName, save);
                }
                // Other pickers retain accepted-only callbacks. The result also
                // reports cancellation so save callers cannot be left pending.
                if (path != null) {
                    if (save && !hasAnyExtension(path, extensions)) path += suffix;
                    onPicked.accept(path);
                }
                result.complete(path);
            } catch (Throwable error) {
                Logger.warn("desktop native file dialog failed: {}", error.toString());
                result.completeExceptionally(error);
            } finally {
                OPEN.set(false);
            }
        };
        try {
            if (MacNativeDialog.isMac()) {
                if (Thread.currentThread() == mainThread) dialog.run();
                else if (mainExec != null) mainExec.accept(dialog);
                else throw new IllegalStateException("AppKit file dialog main loop is not initialized");
            } else {
                // COM owns its STA and GIO owns its thread-default context. Neither blocks
                // GLFW's main loop or the render thread while the system dialog is open.
                Thread worker = new Thread(dialog, "qplayer-native-file-dialog");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable error) {
            OPEN.set(false);
            Logger.warn("desktop native file dialog dispatch failed: {}", error.toString());
            result.completeExceptionally(error);
        }
        return result;
    }

    private static boolean hasAnyExtension(String path, String[] extensions) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith('.' + extension)) return true;
        }
        return false;
    }

    private static String homeSubdirectory(String name) {
        return new File(System.getProperty("user.home", "."), name).getAbsolutePath();
    }

    private static File initialDirectory(String path) {
        if (path != null && !path.trim().isEmpty()) {
            File candidate = new File(path).getAbsoluteFile();
            if (candidate.isDirectory()) return candidate;
            File parent = candidate.getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return new File(System.getProperty("user.home", ".")).getAbsoluteFile();
    }
}
