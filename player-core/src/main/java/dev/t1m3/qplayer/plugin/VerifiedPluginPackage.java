package dev.t1m3.qplayer.plugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable result of validating a .qplug before extraction or execution. */
public final class VerifiedPluginPackage {
    private final Path file;
    private final PluginManifest manifest;
    private final Map<String, String> hashes;
    private final boolean signed;
    private final String packageDigest;

    VerifiedPluginPackage(Path file, PluginManifest manifest, Map<String, String> hashes,
                          boolean signed, String packageDigest) {
        this.file = file;
        this.manifest = manifest;
        this.hashes = Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
        this.signed = signed;
        this.packageDigest = packageDigest;
    }

    public Path file() { return file; }
    public PluginManifest manifest() { return manifest; }
    public Map<String, String> hashes() { return hashes; }
    public boolean signed() { return signed; }
    public String packageDigest() { return packageDigest; }
}
