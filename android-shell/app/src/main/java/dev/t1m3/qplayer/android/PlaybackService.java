package dev.t1m3.qplayer.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

/**
 * Foreground service that bridges {@link PlayerController} to the system media
 * session: lockscreen / notification / bluetooth transport controls, and — by
 * keeping the process foregrounded — uninterrupted background playback.
 *
 * <p>The controller is owned by {@link QPlayerActivity}; since both live in the
 * same process the activity hands it over via {@link #controller} and pokes this
 * service ({@link #ACTION_REFRESH}) through its
 * {@link PlayerController.PlaybackListener} whenever playback changes. Metadata is
 * read from {@link PlayerController#currentTrack()} (plain state, valid off the
 * render thread) rather than the UI Properties, which lag while backgrounded.
 */
public final class PlaybackService extends Service {

    static final String ACTION_REFRESH = "dev.t1m3.qplayer.action.REFRESH";
    private static final String CHANNEL_ID = "qplayer.playback";
    /** Package-visible so {@link QPlayerApplication}'s crash handler can cancel
     *  this notification without needing a live Service instance. */
    static final int NOTIF_ID = 1;

    /** Set by the activity before the service is started (same-process handoff). */
    static volatile PlayerController controller;
    /** The activity's listener that boot-starts this service; restored on destroy so a
     *  later play re-bootstraps. While the service lives it registers itself instead, so
     *  refreshes run in-process (no startForegroundService — which is barred from the
     *  background on Android 12+, the bug that left controls stuck after a focus pause). */
    static volatile PlayerController.PlaybackListener bootstrapListener;

