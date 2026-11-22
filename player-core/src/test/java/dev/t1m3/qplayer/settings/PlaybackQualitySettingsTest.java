package dev.t1m3.qplayer.settings;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PlaybackQualitySettingsTest {

    @Test
    public void playbackQualitySpecIsRegisteredWithFiveOptions() {
        SettingSpec spec = null;
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (SettingsCatalog.PLAYBACK_QUALITY_KEY.equals(s.key)) {
                spec = s;
                break;
            }
        }
        assertNotNull("playbackQuality setting must be present", spec);
        assertEquals(SettingSpec.DROPDOWN, spec.type);
        assertEquals(SettingsCatalog.PLAYBACK, spec.category);
        assertEquals(SettingsCatalog.QUALITY_EXHIGH, spec.def);
        assertNotNull(spec.options);
        assertEquals(5, spec.options.size());
        assertEquals("menu.download.standard", spec.options.get(0));
        assertEquals("menu.download.higher", spec.options.get(1));
        assertEquals("menu.download.exhigh", spec.options.get(2));
        assertEquals("menu.download.lossless", spec.options.get(3));
        assertEquals("menu.download.hires", spec.options.get(4));
    }

    @Test
    public void qualityConversionsAreConsistent() {
        assertEquals("standard", SettingsCatalog.qualityNameOf(SettingsCatalog.QUALITY_STANDARD));
        assertEquals("higher", SettingsCatalog.qualityNameOf(SettingsCatalog.QUALITY_HIGHER));
        assertEquals("exhigh", SettingsCatalog.qualityNameOf(SettingsCatalog.QUALITY_EXHIGH));
        assertEquals("lossless", SettingsCatalog.qualityNameOf(SettingsCatalog.QUALITY_LOSSLESS));
        assertEquals("hires", SettingsCatalog.qualityNameOf(SettingsCatalog.QUALITY_HIRES));
        assertEquals("exhigh", SettingsCatalog.qualityNameOf(999));
        assertEquals("exhigh", SettingsCatalog.qualityNameOf(-1));

        assertEquals(SettingsCatalog.QUALITY_STANDARD, SettingsCatalog.qualityIndexOf("standard"));
        assertEquals(SettingsCatalog.QUALITY_HIGHER, SettingsCatalog.qualityIndexOf("higher"));
        assertEquals(SettingsCatalog.QUALITY_EXHIGH, SettingsCatalog.qualityIndexOf("exhigh"));
        assertEquals(SettingsCatalog.QUALITY_LOSSLESS, SettingsCatalog.qualityIndexOf("lossless"));
        assertEquals(SettingsCatalog.QUALITY_HIRES, SettingsCatalog.qualityIndexOf("hires"));
        assertEquals(SettingsCatalog.QUALITY_LOSSLESS, SettingsCatalog.qualityIndexOf("LOSSLESS"));
        assertEquals(SettingsCatalog.QUALITY_EXHIGH, SettingsCatalog.qualityIndexOf("unknown"));
        assertEquals(SettingsCatalog.QUALITY_EXHIGH, SettingsCatalog.qualityIndexOf(null));
    }

    @Test
    public void legacyHighQualityMigration() throws Exception {
        FakeSettingsStore store = new FakeSettingsStore();
        store.putBool("highQuality", false);

        SettingsCore core = new SettingsCore();
        java.lang.reflect.Field storeField = SettingsCore.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(core, store);

        java.lang.reflect.Method migrate = SettingsCore.class.getDeclaredMethod("migrateLegacyKeys");
        migrate.setAccessible(true);
        migrate.invoke(core);

        assertEquals(SettingsCatalog.QUALITY_STANDARD, store.getInt(SettingsCatalog.PLAYBACK_QUALITY_KEY, -1));

        FakeSettingsStore storeHq = new FakeSettingsStore();
        storeHq.putBool("highQuality", true);
        storeField.set(core, storeHq);
        migrate.invoke(core);
        assertEquals(SettingsCatalog.QUALITY_EXHIGH, storeHq.getInt(SettingsCatalog.PLAYBACK_QUALITY_KEY, -1));
    }

    @Test
    public void playerControllerPlaybackQualitySwitchAndSync() throws Exception {
        dev.t1m3.qplayer.bridge.PlayerController controller = new dev.t1m3.qplayer.bridge.PlayerController(
                new SimpleAudioBackend(), track -> { }, dev.t1m3.qplayer.netease.NeteaseClient.INSTANCE);

        FakeSettingsStore store = new FakeSettingsStore();
        SettingsCore core = new SettingsCore();
        core.attach(controller);
        core.load(store, SettingsCatalog.ANDROID);

        // Initial default is exhigh
        assertEquals("exhigh", controller.playbackQuality.peek());

        // Change from controller updates property and syncs to settings store
        controller.setPlaybackQuality("lossless");
        assertEquals("lossless", controller.playbackQuality.peek());
        assertEquals(SettingsCatalog.QUALITY_LOSSLESS, store.getInt(SettingsCatalog.PLAYBACK_QUALITY_KEY, -1));

        // Change from settings index updates controller
        controller.setPlaybackQualityIndex(SettingsCatalog.QUALITY_HIRES);
        assertEquals("hires", controller.playbackQuality.peek());
        assertEquals(SettingsCatalog.QUALITY_HIRES, store.getInt(SettingsCatalog.PLAYBACK_QUALITY_KEY, -1));

        // Invalid quality falls back to exhigh
        controller.setPlaybackQuality("invalid_quality_token");
        assertEquals("exhigh", controller.playbackQuality.peek());
    }

    private static class SimpleAudioBackend implements dev.t1m3.qplayer.audio.AudioBackend {
        private boolean playing;
        private long pos;
        private float vol = 1.0f;

        @Override public void play(String source, long startMs) { playing = true; pos = startMs; }
        @Override public void pause() { playing = false; }
        @Override public void resume() { playing = true; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void seek(long ms) { pos = ms; }
        @Override public long position() { return pos; }
        @Override public long duration() { return 180_000L; }
        @Override public void setVolume(float volume) { vol = volume; }
        @Override public void setOnComplete(Runnable callback) { }
        @Override public void release() { playing = false; }
    }

    private static class FakeSettingsStore implements SettingsStore {
        private final Map<String, Object> map = new HashMap<>();

        @Override
        public boolean has(String key) {
            return map.containsKey(key);
        }

        @Override
        public boolean getBool(String key, boolean def) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public void putBool(String key, boolean value) {
            map.put(key, value);
        }

        @Override
        public int getInt(String key, int def) {
            Object v = map.get(key);
            return v instanceof Number ? ((Number) v).intValue() : def;
        }

        @Override
        public void putInt(String key, int value) {
            map.put(key, value);
        }

        @Override
        public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override
        public void putString(String key, String value) {
            map.put(key, value);
        }
    }
}
