import QtQuick
import md3.Core

// Compact horizontal artist entry used by the song-credits picker. It mirrors
// SongRow's long, narrow interaction shape while using a circular avatar.
Rectangle {
    id: row

    property double artistId: 0
    property string name: ""
    property string coverUrl: ""
    property string coverThumbPath: ""
    signal activated()

    implicitHeight: 64
    color: "transparent"

    Rectangle {
        anchors.fill: parent
        anchors.leftMargin: 4
        anchors.rightMargin: 4
        anchors.topMargin: 4
        anchors.bottomMargin: 4
        radius: 12
        color: Theme.color.surfaceContainerHighest
        opacity: ripple.containsMouse ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
    }

    Item {
        id: avatar
        anchors.left: parent.left
        anchors.leftMargin: 12
        anchors.verticalCenter: parent.verticalCenter
        width: 44
        height: 44

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: Theme.color.surfaceContainerHighest
            visible: row.coverThumbPath === "" && row.coverUrl === ""
            Text {
                anchors.centerIn: parent
                text: "person"
                font.family: Theme.iconFont.name
                font.pixelSize: 22
                color: Theme.color.onSurfaceVariantColor
            }
        }

        Image {
            anchors.fill: parent
            source: row.coverThumbPath !== "" ? row.coverThumbPath : row.coverUrl
            radius: width / 2
            fillMode: Image.PreserveAspectCrop
            // See CoverImage.qml's own comment: without sourceSize, a large
            // fetched avatar (up to 512px) gets decoded at full resolution and
            // downscaled to this 44px avatar every frame with plain (non-
            // mipmap) bilinear sampling -- aliases into visible moiré on
            // detailed source art. sourceSize routes it through the mipmap-
            // quality decode-time downscale instead, at the device pixel ratio
            // so it decodes at display resolution rather than 1x.
            sourceSize.width: Math.round(width * player.pixelRatio)
            sourceSize.height: Math.round(height * player.pixelRatio)
        }
    }

    Text {
        anchors.left: avatar.right
        anchors.leftMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: 16
        anchors.verticalCenter: parent.verticalCenter
        text: row.name
        elide: Text.ElideRight
        color: Theme.color.onSurfaceColor
        fontSize: 15
    }

    Ripple {
        id: ripple
        x: 4
        y: 4
        width: row.width - 8
        height: row.height - 8
        clipRadius: 12
        rippleColor: Theme.color.onSurfaceColor
        onClicked: row.activated()
    }
}
