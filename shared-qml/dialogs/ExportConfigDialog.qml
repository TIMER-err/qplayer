import QtQuick
import miuix.Core
import design
import "../components"

Item {
    id: control
    anchors.fill: parent
    property bool active: false
    signal closed()
    signal exportEncrypted()
    signal exportPlain()

    onActiveChanged: {
        if (active) dialog.open()
        else if (dialog.opened) dialog.close()
    }

    Dialog {
        id: dialog
        objectName: "exportConfigDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        maxWidth: 480
        icon: "upload_file"
        title: i18n.t("settings.exportConfig.dialogTitle")
        text: i18n.t("settings.exportConfig.dialogDesc")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onRejected: control.closed()
        onClosed: control.closed()

        Item {
            id: optionsContainer
            width: dialog.contentWidth
            height: cardPlain.y + cardPlain.height

            Rectangle {
                id: cardEncrypted
                objectName: "exportEncryptedCard"
                width: parent.width
                height: Math.max(76, descEnc.y + descEnc.implicitHeight + 14)
                radius: Ui.radiusMd
                color: mouseEnc.pressed ? Ui.surfacePressed
                       : (mouseEnc.containsMouse ? Ui.surfaceHover : Ui.surfaceSunken)
                Behavior on color { ColorAnimation { duration: 140; easing.type: Easing.OutCubic } }

                Glyph {
                    x: 16
                    y: (parent.height - height) / 2
                    name: "lock"
                    color: Ui.accent
                }

                Text {
                    id: titleEnc
                    x: 52
                    y: 14
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportEncryptedConfig.title")
                    color: Ui.textPrimary
                    font.pixelSize: Ui.textBody
                    font.weight: Ui.weightBold
                    wrapMode: Text.Wrap
                }

                Text {
                    id: descEnc
                    x: 52
                    y: titleEnc.y + titleEnc.implicitHeight + 4
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportEncryptedConfig.desc")
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCaption
                    wrapMode: Text.Wrap
                }

                MouseArea {
                    id: mouseEnc
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        dialog.close()
                        control.exportEncrypted()
                    }
                }
            }

            Rectangle {
                id: cardPlain
                objectName: "exportPlainCard"
                y: cardEncrypted.y + cardEncrypted.height + Ui.space3
                width: parent.width
                height: Math.max(76, descPlain.y + descPlain.implicitHeight + 14)
                radius: Ui.radiusMd
                color: mousePlain.pressed ? Ui.surfacePressed
                       : (mousePlain.containsMouse ? Ui.surfaceHover : Ui.surfaceSunken)
                Behavior on color { ColorAnimation { duration: 140; easing.type: Easing.OutCubic } }

                Glyph {
                    x: 16
                    y: (parent.height - height) / 2
                    name: "description"
                    color: Ui.textSecondary
                }

                Text {
                    id: titlePlain
                    x: 52
                    y: 14
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportPlainConfig.title")
                    color: Ui.textPrimary
                    font.pixelSize: Ui.textBody
                    font.weight: Ui.weightBold
                    wrapMode: Text.Wrap
                }

                Text {
                    id: descPlain
                    x: 52
                    y: titlePlain.y + titlePlain.implicitHeight + 4
                    width: Math.max(0, parent.width - 68)
                    text: i18n.t("settings.exportPlainConfig.desc")
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCaption
                    wrapMode: Text.Wrap
                }

                MouseArea {
                    id: mousePlain
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        dialog.close()
                        control.exportPlain()
                    }
                }
            }
        }
    }
}
