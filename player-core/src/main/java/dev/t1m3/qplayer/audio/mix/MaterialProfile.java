package dev.t1m3.qplayer.audio.mix;

/**
 * <b>挑接法要用的"素材画像"</b>（2026-10-07）。
 *
 * <p>{@link Chooser} 原来只看得到两件事：速度差与调性相似度 ✗。于是它只能在
 * 「朴素融合 / 低音交接」之间二选一，其余四档永远进不了自动档 ✗。要把六档都接上，
 * 就得先有**可测的素材特征** ✗ —— 这个类就是那六个量。
 *
 * <h3>为什么在"素材整体"上算，而不是在具体点位上算</h3>
 *
 * <p>挑接法发生在**选窗口之前**（见 {@code AiMix.prepare} 的 autoPick 分支 ✓）：
 * 那时还不知道出场点/入场点/窗口 ✗。所以这些量必须只看 A 的尾段与 B 的头段本身 ✓
 * ——这与 {@code Harmonic.match} 拿 tail/head 的 PCM 去比是同一个道理 ✓。
 *
 * <h3>六个量各自回答什么</h3>
 * <ul>
 *   <li>{@link #lowClash}：两边低频**真的**撞上了吗（撞上才有"糊"这回事 ✓）；</li>
 *   <li>{@link #dropReliable}：窗口里有没有一个**站得住的能量峰**（学习EQ 的拐点
 *       绑在 drop 上 ✓；站不住 ⇒ 它的曲线会退化成"恒定压一点"✗，那不如低音交接 ✓）；</li>
 *   <li>{@link #tailDecayDbPerSec}：A 的尾巴是不是**长衰减**（值得留着骑在 B 上 ✓）；</li>
 *   <li>{@link #headOnset}：B 是**满拍进**还是**留白进**（留白才容得下 A 的尾巴 ✓）；</li>
 *   <li>{@link #overlapSecs}：A 尾/B 头**都够长**吗（都不够 ⇒ 没东西可叠 ✓）；</li>
 *   <li>{@link #buildUp}：A 的尾段是不是在**往上堆**（配合 B 起点紧跟 drop ⇒ 扫频才服务于音乐 ✓）。</li>
 * </ul>
 *
 * <p>算不出来一律给"未知"（{@link #known()} = false ✓）——沿用本仓库那条纪律：
 * <b>算不出来就别猜</b>，{@link Chooser} 会因此退回朴素融合 ✓。
 */
public final class MaterialProfile {

    /** 逐帧能量的帧率（与 {@code AutoCue.HOP_SECS} 一致：50 Hz）。 */
    static final double FRAME_HZ = 50.0;

    /** 低频段的判定频率（一阶低通）。 */
    static final double LOW_HZ = 200.0;

    /** 每个量算不出来时的取值。 */
    public static final MaterialProfile UNKNOWN =
            new MaterialProfile(false, 0f, 0f, 0f, 0f, 0f, false, 0f, 0f);

    private final boolean known;
    private final float lowClash;
    private final float dropReliable;
    private final float tailDecayDbPerSec;
    private final float headOnset;
    private final float overlapSecs;
    private final boolean buildUp;

    /** 两侧低频占比的均值（0..1）——{@code genreScores} 的入参之一。 */
    private final float lowRatio;

    /** 起跳强度（A 侧包络的峰值因子，0..1）——{@code genreScores} 的另一个入参。 */
    private final float onset;

    MaterialProfile(boolean known, float lowClash, float dropReliable,
                    float tailDecayDbPerSec, float headOnset, float overlapSecs,
                    boolean buildUp, float lowRatio, float onset) {
        this.known = known;
        this.lowClash = lowClash;
        this.dropReliable = dropReliable;
        this.tailDecayDbPerSec = tailDecayDbPerSec;
        this.headOnset = headOnset;
        this.overlapSecs = overlapSecs;
        this.buildUp = buildUp;
        this.lowRatio = lowRatio;
        this.onset = onset;
    }

    /** 两侧低频占比的均值（供 {@code genreScores} 判曲风）。 */
    public float lowRatio() {
        return lowRatio;
    }

    /** 起跳强度（供 {@code genreScores} 判曲风）。 */
    public float onset() {
        return onset;
    }

    /** 六个量是不是都算出来了（false ⇒ 调用方应当退回朴素融合 ✓）。 */
    public boolean known() {
        return known;
    }

