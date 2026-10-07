package dev.t1m3.qplayer.desktop.audio;

import java.io.File;
import java.io.IOException;

/** Delegates every call except {@link #close()}, so a decoder can be replaced
 *  without tearing down the live HTTP download underneath it. */
final class UnclosedByteSource implements SeekableByteSource {
    private final SeekableByteSource inner;

    UnclosedByteSource(SeekableByteSource inner) {
        this.inner = inner;
    }

    @Override public int read(byte[] dst, int off, int len) throws IOException {
        return inner.read(dst, off, len);
    }

    @Override public boolean hasBuffered(int n) {
        return inner.hasBuffered(n);
    }

    @Override public void seek(long pos) throws IOException {
        inner.seek(pos);
    }

    @Override public long position() {
        return inner.position();
    }

    @Override public long size() {
        return inner.size();
    }

    @Override public long downloadedBytes() {
        return inner.downloadedBytes();
    }

    @Override public File backingFile() {
        return inner.backingFile();
    }

    @Override public void close() { }
}
