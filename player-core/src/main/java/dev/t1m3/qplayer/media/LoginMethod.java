package dev.t1m3.qplayer.media;

/** One host-rendered login route advertised by a provider plugin. */
public final class LoginMethod {
    public String id = "";
    /** qr | web | credential */
    public String type = "";
    public String label = "";
    public String instructions = "";
    public String webUrl = "";
    public String cookieUrl = "";
    public String credentialCookieName = "";
    public String credentialLabel = "登录凭据";
}
