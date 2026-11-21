package dev.t1m3.qplayer.audio.mix;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * <b>ReferenceParityTest（随机数）</b>：{@link ReferenceRandom} 必须与 CPython
 * {@code random.Random(seed).randrange(n)} 的序列逐位一致（同一个 seed 抽同样的数）。
 *
 * <p>这是 weighted 选择能一致的前提：权重模的落点依赖这个序列。
 */
public class ReferenceRandomParityTest {

    @Test
    public void 随机序列与CPython逐位一致() {
        check(0L, 7, new int[]{6, 3, 6, 3, 0});
        check(0L, 100, new int[]{49, 97, 53, 5, 33});
        check(0L, 999, new int[]{864, 394, 776, 911, 430});
        check(0L, 1000, new int[]{864, 394, 776, 911, 430});
        check(1L, 7, new int[]{1, 4, 6, 6, 6});
        check(1L, 100, new int[]{17, 72, 97, 8, 32});
        check(1L, 999, new int[]{137, 582, 867, 821, 782});
        check(1L, 1000, new int[]{137, 582, 867, 821, 782});
        check(42L, 7, new int[]{5, 0, 0, 5, 2});
        check(42L, 100, new int[]{81, 14, 3, 94, 35});
        check(42L, 999, new int[]{654, 114, 25, 759, 281});
        check(42L, 1000, new int[]{654, 114, 25, 759, 281});
        check(123456789L, 7, new int[]{5, 3, 4, 5, 6});
        check(123456789L, 100, new int[]{82, 56, 69, 90, 77});
        check(123456789L, 999, new int[]{656, 452, 555, 726, 924});
        check(123456789L, 1000, new int[]{656, 452, 555, 726, 924});
        check(9223372036854775807L, 7, new int[]{2, 5, 5, 1, 6});
        check(9223372036854775807L, 100, new int[]{40, 84, 80, 16, 62});
        check(9223372036854775807L, 999, new int[]{324, 672, 646, 135, 822});
        check(9223372036854775807L, 1000, new int[]{324, 672, 646, 135, 822});
    }

    private static void check(long seed, int n, int[] expected) {
        ReferenceRandom r = new ReferenceRandom(seed);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("seed=" + seed + " n=" + n + " i=" + i, expected[i], r.randrange(n));
        }
    }
}
