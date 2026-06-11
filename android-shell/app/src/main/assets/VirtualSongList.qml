import QtQuick
import md3.Core
import "."

// Windowed song list: qml4j neither virtualizes ListView nor culls off-screen
// Flickable children, so a long Repeater paints + lays out every row each frame
// (and keeps doing so off-page while playback keeps the scene dirty). Here only
// the visible window (+buffer) of SongRows exists; as `first` tracks contentY
// the rows rebind/reposition (recycle). `list` is the full List Property.
Flickable {
    id: view

    property var list
    property bool isLocal: false
    property int rowH: 64
    property int activatedIndex: -1
    signal activated()

    property int count: list ? list.length : 0
    property int first: Math.max(0, Math.floor(contentY / rowH) - 2)
    property int visCount: Math.max(0, Math.min(count - first, Math.ceil(height / rowH) + 4))

    clip: true
    contentWidth: width
    contentHeight: count * rowH

    Item {
        width: view.width
        height: view.contentHeight

        Repeater {
            model: view.visCount
            SongRow {
                property int abs: view.first + index
                width: view.width
                y: abs * view.rowH
                rowTitle: { var it = view.list[abs]; return it ? (view.isLocal ? it.title : it.name) : "" }
                rowArtist: { var it = view.list[abs]; return it ? it.artist : "" }
                highlighted: view.isLocal && abs === player.index
                onActivated: { view.activatedIndex = abs; view.activated() }
            }
        }
    }
}
