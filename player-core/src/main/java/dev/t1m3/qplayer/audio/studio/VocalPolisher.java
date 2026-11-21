package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;

/**
 * 「人声后期」那一套的离线跑法：把 {@link AutotuneModule} 与 {@link DereverbModule}
 * 依次作用在<strong>一整段人声素材</strong>上（左/右两条数组，就地改写）。
 *
 * <h2>为什么是离线而不是逐块插在链上</h2>
 *
 * <p>修跑调与去混响都<strong>只能作用在人声轨上</strong>（理由见 {@link AutotuneParams}
 * 与 {@link DereverbParams} 的类注释）。而这条链路上真正拿得到「人声轨」的地方是
 * <strong>分轨</strong>——{@code StemSeparator} 跑在准备线程上、拿到的是一整段素材，
 * 不是逐块的音频。所以这一套就在这里按「整段进、整段出」跑：
 *
 * <ul>
 *   <li>准备线程上跑（离线，允许几十毫秒到几秒）；</li>
 *   <li>两个模块各自的延迟（一个 CHUNK / 一个窗）在<strong>这里被补掉</strong>：
 *       多喂 {@code latency} 个零、再把输出往前挪那么多——整段处理完就没有延迟了
 *       （逐个采样与输入对齐），这与「实时链上必须如实上报延迟」并不矛盾：
 *       那里不能提前看，这里可以。</li>
 * </ul>
 *
 * <h2>判据</h2>
 *
 * <p>见 {@code VocalPolisherTest}：故意唱高 60 音分的合成人声跑完必须落到音阶上；
 * 伴奏那两条数组必须<strong>逐位不变</strong>（「只修人声」的硬判据）；
 * 关掉时输入输出逐位相同。
 */
public final class VocalPolisher {

    /** 每次处理的块长（必须是 {@link DereverbModule#HOP} 的整数倍，见那个类的块长约定）。 */
    private static final int BLOCK = 1024;

    private final int rate;
    private final AutotuneParams autotune;
    private final DereverbParams dereverb;

    public VocalPolisher(int rate, AutotuneParams autotune, DereverbParams dereverb) {
        this.rate = Math.max(rate, 1);
        this.autotune = autotune == null ? AutotuneParams.off() : autotune;
        this.dereverb = dereverb == null ? DereverbParams.off() : dereverb;
    }

    /** 这一套参数会不会改采样。 */
    public boolean active() {
        return autotune.active() || dereverb.active();
    }

    /**
     * 就地处理一段立体声人声素材。
     *
     * <p>两个模块<strong>依次</strong>跑（先修音准、再去混响）：反过来的话，去混响会把
     * 尾巴压掉、检测器看到的音高更干净一点，但修音准要的是「原始的音高轨迹」——
     * 先去混响会把颤音与音尾的细节一起削掉，修出来的音准曲线会偏。
     *
     * @param left  左声道（就地改写）
     * @param right 右声道（就地改写；单声道素材传同一份数组会出错，请传副本）
     * @return 实际处理了多少帧；参数全关时返回 0（不做任何事）
     */
    public int polish(float[] left, float[] right) {
        if (!active() || left == null || right == null || left.length == 0) {
            return 0;
        }
        int frames = Math.min(left.length, right.length);
        // **参数一定要交给模块**：两个模块的参数默认都是「关」，忘了这一句的表现是
        // 「跑了一遍、一个采样都没改」——判据里那一条（修高的音必须落回音阶）当场红。
        if (autotune.active()) {
            AutotuneModule module = new AutotuneModule(rate, 2);
            module.setParams(autotune);
            frames = runModule(module, frames, left, right);
        }
        if (dereverb.active()) {
            DereverbModule module = new DereverbModule(rate, 2);
            module.setParams(dereverb);
            frames = runModule(module, frames, left, right);
        }
        return frames;
    }

    /**
     * 把一个 {@link StudioModule} 跑完整段素材，并<strong>补掉它自己的延迟</strong>。
     *
     * <p>做法：多喂 {@code latency} 个零采样（让最后那一窗也有输入可看），
     * 再把输出整体往前挪 {@code latency}——于是输出与输入<strong>逐个采样对齐</strong>，
     * 连尾巴都是完整的。实时链上做不到这一点（不能看未来），所以那里的延迟必须如实上报。
     */
    private int runModule(StudioModule module, int frames, float[] left, float[] right) {
        int latency = Math.max(0, module.latencyFrames());
        int total = frames + latency;
        // 输出暂存（整段处理完再整体前移）：与「交给调用方的数组」分开，
        // 否则前移会覆盖还没读的输入
        float[] outL = new float[total];
        float[] outR = new float[total];
        AudioBuf buf = new AudioBuf(ChannelLayout.STEREO, BLOCK);
        int at = 0;
        while (at < total) {
            int n = Math.min(BLOCK, total - at);
            buf.setFrames(n);
            java.nio.FloatBuffer l = buf.channelWriter(0);
            java.nio.FloatBuffer r = buf.channelWriter(1);
            for (int i = 0; i < n; i++) {
                int src = at + i;
                // 超出素材范围的那一段喂零：让模块把手上那一窗算完（多出来的部分丢弃）
                l.put(i, src < frames ? left[src] : 0f);
                r.put(i, src < frames ? right[src] : 0f);
            }
            module.process(buf);
            float[] backing = buf.backingArray();
            int baseL = buf.channelBase(0);
            int baseR = buf.channelBase(1);
            for (int i = 0; i < n; i++) {
                outL[at + i] = backing[baseL + i];
                outR[at + i] = backing[baseR + i];
            }
            at += n;
        }
        // 前移 latency：输出与输入逐个采样对齐
        for (int i = 0; i < frames; i++) {
            left[i] = outL[i + latency];
            right[i] = outR[i + latency];
        }
        return frames;
    }

    @Override
    public String toString() {
        return "VocalPolisher{" + autotune + "，" + dereverb + "}";
    }
}
