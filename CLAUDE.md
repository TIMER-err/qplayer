# QPlayer

跨平台 Java+QML 音乐播放器。`player-core`(共享 Java 逻辑，Android/Desktop 共用)+ `shared-qml`(共享 UI)+ `desktop-host`(LWJGL/qml4j 桌面端，Maven)+ `android-shell`(Gradle)。QML 引擎是 [qml4j](https://github.com/TIMER-err/qml4j)，一个自研的非 Qt 引擎，有一些不同于标准 QML 的限制（见下）。

## 环境约束

- 需要 **JDK 21** 与 **Maven**；安装位置随开发机而定，不要写死盘符或绝对路径，一切以各机实际的 `JAVA_HOME`、`mvn`（在 `PATH` 上）和本地仓库为准。
- 用中文回复。
- 编辑 `player-core` 后必须 `mvn -pl player-core install`（不能只 `package`），否则 `desktop-host` 会继续用本地仓库里的旧 jar。

## 仓库与分支

- `origin` = `Ink-Stained-Clouds/qplayer`（自己的 fork），`upstream` = `TIMER-err/qplayer`（协作者仓库，remote URL 里嵌了 PAT，绝不能回显到输出或写进文件）。
- **`upstream/master` 是唯一权威分支**（2026-07-13 起）。`origin` 这个 fork 和 `upstream` 的 `ink`/`ink_clouds` 分支都是临时/过时的，已被强推覆盖为 `upstream/master` 的内容，不再维护。
- 本地仓库**只保留 `master` 一个分支**，不要新建 `ink-fresh` 等其他本地分支。所有开发直接在 `master` 上进行。
- 正常情况下 `git push origin master` / `git push upstream master:master` 应该都是快进（fast-forward），不需要强推。
- **禁止 `git push --force`**，包括任何分支/远端。哪怕之前批准过一次强推，下一次也要重新问，不能当作标准许可。

## 发版流程

- 版本号有**两处**，发版前必须都改，保持一致：
  - `android-shell/app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`
  - `desktop-host/pom.xml` 的 `<qplayer.app.version>`
  - 漏改会导致 APK 内置版本号跟 tag 对不上，App 自带的更新检查（比较 `PackageInfo.versionName` 和 GitHub 最新 release tag）会无限提示更新。
- 打 `v<versionName>` tag 触发 `.github/workflows/release.yml`：android + linux/macos/windows 桌面共 4 个 job 并行构建，都会把产物传到同一个 GitHub Release。**这 4 个并发上传偶尔会互相顶掉、丢文件**（比如 android job 构建成功但 apk 没挂上 release）；遇到这种情况用 GitHub API 单独 rerun 那一个 job（`POST /repos/{owner}/{repo}/actions/jobs/{job_id}/rerun`），比重新走一遍全部 native-image 编译快得多。
- Release notes 手写中文，格式：`### 新功能` / `### 优化` / `### 修复` 三段式，结尾 `Full Changelog: vX...vY` 对比链接。改 release body 用 GitHub API PATCH 即可（不会重新触发 CI）；改 tag message 必须删掉 tag 重新打（会重新触发一次完整构建）。
- 这台开发机在国内网络下直连 `api.github.com` 下载大文件很慢（~25KB/s），能用 GitHub 自己的基础设施做的事（如上面的单独 rerun）优先别走本地下载。

## qml4j 引擎已知限制（不是实现错误，是引擎本身的坑）

- 没有 `KEY_DELETE`：桌面端要在 `InputBridge.java` 里手动转发 Delete 键。
- `CoverImage` 的 `PreserveAspectCrop` 对非正方形本地图片有 bug（拉伸/溢出）；非正方形预览用 `PreserveAspectFit` + `clip: true`。
- `TextField`/`TextInput` 没有光标跟随自动滚动；长文本输入框要镜像一个自动换行的 `Text` 在下面显示完整内容。
- 深层嵌套 `Layout` 的 `fillWidth` 传播不可靠，会导致 `Text.WordWrap` 失效——QML 结构尽量拍平，别多套一层 `ColumnLayout`。
- `Repeater` 不会像 Qt positioner 那样自动排列动态创建的子项，要用显式 `x`/`y` 定位。
- git 合并时，两个分支**各自独立新增、内容相同但位置不重叠**的代码不会被识别为冲突（不会报冲突标记），会悄悄重复，只能靠编译错误事后发现——合并有历史分叉的分支后务必实际编译三个模块。

## 构建 & 运行

```powershell
# player-core 改动后先装到本地仓库
mvn -pl player-core install -DskipTests -q

# 桌面端：编译
mvn -pl desktop-host package -DskipTests -q
# 桌面端：运行
mvn -pl desktop-host exec:exec -q

# Android：编译（这台机器内存紧张，需要限制 Gradle 堆）
cd android-shell
./gradlew compileDebugJavaWithJavac "-Dorg.gradle.jvmargs=-Xmx768m" --no-daemon
```
