import QtQuick
import QtQuick.Layouts
import md3.Core

// Netease QR login. All network work is async on the controller: startQrLogin()
// mints the key + matrix off-thread into player.qrImage, and pollQrLogin() polls
// the scan status into player.qrStatus — nothing blocks the render thread.
Rectangle {
    id: dialog

    property bool active: false
    signal closed()

    anchors.fill: parent
    opacity: active ? 1 : 0
    visible: opacity > 0.01
    color: "#99000000"
    Behavior on opacity { NumberAnimation { duration: 150 } }

    // Defer drawing the QR until the open animation finishes, so the one-off
    // matrix paint can't hitch the scale/fade. Until then only the loading
    // indicator shows.
    property bool ready: false
    onActiveChanged: {
        if (active) { ready = false; player.startQrLogin(); revealTimer.restart(); }
    }
    Timer {
        id: revealTimer
        interval: 280
        onTriggered: { dialog.ready = true; canvas.requestPaint(); }
    }

    function statusText(code) {
        if (code === 0) return "正在获取二维码…";
        if (code === 802) return "已扫码，请在手机上确认";
        if (code === 803) return "登录成功";
        if (code === 800) return "二维码已过期，正在刷新…";
        return "请用网易云音乐 App 扫码";
    }

    // redraw when the matrix arrives (only once revealed); close on success
    property var qr: player.qrImage
    onQrChanged: if (ready) canvas.requestPaint()
    property int st: player.qrStatus
    onStChanged: if (st === 803) dialog.closed()

    MouseArea { anchors.fill: parent }   // swallow scrim taps

    Timer {
        interval: 800
        repeat: true
        running: dialog.active
        onTriggered: player.pollQrLogin()
    }

    Rectangle {
        anchors.centerIn: parent
        width: 300
        height: 400
        radius: 24
        color: Theme.color.surfaceContainerHigh
        scale: dialog.active ? 1 : 0.9
        Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 16

            Text {
                Layout.alignment: Qt.AlignHCenter
                text: "扫码登录网易云"
                color: Theme.color.onSurfaceColor
                fontSize: 20
            }

            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 220; height: 220; radius: 12
                color: "#ffffff"

                // Loading spinner while the QR isn't shown yet. Now crisp: the
                // engine backs canvases at device resolution.
                CircularProgress {
                    anchors.centerIn: parent
                    width: 48; height: 48
                    indeterminate: true
                    visible: dialog.active && !canvas.visible
                }
                Canvas {
                    id: canvas
                    anchors.centerIn: parent
                    width: 200; height: 200
                    visible: dialog.ready && dialog.qr.length > 0
                    onPaint: {
                        var ctx = getContext("2d");
                        // Stay transparent until the QR is ready so the spinner
                        // beneath shows through (the white box is the Rectangle).
                        if (!dialog.ready) return;
                        var m = dialog.qr;
                        if (!m || m.length <= 0) return;
                        ctx.fillStyle = "#ffffff";
                        ctx.fillRect(0, 0, width, height);
                        var n = m.length;
                        var cell = width / n;
                        ctx.fillStyle = "#000000";
                        for (var y = 0; y < n; y++) {
                            var row = m[y];
                            for (var x = 0; x < n; x++) {
                                if (row[x])
                                    ctx.fillRect(Math.floor(x * cell), Math.floor(y * cell),
                                                 Math.ceil(cell), Math.ceil(cell));
                            }
                        }
                    }
                }
            }

            Text {
                Layout.alignment: Qt.AlignHCenter
                Layout.fillWidth: true
                text: dialog.statusText(player.qrStatus)
                horizontalAlignment: Text.AlignHCenter
                color: Theme.color.onSurfaceVariantColor
                fontSize: 14
            }

            Item { Layout.fillHeight: true }

            Button {
                Layout.alignment: Qt.AlignHCenter
                type: "text"; text: "取消"
                onClicked: dialog.closed()
            }
        }
    }
}
