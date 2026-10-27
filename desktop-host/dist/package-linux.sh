#!/usr/bin/env bash
# Wrap the GraalVM native executable in a single-file AppImage; no JVM is shipped.
# Run AFTER `mvn -pl desktop-host -am -Pdist package` under GraalVM JDK 25.
#   bash desktop-host/dist/package-linux.sh
# Output: desktop-host/target/QPlayer-x86_64.AppImage
set -euo pipefail
cd "$(dirname "$0")/../.."                      # repo root
DIST="desktop-host/dist"
T=desktop-host/target
BINARY="$T/qplayer"
[ -x "$BINARY" ] || { echo "$BINARY not found — run 'mvn -pl desktop-host -am -Pdist package' first"; exit 1; }
[ -d "$T/native-legal" ] || { echo "$T/native-legal not found — rebuild with -Pdist"; exit 1; }

# Version for the bundle metadata. CI passes the release tag; a local build falls
# back to the latest git tag, then 0.0.0. Leading "v" stripped either way.
VERSION="${QPLAYER_VERSION:-${GITHUB_REF_NAME:-}}"
VERSION="${VERSION#v}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  VERSION="${VERSION#v}"
fi
[ -n "$VERSION" ] || VERSION="0.0.0"
echo "packaging QPlayer $VERSION"

# Keep the same release asset and freedesktop identity, without a bundled JRE.
rm -rf "$T/AppDir"
install -Dm755 "$BINARY" "$T/AppDir/usr/bin/qplayer"
# Keep Native Image's emitted JNI libraries next to the executable.
for library in "$T"/*.so "$T"/*.so.*; do
  [ -f "$library" ] || continue
  install -m755 "$library" "$T/AppDir/usr/bin/"
done
mkdir -p "$T/AppDir/usr/share/licenses/qplayer"
cp -a "$T/native-legal/." "$T/AppDir/usr/share/licenses/qplayer/"

cat > "$T/AppDir/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/qplayer" "$@"
EOF
chmod +x "$T/AppDir/AppRun"

cat > "$T/AppDir/dev.t1m3.qplayer.desktop" <<'EOF'
[Desktop Entry]
Name=QPlayer
Exec=qplayer
Icon=qplayer
Type=Application
Categories=AudioVideo;Audio;Player;
EOF
cp docs/icon.png "$T/AppDir/qplayer.png" 2>/dev/null || \
  cp shared-qml/app-icon.png "$T/AppDir/qplayer.png" 2>/dev/null || true
install -Dm644 "$T/AppDir/dev.t1m3.qplayer.desktop" \
  "$T/AppDir/usr/share/applications/dev.t1m3.qplayer.desktop"
install -Dm644 "$T/AppDir/qplayer.png" \
  "$T/AppDir/usr/share/icons/hicolor/512x512/apps/qplayer.png"
install -Dm644 "$DIST/dev.t1m3.qplayer.appdata.xml" \
  "$T/AppDir/usr/share/metainfo/dev.t1m3.qplayer.appdata.xml"

TOOL="${APPIMAGETOOL:-$T/appimagetool}"
if [ ! -x "$TOOL" ]; then
  curl -fL -o "$TOOL" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$TOOL"
fi
# APPIMAGE_EXTRACT_AND_RUN avoids needing FUSE (CI runners lack it).
OUTPUT="$T/QPlayer-x86_64.AppImage"
STAGED_OUTPUT="$T/QPlayer-x86_64.new.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$T/AppDir" "$STAGED_OUTPUT"
# Replacing by rename also works while an older AppImage is running; writing the
# final executable in place fails with ETXTBSY and leaves no new test artifact.
mv -f "$STAGED_OUTPUT" "$OUTPUT"
echo "→ $T/QPlayer-x86_64.AppImage"
