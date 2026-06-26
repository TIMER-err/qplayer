package dev.t1m3.qplayer.desktop;

import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.Separator;
import dorkbox.systemTray.SystemTray;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * System-tray integration (the desktop analogue of the Android PlaybackService):
 * a dorkbox tray icon whose menu mirrors the transport — Previous / Play-Pause /
 * Next / Show-Hide / Quit — and a tooltip showing the current track.
 *
 * <p>Threading: dorkbox dispatches menu callbacks on its own thread and
 * {@link PlayerController.PlaybackListener#onPlaybackChanged} may fire from the
 * audio/worker threads, so every read/write funnels through the window's main-task
 * queue — the same queue playback control runs on — so neither the audio thread
 * nor dorkbox's thread touches controller/tray state directly.
 */
final class TrayController implements PlayerController.PlaybackListener {

    private final PlayerController controller;
    private final DesktopWindow win;
    private final byte[] iconPng;

    private SystemTray tray;
    private MenuItem playPause;

    TrayController(PlayerController controller, DesktopWindow win, byte[] iconPng) {
        this.controller = controller;
        this.win = win;
        this.iconPng = iconPng;
    }

    /** Build the tray. Returns false (and logs) if no tray is available, in which
     *  case the app still runs windowed. */
    boolean install() {
        try {
            tray = SystemTray.get("QPlayer");
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            Logger.warn("system tray init failed:\n{}", sw);
            tray = null;
        }
        if (tray == null) {
            Logger.warn("no system tray available; tray menu disabled");
            return false;
        }
        Logger.info("system tray initialized: {}", tray.getClass().getName());
        tray.setTooltip("QPlayer");
        tray.setImage(iconFile());

        tray.getMenu().add(new MenuItem("上一首", e -> win.postMainTask(controller::prev)));
        playPause = new MenuItem("播放 / 暂停", e -> win.postMainTask(controller::toggle));
        tray.getMenu().add(playPause);
        tray.getMenu().add(new MenuItem("下一首", e -> win.postMainTask(controller::next)));
        tray.getMenu().add(new Separator());
        tray.getMenu().add(new MenuItem("显示窗口", e -> win.postMainTask(win::restoreFromTray)));
        tray.getMenu().add(new MenuItem("退出", e -> win.postMainTask(() -> {
            shutdown();
            win.requestQuit();
        })));
        return true;
    }

    @Override
    public void onPlaybackChanged() {
        // May arrive on the audio/worker thread — marshal to the main loop.
        win.postMainTask(this::refresh);
    }

    private void refresh() {
        if (tray == null) return;
        try {
            if (playPause != null) {
                playPause.setText(controller.isPlaying() ? "暂停" : "播放");
            }
            Object title = controller.title.peek();
            Object artist = controller.artist.peek();
            String tip = title == null ? "QPlayer"
                    : (artist != null ? artist + " — " + title : String.valueOf(title));
            tray.setTooltip(tip);
        } catch (Throwable t) {
            Logger.warn("tray refresh failed: {}", t);
        }
    }

    void shutdown() {
        if (tray != null) {
            try { tray.shutdown(); } catch (Throwable ignored) {}
            tray = null;
        }
    }

    // dorkbox's setImage wants a File; write the bundled PNG (or a generated
    // placeholder) to a temp file once.
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

    private static BufferedImage placeholder() {
        int n = 64;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x6750A4)); // MD3 primary-ish
        g.fillRoundRect(2, 2, n - 4, n - 4, 18, 18);
        g.setColor(Color.WHITE);
        int[] xs = {24, 24, 46};
        int[] ys = {18, 46, 32};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return img;
    }
}
