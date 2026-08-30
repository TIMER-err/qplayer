package dev.t1m3.qplayer.netease;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.t1m3.qplayer.netease.dto.NeteaseAlbum;
import dev.t1m3.qplayer.netease.dto.NeteaseArtist;
import dev.t1m3.qplayer.netease.dto.NeteaseLyric;
import dev.t1m3.qplayer.netease.dto.NeteasePlaylist;
import dev.t1m3.qplayer.netease.dto.NeteaseSong;
import dev.t1m3.qplayer.netease.dto.NeteaseUser;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.CredentialCipher;
import dev.t1m3.qplayer.store.CredentialKeyProtection;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only compatibility bridge for credentials and DTOs written before the
 * plugin transition. QPlayer core deliberately contains no provider endpoint,
 * transport, signing algorithm or online implementation. Every network-facing
 * method fails closed and remains only until the delayed migration window ends.
 */
@Deprecated
public final class NeteaseClient {
    public static final NeteaseClient INSTANCE = new NeteaseClient();

    public interface ErrorListener { void onError(String message); }
    public enum CredentialEvent { ENCRYPTED, KEYSTORE_FALLBACK, KEYSTORE_READ_FAILED }
    public interface CredentialListener { void onCredentialEvent(CredentialEvent event); }

    private final Gson gson = new Gson();
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Path cookieFile = AppDirs.credentialsFile("netease-cookies.enc");
    private final Path legacyCookieFile = AppDirs.credentialsFile("netease-cookies.json");
    private final CredentialCipher cookieCipher = new CredentialCipher(
            AppDirs.credentialsFile("credential-encryption.key"),
            CredentialKeyProtection.current());
    private final Object eventLock = new Object();
    private final List<CredentialEvent> pendingEvents = new ArrayList<>();
    private volatile CredentialListener credentialListener;
    private boolean credentialUnlockPending;

    private NeteaseClient() { loadCookies(false); }

    public void setErrorListener(ErrorListener ignored) { }

