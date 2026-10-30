package dev.t1m3.qplayer.desktop.audio;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Progressive, sparse HTTP storage. An uncached seek starts a validated Range
 * request without waiting for the old socket to unblock. Servers ignoring Range
 * fall back to a sequential response. Construction does no network IO, allowing
 * the owner to publish and cancel the source before decoder initialization.
 */
final class HttpByteSource implements SeekableByteSource {
    private final File tempFile;
    private final RandomAccessFile reader;
    private final Object lock = new Object();
    private final String url;
    private final Map<String, String> headers;
    private final TreeMap<Long, Long> ranges = new TreeMap<>();
    private volatile long total = -1L;
    private final Map<Long, HttpURLConnection> connections = new LinkedHashMap<>();
    private Thread downloader;
    private boolean closed;
    private long pos;
    private long requestStart;
    private long requestRevision;
    private long acceptedRevision;
    private boolean awaitingResponse;
    private long writingPosition;
    private int activeDownloads;
    private boolean sequentialOnly;
    private boolean ended;
    private IOException error;

    HttpByteSource(String url, Map<String, String> headers) throws IOException {
        this.url = url;
        this.headers = headers == null || headers.isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        tempFile = File.createTempFile("qplayer-audio", ".tmp");
        reader = new RandomAccessFile(tempFile, "r");
        synchronized (lock) { startRequest(0); }
    }

    /** Caller holds lock; the accepted response stays readable until handoff. */
    private void startRequest(long start) {
        requestStart = writingPosition = start;
        ended = false;
        error = null;
        awaitingResponse = true;
        long revision = ++requestRevision;
        activeDownloads++;
        downloader = new Thread(() -> download(start, revision), "qplayer-audio-dl");
        downloader.setDaemon(true);
        downloader.start();
    }

