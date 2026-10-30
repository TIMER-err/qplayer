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

    @Test
    public void dnsFailureDoesNotBecomeAPermanentPermissionDecision() throws Exception {
        PluginManifest manifest = new PluginManifest();
        manifest.schemaVersion = 1;
        manifest.id = "dns-fixture";
        manifest.name = "DNS fixture";
        manifest.version = "1.0.0";
        manifest.apiVersion = "1.0";
        manifest.permissions = java.util.Collections.singletonList(PluginPermission.NETWORK.wireName());
        manifest.networkDomains = java.util.Arrays.asList("unresolvable.invalid", "127.0.0.1");
        try (CorePluginHostApi api = new CorePluginHostApi()) {
            api.register(manifest);
            java.lang.reflect.Field field = CorePluginHostApi.class.getDeclaredField("urlGrants");
            field.setAccessible(true);
            java.util.Map<?, ?> grants = (java.util.Map<?, ?>) field.get(api);
            for (int attempt = 0; attempt < 2; attempt++) {
                assertFalse(api.allowsReturnedUrl(manifest.id, "https://unresolvable.invalid/art"));
                assertFalse(grants.containsKey("dns-fixture|https|unresolvable.invalid"));
            }
            // Deterministic policy denials still retain the LAN/scheme boundary.
            assertFalse(api.allowsReturnedUrl(manifest.id, "https://127.0.0.1/art"));
            assertEquals(Boolean.FALSE, grants.get("dns-fixture|https|127.0.0.1"));
            assertFalse(api.allowsReturnedUrl(manifest.id, "ftp://127.0.0.1/art"));
        }
    }
}
