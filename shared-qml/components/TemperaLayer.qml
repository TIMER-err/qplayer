import QtQuick
import md3.Core

// 「凝彩」歌词页的 QML 控件层：右下角一枚胶囊（退出 / 播放暂停 / 下一首）。
//
// 画面不是 QML 画的 —— 打开凝彩模式后，RenderThread 整帧用 Skija 满屏绘制（构图 + 逐字歌词），
// 再单独把这棵子树渲染上去，所以它是这一页唯一可见的 QML（和标准歌词页的 "lyricChrome"
// 是同一套子树渲染机制）。根节点的 objectName 固定为 "temperaChrome"，宿主按这个名字查它。
//
// 凝彩不是独立页面：它和标准歌词共用歌词页这一个入口（迷你播放器的歌词按钮 / "lyrics" 路由），
// 设置里的「启用凝彩」只决定歌词页由哪套渲染器出画。
//
// 控件刻意放在右下角：桌面端自定义标题栏（TitleBar）压在最上层且点击区正好是顶部那一条，
// 左上角放什么都不稳定 —— 这正是上一版退出按钮点了没反应的原因。
Item {
    id: layer
    objectName: "temperaChrome"
    visible: player.lyricSlide > 0.001 && settings.value("temperaEnabled") === true

    // 与标准歌词页同一条 bottom-sheet 位移，两页的开合手感保持一致。
    property real transitionEase: {
        var s = player.lyricSlide
        return s * s * (3 - 2 * s)
    }
    y: (1 - transitionEase) * height

    // 空白处吞掉点击，避免手势穿到下面那层（那一层并没有被绘制）。
    MouseArea { anchors.fill: parent }

    Rectangle {
        id: capsule
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.rightMargin: settings.rightInset + 18
        anchors.bottomMargin: settings.bottomInset + 26
        height: 56
        width: row.implicitWidth + 16
        radius: height / 2
        color: settings.resolvedDark ? "#40FFFFFF" : "#33000000"

        Row {
            id: row
            anchors.centerIn: parent
            spacing: 2

            IconButton {
                type: "standard"
                icon: "expand_more"
                contentColor: settings.resolvedDark ? "#FFFFFFFF" : "#FF0B0B10"
                onClicked: player.setLyricsOpen(false)
            }
            IconButton {
                type: "filled"
                icon: player.playing ? "pause" : "play_arrow"
                onClicked: player.toggle()
            }
            IconButton {
                type: "standard"
                icon: "skip_next"
                contentColor: settings.resolvedDark ? "#FFFFFFFF" : "#FF0B0B10"
                onClicked: player.next()
            }
        }
    }
}
