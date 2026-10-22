package dev.t1m3.qplayer.desktop.tray;

import dev.t1m3.qplayer.desktop.window.DesktopWindow;
import dev.t1m3.qplayer.i18n.I18n;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

/**
 * System-tray integration (the desktop analogue of the Android PlaybackService).
 *
 * <p>Two backends:
 * <ul>
 *   <li><b>Windows</b> ({@link WinTray}): Shell_NotifyIcon + a native Win32 popup
 *       menu via JNA. The menu is rendered by the OS, so CJK works with zero
 *       {@code java.awt} font-manager init — which dies in the native image
 *       ({@code sun.awt.FontConfiguration} NPEs with no JDK lib dir).
 *   <li><b>Linux / macOS</b>: AWT {@link TrayIcon} + a Swing {@link javax.swing.JPopupMenu}
 *       (Java2D-drawn, so setFont controls the CJK font). Works on the JVM; the
 *       native backends for these platforms are a follow-up.
 * </ul>
 *
 * <p>Threading: tray callbacks arrive on a backend thread (the Win32 pump thread
 * or AWT's EDT) and {@link PlayerController.PlaybackListener#onPlaybackChanged} may
 * fire from the audio/worker threads, so every action funnels through the window's
 * main-task queue — the same queue playback control runs on.
 */
