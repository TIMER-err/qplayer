package dev.t1m3.qplayer.audio.studio;

/**
 * 三台机架：**出场曲、进场曲、主总线**各一套。
 *
 * <p>为什么每首曲子要各有一套（这是自动混音逼出来的需求）：过渡窗口里两首歌同时在响，
 * 而它们常常一个低频轰、一个高频刺。只有主总线一套时，你只能找一个「两首都能接受」的
 * 折中；分开之后可以各自修——A 的低频压一点、B 的高频提一点，过渡听起来才像一个人做的。
 *
 * <p>顺序：<b>每 deck 机架 → 混音/过渡 → 主机架</b>。主机架是「最终润色 + 整套音乐的
 * 统一味道」，每 deck 机架是「把这一首修到能接上去」。
 */
public final class StudioRacks {

    /** 全关（默认；此时整条工作室链路完全直通，位完美不受影响）。 */
    public static final StudioRacks EMPTY = new StudioRacks(StudioRack.EMPTY, StudioRack.EMPTY,
            StudioRack.EMPTY);

    /** 主总线（混完之后）。 */
    public final StudioRack master;

    /** 出场曲（跳转点之前那一首）。 */
    public final StudioRack deckA;

    /** 进场曲。 */
    public final StudioRack deckB;

    private StudioRacks(StudioRack master, StudioRack deckA, StudioRack deckB) {
        this.master = master == null ? StudioRack.EMPTY : master;
        this.deckA = deckA == null ? StudioRack.EMPTY : deckA;
        this.deckB = deckB == null ? StudioRack.EMPTY : deckB;
    }

    public static StudioRacks of(StudioRack master, StudioRack deckA, StudioRack deckB) {
        return new StudioRacks(master, deckA, deckB);
    }

    /** 只做主总线时用这个。 */
    public static StudioRacks master(StudioRack rack) {
        return new StudioRacks(rack, StudioRack.EMPTY, StudioRack.EMPTY);
    }

    public StudioRacks withMaster(StudioRack rack) {
        return new StudioRacks(rack, deckA, deckB);
    }

    public StudioRacks withDeckA(StudioRack rack) {
        return new StudioRacks(master, rack, deckB);
    }

    public StudioRacks withDeckB(StudioRack rack) {
        return new StudioRacks(master, deckA, rack);
    }

    /** 有没有任何一台机架在动采样（决定位完美标记与要不要走处理路径）。 */
    public boolean active() {
        return master.active() || deckA.active() || deckB.active();
    }

    /** 一共几段在工作（日志与界面用）。 */
    public int activeBands() {
        return master.activeBands() + deckA.activeBands() + deckB.activeBands();
    }

    @Override
    public String toString() {
        return "StudioRacks{主 " + master + ", A " + deckA + ", B " + deckB + "}";
    }
}
