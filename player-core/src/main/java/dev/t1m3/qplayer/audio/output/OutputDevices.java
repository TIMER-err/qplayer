package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 宿主安装的输出设备来源，以及上游那几个「探测默认设备」的自由函数。
 *
 * <p>对应 Sonalis 顶层的 {@code list_output_devices()} / {@code probe_capabilities()} /
 * {@code probe_dsd_support()} / {@code probe_max_channels()} / {@code probe_buffer_size_range()}
 * 与 {@code cpal::default_host()}。上游直接 new 一个 cpal host，
 * Java 这边「宿主是谁」必须由上层注入——player-core 不能依赖 {@code javax.sound.sampled}。
 *
 * <p>用法（桌面端启动时一次）：
 *
 * <pre>
 * OutputDevices.install(new JavaSoundOutputProvider());
 * </pre>
 *
 * <p>没装源时所有探测都走「没有可用的输出设备」，与上游拿不到默认设备时的行为一致。
 */
public final class OutputDevices {

    /**
     * 设备来源。宿主实现它（桌面 = Java Sound，安卓 = AudioTrack）。
     *
     * <p>实现要求：{@link #list()} 与 {@link #defaultDevice()} <strong>不抛异常</strong>，
     * 探不到就返回空列表 / {@code null}。没声卡的机器上仍然要能把设置页打开。
     */
    public interface Provider {

        /** 全部输出设备。 */
        List<OutputDevice> list();

        /** 系统默认输出设备。没有可用设备时返回 {@code null}。 */
        OutputDevice defaultDevice();
    }

    private static volatile Provider provider;

    private OutputDevices() {
    }

    /** 安装设备来源（宿主启动时调用一次）。 */
    public static void install(Provider p) {
        // 装上来源时把设备名录记一行：用户报"设置里看不到我的设备"时，
        // 先要能确认宿主到底枚举到了什么。
        if (p != null) {
            try {
                List<OutputDevice> devices = p.list();
                StringBuilder names = new StringBuilder();
                if (devices != null) {
                    for (OutputDevice d : devices) {
                        if (names.length() > 0) {
                            names.append(" / ");
                        }
                        names.append(d.name());
                    }
                }
                Logger.info("输出设备：枚举到 {} 台（{}）", Integer.valueOf(
                        devices == null ? 0 : devices.size()), names);
            } catch (RuntimeException e) {
                Logger.warn("输出设备：枚举失败（{}）", e.toString());
            }
        }
        provider = p;
    }

    /** 当前设备来源，未安装时为 {@code null}。 */
    public static Provider provider() {
        return provider;
    }

    /** 是否已安装设备来源。 */
    public static boolean isInstalled() {
        return provider != null;
    }

    /**
     * 系统默认输出设备，没有可用设备（或没装来源）时返回 {@code null}。
     *
     * <p>对应上游 {@code host.default_output_device()}。
     */
    public static OutputDevice defaultDevice() {
        Provider p = provider;
        if (p == null) {
            return null;
        }
        // **用户选的那台优先**（设置页「播放设备」）：引擎是懒解析
        // （{@code PlaybackEngine} 开流时才问一次），所以换设备只要重开输出就会生效。
        OutputDevice chosen = selectedDevice();
        if (chosen != null) {
            return chosen;
        }
        try {
            return p.defaultDevice();
        } catch (RuntimeException e) {
            // 探测路径上的任何异常都不该把界面拖下水：当成「没有设备」处理
            return null;
        }
    }