    private HttpURLConnection connect(long start, long revision) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        synchronized (lock) {
            if (closed || revision != requestRevision) throw new InterruptedIOException("audio request cancelled");
            connections.put(revision, conn);
        }
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent", "qplayer");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String key = header.getKey();
            if ("Host".equalsIgnoreCase(key) || "Content-Length".equalsIgnoreCase(key)
                    || "Connection".equalsIgnoreCase(key) || "Range".equalsIgnoreCase(key)
                    || "Accept-Encoding".equalsIgnoreCase(key)) continue;
            conn.setRequestProperty(key, header.getValue());
        }
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (start > 0) conn.setRequestProperty("Range", "bytes=" + start + "-");
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private void download(long start, long revision) {
        HttpURLConnection conn = null;
        try (RandomAccessFile writer = new RandomAccessFile(tempFile, "rw")) {
            conn = connect(start, revision);
            int status = conn.getResponseCode();
            long responseEnd;
            long responseTotal;
            if (status == HttpURLConnection.HTTP_PARTIAL) {
                String value = conn.getHeaderField("Content-Range");
                if (value == null || !value.startsWith("bytes ")) throw new IOException("missing HTTP Content-Range");
                String[] parts = value.substring(6).split("[-/]");
                if (parts.length != 3 || Long.parseLong(parts[0]) != start) {
                    throw new IOException("invalid HTTP Content-Range: " + value);
                }
                responseEnd = Long.parseLong(parts[1]) + 1;
                responseTotal = "*".equals(parts[2]) ? -1 : Long.parseLong(parts[2]);
                if (responseEnd <= start || (responseTotal >= 0 && responseEnd > responseTotal)) {
                    throw new IOException("invalid HTTP range bounds");
                }
            } else if (status == HttpURLConnection.HTTP_OK) {
                synchronized (lock) {
                    if (revision != requestRevision || closed) return;
                    if (start > 0) sequentialOnly = true;
                }
                start = 0;
                responseTotal = conn.getContentLengthLong();
                responseEnd = responseTotal;
            } else if (status == 416 && total >= 0 && start >= total) {
                synchronized (lock) {
                    if (revision == requestRevision) {
                        awaitingResponse = false;
                        ended = true;
                        lock.notifyAll();
                    }
                }
                return;
            } else {
                throw new IOException("HTTP audio response: " + status);
            }
            synchronized (lock) {
                if (revision != requestRevision || closed) return;
                if (responseTotal >= 0) total = responseTotal;
                writingPosition = start;
                acceptedRevision = revision;
                awaitingResponse = false;
                // Seeking can outrun the first HTTP chunk by only a few bytes.
                // Do not discard that still-progressing response while a Range
                // request is connecting: the server may serialize requests.
                // Only a validated replacement response can take ownership.
                for (Map.Entry<Long, HttpURLConnection> entry : connections.entrySet()) {
                    if (entry.getKey() != revision) disconnectAsync(entry.getValue());
                }
                // A seek may have moved again while these headers were in
                // flight. Refill a hole outside this response now: the backend
                // can be waiting in hasBuffered(), without another read/seek.
                long cached = available();
                long missingStart = pos + cached;
                if (!sequentialOnly && (total < 0 || missingStart < total)
                        && (missingStart < start || (cached == 0 && pos > start))) {
                    startRequest(missingStart);
                }
                lock.notifyAll();
            }
            writer.seek(start);
            long offset = start;
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    synchronized (lock) {
                        if (closed || (revision != requestRevision && revision != acceptedRevision)) return;
                    }
                    int n = in.read(buffer);
                    if (n < 0) break;
                    if (n == 0) continue;
                    synchronized (lock) {
                        if (closed || (revision != requestRevision && revision != acceptedRevision)) return;
                        writer.write(buffer, 0, n);
                        addRange(offset, offset + n);
                        offset += n;
                        if (revision == requestRevision) writingPosition = offset;
                        lock.notifyAll();
                    }
                }
            }
            synchronized (lock) {
                if (closed) return;
                if (revision != requestRevision) {
                    if (revision == acceptedRevision && total < 0 && start == 0
                            && (responseEnd < 0 || offset >= responseEnd)) {
                        total = offset;
                        lock.notifyAll();
                    }
                    return;
                }
                if (responseEnd >= 0 && offset < responseEnd) {
                    error = new IOException("stream ended early: " + offset + "/" + responseEnd + " bytes");
                } else {
                    if (total < 0) total = offset;
                    ended = true;
                    if (offset < total) startRequest(offset);
                }
                lock.notifyAll();
            }
        } catch (IOException | IllegalArgumentException e) {
            synchronized (lock) {
                if (!closed && revision == requestRevision) {
                    awaitingResponse = false;
                    error = e instanceof IOException ? (IOException) e : new IOException("invalid HTTP range", e);
                    lock.notifyAll();
                }
            }
        } finally {
            // Disconnect can wait on an in-flight read in some JDKs. It is never
            // called on the control/decoder thread and does not gate a new Range.
            if (conn != null) conn.disconnect();
            synchronized (lock) {
                connections.remove(revision);
                if (--activeDownloads == 0 && closed) tempFile.delete();
            }
        }
    }

    private void addRange(long start, long end) {
        Map.Entry<Long, Long> previous = ranges.floorEntry(start);
        if (previous != null && previous.getValue() >= start) {
            start = previous.getKey();
            end = Math.max(end, previous.getValue());
            ranges.remove(previous.getKey());
        }
        Map.Entry<Long, Long> next;
        while ((next = ranges.ceilingEntry(start)) != null && next.getKey() <= end) {
            end = Math.max(end, next.getValue());
            ranges.remove(next.getKey());
        }
        ranges.put(start, end);
    }

    private long available() {
        Map.Entry<Long, Long> range = ranges.floorEntry(pos);
        return range == null ? 0 : Math.max(0, range.getValue() - pos);
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        synchronized (lock) {
            while (available() == 0) {
                if (closed) throw new InterruptedIOException("audio source closed");
                if (error != null) throw error;
                if (total >= 0 && pos >= total) return -1;
                if (!awaitingResponse && !sequentialOnly
                        && (pos < requestStart || pos > writingPosition || ended)) {
                    startRequest(pos);
                } else if (ended) {
                    throw new IOException("HTTP audio has an unavailable byte range at " + pos);
                }
                try { lock.wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while buffering audio");
                }
            }
            if (closed) throw new InterruptedIOException("audio source closed");
            reader.seek(pos);
            int n = reader.read(dst, off, (int) Math.min(len, available()));
            if (n < 0) throw new IOException("downloaded audio cache is truncated");
            pos += n;
            return n;
        }
    }

    @Override
    public void seek(long newPos) throws IOException {
        synchronized (lock) {
            if (closed) throw new InterruptedIOException("audio source closed");
            pos = Math.max(0, newPos);
            // Even a cached cursor may sit on a short island. The backend will
            // not call read() until hasBuffered() passes, so fill its following
            // hole now if the active response cannot ever reach that hole.
            long missingStart = pos + available();
            if (!awaitingResponse && !sequentialOnly && (total < 0 || missingStart < total)
                    && (missingStart < requestStart || missingStart > writingPosition || ended)) {
                startRequest(missingStart);
            }
        }
    }

    @Override public long position() { synchronized (lock) { return pos; } }
    @Override public long size() { return total; }
    @Override public File backingFile() { return tempFile; }
    @Override public long downloadedBytes() {
        synchronized (lock) { return ranges.getOrDefault(0L, 0L); }
    }
    @Override public boolean hasBuffered(int n) {
        synchronized (lock) {
            return closed || error != null || (total >= 0 && pos >= total)
                    || available() >= n || (ended && available() > 0);
        }
    }

    private static void disconnectAsync(HttpURLConnection conn) {
        if (conn == null) return;
        Thread cancel = new Thread(conn::disconnect, "qplayer-audio-cancel");
        cancel.setDaemon(true);
        cancel.start();
    }

    @Override
    public void close() {
        HttpURLConnection[] cancel;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
            try { reader.close(); } catch (IOException ignored) { }
            if (downloader != null) downloader.interrupt();
            cancel = connections.values().toArray(new HttpURLConnection[0]);
            if (activeDownloads == 0) tempFile.delete();
        }
        for (HttpURLConnection conn : cancel) disconnectAsync(conn);
    }
}
