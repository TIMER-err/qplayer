package dev.t1m3.qplayer.audio.core;

/**
 * 采样率家族。
 *
 * <p>44.1k 与 48k 是两个<strong>独立时钟</strong>，跨家族转换要重造波形，而同家族内
 * （44.1 ↔ 88.2 ↔ 176.4）是整数倍，代价与失真都小得多。设备不支持源采样率时，
 * 优先在同家族里选一档升上去。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::format::RateFamily}。
 */
public enum RateFamily {

    /** 44.1 kHz 家族：44.1 / 88.2 / 176.4 / 352.8，含 DSD（2.8224 MHz = 44100 × 64）。 */
    BASE_44,
    /** 48 kHz 家族：48 / 96 / 192 / 384。 */
    BASE_48;

    /**
     * 判定某个采样率属于哪个家族。
     *
     * <p>这是个启发式：44100 的整数倍归入 44.1 家族，其余归入 48 家族。
     * 8 kHz / 32 kHz 这类电话/广播规格会被归进 48 家族，对播放路径无影响。
     */
    public static RateFamily of(int rate) {
        return rate % 44_100 == 0 ? BASE_44 : BASE_48;
    }
}
