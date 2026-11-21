package dev.t1m3.qplayer.store;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Versioned authenticated encryption for credentials persisted by QPlayer.
 *
 * <p>Each installation gets a random 256-bit AES data key. When the host provides
 * a {@link CredentialKeyProtector}, that key is wrapped by the platform credential
 * store and only the wrapped envelope is written to disk. Every payload uses an
 * independent 96-bit nonce and AES-GCM's 128-bit tag. Both envelopes are versioned
 * so existing owner-only plaintext key files can be migrated without rewriting or
 * exposing the cookie payload.
 */
public final class CredentialCipher {

    /** Result of the most recent data-key operation, exposed for user notices. */
    public enum KeyAccess {
        NONE,
        /** A new data key was wrapped by the platform store. */
        PLATFORM_CREATED,
        /** An existing wrapped data key was recovered from the platform store. */
        PLATFORM_READ,
        /** The legacy owner-only data key was successfully wrapped in-place. */
        PLATFORM_MIGRATED,
        /** Platform storage was absent/unavailable and owner-only storage was used. */
        OWNER_ONLY_FALLBACK
    }

    private static final byte[] MAGIC = new byte[]{'Q', 'P', 'C', 'R'};
    private static final byte[] KEY_MAGIC = new byte[]{'Q', 'P', 'K', 'S'};
    private static final byte VERSION = 1;
    private static final byte KEY_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD =
            "QPlayer credential store v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path keyFile;
    private final Path ownerOnlyMarker;
    private final CredentialKeyProtector keyProtector;
    private volatile boolean forceOwnerOnly;
    /** Avoid retrying a locked platform store for every Set-Cookie in one session. */
    private volatile boolean platformMigrationSuppressed;
    private volatile KeyAccess lastKeyAccess = KeyAccess.NONE;

    public CredentialCipher(Path keyFile) {
        this(keyFile, null);
    }

    public CredentialCipher(Path keyFile, CredentialKeyProtector keyProtector) {
        if (keyFile == null) throw new IllegalArgumentException("keyFile");
        this.keyFile = keyFile;
        this.ownerOnlyMarker = keyFile.resolveSibling(
                keyFile.getFileName().toString() + ".owner-only");
        this.keyProtector = keyProtector;
        this.forceOwnerOnly = Files.exists(ownerOnlyMarker);
    }

    public KeyAccess lastKeyAccess() {
        return lastKeyAccess;
    }

