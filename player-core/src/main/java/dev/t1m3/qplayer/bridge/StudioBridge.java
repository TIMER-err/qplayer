package dev.t1m3.qplayer.bridge;

import dev.t1m3.qplayer.audio.AudioBackend;
import dev.t1m3.qplayer.audio.engine.SpectrumBands;
import dev.t1m3.qplayer.audio.engine.TransitionSnapshot;
import dev.t1m3.qplayer.audio.studio.BandType;
import dev.t1m3.qplayer.audio.studio.ChannelTarget;
import dev.t1m3.qplayer.audio.studio.EqBand;
import dev.t1m3.qplayer.audio.studio.EqResponse;
import dev.t1m3.qplayer.audio.studio.PitchParams;
import dev.t1m3.qplayer.audio.studio.SaturationParams;
import dev.t1m3.qplayer.audio.studio.ScaleQuantizer;
import dev.t1m3.qplayer.audio.studio.StudioJson;
import dev.t1m3.qplayer.audio.studio.StudioMeters;
import dev.t1m3.qplayer.audio.studio.StudioPresets;
import dev.t1m3.qplayer.audio.studio.StudioRack;
import dev.t1m3.qplayer.audio.studio.StudioRacks;
import dev.t1m3.qplayer.settings.SettingsCatalog;
import dev.t1m3.qplayer.settings.SettingsCore;
import io.github.timer_err.qml4j.engine.binding.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 「音频工作室」页面（工作站）与引擎之间的桥。
 *
 * <h2>它解决什么</h2>
 *
 * <p>设置页那几行是「一组开关」，而工作站要的是<strong>一整套可编辑的链</strong>：
 * 一台机架上有十几段均衡，每段有频率/增益/Q/类型/声道，外加音高、动态、立体声、染色四组模块，
 * 再加实时频谱与表头。这些东西不可能摊成设置行（那会是几百行），也不该让界面自己算
 * ——界面自己算就等于把 DSP 抄第二遍，两份迟早分叉。
 *
 * <p>所以这一层做三件事：
 *
 * <ol>
 *   <li><b>读</b>：把「此刻引擎真的在用哪条链」发布成 QML 能直接画的形状
 *       （段表 + 频响曲线 + 实时频谱 + 表头 + 控制室）；</li>
 *   <li><b>写</b>：把界面上的编辑翻译成设置里的值——<strong>两条落点是刻意分开的</strong>
 *       （见下面）；</li>
 *   <li><b>算</b>：频响曲线由 {@link EqResponse} 算（与判据用的是同一份实现）。</li>
 * </ol>
 *
 * <h2>编辑落在哪里（两条落点，别混）</h2>
 *
 * <ul>
 *   <li><b>均衡段</b> → 写进「自定义 JSON」（{@code studioCustomChain}）并把「曲线」那一行
 *       置为「关」。理由：曲线那一行的语义是「用它覆盖段表」，编辑完还留着一条曲线在上面
 *       覆盖，用户会看到「拖了没反应」。置成「关」之后<strong>页面上看到的就是听到的</strong>；</li>
 *   <li><b>模块参数</b>（前级/响度/音高/动态/立体声/染色/手动点位）→ 写进对应的<strong>设置行</strong>。
 *       理由：那些行本来就存在（设置页里能看见），两处显示同一份值才不会打架
 *       （{@code AutomixSettings.composeStudio} 的行是最后叠加的，写 JSON 会被行覆盖）。</li>
 * </ul>
 *
 * <h2>线程</h2>
 *
 * <p>{@code refresh()} / {@code tick()} 以及所有 setter 都必须在渲染线程上调用
 * （QML 的事件处理本来就在那条线程上；{@code tick()} 由 {@code PlayerController.pump()} 调）。
 * 音频线程只往 {@link SpectrumBands} / 引擎快照里写数据，这一层再把它拷出来。
 */
public final class StudioBridge {

    /** 频响曲线的点数（对数网格）。 */
    private static final int CURVE_POINTS = 240;

    /** 频响曲线的 dB 范围（画布上下限）。 */
    public static final float CURVE_MIN_DB = -24f;
    public static final float CURVE_MAX_DB = 24f;

    /** 可选的段数量上限（与 {@code StudioRack.MAX_BANDS} 一致）。 */
    public static final int MAX_BANDS = StudioRack.MAX_BANDS;

    private final SettingsCore settings;
    private final AudioBackend backend;

    /** 曲线的频率网格（对数等分）；界面用它把点画到对数坐标上。 */
    private final double[] grid = EqResponse.logGrid(CURVE_POINTS);

