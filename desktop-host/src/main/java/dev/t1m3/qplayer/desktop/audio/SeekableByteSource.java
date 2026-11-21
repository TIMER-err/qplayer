package dev.t1m3.qplayer.desktop.audio;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Random-access view of a local or progressively downloaded audio payload.
 * Reads are sequential from the current cursor; remote seeks use HTTP ranges
 * where supported, otherwise they wait for the sequential download.
 */
interface SeekableByteSource extends AutoCloseable {

    /**
     * Read up to {@code len} bytes and advance the cursor. For a non-empty
     * request this returns a positive count or -1 only at true EOF; temporary
     * starvation blocks, never returns zero. Closing cancels pending IO with
     * an IOException. An empty request returns zero.
     */
    int read(byte[] dst, int off, int len) throws IOException;

    /**
     * True when the next {@link #read} will not wait for the network. Local
     * files always return true; a remote source returns true once {@code n}
     * bytes past the cursor are already on disk, or the download has ended.
     */
    default boolean hasBuffered(int n) { return true; }

    /** Bytes already on disk from the start of the resource. Local files
     *  report {@link #size()}; a remote download reports how far the
     *  background thread has gotten. */
    default long downloadedBytes() {
        long s = size();
        return s < 0 ? 0L : s;
    }

    /** Move the cursor. Uncached remote offsets request a new HTTP range when
     *  available; a growing local cache waits for its writer to reach them. */
    void seek(long pos) throws IOException;

    long position();

    /** Total size in bytes, or -1 while still unknown (remote without a
     *  {@code Content-Length}, mid-download). */
    long size();

    /**
     * The local file backing this source, returned immediately — for a remote
     * source this is the temp file the download streams into, which may still
     * be partial. Decoders that need a {@code RandomFileInputStream} (jflac) wrap
     * it but route reads through {@link #read}/{@link #seek} so they still block
     * progressively rather than wait for the whole file.
     */
    File backingFile();

    @Override
    void close();


    static SeekableByteSource open(String src, Map<String, String> headers) throws IOException {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return new HttpByteSource(src, headers);
        }
        return new FileByteSource(src);
    }

}
