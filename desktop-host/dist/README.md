# Desktop release (GraalVM native image)

The desktop app ships as a **GraalVM native-image** binary — no JVM required, fast
startup. The QML is **AOT-compiled at build time** (the engine generates JVM
bytecode at runtime, which native-image's closed world forbids) and the JS
expression engine (Rhino) runs interpreted (`-Dqml4j.rhino.opt=-1`, baked in), so
there is no runtime code generation. Reflection / JNI / resource metadata lives in
`../src/main/resources/META-INF/native-image/`.

native-image **cannot cross-compile**, so each OS is built on its own machine /
CI runner.

## Build (per platform, under a GraalVM 21 JDK)

```sh
# install the shared modules once
mvn -DskipTests -pl player-core -am install
# AOT-compile the QML + build the native binary → desktop-host/target/qplayer[.exe]
mvn -DskipTests -pl desktop-host -Pnative package
```

## Package (lightweight, portable)

```sh
bash    desktop-host/dist/package-linux.sh     # → target/QPlayer-x86_64.AppImage (single file)
bash    desktop-host/dist/package-macos.sh     # → target/QPlayer.dmg  (intel or apple-silicon by arch)
pwsh -File desktop-host/dist/package-windows.ps1 # → target/QPlayer-windows-x64.zip
```

Each packager bundles the native binary + the JDK native libs native-image emits
+ the Skija/LWJGL natives (from the local Maven repo). The Skija/LWJGL libs are
kept in a **separate dir** from the JDK `libjvm`/`libjava` shims — colocating them
makes `System.load(libskija)` bind to the stub shim and fail. A small launcher
points `skija.library.path` / `org.lwjgl.librarypath` at them.

> **macOS**: the `.app`/`.dmg` is **unsigned**. For distribution it must be
> codesigned + notarized (Apple Developer ID) or Gatekeeper blocks it.

## CI release

`.github/workflows/release.yml` is the unified release pipeline: a `v*` tag push
builds the Android APK and the desktop native image on an `ubuntu` / `windows` /
`macos-13` (intel) / `macos-14` (arm64) matrix in parallel, and attaches every
artifact to the same GitHub Release.

## Status

- **Linux AppImage**: built + verified end-to-end (runs standalone, full UI,
  audio, network).
- **Windows / macOS**: scripts written to the same pattern; validate on those
  platforms / via the CI workflow.
