package dev.t1m3.qplayer.desktop.tray;

import dev.t1m3.qplayer.desktop.window.DesktopWindow;
import dev.t1m3.qplayer.i18n.I18n;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Point;
import io.github.humbleui.types.RRect;
import java.util.function.BooleanSupplier;

/**
 * System-tray integration (the desktop analogue of the Android PlaybackService).
 *
 * <p>Uses native Win32, Linux StatusNotifierItem/AppIndicator and macOS
 * NSStatusItem backends. Unsupported platforms keep running windowed.
 *
 * <p>Tray callbacks and playback notifications may arrive on backend/worker
 * threads, so every player/window action funnels through the window's main-task
 * queue.
 */
public final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;
    /** Multi-size .ico for the Windows tray; see setIcoBytes. */
    private byte[] iconIco;
    private BooleanSupplier darkTheme;

    // Windows backend (non-null when active).
    private WinTray winTray;
    private Object winPlayPause;
    private Object winLyricToggle;

    // Linux backend (non-null when active).
    private LinuxTrayBackend linuxTray;
    private Object linuxPlayPause;
    private Object linuxLyricToggle;

    // macOS NSStatusItem backend (non-null when active).
    private MacTray macTray;
    private Object macPlayPause;
    private Object macLyricToggle;

    public TrayController(PlayerController controller, DesktopWindow win, byte[] iconPng) {
        this.controller = controller;
        this.win = win;
        this.iconPng = iconPng;
    }

    /** The app's multi-size .ico. Windows picks the entry matching the tray's own
     *  size from it, instead of shrinking one 256px image twice (LoadImage to the
     *  default icon size, then the shell to the notification-area size) — which
     *  visibly mushes fine detail. Ignored by the other backends, which take the
     *  PNG. Set before {@link #install}. */
    public void setIcoBytes(byte[] ico) {
        this.iconIco = ico;
    }

    /** Read at each menu open, so an in-app theme change applies without restart. */
    public void setDarkTheme(BooleanSupplier dark) {
        darkTheme = dark;
        if (winTray != null) winTray.setDarkTheme(dark);
    }

    /** Build the tray. Returns false (and logs) if no tray is available, in which
     *  case the app still runs windowed. */
    public boolean install() {
        if (isWindows()) return installWin();
        if (isLinux()) return installLinux();
        if (isMac()) return installMac();
        Logger.warn("system tray unavailable on this platform; tray menu disabled");
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("nux") || os.contains("nix");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    // ---------- Windows backend ----------
    private boolean installWin() {
        try {
            winTray = new WinTray();
            winTray.setDarkTheme(darkTheme);
            if (iconIco != null) winTray.setIconIco(iconIco);
            winTray.setIconPng(iconPng != null ? iconPng : placeholderPng());
            winTray.addItem(I18n.tr("tray.previous"), () -> win.postMainTask(controller::prev));
            winPlayPause = winTray.addItem(I18n.tr("tray.playPause"), () -> win.postMainTask(controller::toggle));
            winTray.addItem(I18n.tr("tray.next"), () -> win.postMainTask(controller::next));
            winTray.addSeparator();
            winLyricToggle = winTray.addItem(lyricToggleLabel(), () -> win.postMainTask(this::toggleLyricWindow));
            winTray.addItem(I18n.tr("tray.show"), () -> win.postMainTask(win::restoreFromTray));
            winTray.addItem(I18n.tr("tray.quit"), () -> win.postMainTask(() -> {
                shutdown();
                win.requestQuit();
            }));
            winTray.setLeftClickAction(() -> win.postMainTask(win::restoreFromTray));
            if (winTray.install()) return true;
            winTray = null;
            return false;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("Windows tray init failed:\n{}", sw);
            winTray = null;
            return false;
        }
    }

    // ---------- Linux backend (AppIndicator + GTK via JNA) ----------
    private boolean installLinux() {
        try {
            linuxTray = new LinuxStatusNotifierTray();
            configureLinuxTray(linuxTray);
            if (linuxTray.install()) return true;
            linuxTray.shutdown();

            Logger.warn("Linux StatusNotifierItem unavailable; falling back to AppIndicator");
            linuxTray = new LinuxTray();
            configureLinuxTray(linuxTray);
            if (linuxTray.install()) return true;
            linuxTray = null;
            return false;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("Linux tray init failed:\n{}", sw);
            linuxTray = null;
            return false;
        }
    }

    private void configureLinuxTray(LinuxTrayBackend tray) {
        tray.setIconPng(iconPng != null ? iconPng : placeholderPng());
        tray.setLeftClickAction(() -> win.postMainTask(win::restoreFromTray));
        tray.addItem(I18n.tr("tray.previous"), () -> win.postMainTask(controller::prev));
        linuxPlayPause = tray.addItem(I18n.tr("tray.playPause"),
                () -> win.postMainTask(controller::toggle));
        tray.addItem(I18n.tr("tray.next"), () -> win.postMainTask(controller::next));
        tray.addSeparator();
        linuxLyricToggle = tray.addItem(lyricToggleLabel(),
                () -> win.postMainTask(this::toggleLyricWindow));
        tray.addItem(I18n.tr("tray.show"), () -> win.postMainTask(win::restoreFromTray));
        tray.addItem(I18n.tr("tray.quit"), () -> win.postMainTask(() -> {
            shutdown();
            win.requestQuit();
        }));
    }

    // ---------- macOS backend (NSStatusItem via JNA; no AWT) ----------
    private boolean installMac() {
        try {
            macTray = new MacTray();
            macTray.setIconPng(iconPng != null ? iconPng : placeholderPng());
            macTray.addItem(I18n.tr("tray.previous"), () -> win.postMainTask(controller::prev));
            macPlayPause = macTray.addItem(I18n.tr("tray.playPause"),
                    () -> win.postMainTask(controller::toggle));
            macTray.addItem(I18n.tr("tray.next"), () -> win.postMainTask(controller::next));
            macTray.addSeparator();
            macLyricToggle = macTray.addItem(lyricToggleLabel(),
                    () -> win.postMainTask(this::toggleLyricWindow));
            macTray.addItem(I18n.tr("tray.show"), () -> win.postMainTask(win::restoreFromTray));
            macTray.addItem(I18n.tr("tray.quit"), () -> win.postMainTask(() -> {
                shutdown();
                win.requestQuit();
            }));
            if (macTray.install()) return true;
            macTray = null;
            return false;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("macOS tray init failed:\n{}", sw);
            macTray = null;
            return false;
        }
    }

    /** Main thread (posted by every caller above) -- toggles the desktop
     *  lyric window (issue #25) and refreshes this item's own label
     *  immediately, same as play/pause does for its own label via {@link
     *  #onPlaybackChanged}/{@link #refresh} (nothing else drives a refresh
     *  right after a lyric-window toggle specifically). */
    private void toggleLyricWindow() {
        dev.t1m3.qplayer.desktop.window.DesktopLyricWindow lw = win.lyricWindow();
        if (lw == null) return;
        lw.toggle();
        refreshLyricLabel();
    }

    /**
     * The native lyric window can change from QML, its own close button, or the
     * tray. Marshal its actual state onto the GLFW main loop; this path remains
     * active even when the main QML/render thread has been destroyed in tray mode.
     */
    public void onDesktopLyricChanged() {
        win.postMainTask(this::refreshLyricLabel);
    }

    private void refreshLyricLabel() {
        String label = lyricToggleLabel();
        if (winTray != null) winTray.setLabel(winLyricToggle, label);
        else if (linuxTray != null) linuxTray.setLabel(linuxLyricToggle, label);
        else if (macTray != null) macTray.setLabel(macLyricToggle, label);
    }

    private String lyricToggleLabel() {
        dev.t1m3.qplayer.desktop.window.DesktopLyricWindow lw = win.lyricWindow();
        boolean on = lw != null && lw.isEnabled();
        return I18n.tr(on ? "tray.lyric.off" : "tray.lyric.on");
    }

    @Override
    public void onPlaybackChanged() {
        // May arrive on the audio/worker thread — marshal to the main loop.
        win.postMainTask(this::refresh);
    }

    private void refresh() {
        try {
            String pp = I18n.tr(controller.isPlaying() ? "tray.pause" : "tray.play");
            Object title = controller.title.peek();
            Object artist = controller.artist.peek();
            String tip = title == null ? "QPlayer"
                    : (artist != null ? artist + " — " + title : String.valueOf(title));
            // Win32 szTip holds 128 UTF-16 characters; keep all backends well under.
            if (tip.length() > 64) tip = tip.substring(0, 63) + "…";

            if (winTray != null) {
                winTray.setLabel(winPlayPause, pp);
                winTray.setTooltip(tip);
            } else if (linuxTray != null) {
                linuxTray.setLabel(linuxPlayPause, pp);
                linuxTray.setTooltip(tip);
            } else if (macTray != null) {
                macTray.setLabel(macPlayPause, pp);
                macTray.setTooltip(tip);
            }
        } catch (Throwable t) {
            Logger.warn("tray refresh failed: {}", t);
        }
    }

    public void shutdown() {
        if (winTray != null) {
            try { winTray.shutdown(); } catch (Throwable ignored) {}
            winTray = null;
        }
        if (linuxTray != null) {
            try { linuxTray.shutdown(); } catch (Throwable ignored) {}
            linuxTray = null;
        }
        if (macTray != null) {
            try { macTray.shutdown(); } catch (Throwable ignored) {}
            macTray = null;
        }
    }

    /** Encode the generated placeholder to PNG bytes (icon resource is absent). */
    private static byte[] placeholderPng() {
        try (Surface surface = Surface.makeRasterN32Premul(64, 64);
             Paint paint = new Paint().setAntiAlias(true);
             Path triangle = Path.makePolygon(new Point[]{
                     new Point(24, 18), new Point(24, 46), new Point(46, 32)}, true)) {
            Canvas canvas = surface.getCanvas(); // Borrowed; the surface owns it.
            canvas.clear(0);
            canvas.drawRRect(RRect.makeXYWH(2, 2, 60, 60, 9), paint.setColor(0xff6750a4));
            canvas.drawPath(triangle, paint.setColor(0xffffffff));
            try (Image image = surface.makeImageSnapshot();
                 Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                if (data == null) throw new IllegalStateException("PNG encoding failed");
                return data.getBytes();
            }
        }
    }
}
