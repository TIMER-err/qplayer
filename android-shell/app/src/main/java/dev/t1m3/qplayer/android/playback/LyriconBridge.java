package dev.t1m3.qplayer.android.playback;

import android.content.Context;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.qml.QmlRuntimeLock;
import dev.t1m3.qplayer.util.Logger;

import io.github.proify.lyricon.lyric.model.LyricWord;
import io.github.proify.lyricon.lyric.model.RichLyricLine;
import io.github.proify.lyricon.lyric.model.Song;
import io.github.proify.lyricon.provider.ConnectionListener;
import io.github.proify.lyricon.provider.LyriconFactory;
import io.github.proify.lyricon.provider.LyriconProvider;
import io.github.proify.lyricon.provider.ProviderConstants;
import io.github.proify.lyricon.provider.ProviderInfo;
import io.github.proify.lyricon.provider.RemotePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pushes the current track, structured lyrics and play-head to Lyricon (词幕)
 * so the status-bar overlay can render QPlayer's syllable-timed lyrics natively.
 *
 * <p>Lives with {@link PlaybackService}: register while the session is up,
 * destroy when it goes away. Callers arrive from the main thread, the lyric
 * Property listener (which runs while the writer holds the QML runtime lock) and
 * the position ticker on the checkpoint worker.
 *
 * <p>Lock order is always QML runtime lock, then this bridge: controller state is
 * read into a {@link Snapshot} first, and only then is the bridge locked for the
 * Lyricon calls. Reading the controller while holding the bridge lock deadlocked
 * against a lyric update that already held the runtime lock (ANR on track switch).
 */
final class LyriconBridge {

    private volatile LyriconProvider provider;
    private volatile PlayerController bound;
    private final Consumer<List<LyricLine>> lyricsListener = ignored -> push();

    private String lastSongId;
    private Track lastTrack;
    private List<LyricLine> lastLyrics;
    private Boolean lastPlaying;
    private long lastPosition = Long.MIN_VALUE;

    void start(Context context) {
        if (provider != null) return;
        Context app = context.getApplicationContext();
        String pkg = app.getPackageName();
        try {
            provider = LyriconFactory.INSTANCE.createProvider(
                    app,
                    new ProviderInfo(pkg, pkg, null, null, null),
                    null,
                    ProviderConstants.SYSTEM_UI_PACKAGE_NAME);
            provider.setAutoSync(true);
            provider.getService().addConnectionListener(new ConnectionListener() {
                @Override public void onConnected(LyriconProvider p) {
                    Logger.info("lyricon: connected");
                    push();
                }
                @Override public void onReconnected(LyriconProvider p) {
                    Logger.info("lyricon: reconnected");
                    push();
                }
                @Override public void onDisconnected(LyriconProvider p) {
                    Logger.info("lyricon: disconnected");
                }
                @Override public void onConnectTimeout(LyriconProvider p) {
                    Logger.warn("lyricon: connect timeout (is 词幕 / LSPosed System UI scope active?)");
                }
            });
            boolean sent = provider.register();
            Logger.info("lyricon: register {}", sent ? "sent" : "skipped");
        } catch (Throwable e) {
            Logger.error("lyricon: start failed: {}", e.toString());
            provider = null;
        }
    }

    void bind(PlayerController controller) {
        // Listener lists belong to the QML runtime: mutate them under its lock.
        synchronized (QmlRuntimeLock.MONITOR) {
            synchronized (this) {
                if (controller == bound) return;
                unbind();
                bound = controller;
                if (bound != null) bound.lyrics.addListener(lyricsListener);
                lastSongId = null;
                lastTrack = null;
                lastLyrics = null;
                lastPlaying = null;
                lastPosition = Long.MIN_VALUE;
            }
        }
        push();
    }

    /** Controller state read before the bridge lock is taken. */
    private static final class Snapshot {
        final Track track;
        final List<LyricLine> lyrics;
        final boolean playing;
        final long position;
        final long durationMs;

        Snapshot(PlayerController c) {
            synchronized (QmlRuntimeLock.MONITOR) {
                track = c.currentTrack();
                lyrics = c.lyrics.peek();
                playing = c.isPlaying();
                position = lyricPosition(c);
                durationMs = c.duration();
            }
        }
    }

    void push() {
        PlayerController c = bound;
        if (c == null) return;
        Snapshot s = new Snapshot(c);
        synchronized (this) {
            if (c == bound) pushLocked(s);
        }
    }

