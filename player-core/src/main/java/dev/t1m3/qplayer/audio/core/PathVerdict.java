package dev.t1m3.qplayer.audio.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 位完美可行性判定。
 *
 * <p>这是界面上「直通 / 已重采样」那行字的<strong>唯一来源</strong>：由实际协商结果
 * 推出，不猜、不假设、不美化。UI 不得自行判断——判定逻辑只有一份，就在
 * {@link #classify} 里。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::format::PathVerdict}（那边是带数据的 enum，
 * 这边是抽象基类 + 三个不可变子类）。
 */
public abstract class PathVerdict {

    private PathVerdict() {
    }

    /** 直通：设备原生支持源采样率与位深，比特可原样送达。 */
    public static final class Direct extends PathVerdict {
        /** 采样率。 */
        public final int sampleRate;
        /** 位深。 */
        public final BitDepth bitDepth;

        public Direct(int sampleRate, BitDepth bitDepth) {
            this.sampleRate = sampleRate;
            this.bitDepth = bitDepth;
        }

        @Override
        public String summary() {
            return "直通 " + formatRate(sampleRate) + " / " + bitDepth.label();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Direct)) return false;
            Direct d = (Direct) o;
            return sampleRate == d.sampleRate && bitDepth == d.bitDepth;
        }

        @Override
        public int hashCode() {
            return 31 * sampleRate + bitDepth.hashCode();
        }
    }

    /** 已重采样：设备不支持源采样率。波形已被改变，不再位完美。 */
    public static final class Resampled extends PathVerdict {
        /** 源采样率。 */
        public final int from;
        /** 实际输出采样率。 */
        public final int to;
        /** 是否同家族。同家族是整数倍转换，失真显著更小。 */
        public final boolean sameFamily;

        public Resampled(int from, int to, boolean sameFamily) {
            this.from = from;
            this.to = to;
            this.sameFamily = sameFamily;
        }

        @Override
        public String summary() {
            String note = sameFamily ? "同家族整数倍" : "跨时钟家族";
            return "已重采样 " + formatRate(from) + " → " + formatRate(to) + "（" + note + "）";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Resampled)) return false;
            Resampled r = (Resampled) o;
            return from == r.from && to == r.to && sameFamily == r.sameFamily;
        }

        @Override
        public int hashCode() {
            return (31 * from + to) * 31 + (sameFamily ? 1 : 0);
        }
    }

    /** 已改位深：采样率保住了，但位深被改动。 */
    public static final class Requantized extends PathVerdict {
        /** 源位深。 */
        public final BitDepth from;
        /** 实际输出位深。 */
        public final BitDepth to;
        /** 是否升位。升位只是补零，不丢信息。 */
        public final boolean upconverted;

        public Requantized(BitDepth from, BitDepth to, boolean upconverted) {
            this.from = from;
            this.to = to;
            this.upconverted = upconverted;
        }

        @Override
        public String summary() {
            String note = upconverted ? "补零，无损失" : "有效位减少";
            return "位深 " + from + " → " + to + "（" + note + "）";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Requantized)) return false;
            Requantized r = (Requantized) o;
            return from == r.from && to == r.to && upconverted == r.upconverted;
        }

        @Override
        public int hashCode() {
            return (31 * from.hashCode() + to.hashCode()) * 31 + (upconverted ? 1 : 0);
        }
    }

    /** 是否真的位完美。 */
    public boolean isBitPerfect() {
        return this instanceof Direct;
    }

    /**
     * 这条路径是否<strong>真的丢了信息</strong>。
     *
     * <p>与 {@link #isBitPerfect()} 的区别很关键，两者不能互相代替：
     *
     * <ul>
     *   <li><strong>重采样</strong>：波形被滤波器改过，是真损失</li>
     *   <li><strong>降位深</strong>（{@code upconverted == false}）：有效位被砍掉，是真损失</li>
     *   <li><strong>升位深</strong>（{@code upconverted == true}）：只是补零换了个容器，
     *       <strong>一个比特都没丢</strong>（f32 的尾数正好 24 位，16 bit 与 24 bit
     *       经内部 f32 中转是精确的）</li>
     * </ul>
     *
     * <p>所以「不直通」和「有损失」是两件事。24 bit 的 FLAC 在只声明 F32 的设备上
     * 不是 {@link Direct}，但信息完好——把它一并报成「丢失」会让用户去追一个并不
     * 存在的问题。
     */
    public boolean losesInformation() {
        if (this instanceof Direct) return false;
        if (this instanceof Resampled) return true;
        return !((Requantized) this).upconverted;
    }

    /** 给界面用的一行说明。 */
    public abstract String summary();

    /** 采样率的可读形式：44000 → {@code 44.1 kHz}。 */
    public static String formatRate(int rate) {
        if (rate % 1000 == 0) {
            return (rate / 1000) + " kHz";
        }
        return String.format(Locale.ROOT, "%.1f kHz", rate / 1000.0);
    }

    /**
     * 判定实际信号路径。
     *
     * <p>优先级是 <strong>保采样率 &gt; 保位深</strong>。理由：重采样会真实改变波形
     * （引入滤波与混叠），而位深转换只是移动量化噪声底——前者可听，后者在正常
     * 听音条件下基本不可听。
     *
     * @param src           源格式指纹
     * @param deviceRates   设备实际支持的采样率（可为空；探测不到时传空数组）
     * @param deviceDepths  设备实际支持的位深（同上）
     */
    public static PathVerdict classify(SourceFormat src, int[] deviceRates,
                                       BitDepth[] deviceDepths) {
        boolean rateExact = containsRate(deviceRates, src.sampleRate());
        boolean depthExact = containsDepth(deviceDepths, src.bitDepth());

        if (rateExact && depthExact) {
            return new Direct(src.sampleRate(), src.bitDepth());
        }

        if (!rateExact) {
            int to = pickRate(src.sampleRate(), deviceRates);
            return new Resampled(src.sampleRate(), to,
                    RateFamily.of(src.sampleRate()) == RateFamily.of(to));
        }

        BitDepth to = pickDepth(src.bitDepth(), deviceDepths);
        return new Requantized(src.bitDepth(), to, to.bits() > src.bitDepth().bits());
    }

    /**
     * 在设备支持的采样率里挑一个最合适的。
     *
     * <p>优先级：
     * <ol>
     *   <li><strong>同家族里不低于源的最低档</strong> —— 整数倍转换，代价与失真最小</li>
     *   <li><strong>全局不低于源的最低档</strong> —— 不无谓升到最高（升采样不增加信息，
     *       却让计算量与时钟负担白白变大）</li>
     *   <li><strong>全局最高档</strong> —— 全都低于源，被迫降采样，只好把损失压到最小</li>
     * </ol>
     */
    static int pickRate(int src, int[] avail) {
        if (avail == null || avail.length == 0) {
            return src;
        }
        TreeSet<Integer> sorted = new TreeSet<Integer>();
        for (int rate : avail) {
            if (rate > 0) sorted.add(rate);
        }
        if (sorted.isEmpty()) {
            return src;
        }

        RateFamily family = RateFamily.of(src);
        for (Integer rate : sorted) {
            if (RateFamily.of(rate) == family && rate >= src) {
                return rate;
            }
        }
        for (Integer rate : sorted) {
            if (rate >= src) {
                return rate;
            }
        }
        return sorted.last();
    }

    /** 在设备支持的位深里挑一个：优先精确匹配，否则取不低于源的最低档。 */
    static BitDepth pickDepth(BitDepth src, BitDepth[] avail) {
        if (avail == null || avail.length == 0) {
            return src;
        }
        for (BitDepth depth : avail) {
            if (depth == src) return depth;
        }
        // TreeSet 按 enum 声明顺序排（Rust 的 derive(Ord) 同样是声明顺序）。
        List<BitDepth> sorted = new ArrayList<BitDepth>(new TreeSet<BitDepth>(Arrays.asList(avail)));
        if (sorted.isEmpty()) {
            return src;
        }
        for (BitDepth depth : sorted) {
            if (depth.bits() >= src.bits()) {
                return depth;
            }
        }
        return sorted.get(sorted.size() - 1);
    }

    private static boolean containsRate(int[] rates, int rate) {
        if (rates == null) return false;
        for (int candidate : rates) {
            if (candidate == rate) return true;
        }
        return false;
    }

    private static boolean containsDepth(BitDepth[] depths, BitDepth depth) {
        if (depths == null) return false;
        for (BitDepth candidate : depths) {
            if (candidate == depth) return true;
        }
        return false;
    }
}
