import QtQuick
import miuix.Core
import design
import "."

// Shared hero for playlist, album and artist pages: an accent wash, large cover,
// kind label, title, linked subtitle, meta line and an action row.
//
// The height is deterministic (see preferredHeight) because detail pages feed it
// to VirtualSongList.headerHeight before the header delegate exists. Wide layouts
// bottom-align the text block against the cover; compact layouts stack it and
// reserve two title lines so one- and two-line titles share the same geometry.
Item {
    id: hero
    property string coverSource: ""
    property string coverIcon: "album"
    property bool round: false
    property string kind: ""
    property string title: ""
    property string subtitle: ""
    property bool subtitleLink: false
    property string meta: ""
    property bool compact: width < Ui.compactMax
    property real gutter: Ui.gutter(width)
    // Space reserved above the hero for the overlaid RouteHeader.
    property real topInset: Ui.headerHeight
    default property alias actions: actionRow.data
    signal subtitleClicked()

    readonly property int coverSize: compact ? Ui.heroCoverCompact : Ui.heroCoverWide
    readonly property int titleSize: compact ? Ui.textTitle1 : Ui.textDisplay
    readonly property int titleLine: Math.round(titleSize * 1.3)
    readonly property real textBand: titleLine * 2
                                     + (subtitle.length > 0 ? 24 : 0)
                                     + (meta.length > 0 ? 20 : 0)
    readonly property real preferredHeight: compact
        ? topInset + 8 + coverSize + 18 + textBand + 16 + Ui.iconButton + 24
        : topInset + 8 + coverSize + 32

    height: preferredHeight

    // Accent wash fading into the page surface.
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: Ui.accentAlpha(Ui.dark ? 0.30 : 0.18) }
            GradientStop { position: 0.75; color: Ui.accentAlpha(Ui.dark ? 0.06 : 0.04) }
            GradientStop { position: 1.0; color: Ui.surfaceAlpha(0) }
        }
    }

    CoverImage {
        id: cover
        objectName: "heroCover"
        width: hero.coverSize
        height: hero.coverSize
        x: hero.compact ? (hero.width - width) / 2 : hero.gutter
        y: hero.topInset + 8
        radius: hero.round ? hero.coverSize / 2 : Ui.radiusLg
        icon: hero.coverIcon
        iconSize: Math.round(hero.coverSize * 0.32)
        fadeIn: true
        source: hero.coverSource
    }

    // Text block. Wide: right of the cover, bottom-aligned with the action row.
    // Compact: centred under the cover inside the reserved band.
    Item {
        id: textBlock
        x: hero.compact ? hero.gutter : cover.x + cover.width + 28
        width: Math.max(0, hero.width - x - hero.gutter)
        height: blockHeight
        readonly property real blockHeight: (kindText.visible ? kindText.implicitHeight + 4 : 0)
                                            + titleText.implicitHeight
                                            + (subtitleText.visible ? subtitleText.implicitHeight + 6 : 0)
                                            + (metaText.visible ? metaText.implicitHeight + 4 : 0)
        y: hero.compact
           ? cover.y + cover.height + 18 + Math.max(0, (hero.textBand - blockHeight) / 2)
           : actionRow.y - 18 - blockHeight

        Text {
            id: kindText
            objectName: "heroKind"
            width: parent.width
            visible: !hero.compact && hero.kind.length > 0
            text: hero.kind
            color: Ui.textSecondary
            font.pixelSize: Ui.textCaption
            font.weight: Ui.weightBold
            elide: Text.ElideRight
        }
        Text {
            id: titleText
            objectName: "heroTitle"
            y: kindText.visible ? kindText.implicitHeight + 4 : 0
            width: parent.width
            text: hero.title
            color: Ui.textPrimary
            horizontalAlignment: hero.compact ? Text.AlignHCenter : Text.AlignLeft
            wrapMode: Text.Wrap
            maximumLineCount: 2
            elide: Text.ElideRight
            font.pixelSize: hero.titleSize
            font.weight: Ui.weightBold
        }
        Text {
            id: subtitleText
            objectName: "heroSubtitle"
            y: titleText.y + titleText.implicitHeight + 6
            width: Math.min(parent.width, implicitWidth)
            x: hero.compact ? (parent.width - width) / 2 : 0
            visible: hero.subtitle.length > 0
            text: hero.subtitle
            color: hero.subtitleLink ? Ui.accent : Ui.textSecondary
            font.pixelSize: Ui.textBody
            font.weight: hero.subtitleLink ? Ui.weightBold : Ui.weightRegular
            elide: Text.ElideRight
            maximumLineCount: 1
            MouseArea {
                anchors.fill: parent
                enabled: hero.subtitleLink
                hoverEnabled: enabled
                cursorShape: Qt.PointingHandCursor
                onClicked: hero.subtitleClicked()
            }
        }
        Text {
            id: metaText
            objectName: "heroMeta"
            y: (subtitleText.visible ? subtitleText.y + subtitleText.implicitHeight
                                     : titleText.y + titleText.implicitHeight) + 4
            width: parent.width
            visible: hero.meta.length > 0
            text: hero.meta
            color: Ui.textTertiary
            horizontalAlignment: hero.compact ? Text.AlignHCenter : Text.AlignLeft
            font.pixelSize: Ui.textCaption
            elide: Text.ElideRight
            maximumLineCount: 1
        }
    }

    Row {
        id: actionRow
        objectName: "heroActions"
        height: Ui.iconButton
        spacing: 8
        x: hero.compact ? (hero.width - width) / 2 : textBlock.x
        y: hero.compact
           ? cover.y + cover.height + 18 + hero.textBand + 16
           : cover.y + cover.height - height
    }
}
