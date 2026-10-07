package dev.t1m3.qplayer.desktop.audio;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GrowingFileByteSourceTest {
    @Test
    public void starveIsNotEofUntilMarkedComplete() throws Exception {
        Path file = Files.createTempFile("qplayer-grow-", ".part");
        Files.write(file, new byte[]{1, 2, 3, 4});
        GrowingFileByteSource src = new GrowingFileByteSource(file.toString(), false);
        byte[] buf = new byte[8];
        assertEquals(4, src.read(buf, 0, 8));
        assertEquals(0, src.read(buf, 0, 8));
        assertFalse(src.hasBuffered(1));
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6});
        assertTrue(src.hasBuffered(1));
        assertEquals(2, src.read(buf, 0, 8));
        src.markComplete();
        assertEquals(-1, src.read(buf, 0, 8));
        src.close();
        Files.deleteIfExists(file);
    }
}
