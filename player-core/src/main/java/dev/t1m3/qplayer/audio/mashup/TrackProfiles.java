package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.dsp.Lufs;
import dev.t1m3.qplayer.audio.mix.AutoCue;
import dev.t1m3.qplayer.audio.mix.Automix;
import dev.t1m3.qplayer.audio.mix.ReferenceKeyEstimate;

import java.util.Map;

/**
 * <b>把一首歌扫成 {@link TrackProfile}</b>（mashup 第二阶段）。
 *
 * <h2>为什么复用 {@code AutoCue.scan} 而不是自己解码</h2>
 *
 * <p>整曲扫描这件事仓库里已经做过一遍，而且做了缓存与性能优化（一首歌只解一遍；
 * 220 s 曲约 1.1 s）。自己再写一套"解码 + 包络 + chroma + 结构点"不会更准，
 * 只会多一份会和那边分叉的实现。这里只做<b>翻译</b>：把 {@code Envelope} 里的东西
 * 搬进可缓存、可比较的 {@link TrackProfile}。
 *
 * <h2>响度为什么是"近似"</h2>
 *
 * <p>{@code Lufs.integrated} 要<b>整曲 PCM</b>，而整曲立体声 f32 在 48 kHz 下是
 * 92 MB/首（4 分钟）——为了一个实数不值得。这里取<b>中段 60 s、22.05 kHz</b>
 * （约 10 MB 峰值）算积分响度：对"两首歌响度差要不要降权"这个用途，误差远小于
 * 6 dB 的判据阈值。真要做电平配平时（第四阶段）再加"整曲"或"分段加权"的版本。
 *
 * <p>算不出来（解码失败、曲子太短、没有解码器）就写 {@link Double#NaN}，
 * 配对时按"未知"处理——<b>不猜一个数出来</b>。
 */
public final class TrackProfiles {

    /** 近似响度的采样率：够 K 加权用，又把内存压到整曲的 1/4.6。 */
    public static final int LUFS_RATE = 22_050;

    /** 近似响度的取样长度（秒）。 */
    public static final double LUFS_EXCERPT_SECS = 60.0;

    private TrackProfiles() {
    }

