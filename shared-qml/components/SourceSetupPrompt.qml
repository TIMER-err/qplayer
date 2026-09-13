import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."

// Shared empty state for destinations that cannot work until at least one
// source plugin is active. It covers the destination beneath it so stale data
// and controls from a previously removed source cannot still be operated.
Rectangle {
    color: Theme.color.surface

    MouseArea { anchors.fill: parent }

    EmptyState {
        anchors.centerIn: parent
        icon: "extension"
        title: i18n.t("source.prompt.title")
        actionText: i18n.t("source.prompt.button")
        onActionRequested: player.requestSourceSetup()
    }
}
