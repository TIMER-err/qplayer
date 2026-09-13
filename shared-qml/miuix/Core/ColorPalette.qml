import QtQuick
import miuix.Core

// Ports miuix basic/ColorPalette.kt: an HSV grid (hue columns plus an optional
// gray column) with a ring indicator and the shared alpha slider underneath.
Item {
    id: colorPaletteRoot

    property color color: Theme.color.primary
    property int rows: 7
    property int hueColumns: 12
    property bool includeGrayColumn: true
    property bool showPreview: true
    property real cornerRadius: 16
    property real indicatorRadius: 10
    property real gridHeight: 180
    property real spacing: 12
    signal colorSelected(color newColor)

    property int _selectedRow: 0
    property int _selectedCol: 0
    property real _alpha: 1
    property bool _syncing: false
    property real _pressX: 0
    property real _pressY: 0
    property bool _picking: false

    readonly property int _totalColumns: hueColumns + (includeGrayColumn ? 1 : 0)

    implicitWidth: 320
    implicitHeight: layout.height

    function _hsvColor(h, s, v, a) {
        var hh = ((h % 360) + 360) % 360 / 60
        var c = v * s
        var x = c * (1 - Math.abs((hh % 2) - 1))
        var m = v - c
        var r = 0, g = 0, b = 0
        if (hh < 1) { r = c; g = x }
        else if (hh < 2) { r = x; g = c }
        else if (hh < 3) { g = c; b = x }
        else if (hh < 4) { g = x; b = c }
        else if (hh < 5) { r = x; b = c }
        else { r = c; b = x }
        return Qt.rgba(r + m, g + m, b + m, a)
    }

    function _toHsv(value) {
        var r = value.r, g = value.g, b = value.b
        var max = Math.max(r, g, b)
        var min = Math.min(r, g, b)
        var d = max - min
        var h = 0
        if (d > 0) {
            if (max === r) h = 60 * ((((g - b) / d) % 6 + 6) % 6)
            else if (max === g) h = 60 * ((b - r) / d + 2)
            else h = 60 * ((r - g) / d + 4)
        }
        return { h: h, s: max === 0 ? 0 : d / max, v: max }
    }

    // buildRowSV: a fixed table for the default 7 rows, ramped otherwise.
    function _rowSV() {
        if (rows <= 1) return [{ s: 1, v: 1 }]
        if (rows === 7) {
            var sArr = [0.10, 0.35, 0.70, 1.00, 1.00, 1.00, 1.00]
            var vArr = [1.00, 1.00, 1.00, 0.85, 0.65, 0.45, 0.20]
            var fixed = []
            for (var i = 0; i < 7; i++) fixed.push({ s: sArr[i], v: vArr[i] })
            return fixed
        }
        var topBrightCut = Math.min(0.34, 2 / (rows - 1))
        var out = []
        for (var r = 0; r < rows; r++) {
            var t = r / (rows - 1)
            var sRamp = Math.max(0, Math.min(1, t / 0.35))
            var s = Math.max(0, Math.min(1, 0.10 + 0.90 * sRamp))
            var v = 1
            if (t > topBrightCut) {
                var k = Math.max(0, Math.min(1, (t - topBrightCut) / (1 - topBrightCut)))
                v = 1 + (0.20 - 1) * k
            }
            out.push({ s: s, v: v })
        }
        return out
    }

    function _grayV() {
        if (rows <= 1) return [1]
        var out = []
        for (var i = 0; i < rows; i++) out.push(1 - i / (rows - 1))
        return out
    }

    function _cellColor(col, row, alpha) {
        var rowSV = _rowSV()
        var cell = rowSV[Math.max(0, Math.min(rowSV.length - 1, row))]
        if (includeGrayColumn && col === _totalColumns - 1) {
            return _hsvColor(0, 0, _grayV()[Math.max(0, Math.min(rows - 1, row))], alpha)
        }
        var step = 360 / hueColumns
        return _hsvColor((col * step) % 360, cell.s, cell.v, alpha)
    }

    readonly property color _selectedColor: _cellColor(_selectedCol, _selectedRow, _alpha)

    function _emit() {
        _syncing = true
        color = _selectedColor
        _syncing = false
        colorSelected(_selectedColor)
    }

    function _select(row, col) {
        _selectedRow = Math.max(0, Math.min(rows - 1, row))
        _selectedCol = Math.max(0, Math.min(_totalColumns - 1, col))
        _emit()
    }

    function _adoptExternalColor() {
        if (_syncing) return
        var hsv = _toHsv(color)
        _alpha = color.a
        var isGray = includeGrayColumn && hsv.s < 0.05
        if (isGray) {
            _selectedCol = _totalColumns - 1
            var grays = _grayV()
            var bestGray = 0
            var bestGrayDelta = Number.POSITIVE_INFINITY
            for (var g = 0; g < grays.length; g++) {
                var dv = hsv.v - grays[g]
                if (dv * dv < bestGrayDelta) {
                    bestGrayDelta = dv * dv
                    bestGray = g
                }
            }
            _selectedRow = bestGray
            return
        }
        var k = (hsv.h % 360) / 360 * hueColumns
        _selectedCol = Math.max(0, Math.min(hueColumns - 1, Math.round(k)))
        var rowSV = _rowSV()
        var best = 0
        var bestDelta = Number.POSITIVE_INFINITY
        for (var i = 0; i < rowSV.length; i++) {
            var ds = hsv.s - rowSV[i].s
            var dvv = hsv.v - rowSV[i].v
            var d = ds * ds + dvv * dvv
            if (d < bestDelta) {
                bestDelta = d
                best = i
            }
        }
        _selectedRow = best
    }

    onColorChanged: _adoptExternalColor()
    onRowsChanged: grid.requestPaint()
    onHueColumnsChanged: grid.requestPaint()
    onIncludeGrayColumnChanged: grid.requestPaint()
    onWidthChanged: grid.requestPaint()
    onGridHeightChanged: grid.requestPaint()
    Component.onCompleted: _adoptExternalColor()

    Column {
        id: layout
        width: parent.width
        spacing: colorPaletteRoot.spacing

        Rectangle {
            width: parent.width
            height: 26
            radius: 13
            visible: colorPaletteRoot.showPreview
            color: colorPaletteRoot._selectedColor
        }

        Item {
            id: gridArea
            width: parent.width
            height: colorPaletteRoot.gridHeight

            // The rounded corners are clipped inside the canvas: wrapping it in a
            // layer.effect mask left the grid unpainted on device.
            Canvas {
                id: grid
                anchors.fill: parent

                onPaint: {
                    const ctx = getContext("2d")
                    const w = width
                    const h = height
                    ctx.clearRect(0, 0, w, h)
                    if (w <= 0 || h <= 0) return

                    const r = Math.min(colorPaletteRoot.cornerRadius, Math.min(w, h) / 2)
                    ctx.save()
                    ctx.beginPath()
                    ctx.moveTo(r, 0)
                    ctx.arcTo(w, 0, w, h, r)
                    ctx.arcTo(w, h, 0, h, r)
                    ctx.arcTo(0, h, 0, 0, r)
                    ctx.arcTo(0, 0, w, 0, r)
                    ctx.clip()

                    const totalColumns = colorPaletteRoot._totalColumns
                    const rowCount = colorPaletteRoot.rows
                    for (let row = 0; row < rowCount; row++) {
                        const top = (row * h) / rowCount
                        const bottom = ((row + 1) * h) / rowCount
                        for (let c = 0; c < totalColumns; c++) {
                            const start = (c * w) / totalColumns
                            const end = ((c + 1) * w) / totalColumns
                            ctx.fillStyle = colorPaletteRoot._cellColor(c, row, 1)
                            ctx.fillRect(start, top, end - start, bottom - top)
                        }
                    }
                    ctx.restore()
                }
            }

            Item {
                width: colorPaletteRoot.indicatorRadius * 2
                height: colorPaletteRoot.indicatorRadius * 2
                x: (colorPaletteRoot._selectedCol + 0.5) * gridArea.width / colorPaletteRoot._totalColumns
                    - colorPaletteRoot.indicatorRadius
                y: (colorPaletteRoot._selectedRow + 0.5) * gridArea.height / colorPaletteRoot.rows
                    - colorPaletteRoot.indicatorRadius

                Rectangle {
                    anchors.fill: parent
                    anchors.margins: -1
                    radius: width / 2
                    color: "transparent"
                    border.width: 1
                    border.color: Qt.rgba(0, 0, 0, 0.25)
                }
                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: "transparent"
                    border.width: 3
                    border.color: "#ffffff"
                }
            }

            // A tap picks; dragging picks only once the gesture is horizontal. Pressing
            // alone must not change the color, or scrolling the page through the grid
            // repaints it -- the same rule the sliders follow.
            MouseArea {
            enabled: colorPaletteRoot.enabled
                id: gridArea_mouse
                anchors.fill: parent
                preventStealing: colorPaletteRoot._picking

                function _pick(mouseX, mouseY) {
                    var col = Math.floor(mouseX / gridArea.width * colorPaletteRoot._totalColumns)
                    var row = Math.floor(mouseY / gridArea.height * colorPaletteRoot.rows)
                    colorPaletteRoot._select(row, col)
                }

                onPressed: (mouse) => {
                    colorPaletteRoot._pressX = mouse.x
                    colorPaletteRoot._pressY = mouse.y
                    colorPaletteRoot._picking = false
                }
                onPositionChanged: (mouse) => {
                    if (!pressed) return
                    if (!colorPaletteRoot._picking) {
                        var dx = mouse.x - colorPaletteRoot._pressX
                        var dy = mouse.y - colorPaletteRoot._pressY
                        if (Math.abs(dx) < 8 || Math.abs(dx) <= Math.abs(dy)) return
                        colorPaletteRoot._picking = true
                    }
                    _pick(mouse.x, mouse.y)
                }
                onReleased: (mouse) => {
                    // A tap that never became a drag still selects.
                    if (!colorPaletteRoot._picking
                            && Math.abs(mouse.x - colorPaletteRoot._pressX) < 8
                            && Math.abs(mouse.y - colorPaletteRoot._pressY) < 8) {
                        _pick(mouse.x, mouse.y)
                    }
                    colorPaletteRoot._picking = false
                }
                onCanceled: colorPaletteRoot._picking = false
            }
        }

        ColorSlider {
            enabled: colorPaletteRoot.enabled
            width: parent.width
            height: 26
            checkerboard: true
            value: colorPaletteRoot._alpha
            startColor: colorPaletteRoot._cellColor(colorPaletteRoot._selectedCol, colorPaletteRoot._selectedRow, 0)
            endColor: colorPaletteRoot._cellColor(colorPaletteRoot._selectedCol, colorPaletteRoot._selectedRow, 1)
            onMoved: (newValue) => {
                colorPaletteRoot._alpha = newValue
                colorPaletteRoot._emit()
            }
        }
    }
}
