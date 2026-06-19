import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// Phone shell: TopAppBar + paged content + mini player + bottom navigation,
// with a playlist-detail overlay, QR login dialog, a Snackbar for transient
// messages and the debug log on top.
Rectangle {
    id: app
    color: Theme.color.surface

    property int page: 0
    property int nextPage: 0
    property bool detailOpen: false
    property bool loginOpen: false
    property bool settingsOpen: false
    property bool accountOpen: false
    property bool showLog: false

    property var titles: ["推荐", "搜索", "我的", "最近", "本地"]

    // Rebuild the debug log string only while it's actually shown (its set() forces a
    // full relayout, which periodically stuttered the scene when always rebuilt).
    onShowLogChanged: player.setLogVisible(app.showLog)

    // isDarkTheme follows the settings policy. seedColor (Monet) is driven from
    // PlayerController in Java -- a QML Binding on StyleManager.seedColor would not
    // re-fire when the cover seed changed.
    Binding {
        target: StyleManager; property: "isDarkTheme"
        value: settings.resolvedDark
    }

    // System back press (hardware + gesture): the host bumps player.backTick; pop the
    // topmost open overlay/page, and only ask the host to exit when nothing is open.
    property int backTick: player.backTick
    onBackTickChanged: app.handleBack()

    function handleBack() {
        // Order = top-most layer first. The lyric page (host-drawn) and the queue
        // sit above the QML overlays, so they must close before settings/login/log.
        if (player.lyricsOpen)      { player.setLyricsOpen(false); return; }
        if (player.queueOpen)       { player.setQueueOpen(false); return; }
        if (app.showLog)            { app.showLog = false; return; }
        if (app.loginOpen)          { app.loginOpen = false; return; }
        if (app.accountOpen)        { app.accountOpen = false; return; }
        if (app.settingsOpen)       { app.settingsOpen = false; return; }
        if (app.detailOpen)         { app.detailOpen = false; return; }
        if (app.page !== 0)         { app.switchTo(0); return; }
        player.requestExit();
    }

    // MD3 fade-through page switch: fade the content out, swap, fade it back in.
    function switchTo(idx) {
        app.detailOpen = false;          // dismiss any open playlist detail
        app.settingsOpen = false;        // and the settings overlay
        app.accountOpen = false;         // and the account overlay
        player.setQueueOpen(false);      // and the queue overlay
        if (idx === app.page) return;
        app.nextPage = idx;
        if (idx === 2) player.loadMyPlaylists();
        else if (idx === 3) player.loadRecent();
        pageAnim.restart();
    }

    // Driven by pageAnim; pageBody binds y + opacity to these.
    property real pageOpacity: 1
    property real pageShift: 0

    SequentialAnimation {
        id: pageAnim
        NumberAnimation {
            target: app; property: "pageOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction { onTrigger: { app.page = app.nextPage; app.pageShift = 28 } }
        ParallelAnimation {
            NumberAnimation {
                target: app; property: "pageOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: app; property: "pageShift"; from: 28; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // surface player toasts in a Snackbar
    property string toastWatch: player.toast
    onToastWatchChanged: if (player.toast.length > 0) { snack.text = player.toast; snack.open() }

    // Chrome is absolute/anchor-positioned, NOT a ColumnLayout. The play clock
    // sets player.positionMs ~5x/s; each set bumps the engine change version and
    // forces a whole-tree settleLayout that frame (and on coinciding scroll
    // frames). Layout containers in the always-visible chrome re-ran their
    // measure/fill passes every one of those ticks; anchors keep it cheap.
    TopAppBar {
        id: topBar
        anchors.top: parent.top
        anchors.topMargin: settings.topInset   // clear the status bar (edge-to-edge)
        anchors.left: parent.left
        anchors.right: parent.right
        height: 64
        title: app.titles[app.page]
        showNavigationIcon: false

        IconButton {
            type: "standard"
            icon: "queue_music"
            onClicked: player.setQueueOpen(true)
        }
        IconButton {
            type: "standard"
            icon: player.loggedIn ? "account_circle" : "login"
            onClicked: if (player.loggedIn) app.accountOpen = true; else app.loginOpen = true
        }
        IconButton {
            type: "standard"
            icon: "settings"
            onClicked: app.settingsOpen = true
        }
        IconButton {
            type: "standard"
            icon: "bug_report"
            onClicked: app.showLog = !app.showLog
        }
    }

    // content region. pageBody is positioned by y (not anchors) so the
    // switch can rise it up; pageWrap clips the overshoot.
    Item {
        id: pageWrap
        anchors.top: topBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: mini.top
        clip: true

        Item {
            id: pageBody
            width: parent.width
            height: parent.height
            y: app.pageShift
            opacity: app.pageOpacity

            // Pages stacked + toggled by visibility (was a StackLayout). The
            // engine doesn't recurse into an invisible child's subtree during
            // measure, so only the current page is laid out each frame.
            HomePage {
                id: home
                anchors.fill: parent
                visible: app.page === 0
                onOpenPlaylist: { player.openPlaylist(home.pendingPlaylist.id); app.detailOpen = true }
            }
            SearchPage {
                anchors.fill: parent
                visible: app.page === 1
            }
            LibraryPage {
                id: library
                anchors.fill: parent
                visible: app.page === 2
                onOpenPlaylist: { player.openPlaylist(library.pendingPlaylist.id); app.detailOpen = true }
                onRequestLogin: app.loginOpen = true
            }
            RecentPage {
                anchors.fill: parent
                visible: app.page === 3
                onRequestLogin: app.loginOpen = true
            }
            LocalPage {
                anchors.fill: parent
                visible: app.page === 4
            }

            // Overlays animate in: detail drills in from the right, queue and
            // settings rise from below. Kept laid out only while opacity > 0 so a
            // closed overlay costs nothing per frame.
            PlaylistDetailPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.detailOpen ? 1 : 0
                x: app.detailOpen ? 0 : 36
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on x { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.detailOpen = false
            }

            QueuePage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: player.queueOpen ? 1 : 0
                y: player.queueOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: player.setQueueOpen(false)
            }

            SettingsPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.settingsOpen ? 1 : 0
                y: app.settingsOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.settingsOpen = false
            }

            AccountPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.accountOpen ? 1 : 0
                y: app.accountOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.accountOpen = false
            }
        }
    }

    MiniPlayer {
        id: mini
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: bottomNav.top
        height: 84
        onLyricsRequested: player.setLyricsOpen(true)
    }

    BottomNav {
        id: bottomNav
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        // Nav content sits in the top 76; the extra height is background that fills
        // behind the gesture/navigation bar (edge-to-edge).
        height: 76 + settings.bottomInset
        currentIndex: app.page
        onNavigate: app.switchTo(bottomNav.pendingIndex)
    }

    // Lyric page chrome (title / wavy progress / transport), over the host-drawn
    // fluid backdrop + lyrics. Slides up from the bottom in lockstep with the host
    // layer -- same smoothstep(player.lyricSlide) offset the host applies.
    LyricOverlay {
        objectName: "lyricChrome"   // host renders this subtree in its own pass, over the fluid
        width: parent.width
        height: parent.height
        visible: player.lyricSlide > 0.001
        y: {
            var s = player.lyricSlide;
            return (1 - s * s * (3 - 2 * s)) * height;
        }
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

    // New-version dialog: the host's startup check sets player.updateAvailable when a
    // newer GitHub release exists; we pop a dialog showing the release notes + an
    // update button that hands the APK url back to the host to download.
    Dialog {
        id: updateDialog
        title: "发现新版本"
        icon: "system_update"
        text: "新版本 " + player.updateVersion + " 现已发布"
        acceptText: "立即更新"
        rejectText: "稍后"
        onAccepted: player.openUpdateUrl()

        Flickable {
            width: parent.width
            height: Math.min(notesText.height, 260)
            contentHeight: notesText.height
            clip: true
            Text {
                id: notesText
                width: parent.width
                text: player.updateNotes
                color: Theme.color.onSurfaceVariantColor
                fontSize: 13
                wrapMode: Text.Wrap
            }
        }
    }

    property bool updateWatch: player.updateAvailable
    onUpdateWatchChanged: if (player.updateAvailable) updateDialog.open()

    Snackbar { id: snack }

    // --- debug log overlay ---------------------------------------------
    Rectangle {
        visible: app.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 8
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: "日志"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }
                IconButton { type: "standard"; icon: "delete"; onClicked: player.clearLog() }
                IconButton { type: "standard"; icon: "close"; onClicked: app.showLog = false }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.margins: 12
                clip: true
                contentHeight: logText.height
                Text {
                    id: logText
                    width: parent.width
                    text: player.logText
                    color: Theme.color.onSurfaceColor
                    fontSize: 12
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
