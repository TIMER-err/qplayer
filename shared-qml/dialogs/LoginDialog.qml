import QtQuick
import QtQuick.Layouts
import miuix.Core

// Three login paths share the same transactional Cookie importer in
// PlayerController: QR, the official site in a system WebView, and a manual
// Cookie-header fallback. Network and credential persistence stay off-render.
Item {
    id: dialog

    property bool active: false
    property int loginMode: 0 // 0 QR, 1 official website, 2 pasted Cookie, 3 app
    property bool ready: false
    property string cookieText: ""
    property var successRevision: player.webLoginSuccessRevision
    signal closed()

    function startCurrentMode() {
        if (!player.pluginLoginActive) return;
        if (loginMode === 0) {
            ready = false;
            player.startQrLogin();
            revealTimer.restart();
        } else if (loginMode === 3) {
            player.startAppLogin();
        }
    }

    onActiveChanged: {
        if (active) {
            player.clearWebLoginError();
            var next = player.pluginAppLoginAvailable ? 3
                        : (player.pluginLoginActive && !player.pluginQrLoginAvailable
                           ? (player.webLoginAvailable ? 1 : 2) : 0);
            cookieText = "";
            ready = false;
            if (loginMode === next) dialog.startCurrentMode();
            else loginMode = next;
            revealTimer.restart();
            loginSheet.open();
        } else if (loginSheet.opened) loginSheet.close();
    }
    onLoginModeChanged: {
        player.clearWebLoginError();
        if (active) dialog.startCurrentMode();
    }
    onSuccessRevisionChanged: if (active) dialog.closed()

    function statusText(code) {
        if (code === 0) return i18n.t("login.qr.loading");
        if (code === 802) return dialog.loginMode === 3
                    ? i18n.t("login.app.confirm") : i18n.t("login.qr.confirm");
        if (code === 803) return i18n.t("login.qr.done");
        if (code === 800) return player.webLoginError.length > 0
                    ? player.webLoginError : i18n.t("login.qr.expired");
        if (dialog.loginMode === 3) return i18n.t("login.app.waiting");
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
        running: dialog.active && player.pluginLoginActive
                 && (dialog.loginMode === 0 || dialog.loginMode === 3)
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
            width: loginSheet.contentWidth
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
                    if (player.pluginAppLoginAvailable) out.push(3)
                    if (player.pluginQrLoginAvailable) out.push(0)
                    if (player.webLoginAvailable) out.push(1)
                    if (player.pluginCredentialLoginAvailable) out.push(2)
                    return out
                }
                tabs: {
                    var app = player.loginAppLabel.length > 0
                              ? player.loginAppLabel : i18n.t("login.mode.app")
                    var labels = [i18n.t("login.mode.qr"), i18n.t("login.mode.web"), "Cookie", app]
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
                            visible: dialog.active && !qrCanvas.visible && !qrImg.visible
                        }
                        // Some providers' QR encodes a token that only exists inside an
                        // image their own endpoint returns (see qrImagePath) — shown
                        // as-is instead of the host re-encoding qrContent into a matrix.
                        Image {
                            id: qrImg
                            objectName: "loginQrImage"
                            anchors.centerIn: parent
                            width: Math.max(0, parent.width - 20); height: width
                            fillMode: Image.PreserveAspectFit
                            visible: player.qrImagePath.length > 0 && width > 0
                            source: visible ? player.qrImagePath : ""
                        }
                        Canvas {
                            id: qrCanvas
                            objectName: "loginQrCanvas"
                            anchors.centerIn: parent
                            width: Math.max(0, parent.width - 20); height: width
                            visible: !qrImg.visible
                                     && dialog.ready && dialog.qr && dialog.qr.length > 0 && width > 0
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
                        color: player.qrStatus === 800 && player.webLoginError.length > 0
                               ? Theme.color.error : Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Button {
                        visible: player.loginAppOpenAvailable
                        width: parent.width
                        type: "filled"
                        icon: "open_in_new"
                        text: player.loginAppButtonLabel
                        onClicked: player.openLoginApp()
                    }
                    Button {
                        visible: player.qrStatus === 800
                        width: parent.width
                        type: "outlined"
                        text: i18n.t("login.qr.retry")
                        onClicked: player.startQrLogin()
                    }

            }

            ColumnLayout {
                Layout.fillWidth: true
                visible: player.pluginLoginActive && dialog.loginMode === 3

                    spacing: 16
                    Text {
                        Layout.fillWidth: true
                        text: player.loginAppInstructions
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Button {
                        Layout.alignment: Qt.AlignHCenter
                        type: "filled"
                        icon: "open_in_new"
                        text: player.qrStatus === 0
                              ? i18n.t("login.qr.loading") : player.loginAppButtonLabel
                        enabled: player.loginAppOpenAvailable
                        onClicked: player.openLoginApp()
                    }
                    Text {
                        Layout.fillWidth: true
                        text: dialog.statusText(player.qrStatus)
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: player.qrStatus === 800 && player.webLoginError.length > 0
                               ? Theme.color.error : Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Button {
                        Layout.alignment: Qt.AlignHCenter
                        visible: player.qrStatus === 800
                        type: "outlined"
                        text: i18n.t("login.qr.retry")
                        onClicked: player.startAppLogin()
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
