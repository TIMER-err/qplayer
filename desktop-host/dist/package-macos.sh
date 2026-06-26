#!/usr/bin/env bash
# Package the macOS native image into QPlayer.app + a .dmg.
# Run AFTER `mvn -pl desktop-host -Pnative package` (under a GraalVM JDK) on macOS.
#   bash desktop-host/dist/package-macos.sh
# Output: desktop-host/target/QPlayer.dmg
#
# NOTE: unsigned. For distribution outside your own machine the .app must be
# codesigned + notarized (Apple Developer ID), otherwise Gatekeeper blocks it.
set -euo pipefail
cd "$(dirname "$0")/../.."
T=desktop-host/target
BIN="$T/qplayer"
[ -x "$BIN" ] || { echo "native binary $BIN not found — run the native build first"; exit 1; }

APP="$T/QPlayer.app"
LIBS="$APP/Contents/lib"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$LIBS"

# Pick the natives matching this Mac's architecture.
if [ "$(uname -m)" = "arm64" ]; then SKP=macos-arm64; LWP=natives-macos-arm64; else SKP=macos-x64; LWP=natives-macos; fi

# binary + the JDK native libs (native-image emits *.dylib next to the binary; keep
# them next to the binary in Contents/MacOS where it resolves them).
cp "$BIN" "$APP/Contents/MacOS/qplayer-bin"
cp "$T"/*.dylib "$APP/Contents/MacOS/" 2>/dev/null || true

# Skija + LWJGL dylibs go in a separate Contents/lib (same shim-shadowing reason as
# Linux), pulled from the local Maven repo.
M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
for jar in \
  "$M2"/io/github/humbleui/skija-$SKP/*/skija-$SKP-*.jar \
  "$M2"/org/lwjgl/*/*/*-$LWP.jar; do
  [ -f "$jar" ] || continue
  case "$jar" in *sources*|*javadoc*) continue ;; esac
  unzip -o -j "$jar" '*.dylib' -d "$LIBS" >/dev/null 2>&1 || true
done
[ "$(ls -A "$LIBS" 2>/dev/null)" ] || { echo "no Skija/LWJGL .dylib found under $M2"; exit 1; }

# launcher (CFBundleExecutable): set the dylib path then exec the binary. A native
# image runs main() on thread 0, so GLFW's macOS main-thread rule is already met.
cat > "$APP/Contents/MacOS/qplayer" <<'EOF'
#!/bin/sh
HERE="$(cd "$(dirname "$0")" && pwd)"
export DYLD_LIBRARY_PATH="$HERE/../lib:${DYLD_LIBRARY_PATH:-}"
exec "$HERE/qplayer-bin" \
  -Dskija.library.path="$HERE/../lib" \
  -Dorg.lwjgl.librarypath="$HERE/../lib" \
  "$@"
EOF
chmod +x "$APP/Contents/MacOS/qplayer" "$APP/Contents/MacOS/qplayer-bin"

cp docs/icon.png "$APP/Contents/Resources/qplayer.png" 2>/dev/null || true
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>QPlayer</string>
  <key>CFBundleExecutable</key><string>qplayer</string>
  <key>CFBundleIdentifier</key><string>dev.t1m3.qplayer</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.8.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
EOF

# .dmg
hdiutil create -volname QPlayer -srcfolder "$APP" -ov -format UDZO "$T/QPlayer.dmg"
echo "→ $T/QPlayer.dmg"
