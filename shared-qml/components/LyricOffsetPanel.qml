import QtQuick
import miuix.Core
import design

// Lyric-timing offset panel, dropped down from the lyric page's top-right sync
// button. Its own file to keep LyricOverlay under qml4j's 64 KB per-method
// limit. A local panel, NOT a root Dialog: the host only re-renders the
// lyricChrome subtree while the lyric page covers the scene.
Item {
    id: panel
    property bool open: false
    // Seed the slider once on open; see LyricOverlay.onOffsetPanelOpenChanged.
    function syncFromPlayer() { offsetSlider.value = player.lyricOffsetMs }
    // Keep painting until the exit fade reaches zero; binding visible directly
    // to offsetPanelOpen would cut the zoom-out off on its first frame.
    visible: panel.open || opacity > 0.01
    opacity: panel.open ? 1 : 0
    scale: panel.open ? 1 : 0.8
    transformOrigin: Item.Center
    Behavior on opacity {
        NumberAnimation { duration: 150 }
    }
    Behavior on scale {
        NumberAnimation {
            duration: panel.open ? 200 : 150
            easing.type: panel.open ? Easing.OutCubic : Easing.InCubic
        }
    }
    width: Math.min(320, parent.width - 24)
    height: 176

    // Composed, not inherited: a QML type derived from another custom QML type
    // loses its children in qml4j.
    SmoothRectangle {
        anchors.fill: parent
        radius: Ui.radiusLg
        color: Theme.color.surfaceContainerHigh
        borderWidth: 1
        borderColor: Ui.hairline
    }

    MouseArea { anchors.fill: parent }

    Text {
        id: offsetTitle
        anchors.top: parent.top
        anchors.topMargin: 18
        anchors.left: parent.left
        anchors.leftMargin: 18
        text: i18n.t("lyric.offset.title")
        color: Ui.textPrimary
        font.pixelSize: Ui.textHeadline
        font.weight: Ui.weightBold
    }
    // Current offset as an accent pill, so the value reads at a glance.
    Rectangle {
        id: offsetValuePill
        anchors.top: parent.top
        anchors.topMargin: 14
        anchors.right: parent.right
        anchors.rightMargin: 14
        width: offsetValueText.implicitWidth + 20
        height: 28
        radius: 14
        color: Ui.accentSoft
        Text {
            id: offsetValueText
            x: (parent.width - implicitWidth) / 2
            y: (parent.height - implicitHeight) / 2
            text: (offsetSlider.value > 0 ? "+" : "") + offsetSlider.value + " ms"
            color: Ui.accent
            font.pixelSize: Ui.textCallout
            font.weight: Ui.weightBold
        }
    }
    Text {
        id: offsetCaption
        anchors.top: offsetTitle.bottom
        anchors.topMargin: 6
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 18
        anchors.rightMargin: 18
        text: i18n.t("lyric.offset.desc")
        color: Ui.textSecondary
        font.pixelSize: Ui.textCaption
        elide: Text.ElideRight
    }

    Slider {
        id: offsetSlider
        anchors.top: offsetCaption.bottom
        anchors.topMargin: 10
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 18
        anchors.rightMargin: 18
        from: -5000
        to: 5000
        stepSize: 50
        snapMode: true
        value: 0
        // Keep dragging entirely local. Updating PlayerController on every 50 ms
        // step re-enters the QML tree (lyric index + persistence) during the
        // pointer callback and interrupts the gesture in qml4j.
        onEditingFinished: player.setLyricOffset(value)
    }

    Text {
        anchors.top: offsetSlider.bottom
        anchors.topMargin: 1
        anchors.left: parent.left
        anchors.leftMargin: 18
        text: i18n.t("lyric.offset.earlier")
        color: Ui.textTertiary
        font.pixelSize: Ui.textMicro
    }
    Text {
        anchors.top: offsetSlider.bottom
        anchors.topMargin: 1
        anchors.horizontalCenter: parent.horizontalCenter
        text: "0"
        color: Ui.textTertiary
        font.pixelSize: Ui.textMicro
    }
    Text {
        anchors.top: offsetSlider.bottom
        anchors.topMargin: 1
        anchors.right: parent.right
        anchors.rightMargin: 18
        text: i18n.t("lyric.offset.later")
        color: Ui.textTertiary
        font.pixelSize: Ui.textMicro
    }
    Button {
        id: resetBtn
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 8
        anchors.right: parent.right
        anchors.rightMargin: 10
        type: "text"
        text: i18n.t("lyric.offset.reset")
        enabled: offsetSlider.value !== 0
        onClicked: {
            offsetSlider.value = 0
            player.resetLyricOffset()
        }
    }
}
