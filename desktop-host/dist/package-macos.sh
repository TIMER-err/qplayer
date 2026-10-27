#!/usr/bin/env bash
# Bundle the GraalVM native executable as QPlayer.app and a DMG, without a JVM.
# Run AFTER `mvn -pl desktop-host -am -Pdist package` under GraalVM JDK 25 on macOS.
#   bash desktop-host/dist/package-macos.sh
# Output: desktop-host/target/QPlayer.dmg
#
# NOTE: unsigned. For distribution outside your own machine the .app must be
# codesigned + notarized (Apple Developer ID), otherwise Gatekeeper blocks it.
set -euo pipefail
cd "$(dirname "$0")/../.."
T=desktop-host/target
BINARY="$T/qplayer"
APP="$T/QPlayer.app"
[ -x "$BINARY" ] || { echo "$BINARY not found — run 'mvn -pl desktop-host -am -Pdist package' first"; exit 1; }
[ -d "$T/native-legal" ] || { echo "$T/native-legal not found — rebuild with -Pdist"; exit 1; }

# Version for Info.plist. CI passes QPLAYER_VERSION (the release tag); a local
# build falls back to the latest git tag, then to 0.0.0.
VERSION="${QPLAYER_VERSION:-${GITHUB_REF_NAME:-}}"
VERSION="${VERSION#v}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  VERSION="${VERSION#v}"
fi
[ -n "$VERSION" ] || VERSION="0.0.0"
# Apple bundle versions are numeric; the application retains the full RC version.
BUNDLE_VERSION="${VERSION%%-*}"
BUNDLE_VERSION="${BUNDLE_VERSION%%+*}"
echo "packaging QPlayer $VERSION"

# Native Image's main() already owns the initial OS thread; no JVM launcher flags.
rm -rf "$APP" "$T/dmg-root" "$T/qplayer.iconset"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/licenses" "$T/dmg-root"
cp "$BINARY" "$APP/Contents/MacOS/qplayer"
chmod +x "$APP/Contents/MacOS/qplayer"
for library in "$T"/*.dylib "$T"/*.jnilib; do
  [ -f "$library" ] || continue
  cp "$library" "$APP/Contents/MacOS/"
done
cp -R "$T/native-legal/." "$APP/Contents/Resources/licenses/"

SET="$T/qplayer.iconset"
mkdir -p "$SET"
for pair in 16x16:16 16x16@2x:32 32x32:32 32x32@2x:64 \
            128x128:128 128x128@2x:256 256x256:256 256x256@2x:512 512x512:512; do
  sips -z "${pair##*:}" "${pair##*:}" docs/icon.png --out "$SET/icon_${pair%%:*}.png" >/dev/null
done
iconutil -c icns "$SET" -o "$APP/Contents/Resources/qplayer.icns"
rm -rf "$SET"
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>qplayer</string>
  <key>CFBundleIdentifier</key><string>dev.t1m3.qplayer</string>
  <key>CFBundleName</key><string>QPlayer</string>
  <key>CFBundleDisplayName</key><string>QPlayer</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$BUNDLE_VERSION</string>
  <key>CFBundleVersion</key><string>$BUNDLE_VERSION</string>
  <key>CFBundleIconFile</key><string>qplayer.icns</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
EOF
printf 'APPL????' > "$APP/Contents/PkgInfo"
# Ad-hoc signing is required on Apple Silicon; this is not Developer ID signing.
codesign --force --deep --sign - "$APP"
cp -R "$APP" "$T/dmg-root/"
ln -s /Applications "$T/dmg-root/Applications"
hdiutil create -volname QPlayer -srcfolder "$T/dmg-root" -ov -format UDZO "$T/QPlayer.new.dmg"
mv -f "$T/QPlayer.new.dmg" "$T/QPlayer.dmg"
rm -rf "$T/dmg-root"
echo "→ $T/QPlayer.dmg"
