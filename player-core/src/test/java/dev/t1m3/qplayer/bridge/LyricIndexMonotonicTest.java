package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.lyric.LyricLine;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 歌词行号不许被位置的**小幅抖动**带着往回退（手机端实测现象）。
 *
 * <p>用户原话：「特别是手机端歌曲歌词进度一直在超小进度波动，歌词逐词显示能看到
 * 有时候歌词卡了一下往回去了一丢丢」。行号是每 ~200ms 按当前位置重算的，
 * 位置抖几十毫秒就会让同一行里算出的行号跳回上一格，逐词扫光于是往回退。
 *
 * <p>判据：小幅回退（几十毫秒）行号不动；真的往回跳（拖动/重开，秒级）照实跟随。
 */
public class LyricIndexMonotonicTest {

    /** 一句歌词 = 一个音节（判据只关心起始时间那一格）。 */
    private static LyricLine line(long startMs, String text) {
        LyricLine l = new LyricLine();
        l.syllables.add(new dev.t1m3.qplayer.lyric.Syllable(text, startMs, 1_000));
        return l;
    }

    private static PlayerController controllerWith(List<LyricLine> lines) {
        AudioBackend backend = (AudioBackend) java.lang.reflect.Proxy.newProxyInstance(
                AudioBackend.class.getClassLoader(), new Class<?>[]{AudioBackend.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        PlayerController player = new PlayerController(backend, track -> { });
        player.lyrics.set(lines);
        return player;
    }

    private static void at(PlayerController player, long posMs) throws Exception {
        Method m = PlayerController.class.getDeclaredMethod("updateLyricIndex", long.class);
        m.setAccessible(true);
        m.invoke(player, Long.valueOf(posMs));
    }

    @Test
    public void aTinyBackwardJitterDoesNotMoveTheLineBack() throws Exception {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0, "第一句"), line(5_000, "第二句"), line(10_000, "第三句")));
        PlayerController player = controllerWith(lines);
        try {
            at(player, 10_400);
            assertEquals("先到第三句", 2, player.lyricIndex.peek().intValue());
            // 位置抖了 120ms（手机端常见量级）：行号必须不动
            at(player, 10_280);
            assertEquals("小幅回退不许把行号带回去", 2, player.lyricIndex.peek().intValue());
            at(player, 10_390);
            assertEquals("抖动之后继续往前走", 2, player.lyricIndex.peek().intValue());
        } finally {
            player.shutdown();
        }
    }

    @Test
    public void aRealSeekBackwardsStillFollows() throws Exception {
        List<LyricLine> lines = new ArrayList<>(Arrays.asList(
                line(0, "第一句"), line(5_000, "第二句"), line(10_000, "第三句")));
        PlayerController player = controllerWith(lines);
        try {
            at(player, 12_000);
            assertEquals(2, player.lyricIndex.peek().intValue());
            // 拖回 3 秒处：真的往回听了，行号必须跟着回到第一句
            at(player, 3_000);
            assertEquals("拖动进度条要照实跟随", 0, player.lyricIndex.peek().intValue());
            at(player, 6_000);
            assertEquals(1, player.lyricIndex.peek().intValue());
        } finally {
            player.shutdown();
        }
    }
}
