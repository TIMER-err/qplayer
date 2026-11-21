# 离线 Mashup（混搭重混）生成：完整实现方案

状态：**设计稿，未实现**。日期 2026-11-21。基线 `master` @ `3cbb3faa`（automix 分支合并后）。

> 术语：本文的 **mashup** 指「把 A 的人声铺在 B 的伴奏上，撑一整首歌」；
> 仓库里已有的 **融合（Fusion）** 指「我们的接法与参考包模板同台排序」，两者不是一回事，
> 代码里不要混用 `Fusion` 这个名字。

---

## 0. 一句话结论

**有戏，而且不必动实时播放链路**：拍点、保调变速（WSOLA）、调性、响度、限幅、分轨接缝、
整曲扫描这些底层件仓库里都有，mashup 真正缺的三样是
**整曲（分窗）分轨**、**全程时间轴（TimeMap）**、**产品级 WAV 导出**。
方案是新增一个**纯离线**的 `audio/mashup` 包，单向依赖 `audio/mix`，实时链路零改动。

代价要先说清：**AI 分轨（htdemucs）在本仓库是死路径**——没有 ONNX 运行时依赖、没有
`Opener` 的生产实现、没有切窗拼接代码（详见 §3 G1）。所以第一版只能跑 **DSP 分轨**，
它的定位是「卡拉 OK 级近似」，人声轨会带底鼓与贝斯（§7.6 给了频段再平衡的补救），
听感上限明显低于 Demucs 那类真分轨。AI 档作为质量升级单独一个里程碑。

---

## 1. 目标与非目标

### 目标（按优先级）

1. 选两首歌（A 出人声、B 出伴奏），后台生成一段**音高对齐、拍点对齐、电平对齐**的
   mashup，导出为本地音频文件。
2. 可取消、有进度、**失败说清在哪一步**（照 `PrepareStage` 的一句话口径）。
3. 同一对歌 + 同一参数 ⇒ **结果可复现**（plan 落盘，同 hash 同 PCM）。
4. 桌面优先；安卓只播放已生成的成品，不本地跑分轨。

### 非目标（这一期明确不做）

- 不做实时 mashup 播放（不把 `Transition` 的窗口拉到整首歌）。
- 不做 remix（鼓组替换、切片重排）。
- 不做多于 2 首的叠加。
- 不做 mp3/flac 编码（先 WAV，见 §7.9）。
- 不改 `Transition` / `Director` / `AutoCueTransition` 的任何行为。

---

## 2. 可复用的既有件（全部是仓库真实代码）

### 2.1 分析侧

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 整曲扫描（一首歌只解一遍，流式） | `AutoCue.scanInternal` → `AutoCue.Envelope` | 一次遍历同时产 rms / 三条分频带 / 中置显著度 / 1 kHz 波形 / 谱通量 / chroma / tempo / 两个结构点；**这是 mashup 应该直接复用的特征源** |
| 拍点序列 | `Envelope.beatsIn(fromSecs, toSecs)` → `double[]`（秒） | 由 `Envelope.tempo` 的恒定栅格推出来的拍时刻，**不是**跟踪到的逐拍列表 |
| 拍点细化到样本级 | `BeatRefine.refineFrame(mono, rate, approxFrame)` | 在近似拍点 ±40 ms 内找起音峰；找不到就原样返回（不猜） |
| 桥段/结构点 | `Envelope.structureIntroStartSecs / structureOutroStartSecs` | 只有两个点，精度"差一档"（代码自承"最低权"），够当边界提示，不够当段落标注 |
| 人声活动（代理 VAD） | `Envelope.voiceBasicMask() / voiceActiveMask()` | 中频 + 中置门；**包内可见**，mashup 要用需加一个 public 访问器（§8） |
| 速度 | `Analysis.Tempo{bpm, periodFrames, phaseFrames, confidence}` | `periodFrames` 是**采样帧**单位；`nextBeatAfter/beatOffsetBy` 可直接用 |
| 调性 | `Harmonic.match(...)`；`ReferenceKeyEstimate.Estimate{tonic, mode, confidence}` | **没有调名、没有 Camelot**；`Match` 里也**没有置信度**字段，置信度要用 `ReferenceKeyEstimate` |
| 响度 | `Lufs` | 必须用它：`Envelope.rms` 按 95 分位归一化，**跨曲不可比** |

### 2.2 处理与渲染侧

| 能力 | 入口 | 约束 |
| --- | --- | --- |
| 整曲解码到内存 | `Automix.readStereoWindow(source, headers, start, frames, outRate)`；`Automix.readMono(...)` | 给整段长度即等价整曲解码；约 384 KB/s（48k 立体声 f32） |
| 保调变速（WSOLA） | `TempoBridge.stretch / stretchRamp / stretchConverge` | 速度硬限 **0.75–1.33**；一次 OLA 走完、误差不累积；**分段拼接会留硬接缝**（类注释实测） |
| 重采样 | `Resampler`（多相窗函数 sinc，`push/pull/drain`） | 用于移调（§7.6） |
| 限幅 | `TransitionLimiter`（-1 dBFS、80 ms 释放、块内预扫、零延迟） | 本身不限窗口，可直接当成品最后一级 |
| 配对阈值 | `MixPaths`（`STRICT_TEMPO=0.06` / `LOOSE_TEMPO=0.12` / `SAFE_PITCH_SEMITONES=2` / `MAX_LOUDNESS_DELTA_DB=6`） | **纯函数表，无 I/O**；阈值可直接沿用 |
| DSP 分轨 | `DspStemSeparator` | STFT 2048/512、只抑制 100 Hz–5.2 kHz 的 center；②**不可重入**、③整段 `double` 累加器约 1.4 MB/s 素材、④1 个实例 4 线程并行会 4 倍分配 |
| 分轨接缝 | `StemSeparator.separate(L, R, rate)` → `StemSet`（四条轨、长度必须相等） | 契约允许任意长度；44.1k 立体声的重采样是**实现的义务** |
| 缓存纪律 | `BeatGridSidecar`（魔数+版本、内容 sha256、坏文件当空、未知键忽略、读永不抛） | ⚠️ 它的 `write` **在生产路径没有调用点**，只有测试——"既有约定"实际是"读路径 + 可测格式" |
| 目录分工 | `AppDirs.modelsDir()`（不淘汰）/ `DiskCache`（可重建、LRU+体积+pin） | 108 MB 模型不能放 cache；分轨缓存必须可淘汰 |
| 导出接缝（宿主） | `PlayerController.FileSavePicker` → 桌面 `DesktopFilePicker.saveAudio`、安卓 `QPlayerActivity.saveAudioFile`（SAF `ACTION_CREATE_DOCUMENT` + `AudioSaveRequest` 暂存/复制） | ⚠️ **死接缝**：两端宿主都装了实现，`PlayerController` 里**没有调用者**（历史调用随 `3cbb3faa` 丢失） |
| 长任务模板 | `SettingsCore.modelWorker`（单线程 daemon）+ 200 ms 节流 + `post()` 回渲染线程 + `Property<Long>` revision | 进度/取消/失败文案都有现成形状，QML 只重算一行 |