    private void pushLocked(Snapshot s) {
        LyriconProvider p = provider;
        if (p == null) return;
        try {
            RemotePlayer player = p.getPlayer();
            Track t = s.track;
            if (t == null) {
                lastTrack = null;
                if (lastSongId != null) {
                    player.setSong(null);
                    lastSongId = null;
                    lastLyrics = null;
                }
                if (lastPlaying != Boolean.FALSE) {
                    player.setPlaybackState(false);
                    lastPlaying = Boolean.FALSE;
                }
                lastPosition = Long.MIN_VALUE;
                return;
            }

            String id = songId(t);
            boolean songChanged = !id.equals(lastSongId) || s.lyrics != lastLyrics;
            if (songChanged) {
                player.setSong(toSong(t, s.durationMs, s.lyrics));
                lastSongId = id;
                lastLyrics = s.lyrics;
            }

            if (lastPlaying == null || lastPlaying != s.playing) {
                player.setPlaybackState(s.playing);
                lastPlaying = s.playing;
            }

            player.setDisplayTranslation(Boolean.TRUE.equals(LyricConfig.instance.showTranslation.getValue()));
            player.setDisplayRoma(Boolean.TRUE.equals(LyricConfig.instance.showRomaji.getValue()));

            if (songChanged) player.seekTo(s.position);
            else if (s.position != lastPosition) player.setPosition(s.position);
            lastPosition = s.position;
            lastTrack = t;
        } catch (Throwable e) {
            Logger.error("lyricon: push failed: {}", e.toString());
        }
    }

    void pushPosition() {
        PlayerController c = bound;
        if (c == null) return;
        Snapshot s = new Snapshot(c);
        synchronized (this) {
            LyriconProvider p = provider;
            if (p == null || c != bound) return;
            try {
                if (s.track == null) {
                    if (lastTrack != null || lastSongId != null || lastPlaying != Boolean.FALSE) pushLocked(s);
                    return;
                }
                // Track and play state can change without a lyric Property update.
                // The service ticker keeps those changes visible while backgrounded.
                if (s.track != lastTrack || s.lyrics != lastLyrics
                        || lastPlaying == null || lastPlaying != s.playing) {
                    pushLocked(s);
                    return;
                }
                if (s.position == lastPosition) return;
                p.getPlayer().setPosition(s.position);
                lastPosition = s.position;
            } catch (Throwable e) {
                Logger.error("lyricon: position failed: {}", e.toString());
            }
        }
    }

    void destroy() {
        LyriconProvider p;
        synchronized (QmlRuntimeLock.MONITOR) {
            synchronized (this) {
                unbind();
                p = provider;
                provider = null;
                lastSongId = null;
                lastTrack = null;
                lastLyrics = null;
                lastPlaying = null;
                lastPosition = Long.MIN_VALUE;
            }
        }
        if (p == null) return;
        try {
            p.unregister();
        } catch (Throwable ignored) {
        }
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
    }

    private void unbind() {
        PlayerController previous = bound;
        bound = null;
        if (previous != null) previous.lyrics.removeListener(lyricsListener);
    }

    private static long lyricPosition(PlayerController c) {
        int offset = LyricConfig.instance.offsetMs.getValue();
        return Math.max(0L, c.lyricClockPosition() - offset);
    }

    private static String songId(Track t) {
        String id = t.canonicalId();
        if (id != null && !id.isEmpty()) return id;
        return String.valueOf(t.title) + '\0' + t.artist;
    }

    private static Song toSong(Track t, long controllerDurationMs, List<LyricLine> lyrics) {
        long duration = t.durationMs > 0 ? t.durationMs : controllerDurationMs;
        return new Song(
                songId(t),
                nz(t.title),
                nz(t.artist),
                Math.max(0L, duration),
                null,
                toLines(lyrics));
    }

    private static List<RichLyricLine> toLines(List<LyricLine> lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return null;
        List<RichLyricLine> out = new ArrayList<>(lyrics.size());
        for (LyricLine src : lyrics) {
            RichLyricLine line = toLine(src);
            if (line != null) out.add(line);
        }
        return out.isEmpty() ? null : out;
    }

    private static RichLyricLine toLine(LyricLine src) {
        if (src == null) return null;
        String text = src.text();
        if (text == null || text.trim().isEmpty()) return null;
        long begin = src.startMs();
        long end = src.endMs();
        if (end <= begin) end = begin + 500L;
        boolean karaoke = src.syllables.size() > 1;
        List<LyricWord> words = karaoke ? toWords(src) : null;
        boolean right = src.vocalChannel == LyricLine.VocalChannel.DUET_RIGHT
                || src.vocalChannel == LyricLine.VocalChannel.BACKGROUND_RIGHT;
        return new RichLyricLine(
                begin,
                end,
                end - begin,
                right,
                null,
                text,
                words,
                null,
                null,
                emptyToNull(src.translation),
                null,
                emptyToNull(src.romaji));
    }

    private static List<LyricWord> toWords(LyricLine src) {
        List<LyricWord> words = new ArrayList<>(src.syllables.size());
        for (Syllable s : src.syllables) {
            if (s == null || s.text == null) continue;
            long begin = s.startMs;
            long end = s.endMs();
            if (end <= begin) continue;
            words.add(new LyricWord(begin, end, end - begin, s.text, null));
        }
        return words.isEmpty() ? null : words;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
