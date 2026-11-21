package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.desktop.resources.ClasspathResourceLoader;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.resources.CompressedResources;

/** Pixel tests use the application's bundled CJK faces, not host-installed fonts. */
final class TemperaTestFonts {
    private TemperaTestFonts() { }

    static void initialize() {
        ClasspathResourceLoader resources = new ClasspathResourceLoader();
        Fonts.init(weight -> CompressedResources.load(resources,
                "fonts/PingFangSC-" + switch (weight) {
                    case THIN -> "Thin";
                    case LIGHT -> "Light";
                    case REGULAR -> "Regular";
                    case MEDIUM -> "Medium";
                    // 我们的 Weight 多 BOLD/HEAVY（字重档要）✓ 内置字面只有 4 个 ⇒ 回落 Medium
                    case BOLD, HEAVY -> "Medium";
                } + ".otf"));
        Fonts.setSelection("");
    }
}
