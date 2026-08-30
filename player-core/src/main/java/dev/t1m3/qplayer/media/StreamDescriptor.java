package dev.t1m3.qplayer.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** A short-lived rendition resolved by a source plugin. */
public final class StreamDescriptor {
    public String url = "";
    public Map<String, String> headers = new LinkedHashMap<>();
    public String mimeType = "";
    public long expiresAtMs;
    public boolean trial;
    public boolean cacheable = true;
    /** Provider-qualified rendition id when fallback uses a different source. */
    public String renditionId = "";
}
