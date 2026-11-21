package dev.t1m3.qplayer.audio.dsp;

/**
 * <b>自研小混响</b>（Schroeder 结构：每声道 4 个并联梳状 + 2 个串联全通 ✓）。
 *
 * <h2>为什么自己写</h2>
 *
 * <p>用户 2026-10-07：「名字里带 REVERB 的档听着跟朴素淡化一样 —— 全部让他们名副其实」✓。
 * 而仓库里**一个混响实现都没有** ✗（`AiMix` 里搜 reverb/delay 是 0 命中 ✓）。
 * 这里按最经典、最省的结构做一个：不追求录音室品质，追求"**听得出来是混响**、
 * 而且在渲染路径上绝对安全" ✓。
 *
 * <h2>渲染路径的硬要求（这个类是照着它们写的）</h2>
 *
 * <ul>
 *   <li><b>零分配</b>：所有缓冲在构造时按采样率一次分好 ✓，{@code process} 里一个对象都不建 ✓；</li>
 *   <li><b>可重置</b>：{@link #reset()} 清干净，下一次过渡不带上一首的尾巴 ✓；</li>
 *   <li><b>可关掉</b>：湿量为 0 时调用方**根本不喂**它 ✓ ⇒ 与该档加混响之前逐位相同 ✓。</li>
 * </ul>
 *
 * <h2>参数</h2>
 *
 * <ul>
 *   <li>{@code decaySecs}：RT60（混响衰减 60 dB 的时间 ✓）—— 梳状反馈由它反算 ✓；</li>
 *   <li>{@code damping}：梳状反馈里的一阶低通系数（0 = 全反射、越接近 1 越闷 ✓）——
 *       真实房间的高频衰减更快 ✓，这一项就是那个 ✓。</li>
 * </ul>
 *
 * <p>延迟长度取自 Schroeder 的经典互质表（44.1 kHz 下的样本数 ✓），按采样率缩放 ✓；
 * 左右两声道取不同的长度 ⇒ 自然的立体声宽度 ✓（不额外做乒乓 ✗）。
 */
public final class Reverb {

    /** 每声道的梳状滤波器数量。 */
    private static final int COMBS = 4;
    /** 每声道的全通数量。 */
    private static final int ALLPASSES = 2;
    /** 经典延迟长度（44.1 kHz 样本）：互质 ⇒ 不共振 ✓；左右各一组 ✓。 */
    private static final int[][] COMB_LEN_44K = {
        {1116, 1188, 1277, 1356},          // 左
        {1139, 1211, 1301, 1381},          // 右（与左不同 ⇒ 有宽度 ✓）
    };
    private static final int[][] ALLPASS_LEN_44K = {
        {556, 441},
        {579, 464},
    };
    /** 全通的固定反馈（Schroeder 经典值 ✓）。 */
    private static final float ALLPASS_G = 0.5f;

    private final int channels;
    private final float[][] combBuf;       // [声道][梳状序号] 的环形缓冲
    private final int[][] combSize;
    private final int[][] combIdx;
    private final float[][] combStore;     // 每个梳状的反馈低通状态
    private final float[] combG;           // 每个梳状的反馈系数（由 RT60 反算 ✓）
    private final float[][] apBuf;         // [声道][全通序号]
    private final int[][] apSize;
    private final int[][] apIdx;
    private final float damp;

