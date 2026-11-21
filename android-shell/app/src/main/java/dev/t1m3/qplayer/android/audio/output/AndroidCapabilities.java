package dev.t1m3.qplayer.android.audio.output;

import dev.t1m3.qplayer.audio.output.DeviceCapabilities;
import dev.t1m3.qplayer.audio.output.DeviceCapability;
import dev.t1m3.qplayer.audio.output.DeviceSampleFormat;
import dev.t1m3.qplayer.audio.output.OutputSampleFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 把「探针报回来的接受/不接受」拼成 {@link DeviceCapabilities}（<strong>纯逻辑，不引用 {@code android.*}</strong>）。
 *
 * <p>拆出来是为了让能力矩阵的形状有测试钉着：探测本身要真机（开 AudioTrack），
 * 而「这些命中怎么合并成区间、顺序怎么排」是纯计算，错了会让
 * {@code OutputChooser} 挑出错误的档位（桌面端就真踩过：48 kHz 立体声请求被协商成单声道）。
 *
 * <h3>矩阵的语义：「输出这条路愿意接受」</h3>
 *
 * <p>与桌面端的结论保持一致（见 {@code docs/audio-port.md} 的平台实测结论）：
 * AudioTrack 在普通（非直通）模式下会<strong>接受</strong>很宽的采样率范围，
 * 由系统混音器重采样到设备速率。所以这份矩阵的含义是「AudioTrack 愿意以这个采样率开流」，
 * <strong>不是</strong>「这个采样率是位完美的」。位完美判定只认
 * {@code PathVerdict.classify(源格式, 实际协商到的 OutputConfig)}——
 * 这两件事混起来就是「界面显示位完美、实际在重采样」这类查不出的问题。
 *
 * <p>区间只覆盖<strong>探过的档位</strong>（{@link DeviceCapabilities#RATE_LADDER}），
 * 相邻命中合并成区间，非档位表上的采样率（例如某些设备的原生 37000 Hz）单独记成单点档位——
 * 不能因为「它不在档位表里」就把设备真正的原生采样率丢掉。
 */
public final class AndroidCapabilities {

    /** 一次探测命中：某格式在某声道数上能以某采样率开流。 */
    public static final class Hit {

        public final OutputSampleFormat format;
        public final int channels;
        public final int rate;

        public Hit(OutputSampleFormat format, int channels, int rate) {
            if (format == null) {
                throw new IllegalArgumentException("命中缺少采样格式");
            }
            if (channels < 1 || rate < 1) {
                throw new IllegalArgumentException("命中参数非法：" + channels + "ch " + rate + " Hz");
            }
            this.format = format;
            this.channels = channels;
            this.rate = rate;
        }

        @Override
        public String toString() {
            return format + " " + channels + "ch " + rate + " Hz";
        }
    }

    private AndroidCapabilities() {
    }

    /**
     * 拼装矩阵。
     *
     * @param hits     探测命中（可重复、可乱序）
     * @param declared 设备声明过的格式（DSD 判定看它，不看可开流矩阵）
     */
    public static DeviceCapabilities assemble(List<Hit> hits, List<DeviceSampleFormat> declared) {
        List<DeviceCapability> entries = new ArrayList<DeviceCapability>();
        if (hits != null && !hits.isEmpty()) {
            // key = 格式|声道数 → 命中的档位表下标 与 非档位表采样率
            Map<String, TreeSet<Integer>> ladderHits = new LinkedHashMap<String, TreeSet<Integer>>();
            Map<String, TreeSet<Integer>> extraHits = new LinkedHashMap<String, TreeSet<Integer>>();
            for (int i = 0; i < hits.size(); i++) {
                Hit hit = hits.get(i);
                String key = hit.format.name() + "|" + hit.channels;
                int idx = ladderIndex(hit.rate);
                Map<String, TreeSet<Integer>> target = idx >= 0 ? ladderHits : extraHits;
                TreeSet<Integer> set = target.get(key);
                if (set == null) {
                    set = new TreeSet<Integer>();
                    target.put(key, set);
                }
                set.add(Integer.valueOf(idx >= 0 ? idx : hit.rate));
            }

            for (Map.Entry<String, TreeSet<Integer>> e : ladderHits.entrySet()) {
                mergeRuns(e.getKey(), e.getValue(), true, entries);
            }
            for (Map.Entry<String, TreeSet<Integer>> e : extraHits.entrySet()) {
                mergeRuns(e.getKey(), e.getValue(), false, entries);
            }
        }

        // 立体声档位排到前面。
        //
        // 与桌面端同一条理由：上游的挑选（OutputChooser.choose）在打分相同时保留**先出现**的档位，
        // 而 cpal 报的是设备原生档位（音乐播放器的目标布局就是立体声）。
        // 这里我们自己造矩阵，必须把 2 声道排在前面，否则一旦哪天探到别的声道数，
        // 立体声请求可能被协商成别的布局。排序用稳定排序，同档位内的相对顺序不变。
        Collections.sort(entries, new Comparator<DeviceCapability>() {
            @Override
            public int compare(DeviceCapability a, DeviceCapability b) {
                return rank(a.channels()) - rank(b.channels());
            }

            private int rank(int channels) {
                return channels == 2 ? 0 : 1;
            }
        });

        return new DeviceCapabilities(entries, declared);
    }

    /** 把连续的档位表下标合并成区间（{@code isLadder=false} 时集合里放的就是采样率本身，逐点成档）。 */
    private static void mergeRuns(String key, TreeSet<Integer> values, boolean isLadder,
                                  List<DeviceCapability> out) {
        int bar = key.indexOf('|');
        OutputSampleFormat fmt = OutputSampleFormat.valueOf(key.substring(0, bar));
        int channels = Integer.parseInt(key.substring(bar + 1));

        int runStart = -1;
        int prev = -2;
        for (Integer v : values) {
            int i = v.intValue();
            if (runStart < 0) {
                runStart = i;
            } else if (!isLadder || i != prev + 1) {
                out.add(toCapability(fmt, channels, runStart, prev, isLadder));
                runStart = i;
            }
            prev = i;
        }
        if (runStart >= 0) {
            out.add(toCapability(fmt, channels, runStart, prev, isLadder));
        }
    }

    private static DeviceCapability toCapability(OutputSampleFormat fmt, int channels,
                                                 int from, int to, boolean isLadder) {
        if (isLadder) {
            // from / to 是档位表下标 → 换成真实采样率组成区间
            int[] ladder = DeviceCapabilities.RATE_LADDER;
            return new DeviceCapability(fmt, channels, ladder[from], ladder[to]);
        }
        // 非档位表的采样率：from / to 就是采样率本身（上面每个值都单独成档，所以 from 与 to 相等），
        // 单点区间。不因为「不在档位表里」就把设备真正的原生采样率丢掉。
        return new DeviceCapability(fmt, channels, from, to);
    }

    /** 采样率在档位表里的下标；不在表里返回 -1。 */
    static int ladderIndex(int rate) {
        int[] ladder = DeviceCapabilities.RATE_LADDER;
        for (int i = 0; i < ladder.length; i++) {
            if (ladder[i] == rate) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析 {@code AudioManager.getProperty(...)} 的返回值。
     *
     * <p>平台把它当字符串给（{@code "48000"}），拿不到时返回 {@code null}，
     * 垃圾值也不能变成 0——0 Hz 的采样率会一路走到「设备不支持 0 Hz」那种驴唇不对马嘴的报错。
     */
    public static int propertyInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