    /** 两侧低频的重叠程度（0..1，逐帧 lowA×lowB 的均值归一化）。越高越"糊"。 */
    public float lowClash() {
        return lowClash;
    }

    /** 能量峰的显著度（0..1）：峰减均值再归一。越高越像"有明确的 drop"。 */
    public float dropReliable() {
        return dropReliable;
    }

    /** A 尾段的衰减斜率（dB/秒，负 = 在衰减）。接近 0 或为正 = 长尾/还在堆。 */
    public float tailDecayDbPerSec() {
        return tailDecayDbPerSec;
    }

    /** B 头段的起跳强度（0..1）：前 2 秒的 onset 峰值因子。越低越"留白进"。 */
    public float headOnset() {
        return headOnset;
    }

    /** A 尾与 B 头都够长的秒数（两者的较短者）。 */
    public float overlapSecs() {
        return overlapSecs;
    }

    /** A 的尾段是不是在往上堆（build-up ✓）。 */
    public boolean buildUp() {
        return buildUp;
    }

    @Override
    public String toString() {
        if (!known) {
            return "素材画像：未知";
        }
        return String.format(java.util.Locale.ROOT,
                "素材画像：低频冲突 %.2f · drop 显著度 %.2f · A 尾斜率 %+.1f dB/s · "
                        + "B 起跳 %.2f · 可叠 %.1fs · build-up %s",
                Float.valueOf(lowClash), Float.valueOf(dropReliable),
                Float.valueOf(tailDecayDbPerSec), Float.valueOf(headOnset),
                Float.valueOf(overlapSecs), buildUp ? "是" : "否");
    }

    /**
     * 从两侧的整段 PCM 画像。
     *
     * @param tail      A 的尾段（单声道或已混单，取末尾若干秒）
     * @param head      B 的头段
     * @param tailSecs  A 尾段实际可用的秒数（用来判"有没有东西可叠"）
     * @param headSecs  B 头段实际可用的秒数
     * @param rate      采样率
     * @return 画像；输入不成立时返回 {@link #UNKNOWN}
     */
    public static MaterialProfile of(float[] tail, float[] head, double tailSecs,
                                     double headSecs, int rate) {
        if (tail == null || head == null || rate <= 0 || tail.length < 2 || head.length < 2) {
            return UNKNOWN;
        }
        int hop = Math.max(1, (int) Math.round(rate / FRAME_HZ));
        float[] envTail = envelope(tail, hop);
        float[] envHead = envelope(head, hop);
        float[] lowTail = lowEnvelope(tail, hop, rate);
        float[] lowHead = lowEnvelope(head, hop, rate);
        if (envTail.length < 4 || envHead.length < 4) {
            return UNKNOWN;
        }
        float[] combined = new float[Math.min(envTail.length, envHead.length)];
        for (int i = 0; i < combined.length; i++) {
            combined[i] = envTail[i] + envHead[i];
        }
        float lowClash = lowClash(lowTail, envTail, lowHead, envHead);
        float drop = prominence(combined);
        float decay = slopeDbPerSec(envTail, FRAME_HZ);
        float onset = onsetFactor(envHead, FRAME_HZ * 2.0);       // 只看前 2 秒 ✓
        float overlap = (float) Math.max(0.0, Math.min(tailSecs, headSecs));
        boolean buildUp = decay > -0.5f && rises(envTail);
        // lowRatio：两侧低频占比的均值；midRatio 由调用方按 1 − lowRatio 近似（如实标注）
        double ra = mean(lowTail) / Math.max(mean(envTail), 1e-9);
        double rb = mean(lowHead) / Math.max(mean(envHead), 1e-9);
        float lowRatio = (float) Math.max(0.0, Math.min(1.0, (ra + rb) / 2.0));
        float onsetStrength = onsetFactor(envTail, FRAME_HZ * 2.0);
        return new MaterialProfile(true, lowClash, drop, decay, onset, overlap, buildUp,
                lowRatio, onsetStrength);
    }

    /** 逐帧 RMS 包络（去均值）。 */
    static float[] envelope(float[] pcm, int hop) {
        int frames = pcm.length / hop;
        float[] out = new float[Math.max(0, frames)];
        for (int f = 0; f < out.length; f++) {
            double sum = 0.0;
            for (int i = 0; i < hop; i++) {
                float v = pcm[f * hop + i];
                sum += (double) v * v;
            }
            out[f] = (float) Math.sqrt(sum / hop);
        }
        return out;
    }

