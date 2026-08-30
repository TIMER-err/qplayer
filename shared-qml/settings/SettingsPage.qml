import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../dialogs"
import "../components"

// App settings overlay. Nothing here knows what a setting IS: the categories and
// the rows come from player-core's SettingsCatalog through the `settings` context
// global (SettingsCore), and each row is rendered by whichever Setting*Row
// component matches its declared type. Adding a setting is a catalog entry —
// no edit here, and none in either platform's host code.
//
// This also keeps the page well clear of the 64KB-per-QML-file constructor limit
// that forced the old hand-written version to be split across six files: the
// markup is now one Repeater plus one Component per row type.
Rectangle {
    id: page
    signal back()
    signal home()
    signal openDebugLog()
    color: Theme.color.surface

    property var categories: settings.categories()
    property var categoryTabModel: {
        var out = []
        for (var i = 0; i < page.categories.length; i++) out.push({ text: page.categories[i] })
        return out
    }

    // Category switching uses Main.qml's MD3 fade-through verbatim (fade out,
    // swap, fade back in while rising), so it reads the same as switching pages.
    property string currentCategory: page.categories.length > 0 ? page.categories[0] : ""
    property string nextCategory: page.currentCategory
    property real panelOpacity: 1
    property real panelShift: 0
    property var groups: settings.groups(page.currentCategory)
    // A desktop-width window fits two card columns; one card per row there left
    // most of the page empty sideways and very long vertically. Same 600px break
    // Main.qml uses to swap the bottom bar for the rail.
    property bool twoColumn: page.width >= 600

    // The two columns pack INDEPENDENTLY (each is its own ColumnLayout), rather
    // than sharing grid rows: a grid row is as tall as its tallest card, so a
    // short card next to a tall one left a hole under it. Cards are dealt out
    // greedily to whichever column is currently shorter, estimating a card's
    // height from its row count — close enough to keep the two columns even
    // without measuring anything, and stable (it doesn't depend on layout).
    property var leftGroups: page.column(0)
    property var rightGroups: page.column(1)

    function column(which) {
        var out = []
        if (!page.twoColumn) return which === 0 ? page.groups : out
        var load = [0, 0]
        for (var i = 0; i < page.groups.length; i++) {
            var g = page.groups[i]
            var target = load[0] <= load[1] ? 0 : 1
            load[target] += g.rows.length
            if (target === which) out.push(g)
        }
        return out
    }

    function selectCategory(name) {
        if (!name || name === page.currentCategory) return
        page.nextCategory = name
        categoryAnim.restart()
    }

    SequentialAnimation {
        id: categoryAnim
        NumberAnimation {
            target: page; property: "panelOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction {
            onTrigger: {
                page.currentCategory = page.nextCategory
                settingsFlickable.contentY = 0
                page.panelShift = 28
            }
        }
        ParallelAnimation {
            NumberAnimation {
                target: page; property: "panelOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: page; property: "panelShift"; from: 28; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // Tabs owns currentIndex (its Ripple writes it directly); mirror it into a
    // plain property so this page has a change handler to hang the transition off.
    property int tabIndex: categoryTabs.currentIndex
    onTabIndexChanged: page.selectCategory(page.categories[page.tabIndex])

    // Catch-all so taps on empty areas don't fall through to the page beneath.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            PageHeaderButtons {
                Layout.alignment: Qt.AlignVCenter
                onHome: page.home()
                onBack: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "设置"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                icon: "bug_report"
                onClicked: page.openDebugLog()
            }
        }

        Tabs {
            id: categoryTabs
            Layout.fillWidth: true
            Layout.preferredHeight: 48
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            type: "secondary"
            model: page.categoryTabModel
        }

        Flickable {
            id: settingsFlickable
            Layout.fillWidth: true
            Layout.fillHeight: true
            // Breathing room under the tab bar so the first card doesn't sit on
            // the indicator.
            Layout.topMargin: 16
            clip: true
            contentWidth: width
            contentHeight: groupsRow.implicitHeight + 24

            RowLayout {
                id: groupsRow
                x: 12
                width: settingsFlickable.width - 24
                y: page.panelShift
                opacity: page.panelOpacity
                spacing: 14

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignTop
                    spacing: 14

                    SettingCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "关于"
                                 && player.credentialOwnerOnlyFallback

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            SettingTitle { text: "登录凭据保护" }
                            Button {
                                type: "filledTonal"
                                icon: "enhanced_encryption"
                                enabled: !player.credentialProtectionBusy
                                text: player.credentialProtectionBusy
                                      ? "正在启用…" : "重新开启系统加密"
                                onClicked: player.reenableSystemCredentialProtection()
                            }
                        }
                        SettingDesc {
                            text: "当前使用仅本机用户可读的普通加密。重新开启后，登录凭据密钥将由系统密钥库保护。"
                        }
                    }

                    Repeater {
                        model: page.currentCategory === "插件" ? player.pluginUiContributions : null
                        delegate: SettingCard {
                            Layout.fillWidth: true
                            RowLayout {
                                Layout.fillWidth: true
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    SettingTitle { text: modelData.pluginName + " · 扩展界面" }
                                    SettingDesc { text: modelData.id + " · " + modelData.placement }
                                }
                                Button {
                                    type: "outlined"
                                    icon: "open_in_new"
                                    text: "打开"
                                    onClicked: player.requestPluginUi(modelData.pluginId, modelData.id)
                                }
                            }
                        }
                    }

                    SettingCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "插件"
                                 && (!player.sourcePlugins || player.sourcePlugins.length === 0)

                        SettingTitle { text: "尚未安装音源插件" }
                        SettingDesc {
                            text: "QPlayer 核心不提供在线音源。导入插件后，对应的搜索、首页、账户和扩展功能会出现在现有界面中。"
                        }
                    }

                    Repeater {
                        model: page.currentCategory === "插件" ? player.sourcePlugins : null
                        delegate: SettingCard {
                            Layout.fillWidth: true
                            property var pluginData: modelData

                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 10
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2
                                    SettingTitle {
                                        text: pluginData.name + "  " + pluginData.version
                                    }
                                    SettingDesc {
                                        text: pluginData.id + (pluginData.signed
                                              ? " · 已验证签名" : " · 未验证来源")
                                    }
                                }
                                Button {
                                    visible: pluginData.enabled
                                    type: pluginData.primary ? "filledTonal" : "outlined"
                                    text: pluginData.primary ? "主音源" : "设为主音源"
                                    enabled: !pluginData.primary
                                    onClicked: player.setPrimarySourcePlugin(pluginData.id)
                                }
                                Switch {
                                    checked: pluginData.enabled
                                    onClicked: player.setSourcePluginEnabled(pluginData.id, checked)
                                }
                                IconButton {
                                    icon: "delete"
                                    enabled: !player.pluginInstallBusy
                                    onClicked: player.requestSourcePluginRemoval(pluginData.id)
                                }
                            }
                            SettingDesc {
                                text: "权限：" + (pluginData.permissions.length > 0
                                      ? pluginData.permissions : "无额外权限")
                            }
                        }
                    }

                    PluginCatalogCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "插件" && !page.twoColumn
                    }

                    Repeater {
                        model: page.leftGroups
                        delegate: SettingCard {
                            Layout.fillWidth: true
                            property var groupData: modelData
                            Repeater {
                                model: groupData.rows
                                delegate: Loader {
                                    Layout.fillWidth: true

                                    // A row gated on another setting collapses when
                                    // its dependency is off.
                                    visible: modelData.dependsOn.length === 0
                                             || settings.value(modelData.dependsOn) === true

                                    // Read inside the loaded component, the same
                                    // way MD3 Menu's delegates reach their data.
                                    property var rowSpec: modelData

                                    sourceComponent: modelData.type === "switch" ? switchRow
                                                   : modelData.type === "stepper" ? stepperRow
                                                   : modelData.type === "slider" ? sliderRow
                                                   : modelData.type === "segmented" ? segmentedRow
                                                   : modelData.type === "radio" ? radioRow
                                                   : modelData.type === "dropdown" ? dropdownRow
                                                   : modelData.type === "text" ? textRow
                                                   : modelData.type === "path" ? pathRow
                                                   : actionRow
                                }
                            }
                        }
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignTop
                    spacing: 14
                    visible: page.twoColumn

                    PluginCatalogCard {
                        Layout.fillWidth: true
                        visible: page.currentCategory === "插件"
                    }

                    Repeater {
                        model: page.rightGroups
                        delegate: SettingCard {
                            Layout.fillWidth: true
                            property var groupData: modelData
                            Repeater {
                                model: groupData.rows
                                delegate: Loader {
                                    Layout.fillWidth: true
                                    visible: modelData.dependsOn.length === 0
                                             || settings.value(modelData.dependsOn) === true
                                    property var rowSpec: modelData
                                    sourceComponent: modelData.type === "switch" ? switchRow
                                                   : modelData.type === "stepper" ? stepperRow
                                                   : modelData.type === "slider" ? sliderRow
                                                   : modelData.type === "segmented" ? segmentedRow
                                                   : modelData.type === "radio" ? radioRow
                                                   : modelData.type === "dropdown" ? dropdownRow
                                                   : modelData.type === "text" ? textRow
                                                   : modelData.type === "path" ? pathRow
                                                   : actionRow
                                }
                            }
                        }
                    }
                }
            }

            // Components are Items and are NOT visible:false, so they'd take a
            // slot (plus spacing) inside a layout — keep them under a plain Item.
            Item {
                Component { id: switchRow; SettingSwitchRow { spec: rowSpec } }
                Component { id: stepperRow; SettingStepperRow { spec: rowSpec } }
                Component { id: sliderRow; SettingSliderRow { spec: rowSpec } }
                Component { id: segmentedRow; SettingSegmentedRow { spec: rowSpec } }
                Component { id: radioRow; SettingRadioRow { spec: rowSpec } }
                Component { id: dropdownRow; SettingDropdownRow { spec: rowSpec } }
                Component { id: textRow; SettingTextRow { spec: rowSpec } }
                Component { id: pathRow; SettingPathRow { spec: rowSpec } }
                Component { id: actionRow; SettingActionRow { spec: rowSpec } }
            }
        }
    }

    FontPickerDialog {
        active: settings.fontPickerOpen
        onClosed: settings.fontPickerOpen = false
    }

}
