# AutoMix 选点：与参考包的一致性报告

参考包：`/home/strokeshading/Documents/automix-c/reference-python/`（Apple 行为的第三方重建）
本仓库：`player-core/src/main/java/dev/t1m3/qplayer/audio/mix/Reference*.java`
结论口径：**可证"与参考包一致"；不可证"与 Apple 闭源引擎一致"**。

## 1. 已证明 diff = 0 的部分（Java 判据）

| 判据 | 覆盖内容 | 规模 |
| --- | --- | --- |
| `ReferenceParityTest` | `score_core`（连乘因子 + 0.001·boundary_delta） | 11 组向量 |
| `ReferenceTempoParityTest` | `harmonic_tempo` + `stretch_comfort`（含 174/87 half-time） | 5 组 |
| `ReferenceLogicParityTest` | `tempo_relationship`（alias 平手顺序）+ `tonality_relationship`（五度圈） | 7 + 7 组 |
| `ReferenceRandomParityTest` | CPython `random.Random(seed).randrange(n)` 序列 | 100 个值 |
| `ReferenceDecisionParityTest` | `_candidate` + `plan_transition` 全链路（模板 id / 分数 / 四个区间） | 12 组 |
| `ReferencePathsParityTest` | `evaluate_candidate`（11 条路径的 required/因子/特例） | 20 组 |
| `ReferenceRegionsParityTest` | 区域索引算术 + 响度窗 + 叠唱冲突 + 响度显著性 | 56 断言 |
| `ReferencePathPipelineParityTest` | `shift_bars_for → scale/shift → evaluate` 一条龙 | 15 组合 |
| `ReferenceDspTest` | 69 条 DSP 参数表 / `out_gain` 规则 / vocoder 归一化 / 区间夹取 | 5 条 |

## 2. 现场对拍（dump → 参考包重跑 → 逐字段比对）

工具链：

```sh
# 桌面端（或 JUnit）打开 dump
java -Dqplayer.automix.dump=/tmp/automix-dump … dev.t1m3.qplayer.desktop.app.Main
# 对拍（模板级 score/eligible + chosen 的 7 字段 + 因子并排）
python3 tools/automix_compare.py /tmp/automix-dump
```

已验证样本：

- 15 份合成 dump（含低拍可信度 / 高原声度 / 低舞曲度 / 缺调性 / 短曲 / 大响度差 / 区域自带调性）
- 36 份真实歌曲 dump（6 首两两组合 30 对 + 6 份变体：无 downbeats / 无调性）
  曲目清单与逐对结论见 `docs/automix-parity-table.md`
- 合计 51 份 × 24 模板 ≈ **1200 次模板级比对，全部 diff = 0**

## 3. 仍是"我们的近似"的部分（不要当成 Apple 的值）

| 特征 | 来源 | 状态 |
| --- | --- | --- |
| `beat_confidence` / `beat_stability` | `Analysis.Tempo.confidence`（同一量） | 代理 |
| `timing_confidence` | 常量 0.95 | 假设 |
| `acousticness` | band 低频占比 | 代理 |
| `danceability` | onset 密度 | 代理 |
| `genre` | BPM 段推断 | 代理（非目录元数据） |
| `key` / `key_confidence` | Krumhansl 轮廓（12 维 chroma） | 自算，方法论与 Apple 不同 |
| `vocal_activity` | 人声占比（vamp / 让位区间） | 近似 |
| `meter` | 常量 4/4 | 假设 |

Apple 的这些量来自其私有分析层，无法取得；参考包本身也只是接受这些特征作为输入。
因此："同一份特征下选点一致"已被证明；"特征值本身与 Apple 相同"未证明，也不会声称。

## 4. 尚未接线

- `dsp-parameters` 的原生默认值目前用于：日志展示 + 湿声/send 的区间夹取；
  真正的逐路径 DSP 数值表参考包也没有给出（其注释明说 units 未恢复）。
- 产品进程（桌面 / 安卓）上的对拍需要真机跑一次；JUnit 侧已用真实音频验证。

## 5. 回滚

- 旧选点逻辑：`git checkout automix-legacy-20261121 -- player-core/src/main/java/dev/t1m3/qplayer/audio/mix/`
- 本轮参考链开关：`AutoCueTransition.REFERENCE_CHAIN_ENABLED`

## 6. 复跑方式（一条命令）

```sh
tools/automix_guard.sh --regen   # 重新生成全部 dump 并对拍
tools/automix_guard.sh           # 只对拍已有 dump
```

任何一份不一致都会打印「哪份 dump / 哪条模板 / 哪个因子（java vs reference）」并以非零退出。

## 7. 最终数字（2026-11-21 收尾轮实测）

```
Java 判据：14 个测试类，其中 10 个是"与参考包逐位一致"判据
全量测试：1491 跑 / 10 失败（均为迁移前既有失败，与本次工作无关）/ 8 跳过
对拍样本：15 份合成 dump + 36 份真实歌曲 dump = 51 份
模板级比对：51 × 24 ≈ 1224 次，全部 diff = 0
产品路径：AutoCueTransition.prepare 在真实音频上采纳参考包决策（判据 + 日志双证）
```