    // ---------------- 界面读的属性 ----------------

    /** 当前选中的机架：0 主总线 / 1 出场曲 / 2 进场曲。 */
    public final Property<Integer> rack = new Property<>(0);

    /** 当前机架的名字（界面标题用）。 */
    public final Property<String> rackName = new Property<>("主总线");

    /** 整条链的总闸。 */
    public final Property<Boolean> chainEnabled = new Property<>(false);

    /** 当前机架的段表（每个元素是一条段的只读视图）。 */
    public final Property<List<BandRow>> bands =
            new Property<>(Collections.<BandRow>emptyList());

    /** 当前机架的频响曲线（dB，长度 {@value #CURVE_POINTS}）。 */
    public final Property<List<Float>> curveDb =
            new Property<>(Collections.<Float>emptyList());

    /** 实时频谱（dBFS，长度 {@value SpectrumBands#BANDS}）。 */
    public final Property<List<Float>> spectrumDb =
            new Property<>(Collections.<Float>emptyList());

    /**
     * 修订号：每发布一次新的频谱 +1。
     *
     * <p>界面为什么需要一个数字、而不是直接盯着 {@link #spectrumDb} 的<strong>值</strong>：
     * qml4j 的 Canvas 只在 {@code requestPaint()} 之后重画，而 Canvas 里
     * {@code Connections { target: bridge; function onSpectrumDbChanged() {...} }} 这种
     * 「连到 Java 属性变化」的写法<strong>是静默失效的</strong>——引擎的 Connections 只认
     * 反射找得到的**信号字段**（{@code spectrumDbChanged}），而 Java 的 {@link Property}
     * 没有那个字段，处理器被丢掉。结果就是画布只在创建那一帧画过一次，「实时频谱」永远不动。
     * 绑一个会变的数字走的是正常的属性依赖，见 {@code EqCurveEditor} 里的 {@code dataRev}。
     */
    public final Property<Integer> spectrumRev = new Property<>(Integer.valueOf(0));

    /** 修订号：整份状态（段表/曲线/模块）每重读一次 +1，用途同 {@link #spectrumRev}。 */
    public final Property<Integer> refreshRev = new Property<>(Integer.valueOf(0));

    /** 当前机架的段数上限（界面禁用「加一段」时用）。 */
    public final Property<Integer> maxBands = new Property<>(MAX_BANDS);

    /** 曲线的 dB 上下限（界面画网格）。 */
    public final Property<Float> curveMinDb = new Property<>(CURVE_MIN_DB);

    public final Property<Float> curveMaxDb = new Property<>(CURVE_MAX_DB);

    // 模块参数（都是「当前机架」的）
    public final Property<Boolean> preampEnabled = new Property<>(false);
    public final Property<Float> preampDb = new Property<>(0f);
    public final Property<Boolean> loudness = new Property<>(false);
    public final Property<Boolean> pitchOn = new Property<>(false);
    public final Property<Float> pitchSemitone = new Property<>(0f);
    public final Property<Float> pitchCents = new Property<>(0f);
    public final Property<Float> pitchMix = new Property<>(1f);
    public final Property<Boolean> gateOn = new Property<>(false);
    public final Property<Float> gateThresholdDb = new Property<>(-45f);
    public final Property<Boolean> compOn = new Property<>(false);
    public final Property<Float> compThresholdDb = new Property<>(-18f);
    public final Property<Float> compRatio = new Property<>(3f);
    public final Property<Float> compAttackMs = new Property<>(10f);
    public final Property<Float> compReleaseMs = new Property<>(120f);
    public final Property<Float> compMakeupDb = new Property<>(0f);
    public final Property<Boolean> limiterOn = new Property<>(false);
    public final Property<Float> limiterCeilingDb = new Property<>(-1f);
    public final Property<Boolean> stereoOn = new Property<>(false);
    public final Property<Float> stereoWidth = new Property<>(1f);
    public final Property<Float> bassMonoHz = new Property<>(0f);
    public final Property<Float> crossfeed = new Property<>(0f);
    public final Property<Boolean> saturationOn = new Property<>(false);
    public final Property<Float> saturationDriveDb = new Property<>(6f);
    public final Property<Integer> saturationCurve = new Property<>(0);
    public final Property<Integer> saturationOversample = new Property<>(1);

    /** 内置曲线（预设）的名字与当前下标。 */
    public final Property<List<String>> presetNames =
            new Property<>(Arrays.asList(StudioPresets.labels()));
    public final Property<Integer> presetIndex = new Property<>(0);

