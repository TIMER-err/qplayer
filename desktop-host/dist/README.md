# Desktop release (GraalVM Native Image)

Desktop distributions contain a native executable and licenses, not application
JARs or a bundled JVM. Build each operating system and architecture on its target
machine or CI runner. JVM development runs remain available.

`qml-aot-tool` compiles bundled QML into ordinary classes before packaging. Both
hosts load those classes through `BundledQml`; neither host compiles QML at runtime.
Rhino executes scripts in interpreter mode. The Android build converts the same
class output to DEX through its regular build pipeline.

## Build prerequisites

Use GraalVM Community **25 Innovation 4 / 25.4.4.1.1** (JDK 25.0.4.1.1) with
`native-image`, Maven, CMake, and a C/C++ toolchain. This matches the locally verified
compiler and the version pinned in CI; LTS 25.0.2 rejects the logical callback
reflection metadata used by this build.
Set `JAVA_HOME` to that installation and put its `bin` directory on `PATH`.

- **Linux:** Install `pkg-config`, zlib, GTK 3, WebKitGTK 4.1, and X11 development
  packages. On Debian/Ubuntu, use `build-essential zlib1g-dev libgtk-3-dev
  libwebkit2gtk-4.1-dev libx11-dev`. Packaging also requires `curl` and `file`.
- **Windows:** Use an x64 MSVC developer shell with the Windows SDK. Install
  `Microsoft.Web.WebView2` version `1.0.2592.51` with NuGet. Set `WEBVIEW2_SDK` to
  that package directory, which contains `build/native/include` and
  `build/native/x64/WebView2LoaderStatic.lib`.
  Desktop login requires the Evergreen WebView2 Runtime on the user's machine.
- **macOS:** Install Xcode Command Line Tools. The build targets macOS 13 or newer.

The engine version has one source: `qml4j.version` in the root POM. Android reads
that property too. All CI builds use `.github/actions/build-engine` to clone the
matching tag and pin its POM versions before installation. This pin is required:
an engine tag can still contain the previous release's Maven version.

```sh
# Install player-core for consumers outside this Maven reactor, including Android.
mvn -DskipTests -pl player-core -am install

# Build the QML compiler, native WebView backend, metadata, and native executable.
mvn -DskipTests -pl desktop-host -am -Pdist package
# -> desktop-host/target/qplayer (qplayer.exe on Windows)
```

Before packaging, run the project behavior gate:

```sh
mvn -B -pl player-core,qml-aot-tool -am install
mvn -B -pl desktop-host verify
```

The first command runs core tests and installs the build tool for Maven plugin
resolution. The second runs desktop tests and the `smoke-packaged-qml` integration
step. Existing source-interpreted QML tests remain useful component tests. The
integration step loads both real scene manifests through `BundledQml`, without
QML source access. It checks visible theme changes, theme ownership after scene
reopening, a reactive lyric lock control, and native image decoding. Scene layout
also uses the production font-loading path.

After building the native executable, run the same smoke under strict native
metadata checking:

```sh
desktop-host/target/qplayer --build-smoke
# Windows: desktop-host/target/qplayer.exe --build-smoke
```

The smoke uses a temporary empty profile and does not create a window or start
audio playback. It is not a substitute for platform WebView, tray, file-dialog,
or audio integration tests. Release CI runs it on each native target before
packaging. Debug and release jobs also depend on the shared project behavior
workflow; Android jobs run `:app:testDebugUnitTest` before assembly.

For a nonstandard SDK layout, pass its native library directories explicitly:

```sh
mvn -DskipTests -pl desktop-host -am -Pdist package \
  "-Dqplayer.native.library.path=$(pkg-config --variable=libdir zlib)"
```

Multiple library directories use a comma separator. On NixOS, also expose the
native development packages through `PKG_CONFIG_PATH`. Runtime library and TLS
module requirements are listed in the root README.

## Package

```sh
bash       desktop-host/dist/package-linux.sh     # -> target/QPlayer-x86_64.AppImage
bash       desktop-host/dist/package-macos.sh     # -> target/QPlayer.dmg
pwsh -File desktop-host/dist/package-windows.ps1  # -> target/QPlayer/ and QPlayer-windows-x64.zip
```

The scripts package the native executable, emitted JNI libraries, and
`target/native-legal`. They do not create a JRE. Native Image can emit small JNI
shim libraries named `libjava` or `libjvm`; these are not a HotSpot runtime.
`QPLAYER_VERSION`, then `GITHUB_REF_NAME`, then the latest Git tag sets container
metadata. The application version comes from `version.properties`.

