package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.RuntimeEffect;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class LyricRowShaderTest {

    /** A shader Skia rejects silently degrades every lyric row to static text. */
    @Test
    public void bundledRowShaderCompiles() {
        RuntimeEffect effect = LyricRowRenderer.compileShaderResource(
                () -> LyricRowRenderer.class.getResourceAsStream("/shaders/lyric/lyric_row.sksl"),
                RuntimeEffect::makeForShader);
        assertNotNull(effect);
        effect.close();
    }
}
