package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;

import java.nio.FloatBuffer;

/**
 * 混合与声道映射。
 *
 * <p>这个模块只做「把若干路缓冲按权重加在一起」这一件事，刻意保持
 * <strong>无状态、不分配</strong>——所有输出缓冲由调用方提供并预先分配好。
 *
 * <h2>为什么是等功率，不是线性</h2>
 *
 * <p>线性淡化（{@code a = 1-t}、{@code b = t}）在两路各 0.5 的中点，总功率是
 * {@code 0.5² + 0.5² = 0.5}，也就是<strong>掉了 3 dB</strong>——听感上过渡处会
 * 「塌一下」。
 *
 * <p>等功率用 {@code cos/sin}，满足 {@code a² + b² = 1}，中点功率恒定，听感才是平的。
 * 这不是玄学，是 {@code equalPowerHoldsPowerConstant} 那条测试在锁的东西。
 *
 * <p>对应 Sonalis 的 {@code sonalis_mix::mixer}。
 */
public final class Mixer {

    /** {@code f32::EPSILON}：单位增益快路径的判定阈值。 */
    private static final float F32_EPSILON = 1.1920929E-7f;

    private Mixer() {
    }

    /** 等功率系数对：出场增益与进场增益。 */
    public static final class Gains {
        public final float from;
        public final float to;

        Gains(float from, float to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return "Gains{from=" + from + ", to=" + to + '}';
        }
    }

    /**
     * 等功率交叉淡化系数，返回 {@code (出场增益, 进场增益)}。
     *
     * <p>{@code progress} 会被夹到 {@code [0, 1]}：0 表示完全是出场曲，1 表示完全是进场曲。
     */
    /**
     * <b>短交叉专用</b>的等功率曲线（窗口 ≤ {@code 2.5 s} 时用，见 {@code AutoCueTransition}）。
     *
     * <p>为什么需要另一条：教科书等功率曲线在<b>短窗口</b>里会把出场曲提前让掉——
     * 实测（2026-11-20，2 s 短交叉）末尾 A 已掉到 -22 dBFS、B 才 -10.9 dBFS，
     * 交接瞬间电平跳 11.38 dB，听感就是"卡一下"。
     *
     * <p>这条把两侧**错开**：A 前 20% 保持满、此后才降；B 前 40% 就到顶。
     * 再按"功率 ≤ 1"归一化 —— 错开后总功率会超 1，不归一化就会削波。
     * 长窗口仍然走 {@link #equalPowerGains(float)}，与从前逐位相同。
     */
    public static Gains shortCrossfadeGains(float progress) {
        float t = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        float ta = Math.max(0f, Math.min(1f, (t - 0.20f) / 0.80f));
        float tb = Math.max(0f, Math.min(1f, t / 0.40f));
        double ga = Math.cos(ta * (Math.PI / 2.0));
        double gb = Math.sin(tb * (Math.PI / 2.0));
        double power = ga * ga + gb * gb;
        if (power > 1.0) {
            double k = 1.0 / Math.sqrt(power);
            ga *= k;
            gb *= k;
        }
        return new Gains((float) ga, (float) gb);
    }

    public static Gains equalPowerGains(float progress) {
        float t = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        float angle = t * (float) (Math.PI / 2.0);
        return new Gains((float) Math.cos(angle), (float) Math.sin(angle));
    }

    /**
     * 声道映射：把 {@code src} 搬进 {@code dst}，必要时铺开或下混。
     *
     * <p>只处理四种情况：等声道直通、单声道铺开、下混到单声道、截断或补零。
     * 完整的 5.1/7.1 矩阵上下混与 HRTF 折叠不在这一层。
     *
     * <p>{@code dst} 的有效帧数会被设成实际写入量，容量不足时截断。
     */
    public static void mapChannels(AudioBuf src, AudioBuf dst) {
        int frames = Math.min(src.frames(), dst.capacityFrames());
        int inCh = src.channels();
        int outCh = dst.channels();
        dst.setFrames(frames);
        if (frames == 0) {
            return;
        }

        if (inCh == outCh) {
            for (int c = 0; c < inCh; c++) {
                copyInto(src.channel(c), dst.channelWriter(c), frames);
            }
            return;
        }

        if (inCh == 1) {
            // 单声道铺到所有输出声道
            FloatBuffer mono = src.channel(0);
            for (int c = 0; c < outCh; c++) {
                copyInto(mono, dst.channelWriter(c), frames);
            }
            return;
        }

        if (outCh == 1) {
            // 下混：取平均。直接相加会在多声道内容上溢出。
            float inv = 1f / inCh;
            FloatBuffer dstCh = dst.channelWriter(0);
            for (int i = 0; i < frames; i++) {
                float acc = 0f;
                for (int c = 0; c < inCh; c++) {
                    acc += src.channel(c).get(i);
                }
                dstCh.put(i, acc * inv);
            }
            return;
        }

        // 其余情况（例如 5.1 → 立体声）：**取前 N 个声道，不假装做了下混**。
        //
        // 刻意不做「智能」处理：错误的上下混比不做更糟。真正的矩阵下混需要
        // 带相位与增益补偿的系数表。
        int copy = Math.min(outCh, inCh);
        for (int c = 0; c < copy; c++) {
            copyInto(src.channel(c), dst.channelWriter(c), frames);
        }
        for (int c = copy; c < outCh; c++) {
            FloatBuffer dstCh = dst.channelWriter(c);
            for (int i = 0; i < frames; i++) {
                dstCh.put(i, 0f);
            }
        }
    }

