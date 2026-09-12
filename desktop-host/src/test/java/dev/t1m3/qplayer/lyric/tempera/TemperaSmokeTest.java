package dev.t1m3.qplayer.lyric.tempera;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 「凝彩」引擎的离屏冒烟测试：不用窗口、不用播放器，直接把编译好的程序在一张光栅画布上
 * 按时间轴出帧。
 *
 * <p>它替代的是「手动打开播放器、切到凝彩页、盯着看」这一步——目的是让 42 个移植文件里
 * 任何一处 NPE、数组越界、除零或原生资源误用都在这里暴露出来，而不是在用户点开那一刻。
 * 同时把若干帧保存到 target/tempera-smoke 供人工检查。
 */
public class TemperaSmokeTest {

    private static final int W = 960;
    private static final int H = 540;
    private static final float UI_SCALE = 1f;

    /** 造一段有词级/字级时间的统一歌词：三个段落，段落之间留出能被切段的静默间隔。 */
    private static List<TemperaTypes.SourceLine> syntheticLyrics() {
        List<TemperaTypes.SourceLine> lines = new ArrayList<>();
        lines.add(line("夜色漫过这座城市", 1.0, 4.0));
        lines.add(line("灯火在窗上凝成彩", 4.2, 7.2));
        lines.add(line("我们把名字写进风里", 10.0, 13.2));
        lines.add(line("让每个字都发出声", 13.3, 16.2));
        lines.add(line("凝彩", 19.0, 21.0));
        lines.add(line("—— Tempera ——", 23.0, 26.0));
        return lines;
    }

    private static TemperaTypes.SourceLine line(String text, double start, double end) {
        TemperaTypes.SourceLine source = new TemperaTypes.SourceLine(text, start, end);
        int chars = text.length();
        if (chars == 0) return source;
        double step = (end - start) / chars;
        for (int i = 0; i < chars; i++) {
            String ch = text.substring(i, i + 1);
            double wordStart = start + step * i;
            double wordEnd = wordStart + step;
            TemperaTypes.Word word = new TemperaTypes.Word(ch, wordStart, wordEnd);
            word.addSyllable(ch, wordStart, wordEnd);
            source.addWord(word);
        }
        return source;
    }

    @Test
    public void rendersEveryParagraphWithoutBlowingUp() throws Exception {
        TemperaTuning tuning = new TemperaTuning();
        List<TemperaTypes.SourceLine> lyrics = syntheticLyrics();

        TemperaTypes.Program program = TemperaProgram.compile(lyrics, "#5A5470", tuning);
        assertNotNull("程序不应为 null", program);
        assertTrue("应当编译出段落", program.paragraphs.size() >= 2);

        int shotCount = 0;
        for (TemperaTypes.Paragraph paragraph : program.paragraphs) {
            assertNotNull("段落必须有镜头列表", paragraph.shots);
            shotCount += paragraph.shots.size();
        }
        assertTrue("应当编译出镜头", shotCount > 0);

        TemperaPalette.Theme theme = new TemperaPalette.Theme(
                "#F2F0E8", "#5A5470", "#3A3550", "#B9B3CC");
        TemperaPageRenderer renderer = new TemperaPageRenderer();
        renderer.setProgram(program, theme, new String[]{"#5A5470"});
        renderer.setTuning(tuning);
        renderer.setLyricsFontScale(1f);

        Path outDir = Paths.get("target", "tempera-smoke");
        Files.createDirectories(outDir);

        Surface surface = Surface.makeRasterN32Premul(W, H);
        Canvas canvas = surface.getCanvas();

        List<Double> shots = new ArrayList<>();
        for (int i = 0; i <= 135; i++) shots.add(i * 0.2);

        // 纸色是整帧的底；覆盖率 = 与纸色明显不同的像素占比。构图层（色块/网点/装饰）坏掉时
        // 这个数字会塌到只剩文字和网点的量级，所以它同时是构图层的回归探针。
        int paper = TemperaColor.withAlpha(
                TemperaPalette.resolve(theme, "duo", new String[]{"#5A5470"}).paper, 1f);
        List<Double> coverage = new ArrayList<>();

        int rendered = 0;
        double[] marquee = {2.0, 5.0, 11.0, 14.0, 20.0, 24.5};
        for (double time : shots) {
            canvas.clear(0xFF000000);
            renderer.render(canvas, time, UI_SCALE, W, H, "Digital Girl", "测试歌手");
            coverage.add(mutatedPixels(surface, paper) / (double) (W * H));
            rendered++;
            for (double mark : marquee) {
                if (Math.abs(time - mark) < 0.099) {
                    writePng(surface, outDir.resolve(String.format("frame-%05.1f.png", mark)));
                }
            }
        }
        assertEquals("每一帧都必须无异常地画完", shots.size(), rendered);

        double peak = Collections.max(coverage);
        long rich = coverage.stream().filter(value -> value >= 0.03).count();
        Set<Long> buckets = new HashSet<>();
        for (double value : coverage) buckets.add(Math.round(value * 60));
        System.out.println("TEMPERA_COVERAGE peak=" + peak + " rich=" + rich + "/" + coverage.size()
                + " buckets=" + buckets.size());
        assertTrue("构图层必须真的画上去（峰值覆盖率只有 " + peak + "）", peak >= 0.12);
        assertTrue("至少三分之一的帧要有成规模的色块（" + rich + "/" + coverage.size() + "）",
                rich * 3 >= coverage.size());
        assertTrue("画面必须随镜头/时间变化（只有 " + buckets.size() + " 档覆盖率）",
                buckets.size() >= 12);

        // 最后一帧必须真的有内容：整片纸色是纯色，方差会接近 0。
        canvas.clear(0xFF000000);
        renderer.render(canvas, 2.0, UI_SCALE, W, H, "Digital Girl", "测试歌手");
        double variance = frameVariance(surface);
        writePng(surface, outDir.resolve("frame-assert.png"));
        assertTrue("首段画面不应该是纯色（实际方差 " + variance + "）", variance > 4.0);

        renderer.dispose();
        surface.close();
    }

