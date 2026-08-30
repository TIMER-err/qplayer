<p align="center">
  <img src="docs/icon.png" width="128" alt="QPlayer icon">
</p>

<h1 align="center">QPlayer</h1>

<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b>
</p>

<p align="center">
  <b>An extensible cross-platform music player with a QML-rendered UI</b><br>
  Powered by <a href="https://github.com/TIMER-err/qml4j">qml4j</a>
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
  <img src="docs/screenshots/platform-showcase.png" width="100%" alt="QPlayer's recommendations, settings, and lyrics UI on phone, tablet, and desktop">
</p>
<p align="center">
  <sub>Phone recommendations · Tablet settings · Desktop lyrics</sub>
</p>
<p align="center">
  <img src="docs/screenshots/platform-showcase-2.png" width="100%" alt="QPlayer's recommendations, settings, and lyrics UI on desktop, tablet, and phone">
</p>
<p align="center">
  <sub>Desktop recommendations · Tablet settings · Phone lyrics</sub>
</p>

The UI uses no native Views. Every control is described in QML and rendered by qml4j — **except** the lyric-page body (per-syllable scrolling + fluid backdrop), which the host draws by hand directly through Skija, not in QML. qml4j is itself a QML runtime written in pure Java.

<a href="https://www.star-history.com/?repos=TIMER-err%2Fqplayer&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=TIMER-err/qplayer&type=date&theme=dark&legend=top-left&sealed_token=pvVKTlOWl7Lak9qFpFBXwrZXczfPyNb2ZD6ZbfiJY2us9fPe7ck5CffvPIOKcTPhT9B6J92c16ce9UrxUIJ-hwpT4WlDEdPJJ5MFvDSvK9CTG1wry56KYPc0OyDhCujlPX35c-dFPj9xU7IqhAEkH6Xz3Q13--zsYmcC_WLSYtiPKr_Et0O9x5sj-mZr" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=TIMER-err/qplayer&type=date&legend=top-left&sealed_token=pvVKTlOWl7Lak9qFpFBXwrZXczfPyNb2ZD6ZbfiJY2us9fPe7ck5CffvPIOKcTPhT9B6J92c16ce9UrxUIJ-hwpT4WlDEdPJJ5MFvDSvK9CTG1wry56KYPc0OyDhCujlPX35c-dFPj9xU7IqhAEkH6Xz3Q13--zsYmcC_WLSYtiPKr_Et0O9x5sj-mZr" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=TIMER-err/qplayer&type=date&legend=top-left&sealed_token=pvVKTlOWl7Lak9qFpFBXwrZXczfPyNb2ZD6ZbfiJY2us9fPe7ck5CffvPIOKcTPhT9B6J92c16ce9UrxUIJ-hwpT4WlDEdPJJ5MFvDSvK9CTG1wry56KYPc0OyDhCujlPX35c-dFPj9xU7IqhAEkH6Xz3Q13--zsYmcC_WLSYtiPKr_Et0O9x5sj-mZr" />
 </picture>
</a>

## Features

- Source plugins: QPlayer itself only provides player capabilities and neither embeds nor distributes online sources. JavaScript plugins may add recommendations, aggregate search, playlists, login, likes, recent playback, recommendation modes, and synchronized listening; the native pages adapt to each plugin's capabilities.
- Local playback, a shared queue, and three play modes (list loop, shuffle, repeat one). Online entities use collision-free `provider:kind:id` identifiers.
- Plugin security: signed `.qplug` packages, built-in sources pinned to a publisher key, permission confirmation, isolated Rhino realms, network-domain grants, namespaced credential storage, and separately sandboxed custom-QML sessions.
- Lyric page: drawn directly through Skija by the host, with per-syllable scrolling, cover-tinted fluid backdrops, romaji and translation, and a Material wavy progress bar. Lyrics come from the active source plugin or local files.
- Material 3 UI: the whole interface is QML (`md3.Core`) running on the qml4j engine.
- Dynamic color (Monet): the theme is reseeded from the current cover (toggleable); dark, light, and follow-system modes.
- System media controls and background playback: a foreground `MediaSession` service drives the lockscreen, notification, and bluetooth transport, with auto-advance, position sync, pause-on-call, and ducking on transient focus loss.
- Responsive layout: the UI adapts to the window/screen width (MD3 breakpoints 600 / 840) — a bottom bar when narrow, a left `NavigationRail` when wide, and a playlist grid whose column count grows with width. It's width-driven, so Android landscape and tablets get it too.
- Desktop (LWJGL3): the same QML and `player-core` logic run on the desktop, windowed with GLFW and rendered with Skija. The **OpenGL / Vulkan graphics backend is switchable** at startup; a taskbar icon plus a system tray whose menu mirrors the transport; **minimizing to the tray destroys the render thread and GPU resources and rebuilds them on restore** (playback and UI state are preserved).

## Credential storage and security boundary

QPlayer encrypts each plugin's login credentials with authenticated AES-GCM in a plugin-specific namespace and, whenever available, protects the random data key with Android Keystore, macOS Keychain, Windows DPAPI, or Linux Secret Service/KWallet. If the system credential store is unavailable, the user may explicitly fall back to a local key restricted to the current user.

This feature provides **data-at-rest protection**, not protection against malware already running locally. It reduces the risk of restoring a login from copied credential files, configuration directories, backups, or old drives, and prevents other unprivileged operating-system accounts from directly reading the credentials.

