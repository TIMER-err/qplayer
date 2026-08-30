package dev.t1m3.qplayer.netease;

import dev.t1m3.qplayer.store.CredentialCipher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NeteaseClientMigrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void logoutRemovesMigratedAndPlaintextLegacyCredentials() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path encrypted = root.resolve("netease-cookies.enc");
        Path plaintext = root.resolve("netease-cookies.json");
        Files.write(plaintext, "{\"MUSIC_U\":\"legacy-token\"}"
                .getBytes(StandardCharsets.UTF_8));

        NeteaseClient client = new NeteaseClient(encrypted, plaintext,
                new CredentialCipher(root.resolve("credential-encryption.key")));

        assertTrue(client.isLoggedIn());
        assertTrue(Files.isRegularFile(encrypted));
        assertFalse(Files.exists(plaintext));

        // A stale plaintext file can coexist after an interrupted old upgrade;
        // logout must remove both representations, not only the active one.
        Files.write(plaintext, "{}".getBytes(StandardCharsets.UTF_8));
        client.logout();

        assertFalse(client.isLoggedIn());
        assertFalse(Files.exists(encrypted));
        assertFalse(Files.exists(plaintext));
    }
}
