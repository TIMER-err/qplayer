package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>色度新颖度作为「最低权加分项」参与选点</b>（2026-10-02，用户要求）。
 *
 * <p>用户原话：「色度新颖度没参与打分吗？让它参与，作为比较的加分项，权值很低」。
 *
 * <p>背景：这个信号的边界原先被<b>整个丢弃</b>（强制成 −1）。原因是拿它当<strong>主结构项</strong>
 * 用时输给能量法——实测 skydive 尾声起点 183.9（能量法 179.7、Apple 174.0）、
 * 春めく 进场起点 5.2（能量法 16.3、Apple 15.0），最终点位从 175.3/15.5 退到 180.0/6.0，
 * 参考判据当场红。但它讲的是**和声/音色**变化，与「电平跨过参考线」是两件事，
 * 所以现在改成：主结构项仍是能量法，色度分段另立一项**最低权**加分。
 *
 * <p>判据钉三件事：① 权值是「最低」那一档；② 加分是**加法且封顶**的；
 * ③ 没有这个值时不假装对齐（{@code null} = 一分不加）。
 */
public class AutoCueNoveltyTest {

    /** 权值必须是**最低**的一档，而且低到只能「轻推」。 */
    @Test
    public void theNoveltyBonusIsTheLowestTier() {
        System.out.println(String.format(java.util.Locale.ROOT,
                "[novelty] 权重：色度 %.2f · 结构 %.2f · 小节线 %.2f · 歌词 %.2f（窗口死区 %.3f）",
                Double.valueOf(AutoCue.NOVELTY_WEIGHT), Double.valueOf(AutoCue.STRUCTURE_WEIGHT),
                Double.valueOf(AutoCue.BAR_WEIGHT), Double.valueOf(AutoCue.LINE_GAP_WEIGHT),
                Double.valueOf(AutoCue.WINDOW_TIE_EPS)));
        assertTrue("色度加分必须严格低于结构项", AutoCue.NOVELTY_WEIGHT < AutoCue.STRUCTURE_WEIGHT);
        assertTrue("色度加分必须严格低于小节线加分", AutoCue.NOVELTY_WEIGHT < AutoCue.BAR_WEIGHT);
        assertTrue("色度加分必须严格低于歌词项", AutoCue.NOVELTY_WEIGHT < AutoCue.LINE_GAP_WEIGHT);
        assertTrue("「权值很低」：不得超过 0.02，实际 " + AutoCue.NOVELTY_WEIGHT,
                AutoCue.NOVELTY_WEIGHT <= 0.02);
        assertTrue("容差要比主结构项的 6 秒更宽（色度分段本来就糊一档）",
                AutoCue.NOVELTY_TOLERANCE_SECS > AutoCue.STRUCTURE_TOLERANCE_SECS);
    }

    /** 落在边界上给满分；离得越远越少；超出容差归零。 */
    @Test
    public void theBonusFallsOffWithDistance() {
        AutoCue.Envelope outro = withBoundaries(60.0, 40.0, -1.0);
        AutoCue.Envelope intro = withBoundaries(60.0, -1.0, 10.0);
        assertEquals("正好落在边界上应当满分", 1.0,
                AutoCue.noveltyBonus(outro, intro, 40.0, 10.0).doubleValue(), 1e-9);
        // **两侧都要推远**：这一项是出场点与进场点各自接近度的**平均**，
        // 只推远一侧的话另一侧仍然满分，平均下来是 0.5（第一版断言就是这么被自己抓出来的）。
        assertEquals("两侧都离边界超过容差才一分不给", 0.0,
                AutoCue.noveltyBonus(outro, intro,
                        40.0 + AutoCue.NOVELTY_TOLERANCE_SECS + 1.0,
                        10.0 + AutoCue.NOVELTY_TOLERANCE_SECS + 1.0).doubleValue(), 1e-9);
        double half = AutoCue.noveltyBonus(outro, intro,
                40.0 + AutoCue.NOVELTY_TOLERANCE_SECS / 2.0, 10.0).doubleValue();
        assertTrue("半程应当落在中间（0,1），实际 " + half, half > 0.0 && half < 1.0);
    }

    /** 两侧都没有这个值（手搭包络 / 那次没算出来）→ {@code null}：一分不加，也不假装对齐。 */
    @Test
    public void withoutAnyBoundaryThereIsNoBonusAtAll() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[600], new float[600], 12.0);
        assertNull("没有边界值时这一项整项不加", AutoCue.noveltyBonus(bare, bare, 5.0, 5.0));

        AutoCue.Score base = AutoCue.evaluate(bare, bare, 5.0, 5.0, 4.0);
        assertEquals("withNovelty(null) 必须原样返回、不加一分",
                base.total, base.withNovelty(null).total, 1e-12);
    }

    /** 加分是**加法且封顶**的，上限就是 NOVELTY_WEIGHT（它不参与归一）。 */
    @Test
    public void theBonusIsAdditiveAndCapped() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[600], new float[600], 12.0);
        AutoCue.Score base = AutoCue.evaluate(bare, bare, 5.0, 5.0, 4.0);
        AutoCue.Score with = base.withNovelty(Double.valueOf(1.0));
        assertTrue("加了不该变小", with.total >= base.total - 1e-12);
        assertEquals("上限必须正好是 NOVELTY_WEIGHT", AutoCue.NOVELTY_WEIGHT,
                with.total - base.total, 1e-9);
        AutoCue.Score half = base.withNovelty(Double.valueOf(0.5));
        assertEquals("半分就是一半上限", AutoCue.NOVELTY_WEIGHT / 2.0, half.total - base.total, 1e-9);
    }

    /** 它与其它加分同向叠加（三个 with* 谁先谁后都不该把对方抹掉）。 */
    @Test
    public void itStacksWithTheOtherBonuses() {
        AutoCue.Envelope bare = new AutoCue.Envelope(new float[600], new float[600], 12.0);
        AutoCue.Score base = AutoCue.evaluate(bare, bare, 5.0, 5.0, 4.0);
        AutoCue.Score all = base.withLineGap(Double.valueOf(1.0))
                .withStructure(Double.valueOf(1.0))
                .withBar(Double.valueOf(1.0))
                .withNovelty(Double.valueOf(1.0));
        double expected = AutoCue.LINE_GAP_WEIGHT + AutoCue.STRUCTURE_WEIGHT
                + AutoCue.BAR_WEIGHT + AutoCue.NOVELTY_WEIGHT;
        assertEquals("四项加分应当叠加", expected, all.total - base.total, 1e-9);
        assertNotNull("四项都加完之后仍然是个有效的分数", all);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 一段平包络，带色度分段给出的两个边界（{@code -1} = 没算）。 */
    private static AutoCue.Envelope withBoundaries(double secs, double outroBoundary,
                                                   double introBoundary) {
        int hops = (int) Math.round(secs / AutoCue.HOP_SECS);
        float[] rms = new float[hops];
        float[][] band = new float[hops][];
        Arrays.fill(rms, 0.7f);
        for (int i = 0; i < hops; i++) {
            band[i] = new float[] {0.6f, 0.4f, 0.4f};
        }
        return new AutoCue.Envelope(rms, new float[hops], secs, null, 0, band, null, 0,
                (float) outroBoundary, (float) introBoundary);
    }
}
