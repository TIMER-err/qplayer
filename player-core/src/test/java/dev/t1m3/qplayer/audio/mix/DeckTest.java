package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;
import dev.t1m3.qplayer.audio.dsp.PitchShifter;
import dev.t1m3.qplayer.audio.dsp.Resampler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 单条 deck 的对齐测试：对应 Sonalis {@code sonalis-mix/src/deck.rs} 的 {@code mod tests}
 * （上游 9 条，这里逐条对应），另加 6 条本地补充。
 *
 * <p>方法名按 camelCase 逐条对应上游，断言强度与容差一致（{@code assert_eq!} →
 * {@code assertEquals}，{@code assert!(x, "…{}", y)} → {@code assertTrue(msg, x)}
 * 并把上游那段解释原样带上）。
 *
 * <h2>为什么这个类里有一套 WAV 解码器</h2>
 *
 * <p>上游 {@code test_wav} 写一个真的 WAV 到临时目录，再由 {@code symphonia} 打开。
 * QPlayer 的 player-core <strong>故意没有解码器实现</strong>（桌面是
 * jaudiotagger + jlayer + jflac，安卓是 MediaCodec / MediaExtractor，两边不能共用，
 * 见 {@link Decoder} 的类注释），所以这里由测试自己装一个只认 16 bit PCM WAV 的最小
 * 实现：<strong>它是测试替身，不是产品代码</strong>。另一个作用是「文件不存在」那条：
 * 产品路径上 {@code Decoder.open} 会抛 {@link AudioException}，替身必须提供同样的行为。
 *
 * <h2>Java 侧没有 {@code cfg(test)} 探针，改用「计数 + 记线程名」的替身</h2>
 *
 * <p>上游的 {@code crate::probe} 是一套 {@code cfg(test)} 专用探针，记录「哪条线程做了
 * 开文件 / 定位 / 造变调器」。Java 没有等价机制，所以这里的替身自己做这件事：
 * {@link WavOpener} 记下每次开音源与定位<strong>发生在哪条线程上</strong>，
 * {@code prebuiltPitchShifterIsInstalledWithoutIo} 用它断言「安装路径一个都不新增」——
 * 这正是上游那条测试守住的行为（过渡开始那一刻生产者线程不做 IO）。
 *
 * <p>{@link Decoder#install(Decoder.Opener)} 是<strong>全局静态状态</strong>：
 * 每条测试的 {@link Before} 自己装一遍，{@link After} 用反射快照把全局工厂还原成
 * 测试开始前的样子（{@code Decoder} 没有提供读取当前工厂的入口）。
 */
public class DeckTest {

    private static final int RATE = 48_000;

    /** 造素材用的 {@code core::f32::consts::PI} 的 f32 镜像。 */
    private static final float PI_F32 = (float) Math.PI;

    /**
     * 重采样器每侧的抽头数，即 {@code Resampler.TAPS_PER_SIDE}。
     *
     * <p>为什么在这里抄一个常量而不是直接引用：那个常量是<strong>包内可见</strong>的
     * （{@code audio.dsp} 的策略：不让它进公开 API），{@code DeckTest} 在
     * {@code audio.mix} 包里看不见它。它只用在下面「末尾收尾」那两条断言的容差上，
     * 而「它就是 16」这个事实由
     * {@code ResamplerTest.drainFlushesTheTailSampleExactlyAtSameRate} 钉住，
     * 所以两边不会悄悄漂移。
     */
    private static final int RESAMPLER_TAPS_PER_SIDE = 16;

    /** 造出来的临时 WAV，测试结束删掉（上游留在 temp 目录里不管）。 */
    private final List<Path> tempFiles = new ArrayList<Path>();

    /** 本轮装上去的解码器工厂（探针就在它里面）。 */
    private WavOpener opener;

    /** 测试开始前的全局工厂，用于还原。 */
    private Decoder.Opener savedOpener;

    @Before
    public void installTestDecoder() {
        savedOpener = currentOpener();
        opener = new WavOpener();
        Decoder.install(opener);
    }

    @After
    public void restoreGlobalDecoderFactory() {
        Decoder.install(savedOpener);
        for (int i = 0; i < tempFiles.size(); i++) {
            try {
                Files.deleteIfExists(tempFiles.get(i));
            } catch (IOException e) {
                // 临时文件删不掉不该让测试变红
            }
        }
        tempFiles.clear();
    }

    /**
     * 读全局解码器工厂的当前值。
     *
     * <p>{@link Decoder} 只有 {@code install} 没有 getter（约定文档给的 API 表里也没有），
     * 所以只能反射快照。读不到时返回 {@code null}——那对应「宿主还没安装」这个干净起点，
     * 也是 player-core 测试的常态。
     */
    private static Decoder.Opener currentOpener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (RuntimeException e) {
            // InaccessibleObjectException / SecurityException 之类：退回干净起点
            return null;
        }
    }

    // ==================================================================
    // 造素材的辅助（对应上游 tests 模块里的 test_wav）
    // ==================================================================

    /**
     * 造一个临时 WAV（48 kHz / 16-bit / 指定声道），供 deck 测试打开。
     *
     * <p>源采样率刻意取 48 kHz：与测试里的输出采样率一致，重采样器处于
     * 透传状态——这样 {@code isPassthrough} 的断言不会被重采样干扰。
     */
    private Path testWav(ChannelLayout layout, float secs) {
        int rate = RATE;
        int ch = layout.count();
        // 上游：let frames = (rate as f32 * secs) as usize —— f32 乘法后向零截断
        int frames = (int) ((float) rate * secs);
        int dataLen = frames * ch * 2; // 16-bit

        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);                     // PCM
        buf.putShort((short) ch);
        buf.putInt(rate);
        buf.putInt(rate * ch * 2);
        buf.putShort((short) (ch * 2));
        buf.putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataLen);
        for (int n = 0; n < frames; n++) {
            for (int c = 0; c < ch; c++) {
                // 上游是 f32 算术 + f32 的 sin：这里先把相位算成 float，
                // 再走 Math.sin（double 实现）并立刻窄化回 float。
                float t = (float) n / (float) rate;
                float freq = 220.0f + (float) c * 110.0f;
                float v = (float) Math.sin((double) (2.0f * PI_F32 * freq * t)) * 0.3f;
                // Rust 的 `as i16` 是向零截断，Java 的 (short) 转换同样是截断
                buf.putShort((short) (v * 32767.0f));
            }
        }

        try {
            Path path = Files.createTempFile("sonalis-deck-test-" + ch + "-", ".wav");
            Files.write(path, buf.array());
            tempFiles.add(path);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("造不出测试 WAV", e);
        }
    }

    /** {@code f32::is_finite} 的镜像。不能用 {@code Float.isFinite}：那是安卓 API 24+。 */
    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /**
     * 极简可增长 float 缓冲：把每块的 {@code channel(0)} 接起来。
     * 对应上游测试里的 {@code let mut expect: Vec<f32>} 与 {@code extend_from_slice}。
     */
    private static final class FloatAcc {
        float[] data = new float[1024];
        int len;

        void add(FloatBuffer src, int n) {
            if (len + n > data.length) {
                int cap = data.length;
                while (cap < len + n) {
                    cap <<= 1;
                }
                data = Arrays.copyOf(data, cap);
            }
            for (int i = 0; i < n; i++) {
                data[len + i] = src.get(i);
            }
            len += n;
        }
    }

    // ==================================================================
    // 上游 9 条
    // ==================================================================

    /** 上游 {@code missing_file_is_reported_not_panicked}：{@code r.is_err()} → 抛异常。 */
    @Test
    public void missingFileIsReportedNotPanicked() {
        try {
            Deck d = Deck.open("definitely-not-here.wav", RATE, 2);
            fail("不存在的文件应当报错，不该静默返回：" + d);
        } catch (AudioException expected) {
            assertTrue("错误应当是「打不开音源」这一类：" + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().length() > 0);
        }
    }

    /** 上游 {@code copy_frames_into_places_at_offset}。 */
    @Test
    public void copyFramesIntoPlacesAtOffset() {
        AudioBuf src = new AudioBuf(ChannelLayout.MONO, 4);
        src.setFrames(4);
        FloatBuffer sw = src.channelWriter(0);
        sw.put(0, 1.0f);
        sw.put(1, 2.0f);
        sw.put(2, 3.0f);
        sw.put(3, 4.0f);

        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 8);
        dst.setFrames(8);
        Deck.copyFramesInto(src, dst, 2, 3);

        FloatBuffer d = dst.channel(0);
        assertEquals("偏移之前不该被写入", 0.0f, d.get(0), 0f);
        assertEquals(1.0f, d.get(2), 0f);
        assertEquals(2.0f, d.get(3), 0f);
        assertEquals(3.0f, d.get(4), 0f);
        assertEquals("n 帧之后不该被写入", 0.0f, d.get(5), 0f);
        assertEquals("搬运不该改变有效帧数（长度由调用方最后统一 setFrames）",
                8, dst.frames());
    }

    /** 上游 {@code copy_frames_into_handles_zero}：只是不该 panic。 */
    @Test
    public void copyFramesIntoHandlesZero() {
        AudioBuf src = new AudioBuf(ChannelLayout.MONO, 4);
        AudioBuf dst = new AudioBuf(ChannelLayout.MONO, 4);
        Deck.copyFramesInto(src, dst, 0, 0);
        assertEquals("n == 0 时不该动任何东西", 0, dst.frames());
    }

    /**
     * 变调器的环形缓冲必须容得下「一次 render 的产出 + 自身前视」，
     * 否则 {@code render} 里那个 break 会被走到、进而静默丢帧。
     * 这条用算术钉住参数关系，避免日后有人调小 {@link Deck#PITCH_CHUNK_FACTOR}。
     */
    @Test
    public void pitchRingIsLargeEnoughForOneRender() {
        int rate = RATE;
        int chunk = Deck.RENDER_FRAMES * Deck.PITCH_CHUNK_FACTOR;
        PitchShifter ps = new PitchShifter(2, rate, 3.0f, chunk);

        // 一条 deck 在一次 render 里最多喂 RENDER_FRAMES 帧
        int need = ps.latencyFrames() + Deck.RENDER_FRAMES;
        assertTrue("空载时应当一次能收下整块：可用 " + ps.freeFrames() + " < " + Deck.RENDER_FRAMES,
                ps.freeFrames() >= Deck.RENDER_FRAMES);
        assertTrue("算术上界 " + need + " 应当成立",
                need < ps.latencyFrames() + Deck.RENDER_FRAMES + 1);
    }

    /**
     * 空间音频：开关必须如实反映到透传状态上。
     *
     * <p>开着时渲染器在改写采样值，不能再声称位完美；
     * 关掉后（旁路）一个采样都不经过，透传状态应当恢复。
     */
    @Test
    public void spatialSwitchReportsPassthroughHonestly() {
        Path wav = testWav(ChannelLayout.STEREO, 2.0f);
        Deck d = Deck.open(wav.toString(), RATE, 2);
        assertTrue("刚打开应当透传", d.isPassthrough());

        d.setSpatial(true);
        assertTrue("立体声输出应当挂上渲染器", d.spatialIsActive());
        assertFalse("渲染器在干活，不能声称透传", d.isPassthrough());

        d.setSpatial(false);
        assertFalse("关掉后不该在干活", d.spatialIsActive());
        assertTrue("旁路时不碰采样值，透传恢复", d.isPassthrough());

        d.close();
    }

    /**
     * 非立体声输出时开启空间音频必须是 no-op：
     * 接 5.1 功放应当直通，而不是先折叠再送出去。
     */
    @Test
    public void spatialIsNoopForMultichannelOutput() {
        Path wav = testWav(ChannelLayout.STEREO, 1.0f);
        Deck d = Deck.open(wav.toString(), RATE, 6);
        d.setSpatial(true);
        assertFalse("5.1 输出不该挂渲染器", d.spatialIsActive());
        assertTrue("没有渲染器在干活", d.isPassthrough());
        d.close();
    }

    /**
     * <strong>改块长不能动内容。</strong>
     *
     * <p>攒块的改动只该改变「一次交付多少帧」，一个采样都不该多、不该少、
     * 也不该变。守它的办法是拿<strong>直通</strong>做基准：48 kHz → 48 kHz、增益处于
     * 单位状态时，deck 的产出必须与直接解码出来的采样<strong>逐帧相同</strong>。
     *
     * <p>这条是「攒块」这个改动的安全带：多拉一次、把余量搬进 carry、
     * 下一块再搬出来——任何一处写错都会在这里变成一个可数的差值，
     * 而不是一段谁也没听见的缺失（那正是本项目最不能接受的一类缺陷）。
     */
    @Test
    public void fullBlocksDoNotChangeTheSamples() {
        Path wav = testWav(ChannelLayout.STEREO, 1.0f);

        // 基准：直接解码一遍
        Decoder dec = Decoder.open(wav.toString());
        AudioBuf decBuf = new AudioBuf(dec.spec().layout, 65_536);
        FloatAcc expect = new FloatAcc();
        while (true) {
            int n = dec.decodeInto(decBuf);
            if (n == 0) {
                break;
            }
            expect.add(decBuf.channel(0), n);
        }
        dec.close();
        assertTrue("基准太短，说明不了问题（" + expect.len + " 帧）", expect.len > 10_000);

        // 实测：走 deck
        Deck d = Deck.open(wav.toString(), RATE, 2);
        assertTrue("48k→48k 应当是直通，这条测试才算数", d.isPassthrough());
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        FloatAcc got = new FloatAcc();
        while (true) {
            int n = d.render(out);
            if (n == 0) {
                break;
            }
            got.add(out.channel(0), n);
        }
        d.close();

        // 末尾也一帧都不许再少：重采样器每算一个输出点都要看到它后面 TAPS_PER_SIDE 帧
        // 「未来输入」，而流末尾没有未来了——这一段以前是被**静默丢掉**的（正好 16 帧）。
        // 现在 {@code Deck.render} 在解码器耗尽且 pull 拉不出东西时调一次
        // {@code Resampler#drain} 收尾（同采样率下补出来的尾帧逐位精确），所以这里要求
        // 严格相等：这条断言在收尾那一步缺失时必红。
        assertTrue("deck 产出的帧数（" + got.len + "）比源（" + expect.len + "）还多——重复了",
                got.len <= expect.len);
        assertEquals("产出总帧数必须与源严格相等：丢掉最后 16 帧就是这条红的",
                0, expect.len - got.len);
        // 容差 1e-6：直通并不是**逐位**相同——重采样器的核在相位 0 处是
        // 近似 delta（上游实测第一点差 4e-19），这是它的实现细节，不是这次改动的
        // 事。真正要守的是「错位与丢帧」：那会让差值直接跳到信号本身的量级。
        // （同采样率下「零容差」的那条断言在
        // {@code deckProducesEverySourceFrameAtTheSameRate} 里，这里保持原来的强度。）
        for (int i = 0; i < got.len; i++) {
            float a = got.data[i];
            float b = expect.data[i];
            assertTrue("第 " + i + " 个采样对不上：deck 给出 " + a + "，源是 " + b
                    + "——内容被改动或错位了", Math.abs(a - b) <= 1e-6f);
        }
    }

    /**
     * <strong>每一次 render 都必须交付一整块</strong>（只有尾块可以短）。
     *
     * <p>这条守的是用户报的那条听感缺陷：「融合阶段声音会时不时顿一下」。
     *
     * <p>块长原先由「重采样器历史里攒了多少」决定，而历史一次只吃得下<strong>一个
     * 解码包</strong>：48 kHz 源、44.1 kHz 输出时 pull 时不时只给出 2867 帧而不是
     * 4096（上游实测 173 块里 23 块如此）。单条 deck 出短块<strong>完全听不出来</strong>
     * （短块只是短块，音频一帧不少），所以它一直没被发现；过渡窗口里却会
     * 变成一个洞——{@link Mixer#mixTwo} 把短的那一路的尾巴当作「这一路已经结束」
     * 补零，于是每短一次就少掉 1229 帧 ≈ 28 ms 的声音。
     *
     * <p>判据只用块长，与机器快慢无关：源采样率与输出采样率不同是<strong>触发条件</strong>，
     * 而两者都是测试自己定的。
     */
    @Test
    public void renderAlwaysDeliversAFullBlock() {
        // 48 kHz 的素材、44.1 kHz 的输出——这一步正是让 pull 受历史长度限制
        // 的那个条件。两者相同（透传）时这个缺陷一个都不露。
        Path wav = testWav(ChannelLayout.STEREO, 3.0f);
        Deck d = Deck.open(wav.toString(), 44_100, 2);

        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        List<Integer> sizes = new ArrayList<Integer>();
        while (true) {
            int n = d.render(out);
            if (n == 0) {
                break;
            }
            sizes.add(Integer.valueOf(n));
        }
        d.close();

        assertTrue("产出太少，说明不了问题：" + sizes, sizes.size() >= 4);
        // 只有**最后一块**可以短——那是尾音，再往后就没有源了
        int last = sizes.get(sizes.size() - 1).intValue();
        for (int i = 0; i < sizes.size() - 1; i++) {
            assertEquals("第 " + i + " 块只交付了 " + sizes.get(i) + " 帧（应为 "
                            + Deck.RENDER_FRAMES + "）——块长随解码包起伏，"
                            + "过渡期两条 deck 一起混音时就会变成一个洞",
                    Deck.RENDER_FRAMES, sizes.get(i).intValue());
        }
        assertTrue("尾块长度 " + last + " 不该超过一整块，也不该是 0（那是「已结束」的意思）",
                last > 0 && last <= Deck.RENDER_FRAMES);
    }

    /**
     * <strong>挂着变调器时块长同样必须恒为一整块。</strong>
     *
     * <p>「声波重合」把变调器挂在<strong>出场 deck</strong> 上，于是这条 deck 的产出
     * 成了过渡窗口里的一路。变调器是流式的：它一次能吐出多少取决于手上有多少输入，
     * 又总扣着 {@code (grain−1)·ratio} 帧前视不放，所以块长会随解码包起伏。
     *
     * <p><strong>实测（48 kHz 源 → 44.1 kHz 输出、10 秒窗口 108 块）：</strong>
     * 修复前 8 块偏短（2048/2560/3072/3584），{@link Mixer#mixTwo} 每次都把短的那一段
     * 补零 —— 合计 <strong>10752 帧 ≈ 244 ms 的静音洞</strong>，每隔几块就断一下。
     * 听感正是用户报的「过渡的时候音乐又糊」。修复后同一场景 0 块偏短、0 帧补零。
     *
     * <p>判据只用块长，与机器快慢无关；触发条件（源 48 kHz、输出 44.1 kHz）
     * 是测试自己定的，所以它不会变成一条「有时过有时不过」的测试。
     */
    @Test
    public void pitchPathAlsoDeliversFullBlocks() {
        Path wav = testWav(ChannelLayout.STEREO, 3.0f);
        Deck d = Deck.open(wav.toString(), 44_100, 2);
        // 与真实路径一致：准备线程造好，以单位比例挂上
        d.engagePitchWith(Deck.idlePitchShifter(44_100, 2));

        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        List<Integer> sizes = new ArrayList<Integer>();
        while (true) {
            int n = d.render(out);
            if (n == 0) {
                break;
            }
            // 每轮都按「声波重合」的曲线改一次变调量（它有滑入段，是连续变化的）
            d.setPitch(2.0f);
            sizes.add(Integer.valueOf(n));
        }
        d.close();

        assertTrue("产出太少，说明不了问题：" + sizes, sizes.size() >= 4);
        int last = sizes.get(sizes.size() - 1).intValue();
        for (int i = 0; i < sizes.size() - 1; i++) {
            assertEquals("第 " + i + " 块只交付了 " + sizes.get(i) + " 帧（应为 "
                            + Deck.RENDER_FRAMES + "）——变调器扣着前视不放，块长随之起伏，"
                            + "过渡窗口里这就是一个静音洞（实测 10 秒窗口丢了 244 ms）",
                    Deck.RENDER_FRAMES, sizes.get(i).intValue());
        }
        assertTrue("尾块长度 " + last + " 不该超过一整块，也不该是 0（那是「已结束」的意思）",
                last > 0 && last <= Deck.RENDER_FRAMES);
    }

    /**
     * <strong>挂着变调器的 deck 不能丢尾。</strong>
     *
     * <p>变调器要读到 {@code base + (grain−1)·ratio} 才吐一帧，而流末尾根本没有那些
     * 「未来的输入」。不收尾的话那一段内容就永远留在它的环形缓冲里——
     * 静默丢尾，与 {@code Resampler} 少最后 16 帧同一类问题。
     *
     * <p>判据是<strong>产出总帧数与源帧数一致</strong>（容差只留给最后那段淡出：
     * 未满 4 层重叠的粒窗和小于 1，尾巴上能少几帧）。
     */
    @Test
    public void pitchPathDoesNotDropTheTail() {
        Path wav = testWav(ChannelLayout.STEREO, 3.0f);
        int expected = (int) ((float) RATE * 3.0f * 44_100f / (float) RATE);

        Deck plain = Deck.open(wav.toString(), 44_100, 2);
        Deck shifted = Deck.open(wav.toString(), 44_100, 2);
        shifted.setPitch(3.0f);

        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        long plainTotal = 0;
        long shiftedTotal = 0;
        while (true) {
            int n = plain.render(out);
            if (n == 0) {
                break;
            }
            plainTotal += n;
        }
        while (true) {
            int n = shifted.render(out);
            if (n == 0) {
                break;
            }
            shiftedTotal += n;
        }
        plain.close();
        shifted.close();

        assertTrue("基准 deck 本身就短了太多：" + plainTotal + " vs " + expected,
                Math.abs(plainTotal - expected) <= 64);
        assertTrue("变调后的 deck 比基准短了 " + (plainTotal - shiftedTotal)
                        + " 帧（" + plainTotal + " vs " + shiftedTotal + "）——变调器扣着的前视"
                        + "没有在流末尾吐出来，那就是静默丢尾",
                plainTotal - shiftedTotal <= 64);
    }

    /**
     * 渲染器开着时 deck 必须能正常出帧（单声道源折叠成立体声），
     * 且不能有 NaN——延迟线与滤波器一旦不稳定最先表现就是这个。
     */
    @Test
    public void spatialRenderProducesFiniteFrames() {
        Path wav = testWav(ChannelLayout.MONO, 3.0f);
        Deck d = Deck.open(wav.toString(), RATE, 2);
        d.setSpatial(true);

        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        int total = 0;
        for (int r = 0; r < 8; r++) {
            int n = d.render(out);
            total += n;
            for (int ch = 0; ch < out.channels(); ch++) {
                for (int i = 0; i < n; i++) {
                    float v = out.channel(ch).get(i);
                    assertTrue("渲染输出了非有限值：" + v, isFinite(v));
                }
            }
            if (n == 0) {
                break;
            }
        }
        d.close();
        assertTrue("开了渲染器之后一帧都出不来", total > 0);
    }

    // ==================================================================
    // 本地补充（4 条）
    // ==================================================================

    /**
     * 本地补充：<strong>过渡开始那一刻，生产者线程不做 IO</strong>——这是 {@link Deck}
     * 类注释里的第二条架构纪律，上游靠 {@code cfg(test)} 的重活探针守它，Java 侧
     * 靠这里的替身（记下开了几次音源、定位了几次、分别在哪条线程上）。
     *
     * <p>守的是一条真实修过的缺陷：造变调器要分配环形缓冲、铺粒窗、算粒长，
     * 而这曾经发生在<strong>开始过渡的那一瞬间</strong>、在生产者线程上——
     * 那正是用户听到的「开始过渡时卡一下」的其中一份。现在的分工是
     * {@link Deck#idlePitchShifter} 在准备线程上造、{@link Deck#engagePitchWith}
     * 在过渡开始时只交出所有权。
     *
     * <p>额外覆盖一条 Java 特有的性质：变调器在<strong>另一条线程上造好、在生产者
     * 线程上装上去</strong>。上游的 {@code Deck: Send} 在类型系统上保证了这件事，
     * Java 表达不了，只能在这里跑一遍（{@code Thread.join} 提供 happens-before）。
     */
    @Test
    public void prebuiltPitchShifterIsInstalledWithoutIo() throws InterruptedException {
        Path wav = testWav(ChannelLayout.STEREO, 1.0f);
        Deck d = Deck.open(wav.toString(), RATE, 2);
        assertTrue("打开时应当是透传", d.isPassthrough());
        assertEquals("打开 deck 应当只开了一次音源", 1, opener.openedOn.size());

        // 准备线程：提前造好变调器（这一步是重活，必须不在过渡开始的那一瞬间）
        final PitchShifter[] holder = new PitchShifter[1];
        Thread prepare = new Thread(new Runnable() {
            @Override
            public void run() {
                holder[0] = Deck.idlePitchShifter(RATE, 2);
            }
        }, "sonalis-prepare");
        prepare.start();
        prepare.join();
        PitchShifter prebuilt = holder[0];
        assertTrue("造出来的是单位比例（后续由 setPitch 连续调整）", prebuilt.isUnity());
        // 先把变调量设好：这样「装上的就是同一个实例」这件事才是可观测的
        // （Deck 从不重造变调器，装上的是谁，报出来的变调量就是谁的）
        prebuilt.setSemitones(3.0f);

        int opensBefore = opener.openedOn.size();
        int seeksBefore = opener.soughtOn.size();

        // 过渡开始的那一瞬间
        d.engagePitchWith(prebuilt);

        assertEquals("过渡开始这一刻不该再开音源", opensBefore, opener.openedOn.size());
        assertEquals("过渡开始这一刻不该再定位", seeksBefore, opener.soughtOn.size());
        assertFalse("挂上变调器后不能再声称位完美", d.isPassthrough());
        assertTrue("装上的必须是准备线程造好的那一个实例，实际变调量 "
                        + d.pitchSemitones(),
                Math.abs(d.pitchSemitones() - 3.0f) < 1e-3f);
        d.close();
    }

    /**
     * 本地补充：{@link Deck#setPitch} 的两条刻意语义（上游没有对应测试，但它们
     * 是位完美与「听感无咔哒」的前提）：
     *
     * <ol>
     *   <li>没挂变调器、又不需要变调（|semitones| &lt; 1e-6）→ <strong>什么都不做</strong>，
     *       保持位完美（不是「挂一个 1:1 的变调器」）；</li>
     *   <li>从零变到非零时挂上；<strong>反过来不会拆掉</strong>——拆掉等于把缓冲里
     *       那几十毫秒的音频丢掉，听感是一声咔哒。</li>
     * </ol>
     */
    @Test
    public void setPitchEngagesOnceAndIsNotRemovedByZero() {
        Path wav = testWav(ChannelLayout.STEREO, 0.5f);
        Deck d = Deck.open(wav.toString(), RATE, 2);
        assertTrue("刚打开应当透传", d.isPassthrough());

        // 1) 零变调 + 没挂载 → 一个采样都不碰，仍然位完美
        d.setPitch(0.0f);
        assertTrue("零变调且未挂载时该保持位完美（不该挂一个 1:1 空转的变调器）",
                d.isPassthrough());
        assertEquals("没挂变调器时变调量报 0", 0.0f, d.pitchSemitones(), 0f);

        d.setPitch(3.0f);
        assertFalse("非零变调必须挂上变调器", d.isPassthrough());
        assertTrue("变调量应当生效，实际 " + d.pitchSemitones(),
                Math.abs(d.pitchSemitones() - 3.0f) < 1e-3f);

        // 2) 回零不拆
        d.setPitch(0.0f);
        assertFalse("变调器一旦挂上就不再拆掉（拆掉等于丢掉几十毫秒的前视）",
                d.isPassthrough());
        assertTrue("回零后变调量应当确实是 0，实际 " + d.pitchSemitones(),
                Math.abs(d.pitchSemitones()) < 1e-6f);
        d.close();
    }

    /**
     * 本地补充：<strong>攒块路径（carry）必须一帧都不丢</strong>——上游那条
     * {@code full_blocks_do_not_change_the_samples} 的覆盖盲区就在这里。
     *
     * <p>上游拿 48 kHz → 48 kHz 的<strong>直通</strong>做基准，好处是逐帧对比可以
     * 零容差；但直通时 {@code pull} 每次恰好产出一整块，{@code extra} 恒为 0，
     * 于是<strong>根本没走到 carry</strong>。而 carry 恰恰是「攒块」这个改动里最容易
     * 写错的一部分：余量要<strong>按偏移</strong>搬进去（它不在源的开头，而在已经交付
     * 掉的那一段之后），下一块还要整份搬出来。
     *
     * <p>所以这里改用 48 kHz → 44.1 kHz：这一步才让 {@code pull} 时不时只给出 2867 帧
     * 而不是 4096，于是 carry 必然被走到。基准是一条<strong>独立的重采样器</strong>——
     * 这条链路上只有重采样器会改采样值（立体声的声道映射是逐样本拷贝、unity 增益直接
     * 返回），所以基准与 deck 的产出应当<strong>逐帧相同</strong>，容差为 0。
     */
    @Test
    public void carryPathLosesNoSamplesWhenRatesDiffer() {
        Path wav = testWav(ChannelLayout.STEREO, 3.0f);

        // 基准素材：整段解出来（对应上游那条测试里的「直接解码一遍」）
        Decoder dec = Decoder.open(wav.toString());
        AudioBuf chunk = new AudioBuf(ChannelLayout.STEREO, 65_536);
        AudioBuf all = new AudioBuf(ChannelLayout.STEREO, 3 * RATE + 8);
        while (true) {
            int n = dec.decodeInto(chunk);
            if (n == 0) {
                break;
            }
            all.append(chunk);
        }
        dec.close();
        assertEquals("素材应当整段解出", 3 * RATE, all.frames());

        // 基准：同一份素材过一条独立的重采样器，拉干净为止
        Resampler rs = new Resampler(RATE, 44_100, 2);
        AudioBuf tmp = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        FloatAcc ref = new FloatAcc();
        int off = 0;
        while (off < all.frames()) {
            off += rs.pushFrom(all, off);
            // 注意顺序：channel(0) 的长度是**当时的**有效帧数，必须在 pull 之后再取视图
            int n = rs.pull(tmp);
            ref.add(tmp.channel(0), n);
        }
        while (true) {
            int n = rs.pull(tmp);
            if (n == 0) {
                break;
            }
            ref.add(tmp.channel(0), n);
        }
        // 基准的流末尾也要收尾：deck 现在会调 {@code Resampler#drain} 把最后那几帧交出来
        // （修的是「每首曲子丢掉最后 16 帧」）。基准若停在 pull 上就会比 deck 短，
        // 于是这条「一帧不差」的比较会报成「deck 比基准长」——那不是 deck 的缺陷，
        // 是基准少走了同一步。
        int refTail = rs.drain(tmp);
        if (refTail > 0) {
            ref.add(tmp.channel(0), refTail);
        }

        // 实测：走 deck（同一条链路的其余环节都不改采样值）
        Deck d = Deck.open(wav.toString(), 44_100, 2);
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        FloatAcc got = new FloatAcc();
        while (true) {
            int n = d.render(out);
            if (n == 0) {
                break;
            }
            got.add(out.channel(0), n);
        }
        d.close();

        assertTrue("基准太短，说明不了问题（" + ref.len + " 帧）", ref.len > 100_000);
        assertEquals("攒块是一条搬运路径，一帧都不该多、不该少（这条真正压到 carry）",
                ref.len, got.len);
        for (int i = 0; i < got.len; i++) {
            assertEquals("第 " + i + " 个采样对不上：deck 给出 " + got.data[i]
                    + "，基准是 " + ref.data[i] + "——carry 的偏移或交接写错了", ref.data[i],
                    got.data[i], 0f);
        }
    }

    /**
     * 本地补充：{@link Deck#seek} 的清算与记账。
     *
     * <p>三件事：变调器被<strong>摘掉</strong>（跳转后转调的意义就没了，而重新挂上
     * 要重新积累前视）、进度按<strong>实际落点</strong>记账（用请求值会让界面上的进度条
     * 在松手后跳回去）、以及 {@link Deck#remainingFrames} 必须拿已产出帧数去算
     * （用解码器的 {@code framesDecoded} 会算出「快结束了」而提前触发过渡，
     * 上游实测在 1.56 s 处触发、而正确位置是 2.5 s）。
     */
    @Test
    public void seekDetachesPitchAndAccountsFromTheActualLanding() {
        Path wav = testWav(ChannelLayout.STEREO, 2.0f);
        Deck d = Deck.open(wav.toString(), RATE, 2);
        d.setPitch(2.0f);
        assertFalse("跳转前变调器在链上", d.isPassthrough());

        double actual = d.seek(1.0);
        assertEquals("替身的定位是精确的", 1.0, actual, 1e-9);
        assertTrue("跳转后变调器应当被摘掉（转调在跳转后没有意义）", d.isPassthrough());
        assertEquals("摘掉后变调量报 0", 0.0f, d.pitchSemitones(), 0f);
        assertEquals("进度必须按实际落点记账", (long) (actual * (double) RATE), d.outFrames());
        assertEquals("剩余帧数要拿已产出帧数去算", Long.valueOf(2L * RATE - d.outFrames()),
                d.remainingFrames());
        d.close();
    }

    /**
     * 本地补充（<strong>上游缺的正是这一条</strong>）：产出总帧数必须与
     * {@code spec.totalFrames} <strong>严格相等</strong>，且末端逐位相同。
     *
     * <p>上游为什么没发现这个缺陷：{@code deck.rs} 的测试里<strong>没有任何一条</strong>把
     * 「产出总帧数」与曲目总长对齐——它们只比中段内容对不对，而
     * {@code full_blocks_do_not_change_the_samples} 那类还刻意留了「末尾允许少一个核」的
     * 余量。于是「每首曲子丢掉最后 16 帧」在上游一路活到今天：{@code pull} 每算一个输出点
     * 都要看到它前后各 {@code TAPS_PER_SIDE} 帧输入，而流末尾没有「未来」了。
     * 用户侧的表现是进度条永远停在倒数第 16 帧（曲子 500 ms、位置卡在 499 ms），
     * gapless 接缝整体早 0.33 ms。
     *
     * <p>断言分三层，都不会被「差不多」蒙过去：
     *
     * <ol>
     *   <li><strong>帧数</strong>：与 {@code spec.totalFrames} 严格相等（整数比较，
     *       零容差，差一帧即红）；</li>
     *   <li><strong>采样值</strong>：与「直接解码同一份文件」的内容逐帧比对，非零样本
     *       容差 {@code 0f}（同采样率下相位 0 的核精确等于 delta，补零收尾不碰任何真实
     *       采样）；素材里<strong>恰好为 0</strong> 的样本另有上界断言，理由见方法内注释
     *       （与收尾无关，是核本身的旁瓣泄漏，正数样本上会被舍入吸收）；</li>
     *   <li><strong>末尾 {@code TAPS_PER_SIDE} 帧单独再钉一次逐位相同</strong>——
     *       就是这次修复补出来的那 16 帧。</li>
     * </ol>
     */
    @Test
    public void deckProducesEverySourceFrameAtTheSameRate() {
        Path wav = testWav(ChannelLayout.STEREO, 1.0f);

        // 基准：直接解码一遍，拿到曲长与内容
        Decoder dec = Decoder.open(wav.toString());
        Long total = dec.spec().totalFrames;
        assertTrue("替身应当报得出总帧数", total != null);
        AudioBuf decBuf = new AudioBuf(dec.spec().layout, 65_536);
        FloatAcc expect = new FloatAcc();
        while (true) {
            int n = dec.decodeInto(decBuf);
            if (n == 0) {
                break;
            }
            expect.add(decBuf.channel(0), n);
        }
        dec.close();
        assertEquals("基准素材应当正好是曲长", total.longValue(), (long) expect.len);

        // 实测：走 deck
        Deck d = Deck.open(wav.toString(), RATE, 2);
        assertTrue("48k→48k 应当是直通，这条的零容差才有意义", d.isPassthrough());
        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        FloatAcc got = new FloatAcc();
        while (true) {
            int n = d.render(out);
            if (n == 0) {
                break;
            }
            got.add(out.channel(0), n);
        }

        assertEquals("产出总帧数必须与曲目总长严格相等（丢最后 16 帧就是这条红的）",
                total.longValue(), (long) got.len);
        assertEquals("收尾之后不该再有剩余帧（修复前这里永远停在 16）",
                Long.valueOf(0L), d.remainingFrames());
        assertEquals("已产出帧数的记账也要对上", total.longValue(), d.outFrames());
        assertTrue("解码器应当已耗尽", d.isDrained());
        assertEquals("曲终之后 render 必须返回 0，收尾逻辑不许再多吐一帧", 0, d.render(out));

        // 采样值：逐帧与「直接解码出来的源」比对，**非零样本一律零容差**。
        //
        // 跳过开头 TAPS_PER_SIDE 帧：那是插值核的前端还没有历史的那一段
        // （pos < TAPS_PER_SIDE - 1 时窗口伸到流开始之前），头部另有断言（见本方法末尾）。
        //
        // 素材里恰好等于 0 的样本要单独说清楚：相位 0 的核并不是**精确** delta，
        // 旁瓣量级 ≤ 4e-17（见 ResamplerTest.phaseZeroKernelIsExactDelta）。只要样本本身
        // 不为 0，这些项加进去会被舍入吸收（ulp(0.0086) = 9e-10），逐位结果不变；
        // 而素材是 16 bit 量化的正弦，**落在零点上的样本恰好等于 0**（sin(0)、每个过零点
        // 附近量化到 0 的那些），此时旁瓣不再被吸收，输出是 ~1e-26 而不是 0.0。
        // 这是核本身的性质（与本次收尾无关，上游也这样），所以对这类样本改成断言
        // 「仍在核的泄漏上界之内」——上界 1e-15 约等于满量程的 -320 dBFS，
        // 与「丢帧」的量级（差一整段信号）差了十几个数量级，不可能掩护任何真缺陷。
        for (int i = RESAMPLER_TAPS_PER_SIDE; i < got.len; i++) {
            if (expect.data[i] == 0f) {
                assertTrue("第 " + i + " 个样本源值是 0，deck 却给出了 " + got.data[i]
                                + "——超出相位 0 核的泄漏上界（1e-15）",
                        Math.abs(got.data[i]) <= 1e-15f);
                continue;
            }
            assertEquals("第 " + i + " 个采样被改变了（同采样率下应当逐位相同）",
                    expect.data[i], got.data[i], 0f);
        }

        // 末尾 TAPS_PER_SIDE 帧是本次修复的重点，单独再钉一次**逐位相同**：
        // 以前这 16 帧**根本不存在**（deck 一帧都不产出），现在它们必须是源的最后
        // 16 个采样本身，一个比特都不许差。
        // 先确认这一段素材里没有「恰好为 0」的样本——否则上面的零样本规则会接手，
        // 这条断言就不再是逐位比较了；有的话这里会先红，那种情况下就该换素材而不是放宽。
        for (int i = got.len - RESAMPLER_TAPS_PER_SIDE; i < got.len; i++) {
            assertTrue("尾部素材第 " + i + " 个采样恰好是 0，这条逐位断言失去意义",
                    expect.data[i] != 0f);
            assertEquals("尾帧 " + i + " 不是源采样的逐位拷贝（收尾必须补零插值，"
                            + "不能改动任何一个真实采样）",
                    expect.data[i], got.data[i], 0f);
        }

        // 头部那几帧的偏差也必须小到不可能是「丢帧 / 错位」——丢一帧会让后面整段错位，
        // 偏差量级是信号本身（0.3 上下），而这里量到的是 4.4e-19。
        float headMax = 0f;
        for (int i = 0; i < RESAMPLER_TAPS_PER_SIDE && i < got.len; i++) {
            headMax = Math.max(headMax, Math.abs(got.data[i] - expect.data[i]));
        }
        assertTrue("头部偏差 " + headMax + " 太大——那说明是真丢帧/错位，不是核边缘的舍入",
                headMax <= 1e-15f);
        d.close();
    }

    /**
     * 本地补充：<strong>跨采样率时的产出总帧数</strong>。
     *
     * <p>同采样率那条能要求「一帧不差」，因为它有精确的整数关系；跨采样率只有
     * {@code 理论值 = totalFrames × outRate / inRate} 这个实数关系，所以这里钉的是一个
     * <strong>有来源的区间</strong>，而不是「差不多就行」：
     *
     * <ul>
     *   <li><strong>下界 {@code ≥ 理论值} 不是容差，是数学下界</strong>：输入范围
     *       {@code [0, N)} 内的输出点数就是 {@code ceil(N × outRate / inRate)}。
     *       少一帧都说明末尾那几帧没被交出来——修复前实测 132286 帧（少了 14 帧，
     *       正好是这一段末尾缺的那几个输出点），
     *       所以这条断言在修复前必红，它钉的正是「末尾到底补没补」。</li>
     *   <li><strong>上界 {@code ≤ 理论值 + TAPS_PER_SIDE}</strong>：输出点的存在性判据是
     *       半开区间 {@code pos < histLen}，而 {@code pos} 是一步步 {@code += step} 累加
     *       出来的 double，累加舍入决定最后一个输出点落在界线哪一侧（本机实测比理论值多
     *       1 帧）；不同 libm 的 ulp 级差异最多再挪一帧。留一个核（16 帧 ≈ 0.33 ms）的
     *       宽度只为让这条断言与浮点实现无关——真丢帧的量级是修复前那个 −14，写坏了会是
     *       上千，离这个上界差得远，所以断言没有因此变弱。</li>
     * </ul>
     *
     * <p>末尾缺失的输入由 {@code Resampler#drain} 按静音补齐，这是<strong>有意</strong>的：
     * 拿不到的输入不能凭空造，末尾约 0.33 ms 的淡出比把波形截成硬台阶更接近真实信号
     * （理由写在那个方法的 javadoc 里）。
     */
    @Test
    public void deckTailIsFlushedWhenRatesDiffer() {
        Path wav = testWav(ChannelLayout.STEREO, 3.0f);
        Deck d = Deck.open(wav.toString(), 44_100, 2);
        Long totalSrc = d.spec().totalFrames;
        assertTrue("替身应当报得出总帧数", totalSrc != null);

        AudioBuf out = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        long got = 0L;
        while (true) {
            int n = d.render(out);
            if (n == 0) {
                break;
            }
            got += (long) n;
        }

        double ideal = (double) totalSrc.longValue() * 44_100.0 / (double) RATE;
        assertTrue("总帧数 " + got + " 少于理论值 " + ideal
                        + "——末尾那几帧没被交出来（修复前实测 132286，少 14 帧）",
                (double) got >= ideal);
        assertTrue("总帧数 " + got + " 超过理论值 + 一个核 "
                        + (ideal + RESAMPLER_TAPS_PER_SIDE)
                        + "——收尾多产出了输出点",
                (double) got <= ideal + (double) RESAMPLER_TAPS_PER_SIDE);
        assertEquals("已产出帧数的记账要能对上渲染出的总量", got, d.outFrames());
        assertEquals("曲终之后不该再有剩余帧（修复前这里停在 16）",
                Long.valueOf(0L), d.remainingFrames());
        assertTrue("解码器应当已耗尽", d.isDrained());
        assertEquals("曲终之后 render 必须返回 0", 0, d.render(out));
        d.close();
    }

    // ==================================================================
    // 测试替身：一个只认 16 bit PCM WAV 的解码器（带探针）
    // ==================================================================

    /**
     * 测试用的工厂。除了返回替身之外，还记下每次<strong>开音源 / 定位发生在哪条线程上</strong>——
     * 这是 Java 侧对上游 {@code cfg(test)} 重活探针的替代（见类注释）。
     */
    private static final class WavOpener implements Decoder.Opener {

        /** 每次 {@link #open} 落在哪条线程上（按调用顺序）。 */
        final List<String> openedOn = new ArrayList<String>();

        /** 每次 {@code seek} 落在哪条线程上（按调用顺序）。 */
        final List<String> soughtOn = new ArrayList<String>();

        @Override
        public Decoder open(String source, Map<String, String> headers) {
            WavDecoder d = WavDecoder.open(source, this);
            openedOn.add(Thread.currentThread().getName());
            return d;
        }
    }

    /**
     * 只认 16 bit PCM WAV 的最小解码器<strong>替身</strong>（测试用，不是产品代码）。
     *
     * <p>产品路径上 {@code Decoder.open} 打不开音源时会抛 {@link AudioException}，
     * 这里对不存在的文件给出同样的行为——{@code Deck::open} 那条「报错而不是 panic」
     * 的测试正是靠这一点才测得到。
     */
    private static final class WavDecoder implements Decoder {

        private final byte[] file;
        private final int dataOff;
        private final int dataLen;
        private final int rate;
        private final int channels;
        private final int bytesPerSample;
        private final int frames;
        private final WavOpener probe;
        private int pos;

        private WavDecoder(byte[] file, int dataOff, int dataLen, int rate, int channels,
                           int bytesPerSample, WavOpener probe) {
            this.file = file;
            this.dataOff = dataOff;
            this.dataLen = dataLen;
            this.rate = rate;
            this.channels = channels;
            this.bytesPerSample = bytesPerSample;
            this.frames = dataLen / (channels * bytesPerSample);
            this.probe = probe;
        }

        static WavDecoder open(String source, WavOpener probe) {
            Path path;
            try {
                path = Paths.get(source);
            } catch (InvalidPathException e) {
                throw AudioException.config("打不开音源：" + source);
            }
            if (!Files.isReadable(path)) {
                // 对应上游 Decoder::open 对不存在的文件返回的那条 Err。
                throw AudioException.config("音源不存在或不可读：" + source);
            }
            byte[] all;
            try {
                all = Files.readAllBytes(path);
            } catch (IOException e) {
                throw AudioException.io("读不出音源：" + source, e);
            }
            if (all.length < 44 || !tag(all, 0, "RIFF") || !tag(all, 8, "WAVE")) {
                throw AudioException.unsupportedFormat("测试替身只认 RIFF/WAVE：" + source);
            }
            int rate = 0;
            int channels = 0;
            int bits = 0;
            int dataOff = -1;
            int dataLen = 0;
            int p = 12;
            while (p + 8 <= all.length) {
                int size = le32(all, p + 4);
                int body = p + 8;
                if (tag(all, p, "fmt ")) {
                    channels = le16(all, body + 2);
                    rate = le32(all, body + 4);
                    bits = le16(all, body + 14);
                } else if (tag(all, p, "data")) {
                    dataOff = body;
                    dataLen = Math.min(size, all.length - body);
                }
                p = body + size + (size & 1);
            }
            if (dataOff < 0 || channels <= 0 || bits != 16) {
                throw AudioException.unsupportedFormat("测试替身只认 16 bit PCM：" + source);
            }
            return new WavDecoder(all, dataOff, dataLen, rate, channels, bits / 8, probe);
        }

        private static boolean tag(byte[] b, int off, String s) {
            for (int i = 0; i < s.length(); i++) {
                if (b[off + i] != (byte) s.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static int le16(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        }

        private static int le32(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                    | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
        }

        private float sample(int frame, int ch) {
            int c = Math.min(ch, channels - 1);
            int off = dataOff + (frame * channels + c) * bytesPerSample;
            short raw = (short) ((file[off] & 0xFF) | (file[off + 1] << 8));
            // 16 bit PCM 的满量程是 32768（不是 32767）：这是解码侧的约定，
            // 与造素材时的 (v * 32767.0) 截断配对使用，两边都在同一个替身里。
            return (float) raw / 32768.0f;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("wav", rate, ChannelLayout.of(channels),
                    Integer.valueOf(16), Long.valueOf(frames), true);
        }

        @Override
        public long framesDecoded() {
            return pos;
        }

        @Override
        public boolean isDrained() {
            return pos >= frames;
        }

        @Override
        public double seek(double secs) {
            if (probe != null) {
                probe.soughtOn.add(Thread.currentThread().getName());
            }
            long want = (long) (secs * (double) rate);
            pos = (int) Math.max(0L, Math.min(want, (long) frames));
            return (double) pos / (double) rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            int n = Math.min(out.capacityFrames(), frames - pos);
            if (n <= 0) {
                out.setFrames(0);
                return 0;
            }
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < out.channels(); c++) {
                    out.channelWriter(c).put(i, sample(pos + i, c));
                }
            }
            out.setFrames(n);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            // 数据已经在内存里，没有句柄要放。
        }
    }
}
