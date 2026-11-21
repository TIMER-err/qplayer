import QtQuick
import miuix.Core

// Rounded album-cover image with a glyph placeholder shown until the cover
// decodes. `source` accepts a local asset path or an http(s) URL — the engine
// Image fetches + decodes remote sources off-thread. Rounding is done by the
// Image's own `radius` (a clipRRect at draw time), NOT a layer.effect mask.
//
// 换封面的规则（2026-11-15 与用户确认后的最终版）：
//   ① **不闪灰**：新封面还没解码好之前继续画上一张；灰底占位只在"一张都没有"时出现
//   ② 换图做**交叉淡化**（automix 交接时约 420ms；其它情况瞬切）
//   （先前那版"纸被烧掉"的写法在这个引擎里看不出效果 ⇒ 已废弃）
Item {
    id: cover

    property string source: ""
    property real radius: 8
    property string icon: "music_note"
    property int iconSize: 26
    // Crop instead of letterboxing. Defaults on for circular covers (artist
    // avatars), where a letterboxed photo reads as a broken circle.
    property bool crop: cover.width > 0 && cover.radius * 2 >= cover.width - 0.5
    property bool fadeIn: false
    /** 换图时是否做交叉淡化（只有 automix 交接才打开） */
    property bool crossFadeTransition: false
    property int crossFadeMs: 420

    // qml4j decodes at the sourceSize present when `source` is first seen and
    // does not re-decode if sourceSize later changes. Latch the pixel size once
    // laid out so the first decode is already at display resolution.
    property int decodeW: 0
    property int decodeH: 0

    // ---- 换图状态 -----------------------------------------------------------
    /**
     * 网格首帧错峰（上游 2.2.x 加的）：调用方按卡片下标给一个毫秒值，
     * 让同一屏的封面不要挤在一帧里解码（打开「我的」实测 744 ms 单帧）。
     * 我们暂时只接受这个属性、不做延迟解码 —— 我们的换图链路还要保证
     * 「只有 automix 交接才交叉淡化」，先不引入第二条时序，避免两套时序打架。
     */
    property int deferMs: 0
    property bool deferred: deferMs > 0

    /** 屏上正在显示的那一张 */
    property string shownSource: ""
    /** 交叉淡化中：上面那层 = shownSource，下面那层 = source */
    property bool fading: false
    property real fadeProgress: 0
    /** 这次换图是不是 automix 交接引起的（只有它才做淡化） */
    property bool fadeArmed: false
    property bool waitingReady: false
    property int waitTicks: 0

    readonly property bool waitingForNew: !fading && shownSource !== "" && shownSource !== source

    // 等新图解码（qml4j 里 status 不一定推进 ⇒ 最多等 900ms 强制换）
    Timer {
        id: readyWait
        interval: 30
        repeat: true
        onTriggered: {
            cover.waitTicks = cover.waitTicks + 1
            if (img.status === Image.Ready || cover.waitTicks > 30) {
                cover.waitingReady = false
                readyWait.stop()
                cover.beginFadeNow()
            }
        }
    }

    function latchDecodeSize() {
        var w = Math.round(width * player.pixelRatio)
        var h = Math.round(height * player.pixelRatio)
        if (w <= 0 || h <= 0) return
        if (decodeW === 0 || Math.abs(w - decodeW) >= 32 || Math.abs(h - decodeH) >= 32) {
            decodeW = w
            decodeH = h
        }
    }

    onWidthChanged: latchDecodeSize()
    onHeightChanged: latchDecodeSize()
    Component.onCompleted: latchDecodeSize()

    function finishFade() {
        fading = false
        fadeProgress = 0
        shownSource = cover.source
    }

    function beginFadeNow() {
        if (fading) return
        if (source === "" || shownSource === source) return
        // 只有 automix 交接才做交叉淡化；手动切歌/换队列/拖进度一律瞬切
        if (!crossFadeTransition || !fadeArmed) {
            shownSource = cover.source
            return
        }
        fading = true
        fadeProgress = 0
    }

    function maybeFade() {
        if (fading) return
        if (decodeW <= 0 || source === "") return
        if (shownSource === "") {
            shownSource = source
            return
        }
        if (shownSource === source) return
        if (img.status === Image.Ready) {
            beginFadeNow()
            return
        }
        if (!waitingReady) {
            waitingReady = true
            waitTicks = 0
            readyWait.restart()
        }
    }

    // 推进淡化进度：NumberAnimation 对自定义属性名不保证生效 ⇒ 用 Timer 推 ✓
    Timer {
        interval: 16
        repeat: true
        running: cover.fading
        onTriggered: {
            if (!cover.fading) return
            cover.fadeProgress = Math.min(1, cover.fadeProgress + 16 / Math.max(1, cover.crossFadeMs))
            if (cover.fadeProgress >= 1) cover.finishFade()
        }
    }

    // Placeholder：只在"一张都没有"时出现
    Rectangle {
        objectName: "coverPlaceholder"
        visible: img.status !== Image.Ready && cover.shownSource === "" && !cover.fading
        width: cover.width
        height: cover.height
        radius: cover.radius
        color: Theme.color.surfaceContainerHighest
        Text {
            width: parent.width
            height: parent.height
            horizontalAlignment: Text.AlignHCenter
            text: cover.icon
            font.family: Theme.iconFont.name
            font.pixelSize: cover.iconSize
            color: Theme.color.onSurfaceVariantColor
        }
    }

    // 下面那层：新封面
    Image {
        id: img
        objectName: "coverArtImage"
        width: cover.width
        height: cover.height
        clip: true
        source: cover.decodeW > 0 ? cover.source : ""
        radius: cover.radius
        fillMode: cover.crop ? "PreserveAspectCrop" : "PreserveAspectFit"
        sourceSize.width: cover.decodeW
        sourceSize.height: cover.decodeH
        opacity: cover.fading ? cover.fadeProgress : 1
        visible: cover.fading || !cover.waitingForNew
        onStatusChanged: cover.maybeFade()
    }

    // 上面那层：上一张（淡化时逐渐透明）
    Image {
        objectName: "coverPreviousArt"
        visible: cover.shownSource !== "" && cover.shownSource !== cover.source
        width: cover.width
        height: cover.height
        clip: true
        source: cover.decodeW > 0 && visible ? cover.shownSource : ""
        radius: cover.radius
        fillMode: cover.crop ? "PreserveAspectCrop" : "PreserveAspectFit"
        sourceSize.width: cover.decodeW
        sourceSize.height: cover.decodeH
        opacity: cover.fading ? (1 - cover.fadeProgress) : 1
    }

    onSourceChanged: {
        cover.fadeArmed = crossFadeTransition && ("" + player.automixHandover) === "true"
        cover.maybeFade()
    }
    onDecodeWChanged: cover.maybeFade()
}
