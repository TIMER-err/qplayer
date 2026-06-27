package dev.t1m3.qplayer.desktop;

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
 * <p>Architecture: a {@link TrayIcon} (native Win32 NOTIFYICON / GtkStatusIcon /
 * macOS NSStatusItem via AWT) carries the icon + tooltip and surfaces mouse events
 * in absolute screen coords. The menu itself is a Swing {@link javax.swing.JPopupMenu}
 * drawn by Java2D — gives us full font control (native menus on Windows ignore
 * setFont and rely on GDI font-linking, which renders CJK as tofu on some Win10
 * LTSC installs) AND a single code path across all three OSes.
 *
 * <p>FlatLaf (Material Design Dark) styles the popup; the rest of the app is
 * GLFW/LWJGL so there is no other Swing UI to clash with the L&amp;F change.
 *
 * <p>Threading: tray callbacks arrive on AWT's EDT, and
 * {@link PlayerController.PlaybackListener#onPlaybackChanged} may fire from the
 * audio/worker threads, so every read/write funnels through the window's
 * main-task queue — the same queue playback control runs on — so neither the
 * audio thread nor the EDT touches controller state directly.
 */
final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;

    private TrayIcon trayIcon;
    private javax.swing.JPopupMenu popup;
    private javax.swing.JDialog popupAnchor;
    private javax.swing.JMenuItem playPause;
    private Font menuFont;

    TrayController(PlayerController controller, DesktopWindow win, byte[] iconPng) {
        this.controller = controller;
        this.win = win;
        this.iconPng = iconPng;
    }

    /** Build the tray. Returns false (and logs) if no tray is available, in which
     *  case the app still runs windowed. */
    boolean install() {
        if (!SystemTray.isSupported()) {
            Logger.warn("system tray not supported; tray menu disabled");
            return false;
        }
        try {
            File icon = iconFile();
            Image img = (icon != null) ? ImageIO.read(icon) : placeholder();
            menuFont = pickCjkFont();

            // Dark, flat L&F for the popup. Set before any JComponent is built.
            try { com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme.setup(); }
            catch (Throwable t) { Logger.warn("FlatLaf init failed: {}", t); }

            popup = new javax.swing.JPopupMenu();
            popup.add(swingItem("上一首", controller::prev));
            playPause = swingItem("播放 / 暂停", controller::toggle);
            popup.add(playPause);
            popup.add(swingItem("下一首", controller::next));
            popup.addSeparator();
            popup.add(swingItem("显示窗口", win::restoreFromTray));
            popup.add(swingItem("退出", () -> {
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
                @Override public void mouseReleased(MouseEvent e) { showPopupAt(e.getX(), e.getY()); }
            });
            SystemTray.getSystemTray().add(trayIcon);
            Logger.info("system tray initialized (font={})",
                    menuFont != null ? menuFont.getFamily() : "default");
            return true;
        } catch (Throwable t) {
            // Print the full stack: under native-image a bare NPE carries no message
            // (helpful-NPE info is stripped), so the toString alone is useless.
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("system tray init failed:\n{}", sw);
            trayIcon = null;
            return false;
        }
    }

    @Override
    public void onPlaybackChanged() {
        // May arrive on the audio/worker thread — marshal to the main loop.
        win.postMainTask(this::refresh);
    }

    private void refresh() {
        if (trayIcon == null) return;
        try {
            if (playPause != null) {
                javax.swing.SwingUtilities.invokeLater(() ->
                        playPause.setText(controller.isPlaying() ? "暂停" : "播放"));
            }
            Object title = controller.title.peek();
            Object artist = controller.artist.peek();
            String tip = title == null ? "QPlayer"
                    : (artist != null ? artist + " — " + title : String.valueOf(title));
            // AWT TrayIcon caps tooltips at 127 chars on Windows; trim well under.
            if (tip.length() > 64) tip = tip.substring(0, 63) + "…";
            trayIcon.setToolTip(tip);
        } catch (Throwable t) {
            Logger.warn("tray refresh failed: {}", t);
        }
    }

    void shutdown() {
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
            // Tray sits in the bottom-right of the screen (Win) / top-right (mac)
            // / wherever-the-panel-is (Linux). Snap the popup's bottom-right to
            // the click point — works for any of those layouts; clamping below
            // keeps it on-screen if the click is near a top/left edge.
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

    /** Pin a CJK family; Windows default (Segoe UI) and macOS default (Helvetica
     *  Neue) lack CJK glyphs and fall through to the JDK's logical-font chain,
     *  which renders tofu on stripped / non-fontconfig JDKs. */
    private static Font pickCjkFont() {
        String[] candidates = {
                "Microsoft YaHei UI", "Microsoft YaHei",   // Windows
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
