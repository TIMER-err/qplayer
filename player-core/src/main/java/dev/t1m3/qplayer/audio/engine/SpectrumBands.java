package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.dsp.Fft;

/**
 * 实时频谱的<strong>进程级出口</strong>（工作站的频谱显示用）：{@value #BANDS} 条对数频带，
 * 生产者每块发布一次，界面每帧读一次。
 *
 * <h2>为什么是「对数频带」而不是「FFT 的 512 根线」</h2>
 *
 * <p>人耳听频率是对数的：20 Hz–20 kHz 摊成 512 条等宽线时，低频那一半挤在最左边几像素里
 * （20–200 Hz 只占 6% 的宽度，而那是音乐里最要紧的一段）。所以这里按<strong>对数</strong>把
 * 20 Hz–20 kHz 分成 {@value #BANDS} 条，每条取该区间内的最大幅度——低频因此有足够的宽度，
 * 而高频不会浪费分辨率。这也是所有频谱分析仪的做法。
 *
 * <h2>无锁、零分配（音频线程）</h2>
 *
 * <p>双缓冲 + 一个 {@code volatile int}：写线程写另一份、写完再翻索引；
 * 读线程读当前那一份。最坏情况是读到上一块的数据（20 ms 前的频谱），而对显示来说
 * <strong>旧一块比撕裂好</strong>。整个发布过程<strong>不分配</strong>
 * （FFT 的表、窗、暂存都在构造时算好），所以它可以放在音频线程上。
 *
 * <p>与 {@link SpectrumTap}（三段电平）的分工：那个给歌词背景那种「只要有起伏」的画面用，
 * 这个给工作站的频谱用（要看得清频段）。两个都保留。
 */
public final class SpectrumBands {

    /** 频带数。64 条在 900 px 宽的画布上每条约 14 px，够细也够快。 */
    public static final int BANDS = 64;

    /** FFT 窗长（帧）。1024 @48 kHz = 21 ms：够低到 47 Hz 一个 bin，够短到跟得上鼓点。 */
    public static final int FFT_SIZE = 1024;

    /** 频带的下限 / 上限（Hz）。 */
    public static final double MIN_HZ = 20.0;
    public static final double MAX_HZ = 20_000.0;

    /** 两份缓冲：写一份、读一份。 */
    private static final float[][] BUFFERS = new float[2][BANDS];
    /** 当前可读的那一份（写线程翻它）。 */
    private static volatile int published = 0;

    private SpectrumBands() {
    }

    /**
     * 频带的中心频率（Hz）——画坐标轴与「拖到某个频段」时用它，界面读一次就够。
     */
    public static double bandHz(int index) {
        int i = Math.max(0, Math.min(BANDS - 1, index));
        double lo = Math.log10(MIN_HZ);
        double hi = Math.log10(MAX_HZ);
        return Math.pow(10.0, lo + (hi - lo) * (i + 0.5) / BANDS);
    }

    /**
     * 把一份 dB 值（{@value #BANDS} 条）发布出去。
     *
     * @param db 每条频带的电平（dBFS，已经取过最大值；{@code -120} 表示静音）
     */
    public static void publish(float[] db) {
        if (db == null || db.length < BANDS) {
            return;
        }
        int next = 1 - published;
        float[] target = BUFFERS[next];
        System.arraycopy(db, 0, target, 0, BANDS);
        published = next;                      // 写完再翻：读者要么看到旧的、要么看到新的
    }

    /** 回到「没有声音」。 */
    public static void reset() {
        for (float[] b : BUFFERS) {
            java.util.Arrays.fill(b, -120f);
        }
    }

    /**
     * 把当前频谱写进 {@code dst}。
     *
     * @return 写进去了几条（{@code dst} 太短时写满为止）
     */
    public static int copyInto(float[] dst) {
        if (dst == null) {
            return 0;
        }
        float[] src = BUFFERS[published];
        int n = Math.min(dst.length, BANDS);
        System.arraycopy(src, 0, dst, 0, n);
        return n;
    }

