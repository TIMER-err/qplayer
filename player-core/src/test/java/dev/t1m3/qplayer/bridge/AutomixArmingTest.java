package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.netease.NeteaseClient;
import dev.t1m3.qplayer.store.AppDirs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「下一首预告」这条链在<b>控制器这一侧</b>的门控：什么时候会把「下一首」交给后端
 * （{@link AudioBackend#setNextSource}），什么时候如实放弃（{@code clearNextSource}）。
 *
 * <h2>为什么这几条必须钉住</h2>
 *
 * <p>自动混音的失败方式全是<strong>静默</strong>的：没有预告，引擎的 {@code Director}
 * 队列就是空的，于是既不做 {@code prepare} 也不进过渡，听感上只有「换歌时什么都没发生」。
 * 用户实测就是这么一回事——他的曲库全是在线流式播放的歌，而这一侧只预告
 * <strong>现在就已经拿到句柄</strong>的那一首（在线歌曲要先把音频落到本地缓存），
 * 于是每首歌的每一次换曲都落在「没有下一首」这一支上，日志里连一条痕迹都没有。
 *
 * <p>所以这里把四种情况逐条钉死：总开关关着 / 本地文件 / 在线且已在缓存里 /
 * 在线但还没落盘；再钉住「关掉开关会撤掉手上那条预告」（关了还在跑是最糟的一种）。
 *
 * <p>队列用反射直接填：恢复路径会丢掉「插件没装」的在线曲目（本测试没有插件宿主），
 * 而这里要测的是预告的判据，不是恢复的判据。
 */
public class AutomixArmingTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 把两次调用记下来的后端：这个测试只关心预告那两条命令。 */
    private static final class RecordingBackend implements AudioBackend {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        /** 控制器在构造时注册进来的那几个回调（过渡开始 / 曲终）。 */
        java.util.function.Consumer<String> transitionStart;
        Runnable onCompleteCallback;

        /** 引擎会报的「现在放的是哪一首」与「在不在放」（过渡接管时用得上）。 */
        volatile String current = "";
        volatile boolean playing;
        volatile long positionMs;

        @Override public void play(String source, long startMs) {
            events.add("play(" + source + ")");
            current = source;
            positionMs = startMs;
            playing = true;
        }
        @Override public void pause() { playing = false; }
        @Override public void resume() { playing = true; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { positionMs = ms; }
        @Override public long position() { return positionMs; }
        @Override public long duration() { return 0L; }
        @Override public void setVolume(float v) { }
        @Override public void setOnComplete(Runnable callback) { onCompleteCallback = callback; }
        @Override public void release() { }
        @Override public String currentSource() { return current; }

        /**
         * 控制器认下一次自动交接时发的确认（{@link AudioBackend#confirmHandoff}）。
         *
         * <p>它从前是一次 {@code play(进场曲, 0)}——引擎按句柄字符串逐字比对那枚令牌，
         * 同一首歌的直链与缓存文件对不上就真的从头重开一遍（用户实测：「过渡完成后
         * 歌曲进度被重置回 0 从头播放」）。现在换成本方法：引擎只认账，不重启。
         */
        @Override public void confirmHandoff(String source) {
            events.add("confirmHandoff(" + source + ")");
        }
        @Override public void setOnTransitionStart(java.util.function.Consumer<String> callback) {
            transitionStart = callback;
        }
        @Override public void setNextSource(String source, long startMs) {
            events.add("setNextSource(" + source + ")");
        }

        @Override public void setNextSource(String source, java.util.Map<String, String> headers,
                                            long startMs) {
            // 事件格式与三参重载保持一致（老断言都在看 events/armed()），请求头单独记下来
            armedHeaders = headers;
            setNextSource(source, startMs);
        }
        @Override public void clearNextSource() { events.add("clearNextSource()"); }

        /** 最近一次预告带过来的请求头（{@link #setNextSource(String, java.util.Map, long)}）。 */
        java.util.Map<String, String> armedHeaders;

        /** 引擎报「过渡已经开始」（进场曲在出声），此刻它已经在放这一首。 */
        void fireTransitionStart(String intro) {
            current = intro;
            playing = true;
            transitionStart.accept(intro);
        }

        /** 引擎报「曲终」（自动交接：进场曲已经上位）。 */
        void fireComplete() {
            onCompleteCallback.run();
        }

        /** 有哪几条 setNextSource（句柄）；一条都没有时是空表。 */
        List<String> armed() {
            List<String> out = new ArrayList<>();
            for (String e : events) {
                if (e.startsWith("setNextSource(")) {
                    out.add(e.substring("setNextSource(".length(), e.length() - 1));
                }
            }
            return out;
        }

        void clear() { events.clear(); }
    }

    // 用户那次实际队列的头几首（state/queue.json）：全部是流式播放的插件曲目。
    private static final String[] PLUGIN_MEDIA_IDS = {
            "netease:song:4172700", "netease:song:3396667536", "netease:song:25657526"};

    // ==================================================================
    // 总开关
    // ==================================================================

    /** <b>默认关</b>：没人推过设置时，控制器这边的开关就是关的。 */
    @Test
    public void theMasterSwitchDefaultsOff() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            assertFalse("控制器的默认值必须与设置里那个键的默认值一致（关）",
                    controller.isAutomixEnabled());
            play(controller, 0);
            // 关着也照样预告：预告 ≠ 过渡。引擎那边窗口是 0，这份预告只用来无缝换曲
            // （见下面 withTheSwitchOffTheHandoverIsStillArmed 的详细理由）。
            assertEquals("关着也要把下一首交出去，否则曲终只能由控制器重开一次播放",
                    Collections.singletonList("C:/music/local1.flac"), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    /** 打开之后，本地队列的「下一首」立刻被预告出去（这是自动混音唯一的前提）。 */
    @Test
    public void withTheSwitchOnALocalQueueArmsItsNextTrack() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();

            play(controller, 0);
            assertEquals("本地文件的句柄就是路径，必须原样预告给后端",
                    Collections.singletonList("C:/music/local1.flac"), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    /**
     * <b>总开关关着时照样预告下一首</b>——只是这份预告不再用于过渡。
     *
     * <p>理由：预告 ≠ 过渡。引擎那边「关」= 窗口 0（不 {@code prepare}、不交叠），
     * 而这份预告仍然让曲终在<strong>同一路输出流里</strong>换曲（无缝，见 ADR-003 §2）。
     * 反过来「关掉就不预告」会把无缝换曲一起关掉：曲终引擎只能结束会话、
     * 由控制器重开一次播放，接缝上多出一小段空隙——那是另一个功能被顺手拿走了。
     */
    @Test
    public void withTheSwitchOffTheHandoverIsStillArmed() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            assertFalse(controller.isAutomixEnabled());
            backend.clear();

            play(controller, 0);
            assertEquals("关着也要预告（只为无缝换曲）：" + backend.events,
                    Collections.singletonList("C:/music/local1.flac"), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 拨开关会<strong>立刻重算一次预告</strong>：引擎据此发现「开关和这份编排器建的时候
     * 不一样」并重建编排器，于是新的窗口（关 = 0）立即生效。
     *
     * <p>钉住它是因为「拨了开关、当前这一曲结尾照样交叠」正是最难发现的那种不一致。
     */
    @Test
    public void togglingTheSwitchReArmsSoTheEngineRebuildsWithTheNewWindow() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();
            play(controller, 0);
            assertFalse("先确认这一轮确实预告了", backend.armed().isEmpty());

            backend.clear();
            controller.setAutomixEnabled(false);
            assertEquals("关掉开关必须再发一条预告（引擎才会重建编排器、窗口归 0）",
                    Collections.singletonList("C:/music/local1.flac"), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    // ==================================================================
    // 在线曲目：句柄就是「音频已经在本地缓存里」
    // ==================================================================

    /**
     * 在线（插件）曲目：既没落盘、也<strong>没有一条新鲜的直链</strong>时，如实放弃。
     *
     * <p>这正是用户实测的情形：曲目是流式播放的，直链是点歌那一刻才异步解析出来的，
     * 「下一首」那一侧还没有解析过、缓存里也没有它 → 预告拿不到句柄 → 没有过渡。
     */
    @Test
    public void aStreamingNextTrackIsNotArmedWhileItsAudioIsMissing() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, pluginTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();

            arm(controller);
            assertTrue("下一首还没落盘时不该编一个打不开的句柄出来：" + backend.events,
                    backend.armed().isEmpty());
            assertTrue("要如实说「没有下一首」：" + backend.events,
                    backend.events.contains("clearNextSource()"));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 插件曲目<strong>手上有一条新鲜的直链</strong>时，也要用它（与网易云源同一套取法）。
     *
     * <p>这条以前完全缺：{@code automixSourceOf} 的 PLUGIN 分支只看本地缓存，
     * 明明同一份文件里就有 {@code hasFreshPluginStream} 没用上。用户的整个曲库都是
     * 插件源，于是「下一首」永远是 null——这是实测出来的根因之一。
     */
    @Test
    public void aFreshPluginStreamUrlIsArmedWhenThereIsNoCachedAudio() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/next.mp3";
        tracks.get(1).streamHeaders.clear();
        tracks.get(1).streamExpiresAtMs = 0L;
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();

            arm(controller);
            assertEquals("有新鲜直链就该预告它：",
                    Collections.singletonList("http://127.0.0.1/next.mp3"), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    /** 直链**过期**了就不许再递下去（引擎拿去开只会白撞一次）。 */
    @Test
    public void anExpiredPluginStreamUrlIsNotArmed() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/old.mp3";
        tracks.get(1).streamExpiresAtMs = System.currentTimeMillis() - 1_000L;
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();

            arm(controller);
            assertTrue("过期直链不该被预告：" + backend.events, backend.armed().isEmpty());
            assertTrue(backend.events.contains("clearNextSource()"));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 插件直链<strong>需要请求头</strong>时<strong>照样预告，并把请求头一起交出去</strong>。
     *
     * <p>2026-09-24 之前这里是「如实放弃」：混音层没有 headers 通路，递下去就是让引擎
     * 去开一个一定打不开的 URL。现在那条通路接通了
     * （{@code setNextSource(source, headers, startMs)} → {@code Director.HeadersLookup}
     * → 分析与开流），所以放开：自动点位/对拍这类「要先读整首」的能力对插件源
     * 才不是永远没效果。
     */
    @Test
    public void aPluginStreamThatNeedsHeadersIsArmedWithThem() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/needs-headers.mp3";
        tracks.get(1).streamHeaders.put("Referer", "http://example.invalid/");
        tracks.get(1).streamExpiresAtMs = 0L;
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();

            arm(controller);
            assertEquals("要请求头的直链应当照常预告：" + backend.events,
                    java.util.Collections.singletonList("http://127.0.0.1/needs-headers.mp3"),
                    backend.armed());
            assertNotNull("请求头必须一起交出去（否则引擎开不了这条直链）",
                    backend.armedHeaders);
            assertEquals("http://example.invalid/",
                    backend.armedHeaders.get("Referer"));
            assertFalse("不该再走「没有下一首」那条撤销路：" + backend.events,
                    backend.events.contains("clearNextSource()"));
        } finally {
            controller.shutdown();
        }
    }

    /** 缓存与直链都在时<strong>缓存优先</strong>：本地文件不会过期、也不需要请求头。 */
    @Test
    public void cachedAudioWinsOverAFreshStreamUrl() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/next.mp3";
        PlayerController controller = controller(backend, tracks, 0);
        try {
            String cached = controller.diskCache.audioPath(PLUGIN_MEDIA_IDS[1]);
            File file = new File(cached);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), new byte[]{1, 2, 3});
            controller.setAutomixEnabled(true);
            backend.clear();

            arm(controller);
            assertEquals("缓存优先", Collections.singletonList(cached), backend.armed());

            // 「同一首换句柄不重发」那条见 repeatOneArmsItselfAsSoonAsItsOwnHandleLands
            // （那条路才是句柄真的换掉的情形：先直链、音频落盘之后换成缓存文件）。
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 「下一首」是<strong>这一首自己</strong>（单曲循环）时，直链/缓存刚到手就要能预告出去。
     *
     * <p>顺带钉住一条以前被挡掉的组合：<b>队列里只有一首 + 单曲循环</b>。
     * 「单曲循环时预告的就是当前这一首，引擎据此把句尾无缝接回句首」这句注释一直写在
     * {@code armNextSourceForAutomix} 上，而那个 {@code queue.size() <= 1} 的提前返回
     * 恰好把这个最需要它的组合挡在门外。
     */
    @Test
    public void repeatOneArmsItselfAsSoonAsItsOwnHandleLands() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks().subList(0, 1);
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            controller.setPlayMode(2);          // 单曲循环：下一首就是这一首
            arm(controller);
            assertTrue("还没解析、还没落盘时预告不出来", backend.armed().isEmpty());

            // 直链解析成功（= resolveAndPlayPlugin / prefetchNextForAutomix 里那次重算）
            tracks.get(0).streamUrl = "http://127.0.0.1/self.mp3";
            tracks.get(0).streamHeaders.clear();
            tracks.get(0).streamExpiresAtMs = 0L;
            backend.clear();
            arm(controller);
            assertEquals("解析成功之后必须能预告出来",
                    Collections.singletonList("http://127.0.0.1/self.mp3"), backend.armed());

            // 音频落盘之后（= 缓存回调里那次重算）：缓存优先
            String cached = controller.diskCache.audioPath(PLUGIN_MEDIA_IDS[0]);
            File file = new File(cached);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), new byte[]{1, 2, 3});
            backend.clear();
            arm(controller);
            assertTrue("落盘之后的句柄升级不重发（否则把已经备好的过渡作废）："
                            + backend.events, backend.armed().isEmpty());
        } finally {
            controller.shutdown();
        }
    }

    /**
     * <strong>总开关关着时，句柄升级照旧发出去</strong>——「缓存那份不会过期」这条性质
     * 真正起作用的就是这条路：没有过渡要准备，而引擎会在<strong>几分钟之后</strong>、
     * 当前曲放完时才去打开队首；那时候一条带时间戳的直链可能已经过期了。
     */
    @Test
    public void withAutomixOffTheCacheHandleUpgradeStillGoesOut() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/next.mp3";
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(false);
            backend.clear();
            arm(controller);
            assertEquals("关着开关也照样预告（曲终无缝换曲要用）：" + backend.events,
                    Collections.singletonList("http://127.0.0.1/next.mp3"), backend.armed());

            String cached = controller.diskCache.audioPath(PLUGIN_MEDIA_IDS[1]);
            File file = new File(cached);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), new byte[]{1, 2, 3});
            backend.clear();
            arm(controller);
            assertEquals("没有过渡要准备 → 该换成缓存那份（不会过期）",
                    Collections.singletonList(cached), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    /** 同一首在线曲目一旦落到本地缓存里，下一次预告就拿得到它了。 */
    @Test
    public void theSameStreamingTrackIsArmedOnceItsAudioLandsInTheCache() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, pluginTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            arm(controller);
            assertTrue(backend.armed().isEmpty());

            // 音频落到本地缓存（就是播放时那一次自动缓存干的事）
            String cached = controller.diskCache.audioPath(PLUGIN_MEDIA_IDS[1]);
            File file = new File(cached);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), new byte[]{1, 2, 3});

            backend.clear();
            arm(controller);
            assertEquals("落盘之后必须预告出来，否则引擎永远拿不到下一首",
                    Collections.singletonList(cached), backend.armed());
        } finally {
            controller.shutdown();
        }
    }

    // ==================================================================
    // 如实放弃的其它两种情形
    // ==================================================================

    /** 队列里只有一首：没有下一首可预告。 */
    @Test
    public void aSingleTrackQueueArmsNothing() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks().subList(0, 1), 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();

            arm(controller);
            assertTrue("只有一首时不该预告：" + backend.events, backend.armed().isEmpty());
            assertTrue(backend.events.contains("clearNextSource()"));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 随机播放：下一首由 RNG 决定，这里再摇一次必然是另一个下标——预告错了会让引擎
     * 把一首不该放的曲子接上来，比不做过渡更糟。
     */
    @Test
    public void shuffleArmsNothingBecauseTheNextTrackIsNotDecidedYet() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            controller.setPlayMode(1);
            backend.clear();

            arm(controller);
            assertTrue("随机播放时不该预告：" + backend.events, backend.armed().isEmpty());
            assertTrue(backend.events.contains("clearNextSource()"));
        } finally {
            controller.shutdown();
        }
    }

    // ==================================================================
    // 临时钉住（缓存淘汰时不许删）
    // ==================================================================

    /**
     * <b>正在听的那一首 + 已经预告出去的下一首都要被钉住。</b>
     *
     * <p>为什么：{@code DiskCache.evictIfNeeded} 对未受保护的音频是「最新优先」淘汰，
     * 而缓存满时「最新」就是刚写完的那一个——实测过：用户机器上每首歌起播约 7 秒落盘、
     * 随即被删，5 首一个都没留住。那样的话「正在听的那一首」会在播放中途消失，
     * 而引擎三十几秒后要去打开的「下一首」也必然打不开——有声的预告变成没声的。
     */
    @Test
    public void theCurrentTrackAndTheArmedNextArePinned() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/next.mp3";   // 下一首有新鲜直链 → 能预告
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            controller.setAutomixTransitionRequested(true);
            backend.clear();

            arm(controller);
            assertFalse("先确认这一轮确实预告了", backend.armed().isEmpty());
            assertTrue("正在听的那一首必须被钉住（不然它自己会被淘汰掉）",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[0]));
            assertTrue("已经预告出去的下一首必须被钉住（引擎稍后要去打开它）",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[1]));
            assertFalse("没被预告、也不在放的那一首不该占着保护名额",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[2]));
        } finally {
            controller.shutdown();
        }
    }

    /** 换了槽位之后：不再是「当前曲」也不是「下一首」的那一首，钉子要撤掉。 */
    @Test
    public void movingOnReleasesThePinOnTheTrackThatIsNoLongerNeeded() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/one.mp3";
        tracks.get(0).streamUrl = "http://127.0.0.1/two.mp3";
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            controller.setAutomixTransitionRequested(true);
            arm(controller);
            assertTrue(controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[1]));

            // 跳到第 3 槽（下标 2）：这里是「当前曲 = 2、下一首 = 0（绕回）」
            setPlayIndex(controller, 2);
            arm(controller);
            assertTrue("现在的当前曲要被钉住",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[2]));
            assertFalse("下标 1 既不是当前曲也不是下一首了，钉子必须撤掉",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[1]));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 删掉队列里那一首（它很可能正是已预告的「下一首」）之后要重算：
     * 撤掉旧钉子，并且不再把删掉的那首当作下一首。
     */
    @Test
    public void removingTheArmedNextRecomputesTheArmAndThePins() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        List<Track> tracks = pluginTracks();
        tracks.get(1).streamUrl = "http://127.0.0.1/next.mp3";
        tracks.get(2).streamUrl = "http://127.0.0.1/after.mp3";
        PlayerController controller = controller(backend, tracks, 0);
        try {
            controller.setAutomixEnabled(true);
            controller.setAutomixTransitionRequested(true);
            backend.clear();
            arm(controller);
            assertEquals(Collections.singletonList("http://127.0.0.1/next.mp3"), backend.armed());

            backend.clear();
            controller.removeFromQueue(1);          // 删掉的正是「下一首」
            assertEquals("删掉之后下一首变成下标 2 的那一首",
                    Collections.singletonList("http://127.0.0.1/after.mp3"), backend.armed());
            assertFalse("删掉的那一首不该再被钉着",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[1]));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 总开关关着时：当前曲照样钉住（与混音无关，正在听的歌不该在播放中途消失），
     * 但<b>不预取</b>——不会为一个关着的功能花用户的带宽。
     */
    @Test
    public void withTheSwitchOffTheCurrentTrackIsStillPinnedButNothingIsPrefetched() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, pluginTracks(), 0);
        try {
            assertFalse(controller.isAutomixEnabled());
            assertFalse("没打开开关时不该说「要做过渡」",
                    controller.isAutomixTransitionRequested());

            arm(controller);
            assertTrue("正在听的那一首与开关无关，照样要钉住",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[0]));
            assertFalse("没解析过也没缓存的下一首没有可钉的东西",
                    controller.diskCache.isAudioPinned(PLUGIN_MEDIA_IDS[1]));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 「这一次会不会真的做过渡」是<b>另一个</b>影子值（开关打开 <b>且</b> 接法不是无缝拼接），
     * 预取只看它：接法是无缝拼接时过渡不会发生，预取就是白花带宽。
     */
    @Test
    public void theTransitionRequestedFlagIsIndependentOfTheMasterSwitch() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, pluginTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            assertFalse("刚打开开关时控制器还不知道接法，默认不算「会做过渡」",
                    controller.isAutomixTransitionRequested());

            controller.setAutomixTransitionRequested(true);
            assertTrue(controller.isAutomixTransitionRequested());
            assertTrue("开关仍然开着", controller.isAutomixEnabled());
        } finally {
            controller.shutdown();
        }
    }

    // ==================================================================
    // 过渡**开始**那一刻就推进队列/界面（而不是等曲终）
    // ==================================================================

    /**
     * <b>过渡一开始（进场曲已经在出声）界面就必须切到进场曲。</b>
     *
     * <p>用户原话：「automix 切歌对应显示应该是过渡到 B 后 ui 直接跳转到下首歌了啊
     * 现在还是 A 完全进度走完了才跳跃」。引擎的自动交接（{@code onComplete}）要等
     * 整个过渡窗口走完——交叉淡化 6 s、低音交接 8 s、表现模式 16 s——等它，界面就会在
     * 那几秒里停在出场曲上。所以引擎在窗口一开就报一次（
     * {@link AudioBackend#setOnTransitionStart}），控制器据此在这里推进。
     *
     * <p>推进的内容必须与一次真正的换曲一致（下标/标题/时长/队列高亮），否则
     * 界面上会出现「标题是 B、时长还是 A 的」这种半截状态。
     */
    @Test
    public void theUiSwitchesToTheIncomingTrackWhenTheTransitionStarts() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            backend.clear();
            play(controller, 0);
            assertEquals("先确认这一轮真的预告了下一首",
                    Collections.singletonList("C:/music/local1.flac"), backend.armed());
            controller.pump();
            assertEquals("推进之前界面还在出场曲上", 0, controller.index.peek().intValue());
            assertEquals("local0", controller.title.peek());

            // 引擎报「过渡已经开始」：进场曲就是刚才预告出去的那一份
            backend.fireTransitionStart("C:/music/local1.flac");
            controller.pump();

            assertEquals("过渡一开始界面就必须切到进场曲（不许等曲终）",
                    1, controller.index.peek().intValue());
            assertEquals("local1", controller.title.peek());
            assertEquals("时长必须换成进场曲的（半截状态最容易被当成 bug）",
                    Long.valueOf(220_000L), controller.durationMs.peek());
            assertEquals("队列高亮的来源（index）与播放下标必须一致",
                    1, playIndexOf(controller));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * <b>推进恰好一次</b>：过渡开始时推进一次，曲终那次 {@code onComplete} 不许再推进一次
     * （那会从 B 直接跳到 C——比滞后更糟）。
     *
     * <p>判据是「引擎现在放的正是过渡时切过去的那一首」：对上了就说明这次曲终是那一次
     * 推进的交接，只补收尾（出场曲的 scrobble、认掉引擎的一次性令牌、重算下一首预告），
     * 队列不动。
     */
    @Test
    public void theQueueAdvancesExactlyOnceAcrossATransition() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            play(controller, 0);
            controller.pump();

            backend.fireTransitionStart("C:/music/local1.flac");
            controller.pump();
            assertEquals(1, controller.index.peek().intValue());

            backend.clear();
            backend.fireComplete();          // 曲终 = 自动交接完成（引擎已经在放进场曲）
            controller.pump();

            assertEquals("曲终不许再推进一次（那就跳到 local2 了）",
                    1, controller.index.peek().intValue());
            assertEquals("local1", controller.title.peek());
            assertTrue("确认交接要发出去（引擎那枚一次性令牌靠它消费，"
                            + "不然令牌一直挂着、用户下一次点这一首会被它吃掉）" + backend.events,
                    backend.events.contains("confirmHandoff(C:/music/local1.flac)"));
            assertFalse("**不许再发一次 play(同一首)**：那正是这条 bug 的来源——引擎按句柄"
                            + "字符串逐字比对令牌，同一首歌的直链与缓存文件对不上时就真的从头"
                            + "重开一遍（用户实测：「过渡完成后歌曲进度被重置回 0 从头播放」）"
                            + backend.events,
                    backend.events.contains("play(C:/music/local1.flac)"));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * <b>同一首歌的句柄升级（直链 → 缓存文件）之后，过渡开始那一刻界面照样要推进。</b>
     *
     * <p>这条钉的是一个真 bug 的两半（2026-09-26 手机端实测）：
     *
     * <ol>
     *   <li>「同一首歌换个句柄不算换了下一首」那条纪律（见
     *       {@code isHandleUpgradeForSameTrack}）会让第二次预告<strong>不再告诉引擎</strong>——
     *       于是引擎手上是<strong>直链</strong>、控制器手上是<strong>缓存路径</strong>；</li>
     *   <li>推进那一支从前拿字符串逐字比对这两者 → 对不上 → <strong>不推进界面</strong>，
     *       并且曲终那次 {@code onComplete} 被当成普通曲终 → 队列往前走、对着进场曲再发一次
     *       {@code play(0)} → <strong>进度被重置回 0 从头播放</strong>。</li>
     * </ol>
     *
     * <p>判据用「引擎报上来的那一份句柄」当输入：那是引擎真正打开的那一份，
     * 而控制器手上已经是升级之后那份。修正后按曲目身份认，所以照样要推进。
     */
    @Test
    public void anUpgradedHandleStillCountsAsTheArmedTrackWhenTheTransitionStarts() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            play(controller, 0);
            controller.pump();
            String armedHandle = backend.armed().get(0);
            assertNotNull("要先把下一首预告出去才谈得上句柄升级", armedHandle);

            // 句柄升级：控制器手上换成另一个句柄（引擎那边不变，走的是「同一首歌不重新预告」
            // 那一条）——两边就此不同，正是真机上那个局面。
            java.lang.reflect.Field handleField =
                    PlayerController.class.getDeclaredField("automixArmedHandle");
            handleField.setAccessible(true);
            handleField.set(controller, armedHandle + ".upgraded");
            backend.clear();

            // 引擎报「过渡已经开始」——句柄是它当初打开的那一份（升级之前那个）
            backend.fireTransitionStart(armedHandle);
            controller.pump();

            assertEquals("句柄升级之后过渡开始，界面**照样**要推进到进场曲"
                            + "（判据是曲目身份，不是句柄字符串）",
                    1, controller.index.peek().intValue());

            backend.clear();
            backend.fireComplete();
            controller.pump();

            assertEquals("曲终是这次推进的交接 → 队列不许再走一首",
                    1, controller.index.peek().intValue());
            assertTrue("交接要对账（确认命令）" + backend.events,
                    backend.events.contains("confirmHandoff(" + armedHandle + ")"));
            assertFalse("交接同样不许用 play(同一首) 来顶" + backend.events,
                    backend.events.contains("play(" + armedHandle + ")"));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 兜底一：<b>根本没有自动混音</b>（后端没有过渡、总开关关着）时，曲终照旧按老路推进队列。
     *
     * <p>这一条与下面的「交接对账」是一对：新机制只该在<strong>确实发生过过渡</strong>时
     * 改变行为，别的场合一律与改动前一致（不许把队列卡住）。
     */
    @Test
    public void aCompletionWithoutAnyAdoptionStillAdvancesTheQueueTheOldWay() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            play(controller, 0);
            controller.pump();
            assertEquals(0, controller.index.peek().intValue());

            // 没有任何 fireTransitionStart：这就是「后端不做过渡」的场合
            backend.current = "C:/music/local0.flac";
            backend.fireComplete();
            controller.pump();

            assertEquals("没有交接时曲终必须照旧推进", 1, controller.index.peek().intValue());
            assertEquals("local1", controller.title.peek());
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 兜底二：<b>用户中途自己选了别的曲子</b>时，那笔「过渡时推进过」的记录作废，
     * 曲终按老路推进——明确的用户意图压过推断。
     */
    @Test
    public void aCompletionAfterTheUserPickedAnotherTrackAdvancesTheOldWay() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            play(controller, 0);
            controller.pump();
            backend.fireTransitionStart("C:/music/local1.flac");
            controller.pump();
            assertEquals(1, controller.index.peek().intValue());

            // 用户自己点了第 2 首（队列里最后一首）：过渡那笔账作废
            play(controller, 2);
            controller.pump();
            assertEquals(2, controller.index.peek().intValue());

            backend.current = "C:/music/local2.flac";
            backend.fireComplete();
            controller.pump();

            assertFalse("用户选过曲之后不该再走「交接确认」那条路（否则队列会卡住）"
                            + backend.events,
                    backend.events.contains("confirmHandoff(C:/music/local2.flac)"));
        } finally {
            controller.shutdown();
        }
    }

    /**
     * 交接对账：<b>引擎报的正是过渡时切过去的那一首</b>时，这次曲终属于「交接」——
     * 只确认、<strong>不再推进一次</strong>。
     *
     * <p>为什么这一条必须有判据：这里曾经是 {@code play(handle, 0)}，
     * 而引擎那枚一次性令牌按句柄字符串逐字比——同一首歌的直链与缓存文件是两个字符串，
     * 对不上的那一次引擎真的从头重开一遍，用户听到的是
     * <strong>「过渡完成后歌曲进度被重置回 0 从头播放」</strong>（2026-09-26 真机实测）。
     * 现在的判据是「队列此刻指的正是这一首」+ 句柄别名，两者任一成立就算交接。
     */
    @Test
    public void aCompletionForTheAdoptedTrackConfirmsTheHandoffInsteadOfAdvancing() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PlayerController controller = controller(backend, localTracks(), 0);
        try {
            controller.setAutomixEnabled(true);
            play(controller, 0);
            controller.pump();
            backend.fireTransitionStart("C:/music/local1.flac");
            controller.pump();
            assertEquals(1, controller.index.peek().intValue());

            // 引擎报的正是进场曲（这里是它的**另一种句柄**：换成了缓存文件那种形态，
            // 句柄字符串对不上，但它是同一首）
            backend.current = "/tmp/does-not-matter/v2-local1.cache";
            backend.fireComplete();
            controller.pump();

            assertEquals("交接只确认、不再推进：队列必须停在进场曲上",
                    1, controller.index.peek().intValue());
            // 确认时带的是**引擎手上那一份句柄**（控制器当初预告出去的那个），
            // 因为引擎的一次性令牌就是照它armed 的；带引擎刚报的那一份反而对不上
            assertTrue("确认走的是 confirmHandoff（不是 play(同一首)）：" + backend.events,
                    backend.events.contains("confirmHandoff(C:/music/local1.flac)"));
            assertFalse("绝不许用 play(同一首) 顶交接（那会把进度重置回 0）" + backend.events,
                    backend.events.contains("play(C:/music/local1.flac)"));
        } finally {
            controller.shutdown();
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private PlayerController controller(RecordingBackend backend, List<Track> tracks, int index)
            throws Exception {
        Path base = temporaryFolder.newFolder("automix-arm-" + System.nanoTime()).toPath();
        AppDirs.setBase(base.toString());
        AppDirs.setCacheBase(base.resolve("cache").toString());
        PlayerController controller = new PlayerController(backend, track -> { },
                NeteaseClient.INSTANCE);
        java.lang.reflect.Field queueField = PlayerController.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Track> queue = (List<Track>) queueField.get(controller);
        queue.clear();
        queue.addAll(tracks);
        controller.queueTracks.set(new ArrayList<>(tracks));
        java.lang.reflect.Field indexField = PlayerController.class.getDeclaredField("playIndex");
        indexField.setAccessible(true);
        indexField.setInt(controller, index);
        return controller;
    }

    /** 走真实的 {@code playAt}（它末尾就是那次预告）。 */
    private static void play(PlayerController controller, int index) throws Exception {
        java.lang.reflect.Method m = PlayerController.class.getDeclaredMethod("playAt", int.class);
        m.setAccessible(true);
        m.invoke(controller, index);
    }

    /** 只重算一次预告（队列/开关变化时走的就是这一条）。 */
    private static void arm(PlayerController controller) throws Exception {
        java.lang.reflect.Method m = PlayerController.class.getDeclaredMethod("armNextSourceForAutomix");
        m.setAccessible(true);
        m.invoke(controller);
    }

    /** 改当前槽位（不走播放路径：本测试环境的插件宿主是空的，playAt 会把歌全跳过）。 */
    private static void setPlayIndex(PlayerController controller, int index) throws Exception {
        java.lang.reflect.Field f = PlayerController.class.getDeclaredField("playIndex");
        f.setAccessible(true);
        f.setInt(controller, index);
    }

    /** 读控制器的播放下标（界面 index 之外的内部那一份，用来核对两者一致）。 */
    private static int playIndexOf(PlayerController controller) throws Exception {
        java.lang.reflect.Field f = PlayerController.class.getDeclaredField("playIndex");
        f.setAccessible(true);
        return f.getInt(controller);
    }

    /**
     * <strong>同一首歌换了句柄，不许重新预告</strong>——那会把引擎已经备好的过渡作废。
     *
     * <p>用户实测（2026-09-25）：「生成式过渡准备好了，但是没过渡，A 完全走完走的 B」。
     * 日志里能直接看到那两次预告：20:59:40 直链 → 20:59:46 本地缓存文件（同一首）。
     * 在线源的句柄本来就是分两步到位的，而引擎那边「换队首」= 整份准备作废；
     * 生成式一次准备 70 秒，够它赶不上触发点。
     *
     * <p>这里只测判据本身（盘上的那两条路径是插件宿主的事，本测试环境的插件宿主是空的，
     * 走不到真实预告那一步）；判据是「曲目的稳定身份」，不是句柄字符串。
     */
    @Test
    public void aHandleUpgradeForTheSameTrackMustNotReannounce() {
        List<Track> tracks = pluginTracks();
        Track song = tracks.get(0);
        String url = "https://m801.music.126.net/20260925212439/abc/jdymusic/song.flac";
        String cache = "/home/u/.qplayer/cache/audio/v2-cdb3f3bd91cd0f8744.cache";
        assertTrue("同一首的直链换缓存文件：不算换歌",
                PlayerController.isHandleUpgradeForSameTrack(song, url, cache, song));
        assertTrue("反过来也一样（缓存 → 直链，比如缓存被清掉了）",
                PlayerController.isHandleUpgradeForSameTrack(song, cache, url, song));
        assertFalse("换了一首就必须重新预告",
                PlayerController.isHandleUpgradeForSameTrack(song, url, cache, tracks.get(1)));
        assertFalse("句柄没变也不算「升级」（那一次本来就不会预告）",
                PlayerController.isHandleUpgradeForSameTrack(song, url, url, song));
        assertFalse("还没预告过（句柄空）：必须发出去",
                PlayerController.isHandleUpgradeForSameTrack(null, "", cache, song));
        // 身份取不到（比如本地文件还没有 id）时**保守派**：照旧重新预告，
        // 宁可多作废一次，也不能把两首歌当成一首。
        Track noId = new Track();
        noId.source = Track.Source.LOCAL;
        noId.title = "no-id";
        assertFalse("身份取不到时不许当成同一首",
                PlayerController.isHandleUpgradeForSameTrack(noId, url, cache, noId));
    }

    private static List<Track> pluginTracks() {
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < PLUGIN_MEDIA_IDS.length; i++) {
            Track t = new Track();
            t.source = Track.Source.PLUGIN;
            t.mediaId = PLUGIN_MEDIA_IDS[i];
            t.title = "stream" + i;
            t.durationMs = 220_000L;
            out.add(t);
        }
        return out;
    }

    private static List<Track> localTracks() {
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Track t = new Track();
            t.source = Track.Source.LOCAL;
            t.title = "local" + i;
            t.filePath = "C:/music/local" + i + ".flac";
            t.durationMs = 220_000L;
            out.add(t);
        }
        return out;
    }
}
