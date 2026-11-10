import QtQuick
import miuix.Core
import design

// Host-owned quality picker. qml4j drops JS closures on Menu.subItems, so the
// right-click Download entry only opens this dialog; each button is a real
// QML handler that calls Java with a quality token. Very high (exhigh) is the
// filled default; the others are tonal.
Item {
    id: control

    readonly property var qualities: ["standard", "higher", "exhigh", "lossless", "hires"]
    readonly property real buttonHeight: 48
    readonly property real gap: Ui.space2

    property bool openWatch: player.downloadQualityOpen
    onOpenWatchChanged: {
        if (player.downloadQualityOpen) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
        objectName: "downloadQualityDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "download"
        title: i18n.t("download.quality.title")
        text: player.downloadQualityTitle
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onRejected: player.cancelDownloadQuality()
        onClosed: {
            if (player.downloadQualityOpen) player.cancelDownloadQuality()
        }

        Item {
            width: dialog.contentWidth
            height: control.qualities.length * (control.buttonHeight + control.gap)

            // Placed by index: qml4j does not position Repeater children.
            Repeater {
                model: control.qualities
                Button {
                    objectName: "downloadQuality_" + modelData
                    y: index * (control.buttonHeight + control.gap)
                    width: parent.width
                    height: control.buttonHeight
                    type: modelData === "exhigh" ? "filled" : "filledTonal"
                    text: i18n.t("menu.download." + modelData)
                    onClicked: { player.confirmDownloadQuality(modelData); dialog.close() }
                }
            }
        }
    }
}
