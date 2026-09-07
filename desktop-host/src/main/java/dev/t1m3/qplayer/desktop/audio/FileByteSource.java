package dev.t1m3.qplayer.desktop.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** {@link SeekableByteSource} over a local file — random access is free. */
final class FileByteSource implements SeekableByteSource {

    private final File file;
    private final RandomAccessFile raf;
    private final long size;

    FileByteSource(String path) throws IOException {
        this.file = new File(path);
        this.raf = new RandomAccessFile(file, "r");
        this.size = raf.length();
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        return raf.read(dst, off, len);
    }

    @Override
    public void seek(long pos) throws IOException {
        raf.seek(pos);
    }

    @Override
    public long position() {
        try {
            return raf.getFilePointer();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public File backingFile() {
        return file;
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }
}