    /** 段类型与声道的名字（下拉用）。 */
    public final Property<List<String>> bandTypeNames =
            new Property<>(Arrays.asList(BandType.labels()));
    public final Property<List<String>> channelNames =
            new Property<>(Arrays.asList(ChannelTarget.labels()));
    /** 染色曲线的档名（与设置页那个下拉同一份文案，来自 {@code SaturationParams.Curve.labels()}）。 */
    public final Property<List<String>> saturationCurveNames =
            new Property<>(Arrays.asList(
                    dev.t1m3.qplayer.audio.studio.SaturationParams.Curve.labels()));

    // 表头（P8）
    public final Property<Float> meterPeakDb = new Property<>(-120f);
    public final Property<Float> meterTruePeakDb = new Property<>(-120f);
    public final Property<Float> meterLufsM = new Property<>(-120f);
    public final Property<Float> meterLufsS = new Property<>(-120f);
    public final Property<Float> meterLufsI = new Property<>(-120f);
    public final Property<Float> meterCorrelation = new Property<>(0f);
    public final Property<String> meterCorrelationText = new Property<>("");
    public final Property<Float> gainReductionDb = new Property<>(0f);

    // 链的状态
    public final Property<String> pathStateText = new Property<>("");
    public final Property<Boolean> bitPerfect = new Property<>(false);
    /** 这条链引入的延迟（毫秒，三台机架里最大的那一条路）。 */
    public final Property<Float> latencyMs = new Property<>(0f);
    public final Property<String> chainSummary = new Property<>("");

    // 控制室（P4）
    public final Property<String> transitionSummary = new Property<>("");
    public final Property<Float> transitionProgress = new Property<>(0f);
    public final Property<Boolean> inTransition = new Property<>(false);
    public final Property<Boolean> manualCueOn = new Property<>(false);
    public final Property<Float> manualOutroSecs = new Property<>(0f);
    public final Property<Float> manualIntroSecs = new Property<>(0f);
    /** 「重做这次过渡」的结果提示（一句话，可为空）。 */
    public final Property<String> actionNotice = new Property<>("");

    /** 由宿主注入的回调：手动点位改完之后请求引擎重做准备（可为 null）。 */
    private Runnable reprepareRequest;

    private final float[] spectrumScratch = new float[SpectrumBands.BANDS];

    public StudioBridge(SettingsCore settings, AudioBackend backend) {
        this.settings = settings;
        this.backend = backend;
    }

    /** 宿主注入「重做这次过渡」的动作（引擎那边的一条命令）。 */
    public void setReprepareRequest(Runnable action) {
        this.reprepareRequest = action;
    }

    // ==================================================================
    // 读：把当前状态刷成界面能画的形状
    // ==================================================================

    /** 从设置里重读全部状态。页面打开时、以及每一次改动之后调它。 */
    public void refresh() {
        chainEnabled.set(Boolean.valueOf(settings.bool(SettingsCatalog.STUDIO_PRESET_KEY)
                || hasCustomJson() || anyModuleRowOn()));
        presetIndex.set(Integer.valueOf(
                settings.intOf(SettingsCatalog.STUDIO_PRESET_KEY)));
        refreshRack();
        refreshModules();
        refreshMeters();
        refreshRev.set(Integer.valueOf(refreshRev.peek().intValue() + 1));
    }

    /** 只刷新实时数据（每帧一次，由 {@code PlayerController.pump()} 调）。 */
    public void tick() {
        // 频谱（64 条，音频线程已经在算）
        int got = backend.studioSpectrum(spectrumScratch);
        if (got > 0) {
            List<Float> out = new ArrayList<Float>(got);
            for (int i = 0; i < got; i++) {
                out.add(Float.valueOf(spectrumScratch[i]));
            }
            spectrumDb.set(out);
            spectrumRev.set(Integer.valueOf(spectrumRev.peek().intValue() + 1));
        }
        refreshMeters();
    }

