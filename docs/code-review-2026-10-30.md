# QPlayer 全仓库代码审查

- 审查日期：2026-10-30。
- 代码基线：`db3ecc9281c82bfffa1046f353bc6851d7274fd2`。原审查源码引用已固定到该提交，避免删除或拆分实现后历史证据行号漂移；最新实施记录见第 9 节。
- 用户要求：规范与可读性、耦合和上帝类、性能与维护性，以及冗余、不可达和已经失效的实现；全部发现记录于本文。
- 首次审查交付：仅新增本文；该阶段未修改业务代码、配置、测试、版本号或发布状态。用户随后要求拉取远端并实施修复，最新进展见第 9 节。
- 方法：全量清单与引用检索、关键调用链细读、关键并发/QML 语义交叉复核、隔离运行实际生产类。**不是仅按文件长度、无 Java 引用或主观风格判定缺陷。**

## 1. 结论、级别与覆盖

核心问题是**状态所有权不一致、跨层完成协议不完整、重复规则已经漂移，以及插件切换后保留整套失效在线分支**。不建议重写全仓，也不建议先做全量格式化。

主要发现统计如下；同一主来源门禁在 Java 与 QML 的表现已合并。第 3 节清理项独立列出，不将其作为重复的主要缺陷凑数。

| 级别 | 项数 | 含义 |
| --- | --- | --- |
| P1 | 6 | 优先处理：播放、外部控制、线程/原生生命周期或主线程阻塞的高影响问题。 |
| P2 | 47 | 正常使用路径的正确性、性能、资源或维护边界问题。 |
| P3 | 7 | 较低优先级的可读性、诊断、格式容错与局部重复工作。 |

证据状态：

- **运行复现**：7 项以实际生产实现完成隔离验证。每项正文说明实际执行到的边界；不能从局部复现扩大为所有平台均已实测。
- **静态推断 `[INFERENCE]`**：其他项的代码结构和调用链已核实，但触发后的用户可见表现/性能代价尚未实测。P1 不意味着已证明崩溃或 ANR。
- 不提供未经采样的 FPS、GPU 耗时、原生内存增量或 ANR 发生率。

### 1.1 覆盖清单

| 范围 | 类型 | 文件数 | 行数 |
| --- | --- | --- | --- |
| player-core | Java | 163 | 43,912 |
| desktop-host | Java | 67 | 14,854 |
| android-shell main | 21 Java + 17 XML | 38 | 6,202 |
| shared-qml 应用 | QML | 79 | 13,874 |
| shared-qml/miuix | QML | 65 | 10,358 |
| player-core tests | Java | 42 | 5,216 |
| desktop-host tests | Java | 35 | 5,624 |
| Android tests | Java | 2 | 未单独统计 |

另审查根/模块 POM、Android Gradle、AOT 工具 3 个 Java 文件、仓库内 3 个 workflow、三平台打包脚本、JNI/resource 配置及自研 WebView 适配层。资源检查沿 shader、字体、歌词和 AOT 的实际消费者展开。Android 主源码包含 `io/github/timer_err/qml4j/android` 桥接类；不是只看 `dev/t1m3` 包。

覆盖方式为全量声明/引用检索加关键实现细读，不承诺每一行均以同等深度分析。`target/`、构建缓存、下载产物及外部依赖本体不计入自研质量统计。

第三方边界：`desktop-host/native/webview/upstream` 的 `webview_embed.cpp`（10,360 行）与 Windows `webview_embed.cc`（4,975 行）是上游引擎及补丁，不是 QPlayer 自研上帝类；本次只沿真实创建、Cookie/JNI、排队和销毁链检查。miuix 导出组件、外部解码库、Lyricon AAR 亦不以体量归罪于本仓设计。

## 2. 全部主要发现

以下是 `db3ecc9` 基线的原始审查快照，每项包含定位、证据/影响及建议边界。当前修复状态以第 9 节为准，不将历史证据改写为修复后的代码行号。

### 架构与可维护性

#### ARCH-01 · P2 · 静态推断 [INFERENCE] · PlayerController 已经是跨领域上帝类，而不只是长文件

- **定位**：[`PlayerController.java:119-180`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L119-L180)；[`PlayerController.java:1017-1034`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L1017-L1034)；[`PlayerController.java:6789-6964`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6789-L6964)。
- **证据与影响**：同一对象同时拥有播放队列和状态、插件账户与 UI 协议、页面内容、歌词、封面、缓存、持久化及更新检查；宿主线程、QML 渲染线程和插件 actor 都能进入它。各责任的失败与生命周期无法独立收口，CORE-01/02/03、PLUGIN-01 与 ANDROID-01 是实际后果，不是仅凭行数判坏设计。
- **建议**：沿已有 AudioBackend、Plugin*Service、Store 和 SettingsCore 边界逐步抽出 PlaybackSession、QueuePersistence、ArtworkResolver、页面状态投影和 ReleaseUpdateChecker。先定义单一状态所有者，再移动实现；不要仅按每千行拆 helper 或做整仓重写。

#### ARCH-02 · P2 · 静态推断 [INFERENCE] · AOT 工具直接依赖 qml4j 私有字段和包内实现

