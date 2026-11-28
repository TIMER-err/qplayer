package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class StagedAppUpdateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String API = "https://api.github.com/repos/TIMER-err/qplayer/releases/latest";
    private static final String BASE = "https://github.com/TIMER-err/qplayer/releases/download/v2.0.0/";
    private static final String DIGEST =
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String APK = "QPlayer-android.apk";

    @Test public void settingIsOptInAndVisibleToBothHosts() {
        SettingSpec spec = SettingsCatalog.specs().stream()
                .filter(s -> "silentUpdate".equals(s.key)).findFirst().get();
        assertEquals(Boolean.FALSE, spec.def);
        assertEquals(SettingSpec.ANY, spec.platform);
    }

    @Test public void restrictsAssetsToOfficialStableReleases() {
        for (String name : new String[] {APK, "QPlayer-windows-x64-setup.exe",
                "QPlayer-macos-arm64.dmg", "QPlayer-linux-x64.AppImage"}) {
            assertTrue(StagedAppUpdate.isTrustedAsset(BASE + name, "2.0.0"));
        }
        assertFalse(StagedAppUpdate.isTrustedAsset(BASE + APK, "2.0.1"));
        assertFalse(StagedAppUpdate.isTrustedAsset(BASE + APK + "?a=b", "2.0.0"));
        assertFalse(StagedAppUpdate.isTrustedAsset(BASE.replace("github.com", "example.org") + APK, "2.0.0"));
        assertFalse(StagedAppUpdate.isTrustedAsset(BASE + "QPlayer-windows-x64.zip", "2.0.0"));
        assertFalse(StagedAppUpdate.isTrustedAsset(BASE.replace("/v2.0.0/", "/v2.0.0-rc.1/") + APK,
                "2.0.0-rc.1"));
    }

    @Test public void acceptsCachedAssetButNeverHandsOffCorruptedOrCanceledCopy() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Path cached = dir.resolve("verified-update");
        Path destination = dir.resolve("install.apk");
        StagedAppUpdate update = new StagedAppUpdate(release(DIGEST, 3), dir);
        Files.write(cached, "abc".getBytes(StandardCharsets.UTF_8));
        update.download(() -> true); // valid cache does not touch the network
        update.copyVerified(destination, () -> true);
        assertEquals("abc", new String(Files.readAllBytes(destination), StandardCharsets.UTF_8));

        Files.write(cached, "bad".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> update.copyVerified(destination, () -> true));
        Files.write(cached, "abc".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> update.copyVerified(destination, () -> false));
        assertEquals("abc", new String(Files.readAllBytes(destination), StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test public void missingDigestOrInvalidSizeCannotStage() throws Exception {
        Path dir = tmp.newFolder().toPath();
        assertThrows(IOException.class, () -> new StagedAppUpdate(release("", 3), dir));
        assertThrows(IOException.class, () -> new StagedAppUpdate(release(DIGEST, 0), dir));
        assertThrows(IOException.class, () -> new StagedAppUpdate(release(DIGEST, Long.MAX_VALUE), dir));
    }

    private static ReleaseUpdateChecker.Update release(String digest, long size) throws IOException {
        String json = String.format(
                "{\"tag_name\":\"v2.0.0\",\"assets\":[{\"name\":\"%s\",\"browser_download_url\":\"%s\","
                        + "\"digest\":\"%s\",\"size\":%s}]}",
                APK, BASE + APK, digest, size);
        return new ReleaseUpdateChecker(url -> {
            assertEquals(API, url);
            return json;
        }).checkDirect("1.9.0", APK::equals).get();
    }
}