    /** 表头与控制室（都是引擎已经发布的快照，读一次拷一次）。 */
    private void refreshMeters() {
        // 后端**可能返回 null**（代理出来的假后端、或某个宿主没实现那条 default 方法）：
        // 如实退回「静音」那一份，而不是让一次读数把整个页面打崩。
        StudioMeters.Snapshot snap = backend.studioMeters();
        if (snap == null) {
            snap = StudioMeters.Snapshot.SILENT;
        }
        meterPeakDb.set(Float.valueOf((float) snap.peakDb()));
        meterTruePeakDb.set(Float.valueOf((float) snap.truePeakDb()));
        meterLufsM.set(Float.valueOf((float) snap.lufsM));
        meterLufsS.set(Float.valueOf((float) snap.lufsS));
        meterLufsI.set(Float.valueOf((float) snap.lufsI));
        meterCorrelation.set(Float.valueOf((float) snap.correlation));
        meterCorrelationText.set(snap.correlationLabel());
        gainReductionDb.set(Float.valueOf(backend.studioGainReductionDb()));

        TransitionSnapshot t = backend.transitionSnapshot();
        if (t == null) {
            t = TransitionSnapshot.IDLE;
        }
        transitionSummary.set(t.summary);
        transitionProgress.set(Float.valueOf(t.progress));
        inTransition.set(Boolean.valueOf(t.inTransition));
        pathStateText.set(t.pathState.label());
        bitPerfect.set(Boolean.valueOf(t.pathState.isBitPerfect()));
        manualCueOn.set(Boolean.valueOf(settings.bool(SettingsCatalog.AUTOMIX_MANUAL_CUE_KEY)));
        manualOutroSecs.set(Float.valueOf(settings.intOf(
                SettingsCatalog.AUTOMIX_MANUAL_OUTRO_SECS_KEY)));
        manualIntroSecs.set(Float.valueOf(settings.intOf(
                SettingsCatalog.AUTOMIX_MANUAL_INTRO_SECS_KEY)));
    }

    /** 当前机架的段表 + 频响曲线。 */
    private void refreshRack() {
        StudioRacks racks = currentRacks();
        StudioRack r = rackOf(racks, rack.peek().intValue());
        rackName.set(nameOf(rack.peek().intValue()));
        chainEnabled.set(Boolean.valueOf(r.enabled));
        List<BandRow> rows = new ArrayList<BandRow>();
        for (int i = 0; i < r.bands.size(); i++) {
            rows.add(new BandRow(i, r.bands.get(i)));
        }
        bands.set(rows);
        double[] db = EqResponse.db(r.bands, 48_000, grid, r.preampDb);
        List<Float> curve = new ArrayList<Float>(db.length);
        for (double v : db) {
            curve.add(Float.valueOf((float) v));
        }
        curveDb.set(curve);
        // 这条链的延迟（三台里最大的一条路：机架 + 主总线）
        float ms = Math.max(r.latencyFrames(48_000), 0) * 1000f / 48_000f;
        latencyMs.set(Float.valueOf(ms));
        chainSummary.set(r.toString());
    }

    /** 当前机架的模块参数（读设置行里的值——那是最后叠加、真正生效的那一份）。 */
    private void refreshModules() {
        StudioRacks racks = currentRacks();
        StudioRack r = rackOf(racks, rack.peek().intValue());
        preampEnabled.set(Boolean.valueOf(Math.abs(r.preampDb) > 1e-6));
        preampDb.set(Float.valueOf((float) r.preampDb));
        loudness.set(Boolean.valueOf(r.loudness));
        pitchOn.set(Boolean.valueOf(r.pitch.enabled));
        pitchSemitone.set(Float.valueOf(r.pitch.semitone));
        pitchCents.set(Float.valueOf((float) r.pitch.cents));
        pitchMix.set(Float.valueOf((float) r.pitch.mix));
        gateOn.set(Boolean.valueOf(r.dynamics.gateOn));
        gateThresholdDb.set(Float.valueOf((float) r.dynamics.gateThresholdDb));
        compOn.set(Boolean.valueOf(r.dynamics.compOn));
        compThresholdDb.set(Float.valueOf((float) r.dynamics.compThresholdDb));
        compRatio.set(Float.valueOf((float) r.dynamics.compRatio));
        compAttackMs.set(Float.valueOf((float) r.dynamics.compAttackMs));
        compReleaseMs.set(Float.valueOf((float) r.dynamics.compReleaseMs));
        compMakeupDb.set(Float.valueOf((float) r.dynamics.compMakeupDb));
        limiterOn.set(Boolean.valueOf(r.dynamics.limiterOn));
        limiterCeilingDb.set(Float.valueOf((float) r.dynamics.limiterCeilingDb));
        stereoOn.set(Boolean.valueOf(r.stereo.enabled));
        stereoWidth.set(Float.valueOf((float) r.stereo.width));
        bassMonoHz.set(Float.valueOf((float) r.stereo.bassMonoHz));
        crossfeed.set(Float.valueOf((float) r.stereo.crossfeed));
        saturationOn.set(Boolean.valueOf(r.saturation.enabled));
        saturationDriveDb.set(Float.valueOf((float) r.saturation.driveDb));
        saturationCurve.set(Integer.valueOf(r.saturation.curve.ordinal()));
        saturationOversample.set(Integer.valueOf(r.saturation.oversample));
    }

