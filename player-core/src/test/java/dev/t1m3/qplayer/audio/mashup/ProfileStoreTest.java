package dev.t1m3.qplayer.audio.mashup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProfileStore} 的纪律：<b>键是内容、读永不抛、写是原子的</b>。
 */
public class ProfileStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesThenReadsTheSameProfile() throws IOException {
        ProfileStore store = new ProfileStore(tmp.newFolder("cache").toPath());
        TrackProfile profile = Profiles.rich();
        assertTrue(store.write(profile));
        assertTrue("缓存文件应当落在 store 的目录里", Files.isRegularFile(store.fileFor(profile.key)));

        ProfileStore fresh = new ProfileStore(store.dir());   // 模拟"进程重启"
        TrackProfile read = fresh.read(profile.key);
        assertNotNull(read);
        assertEquals(profile.bpm, read.bpm, 1e-4);
        assertEquals(profile.keyTonic, read.keyTonic);
        assertEquals(profile.bars(), read.bars());
    }

    @Test
    public void overwriteLeavesNoTemporaryFiles() throws IOException {
        Path dir = tmp.newFolder("cache").toPath();
        ProfileStore store = new ProfileStore(dir);
        assertTrue(store.write(Profiles.rich()));
        assertTrue(store.write(Profiles.rich()));
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            long count = files.count();
            assertEquals("只该有一个 .profile，不该留下临时文件", 1, count);
        }
    }

    @Test
    public void corruptOrForeignFilesReadAsNoCache() throws IOException {
        Path dir = tmp.newFolder("cache").toPath();
        ProfileStore store = new ProfileStore(dir);
        TrackProfile profile = Profiles.rich();
        assertTrue(store.write(profile));

        // 半截文件
        Files.write(store.fileFor(profile.key), "qplayer-mashup-profile 1\nkey x\n"
                .getBytes(StandardCharsets.UTF_8));
        assertNull("半截文件必须当没有缓存", store.read(profile.key));

        // 键对不上的档案（文件被别的内容覆盖）
        Path file = store.fileFor(profile.key);
        Files.write(file, TrackProfile.builder("someone-else").durationSecs(100).build()
                .toText().getBytes(StandardCharsets.UTF_8));
        assertNull("档案里的键与文件名对不上必须当没有缓存", store.read(profile.key));

        // 空文件
        Files.write(file, new byte[0]);
        assertNull(store.read(profile.key));

        // 不存在的键
        assertNull(store.read("nope"));
        assertNull(store.read(null));
    }

    @Test
    public void deleteRemovesOnlyThatEntry() throws IOException {
        ProfileStore store = new ProfileStore(tmp.newFolder("cache").toPath());
        TrackProfile a = Profiles.of("k-a", 120, 0, "major", 200, 0.2);
        TrackProfile b = Profiles.of("k-b", 120, 0, "major", 200, 0.2);
        store.write(a);
        store.write(b);
        assertTrue(store.delete(a.key));
        assertNull(store.read(a.key));
        assertNotNull(store.read(b.key));
        assertFalse("再删一次应返回 false", store.delete(a.key));
    }

    @Test
    public void keyIsTheContentNotThePath() throws IOException {
        Path dir = tmp.newFolder("files").toPath();
        Path wav = dir.resolve("a.wav");
        Files.write(wav, new byte[]{1, 2, 3, 4});
        String first = ProfileStore.keyOf(wav.toString());
        assertNotNull(first);

        // 换路径、内容相同 ⇒ 同一个键
        Path copy = dir.resolve("b.wav");
        Files.copy(wav, copy);
        assertEquals(first, ProfileStore.keyOf(copy.toString()));

        // 内容变了 ⇒ 键必须变（缓存失效）
        Files.write(wav, new byte[]{1, 2, 3, 5});
        assertNotEquals(first, ProfileStore.keyOf(wav.toString()));

        // 读不到 ⇒ null（调用方据此跳过缓存，而不是用一个假键）
        assertNull(ProfileStore.keyOf(dir.resolve("missing.wav").toString()));
        assertNull(ProfileStore.keyOf(null));
        assertNull(ProfileStore.keyOf(""));
    }

    @Test
    public void remoteUrlsDropTheQueryString() {
        String a = ProfileStore.keyOf("https://example.com/song.mp3?token=aaa&exp=1");
        String b = ProfileStore.keyOf("https://example.com/song.mp3?token=bbb&exp=2");
        assertEquals("token 过期不该让缓存每次都失效", a, b);
        assertTrue(a.startsWith("url:"));
    }
}
