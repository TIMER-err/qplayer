package dev.t1m3.qplayer.audio.mix;

import java.util.Locale;

/**
 * <b>只打印的探针：{@link TempoRamp#derive} 的「要不要出手」那条门槛在 ratio 两侧各是什么表现</b>
 * （2026-10-06 量，归「对齐网格」那一档的定性）。
 *
 * <p>它回答的是一句可以一眼看穿的算术：{@code derive} 的门是
 * {@code stop = ratio × (1 + TEMPO_JND); if (stop >= 1.0) return off(...)}，
 * 而 {@link TempoRamp#TEMPO_JND} = 0.027 是<b>正数</b> ⇒ 只要 {@code ratio > 1}
 * （= 进场曲比出场曲<b>快</b>），{@code stop > 1.027 > 1} 恒成立，这条斜坡<b>一次都不出手</b>。
 * 而闸门（{@link AutoCueTransition#stretchSpeedFor}）的上界是
 * {@link AutoCueTransition#STRETCH_MAX_SPEED} = 1.08——它<b>允许</b> {@code ratio} 一直取到 1.08。
 * 也就是说这是一条<b>单向斜坡</b>：只降速、不加速。
 *
 * <p>它<strong>不带任何 {@code @Test}</strong>，也不断言任何东西：只把表打出来，
 * 好让这一段读数进文档（用户的纪律：docs 里只抄实测输出，不抄转述）。跑法见类尾注释。
 */
public final class GridTierRampProbe {

    private GridTierRampProbe() {
    }

    /** 与任务给的那一列一致：跨 1.0 的两侧，含闸门两端 0.92 / 1.08 附近。 */
    private static final double[] RATIOS = {0.90, 0.94, 0.96, 0.98, 1.00, 1.02, 1.05, 1.08};

    /** 窗口取 5.0 s：落在"用户设的交叠时长"常见档里，且不会让斜率上限咬住。 */
    private static final double WINDOW = 5.0;

    public static void main(String[] args) {
        System.out.println("GridTierRampProbe · TempoRamp.derive(ratio, " + WINDOW + ")");
        System.out.println("TEMPO_JND = " + TempoRamp.TEMPO_JND
                + "   SLEW_MAX = " + TempoRamp.SLEW_MAX
                + "   STRETCH_MAX_SPEED = " + AutoCueTransition.STRETCH_MAX_SPEED);
        System.out.println("ratio | active | windowSecs | endSpeedFor | absorbedFraction | note");
        for (double ratio : RATIOS) {
            TempoRamp ramp = TempoRamp.derive(ratio, WINDOW);
            System.out.println(String.format(Locale.US,
                    "%.2f  | %-6s | %.4f     | %.4f      | %.4f           | %s",
                    Double.valueOf(ratio),
                    Boolean.valueOf(ramp.active),
                    Double.valueOf(ramp.windowSecs),
                    Double.valueOf(ramp.endSpeedFor(WINDOW)),
                    Double.valueOf(ramp.absorbedFraction(WINDOW)),
                    ramp.note));
        }
        System.out.println();
        System.out.println("· ratio > 1（进场曲更快）的行全部 off ⇒ 单向斜坡（只降速、不加速）");
        System.out.println("· ratio 落在 1.0 的 -2.7%~0 之间（0.973~1.0）也是 off："
                + "两侧拍长差本来就在听阈内");
    }

    // 跑法（不需要跑全量套件）：
    //   M=$(ls -d ~/.m2/wrapper/dists/apache-maven-3.9.11-bin/*/apache-maven-3.9.11/bin/mvn)
    //   $M -o -pl player-core test-compile -q
    //   java -cp player-core/target/classes:player-core/target/test-classes \
    //        dev.t1m3.qplayer.audio.mix.GridTierRampProbe
}
