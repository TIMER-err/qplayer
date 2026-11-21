package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * <b>三道闸真的能改到限制读数</b>（2026-10-06 第一阶段工作六）。
 *
 * <p>用户要求：「放开限制后，在少数极端情况下过渡会变得很长、变速会很猛、切点会很早。
 * 为了只在旋律匹配足够高时才全放开，保留三道闸：> 0.9 全放开；0.7~0.9 线性插值；
 * < 0.7 保持原限制。」
 *
 * <p>这条判据钉三件事：① 三档读数各自对（含 0.8 恰好插值一半）；② 量不到（NaN）时按原限制；
 * ③ <b>跨模块读数也跟着动</b>——{@code public static final} 是编译期内联的，
 * 所以 {@link AutoCue#maxCutFromEndFraction()} 必须走闸门出口，否则桌面端读到的还是旧值
 * （这个包在这上面栽过一次，注释里记着）。
 */
public class AutoCueMelodyGateWiringTest {

    @Test
    public void theGateMovesEveryLimitAndClearsBack() {
        try {
            AutoCue.applyMelodyGate(1.0);                       // 全放开
            assertEquals("0.25 全放开", 0.25, AutoCue.cutFromEndFractionNow(), 1e-12);
            assertEquals("最短尾奏 10 s", 10.0, AutoCue.minOutroSecsNow(), 1e-12);
            assertEquals("尾奏占比 0.10", 0.10, AutoCue.minOutroFractionNow(), 1e-12);
            assertEquals("**跨模块**读数必须跟着动", 0.25, AutoCue.maxCutFromEndFraction(), 1e-12);

            AutoCue.applyMelodyGate(0.8);                       // 中间：恰好一半
            assertEquals("切点上限插值一半", 0.20, AutoCue.cutFromEndFractionNow(), 1e-12);
            assertEquals("最短尾奏插值一半", 15.0, AutoCue.minOutroSecsNow(), 1e-12);
            assertEquals("尾奏占比插值一半", 0.15, AutoCue.minOutroFractionNow(), 1e-12);

            AutoCue.applyMelodyGate(0.5);                       // 低于下闸：原限制
            assertEquals("切点上限回原限制", 0.15, AutoCue.cutFromEndFractionNow(), 1e-12);
            assertEquals("最短尾奏回原限制", 20.0, AutoCue.minOutroSecsNow(), 1e-12);
            assertEquals("尾奏占比回原限制", 0.20, AutoCue.minOutroFractionNow(), 1e-12);

            AutoCue.applyMelodyGate(Double.NaN);                // 量不到：也按原限制
            assertEquals("量不到就别放开（切点）", 0.15, AutoCue.cutFromEndFractionNow(), 1e-12);
            assertEquals("量不到就别放开（尾奏）", 20.0, AutoCue.minOutroSecsNow(), 1e-12);
        } finally {
            AutoCue.clearMelodyGate();
        }
        assertEquals("关掉闸门 ⇒ 回到放开后的常量（也就是接这一条之前的行为）",
                0.25, AutoCue.cutFromEndFractionNow(), 1e-12);
        assertEquals("跨模块读数同理", 0.25, AutoCue.maxCutFromEndFraction(), 1e-12);
        assertEquals(10.0, AutoCue.minOutroSecsNow(), 1e-12);
        assertEquals(0.10, AutoCue.minOutroFractionNow(), 1e-12);
    }
}
