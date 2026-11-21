package dev.t1m3.qplayer.desktop.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import dev.t1m3.qplayer.audio.output.DeviceCapabilities;
import dev.t1m3.qplayer.audio.output.DeviceCapability;
import dev.t1m3.qplayer.audio.output.DeviceSampleFormat;
import dev.t1m3.qplayer.audio.output.DsdSupport;
import dev.t1m3.qplayer.audio.output.OutputChooser;
import dev.t1m3.qplayer.audio.output.OutputConfig;
import dev.t1m3.qplayer.audio.output.OutputDevice;
import dev.t1m3.qplayer.audio.output.OutputSampleFormat;
import dev.t1m3.qplayer.audio.output.OutputStreamControl;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 桌面端的输出设备：{@code javax.sound.sampled} 的一个混音器。
 *
 * <p>对应上游 {@code sonalis-output} 里用 cpal 的那部分：
 * {@code capabilities_of(device)}（能力矩阵）、{@code choose_config}（挑选，已抽到
 * player-core 的 {@link OutputChooser}）、{@code device.default_output_config()}
 * （默认配置）、{@code build_output_stream}（开流）。
 *
 * <h3>为什么这一层在 desktop-host 而不在 player-core</h3>
 *
 * <strong>{@code javax.sound.sampled} 在安卓上不存在</strong>，所以设备 I/O 不能放进
 * player-core。player-core 只放纯类型与纯函数（能力矩阵、挑选、转换、限幅），
 * 桌面宿主实现 {@link OutputDevice}：安卓端以后用 {@code AudioTrack} 实现同一套接口。
 *
 * <h3>与 cpal 的能力探测差在哪（诚实标注）</h3>
 *
 * <ol>
 *   <li>cpal 报的是<strong>区间</strong>（{@code min_sample_rate..max_sample_rate}）；
 *       {@code AudioFormat} 只有<strong>一个</strong> {@code getSampleRate()}。实测
 *       Windows 的 DirectSound 后端干脆把采样率报成 {@code NOT_SPECIFIED}（“任意”，
 *       由 OS 重采样）。所以这里按上游那张常见档位表去问平台「这一档你到底收不收」
 *       （{@link DeviceCapabilities#RATE_LADDER}），再把连续的档位合并回区间——
 *       这样能力矩阵的形状（区间）与上游一致，内容也是平台亲口说的，不是猜的。</li>
 *   <li>它还会报一堆我们开不了的格式（8 位、μ-law、A-law、大端变体）：
 *       <strong>直接跳过，不报错</strong>（上游原文）。跳过不等于丢掉——
 *       全部记进 {@link DeviceCapabilities#declaredFormats()}，DSD 判定要看它。</li>
 *   <li>{@code AudioFormat} 的 24 位惯例是<strong>3 字节紧凑打包</strong>
 *       （{@code frameSize == 3 × channels}），而上游的 I24 是「4 字节存储、24 位有效」。
 *       两种帧长都会探、都会试着开，真正的存储约定在写入时按线路实际帧长决定
 *       （见 {@link JavaSoundOutputStream}）。</li>
 * </ol>
 *
 * <h3>DSD</h3>
 *
 * {@code AudioFormat.Encoding} 里没有 DSD，所以桌面端的 DSD 结论恒为
 * {@link DsdSupport#NONE}——不是偷懒，是平台确实不提供（见 {@link #dsdSupport()}）。
 */
public final class JavaSoundOutputDevice implements OutputDevice {

    /**
     * 不指定固定缓冲时用的设备缓冲帧数。
     *
     * <p>Java Sound 的默认值通常很大（0.5 s 量级），会把暂停/定位的响应拖钝。
     * 真正的缓冲是在 player-core 的环形缓冲里（约 200 ms），设备缓冲只影响单次写入的粒度，
     * 所以这里取一个 ~43 ms（48 kHz）的小值。调用方要压低延迟请用
     * {@code OutputRequest.fixedBufferFrames}。
     */
    static final int DEFAULT_BUFFER_FRAMES = 2_048;

    private final Mixer.Info mixerInfo;
    private volatile DeviceCapabilities cached;

    /**
     * @param mixerInfo 该设备对应的混音器，{@code null} 表示系统默认混音器
     */
    public JavaSoundOutputDevice(Mixer.Info mixerInfo) {
        this.mixerInfo = mixerInfo;
    }

    @Override
    public String name() {
        if (mixerInfo != null) {
            return mixerInfo.getName();
        }
        Mixer mixer = mixer();
        return mixer == null ? "默认输出设备" : mixer.getMixerInfo().getName();
    }

    private Mixer mixer() {
        try {
            // AudioSystem.getMixer(null) 返回默认混音器（探测路径，不打开任何线路）
            return AudioSystem.getMixer(mixerInfo);
        } catch (Throwable t) {
            // 没有音频系统 / 驱动异常：当成「没有这台设备」，由调用方走空矩阵分支
            return null;
        }
    }

    /**
     * 枚举这台设备的能力矩阵。
     *
     * <p><strong>绝不抛异常</strong>：没有可用声卡、混音器不存在、驱动抛错，一律返回
     * 已经收集到的部分（可能是空矩阵）。探测是界面初始化时必走的一步，
     * 它抛异常等于整个设置页打不开。
     */
    @Override
    public DeviceCapabilities capabilities() {
        DeviceCapabilities local = cached;
        if (local == null) {
            local = enumerate();
            cached = local;
        }
        return local;
    }

    private DeviceCapabilities enumerate() {
        List<DeviceCapability> entries = new ArrayList<DeviceCapability>();
        List<DeviceSampleFormat> declared = new ArrayList<DeviceSampleFormat>();

        Mixer m = mixer();
        if (m == null) {
            return DeviceCapabilities.empty();
        }

        Line.Info[] lines;
        try {
            lines = m.getSourceLineInfo();
        } catch (Throwable t) {
            return DeviceCapabilities.empty();
        }
        if (lines == null) {
            return DeviceCapabilities.empty();
        }

        // (格式, 声道) → 支持的档位表下标，最后合并成区间
        Map<String, TreeSet<Integer>> rateHits = new LinkedHashMap<String, TreeSet<Integer>>();

        for (int i = 0; i < lines.length; i++) {
            Line.Info li = lines[i];
            if (!(li instanceof DataLine.Info)) {
                continue; // Port 之类不是数据线
            }
            DataLine.Info di = (DataLine.Info) li;
            if (!SourceDataLine.class.isAssignableFrom(di.getLineClass())) {
                continue; // Clip 不是我们要的（它不做流式写入）
            }
            AudioFormat[] formats;
            try {
                formats = di.getFormats();
            } catch (Throwable t) {
                continue;
            }
            if (formats == null) {
                continue;
            }
            for (int k = 0; k < formats.length; k++) {
                collect(m, formats[k], entries, declared, rateHits);
            }
        }

        // 把档位表里连续命中的采样率合并成区间。
        //
        // 注意这里合并出来的是「平台愿意接受的区间」，不一定等于「硬件原生支持的区间」：
        // Windows 的 DirectSound 后端对任何采样率都说收，实际由 OS 重采样到硬件速率。
        // 所以由这份矩阵导出的 Hi-Res 门槛含义是「输出这条路愿意接受」，而不是
        // 「位完美」。位完美的判定在 player-core：拿实际协商到的 OutputConfig 和源格式
        // 一起喂给 PathVerdict.classify。
        for (Map.Entry<String, TreeSet<Integer>> e : rateHits.entrySet()) {
            String[] key = e.getKey().split("\\|");
            OutputSampleFormat fmt = OutputSampleFormat.valueOf(key[0]);
            int channels = Integer.parseInt(key[1]);
            int[] ladder = DeviceCapabilities.RATE_LADDER;
            int start = -1;
            int prev = -2;
            for (Integer idx : e.getValue()) {
                int i = idx.intValue();
                if (start < 0) {
                    start = i;
                } else if (i != prev + 1) {
                    entries.add(new DeviceCapability(fmt, channels, ladder[start], ladder[prev]));
                    start = i;
                }
                prev = i;
            }
            if (start >= 0) {
                entries.add(new DeviceCapability(fmt, channels, ladder[start], ladder[prev]));
            }
        }

        // 立体声档位排到前面。
        //
        // 理由：上游的挑选逻辑（OutputChooser.choose）在打分相同时保留**先出现**的那一档，
        // 而 cpal 报的是设备原生档位（通常就是立体声），所以那边「先出现的」天然是对的那个。
        // javax.sound 的 AudioFormat[] 顺序是固定排列——**1 声道排在 2 声道前面**，
        // 原样传下去会让立体声播放被协商成单声道（实测：48 kHz 立体声请求选出 1ch）。
        // 设备原生顺序在 cpal 那边有意义，在这里没有，所以由适配层把本播放器的
        // 目标布局（立体声）提到前面，player-core 的挑选规则保持与上游一一对应。
        java.util.Collections.sort(entries, new java.util.Comparator<DeviceCapability>() {
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

    /** 处理设备声明的一个 {@code AudioFormat}。 */
    private static void collect(Mixer m, AudioFormat f, List<DeviceCapability> entries,
                               List<DeviceSampleFormat> declared, Map<String, TreeSet<Integer>> rateHits) {
        if (f == null) {
            return;
        }
        DeviceSampleFormat dsf = fromJavaSound(f);
        // 声明清单按「平台报了几条就是几条」记（同一格式的字节序/帧长变体各算一条），
        // 只有 DSD 判定看它，重复无副作用
        declared.add(dsf);

        // 我们不支持的格式直接跳过，不报错——设备报一堆格式是常态
        if (!OutputSampleFormat.isSupported(dsf)) {
            return;
        }
        // 写路径只做小端（见 OutputSampleFormat.writeSample）；javax.sound 会把大端变体
        // 也一起列出来，那些跳过
        if (f.isBigEndian()) {
            return;
        }
        OutputSampleFormat fmt = OutputSampleFormat.fromDevice(dsf);
        int channels = f.getChannels() == AudioSystem.NOT_SPECIFIED ? 2 : f.getChannels();
        if (channels < 1) {
            return;
        }
        int bits = f.getSampleSizeInBits();
        if (bits == AudioSystem.NOT_SPECIFIED || bits <= 0) {
            return;
        }

        float rate = f.getSampleRate();
        if (rate == AudioSystem.NOT_SPECIFIED) {
            // 平台说「任意采样率」：按常见档位逐档问它收不收，避免把区间展开成上万个值
            int[] ladder = DeviceCapabilities.RATE_LADDER;
            for (int i = 0; i < ladder.length; i++) {
                if (lineSupported(m, fmt, bits, channels, ladder[i])) {
                    String key = fmt.name() + "|" + channels;
                    TreeSet<Integer> hits = rateHits.get(key);
                    if (hits == null) {
                        hits = new TreeSet<Integer>();
                        rateHits.put(key, hits);
                    }
                    hits.add(Integer.valueOf(i));
                }
            }
        } else if (rate > 0) {
            // 报的是具体采样率：直接信它（单点区间）
            entries.add(new DeviceCapability(fmt, channels, (int) rate, (int) rate));
        }
    }

    /** 按格式与帧长候选，问混音器这个档位收不收（纯查询，不打开线路）。 */
    private static boolean lineSupported(Mixer m, OutputSampleFormat fmt, int bits, int channels, int rate) {
        int[] frameSizes = frameSizeCandidates(fmt, bits, channels);
        for (int i = 0; i < frameSizes.length; i++) {
            try {
                AudioFormat probe = new AudioFormat(encodingOf(fmt), (float) rate, bits, channels,
                        frameSizes[i], (float) rate, false);
                if (m.isLineSupported(new DataLine.Info(SourceDataLine.class, probe))) {
                    return true;
                }
            } catch (Throwable t) {
                // 单个候选查询失败就当不支持
            }
        }
        return false;
    }

    /**
     * 一帧的字节数候选。
     *
     * <p>24 位有两个候选：Java Sound 惯例的 3 字节紧凑打包，以及上游 I24 语义的
     * 「4 字节存储、24 位有效」。两个都问一遍，谁被支持就用谁。
     */
    static int[] frameSizeCandidates(OutputSampleFormat fmt, int bits, int channels) {
        if (fmt == OutputSampleFormat.I24) {
            return new int[]{3 * channels, 4 * channels};
        }
        return new int[]{bits / 8 * channels};
    }

    /** {@link OutputSampleFormat} → javax.sound 的编码。 */
    static AudioFormat.Encoding encodingOf(OutputSampleFormat fmt) {
        switch (fmt) {
            case U16:
                return AudioFormat.Encoding.PCM_UNSIGNED;
            case F32:
                return AudioFormat.Encoding.PCM_FLOAT;
            default:
                return AudioFormat.Encoding.PCM_SIGNED;
        }
    }

    /** 有效位数（{@code AudioFormat} 的 sampleSizeInBits）。 */
    static int bitsOf(OutputSampleFormat fmt) {
        return fmt.bitDepth().bits();
    }

    /**
     * javax.sound 的格式 → 我们能理解的设备格式。
     *
     * <p>与上游 {@code OutputSampleFormat::from_cpal} 同一个位置：认不出来就
     * {@link DeviceSampleFormat#OTHER}，由调用方<strong>跳过而不报错</strong>。
     */
    static DeviceSampleFormat fromJavaSound(AudioFormat f) {
        if (f == null) {
            return DeviceSampleFormat.OTHER;
        }
        AudioFormat.Encoding enc = f.getEncoding();
        int bits = f.getSampleSizeInBits();
        if (AudioFormat.Encoding.PCM_SIGNED.equals(enc)) {
            switch (bits) {
                case 16: return DeviceSampleFormat.I16;
                case 24: return DeviceSampleFormat.I24;
                case 32: return DeviceSampleFormat.I32;
                default: return DeviceSampleFormat.OTHER;
            }
        }
        if (AudioFormat.Encoding.PCM_UNSIGNED.equals(enc)) {
            switch (bits) {
                case 16: return DeviceSampleFormat.U16;
                case 8: return DeviceSampleFormat.U8;
                default: return DeviceSampleFormat.OTHER;
            }
        }
        if (AudioFormat.Encoding.PCM_FLOAT.equals(enc)) {
            switch (bits) {
                case 32: return DeviceSampleFormat.F32;
                case 64: return DeviceSampleFormat.F64;
                default: return DeviceSampleFormat.OTHER;
            }
        }
        // ULAW / ALAW / 其它
        return DeviceSampleFormat.OTHER;
    }

    /**
     * 设备默认配置。
     *
     * <p>cpal 有 {@code default_output_config()}；{@code javax.sound} 没有等价 API，
     * 所以按 {@link OutputChooser#chooseDefault} 里写明的那条规则重建
     * （2 声道 → 48 kHz → 44.1 kHz → 矩阵里最好的一档）。
     */
    @Override
    public OutputConfig defaultConfig(int ringFrames) {
        return OutputChooser.chooseDefault(capabilities()).toConfig(ringFrames);
    }

    /**
     * 开一条 {@code SourceDataLine}。
     *
     * <p>对应上游的 {@code build_output_stream}：消费端由 player-core 交进来，
     * 包成 {@link JavaSoundOutputStream}（自己的写线程，见该类注释）。
     */
    @Override
    public OutputStreamControl openStream(OutputConfig config, RingBuffer.Consumer consumer,
                                          Integer fixedBufferFrames) {
        Mixer m = mixer();
        if (m == null) {
            throw AudioException.output("没有可用的输出设备");
        }
        int bits = bitsOf(config.sampleFormat());
        int channels = config.channels();
        int[] frameSizes = frameSizeCandidates(config.sampleFormat(), bits, channels);

        LineUnavailableException failure = null;
        for (int i = 0; i < frameSizes.length; i++) {
            int frameSize = frameSizes[i];
            AudioFormat format = new AudioFormat(encodingOf(config.sampleFormat()),
                    (float) config.sampleRate(), bits, channels, frameSize,
                    (float) config.sampleRate(), false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            try {
                if (!m.isLineSupported(info)) {
                    continue;
                }
                SourceDataLine line = (SourceDataLine) m.getLine(info);
                int frames = fixedBufferFrames != null
                        ? fixedBufferFrames.intValue() : DEFAULT_BUFFER_FRAMES;
                int bufferBytes = Math.max(frames * frameSize, frameSize);
                line.open(format, bufferBytes);
                return new JavaSoundOutputStream(line, config, consumer);
            } catch (LineUnavailableException e) {
                failure = e;
            } catch (RuntimeException e) {
                // IllegalArgumentException / SecurityException 之类：换下一个帧长候选
                failure = new LineUnavailableException(e.toString());
            }
        }
        throw AudioException.output("建立音频流失败：" + config + "（"
                + (failure == null ? "设备不支持该配置" : failure.getMessage()) + "）");
    }

    /**
     * DSD 支持度：桌面端恒为 {@link DsdSupport#NONE}。
     *
     * <p>{@code AudioFormat.Encoding} 只有 {@code PCM_SIGNED / PCM_UNSIGNED / PCM_FLOAT /
     * ULAW / ALAW}，没有 DSD，所以枚举结果里永远不会有 DSD 格式。这是平台的限制，
     * 不是探测没写：上游那边靠 cpal 的 {@code DsdU8/U16/U32} 才有 {@code AVAILABLE}。
     */
    @Override
    public DsdSupport dsdSupport() {
        return DsdSupport.NONE;
    }

    /** 默认配置的声道数（与上游 {@code probe_max_channels} 取法一致，不是矩阵最大值）。 */
    @Override
    public int maxChannels() {
        DeviceCapabilities caps = capabilities();
        if (caps.isEmpty()) {
            return 0;
        }
        return defaultConfig(1).channels();
    }

    /**
     * 缓冲帧数范围：桌面端不提供（返回 {@code null}）。
     *
     * <p>对应上游 {@code SupportedBufferSize::Unknown} 分支——<strong>不能当成 0 处理</strong>。
     * {@code DataLine.Info} 里没有任何关于缓冲大小的信息，所以这里只能如实说「不知道」，
     * 而不是编一个范围出来。
     */
    @Override
    public OutputDevice.BufferRange bufferSizeRange() {
        return null;
    }

    @Override
    public String toString() {
        return "JavaSoundOutputDevice{" + name() + ", " + capabilities() + '}';
    }

    /**
     * 设备身份 = 混音器身份。
     *
     * <p>每次 {@code list()} 都会新建一批设备对象，没有相等性的话
     * 「默认设备是不是列表里的某一台」就永远问不出来（{@code contains} 恒为 false）。
     * {@code Mixer.Info} 自己按名称/厂商/描述/版本比较，正好就是我们要的粒度。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JavaSoundOutputDevice)) {
            return false;
        }
        JavaSoundOutputDevice other = (JavaSoundOutputDevice) o;
        return mixerInfo == null ? other.mixerInfo == null : mixerInfo.equals(other.mixerInfo);
    }

    @Override
    public int hashCode() {
        return mixerInfo == null ? 0 : mixerInfo.hashCode();
    }
}
