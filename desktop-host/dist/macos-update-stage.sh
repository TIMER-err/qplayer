#!/bin/sh
# Called with a hash-verified DMG and two private, freshly-created directories.
set -eu
dmg=$1
mount=$2
destination=$3
team=$4
attached=0
cleanup() {
    if [ "$attached" -eq 1 ]; then
        /usr/bin/hdiutil detach "$mount" >&2 || true
    fi
}
trap cleanup EXIT
/usr/bin/hdiutil attach -readonly -nobrowse -noautoopen -mountpoint "$mount" "$dmg" >&2
attached=1
source="$mount/QPlayer.app"
[ -d "$source" ] && [ ! -L "$source" ]
requirement="identifier \"dev.t1m3.qplayer\" and anchor apple generic and certificate leaf[subject.OU] = \"$team\""
/usr/bin/codesign --verify --deep --strict "$source"
/usr/bin/codesign --verify -R "$requirement" "$source"
/usr/sbin/spctl --assess --type execute "$source"
/usr/bin/ditto "$source" "$destination"
/usr/bin/codesign --verify --deep --strict "$destination"
/usr/bin/codesign --verify -R "$requirement" "$destination"