    /**
     * 分析一首歌（不读缓存、不写缓存）。
     *
     * @param key     缓存键（{@link ProfileStore#keyOf(String)}；远端可为 URL）
     * @param source  本地路径或 http(s) 直链
     * @param headers 音源请求头（可为 null）
     */
    public static TrackProfile analyze(String key, String source,
                                       Map<String, String> headers) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("分析必须有缓存键");
        }
        AutoCue.Envelope env = AutoCue.scan(source, headers);
        double duration = env.durationSecs;
        double bpm = 0;
        double confidence = 0;
        float periodFrames = 0;
        float phaseFrames = 0;
        int beatRate = env.beatRate;
        if (env.tempo != null) {
            bpm = env.tempo.bpm;
            confidence = env.tempo.confidence;
            periodFrames = env.tempo.periodFrames;
            phaseFrames = env.tempo.phaseFrames;
        }

        float[] meanChroma = meanChroma(env);
        int tonic = -1;
        String mode = "";
        double keyConfidence = 0;
        if (meanChroma != null) {
            ReferenceKeyEstimate.Estimate estimate =
                    ReferenceKeyEstimate.estimate(toDoubles(meanChroma));
            if (estimate != null) {
                tonic = estimate.tonic;
                mode = estimate.mode;
                keyConfidence = estimate.confidence;
            }
        }

        float[] mask = env.voiceActiveMask();
        double vocalShare = meanOf(mask);
        BarFeatures bars = barFeatures(env, bpm, mask);
        double lufs = approximateLufs(source, headers, duration);

        return TrackProfile.builder(key)
                .durationSecs(duration)
                .tempo(bpm, confidence, beatRate, periodFrames, phaseFrames)
                .loudness(lufs)
                .key(tonic, mode, keyConfidence, meanChroma)
                .structure(env.structureIntroStartSecs, env.structureOutroStartSecs)
                .bars(bars.barSecs, bars.rms, bars.onset, bars.vocal, vocalShare)
                .build();
    }

    /**
     * 先读缓存，没有再分析并写回。
     *
     * <p>键算不出来（文件不存在/读不了）时就<b>不缓存</b>，但仍然尝试分析——
     * 分析失败会把异常如实抛给调用方，而不是悄悄给一份空档案。
     */
    public static TrackProfile load(ProfileStore store, String source,
                                    Map<String, String> headers) {
        String key = ProfileStore.keyOf(source);
        if (store != null && key != null) {
            TrackProfile cached = store.read(key);
            if (cached != null) {
                return cached;
            }
        }
        TrackProfile profile = analyze(key != null ? key : ("raw:" + source), source, headers);
        if (store != null && key != null) {
            store.write(profile);
        }
        return profile;
    }

    // ---- 内部 ------------------------------------------------------------

    /** 整曲平均色度（12 维、和为 1）；没有 chroma 时返回 null。 */
    static float[] meanChroma(AutoCue.Envelope env) {
        if (env == null || env.chroma == null || env.chromaFrames <= 0) {
            return null;
        }
        int frames = env.chromaFrames;
        int from = (int) Math.floor(frames * 0.2);
        int to = (int) Math.ceil(frames * 0.8);
        if (to - from < 1) {
            return null;
        }
        double[] acc = new double[12];
        for (int f = from; f < to; f++) {
            int base = f * 12;
            for (int k = 0; k < 12; k++) {
                acc[k] += env.chroma[base + k];
            }
        }
        double sum = 0;
        for (double v : acc) {
            sum += v;
        }
        if (!(sum > 0)) {
            return null;
        }
        float[] out = new float[12];
        for (int k = 0; k < 12; k++) {
            out[k] = (float) (acc[k] / sum);
        }
        return out;
    }

    private static double[] toDoubles(float[] a) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return out;
    }

    /** 每小节特征。没有拍点或没有包络时给空数组（不是造 0 出来）。 */
    static BarFeatures barFeatures(AutoCue.Envelope env, double bpm, float[] mask) {
        if (env == null || env.rms == null || env.rms.length == 0
                || !(bpm > 0) || !(env.durationSecs > 0)) {
            return BarFeatures.EMPTY;
        }
        double barSecs = 240.0 / bpm;                 // 4 拍一小节
        double framesPerSec = env.rms.length / env.durationSecs;
        int bars = (int) Math.floor(env.durationSecs / barSecs);
        if (bars < 1) {
            return BarFeatures.EMPTY;
        }
        float[] rms = new float[bars];
        float[] onset = new float[bars];
        float[] vocal = new float[bars];
        for (int k = 0; k < bars; k++) {
            int i0 = (int) Math.floor(k * barSecs * framesPerSec);
            int i1 = (int) Math.floor((k + 1) * barSecs * framesPerSec);
            i0 = Math.max(0, Math.min(i0, env.rms.length));
            i1 = Math.max(i0, Math.min(i1, env.rms.length));
            if (i1 <= i0) {
                continue;
            }
            float sumRms = 0;
            float sumOnset = 0;
            float sumVocal = 0;
            for (int i = i0; i < i1; i++) {
                sumRms += env.rms[i];
                if (env.onset != null && i < env.onset.length) {
                    sumOnset += env.onset[i];
                }
                if (mask != null && i < mask.length) {
                    sumVocal += mask[i];
                }
            }
            int n = i1 - i0;
            rms[k] = sumRms / n;
            onset[k] = sumOnset / n;
            vocal[k] = sumVocal / n;
        }
        normalizePeak(rms);
        normalizePeak(onset);
        return new BarFeatures(barSecs, rms, onset, vocal);
    }

    /** 按峰值归一到 0..1（选段要的是段内相对形状；绝对响度由 LUFS 负责）。 */
    private static void normalizePeak(float[] a) {
        float max = 0f;
        for (float v : a) {
            max = Math.max(max, v);
        }
        if (max > 0f) {
            for (int i = 0; i < a.length; i++) {
                a[i] /= max;
            }
        }
    }

    private static double meanOf(float[] a) {
        if (a == null || a.length == 0) {
            return 0;
        }
        double sum = 0;
        for (float v : a) {
            sum += v;
        }
        return sum / a.length;
    }

    /** 中段 60 s 的近似积分响度；任何失败都返回 NaN。 */
    static double approximateLufs(String source, Map<String, String> headers, double duration) {
        if (!(duration >= 10.0)) {
            return Double.NaN;
        }
        double lengthSecs = Math.min(LUFS_EXCERPT_SECS, duration);
        double startSecs = Math.max(0.0, (duration - lengthSecs) / 2.0);
        int frames = (int) Math.round(lengthSecs * LUFS_RATE);
        if (frames <= 0) {
            return Double.NaN;
        }
        try {
            float[][] stereo = Automix.readStereoWindow(source, headers, startSecs, frames,
                    LUFS_RATE);
            if (stereo == null || stereo.length == 0 || stereo[0] == null) {
                return Double.NaN;
            }
            float[] right = stereo.length > 1 ? stereo[1] : null;
            double lufs = Lufs.integrated(stereo[0], right, LUFS_RATE);
            return Double.isFinite(lufs) ? lufs : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** 每小节特征一组。 */
    static final class BarFeatures {

        static final BarFeatures EMPTY = new BarFeatures(0, new float[0], new float[0],
                new float[0]);

        final double barSecs;
        final float[] rms;
        final float[] onset;
        final float[] vocal;

        BarFeatures(double barSecs, float[] rms, float[] onset, float[] vocal) {
            this.barSecs = barSecs;
            this.rms = rms;
            this.onset = onset;
            this.vocal = vocal;
        }
    }
}
