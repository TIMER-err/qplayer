import QtQuick
import miuix.Core

// Ports miuix basic/Badge.kt's BadgedBox: the box takes the anchor's size and the
// badge hangs off its top-end corner. Upstream places the badge's *leading* edge at
// `anchorWidth - offset` and its top at `-badgeHeight + offset`, so the badge
// deliberately overflows the anchor.
Item {
    id: badgedBoxRoot

    property Component badge: null
    // Optional bounds in this anchor's coordinates, matching upstream top/end rulers.
    property var badgeBounds: null
    default property alias content: anchorContainer.data

    implicitWidth: anchorContainer.childrenRect.width
    implicitHeight: anchorContainer.childrenRect.height

    Item {
        id: anchorContainer
        anchors.fill: parent
    }

    Loader {
        id: badgeLoader
        objectName: "miuixBadgePlacement"
        sourceComponent: badgedBoxRoot.badge
        width: item ? item.implicitWidth : 0
        height: item ? item.implicitHeight : 0
        // BadgeDefaults.Size is 6dp; anything wider carries content and uses the
        // 12/14dp offsets instead of the 6/6dp dot offsets.
        readonly property bool hasContent: width > 6
        x: badgedBoxRoot.badgeBounds
            ? Math.min(badgedBoxRoot.width - (hasContent ? 12 : 6), badgedBoxRoot.badgeBounds.x + badgedBoxRoot.badgeBounds.width - width)
            : badgedBoxRoot.width - (hasContent ? 12 : 6)
        y: badgedBoxRoot.badgeBounds ? Math.max(-height + (hasContent ? 14 : 6), badgedBoxRoot.badgeBounds.y)
            : -height + (hasContent ? 14 : 6)
        z: 1
    }
}
