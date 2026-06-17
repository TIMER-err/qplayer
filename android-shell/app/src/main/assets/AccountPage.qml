import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Account overlay: signed-in user's profile (avatar / nickname / VIP + level
// badges / signature), header stats (playlist + liked counts) and a logout
// action. Opened from the top-bar account icon when player.loggedIn. Follows
// SettingsPage's overlay pattern -- a plain rounded-rect section layout, since
// md3 Card is fixed-size.
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

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
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "账户"
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
                width: parent.width
                spacing: 14

                // --- profile header ------------------------------------
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    Layout.topMargin: 6
                    radius: 18
                    color: Theme.color.surfaceContainerHighest
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
                            }
                        }

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 6

                            Text {
                                Layout.fillWidth: true
                                text: player.userName.length > 0 ? player.userName : "网易云用户"
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
                                        text: "黑胶 VIP"
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

                // --- stats ---------------------------------------------
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    radius: 18
                    color: Theme.color.surfaceContainerHighest
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
                                text: "歌单"
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
                                text: "红心"
                                color: Theme.color.onSurfaceVariantColor
                                font.family: Theme.typography.bodySmall.family
                                font.pixelSize: Theme.typography.bodySmall.size
                            }
                        }
                    }
                }

                // --- actions -------------------------------------------
                Item { Layout.preferredHeight: 8 }

                Button {
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    type: "outlined"
                    icon: "logout"
                    text: "退出登录"
                    onClicked: logoutDialog.open()
                }
            }
        }
    }

    Dialog {
        id: logoutDialog
        title: "退出登录"
        text: "确定要退出当前账户吗?"
        icon: "logout"
        acceptText: "退出"
        rejectText: "取消"
        onAccepted: { player.logout(); page.back(); }
    }
}
