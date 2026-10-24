import QtQuick
import QtQuick.Layouts
import miuix.Core

// Host-owned quality picker. qml4j drops JS closures on Menu.subItems, so the
// right-click Download entry only opens this dialog; each button is a real
// QML handler that calls Java with a quality token.
Item {
    id: control

    property bool openWatch: player.downloadQualityOpen
    onOpenWatchChanged: {
        if (player.downloadQualityOpen) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
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

        ColumnLayout {
            width: dialog.contentWidth
            spacing: 8

            Button {
                Layout.fillWidth: true
                height: 44
                text: i18n.t("menu.download.standard")
                type: "outlined"
                onClicked: { player.confirmDownloadQuality("standard"); dialog.close() }
            }
            Button {
                Layout.fillWidth: true
                height: 44
                text: i18n.t("menu.download.higher")
                type: "outlined"
                onClicked: { player.confirmDownloadQuality("higher"); dialog.close() }
            }
            Button {
                Layout.fillWidth: true
                height: 44
                text: i18n.t("menu.download.exhigh")
                type: "filled"
                onClicked: { player.confirmDownloadQuality("exhigh"); dialog.close() }
            }
            Button {
                Layout.fillWidth: true
                height: 44
                text: i18n.t("menu.download.lossless")
                type: "outlined"
                onClicked: { player.confirmDownloadQuality("lossless"); dialog.close() }
            }
            Button {
                Layout.fillWidth: true
                height: 44
                text: i18n.t("menu.download.hires")
                type: "outlined"
                onClicked: { player.confirmDownloadQuality("hires"); dialog.close() }
            }
        }
    }
}
