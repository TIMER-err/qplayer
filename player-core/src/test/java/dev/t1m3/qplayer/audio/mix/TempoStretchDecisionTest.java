package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * <b>什么条件下才变速</b>——每条规则都要能证伪。
 *
 * <p>变速是这一层里唯一<b>改变作品本身</b>的手法，所以宁可少用：拿不准的速度不按它改素材、
 * 比值太大不当成"微调"、比值几乎等于 1 就干脆不动。返回 1.0 的所有分支都意味着
 * 「走原来的路，与从前逐位相同」。
 */
public class TempoStretchDecisionTest {

    /** 参考对：128 vs 120 BPM、置信度 0.944 / 0.987 → 变速比 120/128 = 0.9375。 */
    @Test
    public void thereferencePairGetsStretched() {
        double speed = AutoCueTransition.stretchSpeedFor(128.0, 0.944, 120.0, 0.987);
        assertEquals("应当把出场曲放慢到进场曲的拍长", 120.0 / 128.0, speed, 1e-9);
        System.out.println("[stretch] 128 vs 120 → 变速比 " + speed);
    }

    /** 反方向也一样：进场曲更快时把出场曲<b>加快</b>（speed > 1）。 */
    @Test
    public void theOppositeDirectionAlsoWorks() {
        assertEquals("120 vs 128 → 应当加快", 128.0 / 120.0,
                AutoCueTransition.stretchSpeedFor(120.0, 0.9, 128.0, 0.9), 1e-9);
    }

    /** 置信度不够 → 不动（拿不准的拍长不配改素材）。 */
    @Test
    public void lowConfidenceMeansNoStretch() {
        assertEquals("出场侧不可信", 1.0,
                AutoCueTransition.stretchSpeedFor(128.0, 0.5, 120.0, 0.99), 1e-9);
        assertEquals("进场侧不可信", 1.0,
                AutoCueTransition.stretchSpeedFor(128.0, 0.99, 120.0, 0.5), 1e-9);
        assertEquals("两侧都不可信", 1.0,
                AutoCueTransition.stretchSpeedFor(128.0, 0.2, 120.0, 0.3), 1e-9);
    }

    /**
     * 比值超出 {@code STRETCH_MIN_SPEED~STRETCH_MAX_SPEED} → 不动。
     *
     * <p><b>2026-10-06 按证据重述</b>：原来钉的是 0.92~1.08（于是 20% 与 12% 都被拒），
     * 而用户在第一阶段工作二里明确要求放开到 {@code TempoBridge.MIN_SPEED~MAX_SPEED}
     * （0.75~1.33）：「该变速就变速，该咋滴就咋滴，不要受到神秘条款制约。」
     * 所以这条判据改成**照着常量测**：越界用常量之外的值，边界内用常量本身——
     * 带宽再变时它自动跟着走，不会再变成一份写死的旧数。
     */
    @Test
    public void outOfBandRatiosAreRefused() {
        double tooBig = AutoCueTransition.STRETCH_MAX_SPEED + 0.05;
        double tooSmall = AutoCueTransition.STRETCH_MIN_SPEED - 0.05;
        assertEquals("超出上界（×" + tooBig + "）", 1.0,
                AutoCueTransition.stretchSpeedFor(100.0, 0.95, 100.0 * tooBig, 0.95), 1e-9);
        assertEquals("超出下界（×" + tooSmall + "）", 1.0,
                AutoCueTransition.stretchSpeedFor(100.0, 0.95, 100.0 * tooSmall, 0.95), 1e-9);
        double atMin = AutoCueTransition.STRETCH_MIN_SPEED;
        assertEquals("正好在下界上（x" + atMin + "）应当动", atMin,
                AutoCueTransition.stretchSpeedFor(100.0, 0.95, 100.0 * atMin, 0.95), 1e-9);
    }

    /** 比值几乎等于 1（差不到 0.5%）→ 不动素材。 */
    @Test
    public void aNegligibleDifferenceDoesNotWorthTouchingTheMaterial() {
        assertEquals("差 0.2%", 1.0,
                AutoCueTransition.stretchSpeedFor(120.0, 0.95, 120.24, 0.95), 1e-9);
        assertEquals("差 0.6% 就该动了", 120.72 / 120.0,
                AutoCueTransition.stretchSpeedFor(120.0, 0.95, 120.72, 0.95), 1e-9);
    }

    /** 拿不到 BPM（0 或负）→ 不动。 */
    @Test
    public void withoutTempoThereIsNoStretch() {
        assertEquals(1.0, AutoCueTransition.stretchSpeedFor(0.0, 0.99, 120.0, 0.99), 1e-9);
        assertEquals(1.0, AutoCueTransition.stretchSpeedFor(128.0, 0.99, 0.0, 0.99), 1e-9);
    }
}
