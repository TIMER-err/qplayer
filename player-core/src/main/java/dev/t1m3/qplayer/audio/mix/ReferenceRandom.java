package dev.t1m3.qplayer.audio.mix;

/**
 * <b>与 CPython {@code random.Random} 逐位一致的 MT19937</b>。
 *
 * <p>为什么必须 port：参考包的加权选择用的是
 * {@code random.Random(seed).randrange(sum(weights))} —— 只要随机序列差一个数，
 * 同层里抽中的模板就会不同（这正是"为什么点和 Apple 不一样"的一个直接原因）。
 *
 * <p>CPython 的口径（本类逐行照做）：
 * <ol>
 *   <li>整数种子：{@code n = abs(seed)} → 小端字节 → 4 字节一组变成 key 数组；</li>
 *   <li>{@code init_by_array}（标准 MT19937 的数组播种，含 1664525 / 1566083941 两轮）；</li>
 *   <li>{@code getrandbits(k)}：k ≤ 32 时取 {@code genrand_uint32() >> (32-k)}；</li>
 *   <li>{@code randrange(n)}：{@code k = n.bit_length()}，抽到 ≥ n 就重抽。</li>
 * </ol>
 */
public final class ReferenceRandom {

    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private final int[] mt = new int[N];
    private int mti = N + 1;

    public ReferenceRandom(long seed) {
        initByArray(seedWords(seed));
    }

    /** CPython：整数种子 → 小端字节 → 4 字节一组（不足补 0）。 */
    static int[] seedWords(long seed) {
        long n = Math.abs(seed);
        int byteLen = n == 0 ? 1 : (64 - Long.numberOfLeadingZeros(n) + 7) / 8;
        int words = (byteLen + 3) / 4;
        int[] key = new int[Math.max(1, words)];
        long v = n;
        for (int i = 0; i < key.length; i++) {
            key[i] = (int) (v & 0xffffffffL);
            v >>>= 32;
        }
        return key;
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (int i = 1; i < N; i++) {
            mt[i] = (int) (1812433253L * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i);
        }
        mti = N;
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        int k = Math.max(N, key.length);
        for (; k > 0; k--) {
            mt[i] = (int) (((mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j));
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (k = N - 1; k > 0; k--) {
            mt[i] = (int) (((mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i));
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
    }

    /** 标准 MT19937 取一个 32 位无符号数（以 long 承载）。 */
    public long nextUint32() {
        if (mti >= N) {
            int kk = 0;
            for (; kk < N - M; kk++) {
                int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            for (; kk < N - 1; kk++) {
                int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            int y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            mti = 0;
        }
        int y = mt[mti++];
        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= (y >>> 18);
        return y & 0xffffffffL;
    }

    /** CPython {@code getrandbits(k)}（k ∈ 1..32）。 */
    public long getrandbits(int k) {
        if (k <= 0 || k > 32) {
            throw new IllegalArgumentException("k must be 1..32");
        }
        return nextUint32() >>> (32 - k);
    }

    /** CPython {@code randrange(n)}（= _randbelow_with_getrandbits）。 */
    public int randrange(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        int k = 32 - Integer.numberOfLeadingZeros(n);
        int r = (int) getrandbits(k);
        while (r >= n) {
            r = (int) getrandbits(k);
        }
        return r;
    }
}
