import QtQuick
import QtQuick.Layouts
import miuix.Core
import "."
import "../components"
import "../dialogs"

// Account overlay: signed-in user's profile (avatar / nickname / VIP + level
// badges / signature), header stats (playlist + liked counts) and a logout
// action. Opened from the top-bar account icon when player.loggedIn. Follows
// SettingsPage's overlay pattern -- a plain rounded-rect section layout, since
// md3 Card is fixed-size.
Rectangle {
    id: page
    signal back()
    signal home()
    signal requestLogin()
    color: Theme.color.surface

    // The source whose gear was tapped. Sign-in and sign-out act on this one
    // alone, so the two sources never share a setting.
    property var menuRow: null

    // Swallow taps on empty areas so they don't fall through to the page beneath.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // --- top bar -----------------------------------------------------
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
                text: i18n.t("account.title")
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
            }
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            contentHeight: content.implicitHeight + 24

            ColumnLayout {
                id: content
                width: Math.min(904, parent.width)
                x: (parent.width - width) / 2
                spacing: 16

                // --- profile header ------------------------------------
                // The primary source's signed-in user. Hidden when there is no
                // session to describe -- an empty avatar over "匿名 / Lv.0" says
                // nothing the signed-out row below doesn't already say better.
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    Layout.topMargin: 6
                    visible: player.loggedIn
                    radius: 20
                    color: Theme.color.surfaceContainer
                    implicitHeight: profileRow.implicitHeight + 32

                    RowLayout {
                        id: profileRow
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 16

                        // Avatar: circular Image over a glyph placeholder.
                        Item {
                            Layout.preferredWidth: 72
                            Layout.preferredHeight: 72
                            Rectangle {
                                anchors.fill: parent
                                radius: width / 2
                                color: Theme.color.surfaceContainerHigh
                                Text {
                                    anchors.centerIn: parent
                                    text: "account_circle"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 44
                                    color: Theme.color.onSurfaceVariantColor
                                }
                            }
                            Image {
                                anchors.fill: parent
                                source: player.userAvatar
                                radius: width / 2
                                fillMode: "PreserveAspectCrop"
                                visible: player.userAvatar.length > 0
                                // See CoverImage.qml: decode-time downscale
                                // (mipmap-quality) instead of a plain bilinear
                                // draw-time scale, which aliases into moiré,
                                // scaled by the device pixel ratio so it decodes
                                // at display resolution instead of 1x.
                                sourceSize.width: Math.round(72 * player.pixelRatio)
                                sourceSize.height: Math.round(72 * player.pixelRatio)
                            }
                        }

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 6

                            Text {
                                Layout.fillWidth: true
                                text: player.userName.length > 0 ? player.userName : i18n.t("account.anonymous")
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.titleMedium.family
                                font.pixelSize: Theme.typography.titleMedium.size
                                elide: Text.ElideRight
                            }

                            // Badge row: VIP pill (only when vipType > 0) + level pill.
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 6

                                Rectangle {
                                    visible: player.userVipType > 0
                                    radius: 999
                                    color: Theme.color.tertiaryContainer
                                    implicitWidth: vipText.implicitWidth + 16
                                    implicitHeight: vipText.implicitHeight + 8
                                    Text {
                                        id: vipText
                                        anchors.centerIn: parent
                                        text: i18n.t("account.vip")
                                        color: Theme.color.onTertiaryContainerColor
                                        font.family: Theme.typography.labelMedium.family
                                        font.pixelSize: Theme.typography.labelMedium.size
                                    }
                                }
                                Rectangle {
                                    radius: 999
                                    color: Theme.color.secondaryContainer
                                    implicitWidth: lvlText.implicitWidth + 16
                                    implicitHeight: lvlText.implicitHeight + 8
                                    Text {
                                        id: lvlText
                                        anchors.centerIn: parent
                                        text: "Lv." + player.userLevel
                                        color: Theme.color.onSecondaryContainerColor
                                        font.family: Theme.typography.labelMedium.family
                                        font.pixelSize: Theme.typography.labelMedium.size
                                    }
                                }
                                Item { Layout.fillWidth: true }
                            }

                            Text {
                                Layout.fillWidth: true
                                visible: player.userSignature.length > 0
                                text: player.userSignature
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                                wrapMode: Text.WordWrap
                            }
                        }
                    }
                }

                // --- every source's account ----------------------------
                // One row per installed source that can report an account,
                // signed in or not -- signing in and out both live behind the
                // row's own gear, so a source must be listed before it can be
                // signed into. Primary sorts first, which is the order the
                // controller publishes.
                Text {
                    Layout.fillWidth: true
                    Layout.leftMargin: 16
                    visible: (player.sourceAccounts || []).length > 0
                    text: i18n.t("account.sources")
                    color: Theme.color.primary
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                    font.weight: Font.DemiBold
                }

                Repeater {
                    model: player.sourceAccounts || null
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        Layout.leftMargin: 12
                        Layout.rightMargin: 12
                        radius: 20
                        color: Theme.color.surfaceContainer
                        implicitHeight: 76

                        // Kept anchor-based and flat: nested Layouts here stopped
                        // propagating fillWidth and the labels lost their wrapping.
                        Item {
                            id: sourceAvatar
                            anchors.left: parent.left
                            anchors.leftMargin: 16
                            anchors.verticalCenter: parent.verticalCenter
                            width: 44
                            height: 44
                            Rectangle {
                                anchors.fill: parent
                                radius: width / 2
                                color: Theme.color.surfaceContainerHigh
                                Text {
                                    anchors.centerIn: parent
                                    text: "account_circle"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 28
                                    color: Theme.color.onSurfaceVariantColor
                                }
                            }
                            Image {
                                anchors.fill: parent
                                source: modelData.avatarUrl
                                radius: width / 2
                                fillMode: "PreserveAspectCrop"
                                visible: modelData.avatarUrl.length > 0
                                // Same decode-time downscale as the header avatar
                                // above, so it doesn't alias into moiré.
                                sourceSize.width: Math.round(44 * player.pixelRatio)
                                sourceSize.height: Math.round(44 * player.pixelRatio)
                            }
                        }

                        Text {
                            id: sourceWho
                            anchors.left: sourceAvatar.right
                            anchors.leftMargin: 14
                            anchors.right: primaryBadge.visible ? primaryBadge.left : sourceGear.left
                            anchors.rightMargin: 8
                            anchors.top: parent.top
                            anchors.topMargin: 16
                            elide: Text.ElideRight
                            text: modelData.loggedIn
                                  ? (modelData.displayName.length > 0
                                     ? modelData.displayName : i18n.t("account.anonymous"))
                                  : i18n.t("account.source.signedOut")
                            color: modelData.loggedIn ? Theme.color.onSurfaceColor
                                                      : Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.titleSmall.family
                            font.pixelSize: Theme.typography.titleSmall.size
                        }

                        Text {
                            anchors.left: sourceWho.left
                            anchors.right: sourceWho.right
                            anchors.top: sourceWho.bottom
                            anchors.topMargin: 2
                            elide: Text.ElideRight
                            text: modelData.sourceName
                            color: Theme.color.onSurfaceVariantColor
                            font.family: Theme.typography.bodySmall.family
                            font.pixelSize: Theme.typography.bodySmall.size
                        }

                        IconButton {
                            id: sourceGear
                            anchors.right: parent.right
                            anchors.rightMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            icon: "settings"
                            onClicked: {
                                page.menuRow = modelData;
                                sourceMenu.open();
                            }
                        }

                        Rectangle {
                            id: primaryBadge
                            anchors.right: sourceGear.left
                            anchors.rightMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.primary
                            width: primaryLabel.implicitWidth + 16
                            height: 24
                            radius: 12
                            color: Theme.color.primary
                            Text {
                                id: primaryLabel
                                anchors.centerIn: parent
                                text: i18n.t("account.source.primary")
                                color: Theme.color.onPrimaryColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                        }
                    }
                }

                // --- stats ---------------------------------------------
                // Counts belong to the primary source's session, so they hide
                // with the header rather than reading a confident "0 / 0".
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    visible: player.loggedIn
                    radius: 20
                    color: Theme.color.surfaceContainer
                    implicitHeight: statsRow.implicitHeight + 32

                    RowLayout {
                        id: statsRow
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 0

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text {
                                Layout.alignment: Qt.AlignHCenter
                                text: "" + player.playlistCount
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.titleLarge.family
                                font.pixelSize: Theme.typography.titleLarge.size
                            }
                            Text {
                                Layout.alignment: Qt.AlignHCenter
                                text: i18n.t("account.playlists")
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                        }
                        Rectangle {
                            Layout.preferredWidth: 1
                            Layout.preferredHeight: 32
                            color: Theme.color.outlineVariant
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text {
                                Layout.alignment: Qt.AlignHCenter
                                text: "" + player.likedCount
                                color: Theme.color.onSurfaceColor
                                font.family: Theme.typography.titleLarge.family
                                font.pixelSize: Theme.typography.titleLarge.size
                            }
                            Text {
                                Layout.alignment: Qt.AlignHCenter
                                text: i18n.t("account.liked")
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                        }
                    }
                }

                // Every source is listed now, so signing in and out belongs to
                // each row's own gear -- a page-level button could only ever mean
                // one of them, and would be a second way to do what the single
                // row's gear already does.
                Item { Layout.preferredHeight: 8 }
            }
        }
    }

    // Per-source settings. Sign-in aims the shared login surface at this source
    // first, so the existing login dialog can serve a source that is not primary.
    SourceAccountDialog {
        id: sourceMenu
        row: page.menuRow
        onRequestLogin: {
            if (page.menuRow && player.beginSourceLogin(page.menuRow.providerId)) {
                page.requestLogin();
            }
        }
        onRequestLogout: if (page.menuRow) sourceLogoutDialog.open()
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: sourceLogoutDialog
        title: i18n.t("account.logout")
        text: page.menuRow
              ? i18n.t("account.source.logout.confirm", page.menuRow.sourceName) : ""
        icon: "logout"
        acceptText: i18n.t("account.logout.accept")
        rejectText: i18n.t("common.cancel")
        onAccepted: if (page.menuRow) player.logoutSource(page.menuRow.providerId)
    }

}
