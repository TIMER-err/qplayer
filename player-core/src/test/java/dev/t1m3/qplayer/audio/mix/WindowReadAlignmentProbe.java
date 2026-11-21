package dev.t1m3.qplayer.audio.mix;

import java.io.File;
import java.util.Locale;

import org.junit.Assume;
import org.junit.Test;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import static org.junit.Assert.assertEquals;

/**
 * <b>「同一时刻，两条读法拿到的必须是同一段样本」</b>——过渡窗口里那条对不上的缺陷的判据
 * （2026-10-06）。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>过渡窗口里的 A（出场曲）素材有两个来源，而这两个来源必须落在<strong>同一根时间轴</strong>上：
 *
 * <ol>
 *   <li><b>实时那条</b>：deck 从 0 <em>自然播</em>到窗口起点（{@code Deck.render} 一直推进，
 *       全程不定位）——这是用户在窗口开始前一刻<em>听到</em>的东西；</li>
 *   <li><b>离线那条</b>：{@link Automix#readStereoWindow} 打开同一个文件、{@code seek(startSecs)}
 *       之后读一段——这是真正喂给 WSOLA 的那份素材。</li>
 * </ol>
 *
 * <p>两条对不上，窗口起点就是一次<strong>内容跳变</strong>：素材从 deck 刚播到的位置
 * 往前/往后跳一整帧，听感是过渡一开始「咔」一下。<b>这个判据就是量这个跳变的</b>。
 *
 * <h2>实测（2026-10-06，本机真素材，48 kHz，块长 8192，互相关求偏移）</h2>
 *
 * <pre>
 *   素材            离线窗口 vs deck 自然播放   离线窗口 vs deck.seek(T)   离线 vs ffmpeg 真值
 *   skydive.mp3     +2304 帧（+48.000 ms）      0 帧                      +1152 帧（+24.000 ms）
 *   春めく.mp3       +2304 帧（+48.000 ms）      0 帧                      +1152 帧（+24.000 ms）
 * </pre>
 *
 * <p>（NCC = 1.00000：两条读法的内容是<strong>逐位相同</strong>的同一段，差的只是整整
 * 1152 采样 = 一个 MPEG 帧。三条读法的完整对照表见 {@code main} 的输出。）
 *
 * <p><b>根因不在本包，也不在 {@link Deck}</b>：{@code Deck.seek} 与
 * {@link Automix#readStereoWindow} 走的是<strong>同一条</strong> {@code Decoder.seek}，
 * 两者逐位相同（上表第二列恒为 0）；对不上的是「自然播」这条。同一个文件换成一个
 * 无损 WAV 时两条读法的偏移是 <b>0</b>，所以差异全部出在<strong>解码器层</strong>的
 * MP3 适配器上（桌面端 {@code Mp3CodecAdapter} / {@code Mp3XingTag}，见本轮报告：
 * Xing 的「帧数」不含 Info/Xing 帧、而 jlayer 会把 Info 帧当成一帧解出 1152 个采样，
 * 于是「头部丢 1105 采样」（= LAME 延迟 delay + 528 + 1）只对了<em>音频帧</em>那根轴，
 * 少了 Info 帧的那 1152；另外定位之后解码器状态（jlayer 的比特池记账）没有预热，
 * 落地还会再偏 0~2 帧）。
 *
 * <p><b>2026-10-06：缺陷已修，本判据现在是<em>绿</em>的。</b>修在解码器那一层
 * （{@code Mp3CodecAdapter} / {@code Mp3XingTag}：游标记在音频帧轴上、起点
 * {@code −samplesPerFrame}；定位的最后 {@code RESERVOIR_WARMUP_FRAMES = 3} 帧改用真解码
 * 推进把比特池喂满），同一次提交里新增的判据是
 * {@code Mp3GaplessAxisAlignmentTest}（5 条，进 surefire 默认匹配）。上面那张表里
 * 「修前」两列（+2304 / +1152 帧）就是它当时的红；修后实测四条读法全 <b>0 帧</b>，
 * 无损 WAV 与 48 kHz 对照件也都还是 0。**上面那句"本判据因此现在就是红的"留在这里作账，
 * 不再代表当前状态**——它是"先有红判据、再修"这条纪律的证据，不是一句待办。
 * 一句真话：这条探针留在 player-core 里是为了**不依赖宿主**也能跑（反射找解码器工厂、
 * 找不到就 Assume 跳过），红/绿要看真素材时照下面的手工跑法。
 *
 * <h2>为什么它在 {@code player-core} 里是「探针」而不是普通判据</h2>
 *
 * <p>真素材 + 真解码器只有宿主有：player-core 的测试类路径上没有 jlayer，所以这里用
 * <strong>反射</strong>去找宿主的解码器工厂（{@code DesktopDecoder.install()}），
 * 找不到就 {@code Assume} 跳过——这样 {@code mvn -o -pl player-core test}
 * 的基线条数（1293）一条都不动。要看它的红/绿，用宿主 + 本类的测试类路径手工跑：
 *
 * <pre>
 *   java -cp player-core/target/test-classes:player-core/target/classes:\
 *            desktop-host/target/classes:$(数据依赖) \
 *        dev.t1m3.qplayer.audio.mix.WindowReadAlignmentProbe
 * </pre>
 *
 * <p>（{@code main} 会打全表；{@code compareSamplesAsClasses} 那几条则是判据本体。）
 */
