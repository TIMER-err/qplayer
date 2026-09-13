import QtQuick
import QtQuick.Layouts
import miuix.Core

Item {
    id: control
    anchors.fill: parent
    property bool active: false
    property bool familyListOpen: false
    signal closed()
    onActiveChanged: {
        if (active) { searchField.text = ""; picker.open() }
        else {
            if (familyPicker.opened) familyPicker.close()
            if (picker.opened) picker.close()
        }
    }
    property var filtered: {
        var q = searchField.text.toLowerCase()
        var source = settings.availableFontFamilies || []
        var out = []
        for (var i = 0; i < source.length; i++)
            if (q === "" || source[i].toLowerCase().indexOf(q) >= 0) out.push(source[i])
        return out
    }
    Dialog {
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        id: picker
        title: i18n.t("font.picker.title")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onClosed: control.closed()
        ColumnLayout {
            width: parent.width
            spacing: 8
            SuperArrow {
                Layout.fillWidth: true
                title: i18n.t("font.picker.bundled")
                indicator: settings.fontFamily() === "" ? "check" : "chevron_right"
                onClicked: { settings.setFontSelection(""); picker.close() }
            }
            SuperArrow {
                Layout.fillWidth: true
                title: i18n.t("font.picker.system")
                indicator: settings.fontFamily() === "system" ? "check" : "chevron_right"
                onClicked: { settings.setFontSelection("system"); picker.close() }
            }
            SuperArrow {
                objectName: "openFontFamilyList"
                Layout.fillWidth: true
                title: i18n.t("font.picker.installed")
                summary: settings.fontFamily() !== "" && settings.fontFamily() !== "system"
                    ? settings.fontFamily() : i18n.t("font.picker.installed.summary")
                onClicked: { control.familyListOpen = true; familyPicker.open() }
            }
        }
    }
    Dialog {
        id: familyPicker
        objectName: "fontFamilyDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t("font.picker.installed")
        showAcceptButton: false
        rejectText: i18n.t("common.back")
        onClosed: control.familyListOpen = false
        ColumnLayout {
            width: parent.width
            spacing: 8
            TextField {
                id: searchField
                Layout.fillWidth: true
                label: i18n.t("font.picker.search")
                leadingIcon: "search"
                onTextChanged: listView.contentY = 0
            }
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(360, Math.max(88, control.height - 240))
                Flickable {
                    id: listView
                    objectName: "fontFamilyList"
                    anchors.fill: parent
                    clip: true
                    contentWidth: width
                    contentHeight: control.filtered.length * rowH
                    property int rowH: 52
                    property int liveRows: Math.ceil(height / rowH) + 7
                    onContentHeightChanged: contentY = Math.min(contentY, Math.max(0, contentHeight - height))
                    Item {
                        width: listView.width
                        height: listView.contentHeight
                        cachedLayout: true
                        Repeater {
                            model: control.familyListOpen ? control.filtered : null
                            windowStart: Math.max(0, Math.min(control.filtered.length - listView.liveRows, Math.floor(listView.contentY / listView.rowH) - 3))
                            windowCount: listView.liveRows
                            Item {
                                id: familyRow
                                objectName: "fontFamilyRow"
                                property string family: modelData
                                property bool selected: settings.fontFamily() === family
                                width: listView.width
                                height: listView.rowH - 4
                                y: index * listView.rowH
                                activeFocusOnTab: true
                                function choose() { settings.setFontSelection(family); familyPicker.close() }
                                Keys.onReturnPressed: { choose(); event.accepted = true }
                                Keys.onSpacePressed: { choose(); event.accepted = true }
                                Rectangle {
                                    objectName: "fontFamilyBackground"
                                    width: parent.width; height: parent.height; radius: 12
                                    color: familyRow.selected || familyRow.activeFocus
                                        ? Theme.color.secondaryContainer : "transparent"
                                }
                                Text {
                                    objectName: "fontFamilyLabel"
                                    x: 16
                                    y: (familyRow.height - height) / 2
                                    width: Math.max(0, parent.width - 64)
                                    height: implicitHeight
                                    text: familyRow.family
                                    horizontalAlignment: Text.AlignLeft
                                    elide: Text.ElideRight
                                    font.pixelSize: 16
                                    color: Theme.color.onSurfaceColor
                                }
                                Icon {
                                    x: parent.width - 40; y: (parent.height - height) / 2
                                    width: 24; height: 24
                                    name: "check"
                                    visible: familyRow.selected
                                    color: Theme.color.primary
                                }
                                Ripple {
                                    width: parent.width; height: parent.height; clipRadius: 12
                                    onClicked: familyRow.choose()
                                }
                            }
                        }
                    }
                }
                ScrollBar {
                    objectName: "fontFamilyScrollBar"
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.right: parent.right
                    target: listView
                }
            }
        }
    }
}
