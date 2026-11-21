package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricSpringTest {

    private static float[] run(LyricSpring spring, int frames, double dt) {
        float[] position = {0f};
        float[] velocity = {0f};
        for (int i = 0; i < frames; i++) spring.step(position, velocity, 0, 100f, dt);
        return new float[]{position[0], velocity[0]};
    }

    /** Lines must move the same on a 30 Hz and a 144 Hz display. */
    @Test
    public void motionDoesNotDependOnFrameRate() {
        for (LyricSpring spring : new LyricSpring[]{
                new LyricSpring(65.0, 11.0), LyricSpring.critical(65.0), new LyricSpring(180.0, 28.0)}) {
            float[] coarse = run(spring, 6, 1.0 / 30.0);
            float[] fine = run(spring, 24, 1.0 / 120.0);
            assertEquals(coarse[0], fine[0], 0.01f);
            assertEquals(coarse[1], fine[1], 0.05f);
        }
    }

    @Test
    public void underdampedSpringOvershootsOnceAndSettles() {
        LyricSpring spring = new LyricSpring(65.0, 11.0);
        float[] position = {0f};
        float[] velocity = {0f};
        float peak = 0f;
        for (int i = 0; i < 240; i++) {
            spring.step(position, velocity, 0, 100f, 1.0 / 60.0);
            peak = Math.max(peak, position[0]);
        }
        assertTrue(peak > 100f && peak < 108f);
        assertEquals(100f, position[0], 0f);
        assertEquals(0f, velocity[0], 0f);
    }
}
