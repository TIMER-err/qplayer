package dev.t1m3.qplayer.plugin;

import org.junit.Test;

import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CorePluginHostApiPtloginTest {

    @Test
    public void ptqrloginRedirectWithUinAndPtsigxIsAHandoff() throws Exception {
        assertTrue(handoff("https://ssl.ptlogin2.qq.com/ptqrlogin?aid=1",
                "https://ssl.ptlogin2.graph.qq.com/check_sig?uin=o123&ptsigx=abc"));
        assertFalse("waiting polls have no login hop",
                handoff("https://ssl.ptlogin2.qq.com/ptqrlogin?aid=1",
                        "https://xui.ptlogin2.qq.com/"));
        assertFalse("unrelated 302s stay followed",
                handoff("https://y.qq.com/portal/profile.html",
                        "https://y.qq.com/?uin=1&ptsigx=x"));
    }

    private static boolean handoff(String request, String location) throws Exception {
        Method method = CorePluginHostApi.class.getDeclaredMethod(
                "isPtloginHandoff", URL.class, String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, new URL(request), location);
    }
}
