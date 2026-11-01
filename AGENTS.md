# QPlayer

Reply to the user in Chinese.

QPlayer is a cross-platform Java/QML music player. `player-core` contains shared
Java logic, `shared-qml` contains shared UI, `desktop-host` is the LWJGL/Maven host,
and `android-shell` is the Gradle host. The engine is [qml4j](https://github.com/TIMER-err/qml4j), not Qt.

## Repository

- `upstream` (`TIMER-err/qplayer`) is authoritative; `origin` is a personal fork.
- Work directly on the only local branch, `master`, tracking `upstream/master`.
  Do not create additional local branches.
- Fetch `upstream` before pushing. The default push is `git push upstream master`.
  A force push requires fresh explicit approval for that operation.
- Never print or persist remote URLs containing credentials; redact them first.

## Build and dependencies

- Android and JVM development require JDK 21 and Maven. Native desktop builds
  require the GraalVM Community version pinned in `.github/workflows/release.yml`,
  CMake, and a native C/C++ toolchain. Use `JAVA_HOME`, `PATH`, and the configured
  Android SDK; do not commit machine-specific installation paths.
- After changing `player-core`, install it into the local Maven repository;
  `package` alone leaves stale JARs for other consumers.
- Set the engine version only through `qml4j.version` in the root POM; Android
  reads it too. Consume published JitPack artifacts, not locally built JARs
  installed under JitPack coordinates.
- Production QML is compiled by `qml-aot-tool` at build time for both hosts.
  Rebuild after QML changes; do not rely on runtime compilation or stale scenes.

Run from the repository root:

```sh
# Required after player-core changes
mvn -pl player-core install
# Build and run the desktop host
mvn -pl desktop-host -am install
mvn -pl desktop-host exec:exec
# Build Android with a bounded Gradle heap
(cd android-shell && ./gradlew assembleDebug -Dorg.gradle.jvmargs=-Xmx768m --no-daemon)
```

## QML conventions

- Derived bindings update at `DirtyQueue.flush()`, not on read. In signal
  handlers, read source properties directly or explicitly update dependent state.
- Keep layouts shallow and position `Repeater` children explicitly.
- For non-square local cover previews, use `PreserveAspectFit` with `clip: true`.

## Native desktop and verification

Build with the pinned GraalVM selected in `JAVA_HOME`:

```sh
mvn -pl desktop-host -am -Pdist package -DskipTests
desktop-host/target/qplayer --build-smoke
```

The distribution is a native executable, not a bundled JVM. Keep system file
pickers and the Swing-free JNI WebView. Update the reflection/JNI/resource
metadata generators for new dynamic entry points; do not edit generated files
under `target` or disable strict metadata checks to bypass failures.

Follow the behavior gates and platform packaging instructions in
`desktop-host/dist/README.md`. Exercise changed behavior in the actual application;
compilation and tests alone are not runtime proof. State hardware verification limits.

## QPlayer releases

- Increment Android `versionCode` and update `versionName` in
  `android-shell/app/build.gradle.kts`. Match `qplayer.app.version` in
  `desktop-host/pom.xml` and the `v<versionName>` tag.
- `.github/workflows/release.yml` builds the platform artifacts and publishes
  them through a single `publish` job. Treat that workflow as the release source of truth.
- Write Chinese release notes with `### 新功能`, `### 优化`, and `### 修复`, followed
  by a `Full Changelog` compare link. Update release text through the GitHub API.
