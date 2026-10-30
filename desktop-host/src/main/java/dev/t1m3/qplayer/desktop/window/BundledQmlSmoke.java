package dev.t1m3.qplayer.desktop.window;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.bridge.WindowChromeStub;
import dev.t1m3.qplayer.desktop.audio.DesktopAudioBackend;
import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.desktop.settings.JsonSettingsStore;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.qml.BundledQml;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import dev.t1m3.qplayer.store.AppDirs;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.humbleui.skija.Image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Non-windowed release smoke, shared by JVM tests and the actual native executable.
 * Uses real contexts/backend and an empty isolated profile; it never starts audio. */
public final class BundledQmlSmoke {
    private BundledQmlSmoke() { }

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        Path profile = Files.createTempDirectory("qplayer-build-smoke-");
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        PlayerController player = null;
        DesktopAudioBackend audio = new DesktopAudioBackend();
        try {
            AppDirs.setBase(profile.toString());
            AppDirs.setCacheBase(profile.resolve("cache").toString());
            player = new PlayerController(audio, track -> {
                throw new IllegalStateException("Build smoke must not request playback");
            });
            player.sourceSetupRequired.set(false);
            player.sourceSetupPending.set(false);
            SettingsCore settings = new SettingsCore();
            settings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            ClasspathResourceLoader resources = new ClasspathResourceLoader();
            // Decode a real packaged image through Skija's native path.
            try (Image icon = Image.makeFromEncoded(resources.load("app-icon.png"))) {
                require(icon.getWidth() > 0 && icon.getHeight() > 0, "Packaged application icon did not decode");
            }
            scene("Main.qml", player, settings, null, resources);
            // A reopened scene uses a new settings owner. Mutating the old owner
            // must not override the visible theme of the new scene.
            SettingsCore reopenedSettings = new SettingsCore();
            reopenedSettings.load(new JsonSettingsStore(), SettingsCatalog.DESKTOP);
            scene("Main.qml", player, reopenedSettings, settings, resources);
            scene("DesktopLyric.qml", player, reopenedSettings, null, resources);
            System.out.println("Bundled QML scene/binding/theme isolation smoke passed");
        } finally {
            try {
                if (player != null) player.shutdownAsync().join();
            } finally {
                audio.release();
                AppDirs.setBase(oldBase);
                AppDirs.setCacheBase(oldCache);
                try (Stream<Path> paths = Files.walk(profile)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

    private static void scene(String entry, PlayerController player, SettingsCore settings,
                              SettingsCore previousSettings, ClasspathResourceLoader resources) throws Exception {
        settings.setValue("darkMode", SettingsCatalog.MODE_LIGHT);
        try (BundledQml bundle = new BundledQml(entry)) {
            QmlView view = bundle.configure(QmlView.withStockTypes(new QmlEngine(bundle)), path -> {
                require(!path.endsWith(".qml"), "Runtime requested QML source: " + path);
                return resources.load(path);
            });
            try {
                view.context("player", player).context("settings", settings)
                        .context("i18n", I18n.instance()).context("hostWindow", new WindowChromeStub());
                DesktopLyricState lyric = new DesktopLyricState(null);
                view.context("desktopLyric", lyric);
                DesktopWindow.loadFonts(view, resources);
                Item root = bundle.load(view);
                require(root != null, "No packaged root: " + entry);
                settle(view);
                if ("Main.qml".equals(entry)) {
                    Rectangle surface = (Rectangle) root;
                    String light = surface.color.peek();
                    settings.setValue("darkMode", SettingsCatalog.MODE_DARK);
                    settle(view);
                    String dark = surface.color.peek();
                    require(!light.equalsIgnoreCase(dark), "Packaged theme binding did not change the visible surface");
                    if (previousSettings != null) {
                        previousSettings.setValue("darkMode", SettingsCatalog.MODE_LIGHT);
                        settle(view);
                        require(dark.equalsIgnoreCase(surface.color.peek()), "Disposed scene settings changed the reopened scene theme");
                        settings.setValue("darkMode", SettingsCatalog.MODE_LIGHT);
                        settle(view);
                        require(light.equals(surface.color.peek()), "Reopened scene did not restore its visible light theme");
                    }
                } else {
                    Item locked = view.findByObjectName("desktopLyricLockedControl");
                    require(locked != null && !Boolean.TRUE.equals(locked.visible.peek()), "Initial lyric lock state");
                    lyric.mousePassthrough.set(true);
                    settle(view);
                    require(Boolean.TRUE.equals(locked.visible.peek()), "Packaged lyric binding did not react");
                }
            } finally {
                view.dispose();
            }
        }
    }

    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            for (int frame = 0; frame < 3; frame++) {
                queue.flush();
                view.renderer().layoutOnly(view.root());
            }
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
