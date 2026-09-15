#!/usr/bin/env bash
# Package the Linux desktop app with jpackage, then wrap it in a single-file AppImage.
# Run AFTER `mvn -pl desktop-host -Pdist package` (under a JDK 21).
#   bash desktop-host/dist/package-linux.sh
# Output: desktop-host/target/QPlayer-x86_64.AppImage
set -euo pipefail
cd "$(dirname "$0")/../.."                      # repo root
DIST="desktop-host/dist"
T=desktop-host/target
APP="$T/app"
[ -f "$APP/qplayer.jar" ] || { echo "$APP/qplayer.jar not found — run 'mvn -pl desktop-host -Pdist package' first"; exit 1; }

# Version for the bundle metadata. CI passes the release tag; a local build falls
# back to the latest git tag, then 0.0.0. Leading "v" stripped either way.
VERSION="${QPLAYER_VERSION:-${GITHUB_REF_NAME:-}}"
VERSION="${VERSION#v}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  VERSION="${VERSION#v}"
fi
[ -n "$VERSION" ] || VERSION="0.0.0"
# jpackage only accepts a numeric --app-version (digits and periods, at most three
# components). A tag suffix such as "-debug" is what version.properties reports to
# the update check, but it cannot go into the bundle metadata, so drop everything
# from the first non-numeric character.
BUNDLE_VERSION="$(printf '%s' "$VERSION" | sed 's/[^0-9.].*$//; s/\.*$//')"
[ -n "$BUNDLE_VERSION" ] || BUNDLE_VERSION="0.0.0"
echo "packaging QPlayer $VERSION (bundle version $BUNDLE_VERSION)"

# Shared module list (see jre-modules.txt), comments stripped.
MODS=$(sed 's/#.*//' "$DIST/jre-modules.txt" | tr -d '[:blank:]' | grep . | paste -sd, -)
JAVA_OPTION_ARGS=()
while IFS= read -r option; do
  JAVA_OPTION_ARGS+=(--java-options "$option")
done < <(sed 's/#.*//' "$DIST/jvm-options.txt" | awk 'NF')

rm -rf "$T/pkg" "$T/AppDir"

# jpackage jlinks the runtime from --add-modules and emits qplayer/{bin,lib}.
# Skija/LWJGL keep extracting their own natives out of the jars in lib/app, so
# there is nothing to hand-place here.
jpackage --type app-image \
  --name qplayer --app-version "$BUNDLE_VERSION" --vendor t1m3 --description "QPlayer" \
  --input "$APP" --main-jar qplayer.jar --main-class dev.t1m3.qplayer.desktop.app.Main \
  --dest "$T/pkg" \
  --add-modules "$MODS" \
  "${JAVA_OPTION_ARGS[@]}" \
  --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6 --dedup-legal-notices=error-if-not-same-content"

# AppDir = the jpackage image + the three things appimagetool insists on at the
# root: AppRun, a .desktop entry and a matching icon.
mv "$T/pkg/qplayer" "$T/AppDir"
rmdir "$T/pkg"

# Temurin's Linux jmods retain the native symbol table in libjvm even with
# jlink --strip-debug. It is useful to JDK developers but not required to run
# QPlayer; removing it saves roughly another 4 MiB from the runtime image.
if command -v strip >/dev/null 2>&1; then
  strip --strip-unneeded "$T/AppDir/lib/runtime/lib/server/libjvm.so"
fi

cat > "$T/AppDir/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/bin/qplayer" "$@"
EOF
chmod +x "$T/AppDir/AppRun"

cat > "$T/AppDir/qplayer.desktop" <<'EOF'
[Desktop Entry]
Name=QPlayer
Exec=qplayer
Icon=qplayer
Type=Application
Categories=AudioVideo;Audio;Player;
EOF
cp docs/icon.png "$T/AppDir/qplayer.png" 2>/dev/null || \
  cp shared-qml/app-icon.png "$T/AppDir/qplayer.png" 2>/dev/null || true

TOOL="${APPIMAGETOOL:-$T/appimagetool}"
if [ ! -x "$TOOL" ]; then
  curl -fL -o "$TOOL" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$TOOL"
fi
# APPIMAGE_EXTRACT_AND_RUN avoids needing FUSE (CI runners lack it).
OUTPUT="$T/QPlayer-x86_64.AppImage"
STAGED_OUTPUT="$T/QPlayer-x86_64.new.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$T/AppDir" "$STAGED_OUTPUT"
# Replacing by rename also works while an older AppImage is running; writing the
# final executable in place fails with ETXTBSY and leaves no new test artifact.
mv -f "$STAGED_OUTPUT" "$OUTPUT"
echo "→ $T/QPlayer-x86_64.AppImage"
