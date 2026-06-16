import QtQuick
import md3.Core

Item {
    id: root

    property var values: [30, 20, 15, 10, 25]
    property var labels: []
    property var sliceColors: [
        Theme.color.primary,
        Theme.color.secondary,
        Theme.color.tertiary,
        Theme.color.error,
        Theme.color.outline
    ]
    property real padding: 12
    property real innerRadiusRatio: 0
    property real strokeWidth: 0

    property bool tooltipEnabled: true
    property bool legendEnabled: true
    property bool hoverEnabled: true
    property real hoverOffset: 10
    property real hoverExpand: 0.06

    property color labelColor: Theme.color.onSurfaceVariantColor
    property color strokeColor: Theme.color.surface

    implicitWidth: 260
    implicitHeight: 220

    function _clamp(v, lo, hi) {
        return Math.max(lo, Math.min(hi, v))
    }

    function _pickFontFamily(preferredFamily) {
        const fams = Qt.fontFamilies()
        if (fams.indexOf(preferredFamily) >= 0) return preferredFamily
        return Qt.application.font.family
    }

    function _ctxFont(sizePx, preferredFamily) {
        const fam = root._pickFontFamily(preferredFamily)
        return sizePx + "px \"" + fam + "\", sans-serif"
    }

    property bool _hoverActive: false
    property int _hoverIndex: -1
    property real _hoverValue: 0
    property real _hoverPercent: 0
    property string _hoverLabel: ""
    property real _mouseX: 0
    property real _mouseY: 0
    property real hoverT: 0

    NumberAnimation {
        id: hoverAnim
        target: root
        property: "hoverT"
        duration: 140
        easing.type: Easing.OutCubic
    }

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            const ctx = getContext("2d")
            const w = width
            const h = height
            ctx.clearRect(0, 0, w, h)

            const data = Array.isArray(root.values) ? root.values : []
            let sum = 0
            for (let i = 0; i < data.length; i++) sum += Math.max(0, data[i])

            if (sum <= 0) {
                ctx.fillStyle = root.labelColor
                ctx.font = root._ctxFont(Theme.typography.labelMedium.size, Theme.typography.labelMedium.family)
                ctx.textAlign = "center"
                ctx.textBaseline = "middle"
                ctx.fillText("No data", w / 2, h / 2)
                return
            }

            const p = Math.max(0, root.padding)
            const r = Math.max(1, Math.min((w - p * 2) / 2, (h - p * 2) / 2))
            const cx = w / 2
            const cy = h / 2
            const innerR = Math.max(0, Math.min(r, r * root.innerRadiusRatio))
            const ringStroke = Math.max(0, root.strokeWidth)

            let start = -Math.PI / 2
            for (let i = 0; i < data.length; i++) {
                const v = Math.max(0, data[i])
                if (v <= 0) continue
                const angle = (v / sum) * Math.PI * 2
                const end = start + angle

                const c = (Array.isArray(root.sliceColors) && root.sliceColors.length > 0)
                    ? root.sliceColors[i % root.sliceColors.length]
                    : Theme.color.primary

                const isHover = root.hoverEnabled && root._hoverActive && root._hoverIndex === i
                const t = isHover ? root.hoverT : 0
                const mid = (start + end) / 2
                const off = root.hoverOffset * t
                const cxx = cx + Math.cos(mid) * off
                const cyy = cy + Math.sin(mid) * off
                const rr = r * (1 + root.hoverExpand * t)

                ctx.beginPath()
                ctx.moveTo(cxx + Math.cos(start) * rr, cyy + Math.sin(start) * rr)
                ctx.arc(cxx, cyy, rr, start, end, false)
                if (innerR > 0) {
                    ctx.lineTo(cxx + Math.cos(end) * innerR, cyy + Math.sin(end) * innerR)
                    ctx.arc(cxx, cyy, innerR, end, start, true)
                } else {
                    ctx.lineTo(cxx, cyy)
                }
                ctx.closePath()
                ctx.fillStyle = c
                ctx.fill()

                if (ringStroke > 0) {
                    ctx.strokeStyle = root.strokeColor
                    ctx.lineWidth = ringStroke
                    ctx.stroke()
                }

                start = end
            }
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    MouseArea {
        anchors.fill: parent
        hoverEnabled: true

        onPositionChanged: (mouse) => {
            root._mouseX = mouse.x
            root._mouseY = mouse.y

            const data = Array.isArray(root.values) ? root.values : []
            let sum = 0
            for (let i = 0; i < data.length; i++) sum += Math.max(0, data[i])
            if (sum <= 0) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    hoverAnim.stop()
                    hoverAnim.to = 0
                    hoverAnim.start()
                    canvas.requestPaint()
                }
                return
            }

            const p = Math.max(0, root.padding)
            const r = Math.max(1, Math.min((root.width - p * 2) / 2, (root.height - p * 2) / 2))
            const cx = root.width / 2
            const cy = root.height / 2
            const innerR = Math.max(0, Math.min(r, r * root.innerRadiusRatio))

            const dx = mouse.x - cx
            const dy = mouse.y - cy
            const dist = Math.sqrt(dx * dx + dy * dy)
            const inside = dist <= r && dist >= innerR
            if (!inside) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    hoverAnim.stop()
                    hoverAnim.to = 0
                    hoverAnim.start()
                    canvas.requestPaint()
                }
                return
            }

            let angle = Math.atan2(dy, dx)
            const start0 = -Math.PI / 2
            let a = angle - start0
            const twoPi = Math.PI * 2
            while (a < 0) a += twoPi
            while (a >= twoPi) a -= twoPi

            let acc = 0
            let found = -1
            for (let i = 0; i < data.length; i++) {
                const v = Math.max(0, data[i])
                if (v <= 0) continue
                const seg = (v / sum) * twoPi
                if (a >= acc && a < acc + seg) {
                    found = i
                    break
                }
                acc += seg
            }

            if (found !== root._hoverIndex || !root._hoverActive) {
                root._hoverActive = found >= 0
                root._hoverIndex = found
                if (found >= 0) {
                    const v = Math.max(0, data[found])
                    root._hoverValue = v
                    root._hoverPercent = v / sum
                    root._hoverLabel = (Array.isArray(root.labels) && root.labels.length > found) ? String(root.labels[found]) : ("Slice " + (found + 1))
                    if (root.hoverEnabled) {
                        hoverAnim.stop()
                        root.hoverT = 0
                        hoverAnim.to = 1
                        hoverAnim.start()
                    }
                }
                canvas.requestPaint()
            }
        }

        onExited: {
            if (root._hoverActive) {
                root._hoverActive = false
                root._hoverIndex = -1
                hoverAnim.stop()
                hoverAnim.to = 0
                hoverAnim.start()
                canvas.requestPaint()
            }
        }
    }

    Rectangle {
        id: tooltip
        visible: root.tooltipEnabled && root._hoverActive && root._hoverIndex >= 0
        color: Theme.color.surfaceContainerHigh
        border.color: Theme.color.outlineVariant
        border.width: 1
        radius: 10
        z: 10
        opacity: visible ? 1 : 0

        property real pad: 10
        implicitWidth: tooltipText.implicitWidth + pad * 2
        implicitHeight: tooltipText.implicitHeight + pad * 2

        x: root._clamp(root._mouseX + 14, 0, root.width - implicitWidth)
        y: root._clamp(root._mouseY + 14, 0, root.height - implicitHeight)

        Text {
            id: tooltipText
            anchors.centerIn: parent
            width: implicitWidth
            wrapMode: Text.NoWrap
            color: Theme.color.onSurfaceColor
            font.pixelSize: Theme.typography.labelMedium.size
            font.family: Theme.typography.labelMedium.family
            text: root._hoverLabel + "\n" + root._hoverValue + " (" + Math.round(root._hoverPercent * 100) + "%)"
        }
    }

    Item {
        id: legend
        visible: root.legendEnabled
        z: 9

        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.margins: 10

        width: Math.min(parent.width - 20, legendFlow.implicitWidth + 16)
        height: Math.min(parent.height - 20, legendFlow.implicitHeight + 16)

        Rectangle {
            anchors.fill: parent
            color: Theme.color.surfaceContainerHigh
            border.color: Theme.color.outlineVariant
            border.width: 1
            radius: 10
            opacity: 0.92
        }

        Flow {
            id: legendFlow
            anchors.fill: parent
            anchors.margins: 8
            spacing: 8

            Repeater {
                model: {
                    const data = Array.isArray(root.values) ? root.values : []
                    return Math.min(data.length, 12)
                }

                delegate: Row {
                    spacing: 6
                    Rectangle {
                        width: 10
                        height: 10
                        radius: 5
                        color: (Array.isArray(root.sliceColors) && root.sliceColors.length > 0)
                            ? root.sliceColors[index % root.sliceColors.length]
                            : Theme.color.primary
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: (Array.isArray(root.labels) && root.labels.length > index) ? String(root.labels[index]) : ("Slice " + (index + 1))
                        color: Theme.color.onSurfaceColor
                        font.pixelSize: Theme.typography.labelSmall.size
                        font.family: Theme.typography.labelSmall.family
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }
        }
    }

    onValuesChanged: canvas.requestPaint()
    onLabelsChanged: canvas.requestPaint()
    onSliceColorsChanged: canvas.requestPaint()
    onPaddingChanged: canvas.requestPaint()
    onInnerRadiusRatioChanged: canvas.requestPaint()
    onStrokeWidthChanged: canvas.requestPaint()
    onTooltipEnabledChanged: canvas.requestPaint()
    onLegendEnabledChanged: canvas.requestPaint()
    onHoverEnabledChanged: canvas.requestPaint()
    onHoverOffsetChanged: canvas.requestPaint()
    onHoverExpandChanged: canvas.requestPaint()
    onLabelColorChanged: canvas.requestPaint()
    onStrokeColorChanged: canvas.requestPaint()
    onHoverTChanged: canvas.requestPaint()
}