    /**
     * 全部输出设备。对应上游 {@code host.output_devices()}。
     *
     * <p>枚举失败时返回空列表而不是抛异常——同样是「界面要能打开」的理由。
     */
    public static List<OutputDevice> list() {
        Provider p = provider;
        if (p == null) {
            return Collections.emptyList();
        }
        try {
            List<OutputDevice> devices = p.list();
            return devices == null ? Collections.<OutputDevice>emptyList() : devices;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 所有输出设备名。
     *
     * <p>上游 {@code list_output_devices()}：cpal 0.18 把 {@code Device::name()} 换成了
     * {@code Device::description()}，这里同样只取名字这一项。
     */
    /**
     * 用户选中的设备名；{@code null}/空 = 跟随系统默认。
     *
     * <p>存的是**名字**而不是列表下标：设备的枚举顺序会变（插拔、驱动重排），
     * 下标会在下一次启动时指向别的设备，而名字不会。
     */
    private static volatile String selection;

    /** 选中一台设备（{@code null}/空 = 跟随系统默认）。 */
    public static void select(String name) {
        selection = name == null || name.isEmpty() ? null : name;
    }

    /** 当前选中的设备名（没选就是 {@code null}）。 */
    public static String selectedName() {
        return selection;
    }

    /**
     * 选中的那台设备对象；没选、或者它已经不在列表里（拔了/改名了）时返回 {@code null}。
     *
     * <p>不在列表里就退回系统默认——用户拔掉耳机之后仍然要能出声，
     * 而不是"打开输出失败"（那种失败在界面上只会表现为"点了播放没反应"）。
     */
    public static OutputDevice selectedDevice() {
        String want = selection;
        if (want == null) {
            return null;
        }
        for (OutputDevice device : list()) {
            if (want.equals(device.name())) {
                return device;
            }
        }
        Logger.warn("[qplayer-engine] 选中的输出设备「{}」不在列表里（拔掉或改名了），本次用系统默认",
                want);
        return null;
    }

    public static List<String> listOutputDeviceNames() {
        List<String> names = new ArrayList<String>();
        for (OutputDevice d : list()) {
            names.add(d.name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * 枚举默认设备支持的全部配置档位。
     *
     * <p>界面上的 Hi-Res 开关要不要置灰、能升到多高，全部依据这个结果。
     *
     * @throws AudioException 没有可用的输出设备
     */
    public static DeviceCapabilities probeCapabilities() {
        return requireDefaultDevice().capabilities();
    }

    /**
     * 默认设备声明支持的采样率集合（去重、升序）。
     *
     * @throws AudioException 没有可用的输出设备
     */
    public static int[] supportedRates() {
        return requireDefaultDevice().supportedRates();
    }

    /**
     * 默认设备声明支持的位深集合（去重、升序）。
     *
     * @throws AudioException 没有可用的输出设备
     */
    public static List<BitDepth> supportedBitDepths() {
        return requireDefaultDevice().supportedBitDepths();
    }

    /**
     * 探测默认设备的 DSD 原生支持情况。
     *
     * <p>看的是设备声明的 {@code DsdU8/DsdU16/DsdU32}。<strong>只说明底座能开流</strong>，
     * 目前没有 DSD 解码能力。
     *
     * @throws AudioException 没有可用的输出设备
     */
    public static DsdSupport probeDsdSupport() {
        return requireDefaultDevice().dsdSupport();
    }

    /**
     * 探测默认输出设备支持的声道数上限。
     *
     * <p>环绕声设置项要不要置灰，取决于这个结果——<strong>由硬件决定，不由我们猜</strong>。
     *
     * @throws AudioException 没有可用的输出设备
     */
    public static int probeMaxChannels() {
        return requireDefaultDevice().maxChannels();
    }

    /**
     * 设备支持的缓冲帧数范围，{@code null} 表示平台不提供这项信息。
     *
     * <p>对应上游：{@code device.default_output_config().ok()?} 之后匹配
     * {@code SupportedBufferSize::Range} / {@code Unknown}。上游用 {@code ?} 在
     * 拿不到设备时直接返回 {@code None}，所以这里也返回 {@code null} 而不是抛异常。
     */
    public static OutputDevice.BufferRange probeBufferSizeRange() {
        OutputDevice device = defaultDevice();
        if (device == null) {
            return null;
        }
        return device.bufferSizeRange();
    }

    private static OutputDevice requireDefaultDevice() {
        OutputDevice device = defaultDevice();
        if (device == null) {
            throw AudioException.output("没有可用的输出设备");
        }
        return device;
    }
}
