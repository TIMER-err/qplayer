package dev.t1m3.qplayer.audio.model;

/**
 * 峰值拾取（上游 {@code sonalis-model/src/beat.rs} 的模块级自由函数 {@code pick_peaks}）。
 *
 * <h2>为什么单独拆出来</h2>
 *
 * <p>模型上万个参数，但峰值拾取只有十几行。它出错时更应该<b>一眼看出来</b>，
 * 也应当能<b>脱离模型测试</b>——本类的测试全部只喂数组，不碰 ONNX。
 *
 * <h2>与上游的类型差异（按 docs/audio-port.md 记录）</h2>
 *
 * <ul>
 *   <li>上游是模块级 {@code pub fn pick_peaks}；Java 没有自由函数，按约定挂到一个
 *       <b>不可实例化</b>的类上（私有构造 + 全 {@code static}），与 {@code Analysis}
 *       / {@code Harmonic} 同一处理。</li>
 *   <li>{@code &[f32]} → {@code float[]}，{@code Vec<usize>} → {@code int[]}
 *       （{@code usize} 计数 → {@code int}）。返回 {@code int[]} 而不是
 *       {@code List<Integer>}：上游的 {@code Vec<usize>} 是连续存储，
 *       而调用方（{@code BeatModel::beats}）只是顺序遍历它。</li>
 * </ul>
 *
 * <p>对应 Sonalis 的 {@code sonalis_model::beat::pick_peaks}。
 */
public final class Peaks {

    private Peaks() {
    }

    /**
     * 从逐帧激活值里挑出峰所在的帧号。
     *
     * <p>逐条对应上游的实现，包括三处容易「顺手优化」掉、但改了就会变行为的地方：
     *
     * <ol>
     *   <li>先减 {@code offset} 再取最大值、再算阈值——{@code offset} 是给模型
     *       那点非零基线留的，减之前取最大值会把阈值整体抬高。</li>
     *   <li>局部极大的判据是 {@code v <= vals[i]}（<b>允许相等</b>）：一片平台会被
     *       整段判成极值，之后由 {@code minGapFrames} 收敛成一个峰。</li>
     *   <li>挨得太近时<b>留更高的那个</b>（而不是留更早的那个）：{@code vals[i] > vals[last]}
     *       才把前一个挤掉，否则直接跳过当前这个。</li>
     * </ol>
     *
     * @param activation 逐帧激活值
     * @param p          拾取参数
     * @return 峰所在的帧号，升序
     */
    public static int[] pickPeaks(float[] activation, PeakParams p) {
        if (activation.length == 0) {
            return new int[0];
        }

        // 减掉基线。上游是 `activation.iter().map(|v| v - p.offset).collect()`：
        // 这里不额外分配一个等长数组，而是在下面每次比较时现减——数值逐位相同，
        // 少一次 frames 级的分配（frames 可以到几万）。
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < activation.length; i++) {
            float v = activation[i] - p.offset;
            if (v > max) {
                max = v;
            }
        }
        // `!(max > 0.0)` 而不是 `max <= 0.0`：NaN 也走这一支（取反比较为真），
        // 与上游 `if !(max > 0.0)` 逐位一致。全负的激活值在这里就被挡掉。
        if (!(max > 0.0f)) {
            return new int[0];
        }
        float threshold = max * p.thresholdRatio;

        // 上游用 Vec::push/pop，这里用固定上界 + 手工长度：push 的次数上界就是
        // 激活值个数，且最终要转成 int[]。
        int[] peaks = new int[activation.length];
        int count = 0;

        for (int i = 0; i < activation.length; i++) {
            float vi = activation[i] - p.offset;
            if (vi < threshold) {
                continue;
            }
            // 局部极大：向前后的窗口内都不能有更大的
            int lo = i - p.preMax;
            if (lo < 0) {
                lo = 0;
            }
            int hi = i + p.postMax + 1;
            if (hi > activation.length) {
                hi = activation.length;
            }
            boolean isMax = true;
            for (int k = lo; k < hi; k++) {
                // 上游是 `vals[lo..hi].iter().all(|&v| v <= vals[i])`。写成
                // `!(v <= vi)` 而不是 `v > vi`：两者的差别只在 NaN 上——
                // `NaN <= x` 与 `NaN > x` 都是 false，用 `all` 的语义时 NaN 会让
                // 局部极大判为 false，用 `v > vi` 则会判为 true（与上游分叉）。
                if (!(activation[k] - p.offset <= vi)) {
                    isMax = false;
                    break;
                }
            }
            if (!isMax) {
                continue;
            }
            // 太近就丢掉前一个
            if (count > 0) {
                int last = peaks[count - 1];
                if (i - last < p.minGapFrames) {
                    if (vi > activation[last] - p.offset) {
                        count--;
                    } else {
                        continue;
                    }
                }
            }
            peaks[count] = i;
            count++;
        }

        return java.util.Arrays.copyOf(peaks, count);
    }
}
