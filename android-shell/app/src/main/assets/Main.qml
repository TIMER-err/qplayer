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
    property bool showLog: false

    property var titles: ["推荐", "搜索", "我的", "最近", "本地"]

    // MD3 fade-through page switch: fade the content out, swap, fade it back in.
    function switchTo(idx) {
        app.detailOpen = false;          // dismiss any open playlist detail
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

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TopAppBar {
            Layout.fillWidth: true
            title: app.titles[app.page]
            showNavigationIcon: false

            IconButton {
                type: "standard"
                icon: player.loggedIn ? "account_circle" : "login"
                onClicked: if (!player.loggedIn) app.loginOpen = true
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
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true

            Item {
                id: pageBody
                width: parent.width
                height: parent.height
                y: app.pageShift
                opacity: app.pageOpacity

                StackLayout {
                    anchors.fill: parent
                    currentIndex: app.page

                    HomePage {
                        id: home
                        onOpenPlaylist: { player.openPlaylist(home.pendingPlaylist.id); app.detailOpen = true }
                    }
                    SearchPage {}
                    LibraryPage {
                        id: library
                        onOpenPlaylist: { player.openPlaylist(library.pendingPlaylist.id); app.detailOpen = true }
                        onRequestLogin: app.loginOpen = true
                    }
                    RecentPage {
                        onRequestLogin: app.loginOpen = true
                    }
                    LocalPage {}
                }

                PlaylistDetailPage {
                    anchors.fill: parent
                    visible: app.detailOpen
                    onBack: app.detailOpen = false
                }
            }
        }

        MiniPlayer { Layout.fillWidth: true }

        BottomNav {
            id: bottomNav
            Layout.fillWidth: true
            currentIndex: app.page
            onNavigate: app.switchTo(bottomNav.pendingIndex)
        }
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

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
