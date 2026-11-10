import QtQuick
import miuix.Core
import design

// Large-title header for the root destinations (推荐 / 搜索 / 本地 / 我的) with
// the global actions on the right: 凝彩, mobile volume, queue and, on compact
// widths, the overflow menu fed by AppActions.
Item {
    id: header
    property string title: ""
    property bool compact: width < Ui.compactMax
    property var appActions
    signal queueRequested()
    signal volumeRequested()

    height: Ui.headerHeight

    Text {
        id: titleText
        objectName: "rootHeaderTitle"
        x: Ui.gutter(header.width)
        y: (header.height - implicitHeight) / 2
        width: Math.max(0, actionRow.x - x - 12)
        text: header.title
        color: Ui.textPrimary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: header.compact ? Ui.textTitle1 : Ui.textDisplay
        font.weight: Ui.weightBold
    }

    Row {
        id: actionRow
        x: header.width - width - Math.max(4, Ui.gutter(header.width) - 9)
        y: (header.height - height) / 2
        height: Ui.iconButton
        spacing: 2

        IconButton {
            objectName: "openTempera"
            visible: settings.has("temperaWholeLine")
            type: "standard"
            icon: "auto_awesome"
            onClicked: player.setTemperaOpen(true)
        }
        IconButton {
            objectName: "mobileVolumeAction"
            visible: !settings.has("desktopLyricEnabled")
            type: "standard"
            icon: settings.appVolume === 0 ? "volume_off"
                  : (settings.appVolume < 50 ? "volume_down" : "volume_up")
            onClicked: header.volumeRequested()
        }
        IconButton {
            objectName: "rootQueueAction"
            type: "standard"
            icon: "queue_music"
            onClicked: header.queueRequested()
        }
        IconButton {
            id: overflowAction
            objectName: "compactOverflowAction"
            visible: header.compact
            type: "standard"
            icon: "more_vert"
            onClicked: compactMenu.open(overflowAction)
        }
    }

    Menu {
        id: compactMenu
        objectName: "compactOverflowMenu"
        model: header.appActions ? header.appActions.menuModel() : []
    }
}
