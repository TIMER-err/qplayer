package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>歌词内容级锚点：英文相邻词段 + 中缝交接</b>（2026-10-06 用户提的玩法）。
 *
 * <p>用户的三条修正原话：「歌词不一定一整行都一样，**内部区域一样就行**，我们穿插的是内部区域，
 * 全行一样肯定更好」、「**只有英文参与这个**，中文这些参与的话会显得很诡异」、
 * 「英文要求**至少两个相邻单词相等**才触发」、「触发后切换逻辑差不多就是**前半是 A 的后半换成
 * B 的**了，类比一下，两个单词的话就是第一个单词 A 念第二个单词 B 念」。
 */
public class LyricOverlapTest {

    private static AutoCue.LyricSpans spans(double[] starts, double[] ends, String... texts) {
        return new AutoCue.LyricSpans(starts, ends, texts);
    }

    @Test
    public void onlyEnglishParticipates() {
        AutoCue.LyricSpans a = spans(new double[] {10.0}, new double[] {13.0}, "我们要一起走到最后");
        AutoCue.LyricSpans b = spans(new double[] {20.0}, new double[] {23.0}, "我们要一起走到最后");
        assertFalse("中文行不许参与（用户：会显得很诡异）",
                LyricOverlap.best(a, 0, 60, b, 0, 60).strong());
        // 中英混排也整行跳过
        AutoCue.LyricSpans mixed = spans(new double[] {10.0}, new double[] {13.0}, "hold on 宝贝");
        assertFalse("中英混排整行跳过", LyricOverlap.best(mixed, 0, 60, b, 0, 60).strong());
    }

    @Test
    public void twoAdjacentWordsAreEnoughToTrigger() {
        AutoCue.LyricSpans a = spans(new double[] {10.0}, new double[] {12.0}, "hold on");
        AutoCue.LyricSpans b = spans(new double[] {20.0}, new double[] {23.0}, "hold on tight");
        LyricOverlap.Match m = LyricOverlap.best(a, 0, 60, b, 0, 60);
        assertTrue("两个相邻词相等就该触发：" + m, m.strong());
        assertEquals("重复段是 2 个词", 2, m.runWords);
        // 「两个单词的话就是第一个单词 A 念第二个单词 B 念」⇒ 交接在第 2 个词上（序号 1）
        assertEquals("A 唱第 1 个词、B 从第 2 个词接", 1, m.aHandoverWord);
        assertEquals("B 侧同理", 1, m.bHandoverWord);
    }

    @Test
    public void anInternalRegionIsEnoughAndTheHandoverIsInItsMiddle() {
        AutoCue.LyricSpans a = spans(new double[] {10.0}, new double[] {13.0}, "we will rock you");
        AutoCue.LyricSpans b = spans(new double[] {20.0}, new double[] {24.0}, "yeah we will rock tonight");
        LyricOverlap.Match m = LyricOverlap.best(a, 0, 60, b, 0, 60);
        assertTrue("内部区域一样就该触发：" + m, m.strong());
        assertEquals("最长相邻共同段 = we will rock（3 个词）", 3, m.runWords);
        assertEquals("在 A 里从第 0 个词起", 0, m.aRunStart);
        assertEquals("在 B 里从第 1 个词起", 1, m.bRunStart);
        // 中缝：3 个词 ⇒ 过词数 ceil(3/2) = 2 ⇒ A 唱前 2 个、B 从第 3 个（序号 2）接
        assertEquals("A 交接在序号 2（唱 we will）", 2, m.aHandoverWord);
        assertEquals("B 交接在序号 3（从 rock 接）", 3, m.bHandoverWord);
        // 行内线性插值：A 行 10→13、4 个词 ⇒ 第 2 个词在 10 + 3×(2/4) = 11.5
        assertEquals(11.5, m.aHandoverSecs, 1e-9);
        // B 行 20→24、5 个词 ⇒ 第 3 个词在 20 + 4×(3/5) = 22.4
        assertEquals(22.4, m.bHandoverSecs, 1e-9);
        // 对齐关系：entry = cut + (tB − tA) = cut − offsetSecs
        assertEquals("offsetSecs = tA − tB", 11.5 - 22.4, m.offsetSecs(), 1e-9);
    }

    @Test
    public void aSingleSharedWordIsNotEnoughAndLinesOutsideTheWindowAreIgnored() {
        AutoCue.LyricSpans a = spans(new double[] {10.0, 100.0}, new double[] {12.0, 102.0},
                "tonight", "alpha beta gamma");
        AutoCue.LyricSpans b = spans(new double[] {20.0, 200.0}, new double[] {22.0, 202.0},
                "tonight forever", "delta epsilon zeta");
        LyricOverlap.Match m = LyricOverlap.best(a, 0, 60, b, 0, 60);
        assertFalse("只有一个词相等不许触发（'tonight' 单独不算）", m.strong());
        // 窗口只覆盖后半段 ⇒ 里面没有共同词组 ⇒ 没找到
        LyricOverlap.Match out = LyricOverlap.best(a, 60, 300, b, 60, 300);
        assertFalse("窗口外的词组不许被选中", out.strong());
        assertTrue("没找到时如实返回", out.aLine < 0);
    }

    @Test
    public void withoutLyricTextThereIsNoAnchor() {
        AutoCue.LyricSpans noText = new AutoCue.LyricSpans(new double[] {1.0, 2.0},
                new double[] {1.5, 2.5});
        assertFalse("这一路没给字 ⇒ 不许假装有锚点", noText.hasTexts());
        assertTrue("没有字就没有锚点",
                LyricOverlap.best(noText, 0, 10, noText, 0, 10).aLine < 0);
        AutoCue.LyricSpans mismatched = new AutoCue.LyricSpans(new double[] {1.0, 2.0},
                new double[] {1.5, 2.5}, new String[] {"only one"});
        assertFalse("长度对不上不许半吊子对齐", mismatched.hasTexts());
    }
}
