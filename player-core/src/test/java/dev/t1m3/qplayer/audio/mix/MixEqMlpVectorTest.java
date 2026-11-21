package dev.t1m3.qplayer.audio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>MLP 推理：Python 与 Java 必须同算法</b>（2026-10-06 第二阶段）。
 *
 * <p>规格锁死：结构 36→14→14→12、两个隐层 ReLU、输出 Sigmoid、不用 BatchNorm/Norm/Dropout；
 * 特征必须先做 {@code (x − mean)/std}（用的是训练时那一组）；固定测试向量下两边误差 < 1e-4；
 * 单次推理 < 1 ms；权重内嵌在 {@code MixEqMlp.java} 里，**损坏就必须能被看出来**
 * （{@link MixEqMlp#load} 返回 null ⇒ 调用方退回 PLAIN_BLEND）。
 *
 * <p>期望值由 {@code tools/automix-ml/export_weights.py} 现算（numpy），
 * 所以这个文件同时钉住：前向式子、标准化用的那一组 mean/std、以及权重的解析。
 */
public class MixEqMlpVectorTest {

    @Test
    public void everyVectorMatchesThePythonSide() {
        MixEqMlp mlp = MixEqMlp.embedded();
        assertNotNull("内嵌权重必须能加载", mlp);
        {
            float[] x = {0.78225148f, 0.67148671f, 0.23738090f, 0.17946133f, 0.34662367f, 0.15210999f, 0.31142952f, 0.23900652f, 0.54355731f, 0.91770851f, 0.44426472f, 0.76028409f, 0.57528077f, 0.51188412f, 0.65716027f, 0.94673004f, 0.91530369f, 0.72023466f, 0.12275448f, 0.90934143f, 0.71328206f, 0.35330292f, 0.22136687f, 0.91407830f, 0.81568705f, 0.34031338f, 0.96281809f, 0.68378707f, 0.03639876f, 0.38488835f, 0.14208660f, 0.35420440f, 0.96771898f, 0.59558710f, 0.39578948f, 0.18387862f};
            double[] want = {0.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 0 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
        {
            float[] x = {0.27742505f, 0.71824451f, 0.91700967f, 0.42292172f, 0.18352396f, 0.50074802f, 0.82354167f, 0.36341172f, 0.48882684f, 0.57166478f, 0.87326735f, 0.69645078f, 0.82750422f, 0.23478284f, 0.36083202f, 0.00787289f, 0.99284121f, 0.13794023f, 0.71772947f, 0.78305351f, 0.81705696f, 0.23756207f, 0.29640472f, 0.64491919f, 0.24609592f, 0.71284013f, 0.26234612f, 0.36387230f, 0.53549529f, 0.86538606f, 0.55542083f, 0.70500333f, 0.92982269f, 0.92670483f, 0.65358495f, 0.22874308f};
            double[] want = {0.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 1 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
        {
            float[] x = {0.60755249f, 0.72169608f, 0.45971434f, 0.74091193f, 0.48540239f, 0.47401345f, 0.17382109f, 0.55975800f, 0.00829095f, 0.09665499f, 0.41962748f, 0.84805218f, 0.07490577f, 0.52538420f, 0.05762873f, 0.19067482f, 0.19450092f, 0.75254834f, 0.42582301f, 0.07852396f, 0.35428083f, 0.31330421f, 0.13918378f, 0.59584762f, 0.46279541f, 0.56573888f, 0.32004873f, 0.99741986f, 0.31586149f, 0.69485119f, 0.71598308f, 0.05260818f, 0.05846310f, 0.22222981f, 0.80328597f, 0.80841436f};
            double[] want = {0.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 2 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
        {
            float[] x = {0.19110176f, 0.49776336f, 0.48452116f, 0.53070989f, 0.14718379f, 0.97159621f, 0.07234222f, 0.38065575f, 0.68362308f, 0.55065688f, 0.39255677f, 0.28394355f, 0.47007892f, 0.90299465f, 0.73638389f, 0.64914778f, 0.66395014f, 0.72134133f, 0.30354277f, 0.79082231f, 0.63209387f, 0.73580899f, 0.88758096f, 0.61736970f, 0.31246236f, 0.34448587f, 0.12730817f, 0.90846630f, 0.21776386f, 0.58999476f, 0.33190617f, 0.37834686f, 0.27794367f, 0.96381183f, 0.06731017f, 0.77719220f};
            double[] want = {0.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 3 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
        {
            float[] x = {0.49812869f, 0.80944024f, 0.62383879f, 0.65994266f, 0.98569112f, 0.94657990f, 0.09788353f, 0.45249871f, 0.01091492f, 0.67305238f, 0.53159876f, 0.18797915f, 0.18325223f, 0.97857850f, 0.69623033f, 0.24060822f, 0.15258378f, 0.51316588f, 0.37726500f, 0.70364678f, 0.34132667f, 0.55333358f, 0.64815155f, 0.16050260f, 0.87923706f, 0.20148454f, 0.67418853f, 0.60425170f, 0.82861201f, 0.95551225f, 0.99279585f, 0.63957377f, 0.36037591f, 0.61986107f, 0.93688350f, 0.16477706f};
            double[] want = {0.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 4 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
        {
            float[] x = {0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f, 0.00000000f};
            double[] want = {0.155077136, 0.999205967, 0.914784280, 0.602780490, 0.999821408, 0.998528612, 0.999647451, 0.999984153, 0.996913943, 0.999204723, 0.999792759, 0.999990716};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 5 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
        {
            float[] x = {1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f};
            double[] want = {0.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000, 1.000000000};
            double[] got = mlp.forward(x, null);
            for (int k = 0; k < MixEqMlp.OUT; k++) {
                assertEquals("第 6 组向量第 " + k + " 个控制点", want[k], got[k], 1e-4);
            }
        }
    }

    @Test
    public void aSingleInferenceIsFarBelowOneMillisecond() {
        MixEqMlp mlp = MixEqMlp.embedded();
        assertNotNull(mlp);
        float[] x = new float[MixEqMlp.IN];
        for (int i = 0; i < x.length; i++) {
            x[i] = (i % 7) * 0.1f;
        }
        double[] out = new double[MixEqMlp.OUT];
        for (int i = 0; i < 2000; i++) {
            mlp.forward(x, out);                       // 预热 + JIT
        }
        long t0 = System.nanoTime();
        int n = 20000;
        for (int i = 0; i < n; i++) {
            mlp.forward(x, out);
        }
        double perCallMicros = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[mlp] 单次推理 %.3f µs（规格要求 < 1 ms）", Double.valueOf(perCallMicros)));
        assertTrue("单次推理必须远小于 1 ms，实测 " + perCallMicros + " µs",
                perCallMicros < 1000.0);
    }

    @Test
    public void aCorruptWeightBlobIsRefusedInsteadOfSilentlyGoingNaN() {
        assertFalse("空权重必须被拒（调用方据此退回 PLAIN_BLEND）",
                MixEqMlp.load("", "", "", "", "", "", "", "") != null);
        assertFalse("维度不对必须被拒", MixEqMlp.load("1 2 3", "0", "0", "0", "0", "0",
                "0", "1") != null);
        String okW1 = repeat("0", MixEqMlp.H1 * MixEqMlp.IN);
        String okB1 = repeat("0", MixEqMlp.H1);
        String okW2 = repeat("0", MixEqMlp.H2 * MixEqMlp.H1);
        String okB2 = repeat("0", MixEqMlp.H2);
        String okW3 = repeat("0", MixEqMlp.OUT * MixEqMlp.H2);
        String okB3 = repeat("0", MixEqMlp.OUT);
        String okMean = repeat("0", MixEqMlp.IN);
        String okStd = repeat("1", MixEqMlp.IN);
        assertNotNull("全零权重是合法的（输出恒 0.5）",
                MixEqMlp.load(okW1, okB1, okW2, okB2, okW3, okB3, okMean, okStd));
        assertFalse("NaN 字面量必须被拒（静默变 NaN 是最难查的故障）",
                MixEqMlp.load(okW1, okB1, okW2, okB2, okW3, okB3, okMean,
                        "NaN " + repeat("1", MixEqMlp.IN - 1)) != null);
        assertFalse("标准差为 0 必须被拒",
                MixEqMlp.load(okW1, okB1, okW2, okB2, okW3, okB3, okMean,
                        "0 " + repeat("1", MixEqMlp.IN - 1)) != null);
    }

    private static String repeat(String v, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
