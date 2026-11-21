package dev.t1m3.qplayer.lyric.skia;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>「间奏点必须长在行上」——判据（2026-10-03）</b>。
 *
 * <h3>用户报的现象</h3>
 *
 * <p>「间奏点<b>有时</b>垂直错位」。注意「有时」：静止看、慢滚看都正常，只有滚动正在
 * 收敛（逐行弹簧还没追上，或一次大跳正在缓动）那几帧才错开，而且错开量就是「点用的基准
 * 和行用的基准差多少」。
 *
 * <h3>根因</h3>
 *
 * <p>画行和画点各写了一套「第 i 行这一帧在哪」的判断，三处不一致：
 *
 * <ol>
 *   <li>画行用 {@code rigidMode}（{@code !spring || seekEaseActive || seekSpringActive}），
 *       画点用 {@code spring}——大跳缓动那几帧弹簧对象还在，画行走刚性、点却去读弹簧值；</li>
 *   <li>画点多一个 {@code nf >= start && nf < end}（在绘制窗口内）的分叉，同一行在窗口边界
 *       两侧会换基准；</li>
 *   <li>画点用<b>目标</b>滚动量 {@code targetScroll}，画行用<b>实际</b>滚动量 {@code scrollY}
 *       ——弹簧/全局滚动动画没追上目标时，点按「已经到位」的位置画，行还在路上。</li>
 * </ol>
 *
 * <h3>这条判据怎么判</h3>
 *
 * <p>修法是结构性的：两处<b>必须</b>调同一个 {@link LyricRenderer#drawnLineTop}，点圆心也必须
 * 走 {@link LyricRenderer#interludeDotTop}。所以判据分两层：
 *
 * <ul>
 *   <li><b>行为层</b>：直接把纯函数钉死——刚性帧不看弹簧值（哪怕弹簧差 300px），弹簧帧不看
 *       滚动量，并显式断言「旧公式在这一帧会给出另外一个答案」（这条就是那次错位的数值证据）；</li>
 *   <li><b>结构层</b>：读 {@code LyricRenderer.java}，把「画行」那段和「画点」那段各自切出来，
 *       断言两段都出现 {@code drawnLineTop(}，且画点那段里<b>不出现</b> {@code targetScroll} /
 *       {@code centerY} / {@code ? lineCurTop} —— 也就是它再也不可能自己算一遍。</li>
 * </ul>
 *
 * <p>结构层是必要的：这两个基准分叉过一次，而分叉在真机上只表现为「有概率错位」，
 * 光靠行为层判据守不住下一次有人「顺手把点那段改回去」。
 */
public class LyricInterludeAnchorTest {

    /** 画行的基准（{@code centerY + lineTops} 里的 centerY）。 */
    private static final float CENTER_Y = 500f;

    // ------------------------------------------------ 行为层：纯函数契约

    @Test
    public void rigidFramesIgnoreTheSpringValueEntirely() {
        // 刚性帧 = 静止布局位置 − 实际滚动量。
        assertEquals(440f, LyricRenderer.drawnLineTop(true, CENTER_Y, 40f, 100f, 777f), 0.001f);
        assertEquals(515f, LyricRenderer.drawnLineTop(true, CENTER_Y, 40f, 25f, -999f), 0.001f);
        // 弹簧值（第 5 个参数）在刚性帧里必须完全不参与。
        assertEquals(LyricRenderer.drawnLineTop(true, CENTER_Y, 40f, 25f, 1f),
                LyricRenderer.drawnLineTop(true, CENTER_Y, 40f, 25f, 9999f), 0.0f);
    }

    @Test
    public void springFramesDrawExactlyWhereTheSpringIs() {
        assertEquals(700f, LyricRenderer.drawnLineTop(false, CENTER_Y, 40f, 25f, 700f), 0.001f);
        assertEquals(-30f, LyricRenderer.drawnLineTop(false, CENTER_Y, 40f, 25f, -30f), 0.001f);
        // 弹簧帧里滚动量必须完全不参与（连 NaN 都不能影响结果）。
        assertEquals(12.5f, LyricRenderer.drawnLineTop(false, CENTER_Y, 40f, Float.NaN, 12.5f),
                0.001f);
        assertEquals(12.5f, LyricRenderer.drawnLineTop(false, CENTER_Y, 40f, 12345f, 12.5f),
                0.001f);
    }

    /**
     * <b>那次错位的数值证据</b>：一次大跳缓动中途，{@code spring == true} 但 {@code rigidMode == true}，
     * 且实际滚动量还没到目标。此时画行走刚性（示例里 440），旧的点基准会走 {@code lineCurTop}
     * （示例里 777）。两者差 337px——这就是用户看到的「错开一截」。
     */
    @Test
    public void aSeekEaseFrameIsWhereTheOldDotBasisDrifted() {
        boolean springExists = true;
        boolean rigidMode = true;          // 大跳缓动期间
        float actualScrollY = 100f;        // 动画走到一半
        float targetScroll = 660f;         // 目标还没到
        float springValue = 777f;          // 这一行弹簧还在半路

        float drawn = LyricRenderer.drawnLineTop(rigidMode, CENTER_Y, 40f, actualScrollY,
                springValue);
        assertEquals(440f, drawn, 0.001f);
        assertTrue("刚性帧不能取弹簧值", Math.abs(drawn - springValue) > 0.5f);

        float oldDots = springExists
                ? springValue
                : CENTER_Y + 40f - targetScroll;
        assertTrue("旧基准在这一帧会给出另一个位置（这正是那次错位）",
                Math.abs(drawn - oldDots) > 0.5f);
        assertEquals("差值就是弹簧/滚动动画还没追上的那段", 337f, oldDots - drawn, 0.001f);
    }

    @Test
    public void theDotCentreSitsBetweenTheTwoTextEdges() {
        // slotH = 预留的点位高度；nextTextOffset = 该行的「top → 文字顶」偏移。
        // 期望 = (上一行文字底 + 下一行文字顶)/2 − 点半径
        //      = ((100-60) + (100+50))/2 − 3 = 92。
        float prevTextBottom = 100f - 60f;
        float nextTextTop = 100f + 50f;
        assertEquals((prevTextBottom + nextTextTop) * 0.5f - 3f,
                LyricRenderer.interludeDotTop(100f, 60f, 50f, 3f), 0.001f);
        assertEquals(92f, LyricRenderer.interludeDotTop(100f, 60f, 50f, 3f), 0.001f);
        // 点随行走：nextTop 平移多少，圆心就平移多少（斜率 1，不放大不缩水）。
        float a = LyricRenderer.interludeDotTop(100f, 60f, 50f, 3f);
        float b = LyricRenderer.interludeDotTop(140f, 60f, 50f, 3f);
        assertEquals(40f, b - a, 0.001f);
    }

    /** 端到端（仍是纯函数）：行基准 → 点圆心，全程只经过这两个被钉死的函数。 */
    @Test
    public void theWholeChainFromLineBasisToDotCentreIsPinned() {
        float nextTop = LyricRenderer.drawnLineTop(true, CENTER_Y, 40f, 100f, 777f);
        float dotTop = LyricRenderer.interludeDotTop(nextTop, 60f, 50f, 3f);
        // (440-60 + 440+50)/2 - 3 = 432
        assertEquals(432f, dotTop, 0.001f);
    }

    // ------------------------------------------------ 结构层：两处同源

    @Test
    public void theDotsAndTheLinesMustBothRouteThroughDrawnLineTop() throws IOException {
        String src = readRenderer();

        String code = stripLineComments(src);
        String lineRegion = region(code, LINE_REGION_FROM, LINE_REGION_TO);
        String dotsRegion = region(code, DOTS_REGION_FROM, DOTS_REGION_TO);

        assertTrue("画行那段必须经 drawnLineTop：\n" + lineRegion,
                lineRegion.contains("drawnLineTop("));
        assertTrue("画点那段必须经 drawnLineTop：\n" + dotsRegion,
                dotsRegion.contains("drawnLineTop("));
        assertTrue("画点那段必须把 rigidMode（而不是 spring）传进去，否则大跳缓动帧又会分叉：\n"
                + dotsRegion, dotsRegion.contains("drawnLineTop(rigidMode,"));
        assertTrue("点圆心必须经 interludeDotTop：\n" + dotsRegion,
                dotsRegion.contains("interludeDotTop("));
    }

    @Test
    public void theDotsRegionNeverRebuildsItsOwnBasis() throws IOException {
        String dotsRegion = region(stripLineComments(readRenderer()),
                DOTS_REGION_FROM, DOTS_REGION_TO);
        assertNo(dotsRegion, "targetScroll", "画点不能用目标滚动量（行用的是实际滚动量）");
        assertNo(dotsRegion, "centerY +", "画点不能再自己拼静止布局位置（那是 drawnLineTop 的活）");
        assertNo(dotsRegion, "? lineCurTop", "画点不能有「窗口内用弹簧值」这种分叉");
        assertNo(dotsRegion, "spring &&", "画点不能用 spring 判断（要用 rigidMode）");
        assertNo(dotsRegion, "userScroll.isActive()", "画点不能按「用户是否在拖」再分一套基准");
    }

    /** 基准公式只许有一份，且必须就在 {@code drawnLineTop} 里面。 */
    @Test
    public void theBasisFormulaExistsExactlyOnce() throws IOException {
        String src = readRenderer();
        int calls = count(src, "drawnLineTop(") - count(src, "static float drawnLineTop(");
        assertEquals("drawnLineTop 的调用点必须正好两处（画行 + 画点）", 2, calls);
        assertEquals("静止布局 − 实际滚动量 这个式子只许出现在 drawnLineTop 里",
                1, count(src, "return rigidMode ? centerY + restingTop - scrollY : springTop;"));
        assertEquals("点圆心的式子只许出现在 interludeDotTop 里",
                1, count(src, "(prevTextBottom + nextTextTop) * 0.5f - dotRadius"));
    }

    // ------------------------------------------------ 取材

    // 「画行那一段」和「画点那一段」的边界标记：都取在**改动前后都存在**的相邻行上，
    // 这样判据不会因为文件里别的改动而找不到；找不到时 assertTrue 会大声报出来。
    private static final String LINE_REGION_FROM =
            "float restTop = centerY + lineTops[i] - targetScroll;";
    private static final String LINE_REGION_TO = "if (spring && scaleSpring.length == n) {";
    private static final String DOTS_REGION_FROM =
            "LyricTimeline.Group interludeNext = groups.get(";
    private static final String DOTS_REGION_TO =
            "LyricLine.VocalChannel nextCh = lines.get(interludeNext.from).vocalChannel;";

    /**
     * 去掉 {@code //} 行注释——判据只看<b>代码</b>。
     *
     * <p>这几个区间的注释里正解释着「为什么不能再用 targetScroll」（历史原因还在，
     * 值得留），不剥掉的话判据会被自己的注释绊倒。
     */
    private static String stripLineComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            int i = line.indexOf("//");
            out.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return out.toString();
    }

    private static void assertNo(String region, String needle, String why) {
        assertTrue(why + "，但出现了 `" + needle + "`：\n" + region, !region.contains(needle));
    }

    /**
     * 从含 {@code from} 的那一行开始，到含 {@code to} 的那一行结束（含两端）。
     *
     * <p>{@code from} 之后的第一个 {@code to} 生效——两段代码各自的起止标记在文件里都不唯一，
     * 但这个「取最近的一对」就是在切它们本身。
     */
    private static String region(String src, String from, String to) {
        int a = src.indexOf(from);
        assertTrue("找不到起始标记：" + from, a >= 0);
        int b = src.indexOf(to, a);
        assertTrue("起始标记之后找不到结束标记：" + to, b >= 0);
        return src.substring(a, b + to.length());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String readRenderer() throws IOException {
        File f = new File(repoRoot(),
                "player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/LyricRenderer.java");
        assertTrue("找不到 LyricRenderer.java：" + f, f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 从 {@code user.dir} 往上找仓库根（与 {@code RenderThreadUiHazardLintTest} 同一套做法）。 */
    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "player-core").isDirectory()
                    && new File(dir, "shared-qml").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        fail("找不到仓库根目录（从 " + System.getProperty("user.dir") + " 往上找过）");
        return null;
    }
}
