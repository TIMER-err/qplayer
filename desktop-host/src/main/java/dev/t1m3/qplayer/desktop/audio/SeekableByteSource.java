package dev.t1m3.qplayer.desktop.audio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Random-access view over a compressed audio payload — a local file or a
 * remote {@code http(s)} stream that is progressively downloaded to a temp
 * file. Reads are sequential from the current position; {@link #seek} moves
 * it. A remote source blocks reads until the requested bytes have arrived,
 * which is what lets the decoders seek for real instead of re-decoding from
 * zero (the latency the old {@code AudioSystem} path suffered).
 */
interface SeekableByteSource extends AutoCloseable {

    /**
     * Read up to {@code len} bytes at the current position; advances it.
     * Returns the count, or -1 at end of stream. May block (remote) until at
     * least one byte is available or the download reaches this position.
     */
    int read(byte[] dst, int off, int len) throws IOException;

    /** Move the read position. May point past the bytes downloaded so far for a
     *  remote source; the next read then blocks until they arrive. */
    void seek(long pos) throws IOException;

    long position();

    /** Total size in bytes, or -1 while still unknown (remote without a
     *  {@code Content-Length}, mid-download). */
    long size();

    /**
     * A fully-materialised local file backing this source. For a remote source
     * this blocks until the download completes. Needed by decoders that demand
     * a real seekable file (jflac's {@code RandomFileInputStream}).
     */
    File localFileBlocking() throws IOException;

    @Override
    void close();

    static SeekableByteSource open(String src) throws IOException {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return new HttpByteSource(src);
        }
        return new FileByteSource(src);
    }

    /** Read the whole payload into a byte[] (blocks until EOF). For decoders
     *  that need the entire compressed buffer in memory (stb_vorbis). */
    default byte[] readAll() throws IOException {
        long sz = size();
        int hint = sz > 0 ? (int) Math.min(sz, 1 << 26) : 1 << 16;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(hint);
        seek(0);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = read(buf, 0, buf.length)) >= 0) {
            if (n > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
