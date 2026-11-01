package dev.t1m3.qplayer.bridge;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.plugin.PluginInstaller;
import dev.t1m3.qplayer.plugin.PluginManifest;
import dev.t1m3.qplayer.plugin.PluginPackageVerifier;
import dev.t1m3.qplayer.plugin.PluginPermission;
import dev.t1m3.qplayer.plugin.PluginRegistry;
import dev.t1m3.qplayer.plugin.SourceAccountRow;
import dev.t1m3.qplayer.qml.QmlRuntimeLock;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline source runtimes exercise the actual controller's account projections (issue #55). */
public class PlayerControllerAccountTest {
    private static final String PROFILE =
            "{loggedIn:true,id:'offline',displayName:'Fixture user',level:7,"
                    + "membershipTier:10,signature:'Offline profile'}";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule public final ControllerTestLifecycle lifecycle = new ControllerTestLifecycle();
    private String oldBase;
    private String oldCache;
    private String oldDataDir;
    private Path base;
    private PlayerController controller;

    @Before
    public void isolateData() throws Exception {
        oldBase = AppDirs.base();
        oldCache = AppDirs.cacheBase();
        oldDataDir = System.getProperty("qplayer.data.dir");
        base = temporaryFolder.newFolder("account").toPath();
        System.setProperty("qplayer.data.dir", base.toString());
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
    }

    @After
    public void closeController() {
        try {
            if (controller != null) lifecycle.close(controller);
        } finally {
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
            if (oldDataDir == null) System.clearProperty("qplayer.data.dir");
            else System.setProperty("qplayer.data.dir", oldDataDir);
        }
    }

    @Test
    public void laterSignedOutSnapshotClearsTheEarlierHeader() throws Exception {
        install("fixture", "if (calls === 1) return " + PROFILE + "; return {loggedIn:false};");
        startController();
        await(() -> row("fixture") != null && !row("fixture").loggedIn);

        assertFalse(row("fixture").accountError);
        assertPrimaryProfileMatchesRow();
        assertFalse(controller.loggedIn.peek());
        assertEquals("", controller.userName.peek());
        assertEquals(Integer.valueOf(0), controller.userLevel.peek());
    }

    @Test
    public void failedAccountCheckKeepsTheConfirmedSessionButIsNotSignedOut() throws Exception {
        install("fixture", "if (calls === 1) return " + PROFILE + "; throw new Error('offline check failed');");
        startController();
        await(() -> row("fixture") != null && row("fixture").accountError);

        assertTrue(row("fixture").loggedIn);
        assertEquals("Fixture user", row("fixture").displayName);
        assertPrimaryProfileMatchesRow();
        assertTrue(controller.loggedIn.peek());
        assertEquals(Integer.valueOf(7), controller.userLevel.peek());
    }

    @Test
    public void firstAccountFailurePublishesUnknownStatusWithoutInventingASession() throws Exception {
        install("fixture", "throw new Error('offline check failed');");
        startController();
        await(() -> row("fixture") != null && row("fixture").accountError);

        assertFalse(row("fixture").loggedIn);
        assertPrimaryProfileMatchesRow();
        assertFalse(controller.loggedIn.peek());
        assertEquals("", controller.userName.peek());
    }

    @Test
    public void explicitSignedOutResponseReplacesAnUnavailableSession() throws Exception {
        install("fixture", "if (calls === 1) return " + PROFILE + ";"
                + "if (calls === 2) throw new Error('offline check failed'); return {loggedIn:false};");
        startController();
        await(() -> row("fixture") != null && row("fixture").accountError);
        refreshAccounts();
        await(() -> !row("fixture").accountError);

        assertPrimaryProfileMatchesRow();
        assertFalse(controller.loggedIn.peek());
        assertEquals("", controller.userSignature.peek());
    }

    @Test
    public void logoutRemainsAuthoritativeWhenItsAccountRecheckFails() throws Exception {
        install("fixture", "if (signedOut) throw new Error('offline check failed'); return " + PROFILE + ";");
        startController();
        await(() -> row("fixture") != null && row("fixture").loggedIn);
        controller.logoutSource("fixture");
        await(() -> row("fixture").accountError);

        assertFalse(row("fixture").loggedIn);
        assertEquals("", row("fixture").displayName);
        assertPrimaryProfileMatchesRow();
        assertFalse(controller.loggedIn.peek());
    }

