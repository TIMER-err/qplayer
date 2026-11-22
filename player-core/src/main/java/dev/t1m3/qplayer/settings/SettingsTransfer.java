package dev.t1m3.qplayer.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.util.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Portable exchange format for QPlayer personalized configuration.
 * Serializes all catalog settings, custom typography, audio gain,
 * and track-specific lyric offsets into a versioned JSON document,
 * with optional AES-256-GCM encryption and strict exclusion of any
 * account credentials, cookies, tokens, or login data.
 */
public final class SettingsTransfer {
    public static final String FORMAT = "qplayer.settings";
    public static final String ENCRYPTED_FORMAT = "qplayer.settings.encrypted";
    public static final int FORMAT_VERSION = 1;
    public static final String FILE_SUFFIX = ".json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Sensitive keyword blacklist: Any key matching these is strictly forbidden from
    // being exported or imported, guaranteeing no login tokens, credentials, or cookies
    // can ever be leaked into configuration files.
    private static final Set<String> SENSITIVE_KEYWORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "cookie", "token", "credential", "auth", "login", "password", "secret", "session", "user"
            )));

    // Internal passphrase & KDF parameters for default AES-256-GCM encrypted export
    private static final char[] INTERNAL_PASSPHRASE =
            "QPlayer!Config@Storage#2026$Encrypted%Settings^Secure".toCharArray();
    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final class Document {
        public String format = FORMAT;
        public int formatVersion = FORMAT_VERSION;
        public String appVersion = "";
        public long exportedAtMs;
        public Map<String, Object> settings = new LinkedHashMap<>();
        public Map<String, Integer> lyricOffsets = new LinkedHashMap<>();
    }

    public static final class EncryptedDocument {
        public String format = ENCRYPTED_FORMAT;
        public int formatVersion = FORMAT_VERSION;
        public String appVersion = "";
        public long exportedAtMs;
        public String cipher = "AES-256-GCM";
        public String salt;
        public String iv;
        public int tagBits = GCM_TAG_BITS;
        public String data;
    }

    private SettingsTransfer() {}

    /**
     * Checks whether a setting key could be related to authentication or credentials.
     * Guarantees zero sensitive data leakage into configuration files.
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String kw : SENSITIVE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Export all current personalized settings into a formatted plain JSON string.
     * Guaranteed never to include login credentials or session tokens.
     */
    public static String export(SettingsCore settings, PlayerController controller, String appVersion) {
        Document doc = new Document();
        doc.format = FORMAT;
        doc.formatVersion = FORMAT_VERSION;
        doc.appVersion = appVersion != null ? appVersion : "";
        doc.exportedAtMs = System.currentTimeMillis();

        if (settings != null) {
            // 1. All declared settings specs across all categories
            for (SettingSpec spec : SettingsCatalog.specs()) {
                if (!spec.hasValue()) continue;
                if (isSensitiveKey(spec.key)) continue;
                Object val = settings.raw(spec.key);
                if (val != null) {
                    doc.settings.put(spec.key, val);
                }
            }

            // 2. Custom font selections (stored outside catalog specs)
            String font = settings.fontSelection();
            if (font != null && !font.isEmpty()) {
                doc.settings.put(SettingsCore.FONT_KEY, font);
            }
            String desktopFont = settings.desktopLyricFontSelection();
            if (desktopFont != null && !desktopFont.isEmpty()) {
                doc.settings.put(SettingsCore.DESKTOP_LYRIC_FONT_KEY, desktopFont);
            }

            // 3. App volume
            if (settings.appVolume.peek() != null) {
                doc.settings.put("volume", settings.appVolume.peek());
            }
        }

        // 4. Per-track lyric offsets
        if (controller != null) {
            Map<String, Integer> offsets = controller.getLyricOffsets();
            if (offsets != null && !offsets.isEmpty()) {
                doc.lyricOffsets.putAll(offsets);
            }
        }

        return GSON.toJson(doc);
    }

    /**
     * Export all current settings into an AES-256-GCM encrypted document.
     * Guaranteed never to include login credentials or session tokens.
     */
    public static String exportEncrypted(SettingsCore settings, PlayerController controller, String appVersion) {
        return exportEncrypted(settings, controller, appVersion, null);
    }

    /**
     * Export all current settings into an AES-256-GCM encrypted document with an optional custom passphrase.
     */
    public static String exportEncrypted(SettingsCore settings, PlayerController controller, String appVersion,
                                         char[] optionalPassphrase) {
        String plainJson = export(settings, controller, appVersion);
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        char[] pass = (optionalPassphrase != null && optionalPassphrase.length > 0)
                ? optionalPassphrase : INTERNAL_PASSPHRASE;
        try {
            byte[] keyBytes = deriveKey(pass, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));

            EncryptedDocument doc = new EncryptedDocument();
            doc.format = ENCRYPTED_FORMAT;
            doc.formatVersion = FORMAT_VERSION;
            doc.appVersion = appVersion != null ? appVersion : "";
            doc.exportedAtMs = System.currentTimeMillis();
            doc.salt = Base64.getEncoder().encodeToString(salt);
            doc.iv = Base64.getEncoder().encodeToString(iv);
            doc.data = Base64.getEncoder().encodeToString(ciphertext);

            return GSON.toJson(doc);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt settings configuration", e);
        }
    }

    /**
     * Import a configuration string (either plain JSON or AES-256-GCM encrypted) into SettingsCore.
     * Automatically auto-detects and decrypts encrypted formats.
     *
     * @return true if successfully imported and applied, false on invalid format or decryption failure.
     */
    public static boolean importInto(String jsonOrEncrypted, SettingsCore settings, PlayerController controller) {
        return importInto(jsonOrEncrypted, settings, controller, null);
    }

    /**
     * Import a configuration string with an optional custom decryption passphrase.
     */
    public static boolean importInto(String jsonOrEncrypted, SettingsCore settings, PlayerController controller,
                                     char[] optionalPassphrase) {
        if (jsonOrEncrypted == null || jsonOrEncrypted.trim().isEmpty() || settings == null) return false;
        try {
            JsonElement rootElem = JsonParser.parseString(jsonOrEncrypted);
            if (!rootElem.isJsonObject()) return false;
            JsonObject root = rootElem.getAsJsonObject();

            String plainJson = jsonOrEncrypted;
            if (root.has("format") && ENCRYPTED_FORMAT.equals(root.get("format").getAsString())) {
                plainJson = decryptDocument(root, optionalPassphrase);
                if (plainJson == null) return false;
            }

            return importPlainJson(plainJson, settings, controller);
        } catch (Throwable t) {
            Logger.warn("import settings failed: {}", t.getMessage());
            return false;
        }
    }

    private static String decryptDocument(JsonObject root, char[] optionalPassphrase) {
        try {
            if (!root.has("salt") || !root.has("iv") || !root.has("data")) return null;
            byte[] salt = Base64.getDecoder().decode(root.get("salt").getAsString());
            byte[] iv = Base64.getDecoder().decode(root.get("iv").getAsString());
            byte[] ciphertext = Base64.getDecoder().decode(root.get("data").getAsString());

            char[] pass = (optionalPassphrase != null && optionalPassphrase.length > 0)
                    ? optionalPassphrase : INTERNAL_PASSPHRASE;
            byte[] keyBytes = deriveKey(pass, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            Logger.warn("failed to decrypt settings configuration: {}", e.getMessage());
            return null;
        }
    }

    private static boolean importPlainJson(String json, SettingsCore settings, PlayerController controller) {
        JsonElement rootElem = JsonParser.parseString(json);
        if (!rootElem.isJsonObject()) return false;
        JsonObject root = rootElem.getAsJsonObject();

        JsonObject settingsObj = null;
        JsonObject offsetsObj = null;

        if (root.has("format") && FORMAT.equals(root.get("format").getAsString())) {
            if (root.has("settings") && root.get("settings").isJsonObject()) {
                settingsObj = root.getAsJsonObject("settings");
            }
            if (root.has("lyricOffsets") && root.get("lyricOffsets").isJsonObject()) {
                offsetsObj = root.getAsJsonObject("lyricOffsets");
            }
        } else if (root.has("settings") && root.get("settings").isJsonObject()) {
            settingsObj = root.getAsJsonObject("settings");
        } else {
            // Loose json: treat the top-level keys as settings directly
            settingsObj = root;
        }

        if (settingsObj != null) {
            for (Map.Entry<String, JsonElement> entry : settingsObj.entrySet()) {
                String key = entry.getKey();
                if (isSensitiveKey(key)) continue; // Never import credentials

                JsonElement elem = entry.getValue();
                if (elem == null || elem.isJsonNull()) continue;

                if (SettingsCore.FONT_KEY.equals(key)) {
                    if (elem.isJsonPrimitive()) {
                        settings.setFontSelection(elem.getAsString());
                    }
                } else if (SettingsCore.DESKTOP_LYRIC_FONT_KEY.equals(key)) {
                    if (elem.isJsonPrimitive()) {
                        settings.setDesktopLyricFontSelection(elem.getAsString());
                    }
                } else if ("volume".equals(key)) {
                    if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                        settings.setAppVolume(elem.getAsInt());
                    }
                } else if (settings.hasSpec(key)) {
                    SettingSpec spec = settings.specOf(key);
                    if (spec != null) {
                        Object parsedVal = parseValueForSpec(spec, elem);
                        if (parsedVal != null) {
                            settings.setValue(key, parsedVal);
                        }
                    }
                } else {
                    // Persist non-native or platform-specific key directly to store so it's not lost
                    SettingsStore store = settings.store();
                    if (store != null && elem.isJsonPrimitive()) {
                        JsonPrimitive p = elem.getAsJsonPrimitive();
                        if (p.isBoolean()) store.putBool(key, p.getAsBoolean());
                        else if (p.isNumber()) store.putInt(key, p.getAsInt());
                        else store.putString(key, p.getAsString());
                    }
                }
            }
        }

        if (offsetsObj != null && controller != null) {
            Map<String, Integer> offsets = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : offsetsObj.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    offsets.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
            controller.importLyricOffsets(offsets);
        }

        // Push every updated setting into runtime consumers (themes, fonts, lyrics, volume)
        settings.applyAll();
        return true;
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS);
        return factory.generateSecret(spec).getEncoded();
    }

    private static Object parseValueForSpec(SettingSpec spec, JsonElement elem) {
        if (!elem.isJsonPrimitive()) return null;
        JsonPrimitive p = elem.getAsJsonPrimitive();
        switch (spec.type) {
            case SettingSpec.SWITCH:
                return p.isBoolean() ? p.getAsBoolean() : Boolean.parseBoolean(p.getAsString());
            case SettingSpec.SLIDER:
            case SettingSpec.STEPPER:
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN:
                try {
                    return p.isNumber() ? p.getAsInt() : Integer.parseInt(p.getAsString());
                } catch (NumberFormatException e) {
                    return null;
                }
            case SettingSpec.COLOR:
            case SettingSpec.PATH:
            case SettingSpec.TEXT:
                return p.getAsString();
            default:
                return null;
        }
    }
}
