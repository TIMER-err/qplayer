import QtQuick
import miuix.Core
import design

Rectangle {
    id: badge
    signal clicked()
    visible: player.currentMediaId !== "" && player.currentMediaId.indexOf("local:") !== 0
    width: qualityText.implicitWidth + 14
    height: 22
    radius: 4
    color: "#2EFFFFFF"
    border.width: 1
    border.color: "#40FFFFFF"

    Text {
        id: qualityText
        anchors.centerIn: parent
        text: i18n.t("menu.download." + player.playbackQuality)
        color: "#B3FFFFFF"
        font.pixelSize: Ui.textMicro
        font.weight: Ui.weightMedium
    }

    MouseArea {
        anchors.fill: parent
        cursorShape: Qt.PointingHandCursor
        onClicked: badge.clicked()
    }
}
