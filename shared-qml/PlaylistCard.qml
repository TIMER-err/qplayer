import QtQuick
import md3.Core
import "."

// A playlist tile. Its size is fixed from `tile` (computed by the page from the
// available width), set as implicitWidth so GridLayout sizes its columns
// correctly. No dependence on the layout-assigned width → no feedback loop.
Item {
    id: card

    property string name: ""
    property int count: 0
    property string coverUrl: ""
    property string coverThumbPath: ""
    property real tile: 160
    signal clicked()

    implicitWidth: tile
    implicitHeight: tile + 52

    // Explicit sibling anchors instead of a Column: qml4j's positioner skips an
    // invisible child and does not re-flow when a child's `visible` flips false→true,
    // so the count label — hidden while the playlist is empty — landed at (0,0) over
    // the cover the moment a first track pushed count past 0 (fixed only by a restart,
    // which rebuilt the card already-visible). Anchored positions are resolved
    // regardless of visibility, so the toggle no longer moves anything.
    CoverImage {
        id: cover
        x: 4
        y: 4
        width: card.width - 8
        height: card.width - 8
        radius: 14
        icon: "queue_music"
        iconSize: 44
        fadeIn: true
        source: card.coverThumbPath || card.coverUrl
    }
    Text {
        id: nameLabel
        x: 4
        anchors.top: cover.bottom
        anchors.topMargin: 6
        width: card.width - 8
        text: card.name
        color: Theme.color.onSurfaceColor
        fontSize: 14
        elide: Text.ElideRight
    }
    Text {
        x: 4
        anchors.top: nameLabel.bottom
        anchors.topMargin: 2
        width: card.width - 8
        visible: card.count > 0
        text: card.count + " 首"
        color: Theme.color.onSurfaceVariantColor
        fontSize: 12
    }

    MouseArea {
        id: cardMa
        anchors.fill: parent
        hoverEnabled: true
        onClicked: card.clicked()
    }
}
