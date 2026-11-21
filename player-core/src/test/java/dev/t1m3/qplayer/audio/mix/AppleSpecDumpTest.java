package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;

/**
 * **只读**：把他表在我们代码里的转录**原样打印**成一行一行的数据 ✓（2026-10-09 ✓）。
 *
 * <p>用途：与他的 `TransitionStyles.json` 做**机器对拍**✓（用户：「我估计还有其他你没用他的参数的地方」✓）。
 * 前缀 `SPECDUMP|` 是 ASCII ✓ 便于 grep ✓；测试本身不做断言 ✓（只是导出数据 ✓）。
 */
public class AppleSpecDumpTest {
    @Test
    public void dumpTranscribedSpecs() {
        for (MixStyle style : MixStyle.values()) {
            AppleTransitionSpec.Style spec = AppleStyleMap.specFor(style);
            if (spec == null) {
                continue;
            }
            System.out.println("SPECDUMP|style|" + style.name() + "|" + spec.id + "|"
                    + spec.name + "|" + spec.durationSecs);
            dumpSide("out", spec.outgoing);
            dumpSide("in", spec.incoming);
            if (spec.outgoingOps != null) {
                for (String op : spec.outgoingOps) {
                    System.out.println("SPECDUMP|op|" + style.name() + "|out|" + op);
                }
            }
            if (spec.incomingOps != null) {
                for (String op : spec.incomingOps) {
                    System.out.println("SPECDUMP|op|" + style.name() + "|in|" + op);
                }
            }
        }
    }

    private static void dumpSide(String side, AppleTransitionSpec.Auto[] lanes) {
        if (lanes == null) {
            return;
        }
        for (AppleTransitionSpec.Auto lane : lanes) {
            if (lane == null || lane.segs == null) {
                continue;
            }
            for (AppleTransitionSpec.Seg seg : lane.segs) {
                System.out.println("SPECDUMP|seg|" + side + "|" + lane.key + "|"
                        + seg.t0 + "|" + seg.t1 + "|" + seg.v0 + "|" + seg.v1 + "|"
                        + seg.absT0 + "|" + seg.absT1 + "|" + seg.easeKind + "|" + seg.easeN);
            }
        }
    }
}
