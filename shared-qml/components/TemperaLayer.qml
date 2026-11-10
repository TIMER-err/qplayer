import QtQuick
import miuix.Core
import design

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
        height: 60
        width: row.implicitWidth + 16
        radius: height / 2
        // Frosted-looking capsule over the artwork: raised surface at partial
        // alpha plus a hairline, so it reads on both bright and dark covers.
        color: Ui.hexAlpha(Ui.surfaceRaised, 0.72)
        border.width: 1
        border.color: Ui.hairline

        Row {
            id: row
            anchors.centerIn: parent
            spacing: Ui.space1

            IconButton {
                type: "standard"
                objectName: "temperaClose"
                anchors.verticalCenter: parent.verticalCenter
                icon: "close"
                contentColor: Ui.textPrimary
                onClicked: player.setTemperaOpen(false)
            }
            IconButton {
                type: "filled"
                width: 44
                height: 44
                anchors.verticalCenter: parent.verticalCenter
                icon: player.playing ? "pause" : "play_arrow"
                onClicked: player.toggle()
            }
            IconButton {
                type: "standard"
                icon: "skip_next"
                anchors.verticalCenter: parent.verticalCenter
                contentColor: Ui.textPrimary
                onClicked: player.next()
            }
        }
    }
}