    // ==================================================================
    // 写：编辑 → 设置
    //
    // **每一条写操作都以 `refresh()` 收尾**，不用 `refreshRack()` / `refreshMeters()`
    // 那种「只刷一部分」的收尾。原因是这一页的控件既读状态又改状态：
    // 只刷一部分时，改了之后**界面读到的还是改之前的那一份**——2026-09-26 实测到的表现是
    // 「点开染色之后，下面那两条（曲线 / 过采样）还是灰的、点不动」（`saturationOn` 没更新，
    // 于是那两行的 MouseArea 一直是 disabled），以及「拖完节点曲线不重画」
    // （画布靠 `refreshRev` 触发重画，而它只在 `refresh()` 里 +1）。
    // 一次编辑多算几趟列表是用户点一下的代价，不值得为它换回「静默过期」。
    // ==================================================================

    /** 换机架（0 主总线 / 1 出场曲 / 2 进场曲）。 */
    public void selectRack(int index) {
        rack.set(Integer.valueOf(Math.max(0, Math.min(2, index))));
        refresh();
    }

    /** 整条链的总闸。 */
    public void setChainEnabled(boolean on) {
        writeBandRack(rackOf(currentRacks(), rack.peek().intValue()).withEnabled(on));
        refresh();
    }

    /** 加一段（默认落在 1 kHz、增益 0、Q 1；已满则什么都不做）。 */
    public void addBand(double freqHz) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (r.bands.size() >= MAX_BANDS) {
            return;
        }
        List<EqBand> next = new ArrayList<EqBand>(r.bands);
        double f = Math.max(EqBand.MIN_FREQ, Math.min(EqBand.MAX_FREQ,
                freqHz > 0 ? freqHz : 1000.0));
        next.add(EqBand.peak(f, 0.0, 1.0));
        writeBandRack(r.withBands(next).withEnabled(true));
        refresh();
    }

    /** 删掉一段。 */
    public void removeBand(int index) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (index < 0 || index >= r.bands.size()) {
            return;
        }
        List<EqBand> next = new ArrayList<EqBand>(r.bands);
        next.remove(index);
        writeBandRack(r.withBands(next));
        refresh();
    }

    /** 拖节点：改频率与增益（界面在拖动中连续调它）。 */
    public void setBandFreqGain(int index, double freqHz, double gainDb) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (index < 0 || index >= r.bands.size()) {
            return;
        }
        EqBand b = r.bands.get(index).withFreq(freqHz).withGain(gainDb);
        writeBandRack(r.withBand(index, b));
        refresh();
    }

    /** 滚轮：改 Q。 */
    public void setBandQ(int index, double q) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (index < 0 || index >= r.bands.size()) {
            return;
        }
        writeBandRack(r.withBand(index, r.bands.get(index).withQ(q)));
        refresh();
    }

    /** 双击：换类型（在八种之间循环）。 */
    public void cycleBandType(int index) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (index < 0 || index >= r.bands.size()) {
            return;
        }
        EqBand b = r.bands.get(index);
        BandType[] all = BandType.values();
        BandType next = all[(b.type.ordinal() + 1) % all.length];
        writeBandRack(r.withBand(index, b.withType(next)));
        refresh();
    }

    /** 换声道路由（立体声/左/右/中/侧）。 */
    public void setBandChannel(int index, int channelIndex) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (index < 0 || index >= r.bands.size()) {
            return;
        }
        writeBandRack(r.withBand(index, r.bands.get(index)
                .withChannel(ChannelTarget.fromJson(
                        ChannelTarget.values()[Math.max(0, Math.min(
                                ChannelTarget.values().length - 1, channelIndex))].json()))));
        refresh();
    }

    /** 单段的开关。 */
    public void setBandEnabled(int index, boolean on) {
        StudioRack r = rackOf(currentRacks(), rack.peek().intValue());
        if (index < 0 || index >= r.bands.size()) {
            return;
        }
        writeBandRack(r.withBand(index, r.bands.get(index).withEnabled(on)));
        refresh();
    }

    /** 预设（内置曲线）：写那一行，并把自定义 JSON 清空（否则它会盖在预设上）。 */
    public void applyPreset(int index) {
        settings.setValue(SettingsCatalog.STUDIO_PRESET_KEY, Integer.valueOf(index));
        settings.setValue(SettingsCatalog.STUDIO_CUSTOM_KEY, "");
        presetIndex.set(Integer.valueOf(index));
        refresh();
    }

    /** 前级增益。 */
    public void setPreamp(float db) {
        settings.setValue(SettingsCatalog.STUDIO_GAIN_KEY, Integer.valueOf(Math.round(db)));
        refresh();
    }

    /** 等响度补偿。 */
    public void setLoudness(boolean on) {
        settings.setValue(SettingsCatalog.STUDIO_LOUDNESS_KEY, Boolean.valueOf(on));
        refresh();
    }

    /** 音高（半音 / 音分 / 干湿）。 */
    public void setPitch(boolean on, float semitone, float cents, float mix) {
        settings.setValue(SettingsCatalog.STUDIO_PITCH_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.STUDIO_PITCH_SEMITONE_KEY,
                Integer.valueOf(Math.round(semitone)));
        settings.setValue(SettingsCatalog.STUDIO_PITCH_CENTS_KEY, Integer.valueOf(Math.round(cents)));
        settings.setValue(SettingsCatalog.STUDIO_PITCH_MIX_KEY,
                Integer.valueOf(Math.round(mix * 100f)));
        refresh();
    }

    /** 噪声门。 */
    public void setGate(boolean on, float thresholdDb) {
        settings.setValue(SettingsCatalog.STUDIO_GATE_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.STUDIO_GATE_THRESHOLD_KEY,
                Integer.valueOf(Math.round(thresholdDb)));
        refresh();
    }

    /** 压缩器。 */
    public void setCompressor(boolean on, float thresholdDb, float ratio, float attackMs,
                              float releaseMs, float makeupDb) {
        settings.setValue(SettingsCatalog.STUDIO_COMP_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.STUDIO_COMP_THRESHOLD_KEY,
                Integer.valueOf(Math.round(thresholdDb)));
        settings.setValue(SettingsCatalog.STUDIO_COMP_RATIO_KEY, Integer.valueOf(Math.round(ratio)));
        settings.setValue(SettingsCatalog.STUDIO_COMP_ATTACK_KEY,
                Integer.valueOf(Math.round(attackMs)));
        settings.setValue(SettingsCatalog.STUDIO_COMP_RELEASE_KEY,
                Integer.valueOf(Math.round(releaseMs)));
        settings.setValue(SettingsCatalog.STUDIO_COMP_MAKEUP_KEY,
                Integer.valueOf(Math.round(makeupDb)));
        refresh();
    }

    /** 限制器。 */
    public void setLimiter(boolean on, float ceilingDb) {
        settings.setValue(SettingsCatalog.STUDIO_LIMITER_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.STUDIO_LIMITER_CEILING_KEY,
                Integer.valueOf(Math.round(ceilingDb)));
        refresh();
    }

    /** 立体声工具。 */
    public void setStereo(boolean on, float widthPercent, float bassMonoHz, float crossfeedPercent) {
        settings.setValue(SettingsCatalog.STUDIO_STEREO_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.STUDIO_STEREO_WIDTH_KEY,
                Integer.valueOf(Math.round(widthPercent)));
        settings.setValue(SettingsCatalog.STUDIO_STEREO_BASS_MONO_KEY,
                Integer.valueOf(Math.round(bassMonoHz)));
        settings.setValue(SettingsCatalog.STUDIO_STEREO_CROSSFEED_KEY,
                Integer.valueOf(Math.round(crossfeedPercent)));
        refresh();
    }

    /** 染色。 */
    public void setSaturation(boolean on, float driveDb, int curveIndex, int oversample) {
        settings.setValue(SettingsCatalog.STUDIO_SATURATION_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.STUDIO_SATURATION_DRIVE_KEY,
                Integer.valueOf(Math.round(driveDb)));
        settings.setValue(SettingsCatalog.STUDIO_SATURATION_CURVE_KEY, Integer.valueOf(curveIndex));
        settings.setValue(SettingsCatalog.STUDIO_SATURATION_OVERSAMPLE_KEY,
                Integer.valueOf(oversample));
        refresh();
    }

    /** 手动点位（P4 控制室）：两个数都是秒，0 = 不覆盖那一侧。 */
    public void setManualCue(boolean on, float outroSecs, float introSecs) {
        settings.setValue(SettingsCatalog.AUTOMIX_MANUAL_CUE_KEY, Boolean.valueOf(on));
        settings.setValue(SettingsCatalog.AUTOMIX_MANUAL_OUTRO_SECS_KEY,
                Integer.valueOf(Math.round(outroSecs)));
        settings.setValue(SettingsCatalog.AUTOMIX_MANUAL_INTRO_SECS_KEY,
                Integer.valueOf(Math.round(introSecs)));
        refresh();
    }

    /**
     * 「重做这次过渡」：请引擎把已经备好的结论作废、重新准备一次。
     *
     * <p>它在<strong>过渡进行中什么都不做</strong>（引擎那边会拒绝——撕开正在混的那几秒
     * 比点位晚一拍糟得多），所以这里如实把结果写进 {@link #actionNotice}。
     */
    public void reprepareTransition() {
        Runnable action = reprepareRequest;
        if (action == null) {
            actionNotice.set("这个宿主没有接上引擎，重做无效");
            return;
        }
        action.run();
        actionNotice.set("已请求重做：新的点位会在这一曲的结尾生效（过渡进行中不打断）");
    }

    /** 清掉那条提示（界面显示几秒后自己调）。 */
    public void clearNotice() {
        actionNotice.set("");
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 设置里那几行组合出来的三台机架（= 引擎此刻真正在用的那一份）。 */
    private StudioRacks currentRacks() {
        int preset = settings.intOf(SettingsCatalog.STUDIO_PRESET_KEY);
        int target = settings.intOf(SettingsCatalog.STUDIO_RACK_KEY);
        int gain = settings.intOf(SettingsCatalog.STUDIO_GAIN_KEY);
        boolean loud = settings.bool(SettingsCatalog.STUDIO_LOUDNESS_KEY);
        String custom = settings.str(SettingsCatalog.STUDIO_CUSTOM_KEY);
        StudioRacks base = custom == null || custom.trim().isEmpty()
                ? StudioRacks.EMPTY : StudioJson.fromJson(custom);
        boolean presetOff = preset <= StudioPresets.OFF;
        StudioRack presetRack = StudioPresets.at(preset).rack;
        int[] targets = target == 3 ? new int[]{0, 1, 2} : new int[]{target < 0 ? 0 : target};
        boolean rowsAsk = Math.abs(gain) > 0 || loud;
        for (int t : targets) {
            StudioRack r = presetOff ? rackOf(base, t) : presetRack;
            r = r.withPreamp(gain).withLoudness(loud, Math.max(r.loudnessAmount, 0.8));
            r = r.withPitch(PitchParams.of(
                    settings.intOf(SettingsCatalog.STUDIO_PITCH_SEMITONE_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_PITCH_CENTS_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_PITCH_MIX_KEY) / 100.0)
                    .withEnabled(settings.bool(SettingsCatalog.STUDIO_PITCH_KEY)));
            r = r.withDynamics(dev.t1m3.qplayer.audio.engine.AutomixSettings.composeDynamics(
                    settings.bool(SettingsCatalog.STUDIO_GATE_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_GATE_THRESHOLD_KEY),
                    settings.bool(SettingsCatalog.STUDIO_COMP_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_COMP_THRESHOLD_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_COMP_RATIO_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_COMP_ATTACK_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_COMP_RELEASE_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_COMP_MAKEUP_KEY),
                    settings.bool(SettingsCatalog.STUDIO_LIMITER_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_LIMITER_CEILING_KEY)));
            r = r.withSaturation(dev.t1m3.qplayer.audio.engine.AutomixSettings.composeSaturation(
                    settings.bool(SettingsCatalog.STUDIO_SATURATION_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_SATURATION_DRIVE_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_SATURATION_CURVE_KEY),
                    Math.max(1, settings.intOf(SettingsCatalog.STUDIO_SATURATION_OVERSAMPLE_KEY))));
            r = r.withStereo(dev.t1m3.qplayer.audio.engine.AutomixSettings.composeStereo(
                    settings.bool(SettingsCatalog.STUDIO_STEREO_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_STEREO_WIDTH_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_STEREO_BASS_MONO_KEY),
                    settings.intOf(SettingsCatalog.STUDIO_STEREO_CROSSFEED_KEY)));
            if (rowsAsk || r.dynamics.active() || r.saturation.active() || r.stereo.active()
                    || r.pitch.active()) {
                r = r.withEnabled(true);
            }
            base = withRack(base, t, r);
        }
        return base;
    }

    /**
     * 把「当前机架的段表」写回设置。
     *
     * <p><strong>两条落点里的一条</strong>（见类注释）：段表进自定义 JSON，并把「曲线」那一行
     * 置为「关」——否则那条曲线会盖在上面，用户拖了没反应。
     */
    private void writeBandRack(StudioRack updated) {
        StudioRacks racks = currentRacks();
        racks = withRack(racks, rack.peek().intValue(), updated);
        settings.setValue(SettingsCatalog.STUDIO_CUSTOM_KEY, StudioJson.toJson(racks));
        if (settings.intOf(SettingsCatalog.STUDIO_PRESET_KEY) > StudioPresets.OFF) {
            settings.setValue(SettingsCatalog.STUDIO_PRESET_KEY,
                    Integer.valueOf(StudioPresets.OFF));
            presetIndex.set(Integer.valueOf(StudioPresets.OFF));
            actionNotice.set("改动已固化到自定义链（内置曲线随之置为「关」）");
        }
    }

    private boolean hasCustomJson() {
        String json = settings.str(SettingsCatalog.STUDIO_CUSTOM_KEY);
        return json != null && !json.trim().isEmpty();
    }

    private boolean anyModuleRowOn() {
        return settings.intOf(SettingsCatalog.STUDIO_GAIN_KEY) != 0
                || settings.bool(SettingsCatalog.STUDIO_LOUDNESS_KEY)
                || settings.bool(SettingsCatalog.STUDIO_PITCH_KEY)
                || settings.bool(SettingsCatalog.STUDIO_COMP_KEY)
                || settings.bool(SettingsCatalog.STUDIO_LIMITER_KEY)
                || settings.bool(SettingsCatalog.STUDIO_GATE_KEY)
                || settings.bool(SettingsCatalog.STUDIO_STEREO_KEY)
                || settings.bool(SettingsCatalog.STUDIO_SATURATION_KEY);
    }

    private static StudioRack rackOf(StudioRacks racks, int target) {
        if (target == 1) {
            return racks.deckA;
        }
        if (target == 2) {
            return racks.deckB;
        }
        return racks.master;
    }

    private static StudioRacks withRack(StudioRacks racks, int target, StudioRack r) {
        if (target == 1) {
            return racks.withDeckA(r);
        }
        if (target == 2) {
            return racks.withDeckB(r);
        }
        return racks.withMaster(r);
    }

    private static String nameOf(int target) {
        if (target == 1) {
            return "出场曲（正在放的）";
        }
        if (target == 2) {
            return "进场曲（即将进来的）";
        }
        return "主总线";
    }

    /** 频响曲线的频率网格（Hz）——界面画对数坐标时要它。 */
    public List<Float> curveFreqs() {
        List<Float> out = new ArrayList<Float>(grid.length);
        for (double f : grid) {
            out.add(Float.valueOf((float) f));
        }
        return out;
    }

    /**
     * 一段均衡在界面上的视图（只读）。
     *
     * <p>做成一个「扁」的对象而不是把 {@link EqBand} 直接给 QML：QML 需要的是
     * 已经排好版的文字（{@code "1.2 kHz"}）与可以直接用的数值，而不是让界面自己去
     * 拼单位、猜枚举。这一层的存在也把「界面改动 → 参数」收在一个地方。
     */
    public static final class BandRow {

        public final int index;
        public final double freqHz;
        public final double gainDb;
        public final double q;
        public final int typeIndex;
        public final String typeLabel;
        public final int channelIndex;
        public final String channelLabel;
        public final boolean enabled;
        public final String freqText;
        public final String gainText;
        public final String qText;
        /** 这一段是不是「全通 / 高通 / 低通」这类不看增益的类型（界面据此隐藏增益）。 */
        public final boolean gainRelevant;

        BandRow(int index, EqBand band) {
            this.index = index;
            this.freqHz = band.freqHz;
            this.gainDb = band.gainDb;
            this.q = band.q;
            this.typeIndex = band.type.ordinal();
            this.typeLabel = band.type.label();
            this.channelIndex = band.channel.ordinal();
            this.channelLabel = band.channel.label();
            this.enabled = band.enabled;
            this.freqText = band.freqHz >= 1000.0
                    ? String.format(java.util.Locale.ROOT, "%.2f kHz", band.freqHz / 1000.0)
                    : String.format(java.util.Locale.ROOT, "%.0f Hz", band.freqHz);
            this.gainText = String.format(java.util.Locale.ROOT, "%+.1f dB", band.gainDb);
            this.qText = String.format(java.util.Locale.ROOT, "Q %.2f", band.q);
            this.gainRelevant = band.type == BandType.PEAK || band.type == BandType.LOW_SHELF
                    || band.type == BandType.HIGH_SHELF;
        }

        @Override
        public String toString() {
            return index + ": " + typeLabel + " " + freqText + " " + gainText + " " + qText;
        }
    }
}
