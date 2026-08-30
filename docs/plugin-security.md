# Plugin security model

Plugins are third-party code, not declarative data. QPlayer reduces their ambient
authority but cannot make a malicious plugin safe. Install only publishers you
trust and review every requested permission.

## Enforced boundaries

- Packages are verified before extraction. Archive paths, size limits, per-file
  SHA-256 hashes, optional publisher signatures, and catalog digests are checked.
- Installation is staged, preflights the exported handler table, and atomically
  activates the version. The registry records the exact package digest and grants;
  every activation re-hashes the extracted file set and disables a modified package.
- Each plugin uses a safe Rhino global scope and one serialized actor. Java package
  globals and host reflection are absent; relative modules cannot escape the package.
- CPU execution is instruction-observed and time-bounded. HTTP, decoded bodies,
  storage, lyrics, UI payloads, archives, and returned values have limits. JS-to-Java
  conversion rejects cycles, excessive nesting/items/text, and non-finite numbers.
- Network permission is deny-by-default. Scheme, declared method, hostname,
  wildcard rules, DNS results, and local/private addresses are checked. Host HTTP,
  artwork, and cached-audio downloads re-check every redirect. Direct platform
  playback validates the initial stream URL, while redirects performed internally
  by the platform media stack follow that platform's networking model.
- Persistent storage and encrypted credentials are namespaced by validated plugin
  ID and hashed logical key. A credential envelope embeds plugin ID and key, so a
  copied ciphertext fails namespace authentication.
- Optional plugin QML has its own safe engine realm and resource loader. It cannot
  see QPlayer's controller or shared context and can invoke only its contribution's
  `ui.*` handler. Native file/window types, shared style singletons, and inherited
  Java reflection methods are excluded; third-party bindings and handlers run in
  interpreted mode with an instruction-observed execution deadline.
- Disabling a plugin stops its actor and unregisters its network policy. A malformed
  registry is preserved for recovery and all plugins start disabled.

## Trust and signatures

A signature proves that the package bytes were produced by the holder of a
publisher key; it does not prove that the code is benign. The bundled catalog is
signed by a separate catalog key and pins the package digest and publisher public
key. Publisher and catalog private keys must never be stored in this repository.

Unsigned manual packages remain possible for development and independent
distribution. Their install prompt explicitly reports that the publisher is
unverified. QPlayer always asks for the full permission set before activation,
including for catalog packages.

## Credential limits

The credential vault protects files at rest with AES-GCM. Android Keystore,
macOS Keychain, Windows DPAPI, or Linux Secret Service/KWallet protects the data
key when available. A locked/unavailable store times out instead of blocking app
startup; the user can retry, discard inaccessible credentials and log in again, or
explicitly accept owner-only fallback encryption.

This does not defend against root/administrator access, process injection, a
debugger, reading QPlayer memory, or same-user malware that can access an already
unlocked platform store. Linux Secret Service and Windows DPAPI are primarily
user/session boundaries. See the README's static-file protection warning.

## Out of scope

- Provider legality, service terms, availability, and correctness.
- Preventing a granted network plugin from sending data it can legitimately read
  to one of its granted domains.
- DRM circumvention or distribution of copyrighted media. QPlayer provides no
  source endpoint, provider protocol, account, or media package.
- Perfect availability under hostile input. Limits make abuse bounded, but a plugin
  can still fail its own operations and be disabled by the user.

Security reports should include the plugin manifest, package digest, QPlayer
version, platform, and the smallest reproducible package. Do not attach real login
credentials.