    private MediaSessionCompat session;
    private final PlayerController.PlaybackListener selfListener = this::onControllerChanged;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        session = new MediaSessionCompat(this, "qplayer");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() {
                PlayerController c = controller;
                if (c != null && !c.isPlaying()) c.toggle();
            }
            @Override public void onPause() {
                PlayerController c = controller;
                if (c != null && c.isPlaying()) c.toggle();
            }
            @Override public void onSkipToNext() {
                PlayerController c = controller;
                if (c != null) c.next();
            }
            @Override public void onSkipToPrevious() {
                PlayerController c = controller;
                if (c != null) c.prev();
            }
            @Override public void onSeekTo(long pos) {
                PlayerController c = controller;
                if (c != null) c.seek(pos);
            }
            @Override public void onStop() {
                PlayerController c = controller;
                if (c != null && c.isPlaying()) c.toggle();
                clearNotification();
                stopSelf();
            }
        });
        session.setActive(true);
        // Take over as the controller's listener so state changes refresh us directly,
        // in-process, instead of round-tripping through startForegroundService.
        PlayerController c = controller;
        if (c != null) c.setPlaybackListener(selfListener);
    }

    private void onControllerChanged() {
        try {
            refresh();
        } catch (Throwable e) {
            Logger.error("PlaybackService: refresh failed: {}", e.toString());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Hardware / bluetooth media buttons routed through the session callback.
        MediaButtonReceiver.handleIntent(session, intent);
        try {
            refresh();
        } catch (Throwable e) {
            Logger.error("PlaybackService: refresh failed: {}", e.toString());
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // Stop playback and release audio backend — called when the task is
        // swiped away (stopWithTask="true") or explicitly stopped.
        PlayerController c = controller;
        if (c != null) {
            // Capture position/queue/playMode before anything below pauses/tears
            // playback down — QPlayerActivity.onDestroy() skips PlayerController.
            // shutdown() while playing precisely so this Service can keep going in
            // the background, so this is the only save-on-real-exit this path gets.
            c.saveSessionState();
            if (c.isPlaying()) c.toggle();
            c.setPlaybackListener(bootstrapListener);
        }
        session.setActive(false);
        session.release();
        // Explicit stop + cancel rather than relying on "destroying a foreground
        // service auto-clears its notification" — that contract isn't reliable
        // across OEM ROMs (MIUI/HyperOS, ColorOS, EMUI task-cleanup) and doesn't
        // hold at all if this method never runs (see onTaskRemoved / the crash
        // handler in QPlayerApplication for the paths where it might not).
        clearNotification();
        super.onDestroy();
    }

    /** Swiping the task away from Recents calls this instead of a normal destroy
     *  on many OEM ROMs' aggressive task-cleanup, which doesn't reliably run
     *  onDestroy() afterward — stop the service outright here so the notification
     *  and foreground state never survive the task disappearing. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PlayerController c = controller;
        if (c != null) {
            // See onDestroy(): capture position/queue/playMode before pausing.
            c.saveSessionState();
            // Unregister before toggle(): toggle() runs on the controller's main
            // executor (a Handler.post on Android, i.e. deferred, not inline), so its
            // notifyPlayback() callback lands *after* clearNotification() below has
            // already run. Left wired to selfListener, that deferred callback calls
            // refresh() -> startForeground(), which resurrects the notification right
            // after it was cleared (the disappear-then-reappear bug). Left wired to
            // bootstrapListener it's worse — that calls startForegroundService(),
            // which can spin up a whole new service instance. Null makes the deferred
            // callback a no-op; the next QPlayerActivity launch rewires the listener.
            c.setPlaybackListener(null);
            if (c.isPlaying()) c.toggle();
        }
        clearNotification();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void clearNotification() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) {
        }
    }

    /** Rebuild metadata + playback state + notification from the controller. */
    private void refresh() {
        PlayerController c = controller;
        if (c == null) return;
        Track t = c.currentTrack();
        boolean playing = c.isPlaying();
        long pos = c.position();
        long dur = c.duration();

        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder();
        Bitmap art = null;
        if (t != null) {
            mb.putString(MediaMetadataCompat.METADATA_KEY_TITLE, nz(t.title));
            mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nz(t.artist));
            mb.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, nz(t.album));
            if (dur > 0) mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur);
            // Read the cover from the current Track's plain bytes, set synchronously by
            // the loader before it fires the refresh (netease downloads, local
            // file-backed and embedded covers all populate t.coverBytes). The coverBytes
            // Property is only committed on the render queue, which is paused while
            // backgrounded — reading it primary left a background track-switch showing the
            // previous song's art. It stays as a fallback for any path that only sets it.
            byte[] coverData = t.coverBytes;
            if (coverData == null) coverData = c.coverBytes.peek();
            art = decode(coverData);
            if (art != null) mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        }
        session.setMetadata(mb.build());

        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        pos, playing ? 1f : 0f)
                .build());

        // Extract Monet seed from cover for notification accent (progress bar tint).
        String seedHex = c.coverSeed.peek();
        int accentColor = parseHexColor(seedHex);

        Notification n = buildNotification(t, playing, art, accentColor);
        // Stay foreground for the whole session — including while paused — so we never
        // need to re-promote from the background (Android 12+ forbids starting a FGS
        // from the background, which previously left controls stuck after a pause).
        // The notification is dismissible (setOngoing not set) and STOP fully exits.
        startForeground(NOTIF_ID, n);
    }

    private Notification buildNotification(Track t, boolean playing, Bitmap art, int accentColor) {
        Intent open = new Intent(this, QPlayerActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, pf);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t != null ? nz(t.title) : "QPlayer")
                .setContentText(t != null ? nz(t.artist) : "")
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // CATEGORY_TRANSPORT + an ongoing notification while playing is how OEM
                // "live media" surfaces (ColorOS 流体云 media capsule, MIUI/HyperOS, etc.)
                // recognise this as an active session to elevate. Paused -> not ongoing so
                // the notification stays swipe-dismissible (and STOP fully exits).
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accentColor)
                .setColorized(true);
        if (art != null) b.setLargeIcon(art);

        b.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, "prev",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)));
        b.addAction(new NotificationCompat.Action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "pause" : "play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        b.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, "next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT)));

        b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                        PlaybackStateCompat.ACTION_STOP)));
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Playback",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Parse a #RRGGBB hex string to an int color (A=FF). Returns 0 on failure. */
    private static int parseHexColor(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return 0;
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
