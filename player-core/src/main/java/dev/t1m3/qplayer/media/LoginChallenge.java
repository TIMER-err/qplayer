package dev.t1m3.qplayer.media;

/** Opaque provider-owned login challenge; QPlayer renders it but never interprets credentials. */
public final class LoginChallenge {
    public String id = "";
    public String methodId = "";
    public String status = "waiting";
    public String message = "";
    public String qrContent = "";
    /** Base64 PNG bytes for a QR the plugin already rendered itself (e.g. a
     *  provider whose QR encodes a token the plugin cannot recover as text) —
     *  shown as-is instead of {@link #qrContent} being re-encoded into a matrix. */
    public String qrImageBase64 = "";
    public long expiresAtMs;
    public AccountProfile account;
}
