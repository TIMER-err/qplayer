package dev.t1m3.qplayer.android.audio.output;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import dev.t1m3.qplayer.android.audio.AndroidAudioFormats;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import dev.t1m3.qplayer.audio.output.DeviceCapabilities;
import dev.t1m3.qplayer.audio.output.DeviceSampleFormat;
import dev.t1m3.qplayer.audio.output.DsdSupport;
import dev.t1m3.qplayer.audio.output.OutputChooser;
import dev.t1m3.qplayer.audio.output.OutputConfig;
import dev.t1m3.qplayer.audio.output.OutputDevice;
import dev.t1m3.qplayer.audio.output.OutputSampleFormat;
import dev.t1m3.qplayer.audio.output.OutputStreamControl;
import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 安卓端的输出设备：{@code AudioTrack}（{@code MODE_STREAM}）。
 *
 * <p>对应上游 {@code sonalis-output} 里用 cpal 的那部分：能力矩阵
 * （{@code capabilities_of}）、挑选（已抽到 player-core 的 {@link OutputChooser}）、
 * 默认配置（{@code default_output_config()}）、开流（{@code build_output_stream}）。
 * 与桌面端 {@code JavaSoundOutputDevice} 是同一个接缝的另一份实现。
 *
 * <h3>能力探测：平台说什么就记什么，探不出来就窄</h3>
 *
 * <ol>
 *   <li>原生采样率/原生缓冲帧数来自 {@code AudioManager.getProperty(
 *       PROPERTY_OUTPUT_SAMPLE_RATE / PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}——
 *       这是平台亲口说的（混音器工作的采样率），不是我们猜的。</li>
 *   <li>{@code AudioTrack.getMinBufferSize(rate, mask, encoding)} 是每个档位的
 *       <strong>廉价前置筛</strong>：平台自己说不支持（返回 {@code ERROR_BAD_VALUE}）就跳过。</li>
 *   <li>API 29+ 用 {@code AudioTrack.isDirectPlaybackSupported(...)}（原生直通路径，
 *       它绕开混音器）；低版本没有这个 API，就退化成<strong>「按常见档位试开再关」</strong>
 *       （{@code MODE_STREAM} 的 track 建起来看 {@code STATE_INITIALIZED} 就释放）。
 *       两条路是<strong>或</strong>的关系：直通说自己不支持、但普通路径能开，也算能开。</li>
 *   <li>探不出来就如实返回窄/空矩阵。<strong>不编</strong>：编出来的档位会让界面显示
 *       「支持 Hi-Res」而实际开不出声。</li>
 * </ol>
 *
 * <h3>矩阵的含义：「输出这条路愿意接受」，不是「位完美」</h3>
 *
 * <p>与桌面端（DirectSound 报 NOT_SPECIFIED、由 OS 重采样）同一个结论：
 * {@code AudioTrack} 在普通路径上会接受很宽的采样率并由系统重采样到设备速率。
 * 所以这份矩阵说明的是「能以此开流」；<strong>位完美判定必须用
 * {@code PathVerdict.classify(源格式, 实际协商到的 OutputConfig)}</strong>，
 * 两者混起来就是「界面显示位完美、实际在重采样」这类永远查不出来的问题。
 *
 * <h3>DSD</h3>
 *
 * <p>{@code AudioFormat} 里没有 DSD 编码（{@code ENCODING_DSD_*} 不是公开 API），
 * {@code AudioTrack} 也开不出 DSD。所以这里恒为 {@link DsdSupport#NONE}——
 * 平台确实不提供，不是探测偷懒。
 */
public final class AndroidOutputDevice implements OutputDevice {

    /**
     * 不指定固定缓冲时用的设备缓冲帧数（≈43 ms @48 kHz），与桌面端同值。
     *
     * <p>真正的缓冲是 player-core 的环形缓冲（约 200 ms），设备缓冲只影响单次写入的粒度；
     * 但太小会在负载波动时欠载（写线程晚 40 ms 就是一次爆音），太大又让暂停/定位变钝。
     * 实际取值还会被 {@code getMinBufferSize} 顶高（见 {@link #openStream}）。
     */
    static final int DEFAULT_BUFFER_FRAMES = 2_048;

    /** 「试开再关」探测时给的最小缓冲帧数：太小会被平台判成非法参数，探出假阴性。 */
    private static final int PROBE_BUFFER_FRAMES = 1_024;

    /** 本次移植的目标布局：引擎链路（混音/过渡/声道映射）就是立体声。 */
    private static final int PROBE_CHANNELS = 2;

    /** 音频用途/内容类型，与播放引擎那条链路（前身是 MediaPlayer）保持一致。 */
    static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private final Context appContext;
    private final AudioManager audioManager;
    private volatile DeviceCapabilities cached;

    /**
     * 「这一档能不能<strong>真的写进去</strong>」的结论缓存：{@code 格式@采样率x声道 → 能不能写}。
     *
     * <p>为什么不能只看 {@code STATE_INITIALIZED}：2026-09-20 在真机上实测到一条
     * <strong>建得成、进得了 PLAYING、第一次 {@code write} 却被 HAL 用
     * {@code ERROR_INVALID_OPERATION} 拒掉</strong>的 F32 @48 kHz 输出流
     * （日志：{@code 流已启动: state=1 playState=3 48000 Hz / 2 ch / F32} 紧跟
     * {@code AudioTrack 写入失败: 状态非法}）。创建成功只证明「平台收下了这个描述」，
     * 不证明「这个格式能出声」——所以真正的判据是写一次。
     */
    private final java.util.Map<String, Boolean> writable =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    public AndroidOutputDevice(Context appContext) {
        this.appContext = appContext;
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public String name() {
        Integer rate = nativeSampleRate();
        Integer frames = nativeFramesPerBuffer();
        if (rate == null) {
            // 平台没报原生档位就不报数字：编一个「48000 Hz」出来只会误导
            return "安卓默认输出（AudioTrack）";
        }
        return frames == null
                ? "安卓默认输出（AudioTrack）· " + rate + " Hz"
                : "安卓默认输出（AudioTrack）· " + rate + " Hz / " + frames + " 帧";
    }

    /** 平台报的原生采样率；拿不到返回 {@code null}。 */
    private Integer nativeSampleRate() {
        String v = property(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        if (v == null) {
            return null;
        }
        int rate = AndroidCapabilities.propertyInt(v, 0);
        return rate > 0 ? Integer.valueOf(rate) : null;
    }

    /** 平台报的原生缓冲帧数；拿不到返回 {@code null}。 */
    private Integer nativeFramesPerBuffer() {
        String v = property(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        if (v == null) {
            return null;
        }
        int frames = AndroidCapabilities.propertyInt(v, 0);
        return frames > 0 ? Integer.valueOf(frames) : null;
    }

    private String property(String key) {
        if (audioManager == null) {
            return null;
        }
        try {
            return audioManager.getProperty(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 枚举能力矩阵。<strong>绝不抛异常</strong>：没有 AudioService、驱动异常、探测失败，
     * 一律返回已经收集到的部分（可能是空矩阵）。探测是设置页初始化时必走的一步。
     *
     * <p>代价：低版本（API &lt; 29）上「试开再关」会真的建一批 {@code AudioTrack}，
     * 首次调用可能花几十到几百毫秒。结果是缓存住的，所以别在每帧的路径上调它；
     * 想量实测耗时看日志里的这一行。
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
        List<AndroidCapabilities.Hit> hits = new ArrayList<AndroidCapabilities.Hit>();
        List<DeviceSampleFormat> declared = new ArrayList<DeviceSampleFormat>();
        // 设备「声明过」的格式：AudioTrack 只有 PCM（16 位 / float，API 31 起还有 24/32 位）。
        // DSD 判定看这份清单，不看可开流矩阵（矩阵里永远不会有 DSD）
        declared.add(DeviceSampleFormat.I16);
        declared.add(DeviceSampleFormat.F32);

        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= AndroidAudioFormats.SDK_PCM_24_32) {
            declared.add(DeviceSampleFormat.I24);
            declared.add(DeviceSampleFormat.I32);
        }

        try {
            int channelMask = AndroidAudioFormats.channelMask(PROBE_CHANNELS);
            int[] encodings = encodingsFor(sdk);
            int[] ladder = DeviceCapabilities.RATE_LADDER;
            Integer nativeRate = nativeSampleRate();

            long started = System.currentTimeMillis();
            int probed = 0;
            for (int e = 0; e < encodings.length; e++) {
                int encoding = encodings[e];
                OutputSampleFormat fmt = AndroidAudioFormats.toOutputSampleFormat(encoding, sdk);
                if (fmt == null) {
                    continue; // 平台版本不够（24/32 位打包）：跳过而不是报错
                }
                for (int i = 0; i < ladder.length; i++) {
                    probed++;
                    if (accepts(ladder[i], encoding, channelMask, sdk)) {
                        hits.add(new AndroidCapabilities.Hit(fmt, PROBE_CHANNELS, ladder[i]));
                    }
                }
                // 原生采样率一定要进矩阵：它不一定在档位表上（那也要单点记下来），
                // 而它是「跟随源采样率」那条位完美路径唯一真正需要的档位。
                // 即使它已在档位表里也再确认一次——重复的命中在拼装时会被合并掉。
                if (nativeRate != null) {
                    probed++;
                    if (accepts(nativeRate.intValue(), encoding, channelMask, sdk)) {
                        hits.add(new AndroidCapabilities.Hit(fmt, PROBE_CHANNELS, nativeRate.intValue()));
                    }
                }
            }
            Logger.info("AndroidOutputDevice: 探测 {} 档，命中 {} 档，用时 {} ms（SDK {}）",
                    Integer.valueOf(probed), Integer.valueOf(hits.size()),
                    Long.valueOf(System.currentTimeMillis() - started), Integer.valueOf(sdk));
        } catch (Throwable t) {
            // 探测路径上的任何异常都不该把界面拖下水：把已经收集到的部分交出去
            Logger.warn("AndroidOutputDevice: 能力探测中断：{}", t.toString());
        }

        return AndroidCapabilities.assemble(hits, declared);
    }

    /** 本次要探的编码。16 位与 float 恒可用；24/32 位打包要 API 31+（是加分项，不是前提）。 */
    private static int[] encodingsFor(int sdk) {
        if (sdk >= AndroidAudioFormats.SDK_PCM_24_32) {
            return new int[]{
                    AndroidAudioFormats.ENCODING_PCM_16BIT,
                    AndroidAudioFormats.ENCODING_PCM_FLOAT,
                    AndroidAudioFormats.ENCODING_PCM_24BIT_PACKED,
                    AndroidAudioFormats.ENCODING_PCM_32BIT,
            };
        }
        return new int[]{
                AndroidAudioFormats.ENCODING_PCM_16BIT,
                AndroidAudioFormats.ENCODING_PCM_FLOAT,
        };
    }

    /**
     * 这一档到底能不能开。
     *
     * <p>API 29+ 先问 {@code isDirectPlaybackSupported}（便宜且是平台权威答复，
     * 且它代表<strong>原生直通</strong>路径——不重采样那条）；它说不支持时再试开一次。
     * 低版本没有这个 API，只有「试开再关」这一条路。
     */
    private boolean accepts(int rate, int encoding, int channelMask, int sdk) {
        int minBufferBytes;
        try {
            minBufferBytes = AudioTrack.getMinBufferSize(rate, channelMask, encoding);
        } catch (RuntimeException e) {
            return false;
        }
        if (minBufferBytes <= 0) {
            // ERROR_BAD_VALUE / ERROR：平台自己说这一档不行，连试都不用试
            return false;
        }
        if (sdk >= AndroidAudioFormats.SDK_DIRECT_PLAYBACK
                && DirectPlayback.supported(rate, encoding, channelMask)) {
            return true;
        }
        return probeOpen(minBufferBytes, rate, encoding, channelMask);
    }

    /**
     * 「试开再关」：建一条 {@code MODE_STREAM} 的 track，看它有没有进
     * {@code STATE_INITIALIZED}，然后立刻释放。
     *
     * <p>这是低版本上唯一能拿到「平台到底收不收这一档」的办法。不真写数据、不 start，
     * 所以不会有可听的开流噪音；失败与异常一律当成「不支持」（探测不能把界面拖下水）。
     */
    private static boolean probeOpen(int minBufferBytes, int rate, int encoding, int channelMask) {
        AudioTrack track = null;
        try {
            AudioFormat format = audioFormat(rate, encoding, channelMask);
            int frameBytes = AndroidAudioFormats.frameBytes(encoding, PROBE_CHANNELS);
            int bufferBytes = AndroidAudioFormats.roundUpToFrame(
                    Math.max(minBufferBytes, PROBE_BUFFER_FRAMES * Math.max(1, frameBytes)),
                    frameBytes);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(AUDIO_ATTRIBUTES)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            return track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static AudioFormat audioFormat(int rate, int encoding, int channelMask) {
        return new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .build();
    }

    /**
     * {@code isDirectPlaybackSupported} 的隔离壳。
     *
     * <p>把它单独放一个类里（而不是在 {@link AndroidOutputDevice} 的方法体里直接调）
     * 是为了让「这个 API 在低版本上不存在」这件事只影响这一个类：
     * 类只在 {@code SDK_INT >= 29} 的分支里被加载，API 26~28 上根本不会碰到它，
     * 也就没有 {@code NoSuchMethodError} / 校验失败的风险。
     */
    private static final class DirectPlayback {
        static boolean supported(int rate, int encoding, int channelMask) {
            try {
                return AudioTrack.isDirectPlaybackSupported(
                        audioFormat(rate, encoding, channelMask), AUDIO_ATTRIBUTES);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /**
     * 设备默认配置。
     *
     * <p>与桌面端同一条重建规则（{@link OutputChooser#chooseDefault}：2 声道 → 48 kHz →
     * 44.1 kHz → 矩阵里最好的一档）。这里的矩阵已经把立体声排在最前面。
     */
    @Override
    public OutputConfig defaultConfig(int ringFrames) {
        OutputConfig chosen = OutputChooser.chooseDefault(capabilities()).toConfig(ringFrames);
        if (writable(chosen)) {
            return chosen;
        }
        // 这条配置写不进去：换最兼容的一档（I16，仍是设备原生采样率）重挑一次，
        // **并且把这件事说清楚**——静默降级是禁止的，而"降级但写进日志"是允许的：
        // 16 位整数 → f32 在引擎内部是精确的（只改指数），代价只是动态范围。
        int rate = safeRate(chosen.sampleRate());
        Logger.warn("AndroidOutputDevice: {} 这条配置写不进去（试写被拒）→ 改用 I16 @ {} Hz 重挑；"
                + "这条降级只是动态范围，不是精度（见 ADR-003 §3.2）", chosen, Integer.valueOf(rate));
        OutputConfig safe = OutputChooser.chooseDefault(i16At(rate)).toConfig(ringFrames);
        if (writable(safe)) {
            Logger.info("AndroidOutputDevice: 改用 {} 后写入正常", safe);
            return safe;
        }
        // I16 也写不进去：如实把原来的选择交出去，让失败在开流/写入处暴露出来，
        // 而不是在这里编一个「应该能行」的配置。
        Logger.warn("AndroidOutputDevice: I16 @ {} Hz 同样写不进去——如实交回 {}，"
                + "失败会在写入处报出来", Integer.valueOf(rate), chosen);
        return chosen;
    }

    /** 采样率取原生档位；拿不到就用选中的那一档（不编数字）。 */
    private int safeRate(int fallback) {
        Integer native_ = nativeSampleRate();
        return native_ != null && native_.intValue() > 0 ? native_.intValue() : fallback;
    }

    /** 只报 I16（在给定采样率上）的能力矩阵：用于「高位深写不进去」时的回退。 */
    private DeviceCapabilities i16At(int rate) {
        List<AndroidCapabilities.Hit> hits = new ArrayList<AndroidCapabilities.Hit>();
        hits.add(new AndroidCapabilities.Hit(OutputSampleFormat.I16, PROBE_CHANNELS, rate));
        List<DeviceSampleFormat> declared = new ArrayList<DeviceSampleFormat>();
        declared.add(DeviceSampleFormat.I16);
        return AndroidCapabilities.assemble(hits, declared);
    }

    /** 这条配置能不能真的写进去（结论按 格式@采样率x声道 缓存）。 */
    private boolean writable(OutputConfig config) {
        String key = config.sampleFormat() + "@" + config.sampleRate() + "x" + config.channels();
        Boolean cachedResult = writable.get(key);
        if (cachedResult != null) {
            return cachedResult.booleanValue();
        }
        boolean ok = probeWritable(config);
        writable.put(key, Boolean.valueOf(ok));
        Logger.info("AndroidOutputDevice: 试写 {} → {}", config, ok ? "可以写" : "写不进去");
        return ok;
    }

    /** 写失败回报进来（见 {@link #markUnwritable}）。 */
    private void markUnwritable(OutputConfig config, String why) {
        String key = config.sampleFormat() + "@" + config.sampleRate() + "x" + config.channels();
        writable.put(key, Boolean.FALSE);
        Logger.warn("AndroidOutputDevice: {} 被运行中的写入否决了（{}）→ 下一次挑档会避开它",
                config, why);
    }

    /**
     * 真写一次静音，回答「这个配置能不能出声」。
     *
     * <p>建流 → {@code play()} → 写一小块<strong>静音</strong>（全零字节，听不见）→ 看返回码 → 释放。
     * 写静音不会有可听的开流噪音，而返回码正好把「建得成但写不进去」这种 HAL 拒绝抓出来。
     * 结论缓存住，所以一个进程里每档只花一次（几十毫秒），且只在真正要用的那一档上做。
     */
    private boolean probeWritable(OutputConfig config) {
        int sdk = Build.VERSION.SDK_INT;
        int encoding = AndroidAudioFormats.fromOutputSampleFormat(config.sampleFormat(), sdk);
        int channelMask = AndroidAudioFormats.channelMask(config.channels());
        int frameBytes = AndroidAudioFormats.frameBytes(encoding, config.channels());
        if (encoding == AndroidAudioFormats.ENCODING_INVALID || channelMask < 0 || frameBytes <= 0) {
            return false;
        }
        AudioTrack track = null;
        try {
            int minBufferBytes = AudioTrack.getMinBufferSize(
                    config.sampleRate(), channelMask, encoding);
            if (minBufferBytes <= 0) {
                return false;
            }
            int bufferBytes = AndroidAudioFormats.roundUpToFrame(
                    Math.max(minBufferBytes, PROBE_BUFFER_FRAMES * frameBytes), frameBytes);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(AUDIO_ATTRIBUTES)
                    .setAudioFormat(audioFormat(config.sampleRate(), encoding, channelMask))
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return false;
            }
            track.play();
            byte[] silence = new byte[frameBytes * 256];
            int n = track.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
            return n > 0;
        } catch (RuntimeException e) {
            // 建不起来 / 写不进去 / ROM 抽风：都算「这一档不能写」，交给上层换档
            return false;
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    track.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * 开一条 {@code AudioTrack}（<strong>不启动</strong>，启动由 {@link OutputStreamControl#play()}
     * 统一做——{@code OutputHandle} 在开流成功后就调它）。
     *
     * <p>这条流一旦打开就<strong>不为换曲停/启</strong>：「一条永不中断的输出流」是无缝的前提
     * （ADR-003 §2）。换曲、过渡、跳转都只是往环形缓冲里推不同的数据。
     */
    @Override
    public OutputStreamControl openStream(OutputConfig config, RingBuffer.Consumer consumer,
                                          Integer fixedBufferFrames) {
        // 引擎是在**生产者线程**上调开流的（PlaybackEngine.ensureOutput ← handlePlay），
        // 而这条线程要一路喂混音结果进环形缓冲：在这里把它的优先级提到音频级，
        // 是唯一能精确作用到那条线程的位置（引擎的 ThreadTuner 只在 start() 之前、
        // 调用方线程上被回调，那里调 setThreadPriority 会提到 UI 线程头上，见
        // AndroidProducerTuner 的类注释）。没有登记过时这里只是一次 volatile 读。
        dev.t1m3.qplayer.android.playback.AndroidProducerTuner.applyToCurrentThreadIfProducer();
        int sdk = Build.VERSION.SDK_INT;
        int encoding = AndroidAudioFormats.fromOutputSampleFormat(config.sampleFormat(), sdk);
        if (encoding == AndroidAudioFormats.ENCODING_INVALID) {
            throw AudioException.output("AudioTrack 开不了这条采样格式：" + config.sampleFormat()
                    + (config.sampleFormat() == OutputSampleFormat.I24
                            || config.sampleFormat() == OutputSampleFormat.I32
                            ? "（24/32 位需要 Android 12 / API 31+，本机 SDK " + sdk + "）"
                            : "（无符号 16 位 PCM 在安卓上没有对应编码）"));
        }
        int channelMask = AndroidAudioFormats.channelMask(config.channels());
        if (channelMask < 0) {
            throw AudioException.output("AudioTrack 这条路只开了单声道/立体声，开不了 "
                    + config.channels() + " 声道");
        }
        int frameBytes = AndroidAudioFormats.frameBytes(encoding, config.channels());
        int minBufferBytes;
        try {
            minBufferBytes = AudioTrack.getMinBufferSize(config.sampleRate(), channelMask, encoding);
        } catch (RuntimeException e) {
            minBufferBytes = AudioTrack.ERROR_BAD_VALUE;
        }
        if (minBufferBytes <= 0) {
            throw AudioException.output("设备不支持 " + config.sampleRate() + " Hz / "
                    + config.sampleFormat() + " / " + config.channels() + " 声道的输出");
        }
        int wantFrames = fixedBufferFrames != null
                ? fixedBufferFrames.intValue() : DEFAULT_BUFFER_FRAMES;
        int bufferBytes = AndroidAudioFormats.roundUpToFrame(
                Math.max(minBufferBytes, wantFrames * frameBytes), frameBytes);

        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(AUDIO_ATTRIBUTES)
                    .setAudioFormat(audioFormat(config.sampleRate(), encoding, channelMask))
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException e) {
            throw AudioException.output("建立音频流失败：" + config + "（" + e + "）");
        }
        int state = track.getState();
        if (state != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release();
            } catch (RuntimeException ignored) {
            }
            throw AudioException.output("AudioTrack 初始化失败（state=" + state + "）：" + config);
        }
        Logger.info("AndroidOutputDevice: 开流 {}，设备缓冲 {} 字节（原生 {} 帧/缓冲）",
                config, Integer.valueOf(bufferBytes), nativeFramesPerBuffer());
        final OutputConfig opened = config;
        // 写完第一块若被拒，回报给设备：下一次挑档会避开这一档（见 markUnwritable）。
        return new AndroidOutputStream(track, opened, consumer,
                why -> markUnwritable(opened, why));
    }

    /**
     * DSD：恒为 {@link DsdSupport#NONE}。
     *
     * <p>{@code AudioFormat} 里没有公开的 DSD 编码，{@code AudioTrack} 也没有 DSD 通路
     * （DSD 在安卓上要么走 USB 直通的私有路径，要么由硬件解码）。
     * 这是平台限制，不是探测没写。
     */
    @Override
    public DsdSupport dsdSupport() {
        return DsdSupport.NONE;
    }

    /**
     * 声道数上限：取<strong>默认配置</strong>的声道数，不是矩阵最大值
     * （上游 {@code probe_max_channels} 就是这么取的）。
     *
     * <p>本设备只探了立体声（引擎链路就是立体声），所以这里如实返回 2，
     * 不虚报 5.1/7.1——「能开 8 声道」和「默认就是 8 声道」是两件事，
     * 而引擎还没接通环绕声的声道映射。
     */
    @Override
    public int maxChannels() {
        DeviceCapabilities caps = capabilities();
        if (caps.isEmpty()) {
            return 0;
        }
        return defaultConfig(1).channels();
    }

    /**
     * 缓冲帧数范围：{@code null}（平台不提供「范围」这项信息）。
     *
     * <p>对应上游 {@code SupportedBufferSize::Unknown} 分支——<strong>不能当成 0 处理</strong>。
     * {@code AudioTrack} 只给了一个<em>下限</em>（{@code getMinBufferSize}），
     * 上限它没说（更大的缓冲它照收），所以这里只能如实说「不知道」，
     * 而不是拿下限乘个系数编一个范围出来。
     */
    @Override
    public BufferRange bufferSizeRange() {
        return null;
    }

    @Override
    public String toString() {
        return "AndroidOutputDevice{" + name() + ", " + capabilities() + '}';
    }

    /**
     * 设备身份 = 设备名（平台原生档位）。
     *
     * <p>{@code list()} 会新建设备对象，没有相等性的话「默认设备是不是列表里的那一台」
     * 就永远问不出来（{@code contains} 恒为 false）。桌面端用混音器身份，这里没有对应物，
     * 用名字即可（本设备只有一台）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AndroidOutputDevice)) {
            return false;
        }
        return name().equals(((AndroidOutputDevice) o).name());
    }

    @Override
    public int hashCode() {
        return name().hashCode();
    }
}