public final class WindowReadAlignmentProbe {

    /** 参考素材：本机没有就跳过（别人的机器、CI 上都该跳过而不是红）。 */
    private static final String[] SONGS = {
        System.getProperty("user.home") + "/Music/skydive.mp3",
        System.getProperty("user.home") + "/Music/春めく.mp3",
    };

    /**
     * 比对窗口长度（帧）。32768 帧 = 0.68 s @48k。
     *
     * <p>不能只取几千帧：模板落在一段安静/自相似的地方时互相关会锁到隔壁的峰上，
     * 量出来的偏移就不是整数帧（实测取 8192 帧时给出 +1721 这种假值）。
     * 下面的 {@link #offsetFrames} 因此先用「能量最大的那一块」当模板。
     */
    private static final int WINDOW = 32768;

    /** 窗口起点（秒）。取一个远离文件头尾的位置，避免首尾裁剪干扰。 */
    private static final double AT_SECS = 60.0;

    /** 偏移容差：一条判据只允许差 1 帧（1152 采样 @48k 是「一个 MPEG 帧」，不是容差）。 */
    private static final int TOLERANCE_FRAMES = 0;

    // ---------------------------------------------------------------- 判据本体

    /**
     * <b>核心判据</b>：同一时刻，离线窗口素材与 deck <em>自然播</em>到的位置必须是同一段样本。
     *
     * <p>这就是「过渡窗口起点跳变」那条缺陷的照妖镜：现在实测偏 +2304 帧（skydive）。
     */
    @Test
    public void offlineWindowMatchesWhatTheDeckNaturallyPlays() {
        Assume.assumeTrue("没有宿主解码器（player-core 单独跑时正常）", installHostDecoder());
        for (String song : SONGS) {
            Assume.assumeTrue("参考曲不在本机，跳过：" + song, new File(song).isFile());
        }
        for (String song : SONGS) {
            float[][] natural = deckNatural(song, AT_SECS, WINDOW);
            float[][] offline = Automix.readStereoWindow(song, null, AT_SECS, WINDOW, rate(song));
            int offset = offsetFrames(natural[0], offline[0]);
            assertEquals("离线窗口素材与 deck 自然播到的位置对不上：" + name(song)
                            + " 在 " + AT_SECS + " s 处偏移 " + offset + " 帧（"
                            + ms(offset, rate(song)) + " ms）。"
                            + "根因在解码器层（MP3 适配器的头部裁剪轴 / 定位预热），不在 Deck/Automix；"
                            + "见 WindowReadAlignmentProbe 的类注释。",
                    TOLERANCE_FRAMES, offset);
        }
    }

    /**
     * 离线窗口读法与 {@link Deck#seek} 必须逐位相同。
     *
     * <p>这条现在是<strong>绿的</strong>，而它是一条有价值的护栏：它把「缺陷在解码器层」
     * 这件事钉住——两条 seek 读法逐位相同，对不上的只有「自然播」那条。
     * 哪天有人在 {@code Deck.seek} 或 {@code Automix.readStereoWindow} 里
     * 单边改口径（比如只给其中一条加补偿），这条会立刻红。
     */
    @Test
    public void offlineWindowMatchesDeckSeekAtTheSameSecond() {
        Assume.assumeTrue("没有宿主解码器（player-core 单独跑时正常）", installHostDecoder());
        for (String song : SONGS) {
            Assume.assumeTrue("参考曲不在本机，跳过：" + song, new File(song).isFile());
        }
        for (String song : SONGS) {
            float[][] seeked = deckSeek(song, AT_SECS, WINDOW);
            float[][] offline = Automix.readStereoWindow(song, null, AT_SECS, WINDOW, rate(song));
            int offset = offsetFrames(seeked[0], offline[0]);
            assertEquals("离线窗口素材与 Deck.seek 读到的位置对不上：" + name(song)
                            + " 在 " + AT_SECS + " s 处偏移 " + offset + " 帧。",
                    TOLERANCE_FRAMES, offset);
        }
    }

    // ---------------------------------------------------------------- 量法

