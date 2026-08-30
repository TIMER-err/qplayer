package dev.t1m3.qplayer.plugin;

/** Version gate shared by package inspection and installed-runtime startup. */
public final class PluginCompatibility {
    public static final String HOST_VERSION = "1.4.0";
    public static final String API_VERSION = "1.0";

    private PluginCompatibility() {}

    public static void requireCompatible(PluginManifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("manifest == null");
        int[] requestedApi = numbers(manifest.apiVersion);
        int[] supportedApi = numbers(API_VERSION);
        if (requestedApi[0] != supportedApi[0] || requestedApi[1] > supportedApi[1]) {
            throw new IllegalArgumentException("unsupported plugin API " + manifest.apiVersion);
        }
        if (manifest.minHostVersion != null && !manifest.minHostVersion.isEmpty()
                && compare(manifest.minHostVersion, HOST_VERSION) > 0) {
            throw new IllegalArgumentException("plugin requires QPlayer "
                    + manifest.minHostVersion + " or later");
        }
    }

    public static void requireHostAtLeast(String minimum) {
        if (minimum != null && !minimum.isEmpty() && compare(minimum, HOST_VERSION) > 0) {
            throw new IllegalArgumentException("plugin requires QPlayer " + minimum + " or later");
        }
    }

    public static int compare(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private static int[] numbers(String value) {
        int[] out = new int[4];
        if (value == null) return out;
        String core = value.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }
}