    public byte[] encrypt(byte[] plaintext) throws IOException, GeneralSecurityException {
        if (plaintext == null) throw new IllegalArgumentException("plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] key = loadOrCreateKey();
        try {
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plaintext);

        byte[] envelope = new byte[MAGIC.length + 2 + nonce.length + encrypted.length];
        int offset = 0;
        System.arraycopy(MAGIC, 0, envelope, offset, MAGIC.length);
        offset += MAGIC.length;
        envelope[offset++] = VERSION;
        envelope[offset++] = (byte) nonce.length;
        System.arraycopy(nonce, 0, envelope, offset, nonce.length);
        offset += nonce.length;
        System.arraycopy(encrypted, 0, envelope, offset, encrypted.length);
        return envelope;
    }

    public byte[] decrypt(byte[] envelope) throws IOException, GeneralSecurityException {
        return decrypt(envelope, false);
    }

    /** Explicit user retry; platform stores may allow an interactive unlock prompt. */
    public byte[] decryptInteractively(byte[] envelope)
            throws IOException, GeneralSecurityException {
        return decrypt(envelope, true);
    }

    private byte[] decrypt(byte[] envelope, boolean interactive)
            throws IOException, GeneralSecurityException {
        if (envelope == null || envelope.length < MAGIC.length + 2 + NONCE_BYTES + 16) {
            throw new IOException("credential envelope is truncated");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (envelope[i] != MAGIC[i]) throw new IOException("unknown credential format");
        }
        int offset = MAGIC.length;
        int version = envelope[offset++] & 0xff;
        if (version != VERSION) throw new IOException("unsupported credential version " + version);
        int nonceLength = envelope[offset++] & 0xff;
        if (nonceLength != NONCE_BYTES || envelope.length < offset + nonceLength + 16) {
            throw new IOException("invalid credential envelope");
        }

        byte[] nonce = Arrays.copyOfRange(envelope, offset, offset + nonceLength);
        byte[] encrypted = Arrays.copyOfRange(envelope, offset + nonceLength, envelope.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] key = loadExistingKey(interactive);
        try {
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        cipher.updateAAD(AAD);
        try {
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException e) {
            throw new GeneralSecurityException("credential authentication failed", e);
        }
    }

    private byte[] loadOrCreateKey() throws IOException, GeneralSecurityException {
        if (Files.exists(keyFile)) {
            // A crash after persisting the explicit fallback marker but before
            // removing the old system-wrapped envelope must not re-enter the store.
            byte[] stored = forceOwnerOnly ? Files.readAllBytes(keyFile) : null;
            if (stored != null && startsWith(stored, KEY_MAGIC)) {
                Files.delete(keyFile);
            } else {
                return loadExistingKey();
            }
        }
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        if (keyProtector != null && !forceOwnerOnly) {
            try {
                writeProtectedKey(key);
                lastKeyAccess = KeyAccess.PLATFORM_CREATED;
            } catch (IOException | GeneralSecurityException e) {
                // A first-run key-store outage must not prevent login entirely. The
                // owner-only legacy representation is deliberately recognizable and
                // will be migrated on the next successful launch.
                dev.t1m3.qplayer.util.Logger.warn(
                        "platform credential store unavailable; using owner-only fallback: {}",
                        e.getMessage());
                platformMigrationSuppressed = true;
                writeLegacyKey(key);
                lastKeyAccess = KeyAccess.OWNER_ONLY_FALLBACK;
            }
        } else {
            writeLegacyKey(key);
            lastKeyAccess = KeyAccess.OWNER_ONLY_FALLBACK;
        }
        return key;
    }

    private byte[] loadExistingKey() throws IOException, GeneralSecurityException {
        return loadExistingKey(false);
    }

    private byte[] loadExistingKey(boolean interactive)
            throws IOException, GeneralSecurityException {
        if (!Files.exists(keyFile)) throw new IOException("credential key is missing");
        StorageFiles.restrictOwnerOnly(keyFile);
        byte[] stored = Files.readAllBytes(keyFile);
        if (startsWith(stored, KEY_MAGIC)) {
            byte[] key = readProtectedKey(stored, interactive);
            lastKeyAccess = KeyAccess.PLATFORM_READ;
            return key;
        }

        // v1 used an owner-only base64 file. Use it for this read, then atomically
        // replace it with a platform-protected envelope. Migration failure leaves
        // the working old file untouched so it can be retried next launch.
        final byte[] key;
        try {
            key = Base64.getDecoder().decode(
                    new String(stored, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("credential key is invalid", e);
        }
        if (key.length != KEY_BYTES) throw new IOException("credential key has invalid length");
        if (keyProtector != null && !forceOwnerOnly && !platformMigrationSuppressed) {
            try {
                writeProtectedKey(key);
                lastKeyAccess = KeyAccess.PLATFORM_MIGRATED;
            } catch (IOException | GeneralSecurityException e) {
                platformMigrationSuppressed = true;
                lastKeyAccess = KeyAccess.OWNER_ONLY_FALLBACK;
                dev.t1m3.qplayer.util.Logger.warn(
                        "credential key migration to {} will be retried: {}",
                        keyProtector.id(), e.getMessage());
            }
        } else {
            lastKeyAccess = KeyAccess.OWNER_ONLY_FALLBACK;
        }
        return key;
    }

    private byte[] readProtectedKey(byte[] stored, boolean interactive)
            throws IOException, GeneralSecurityException {
        int offset = KEY_MAGIC.length;
        if (stored.length < offset + 1 + 1 + 4) {
            throw new IOException("protected credential key is truncated");
        }
        int version = stored[offset++] & 0xff;
        if (version != KEY_VERSION) {
            throw new IOException("unsupported protected credential key version " + version);
        }
        int idLength = stored[offset++] & 0xff;
        if (idLength == 0 || stored.length < offset + idLength + 4) {
            throw new IOException("invalid protected credential key envelope");
        }
        String providerId = new String(stored, offset, idLength, StandardCharsets.UTF_8);
        offset += idLength;
        int payloadLength = ((stored[offset++] & 0xff) << 24)
                | ((stored[offset++] & 0xff) << 16)
                | ((stored[offset++] & 0xff) << 8)
                | (stored[offset++] & 0xff);
        if (payloadLength < 0 || payloadLength != stored.length - offset) {
            throw new IOException("invalid protected credential key payload");
        }
        if (keyProtector == null) {
            throw new IOException("credential key requires platform store " + providerId);
        }
        if (!providerId.equals(keyProtector.id())) {
            throw new IOException("credential key belongs to platform store " + providerId
                    + ", not " + keyProtector.id());
        }
        byte[] protectedKey = Arrays.copyOfRange(stored, offset, stored.length);
        byte[] key = interactive
                ? keyProtector.unprotectInteractively(protectedKey)
                : keyProtector.unprotect(protectedKey);
        if (key == null || key.length != KEY_BYTES) {
            throw new IOException("platform credential store returned an invalid key");
        }
        return key;
    }

    private void writeProtectedKey(byte[] key)
            throws IOException, GeneralSecurityException {
        writeProtectedKey(key, false);
    }

    private void writeProtectedKey(byte[] key, boolean interactive)
            throws IOException, GeneralSecurityException {
        byte[] provider = keyProtector.id().getBytes(StandardCharsets.UTF_8);
        if (provider.length == 0 || provider.length > 255) {
            throw new IOException("invalid credential key protector id");
        }
        byte[] keyCopy = Arrays.copyOf(key, key.length);
        final byte[] protectedKey;
        try {
            protectedKey = interactive
                    ? keyProtector.protectInteractively(keyCopy)
                    : keyProtector.protect(keyCopy);
        } finally {
            Arrays.fill(keyCopy, (byte) 0);
        }
        if (protectedKey == null) throw new IOException("platform credential store returned null");
        byte[] envelope = new byte[KEY_MAGIC.length + 1 + 1 + provider.length + 4
                + protectedKey.length];
        int offset = 0;
        System.arraycopy(KEY_MAGIC, 0, envelope, offset, KEY_MAGIC.length);
        offset += KEY_MAGIC.length;
        envelope[offset++] = KEY_VERSION;
        envelope[offset++] = (byte) provider.length;
        System.arraycopy(provider, 0, envelope, offset, provider.length);
        offset += provider.length;
        int length = protectedKey.length;
        envelope[offset++] = (byte) (length >>> 24);
        envelope[offset++] = (byte) (length >>> 16);
        envelope[offset++] = (byte) (length >>> 8);
        envelope[offset++] = (byte) length;
        System.arraycopy(protectedKey, 0, envelope, offset, length);
        StorageFiles.writeBytesAtomic(keyFile, envelope, true);
        Files.deleteIfExists(ownerOnlyMarker);
    }

    private void writeLegacyKey(byte[] key) throws IOException {
        StorageFiles.writeUtf8Atomic(keyFile,
                Base64.getEncoder().encodeToString(key), true);
    }

    /**
     * Explicitly abandon an inaccessible system-wrapped key and keep future keys
     * in the owner-only fallback format. The marker makes this choice persistent;
     * otherwise the next launch would immediately attempt platform migration again.
     */
    public synchronized void forceOwnerOnlyFallback() throws IOException {
        StorageFiles.writeUtf8Atomic(ownerOnlyMarker, "owner-only-v1", true);
        forceOwnerOnly = true;
        Files.deleteIfExists(keyFile);
    }

    /**
     * Discard an inaccessible data-key envelope and restore the default platform
     * protection policy. The next encryption creates a fresh data key and asks the
     * configured system credential store to protect it.
     */
    public synchronized void resetForPlatformProtection() throws IOException {
        Files.deleteIfExists(keyFile);
        Files.deleteIfExists(ownerOnlyMarker);
        forceOwnerOnly = false;
        platformMigrationSuppressed = false;
        lastKeyAccess = KeyAccess.NONE;
    }

    /**
     * Verify that the existing platform-wrapped key is immediately available.
     * This is deliberately non-interactive and never modifies either envelope.
     */
    public synchronized void verifyPlatformProtectionAvailable()
            throws IOException, GeneralSecurityException {
        if (keyProtector == null) {
            throw new IOException("no platform credential store is available");
        }
        if (!Files.exists(keyFile)) throw new IOException("credential key is missing");
        StorageFiles.restrictOwnerOnly(keyFile);
        byte[] stored = Files.readAllBytes(keyFile);
        if (!startsWith(stored, KEY_MAGIC)) {
            throw new IOException("credential key is not protected by the platform store");
        }
        byte[] key = readProtectedKey(stored, false);
        Arrays.fill(key, (byte) 0);
    }

    /** Whether credentials currently rely on the owner-readable local key file. */
    public synchronized boolean usesOwnerOnlyProtection() {
        if (forceOwnerOnly || Files.exists(ownerOnlyMarker)) return true;
        if (!Files.exists(keyFile)) return false;
        try {
            return !startsWith(Files.readAllBytes(keyFile), KEY_MAGIC);
        } catch (IOException e) {
            return forceOwnerOnly;
        }
    }

    /**
     * Re-wrap an existing owner-only data key with the platform credential store.
     * The local representation and fallback marker are left untouched unless the
     * platform write succeeds, so cancelling an unlock prompt cannot lose login.
     */
    public synchronized void enablePlatformProtectionInteractively()
            throws IOException, GeneralSecurityException {
        if (keyProtector == null) {
            throw new IOException("no platform credential store is available");
        }
        if (!Files.exists(keyFile)) {
            Files.deleteIfExists(ownerOnlyMarker);
            forceOwnerOnly = false;
            platformMigrationSuppressed = false;
            lastKeyAccess = KeyAccess.NONE;
            return;
        }

        StorageFiles.restrictOwnerOnly(keyFile);
        byte[] stored = Files.readAllBytes(keyFile);
        if (startsWith(stored, KEY_MAGIC)) {
            byte[] key = readProtectedKey(stored, true);
            Arrays.fill(key, (byte) 0);
            Files.deleteIfExists(ownerOnlyMarker);
            forceOwnerOnly = false;
            platformMigrationSuppressed = false;
            return;
        }

        final byte[] key;
        try {
            key = Base64.getDecoder().decode(
                    new String(stored, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("credential key is invalid", e);
        }
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw new IOException("credential key has invalid length");
        }
        try {
            writeProtectedKey(key, true);
            forceOwnerOnly = false;
            platformMigrationSuppressed = false;
            lastKeyAccess = KeyAccess.PLATFORM_MIGRATED;
        } catch (IOException | GeneralSecurityException e) {
            platformMigrationSuppressed = true;
            throw e;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }
}
