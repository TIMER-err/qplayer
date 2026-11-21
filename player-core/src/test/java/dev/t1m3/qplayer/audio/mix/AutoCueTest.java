package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 「自动点位」挑点的测试：喂合成的包络，验证它挑的位置符合三条硬约束与那套打分。
 *
 * <p>合成数据比真歌好用：想要「副歌在 60 秒、之后是安静的尾声」这种结构，
 * 直接造出来就行，而且断言能写死到秒。
 */
public class AutoCueTest {

    /** 造一条包络：{@code sections} 是若干段 (起始秒, 结束秒, 电平)。 */
    private static AutoCue.Envelope envelope(double durationSecs, double[][] sections) {
        int hops = (int) Math.round(durationSecs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        for (int i = 0; i < hops; i++) {
            double t = i * AutoCue.HOP_SECS;
            double level = 0.5;
            for (double[] section : sections) {
                if (t >= section[0] && t < section[1]) {
                    level = section[2];
                }
            }
            rms[i] = (float) level;
        }
        return AutoCue.envelopeOf(rms, durationSecs);
    }

    private static final double WINDOW = 15.0;

    /**
     * <b>不在曲尾硬切</b>，而且挑出来的那一段交叠必须「两边响度接得上」。
     *
     * <h2>这条判据在 2026-09-26 被改写过（打分升级是用户要求的）</h2>
     *
     * <p>旧打分只看<strong>交接点那一格</strong>的响度差，于是它挑了「两边刚好都在各自
     * 响度台阶上」的位置（179.98 s：出场曲那一格还是 1.0、进场曲那一格也是 1.0）。那个位置
     * 在交接点上完美，整窗却是一路塌下去的（出场曲已经开始掉、进场曲还没起来）。
     * 新的方案级打分看的是<strong>整窗均值</strong>，于是它选了另一个同样合法的位置：
     * 在出场曲还响着的时候切、进场曲也在它的响处进来（实测总分 0.735 vs 0.724）。
     *
     * <p>所以这条判据现在钉的是<strong>不变量</strong>：合法区间、不贴曲尾、
     * 以及「两边整窗均值接近」这条新尺子本身。逐格的挑点偏好属于打分口径，
     * 由 {@code AutoCuePlanTest} 那几条（相位、淡化起伏、窗口时长）单独钉。
     */
    @Test
    public void neverCutsAtTheVeryEndAndKeepsTheTwoSidesLevelMatched() {
        // 4 分钟的歌：0..180 响、180..210 安静（尾声）、210..240 又轻响
        AutoCue.Envelope outro = envelope(240.0, new double[][]{
                {0, 180, 0.9}, {180, 210, 0.15}, {210, 240, 0.4}});
        // 进场曲：开头 15 秒轻、之后是主歌/副歌
        AutoCue.Envelope intro = envelope(200.0, new double[][]{{0, 15, 0.2}, {15, 200, 0.85}});

        AutoCue.Cue cue = AutoCue.plan(outro, intro, WINDOW);
        assertNotNull(cue);
        assertTrue("出场点不许早于 max(20, 20%)：实际 " + cue.outroCutSecs,
                cue.outroCutSecs >= 48.0 - AutoCue.HOP_SECS);
        assertTrue("出场点不许贴到完整结尾（那就是固定窗口的行为）：实际 " + cue.outroCutSecs,
                cue.outroCutSecs <= 240.0 - WINDOW - 2.0 + 0.001);
        assertTrue("进场点不该超过 25%，实际 " + cue.introEntrySecs, cue.introEntrySecs <= 50.0);
        assertEquals(WINDOW, cue.windowSecs, 1e-9);
        assertEquals(240.0 - (cue.outroCutSecs + WINDOW), cue.tailDropSecs, 1e-6);
        assertTrue("说明里应当写清自动点位", cue.note.contains("自动点位"));

        // 新尺子：整窗均值必须接近（旧版只看交接点那一格，才会挑出「点在台阶上、整窗塌掉」）
        int c0 = outro.indexAt(cue.outroCutSecs);
        int e0 = intro.indexAt(cue.introEntrySecs);
        int hops = (int) Math.round(WINDOW / AutoCue.HOP_SECS);
        double meanA = 0;
        double meanB = 0;
        for (int i = 0; i < hops; i++) {
            meanA += outro.rms[Math.min(c0 + i, outro.rms.length - 1)];
            meanB += intro.rms[Math.min(e0 + i, intro.rms.length - 1)];
        }
        meanA /= hops;
        meanB /= hops;
        System.out.printf("### 整窗均值：出场 %.3f、进场 %.3f（差 %.3f）%n",
                Double.valueOf(meanA), Double.valueOf(meanB), Double.valueOf(Math.abs(meanA - meanB)));
        assertTrue("整窗均值必须接得上（差 " + Math.abs(meanA - meanB) + "）",
                Math.abs(meanA - meanB) < 0.25);
    }

    @Test
    public void neverPicksTheVeryStartOrTheVeryEnd() {
        // 一首「一直都在响」的歌：没有任何结构突出的地方，但它仍然不许选边界
        AutoCue.Envelope outro = envelope(300.0, new double[][]{{0, 300, 0.7}});
        AutoCue.Envelope intro = envelope(300.0, new double[][]{{0, 300, 0.7}});
        AutoCue.Cue cue = AutoCue.plan(outro, intro, WINDOW);
        assertNotNull(cue);
        assertTrue("不许早于 20 秒/20%：实际 " + cue.outroCutSecs, cue.outroCutSecs >= 60.0 - 0.1);
        assertTrue("不许贴到完整结尾：实际 " + cue.outroCutSecs + "（时长 300、窗口 15）",
                cue.outroCutSecs <= 300.0 - WINDOW - 2.0 + 0.1);
        assertTrue("进场点不许跑到曲子后半段：实际 " + cue.introEntrySecs, cue.introEntrySecs <= 20.0);
    }

    /**
     * <b>太短的曲子必须返回 null</b>（让调用方退回固定点位，别硬接）。
     *
     * <p><b>2026-10-06 按证据缩了 fixture</b>（第一阶段工作五）：最短尾奏从
     * "20 秒 / 20%" 放到 "10 秒 / 10%" 之后，原来那条 30 秒的素材**已经合格了**
     * ——允许区间 = [max(10, 3) .. 30−15−2] = [10..13]，于是它正确地产出了一个点。
     * 判据的<b>意图</b>（不适合的素材不要硬接）必须保留，所以把素材缩到
     * <b>22 秒</b>：允许区间 = [max(10, 2.2) .. 22−15−2] = [10..5] ⇒ 空 ⇒ 仍然必须 null。
     * 换句话说：这条判据现在钉的是"**连放开后的限制也放不下**"，而不是某个旧数字。
     */
    @Test
    public void shortTracksFallBackToNothing() {
        AutoCue.Envelope shortOutro = envelope(22.0, new double[][]{{0, 22, 0.7}});
        AutoCue.Envelope intro = envelope(200.0, new double[][]{{0, 200, 0.7}});
        assertNull(AutoCue.plan(shortOutro, intro, WINDOW));
    }

    /**
     * <b>「轻对轻」胜过「留得更多」</b>：进场曲开头是轻的，那出场点就该落在出场曲轻的那一段上。
     *
     * <p>与上一条同一处改写：判据现在是<strong>整窗均值</strong>接得上，
     * 而不是交接点那一格的值。实测仍是「轻对轻」（整窗均值 0.107 vs 0.120），
     * 只是落点在轻段起始处前一个 hop（那一处出场曲还响着、紧接着自己掉下去，
     * 属于出场曲的编曲，不是过渡造成的塌陷）。
     */
    @Test
    public void energyContinuityBeatsBeingLater() {
        // 出场曲：0..100 响、100..170 很轻、170..200 又响起来
        AutoCue.Envelope outro = envelope(200.0, new double[][]{
                {0, 100, 0.9}, {100, 170, 0.1}, {170, 200, 0.95}});
        // 进场曲：开头就是很轻的一段（与出场曲轻的那段对得上）
        AutoCue.Envelope intro = envelope(200.0, new double[][]{{0, 25, 0.12}, {25, 200, 0.9}});
        AutoCue.Cue cue = AutoCue.plan(outro, intro, WINDOW);
        assertNotNull(cue);
        assertTrue("应当选到轻的那一段（整窗均值接得上），实际 " + cue.outroCutSecs,
                cue.outroCutSecs >= 100.0 - AutoCue.HOP_SECS && cue.outroCutSecs <= 170.0);
        assertTrue("进场点也应当落在轻的那一段里，实际 " + cue.introEntrySecs,
                cue.introEntrySecs <= 25.0);
    }

    @Test
    public void envelopeNormalizationIgnoresOneLoudSpike() {
        float[] rms = new float[2000];
        java.util.Arrays.fill(rms, 0.2f);
        rms[100] = 50f;      // 一个爆音：不该把其它内容压平
        AutoCue.Envelope envelope = AutoCue.envelopeOf(rms, 100.0);
        assertTrue("归一化该按 95 分位走，实际峰值处的值 " + envelope.rms[100],
                envelope.rms[100] <= 1.0f);
        assertTrue("普通内容不该被压到接近 0，实际 " + envelope.rms[50], envelope.rms[50] > 0.4f);
    }

    @Test
    public void degenerateInputsAreRefused() {
        AutoCue.Envelope ok = envelope(300.0, new double[][]{{0, 300, 0.5}});
        assertNull(AutoCue.plan(null, ok, WINDOW));
        assertNull(AutoCue.plan(ok, null, WINDOW));
        assertNull("窗口 0 没有可融合的片段", AutoCue.plan(ok, ok, 0.0));
    }

    @Test
    public void indexAndSecsRoundTrip() {
        AutoCue.Envelope env = envelope(240.0, new double[][]{{0, 240, 0.5}});
        assertEquals(240.0 / AutoCue.HOP_SECS, env.hops(), 1.0);
        assertEquals(0, env.indexAt(-5.0));
        assertEquals(env.hops() - 1, env.indexAt(1e6));
        double secs = 123.45;
        assertEquals(secs, env.secs(env.indexAt(secs)), AutoCue.HOP_SECS);
    }
}