public final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;
    /** Multi-size .ico for the Windows tray; see setIcoBytes. */
    private byte[] iconIco;

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

    // AWT backend (non-Windows).
    private TrayIcon trayIcon;
    private javax.swing.JPopupMenu popup;
    private javax.swing.JDialog popupAnchor;
    private javax.swing.JMenuItem playPause;
    private javax.swing.JMenuItem lyricToggle;
    private Font menuFont;

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

    /** Build the tray. Returns false (and logs) if no tray is available, in which
     *  case the app still runs windowed. */
    public boolean install() {
        if (isWindows()) return installWin();
        if (isLinux()) return installLinux();
        if (isMac()) return installMac();
        return installAwt();
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

    // ---------- AWT backend (headful leftover; unused on packaged macOS) ----------
    private boolean installAwt() {
        if (!SystemTray.isSupported()) {
            Logger.warn("system tray not supported; tray menu disabled");
            return false;
        }
        try {
            File icon = iconFile();
            Image img = (icon != null) ? ImageIO.read(icon) : placeholder();
            menuFont = pickCjkFont();

            popup = new javax.swing.JPopupMenu();
            popup.add(swingItem(I18n.tr("tray.previous"), controller::prev));
            playPause = swingItem(I18n.tr("tray.playPause"), controller::toggle);
            popup.add(playPause);
            popup.add(swingItem(I18n.tr("tray.next"), controller::next));
            popup.addSeparator();
            lyricToggle = swingItem(lyricToggleLabel(), this::toggleLyricWindow);
            popup.add(lyricToggle);
            popup.add(swingItem(I18n.tr("tray.show"), win::restoreFromTray));
            popup.add(swingItem(I18n.tr("tray.quit"), () -> {
                shutdown();
                win.requestQuit();
            }));

            // JPopupMenu needs a parent component to anchor against; a tiny
            // undecorated always-on-top dialog placed at the click point doubles
            // as that anchor and as the focus owner (the popup auto-dismisses
            // when this anchor loses focus).
            popupAnchor = new javax.swing.JDialog();
            popupAnchor.setUndecorated(true);
            popupAnchor.setAlwaysOnTop(true);
            popupAnchor.setSize(1, 1);
            popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    if (popupAnchor != null) popupAnchor.setVisible(false);
                }
            });

            trayIcon = new TrayIcon(img, "QPlayer");
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger()) {
                        win.postMainTask(win::restoreFromTray);
                    } else {
                        maybeShowPopup(e);
                    }
                }

                private void maybeShowPopup(MouseEvent e) {
                    if ((e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e))
                            && (popup == null || !popup.isVisible())) {
                        showPopupAt(e.getX(), e.getY());
                    }
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
            Logger.info("system tray initialized: AWT (font={})",
                    menuFont != null ? menuFont.getFamily() : "default");
            return true;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("system tray init failed:\n{}", sw);
            trayIcon = null;
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
        else if (lyricToggle != null) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (lyricToggle != null) lyricToggle.setText(label);
            });
        }
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
            // Tooltips: AWT caps at 127 chars on Windows, Win32 szTip at 128; trim well under.
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
            } else if (trayIcon != null) {
                String tipF = tip;
                if (playPause != null) {
                    javax.swing.SwingUtilities.invokeLater(() -> playPause.setText(pp));
                }
                trayIcon.setToolTip(tipF);
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
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); } catch (Throwable ignored) {}
            trayIcon = null;
        }
        if (popupAnchor != null) {
            try { popupAnchor.dispose(); } catch (Throwable ignored) {}
            popupAnchor = null;
        }
    }

    private void showPopupAt(int screenX, int screenY) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (popup == null || popupAnchor == null) return;
            Dimension pref = popup.getPreferredSize();
            Rectangle screen = boundsContaining(screenX, screenY);
            // Snap the popup's bottom-right corner to the click point; clamping
            // keeps it on-screen for any panel position.
            int x = screenX - pref.width;
            int y = screenY - pref.height;
            if (x < screen.x) x = screen.x;
            if (y < screen.y) y = screen.y;
            if (x + pref.width > screen.x + screen.width) x = screen.x + screen.width - pref.width;
            if (y + pref.height > screen.y + screen.height) y = screen.y + screen.height - pref.height;
            popupAnchor.setLocation(x, y);
            popupAnchor.setVisible(true);
            popupAnchor.toFront();
            popup.show(popupAnchor.getContentPane(), 0, 0);
        });
    }

    private static Rectangle boundsContaining(int px, int py) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            if (b.contains(px, py)) return b;
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private javax.swing.JMenuItem swingItem(String label, Runnable action) {
        javax.swing.JMenuItem mi = new javax.swing.JMenuItem(label);
        if (menuFont != null) mi.setFont(menuFont);
        mi.addActionListener(e -> win.postMainTask(action));
        return mi;
    }

    /** Pin a CJK family; macOS default (Helvetica Neue) lacks CJK glyphs and falls
     *  through to the JDK's logical-font chain, which renders tofu on stripped /
     *  non-fontconfig JDKs. */
    private static Font pickCjkFont() {
        String[] candidates = {
                "PingFang SC", "Hiragino Sans GB",         // macOS
                "Noto Sans CJK SC", "WenQuanYi Micro Hei", // Linux
                "SimSun", "SimHei"
        };
        java.util.Set<String> available = new java.util.HashSet<>(
                java.util.Arrays.asList(
                        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : candidates) {
            if (available.contains(name)) return new Font(name, Font.PLAIN, 12);
        }
        return null;
    }

    private File iconFile() {
        try {
            File f = File.createTempFile("qplayer-tray", ".png");
            f.deleteOnExit();
            if (iconPng != null) {
                java.nio.file.Files.write(f.toPath(), iconPng);
            } else {
                ImageIO.write(placeholder(), "png", f);
            }
            return f;
        } catch (Exception e) {
            Logger.warn("tray icon temp write failed: {}", e);
            return null;
        }
    }

    /** Encode the generated placeholder to PNG bytes (icon resource is absent). */
    private static byte[] placeholderPng() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(placeholder(), "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            Logger.warn("placeholder PNG encode failed: {}", e);
            return null;
        }
    }

    private static java.awt.image.BufferedImage placeholder() {
        int n = 64;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x6750A4));
        g.fillRoundRect(2, 2, n - 4, n - 4, 18, 18);
        g.setColor(Color.WHITE);
        int[] xs = {24, 24, 46};
        int[] ys = {18, 46, 32};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return img;
    }
}
