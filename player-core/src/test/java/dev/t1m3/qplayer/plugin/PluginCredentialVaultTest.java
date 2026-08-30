package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.store.CredentialCipher;
import dev.t1m3.qplayer.store.CredentialKeyProtector;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginCredentialVaultTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void keepsCredentialsInPluginNamespaces() throws Exception {
        PluginCredentialVault vault = new PluginCredentialVault(
                temporary.getRoot().toPath().resolve("credentials"),
                new CredentialCipher(temporary.getRoot().toPath().resolve("master.key")));
        byte[] first = "first-cookie".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-cookie".getBytes(StandardCharsets.UTF_8);

        vault.put("first", "cookie", first);
        vault.put("second", "cookie", second);

        assertArrayEquals(first, vault.get("first", "cookie"));
        assertArrayEquals(second, vault.get("second", "cookie"));
        assertNull(vault.get("first", "missing"));
        assertFalse(vault.delete("first", "missing"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCredentialTraversal() throws Exception {
        PluginCredentialVault vault = new PluginCredentialVault(
                temporary.getRoot().toPath().resolve("credentials"),
                new CredentialCipher(temporary.getRoot().toPath().resolve("master.key")));
        vault.put("first", "../second", new byte[]{1});
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnboundedCredentialValues() throws Exception {
        PluginCredentialVault vault = new PluginCredentialVault(
                temporary.getRoot().toPath().resolve("credentials"),
                new CredentialCipher(temporary.getRoot().toPath().resolve("master.key")));
        vault.put("first", "cookie", new byte[1024 * 1024 + 1]);
    }

    @Test
    public void deliversStartupProtectionEventsAfterUiListenerAttaches() throws Exception {
        Path directory = temporary.newFolder("queued-event").toPath();
        PluginCredentialVault vault = new PluginCredentialVault(
                directory.resolve("credentials"),
                new CredentialCipher(directory.resolve("master.key")));

        vault.put("first", "cookie", new byte[]{1});
        List<PluginCredentialVault.CredentialEvent> events = new ArrayList<>();
        vault.setCredentialListener(events::add);

        assertTrue(events.contains(PluginCredentialVault.CredentialEvent.KEYSTORE_FALLBACK));
        assertTrue(vault.usesOwnerOnlyCredentialProtection());
    }

    @Test
    public void successfulPlatformReadIsConsumedOnlyAfterAccountValidation() throws Exception {
        Path directory = temporary.newFolder("platform-read").toPath();
        Path key = directory.resolve("master.key");
        CredentialKeyProtector protector = new ReversibleProtector();
        PluginCredentialVault writer = new PluginCredentialVault(
                directory.resolve("credentials"), new CredentialCipher(key, protector));
        writer.put("first", "cookie", "secret".getBytes(StandardCharsets.UTF_8));

        PluginCredentialVault reader = new PluginCredentialVault(
                directory.resolve("credentials"), new CredentialCipher(key, protector));
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8),
                reader.get("first", "cookie"));
        assertTrue(reader.consumeCredentialUnlock());
        assertFalse(reader.consumeCredentialUnlock());
    }

    @Test
    public void explicitFallbackDeletesUnreadablePluginCiphertext() throws Exception {
        Path directory = temporary.newFolder("explicit-fallback").toPath();
        Path root = directory.resolve("credentials");
        PluginCredentialVault vault = new PluginCredentialVault(root,
                new CredentialCipher(directory.resolve("master.key"),
                        new ReversibleProtector()));
        vault.put("first", "cookie", new byte[]{1, 2, 3});

        assertTrue(vault.fallbackUnreadableCredentials());
        assertFalse(java.nio.file.Files.exists(root));
        assertTrue(vault.usesOwnerOnlyCredentialProtection());
    }

    private static final class ReversibleProtector implements CredentialKeyProtector {
        @Override public String id() { return "test-plugin-store"; }

        @Override public byte[] protect(byte[] key) {
            byte[] result = Arrays.copyOf(key, key.length);
            for (int i = 0; i < result.length; i++) result[i] ^= 0x55;
            return result;
        }

        @Override public byte[] unprotect(byte[] protectedKey) {
            return protect(protectedKey);
        }
    }
}
