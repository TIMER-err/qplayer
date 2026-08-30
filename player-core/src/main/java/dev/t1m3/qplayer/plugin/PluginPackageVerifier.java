package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict verifier for deterministic QPlayer plugin archives. */
public final class PluginPackageVerifier {
    public static final String HASHES_PATH = "META-INF/qplayer-files.json";
    public static final String SIGNATURE_PATH = "META-INF/qplayer.sig";
    private static final int MAX_FILES = 512;
    private static final long MAX_ENTRY_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_PACKAGE_BYTES = 64L * 1024L * 1024L;
    private static final Type HASH_MAP = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    private final Gson gson = new Gson();

    public VerifiedPluginPackage verify(Path archive, PublicKey publisherKey,
                                        boolean allowUnsigned)
            throws IOException, GeneralSecurityException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new IOException("plugin package does not exist");
        }
        byte[] archiveDigest = digestFile(archive);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Map<String, ZipEntry> entries = inspect(zip);
            byte[] hashesBytes = readRequired(zip, entries.get(HASHES_PATH), 2L * 1024L * 1024L);
            Map<String, String> hashes = gson.fromJson(
                    new String(hashesBytes, StandardCharsets.UTF_8), HASH_MAP);
            validateHashes(hashes, entries);

            ZipEntry signatureEntry = entries.get(SIGNATURE_PATH);
            boolean signaturePresent = signatureEntry != null;
            boolean trustedSignature = false;
            if (signaturePresent && publisherKey != null) {
                byte[] encoded = readRequired(zip, signatureEntry, 16L * 1024L);
                byte[] signature;
                try {
                    signature = Base64.getDecoder().decode(
                            new String(encoded, StandardCharsets.US_ASCII).trim());
                } catch (IllegalArgumentException e) {
                    throw new GeneralSecurityException("plugin signature is not base64", e);
                }
                Signature verifier = Signature.getInstance("SHA256withECDSA");
                verifier.initVerify(publisherKey);
                verifier.update(hashesBytes);
                if (!verifier.verify(signature)) {
                    throw new GeneralSecurityException("plugin signature verification failed");
                }
                trustedSignature = true;
            } else if (!allowUnsigned) {
                throw new GeneralSecurityException("unsigned plugin package");
            }

            for (Map.Entry<String, String> expected : hashes.entrySet()) {
                byte[] bytes = readRequired(zip, entries.get(expected.getKey()), MAX_ENTRY_BYTES);
                String actual = hex(sha256(bytes));
                if (!actual.equals(expected.getValue().toLowerCase(Locale.ROOT))) {
                    throw new GeneralSecurityException("plugin file hash mismatch: " + expected.getKey());
                }
            }
            byte[] manifestBytes = readRequired(zip, entries.get("plugin.json"), MAX_ENTRY_BYTES);
            PluginManifest manifest = gson.fromJson(
                    new String(manifestBytes, StandardCharsets.UTF_8), PluginManifest.class);
            if (manifest == null) throw new IOException("plugin manifest is empty");
            manifest.validate();
            PluginCompatibility.requireCompatible(manifest);
            if (!hashes.containsKey(manifest.entry)) {
                throw new IOException("plugin entry is not covered by signed hashes");
            }
            for (PluginManifest.UiContribution contribution : manifest.ui) {
                if (!hashes.containsKey(contribution.source)) {
                    throw new IOException("UI contribution is not covered by signed hashes: "
                            + contribution.source);
                }
            }
            return new VerifiedPluginPackage(
                    archive, manifest, hashes, trustedSignature, hex(archiveDigest));
        }
    }

    private static Map<String, ZipEntry> inspect(ZipFile zip) throws IOException {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        long total = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()) continue;
            validatePath(name);
            if (entries.put(name, entry) != null) throw new IOException("duplicate zip entry: " + name);
            if (entries.size() > MAX_FILES) throw new IOException("plugin contains too many files");
            long size = entry.getSize();
            if (size < 0 || size > MAX_ENTRY_BYTES) throw new IOException("invalid plugin entry size: " + name);
            total += size;
            if (total > MAX_PACKAGE_BYTES) throw new IOException("plugin is too large");
        }
        return entries;
    }

    private static void validateHashes(Map<String, String> hashes, Map<String, ZipEntry> entries)
            throws IOException {
        if (hashes == null || hashes.isEmpty()) throw new IOException("plugin hash manifest is empty");
        Set<String> expectedFiles = new LinkedHashSet<>(entries.keySet());
        expectedFiles.remove(HASHES_PATH);
        expectedFiles.remove(SIGNATURE_PATH);
        if (!expectedFiles.equals(hashes.keySet())) {
            throw new IOException("plugin hash manifest does not cover exactly the package files");
        }
        if (!hashes.containsKey("plugin.json")) throw new IOException("plugin.json is missing");
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            validatePath(entry.getKey());
            if (entry.getValue() == null || !entry.getValue().matches("[A-Fa-f0-9]{64}")) {
                throw new IOException("invalid SHA-256 for " + entry.getKey());
            }
        }
    }

    private static void validatePath(String path) throws IOException {
        try {
            PluginManifest.requirePackagePath("package", path, null);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static byte[] readRequired(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        if (entry == null) throw new IOException("required plugin entry is missing");
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(entry.getSize(), 8192));
            byte[] buffer = new byte[8192];
            long read = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                read += count;
                if (read > limit) throw new IOException("plugin entry exceeds size limit: " + entry.getName());
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] digestFile(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static byte[] sha256(byte[] bytes) {
        return digest().digest(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
