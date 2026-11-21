package dev.t1m3.qplayer.i18n;

import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class I18nTest {

    @After
    public void restoreDefaultLanguage() {
        I18n.instance().setLanguage(I18n.FALLBACK);
    }

    @Test
    public void translatesAndSwitchesLanguage() {
        I18n i18n = I18n.instance();
        assertEquals("设置", i18n.t("settings.title"));

        i18n.setLanguage("en_US");
        assertEquals("Settings", i18n.t("settings.title"));
        assertEquals("en_US", i18n.language());
    }

    @Test
    public void fillsPositionalPlaceholders() {
        assertEquals("已复制链接", I18n.tr("toast.linkCopied"));
        assertEquals("插件已安装：网易云音乐", I18n.tr("toast.plugin.installed", "网易云音乐"));
    }

    @Test
    public void missingKeyShowsTheKeyRatherThanNothing() {
        assertEquals("nope.not.a.key", I18n.tr("nope.not.a.key"));
    }

    @Test
    public void unknownLanguageKeepsTheCurrentOne() {
        I18n i18n = I18n.instance();
        i18n.setLanguage("kl_KL");
        assertEquals(I18n.FALLBACK, i18n.language());
        assertEquals("设置", i18n.t("settings.title"));
    }

    /** A key added to the base catalog and forgotten elsewhere silently shows
     *  Chinese to an English user, so hold the catalogs to the same key set. */
    @Test
    public void everyLanguageDefinesTheSameKeys() throws Exception {
        TreeSet<String> base = new TreeSet<>(keys("zh_CN"));
        TreeSet<String> english = new TreeSet<>(keys("en_US"));
        TreeSet<String> missing = new TreeSet<>(base);
        missing.removeAll(english);
        TreeSet<String> extra = new TreeSet<>(english);
        extra.removeAll(base);
        assertTrue("en_US is missing: " + missing, missing.isEmpty());
        assertTrue("en_US has unknown keys: " + extra, extra.isEmpty());
    }

    private static java.util.Set<String> keys(String tag) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = I18n.class.getResourceAsStream("/lang/" + tag + ".properties");
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties.stringPropertyNames();
    }
}