    /**
     * 两路混合：{@code out = a * ga + b * gb}。
     *
     * <p>两路长度<strong>可以不同</strong>——短的那一路视作已结束，尾部按零处理。
     * 输出帧数取两路的较大者，并被 {@code out} 的容量截断。
     *
     * <p>调用前两路的声道数必须已经被统一（用 {@link #mapChannels}）。上游这里用
     * {@code debug_assert_eq!} 表达这个前置条件；Java 侧不额外断言，但声道数不符时
     * {@link AudioBuf#channel(int)} 会直接抛下标越界，不会静默算错。
     *
     * <p>返回写入帧数。
     */
    /**
     * <b>中/侧让位</b>：就地把一路音频按「中间增益 / 两侧增益」改写（P2）。
     *
     * <p>重叠期两首歌同时响，最大的糊来自「两条全频段内容叠在一起」。人耳分辨两个同时发声的
     * 源，一半靠频谱、一半靠**空间**——把一路推向两侧（压低中间）、另一路收窄到中间
     * （压低两侧），两首歌在空间上就分开了，这是 DJ 用 EQ + 声像干的那件事。
     *
     * <p>数学：{@code m = (l+r)/2, s = (l−r)/2}，输出 {@code l' = m·midGain + s·sideGain}、
     * {@code r' = m·midGain − s·sideGain}。两个增益都等于 1 时**直接返回、一个采样都不碰**
     * （浮点下 `m+s` 未必逐位等于 `l`，所以「关掉」必须是短路而不是乘 1）——这条纪律与
     * 「关掉的功能要和没有它逐位相同」一致。
     *
     * <p>单声道缓冲（{@code channels < 2}）是 no-op：M/S 在单声道上没有定义。
     *
     * @param frames   只处理前这么多帧（缓冲里可能有更多）
     * @param midGain  中间分量增益（1 = 不动；<1 把这一路推向两侧）
     * @param sideGain 两侧分量增益（1 = 不动；<1 把这一路收窄到中间）
     */
    public static void applyStereoWidth(AudioBuf buf, int frames, float midGain, float sideGain) {
        if (buf == null || buf.channels() < 2 || frames <= 0) {
            return;
        }
        if (Math.abs(midGain - 1.0f) < F32_EPSILON && Math.abs(sideGain - 1.0f) < F32_EPSILON) {
            return;      // **短路**：两个增益都是 1 时逐位不动（见方法注释）
        }
        int n = Math.min(frames, buf.frames());
        FloatBuffer left = buf.channel(0);
        FloatBuffer right = buf.channel(1);
        for (int i = 0; i < n; i++) {
            float l = left.get(i);
            float r = right.get(i);
            float m = (l + r) * 0.5f;
            float sd = (l - r) * 0.5f;
            left.put(i, m * midGain + sd * sideGain);
            right.put(i, m * midGain - sd * sideGain);
        }
    }

    /**
     * 把一路缓冲区的前 {@code frames} 帧按中/侧加权**复制**进 {@code dst}（不碰源）。
     *
     * <p>P2 的接线用得到它：空间让位要作用在「这一块新渲染的帧」上，而不是就地改输入缓冲
     * ——输入缓冲里可能还留着上一块没交付的余量，就地改会被**反复加宽**，效果就不均匀了。
     * 复制一份、只改副本，语义就干净了。
     */
    public static void copyWithWidth(AudioBuf src, AudioBuf dst, int frames,
                                     float midGain, float sideGain) {
        int n = Math.min(Math.min(frames, src.frames()), dst.capacityFrames());
        int chs = Math.min(src.channels(), dst.channels());
        if (n <= 0) {
            dst.setFrames(0);
            return;
        }
        boolean width = chs >= 2
                && (Math.abs(midGain - 1.0f) > F32_EPSILON
                        || Math.abs(sideGain - 1.0f) > F32_EPSILON);
        if (!width) {
            for (int c = 0; c < chs; c++) {
                FloatBuffer in = src.channel(c);
                FloatBuffer out = dst.channelWriter(c);
                for (int i = 0; i < n; i++) {
                    out.put(i, in.get(i));
                }
            }
            for (int c = chs; c < dst.channels(); c++) {
                FloatBuffer out = dst.channelWriter(c);
                for (int i = 0; i < n; i++) {
                    out.put(i, 0.0f);
                }
            }
            dst.setFrames(n);
            return;
        }
        FloatBuffer sl = src.channel(0);
        FloatBuffer sr = src.channel(1);
        FloatBuffer dl = dst.channelWriter(0);
        FloatBuffer dr = dst.channelWriter(1);
        for (int i = 0; i < n; i++) {
            float l = sl.get(i);
            float r = sr.get(i);
            float m = (l + r) * 0.5f;
            float sd = (l - r) * 0.5f;
            dl.put(i, m * midGain + sd * sideGain);
            dr.put(i, m * midGain - sd * sideGain);
        }
        for (int c = 2; c < chs; c++) {
            FloatBuffer in = src.channel(c);
            FloatBuffer out = dst.channelWriter(c);
            for (int i = 0; i < n; i++) {
                out.put(i, in.get(i));
            }
        }
        dst.setFrames(n);
    }

