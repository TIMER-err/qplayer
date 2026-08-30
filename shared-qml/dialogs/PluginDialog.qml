// The one dialog every plugin contribution is rendered into. QPlayer owns this
// document; a plugin only supplies a validated description (PluginUiDescription)
// and receives the ids of the buttons the user presses. No plugin QML, engine or
// realm is ever loaded, so a plugin is theme-correct by construction and
// carries none of a third-party document's authority.

import "../components"
import QtQuick
import QtQuick.Layouts
import md3.Core

// Wrap the Dialog instead of deriving from it: qml4j loses user-defined
// base-type properties (notably Dialog.icon) across that composition boundary.
Item {
    id: control

    property var body: []
    readonly property var slots: [slot0, slot1, slot2, slot3, slot4, slot5, slot6, slot7, slot8, slot9]
    property string jsonWatch: player.pluginDialogJson
    property bool openWatch: player.pluginDialogOpen

    function open() {
        dialog.open();
    }

    function close() {
        dialog.close();
    }

    function described(field, fallback) {
        if (player.pluginDialogJson && player.pluginDialogJson.length > 0) {
            try {
                var value = JSON.parse(player.pluginDialogJson)[field];
                if (value !== undefined && value !== "")
                    return value;

            } catch (_) {
            }
        }
        return fallback;
    }

    function refreshBody() {
        var parsed = [];
        if (player.pluginDialogJson && player.pluginDialogJson.length > 0) {
            try {
                parsed = JSON.parse(player.pluginDialogJson).body || [];
            } catch (_) {
                parsed = [];
            }
        }
        control.body = parsed;
    }

    function submit(actionId) {
        var inputs = {
        };
        for (var i = 0; i < control.slots.length; i++) {
            var slot = control.slots[i];
            if (slot && slot.node && slot.node.type === "input")
                inputs[slot.node.id] = slot.inputValue;

        }
        player.pluginDialogAction(actionId, JSON.stringify(inputs));
    }

    onJsonWatchChanged: refreshBody()
    onOpenWatchChanged: {
        if (player.pluginDialogOpen) {
            refreshBody();
            dialog.open();
        } else {
            dialog.close();
        }
    }

    // The plugin asks for its own cadence; the host owns the timer so a plugin
    // cannot spin one faster than the validated bounds allow.
    Timer {
        interval: Math.max(500, control.described("refreshMs", 0))
        running: player.pluginDialogOpen && control.described("refreshMs", 0) > 0
        repeat: true
        onTriggered: player.pluginDialogRefresh()
    }

    Dialog {
        id: dialog

        icon: control.described("icon", "extension")
        title: control.described("title", player.pluginDialogTitle)
        text: player.pluginDialogError && player.pluginDialogError.length > 0 ? player.pluginDialogError : (control.body.length === 0 ? "正在载入…" : "")
        showAcceptButton: false
        rejectText: "关闭"
        onClosed: player.closePluginDialog()
        onRejected: player.closePluginDialog()

        ColumnLayout {
            id: nodeColumn

            width: parent.width
            spacing: 10

            PluginDialogNode {
                id: slot0

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 0 ? control.body[0] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot1

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 1 ? control.body[1] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot2

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 2 ? control.body[2] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot3

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 3 ? control.body[3] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot4

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 4 ? control.body[4] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot5

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 5 ? control.body[5] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot6

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 6 ? control.body[6] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot7

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 7 ? control.body[7] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot8

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 8 ? control.body[8] : null
                busy: player.pluginDialogBusy
                host: control
            }

            PluginDialogNode {
                id: slot9

                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                node: control.body.length > 9 ? control.body[9] : null
                busy: player.pluginDialogBusy
                host: control
            }

        }

    }

}
