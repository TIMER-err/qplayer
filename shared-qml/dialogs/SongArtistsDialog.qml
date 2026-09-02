import QtQuick
import md3.Core
import "../components"

// Multi-artist chooser shared by SongRow clicks and the song context menu.
// Wrap the standard MD3 Dialog instead of recreating its scrim, surface,
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
        id: picker
        icon: "group"
        title: "选择歌手"
        showAcceptButton: false
        rejectText: "取消"
        onClosed: {
            if (player.songArtistPickerOpen)
                player.closeSongArtistPicker()
        }

        Flickable {
            id: list
            width: parent.width
            height: Math.min(control.rowsHeight, 320)
            contentWidth: width
            contentHeight: control.rowsHeight
            clip: true

            Item {
                width: list.width
                height: list.contentHeight

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
}
