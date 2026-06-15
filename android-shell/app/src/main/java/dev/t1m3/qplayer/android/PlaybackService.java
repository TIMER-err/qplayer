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
    private static final int NOTIF_ID = 1;

    /** Set by the activity before the service is started (same-process handoff). */
    static volatile PlayerController controller;

    private MediaSessionCompat session;

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
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                stopSelf();
            }
        });
        session.setActive(true);
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
        session.setActive(false);
        session.release();
        super.onDestroy();
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
            art = decode(t.coverBytes);
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

        Notification n = buildNotification(t, playing, art);
        // Always satisfy the startForegroundService contract (startForeground within
        // ~5s of each delivery), then detach while paused so the service can be
        // reclaimed — the notification stays, dismissible, for quick resume.
        startForeground(NOTIF_ID, n);
        if (!playing) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
        }
    }

    private Notification buildNotification(Track t, boolean playing, Bitmap art) {
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
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
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
}
