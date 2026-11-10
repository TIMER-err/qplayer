import QtQuick
import miuix.Core
import design
import "../components"

// One source's account sheet, opened from that source's row on AccountPage.
// Wraps the Miuix Dialog (scrim, surface, motion, action area) and fills its
// content slot with an account header plus tappable action lines, rather than
// crowding sign-in and sign-out into the dialog's own button row.
Item {
    id: control

    // The SourceAccountRow this dialog is acting on.
    property var row: null
    signal requestLogin()
    signal requestLogout()

    readonly property real headerHeight: 80
    readonly property real rowHeight: 52
    readonly property bool signedIn: row ? row.loggedIn : false
    // Sign in is always offered; sign out only once there is a session to end.
    readonly property int actionCount: signedIn ? 2 : 1

    function open() { sheet.open() }

    Dialog {
        id: sheet
        objectName: "sourceAccountDialog"
        topInset: settings.topInset
        bottomInset: settings.bottomInset
        // No headline: the header below already carries the source name, and the
        // Dialog title's centred wrap breaks CJK source names mid-word.
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")

        Item {
            width: sheet.contentWidth
            height: control.headerHeight + Ui.space2 + control.actionCount * control.rowHeight

            // --- account header ------------------------------------------
            Rectangle {
                id: header
                width: parent.width
                height: control.headerHeight
                radius: Ui.radiusLg
                color: Ui.surfaceRaised

                Item {
                    id: avatar
                    x: Ui.space4
                    y: (header.height - height) / 2
                    width: 48
                    height: 48
                    Rectangle {
                        anchors.fill: parent
                        radius: width / 2
                        color: control.signedIn ? Ui.accentSoft : Ui.surfaceSunken
                        Glyph {
                            x: (parent.width - width) / 2
                            y: (parent.height - height) / 2
                            size: 26
                            name: "person"
                            color: control.signedIn ? Ui.accent : Ui.textTertiary
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
                    x: avatar.x + avatar.width + 14
                    y: (header.height - implicitHeight - sourceLabel.implicitHeight - 2) / 2
                    width: Math.max(0, header.width - x - Ui.space4)
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    text: control.signedIn
                          ? ((control.row && control.row.displayName.length > 0)
                             ? control.row.displayName : i18n.t("account.anonymous"))
                          : i18n.t("account.source.signedOut")
                    color: control.signedIn ? Ui.textPrimary : Ui.textSecondary
                    font.pixelSize: Ui.textHeadline
                    font.weight: Ui.weightBold
                }

                Text {
                    id: sourceLabel
                    x: who.x
                    y: who.y + who.implicitHeight + 2
                    width: who.width
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    text: !control.row ? ""
                          : (control.row.primary
                             ? control.row.sourceName + " · " + i18n.t("account.source.primary")
                             : control.row.sourceName)
                    color: Ui.textSecondary
                    font.pixelSize: Ui.textCallout
                }
            }

            // --- actions --------------------------------------------------
            // Placed with explicit y rather than a positioner, so the two rows are
            // identical whether or not sign-out is present.
            SourceAccountAction {
                objectName: "sourceAccountLogin"
                y: control.headerHeight + Ui.space2
                width: parent.width
                height: control.rowHeight
                icon: "login"
                text: control.signedIn ? i18n.t("account.source.relogin")
                                       : i18n.t("account.source.login")
                onActivated: { sheet.close(); control.requestLogin() }
            }

            SourceAccountAction {
                objectName: "sourceAccountLogout"
                y: control.headerHeight + Ui.space2 + control.rowHeight
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
