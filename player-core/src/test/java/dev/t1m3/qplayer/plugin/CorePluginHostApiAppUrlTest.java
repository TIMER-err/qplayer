package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CorePluginHostApiAppUrlTest {

    @Test
    public void knownMusicAppSchemesAreKept() {
        assertEquals("snssdk1128", CorePluginHostApi.loginAppScheme(
                "snssdk1128://webview?url=https%3A%2F%2Fexample.com"));
        assertEquals("orpheus", CorePluginHostApi.loginAppScheme(
                "orpheus://openurl?url=https%3A%2F%2Fmusic.163.com%2Flogin"));
        assertEquals("wtloginmqq", CorePluginHostApi.loginAppScheme(
                "wtloginmqq://ptlogin/qlogin?appid=1"));
        assertEquals("https", CorePluginHostApi.loginAppScheme(
                "https://music.163.com/login?codekey=abc"));
    }

    @Test
    public void dangerousSchemesAreRejected() {
        assertEquals("", CorePluginHostApi.loginAppScheme("file:///data/data/x"));
        assertEquals("", CorePluginHostApi.loginAppScheme("content://media/external"));
        assertEquals("", CorePluginHostApi.loginAppScheme("intent://scan/#Intent;end"));
        assertEquals("", CorePluginHostApi.loginAppScheme("javascript:alert(1)"));
        assertEquals("", CorePluginHostApi.loginAppScheme("http://example.com"));
        assertEquals("", CorePluginHostApi.loginAppScheme("not a url"));
    }

    @Test
    public void allowlistAcceptsOnlyDeclaredAppSchemes() {
        CorePluginHostApi api = new CorePluginHostApi();
        assertTrue(api.allowsAppUrl("soda", "snssdk1128://webview?url=x"));
        assertTrue(api.allowsAppUrl("netease", "orpheus://openurl?url=x"));
        assertTrue(api.allowsAppUrl("qq", "wtloginmqq://ptlogin/qlogin?q=1"));
        assertFalse(api.allowsAppUrl("soda", "myapp://login"));
        assertFalse(api.allowsAppUrl("soda", "intent://x"));
    }
}