    /** 用宿主解码器工厂（反射安装；没有就返回 false）。 */
    static boolean installHostDecoder() {
        try {
            Class<?> cls = Class.forName("dev.t1m3.qplayer.desktop.audio.decode.DesktopDecoder");
            cls.getMethod("install").invoke(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** deck 从 0 自然播到 {@code atSecs}，再取 {@code n} 帧（左声道）。 */
    static float[][] deckNatural(String source, double atSecs, int n) {
        Deck deck = Deck.open(source, rate(source), 2);
        try {
            return collect(deck, (long) (atSecs * rate(source)), n);
        } finally {
            deck.close();
        }
    }

    /** deck 打开后直接 {@code seek(atSecs)}，再取 {@code n} 帧（左声道）。 */
    static float[][] deckSeek(String source, double atSecs, int n) {
        Deck deck = Deck.open(source, rate(source), 2);
        try {
            deck.seek(atSecs);
            return collect(deck, deck.outFrames(), n);
        } finally {
            deck.close();
        }
    }

    static float[][] collect(Deck deck, long fromAbs, int n) {
        int rate = deck.outRate();
        AudioBuf out = new AudioBuf(ChannelLayout.of(2), Deck.RENDER_FRAMES);
        float[][] res = new float[2][n];
        int have = 0;
        while (have < n) {
            int got = deck.render(out);
            if (got == 0) {
                break;
            }
            long base = deck.outFrames() - got;
            for (int i = 0; i < got && have < n; i++) {
                long rel = base + i - fromAbs;
                if (rel >= 0 && rel < n) {
                    res[0][(int) rel] = out.channel(0).get(i);
                    res[1][(int) rel] = out.channel(1).get(i);
                    have = Math.max(have, (int) rel + 1);
                }
            }
            if (base > fromAbs + n) {
                break;
            }
        }
        return res;
    }

    static int rate(String source) {
        try (Decoder d = Decoder.open(source)) {
            TrackSpec s = d.spec();
            return Math.max(s.sampleRate, 1);
        }
    }

    /** b 相对 a 的偏移（帧）：正 = b 的内容比 a 晚。 */
    static int offsetFrames(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        int seg = 8192;
        if (n < seg * 3) {
            return 0;
        }
        // 模板取「能量最大的那一块」：安静段做互相关会锁错峰（实测给出过 +1721 这种非整数帧）
        int tpl = 0;
        double bestE = -1;
        for (int st = 0; st + seg <= n; st += 1024) {
            double e = 0;
            for (int i = 0; i < seg; i++) {
                e += (double) b[st + i] * b[st + i];
            }
            if (e > bestE) {
                bestE = e;
                tpl = st;
            }
        }
        if (tpl + seg > n) {
            tpl = n - seg;
        }
        int span = 8 * 1152;
        double best = -2;
        int bestLag = 0;
        for (int lag = -span; lag <= span; lag++) {
            int ao = tpl + lag;
            if (ao < 0 || ao + seg > a.length || tpl + seg > b.length) {
                continue;
            }
            double v = ncc(a, ao, b, tpl, seg);
            if (v > best) {
                best = v;
                bestLag = lag;
            }
        }
        return bestLag;
    }

    static double ncc(float[] a, int ao, float[] b, int bo, int n) {
        double am = 0;
        double bm = 0;
        for (int i = 0; i < n; i++) {
            am += a[ao + i];
            bm += b[bo + i];
        }
        am /= n;
        bm /= n;
        double dot = 0;
        double an = 0;
        double bn = 0;
        for (int i = 0; i < n; i++) {
            double va = a[ao + i] - am;
            double vb = b[bo + i] - bm;
            dot += va * vb;
            an += va * va;
            bn += vb * vb;
        }
        return dot / (Math.sqrt(an) * Math.sqrt(bn) + 1e-30);
    }

    static String name(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String ms(int frames, int rate) {
        return String.format(Locale.US, "%+.3f", frames * 1000.0 / rate);
    }

    // ---------------------------------------------------------------- 打印全表

    /**
     * 手工跑：打印每个素材上三条读法的两两偏移。判据红了就看这张表。
     */
    public static void main(String[] args) {
        if (!installHostDecoder()) {
            System.out.println("找不到宿主解码器（dev.t1m3.qplayer.desktop.audio.decode.DesktopDecoder）"
                    + "——player-core 单独跑时是正常的；要在宿主类路径下跑这个探针。");
            return;
        }
        System.out.println("窗口 " + WINDOW + " 帧 @" + AT_SECS + " s；偏移为正 = 后者内容更晚；NCC 越接近 1 越可信");
        for (String song : SONGS) {
            if (!new File(song).isFile()) {
                System.out.println("（跳过，文件不在：" + song + "）");
                continue;
            }
            int rate = rate(song);
            float[][] natural = deckNatural(song, AT_SECS, WINDOW);
            float[][] seeked = deckSeek(song, AT_SECS, WINDOW);
            float[][] offline = Automix.readStereoWindow(song, null, AT_SECS, WINDOW, rate);
            System.out.println(name(song) + "（" + rate + " Hz）");
            System.out.println(String.format(Locale.US, "  离线窗口 vs deck 自然播放 : %+6d 帧（%+8.3f ms）",
                    offsetFrames(natural[0], offline[0]), offsetFrames(natural[0], offline[0]) * 1000.0 / rate));
            System.out.println(String.format(Locale.US, "  离线窗口 vs Deck.seek(T)   : %+6d 帧（%+8.3f ms）",
                    offsetFrames(seeked[0], offline[0]), offsetFrames(seeked[0], offline[0]) * 1000.0 / rate));
            System.out.println(String.format(Locale.US, "  自然播放 vs Deck.seek(T)   : %+6d 帧（%+8.3f ms）",
                    offsetFrames(natural[0], seeked[0]), offsetFrames(natural[0], seeked[0]) * 1000.0 / rate));
        }
    }
}
