package dev.t1m3.qplayer.android.playback;

import android.content.Context;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.lyric.LyricLine;
import dev.t1m3.qplayer.lyric.Syllable;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.model.Track;
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
 * destroy when it goes away. {@link #push()} and {@link #pushPosition()} are
 * synchronized because playback callbacks run on the main thread, lyric
 * Property listeners on the render thread, and the position ticker on the
 * checkpoint worker.
 */
final class LyriconBridge {

    private LyriconProvider provider;
    private PlayerController bound;
    private final Consumer<List<LyricLine>> lyricsListener = ignored -> push();

    private String lastSongId;
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

    synchronized void bind(PlayerController controller) {
        if (controller == bound) return;
        unbind();
        bound = controller;
        if (bound != null) bound.lyrics.addListener(lyricsListener);
        lastSongId = null;
        lastLyrics = null;
        lastPlaying = null;
        lastPosition = Long.MIN_VALUE;
        push();
    }

    synchronized void push() {
        LyriconProvider p = provider;
        PlayerController c = bound;
        if (p == null || c == null) return;
        try {
            RemotePlayer player = p.getPlayer();
            Track t = c.currentTrack();
            if (t == null) {
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

            List<LyricLine> lyrics = c.lyrics.peek();
            String id = songId(t);
            boolean songChanged = !id.equals(lastSongId) || lyrics != lastLyrics;
            if (songChanged) {
                player.setSong(toSong(t, c, lyrics));
                lastSongId = id;
                lastLyrics = lyrics;
            }

            boolean playing = c.isPlaying();
            if (lastPlaying == null || lastPlaying != playing) {
                player.setPlaybackState(playing);
                lastPlaying = playing;
            }

            player.setDisplayTranslation(Boolean.TRUE.equals(LyricConfig.instance.showTranslation.getValue()));
            player.setDisplayRoma(Boolean.TRUE.equals(LyricConfig.instance.showRomaji.getValue()));

            long pos = lyricPosition(c);
            if (songChanged) player.seekTo(pos);
            else if (pos != lastPosition) player.setPosition(pos);
            lastPosition = pos;
        } catch (Throwable e) {
            Logger.error("lyricon: push failed: {}", e.toString());
        }
    }

    synchronized void pushPosition() {
        LyriconProvider p = provider;
        PlayerController c = bound;
        if (p == null || c == null || c.currentTrack() == null) return;
        try {
            long pos = lyricPosition(c);
            if (pos == lastPosition) return;
            p.getPlayer().setPosition(pos);
            lastPosition = pos;
        } catch (Throwable e) {
            Logger.error("lyricon: position failed: {}", e.toString());
        }
    }

    synchronized void destroy() {
        unbind();
        LyriconProvider p = provider;
        provider = null;
        lastSongId = null;
        lastLyrics = null;
        lastPlaying = null;
        lastPosition = Long.MIN_VALUE;
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

    private static Song toSong(Track t, PlayerController c, List<LyricLine> lyrics) {
        long duration = t.durationMs > 0 ? t.durationMs : c.duration();
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
