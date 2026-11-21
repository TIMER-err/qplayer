package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.MediaId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 音源歌单的拖拽排序（{@code playlistReorder}）在跨过插件边界之前的那几道闸门。
 *
 * <h3>为什么值得单独钉住</h3>
 *
 * <p>这个能力会把<strong>整张歌单的新顺序</strong>交给插件，由插件在服务端整表替换。
 * 于是校验一旦漏掉一项，代价不是「排序失败」，而是<strong>把别人的歌单改坏</strong>：
 * 一个服务端查不到的 id 会被静默丢掉，重复的 id 会让服务端自己去重决定留哪一份，
 * 而跨音源的 id 会被写进一个不认它的服务端。所以这几条都在本地拦掉、抛
 * {@link IllegalArgumentException}，一次网络请求都不发（{@code manager} 传 null 也能跑）。
 */
public final class PlaylistReorderTest {

    private static final PluginProviderService SERVICE = new PluginProviderService(null, null);

    private static MediaId playlist() {
        return MediaId.parse("netease:playlist:9");
    }

    private static MediaId song(String provider, String id) {
        return MediaId.parse(provider + ":song:" + id);
    }

    /** 传输名就是能力表的键：插件按它注册 handler，改名等于所有插件一起失效。 */
    @Test
    public void theCapabilityTravelsOnTheWireAsPlaylistReorder() {
        assertEquals("playlistReorder", ProviderCapability.PLAYLIST_REORDER.wireName());
        assertEquals(ProviderCapability.PLAYLIST_REORDER,
                ProviderCapability.fromWireName("playlistReorder"));
        // 和 playlistMutation 是两件事：一个改成员，一个改顺序，插件可以只会其中一个。
        assertNotEquals(ProviderCapability.PLAYLIST_REORDER, ProviderCapability.PLAYLIST_MUTATION);
    }

    @Test
    public void anEmptyOrderIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SERVICE.reorderPlaylist(playlist(), Collections.<MediaId>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> SERVICE.reorderPlaylist(playlist(), null));
    }

    @Test
    public void aSongFromAnotherProviderIsRejected() {
        List<MediaId> order = new ArrayList<>();
        order.add(song("netease", "1"));
        order.add(song("qq", "2"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SERVICE.reorderPlaylist(playlist(), order));
        assertTrue(error.getMessage(), error.getMessage().contains("provider mismatch"));
    }

    /** 重复 id 会让服务端自己的去重决定留下哪一份，静默丢掉用户还看得见的一行。 */
    @Test
    public void aRepeatedSongIsRejected() {
        List<MediaId> order = new ArrayList<>();
        order.add(song("netease", "1"));
        order.add(song("netease", "2"));
        order.add(song("netease", "1"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SERVICE.reorderPlaylist(playlist(), order));
        assertTrue(error.getMessage(), error.getMessage().contains("repeats a song"));
    }

    @Test
    public void anEntryThatIsNotASongIsRejected() {
        List<MediaId> order = new ArrayList<>();
        order.add(MediaId.parse("netease:album:7"));
        assertThrows(IllegalArgumentException.class,
                () -> SERVICE.reorderPlaylist(playlist(), order));
    }

    /** 整表替换意味着请求体里是完整歌单，上限挡的是「传了一个不是歌单的东西进来」。 */
    @Test
    public void anAbsurdlyLongOrderIsRejected() {
        List<MediaId> order = new ArrayList<>(10_001);
        for (int i = 0; i <= 10_000; i++) {
            order.add(song("netease", String.valueOf(i)));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SERVICE.reorderPlaylist(playlist(), order));
        assertTrue(error.getMessage(), error.getMessage().contains("too many songs"));
    }

    /** 一万首正好是上限：不该在 10_000 这一档就炸（发不发得出去是另一回事）。 */
    @Test
    public void tenThousandSongsAreStillAccepted() {
        List<MediaId> order = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            order.add(song("netease", String.valueOf(i)));
        }
        // manager 是 null，所以越过校验之后会 NPE —— 那恰好证明校验放行了。
        assertThrows(NullPointerException.class, () -> SERVICE.reorderPlaylist(playlist(), order));
    }
}