    public static int mixTwo(AudioBuf out, AudioBuf a, float ga, AudioBuf b, float gb) {
        int outCh = out.channels();
        int aOk = a.frames();
        int bOk = b.frames();
        int frames = Math.min(Math.max(aOk, bOk), out.capacityFrames());
        if (frames == 0) {
            out.setFrames(0);
            return 0;
        }

        // 两路都有效的区间 / 只有一路有效的尾部
        int both = Math.min(Math.min(aOk, bOk), frames);
        boolean aLonger = aOk >= bOk;

        for (int c = 0; c < outCh; c++) {
            FloatBuffer sa = a.channel(c);
            FloatBuffer sb = b.channel(c);
            FloatBuffer dst = out.channelWriter(c);

            for (int i = 0; i < both; i++) {
                dst.put(i, sa.get(i) * ga + sb.get(i) * gb);
            }
            // 尾部只可能有一路有效（较长的那路），长度必然被 frames 覆盖
            if (aLonger) {
                for (int i = both; i < frames; i++) {
                    dst.put(i, sa.get(i) * ga);
                }
            } else {
                for (int i = both; i < frames; i++) {
                    dst.put(i, sb.get(i) * gb);
                }
            }
        }

        out.setFrames(frames);
        return frames;
    }

    /**
     * <b>带逐帧响度匹配的 {@link #mixTwo}</b>（P4）：B 那一侧先乘一个「贴着 A 的响度」的
     * 增益，再按各自的曲线混进来。
     *
     * <p>为什么增益要在这里逐帧算、而不是先算一整块增益数组再混：{@link LevelMatcher}
     * 是有状态的，而素材缓冲里可能留着上一块<b>没交付</b>的余量。先算数组就会把那几帧
     * 算两遍，状态是累积跑偏的（同 2026-10-04「进度回跳」那一类 bug）。在混音的同一个
     * 循环里推进，才是「交付多少帧、状态走多少帧」。
     *
     * <p>{@code strength <= 0}（或 {@code matcher == null}）时<b>直接走</b> {@link #mixTwo}，
     * 与没有这个功能逐位相同。
     *
     * <p>语义与 {@link #mixTwo} 完全一致（帧数、尾部单路、返回值），只有 B 的增益不同。
     */
    public static int mixTwoMatched(AudioBuf out, AudioBuf a, float ga, AudioBuf b, float gb,
                                    LevelMatcher matcher, float strength) {
        return mixTwoMatched(out, a, ga, b, gb, matcher, strength, null, 0.0f);
    }