    /**
     * @param rate       采样率（Hz）
     * @param channels   声道数（1 或 2；更多声道按第 0/1 组复用 ✓）
     * @param decaySecs  RT60（秒；≤0 ⇒ 当成 0.1 s 的下限 ✓）
     * @param damping    高频衰减（0..0.95 ✓；越大人声尾巴越闷 ✓）
     */
    public Reverb(int rate, int channels, float decaySecs, float damping) {
        this.channels = Math.max(1, channels);
        int r = Math.max(8000, rate);
        float decay = Math.max(0.1f, decaySecs);
        this.damp = Math.max(0f, Math.min(0.95f, damping));
        this.combBuf = new float[this.channels][];
        this.combSize = new int[this.channels][];
        this.combIdx = new int[this.channels][];
        this.combStore = new float[this.channels][];
        this.combG = new float[COMBS];
        this.apBuf = new float[this.channels][];
        this.apSize = new int[this.channels][];
        this.apIdx = new int[this.channels][];
        for (int c = 0; c < this.channels; c++) {
            int[] lens = COMB_LEN_44K[c % COMB_LEN_44K.length];
            combBuf[c] = new float[COMBS * 2048];
            combSize[c] = new int[COMBS];
            combIdx[c] = new int[COMBS];
            combStore[c] = new float[COMBS];
            for (int i = 0; i < COMBS; i++) {
                int len = Math.max(8, Math.round(lens[i] * (float) r / 44100f));
                combSize[c][i] = len;
                // ⚠️ 环**下标**从 0 起 ✓（槽位基址 `i * 2048` 是**用的时候**才加的 ✗ ——
                // 我第一版把基址当下标写进来 ⇒ 起始就越界，判据当场 ArrayIndexOutOfBounds ✗）。
                combIdx[c][i] = 0;
            }
            int[] alens = ALLPASS_LEN_44K[c % ALLPASS_LEN_44K.length];
            apBuf[c] = new float[ALLPASSES * 1024];
            apSize[c] = new int[ALLPASSES];
            apIdx[c] = new int[ALLPASSES];
            for (int i = 0; i < ALLPASSES; i++) {
                int len = Math.max(8, Math.round(alens[i] * (float) r / 44100f));
                apSize[c][i] = len;
                apIdx[c][i] = 0;                          // 同上：下标从 0 起 ✓
            }
        }
        for (int i = 0; i < COMBS; i++) {
            // RT60：g^(len/(decay·rate)) = 10^-3 ⇒ g = 10^(-3·len/(decay·rate)) ✓
            double len = COMB_LEN_44K[0][i];
            combG[i] = (float) Math.pow(10.0, -3.0 * len / (decay * 44100.0));
        }
    }

    /** 清空所有缓冲（下一次过渡不带上一首的尾巴 ✓）。 */
    public void reset() {
        for (int c = 0; c < channels; c++) {
            java.util.Arrays.fill(combBuf[c], 0f);
            java.util.Arrays.fill(combStore[c], 0f);
            java.util.Arrays.fill(apBuf[c], 0f);
            for (int i = 0; i < COMBS; i++) {
                combIdx[c][i] = 0;                    // 下标归零 ✓（基址不在里面 ✗）
            }
            for (int i = 0; i < ALLPASSES; i++) {
                apIdx[c][i] = 0;
            }
        }
    }

    /** 处理一个样本（**零分配** ✓）。 */
    public float process(int channel, float x) {
        int c = Math.min(Math.max(0, channel), channels - 1);
        float[] cb = combBuf[c];
        int[] cs = combSize[c];
        int[] ci = combIdx[c];
        float[] store = combStore[c];
        float sum = 0f;
        for (int i = 0; i < COMBS; i++) {
            int base = i * 2048;
            int idx = ci[i];
            float v = cb[base + idx];
            // 反馈低通：真实房间高频衰减更快 ✓
            store[i] = v * (1f - damp) + store[i] * damp;
            cb[base + idx] = x + store[i] * combG[i];
            ci[i] = idx + 1 >= cs[i] ? 0 : idx + 1;
            sum += v;
        }
        sum *= 1f / COMBS;
        float[] ab = apBuf[c];
        int[] as = apSize[c];
        int[] ai = apIdx[c];
        for (int i = 0; i < ALLPASSES; i++) {
            int base = i * 1024;
            int idx = ai[i];
            float buffered = ab[base + idx];
            float out = -sum + buffered;
            ab[base + idx] = sum + buffered * ALLPASS_G;
            ai[i] = idx + 1 >= as[i] ? 0 : idx + 1;
            sum = out;
        }
        return sum;
    }
}