    @Test
    public void switchingPrimaryAndLoggingOutAnotherSourceKeepsTheNewPrimaryProfile() throws Exception {
        install("fixture", "return signedOut ? {loggedIn:false} : " + PROFILE + ";");
        install("second", "return {loggedIn:true,id:'second',displayName:'Second user',level:3};");
        startController();
        await(() -> row("fixture") != null && row("second") != null);
        controller.setPrimarySourcePlugin("second");
        await(() -> row("second").primary && "Second user".equals(controller.userName.peek()));
        controller.logoutSource("fixture");
        await(() -> !row("fixture").loggedIn);

        assertPrimaryProfileMatchesRow();
        assertEquals("Second user", controller.userName.peek());
        assertEquals(Integer.valueOf(3), controller.userLevel.peek());
    }

    private void startController() {
        controller = new PlayerController(new SilentBackend(), track -> {}, NeteaseClient.INSTANCE);
    }

    private SourceAccountRow row(String provider) {
        for (SourceAccountRow row : controller.sourceAccounts.peek()) {
            if (provider.equals(row.providerId)) return row;
        }
        return null;
    }

    private void assertPrimaryProfileMatchesRow() {
        SourceAccountRow primary = null;
        for (SourceAccountRow row : controller.sourceAccounts.peek()) {
            if (row.primary) primary = row;
        }
        assertNotNull(primary);
        assertEquals(primary.loggedIn, controller.loggedIn.peek());
        assertEquals(primary.loggedIn ? primary.displayName : "", controller.userName.peek());
        assertEquals(primary.loggedIn ? primary.avatarUrl : "", controller.userAvatar.peek());
        assertEquals(Integer.valueOf(primary.loggedIn ? primary.membershipTier : 0), controller.userVipType.peek());
        assertEquals(Integer.valueOf(primary.loggedIn ? primary.level : 0), controller.userLevel.peek());
        assertEquals(primary.loggedIn ? primary.signature : "", controller.userSignature.peek());
    }

    private void refreshAccounts() throws Exception {
        Method refresh = PlayerController.class.getDeclaredMethod("refreshSourceAccounts");
        refresh.setAccessible(true);
        synchronized (QmlRuntimeLock.MONITOR) {
            refresh.invoke(controller);
        }
    }

    private void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            controller.pump();
            if (ready.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("timed out waiting for offline account state");
    }

    private void install(String id, String accountBody) throws Exception {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = id;
        manifest.name = id;
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.capabilities.add("account");
        manifest.capabilities.add("login");
        manifest.permissions.add("credentials");
        byte[] json = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] script = ("var calls = 0; var signedOut = false; module.exports = {handlers:{"
                + "login: function(args) { if (args.operation === 'methods') return [];"
                + "if (args.operation === 'logout') signedOut = true; return true; },"
                + "account: function() { calls++; " + accountBody + " } }};")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("plugin.json", hex(MessageDigest.getInstance("SHA-256").digest(json)));
        hashes.put("main.js", hex(MessageDigest.getInstance("SHA-256").digest(script)));
        Path archive = base.resolve(id + ".qplug");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "plugin.json", json);
            put(zip, "main.js", script);
            put(zip, PluginPackageVerifier.HASHES_PATH, new Gson().toJson(hashes).getBytes(StandardCharsets.UTF_8));
        }
        new PluginInstaller(new PluginRegistry()).install(
                new PluginPackageVerifier().verify(archive, null, true),
                Collections.singleton(PluginPermission.CREDENTIALS));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    private static final class SilentBackend implements AudioBackend {
        @Override public void play(String source, long startMs) {}
        @Override public void pause() {}
        @Override public void resume() {}
        @Override public boolean isPlaying() { return false; }
        @Override public void seek(long ms) {}
        @Override public long position() { return 0L; }
        @Override public long duration() { return 0L; }
        @Override public void setVolume(float volume) {}
        @Override public void setOnComplete(Runnable callback) {}
        @Override public void setOnStarted(Runnable callback) {}
        @Override public void release() {}
    }
}
