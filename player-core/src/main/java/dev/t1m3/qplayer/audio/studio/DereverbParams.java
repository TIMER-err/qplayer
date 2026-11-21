package dev.t1m3.qplayer.audio.studio;

/**
 * 去混响的参数（不可变）。
 *
 * <h2>三个旋钮</h2>
 *
 * <ul>
 *   <li><b>强度</b>（0..1）：谱减的过量因子 {@code 1.0 → 3.0} 与尾音估计的泄漏系数一起走。
 *       越大尾巴压得越狠，但干声的齿音/气声也会开始发闷；</li>
 *   <li><b>噪声底</b>（−30..−6 dB）：增益的下限。谱减会把「减到负数」的 bin 直接归零，
 *       那听感是「水声/金属声」（musical noise）——留一个底让它<strong>衰减而不是消失</strong>，
 *       这是这一档最重要的一个参数；</li>
 *   <li><b>干湿</b>：0 = 原样（逐位直通），1 = 全处理。中间值可以「少去一点」。</li>
 * </ul>
 *
 * <h2>只作用在人声轨</h2>
 *
 * <p>与修跑调同一条纪律（见 {@link AutotuneParams}）：整条混音里的鼓与镲本身就有
 * 「尾巴」，谱减法会把它们当混响减掉——镲片会变干、军鼓会失掉余韵。
 * 所以它也只装在「喂进来的是人声」的那条链上。
 */
public final class DereverbParams {

    /** 强度上限。 */
    public static final double MAX_AMOUNT = 1.0;

    /** 噪声底下限（dB）。 */
    public static final double MIN_FLOOR_DB = -30.0;

    /** 噪声底上限（dB）。 */
    public static final double MAX_FLOOR_DB = -6.0;

    public final boolean enabled;
    /** 强度（0..1）。 */
    public final double amount;
    /** 增益下限（dB，−30..−6）。 */
    public final double floorDb;
    /** 干湿（0..1）。 */
    public final double mix;

    private DereverbParams(boolean enabled, double amount, double floorDb, double mix) {
        this.enabled = enabled;
        double a = Double.isNaN(amount) ? 0.6 : amount;
        this.amount = Math.max(0.0, Math.min(MAX_AMOUNT, a));
        double f = Double.isNaN(floorDb) ? -18.0 : floorDb;
        this.floorDb = Math.max(MIN_FLOOR_DB, Math.min(MAX_FLOOR_DB, f));
        double m = Double.isNaN(mix) ? 1.0 : mix;
        this.mix = Math.max(0.0, Math.min(1.0, m));
    }

    public static DereverbParams off() {
        return new DereverbParams(false, 0.6, -18.0, 1.0);
    }

    public static DereverbParams of(double amount, double floorDb, double mix) {
        return new DereverbParams(true, amount, floorDb, mix);
    }

    public DereverbParams withEnabled(boolean value) {
        return new DereverbParams(value, amount, floorDb, mix);
    }

    public DereverbParams withAmount(double value) {
        return new DereverbParams(enabled, value, floorDb, mix);
    }

    public DereverbParams withFloorDb(double value) {
        return new DereverbParams(enabled, amount, value, mix);
    }

    public DereverbParams withMix(double value) {
        return new DereverbParams(enabled, amount, floorDb, value);
    }

    /** 这个参数会不会改采样。 */
    public boolean active() {
        return enabled && mix > 1e-9 && amount > 1e-9;
    }

    /** 谱减的过量因子（1.0 起，强度 1 时到 3.0）。 */
    public double overSubtraction() {
        return 1.0 + 2.0 * amount;
    }

    /** 增益下限（线性）。 */
    public double floorLinear() {
        return Math.pow(10.0, floorDb / 20.0);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "DereverbParams{%s 强度 %.0f%%、噪声底 %.0f dB、干湿 %.2f}",
                enabled ? "开" : "关", Double.valueOf(amount * 100), Double.valueOf(floorDb),
                Double.valueOf(mix));
    }
}
