package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.core.AudioBuf;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;

/**
 * <b>把平面 f32 块写成 WAV（RIFF / 16 bit PCM）</b>（2026-11-21，mashup 第一阶段）。
 *
 * <h2>为什么是"先写头、最后回填"</h2>
 *
 * <p>RIFF 的 {@code RIFF} 块长度与 {@code data} 块长度都在文件开头，而总帧数要等渲染完
 * 才知道。两条路：①先算总数、一次写对；②先写占位、关闭时回填。这里选 ②——
 * 因为渲染循环是按块拉取的，"文件写完时才可能知道真实帧数"，而回填用一个
 * {@link RandomAccessFile} 就够了，代价是 8 个字节的两次 seek。
 *
 * <p>这与仓库其它地方"不确定就别猜"的纪律一致：<b>不</b>预先算长度（算错会写出一个
 * 播放器能读、但时长字段与内容不符的文件，那种坏法最难查）。
 *
 * <h2>为什么目标必须是文件而不是 OutputStream</h2>
 *
 * <p>回填需要 seek。安卓的导出走的是"宿主先写一个 {@code .stage} 临时文件、再整段复制到
 * SAF 流"（见 {@code AudioSaveRequest}），所以<b>目标本来就是文件</b>——
 * 不需要为 {@code OutputStream} 再造一套"必须预先知道长度"的接口。
 *
 * <h2>量化</h2>
 *
 * <p>f32 → s16 用 {@code round(v × 32768)} 后夹到 {@code [-32768, 32767]}，
 * 而不是 {@code round(v × 32767)}：后者会把 {@code +1.0} 映到 32767、把 {@code −1.0}
 * 映到 −32767，负半轴少一档（听起来是"轻微的不对称"）。这里不加重放抖动（dither）——
 * 一段一次性的导成品，加抖动的收益远小于它带来的"结果不可复现"。
 */
public final class WavWriter implements AutoCloseable {

    /** 标准 PCM WAV 头长度（{@code RIFF(12) + fmt(24) + data(8)}）。 */
    public static final int HEADER_BYTES = 44;

    private static final int FMT_PCM = 1;

    /** RIFF 头里 {@code RIFF} 块长度字段的位置。 */
    private static final int RIFF_SIZE_OFFSET = 4;

    /** RIFF 头里 {@code data} 块长度字段的位置。 */
    private static final int DATA_SIZE_OFFSET = 40;

    private final RandomAccessFile file;
    private final int sampleRate;
    private final int channels;
    private final Path path;

    /** 已写帧数。s16 × 声道数 = 每帧字节数，所以字节数由它算出来，不另记一个"账"。 */
    private long frames;

    private boolean closed;

