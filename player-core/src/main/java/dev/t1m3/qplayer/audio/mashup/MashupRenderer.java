package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.mix.TransitionLimiter;

import java.io.IOException;
import java.nio.FloatBuffer;

/**
 * <b>把两路素材按一条时间轴离线合成为成品 PCM</b>（2026-11-21，mashup 第一阶段）。
 *
 * <h2>它是什么、不是什么</h2>
 *
 * <p>它是 mashup 的<b>渲染循环</b>：按固定块长从两路素材取数、按增益相加、过限幅、
 * 交给 {@link Sink}。它<b>不</b>做分轨（{@code StemStore} 的事）、<b>不</b>选段
 * （{@code MashupPlanner} 的事）、也<b>不</b>复用实时链路的 {@code Director}——
 * 后者的记账（响度补偿、pitch 曲线、抬 B、工作室机架）是为"窗口 4 秒的一次过渡"
 * 写的，整曲套上去会把语义搅在一起。
 *
 * <h2>第一阶段的硬边界：只支持恒速 1:1</h2>
 *
 * <p>本阶段的输入是<b>已经对齐好时间轴</b>的两路素材（A 的人声轨与 B 的伴奏轨），
 * {@link TimeMap} 只用来表达"源从第几帧开始"（斜率必须恰好等于 1）。
 * 真正的变速（{@code TempoBridge.stretchCurve}）在第三阶段接进来，那时
 * {@link #builder(int, int)} 里那条 {@code speed == 1} 的断言会被换成"带时间轴的读取"。
 *
 * <p>这么做是刻意的：先让"块循环 + 混音 + 限幅 + 写文件"这条链在恒速下被逐帧验证，
 * 再让变速只影响"取数的位置"这一处。两者一起上，出问题时无法判断是映射错了还是混音错了。
 *
 * <h2>块长是内存参数</h2>
 *
 * <p>默认 {@value #DEFAULT_BLOCK_FRAMES} 帧（48 kHz 下约 85 ms）。块长不改变听感
 * （这里没有"按块步进的响度补偿"那种记账），只影响峰值内存与 {@link Sink} 的调用频率。
 */
public final class MashupRenderer {

    /** 默认块长（帧）。 */
    public static final int DEFAULT_BLOCK_FRAMES = 4096;

    private MashupRenderer() {
    }

    /** 渲染完的记账，供测试与验收报告用。 */
    public static final class Report {

        /** 实际写出的帧数。 */
        public final long frames;

        /** 限幅<b>之前</b>的峰值（判断"是不是本来就会炸"）。 */
        public final float peakBeforeLimiter;

        /** 限幅<b>之后</b>的峰值（成品峰值）。 */
        public final float peakAfterLimiter;

        /** 限幅器这一次有没有真的动过手。 */
        public final boolean limiterActed;

