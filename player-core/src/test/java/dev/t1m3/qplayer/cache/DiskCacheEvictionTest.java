package dev.t1m3.qplayer.cache;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 缓存淘汰的<strong>两条</strong>规则，以及「临时钉住」为什么必须存在。
 *
 * <h2>背景（不是猜的，是实测出来的）</h2>
 *
 * <p>未受保护的音频是<strong>最新优先</strong>淘汰的（见 {@code DiskCache.evictIfNeeded}
 * 的注释：连着跳过几十首留下的缓存没什么价值）。这条规则的直接后果是：
 * <strong>刚刚下载完的那个文件会被它自己挤掉</strong>。
 *
 * <p>2026-09-19 在用户那台机器上实测：缓存卡在 200 MB 上限，7 首在线歌每首都在起播后
 * 约 7 秒自动落盘、随即被淘汰，日志里 5 条 {@code disk cache evicted} 的哈希与刚播的
 * 那 5 首一一对应，缓存里一首都没留下。对自动混音更要命：预告给引擎的「下一首」如果
 * 是个转眼就被删掉的路径，引擎在三十几秒后打开它必然失败，<strong>有声的预告会变成
 * 没声的</strong>。
 *
 * <p>所以这里把两件事都钉死：①上面那条「最新优先」的规则本身（免得哪天被无声改掉，
 * 而依赖它的推理还留在注释里）；②{@link DiskCache#pinAudio} 能让「这一刻需要的文件」
 * 活下来，并且<strong>不会</strong>变成用户的永久意愿。
 */
public class DiskCacheEvictionTest {

    /** 每个文件 400 KB，上限 1 MB —— 三个文件必然超额。 */
    private static final int FILE_BYTES = 400 * 1024;

    private DiskCache cache(String label) throws Exception {
        Path root = Files.createTempDirectory("qplayer-cache-evict-" + label + "-");
        DiskCache cache = new DiskCache(1);
        cache.setBaseDir(root.toString());
        return cache;
    }

    /** 写一个音频缓存文件，并把它的修改时间钉到 {@code secondsAgo} 秒前。 */
    private static void write(DiskCache cache, String mediaId, long secondsAgo) throws Exception {
        Path file = Paths.get(cache.audioPath(mediaId));
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[FILE_BYTES]);
        assertTrue("改不动修改时间就没法构造「谁更新」这个前提",
                file.toFile().setLastModified(System.currentTimeMillis() - secondsAgo * 1000L));
    }

    private static boolean exists(DiskCache cache, String mediaId) {
        return new File(cache.audioPath(mediaId)).isFile();
    }

    /**
     * 规则本身：超额时，未受保护的音频<strong>新写的那个先被删</strong>。
     *
     * <p>这条断言就是那个实测现象的机制。它读起来很刺眼——但它记的是<strong>当前的
     * 事实</strong>，不是我希望的行为；把事实写成断言，比写成注释诚实。
     */
    @Test
    public void unprotectedAudioIsEvictedNewestFirst() throws Exception {
        DiskCache cache = cache("rule");
        write(cache, "netease:song:1", 3000);
        write(cache, "netease:song:2", 2000);
        // 第三个写下去，总量越过 1 MB —— 被删的正是它自己。
        write(cache, "netease:song:3", 1000);
        cache.finishExternalWrite();

        assertFalse("最新写下去的那一个必须是被淘汰的那一个（这就是「刚缓存完就没了」）",
                exists(cache, "netease:song:3"));
        assertTrue("更早的两个要留着", exists(cache, "netease:song:1"));
        assertTrue(exists(cache, "netease:song:2"));
    }

    /**
     * 临时钉住：即使它是<strong>最新</strong>的，也不会被删——被删的是下一个最新的。
     *
     * <p>这正是自动混音「下一首」能活到过渡那一刻的前提。
     */
    @Test
    public void pinnedAudioSurvivesEvenWhenItIsTheNewest() throws Exception {
        DiskCache cache = cache("pin");
        write(cache, "netease:song:1", 3000);
        write(cache, "netease:song:2", 2000);

        cache.pinAudio("netease:song:3");
        assertTrue(cache.isAudioPinned("netease:song:3"));
        write(cache, "netease:song:3", 1000);
        cache.finishExternalWrite();

        assertTrue("钉住的「下一首」必须活下来，否则预告到的是一个不存在的路径",
                exists(cache, "netease:song:3"));
        assertFalse("该腾地方的是下一个最新的（song:2）", exists(cache, "netease:song:2"));
        assertTrue("最老的照旧不动", exists(cache, "netease:song:1"));
    }

    /** 撤掉钉住之后，它就只是一个普通文件了：该被淘汰时照样被淘汰。 */
    @Test
    public void unpinnedAudioBecomesEvictableAgain() throws Exception {
        DiskCache cache = cache("unpin");
        write(cache, "netease:song:1", 6000);

        cache.pinAudio("netease:song:2");
        write(cache, "netease:song:2", 5000);
        cache.finishExternalWrite();          // 800 KB，还没超额
        assertTrue(exists(cache, "netease:song:2"));

        cache.unpinAudio("netease:song:2");
        assertFalse(cache.isAudioPinned("netease:song:2"));

        // 再写一个更老的：总量超额，而此刻最新的未受保护文件是 song:2。
        write(cache, "netease:song:3", 7000);
        cache.finishExternalWrite();

        assertFalse("撤了钉住之后它就该被淘汰了", exists(cache, "netease:song:2"));
        assertTrue(exists(cache, "netease:song:3"));
    }

    /**
     * 钉住<strong>不是</strong>用户的意愿：不许写进 {@code actively-cached.txt}。
     *
     * <p>写进去的话，「缓存此歌曲」这份用户意图就被自动混音的临时需要污染了——
     * 那个文件里的每一条都意味着「永远别删」，而自动混音每换一首都会钉一首。
     */
    @Test
    public void pinningIsNotPersistedAsUserIntent() throws Exception {
        DiskCache cache = cache("nopersist");
        String base = cache.baseDir();
        File marker = new File(base, "actively-cached.txt");

        cache.pinAudio("netease:song:7");
        cache.unpinAudio("netease:song:7");

        assertFalse("临时钉住绝不能落进 actively-cached.txt", marker.isFile());
    }
}
