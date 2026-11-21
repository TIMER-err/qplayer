package dev.t1m3.qplayer.audio.engine;

/**
 * 三段频谱电平的<strong>进程级出口</strong>：播放引擎每块发布一次，界面（歌词动态背景）每帧读一次。
 *
 * <p>为什么要这么一个类：引擎的生产线程与界面的渲染线程是两条线程，而这两件事
 * <strong>都不该为对方停下</strong>——渲染线程不能去拿引擎的锁（那是音频路径上的锁），
 * 引擎也不能等界面。所以这里是一个单向的、无锁的发布点：
 *
 * <ul>
 *   <li>发布：{@link #publish(float, float, float)}，每个音频块（约 20 ms）一次；</li>
 *   <li>读取：{@link #copyInto(float[], int)}，渲染线程每帧拷一份快照。</li>
 * </ul>
 *
 * <h3>为什么要打包成一个 {@code long}</h3>
 *
 * <p>三个 {@code volatile float} 分开写会出现<strong>撕裂</strong>：读者可能拿到
 * 「这一块的低频 + 上一块的高频」，画面在鼓点上会抽一下。打包成一个 {@code volatile long}
 * 之后，读者要么全拿到新的、要么全拿到旧的，而且<strong>不分配</strong>——
 * 每块 new 一个 float[3] 虽然也不贵（50 次/秒），但音频生产线程上能省则省。
 *
 * <p>编码精度：每个频段 10 位（1/1023 ≈ 0.001），电平表用不上更细的。
 */
public final class SpectrumTap {

    /** 频段数：低 / 中 / 高。 */
    public static final int BANDS = 3;

    private static final int BITS = 10;
    private static final int MASK = (1 << BITS) - 1;
    private static final float QUANT = MASK;

    /** 打包后的三段电平（低在高位）。初始为全 0（＝没在放歌）。 */
    private static volatile long packed = 0L;

    private SpectrumTap() {
    }

    /** 发布一块的电平（0..1 之外的取值会被夹住）。 */
    public static void publish(float low, float mid, float high) {
        long l = quantize(low);
        long m = quantize(mid);
        long h = quantize(high);
        packed = (l << (BITS * 2)) | (m << BITS) | h;
    }

    /** 回到「没有声音」：暂停、停止、会话结束都要调，否则画面会停在最后一个电平上。 */
    public static void reset() {
        packed = 0L;
    }

    /**
     * 把当前电平快照写进 {@code dst[offset..offset+2]}。
     *
     * <p>{@code dst} 太短时一个字节都不写（宁可什么都不给，也不要写半个快照）。
     */
    public static void copyInto(float[] dst, int offset) {
        if (dst == null || dst.length < offset + BANDS) {
            return;
        }
        long snapshot = packed;
        dst[offset] = dequantize((snapshot >>> (BITS * 2)) & MASK);
        dst[offset + 1] = dequantize((snapshot >>> BITS) & MASK);
        dst[offset + 2] = dequantize(snapshot & MASK);
    }

    /** 直接读一个频段（测试/诊断用）；{@code band} 取 0/1/2。 */
    public static float band(int band) {
        float[] tmp = new float[BANDS];
        copyInto(tmp, 0);
        return tmp[band];
    }

    private static long quantize(float value) {
        if (!(value > 0f)) {           // 同时挡住 NaN
            return 0L;
        }
        float clamped = value >= 1f ? 1f : value;
        return (long) (clamped * QUANT + 0.5f);
    }

    private static float dequantize(long value) {
        return (float) value / QUANT;
    }
}
