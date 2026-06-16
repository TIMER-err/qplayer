import QtQuick
import md3.Core

Item {
    id: root

    property var values: [4, 8, 6, 10, 12, 9, 14]
    property var labels: []
    property var series: []
    property real padding: 16
    property real lineWidth: 2
    property bool showPoints: true
    property bool showArea: true
    property int gridLines: 4

    property bool crosshairEnabled: true
    property bool tooltipEnabled: true
    property bool legendEnabled: true
    property string seriesName: "Series"

    property color lineColor: Theme.color.primary
    property color areaColor: Theme.color.primary
    property color gridColor: Theme.color.outlineVariant
    property color axisColor: Theme.color.outline
    property color labelColor: Theme.color.onSurfaceVariantColor
    property color crosshairColor: Theme.color.onSurfaceVariantColor

    implicitWidth: 360
    implicitHeight: 200

    function _clamp(v, lo, hi) {
        return Math.max(lo, Math.min(hi, v))
    }

    property bool _hoverActive: false
    property int _hoverIndex: -1
    property real _hoverX: 0
    property real _hoverY: 0
    property real _hoverValue: 0
    property var _hoverValues: []
    property string _hoverLabel: ""
    property real _mouseX: 0
    property real _mouseY: 0

    function _resolveThemeColor(v, fallback) {
        if (v === undefined || v === null) return fallback
        if (typeof v === "string" && Theme.color && Theme.color[v] !== undefined) return Theme.color[v]
        return v
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

    function _seriesList() {
        const out = []
        if (Array.isArray(root.series) && root.series.length > 0) {
            for (let i = 0; i < root.series.length; i++) {
                const s = root.series[i] || {}
                const vals = Array.isArray(s.values) ? s.values : []
                out.push({
                    name: (s.name !== undefined) ? String(s.name) : ("Series " + (i + 1)),
                    values: vals,
                    lineColor: root._resolveThemeColor(s.lineColor, root._resolveThemeColor(s.color, root.lineColor)),
                    areaColor: root._resolveThemeColor(s.areaColor, root._resolveThemeColor(s.color, root.areaColor)),
                    lineWidth: (s.lineWidth !== undefined) ? Number(s.lineWidth) : root.lineWidth,
                    showArea: (s.showArea !== undefined) ? !!s.showArea : root.showArea,
                    showPoints: (s.showPoints !== undefined) ? !!s.showPoints : root.showPoints
                })
            }
            return out
        }
        out.push({
            name: root.seriesName,
            values: Array.isArray(root.values) ? root.values : [],
            lineColor: root.lineColor,
            areaColor: root.areaColor,
            lineWidth: root.lineWidth,
            showArea: root.showArea,
            showPoints: root.showPoints
        })
        return out
    }

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            const ctx = getContext("2d")
            const w = width
            const h = height
            ctx.clearRect(0, 0, w, h)

            const seriesList = root._seriesList()
            let count = 0
            for (let si = 0; si < seriesList.length; si++) {
                count = Math.max(count, seriesList[si].values.length)
            }

            if (count < 2) {
                ctx.fillStyle = root.labelColor
                ctx.font = root._ctxFont(Theme.typography.labelMedium.size, Theme.typography.labelMedium.family)
                ctx.textAlign = "center"
                ctx.textBaseline = "middle"
                ctx.fillText("No data", w / 2, h / 2)
                return
            }

            let minV = Number.POSITIVE_INFINITY
            let maxV = Number.NEGATIVE_INFINITY
            for (let si = 0; si < seriesList.length; si++) {
                const vals = seriesList[si].values
                for (let i = 0; i < vals.length; i++) {
                    const v = Number(vals[i])
                    if (!Number.isFinite(v)) continue
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
            }
            if (!Number.isFinite(minV) || !Number.isFinite(maxV)) {
                minV = 0
                maxV = 1
            } else if (minV === maxV) {
                minV -= 1
                maxV += 1
            }

            const p = Math.max(0, root.padding)
            const plotX = p
            const plotY = p
            const plotW = Math.max(1, w - p * 2)
            const plotH = Math.max(1, h - p * 2)

            ctx.save()
            ctx.beginPath()
            ctx.rect(plotX, plotY, plotW, plotH)
            ctx.clip()

            ctx.strokeStyle = root.gridColor
            ctx.lineWidth = 1
            const lines = Math.max(0, root.gridLines)
            for (let i = 0; i <= lines; i++) {
                const t = lines === 0 ? 0 : i / lines
                const y = plotY + plotH - t * plotH
                ctx.beginPath()
                ctx.moveTo(plotX, y)
                ctx.lineTo(plotX + plotW, y)
                ctx.stroke()
            }

            ctx.strokeStyle = root.axisColor
            ctx.lineWidth = 1
            ctx.beginPath()
            ctx.moveTo(plotX, plotY + plotH)
            ctx.lineTo(plotX + plotW, plotY + plotH)
            ctx.stroke()

            const stepX = plotW / (count - 1)
            const toY = (v) => plotY + plotH - ((v - minV) / (maxV - minV)) * plotH

            for (let si = 0; si < seriesList.length; si++) {
                const s = seriesList[si]
                const vals = s.values
                ctx.beginPath()
                let started = false
                for (let i = 0; i < vals.length; i++) {
                    const v = Number(vals[i])
                    if (!Number.isFinite(v)) continue
                    const x = plotX + i * stepX
                    const y = toY(v)
                    if (!started) {
                        ctx.moveTo(x, y)
                        started = true
                    } else {
                        ctx.lineTo(x, y)
                    }
                }

                if (started && s.showArea) {
                    ctx.save()
                    ctx.lineTo(plotX + (vals.length - 1) * stepX, plotY + plotH)
                    ctx.lineTo(plotX, plotY + plotH)
                    ctx.closePath()
                    ctx.fillStyle = Qt.rgba(s.areaColor.r, s.areaColor.g, s.areaColor.b, 0.14)
                    ctx.fill()
                    ctx.restore()
                }

                ctx.strokeStyle = s.lineColor
                ctx.lineWidth = s.lineWidth
                ctx.lineJoin = "round"
                ctx.lineCap = "round"
                ctx.stroke()

                if (started && s.showPoints) {
                    ctx.fillStyle = Theme.color.surface
                    ctx.strokeStyle = s.lineColor
                    ctx.lineWidth = Math.max(1, s.lineWidth)
                    for (let i = 0; i < vals.length; i++) {
                        const v = Number(vals[i])
                        if (!Number.isFinite(v)) continue
                        const x = plotX + i * stepX
                        const y = toY(v)
                        ctx.beginPath()
                        ctx.arc(x, y, 3.5, 0, Math.PI * 2)
                        ctx.fill()
                        ctx.stroke()
                    }
                }
            }

            if (root.crosshairEnabled && root._hoverActive && root._hoverIndex >= 0) {
                const cx = root._hoverX
                const cy = root._hoverY

                ctx.save()
                if (ctx.setLineDash) ctx.setLineDash([4, 4])
                ctx.strokeStyle = Qt.rgba(root.crosshairColor.r, root.crosshairColor.g, root.crosshairColor.b, 0.65)
                ctx.lineWidth = 1

                ctx.beginPath()
                ctx.moveTo(cx, plotY)
                ctx.lineTo(cx, plotY + plotH)
                ctx.stroke()

                ctx.beginPath()
                ctx.moveTo(plotX, cy)
                ctx.lineTo(plotX + plotW, cy)
                ctx.stroke()

                if (ctx.setLineDash) ctx.setLineDash([])

                for (let si = 0; si < seriesList.length; si++) {
                    const s = seriesList[si]
                    const vals = s.values
                    const idx = root._hoverIndex
                    if (idx < 0 || idx >= vals.length) continue
                    const v = Number(vals[idx])
                    if (!Number.isFinite(v)) continue
                    const y = toY(v)
                    ctx.fillStyle = Theme.color.surface
                    ctx.strokeStyle = s.lineColor
                    ctx.lineWidth = Math.max(1, s.lineWidth)
                    ctx.beginPath()
                    ctx.arc(cx, y, 5, 0, Math.PI * 2)
                    ctx.fill()
                    ctx.stroke()
                }
                ctx.restore()
            }

            ctx.restore()

            ctx.fillStyle = root.labelColor
            ctx.font = root._ctxFont(Theme.typography.labelSmall.size, Theme.typography.labelSmall.family)
            ctx.textAlign = "left"
            ctx.textBaseline = "top"
            ctx.fillText(String(Math.round(maxV)), plotX, plotY)
            ctx.textBaseline = "bottom"
            ctx.fillText(String(Math.round(minV)), plotX, plotY + plotH)
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    Connections {
        target: Theme
        function onColorChanged() { canvas.requestPaint() }
    }

    MouseArea {
        anchors.fill: parent
        hoverEnabled: true

        onPositionChanged: (mouse) => {
            root._mouseX = mouse.x
            root._mouseY = mouse.y

            const seriesList = root._seriesList()
            let count = 0
            for (let si = 0; si < seriesList.length; si++) count = Math.max(count, seriesList[si].values.length)
            if (count < 2) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    canvas.requestPaint()
                }
                return
            }

            let minV = Number.POSITIVE_INFINITY
            let maxV = Number.NEGATIVE_INFINITY
            for (let si = 0; si < seriesList.length; si++) {
                const vals = seriesList[si].values
                for (let i = 0; i < vals.length; i++) {
                    const v = Number(vals[i])
                    if (!Number.isFinite(v)) continue
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
            }
            if (!Number.isFinite(minV) || !Number.isFinite(maxV)) {
                minV = 0
                maxV = 1
            } else if (minV === maxV) {
                minV -= 1
                maxV += 1
            }

            const p = Math.max(0, root.padding)
            const plotX = p
            const plotY = p
            const plotW = Math.max(1, root.width - p * 2)
            const plotH = Math.max(1, root.height - p * 2)

            const inside = mouse.x >= plotX && mouse.x <= plotX + plotW && mouse.y >= plotY && mouse.y <= plotY + plotH
            if (!inside) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    canvas.requestPaint()
                }
                return
            }

            const stepX = plotW / (count - 1)
            const idx = root._clamp(Math.round((mouse.x - plotX) / stepX), 0, count - 1)
            const x = plotX + idx * stepX
            const cy = root._clamp(mouse.y, plotY, plotY + plotH)

            const hoverVals = []
            for (let si = 0; si < seriesList.length; si++) {
                const vals = seriesList[si].values
                hoverVals.push((idx >= 0 && idx < vals.length) ? vals[idx] : undefined)
            }

            root._hoverActive = true
            root._hoverIndex = idx
            root._hoverValues = hoverVals
            root._hoverValue = (hoverVals.length > 0 && hoverVals[0] !== undefined) ? hoverVals[0] : 0
            root._hoverX = x
            root._hoverY = cy
            root._hoverLabel = (Array.isArray(root.labels) && root.labels.length > idx) ? String(root.labels[idx]) : String(idx + 1)
            canvas.requestPaint()
        }

        onExited: {
            if (root._hoverActive) {
                root._hoverActive = false
                root._hoverIndex = -1
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
            text: {
                const list = root._seriesList()
                const idx = root._hoverIndex
                const header = root._hoverLabel
                let body = ""
                for (let si = 0; si < list.length; si++) {
                    const s = list[si]
                    const vals = s.values
                    const v = (idx >= 0 && idx < vals.length) ? vals[idx] : undefined
                    if (v === undefined) continue
                    body += (body.length ? "\n" : "") + s.name + ": " + v
                }
                return header + (body.length ? ("\n" + body) : "")
            }
        }
    }

    Rectangle {
        visible: root.legendEnabled
        color: Theme.color.surfaceContainerHigh
        border.color: Theme.color.outlineVariant
        border.width: 1
        radius: 10
        z: 9
        opacity: 0.92

        anchors.left: parent.left
        anchors.top: parent.top
        anchors.margins: 10

        height: 28
        width: legendFlow.implicitWidth + 16

        Flow {
            id: legendFlow
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: parent.left
            anchors.leftMargin: 8
            spacing: 8

            Repeater {
                model: root._seriesList()
                delegate: Row {
                    spacing: 6
                    Rectangle {
                        width: 10
                        height: 10
                        radius: 5
                        color: modelData.lineColor
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: modelData.name
                        color: Theme.color.onSurfaceColor
                        font.pixelSize: Theme.typography.labelMedium.size
                        font.family: Theme.typography.labelMedium.family
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }
        }
    }

    onValuesChanged: canvas.requestPaint()
    onLabelsChanged: canvas.requestPaint()
    onSeriesChanged: canvas.requestPaint()
    onPaddingChanged: canvas.requestPaint()
    onLineWidthChanged: canvas.requestPaint()
    onShowPointsChanged: canvas.requestPaint()
    onShowAreaChanged: canvas.requestPaint()
    onGridLinesChanged: canvas.requestPaint()
    onCrosshairEnabledChanged: canvas.requestPaint()
    onTooltipEnabledChanged: canvas.requestPaint()
    onLegendEnabledChanged: canvas.requestPaint()
    onSeriesNameChanged: canvas.requestPaint()
    onLineColorChanged: canvas.requestPaint()
    onAreaColorChanged: canvas.requestPaint()
    onGridColorChanged: canvas.requestPaint()
    onAxisColorChanged: canvas.requestPaint()
    onLabelColorChanged: canvas.requestPaint()
    onCrosshairColorChanged: canvas.requestPaint()
}
