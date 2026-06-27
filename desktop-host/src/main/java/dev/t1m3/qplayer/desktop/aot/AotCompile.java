package dev.t1m3.qplayer.desktop.aot;

import dev.t1m3.qplayer.desktop.*;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.MetadataReader;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.util.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Build-time AOT compiler. Wires the same engine/view the desktop host builds, but
 * with an {@link AotDumpBackend}, then runs one {@code view.load(Main.qml)} — which
 * compiles the whole reachable QML graph (root + pages + md3 components + inline
 * components + singletons) and writes every generated class to disk. Those classes
 * are then put on the classpath so {@link PrecompiledBackend} loads them at runtime
 * and GraalVM native-image can include them ahead of time.
 *
 * <p>Run with the desktop runtime classpath:
 * {@code java ... dev.t1m3.qplayer.desktop.aot.AotCompile <out-dir>}.
 */
public final class AotCompile {

    public static void main(String[] args) {
        Path outDir = Paths.get(args.length > 0 ? args[0]
                : System.getProperty("qplayer.aot.out", "target/aot-classes"));
        Logger.info("AOT: compiling QML → {}", outDir.toAbsolutePath());

        ResourceLoader resources = new ClasspathResourceLoader();
        Fonts.init(
                resources.load("fonts/PingFangSC-Thin.otf"),
                resources.load("fonts/PingFangSC-Light.otf"),
                resources.load("fonts/PingFangSC-Regular.otf"),
                resources.load("fonts/PingFangSC-Medium.otf")
        );
        Fonts.initIcon(resources.load("fonts/MaterialSymbolsRounded.ttf"));

        AudioBackend audio = new DesktopAudioBackend();
        MetadataReader reader = new DesktopMetadataReader();
        PlayerController controller = new PlayerController(audio, reader);
        controller.setColorExtractor(new DesktopColorExtractor());
        DesktopSettings settings = new DesktopSettings();
        settings.load();

        byte[] qmlBytes = resources.load("Main.qml");
        if (qmlBytes == null) throw new IllegalStateException("Main.qml not found on classpath");
        String qml = new String(qmlBytes, StandardCharsets.UTF_8);

        AotDumpBackend backend = new AotDumpBackend(outDir);
        QmlEngine engine = new QmlEngine(backend);
        QmlView view = QmlView.withStockTypes(engine).resources(resources);
        view.context("player", controller);
        view.context("settings", settings);
        DesktopWindow.loadFonts(view, resources);

        try {
            view.load(qml);
        } catch (Throwable t) {
            // Classes are dumped during compilation (before instantiation), so an
            // instantiation-time error still leaves a complete class set; surface it
            // but don't fail the dump.
            Logger.warn("AOT: view.load raised after compilation ({}): {}",
                    t.getClass().getSimpleName(), t.getMessage());
        }

        Logger.info("AOT: dumped {} classes", backend.dumpedCount());
        // Audio/AWT non-daemon threads would keep us alive; exit hard.
        Runtime.getRuntime().halt(0);
    }
}
