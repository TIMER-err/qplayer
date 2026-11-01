# Plugin security model

Plugins are third-party code, not declarative data. QPlayer reduces their ambient
authority but cannot make a malicious plugin safe. Install only publishers you
trust and review every requested permission.

This describes QPlayer 1.9.6 with plugin ABI `1.0`. The
[plugin API reference](plugins.md) defines handler arguments, result fields and
host calls. `minHostVersion` selects the host versions a package can run on; it
does not grant permissions or prove that a provider's responses are correct.

## Enforced boundaries

- Packages are verified before extraction. Archive paths, size limits, per-file
  SHA-256 hashes and optional publisher signatures are checked.
- Installation is staged, preflights the exported handler table, and atomically
  activates the version. The registry records the exact package digest and grants;
  every activation re-hashes the extracted file set and disables a modified package.
  Host calls are denied during staged initialization; modules should declare
  handlers there and perform network or storage work when a handler is invoked.
- Each plugin uses a safe Rhino global scope and one serialized actor. Java package
  globals and host reflection are absent; relative modules cannot escape the package.
- CPU execution is instruction-observed and each runtime call has a 90-second
  deadline. Response conversion rejects cycles, non-finite numbers, depth above
  32, more than 500,000 nodes and over 24 Mi UTF-16 code units of string values.
  A JavaScript module is limited to 4 MiB. These are per-call or per-module
  limits, not a total memory quota for the plugin.
  `http.request` caps both its input body and response bytes at 16 MiB; a body
  automatically decoded from gzip is also capped at 16 MiB. A `storage.put`
  value, including its serialized key/value envelope, is capped at 1 MiB per
  key. This is not a 1 MiB quota for the whole plugin namespace.
- Network permission is deny-by-default. Scheme, declared method, hostname,
  wildcard rules, DNS results, and local/private addresses are checked. Host HTTP,
  artwork, and `files.save` downloads re-check every redirect. Cached-audio and
  explicit song downloads follow the playback model: the initial stream URL must
  sit inside the plugin grant, then CDN hops are followed without re-checking
  `networkDomains`. Direct platform playback validates the initial stream URL,
  while redirects performed internally by the platform media stack follow that
  platform's networking model.
- `files.save` is a separate grant. The plugin supplies a URL already inside its
  network grant and a suggested file name; the host shows a save dialog and
  writes the bytes. The plugin never receives a filesystem path.
- `files.downloadCurrent` uses the same `files.save` permission but saves the
  host's current track through its quality selector and save dialog. The current
  track's source supplies the stream and network grant; it need not be the
  plugin that requested the action.
- `webAuth` is a separate browser-code grant, not a variant of `http.request`.
  The host validates `originUrl` against the active network grant; an `http` URL
  also needs `clearTextNetwork`. Android creates an empty HTML page with that
  base URL, while desktop navigates to the supplied URL before script injection.
  Each platform allows one active script session and closes it after a result,
  failure or a 45-second timeout. Script and result limits are 256 Ki and 64 Ki
  UTF-16 code units. Browser subresources, redirects and requests do not
  pass through the host HTTP domain/DNS/redirect checks.
- Login `appUrl` values are opened by the host after a user tap. Custom schemes are
  restricted to a host allowlist of official music/SSO apps; `https` still needs the
  plugin network grant. `file`/`content`/`intent`/`javascript` are rejected.
- A web login uses `credentialCookieName` to detect completion, then gives the
  plugin the complete Cookie header for `cookieUrl`, not just the named cookie.
  Treat that header as a credential: do not log it or include it in UI payloads.
- Persistent storage and encrypted credentials are namespaced by validated plugin
  ID and hashed logical key. A credential envelope embeds plugin ID and key, so a
  copied ciphertext fails namespace authentication.
- Plugin dialogs contain no executable plugin QML. The host normalizes their
  description and renders it with its own components. Unknown fields are
  discarded; invalid known fields, unsupported node types and exceeded limits
  reject the description. Only normalized fields reach the UI, so extra colors,
  images, markup or geometry cannot extend the dialog schema.
- Enablement is stored per validated plugin ID. The settings page reloads that
  entry when it is reused for another plugin; toggling one entry cannot carry its
  switch state into another plugin.
- Disabling a plugin stops its actor, stops scheduling its background ticks and
  unregisters its network policy. It does not roll back changes or guarantee
  cancellation of host operations already accepted before disablement. Other
  plugins keep their own runtimes and policies.
  A malformed registry is preserved for recovery and all plugins start disabled.

## Trust and signatures

A signature proves that the package bytes were produced by the holder of a
publisher key; it does not prove that the code is benign. The bundled
`player-core/src/main/resources/dev/t1m3/qplayer/plugin/plugin-catalog.json`
pins each known plugin ID to its GitHub `owner/repo` and publisher public key.
A package offered through that catalog must verify against the pinned key.
This signature check also applies when a GitHub download uses a third-party
mirror. Publisher private keys must never be stored in this repository.

Unsigned manual packages remain possible for development and independent
distribution. Their install prompt explicitly reports that the publisher is
unverified. QPlayer always asks for the full permission set before activation,
including for packages resolved from a built-in source.

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
