package dev.t1m3.qplayer.desktop.audio;

import dev.t1m3.qplayer.util.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * {@link SeekableByteSource} over an {@code http(s)} URL. A background thread
 * streams the whole resource into a temp file; reads draw from that file and
 * block when they outrun the download. Seeking backward is instant; seeking
 * forward past the downloaded tail waits only for that tail to arrive. This
 * keeps first-play start-up fast (we begin decoding as soon as the first bytes
 * land) while still supporting real seeks — the same temp file is what
 * {@link #localFileBlocking()} hands the flac decoder once complete.
 */
final class HttpByteSource implements SeekableByteSource {

    private final File tempFile;
    private final RandomAccessFile reader;
    private final Thread downloader;
    private final Object lock = new Object();

    private volatile long downloaded = 0L;
    private volatile long total = -1L;
    private volatile boolean complete = false;
    private volatile boolean closed = false;
    private volatile IOException error;

    private long pos = 0L;

    HttpByteSource(String url) throws IOException {
        this.tempFile = File.createTempFile("qplayer-audio", ".tmp");
        this.tempFile.deleteOnExit();
        this.reader = new RandomAccessFile(tempFile, "r");
        this.downloader = new Thread(() -> download(url), "qplayer-audio-dl");
        this.downloader.setDaemon(true);
        this.downloader.start();
        // Wait until headers (Content-Length) or the first bytes are in, so the
        // caller has a usable size() / non-empty stream to start decoding.
        synchronized (lock) {
            while (total < 0 && downloaded == 0 && !complete && error == null && !closed) {
                try {
                    lock.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (error != null) {
            close();
            throw error;
        }
    }

    private void download(String url) {
        HttpURLConnection conn = null;
        try (RandomAccessFile writer = new RandomAccessFile(tempFile, "rw")) {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", "qplayer");
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            long len = conn.getContentLengthLong();
            synchronized (lock) {
                total = len;
                lock.notifyAll();
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!closed && (n = in.read(buf)) >= 0) {
                    writer.write(buf, 0, n);
                    synchronized (lock) {
                        downloaded += n;
                        lock.notifyAll();
                    }
                }
            }
            synchronized (lock) {
                complete = true;
                if (total < 0) total = downloaded;
                lock.notifyAll();
            }
        } catch (IOException e) {
            synchronized (lock) {
                error = e;
                lock.notifyAll();
            }
            if (!closed) Logger.exception(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Block until at least {@code want} bytes are downloaded past {@code pos},
     *  or the download ends. @return bytes actually available at pos (may be 0
     *  only at true EOF). */
    private long awaitAvailable(long want) throws IOException {
        synchronized (lock) {
            while (true) {
                if (error != null) throw error;
                if (closed) return 0L;
                long avail = downloaded - pos;
                if (avail >= want || complete) return Math.max(0L, avail);
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while buffering");
                }
            }
        }
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        long avail = awaitAvailable(1);
        if (avail <= 0) return -1; // complete and nothing left past pos
        int toRead = (int) Math.min(len, avail);
        synchronized (reader) {
            reader.seek(pos);
            int n = reader.read(dst, off, toRead);
            if (n > 0) pos += n;
            return n;
        }
    }

    @Override
    public void seek(long newPos) {
        this.pos = Math.max(0L, newPos);
    }

    @Override
    public long position() {
        return pos;
    }

    @Override
    public long size() {
        return total;
    }

    @Override
    public File localFileBlocking() throws IOException {
        synchronized (lock) {
            while (!complete && error == null && !closed) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while downloading");
                }
            }
        }
        if (error != null) throw error;
        return tempFile;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        downloader.interrupt();
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        // The temp file is deleteOnExit; drop it eagerly too.
        //noinspection ResultOfMethodCallIgnored
        tempFile.delete();
    }
}