        Report(long frames, float peakBeforeLimiter, float peakAfterLimiter,
               boolean limiterActed) {
            this.frames = frames;
            this.peakBeforeLimiter = peakBeforeLimiter;
            this.peakAfterLimiter = peakAfterLimiter;
            this.limiterActed = limiterActed;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "Report{%d 帧, 限幅前峰值 %.6f, 限幅后峰值 %.6f, 限幅%s}",
                    frames, peakBeforeLimiter, peakAfterLimiter,
                    limiterActed ? "动过" : "未动");
        }
    }

    /** 块的下游：真正的写出、或测试里的收集器。 */
    public interface Sink {

        /** 写一块。{@code block.frames()} 是本块的帧数。 */
        void write(AudioBuf block) throws IOException;
    }

    /** 渲染请求（不可变）。用 {@link MashupRenderer#builder(int, int)} 构造。 */
    public static final class Request {

        final int sampleRate;
        final int channels;
        final long outFrames;
        final int blockFrames;
        final PcmTrack vocals;
        final TimeMap vocalMap;
        final PcmTrack instrumental;
        final long instrumentalStartFrame;
        final float vocalGain;
        final float instrumentalGain;
        final double fadeInSecs;
        final double fadeOutSecs;

        private Request(Builder b) {
            this.sampleRate = b.sampleRate;
            this.channels = b.channels;
            this.outFrames = b.outFrames;
            this.blockFrames = b.blockFrames;
            this.vocals = b.vocals;
            this.vocalMap = b.vocalMap;
            this.instrumental = b.instrumental;
            this.instrumentalStartFrame = b.instrumentalStart;
            this.vocalGain = b.vocalGain;
            this.instrumentalGain = b.instrumentalGain;
            this.fadeInSecs = b.fadeInSecs;
            this.fadeOutSecs = b.fadeOutSecs;
        }

        /** 输出帧数。 */
        public long outFrames() {
            return outFrames;
        }
    }

    /** 建一个请求。 */
    public static Builder builder(int sampleRate, int channels) {
        return new Builder(sampleRate, channels);
    }

    /** 请求构造器：参数一多，顺序写错是查不出来的那种错，所以用名字而不用位置。 */
    public static final class Builder {

        private final int sampleRate;
        private final int channels;
        private PcmTrack vocals;
        private TimeMap vocalMap;
        private PcmTrack instrumental;
        private long instrumentalStart;
        private long outFrames;
        private int blockFrames = DEFAULT_BLOCK_FRAMES;
        private float vocalGain = 1f;
        private float instrumentalGain = 1f;
        private double fadeInSecs = 0.03;
        private double fadeOutSecs = 0.3;

        private Builder(int sampleRate, int channels) {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("采样率必须为正：" + sampleRate);
            }
            if (channels < 1 || channels > 2) {
                throw new IllegalArgumentException("mashup 只处理 1~2 声道，收到 " + channels);
            }
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        /** 人声轨 + 它到输出时间轴的映射（映射的斜率必须是 1，见类注释）。 */
        public Builder vocals(PcmTrack vocals, TimeMap map) {
            this.vocals = vocals;
            this.vocalMap = map;
            return this;
        }

        /** 伴奏轨 + 它在输出第 0 帧时对应的源帧。 */
        public Builder instrumental(PcmTrack instrumental, long startFrame) {
            this.instrumental = instrumental;
            this.instrumentalStart = startFrame;
            return this;
        }

        /** 输出长度（帧）。 */
        public Builder frames(long outFrames) {
            this.outFrames = outFrames;
            return this;
        }

        /** 块长（帧），默认 {@value MashupRenderer#DEFAULT_BLOCK_FRAMES}。 */
        public Builder blockFrames(int blockFrames) {
            this.blockFrames = blockFrames;
            return this;
        }

        /** 线性增益（0 = 不参与）。 */
        public Builder gains(float vocalGain, float instrumentalGain) {
            this.vocalGain = vocalGain;
            this.instrumentalGain = instrumentalGain;
            return this;
        }

        /** 人声的淡入/淡出（秒）：起点别咔哒，收尾别硬切。 */
        public Builder fades(double fadeInSecs, double fadeOutSecs) {
            this.fadeInSecs = fadeInSecs;
            this.fadeOutSecs = fadeOutSecs;
            return this;
        }

        /** 校验并冻结。 */
        public Request build() {
            if (outFrames <= 0) {
                throw new IllegalArgumentException("输出帧数必须为正：" + outFrames);
            }
            if (blockFrames <= 0) {
                throw new IllegalArgumentException("块长必须为正：" + blockFrames);
            }
            if (vocals != null) {
                if (vocalMap == null) {
                    throw new IllegalArgumentException("给了人声轨就必须给它到输出时间轴的映射");
                }
                requireConstantSpeedOne(vocalMap);
                if (vocals.channels() != channels) {
                    throw new IllegalArgumentException("人声轨是 " + vocals.channels()
                            + " 声道，输出是 " + channels + " 声道（本项目不在这里做声道映射）");
                }
                if (vocalMap.outStart() > 0 || vocalMap.outEnd() < outFrames) {
                    // 不算错，只是"外推"：这里只要求映射覆盖输出区间，否则说明调用方忘了给全长
                    throw new IllegalArgumentException("映射只覆盖输出 "
                            + vocalMap.outStart() + ".." + vocalMap.outEnd()
                            + "，不够 " + outFrames + " 帧");
                }
            }
            if (instrumental != null) {
                if (instrumental.channels() != channels) {
                    throw new IllegalArgumentException("伴奏轨是 " + instrumental.channels()
                            + " 声道，输出是 " + channels + " 声道");
                }
                if (instrumentalStart < 0) {
                    throw new IllegalArgumentException("伴奏起点不能为负：" + instrumentalStart);
                }
            }
            if (vocals == null && instrumental == null) {
                throw new IllegalArgumentException("两路素材至少要有一路");
            }
            if (!(vocalGain >= 0f) || !(instrumentalGain >= 0f)) {
                throw new IllegalArgumentException("增益不能为负");
            }
            if (!(fadeInSecs >= 0) || !(fadeOutSecs >= 0)) {
                throw new IllegalArgumentException("淡入淡出不能为负");
            }
            return new Request(this);
        }

        private static void requireConstantSpeedOne(TimeMap map) {
            // 第一阶段的边界（见类注释）：斜率必须是 1，否则这里读的是"变速后的素材"，
            // 而本类还没有 WSOLA。宁可拒绝，也不要用重采样冒充保调变速。
            double min = map.minSpeed();
            double max = map.maxSpeed();
            if (Math.abs(min - 1.0) > 1e-9 || Math.abs(max - 1.0) > 1e-9) {
                throw new UnsupportedOperationException(
                        "第一阶段只支持恒速 1:1 的映射（现在 " + min + "~" + max
                                + "）；变速要等 TempoBridge.stretchCurve 接进来");
            }
        }
    }

    /**
     * 渲染。
     *
     * @param request 请求
     * @param sink    每块的去处（必须是顺序消费；本方法不保留任何块）
     */
    public static Report render(Request request, Sink sink) throws IOException {
        if (request == null || sink == null) {
            throw new IllegalArgumentException("请求与下游都不能为空");
        }
        int rate = request.sampleRate;
        int chs = request.channels;
        ChannelLayout layout = ChannelLayout.of(chs);
        AudioBuf block = new AudioBuf(layout, request.blockFrames);
        TransitionLimiter limiter = new TransitionLimiter(rate, chs);

        long fadeIn = Math.round(request.fadeInSecs * rate);
        long fadeOut = Math.round(request.fadeOutSecs * rate);
        long out = request.outFrames;

        float peakBefore = 0f;
        float peakAfter = 0f;
        boolean acted = false;
        long written = 0;

        for (long o0 = 0; o0 < out; o0 += request.blockFrames) {
            int n = (int) Math.min(request.blockFrames, out - o0);
            block.setFrames(n);
            block.zero();

            if (request.instrumental != null) {
                fillInstrumental(block, request, o0, n);
            }
            if (request.vocals != null) {
                fillVocals(block, request, o0, n, fadeIn, fadeOut);
            }

            peakBefore = Math.max(peakBefore, block.peak());
            limiter.process(block, n);
            acted |= limiter.acted();
            peakAfter = Math.max(peakAfter, block.peak());

            sink.write(block);
            written += n;
        }
        return new Report(written, peakBefore, peakAfter, acted);
    }

    private static void fillInstrumental(AudioBuf block, Request request, long o0, int n) {
        PcmTrack track = request.instrumental;
        long base = request.instrumentalStartFrame + o0;
        float gain = request.instrumentalGain;
        for (int c = 0; c < block.channels(); c++) {
            FloatBuffer view = block.channelWriter(c);
            for (int i = 0; i < n; i++) {
                view.put(i, track.sample(c, base + i) * gain);
            }
        }
    }

    private static void fillVocals(AudioBuf block, Request request, long o0, int n,
                                   long fadeInFrames, long fadeOutFrames) {
        PcmTrack track = request.vocals;
        TimeMap map = request.vocalMap;
        float gain = request.vocalGain;
        long outFrames = request.outFrames;
        int chs = block.channels();
        // 视图在循环外取一次：每个采样都 channelWriter() 一次会分配一堆 FloatBuffer
        FloatBuffer[] views = new FloatBuffer[chs];
        for (int c = 0; c < chs; c++) {
            views[c] = block.channelWriter(c);
        }
        for (int i = 0; i < n; i++) {
            long o = o0 + i;
            // 恒速 1:1 ⇒ srcAt 是整数；仍然 floor 一次，这样映射换成小数斜率时不用改这里
            long src = (long) Math.floor(map.srcAt(o));
            float env = envelope(o, outFrames, fadeInFrames, fadeOutFrames) * gain;
            if (env == 0f) {
                continue;
            }
            for (int c = 0; c < chs; c++) {
                // 相加而不是覆盖：伴奏那一路已经写进这块了（两路都要参与混音）
                views[c].put(i, views[c].get(i) + track.sample(c, src) * env);
            }
        }
    }

    /**
     * 人声包络：起点半余弦淡入、终点半余弦淡出。
     *
     * <p>为什么用半余弦而不是线性：线性的斜率在起点/终点是<b>突变</b>的，那一下本身就是
     * 一个宽带的"咔"。半余弦的斜率在两端为 0，听不出来。
     */
    static float envelope(long frame, long outFrames, long fadeInFrames, long fadeOutFrames) {
        float v = 1f;
        if (fadeInFrames > 0 && frame < fadeInFrames) {
            v *= (float) (0.5 - 0.5 * Math.cos(Math.PI * (double) frame / (double) fadeInFrames));
        }
        long remaining = outFrames - 1 - frame;
        if (fadeOutFrames > 0 && remaining < fadeOutFrames) {
            v *= (float) (0.5 - 0.5 * Math.cos(Math.PI * (double) remaining
                    / (double) fadeOutFrames));
        }
        return v;
    }
}
