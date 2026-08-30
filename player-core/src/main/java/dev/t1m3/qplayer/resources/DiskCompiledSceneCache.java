package dev.t1m3.qplayer.resources;

import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.CompiledSceneCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable, checksummed storage for qml4j generated scene classes. */
public final class DiskCompiledSceneCache implements CompiledSceneCache {

    private static final int MAGIC = 0x514D4C43;
    private static final int DISK_FORMAT_VERSION = 1;
    private static final int CHECKSUM_SIZE = 32;
    private static final int MAX_FILE_BYTES = 128 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    private final Path directory;
    private final String bundleFingerprint;

    public DiskCompiledSceneCache(Path directory, String bundleFingerprint) {
        this.directory = directory;
        this.bundleFingerprint = bundleFingerprint;
    }

    public String sceneKey(String rootResource) {
        return (bundleFingerprint == null ? "disabled" : bundleFingerprint)
                + ':' + rootResource;
    }

    @Override
    public CompiledScene load(String key) {
        if (bundleFingerprint == null) return null;
        Path file = cacheFile(key);
        if (!Files.isRegularFile(file)) return null;
        try {
            long size = Files.size(file);
            if (size <= CHECKSUM_SIZE || size > MAX_FILE_BYTES) {
                throw new IOException("invalid cache file size " + size);
            }
            CompiledScene scene = decode(key, Files.readAllBytes(file));
            Logger.info("QML compilation cache hit: {}", key.substring(key.length() - rootNameLength(key)));
            return scene;
        } catch (Exception error) {
            Logger.warn("discarding invalid QML compilation cache {} ({})", file, error);
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteError) {
                Logger.warn("cannot delete invalid QML compilation cache {} ({})", file, deleteError);
            }
            return null;
        }
    }

    @Override
    public void store(String key, CompiledScene scene) {
        if (bundleFingerprint == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            Path file = cacheFile(key);
            temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
            Files.write(temporary, encode(key, scene));
            moveReplacing(temporary, file);
            temporary = null;
            Logger.info("QML compilation cache stored: {}", key.substring(key.length() - rootNameLength(key)));
        } catch (Exception error) {
            Logger.warn("cannot store QML compilation cache ({})", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Path cacheFile(String key) {
        return directory.resolve(hex(sha256(key.getBytes(StandardCharsets.UTF_8))) + ".qmlc");
    }

    private static byte[] encode(String key, CompiledScene scene) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(DISK_FORMAT_VERSION);
            output.writeInt(CompiledScene.FORMAT_VERSION);
            writeString(output, key);
            writeString(output, scene.rootClassName());
            writeClasses(output, scene.classes());
            writeStringMap(output, scene.importedTypes());
            output.writeInt(scene.singletons().size());
            for (Map.Entry<String, Map<String, String>> prefix : scene.singletons().entrySet()) {
                writeString(output, prefix.getKey());
                writeStringMap(output, prefix.getValue());
            }
            output.writeInt(scene.jsImports().size());
            for (CompiledScene.JsImport jsImport : scene.jsImports()) {
                writeString(output, jsImport.alias());
                writeString(output, jsImport.path());
            }
        }
        byte[] payload = bytes.toByteArray();
        byte[] result = Arrays.copyOf(payload, payload.length + CHECKSUM_SIZE);
        System.arraycopy(sha256(payload), 0, result, payload.length, CHECKSUM_SIZE);
        if (result.length > MAX_FILE_BYTES) throw new IOException("compiled scene is too large");
        return result;
    }

    private static CompiledScene decode(String expectedKey, byte[] encoded) throws IOException {
        int payloadLength = encoded.length - CHECKSUM_SIZE;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] checksum = Arrays.copyOfRange(encoded, payloadLength, encoded.length);
        if (!MessageDigest.isEqual(checksum, sha256(payload))) {
            throw new IOException("cache checksum mismatch");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) throw new IOException("wrong cache magic");
            if (input.readInt() != DISK_FORMAT_VERSION) throw new IOException("wrong disk format");
            if (input.readInt() != CompiledScene.FORMAT_VERSION) {
                throw new IOException("wrong compiled scene format");
            }
            String key = readString(input);
            if (!expectedKey.equals(key)) throw new IOException("cache key mismatch");
            String rootClassName = readString(input);
            Map<String, byte[]> classes = readClasses(input);
            Map<String, String> importedTypes = readStringMap(input);
            int singletonPrefixes = readCount(input);
            Map<String, Map<String, String>> singletons = new LinkedHashMap<>();
            for (int i = 0; i < singletonPrefixes; i++) {
                singletons.put(readString(input), readStringMap(input));
            }
            int jsImportCount = readCount(input);
            List<CompiledScene.JsImport> jsImports = new ArrayList<>(jsImportCount);
            for (int i = 0; i < jsImportCount; i++) {
                jsImports.add(new CompiledScene.JsImport(readString(input), readString(input)));
            }
            if (input.available() != 0) throw new IOException("trailing cache data");
            return new CompiledScene(rootClassName, classes, importedTypes, singletons, jsImports);
        } catch (EOFException error) {
            throw new IOException("truncated cache", error);
        }
    }

    private static void writeClasses(DataOutputStream output, Map<String, byte[]> classes)
            throws IOException {
        output.writeInt(classes.size());
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            writeString(output, entry.getKey());
            writeBytes(output, entry.getValue());
        }
    }

    private static Map<String, byte[]> readClasses(DataInputStream input) throws IOException {
        int count = readCount(input);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) classes.put(readString(input), readBytes(input));
        return classes;
    }

    private static void writeStringMap(DataOutputStream output, Map<String, String> values)
            throws IOException {
        output.writeInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readStringMap(DataInputStream input) throws IOException {
        int count = readCount(input);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) values.put(readString(input), readString(input));
        return values;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream input) throws IOException {
        return new String(readBytes(input), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > MAX_ENTRY_BYTES) throw new IOException("cache entry is too large");
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_ENTRY_BYTES || length > input.available()) {
            throw new IOException("invalid cache entry length " + length);
        }
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("invalid entry count " + count);
        return count;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static int rootNameLength(String key) {
        int separator = key.lastIndexOf(':');
        return separator < 0 ? key.length() : key.length() - separator - 1;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
