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
    /**
     * Deep link the host may open so the user can confirm this challenge in
     * another app (Douyin / QQ / NetEase Cloud Music) instead of scanning a
     * QR on the same phone. Empty when the plugin has no such route.
     */
    public String appUrl = "";
    /** Button caption for {@link #appUrl}; empty uses the method label. */
    public String appLabel = "";
    public long expiresAtMs;
    public AccountProfile account;
}
