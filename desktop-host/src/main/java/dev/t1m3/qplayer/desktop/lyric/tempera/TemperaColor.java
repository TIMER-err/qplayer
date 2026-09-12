package dev.t1m3.qplayer.desktop.lyric.tempera;

/**
 * 颜色工具，1:1 移植自 folia-major {@code visualizer/colorMix.ts}。
 *
 * <p>folia 用 {@code rgba(r, g, b, a)} 字符串在引擎里传递颜色；这里直接返回 ARGB int，
 * 解析与舍入规则保持不变（只认 {@code #RGB}/{@code #RRGGBB}/{@code rgb()}/{@code rgba()}，
 * 非法 {@code #} 落白，通道按 {@code Math.round} 四舍五入）。
 */
public final class TemperaColor {
    private TemperaColor() {
    }

    private static final int FALLBACK_RGB = 0xFFFFFF;

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int pack(int r, int g, int b, float alpha) {
        int a = Math.round(clamp(alpha, 0f, 1f) * 255f);
        return (a << 24)
                | ((Math.round(clamp(r, 0, 255)) & 0xFF) << 16)
                | ((Math.round(clamp(g, 0, 255)) & 0xFF) << 8)
                | (Math.round(clamp(b, 0, 255)) & 0xFF);
    }

    /** 解析 {@code #RGB} / {@code #RRGGBB} / {@code rgb()} / {@code rgba()}；其它返回 null。 */
    public static float[] parseChannels(String color) {
        if (color == null) return null;
        String normalized = color.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.startsWith("#")) {
            String hex = normalized.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3), 16);
                    return new float[]{r * 17f, g * 17f, b * 17f};
                }
                if (hex.length() == 6) {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    return new float[]{r, g, b};
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (normalized.startsWith("rgb(") || normalized.startsWith("rgba(")) {
            int open = normalized.indexOf('(');
            int close = normalized.lastIndexOf(')');
            if (open < 0 || close <= open) return null;
            String[] parts = normalized.substring(open + 1, close).split(",");
            if (parts.length < 3) return null;
            float[] channels = new float[3];
            for (int i = 0; i < 3; i++) {
                try {
                    channels[i] = Float.parseFloat(parts[i].trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
                if (!Float.isFinite(channels[i])) return null;
            }
            return channels;
        }
        return null;
    }

    /** 给颜色套一个 alpha；不可解析时落白色回退（与 folia 一致）。 */
    public static int withAlpha(String color, float alpha) {
        float[] channels = color == null ? null : parseChannels(color);
        if (channels == null) {
            return pack((FALLBACK_RGB >> 16) & 0xFF, (FALLBACK_RGB >> 8) & 0xFF,
                    FALLBACK_RGB & 0xFF, alpha);
        }
        return pack((int) channels[0], (int) channels[1], (int) channels[2], alpha);
    }

    /**
     * 线性混合两个颜色。任一不可解析时按 {@code amount >= 0.5} 二选一——与 folia 相同，
     * 因为它在解析失败时用的是 {@code colorWithAlpha(选择结果, alpha)}。
     */
    public static int mixColors(String from, String to, float amount) {
        return mixColors(from, to, amount, 1f);
    }

    public static int mixColors(String from, String to, float amount, float alpha) {
        float normalizedAmount = clamp(amount, 0f, 1f);
        float[] a = parseChannels(from);
        float[] b = parseChannels(to);
        if (a == null || b == null) {
            return withAlpha(normalizedAmount >= 0.5f ? to : from, alpha);
        }
        return pack((int) mix(a[0], b[0], normalizedAmount),
                (int) mix(a[1], b[1], normalizedAmount),
                (int) mix(a[2], b[2], normalizedAmount),
                alpha);
    }

    /** ARGB int 还原成 {@code #rrggbb} 字符串（忽略 alpha），供再次交给颜色解析的场合。 */
    public static String toHex(int argb) {
        return String.format("#%02x%02x%02x",
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    /** Rec.709 亮度（0..255）；不可解析时返回 128，与 folia 的回退一致。 */
    public static float luminance(String color) {        float[] channels = parseChannels(color);
        if (channels == null) return 128f;
        return channels[0] * 0.2126f + channels[1] * 0.7152f + channels[2] * 0.0722f;
    }

    /** ARGB int 的 Rec.709 亮度（0..1）。 */
    public static float luminanceOfArgb(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return r * 0.2126f + g * 0.7152f + b * 0.0722f;
    }
}
