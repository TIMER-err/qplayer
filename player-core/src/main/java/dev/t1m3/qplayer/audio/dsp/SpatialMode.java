package dev.t1m3.qplayer.audio.dsp;

/**
 * 空间音频模式。
 *
 * <p>对应 Sonalis 的 {@code sonalis_dsp::spatial::SpatialMode}（简单 enum → Java enum，
 * 变体名 UPPER_SNAKE，方法名小驼峰）。
 *
 * <p>三个变体的分工就是 ADR-001 里「环绕声」的两件事：
 *
 * <ul>
 *   <li>{@link #OFF}：关闭，多声道原样交给下游（直通）。</li>
 *   <li>{@link #BINAURAL}：双耳渲染，把任意多声道折叠成耳机里的空间感。</li>
 *   <li>{@link #PASSTHROUGH}：多声道直通，不做折叠，原样交给 HDMI 功放。
 *       这条路不需要 DSP，由声道映射完成。</li>
 * </ul>
 *
 * <p><strong>{@link #rewritesSamples()} 是位完美标记的唯一来源</strong>，与
 * {@link dev.t1m3.qplayer.audio.mix.Transition#rewritesSamples()} 是同一套判断纪律：
 * 判定逻辑只允许有一份，谁都不许在旁边另写一遍「这个模式动不动采样」。双耳渲染
 * 必然改写——它是彻头彻尾的处理；另两个模式一个采样都不碰。
 *
 * <p>装备门槛（与 ADR-001 一致）：硬件空间音频按硬件置灰（由设备能力探测决定），
 * 而本模块是纯软件渲染，耳机就能用——但只有当输出设备是耳机或双声道时才有意义；
 * 接到 5.1 功放上时应该走 {@link #PASSTHROUGH}，而不是先折叠再送出去。
 */
public enum SpatialMode {

    /** 关闭。多声道原样交给下游（直通）。上游 {@code #[default]} 就是它。 */
    OFF,

    /** 双耳渲染：折叠成耳机里的空间感。 */
    BINAURAL,

    /** 多声道直通：不做折叠，交给功放。 */
    PASSTHROUGH;

    /**
     * 界面上的显示名。
     *
     * <p>对应上游的 {@code SpatialMode::label}。上游是 {@code const fn} 返回
     * {@code &'static str}；Java 里就是返回常量字符串。
     */
    public String label() {
        switch (this) {
            case OFF:
                return "关闭";
            case BINAURAL:
                return "双耳渲染";
            case PASSTHROUGH:
                return "多声道直通";
            default:
                // 上游的 match 是穷尽的；Java 的 switch 不是，加一条让新增变体时立刻暴露
                throw new IllegalStateException("未知空间音频模式：" + this);
        }
    }

    /**
     * 是否会改写采样值。双耳渲染必然改写——它是彻头彻尾的处理。
     *
     * <p>返回 {@code false} 的模式不会破坏位完美：引擎据此决定位完美标记，而不是
     * 另外判断一遍模式（理由见 {@link dev.t1m3.qplayer.audio.mix.Transition}）。
     */
    public boolean rewritesSamples() {
        return this == BINAURAL;
    }

    /**
     * 上游 {@code #[derive(Default)]} + {@code #[default] Off} 的对应物。
     *
     * <p>不这样显式命名不行：Java 的 enum 不能用 {@code default} 做方法名（保留字），
     * 而且 Java 字段的隐式默认是 {@code null}——那既不是 {@link #OFF} 也不是任何
     * 合法模式。默认值必须能直接取到，不能让调用方拿 {@code null} 顶着再判空。
     */
    public static SpatialMode defaultValue() {
        return OFF;
    }
}
