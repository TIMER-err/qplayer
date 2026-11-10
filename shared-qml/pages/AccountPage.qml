import QtQuick
import QtQuick.Layouts
import miuix.Core
import design
import "."
import "../components"
import "../dialogs"
import "../settings"

// Account route: an accent-washed profile hero (avatar, name, VIP / level pills,
// signature, playlist + liked counts) above one row per source account. Each
// row signs in directly when signed out; a signed-in row opens that source's
// own account sheet (re-login / sign out), so the sources never share a setting.
Rectangle {
    id: page
    property real bottomContentInset: 0
    signal back()
    signal requestLogin()
    color: Ui.background

    // The source whose row was tapped. Sign-in and sign-out act on this one alone.
    property var menuRow: null
    property var accounts: player.sourceAccounts || []

    readonly property bool compact: width < Ui.compactMax
    readonly property real gutter: Ui.gutter(width)
    readonly property real columnWidth: Math.min(Ui.readableMax, Math.max(0, width - gutter * 2))
    readonly property real columnX: (width - columnWidth) / 2
    readonly property real avatarSize: compact ? 88 : 112

    function beginLogin(row) {
        page.menuRow = row
        if (row && player.beginSourceLogin(row.providerId)) page.requestLogin()
    }
    function openSourceSheet(row) {
        page.menuRow = row
        sourceMenu.open()
    }

    // Swallow taps on empty areas so they don't fall through to the page beneath.
    MouseArea { anchors.fill: parent }

    Flickable {
        id: accountScroller
        objectName: "accountScroller"
        anchors.fill: parent
        clip: true
        contentWidth: width
        contentHeight: sources.y + sources.height + Ui.space8 + page.bottomContentInset

        // Accent wash behind the hero, running under the transparent route header.
        Rectangle {
            width: accountScroller.width
            height: hero.y + hero.height + Ui.space10
            gradient: Gradient {
                GradientStop { position: 0.0; color: Ui.accentAlpha(Ui.dark ? 0.30 : 0.16) }
                GradientStop { position: 0.7; color: Ui.accentAlpha(Ui.dark ? 0.07 : 0.04) }
                GradientStop { position: 1.0; color: Ui.surfaceAlpha(0) }
            }
        }

        // ---- profile hero ------------------------------------------------------------
        Item {
            id: hero
            objectName: "accountHero"
            x: page.columnX
            y: Ui.headerHeight + Ui.space2
            width: page.columnWidth
            height: page.compact ? info.y + info.height
                                 : Math.max(avatar.height, info.y + info.height)

            Item {
                id: avatar
                objectName: "accountAvatar"
                width: page.avatarSize
                height: page.avatarSize
                x: page.compact ? (hero.width - width) / 2 : 0
                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: Ui.accentSoft
                    Glyph {
                        x: (parent.width - width) / 2
                        y: (parent.height - height) / 2
                        size: Math.round(page.avatarSize * 0.5)
                        name: "person"
                        color: Ui.accent
                    }
                }
                Image {
                    anchors.fill: parent
                    source: player.loggedIn ? player.userAvatar : ""
                    radius: width / 2
                    fillMode: "PreserveAspectCrop"
                    visible: player.loggedIn && player.userAvatar.length > 0
                    // Decode-time downscale at display resolution (see CoverImage).
                    sourceSize.width: Math.round(112 * player.pixelRatio)
                    sourceSize.height: Math.round(112 * player.pixelRatio)
                }
            }

            Item {
                id: info
                x: page.compact ? 0 : avatar.width + Ui.space6
                y: page.compact ? avatar.height + Ui.space4
                                : Math.max(0, (avatar.height - infoHeight) / 2)
                width: Math.max(0, hero.width - x)
                height: infoHeight
                readonly property real infoHeight: nameText.implicitHeight
                    + (pills.visible ? Ui.space2 + pills.height : 0)
                    + (signature.visible ? Ui.space2 + signature.implicitHeight : 0)
                    + (stats.visible ? Ui.space4 + stats.height : 0)

                Text {
                    id: nameText
                    objectName: "accountName"
                    width: info.width
                    horizontalAlignment: page.compact ? Text.AlignHCenter : Text.AlignLeft
                    text: player.loggedIn
                          ? (player.userName.length > 0 ? player.userName : i18n.t("account.anonymous"))
                          : i18n.t("account.source.signedOut")
                    color: Ui.textPrimary
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    font.pixelSize: page.compact ? Ui.textTitle1 : Ui.textDisplay
                    font.weight: Ui.weightBold
                }

                // VIP pill (only when vipType > 0) + level pill.
                Row {
                    id: pills
                    visible: player.loggedIn
                    x: page.compact ? (info.width - width) / 2 : 0
                    y: nameText.implicitHeight + Ui.space2
                    height: 26
                    spacing: Ui.space2
                    Rectangle {
                        visible: player.userVipType > 0
                        height: 26
                        width: vipRow.width + Ui.space5
                        radius: height / 2
                        color: Ui.accent
                        Row {
                            id: vipRow
                            x: Ui.space2 + 2
                            y: (parent.height - height) / 2
                            height: 16
                            spacing: Ui.space1
                            Glyph { size: 16; name: "workspace_premium"; color: Ui.onAccent }
                            Text {
                                y: (16 - implicitHeight) / 2
                                text: i18n.t("account.vip")
                                color: Ui.onAccent
                                font.pixelSize: Ui.textCaption
                                font.weight: Ui.weightBold
                            }
                        }
                    }
                    Rectangle {
                        height: 26
                        width: levelText.implicitWidth + Ui.space5
                        radius: height / 2
                        color: Ui.accentSoft
                        Text {
                            id: levelText
                            x: (parent.width - implicitWidth) / 2
                            y: (parent.height - implicitHeight) / 2
                            text: "Lv." + player.userLevel
                            color: Ui.accent
                            font.pixelSize: Ui.textCaption
                            font.weight: Ui.weightBold
                        }
                    }
                }

                Text {
                    id: signature
                    y: pills.visible ? pills.y + pills.height + Ui.space2
                                     : nameText.implicitHeight + Ui.space2
                    width: info.width
                    visible: player.loggedIn ? player.userSignature.length > 0 : true
                    horizontalAlignment: page.compact ? Text.AlignHCenter : Text.AlignLeft
                    text: player.loggedIn ? player.userSignature : i18n.t("account.hero.signedOutHint")
                    color: Ui.textSecondary
                    wrapMode: Text.Wrap
                    maximumLineCount: 3
                    elide: Text.ElideRight
                    font.pixelSize: Ui.textBody
                }

                // Counts aggregate the sources' playlists and liked songs; they hide
                // with the profile rather than reading a confident "0 / 0".
                Row {
                    id: stats
                    objectName: "accountStats"
                    visible: player.loggedIn
                    x: page.compact ? (info.width - width) / 2 : 0
                    y: signature.visible ? signature.y + signature.implicitHeight + Ui.space4
                                         : (pills.visible ? pills.y + pills.height + Ui.space4
                                                          : nameText.implicitHeight + Ui.space4)
                    height: 48
                    spacing: Ui.space8
                    Item {
                        width: Math.max(playlistCount.implicitWidth, playlistLabel.implicitWidth)
                        height: 48
                        Text {
                            id: playlistCount
                            x: page.compact ? (parent.width - implicitWidth) / 2 : 0
                            text: "" + player.playlistCount
                            color: Ui.textPrimary
                            font.pixelSize: Ui.textTitle1
                            font.weight: Ui.weightBold
                        }
                        Text {
                            id: playlistLabel
                            x: page.compact ? (parent.width - implicitWidth) / 2 : 0
                            y: playlistCount.implicitHeight
                            text: i18n.t("account.playlists")
                            color: Ui.textSecondary
                            font.pixelSize: Ui.textCaption
                        }
                    }
                    Rectangle {
                        y: 8
                        width: 1
                        height: 32
                        color: Ui.hairline
                    }
                    Item {
                        width: Math.max(likedCount.implicitWidth, likedLabel.implicitWidth)
                        height: 48
                        Text {
                            id: likedCount
                            x: page.compact ? (parent.width - implicitWidth) / 2 : 0
                            text: "" + player.likedCount
                            color: Ui.textPrimary
                            font.pixelSize: Ui.textTitle1
                            font.weight: Ui.weightBold
                        }
                        Text {
                            id: likedLabel
                            x: page.compact ? (parent.width - implicitWidth) / 2 : 0
                            y: likedCount.implicitHeight
                            text: i18n.t("account.liked")
                            color: Ui.textSecondary
                            font.pixelSize: Ui.textCaption
                        }
                    }
                }
            }
        }

        // ---- every source's account ---------------------------------------------------
        // One row per installed source that can report an account, signed in or
        // not. Primary sorts first, which is the order the controller publishes.
        Item {
            id: sources
            objectName: "accountSources"
            x: page.columnX
            y: hero.y + hero.height + Ui.space8
            width: page.columnWidth
            height: sourcesHeader.height + Ui.space2 + sourcesCard.height

            SectionHeader {
                id: sourcesHeader
                width: sources.width
                title: i18n.t("account.sources")
            }

            SettingCard {
                id: sourcesCard
                y: sourcesHeader.height + Ui.space2
                width: sources.width
                height: implicitHeight

                Repeater {
                    model: page.accounts
                    Item {
                        id: sourceRow
                        objectName: "accountSourceRow"
                        Layout.fillWidth: true
                        implicitHeight: Math.max(68, sourceFrame.implicitHeight)
                        readonly property bool signedIn: modelData.loggedIn
                        readonly property real actionWidth: signedIn ? manageButton.width : loginButton.width

                        SettingRowFrame {
                            id: sourceFrame
                            width: sourceRow.width
                            height: sourceRow.height
                            leadingWidth: 44
                            separator: index > 0
                            title: modelData.accountError
                                   ? i18n.t("account.source.unavailable")
                                   : (modelData.loggedIn
                                      ? (modelData.displayName.length > 0
                                         ? modelData.displayName : i18n.t("account.anonymous"))
                                      : i18n.t("account.source.signedOut"))
                            summary: modelData.sourceName
                            controlWidth: sourceRow.actionWidth
                                          + (modelData.primary ? primaryPill.width + Ui.space2 : 0)
                            onClicked: page.openSourceSheet(modelData)
                        }

                        Item {
                            x: Ui.space4
                            y: (sourceRow.height - height) / 2
                            width: 44
                            height: 44
                            Rectangle {
                                anchors.fill: parent
                                radius: width / 2
                                color: modelData.loggedIn ? Ui.accentSoft : Ui.surfaceSunken
                                Glyph {
                                    x: (parent.width - width) / 2
                                    y: (parent.height - height) / 2
                                    size: 24
                                    name: "person"
                                    color: modelData.loggedIn ? Ui.accent : Ui.textTertiary
                                }
                            }
                            Image {
                                anchors.fill: parent
                                source: modelData.avatarUrl
                                radius: width / 2
                                fillMode: "PreserveAspectCrop"
                                visible: modelData.avatarUrl.length > 0
                                sourceSize.width: Math.round(44 * player.pixelRatio)
                                sourceSize.height: Math.round(44 * player.pixelRatio)
                            }
                        }

                        Rectangle {
                            id: primaryPill
                            visible: modelData.primary
                            x: sourceRow.width - Ui.space4 - sourceRow.actionWidth - Ui.space2 - width
                            y: (sourceRow.height - height) / 2
                            width: primaryLabel.implicitWidth + Ui.space4
                            height: 24
                            radius: height / 2
                            color: Ui.accentSoft
                            Text {
                                id: primaryLabel
                                x: (parent.width - implicitWidth) / 2
                                y: (parent.height - implicitHeight) / 2
                                text: i18n.t("account.source.primary")
                                color: Ui.accent
                                font.pixelSize: Ui.textMicro
                                font.weight: Ui.weightBold
                            }
                        }

                        Button {
                            id: loginButton
                            objectName: "accountSourceLogin"
                            visible: !sourceRow.signedIn
                            x: sourceRow.width - Ui.space4 - width
                            y: (sourceRow.height - height) / 2
                            type: "filledTonal"
                            icon: "login"
                            cornerRadius: Ui.radiusFull
                            text: i18n.t("account.source.login")
                            onClicked: page.beginLogin(modelData)
                        }
                        IconButton {
                            id: manageButton
                            objectName: "accountSourceManage"
                            visible: sourceRow.signedIn
                            x: sourceRow.width - Ui.space4 + 8 - width
                            y: (sourceRow.height - height) / 2
                            type: "standard"
                            icon: "more_horiz"
                            onClicked: page.openSourceSheet(modelData)
                        }
                    }
                }

                // No source plugin reports an account yet.
                Item {
                    id: noSourcesRow
                    Layout.fillWidth: true
                    visible: page.accounts.length === 0
                    implicitHeight: noSourcesFrame.implicitHeight
                    SettingRowFrame {
                        id: noSourcesFrame
                        width: noSourcesRow.width
                        height: noSourcesRow.height
                        icon: "extension_off"
                        title: i18n.t("plugin.none.title")
                        summary: i18n.t("account.sources.empty")
                        clickable: false
                    }
                }
            }
        }

        ViewportScrollBar {
            target: accountScroller
            bottomInset: page.bottomContentInset
        }
    }

    RouteHeader {
        width: page.width
        title: i18n.t("account.title")
        titleOpacity: Math.max(0, Math.min(1, (accountScroller.contentY - hero.y - info.y) / 40))
        elevated: accountScroller.contentY > hero.y + hero.height - Ui.headerHeight
        onBack: page.back()
    }

    // Per-source sheet. Sign-in aims the shared login surface at this source
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
        id: sourceLogoutDialog
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        title: i18n.t("account.logout")
        text: page.menuRow
              ? i18n.t("account.source.logout.confirm", page.menuRow.sourceName) : ""
        icon: "logout"
        acceptText: i18n.t("account.logout.accept")
        rejectText: i18n.t("common.cancel")
        onAccepted: if (page.menuRow) player.logoutSource(page.menuRow.providerId)
    }
}
