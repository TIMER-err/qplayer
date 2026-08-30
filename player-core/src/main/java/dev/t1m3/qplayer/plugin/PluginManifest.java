package dev.t1m3.qplayer.plugin;

import dev.t1m3.qplayer.media.MediaId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Parsed {@code plugin.json}; all fields are data-only and Android-dexable. */
public final class PluginManifest {
    public static final int CURRENT_SCHEMA = 1;
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][A-Za-z0-9._-]+)?");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?:\\*\\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");

    public int schemaVersion;
    public String id = "";
    public String name = "";
    public String version = "";
    public String apiVersion = "";
    public String minHostVersion = "";
    public String entry = "main.js";
    public String publisher = "";
    public String icon = "";
    public List<String> capabilities = new ArrayList<>();
    public List<String> permissions = new ArrayList<>();
    public List<String> networkDomains = new ArrayList<>();
    public List<String> networkMethods = new ArrayList<>();
    public List<UiContribution> ui = new ArrayList<>();
    private static final int MAX_UI_CONTRIBUTIONS = 8;

    public static final class UiContribution {
        public String id = "";
        public String placement = "";
        /** Accepted and ignored: contributions described QML documents before
         *  dialogs became declarative. Kept so an older package still parses. */
        @Deprecated public String source = "";
        public String label = "";
        public String icon = "extension";
    }

    public void validate() {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported plugin schema " + schemaVersion);
        }
        MediaId.validateProvider(id);
        if ("local".equals(id)) throw new IllegalArgumentException("plugin id 'local' is reserved");
        if (name == null || name.trim().isEmpty() || name.length() > 80) {
            throw new IllegalArgumentException("invalid plugin name");
        }
        requireVersion("version", version);
        requireVersion("apiVersion", apiVersion);
        if (minHostVersion != null && !minHostVersion.isEmpty()) requireVersion("minHostVersion", minHostVersion);
        requirePackagePath("entry", entry, ".js");
        if (icon != null && !icon.isEmpty()) requirePackagePath("icon", icon, null);

        Set<String> capabilitySet = unique("capability", capabilities);
        for (String capability : capabilitySet) ProviderCapability.fromWireName(capability);
        Set<String> permissionSet = unique("permission", permissions);
        for (String permission : permissionSet) PluginPermission.fromWireName(permission);
        if (capabilitySet.contains(ProviderCapability.LOGIN.wireName())
                && !permissionSet.contains(PluginPermission.CREDENTIALS.wireName())) {
            throw new IllegalArgumentException("login capability requires credentials permission");
        }
        if (networkDomains == null) throw new IllegalArgumentException("network domain list is null");
        if (!networkDomains.isEmpty() && !permissionSet.contains(PluginPermission.NETWORK.wireName())) {
            throw new IllegalArgumentException("networkDomains requires network permission");
        }
        for (String raw : networkDomains) {
            String domain = raw != null ? raw.toLowerCase(Locale.ROOT) : "";
            if (!DOMAIN.matcher(domain).matches()) {
                throw new IllegalArgumentException("invalid network domain: " + raw);
            }
        }
        if (networkMethods == null) throw new IllegalArgumentException("network method list is null");
        Set<String> methods = unique("network method", networkMethods);
        for (String method : methods) {
            if (!method.matches("GET|POST|PUT|PATCH|DELETE|HEAD")) {
                throw new IllegalArgumentException("invalid network method: " + method);
            }
        }
        if (ui == null) throw new IllegalArgumentException("UI contribution list is null");
        Set<String> contributionIds = new LinkedHashSet<>();
        for (UiContribution contribution : ui) {
            if (contribution == null || contribution.id == null
                    || !contribution.id.matches("[a-z][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalid UI contribution id");
            }
            if (!contributionIds.add(contribution.id)) {
                throw new IllegalArgumentException("duplicate UI contribution " + contribution.id);
            }
            if (contribution.placement == null
                    || !contribution.placement.matches("[a-z][a-zA-Z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalid UI placement");
            }
            if (contribution.label == null || contribution.label.length() > 40) {
                throw new IllegalArgumentException("invalid UI contribution label");
            }
            if (contribution.icon == null
                    || !contribution.icon.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("invalid UI contribution icon");
            }
        }
        if (!ui.isEmpty() && !permissionSet.contains(PluginPermission.CUSTOM_UI.wireName())) {
            throw new IllegalArgumentException("UI contributions require customUi permission");
        }
        if (ui.size() > MAX_UI_CONTRIBUTIONS) {
            throw new IllegalArgumentException("too many UI contributions");
        }
    }

    public Set<ProviderCapability> capabilitySet() {
        Set<ProviderCapability> out = new LinkedHashSet<>();
        for (String value : capabilities) out.add(ProviderCapability.fromWireName(value));
        return Collections.unmodifiableSet(out);
    }

    public Set<PluginPermission> permissionSet() {
        Set<PluginPermission> out = new LinkedHashSet<>();
        for (String value : permissions) out.add(PluginPermission.fromWireName(value));
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> unique(String label, List<String> values) {
        if (values == null) throw new IllegalArgumentException(label + " list is null");
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || !result.add(value)) {
                throw new IllegalArgumentException("invalid or duplicate " + label + ": " + value);
            }
        }
        return result;
    }

    private static void requireVersion(String label, String value) {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
    }

    public static void requirePackagePath(String label, String value, String suffix) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.startsWith("\\")
                || value.contains(":") || value.contains("\\") || value.contains("//")) {
            throw new IllegalArgumentException("invalid " + label + " path: " + value);
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("invalid " + label + " path: " + value);
            }
        }
        if (suffix != null && !value.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            throw new IllegalArgumentException(label + " must end with " + suffix);
        }
    }
}
