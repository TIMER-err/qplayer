import QtQuick
import QtQuick.Layouts
import md3.Core

// MD3-style bottom navigation bar (phone): five items spread evenly, each with
// an animated selection pill + ripple. Parameterless signal + property payload
// (qml4j can't read cross-file signal params).
Rectangle {
    id: bar

    property int currentIndex: 0
    property int pendingIndex: 0
    signal navigate()

    property var items: [
        { icon: "recommend",     label: "推荐" },
        { icon: "search",        label: "搜索" },
        { icon: "library_music", label: "我的" },
        { icon: "history",       label: "最近" },
        { icon: "folder",        label: "本地" }
    ]

    implicitHeight: 76
    color: Theme.color.surfaceContainer

    RowLayout {
        anchors.fill: parent
        anchors.topMargin: 8
        anchors.bottomMargin: 8
        spacing: 0

        Repeater {
            model: bar.items
            Item {
                id: navItem
                Layout.fillWidth: true
                Layout.fillHeight: true
                property bool selected: index === bar.currentIndex
                property color indColor: Theme.color.secondaryContainer

                Rectangle {
                    id: pill
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: parent.top
                    width: 64; height: 32; radius: 16
                    color: Qt.rgba(navItem.indColor.r, navItem.indColor.g,
                                   navItem.indColor.b, navItem.selected ? 1 : 0)
                    Behavior on color { ColorAnimation { duration: 200; easing.type: Easing.OutCubic } }
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: parent.top
                    anchors.topMargin: 5
                    text: modelData.icon
                    font.family: Theme.iconFont.name
                    font.pixelSize: 22
                    color: navItem.selected ? Theme.color.onSecondaryContainerColor
                                            : Theme.color.onSurfaceVariantColor
                    Behavior on color { ColorAnimation { duration: 200 } }
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: parent.top
                    anchors.topMargin: 38
                    text: modelData.label
                    fontSize: 11
                    color: navItem.selected ? Theme.color.onSurfaceColor
                                            : Theme.color.onSurfaceVariantColor
                    Behavior on color { ColorAnimation { duration: 200 } }
                }
                Ripple {
                    anchors.fill: pill
                    clipRadius: 16
                    onClicked: { bar.pendingIndex = index; bar.navigate() }
                }
            }
        }
    }
}
