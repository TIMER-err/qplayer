import QtQuick
import miuix.Core

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

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: dialog
        title: i18n.t("update.app.title")
        icon: "system_update"
        text: i18n.t("update.app.body", player.updateVersion)
        acceptText: i18n.t("update.now")
        rejectText: i18n.t("update.later")
        onAccepted: player.startUpdateDownload()
        // Releases the host's hold on the plugin-update prompt: only one update
        // dialog is ever on screen, and the app's own goes first.
        onClosed: player.appUpdatePromptClosed()

        // No Flickable of its own: Dialog scrolls its body once the notes push
        // it past the screen, and a nested scroller would just fight that one
        // for the drag.
        Text {
            width: dialog.contentWidth
            text: player.updateNotes
            color: Theme.color.onSurfaceVariantColor
            fontSize: 13
            wrapMode: Text.Wrap
        }
    }
}
