package dev.t1m3.qplayer.plugin;

/** QML-facing entry describing the latest release of a known plugin repository. */
public final class PluginCatalogEntry {
    public String id = "";
    public String name = "";
    public String description = "";
    public String version = "";
    public String minHostVersion = "";
    public String homepage = "";
    public String downloadUrl = "";
    public String publisherKey = "";
    public boolean installed;
    public String installedVersion = "";
    public boolean updateAvailable;
}
