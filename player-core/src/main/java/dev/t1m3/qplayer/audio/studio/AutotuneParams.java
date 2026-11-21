package dev.t1m3.qplayer.audio.studio;

/**
 * 修跑调（自动音准）的参数（不可变）。
 *
 * <h2>四个参数各管什么（界面上就是这四行）</h2>
 *
 * <ul>
 *   <li><b>音阶 / 主音 / 参考音高</b>：往哪儿修。半音档等于「只做等程平均律微调」，
 *       大调/小调/五声/蓝调是「音集」——不在音集里的音会被拉到最近的音级；</li>
 *   <li><b>强度</b>（0..1）：修多少。1 = 完全拉到音上（很「电」），0.5 = 只修一半
 *       （保留一点人味）。<strong>0 时整个模块等于旁路</strong>；</li>
 *   <li><b>回正速度</b>（0–200 ms）：修正量的一阶时间常数。小 = 一发现就掰过去（电音），
 *       大 = 慢慢靠过去（像人）。这是这一档最影响听感的参数；</li>
 *   <li><b>保留颤音</b>：修的是「平滑后的偏差」而不是瞬时偏差。于是颤音（偏差里的
 *       交流成分）原样留着，只有「整体偏高/偏低」被修掉。关掉它等于把颤音也拉平
 *       ——那正是「电音」的来源。</li>
 * </ul>
 *
 * <h2>为什么它必须只作用在人声轨上</h2>
 *
 * <p>修跑调的前提是「这段素材里只有一个音高」。伴奏是复音的（和弦、鼓），
 * 检测器在里面读到的「基频」没有意义——照着它修，等于给整首歌随机加一个缓慢的移调，
 * 听起来像走带不稳。<strong>所以这一档永远不作用在整条混音上</strong>：
 * 它只在「已经分出来的人声轨」上生效（见 {@code RackProcessor} 的 vocalChain 约定）。
 */
public final class AutotuneParams {

    /** 强度上限（1 = 全修）。 */
    public static final double MAX_STRENGTH = 1.0;

    /** 回正速度上限（毫秒）。 */
    public static final double MAX_RETUNE_MS = 200.0;

    public final boolean enabled;
    public final ScaleQuantizer.Scale scale;
    /** 主音（0 = C，…，11 = B）：小调/五声离开主音没有意义。 */
    public final int tonic;
    /** 参考音高（Hz）：默认 440，老录音可能是 432/442。 */
    public final double a4;
    /** 强度（0..1）。 */
    public final double strength;
    /** 回正速度（毫秒，0–200）。 */
    public final double retuneMs;
    /** 保留颤音（修平滑后的偏差，而不是瞬时偏差）。 */
    public final boolean preserveVibrato;
    /** 干湿：0 = 原样（逐位直通）。 */
    public final double mix;

    private AutotuneParams(boolean enabled, ScaleQuantizer.Scale scale, int tonic, double a4,
                           double strength, double retuneMs, boolean preserveVibrato, double mix) {
        this.enabled = enabled;
        this.scale = scale == null ? ScaleQuantizer.Scale.CHROMATIC : scale;
        this.tonic = ((tonic % 12) + 12) % 12;
        double ref = Double.isNaN(a4) ? ScaleQuantizer.DEFAULT_A4 : a4;
        this.a4 = Math.max(400.0, Math.min(480.0, ref));
        double st = Double.isNaN(strength) ? 1.0 : strength;
        this.strength = Math.max(0.0, Math.min(MAX_STRENGTH, st));
        double rt = Double.isNaN(retuneMs) ? 40.0 : retuneMs;
        this.retuneMs = Math.max(0.0, Math.min(MAX_RETUNE_MS, rt));
        this.preserveVibrato = preserveVibrato;
        double m = Double.isNaN(mix) ? 1.0 : mix;
        this.mix = Math.max(0.0, Math.min(1.0, m));
    }

    /** 关。 */
    public static AutotuneParams off() {
        return new AutotuneParams(false, ScaleQuantizer.Scale.CHROMATIC, 0, 440.0,
                1.0, 40.0, true, 1.0);
    }

    public static AutotuneParams of(ScaleQuantizer.Scale scale, int tonic, double a4,
                                    double strength, double retuneMs, boolean preserveVibrato,
                                    double mix) {
        return new AutotuneParams(true, scale, tonic, a4, strength, retuneMs, preserveVibrato, mix);
    }

    public AutotuneParams withEnabled(boolean value) {
        return new AutotuneParams(value, scale, tonic, a4, strength, retuneMs, preserveVibrato, mix);
    }

    public AutotuneParams withScale(ScaleQuantizer.Scale value) {
        return new AutotuneParams(enabled, value, tonic, a4, strength, retuneMs, preserveVibrato,
                mix);
    }

    public AutotuneParams withTonic(int value) {
        return new AutotuneParams(enabled, scale, value, a4, strength, retuneMs, preserveVibrato,
                mix);
    }

    public AutotuneParams withA4(double value) {
        return new AutotuneParams(enabled, scale, tonic, value, strength, retuneMs, preserveVibrato,
                mix);
    }

    public AutotuneParams withStrength(double value) {
        return new AutotuneParams(enabled, scale, tonic, a4, value, retuneMs, preserveVibrato, mix);
    }

    public AutotuneParams withRetuneMs(double value) {
        return new AutotuneParams(enabled, scale, tonic, a4, strength, value, preserveVibrato, mix);
    }

    public AutotuneParams withPreserveVibrato(boolean value) {
        return new AutotuneParams(enabled, scale, tonic, a4, strength, retuneMs, value, mix);
    }

    public AutotuneParams withMix(double value) {
        return new AutotuneParams(enabled, scale, tonic, a4, strength, retuneMs, preserveVibrato,
                value);
    }

    /** 这个参数会不会改采样（关掉、全干、或强度 0 → 不会）。 */
    public boolean active() {
        return enabled && mix > 1e-9 && strength > 1e-9;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "AutotuneParams{%s %s%s，A4=%.1f，强度 %.0f%%，回正 %.0f ms，颤音%s，干湿 %.2f}",
                enabled ? "开" : "关", scale.label,
                scale == ScaleQuantizer.Scale.CHROMATIC ? "" : ("/" + tonic),
                Double.valueOf(a4), Double.valueOf(strength * 100), Double.valueOf(retuneMs),
                preserveVibrato ? "保留" : "拉平", Double.valueOf(mix));
    }
}
