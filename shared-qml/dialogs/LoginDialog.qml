import QtQuick
import QtQuick.Layouts
import miuix.Core

// Three login paths share the same transactional Cookie importer in
// PlayerController: QR, the official site in a system WebView, and a manual
// Cookie-header fallback. Network and credential persistence stay off-render.
Item {
    id: dialog

    property bool active: false
    property int loginMode: 0 // 0 QR, 1 official website, 2 pasted Cookie
    property bool ready: false
    property string cookieText: ""
    property var successRevision: player.webLoginSuccessRevision
    signal closed()

    onActiveChanged: {
        if (active) {
            player.clearWebLoginError();
            loginMode = player.pluginLoginActive && !player.pluginQrLoginAvailable
                        ? (player.webLoginAvailable ? 1 : 2) : 0;
            cookieText = "";
            ready = false;
            if (player.pluginLoginActive) player.startQrLogin();
            revealTimer.restart();
            loginSheet.open();
        } else if (loginSheet.opened) loginSheet.close();
    }
    onLoginModeChanged: {
        player.clearWebLoginError();
        if (active && loginMode === 0) {
            ready = false;
            player.startQrLogin();
            revealTimer.restart();
        }
    }
    onSuccessRevisionChanged: if (active) dialog.closed()

    function statusText(code) {
        if (code === 0) return i18n.t("login.qr.loading");
        if (code === 802) return i18n.t("login.qr.confirm");
        if (code === 803) return i18n.t("login.qr.done");
        if (code === 800) return i18n.t("login.qr.expired");
        return i18n.t("login.qr.scan", player.loginProviderName);
    }

    Timer {
        id: revealTimer
        interval: 280
        onTriggered: { dialog.ready = true; qrCanvas.requestPaint(); }
    }
    property var qr: player.qrImage
    onQrChanged: if (ready) qrCanvas.requestPaint()
    property int st: player.qrStatus
    onStChanged: if (st === 803) dialog.closed()


    Timer {
        interval: 800
        repeat: true
        running: dialog.active && player.pluginLoginActive && dialog.loginMode === 0
        onTriggered: player.pollQrLogin()
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: loginSheet
        title: i18n.t("login.title", player.loginProviderName)
        showAcceptButton: false
        showRejectButton: !player.webLoginBusy
        rejectText: i18n.t("common.cancel")
        closeOnScrim: false
        closeOnEscape: !player.webLoginBusy
        onClosed: { player.cancelWebLogin(); dialog.closed() }

        ColumnLayout {
            id: loginContent
            width: parent.width
            spacing: 14



            Text {
                Layout.fillWidth: true
                visible: !player.pluginLoginActive
                text: i18n.t("login.unsupported")
                wrapMode: Text.WordWrap
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
                color: Theme.color.onSurfaceVariantColor
                fontSize: 14
            }

            TabRowWithContour {
                visible: player.pluginLoginActive
                Layout.fillWidth: true
                equalWidth: false
                selectOnClick: false
                property var modes: {
                    var out = []
                    if (player.pluginQrLoginAvailable) out.push(0)
                    if (player.webLoginAvailable) out.push(1)
                    if (player.pluginCredentialLoginAvailable) out.push(2)
                    return out
                }
                tabs: {
                    var labels = [i18n.t("login.mode.qr"), i18n.t("login.mode.web"), "Cookie"]
                    var out = []
                    for (var i = 0; i < modes.length; i++) out.push(labels[modes[i]])
                    return out
                }
                selectedTabIndex: modes.indexOf(dialog.loginMode)
                onTabSelected: (index) => dialog.loginMode = modes[index]
            }

            Column {
                Layout.fillWidth: true
                width: parent.width
                visible: player.pluginLoginActive && dialog.loginMode === 0

                    spacing: 12

                    Rectangle {
                        // Give the QR a concrete square before the async matrix arrives.
                        // A preferred size based on a nested layout's width can collapse to zero.
                        width: Math.max(0, Math.min(220, loginContent.width))
                        height: width
                        x: (parent.width - width) / 2
                        radius: 12
                        color: "#ffffff"

                        CircularProgress {
                            anchors.centerIn: parent
                            width: 48; height: 48
                            indeterminate: true
                            visible: dialog.active && !qrCanvas.visible
                        }
                        Canvas {
                            id: qrCanvas
                            objectName: "loginQrCanvas"
                            anchors.centerIn: parent
                            width: Math.max(0, parent.width - 20); height: width
                            visible: dialog.ready && dialog.qr && dialog.qr.length > 0 && width > 0
                            onWidthChanged: requestPaint()
                            onPaint: {
                                if (!dialog.ready || width <= 0 || height <= 0) return;
                                var matrix = dialog.qr;
                                if (!matrix || matrix.length <= 0) return;
                                var ctx = getContext("2d");
                                ctx.fillStyle = "#ffffff";
                                ctx.fillRect(0, 0, width, height);
                                var size = matrix.length;
                                var cell = width / size;
                                ctx.fillStyle = "#000000";
                                for (var y = 0; y < size; y++) {
                                    var row = matrix[y];
                                    for (var x = 0; x < size; x++) {
                                        if (row[x]) ctx.fillRect(
                                            Math.floor(x * cell), Math.floor(y * cell),
                                            Math.ceil(cell), Math.ceil(cell));
                                    }
                                }
                            }
                        }
                    }

                    Text {
                        width: parent.width
                        text: dialog.statusText(player.qrStatus)
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }

            }

            ColumnLayout {
                Layout.fillWidth: true
                visible: player.pluginLoginActive && dialog.loginMode === 1

                    spacing: 16
                    Text {
                        Layout.fillWidth: true
                        text: player.loginWebInstructions
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Button {
                        Layout.alignment: Qt.AlignHCenter
                        type: "filled"
                        icon: "open_in_new"
                        text: i18n.t(player.webLoginBusy ? "login.web.waiting" : "login.web.open")
                        enabled: !player.webLoginBusy
                        onClicked: player.startWebLogin()
                    }
                    Text {
                        Layout.fillWidth: true
                        visible: player.webLoginError.length > 0
                        text: player.webLoginError
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.error
                        fontSize: 13
                    }

            }

            ColumnLayout {
                Layout.fillWidth: true
                visible: player.pluginLoginActive && dialog.loginMode === 2

                    spacing: 12
                    Text {
                        Layout.fillWidth: true
                        text: player.loginCredentialInstructions
                        wrapMode: Text.WordWrap
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 13
                    }
                    TextField {
                        Layout.fillWidth: true
                        type: "outlined"
                        label: player.loginCredentialLabel
                        isPassword: true
                        text: dialog.cookieText
                        errorText: player.webLoginError
                        onTextChanged: dialog.cookieText = text
                        onAccepted: if (text.length > 0 && !player.webLoginBusy)
                            player.submitCookieLogin(text)
                    }
                    Button {
                        Layout.fillWidth: true
                        type: "filled"
                        text: i18n.t(player.webLoginBusy ? "login.web.verifying" : "login.web.verify")
                        enabled: dialog.cookieText.trim().length > 0 && !player.webLoginBusy
                        onClicked: player.submitCookieLogin(dialog.cookieText)
                    }

            }


        }
    }
}
