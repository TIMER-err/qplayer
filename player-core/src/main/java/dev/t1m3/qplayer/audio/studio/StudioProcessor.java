package dev.t1m3.qplayer.audio.studio;

import dev.t1m3.qplayer.audio.core.AudioBuf;

/**
 * 工作室处理链的总入口：三台机架（出场曲 / 进场曲 / 主总线）。
 *
 * <h2>挂在哪两个位置</h2>
 *
 * <ul>
 *   <li>{@link #processDeckA}/{@link #processDeckB}：每一条 deck 渲染完之后、混音之前
 *       （{@code Director.renderCurrent}/{@code renderTransition} 里那两个
 *       {@code current.render(curBuf)} / {@code nextDeck.render(nxtBuf)} 之后）；</li>
 *   <li>{@link #processMaster}：混音（含过渡）之后、写环形缓冲之前。</li>
 * </ul>
 *
 * <h2>为什么三台机架共用一个类而不是三个对象</h2>
 *
 * <p>它们要共享同一份「当前音量」（等响度补偿），而且换参数时应当<strong>一起</strong>换：
 * 界面上的「应用」是一次动作，泵线程不该看到「主总线换了、deck A 还没换」的中间态。
 * 所以参数是一个不可变的 {@link StudioRacks}，整体替换。
 */
public final class StudioProcessor {

    private final RackProcessor master;
    private final RackProcessor deckA;
    private final RackProcessor deckB;

    public StudioProcessor(int rate, int channels) {
        this.master = new RackProcessor(rate, channels);
        this.deckA = new RackProcessor(rate, channels);
        this.deckB = new RackProcessor(rate, channels);
    }

    /** 换一整套参数。 */
    public void setRacks(StudioRacks racks) {
        StudioRacks r = racks == null ? StudioRacks.EMPTY : racks;
        master.setRack(r.master);
        deckA.setRack(r.deckA);
        deckB.setRack(r.deckB);
    }

    /** 当前音量（0..1）：等响度补偿跟着它走。 */
    public void setVolume(float volume) {
        master.setVolume(volume);
        deckA.setVolume(volume);
        deckB.setVolume(volume);
    }

    /** 有没有任何一台机架在动采样（位完美标记靠它）。 */
    public boolean active() {
        return master.active() || deckA.active() || deckB.active();
    }

    /**
     * 这条链引入的延迟（采样）——<strong>按信号流过的那几台相加</strong>。
     *
     * <p>为什么不取最大值：三台里出场曲与主总线是<strong>串在一条路上</strong>的
     * （{@code deckA → 混音 → master}），延迟要相加；进场曲那条在混音前另成一路，
     * 所以「出场曲这一路的总延迟」= {@code deckA + master}，
     * 「进场曲这一路」= {@code deckB + master}。两条路不一样时（比如只给出场曲开了移调）
     * 融合期里两首歌会错开那一个前视——这正是必须<strong>如实上报</strong>的场景：
     * 现在它是已知量（21.3 ms），而不是「听起来有点糊」。
     *
     * <p>报的是<strong>较大</strong>的那一条路：引擎那本账问的是「输出相对输入晚了多少」，
     * 而每条路各自的值由 {@link #latencyFramesDeckA()} / {@link #latencyFramesDeckB()} 给。
     */
    public int latencyFrames() {
        return Math.max(latencyFramesDeckA(), latencyFramesDeckB());
    }

    /** 出场曲那一条路的总延迟（出场曲机架 + 主总线机架）。 */
    public int latencyFramesDeckA() {
        return deckA.latencyFrames() + master.latencyFrames();
    }

    /** 进场曲那一条路的总延迟（进场曲机架 + 主总线机架）。 */
    public int latencyFramesDeckB() {
        return deckB.latencyFrames() + master.latencyFrames();
    }

    public void processDeckA(AudioBuf buf) {
        deckA.process(buf);
    }

    public void processDeckB(AudioBuf buf) {
        deckB.process(buf);
    }

    public void processMaster(AudioBuf buf) {
        master.process(buf);
    }

    /**
     * 本块的最大增益衰减（dB，正数）——<strong>三台里最大的那一台</strong>。
     *
     * <p>表头要的是「现在压了多少」，而三台机架可能各压各的（出场曲压得多、主总线没动）。
     * 取最大值而不是相加：相加会把「三台各压 3 dB」说成 9 dB，那不是任何一处真实发生的事。
     */
    public float gainReductionDb() {
        return Math.max(master.gainReductionDb(),
                Math.max(deckA.gainReductionDb(), deckB.gainReductionDb()));
    }

    /** 清状态（跳转、换曲、重开设备）。 */
    public void reset() {
        master.reset();
        deckA.reset();
        deckB.reset();
    }

    /** 采样率（换设备时重建）。 */
    public int rate() {
        return master.rate();
    }

    /** 人看的一句话（进日志：开着哪些、几段在动）。 */
    public String describe() {
        if (!active()) {
            return "工作室：全关（直通）";
        }
        StringBuilder sb = new StringBuilder("工作室：主总线 ");
        sb.append(master.activeBands()).append(" 段、出场曲 ").append(deckA.activeBands())
                .append(" 段、进场曲 ").append(deckB.activeBands()).append(" 段");
        if (latencyFrames() > 0) {
            // 延迟只在真的有的时候写出来：0 是常态，每行都挂一句「延迟 0」是噪音
            sb.append("、延迟 ").append(latencyFrames()).append(" 帧（出场曲那一路 ")
                    .append(latencyFramesDeckA()).append(" / 进场曲那一路 ")
                    .append(latencyFramesDeckB()).append("）");
        }
        return sb.toString();
    }
}
