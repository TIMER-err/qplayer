import QtQuick
import QtQuick.Layouts
import md3.Core

// SPlayer-style left navigation rail: account header on top, the section items
// below. `currentIndex` highlights the active page; taps emit navigate()/account().
Rectangle {
    id: rail

    property int currentIndex: 0
    property bool loggedIn: false
    property string userName: ""
    // qml4j can't resolve a custom signal's params in a cross-file handler, so
    // signals stay parameterless and carry their payload via a property.
    property int pendingIndex: 0
    signal navigate()
    signal account()

    property var items: [
        { icon: "recommend",   label: "推荐" },
        { icon: "search",      label: "搜索" },
        { icon: "library_music", label: "我的" },
        { icon: "history",     label: "最近" },
        { icon: "folder",      label: "本地" }
    ]

    implicitWidth: 92
    color: Theme.color.surfaceContainer

    ColumnLayout {
        anchors.fill: parent
        anchors.topMargin: 16
        anchors.bottomMargin: 12
        spacing: 8

        // account header
        ColumnLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignHCenter
            spacing: 4

            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 48; height: 48; radius: 24
                color: rail.loggedIn ? Theme.color.primaryContainer : Theme.color.surfaceContainerHighest
                Text {
                    anchors.centerIn: parent
                    text: "account_circle"
                    font.family: Theme.iconFont.name
                    font.pixelSize: 30
                    color: rail.loggedIn ? Theme.color.onPrimaryContainerColor : Theme.color.onSurfaceVariantColor
                }
                MouseArea { anchors.fill: parent; onClicked: rail.account() }
            }
            Text {
                Layout.alignment: Qt.AlignHCenter
                Layout.maximumWidth: rail.width - 8
                text: rail.loggedIn ? rail.userName : "登录"
                color: Theme.color.onSurfaceVariantColor
                fontSize: 11
                elide: Text.ElideRight
            }
        }

        Rectangle { Layout.fillWidth: true; Layout.leftMargin: 16; Layout.rightMargin: 16
                    implicitHeight: 1; color: Theme.color.outlineVariant }

        // nav items
        Repeater {
            model: rail.items
            Item {
                Layout.fillWidth: true
                implicitHeight: 64
                property bool selected: index === rail.currentIndex

                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: parent.top
                    anchors.topMargin: 6
                    width: 56; height: 32; radius: 16
                    color: parent.selected ? Theme.color.secondaryContainer : "transparent"
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: parent.top
                    anchors.topMargin: 11
                    text: modelData.icon
                    font.family: Theme.iconFont.name
                    font.pixelSize: 22
                    color: parent.selected ? Theme.color.onSecondaryContainerColor
                                           : Theme.color.onSurfaceVariantColor
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.bottom
                    text: modelData.label
                    fontSize: 11
                    color: parent.selected ? Theme.color.onSurfaceColor : Theme.color.onSurfaceVariantColor
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { rail.pendingIndex = index; rail.navigate() }
                }
            }
        }

        Item { Layout.fillHeight: true }
    }
}
