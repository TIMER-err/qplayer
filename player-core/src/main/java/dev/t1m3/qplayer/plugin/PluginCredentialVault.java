package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.MediaId;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.CredentialCipher;
import dev.t1m3.qplayer.store.CredentialKeyProtection;
import dev.t1m3.qplayer.store.StorageFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Namespaced encrypted secrets; callers can never address another plugin's path. */
public final class PluginCredentialVault {
    public enum CredentialEvent {
        ENCRYPTED,
        KEYSTORE_FALLBACK,
        KEYSTORE_READ_FAILED
    }

    public interface CredentialListener {
        void onCredentialEvent(CredentialEvent event);
    }

    private static final byte[] MAGIC = new byte[]{'Q', 'P', 'P', 'C'};
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_CREDENTIAL_BYTES = 1024 * 1024;
    private final Path root;
    private final CredentialCipher cipher;
    private final Object eventLock = new Object();
    private final List<CredentialEvent> pendingEvents = new ArrayList<>();
    private volatile CredentialListener listener;
    private boolean credentialUnlockPending;
    private boolean fallbackNoticeSent;

    public PluginCredentialVault() {
        this(AppDirs.credentialsFile("plugins"), new CredentialCipher(
                AppDirs.credentialsFile("plugin-credential-encryption.key"),
                CredentialKeyProtection.current()));
    }

    public PluginCredentialVault(Path root, CredentialCipher cipher) {
        if (root == null || cipher == null) throw new IllegalArgumentException();
        this.root = root.toAbsolutePath().normalize();
        this.cipher = cipher;
    }

