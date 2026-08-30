import QtQuick
import md3.Core

// The app's own new-version dialog. Kept in its own document because qml4j
// compiles a whole QML file's construction into one generated constructor and
// Main.qml sits right at the JVM's 64 KB method limit -- an inline Dialog of
// this size there fails with MethodTooLargeException.
//
// The host's startup check sets player.updateAvailable when a newer GitHub
// release exists; accepting downloads the package in-app (through the mirror)
// and hands it to the system installer.
Item {
    id: control

    readonly property bool opened: dialog.opened

    property bool updateWatch: player.updateAvailable
    onUpdateWatchChanged: if (player.updateAvailable) dialog.open()

    Dialog {
        id: dialog
        title: "发现新版本"
        icon: "system_update"
        text: "新版本 " + player.updateVersion + " 现已发布"
        acceptText: "立即更新"
        rejectText: "稍后"
        onAccepted: player.startUpdateDownload()
        // Releases the host's hold on the plugin-update prompt: only one update
        // dialog is ever on screen, and the app's own goes first.
        onClosed: player.appUpdatePromptClosed()

        Flickable {
            width: parent.width
            height: Math.min(notesText.height, 260)
            contentHeight: notesText.height
            clip: true
            Text {
                id: notesText
                width: parent.width
                text: player.updateNotes
                color: Theme.color.onSurfaceVariantColor
                fontSize: 13
                wrapMode: Text.Wrap
            }
        }
    }
}
