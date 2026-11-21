package dev.t1m3.qplayer.audio.output;

import dev.t1m3.qplayer.audio.core.AudioException;
import dev.t1m3.qplayer.audio.core.BitDepth;
import dev.t1m3.qplayer.audio.core.PathVerdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 从设备能力矩阵里挑一个最接近请求的配置。
 *
 * <p>对应 Sonalis 的 {@code choose_config(device, req)} 与 {@code score_format(f, want)}
 * 两个自由函数。上游这边是「拿设备去枚举」，这里把枚举结果（{@link DeviceCapabilities}）
 * 当参数传进来——<strong>挑选逻辑因此变成纯函数，可以脱离真实声卡被测到</strong>，
 * 而「设备能力从哪来」交给宿主（桌面是 {@code javax.sound.sampled}）。
 *
 * <h3>选不到就报错，不静默降级（上游设计决定 ②）</h3>
 *
 * 降级与否该由上层看着源格式决定，输出层只负责「尽力而为 + 说实话」。
 * <strong>假装成功是这类功能最糟的失败模式</strong>：界面显示位完美，实际在重采样，
 * 用户永远查不出问题。
 *
 * <p>唯一的例外是<strong>位深</strong>：请求的位深选不到时不失败，只是退回设备上最接近的格式，
 * 由实际协商结果如实汇报（见 {@link OutputRequest#withBitDepth(BitDepth)}）。
 */
public final class OutputChooser {

    private OutputChooser() {
    }

    /** 挑选结果：实际要开的三元组。上游这里返回的是 {@code SupportedStreamConfig}。 */
    public static final class Chosen {

        public final OutputSampleFormat sampleFormat;
        public final int channels;
        public final int sampleRate;

        Chosen(OutputSampleFormat sampleFormat, int channels, int sampleRate) {
            this.sampleFormat = sampleFormat;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }

        /** 套上缓冲帧数变成一份完整的 {@link OutputConfig}。 */
        public OutputConfig toConfig(int ringFrames) {
            return new OutputConfig(sampleRate, channels, sampleFormat, ringFrames);
        }

        @Override
        public String toString() {
            return "Chosen{" + sampleFormat + " " + channels + "ch " + sampleRate + " Hz}";
        }
    }

    /**
     * 在设备的能力矩阵里挑一个最接近请求的配置。
     *
     * <p>规则与上游一致：
     *
     * <ol>
     *   <li>只考虑覆盖请求采样率的档位（上游用 {@code try_with_sample_rate}：
     *       超范围时 {@code with_sample_rate} 会 panic，所以必须走 try 版本）</li>
     *   <li>声道数筛选：能满足就用满足的子集，<strong>满足不了就放开</strong>——
     *       宁可换声道数也别直接失败</li>
     *   <li>位深按 {@link #scoreFormat} 打分，取最高分；同分保留先出现的那一个
     *       （上游是 {@code score > best}，严格大于，所以是稳定的）。
     *       <strong>因此「先出现」的顺序是有语义的</strong>：设备实现若知道某个档位更合适
     *       （例如本播放器的目标布局是立体声，而 javax.sound 偏偏把 1 声道排在前面），
     *       应当在 {@code capabilities()} 里把它排到前面，而不是在这里改打分规则</li>
     * </ol>
     *
     * @throws AudioException 设备没有声明任何受支持的配置，或没有任何档位覆盖该采样率
     */
    public static Chosen choose(DeviceCapabilities caps, OutputRequest req) {
        List<DeviceCapability> pool = new ArrayList<DeviceCapability>(caps.entries());

        if (pool.isEmpty()) {
            throw AudioException.output("设备没有声明任何受支持的输出配置");
        }

        // 声道数筛选。满足不了就放开——宁可换声道数也别直接失败。
        if (req.channels() != null) {
            List<DeviceCapability> filtered = new ArrayList<DeviceCapability>();
            for (DeviceCapability c : pool) {
                if (c.channels() == req.channels().intValue()) {
                    filtered.add(c);
                }
            }
            if (!filtered.isEmpty()) {
                pool = filtered;
            }
        }

        Chosen best = null;
        int bestScore = 0;
        for (DeviceCapability c : pool) {
            if (!c.coversRate(req.sampleRate())) {
                continue;
            }
            int score = scoreFormat(c.sampleFormat(), req.bitDepth());
            if (best == null || score > bestScore) {
                best = new Chosen(c.sampleFormat(), c.channels(), req.sampleRate());
                bestScore = score;
            }
        }

        if (best == null) {
            throw AudioException.output("设备不支持 " + PathVerdict.formatRate(req.sampleRate())
                    + " 的输出，请改用默认配置或降低请求");
        }
        return best;
    }

    /**
     * 采样格式的偏好打分：请求的位深优先，否则 F32 最优（内部格式直通）。
     *
     * <p>分值本身与上游逐值一致（F32 90 / I32 80 / I24 70 / I16 60 / U16 50，
     * 命中请求位深再 +100），<strong>不要顺手调</strong>——命中位深的 +100 必须能压过
     * F32 的默认优势，否则「点名 I24」等于没提（上游缺陷 A1）。
     *
     * @param want 目标位深，{@code null} = 不点名
     */
    public static int scoreFormat(OutputSampleFormat f, BitDepth want) {
        int base;
        switch (f) {
            case F32:
                base = 90;
                break;
            case I32:
                base = 80;
                break;
            case I24:
                base = 70;
                break;
            case I16:
                base = 60;
                break;
            default:
                base = 50;
                break;
        }
        if (want != null && f.matchesBitDepth(want)) {
            return base + 100;
        }
        return base;
    }

    /**
     * 「设备默认配置」。
     *
     * <p>上游直接问 cpal 要 {@code device.default_output_config()}（OS 的共享模式默认档位）。
     * <strong>javax.sound.sampled 没有等价 API</strong>：{@code DataLine.Info} 只告诉你
     * 「支持哪些」，不告诉你「默认是哪个」。所以这里按一条不猜硬件、只按平台惯例的规则重建：
     *
     * <ol>
     *   <li>声道数优先 2（本播放器是立体声链路），有 2 声道档位就只用它们</li>
     *   <li>采样率优先 48 kHz（Windows/Linux 共享模式的事实默认值），其次 44.1 kHz</li>
     *   <li>都没有时退到矩阵里采样率上界最高的档位，并用它能覆盖的最低档位表采样率</li>
     *   <li>位深按 {@link #scoreFormat} 打分（不点名位深时 F32 直通最优）</li>
     * </ol>
     *
     * <p>走这条路的前提是调用方<strong>明确表示「用设备默认」</strong>（{@code open_default}）；
     * 想跟随源采样率请走 {@link OutputChooser#choose}。
     *
     * @throws AudioException 能力矩阵为空
     */
    public static Chosen chooseDefault(DeviceCapabilities caps) {
        List<DeviceCapability> pool = new ArrayList<DeviceCapability>(caps.entries());
        if (pool.isEmpty()) {
            throw AudioException.output("设备没有声明任何受支持的输出配置");
        }

        List<DeviceCapability> stereo = new ArrayList<DeviceCapability>();
        for (DeviceCapability c : pool) {
            if (c.channels() == 2) {
                stereo.add(c);
            }
        }
        if (!stereo.isEmpty()) {
            pool = stereo;
        }

        Chosen best = pickAtRate(pool, 48_000);
        if (best == null) {
            best = pickAtRate(pool, 44_100);
        }
        if (best == null) {
            // 连 48k / 44.1k 都没有：退到「打分最高、上界最高」的那一档，
            // 用它区间的最低档位表采样率开流（不猜硬件，只是把矩阵里已知的一档用起来）
            DeviceCapability pick = null;
            long pickScore = 0;
            for (DeviceCapability c : pool) {
                long score = (long) scoreFormat(c.sampleFormat(), null) * 1_000_000L + c.maxSampleRate();
                if (pick == null || score > pickScore) {
                    pick = c;
                    pickScore = score;
                }
            }
            best = new Chosen(pick.sampleFormat(), pick.channels(), pick.minSampleRate());
        }
        return best;
    }

    /** 在池子里挑「覆盖给定采样率、且打分最高」的那一档。挑不到返回 {@code null}。 */
    private static Chosen pickAtRate(List<DeviceCapability> pool, int rate) {
        Chosen best = null;
        int bestScore = 0;
        for (DeviceCapability c : pool) {
            if (!c.coversRate(rate)) {
                continue;
            }
            int score = scoreFormat(c.sampleFormat(), null);
            if (best == null || score > bestScore) {
                best = new Chosen(c.sampleFormat(), c.channels(), rate);
                bestScore = score;
            }
        }
        return best;
    }
}
