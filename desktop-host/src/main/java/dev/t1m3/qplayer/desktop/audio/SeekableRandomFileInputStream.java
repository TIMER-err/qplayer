package dev.t1m3.qplayer.desktop.audio;

import org.jflac.io.RandomFileInputStream;

import java.io.IOException;

/**
 * A {@link RandomFileInputStream} (the only stream type jflac's
 * {@code FLACDecoder.seek} accepts) whose reads/seeks are routed through a
 * {@link SeekableByteSource} instead of its own file handle. For a remote
 * source that means decoding starts as soon as the first bytes arrive and a
 * forward seek only waits for the bytes it needs — so FLAC streams like mp3/ogg
 * instead of stalling on a full download before the first sound.
 *
 * <p>The inherited {@code RandomAccessFile} (opened on the possibly-partial
 * backing file) is never read from; everything goes through {@code src}. It is
 * kept open only so any inherited method stays harmless, and closed in
 * {@link #close()}.
 */
final class SeekableRandomFileInputStream extends RandomFileInputStream {

    private final SeekableByteSource src;

    SeekableRandomFileInputStream(SeekableByteSource src) throws IOException {
        super(src.backingFile());
        this.src = src;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = src.read(b, 0, 1);
        return n < 0 ? -1 : (b[0] & 0xff);
    }

    @Override
    public int read(byte[] b) throws IOException {
        return src.read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return src.read(b, off, len);
    }

    @Override
    public void seek(long pos) throws IOException {
        src.seek(pos);
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) return 0;
        src.seek(src.position() + n);
        return n;
    }

    @Override
    public long getLength() throws IOException {
        long size = src.size();
        if (size < 0) throw new IOException("unknown FLAC stream length");
        return size;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
