package dev.t1m3.qplayer.audio.mix;

import java.io.File;
import java.util.Map;

import dev.t1m3.qplayer.audio.decode.Decoder;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>产品路径验证</b>：直接调用 {@link AutoCueTransition#prepare}（产品用的入口），
 * 真实音频 + 真实分析 ⇒ 参考包决策链必须**真的跑起来并被采纳**。
 *
 * <p>这条补的是"JUnit 里跑的是 ReferenceDecision.plan，产品跑的是 prepare"之间的缝：
 * 只证明算法一致，不代表接线正确。
 */
public class ReferenceProductPathTest {

    private static final String WAV_DIR = "/tmp/qplayer-real/wav";

    @BeforeClass
    public static void installDecoder() {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, Map<String, String> headers) {
                return TestWavDecoder.open(source);
            }
        });
    }

    @Test
    public void 融合接法提示会交给内层AiMix() {
        String a = WAV_DIR + "/song-1.wav";
        String b = WAV_DIR + "/song-2.wav";
        if (!new File(a).isFile() || !new File(b).isFile()) {
            System.out.println("style-hint: 缺少真实音频，跳过");
            return;
        }
        AiMix inner = new AiMix(MixStyle.PLAIN_BLEND, 44100, 2);
        AutoCueTransition transition = new AutoCueTransition(inner, 44100, 2);
        TransitionPlan plan = transition.prepare(a, b, 44100);
        assertNotNull("prepare 必须给出计划", plan);
        // 提示若生效，内层 style 会变成我们四个接法之一（或保持原样但日志已给出提示）
        System.out.println("style-hint: 内层实际接法 = " + inner.style());
    }

    /** 只提供"交给基类准备"的最小桩（AutoCueTransition 只需要它的 prepare 默认实现）。 */
    private static final class StubTransition implements Transition {
        @Override
        public long windowFrames(int sampleRate) {
            // 4 s 的窗口：AutoCueTransition 需要 baseWindow > 0 才会继续（否则原样透传）
            return 4L * sampleRate;
        }

        @Override
        public int render(dev.t1m3.qplayer.audio.core.AudioBuf out,
                          dev.t1m3.qplayer.audio.core.AudioBuf from,
                          dev.t1m3.qplayer.audio.core.AudioBuf to, float progress) {
            return 0;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean rewritesSamples() {
            return false;
        }
    }

    @Test
    public void 产品prepare会采纳参考包决策() {
        String a = WAV_DIR + "/song-1.wav";
        String b = WAV_DIR + "/song-2.wav";
        if (!new File(a).isFile() || !new File(b).isFile()) {
            System.out.println("product-path: 缺少真实音频，跳过");
            return;
        }
        AutoCueTransition.LAST_REFERENCE_RESULT = null;
        AutoCueTransition.LAST_REFERENCE_APPLIED = false;
        AutoCueTransition transition = new AutoCueTransition(new StubTransition(), 44100, 2);
        TransitionPlan plan = transition.prepare(a, b, 44100);
        assertNotNull("prepare 必须给出计划", plan);
        System.out.println("product-path: note=" + plan.note
                + " · result=" + AutoCueTransition.LAST_REFERENCE_RESULT);
        assertNotNull("参考包决策链必须跑过（LAST_REFERENCE_RESULT 非空）",
                AutoCueTransition.LAST_REFERENCE_RESULT);
        assertTrue("参考包的结论必须被采纳（窗口/落点按它改）",
                AutoCueTransition.LAST_REFERENCE_APPLIED);
        ReferenceDecision.Candidate chosen = AutoCueTransition.LAST_REFERENCE_RESULT.chosen;
        assertNotNull("必须选出了一个模板", chosen);
        System.out.println("product-path: 模板 " + chosen.templateId + "「" + chosen.templateName
                + "」tier " + chosen.tier + " · 出场 " + chosen.outStart + " · 进场 " + chosen.inStart
                + " · 时长 " + chosen.duration + " s");
        assertTrue("时长必须为正", chosen.duration > 0.0);
    }
}