    /** 低频段（一阶低通）的逐帧 RMS。 */
    static float[] lowEnvelope(float[] pcm, int hop, int rate) {
        double a = 1.0 - Math.exp(-2.0 * Math.PI * LOW_HZ / rate);
        float[] lp = new float[pcm.length];
        double y = 0.0;
        for (int i = 0; i < pcm.length; i++) {
            y += a * (pcm[i] - y);
            lp[i] = (float) y;
        }
        return envelope(lp, hop);
    }

    /**
     * <b>低频冲突</b>：两侧"低频占比"的<b>较小者</b>。
     *
     * <p>第一版算的是"两边低频包络的相关性"✗ —— 那是个坏定义：两个完全同形的东西
     * （比如两首歌在同一段里低频都平）会得到 0，而"两边都很有低频"恰恰是我们要抓的
     * 那种冲突。改成占比取小：**只要有一边没有低频，就没有"撞上"这回事**；
     * 两边都沉，才叫撞上。
     */
    static float lowClash(float[] lowA, float[] allA, float[] lowB, float[] allB) {
        double ra = mean(lowA) / Math.max(mean(allA), 1e-9);
        double rb = mean(lowB) / Math.max(mean(allB), 1e-9);
        return (float) Math.max(0.0, Math.min(1.0, Math.min(ra, rb)));
    }

    /** 均值（空数组给 0）。 */
    static double mean(float[] v) {
        if (v == null || v.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (float x : v) {
            sum += x;
        }
        return sum / v.length;
    }

    /** 旧的相关性版（保留作对照，不再用于判定）。 */
    static float meanProduct(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) {
            return 0f;
        }
        double ma = 0.0;
        double mb = 0.0;
        for (int i = 0; i < n; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma = Math.max(ma / n, 1e-9);
        mb = Math.max(mb / n, 1e-9);
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += (a[i] / ma) * (b[i] / mb);
        }
        // 两个都"平常大"时乘积均值约 1（各自归一）⇒ 减 1 再压到 0..1
        return (float) Math.max(0.0, Math.min(1.0, sum / n - 1.0));
    }

    /** 峰的显著度：(峰 − 均值) / (峰 + 均值)。 */
    static float prominence(float[] env) {
        if (env.length == 0) {
            return 0f;
        }
        double peak = 0.0;
        double sum = 0.0;
        for (float v : env) {
            peak = Math.max(peak, v);
            sum += v;
        }
        double mean = sum / env.length;
        return (float) Math.max(0.0, (peak - mean) / Math.max(peak + mean, 1e-9));
    }

    /** 用最小二乘拟合 dB 斜率（dB/秒）。 */
    static float slopeDbPerSec(float[] env, double frameHz) {
        int n = env.length;
        if (n < 4) {
            return 0f;
        }
        double sx = 0.0;
        double sy = 0.0;
        double sxx = 0.0;
        double sxy = 0.0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = 20.0 * Math.log10(Math.max(env[i], 1e-6));
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        if (Math.abs(denom) < 1e-9) {
            return 0f;
        }
        double slopePerFrame = (n * sxy - sx * sy) / denom;
        return (float) (slopePerFrame * frameHz);
    }

    /** 起跳强度：前 {@code frames} 帧里"上升量"的峰值因子。 */
    static float onsetFactor(float[] env, double frames) {
        int n = Math.min(env.length, Math.max(2, (int) Math.round(frames)));
        double peak = 0.0;
        double sum = 0.0;
        for (int i = 1; i < n; i++) {
            double d = Math.max(0.0, env[i] - env[i - 1]);
            peak = Math.max(peak, d);
            sum += d;
        }
        double mean = sum / Math.max(1, n - 1);
        return (float) Math.max(0.0, (peak - mean) / Math.max(peak + mean, 1e-9));
    }

    /** 尾段是不是"一路往上"：后半段的均值明显高于前半段。 */
    static boolean rises(float[] env) {
        int n = env.length;
        if (n < 8) {
            return false;
        }
        double first = 0.0;
        double second = 0.0;
        for (int i = 0; i < n / 2; i++) {
            first += env[i];
        }
        for (int i = n / 2; i < n; i++) {
            second += env[i];
        }
        first /= Math.max(1, n / 2);
        second /= Math.max(1, n - n / 2);
        return second > first * 1.15;                 // 明显在堆（>15%）
    }
}
