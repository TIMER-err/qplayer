package dev.t1m3.qplayer.audio;

/**
 * Platform audio playback primitive. Plays one source at a time and signals
 * completion; playlist advancement is the controller's job, not the backend's.
 *
 * <p>Desktop implements this over {@code javax.sound.sampled} + SPI decoders;
 * Android over {@code android.media.MediaPlayer}. Neither type leaks into
 * player-core, which only depends on this interface.
 *
 * <p>All methods are safe to call from the controller / UI thread; the impl
 * serialises onto its own audio thread as needed.
 */
public interface AudioBackend {

    /**
     * Begin playing {@code source} (a local file path or an {@code http(s)}
     * URL) from {@code startMs}. Replaces any current playback. Idempotent
     * enough to double as "restart": calling with the same source reopens it.
     */
    void play(String source, long startMs);

    /** Network rendition variant with provider-required request headers. */
    default void play(String source, java.util.Map<String, String> headers, long startMs) {
        play(source, startMs);
    }

    /** Pause without releasing the source. */
    void pause();

    /** Resume after {@link #pause()}. No-op if nothing is loaded. */
    void resume();

    boolean isPlaying();

    /** Absolute seek within the current source. */
    void seek(long ms);

    /** Current play head in milliseconds (song time, seek-relative). */
    long position();

    /**
     * **界面该读的"耳朵听到的位置"**（2026-10-09 ✓ 用户：「歌词始终比音频快一拍」✓）：
     * 默认与 {@link #position()} 相同 ✓（其他后端不受影响 ✓），
     * `PlaybackEngine` 覆盖它并扣掉输出环形缓冲（约 200 ms ✓）⇒ 界面与听到的对齐 ✓。
     */
    default long audiblePosition() {
        return position();
    }

    /** Duration of the current source in ms, or 0 if unknown. */
    long duration();

    /** Linear gain 0.0–1.0. */
    void setVolume(float volume);

    /**
     * Callback invoked when the current source plays through to its end
     * (not on pause/seek/manual replace). May fire on the audio thread —
     * the controller marshals to the render thread itself.
     */
    void setOnComplete(Runnable callback);

    /**
     * Callback invoked when playback actually begins (e.g. after an async prepare),
     * so a media session can re-baseline its reported position. May fire on the
     * audio thread. Default no-op for backends that don't need it.
     */
    default void setOnStarted(Runnable callback) { }

    /**
     * Callbacks for playback paused / resumed by the backend itself (not the user) —
     * e.g. losing or regaining audio focus on a phone call. Let the controller keep
     * its intended-play state and the media session in sync. Default no-op.
     */
    default void setOnPaused(Runnable callback) { }

    default void setOnResumed(Runnable callback) { }

    /**
     * Callback invoked when the backend encounters a playback error (e.g.
     * MediaPlayer error, invalid source). May fire on the audio thread.
     * Default no-op.
     */
    default void setOnError(Runnable callback) { }

    /**
     * 预告下一首，供自动混音提前做过渡准备分析。默认空实现。
     *
     * <h3>为什么这条接口扩展是必须的</h3>
     *
     * <p>自动混音（AI 模式、声波重合）要在当前曲结束<strong>之前</strong>就开始动手：
     * 对拍与判调性各需要读两首歌的尾段与头段、解码十几秒音频、跑频谱通量与 chroma，
     * 上游实测最坏 4.6 秒（见 {@code Director.PREPARE_MARGIN_SECS} 那 30 秒余量）。
     * 而 {@code Director} 是靠<strong>它自己的队列</strong>知道「下一首是什么」的
     * （{@code prepare} 拿的是 {@code (出场曲, 队首)} 这一对）。
     *
     * <p>单音源的 {@code AudioBackend} 表达不了这件事——{@link #play} 只说「现在放这一首」。
     * <strong>没有这个方法，AI 模式与声波重合就永远不会触发</strong>：
     * {@code Director.prepare} 拿不到下一首，过渡永远走不出「窗口为 0」的
     * {@code Gapless}，听感上就只剩交叉淡化甚至硬接。所以队列推进虽然仍是控制器的活
     * （见本接口的类注释），「下一首是什么」这条<strong>预告</strong>必须能传下来。
     *
     * <p>默认实现是空操作：安卓上已有的 {@code MediaPlayer} 后端不受影响，
     * 它只是没有自动混音而已。
     *
     * @param source  下一首的音源句柄（与 {@link #play} 同一种：本地路径或 http(s) URL）
     * @param startMs 下一首的起始位置（通常是 0）
     */
    default void setNextSource(String source, long startMs) { }

