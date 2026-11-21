package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * <b>B 侧交接的代数条件</b>（2026-10-06 第一阶段工作三 B 侧）。
 *
 * <p>背景：对半之后 B 以 {@code speedB = 1/speedA} 播完窗口，消耗的源素材比实时多
 * （提速侧多 {@code 窗口 × (speedB − 1)}），而 B 的 Deck 按实时推进 ⇒ 窗口结束时
 * 会跳一下。修法是让 B 的歌内位置也往前走同样一段
 * （{@link AutoCueTransition#introSkipBonusSecs}）。
 *
 * <p>这条判据钉的是**那个等式本身**，不是某个写死的数——等式成立，接缝就不跳。
 */
public class AutoCueIntroHandoffTest {

    @Test
    public void theSeamIsExact() {
        double entry = 14.9504;
        double window = 5.9910;
        double speedA = 0.9681;                 // 参考对：A 侧降速
        double speedB = 1.0 / speedA;           // B 侧提速（speedA × speedB = 1）
        double bonus = AutoCueTransition.introSkipBonusSecs(window, speedA);

        // ① 素材末尾对应的歌内位置
        double materialEnd = entry + window * speedB;
        // ② Deck 从 (entry + bonus) 起、按实时走完窗口之后的位置
        double deckEnd = (entry + bonus) + window;
        assertEquals("**接缝的代数条件**：两边必须落在同一个歌内位置", materialEnd, deckEnd, 1e-12);

        // ③ 偏移量本身就是 窗口 × (speedB − 1)，且提速侧为正
        assertEquals("偏移 = 窗口 × (1/speedA − 1)", window * (1.0 / speedA - 1.0), bonus, 1e-12);
        org.junit.Assert.assertTrue("参考对是提速侧，偏移必须为正（实测 " + bonus + " s）", bonus > 0.0);
        org.junit.Assert.assertTrue("而且很小（听不出来，但接缝严丝合缝）：" + bonus, bonus < 0.5);
    }

    @Test
    public void noStretchMeansNoOffset() {
        assertEquals("不变速（speedA = 1）时偏移必须是 0——与加这一条之前逐位相同",
                0.0, AutoCueTransition.introSkipBonusSecs(5.99, 1.0), 1e-12);
        assertEquals("窗口为 0 也是 0", 0.0, AutoCueTransition.introSkipBonusSecs(0.0, 0.9), 1e-12);
        assertEquals("速度非法时不猜", 0.0, AutoCueTransition.introSkipBonusSecs(5.99, 0.0), 1e-12);
    }

    @Test
    public void theDirectionFollowsWhichSideIsSlowed() {
        // A 降速（speedA < 1）⇒ B 提速 ⇒ 偏移为正（B 要往前走）
        org.junit.Assert.assertTrue(AutoCueTransition.introSkipBonusSecs(6.0, 0.9) > 0.0);
        // A 提速（speedA > 1）⇒ B 降速 ⇒ 偏移为负（B 要往回退）
        org.junit.Assert.assertTrue(AutoCueTransition.introSkipBonusSecs(6.0, 1.1) < 0.0);
    }
}
