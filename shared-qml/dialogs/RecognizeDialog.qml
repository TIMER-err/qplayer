import QtQuick
import QtQuick.Layouts
import miuix.Core

// 听歌识曲（移植自她的 recognize/ 包 + Compose 的 RecognizeDialog）。
//
// 后端已在 player 上就位：startRecognize() / cancelRecognize() 与
// recognizePhase（0 未开始 / 1 正在听 / 2 正在匹配 / 3 已找到 / 4 失败）、
// recognizeStatus / recognizeTitle / recognizeArtist。
//
// 只使用本仓库对话框里已验证存在的写法（见 CachedSongsDialog）：
// 根 Rectangle + 吞点击的 MouseArea + ColumnLayout 控制行；
// 不用 Connections、不用 Component.onCompleted、不用 Theme.type.* ——
// 那三样是上一版加载期报 "Component must declare exactly one delegate child object" 的来源。
Rectangle {
    id: page
    signal back()
    signal home()
    property real contentInsetBottom: 0
    color: "transparent"

    // 铺满整屏吞掉空白处点击 —— 但**必须能关掉**：本仓库引擎的命中测试只看 item
    // 自己的 isVisible()，不往上查祖先，所以页面被隐藏后这个 MouseArea 仍会被命中，
    // 把点击全吃掉（用户实测：退出识曲页后整个界面点不动）。引擎会跳过
    // `enabled: false` 的 MouseArea，所以由 Main.qml 把真状态传进来控制它。
    property bool swallowEnabled: true
    MouseArea { anchors.fill: parent; enabled: page.swallowEnabled }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: Theme.metrics.ios27.navBarControl
            Layout.leftMargin: Theme.metrics.ios27.contentInset
            Layout.rightMargin: Theme.metrics.ios27.contentInset
            spacing: 2

            // 返回同时停止识别：识别是麦克风常驻的事，离开这一页就该放手。
            Text {
                Layout.alignment: Qt.AlignVCenter
                text: i18n.t("common.back")
                font.pixelSize: 17
                color: Theme.color.primary
                MouseArea {
                    anchors.fill: parent
                    anchors.margins: -8
                    onClicked: {
                        player.cancelRecognize()
                        page.back()
                    }
                }
            }
            Item { Layout.fillWidth: true; Layout.preferredHeight: 1 }
            Text {
                Layout.alignment: Qt.AlignVCenter
                text: i18n.t("recognize.title")
                font.pixelSize: 17
                font.bold: true
                color: Theme.color.onBackground
            }
            Item { Layout.fillWidth: true; Layout.preferredHeight: 1 }
            Item { Layout.preferredWidth: 44; Layout.preferredHeight: 1 }
        }

        Item { Layout.fillHeight: true }

        // ---- 中间那一块：圆钮 + 状态文字 ----------------------------------------
        //
        // **这里原来是「ColumnLayout 套 ColumnLayout + Layout.preferredWidth: 128」，
        // 在 qml4j 下圆钮被算成 w=0**（实测：`Rectangle x=33.5 y=0 w=0 h=128`），
        // 于是按钮整块既看不见、也点不到（MouseArea 的 anchors.fill 跟着变成 0 宽），
        // 用户看到的就是「识曲界面完全是空的」（2026-10-03 报，桌面探针
        // RecognizeDialogProbe 复现并定位）。
        //
        // 修法照本仓库的既有纪律：**别多套一层 Layout**（见 CLAUDE.md 里
        // 「深层嵌套 Layout 的 fillWidth 传播不可靠」），改成显式尺寸 + anchors 居中。
        // 外层只留一个占位 Item 拿 `Layout.fillWidth`，位置由它自己的高度与上下留白决定。
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 128 + 14 + 24 + 20 + 8

            Rectangle {
                id: recognizeButton
                // 判据靠它找（RecognizeDialogUiTest）：这个按钮曾经被算成 0 宽而整块消失，
                // 名字留着，回归时第一条就会红。
                objectName: "recognizeButton"
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.top: parent.top
                width: 128
                height: 128
                radius: width / 2
                color: Theme.material.glassThick
                border.width: Theme.metrics.hairline
                border.color: Theme.color.separatorSoft

                Text {
                    anchors.centerIn: parent
                    text: player.recognizePhase === 1 || player.recognizePhase === 2
                          ? i18n.t("recognize.stop") : i18n.t("recognize.start")
                    font.pixelSize: 17
                    color: Theme.color.onBackground
                }
                MouseArea {
                    objectName: "recognizeButtonTap"
                    anchors.fill: parent
                    onClicked: player.recognizePhase === 1 || player.recognizePhase === 2
                               ? player.cancelRecognize() : player.startRecognize()
                }
            }

            Text {
                id: recognizeStatus
                anchors.top: recognizeButton.bottom
                anchors.topMargin: 14
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(parent.width - 32, 320)
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                font.pixelSize: 15
                color: Theme.color.onBackground
                text: {
                    // 识别期**必须把 status 显示出来**（2026-10-03 修）：这一层原来无条件
                    // 显示「正在听…」，把后端报上来的说明全吞了——「太安静了，把手机靠近音源」、
                    // 「读不到麦克风数据…」用户一条都看不见，于是界面看起来就是永远卡在
                    // 「正在听…」不动（用户报的正是这个现象）。有说明就显示说明，没有才用阶段文案。
                    if (player.recognizePhase === 1) {
                        return player.recognizeStatus !== "" ? player.recognizeStatus
                                                             : i18n.t("recognize.listening")
                    }
                    if (player.recognizePhase === 2) {
                        return player.recognizeStatus !== "" ? player.recognizeStatus
                                                             : i18n.t("recognize.matching")
                    }
                    if (player.recognizePhase === 3) return player.recognizeTitle
                    if (player.recognizePhase === 4) {
                        return player.recognizeStatus !== "" ? player.recognizeStatus
                                                             : i18n.t("recognize.failed")
                    }
                    return i18n.t("recognize.intro")
                }
            }

            Text {
                anchors.top: recognizeStatus.bottom
                anchors.topMargin: 6
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(parent.width - 32, 320)
                horizontalAlignment: Text.AlignHCenter
                visible: player.recognizePhase === 3 && player.recognizeArtist !== ""
                text: player.recognizeArtist
                font.pixelSize: 14
                color: Theme.color.onSurfaceVariant
            }
        }

        Item { Layout.fillHeight: true }
        Item { Layout.preferredHeight: page.contentInsetBottom }
    }
}
