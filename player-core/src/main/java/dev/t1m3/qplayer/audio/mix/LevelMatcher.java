package dev.t1m3.qplayer.audio.mix;

/**
 * <b>逐帧响度匹配</b>（P4）：融合期让进场的 B 在<b>响度上贴着</b>出场的 A 走。
 *
 * <h3>它治的是哪一种「糊」</h3>
 *
 * <p>用户报「有时候会两首歌皱在一起很糊」。等功率交叉淡化只保证<b>功率和</b>是常数，
 * 对两首歌<b>各自的响度</b>一无所知：母带差 6 dB 的两首歌叠在一起，听到的就是「一首把
 * 另一首埋了」——埋掉的那首只剩一层糊底。空间展宽（P2）把两首歌在<b>位置</b>上分开，
 * 这一层把它们在<b>响度</b>上拉到同一档。
 *
 * <h3>为什么是「慢的」匹配，而不是 AGC</h3>
 *
 * <p>两种时间常数都取得<b>慢</b>，这是有意的：
 *
 * <ul>
 *   <li>响度估计 {@value #POWER_TAU_SECS} s（≈ 短时响度）——比一拍长得多，所以不会被鼓点
 *       带着抖；</li>
 *   <li>增益跟随 {@value #GAIN_TAU_SECS} s——慢到听不出「压缩在泵」，台阶也自然不存在。</li>
 * </ul>
 *
 * <p>代价是<b>短时动态会被保留</b>：B 自己的强弱起伏不会被换成 A 的包络（那正是 AGC 的听感），
 * 匹配的只是它的<b>平均档位</b>。上限 {@value #MAX_GAIN_DB} dB（双向）：安静的前奏不该被拉飞，
 * 响的那首也不该被压死。
 *
 * <h3>两处刻意的保守</h3>
 *
 * <p>1. <b>B 静音时不抬</b>（目标增益留在 1）：把静音乘上一个大增益，除了放大底噪什么也得不到，
 * 还会在极安静的前奏上放出一层空气声。
 *
 * <p>2. <b>状态只按「真正交付的帧」推进</b>：这个类是有状态的，所以它必须在<b>混音那一层</b>
 * （{@link Mixer#mixTwoMatched}）逐帧驱动，而不是先算一整块增益数组再去混。素材缓冲里
 * 可能留着上一块没交付的余量，先算数组就会把那几帧<b>算两遍</b>——状态跑偏，且是累积的
 * （2026-10-04「进度回跳」就是同一类 bug：多算或少算的帧不会自己还回来）。
 */
public final class LevelMatcher {

    /** 响度估计的时间常数（秒）。≈ 短时响度，比一拍长，不会被鼓点带着抖。 */
    static final float POWER_TAU_SECS = 0.4f;

    /** 增益跟随的时间常数（秒）。慢到听不出压缩在泵，也因此不会有台阶。 */
    static final float GAIN_TAU_SECS = 0.25f;

    /** 增益上限（dB，双向）：±6 dB。 */
    static final float MAX_GAIN_DB = 6.0f;

    /** 上限折算成线性增益（≈1.9953）。 */
    static final float MAX_GAIN = (float) Math.pow(10.0, MAX_GAIN_DB / 20.0);

    /** 下限折算成线性增益（≈0.5012）。 */
    static final float MIN_GAIN = 1.0f / MAX_GAIN;

    /** 低于这个功率就当没有声音（−100 dB），不去算比值。 */
    private static final float SILENT_POWER = 1e-10f;

    private final float powerAlpha;
    private final float gainAlpha;

    private float powerA;
    private float powerB;
    private float gain = 1.0f;
    private float lastGain = 1.0f;

    public LevelMatcher(int rate) {
        this.powerAlpha = alphaFor(POWER_TAU_SECS, rate);
        this.gainAlpha = alphaFor(GAIN_TAU_SECS, rate);
    }

    /** 一阶低通系数：{@code 1 − exp(−1/(τ·rate))}；rate 无效时退回 1（等于不平滑）。 */
    private static float alphaFor(float tauSecs, int rate) {
        if (rate <= 0) {
            return 1.0f;
        }
        double a = 1.0 - Math.exp(-1.0 / ((double) tauSecs * rate));
        return (float) Math.max(0.0, Math.min(1.0, a));
    }

    /** 回到起始状态（每次过渡开始时调用，别把上一首歌的档位带进来）。 */
    public void reset() {
        powerA = 0.0f;
        powerB = 0.0f;
        gain = 1.0f;
        lastGain = 1.0f;
    }

    /**
     * 交一帧两侧的功率（该帧各样点平方的<b>均值</b>），返回这一帧该给 B 的增益。
     *
     * @param strength 0..1：0 = 完全不动（返回 1），1 = 匹配到上限为止
     */
    public float gainForFrame(float framePowerA, float framePowerB, float strength) {
        powerA += (framePowerA - powerA) * powerAlpha;
        powerB += (framePowerB - powerB) * powerAlpha;

        float target = 1.0f;
        if (powerB > SILENT_POWER && powerA > SILENT_POWER) {
            target = (float) Math.sqrt((double) (powerA / powerB));
            if (target > MAX_GAIN) {
                target = MAX_GAIN;
            } else if (target < MIN_GAIN) {
                target = MIN_GAIN;
            }
        }
        gain += (target - gain) * gainAlpha;

        float s = strength;
        if (s <= 0.0f) {
            lastGain = 1.0f;
            return lastGain;
        }
        if (s > 1.0f) {
            s = 1.0f;
        }
        lastGain = 1.0f + (gain - 1.0f) * s;
        return lastGain;
    }

    /**
     * 逐帧增益用的暂存（**复用**，不每次分配）。
     *
     * <p>混音是热路径：一个 8192 帧的块每次分配 32 KB，一秒一百来块，全是年轻代垃圾。
     * 这个数组只在一次 {@code mixTwoMatched} 调用内有效，调用之间不保留内容。
     */
    public float[] buffer(int frames) {
        if (gainBuffer == null || gainBuffer.length < frames) {
            gainBuffer = new float[Math.max(frames, 1024)];
        }
        return gainBuffer;
    }

    private float[] gainBuffer;

    /** 上一帧交付的增益（判据与日志用；渲染不看它）。 */
    public float lastGain() {
        return lastGain;
    }
}
