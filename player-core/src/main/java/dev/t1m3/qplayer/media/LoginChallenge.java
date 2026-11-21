package dev.t1m3.qplayer.media;

/** Opaque provider-owned login challenge; QPlayer renders it but never interprets credentials. */
public final class LoginChallenge {
    public String id = "";
    public String methodId = "";
    public String status = "waiting";
    public String message = "";
    public String qrContent = "";
    public long expiresAtMs;
    public AccountProfile account;
}
