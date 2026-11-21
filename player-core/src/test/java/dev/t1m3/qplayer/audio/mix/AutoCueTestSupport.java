package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

/**
 * 拍点对齐那组测试的脚手架：装一个<strong>假解码器</strong>，把一段合成 PCM 喂给
 * <em>真正的</em> {@link AutoCue#scan(String)}。
 *
 * <p>为什么不直接调内部工具：扫描这一路（降采样、RMS hop、流式谱通量、取帧边界）
 * 正是要对的东西，绕过它就等于什么都没测。
 */
final class AutoCueTestSupport {

    private AutoCueTestSupport() {
    }

    /** 把这段 PCM 当成一首曲子扫一遍（采样率即真实采样率，声道 1）。 */
    static AutoCue.Envelope scan(float[] pcm, int rate) {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                return new FakeDecoder(pcm, rate);
            }
        });
        try {
            return AutoCue.scan("test://fake");
        } finally {
            Decoder.install(null);
        }
    }

    /**
     * 只装解码器、不扫：给「自己会去扫」的那一层（{@code AutoCueTransition.prepare}）用。
     *
     * <p>用完要 {@code Decoder.install(null)} 还原（与 {@link #scan} 同一套纪律）。
     */
    static void install(float[] pcm, int rate) {
        install(pcm, rate, null);
    }

    /** 同上，但把「打开了几次」记进 {@code opens}（缓存判据用：扫两遍只该开一次）。 */
    static void install(float[] pcm, int rate, java.util.concurrent.atomic.AtomicInteger opens) {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                if (opens != null) {
                    opens.incrementAndGet();
                }
                return new FakeDecoder(pcm, rate);
            }
        });
    }

    /**
     * 装一个<strong>对某个音源会打不开</strong>的假解码器：给「扫不动时怎么退化、日志怎么写」
     * 那类判据用。
     *
     * @param failingSource 音源里含这个子串就抛 {@code AudioException.io}，其余照常解码
     */
    static void installFailing(String failingSource, float[] pcm, int rate) {
        Decoder.install(new Decoder.Opener() {
            @Override
            public Decoder open(String source, java.util.Map<String, String> headers) {
                if (source != null && source.contains(failingSource)) {
                    throw dev.t1m3.qplayer.audio.core.AudioException.io(
                            "打开音源失败：" + source, null);
                }
                return new FakeDecoder(pcm, rate);
            }
        });
    }

    /** 造一个真实存在的临时文件（扫描缓存只对**本地文件**生效，键里要它的长度与时间）。 */
    static String tempFile() throws java.io.IOException {
        java.nio.file.Path path = java.nio.file.Files.createTempFile("autocue-cache", ".bin");
        java.nio.file.Files.write(path, new byte[]{1, 2, 3, 4});
        path.toFile().deleteOnExit();
        return path.toString();
    }

    /** 造一个空的流式谱通量（等价性测试自己 push）。 */
    static AutoCue.OnsetFlux stream() {
        return new AutoCue.OnsetFlux();
    }

    private static final class FakeDecoder implements Decoder {

        private final float[] pcm;
        private final int rate;
        private int position;

        FakeDecoder(float[] pcm, int rate) {
            this.pcm = pcm;
            this.rate = rate;
        }

        @Override
        public TrackSpec spec() {
            return new TrackSpec("pcm", rate, ChannelLayout.of(1), Integer.valueOf(16),
                    Long.valueOf(pcm.length), true);
        }

        @Override
        public long framesDecoded() {
            return position;
        }

        @Override
        public boolean isDrained() {
            return position >= pcm.length;
        }

        @Override
        public double seek(double secs) {
            position = Math.max(0, Math.min(pcm.length, (int) (secs * rate)));
            return position / (double) rate;
        }

        @Override
        public int decodeInto(AudioBuf out) {
            int n = Math.min(out.capacityFrames(), pcm.length - position);
            if (n <= 0) {
                out.setFrames(0);
                return 0;
            }
            java.nio.FloatBuffer dst = out.channelWriter(0);
            for (int i = 0; i < n; i++) {
                dst.put(i, pcm[position + i]);
            }
            out.setFrames(n);
            position += n;
            return n;
        }

        @Override
        public void close() {
        }
    }
}
