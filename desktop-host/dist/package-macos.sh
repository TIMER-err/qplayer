#!/usr/bin/env bash
# Package the macOS desktop app with jpackage into QPlayer.app + a .dmg.
# Run AFTER `mvn -pl desktop-host -Pdist package` (under a JDK 21) on macOS.
#   bash desktop-host/dist/package-macos.sh
# Output: desktop-host/target/QPlayer.dmg
#
# NOTE: unsigned. For distribution outside your own machine the .app must be
# codesigned + notarized (Apple Developer ID), otherwise Gatekeeper blocks it.
set -euo pipefail
cd "$(dirname "$0")/../.."
DIST="desktop-host/dist"
T=desktop-host/target
APP="$T/app"
[ -f "$APP/qplayer.jar" ] || { echo "$APP/qplayer.jar not found — run 'mvn -pl desktop-host -Pdist package' first"; exit 1; }

# Version for Info.plist. CI passes QPLAYER_VERSION (the release tag); a local
# build falls back to the latest git tag, then to 0.0.0.
VERSION="${QPLAYER_VERSION:-${GITHUB_REF_NAME:-}}"
VERSION="${VERSION#v}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  VERSION="${VERSION#v}"
fi
[ -n "$VERSION" ] || VERSION="0.0.0"
# jpackage (and macOS itself) only accept a numeric bundle version — digits and
# periods, at most three components. A tag suffix such as "-debug" is still what
# version.properties reports to the update check, but it cannot appear in
# CFBundleVersion, so drop everything from the first non-numeric character.
BUNDLE_VERSION="$(printf '%s' "$VERSION" | sed 's/[^0-9.].*$//; s/\.*$//')"
[ -n "$BUNDLE_VERSION" ] || BUNDLE_VERSION="0.0.0"
# CFBundleVersion's major must be > 0 or jpackage refuses the build, and QPlayer
# is still on 0.x. Bump only the number baked into the bundle — the version the
# app reports (and the update check compares) comes from version.properties.
case "$BUNDLE_VERSION" in 0.*|0) BUNDLE_VERSION="1${BUNDLE_VERSION#0}" ;; esac
echo "packaging QPlayer $VERSION (bundle version $BUNDLE_VERSION)"

# App icon: macOS wants a multi-resolution .icns, not a loose PNG. Build one from
# the 512px source with sips + iconutil (both ship with macOS); if that fails,
# jpackage falls back to its own generic icon rather than failing the build.
ICON_SRC="docs/icon.png"
ICON_ARG=()
if command -v sips >/dev/null && command -v iconutil >/dev/null && [ -f "$ICON_SRC" ]; then
  SET="$T/qplayer.iconset"
  rm -rf "$SET"; mkdir -p "$SET"
  # name:px pairs (16/32/128/256/512 pt, each + @2x, capped at the 512px source).
  for pair in 16x16:16 16x16@2x:32 32x32:32 32x32@2x:64 \
              128x128:128 128x128@2x:256 256x256:256 256x256@2x:512 512x512:512; do
    sips -z "${pair##*:}" "${pair##*:}" "$ICON_SRC" --out "$SET/icon_${pair%%:*}.png" >/dev/null 2>&1
  done
  if iconutil -c icns "$SET" -o "$T/qplayer.icns" 2>/dev/null; then
    ICON_ARG=(--icon "$T/qplayer.icns")
  fi
  rm -rf "$SET"
fi

# Shared module list (see jre-modules.txt), comments stripped.
MODS=$(sed 's/#.*//' "$DIST/jre-modules.txt" | tr -d '[:blank:]' | grep . | paste -sd, -)
JAVA_OPTION_ARGS=()
while IFS= read -r option; do
  JAVA_OPTION_ARGS+=(--java-options "$option")
done < <(sed 's/#.*//' "$DIST/jvm-options.txt" | awk 'NF')

rm -rf "$T/pkg"

# -XstartOnFirstThread is mandatory: GLFW must own thread 0 on macOS, and the JVM
# otherwise runs main() on a thread it spawns itself.
jpackage --type dmg \
  --name QPlayer --app-version "$BUNDLE_VERSION" --vendor t1m3 --description "QPlayer" \
  --input "$APP" --main-jar qplayer.jar --main-class dev.t1m3.qplayer.desktop.app.Main \
  --dest "$T/pkg" "${ICON_ARG[@]}" \
  --mac-package-identifier dev.t1m3.qplayer --mac-package-name QPlayer \
  --java-options -XstartOnFirstThread \
  "${JAVA_OPTION_ARGS[@]}" \
  --add-modules "$MODS" \
  --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6 --dedup-legal-notices=error-if-not-same-content"

# jpackage names it QPlayer-<version>.dmg; the release asset is plain QPlayer.dmg.
mv "$T"/pkg/QPlayer-*.dmg "$T/QPlayer.dmg"
rm -rf "$T/pkg"
echo "→ $T/QPlayer.dmg"
