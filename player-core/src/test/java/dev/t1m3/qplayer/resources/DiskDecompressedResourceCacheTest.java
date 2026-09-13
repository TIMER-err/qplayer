package dev.t1m3.qplayer.resources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DiskDecompressedResourceCacheTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reusesExpandedPayloadAndRecoversFromCorruption() throws Exception {
        byte[] original = new byte[128 * 1024];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 31);
        byte[] compressed = xz(original);
        AtomicInteger compressedReads = new AtomicInteger();
        io.github.timer_err.qml4j.render.ResourceLoader loader = source -> {
            if (!"fonts/Test.otf.xz".equals(source)) return null;
            compressedReads.incrementAndGet();
            return compressed;
        };
        Path directory = temporaryFolder.getRoot().toPath();
        DiskDecompressedResourceCache cache =
                new DiskDecompressedResourceCache(loader, directory);

        assertArrayEquals(original, cache.load("fonts/Test.otf"));
        assertArrayEquals(original, cache.load("fonts/Test.otf"));
        assertEquals(1, compressedReads.get());

        // A new loader must read the on-disk entry, including its payload checksum.
        assertArrayEquals(original,
                new DiskDecompressedResourceCache(loader, directory).load("fonts/Test.otf"));
        assertEquals(2, compressedReads.get());

        Path entry;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            entry = files.findFirst().orElseThrow(java.util.NoSuchElementException::new);
        }
        byte[] corrupt = Files.readAllBytes(entry);
        Arrays.fill(corrupt, corrupt.length - 32, corrupt.length, (byte) 0);
        Files.write(entry, corrupt);

        DiskDecompressedResourceCache restarted =
                new DiskDecompressedResourceCache(loader, directory);
        assertArrayEquals(original, restarted.load("fonts/Test.otf"));
        assertEquals(3, compressedReads.get());
    }

    @Test
    public void rejectsInvalidLengthBeforeAllocatingPayload() throws Exception {
        byte[] original = new byte[1024];
        byte[] compressed = xz(original);
        io.github.timer_err.qml4j.render.ResourceLoader loader = source ->
                "fonts/Test.otf.xz".equals(source) ? compressed : null;
        Path directory = temporaryFolder.getRoot().toPath();
        new DiskDecompressedResourceCache(loader, directory).load("fonts/Test.otf");
        Path entry;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            entry = files.findFirst().orElseThrow(java.util.NoSuchElementException::new);
        }
        byte[] invalid = Files.readAllBytes(entry);
        java.nio.ByteBuffer.wrap(invalid).putInt(8, Integer.MAX_VALUE);
        Files.write(entry, invalid);
        assertArrayEquals(original,
                new DiskDecompressedResourceCache(loader, directory).load("fonts/Test.otf"));

        // Truncation and extra bytes also invalidate the entry rather than
        // producing a partially read or unchecked font.
        byte[] valid = Files.readAllBytes(entry);
        Files.write(entry, Arrays.copyOf(valid, valid.length - 1));
        assertArrayEquals(original,
                new DiskDecompressedResourceCache(loader, directory).load("fonts/Test.otf"));
        Files.write(entry, Arrays.copyOf(valid, valid.length + 1));
        assertArrayEquals(original,
                new DiskDecompressedResourceCache(loader, directory).load("fonts/Test.otf"));
    }

    @Test
    public void readsLargeCacheEntryWithoutASecondPayloadCopy() throws Exception {
        byte[] compressed = xz(new byte[24 * 1024 * 1024]);
        Path directory = temporaryFolder.getRoot().toPath();
        Path asset = directory.resolve("test.xz");
        Files.write(asset, compressed);
        new DiskDecompressedResourceCache(source ->
                "test.xz".equals(source) ? compressed : null, directory).load("test");

        // The payload fits in 40 MiB, but payload + an entire encoded-file copy
        // does not. Run in a separate VM so the build's heap size cannot hide it.
        String javaExecutable = new java.io.File(System.getProperty("java.home"),
                "bin/java").getAbsolutePath();
        Path output = directory.resolve("child.log");
        Process child = new ProcessBuilder(javaExecutable, "-Xmx40m", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SmallHeapReader.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue("cache reader timed out", child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8),
                    0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }
    }

    public static final class SmallHeapReader {
        public static void main(String[] args) throws Exception {
            Path directory = java.nio.file.Paths.get(args[0]);
            byte[] compressed = Files.readAllBytes(directory.resolve("test.xz"));
            byte[] payload = new DiskDecompressedResourceCache(source ->
                    "test.xz".equals(source) ? compressed : null, directory).load("test");
            if (payload == null || payload.length != 24 * 1024 * 1024) {
                throw new AssertionError("wrong payload");
            }
        }
    }

    private static byte[] xz(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XZOutputStream output = new XZOutputStream(bytes, new LZMA2Options())) {
            output.write(input);
        }
        return bytes.toByteArray();
    }
}
