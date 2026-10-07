package dev.t1m3.qplayer.desktop.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * A local file that may still be growing (the disk-cache {@code .pending}
 * sibling). Opens the file per read so the downloader can promote it on
 * Windows. Reads past the current length are a starve (0), not EOF, until
 * {@code complete} is true.
 */
final class GrowingFileByteSource implements SeekableByteSource {
    private final File file;
    private volatile boolean complete;
    private long pos;

    GrowingFileByteSource(String path, boolean complete) throws IOException {
        this.file = new File(path);
        if (!file.isFile()) throw new IOException("missing growing cache: " + path);
        this.complete = complete;
        this.pos = 0L;
    }

    void markComplete() {
        complete = true;
    }

    @Override
    public synchronized int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        if (!file.isFile()) return complete ? -1 : 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (pos >= length) return complete ? -1 : 0;
            int want = (int) Math.min(len, length - pos);
            raf.seek(pos);
            int n = raf.read(dst, off, want);
            if (n > 0) pos += n;
            return n;
        }
    }

    @Override
    public synchronized boolean hasBuffered(int n) {
        if (!file.isFile()) return complete;
        if (complete) return true;
        return file.length() - pos >= n;
    }

    @Override
    public synchronized void seek(long newPos) {
        pos = Math.max(0L, newPos);
    }

    @Override
    public synchronized long position() {
        return pos;
    }

    @Override
    public long size() {
        if (!file.isFile()) return complete ? 0L : -1L;
        return complete ? file.length() : -1L;
    }

    @Override
    public long downloadedBytes() {
        return file.isFile() ? file.length() : 0L;
    }

    @Override
    public File backingFile() {
        return file;
    }

    @Override
    public void close() { }
}
