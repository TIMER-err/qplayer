import QtQuick
import md3.Core

// Plugin update prompt, kept in its own document on purpose: qml4j compiles a
// whole QML file's construction into one generated constructor, and Main.qml is
// close enough to the JVM's 64 KB method limit that another inline Dialog there
// fails the build with MethodTooLargeException. A separate file gets its own
// generated class.
//
// This owns the whole "only one update dialog at a time" rule. The app's own
// update always wins: a QPlayer release can change what a plugin requires
// (minHostVersion), so updating the app first is the order that cannot deadlock.
// The host holds the prompt back while an app update is pending; the watchers
// here cover the opposite ordering, where the catalog refresh lands first and
// this dialog is already up when the app's check returns.
Item {
    id: control

    readonly property bool opened: dialog.opened

    function close() { dialog.close() }

    property bool appUpdateWatch: player.updateAvailable
    onAppUpdateWatchChanged: {
        if (!player.updateAvailable || !dialog.opened) return
        // Step aside without consuming the offer -- the host proposes this same
        // plugin again once the app dialog closes.
        player.deferPluginUpdate()
        dialog.close()
    }

    property bool pluginUpdateWatch: player.pluginUpdateAvailable
    onPluginUpdateWatchChanged: {
        if (!player.pluginUpdateAvailable || dialog.opened) return
        if (player.updateAvailable) return
        dialog.open()
    }

    Dialog {
        id: dialog
        title: "插件有新版本"
        icon: "extension"
        text: player.pluginUpdateName + " " + player.pluginUpdateVersion
              + " 可用（当前 " + player.pluginUpdateInstalledVersion + "）"
        acceptText: "立即更新"
        rejectText: "稍后"
        // Routes into the normal catalog install path, so the permission sheet
        // still appears before the new version is activated.
        onAccepted: player.installCatalogPlugin(player.pluginUpdateId)
        onClosed: player.acknowledgePluginUpdate()
    }
}