- **Linux:** The AppImage includes the desktop entry, icon, and
  `usr/share/metainfo/dev.t1m3.qplayer.appdata.xml`. System graphics, audio,
  WebKitGTK, and GLib libraries remain external dependencies.
- **Windows:** Native Image links a GUI-subsystem executable with the application
  icon and version resource. `WinConsole` attaches to a parent console for shell
  launches. CI builds the Inno Setup installer over the packaged `QPlayer/` folder.
- **macOS:** Native Image starts on the initial OS thread; the native executable
  does not use the Java launcher's `-XstartOnFirstThread` option. The script creates
  an ad-hoc-signed application. Public distribution still requires Developer ID
  signing and notarization.

The four large PingFang weights remain XZ assets. The resource loader decompresses
a weight only when requested, then Skija caches its typeface. Use a clean build
when comparing artifact sizes; Maven resource copying can leave old files in
`target/classes`.

## Native integration and metadata

File dialogs use Windows COM, the Linux desktop portal, and macOS AppKit. There
is no Swing chooser or AWT tray fallback.

WebView retains swingwebview's original native engines: WebView2 on Windows,
WKWebView on macOS, and WebKitGTK on Linux. A standalone JNI bridge replaces the
Swing host, not the browser engine. Vendored sources and license attribution live
in `desktop-host/native/webview`.

Metadata is generated at build time:

- `QmlBundleCompiler` registers generated components, QML context types, stock
  types, and untyped input/model payloads.
- `DesktopNativeMetadata` discovers Gson `TypeToken` roots and registers JSON models
  and container constructors, JNA libraries and callbacks, Skija JNI entry points,
  reflective audio-tag factories, Rhino reflection, and required resources.
- The WebView CMake install step registers its JNI bridge and native library.
- OkHttp's JVM artifact supplies its own native-image arguments, Kotlin reflection
  registrations, and public-suffix resource configuration under
  `META-INF/native-image/okhttp/okhttp`. Do not duplicate those registrations.

`okhttp.version` in the root POM pins the plugin HTTP transport for both hosts.
Maven uses `okhttp-jvm`; Android excludes that transitive artifact and lets Gradle
select the `okhttp-android` AAR and its AndroidX Startup initializer from `okhttp`.
The Android build-time QML compiler retains the JVM artifact. The desktop build
uses the JDK TLS provider; adding Conscrypt, Bouncy Castle, or OpenJSSE requires
testing that provider's native integration, not just adding its detection class.

Change the generators when adding a dynamic entry point. Do not edit generated
JSON or disable strict metadata checks. Exercise the affected interaction in the
native executable; a successful image build does not prove dynamic reachability.

The qml4j 0.2.33 public API does not yet replace the build-only private ABI
adapter. `TypeRegistry` has no type/singleton enumeration. `Loader` and
`CompilationRecorder` are package-private. Public `QmlCompiler.compile` returns
an individual unit, not a complete import/singleton/JS scene, and exposes no
namespace allocation. Removing `QPlayerSceneCapture` requires an upstream public
scene-capture API that accepts resources, context names, all lazy component
sources, and an isolated generated-class namespace without instantiating UI
objects. It must return a complete `CompiledScene`. It also requires public
registry enumeration for reflection-root discovery. Do not replace this adapter
with source compilation at runtime or a partial fallback.

For plugin metadata changes, exercise download, publisher-signature verification,
consent, and first installation with an empty profile. An existing plugin does not
exercise package hash-map deserialization.
The image includes all locales and charsets, with a default maximum heap of
768 MiB. These options live in `desktop-host/pom.xml`.

Native Image accepts startup properties directly:

```sh
desktop-host/target/qplayer -Dqplayer.width=430 -Dqplayer.height=900
```

## CI release

A `v*` tag triggers `.github/workflows/release.yml`. The Android job uses JDK 21.
The Linux, Windows, and macOS jobs use the pinned GraalVM Innovation 4 compiler
and native SDK prerequisites.
All jobs attach their artifacts to the same GitHub Release.
Tags containing a prerelease suffix (for example `v1.9.3-rc.1`) publish a GitHub
prerelease without replacing the latest stable release. Android and the application
version retain the suffix; macOS bundle and Windows binary version fields use the
numeric version required by their platforms.
For a desktop-only manual packaging preflight, set `desktop_only` to `true` and
pass the intended RC in `version`; this does not rebuild Android or publish assets.

CI produces an Apple Silicon DMG on `macos-14`. Intel Mac users build locally;
the Maven OS profiles select native dependencies for the current architecture.
