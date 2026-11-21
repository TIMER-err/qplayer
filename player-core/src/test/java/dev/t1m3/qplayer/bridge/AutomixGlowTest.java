package dev.t1m3.qplayer.bridge;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>发光必须在过渡开始前三秒就亮</b>（用户 2026-09-30：「让进度条发光在 automix 开始前三秒就有」）。
 *
 * <p>原来它等于快照里的 {@code inTransition}——那一位是过渡<b>开始那一刻</b>才翻的，
 * 而界面也在那一刻跳到进场曲，于是提示和跳页同时发生，用户只看到「A 直接跳了」。
 *
 * <p>判据按边界写，不按实现写：正好十秒要亮、十秒零一毫秒不亮、过渡中（0 / 负数）要亮、
 * <b>{@code NaN}（没有已装载的过渡）绝对不许亮</b>——最后这条最容易写错成
 * {@code !(lead > 3.0)}，那样 NaN 会翻成"亮"，页面就永远亮着。
 */
public class AutomixGlowTest {

    @Test
    public void itLightsUpTenSecondsBeforeTheTransitionStarts() {
        assertFalse("刚装载、还有 30 秒才开始：不该亮", AutomixGlow.active(30.0));
        assertFalse("还有 10.01 秒：还不该亮", AutomixGlow.active(10.01));
        assertTrue("还有 10.00 秒：必须已经亮了（用户点的是「提前十秒」）",
                AutomixGlow.active(AutomixGlow.LEAD_SECS));
        assertTrue("还有 6 秒：亮着", AutomixGlow.active(6.0));
        assertTrue("还有 0.1 秒：亮着", AutomixGlow.active(0.1));
    }

    @Test
    public void itStaysLitThroughTheWholeTransition() {
        assertTrue("过渡刚开始（0）要亮", AutomixGlow.active(0.0));
        assertTrue("过渡走到一半（负的剩余窗口）要亮", AutomixGlow.active(-3.0));
        assertTrue("过渡快结束（-0.01）也要亮着，不许提前灭",
                AutomixGlow.active(-0.01));
    }

    @Test
    public void itNeverLightsUpWhenThereIsNoPendingTransition() {
        assertFalse("没有已装载的过渡（NaN）绝不许预告——"
                        + "拿「离曲终还有多久」冒充提前量会让它在每首歌结尾都亮",
                AutomixGlow.active(Double.NaN));
        assertFalse("负无穷（异常输入）也不亮", AutomixGlow.active(Double.NEGATIVE_INFINITY));
        assertFalse("正无穷（长度未知）不亮", AutomixGlow.active(Double.POSITIVE_INFINITY));
    }
}
