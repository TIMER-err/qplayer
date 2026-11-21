package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * <b>ReferenceParityTest（分数核心）</b>：Java 版 {@link ReferenceScoring#scoreCore} 必须与
 * 参考包 {@code reference-python/scoring.py} 的 {@code score_core} 逐位一致。
 *
 * <p>期望值由 Python 侧真实实现算出后固化（非手算），容差 1e-12：公式或系数动一个字符就会红。
 */
public class ReferenceParityTest {

    private static void check(double base, double delta, double expect, double... factors) {
        assertEquals("score_core 与参考包不一致", expect,
                ReferenceScoring.scoreCore(base, factors, delta), 1e-12);
    }

    @Test
    public void 分数核心与参考包逐位一致() {
        check(1.889, -250.69, 0.22814941759000001, 0.3169, 0.7999);   // [0.3169, 0.7999] · delta=-250.69
        check(0.333, -201.48, -0.1756788603, 0.2023, 0.383);   // [0.2023, 0.383] · delta=-201.48
        check(0.855, 250.82, 0.33330002376959, 0.3769, 0.9057, 0.2826);   // [0.3769, 0.9057, 0.2826] · delta=250.82
        check(1.044, -298.52, -0.28511751753728, 0.1912, 0.269, 0.2496);   // [0.1912, 0.269, 0.2496] · delta=-298.52
        check(0.832, 72.3, 0.07633698281822678, 0.4598, 0.2565, 0.8742, 0.2164, 0.2998, 0.7254);   // [0.4598, 0.2565, 0.8742, 0.2164, 0.2998, 0.7254] · delta=72.3
        check(1.798, 11.43, 0.2178545246116465, 0.4626, 0.6039, 0.8932, 0.4601);   // [0.4626, 0.6039, 0.8932, 0.4601] · delta=11.43
        check(0.595, 96.53, 0.11184797440335659, 0.988, 0.8831, 0.7245, 0.4989, 0.5083, 0.1606);   // [0.988, 0.8831, 0.7245, 0.4989, 0.5083, 0.1606] · delta=96.53
        check(1.258, -23.2, 0.28541899713693797, 0.8187, 0.7089, 0.4227);   // [0.8187, 0.7089, 0.4227] · delta=-23.2
        check(1.986, -115.64, 0.3246562, 0.2217);   // [0.2217] · delta=-115.64
        check(0.545, -75.43, 0.25048000000000004, 0.598);   // [0.598] · delta=-75.43
        check(1.5, 120.0, 0.0, 0.0, 0.8);   // [0.0, 0.8] · delta=120.0
    }
}
