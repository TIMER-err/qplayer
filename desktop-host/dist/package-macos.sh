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
DIST_UPDATE_STAGE=desktop-host/dist/macos-update-stage.sh
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
rm -rf "$APP" "$T/dmg-root"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/licenses" "$T/dmg-root"
cp "$BINARY" "$APP/Contents/MacOS/qplayer"
chmod +x "$APP/Contents/MacOS/qplayer"
for library in "$T"/*.dylib "$T"/*.jnilib; do
  [ -f "$library" ] || continue
  cp "$library" "$APP/Contents/MacOS/"
done
cp -R "$T/native-legal/." "$APP/Contents/Resources/licenses/"
install -m 755 "$DIST_UPDATE_STAGE" "$APP/Contents/Resources/qplayer-update-stage"
xcrun clang -O2 -mmacosx-version-min=13.0 desktop-host/dist/macos-update-swap.c \
  -o "$APP/Contents/MacOS/qplayer-update-swap"

sips -s format icns docs/icon.png --out "$APP/Contents/Resources/qplayer.icns" >/dev/null
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
if [ -n "${QPLAYER_MAC_SIGN_IDENTITY:-}" ]; then
  : "${QPLAYER_MAC_NOTARY_PROFILE:?Missing notarytool keychain profile}"
  for native in "$APP"/Contents/MacOS/*; do
    codesign --force --options runtime --timestamp --sign "$QPLAYER_MAC_SIGN_IDENTITY" "$native"
  done
  codesign --force --options runtime --timestamp --sign "$QPLAYER_MAC_SIGN_IDENTITY" "$APP"
  (cd "$T" && ditto -c -k --keepParent QPlayer.app QPlayer-notary.zip)
  xcrun notarytool submit "$T/QPlayer-notary.zip" --keychain-profile "$QPLAYER_MAC_NOTARY_PROFILE" --wait
  xcrun stapler staple "$APP"
  rm -f "$T/QPlayer-notary.zip"
else
  codesign --force --deep --sign - "$APP"
fi
cp -R "$APP" "$T/dmg-root/"
ln -s /Applications "$T/dmg-root/Applications"
hdiutil create -volname QPlayer -srcfolder "$T/dmg-root" -ov -format UDZO "$T/QPlayer.new.dmg"
mv -f "$T/QPlayer.new.dmg" "$T/QPlayer.dmg"
rm -rf "$T/dmg-root"
echo "→ $T/QPlayer.dmg"
