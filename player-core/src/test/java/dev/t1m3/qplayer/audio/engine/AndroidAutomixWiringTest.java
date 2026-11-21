package dev.t1m3.qplayer.audio.engine;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>安卓宿主的自动混音接线检查。</b>
 *
 * <p>{@code AudioBackend} 上有三条「接口加了默认实现、某个宿主忘了转发」的坑，历史上
 * 每一条都真的发生过（见 {@code AudioBackend} 的注释）：
 *
 * <ol>
 *   <li>{@code currentSource()} —— 漏了就是「每遇到一次自动交接就多推一首队列」
 *       （刚交接过去的歌放几秒又跳）；2026-09-20 安卓真机实测就是这个原因。
 *       后来它改成了抽象方法，编译器替我们挡住了。</li>
 *   <li>{@code setOnTransitionStart(...)} —— 漏了就是「过渡一开始界面就跳下一首」这条
 *       等于没生效（桌面端好、安卓端赖在出场曲上）。同样改成了抽象方法。</li>
 *   <li>{@code setNextSource(source, headers, startMs)} —— <strong>这条有默认实现</strong>
 *       （别的后端可以真的不需要请求头），默认实现<strong>丢掉请求头</strong>转调老签名。
 *       安卓只实现了老签名，于是控制器交下来的 token/Referer 在宿主机这一层蒸发了：
 *       插件直链的整曲扫描（自动点位）与开进场流都会失败/退化成「把整首歌再下一遍」，
 *       用户听到的是「开了自动点位反而完全没有过渡」（2026-09-26 真机报告）。</li>
 * </ol>
 *
 * <h2>为什么是源码级检查</h2>
 *
 * <p>{@code AndroidPlaybackBackend} 的构造函数要 {@code android.content.Context}，
 * player-core 的 JUnit 里跑不起来（这里没有 Robolectric，也不打算为一个宿主类引入它）。
 * 所以这一条只能查「那两行转发在不在」——它比行为测试弱，但正好覆盖了那个真正的缺口：
 * <strong>漏转发是静默的</strong>，而行为本身（引擎收到头之后怎么用）由
 * {@code PlaybackEngineTest} 与 {@code DirectorHeadersTest} 覆盖。
 *
 * <p>源码注释里写「已经转发」不算数（注释与代码会分家），所以判据是<strong>方法体里的
 * 调用原文</strong>：{@code engine.setNextSource(source, headers, startMs)}。
 */
public class AndroidAutomixWiringTest {

    /** 安卓宿主的实现文件（仓库相对路径）。 */
    private static final String BACKEND =
            "android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/"
                    + "AndroidPlaybackBackend.java";

    /**
     * 三参预告必须把请求头原样交给引擎。
     *
     * <p>少了这一句的表现：插件直链的分析与开流都拿不到 token/Referer。
     */
    @Test
    public void theAndroidHostForwardsNextSourceHeadersToTheEngine() {
        String src = readBackend();
        assertTrue("安卓宿主必须覆盖三参 setNextSource（否则默认实现会把请求头丢掉）："
                        + "没找到 `setNextSource(String source, Map<String, String> headers, long startMs)`",
                src.contains("setNextSource(String source, Map<String, String> headers, long startMs)"));
        assertTrue("覆盖了也必须真的转给引擎，而且要把 headers 带上——"
                        + "`engine.setNextSource(source, headers, startMs)` 这一句不见了",
                src.contains("engine.setNextSource(source, headers, startMs)"));
    }

    /**
     * 交接确认必须转发：不转发的话引擎那枚一次性令牌一直挂着，
     * 用户下一次点这一首会被静默吃掉（点了没反应）。
     */
    @Test
    public void theAndroidHostForwardsHandoffConfirmation() {
        String src = readBackend();
        assertTrue("安卓宿主必须实现 confirmHandoff（AudioBackend 上它是抽象方法，"
                        + "但宿主这一层仍可能实现成空方法）",
                src.contains("public void confirmHandoff(String source)"));
        assertTrue("实现体必须转给引擎：`engine.confirmHandoff(source)` 不见了",
                src.contains("engine.confirmHandoff(source)"));
    }

