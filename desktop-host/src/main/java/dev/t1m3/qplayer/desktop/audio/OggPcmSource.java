package dev.t1m3.qplayer.desktop.audio;

import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Vorbis via stb: seekable local input is read directly into one native buffer
 * (no whole-file heap array or intermediate copy); progressive input uses
 * pushdata and retains only the incomplete packet. Pushdata seeks replay the
 * source's disk-backed bytes because stb cannot seek a pushdata handle itself.
 */
final class OggPcmSource implements PcmSource {
    private final SeekableByteSource src;
    private final boolean push;
    private long handle;
    private ByteBuffer input;
    private final byte[] transfer = new byte[16 * 1024];
    private final ShortBuffer pcm;
    private final int sampleRate;
    private final int channels;
    private final int totalSamples;
    private int inputStart;
    private int inputEnd;
    private boolean inputEof;
    private byte[] pending = new byte[0];
    private int pendingPos;
    private int pendingEnd;

    private boolean closed;
    OggPcmSource(SeekableByteSource src) throws IOException {
        this.src = src;
        push = !(src instanceof FileByteSource);
        try {
            if (push) {
                input = MemoryUtil.memAlloc(64 * 1024);
                openPush();
            } else {
                long length = src.size();
                if (length <= 0 || length > Integer.MAX_VALUE) throw new IOException("unsupported Vorbis payload size: " + length);
                input = MemoryUtil.memAlloc((int) length);
                src.seek(0);
                while (input.hasRemaining()) {
                    if (((FileByteSource) src).read(input) < 0) throw new IOException("truncated Vorbis payload");
                }
                input.flip();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer error = stack.mallocInt(1);
                    handle = stb_vorbis_open_memory(input, error, null);
                    if (handle == NULL) throw new IOException("stb_vorbis_open_memory failed: " + error.get(0));
                }
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                stb_vorbis_get_info(handle, info);
                sampleRate = info.sample_rate();
                channels = info.channels();
            }
            if (channels < 1 || channels > 2) throw new IOException("unsupported ogg channel count: " + channels);
            totalSamples = push ? 0 : stb_vorbis_stream_length_in_samples(handle);
            pcm = push ? null : MemoryUtil.memAllocShort(16 * 1024);
        } catch (IOException | RuntimeException e) {
            if (handle != NULL) stb_vorbis_close(handle);
            if (input != null) MemoryUtil.memFree(input);
            throw e;
        }
    }

    private ByteBuffer compressed() {
        input.position(inputStart).limit(inputEnd);
        return input;
    }

    private boolean appendInput() throws IOException {
        if (inputEof) return false;
        input.position(inputStart).limit(inputEnd);
        input.compact();
        inputEnd -= inputStart;
        inputStart = 0;
        if (input.remaining() == 0) {
            if (input.capacity() > Integer.MAX_VALUE / 2) throw new IOException("Vorbis packet is too large");
            input = MemoryUtil.memRealloc(input, input.capacity() * 2);
        }
        int n = src.read(transfer, 0, Math.min(transfer.length, input.capacity() - inputEnd));
        if (n < 0) { inputEof = true; return false; }
        input.limit(input.capacity()).position(inputEnd);
        input.put(transfer, 0, n);
        inputEnd += n;
        return true;
    }

    private void openPush() throws IOException {
        src.seek(0);
        inputStart = inputEnd = 0;
        inputEof = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer used = stack.mallocInt(1);
            IntBuffer error = stack.mallocInt(1);
            while (true) {
                handle = stb_vorbis_open_pushdata(compressed(), used, error, null);
                if (handle != NULL) { inputStart += used.get(0); return; }
                if (error.get(0) != VORBIS_need_more_data) {
                    throw new IOException("stb_vorbis_open_pushdata failed: " + error.get(0));
                }
                if (!appendInput()) throw new IOException("truncated Vorbis headers");
            }
        }
    }

    @Override public int sampleRate() { return sampleRate; }
    @Override public int channels() { return channels; }
    @Override public long durationMs() { return totalSamples > 0 ? totalSamples * 1000L / sampleRate : 0; }
    @Override public boolean hasBuffered(int n) {
        return pendingPos < pendingEnd || !push || inputStart < inputEnd || inputEof || src.hasBuffered(1);
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len <= 0) return 0;
        if (!push) {
            int frames = Math.min(len / (channels * 2), pcm.capacity() / channels);
            if (frames == 0) return 0;
            pcm.clear().limit(frames * channels);
            int samples = stb_vorbis_get_samples_short_interleaved(handle, channels, pcm) * channels;
            if (samples == 0) return -1;
            for (int i = 0; i < samples; i++) {
                short value = pcm.get(i);
                dst[off++] = (byte) value;
                dst[off++] = (byte) (value >> 8);
            }
            return samples * 2;
        }
        while (pendingPos >= pendingEnd) if (!decodePushFrame()) return -1;
        int n = Math.min(len, pendingEnd - pendingPos);
        System.arraycopy(pending, pendingPos, dst, off, n);
        pendingPos += n;
        return n;
    }

    private boolean decodePushFrame() throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            IntBuffer channelCount = stack.mallocInt(1);
            PointerBuffer output = stack.mallocPointer(1);
            while (true) {
                int used = stb_vorbis_decode_frame_pushdata(handle, compressed(), channelCount, output, count);
                inputStart += used;
                int error = stb_vorbis_get_error(handle);
                if (error != VORBIS__no_error && error != VORBIS_need_more_data) {
                    throw new IOException("Vorbis decode failed: " + error);
                }
                int frames = count.get(0);
                if (frames > 0) {
                    if (channelCount.get(0) != channels) throw new IOException("Vorbis channel count changed");
                    int bytes = frames * channels * 2;
                    if (pending.length < bytes) pending = new byte[bytes];
                    PointerBuffer planes = MemoryUtil.memPointerBuffer(output.get(0), channels);
                    for (int c = 0; c < channels; c++) {
                        FloatBuffer plane = MemoryUtil.memFloatBuffer(planes.get(c), frames);
                        for (int f = 0, index = c * 2; f < frames; f++, index += channels * 2) {
                            int value = Math.max(-32768, Math.min(32767, Math.round(plane.get(f) * 32768.0f)));
                            pending[index] = (byte) value;
                            pending[index + 1] = (byte) (value >> 8);
                        }
                    }
                    pendingPos = 0;
                    pendingEnd = bytes;
                    return true;
                }
                if (used == 0 && !appendInput()) return false;
            }
        }
    }

    @Override
    public long seek(long ms) throws IOException {
        long sample = Math.max(0L, ms) * sampleRate / 1000L;
        if (!push) {
            if (totalSamples > 0) sample = Math.min(sample, totalSamples - 1L);
            if (!stb_vorbis_seek(handle, (int) sample)) throw new IOException("Vorbis seek failed");
            return sample * 1000L / sampleRate;
        }
        stb_vorbis_close(handle);
        handle = NULL;
        pendingPos = pendingEnd = 0;
        openPush();
        long skipped = 0;
        while (skipped < sample && decodePushFrame()) {
            int frames = pendingEnd / (channels * 2);
            int discard = (int) Math.min(sample - skipped, frames);
            pendingPos = discard * channels * 2;
            skipped += discard;
        }
        return skipped * 1000L / sampleRate;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (handle != NULL) { stb_vorbis_close(handle); handle = NULL; }
        if (pcm != null) MemoryUtil.memFree(pcm);
        if (input != null) MemoryUtil.memFree(input);
        src.close();
    }
}
