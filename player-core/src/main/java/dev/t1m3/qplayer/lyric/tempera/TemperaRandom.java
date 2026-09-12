package dev.t1m3.qplayer.lyric.tempera;

/**
 * 确定性种子工具，1:1 移植自 folia-major {@code tempera/temperaRandom.ts}。
 *
 * <p>全部用 int 位运算表达 JS 的 {@code Math.imul(...) >>> 0} 语义：Java 的 int 乘法/
 * 异或在 32 位上与 {@code Math.imul} 一致，{@code >>> 0} 只是把结果当无符号 32 位看，
 * 所以这里保留 int 位型，只在取模/归一化时按无符号解释。
 */
public final class TemperaRandom {
    private TemperaRandom() {
    }

    /** FNV-1a；返回值按无符号 32 位解释。 */
    public static int hashSeed(String value) {
        int hash = 0x811C9DC5; // 2166136261
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                hash ^= value.charAt(i);
                hash *= 0x01000193; // 16777619
            }
        }
        return hash;
    }

    /** 把一个种子与盐混合，让不同子系统（色块/装饰/逐字抖动）互不相关。 */
    public static int mixSeed(int seed, int salt) {
        return (seed ^ salt) * 0x9E3779B1; // 2654435761
    }

    /** 每个元素索引上的确定性 0..1 抖动；可重播、可重建。 */
    public static double hash01(int seed, int index, int salt) {
        int mixed = mixSeed(seed + (index + 1) * 97, salt);
        return Integer.toUnsignedLong(mixed) / 4294967296.0;
    }

    /** 从候选里挑一个，尽量与上一次不同。 */
    public static String chooseWithoutRepeat(String[] choices, String seed, String previous) {
        if (choices.length == 0) return previous;
        int start = Integer.remainderUnsigned(hashSeed(seed), choices.length);
        for (int offset = 0; offset < choices.length; offset++) {
            String candidate = choices[(start + offset) % choices.length];
            if (!candidate.equals(previous)) return candidate;
        }
        return choices[start];
    }
}