    public synchronized void put(String pluginId, String key, byte[] value)
            throws IOException, GeneralSecurityException {
        if (value == null) throw new IllegalArgumentException("value == null");
        if (value.length > MAX_CREDENTIAL_BYTES) {
            throw new IllegalArgumentException("credential value is too large");
        }
        Path target = path(pluginId, key);
        byte[] plaintext = envelope(pluginId, key, value);
        try {
            StorageFiles.writeCredentialBytesAtomic(target, cipher.encrypt(plaintext));
            observeKeyAccess(false);
        } catch (IOException | GeneralSecurityException error) {
            emit(CredentialEvent.KEYSTORE_READ_FAILED);
            throw error;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized byte[] get(String pluginId, String key)
            throws IOException, GeneralSecurityException {
        Path source = path(pluginId, key);
        if (!Files.isRegularFile(source)) return null;
        final byte[] plaintext;
        try {
            plaintext = cipher.decrypt(Files.readAllBytes(source));
            observeKeyAccess(true);
        } catch (IOException | GeneralSecurityException error) {
            emit(CredentialEvent.KEYSTORE_READ_FAILED);
            throw error;
        }
        try {
            byte[] value = payload(plaintext, pluginId, key);
            if (value.length > MAX_CREDENTIAL_BYTES) {
                Arrays.fill(value, (byte) 0);
                throw new GeneralSecurityException("credential value is too large");
            }
            return value;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized byte[] getInteractively(String pluginId, String key)
            throws IOException, GeneralSecurityException {
        Path source = path(pluginId, key);
        if (!Files.isRegularFile(source)) return null;
        final byte[] plaintext;
        try {
            plaintext = cipher.decryptInteractively(Files.readAllBytes(source));
            observeKeyAccess(true);
        } catch (IOException | GeneralSecurityException error) {
            emit(CredentialEvent.KEYSTORE_READ_FAILED);
            throw error;
        }
        try {
            byte[] value = payload(plaintext, pluginId, key);
            if (value.length > MAX_CREDENTIAL_BYTES) {
                Arrays.fill(value, (byte) 0);
                throw new GeneralSecurityException("credential value is too large");
            }
            return value;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized boolean delete(String pluginId, String key) throws IOException {
        return Files.deleteIfExists(path(pluginId, key));
    }

    /** Attach after the UI exists; startup events are queued instead of lost. */
    public void setCredentialListener(CredentialListener value) {
        List<CredentialEvent> queued;
        synchronized (eventLock) {
            listener = value;
            if (value == null || pendingEvents.isEmpty()) return;
            queued = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        for (CredentialEvent event : queued) value.onCredentialEvent(event);
    }

    /** Consume only after a provider confirms that the recovered secret is valid. */
    public boolean consumeCredentialUnlock() {
        synchronized (eventLock) {
            boolean value = credentialUnlockPending;
            credentialUnlockPending = false;
            return value;
        }
    }

    /** Non-interactive retry. Platform protectors enforce their own short timeout. */
    public synchronized boolean retryCredentialUnlock() {
        try {
            cipher.verifyPlatformProtectionAvailable();
            synchronized (eventLock) { credentialUnlockPending = true; }
            return true;
        } catch (Exception error) {
            emit(CredentialEvent.KEYSTORE_READ_FAILED);
            return false;
        }
    }

    /** Abandon inaccessible ciphertext and persist the explicitly weaker mode. */
    public synchronized boolean fallbackUnreadableCredentials() {
        try {
            cipher.forceOwnerOnlyFallback();
            clearCredentialFiles();
            fallbackNoticeSent = true;
            emit(CredentialEvent.KEYSTORE_FALLBACK);
            return true;
        } catch (IOException error) {
            emit(CredentialEvent.KEYSTORE_READ_FAILED);
            return false;
        }
    }

    /** Keep old ciphertext until the platform store has proved immediately readable. */
    public synchronized boolean resetUnreadableCredentialsForPlatformLogin() {
        try {
            cipher.verifyPlatformProtectionAvailable();
            cipher.resetForPlatformProtection();
            clearCredentialFiles();
            fallbackNoticeSent = false;
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public synchronized boolean enableSystemCredentialProtection() {
        try {
            cipher.enablePlatformProtectionInteractively();
            fallbackNoticeSent = false;
            emit(CredentialEvent.ENCRYPTED);
            return true;
        } catch (Exception error) {
            fallbackNoticeSent = true;
            emit(CredentialEvent.KEYSTORE_FALLBACK);
            return false;
        }
    }

    public boolean usesOwnerOnlyCredentialProtection() {
        return cipher.usesOwnerOnlyProtection();
    }

    private void observeKeyAccess(boolean reading) {
        CredentialCipher.KeyAccess access = cipher.lastKeyAccess();
        if (access == CredentialCipher.KeyAccess.PLATFORM_CREATED
                || access == CredentialCipher.KeyAccess.PLATFORM_MIGRATED) {
            fallbackNoticeSent = false;
            emit(CredentialEvent.ENCRYPTED);
        } else if (access == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK) {
            if (!fallbackNoticeSent) {
                fallbackNoticeSent = true;
                emit(CredentialEvent.KEYSTORE_FALLBACK);
            }
        } else if (reading && access == CredentialCipher.KeyAccess.PLATFORM_READ) {
            synchronized (eventLock) { credentialUnlockPending = true; }
        }
    }

    private void emit(CredentialEvent event) {
        CredentialListener current;
        synchronized (eventLock) {
            current = listener;
            if (current == null) {
                pendingEvents.add(event);
                return;
            }
        }
        current.onCredentialEvent(event);
    }

    private void clearCredentialFiles() throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path path(String pluginId, String key) {
        MediaId.validateProvider(pluginId);
        if (!KEY.matcher(key != null ? key : "").matches()) {
            throw new IllegalArgumentException("invalid credential key");
        }
        Path pluginRoot = root.resolve(pluginId).normalize();
        Path target = pluginRoot.resolve(hex(sha256(key.getBytes(StandardCharsets.UTF_8))) + ".enc")
                .normalize();
        if (!pluginRoot.startsWith(root) || !target.startsWith(pluginRoot)) {
            throw new IllegalArgumentException("credential path escapes vault");
        }
        return target;
    }

    private static byte[] envelope(String pluginId, String key, byte[] value) throws IOException {
        byte[] plugin = pluginId.getBytes(StandardCharsets.UTF_8);
        byte[] name = key.getBytes(StandardCharsets.UTF_8);
        if (plugin.length > 255 || name.length > 255) throw new IOException("credential identity too long");
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                MAGIC.length + 2 + plugin.length + name.length + value.length);
        output.write(MAGIC);
        output.write(plugin.length);
        output.write(name.length);
        output.write(plugin);
        output.write(name);
        output.write(value);
        return output.toByteArray();
    }

    private static byte[] payload(byte[] plaintext, String pluginId, String key)
            throws GeneralSecurityException {
        if (plaintext.length < MAGIC.length + 2) throw new GeneralSecurityException("credential is truncated");
        for (int i = 0; i < MAGIC.length; i++) {
            if (plaintext[i] != MAGIC[i]) throw new GeneralSecurityException("credential namespace missing");
        }
        int offset = MAGIC.length;
        int pluginLength = plaintext[offset++] & 0xff;
        int keyLength = plaintext[offset++] & 0xff;
        if (plaintext.length < offset + pluginLength + keyLength) {
            throw new GeneralSecurityException("credential identity is truncated");
        }
        String actualPlugin = new String(plaintext, offset, pluginLength, StandardCharsets.UTF_8);
        offset += pluginLength;
        String actualKey = new String(plaintext, offset, keyLength, StandardCharsets.UTF_8);
        offset += keyLength;
        if (!pluginId.equals(actualPlugin) || !key.equals(actualKey)) {
            throw new GeneralSecurityException("credential belongs to a different plugin or key");
        }
        return Arrays.copyOfRange(plaintext, offset, plaintext.length);
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (GeneralSecurityException e) { throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
