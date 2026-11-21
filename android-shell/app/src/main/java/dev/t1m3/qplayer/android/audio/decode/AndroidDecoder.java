package dev.t1m3.qplayer.android.audio.decode;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter;
import androidx.media3.exoplayer.source.BundledExtractorsAdapter;
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor;
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;

import dev.t1m3.qplayer.android.audio.AndroidAudioFormats;
import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.util.Logger;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 安卓宿主解码层：<strong>media3</strong> 的容器解析 + 解码器接缝 → <strong>平面 f32</strong>。
 *
 * <p>与桌面端 {@code DesktopDecoder} 是同一个接缝的另一份实现：结构、语义、踩坑理由
 * <strong>照搬上游</strong>，只把「怎么取包/怎么解码」换成 media3。下面每一条都是
 * 上游用测试钉住的硬约定，注释里保留理由。
 *
 * <h2>0. 为什么是 media3，而不是 {@code MediaExtractor} + {@code MediaCodec}</h2>
 *
 * <p>上一版直接用平台那对类，代价是<strong>「认不出容器」时平台不报错，而是退回
 * {@code audio/raw}</strong>：那时「解码」就是把压缩码流当 PCM 播，听感是雪花炸麦音，
 * 而且一个错都不报（实测：网易云的 {@code .flac} 直链被 {@@code MediaExtractor} 报成
 * {@code audio/raw}）。平台那份容器解析器的判定准则看不见、也改不动，只能在外面猜
 * （上一版为此写了「魔数嗅探 + 换通道重试 + 整份下到本地」三条兜底路，全是补丁）。
 *
 * <p>media3 的提取器把这件事做对了，而且是<strong>可预期的</strong>：
 *
 * <ul>
 *   <li>每个容器一个 {@code Extractor}，用<strong>内容嗅探</strong>（{@code sniff}）决定谁负责，
 *       谁也不认就抛 {@code UnrecognizedInputFormatException}——<strong>认不出就报错，
 *       绝不猜成裸 PCM</strong>。雪花音的根因在这里被去掉，而不是被绕过；</li>
 *   <li>裸 PCM 只有一种来源：{@code WavExtractor} 认出了 RIFF/WAVE。它给的是真的 PCM；</li>
 *   <li>格式、时长、编码器延迟/填充（gapless）都在 {@link Format} 里一次给全，
 *       比从 {@code MediaFormat} 里一个个 {@code getInteger} 可靠（键名甚至不是公开 API）；</li>
 *   <li>解码器选择走 {@link MediaCodecUtil}（避开已知坏实现、按格式支持度排序），
 *       {@code MediaCodec} 的封装走 media3 自己的 {@link SynchronousMediaCodecAdapter}
 *       （{@code MediaCodecRenderer} 用的就是它），设备兼容性知识不必我们自己攒；</li>
 *   <li>网络源走 media3 的 {@code DefaultDataSource}/{@code DefaultHttpDataSource}：
 *       Range、重定向、请求头、超时都是它的事，我们不再自己写 HTTP（旧版那份
 *       {@code HttpMediaDataSource} 正是「让 MediaExtractor 认不出容器」的元凶之一）。</li>
 * </ul>
 *
 * <p>与 ExoPlayer 的关系：<strong>只借它的提取器与解码层，不借它的播放器</strong>。
 * 播放是 player-core 的 {@code PlaybackEngine}（环形缓冲 + AudioTrack）的事，
 * 这里只负责「把音源变成 PCM」，所以是同步拉取（{@code decodeInto}），不是 ExoPlayer 那套
 * 推流的 Renderer/AudioSink。这一点决定了下面所有循环的形状。
 *
 * <h2>1. 输出是平面 f32</h2>
 *
 * <p>{@link #decodeInto} 把交错暂存拆进 {@link AudioBuf} 的各个声道（{@code channelWriter(c)}），
 * 最后 {@code setFrames(n)}；返回写入帧数，0 表示到结尾；返回前一直解码到填满容量或流结束，
 * 调用方不必自己循环。
 *
 * <h2>2. 先清 pending 再取新包（「3 秒的文件只放出 2.67 秒」的根因）</h2>
 *
 * <p>一包数据一旦从解码器取出来就无法退回，而它可能放不进调用方给的缓冲
 * （包长与缓冲大小无关）。<strong>没有暂存，多出的部分只能丢弃</strong>——上游正是这样丢了
 * 11% 的音频。所以交付顺序是硬约束：先把 {@code pending} 交付干净，再去解码新的一包
 * （见 {@link #decodeInto} 的两段式循环）。这里连解码器的输出缓冲也在拷进 pending 之后
 * <strong>立刻</strong>释放，不跨调用持有。
 *
 * <p>压缩包（解码器的<em>输入</em>）另有一个小队列 {@code samples}：提取器一次
 * {@code read()} 可能吐出不止一个样本（准备阶段尤其如此），丢一个就是丢一段音乐。
 *
 * <h2>3. seek 必须作废暂存，并返回实际落点</h2>
 *
 * <p>media3 的 {@link SeekMap} 给出「同步点」：{@code SeekPoint} 同时带字节位置与时间戳，
 * 定位就是<strong>在新位置重开数据源 + 让提取器从那个同步点继续</strong>（ExoPlayer 也是这么做的）。
 * 同步点通常<em>略早于</em>请求位置，所以返回实际落点而不是请求位置：否则界面进度条会在松手后
 * 自己跳回去，看起来像 bug。定位之后 {@code pending}、样本队列、EOS 标记、裁剪游标全部作废——
 * 留着它们，跳转后会先放出一小段旧内容，听起来像「跳过去又弹回来」。
 *
 * <p>流本身不可定位时（{@code SeekMap.isSeekable()} 为 false，例如长度未知的直播流）
 * <strong>不如实装作跳成功</strong>：记一条日志、位置原地不动并如实返回当前位置。
 *
 * <h2>4. 坏包跳过而不是中断</h2>
 *
 * <p>抓轨文件常有零星坏帧，为此停掉整首歌不可接受：解码器报可恢复错误时
 * （{@code CodecException.isRecoverable()}）{@code flush} + 回到上一个同步点继续，
 * 瞬时错误（{@code isTransient()}）原地重试；连续出错超过上限才放弃（见 {@link #MAX_CODEC_RECOVERIES}）。
 * 空包/配置包（{@code BUFFER_FLAG_CODEC_CONFIG}）同样跳过。
 *
 * <h2>5. 无缝播放默认开启，且<strong>只裁一次</strong></h2>
 *
 * <p>编码器延迟与填充取自 media3 的 {@link Format#encoderDelay}/{@link Format#encoderPadding}
 * （单位帧；media3 从 LAME/Xing、iTunSMPB、Opus pre-skip 这些地方读出来，
 * 认不出就是 {@code NO_VALUE}，按「不知道」记账，见 {@link AndroidGapless#note()}）。
 *
 * <p><strong>绝不把这两个值交给 {@code MediaCodec}</strong>（configure 用的 {@code MediaFormat}
 * 里不写它们）：平台解码器<em>也</em>会照它裁，两边都裁就是<strong>双重裁剪</strong>
 * （开头少一小段音乐，正是 gapless 最不该出错的地方），而且「平台到底裁没裁」在 Java 侧看不出来，
 * 账目会彻底对不上。所以由我们自己裁：一次且仅一次，且 {@code durationSecs()} / {@code seek}
 * 用的是同一本账。（media3 自己的播放链路也是这么分工的：解码器不裁，由消费端按帧数裁。）
 *
 * <h2>6. 输出格式：优先向解码器要 float PCM；裸 PCM 不过解码器</h2>
 *
 * <p>configure 时带 {@code KEY_PCM_ENCODING = ENCODING_PCM_FLOAT}（API 21+）：拿到就把
 * f32 位模式直通搬进暂存，零转换。解码器不认这个键时，它报什么我们就解什么——最常见是 16 位整数，
 * 而 <strong>16 位整数 → f32 是精确的</strong>（除以 2^15 只改指数，ADR-003 §3.2），
 * 所以这条退化路径不损失精度。解不出来的编码（不认识的值）直接报错，不吐噪音。
 *
 * <p>{@code audio/raw}（WAV 这类未压缩音源）<strong>根本不建解码器</strong>：提取器给出的样本
 * 就是 PCM 本身，直接用它的 {@code pcmEncoding} 解——少一次拷贝，也少一个「设备上没有 raw
 * 解码器」的失败点（ExoPlayer 对这类格式同样走旁路，见 {@code MediaCodecAudioRenderer.shouldUseBypass}）。
 *
 * <h2>7. 网络源</h2>
 *
 * <p>{@code http(s)} 走 media3 的 {@code DefaultHttpDataSource}（Range 请求 + 请求头 + 重定向）：
 * automix 要读尾部（对拍看末尾、判调性读尾段），没有 Range 就只能先下完整首歌，
 * AI 模式与声波重合在手机上会直接失效。本地路径走 {@code FileDataSource}，
 * {@code content://} 走 {@code ContentDataSource}（两者都由 {@code DefaultDataSource} 分派）。
 *
 * <p>与上游的差异：音源句柄是 {@code String}（本地路径 / {@code content://} / http(s) URL，
 * 可带请求头），错误是异常而不是 {@code Result}。
 */
@SuppressLint("UnsafeOptInUsageError") // media3 的提取/解码层都标了 @UnstableApi，这里是有意使用
public final class AndroidDecoder implements Decoder {

    /**
     * 暂存（pending）预分配容量（帧），与桌面端同值。
     *
     * <p>容量必须覆盖<strong>任何单包</strong>：上游原先只给 16384 并在放不下时丢弃多出的部分
     * （注释还写着「不影响听感」），实测后果是 11% 的音频凭空消失——3 秒的文件只放出 2.67 秒。
     * 解码器的输出缓冲一般远小于这个值，真超了就扩容一次（见 {@link #stageFrames}）。
     */
    private static final int SCRATCH_FRAMES = 65536;

    /**
     * 交给解码器的 {@code KEY_MAX_INPUT_SIZE}（字节）下限。
     *
     * <p>{@code MediaCodec} 的输入缓冲大小由解码器决定，而一个压缩样本可能比它大
     * （FLAC 的一帧可以到几十 KB；无损 WAV 的一块更大）。media3 的提取器会在
     * {@code Format.maxInputSize} 里给出它见过/估计的最大样本，我们取其与这个下限的较大者。
     * 真有样本还是放不下时按 media3 的做法丢掉该样本并 flush 重来（见 {@link #feedInput()}）——
     * 那总比把半帧塞进解码器（听起来是爆音）强。
     */
    private static final int REQUESTED_MAX_INPUT_SIZE = 256 * 1024;

    /**
     * 等待解码输出的轮询间隔（毫秒）与上限。
     *
     * <p>media3 的同步 {@code MediaCodec} 封装（{@link SynchronousMediaCodecAdapter}）是
     * <strong>非阻塞</strong>的（内部 timeout 恒为 0，ExoPlayer 由一个消息循环反复调它），
     * 所以「等输出」这件事得我们自己在这里等：小睡一下再取，不空转烧 CPU。
     * 上限存在的意义是把「解码器真卡住了」和「只是慢」分开——真卡住就报错，别让它永远转下去。
     */
    private static final long POLL_SLEEP_MS = 2L;
    private static final int MAX_OUTPUT_POLLS = 20_000;      // ≈40 秒没吐出任何输出 → 报错

    /**
     * 送完 EOS 之后还给解码器多久把尾巴吐完（轮询次数，≈1 秒）。
     *
     * <p>有些设备的解码器<strong>不回报 {@code BUFFER_FLAG_END_OF_STREAM}</strong>
     * （media3 为此在 {@code MediaCodecRenderer} 里留了一串 {@code codecNeedsEos*} 补丁）。
     * 一直等下去会把「正常收尾」变成「解码失败」，所以超过这个时间就按结束处理，
     * 并在日志里如实写明是「没等到 EOS 标记」而不是「播完了」。
     */
    private static final int EOS_GRACE_POLLS = 500;

    /** 连续空包上限：提取器/解码器一直不给数据时，不能无限转下去。 */
    private static final int MAX_EMPTY_PACKETS = 8192;

    /** 可恢复错误的次数上限：坏包跳过，但一路都在坏就该放弃（否则等于假装在播）。 */
    private static final int MAX_CODEC_RECOVERIES = 8;

    /**
     * 单个「编码器延迟/填充」的可信上限（秒）。
     *
     * <p>真实的编码器延迟与填充都很小：LAME 延迟 ≈ 1105 帧（25 ms）、iTunSMPB 填充同一量级，
     * Opus 的 pre-skip 也就几百个采样。超过这个数的值几乎一定是<strong>头里的字段被写坏了</strong>
     * （或者单位根本不是帧），而它一旦被当成「要裁掉的帧数」，副作用是<strong>整首歌被截掉一大段</strong>——
     * 2026-09-23 安卓实机报告的症状正是「过渡完成后没放几秒就跳到下一首」。
     *
     * <p>真正的判据在 {@link AndroidCodecs#trimVerdict}（那边能单独单测），这里只是引用它。
     */
    /**
     * 连续重开数据源的次数上限（提取器要求跳转时会发生）。
     *
     * <p>计的是<strong>连续没有进展</strong>的次数：只要抽出一个样本就清零。
     * 不这么算的话，用户正常拖动 64 次之后会被自己的拖动顶死。
     */
    private static final int MAX_REOPEN_STREAK = 64;

    private static final Map<String, String> NO_HEADERS = Collections.emptyMap();

    // ---- 打开时定下来的东西 ----

    private final String label;
    private final Uri uri;
    private final DataSource.Factory dataSourceFactory;
    private final ProgressiveMediaExtractor extractor;
    private final Tracks tracks = new Tracks();
    private final PositionHolder positionHolder = new PositionHolder();

    /** 声道数（轨道事实，{@link #buildTrack()} 里定下来后不再变）。 */
    private int channels;

    private DataSource dataSource;
    private boolean dataSourceOpen;
    /** 连续重开（没有抽出任何样本）的次数，见 {@link #MAX_REOPEN_STREAK}。 */
    private int reopenStreak;

    private SeekMap seekMap;
    private Format format;
    private TrackSpec spec;
    private AndroidGapless gapless;

    /** 未压缩音源（{@code audio/raw}）：样本本身就是 PCM，没有解码器。 */
    private boolean bypass;
    /** 旁路时的采样编码（来自提取器的 {@code Format.pcmEncoding}）。 */
    private int bypassEncoding = AndroidAudioFormats.ENCODING_PCM_16BIT;

    private MediaCodecAdapter codec;
    /** 解码输出侧的实际 PCM 编码（由 {@code INFO_OUTPUT_FORMAT_CHANGED} 给出，拿不到就是 16 位）。 */
    private int pcmEncoding = AndroidAudioFormats.ENCODING_PCM_16BIT;

    /** 容器报的总时长（微秒）；{@code C.TIME_UNSET} = 没报。用于「时长与时间戳自洽」的检查。 */
    private long containerDurationUs = C.TIME_UNSET;

    /** 时长与时间戳不符时只警告一次（否则每块都刷日志）。 */
    private boolean warnedDurationMismatch;

    /**
     * 字节流 → 整帧 → f32 的切分器：跨包接续「半个采样」。
     *
     * <p>丢掉半个采样会让下一包从声道中间接上（左右错位 = 杂音），所以必须接续；
     * 编码在运行中变化（{@code INFO_OUTPUT_FORMAT_CHANGED}）时按新编码重建。
     */
    private PcmFramer framer;

    // ---- 流转状态（单线程：由 Deck 的渲染线程使用） ----

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    /** 已抽出、还没喂进解码器的压缩样本（FIFO）。 */
    private final ArrayDeque<Sample> samples = new ArrayDeque<Sample>();

    /** 正在喂的那个样本（放不进一个输入缓冲时会分几次喂完，见 {@link #feedInput()}）。 */
    private Sample currentSample;
    private int currentSampleOffset;

    /** 已解码但尚未交付的交错采样，容量 = {@code pendingCapacityFrames} × 声道数。 */
    private float[] pending;
    private int pendingCapacityFrames;
    /** pending 里已交付的帧数。 */
    private int pendingOffset;
    /** pending 里有效的帧数。 */
    private int pendingFrames;

    /** 首部还要丢掉的帧数（编码器延迟；seek 之后重算）。 */
    private long skipFrames;
    /** 已交付的帧数（裁剪后、从音乐第一帧算起）。 */
    private long framesDecoded;

    private boolean extractEnded;
    private boolean inputEos;
    private boolean outputEos;
    private boolean drained;
    private boolean closed;
    private boolean warnedEosMissing;
    /** 解码器是否已经把实际输出格式报给我们了（没报就在第一个输出缓冲前自己读一次）。 */
    private boolean outputFormatSeen;
    private int recoveries;
    /** 最近一次交给解码器的样本时间戳（微秒），坏包恢复时用来找回同步点。 */
    private long lastSampleUs = -1L;

    private AndroidDecoder(String label, Uri uri, DataSource.Factory dataSourceFactory) {
        this.label = label;
        this.uri = uri;
        this.dataSourceFactory = dataSourceFactory;
        this.extractor = new BundledExtractorsAdapter(
                // 打开「按码率估定位点」：无 Xing/LAME 头的 CBR MP3（以及 ADTS/AMR）本来会报
                // 「不可定位」，那样界面上拖进度条就是拖不动。代价是这类文件的落点是**估算**的
                // （码率×时间 ÷ 帧长），真有无损需求的话用户应该用带 Xing 头的文件。
                // media3 自己的 DefaultMediaSourceFactory 默认不开这个开关，这里是有意开。
                new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true));
    }

    /**
     * 安装安卓端的解码器工厂。
     *
     * <p>宿主的启动路径调用一次（{@code QPlayerApplication.onCreate}，
     * 与凭证保护那些进程级安装挨着写）。player-core 只认 {@link Decoder#install}，
     * 不认任何平台解码库。
     *
     * @param context 需要它来打开 {@code content://} 音源；内部只留 application context
     */
    public static void install(Context context) {
        final Context appContext = context == null ? null : context.getApplicationContext();
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return AndroidDecoder.open(appContext, source, headers);
            }
        });
    }

    /**
     * 打开一个音源（本地路径 / {@code content://} / http(s) URL，可带提供商要求的请求头）。
     *
     * <p>这一步是<strong>同步</strong>的，而且必须在返回前拿到轨道事实（{@link TrackSpec}）：
     * 引擎要用源采样率去选输出流（{@code PlaybackEngine.probeSourceRate}），
     * 而那件事没法异步化。代价是打开时要读几十 KB（嗅探容器 + 解析头部），
     * 对本地文件与带 Range 的网络源都是一次 Range 请求的事。
     */
    public static AndroidDecoder open(Context context, String source, Map<String, String> headers) {
        if (source == null || source.isEmpty()) {
            throw AudioException.decode("音源为空");
        }
        Map<String, String> h = headers == null ? NO_HEADERS : headers;
        if (context == null && source.startsWith("content://")) {
            throw AudioException.config(
                    "打开 content:// 音源需要 Context：宿主应以 Application Context 安装解码器");
        }
        final Context appContext = context == null ? null : context.getApplicationContext();
        AndroidDecoder decoder = new AndroidDecoder(redact(source), toUri(source),
                dataSourceFactory(appContext, h));
        decoder.start();
        return decoder;
    }

    /**
     * 音源 → media3 的 {@code Uri}：带 scheme 的原样，绝对路径补 {@code file://}（顺带把
     * 空格/中文这类字符转义掉），相对路径保持原样交给 {@code FileDataSource} 按当前目录解析。
     */
    private static Uri toUri(String source) {
        if (source.indexOf("://") >= 0) {
            return Uri.parse(source);
        }
        File file = new File(source);
        return file.isAbsolute() ? Uri.fromFile(file) : Uri.parse(source);
    }

    /**
     * 数据源工厂：http(s) 走 media3 的 HTTP 数据源（请求头/超时/重定向），
     * 其余（本地文件、{@code content://}、{@code asset://}）由 {@code DefaultDataSource} 分派。
     */
    private static DataSource.Factory dataSourceFactory(Context context, Map<String, String> headers) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("QPlayer/Android")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                // 直链经常先从 https 跳一次 http（或反过来）：不打开这个开关，那类音源直接打不开
                .setAllowCrossProtocolRedirects(true);
        if (!headers.isEmpty()) {
            http.setDefaultRequestProperties(headers);
        }
        return new DefaultDataSource.Factory(context, http);
    }

    // ---- 打开流程 ----

    /** 建数据源 → 嗅探容器 → 读齐轨道事实 → 建解码器。 */
    private void start() {
        dataSource = dataSourceFactory.createDataSource();
        openAt(0L, C.TIME_UNSET);   // 第一次 init 就是「嗅探容器」，认不出会在这里抛
        prepare();
        buildTrack();
        Logger.info("AndroidDecoder: {} codec={} rate={} ch={} lossless={} frames={} 容器时长={}us {}",
                bypass ? "（裸 PCM 直通）" : "", spec.codec, Integer.valueOf(spec.sampleRate),
                Integer.valueOf(channels), Boolean.valueOf(spec.lossless), spec.totalFrames,
                Long.valueOf(containerDurationUs), gapless.note());
    }

    /**
     * 在新位置打开数据源并把输入交给提取器。
     *
     * <p>{@code BundledExtractorsAdapter.init} 的语义要记住两件事：
     *
     * <ol>
     *   <li>第一次调用做<strong>嗅探</strong>（谁都不认 → {@code UnrecognizedInputFormatException}），
     *       之后每次调用只是把「从哪儿读」换成新的位置；</li>
     *   <li>{@code seekTimeUs} 不是 {@code C.TIME_UNSET} 时要在 init 之后调
     *       {@code extractor.seek(position, timeUs)} —— 提取器内部记着「我停在哪个同步点上」。</li>
     * </ol>
     *
     * <p>返回值 {@code length} 是「从 position 起还能读多少字节」，而提取器要的是
     * <strong>绝对结束位置</strong>（{@code DefaultExtractorInput} 就是这么用的，ExoPlayer 也这么算）。
     */
    private void openAt(long position, long seekTimeUs) {
        if (++reopenStreak > MAX_REOPEN_STREAK) {
            throw AudioException.decode("连续重开音源超过 " + MAX_REOPEN_STREAK
                    + " 次都没有抽出一个样本：" + label);
        }
        closeDataSource();
        DataSpec dataSpec = new DataSpec.Builder().setUri(uri).setPosition(position).build();
        long length;
        try {
            length = dataSource.open(dataSpec);
        } catch (IOException e) {
            throw AudioException.io("打开音源失败（" + label + "）：" + e, e);
        }
        dataSourceOpen = true;
        long endPosition = length == C.LENGTH_UNSET ? C.LENGTH_UNSET : length + position;
        try {
            extractor.init(dataSource, uri, dataSource.getResponseHeaders(), position, endPosition, tracks);
        } catch (UnrecognizedInputFormatException e) {
            // **雪花音的根因就在这里被挡住**：认不出容器就如实报错，绝不退回「按裸 PCM 播」
            throw AudioException.unsupportedFormat(
                    "认不出这个音源的容器（" + label + "）：media3 的提取器都判不出来。"
                            + "宁可这一首不播，也不把压缩码流当 PCM 放成噪音。原始错误：" + e.getMessage());
        } catch (IOException e) {
            throw AudioException.io("解析音源失败（" + label + "）：" + e, e);
        }
        if (seekTimeUs != C.TIME_UNSET) {
            extractor.seek(position, seekTimeUs);
        }
    }

    private void closeDataSource() {
        if (dataSource == null) {
            return;
        }
        if (dataSourceOpen) {
            DataSourceUtil.closeQuietly(dataSource);
            dataSourceOpen = false;
        }
    }

    /** 读到「轨道列表、时长（SeekMap）、音频格式」都齐了为止。 */
    private void prepare() {
        while (!tracks.prepared()) {
            if (!readOnce()) {
                break;      // 流提前结束：下面按「没有音频轨」如实报错
            }
        }
        Samples audio = tracks.audio();
        if (audio == null || audio.announced == null) {
            throw AudioException.unsupportedFormat(
                    "音源里没有音频轨道（" + label + "）：提取器认出了容器，但里面没有能播的音频");
        }
        format = audio.announced;
        seekMap = tracks.seekMap;
    }

    /**
     * 抽一次包。返回 {@code false} 表示流到头了。
     *
     * <p>{@code RESULT_SEEK} 是提取器在说「请从 {@code positionHolder.position} 重开」——
     * ExoPlayer 的 {@code ProgressiveMediaPeriod} 也是这么处理的（关掉、在新位置重开、继续读）。
     */
    private boolean readOnce() {
        int result;
        try {
            result = extractor.read(positionHolder);
        } catch (IOException e) {
            throw AudioException.decode("读取码流失败（" + label + "）：" + e);
        }
        if (result == Extractor.RESULT_END_OF_INPUT) {
            extractEnded = true;
            return false;
        }
        if (result == Extractor.RESULT_SEEK) {
            openAt(positionHolder.position, C.TIME_UNSET);
        }
        return true;
    }

    // ---- 轨道事实 → TrackSpec + 解码器 ----

    private void buildTrack() {
        String mime = format.sampleMimeType;
        if (mime == null) {
            throw AudioException.unsupportedFormat("轨道没有编码信息：" + label);
        }
        int rate = format.sampleRate;
        int channelCount = format.channelCount;
        if (rate < 1 || channelCount < 1) {
            throw AudioException.unsupportedFormat("轨道缺少采样率/声道数（rate=" + rate
                    + ", channels=" + channelCount + "）：" + label);
        }
        channels = channelCount;

        // 无损判定**只看编码名**（见 AndroidCodecs.isLosslessMime 的注释：
        // 拿位深佐证会把 AAC/MP3 标成无损，并一路传到 Hi-Res 标签）
        boolean lossless = AndroidCodecs.isLosslessMime(mime);
        // 源位深：media3 的 FLAC / WAV 提取器会把**源**位深放进 Format.pcmEncoding
        // （Util.getPcmEncoding(bitsPerSample)），这比平台的 bits-per-sample 键可靠
        // （那个键名甚至不是公开 API）。数值就是 AudioFormat 的编码常量，同一张表。
        Integer encoding = format.pcmEncoding == Format.NO_VALUE ? null : Integer.valueOf(format.pcmEncoding);
        Integer bits = AndroidCodecs.bitsPerSample(lossless, null, encoding);

        long durationUs = seekMap == null ? C.TIME_UNSET : seekMap.getDurationUs();
        containerDurationUs = durationUs;
        Long containerFrames = AndroidCodecs.framesFromDurationMicros(
                durationUs == C.TIME_UNSET ? 0L : durationUs, rate);
        Integer delayFrames = intOrNull(format.encoderDelay);
        Integer paddingFrames = intOrNull(format.encoderPadding);
        // 头里的裁剪值可能是坏的：坏了就当「没给」（不裁），**绝不**拿它去算「可播放帧数」——
        // 那个值一旦偏小，解码器会在歌还没放完时宣告结束（判据见 AndroidCodecs.trimVerdict）
        String trimVerdict = AndroidCodecs.trimVerdict(rate, delayFrames, paddingFrames, containerFrames);
        if (trimVerdict != null) {
            Logger.warn("AndroidDecoder: 编码器延迟/填充不可信（delay={} padding={} 帧，"
                            + "容器 {} 帧）：不裁剪（{}）· {}",
                    delayFrames, paddingFrames, containerFrames, trimVerdict, label);
            delayFrames = null;
            paddingFrames = null;
        }
        gapless = AndroidGapless.of(delayFrames, paddingFrames, containerFrames);

        // 可播放帧数才是时长：容器时长含编码器延迟与填充，报给界面的应该是音乐本身的长度
        Long totalFrames = gapless.playableFrames() != null
                ? gapless.playableFrames() : containerFrames;
        spec = new TrackSpec(AndroidCodecs.codecName(mime), rate, ChannelLayout.of(channelCount),
                bits, totalFrames, lossless);

        pendingCapacityFrames = SCRATCH_FRAMES;
        pending = new float[SCRATCH_FRAMES * channelCount];
        skipFrames = gapless.delayFrames();

        if (MimeTypes.AUDIO_RAW.equals(mime)) {
            // 未压缩音源：样本就是 PCM，没有解码器可建（也不该建）
            bypass = true;
            bypassEncoding = encoding == null ? AndroidAudioFormats.ENCODING_PCM_16BIT : encoding.intValue();
            if (!AndroidAudioFormats.isDecodable(bypassEncoding)) {
                throw AudioException.unsupportedFormat("裸 PCM 的采样编码不认识：" + bypassEncoding
                        + "（" + label + "）");
            }
            Logger.info("AndroidDecoder: 裸 PCM 直通（{} 位），不过解码器", bits);
            return;
        }

        codec = createCodec(format);
        // 首包之前先把输入喂上：有些解码器要读到数据才吐 CSD/输出格式
        lastSampleUs = -1L;
    }

    /**
     * 建解码器：媒体3 选解码器（{@link MediaCodecUtil}）+ media3 的同步封装（{@link SynchronousMediaCodecAdapter}）。
     *
     * <p>候选列表按格式支持度排过序，<strong>逐个试</strong>：某台设备上排第一的解码器 configure 失败
     * （损坏的固件实现、被别的应用占满的实例数……）时，第二个往往能用——
     * {@code MediaCodec.createDecoderByType} 只能拿到一个，失败就没得退了。
     */
    private static MediaCodecAdapter createCodec(Format format) {
        List<MediaCodecInfo> candidates;
        try {
            candidates = MediaCodecUtil.getDecoderInfosSoftMatch(
                    MediaCodecSelector.DEFAULT, format, /* requiresSecureDecoder= */ false,
                    /* requiresTunnelingDecoder= */ false);
        } catch (MediaCodecUtil.DecoderQueryException e) {
            throw AudioException.unsupportedFormat("查询解码器失败（" + format.sampleMimeType + "）：" + e);
        }
        candidates = MediaCodecUtil.getDecoderInfosSortedByFormatSupport(candidates, format);
        if (candidates.isEmpty()) {
            throw AudioException.unsupportedFormat(
                    "设备上没有能解 " + format.sampleMimeType + " 的解码器");
        }
        RuntimeException lastFailure = null;
        IOException lastIoFailure = null;
        for (MediaCodecInfo info : candidates) {
            MediaFormat mediaFormat = buildMediaFormat(format, info);
            try {
                return new SynchronousMediaCodecAdapter.Factory().createAdapter(
                        MediaCodecAdapter.Configuration.createForAudioDecoding(
                                info, mediaFormat, format, /* crypto= */ null));
            } catch (IOException e) {
                lastIoFailure = e;
                Logger.warn("AndroidDecoder: 解码器 {} 建不起来（{}），试下一个",
                        info.name, e.toString());
            } catch (RuntimeException e) {
                lastFailure = e;
                Logger.warn("AndroidDecoder: 解码器 {} configure/start 失败（{}），试下一个",
                        info.name, e.toString());
            }
        }
        throw AudioException.decode("候选解码器（" + candidates.size() + " 个）全部初始化失败："
                + (lastFailure != null ? lastFailure : String.valueOf(lastIoFailure)));
    }

    /**
     * 给 {@code MediaCodec} 的格式：照抄 media3 自己的音频渲染器（{@code MediaCodecAudioRenderer.getMediaFormat}），
     * 少一条就会在某些设备上退化甚至打不开。
     *
     * <p>两条刻意的不一样：
     *
     * <ul>
     *   <li><strong>不写</strong> {@code KEY_ENCODER_DELAY}/{@code KEY_ENCODER_PADDING}：
     *       平台会照它裁，我们自己也要裁，写进去就是双重裁剪（见类注释第 5 条）；</li>
     *   <li>要 float PCM：我们的整条链路都是 f32，拿到就是零转换直通（见类注释第 6 条）。</li>
     * </ul>
     */
    private static MediaFormat buildMediaFormat(Format format, MediaCodecInfo info) {
        MediaFormat mediaFormat = new MediaFormat();
        // 用解码器自己的 MIME（别名/兼容名与容器报的不一定一样），media3 也是这么配的
        mediaFormat.setString(MediaFormat.KEY_MIME, info.codecMimeType);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channelCount);
        mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate);
        // CSD（FLAC 的 STREAMINFO、AAC 的 AudioSpecificConfig……）：没有它解码器起不来
        MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);
        int maxInput = format.maxInputSize == Format.NO_VALUE ? 0 : format.maxInputSize;
        MediaFormatUtil.maybeSetInteger(mediaFormat, MediaFormat.KEY_MAX_INPUT_SIZE,
                Math.max(REQUESTED_MAX_INPUT_SIZE, maxInput));
        if (Build.VERSION.SDK_INT >= 23) {
            // 实时优先级：音频卡顿有一半是解码线程被别的活挤掉造成的
            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        MediaFormatUtil.maybeSetInteger(mediaFormat, MediaFormat.KEY_PCM_ENCODING,
                AndroidAudioFormats.ENCODING_PCM_FLOAT);
        if (Build.VERSION.SDK_INT >= 32) {
            // 老版本解码器可能只给 2 声道输出；32 起才认这个键（media3 同样只在这里设）
            MediaFormatUtil.maybeSetInteger(mediaFormat, MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 99);
        }
        return mediaFormat;
    }

    private static Integer intOrNull(int value) {
        return value == Format.NO_VALUE ? null : Integer.valueOf(value);
    }

    /** 直链常带 token：错误信息与日志里不回显 query 部分。 */
    private static String redact(String source) {
        int q = source.indexOf('?');
        return q < 0 ? source : source.substring(0, q) + "?…";
    }

    // ---- 提取器 → 我们的接口 ----

    /**
     * 提取器吐出来的轨道与时长（media3 的 {@code ExtractorOutput}）。
     *
     * <p>只接第一条音频轨；封面（JPEG）、歌词（文本）那些轨照样要返回一个 {@code TrackOutput}
     * （提取器会往上写），但内容直接丢掉——它们不是要播的东西。
     */
    private final class Tracks implements ExtractorOutput {
        private final Map<Integer, TrackOutput> outputs = new HashMap<Integer, TrackOutput>();
        private final Discarding discarding = new Discarding();

        private Samples audio;
        private SeekMap seekMap;
        private boolean tracksEnded;

        @Override
        public TrackOutput track(int id, int type) {
            TrackOutput existing = outputs.get(Integer.valueOf(id));
            if (existing != null) {
                return existing;
            }
            TrackOutput output;
            if (type == C.TRACK_TYPE_AUDIO && audio == null) {
                audio = new Samples();
                output = audio;
            } else {
                output = discarding;
            }
            outputs.put(Integer.valueOf(id), output);
            return output;
        }

        @Override
        public void endTracks() {
            tracksEnded = true;
        }

        @Override
        public void seekMap(SeekMap map) {
            seekMap = map;
        }

        private Samples audio() {
            return audio;
        }

        private boolean prepared() {
            return seekMap != null && tracksEnded && audio != null && audio.announced != null;
        }
    }

    /** 不是音频的轨（封面/歌词）：字节照读（不能卡住提取器），但一个都不留。 */
    private static final class Discarding implements TrackOutput {
        private final byte[] scratch = new byte[4096];

        @Override
        public void format(Format format) {
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            int total = 0;
            while (total < length) {
                int read = input.read(scratch, 0, Math.min(scratch.length, length - total));
                if (read == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) {
                        return total == 0 ? C.RESULT_END_OF_INPUT : total;
                    }
                    throw new EOFException();
                }
                total += read;
            }
            return total;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            data.skipBytes(length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
        }
    }

    /**
     * 音频轨的样本收集（media3 的 {@code TrackOutput}）。
     *
     * <p>一次 {@code sampleData} 只是往缓冲里追加；真正的样本在 {@code sampleMetadata} 时
     * 才知道边界：{@code size} 是本样本的字节数，{@code offset} 是<strong>本样本之后</strong>
     * 已经写进来的字节数（属于下一个样本，例如加密信息/补充数据写在主数据后面）。
     * 所以本样本在 {@code [总长 − offset − size, 总长 − offset)}，而尾巴要搬到前面留着——
     * 这套算术与 media3 自己的 {@code SampleQueue} 一致（照抄是为了不在这里自创语义）。
     *
     * <p>每个样本复制成一个独立的小数组进队列，而不是复用一块大缓冲：解码是<strong>按需</strong>拉取的
     * （解码器一次要一个样本），队列深度通常就是 1；每秒几十个几百字节的数组分配，
     * 比自造一个分配器简单，也不会在音频回调里分配（这一段跑在渲染线程上）。
     */
    private final class Samples implements TrackOutput {
        /** 提取器报的格式（{@link #prepare()} 就是等它出现）。中途换格式会在这里被拦下。 */
        private Format announced;
        private byte[] data = new byte[8 * 1024];
        private int written;

        @Override
        public void format(Format newFormat) {
            if (announced == null) {
                announced = newFormat;
                return;
            }
            // 流中途换格式：采样率/声道数一换，AudioBuf 与下游的账就全对不上了，如实报错而不是硬放
            if (!eq(announced.sampleMimeType, newFormat.sampleMimeType)
                    || (newFormat.sampleRate != Format.NO_VALUE
                        && newFormat.sampleRate != announced.sampleRate)
                    || (newFormat.channelCount != Format.NO_VALUE
                        && newFormat.channelCount != announced.channelCount)) {
                throw AudioException.unsupportedFormat("流中途换了音频格式（"
                        + announced.sampleMimeType + " → " + newFormat.sampleMimeType + "）");
            }
            announced = newFormat;
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            ensure(written + length);
            int read = input.read(data, written, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw new EOFException();
            }
            written += read;
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray buffer, int length, int sampleDataPart) {
            ensure(written + length);
            buffer.readBytes(data, written, length);
            written += length;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            if (cryptoData != null) {
                // 加密音轨我们不解密，也不该假装解出来
                throw AudioException.unsupportedFormat("音轨是加密的（DRM），本播放器不支持");
            }
            int start = written - offset - size;
            if (size < 0 || start < 0) {
                // 数据对不上（提取器给了自相矛盾的长度）：丢掉这一包并记一笔，不猜、不硬搬
                Logger.warn("AndroidDecoder: 样本长度自相矛盾（size={}, offset={}, written={}），丢弃",
                        Integer.valueOf(size), Integer.valueOf(offset), Integer.valueOf(written));
                written = 0;
                return;
            }
            byte[] copy = new byte[size];
            System.arraycopy(data, start, copy, 0, size);
            if (offset > 0) {
                // 属于下一个样本的尾巴搬到前面：它就是下一次 sampleData 的起点
                System.arraycopy(data, written - offset, data, 0, offset);
            }
            written = offset;
            samples.add(new Sample(copy, timeUs));
            reopenStreak = 0;   // 抽出样本就是进展（见 MAX_REOPEN_STREAK）
        }

        private void ensure(int capacity) {
            if (data.length >= capacity) {
                return;
            }
            int size = Math.max(capacity, data.length * 2);
            byte[] bigger = new byte[size];
            System.arraycopy(data, 0, bigger, 0, written);
            data = bigger;
        }
    }

    /** 一个压缩样本：字节 + 它自己的时间戳（喂给解码器时要用同一个）。 */
    private static final class Sample {
        final byte[] data;
        final long timeUs;

        Sample(byte[] data, long timeUs) {
            this.data = data;
            this.timeUs = timeUs;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- Decoder 接口 ----

    @Override
    public TrackSpec spec() {
        return spec;
    }

    @Override
    public long framesDecoded() {
        return framesDecoded;
    }

    @Override
    public boolean isDrained() {
        return drained;
    }

    /**
     * 跳到指定位置（秒），返回<strong>实际</strong>落点。
     *
     * <p>容器只能停在同步点上（{@link SeekMap} 给的就是这些点）：实际落点通常略早于请求位置，
     * 这个差别必须如实回报，否则进度条会在松手后自己跳回去。
     *
     * <p>定位之后所有暂存与游标全部作废：{@code pending} 与样本队列里装的是定位<em>之前</em>
     * 解出的数据，不丢掉的话跳转后会先放出一小段旧内容（「跳过去又弹回来」）。
     * 裁剪游标也要按实际落点重算：跳到 0 时落点仍在编码器延迟区间里，那段延迟必须重新丢
     * （见 {@link AndroidGapless#skipAfterSeek}）。
     */
    @Override
    public double seek(double secs) {
        if (closed) {
            throw new IllegalStateException("解码器已关闭，不能再定位");
        }
        if (Double.isNaN(secs) || Double.isInfinite(secs) || secs < 0.0) {
            throw AudioException.decode("无效的定位目标：" + secs);
        }
        double rate = Math.max(1, spec.sampleRate);
        Long total = spec.totalFrames;
        double targetFrames = secs * rate;
        if (total != null && targetFrames > total.longValue()) {
            targetFrames = total.longValue();
        }
        long wantUs = (long) (targetFrames * 1_000_000.0 / rate);

        if (seekMap == null || !seekMap.isSeekable()) {
            // 不可定位的源（长度未知的直播流）：假装跳成功是最坏的做法——界面会走到 2:00
            // 而耳朵还在 0:00。这里如实不动，并让返回值把界面拉回真实位置。
            Logger.warn("AndroidDecoder: 这一路音源不可定位（长度未知或容器没给定位点），跳过这次 seek：{}",
                    label);
            return rate < 1 ? 0.0 : framesDecoded / rate;
        }

        SeekPoint point = seekMap.getSeekPoints(wantUs).first;
        openAt(point.position, point.timeUs);
        reopenStreak = 0;   // 定位是一次合法的重开，不该记在「转不停」的账上
        if (codec != null) {
            try {
                codec.flush();
            } catch (RuntimeException e) {
                throw AudioException.decode("定位后重置解码器失败：" + e);
            }
        }

        // 定位之后一切在途状态作废（见方法注释）
        samples.clear();
        currentSample = null;
        currentSampleOffset = 0;
        pendingFrames = 0;
        pendingOffset = 0;
        framer = null;      // 半个采样的残留属于跳转前的位置，接不上
        warnedDurationMismatch = false;
        extractEnded = false;
        inputEos = false;
        outputEos = false;
        drained = false;
        recoveries = 0;

        long landedFrames = point.timeUs * spec.sampleRate / 1_000_000L;
        skipFrames = gapless.skipAfterSeek(landedFrames);
        framesDecoded = gapless.framesDecodedAtSeek(landedFrames);
        lastSampleUs = point.timeUs;
        return framesDecoded / rate;
    }

    /**
     * 解码填满 {@code out}，返回写入的帧数。返回 0 表示已到结尾。
     *
     * <p>会一直解码到填满容量或流结束，所以调用方不必自己循环。
     */
    @Override
    public int decodeInto(AudioBuf out) {
        if (closed || drained) {
            return 0;
        }
        if (out.channels() != channels) {
            throw new IllegalArgumentException(
                    "缓冲声道数与音轨不符：" + out.channels() + " ≠ " + channels);
        }
        int capacity = out.capacityFrames();
        if (capacity <= 0) {
            // 容量为 0 是调用方的问题，不该污染流状态（上游在这里会把流标成 drained）
            return 0;
        }

        int written = 0;
        while (true) {
            // 1) 先把上次没交付完的部分交付掉：**先清 pending，再解码新包**，
            //    这个顺序是正确性的关键（见类注释第 2 条）
            if (pendingOffset < pendingFrames) {
                int avail = pendingFrames - pendingOffset;
                int n = Math.min(avail, capacity - written);
                for (int c = 0; c < channels; c++) {
                    // 相对写入（put(float) 依赖 position）：带下标的绝对批量读写是
                    // Java 13+ API，Android（release 8）会构建失败
                    java.nio.FloatBuffer dst = out.channelWriter(c);
                    dst.position(written);
                    for (int i = 0; i < n; i++) {
                        dst.put(pending[(pendingOffset + i) * channels + c]);
                    }
                }
                pendingOffset += n;
                written += n;
                framesDecoded += n;
                if (written == capacity) {
                    break;
                }
                continue;
            }

            // 2) pending 空了，去解新的一包
            if (!fillPending()) {
                break;
            }
        }

        out.setFrames(written);
        if (written == 0 && !drained) {
            // 容量非零却没解出任何数据，且流也没结束——不该发生，按结束处理避免死循环
            drained = true;
        }
        return written;
    }

    /**
     * 解码一包进 {@code pending}，并重置交付游标。返回 {@code false} 表示流已结束。
     *
     * <p>与 {@link #decodeInto} 拆开正是为了让「先清 pending，再取新包」这个顺序看得见：
     * 把两件事揉在一个循环里就是上游上一个版本的错误来源。
     */
    private boolean fillPending() {
        int polls = 0;
        int emptyReads = 0;
        while (true) {
            if (outputEos || reachedPlayableEnd()) {
                // reachedPlayableEnd：尾部的编码器填充已经全部被裁掉，剩下的都是填充，
                // 必须**主动**收工。不然我们会继续把填充解出来、每包都被裁成 0 帧，
                // 一路撞空读上限然后报「解不出数据」——把一次正常收尾变成了错误。
                drained = true;
                return false;
            }

            if (bypass) {
                // 未压缩音源：样本本身就是 PCM，没有解码器可等（见类注释第 6 条）
                Sample sample = nextSample();
                if (sample == null) {
                    drained = true;
                    return false;
                }
                int frames = stageFrames(ByteBuffer.wrap(sample.data), bypassEncoding);
                if (frames > 0) {
                    pendingFrames = frames;
                    pendingOffset = 0;
                    return true;
                }
                continue;
            }

            feedInput();

            int index;
            try {
                index = codec.dequeueOutputBufferIndex(bufferInfo);
            } catch (MediaCodec.CodecException e) {
                if (e.isTransient()) {
                    // 瞬时错误：立刻重试（不计数，不算坏包）
                    continue;
                }
                if (e.isRecoverable()) {
                    recoverCodec(e);
                    continue;
                }
                throw AudioException.decode("解码失败：" + e.getMessage());
            } catch (IllegalStateException e) {
                throw AudioException.decode("解码器状态异常：" + e);
            }

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 暂时没有输出：喂过输入了，继续等
                polls++;
                if (inputEos && polls > EOS_GRACE_POLLS) {
                    // 解码器没回 EOS 标记（有些设备就是不回），但输入早送完了、也没数据可吐了：
                    // 按结束处理，并如实说明这不是「解码器报的 EOS」
                    if (!warnedEosMissing) {
                        warnedEosMissing = true;
                        Logger.warn("AndroidDecoder: 输入已结束但解码器一直没有回 EOS 标记，"
                                + "按「尾巴吐完」处理：{}", label);
                    }
                    drained = true;
                    return false;
                }
                if (polls > MAX_OUTPUT_POLLS) {
                    throw AudioException.decode("等不到解码输出（连续 " + MAX_OUTPUT_POLLS
                            + " 次空轮询），放弃这一路音源");
                }
                sleepQuietly();
                continue;
            }
            polls = 0;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onOutputFormatChanged();
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 已废弃的通知（API 21+ 用 getOutputBuffer 直接取）；同步封装内部已经吞掉它
                continue;
            }
            if (index < 0) {
                continue;
            }

            if (!outputFormatSeen) {
                // 正常情况 INFO_OUTPUT_FORMAT_CHANGED 会先到；万一来晚了，这里补读一次，
                // 免得拿 16 位的默认值去解 float 数据（听起来正好就是雪花音）
                applyOutputFormat(codec.getOutputFormat());
            }
            boolean eosFlag = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            // 配置包（CSD）不是音频：必须跳过，但它同时也是「解码器在工作」的证据
            boolean configFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            int frames = 0;
            try {
                if (!configFrame && bufferInfo.size > 0) {
                    ByteBuffer buf = codec.getOutputBuffer(index);
                    if (buf != null) {
                        buf.position(bufferInfo.offset);
                        buf.limit(bufferInfo.offset + bufferInfo.size);
                        frames = stageFrames(buf, pcmEncoding);
                    }
                }
            } finally {
                // 立刻还回去：数据已经拷进 pending，不跨调用持有解码器缓冲
                codec.releaseOutputBuffer(index, false);
            }

            if (eosFlag) {
                outputEos = true;
            }
            if (frames > 0) {
                pendingFrames = frames;
                pendingOffset = 0;
                return true;
            }
            if (outputEos) {
                drained = true;
                return false;
            }
            // 空包/被裁光的包：跳过，继续取下一包（坏包跳过而不是中断）
            if (++emptyReads > MAX_EMPTY_PACKETS) {
                throw AudioException.decode("连续 " + MAX_EMPTY_PACKETS
                        + " 个包都没有解出数据，放弃这一路音源");
            }
        }
    }

    /** 抽下一个压缩样本；没有更多就返回 {@code null}（并把 EOS 记账留给调用方）。 */
    private Sample nextSample() {
        int emptyReads = 0;
        while (samples.isEmpty() && !extractEnded) {
            readOnce();
            if (++emptyReads > MAX_EMPTY_PACKETS) {
                throw AudioException.decode("提取器连续 " + MAX_EMPTY_PACKETS
                        + " 次没有给出任何音频样本，放弃这一路音源");
            }
        }
        if (samples.isEmpty()) {
            return null;
        }
        Sample sample = samples.poll();
        lastSampleUs = sample.timeUs;
        return sample;
    }

    /**
     * 是不是已经把「可播放的帧」全部交付完了。
     *
     * <p>{@code playableFrames} 未知（容器没给 gapless 信息时）恒为 {@code false}——
     * 那时只能播到流的真结尾，这正是「已知缺口」的含义。
     */
    private boolean reachedPlayableEnd() {
        Long playable = gapless.playableFrames();
        if (playable == null || framesDecoded < playable.longValue()) {
            return false;
        }
        // 到点了，但**先跟容器自己的时间戳对一下账**再收工。
        //
        // 为什么需要这一步：可播放帧数是从「容器时长 − 编码器延迟/填充」算出来的，
        // 而时长字段会被写坏（或本来就是按码率估的，偏小）。一旦偏小，这里就会在
        // 歌曲还在放的时候宣告结束——**听感正是「刚换过来没放几秒就跳到下一首」**。
        // 时间戳（每个样本的 pts）是另一条独立的证据：它随真实帧数推进，不依赖时长字段。
        // 时间戳说「还剩很多」而时长说「到头了」，那就信时间戳，继续放。
        if (!containerEndAgrees()) {
            if (!warnedDurationMismatch) {
                warnedDurationMismatch = true;
                Logger.warn("AndroidDecoder: 容器报的时长与它自己的时间戳不符"
                                + "（时长 {}us，最后一个样本 pts {}us）→ 不按时长收尾，改到流尾自然结束 · {}",
                        Long.valueOf(containerDurationUs), Long.valueOf(lastSampleUs), label);
            }
            return false;
        }
        return true;
    }

    /**
     * 容器的时长字段与它自己的样本时间戳是否一致（是否「已经真的到头了」）。
     *
     * <p>只剩 {@link #END_SLACK_US} 以内才算一致：那一小段正好覆盖解码器缓冲里的
     * 最后一个样本与尾部填充（填充本来就是时长的最后一段）。
     */
    private boolean containerEndAgrees() {
        if (containerDurationUs == C.TIME_UNSET || containerDurationUs <= 0L || lastSampleUs < 0L) {
            // 没时长、或还没喂过样本：没有可对照的东西，认这个可播放帧数
            return true;
        }
        return containerDurationUs - lastSampleUs <= END_SLACK_US;
    }

    /** 时长与时间戳允许的差值：比任何真实的尾部填充都宽，比任何「时长报小了」都窄。 */
    private static final long END_SLACK_US = 5_000_000L;

    /** 喂一个样本进解码器（输入侧永远是「能喂就喂」，输出侧自己等）。 */
    private void feedInput() {
        if (inputEos) {
            return;
        }
        int index;
        try {
            index = codec.dequeueInputBufferIndex();
        } catch (MediaCodec.CodecException e) {
            if (e.isTransient()) {
                return;     // 这一轮没喂成，下一轮再试
            }
            if (e.isRecoverable()) {
                recoverCodec(e);
                return;
            }
            throw AudioException.decode("取输入缓冲失败：" + e.getMessage());
        } catch (IllegalStateException e) {
            throw AudioException.decode("解码器状态异常：" + e);
        }
        if (index < 0) {
            return;
        }

        if (currentSample == null) {
            Sample sample = nextSample();
            if (sample == null) {
                // 没有更多样本：送 EOS，让解码器把内部的尾巴吐完
                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEos = true;
                return;
            }
            currentSample = sample;
            currentSampleOffset = 0;
        }

        ByteBuffer in = codec.getInputBuffer(index);
        if (in == null) {
            codec.queueInputBuffer(index, 0, 0, 0L, 0);
            return;
        }
        int remaining = currentSample.data.length - currentSampleOffset;
        if (remaining > in.capacity()) {
            // 一个样本比解码器的输入缓冲还大：**不能**塞半帧（解出来是爆音）。
            // 按 media3 的做法：丢掉这个样本、flush 一下从下一个同步点继续，
            // 代价是少一小段声音，好过把整条流解成噪音。
            Logger.warn("AndroidDecoder: 样本 {} 字节放不进解码器输入缓冲（{} 字节）："
                            + "丢掉这一包、flush 解码器并从同步点继续（{}）",
                    Integer.valueOf(remaining), Integer.valueOf(in.capacity()), label);
            codec.queueInputBuffer(index, 0, 0, 0L, 0);
            flushAndResync(lastSampleUs);
            return;
        }
        in.clear();
        in.put(currentSample.data, currentSampleOffset, remaining);
        // 时间戳用样本自己的（pts 决定解码器与下游的时间轴）
        codec.queueInputBuffer(index, 0, remaining, currentSample.timeUs, 0);
        currentSample = null;
        currentSampleOffset = 0;
    }

    /**
     * 解码器报了实际输出格式。
     *
     * <p>只有这一处能知道「要 float 到底给没给」：{@code KEY_PCM_ENCODING} 被解码器忽略时，
     * 输出格式里的编码仍是 16 位——那就按 16 位解（→ f32 精确），<strong>如实退而不是报错</strong>。
     * 声道数/采样率与容器声明不一致则直接报错：布局变了还硬搬数据会把左右声道搅在一起。
     */
    private void onOutputFormatChanged() {
        MediaFormat out;
        try {
            out = codec.getOutputFormat();
        } catch (RuntimeException e) {
            return;
        }
        applyOutputFormat(out);
    }

    /** 把解码器报的输出格式记下来（{@code null} 视为「没报」，保持现有编码）。 */
    private void applyOutputFormat(MediaFormat out) {
        if (out == null) {
            return;
        }
        outputFormatSeen = true;
        int encoding = intOr(out, MediaFormat.KEY_PCM_ENCODING, pcmEncoding);
        if (encoding != pcmEncoding) {
            Logger.info("AndroidDecoder: 解码器输出 PCM 编码 {}（请求的是 float {}）",
                    Integer.valueOf(encoding), Integer.valueOf(AndroidAudioFormats.ENCODING_PCM_FLOAT));
        }
        pcmEncoding = encoding;
        int decodedChannels = intOr(out, MediaFormat.KEY_CHANNEL_COUNT, channels);
        int decodedRate = intOr(out, MediaFormat.KEY_SAMPLE_RATE, spec.sampleRate);
        if (!AndroidCodecs.layoutMatches(channels, spec.sampleRate, decodedChannels, decodedRate)) {
            throw AudioException.decode("解码器输出的声道/采样率与容器声明不符："
                    + decodedChannels + "ch " + decodedRate + " Hz ≠ "
                    + channels + "ch " + spec.sampleRate + " Hz");
        }
    }

    /**
     * 把一段 PCM 拷进 {@code pending}（含首尾裁剪）。
     *
     * @return 暂存下来的帧数（可能是 0：整包都被编码器延迟/填充吃掉）
     */
    private int stageFrames(ByteBuffer buf, int encoding) {
        if (framer == null || framer.frameBytes() != AndroidAudioFormats.frameBytes(encoding, channels)) {
            framer = new PcmFramer(encoding, channels);
        }
        // 半帧由 framer 接续到下一包（丢了它会让左右声道错位 = 杂音），所以这里不判断「整帧」
        framer.feed(buf);
        int availFrames = framer.availableFrames();
        if (availFrames <= 0) {
            return 0;
        }

        // 1) 首部编码器延迟：先丢
        if (skipFrames > 0L) {
            int drop = framer.skip((int) Math.min(skipFrames, (long) availFrames));
            skipFrames -= drop;
            availFrames -= drop;
            if (availFrames <= 0) {
                return 0;
            }
        }

        // 2) 尾部编码器填充：到应收的帧数就停（不猜总长时这一步不做，属已知缺口）
        int want = availFrames;
        Long playable = gapless.playableFrames();
        if (playable != null) {
            long room = playable.longValue() - framesDecoded;
            if (room <= 0L) {
                return 0;
            }
            if ((long) want > room) {
                want = (int) room;
            }
        }

        // **这一包一律整包读进来**，再按 want 决定交付多少。
        // 为什么不是「只读 want 帧」：framer 里剩下的帧会留在它的 source 上，而下一包
        // feed 时那份残留既接不上也吃不掉（半个采样的接续机制只处理不足一帧的尾巴）。
        // 多读进来的那些（尾部填充）留在暂存里、不交付，也就不会被记进已交付帧数。
        int toRead = Math.min(availFrames, Math.max(want, 1));
        if (pendingCapacityFrames < toRead) {
            // 极少走到：包比预分配的暂存还大。扩容一次（渲染线程上分配，不是音频回调里）
            int cap = Math.max(toRead, pendingCapacityFrames * 2);
            pending = new float[cap * channels];
            pendingCapacityFrames = cap;
        }
        int frames = framer.read(pending, toRead);
        return Math.min(frames, want);
    }

    /**
     * 可恢复的解码错误：{@code flush} 后回到同步点继续——<strong>坏包跳过而不是中断</strong>。
     *
     * <p>抓轨文件常有零星坏帧，为此停掉整首歌不可接受。但有两点必须守住：
     * 恢复次数有上限（一路都在坏就说明这文件没救了，继续「播」等于假装在播），
     * 且恢复后从同步点重喂（解码器 flush 之后不能接着帧中间的数据解）。
     */
    private void recoverCodec(MediaCodec.CodecException cause) {
        if (recoveries >= MAX_CODEC_RECOVERIES) {
            throw AudioException.decode("解码器连续出错 " + MAX_CODEC_RECOVERIES
                    + " 次，放弃这一路音源：" + cause.getMessage());
        }
        recoveries++;
        Logger.warn("AndroidDecoder: 解码器可恢复错误（第 {} 次），跳过坏包并回到同步点：{}",
                Integer.valueOf(recoveries), cause.getMessage());
        // 已经在流中间了，首部延迟不该再裁一次
        skipFrames = 0L;
        flushAndResync(lastSampleUs);
    }

    /**
     * 丢掉手上的一切、{@code flush} 解码器，然后从某个同步点重开。
     *
     * <p><strong>必须 flush</strong>：解码器此刻可能停在某一帧的中间，直接换位置继续喂，
     * 它会把帧边界错开的数据当音频解出来——那正是「雪花音」。flush 之后从同步点重喂才是
     * 一条干净的流（media3 遇到「样本比输入缓冲还大」时也是这么做的）。
     */
    private void flushAndResync(long timeUs) {
        try {
            codec.flush();
        } catch (RuntimeException e) {
            throw AudioException.decode("解码器恢复失败：" + e);
        }
        samples.clear();
        currentSample = null;
        currentSampleOffset = 0;
        inputEos = false;
        outputEos = false;
        drained = false;
        resyncTo(timeUs);
    }

    /** 从某个时间戳的同步点重新开始读（坏包恢复用）；找不到就继续顺序喂。 */
    private void resyncTo(long timeUs) {
        if (seekMap == null || !seekMap.isSeekable() || timeUs < 0L) {
            return;
        }
        SeekPoint point = seekMap.getSeekPoints(timeUs).first;
        try {
            openAt(point.position, point.timeUs);
        } catch (RuntimeException e) {
            // 找回同步点失败就继续顺序喂：多数解码器能从下一个包重新同步
            Logger.warn("AndroidDecoder: 坏包后找回同步点失败：{}", e.toString());
        }
    }

    private static void sleepQuietly() {
        try {
            SystemClock.sleep(POLL_SLEEP_MS);
        } catch (RuntimeException ignored) {
            // 被中断之类：下一轮继续轮询即可
        }
    }

    private int intOr(MediaFormat f, String key, int fallback) {
        if (f == null || !f.containsKey(key)) {
            return fallback;
        }
        try {
            return f.getInteger(key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return; // 幂等：Java 的 AutoCloseable 惯例是重复调用无害
        }
        closed = true;
        if (codec != null) {
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            extractor.release();
        } catch (RuntimeException ignored) {
        }
        // 网络源的连接要显式关：提取器只持有回调，不保证替我们释放
        // （不关的话 socket 会挂到 GC 那天）
        closeDataSource();
        samples.clear();
        currentSample = null;
    }

    // ---- 包内可见的诊断/测试钩子（不改状态） ----

    /** pending 里还没交付的帧数（测试用它验证「先清 pending 再取新包」这条硬约束）。 */
    int pendingFrames() {
        return pendingFrames - pendingOffset;
    }

    /** 解码输出侧实际用的 PCM 编码（裸 PCM 直通时是源编码）。 */
    int pcmEncoding() {
        return bypass ? bypassEncoding : pcmEncoding;
    }

    /** gapless 记账（诊断用）。 */
    AndroidGapless gapless() {
        return gapless;
    }

    /** 是不是走「裸 PCM 直通」（不建解码器）。 */
    boolean isBypass() {
        return bypass;
    }

    /**
     * 只暴露真正有用的状态：解码器里的库对象没什么可打印的，而 AudioBuf 的教训是
     * 别把几万个采样刷进日志。
     */
    @Override
    public String toString() {
        return "AndroidDecoder{spec=" + spec + ", framesDecoded=" + framesDecoded
                + ", drained=" + drained + ", bypass=" + bypass + ", " + gapless.note() + '}';
    }
}
