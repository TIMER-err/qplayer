package dev.t1m3.qplayer.bridge;

/**
 * 「过渡提示」什么时候亮：<strong>过渡开始前三秒就该亮</strong>。
 *
 * <h2>用户的要求（2026-09-30）</h2>
 *
 * <p>原话是「让进度条发光在 automix 开始前三秒就有」，起因是「开始 automix 的话 A 就直接跳了」——
 * 提示与「界面早一步跳到进场曲」是<b>同一刻</b>发生的，所以那一下提示等于没提示：
 * 用户看到的是 A 的页面直接跳走。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>这条规则是<b>策略</b>，它的输入只有一个数（{@code TransitionSnapshot.leadInSecs}），
 * 而它的调用者 {@link PlayerController} 在测试里不好单独摆（要 Skija 原生库）。
 * 把它抽成一个纯函数，判据就能直接钉住边界：提前量正好等于三秒时亮不亮、
 * 「没有已装载的过渡」（{@code NaN}）绝不能亮。
 */
public final class AutomixGlow {

    /**
     * 提前量（秒）：过渡开始前这么久就亮。
     *
     * <p>十秒是用户点的数（2026-09-30 先要 3 秒，随后改成 10 秒）。它必须短于「这一次过渡
     * 大概还剩多久开始」的最坏情况，否则提示就没有意义了——所以上限由最长的准备余量
     * （90 s）与最短的曲子共同决定，10 s 在任何一档上都只是曲终前很小的一段；
     * 下限只要够 {@code LyricProgressBlock} 那条 0.6 s 量级的淡入走完就行。
     */
    public static final double LEAD_SECS = 10.0;

    private AutomixGlow() {
    }

    /**
     * 经典歌词页此刻该不该让进度条的已完成段发光。
     *
     * @param leadInSecs 离过渡开始还有几秒（{@code TransitionSnapshot.leadInSecs}）：
     *                   正数 = 还没开始；{@code 0} 或负数 = 已经在过渡里；
     *                   {@code NaN} = 没有已装载的过渡（<b>不预告</b>）
     */
    public static boolean active(double leadInSecs) {
        // **必须先挡掉非有限值**：
        //
        //   * `NaN`（没有已装载的过渡）——`NaN <= 3.0` 恰好是 false，但**不许**把这行写成
        //     `!(leadInSecs > LEAD_SECS)`：那样 NaN 会翻成 true，页面就永远亮着；
        //   * `-Infinity`（异常输入）——它「小于三秒」，一个裸比较会把它判成要亮；
        //     真出现这种值说明上游算坏了，那时**不亮**比「永远亮着」安全得多
        //     （亮着的东西用户会当成真的预告）。
        return Double.isFinite(leadInSecs) && leadInSecs <= LEAD_SECS;
    }
}