    /**
     * 音频线程用的分析器：把一块（交错 f32）算成 {@value #BANDS} 条 dB 值。
     *
     * <p>它是有状态的（要留一块的尾巴做重叠），每个会话建一个；
     * 所有缓冲构造时分配，{@link #process} <strong>零分配</strong>。
     */
    public static final class Analyzer {

        private final Fft fft = new Fft(FFT_SIZE);
        private final float[] window = new float[FFT_SIZE];
        private final float[] re = new float[FFT_SIZE];
        private final float[] im = new float[FFT_SIZE];
        /** 单声道混音的最新一窗（每次前移一块）。 */
        private final float[] history = new float[FFT_SIZE];
        /** 复用给 {@link #publish} 的那一条。 */
        private final float[] out = new float[BANDS];
        /** 每条频带覆盖的 bin 区间（预先算好，逐块只做 max）。 */
        private final int[] binFrom = new int[BANDS];
        private final int[] binTo = new int[BANDS];
        private final float norm;

        public Analyzer(int rate) {
            int r = Math.max(1, rate);
            Fft.hann(window);
            for (int b = 0; b < BANDS; b++) {
                double lo = Math.log10(MIN_HZ);
                double hi = Math.log10(MAX_HZ);
                double f0 = Math.pow(10.0, lo + (hi - lo) * b / BANDS);
                double f1 = Math.pow(10.0, lo + (hi - lo) * (b + 1) / BANDS);
                int k0 = (int) Math.floor(f0 / r * FFT_SIZE);
                int k1 = (int) Math.ceil(f1 / r * FFT_SIZE);
                binFrom[b] = Math.max(0, Math.min(FFT_SIZE / 2 - 1, k0));
                binTo[b] = Math.max(binFrom[b], Math.min(FFT_SIZE / 2, k1));
            }
            // 单边谱的幅度归一化：除以窗的相干增益（汉宁窗 = 0.5）
            norm = 1f / (FFT_SIZE * 0.5f);
            java.util.Arrays.fill(out, -120f);
        }

        /** 清状态（换曲、跳转、重开设备）。 */
        public void reset() {
            java.util.Arrays.fill(history, 0f);
            java.util.Arrays.fill(out, -120f);
            publish(out);
        }

        /**
         * 算一块并发布。
         *
         * @param interleaved 交错 f32 采样
         * @param samples     采样数（= 帧数 × 声道数）
         * @param channels    声道数
         */
        public void process(float[] interleaved, int samples, int channels) {
            if (interleaved == null || samples <= 0 || channels <= 0) {
                return;
            }
            int frames = samples / channels;
            // ① 单声道混音挪进历史窗（左移 frames，最多留一整个窗）
            int keep = Math.min(FFT_SIZE, frames);
            int shift = Math.min(frames, FFT_SIZE);
            System.arraycopy(history, shift, history, 0, FFT_SIZE - shift);
            int at = FFT_SIZE - keep;
            for (int i = 0; i < keep; i++) {
                float sum = 0f;
                int base = i * channels;
                for (int ch = 0; ch < channels; ch++) {
                    sum += interleaved[base + ch];
                }
                history[at + i] = sum / channels;
            }
            // ② 加窗 + FFT
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = history[i] * window[i];
                im[i] = 0f;
            }
            fft.forward(re, im);
            // ③ 每条频带取该区间内 bin 的最大幅度
            for (int b = 0; b < BANDS; b++) {
                float peak = 0f;
                for (int k = binFrom[b]; k <= binTo[b] && k <= FFT_SIZE / 2; k++) {
                    double m = Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]) * norm;
                    if (m > peak) {
                        peak = (float) m;
                    }
                }
                float db = peak > 1e-7f ? (float) (20.0 * Math.log10(peak)) : -120f;
                // 显示要平一点：只升不降的「慢落」包络（每秒落 ~30 dB）
                float previous = out[b];
                out[b] = db > previous ? db : Math.max(db, previous - 1.5f);
            }
            publish(out);
        }
    }
}
