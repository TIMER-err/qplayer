import QtQuick

// The single list of secondary destinations and tools (recent, downloads, plugin
// player actions, sleep timer, desktop lyrics, account, settings). The sidebar,
// the compact overflow menu and any future surface read `items` and call
// `trigger(index)`, so labels, icons, order and behaviour cannot drift apart.
QtObject {
    id: actions
    property var pageManager

    readonly property bool desktopLyricAvailable: settings.has("desktopLyricEnabled")

    // route: the navigation route an entry opens, used to highlight it while open.
    property var items: {
        var out = [
            { action: "recent", icon: "history", text: i18n.t("nav.recent"), route: "recent" },
            { action: "download", icon: "download", text: i18n.t("nav.downloaded"), route: "cachedSongs" }
        ]
        var rows = player.pluginUiContributions || []
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].placement !== "playerAction") continue
            out.push({ action: "plugin", icon: rows[i].icon, text: rows[i].label, route: "",
                       pluginId: rows[i].pluginId, contributionId: rows[i].id })
        }
        out.push({ action: "sleepTimer", icon: "bedtime", text: i18n.t("sleepTimer.title"), route: "" })
        return out
    }

    // Account and settings sit apart from the tools (sidebar footer / menu end).
    property var accountItem: ({ action: "account",
                                 icon: player.loggedIn ? "account_circle" : "login",
                                 text: player.loggedIn && player.userName.length > 0
                                       ? player.userName
                                       : i18n.t(player.loggedIn ? "account.title" : "nav.signIn"),
                                 route: "account" })
    property var settingsItem: ({ action: "settings", icon: "settings",
                                  text: i18n.t("nav.settings"), route: "settings" })

    function run(entry) {
        if (!entry) return
        var pm = actions.pageManager
        if (entry.action === "recent") {
            pm.replacePage("recent", 0)
        } else if (entry.action === "download") {
            player.refreshCachedSongs()
            pm.replacePage("cachedSongs", 0)
        } else if (entry.action === "plugin") {
            player.requestPluginUi(entry.pluginId, entry.contributionId)
        } else if (entry.action === "sleepTimer") {
            pm.sleepTimerOpen = true
        } else if (entry.action === "desktopLyric") {
            settings.setValue("desktopLyricEnabled", !settings.value("desktopLyricEnabled"))
        } else if (entry.action === "account") {
            // Also when signed out: the account page lists every installed source
            // and signing in lives behind each row.
            pm.replacePage("account", 0)
        } else if (entry.action === "settings") {
            pm.replacePage("settings", 0)
        }
    }

    // Full ordered list for one-column surfaces such as the compact menu.
    function menuEntries() {
        var out = []
        for (var i = 0; i < actions.items.length; i++) out.push(actions.items[i])
        if (actions.desktopLyricAvailable)
            out.push({ action: "desktopLyric", icon: "subtitles",
                       text: i18n.t("settings.desktopLyric.title"), route: "" })
        out.push(actions.accountItem)
        out.push(actions.settingsItem)
        return out
    }

    function runner(entry) { return function() { actions.run(entry) } }

    // Miuix Menu model (each row carries its own action closure).
    function menuModel() {
        var entries = actions.menuEntries()
        var out = []
        for (var i = 0; i < entries.length; i++)
            out.push({ text: entries[i].text, icon: entries[i].icon,
                       action: actions.runner(entries[i]) })
        return out
    }
}
