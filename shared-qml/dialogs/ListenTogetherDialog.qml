import QtQuick
import QtQuick.Layouts
import md3.Core

// Shared room panel opened from MiniPlayer. Wrap the actual Dialog instead of
// deriving this component from it: qml4j currently loses user-defined base-type
// properties (notably Dialog.icon) on that composition boundary.
Item {
    id: control

    function open() { dialog.open() }
    function close() { dialog.close() }

    Dialog {
        id: dialog
        icon: "group"
        title: player.loginProviderName + "一起听"
        text: player.listenTogetherInRoom
              ? player.listenTogetherStatusText
              : (player.loggedIn
                 ? "创建房间，或粘贴一起听邀请信息加入。"
                 : ("登录" + player.loginProviderName + "账号后才能使用一起听。"))
        showAcceptButton: false
        rejectText: "关闭"

        ColumnLayout {
            width: parent.width
            spacing: 12

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4
                visible: player.listenTogetherInRoom
                Text {
                    Layout.fillWidth: true
                    text: "房间 " + player.listenTogetherRoomId
                    color: Theme.color.onSurfaceColor
                    fontSize: 14
                    wrapMode: Text.Wrap
                }
                Text {
                    Layout.fillWidth: true
                    text: player.listenTogetherMembers
                    color: Theme.color.onSurfaceVariantColor
                    fontSize: 13
                    wrapMode: Text.Wrap
                }
                RowLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 8
                    spacing: 8
                    Button {
                        Layout.fillWidth: true
                        type: "filledTonal"
                        text: "复制邀请"
                        enabled: !player.listenTogetherBusy
                        onClicked: player.copyListenTogetherInvitation()
                    }
                    Button {
                        Layout.fillWidth: true
                        type: "text"
                        text: "退出房间"
                        enabled: !player.listenTogetherBusy
                        onClicked: player.leaveListenTogether()
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 12
                visible: !player.listenTogetherInRoom && player.loggedIn
                Button {
                    Layout.fillWidth: true
                    type: "filled"
                    text: player.listenTogetherBusy ? "正在创建…" : "创建房间"
                    enabled: !player.listenTogetherBusy
                    onClicked: player.createListenTogetherRoom()
                }
                Text {
                    Layout.fillWidth: true
                    text: "或"
                    horizontalAlignment: Text.AlignHCenter
                    color: Theme.color.onSurfaceVariantColor
                    fontSize: 13
                }
                TextField {
                    id: invitationField
                    Layout.fillWidth: true
                    type: "outlined"
                    label: "邀请链接"
                    onAccepted: if (text.length > 0) player.joinListenTogether(text)
                }
                Button {
                    Layout.fillWidth: true
                    type: "filledTonal"
                    text: player.listenTogetherBusy ? "正在加入…" : "加入房间"
                    enabled: !player.listenTogetherBusy && invitationField.text.length > 0
                    onClicked: player.joinListenTogether(invitationField.text)
                }
            }
        }
    }
}