    /**
     * 同 {@link #setNextSource(String, long)}，但带上这条音源需要的<strong>请求头</strong>。
     *
     * <p>为什么需要它：插件的直链常常必须带 token/Referer，而<strong>自动混音要提前分析
     * 下一首</strong>（对拍、判调性、自动点位要扫整曲）。没有这条通路时，控制器只能
     * 「如实放弃」这类音源——于是用户看到的是「自动混音对插件源永远没效果」
     * （2026-09-20 安卓实机日志就是一串「拿不到句柄 → 不预告」）。
     *
     * <p>默认实现<strong>丢掉请求头</strong>转调老签名：只实现了老方法的后端
     * （测试夹具、将来别的宿主）行为完全不变，不会因为多了一个重载就假装拿到了头。
     */
    default void setNextSource(String source, java.util.Map<String, String> headers, long startMs) {
        setNextSource(source, startMs);
    }

    /** 取消预告（下一首变成未知、或用户切到了别的曲子）。默认空实现。 */
    default void clearNextSource() { }

    /**
     * 回调：自动混音的一次过渡<strong>已经开始</strong>——进场曲<strong>正在出声</strong>，
     * 参数是进场曲的音源句柄（与 {@link #setNextSource} 收到的是同一个字符串）。
     * 默认空实现。
     *
     * <h3>为什么这条接口扩展是必须的</h3>
     *
     * <p>引擎的自动交接是「过渡窗口走完 → 进场曲上位 → 触发 {@link #setOnComplete}」，
     * 而交接之前的那整个窗口（交叉淡化 6 s、低音交接 8 s、表现模式 16 s）里进场曲
     * <strong>已经在出声</strong>。等 {@code onComplete} 才推进队列，界面就会在整个窗口里
     * 停在出场曲上、进度条一路走到 100%——用户实测的抱怨正是「B 都放上了，界面还赖在 A」。
     *
     * <p>所以这个回调报的时机是「窗口开了」，不是「窗口走完了」：
     * 队列推进（谁算当前曲、标题/封面/歌词/队列高亮）由控制器在这一刻完成，
     * 而引擎那边<strong>一个采样都不动</strong>——它只是把「已经发生的事」说出来。
     *
     * <p>只在**真的发生过渡**时发：总开关关着（窗口 0）、接法是无缝拼接（窗口 0）、
     * 没有下一首预告时，引擎压根不会进入过渡窗口，这个回调也就一次都不会有。
     * 一次过渡<strong>恰好一次</strong>（引擎按「是否在过渡窗口内」的边沿触发）。
     *
     * <p>默认实现是空操作：安卓的 {@code MediaPlayer} 后端没有自动混音，不受影响。
     *
     * @param callback 进场曲的音源句柄的消费者
     */
    default void setOnTransitionStart(java.util.function.Consumer<String> callback) { }

    /**
     * 确认一次自动交接：<strong>引擎只认账，绝不重启播放</strong>。
     *
     * <h3>为什么不能拿 {@code play(进场曲, 0)} 代替（真 bug 的修法）</h3>
     *
     * <p>控制器在曲终回调里要告诉引擎「这次交接我认了、队列我推进过了」。它从前发的
     * 是一次 {@code play(进场曲, 0)}，指望引擎把它当成「追认」而不是「换曲」。
     * 而引擎认这条的凭据是一枚按<strong>句柄字符串逐字比对</strong>的一次性令牌，
     * 同一首歌的「直链」与「落地后的缓存文件」是两个字符串——对不上的那一次，
     * 引擎真的把这首歌<strong>从 0 重开了一遍</strong>（用户实测：「过渡完成后歌曲进度
     * 被重置回 0 从头播放」，安卓端尤其容易命中：在线曲目的句柄会从直链升级成缓存文件）。
     *
     * <p>语义上也不该是 {@code play}：这一刻控制器<strong>不想播任何东西</strong>。
     * 所以另给一条命令，它没有「重开」这条路径。
     *
     * <p><strong>不给默认实现。</strong> 它以前会是一条 {@code default} 空方法，
     * 于是漏转发的宿主表现是「令牌一直挂着、用户下一次点这一首被静默吃掉」——
     * 与 {@link #currentSource()} / {@link #setOnTransitionStart} 同一个坑
     * （那两个都改成了抽象方法，让编译器替我们挡住「第二个宿主忘了转发」）。没有自动混音的
     * 后端永远不会收到这条调用，实现成空方法是安全的。
     *
     * @param source 控制器认下来的那一首的音源句柄（只用于日志对账，不参与任何「开流」决策）
     */
    /** 2.1.1 的宿主不做双 deck 交接 ⇒ 默认空实现 ✓ */
    default void confirmHandoff(String source) { }

