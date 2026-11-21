package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/**
 * 自动接法白名单的契约（用户 2026-10-09：「给 AI 自动模式添加个列表选择，
 * 我可以选自动模式会选哪些模式」✓）。
 *
 * <p>钉住三件事：**空 = 全允许**（旧行为 ✓）、**逗号串原样保留**（顺序 = 偏好 ✓）、
 * **null 归一成空**（不炸 ✓）。真正的"未勾选 ⇒ 换掉"那条路径由
 * {@code AiMix.whitelisted(...)} 在选档出口执行 ✓（这里只守输入契约 ✓）。
 */
public class AiMixStyleWhitelistTest {

    @After
    public void reset() {
        AiMix.setAutoAllowedStyleNames("");
    }

    @Test
    public void emptyMeansEverythingAllowed() {
        AiMix.setAutoAllowedStyleNames("");
        assertEquals("", AiMix.autoAllowedStyleNames());
    }

    @Test
    public void nullNormalisesToEmpty() {
        AiMix.setAutoAllowedStyleNames(null);
        assertEquals("", AiMix.autoAllowedStyleNames());
    }

    @Test
    public void trimsOuterWhitespaceAndKeepsOrder() {
        AiMix.setAutoAllowedStyleNames("  A , B ,C  ");
        assertEquals("A , B ,C", AiMix.autoAllowedStyleNames());
    }
}
