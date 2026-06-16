import QtQuick
import md3.Core

Item {
    id: root

    property var values: [12, 6, 9, 3, 11, 7]
    property var labels: []
    property var barColors: []
    property var series: []
    property string seriesName: "Series"
    property real padding: 16
    property real barSpacing: 8
    property real seriesSpacing: 4
    property real cornerRadius: 6
    property int gridLines: 4

    property bool crosshairEnabled: true
    property bool tooltipEnabled: true
    property bool legendEnabled: true

    property color barColor: Theme.color.primary
    property color gridColor: Theme.color.outlineVariant
    property color axisColor: Theme.color.outline
    property color labelColor: Theme.color.onSurfaceVariantColor
    property color crosshairColor: Theme.color.onSurfaceVariantColor
    property color highlightColor: Theme.color.primary

    implicitWidth: 360
    implicitHeight: 200

    function _clamp(v, lo, hi) {
        return Math.max(lo, Math.min(hi, v))
    }

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
        const palette = [Theme.color.primary, Theme.color.secondary, Theme.color.tertiary, Theme.color.error, Theme.color.outline]
        const out = []
        if (Array.isArray(root.series) && root.series.length > 0) {
            for (let i = 0; i < root.series.length; i++) {
                const s = root.series[i] || {}
                const vals = Array.isArray(s.values) ? s.values : []
                let c = root._resolveThemeColor(s.color, root._resolveThemeColor(s.barColor, undefined))
                if (!c) {
                    if (Array.isArray(root.barColors) && root.barColors.length > 0) c = root.barColors[i % root.barColors.length]
                    else c = palette[i % palette.length]
                }
                out.push({
                    name: (s.name !== undefined) ? String(s.name) : ("Series " + (i + 1)),
                    values: vals,
                    color: c
                })
            }
            return out
        }
        return [{
            name: root.seriesName,
            values: Array.isArray(root.values) ? root.values : [],
            color: root.barColor
        }]
    }

    function _isMultiSeries() {
        return Array.isArray(root.series) && root.series.length > 0
    }

    property bool _hoverActive: false
    property int _hoverIndex: -1
    property int _hoverSeriesIndex: -1
    property real _hoverX: 0
    property real _mouseX: 0
    property real _mouseY: 0
    property real _hoverValue: 0
    property string _hoverLabel: ""
    property real _legendReserve: legendArea.visible ? (legendArea.height + 8) : 0

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            const ctx = getContext("2d")
            const w = width
            const h = height
            ctx.clearRect(0, 0, w, h)

            const seriesList = root._seriesList()
            const seriesMode = root._isMultiSeries()

            let categories = 0
            for (let si = 0; si < seriesList.length; si++) categories = Math.max(categories, seriesList[si].values.length)

            if (categories === 0) {
                ctx.fillStyle = root.labelColor
                ctx.font = root._ctxFont(Theme.typography.labelMedium.size, Theme.typography.labelMedium.family)
                ctx.textAlign = "center"
                ctx.textBaseline = "middle"
                ctx.fillText("No data", w / 2, h / 2)
                return
            }

            let maxV = 0
            for (let si = 0; si < seriesList.length; si++) {
                const vals = seriesList[si].values
                for (let i = 0; i < vals.length; i++) {
                    const v = Math.max(0, Number(vals[i]))
                    if (!Number.isFinite(v)) continue
                    if (v > maxV) maxV = v
                }
            }
            if (maxV === 0) maxV = 1

            const p = Math.max(0, root.padding)
            const plotX = p
            const plotY = p
            const plotW = Math.max(1, w - p * 2)
            const plotH = Math.max(1, h - p - (p + root._legendReserve))

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

            const n = categories
            const groupSpacing = Math.max(0, root.barSpacing)
            const m = seriesList.length
            const availableW = Math.max(1, plotW - groupSpacing * (n - 1))
            const baseGroupW = availableW / n
            let innerSp = (m > 1) ? Math.max(0, root.seriesSpacing) : 0
            let barW = (baseGroupW - (m - 1) * innerSp) / m
            if (barW < 2) {
                barW = 2
                if (m > 1) innerSp = Math.max(0, (baseGroupW - m * barW) / (m - 1))
                else innerSp = 0
            }
            const groupW = m * barW + (m - 1) * innerSp
            const radius = Math.max(0, Math.min(root.cornerRadius, barW / 2))

            const roundRect = (x, y, ww, hh, rr) => {
                const r = Math.max(0, Math.min(rr, ww / 2, hh / 2))
                ctx.beginPath()
                ctx.moveTo(x + r, y)
                ctx.lineTo(x + ww - r, y)
                ctx.arcTo(x + ww, y, x + ww, y + r, r)
                ctx.lineTo(x + ww, y + hh - r)
                ctx.arcTo(x + ww, y + hh, x + ww - r, y + hh, r)
                ctx.lineTo(x + r, y + hh)
                ctx.arcTo(x, y + hh, x, y + hh - r, r)
                ctx.lineTo(x, y + r)
                ctx.arcTo(x, y, x + r, y, r)
                ctx.closePath()
            }

            for (let i = 0; i < n; i++) {
                const gx = plotX + i * (groupW + groupSpacing)
                for (let si = 0; si < m; si++) {
                    const s = seriesList[si]
                    const v = (i >= 0 && i < s.values.length) ? Math.max(0, Number(s.values[i])) : 0
                    const vv = Number.isFinite(v) ? v : 0
                    const barH = (vv / maxV) * plotH
                    const x = gx + si * (barW + innerSp)
                    const y = plotY + plotH - barH
                    let c = s.color
                    if (!seriesMode) {
                        c = (Array.isArray(root.barColors) && root.barColors.length > 0)
                            ? root.barColors[i % root.barColors.length]
                            : root.barColor
                    }
                    ctx.fillStyle = c
                    roundRect(x, y, barW, barH, radius)
                    ctx.fill()
                }
            }

            if (root._hoverActive && root._hoverIndex >= 0) {
                const i = root._hoverIndex
                const si = seriesMode ? root._hoverSeriesIndex : 0
                const s = seriesList[Math.max(0, Math.min(m - 1, si))]
                const v0 = (i >= 0 && i < s.values.length) ? Math.max(0, Number(s.values[i])) : 0
                const vv0 = Number.isFinite(v0) ? v0 : 0
                const barH = (vv0 / maxV) * plotH
                const gx = plotX + i * (groupW + groupSpacing)
                const x = gx + si * (barW + innerSp)
                const y = plotY + plotH - barH
                const cx = gx + groupW / 2

                ctx.save()

                if (root.crosshairEnabled) {
                    if (ctx.setLineDash) ctx.setLineDash([4, 4])
                    ctx.strokeStyle = Qt.rgba(root.crosshairColor.r, root.crosshairColor.g, root.crosshairColor.b, 0.65)
                    ctx.lineWidth = 1
                    ctx.beginPath()
                    ctx.moveTo(cx, plotY)
                    ctx.lineTo(cx, plotY + plotH)
                    ctx.stroke()
                    if (ctx.setLineDash) ctx.setLineDash([])
                }

                ctx.fillStyle = Qt.rgba(root.highlightColor.r, root.highlightColor.g, root.highlightColor.b, 0.14)
                roundRect(x, y, barW, barH, radius)
                ctx.fill()

                ctx.restore()
            }

            ctx.restore()

            ctx.fillStyle = root.labelColor
            ctx.font = root._ctxFont(Theme.typography.labelSmall.size, Theme.typography.labelSmall.family)
            ctx.textAlign = "left"
            ctx.textBaseline = "top"
            ctx.fillText(String(Math.round(maxV)), plotX, plotY)
            ctx.textBaseline = "bottom"
            ctx.fillText("0", plotX, plotY + plotH)
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
            const seriesMode = root._isMultiSeries()
            let categories = 0
            for (let si = 0; si < seriesList.length; si++) categories = Math.max(categories, seriesList[si].values.length)
            if (categories === 0) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    root._hoverSeriesIndex = -1
                    canvas.requestPaint()
                }
                return
            }

            const p = Math.max(0, root.padding)
            const plotX = p
            const plotY = p
            const plotW = Math.max(1, root.width - p * 2)
            const plotH = Math.max(1, root.height - p - (p + root._legendReserve))
            const inside = mouse.x >= plotX && mouse.x <= plotX + plotW && mouse.y >= plotY && mouse.y <= plotY + plotH
            if (!inside) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    root._hoverSeriesIndex = -1
                    canvas.requestPaint()
                }
                return
            }

            const n = categories
            const groupSpacing = Math.max(0, root.barSpacing)
            const m = seriesList.length
            const availableW = Math.max(1, plotW - groupSpacing * (n - 1))
            const baseGroupW = availableW / n
            let innerSp = (m > 1) ? Math.max(0, root.seriesSpacing) : 0
            let barW = (baseGroupW - (m - 1) * innerSp) / m
            if (barW < 2) {
                barW = 2
                if (m > 1) innerSp = Math.max(0, (baseGroupW - m * barW) / (m - 1))
                else innerSp = 0
            }
            const groupW = m * barW + (m - 1) * innerSp

            const idx = root._clamp(Math.floor((mouse.x - plotX) / (groupW + groupSpacing)), 0, n - 1)
            const gx = plotX + idx * (groupW + groupSpacing)
            const inGroup = mouse.x >= gx && mouse.x <= gx + groupW
            if (!inGroup) {
                if (root._hoverActive) {
                    root._hoverActive = false
                    root._hoverIndex = -1
                    root._hoverSeriesIndex = -1
                    canvas.requestPaint()
                }
                return
            }

            let sIdx = 0
            if (seriesMode) {
                sIdx = root._clamp(Math.floor((mouse.x - gx) / (barW + innerSp)), 0, m - 1)
                const sx0 = gx + sIdx * (barW + innerSp)
                const inBar = mouse.x >= sx0 && mouse.x <= sx0 + barW
                if (!inBar) {
                    if (root._hoverActive) {
                        root._hoverActive = false
                        root._hoverIndex = -1
                        root._hoverSeriesIndex = -1
                        canvas.requestPaint()
                    }
                    return
                }
            }

            const s = seriesList[sIdx]
            const v = (idx >= 0 && idx < s.values.length) ? s.values[idx] : undefined

            root._hoverActive = true
            root._hoverIndex = idx
            root._hoverSeriesIndex = sIdx
            root._hoverX = gx + groupW / 2
            root._hoverValue = (v !== undefined) ? v : 0
            root._hoverLabel = (Array.isArray(root.labels) && root.labels.length > idx) ? String(root.labels[idx]) : String(idx + 1)
            canvas.requestPaint()
        }

        onExited: {
            if (root._hoverActive) {
                root._hoverActive = false
                root._hoverIndex = -1
                root._hoverSeriesIndex = -1
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
                if (!root._isMultiSeries()) return root._hoverLabel + ": " + root._hoverValue
                const list = root._seriesList()
                const s = list[Math.max(0, Math.min(list.length - 1, root._hoverSeriesIndex))]
                return root._hoverLabel + "\n" + s.name + ": " + root._hoverValue
            }
        }
    }

    Item {
        id: legendArea
        visible: root.legendEnabled
        z: 9

        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.margins: 10

        width: parent.width - 20
        height: Math.min(64, legendFlow.implicitHeight + 16)

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
                model: root._isMultiSeries()
                    ? root._seriesList()
                    : Math.min((Array.isArray(root.values) ? root.values.length : 0), 12)

                delegate: Row {
                    spacing: 6
                    Rectangle {
                        width: 10
                        height: 10
                        radius: 5
                        color: root._isMultiSeries()
                            ? modelData.color
                            : ((Array.isArray(root.barColors) && root.barColors.length > 0)
                                ? root.barColors[index % root.barColors.length]
                                : root.barColor)
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: root._isMultiSeries()
                            ? modelData.name
                            : ((Array.isArray(root.labels) && root.labels.length > index) ? String(root.labels[index]) : String(index + 1))
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
    onBarColorsChanged: canvas.requestPaint()
    onSeriesChanged: canvas.requestPaint()
    onSeriesNameChanged: canvas.requestPaint()
    onPaddingChanged: canvas.requestPaint()
    onBarSpacingChanged: canvas.requestPaint()
    onSeriesSpacingChanged: canvas.requestPaint()
    onCornerRadiusChanged: canvas.requestPaint()
    onGridLinesChanged: canvas.requestPaint()
    onCrosshairEnabledChanged: canvas.requestPaint()
    onTooltipEnabledChanged: canvas.requestPaint()
    onLegendEnabledChanged: canvas.requestPaint()
    onBarColorChanged: canvas.requestPaint()
    onGridColorChanged: canvas.requestPaint()
    onAxisColorChanged: canvas.requestPaint()
    onLabelColorChanged: canvas.requestPaint()
    onCrosshairColorChanged: canvas.requestPaint()
    onHighlightColorChanged: canvas.requestPaint()
}
