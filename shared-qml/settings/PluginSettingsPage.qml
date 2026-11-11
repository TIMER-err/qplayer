import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."
import "../components"

// Host-owned settings surface for one plugin. No third-party QML exists anywhere:
// a declared settings contribution opens the shared PluginDialog, which QPlayer
// renders from the plugin's validated description.
//
// Layout: an accent-washed hero (icon tile, name, id · version, trust and role
// pills, description) followed by grouped cards with the same row chrome as the
// settings page. The route header's title fades in once the hero name scrolls away.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()

    required property string pluginId
    color: Ui.background

    property var pluginData: {
        var rows = player.sourcePlugins || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].id === page.pluginId) return rows[i]
        }
        return null
    }
    property var catalogData: {
        var rows = player.pluginCatalogEntries || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].id === page.pluginId) return rows[i]
        }
        return null
    }
    property var settingsContributions: {
        var out = []
        var rows = player.pluginUiContributions || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].pluginId === page.pluginId && rows[i].placement === "settings")
                out.push(rows[i])
        }
        return out
    }
    property string displayName: pluginData ? pluginData.name
                                 : (catalogData ? catalogData.name
                                    : i18n.t("plugin.page.fallbackName"))
    readonly property bool known: page.pluginData !== null || page.catalogData !== null
    readonly property bool installed: page.pluginData !== null
    readonly property bool pluginEnabled: page.pluginData ? page.pluginData.enabled : false
    readonly property bool pluginPrimary: page.pluginData ? page.pluginData.primary : false
    readonly property string versionText: page.pluginData ? page.pluginData.version
                                          : (page.catalogData ? page.catalogData.version : "")
    readonly property string descriptionText: page.catalogData ? page.catalogData.description : ""
    readonly property string permissionText: page.pluginData
        ? i18n.t("plugin.entry.permissions", page.pluginData.permissions.length > 0
                 ? page.pluginData.permissions : i18n.t("plugin.entry.noPermissions"))
        : ""

    readonly property real gutter: Ui.gutter(width)
    readonly property real columnWidth: Math.min(Ui.readableMax, Math.max(0, width - gutter * 2))
    readonly property real columnX: (width - columnWidth) / 2
    readonly property real tileSize: width < Ui.compactMax ? 64 : 80

    function togglePluginEnabled() {
        if (!page.pluginData) return
        pluginEnableSwitch.checked = !pluginEnableSwitch.checked
        player.setSourcePluginEnabled(page.pluginId, pluginEnableSwitch.checked)
    }

    MouseArea { anchors.fill: parent }

    Flickable {
        id: scroller
        objectName: "pluginSettingsScroller"
        anchors.fill: parent
        clip: true
        contentWidth: width
        contentHeight: (page.known ? cards.y + cards.implicitHeight : missingState.y + missingState.height)
                       + Ui.space8 + page.bottomContentInset

        // Accent wash behind the hero, running under the transparent route header.
        Rectangle {
            width: scroller.width
            height: hero.y + hero.height + Ui.space8
            gradient: Gradient {
                GradientStop { position: 0.0; color: Ui.accentAlpha(Ui.dark ? 0.26 : 0.14) }
                GradientStop { position: 0.7; color: Ui.accentAlpha(Ui.dark ? 0.06 : 0.03) }
                GradientStop { position: 1.0; color: Ui.surfaceAlpha(0) }
            }
        }

        // ---- hero ------------------------------------------------------------------
        Item {
            id: hero
            objectName: "pluginSettingsHero"
            x: page.columnX
            y: Ui.headerHeight + Ui.space2
            width: page.columnWidth
            height: page.known ? textColumn.y + textColumn.height : 0
            visible: page.known

            Rectangle {
                id: tile
                width: page.tileSize
                height: page.tileSize
                radius: Ui.radiusLg
                color: Ui.accentSoft
                Glyph {
                    x: (tile.width - width) / 2
                    y: (tile.height - height) / 2
                    size: Math.round(tile.width * 0.46)
                    name: "extension"
                    color: Ui.accent
                }
            }

            Text {
                id: heroTitle
                objectName: "pluginSettingsTitle"
                x: tile.width + Ui.space5
                y: Math.max(0, (tile.height - heroTitle.implicitHeight - metaText.implicitHeight - 4) / 2)
                width: Math.max(0, hero.width - x)
                text: page.displayName
                color: Ui.textPrimary
                elide: Text.ElideRight
                maximumLineCount: 2
                wrapMode: Text.Wrap
                font.pixelSize: page.width < Ui.compactMax ? Ui.textTitle1 : Ui.textDisplay
                font.weight: Ui.weightBold
            }
            Text {
                id: metaText
                x: heroTitle.x
                y: heroTitle.y + heroTitle.implicitHeight + 4
                width: heroTitle.width
                text: page.pluginId + (page.versionText.length > 0 ? " · " + page.versionText : "")
                color: Ui.textSecondary
                elide: Text.ElideRight
                maximumLineCount: 1
                font.pixelSize: Ui.textCallout
            }

            Item {
                id: textColumn
                y: Math.max(tile.height, metaText.y + metaText.implicitHeight) + Ui.space4
                width: hero.width
                height: permissions.visible ? permissions.y + permissions.implicitHeight
                        : (description.visible ? description.y + description.implicitHeight
                                               : badges.height)

                Row {
                    id: badges
                    height: page.installed ? 28 : 0
                    visible: page.installed
                    spacing: Ui.space2
                    Rectangle {
                        visible: page.installed
                        height: 28
                        width: signedRow.width + Ui.space6
                        radius: height / 2
                        color: page.pluginData && page.pluginData.signed ? Ui.accentSoft : Ui.surfaceSunken
                        Row {
                            id: signedRow
                            x: Ui.space3
                            y: (parent.height - height) / 2
                            height: 18
                            spacing: Ui.space1
                            Glyph {
                                size: 18
                                name: page.pluginData && page.pluginData.signed ? "verified" : "gpp_maybe"
                                color: page.pluginData && page.pluginData.signed ? Ui.accent : Ui.danger
                            }
                            Text {
                                y: (18 - implicitHeight) / 2
                                text: i18n.t(page.pluginData && page.pluginData.signed
                                             ? "plugin.page.signed" : "plugin.page.unsigned")
                                color: page.pluginData && page.pluginData.signed ? Ui.accent : Ui.textPrimary
                                font.pixelSize: Ui.textCaption
                                font.weight: Ui.weightBold
                            }
                        }
                    }
                    Rectangle {
                        visible: page.installed
                        height: 28
                        width: stateText.implicitWidth + Ui.space6
                        radius: height / 2
                        color: page.pluginPrimary ? Ui.accent : Ui.surfaceSunken
                        Text {
                            id: stateText
                            x: (parent.width - implicitWidth) / 2
                            y: (parent.height - implicitHeight) / 2
                            text: i18n.t(page.pluginPrimary ? "plugin.entry.primary"
                                         : (page.pluginEnabled ? "plugin.entry.enabled"
                                                               : "plugin.entry.disabled"))
                            color: page.pluginPrimary ? Ui.onAccent : Ui.textSecondary
                            font.pixelSize: Ui.textCaption
                            font.weight: Ui.weightBold
                        }
                    }
                }

                Text {
                    id: description
                    y: page.installed ? badges.height + Ui.space3 : 0
                    width: textColumn.width
                    visible: page.descriptionText.length > 0
                    text: page.descriptionText
                    color: Ui.textSecondary
                    wrapMode: Text.Wrap
                    font.pixelSize: Ui.textBody
                }
                Text {
                    id: permissions
                    y: description.visible ? description.y + description.implicitHeight + Ui.space2
                                           : (page.installed ? badges.height + Ui.space3 : 0)
                    width: textColumn.width
                    visible: page.permissionText.length > 0
                    text: page.permissionText
                    color: Ui.textTertiary
                    wrapMode: Text.Wrap
                    font.pixelSize: Ui.textCaption
                }
            }
        }

        // ---- cards ---------------------------------------------------------------
        ColumnLayout {
            id: cards
            x: page.columnX
            y: hero.y + hero.height + Ui.space6
            width: page.columnWidth
            visible: page.known
            spacing: Ui.space4

            // Install (catalog entry not yet installed).
            SettingCard {
                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                visible: !page.installed && page.catalogData !== null
                Item {
                    id: installRow
                    Layout.fillWidth: true
                    implicitHeight: installFrame.implicitHeight
                    SettingRowFrame {
                        id: installFrame
                        width: installRow.width
                        height: installRow.height
                        icon: "download"
                        iconColor: Ui.accent
                        title: i18n.t("plugin.page.install.title")
                        summary: i18n.t("plugin.page.install.desc")
                        clickable: false
                        controlWidth: installButton.width
                    }
                    Button {
                        id: installButton
                        objectName: "pluginInstallButton"
                        x: installRow.width - Ui.space4 - width
                        y: (installRow.height - height) / 2
                        type: "filled"
                        icon: "download"
                        cornerRadius: Ui.radiusFull
                        text: i18n.t(player.pluginInstallBusy ? "plugin.page.install.busy"
                                     : "plugin.page.install.button")
                        enabled: !player.pluginInstallBusy
                        onClicked: player.installCatalogPlugin(page.pluginId)
                    }
                }
            }

            // Enable + primary source.
            SettingCard {
                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                visible: page.installed
                Item {
                    id: enableRow
                    Layout.fillWidth: true
                    implicitHeight: enableFrame.implicitHeight
                    SettingRowFrame {
                        id: enableFrame
                        width: enableRow.width
                        height: enableRow.height
                        icon: "power_settings_new"
                        title: i18n.t("plugin.page.enable.title")
                        summary: i18n.t(page.pluginEnabled ? "plugin.page.enable.on"
                                                           : "plugin.page.enable.off")
                        controlWidth: pluginEnableSwitch.width
                        onClicked: page.togglePluginEnabled()
                    }
                    Switch {
                        id: pluginEnableSwitch
                        objectName: "pluginEnableSwitch"
                        x: enableRow.width - Ui.space4 - width
                        y: (enableRow.height - height) / 2
                        // Clicking assigns checked and therefore replaces its
                        // binding. Keep a separate host-state binding so this
                        // persistent page reloads the next plugin's own value
                        // instead of carrying the previous plugin's toggle across.
                        property bool storedChecked: page.pluginData ? page.pluginData.enabled : false
                        checked: storedChecked
                        onStoredCheckedChanged: checked = storedChecked
                        toggleOnClick: false
                        onClicked: page.togglePluginEnabled()
                    }
                }
                Item {
                    id: primaryRow
                    Layout.fillWidth: true
                    visible: page.pluginEnabled
                    implicitHeight: primaryFrame.implicitHeight
                    // A plain action row like the other rows on this page, not
                    // a tonal button that outgrew the row: tap anywhere to make
                    // this plugin the primary source; the current one shows a
                    // check instead.
                    SettingRowFrame {
                        id: primaryFrame
                        objectName: "pluginPrimaryRow"
                        width: primaryRow.width
                        height: primaryRow.height
                        icon: "star"
                        iconColor: page.pluginPrimary ? Ui.accent : Ui.textSecondary
                        title: i18n.t("plugin.entry.primary")
                        summary: page.pluginPrimary ? i18n.t("plugin.page.primary.current") : ""
                        separator: true
                        clickable: !page.pluginPrimary
                        rowEnabled: page.pluginPrimary || !player.pluginInstallBusy
                        value: page.pluginPrimary ? "" : i18n.t("plugin.page.primary.set")
                        valueColor: Ui.accent
                        indicator: page.pluginPrimary ? "check_circle" : "chevron_right"
                        indicatorColor: page.pluginPrimary ? Ui.accent : Ui.textTertiary
                        onClicked: player.setPrimarySourcePlugin(page.pluginId)
                    }
                }
            }

            // Update available.
            SettingCard {
                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                visible: page.installed && page.catalogData !== null && page.catalogData.updateAvailable
                Item {
                    id: updateRow
                    Layout.fillWidth: true
                    implicitHeight: updateFrame.implicitHeight
                    SettingRowFrame {
                        id: updateFrame
                        width: updateRow.width
                        height: updateRow.height
                        icon: "system_update_alt"
                        iconColor: Ui.accent
                        title: i18n.t("plugin.page.update.title",
                                      page.catalogData ? page.catalogData.version : "")
                        summary: i18n.t("plugin.page.update.desc")
                        clickable: false
                        controlWidth: updateButton.width
                    }
                    Button {
                        id: updateButton
                        x: updateRow.width - Ui.space4 - width
                        y: (updateRow.height - height) / 2
                        type: "filled"
                        icon: "update"
                        cornerRadius: Ui.radiusFull
                        text: i18n.t(player.pluginInstallBusy ? "plugin.page.install.busy"
                                     : "plugin.page.update.button")
                        enabled: !player.pluginInstallBusy
                        onClicked: player.installCatalogPlugin(page.pluginId)
                    }
                }
            }

            // Plugin-declared settings surfaces.
            SettingCard {
                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                visible: page.settingsContributions.length > 0
                Repeater {
                    model: page.settingsContributions
                    Item {
                        id: contributionRow
                        Layout.fillWidth: true
                        implicitHeight: contributionFrame.implicitHeight
                        SettingRowFrame {
                            id: contributionFrame
                            width: contributionRow.width
                            height: contributionRow.height
                            icon: "tune"
                            title: i18n.t("plugin.page.settings.title")
                            summary: modelData.id
                            separator: index > 0
                            value: i18n.t("plugin.page.settings.open")
                            indicator: "chevron_right"
                            onClicked: player.requestPluginUi(modelData.pluginId, modelData.id)
                        }
                    }
                }
            }

            // Remove.
            SettingCard {
                Layout.fillWidth: true
                Layout.preferredHeight: implicitHeight
                visible: page.installed
                Item {
                    id: removeRow
                    Layout.fillWidth: true
                    implicitHeight: removeFrame.implicitHeight
                    SettingRowFrame {
                        id: removeFrame
                        width: removeRow.width
                        height: removeRow.height
                        icon: "delete"
                        iconColor: Ui.danger
                        title: i18n.t("plugin.page.remove.title")
                        summary: i18n.t("plugin.page.remove.desc")
                        clickable: false
                        controlWidth: removeButton.width
                    }
                    Button {
                        id: removeButton
                        objectName: "pluginRemoveButton"
                        x: removeRow.width - Ui.space4 - width
                        y: (removeRow.height - height) / 2
                        type: "outlined"
                        contentColor: Ui.danger
                        text: i18n.t("plugin.page.remove.button")
                        enabled: !player.pluginInstallBusy
                        onClicked: player.requestSourcePluginRemoval(page.pluginId)
                    }
                }
            }
        }

        // ---- missing plugin -------------------------------------------------------
        Item {
            id: missingState
            objectName: "pluginSettingsMissing"
            visible: !page.known
            x: page.columnX
            y: Ui.headerHeight + Ui.space10
            width: page.columnWidth
            height: visible ? missingButton.y + missingButton.height : 0
            Glyph {
                x: (missingState.width - width) / 2
                size: 48
                name: "extension_off"
                color: Ui.textTertiary
            }
            Text {
                id: missingText
                y: 64
                width: missingState.width
                horizontalAlignment: Text.AlignHCenter
                text: i18n.t("plugin.page.missing")
                color: Ui.textSecondary
                wrapMode: Text.Wrap
                font.pixelSize: Ui.textHeadline
            }
            Button {
                id: missingButton
                x: (missingState.width - width) / 2
                y: missingText.y + missingText.implicitHeight + Ui.space4
                type: "filledTonal"
                text: i18n.t("common.back")
                onClicked: page.back()
            }
        }

        ViewportScrollBar {
            target: scroller
            bottomInset: page.bottomContentInset
        }
    }

    RouteHeader {
        width: page.width
        title: page.displayName
        titleOpacity: Math.max(0, Math.min(1, (scroller.contentY - hero.y - 20) / 40))
        elevated: scroller.contentY > hero.y + hero.height - Ui.headerHeight
        onBack: page.back()
    }
}
