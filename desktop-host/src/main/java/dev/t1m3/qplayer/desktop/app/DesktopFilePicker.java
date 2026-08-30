package dev.t1m3.qplayer.desktop.app;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import dev.t1m3.qplayer.util.Logger;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Non-blocking host file chooser shared by all desktop builds. It deliberately
 * uses the pure-Java Swing chooser: FlatLaf's native Linux chooser loads GTK in a
 * separate native thread alongside GLFW and has been observed crashing in GTK.
 * The chooser is modal only on the AWT event thread, so GLFW keeps running.
 */
final class DesktopFilePicker {

    private static final AtomicBoolean OPEN = new AtomicBoolean(false);
    private static volatile boolean darkTheme;
    private static volatile Boolean installedDarkTheme;

    private DesktopFilePicker() {}

    /** Remember the theme without loading Swing/FlatLaf on the startup path. */
    static void initializeLookAndFeel(boolean dark) {
        darkTheme = dark;
    }

    /** The next chooser reflects an in-app light/dark-mode change. */
    static void setDarkTheme(boolean dark) {
        darkTheme = dark;
    }

    static void pickDirectory(String initialPath, Consumer<String> onPicked) {
        show("选择目录", initialDirectory(initialPath), true, null, null, onPicked);
    }

    static void pickImage(Consumer<String> onPicked) {
        File pictures = new File(System.getProperty("user.home", "."), "Pictures");
        show("选择歌单封面", initialDirectory(pictures.getAbsolutePath()), false,
                "图片文件 (*.png, *.jpg, *.webp, *.gif, *.bmp)",
                new String[]{"png", "jpg", "jpeg", "webp", "gif", "bmp"}, onPicked);
    }

    static void pickPlugin(Consumer<String> onPicked) {
        File downloads = new File(System.getProperty("user.home", "."), "Downloads");
        show("导入音源插件", initialDirectory(downloads.getAbsolutePath()), false,
                "QPlayer 插件 (*.qplug)", new String[]{"qplug"}, onPicked);
    }

    private static void show(String title, File initialDirectory, boolean directoryOnly,
                             String filterDescription, String[] extensions,
                             Consumer<String> onPicked) {
        if (!OPEN.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                applyLookAndFeel(darkTheme);
                JFileChooser chooser = new JFileChooser(initialDirectory);
                chooser.setDialogTitle(title);
                chooser.setMultiSelectionEnabled(false);
                if (directoryOnly) {
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                } else {
                    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    if (filterDescription != null && extensions != null) {
                        chooser.setFileFilter(new FileNameExtensionFilter(
                                filterDescription, extensions));
                    }
                }

                // The GLFW main window cannot be passed as an AWT owner. Bring
                // the JFileChooser-created dialog forward as soon as it becomes
                // visible instead of letting Windows place it behind QPlayer.
                chooser.addHierarchyListener(event -> {
                    if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0
                            || !chooser.isShowing()) return;
                    Window dialog = SwingUtilities.getWindowAncestor(chooser);
                    DesktopSwingFocus.requestForeground(dialog);
                });

                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    File selected = chooser.getSelectedFile();
                    if (selected != null) onPicked.accept(selected.getAbsolutePath());
                }
            } catch (Throwable t) {
                Logger.warn("desktop file chooser failed: {}", t.toString());
            } finally {
                OPEN.set(false);
            }
        });
    }

    private static void applyLookAndFeel(boolean dark) {
        if (installedDarkTheme != null && installedDarkTheme == dark) return;
        try {
            if (dark) FlatMacDarkLaf.setup();
            else FlatMacLightLaf.setup();
            installedDarkTheme = dark;
        } catch (Throwable t) {
            Logger.warn("install FlatLaf look and feel failed: {}", t.toString());
        }
    }

    private static File initialDirectory(String path) {
        if (path != null && !path.trim().isEmpty()) {
            File candidate = new File(path).getAbsoluteFile();
            if (candidate.isDirectory()) return candidate;
            File parent = candidate.getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return new File(System.getProperty("user.home", "."));
    }
}
