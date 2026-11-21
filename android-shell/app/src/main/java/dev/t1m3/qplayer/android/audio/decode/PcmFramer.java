package dev.t1m3.qplayer.android.audio.decode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.t1m3.qplayer.android.audio.AndroidAudioFormats;

/**
 * 把解码器吐出来的<strong>字节流</strong>按「帧」切好并转成 f32（<strong>纯逻辑，不引用 android.*</strong>）。
 *
 * <h2>为什么需要它：半个采样会让左右声道错位</h2>
 *
 * <p>解码器一次给的字节数<strong>不保证是整帧</strong>（帧 = 声道数 × 每采样字节数）。
 * 上一版遇到半帧就直接把尾巴丢掉，下一包的字节于是接到了<strong>声道中间</strong>——
 * 左右声道从此错位，听起来就是杂音，而且只在少数解码器/少数文件上出现。
 * 正确做法是<strong>把不足一帧的尾巴留到下一包接上</strong>（{@link #feed(ByteBuffer)} 里那几步）。
 *
 * <h2>契约</h2>
 *
 * <p>每包按顺序：{@link #feed} → {@link #skipFrames}/{@link #read} → 下一包。
 * 内部只保留「半个采样」（最多 {@code 帧字节数 − 1} 字节），所以内存是常数。
 * 不是线程安全的（调用方是解码线程）。
 */
public final class PcmFramer {

    /** carry 预分配大小：够放「上一包残留 + 这一包上半帧」（8 声道 × 4 字节的两倍）。 */
    private static final int MAX_CARRY = 64;

    private final int encoding;
    private final int channels;
    private final int frameBytes;

    private final byte[] carry = new byte[MAX_CARRY];
    private int carryLength;
    /** 补全出来的那一帧是否还没被消费（补全后 always 1 帧）。 */
    private boolean carryFrameReady;

    private ByteBuffer source;

    public PcmFramer(int encoding, int channels) {
        this.encoding = encoding;
        this.channels = channels;
        this.frameBytes = AndroidAudioFormats.frameBytes(encoding, channels);
        if (this.frameBytes <= 0) {
            throw new IllegalArgumentException("不认识的 PCM 编码/声道数：" + encoding + " / " + channels);
        }
    }

    /** 一帧的字节数。 */
    public int frameBytes() {
        return frameBytes;
    }

    /**
     * 接上解码器刚给出的一段字节。
     *
     * <p>上一包如果还剩<strong>不足一帧</strong>，那份尾巴先搬进 carry 一起补全（正常情况）；
     * 还剩**整帧**没读则是调用方的问题（它没有把可用帧读干净），如实报错而不是悄悄丢帧。
     */
    public void feed(ByteBuffer buffer) {
        if (source != null) {
            int leftover = source.remaining();
            if (leftover >= frameBytes) {
                throw new IllegalStateException("上一包还有 " + (leftover / frameBytes)
                        + " 整帧没读（availableFrames 报少了？）");
            }
            if (leftover > 0) {
                if (carryLength + leftover > MAX_CARRY) {
                    throw new IllegalStateException("carry 放不下上一包的残留（" + leftover + " 字节）");
                }
                source.get(carry, carryLength, leftover);
                carryLength += leftover;
            }
            source = null;
        }
        source = buffer;
        if (carryLength > 0) {
            // 上一包剩下的半个采样：这一包补全它（不够就继续攒着）
            int needed = frameBytes - carryLength;
            if (buffer.remaining() >= needed) {
                buffer.get(carry, carryLength, needed);
                carryLength = frameBytes;
                carryFrameReady = true;
            } else {
                int take = buffer.remaining();
                buffer.get(carry, carryLength, take);
                carryLength += take;
            }
        }
    }

    /** 现在能凑出多少整帧。 */
    public int availableFrames() {
        int frames = 0;
        if (carryFrameReady) {
            frames++;
        }
        if (source != null) {
            frames += source.remaining() / frameBytes;
        }
        return frames;
    }

    /**
     * 丢掉最多 {@code frames} 帧（编码器延迟那种）。
     *
     * @return 实际丢掉的帧数
     */
    public int skip(int frames) {
        int skipped = 0;
        if (carryFrameReady && skipped < frames) {
            carryFrameReady = false;
            carryLength = 0;
            skipped++;
        }
        if (source != null) {
            int fromSource = Math.min(frames - skipped, source.remaining() / frameBytes);
            if (fromSource > 0) {
                source.position(source.position() + fromSource * frameBytes);
                skipped += fromSource;
            }
        }
        stashRemainder();
        return skipped;
    }

    /**
     * 把最多 {@code maxFrames} 帧转成交错 f32 写进 {@code dst}。
     *
     * @return 实际写入的帧数
     */
    public int read(float[] dst, int maxFrames) {
        if (maxFrames <= 0) {
            return 0;
        }
        int written = 0;
        if (carryFrameReady && written < maxFrames) {
            // 补全出来的这一帧：单独转一次（很少发生，包装对象可以忽略）。
            // **必须带目标下标**：下面那一段会接着写，不带下标就会把它盖掉。
            written += AndroidAudioFormats.readPcm(
                    ByteBuffer.wrap(carry, 0, frameBytes), dst, 0, encoding, channels, 1);
            carryFrameReady = false;
            carryLength = 0;
        }
        if (source != null && written < maxFrames) {
            written += AndroidAudioFormats.readPcm(source, dst, written * channels,
                    encoding, channels, maxFrames - written);
        }
        stashRemainder();
        return written;
    }

    /** 这一包消费完之后，把不足一帧的尾巴搬进 carry（下一包接上）。 */
    private void stashRemainder() {
        if (source == null) {
            return;
        }
        int remaining = source.remaining();
        if (remaining <= 0) {
            source = null;
            return;
        }
        if (remaining % frameBytes != 0) {
            int keep = remaining % frameBytes;
            int start = source.limit() - keep;
            if (carryLength + keep > MAX_CARRY) {
                // 理论上不会发生（keep < frameBytes ≤ MAX_CARRY）；真发生了宁可丢掉也不要越界
                source = null;
                return;
            }
            source.position(start);
            source.get(carry, carryLength, keep);
            carryLength += keep;
            source.limit(start);
        }
        if (!source.hasRemaining()) {
            source = null;
        }
    }

    /** 手上还留着多少「半个采样」的字节（诊断/测试用；正常应当 < 一帧）。 */
    public int carryBytes() {
        return carryFrameReady ? 0 : carryLength;
    }

    /** 这一包是不是已经吃完（诊断用）。 */
    public boolean exhausted() {
        return !carryFrameReady && (source == null || !source.hasRemaining());
    }

    /** 字节序固定小端：安卓的 ABI 全是小端，PCM 也是小端。 */
    static ByteOrder pcmOrder() {
        return ByteOrder.LITTLE_ENDIAN;
    }
}
