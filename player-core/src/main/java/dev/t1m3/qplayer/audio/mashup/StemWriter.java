package dev.t1m3.qplayer.audio.mashup;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/**
 * <b>按块写分轨文件</b>（mashup 第三阶段，内部类）。
 *
 * <h2>为什么调用方可以随便写、块却是齐的</h2>
 *
 * <p>文件格式要求"每块正好 {@code blockFrames} 帧"（这样第 f 帧的偏移是纯算术，
 * 不需要索引表）。而生成方的自然节奏是"每次交叉淡化写 0.5 s、再写 19.5 s"——
 * 一个窗口要分两次交。所以块对齐这件事只能由写入方自己扛：内部攒一块、满了才落盘，
 * 关闭时把不满的尾巴作为<b>最后一块</b>写出。
 *
 * <p>代价是常驻两份块缓冲（20 s 立体声 × 2 轨 ≈ 15 MB），换来的是"写的人不用关心块"。
 * 这条取舍值得：块边界要是让每个调用点自己算，迟早会有一个地方算错，而那种错的
 * 表现是"某一段声音错位 20 秒"——很难从听感反推。
 */
final class StemWriter implements AutoCloseable {

    private final Path target;
    private final Path tmp;
    private final RandomAccessFile file;
    private final int sampleRate;
    private final int blockFrames;
    private final int tag;
    private final CRC32 crc = new CRC32();

    /** 攒块的缓冲：四条平面（L/R × 伴奏/人声）。 */
    private final float[][] blockI;
    private final float[][] blockV;
    private int blockFill;

    private long frames;
    private boolean closed;

    private StemWriter(Path target, Path tmp, RandomAccessFile file, int sampleRate,
                       int blockFrames, int tag) {
        this.target = target;
        this.tmp = tmp;
        this.file = file;
        this.sampleRate = sampleRate;
        this.blockFrames = blockFrames;
        this.tag = tag;
        this.blockI = new float[2][blockFrames];
        this.blockV = new float[2][blockFrames];
    }

    /**
     * 打开一个临时文件准备写。
     *
     * @param target      最终路径（关闭时才出现）
     * @param sampleRate  采样率
     * @param blockFrames 块长（帧）
     */
    static StemWriter open(Path target, int sampleRate, int blockFrames, int tag)
            throws IOException {
        if (sampleRate <= 0 || blockFrames <= 0) {
            throw new IllegalArgumentException("采样率与块长都必须为正：" + sampleRate
                    + "/" + blockFrames);
        }
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        RandomAccessFile f = new RandomAccessFile(tmp.toFile(), "rw");
        try {
            f.setLength(0);
            f.write(new byte[StemFile.HEADER_BYTES]);      // 占位头，关闭时回填
            return new StemWriter(target, tmp, f, sampleRate, blockFrames, tag);
        } catch (IOException | RuntimeException e) {
            try {
                f.close();
            } catch (IOException ignored) {
                // 交给原始异常
            }
            throw e;
        }
    }

    /** 已写帧数（含还没落盘的那部分）。 */
    long framesWritten() {
        return frames;
    }

    /**
     * 写一段（任意长度）。
     *
     * <p>{@code instrumental}/{@code vocals} 都是 {@code [2][n]} 平面，四路样本按
     * {@code L_instr, R_instr, L_voc, R_voc} 交错落盘。
     */
    void write(float[][] instrumental, float[][] vocals, int n) throws IOException {
        if (closed) {
            throw new IllegalStateException("StemWriter 已关闭");
        }
        if (n < 0) {
            throw new IllegalArgumentException("帧数不能为负：" + n);
        }
        checkPlane(instrumental, n, "伴奏");
        checkPlane(vocals, n, "人声");
        int done = 0;
        while (done < n) {
            int take = Math.min(n - done, blockFrames - blockFill);
            for (int c = 0; c < 2; c++) {
                System.arraycopy(instrumental[c], done, blockI[c], blockFill, take);
                System.arraycopy(vocals[c], done, blockV[c], blockFill, take);
            }
            blockFill += take;
            done += take;
            frames += take;
            if (blockFill == blockFrames) {
                flushBlock(blockFrames);
                blockFill = 0;
            }
        }
    }

    /** 收尾：写出尾巴、回填头 + 原子改名。 */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (blockFill > 0) {
                flushBlock(blockFill);
                blockFill = 0;
            }
            if (frames <= 0) {
                throw new IOException("没有写入任何帧，不生成空的分轨文件");
            }
            ByteBuffer head = ByteBuffer.allocate(StemFile.HEADER_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            head.put(StemFile.MAGIC);
            head.putInt(StemFile.VERSION);
            head.putInt(sampleRate);
            head.putInt((int) Math.min(frames, Integer.MAX_VALUE));
            head.put((byte) 2);
            head.put((byte) tag);
            head.putShort((short) 0);
            head.putInt(blockFrames);
            head.putInt((int) crc.getValue());
            file.seek(0);
            file.write(head.array());
            file.close();
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // 收尾失败就把临时文件删掉：留下一个 .tmp 会让"上次写坏了"永远看不出来
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 删不掉也不改变"这次失败了"这个事实
            }
            throw e;
        } finally {
            try {
                file.close();
            } catch (IOException ignored) {
                // 已经关过或关不掉：调用方看的是上面那个异常
            }
        }
    }

    /** 放弃：删掉临时文件，不动已有的正式文件。 */
    void abort() {
        try {
            file.close();
        } catch (IOException ignored) {
            // 放弃路径上不再关心
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 删不掉也不会让调用方更糟
        }
    }

    private void flushBlock(int n) throws IOException {
        byte[] bytes = new byte[n * StemFile.BYTES_PER_FRAME];
        int p = 0;
        for (int i = 0; i < n; i++) {
            p = put(bytes, p, blockI[0][i]);
            p = put(bytes, p, blockI[1][i]);
            p = put(bytes, p, blockV[0][i]);
            p = put(bytes, p, blockV[1][i]);
        }
        file.write(bytes);
        crc.update(bytes, 0, bytes.length);
    }

    private static void checkPlane(float[][] plane, int n, String what) {
        if (plane == null || plane.length < 2 || plane[0] == null || plane[1] == null
                || plane[0].length < n || plane[1].length < n) {
            throw new IllegalArgumentException(what + "必须是 [2][" + n + "] 平面");
        }
    }

    private static int put(byte[] out, int p, float value) {
        short s = WavWriter.toS16(value);
        out[p] = (byte) (s & 0xFF);
        out[p + 1] = (byte) ((s >> 8) & 0xFF);
        return p + 2;
    }
}