    /**
     * 换曲那条（三参 {@code play}）也要带头——这一条本来就有，
     * 写在这里是为了让「三条带 headers 的通路」在同一处被看全。
     */
    @Test
    public void theAndroidHostForwardsPlayHeadersToTheEngine() {
        String src = readBackend();
        assertTrue("安卓宿主的 play 必须把请求头转给引擎（当前曲的直链就靠它）",
                src.contains("engine.play(source, headers, startMs)"));
    }

    /**
     * 默认实现那条路不能被走回来：只要宿主自己不去覆盖三参重载，
     * 编译器就会把请求头交给默认实现（丢掉）。这里顺带钉住「没有把它删掉改成只转发老签名」。
     */
    @Test
    public void theAndroidHostDoesNotSilentlyFallBackToTheTwoArgOverload() {
        String src = readBackend();
        assertFalse("三参预告不许只转老签名（那就是「悄悄丢掉请求头」这个 bug 的原样）："
                        + "找到了 `engine.setNextSource(source, startMs)` 出现在三参方法里",
                src.contains("engine.setNextSource(source, startMs)")
                        && !src.contains("engine.setNextSource(source, headers, startMs)"));
    }

    /**
     * <b>工作室页的四条实时数据也必须转发。</b>
     *
     * <p>{@code studioSpectrum} / {@code studioMeters} / {@code studioGainReductionDb} /
     * {@code transitionSnapshot} 在 {@code AudioBackend} 上<strong>都有默认实现</strong>
     * （空频谱、静音表头、0 dB 衰减、IDLE 快照），所以宿主漏转发是<strong>静默的</strong>：
     * 桌面宿主直接用 {@code PlaybackEngine} 一切正常，安卓这边套了一层就永远是
     * 「频谱不动、表头 -120、控制室写着『现在没有在过渡』」——
     * 用户看到的是「这一页是不是没做完」，而不是「哪根线断了」。
     *
     * <p>这四条一起检查，是因为它们的失效方式完全一样，将来再加一条同类实时数据
     * （比如新的表头读数）也应该顺手加进这里。
     */
    @Test
    public void theAndroidHostForwardsTheStudioLiveDataHooks() {
        String src = readBackend();
        assertTrue("studioSpectrum 必须转给引擎：`engine.studioSpectrum(dst)` 不见了"
                        + "（少了它，工作室页的实时频谱在安卓上永远是空的）",
                src.contains("engine.studioSpectrum(dst)"));
        assertTrue("studioMeters 必须转给引擎：`engine.studioMeters()` 不见了",
                src.contains("engine.studioMeters()"));
        assertTrue("studioGainReductionDb 必须转给引擎：`engine.studioGainReductionDb()` 不见了",
                src.contains("engine.studioGainReductionDb()"));
        assertTrue("transitionSnapshot 必须转给引擎：`engine.transitionSnapshot()` 不见了"
                        + "（控制室会永远显示「现在没有在过渡」）",
                src.contains("engine.transitionSnapshot()"));
        assertTrue("setStudioMonitoring 必须转给引擎：`engine.setStudioMonitoring(on)` 不见了"
                        + "（安卓端的表头与频谱永远不会被算——它们只在有人看的时候算）",
                src.contains("engine.setStudioMonitoring(on)"));
    }

    private static String readBackend() {
        File file = new File(repoRoot(), BACKEND);
        if (!file.isFile()) {
            fail("找不到 " + file);
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            fail("读不了 " + file + "：" + e);
            return "";
        }
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (new File(dir, "player-core").isDirectory()
                    && new File(dir, "android-shell").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        fail("找不到仓库根目录（从 " + System.getProperty("user.dir") + " 往上找过）");
        return null;
    }
}
