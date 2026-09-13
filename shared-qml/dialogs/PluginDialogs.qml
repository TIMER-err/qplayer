import QtQuick
import miuix.Core
import "."

// App-wide plugin onboarding, install consent and removal confirmation. Keeping
// this in a separate component avoids inflating Main.qml's generated constructor
// past the JVM's 64 KiB method limit.
Item {
    id: root

    readonly property bool modalOpened: sourceSetupDialog.opened
                                        || pluginWarningDialog.opened
                                        || pluginRemovalDialog.opened

    function handleBack() {
        return root.modalOpened
    }

    SourceSetupDialog { id: sourceSetupDialog }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: pluginWarningDialog
        title: i18n.t(player.pendingPluginTrusted ? "plugin.install.trusted.title"
                      : "plugin.install.untrusted.title")
        icon: player.pendingPluginTrusted ? "verified_user" : "warning"
        closeOnScrim: false
        acceptText: i18n.t(player.pluginInstallBusy ? "plugin.install.busy"
                           : (player.pendingPluginTrusted ? "plugin.install.accept"
                              : "plugin.install.acceptRisky"))
        rejectText: i18n.t("common.cancel")
        text: i18n.t("plugin.install.body", player.pendingPluginName,
                     player.pendingPluginVersion)
              .replace("{2}", player.pendingPluginId)
              .replace("{3}", player.pendingPluginPermissions)
              + i18n.t(player.pendingPluginTrusted ? "plugin.install.trustedNote"
                       : "plugin.install.untrustedNote")
        onAccepted: player.confirmPendingPluginInstall()
        onRejected: player.cancelPendingPluginInstall()
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: pluginRemovalDialog
        title: i18n.t("plugin.remove.title")
        icon: "delete"
        closeOnScrim: false
        acceptText: i18n.t(player.pluginInstallBusy ? "plugin.remove.busy"
                           : "plugin.page.remove.button")
        rejectText: i18n.t("common.cancel")
        text: i18n.t("plugin.remove.body", player.pendingPluginRemovalName)
        onAccepted: player.confirmSourcePluginRemoval()
        onRejected: player.cancelSourcePluginRemoval()
    }

    Timer {
        id: sourceSetupOpenTimer
        interval: 1
        repeat: false
        onTriggered: sourceSetupDialog.open()
    }

    property real sourceSetupWatch: player.sourceSetupRevision
    onSourceSetupWatchChanged: {
        if (player.sourceSetupRevision > 0) sourceSetupOpenTimer.restart()
    }

    property real pluginInstallPromptWatch: player.pluginInstallPromptRevision
    onPluginInstallPromptWatchChanged: {
        if (player.pluginInstallPromptRevision > 0) pluginWarningDialog.open()
    }

    property real pluginRemovalPromptWatch: player.pluginRemovalPromptRevision
    onPluginRemovalPromptWatchChanged: {
        if (player.pluginRemovalPromptRevision > 0) pluginRemovalDialog.open()
    }

    Component.onCompleted: {
        if (player.sourceSetupPending) sourceSetupOpenTimer.restart()
    }
}
