import QtQuick
import miuix.Core

// Wide-screen navigation rail (left), shown in place of the bottom bar once the
// window is wide enough; collapses to width 0 (and hides) on compact widths so
// the content reclaims the full width. Expands to a labelled rail at ≥ 840.
//
// Split out of Main.qml: that file's single generated Component.<init>() was
// pushing past the JVM's 64KB method-size limit once both branches' additions
// landed in one merge (MethodTooLarge at test time) -- this is the single
// largest self-contained subtree, so it is the fix rather than a style choice.
Item {
    id: rail

    property var pageManager

    anchors.left: parent.left
    anchors.leftMargin: settings.leftInset
    anchors.top: parent.top
    anchors.bottom: parent.bottom
    visible: pageManager.wide
    width: pageManager.wide ? (pageManager.expanded ? 216 : 80) : 0
    Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
    Rectangle { anchors.fill: parent; color: Theme.color.surface }
    Item {
        id: railBrand
        width: parent.width
        height: implicitHeight
        // Desktop: the title bar already covers the topInset area, so the
        // header only needs topInset (items start at topInset + 12).
        // Mobile: 64px more for the logo.
        implicitHeight: (!hostWindow.available) ? (64 + settings.topInset) : settings.topInset

        Image {
            id: railLogo
            width: 32
            height: 32
            // topInset is the reserved system/custom-title-bar strip. Centre
            // the brand in the 64px rail header below it so native desktop
            // decorations cannot cover its top edge when topInset is 0.
            y: settings.topInset + (parent.height - settings.topInset - height) / 2
            x: rail.pageManager.expanded ? 24 : (parent.width - width) / 2
            Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            visible: (!hostWindow.available)
            source: "app-icon.png"
            // Decode straight to the drawn size in device pixels. Without this
            // the 256px source is resampled to 32 at draw time with plain
            // bilinear (SamplingMode.LINEAR), which at an 8:1 ratio aliases the
            // disc's grooves badly; sourceSize routes it through the loader's
            // mipmapped downscale instead. The artwork already carries its own
            // rounded corners, so no radius here — clipping them a second time
            // just re-aliases the edge.
            sourceSize.width: Math.round(32 * player.pixelRatio)
            sourceSize.height: Math.round(32 * player.pixelRatio)
        }
        Text {
            anchors.left: railLogo.right
            anchors.leftMargin: 12
            anchors.verticalCenter: railLogo.verticalCenter
            text: "QPlayer"
            opacity: (rail.pageManager.expanded && (!hostWindow.available)) ? 1 : 0
            visible: opacity > 0.01
            Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.titleMedium.family
            font.pixelSize: Theme.typography.titleMedium.size
        }
    }
    NavigationRail {
        anchors.top: railBrand.bottom
        anchors.bottom: railActions.top
        width: parent.width
        extended: rail.pageManager.expanded
        selectOnClick: false
        showToggle: false
        expandedWidth: 216
        currentIndex: rail.pageManager.page
        model: rail.pageManager.navItems
        sectionLabel: i18n.t("nav.section")
        onItemClicked: (index, itemData) => rail.pageManager.switchTo(index)
    }
    Item {
        id: railActions
        width: parent.width
        height: implicitHeight
        anchors.bottom: parent.bottom
        // Feature actions are contributed by plugins; the host only supplies
        // stable navigation placement and an isolated UI launcher.
        implicitHeight: 20 + rail.pageManager.footerActions().length * 48

        Rectangle {
            x: 12
            y: 0
            width: parent.width - 24
            height: 1
            color: Theme.color.outlineVariant
        }

        Repeater {
            model: rail.pageManager.footerActions()

            Item {
                id: footerAction
                x: 0
                y: 10 + index * 48
                width: parent.width
                height: 48

                Rectangle {
                    id: footerState
                    property color hoverColor: Theme.color.surfaceContainerHighest
                    x: rail.pageManager.expanded ? 12 : (parent.width - 48) / 2
                    y: 2
                    width: rail.pageManager.expanded ? parent.width - 24 : 48
                    height: 44
                    radius: 22
                    color: footerRipple.containsMouse
                           ? hoverColor
                           : Qt.rgba(hoverColor.r, hoverColor.g, hoverColor.b, 0)
                    Behavior on color { ColorAnimation { duration: 140 } }
                }

                Text {
                    x: rail.pageManager.expanded ? 28 : (parent.width - width) / 2
                    anchors.verticalCenter: parent.verticalCenter
                    text: modelData.icon
                    font.family: Theme.iconFont.name
                    font.pixelSize: 22
                    color: Theme.color.onSurfaceVariantColor
                    Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                }

                Text {
                    x: 64
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - 76
                    text: modelData.text
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.labelLarge.family
                    font.pixelSize: Theme.typography.labelLarge.size
                    elide: Text.ElideRight
                    opacity: rail.pageManager.expanded ? 1 : 0
                    visible: opacity > 0.01
                    Behavior on opacity { NumberAnimation { duration: 160 } }
                }

                Ripple {
                    id: footerRipple
                    x: footerState.x
                    y: footerState.y
                    width: footerState.width
                    height: footerState.height
                    clipRadius: footerState.radius
                    rippleColor: Theme.color.onSurfaceColor
                    onClicked: {
                        if (modelData.action === "download") {
                            player.refreshCachedSongs()
                            rail.pageManager.replacePage("cachedSongs", 0)
                        } else if (modelData.action === "plugin") {
                            player.requestPluginUi(modelData.pluginId,
                                                   modelData.contributionId)
                        } else if (modelData.action === "account") {
                            // Also when signed out: the account page lists one
                            // row per installed source and signing in lives
                            // behind each row's gear, so gating this on
                            // loggedIn would make that unreachable. With no
                            // source installed at all the page is covered by
                            // SourceSetupPrompt, which is the right answer
                            // there anyway.
                            rail.pageManager.replacePage("account", 0)
                        } else {
                            rail.pageManager.replacePage("settings", 0)
                        }
                    }
                }
            }
        }
    }
}
