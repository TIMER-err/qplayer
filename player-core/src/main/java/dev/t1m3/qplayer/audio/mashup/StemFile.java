package dev.t1m3.qplayer.audio.mashup;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>整曲分轨的落盘格式</b>（mashup 第三阶段）：4 条 s16 平面（左/右 × 伴奏/人声），
 * 按固定块长分块存放，可随机读。
 *
 * <h2>为什么不是 WAV</h2>
 *
 * <p>一条轨一首歌 4 分钟就是 42 MB（44.1k 立体声 s16），而这里要存<b>两条</b>轨
 * （伴奏 + 人声），而且读的时候是"按输出时间轴随机取 4096 帧"——WAV 能存但不能表达
 * "四平面 + 块索引 + 校验"，读起来还得自己算交错偏移。所以自定义一个 32 字节头 + 定长块的容器：
 *
 * <pre>
 *   magic "QPSTEM\0\0" (8) | u32 version | u32 sampleRate | u32 frames
 *   | u8 channels(2) | u8 sourceTag(0=DSP,1=AI) | u16 reserved
 *   | u32 blockFrames | u32 crc32(body)
 *   之后是块：每块 blockFrames 帧，每帧 4 个 s16（L_instr, R_instr, L_voc, R_voc）
 * </pre>
 *
 * <p>块长固定，所以"第 f 帧"的偏移是纯算术（{@code HEADER + (f/block)*block*8 + (f%block)*8}），
 * 不需要索引表；只有最后一块可能短一截（写的时候保证"短块只能是最后一块"）。
 *
 * <h2>校验的取舍</h2>
 *
 * <p>头里存了全文件的 crc32，但<b>{@link #open(Path)} 不验</b>——4 分钟的文件读一遍是
 * 85 MB，为了打开就要多花一两百毫秒不值得。open 只验证：魔数/版本/头字段自洽、
 * 文件长度恰好等于 {@code HEADER + frames × 8}。截断、魔数被改、长度对不上都能当场发现；
 * 中间的位翻转要靠 {@link #verifyCrc()}（测试与"清空缓存"路径会调）。
 */
public final class StemFile implements AutoCloseable {

    /** 文件魔数（8 字节，含两个 NUL）。 */
    static final byte[] MAGIC = {'Q', 'P', 'S', 'T', 'E', 'M', 0, 0};

    /** 格式版本。 */
    public static final int VERSION = 1;

    /** 头长度。 */
    public static final int HEADER_BYTES = 32;

    /** 每帧的 s16 采样数（4 条平面）。 */
    public static final int SAMPLES_PER_FRAME = 4;

    /** 每帧字节数。 */
    public static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2;

    private final Path path;
    private final RandomAccessFile file;
    private final int sampleRate;
    private final int channels;
    private final int tag;
    private final int blockFrames;
    private final long frames;
    private final long crc32;

    /** 读缓冲（按需增长，复用）。 */
    private byte[] scratch = new byte[0];

    private StemFile(Path path, RandomAccessFile file, int sampleRate, int channels, int tag,
                     int blockFrames, long frames, long crc32) {
        this.path = path;
        this.file = file;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.tag = tag;
        this.blockFrames = blockFrames;
        this.frames = frames;
        this.crc32 = crc32;
    }

    /** 打开并校验头（不验 crc，见类注释）。 */
    public static StemFile open(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("分轨文件路径不能为空");
        }
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
        try {
            long size = file.length();
            if (size < HEADER_BYTES) {
                throw new IOException("分轨文件太短：" + size + " 字节（至少要 "
                        + HEADER_BYTES + "）");
            }
            file.seek(0);
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            file.readFully(head.array());
            for (int i = 0; i < MAGIC.length; i++) {
                if (head.get(i) != MAGIC[i]) {
                    throw new IOException("不是分轨文件（魔数不对）：" + path);
                }
            }
            int version = head.getInt(8);
            if (version != VERSION) {
                throw new IOException("分轨文件版本不认：" + version + "（本实现只读 " + VERSION + "）");
            }
            int rate = head.getInt(12);
            long frames = head.getInt(16) & 0xFFFFFFFFL;
            int channels = head.get(20) & 0xFF;
            int tag = head.get(21) & 0xFF;
            int blockFrames = head.getInt(24);
            long crc = head.getInt(28) & 0xFFFFFFFFL;
            if (rate <= 0 || channels <= 0 || blockFrames <= 0) {
                throw new IOException("分轨头字段不合法：" + rate + "/" + channels + "/" + blockFrames);
            }
            if (channels != 2) {
                throw new IOException("分轨只支持立体声，文件里是 " + channels + " 声道");
            }
            long expected = HEADER_BYTES + frames * BYTES_PER_FRAME;
            if (size != expected) {
                throw new IOException("分轨文件长度不对：实际 " + size + "，应当是 " + expected
                        + "（" + frames + " 帧）——写的时候被打断了？");
            }
            return new StemFile(path, file, rate, channels, tag, blockFrames, frames, crc);
        } catch (IOException | RuntimeException e) {
            try {
                file.close();
            } catch (IOException ignored) {
                // 关不掉也不影响原始异常
            }
            throw e;
        }
    }

    /** 文件路径。 */
    public Path path() {
        return path;
    }

    /** 采样率。 */
    public int sampleRate() {
        return sampleRate;
    }

    /** 声道数（恒为 2）。 */
    public int channels() {
        return channels;
    }

    /** 分轨档位标记（0 = DSP，1 = AI）。 */
    public int tag() {
        return tag;
    }

    /** 块长（帧）。 */
    public int blockFrames() {
        return blockFrames;
    }

    /** 总帧数。 */
    public long frames() {
        return frames;
    }

    /** 文件里存的 crc32（要验证请调 {@link #verifyCrc()}）。 */
    public long crc32() {
        return crc32;
    }

    /** 全文件读一遍验 crc（慢；测试与缓存校验用）。 */
    public boolean verifyCrc() {
        try {
            file.seek(HEADER_BYTES);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = file.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
            return crc.getValue() == crc32;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读一段。
     *
     * @param frame        起始帧（文件内绝对帧号）
     * @param count        帧数
     * @param instrumental 伴奏输出（{@code [2][count]}，平面）
     * @param vocals       人声输出（{@code [2][count]}，平面）
     * @throws IllegalArgumentException 参数或数组形状不对
     */
    public void read(long frame, int count, float[][] instrumental, float[][] vocals)
            throws IOException {
        if (frame < 0 || count < 0 || frame + count > frames) {
            throw new IllegalArgumentException("读取区间越界：" + frame + " + " + count
                    + " > " + frames);
        }
        if (instrumental == null || instrumental.length < 2 || instrumental[0] == null
                || instrumental[1] == null || instrumental[0].length < count
                || instrumental[1].length < count) {
            throw new IllegalArgumentException("伴奏输出必须是 [2][" + count + "]");
        }
        if (vocals == null || vocals.length < 2 || vocals[0] == null || vocals[1] == null
                || vocals[0].length < count || vocals[1].length < count) {
            throw new IllegalArgumentException("人声输出必须是 [2][" + count + "]");
        }
        int done = 0;
        while (done < count) {
            long f = frame + done;
            long block = f / blockFrames;
            int within = (int) (f % blockFrames);
            int inBlock = (int) Math.min(count - done, blockFrames - within);
            long offset = HEADER_BYTES + block * (long) blockFrames * BYTES_PER_FRAME
                    + (long) within * BYTES_PER_FRAME;
            int bytes = inBlock * BYTES_PER_FRAME;
            ensureScratch(bytes);
            file.seek(offset);
            file.readFully(scratch, 0, bytes);
            ByteBuffer b = ByteBuffer.wrap(scratch, 0, bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < inBlock; i++) {
                instrumental[0][done + i] = b.getShort() / 32768f;
                instrumental[1][done + i] = b.getShort() / 32768f;
                vocals[0][done + i] = b.getShort() / 32768f;
                vocals[1][done + i] = b.getShort() / 32768f;
            }
            done += inBlock;
        }
    }

    private void ensureScratch(int bytes) {
        if (scratch.length < bytes) {
            scratch = new byte[bytes];
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /** 这个文件能不能当某个采样率的缓存用（版本/长度/采样率都对）。 */
    public static boolean isUsable(Path path, int rate, int tag) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try (StemFile f = open(path)) {
            return f.sampleRate() == rate && f.tag() == tag && f.frames() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "StemFile{" + path.getFileName() + ", " + sampleRate + " Hz, " + frames
                + " 帧, 块 " + blockFrames + ", tag " + tag + "}";
    }
}
