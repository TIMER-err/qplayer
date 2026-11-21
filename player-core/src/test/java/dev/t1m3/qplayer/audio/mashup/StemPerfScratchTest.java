package dev.t1m3.qplayer.audio.mashup;

import dev.t1m3.qplayer.audio.decode.Decoder;
import dev.t1m3.qplayer.audio.mix.TestWavFile;
import dev.t1m3.qplayer.audio.model.BeatModels;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

public class StemPerfScratchTest {

    private static final int RATE = 48_000;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private Decoder.Opener saved;

    @Before
    public void install() {
        saved = opener();
        Decoder.install(TestWavFile.opener());
        BeatModels.uninstall();
        ModelDownloads.uninstall();
    }

    @After
    public void restore() {
        BeatModels.uninstall();
        ModelDownloads.uninstall();
        Decoder.install(saved);
    }

    @Test
    public void measureNinetySeconds() throws IOException {
        double seconds = 90.0;
        Path wav = tmp.newFile("perf.wav").toPath();
        int frames = (int) Math.round(RATE * seconds);
        float[] l = new float[frames];
        float[] r = new float[frames];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / RATE;
            double v = 0.3 * Math.sin(2 * Math.PI * 220 * t) + 0.2 * Math.sin(2 * Math.PI * 110 * t);
            l[i] = (float) v;
            r[i] = (float) (v * 0.95);
        }
        TestWavFile.write(wav, l, r, RATE);
        StemStore store = new StemStore(tmp.newFolder("stems").toPath());
        long t0 = System.nanoTime();
        Path p = store.ensure("perf", wav.toString(), null, RATE, null, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long fileBytes;
        try (StemFile sf = StemFile.open(p)) {
            fileBytes = sf.frames() * StemFile.BYTES_PER_FRAME;
        }
        System.out.println("PERF 90s: " + ms + " ms, stem bytes=" + fileBytes
                + ", ratio=" + (seconds * 1000.0 / ms));
    }

    private static Decoder.Opener opener() {
        try {
            Field f = Decoder.Holder.class.getDeclaredField("opener");
            f.setAccessible(true);
            return (Decoder.Opener) f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
