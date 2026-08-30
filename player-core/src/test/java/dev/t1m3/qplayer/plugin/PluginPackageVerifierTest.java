package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginPackageVerifierTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void verifiesSignedFileManifest() throws Exception {
        KeyPair keys = keys();
        Path archive = packageFile("valid.qplug", keys, "module.exports={handlers:{}};");

        VerifiedPluginPackage verified = new PluginPackageVerifier()
                .verify(archive, keys.getPublic(), false);

        assertTrue(verified.signed());
        assertEquals("fixture", verified.manifest().id);
        assertTrue(verified.hashes().containsKey("main.js"));
    }

    /** Dialogs are declarative: a contribution names no file, so there is nothing
     *  for the signed hash manifest to cover. Regression for an install that
     *  failed with "UI contribution is not covered by signed hashes". */
    @Test
    public void verifiesAContributionThatShipsNoDocument() throws Exception {
        KeyPair keys = keys();
        PluginManifest.UiContribution contribution = new PluginManifest.UiContribution();
        contribution.id = "listen-together";
        contribution.placement = "playerAction";
        contribution.label = "Listen Together";
        contribution.icon = "group";
        Path archive = uiPackageFile("declarative.qplug", keys, contribution);

        VerifiedPluginPackage verified = new PluginPackageVerifier()
                .verify(archive, keys.getPublic(), false);

        assertEquals(1, verified.manifest().ui.size());
        assertTrue(verified.manifest().ui.get(0).source.isEmpty());
    }

    private Path uiPackageFile(String name, KeyPair keys,
                               PluginManifest.UiContribution contribution) throws Exception {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "fixture";
        manifest.name = "Fixture";
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.permissions.add(PluginPermission.CUSTOM_UI.wireName());
        manifest.ui.add(contribution);
        byte[] manifestBytes = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] scriptBytes = "module.exports={handlers:{}};".getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("plugin.json", hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
        hashes.put("main.js", hex(MessageDigest.getInstance("SHA-256").digest(scriptBytes)));
        byte[] hashesBytes = new Gson().toJson(hashes).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(hashesBytes);
        byte[] signature = Base64.getEncoder().encode(signer.sign());

        Path archive = temporary.getRoot().toPath().resolve(name);
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "plugin.json", manifestBytes);
            put(zip, "main.js", scriptBytes);
            put(zip, PluginPackageVerifier.HASHES_PATH, hashesBytes);
            put(zip, PluginPackageVerifier.SIGNATURE_PATH, signature);
        }
        return archive;
    }

    @Test(expected = java.security.GeneralSecurityException.class)
    public void rejectsFileChangedAfterHashManifestWasSigned() throws Exception {
        KeyPair keys = keys();
        Path archive = packageFile("tampered.qplug", keys, "module.exports={handlers:{}};", true);
        new PluginPackageVerifier().verify(archive, keys.getPublic(), false);
    }

    @Test
    public void refusesModifiedFilesAfterInstallation() throws Exception {
        KeyPair keys = keys();
        Path archive = packageFile("installed.qplug", keys,
                "module.exports={handlers:{}};");
        VerifiedPluginPackage verified = new PluginPackageVerifier()
                .verify(archive, keys.getPublic(), false);
        Path installRoot = temporary.newFolder("plugins").toPath();
        PluginRegistry registry = new PluginRegistry(
                temporary.getRoot().toPath().resolve("plugins.json"));
        new PluginInstaller(installRoot, registry).install(verified,
                Collections.<PluginPermission>emptySet());
        Files.write(installRoot.resolve("fixture/1.0.0/main.js"),
                "tampered".getBytes(StandardCharsets.UTF_8));

        PluginHostApi host = (pluginId, method, arguments) ->
                java.util.concurrent.CompletableFuture.completedFuture(null);
        try (PluginManager manager = new PluginManager(installRoot, registry, host)) {
            manager.startEnabled();
            assertTrue(manager.enabledProviders().isEmpty());
        }
    }

    @Test
    public void failedUpdateRestoresPreviousEnabledRuntime() throws Exception {
        KeyPair keys = keys();
        Path v1Archive = packageFile("v1.qplug", keys,
                "module.exports={handlers:{}};", "1.0.0");
        Path v2Archive = packageFile("v2.qplug", keys,
                "module.exports={handlers:{}};", "2.0.0");
        PluginPackageVerifier verifier = new PluginPackageVerifier();
        VerifiedPluginPackage v1 = verifier.verify(v1Archive, keys.getPublic(), false);
        VerifiedPluginPackage v2 = verifier.verify(v2Archive, keys.getPublic(), false);
        Path installRoot = temporary.newFolder("update-plugins").toPath();
        PluginRegistry registry = new PluginRegistry(
                temporary.getRoot().toPath().resolve("update-plugins.json"));
        PluginInstaller installer = new PluginInstaller(installRoot, registry);
        installer.install(v1, Collections.<PluginPermission>emptySet());

        PolicyAwarePluginHostApi host = new PolicyAwarePluginHostApi() {
            @Override public void register(PluginManifest manifest) {
                if ("2.0.0".equals(manifest.version)) {
                    throw new SecurityException("fixture rejects updated policy");
                }
            }
            @Override public void unregister(String pluginId) { }
            @Override public java.util.concurrent.CompletableFuture<Object> call(
                    String pluginId, String method, Map<String, Object> arguments) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        try (PluginManager manager = new PluginManager(installRoot, registry, host)) {
            manager.startEnabled();
            try {
                manager.installAndEnable(installer, v2,
                        Collections.<PluginPermission>emptySet());
            } catch (java.io.IOException expected) {
                assertTrue(String.valueOf(expected.getCause()).contains("fixture rejects"));
            }
            assertEquals("1.0.0", registry.get("fixture").activeVersion);
            assertTrue(registry.get("fixture").enabled);
            assertEquals("1.0.0", manager.enabledProviders().get(0).version);
            assertFalse(Files.exists(installRoot.resolve("fixture/2.0.0")));
        }
    }

    private Path packageFile(String name, KeyPair keys, String script) throws Exception {
        return packageFile(name, keys, script, false);
    }

    private Path packageFile(String name, KeyPair keys, String script, String version)
            throws Exception {
        return packageFile(name, keys, script, false, version);
    }

    private Path packageFile(String name, KeyPair keys, String script, boolean tamper) throws Exception {
        return packageFile(name, keys, script, tamper, "1.0.0");
    }

    private Path packageFile(String name, KeyPair keys, String script, boolean tamper,
                             String version) throws Exception {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "fixture";
        manifest.name = "Fixture";
        manifest.version = version;
        manifest.apiVersion = "1.0";
        byte[] manifestBytes = new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_8);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("plugin.json", hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)));
        hashes.put("main.js", hex(MessageDigest.getInstance("SHA-256").digest(scriptBytes)));
        byte[] hashesBytes = new Gson().toJson(hashes).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(hashesBytes);
        byte[] signature = Base64.getEncoder().encode(signer.sign());

        Path archive = temporary.getRoot().toPath().resolve(name);
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "plugin.json", manifestBytes);
            put(zip, "main.js", tamper ? "changed".getBytes(StandardCharsets.UTF_8) : scriptBytes);
            put(zip, PluginPackageVerifier.HASHES_PATH, hashesBytes);
            put(zip, PluginPackageVerifier.SIGNATURE_PATH, signature);
        }
        return archive;
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
