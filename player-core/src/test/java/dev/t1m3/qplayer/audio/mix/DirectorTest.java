package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 播放编排器的对齐测试：对应 Sonalis {@code sonalis-mix/src/director.rs} 的
 * {@code mod tests}（上游 <strong>13 条</strong>，这里逐条对应），另加 3 条本地补充。
 *
 * <p>方法名按 camelCase 逐条对应上游，断言强度与容差一致（{@code assert_eq!} →
 * {@code assertEquals}，{@code assert!(x, "…{}", y)} → {@code assertTrue(msg, x)}
 * 并把上游那段解释原样带上）。
 *
 * <h2>本地补充的三条（上游没有对应测试）</h2>
 *
 * <ol>
 *   <li>{@link #pumpZeroFramesDuringTransitionFinishesInsteadOfEndingPlayback()}——
 *       <strong>「过渡里交付不出帧」那条修法的唯一守卫。</strong> 上游那 13 条里
 *       <strong>没有一条</strong>会红：把 {@code pump} 里那几行去掉、用
 *       {@code cargo test -p sonalis-mix --lib director::tests -- --test-threads=1}
 *       跑一遍，13 条依然全绿（实测过）。原因是上游的假接法在「出场曲已经放完」
 *       那一块都混出了非零帧，唯一会看到 0 的是「泵到没有音频为止」的调用方
 *       （CLI 的 queue、引擎的 session），那一层的粒度抓不住这种状态。</li>
 *   <li>{@link #pathStateFollowsTheActualStateNotAModeSwitch()}——位完美状态是
 *       <strong>算出来的</strong>，不是某个模式开关：同一条判定逻辑（透传 + 增益单位）
 *       对 0 dB 与非 0 dB 给出不同结论。</li>
 *   <li>{@link #retiredDecksAreClosedOnHandover()}——Java 侧的 {@code Drop} 替身：
 *       上游在换 deck 时靠 {@code Drop} 释放解码器（文件句柄 / 网络连接），
 *       Java 没有析构，必须显式关。</li>
 * </ol>
 *
 * <h2>为什么这个类里有一套 WAV 解码器</h2>
 *
 * <p>上游这些测试要真的把两个 deck 打开、走完一次过渡，有几条还要真的做拍点分析，
 * 所以必须写真的文件到临时目录再由解码器打开。QPlayer 的 player-core
 * <strong>故意没有解码器实现</strong>（桌面是 jaudiotagger+jlayer+jflac，安卓是
 * MediaCodec，两边不能共用，见 {@link Decoder} 的类注释），所以这里由测试自己装一个
 * 只认 16 bit PCM WAV 的最小实现：<strong>它是测试替身，不是产品代码</strong>
 * （与 {@code DeckTest} / {@code AiMixTest} 同一套做法）。另一个作用是「文件不存在 /
 * 不是音频」那些用例：产品路径上 {@code Decoder.open} 会抛 {@link AudioException}，
 * 替身必须提供同样的行为——「打不开的进场曲要被跳过并记账」那条正是靠它才测得到。
 *
 * <h2>Java 侧没有 {@code cfg(test)} 探针</h2>
 *
 * <p>上游的 {@code crate::probe} 记录「哪条线程做了开文件 / 定位 / 造变调器」，
 * 主判据是「这些活一条都不能落在生产者线程上」（单看耗时的测试永远不会失败，
 * 等于没测）。Java 没有「只在测试编译期存在」的机制，所以探针挂在
 * {@link Director#heavyProbe}（包私有，见那个字段的注释）上，由本测试负责开关；
 * 而「把成本放大」那一半（上游的 {@code arm_open_delay}）改由这里的
 * {@link WavOpener} 注入——注入点同样在 {@code Deck.open} 的调用链里
 * （{@code Deck.open} → {@code Decoder.open}），等价。
 *
 * <p>{@link Decoder#install(Decoder.Opener)} 是<strong>全局静态状态</strong>：
 * 每条测试的 {@link Before} 自己装一遍，{@link After} 用反射快照把全局工厂还原成
 * 测试开始前的样子（{@code Decoder} 没有提供读取当前工厂的入口）。
 *
 * <h2>线程相关测试为什么不是 flaky</h2>
 *
 * <p>等待一律是<strong>等计数器的超时轮询</strong>（{@code waitFor}）或
 * {@code Thread.join(超时)}，没有一处「睡一会儿猜它应该好了」。唯一的
 * {@code Thread.sleep} 在假接法的 {@code prepare} 里——那是<strong>被测对象本身</strong>
 * （上游用同一个手法证明「准备不在生产者线程上」），不是测试在猜时间。
 */
public class DirectorTest {

    /** 测试用采样率。对应上游 {@code TEST_RATE}。 */
    private static final int RATE = 48_000;

    /** 实测最坏的分析耗时（秒）。上游注释：debug 构建、40 秒素材、auto 接法 4573 ms。 */
    private static final float WORST_CASE_ANALYSIS_SECS = 4.6f;

    /** 假接法 {@code prepare} 的睡眠时长（毫秒）。上游 {@code FAKE_PREPARE_SLEEP}。 */
    private static final long FAKE_PREPARE_SLEEP_MS = 2_000L;

    /** 「没准备过」那个实例的窗口（0.1 秒 @48k）。上游 {@code NOT_PREPARED_WINDOW}。 */
    private static final long NOT_PREPARED_WINDOW = 4_800L;

    /**
     * 「准备好」那个实例的窗口（0.4 秒 @48k）。
     *
     * <p><strong>必须与上面不同</strong>，否则「窗口从准备好的实例重读回来」这条断言
     * 没有区分力。
     */
    private static final long PREPARED_WINDOW = 19_200L;

    /**
     * 注入到 {@code Deck.open} 上的额外耗时（毫秒）。
     *
     * <p><strong>为什么要注入</strong>：debug 构建下打开一个几十秒的 WAV 只要几毫秒，
     * 藏在每块解码的耗时噪声里，耗时判据测不出任何东西。把它放大到远超一个环形缓冲
     * （200 ms）的量级，「跨过过渡起点那一块也不能冒尖」才有分辨率：
     * 旧实现会在那一块里把它整付掉，新实现付在准备线程上。
     */
    private static final long INJECTED_OPEN_DELAY_MS = 400L;

    /** 一个环形缓冲的量级（毫秒）。耗时判据的上界取它。上游 {@code RING_SCALE_MS}。 */
    private static final long RING_SCALE_MS = 200L;

    /** 本测试用过的临时目录，测试结束删掉（上游留在 temp 目录里不管）。 */
    private final List<Path> tempDirs = new ArrayList<Path>();

    /** 本轮装上去的解码器工厂（探针与「打开放慢」都在它里面）。 */
    private WavOpener opener;

    /** 测试开始前的全局工厂，用于还原。 */
    private Decoder.Opener savedOpener;

    @Before
    public void installTestDecoder() {
        savedOpener = currentOpener();
        opener = new WavOpener();
        Decoder.install(opener);
        Director.heavyProbe = null;
    }

    @After
    public void restoreGlobals() {
        Director.heavyProbe = null;
        opener.armOpenDelay(null, 0L);
        Decoder.install(savedOpener);
        for (int i = tempDirs.size() - 1; i >= 0; i--) {
            deleteRecursively(tempDirs.get(i));
        }
        tempDirs.clear();
    }

    /**
     * 读全局解码器工厂的当前值。
     *
     * <p>{@link Decoder} 只有 {@code install} 没有 getter（约定文档给的 API 表里也没有），
     * 所以只能反射快照。读不到时返回 {@code null}——那对应「宿主还没安装」这个干净起点，
     * 也是 player-core 测试的常态（与 {@code DeckTest} 同一处理）。
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
    // 上游 1：PathState 的三个变体
    // ==================================================================

    /** 上游 {@code path_state_labels_are_stable}。 */
    @Test
    public void pathStateLabelsAreStable() {
        assertEquals("直通", PathState.BIT_PERFECT.label());
        assertTrue(PathState.BIT_PERFECT.isBitPerfect());
        assertFalse(PathState.TRANSITIONING.isBitPerfect());
        assertFalse(PathState.PROCESSED.isBitPerfect());
        assertEquals("过渡中", PathState.TRANSITIONING.toString());
    }

    // ==================================================================
    // 上游 2、3：构造期的失败与跳过
    // ==================================================================

    /** 上游 {@code empty_queue_is_reported_not_panicked}：{@code r.is_err()} → 抛异常。 */
    @Test
    public void emptyQueueIsReportedNotPanicked() {
        try {
            Director d = new Director(new ArrayList<String>(), RATE, 2, gaplessFactory());
            fail("空队列应报错而不是静默返回：" + d);
        } catch (AudioException expected) {
            assertTrue("错误应当是「队列里没有能播的」这一类：" + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().length() > 0);
        }
    }

    /** 上游 {@code unopenable_files_are_skipped_and_recorded}。 */
    @Test
    public void unopenableFilesAreSkippedAndRecorded() {
        // 三个都不存在 → 构造必然失败，但要走「跳过」这条路而不是抛别的
        List<String> paths = Arrays.asList("a.wav", "b.wav", "c.wav");
        try {
            Director d = new Director(paths, RATE, 2, gaplessFactory());
            fail("一个都打不开时应当报错：" + d);
        } catch (AudioException expected) {
            assertTrue("空队列那句原因应当被用上：" + expected.getMessage(),
                    expected.getMessage().contains("没有任何可播放"));
        }
    }

    /** 上游 {@code prepare_margin_covers_the_worst_case_analysis}。 */
    @Test
    public void prepareMarginCoversTheWorstCaseAnalysis() {
        assertTrue("余量 " + Director.PREPARE_MARGIN_SECS + " 秒对实测最坏的 "
                        + WORST_CASE_ANALYSIS_SECS + " 秒分析没有余量可言",
                (float) Director.PREPARE_MARGIN_SECS >= WORST_CASE_ANALYSIS_SECS * 3.0f);
    }

    // ==================================================================
    // 上游 5：过渡窗口里不能有洞
    // ==================================================================

    /**
     * <strong>过渡窗口里不能有洞。</strong>
     *
     * <p>这是用户那句话的可执行版本：「融合阶段声音会时不时顿一下」。
     * 两条 deck 的块长不齐时，混音器把短的那一路的尾巴当作「这一路已经结束」
     * 补零，融合里就凭空少掉一段声音——数字上是 5 ms 块的 RMS 塌到窗口
     * 中位数的两成以下，听感上就是「顿一下」。
     *
     * <p>两处是刻意的（照上游）：
     *
     * <ul>
     *   <li><strong>输出采样率与素材不同</strong>（48 kHz 素材 → 44.1 kHz 输出）。只有重采样器
     *       在链上时块长才会起伏；两边相同（透传）是这个缺陷最好的伪装——
     *       实测那边一处洞都没有，这也正是它一直没被发现的原因。</li>
     *   <li><strong>素材是正弦</strong>。任何一个洞都只可能来自渲染，不可能来自素材自己。</li>
     * </ul>
     */
    /**
     * <b>队首是远端音源时，准备余量必须抬到 90 秒。</b>
     *
     * <p>用户 2026-09-30：「安卓端会出现有的歌曲过渡压根没有任何 automix，90s 准备时间
     * 肯定是给了的」。余量表是按**设置**定的（30 / 90 / 150 s），看不见「下一首在哪儿」——
     * 而手机上的下一首经常还是那条带签名的直链（预取的音频没落盘），此时准备阶段的每一次读
     * 都是**网络读**。默认那 30 s（自动点位关着的声波重合/交叉淡化就是这一档）在这种素材上
     * 必然不够：到了触发点没有结果，这一次过渡**根本不发生**（`beginTransition` 刻意不退化）。
     *
     * <p>钉三件事：远端抬到 90、落回本地回到设置值、**只抬不降**（设置里那一档本来就更高时
     * 不许被压低——生成式是 150 s）。
     */
    @Test
    public void aRemoteNextSourceRaisesThePrepareMargin() throws IOException {
        Path dir = tempDir("remote");
        Path a = writeWav(dir.resolve("a.wav"), sineTrack(2.0f), RATE);
        Path b = writeWav(dir.resolve("b.wav"), sineTrack(2.0f), RATE);
        Director d = new Director(listOf(a, b), RATE, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                return new OutroOnly(RATE);
            }
        });

        d.setPrepareMarginSecs(30L);
        assertEquals("本地队首：余量就是设置里那一档", 30L, d.prepareMarginSecs());

        assertTrue("换成远端队首应当成功", d.replaceQueued(
                "https://m801.music.126.net/20260930210134/x/jdymusic/obj/y.mp3?vuutv=abc"));
        assertEquals("远端队首：余量必须抬到 " + Director.REMOTE_PREPARE_MARGIN_SECS + " s"
                        + "（网络读的耗时没法预估，30 s 必然不够）",
                Director.REMOTE_PREPARE_MARGIN_SECS, d.prepareMarginSecs());

        // 预取落盘、句柄升级成本地文件 → 回到设置里那一档
        assertTrue(d.replaceQueued(b.toString()));
        assertEquals("落回本地之后应当回到设置里那一档", 30L, d.prepareMarginSecs());

        // 只抬不降：设置里那一档本来就比 90 高（生成式 150 s）
        d.setPrepareMarginSecs(150L);
        assertTrue(d.replaceQueued("http://example.com/x.mp3"));
        assertEquals("设置里那一档更高时不许被压低", 150L, d.prepareMarginSecs());
    }

    /**
     * <b>准备一收到预告就开工</b>（用户 2026-10-02：「automix 预处理一收到预告就开始做，
     * 不要留到最后再开始」）。
     *
     * <p>改之前这里有一道时间闸门：{@code 剩余 ≤ 触发点 + 余量}（余量 30/90 s）。
     * 这条判据用一首**远长于余量**的出场曲把它照出来：120 s 的歌，泵一块之后还剩约 119.9 s，
     * 旧代码下准备**不会**开工（要等到剩 30 秒才动），新代码下第一块就该开工。
     *
     * <p>为什么要这样：那道闸门在「队首本来就短 / 用户中途换歌 / 第一首刚播就被预告」时
     * 根本不成立（于是这次过渡彻底不发生），而且会把 4 秒的分析挤到触发点前跟播放抢 CPU。
     */
    @Test
    public void preparationStartsAsSoonAsTheNextTrackIsAnnounced() throws IOException {
        Path dir = tempDir("announce");
        Path a = writeWav(dir.resolve("a.wav"), sineTrack(120.0f), RATE);
        Path b = writeWav(dir.resolve("b.wav"), sineTrack(2.0f), RATE);
        FakeState st = new FakeState(FAKE_PREPARE_SLEEP_MS);
        Director d = new Director(listOf(a, b), RATE, 2, slowFactory(st));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        assertEquals("构造时不该有准备在跑", 0, st.started.get());
        d.pump(buf);
        // 出场曲 120 s，而余量只有 30 s：这一块结束时离触发点还差得远。
        Double secs = d.currentSpec() == null ? null : d.currentSpec().durationSecs();
        assertTrue("夹具本身有问题：出场曲应当是 120 s 的长曲", secs != null && secs > 100.0);
        waitFor(st.started, 1, "第一块就该开工的准备");
        assertEquals("开工了就该还没跑完——跑完了这条就测不出「提前开工」",
                0, st.finished.get());
    }

    /**
     * <b>准备在跑的时候，「卡在哪一步」必须读得出来。</b>
     *
     * <p>用户 2026-09-30：手机上「有的歌曲过渡压根没有任何 automix」，而日志取不出来
     * （原话「安卓那边我复制不出来 log 只能你按照代码自己推了」）。到了触发点却没有结果时，
     * 引擎那条警告只说「准备线程还在跑」——分不清是整曲扫描（两百秒的 MP3 或一条直链）、
     * 模型推理（对拍/分轨，手机上最慢的两样）还是分轨之后的移调，而三者的修法完全不同。
     * 所以准备链上几个慢阶段各自往 {@link PrepareStage} 里留一个记号。
     *
     * <p>这条判据钉两头：准备中记号**必须有值**（否则那条日志还是说不出卡在哪）、
     * 收工后**必须清掉**（否则下一次读到的是上一次的）。
     */
    @Test
    public void thePrepareStageIsVisibleWhilePreparing() throws IOException {
        Path dir = tempDir("stage");
        Path a = writeWav(dir.resolve("a.wav"), sineTrack(2.0f), RATE);
        Path b = writeWav(dir.resolve("b.wav"), sineTrack(2.0f), RATE);
        FakeState st = new FakeState(FAKE_PREPARE_SLEEP_MS);
        Director d = new Director(listOf(a, b), RATE, 2, slowFactory(st));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        assertEquals("还没准备时应当是「没有准备在跑」",
                PrepareStage.IDLE, PrepareStage.current());
        for (int i = 0; i < 5; i++) {
            d.pump(buf);
        }
        waitFor(st.started, 1, "准备线程开工");
        String during = PrepareStage.current();
        System.out.println("[prepare-stage] 准备中：" + during);
        assertNotEquals("准备在跑时阶段记号必须有值（否则「没赶上触发点」那条日志还是说不出"
                + "卡在哪一步）：" + during, PrepareStage.IDLE, during);

        waitFor(st.finished, 1, "准备收工");
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!PrepareStage.IDLE.equals(PrepareStage.current())
                && System.currentTimeMillis() < deadline) {
            sleepQuietly(2L);
        }
        assertEquals("收工后阶段记号要清掉，否则下一次读到的是上一次的",
                PrepareStage.IDLE, PrepareStage.current());
    }

    /**
     * <b>离过渡开始还有几秒：界面靠它把「过渡提示」提前三秒亮起来。</b>
     *
     * <p>用户 2026-09-30：「让进度条发光在 automix 开始前三秒就有」。界面原来只能读
     * {@code inTransition}，而那一位是<b>过渡开始那一刻</b>才翻的——恰恰也是界面跳到进场曲的
     * 那一刻，于是提示与跳页同时发生，等于没提示。这里钉住三件事：
     *
     * <ol>
     *   <li>还没装载过渡计划时是 {@code null}——界面据此<b>不预告</b>。
     *       这一条最容易写成「拿离曲终还有多久去凑」，那样每首歌结尾都会亮；</li>
     *   <li>装载之后是正数、而且<b>随播放递减</b>（它是倒计时，不是常数）；</li>
     *   <li>进入过渡之后是 <b>0 或负数</b>（提示要一直亮到过渡结束）。</li>
     * </ol>
     *
     * <p>提前量里必须含「自动点位丢掉的尾巴」——那一条在
     * {@code autoCueTailMovesTheTransitionEarlier} 里钉的是触发点本身，这里钉的是读数。
     */
    @Test
    public void theLeadInCountsDownToTheTriggerAndIsNonPositiveInside() throws IOException {
        final int outRate = 44_100;
        Path dir = tempDir("lead");
        Path outro = writeWav(dir.resolve("lead-outro.wav"), sineTrack(6.0f), RATE);
        Path intro = writeWav(dir.resolve("lead-intro.wav"), sineTrack(6.0f), RATE);

        // 窗口 4 秒、出场曲 6 秒：过渡从第 2 秒开始 → 装载后应当读到约 2 秒的倒计时
        final long window = (long) outRate * 4L;
        Director d = new Director(listOf(outro, intro), outRate, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                return new OutroOnly(window);
            }
        });

        assertNull("一块都没泵过时不该给提前量（界面此时不许亮）", d.transitionLeadInSecs());

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        boolean pumped = false;
        boolean entered = false;
        Double first = null;
        Double lastBefore = null;
        double worstInside = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 400; i++) {
            if (pumped && !entered && d.windowFrames() > 0L && d.queuedNextPath() != null) {
                long deadline = System.currentTimeMillis() + 30_000L;
                while (!d.collectPreparation()) {
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    sleepQuietly(2L);
                }
            }
            int n = d.pump(buf);
            if (n == 0) {
                break;
            }
            pumped = true;
            Double lead = d.transitionLeadInSecs();
            if (d.inTransition()) {
                entered = true;
                if (lead != null && lead.doubleValue() > worstInside) {
                    worstInside = lead.doubleValue();
                }
            } else if (entered) {
                break;
            } else if (lead != null) {
                if (first == null) {
                    first = lead;
                }
                lastBefore = lead;
            }
        }

        assertTrue("这一轮没进过渡，测不到倒计时", entered);
        assertNotNull("装载之后必须读得出倒计时", first);
        System.out.println("[director] 倒计时：" + first + " s → " + lastBefore
                + " s（窗口 4.0 s、出场曲 6.0 s，应在 2 s 附近开始倒数）");
        assertTrue("倒计时必须是正数（还没开始），实测 " + first, first.doubleValue() > 0.0);
        assertTrue("窗口 4 s、曲长 6 s 时倒计时不该超过 2 s，实测 " + first,
                first.doubleValue() <= 2.0 + 0.2);
        assertTrue("倒计时必须随播放递减（它是倒计时，不是常数）：" + first + " → " + lastBefore,
                lastBefore.doubleValue() < first.doubleValue());
        assertTrue("进入过渡后必须是 0 或负数（提示要一直亮到结束），实测最大 " + worstInside,
                worstInside <= 0.0);
    }

    @Test
    public void transitionWindowHasNoSilentHoles() throws IOException {
        final int outRate = 44_100;
        Path dir = tempDir("holes");
        Path outro = writeWav(dir.resolve("outro.wav"), sineTrack(6.0f), RATE);
        Path intro = writeWav(dir.resolve("intro.wav"), sineTrack(6.0f), RATE);

        // 窗口 4 秒：出场曲 6 秒，于是过渡从第 2 秒开始
        final long window = (long) outRate * 4L;
        Director d = new Director(listOf(outro, intro), outRate, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                return new OutroOnly(window);
            }
        });

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        // 过渡窗口里交出来的音频（折成单声道就够看能量了）
        List<Float> windowPcm = new ArrayList<Float>();
        boolean entered = false;
        boolean ended = false;
        boolean pumped = false;
        for (int i = 0; i < 400; i++) {
            // 这个测试泵得比实时快得多，准备结果要等一等——实时播放靠的是
            // 30 秒余量，不需要等（见 `Director.collectPreparation`）。
            //
            // `pumped` 不能省：准备是在 `pump` 里发起的，一块都没泵过就等，
            // 等的是一个永远不会开工的东西（会白等到超时）。
            if (pumped && !ended && d.windowFrames() > 0L && d.queuedNextPath() != null) {
                long deadline = System.currentTimeMillis() + 30_000L;
                while (!d.collectPreparation()) {
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    sleepQuietly(2L);
                }
            }
            int n = d.pump(buf);
            if (n == 0) {
                break;
            }
            pumped = true;
            if (d.inTransition()) {
                entered = true;
                for (int k = 0; k < n; k++) {
                    windowPcm.add(Float.valueOf(0.5f
                            * (buf.channel(0).get(k) + buf.channel(1).get(k))));
                }
            } else if (entered) {
                ended = true;
                break;
            }
        }
        assertTrue("一直没进入过渡，这条测试什么也没测到", entered);
        assertTrue("过渡没有走完（泵了 400 块就停了）", ended);
        assertTrue("过渡窗口只有 " + windowPcm.size() + " 帧，太短了，说明不了问题",
                windowPcm.size() > outRate / 2);

        // **末尾那 0.25 秒不参与判定。**
        //
        // 出场曲是在窗口末尾自然结束的，而 `OutroOnly` 给进场曲的增益是零——
        // 从出场曲耗尽那一刻起它按定义就该是静音。那不是缺陷，是这次取景方式的
        // 固有代价（真实淡化在这里有进场曲接着响）。触发点是按块判定的，
        // 所以出场曲最早可能提前不到一块（93 ms）结束，留 0.25 秒足够把这个
        // 合法的尾巴排除干净。
        int usable = Math.max(windowPcm.size() - outRate / 4, 0);

        // 5 ms 一块的 RMS：短到能看见一次几十毫秒的断口，又长到不会被单个采样
        // 骗到。正弦素材在 5 ms 里有两个以上周期，块内能量天然平稳。
        int block = outRate / 200;
        int blocks = usable / block;
        assertTrue("分不出几块，这条测试太短", blocks >= 8);
        float[] rms = new float[blocks];
        for (int b = 0; b < blocks; b++) {
            float sum = 0f;
            for (int k = 0; k < block; k++) {
                float v = windowPcm.get(b * block + k).floatValue();
                sum += v * v;
            }
            // 上游是 f32 的 sqrt（`sum::<f32>() / block as f32` 再 `.sqrt()`）
            rms[b] = (float) Math.sqrt((double) (sum / (float) block));
        }
        float[] sorted = rms.clone();
        Arrays.sort(sorted);
        float median = sorted[sorted.length / 2];
        float worst = sorted[0];
        double dropDb = 20.0 * Math.log10((double) (worst / median));
        assertTrue("过渡窗口里出现了一个洞：最低的 5 ms 块 RMS 是 " + worst
                        + "，窗口中位数 " + median + "（掉了 " + (float) dropDb + " dB）——"
                        + "融合被切掉了一段，这正是用户听到的「顿一下」",
                worst > median * 0.2f);
    }

    // ==================================================================
    // 上游 6：准备绝不能跑在生产者线程上
    // ==================================================================

    /**
     * <strong>准备绝不能跑在生产者线程上。</strong>
     *
     * <p>这就是用户报的那一条：过渡前会「直接卡住（整个音频），然后加载完了
     * 再出来」。根因是 {@code pump()}（生产者线程）里同步调用了 {@code prepare}，
     * 而它要 1.6~4.6 秒，环形缓冲默认只有 200 ms。
     *
     * <p>这里用一个 {@code prepare} 故意睡 2 秒的假接法复现那个场景，断言两件事：
     *
     * <ol>
     *   <li>在那 2 秒<strong>还没跑完</strong>的时候，{@code pump()} 已经产出了十块音频，
     *       而且这十次调用总共只花了不到 500 ms（远小于那个 sleep）——
     *       也就是音频在准备期间照常产出；</li>
     *   <li>准备完成后，<strong>那个实例确实被换上来了</strong>（用「它渲染过」直接数出来）。</li>
     * </ol>
     *
     * <p>用另一个线程 + 超时来驱动，理由与上游一样：写成同步的话，失败的表现是
     * 「测试挂住」而不是「测试失败」，那更难发现。Java 这边对应上游的
     * {@code recv_timeout} 的是 {@code join(超时)} + 把驱动线程的异常搬回来。
     */
    @Test
    public void preparationDoesNotBlockTheProducerThread() throws InterruptedException {
        final Throwable[] failure = new Throwable[1];
        Thread driver = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    slowPreparationCase();
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }
        }, "qplayer-director-test-driver");
        // 上游没有 daemon 这个概念（Rust 的线程不阻止进程退出）；Java 里非 daemon 的
        // 驱动线程在超时后会把 JVM 吊住，所以显式设成 daemon。
        driver.setDaemon(true);
        driver.start();
        driver.join(60_000L);

        assertFalse("测试超时——准备很可能又跑回生产者线程上了（线程 " + driver.getName() + " 还挂着）",
                driver.isAlive());
        if (failure[0] != null) {
            if (failure[0] instanceof AssertionError) {
                throw (AssertionError) failure[0];
            }
            throw new AssertionError("驱动线程抛了异常（多半是断言失败），原因见上面：" + failure[0],
                    failure[0]);
        }
    }

    /**
     * {@link #preparationDoesNotBlockTheProducerThread()} 的现场（跑在另一条线程上）。
     *
     * <p>写成独立方法是为了让断言失败变成「另一个线程里的异常」，由上面那段
     * {@code join(超时)} 把它搬回来——上游用 {@code recv_timeout} 的
     * {@code Disconnected} 分支区分「断言失败」与「超时」，这里用「线程结束 +
     * 异常快照」区分同两件事。造素材的 IO 异常在这里就地转成
     * {@code IllegalStateException}（它是测试脚手架，不该自己成为失败原因）。
     */
    private void slowPreparationCase() {
        Path dir = tempDir("block");
        Path outro;
        Path intro;
        try {
            outro = writeWav(dir.resolve("outro.wav"), sineTrack(2.0f), RATE);
            intro = writeWav(dir.resolve("intro.wav"), sineTrack(2.0f), RATE);
        } catch (IOException e) {
            throw new IllegalStateException("造不出测试素材", e);
        }

        FakeState st = new FakeState(FAKE_PREPARE_SLEEP_MS);
        Director d = new Director(listOf(outro, intro), RATE, 2, slowFactory(st));
        assertEquals("构造时应当只造一个实例（渲染用的那个）", 1, st.built.get());

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        // ---- 一、准备期间音频照常产出 ----
        //
        // 十块约 0.85 秒音频。同步实现里第一块就会卡满整个 sleep。
        long t0 = System.nanoTime();
        int frames = 0;
        for (int i = 0; i < 10; i++) {
            frames += d.pump(buf);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue("准备期间一块音频都没产出来", frames > 0);
        assertTrue("10 次 pump 花了 " + elapsedMs + " ms（假接法的 prepare 睡 "
                        + FAKE_PREPARE_SLEEP_MS + " ms）——准备又跑回生产者线程上了，"
                        + "这就是用户听到的那次卡死",
                elapsedMs < FAKE_PREPARE_SLEEP_MS / 4L);

        // 这一段是「测试没有空转」的证据：准备确实开工了，而且当时**还没跑完**。
        waitFor(st.started, 1, "准备线程开工");
        assertEquals("上面那 10 次 pump 期间准备就已经跑完了——那次计时证明不了任何事",
                0, st.finished.get());

        // ---- 二、准备好的实例确实被换上来了 ----
        //
        // 这里等一下没关系：**等的是测试线程**。生产者线程那边一块都没等过。
        waitFor(st.finished, 1, "准备线程收工");

        // 注意：过渡之前**数不到任何东西**——那块时间渲染走的是 deck，
        // 过渡实例只在过渡窗口里被调用。所以「旧实例没被用上」的判据是
        // 「它一次都没渲染过」，放在下面换上来之后再断言。
        boolean entered = false;
        for (int i = 0; i < 200; i++) {
            if (d.pump(buf) == 0) {
                break;
            }
            if (d.inTransition()) {
                entered = true;
                break;
            }
        }
        assertTrue("一直没进入过渡", entered);
        assertTrue("准备好的实例一次都没渲染——它没被换上来，准备白做了", st.slowRenders.get() > 0);
        assertEquals("窗口没有从准备好的实例读回来（对拍拉长窗口这一步会失效）",
                PREPARED_WINDOW, d.windowFrames());
        assertTrue("过渡用的不是准备好的那份计划：" + d.transitionNote(),
                d.transitionNote().contains("intro=intro.wav"));
        assertEquals("渲染用的还是旧实例", "假接法·已准备", d.transitionName());

        // 再泵几块：换上来之后旧实例不该再碰过一次
        for (int i = 0; i < 3; i++) {
            d.pump(buf);
        }
        assertEquals("那次过渡用了没准备过的旧实例——准备好的实例没换上（或者换晚了）",
                0, st.naiveRenders.get());
    }

    // ==================================================================
    // QPlayer 补充：自动点位的「丢尾」把过渡触发点提前
    // ==================================================================

    /**
     * 「自动点位」选中的融合点不在曲尾时，过渡必须<strong>更早</strong>开始。
     *
     * <p>为什么这件事值得一条测试：融合点由 {@code TransitionPlan.outroTailSecs} 带回来，
     * 而触发判据原本只看窗口。只看窗口的话，等到剩一个窗口时出场曲已经被自己的尾巴占满了——
     * 融合点会<strong>悄悄落回曲尾</strong>，用户打开开关之后听到的与之前一模一样，
     * 而且没有任何报错。
     */
    @Test
    public void autoCueTailMovesTheTransitionEarlier() {
        Path dir = tempDir("autocue");
        Path outro;
        Path intro;
        try {
            outro = writeWav(dir.resolve("outro.wav"), sineTrack(6.0f), RATE);
            intro = writeWav(dir.resolve("intro.wav"), sineTrack(6.0f), RATE);
        } catch (IOException e) {
            throw new IllegalStateException("造不出测试素材", e);
        }
        long window = 4_800L;          // 0.1 秒
        double tailSecs = 2.0;         // 融合点选在曲中：后面 2 秒的尾巴不播
        Director d = new Director(listOf(outro, intro), RATE, 2,
                tailPlanFactory(window, tailSecs));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        long expectedLead = window + (long) Math.round(tailSecs * RATE);
        assertEquals("还没开始交叠时，提前量先按构造时的窗口算（计划还没回来）",
                window, d.triggerLeadFrames());

        // 泵到进入过渡：这一刻剩下的帧数应当接近 expectedLead，而不是 window
        boolean entered = false;
        for (int i = 0; i < 4000 && !entered; i++) {
            if (d.pump(buf) == 0) {
                break;
            }
            entered = d.inTransition();
        }
        assertTrue("一直没进入过渡", entered);
        assertEquals("计划到手后提前量应当带上丢尾", expectedLead, d.triggerLeadFrames());

        TrackSpec spec = d.currentSpec();
        Long totalSrc = spec.totalFrames;
        assertTrue("这条测试的素材必须有已知总帧数", totalSrc != null);
        double ratio = (double) d.outRate() / (double) Math.max(spec.sampleRate, 1);
        long totalOut = (long) (totalSrc.longValue() * ratio);
        long remaining = Math.max(totalOut - d.currentFrames(), 0L);
        assertTrue("过渡开始时还剩 " + remaining + " 帧（窗口只有 " + window + " 帧）——"
                        + "丢尾没有被算进触发点，融合点会悄悄落回曲尾",
                remaining > window + (long) (0.5 * tailSecs * RATE));
        assertTrue("过渡也不该早得离谱：还剩 " + remaining + " 帧，提前量 " + expectedLead,
                remaining <= expectedLead + 4 * Deck.RENDER_FRAMES);
    }



    // ==================================================================
    // 自动点位：听到的 B 必须和 deck 报的位置对得上
    // ==================================================================

    /**
     * 造一段「位置编码」素材：频率随时间线性上升（啁啾，2000 Hz 起、每秒 +400 Hz）。
     * 于是在混音输出里认出频率，就知道听到的是 B 的第几秒——比阶梯细得多。
     */
    private static float[] chirpTrack(double secs, float base, float perSec) {
        int frames = (int) ((double) RATE * secs);
        float[] pcm = new float[frames];
        double phase = 0.0;
        for (int n = 0; n < frames; n++) {
            double f = base + perSec * ((double) n / (double) RATE);
            phase += 2.0 * Math.PI * f / (double) RATE;
            pcm[n] = (float) (Math.sin(phase) * 0.3);
        }
        return pcm;
    }

    /** 在 1500–9000 Hz 之间找最强谱峰，换算回 B 的秒数；没有内容时返回 NaN。 */
    private static double heardSecs(AudioBuf buf, int n) {
        int size = 8192;
        float[] re = new float[size];
        float[] im = new float[size];
        for (int i = 0; i < size && i < n; i++) {
            re[i] = buf.channelWriter(0).get(i)
                    * (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / size));
        }
        new dev.t1m3.qplayer.audio.dsp.Fft(size).forward(re, im);
        double best = 0;
        int at = 0;
        for (int k = 1; k < size / 2; k++) {
            double f = k * (double) RATE / size;
            if (f < 1500 || f > 9000) {
                continue;
            }
            double m = Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]);
            if (m > best) {
                best = m;
                at = k;
            }
        }
        if (best <= 0.0) {
            return Double.NaN;
        }
        double hz = at * (double) RATE / size;
        return (hz - 2000.0) / 400.0;
    }

    /**
     * 走生产代码那条接线：{@code AutomixSettings.factoryFor(true, 交叉淡化, …, autoCue=true)}。
     *
     * <p>参数表：{@code (enabled, mode, style, crossfadeSecs, harmonicSecs, vocalMode,
     * autoCue, rate, channels)}。窗口 6 秒、风格 0（交叉淡化那一档不看风格）。
     */
    private static TransitionFactory productionFactory(final int vocalMode) {
        try {
            java.lang.reflect.Method m = Class
                    .forName("dev.t1m3.qplayer.audio.engine.AutomixSettings")
                    .getDeclaredMethod("factoryFor", boolean.class, int.class, int.class,
                            float.class, float.class, int.class, boolean.class,
                            int.class, int.class);
            m.setAccessible(true);
            return (TransitionFactory) m.invoke(null, Boolean.TRUE,
                    Integer.valueOf(1),        // AUTOMIX_MODE_CROSSFADE
                    Integer.valueOf(0),        // 风格（交叉淡化不看它）
                    Float.valueOf(6.0f),       // 交叉淡化窗口
                    Float.valueOf(6.0f),       // 声波重合窗口
                    Integer.valueOf(vocalMode),
                    Boolean.TRUE,              // 自动点位：开
                    Integer.valueOf(RATE),
                    Integer.valueOf(2));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("拿不到 AutomixSettings.factoryFor，"
                    + "这条测试必须用生产接线，不能退化成手搭一份", e);
        }
    }

    /**
     * <strong>自动点位之后，融合期听到的进场曲必须和 deck 报出来的位置对得上。</strong>
     *
     * <p>这是用户报的那一条：「A→B 时融合期 B 的音频和实际 B 时间错位，
     * 导致完全过渡完瞬间跳进度」。根因是<strong>两层的嵌套顺序反了</strong>：
     * 「去人声」那一层要<em>预先读好</em>进场曲的一段素材（渲染路径上一次盘都不许读），
     * 读的位置正是内层计划给的 {@code skipSecs}；而「自动点位」恰恰会把这个值挪到曲中的
     * 另一个点上。自动点位套在外面时，去人声读到的是<strong>旧的</strong>进场点——
     * 实测进场点 2.8 s 时，听到的 B 比 deck 报的位置整整晚 <strong>2.87 s</strong>，
     * 而过渡一结束 deck 上位，进度就往前跳那么多。
     *
     * <p>所以这条测试量的是<strong>音频本身</strong>：素材用啁啾编码位置（2000 Hz 起、
     * 每秒 +400 Hz），从混音输出里认出频率就知道听到的是 B 的第几秒，再与
     * {@link Director#nextFrames()} 比对。容差 0.1 s ≈ 两个块内的采样栅格误差；
     * 修好前这里是 2.87 s，任何一次「嵌套顺序被改回去」都会当场响。
     *
     * <p>两种配置都要过：不套去人声的（对照，一直是 0.03 s）与套去人声的（出问题的那条）。
     */
    @Test
    public void autoCueIntroAudioStaysAlignedWithItsReportedPosition() throws IOException {
        assertAligned("不套去人声", false);
        assertAligned("套去人声", true);
    }

    private void assertAligned(String label, boolean vocal) throws IOException {
        Path dir = tempDir("aligned");
        Path outro = writeWav(dir.resolve("a.wav"), sineTrack(40.0f), RATE);
        Path intro = writeWav(dir.resolve("b.wav"), chirpTrack(30.0, 2000.0f, 400.0f), RATE);

        // **用生产代码那条接线**（AutomixSettings.factoryFor），不是这里手搭的：
        // 这个缺陷的根因就是工厂里两层的嵌套顺序，手搭一份等于把被测对象换掉。
        // 那个方法是包私有的（只给引擎内部与「只关心模式→接法那张表」的调用方用），
        // 所以这里按名字取——取不到会当场抛，不会静默变成「测了个假的」。
        Director d = new Director(listOf(outro, intro), RATE, 2,
                productionFactory(vocal ? 1 : 0));

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        boolean entered = false;
        boolean finished = false;
        int measured = 0;
        double worst = 0.0;
        for (int i = 0; i < 20000 && !finished; i++) {
            int n = d.pump(buf);
            if (n == 0) {
                break;
            }
            // 准备结果：只在**准备真的开工之后**才等，而且等待期间不能挡住泵——
            // 挡住的话曲子永远走不到该准备的位置（实测就是这样把自己锁死的）。
            if (!entered && "true".equals(String.valueOf(reflect(d, "prepared")))
                    && d.queuedNextPath() != null) {
                long deadline = System.currentTimeMillis() + 20_000L;
                while (!d.collectPreparation() && System.currentTimeMillis() < deadline) {
                    sleepQuietly(20L);
                }
            }
            if (entered && !d.inTransition()) {
                finished = true;
                break;
            }
            if (!d.inTransition()) {
                continue;
            }
            entered = true;
            double heard = heardSecs(buf, n);
            // 窗口最开头那一两个块里进场曲的增益还是 0（等功率曲线从 0 起），
            // 认不出内容，跳过——它不构成「错位」的证据。
            if (Double.isNaN(heard) || heard < 0.0) {
                continue;
            }
            double reported = d.nextFrames() / (double) RATE;
            double diff = reported - heard;
            measured++;
            if (Math.abs(diff) > Math.abs(worst)) {
                worst = diff;
            }
        }
        assertTrue(label + "：一直没进入过渡，这条测试什么也没测到", entered);
        assertTrue(label + "：过渡窗口里认出的 B 内容太少（" + measured + " 块），说明不了问题",
                measured >= 10);
        assertTrue(label + "：听到的 B 与 deck 报的位置最大差了 " + worst
                        + " s——融合期听到的是另一段内容，过渡一结束进度就会跳那么多",
                Math.abs(worst) < 0.1);
    }

    private static Object reflect(Object o, String field) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable e) {
            return "?";
        }
    }

    /** 计划里带「丢尾」的假接法（融合点不在曲尾时的形状）。 */
    private static TransitionFactory tailPlanFactory(final long window, final double tailSecs) {
        return new TransitionFactory() {
            @Override
            public Transition create() {
                return new TailPlanFake(window, tailSecs);
            }
        };
    }

    private static final class TailPlanFake implements Transition {

        private final long window;
        private final double tailSecs;

        TailPlanFake(long window, double tailSecs) {
            this.window = window;
            this.tailSecs = tailSecs;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            if (progress < 0.5f) {
                return Mixer.gainInto(out, from, 1.0f);
            }
            return Mixer.gainInto(out, to, 1.0f);
        }

        @Override
        public String name() {
            return "假接法·自动点位";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int rate) {
            return new TransitionPlan(0.0, PitchPlan.ZERO, 0f, "自动点位（测试）", tailSecs);
        }
    }

    // ==================================================================
    // 上游 7：已作废的准备结果绝不能被用上
    // ==================================================================

    /**
     * <strong>已作废的准备结果绝不能被用上。</strong>
     *
     * <p>切歌/跳转/上一首时，正在跑的那次分析还在路上；它算的是上一个局面的
     * 进出场关系（窗口、对拍跳过量、变调）。用上它就是把 A 的结论施加到 B 上：
     * 跳到 B 里一个凭空的时刻、给它转一个凭空的调。
     *
     * <p>上游为「分析的是 A、打开的是 B」修过一次（{@code prepared_intro} 那段注释），
     * 这里是它的异步版：结论还在路上时用户就已经切走了。
     */
    @Test
    public void aCancelledPreparationIsNeverInstalled() throws IOException {
        Path dir = tempDir("cancel");
        Path a = writeWav(dir.resolve("a.wav"), sineTrack(2.0f), RATE);
        Path b = writeWav(dir.resolve("b.wav"), sineTrack(2.0f), RATE);
        Path c = writeWav(dir.resolve("c.wav"), sineTrack(2.0f), RATE);

        FakeState st = new FakeState(FAKE_PREPARE_SLEEP_MS);
        Director d = new Director(listOf(a, b, c), RATE, 2, slowFactory(st));
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        // 泵几块，让「为第二首做准备」跑起来。这时离过渡还很远：
        // 余量是 30 秒，而这里只泵了 5 块（约 0.43 秒）。
        for (int i = 0; i < 5; i++) {
            d.pump(buf);
        }
        waitFor(st.started, 1, "准备线程开工");
        assertEquals("准备已经跑完了，这次就测不到「结论还在路上」了", 0, st.finished.get());

        // 切歌：正在跑的那次准备当场作废
        int builtBeforeSkip = st.built.get();
        assertTrue("队列里还有两首，切歌该成功", d.skipNext());
        assertEquals("切歌本身不该造实例——准备线程只由 pump 里的准备时机启动",
                builtBeforeSkip, st.built.get());

        // 旧线程还睡着的时候再泵几块：**不该**马上又开一条准备线程。
        // 作废只丢结果、打断不了线程，不等旧槽位空出来就一路新开，密集跳转
        // 会堆起一堆同时跑 FFT 的线程，把 CPU 打满——修卡顿的动作反倒给音频
        // 带回一个新的卡顿来源。
        for (int i = 0; i < 3; i++) {
            d.pump(buf);
        }
        assertEquals("旧准备线程还没收工就开了新的——跳转密集时会把并行分析线程堆起来",
                builtBeforeSkip, st.built.get());

        // 等那次被作废的准备把结论送回来（送回来也没人接）
        waitFor(st.finished, 1, "被作废的准备收工");

        // 再泵到「为 c 的那次准备开工」。
        //
        // **不能只泵一块。** `finished` 是在 `prepare` 里加的，而「准备槽位空出来」
        // 要等线程真收工（`PrepBusyGuard` 的 Drop / Java 的 try-finally）——
        // 两者之间有几微秒的缝。只泵一块正好落进缝里的话，这一轮仍然开不了工；
        // 而后面等结果的那几句不产音频，`shouldPrepare` 就再也没机会被调用，
        // 测试会卡死在这里。
        //
        // **也不能不等。** 编排器在没有准备好的 deck 时会把过渡**推迟**
        // （见 `Director` 的类注释）：不等就一路泵下去，只会泵到曲子结束走硬接，
        // 压根进不了过渡，这条测试也就测不到「换上来的到底是哪一份」。
        // 真实播放不需要这样等——生产者线程被设备节奏限着，30 秒余量足够；
        // 是测试泵得比实时快才需要。
        long deadline = System.currentTimeMillis() + 30_000L;
        while (st.started.get() < 2) {
            assertTrue("为 c 做的准备一直没开工", System.currentTimeMillis() < deadline);
            d.pump(buf);
        }
        waitFor(st.finished, 2, "为 c 做的准备收工");

        // **作废那一份也要在日志里留一行**（2026-09-30）。原先它完全静默，于是那份准备
        // 干活时留下的告警（例如「自动点位：整曲扫描失败」——它的源文件可能刚被磁盘缓存
        // 淘汰）在日志里没有下文，用户读到的就是一段没头没尾的 WARN（用户 20:36 的日志
        // 正是这个：整曲扫描失败，而那一对其实早就换掉了）。
        //
        // 放在这里而不是紧跟切歌之后：丢掉那份结果是 `pump` → `collectPreparation` 里做的，
        // 落在哪一块取决于线程调度——上面那个「等 d 的准备开工」的循环已经泵过很多块了，
        // 到这里必定已经发生过。
        boolean logged = false;
        for (String line : dev.t1m3.qplayer.util.Logger.snapshot()) {
            if (line.contains("这份准备已作废") && line.contains("b.wav")) {
                logged = true;
                break;
            }
        }
        assertTrue("被作废的准备结果被丢掉时没有留任何日志——那份准备自己的告警"
                + "（扫描失败之类）在日志里就成了没头没尾的一段", logged);

        // 一路泵到 b → c 的过渡开始
        boolean entered = false;
        for (int i = 0; i < 300; i++) {
            if (d.pump(buf) == 0) {
                break;
            }
            if (d.inTransition()) {
                entered = true;
                break;
            }
        }
        assertTrue("一直没进入过渡", entered);

        // **用上那份作废的结论的话，这里必然不成立**：那次分析是拿 b 当进场曲
        // 做的，而现在这次过渡的进场曲是 c。
        String note = d.transitionNote();
        assertFalse("用上了已经作废的准备结论（那是给 b 做的）：" + note, note.contains("intro=b.wav"));
        // 正控：换上来必须是**为 c 新做**的那一份。少了这一句，上面那句可以靠
        // 「这次过渡根本没装计划」蒙混过关。
        assertTrue("过渡用的不是为 c 新做的准备结果：" + note, note.contains("intro=c.wav"));
    }

    // ==================================================================
    // 上游 8、9：开始过渡那一刻，生产者线程不做 IO
    // ==================================================================

    /**
     * <strong>开始过渡那一刻，生产者线程一个字节的 IO 都不做。</strong>
     *
     * <p>用户报的「音频开始过渡的时候还是会卡一下」：上一轮把 {@code prepare}（分析）
     * 搬走之后，{@code beginTransition} 里还剩三件重活——{@code Deck.open}（开文件、
     * 探容器、分配缓冲、建重采样核表）、对拍定位的 {@code Deck.seek}、以及造变调器。
     * 它们仍然落在<strong>开始过渡的那一瞬间</strong>，而环形缓冲只有 200 ms。
     *
     * <h3>判据一（主）：重活一条都不能落在生产者线程上</h3>
     *
     * <p>debug 构建下打开一个几十秒的 WAV 只要几毫秒，这笔成本藏在每块解码的
     * 耗时噪声里——<strong>单看耗时的测试永远不会失败</strong>，等于没测。所以改用
     * {@link Director#heavyProbe} 记下每次 {@code Deck.open} / {@code Deck.seek} /
     * 造变调器<strong>是哪条线程做的</strong>，断言它们全都发生在
     * {@code sonalis-prepare} 上、一条都没落在跑 {@code pump()} 的这条线程上。
     *
     * <p>这与「生产者线程不再被 IO 阻塞」<strong>等价</strong>：活不在它身上，它就一秒都
     * 阻塞不了音频；在它身上，几毫秒也是一次真实的阻塞。而且这条判据可判定
     * ——把 {@code Deck.open} 挪回 {@code beginTransition}，记录里立刻出现生产者线程的名字。
     *
     * <h3>判据二（副）：逐块计时，跨过过渡起点那一块也不冒尖</h3>
     *
     * <p>这笔成本本身太小、没有分辨率，所以<strong>把它放大</strong>：
     * {@link WavOpener#armOpenDelay} 让这两首素材的打开多睡 {@link #INJECTED_OPEN_DELAY_MS}
     * （远超一个环形缓冲的量级）。旧实现会在跨过过渡起点的那一块里把它整付掉，
     * 新实现付在准备线程上。
     */
    @Test
    public void handoverRewindsTheIncomingDeckSoNothingIsSkipped() throws IOException {
        // 用户 2026-10-01 报：「融合部分加速了但本体时间没加速，回归到 B 的时候听感进度回跳」。
        // 逐帧查下来是最后一块的余量：Deck.render 固定产出一整块（4096 帧），而窗口常在
        // 块中间结束，多渲染的那截**从没被听到**却让解码位置过去了（实测日志里
        // 「B 前进 12.032 s（素材推进 12.000 s，差 +32 ms）」正是 1536 帧 = 4096 − 2560）。
        //
        // 素材：窗口取 4 s + 1234 帧 @44.1 kHz——**故意不是 4096 的整数倍**，
        // 于是最后一块是部分块，正好是触发这个缺陷的形状（整数倍的窗口测不到它）。
        final int outRate = 44_100;
        Path dir = tempDir("handover");
        Path outro = writeWav(dir.resolve("outro.wav"), sineTrack(6.0f), RATE);
        Path intro = writeWav(dir.resolve("intro.wav"), sineTrack(6.0f), RATE);
        // A 是 6 秒，窗口 4 秒 → 窗口从第 2 秒开始、正好在 A 放完那一刻结束：
        // 最后一块里 A 交不足一整块，混音产出的是**短块**，而 B 已经渲染了 4096 帧——
        // 这就是生产日志里那个形状（`B 前进 12.032 s（素材推进 12.000 s，差 +32 ms）`
        // 与另一处的 +133 ms 都是它，只是收尾方式不同）。
        final long window = (long) outRate * 4L;
        Director d = new Director(listOf(outro, intro), outRate, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                // 必须用**两侧都出声**的假接法：`OutroOnly` 只在最后一块才渲染进场曲
                // （`introStartAt` 把它关在门外），压根碰不到「多渲染一段」那个形状。
                return new BothFade(window);
            }
        });

        // 泵块沿用 `RENDER_FRAMES`：`Deck.render` 要求输出缓冲至少能放一整块
        // （非过渡时段它直接往调用方的缓冲里写），给更小的会当场报错。
        // 触发那个形状的东西因此不是泵块大小，而是**窗口不是块的整数倍**——
        // 上面那个 `+1234` 就是为它加的：整数倍的窗口最后一块正好齐平，测不到。
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        boolean entered = false;
        boolean ended = false;
        boolean pumped = false;
        for (int i = 0; i < 2000; i++) {
            if (pumped && !ended && d.windowFrames() > 0L && d.queuedNextPath() != null) {
                long deadline = System.currentTimeMillis() + 30_000L;
                while (!d.collectPreparation()) {
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    sleepQuietly(2L);
                }
            }
            int n = d.pump(buf);
            if (n == 0) {
                break;
            }
            pumped = true;
            if (d.inTransition()) {
                entered = true;
            } else if (entered) {
                ended = true;
                break;
            }
        }
        assertTrue("一直没进入过渡，这条测试什么也没测到", entered);
        assertTrue("过渡没有走完（泵了 400 块就停了）", ended);
        // 契约（这条判据守的就是它）：交接前后「进场 deck 前进的帧数」必须等于
        // 「过渡真正产出的帧数」——不等就是交接处会跳掉/重放一段。
        //
        // 注意：这一条**不是**那个缺陷的复现器。缺陷的形状（`Deck.render` 一次固定产出
        // 一整块，而收尾时只用到一部分）在这条用例的参数下不一定出现——
        // `lastHandoverUnusedFrames > 0` 只在特定收尾路径上才成立，写死它会让用例时红时绿。
        // 真正的复现点在别的用例里（实测日志：`多渲染了 6400 帧（133 ms）`，那一条现在
        // 打印的是 `差 +0 ms`），这里守的是所有路径共同的那条不变量。
        System.out.println(String.format(java.util.Locale.ROOT,
                "[handover] 多渲染 %d 帧、交接差额 %d 帧",
                Long.valueOf(d.lastHandoverUnusedFrames), Long.valueOf(d.lastHandoverDeltaFrames)));
        assertTrue("交接前后进场 deck 的位置必须一致（差 " + d.lastHandoverDeltaFrames
                        + " 帧，正数=交接时会跳掉一段）",
                Math.abs(d.lastHandoverDeltaFrames) <= 2L);
        // **界面读到的那份位置在交接前后必须逐帧相等**（用户报的「进度回跳」就是它）。
        // 这条判据是第一版修的（回退用 seek 把位置往回拨 → 这里会是 -6400）之后加的，
        // 盯着「已渲染但未交付的余量」那套记账别再退化成 seek。
        assertEquals("界面位置在交接处跳了 " + d.lastHandoverPositionDeltaFrames
                        + " 帧——余量必须留在缓冲里下一块放，不许用 seek 把 deck 拨回去",
                0L, d.lastHandoverPositionDeltaFrames);
    }

    @Test
    public void transitionStartDoesNoIoOnTheProducerThread() throws IOException {
        Path dir = tempDir("noio");
        // 素材名刻意带上上游那两首的名字（判据与路径无关，只是为了与上游对照）。
        // 内容仍是合成出来的：这条测试走的是假接法，它不读文件内容。
        Path outro = writeWav(dir.resolve("long_beat120_am.wav"), sineTrack(12.0f), RATE);
        Path intro = writeWav(dir.resolve("long_beat124_cm.wav"), sineTrack(12.0f), RATE);

        Director d = new Director(listOf(outro, intro), RATE, 2, forcedPlanFactory());

        // 构造时打开当前曲本来就在这条线程上（那是启动，不是过渡），划掉它。
        Director.heavyProbe = Collections.synchronizedList(new ArrayList<Director.HeavyRecord>());
        String producerThread = Thread.currentThread().getName();
        // 放大只对这两首素材生效（判据是路径含 needle）。Java 的 JUnit 是顺序跑的，
        // 不存在上游那种「别的测试并行跑到同一个进程里」的干扰，所以不需要 RAII 解除器
        // ——但 @After 里仍然会解除一次，免得将来有人开并行执行。
        opener.armOpenDelay("long_beat", INJECTED_OPEN_DELAY_MS);

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        PumpRun run = pumpUntilTransition(d, buf, 120_000L, true);

        // ---- 一、过渡真的开始了，用的就是准备线程开好的那一首 ----
        assertEquals("过渡用的不是准备好的那一首——那这条测试什么都没测到",
                intro.toString(), d.nextPath());

        // ---- 二、主判据 ----
        List<Director.HeavyRecord> records = records();
        List<Director.HeavyRecord> onProducer = onThread(records, producerThread);
        assertTrue("开始过渡这条路上，生产者线程（" + producerThread + "）又做重活了："
                + onProducer, onProducer.isEmpty());

        // 正控：这次运行确实做过这三件活，而且都是在准备线程上做的。
        // 没有它，上面那句可以靠「什么都没做」混过去。
        assertTrue("准备线程没有打开进场曲：" + records,
                hasRecord(records, Director.HEAVY_OPEN, "sonalis-prepare", intro.toString()));
        assertTrue("对拍定位没有发生在准备线程上（计划里强塞了 0.5 s 跳过量）：" + records,
                hasRecord(records, Director.HEAVY_SEEK, "sonalis-prepare", intro.toString()));
        assertTrue("变调器不是在准备线程上造的（计划里强塞了出场 +2 半音）：" + records,
                hasKindOnThread(records, Director.HEAVY_PITCH_SHIFTER, "sonalis-prepare"));

        // ---- 三、副判据：逐块计时，没有冒尖 ----
        long crossedMs = run.durationsNanos.get(run.enteredAt).longValue() / 1_000_000L;
        long worstMs = 0L;
        for (int i = 0; i < run.durationsNanos.size(); i++) {
            worstMs = Math.max(worstMs, run.durationsNanos.get(i).longValue() / 1_000_000L);
        }
        List<Long> sorted = new ArrayList<Long>(run.durationsNanos);
        Collections.sort(sorted);
        long medianMs = sorted.get(sorted.size() / 2).longValue() / 1_000_000L;
        assertTrue("跨过过渡起点的那一块花了 " + crossedMs + " ms（中位 " + medianMs
                        + " ms，最坏 " + worstMs + " ms）：注入的打开成本是 "
                        + INJECTED_OPEN_DELAY_MS + " ms、环形缓冲量级是 " + RING_SCALE_MS
                        + " ms，说明这笔成本又落回生产者线程了",
                crossedMs < RING_SCALE_MS);
        assertTrue("整个跑动里出现了 " + worstMs + " ms 的一块（中位 " + medianMs
                + " ms）——超出环形缓冲量级", worstMs < RING_SCALE_MS);
    }

    /**
     * 换<strong>真实接法</strong>（AI 模式）再走一遍：搬动没有把真引擎那条路走坏。
     *
     * <p>与上一条的分工：上一条用假接法<strong>强塞</strong>跳过量与变调，判据齐全、可判定；
     * 这一条不加任何注入，走的是<strong>真的会用到的那条路</strong>——真分析、真计划、
     * 真素材里算出来的对拍跳过量。判定的是同一件事。
     */
    @Test
    public void realEngineTransitionAlsoStartsWithoutIoOnTheProducerThread() throws IOException {
        Path dir = tempDir("realio");
        Path outro = writeWav(dir.resolve("chord_am.wav"), clickTrack(120.0f, 10.0), RATE);
        Path intro = writeWav(dir.resolve("chord_cm.wav"), clickTrack(124.0f, 10.0), RATE);

        Director d = new Director(listOf(outro, intro), RATE, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                return new AiMix(MixStyle.PLAIN_BLEND, RATE, 2);
            }
        });
        Director.heavyProbe = Collections.synchronizedList(new ArrayList<Director.HeavyRecord>());
        String producerThread = Thread.currentThread().getName();

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        pumpUntilTransition(d, buf, 180_000L, true);
        assertEquals("过渡用的不是准备好的那一首", intro.toString(), d.nextPath());

        List<Director.HeavyRecord> records = records();
        List<Director.HeavyRecord> onProducer = onThread(records, producerThread);
        assertTrue("真接法这条路：生产者线程（" + producerThread + "）又做重活了：" + onProducer,
                onProducer.isEmpty());
        // 正控：准备线程确实开了进场曲（否则上面那句可以靠「什么都没做」混过去）
        assertTrue("准备线程没有打开进场曲——这条测试空转了：" + records,
                hasRecord(records, Director.HEAVY_OPEN, "sonalis-prepare", intro.toString()));
    }

    // ==================================================================
    // 上游 10：打不开的进场曲要被跳过并记账
    // ==================================================================

    /**
     * <strong>打不开的进场曲要被跳过并记账，而且 A 的结论绝不能落到 B 身上。</strong>
     *
     * <p>这条守住两样东西，两样都是「搬走」之后最容易悄悄丢的：
     *
     * <ol>
     *   <li>{@code openNext} 的语义：队列里打不开的文件跳过、把原因记进 {@code skipped}
     *       （界面靠它告诉用户「这个文件为什么没放」）。现在跳过发生在准备
     *       线程上，账也得跟着回来——不并回来，界面上就只剩「莫名其妙少了一首」。</li>
     *   <li>「分析的是队首、打开的可能是另一首」这道交叉核对。分析看的是打不开的
     *       那首 A，计划里的 {@code skip_secs} 是按 <strong>A</strong> 的拍点算的；
     *       把它施加到真正打开的 B 上，就是把 B 跳到一个凭空的时刻。
     *       <strong>定位不可逆</strong>，所以核对必须发生在 {@code Deck.seek}
     *       <strong>之前</strong>——这条测试直接盯着这一点：准备线程一次都不该对 B 做过定位。</li>
     * </ol>
     *
     * <p>注意这里的过滤用<strong>完整路径相等</strong>，不是上游那种 {@code contains("intro.wav")}
     * 子串匹配：上游那条子串匹配与 {@code the_note_of_a_finished_transition_does_not_stick}
     * 的临时目录撞名（两者都叫 {@code intro.wav}），在默认的多线程执行下会互相误伤
     * （实测：把修法去掉后那条测试在多线程下红、单线程下绿，红的正是这个撞名）。
     */
    @Test
    public void anUnopenableIntroIsSkippedAndItsPlanIsNotAppliedToAnotherSong() throws IOException {
        Path dir = tempDir("skip");
        Path outro = writeWav(dir.resolve("outro.wav"), sineTrack(2.0f), RATE);
        Path broken = dir.resolve("broken.wav");
        Path intro = writeWav(dir.resolve("intro.wav"), sineTrack(2.0f), RATE);
        // 队首放一个不是音频的文件：它必须被跳过，而不是让整场播放失败
        Files.write(broken, "this is not audio, not even close\n".getBytes(StandardCharsets.UTF_8));

        Director d = new Director(listOf(outro, broken, intro), RATE, 2, forcedPlanFactory());
        Director.heavyProbe = Collections.synchronizedList(new ArrayList<Director.HeavyRecord>());

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        pumpUntilTransition(d, buf, 60_000L, true);

        // ---- 一、跳过的是坏文件，真正上来的是它后面那一首 ----
        assertEquals("跳过了打不开的文件之后，上来应当是它后面那一首",
                intro.toString(), d.nextPath());

        boolean recorded = false;
        List<Director.Skipped> skipped = d.skipped();
        for (int i = 0; i < skipped.size(); i++) {
            Director.Skipped s = skipped.get(i);
            if (broken.toString().equals(s.source) && s.why != null && s.why.trim().length() > 0) {
                recorded = true;
            }
        }
        assertTrue("打不开的进场曲没有被记进 skipped（或者没写原因）：" + skipped, recorded);
        assertEquals("交叉核对没有生效——用上了按 A 算出来的计划",
                "队首曲目打不开，本次不做对拍", d.transitionNote());

        // ---- 二、那次定位一次都不该发生：A 的跳过量不能落到 B 身上 ----
        List<Director.HeavyRecord> introSeeks =
                ofKind(records(), Director.HEAVY_SEEK, intro.toString());
        assertTrue("按打不开的那首算出来的跳过量被施加到了真正打开的那首上：" + introSeeks,
                introSeeks.isEmpty());
    }

    // ==================================================================
    // 上游 11、12：用户选了哪个接法，过渡就得用哪个
    // ==================================================================
    //
    // 用户报的是「桌面端我选别的 mix 模式，界面还是显示是表现模式」。
    // 那排按钮本身只写本地变量（真正生效要等下一次播放请求），但**引擎这一侧不能跟着错**：
    // 一次会话里连着几次过渡，每一次都必须用用户选的那个接法。下面两条测试把这条
    // 不变量钉在引擎上——界面上的选择对不对是界面的事，引擎上「选 A 用 A」必须成立。

    /**
     * <strong>同一个会话里，选的接法要在每一次过渡上都生效。</strong>
     *
     * <p>两个判据：
     *
     * <ol>
     *   <li>每一次过渡的 {@code transitionName()} 都是用户选的那个（不是自动挑的、
     *       也不是上一次过渡留下的）；</li>
     *   <li>每一次过渡用的是<strong>工厂新造的实例</strong>——{@code AiMix} 的 {@code prepare}
     *       会把 {@code AUTO} 改写成具体接法，实例一旦被复用，那个改写就会泄漏到下一次
     *       过渡上（见 {@code startPreparation} 与下一条测试）。</li>
     * </ol>
     */
    @Test
    public void theChosenStyleIsUsedForEveryTransitionInOneSession() throws IOException {
        Path dir = tempDir("style");
        Path first = writeWav(dir.resolve("first.wav"), clickTrack(120.0f, 10.0), RATE);
        Path second = writeWav(dir.resolve("second.wav"), clickTrack(124.0f, 10.0), RATE);

        // 工厂造过几个实例。第 0 个被 `Director` 的构造器直接拿去渲染。
        final AtomicInteger built = new AtomicInteger();
        Director d = new Director(listOf(first, second, first), RATE, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                built.incrementAndGet();
                return new AiMix(MixStyle.BASS_SWAP, RATE, 2);
            }
        });

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        // ---- 第一次过渡 ----
        pumpUntilTransition(d, buf, 120_000L, true);
        assertEquals("用户选的是低音交接，第一次过渡却用了别的接法",
                "低音交接", d.transitionName());
        assertTrue("过渡的结论不是这次接法算出来的：" + d.transitionNote(),
                d.transitionNote().contains("低音交接"));

        // 把这次过渡走完（走完才谈得上「下一次」）
        int guard = 0;
        while (d.inTransition() && guard < 4000) {
            d.pump(buf);
            guard++;
        }
        assertFalse("第一次过渡没能走完（泵了 " + guard + " 块）", d.inTransition());

        // ---- 第二次过渡 ----
        pumpUntilTransition(d, buf, 120_000L, true);
        assertEquals("第二次过渡换成了别的接法——用户的固定选择不许被上一次的实例顶掉",
                "低音交接", d.transitionName());
        assertTrue("只有 " + built.get() + " 个实例被造出来：每一次过渡都该由工厂新造一个"
                + "（渲染 1 + 两次准备）", built.get() >= 3);
    }

    /**
     * <strong>自动挑中的接法，只属于它服务的那一次过渡。</strong>
     *
     * <p>{@code AiMix.prepare} 在 {@code style == AUTO} 时把 {@code style} 改写成挑中的
     * 具体接法——那是「这一次挑中了什么」，<strong>不是</strong>「用户选了什么」。实例要是
     * 跨过渡复用，这个改写就留下来了：第二对素材本来是「匹配 → 朴素融合」，也会被
     * 上一次挑中的表现模式顶掉。用户看到的就是「我选的是自动，怎么每次都表现模式」。
     *
     * <p>素材是刻意配的（合成出来的脉冲轨，速度可推导）：第一对 128 vs 120
     * （速度差 6.3%，够到 3% 的门槛）→ 自动挑表现模式；第二对是同一首接同一首
     * （速度差 0、调性相似度 1.0）→ 自动挑朴素融合。两次的接法必须不同，
     * 否则这条测试没有区分力。
     */
    @Test
    public void anAutoPickDoesNotLeakIntoTheNextTransition() throws IOException {
        Path dir = tempDir("auto");
        Path clashing = writeWav(dir.resolve("clashing.wav"), clickTrack(128.0f, 10.0), RATE);
        Path plain = writeWav(dir.resolve("plain.wav"), clickTrack(120.0f, 10.0), RATE);

        Director d = new Director(listOf(clashing, plain, plain), RATE, 2,
                new TransitionFactory() {
                    @Override
                    public Transition create() {
                        return new AiMix(MixStyle.AUTO, RATE, 2);
                    }
                });

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        pumpUntilTransition(d, buf, 120_000L, true);
        String firstName = d.transitionName();
        String firstNote = d.transitionNote();
        assertEquals("速度对不上的那一对：打分核选中「舞曲·任意调」（结论：" + firstNote + "）",
                // 2026-10-07 改（内核口径修正之后）：**旧结论是被一个 bug 撑着的** ✗ ——
                // RouteScore 原先在闸门没过时返回 **0**（而不是"没有分"），于是"所有候选都没过闸门"
                // 时会硬选一个 0 分的档（这里恰好是 base 50 的「滤波·展开」）。
                // 按他的口径（score = P + 0.001·D **if P > 0**）改成"没过闸门 ⇒ 没有分（null）"之后，
                // 这一对（速度差超 strict）真正过得去的是「舞曲·任意调」：它要的是
                // "调性低或量不出" + 两侧拍数够 —— 这一对的素材正好满足 ✓。
                "舞曲·任意调", firstName);
        // **接线判据**（2026-10-07 新增，理由很实在）：旋律这一维曾经因为"闸门里有、没人喂"
        // 而**死成恒 1.0**，而且很久都没被发现 ✗ —— 因为没有判据看着它。
        // 这里钉住端到端的接线：自动结论里必须带**旋律读数**，而且在真实素材上必须是**量到了**
        // （不是"没量到（中性）"）；叠唱的可靠性同理。
        assertTrue("自动结论里必须带旋律读数（证明它真的喂上了）：" + firstNote,
                firstNote.contains("旋律"));
        assertFalse("真实素材上旋律必须**量到了**，不能是中性兜底：" + firstNote,
                firstNote.contains("旋律 没量到"));
        assertTrue("结论里还要带叠唱可靠性（真中置比那条线的读数）：" + firstNote,
                firstNote.contains("叠唱"));

        int guard = 0;
        while (d.inTransition() && guard < 4000) {
            d.pump(buf);
            guard++;
        }
        assertFalse("第一次过渡没能走完（泵了 " + guard + " 块）", d.inTransition());

        pumpUntilTransition(d, buf, 120_000L, true);
        assertNotEquals("第二对素材是同一首接同一首，自动该挑朴素融合；还是表现模式说明"
                + "上一次的改写留在了实例上（结论：" + d.transitionNote() + "）",
                "表现模式", d.transitionName());
        // **判定契约变了**（2026-10-07 Chooser 重写）："两边本来就合"不再只由速度与调性
        // 决定 —— **低频不冲突**也是条件之一。这对合成素材的低频是两个持续低频叠着
        // （MaterialProfile 量出来 lowClash = 1.00、drop 显著度 0.91），所以自动档给出
        // **学习EQ** 才是对的：它就是为"低频真的撞上"这种情况准备的。
        // 想看"没冲突 ⇒ 朴素融合"那一支，得用低频不重叠的素材（见 Chooser 六档判据）。
        assertEquals("第二对素材（人声叠着、低频 1.00、drop 0.91）⇒ 滤波·长高到低（结论："
                        + d.transitionNote() + "）",
                // 2026-10-07 改（**这正是"可靠人声信号"那条线的成果**）：
                // 他 id 9（滤波·展开，base 50）的清单里有一项 vocal_overlap_incompatible ——
                // 我们此前 vocal 那一项恒软（量不出可靠性）⇒ 它几乎总能过 ✗。
                // 接上**真中置比**（readStereoWindow 拿立体声 ⇒ mid/side ⇒ 人声明显居中才算可靠）之后，
                // 这一对（人声可靠地叠着）会把它**硬筛掉** ✓ ⇒ 落到同样过闸门、base 20 的
                // 「滤波·长高到低」（他的 id 12：strict + loose + 小节落在整小节边界）。
                "滤波·长高到低", d.transitionName());
    }

    // ==================================================================
    // 上游 13：过渡结束后，说明不许再报上一次的结论
    // ==================================================================

    /**
     * <strong>过渡结束后，{@code transitionNote()} 不许再报上一次过渡的结论。</strong>
     *
     * <p>它读的是 {@code activePlan}（非空就用它，否则用已就绪的那一份），而
     * {@code activePlan} 从前只在 {@code beginTransition} 里被赋值、<strong>过渡结束时没人清</strong>。
     * 后果是：一次过渡走完之后，这一路就被那份旧结论永久顶住了——
     * 界面上「接下来这次怎么接」再也更新不了，一直显示上一首的窗口与跳过量。
     *
     * <p>队列只有两首，所以过渡一结束就没有「下一份」可报：必须回到空。
     */
    @Test
    public void theNoteOfAFinishedTransitionDoesNotStick() throws IOException {
        Path dir = tempDir("note-stick");
        Path outro = writeWav(dir.resolve("outro.wav"), sineTrack(2.0f), RATE);
        Path intro = writeWav(dir.resolve("intro.wav"), sineTrack(2.0f), RATE);

        Director d = new Director(listOf(outro, intro), RATE, 2, forcedPlanFactory());

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);
        pumpUntilTransition(d, buf, 60_000L, true);

        // 正控：过渡进行中，报的就是这次过渡的结论。没有它，下面那句
        // 可以靠「这个字段永远是空的」混过去。
        assertEquals("过渡进行中没报这次过渡的结论",
                "强塞计划（0.5 s 定位 / 出场 +2 半音）", d.transitionNote());

        int guard = 0;
        while (d.inTransition() && guard < 4000) {
            d.pump(buf);
            guard++;
        }
        assertFalse("这次过渡没能走完（泵了 " + guard + " 块）", d.inTransition());

        assertTrue("过渡已经结束了，还在报上一次的结论：" + d.transitionNote(),
                d.transitionNote().isEmpty());
    }

    // ==================================================================
    // 本地补充
    // ==================================================================

    /**
     * 本地补充一：<strong>「过渡里交付不出帧」不等于「队列播完了」</strong>
     * （ADR-005 §5③ 那条用 bug 换来的教训，也是 {@code Director.pump} 第 4 步的唯一守卫）。
     *
     * <p>拍点剪切的切点落在出场曲结束<strong>之后</strong>时，那一小段窗口里两路都还没内容，
     * 过渡引擎返回 0——调用方（CLI / 引擎）看到 0 会认为<strong>整场结束、播放提前收尾</strong>。
     * 修法是：见到「过渡里 0 帧」时先把过渡收尾、让进场曲上位，再重试一次。
     *
     * <p><strong>为什么要有这条本地测试</strong>：上游那 13 条里没有一条会因为这个修法
     * 变红（去掉修法、用 {@code --test-threads=1} 跑一遍，13 条全绿，实测过）。
     * 上游的假接法在「出场曲已经放完」那一块都混出了非零帧，唯一会看到 0 的调用方
     * 在 {@code pump} 之上。
     *
     * <p>这里的造法：出场曲 2 秒，而窗口 8 秒——于是过渡在曲子刚开头就触发
     * （剩余 96000 ≤ 384000），出场曲在窗口走到大约四分之一处就耗尽；假接法
     * 在切点之前只交出出场曲（{@code Mixer.gainInto} 对空源返回 0），
     * 而切点在窗口正中（progress 0.5），所以出场曲耗尽那一刻**必然**返回 0。
     * 进场曲取 6 秒：它在过渡期间一直跟着跑，必须比那 2 秒长出足够多，
     * 收尾之后才有音频可交付（否则「收尾那一块也交付了音频」这句测的是别的东西）。
     * 假接法同时数下自己返回过几次 0，测试据此断言「这个局面真的发生了」，
     * 免得这条测试变成空转。**实测**：把 {@code Director.pump} 第 4 步那三行去掉之后，
     * 这条测试在第 24 块上红（「过渡里出现了 0 帧」），其余 15 条一条不变——
     * 也就是说它钉住的正是那条修法。
     */
    @Test
    public void pumpZeroFramesDuringTransitionFinishesInsteadOfEndingPlayback() throws IOException {
        Path dir = tempDir("zero-frame");
        Path outro = writeWav(dir.resolve("outro.wav"), sineTrack(2.0f), RATE);
        Path intro = writeWav(dir.resolve("intro.wav"), sineTrack(6.0f), RATE);

        final long window = (long) RATE * 8L;
        final ZeroCounter counter = new ZeroCounter();
        Director d = new Director(listOf(outro, intro), RATE, 2, new TransitionFactory() {
            @Override
            public Transition create() {
                return new CutFake(window, counter);
            }
        });

        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        // 第一块：准备在这里发起（假接法的 prepare 是瞬时的，但仍然是另一条线程）
        assertTrue("第一块就该有音频", d.pump(buf) > 0);
        long deadline = System.currentTimeMillis() + 30_000L;
        while (!d.collectPreparation()) {
            assertTrue("准备结果一直没回来", System.currentTimeMillis() < deadline);
            sleepQuietly(2L);
        }

        // 一路泵到过渡结束；**过渡里的每一块都必须交付帧**
        boolean entered = false;
        int n = 0;
        for (int i = 0; i < 400; i++) {
            n = d.pump(buf);
            if (d.inTransition()) {
                entered = true;
                // 判定只看实际状态：位完美状态也是从这里推出来的
                assertEquals("过渡窗口内必须是「过渡中」", PathState.TRANSITIONING, d.pathState());
                assertTrue("过渡里出现了 0 帧——调用方会以为整场结束、播放提前收尾"
                        + "（第 " + (i + 1) + " 块）", n > 0);
                continue;
            }
            break;
        }
        assertTrue("一直没进入过渡，这条测试什么也没测到", entered);
        assertTrue("假接法一次都没返回过 0——那个「交付不出帧」的局面没被复现出来，"
                + "这条测试是空转的", counter.zeroRenders.get() > 0);
        assertFalse("过渡没有收尾", d.inTransition());

        // 关键判据：过渡引擎交付不出帧的那一块**照样交付了音频**——修法把过渡收尾、
        // 让进场曲上位，然后重试了一次（这就是「0 不等于整场结束」）。
        // 没有修法的话这一块会返回 0，而且 `inTransition()` 还是真（循环里那句断言会先红）。
        assertTrue("过渡收尾的那一块也必须交付音频（调用方看到 0 会以为整场结束）", n > 0);
        assertEquals("过渡收尾后上位的应当是进场曲", intro.toString(), d.currentPath());
    }

    /**
     * 本地补充二：<strong>位完美状态是算出来的，不是某个模式开关。</strong>
     *
     * <p>同一条判定逻辑（{@code isPassthrough() && gainIsUnity()}）对 0 dB 与
     * 非 0 dB 给出不同结论；非 0 dB 让 {@code pathState()} 变成
     * {@link PathState#PROCESSED}，因为增益是对采样值的乘加——这不是特判。
     * 位完美因此是逐段生效的：歌曲主体保持、过渡窗口放弃（过渡那一段的断言在
     * {@link #pumpZeroFramesDuringTransitionFinishesInsteadOfEndingPlayback()} 里）。
     */
    @Test
    public void pathStateFollowsTheActualStateNotAModeSwitch() throws IOException {
        Path dir = tempDir("path-state");
        Path wav = writeWav(dir.resolve("plain.wav"), sineTrack(4.0f), RATE);
        // 窗口为 0（无缝拼接）→ 不做过渡、不做分析，只看 deck 状态
        Director d = new Director(listOf(wav), RATE, 2, gaplessFactory());
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, Deck.RENDER_FRAMES);

        assertTrue("48k→48k、增益单位、没挂处理单元 → 位完美",
                d.pathState().isBitPerfect());
        assertEquals(PathState.BIT_PERFECT, d.pathState());

        d.setGainDb(-3.0f);
        assertEquals("增益不是 0 dB 就不再位完美（同一条判定逻辑的结果，不是特判）",
                PathState.PROCESSED, d.pathState());

        d.pump(buf);
        d.setGainDb(0.0f);
        d.pump(buf);
        assertEquals("增益回到单位状态后位完美应当恢复", PathState.BIT_PERFECT, d.pathState());
        assertNull("不在过渡里，进度没有值", d.transitionProgress());
    }

    /**
     * 本地补充三：<strong>换下来的 deck 必须被关掉</strong>。
     *
     * <p>上游没有 {@code close()}：deck 被丢弃时 Rust 的 {@code Drop} 会释放解码器
     * （文件句柄 / 网络连接）。Java 没有析构，所以编排器在每一处「上游会丢弃 deck」
     * 的地方显式关（见 {@code Director.retire}）。漏一处就是一个一直挂着的文件句柄
     * ——这在播放器里是实打实的缺陷，而不是风格问题。
     */
    @Test
    public void retiredDecksAreClosedOnHandover() throws IOException {
        Path dir = tempDir("close");
        Path first = writeWav(dir.resolve("first.wav"), sineTrack(1.0f), RATE);
        Path second = writeWav(dir.resolve("second.wav"), sineTrack(1.0f), RATE);

        Director d = new Director(listOf(first, second), RATE, 2, gaplessFactory());
        assertTrue("切歌该成功", d.skipNext());

        List<String> closed = opener.closedSourcesSnapshot();
        assertTrue("换下来的 deck 必须被关掉（不然文件句柄一直挂着）：" + closed,
                closed.contains(first.toString()));
        assertFalse("还在用的 deck 不该被关掉", closed.contains(second.toString()));
    }

    // ==================================================================
    // 泵与等待的辅助
    // ==================================================================

    /** 一次「泵到进入过渡」的结果。 */
    private static final class PumpRun {

        /** 每一块 {@code pump} 的耗时（纳秒），按顺序。 */
        final List<Long> durationsNanos;

        /** 进入过渡的是第几块（下标进 {@code durationsNanos}）。 */
        final int enteredAt;

        PumpRun(List<Long> durationsNanos, int enteredAt) {
            this.durationsNanos = durationsNanos;
            this.enteredAt = enteredAt;
        }
    }

    /** 一次 {@code pump} 的产出与耗时。 */
    private static final class PumpSample {

        final int frames;
        final long nanos;

        PumpSample(int frames, long nanos) {
            this.frames = frames;
            this.nanos = nanos;
        }
    }

    /** 泵一块，返回（帧数，耗时）。上游 {@code pump_once}。 */
    private static PumpSample pumpOnce(Director d, AudioBuf buf) {
        long t0 = System.nanoTime();
        int n = d.pump(buf);
        return new PumpSample(n, System.nanoTime() - t0);
    }

    /**
     * 泵到进入过渡为止，<strong>逐块计时</strong>。
     *
     * <p>{@code waitForReady} 是给<strong>非实时驱动</strong>（测试、离屏渲染）用的：
     * 它们泵得比实时快得多，准备线程追不上，不等就会一路泵到曲子结束
     * （见 {@code Director.collectPreparation}）。等的是<strong>另一条线程</strong>，
     * 不是音频——生产者线程那边一块都没等过，这正是被测的东西。
     */
    private static PumpRun pumpUntilTransition(Director d, AudioBuf buf, long budgetMs,
                                               boolean waitForReady) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        // 第一块**必须泵**：准备是在 `pump` 里发起的，不泵就永远不会有结果，
        // 下面那个「等就绪」就成了死等。
        List<Long> durations = new ArrayList<Long>();
        PumpSample first = pumpOnce(d, buf);
        assertTrue("第一块就没有音频了", first.frames > 0);
        durations.add(Long.valueOf(first.nanos));
        if (d.inTransition()) {
            return new PumpRun(durations, 0);
        }

        while (true) {
            assertTrue("泵了 " + durations.size() + " 块（耗时上限 " + budgetMs
                    + " ms）还没进入过渡", System.nanoTime() < deadline);
            // 到了触发点还没就绪就等一等。**只能在触发点等**：准备要等到
            // 「离末尾还有 窗口+余量」才开始跑，在更早的地方等就是死等。
            if (waitForReady && transitionIsDue(d) && !d.collectPreparation()) {
                sleepQuietly(5L);
                continue;
            }
            PumpSample s = pumpOnce(d, buf);
            durations.add(Long.valueOf(s.nanos));
            if (d.inTransition()) {
                return new PumpRun(durations, durations.size() - 1);
            }
            assertTrue("第 " + durations.size() + " 块就播完了，没能走到过渡", s.frames > 0);
        }
    }

    /**
     * 当前曲是不是已经到了「该开始过渡」的位置。
     *
     * <p>与内部的 {@code shouldBeginTransition} 等价，只是用公开信息重算一遍
     * （它算的是 {@code remaining <= triggerLeadFrames()}）。测试要用它来判断「该开始等了」。
     *
     * <p>注意用的是 {@code triggerLeadFrames()} 而不是 {@code windowFrames()}：
     * 「自动点位」把融合点选在曲中时，提前量会比窗口更大（多出被丢掉的尾巴）。
     */
    private static boolean transitionIsDue(Director d) {
        TrackSpec spec = d.currentSpec();
        Long totalSrc = spec.totalFrames;
        if (totalSrc == null) {
            // 长度未知的流不做过渡，也就没有「到点」这回事
            return false;
        }
        double ratio = (double) d.outRate() / (double) Math.max(spec.sampleRate, 1);
        long totalOut = (long) ((double) totalSrc.longValue() * ratio);
        long remaining = Math.max(totalOut - d.currentFrames(), 0L);
        return remaining <= d.triggerLeadFrames();
    }

    /**
     * 等一个计数涨到 {@code want}，超时就报错。
     *
     * <p><strong>等的是测试线程，不是生产者线程</strong>——这条界限正是被测的东西。
     * 用超时轮询而不是 {@code sleep} 猜时间：失败的表现是「报错」而不是「挂住」。
     */
    private static void waitFor(AtomicInteger counter, int want, String what) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (counter.get() < want) {
            assertTrue(what + "：一直没等到", System.currentTimeMillis() < deadline);
            sleepQuietly(5L);
        }
    }

    /** 睡眠，供轮询用（被打断时恢复中断标志并抛出，绝不静默继续）。 */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }

    // ==================================================================
    // 造素材与临时目录
    // ==================================================================

    /**
     * 本次测试专用的临时目录（每个 tag 一个，测试结束整目录删掉）。
     *
     * <p>上游把目录名写成 {@code sonalis-director-{tag}-{pid}}；Java 的 JUnit 是顺序跑的，
     * 用 {@code createTempDirectory} 拿一个唯一目录更省事，也免得上一次跑剩下来的
     * 文件把「跳过」那条测试带歪。
     */
    private Path tempDir(String tag) {
        try {
            Path dir = Files.createTempDirectory("qplayer-director-" + tag + "-");
            tempDirs.add(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("造不出临时目录", e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try {
            java.io.File f = dir.toFile();
            if (!f.exists()) {
                return;
            }
            java.io.File[] entries = f.listFiles();
            if (entries != null) {
                for (int i = 0; i < entries.length; i++) {
                    if (entries[i].isDirectory()) {
                        deleteRecursively(entries[i].toPath());
                    } else {
                        Files.deleteIfExists(entries[i].toPath());
                    }
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            // 临时目录删不掉不该让测试变红
        }
    }

    private static List<String> listOf(Path a) {
        List<String> out = new ArrayList<String>();
        out.add(a.toString());
        return out;
    }

    private static List<String> listOf(Path a, Path b) {
        List<String> out = listOf(a);
        out.add(b.toString());
        return out;
    }

    private static List<String> listOf(Path a, Path b, Path c) {
        List<String> out = listOf(a, b);
        out.add(c.toString());
        return out;
    }

    /** 440 Hz 正弦、0.3 振幅、双声道——上游 {@code write_test_wav} 的内容。 */
    private static float[] sineTrack(float secs) {
        int frames = (int) ((float) RATE * secs);
        float[] pcm = new float[frames];
        for (int n = 0; n < frames; n++) {
            float t = (float) n / (float) RATE;
            pcm[n] = (float) Math.sin((double) (2.0f * (float) Math.PI * 440.0f * t)) * 0.3f;
        }
        return pcm;
    }

    /**
     * 造一段带拍点的信号：每拍一个衰减脉冲 + 很轻的底噪。
     *
     * <p>与 {@code AiMixTest} 里的是同一套造法（那边是私有的，这里复制一份）。
     * 用脉冲而不是真实音乐，是因为测试要的是<strong>可推导的答案</strong>：BPM 已知，
     * 拍长就是 60/BPM 秒，于是「自动挑哪个接法」这种依赖拍点的判定才有确定的期望。
     *
     * <p>那个 LCG 的状态在 Rust 里是 {@code u32}，所以右移必须是<strong>逻辑</strong>右移
     * （Java 的 {@code int >>} 是算术右移，会把 1 带进来）；Java 的 {@code int}
     * 乘法溢出天然回绕，与 Rust 的 {@code wrapping_mul} 逐位一致。
     */
    private static float[] clickTrack(float bpm, double secs) {
        int n = (int) ((double) RATE * secs);
        float[] v = new float[n];
        int period = (int) ((double) RATE * 60.0 / (double) bpm);

        int state = 0x2545F491;
        for (int i = 0; i < n; i++) {
            state = state * 1_664_525 + 1_013_904_223;
            v[i] = (float) (state >>> 16) / 32768.0f * 0.002f - 0.001f;
        }

        int t = 0;
        int len = (int) ((float) RATE * 0.03f);
        while (t < n) {
            for (int k = 0; k < len; k++) {
                if (t + k >= n) {
                    break;
                }
                float env = (float) Math.exp((double) (-((float) k) / ((float) RATE * 0.006f)));
                float noise = (float) Math.sin((double) ((float) (t + k) * 12989.0f));
                v[t + k] += env * noise * 0.5f;
            }
            t += period;
        }
        return v;
    }

    /**
     * 写一个真的 16 bit / 双声道 WAV。
     *
     * <p>内容对这些测试不重要（假接法不读文件），但<strong>必须是真文件</strong>：
     * 编排器要真的把两个 deck 打开、走完一次过渡；分析型的测试还要真的把尾部与首部读出来。
     */
    private static Path writeWav(Path path, float[] pcm, int rate) throws IOException {
        int frames = pcm.length;
        int dataLen = frames * 2 * 2; // 双声道 × 16 bit
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);                 // PCM
        buf.putShort((short) 2);                 // 双声道
        buf.putInt(rate);
        buf.putInt(rate * 2 * 2);
        buf.putShort((short) 4);
        buf.putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataLen);
        for (int i = 0; i < frames; i++) {
            float s = pcm[i];
            // Rust 的 `as i16` 是向零截断，Java 的 (short) 转换同样是截断（不是舍入）。
            short v = (short) (Math.max(-1.0f, Math.min(1.0f, s)) * 32767.0f);
            buf.putShort(v);
            buf.putShort(v);
        }
        Files.write(path, buf.array());
        return path;
    }

    // ==================================================================
    // 探针记录的小工具
    // ==================================================================

    private static List<Director.HeavyRecord> records() {
        List<Director.HeavyRecord> log = Director.heavyProbe;
        if (log == null) {
            return new ArrayList<Director.HeavyRecord>();
        }
        synchronized (log) {
            return new ArrayList<Director.HeavyRecord>(log);
        }
    }

    private static List<Director.HeavyRecord> onThread(List<Director.HeavyRecord> rs,
                                                       String thread) {
        List<Director.HeavyRecord> out = new ArrayList<Director.HeavyRecord>();
        for (int i = 0; i < rs.size(); i++) {
            if (thread.equals(rs.get(i).thread)) {
                out.add(rs.get(i));
            }
        }
        return out;
    }

    private static List<Director.HeavyRecord> ofKind(List<Director.HeavyRecord> rs, String kind,
                                                     String source) {
        List<Director.HeavyRecord> out = new ArrayList<Director.HeavyRecord>();
        for (int i = 0; i < rs.size(); i++) {
            Director.HeavyRecord r = rs.get(i);
            if (kind.equals(r.kind) && source.equals(r.source)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean hasRecord(List<Director.HeavyRecord> rs, String kind, String thread,
                                     String source) {
        return !ofKind(onThread(rs, thread), kind, source).isEmpty();
    }

    private static boolean hasKindOnThread(List<Director.HeavyRecord> rs, String kind,
                                           String thread) {
        for (int i = 0; i < rs.size(); i++) {
            Director.HeavyRecord r = rs.get(i);
            if (kind.equals(r.kind) && thread.equals(r.thread)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 工厂
    // ==================================================================

    /** 无缝拼接（窗口为 0，不做过渡、不做分析）。 */
    private static TransitionFactory gaplessFactory() {
        return new TransitionFactory() {
            @Override
            public Transition create() {
                return Gapless.INSTANCE;
            }
        };
    }

    /** 假接法的共享计数（上游 {@code FakeState}）。 */
    private static final class FakeState {

        /** {@code prepare} 开工几次（进 sleep 之前计数）。 */
        final AtomicInteger started = new AtomicInteger();

        /** {@code prepare} 完工几次（sleep 之后计数）。 */
        final AtomicInteger finished = new AtomicInteger();

        /** 「没准备过」那个实例渲染了几块。 */
        final AtomicInteger naiveRenders = new AtomicInteger();

        /** 工厂后来造的实例（会 prepare 的那种）渲染了几块。 */
        final AtomicInteger slowRenders = new AtomicInteger();

        /** 工厂一共造了几个实例。第 0 个被 {@code Director} 的构造器直接拿去渲染。 */
        final AtomicInteger built = new AtomicInteger();

        /** 工厂造出来的实例准备睡多久（第 0 个不睡）。 */
        final long sleepMs;

        FakeState(long sleepMs) {
            this.sleepMs = sleepMs;
        }
    }

    /**
     * 假接法：{@code prepare} 故意睡一觉，用来证明<strong>准备不在生产者线程上</strong>。
     */
    private static final class SlowFake implements Transition {

        private final FakeState state;

        /** 这个实例被用来渲染时记到哪个计数上。 */
        private final AtomicInteger renders;

        /** {@code prepare} 睡多久（第 0 个实例是零）。 */
        private final long sleepMs;

        /** {@code windowFrames()} 返回的值。 */
        private final long window;

        /** 名字。<strong>一眼可分「没准备过」与「已准备」</strong>，断言直接看它。 */
        private final String name;

        SlowFake(FakeState state, AtomicInteger renders, long sleepMs, long window, String name) {
            this.state = state;
            this.renders = renders;
            this.sleepMs = sleepMs;
            this.window = window;
            this.name = name;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            renders.incrementAndGet();
            // 混出什么音这条测试不关心，够用就行
            if (progress < 0.5f) {
                return Mixer.gainInto(out, from, 1.0f);
            }
            return Mixer.gainInto(out, to, 1.0f);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int rate) {
            state.started.incrementAndGet();
            if (sleepMs > 0L) {
                sleepQuietly(sleepMs);
            }
            state.finished.incrementAndGet();

            // 一个凭空的「对拍跳过量」：它要是被施加到别的曲子上，就是把
            // 曲子跳到某个毫无道理的时刻——正是「拿 A 的结论处理 B」的样子。
            // 说明里带上**这次分析是给哪一首进场曲做的**，作废那一条靠它断言。
            return new TransitionPlan(0.5, PitchPlan.ZERO, 0.0f,
                    "慢准备·intro=" + fileName(intro));
        }
    }

    /**
     * 造一个「第 0 个实例永远不被 prepare、之后每个都慢」的工厂。
     */
    private static TransitionFactory slowFactory(final FakeState st) {
        return new TransitionFactory() {
            @Override
            public Transition create() {
                int id = st.built.getAndIncrement();
                // id 0 由 `Director` 的构造器直接拿走渲染（它永远不会被 prepare），
                // 后面每个都由准备线程造出来。
                if (id == 0) {
                    return new SlowFake(st, st.naiveRenders, 0L, NOT_PREPARED_WINDOW,
                            "假接法·未准备");
                }
                return new SlowFake(st, st.slowRenders, st.sleepMs, PREPARED_WINDOW,
                        "假接法·已准备");
            }
        };
    }

    /**
     * 假接法：计划里<strong>硬塞</strong>跳过量与出场侧变调。
     *
     * <p>为什么另立一个而不改 {@link SlowFake}：这条测试要在<strong>不受素材分析结果影响</strong>
     * 的前提下把三件重活都逼出来——计划里没有跳过量就不会有 {@code Deck.seek}，
     * 没有出场侧变调就不会有变调器构造，测试会因为「这两首恰好不需要对拍」
     * 而空转。
     */
    private static final class ForcedPlanFake implements Transition {

        /**
         * {@code windowFrames()} 的返回值。第 0 个实例（渲染用的那个）小一些，
         * 好让「窗口从准备好的实例重读回来」也顺带被走到。
         */
        private final long window;

        ForcedPlanFake(long window) {
            this.window = window;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            // 混出什么音这条测试不关心，够用就行
            if (progress < 0.5f) {
                return Mixer.gainInto(out, from, 1.0f);
            }
            return Mixer.gainInto(out, to, 1.0f);
        }

        @Override
        public String name() {
            return "假接法·强塞计划";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }

        @Override
        public TransitionPlan prepare(String outro, String intro, int rate) {
            // 逼出一次对拍定位（0.5 s）与一次变调器构造（出场 +2 半音）
            return new TransitionPlan(0.5, new PitchPlan(2.0f, 0.0f), 0.0f,
                    "强塞计划（0.5 s 定位 / 出场 +2 半音）");
        }
    }

    /** 造「第 0 个窗口小、之后每个都带强塞计划」的假接法。 */
    private static TransitionFactory forcedPlanFactory() {
        final AtomicInteger built = new AtomicInteger();
        return new TransitionFactory() {
            @Override
            public Transition create() {
                int id = built.getAndIncrement();
                // id 0 由 `Director` 的构造器直接拿走渲染（它永远不会被 prepare），
                // 后面每个都由准备线程造出来。
                return new ForcedPlanFake(id == 0 ? NOT_PREPARED_WINDOW : PREPARED_WINDOW);
            }
        };
    }

    /**
     * 只交出<strong>出场曲</strong>的假接法，窗口固定（上游 {@code OutroOnly}）。
     *
     * <p>为什么不直接用真实的交叉淡化来测这个洞：等功率曲线在中段让<strong>两路等响</strong>，
     * 短的那一路缺掉的一段会被另一路填上——听得出来，但 5 ms 的 RMS 只掉一半，
     * 掉不到「洞」的判据以下。真实融合里这个洞之所以刺耳，是因为它正好落在
     * <strong>进场曲还没进来</strong>（或已经走了）的时候，那一刻缺的那一路就是全部声音。
     * 这个假接法把那一刻钉死：出场曲满增益、进场曲零增益，于是但凡出场曲的
     * 尾巴被补零，输出就是<strong>实打实的静音</strong>。
     *
     * <p>它走的仍然是真实混音路径（{@link Mixer#mixTwo}）：帧数取两路的较大者，
     * 短的那一路的尾巴按零处理——这正是要守住的那条边界。
     */
    /** 假接法：整段都按等功率把两侧混起来（进场 deck 从第一块就在跑）。 */
    private static final class BothFade implements Transition {

        private final long window;

        BothFade(long window) {
            this.window = window;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            Mixer.Gains g = Mixer.equalPowerGains(progress);
            return Mixer.mixTwo(out, from, g.from, to, g.to);
        }

        @Override
        public String name() {
            return "假接法·两侧同时出声";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }
    }

    private static final class OutroOnly implements Transition {

        private final long window;

        OutroOnly(long window) {
            this.window = window;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            return Mixer.mixTwo(out, from, 1.0f, to, 0.0f);
        }

        @Override
        public String name() {
            return "假接法·只交出出场曲";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }
    }

    /** 数「假接法在过渡里返回过几次 0 帧」。 */
    private static final class ZeroCounter {

        final AtomicInteger zeroRenders = new AtomicInteger();
    }

    /**
     * 假接法：切点之前<strong>只交出出场曲</strong>，切点之后才交出混合结果。
     *
     * <p>出场曲已经放完（{@code from} 为空）时它返回 0——{@link Mixer#gainInto}
     * 对空源就是返回 0。这正是拍点剪切在「切点落在出场曲结束之后」那一刻的样子，
     * 也是 {@code Director.pump} 第 4 步要处理的那种「过渡里一帧都交付不出」。
     */
    private static final class CutFake implements Transition {

        private final long window;
        private final ZeroCounter counter;

        CutFake(long window, ZeroCounter counter) {
            this.window = window;
            this.counter = counter;
        }

        @Override
        public long windowFrames(int sampleRate) {
            return window;
        }

        @Override
        public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
            int n;
            if (progress < 0.5f) {
                n = Mixer.gainInto(out, from, 1.0f);
            } else {
                n = Mixer.gainInto(out, to, 1.0f);
            }
            if (n == 0) {
                counter.zeroRenders.incrementAndGet();
            }
            return n;
        }

        @Override
        public String name() {
            return "假接法·切点落在出场曲之后";
        }

        @Override
        public boolean rewritesSamples() {
            return true;
        }
    }

    /** 取路径最后一段（上游 {@code Path::file_name}）。 */
    private static String fileName(String source) {
        int cut = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        return cut < 0 ? source : source.substring(cut + 1);
    }

    // ==================================================================
    // 测试替身：一个只认 16 bit PCM WAV 的解码器（带探针）
    // ==================================================================

    /**
     * 测试用的工厂。除了返回替身之外，还做两件属于「上游探针」的事：
     *
     * <ol>
     *   <li>记下每次<strong>开音源 / 定位发生在哪条线程上</strong>（{@code DeckTest} 里同一手法）；</li>
     *   <li>支持给指定路径的打开<strong>注入额外耗时</strong>——上游的
     *       {@code probe::arm_open_delay} 是在 {@code Deck::open} 里睡的，这里睡在
     *       {@code Decoder.open} 里（{@code Deck.open} 的第一件事就是调它），等价。</li>
     * </ol>
     *
     * <p>列表都是同步包装的：准备线程与生产者线程都会往里写。
     */
    private static final class WavOpener implements Decoder.Opener {

        /** 每次 {@link #open} 落在哪条线程上（按调用顺序）。 */
        final List<String> openedOn = Collections.synchronizedList(new ArrayList<String>());

        /** 每次 {@link #open} 打开的是哪个句柄（与 {@link #openedOn} 同序）。 */
        final List<String> openedSources = Collections.synchronizedList(new ArrayList<String>());

        /** 每次 {@code seek} 落在哪条线程上（按调用顺序）。 */
        final List<String> soughtOn = Collections.synchronizedList(new ArrayList<String>());

        /** 每次 {@code seek} 定位的是哪个句柄。 */
        final List<String> soughtSources = Collections.synchronizedList(new ArrayList<String>());

        /** 每次 {@code close} 关掉的是哪个句柄（Java 侧的 {@code Drop} 替身要看的账）。 */
        final List<String> closedSources = Collections.synchronizedList(new ArrayList<String>());

        /** 打开耗时注入：句柄含这个片段时多睡 {@link #delayMs}。{@code null} = 不注入。 */
        private volatile String delayNeedle;

        private volatile long delayMs;

        @Override
        public Decoder open(String source, Map<String, String> headers) {
            openedOn.add(Thread.currentThread().getName());
            openedSources.add(source);
            // 先记账、后放慢：记录是判据，不能被放慢这件事影响（上游同一个顺序）
            String needle = delayNeedle;
            long delay = delayMs;
            if (needle != null && delay > 0L && source.contains(needle)) {
                sleepQuietly(delay);
            }
            return WavDecoder.open(source, this);
        }

        void armOpenDelay(String needle, long ms) {
            this.delayNeedle = needle;
            this.delayMs = ms;
        }

        List<String> closedSourcesSnapshot() {
            synchronized (closedSources) {
                return new ArrayList<String>(closedSources);
            }
        }

        /** 定位发生在哪条线程上（同一个列表被 WavDecoder 写）。 */
        void recordSeek(String source) {
            soughtOn.add(Thread.currentThread().getName());
            soughtSources.add(source);
        }
    }

    /**
     * 只认 16 bit PCM WAV 的最小解码器<strong>替身</strong>（测试用，不是产品代码）。
     *
     * <p>产品路径上 {@code Decoder.open} 打不开音源时会抛 {@link AudioException}，
     * 这里对不存在的文件与不是 RIFF/WAVE 的文件给出同样的行为——「跳过打不开的
     * 进场曲」与「空队列应报错」那几条正是靠这一点才测得到。
     */
    private static final class WavDecoder implements Decoder {

        private final byte[] file;
        private final int dataOff;
        private final int dataLen;
        private final int rate;
        private final int channels;
        private final int bytesPerSample;
        private final int frames;
        private final String source;
        private final WavOpener probe;
        private int pos;
        private boolean closed;

        private WavDecoder(byte[] file, int dataOff, int dataLen, int rate, int channels,
                           int bytesPerSample, String source, WavOpener probe) {
            this.file = file;
            this.dataOff = dataOff;
            this.dataLen = dataLen;
            this.rate = rate;
            this.channels = channels;
            this.bytesPerSample = bytesPerSample;
            this.frames = dataLen / (channels * bytesPerSample);
            this.source = source;
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
            return new WavDecoder(all, dataOff, dataLen, rate, channels, bits / 8, source, probe);
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
                probe.recordSeek(source);
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
            // 上游的 Drop 会在这里释放文件句柄；替身只把「关过谁」记下来，
            // 给「换下来的 deck 必须被关掉」那条本地测试当判据。
            if (!closed) {
                closed = true;
                if (probe != null) {
                    probe.closedSources.add(source);
                }
            }
        }
    }
}
