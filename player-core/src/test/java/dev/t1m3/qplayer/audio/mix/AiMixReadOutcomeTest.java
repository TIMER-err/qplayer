package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * <b>两侧并行读取收工之后的判定</b>（2026-10-03）。
 *
 * <p>为什么值得一条判据：这段判定里藏着三条**行为**规则，而它们是评审当场指出的缺口——
 * 并行化之前不存在「结果与错误都是 null」这种结局，所以谁也没写过它。
 *
 * <ol>
 *   <li>失败**谁先报**：两侧都失败时先出场、后进场——与并行化之前的串行版逐字相同
 *       （用户看到的那句话不能因为「改成并行了」而变）；</li>
 *   <li><b>「没有结果也没有错误」= 这一次准备被中断</b>，不是分析失败：如实退回固定窗口，
 *       绝不拿 null 往下走（`head.pcm` 会 NPE，而 NPE 会被读成「自动混音坏了」）；</li>
 *   <li>只有两侧都能用（都是结果、都没有错误）才返回 {@code null} 放行。</li>
 * </ol>
 */
public class AiMixReadOutcomeTest {

    private static Automix.Mono mono() {
        return new Automix.Mono(new float[] {0.1f, 0.2f}, 44100, Double.valueOf(120.0));
    }

    private static AudioException boom() {
        return AudioException.decode("打不开");
    }

    /**
     * 期望的那句话**用异常自己的 getMessage() 拼**，不写死前缀。
     *
     * <p>判据要钉的是「哪一侧、什么顺序、外面那层措辞」，而不是 {@code AudioException}
     * 工厂给消息加的前缀（那一层是另一处的实现细节，钉死它只会让这条判据跟着它一起变）。
     */
    private static String expected(String side) {
        return side + "曲分析失败（" + boom().getMessage() + "），退回固定窗口";
    }

    /** 两个结果都在、没有错误 → 放行。 */
    @Test
    public void bothReadsAvailableMeansGoAhead() {
        assertNull(AiMix.readFailureNote(mono(), null, mono(), null));
    }

    /** 出场侧失败优先报（与串行版逐字相同：先出场，后进场）。 */
    @Test
    public void theOutgoingFailureIsReportedFirst() {
        String both = AiMix.readFailureNote(mono(), boom(), mono(), boom());
        assertEquals("两侧都失败时先报出场曲那一句（串行版的顺序）", expected("出场"), both);
        assertEquals("只有进场失败时报进场", expected("进场"),
                AiMix.readFailureNote(mono(), null, mono(), boom()));
    }

    /** **中断**：结果与错误都为 null → 如实说「被中断」，不退化成 NPE。 */
    @Test
    public void aMissingResultWithoutAnErrorIsAnInterrupt() {
        assertEquals("读取被中断（准备线程被打断），退回固定窗口",
                AiMix.readFailureNote(mono(), null, null, null));
        assertEquals("两侧都没结果也一样",
                "读取被中断（准备线程被打断），退回固定窗口",
                AiMix.readFailureNote(null, null, null, null));
    }

    /** 中断与失败同时出现时，**失败优先**：那句话对用户更有信息量。 */
    @Test
    public void aReportedFailureWinsOverAnInterrupt() {
        assertEquals(expected("出场"), AiMix.readFailureNote(null, boom(), null, null));
    }
}
