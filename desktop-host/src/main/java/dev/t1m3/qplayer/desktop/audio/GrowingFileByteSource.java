package dev.t1m3.qplayer.desktop.audio;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/**
 * A growing disk-cache payload. A non-empty read waits at the pending tail;
 * only a committed payload can return EOF. Handles are short-lived so cache
 * promotion also works on Windows. The timed wait observes external writes
 * without spinning; close wakes it immediately.
 */
final class GrowingFileByteSource implements SeekableByteSource {
    private File file;
    private final File promoted;
    private boolean complete;
    private boolean closed;
    private long pos;

    GrowingFileByteSource(String path, boolean complete) throws IOException {
        this(path, complete, null);
    }

    GrowingFileByteSource(String path, boolean complete, String completePath) throws IOException {
        file = new File(path);
        promoted = completePath == null ? null : new File(completePath);
        if (!file.isFile()) throw new IOException("missing growing cache: " + path);
        this.complete = complete;
    }

    synchronized void markComplete() {
        refreshPromotion();
        complete = true;
        notifyAll();
    }

    private void refreshPromotion() {
        if (promoted != null && promoted.isFile()) {
            file = promoted;
            complete = true;
        }
    }

    @Override
    public synchronized int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        while (true) {
            if (closed) throw new InterruptedIOException("growing audio source closed");
            refreshPromotion();
            if (file.isFile()) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    long available = raf.length() - pos;
                    if (available > 0) {
                        raf.seek(pos);
                        int n = raf.read(dst, off, (int) Math.min(len, available));
                        if (n > 0) { pos += n; return n; }
                    }
                    if (complete) return -1;
                } catch (FileNotFoundException e) {
                    if (file.isFile()) throw e;
                    // The pending path may have been renamed after isFile().
                    refreshPromotion();
                    if (file.isFile()) continue;
                }
            }
            if (complete) throw new IOException("completed audio cache is missing: " + file);
            try { wait(25L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while buffering growing audio");
            }
        }
    }

    @Override public synchronized boolean hasBuffered(int n) {
        refreshPromotion();
        return closed || complete || file.length() - pos >= n;
    }
    @Override public synchronized void seek(long newPos) { pos = Math.max(0L, newPos); }
    @Override public synchronized long position() { return pos; }
    @Override public synchronized long size() {
        refreshPromotion();
        return complete ? file.length() : -1L;
    }
    @Override public synchronized long downloadedBytes() { refreshPromotion(); return file.length(); }
    @Override public synchronized File backingFile() { refreshPromotion(); return file; }
    @Override public synchronized void close() { closed = true; notifyAll(); }
}
