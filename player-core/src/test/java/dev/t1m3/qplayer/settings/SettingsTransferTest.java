package dev.t1m3.qplayer.settings;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SettingsTransferTest {

    private static final class TestSettingsStore implements SettingsStore {
        private final Map<String, Object> map = new HashMap<>();

        @Override
        public boolean getBool(String key, boolean def) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override
        public int getInt(String key, int def) {
            Object v = map.get(key);
            return v instanceof Number ? ((Number) v).intValue() : def;
        }

        @Override
        public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : def;
        }

        @Override
        public boolean has(String key) {
            return map.containsKey(key);
        }

        @Override
        public void putBool(String key, boolean value) {
            map.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            map.put(key, value);
        }

        @Override
        public void putString(String key, String value) {
            map.put(key, value);
        }
    }

    @Test
    public void exportAndImportRoundTrip() {
        SettingsCore source = new SettingsCore();
        TestSettingsStore store1 = new TestSettingsStore();
        source.load(store1, SettingsCatalog.ANDROID);

        // Customize various settings across categories
        source.setValue("darkMode", 2);
        source.setValue("uiScale", 115);
        source.setValue("lyricFontSize", 32);
        source.setValue("lyricSpring", false);
        source.setValue("lyricScale", false);
        source.setValue("lyricLineSpacing", 230);
        source.setValue("overlayLyricFontSize", 22);
        source.setValue("highQuality", false);
        source.setAppVolume(65);
        source.setFontSelection("TestCustomFont");

        String json = SettingsTransfer.export(source, null, "2.3.0");
        assertNotNull(json);
        assertTrue(json.contains(SettingsTransfer.FORMAT));
        assertTrue(json.contains("\"darkMode\": 2"));
        assertTrue(json.contains("\"lyricFontSize\": 32"));
        assertTrue(json.contains("\"lyricSpring\": false"));
        assertTrue(json.contains("TestCustomFont"));
        assertTrue(json.contains("\"volume\": 65"));

        // Import into target settings
        SettingsCore target = new SettingsCore();
        TestSettingsStore store2 = new TestSettingsStore();
        target.load(store2, SettingsCatalog.ANDROID);

        boolean ok = SettingsTransfer.importInto(json, target, null);
        assertTrue(ok);

        assertEquals(2, target.intOf("darkMode"));
        assertEquals(115, target.intOf("uiScale"));
        assertEquals(32, target.intOf("lyricFontSize"));
        assertFalse(target.bool("lyricSpring"));
        assertFalse(target.bool("lyricScale"));
        assertEquals(230, target.intOf("lyricLineSpacing"));
        assertEquals(22, target.intOf("overlayLyricFontSize"));
        assertFalse(target.bool("highQuality"));
        assertEquals((Integer) 65, target.appVolume.peek());
        assertEquals("TestCustomFont", target.fontSelection());
    }

    @Test
    public void exportEncryptedAndImportRoundTrip() {
        SettingsCore source = new SettingsCore();
        TestSettingsStore store1 = new TestSettingsStore();
        source.load(store1, SettingsCatalog.ANDROID);

        source.setValue("darkMode", 1);
        source.setValue("uiScale", 105);
        source.setValue("lyricFontSize", 28);
        source.setFontSelection("EncryptedFontTest");

        // 1. Export encrypted
        String encJson = SettingsTransfer.exportEncrypted(source, null, "2.3.0");
        assertNotNull(encJson);
        assertTrue(encJson.contains(SettingsTransfer.ENCRYPTED_FORMAT));
        assertTrue(encJson.contains("\"cipher\": \"AES-256-GCM\""));
        // Ensure sensitive config plaintext is NOT readable directly in encrypted file
        assertFalse(encJson.contains("EncryptedFontTest"));
        assertFalse(encJson.contains("\"darkMode\": 1"));

        // 2. Import encrypted document
        SettingsCore target = new SettingsCore();
        TestSettingsStore store2 = new TestSettingsStore();
        target.load(store2, SettingsCatalog.ANDROID);

        boolean ok = SettingsTransfer.importInto(encJson, target, null);
        assertTrue(ok);

        assertEquals(1, target.intOf("darkMode"));
        assertEquals(105, target.intOf("uiScale"));
        assertEquals(28, target.intOf("lyricFontSize"));
        assertEquals("EncryptedFontTest", target.fontSelection());
    }

    @Test
    public void exportEncryptedWithCustomPassphrase() {
        SettingsCore source = new SettingsCore();
        TestSettingsStore store1 = new TestSettingsStore();
        source.load(store1, SettingsCatalog.ANDROID);
        source.setValue("uiScale", 120);

        char[] passphrase = "MySecretCustomPassword123!".toCharArray();
        String encJson = SettingsTransfer.exportEncrypted(source, null, "2.3.0", passphrase);
        assertNotNull(encJson);

        SettingsCore target = new SettingsCore();
        TestSettingsStore store2 = new TestSettingsStore();
        target.load(store2, SettingsCatalog.ANDROID);

        // Wrong password should fail safely
        boolean wrongPassOk = SettingsTransfer.importInto(encJson, target, null, "WrongPass".toCharArray());
        assertFalse(wrongPassOk);

        // Correct password should succeed
        boolean ok = SettingsTransfer.importInto(encJson, target, null, passphrase);
        assertTrue(ok);
        assertEquals(120, target.intOf("uiScale"));
    }

    @Test
    public void loginAndCredentialsNeverExportedOrImported() {
        // Test sensitive key filter
        assertTrue(SettingsTransfer.isSensitiveKey("cookie"));
        assertTrue(SettingsTransfer.isSensitiveKey("netease_token"));
        assertTrue(SettingsTransfer.isSensitiveKey("user_credential"));
        assertTrue(SettingsTransfer.isSensitiveKey("auth_session"));
        assertTrue(SettingsTransfer.isSensitiveKey("loginPassword"));
        assertFalse(SettingsTransfer.isSensitiveKey("fontFamily"));
        assertFalse(SettingsTransfer.isSensitiveKey("darkMode"));
        assertFalse(SettingsTransfer.isSensitiveKey("uiScale"));

        // Test that if someone puts fake login keys into a JSON, importInto ignores them completely
        String taintedJson = "{\n" +
                "  \"format\": \"qplayer.settings\",\n" +
                "  \"settings\": {\n" +
                "    \"darkMode\": 1,\n" +
                "    \"userCookie\": \"SECRET_COOKIE_DATA\",\n" +
                "    \"authToken\": \"SECRET_TOKEN\",\n" +
                "    \"credentialPass\": \"SECRET_PASSWORD\"\n" +
                "  }\n" +
                "}";

        SettingsCore target = new SettingsCore();
        TestSettingsStore store = new TestSettingsStore();
        target.load(store, SettingsCatalog.ANDROID);

        boolean ok = SettingsTransfer.importInto(taintedJson, target, null);
        assertTrue(ok);
        assertEquals(1, target.intOf("darkMode"));
        assertFalse(store.has("userCookie"));
        assertFalse(store.has("authToken"));
        assertFalse(store.has("credentialPass"));
    }

    @Test
    public void importInvalidJsonReturnsFalseWithoutThrowing() {
        SettingsCore settings = new SettingsCore();
        TestSettingsStore store = new TestSettingsStore();
        settings.load(store, SettingsCatalog.ANDROID);

        assertFalse(SettingsTransfer.importInto(null, settings, null));
        assertFalse(SettingsTransfer.importInto("", settings, null));
        assertFalse(SettingsTransfer.importInto("   ", settings, null));
        assertFalse(SettingsTransfer.importInto("{malformed json", settings, null));
        assertFalse(SettingsTransfer.importInto("[\"an\", \"array\"]", settings, null));
    }
}
