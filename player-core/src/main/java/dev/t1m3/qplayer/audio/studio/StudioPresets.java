package dev.t1m3.qplayer.audio.studio;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 内置曲线（P1 的控制面就用它：一个下拉，一行一行能听出来）。
 *
 * <h2>为什么先给预设，而不是直接给 12 段滑杆</h2>
 *
 * <p>「工作站级」的终点是能拖的曲线编辑器（见 docs/audio-studio.md 的 P7），
 * 但那要先把交互做出来。在那之前，预设能回答一个更重要的问题：<strong>这条链到底有没有用、
 * 值不值得继续投</strong>——一条一条听过去，三十秒就知道。而且这些预设本身就是参数，
 * 用完就可以在它们的基础上继续改（曲线编辑器上线后，它们是「起点」而不是「全部」）。
 *
 * <h2>取值依据</h2>
 *
 * <p>都不是拍脑袋的：频点取常见问题所在的频段（60/90 Hz 是低频轰鸣与鼓的低端、
 * 250 Hz 是浑浊、2.5 kHz 是咬字、7–9 kHz 是齿音与刺耳），增益落在 ±8 dB 以内
 * （更大就成音效了）。每条曲线都带一句「它解决什么问题」，界面上直接显示这句。
 */
public final class StudioPresets {

    /** 一条内置曲线。 */
    public static final class Preset {

        public final String name;
        public final String solves;
        /** 链的构造（响度补偿等开关也在里面）。 */
        public final StudioRack rack;

        Preset(String name, String solves, StudioRack rack) {
            this.name = name;
            this.solves = solves;
            this.rack = rack;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 关（下标 0，也是默认）：**整条链直通**，位完美照旧。 */
    public static final int OFF = 0;

    private static final List<Preset> ALL = Collections.unmodifiableList(Arrays.asList(
            new Preset("关（直通）", "不碰任何采样——位完美的前提", StudioRack.EMPTY),
            new Preset("平直（归零）", "把参数恢复成一条平线，方便从零开始调",
                    StudioRack.eq(Collections.<EqBand>emptyList())),
            new Preset("低频增强", "流行/电子缺下盘、鼓不够实",
                    StudioRack.eq(Arrays.asList(
                            EqBand.of(BandType.LOW_SHELF, 90, 5.5, 0.7071),
                            EqBand.peak(60, 3.0, 1.0)))),
            new Preset("低频收干净", "低频轰、房间驻波、底噪里有次声",
                    StudioRack.eq(Arrays.asList(
                            EqBand.of(BandType.HIGH_PASS, 28, 0, 0.7071, 24, ChannelTarget.STEREO,
                                    true),
                            EqBand.peak(200, -2.5, 1.1)))),
            new Preset("人声突出", "人声被伴奏压住、咬字不清",
                    StudioRack.eq(Arrays.asList(
                            EqBand.peak(400, -2.0, 1.0),
                            EqBand.peak(2500, 4.0, 1.2),
                            EqBand.of(BandType.HIGH_SHELF, 8000, 2.0, 0.7071)))),
            new Preset("高频柔化", "齿音刺、镲片刮耳、录音偏亮",
                    StudioRack.eq(Arrays.asList(
                            EqBand.peak(4200, -3.0, 1.5),
                            EqBand.of(BandType.HIGH_SHELF, 7000, -3.0, 0.7071)))),
            new Preset("耳机补偿", "耳机低频下潜不足、声场偏窄带来的「薄」",
                    StudioRack.eq(Arrays.asList(
                            EqBand.of(BandType.LOW_SHELF, 100, 4.5, 0.7071),
                            EqBand.of(BandType.HIGH_SHELF, 9000, -2.0, 0.7071)))),
            new Preset("小音量夜间", "夜里音量小的时候低频与高频都听不见了",
                    StudioRack.eq(Arrays.asList(
                            EqBand.of(BandType.LOW_SHELF, 80, 6.0, 0.7071),
                            EqBand.peak(250, -3.0, 1.0),
                            EqBand.of(BandType.HIGH_SHELF, 8000, 2.5, 0.7071)))
                            .withLoudness(true, 1.0))));

    private StudioPresets() {
    }

    /** 全部曲线（下标与设置里的下拉一致，0 = 关）。 */
    public static List<Preset> all() {
        return ALL;
    }

    /** 下拉的选项文字。 */
    public static String[] labels() {
        String[] out = new String[ALL.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALL.get(i).name;
        }
        return out;
    }

    /** 取一条；越界或认不出来退回「关」。 */
    public static Preset at(int index) {
        if (index < 0 || index >= ALL.size()) {
            return ALL.get(OFF);
        }
        return ALL.get(index);
    }

    /** 这条曲线解决什么问题（界面显示用；「关」没有）。 */
    public static String solves(int index) {
        return at(index).solves;
    }
}
