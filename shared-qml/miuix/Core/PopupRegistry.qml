pragma Singleton
import QtQuick

QtObject {
    property var menus: []
    function claim(host, menu) {
        var previous = menus
        var next = []
        for (var i = 0; i < previous.length; i++) {
            var entry = previous[i]
            if (entry.host === host) {
                if (entry.menu !== menu) entry.menu.dismissImmediately()
            } else next.push(entry)
        }
        next.push({host: host, menu: menu})
        menus = next
    }
    function release(menu) {
        var next = []
        for (var i = 0; i < menus.length; i++) if (menus[i].menu !== menu) next.push(menus[i])
        menus = next
    }
}
