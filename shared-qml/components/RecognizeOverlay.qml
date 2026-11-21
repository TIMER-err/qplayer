import QtQuick
import "../dialogs"

// 识曲页的整屏层（2026-11-15 用户拍板"能出现就行"）。
//
// 为什么不走 `ManagedPageLoader`：那条路在这个引擎里对 `replacePage` 推的路由**不实例化**
// （实测 present/active/shownState/visible 全 true，但 children=0、opacity=0；
//  把 sourceComponent 换成极简 Item 也一样 ✗）。这里直接把页面当普通子项建出来 ✓
//
// `routeType` 这个名字是给判据定位用的（它原本是 ManagedPageLoader 的必需属性）。
Item {
    id: overlay

    property string routeType: "recognize"
    property bool open: false
    property real insetBottom: 0

    signal home()
    signal back()

    anchors.fill: parent
    z: 9000
    opacity: open ? 1 : 0
    visible: open

    RecognizeDialog {
        anchors.fill: parent
        swallowEnabled: overlay.open
        contentInsetBottom: overlay.insetBottom
        onHome: overlay.home()
        onBack: overlay.back()
    }
}
