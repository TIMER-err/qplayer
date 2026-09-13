import QtQuick
import miuix.Core
import "../components"

// Multi-artist chooser shared by SongRow clicks and the song context menu.
// Wrap the Miuix Dialog instead of recreating its scrim, surface,
// typography, motion and action area here. A single credit bypasses this view
// in PlayerController, so the content always represents a real choice.
Item {
    id: control

    property bool active: player.songArtistPickerOpen
    property var artists: player.songArtistPickerList || []
    property real rowH: 64
    property real rowsHeight: artists.length * rowH

    onActiveChanged: {
        if (active) picker.open()
        else if (picker.opened) picker.close()
    }
    Component.onCompleted: if (active) picker.open()

    Dialog {

        topInset: settings.topInset

        bottomInset: settings.bottomInset
        id: picker
        icon: "group"
        title: i18n.t("song.artists.title")
        showAcceptButton: false
        rejectText: i18n.t("common.cancel")
        onClosed: {
            if (player.songArtistPickerOpen)
                player.closeSongArtistPicker()
        }

        // No Flickable of its own: Dialog scrolls its body once the list pushes
        // it past the screen, and a nested scroller would just fight that one
        // for the drag. Repeater rows still need explicit y -- qml4j does not
        // position dynamically created children the way a Qt positioner does.
        Item {
            id: list
            width: parent.width
            height: control.rowsHeight

            Repeater {
                model: control.artists
                ArtistRow {
                    width: list.width
                    height: control.rowH
                    y: index * control.rowH
                    artistId: modelData.id
                    name: modelData.name
                    coverUrl: modelData.coverUrl || ""
                    coverThumbPath: modelData.coverThumbPath || ""
                    onActivated: {
                        player.closeSongArtistPicker()
                        if (modelData.mediaId)
                            player.openMediaArtist(modelData.mediaId)
                        else
                            player.openArtist(modelData.id)
                    }
                }
            }
        }
    }
}
