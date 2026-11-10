import QtQuick
import miuix.Core
import design
import "."

// The featured "每日推荐" entry at the top of Home: an accent-washed panel with
// today's date, the title, a count line, a Play button and a fan of the first
// three song covers. Tapping the panel body asks the page to scroll to the
// list; Play starts the daily list from its first song.
Item {
    id: hero
    property bool compact: false
    property var songs
    property int songCount: 0
    signal playRequested()
    signal openRequested()

    readonly property real inset: compact ? Ui.space5 : Ui.space6
    readonly property real coverSize: compact ? 104 : Math.max(0, height - 2 * inset)
    readonly property real frontX: width - inset - coverSize
    readonly property real backX: frontX - 0.62 * coverSize
    readonly property real textWidth: Math.max(0, backX - Ui.space4 - inset)
    // 0..1: the cover fan opens slightly while the panel is hovered.
    property real spread: bodyArea.containsMouse ? 1 : 0
    Behavior on spread { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }

    function coverOf(i) {
        if (hero.songCount <= i || !hero.songs || !hero.songs[i]) return ""
        return hero.songs[i].coverThumbPath || hero.songs[i].coverUrl || ""
    }

    Rectangle {
        anchors.fill: parent
        radius: Ui.radiusLg
        color: Ui.surfaceRaised
    }
    Rectangle {
        anchors.fill: parent
        radius: Ui.radiusLg
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: Ui.accentAlpha(Ui.dark ? 0.40 : 0.26) }
            GradientStop { position: 1.0; color: Ui.accentAlpha(0.03) }
        }
    }
    Rectangle {
        anchors.fill: parent
        radius: Ui.radiusLg
        color: Ui.surfaceHover
        opacity: bodyArea.containsMouse ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
    }

    MouseArea {
        id: bodyArea
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: hero.openRequested()
    }

    // Cover fan, back to front. The two rear covers sit under a surface veil so
    // the front one reads as the lead.
    CoverImage {
        id: backCover
        visible: hero.songCount > 2
        width: Math.round(hero.coverSize * 0.74)
        height: width
        x: hero.backX - hero.spread * 0.08 * hero.coverSize
        y: (hero.height - height) / 2
        radius: Ui.radiusSm
        icon: "music_note"
        iconSize: 28
        source: hero.coverOf(2)
    }
    Rectangle {
        visible: backCover.visible
        x: backCover.x; y: backCover.y
        width: backCover.width; height: backCover.height
        radius: Ui.radiusSm
        color: Ui.surfaceAlpha(0.38)
    }
    CoverImage {
        id: midCover
        visible: hero.songCount > 1
        width: Math.round(hero.coverSize * 0.87)
        height: width
        x: hero.frontX - 0.33 * hero.coverSize - hero.spread * 0.04 * hero.coverSize
        y: (hero.height - height) / 2
        radius: Ui.radiusMd
        icon: "music_note"
        iconSize: 30
        source: hero.coverOf(1)
    }
    Rectangle {
        visible: midCover.visible
        x: midCover.x; y: midCover.y
        width: midCover.width; height: midCover.height
        radius: Ui.radiusMd
        color: Ui.surfaceAlpha(0.2)
    }
    CoverImage {
        objectName: "homeDailyHeroCover"
        width: hero.coverSize
        height: width
        x: hero.frontX
        y: (hero.height - height) / 2
        radius: Ui.radiusMd
        icon: "today"
        iconSize: 40
        source: hero.coverOf(0)
    }

    Item {
        x: hero.inset
        y: hero.inset
        width: hero.textWidth
        height: kicker.implicitHeight
        Glyph {
            id: kickerIcon
            y: (parent.height - size) / 2
            name: "today"
            size: 16
            color: Ui.accent
        }
        Text {
            id: kicker
            x: kickerIcon.width + Ui.space1
            width: Math.max(0, parent.width - x)
            text: i18n.t("home.daily.date", new Date().getMonth() + 1, new Date().getDate())
            color: Ui.accent
            elide: Text.ElideRight
            maximumLineCount: 1
            font.pixelSize: Ui.textCaption
            font.weight: Ui.weightBold
        }
    }

    Text {
        id: title
        objectName: "homeDailyHeroTitle"
        x: hero.inset
        y: hero.inset + kicker.implicitHeight + Ui.space1
        width: hero.textWidth
        text: i18n.t("home.dailyRecommend")
        color: Ui.textPrimary
        elide: Text.ElideRight
        maximumLineCount: 1
        font.pixelSize: hero.compact ? Ui.textTitle2 + 2 : Ui.textTitle1 + 2
        font.weight: Ui.weightBold
    }
    Text {
        x: hero.inset
        y: title.y + title.implicitHeight + Ui.space1
        width: hero.textWidth
        // The compact panel only has room for the count.
        text: hero.compact ? i18n.t("common.songCount", hero.songCount)
                           : i18n.t("home.daily.caption", hero.songCount)
        color: Ui.textSecondary
        wrapMode: Text.Wrap
        maximumLineCount: 2
        elide: Text.ElideRight
        font.pixelSize: hero.compact ? Ui.textCaption : Ui.textCallout
    }

    Button {
        objectName: "homeDailyPlayButton"
        x: hero.inset
        y: hero.height - hero.inset - height
        type: "filled"
        icon: "play_arrow"
        text: i18n.t("playlist.playAll")
        cornerRadius: Ui.radiusFull
        onClicked: hero.playRequested()
    }
}