    public void setCredentialListener(CredentialListener listener) {
        List<CredentialEvent> queued;
        synchronized (eventLock) {
            credentialListener = listener;
            if (listener == null || pendingEvents.isEmpty()) return;
            queued = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        for (CredentialEvent event : queued) listener.onCredentialEvent(event);
    }

    public boolean consumeCredentialUnlock() {
        synchronized (eventLock) {
            boolean value = credentialUnlockPending;
            credentialUnlockPending = false;
            return value;
        }
    }

    private void emit(CredentialEvent event) {
        CredentialListener listener;
        synchronized (eventLock) {
            listener = credentialListener;
            if (listener == null) { pendingEvents.add(event); return; }
        }
        listener.onCredentialEvent(event);
    }

    private static IOException removed() {
        return new IOException("built-in online source was removed; install a source plugin");
    }

    public String songUrl(long id, String level) throws IOException { throw removed(); }
    public UrlInfo songUrlInfo(long id, String level) throws IOException { throw removed(); }
    public List<String> searchHot() throws IOException { throw removed(); }
    public List<NeteasePlaylist> personalizedPlaylists(int limit) throws IOException { throw removed(); }
    public SongSearchPage searchSongsPage(String query, int offset, int limit) throws IOException { throw removed(); }
    public List<NeteaseAlbum> searchAlbums(String query, int limit) throws IOException { throw removed(); }
    public List<NeteaseArtist> searchArtists(String query, int limit) throws IOException { throw removed(); }
    public List<NeteaseSong> searchSongs(String query, int offset, int limit) throws IOException { throw removed(); }
    public NeteasePlaylist playlistDetail(long id) throws IOException { throw removed(); }
    public List<NeteaseSong> playlistTracks(long id, int limit) throws IOException { throw removed(); }
    public ArtistPage artistDetail(long id) throws IOException { throw removed(); }
    public List<NeteaseAlbum> artistAlbums(long id, int limit) throws IOException { throw removed(); }
    public AlbumPage albumDetail(long id) throws IOException { throw removed(); }
    public boolean playlistSubscribe(long id, boolean subscribe) throws IOException { throw removed(); }
    public NeteaseSong songDetail(long id) throws IOException { throw removed(); }
    public List<NeteaseSong> songDetails(List<Long> ids) throws IOException { throw removed(); }
    public long loginUid() throws IOException { throw removed(); }
    public synchronized long importLoginCookies(String header) throws IOException { throw removed(); }
    public List<NeteasePlaylist> userPlaylists(long uid, int limit) throws IOException { throw removed(); }
    public List<NeteaseSong> recentPlayed(int limit) throws IOException { throw removed(); }
    public void scrobble(long id, long sourceId, long time, String source) { }
    public NeteaseLyric lyric(long id) throws IOException { throw removed(); }
    public boolean like(long id, boolean like) throws IOException { throw removed(); }
    public long favoritePlaylistId(long uid) throws IOException { throw removed(); }
    public boolean setFavorite(long uid, long id, boolean value) throws IOException { throw removed(); }
    public boolean manipulatePlaylistTracks(long playlist, long song, boolean add) throws IOException { throw removed(); }
    public long createPlaylist(String name, boolean privacy) throws IOException { throw removed(); }
    public boolean deletePlaylist(long id) throws IOException { throw removed(); }
    public long uploadImage(byte[] image, String filename) throws IOException { throw removed(); }
    public boolean updatePlaylistCover(long playlist, long image) throws IOException { throw removed(); }
    public Set<Long> likedSongIds(long uid) throws IOException { throw removed(); }
    public List<NeteaseSong> recommendSongs() throws IOException { throw removed(); }
    public List<NeteaseSong> intelligenceSongs(long id, long playlist, long start) throws IOException { throw removed(); }
    public NeteaseUser userDetail(long id) throws IOException { throw removed(); }
    public String qrLoginKey() throws IOException { throw removed(); }
    public String qrLoginContent(String key) { return ""; }
    public boolean[][] qrMatrix(String content) { return new boolean[0][0]; }
    public int qrLoginCheck(String key) throws IOException { throw removed(); }

    public static String thumbUrl(String raw) {
        return raw == null ? null : raw.replace("http://", "https://");
    }

    public boolean hasCookie(String name) { return cookies.containsKey(name); }
    public boolean isLoggedIn() { return hasLoginCredential(); }

    /** Whether an older QPlayer installation left a credential envelope that a
     * source plugin can migrate. This deliberately does not reveal its contents
     * and remains true when the system key store is temporarily locked. */
    public boolean hasStoredLegacyCredentials() {
        return Files.isRegularFile(cookieFile) || Files.isRegularFile(legacyCookieFile);
    }

    private boolean hasLoginCredential() {
        String value = cookies.get("MUSIC_U");
        return value != null && !value.isEmpty();
    }

    public synchronized void clearCookies() {
        cookies.clear();
        synchronized (eventLock) { credentialUnlockPending = false; }
        try { Files.deleteIfExists(cookieFile); } catch (IOException ignored) { }
        try { Files.deleteIfExists(legacyCookieFile); } catch (IOException ignored) { }
    }

    public void logout() { clearCookies(); }
    public boolean retryCredentialLoad() { return loadCookies(true); }

    public synchronized boolean fallbackUnreadableCredentials() {
        try {
            cookieCipher.forceOwnerOnlyFallback();
            clearCookies();
            return true;
        } catch (IOException error) {
            emit(CredentialEvent.KEYSTORE_READ_FAILED);
            return false;
        }
    }

    public synchronized boolean resetUnreadableCredentialsForPlatformLogin() {
        try {
            cookieCipher.verifyPlatformProtectionAvailable();
            cookieCipher.resetForPlatformProtection();
            clearCookies();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public boolean usesOwnerOnlyCredentialProtection() {
        return cookieCipher.usesOwnerOnlyProtection();
    }

    public synchronized boolean enableSystemCredentialProtection() {
        try {
            cookieCipher.enablePlatformProtectionInteractively();
            emit(CredentialEvent.ENCRYPTED);
            return true;
        } catch (Exception error) {
            emit(CredentialEvent.KEYSTORE_FALLBACK);
            return false;
        }
    }

    /** One-way handoff to the selected external plugin. */
    public String legacyCookieHeaderForMigration() {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (value.length() > 0) value.append("; ");
            value.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return value.toString();
    }

    private synchronized boolean loadCookies(boolean interactive) {
        boolean encrypted = Files.isRegularFile(cookieFile);
        Path source = encrypted ? cookieFile : legacyCookieFile;
        if (!Files.isRegularFile(source)) return false;
        cookies.clear();
        synchronized (eventLock) { credentialUnlockPending = false; }
        try {
            String json = encrypted
                    ? new String(interactive
                            ? cookieCipher.decryptInteractively(Files.readAllBytes(cookieFile))
                            : cookieCipher.decrypt(Files.readAllBytes(cookieFile)),
                            StandardCharsets.UTF_8)
                    : StorageFiles.readUtf8(legacyCookieFile);
            JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) throw new IOException("legacy credential is not an object");
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    cookies.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (!encrypted && persistEncryptedCopy()) {
                Files.deleteIfExists(legacyCookieFile);
            }
            CredentialCipher.KeyAccess access = cookieCipher.lastKeyAccess();
            if (encrypted && access == CredentialCipher.KeyAccess.PLATFORM_READ) {
                synchronized (eventLock) { credentialUnlockPending = true; }
            } else if (access == CredentialCipher.KeyAccess.PLATFORM_MIGRATED) {
                emit(CredentialEvent.ENCRYPTED);
            } else if (access == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK) {
                emit(CredentialEvent.KEYSTORE_FALLBACK);
            }
            return true;
        } catch (Exception error) {
            cookies.clear();
            Logger.warn("legacy source credential load failed: {}", error.getMessage());
            if (encrypted) emit(CredentialEvent.KEYSTORE_READ_FAILED);
            return false;
        }
    }

    private boolean persistEncryptedCopy() {
        byte[] plaintext = gson.toJson(new LinkedHashMap<>(cookies))
                .getBytes(StandardCharsets.UTF_8);
        try {
            StorageFiles.writeCredentialBytesAtomic(cookieFile, cookieCipher.encrypt(plaintext));
            emit(cookieCipher.lastKeyAccess() == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK
                    ? CredentialEvent.KEYSTORE_FALLBACK : CredentialEvent.ENCRYPTED);
            return true;
        } catch (Exception error) {
            Logger.warn("legacy plaintext credential migration failed: {}", error.getMessage());
            return false;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static final class UrlInfo {
        public final String url;
        public final boolean trial;
        public UrlInfo(String url, boolean trial) { this.url = url; this.trial = trial; }
    }

    public static final class SongSearchPage {
        public final List<NeteaseSong> songs;
        public final int total;
        public final int consumed;
        public SongSearchPage(List<NeteaseSong> songs, int total, int consumed) {
            this.songs = songs != null ? songs : Collections.<NeteaseSong>emptyList();
            this.total = total;
            this.consumed = consumed;
        }
        public boolean hasMore(int offset, int limit) { return offset + consumed < total; }
    }

    public static final class ArtistPage {
        public final NeteaseArtist artist;
        public final List<NeteaseSong> hotSongs;
        public ArtistPage(NeteaseArtist artist, List<NeteaseSong> hotSongs) {
            this.artist = artist;
            this.hotSongs = hotSongs != null ? hotSongs : Collections.<NeteaseSong>emptyList();
        }
    }

    public static final class AlbumPage {
        public final NeteaseAlbum album;
        public final List<NeteaseSong> songs;
        public AlbumPage(NeteaseAlbum album, List<NeteaseSong> songs) {
            this.album = album;
            this.songs = songs != null ? songs : Collections.<NeteaseSong>emptyList();
        }
    }

}
