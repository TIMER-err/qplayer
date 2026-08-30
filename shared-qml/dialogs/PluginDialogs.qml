import QtQuick
import md3.Core
import "."

// App-wide plugin onboarding, install consent and removal confirmation. Keeping
// this in a separate component avoids inflating Main.qml's generated constructor
// past the JVM's 64 KiB method limit.
Item {
    id: root

    readonly property bool modalOpened: sourceSetupDialog.opened
                                        || pluginListDialog.opened
                                        || pluginWarningDialog.opened
                                        || pluginRemovalDialog.opened

    function handleBack() {
        if (pluginListDialog.opened) {
            pluginListDialog.close()
            return true
        }
        return root.modalOpened
    }

    SourceSetupDialog { id: sourceSetupDialog }
    PluginListDialog { id: pluginListDialog }

    Dialog {
        id: pluginWarningDialog
        title: player.pendingPluginTrusted ? "安装音源插件" : "安装未验证的插件？"
        icon: player.pendingPluginTrusted ? "verified_user" : "warning"
        closeOnScrim: false
        acceptText: player.pluginInstallBusy ? "正在安装…"
                    : (player.pendingPluginTrusted ? "安装" : "了解风险并安装")
        rejectText: "取消"
        text: player.pendingPluginName + " " + player.pendingPluginVersion
              + "\n插件 ID：" + player.pendingPluginId
              + "\n请求权限：" + player.pendingPluginPermissions
              + (player.pendingPluginTrusted ? "\n\n该插件包已通过受信发布者签名验证。"
                 : "\n\n该插件的发布者签名未被 QPlayer 信任。插件包含可执行 JavaScript/QML，可能读取获准的数据并代表您操作播放器。仅在信任其来源时继续。")
        onAccepted: player.confirmPendingPluginInstall()
        onRejected: player.cancelPendingPluginInstall()
    }

    Dialog {
        id: pluginRemovalDialog
        title: "移除音源插件？"
        icon: "delete"
        closeOnScrim: false
        acceptText: player.pluginInstallBusy ? "正在移除…" : "移除"
        rejectText: "取消"
        text: "将移除 " + player.pendingPluginRemovalName
              + " 的可执行插件文件。加密登录凭据和插件数据会保留，重新安装后可继续使用。"
        onAccepted: player.confirmSourcePluginRemoval()
        onRejected: player.cancelSourcePluginRemoval()
    }

    Timer {
        id: sourceSetupOpenTimer
        interval: 1
        repeat: false
        onTriggered: sourceSetupDialog.open()
    }

    property real sourceSetupWatch: player.sourceSetupRevision
    onSourceSetupWatchChanged: {
        if (player.sourceSetupRevision > 0) sourceSetupOpenTimer.restart()
    }

    property real pluginInstallPromptWatch: player.pluginInstallPromptRevision
    onPluginInstallPromptWatchChanged: {
        if (player.pluginInstallPromptRevision > 0) pluginWarningDialog.open()
    }

    property real pluginRemovalPromptWatch: player.pluginRemovalPromptRevision
    onPluginRemovalPromptWatchChanged: {
        if (player.pluginRemovalPromptRevision > 0) pluginRemovalDialog.open()
    }

    property real pluginManagerWatch: player.pluginManagerRevision
    onPluginManagerWatchChanged: {
        if (player.pluginManagerRevision > 0) pluginListDialog.open()
    }

    Component.onCompleted: {
        if (player.sourceSetupPending) sourceSetupOpenTimer.restart()
    }
}
