<p align="center">
  <img src="docs/icon.png" width="128" alt="QPlayer icon">
</p>

<h1 align="center">QPlayer</h1>

<p align="center">
  <b>简体中文</b> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <b>一个界面由 QML 渲染、歌词由宿主手工绘制的网易云音乐播放器,同时支持安卓与桌面。</b><br>
  运行在 <a href="https://github.com/TIMER-err/qml4j">qml4j</a> 上——一个用纯 Java 实现的 QML 引擎,不依赖 Qt 和 C++。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%2026%2B%20%C2%B7%20Desktop-A4C639" alt="Android 26+ · Desktop">
  <img src="https://img.shields.io/badge/graphics-OpenGL%20%2F%20Vulkan-CC3333" alt="OpenGL / Vulkan">
  <img src="https://img.shields.io/badge/UI-QML%20%2F%20Material%203-7C6CF0" alt="QML / Material 3">
  <img src="https://img.shields.io/badge/engine-qml4j-465BA6" alt="qml4j">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0"></a>
</p>

---

<p align="center">
  <img src="docs/screenshots/1.jpg" width="24%" alt="首页(浅色,莫奈取色)">
  <img src="docs/screenshots/2.jpg" width="24%" alt="首页(深色)">
  <img src="docs/screenshots/3.jpg" width="24%" alt="歌词(罗马音 + 翻译)">
  <img src="docs/screenshots/4.jpg" width="24%" alt="歌词(波浪进度条)">
</p>
<p align="center">
  <sub>首页 · 浅色莫奈 &nbsp;|&nbsp; 首页 · 深色 &nbsp;|&nbsp; 歌词 · 逐字 + 罗马音/翻译 &nbsp;|&nbsp; 歌词 · 流体背景 + 波浪进度</sub>
</p>

界面不使用任何原生 View。除歌词页正文外,所有控件都由 QML 描述并经 qml4j 渲染;歌词正文(逐字滚动 + 流体背景)由宿主通过 Skija 直接手工绘制,不走 QML。qml4j 本身是用纯 Java 实现的 QML 运行时。

## 特性

- 端到端播放:基于网易云音乐 API,覆盖推荐、搜索、用户歌单、最近播放与本地文件。
- 扫码登录,喜欢与取消喜欢,播放队列,三种播放模式(列表循环、随机、单曲循环)。
- 自动换源:灰色、VIP、仅试听的曲目按歌名与歌手匹配替代音源,在播放前完成切换(可关闭)。
- 歌词页:由宿主直接通过 Skija 绘制。逐字滚动(优先 AMLL TTML,网易云作为回退),基于封面取色的流体背景,罗马音与翻译,Material 波浪进度条;支持拖动滚动、释放后惯性滑动与点击行跳转。
- Material 3 界面:整套 UI 为 QML(`md3.Core`),运行在 qml4j 引擎上。
- 莫奈动态取色:主题色从当前封面提取(可关闭);支持深色、浅色与跟随系统。
- 系统媒体控件与后台播放:前台 `MediaSession` 服务接管锁屏、通知栏与蓝牙控制,处理自动续播、进度同步、来电暂停与失焦降音。
- 响应式布局:界面随窗口/屏幕宽度自适应(MD3 断点 600 / 840)——窄屏底部导航,宽屏切换为左侧 `NavigationRail`,歌单栅格列数随宽度增减。这套布局是宽度驱动的,安卓横屏与平板同样生效。
- 桌面端(LWJGL3):同一套 QML 与 `player-core` 逻辑跑在桌面,GLFW 开窗、Skija 渲染。**OpenGL / Vulkan 图形后端可在启动时切换**;任务栏图标 + 系统托盘,托盘菜单镜像播放控制;**最小化到托盘时销毁渲染线程与 GPU 资源,恢复时重建**(播放与界面状态保留)。

## 仓库结构

