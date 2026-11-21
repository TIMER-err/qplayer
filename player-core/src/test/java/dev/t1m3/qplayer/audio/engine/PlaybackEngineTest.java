package dev.t1m3.qplayer.audio.engine;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.core.RingBuffer;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.mix.PathState;
import dev.t1m3.qplayer.audio.output.DeviceCapabilities;
import dev.t1m3.qplayer.audio.output.DeviceCapability;
import dev.t1m3.qplayer.audio.output.OutputChooser;
import dev.t1m3.qplayer.audio.output.OutputConfig;
import dev.t1m3.qplayer.audio.output.OutputDevice;
import dev.t1m3.qplayer.audio.output.OutputFiller;
import dev.t1m3.qplayer.audio.output.OutputSampleFormat;
import dev.t1m3.qplayer.audio.output.OutputStreamControl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 播放引擎的端到端测试：<strong>不需要声卡、不需要真实文件、不依赖实时节奏</strong>。
 *
 * <p>它验证的是 docs/audio-engine.md 里那几条硬要求，而不是实现细节： *
 * <ol>
 *   <li><strong>喂进环形缓冲的采样是对的</strong>：假音源的采样值是「帧号的纯函数」
 *       （见 {@link #sampleOf}），所以从设备侧读出来的每一个采样都能反推出「这是哪一首的
 *       第几帧」。{@link #assertContiguousFrames} 因此能钉住<strong>不丢帧、不重帧、
 *       顺序不乱</strong>——这正是「环满就阻塞等待、绝不丢弃」那条要求的可执行版本。</li>
 *   <li><strong>位置/时长</strong>与产出帧数吻合，未知长度如实报 0。</li>
 *   <li><strong>跳转必须清空环形缓冲</strong>：跳转后环里的旧样本一个都不许漏出来。</li>
 *   <li><strong>暂停不关流</strong>：停止喂数据，环里剩下的自然放完，设备侧随后只是静音，
 *       恢复继续喂且位置连续。</li>
 *   <li><strong>曲终只在队列为空时报一次</strong>，并且先排空（不许把最后 200 ms 切掉）。</li>
 *   <li><strong>位完美如实报</strong>：增益走 deck，非单位值就不再直通；
 *       HiFi 开着时输出流按源采样率请求、主增益保持单位值。</li>
 *   <li><strong>预告的下一首真的进了编排器的队列</strong>：自动交接（编排器自己换曲）
 *       如实触发 onComplete，且控制器随后的 play(同一首) 不会把它重头再放一遍。</li>
 * </ol>
 *
 * <h2>假件是怎么搭的</h2>
 *
 * <ul>
 *   <li>{@link FakeDevice} / {@link FakeStream}：{@link OutputDevice} 的实现
 *       （参考 {@code audio.output.FakeOutputDevice} 的写法，那是包私有的，用不到这里）。
 *       它的写线程<strong>真的</strong>调 {@link OutputFiller#fill}，把产出的 f32 采样
 *       录下来，所以这是一条真链路：{@code Director/Deck → 环 → 回调体 → 设备格式}。
 *       它按<strong>模拟设备时钟</strong>消费（一次回调 512 帧，按输出采样率折算成时间，
 *       见 {@link FakeStream#pace()}）——理由不是「像真设备一点」，
 *       而是<strong>不节流的写线程会把整场断言毁掉</strong>：环被瞬间抽空、
 *       录音里九成以上是欠载静音，而所有断言都按「录音第 N 个采样 = 音源第 N 帧」算。
 *       需要真实时间的地方一律用「等到某个条件成立」，不 sleep 猜时间。</li>
 *   <li>{@link MemoryDecoder}：内存里的假解码器（{@link Decoder#install} 装上去，
 *       {@link After} 用反射把全局工厂还原——{@code DeckTest} 的先例）。</li>
 * </ul>
 *
 * <h2>曾经钉着的缺口：Deck 丢掉每首曲子的最后 16 帧（已修）</h2>
 *
 * <p>{@code Deck} 曾经每首曲子都<strong>丢掉最后 16 帧</strong>（{@code Resampler.TAPS_PER_SIDE}
 * 的前视在流末尾永远凑不齐，而 {@code pull} 不会在「没有更多输入」时吐出尾巴）：
 * 24000 帧的源只有 23984 帧出来，{@code remainingFrames()} 永远停在 16。
 * 修法是 {@code Resampler.drain(AudioBuf)}（输入结束时把缺失的「未来输入」按静音补齐后
 * 交付剩余输出点）+ {@code Deck.render} 在解码器耗尽且 {@code pull} 返回 0 时调一次它，
 * 属于 {@code audio.mix} / {@code audio.dsp} 的改动，见 {@code docs/audio-port.md}
 * 的「与上游的已知差异」表。
 *
 * <p>本类的两条测试正是照规格钉住这个缺口的，断言<strong>一个字都没改过</strong>：
 * {@code trackEndDrainsThenFiresOnCompleteExactlyOnce}（要求整首不差分毫）与
 * {@code armedNextSourceIsHandedToTheDirectorAndReportsTheHandoff}（要求接缝正好落在
 * 第 24000 帧）。修复前它们是红的，修复后转绿——这就是它们存在的意义。
 */
public class PlaybackEngineTest {

    /** 假设备/假音源统一用 48 kHz 立体声：与设备默认档位一致，重采样器处于透传，
     *  于是「采样值逐位可比」——断言才能用 {@code assertEquals(..., 0f)} 这种强度。 */
    private static final int RATE = 48_000;

    private static final int CHANNELS = 2;

    /** 假解码器一次最多交付多少帧（模拟「一个编码包」）。 */
    private static final int PACKET_FRAMES = 4_096;

    /** 设备一次回调要多少帧（假的写线程的粒度）。 */
    private static final int DEVICE_CHUNK_FRAMES = 512;

    /** 一秒的纳秒数（{@link FakeStream#pace()} 的模拟设备时钟要用）。 */
    private static final long NANOS_PER_SEC = 1_000_000_000L;

    /** 默认设备档位选 48 kHz（见 {@link OutputChooser#chooseDefault}），与 {@link #RATE} 一致。 */
    private static final int DEVICE_RATE = 48_000;

    private PlaybackEngine engine;
    private FakeDevice device;
    private MemoryOpener opener;
    private TestSettings settings;
    private Decoder.Opener savedOpener;

    // ==================================================================
    // 假件的准备与还原
    // ==================================================================

    @Before
    public void setUp() {
        savedOpener = currentOpener();
        opener = new MemoryOpener();
        opener.add("a.wav", RATE, CHANNELS, 24_000L, 0.50f);
        opener.add("b.wav", RATE, CHANNELS, 24_000L, 0.90f);
        opener.add("long.wav", RATE, CHANNELS, 480_000L, 0.70f);
        opener.add("hifi44.wav", 44_100, CHANNELS, 176_400L, 0.30f);
        Decoder.install(opener);

        settings = new TestSettings();
        device = new FakeDevice();
        engine = new PlaybackEngine(device);
        engine.setSettings(settings);
        // 环取小一点：测试里一秒音频几毫秒就放完，小环让「满-等-推」这条路径被反复走到
        engine.setRingMs(50);
    }

    @After
    public void tearDown() {
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
                // 释放失败不该把测试结果盖掉
            }
        }
        if (device != null && device.stream != null) {
            device.stream.close();
        }
        PlaybackEngine.commandProbe = null;
        Decoder.install(savedOpener);
    }

    /**
     * 读全局解码器工厂的当前值（{@link Decoder} 只有 {@code install}，没有 getter）。
     *
     * <p>与 {@code DeckTest} / {@code DirectorTest} 同一套：读不到就返回 {@code null}，
     * 那对应「宿主还没安装」这个干净起点。
     */
    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ==================================================================
    // 假音源：采样值是「第几帧」的纯函数
    // ==================================================================

    /**
     * 一条假音源的事实。
     *
     * <p>{@code amp} 让每一首的采样值落在互不相同的幅度上，于是从设备侧录下来的
     * 采样就能反推出「这是哪一首、第几帧」——跳转/换曲之后「放的是不是正确位置的内容」
     * 因此可以被逐样本断言，而不是只能看个大概。
     */
    private static final class MemTrack {

        final int rate;
        final int channels;
        final long frames;
        final float amp;

        MemTrack(int rate, int channels, long frames, float amp) {
            this.rate = rate;
            this.channels = channels;
            this.frames = frames;
            this.amp = amp;
        }
    }

    /**
     * 帧号 → 采样值。<strong>同一帧号永远得到同一个值</strong>，这是所有断言的支点。
     *
     * <p>值域是 {@code (0, 1]}：<strong>永不等于 0</strong>。0 专门留给「静音」——
     * 欠载补零、以及被跳转清掉的那一段。于是「有没有静音」本身就是一条可断言的信息。
     * 采样的低 10 位编码了 {@code 帧号 % 512}，反推得回来（见 {@link #frameIndexMod512}）。
     */
    private static float sampleOf(MemTrack t, long frameIndex, int channel) {
        int k = (int) (frameIndex % 512L) * 2 + 1;                 // 1..1023
        return (float) ((double) k / 1024.0) * t.amp + 0.05f * (float) channel;
    }

    /** 从通道 0 的采样值反推 {@code 帧号 % 512}。 */
    private static int frameIndexMod512(float v, float amp) {
        int k = Math.round(v / amp * 1024.0f);                     // 1..1023
        return ((k - 1) / 2) % 512;
    }

    private static final class MemoryDecoder implements Decoder {

        private final MemTrack track;
        private long pos;
        private boolean closed;

        MemoryDecoder(MemTrack track) {
            this.track = track;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("mem", track.rate, ChannelLayout.of(track.channels),
                    Integer.valueOf(16), Long.valueOf(track.frames), true);
        }

        @Override
        public long framesDecoded() {
            return pos;
        }

        @Override
        public boolean isDrained() {
            return pos >= track.frames;
        }

        @Override
        public double seek(double secs) {
            double s = secs < 0.0 ? 0.0 : secs;
            long f = (long) (s * (double) track.rate);
            if (f < 0L) {
                f = 0L;
            }
            if (f > track.frames) {
                f = track.frames;
            }
            pos = f;
            return (double) pos / (double) track.rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            if (isDrained()) {
                out.setFrames(0);
                return 0;
            }
            long left = track.frames - pos;
            int want = (int) Math.min((long) out.capacityFrames(),
                    Math.min(left, (long) PACKET_FRAMES));
            float[] data = out.backingArray();
            for (int c = 0; c < out.channels(); c++) {
                int base = out.channelBase(c);
                for (int i = 0; i < want; i++) {
                    data[base + i] = sampleOf(track, pos + i, c);
                }
            }
            out.setFrames(want);
            pos += want;
            return want;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    /** 假的解码器工厂：注册一批内存音源，开不存在的音源就抛异常（与产品路径一致）。 */
    private static final class MemoryOpener implements Decoder.Opener {

        private final Map<String, MemTrack> tracks = new HashMap<String, MemTrack>();
        private final Map<String, Integer> opens = new HashMap<String, Integer>();

        void add(String source, int rate, int channels, long frames, float amp) {
            tracks.put(source, new MemTrack(rate, channels, frames, amp));
        }

        MemTrack track(String source) {
            return tracks.get(source);
        }

        synchronized int opens(String source) {
            Integer n = opens.get(source);
            return n == null ? 0 : n.intValue();
        }

        @Override
        public Decoder open(String source, Map<String, String> headers) {
            MemTrack t = tracks.get(source);
            if (t == null) {
                throw AudioException.config("打不开音源：" + source);
            }
            synchronized (this) {
                Integer n = opens.get(source);
                opens.put(source, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            }
            return new MemoryDecoder(t);
        }
    }

    // ==================================================================
    // 假设备：真的走 OutputFiller，把设备侧听到的东西录下来
    // ==================================================================

    /** 录下来的设备侧采样。写线程与测试线程并发访问，所以自带同步。 */
    private static final class Recording {

        private float[] data = new float[1 << 17];
        private int len;
        private int content;
        private int underruns;

        synchronized void add(float[] src, int n, boolean underrun) {
            if (len + n > data.length) {
                int cap = data.length;
                while (cap < len + n) {
                    cap <<= 1;
                }
                data = Arrays.copyOf(data, cap);
            }
            for (int i = 0; i < n; i++) {
                float v = src[i];
                data[len + i] = v;
                // 假音源的采样值永不为 0，所以「非零采样数」就是「真正的内容有多少」
                if (v != 0f) {
                    content++;
                }
            }
            len += n;
            if (underrun) {
                underruns++;
            }
        }

        synchronized int length() {
            return len;
        }

        synchronized int contentSamples() {
            return content;
        }

        synchronized int underrunFills() {
            return underruns;
        }

        synchronized float at(int index) {
            return data[index];
        }

        synchronized float[] copy(int from, int n) {
            return Arrays.copyOfRange(data, from, from + n);
        }
    }

    /** 假设备的一条流：一个写线程 + 一块录音缓冲。 */
    private static final class FakeStream implements OutputStreamControl {

        private final RingBuffer.Consumer consumer;
        private final OutputSampleFormat format;
        private final int channels;
        private final int wireBytes;
        private final int rate;
        private final float[] scratch = new float[OutputFiller.SCRATCH_SAMPLES];
        private final byte[] buffer;
        private final Recording recording = new Recording();

        /**
         * 模拟设备时钟：下一次回调应当发生的时刻（{@link System#nanoTime()} 基准）。
         * 见 {@link #pace()}——<strong>不节流的假设备会把整场断言毁掉</strong>。
         */
        private long nextTickNanos;

        private volatile boolean running;
        private volatile boolean closed;
        private volatile Thread thread;

        FakeStream(RingBuffer.Consumer consumer, OutputConfig config) {
            this.consumer = consumer;
            this.format = config.sampleFormat();
            this.channels = config.channels();
            this.wireBytes = format.bytes();
            this.rate = config.sampleRate();
            this.buffer = new byte[DEVICE_CHUNK_FRAMES * channels * wireBytes];
        }

        @Override
        public void play() {
            running = true;
        }

        @Override
        public void close() {
            closed = true;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        boolean isClosed() {
            return closed;
        }

        /** 起一条「设备写线程」：不停地填满设备缓冲，直到流被关掉。 */
        void start() {
            if (thread != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    // 退出条件是 closed 而不是 running：OutputHandle.build 里
                    // stream.play() 与 openStream() 返回之间有一个极小的窗口，
                    // 只认 running 的话写线程可能刚起来就自己退出了。
                    while (!closed) {
                        fillOnce();
                    }
                }
            }, "fake-audio-device");
            t.setDaemon(true);
            thread = t;
            t.start();
        }

        /**
         * 按<strong>模拟设备时钟</strong>等一等：真实设备就是按采样率消费的。
         *
         * <p>为什么必须节流（这是夹具的核心约束，不是「顺手加个 sleep」）：
         * 写线程全速空转时，消费速度远超生产者的产出速度，环几乎总是空的，
         * 于是录下来的东西里<strong>九成以上是欠载补的静音</strong>
         * （实测：内容 24576 帧、静音 211968 帧）。而本类所有断言都建立在
         * 「录音里的第 N 个采样 = 音源第 N 帧」这个对应关系上——静音一插进来，
         * 这个关系就没了：按录音长度算出来的下标会落到静音上
         * （表现为 {@code expected:<0.31…> but was:<0.0>}），
         * 逐帧连续性断言也会在「两侧都是 0（静音）」处断掉。
         * 那些失败看着像引擎丢帧，实际是夹具把静音混进了录音。
         *
         * <p>按采样率节流之后（引擎的产出远快于实时，环里始终有货），
         * 录音就是一段连续的、位置可算的内容流；欠载只在引擎<strong>真的</strong>
         * 停止喂数据时出现——暂停、跳转清环、曲终，正是那些用例要测的东西。
         */
        private void pace() {
            long chunk = (long) DEVICE_CHUNK_FRAMES * NANOS_PER_SEC / Math.max(rate, 1);
            long now = System.nanoTime();
            if (nextTickNanos == 0L) {
                nextTickNanos = now;
            }
            nextTickNanos += chunk;
            long wait = nextTickNanos - System.nanoTime();
            while (wait > 0L) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                wait = nextTickNanos - System.nanoTime();
            }
            if (System.nanoTime() - nextTickNanos > 4L * chunk) {
                // 落后太多（被调度挤开）：把模拟时钟拉回现在。
                // 不拉回的话它会去补一大串突发，而那段突发正是「录音里突然多出一段静音」的来源。
                nextTickNanos = System.nanoTime();
            }
        }

        /** 填一次设备缓冲（512 帧）。**真的**走 {@link OutputFiller}，与桌面宿主同一条路径。 */
        void fillOnce() {
            int samples = DEVICE_CHUNK_FRAMES * channels;
            int availableBefore = consumer.available();
            OutputFiller.fill(consumer, scratch, format, buffer);
            float[] chunk = new float[samples];
            for (int i = 0; i < samples; i++) {
                chunk[i] = format.readSample(buffer, i * wireBytes);
            }
            recording.add(chunk, samples, availableBefore < samples);
            pace();
        }

        /** 环里还有多少采样可读（单消费者单生产者，读它是只读操作）。 */
        int available() {
            return consumer.available();
        }

        int capacity() {
            return consumer.capacity();
        }
    }

    /** 假输出设备：只有一档「F32 立体声 44.1k~192k」，所以协商结果确定。 */
    private static final class FakeDevice implements OutputDevice {

        private final DeviceCapabilities caps = new DeviceCapabilities(Arrays.asList(
                new DeviceCapability(OutputSampleFormat.F32, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I24, 2, 44_100, 192_000),
                new DeviceCapability(OutputSampleFormat.I16, 2, 44_100, 96_000)), null);

        final AtomicInteger openCount = new AtomicInteger();
        volatile OutputConfig openedConfig;
        volatile FakeStream stream;

        @Override
        public String name() {
            return "fake-engine-device";
        }

        @Override
        public DeviceCapabilities capabilities() {
            return caps;
        }

        @Override
        public OutputConfig defaultConfig(int ringFrames) {
            return OutputChooser.chooseDefault(caps).toConfig(ringFrames);
        }

        @Override
        public OutputStreamControl openStream(OutputConfig config, RingBuffer.Consumer consumer,
                                              Integer fixedBufferFrames) {
            openCount.incrementAndGet();
            openedConfig = config;
            FakeStream s = new FakeStream(consumer, config);
            stream = s;
            return s;
        }
    }

    /** 测试用的设置桥（不必造一个真的 {@code SettingsCore}，见 {@link EngineSettings}）。 */
    private static final class TestSettings implements EngineSettings {

        int mode = 0;                 // 0 无缝拼接
        int style = 0;
        float crossfadeSecs;
        float harmonicSecs;
        boolean bitPerfect;

        /** 工作室处理链（默认全关；测试里换一个新对象就代表「用户改了参数」）。 */
        dev.t1m3.qplayer.audio.studio.StudioRacks studioRacks =
                dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;
        boolean rateFollow;
        boolean enabled = true;       // 总开关；默认 true = 与这条接口出现之前一致

        @Override
        public int automixMode() {
            return mode;
        }

        @Override
        public int automixStyle() {
            return style;
        }

        @Override
        public float crossfadeSecs() {
            return crossfadeSecs;
        }

        @Override
        public float harmonicSecs() {
            return harmonicSecs;
        }

        @Override
        public boolean bitPerfect() {
            return bitPerfect;
        }

        @Override
        public dev.t1m3.qplayer.audio.studio.StudioRacks audioStudio() {
            return studioRacks;
        }

        @Override
        public boolean rateFollow() {
            return rateFollow;
        }

        @Override
        public boolean automixEnabled() {
            return enabled;
        }
    }

    // ==================================================================
    // 断言助手
    // ==================================================================

    /** 等到条件成立；超时即失败（**不用 sleep 猜时间**）。 */
    private static void waitUntil(String what, long timeoutMs, BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断：" + what);
            }
        }
        fail("超时（" + timeoutMs + " ms）仍未满足：" + what);
    }

    /**
     * 断言设备侧录下来的每一帧都接着上一帧（按「帧号 % 512」还原）。
     *
     * <p>它同时钉住四件事：<strong>没丢帧、没重帧、顺序没乱、也没跳位置</strong>——
     * 环满时丢弃、攒块截断、跳转漏清环，任何一个都会在这里露出来。
     */
    private void assertContiguousFrames(Recording rec, int fromSample, int toSample, MemTrack t) {
        int prev = -1;
        int startFrame = fromSample / CHANNELS;
        for (int k = fromSample; k + CHANNELS <= toSample; k += CHANNELS) {
            int idx = frameIndexMod512(rec.at(k), t.amp);
            if (prev >= 0 && idx != (prev + 1) % 512) {
                fail("第 " + (k / CHANNELS) + " 帧（相对第 " + startFrame + " 帧起 "
                        + ((k - fromSample) / CHANNELS) + " 帧）不连续：上一帧号%512=" + prev
                        + "，这一帧=" + idx + "——丢了帧、重放了帧，或顺序被打乱了");
            }
            prev = idx;
        }
    }

    private void startDevice() {
        // 开流是**懒**的（第一次 play 时在生产者线程上做），所以这里先等设备流出现，
        // 再等它真的被 play() 起来——假设备的写线程只在 running 为真时填数据。
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        waitUntil("输出流要被启动", 5_000L, () -> device.stream.isRunning());
        // 再等环里真的有内容才起写线程。设备线程一起来就按采样率消费，而起早了的代价是
        // 录音开头多出一段欠载静音——本类的断言全都按「录音位置 == 源帧位置」算
        // （{@link #sampleOf} 与 {@link #assertContiguousFrames} 都建立在它上面），
        // 开头多一段静音就等于把整条时间轴平移了。这是夹具的确定性要求，不是猜时间。
        waitUntil("起设备线程之前环里先要有内容", 5_000L,
                () -> device.stream.available() >= DEVICE_CHUNK_FRAMES * CHANNELS);
        device.stream.start();
    }

    /**
     * 等到「设备侧的内容数」不再变化（暂停之后最多再漏一块），返回那个数。
     *
     * <p>为什么不直接睡一下再断言：暂停命令与生产者线程手上那一块之间存在一个
     * 极小的窗口（命令世代号检查与 push 之间），所以「暂停之后内容就不再增长」
     * 严格来说要等那一块落地才算成立。等它稳定下来是确定性做法，睡一觉是猜。
     */
    private int waitForStableContent() {
        int last = -1;
        long stableSince = System.currentTimeMillis();
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            int now = device.stream.recording.contentSamples();
            if (now != last) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince >= 50L) {
                return now;
            }
            sleep(5L);
        }
        fail("内容一直没停下来——暂停之后还有东西在往环里推");
        return last;
    }

    // ==================================================================
    // 1. 喂进环里的采样是对的
    // ==================================================================

    @Test
    public void ringReceivesTheSourceSamplesUnchanged() {
        engine.play("a.wav", 0L);
        startDevice();
        waitUntil("设备侧至少要放出 2048 帧", 5_000L,
                () -> device.stream.recording.length() >= 2_048 * CHANNELS);

        MemTrack a = opener.track("a.wav");
        Recording rec = device.stream.recording;
        int frames = 2_048;
        for (int i = 0; i < frames; i++) {
            // 48k 源 → 48k 设备、位深 F32、增益单位：这一路上不该有一个采样被改动
            assertEquals("第 " + i + " 帧左声道",
                    sampleOf(a, i, 0), rec.at(i * CHANNELS), 0f);
            assertEquals("第 " + i + " 帧右声道",
                    sampleOf(a, i, 1), rec.at(i * CHANNELS + 1), 0f);
        }
        assertContiguousFrames(rec, 0, frames * CHANNELS, a);
        assertEquals("整场播放只开一次流（无缝的前提）", 1, device.openCount.get());
        assertEquals("设备协商到的就是请求的配置", DEVICE_RATE, device.openedConfig.sampleRate());
        assertEquals(CHANNELS, device.openedConfig.channels());
    }

    // ==================================================================
    // 2. 位置与时长
    // ==================================================================

    @Test
    public void positionAndDurationFollowTheProducedFrames() {
        assertEquals("未知长度如实报 0，不猜", 0L, engine.duration());
        engine.play("long.wav", 0L);
        startDevice();
        waitUntil("至少放出 0.5 秒", 5_000L,
                () -> device.stream.recording.contentSamples() >= 24_000 * CHANNELS);

        assertEquals("时长来自曲目事实（10 秒）", 10_000L, engine.duration());
        int underrunsBefore = device.stream.recording.underrunFills();
        engine.pause();
        // 暂停之后不再喂数据：环里剩下的自然放完，位置于是**冻结**在产出帧数上
        waitUntil("暂停后环必须排空（设备侧开始出现静音）", 3_000L,
                () -> device.stream.recording.underrunFills() > underrunsBefore);
        long consumedFrames = device.stream.recording.contentSamples() / CHANNELS;
        long pos = engine.position();
        // 位置按**已产出**帧数记账，而排空之后「已产出」== 「已消费」，两者应当一致；
        // 留 120 ms 的余量覆盖「暂停时手上还有没推完的一块」（一块 4096 帧 ≈ 85 ms）
        long consumedMs = consumedFrames * 1000L / DEVICE_RATE;
        assertTrue("位置 " + pos + " ms 与已消费的 " + consumedMs + " ms 差得太远",
                Math.abs(pos - consumedMs) <= 120L);
        assertTrue("暂停时位置不该往前跑", pos > 0L);
        assertFalse(engine.isPlaying());
    }

    // ==================================================================
    // 3. 暂停：不关流，也不丢已产出的音频
    // ==================================================================

    @Test
    public void pauseStopsFeedingWithoutClosingTheStreamAndResumeContinues() {
        engine.play("long.wav", 0L);
        startDevice();
        Recording rec = device.stream.recording;
        waitUntil("先放出一些内容", 5_000L, () -> rec.contentSamples() >= 24_000 * CHANNELS);

        engine.pause();
        int underrunsBefore = rec.underrunFills();
        waitUntil("环排空（设备侧开始出现静音）", 3_000L,
                () -> rec.underrunFills() > underrunsBefore);
        int frozenContent = waitForStableContent();
        int frozenLen = rec.length();
        long frozenPos = engine.position();

        // 暂停**不关流**：关掉就丢了「一条永不中断的输出流」这个无缝前提。
        // 设备侧仍在跑，只是拿不到数据——欠载时吐静音，时钟不乱。
        assertFalse("暂停绝不能关流", device.stream.isClosed());
        assertTrue("流还应当在跑", device.stream.isRunning());
        sleep(80L);
        assertEquals("暂停之后不该再有内容进来（只剩静音）", frozenContent, rec.contentSamples());
        assertEquals("暂停之后位置冻结", frozenPos, engine.position());

        engine.resume();
        assertTrue("恢复之后要如实报「在放」", engine.isPlaying());
        waitUntil("恢复后内容继续进环", 3_000L,
                () -> rec.contentSamples() > frozenContent + 24_000 * CHANNELS);
        assertTrue("恢复后位置继续推进", engine.position() > frozenPos);

        // 恢复之后的内容必须是**接着**放的：先跳过暂停期间录下来的静音，
        // 再断言帧号一路连续（没跳回、也没丢一段）。
        MemTrack t = opener.track("long.wav");
        int resumeAt = frozenLen;
        while (resumeAt < rec.length() && rec.at(resumeAt) == 0f) {
            resumeAt++;
        }
        assertTrue("恢复之后应当又有内容", resumeAt + 4_800 * CHANNELS <= rec.length());
        int lastContent = frozenLen - 1;
        while (lastContent >= 0 && rec.at(lastContent) == 0f) {
            lastContent--;
        }
        assertTrue("暂停之前本该录到内容", lastContent >= 0);
        int lastChannel0 = lastContent - (lastContent % CHANNELS);
        int lastIdx = frameIndexMod512(rec.at(lastChannel0), t.amp);
        assertEquals("恢复必须从暂停处接着放，不许跳也不许重",
                (lastIdx + 1) % 512, frameIndexMod512(rec.at(resumeAt), t.amp));
        assertContiguousFrames(rec, resumeAt, resumeAt + 4_800 * CHANNELS, t);
        assertEquals("从头到尾只开了一次流", 1, device.openCount.get());
    }

    // ==================================================================
    // 4. 跳转必须清空环形缓冲
    // ==================================================================

    @Test
    public void seekDiscardsThePendingRingAndResumesAtTheTarget() {
        engine.play("long.wav", 0L);
        // **不**起设备线程：让生产者把环灌满后自己阻塞住，这样我们能看清环里有什么。
        // 条件里必须先判 stream 非空：开流是懒的（在生产者线程上做），
        // 这里第一次求值时它通常还没出现——直接解引用会以 NPE 结束整条用例。
        waitUntil("环要被填满（生产者推完一整块就阻塞）", 5_000L,
                () -> device.stream != null
                        && device.stream.available() >= device.stream.capacity());

        // 跳转之前，环里确实压着旧位置的音频（否则下面的断言是空的）
        device.stream.fillOnce();
        Recording rec = device.stream.recording;
        boolean anyStale = false;
        for (int i = 0; i < DEVICE_CHUNK_FRAMES * CHANNELS; i++) {
            if (rec.at(i) != 0f) {
                anyStale = true;
                break;
            }
        }
        assertTrue("跳转前设备侧本该有内容", anyStale);

        // 跳转命令是**排队**交给生产者线程执行的（引擎的线程模型：控制线程只入队，
        // 只有生产者线程碰 Director 与 deck），而环里的残留是生产者在处理这条命令时
        // 发信号、由消费者在自己的线程上下一次读时才丢掉的。
        // 所以「跳转之后环里还有没有旧内容」只有在命令**真的落到生产者线程上**之后才可判定：
        // 在那之前弹出来的内容本来就还属于跳转前——那不是引擎漏清环，是测试抢跑了。
        // position() 给不出这个信号（seek() 一进函数就把目标位置乐观地发布出去了，
        // 落地时的实际位置在整数毫秒上又完全相同），所以这里用引擎那处包私有探针
        // （PlaybackEngine.commandProbe，Director.heavyProbe 的先例）等一个确定的落地信号。
        List<String> probe = Collections.synchronizedList(new ArrayList<String>());
        PlaybackEngine.commandProbe = probe;
        engine.pause();
        engine.seek(4_000L);
        waitUntil("seek 要落到生产者线程上（在那之前弹出来的内容仍属于跳转前）", 3_000L,
                () -> {
                    synchronized (probe) {
                        return probe.contains("seek");
                    }
                });
        // 跳转之后：环里**旧位置**的采样必须一个都不许漏出来。
        // 不清环的话，这里会先放出跳转前那一小段——听感正是「跳过去又弹回来」。
        device.stream.fillOnce();
        int offset = DEVICE_CHUNK_FRAMES * CHANNELS;
        for (int i = 0; i < DEVICE_CHUNK_FRAMES * CHANNELS; i++) {
            assertEquals("跳转后不该先放出一小段旧内容（第 " + (i / CHANNELS) + " 帧）",
                    0f, rec.at(offset + i), 0f);
        }

        engine.resume();
        // 恢复之后从**新位置**接着放：第 4 秒 = 第 192000 帧
        waitUntil("跳转目标之后要有内容", 3_000L,
                () -> device.stream.available() >= DEVICE_CHUNK_FRAMES * CHANNELS * 2);
        int before = rec.length();
        device.stream.fillOnce();
        MemTrack t = opener.track("long.wav");
        long target = 4_000L * DEVICE_RATE / 1000L;
        assertEquals("跳转后第一帧应当就是新位置的内容",
                sampleOf(t, target, 0), rec.at(before), 0f);
        assertEquals(sampleOf(t, target, 1), rec.at(before + 1), 0f);
        assertEquals("后面要逐帧接着走", sampleOf(t, target + 1, 0), rec.at(before + 2), 0f);
        assertEquals("跳转/暂停都不许重开输出流", 1, device.openCount.get());
    }

    // ==================================================================
    // 5. 曲终：先排空，再报一次
    // ==================================================================

    @Test
    public void trackEndDrainsThenFiresOnCompleteExactlyOnce() {
        final AtomicInteger completes = new AtomicInteger();
        engine.setOnComplete(completes::incrementAndGet);
        engine.play("a.wav", 0L);
        startDevice();
        waitUntil("曲终回调要触发", 5_000L, () -> completes.get() > 0);

        // 排空是**必须**的：直接报曲终会让控制器立刻开下一首，把这首歌最后一段切掉
        MemTrack a = opener.track("a.wav");
        Recording rec = device.stream.recording;
        waitUntil("整首都要真的从设备侧放出去", 5_000L,
                () -> rec.contentSamples() >= a.frames * CHANNELS);
        assertContiguousFrames(rec, 0, (int) a.frames * CHANNELS, a);
        assertEquals("0.5 秒的曲子一帧不多一帧不少",
                a.frames * CHANNELS, rec.contentSamples());
        sleep(50L);
        assertEquals("onComplete 只许报一次", 1, completes.get());
        assertFalse("曲终之后不再是「在放」", engine.isPlaying());
        assertFalse("曲终绝不该关流", device.stream.isClosed());
        assertEquals("整场只开一次流", 1, device.openCount.get());
        assertEquals("位置停在末尾", 500L, engine.position());
    }

    // ==================================================================
    // 6. 音量走 deck 的增益；位完美如实报
    // ==================================================================

    @Test
    public void volumeGoesThroughTheDeckGainAndBitPerfectIsHonest() {
        engine.play("long.wav", 0L);
        startDevice();
        Recording rec = device.stream.recording;
        waitUntil("先放一段", 5_000L, () -> rec.length() >= 24_000 * CHANNELS);
        assertEquals("单位增益 + 透传 + 格式不丢信息 → 直通",
                PathState.BIT_PERFECT, engine.pathState());

        engine.setVolume(0.5f);
        waitUntil("增益非单位值就不再位完美（这是算出来的，不是特判）", 2_000L,
                () -> engine.pathState() == PathState.PROCESSED);
        int settled = rec.length() + 24_000 * CHANNELS;   // 越过 5 ms 的增益斜坡
        waitUntil("音量变化后要有新内容", 3_000L, () -> rec.length() >= settled);
        MemTrack t = opener.track("long.wav");
        // 采样值真的被**deck 的增益**改写了：0.5 × 源采样（增益的 dB 往返会有 1 ulp 级的差）
        int sample = settled - 1_000;
        int frame = sample / CHANNELS;
        assertEquals("音量必须走 deck 的增益，不是偷偷改采样值",
                0.5f * sampleOf(t, frame, 0), rec.at(sample - (sample % CHANNELS)), 1e-5f);

        engine.setVolume(1.0f);
        waitUntil("回到单位增益又如实变回直通", 2_000L,
                () -> engine.pathState() == PathState.BIT_PERFECT);
    }

    /**
     * <strong>工作室主总线那一台必须真的在写环之前的采样上生效</strong>。
     *
     * <p>为什么非要在引擎层再钉一遍：{@code StudioProcessor} 自己测得再全，也证明不了
     * 「{@code produceBlock} 真的调了它」——漏掉那一个调用点<strong>不会报错、不会崩</strong>，
     * 只是用户设的 EQ 一点用都没有。所以这里从设备录下来的采样本身验证：
     * 48 dB/oct 低通（200 Hz）必须把这段锯齿波（基频 93.75 Hz + 一堆谐波）削掉明显的能量，
     * 并且位完美标记必须如实熄灭。
     */
    @Test
    public void theMasterStudioRackProcessesWhatYouHear() {
        engine.play("long.wav", 0L);
        startDevice();
        Recording rec = device.stream.recording;
        waitUntil("先放一段", 5_000L, () -> rec.length() >= 24_000 * CHANNELS);
        assertEquals("没开工作室时应当是位完美", PathState.BIT_PERFECT, engine.pathState());

        settings.studioRacks = dev.t1m3.qplayer.audio.studio.StudioRacks.master(
                dev.t1m3.qplayer.audio.studio.StudioRack.eq(java.util.Arrays.asList(
                        dev.t1m3.qplayer.audio.studio.EqBand.of(
                                dev.t1m3.qplayer.audio.studio.BandType.LOW_PASS, 200, 0, 0.7071,
                                48, dev.t1m3.qplayer.audio.studio.ChannelTarget.STEREO, true))));
        waitUntil("工作室在动采样 → 位完美必须熄灭", 3_000L,
                () -> engine.pathState() == PathState.PROCESSED);

        int start = rec.length();
        waitUntil("等它多放一段（让滤波器进稳态）", 5_000L,
                () -> rec.length() >= start + 96_000 * CHANNELS);
        int from = start + 24_000 * CHANNELS;
        int to = Math.min(rec.length(), from + 48_000 * CHANNELS);
        MemTrack t = opener.track("long.wav");
        double processed = 0;
        double source = 0;
        int different = 0;
        int n = 0;
        float maxDiff = 0f;
        for (int i = from; i < to; i++) {
            float got = rec.at(i);
            int frame = i / CHANNELS;
            float want = sampleOf(t, frame, i % CHANNELS);
            processed += (double) got * got;
            source += (double) want * want;
            float diff = Math.abs(got - want);
            maxDiff = Math.max(maxDiff, diff);
            if (diff > 1e-3f) {
                different++;
            }
            n++;
        }
        // 判据看的是**波形被改写了多少**，不是能量掉了多少：这段测试素材是周期 512 帧的锯齿
        // （基频 93.75 Hz，在 200 Hz 转角之下），低通之后基频照过，能量自然掉不多
        // （实测 −0.3 dB）——拿能量当判据会在一个正确实现上报红。逐样本差才是「处理真的发生了」。
        double dropDb = 20 * Math.log10(Math.sqrt(processed / n) / Math.sqrt(source / n));
        System.out.printf("### 主总线 48 dB/oct @200 Hz：逐样本最大差 %.3f，改写过的采样 %d/%d，"
                        + "能量 %+.1f dB%n",
                Float.valueOf(maxDiff), Integer.valueOf(different), Integer.valueOf(n),
                Double.valueOf(dropDb));
        assertTrue("采样必须真的被改写了（否则那个调用点根本没跑）：不同的有 " + different + "/" + n,
                different > n / 2);
        assertTrue("改写幅度太小（最大 " + maxDiff + "）——不像过了滤波器", maxDiff > 0.05f);

        // 关掉之后要回到位完美（用户关了 EQ 之后那盏灯必须重新亮起来）
        settings.studioRacks = dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;
        waitUntil("关掉之后回到位完美", 3_000L,
                () -> engine.pathState() == PathState.BIT_PERFECT);
    }

    // ==================================================================
    // 6.6 工作室的两块实时数据：表头与频谱必须真的在算
    // ==================================================================

    /**
     * <b>表头与实时频谱必须是「引擎真的在算」，不是两个永远空着的字段。</b>
     *
     * <h2>为什么必须有这一条</h2>
     *
     * <p>2026-09-26 发现：{@code PlaybackEngine} 里 {@code studioMeters} 与
     * {@code spectrumAnalyzer} 两个字段<strong>只声明过、从来没被赋值</strong>——
     * 于是界面上「表头」永远显示 -120 dB、「实时频谱」永远空着，而这一页看起来一切正常
     * （探针用假后端答了这两条数据，所以它没暴露）。这一类缺口没有任何报错，
     * 只有从<strong>引擎出口</strong>断言才抓得到，所以这条判据走真链路：
     * 假设备开流 → play → 从 {@code engine.studioSpectrum()/studioMeters()} 读。
     *
     * <p>它同时钉住「没人看就不算」那条纪律：没开监控时两块数据都必须是空的，
     * 开了之后才真的动起来。
     */
    @Test
    public void theStudioMetersAndSpectrumPublishWhatYouHear() {
        // **必须用长的那一首**：b.wav 只有 0.5 秒，等它录满的时候歌已经放完了，
        // 之后再打开监控就没有任何一块可算（第一版就是这么误判成「引擎没在算」的）
        engine.play("long.wav", 0L);
        startDevice();
        Recording rec = device.stream.recording;
        waitUntil("先放一段", 5_000L, () -> rec.length() >= 24_000 * CHANNELS);

        float[] bands = new float[dev.t1m3.qplayer.audio.engine.SpectrumBands.BANDS];
        // ① 没人看的时候一个字都不许算：频谱必须是整条静音（-120），表头也要是静音。
        //    判的是**值**而不是「写了几条」：那 64 条永远写满（拷贝接口的契约），
        //    有没有人在算要看内容。
        assertEquals("拷贝接口应当把 64 条都写满", bands.length, engine.studioSpectrum(bands));
        for (int i = 0; i < bands.length; i++) {
            assertEquals("没开监控时第 " + i + " 条频谱必须是静音（-120）", -120f, bands[i], 0.001f);
        }
        assertEquals("没开监控时表头读数不该有任何峰值",
                -120.0, engine.studioMeters().peakDb(), 1e-9);

        // ② 界面进来（页面可见）：两块数据都开始动
        engine.setStudioMonitoring(true);
        // 要**真的等它算出来**：拷贝接口永远写满 64 条（没算过时那 64 条是 -120），
        // 所以判据只能看内容，不能看条数（第一版就是这么误判成「已经在算」的）
        double loudest = -120.0;
        long spectrumDeadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < spectrumDeadline) {
            engine.studioSpectrum(bands);
            loudest = -120.0;
            for (int i = 0; i < bands.length; i++) {
                if (bands[i] > loudest) {
                    loudest = bands[i];
                }
            }
            if (loudest > -60.0) {
                break;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int loud = 0;
        for (int i = 0; i < bands.length; i++) {
            if (bands[i] > -80f) {
                loud++;
            }
        }
        System.out.printf("### 频谱：最响 %.1f dBFS，%d/%d 条在 -80 dBFS 以上%n",
                Double.valueOf(loudest), Integer.valueOf(loud), Integer.valueOf(bands.length));
        assertTrue("有音乐在放，频谱不许整条都是静音（最响 " + loudest + " dBFS）", loudest > -60.0);
        assertTrue("64 条里应当有一批真的在动，实际 " + loud, loud > 8);

        // ③ 表头：峰值要接近音源幅度（0.90），而不是永远 -120
        waitUntil("表头要有读数", 3_000L,
                () -> engine.studioMeters().peakDb() > -20.0);
        dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot snap = engine.studioMeters();
        System.out.printf("### 表头：峰值 %.2f dBFS，真峰 %.2f dBTP，M %.1f LUFS，相关 %.2f%n",
                Double.valueOf(snap.peakDb()), Double.valueOf(snap.truePeakDb()),
                Double.valueOf(snap.lufsM), Double.valueOf(snap.correlation));
        assertTrue("峰值应当跟得上音源（0.90 的正弦 ≈ -3 dBFS 以内）：" + snap.peakDb(),
                snap.peakDb() > -12.0 && snap.peakDb() < 3.0);
        assertTrue("真峰不该低于采样峰值（4× 插值只会更高）：" + snap.truePeakDb()
                        + " < " + snap.peakDb(),
                snap.truePeakDb() >= snap.peakDb() - 0.2);
        assertTrue("LUFS 是响度，不能还是静音：" + snap.lufsM, snap.lufsM > -40.0);
        // M（400 ms 窗）与 S（3 s 窗）在稳态信号上必须接近：两个窗用错分母的话
        // S 会凭空高出 8.8 dB（10·log10(3/0.4)），这是最容易被看出来的一个数
        assertTrue("稳态信号下 M 与 S 差不该超过 1.5 dB（M " + snap.lufsM + " / S " + snap.lufsS + "）",
                Math.abs(snap.lufsM - snap.lufsS) < 1.5);

        // ④ 页面离开：不再算（读数停住，但也不该突然变静音——画面会闪）
        engine.setStudioMonitoring(false);
        dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot after = engine.studioMeters();
        assertEquals("关掉监控之后读数就停了（还是最后一次那份）",
                snap.peakDb(), after.peakDb(), 1e-6);
    }

    // ==================================================================
    // 6.5 边放边改工作室参数：生产者线程一次都不许崩
    // ==================================================================

    /**
     * <b>一边出声一边像用户那样改参数</b>：随机段表（类型 / 声道 / Q / 开关）+ 各组模块的开关，
     * 每几十毫秒换一整套，连着换几十次。
     *
     * <h2>为什么必须有这一条</h2>
     *
     * <p>用户报过一次「有概率出现
     * {@code [qplayer-engine] 生产者线程异常：java.lang.ArrayIndexOutOfBoundsException}」——
     * 生产者线程的异常会被 catch 住、会话结束，而日志里当时只有异常类名（现在带栈了），
     * 所以「哪一块参数组合越界」只能靠把参数空间跑一遍来找。
     *
     * <p>它钉住的是一条纪律：<strong>换参数是允许分配的操作，但它不许把正在出声的那条线程弄崩</strong>。
     * 每换一次都要求：会话还在、采样还在往前推、`onError` 一次都没响。
     *
     * <p>覆盖到的组合（都是界面上点得到的）：段数 0–16；类型 峰值/低架/高架/低通/高通/陷波/全通；
     * 声道 立体声/左/右/中/侧；开关随机的段；三台机架各自不同；前级 / 音高 / 染色 / 动态 /
     * 立体声 / 响度补偿逐组开关。<strong>刻意用固定种子</strong>：红了要能一模一样地重放。
     */
    @Test
    public void swappingStudioRacksWhilePlayingNeverBreaksTheProducer() throws Exception {
        List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        engine.setOnError(() -> errors.add("onError"));

        engine.play("long.wav", 0L);
        startDevice();
        Recording rec = device.stream.recording;
        waitUntil("先放一段", 5_000L, () -> rec.length() >= 24_000 * CHANNELS);

        java.util.Random rnd = new java.util.Random(20_260_926L);
        int swaps = 0;
        long deadline = System.currentTimeMillis() + 12_000L;
        while (swaps < 200 && System.currentTimeMillis() < deadline) {
            settings.studioRacks = randomRacks(rnd);
            swaps++;
            // 每换几次就等一小会儿，让生产者真的用这套参数渲染若干块
            if (swaps % 4 == 0) {
                Thread.sleep(6L);
            }
        }
        // 换完之后再稳定跑一段：收尾（音高那一台的 21 ms 前视）也不许出事
        settings.studioRacks = dev.t1m3.qplayer.audio.studio.StudioRacks.EMPTY;
        Thread.sleep(120L);

        assertTrue("换了 " + swaps + " 次参数都没有崩，会话必须还在", engine.isPlaying());
        assertEquals("生产者线程不许报错（onError 一次都不该响）：" + errors, 0, errors.size());
        int before = rec.length();
        waitUntil("换完参数之后声音还得继续出", 3_000L, () -> rec.length() > before);
        assertEquals("换参数期间生产者线程崩溃过一次以上：" + errors, 0, errors.size());
        System.out.println("### 边放边改工作室参数：" + swaps + " 次切换，会话存活，采样继续推进");
    }

    /** 随机一整套工作室参数（三台机架各不同）。 */
    private static dev.t1m3.qplayer.audio.studio.StudioRacks randomRacks(java.util.Random rnd) {
        return dev.t1m3.qplayer.audio.studio.StudioRacks.of(
                randomRack(rnd), randomRack(rnd), randomRack(rnd));
    }

    private static dev.t1m3.qplayer.audio.studio.StudioRack randomRack(java.util.Random rnd) {
        int bands = rnd.nextInt(5) == 0 ? 16 : rnd.nextInt(4);
        List<dev.t1m3.qplayer.audio.studio.EqBand> list =
                new ArrayList<dev.t1m3.qplayer.audio.studio.EqBand>();
        dev.t1m3.qplayer.audio.studio.BandType[] types =
                dev.t1m3.qplayer.audio.studio.BandType.values();
        dev.t1m3.qplayer.audio.studio.ChannelTarget[] targets =
                dev.t1m3.qplayer.audio.studio.ChannelTarget.values();
        for (int i = 0; i < bands; i++) {
            list.add(dev.t1m3.qplayer.audio.studio.EqBand.of(
                    types[rnd.nextInt(types.length)],
                    20.0 * Math.pow(1000.0, rnd.nextDouble()),
                    -12.0 + 24.0 * rnd.nextDouble(),
                    0.1 + 18.0 * rnd.nextDouble(),
                    12,
                    targets[rnd.nextInt(targets.length)],
                    rnd.nextBoolean()));
        }
        dev.t1m3.qplayer.audio.studio.StudioRack rack =
                dev.t1m3.qplayer.audio.studio.StudioRack.eq(list);
        if (rnd.nextBoolean()) {
            rack = rack.withPreamp(-12.0 + 24.0 * rnd.nextDouble());
        }
        if (rnd.nextBoolean()) {
            rack = rack.withBalance(-1.0 + 2.0 * rnd.nextDouble());
        }
        if (rnd.nextInt(4) == 0) {
            rack = rack.withPolarityInvert(true);
        }
        if (rnd.nextInt(3) == 0) {
            rack = rack.withLoudness(true, 0.2 + 0.8 * rnd.nextDouble());
        }
        if (rnd.nextInt(3) == 0) {
            rack = rack.withPitch(dev.t1m3.qplayer.audio.studio.PitchParams.of(
                    rnd.nextInt(25) - 12, 100.0 * rnd.nextDouble() - 50.0,
                    rnd.nextDouble()));
        }
        if (rnd.nextInt(3) == 0) {
            dev.t1m3.qplayer.audio.studio.SaturationParams.Curve[] curves =
                    dev.t1m3.qplayer.audio.studio.SaturationParams.Curve.values();
            rack = rack.withSaturation(dev.t1m3.qplayer.audio.studio.SaturationParams.of(
                    curves[rnd.nextInt(curves.length)],
                    24.0 * rnd.nextDouble(), -24.0 * rnd.nextDouble(), rnd.nextDouble(),
                    1 + rnd.nextInt(3)));
        }
        if (rnd.nextInt(3) == 0) {
            rack = rack.withDynamics(dev.t1m3.qplayer.audio.studio.DynamicsParams.builder()
                    .gate(rnd.nextBoolean(), -80.0 + 50.0 * rnd.nextDouble(),
                            -60.0 + 30.0 * rnd.nextDouble(), 200.0 * rnd.nextDouble())
                    .compressor(rnd.nextBoolean(), -60.0 + 40.0 * rnd.nextDouble(),
                            1.0 + 19.0 * rnd.nextDouble(), 24.0 * rnd.nextDouble(),
                            0.1 + 200.0 * rnd.nextDouble(), 10.0 + 900.0 * rnd.nextDouble(),
                            24.0 * rnd.nextDouble())
                    .limiter(rnd.nextBoolean(), -12.0 + 12.0 * rnd.nextDouble(),
                            20.0 * rnd.nextDouble(), 10.0 + 500.0 * rnd.nextDouble())
                    .build());
        }
        if (rnd.nextInt(3) == 0) {
            rack = rack.withStereo(new dev.t1m3.qplayer.audio.studio.StereoParams(
                    true, 2.0 * rnd.nextDouble(), 500.0 * rnd.nextDouble(),
                    2.0 * rnd.nextDouble(), rnd.nextBoolean(), rnd.nextDouble()));
        }
        return rack;
    }

    // ==================================================================
    // 7. HiFi：按源采样率开流、主增益保持单位值
    // ==================================================================

    @Test
    public void hifiRequestsTheSourceRateAndKeepsTheMainGainAtUnity() {
        settings.bitPerfect = true;
        engine.play("hifi44.wav", 0L);
        startDevice();
        Recording rec = device.stream.recording;
        waitUntil("先放一段", 5_000L, () -> rec.length() >= 22_050 * CHANNELS);

        assertEquals("HiFi 要按**源**采样率请求输出流", 44_100, device.openedConfig.sampleRate());
        assertEquals("也只开一次流", 1, device.openCount.get());

        // HiFi 路径旁路一切电平处理：主增益保持单位值，音量交给系统/硬件
        // （设置页那行说明就是「全程不做增益」）。所以这里改音量**不改**采样值。
        engine.setVolume(0.3f);
        int settled = rec.length() + 22_050 * CHANNELS;
        waitUntil("继续放一段", 3_000L, () -> rec.length() >= settled);
        MemTrack t = opener.track("hifi44.wav");
        int sample = (settled - 1_000) - ((settled - 1_000) % CHANNELS);
        // 容差**只来自线材的位深**，不是放宽：假解码器声明的源是 16 bit
        // （见 {@link MemoryDecoder#spec()}），而 HiFi 直通按**源位深**请求输出流
        // （日志里就是 I16），于是采样值必经 f32 → I16 → f32 的往返，
        // 必然带半个 16 bit 台阶（0.5 / 32767 ≈ 1.5e-5）的量化差——这是位深本身的性质。
        // 这条断言要钉的是「主增益保持单位值」：音量 0.3 若真被施加，这一处会差
        // (1 − 0.3) × 0.027 ≈ 0.019，比容差大三个数量级，所以断言一点没变弱。
        assertEquals("HiFi 下主增益必须是单位值：音量 0.3 不许施加到采样值上",
                sampleOf(t, sample / CHANNELS, 0), rec.at(sample), 0.5f / 32767.0f);
        assertEquals("位完美直通应当如实报直通", PathState.BIT_PERFECT, engine.pathState());
    }

    // ==================================================================
    // 8. 预告的下一首真的进了编排器的队列（自动混音的前提）
    // ==================================================================

    @Test
    public void armedNextSourceIsHandedToTheDirectorAndReportsTheHandoff() {
        final AtomicInteger completes = new AtomicInteger();
        engine.setOnComplete(completes::incrementAndGet);
        engine.play("a.wav", 0L);
        startDevice();
        MemTrack a = opener.track("a.wav");
        Recording rec = device.stream.recording;
        waitUntil("第一首要真的开始放", 5_000L,
                () -> rec.contentSamples() >= 4_800 * CHANNELS);

        // 晚一点才预告：会话已经在跑，引擎只能重建编排器来把队首换掉
        // （Director 的队列在构造时就定下了，mix 层没有 enqueue）。重建不许
        // 在接缝上丢内容——下面的逐帧连续性断言就是钉这条的。
        engine.setNextSource("b.wav", 0L);

        MemTrack b = opener.track("b.wav");
        waitUntil("编排器应当自己过渡到预告的那一首（自动交接）", 10_000L,
                () -> "b.wav".equals(engine.currentSource()));
        assertTrue("自动交接要触发 onComplete，好让控制器推进它自己的队列",
                completes.get() >= 1);

        // 无缝拼接（窗口 0）：A 完整放完之后**紧接着**就是 B 的第 0 帧
        waitUntil("B 的内容也要出来", 5_000L,
                () -> rec.contentSamples() >= (a.frames + 4_800L) * CHANNELS);
        assertContiguousFrames(rec, 0, (int) a.frames * CHANNELS, a);
        int seam = (int) a.frames * CHANNELS;
        assertEquals("A 的最后一段与 B 的开头之间不许有洞、也不许重复",
                sampleOf(b, 0L, 0), rec.at(seam), 0f);
        assertEquals(sampleOf(b, 0L, 1), rec.at(seam + 1), 0f);
        assertEquals(sampleOf(b, 1L, 0), rec.at(seam + 2), 0f);
        assertContiguousFrames(rec, seam, seam + 4_800 * CHANNELS, b);
        assertEquals("换曲也不重开输出流（无缝的前提）", 1, device.openCount.get());

        // 控制器随后为了推进队列会 play(同一首)：那一次**不能重启**——
        // 重启会把已经过渡进来的那一整段重放一遍。
        int beforeCall = rec.length();
        engine.play("b.wav", 0L);
        waitUntil("确认之后还要继续放", 3_000L,
                () -> rec.length() >= beforeCall + 4_800 * CHANNELS);
        for (int k = beforeCall; k < rec.length(); k += CHANNELS * 512) {
            long frameInB = (long) (k / CHANNELS) - a.frames;
            assertEquals("确认自动交接不该把这一首重头再放一遍（第 " + (k / CHANNELS) + " 帧）",
                    sampleOf(b, frameInB, 0), rec.at(k), 0f);
        }
    }

    // ==================================================================
    // 9. 过渡窗口内如实报「过渡中」，收尾时如实交接
    // ==================================================================

    /**
     * 交叉淡化那几秒<strong>必须如实报「过渡中」</strong>——采样值被混合改写了，
     * 就不能还挂在「直通」上，ADR-003 要的是「不假装」。
     *
     * <p>用<strong>手动喂</strong>（不起设备写线程）把节奏攥在手里：每次只放 512 帧，
     * 而环只有约 4096 帧，于是生产者不可能在两次断言之间冲过整整 0.3 秒的过渡窗口，
     * 「看得到 TRANSITIONING」是确定性的，不是碰运气。
     */
    @Test
    public void transitionWindowIsReportedHonestlyThenHandsOver() {
        settings.mode = 1;              // 交叉淡化
        settings.crossfadeSecs = 0.3f;
        engine.setSettings(settings);
        // 日志也是这次交付的一部分：清一下，好断言「过渡那两条到底写没写清」
        dev.t1m3.qplayer.util.Logger.clear();
        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("b.wav", 0L);

        boolean sawTransitioning = false;
        for (int i = 0; i < 4_000 && !sawTransitioning; i++) {
            if (engine.pathState() == PathState.TRANSITIONING) {
                sawTransitioning = true;
                break;
            }
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertTrue("过渡窗口内必须如实报「过渡中」，不许假装还是直通", sawTransitioning);

        // 过渡收尾后编排器把进场曲提上位：引擎如实换掉「当前曲」
        for (int i = 0; i < 8_000 && !"b.wav".equals(engine.currentSource()); i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("过渡收尾后当前曲应当是进场曲", "b.wav", engine.currentSource());
        assertFalse("过渡结束之后不该还挂在「过渡中」",
                engine.pathState() == PathState.TRANSITIONING);
        assertEquals("整场只开一次流", 1, device.openCount.get());

        // ---- 日志：进入了/结束了各一条，而且把接法、窗口与实际交叠时长写全 ----
        // 用户能自己验收的唯一凭据就是这几行（界面上那几个数字之外）：
        // 「有没有发生过过渡」不该只能靠耳朵判断。
        StringBuilder log = new StringBuilder();
        for (String line : dev.t1m3.qplayer.util.Logger.snapshot()) {
            log.append(line).append('\n');
        }
        String text = log.toString();
        assertTrue("过渡开始时必须留下一条日志（含接法名与窗口）：\n" + text,
                text.contains("自动混音：进入过渡") && text.contains("接法"));
        assertTrue("过渡结束时必须留下一条日志（含实际交叠时长）：\n" + text,
                text.contains("自动混音：过渡结束") && text.contains("实际交叠"));
        assertFalse("日志模板的占位符与实参个数对不上（出现了没被替换的 {}）：\n" + text,
                text.contains("{}"));
    }

    // ==================================================================
    // 9b. 总开关关掉：一次过渡都不许发生（哪怕模式选的是交叉淡化）
    // ==================================================================

    /**
     * 总开关关着时，<strong>模式选了什么都不会进过渡</strong>：窗口被落成 0，
     * 于是既不 {@code prepare}（不烧 CPU）也不交叠，换曲就是 A 放完紧接着 B 的第 0 帧。
     *
     * <p>为什么这条必须钉住：用户的抱怨正是「自动混音好像一直在跑、却又完全没有效果」。
     * 一个没有总开关的接法选择，只能靠「模式选没选无缝拼接」间接表达「关」，
     * 而那是两件不同的事——这条测试把「关掉 = 真的什么都没做」变成可执行的断言。
     */
    @Test
    public void theMasterSwitchOffNeverEntersATransition() {
        settings.mode = 1;              // 交叉淡化：开着就该有过渡
        settings.crossfadeSecs = 0.3f;
        settings.enabled = false;       // …但总开关是关的
        engine.setSettings(settings);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("b.wav", 0L);

        // 手动喂，节奏攥在手里：整段过程中一次都不许出现 TRANSITIONING
        for (int i = 0; i < 4_000 && !"b.wav".equals(engine.currentSource()); i++) {
            assertFalse("总开关关着时不许进过渡（第 " + i + " 块）",
                    engine.pathState() == PathState.TRANSITIONING);
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("换曲还是要完成（硬接，不是卡住）", "b.wav", engine.currentSource());

        // 硬接：A 的第 0 帧起逐帧连续，接缝上既不重叠也不留洞。
        // 环里还压着 A 的尾段与 B 的头，所以要接着喂出去才能核对接缝。
        MemTrack a = opener.track("a.wav");
        MemTrack b = opener.track("b.wav");
        Recording rec = device.stream.recording;
        for (int i = 0; i < 200 && rec.contentSamples() < (a.frames + 4_800L) * CHANNELS; i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertTrue("B 的内容要出来（喂了 " + rec.contentSamples() + " 个采样）",
                rec.contentSamples() >= (a.frames + 4_800L) * CHANNELS);
        assertContiguousFrames(rec, 0, (int) a.frames * CHANNELS, a);
        int seam = (int) a.frames * CHANNELS;
        assertEquals("接缝上必须是 B 的第 0 帧（硬接，没有交叠）",
                sampleOf(b, 0L, 0), rec.at(seam), 0f);
        assertContiguousFrames(rec, seam, seam + 4_800 * CHANNELS, b);
    }

    /**
     * <b>中途拨总开关要立刻生效</b>：窗口是造编排器时按当时的设置定下的，
     * 光改设置不会动它——所以引擎在收到新预告时会发现「开关变了」并重建编排器。
     *
     * <p>不这么做的话，用户把开关拨到「关」之后，<strong>当前这一曲的结尾照样会交叠</strong>：
     * 界面说关着、耳朵里在混音，这正是最难查的那种不一致。
     */
    @Test
    public void turningTheSwitchOffMidSongStopsTheTransitionForThatVeryTrack() {
        settings.mode = 1;              // 交叉淡化
        settings.crossfadeSecs = 0.3f;
        settings.enabled = true;
        engine.setSettings(settings);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("b.wav", 0L);   // 这一份编排器的窗口 > 0

        // 用户中途把总开关拨到「关」，控制器随之重发同一条预告
        settings.enabled = false;
        engine.setSettings(settings);
        engine.setNextSource("b.wav", 0L);

        for (int i = 0; i < 4_000 && !"b.wav".equals(engine.currentSource()); i++) {
            assertFalse("开关已经关掉了，这一曲的结尾就不许再交叠（第 " + i + " 块）",
                    engine.pathState() == PathState.TRANSITIONING);
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("换曲仍要完成（无缝换曲，不是卡住）", "b.wav", engine.currentSource());

        // 关掉之后是无缝拼接：A 的尾帧紧接着 B 的第 0 帧
        MemTrack a = opener.track("a.wav");
        MemTrack b = opener.track("b.wav");
        Recording rec = device.stream.recording;
        for (int i = 0; i < 200 && rec.contentSamples() < (a.frames + 4_800L) * CHANNELS; i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        int seam = (int) a.frames * CHANNELS;
        assertTrue("B 的内容要出来（喂了 " + rec.contentSamples() + " 个采样）",
                rec.contentSamples() >= (a.frames + 4_800L) * CHANNELS);
        assertEquals("接缝上必须是 B 的第 0 帧（没有交叠）",
                sampleOf(b, 0L, 0), rec.at(seam), 0f);
    }

    // ==================================================================
    // 9c. 过渡窗口内的「当前曲」语义：位置/时长按进场 deck 报
    // ==================================================================

    /**
     * <b>过渡窗口里，位置与时长必须按进场 deck 报。</b>
     *
     * <p>用户的实测抱怨是「B 已经在出声了，界面还停在 A，A 的进度条一路走到 100% 才跳」。
     * 界面在过渡一开始就切到进场曲（见 {@code PlayerController} 的
     * {@code onTransitionStarted}），如果引擎这边位置还贴着出场曲的结尾，进度条就会
     * 变成「标题写着 B、进度停在 100% 不动」——那是另一种撒谎。
     *
     * <p>进场曲的时长必须与出场曲<b>不同</b>才分得出来，所以这里现加一首 2 秒的 c.wav
     * （夹具里的 a/b 都是 500 ms，报谁的都一样，断言不出东西）。
     */
    @Test
    public void duringTheTransitionTheReportedPositionAndDurationFollowTheIncomingDeck() {
        opener.add("c.wav", RATE, CHANNELS, 96_000L, 0.30f);
        settings.mode = 1;              // 交叉淡化
        settings.crossfadeSecs = 0.3f;
        engine.setSettings(settings);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("c.wav", 0L);

        boolean sawTransitioning = false;
        long posAtEntry = -1L;
        long durAtEntry = -1L;
        for (int i = 0; i < 4_000; i++) {
            if (engine.pathState() == PathState.TRANSITIONING) {
                sawTransitioning = true;
                posAtEntry = engine.position();
                durAtEntry = engine.duration();
                break;
            }
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertTrue("先确认真的进了过渡窗口（窗口 0.3 s）", sawTransitioning);
        assertEquals("过渡窗口里的时长必须按进场曲报（界面此刻已经切到它）："
                        + durAtEntry + " ms",
                2_000L, durAtEntry);
        // 出场曲 a 只有 500 ms、窗口 0.3 s，所以「还在报出场曲」时位置必然是 200 ms 往上
        // （环与手上那一块最多再压 ~50 ms）；进场 deck 那一刻才刚开跑，位置在 100 ms 以内。
        // 150 ms 这条线因此能把两种语义分开，而不是靠运气。
        assertTrue("过渡窗口里的位置必须按进场 deck 报（不许还是出场曲那个贴着结尾的值）："
                        + posAtEntry + " ms",
                posAtEntry >= 0L && posAtEntry <= 150L);

        // 交接之后进场曲上位：还是同一组数字——进度条在交接那一刻不会往回跳
        for (int i = 0; i < 8_000 && !"c.wav".equals(engine.currentSource()); i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("过渡收尾后当前曲应当是进场曲", "c.wav", engine.currentSource());
        assertEquals("交接之后时长仍是进场曲的", 2_000L, engine.duration());
        assertTrue("交接之后位置接着往前走（交接不许把位置打回 0）：" + engine.position()
                        + " ms（交接前 " + posAtEntry + " ms）",
                engine.position() >= posAtEntry);
    }

    // ==================================================================
    // 9d. 过渡一开始就通知控制器（界面跟着走，而不是等曲终）
    // ==================================================================

    /**
     * <b>过渡开始时通知一次，而且只通知一次。</b>
     *
     * <p>这条钉的是用户抱怨的另一半：进场曲已经在出声的那几秒里，界面必须跟着走。
     * 引擎这一刻报的就是「窗口开了」——不是「窗口走完了」：
     *
     * <ul>
     *   <li>通知<strong>恰好一次</strong>（曲终那次 {@code onComplete} 不许再报一遍，
     *       否则控制器会推进两次队列，直接从 B 跳到 C）；</li>
     *   <li>通知时 {@link PlaybackEngine#pathState()} 已经是 {@code TRANSITIONING}
     *       （证明时机是窗口开头，不是交接）；</li>
     *   <li>参数是<strong>进场曲</strong>的句柄（控制器要靠它认出自己预告出去的那一份）；</li>
     *   <li>此刻报的位置/时长已经是进场曲的（界面在同一瞬间切过去了，见 9c）。</li>
     * </ul>
     */
    @Test
    public void enteringTheTransitionNotifiesTheControllerExactlyOnce() {
        opener.add("c.wav", RATE, CHANNELS, 96_000L, 0.30f);
        settings.mode = 1;                  // 交叉淡化
        settings.crossfadeSecs = 0.3f;
        engine.setSettings(settings);

        final java.util.List<String> starts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        final java.util.List<PathState> states =
                java.util.Collections.synchronizedList(new java.util.ArrayList<PathState>());
        final java.util.List<Long> positions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
        final java.util.List<Long> durations =
                java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
        final AtomicInteger completes = new AtomicInteger();
        engine.setOnComplete(completes::incrementAndGet);
        engine.setOnTransitionStart(source -> {
            states.add(engine.pathState());
            positions.add(Long.valueOf(engine.position()));
            durations.add(Long.valueOf(engine.duration()));
            starts.add(source);
        });

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("c.wav", 0L);

        for (int i = 0; i < 10_000 && !"c.wav".equals(engine.currentSource()); i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("过渡收尾后当前曲应当是进场曲", "c.wav", engine.currentSource());

        assertEquals("过渡开始要通知，而且恰好一次：" + starts,
                java.util.Collections.singletonList("c.wav"), starts);
        assertEquals("通知必须发生在「已经在过渡窗口里」的那一刻（不是交接时）：" + states,
                java.util.Collections.singletonList(PathState.TRANSITIONING), states);
        assertEquals("通知那一刻报的时长必须已经是进场曲的（界面同时切过去了）：" + durations,
                java.util.Collections.singletonList(Long.valueOf(2_000L)), durations);
        assertTrue("通知那一刻报的位置必须已经是进场曲的：" + positions,
                positions.get(0).longValue() <= 150L);
        assertEquals("整段过渡只交接一次（onComplete 不许来第二次）", 1, completes.get());

        // 交接之后再喂一小段（不许把整首 c 喂完：它会自然结束、环跟着空掉）：
        // 这段时间里不许出现第二次通知（那会让控制器再推进一次队列）
        for (int i = 0; i < 40; i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("过渡结束之后不许再通知：" + starts,
                java.util.Collections.singletonList("c.wav"), starts);
        assertEquals("交接之后也不许再来一次 onComplete", 1, completes.get());
    }

    /**
     * <b>过渡开始后，控制器（或任何一方）对进场曲的那一次 {@code play} 必须被认掉。</b>
     *
     * <p>令牌窗口要覆盖「过渡开始 → 交接完成」这整段，而不只是交接那一瞬间：
     * 窗口最长 16 s（表现模式），而「控制器在窗口开头就推进」是这次改动之后迟早会发生的事。
     * 认不掉的那次 play 会重开会话——把已经混进来的那几秒<strong>重放一遍</strong>，
     * 比界面滞后糟得多（接缝上凭空多出一段重复、交叉淡化被从中间撕开）。
     */
    @Test
    public void aPlayOfTheIncomingTrackInsideTheWindowDoesNotRestartIt() {
        opener.add("c.wav", RATE, CHANNELS, 96_000L, 0.30f);
        settings.mode = 1;
        settings.crossfadeSecs = 0.3f;
        engine.setSettings(settings);

        final java.util.List<String> starts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        engine.setOnTransitionStart(starts::add);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("c.wav", 0L);

        // 喂到窗口里已经走过一小段：这样「位置被打回 0」才看得出来
        for (int i = 0; i < 4_000; i++) {
            if (engine.pathState() == PathState.TRANSITIONING && engine.position() >= 120L) {
                break;
            }
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertTrue("要先走到过渡窗口里（" + engine.position() + " ms）",
                engine.pathState() == PathState.TRANSITIONING);
        long before = engine.position();
        assertTrue("窗口里进场曲要有可失去的位置：" + before + " ms", before >= 120L);

        // 控制器在窗口开头推进时那一次 play（历史实现就是 play(进场曲, 0)）
        engine.play("c.wav", 0L);

        // play() 会先把请求值（0）发布出去——这是它「界面在会话真起来之前读到的是新曲
        // 位置」那条既有约定。要断言的是**认掉之后位置被真实位置覆盖回来**：
        // 认不掉的话这里会 endSession 重开会话，位置会一直停在 0（环满就没人再推）。
        waitUntil("认掉这一次 play 之后，位置必须回到进场曲已经走到的地方（不许被打回 0）",
                3_000L, () -> engine.position() >= before);
        assertTrue("play 前 " + before + " ms，play 后 " + engine.position() + " ms",
                engine.position() >= before);
        assertTrue("认掉之后过渡还要继续（认不掉就会 endSession 把过渡打断）："
                        + engine.pathState(),
                engine.pathState() == PathState.TRANSITIONING);
        assertEquals("通知仍然只有一次", 1, starts.size());

        for (int i = 0; i < 10_000 && !"c.wav".equals(engine.currentSource()); i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("过渡照常收尾、进场曲上位", "c.wav", engine.currentSource());
        assertEquals("全程只开一次流（认掉的那次 play 没有重开会话）", 1, device.openCount.get());
    }

    // ==================================================================
    // 9f. 工作室在动采样时，过渡照样要被看见（2026-09-26 修回归）
    // ==================================================================

    /**
     * <b>工作室开着（EQ/前级在动采样）时，「在过渡窗口里」这件事不许被吞掉。</b>
     *
     * <p>这条是回归测试：那次改动把 {@code pathState()} 里工作室那一支<strong>排到了过渡前面</strong>，
     * 于是工作室一开机，引擎就再也看不到过渡。三个后果全是用户能听见/看见的：
     *
     * <ol>
     *   <li>界面<strong>整个窗口</strong>停在出场曲上（{@code onTransitionStart} 一次都不发）；</li>
     *   <li>交接时那枚一次性令牌从不落下，控制器随后的确认<strong>不被认</strong>——
     *       引擎真的把进场曲从 0 重开一遍（用户实测：「过渡完成后歌曲进度被重置回 0 从头播放」）；</li>
     *   <li>「过渡结束」那条日志会说成「无缝换曲（本次没有过渡）」。</li>
     * </ol>
     *
     * <p>判据就是这三条的可执行版本：窗口里 {@code pathState()} 必须是 {@code TRANSITIONING}、
     * 通知恰好一次、交接照常完成。
     */
    @Test
    public void anActiveStudioDoesNotHideTheTransitionFromTheEngine() {
        opener.add("c.wav", RATE, CHANNELS, 96_000L, 0.30f);
        settings.mode = 1;                  // 交叉淡化
        settings.crossfadeSecs = 0.3f;
        // 一台**真的在动采样**的机架：主总线前级 +3 dB（StudioRack.active() 会返回 true）
        settings.studioRacks = dev.t1m3.qplayer.audio.studio.StudioRacks.master(
                dev.t1m3.qplayer.audio.studio.StudioRack.EMPTY.withEnabled(true).withPreamp(3.0));
        engine.setSettings(settings);

        final java.util.List<String> starts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        final java.util.List<PathState> states =
                java.util.Collections.synchronizedList(new java.util.ArrayList<PathState>());
        engine.setOnTransitionStart(source -> {
            states.add(engine.pathState());
            starts.add(source);
        });

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("c.wav", 0L);

        boolean sawTransitioning = false;
        for (int i = 0; i < 10_000 && !"c.wav".equals(engine.currentSource()); i++) {
            if (engine.pathState() == PathState.TRANSITIONING) {
                sawTransitioning = true;
            }
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertTrue("工作室开着时也必须能看见过渡窗口（否则界面不推进、交接令牌不落下）",
                sawTransitioning);
        assertEquals("过渡开始要通知，而且恰好一次：" + starts,
                java.util.Collections.singletonList("c.wav"), starts);
        assertEquals("通知那一刻必须已经在过渡窗口里（工作室不许把它盖成 PROCESSED）：" + states,
                java.util.Collections.singletonList(PathState.TRANSITIONING), states);
        assertEquals("过渡照常收尾、进场曲上位", "c.wav", engine.currentSource());
    }

    /**
     * <b>{@code confirmHandoff} 只认账，绝不重启——句柄对不对得上都不重启。</b>
     *
     * <p>控制器在曲终回调里发的那句话从前是 {@code play(进场曲, 0)}：引擎按<strong>句柄
     * 字符串逐字</strong>比对那枚一次性令牌，同一首歌的「直链」与「落地后的缓存文件」是两个
     * 字符串，对不上就当成一次真正的换曲——{@code endSession} + 重新开流，
     * 于是刚过渡进来的歌<strong>从 0 重放</strong>（用户实测原话：「过渡完成后歌曲进度
     * 被重置回 0 从头播放」）。
     *
     * <p>现在换成显式命令，判据三条：句柄一致时不动、句柄不一致时也不动、两者都不重开会话。
     */
    @Test
    public void confirmingTheHandoffNeverRestartsTheIncomingTrack() {
        opener.add("c.wav", RATE, CHANNELS, 96_000L, 0.30f);
        settings.mode = 1;
        settings.crossfadeSecs = 0.3f;
        engine.setSettings(settings);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("c.wav", 0L);

        for (int i = 0; i < 10_000 && !"c.wav".equals(engine.currentSource()); i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("先走到交接完成", "c.wav", engine.currentSource());
        // 交接之后再放一小段：这样「被打回 0」才看得出来
        for (int i = 0; i < 12; i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        long before = engine.position();
        assertTrue("交接之后要已经走过一段（" + before + " ms）", before > 40L);

        // ① 句柄一致（引擎手上的就是这一份）
        engine.confirmHandoff("c.wav");
        waitUntil("确认之后位置不许往回跳", 3_000L, () -> engine.position() >= before);

        // ② 句柄不一致（同一首歌的另一个句柄：控制器手上是缓存文件、引擎开的是直链）
        engine.confirmHandoff("/cache/audio/v2-deadbeef.cache");
        waitUntil("句柄不一致也**只认账**，位置同样不许被打回 0",
                3_000L, () -> engine.position() >= before);

        assertEquals("确认不是换曲：当前曲不变", "c.wav", engine.currentSource());
        assertEquals("确认不许重开会话（重开就是「进度被重置回 0」那条 bug）",
                1, device.openCount.get());
        assertTrue("位置仍然在往前走：" + engine.position() + " ms（确认前 " + before + " ms）",
                engine.position() >= before);
    }

    /** 接法的窗口是 0（无缝拼接）时没有过渡窗口，这个回调一次都不该有。 */
    @Test
    public void withWindowZeroTheControllerIsNeverToldTheTransitionStarted() {
        settings.mode = 0;                  // 无缝拼接：窗口 0
        engine.setSettings(settings);
        final java.util.List<String> starts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        engine.setOnTransitionStart(starts::add);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        engine.setNextSource("b.wav", 0L);

        for (int i = 0; i < 4_000 && !"b.wav".equals(engine.currentSource()); i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertEquals("换曲还是要完成（硬接）", "b.wav", engine.currentSource());
        assertEquals("窗口 0 = 没有过渡窗口，就没有这个回调：" + starts, 0, starts.size());
    }

    /** 没有预告下一首（引擎队列为空）时也不会进过渡，这个回调一次都不该有。 */
    @Test
    public void withoutAnArmedNextNothingIsNotified() {
        settings.mode = 1;
        settings.crossfadeSecs = 0.3f;
        engine.setSettings(settings);
        final java.util.List<String> starts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        engine.setOnTransitionStart(starts::add);

        engine.play("a.wav", 0L);
        waitUntil("输出流要被打开", 5_000L, () -> device.stream != null);
        // 不预告：这一曲放完就结束（控制器那边也拿不到下一首）
        MemTrack a = opener.track("a.wav");
        Recording rec = device.stream.recording;
        for (int i = 0; i < 4_000 && rec.contentSamples() < a.frames * CHANNELS; i++) {
            waitUntil("环里要有内容可放", 3_000L, () -> device.stream.available() > 0);
            device.stream.fillOnce();
        }
        assertTrue("这一首要真的放到底（喂了 " + rec.contentSamples() + " 个采样）",
                rec.contentSamples() >= a.frames * CHANNELS);
        assertEquals("没有预告就没有过渡，也就没有这个回调：" + starts, 0, starts.size());
    }

    // ==================================================================
    // 10. 释放：关流、停线程、不挂住
    // ==================================================================

    @Test
    public void releaseClosesTheStreamStopsProductionAndDoesNotHang() {
        engine.play("long.wav", 0L);
        startDevice();
        waitUntil("先放一段", 5_000L,
                () -> device.stream.recording.contentSamples() > 4_800 * CHANNELS);

        long t0 = System.currentTimeMillis();
        engine.release();
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("release() 不许挂住（上游那场「不退出也不报错」的事故）：" + elapsed + " ms",
                elapsed < 1_000L);
        assertTrue("释放必须关流——不关就是持续出声", device.stream.isClosed());
        assertFalse(device.stream.isRunning());
        assertFalse(engine.isPlaying());
        assertEquals("释放之后没有会话", null, engine.outputConfig());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================================
    // 11. 引擎自己的小工具（不算端到端，但它是位置记账的地基）
    // ==================================================================

    @Test
    public void frameConversionsRoundTrip() {
        assertEquals(9_600, EngineFrames.ringFramesFor(48_000, 200));
        assertEquals(500L, EngineFrames.msOfFrames(24_000, 48_000));
        assertEquals(24_000L, EngineFrames.framesOfMs(500L, 48_000));
        assertEquals(0.5, EngineFrames.secsOfFrames(24_000, 48_000), 0.0);
        assertEquals(2, EngineFrames.framesOfSamples(6, 3));
        assertEquals("未知长度如实报 0", 0L, EngineFrames.msOfFrames(0L, 48_000));
        assertNotNull(EngineFrames.class);
    }
}