---

## 3. 真实缺口（不要当成已有）

| # | 缺口 | 证据 | 影响 |
| --- | --- | --- | --- |
| **G1** | **AI 分轨只有接缝，没有实现** | 两端宿主都无 `onnxruntime` 依赖；`OnnxInferSession` 在 HEAD 不存在（只被 `BeatDetector.java:71` 的注释引用）；`StemModels.install(...)` 只在测试里被调用 ⇒ `StemModels.available()` 恒 false；desktop `Main.java:134-135` 明写"去人声走 DSP 兜底" | AI 档是**死路径**；设置里能下 108 MB 权重却无人能用，且降级提示会说错原因（"模型不在磁盘上"，真实原因是 `opener == null`） |
| **G2** | 变速只有窗口级 | `TempoRamp` 只设计用于 **1–3 s**（`W = min(用户值, |1−ratio|/2.5%)`，实测 2.51 s）；`AutoCueTransition` 的变速深度硬限 **4%**；`BeatTracker` 假设全曲恒速（残差 >10% 拍长就判"不可信"），且**只返回 Tempo，不返回拍点列表** | 3 分钟对不齐；需要 TimeMap（§7.4） |
| **G3** | 没有产品级 WAV 写出器，**导出门本身也是死的** | 两端都只有解码器（`Mp3/Flac/Aac/Ogg/WavPcmSource` 全是 PcmSource）；无 `MediaMuxer`、无 `createEncoderByType`；`AudioBuf.interleaveInto` 是唯一现成积木且**没有写出方**；`FileSavePicker` 有实现无调用者；桌面 `DesktopFilePicker.saveAudio` 只筛 mp3/flac/m4a/ogg 且默认 `.mp3`（语义是**复制原始字节**，不转码），安卓默认 MIME 是 `audio/mpeg` | 只能听不能存；导出 WAV 要同时补 writer + 调用者 + 对话框筛选 |
| **G4** | 没有段落（verse/chorus）标注 | 全曲自相似矩阵被代码显式否决（"4 分钟 = 4 亿项不可行"）；只有两个结构点 | 选段只能靠代理特征（§7.3） |
| **G5** | 没有长任务骨架；`prepare` 的 15 s 预算会"算完再丢" | `VocalSeparationTransition` 15 s 预算（:171）；超了 `clearMaterial()` 降级；而整曲分轨是分钟级 | mashup **绝不能**走 `prepare` 那条路 |
| **G6** | 整曲分轨的内存没验证过 | 按注释推算：DSP 整曲 4 条 `double[10.58M]` ≈ 339 MB + 输出 169 + 输入 85 ≈ **593 MB**；4 线程并行 ≈ **1.35 GB**，且 `ThreadLocal` scratch 会把数组**钉到进程退出** | 必须分窗处理（§7.5），不能整曲一次性喂 |
| **G7** | 没有 downbeat/小节号 | `Analysis` 硬算那一路"不产小节相位"；`BeatGridSidecar` 有 `downbeats[]` 但生产没人写 | 只能按"每 4 拍"假设小节，或等 `.beats` 有 writer |
| **G8** | 置信度有**四把不同的尺子**共用一个字段名 | 旧路 主峰/均值/8（0.15）、新路 |ac|/能量（0.15）、`BeatTracker` 1−残差/0.25、模型路 1−MAD/周期；代码明写"两者值不可直接比较" | 阈值必须按来源分流，否则"看起来同一把尺子" |
| **G9** | `Envelope.rms` 跨曲不可比 | 按 95 分位归一化（`AutoCue.java:509`） | 电平配对、段落代理的绝对量必须换 `Lufs`/别的绝对量 |

---

## 4. 总体架构

```
                ┌──────────────────── MashupService（编排，后台线程）────────────────────┐
                │                                                                      │
A 路径 ─▶ 整曲扫描(AutoCue.Envelope，有磁盘缓存) ─▶ TrackProfile ──┐                       │
B 路径 ─▶ 整曲扫描 ─────────────────────────────▶ TrackProfile ──┤                       │
                                                                  ├─▶ MashupMatcher      │
                                                                  │     └─▶ 闸门+分数     │
A ─▶ StemStore.ensure(A, 档位) ─▶ vocals_A ───────────────────────┼─▶ MashupPlanner      │
B ─▶ StemStore.ensure(B, 档位) ─▶ instrumental_B ─────────────────┘     └─▶ MashupPlan   │
                                                                        │                │
                        MashupRenderer（分块：移调 → 变速 → 混音 → 限幅）─▶ WavWriter       │
                                                                        │                │
                          进度 / 取消 / 失败原因 ◀── MashupJob ◀────────┘                │
                                                                                        │
                └────────────────────────────────────────────────────────────────────────┘
```

