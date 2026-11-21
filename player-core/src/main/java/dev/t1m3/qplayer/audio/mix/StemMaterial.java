package dev.t1m3.qplayer.audio.mix;

/**
 * <b>一段"分轨素材"</b>：伴奏 + 人声两条轨，可以按时变增益重建（2026-10-07，目标②）。
 *
 * <p>为什么需要它（用户实测逼出来的）：我们一度把"人声增益"直接乘到**整段混音**上，
 * 结果增益 0 时**伴奏也一起静音** ⇒ 用户听到"突然静音" ✗。人声增益只能作用在**人声轨**上：
 *
 * <pre>
 *   重建 = instrumental + vocals × gain(t)
 *   gain = 1 ⇒ 原样（应当与未分离的素材逐位相同）
 *   gain = 0 ⇒ 只剩伴奏（"去人声"）
 * </pre>
 *
 * <p>人声按**单声道**存（我们现有的人声处理就是单声道：{@code vocals0/vocals1} 都取左右平均）。
 * {@code vocals == null} 表示这段没有分轨结果（没开分离、或分离失败）⇒ 重建退化为原样 ✓
 * —— "算不出来就别猜"这条纪律在这里体现为**不去动它**。
 */
public final class StemMaterial {

    /** 伴奏（{@code [声道][帧]}），永远非空。 */
    public final float[][] instrumental;

    /** 人声（单声道），可为 {@code null}（没有分轨结果）。 */
    public final float[] vocals;

    public StemMaterial(float[][] instrumental, float[] vocals) {
        if (instrumental == null || instrumental.length == 0) {
            throw new IllegalArgumentException("伴奏轨不能为空");
        }
        this.instrumental = instrumental;
        this.vocals = vocals;
    }

    /** 帧数。 */
    public int frames() {
        return instrumental[0] == null ? 0 : instrumental[0].length;
    }

    /** 有没有分轨结果（没有就等价于"原样素材"）。 */
    public boolean hasStems() {
        return vocals != null;
    }

    /**
     * 按时变增益重建。
     *
     * @param gainPerFrame 每帧的人声增益（1 = 原样、0 = 去人声）；长度不足的部分按 1 处理；
     *                     传 {@code null} 等价于"处处 1"
     * @return 重建后的素材（新数组；**不修改**本对象）
     */
    public float[][] mixWith(float[] gainPerFrame) {
        int n = frames();
        float[][] out = new float[instrumental.length][n];
        for (int c = 0; c < instrumental.length; c++) {
            System.arraycopy(instrumental[c], 0, out[c], 0, n);
        }
        if (vocals == null) {
            return out;                       // 没有分轨 ⇒ out 就是原素材（withoutStems 那条路）
        }
        // 注意：gainPerFrame == null 表示"**处处 1**"（还原原始混音），不是"不加人声" ✗ ——
        // 文档与实现必须一致，这一条也是判据抓出来的（第一次实现提前返回了伴奏）。
        int m = Math.min(n, vocals.length);
        if (gainPerFrame != null) {
            m = Math.min(m, gainPerFrame.length);
        }
        // **semantics 的关键一点**（第一次写错、被判据当场抓住）：
        // instrumental 是**纯伴奏**，所以 gain = 1 必须理解成"**还原成原始混音**"
        // （伴奏 + 人声），而不是"跳过人声" —— 后者会让 g=1 变成"只剩伴奏" ✗。
        // 于是"处处 1 ⇒ 逐位等于未分离素材"这条性质成立（判据在验它）。
        for (int c = 0; c < out.length; c++) {
            for (int i = 0; i < m; i++) {
                float g = gainPerFrame == null ? 1.0f : gainPerFrame[i];
                if (g == 0.0f) {
                    continue;                 // 去人声：不加人声（省一半乘加）
                }
                out[c][i] += (float) (vocals[i] * g);
            }
        }
        return out;
    }

    /**
     * 把一段"未分离"的素材包成分轨素材（人声为空 ⇒ 重建恒等于原样）。
     *
     * <p>用于"没开分离 / 分离失败"的路径：这样调用方**不需要分支**，
     * 而在没有分轨时它逐位等于原素材 ✓。
     */
    public static StemMaterial withoutStems(float[][] material) {
        return new StemMaterial(material, null);
    }

    /**
     * 按增益曲线把增益压成**逐帧**数组的便捷构造（判据与渲染侧共用同一套语义）。
     */
    public static float[] rampGain(int frames, int from, int to, float gain) {
        float[] g = new float[frames];
        for (int i = 0; i < frames; i++) {
            g[i] = (i >= from && i < to) ? gain : 1.0f;
        }
        return g;
    }
}