    /**
     * 覆盖度自检：121 种镜头每一个都要真的往画布上画出东西。
     *
     * <p>上一条测试只覆盖编译器为这几行歌词挑中的少数几种镜头；这条把每一种单独拉出来跑，
     * 于是「某个构图族的某个 kind 少写了一个赋值 / 除零 / 参数错位」会在这里被点名，
     * 而不是等到某首歌恰好抽到它。
     */
    @Test
    public void everyShotKindDrawsSomething() throws Exception {
        assertEquals("121 种镜头都必须登记了绘制器："
                + TemperaCompositions.missingKinds(), 0, TemperaCompositions.missingKinds().size());

        TemperaPalette.Theme theme = new TemperaPalette.Theme(
                "#F2F0E8", "#5A5470", "#3A3550", "#B9B3CC");
        TemperaPalette.Palette palette = TemperaPalette.resolve(theme, "duo", new String[]{"#5A5470"});

        Surface surface = Surface.makeRasterN32Premul(W, H);
        Canvas canvas = surface.getCanvas();

        List<String> blank = new ArrayList<>();
        int paper = TemperaColor.withAlpha(palette.paper, 1f);
        for (String kind : TemperaTypes.SHOT_KINDS) {
            canvas.clear(paper);
            Recorder ctx = new Recorder(canvas, kind, palette);
            TemperaCompositions.drawTemperaComposition(ctx);
            // 画完立刻量：纸色是底，构图必须在此之上留下像素。
            int mutated = mutatedPixels(surface, paper);
            if (ctx.painted == 0 || mutated < 64) {
                blank.add(kind + "(" + ctx.painted + "/" + mutated + ")");
            }
        }

        // 过场卡按定义是净场，却仍然会铺一层满屏色块，所以这里不该有空镜头。
        System.out.println("TEMPERA_COVERAGE blank=" + blank);
        assertEquals("这些镜头什么都没画：" + blank, 0, blank.size());

        surface.close();
    }

