# QPlayer JavaScript plugin ABI v1

QPlayer core does not contain an online music source. A source is a user-installed
`.qplug` archive executed by the embedded Rhino engine. The current ABI version is
`1.0`; plugins can require a minimum QPlayer version with `minHostVersion`.

## Identity

Plugins return native IDs such as `2668056312`. The host owns canonical IDs and
converts them to:

```text
<provider>:<kind>:<percent-encoded-native-id>
```

Kinds are `song`, `album`, `artist`, `playlist`, `user`, and `room`. Native IDs are
limited to 2048 UTF-8 bytes and may not contain control characters. A plugin only
receives native IDs belonging to itself. It must never manufacture another
provider's canonical ID. Persisted queues, caches, playlist context, Together
snapshots, and UI navigation all use canonical IDs.

## Package layout

```text
plugin.json
src/main.js
ui/optional.qml
META-INF/qplayer-files.json
META-INF/qplayer.sig
```

`qplayer-files.json` maps every executable/resource path to a lowercase SHA-256
digest. `qplayer.sig` is the Base64 DER ECDSA/SHA-256 signature of the exact bytes
of that file, using a P-256 publisher key. Manual unsigned packages are supported
for development, but QPlayer shows a mandatory code-execution warning. Catalog
packages must match the signed catalog's digest, publisher key, ID, and version.
The staged runtime must implement every advertised capability, and installed files
are re-hashed before each activation so added or modified modules are not executed.

Minimal manifest:

```json
{
  "schemaVersion": 1,
  "id": "example",
  "name": "Example Source",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "minHostVersion": "1.4.0",
  "entry": "src/main.js",
  "capabilities": ["searchSongs", "resolveStream", "lyrics"],
  "permissions": ["network"],
  "networkDomains": ["api.example.com", "*.cdn.example.com"],
  "networkMethods": ["GET", "POST"],
  "ui": []
}
```

All paths are relative, cannot contain `..`, backslashes, schemes, or absolute
segments. IDs, capabilities, permissions, methods, domains, and UI contribution
IDs are validated before extraction or execution.

## Runtime

The entry module uses CommonJS syntax and exports `handlers`. `require()` can load
only relative `.js` files inside the verified package.

```js
module.exports = {
  handlers: {
    searchSongs: function (args) {
      return qplayer.call("http.request", {
        url: "https://api.example.com/search?q=" + encodeURIComponent(args.query),
        method: "GET"
      }).then(function (response) {
        return { items: [], nextCursor: "", hasMore: false };
      });
    }
  }
};
```

Handlers may return a value or a Promise. One plugin has one serialized actor
thread. A call is time-bounded; expensive loops are interrupted. Java classes,
`Packages`, native modules, arbitrary filesystem access, and a process-wide QML
context are not available.

## Capabilities and handlers

Each advertised capability maps to a same-named handler:

| Capability | Important arguments/result |
|---|---|
| `searchSongs` | `{query,cursor,limit}` → page of songs |
| `searchAlbums`, `searchArtists` | query page |
| `hotSearch` | array of strings |
| `home` | `{limit}` → `{songs,playlists}` |
| `songDetails` | `{ids}` → songs |
| `playlistDetails` | `{id}` → playlist with songs |
| `artistDetails`, `albumDetails` | `{id}` → detail with songs/albums |
| `recent`, `userPlaylists` | `{limit}` → array |
| `resolveStream` | `{id,quality}` → URL, headers, expiry, trial/cache policy |
| `lyrics` | `{id}` → lyric assets (`lrc`, `yrc`, or `ttml`) |
| `account` | account profile |
| `login` | operation `methods`, `begin`, `poll`, `submit`, or `logout` |
| `like` | operation `list` or `set` |
| `playlistMutation` | `add`, `remove`, `subscribe`, `delete`, or `create` |
| `scrobble` | playback report |
| `heartRecommendation` | seed song and optional playlist → songs |
| `share` | canonical entity → share URL/text |
| `listenTogether` | room create/status/check/join/snapshot/report/heartbeat/end |

The host validates response sizes, entity kinds, canonical ownership, URL grants,
pagination bounds, lyric size, headers, and enum values. See the source-neutral
DTOs in `player-core/src/main/java/dev/t1m3/qplayer/media` for the complete field
set and `PluginProviderService`, `PluginAccountService`, and
`PluginTogetherService` for the normative parser behavior.

## Host calls

Calls are asynchronous: `qplayer.call(method, arguments)` returns a Promise.

| Method family | Permission | Notes |
|---|---|---|
| `storage.get/put/delete` | none | 1 MiB, hashed key, plugin namespace |
| `credentials.get/put/delete` | `credentials` | encrypted, plugin namespace |
| `http.request` | `network` | HTTPS/domain/method/DNS/redirect policy; bounded body |
| `crypto.*` | none | digest, random, AES, HMAC, modular exponentiation, X25519 |
| `compression.gunzip` | none | bounded decompression |

Network URLs returned for playback and artwork are checked again by the host.
Host-fetched artwork and cached audio enforce the policy on every redirect. A
platform playback backend validates the initial stream URL; redirects performed
inside the platform media stack are subject to that platform's networking model.

## Login and credentials

Login presentation is generic. The `login` capability requires the `credentials`
permission. A plugin can expose QR, web, and pasted-credential methods. Web methods
also require `webAuth` and provide their HTTPS login URL, HTTPS cookie URL, and the cookie name that
signals completion; QPlayer opens the platform system WebView and returns only the
captured credential to that plugin. The plugin persists it through
`credentials.put`; QPlayer never interprets provider-specific cookies.

Credentials are AES-GCM encrypted under a shared installation data key but are
enveloped and stored under a cryptographically separated plugin/key namespace.
Logging out should delete the plugin's credential keys.

## Optional QML

Declare `customUi` and a contribution:

```json
{"id":"preferences","placement":"settings","source":"ui/Preferences.qml"}
```

The document opens in a separate safe Rhino realm and, on desktop, a separate
render window/thread. Its only bridge is `plugin`, with reactive `busy`,
`resultJson`, `error`, `revision`, and `call(action,payloadJson)`. Calls are routed
only to `ui.<contribution-id>`. Resources are confined to the verified package and
network images obey the plugin domain grant. It receives no `player`, settings,
Java, filesystem, or host-window object. Native file/window QML types and shared
process singletons are absent, inherited reflection methods are blocked, and the
host bridge exposes only its explicit `call` method. Safe-realm JavaScript is
instruction-observed and interrupted when one binding or handler overruns its limit.
Clipboard integration is absent unless the package requested and the user granted
`clipboard`; platform hosts may still choose not to provide it.

## Migration and compatibility

QPlayer reads v1 numeric queue/cache records and converts them to canonical IDs
only when the matching provider plugin is enabled. Writes use the new format and
are atomic. Old credential ciphertext and metadata remain available for one
rollback-compatible release after a successful plugin handoff; migration never
deletes the only readable copy first.

The official catalog is merely a signed list of independent projects. QPlayer
does not bundle or host those projects or their packages.

Installed sources can be enabled, disabled, selected as the primary source, or
removed from Settings. Removal stops the runtime and deletes executable package
files while retaining namespaced encrypted credentials/data for an intentional
reinstall; without an active runtime those namespaces are not exposed.
