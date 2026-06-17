<p align="center">
  <img src="docs/icon.png" width="128" alt="QPlayer icon">
</p>

<h1 align="center">QPlayer</h1>

<p align="center">
  <b>A NetEase Cloud Music player for Android, rendered entirely with QML.</b><br>
  Built on <a href="https://github.com/TIMER-err/qml4j">qml4j</a> — a pure-Java QML engine (no Qt, no C++).
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%2026%2B-A4C639" alt="Android 26+">
  <img src="https://img.shields.io/badge/UI-QML%20%2F%20Material%203-7C6CF0" alt="QML / Material 3">
  <img src="https://img.shields.io/badge/engine-qml4j-465BA6" alt="qml4j">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0"></a>
</p>

---

<p align="center">
  <img src="docs/screenshots/1.jpg" width="24%" alt="Home (light, Monet)">
  <img src="docs/screenshots/2.jpg" width="24%" alt="Home (dark)">
  <img src="docs/screenshots/3.jpg" width="24%" alt="Lyrics (romaji + translation)">
  <img src="docs/screenshots/4.jpg" width="24%" alt="Lyrics (wavy progress)">
</p>
<p align="center">
  <sub>Home · light (Monet) &nbsp;|&nbsp; Home · dark &nbsp;|&nbsp; Lyrics · per-syllable + romaji/translation &nbsp;|&nbsp; Lyrics · fluid backdrop + wavy progress</sub>
</p>

## Features

- **End-to-end playback** over the NetEase Cloud Music API — recommendations, search, your playlists, recent, and local files.
- **QR login** to NetEase; like/unlike, play queue, play modes (list-loop / shuffle / repeat-one).
- **Source switching** — grey/VIP/trial tracks fall back automatically to alternate audio sources (GD音乐台 / 波点 / 酷我), matched by title + artist before playing (toggleable).
- **Material 3 UI** — the whole interface is QML (`md3.Core`), running on the qml4j engine.
- **Dynamic color (Monet)** — the theme reseeds from the current cover art (toggleable); full **dark / light / follow-system** modes.
- **System media controls & background playback** — a foreground `MediaSession` service drives the lockscreen / notification / bluetooth transport, with auto-advance, position sync and audio-focus handling (pause on calls, duck on transient loss) while the app is backgrounded.
- **Lyric page** — host-drawn with Skija: per-syllable scrolling (AMLL TTML mirror, NetEase fallback), an Apple-Music-style SkSL fluid backdrop tinted from the cover, romaji + translation with wrapping, alignment-aware interlude dots, animated background vocals, a Material wavy progress bar and an icon-button transport.
- **Bundled PingFang font** drives the whole UI and lyric page (Latin + CJK); the lyric page's **font size, weight and line spacing** are configurable in settings.
- **In-app back navigation** — back/gesture pops the open overlay (lyrics → queue → settings → playlist → tab) instead of exiting.
- **Edge-to-edge** fullscreen, themed system bars, and a startup splash while the QML tree compiles (dexing is cached across launches, invalidated on reinstall).

## Project layout

| Module | What |
|---|---|
| `player-core/` | Platform-neutral core (Maven, `dev.t1m3.qplayer`): the QML-facing `PlayerController`, NetEase API, lyric parsers (LRC / YRC / TTML), audio + metadata abstractions. |
| `android-shell/` | Android app (Gradle, `applicationId dev.t1m3.qplayer`, minSdk 26). QML UI in `app/src/main/assets/*.qml`; host integration + the Skija lyric page in `…/android/`. |
| `shared-qml/` | Vendored `md3.Core` component library + bundled fonts (PingFang / Material Symbols), at the repo root so the Android shell and a future desktop host can share it. |
| [qml4j](https://github.com/TIMER-err/qml4j) | The QML engine. A published dependency, **not** part of this repo. |

`qml4j-core` is pulled from Maven Central; only the in-repo `player-core` module is built locally.

## Build

Requires JDK 21 and the Android SDK.

```sh
# player core → Maven Local
cd player-core && mvn -q -DskipTests install

# the APK (qml4j-core resolves from Maven Central)
cd ../android-shell && ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Credits

- [qml4j](https://github.com/TIMER-err/qml4j) — the pure-Java QML engine that runs the UI.
- [Skija](https://github.com/HumbleUI/Skija) — Skia bindings for the JVM; the renderer and the host-drawn lyric page draw through it.
- [material-components-qml](https://github.com/sudoevolve/material-components-qml) — the Material 3 QML component library (`md3.Core`) the UI is built from (vendored, engine-adapted).
- [AMLL TTML DB](https://github.com/Steve-xmh/amll-ttml-db) — syllable-level lyrics.
- Lyric rendering adapted from the Haedus renderer; icons are Material Symbols Rounded.

> Personal/educational project. NetEase Cloud Music is a trademark of its respective owner; this app is an unofficial client and is not affiliated with NetEase.

## License

[Apache License 2.0](LICENSE.md).
