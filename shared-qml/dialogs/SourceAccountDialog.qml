import QtQuick
import miuix.Core
import "../components"

// One source's account settings, opened from that row's gear on AccountPage.
// Wraps the Miuix Dialog (scrim, surface, motion, action area) and fills
// its content slot with an account header plus tappable action rows, rather
// than crowding sign-in and sign-out into the dialog's own button row.
Item {
    id: control

    // The SourceAccountRow this dialog is acting on.
    property var row: null
    signal requestLogin()
    signal requestLogout()

    readonly property real headerHeight: 76
    readonly property real rowHeight: 56
    readonly property bool signedIn: row ? row.loggedIn : false
    // Sign in is always offered; sign out only once there is a session to end.
    readonly property int actionCount: signedIn ? 2 : 1

    function open() { sheet.open() }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: sheet
        icon: "manage_accounts"
        // No headline: Dialog's own title is Layout.fillWidth + Text.Wrap, and the
        // width it resolves to here breaks a source name mid-word (QQ音 / 乐 --
        // Text.Wrap splits between CJK characters). The header below already
        // carries the source name, so the headline was only duplicating it.
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")

        Item {
            width: parent.width
            height: control.headerHeight + control.actionCount * control.rowHeight

            // --- account header ------------------------------------------
            Item {
                id: header
                width: parent.width
                height: control.headerHeight

                Item {
                    id: avatar
                    anchors.left: parent.left
                    anchors.leftMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    width: 48
                    height: 48

                    Rectangle {
                        anchors.fill: parent
                        radius: width / 2
                        color: Theme.color.surfaceContainerHigh
                        Text {
                            anchors.centerIn: parent
                            text: "account_circle"
                            font.family: Theme.iconFont.name
                            font.pixelSize: 30
                            color: Theme.color.onSurfaceVariantColor
                        }
                    }
                    Image {
                        anchors.fill: parent
                        source: control.row ? control.row.avatarUrl : ""
                        radius: width / 2
                        fillMode: "PreserveAspectCrop"
                        visible: control.row && control.row.avatarUrl.length > 0
                        sourceSize.width: Math.round(48 * player.pixelRatio)
                        sourceSize.height: Math.round(48 * player.pixelRatio)
                    }
                }

                Text {
                    id: who
                    anchors.left: avatar.right
                    anchors.leftMargin: 14
                    anchors.right: parent.right
                    anchors.rightMargin: 4
                    anchors.top: parent.top
                    anchors.topMargin: 14
                    elide: Text.ElideRight
                    text: control.signedIn
                          ? ((control.row && control.row.displayName.length > 0)
                             ? control.row.displayName : i18n.t("account.anonymous"))
                          : i18n.t("account.source.signedOut")
                    color: control.signedIn ? Theme.color.onSurfaceColor
                                            : Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.titleMedium.family
                    font.pixelSize: Theme.typography.titleMedium.size
                }

                Text {
                    anchors.left: who.left
                    anchors.right: who.right
                    anchors.top: who.bottom
                    anchors.topMargin: 2
                    elide: Text.ElideRight
                    text: !control.row ? ""
                          : (control.row.primary
                             ? control.row.sourceName + " · " + i18n.t("account.source.primary")
                             : control.row.sourceName)
                    color: Theme.color.onSurfaceVariantColor
                    font.family: Theme.typography.bodySmall.family
                    font.pixelSize: Theme.typography.bodySmall.size
                }

                Rectangle {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    height: 1
                    color: Theme.color.outlineVariant
                }
            }

            // --- actions --------------------------------------------------
            // Placed with explicit y rather than a positioner: this engine does
            // not lay out a Repeater's children, and hand-placing keeps the two
            // rows identical whether or not sign-out is present.
            SourceAccountAction {
                y: control.headerHeight
                width: parent.width
                height: control.rowHeight
                icon: "login"
                text: control.signedIn ? i18n.t("account.source.relogin")
                                       : i18n.t("account.source.login")
                onActivated: { sheet.close(); control.requestLogin() }
            }

            SourceAccountAction {
                y: control.headerHeight + control.rowHeight
                width: parent.width
                height: control.rowHeight
                visible: control.signedIn
                icon: "logout"
                text: i18n.t("account.logout")
                danger: true
                onActivated: { sheet.close(); control.requestLogout() }
            }
        }
    }
}