| 模块 | 说明 |
|---|---|
| `player-core/` | 跨平台核心(Maven,`dev.t1m3.qplayer`):面向 QML 的 `PlayerController`、网易云 API、歌词解析(LRC / YRC / TTML)、音频与元数据抽象,以及宿主绘制的歌词页(流体 SkSL 背景 + 逐字渲染器 + `LyricCompositor` 三层合成)。安卓壳与桌面宿主共用,两端歌词渲染完全一致。 |
| `shared-qml/` | 共享 QML:`Main.qml` + 各页面 + 组件,vendored 的 `md3.Core` 组件库,以及内置字体(PingFang / Material Symbols)。位于仓库根目录,安卓与桌面加载同一份(响应式布局因此两端通用)。 |
| `android-shell/` | 安卓应用(Gradle,`applicationId dev.t1m3.qplayer`,minSdk 26)。宿主集成位于 `…/android/`;UI 与歌词均来自上面两个共享模块。 |
| `desktop-host/` | 桌面宿主(Maven):LWJGL3 + GLFW 开窗、Skija 渲染,可切换的 `GraphicsBackend`(`GLBackend` / `VulkanBackend`)、可销毁/重建的渲染线程、系统托盘,以及桌面音频(javax.sound + SPI 解码)。 |
| [qml4j](https://github.com/TIMER-err/qml4j) | QML 引擎。一个已发布的依赖,**不在**本仓库内。 |

`qml4j-core` 从 Maven Central 解析;本地构建仓库内的 `player-core` / `desktop-host` 模块。

## 构建

需要 JDK 21;构建安卓还需 Android SDK。

**安卓**

```sh
# 将共享模块安装到 Maven Local(安卓壳通过 mavenLocal 消费)
mvn -q -pl player-core -am install

# 构建 APK(qml4j-core 从 Maven Central 解析)
cd android-shell && ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

**桌面**

```sh
# 构建一次(player-core / desktop-host)
mvn -q -pl player-core,desktop-host -am install

# 运行(默认 OpenGL)
mvn -pl desktop-host exec:exec

# 切 Vulkan 后端 / 指定初始窗口大小(试响应式断点)
mvn -pl desktop-host exec:exec -Dgfx=vulkan
mvn -pl desktop-host exec:exec -Dwin.w=480 -Dwin.h=800   # 窄屏(底部导航)
```

> 关闭按钮最小化到托盘(渲染线程销毁、音频续播),从托盘"退出"才真正退出。macOS 启动需加 `-XstartOnFirstThread`。

**桌面单文件可执行(GraalVM native-image)**

需要 **GraalVM 21** JDK。QML 在构建期 AOT 编译,无运行时字节码生成。`native-image` 不能跨系统/架构编译,**每个平台需在对应机器上构建**。

```sh
# 1) 安装共享模块
mvn -DskipTests -pl player-core -am install

# 2) AOT 编译 QML + 构建原生二进制 → desktop-host/target/qplayer[.exe]
mvn -DskipTests -pl desktop-host -Pnative package

# 3) 打成各平台轻量分发包(Skija/LWJGL 原生库一并打入)
bash       desktop-host/dist/package-linux.sh      # Linux   → target/QPlayer-x86_64.AppImage(单文件)
pwsh -File desktop-host/dist/package-windows.ps1   # Windows → target/QPlayer-windows-x64.zip
bash       desktop-host/dist/package-macos.sh      # macOS   → target/QPlayer.dmg(随当前架构)
```

> macOS 的 `.dmg` 未签名,对外分发需自行 codesign + 公证,否则 Gatekeeper 会拦截。打 `v*` tag 时,`.github/workflows/release.yml` 会在三平台 CI 上自动完成上述构建并附到 GitHub Release。

## AI 说明

本项目(QPlayer 及其依赖的 [qml4j](https://github.com/TIMER-err/qml4j) 引擎)的大部分代码由 **Claude(Opus 4.8)** 通过 Claude Code 生成。这是一个效率上的取舍:本项目在业余时间开发,可投入的时间有限,vibe coding 让我在有限的时间里做出更多。所有代码在合入前都经过我本人逐行审查,并在发布前于真机测试;最终结果由我负责。多数提交带有 `Co-Authored-By: Claude`,贡献者列表中也因此出现 "Claude";这是有意保留的,用于标注 AI 在本项目中的参与程度。

## 致谢

- [qml4j](https://github.com/TIMER-err/qml4j) —— 运行整个界面的纯 Java QML 引擎。
- [Skija](https://github.com/HumbleUI/Skija) —— JVM 上的 Skia 绑定;渲染器与宿主绘制的歌词页都通过它输出。
- [material-components-qml](https://github.com/sudoevolve/material-components-qml) —— UI 所用的 Material 3 QML 组件库(`md3.Core`,vendored 后适配引擎)。
- [AMLL TTML DB](https://github.com/Steve-xmh/amll-ttml-db) —— 逐字歌词。
- [NeteaseCloudMusicApiEnhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) —— 网易云请求加密方案(weapi/eapi/xeapi)的算法参照。
- 歌词渲染改编自 Haedus renderer;图标使用 Material Symbols Rounded。

> 个人与学习项目。网易云音乐是其各自所有者的商标;本应用为非官方客户端,与网易云无关联。

## 许可证

[Apache License 2.0](LICENSE.md)。
