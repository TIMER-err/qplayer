package dev.t1m3.qplayer.plugin;

/** QML-facing entry from QPlayer's signed plugin catalog. */
public final class PluginCatalogEntry {
    public String id = "";
    public String name = "";
    public String description = "";
    public String version = "";
    public String minHostVersion = "";
    public String homepage = "";
    public String downloadUrl = "";
    public String sha256 = "";
    public String publisherKey = "";
    public boolean installed;
    public String installedVersion = "";
    public boolean updateAvailable;
}
