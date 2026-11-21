package dev.t1m3.qplayer.audio.dsp;

/**
 * <b>跟拍延迟</b>（名字里带 DELAY 的那 3 档用 ✓；2026-10-07「让名不副实的档名副其实」）。
 *
 * <p>在这之前那 3 档跑的是 `tailRide` / `learnedEq` ✗ —— 用户的原话是「怎么感觉不对」✓。
 *
 * <h2>结构</h2>
 *
 * <p>每声道一条环形延迟线 + 反馈（反馈路上带一阶低通 ⇒ 每一次回声都比上一次闷一点 ✓，
 * 这是"房间里回响"和"数字重复"的区别 ✓）。延时长度由**拍长**给（调用方算好样本数 ✓），
 * 所以回声落在拍上 ✓。
 *
 * <h2>渲染路径的硬要求</h2>
 *
 * <ul>
 *   <li><b>零分配</b>：缓冲在构造时按采样率与**最大延时**一次分好 ✓（{@code process} 里不建对象 ✓）；</li>
 *   <li>{@link #setDelayFrames(long)} 只改读写下标 ✓（原地 ✓，可以随拍长变化 ✓）；</li>
 *   <li>{@link #reset()} 清干净 ✓；湿量为 0 时调用方**根本不喂** ⇒ 逐位可回退 ✓。</li>
 * </ul>
 */
public final class Delay {

    /** 最大延时（秒）—— 覆盖慢速曲子的 1/2 拍还有余 ✓。 */
    private static final double MAX_SECS = 2.0;

    private final int channels;
    private final float[][] buf;
    private final int[] size;
    private final int[] write;
    private final float[] lp;               // 反馈路上的低通状态
    private int delayFrames;
    private final float feedback;
    private final float damping;

    /**
     * @param rate      采样率（Hz）
     * @param channels  声道数
     * @param feedback  反馈量（0..0.9 ✓；越大回声次数越多 ✓）
     * @param damping   反馈低通（0..0.95 ✓；越大人声回声越闷 ✓）
     */
    public Delay(int rate, int channels, float feedback, float damping) {
        this.channels = Math.max(1, channels);
        this.feedback = Math.max(0f, Math.min(0.9f, feedback));
        this.damping = Math.max(0f, Math.min(0.95f, damping));
        int cap = Math.max(64, (int) Math.round(Math.max(8000, rate) * MAX_SECS));
        this.buf = new float[this.channels][];
        this.size = new int[this.channels];
        this.write = new int[this.channels];
        this.lp = new float[this.channels];
        for (int c = 0; c < this.channels; c++) {
            this.buf[c] = new float[cap];
            this.size[c] = cap;
            this.write[c] = 0;
        }
        this.delayFrames = Math.max(1, cap / 4);
    }

    /** 设延时长度（样本 ✓）；越界会被夹到缓冲容量内 ✓。 */
    public void setDelayFrames(long frames) {
        this.delayFrames = (int) Math.max(1L, Math.min((long) size[0] - 1L, frames));
    }

    /** 当前延时长度（样本 ✓；诊断/判据用 ✓）。 */
    public int delayFrames() {
        return delayFrames;
    }

    /** 清空（下一次过渡不带上一首的回声 ✓）。 */
    public void reset() {
        for (int c = 0; c < channels; c++) {
            java.util.Arrays.fill(buf[c], 0f);
            write[c] = 0;
            lp[c] = 0f;
        }
    }

    /** 处理一个样本（**零分配** ✓）。 */
    public float process(int channel, float x) {
        int c = Math.min(Math.max(0, channel), channels - 1);
        float[] b = buf[c];
        int cap = size[c];
        int w = write[c];
        int r = w - delayFrames;
        if (r < 0) {
            r += cap;
        }
        float delayed = b[r];
        // 反馈路上的低通：每一次回声都更闷一点 ✓
        lp[c] = delayed * (1f - damping) + lp[c] * damping;
        b[w] = x + lp[c] * feedback;
        write[c] = w + 1 >= cap ? 0 : w + 1;
        return delayed;
    }
}