    /** 与纸色明显不同的像素数量。 */
    private static int mutatedPixels(Surface surface, int paper) {
        Image image = surface.makeImageSnapshot();
        try {
            ByteBuffer buffer = image.peekPixels();
            assertNotNull("Raster pixels must be readable", buffer);
            buffer.rewind();
            int pr = (paper >> 16) & 0xFF;
            int pg = (paper >> 8) & 0xFF;
            int pb = paper & 0xFF;
            int count = 0;
            for (int i = 0; i < W * H; i++) {
                if (buffer.remaining() < 4) break;
                int b = buffer.get() & 0xFF;
                int g = buffer.get() & 0xFF;
                int r = buffer.get() & 0xFF;
                buffer.get();
                if (Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb) > 12) count++;
            }
            return count;
        } finally {
            image.close();
        }
    }

    /** 把每个图形节点就地画到画布上；父链（倾斜子组）由节点自身的 paint 负责。 */
    private static final class Recorder extends TemperaCompositionContext {
        private final Canvas canvas;
        int painted;

        Recorder(Canvas canvas, String kind, TemperaPalette.Palette palette) {
            this.canvas = canvas;
            this.kind = kind;
            this.palette = palette;
            this.decor = new TemperaTypes.DecorSpec();
            this.decor.crossCount = 5;
            this.decor.hatchAngle = 0.62f;
            this.decor.scribbleSeed = 17;
            this.decor.motif = TemperaTypes.DECOR_MOTIFS[
                    Math.abs(kind.hashCode()) % TemperaTypes.DECOR_MOTIFS.length];
            this.width = W;
            this.height = H;
            this.seed = 0x5F3A21;
            this.showDecor = true;
            this.bleed = 48f;
            this.flowAngle = 0.35f;
            this.gradient = null;
        }

        @Override
        public void add(TemperaDraw.Graphic node, TemperaBlocks.BlockOptions options,
                        TemperaDraw.Graphic parent) {
            if (node == null) return;
            if (parent != null) node.parent = parent;
            // 构图只负责静态几何；这里不跑块运动，直接落最终形态。
            node.paint(canvas);
            painted++;
        }

        @Override
        public TemperaDraw.Graphic createGroup(float rotation, float x, float y) {
            TemperaDraw.Graphic group = new TemperaDraw.Graphic();
            group.rotation = rotation;
            group.x = x;
            group.y = y;
            return group;
        }
    }

    /**
     * 空档期既不能是黑屏，也不能是一张静止的纸。
     *
     * <p>两条空档：整首歌没有歌词（program 为 null），以及有歌词但还没唱到第一个字 —— 用户
     * 报的「完全静止 / 黑屏」正是这两条。用整帧的均值 + 方差判定：黑屏 = 均值 0 且方差 0；
     * 纯纸底 = 均值≈纸色且方差 0；待机卡两者都不同。
     */
    @Test
    public void deadZonesAreNeitherBlackNorFrozen() throws Exception {
        TemperaPalette.Theme theme = new TemperaPalette.Theme(
                "#F2F0E8", "#5A5470", "#3A3550", "#B9B3CC");
        double paperLuminance = 0.2126 * 0xF2 + 0.7152 * 0xF0 + 0.0722 * 0xE8;

        Path outDir = Paths.get("target", "tempera-smoke");
        Files.createDirectories(outDir);
        Surface surface = Surface.makeRasterN32Premul(W, H);
        Canvas canvas = surface.getCanvas();

        // 1) 整首歌没有歌词：以前这里什么都不画，整帧透出黑板。
        TemperaPageRenderer empty = new TemperaPageRenderer();
        empty.setProgram(null, theme, null);
        empty.setTuning(new TemperaTuning());
        for (double time : new double[]{0.0, 4.5, 12.0}) {
            canvas.clear(0xFF000000);
            empty.render(canvas, time, UI_SCALE, W, H, "Digital Girl", "测试歌手");
            double[] stats = frameStats(surface);
            writePng(surface, outDir.resolve(String.format("idle-empty-%04.1f.png", time)));
            assertTrue("没有歌词时不能是黑屏（t=" + time + " 均值 " + stats[0] + "）",
                    stats[0] > paperLuminance * 0.75);
            assertTrue("没有歌词时画面要有内容（t=" + time + " 方差 " + stats[1] + "）",
                    stats[1] > 3.0);
        }
        empty.dispose();

        // 2) 有歌词、但还没唱到第一个字：待机卡，而不是静止的纸。
        TemperaTypes.Program program = TemperaProgram.compile(syntheticLyrics(), "#5A5470",
                new TemperaTuning());
        TemperaPageRenderer preRoll = new TemperaPageRenderer();
        preRoll.setProgram(program, theme, new String[]{"#5A5470"});
        preRoll.setTuning(new TemperaTuning());
        for (double time : new double[]{0.0, 0.6}) {
            canvas.clear(0xFF000000);
            preRoll.render(canvas, time, UI_SCALE, W, H, "Digital Girl", "测试歌手");
            double[] stats = frameStats(surface);
            writePng(surface, outDir.resolve(String.format("idle-preroll-%04.1f.png", time)));
            assertTrue("前奏期间不能是静止的空画面（t=" + time + " 方差 " + stats[1] + "）",
                    stats[1] > 3.0);
        }
        preRoll.dispose();

        surface.close();
    }

    @Test
    public void longPreludeShowsMovingTitleUntilTheFirstLyric() throws Exception {
        TemperaTuning tuning = new TemperaTuning();
        tuning.fluidBackdrop = false;
        tuning.postProcessEnabled = false;
        TemperaTypes.Program program = TemperaProgram.compile(
                Collections.singletonList(line("第一句歌词", 20, 24)), "intro", tuning);
        assertEquals(20.0, program.paragraphs.get(0).shots.get(0).startTime, 0.001);
        Path outDir = Paths.get("target", "tempera-smoke");
        Files.createDirectories(outDir);
        for (String paper : new String[]{"#F2F0E8", "#0B0B10"}) {
            TemperaPalette.Theme theme = new TemperaPalette.Theme(
                    paper, "#5A5470", "#3A3550", "#B9B3CC");
            TemperaPalette.Palette palette = TemperaPalette.resolve(theme, "duo", null);
            TemperaPageRenderer renderer = new TemperaPageRenderer();
            renderer.setTuning(tuning);
            renderer.setProgram(program, theme, null);
            try (Surface actual = Surface.makeRasterN32Premul(W, H);
                 Surface expected = Surface.makeRasterN32Premul(W, H)) {
                byte[] previous = null;
                byte[] first = null;
                for (double time : new double[]{0, 5, 19.9, 20}) {
                    renderer.render(actual.getCanvas(), time, 1, W, H, "Prelude", "Artist");
                    expected.getCanvas().clear(TemperaColor.withAlpha(palette.paper, 1));
                    TemperaIdle.paint(expected.getCanvas(), palette, W, H, time,
                            "Prelude", "Artist", tuning.textInversion);
                    byte[] pixels = pixels(actual);
                    assertArrayEquals("The first scene must not cover the intro at " + time,
                            pixels(expected), pixels);
                    if (previous != null) {
                        int changed = 0;
                        for (int i = 0; i < pixels.length; i += 4) {
                            if (Math.abs((pixels[i] & 255) - (previous[i] & 255))
                                    + Math.abs((pixels[i + 1] & 255) - (previous[i + 1] & 255))
                                    + Math.abs((pixels[i + 2] & 255) - (previous[i + 2] & 255)) > 12) changed++;
                        }
                        assertTrue("Prelude must keep moving at " + time, changed > W * H / 1000);
                    }
                    if (first == null) first = pixels;
                    previous = pixels;
                    writePng(actual, outDir.resolve("intro-" + paper.substring(1) + "-" + time + ".png"));
                }
                renderer.render(actual.getCanvas(), 21, 1, W, H, "Prelude", "Artist");
                writePng(actual, outDir.resolve("intro-" + paper.substring(1) + "-21.0.png"));
                assertTrue("Lyrics take over after intro", !java.util.Arrays.equals(previous, pixels(actual)));
                renderer.render(actual.getCanvas(), 0, 1, W, H, "Prelude", "Artist");
                assertArrayEquals("Seeking back restores the intro", first, pixels(actual));
            } finally {
                renderer.dispose();
            }
        }
    }

    private static byte[] pixels(Surface surface) {
        try (Image image = surface.makeImageSnapshot()) {
            ByteBuffer buffer = image.peekPixels();
            assertNotNull(buffer);
            buffer.rewind();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        }
    }

    /** 整帧的 {均值亮度, 亮度方差}；像素读取失败时测试失败。 */
    private static double[] frameStats(Surface surface) {
        Image image = surface.makeImageSnapshot();
        try {
            ByteBuffer buffer = image.peekPixels();
            assertNotNull("Raster pixels must be readable", buffer);
            buffer.rewind();
            int pixels = W * H;
            double sum = 0;
            double sumSq = 0;
            int counted = 0;
            for (int i = 0; i < pixels; i++) {
                if (buffer.remaining() < 4) break;
                int b = buffer.get() & 0xFF;
                int g = buffer.get() & 0xFF;
                int r = buffer.get() & 0xFF;
                buffer.get();
                double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                sum += luminance;
                sumSq += luminance * luminance;
                counted++;
            }
            assertTrue("Frame must contain pixels", counted > 0);
            double mean = sum / counted;
            return new double[]{mean, sumSq / counted - mean * mean};
        } finally {
            image.close();
        }
    }

    /** 整帧亮度方差；像素读取失败时测试失败。 */    private static double frameVariance(Surface surface) {
        Image image = surface.makeImageSnapshot();
        try {
            ByteBuffer buffer = image.peekPixels();
            assertNotNull("Raster pixels must be readable", buffer);
            buffer.rewind();
            int pixels = W * H;
            double sum = 0;
            double sumSq = 0;
            int counted = 0;
            for (int i = 0; i < pixels; i++) {
                if (buffer.remaining() < 4) break;
                int b = buffer.get() & 0xFF;
                int g = buffer.get() & 0xFF;
                int r = buffer.get() & 0xFF;
                buffer.get();
                double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                sum += luminance;
                sumSq += luminance * luminance;
                counted++;
            }
            assertTrue("Frame must contain pixels", counted > 0);
            double mean = sum / counted;
            return sumSq / counted - mean * mean;
        } finally {
            image.close();
        }
    }

    private static void writePng(Surface surface, Path path) throws Exception {
        Image image = surface.makeImageSnapshot();
        try {
            Data data = image.encodeToData(EncodedImageFormat.PNG);
            try {
                Files.write(path, data.getBytes());
            } finally {
                data.close();
            }
        } finally {
            image.close();
        }
    }
}