依赖方向单向：`audio.mashup → audio.mix / audio.dsp / audio.decode / audio.stem / audio.model`，
`audio.mix` 不认识 `audio.mashup`。

---

## 5. 磁盘布局与缓存

要同时继承两套既有纪律（它们各有一半）：

- **`BeatGridSidecar` 的严谨**：魔数 + 版本 + 内容 sha256 + 坏文件当空 + 未知键忽略 + 读永不抛；
- **`AutoCue` 磁盘缓存的工程**：`cache/analysis/` 下、`tmp → rename` 原子写、内容键（本地文件 `length@mtime`、远端 URL 去 query）。

```
<cacheRoot>/mashup/            # 可重建、可淘汰（<cacheRoot> = DiskCache 的 cache 目录）
├── profile/  <sha1>.<ver>.profile      整曲特征（含 needs 标记：chroma 有没有算）
├── stems/    <sha1>.<档位>.<ver>.stem   整曲分轨（s16，见下）
└── out/      <A>__<B>__<planId>.wav     成品（也可直接写到用户选的导出目录）
<base>/models/htdemucs.onnx            # 不淘汰（既有 AppDirs 分工，不动）
```

**stems 文件格式**（自描述、无外部依赖、可校验）：

```
"QPSTEM\0\0" | u32 version | u32 sampleRate | u32 frames | u8 channels | u8 sourceTag(0=DSP,1=AI)
| u32 crc32(body) | body = planar s16: L_instr, R_instr, L_voc, R_voc
```

- 体积：44.1k 立体声 s16 = 176 KB/s；4 分钟 ≈ 42 MB/轨 ×2 ≈ **85 MB/首**（f32 是 169 MB/首）。
- 淘汰：`stems/` 超过 2 GB 或 30 天未用按 LRU 清；设置页给「清空分轨缓存」。
- 校验：**读盘必须比 crc32 与 sha256**，不能只比大小——`ModelDownloads.present` 只比大小这件事
  不值得再抄一遍（"自信地给出错误伴奏"是最坏的一种错）。

---

## 6. 数据模型

### 6.1 `TrackProfile`（可缓存的分析结果）

