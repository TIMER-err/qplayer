package dev.t1m3.qplayer.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CredentialCipherTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripsWithoutPersistingPlaintext() throws Exception {
        Path directory = temporaryFolder.newFolder("round-trip").toPath();
        CredentialCipher cipher = new CredentialCipher(directory.resolve("credential.key"));
        byte[] plaintext = "{\"MUSIC_U\":\"secret-cookie\"}"
                .getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = cipher.encrypt(plaintext);

        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1)
                .contains("secret-cookie"));
        assertArrayEquals(plaintext, cipher.decrypt(encrypted));
        assertTrue(Files.exists(directory.resolve("credential.key")));
    }

    @Test
    public void everyWriteUsesANewNonce() throws Exception {
        Path directory = temporaryFolder.newFolder("nonce").toPath();
        CredentialCipher cipher = new CredentialCipher(directory.resolve("credential.key"));
        byte[] plaintext = "same payload".getBytes(StandardCharsets.UTF_8);

        byte[] first = cipher.encrypt(plaintext);
        byte[] second = cipher.encrypt(plaintext);

        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(plaintext, cipher.decrypt(first));
        assertArrayEquals(plaintext, cipher.decrypt(second));
    }

    @Test
    public void rejectsTamperedCiphertext() throws Exception {
        Path directory = temporaryFolder.newFolder("tamper").toPath();
        CredentialCipher cipher = new CredentialCipher(directory.resolve("credential.key"));
        byte[] encrypted = cipher.encrypt("cookie".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 0x01;

        try {
            cipher.decrypt(encrypted);
            fail("tampered credentials must fail authentication");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
    }

    @Test
    public void ciphertextCannotBeOpenedWithoutItsInstallationKey() throws Exception {
        Path first = temporaryFolder.newFolder("first-install").toPath();
        Path second = temporaryFolder.newFolder("second-install").toPath();
        CredentialCipher writer = new CredentialCipher(first.resolve("credential.key"));
        CredentialCipher reader = new CredentialCipher(second.resolve("credential.key"));
        byte[] encrypted = writer.encrypt("cookie".getBytes(StandardCharsets.UTF_8));

        try {
            reader.decrypt(encrypted);
            fail("a missing installation key must not be regenerated while decrypting");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("missing"));
            assertFalse(Files.exists(second.resolve("credential.key")));
        }
    }

    @Test
    public void platformProtectorKeepsRawKeyOutOfTheKeyFile() throws Exception {
        Path directory = temporaryFolder.newFolder("protected-key").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialKeyProtector protector = new XorProtector("test-store", (byte) 0x5a);
        CredentialCipher writer = new CredentialCipher(keyFile, protector);
        byte[] plaintext = "protected cookie".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = writer.encrypt(plaintext);
        byte[] storedKey = Files.readAllBytes(keyFile);

        assertTrue(writer.lastKeyAccess() == CredentialCipher.KeyAccess.PLATFORM_CREATED);
        assertTrue(new String(storedKey, StandardCharsets.ISO_8859_1)
                .startsWith("QPKS"));
        assertFalse(new String(storedKey, StandardCharsets.UTF_8).matches("[A-Za-z0-9+/=]+"));
        CredentialCipher reader = new CredentialCipher(keyFile, protector);
        assertArrayEquals(plaintext, reader.decrypt(encrypted));
        assertTrue(reader.lastKeyAccess() == CredentialCipher.KeyAccess.PLATFORM_READ);
    }

    @Test
    public void migratesExistingOwnerOnlyKeyWithoutReencryptingCookies() throws Exception {
        Path directory = temporaryFolder.newFolder("key-migration").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialCipher legacy = new CredentialCipher(keyFile);
        byte[] plaintext = "existing login".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = legacy.encrypt(plaintext);
        assertFalse(new String(Files.readAllBytes(keyFile), StandardCharsets.ISO_8859_1)
                .startsWith("QPKS"));

        CredentialCipher migrated = new CredentialCipher(
                keyFile, new XorProtector("test-store", (byte) 0x31));

        assertArrayEquals(plaintext, migrated.decrypt(encrypted));
        assertTrue(migrated.lastKeyAccess() == CredentialCipher.KeyAccess.PLATFORM_MIGRATED);
        assertTrue(new String(Files.readAllBytes(keyFile), StandardCharsets.ISO_8859_1)
                .startsWith("QPKS"));
        assertArrayEquals(plaintext, migrated.decrypt(encrypted));
        assertTrue(migrated.lastKeyAccess() == CredentialCipher.KeyAccess.PLATFORM_READ);
    }

    @Test
    public void rejectsAProtectedKeyFromAnotherPlatformStore() throws Exception {
        Path directory = temporaryFolder.newFolder("wrong-store").toPath();
        Path keyFile = directory.resolve("credential.key");
        byte[] encrypted = new CredentialCipher(
                keyFile, new XorProtector("first", (byte) 1))
                .encrypt("cookie".getBytes(StandardCharsets.UTF_8));

        try {
            new CredentialCipher(keyFile, new XorProtector("second", (byte) 1))
                    .decrypt(encrypted);
            fail("a different platform store must not open the key envelope");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("first"));
        }
    }

    @Test
    public void firstRunFallsBackWhenPlatformStoreIsUnavailable() throws Exception {
        Path directory = temporaryFolder.newFolder("store-unavailable").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialCipher cipher = new CredentialCipher(
                keyFile, new FailingProtector("unavailable", true, false));

        byte[] encrypted = cipher.encrypt("cookie".getBytes(StandardCharsets.UTF_8));

        assertTrue(cipher.lastKeyAccess()
                == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK);
        assertFalse(new String(Files.readAllBytes(keyFile), StandardCharsets.ISO_8859_1)
                .startsWith("QPKS"));
        assertArrayEquals("cookie".getBytes(StandardCharsets.UTF_8),
                new CredentialCipher(keyFile).decrypt(encrypted));
    }

    @Test
    public void failedPlatformReadNeverReplacesAnExistingProtectedKey() throws Exception {
        Path directory = temporaryFolder.newFolder("read-unavailable").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialKeyProtector working = new XorProtector("test-store", (byte) 7);
        byte[] encrypted = new CredentialCipher(keyFile, working)
                .encrypt("cookie".getBytes(StandardCharsets.UTF_8));
        byte[] originalKeyEnvelope = Files.readAllBytes(keyFile);

        try {
            new CredentialCipher(keyFile,
                    new FailingProtector("test-store", false, true)).decrypt(encrypted);
            fail("an unavailable platform store must fail closed");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }
        assertArrayEquals(originalKeyEnvelope, Files.readAllBytes(keyFile));
    }

    @Test
    public void explicitFallbackRemainsOwnerOnlyAcrossRestarts() throws Exception {
        Path directory = temporaryFolder.newFolder("explicit-fallback").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialKeyProtector protector = new XorProtector("test-store", (byte) 9);
        CredentialCipher cipher = new CredentialCipher(keyFile, protector);
        cipher.encrypt("old".getBytes(StandardCharsets.UTF_8));

        cipher.forceOwnerOnlyFallback();
        byte[] encrypted = cipher.encrypt("new".getBytes(StandardCharsets.UTF_8));

        assertTrue(cipher.lastKeyAccess()
                == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK);
        assertTrue(Files.exists(directory.resolve("credential.key.owner-only")));
        CredentialCipher restarted = new CredentialCipher(keyFile, protector);
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8),
                restarted.decrypt(encrypted));
        assertTrue(restarted.lastKeyAccess()
                == CredentialCipher.KeyAccess.OWNER_ONLY_FALLBACK);
        assertFalse(new String(Files.readAllBytes(keyFile), StandardCharsets.ISO_8859_1)
                .startsWith("QPKS"));
    }

    @Test
    public void interactiveRetryUsesTheInteractiveProviderPath() throws Exception {
        Path directory = temporaryFolder.newFolder("interactive-retry").toPath();
        Path keyFile = directory.resolve("credential.key");
        InteractiveProtector protector = new InteractiveProtector();
        CredentialCipher cipher = new CredentialCipher(keyFile, protector);
        byte[] encrypted = cipher.encrypt("cookie".getBytes(StandardCharsets.UTF_8));

        try {
            new CredentialCipher(keyFile, protector).decrypt(encrypted);
            fail("non-interactive recovery must fail in this fixture");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("locked"));
        }
        assertArrayEquals("cookie".getBytes(StandardCharsets.UTF_8),
                new CredentialCipher(keyFile, protector).decryptInteractively(encrypted));
    }

    @Test
    public void encryptedReloginResetRestoresPlatformProtection() throws Exception {
        Path directory = temporaryFolder.newFolder("encrypted-relogin").toPath();
        Path keyFile = directory.resolve("credential.key");
        Path marker = directory.resolve("credential.key.owner-only");
        CredentialKeyProtector protector = new XorProtector("test-store", (byte) 13);
        CredentialCipher cipher = new CredentialCipher(keyFile, protector);

        cipher.forceOwnerOnlyFallback();
        cipher.encrypt("fallback".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(marker));

        cipher.resetForPlatformProtection();
        byte[] encrypted = cipher.encrypt("new login".getBytes(StandardCharsets.UTF_8));

        assertFalse(Files.exists(marker));
        assertTrue(cipher.lastKeyAccess() == CredentialCipher.KeyAccess.PLATFORM_CREATED);
        assertTrue(new String(Files.readAllBytes(keyFile), StandardCharsets.ISO_8859_1)
                .startsWith("QPKS"));
        assertArrayEquals("new login".getBytes(StandardCharsets.UTF_8),
                new CredentialCipher(keyFile, protector).decrypt(encrypted));
    }

    @Test
    public void ownerOnlyKeyCanBeMigratedBackWithoutReencryptingCredentials()
            throws Exception {
        Path directory = temporaryFolder.newFolder("reenable-platform").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialKeyProtector protector = new XorProtector("test-store", (byte) 17);
        CredentialCipher cipher = new CredentialCipher(keyFile, protector);
        cipher.forceOwnerOnlyFallback();
        byte[] encrypted = cipher.encrypt("existing login".getBytes(StandardCharsets.UTF_8));
        assertTrue(cipher.usesOwnerOnlyProtection());

        cipher.enablePlatformProtectionInteractively();

        assertFalse(cipher.usesOwnerOnlyProtection());
        assertTrue(cipher.lastKeyAccess() == CredentialCipher.KeyAccess.PLATFORM_MIGRATED);
        assertArrayEquals("existing login".getBytes(StandardCharsets.UTF_8),
                new CredentialCipher(keyFile, protector).decrypt(encrypted));
    }

    @Test
    public void failedReenableKeepsOwnerOnlyKeyAndCredentialsReadable() throws Exception {
        Path directory = temporaryFolder.newFolder("reenable-failure").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialCipher fallback = new CredentialCipher(keyFile);
        fallback.forceOwnerOnlyFallback();
        byte[] encrypted = fallback.encrypt("existing login".getBytes(StandardCharsets.UTF_8));
        byte[] originalKey = Files.readAllBytes(keyFile);
        CredentialCipher unavailable = new CredentialCipher(keyFile,
                new FailingProtector("test-store", true, false));

        try {
            unavailable.enablePlatformProtectionInteractively();
            fail("an unavailable platform store must not report migration success");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }

        assertTrue(unavailable.usesOwnerOnlyProtection());
        assertArrayEquals(originalKey, Files.readAllBytes(keyFile));
        assertArrayEquals("existing login".getBytes(StandardCharsets.UTF_8),
                new CredentialCipher(keyFile).decrypt(encrypted));
    }

    @Test
    public void lockedStoreIsNotRetriedForEveryCredentialWrite() throws Exception {
        Path directory = temporaryFolder.newFolder("migration-backoff").toPath();
        Path keyFile = directory.resolve("credential.key");
        CountingFailingProtector protector = new CountingFailingProtector();
        CredentialCipher cipher = new CredentialCipher(keyFile, protector);

        cipher.encrypt("first cookie".getBytes(StandardCharsets.UTF_8));
        cipher.encrypt("second cookie".getBytes(StandardCharsets.UTF_8));
        cipher.encrypt("third cookie".getBytes(StandardCharsets.UTF_8));

        assertTrue(protector.protectCalls == 1);
        assertTrue(cipher.usesOwnerOnlyProtection());
    }

    @Test
    public void reloginPreflightDoesNotModifyFilesWhenStoreIsLocked() throws Exception {
        Path directory = temporaryFolder.newFolder("relogin-preflight").toPath();
        Path keyFile = directory.resolve("credential.key");
        CredentialKeyProtector working = new XorProtector("test-store", (byte) 21);
        CredentialCipher writer = new CredentialCipher(keyFile, working);
        writer.encrypt("existing login".getBytes(StandardCharsets.UTF_8));
        byte[] originalKey = Files.readAllBytes(keyFile);

        try {
            new CredentialCipher(keyFile,
                    new FailingProtector("test-store", false, true))
                    .verifyPlatformProtectionAvailable();
            fail("locked platform store must fail relogin preflight");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }

        assertArrayEquals(originalKey, Files.readAllBytes(keyFile));
    }

    private static final class XorProtector implements CredentialKeyProtector {
        private final String id;
        private final byte mask;

        private XorProtector(String id, byte mask) {
            this.id = id;
            this.mask = mask;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public byte[] protect(byte[] key) {
            return xor(key);
        }

        @Override
        public byte[] unprotect(byte[] protectedKey) {
            return xor(protectedKey);
        }

        private byte[] xor(byte[] value) {
            byte[] result = Arrays.copyOf(value, value.length);
            for (int i = 0; i < result.length; i++) result[i] ^= mask;
            return result;
        }
    }

    private static final class FailingProtector implements CredentialKeyProtector {
        private final String id;
        private final boolean failProtect;
        private final boolean failUnprotect;

        FailingProtector(String id, boolean failProtect, boolean failUnprotect) {
            this.id = id;
            this.failProtect = failProtect;
            this.failUnprotect = failUnprotect;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public byte[] protect(byte[] key) throws java.io.IOException {
            if (failProtect) throw new java.io.IOException("platform store unavailable");
            return Arrays.copyOf(key, key.length);
        }

        @Override
        public byte[] unprotect(byte[] protectedKey) throws java.io.IOException {
            if (failUnprotect) throw new java.io.IOException("platform store unavailable");
            return Arrays.copyOf(protectedKey, protectedKey.length);
        }
    }

    private static final class CountingFailingProtector implements CredentialKeyProtector {
        int protectCalls;

        @Override
        public String id() {
            return "counting-locked-store";
        }

        @Override
        public byte[] protect(byte[] key) throws java.io.IOException {
            protectCalls++;
            throw new java.io.IOException("platform store unavailable");
        }

        @Override
        public byte[] unprotect(byte[] protectedKey) {
            return Arrays.copyOf(protectedKey, protectedKey.length);
        }
    }

    private static final class InteractiveProtector implements CredentialKeyProtector {
        private static final byte MASK = 0x25;

        @Override
        public String id() {
            return "interactive-test";
        }

        @Override
        public byte[] protect(byte[] key) {
            return xor(key);
        }

        @Override
        public byte[] unprotect(byte[] protectedKey) throws java.io.IOException {
            throw new java.io.IOException("key store locked");
        }

        @Override
        public byte[] unprotectInteractively(byte[] protectedKey) {
            return xor(protectedKey);
        }

        private static byte[] xor(byte[] value) {
            byte[] result = Arrays.copyOf(value, value.length);
            for (int i = 0; i < result.length; i++) result[i] ^= MASK;
            return result;
        }
    }
}
