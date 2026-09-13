# Android 后台重建 OOM 调试记录

2026-09-12，PJV110 / Android 15，ADB 真机调试。

## 原因

正式版保存的崩溃日志显示：Java 堆上限为 256 MiB，剩余约 344 KiB 时，
`DiskDecompressedResourceCache.decode` 申请 13,089,528 字节失败。
界面渲染线程捕获错误后显示错误页，播放服务和共享控制器仍然存活，
因此出现“界面无法加载，但音乐继续播放”。

堆快照和原生分配跟踪发现三个叠加问题：

1. 重建后旧场景未完全释放。QML 单例 `Theme.__singleton` 的绑定仍订阅
   全局样式属性；反射缓存还持有 Android 链式 Dex 类加载器里的生成类。
   修复前，连续两次重建后 Activity、SurfaceView、SettingsCore 各从 1 增至 3，
   Property 对象约从 38 万增至 155 万。
2. 字体磁盘缓存读取同时保留完整文件和解码后字体两份大数组，写入也复制整份资源。
3. 每个新场景重新创建原生字体身份。heapprofd 将持续增长定位到界面字体的
   `Data.makeFromBytes`；文字排版缓存会继续持有旧字体数据。
   仅修复 Java 引用后，8 次重建的 Native Heap Alloc 仍从约 113 MiB 增至 228 MiB。

Skia 的常规缓存清理不覆盖 HarfBuzz 的 face 缓存，相关源码：
[SkGraphics](https://github.com/google/skia/blob/main/src/core/SkGraphics.cpp)、
[SkShaper HarfBuzz](https://github.com/google/skia/blob/main/modules/skshaper/src/SkShaper_harfbuzz.cpp)。
这些是上游源码参考；设备归因同时依据实际分配调用栈和对照测试。

## 修复

- 缓存文件流式读写，校验长度后直接读入最终数组，保持原缓存格式。
- 场景销毁时解绑生成单例，并清理所有场景类加载器的反射缓存。
- 清除旧 Activity 的回调、监听器和渲染唤醒引用；错误页替换 SurfaceView 前，
  先在 GL 线程释放失败场景。
- Application 共享解压资源加载器，歌词字体只初始化一次。
- qml4j 新增有容量限制的字体身份缓存（8 项 / 64 MiB 字体数据预算），
  各视图获得独立可关闭的引用，底层复用同一字体身份。

## 自动验证

- QPlayer：158 个测试通过；qml4j：693 个测试通过；Android debug APK 构建成功。
- 新增 40 MiB 子 JVM 读取 24 MiB 字体缓存的回归测试：旧实现 OOM，修复后通过。
- 新增损坏缓存恢复、渲染回调所有权、跨视图字体身份与释放生命周期测试。
- 推送前同步 qml4j 最新主线后，再次完整验证，引擎 697 个测试通过。

## 真机回归

修复后的独立测试包在持续播放本地音乐时连续重建 8 次：

| 指标 | 重建前 | 第 8 次重建后 |
| --- | ---: | ---: |
| Native Heap Alloc | 114.0 MiB | 95.5 MiB |
| Dalvik Heap Alloc | 87.7 MiB | 83.4 MiB |
| Activity / SurfaceView / QmlView / SettingsCore / PlayerController | 各 1 | 各 1 |
| InMemoryDexClassLoader（暖启动） | 1 | 1 |

后 4 轮 Native Heap Alloc 为 95–97 MiB，没有此前每轮约 13 MiB 的增长。
表中使用分配量；RSS/PSS 还包含分配器保留页、共享映射、GPU 等，不等于存活对象大小。

随后 3 次普通后台返回保持同一 Activity；再做 3 次后台重建，全部恢复界面，
PID 保持不变、播放状态为 true。首次后台重建前发送 `RUNNING_CRITICAL` 内存回调。
最终堆快照中的上述 5 类对象和 Dex 类加载器仍各 1 份，截图确认正常显示主界面。
这是重建与内存回调的定向回归，未人为耗尽全机内存，不等同于穷尽所有系统低内存情形。

## 构建与复现

本次同时修改相邻的 `../qml4j` 仓库，并将修改后的 0.2.32 安装至本地 Maven 仓库。
引擎修复提交为 `96e43fa`，尚未发布到 Maven Central，版本号未变。
正式集成时须先发布引擎修复，再同步 QPlayer 的
Maven / Gradle 引擎依赖；仅提交 QPlayer 改动不足以包含原生字体修复。

手机安装的是独立包 `dev.t1m3.qplayer.memtest`，名称 `QPlayer (memory test)`；
原正式版与原 debug 包的数据保留。仓库的 debug 包名配置已恢复。
测试 APK 位于 `android-shell/app/build/outputs/apk/memtest/qplayer-memory-test.apk`。

debug 构建可通过以下命令触发确定性的 Activity 重建；正式构建不执行此命令：

```sh
adb shell am broadcast -a dev.t1m3.qplayer.DEBUG \
  -p dev.t1m3.qplayer.memtest --es cmd recreate
adb shell dumpsys meminfo dev.t1m3.qplayer.memtest
adb shell am dumpheap dev.t1m3.qplayer.memtest /data/local/tmp/qplayer-check.hprof
```

调试日志、对照堆快照和原生跟踪保存在本机 `/tmp/qplayer-android-debug/`，未提交。