| 字段 | 来源 | 用途 |
| --- | --- | --- |
| `durationSecs` | `Envelope.durationSecs` | 边界 |
| `tempo`（bpm/periodFrames/phaseFrames/confidence/**sourceTag**） | `Envelope.tempo` | 配对闸门；`sourceTag` 记住置信度是哪把尺子（G8） |
| `beatSecs[]` | `Envelope.beatsIn(0, duration)` | TimeMap 候选锚点（恒定栅格） |
| `beatRefinedSecs[]` | 上面每个点过 `BeatRefine.refineFrame` | 真实锚点（能跟住漂移） |
| `rms[] / band[3][] / center[] / wave@1 kHz` | `Envelope` | 段落代理、频段互补、波形重合 |
| `chroma[]`（12 维/帧） | `Envelope.chroma` | 配对的和声相似度 |
| `voiceMask[]` | `Envelope.voiceActiveMask()` | 选段、叠唱冲突 |
| `structureIntro/OutroSecs` | `Envelope` | 选段边界提示 |
| `lufsIntegrated` | `Lufs` | 电平（**不用 rms**，见 G9） |
| `keyTonic / keyMode / keyConfidence` | `ReferenceKeyEstimate.Estimate` | 调性配对 |
| `contentHash` | 音频 sha256（远端用 URL+size） | 失效 |

### 6.2 `TimeMap`（本方案的技术核心）

```java
final class TimeMap {
    long[] outAnchor;   // 输出帧，严格递增
    long[] srcAnchor;   // 源帧，严格递增
    long srcAt(long outFrame);      // 段内线性插值
    double speedAt(long outFrame);  // Δsrc/Δout，段内常数
    boolean isMonotone();
}
```

不变式（直接写成 JUnit 判据）：

1. 两个锚点数组都严格递增；
2. 每段速度 ∈ **[0.92, 1.08]**（整曲长时间变速，±8% 是"听不出走调"的上限；
   `TempoBridge` 的 0.75–1.33 是过渡窗口的临时宽容，**不能**拿来整曲用）；
3. 锚点处 `srcAt(outAnchor[i]) == srcAnchor[i]` **精确成立**（不是"接近"）；
4. **速度变化率** `|Δspeed| / Δt ≤ 2.5%/s`（同 `TempoRamp.SLEW_MAX`）。

### 6.3 `MashupPlan`（可复现的全部决策）

```java
final class MashupPlan {
    String aSource, bSource;      // 路径 + headers（网络直链要带 token）
    int rate;                     // 输出采样率
    double vocalSemitones;        // 人声移调（钳在 SAFE_PITCH_SEMITONES=2）
    TimeMap vocalMap;             // A 人声 → 输出时间轴
    long bStartFrame, outFrames;
    double vocalGainDb, instrGainDb;
    boolean duckEnabled, hpOnVocal;
    String planId;                // = sha1(上述字段 + 曲目 contentHash)
}
```

---

## 7. 算法

### 7.1 分析

直接复用 `AutoCue` 的整曲扫描（它已经有磁盘缓存、已经为"一首歌只解一遍"优化过：
220 s 曲 ~1.1 s、190 s 曲 ~261 ms，都是注释实测），**不另写一套分析器**。
唯一需要新增的是两个 public 访问器（`voiceActiveMask()` 与 `beatsIn` 已经 public，
前者是包内可见）和一个 `Lufs.integrated` 调用。

> 想省事的人会顺手用 `Analysis.analyze(pcm, rate)` —— 它足够快，但只给一条 onset 包络 +
> 全局恒定拍栅格，**没有** key/LUFS/结构/人声，拿它做 mashup 会在配对与选段上二次返工。

### 7.2 配对（先在纸上淘汰，再花几分钟渲染）

闸门（沿用 `MixPaths` 的数字，不新增阈值）：

| 闸门 | 条件 | 不过时 |
| --- | --- | --- |
| 速度 | `|BPM_B/BPM_A − 1| ≤ 0.12`（`LOOSE_TEMPO`）才有资格进入渲染；**≤0.08 才能整曲对拍**（§7.4），0.08–0.12 只做 ≤90 s 的短输出；≤0.06 记"舒服" | 先试 half-time / double-time 再判；仍不过 ⇒ 拒绝该对，并写明"速度差 x.x%" |
| 调性 | `Harmonic.match` 的 `|semitones| ≤ 2`（`SAFE_PITCH_SEMITONES`）；`plainScore ≥ 0.72` | 2→3 半音（`MAX_SHIFT`）只降权；`plainScore < 0.72` ⇒ 拒绝 |
| 可靠度 | `tempo.confidence` 按其 `sourceTag` 用对应阈值；`keyConfidence` 低于阈值即"不移调" | 只降权或不移调，不假装知道 |
| 时长 | `min(len_A, len_B) ≥ 75 s` | 拒绝 |

分数（只用于同几对里排序，不做绝对判断）：

```
score = 0.45·tempoFit + 0.35·keyFit + 0.10·vocalFit + 0.10·structureFit
  tempoFit     = 1 − |Δbpm| / 0.12
  keyFit       = Harmonic.Match.bestScore（已归一化）
  vocalFit     = 1 − (B 的伴奏里残留人声占比)     // 用 A/B 的 voiceMask 与 stems 能量交叉估计
  structureFit = B 有明确 drop/副歌起点 ? 1 : 0.6
```

### 7.3 选段（没有段落标注时的代理）

不做 ML 段落分割，用**每小节特征 + 规则**，够用且可解释：

1. 每小节聚合 `(绝对 RMS（由 Lufs 定标，不用 Envelope 的归一化值）, onset 密度, voiceMask 占比)`；
2. 3 点中值平滑，再在 ±4 小节内做 z-score；
3. `drop` = 「RMS 跃升 > 1.5σ」且「onset 密度跃升 > 1σ」的小节；
4. A 的起点 = 第一个 drop 后的小节边界（没有 drop 就退回第一个 8 小节边界）；
5. B 相同；
6. 输出长度 = `min(可用小节) × 小节时长`，向下取 8 的整数倍；不足 24 小节直接拒绝。

> 这一层的目标不是"找到副歌"，而是"**别从 intro 的静音里开始**"。代理错了就调规则，不引入模型。

### 7.4 时间轴（全程对齐）

**主时钟取 B**（伴奏不变速，人声迁就伴奏）：伴奏里的鼓与贝斯一旦变速，低频瞬态最容易露馅；
人声 ±6% 在 WSOLA 下几乎无感。

**锚点构造**（关键：锚点是"拍→拍"的对应关系，不是"每块一个修正量"）：

```
输入：A 的拍序列 dA[]（beatRefinedSecs，已样本级细化）、B 的 dB[]、起点配对 (a0, b0)
1) 名义比 r = BPM_B / BPM_A。要求 |r−1| ≤ 0.08（配对闸门用 0.12 拒绝，
   但真正渲染要更严：整曲变速超过 ±8% 会被听成"变调/拖沓"）
2) 候选锚点：第 k 拍 → (out = dB[b0+k], src = dA[a0+k])
   ⇒ 如果两首歌都是恒速，这条映射的斜率处处相等（= 1/r），TimeMap 退化成一条直线
3) 逐段检查：段速度 = Δsrc/Δout
     · 在 [0.92, 1.08] 内 → 采用该锚点
     · 越界 → 说明这一拍要么错拍、要么这首歌在**局部变速**：
                丢掉这个锚点（不从它插值），并在日志里记"第 k 拍未参与对齐"
4) 平滑：速度变化率钳到 2.5%/s（TempoRamp.SLEW_MAX）。
   与"每个锚点都必须精确命中"冲突时，**优先保证 8 小节边界精确命中**，
   小节内部允许 ≤10 ms 的偏离（一阶保持，1 小节内线性过渡）
5) 漂移检测：若某 8 小节块内被丢弃的锚点超过 1/4，
   说明这首歌在局部变速（BeatTracker 假设恒速，此时它的 confidence 也偏低）：
   在该块上用滑窗谱通量重估局部周期（30 s 窗，思路同 FlowAnalyzer 的头/整/尾）；
   重估不出来就**如实降级**：这一块不对拍，日志写明"第 12 小节起未对拍"
```

- **为什么每拍一个锚点**：插值误差只在**拍内**，不跨拍累积（与 `TempoBridge`「搜索位置锚定目标时钟」
  是同一条纪律）。
- **为什么 8 小节重锚**：把 BPM 估计的 ±0.1% 误差限制在 ≈16 s 内 ⇒ 最坏漂移 ≈16 ms，
  **低于鼓点可辨阈值（≈20 ms）**；不重锚的话 3 分钟会漂到 180 ms。
- **怎么实现变速**：给 `TempoBridge` 加一个 `stretchCurve(input, rate, outFrames, long[] outAnchor, long[] srcAnchor)`，
  一次 OLA 走完（内部目标位置 = 锚点线性插值）。这正是 `stretchConverge` 已有的做法，
  只是把"线性斜坡"换成"任意分段线性"——**纯增量，现有三个方法逐位不变**。

### 7.5 分轨（必须分窗，不能整曲一次喂）

**DSP 档（第一版主线）**：`DspStemSeparator` 一次处理整段会吃 593 MB（单线程）且
`ThreadLocal` scratch 会长期驻留，所以只能**按窗调用**：

```
解码：只开一次 Decoder，从 0 顺序读，绝不 seek
      （Deck 的注释实测：MP3 上 seek 与"从 0 自然播"差 1152~2304 帧 = 24~48 ms，
       而拍网格是从 0 自然播算出来的 —— 一 seek 就整体错位，对拍全白做）
      保留上一窗的尾巴（跳 H 帧）作为下一窗的前缀，内存里只留一个窗
分轨：窗长 W = 30 s，跳 H = 20 s（1/3 重叠），窗间 5 s 升余弦交叉淡化
      separate(winL, winR, rate) → 交错写 s16 到 stems 文件（顺序追加）
```

- 内存上界 ≈ 单个窗的 `double[3][W·rate]` ≈ 32 MB（33 万帧 × 3 × 8 B）+ 解码窗 ≈ 60–80 MB 峰值。
- 代价：46× 实时 ⇒ 4 分钟素材 ≈ **5–6 s**（分窗的额外开销只有淡化重叠那 1/3）。
- 交叉淡化是**必须**的：`DspStemSeparator` 的 STFT 边界会造出与内部不一致的相消，
  不淡化就会在每个窗边界听见"人声忽大忽小"。

**AI 档（第二版，质量升级）**：

- 模型窗 172032 采样（3.9 s）**焊在模型图里**，Java 侧没有窗口参数；4-stem，行序
  **drums / bass / other / vocals**，必须**运行时从模型元数据确认**，猜错不会报错，
  只会把鼓当人声送出去。
- 整曲 = 62 窗 × ≈5.0 s ≈ **5 分钟**（50% 重叠 ≈9 分钟），Java 进程峰值 **1.2 GB**（注释实测，
  单个 3.9 s 窗就已经这样）。
- 拼接：50% 重叠 + sin² 淡化；边界连续性判据见 §10（相邻窗 RMS 差 ≤ 3 dB）。
- 可行性风险见 §11：这条路要先把 ONNX 运行时和 htdemucs 的 I/O 全部补回来。

### 7.6 移调（人声 ±N 半音，保时长）

两段式，两段都是既有 DSP：

```
目标：out(pos) = S(T(pos))，音高 ×ρ，ρ = 2^(k/12)
1) 重采样：R(u) = S(ρ·u)          // 音高 ×ρ、时长 ÷ρ，用 Resampler
2) WSOLA：out(pos) = R(∫₀^pos s)，需 ρ·∫s = T ⇒ s(u) = T′(u)/ρ
   ⇒ 有效速度 = 期望速度 / ρ，必须仍落在 [0.92, 1.08]；
      越界 ⇒ 放弃移调（ρ=1），而不是硬撑
```

`k` 取 `Harmonic.match(...).semitones`，钳在 `SAFE_PITCH_SEMITONES = 2`；`keyConfidence` 低就不移调。

**7.6.1 DSP 档的频段再平衡（第一版能不能听的关键）**

DSP 的"人声轨"其实是 **center 通道的中频**，里面同时有底鼓、贝斯与和弦根音。直接叠加
会和 B 的节奏组打架。所以 A 的人声轨在混音前过一遍：

1. 24 dB/oct 高通 **150 Hz**（去掉 A 的底鼓/贝斯主体）；
2. 300 Hz–4 kHz 提升 **+2 dB**（人声可懂度）；
3. 与 B 的 `band[1]`（中频）包络做 −2 dB 侧链压（20 ms / 200 ms 时间常数），
   避免两边中频同时轰。

这一层不需要新 DSP：`Biquad` 与 `Gain`/`Envelope` 都在 `audio.dsp` 里。

### 7.7 混音

```
out = instrumental_B(g_B) + hp(vocals_A)(g_A · env(t)) [+ duck]
g_A / g_B：由 Lufs 整曲响度差配平，目标"人声在伴奏之上 0~+3 dB"（以 1–4 kHz 频段能量判）
env(t)：起点 30 ms 淡入、终点 300 ms 淡出
最后一级：TransitionLimiter（-1 dBFS、块内预扫、零延迟）
```

### 7.8 渲染（分块循环，内存有上界）

```
for (块 = 0; 块 < 总块数; 块++) {
    B 的伴奏块 = stems 随机读 [块起点, 块长+尾巴)
    A 的源区间 = vocalMap 反查 → 读人声块 → Resampler(移调) → TempoBridge.stretchCurve
    合成 → 高通/提升 → duck → limiter → 写 WAV
    每 8 块报进度；每块检查取消标志
}
```

块长 = **1 小节**（120 BPM ≈ 2 s），块间 20 ms 等功交叉淡化。
块长是**内存参数**不是性能参数：峰值 ≈ 块长 × 4 轨 × 4 B ≈ 2 MB。

> 注意区分两个"块"：这里是**渲染循环**的块；WSOLA 内部的 hop 是另一回事。
> 只要 TimeMap 是全局的、`stretchCurve` 一次走完整段，就不会踩到
> `stretchConverge` 注释里记着的"分段拼接硬接缝"。

### 7.9 导出

- **第一版只做 WAV**：44.1/48 kHz、s16 PCM，RIFF 头手写（≈80 行、零依赖、保持 `player-core` 可 dex）。
  积木是 `AudioBuf.interleaveInto`（它现在没有任何写出方）。
- **先修门，再接功能**：`PlayerController.FileSavePicker` 目前是死接缝（有实现、无调用者），
  必须先补一个 `PlayerController.exportMashup()` 之类的调用者，否则"导出"按钮点了没反应。
- 桌面：给 `DesktopFilePicker` 加 `saveAudioWav()`（筛选 `{"wav"}`、默认后缀 `.wav`）——
  现有 `saveAudio` 只筛 mp3/flac/m4a/ogg 且默认 `.mp3`，选它会把 WAV 字节写进 `.mp3` 文件。
- 安卓：SAF 必须显式传 **`audio/wav` + `.wav`**（`saveAudioFile` 默认 `audio/mpeg` / `audio.mp3`）；
  先写 `AppDirs.cacheDir()/...` 下的 `.stage` 临时文件，再交给 `AudioSaveRequest` 复制过去
  （它已经是这个模型，取消时只删暂存，不会在用户目录留半截文件）。
- 落盘命名：`mashup-<A>-<B>-<yyyyMMdd-HHmm>.wav`，重名加序号。
- mp3/flac 走二期：两端都**没有编码器**，桌面可调外部 `ffmpeg/flac`，安卓要新写
  `MediaCodec(audio/flac) + MediaMuxer`（系统有 FLAC 编码器，mp3 编码器不保证）。
  **在编码器存在之前，设置里不要给出 mp3/flac 档位**——会违反"不许静默降级/不许撒谎"的纪律。

---

## 8. 类清单与 UI 接线（新增 `player-core/src/main/java/dev/t1m3/qplayer/audio/mashup/`）

| 类 | 职责 | 测试 |
| --- | --- | --- |
| `MashupOptions` | 不可变参数（档位、长度、是否允许移调、输出率） | — |
| `TrackProfile` + `ProfileStore` | 特征值类 + 落盘（§5 的格式与校验） | round-trip、坏文件当空、crc 不符当空 |
| `MashupMatcher` | 闸门 + 打分（§7.2） | 边界值逐个钉；`sourceTag` 分流 |
| `TimeMap` | 锚点/插值/不变式 | 4 条不变式（§6.2） |
| `MashupPlanner` | TimeMap + 选段 + 电平（§7.3–7.4） | 合成拍网格（恒定 / 局部变速 / 缺拍） |
| `StemStore` | 分窗分轨 + s16 落盘 + 随机读（§7.5） | 窗边界连续性、crc、并发读 |
| `MashupRenderer` | 分块渲染（§7.8） | 对齐误差、长度精确、峰值 |
| `WavWriter` | RIFF/s16 写出 | 头字段逐字节 |
| `MashupJob` | 后台、进度、取消、失败原因 | 取消 200 ms 内生效、不泄漏线程 |
| `MashupService` | 编排（§4） | 合成素材端到端 |
| `VocalBandShaping` | §7.6.1 的频段处理 | 高通斜率/增益断言 |

对既有代码的改动（**只有三处，都是纯增量**）：

1. `TempoBridge`：新增 `stretchCurve(...)`；现有三个方法逐位不变。
2. `AutoCue.Envelope`：给 `voiceActiveMask()` 加一个 **public** 访问器（现在是包内可见）。
3. `SettingsCatalog` + `lang/zh_CN|en_US.properties`：新增 `mashupStemMode` 行与其 i18n 键，默认 DSP；
   **AI 档在"能整曲分轨"为 false 时置灰并说明原因**（不要复制现有 AI 档"能下载但没人能用"的错误）。

### 8.1 设置项（一处声明，UI 自动生成）

`SettingsCatalog.specs()` 加一行即生成界面（`SettingsPage` → `SettingsPanel` → `SettingGroup`
按 `spec.type` 派发），但**四处配套缺一不可**：

1. 键与取值常量：`MASHUP_STEM_MODE_KEY = "mashupStemMode"`，取值**直接引用**
   `StemSeparators.OFF/DSP/AI`（不另抄 0/1/2）；
2. 声明行：`dropdown(...).onlyOn(DESKTOP)` + `segmented(...).onlyOn(ANDROID)`（**一个键、两行形状**，
   照 `AUTOMIX_VOCAL_MODE_KEY` 的写法：桌面三档、安卓两档，值只有一个真源）；
3. 持久化与越界夹紧**不用改**（`SettingsCore.read/persist/normalize` 已覆盖 dropdown/segmented）；
4. i18n：`lang/zh_CN.properties` 与 `lang/en_US.properties` 都要加
   `settings.mashupStemMode.title/.desc` 与 `settings.group.mashup`（缺键会回落成键名）。

引擎/服务侧读法照仓库纪律：**不信任存的值**，读不到或越界一律降级到最保守档（`OFF`/DSP）。

⚠️ **不要用 `chips` 类型**：`SettingGroup.qml` 的派发链里没有 `chips` 分支（落到"动作行"，
点击无效）——这是现有 `AUTOMIX_STYLE_KEY` 那一行已经存在的坑，别踩第二个。

### 8.2 页面与导航

- 新页面 `shared-qml/pages/MashupPage.qml`（不是 `settings/` 下，那里全是设置行组件），
  骨架用 `RouteHeader` / `SectionHeader` / `Ui`，`onBack: app.popPage()`。
- 导航三处：`Main.qml` 加 `property bool mashupLoaded`、`ensurePageLoaded(which)` 加分支、
  加一个 `ManagedPageLoader{ routeType:"mashup" }`。**必须用 `pushPage`**：
  `replacePage` 推的路由在 `ManagedPageLoader` 里不实例化（`RecognizeOverlay.qml` 里记着这个坑）。
- 从设置页进入：用 `SettingSpec.action` + `registerAction` 里 bump 一个 revision，
  QML 侧监听后 `pushPage("mashup", 0)`（模板见 `pluginSettingsNavigationWatch`）。
- 进度/取消 UI 直接复用 `ModelManagerDialog.qml` 的形状（进度条 + 按钮矩阵 + 状态文字
  走 `settings.info(...)`），不用新造组件。
- **试听**：`playQueue` 会清空当前队列，试听前要 `saveQueue()` 或明确提示"会替换当前队列"。
- QML 是**构建期 AOT**：新页面不改构建流程就不会生效，别指望运行时编译。

### 8.3 长任务（照模型下载那一套）

单线程 daemon executor + 200 ms 节流的进度回调 + `post()` 回渲染线程 + 一个
`Property<Long>` revision（QML 只重算那一行）。**后台线程绝不直接 `Property.set`**。
属性建议：`mashupPhase` / `mashupPercent` / `mashupStatus`（一句话原因）/ `mashupRevision`。

---

## 9. 里程碑与验收判据

先修的死接缝（不属任何里程碑、但排在 M4 之前）：`PlayerController` 里给 `FileSavePicker`
补一个调用者 + 桌面 `saveAudioWav()`（§7.9）。不补，导出按钮点了没反应。

| 阶段 | 内容 | 验收判据（客观） | 粗估 |
| --- | --- | --- | --- |
| **M0** | `WavWriter` + `TimeMap` + `MashupRenderer`（恒定速度、无分轨、无移调） | 两条已知 BPM 的合成轨渲染 60 s：拍偏差 ≤ 5 ms、长度误差 0 帧、峰值 ≤ −1 dBFS | 3–4 天 |
| **M1** | `TrackProfile`/`ProfileStore`（复用 `AutoCue.Envelope` + `Lufs`）+ 配对闸门 | 12 首真实曲目出 profile；二次运行不重新解码；坏缓存不崩 | 3–4 天 |
| **M2** | `StemStore`：DSP 分窗分轨 + s16 落盘 + 交叉淡化 | 4 分钟曲目分轨 ≤ 10 s；窗边界人声包络 RMS 差 ≤ 3 dB；峰值内存 ≤ 120 MB | 4–6 天 |
| **M3** | `stretchCurve` + 逐拍细化锚点 + 漂移检测 + `MashupPlanner` | 3 分钟输出：每小节拍偏差中位数 ≤ 10 ms、全程漂移 ≤ 25 ms（onset 互相关客观量） | 6–8 天 |
| **M4** | 移调 + 频段再平衡 + 电平/duck + limiter + 导出 | 基频误差 ≤ 5 音分；成品 −14±1 LUFS；无削顶；audible 边界无咔哒 | 3–4 天 |
| **M5** | `MashupJob` + QML 页面 + 试听/导出 | 取消 200 ms 内生效；进度单调；失败给出 `PrepareStage` 式一句话 | 3–5 天 |
| **M6**（可选，风险高） | AI 分轨：恢复 ONNX 运行时 + htdemucs I/O + 4→2 折叠 + 分窗拼接 | 4 分钟曲目分轨成功、行序从元数据确认（不猜）、峰值内存实测记录 | 8–12 天 |

合计 **M0–M5 ≈ 4–5 周**（单人、含听感调试）。**M2 是关键路径**：它不通就只有"整曲一次性 DSP"
（内存 593 MB 起、可能直接崩进程），不算可交付。

---

## 10. 验证方法

1. **数学层**：`TimeMap` 四条不变式；`stretchCurve` 在速度恒为 1 时与输入**逐位相同**
   （照抄 `TempoBridge`「关掉时逐位相同」的纪律）。
2. **对齐层**：渲染输出后，用 B 的伴奏 onset 与输出里 A 人声的 onset 做互相关，
   逐小节求峰位置差，输出 `median/max` 曲线；目标见 M3。
3. **听感层**：3 组配对（同风格 / 跨风格 / 同调不同速）× 各 90 s，用户拍板；
   不达观感就调 §7.3/§7.6.1 的规则，不动架构。
4. **回归层**：`mvn -pl player-core test` 全绿（现有 1198 项）；`--build-smoke` 过；
   `audio.mix` 的测试数量与断言**不得变化**——"实时链路零改动"是硬要求。

⚠️ 别把 `.tmp/automix-acceptance/*.wav` 当回归基线：生成它们的那套脚手架
（`MixPreview` / `SmoothMixRenderer`，分支 `gpt-natural-mix` 上的 `fdc5a37f`）**不在 master 祖先里**，
`.tmp/` 被整目录 gitignore，所以那 4 个样本**无法从 master 复现**，只能当听感参照。

---

## 11. 风险与对策

| 风险 | 概率 | 影响 | 对策 |
| --- | --- | --- | --- |
| AI 分轨要恢复 ONNX 运行时 + 写 htdemucs I/O，工作量超预期 | **高** | 高 | 单独 M6；第一版交付 DSP 档；设置里 AI 档置灰而不是"能下不能用" |
| 整曲 DSP 内存爆（593 MB ~ 1.35 GB） | 中 | 高 | 强制分窗（§7.5）；`ThreadLocal` scratch 用独立线程避免多份 |
| 歌曲局部变速导致 BeatTracker 判"不可信" | 中 | 中 | 每 8 小节漂移检测 + 局部重估；估不出来**如实降级**，不假装对齐 |
| 调性判错 ⇒ 移调后更难听 | 中 | 中 | `keyConfidence` 低则不移调；`MATCH_MIN_SCORE=0.72` 是既有闸门 |
| 3 分钟变速的颗粒感累积 | 中 | 中 | ±8% 上限 + 只变人声 + 8 小节重锚；再不行把输出限制在 ≤90 s |
| DSP 档人声轨带鼓/贝斯，叠起来糊 | **高**（第一版必然遇到） | 中 | §7.6.1 的高通 + 中频提升 + 侧链；听感验收决定参数 |
| 导出接缝是死的（`FileSavePicker` 无调用者） | 确定（已存在） | 中 | 排在 M4 前的独立小步；顺手救回这条接缝 |
| 试听清空用户当前队列 | 确定（`playQueue` 行为） | 中 | 试听前 `saveQueue()`，或明确提示 |
| QML 构建期 AOT，新页面不重建不生效 | 确定 | 低 | 改完 QML 必须重建两个宿主再验证 |
| 版权 | — | 高 | 只做本地私人使用；不内置分享/上传入口 |

---

## 12. 需要拍板的决策（实现前必须定）

1. **第一版交付档位**：DSP（快、卡拉 OK 级）还是等 AI（多 2–3 周、质量上限高）？
2. **输出长度**：跟 B 整曲（4–5 分钟）还是固定 90–120 s 精华段？
3. **移调上限**：±2 半音（安全，推荐）还是 ±3（`Harmonic.MAX_SHIFT`）？
4. **导出格式**：本期 WAV 够不够？
5. **A/B 谁变速**：固定"B 不变、A 变速"（本方案默认），还是允许对调？

---

## 13. 实施进度

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| **M0** | ✅ 2026-11-21 | 新增 `audio/mashup/`：`TimeMap`、`PcmTrack`、`WavWriter`、`MashupRenderer`；新增 20 个单测（`TimeMapTest` 8 / `WavWriterTest` 5 / `MashupRendererTest` 6 / `MashupSmokeTest` 1）全绿；60 s 合成素材渲染出成品 WAV，长度逐帧精确、峰值 ≤ −1 dBFS、两次渲染**逐字节相同** |
| **M1** | ✅ 2026-11-21 | 新增 `TrackProfile`（可解析文本档案）、`ProfileStore`（内容键 + 原子写 + 读永不抛）、`TrackProfiles`（复用 `AutoCue.scan` + `Lufs` 近似）、`MashupMatcher`（闸门 + 四项打分 + 一句话原因）；新增 25 个单测全绿；合成 120 BPM 曲目端到端测出 **119.9999 BPM**、4 拍小节 2.0 s、LUFS 与 12 维色度 |
| M2 | ⬜ 下一步 | — |
| M3–M6 | ⬜ | — |

M0 落地时的取舍（写在这里免得下一阶段忘）：

- `MashupRenderer` 在 `build()` 里**拒绝变速映射**（`UnsupportedOperationException`），
  而不是用重采样冒充保调变速——M3 接 `stretchCurve` 时把这条断言换成"按时间轴取数"。
- 混音是**相加**不是覆盖：人声那一路写进块时读回已有值再加（这条一开始写错过，
  被 `limiterCapsTheInPhaseSumAndSaysItActed` 与 `sourceShortage...` 两条判据抓住）。
- `WavWriter` 的目标是**文件**（`RandomAccessFile`，关闭时回填 RIFF/data 长度），
  因为安卓导出本来就是"先写 `.stage` 临时文件、再复制给 SAF"。
- 块长默认 4096 帧（≈85 ms）：本阶段没有"按块步进的响度补偿"，所以块长只影响内存与调用频率。

M1 落地时的取舍：

- **响度是"中段 60 s @ 22.05 kHz"的近似**，不是整曲（整曲 f32 立体声要 92 MB/首）。
  判据是"两首歌响度差要不要降权"（6 dB 门槛），这点误差远小于阈值。真做电平配平的 M4 再看要不要整曲。
- **拍点只存结论（BPM/周期/相位），不存拍列表**：拍位置可由周期/相位在任何时刻推出，
  存几千个 double 既占空间又会和周期分叉；逐拍锚点要过 `BeatRefine`，那是 M3 在规划器里做的事。
- **置信度只降权、不做硬闸门**：`Analysis.Tempo.confidence` 是四把尺子共用一个字段名（§3 G8），
  拿不到"哪把尺子"时硬闸门会误杀，所以只乘 0.7~1.0 的系数。
- **half-time 在闸门里就归一**：87 vs 174 直接相除是 2.0 倍会误杀；先试"逐拍 / B 每两拍 / A 每两拍"
  三种锚法取最接近 1 的，`aStride`/`bStride` 把结论带给 M3，别让规划层再猜一次。
- 对既有代码的两处**纯增量**改动：`AutoCue.Envelope.voiceActiveMask()` 改 public（同一份判据不抄第二份）、
  `TestWavFile`（测试辅助）改 public（跨包测试复用，不再复制第三份 WAV 解码替身）。

环境备注（本机）：

- 系统里**没有** `mvn`，也没有 `JAVA_HOME`；本次把 Maven 3.9.16 解到
  `~/.local/apache-maven-3.9.16`，JDK 是 `/usr/lib/jvm/java-21-openjdk`（`java` 在 `/usr/bin/java`，21.0.12）。
- 全量 `mvn -pl player-core test`：**1539 项、10 项失败**；这 10 项在 HEAD（`3cbb3faa`）的
  干净 worktree 上**同样失败**（`RenderThreadUiHazardLintTest`、`AiMixHarmonicCueTest`、
  `VocalSeparationDspEndToEndTest`、`VocalSeparationTransitionTest`×6、`SettingsCatalogTest`），
  与 mashup 无关，属既有红灯。
- 当前工作树的分支是 `official-ui-211`（不是 AGENTS.md 写的 `master`），远端只有一个
  `origin` 且指向 `TIMER-err/qplayer`。提交前需要确认落哪个分支。

---

## 附：本方案刻意没有采用的四条捷径

- **复用 `Transition` 把窗口拉长**：它的语义是「progress 0→1 的一件事」，整曲重叠会把它变成
  常驻双路混音，污染实时链路的语义与位完美判据。
- **走 `prepare` 那条路**：15 s 预算 + 算完才丢，整曲分轨必然被丢弃且白算。
- **分段 `TempoBridge.stretch` 拼接**：它自己的注释已经说了会留硬接缝（咔哒 + 帧数记账偏差）。
- **给 `player-core` 加编码器依赖**：`player-core` 必须保持可 dex、无原生依赖；编码器只能留在宿主。
