package dev.t1m3.qplayer.audio.dsp;

/**
 * <b>拍重复器</b>（2026-10-08 ✓，照搬 goal 第 41 轮 ✓）。
 *
 * <p>材料里档 17 `Dance - Repeater` 的 `remixfx_repeater_*`：`on` 于 0.4444 置 1、0.8889 置 0 ✓，
 * `rate` 阶梯 1→2→3→4→5 ✓，`mix = 1` ✓ ⇒ 这是**经典的 stutter**（抓一小段、原地循环 ✓），
 * 不是"延迟一拍"✗。
 *
 * <p>行为：未开启 ⇒ **逐位直通** ✓（一个采样都不碰 ✗）；开启后先**抓取**一个循环段（这一段仍直通 ✓），
 * 之后把抓到的段**循环播放** ✓，与干声按 {@code mix} 混合 ✓；关闭 ⇒ 立刻回到直通 ✓。
 * **零分配** ✓（缓冲在构造时按上限建好 ✓）、RT 安全 ✓（无锁、无 new ✓）。
 */
public final class AppleBeatRepeat {

    private float[][] buf;
    private int loopFrames = 1;
    private int pos;
    private boolean active;
    private boolean captured;
    private float mix = 1f;

    /** @param maxLoopFrames 循环段的最大帧数（按最慢速率算 ✓，构造时一次性分配 ✓） */
    public AppleBeatRepeat(int channels, int maxLoopFrames) {
        int n = Math.max(1, maxLoopFrames);
        buf = new float[Math.max(1, channels)][n];
    }

    public void reset() {
        for (float[] ch : buf) {
            java.util.Arrays.fill(ch, 0f);
        }
        pos = 0;
        captured = false;
    }

    public void setMix(float m) {
        mix = m < 0f ? 0f : (m > 1f ? 1f : m);
    }

    public int loopFrames() {
        return loopFrames;
    }

    /** 循环段长度（帧 ✓）。**改变它会重新抓取** ✓（速率阶梯切换时正是这个语义 ✓）。 */
    public void setLoopFrames(int frames) {
        int f = Math.max(1, Math.min(buf[0].length, frames));
        if (f != loopFrames) {
            loopFrames = f;
            pos = 0;
            captured = false;
        }
    }

    public void setActive(boolean on) {
        if (on && !active) {
            pos = 0;
            captured = false;
        }
        active = on;
    }

    public boolean active() {
        return active;
    }

    /** 处理一个采样 ✓（见类注释的行为 ✓）。 */
    public float process(int channel, float in) {
        int c = Math.max(0, Math.min(buf.length - 1, channel));
        float[] b = buf[c];
        if (!active) {
            return in;                       // 关着 ⇒ 逐位直通 ✓（不写缓冲 ✓，免得留残响 ✓）
        }
        float out;
        if (!captured) {
            b[pos] = in;                     // 抓取阶段：这一段仍然直通 ✓
            out = in;
        } else {
            out = b[pos];                    // 循环阶段：播抓到的段 ✓
        }
        if (++pos >= loopFrames) {
            pos = 0;
            captured = true;                 // 抓满一段 ⇒ 之后开始循环 ✓
        }
        return in + mix * (out - in);
    }
}