> [!IMPORTANT]
> Windows DPAPI and Linux Secret Service/KWallet primarily use the current user or login session as their security boundary; they do not guarantee exclusive access by QPlayer. Another process running as the same user may be able to call the same system interfaces, especially while the credential store is unlocked. On desktop, owner-only fallback encryption mainly relies on file permissions and likewise cannot defend against same-user processes. Android's application sandbox and macOS Keychain application access controls provide stronger app-level isolation, but root/administrator access, process injection, debugging, and reading QPlayer's live process memory remain outside the protection boundary.

## Layout

| Module | Description |
|---|---|
| `player-core/` | Platform-neutral core (Maven, `dev.t1m3.qplayer`): the QML-facing `PlayerController`, JavaScript plugin ABI and sandbox, lyric parsers (LRC / YRC / TTML), audio/metadata abstractions, and the host-drawn lyric page. It contains no online-source endpoints or protocol implementation. |
| `shared-qml/` | Shared QML: `Main.qml` + the pages + components, the vendored `md3.Core` library, and bundled fonts (PingFang / Material Symbols). At the repo root; Android and desktop load the same copy (so the responsive layout applies to both). |
| `android-shell/` | Android app (Gradle, `applicationId dev.t1m3.qplayer`, minSdk 26). Host integration in `…/android/`; the UI and lyrics come from the two shared modules above. |
| `desktop-host/` | Desktop host (Maven): an LWJGL3 + GLFW window rendered with Skija, a switchable `GraphicsBackend` (`GLBackend` / `VulkanBackend`), a disposable render thread, a system tray, and desktop audio (javax.sound + SPI decoders). |
| [qml4j](https://github.com/TIMER-err/qml4j) | The QML engine. A published dependency, **not** part of this repo. |

`qml4j-core` is resolved from Maven Central; the in-repo `player-core` / `desktop-host` modules are built locally.

See the [plugin authoring guide](docs/plugins.md) and [plugin security model](docs/plugin-security.md) for the ABI, permissions, package signing, media IDs, and migration rules.

## Build

Requires JDK 21; building for Android also needs the Android SDK.

**Android**

```sh
# install the shared modules to Maven Local (the Android shell consumes them via mavenLocal)
mvn -q -pl player-core -am install

# build the APK (qml4j-core resolves from Maven Central)
cd android-shell && ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

**Desktop**

```sh
# build once (player-core / desktop-host)
mvn -q -pl player-core,desktop-host -am install

# run (OpenGL by default)
mvn -pl desktop-host exec:exec

# switch to the Vulkan backend / set the initial window size (try the breakpoints)
mvn -pl desktop-host exec:exec -Dgfx=vulkan
mvn -pl desktop-host exec:exec -Dwin.w=480 -Dwin.h=800   # narrow (bottom bar)
```

> The close button minimizes to the tray (the render thread is destroyed, audio keeps playing); only "Quit" from the tray exits. On macOS launch with `-XstartOnFirstThread`.

**Self-contained desktop bundle (jpackage + jlink)**

Needs a full **JDK 21** (not a JRE — `jpackage`/`jlink` must be present). The bundle ships a jlinked runtime, so users don't install Java. `jpackage` only targets the OS it runs on, so **each platform is built on its own machine**.

```sh
# 1) install the shared module
mvn -DskipTests -pl player-core -am install

# 2) stage target/app (qplayer.jar + every runtime dependency under lib/)
mvn -DskipTests -pl desktop-host -Pdist package

# 3) package into a per-platform bundle (jpackage jlinks the runtime as it goes)
bash       desktop-host/dist/package-linux.sh      # Linux   → target/QPlayer-x86_64.AppImage (single file)
pwsh -File desktop-host/dist/package-windows.ps1   # Windows → target/QPlayer-windows-x64.zip
bash       desktop-host/dist/package-macos.sh      # macOS   → target/QPlayer.dmg (host arch)
```

> The JDK modules linked into the runtime are listed in `desktop-host/dist/jre-modules.txt`, shared by all three scripts. The macOS `.dmg` is unsigned; distributing it needs codesign + notarization or Gatekeeper blocks it. On a `v*` tag, `.github/workflows/release.yml` runs all of the above on the three-platform CI and attaches the artifacts to the GitHub Release.

## Releasing

The version lives in **two** places — bump both (keep them in sync):

- `android-shell/app/build.gradle.kts` — `versionCode` (integer, +1 each time) and `versionName` (e.g. `0.8.4`)
- `desktop-host/pom.xml` — `<qplayer.app.version>` (desktop package version)

Commit, then tag and push `v<versionName>` (e.g. `v0.8.4`) to trigger `release.yml`: the signed Android APK and the three desktop packages build and attach to the GitHub Release. CI reads the `qml4j-core` version from `build.gradle.kts` and builds that engine from its matching `v*` tag.

## Credits

- [qml4j](https://github.com/TIMER-err/qml4j) — the pure-Java QML engine that runs the UI.
- [Skija](https://github.com/HumbleUI/Skija) — Skia bindings for the JVM; the renderer and the host-drawn lyric page draw through it.
- [material-components-qml](https://github.com/sudoevolve/material-components-qml) — the Material 3 QML component library (`md3.Core`) the UI is built from (vendored, engine-adapted).
- [SPlayer](https://github.com/imsyy/SPlayer) — visual and implementation reference for the fluid lyrics backdrop.
- [AMLL](https://github.com/amll-dev/amll-player) — design reference for Apple Music-style lyrics and fluid backdrops.
- [swingwebview](https://github.com/webliteca/swingwebview) — uses the system WebView for website login on desktop.
- Icons are Material Symbols Rounded.

> QPlayer provides no online source or copyrighted media. Plugin authors and users are responsible for service terms and local law.

## License

[Apache License 2.0](LICENSE.md).
