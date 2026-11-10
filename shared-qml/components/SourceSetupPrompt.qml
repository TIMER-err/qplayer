import QtQuick
import miuix.Core
import design
import "."

// Shared empty state for destinations that cannot work until at least one
// source plugin is active. It covers the destination beneath it so stale data
// and controls from a previously removed source cannot still be operated.
// Main.qml sizes this to the content panel (right of the sidebar, below the
// root header, above the player bar); the placeholder centres inside that box.
Rectangle {
    objectName: "sourceSetupPrompt"
    color: Ui.background

    MouseArea { anchors.fill: parent }

    EmptyState {
        objectName: "sourceSetupEmptyState"
        anchors.fill: parent
        icon: "extension"
        title: i18n.t("source.prompt.title")
        message: i18n.t("source.prompt.desc")
        actionText: i18n.t("source.prompt.button")
        actionIcon: "add"
        onActionRequested: player.requestSourceSetup()
    }
}
