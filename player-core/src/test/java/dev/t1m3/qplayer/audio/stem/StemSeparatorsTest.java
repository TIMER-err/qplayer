package dev.t1m3.qplayer.audio.stem;

import dev.t1m3.qplayer.audio.model.InferException;
import dev.t1m3.qplayer.audio.model.ModelAvailability;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.audio.model.StemSeparator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 选择入口本身：三档的「能不能用」「叫什么」「打不开时说什么」，以及
 * <b>越界值不许退回任何一档</b>。
 *
 * <p>这是「包装接法不认识任何具体实现」那条设计的另一半：分类与措辞都归这里，
 * 所以 {@code VocalSeparationTransition} 只认 {@link StemSeparators}。
 */
public class StemSeparatorsTest {

    @Before
    public void cleanSeams() {
        StemModels.uninstall();
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    @After
    public void restoreSeams() {
        StemModels.uninstall();
        StemSeparators.uninstallDsp();
        ModelDownloads.uninstall();
        ModelAvailability.reset();
    }

    /**
     * <b>DSP 那一档永远可用</b>——它不依赖任何外部文件。
     *
     * <p>这正是它「不需要下载门控」的实现依据：磁盘上一个字节都没有时，
     * {@code available(DSP)} 仍然为真，而 {@code available(AI)} 为假。
     */
    @Test
    public void dspIsAlwaysAvailableWhileAiFollowsTheDisk() {
        assertTrue("DSP 不该受磁盘上有没有权重影响",
                StemSeparators.available(StemSeparators.DSP));
        assertFalse("没有权重时 AI 那一档必须是 false", StemSeparators.available(StemSeparators.AI));
        assertFalse("「不分离」不是一个能用的分离器档",
                StemSeparators.available(StemSeparators.OFF));
        assertFalse("越界值一律不可用", StemSeparators.available(42));
    }

    /** 默认的 DSP 实现就是 player-core 自己那份（接缝真的接上了，不是空壳）。 */
    @Test
    public void theDefaultDspOpenerIsThePureJavaImplementation() {
        StemSeparator sep = StemSeparators.forMode(StemSeparators.DSP);
        assertNotNull(sep);
        assertTrue("默认实现应当是 DspStemSeparator，实际 " + sep.getClass().getName(),
                sep instanceof DspStemSeparator);
        sep.close();
    }

    /** DSP 那一档的实现可以换掉（宿主换实现、测试造失败）。 */
    @Test
    public void theDspImplementationCanBeSwapped() {
        final boolean[] used = new boolean[1];
        StemSeparators.installDsp(new StemSeparators.Opener() {
            @Override
            public StemSeparator open() {
                used[0] = true;
                throw InferException.run("测试替身");
            }
        });
        try {
            StemSeparators.forMode(StemSeparators.DSP);
            fail("应当把替身的异常抛出来");
        } catch (InferException expected) {
            assertTrue("打开的应当是换上去的那个实现", used[0]);
        }
        // 换回来之后又是默认实现
        StemSeparators.uninstallDsp();
        assertTrue(StemSeparators.forMode(StemSeparators.DSP) instanceof DspStemSeparator);
    }

    /** AI 那一档没有实现时抛的是「没装实现」，而不是静默换成别的档。 */
    @Test
    public void openingAiWithoutAHostImplementationThrows() {
        try {
            StemSeparators.forMode(StemSeparators.AI);
            fail("没有装实现时 forMode(AI) 必须抛");
        } catch (InferException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("分轨模型实现"));
        }
    }

    /**
     * <b>越界值不退回任何一档。</b>
     *
     * <p>退回某一档会把「设置里写了个奇怪的值」变成「悄悄用了另一种算法」——
     * 那是查不出来的那种错（界面上一切正常，只有听感不对）。
     */
    @Test
    public void anUnknownModeOpensNothing() {
        assertEquals(StemSeparators.OFF, StemSeparators.DEFAULT_MODE);
        assertTrue(StemSeparators.isKnownMode(StemSeparators.OFF));
        assertTrue(StemSeparators.isKnownMode(StemSeparators.DSP));
        assertTrue(StemSeparators.isKnownMode(StemSeparators.AI));
        assertFalse(StemSeparators.isKnownMode(7));
        try {
            StemSeparators.forMode(7);
            fail("越界的档必须抛，而不是退回某一档");
        } catch (InferException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不是一个分离器档"));
        }
    }

    /** 档名是用户在下拉里看到的那个词（note 与日志写的就是它）。 */
    @Test
    public void labelsMatchWhatTheSettingsRowCallsThem() {
        assertEquals("DSP", StemSeparators.label(StemSeparators.DSP));
        assertEquals(StemModels.MODEL_NAME, StemSeparators.label(StemSeparators.AI));
        assertEquals("不分离", StemSeparators.label(StemSeparators.OFF));
    }

    /**
     * 「为什么不能用」的措辞逐档不同，而且<b>AI 那一句要指向下载</b>。
     *
     * <p>它会被写进 {@code plan.note}：说错了用户就会去下载一个用不上的东西
     * （或者更糟——以为该去下载，其实那是 DSP 那一档）。
     */
    @Test
    public void unavailableReasonsArePerMode() {
        assertTrue(StemSeparators.unavailableReason(StemSeparators.AI)
                .contains(StemModels.MODEL_NAME));
        assertTrue(StemSeparators.unavailableReason(StemSeparators.AI).contains("下载"));
        assertFalse("DSP 那一档不该提下载",
                StemSeparators.unavailableReason(StemSeparators.DSP).contains("下载"));
        assertFalse("「不分离」那句不该提下载",
                StemSeparators.unavailableReason(StemSeparators.OFF).contains("下载"));
    }
}