- **定位**：[`QmlBundleCompiler.java:75-89`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/qml-aot-tool/src/main/java/dev/t1m3/qplayer/aot/build/QmlBundleCompiler.java#L75-L89)；[`QPlayerSceneCapture.java:31-45`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/qml-aot-tool/src/main/java/io/github/timer_err/qml4j/render/QPlayerSceneCapture.java#L31-L45)。
- **证据与影响**：TypeRegistry 的 types/singletons、Loader 的 recorder/compiler、QmlCompiler.componentCounter 和 resolveCompound 均由反射或引擎同包注入访问。升级引擎时字段名、内部签名或构造顺序变化，会同时破坏 Android 与桌面构建；这是已经存在的私有 ABI 耦合，尚未观测到本基线构建因此失败。
- **建议**：在 qml4j 提供稳定的类型枚举与场景捕获/AOT API，再移除 setAccessible 和引擎包内侵入。保留必要的生成工厂 ABI，不把运行时必需的 singleton 反射当死代码。

#### ARCH-03 · P3 · 静态推断 [INFERENCE] · Native 元数据工具中压缩成单行的多步逻辑降低审查可读性

- **定位**：[`DesktopNativeMetadata.java:172-197`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/qml-aot-tool/src/main/java/dev/t1m3/qplayer/aot/build/DesktopNativeMetadata.java#L172-L197)；[`DesktopNativeMetadata.java:413-440`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/qml-aot-tool/src/main/java/dev/t1m3/qplayer/aot/build/DesktopNativeMetadata.java#L413-L440)。
- **证据与影响**：反射发现、集合变换、JSON 生成和异常处理存在多条语句挤在一行的实现。它们处理闭世界可达性，遗漏一个类型就可能只在发行产物暴露；压缩书写增加了核查成本，但不能单凭书写方式判定当前配置错误。
- **建议**：按扫描、归类、生成三步展开局部实现，给反射根来源命名，保留现有生成逻辑。不要再维护一份手工反射清单，也不要将先前已修复的 LinkedHashMap 反射故障重复列为现存 bug。

### 核心状态、持久化与多来源

#### CORE-01 · P1 · 静态推断 [INFERENCE] · 播放状态没有统一所有者：队列复合读取与响应式发布都存在竞态

- **定位**：[`PlayerController.java:3725-3743`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3725-L3743)；[`PlayerController.java:5232-5239`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L5232-L5239)；[`PlayerController.java:9648-9650`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L9648-L9650)；[`PlayerController.java:3105-3125`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3105-L3125)；[`PlayerController.java:6148-6162`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6148-L6162)；[`DesktopWindow.java:1240-1242`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L1240-L1242)。
- **证据与影响**：CopyOnWriteArrayList 只保证单次操作：currentTrack 先读 volatile playIndex、再 size、再 get；并发删除可让 get 越界，或返回旧位置对应的新曲目。部分宿主会捕获异常，因此不能直接断言进程必崩。另有 main/plugin actor 直接写 QML 属性；qml4j 0.2.33 的 setter 同步执行 listener，没有当前线程 DirtyQueue 时还会即时重算绑定，并不自动转发 render 线程。歌词“仍是当前歌”的检查与发布也非同一事务，旧结果可能覆盖新歌。桌面后台转 main pump 的路径持有 QmlRuntimeLock，是有效反例：问题在互斥/所有权，不在名字不是 render。
- **建议**：队列、位置、播放意图归单一 PlaybackSession 所有；宿主读取一致的不可变快照，QML 投影经同一发布协议。修复所有写入方，不能只给 currentTrack 加锁或把更多字段改成 volatile。

#### CORE-02 · P2 · 静态推断 [INFERENCE] · 流地址解析只核对歌曲身份，旧结果会覆盖暂停和新请求

- **定位**：[`PlayerController.java:6453-6501`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6453-L6501)；[`PlayerController.java:6174-6177`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6174-L6177)；[`PlayerController.java:9669-9684`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L9669-L9684)。
- **证据与影响**：resolveAndPlayPlugin 的完成回调仅比较 canonical media ID，然后启动 backend 并把 playingIntent 设为 true。用户等待 B 的地址时暂停，回调仍会播放；A→B→A 时第一次 A 的旧请求也可能通过检查，覆盖第二次选择的 URL 或起播位置。封面的 coverRevision 不保护流解析。
- **建议**：统一记录播放请求代号、目标身份、恢复位置和期望播放状态。暂停改变期望状态，新选择使旧请求失效；不要复用适合歌词缓存的“同歌曲即有效”规则。

#### CORE-03 · P2 · 静态推断 [INFERENCE] · 保存与恢复本地队列会丢掉歌词路径

- **定位**：[`Track.java:73-75`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/model/Track.java#L73-L75)；[`PlayerController.java:6789-6840`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6789-L6840)；[`PlayerController.java:6849-6964`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6849-L6964)；[`PlayerController.java:6631-6640`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6631-L6640)；[`LibraryCache.java:180-183`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/library/LibraryCache.java#L180-L183)。
- **证据与影响**：queue JSON 不保存 lyricFilePath/translationFilePath/romajiFilePath；恢复时新建 Track 又不重新发现 sidecar，随后 loadLocalLyrics 只看这些空路径。重扫只替换 library，不补全已经恢复的 queue 对象，因此原本有歌词的本地歌在重启恢复后可无歌词。LibraryCache 另一套序列化已保留这些字段，说明两套 Track 恢复规则已分叉。
- **建议**：共用明确的 Track 持久化/本地资源重建协议，恢复队列时补全有效歌词资源；不要只在 UI 重新扫描按钮加特判。

#### CORE-04 · P2 · 静态推断 [INFERENCE] · 控制与渲染调用链仍同步执行封面、歌词及缓存目录 IO

- **定位**：[`PlayerController.java:5371-5386`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L5371-L5386)；[`PlayerController.java:5483-5516`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L5483-L5516)；[`PlayerController.java:3783-3803`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3783-L3803)；[`PlayerController.java:10351-10359`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L10351-L10359)；[`DiskCache.java:505-537`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/cache/DiskCache.java#L505-L537)。
- **证据与影响**：本地切歌在启动音频前读取歌词/封面；缓存页查询逐个访问文件；设置缓存容量时同步枚举、排序、删除并重新统计目录。SettingsSlider 每次值变化都能进入该链。在 Android main 或 QML render 被调用时，这些工作占用当前交互线程；没有测量耗时，不称为已证实掉帧或既成 ANR。
- **建议**：将文件读取、目录驱逐和统计交给已有 IO 所有者，控制线程仅提交状态并接收结果；容量拖动合并最终提交。不能只加 loading 标志掩盖阻塞。

#### CORE-05 · P2 · 静态推断 [INFERENCE] · 已缓存列表仍将网易云缩图参数用于所有插件封面

- **定位**：[`PlayerController.java:3786-3803`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3786-L3803)；[`PlayerController.java:5511-5552`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L5511-L5552)；[`PlayerController.java:7714-7726`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L7714-L7726)；[`DiskCache.java:176-179`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/cache/DiskCache.java#L176-L179)。
- **证据与影响**：正常插件封面按原 URL 缓存；已缓存歌曲列表却使用 thumbUrl 加网易云参数后查缓存。DiskCache 对整个 URL 取键，因此实际已缓存的其他来源图片查不中，离线列表可能只剩远程 URL/空封面。trackCoverUrl 已有 provider 判断，两个入口没有复用同一规则。
- **建议**：封面定位与缓存键统一归 provider-neutral artwork resolver，保留仅网易云需要的缩图转换；移除缓存列表残留的无条件源特例。

#### CORE-06 · P2 · 静态推断 [INFERENCE] · 主来源登录/能力被误当成所有来源歌单的授权门禁

- **定位**：[`PlayerController.java:5033-5037`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L5033-L5037)；[`PlayerController.java:8539-8541`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L8539-L8541)；[`PlayerController.java:10159-10162`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L10159-L10162)；[`PlaylistDetailPage.qml:46-60`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/pages/PlaylistDetailPage.qml#L46-L60)；[`LibraryPage.qml:33-41`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/pages/LibraryPage.qml#L33-L41)；[`LibraryPage.qml:264-314`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/pages/LibraryPage.qml#L264-L314)。
- **证据与影响**：loggedIn 与 sourcePlaylistMutationAvailable 只表示主来源，却控制次来源的心动、收藏、删除及新建 FAB。LibraryPage 已正确构造全部可创建的 creatableSources，提交也支持选来源，但入口仍要求主来源登录。主来源未登录/无能力、次来源已登录且支持时，合法功能被隐藏或拒绝。这是 Java 与 QML 同一规则漂移，合并记录，不重复计数。
- **建议**：按目标 provider 查询账户和歌单能力；新建入口按 creatableSources 是否有合法目标决定。主来源标量仅用于主来源自身的账户/UI。

#### CORE-07 · P2 · 运行复现 · 更新比较忽略预发布后缀，RC 无法更新到同号正式版

- **定位**：[`PlayerController.java:3451-3455`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3451-L3455)；[`PlayerController.java:3562-3587`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3562-L3587)；[`PlayerControllerUpdateCheckTest.java:15-27`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/test/java/dev/t1m3/qplayer/bridge/PlayerControllerUpdateCheckTest.java#L15-L27)。
- **证据与影响**：当前实际版本 1.9.3-rc.2 经宿主注入更新检查；比较器按 . + - 分割，只比较前三个整数。实际调用 isNewer("1.9.3", "1.9.3-rc.2") 得到 false，而下一数字版 1.9.4 为 true。同号正式版发布后 RC 会被当作最新版。不是“RC 被解析成 1.9.0”，也不是要求 latest 接口发现下一 RC。
- **建议**：给应用更新使用区分 prerelease/stable 的发布版本比较规则，同时明确 debug/perf 变体政策。不要把此要求误套到 PluginCompatibility 的数值最低版本 gate。

### 插件、权限与本地数据

#### PLUGIN-01 · P2 · 运行复现 · files 服务被两套权限映射的漂移永久挡在入口

- **定位**：[`PluginRuntime.java:241-252`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginRuntime.java#L241-L252)；[`PluginRuntime.java:329-346`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginRuntime.java#L329-L346)；[`CorePluginHostApi.java:333-342`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L333-L342)；[`PlayerController.java:1372-1375`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L1372-L1375)。
- **证据与影响**：Runtime 的 requiredPermission 没有 files.*，HostApi 却已有 FILES_SAVE，公开文档和 controller 也实现了 files.save/files.downloadCurrent。真实 JS 插件已声明 files.save，调用仍得到 permission denied for host method files.save，连宿主文件选择器都到不了。
- **建议**：宿主方法与权限只维护一份声明，补齐 Runtime 消费；不能绕过权限检查修功能。files.downloadCurrent 同属被遗漏的家族，运行探针实际调用的是 files.save。

#### PLUGIN-02 · P2 · 运行复现 · multipart 省略 headers 时修改不可变空 Map

- **定位**：[`CorePluginHostApi.java:396-400`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L396-L400)；[`CorePluginHostApi.java:959-960`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L959-L960)；[`CorePluginHostApiBodyTest.java:71-91`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/test/java/dev/t1m3/qplayer/plugin/CorePluginHostApiBodyTest.java#L71-L91)。
- **证据与影响**：stringMap 对省略的 headers 返回 Collections.emptyMap，自动补 Content-Type 时 put 抛 UnsupportedOperationException。本机真实 HTTP 服务观察到请求数为 0；同样请求只增加 headers:{} 即发送成功并返回 200。现有测试仅测 outgoing body，没有经过 request 组装。
- **建议**：请求组装统一使用可变 headers，再保留用户显式 Content-Type 优先级与 multipart boundary 生成逻辑。

#### PLUGIN-03 · P2 · 静态推断 [INFERENCE] · 临时 DNS 错误被永久缓存成网络授权拒绝

- **定位**：[`CorePluginHostApi.java:147-176`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L147-L176)；[`CorePluginHostApi.java:729`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L729)；[`PluginProviderService.java:680-685`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginProviderService.java#L680-L685)；[`PluginAccountService.java:102-107`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginAccountService.java#L102-L107)。
- **证据与影响**：URL 授权校验会解析 DNS，任何异常都转为 false 并放进无 TTL 的 urlGrants。网络恢复后同 host 仍被拒绝，直到插件重注册/注销或达到缓存整体清理条件。用户得到 outside its grant 或空封面，而不是临时网络错误。
- **建议**：分开确定的清单拒绝与 DNS/IO 暂时失败；后者不可永久记为授权结论，原域名和本地网络限制仍须保留。

#### PLUGIN-04 · P2 · 静态推断 [INFERENCE] · 插件安装回滚未覆盖 Registry 持久化失败

- **定位**：[`PluginManager.java:100-105`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginManager.java#L100-L105)；[`PluginRegistry.java:54-70`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginRegistry.java#L54-L70)；[`PluginInstaller.java:72-76`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginInstaller.java#L72-L76)；[`PluginManager.java:70-82`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginManager.java#L70-L82)。
- **证据与影响**：installer.install 在 Manager 回滚 try 外；Registry 先改内存再 save，save 失败时 Installer 删除新版目录却不恢复内存条目。旧运行时可能仍在跑，但 registry 指向已删的新版本；后续 enable 还可能先关闭旧版再启动失败，另一次成功保存会把坏引用写盘。既有回滚测试只覆盖安装成功后的 runtime 启用失败。
- **建议**：将解包、Registry 提交和 runtime 切换纳入同一事务边界；Registry 写成功后再发布新状态，或完整恢复旧快照。

#### PLUGIN-05 · P2 · 静态推断 [INFERENCE] · 本地歌单损坏/写盘失败被吞掉，后续可能覆盖旧文件并误报成功

- **定位**：[`LocalPlaylistStore.java:40-57`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylistStore.java#L40-L57)；[`LocalPlaylistStore.java:89-96`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylistStore.java#L89-L96)；[`LocalPlaylistStore.java:161-170`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylistStore.java#L161-L170)；[`MyPlaylistOrderStore.java:44-55`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/MyPlaylistOrderStore.java#L44-L55)；[`PlayerController.java:4844-4852`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L4844-L4852)。
- **证据与影响**：load 在解析前标 loaded，异常仅日志后呈现空集合；后续创建/导入会将新集合写回原文件。save 返回 void 并吞 IO 错误，controller 随后仍提示“已导入”。排序 store 也有同类处理。dirty 保留不等于本次保存成功，且损坏原文没有 Registry 那样的保留机制。
- **建议**：区分不存在、损坏、暂时不可读；保留故障文件且阻止静默覆盖。保存结果必须可到达调用方，只有持久化成功才确认；沿用仓内 Registry 的损坏文件保留思路。

#### PLUGIN-06 · P2 · 静态推断 [INFERENCE] · 查询歌曲是否在歌单中会 JSON 深复制整份歌单

- **定位**：[`SongContextMenu.qml:129-146`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/SongContextMenu.qml#L129-L146)；[`PlayerController.java:4252-4266`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L4252-L4266)；[`LocalPlaylistStore.java:73-86`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylistStore.java#L73-L86)；[`LocalPlaylistStore.java:205-206`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylistStore.java#L205-L206)；[`PlayerController.java:4127-4144`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L4127-L4144)。
- **证据与影响**：菜单遍历本地歌单逐个 isInLocalPlaylist，内部 get 通过 gson.toJson/fromJson 深复制全部曲目/订阅，且持 store monitor；发布卡片摘要也先 all 深复制所有歌单。大量曲目时，查询成本和分配量明显超出需要；未测具体延迟。隔离副本本身有正确的所有权价值，不能直接改成泄露内部可变对象。
- **建议**：增加受锁保护的 contains 查询与卡片摘要快照；完整 get 副本只用于确实需要全量隔离数据的导出/同步等操作。

#### PLUGIN-07 · P3 · 静态推断 [INFERENCE] · 凭据恢复边界吞掉具体异常，日志无法区分失败原因

- **定位**：[`PluginCredentialVault.java:153-200`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginCredentialVault.java#L153-L200)；[`CredentialCipher.java:350-424`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/store/CredentialCipher.java#L350-L424)；[`PlayerController.java:2567-2634`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2567-L2634)。
- **证据与影响**：Vault 的重试解锁、重新登录准备与重新启用系统保护只发通用事件/false，未保留捕获的 error；下层明明区分密钥不存在、存储不匹配、平台解锁失败和格式错误。故障恢复操作重复失败时，用户和维护者无法从日志定位原因。
- **建议**：在 Vault 边界记录操作名、异常类型及原因链，UI 可保持通用提示；严禁记录密钥或凭据内容。

#### PLUGIN-08 · P3 · 运行复现 · 歌单导入默认字段让任意 JSON 对象冒充合法格式

- **定位**：[`PlaylistTransfer.java:38-40`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/PlaylistTransfer.java#L38-L40)；[`PlaylistTransfer.java:121-132`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/PlaylistTransfer.java#L121-L132)；[`LocalPlaylistTest.java:362-379`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/test/java/dev/t1m3/qplayer/playlist/LocalPlaylistTest.java#L362-L379)。
- **证据与影响**：Document 初始化了正确 format/formatVersion，解析只检查它们的值而不检查字段是否存在。实际 parse 接受 {}、{"name":"foreign data"}，也接受格式声明正确但 formatVersion:-1 的对象；可创建空歌单或把其他应用 JSON 当歌单。既有测试覆盖错误值、数组、坏 JSON 和未来版本，未覆盖缺字段。
- **建议**：导入验证实际存在的格式标记与有效版本范围，导出默认值可保留，不必改既有格式。

### 渲染、字体与资源缓存

#### RENDER-01 · P2 · 静态推断 [INFERENCE] · 默认饱和度 100 让静态背景缓存每帧失效

- **定位**：[`FluidBackground.java:157-164`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/FluidBackground.java#L157-L164)；[`FluidBackground.java:228-236`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/FluidBackground.java#L228-L236)；[`FluidBackground.java:349-365`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/FluidBackground.java#L349-L365)；[`SettingsCatalog.java:287-288`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCatalog.java#L287-L288)。
- **证据与影响**：100/null 是“不需要颜色滤镜”的正常状态，却被 saturationFilter==null 当成未初始化；每帧 invalidateStatic 关闭旧 snapshot，随即重建设备分辨率 Surface、执行 shader 和 snapshot。不断变化的 time 也进入重建，静态模式并未真正冻结。明确的错误失效条件已核实，GPU 耗时未采样。
- **建议**：分开初始化状态与有效的无滤镜状态；只在实际饱和度、封面、尺寸等输入变化时失效。无需降低背景效果或改 shader 算法。

#### RENDER-02 · P2 · 运行复现 · 正在下载的缓存参与驱逐，当前播放保护匹配不到 pending 文件

- **定位**：[`DiskCache.java:472-484`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/cache/DiskCache.java#L472-L484)；[`DiskCache.java:518-535`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/cache/DiskCache.java#L518-L535)；[`DiskCache.java:603-605`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/cache/DiskCache.java#L603-L605)；[`PlayerController.java:2852-2870`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2852-L2870)。
- **证据与影响**：驱逐枚举全部文件，fileStem 只剥 .cache，不能把 .cache.pending 映射到当前歌曲键。隔离目录中标为 currentlyPlaying 的 2 MiB pending，在容量改为 1 MiB 后仍被删除；Linux 上打开的 writer 可继续写已取消链接文件，最终提升缓存抛 NoSuchFileException。桌面增长缓存也会消费该路径。
- **建议**：明确已提交缓存与在途写入的生命周期；驱逐只处理已提交项，或登记并排除活动 writer。不要只补一个后缀判断而继续让临时文件成为普通驱逐项。

#### RENDER-03 · P2 · 静态推断 [INFERENCE] · 本地库 freshness 不包含 sidecar 和提取资源状态

- **定位**：[`LibraryScanner.java:63-79`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/library/LibraryScanner.java#L63-L79)；[`LibraryScanner.java:111-132`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/library/LibraryScanner.java#L111-L132)；[`LibraryCache.java:125-126`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/library/LibraryCache.java#L125-L126)；[`LibraryCache.java:179-183`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/library/LibraryCache.java#L179-L183)。
- **证据与影响**：只比较音频大小/mtime，命中就返回旧 Track。新增更高优先级歌词/外置封面、删除旧 sidecar 或提取资源时，重扫仍绕过资源发现，旧路径或“无资源”状态持续存在。已被选中歌词文件的内容修改会在播放时重读，不属于此问题。
- **建议**：保留标签元数据缓存，把轻量 sidecar 发现与提取物存在检查独立出来；资源变化不必强迫重读整份音频标签。

#### RENDER-04 · P2 · 静态推断 [INFERENCE] · 凝彩每帧重复计算字形绘制常量，并为多层效果重复准备

- **定位**：[`TemperaTextView.java:208-271`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaTextView.java#L208-L271)；[`TemperaMotion.java:77-80`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaMotion.java#L77-L80)；[`TemperaColor.java:38-50`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaColor.java#L38-L50)。
- **证据与影响**：每帧创建 Frame[] 和运动对象；影子、两层残影、正文可使同字四次进入 drawGlyph，重复查字体、TextLine、宽度、metrics、解析十六进制颜色和构造缓存键。这些字体/字形指标/颜色在视图生命期内不随时间变化。已定位多余 CPU/JNI 和分配，没有 GC/帧时测量。
- **建议**：将字体、宽度、基线、ARGB 等常量缓存到字形视图并明确失效条件；复用运动输出存储。保留动态位置、透明度及反色效果，不重写构图系统。

#### RENDER-05 · P2 · 静态推断 [INFERENCE] · 凝彩插图在 render 同步解码，失败图片逐帧重试

- **定位**：[`TemperaScene.java:482-498`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaScene.java#L482-L498)；[`TemperaImagePool.java:61-76`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaImagePool.java#L61-L76)；[`TemperaImagePool.java:98-105`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaImagePool.java#L98-L105)。
- **证据与影响**：可见镜头每帧 imageFor；未命中直接 readAllBytes + Image.makeFromEncoded，只缓存非空结果。正常大图首次使用占用当前渲染帧；损坏、已删除或不可读图片则整个镜头每帧重读/重解码。来源扫描只筛扩展名。
- **建议**：后台读取/解码，render 只接收完成结果；按素材版本缓存失败，文件变化后才重新尝试。可复用 FluidBackground 已有的后台解码方向。

#### RENDER-06 · P2 · 静态推断 [INFERENCE] · 凝彩连续浮点字号进入无界共享字体缓存

- **定位**：[`TemperaLayout.java:265-287`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaLayout.java#L265-L287)；[`TemperaMeasure.java:88-90`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaMeasure.java#L88-L90)；[`TemperaMeasure.java:168-174`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaMeasure.java#L168-L174)；[`Fonts.java:773-795`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/Fonts.java#L773-L795)；[`TemperaPageRenderer.java:100-111`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaPageRenderer.java#L100-L111)。
- **证据与影响**：布局 fit 和词级 scale 产生连续 float 字号，CJK 回退调用共享 Fonts.get；静态 Map 按 float 原始位区分并永久保留 Font。凝彩自己的 256 项 LRU 和退出释放不限制这张共享表。歌曲/尺寸变化会在场景构建期累积，不是每帧创建字体；实际原生内存增量未测量。
- **建议**：先定义回退 Font 的所有权及与 TextLine 的关联生命周期，再选择可控字号复用/缓存策略。不能直接加会 close 的全局 LRU，其他渲染器还可能持有字体。

### Android 宿主与生命周期

#### ANDROID-01 · P1 · 静态推断 [INFERENCE] · main 冷启动与退出同步读写完整状态，存在 ANR 风险

- **定位**：[`QPlayerActivity.java:125-163`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L125-L163)；[`QPlayerActivity.java:542-559`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L542-L559)；[`PlayerController.java:1017-1034`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L1017-L1034)；[`PlaybackService.java:177-195`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/PlaybackService.java#L177-L195)；[`StorageFiles.java:22-58`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/store/StorageFiles.java#L22-L58)。
- **证据与影响**：已附着 View.post(startEngine) 仍在 main；递归目录统计/清理、controller 初始化、队列/索引 JSON 读取解析都在该线程。Service onDestroy/onTaskRemoved 还同步进入持 controller monitor 的 saveQueue 并写文件，可能等 checkpoint worker。大目录、大队列或慢存储会阻塞 main；没有设备 trace，不宣称实测 ANR。
- **建议**：状态恢复与目录维护放到明确 IO 初始化任务，main 只接线。退出捕获轻量一致快照，交单一持久化所有者有序提交，避免 lifecycle 等 monitor 再做全量 IO。

#### ANDROID-02 · P1 · 静态推断 [INFERENCE] · 无权限导出的 DEBUG 控制广播进入正式包

- **定位**：[`QPlayerActivity.java:361-400`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L361-L400)；[`PlayerController.java:3007-3068`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3007-L3068)；[`app/build.gradle.kts:40-45`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/build.gradle.kts#L40-L45)。
- **证据与影响**：receiver 启动后无条件注册，API 33+ 使用 RECEIVER_EXPORTED，旧版也无发送权限。FLAG_DEBUGGABLE 只保护 recreate；toggle/next/prev/play/search/导航及合成 swipe/fling 仍可由其他应用发送广播触发。release 未隔离该路径。它不是已失效的调试残留，而是仍有效的外部控制面；未在真机演示跨应用调用。
- **建议**：整段注册仅限 debuggable。若正式包必须支持 shell 自动化，另设可验证调用者的受限入口，而非无权限导出全部调试命令。

#### ANDROID-03 · P2 · 静态推断 [INFERENCE] · 音频保存第一段仍在 main，且暂存完成被误当最终成功

- **定位**：[`QPlayerActivity.java:810-846`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L810-L846)；[`PlayerController.java:1707-1750`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L1707-L1750)；[`PlayerController.java:1478-1491`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L1478-L1491)。
- **证据与影响**：onActivityResult 在 main 直接 writer.accept，保存本地歌的 writer 立刻 Files.copy 整个音频到 stage；只有 stage→SAF 才转后台。UI/插件在 stage 完成就确认成功，而最终 provider 写出失败只日志，stage 失败清理也不完整。大文件有主线程阻塞风险；目标文件失败时可出现误报成功/不完整文件。
- **建议**：第一段复制也移到 IO 线程，完成协议覆盖最终 SAF 流成功关闭；统一回传最终失败并在所有路径回收 stage。不能只改一条成功提示。

#### ANDROID-04 · P2 · 静态推断 [INFERENCE] · Activity 专属回调被后台 controller 和静态 settings 保留

- **定位**：[`QPlayerActivity.java:210`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L210)；[`QPlayerActivity.java:244-284`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L244-L284)；[`QPlayerActivity.java:1222-1234`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L1222-L1234)；[`OverlayLyricWindow.java:55-87`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/OverlayLyricWindow.java#L55-L87)；[`OverlayLyricWindow.java:127-135`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/OverlayLyricWindow.java#L127-L135)；[`SettingsCore.java:107-135`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCore.java#L107-L135)。
- **证据与影响**：controller.fileSavePicker 保存 this::saveAudioFile，但 onDestroy 未解绑；PlaybackService.controller/sharedController 可长期持有它。另有静态 OverlayLyricWindow.settings→SettingsCore.fontPicker/hooks→Activity，detach 未解除。idle 分支虽清 sharedController，另一静态 service 根仍存在。确认的是最近绑定 Activity 被保留，不是每次重建永久线性累积。
- **建议**：补齐 UI picker/订阅解绑；后台需要的设置状态不得捕获 Activity。不能机械清空整个 settings 破坏后台浮窗，也不能只清 glView。

#### ANDROID-05 · P2 · 静态推断 [INFERENCE] · MediaPlayer 同步启动错误没有进入正常恢复回调

- **定位**：[`AndroidAudioBackend.java:103-110`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java#L103-L110)；[`AndroidAudioBackend.java:180-187`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java#L180-L187)；[`PlayerController.java:956-962`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L956-L962)；[`PlayerController.java:1012-1015`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L1012-L1015)。
- **证据与影响**：setDataSource/prepareAsync 同步抛 IOException/IllegalStateException 时仅日志并 releasePlayer；异步 onError 才通知 controller。controller 后续仍设播放意图，loading 又主要靠 onStarted 清除。文件丢失/不可访问等同步失败可能停留在播放/加载状态，既没 player 也不触发原有跳过/错误策略。
- **建议**：同步与异步失败汇入同一 onError 语义；不以单独 loading=false 掩盖 backend/controller 状态失配。

#### ANDROID-06 · P2 · 静态推断 [INFERENCE] · IME/硬键盘只 queueEvent，按需渲染模式未被唤醒

- **定位**：[`QmlGLSurfaceView.java:408-456`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L408-L456)；[`QmlGLSurfaceView.java:486-513`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L486-L513)；[`QmlGLSurfaceView.java:663-670`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L663-L670)；[`QmlInputConnection.java:64-116`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlInputConnection.java#L64-L116)。
- **证据与影响**：文本提交、删除、Enter、剪贴板及硬键盘路径只排 GL event；触摸路径会 wakeRenderer，但这些输入没有。WHEN_DIRTY 中 queueEvent 执行 runnable 不等于 requestRender，纯本地输入属性也未必经过 controller.post 唤醒。可出现内部文字已改、屏幕直到下一次触摸/播放事件才刷新；未做输入法实机验证。
- **建议**：所有导致 scene mutation 的输入统一使用已有 wakeRenderer，覆盖 IME、硬键盘和粘贴，而非只修单一输入法。

#### ANDROID-07 · P3 · 静态推断 [INFERENCE] · 通知刷新反复完整解码没有变化的封面

- **定位**：[`PlaybackService.java:143-164`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/PlaybackService.java#L143-L164)；[`PlaybackService.java:281-321`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/PlaybackService.java#L281-L321)；[`PlaybackService.java:434-437`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/PlaybackService.java#L434-L437)。
- **证据与影响**：onStartCommand 和 main Handler 投递的播放监听进入 refresh，每次都从 cover bytes decodeByteArray，无版本复用和目标尺寸采样。暂停/恢复或悬浮锁变化也可重解同一图。已确认主线程重复 CPU/Bitmap 分配，未测耗时或掉帧。
- **建议**：按封面版本复用通知 Bitmap，按实际通知尺寸采样；沿用 AndroidColorExtractor 的采样思路，确保释放/替换所有权明确。

#### ANDROID-08 · P3 · 静态推断 [INFERENCE] · 悬浮逐字歌词每帧重复塑形全部已唱前缀

- **定位**：[`OverlayLyricWindow.java:502`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/OverlayLyricWindow.java#L502)；[`OverlayLyricWindow.java:795-804`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/OverlayLyricWindow.java#L795-L804)；[`OverlayLyricWindow.java:928-944`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/OverlayLyricWindow.java#L928-L944)。
- **证据与影响**：播放以约 16 ms 调度，sweepWidth 每帧构建 StringBuilder，对每个 syllable 再 toString/measureText；长 token 行接近结尾时反复处理递增前缀，累计字符工作接近平方级，且持续临时 String 分配。没有 trace，不宣称已测卡顿。
- **建议**：Visual/字号变化时一次缓存整体前缀累计宽度，每帧只找当前 token 与插值。不能简单逐字宽度相加，须保留连写/字距语义。

### 桌面窗口、托盘与宿主状态

#### DESKTOP-01 · P1 · 静态推断 [INFERENCE] · 屏幕对比度采样未退出就销毁 GLFW 窗口

- **定位**：[`ScreenContrastSampler.java:70-101`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/ScreenContrastSampler.java#L70-L101)；[`ScreenContrastSampler.java:104-121`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/ScreenContrastSampler.java#L104-L121)；[`DesktopLyricWindow.java:959-968`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricWindow.java#L959-L968)。
- **证据与影响**：stop 只置标记并 interrupt，不等待独立采样线程结束；采样已过 while 判断后仍可 glfwGetWin32Window 访问旧指针。浮窗销毁虽 join 渲染线程，却未 join 采样线程，存在 native use-after-destroy 风险，未在 Windows 复现。首次 exclusion 设置尚未完成时，stop 的清除也可能被随后设置覆盖；不能泛化为每次采样都会重新竞争设置。
- **建议**：建立采样退出屏障和最终恢复协议，销毁方确认结束后才释放窗口；缓存 HWND 不能替代生命周期同步。

#### DESKTOP-02 · P1 · 静态推断 [INFERENCE] · 非 Vulkan 回退的渲染致命错误会留下不可见、持锁进程

- **定位**：[`RenderThread.java:69-81`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/RenderThread.java#L69-L81)；[`RenderThread.java:182-183`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/RenderThread.java#L182-L183)；[`DesktopWindow.java:699-716`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L699-L716)；[`DesktopWindow.java:741`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L741)；[`DesktopWindow.java:1200-1217`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L1200-L1217)；[`Main.java:77-79`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/app/Main.java#L77-L79)。
- **证据与影响**：renderer 捕获错误后退出，host 只有首次 Vulkan 失败有回退，其他错误仅日志。主窗口开始 hidden，首次帧才显示并安装部分宿主服务；首次帧前失败可留下持单实例锁的无窗口进程。restore 只重启 hiddenToTray 状态，重新启动应用未必能恢复；已显示后失败则可能留下静止窗口。未注入真实图形故障复现。
- **建议**：致命 render 错误进入明确终态：可见错误入口或可靠退出并释放单实例锁；保留已有 Vulkan 一次性回退，不静默留着失效 scene。

#### DESKTOP-03 · P2 · 静态推断 [INFERENCE] · Retina 输入坐标少了窗口坐标到 framebuffer 的比例

- **定位**：[`DesktopWindow.java:1006-1015`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L1006-L1015)；[`RenderThread.java:217-220`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/RenderThread.java#L217-L220)；[`InputBridge.java:300-301`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/InputBridge.java#L300-L301)；[`InputBridge.java:384-429`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/InputBridge.java#L384-L429)；[`DesktopLyricWindow.java:588-591`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricWindow.java#L588-L591)。
- **证据与影响**：scene 使用 framebuffer/uiScale 尺寸，但主窗口输入直接 cursor/uiScale。macOS cursor 已是窗口逻辑点，framebuffer 为 2x 时命中坐标被额外缩半；浮窗同类路径已经乘 framebuffer/window 比例。鼠标、拖动、触控需一致处理；未在 Retina 设备验证。
- **建议**：统一窗口点→framebuffer→QML 的变换，复用浮窗已正确考虑比例的规则；不硬编码 macOS 乘 2。

#### DESKTOP-04 · P2 · 静态推断 [INFERENCE] · Linux SNI 把对象导出成功误作托盘注册成功

- **定位**：[`LinuxStatusNotifierTray.java:279-303`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxStatusNotifierTray.java#L279-L303)；[`LinuxStatusNotifierTray.java:341-376`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxStatusNotifierTray.java#L341-L376)；[`TrayController.java:130-139`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/TrayController.java#L130-L139)；[`DesktopWindow.java:1181-1193`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L1181-L1193)。
- **证据与影响**：install 等 exported 后就返回 true，Watcher 注册失败仅日志，Watcher 消失也无有效状态降级。此时不会尝试 AppIndicator，host 仍认为 trayAvailable；配置关闭到托盘时可隐藏主窗但没有可用图标。只影响缺失/失败 Watcher 等环境，未在该桌面会话复现。
- **建议**：分开 exported、registered、available 状态；注册失败/Watcher 生命周期通知现有 host 并决定现有 fallback/关闭策略，不能仅返回一个乐观布尔值。

#### DESKTOP-05 · P2 · 静态推断 [INFERENCE] · 外观线程清空 palette 与浮窗发布快照发生检查后竞态

- **定位**：[`DesktopWindow.java:244-249`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L244-L249)；[`DesktopLyricWindow.java:336-349`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricWindow.java#L336-L349)；[`DesktopLyricWindow.java:358-376`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricWindow.java#L358-L376)；[`DesktopLyricState.java:48-55`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricState.java#L48-L55)。
- **证据与影响**：main 的 reloadAppearance 将共享 palette 置 null；发布线程检查/构建后再次读取该字段构造快照，中间可被清空。Snapshot 允许收到 null，下游颜色发布会解引用，错误可进入浮窗关闭路径。volatile 不能保护这组检查-使用；未运行注入复现。
- **建议**：main 只发布不可变外观与版本，palette 构建/失效归发布线程；用局部有效 palette 构造一次一致快照。

#### DESKTOP-06 · P2 · 静态推断 [INFERENCE] · JSON 设置的原子写盘未覆盖跨线程修改和快照提交顺序

- **定位**：[`Main.java:140-168`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/app/Main.java#L140-L168)；[`JsonSettingsStore.java:65-100`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/settings/JsonSettingsStore.java#L65-L100)；[`DesktopLyricWindow.java:768-770`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricWindow.java#L768-L770)；[`DesktopLyricWindow.java:831-838`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricWindow.java#L831-L838)；[`StorageFiles.java:31-33`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/store/StorageFiles.java#L31-L33)。
- **证据与影响**：SettingsCore/render 与浮窗/main 共用 JsonObject；修改、Gson 序列化无完整同步，文件锁在字符串已生成之后。旧快照可晚于新快照提交并覆盖；并发结构变化也可破坏序列化，但不能声称每次必抛 ConcurrentModificationException。原子替换仍能防半文件，不负责业务顺序。位置 X/Y 还分别同步写完整文件。
- **建议**：建立单写入所有者或完整覆盖“修改→快照→有序提交”的同步边界，位置更新批量持久化。保留原子替换，禁止为每次变化无序启动异步写线程。

#### DESKTOP-07 · P2 · 静态推断 [INFERENCE] · 换音乐目录时旧 scan/watcher 可污染新目录状态

- **定位**：[`Main.java:571-594`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/app/Main.java#L571-L594)；[`PlayerController.java:4886-4894`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L4886-L4894)；[`LibraryWatcher.java:49-81`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/library/LibraryWatcher.java#L49-L81)；[`LibraryWatcher.java:96-113`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/library/LibraryWatcher.java#L96-L113)。
- **证据与影响**：每次变化独立线程扫描，完成无条件替换库；慢目录 A 后于新目录 B 完成会覆盖 B。watcher 重用 stopped/service/watchedDirs 且不等待旧 loop，旧循环可能读到新 service 并重新注册旧目录。空/无效路径直接返回，还会保留旧库。
- **建议**：扫描结果提交核对目录与代号；每代 watcher 持有自己的 service、映射和终止状态，并完成退出。仅同步 start 方法不能消除旧循环/旧结果。

#### DESKTOP-08 · P2 · 静态推断 [INFERENCE] · fc-match 超时在阻塞 readAllBytes 之后，不能限制等待

- **定位**：[`DesktopWindow.java:532-577`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L532-L577)；[`Fonts.java:178-194`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/Fonts.java#L178-L194)；[`DesktopLyricRenderThread.java:58-66`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricRenderThread.java#L58-L66)；[`DesktopWindow.java:1160-1176`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L1160-L1176)。
- **证据与影响**：进程 stdout 先 readAllBytes，再 waitFor(3s)。fc-match 卡住且不关 stdout 时超时分支根本不会执行；浮窗 loadFonts 持 QmlRuntimeLock，会阻塞共享 QML 推进，退出又等待 render 线程。未模拟挂起进程或测实机字体探测。
- **建议**：先建立有期限且可回收的进程生命周期，再收集有界输出，所有退出路径销毁子进程。保留 fontconfig/Windows 注册表/macOS 目录的原生差异。

#### DESKTOP-09 · P2 · 静态推断 [INFERENCE] · AppIndicator keepAlive 永久保留已完成的一次性回调

- **定位**：[`LinuxTray.java:83-84`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxTray.java#L83-L84)；[`LinuxTray.java:107-129`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxTray.java#L107-L129)；[`LinuxTray.java:216-228`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxTray.java#L216-L228)；[`TrayController.java:234-260`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/TrayController.java#L234-L260)。
- **证据与影响**：每次菜单标签/tooltip 更新创建回调并加入 keepAlive，g_idle_add 回调返回 false 后 source 已完成，却无移除；关闭也不清列表。使用 AppIndicator 后端长期播放会持续保留闭包和 JNA 回调资源，原生增量未测量。
- **建议**：分开整个菜单生命期信号 callback 与单次 source callback，按 source 完成/销毁释放后者。不能删除所有强引用导致 native 回调悬空。

### 桌面音频与系统媒体协议

#### AUDIO-01 · P1 · 运行复现 · 首播/seek 把暂缺数据当作 EOF，触发提前自动切歌

- **定位**：[`DesktopAudioBackend.java:535-538`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L535-L538)；[`DesktopAudioBackend.java:497-515`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L497-L515)；[`DesktopAudioBackend.java:295-320`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L295-L320)；[`DesktopAudioBackend.java:353-365`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L353-L365)；[`PlayerController.java:953`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L953)。
- **证据与影响**：decodeInto 用 -1 表示暂缺数据，primeFromCurrent 尚无 buffer 时返回 false，被转换为 draining=true；队列空即自然结束并 onComplete→autoAdvance。真实 HTTP WAV 先发 300 字节并暂扣尾部，用 OpenAL null 输出运行 actual backend，177 ms 即收到 onComplete，tailSent=false、onError=false。首播已复现；seek 同类传播由源码确认，未另跑 seek。
- **建议**：填充结果明确区分有数据、等待数据、真实 EOF，不能把是否曾成功填过一个 buffer 当作终结状态。

#### AUDIO-02 · P2 · 运行复现 · 增长缓存返回 0 与解码器契约不一致，WAV 空转/AAC 报错

- **定位**：[`GrowingFileByteSource.java:30-39`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/GrowingFileByteSource.java#L30-L39)；[`WavPcmSource.java:88-91`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/WavPcmSource.java#L88-L91)；[`WavPcmSource.java:140-143`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/WavPcmSource.java#L140-L143)；[`Mp4AacTrack.java:512-516`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/Mp4AacTrack.java#L512-L516)；[`DesktopAudioBackend.java:435-440`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L435-L440)。
- **证据与影响**：pending 当前尾部返回 0，WAV 循环只处理负值，AAC readFully 则把 0 当 no progress 错误。真实增长 WAV 起始有 4096 字节 PCM，读取 16384 字节、producer 暂停约 250 ms：观察到 43,601 次零读取，墙钟 235 ms、当前线程 CPU 234 ms，随后追加后正常返回。证明 WAV 忙等；AAC 后果仍是静态推导。
- **建议**：统一 byte source 与 decoder 的等待/EOF/数据契约，并让等待可取消。不能只在外层估计 4096 字节可读，再把第三种状态交给二态解码循环。

#### AUDIO-03 · P2 · 静态推断 [INFERENCE] · HTTP seek 只改游标，旧源阻塞无法被换曲取消

- **定位**：[`HttpByteSource.java:72-100`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/HttpByteSource.java#L72-L100)；[`HttpByteSource.java:135-181`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/HttpByteSource.java#L135-L181)；[`Mp4AacTrack.java:92-105`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/Mp4AacTrack.java#L92-L105)；[`DesktopAudioBackend.java:104-149`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L104-L149)；[`DesktopAudioBackend.java:273-305`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L273-L305)。
- **证据与影响**：单次顺序 GET 写临时文件；seek 只设 pos，read 等下载追上该偏移。尾部 moov 的 MP4 或向未下载位置跳转会等大量顺序数据。换曲/暂停只是发布新状态，revision 检查在阻塞 open/prime 返回后；持续慢速有进展的服务器可让新本地歌也长期等待旧请求。
- **建议**：明确远程顺序与 Range 读取策略；source revision 改变须解除旧读取/下载等待。仅多检查一次 revision 不会打断当前阻塞。

#### AUDIO-04 · P2 · 静态推断 [INFERENCE] · Ogg 整文件读入与复制，完整缓存到来还会重复打开

- **定位**：[`PcmSource.java:56-58`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/PcmSource.java#L56-L58)；[`OggPcmSource.java:31-38`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/OggPcmSource.java#L31-L38)；[`SeekableByteSource.java:76-86`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/SeekableByteSource.java#L76-L86)；[`DesktopAudioBackend.java:424-469`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L424-L469)。
- **证据与影响**：真实 Ogg magic 进入 readAll，ByteArrayOutputStream.toByteArray 后再 memAlloc/copy。远程首播等待整文件 EOF，存在整文件级 heap/native 多份数据；完整缓存出现会无条件重建 decoder，再做同类读入，尽管 live 可健康。只禁止未完成 Ogg 缓存切换。未测峰值内存/首声时间。
- **建议**：保留 Vorbis 支持，改进增量输入；至少避免健康 Ogg 仅因完整缓存到来而重开。不是可删的兼容死代码。

#### AUDIO-05 · P3 · 静态推断 [INFERENCE] · MP3 每次 seek 从头扫描，缓存接管也会隐式付费

- **定位**：[`Mp3PcmSource.java:113-129`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/Mp3PcmSource.java#L113-L129)；[`DesktopAudioBackend.java:469-491`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L469-L491)。
- **证据与影响**：每次 src.seek(0) 重建 Bitstream/Decoder，逐帧累加到目标，无已扫描索引复用；成本随目标前帧数增长。用户 seek、输出恢复及缓存接管都在音频线程进入它。没有逐帧合成全部 PCM，但仍读/解析目标前压缩帧；未测具体延迟。
- **建议**：在 MP3 decoder 内复用稀疏帧偏移索引并保留 VBR 实际时间和预热要求，不能用平均码率近似替换准确语义。

#### AUDIO-06 · P2 · 静态推断 [INFERENCE] · macOS artwork 的 alloc/init 所有权未释放

- **定位**：[`MacMediaControls.java:183-225`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MacMediaControls.java#L183-L225)；[`MacMediaControls.java:250-308`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MacMediaControls.java#L250-L308)。
- **证据与影响**：每次媒体状态 publish 重新创建 NSImage 与 MPMediaItemArtwork，插入字典后没有平衡调用方 alloc/init 的 release/autorelease，shutdown 也没有。Java Pointer 被 GC 不会平衡 Objective-C 所有权；有封面时暂停/恢复等通知可不断保留 native 对象。未在 macOS 测内存。
- **建议**：macOS 实现内明确 native 所有权与释放时点，按封面身份复用；不能认为字典 retain 替代调用方释放。

#### AUDIO-07 · P2 · 静态推断 [INFERENCE] · MPRIS 缺应用内 Seeked，且忽略 SetPosition 的曲目身份

- **定位**：[`MprisControls.java:344-360`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MprisControls.java#L344-L360)；[`MprisControls.java:467-523`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MprisControls.java#L467-L523)；[`MprisControls.java:578-579`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MprisControls.java#L578-L579)；[`PlayerController.java:6002-6010`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6002-L6010)。
- **证据与影响**：只在两个 DBus handler emitSeeked，publish 不观察 controller.seekRevision；界面 seek 不通知客户端。SetPosition 不读取 TrackId，过期上一首请求可操作新歌；发布 ID 仅 title+album hash，同名同专辑不同曲目本来就共用 ID，不只理论 hash 碰撞。未跑 DBus 协议场景。
- **建议**：MPRIS 发布统一观察 seek revision；用真实曲目身份生成 ID，并在执行请求前核对。不能只在 DBus 两方法重复补信号。规范见文末外部依据。

#### AUDIO-08 · P2 · 静态推断 [INFERENCE] · Linux/macOS 暂停恢复队列的位置读了尚未启动的 backend

- **定位**：[`PlayerController.java:6920-6943`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6920-L6943)；[`PlayerController.java:6075-6080`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6075-L6080)；[`MacMediaControls.java:65-100`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MacMediaControls.java#L65-L100)；[`MprisControls.java:467-468`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MprisControls.java#L467-L468)；[`WindowsMediaControls.java:540-541`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/WindowsMediaControls.java#L540-L541)。
- **证据与影响**：恢复仅设置 pendingResumeMs/UI，尚未打开音频；Linux/macOS 初始化和暂停发布读取 backend position=0，而 Windows 已使用处理恢复点的 mediaSessionPosition。重启后应用内进度可正确、系统媒体显示零；Linux 相对 Seek 还可从错误基线计算。未跑平台媒体面板验证。
- **建议**：三平台复用已有 mediaSessionPosition 作为暂停/初始化位置，不新增另一套恢复进度状态。

### QML 导航、交互与组件协议

#### UI-01 · P2 · 静态推断 [INFERENCE] · 切换本地标签后，根页面数字索引不再表示原目的地

- **定位**：[`Main.qml:141-151`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/Main.qml#L141-L151)；[`Main.qml:174-185`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/Main.qml#L174-L185)；[`Main.qml:619-645`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/Main.qml#L619-L645)。
- **证据与影响**：“我的”页 page=3 时进入设置关闭本地标签，page 未迁移到 2，返回可无匹配根内容并标题越界；反方向开启标签时原 page=2 变成本地页，未加载该页还会空白。当前本地页关闭标签已有回首页处理，首页/搜索不受这两条路径影响，不能说完全没有迁移逻辑。
- **建议**：稳定目的地 ID，或在设置变化时同步迁移 page、nextPage 和加载状态；clearPages 不重置根 page，不能指望退出设置自动恢复。

#### UI-02 · P2 · 静态推断 [INFERENCE] · 切页退出阶段点击原页，最后一次导航意图被丢弃

- **定位**：[`Main.qml:426-448`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/Main.qml#L426-L448)；[`PageMotion.qml:75-121`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PageMotion.qml#L75-L121)；[`NavigationBar.qml:108-112`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/miuix/Core/NavigationBar.qml#L108-L112)；[`LiquidGlassNavigation.qml:156-157`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/LiquidGlassNavigation.qml#L156-L157)。
- **证据与影响**：普通底栏首页→搜索，220 ms 退出阶段再点首页，switchTo 因 idx===page 早退但旧 nextPage/动画仍执行，随后切到搜索。即时动画没有该窗口，部分路由返回 Zoom 分支会停止旧过渡，不应泛化。液态底栏在上游就丢弃 currentIndex 点击，单修 Main 不覆盖该样式。
- **建议**：导航组件与根过渡共用明确的待切换目标协议；点击当前页也须处理尚未完成的旧目标，而非直接忽略。

#### UI-03 · P2 · 静态推断 [INFERENCE] · 系统 Back 没有先交给全部活动 Dialog

- **定位**：[`Main.qml:209-224`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/Main.qml#L209-L224)；[`VolumeDialog.qml:14-27`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/dialogs/VolumeDialog.qml#L14-L27)；[`InputBridge.java:151-154`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/InputBridge.java#L151-L154)；[`Dialog.qml:49-68`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/miuix/Core/Dialog.qml#L49-L68)；[`SettingsPage.qml:286-292`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/settings/SettingsPage.qml#L286-L292)；[`QPlayerActivity.java:226-230`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L226-L230)。
- **证据与影响**：返回列表漏掉手机 volumeOpen、插件贡献表单和颜色/字体选择器。手机首页开音量后 Back 会 requestExit，当前 Android 实际执行 moveTaskToBack(true)：送后台且场景/弹窗保留，不是销毁进程。设置选择器 overlay 已重挂 root，返回可先弹底层设置路由却留下弹窗。桌面非全屏 Escape 被改派 controller，绕过 Dialog 自带 Keys handler；凭据/插件管理等模态已有消费，桌面 VolumePopup 也已有 mini.handleBack。
- **建议**：统一活动弹层返回消费顺序与取消政策，保留不可自动取消的凭据确认；不要继续散落布尔特判，也不要误删已经有效的返回处理。

#### UI-04 · P2 · 静态推断 [INFERENCE] · 插件表单节点换型用旧派生值，输入缓存又缺会话边界

- **定位**：[`PluginDialogNode.qml:32-73`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PluginDialogNode.qml#L32-L73)；[`PluginDialogNode.qml:120-125`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PluginDialogNode.qml#L120-L125)；[`PluginDialog.qml:43-78`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/dialogs/PluginDialog.qml#L43-L78)；[`PlayerController.java:2354-2391`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2354-L2391)。
- **证据与影响**：① onNodeChanged 即读尚未 flush 的 nodeType，空 slot→input/switch 可漏种子；switch 的 checked 绑定最终显示 true，而缓存 switchValue 仍 false，点其他提交按钮会不一致。点开关自身会同步缓存，不能说所有开关点击都错。② input 曾成功种入 ID、用户编辑后，新会话在同 slot 复用 ID 时 appliedInputId 阻止新初值，旧文本可被提交。清空 JSON/关闭 Dialog 不销毁固定 slots，也没有显式 reset；周期刷新保留编辑和新会话初始化被混为一条规则。
- **建议**：handler 判空后直接读 node.type/source，分别保证缓存与显示一致；引入明确 dialog session 重置边界，同时不覆盖同一会话正在编辑的内容。十个固定 slot 本身符合协议上限和引擎限制，不是这里要消除的重复。

#### UI-05 · P2 · 静态推断 [INFERENCE] · ColorPicker 提交的是本次移动前的派生颜色

- **定位**：[`ColorPicker.qml:68-75`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/miuix/Core/ColorPicker.qml#L68-L75)；[`ColorPicker.qml:113-163`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/miuix/Core/ColorPicker.qml#L113-L163)；[`ColorSlider.qml:196-206`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/miuix/Core/ColorSlider.qml#L196-L206)；[`ColorPickerDialog.qml:53-65`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/dialogs/ColorPickerDialog.qml#L53-L65)。
- **证据与影响**：handler 修改 hue/saturation/value/alpha 后立即 _emit 读取旧 _selectedColor；绑定下一轮 flush 才重算。释放没有补最终提交，pendingHex 可落后最后预览一次。该代码来自 vendored miuix，属于 qml4j 兼容缺陷，不是 QPlayer 自研组件体量问题。
- **建议**：提交时直接从最新通道源属性计算一个局部颜色，同值写 color 并发 signal；记录/回馈上游兼容补丁，不依赖多等一帧碰巧刷新。

#### UI-06 · P2 · 静态推断 [INFERENCE] · 隐藏歌词布局仍有不必要时钟和 dirty 更新

- **定位**：[`PlaybackCoverImage.qml:148-152`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PlaybackCoverImage.qml#L148-L152)；[`MarqueeText.qml:70-81`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/MarqueeText.qml#L70-L81)；[`MarqueeText.qml:133-145`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/MarqueeText.qml#L133-L145)；[`LyricOverlay.qml:462-495`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/LyricOverlay.qml#L462-L495)；[`LyricOverlay.qml:405`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/LyricOverlay.qml#L405)；[`LyricProgress.qml:292-297`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/LyricProgress.qml#L292-L297)。
- **证据与影响**：黑胶 timer 仅看自身 visible，父歌词页/横屏布局隐藏不等于停止；长文本曾完成测量并开始滚动后隐藏，跑马灯也可继续。歌词页打开时三套响应式 wave phase 可同时运行，虽只显示一套；关页后三条 wave 自身 visible 会为 false，不能说它们仍全部运行。0.2.33 字节码确认 Item.visible 独立、collect/tickAnimations/Timer 不按祖先可见性剪枝。requestPaint 仅标 dirty，不证明隐藏 Canvas 做了 GPU 绘制；宿主暂停/线程销毁后也不应称仍运行。
- **建议**：显式传入“页面正在呈现且属于当前布局”的 animation-active 条件，保留视觉效果；不把自身 visible 当有效祖先可见性，不用未测 FPS/GPU 数字解释它。

#### UI-07 · P2 · 静态推断 [INFERENCE] · 虚拟列表拖动只保存索引，模型同步后可重排错误项

- **定位**：[`VirtualSongList.qml:95-110`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/VirtualSongList.qml#L95-L110)；[`VirtualSongList.qml:163-177`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/VirtualSongList.qml#L163-L177)；[`VirtualPlaylistList.qml:15-80`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/VirtualPlaylistList.qml#L15-L80)；[`SongRow.qml:480-481`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/SongRow.qml#L480-L481)；[`PlaylistListRow.qml:239-240`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PlaylistListRow.qml#L239-L240)；[`PlayerController.java:4484-4493`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L4484-L4493)；[`PlaylistGrid.qml:106-130`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PlaylistGrid.qml#L106-L130)。
- **证据与影响**：模型/数量变化不取消 _dragFrom/_dropIndex，row cancel 也当释放成功。后台订阅同步能在拖动期间删插曲目块，松手后旧索引被用于新集合并持久化；Java 未校验原歌曲身份。网格已有模型变化取消+ID 校验，是可以复用的正确对照。
- **建议**：沿用网格的取消/身份规则补全两类虚拟列表和 row cancel 协议；不为消重复把全部 delegate 硬塞进通用大组件。

#### UI-08 · P3 · 静态推断 [INFERENCE] · CoverImage 永久保留最后非空 source，空封面显示上一个实体的图

- **定位**：[`CoverImage.qml:21-33`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/CoverImage.qml#L21-L33)；[`CoverImage.qml:79-109`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/CoverImage.qml#L79-L109)；[`MiniPlayer.qml:213-223`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/MiniPlayer.qml#L213-L223)；[`LocalPlaylistPage.qml:181-188`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/pages/LocalPlaylistPage.qml#L181-L188)；[`LocalPlaylist.java:292-300`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylist.java#L292-L300)。
- **证据与影响**：_shownSource 只接收非空源，默认 fadeIn=false 不因空源隐藏。先有封面歌单→无封面空歌单，或有封面歌曲→无封面本地歌，可长期显示旧实体封面，不只加载中的一帧过渡。
- **建议**：区分同实体暂时等待与新实体明确无封面；后者清空/显示占位，不能通用地永远保留任意上一张图。

### 构建、测试与发布维护

#### BUILD-01 · P2 · 静态推断 [INFERENCE] · debug 与 release 复制的引擎构建流程已经分叉

- **定位**：[`build-debug.yml:31-39`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/build-debug.yml#L31-L39)；[`release.yml:78-85`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/release.yml#L78-L85)；[`release.yml:223-230`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/release.yml#L223-L230)。
- **证据与影响**：debug 克隆 v0.2.33 后直接 install；release 则先 versions:set 到 tag 版本。已读取该 tag 原始父 POM，其 version 仍是 0.2.32，子模块继承；因此 debug 源码构建落入旧坐标，不能兑现“不等 Central 发布”的保证。若 0.2.33 已在 Central 可继续用远端，所以不是断言当前每次 debug CI 都失败。root pom 与 Android Gradle 还各维护一次引擎版本，增加漂移点。
- **建议**：复用一份克隆/固定版本/安装配方，并让实际消费版本同源；保留 release 已有版本纠正步骤。不要通过恰好命中开发机 Maven 缓存掩盖流程错误。

#### BUILD-02 · P2 · 静态推断 [INFERENCE] · 仓库 CI 没有项目测试门禁，打包成功不覆盖行为回归

- **定位**：[`release.yml:85-109`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/release.yml#L85-L109)；[`release.yml:230-245`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/release.yml#L230-L245)；[`build-debug.yml:39-49`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/build-debug.yml#L39-L49)。
- **证据与影响**：现有三个 workflow 中 release/debug 的项目 Maven 步骤使用 skipTests，Android 只 assemble；未发现独立 PR/测试 workflow。仓库实际有 core、desktop 和两个 Android 测试源码，但未被这些流水线作为门禁执行。不能由此断言维护者本机从不跑测试；本次审查也没有运行完整套件。
- **建议**：建立独立确定性 core/decoder/关键 QML 行为门禁；平台 native/设备 smoke 与发布验证分层。先覆盖本报告的消费者可见边界，不新建只测字符串/转发的表面测试。

#### BUILD-03 · P2 · 静态推断 [INFERENCE] · 现有 QML 测试走源码解释路径，未覆盖实际 AOT/Native 入口

- **定位**：[`LocalPlaylistUiTest.java:73-78`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/test/java/dev/t1m3/qplayer/desktop/window/LocalPlaylistUiTest.java#L73-L78)；[`ResponsiveNavigationTest.java:71-74`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/test/java/dev/t1m3/qplayer/desktop/window/ResponsiveNavigationTest.java#L71-L74)；[`DesktopWindow.java:133-137`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L133-L137)；[`BundledQml.java:78-96`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/qml/BundledQml.java#L78-L96)。
- **证据与影响**：当前代表性 UI tests 用 withStockTypes+load(source)，生产使用 BundledQml 的编译工厂并限制运行时源码读取；没有找到测试直接走该工厂链。它们仍是真实行为测试，且 pom 仍把 QML 作为资源打包，不能误报“测试已经因为缺源码全部失效”。但解释路径通过不证明生成工厂、singleton、反射/JNI 可达性或发行包资源正确。
- **建议**：保留有价值源码行为测试，补实际 bundled scene/发行产物 smoke，验证动态插件容器、切页/弹窗和必要 native 回调。不要用源文本断言替代产物运行。

## 3. 冗余、不可达、失效与保留边界

“当前应用没有入口”“私有链确定不可达”“公开 API 暂无仓内消费者”“兼容/原生动态入口”是不同结论。下列分类不能混成一次批量删除。

### 3.1 可明确定位的不可达或无行为残留

| 判定 | 对象 | 证据 | 清理边界 |
| --- | --- | --- | --- |
| 确定不可达 | 旧在线搜索/专辑/艺人/分页/详情/扫码分支 | [`PlayerController.java:7548`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L7548)；[`PlayerController.java:7092-7293`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L7092-L7293)；[`PlayerController.java:9923-9929`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L9923-L9929) | onlineSourcesArePluginOnly 恒 true，旧分支位于提前 return 后；删除分支体及只服务它们的 searchWorker/retryWorker。执行器惰性创建，不能声称现存启动多跑线程。旧详情守卫另见 8070、8168、8311。 |
| 确定无行为状态 | DesktopAudioBackend.cacheCompleteFlag | [`DesktopAudioBackend.java:96-191`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/DesktopAudioBackend.java#L96-L191) | 只有声明/赋值，无读。notifyCacheComplete 有调用，但桌面实现只改这个死字段；真正接管靠 414–425 文件轮询。清字段不能误删 Android 仍使用的接口契约。 |
| 确定无行为状态 | AndroidAudioBackend.cachePendingPath | [`AndroidAudioBackend.java:55`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java#L55)；[`AndroidAudioBackend.java:294-296`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java#L294-L296) | 只写不读，Android 只消费 complete path；保留 bindGrowingCache 和完整缓存切换功能。 |
| 确定无行为状态 | LinuxStatusNotifierTray.running | [`LinuxStatusNotifierTray.java:215`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxStatusNotifierTray.java#L215)；[`LinuxStatusNotifierTray.java:315`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxStatusNotifierTray.java#L315)；[`LinuxStatusNotifierTray.java:530`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/LinuxStatusNotifierTray.java#L530) | 只有声明/赋值，不控制 GLib loop 或 shutdown，可清理这层误导状态。 |
| 确定无入口私有链 | TemperaPageRenderer.retireScenes / retiredScenes | [`TemperaPageRenderer.java:122-132`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaPageRenderer.java#L122-L132)；[`TemperaPageRenderer.java:226-227`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaPageRenderer.java#L226-L227) | retireScenes 无内部入口，唯一 add 位于其中；列表 drain 无法收到该链添加的对象。只清这组退休机制，不误删当前 scene 的真实释放。 |
| 确定空操作门控 | TemperaPageRenderer.wipeCleanup / wipeDrawn | [`TemperaPageRenderer.java:324-334`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/tempera/TemperaPageRenderer.java#L324-L334) | wipeCleanup 为空，wipeDrawn 仅门控这个空清理调用；不是说整个 wipe 绘制效果无效。 |
| 仓库无入口，内部方法 | QPlayerActivity.sharedController() / QmlGLSurfaceView.sendSyntheticKey() | [`QPlayerActivity.java:108`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L108)；[`QmlGLSurfaceView.java:486-493`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L486-L493) | 包级方法全仓引用仅定义，未发现 manifest/QML/JS/反射根；当前 IME 走专用入口。不要误删实际保存 controller 的静态字段。 |
| 冗余跨方法字段 | pendingAudioUri / pendingAudioStage | [`QPlayerActivity.java:819-851`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L819-L851) | 结果处理时赋字段再立即复制 final local，之后只清空，无恢复/取消读取协议；可局部化，不是要删除导出功能。 |
| 仓库无入口 | RenderThread.kind() | [`RenderThread.java:61-63`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/RenderThread.java#L61-L63) | 实际判断走 backend.kind()/win.kind()；Java/QML/JNI/元数据未找到该 getter 的消费者。 |
| 产品页面不可达 | RecentPage.qml | [`RecentPage.qml:8-30`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/pages/RecentPage.qml#L8-L30) | Main 无实例、recent route/Loader，也无宿主动态构造入口；AOT 全量扫描仍会编译它，不等于用户能进入。恢复产品入口或删页面；recent 后端仍有 loadRecent 调用，不能一并删。 |
| 旧状态别名无消费者 | Main.detailOpen/settingsOpen/accountOpen/cacheListOpen | [`Main.qml:30-33`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/Main.qml#L30-L33) | 当前协议已用 currentOverlay，QML/Java/测试/编译入口未找到这些旧别名消费者；清理时同步删过时注释。 |

### 3.2 当前无消费者或用途收敛候选：确认契约后再删

| 对象 | 证据 | 限制与建议 |
| --- | --- | --- |
| 旧 custom playlist 交互 API | [`PlayerController.java:3750-3768`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3750-L3768)；[`PlayerController.java:3840-3883`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3840-L3883)；[`PlayerController.java:3905-3955`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L3905-L3955) | QML/host/tests/resources/docs 无消费者，QueuePage 第二标签已迁移。可收敛交互 API 和 customPlaylistTracks 投影，但启动兼容态及 LocalPlaylistStore 旧文件读取仍需分离，不能整链删除。 |
| HttpByteSource(String) / SeekableByteSource.open(String) | [`HttpByteSource.java:40-42`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/HttpByteSource.java#L40-L42)；[`SeekableByteSource.java:63-65`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/SeekableByteSource.java#L63-L65) | 当前入口使用带 headers 重载；未发现动态根。先确认包内/对外契约；不要误删有测试入口的 PcmSource.open。 |
| PluginProviderService.recommendations(String,int) | [`PluginProviderService.java:178-181`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginProviderService.java#L178-L181) | 当前生产首页走 home，未找到该方法消费者，且 limit 未使用/响应形状另起一套；公开 Java 方法需确认外部契约后清除。 |
| PluginCredentialVault.getInteractively() | [`PluginCredentialVault.java:103-124`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginCredentialVault.java#L103-L124) | 无当前生产/QML/JS 映射入口。不能因此删除 CredentialKeyProtector 交互方法，重新启用保护仍使用。 |
| PluginCompatibility.requireHostAtLeast() | [`PluginCompatibility.java:50-53`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginCompatibility.java#L50-L53) | 没有生产调用但有测试入口；BASELINE=1.8.7/后缀比较未建立实际受影响路径，不报现存版本判断 bug。 |
| AssetResourceLoader | [`AssetResourceLoader.java:11-31`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/AssetResourceLoader.java#L11-L31) | 实际 QPlayerApplication→FileResourceLoader，后者包含 assets 分支并支持磁盘。public 引擎桥类仍须确认仓外 API，不以无应用入口直接判删。 |
| DesktopLyricState 的 pointerInside/surfaceColor/outlineColor/primaryColor | [`DesktopLyricState.java:14-18`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricState.java#L14-L18)；[`DesktopLyricState.java:52-55`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricState.java#L52-L55) | 当前 DesktopLyric QML 不消费，但该 context 全字段/方法已注册进 Native 元数据；需核对动态边界后删，不以无 Java 读取者判死。 |
| VirtualSongList.rowSelected()/selectedCount() | [`VirtualSongList.qml:228-231`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/VirtualSongList.qml#L228-L231)；[`VirtualSongList.qml:258-264`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/VirtualSongList.qml#L258-L264) | 当前无调用，逻辑已内联在 delegate 与 selectedN，重复已确认；公开 QML 方法先确认无外部使用再移除。 |
| PlaylistDetailPage.selectedCsv() | [`PlaylistDetailPage.qml:71`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/pages/PlaylistDetailPage.qml#L71) | 当前按钮直接读 tracks.selectedKey，无方法消费者；属于应用内清理候选。 |
| openUrl.*/timers.*/events.*/qr.* 接口家族 | [`PluginRuntime.java:329-346`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginRuntime.java#L329-L346) | Runtime 认识这些权限/家族，但 HostApi/controller 没对应分支。未建立像 files.* 那样的公开承诺/真实消费者，不报当前功能必坏；BACKGROUND_TIMERS 仍有后台调度，不可删权限枚举。 |

### 3.3 已确认的重复规则或重复实现

| 重复内容 | 证据 | 处置边界 |
| --- | --- | --- |
| 宿主方法权限映射 | [`PluginRuntime.java:329-346`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginRuntime.java#L329-L346)；[`CorePluginHostApi.java:333-342`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L333-L342) | 已实际漏 files.*，见 PLUGIN-01。不是纯风格重复，应成为单一声明。 |
| Track 持久化字段规则 | [`PlayerController.java:6789-6964`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6789-L6964)；[`LibraryCache.java:180-215`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/library/LibraryCache.java#L180-L215) | 已遗漏队列歌词路径，见 CORE-03。统一恢复协议，而非用 JSON 深复制代替领域模型。 |
| 启动与增量设置副作用映射 | [`SettingsCore.java:515-551`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCore.java#L515-L551) | 初始化与运行时变化两套映射，暂未发现当前值分叉；启动复用增量 handler，SettingsCatalog/SettingsCore 职责不同，不应合类。 |
| 歌词翻译/罗马音近邻匹配 | [`LyricParser.java:102-173`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/LyricParser.java#L102-L173) | 三处相同最近时间附加算法，输入均有效。提取有参数的内部匹配步骤，保留格式差异与时间边界。 |
| SHA/hex 转换 | [`CorePluginHostApi.java:980-988`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/CorePluginHostApi.java#L980-L988)；[`PluginCredentialVault.java:297-305`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginCredentialVault.java#L297-L305)；[`PluginCryptoServices.java:247-250`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginCryptoServices.java#L247-L250)；[`PluginManager.java:327-330`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginManager.java#L327-L330)；[`PluginPackageVerifier.java:190-193`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginPackageVerifier.java#L190-L193) | 五份逐字节 String.format hex 和两份 SHA 包装。可收敛内部实现，未证明是高频性能瓶颈，不按逐帧热点定级。 |
| 三个托盘后端的菜单装配 | [`TrayController.java:100-189`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/tray/TrayController.java#L100-L189) | 可共享命令模型/文字/可用性通知，不能抹平 AppKit 主队列、COM STA、GTK/GLib 所有权。 |
| debug/release 引擎构建配方 | [`build-debug.yml:31-39`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/build-debug.yml#L31-L39)；[`release.yml:78-85`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/.github/workflows/release.yml#L78-L85) | 版本固定步骤已分叉，见 BUILD-01；统一配方比同步复制注释更可靠。 |
| 两种列表/网格拖动协议 | [`VirtualSongList.qml:95-177`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/VirtualSongList.qml#L95-L177)；[`PlaylistGrid.qml:106-130`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/PlaylistGrid.qml#L106-L130) | 行为已分叉造成 UI-07；复用取消/身份校验规则，不必强行统一全部视觉 delegate。 |

### 3.4 必须保留的迁移、动态入口及合理差异

| 对象 | 证据 | 不能直接删除的原因 |
| --- | --- | --- |
| NeteaseClient 的旧凭据读取/迁移 | [`NeteaseClient.java:55-69`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/netease/NeteaseClient.java#L55-L69)；[`NeteaseClient.java:211-268`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/netease/NeteaseClient.java#L211-L268)；[`PlayerController.java:2261-2296`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2261-L2296) | 加密/明文旧凭据交接插件后才删除；整类不是死代码。旧在线方法 102–142 已 throw removed/空/no-op，其中 toggleLikeMedia 仍能进入旧调用，是“活入口、失效实现”，应明确提示插件要求而非保留假在线能力。 |
| 旧 Track ID/Source 和数据迁移 | [`Track.java:95-138`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/model/Track.java#L95-L138)；[`PlayerController.java:6859-6888`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6859-L6888)；[`PlayerController.java:2048-2077`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2048-L2077) | 旧队列及延迟路由迁移仍需要，不能按网易云插件 provider 名误判为内置在线源。 |
| 旧 DTO、.nlrc、MediaMetaIndex 格式 | [`PlayerController.java:6196-6207`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L6196-L6207)；[`MediaMetaIndex.java:57-80`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/cache/MediaMetaIndex.java#L57-L80) | ArtistRef 还服务通用歌手选择器，NeteaseLyric 读旧缓存，索引反序列化旧 Song；不能整体删 dto 包。 |
| custom-playlist.json / 旧目录 / cache index v1,v2 | [`LocalPlaylistStore.java:210-265`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/playlist/LocalPlaylistStore.java#L210-L265)；[`AppDirs.java:145-170`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/store/AppDirs.java#L145-L170) | 仍有旧数据迁移消费者；移除旧 UI 不等于可以删除数据读取和失败保留。 |
| UiContribution.source / LISTEN_TOGETHER | [`PluginPackageVerifier.java:92-101`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginPackageVerifier.java#L92-L101)；[`ProviderCapability.java:26-29`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/ProviderCapability.java#L26-L29) | 旧包哈希覆盖验证和旧清单兼容仍有效，不能按 deprecated/无新 UI 判断无用。 |
| ui.<id> 与 backgroundTick | [`PluginRuntime.java:164-168`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginRuntime.java#L164-L168)；[`PluginManager.java:248`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginManager.java#L248)；[`PluginManager.java:315-323`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/plugin/PluginManager.java#L315-L323) | 动态协议入口，普通 Java 同名引用计数不能证明不可达。插件卸载保留凭据也是 Manager 146–149 明确策略，不是清理失效。 |
| WindowChromeStub / Song 公共别名 | [`WindowChromeStub.java:5-10`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/bridge/WindowChromeStub.java#L5-L10)；[`Song.java:10-22`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/media/Song.java#L10-L22) | Android 仍注入共享 QML context；name/coverUrl/coverThumbPath 被行组件读取。平台 no-op 与扁平反射数据契约合理。 |
| JNI/JNA/Objective-C/COM/GDBus 回调 | [`DesktopNativeBrowser.java:184-202`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/app/DesktopNativeBrowser.java#L184-L202)；[`MacMediaControls.java:160-180`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/MacMediaControls.java#L160-L180)；[`WindowsMediaControls.java:259-297`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/media/WindowsMediaControls.java#L259-L297) | C++ GetMethodID、selector、vtable 与生成元数据是真实根；Structure 的字段/无参构造器也不能按 Java 调用计数删。 |
| PcmSource.open 与三种音频流适配器 | [`PcmSource.java:42-53`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/PcmSource.java#L42-L53)；[`SeekableRandomFileInputStream.java:20-67`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/SeekableRandomFileInputStream.java#L20-L67)；[`UnclosedByteSource.java:8-43`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/UnclosedByteSource.java#L8-L43) | open 有 AAC 测试消费者；JLayer 输入、jflac 具体类型及不关闭 live 源的包装各有真实职责，非可互删同义封装。 |
| miuix 导出的未实例化组件 | [`miuix/Core/qmldir:30–50`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/miuix/Core/qmldir#L30-L50) | Date/TimePicker、CanvasChart、DataTable、ColorPalette 等仍是上游模块公开 API 并参与 AOT；不归自研死代码。TabRowWithContour 虽薄但广泛使用。 |
| 液态玻璃隐藏 normal/accent 绘制源 | [`LiquidGlassNavigation.qml:55-125`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/shared-qml/components/LiquidGlassNavigation.qml#L55-L125)；[`LiquidGlassNode.java:208-235`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/LiquidGlassNode.java#L208-L235) | native 节点分别读取/回放，两棵隐藏树是效果输入，不是无用 UI。十个固定 PluginDialogNode slot 同样有协议上限/引擎限制依据。 |
| 字体字节重载、Heavy 字重、shader 资源 | [`Fonts.java:773-795`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/Fonts.java#L773-L795) | 公开字体 API 仍允许额外字重/嵌入方调用；已逐个核对当前 shader 消费者，未发现足够依据可删除的 shader。source.sha256 只有生产者也可能是溯源产物，不单凭无代码消费者删除。 |
| 旧 qplayer.mpris 开关及原生图形差异 | [`Main.java:295-299`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/app/Main.java#L295-L299)；[`RenderThread.java:149-156`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/RenderThread.java#L149-L156) | 开关仍有入口；GL/Vulkan present/swapchain 所有权不同，主窗与透明浮窗生命周期也不同，不能机械合并。 |

### 3.5 已过期或与实现矛盾的说明

| 位置 | 问题 |
| --- | --- |
| [`Main.java:220-222`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/app/Main.java#L220-L222) | 仍称三平台使用 FlatMac Swing chooser，实际 DesktopFilePicker 已为 AppKit、COM、portal。 |
| [`GpuCaches.java:6-31`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/GpuCaches.java#L6-L31) | 注释称旧 context 销毁后清理，实际 RenderThread 190–214 在旧 context 有效时清理再销毁。生命周期注释方向错误。 |
| [`HttpByteSource.java:22`](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/audio/HttpByteSource.java#L22) | Javadoc 链到不存在的 localFileBlocking；当前 FLAC 使用 SeekableRandomFileInputStream。 |
| [`CLAUDE.md` 的桌面打包说明](https://github.com/TIMER-err/qplayer/blob/db3ecc9281c82bfffa1046f353bc6851d7274fd2/CLAUDE.md) | **撤回磁盘文件过期的初判**：会话注入的旧副本仍描述 jpackage，但重新核对磁盘文件第 52–55 行，实际已是 GraalVM Native Image。该差异来自会话上下文，不作为本仓源码缺陷或修复成果。 |

## 4. 架构拆分建议：先所有权，再体积

`PlayerController.java` 为 10,368 行、504,826 字节，占 core Java 行数约 23.6%。这只用于定位审查重点；ARCH-01 的判断依据是职责、线程和失败边界，不是行数本身。

| 责任边界 | 目前混在一起的状态/副作用 | 可沿用的现有边界 |
| --- | --- | --- |
| 播放会话 | queue/playIndex/playingIntent/解析请求/恢复位置/媒体快照 | AudioBackend、PlaybackListener；新增明确的单一会话所有者，而非分散原子字段。 |
| 队列持久化 | Track 编解码、旧 Source 兼容、本地资源恢复、checkpoint | StorageFiles、LibraryCache；共用版本化恢复协议，保留迁移。 |
| 封面与缓存定位 | provider URL 变换、缩图、本地封面、下载/提升/驱逐 | DiskCache、现有封面 revision；统一 key 与在途写入所有权。 |
| 插件与账户动作 | 宿主权限、provider 账户、目标歌单能力、动态表单会话 | PluginRuntime、CorePluginHostApi、Plugin*Service/Registry/Installer；合并规则和事务，不建立第二套插件框架。 |
| 内容/页面投影 | 搜索、详情、“我的”、来源切换、QML 集合发布 | 现有 Store/Service 与稳定页面身份；QML 读取投影，不共同拥有领域状态。 |
| 设置与发布检查 | 设置副作用、跨线程写盘、HTTP release 查询、版本比较 | SettingsCatalog/SettingsCore/SettingsStore；更新检查独立并明确发布通道。 |

不应机械合并：主窗与透明歌词浮窗、GL 与 Vulkan、三平台原生线程、三种不同音频适配器、Catalog 与 Core、十个固定插件表单 slot。它们的差异有实际协议或资源所有权依据。

## 5. 运行验证记录

### 5.1 环境与隔离

- 本机 Linux、JDK 21。先解析 desktop runtime classpath，再将涉及探针的当前生产 `.java` 直接编译到独立 `/tmp` classes，覆盖既有 target 类的查找优先级；没有只依赖旧安装 jar 来声称验证当前源码。
- 实际编译覆盖：`CorePluginHostApi`、`PluginRuntime`、`DiskCache`、`GrowingFileByteSource`、`WavPcmSource`、`DesktopAudioBackend`、`PlayerController`、`PlaylistTransfer` 及三个临时 consumer。
- 网络使用本机 `127.0.0.1` 的 JDK HttpServer；插件 JS、缓存和 profile 使用独立临时目录，不使用真实账户/资料。
- 音频使用 `ALSOFT_DRIVERS=null`，运行真实 OpenAL backend/解码状态机但不向真实扬声器输出。它证明缓冲/完成回调路径，不是硬件输出质量测试。
- 临时源码、classes、classpath 文件、插件与缓存 fixture 均已清理；没有向仓库加入永久探针或测试。

### 5.2 已执行场景与结果归属

| 发现 | 实际刺激/边界 | 结果记录位置 |
| --- | --- | --- |
| AUDIO-01 | PCM WAV 长 32,000 字节数据；HTTP 先发头和少量 PCM 共 300 字节，暂扣尾部；执行实际 backend.play 并观察 callback。 | AUDIO-01 正文记录 onComplete 早于尾部且无 onError 的结果。 |
| AUDIO-02 | 真实 pending WAV 初有 4,096 字节 PCM；consumer 请求 16,384 字节，producer 延后约 250 ms 再追加；装饰器只统计实际 source.read，不伪造数据。 | AUDIO-02 正文记录 zero-read 次数、墙钟和线程 CPU。 |
| RENDER-02 | 真实 cache 当前播放键、打开的 2 MiB pending writer；将限额改为 1 MiB 后尝试提交。 | RENDER-02 正文记录文件丢失及提升失败。 |
| PLUGIN-02 | 同一本机 multipart POST，先完全不传 headers，再只增加空 headers 对象；HTTP 服务计数/回包。 | PLUGIN-02 正文记录无请求异常与对照请求成功。 |
| PLUGIN-01 | 真实 JS qplayer.register handlers→qplayer.call(files.save)，manifest 明确声明 files.save；经过真实 Runtime 和 HostApi。 | PLUGIN-01 正文记录入口拒绝；不声称测过 SAF 或桌面选择器。 |
| CORE-07 | 实际 isNewer 比较 RC→同号 stable，并比较下一数字 stable 作对照。 | CORE-07 正文记录错误等价与正常下一版本比较。 |
| PLUGIN-08 | 实际 PlaylistTransfer.parse 输入 {}、只有 name 的 JSON、明确 format 但负 formatVersion。 | PLUGIN-08 正文记录三个不应接受的格式均被接受。 |

### 5.3 没有执行、不得暗示已通过的验证

- 未运行整仓 Maven/Gradle 测试套件，未重新构建 APK/Native Image、未触发 CI、未验证发布产物。
- 未接入 Android 实机/模拟器做性能 trace、IME/SAF/跨应用 DEBUG 广播测试；Android ANR 和 OEM 生命周期后果仍为风险推断。
- 未在 Windows/macOS 验证 ScreenContrastSampler、Retina、Objective-C 内存、SMTC/WebView 生命周期；未在缺 Watcher 的 Linux 会话验证托盘恢复。
- 未启动 GUI 或虚拟桌面验证页面/弹窗视觉，也未测 FPS、GPU frame time、PSS/RSS/GC 分配。QML 问题依据当前源码及 qml4j 0.2.33 绑定/时钟语义核实。

## 6. 已排除的误判与需要更多证据的推测

- AndroidLibraryScanner 当前入口是工作线程，不能因其读取标签/图片就报告 main ANR；Fonts.warmupFromConfig 最终走异步 warmup。
- 字体 `awaitReady(3000)` 在 GL 的 onSurfaceChanged，不是 main；main 的 runOnGlThreadAndWait 单次有界约 1500 ms 可造成生命周期停顿，但不足以独自证明 ANR，也未证明 GL 死锁。
- 未发现足够证据支持 attached Android View 越线程修改导致的 Sync Barrier Leak；传感器、浮窗 update 和 WebAuth 回调有实际 main 派发。
- Android release 当前关闭 minify/resource shrink，不能报告“缺 keep 规则导致现有反射失效”。AndroidX/AAR metadata 兼容绕过已有说明，本轮未建立当前崩溃链。
- 两种虚拟列表与网格已有窗口化 Repeater，拖动释放才提交；不能写成“滚动每帧创建整个曲库”。Tempera 大量几何构造发生在建场景，LyricRenderer 有布局键与 shaping 预算，也不能一概算逐帧分配。
- Native WebView 是当前 POM 实际编译/打包的依赖，JNI 入口仍被配置并由 C++ 调用。Windows STA、GTK context、AppKit main queue 的释放协议各有真实需要；本次没有证据称它们全部死锁或可直接删 native 配置。
- Windows Native 包是否需要额外 CRT 分发尚未核实实际 PE imports/目标系统，不能仅看 DLL 复制脚本判定缺运行时。macOS/Windows 没有在本机运行，不能伪造验证。
- 先前 LinkedHashMap 构造反射故障已在当前基线修复，不重复列为当前 bug；BUILD-03 讨论的是产物路径验证缺口，而非宣称修复无效。
- 未实例化的上游组件、公开嵌入 API、凭据/旧数据迁移、追溯用 source.sha256，均不能只按“没有普通 Java 调用者”判废。

## 7. 建议修复顺序与验收边界

1. **先处理高影响根因**：正式包 DEBUG 入口、在线缓冲/EOF、队列与响应式所有权、Windows 采样退出屏障、渲染致命错误终态；Android 启停的同步全量 IO 同时收口。不要以吞异常或只改 loading 掩盖状态错误。
2. **再修事务与完成协议**：在途缓存保护、插件 Registry 提交回滚、本地歌单损坏/写盘失败、最终 SAF 成功语义、慢解析暂停/换歌请求失效；优先保证用户数据和操作意图不丢失。
3. **再做不减效果的性能改进**：静态背景真实复用、凝彩常量/图片/字体生命周期、隐藏动画门控、通知封面与歌词前缀缓存、本地歌单摘要查询。先采样再比较，不以减少效果或降低正常动画频率替代优化。
4. **清理可证明的残留后拆控制器**：先移除恒真守卫后的旧在线实现、无读状态和无入口私有链，保留旧凭据/队列/歌单迁移；随后沿第 4 节责任边界迁移全部消费者，不保留双轨兼容 shim。
5. **补齐真实行为与产物门禁**：慢网络初缓冲/seek、暂停后的解析完成、A→B→A、队列并发删除、损坏歌单恢复、安装持久化失败、Back 消费、页面设置迁移、表单换型/新会话，以及 packaged AOT/Native 的实际打开/退出。沿现有测试约定，避免只断言源码字符串、复制转发或“没抛异常”。

本节描述的是初审时的建议与验收边界；后续实施、运行验证和仍需外部前提的条目，按第 9 节逐项记录。

## 8. 外部依据

- [qml4j v0.2.33 Property setter/listener](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/engine/binding/Property.java#L101-L112) 与 [绑定失效/重算](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/engine/binding/Property.java#L293-L329)：CORE-01 的线程语义依据。QML 自身属性/动画的其余特殊行为已与 0.2.33 本地依赖字节码核对，不套用 Qt 可见性或拉取绑定假设。
- [MPRIS Player Interface](https://specifications.freedesktop.org/mpris/latest/Player_Interface.html)：AUDIO-07 的 Seeked/SetPosition 规范依据。
- [AOSP GLSurfaceView](https://android.googlesource.com/platform/frameworks/base.git/+/master/opengl/java/android/opengl/GLSurfaceView.java)：ANDROID-06 的 queueEvent 与 requestRender 区别。
- [qml4j v0.2.33 原始父 POM](https://raw.githubusercontent.com/TIMER-err/qml4j/v0.2.33/pom.xml)：BUILD-01 已读取的版本坐标证据，不能只从 tag 名假设 POM 版本一致。

## 9. 修复执行记录

### 9.1 远端整合

- 根据用户后续要求，从权威远端 `upstream/master` 拉取并快进：`db3ecc9` → `17f3856bdd2f67ac43bc3e0f30b149c32c5e692c`；仍在本地 `master`，未创建分支、未强制覆盖或推送。
- 本地原有审查文档保留。直连 GitHub TLS 两次失败后，使用已存在的本机 Clash HTTP 代理完成 fetch；代理仅用于该命令，没有改写仓库或全局 Git 配置。
- `DESKTOP-03` 的 Retina 输入坐标已由远端 `14397e9` 修复，保留实现及回归测试，不重复替换。
- `CORE-01` 的 `playAt/pump` 与 `DirtyQueue.flush` 已由远端 `17f3856` 部分统一到共享 `QmlRuntimeLock`；队列快照及其他异步发布路径继续核对、补齐。
- `DESKTOP-02` 的 application-frame 崩溃由远端增加 render-thread respawn；首帧/后端失败终态及持续失败的恢复边界仍需修复。
- 同时保留远端新增的 macOS Dock 恢复/退出、搜索历史触发及 Android 悬浮歌词转屏/锁定行为。

### 9.2 实施与验证原则

- 分开记录“代码已实施”和“行为已验证”，不把构建通过、局部单测或远端提交存在等同于全部路径验证完成。
- 优先修复 P1、事务及用户数据问题，再处理不减效果的性能优化、交互与确定残留；旧凭据/队列/歌单迁移、原生动态入口和必要平台差异继续保留。
- 构建与运行在模块改动汇合后统一进行，`player-core` 修改后执行 install，避免桌面端使用旧 jar。
- 当前 ADB 没有已连接设备；Android 真机行为、Windows/macOS 原生行为不能在本机冒充已实测。
- 初审关于 `CLAUDE.md` 打包说明的判断已更正：磁盘文件原本已更新为 GraalVM，不能将会话注入的旧上下文当作当前文件内容，也不会重复修改已经正确的说明。

### 9.3 本轮代码变更与剩余边界

下表列的是实际落地及验证范围，不是把所有记录统一改成“已完全解决”。`ARCH-01` 已拆出责任，但控制器仍是较大 Facade；`ARCH-02` 需要 qml4j 上游公共 API；平台原生行为和可见界面另有明确验证限制。

| ID | 本轮落地 | 验证状态与边界 |
|---|---|---|
| ARCH-01 | 拆出 PlaybackSession、PlaybackSnapshot、QueuePersistence、ArtworkResolver、ReleaseUpdateChecker、AudioCacheCoordinator；控制器保留 QML 兼容的统一入口，尚不能称已消除全部跨域耦合。 | 已拆出责任；控制器仍是较大 Facade，不宣称已彻底解耦。 |
| ARCH-02 | 保留单一私有 ABI 适配层；已记录缺失的上游 registry 枚举和完整 CompiledScene 捕获公共契约，不能在当前 qml4j 版本假称完成。 | 外部前提缺失；当前版本不能完成公共 API 切换。 |
| ARCH-03 | 展开 Native 元数据发现、归并与输出职责，保留反射根用途说明；实际退出暴露的 SNI hidden-lambda 回调改为普通 Callback 类，纳入既有字节码发现。 | 元数据生成、Linux Native 构建、产物 AOT 自检及实际托盘退出已过，详见 9.8。 |
| CORE-01 | 队列/响应式状态统一进入 QmlRuntimeLock；宿主读取 immutable PlaybackSnapshot，宿主外部回调不持状态锁；退休按实际 IO producer 退出后释放资源。自动更新请求由控制器持有并在退休时主动取消真实 socket，等待实际 worker 退出。 | 293 项核心测试及恢复/退休 smoke 已过；真实 Native 在未完成 HTTPS 响应时正常退出，服务器观察到连接中断，详见 9.8。 |
| CORE-02 | 流解析及音频回调绑定请求代次和 Track 实例，提交前复核当前请求、暂停意图及最新 seek。 | 核心代次、暂停和 seek 回归已过；本地实际恢复已验证，不冒充已在 Native 中接入所有在线来源。 |
| CORE-03 | QueuePersistence schema 2 保存全部本地歌词路径，恢复时补发现 sidecar；保存返回可等待的 future。 | 实际恢复/落盘 smoke 已过；真实 Native 显示本地歌词，MPRIS 暂停恢复位置为 1.5 秒。 |
| CORE-04 | 封面、歌词、缓存目录 IO 移出状态/渲染临界区，缓存容量变更合并调度。 | 核心缓存/扫描回归已过；真实 Native 完成隔离曲库扫描，不据此编造帧耗时改善数值。 |
| CORE-05 | ArtworkResolver 按来源处理封面 key；网易缩图规则只用于对应旧来源。 | 核心封面来源回归已过；真实 Native 本地 PNG 封面可见。 |
| CORE-06 | 按目标 provider 的登录与能力判断歌单动作；创建入口由实际可用的来源集合决定。 | 核心能力/权限回归已过；隔离 Native profile 未安装第三方来源，不冒充真实账号联调。 |
| CORE-07 | ReleaseUpdateChecker 比较完整预发布语义，同版本正式版高于 RC，保留 debug/performance 策略。 | 版本比较回归已过；真实 Native 经 TLS 1.3 接收并显示测试更新，未下载或安装，详见 9.8。 |
| PLUGIN-01 | files 权限与 host dispatch 共用 PluginHostMethods 映射，避免双表漂移。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-02 | 省略 headers 的 multipart 使用可变工作 map，保留上传字段与二进制语义。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-03 | 暂时 DNS IO 失败不进入永久授权拒绝缓存。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-04 | Registry 先持久化再发布；安装/启用失败回滚运行时、主来源及顺序，保留磁盘仍可能引用的包。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-05 | 歌单存储显式报告保存失败；损坏原文件不被默认空状态覆盖，修复后可重新加载。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-06 | contains 直接查询，列表只构建 immutable Summary，不为成员检查深复制 tracks。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-07 | 凭据恢复按格式、密钥和 IO 原因记录不含凭据内容的分类诊断。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| PLUGIN-08 | 导入要求真实 format/version 字段和受支持整数版本，不再默认接纳任意 JSON 对象。 | 核心全量测试已过；真实 HTTP 取消及慢速下载回归已过。 |
| RENDER-01 | 静态背景以实际饱和度版本判断失效，100 与未设置不再逐帧重建；动态模式保持动画。 | 真实 GTX 1050 OpenGL 像素 smoke 已过，静态/改饱和度/动态三态分别验证。 |
| RENDER-02 | WriteLease 同时保护目标、pending 与完成发布窗口；活动 pin 在释放写 lease 前提交，停机取消须等真实 writer 退出。 | 核心缓存/扫描行为测试已过。 |
| RENDER-03 | 库 freshness 包含 sidecar 与提取资源状态；支持歌词/封面新增删除及缺失提取物恢复。 | 核心缓存/扫描行为测试已过。 |
| RENDER-04 | 凝彩字形常量和每帧准备复用，避免多层绘制重复计算。 | 桌面字体/资源回归及 GTX 1050 字形 GPU 像素测试已过；GPU 补测 1 项、0 跳过，正常进程退出。 |
| RENDER-05 | 图片读取/解码移到后台；失败按资源版本缓存，worker/render 之间显式移交与释放图像。 | 图像读取与失败缓存回归已过；真实 Native 本地封面可见。 |
| RENDER-06 | 连续浮点字号使用有界 owned 字体池，回收前失效关联 glyph/TextLine，不释放借用的 shared 字体。 | 字体池/像素回归及实际 GPU 用例已过；未降低像素断言或以跳过替代通过。 |
| ANDROID-01 | ENGINE_IO 承担迁移、初始化和状态落盘；EngineStartup 管理异步接管、过期完成和退役，不在 main join。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-02 | DEBUG 广播整组注册要求 FLAG_DEBUGGABLE，正式/performance 包不导出控制入口。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-03 | SAF 复制全程后台执行；AudioSaveRequest 只在最终目标写入和关闭成功后完成，取消清理暂存。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-04 | Activity hook 以 owner token 解绑；长期 overlay 设置由应用级窗口持有，过期 Activity 不能回写新实例。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-05 | 同步及异步 MediaPlayer 错误进入同一 generation 保护的恢复路径。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-06 | IME、硬键盘及粘贴在投递后唤醒按需渲染。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-07 | 通知使用一次 PlaybackSnapshot；仅封面版本/目标尺寸变化时异步解码，过期结果不发布。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| ANDROID-08 | 悬浮歌词在内容 build 阶段缓存前缀，保持原 kerning，不再每帧全部重新塑形。 | Android 23 项单测和 debug APK 构建已过；无设备，未做真机确认。 |
| DESKTOP-01 | 屏幕采样器停止并 join 后才恢复 exclusion 和销毁 GLFW 窗口，HWND 查询留在主线程。 | 桌面回归和 Linux Native 已过；Windows 采样器停机未在本机实跑。 |
| DESKTOP-02 | 启动/致命渲染失败进入终态并清理，恢复次数有界；Main 清理后以失败状态退出。 | 桌面行为回归已过；真实 Linux Native 完成 GPU 退休、托盘恢复和正常退出。 |
| DESKTOP-03 | 上游 14397e9 已修 Retina 坐标变换，本次保留，不重复回滚或覆盖。 | 上游已修复，保留回归；本机未运行 Retina/macOS。 |
| DESKTOP-04 | 区分 SNI 对象导出与托盘注册；处理 watcher 消失/重现，不可用托盘不隐藏窗口。 | Linux SNI 真实注册、隐藏/恢复和正常退出已过；未实际重启系统托盘 watcher。 |
| DESKTOP-05 | 外观变更经单一 publisher 发布 immutable appearance，不再跨线程清空可变 palette。 | 桌面外观回归和 Native AOT/theme 隔离自检已过。 |
| DESKTOP-06 | JSON 设置的修改、快照序列化和写盘在同一所有权边界，窗口位置成对保存。 | 设置持久化回归及真实 Native 正常退出已过。 |
| DESKTOP-07 | 扫描/监控绑定目录 generation/session；切换或无效空目录不会发布旧结果。 | 扫描/监控回归已过；真实 Native 日志确认读取 3 首缓存曲目并完成扫描。 |
| DESKTOP-08 | fc-match 输出先重定向临时文件并有界等待，退出后才读取，超时能真正终止进程。 | 相关桌面回归及字体/AOT 资源自检已过；未模拟本机 fc-match 超时。 |
| DESKTOP-09 | AppIndicator 一次性 idle callback 的 native destroy-notify 释放强引用，长期回调仍保活。 | 实现及桌面构建已过；本机实际使用 SNI，未触发 AppIndicator fallback，不能替它宣称原生运行通过。 |
| AUDIO-01 | 区分暂缺数据、等待与 EOF，首播/seek 无数据时不误触发自动切歌。 | 真实 HTTP、增量解码、OGG/MP3 PCM/seek 回归已过；使用 null 音频设备，不冒充实听。 |
| AUDIO-02 | 增长缓存阻塞等待或响应取消/缓存晋升，不把临时 0 交给解码器。 | 真实 HTTP、增量解码、OGG/MP3 PCM/seek 回归已过；使用 null 音频设备，不冒充实听。 |
| AUDIO-03 | HTTP sparse Range 与 source generation 支持缺口补取、seek 和换源取消，不等待旧 socket 读取。 | 真实 HTTP、增量解码、OGG/MP3 PCM/seek 回归已过；使用 null 音频设备，不冒充实听。 |
| AUDIO-04 | 远端 Ogg 用增量 pushdata，本地 FileChannel/native buffer 解码，健康流不因缓存完成而重开。 | 真实 HTTP、增量解码、OGG/MP3 PCM/seek 回归已过；使用 null 音频设备，不冒充实听。 |
| AUDIO-05 | MP3 保存稀疏 VBR seek index 与预热状态，缓存接管复用索引；以真实 PCM 比较 seek 结果。 | 真实 HTTP、增量解码、OGG/MP3 PCM/seek 回归已过；使用 null 音频设备，不冒充实听。 |
| AUDIO-06 | macOS artwork 按资源版本复用，平衡 alloc/init 的所有权。 | 实现已落地；macOS 原生所有权尚未在本机运行。 |
| AUDIO-07 | MPRIS 使用无碰撞 canonical track ID，SetPosition 复核当前曲目，Seeked 随 seek revision 发布。 | 身份/位置回归已过；真实 Native MPRIS 读取 Position、Volume 及 Quit 已验证；未单独采集实时 Seeked 信号。 |
| AUDIO-08 | 各媒体宿主从同一 snapshot 读取暂停恢复位置，不再依赖未启动 backend 的位置。 | 快照回归已过；真实 Native 暂停恢复时 MPRIS Position 返回 1500000 微秒，而非 0。 |
| UI-01 | 可选本地页变更按根目的地身份迁移 page/nextPage 与 lazy-loaded 状态。 | 生产 QML 输入/状态回归已过；Native 本地页可正常导航，选项热切换由回归覆盖。 |
| UI-02 | 点击当前页撤销未完成切页，PageMotion 还原根状态；普通/液体玻璃导航保留最后点击意图。 | 生产 QML 回归已过；真实 Native 液体玻璃导航在开始切页后再次点击当前页，最终留在最后选择的页面。 |
| UI-03 | 统一 Back 先交活动 Dialog、mini volume、歌词偏移等局部弹窗，再退出页面，保留不可隐式取消的确认流程。 | QML 回归已过；真实 Native 音量及歌词偏移弹窗均先关闭、不越级退页；偏移补修及失败前/成功后证据见 9.8。 |
| UI-04 | 表单从最新 node 种子读取，持久节点通过 session revision 重置；同会话刷新不覆盖编辑。 | 生产 QML 输入/状态回归已过；未在隔离 Native profile 中安装实际插件测试账号表单。 |
| UI-05 | ColorPicker 在同一事件内从最新通道算出并提交同一个颜色，不读取旧派生绑定。 | 同事件最新通道回归已过；Native 未手动操作颜色选择器。 |
| UI-06 | 歌词时钟按页面/布局可见性门控，Marquee 检查祖先有效可见性，不改布局和视觉效果。 | 生产 QML 回归已过；真实 Native 歌词和页面切换可见验证已过，未据此编造隐藏态 CPU 或帧耗时数据。 |
| UI-07 | 拖动同时保存实体身份与模型版本，模型变更撤销拖动，释放前复核，不按旧索引提交。 | 生产 QML 实际指针、长按、跨行及释放回归已过；Native 未人工重排真实用户歌单。 |
| UI-08 | CoverImage 收到显式空 source 时清图，实体切换不保留上一首封面。 | 显式空 source 回归已过；真实 Native 本地 PNG 封面可见，不用“可见图片”替代清图断言。 |
| BUILD-01 | CI 共用本地 build-engine action；root POM 唯一 qml4j 版本供 Maven/Android 使用。 | 本地核心/桌面/Android/AOT 门禁已验证；GitHub Actions 本轮未触发。 |
| BUILD-02 | 固定 Surefire；push/PR/release/debug 接 core/desktop 行为测试，Android 打包前执行 unit tests，音频回归使用真实 FFmpeg fixtures。 | 本地核心/桌面/Android/AOT 门禁已验证；GitHub Actions 本轮未触发。 |
| BUILD-03 | BundledQmlSmoke 使用实际打包 AOT 场景及 native 字体/布局/资源；Maven 通过独立 JVM 的 Main --build-smoke 运行，Native 产物也执行相同入口，避免 JNI/进程级字体 worker 滞留在 Maven classloader。 | 本地核心/桌面/Android/AOT 门禁已验证；GitHub Actions 本轮未触发。 |

### 9.4 清理、重复与兼容附表的处理

编号仅用于本次处理记录，与第 5 节原表逐行顺序对应。公开候选没有新外部契约证据时不贸然删除；托盘菜单装配没有强行抽成掩盖平台所有权的统一层。

| 记录 | 对象 | 决定 |
|---|---|---|
| D01 | 旧在线搜索/专辑/艺人/分页/详情/扫码分支 | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D02 | DesktopAudioBackend.cacheCompleteFlag | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D03 | AndroidAudioBackend.cachePendingPath | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D04 | LinuxStatusNotifierTray.running | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D05 | TemperaPageRenderer.retireScenes / retiredScenes | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D06 | TemperaPageRenderer.wipeCleanup / wipeDrawn | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D07 | QPlayerActivity.sharedController() / QmlGLSurfaceView.sendSyntheticKey() | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D08 | pendingAudioUri / pendingAudioStage | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D09 | RenderThread.kind() | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D10 | RecentPage.qml | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| D11 | Main.detailOpen/settingsOpen/accountOpen/cacheListOpen | 已删除确定的无入口/无行为部分；保留真实完成回调、旧数据读取及当前 scene 释放。 |
| C01 | 旧 custom playlist 交互 API | 旧 custom UI 交互入口已收敛，旧文件迁移保留。 |
| C02 | HttpByteSource(String) / SeekableByteSource.open(String) | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C03 | PluginProviderService.recommendations(String,int) | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C04 | PluginCredentialVault.getInteractively() | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C05 | PluginCompatibility.requireHostAtLeast() | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C06 | AssetResourceLoader | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C07 | DesktopLyricState 的 pointerInside/surfaceColor/outlineColor/primaryColor | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C08 | VirtualSongList.rowSelected()/selectedCount() | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C09 | PlaylistDetailPage.selectedCsv() | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| C10 | openUrl.*/timers.*/events.*/qr.* 接口家族 | 保持审慎：不以没有普通 Java 调用判删公开/动态接口；外部契约未获新证据。 |
| U01 | 宿主方法权限映射 | 权限与 dispatch 单表。 |
| U02 | Track 持久化字段规则 | 队列 schema 2 统一完整恢复协议。 |
| U03 | 启动与增量设置副作用映射 | 初始化复用增量 handler。 |
| U04 | 歌词翻译/罗马音近邻匹配 | 共享最近时间匹配步骤。 |
| U05 | SHA/hex 转换 | PluginHashes 统一实现。 |
| U06 | 三个托盘后端的菜单装配 | 未强行统一菜单装配；平台线程/释放协议保留，重复仍作为结构性记录。 |
| U07 | debug/release 引擎构建配方 | 共用 build-engine action。 |
| U08 | 两种列表/网格拖动协议 | 统一取消和身份复核规则；视觉 delegate 继续分离。 |
| K01 | NeteaseClient 的旧凭据读取/迁移 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K02 | 旧 Track ID/Source 和数据迁移 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K03 | 旧 DTO、.nlrc、MediaMetaIndex 格式 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K04 | custom-playlist.json / 旧目录 / cache index v1,v2 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K05 | UiContribution.source / LISTEN_TOGETHER | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K06 | ui.<id> 与 backgroundTick | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K07 | WindowChromeStub / Song 公共别名 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K08 | JNI/JNA/Objective-C/COM/GDBus 回调 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K09 | PcmSource.open 与三种音频流适配器 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K10 | miuix 导出的未实例化组件 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K11 | 液态玻璃隐藏 normal/accent 绘制源 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K12 | 字体字节重载、Heavy 字重、shader 资源 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| K13 | 旧 qplayer.mpris 开关及原生图形差异 | 保留：理由见原审查 5 节；不把兼容、动态根、公开上游 API 或真实平台差异当冗余删掉。 |
| S01 | Main 旧 Swing chooser 注释 | 与当前实现对齐。 |
| S02 | GpuCaches context 销毁顺序注释 | 与当前实现对齐。 |
| S03 | HttpByteSource 失效 Javadoc 链接 | 与当前实现对齐。 |
| S04 | CLAUDE.md 打包说明初判 | 撤回误判：磁盘文档原已为 GraalVM，未修改。 |

### 9.5 已实际执行的验证

| 路径 | 实际执行与结果 | 不能据此声称的内容 |
|---|---|---|
| 共享核心与 AOT 工具 | `mvn -pl player-core,qml-aot-tool -am install`，最终 293 项测试全部通过，0 失败/错误/跳过；两个 jar 已安装本地仓库 | 不把源码 target 8 改成 21，不使用旧 core jar 验证桌面 |
| 桌面完整行为集 | 最终 `mvn -pl desktop-host verify`，配真实 FFmpeg fixtures、system allocator 与 null 音频设备；161 项、0 失败/错误，1 项因 GLFW 图形环境跳过；随后显式传入当前真实会话，GPU 单项补测 1 项通过、0 跳过 | 全量运行本身仍有 1 项跳过，不改写为单次全套 0 跳过；该用例已有独立成功执行。不宣称扬声器实听 |
| 桌面实际 AOT 入口 | `mvn -pl desktop-host exec:exec@smoke-packaged-qml`，独立 JVM 的 `Main --build-smoke` 输出 `Bundled QML scene/binding/theme isolation smoke passed` | 不用 source QML 测试替代打包 AOT 入口；不关闭 Maven daemon 清理来压告警 |
| Android | `:app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2`，限制 Gradle 堆；23 项单测全部通过，debug APK 成功 | ADB 当前无设备；未声称 MediaPlayer、通知、IME、SAF 或帧耗时已在真机确认 |
| Linux Native Image | 最终 GraalVM 产物重建成功；该产物 `qplayer --build-smoke` 返回 0 并输出同一 AOT/binding/theme 成功消息；真实可见界面、TLS、SNI 托盘恢复和退出也已执行，详见 9.8 | 未据此宣称 WebView 实际加载、AppIndicator fallback、Windows/macOS 或 Android 真机均已验证 |
| 本地恢复与落盘 | 隔离 profile + 真实 WAV/PNG/LRC；输出 `RESTORED_METADATA=true PAUSED_POSITION_MS=1500 LOCAL_LYRICS=true RETIREMENT_AND_PERSISTENCE=true` | 不触碰用户曲库、缓存、凭据或设置 |
| 退休中的 HTTPS 更新 | 本地阻塞 CONNECT/TLS peer 在生产更新路径复现停机被阻塞；修复后实际输出 `UPDATE_HANDSHAKE_CANCELLED_AND_CONTROLLER_RETIRED=true`；永久回归还检查真实 socket 关闭 | 不靠缩短正常 connect/read timeout 或仅完成 future 掩盖仍活动的请求 |
| 缓存传输 | 原 5 秒取消用例通过；新增空闲 1.5 秒后继续传输仍成功；lease 直到真实 writer 终止后释放 | 不把派生 future 取消当作 producer 已停止 |
| 真实静态背景 GPU | GTX 1050/OpenGL 实际 GPU readback：静态饱和度 100 跨时间像素不变，改 140 后像素更新，动态模式仍变化 | 未以停掉动态效果或降低画质换性能 |
| 窗口字形 GPU 补测 | fixture 在 `glfwInit` 后设置窗口 hints，在 context 有效时释放场景资源；Linux Wayland 会话匹配生产的 XWayland/EGL 与既有驱动环境策略。独立 JUnit 及 Maven GPU 单项均通过，后者 1 项、0 失败/错误/跳过，进程正常退出 | 早期错误平台设置、锁屏等待和失败退出不算通过；未改变生产渲染策略、降低像素阈值或抑制 fork 失败 |
| 真实桌面可见验证 | 用户解锁新 Plasma 会话后，在真实 430×900 Native 窗口验证本地封面/歌词、液体玻璃导航、音量弹窗、歌词偏移 Back、MPRIS、托盘退休/恢复与正常退出，详见 9.8 | 不使用虚拟桌面；不把锁屏黑图判为业务渲染故障；临时 profile 与测试更新均不污染用户数据 |
| CI 与异平台 | 本地执行核心、桌面、Android、AOT、Native 构建路径；4 个改动的 CI YAML 通过 Bun YAML 解析及静态 needs 目标检查 | 未将本地 YAML 检查冒充 actionlint 或 GitHub Actions 执行；Windows/macOS 的原生线程和发布产物未在本机验证 |

### 9.6 集成验证中补齐的实际问题

- 缓存退休不能等待一个永远不完成的地址解析 future，也不能提前释放仍在写入的文件：拆成停止接收、真实传输取消、producer 退出确认及 lease 释放。
- `HttpURLConnection.disconnect()` 会被阻塞中的读取牵制，线程 interrupt 也不等于关闭 socket。插件 Host HTTP/bytes/download、Catalog 和自动更新改用维护中的 OkHttp 5.5.1 公共 `Call.cancel()`；保留原正常请求超时和字节/gzip/重定向语义。Maven 使用 JVM artifact，Android runtime 选择 Android variant；Native 使用 jar 自带配置，未添加无依据的反射清单。
- 删除构造期 eager Catalog 联网，改由实际来源设置入口按需加载；不让无关控制器测试访问真实 GitHub。
- AAC faststart 在分块初次只返回 26/27 字节时，解析器跳过 28 字节 `ftyp` 会投机发 Range；不能在替代响应尚未接受时断掉仍能前进的原下载。已修交接，新增真实短首块、串行 HTTP 服务回归。
- 修复右键揭示重排握柄的实际交互；歌曲 fixture 提供真实稳定身份。网格测试改为实际指针按下、推进长按时钟、跨行移动和释放，删除手写私有 `_dragFrom/_dropIndex` 及复制生产布局公式的测试搭建。
- 本地封面旧文件只在新状态成功持久化后删除；测试等待最终删除，而不是只看到 UI 路径已切换就判定清理完成。
- 新 QML 回归 fixture 的全局 `id: app` 替换会误改 `appUpdateDialog`；改为只替换准确根 id，不改生产布局规避解析失败。
- 删除只验证手写 `synchronized` 与 JVM `Thread.State` 调度状态的旧用例，不延长等待或重钉实现状态；平台 native 字体像素测试移入实际供应 Skija native 的桌面模块。
- `PlayerController` 的 ownership 改动仅按 JDK 公共 AST 找到的已变更方法范围整理缩进，未全仓格式化。没有版本号、tag、release、commit 或 push 操作。

### 9.7 明确未完成/不冒充完成的前提

1. **ARCH-02：上游公共 API。** qml4j 0.2.33 没有 registry 类型/singleton 枚举及完整 `CompiledScene` 捕获公共契约；需要 resources、context、lazy sources、独立命名空间与完整返回值。当前单一私有 ABI 适配层继续工作，不能用同版本私改依赖、空壳适配或反射别名假称完成。
2. **真实桌面解锁前提已解除。** 用户重新登录并解锁 Plasma 后，已完成可达的实际 Native 验证，详见 9.8；不再把旧锁屏状态列作当前阻塞。
3. **Android/Windows/macOS 运行条件。** 当前无 Android 设备，也无可运行的 Windows/macOS 环境。本轮完成可达构建与行为测试，不伪造跨平台运行结论。
4. **结构性余量。** Controller 已实质拆分，但仍是较大 QML Facade；跨平台托盘菜单装配仍有重复。没有把“文件变短”或“不强行统一平台协议”写成所有架构问题已彻底消失。

### 9.8 解锁后的实际 Native 回归与新增修复

运行环境为本机已解锁的 Plasma Wayland 会话，生产逻辑选择 XWayland/EGL，窗口 430×900，真实 GTX 1050。只操作本次启动的进程和窗口；曲库、队列、设置、TLS 证书与代理均位于隔离临时目录，没有使用虚拟桌面或操作用户账号数据。

- **实际显示与交互：** 本地 WAV/OGG/MP3 的 PNG 封面可见，暂停恢复的 LRC 显示 `Restored local lyrics`。液体玻璃 MiniPlayer 和导航正常显示；切页开始后再次点击当前页，最后点击意图生效。音量通过真实跨帧按住拖动改为 27%，界面与 MPRIS `Volume=0.27` 一致；MPRIS 暂停恢复 `Position=1500000` 微秒。
- **歌词偏移 Back 的失败前/成功后：** 实际 Native 中，偏移弹窗打开后 Esc 原先直接退出歌词页。补充 `LyricOverlay.handleBack()`，由 `Main.handleBack()` 先委派局部弹窗，关闭动画期间也消费 Back；未改布局、中心缩放原点或动画样式。新增生产 QML 行为回归，相关类 12 项通过。最终 Native 重建后重放同一操作，偏移弹窗关闭、歌词页仍保留；下一次 Back 才退出歌词页。
- **Native 退出反射缺口的失败前/成功后：** 实际 MPRIS `Quit` 曾返回成功但进程退出 -6。首个错误是 JNA 探测 SNI `stopLoop` 的 hidden-lambda `OPTIONS` 字段触发 `MissingReflectionRegistrationError`，不是只根据后续 NVIDIA 原生崩溃判断驱动问题。将该长期 `SourceFunc` 改为普通 Callback 类，使既有字节码元数据发现可覆盖具体类；未硬编码不稳定 lambda 类名或添加猜测反射根。最终同一路径进程退出 0，未再出现该异常。
- **托盘与 GPU 生命周期：** 根页面 Back 隐藏窗口后，日志确认 `render thread + GPU destroyed`，本次进程不再有可见托管窗口。再次启动同一隔离 profile，第二实例返回 0，原实例日志确认 `restored from tray`、`respawn=true` 及首帧完成；实际截图恢复完整界面，随后正常退出 0。
- **真实 Native HTTPS：** 隔离可信证书与本地 CONNECT fixture 完成 TLS 1.3；实际生产更新路径解析并显示测试版本 `v999.0.0` 的更新弹窗。只选择“稍后”，没有下载或安装。另一次请求故意只返回 JSON 前缀并保留未完成响应，此时经 MPRIS `Quit`，Native 在约 68 毫秒内退出 0；服务端 TLS 接收观察到 `EPIPE`，确认连接中断，而非等待读取超时或由服务端先放行响应。
- **GPU 门禁环境：** 新会话的 DISPLAY、WAYLAND_DISPLAY、XAUTHORITY 等必须显式传给独立工具进程，不能以 notebook 内已更新的环境推断工具也已更新。修正临时验证命令的环境传递后，Maven `TemperaGpuTextTest` 在真实会话中 1 项通过、0 跳过；没有为了过测改生产窗口策略或像素断言。
- **最终构建与边界：** 最新 QML 与回调补修已纳入 Linux Native 构建；最终产物 `--build-smoke` 正常返回 0。Android 23 项单测与 debug APK 构建通过，但当前无设备；Windows/macOS、真实 WebView 页面加载、AppIndicator fallback 和第三方插件账号联调不在本机已验证范围。

本轮未提交、推送或发版。`ARCH-02` 仍受 qml4j 上游公共接口缺失阻塞；其余结构性余量见 9.7，未将“实施修复”改写为全部架构问题均已消失。
