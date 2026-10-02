# Desktop release (jpackage + jlink)

The desktop app ships as a **jpackage bundle**: the app jars plus a jlinked JRE
trimmed to the modules in [`jre-modules.txt`](jre-modules.txt). No JVM install
required on the user's machine, and — unlike the GraalVM native image this
replaced — it's an ordinary JVM at runtime, so the QML engine generates its
classes normally, Rhino JITs, AWT/Swing work, and Skija/LWJGL extract their own
natives out of the jars exactly like a dev run does. There is no reachability
metadata to maintain.

`jpackage` only targets the OS it runs on, so each platform is built on its own
machine / CI runner.

## Build (per platform, under a full JDK 21 — not a JRE)

```sh
# install the shared modules once
mvn -DskipTests -pl player-core -am install
# stage target/app: qplayer.jar (Class-Path -> lib/) + every runtime dep in lib/
mvn -DskipTests -pl desktop-host -Pdist package
```

## Package

```sh
bash       desktop-host/dist/package-linux.sh     # → target/QPlayer-x86_64.AppImage (single file)
bash       desktop-host/dist/package-macos.sh     # → target/QPlayer.dmg  (intel or apple-silicon by arch)
pwsh -File desktop-host/dist/package-windows.ps1  # → target/QPlayer/ + QPlayer-windows-x64.zip
```

Each script runs `jpackage` over `target/app`, then wraps the result in the
platform's usual container. `QPLAYER_VERSION` (or the latest git tag) sets the
version baked into the bundle metadata. They also read the shared
[`jvm-options.txt`](jvm-options.txt), which bounds heap/address-space growth and
keeps G1/JIT worker counts independent of the host's CPU count.

The four large PingFang weights in `shared-qml/fonts` are stored as XZ assets
(8 MiB dictionary) and decompressed only when a host asks for that weight. This
keeps the packaged JAR small without retaining the compressed or raw font bytes
after Skija has created its cached typeface. Always compare package size from a
clean build: Maven's resource-copy step does not remove stale resources from an
existing `target/classes` directory.

### Platform notes

- **macOS** needs `-XstartOnFirstThread` (GLFW must own thread 0; the java
  launcher otherwise runs `main` on a thread it spawns). The script passes it via
  `--java-options`. It also bumps a `0.x` version to `1.x` for `CFBundleVersion`
  only — Apple rejects a zero major, and the version the app *reports* comes from
  `version.properties`, not the bundle. The `.app`/`.dmg` is **unsigned**; for
  distribution it must be codesigned + notarized or Gatekeeper blocks it.
- **Windows** gets a GUI-subsystem launcher from jpackage, so double-clicking
  shows no console; `WinConsole` re-attaches to a parent terminal's console when
  started from a shell. CI additionally builds an Inno Setup installer
  (`qplayer.iss`) over the packaged `QPlayer/` folder.
- **Linux** additionally strips the symbol table that Temurin leaves in
  `libjvm.so` after `jlink --strip-debug`; this does not affect exported JNI/VM
  symbols and saves roughly 4 MiB in the uncompressed runtime.
- **Linux** embeds [`dev.t1m3.qplayer.appdata.xml`](dev.t1m3.qplayer.appdata.xml)
  under `usr/share/metainfo`, together with the desktop entry and icon. AppImage
  catalogs read its localized description and the two showcase images in
  `docs/screenshots`; catalog pages update after they ingest a newly published
  AppImage.
- **Building with a GraalVM JDK** works, but its `jvmcicompiler.dll`/`.so` adds
  ~48 MB to the runtime. CI uses Temurin.
- The jpackage launcher passes the command line to `main()` rather than the JVM,
  so `Main` re-reads `-Dkey=value` args itself. The packaged app therefore takes
  the same knobs as a dev run: `qplayer.exe -Dqplayer.gfx=vulkan -Dqplayer.width=900`.

## Runtime modules

`jre-modules.txt` is the common source of truth read by all three scripts;
Windows adds `jdk.httpserver` for its SMTC cover endpoint. Raw `jdeps` output
also reports optional Gson SQL adapters and the Windows-only class on every OS,
so review its output rather than copying it wholesale. Inspect it with:

```sh
jdeps --multi-release 21 --ignore-missing-deps --print-module-deps \
      desktop-host/target/app/qplayer.jar desktop-host/target/app/lib/*.jar
```

## CI release

`.github/workflows/release.yml` is the unified release pipeline: a `v*` tag push
builds the Android APK and the desktop bundle on an `ubuntu` / `windows` /
`macos-14` (Apple Silicon) matrix in parallel, and attaches every artifact to the
same GitHub Release.

> **Intel Mac**: GitHub retired the free Intel `macos-13` runner, so CI ships an
> Apple-Silicon `.dmg` only. Intel users build locally with `package-macos.sh`
> (it picks up the natives for the host arch through the Maven os profiles).