    /**
     * 当前曲目的音源句柄；不知道时返回 {@code null}。默认实现返回 {@code null}。
     *
     * <p>它是只读事实，不是意图：引擎里它一直报「编排器手上那个 current deck 的句柄」，
     * 自动交接把它提上位的那一刻才会变（过渡窗口内仍是出场曲的句柄——那时候两首都在出声，
     * 位置/时长报的是进场曲，见 {@code PlaybackEngine.publishSnapshot} 的注释）。
     *
     * <p>控制器用它来核对一件事：曲终那次 {@code onComplete} 是不是「过渡开始时它已经
     * 推进过的那一首」的交接（见 {@code PlayerController.onTransitionStarted} 与
     * {@code autoAdvance}）。对不上就按普通曲终推进。
     *
     * <p><strong>故意不给默认实现。</strong> 它以前是 {@code default} 返回 {@code null}，
     * 于是漏转发的宿主会静默地永远对不上：控制器每遇到一次自动交接就多推一首队列——
     * 听感是「刚切过去的那首放了几秒又跳了」。2026-09-20 安卓真机就是这么炸的
     * （{@code AndroidPlaybackBackend} 没转发它，而桌面宿主本身就是引擎，所以只有安卓中招）。
     * 同一次移植里 {@code setOnTransitionStart} 也是同一个坑。**新增宿主回调就应该定成抽象的**，
     * 让编译器替我们挡住「第二个宿主忘了转发」这件事。
     */
    /** 2.1.1 的宿主媒体控制会读它；你的实现没有这个概念 ⇒ 默认空串 ✓ */
    default String currentSource() {
        return "";
    }

    /**
     * 分析表头（P8）的读数：峰值 / 真峰 / LUFS / 相位相关性。
     *
     * <p><strong>故意给默认实现</strong>：没有工作室的后端（旧宿主、测试夹具）返回
     * 「静音」是<strong>诚实</strong>的——它确实没在算。这与 {@code currentSource()}
     * 那种「漏转发就是静默出错」的回调不同：这里读到一个空表头不会让任何功能失效。
     */
    default dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot studioMeters() {
        return dev.t1m3.qplayer.audio.studio.StudioMeters.Snapshot.SILENT;
    }

    /**
     * 自动混音控制室（P4）的快照：在不在过渡、接法、窗口、进度、两个句柄。
     *
     * <p>默认返回空快照（{@link dev.t1m3.qplayer.audio.engine.TransitionSnapshot#IDLE}）：
     * 没有自动混音的后端本来就没有过渡。
     */
    default dev.t1m3.qplayer.audio.engine.TransitionSnapshot transitionSnapshot() {
        return dev.t1m3.qplayer.audio.engine.TransitionSnapshot.IDLE;
    }

    /** 工作室现在的增益衰减（dB，正数 = 压了多少）；没有工作室时 0。 */
    default float studioGainReductionDb() {
        return 0f;
    }

    /**
     * 实时频谱（{@value dev.t1m3.qplayer.audio.engine.SpectrumBands#BANDS} 条对数频带的 dBFS）。
     *
     * <p>拷进调用方给的数组、返回写了几条；没有频谱时返回 0。
     * 用数组而不是列表：这条数据每次刷新都要读，装箱 64 个对象纯属浪费。
     */
    default int studioSpectrum(float[] dst) {
        return 0;
    }

    /**
     * 界面在不在看「音频工作室」页。
     *
     * <p>表头与实时频谱都是<strong>为了看</strong>才算的数据（4× 过采样的真峰 + 每块一次
     * FFT），所以没有人在看的时候不该花这份 CPU。宿主在这一页进入/离开时如实报一次。
     *
     * <p>给默认实现（不报也行）：漏报的后果只是「多算了点东西」或「页面数据不动」，
     * 不会像 {@code currentSource()} 那样静默出错。宿主必须把这一条转给引擎
     * （安卓那层 {@code AndroidPlaybackBackend} 与其它转发同一条纪律，
     * 由 {@code AndroidAutomixWiringTest} 做源码级检查）。
     */
    default void setStudioMonitoring(boolean on) { }

    /** Stop playback and free native resources. */
    /**
     * 2.1.1 宿主会调它：告诉后端"正在增长的那个缓存文件"与"完成后要落到的路径"
     * （桌面/安卓都用它做"边下边播"的进度联动）。默认空实现 ✓ 你的实现不需要时可以不管。
     */
    default void bindGrowingCache(String pendingPath, String completePath, long durationMs) { }

    /** 2.1.1 宿主会调它把标签写回文件（含歌词）。默认空实现 ✓ */
    default void writeTags(String path, String title, String artist, String album,
                           byte[] cover, String lyrics) { }

    /** 缓存写完时通知后端（2.1.1 的安卓壳会调；默认空实现 ✓）。 */
    default void notifyCacheComplete() { }

    void release();
}
