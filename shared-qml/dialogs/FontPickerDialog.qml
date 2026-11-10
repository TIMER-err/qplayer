import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "../components"
import "../settings"

// Font picker: one grouped list (follow app font / bundled / system / import /
// installed families) with the current choice checked, and a second dialog with
// a searchable, windowed list of installed families.
Item {
    id: control
    anchors.fill: parent
    property bool active: false
    property bool familyListOpen: false
    // Which font source is being edited: empty is the app-wide font, anything
    // else a secondary one (desktop lyrics). A secondary source additionally
    // offers "follow the app font", which is what its empty value means — so the
    // bundled face needs an explicit name there (Fonts.BUNDLED).
    property string targetKey: ""
    property bool secondary: control.targetKey.length > 0
    property string bundledValue: control.secondary ? "bundled" : ""
    property string current: settings.fontFamilyFor(control.targetKey)
    readonly property bool customFamily: control.current !== "" && control.current !== "system"
                                         && control.current !== control.bundledValue
    function choose(value) { settings.setFontSelectionFor(control.targetKey, value) }
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
        id: picker
        objectName: "fontPickerDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        icon: "text_fields"
        title: i18n.t("font.picker.title")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onClosed: control.closed()

        SettingCard {
            width: picker.contentWidth
            height: implicitHeight

            Item {
                id: followRow
                Layout.fillWidth: true
                visible: control.secondary
                implicitHeight: followFrame.implicitHeight
                SettingRowFrame {
                    id: followFrame
                    width: followRow.width
                    height: followRow.height
                    icon: "link"
                    title: i18n.t("font.picker.followMain")
                    indicator: control.current === "" ? "check" : ""
                    indicatorColor: Ui.accent
                    onClicked: { control.choose(""); picker.close() }
                }
            }
            Item {
                id: bundledRow
                Layout.fillWidth: true
                implicitHeight: bundledFrame.implicitHeight
                SettingRowFrame {
                    id: bundledFrame
                    width: bundledRow.width
                    height: bundledRow.height
                    icon: "font_download"
                    title: i18n.t("font.picker.bundled")
                    separator: control.secondary
                    indicator: control.current === control.bundledValue ? "check" : ""
                    indicatorColor: Ui.accent
                    onClicked: { control.choose(control.bundledValue); picker.close() }
                }
            }
            Item {
                id: systemRow
                Layout.fillWidth: true
                implicitHeight: systemFrame.implicitHeight
                SettingRowFrame {
                    id: systemFrame
                    width: systemRow.width
                    height: systemRow.height
                    icon: "computer"
                    title: i18n.t("font.picker.system")
                    separator: true
                    indicator: control.current === "system" ? "check" : ""
                    indicatorColor: Ui.accent
                    onClicked: { control.choose("system"); picker.close() }
                }
            }
            Item {
                id: installedRow
                objectName: "openFontFamilyList"
                Layout.fillWidth: true
                implicitHeight: installedFrame.implicitHeight
                SettingRowFrame {
                    id: installedFrame
                    width: installedRow.width
                    height: installedRow.height
                    icon: "format_list_bulleted"
                    iconColor: control.customFamily ? Ui.accent : Ui.textSecondary
                    title: i18n.t("font.picker.installed")
                    summary: control.customFamily ? control.current
                                                  : i18n.t("font.picker.installed.summary")
                    separator: true
                    indicator: "chevron_right"
                    onClicked: { control.familyListOpen = true; familyPicker.open() }
                }
            }
            Item {
                id: importRow
                objectName: "importFontFile"
                Layout.fillWidth: true
                implicitHeight: importFrame.implicitHeight
                SettingRowFrame {
                    id: importFrame
                    width: importRow.width
                    height: importRow.height
                    icon: "upload_file"
                    title: i18n.t("font.picker.import")
                    summary: i18n.t("font.picker.import.summary")
                    separator: true
                    indicator: "chevron_right"
                    onClicked: { settings.openFontImport(); picker.close() }
                }
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
            width: familyPicker.contentWidth
            spacing: Ui.space3
            TextField {
                id: searchField
                overlayField: true
                Layout.fillWidth: true
                label: i18n.t("font.picker.search")
                leadingIcon: "search"
                onTextChanged: listView.contentY = 0
            }
            Item {
                Layout.fillWidth: true
                // Keep the dialog body itself below its scroll threshold. The
                // virtual font list is the sole vertical scroll owner.
                Layout.preferredHeight: Math.min(260, Math.max(88, control.height - 240))
                Rectangle {
                    anchors.fill: parent
                    radius: Ui.radiusLg
                    color: Ui.surfaceRaised
                }
                Flickable {
                    id: listView
                    objectName: "fontFamilyList"
                    x: 4
                    y: 4
                    width: Math.max(0, parent.width - 8)
                    height: Math.max(0, parent.height - 8)
                    clip: true
                    contentWidth: width
                    contentHeight: control.filtered.length * rowH
                    property int rowH: 48
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
                                property bool selected: control.current === family
                                width: listView.width
                                height: listView.rowH
                                y: index * listView.rowH
                                activeFocusOnTab: true
                                function choose() { control.choose(family); familyPicker.close() }
                                Keys.onReturnPressed: { choose(); event.accepted = true }
                                Keys.onSpacePressed: { choose(); event.accepted = true }
                                Rectangle {
                                    objectName: "fontFamilyBackground"
                                    y: 2
                                    width: parent.width
                                    height: parent.height - 4
                                    radius: Ui.radiusMd
                                    color: familyRow.selected ? Ui.accentSoft
                                           : (familyRow.activeFocus || familyArea.containsMouse
                                              ? Ui.surfaceHover : "transparent")
                                }
                                Text {
                                    objectName: "fontFamilyLabel"
                                    x: Ui.space3
                                    y: (familyRow.height - implicitHeight) / 2
                                    width: Math.max(0, parent.width - 56)
                                    text: familyRow.family
                                    elide: Text.ElideRight
                                    maximumLineCount: 1
                                    font.pixelSize: Ui.textBody
                                    font.weight: familyRow.selected ? Ui.weightBold : Ui.weightRegular
                                    color: familyRow.selected ? Ui.accent : Ui.textPrimary
                                }
                                Glyph {
                                    x: parent.width - Ui.space3 - width
                                    y: (parent.height - height) / 2
                                    size: 20
                                    name: "check"
                                    visible: familyRow.selected
                                    color: Ui.accent
                                }
                                MouseArea {
                                    id: familyArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
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
