package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TemperaImagePoolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void corruptMaterialIsAttemptedOnceAndReplacedOnlyWhenItsVersionChanges() throws Exception {
        String oldBase = AppDirs.base();
        String oldCache = AppDirs.cacheBase();
        TemperaImagePool pool = null;
        try {
            Path base = temporary.newFolder("material-data").toPath();
            AppDirs.setBase(base.toString());
            Path directory = Files.createDirectories(base.resolve("tempera/images"));
            Path bad = Files.write(directory.resolve("bad.png"), new byte[]{1});
            pool = new TemperaImagePool();
            awaitRevision(pool, 0);
            assertEquals(1, pool.size());
            Object first = entry(pool, "bad.png");
            assertNull(pool.imageFor("bad.png"));
            worker(pool).submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue((Boolean) value(first, "attempted"));
            for (int i = 0; i < 100; i++) assertNull(pool.imageFor("bad.png"));
            assertSame("failure must stay attached to this material version", first, entry(pool, "bad.png"));
            long version = pool.revision();
            Files.write(bad, new byte[]{1, 2});
            awaitRevision(pool, version);
            Object changed = entry(pool, "bad.png");
            assertNotSame(first, changed);
            assertFalse((Boolean) value(changed, "attempted"));
            assertNull(pool.imageFor("bad.png"));
            worker(pool).submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue((Boolean) value(changed, "attempted"));
            version = pool.revision();
            Files.delete(bad);
            awaitRevision(pool, version);
            assertEquals(0, pool.size());
            assertNull(pool.imageFor("bad.png"));
        } finally {
            if (pool != null) pool.dispose();
            AppDirs.setBase(oldBase);
            AppDirs.setCacheBase(oldCache);
        }
    }

    private static void awaitRevision(TemperaImagePool pool, long previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.revision() <= previous && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue("worker should publish a changed material version", pool.revision() > previous);
    }

    private static Object entry(TemperaImagePool pool, String id) throws Exception {
        synchronized (pool) { return ((Map<?, ?>) value(pool, "entries")).get(id); }
    }

    private static ExecutorService worker(TemperaImagePool pool) throws Exception {
        return (ExecutorService) value(pool, "worker");
    }

    private static Object value(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
