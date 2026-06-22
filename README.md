<p align="center">
  <img src="docs/icon.png" width="128" alt="QPlayer icon">
</p>

<h1 align="center">QPlayer</h1>

<p align="center">
  <b>简体中文</b> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <b>一个全程用 QML 渲染的安卓网易云音乐播放器。</b><br>
  运行在 <a href="https://github.com/TIMER-err/qml4j">qml4j</a> 上——一个用纯 Java 实现的 QML 引擎,不依赖 Qt 和 C++。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%2026%2B-A4C639" alt="Android 26+">
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

界面不使用任何原生 View。所有控件,包括歌词,都由 QML 描述并由 qml4j 渲染;qml4j 本身是用纯 Java 实现的 QML 运行时。

## 特性

- 端到端播放:基于网易云音乐 API,覆盖推荐、搜索、用户歌单、最近播放与本地文件。
- 扫码登录,喜欢与取消喜欢,播放队列,三种播放模式(列表循环、随机、单曲循环)。
- 自动换源:灰色、VIP、仅试听的曲目按歌名与歌手匹配替代音源,在播放前完成切换(可关闭)。
- 歌词页:由宿主直接通过 Skija 绘制。逐字滚动(优先 AMLL TTML,网易云作为回退),基于封面取色的流体背景,罗马音与翻译,Material 波浪进度条;支持拖动滚动、释放后惯性滑动与点击行跳转。
- Material 3 界面:整套 UI 为 QML(`md3.Core`),运行在 qml4j 引擎上。
- 莫奈动态取色:主题色从当前封面提取(可关闭);支持深色、浅色与跟随系统。
- 系统媒体控件与后台播放:前台 `MediaSession` 服务接管锁屏、通知栏与蓝牙控制,处理自动续播、进度同步、来电暂停与失焦降音。

## 仓库结构

| 模块 | 说明 |
|---|---|
| `player-core/` | 跨平台核心(Maven,`dev.t1m3.qplayer`):面向 QML 的 `PlayerController`、网易云 API、歌词解析(LRC / YRC / TTML)、音频与元数据抽象。 |
| `android-shell/` | 安卓应用(Gradle,`applicationId dev.t1m3.qplayer`,minSdk 26)。QML 界面位于 `app/src/main/assets/*.qml`;宿主集成与 Skija 歌词页位于 `…/android/`。 |
| `shared-qml/` | vendored 的 `md3.Core` 组件库与内置字体(PingFang / Material Symbols),位于仓库根目录,供安卓壳与未来的桌面端共用。 |
| [qml4j](https://github.com/TIMER-err/qml4j) | QML 引擎。一个已发布的依赖,**不在**本仓库内。 |

`qml4j-core` 从 Maven Central 解析;本地只构建仓库内的 `player-core` 模块。

## 构建

需要 JDK 21 与 Android SDK。

```sh
# 将 player core 安装到 Maven Local
cd player-core && mvn -q -DskipTests install

# 构建 APK(qml4j-core 从 Maven Central 解析)
cd ../android-shell && ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

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
