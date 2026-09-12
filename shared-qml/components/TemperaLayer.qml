import QtQuick
import md3.Core

// Controls for the independent host-rendered Tempera page. The host fades
// the artwork and this subtree together, above the unchanged underlying page.
Item {
    id: layer
    objectName: "temperaChrome"
    visible: player.temperaOpen || player.temperaOpacity > 0.001

    // 空白处吞掉点击，避免手势穿到下面那层（那一层并没有被绘制）。
    MouseArea { anchors.fill: parent }

    Rectangle {
        id: capsule
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: settings.rightInset + 18
        anchors.bottomMargin: settings.bottomInset + 26
        height: 56
        width: row.implicitWidth + 16
        radius: height / 2
        color: settings.resolvedDark ? "#40FFFFFF" : "#33000000"

        Row {
            id: row
            anchors.centerIn: parent
            spacing: 2

            IconButton {
                type: "standard"
                objectName: "temperaClose"
                icon: "close"
                contentColor: settings.resolvedDark ? "#FFFFFFFF" : "#FF0B0B10"
                onClicked: player.setTemperaOpen(false)
            }
            IconButton {
                type: "filled"
                icon: player.playing ? "pause" : "play_arrow"
                onClicked: player.toggle()
            }
            IconButton {
                type: "standard"
                icon: "skip_next"
                contentColor: settings.resolvedDark ? "#FFFFFFFF" : "#FF0B0B10"
                onClicked: player.next()
            }
        }
    }
}
