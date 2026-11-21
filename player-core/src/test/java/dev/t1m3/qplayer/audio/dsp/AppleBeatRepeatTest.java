package dev.t1m3.qplayer.audio.dsp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 照搬 goal 第 41 轮：拍重复器的行为契约 ✓（关着逐位直通 ✓ / 抓一段后循环 ✓ / 改段长重抓 ✓）。 */
public class AppleBeatRepeatTest {

    @Test
    public void whenOffItIsBitIdenticalPassthrough() {
        AppleBeatRepeat r = new AppleBeatRepeat(1, 16);
        for (int i = 0; i < 32; i++) {
            assertEquals(i * 1.0f, r.process(0, i * 1.0f), 0f);
        }
    }

    @Test
    public void afterCaptureItLoopsTheCapturedChunk() {
        AppleBeatRepeat r = new AppleBeatRepeat(1, 16);
        r.setMix(1f);
        r.setLoopFrames(4);
        // 先直通一段（关着 ✓）
        for (int i = 0; i < 4; i++) {
            r.process(0, 100f + i);
        }
        r.setActive(true);
        // 抓取阶段：这 4 个采样仍直通 ✓（值是 0,1,2,3 ✓）
        for (int i = 0; i < 4; i++) {
            assertEquals(i * 1.0f, r.process(0, i * 1.0f), 0f);
        }
        // 之后开始循环 ✓：输出应当是 0,1,2,3 反复 ✓
        for (int rep = 0; rep < 3; rep++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(i * 1.0f, r.process(0, 999f), 0f);
            }
        }
    }

    @Test
    public void changingTheLoopLengthRecaptures() {
        AppleBeatRepeat r = new AppleBeatRepeat(1, 16);
        r.setMix(1f);
        r.setLoopFrames(4);
        r.setActive(true);
        for (int i = 0; i < 4; i++) {
            r.process(0, i * 1.0f);
        }
        // 速率阶梯：段长减半 ⇒ 必须重抓 ✓（抓取阶段仍直通 ✓）
        r.setLoopFrames(2);
        assertEquals(7f, r.process(0, 7f), 0f);
        assertEquals(8f, r.process(0, 8f), 0f);
        // 抓满了 ⇒ 循环这两个值 ✓
        assertEquals(7f, r.process(0, 0f), 0f);
        assertEquals(8f, r.process(0, 0f), 0f);
    }

    @Test
    public void whenSwitchedOffAgainItReturnsToPassthrough() {
        AppleBeatRepeat r = new AppleBeatRepeat(1, 16);
        r.setMix(1f);
        r.setLoopFrames(4);
        r.setActive(true);
        for (int i = 0; i < 8; i++) {
            r.process(0, i * 1.0f);
        }
        r.setActive(false);
        assertEquals(42f, r.process(0, 42f), 0f);
        assertEquals(43f, r.process(0, 43f), 0f);
    }
}
