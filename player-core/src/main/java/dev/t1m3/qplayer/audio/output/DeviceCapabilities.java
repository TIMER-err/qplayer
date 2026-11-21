package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.BitDepth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 设备的能力矩阵：枚举到的 (格式, 声道, 采样率区间) 三元组集合，
 * 以及从中导出的「去重后的采样率 / 位深」清单。
 *
 * <p>对应 Sonalis 里 {@code capabilities_of(device)} 的返回值
 * （{@code Vec<DeviceCapability>}）加上 {@code supported_rates} /
 * {@code supported_bit_depths} 这两个自由函数。上游把它们拆成三个函数、
 * 每次都重新枚举一遍设备；这里把它们收在一个不可变的值对象上，
 * 让界面（「Hi-Res 可用 / 不可用」、听得到多高、能开几声道）一次枚举就够。
 *
 * <p>另外还留了一份 {@link #declaredFormats()}：设备<strong>声明过</strong>的全部格式，
 * 包括我们开不了的（F64 / DSD / 8 位）。上游的 {@code probe_dsd_support} 就是直接看
 * 设备声明的原始格式列表，而不是看过滤后的能力矩阵——过滤后的矩阵里永远不会有 DSD，
 * 拿它做 DSD 判定会恒为「不支持」而我们还以为探到了。
 */
public final class DeviceCapabilities {

    /**
     * 常见采样率档位表（与上游逐值一致）。
     *
     * <p>用途：平台的「采样率区间」常常是「任意值」（Java Sound 的 DirectSound 后端
     * 直接报 {@code NOT_SPECIFIED}，由 OS 重采样），<strong>不能把这种区间展开成上万个值</strong>，
     * 所以按这张档位表去问平台「这一档你到底收不收」。
     */
    public static final int[] RATE_LADDER = {
            8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000,
            88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000
    };

    private final List<DeviceCapability> entries;
    private final List<DeviceSampleFormat> declaredFormats;

    public DeviceCapabilities(List<DeviceCapability> entries, List<DeviceSampleFormat> declaredFormats) {
        this.entries = Collections.unmodifiableList(
                new ArrayList<DeviceCapability>(entries == null ? Collections.<DeviceCapability>emptyList() : entries));
        this.declaredFormats = Collections.unmodifiableList(
                new ArrayList<DeviceSampleFormat>(
                        declaredFormats == null ? Collections.<DeviceSampleFormat>emptyList() : declaredFormats));
    }

    /** 空矩阵。没有可用声卡时就该返回它，而不是抛异常。 */
    public static DeviceCapabilities empty() {
        return new DeviceCapabilities(null, null);
    }

    /** 可开流的档位集合。 */
    public List<DeviceCapability> entries() {
        return entries;
    }

    /** 设备声明过的全部格式（含我们开不了的）。 */
    public List<DeviceSampleFormat> declaredFormats() {
        return declaredFormats;
    }

    /** 矩阵是否为空。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 设备声明支持的采样率集合（去重、升序）。
     *
     * <p>用常见档位采样，避免把区间展开成上万个值（上游注释原文）。
     */
    public int[] supportedRates() {
        TreeSet<Integer> rates = new TreeSet<Integer>();
        for (DeviceCapability c : entries) {
            for (int r : RATE_LADDER) {
                if (c.coversRate(r)) {
                    rates.add(Integer.valueOf(r));
                }
            }
        }
        int[] out = new int[rates.size()];
        int i = 0;
        for (Integer r : rates) {
            out[i++] = r.intValue();
        }
        return out;
    }

    /**
     * 设备声明支持的位深集合（去重、升序）。
     *
     * <p>排序按枚举声明顺序（INT16 → INT24 → INT32 → FLOAT32 → FLOAT64），
     * 与上游 {@code BitDepth} 的 {@code Ord} 一致。
     */
    public List<BitDepth> supportedBitDepths() {
        TreeSet<BitDepth> depths = new TreeSet<BitDepth>(new Comparator<BitDepth>() {
            @Override
            public int compare(BitDepth a, BitDepth b) {
                return a.ordinal() - b.ordinal();
            }
        });
        for (DeviceCapability c : entries) {
            depths.add(c.sampleFormat().bitDepth());
        }
        return Collections.unmodifiableList(new ArrayList<BitDepth>(depths));
    }

    /** 矩阵里出现过的最大声道数（空矩阵为 0）。 */
    public int maxChannels() {
        int max = 0;
        for (DeviceCapability c : entries) {
            max = Math.max(max, c.channels());
        }
        return max;
    }

    /** 是否至少有一个档位达到 Hi-Res 门槛。 */
    public boolean isHiresCapable() {
        for (DeviceCapability c : entries) {
            if (c.isHiresCapable()) {
                return true;
            }
        }
        return false;
    }

    /** DSD 原生支持度（看 {@link #declaredFormats()}，不看可开流矩阵）。 */
    public DsdSupport dsdSupport() {
        return DsdSupport.of(declaredFormats);
    }

    @Override
    public String toString() {
        return "DeviceCapabilities{" + entries.size() + " 档, "
                + supportedBitDepths() + ", " + java.util.Arrays.toString(supportedRates()) + " Hz}";
    }
}
