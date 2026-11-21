package dev.t1m3.qplayer.audio.studio;

/**
 * 音阶与「往哪儿修」的映射（纯函数，没有任何状态）。
 *
 * <h2>判据先写在这里，因为它最容易做「想当然」</h2>
 *
 * <ul>
 *   <li><strong>已经在音上的音不许被推走</strong>：一个正好在 A4 上的音，量化之后还是它
 *       （差值 0 音分）。做到这一点的唯一办法是「按最近的音」而不是「按最近的<em>下方</em>音」
 *       ——后者会把每个音都往下拉半个音，听起来是整体走调；</li>
 *   <li><strong>半路音往最近的音修</strong>：C 大调下 F♯4（比 F4 高 100 音分、比 G4 低
 *       100 音分）两边一样近，取下方（这是业界惯例，也避免整条线被抬上去）；</li>
 *   <li><strong>参考音高可调</strong>：{@code A4} 默认 440，但老录音有 432/442 这些；
 *       它不是「音准」而是「调音基准」，所以单独一个参数而不是藏在音阶里。</li>
 * </ul>
 *
 * <p>{@link #chromatic} 这一档是「只做等程平均律的微调」——它对每一个频率都修，
 * 因为十二平均律的格子覆盖所有音。真正的「修跑调」是它加上「强度」这个参数：
 * 强度 &lt; 1 时只修一部分（更像人）。
 */
public final class ScaleQuantizer {

    /** 音阶。 */
    public enum Scale {

        /** 半音（十二平均律，每个半音都是一个目标音）。 */
        CHROMATIC("半音", new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}),
        /** 大调。 */
        MAJOR("大调", new int[]{0, 2, 4, 5, 7, 9, 11}),
        /** 自然小调。 */
        MINOR("小调", new int[]{0, 2, 3, 5, 7, 8, 10}),
        /** 五声（大调五声）：很多流行歌的实际音集。 */
        PENTATONIC("五声", new int[]{0, 2, 4, 7, 9}),
        /** 蓝调。 */
        BLUES("蓝调", new int[]{0, 3, 5, 6, 7, 10});

        public final String label;
        /** 相对主音的半音数（升序，每个值 0..11）。 */
        public final int[] steps;

        Scale(String label, int[] steps) {
            this.label = label;
            this.steps = steps;
        }

        /** 从下标还原（越界退化成半音——那是唯一「不改变用户意图」的档）。 */
        public static Scale fromIndex(int index) {
            Scale[] all = values();
            return index >= 0 && index < all.length ? all[index] : CHROMATIC;
        }

        /** 界面下拉用的文字。 */
        public static String[] labels() {
            Scale[] all = values();
            String[] out = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = all[i].label;
            }
            return out;
        }
    }

    /** 默认参考音高（Hz）。 */
    public static final double DEFAULT_A4 = 440.0;

    private ScaleQuantizer() {
    }

    /** 一个音高对应的「MIDI 音号 + 音分」（含小数），A4 = 69。 */
    public static double midiOf(double hz, double a4) {
        double ref = a4 > 1.0 ? a4 : DEFAULT_A4;
        return 69.0 + 12.0 * Math.log(hz / ref) / Math.log(2.0);
    }

    /** MIDI 音号（含小数）→ 频率。 */
    public static double hzOf(double midi, double a4) {
        double ref = a4 > 1.0 ? a4 : DEFAULT_A4;
        return ref * Math.pow(2.0, (midi - 69.0) / 12.0);
    }

    /**
     * 把频率量化到音阶上，返回<strong>目标频率</strong>。
     *
     * <p>「最近的音」按<strong>半音距离</strong>算，并且只在给定音阶的音级里挑；
     * 主音固定为 C（{@link #MAJOR} 这类音集是从 C 起算的）——界面上的音阶是
     * 「调式」而不是「调性」，想换主音就用 {@link #targetHz(double, Scale, double, int)}。
     *
     * @param hz   实测频率（Hz，&gt; 0）
     * @param scale 音阶
     * @param a4   参考音高（Hz）
     * @return 目标频率；输入非法时原样返回
     */
    public static double targetHz(double hz, Scale scale, double a4) {
        return targetHz(hz, scale, a4, 0);
    }

    /**
     * 同上，但指定<strong>主音</strong>（0 = C，1 = C♯ … 11 = B）。
     *
     * <p>为什么主音也要给：小调、五声这些音集离开主音就没有意义
     * （「a 小调」与「c 小调」是两套音）。默认 0 = C，与「音阶下拉只选调式」的界面一致。
     */
    public static double targetHz(double hz, Scale scale, double a4, int tonic) {
        if (!(hz > 0.0) || Double.isNaN(hz) || Double.isInfinite(hz)) {
            return hz;
        }
        Scale s = scale == null ? Scale.CHROMATIC : scale;
        double midi = midiOf(hz, a4);
        int t = ((tonic % 12) + 12) % 12;
        // **音级网格不能按 floor(midi) 去铺**：那样一来「正好落在网格点上的音」会因为
        // 浮点误差（midi = 64.9999999）落到下面那一个八度里，而那个八度里恰好没有 65
        // 这个音级——于是一个本来就准的 F4 被拉低了一个半音。实测（本机）就是这样：
        // midi 65 / 67 / 71（F4、G4、B4）全都被拉低 100 音分，而 60 / 62 / 64 / 69 没事。
        // 现在的做法是「在 midi 附近的整个八度范围里逐个音级判断是否在音集里」——
        // 判据与网格对齐无关，浮点误差也就影响不到它。
        int lo = (int) Math.floor(midi) - 12;
        int hi = (int) Math.ceil(midi) + 12;
        double bestMidi = midi;
        double bestDist = Double.MAX_VALUE;
        for (int m = lo; m <= hi; m++) {
            int pc = ((m - t) % 12 + 12) % 12;
            if (!inSteps(s, pc)) {
                continue;
            }
            double dist = Math.abs(m - midi);
            // 严格小于：等距时保留**先遇到的那个**（= 下方那个），这是业界惯例，
            // 也避免整条线被抬上去（判据：F♯4 在 C 大调下必须回到 F4）
            if (dist < bestDist - 1e-9) {
                bestDist = dist;
                bestMidi = m;
            }
        }
        return hzOf(bestMidi, a4);
    }

    /** {@code pc} 是不是这个音集里的音级（相对主音的半音数）。 */
    private static boolean inSteps(Scale s, int pc) {
        for (int i = 0; i < s.steps.length; i++) {
            if (s.steps[i] == pc) {
                return true;
            }
        }
        return false;
    }

    /**
     * 相对最近的音差多少音分（正 = 偏高）。界面上的「音准误差」就是这个数。
     *
     * @return 音分；输入非法时 0
     */
    public static double centsOff(double hz, Scale scale, double a4, int tonic) {
        if (!(hz > 0.0) || Double.isNaN(hz) || Double.isInfinite(hz)) {
            return 0.0;
        }
        double target = targetHz(hz, scale, a4, tonic);
        if (!(target > 0.0)) {
            return 0.0;
        }
        return 1200.0 * Math.log(hz / target) / Math.log(2.0);
    }
}
