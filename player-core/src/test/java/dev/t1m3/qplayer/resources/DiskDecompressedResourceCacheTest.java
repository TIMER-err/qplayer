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

    /**
     * 安卓真机 OOM 的回归测试（2026-09-27 的崩溃日志）：
     *
     * <pre>
     * java.lang.OutOfMemoryError: Failed to allocate a 13215139 byte allocation ...
     *   at sun.nio.ch.Util.getTemporaryDirectBuffer
     *   at java.nio.file.ChannelInputStream.read
     *   at DiskDecompressedResourceCache.decode(DiskDecompressedResourceCache.java:136)
     *   at io.github.timer_err.qml4j.android.QmlGLSurfaceView$GlRenderer.onSurfaceChanged
     * </pre>
     *
     * <p>爆掉的不是 Java 堆，是<strong>直接内存</strong>：{@code Files.newInputStream} 内部是
     * FileChannel，把 N 字节读进 {@code byte[]} 时 OpenJDK/ART 会先要一块<strong>和读取量
     * 等大</strong>的临时直接缓冲（堆缓冲不能直接交给内核做 read，见
     * {@code IOUtil.readIntoNativeBuffer}）。写也一样（{@code IOUtil.writeFromNativeBuffer}）。
     * 这块内存不可移动、不归 GC 的堆管，却计进安卓的 growth limit —— 于是每次旋转屏幕/
     * 切回前台重建 surface 都要在 GL 线程上凭空要 13 MB，256 MB 额度就那么被顶爆了。
     *
     * <p>上面那条 {@code readsLargeCacheEntryWithoutASecondPayloadCopy} 抓不到它：那条测的是
     * 堆（{@code -Xmx40m}），而直接内存根本不在这 40 m 里。
     *
     * <p>这里盯的是"读/写一个缓存条目期间，进程直接内存的峰值不该涨到 payload 那么大"。
     * 采样线程取峰值而不是只看调用返回后的读数：临时缓冲有可能被
     * {@code Util} 的线程本地缓存留下（那样峰值会一直挂着），也有可能当场释放
     * （那样只看事后读数就是漏检）。
     */
    @Test
    public void loadsAndStoresLargeEntryWithoutPayloadSizedDirectBuffer() throws Exception {
        final int payloadSize = 16 * 1024 * 1024;
        byte[] original = new byte[payloadSize];
        for (int i = 0; i < original.length; i += 4096) original[i] = (byte) (i >> 12);
        byte[] compressed = xz(original);
        io.github.timer_err.qml4j.render.ResourceLoader loader = source ->
                "fonts/Big.otf.xz".equals(source) ? compressed : null;
        Path directory = temporaryFolder.getRoot().toPath();
        final byte[][] readBack = new byte[1][];

        // 第一次 load：解压 + 落盘（写路径）。
        long storeGrowth = directMemoryPeakDuring(() ->
                readBack[0] = new DiskDecompressedResourceCache(loader, directory).load("fonts/Big.otf"));
        assertArrayEquals(original, readBack[0]);
        assertDirectGrowthWithin("写缓存条目", storeGrowth, payloadSize);

        // 第二次 load：走缓存命中分支，正是崩溃日志里的 decode()（读路径）。
        DiskDecompressedResourceCache restarted =
                new DiskDecompressedResourceCache(loader, directory);
        final byte[][] hit = new byte[1][];
        long readGrowth = directMemoryPeakDuring(() -> hit[0] = restarted.load("fonts/Big.otf"));
        assertArrayEquals(original, hit[0]);
        assertDirectGrowthWithin("读缓存条目", readGrowth, payloadSize);
    }

    private static void assertDirectGrowthWithin(String what, long growth, int payloadSize) {
        if (growth == SKIPPED) return;
        assertTrue(what + "期间直接内存涨了 " + growth + " 字节（payload " + payloadSize
                + "）：读/写大文件不能走 Files.newInputStream/newOutputStream ——"
                + "它们内部是 FileChannel，会要一块和读取量等大的临时直接缓冲。"
                + "用普通 FileInputStream/FileOutputStream（或分块读）就不会。",
                growth < payloadSize / 4);
    }

    /** 直接内存池不存在时（进程还没分配过直接缓冲）返回 SKIPPED，不让测试变成假红。 */
    private static final long SKIPPED = Long.MIN_VALUE;

    private static long directMemory() {
        // 先确保池存在：池是第一次分配直接缓冲时才注册的。
        java.nio.ByteBuffer.allocateDirect(1);
        for (java.lang.management.BufferPoolMXBean pool : java.lang.management.ManagementFactory
                .getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) return pool.getMemoryUsed();
        }
        return SKIPPED;
    }

    private static long directMemoryPeakDuring(Runnable body) throws Exception {
        final long before = directMemory();
        if (before == SKIPPED) return SKIPPED;
        final java.util.concurrent.atomic.AtomicLong peak =
                new java.util.concurrent.atomic.AtomicLong(before);
        final java.util.concurrent.atomic.AtomicBoolean sampling =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                peak.accumulateAndGet(directMemory(), Math::max);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        try {
            body.run();
        } finally {
            sampling.set(false);
            sampler.join(2000);
        }
        peak.accumulateAndGet(directMemory(), Math::max);
        return Math.max(0, peak.get() - before);
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
