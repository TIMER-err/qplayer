package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.util.Logger;

/**
 * 「手动点位」那一层包装（P4 控制室的<b>调</b>那一半）：融合点与切入点由用户给定，
 * 而不是由自动点位扫出来。
 *
 * <h2>它在链上的位置</h2>
 *
 * <p>与 {@link AutoCueTransition} 完全同构——都是「只换点位、不换素材」的一层包装，
 * 区别只有一个：那个去扫整曲找点，这个直接用用户给的两个数。
 * 所以两者的嵌套关系也必须一样（在去人声那层<strong>里面</strong>），
 * 否则去人声读到的还是旧的点位（这条在 {@code AutoCueTransition} 里踩过一次）。
 *
 * <h2>三个数怎么落地</h2>
 *
 * <ul>
 *   <li>{@code outroSecs} → 出场曲从第几秒开始交叠；被丢掉的尾巴 = 全曲 − 交叠 − 窗口，
 *       编排器据此把触发点提前（{@code TransitionPlan.outroTailSecs}）；</li>
 *   <li>{@code introSecs} → 进场曲从第几秒进（{@code TransitionPlan.skipSecs}）；</li>
 *   <li>窗口照旧由被包的接法决定（用户设的 6/8/10 秒）——手动点位管的是「在哪儿」，
 *       不是「多久」。</li>
 * </ul>
 *
 * <p>两个数都是<strong>秒</strong>，负数与 NaN 一律当「没给」（退回被包接法的点位），
 * 越界值由 {@link #prepare} 夹到曲子范围内——设置文件是用户能手改的 JSON，
 * 引擎不能假定它合法。
 */
public final class ManualCue implements Transition {

    private final Transition base;

    /** 出场曲的融合点（秒）；< 0 = 不覆盖。 */
    private final double outroSecs;

    /** 进场曲的切入点（秒）；< 0 = 不覆盖。 */
    private final double introSecs;

    public ManualCue(Transition base, double outroSecs, double introSecs) {
        if (base == null) {
            throw new IllegalArgumentException("被包住的接法不能为 null：这一层只改点位");
        }
        this.base = base;
        this.outroSecs = outroSecs;
        this.introSecs = introSecs;
    }

    /** 被包住的接法（诊断用）。 */
    public Transition inner() {
        return base;
    }

    public double outroSecs() {
        return outroSecs;
    }

    public double introSecs() {
        return introSecs;
    }

    @Override
    public long windowFrames(int sampleRate) {
        return base.windowFrames(sampleRate);
    }

    @Override
    public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
        return base.render(out, from, to, progress);
    }

    @Override
    public String name() {
        return base.name() + " · 手动点位";
    }

    @Override
    public boolean rewritesSamples() {
        return base.rewritesSamples();
    }

    @Override
    public PitchPlan pitchAt(float progress) {
        return base.pitchAt(progress);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int sampleRate) {
        return prepare(outro, intro, sampleRate, null, null);
    }

    @Override
    public TransitionPlan prepare(String outro, String intro, int sampleRate,
                                  java.util.Map<String, String> outroHeaders,
                                  java.util.Map<String, String> introHeaders) {
        TransitionPlan basePlan = base.prepare(outro, intro, sampleRate, outroHeaders, introHeaders);
        double entry = basePlan.skipSecs;
        double tail = basePlan.outroTailSecs;
        String what = "";

        if (introSecs > 0.0) {
            // 切入点：先问解码器要时长，好把它夹进曲子范围内（不猜时长：未知就不覆盖）
            Double introDuration = durationOf(intro, introHeaders);
            double value = introSecs;
            if (introDuration != null && introDuration.doubleValue() > 0.0) {
                // 至少给窗口留出位置（否则进场曲在融合期里就放完了）
                double window = (double) base.windowFrames(sampleRate) / Math.max(1, sampleRate);
                double max = Math.max(0.0, introDuration.doubleValue() - window);
                value = Math.min(value, max);
            }
            entry = value;
            what = "进场 " + fmt(value) + "s";
        }
        if (outroSecs > 0.0) {
            Double outroDuration = durationOf(outro, outroHeaders);
            double value = outroSecs;
            if (outroDuration != null && outroDuration.doubleValue() > 0.0) {
                double window = (double) base.windowFrames(sampleRate) / Math.max(1, sampleRate);
                // 至少留出「窗口 + 2 秒」的尾巴，否则融合期还没走完出场曲就没了
                double max = Math.max(0.0, outroDuration.doubleValue() - window - 2.0);
                value = Math.min(value, max);
                tail = Math.max(0.0, outroDuration.doubleValue() - (value + window));
            }
            what = what.isEmpty() ? ("出场 " + fmt(value) + "s")
                    : (what + "、出场 " + fmt(value) + "s");
        }
        if (what.isEmpty()) {
            // 两个数都没给（或都不是正数）：一个字都不改
            return basePlan;
        }
        String note = "手动点位：" + what + "（丢尾 " + fmt(tail) + "s，交叠 "
                + fmt((double) base.windowFrames(sampleRate) / Math.max(1, sampleRate)) + "s）";
        Logger.info("[qplayer-engine] {}", note);
        return new TransitionPlan(entry, basePlan.pitch, basePlan.introStartAt, note, tail);
    }

    /** 问时长（只读头部，不解码）；未知或打不开时返回 null（调用方就不夹紧）。 */
    private static Double durationOf(String source, java.util.Map<String, String> headers) {
        try (dev.t1m3.qplayer.audio.decode.Decoder decoder =
                     headers == null || headers.isEmpty()
                             ? dev.t1m3.qplayer.audio.decode.Decoder.open(source)
                             : dev.t1m3.qplayer.audio.decode.Decoder.open(source, headers)) {
            return decoder.spec().durationSecs();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(v));
    }

    @Override
    public String toString() {
        return "ManualCue{" + base + "，出场 " + fmt(outroSecs) + "s / 进场 " + fmt(introSecs) + "s}";
    }
}
