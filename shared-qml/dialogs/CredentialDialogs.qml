import QtQuick
import miuix.Core
import "."

// Credential-protection and graphics-fallback modals, plus the timers that
// sequence them. Kept out of Main.qml for the same reason PluginDialogs is:
// every binding here would otherwise land in Main's generated constructor,
// which qml4j compiles into a single method bounded by the JVM's 64 KiB limit.
Item {
    id: root

    // Asks the shell to open the login sheet; this file owns no navigation.
    signal requestLogin()

    // An unreadable credential envelope requires an explicit decision, so back
    // must not dismiss these.
    readonly property bool modalOpened:
        (credentialNoticeDialog.opened && player.credentialNoticeType === 3)
        || credentialFallbackConfirmDialog.opened
        || credentialReloginUnavailableDialog.opened

    function handleBack() {
        return root.modalOpened
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: graphicsFallbackDialog
        title: i18n.t("graphics.fallback.title")
        icon: "warning"
        text: i18n.t("graphics.fallback.body")
        acceptText: i18n.t("common.gotIt")
        showRejectButton: false
        Component.onCompleted: {
            if (settings.graphicsFallbackNotice) graphicsFallbackDialog.open()
        }
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: credentialNoticeDialog
        title: player.credentialNoticeType === 1
            ? i18n.t("credential.notice.enabled")
            : (player.credentialNoticeType === 2
                ? i18n.t("credential.notice.unavailable")
                  : i18n.t("credential.notice.unreadable"))
        icon: player.credentialNoticeType === 1 ? "verified_user" : "warning"
        text: player.credentialNoticeType === 1
            ? i18n.t("credential.body.enabled")
            : (player.credentialNoticeType === 2
                ? i18n.t("credential.body.fallback")
                : i18n.t("credential.body.locked"))
        rejectText: i18n.t("credential.action.fallback")
        rejectIcon: player.credentialNoticeType === 3 ? "warning" : ""
        showRejectButton: player.credentialNoticeType === 3
        neutralText: i18n.t("credential.action.reLogin")
        showNeutralButton: player.credentialNoticeType === 3
        closeOnScrim: player.credentialNoticeType !== 3
        acceptText: i18n.t(player.credentialNoticeType === 3 ? "common.retry" : "common.gotIt")
        onAccepted: {
            if (player.credentialNoticeType === 3) player.retryCredentialUnlock()
        }
        onRejected: {
            if (player.credentialNoticeType === 3) {
                fallbackConfirmOpenTimer.restart()
            }
        }
        onNeutral: {
            if (player.credentialNoticeType === 3) player.prepareEncryptedRelogin()
        }
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: credentialReloginUnavailableDialog
        title: i18n.t("credential.keystore.title")
        icon: "warning"
        text: i18n.t("credential.keystore.body")
        acceptText: i18n.t("common.back")
        showRejectButton: false
        closeOnScrim: false
        onAccepted: credentialNoticeRestoreTimer.restart()
    }

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: credentialFallbackConfirmDialog
        title: i18n.t("credential.downgrade.title")
        icon: "warning"
        text: i18n.t("credential.downgrade.body")
        acceptText: i18n.t("credential.downgrade.accept")
        rejectText: i18n.t("common.cancel")
        closeOnScrim: false
        onAccepted: {
            if (player.fallbackCredentialsToOwnerOnly()) {
                fallbackLoginOpenTimer.restart()
            }
        }
        onRejected: credentialNoticeRestoreTimer.restart()
    }

    // Dialog emits accepted/rejected before its 100 ms exit animation finishes.
    // Delay the next modal so two full-screen scrims never race for the same root.
    Timer {
        id: fallbackConfirmOpenTimer
        interval: 130
        repeat: false
        onTriggered: credentialFallbackConfirmDialog.open()
    }
    Timer {
        id: credentialNoticeRestoreTimer
        interval: 130
        repeat: false
        onTriggered: credentialNoticeDialog.open()
    }
    Timer {
        id: fallbackLoginOpenTimer
        interval: 130
        repeat: false
        onTriggered: root.requestLogin()
    }
    Timer {
        id: encryptedReloginOpenTimer
        interval: 130
        repeat: false
        onTriggered: root.requestLogin()
    }
    Timer {
        id: encryptedReloginUnavailableOpenTimer
        interval: 130
        repeat: false
        onTriggered: credentialReloginUnavailableDialog.open()
    }

    property real credentialReloginWatch: player.credentialReloginRevision
    onCredentialReloginWatchChanged: {
        if (player.credentialReloginRevision <= 0) return
        if (player.credentialReloginResult === 1) encryptedReloginOpenTimer.restart()
        else encryptedReloginUnavailableOpenTimer.restart()
    }

    property real credentialNoticeWatch: player.credentialNoticeRevision
    onCredentialNoticeWatchChanged: {
        if (player.credentialNoticeRevision > 0) credentialNoticeDialog.open()
    }

    property bool graphicsFallbackWatch: settings.graphicsFallbackNotice
    onGraphicsFallbackWatchChanged: {
        if (settings.graphicsFallbackNotice) graphicsFallbackDialog.open()
    }
}
