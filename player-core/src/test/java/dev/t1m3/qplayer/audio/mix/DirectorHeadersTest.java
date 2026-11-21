package dev.t1m3.qplayer.audio.mix;

import dev.t1m3.qplayer.audio.core.AudioBuf;
import dev.t1m3.qplayer.audio.core.ChannelLayout;
import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.decode.TrackSpec;

import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 请求头要<strong>一路通到混音层</strong>：分析（对拍/判调性/自动点位扫整曲）与进场 deck 的开流
 * 都是混音层自己去开解码器，而插件直链常常必须带 token/Referer。
 *
 * <p>这条链子有五段：控制器 → {@code AudioBackend.setNextSource(source, headers, ms)}
 * → 引擎的「句柄 → 请求头」表 → {@code Director.HeadersLookup}
 * → {@code Transition.prepare(..., outroHeaders, introHeaders)} 与 {@code Deck.open(source, headers, …)}。
 * 这里从第 4 段往下一路钉到解码器：装一个记录请求头的假解码器，断言
 * <strong>准备阶段的分析</strong>与<strong>进场 deck 的开流</strong>都拿到了那份头。
 */
public class DirectorHeadersTest {

    private static final int RATE = 22_050;
    private static final String OUTRO = "outro-src";
    private static final String INTRO = "intro-src";
    private static final Map<String, String> OUTRO_HEADERS = headers("X-Outro", "one");
    private static final Map<String, String> INTRO_HEADERS = headers("X-Intro", "two");

    private static Map<String, String> headers(String k, String v) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(k, v);
        return Collections.unmodifiableMap(m);
    }

    @Test
    public void prepareAndIntroDeckReceiveTheHeaders() {
        Recorder recorder = new Recorder(60 * RATE);
        Decoder.install(recorder);
        try {
            RecordingFactory factory = new RecordingFactory(4_800L);
            Director director = new Director(Arrays.asList(OUTRO, INTRO), RATE, 1, factory);
            director.setHeadersLookup(new Director.HeadersLookup() {
                @Override
                public Map<String, String> headersFor(String source) {
                    if (OUTRO.equals(source)) {
                        return OUTRO_HEADERS;
                    }
                    return INTRO.equals(source) ? INTRO_HEADERS : Collections.<String, String>emptyMap();
                }
            });

            AudioBuf buf = new AudioBuf(ChannelLayout.of(1), Deck.RENDER_FRAMES);
            for (int i = 0; i < 6000 && !director.inTransition(); i++) {
                if (director.pump(buf) == 0) {
                    break;
                }
            }
            assertTrue("应当已经进入过渡（准备了 60 秒的素材）", director.inTransition());

            assertEquals("分析出场曲时要带上它的请求头", OUTRO_HEADERS, factory.outroHeaders);
            assertEquals("分析进场曲时要带上它的请求头", INTRO_HEADERS, factory.introHeaders);
            assertTrue("进场 deck 开流时必须带上请求头（否则插件直链根本打不开）",
                    recorder.sawOpenWith(INTRO, INTRO_HEADERS));
        } finally {
            Decoder.install(null);
        }
    }

    /** 假解码器：记录每次 open 收到的请求头，并无限供静音（够放完这段测试）。 */
    private static final class Recorder implements Decoder.Opener {
        private final int frames;
        private final Map<String, Map<String, String>> seen =
                Collections.synchronizedMap(new HashMap<String, Map<String, String>>());

        Recorder(int frames) {
            this.frames = frames;
        }

        @Override
        public Decoder open(String source, Map<String, String> headers) {
            seen.put(source, headers == null ? Collections.<String, String>emptyMap() : headers);
            return new Fake(source);
        }

        boolean sawOpenWith(String source, Map<String, String> expected) {
            Map<String, String> got = seen.get(source);
            return got != null && got.equals(expected);
        }

        private final class Fake implements Decoder {
            private int position;

            Fake(String source) {
            }

            @Override
            public TrackSpec spec() {
                return new TrackSpec("pcm", RATE, ChannelLayout.of(1), Integer.valueOf(16),
                        Long.valueOf(frames), true);
            }

            @Override
            public long framesDecoded() {
                return position;
            }

            @Override
            public boolean isDrained() {
                return position >= frames;
            }

            @Override
            public double seek(double secs) {
                position = Math.max(0, Math.min(frames, (int) (secs * RATE)));
                return position / (double) RATE;
            }

            @Override
            public int decodeInto(AudioBuf out) {
                int n = Math.min(out.capacityFrames(), frames - position);
                if (n <= 0) {
                    out.setFrames(0);
                    return 0;
                }
                FloatBuffer dst = out.channelWriter(0);
                for (int i = 0; i < n; i++) {
                    dst.put(i, 0.1f);
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

    /** 记录 prepare 收到的请求头，其余行为与最小可用的接法一致。 */
    private static final class RecordingFactory implements TransitionFactory {
        private final long window;
        private Map<String, String> outroHeaders;
        private Map<String, String> introHeaders;

        RecordingFactory(long window) {
            this.window = window;
        }

        @Override
        public Transition create() {
            return new Transition() {
                @Override
                public long windowFrames(int sampleRate) {
                    return window;
                }

                @Override
                public int render(AudioBuf out, AudioBuf from, AudioBuf to, float progress) {
                    return Mixer.gainInto(out, progress < 0.5f ? from : to, 1.0f);
                }

                @Override
                public String name() {
                    return "假接法（记录请求头）";
                }

                @Override
                public boolean rewritesSamples() {
                    return true;
                }

                @Override
                public TransitionPlan prepare(String outro, String intro, int rate,
                                              Map<String, String> oh, Map<String, String> ih) {
                    outroHeaders = oh;
                    introHeaders = ih;
                    return TransitionPlan.NONE;
                }
            };
        }
    }
}