    private WavWriter(Path path, RandomAccessFile file, int sampleRate, int channels) {
        this.path = path;
        this.file = file;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * 打开一个 WAV 文件（先写占位头）。
     *
     * @param path       目标文件（父目录必须已存在；这里不替你建目录，"写哪"由调用方决定）
     * @param sampleRate 采样率（必须是正数）
     * @param channels   声道数（1..8）
     */
    public static WavWriter open(Path path, int sampleRate, int channels) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("目标路径不能为空");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("采样率必须为正：" + sampleRate);
        }
        if (channels < 1 || channels > 8) {
            throw new IllegalArgumentException("声道数必须在 1..8：" + channels);
        }
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
        try {
            file.setLength(0);
            file.write(header(sampleRate, channels, 0L));
            WavWriter writer = new WavWriter(path, file, sampleRate, channels);
            return writer;
        } catch (IOException | RuntimeException e) {
            closeQuietly(file);
            throw e;
        }
    }

    /** 采样率。 */
    public int sampleRate() {
        return sampleRate;
    }

    /** 声道数。 */
    public int channels() {
        return channels;
    }

    /** 已写帧数。 */
    public long framesWritten() {
        return frames;
    }

    /** 目标文件。 */
    public Path path() {
        return path;
    }

    /**
     * 写一块（{@code buf.frames()} 帧）。
     *
     * @throws IllegalStateException 已经关闭
     */
    public void write(AudioBuf buf) throws IOException {
        write(buf, buf.frames());
    }

    /**
     * 写一块的<b>前 {@code n} 帧</b>。
     *
     * <p>{@code n > buf.capacityFrames()} 会抛 {@link IllegalArgumentException}，
     * 而不是悄悄少写——静默截断在音频上就是"莫名其妙少了一段"。
     */
    public void write(AudioBuf buf, int n) throws IOException {
        ensureOpen();
        if (buf == null) {
            throw new IllegalArgumentException("音频块不能为空");
        }
        if (buf.channels() != channels) {
            throw new IllegalArgumentException("块的声道数 " + buf.channels()
                    + " 与文件打开时的 " + channels + " 不一致");
        }
        if (n < 0 || n > buf.capacityFrames() || n > buf.frames()) {
            throw new IllegalArgumentException("要写的帧数越界：" + n + "（块有效 "
                    + buf.frames() + "，容量 " + buf.capacityFrames() + "）");
        }
        writePlanar(buf, n);
    }

    /** 写一段平面素材（声道数组必须与文件打开的声道数一致）。 */
    public void write(float[][] planar, int n) throws IOException {
        ensureOpen();
        if (planar == null || planar.length != channels) {
            throw new IllegalArgumentException("平面素材的声道数与文件不一致：传 "
                    + (planar == null ? "null" : String.valueOf(planar.length))
                    + "，需要 " + channels);
        }
        for (int c = 0; c < channels; c++) {
            if (planar[c] == null || planar[c].length < n) {
                throw new IllegalArgumentException("第 " + c + " 个声道不足 " + n + " 帧");
            }
        }
        byte[] bytes = new byte[n * channels * 2];
        int p = 0;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < channels; c++) {
                short s = toS16(planar[c][i]);
                bytes[p++] = (byte) (s & 0xFF);
                bytes[p++] = (byte) ((s >> 8) & 0xFF);
            }
        }
        file.write(bytes);
        frames += n;
    }

    /**
     * 收尾：回填 RIFF/data 长度并关闭文件。
     *
     * <p>可以重复调用（第二次什么都不做）。关闭后写会抛 {@link IllegalStateException}。
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            long dataBytes = frames * channels * 2L;
            file.seek(RIFF_SIZE_OFFSET);
            writeLe32(file, 36L + dataBytes);
            file.seek(DATA_SIZE_OFFSET);
            writeLe32(file, dataBytes);
            file.seek(file.length());
        } finally {
            file.close();
        }
    }

    private void writePlanar(AudioBuf buf, int n) throws IOException {
        byte[] bytes = new byte[n * channels * 2];
        int p = 0;
        // 每声道取一次视图：视图长度是"取的那一刻的 frames"，所以必须在 setFrames 之后取。
        FloatBuffer[] views = new FloatBuffer[channels];
        for (int c = 0; c < channels; c++) {
            views[c] = buf.channel(c);
        }
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < channels; c++) {
                short s = toS16(views[c].get(i));
                bytes[p++] = (byte) (s & 0xFF);
                bytes[p++] = (byte) ((s >> 8) & 0xFF);
            }
        }
        file.write(bytes);
        frames += n;
    }

    /** f32 → s16（见类注释的量化说明）。 */
    static short toS16(float v) {
        if (Float.isNaN(v)) {
            return 0;
        }
        double scaled = (double) v * 32768.0;
        if (scaled >= 32767.0) {
            return Short.MAX_VALUE;
        }
        if (scaled <= -32768.0) {
            return Short.MIN_VALUE;
        }
        return (short) Math.round(scaled);
    }

    private static byte[] header(int sampleRate, int channels, long dataBytes) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt((int) (36L + dataBytes));
        b.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) FMT_PCM);
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(sampleRate * channels * 2);
        b.putShort((short) (channels * 2));
        b.putShort((short) 16);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt((int) dataBytes);
        return b.array();
    }

    private static void writeLe32(RandomAccessFile file, long value) throws IOException {
        byte[] b = new byte[4];
        b[0] = (byte) (value & 0xFF);
        b[1] = (byte) ((value >> 8) & 0xFF);
        b[2] = (byte) ((value >> 16) & 0xFF);
        b[3] = (byte) ((value >> 24) & 0xFF);
        file.write(b);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WavWriter 已关闭");
        }
    }

    private static void closeQuietly(RandomAccessFile file) {
        try {
            file.close();
        } catch (IOException ignored) {
            // 打不开就是打不开，交给调用方看原始异常
        }
    }

    @Override
    public String toString() {
        return "WavWriter{" + path + ", " + sampleRate + " Hz, " + channels + "ch, "
                + frames + " 帧}";
    }
}