    /**
     * 同上，再叠一层<b>低频让位</b>（P5，{@link LowDucker}）：低频段乘一个两侧共有的增益，
     * 让两首歌的低频<b>加起来不超过其中较响的一首</b>；中高频原样相加。
     *
     * <p>两件事必须在<b>同一个循环</b>里做：它们各自都有状态，而状态必须「交付多少帧就走
     * 多少帧」。{@code ducker == null} 或 {@code duckStrength <= 0} 时这一层完全不走。
     */
    public static int mixTwoMatched(AudioBuf out, AudioBuf a, float ga, AudioBuf b, float gb,
                                    LevelMatcher matcher, float strength,
                                    LowDucker ducker, float duckStrength) {
        boolean level = matcher != null && strength > 0.0f;
        boolean duck = ducker != null && duckStrength > 0.0f;
        if (!level && !duck) {
            return mixTwo(out, a, ga, b, gb);
        }
        int outCh = out.channels();
        int aOk = a.frames();
        int bOk = b.frames();
        int frames = Math.min(Math.max(aOk, bOk), out.capacityFrames());
        if (frames == 0) {
            out.setFrames(0);
            return 0;
        }

        // 两路都有效的区间 / 只有一路有效的尾部（与 mixTwo 同一套划分）
        int both = Math.min(Math.min(aOk, bOk), frames);
        boolean aLonger = aOk >= bOk;

        // B 的逐帧增益：只在「两路都有效」的区间里算——尾部只有一路，没有「匹配」可言。
        float[] gains = level ? matcher.buffer(both) : null;
        if (level) {
            int gainCh = Math.min(a.channels(), b.channels());
            for (int i = 0; i < both; i++) {
                float pa = 0.0f;
                float pb = 0.0f;
                for (int c = 0; c < gainCh; c++) {
                    float va = a.channel(c).get(i);
                    float vb = b.channel(c).get(i);
                    pa += va * va;
                    pb += vb * vb;
                }
                float inv = gainCh > 0 ? 1.0f / gainCh : 0.0f;
                gains[i] = matcher.gainForFrame(pa * inv, pb * inv, strength);
            }
        }
        float[] duckGains = duck ? ducker.buffer(frames) : null;

        for (int c = 0; c < outCh; c++) {
            FloatBuffer sa = a.channel(c);
            FloatBuffer sb = b.channel(c);
            FloatBuffer dst = out.channelWriter(c);

            for (int i = 0; i < both; i++) {
                float va = sa.get(i);
                float vb = sb.get(i);
                if (duck) {
                    // 两段相加等于原信号：只把低频那一段乘上共有增益。
                    float loA = ducker.lowOf(true, c, va);
                    float loB = ducker.lowOf(false, c, vb);
                    float g;
                    if (c == 0) {
                        ducker.track(loA, loB);        // 包络只跟第 0 声道，且每帧只推一次
                        g = ducker.gainForFrame(duckStrength);
                        duckGains[i] = g;
                    } else {
                        g = duckGains[i];
                    }
                    va = (va - loA) + loA * g;
                    vb = (vb - loB) + loB * g;
                }
                dst.put(i, va * ga + vb * gb * (level ? gains[i] : 1.0f));
            }
            // 尾部只可能有一路有效（较长的那路），长度必然被 frames 覆盖。
            //
            // 尾部同样要**按帧推进滤波器与增益**：那是已经交付出去的帧，状态少走一帧，
            // 下一块就从头错起（与「素材游标」同一类）。缺的那一路按 0 喂进去即可
            // ——信号本来就是 0，滤波器的状态也就该照着 0 衰减。
            if (aLonger) {
                for (int i = both; i < frames; i++) {
                    float va = sa.get(i);
                    if (duck) {
                        float loA = ducker.lowOf(true, c, va);
                        float loB = ducker.lowOf(false, c, 0.0f);
                        float g;
                        if (c == 0) {
                            ducker.track(loA, loB);
                            g = ducker.gainForFrame(duckStrength);
                            duckGains[i] = g;
                        } else {
                            g = duckGains[i];
                        }
                        va = (va - loA) + loA * g;
                    }
                    dst.put(i, va * ga);
                }
            } else {
                for (int i = both; i < frames; i++) {
                    float vb = sb.get(i);
                    if (duck) {
                        float loA = ducker.lowOf(true, c, 0.0f);
                        float loB = ducker.lowOf(false, c, vb);
                        float g;
                        if (c == 0) {
                            ducker.track(loA, loB);
                            g = ducker.gainForFrame(duckStrength);
                            duckGains[i] = g;
                        } else {
                            g = duckGains[i];
                        }
                        vb = (vb - loB) + loB * g;
                    }
                    dst.put(i, vb * gb);
                }
            }
        }

        out.setFrames(frames);
        return frames;
    }

    /**
     * 单路增益写入：{@code out = src * g}，帧数取 {@code src} 与 {@code out} 容量的较小者。
     *
     * <p>这是「不混合、只过增益」的快路径，避免无谓地走 {@link #mixTwo}。
     * 单位增益时走精确拷贝、不逐样本相乘——这条是「位完美路径下增益可以常驻链路」
     * 的直接前提。
     */
    public static int gainInto(AudioBuf out, AudioBuf src, float g) {
        int outCh = out.channels();
        int frames = Math.min(src.frames(), out.capacityFrames());
        boolean unity = Math.abs(g - 1f) < F32_EPSILON;
        for (int c = 0; c < outCh; c++) {
            FloatBuffer s = src.channel(c);
            FloatBuffer dst = out.channelWriter(c);
            if (unity) {
                copyInto(s, dst, frames);
            } else {
                for (int i = 0; i < frames; i++) {
                    dst.put(i, s.get(i) * g);
                }
            }
        }
        out.setFrames(frames);
        return frames;
    }

    private static void copyInto(FloatBuffer src, FloatBuffer dst, int frames) {
        for (int i = 0; i < frames; i++) {
            dst.put(i, src.get(i));
        }
    }
}
